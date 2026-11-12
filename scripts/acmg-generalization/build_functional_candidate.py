#!/usr/bin/env python3
"""Build disabled functional candidates from pinned, independently reviewed inputs.

Standard library only. No network, model calls, policy activation, or clinical
classification. The input manifest supplies paths and byte SHA-256 values;
scientific content comes exclusively from the separately pinned source review.
See functional-candidate-builder.md for the input and review boundaries.
"""
import argparse
import copy
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zipfile


VERSION = "1.0.0"
MAX_INPUT = 64 * 1024 * 1024
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
AA = dict(zip("ACDEFGHIKLMNPQRSTVWY", (
    "Ala Cys Asp Glu Phe Gly His Ile Lys Leu Met Asn Pro Gln Arg Ser Thr Val Trp Tyr".split())))
# NCBI standard genetic code in T,C,A,G order; this is sequence mechanics,
# never a gene-specific evidence threshold or functional interpretation.
CODONS = dict(zip((a + b + c for a in "TCAG" for b in "TCAG" for c in "TCAG"),
                  "FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG"))
COLUMNS = {"proteinVariantKey", "proteinVariantThreeLetter", "mutationType", "wildTypeCodon",
           "experimentalMutantCodon", "measuredScore", "highConfidence", "imputedScore"}
METHOD_FIELDS = set(("methodId assayId studyId conditionId readout unit mechanismId evidenceDomain "
                     "constructTransfer constructDescription constructTransferRationale calibrationId "
                     "calibrationStatus calibrationRationale maximumStrength biologicalReplicatesDescription "
                     "combination spliceConsequenceHandling sourceIds reviewId sourceColumns evidenceOrigin").split())


class InputError(ValueError):
    pass


def require(ok, code):
    if not ok:
        raise InputError(code)


def only(value, keys, code):
    require(isinstance(value, dict) and set(value) <= set(keys), code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    """Match RuntimeEvidenceV2Adapter: NFC values, Java UTF-16 key order, no floats.

    Reject normalized-key collisions rather than silently overwriting an input.
    Arrays keep order. Source measurements are decimal strings, never JSON floats.
    """
    if value is None or type(value) in (bool, int):
        return value
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if isinstance(value, list):
        return [canonical(item) for item in value]
    require(isinstance(value, dict), "CANONICAL_FLOAT_OR_TYPE_UNSUPPORTED")
    result = {}
    for key in sorted(value, key=lambda s: s.encode("utf-16-be")):
        normalized = unicodedata.normalize("NFC", key)
        require(normalized not in result, "CANONICAL_KEY_COLLISION")
        result[normalized] = canonical(value[key])
    return result


def canonical_bytes(value):
    return json.dumps(canonical(value), ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def canonical_sha(value):
    return sha(canonical_bytes(value))


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "JSON_DUPLICATE_KEY")
        result[key] = value
    return result


def parse_json(data, allow_decimal=False):
    def unsupported(_):
        raise InputError("JSON_NONINTEGRAL_NUMBER_FORBIDDEN")
    return json.loads(data, object_pairs_hook=unique_object,
                      parse_float=Decimal if allow_decimal else unsupported,
                      parse_constant=unsupported)


def read_pinned(binding):
    only(binding, {"path", "sha256"}, "ARTIFACT_BINDING_UNKNOWN_FIELD")
    require(re.fullmatch(r"[0-9a-f]{64}", binding.get("sha256", "")), "ARTIFACT_SHA_MISSING")
    path = Path(binding["path"])
    require(path.is_file() and path.stat().st_size <= MAX_INPUT, "ARTIFACT_MISSING_OR_TOO_LARGE")
    data = path.read_bytes()
    require(sha(data) == binding["sha256"], "ARTIFACT_BYTE_SHA_MISMATCH:" + path.name)
    return data


def split_version(value):
    match = re.fullmatch(r"([A-Za-z0-9_]+)\.([1-9][0-9]*)", value)
    require(match is not None, "EXACT_VERSIONED_ACCESSION_REQUIRED")
    return {"accession": match[1], "version": int(match[2])}


def source_record(source):
    version = source.get("version") or ("DOI:" + source["doi"] if source.get("doi") else None)
    require(version and source.get("sourceId") and source.get("url", "").startswith("https://")
            and re.fullmatch(r"[0-9a-f]{64}", source.get("sha256", "")), "SOURCE_IDENTITY_INCOMPLETE")
    return {key: source[key] for key in ("sourceId", "url", "sha256")} | {"version": version}


def verify_source_files(sources, bindings):
    result = {}
    for source in sources:
        key = source["sourceId"]
        if key in result:
            require(result[key] == source, "SOURCE_ID_COLLISION")
            continue
        require(key in bindings, "SOURCE_FILE_BINDING_MISSING:" + key)
        require(bindings[key]["sha256"] == source["sha256"], "SOURCE_DECLARED_SHA_MISMATCH")
        read_pinned(bindings[key])
        result[key] = source
    require(set(bindings) == set(result), "UNDECLARED_SOURCE_FILE")
    return list(result.values())


def verify_workbook(data, raw):
    """Compare exact typed cells, including original OOXML storage, not formatted values."""
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as workbook:
        require(sum(item.file_size for item in workbook.infolist()) <= 128 * 1024 * 1024,
                "WORKBOOK_EXPANSION_TOO_LARGE")
        strings = []
        if "xl/sharedStrings.xml" in workbook.namelist():
            root = ET.fromstring(workbook.read("xl/sharedStrings.xml"))
            strings = ["".join(node.itertext()) for node in root.findall("s:si", NS)]
        rels = {r.attrib["Id"]: r.attrib["Target"] for r in
                ET.fromstring(workbook.read("xl/_rels/workbook.xml.rels"))}
        sheets = {}
        for sheet in ET.fromstring(workbook.read("xl/workbook.xml")).findall("s:sheets/s:sheet", NS):
            target = rels[sheet.attrib["{" + REL_NS + "}id"]]
            sheets[sheet.attrib["name"]] = target.lstrip("/") if target.startswith("/") else "xl/" + target
        expected = {}
        for row in raw["rows"]:
            for column, value in row["sourceCells"].items():
                locator = (row["sheet"], column + str(row["row"]))
                require(locator not in expected, "DUPLICATE_SOURCE_CELL")
                expected[locator] = value
        for row in raw.get("readme", []):
            for column, value in row["cells"].items():
                locator = (raw["readmeSheet"], column + str(row["row"]))
                require(locator not in expected, "DUPLICATE_SOURCE_CELL")
                expected[locator] = value
        found = set()
        for sheet_name in {key[0] for key in expected}:
            require(sheet_name in sheets, "WORKBOOK_SHEET_MISSING")
            with workbook.open(sheets[sheet_name]) as stream:
                for _, node in ET.iterparse(stream, events=("end",)):
                    if node.tag != "{" + NS["s"] + "}c":
                        continue
                    locator = (sheet_name, node.attrib.get("r"))
                    if locator in expected:
                        require(locator not in found, "DUPLICATE_WORKBOOK_CELL")
                        require(node.find("s:f", NS) is None, "FORMULA_NOT_MEASURED_CELL")
                        kind = node.attrib.get("t", "n")
                        stored = node.findtext("s:v", namespaces=NS)
                        require(kind in ("s", "b", "n") and stored is not None, "CELL_ENCODING_UNSUPPORTED")
                        if kind == "s":
                            require(stored.isdigit() and int(stored) < len(strings), "SHARED_STRING_INVALID")
                            value = strings[int(stored)]
                        else:
                            value = stored
                        require({"type": kind, "raw": value, "xmlStorageValue": stored} == expected[locator],
                                "SOURCE_CELL_MISMATCH:" + sheet_name + "!" + locator[1])
                        found.add(locator)
                    node.clear()
        require(found == set(expected), "SOURCE_CELL_MISSING")
        return len(found)


def validate_review(review):
    only(review, ("schemaVersion inputId recordedAt role sourceRoot review inputArtifacts scope nativeModels "
                  "reviewedBridge assay method pendingBindings activation developmentExpectedInputs").split(),
         "REVIEW_UNKNOWN_CONDITION")
    require(review["schemaVersion"] == "reviewed-functional-construction-inputs-v1"
            and review["review"]["status"] == "REVIEWED_SOURCE_AND_BOUNDED_METHOD"
            and review["review"].get("reviewId") and review["review"].get("reviewer"), "SOURCE_REVIEW_REQUIRED")
    require(set(review["activation"]) == {"defaultEnabled", "classificationEnabled", "adoptionSupported", "clinicalAcceptance"}
            and all(value is False for value in review["activation"].values()), "ACTIVATION_FORBIDDEN")
    only(review["scope"], ("hgncId geneSymbol mondoId moi assembly variantType supportedVariantEffects "
                          "startCodonPolicy").split(), "SCOPE_OPERATOR_UNSUPPORTED")
    scope, assay, method = review["scope"], review["assay"], review["method"]
    require(all(isinstance(scope.get(k), str) and scope[k].strip() for k in ("hgncId", "geneSymbol", "mondoId", "moi", "assembly")),
            "SCOPE_INCOMPLETE")
    require(scope["variantType"] == "NATIVE_NONPROJECTED_SNV" and scope["supportedVariantEffects"] == ["MISSENSE"]
            and scope["startCodonPolicy"] == "EXCLUDE", "EFFECT_OPERATOR_UNSUPPORTED")
    only(assay, {"representation", "unit", "qualityGate", "sourceId", "sourceColumns", "threshold"},
         "ASSAY_OPERATOR_UNSUPPORTED")
    only(assay["threshold"], {"operator", "decimal", "strength"}, "THRESHOLD_OPERATOR_UNSUPPORTED")
    require(assay["representation"] == "MEASURED_SCALAR" and assay["qualityGate"] == "EXCEL_BOOLEAN_TRUE"
            and assay["threshold"]["operator"] in ("LTE", "LT", "GTE", "GT")
            and assay["threshold"]["strength"] in ("SUPPORTING", "MODERATE", "STRONG"), "ASSAY_OPERATOR_UNSUPPORTED")
    require(isinstance(assay["unit"], str) and assay["unit"].strip()
            and isinstance(assay["threshold"]["decimal"], str)
            and re.fullmatch(r"[-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?", assay["threshold"]["decimal"])
            and Decimal(assay["threshold"]["decimal"]).is_finite(), "FINITE_DECIMAL_THRESHOLD_REQUIRED")
    require(set(assay["sourceColumns"]) == COLUMNS and len(set(assay["sourceColumns"].values())) == len(COLUMNS)
            and all(re.fullmatch(r"[A-Z]{1,3}", c) for c in assay["sourceColumns"].values()), "SOURCE_COLUMNS_INVALID")
    only(method, METHOD_FIELDS, "METHOD_OPERATOR_UNSUPPORTED")
    require(set(method) == METHOD_FIELDS and all(method[k] for k in METHOD_FIELDS), "METHOD_REVIEW_INCOMPLETE")
    require(all(isinstance(method[k], str) and method[k].strip() for k in METHOD_FIELDS - {"sourceIds", "sourceColumns"})
            and isinstance(method["sourceIds"], list) and all(isinstance(s, str) and s for s in method["sourceIds"]), "METHOD_REVIEW_INCOMPLETE")
    require(method["conditionId"] == scope["mondoId"] and method["unit"] == assay["unit"]
            and method["sourceColumns"] == assay["sourceColumns"]
            and method["maximumStrength"] == assay["threshold"]["strength"], "METHOD_SCOPE_OR_MEASUREMENT_MISMATCH")
    for key, expected in {"evidenceDomain": "PROTEIN_FUNCTION", "evidenceOrigin": "PUBLISHED_EXPERIMENTAL_MEASUREMENT",
                          "constructTransfer": "REVIEWED_SAME_PROTEIN_CHANGE", "calibrationStatus": "VCEP_NAMED_ASSAY",
                          "combination": "SINGLE_ASSAY_NO_STRENGTH_ADDITION", "spliceConsequenceHandling": "ABSTAIN"}.items():
        require(method[key] == expected, "METHOD_OPERATOR_UNSUPPORTED:" + key)


def validate_models(review, model_bindings):
    require(len(model_bindings) == len(review["nativeModels"]) > 0, "MODEL_SET_INCOMPLETE")
    models = {}
    for declared in review["nativeModels"]:
        tx = declared["transcript"]
        require(tx in model_bindings and tx not in models, "MODEL_BINDING_MISSING_OR_DUPLICATE")
        model = parse_json(read_pinned(model_bindings[tx]))
        require(canonical_sha(model) == declared["canonicalSha256"], "REVIEWED_MODEL_SHA_MISMATCH")
        require(model.get("schemaVersion") in ("pvs1-transcript-model-v1", "native-coding-transcript-model-v1")
                and model.get("modelId") and model.get("modelVersion"), "NATIVE_MODEL_SCHEMA_INVALID")
        require(model["transcript"] == tx and model["protein"] == declared["protein"], "MODEL_ACCESSION_MISMATCH")
        split_version(tx)
        split_version(model["protein"])
        for key in ("hgncId", "geneSymbol", "assembly"):
            require(model[key] == review["scope"][key], "MODEL_SCOPE_MISMATCH")
        cds = model["cdsSequence"]
        require(re.fullmatch("[ACGT]+", cds) and type(model["cdsLength"]) is int and type(model["proteinLength"]) is int
                and len(cds) == model["cdsLength"] and len(cds) % 3 == 0
                and cds.startswith("ATG") and CODONS[cds[-3:]] == "*"
                and all(CODONS[cds[i:i + 3]] != "*" for i in range(0, len(cds) - 3, 3))
                and model["proteinLength"] == len(cds) // 3 - 1, "NATIVE_CDS_INVALID")
        require(model["strand"] in (1, -1) and type(model["strand"]) is int, "MODEL_STRAND_INVALID")
        next_cds = 1
        previous = None
        require(isinstance(model["exons"], list) and 1 <= len(model["exons"]) <= 1000, "MODEL_EXON_SET_INVALID")
        for ordinal, exon in enumerate(model["exons"], 1):
            require(all(type(exon[k]) is int and -2147483648 <= exon[k] <= 2147483647
                        for k in ("ordinal", "genomicStart", "genomicEnd", "cdsStart", "cdsEnd"))
                    and exon["ordinal"] == ordinal and 1 <= exon["genomicStart"] <= exon["genomicEnd"]
                    and exon["genomicEnd"] - exon["genomicStart"] == exon["cdsEnd"] - exon["cdsStart"], "MODEL_EXON_INVALID")
            if previous:
                require(exon["cdsStart"] == previous["cdsEnd"] + 1
                        and (previous["genomicEnd"] < exon["genomicStart"] if model["strand"] == 1
                             else previous["genomicStart"] > exon["genomicEnd"]), "MODEL_EXON_ORDER_INVALID")
            lo, hi = max(1, exon["cdsStart"]), min(len(cds), exon["cdsEnd"])
            if lo <= hi:
                require(lo == next_cds, "MODEL_CDS_GAP_OR_OVERLAP")
                next_cds = hi + 1
            previous = exon
        require(next_cds == len(cds) + 1, "MODEL_CDS_INCOMPLETE")
        models[tx] = model
    bridge = review.get("reviewedBridge")
    if len(models) > 1:
        require(bridge and bridge["status"] == "EXACT_REVIEWED_CDS_PROTEIN_AND_GENOMIC_EXONS"
                and bridge["modelExonsEqual"] is True and bridge["cdsEqual"] is True
                and bridge["noVersionStripping"] is True, "ALIAS_BRIDGE_REVIEW_MISSING")
    if bridge:
        only(bridge, {"status", "transcriptBridge", "modelExonsEqual", "cdsEqual", "cdsSha256", "splicedTranscriptSha256",
                      "cdsLengthIncludingStop", "proteinLength", "sourceRecords", "noVersionStripping", "reviewScope"},
             "BRIDGE_OPERATOR_UNSUPPORTED")
        base = next(iter(models.values()))
        pairs = bridge["transcriptBridge"]
        only(pairs, {"refseqTranscript", "refseqProtein", "ensemblTranscript", "ensemblProtein", "status"},
             "TRANSCRIPT_BRIDGE_OPERATOR_UNSUPPORTED")
        require(pairs.get("status") == "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE", "TRANSCRIPT_BRIDGE_STATUS_UNREVIEWED")
        require(type(bridge.get("cdsLengthIncludingStop")) is int and type(bridge.get("proteinLength")) is int
                and bridge["cdsLengthIncludingStop"] == base["cdsLength"] and bridge["proteinLength"] == base["proteinLength"],
                "BRIDGE_SEQUENCE_LENGTH_MISMATCH")
        require(set(models) == {pairs["refseqTranscript"], pairs["ensemblTranscript"]}, "BRIDGE_MODEL_SET_MISMATCH")
        for tx, model in models.items():
            require(model["transcriptBridge"] == pairs and model["exons"] == base["exons"]
                    and model["cdsSequence"] == base["cdsSequence"] and sha(model["cdsSequence"].encode()) == bridge["cdsSha256"]
                    and model["sourceRecords"] == bridge["sourceRecords"]
                    and model["sequenceVerification"]["fullSplicedTranscriptSha256"] == bridge["splicedTranscriptSha256"],
                    "BRIDGE_MODEL_CONTENT_MISMATCH")
            expected_protein = pairs["refseqProtein"] if tx == pairs["refseqTranscript"] else pairs["ensemblProtein"]
            require(model["protein"] == expected_protein, "BRIDGE_PROTEIN_MISMATCH")
    return models


def make_records(raw, model, review):
    records, identities = [], set()
    columns = review["assay"]["sourceColumns"]
    for original in raw["rows"]:
        require(original["recordId"] not in identities, "DUPLICATE_RECORD_ID")
        identities.add(original["recordId"])
        cells = original["sourceCells"]
        value = lambda key: cells[columns[key]]["raw"]
        key = value("proteinVariantKey")
        match = re.fullmatch(r"([ACDEFGHIKLMNPQRSTVWY])([1-9][0-9]*)([ACDEFGHIKLMNPQRSTVWY])", key)
        require(match and key == original["proteinVariantKey"] and value("mutationType") == "missense",
                "RECORD_PROTEIN_IDENTITY_UNSUPPORTED")
        wt, pos, alt = match[1], int(match[2]), match[3]
        ref_codon, exp_codon = value("wildTypeCodon").upper(), value("experimentalMutantCodon").upper()
        require(wt != alt and 1 <= pos <= model["proteinLength"]
                and ref_codon == model["cdsSequence"][(pos - 1) * 3:pos * 3]
                and CODONS.get(ref_codon) == wt and CODONS.get(exp_codon) == alt
                and value("proteinVariantThreeLetter") == AA[wt] + str(pos) + AA[alt], "RECORD_CODON_PROTEIN_MISMATCH")
        require(original["sourceId"] == review["assay"]["sourceId"], "RECORD_SOURCE_MISMATCH")
        records.append({**copy.deepcopy(original), "wildTypeCodon": ref_codon,
                        "experimentalMutantCodon": exp_codon,
                        "proteinHgvs": model["protein"] + ":p." + value("proteinVariantThreeLetter"),
                        "mutationType": "MISSENSE", "conflictReview": {
                            "status": "PENDING_ASSEMBLED_BUNDLE_REVIEW",
                            "sourceReviewId": review["review"]["reviewId"],
                            "scope": "Source row verified; no automatic all-study or RNA conflict clearance"}})
    require(len({r["proteinVariantKey"] for r in records}) == len(records), "DUPLICATE_PROTEIN_RECORD")
    return records


def native_identity(allele, model):
    require(allele["assembly"] == model["assembly"] and allele["chromosome"] == model["contig"]
            and type(allele["position"]) is int and all(re.fullmatch("[ACGT]", allele[k]) for k in ("reference", "alternate"))
            and allele["reference"] != allele["alternate"], "PUBLIC_ALLELE_NOT_NATIVE_SNV")
    pos = allele["position"]
    exons = [e for e in model["exons"] if e["genomicStart"] <= pos <= e["genomicEnd"]]
    require(len(exons) == 1, "PUBLIC_ALLELE_EXON_MISMATCH")
    exon = exons[0]
    cp = exon["cdsStart"] + (pos - exon["genomicStart"] if model["strand"] == 1 else exon["genomicEnd"] - pos)
    require(4 <= cp <= len(model["cdsSequence"]) - 3, "PUBLIC_START_OR_NONCODING_EFFECT")
    ref, alt = allele["reference"], allele["alternate"]
    if model["strand"] == -1:
        ref, alt = ref.translate(str.maketrans("ACGT", "TGCA")), alt.translate(str.maketrans("ACGT", "TGCA"))
    require(model["cdsSequence"][cp - 1] == ref, "PUBLIC_GENOMIC_REF_MISMATCH")
    aa_pos = (cp - 1) // 3 + 1
    rc = model["cdsSequence"][(aa_pos - 1) * 3:aa_pos * 3]
    offset = (cp - 1) % 3
    ac = rc[:offset] + alt + rc[offset + 1:]
    ra, aa = CODONS[rc], CODONS[ac]
    require(ra in AA and aa in AA and ra != aa, "PUBLIC_MISSENSE_REQUIRED")
    return {"proteinVariantKey": ra + str(aa_pos) + aa,
            "hgvsc": model["transcript"] + ":c." + str(cp) + ref + ">" + alt,
            "hgvsp": model["protein"] + ":p." + AA[ra] + str(aa_pos) + AA[aa],
            "cdsPosition": cp, "proteinPosition": aa_pos, "refCodon": rc, "altCodon": ac,
            "aminoAcids": ra + "/" + aa, "exon": str(exon["ordinal"]) + "/" + str(len(model["exons"]))}


def verify_annotations(expectations, models, selected_model, records, vep_lines):
    result = []
    by_protein = {r["proteinVariantKey"]: r for r in records}
    for allele in expectations["publicDevelopmentAlleles"]:
        observed_model = next((m for m in models.values() if allele["hgvsc"].split(":")[0] == m["transcript"]), None)
        require(observed_model is not None, "PUBLIC_ALLELE_ALIAS_UNREVIEWED")
        source_identity = native_identity(allele, observed_model)
        require(allele["hgvsc"] == source_identity["hgvsc"] and allele["hgvsp"] == source_identity["hgvsp"]
                and allele["proteinVariantKey"] == source_identity["proteinVariantKey"], "PUBLIC_HGVS_SOURCE_MISMATCH")
        identity = native_identity(allele, selected_model)
        require(identity["proteinVariantKey"] in by_protein, "PUBLIC_ASSAY_ROW_MISSING")
        row = by_protein[identity["proteinVariantKey"]]
        require(row["row"] == allele["row"] and row["sheet"] == allele["sheet"], "PUBLIC_ASSAY_ROW_MISMATCH")
        matches = [x for x in vep_lines if x.get("assembly_name") == allele["assembly"]
                   and x.get("seq_region_name") == allele["chromosome"] and x.get("start") == allele["position"]
                   and x.get("end") == allele["position"] and x.get("allele_string") == allele["reference"] + "/" + allele["alternate"]]
        require(len(matches) == 1, "VEP_NATIVE_ALLELE_MISSING_OR_DUPLICATE")
        item = matches[0]
        fields = item["input"].split("\t")
        require(len(fields) >= 5 and fields[0] == allele["chromosome"] and int(fields[1]) == allele["position"]
                and fields[2] == allele["id"] and fields[3:5] == [allele["reference"], allele["alternate"]], "VEP_ORIGINAL_INPUT_MISMATCH")
        transcripts = [tx for tx in item.get("transcript_consequences", [])
                       if tx.get("hgvsc", "").split(":")[0] in models]
        require(len(transcripts) == 1, "VEP_EXACT_TRANSCRIPT_MISSING_OR_AMBIGUOUS")
        tx = transcripts[0]
        native_model = models[tx["hgvsc"].split(":")[0]]
        annotated = native_identity(allele, native_model)
        require(tx.get("hgvsc") == annotated["hgvsc"] and tx.get("hgvsp") == annotated["hgvsp"]
                and tx.get("transcript_id") == split_version(native_model["transcript"])["accession"]
                and tx.get("protein_id") == split_version(native_model["protein"])["accession"]
                and tx.get("gene_symbol") == native_model["geneSymbol"] and tx.get("hgnc_id") == native_model["hgncId"]
                and tx.get("strand") == native_model["strand"] and tx.get("variant_allele") == allele["alternate"]
                and tx.get("consequence_terms") == ["missense_variant"] and tx.get("exon") == annotated["exon"]
                and tx.get("amino_acids") == annotated["aminoAcids"]
                and tx.get("codons", "").upper() == annotated["refCodon"] + "/" + annotated["altCodon"]
                and tx.get("cds_start") == tx.get("cds_end") == annotated["cdsPosition"]
                and tx.get("protein_start") == tx.get("protein_end") == annotated["proteinPosition"], "VEP_DNA_PROTEIN_IDENTITY_MISMATCH")
        bridge = native_model.get("transcriptBridge")
        if bridge:
            require(tx.get("mane_select") == bridge["refseqTranscript"], "VEP_MANE_ALIAS_MISMATCH")
        result.append({"id": allele["id"], "sourceAllele": copy.deepcopy(allele), "nativeIdentity": identity,
                       "nativeModelSha256": canonical_sha(selected_model), "originalVepInput": item["input"],
                       "originalVepTranscript": copy.deepcopy(tx), "annotationRoute":
                       "DIRECT_EXACT_TRANSCRIPT" if native_model["transcript"] == selected_model["transcript"]
                       else "EXPLICIT_REVIEWED_NATIVE_MODEL_BRIDGE_NO_VEP_RELABELING"})
    require(result and len({x["id"] for x in result}) == len(result), "PUBLIC_DEVELOPER_PANEL_EMPTY_OR_DUPLICATE")
    return result


def build(manifest):
    only(manifest, {"schemaVersion", "candidateId", "version", "artifacts", "models", "sourceFiles",
                    "annotationResourceId", "registryClasspathResource"}, "BUILD_MANIFEST_UNKNOWN_FIELD")
    require(manifest["schemaVersion"] == "functional-candidate-build-manifest-v1", "BUILD_SCHEMA_UNSUPPORTED")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+", manifest["candidateId"]) and manifest["version"], "CANDIDATE_ID_INVALID")
    artifacts = manifest["artifacts"]
    require(set(artifacts) == {"construction", "expectations", "rawRows", "annotationIdentity", "registry", "vepJsonl",
                               "vepInputManifest", "vepExecutionStart", "vepExecutionFinish"}, "BUILD_ARTIFACT_SET_INCOMPLETE")
    data = {key: read_pinned(binding) for key, binding in artifacts.items()}
    parsed = {key: parse_json(value) for key, value in data.items() if key != "vepJsonl"}
    review, expectations, raw = (parsed[key] for key in ("construction", "expectations", "rawRows"))
    validate_review(review)
    declarations = {item["relativePath"]: item for item in review["inputArtifacts"]}
    require(len(declarations) == 2, "REVIEW_INPUT_ARTIFACT_SET_INVALID")
    for key in ("expectations", "rawRows"):
        declared = declarations.get(Path(artifacts[key]["path"]).name)
        require(declared and declared["fileSha256"] == sha(data[key])
                and declared["canonicalSha256"] == canonical_sha(parsed[key]), "REVIEW_INPUT_ARTIFACT_MISMATCH")
    require(expectations["schemaVersion"] == "prospective-source-expectations-v1"
            and raw["schemaVersion"] == "prospective-raw-source-rows-v1"
            and expectations["recordedBeforeImplementationOutputs"] is True, "PROSPECTIVE_INPUT_REQUIRED")
    for key in ("hgncId", "geneSymbol", "mondoId", "moi"):
        require(expectations["scope"][key] == review["scope"][key], "EXPECTATION_SCOPE_MISMATCH")
    models = validate_models(review, manifest["models"])
    model_sources = [s for model in models.values() for s in model["sourceRecords"]]
    sources = verify_source_files([source_record(s) for s in expectations["sources"]]
                                  + [source_record(raw["primaryMethodsSource"])] + model_sources, manifest["sourceFiles"])
    require(set(review["method"]["sourceIds"]) <= {s["sourceId"] for s in sources}, "METHOD_SOURCES_UNBOUND")
    require(raw["source"]["sourceId"] == review["assay"]["sourceId"]
            and raw["source"]["sha256"] == manifest["sourceFiles"][raw["source"]["sourceId"]]["sha256"], "RAW_SOURCE_MISMATCH")
    verified_cells = verify_workbook(read_pinned(manifest["sourceFiles"][raw["source"]["sourceId"]]), raw)
    annotation_matches = [r for r in parsed["annotationIdentity"]["resources"] if r["resourceId"] == manifest["annotationResourceId"]]
    require(len(annotation_matches) == 1, "ANNOTATION_RESOURCE_MISSING_OR_DUPLICATE")
    annotation = annotation_matches[0]
    require(annotation.get("provider") == "VEP" and annotation.get("assembly") == review["scope"]["assembly"]
            and annotation.get("identityFingerprintSha256") == canonical_sha(annotation["identity"]), "ANNOTATION_RESOURCE_IDENTITY_MISMATCH")
    for model in models.values():
        require(model["sequenceVerification"]["fastaIndexSha256"] == annotation["identity"]["fasta"]["indexSha256"],
                "ANNOTATION_MODEL_FASTA_INDEX_MISMATCH")
    registry = parsed["registry"]
    require(registry["schemaVersion"] == "acmg-rule-registry-v2"
            and manifest["registryClasspathResource"].startswith("/acmg/rule-registry-v2/")
            and manifest["registryClasspathResource"].split("/")[-1] == Path(artifacts["registry"]["path"]).name
            and sum(x.get("criterionId") == "ACMG_AMP_2015:PS3" for x in registry["criteria"]) == 1, "REGISTRY_PS3_BINDING_INVALID")
    registry_rule = next(x for x in registry["criteria"] if x["criterionId"] == "ACMG_AMP_2015:PS3")
    require(review["assay"]["threshold"]["strength"] in registry_rule.get("currentGeneral", {}).get("allowedStrengths", []),
            "REGISTRY_STRENGTH_NOT_ALLOWED")
    finish, start, input_manifest = (parsed[k] for k in ("vepExecutionFinish", "vepExecutionStart", "vepInputManifest"))
    require(finish["exitCode"] == 0 and finish["vepBatches"] == 1 and start["batchOrdinal"] == 1
            and finish["startedAt"] == start["startedAt"] and finish["formalTopNRun"] is False
            and finish["backendStarted"] is False, "VEP_EXECUTION_NOT_BOUND_DEVELOPER_BATCH")
    require(input_manifest["schemaVersion"] == "varvis-isolated-annotation-check-v1"
            and input_manifest["status"] == "INPUTS_FIXED_BEFORE_VEP"
            and input_manifest["partition"] == "EXPOSED_DEVELOPER_NOT_HELD_OUT"
            and input_manifest["inputAlleles"] == start["alleles"]
            and input_manifest["formalTopNRun"] is False and input_manifest["clinicalAcceptance"] is False
            and any(s.get("sha256") == sha(data["expectations"]) for s in input_manifest["sourceExpectations"]),
            "VEP_PROSPECTIVE_INPUT_BINDING_MISMATCH")
    arguments = start["arguments"]
    require(isinstance(arguments, list) and all(isinstance(a, str) for a in arguments)
            and all(arguments.count(a) == 1 for a in ("--offline", "--cache", "--json", "--hgvs", "--protein", "--symbol", "--numbers", "--mane", "--assembly")),
            "VEP_EXECUTION_OPTIONS_INCOMPLETE")
    assembly_offset = arguments.index("--assembly") + 1
    require(assembly_offset < len(arguments) and arguments[assembly_offset] == review["scope"]["assembly"],
            "VEP_EXECUTION_ASSEMBLY_MISMATCH")
    vep_lines = [parse_json(line, allow_decimal=True) for line in data["vepJsonl"].splitlines() if line.strip()]
    require(len(vep_lines) == start["alleles"], "VEP_BATCH_RECORD_COUNT_MISMATCH")
    outputs = []
    for tx, model in models.items():
        scope = copy.deepcopy(review["scope"]) | {"protein": model["protein"], "transcripts": [split_version(tx)]}
        records = make_records(raw, model, review)
        observed = verify_annotations(expectations, models, model, records, vep_lines)
        # The VEP input manifest and original output are retained by hash. Extra
        # plugins/options are not silently incorporated into the historical VEP
        # resource identity: assembled execution/profile review remains required.
        identifier = manifest["candidateId"] + "_" + tx
        facts = {"schemaVersion": "reviewed-functional-facts-v1", "factBundleId": identifier + "_FACTS",
                 "version": manifest["version"], "review": {"status": "SOURCE_REVIEWED_PENDING_BUNDLE_REVIEW",
                 "sourceReviewId": review["review"]["reviewId"]}, "scope": scope, "nativeModel": model,
                 "annotationResource": annotation, "method": copy.deepcopy(review["method"]), "records": records}
        policy = {"schemaVersion": "functional-evidence-policy-v1", "policyId": identifier + "_PS3",
                  "policyVersion": manifest["version"], "criterionIds": ["ACMG_AMP_2015:PS3"],
                  "review": {"status": "PENDING_ASSEMBLED_BUNDLE_REVIEW", "sourceReviewId": review["review"]["reviewId"]},
                  "scope": scope, "sources": sources, "registryBinding": {"registryId": registry["registryId"],
                  "registrySha256": sha(data["registry"]), "registryClasspathResource": manifest["registryClasspathResource"]},
                  "assay": copy.deepcopy(review["assay"]), "nativeModelSha256": canonical_sha(model),
                  "annotationResourceSha256": canonical_sha(annotation), "methodBinding": {
                      "methodId": review["method"]["methodId"], "canonicalSha256": canonical_sha(review["method"])},
                  "factBundle": {"factBundleId": facts["factBundleId"], "version": facts["version"], "canonicalSha256": canonical_sha(facts)}}
        candidate = {"schemaVersion": "functional-evidence-candidate-v1", "candidateId": identifier,
                     "version": manifest["version"], "activation": copy.deepcopy(review["activation"]) | {"bs3Enabled": False},
                     "sourceReview": copy.deepcopy(review["review"]), "policy": policy, "facts": facts,
                     "buildProvenance": {"builderVersion": VERSION, "manifestCanonicalSha256": canonical_sha(manifest),
                         "artifacts": {k: {"fileSha256": sha(v)} for k, v in data.items()},
                         "sourceFileSha256": {s["sourceId"]: s["sha256"] for s in sources},
                         "reviewedModelBridge": copy.deepcopy(review.get("reviewedBridge")),
                         "additionalModelCalls": 0, "additionalVepBatches": 0},
                     "validation": {"typedSourceCellsVerified": verified_cells, "sourceRowCount": len(records),
                         "publicDeveloperAlleles": observed, "annotationResourceExecutionReview": "PENDING_ASSEMBLED_BUNDLE_REVIEW",
                         "formalTopNRun": False, "heldOutValidation": False, "clinicalAcceptance": False},
                     "remainingReview": ["Assembled policy, fact bundle and per-assay/RNA conflict review",
                         "Actual execution options/plugins versus annotation resource profile and receipt binding",
                         "Controlled catalog selection and business integration; independent held-out acceptance"]}
        # Decimal values from unrelated VEP annotations never enter these selected
        # identity projections; fail closed if a future source changes that shape.
        canonical_sha(candidate)
        outputs.append(candidate)
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--inputs-sha256", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = parse_json(read_pinned({"path": str(args.inputs), "sha256": args.inputs_sha256}))
        candidates = build(manifest)
        require(not args.output_dir.exists(), "OUTPUT_DIRECTORY_ALREADY_EXISTS")
        args.output_dir.mkdir(parents=True)
        report = []
        for item in candidates:
            path = args.output_dir / (item["candidateId"] + ".json")
            content = json.dumps(item, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
            with path.open("xb") as stream:
                stream.write(content)
            report.append({"file": path.name, "fileSha256": sha(content), "canonicalSha256": canonical_sha(item),
                           "nativeModelSha256": item["policy"]["nativeModelSha256"],
                           "policyCanonicalSha256": canonical_sha(item["policy"]),
                           "factCanonicalSha256": canonical_sha(item["facts"])})
        (args.output_dir / "manifest.json").write_bytes(json.dumps({"builderVersion": VERSION,
            "status": "DISABLED_CANDIDATES_PENDING_ASSEMBLED_REVIEW", "outputs": report}, indent=2).encode() + b"\n")
        print(json.dumps({"status": "DISABLED_CANDIDATES_WRITTEN", "outputs": report}, indent=2))
        return 0
    except (InputError, KeyError, TypeError, ValueError, OSError, zipfile.BadZipFile, ET.ParseError, InvalidOperation) as exc:
        print(json.dumps({"status": "NOT_BUILT", "reason": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
