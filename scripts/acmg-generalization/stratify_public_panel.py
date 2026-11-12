"""Deterministic masked source stratification; no predictor, rule or VEP execution."""
import argparse
import collections
import datetime
import decimal
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import zlib


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


N = module("panel_normalization", "normalize_public_panel.py")
M = module("panel_transcript_source", "build_transcript_model.py")
VERSION = "1.1.2"
SEED = "VARVIS-TEN-RULE-GENERALIZATION-PUBLIC-V1-20260913"
STRATA = [("CODING_STOP_INTERNAL", 1), ("CODING_FRAMESHIFT_INTERNAL", 1),
          ("TRUNCATING_TERMINAL_CHALLENGE", 1), ("MISSENSE", 4), ("SYNONYMOUS", 2), ("NONCANONICAL_INTRONIC", 1)]
AF_FIELDS = ("AF_ESP", "AF_EXAC", "AF_TGP")
AF_LITERAL = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
MAX_INPUT = 536870912
MAX_OUTPUT = 268435456
require, canonical, sha = N.require, N.canonical, N.sha


def rank(gene, stratum, key):
    return sha((SEED + "\n" + gene + "\n" + stratum + "\n" + key).encode("utf-8"))


class Model:
    """Same continuous CDS-relative exon convention as CodingTranscriptMapping."""
    def __init__(self, value, context):
        self.value, self.context = value, context
        self.identity = sha(canonical(value))
        self.cds, self.exons, self.strand = value.get("cdsSequence"), value.get("exons"), value.get("strand")
        require(value.get("schemaVersion") in ("pvs1-transcript-model-v1", "native-coding-transcript-model-v1")
                and value.get("assembly") == "GRCh38" and value.get("geneSymbol") == context["geneSymbol"]
                and value.get("hgncId") == context["hgncId"] and N.PRIMARY.fullmatch(value.get("contig", ""))
                and re.fullmatch(r"(?:ENST[0-9]+|NM_[0-9]+)\.[1-9][0-9]*", value.get("transcript", ""))
                and re.fullmatch(r"(?:ENSP[0-9]+|NP_[0-9]+)\.[1-9][0-9]*", value.get("protein", "")), "MODEL_CONTEXT_IDENTITY_INVALID")
        require(type(self.strand) is int and self.strand in (-1, 1) and type(self.cds) is str
                and N.BASES.fullmatch(self.cds) and 6 <= len(self.cds) <= 1000000 and len(self.cds) % 3 == 0
                and self.cds.startswith("ATG") and type(value.get("cdsLength")) is int
                and type(value.get("proteinLength")) is int and value.get("cdsLength") == len(self.cds)
                and value.get("proteinLength") == len(self.cds) // 3 - 1, "MODEL_CDS_INVALID")
        protein = "".join(M.CODONS[self.cds[i:i+3]] for i in range(0, len(self.cds), 3))
        require(protein.endswith("*") and "*" not in protein[:-1], "MODEL_TRANSLATION_INVALID")
        require(type(self.exons) is list and 1 <= len(self.exons) <= 1000, "MODEL_EXONS_INVALID")
        previous = None
        for ordinal, exon in enumerate(self.exons, 1):
            require(all(type(exon.get(key)) is int and -(2**31) <= exon[key] < 2**31
                        for key in ("ordinal", "genomicStart", "genomicEnd", "cdsStart", "cdsEnd")), "MODEL_EXON_INTEGER_INVALID")
            lo, hi, cs, ce = (exon[key] for key in ("genomicStart", "genomicEnd", "cdsStart", "cdsEnd"))
            require(exon["ordinal"] == ordinal and 1 <= lo <= hi and ce - cs == hi - lo
                    and (cs <= 1 if previous is None else cs == previous["cdsEnd"] + 1)
                    and (previous is None or (lo > previous["genomicEnd"] if self.strand == 1 else hi < previous["genomicStart"])),
                    "MODEL_EXON_CONTINUITY_INVALID")
            previous = exon
        require(self.exons[-1]["cdsEnd"] >= len(self.cds), "MODEL_CDS_COVERAGE_INCOMPLETE")
        self.coding = [exon for exon in self.exons if max(1, exon["cdsStart"]) <= min(len(self.cds), exon["cdsEnd"])]

    def exon(self, start, end):
        matches = [e for e in self.exons if e["genomicStart"] <= start <= end <= e["genomicEnd"]]
        require(len(matches) <= 1, "MODEL_EXON_AMBIGUOUS")
        return matches[0] if matches else None

    def cds_position(self, genomic):
        exon = self.exon(genomic, genomic)
        if exon is None:
            return None
        position = exon["cdsStart"] + (genomic - exon["genomicStart"] if self.strand == 1 else exon["genomicEnd"] - genomic)
        return position if 1 <= position <= len(self.cds) else None

    def orient(self, bases):
        return bases if self.strand == 1 or not bases else M.reverse_complement(bases)

    def terminal(self, exon, lo, hi):
        if exon == self.coding[-1]:
            return True
        if len(self.coding) > 1 and exon == self.coding[-2]:
            end = min(len(self.cds), exon["cdsEnd"])
            start = max(1, exon["cdsStart"], end - 54)
            return max(lo, start) <= min(hi, end)
        return False


def event(allele):
    ref, alt, start = allele["reference"], allele["alternate"], allele["position"]
    prefix = 0
    while prefix < min(len(ref), len(alt)) and ref[prefix] == alt[prefix]:
        prefix += 1
    ref, alt = ref[prefix:], alt[prefix:]
    suffix = 0
    while ref and alt and ref[-1] == alt[-1]:
        ref, alt, suffix = ref[:-1], alt[:-1], suffix + 1
    start += prefix
    return dict(genomicStart=start, genomicEnd=start + len(ref) - 1, deleted=ref, inserted=alt,
                removedCommonPrefix=prefix, removedCommonSuffix=suffix,
                insertionFlanks=[start-1, start] if not ref else None)


def mechanical(allele, model):
    result = dict(status="EXCLUDED", stratumId=None, reasonCode=None, modelCanonicalSha256=model.identity,
                  transcript=model.value["transcript"], protein=model.value["protein"],
                  clinicalTranscriptApproval=False, criterionExpectations="PENDING_INDEPENDENT_RULE_INPUTS")
    def refuse(reason, unresolved=False):
        result.update(status="UNRESOLVED" if unresolved else "EXCLUDED", reasonCode=reason)
        return result
    if allele.get("assembly") != "GRCh38" or allele.get("contig") != model.value["contig"]:
        return refuse("CANONICAL_MODEL_ASSEMBLY_CONTIG_MISMATCH", True)
    issue = N.envelope(allele.get("position"), allele.get("reference"), allele.get("alternate"))
    if issue:
        return refuse(issue)
    change = event(allele)
    result["eventProof"] = change
    points = change["insertionFlanks"] or [change["genomicStart"], change["genomicEnd"]]
    if min(points) < 1:
        return refuse("EVENT_FLANK_OUTSIDE_NATIVE_CONTIG")
    exon = model.exon(min(points), max(points))
    positions = [model.cds_position(p) for p in points]
    if exon is not None and all(p is not None for p in positions):
        lo, hi = min(positions), max(positions)
        change.update(exonOrdinal=exon["ordinal"], cdsInterval=[lo, hi], strand=model.strand,
                      orientedDeleted=model.orient(change["deleted"]), orientedInserted=model.orient(change["inserted"]))
        # Include every coding base of the canonical REF, even an unchanged
        # anchor. A noncoding anchor has already been checked by normalization.
        checked = 0
        for offset, base in enumerate(allele["reference"]):
            cds_position = model.cds_position(allele["position"] + offset)
            if cds_position is not None:
                checked += 1
                if model.cds[cds_position-1] != model.orient(base):
                    return refuse("CANONICAL_REFERENCE_MODEL_CDS_MISMATCH", True)
        change["canonicalReferenceCodingBasesCrossChecked"] = checked
        if change["deleted"]:
            if model.cds[lo-1:hi] != change["orientedDeleted"]:
                return refuse("EDIT_REFERENCE_MODEL_CDS_MISMATCH", True)
            index = lo - 1
        else:
            require(hi == lo + 1, "INSERTION_CDS_FLANKS_NONADJACENT")
            index = lo  # Number of biological CDS bases preceding insertion, on either strand.
        change["cdsEditIndex0"] = index
        delta = len(change["inserted"]) - len(change["deleted"])
        change["cdsLengthDelta"] = delta
        if len(allele["reference"]) == len(allele["alternate"]) == 1:
            position = lo
            codon_index = (position - 1) // 3 * 3
            ref_codon = model.cds[codon_index:codon_index+3]
            within = (position - 1) % 3
            alt_codon = ref_codon[:within] + change["orientedInserted"] + ref_codon[within+1:]
            before, after = M.CODONS[ref_codon], M.CODONS[alt_codon]
            change.update(proteinPosition=codon_index//3+1, referenceCodon=ref_codon, alternateCodon=alt_codon,
                          referenceAminoAcid=before, alternateAminoAcid=after)
            if codon_index == 0:
                return refuse("INITIATION_CODON_SNV_EXCLUDED")
            if before == "*":
                return refuse("NATIVE_STOP_CODON_SNV_EXCLUDED")
            stratum = "TRUNCATING_TERMINAL_CHALLENGE" if after == "*" and model.terminal(exon, lo, hi) else (
                "CODING_STOP_INTERNAL" if after == "*" else "SYNONYMOUS" if before == after else "MISSENSE")
        elif delta % 3:
            change.update(effect="FRAMESHIFT", startCodonOverlap=lo <= 3,
                          nativeStopOverlap=hi >= len(model.cds)-2,
                          ptcAndNmdDerivation="PENDING_SEPARATE_RULE_EXPECTATIONS_NOT_A_SAMPLING_FILTER")
            stratum = "TRUNCATING_TERMINAL_CHALLENGE" if model.terminal(exon, lo, hi) else "CODING_FRAMESHIFT_INTERNAL"
        else:
            return refuse("CODING_INFRAME_INDEL_NOT_IN_FIXED_STRATA")
        result.update(status="ASSIGNED", stratumId=stratum)
        return result
    if exon is not None:
        return refuse("UNTRANSLATED_OR_CODING_BOUNDARY_EVENT")
    for ordinal, (before, after) in enumerate(zip(model.exons, model.exons[1:]), 1):
        lo, hi = (before["genomicEnd"]+1, after["genomicStart"]-1) if model.strand == 1 else (after["genomicEnd"]+1, before["genomicStart"]-1)
        if lo <= min(points) <= max(points) <= hi:
            change.update(intronAfterPhysicalExonOrdinal=ordinal, intronGenomicInterval=[lo, hi], strand=model.strand,
                          biologicalIntronOffsets1=[p-lo+1 if model.strand == 1 else hi-p+1 for p in points])
            if min(points) < lo + 2 or max(points) > hi - 2:
                return refuse("CANONICAL_INTRON_BOUNDARY_EVENT")
            result.update(status="ASSIGNED", stratumId="NONCANONICAL_INTRONIC")
            return result
    return refuse("CROSS_EXON_OR_UNRESOLVED_TRANSCRIPT_EVENT")


def historical_af(rows):
    cells, valid, per_field, invalid = [], [], collections.defaultdict(set), False
    for row in rows:
        projection = row["source"]["maskedProjection"]
        for field in AF_FIELDS:
            present, raw = field in projection, projection.get(field)
            cell = dict(fieldId=field, present=present, rawValue=raw, sourceRawLineSha256=projection["sourceRawLineSha256"],
                        sourceRecordId=row["recordId"], status="UNAVAILABLE", normalizedValue=None)
            if not present or raw in ("", "."):
                cell["reasonCode"] = "ABSENT" if not present else "EMPTY" if raw == "" else "DOT"
            else:
                try:
                    require(type(raw) is str and AF_LITERAL.fullmatch(raw), "MALFORMED_HISTORICAL_AF")
                    value = decimal.Decimal(raw)
                    require(value.is_finite() and 0 <= value <= 1, "MALFORMED_HISTORICAL_AF")
                    valid.append(value)
                    per_field[field].add(value)
                    cell.update(status="OBSERVED", normalizedValue=str(value))
                except (ValueError, decimal.InvalidOperation):
                    invalid = True
                    cell.update(status="INVALID", reasonCode="MALFORMED_HISTORICAL_AF")
            cells.append(cell)
    return dict(metricId="MAX_OBSERVED_HISTORICAL_SOURCE_AF_FOR_SAMPLING_ONLY", cells=cells,
                maximumObserved=None if not valid else str(max(valid)), hasMalformedObservation=invalid,
                discordance=[dict(fieldId=field, status="SOURCE_AF_DISCORDANCE", minimum=str(min(values)), maximum=str(max(values)))
                             for field, values in per_field.items() if len(values) > 1], clinicalPopulationEvidence=False)


def allocate(contexts, assignments, anchor_keys):
    """Fixed slots only; global denominator credit is assigned in frame gene order."""
    selected, gaps, credited, orders = [], [], set(), []
    for context in contexts:
        gene = context["geneSymbol"]
        for stratum, quota in STRATA:
            all_rows = [row for row in assignments if row["geneSymbol"] == gene and row["mapping"]["status"] == "ASSIGNED"
                        and row["mapping"]["stratumId"] == stratum]
            ordered = sorted(all_rows, key=lambda row: (row["rankSha256"], row["canonicalKey"].encode("utf-8")))
            orders.append(dict(geneSymbol=gene, stratumId=stratum, candidateCount=len(ordered),
                               orderedCandidateIds=[row["candidateId"] for row in ordered]))
            available = [row for row in ordered if row["canonicalKey"] not in credited]
            def take(row, slot, reason):
                if row is None:
                    gaps.append(dict(geneSymbol=gene, stratumId=stratum, slot=slot, reasonCode=reason))
                else:
                    require(row["canonicalKey"] not in credited, "GLOBAL_CANONICAL_CREDIT_DUPLICATE")
                    credited.add(row["canonicalKey"])
                    selected.append(dict(row, slot=slot, rankingReason=reason))
            if stratum == "MISSENSE":
                anchors = [row for row in available if row["matchingAnchorIds"]][:2]
                independent = [row for row in available if row["canonicalKey"] not in anchor_keys]
                choices = [(row, "FIXED_ANCHOR_HASH") for row in anchors] + [(row, "INDEPENDENT_CANONICAL_HASH") for row in independent[:4-len(anchors)]]
                for slot in range(1, 5):
                    take(choices[slot-1][0] if slot <= len(choices) else None, slot,
                         choices[slot-1][1] if slot <= len(choices) else "INDEPENDENT_MISSENSE_POOL_SHORTFALL")
            elif stratum == "SYNONYMOUS":
                malformed = any(row["historicalAf"]["hasMalformedObservation"] for row in available)
                measured = [row for row in available if row["historicalAf"]["maximumObserved"] is not None]
                if malformed:
                    take(None, 1, "MALFORMED_SYNONYMOUS_AF_POOL_REQUIRES_REVIEW")
                elif not measured:
                    take(None, 1, "NO_OBSERVED_HISTORICAL_AF")
                else:
                    maximum = max(decimal.Decimal(row["historicalAf"]["maximumObserved"]) for row in measured)
                    take(next(row for row in measured if decimal.Decimal(row["historicalAf"]["maximumObserved"]) == maximum), 1,
                         "HIGHEST_OBSERVED_HISTORICAL_RAW_AF_THEN_CANONICAL_HASH")
                remaining = [row for row in available if row["canonicalKey"] not in credited]
                take(remaining[0] if remaining else None, 2, "HASH_SELECTED_DISTINCT_REMAINING" if remaining else "SYNONYMOUS_POOL_SHORTFALL")
            else:
                anchors = [row for row in available if row["matchingAnchorIds"]]
                choices = anchors or available
                take(choices[0] if choices else None, 1, "FIXED_ANCHOR_HASH" if anchors else "CANONICAL_HASH" if choices else "STRATUM_POOL_SHORTFALL")
    return selected, gaps, orders


SEMANTIC_KEYS = ("unchangedRequirements", "requiredInputs", "mechanicalMapping", "strata", "historicalPopulationAfSampling", "rankingAndAnchors", "requiredOutput", "remainingIndependentRuleInputs")
SEMANTIC_SHA = "a2ec60a98da6da5b9bc9283a7fa96de9805adaec898f8ed84e927418d88f8f08"


def validate_contract(contract, frame):
    N.fields(contract, "schemaVersion contractId version fixedAt reviewer status scope bindings unchangedRequirements requiredInputs mechanicalMapping strata historicalPopulationAfSampling rankingAndAnchors requiredOutput remainingIndependentRuleInputs reviewerActivityAtFreeze")
    require(contract["schemaVersion"] == "public-allele-final-stratification-contract-v1"
            and contract["status"] == "PROSPECTIVELY_FIXED_BEFORE_REVIEWER_SEES_NORMALIZATION_OUTPUTS"
            and sha(canonical({key: contract[key] for key in SEMANTIC_KEYS})) == SEMANTIC_SHA,
            "STRATIFICATION_CONTRACT_OPERATOR_UNSUPPORTED")
    N.fields(contract["bindings"], "frameFile frameSha256 queryPlanFile queryPlanSha256 normalizationPlanFile normalizationPlanVersion normalizationPlanSha256 poolManifestSha256 maskedProjectionSha256 queryOccurrencesSha256 privatePrerankManifestSha256 nativeModelsManifestFileSha256 nativeModelSourceReviewFile nativeModelSourceReviewSha256 populationHeaderReceiptLogicalPath populationHeaderReceiptSha256 populationHeaderSha256 normalizationExecutionBinding")
    require(all(N.digest(value) for key, value in contract["bindings"].items() if key.endswith("Sha256")), "CONTRACT_BINDING_SHA_INVALID")
    # Operational prose is fixed too: a future ceiling or prerequisite cannot be
    # silently ignored. Provenance-only top-level fields are explicitly named.
    require(frame.get("schemaVersion") == "prospective-public-allele-sampling-frame-v1"
            and frame["requirements"]["fixedGenes"] == 20 and frame["requirements"]["fixedPerGeneQuota"] == 10
            and frame["requirements"]["plannedUniqueAlleles"] == 200
            and frame["sampling"]["deterministicSeed"] == SEED
            and [(row["id"], row["quota"]) for row in frame["sampling"]["strataPerGene"]] == STRATA,
            "UNCHANGED_FRAME_QUOTA_OR_SEED_REQUIRED")
    contexts = frame["contexts"]
    require(type(contexts) is list and len(contexts) == 20 and len({c["geneSymbol"] for c in contexts}) == 20
            and all(c["publicAlleleQuota"] == 10 for c in contexts)
            and len({c["mechanismSamplingFamily"] for c in contexts}) == 7, "FRAME_CONTEXT_ACCOUNTING_INVALID")
    return contexts


class Inputs:
    def __init__(self):
        self.bytes = 0
        self.identities = []

    def read(self, path, expected, maximum, label):
        maximum = min(maximum, MAX_INPUT - self.bytes)
        require(maximum >= 0, "STRATIFICATION_INPUT_BUDGET_EXHAUSTED")
        content = N.pinned_bytes(path, expected, maximum)
        self.bytes += len(content)
        self.identities.append(dict(label=label, bytes=len(content), sha256=expected))
        return content

    def json(self, path, expected, label, maximum=2097152):
        value = N.strict_json(self.read(path, expected, maximum, label))
        self.identities[-1]["canonicalSha256"] = sha(canonical(value))
        return value


def local_child(root, name):
    require(type(name) is str and name and not Path(name).is_absolute() and ":" not in name
            and ".." not in name.replace("\\", "/").split("/"), "INPUT_ARTIFACT_PATH_INVALID")
    path = (root / name).resolve(strict=True)
    require(root in path.parents and path.is_file(), "INPUT_ARTIFACT_PATH_ESCAPES_BOUND_ROOT")
    return path


def load_models(inputs, contract, contexts, manifest_path, review_path):
    bindings = contract["bindings"]
    manifest = inputs.json(manifest_path, bindings["nativeModelsManifestFileSha256"], "nativeModelsManifest")
    review = inputs.json(review_path, bindings["nativeModelSourceReviewSha256"], "nativeModelSourceReview")
    require(manifest.get("schemaVersion") == "native-twenty-model-execution-v1" and manifest.get("status") == "COMPLETE"
            and manifest.get("sourceKind") == "VEP116_OFFLINE_CACHE_TRANSCRIPT_OBJECT_NOT_REST"
            and manifest.get("independentRnaSource") == "NCBI_VERSIONED_REFSEQ_GENBANK"
            and manifest.get("defaultEnabled") is False and manifest.get("clinicalAcceptance") is False,
            "NATIVE_MODEL_MANIFEST_NOT_REVIEWED_SOURCE")
    require(review.get("schemaVersion") == "independent-native-twenty-model-source-review-v1"
            and review.get("status") == "TWENTY_MODEL_AND_349_ARCHIVED_RETURNED_EXON_HASHES_MATCH_INDEPENDENT_REFSEQ_RNA"
            and review["bindings"]["modelManifestFileSha256"] == bindings["nativeModelsManifestFileSha256"], "MODEL_INDEPENDENT_REVIEW_MISMATCH")
    entries, reviewed = manifest["entries"], review["records"]
    require(len(entries) == len(reviewed) == len(contexts) == 20, "TWENTY_MODELS_REQUIRED")
    by_gene, reviews = {}, {}
    for entry in entries:
        require(entry["geneSymbol"] not in by_gene and entry["status"] == "FULL_RNA_EXON_CDS_TRANSLATION_AGREEMENT", "MODEL_ENTRY_AMBIGUOUS")
        by_gene[entry["geneSymbol"]] = entry
    for entry in reviewed:
        require(entry["geneSymbol"] not in reviews and entry["status"] == "EXACT_SOURCE_AND_ARCHIVED_NATIVE_READ_RECEIPTS_MATCH", "MODEL_REVIEW_AMBIGUOUS")
        reviews[entry["geneSymbol"]] = entry
    require(set(by_gene) == set(reviews) == {c["geneSymbol"] for c in contexts}, "MODEL_GENE_SET_MISMATCH")
    root, result = Path(manifest_path).resolve().parent, {}
    for context in contexts:
        gene = context["geneSymbol"]
        entry, checked = by_gene[gene], reviews[gene]
        require(entry["file"] == checked["modelFile"] and entry["fileSha256"] == checked["modelFileSha256"]
                and entry["canonicalSha256"] == checked["modelCanonicalSha256"] and entry["transcript"] == checked["transcript"], "MODEL_FILE_REVIEW_BINDING_MISMATCH")
        model = inputs.json(local_child(root, entry["file"]), entry["fileSha256"], "model:" + gene)
        require(sha(canonical(model)) == entry["canonicalSha256"] and model["transcript"] == entry["transcript"]
                and model["cdsLength"] == entry["cdsLength"] and len(model["exons"]) == entry["exonCount"]
                and sha(model["cdsSequence"].encode("ascii")) == checked["cdsSha256"], "MODEL_CANONICAL_SEQUENCE_BINDING_MISMATCH")
        bridge = model["transcriptBridge"]
        require(bridge["status"] == "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE"
                and bridge["ensemblTranscript"] == model["transcript"] and bridge["ensemblProtein"] == model["protein"]
                and bridge["refseqTranscript"] == entry["refseqTranscript"] == checked["refSeqTranscript"], "EXACT_CURRENT_SELECT_MODEL_REQUIRED")
        result[gene] = Model(model, context)
        result[gene].source_review = checked
    return result


def load_normalized(inputs, contract, manifest_path, expected_sha, originals, original_identity):
    report = inputs.json(manifest_path, expected_sha, "normalizationExecutionManifest")
    require(report.get("schemaVersion") == "public-panel-normalization-manifest-v1" and report.get("toolVersion") == "1.0.1"
            and report.get("status") == "COMPLETE" and report.get("allSourceRecordsAccountedFor") is True
            and report.get("unresolvedRecords") == 0 and report.get("samplingAllowed") is False
            and report.get("planSha256") == contract["bindings"]["normalizationPlanSha256"]
            and report.get("queryPlanSha256") == contract["bindings"]["queryPlanSha256"]
            and report.get("inputIdentities") == original_identity
            and report.get("finalPanelSelectedAlleles") == report.get("ruleEvaluations") == report.get("networkCalls") == 0,
            "COMPLETE_BOUND_NORMALIZATION_REQUIRED")
    root, contents, entries = Path(manifest_path).resolve().parent, {}, {}
    for entry in report["artifactIdentities"]:
        N.fields(entry, "file bytes sha256")
        require(entry["file"] not in entries and N.number(entry["bytes"], 0, MAX_OUTPUT), "NORMALIZATION_ARTIFACT_IDENTITY_INVALID")
        path = local_child(root, entry["file"])
        content = inputs.read(path, entry["sha256"], entry["bytes"], "normalization:" + entry["file"])
        require(len(content) == entry["bytes"], "NORMALIZATION_ARTIFACT_SIZE_MISMATCH")
        entries[entry["file"]] = entry
        if entry["file"] in ("source-normalization.jsonl", "canonical-aliases.jsonl", "window-attempts.jsonl"):
            contents[entry["file"]] = content
    require(set(contents) == {"source-normalization.jsonl", "canonical-aliases.jsonl", "window-attempts.jsonl"}, "NORMALIZATION_ARTIFACT_REQUIRED")
    budgets = dict(maximumInputRowsPerArtifact=150000, maximumSingleInputLineBytes=1048576)
    by_id, rows = {row["id"]: row for row in originals}, []
    seen, actual_groups = set(), collections.defaultdict(list)
    for ordinal, row in N.input_rows(contents["source-normalization.jsonl"], budgets):
        identifier = row.get("recordId")
        require(identifier in by_id and identifier not in seen and row.get("schemaVersion") == "public-source-allele-normalization-v1", "NORMALIZED_SOURCE_ROW_IDENTITY_INVALID")
        seen.add(identifier)
        original = by_id[identifier]
        require(row["source"] == original["source"] and row["original"] == original["original"]
                and row["memberships"] == original["memberships"] and row["sourceAlleleKey"] == original["source"]["sourceAlleleKey"]
                and row["sourceRawLineSha256s"] == [original["source"]["maskedProjection"]["sourceRawLineSha256"]], "NORMALIZED_ORIGINAL_SOURCE_MISMATCH")
        require(row["queryLocators"] == [{key: m[key] for key in ("queryGeneSymbol", "queryRegion", "queryReturnOrdinal", "occurrenceLineNumber")} for m in original["memberships"]]
                and row["geneMembershipStatuses"] == [m["geneMembershipStatus"] for m in original["memberships"]], "NORMALIZED_MEMBERSHIP_LOCATOR_MISMATCH")
        if row["normalizationStatus"] == "CANONICAL":
            allele = row["canonicalAllele"]
            N.fields(allele, "assembly contig position reference alternate")
            require(row["referenceStatus"] == "MATCH" and row["localHaplotypeEqualityVerified"] is True
                    and allele["assembly"] == "GRCh38" and allele["contig"] == original["original"]["contig"]
                    and N.envelope(allele["position"], allele["reference"], allele["alternate"]) is None
                    and row["canonicalKey"] == N.canonical_key(allele["contig"], allele["position"], allele["reference"], allele["alternate"]), "NORMALIZED_CANONICAL_PROOF_MISSING")
            actual_groups[row["canonicalKey"]].append(row)
        else:
            require(row["normalizationStatus"] == "EXCLUDED" and row["canonicalAllele"] is None and row["canonicalKey"] is None
                    and type(row["reasonCode"]) is str and row["reasonCode"], "NORMALIZED_REJECTION_NOT_ACCOUNTED")
        row["normalizationRecordLocator"] = dict(file="source-normalization.jsonl", fileSha256=entries["source-normalization.jsonl"]["sha256"], lineNumber=ordinal)
        rows.append(row)
    require(seen == set(by_id) and len(rows) == report["sourceRecords"], "NORMALIZED_SOURCE_ACCOUNTING_INCOMPLETE")
    require(sum(r["normalizationStatus"] == "CANONICAL" for r in rows) == report["canonicalSourceRecords"]
            and sum(r["normalizationStatus"] == "EXCLUDED" for r in rows) == report["excludedSourceRecords"], "NORMALIZATION_STATUS_COUNTS_MISMATCH")
    attempts = [entry for _, entry in N.input_rows(contents["window-attempts.jsonl"], budgets)]
    attempt_ids = {entry["attemptId"] for entry in attempts}
    require(len(attempt_ids) == len(attempts) and all(set(r["attemptIds"]) <= attempt_ids for r in rows), "NORMALIZATION_ATTEMPT_LINK_MISSING")
    listed = set()
    for _, group in N.input_rows(contents["canonical-aliases.jsonl"], budgets):
        key = group["canonicalKey"]
        require(key in actual_groups and key not in listed and group["sourceRecordIds"] == [r["recordId"] for r in actual_groups[key]]
                and group["canonicalAllele"] == actual_groups[key][0]["canonicalAllele"]
                and group["sourceAlleleKeys"] == sorted({r["sourceAlleleKey"] for r in actual_groups[key]})
                and group["sourceRawLineSha256s"] == sorted({h for r in actual_groups[key] for h in r["sourceRawLineSha256s"]})
                and group["geneMembershipStatuses"] == sorted({h for r in actual_groups[key] for h in r["geneMembershipStatuses"]})
                and group["selected"] is False and group["transcriptAttribution"] == "UNCHANGED_PENDING_INDEPENDENT_ATTRIBUTION", "CANONICAL_ALIAS_ACCOUNTING_MISMATCH")
        listed.add(key)
    require(listed == set(actual_groups) and len(listed) == report["uniqueCanonicalAlleles"], "CANONICAL_GROUP_ACCOUNTING_INCOMPLETE")
    return rows, actual_groups, report


EXTRA_PRIOR_SOURCE = ("PTEN_PS1_PM5_INDEPENDENT_REFERENCE_INPUTS_V1.json", "ea727a4ab9834b63a9d7ac5a4d3a85aa1e06b17d11e040cf2b0823f535c69403")
RESOLUTION_SEMANTIC_SHA = "35b183bdf4ede79f15e521b98a749977ee1614ef4d22dc8a96a9f7b0ec4c4977"
PRIOR_COMPLETION_CONTRACT_SHA = "43b4eb9a5bcbc2b0b0bddad704e7befca64fafea217f74bb63d493b1e269d196"
ANCHOR_RECORD_KEYS = "anchorId geneSymbol hgncId mondoId moi originalAllele originalSourceKey sourceFile sourceFileSha256 sourceRecordLocator originalFixedAt exposureRole criterionExposureRoles sourceRole"


def load_resolution_contract(inputs, path, expected_sha, contract_sha, frame_sha):
    value = inputs.json(path, expected_sha, "anchorIdentityResolutionContract")
    N.fields(value, "schemaVersion contractId version fixedAt reviewer status bindings scope projectionSchema completionSemantics sourceDiscoveryExposure reviewActivity")
    N.fields(value["bindings"], "originalStratificationContractSha256 frameSha256 priorProjectionSha256 priorCompletionContractSha256")
    require(value["schemaVersion"] == "frozen-anchor-identity-resolution-contract-v2" and value["version"] == "2.1.0"
            and value["status"] == "PROSPECTIVELY_FIXED_BEFORE_REAL_STRATIFICATION_OUTPUTS"
            and sha(canonical({k:value[k] for k in ("projectionSchema", "completionSemantics", "sourceDiscoveryExposure")})) == RESOLUTION_SEMANTIC_SHA
            and value["bindings"]["originalStratificationContractSha256"] == contract_sha
            and value["bindings"]["priorCompletionContractSha256"] == PRIOR_COMPLETION_CONTRACT_SHA
            and value["bindings"]["frameSha256"] == frame_sha and N.digest(value["bindings"]["priorProjectionSha256"]),
            "ANCHOR_RESOLUTION_CONTRACT_MISMATCH")
    return value


def reviewed_resolutions(projected, prior, resolution_contract):
    """Preserve each original object/role; DNA resolution adds no new anchor."""
    require(prior.get("schemaVersion") == "masked-frozen-anchor-identities-v1"
            and projected["sourceFiles"] == prior["sourceFiles"]
            and projected["bindings"]["priorProjectionSha256"] == resolution_contract["bindings"]["priorProjectionSha256"]
            and all(projected["bindings"][k] == prior["bindings"][k] for k in ("frameSha256", "prerankManifestSha256")),
            "ORIGINAL_ANCHOR_PROJECTION_BINDING_MISMATCH")
    original = {}
    for row in prior["records"] + prior["unresolvedAnchors"]:
        require(row["anchorId"] not in original, "ORIGINAL_ANCHOR_ID_AMBIGUOUS")
        original[row["anchorId"]] = row
    exact = projected["records"] + projected["supplementaryRecords"]
    current = {}
    for row in exact + projected["unresolvedAnchors"]:
        require(row["anchorId"] not in current, "ANCHOR_IDENTITY_AMBIGUOUS")
        current[row["anchorId"]] = row
    require(set(current) == set(original), "ORIGINAL_ANCHOR_UNIVERSE_CHANGED")
    for row in prior["records"]:
        require(current[row["anchorId"]] == row and row in projected["records"], "ORIGINAL_EXACT_ANCHOR_CHANGED")
    for row in projected["unresolvedAnchors"]:
        require(row == original[row["anchorId"]], "ORIGINAL_UNRESOLVED_ANCHOR_CHANGED")
    sources = {}
    require(type(projected["identitySources"]) is list and len(projected["identitySources"]) <= 10000, "IDENTITY_SOURCE_LIMIT")
    for source in projected["identitySources"]:
        N.fields(source, "sourceId relativePath url version sha256")
        require(all(type(v) is str and v for v in source.values()) and source["sourceId"] not in sources
                and source["url"].startswith("https://") and N.digest(source["sha256"]), "IDENTITY_SOURCE_INVALID")
        sources[source["sourceId"]] = source
    resolved, ids = {}, set()
    require(type(projected["identityResolutions"]) is list and len(projected["identityResolutions"]) <= 10000, "IDENTITY_RESOLUTION_LIMIT")
    for item in projected["identityResolutions"]:
        N.fields(item, "resolutionId anchorId originalProteinIdentity resolvedAt reviewer sourceId sourceLocator transcript protein cdnaHgvs proteinHgvs genomicHgvs originalAllele originalSourceKey status referenceValidationStatus normalizationStatus mechanicalStatus")
        identifier = item["anchorId"]
        require(identifier in original and original[identifier] in prior["unresolvedAnchors"] and identifier not in resolved
                and item["resolutionId"] not in ids and type(item["resolutionId"]) is str and item["resolutionId"]
                and item["sourceId"] in sources and current[identifier] in exact
                and current[identifier].get("identityResolutionId") == item["resolutionId"], "IDENTITY_RESOLUTION_LINK_INVALID")
        old, record = original[identifier], current[identifier]
        require(all(record[k] == old[k] for k in old if k not in ("originalProteinIdentity", "reason"))
                and item["originalProteinIdentity"] == old["originalProteinIdentity"] == item["proteinHgvs"]
                and item["originalAllele"] == record["originalAllele"] and item["originalSourceKey"] == record["originalSourceKey"],
                "IDENTITY_RESOLUTION_CHANGED_ORIGINAL_ROLE_OR_ALLELE")
        require(item["status"] == "PUBLIC_SOURCE_EXACT_DNA_IDENTITY_REVIEWED"
                and all(item[k] == "PENDING" for k in ("referenceValidationStatus", "normalizationStatus", "mechanicalStatus"))
                and all(type(item[k]) is str and item[k] for k in ("resolvedAt", "reviewer", "sourceLocator"))
                and re.fullmatch(r"(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*", item["transcript"])
                and re.fullmatch(r"(?:NP_[0-9]+|ENSP[0-9]+)\.[1-9][0-9]*", item["protein"])
                and re.fullmatch(r"(?:"+re.escape(item["transcript"])+r":)?c\.[^\s]+", item["cdnaHgvs"])
                and re.fullmatch(r"(?:"+re.escape(item["protein"])+r":)?p\.[^\s]+", item["proteinHgvs"])
                and re.fullmatch(r"NC_[0-9]+\.[1-9][0-9]*:g\..+", item["genomicHgvs"]), "IDENTITY_RESOLUTION_NOT_SOURCE_ONLY")
        resolved[identifier] = dict(resolution=item, source=sources[item["sourceId"]], originalProteinAnchor=old)
        ids.add(item["resolutionId"])
    require({row["anchorId"] for row in exact if "identityResolutionId" in row} == set(resolved)
            and all(row in prior["records"] or row["anchorId"] in resolved for row in exact), "NEW_OR_UNRESOLVED_ANCHOR_NOT_PERMITTED")
    return resolved


def load_anchors(inputs, contract, contexts, prerank_path, projection_path, projection_sha, resolution_contract=None, prior_path=None):
    prerank = inputs.json(prerank_path, contract["bindings"]["privatePrerankManifestSha256"], "privatePrerankManifest")
    require(prerank.get("schemaVersion") == "masked-source-prerank-freeze-v1", "PRERANK_MANIFEST_SCHEMA_INVALID")
    expected = {}
    for entry in prerank["preservedProspectiveInputs"]:
        N.fields(entry, "file sha256")
        require(entry["file"] not in expected and N.digest(entry["sha256"]), "PRERANK_SOURCE_UNIVERSE_INVALID")
        expected[entry["file"]] = entry["sha256"]
    require(expected, "FROZEN_SOURCE_UNIVERSE_MISSING")
    projected = inputs.json(projection_path, projection_sha, "maskedFrozenAnchorIdentities", 16777216)
    v2 = resolution_contract is not None
    N.fields(projected, "schemaVersion bindings sourceFiles records unresolvedAnchors sourceCoverage review"
             + (" supplementaryRecords identitySources identityResolutions" if v2 else ""))
    require(projected["schemaVersion"] == ("masked-frozen-anchor-identities-v2" if v2 else "masked-frozen-anchor-identities-v1"), "ANCHOR_PROJECTION_SCHEMA_INVALID")
    N.fields(projected["bindings"], "frameSha256 prerankManifestSha256" + (" priorProjectionSha256" if v2 else ""))
    require(projected["bindings"]["frameSha256"] == contract["bindings"]["frameSha256"]
            and projected["bindings"]["prerankManifestSha256"] == contract["bindings"]["privatePrerankManifestSha256"], "ANCHOR_FREEZE_BINDING_MISMATCH")
    resolution_evidence = {}
    if v2:
        prior = inputs.json(prior_path, resolution_contract["bindings"]["priorProjectionSha256"], "originalFrozenAnchorProjection", 16777216)
        resolution_evidence = reviewed_resolutions(projected, prior, resolution_contract)
    review = projected["review"]
    N.fields(review, "status reviewedAt reviewer allFrozenAnchorsAccountedFor exactDnaIdentityComplete")
    require(review["status"] == "REVIEWED_SOURCE_IDENTITY_PROJECTION" and review["allFrozenAnchorsAccountedFor"] is True
            and all(type(review[key]) is str and review[key] for key in ("reviewedAt", "reviewer")), "ANCHOR_PROJECTION_REVIEW_REQUIRED")
    actual = {}
    for entry in projected["sourceFiles"]:
        N.fields(entry, "file sha256")
        require(entry["file"] not in actual and N.digest(entry["sha256"]), "ANCHOR_SOURCE_IDENTITY_INVALID")
        actual[entry["file"]] = entry["sha256"]
    # This one additional source was prospectively frozen in the public prerank
    # metadata. It is an identity allowlist, not a gene-specific runtime rule.
    permitted = dict(expected)
    permitted.setdefault(*EXTRA_PRIOR_SOURCE)
    require(all(actual.get(key) == value for key, value in expected.items()) and all(permitted.get(key) == value for key, value in actual.items()),
            "ANCHOR_SOURCE_UNIVERSE_MISMATCH")
    by_gene = {context["geneSymbol"]: context for context in contexts}
    seen, records = set(), []
    supplementary = projected.get("supplementaryRecords", [])
    require(type(projected["records"]) is list and type(supplementary) is list and len(projected["records"]) + len(supplementary) <= 10000, "ANCHOR_ROW_BUDGET_EXHAUSTED")
    exact_records = projected["records"] + supplementary
    for row in exact_records:
        N.fields(row, ANCHOR_RECORD_KEYS, "identityResolutionId" if v2 else "")
        outside = row in supplementary
        require(type(row["anchorId"]) is str and row["anchorId"] and row["anchorId"] not in seen
                and all(type(row[k]) is str and row[k] for k in ("geneSymbol", "moi"))
                and (row["geneSymbol"] not in by_gene if outside else row["geneSymbol"] in by_gene), "ANCHOR_IDENTITY_AMBIGUOUS")
        context = by_gene.get(row["geneSymbol"])
        require((context is None and all(row[k] is None or type(row[k]) is str for k in ("hgncId", "mondoId")) if outside
                else all(row[key] == context[key] for key in ("geneSymbol", "hgncId", "mondoId", "moi")))
                and row["sourceRole"] == "FROZEN_PUBLIC_ANCHOR" and actual.get(row["sourceFile"]) == row["sourceFileSha256"]
                and all(type(row[key]) is str and row[key] for key in ("sourceRecordLocator", "originalFixedAt", "exposureRole")), "ANCHOR_CONTEXT_OR_SOURCE_MISMATCH")
        require(type(row["criterionExposureRoles"]) is dict and all(type(key) is str and type(value) is str and value
                for key, value in row["criterionExposureRoles"].items()), "ANCHOR_EXPOSURE_SCHEMA_INVALID")
        allele = row["originalAllele"]
        N.fields(allele, "assembly contig position reference alternate")
        require(allele["assembly"] == "GRCh38" and type(allele["contig"]) is str and N.number(allele["position"], 1)
                and all(type(allele[key]) is str and allele[key] for key in ("reference", "alternate"))
                and row["originalSourceKey"] == N.canonical_key(allele["contig"], allele["position"], allele["reference"], allele["alternate"]), "ANCHOR_ORIGINAL_ALLELE_IDENTITY_INVALID")
        seen.add(row["anchorId"])
        records.append(dict(row, projectionLocator=dict(fileSha256=projection_sha, array="supplementaryRecords" if outside else "records",
                            recordIndex0=supplementary.index(row) if outside else projected["records"].index(row)),
                            independentExpectationsLocator=dict(file=row["sourceFile"], sha256=row["sourceFileSha256"], locator=row["sourceRecordLocator"]),
                            originalAllCaseDenominatorRetained=True, outsideFixedFrame=outside,
                            **({"identityResolutionEvidence":resolution_evidence[row["anchorId"]]} if row["anchorId"] in resolution_evidence else {})))
    require(type(projected["unresolvedAnchors"]) is list and len(projected["unresolvedAnchors"]) <= 10000
            and review["exactDnaIdentityComplete"] is (not projected["unresolvedAnchors"]), "ANCHOR_EXACT_DNA_ACCOUNTING_MISMATCH")
    for row in projected["unresolvedAnchors"]:
        N.fields(row, "anchorId geneSymbol hgncId mondoId moi sourceFile sourceFileSha256 sourceRecordLocator originalFixedAt exposureRole criterionExposureRoles sourceRole originalProteinIdentity reason")
        require(type(row["anchorId"]) is str and row["anchorId"] and row["anchorId"] not in seen
                and actual.get(row["sourceFile"]) == row["sourceFileSha256"] and row["sourceRole"] == "FROZEN_PUBLIC_ANCHOR"
                and all(type(row[key]) is str and row[key] for key in ("geneSymbol", "moi", "sourceRecordLocator", "originalFixedAt", "exposureRole", "originalProteinIdentity", "reason"))
                and all(row[key] is None or type(row[key]) is str for key in ("hgncId", "mondoId"))
                and type(row["criterionExposureRoles"]) is dict and all(type(k) is str and type(v) is str and v for k,v in row["criterionExposureRoles"].items()), "UNRESOLVED_ANCHOR_PROVENANCE_INVALID")
        seen.add(row["anchorId"])
        records.append(dict(row, originalSourceKey=None, originalAllele=None, originalAllCaseDenominatorRetained=True,
                            projectionLocator=dict(fileSha256=projection_sha, unresolvedIndex0=len(records)-len(exact_records)),
                            independentExpectationsLocator=dict(file=row["sourceFile"], sha256=row["sourceFileSha256"], locator=row["sourceRecordLocator"])))
    coverage = {}
    for entry in projected["sourceCoverage"]:
        N.fields(entry, "file sha256 exactDnaRoleRecordCount unresolvedProteinAnchorCount review")
        require(entry["file"] not in coverage and actual.get(entry["file"]) == entry["sha256"]
                and type(entry["review"]) is str and entry["review"]
                and entry["exactDnaRoleRecordCount"] == sum(r["sourceFile"] == entry["file"] for r in exact_records)
                and entry["unresolvedProteinAnchorCount"] == sum(r["sourceFile"] == entry["file"] for r in projected["unresolvedAnchors"]), "ANCHOR_SOURCE_COVERAGE_MISMATCH")
        coverage[entry["file"]] = entry
    require(set(coverage) == set(actual), "ANCHOR_SOURCE_COVERAGE_INCOMPLETE")
    return records, projected["review"]


def verify_header(inputs, contract, path, source_identity):
    receipt = inputs.json(path, contract["bindings"]["populationHeaderReceiptSha256"], "historicalPopulationHeaderReceipt")
    require(receipt.get("sourceId") == source_identity["sourceId"]
            and receipt.get("headerSha256") == contract["bindings"]["populationHeaderSha256"]
            and receipt.get("sourceRowsRead") == receipt.get("networkCalls") == 0, "HISTORICAL_AF_HEADER_SOURCE_MISMATCH")
    definitions = receipt.get("fieldDefinitions")
    require(type(definitions) is list and len(definitions) == 3, "HISTORICAL_AF_FIELDS_UNBOUND")
    found = set()
    for definition in definitions:
        match = re.fullmatch(r'##INFO=<ID=(AF_ESP|AF_EXAC|AF_TGP),Number=1,Type=Float,Description="[^"]+">', definition)
        require(match is not None and match[1] not in found, "HISTORICAL_AF_HEADER_TYPE_UNSUPPORTED")
        found.add(match[1])
    require(found == set(AF_FIELDS), "HISTORICAL_AF_FIELDS_UNBOUND")


def source_gene_identity(row):
    """Reproduce the extractor's exact membership parsing, including bad tokens."""
    projection = row["source"]["maskedProjection"]
    pairs, unique, valid = [], set(), "GENEINFO" in projection
    for token in projection.get("GENEINFO", "").split("|"):
        pair = token.split(":")
        if len(pair) != 2 or not re.fullmatch(r"[A-Za-z0-9_-]+", pair[0]) or not re.fullmatch(r"[1-9][0-9]*", pair[1]) or token in unique:
            valid = False
            continue
        unique.add(token)
        pairs.append(dict(symbol=pair[0], ncbiGeneId=pair[1]))
    for member in row["memberships"]:
        matched = any(pair["symbol"] == member["queryGeneSymbol"] for pair in pairs)
        status = ("GENEINFO_MISSING_OR_MALFORMED" if not valid else "QUERY_GENE_NOT_EXACTLY_MATCHED" if not matched else
                  "MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED" if len(pairs) != 1 else "EXACT_SINGLE_GENE_MATCH")
        require(member["sourceGeneInfoPairs"] == pairs and member["geneMembershipStatus"] == status, "SOURCE_GENE_MEMBERSHIP_DERIVATION_MISMATCH")
    return valid, pairs


def load_gene_bridge(inputs, contract, contexts, models, path, expected_sha):
    bridge = inputs.json(path, expected_sha, "reviewedGeneinfoIdentityBridge")
    N.fields(bridge, "schemaVersion bridgeId version review bindings scope records boundaries")
    N.fields(bridge["review"], "status reviewId reviewedAt reviewer clinicalSignoff")
    N.fields(bridge["bindings"], "frameSha256 rnaSourceReviewSha256")
    require(bridge["schemaVersion"] == "reviewed-geneinfo-identity-bridge-v1" and bridge["version"] == "1.0.0"
            and bridge["review"]["status"] == "REVIEWED_SOURCE_IDENTITY_BRIDGE" and bridge["review"]["clinicalSignoff"] is False
            and bridge["bindings"]["frameSha256"] == contract["bindings"]["frameSha256"]
            and N.digest(bridge["bindings"]["rnaSourceReviewSha256"]), "GENEINFO_IDENTITY_BRIDGE_NOT_BOUND")
    by_gene, seen_ids = {}, set()
    for entry in bridge["records"]:
        N.fields(entry, "geneSymbol hgncId ncbiGeneId clinvarGeneinfoToken source")
        gene, identifier = entry["geneSymbol"], entry["ncbiGeneId"]
        require(gene in models and gene not in by_gene and type(identifier) is str and re.fullmatch(r"[1-9][0-9]*", identifier)
                and identifier not in seen_ids and entry["hgncId"] == models[gene].value["hgncId"]
                and entry["clinvarGeneinfoToken"] == gene+":"+identifier, "GENEINFO_BRIDGE_GENE_IDENTITY_INVALID")
        source = entry["source"]
        N.fields(source, "relativePath url sha256 transcript locator rawGeneQualifier rawGeneIdQualifier rawHgncQualifier")
        require(all(type(source[k]) is str and source[k] for k in source)
                and source["transcript"] == models[gene].value["transcriptBridge"]["refseqTranscript"]
                and source["sha256"] == models[gene].source_review["sourceFileSha256"]
                and source["rawGeneQualifier"] == '/gene="'+gene+'"'
                and source["rawGeneIdQualifier"] == '/db_xref="GeneID:'+identifier+'"'
                and source["rawHgncQualifier"] in ('/db_xref="'+entry["hgncId"]+'"', '/db_xref="HGNC:'+entry["hgncId"]+'"'), "GENEINFO_BRIDGE_SOURCE_MODEL_MISMATCH")
        by_gene[gene] = entry
        seen_ids.add(identifier)
    require(set(by_gene) == {c["geneSymbol"] for c in contexts}, "GENEINFO_BRIDGE_CONTEXT_SET_INCOMPLETE")
    return by_gene


def snv_identity(allele):
    return (type(allele) is dict and set(allele) == {"assembly", "contig", "position", "reference", "alternate"}
            and allele["assembly"] == "GRCh38" and type(allele["contig"]) is str and N.PRIMARY.fullmatch(allele["contig"])
            and N.number(allele["position"], 1) and all(type(allele[k]) is str and re.fullmatch(r"[ACGT]", allele[k]) for k in ("reference", "alternate"))
            and allele["reference"] != allele["alternate"])


def archived_reference_byte_matches(raw_hex, reference):
    if type(raw_hex) is not str or re.fullmatch(r"[0-9a-fA-F]{2}", raw_hex) is None:
        return False
    observed = bytes.fromhex(raw_hex)
    return observed[0] in b"ACGTacgt" and observed.decode("ascii").upper() == reference


def load_discovery(inputs, projection_path, expected_sha, prepare_path, vcf_path):
    value = inputs.json(projection_path, expected_sha, "reviewedSourceDiscoveryExposure")
    N.fields(value, "schemaVersion projectionId version review bindings probeCount records boundaries")
    N.fields(value["review"], "status reviewId reviewer reviewedAt allOriginalProbesAccountedFor originalReferenceReadReceiptsVerified clinicalSignoff")
    N.fields(value["bindings"], "probeManifestSha256 inputVcfSha256 executionStartSha256 executionFinishSha256 transcriptExportSha256 vepOutputSha256")
    N.fields(value["boundaries"], "selectionEffect criterionEvaluations clinicalAcceptance")
    require(value["schemaVersion"] == "reviewed-source-discovery-exposure-projection-v1" and value["version"] == "1.0.0"
            and value["review"]["status"] == "REVIEWED_COMPLETE_SOURCE_DISCOVERY_IDENTITIES"
            and all(value["review"][k] is True for k in ("allOriginalProbesAccountedFor", "originalReferenceReadReceiptsVerified"))
            and value["review"]["clinicalSignoff"] is False and all(type(value["review"][k]) is str and value["review"][k] for k in ("reviewId", "reviewer", "reviewedAt"))
            and all(N.digest(v) for v in value["bindings"].values()) and type(value["probeCount"]) is int and value["probeCount"] == 20
            and value["boundaries"] == dict(selectionEffect="NONE", criterionEvaluations=0, clinicalAcceptance=False)
            and type(value["boundaries"]["criterionEvaluations"]) is int and value["boundaries"]["clinicalAcceptance"] is False,
            "SOURCE_DISCOVERY_COMPLETE_REVIEW_REQUIRED")
    prepare = inputs.json(prepare_path, value["bindings"]["probeManifestSha256"], "originalSourceDiscoveryPrepare")
    require(prepare.get("schemaVersion") == "synthetic-transcript-source-probes-v1" and prepare.get("dataClass") == "SYNTHETIC"
            and prepare.get("formalTopN") is False and prepare.get("clinicalAcceptance") is False
            and type(prepare.get("ruleEvaluations")) is int and prepare["ruleEvaluations"] == 0
            and prepare.get("referenceQueries") == prepare.get("referenceBasesRead") == 20
            and type(prepare.get("referenceQueries")) is int and type(prepare.get("referenceBasesRead")) is int
            and prepare.get("vcfSha256") == value["bindings"]["inputVcfSha256"]
            and type(prepare.get("probes")) is list and len(prepare["probes"]) == 20, "SOURCE_DISCOVERY_ORIGINAL_PREPARE_MISMATCH")
    raw_vcf = inputs.read(vcf_path, value["bindings"]["inputVcfSha256"], 1048576, "originalSourceDiscoveryVcf")
    require(raw_vcf.endswith(b"\n"), "SOURCE_DISCOVERY_VCF_INCOMPLETE")
    lines = raw_vcf.decode("ascii").splitlines()
    header = [line for line in lines if line.startswith("#CHROM")]
    require(header == ["#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO"], "SOURCE_DISCOVERY_VCF_HEADER_INVALID")
    vcf = {}
    for line in (line for line in lines if not line.startswith("#")):
        cells = line.split("\t")
        require(len(cells) == 8 and re.fullmatch(r"[1-9][0-9]*", cells[1]) and cells[2] and cells[2] not in vcf, "SOURCE_DISCOVERY_VCF_IDENTITY_INVALID")
        allele = dict(assembly="GRCh38", contig=cells[0], position=int(cells[1]), reference=cells[3], alternate=cells[4])
        require(snv_identity(allele), "SOURCE_DISCOVERY_NATIVE_SNV_REQUIRED")
        vcf[cells[2]] = (allele, len(vcf)+1)
    require(len(vcf) == 20 and type(value["records"]) is list and len(value["records"]) == 20, "SOURCE_DISCOVERY_PROBE_SET_INCOMPLETE")
    original = {}
    for index, probe in enumerate(prepare["probes"]):
        identifier = probe["id"]
        require(identifier not in original and identifier in vcf and probe["assembly"] == "GRCh38" and probe["publicAlleleClaim"] is False,
                "SOURCE_DISCOVERY_PREPARE_IDENTITY_INVALID")
        allele = dict(assembly=probe["assembly"], contig=probe["syntheticAllele"]["contig"], position=probe["syntheticAllele"]["position"],
                      reference=probe["syntheticAllele"]["ref"], alternate=probe["syntheticAllele"]["alt"])
        read = probe["referenceRead"]
        N.fields(read, "fastaContig position1 fileOffset observedOriginalByteHex bytesRead")
        require(allele == vcf[identifier][0] and read["position1"] == allele["position"] and N.number(read["fileOffset"], 0, 2**63-1)
                and read["fastaContig"] == "chr"+allele["contig"] and type(read["bytesRead"]) is int and read["bytesRead"] == 1
                and archived_reference_byte_matches(read["observedOriginalByteHex"], allele["reference"]), "SOURCE_DISCOVERY_ARCHIVED_REFERENCE_MISMATCH")
        original[identifier] = (probe, index, allele)
    require(set(original) == set(vcf), "SOURCE_DISCOVERY_ORIGINAL_VCF_SET_MISMATCH")
    seen, by_key = set(), collections.defaultdict(list)
    for record in value["records"]:
        N.fields(record, "probeId allele canonicalKey sourceRole referenceValidationStatus sourceLocators")
        N.fields(record["sourceLocators"], "prepareProbeIndex0 vcfDataRow1 referenceRead")
        require(N.number(record["sourceLocators"]["prepareProbeIndex0"], 0, 19)
                and N.number(record["sourceLocators"]["vcfDataRow1"], 1, 20), "SOURCE_DISCOVERY_LOCATOR_TYPE_INVALID")
        identifier = record["probeId"]
        require(identifier in original and identifier not in seen, "SOURCE_DISCOVERY_PROJECTED_IDENTITY_AMBIGUOUS")
        probe, ordinal, allele = original[identifier]
        key = N.canonical_key(allele["contig"], allele["position"], allele["reference"], allele["alternate"])
        require(record["allele"] == allele and record["canonicalKey"] == key
                and record["sourceRole"] == "SYNTHETIC_TRANSCRIPT_SOURCE_DISCOVERY_ONLY"
                and record["referenceValidationStatus"] == "MATCH_FROM_ARCHIVED_SINGLE_BASE_READ"
                and record["sourceLocators"] == dict(prepareProbeIndex0=ordinal, vcfDataRow1=vcf[identifier][1], referenceRead=probe["referenceRead"]),
                "SOURCE_DISCOVERY_PROJECTION_OR_REFERENCE_MISMATCH")
        seen.add(identifier)
        by_key[key].append(record)
    require(seen == set(original), "SOURCE_DISCOVERY_PROBE_SET_INCOMPLETE")
    return dict(projectionSha256=expected_sha, probeCount=20, sourcePins=value["bindings"], review=value["review"], byCanonicalKey=dict(by_key))


def discovery_overlap(key, discovery):
    if discovery is None:
        return dict(status="UNRESOLVED_SOURCE_DISCOVERY_EXPOSURE", matchedProbeIds=[], selectionEffect="NONE")
    matched = discovery["byCanonicalKey"].get(key, [])
    return dict(status="MATCHED" if matched else "NO_MATCH_IN_COMPLETE_PINNED_PROBE_SET",
                matchedProbeIds=[row["probeId"] for row in matched], matchingProbeRecords=matched,
                probeProjectionSha256=discovery["projectionSha256"], completeProbeCount=discovery["probeCount"],
                probeSourcePins=discovery["sourcePins"], selectionEffect="NONE", priorRuleOrClinicalResultClaim=False,
                originalReferenceReadReceiptsVerified=True, freshReferenceRead=False)


def supplementary_disjoint_proof(anchor, contexts, models):
    proof = dict(operator="SOURCE_SNV_POINT_OUTSIDE_ALL_TWENTY_MODEL_CLOSED_GENOMIC_SPANS",
                 status="SUPPLEMENTARY_PENDING", modelSpans=[], sourcePoolAbsenceClaim=False,
                 referenceValidationStatus="PENDING", normalizationStatus="PENDING", mechanicalStatus="PENDING")
    allele = anchor.get("originalAllele")
    if (not snv_identity(allele) or not anchor.get("identityResolutionEvidence")
            or len(contexts) != 20 or len(models) != 20 or set(models) != {c["geneSymbol"] for c in contexts}):
        proof["reasonCode"] = "SUPPLEMENTARY_SOURCE_SNV_OR_COMPLETE_MODEL_IDENTITY_REQUIRED"
        return proof
    for context in contexts:
        model = models[context["geneSymbol"]]
        # The model already passed full source/hash/CDS/exon validation. Do not
        # trim physical UTR exons or omit the introns between its extreme ends.
        start = min(exon["genomicStart"] for exon in model.exons)
        end = max(exon["genomicEnd"] for exon in model.exons)
        intersects = (model.value["assembly"] == allele["assembly"] and model.value["contig"] == allele["contig"]
                      and start <= allele["position"] <= end)
        proof["modelSpans"].append(dict(geneSymbol=context["geneSymbol"], modelCanonicalSha256=model.identity,
            assembly=model.value["assembly"], contig=model.value["contig"], genomicStart=start, genomicEnd=end, intersects=intersects))
    if any(row["intersects"] for row in proof["modelSpans"]):
        proof["reasonCode"] = "SUPPLEMENTARY_POINT_INTERSECTS_BOUND_PHYSICAL_MODEL_SPAN"
    else:
        proof.update(status="OUTSIDE_FRAME_DISJOINT_MODEL_SPANS_IDENTITY",
                     reasonCode="SUPPLEMENTARY_REFERENCE_NORMALIZATION_AND_MECHANICAL_REVIEW_STILL_PENDING")
    return proof


def unresolved_source_disjoint_proof(aliases, contexts, models):
    """Exclude only a canonical SNV outside every complete mechanical model span.

    This proves no eligible mechanical stratum, not a source gene identity. The
    original missing/conflicting identity and every source row remain preserved.
    """
    if (not aliases or len(contexts) != 20 or len(models) != 20
            or set(models) != {context["geneSymbol"] for context in contexts}):
        return None
    allele = aliases[0].get("canonicalAllele")
    if not snv_identity(allele) or any(row.get("normalizationStatus") != "CANONICAL"
            or row.get("referenceStatus") != "MATCH" or row.get("localHaplotypeEqualityVerified") is not True
            or row.get("canonicalAllele") != allele for row in aliases):
        return None
    spans = []
    for context in contexts:
        model = models[context["geneSymbol"]]
        if model.value["assembly"] != "GRCh38" or not model.exons:
            return None
        start = min(exon["genomicStart"] for exon in model.exons)
        end = max(exon["genomicEnd"] for exon in model.exons)
        spans.append(dict(geneSymbol=context["geneSymbol"], modelCanonicalSha256=model.identity,
            assembly=model.value["assembly"], contig=model.value["contig"], genomicStart=start, genomicEnd=end,
            intersects=model.value["contig"] == allele["contig"] and start <= allele["position"] <= end))
    if any(span["intersects"] for span in spans):
        return None
    return dict(operator="CANONICAL_NATIVE_SNV_OUTSIDE_ALL_TWENTY_COMPLETE_PHYSICAL_MODEL_SPANS",
        canonicalAllele=allele, modelSpans=spans, sourceGeneIdentityResolved=False,
        geneAssignment=None, publicPanelQuotaCredit=0,
        normalizationRecordLocators=[row["normalizationRecordLocator"] for row in aliases],
        newReferenceReads=0, ruleApplicabilityClaim=False)


def assemble(contexts, models, rows, groups, anchors, gene_bridge=None, discovery=None):
    by_source, anchor_keys, anchor_records = collections.defaultdict(list), set(), []
    for row in rows:
        by_source[row["sourceAlleleKey"]].append(row)
    for anchor in anchors:
        if anchor.get("outsideFixedFrame"):
            proof = supplementary_disjoint_proof(anchor, contexts, models)
            disjoint = proof["status"] == "OUTSIDE_FRAME_DISJOINT_MODEL_SPANS_IDENTITY"
            anchor_records.append(dict(anchor, canonicalKey=None, normalizedSourceRecordIds=[], selected=False,
                reconciliationStatus=proof["status"] if disjoint else "SUPPLEMENTARY_UNRESOLVED",
                reasonCode=proof["reasonCode"], outsideFrameDisjointIdentity=disjoint, disjointnessProof=proof,
                referenceValidationStatus="PENDING", normalizationStatus="PENDING", mechanicalStatus="PENDING",
                publicPanelQuotaCredit=0))
            continue
        matches = by_source.get(anchor["originalSourceKey"], [])
        keys = {row["canonicalKey"] for row in matches if row["normalizationStatus"] == "CANONICAL"}
        consistent = matches and len(keys) == 1 and all(row["normalizationStatus"] == "CANONICAL" and row["original"] == anchor["originalAllele"] for row in matches)
        key = next(iter(keys)) if consistent else None
        if key:
            anchor_keys.add(key)
        anchor_records.append(dict(anchor, canonicalKey=key, normalizedSourceRecordIds=[r["recordId"] for r in matches],
                                   reconciliationStatus="CANONICAL_SOURCE_JOINED_PENDING_STRATUM" if key else "SUPPLEMENTARY_UNRESOLVED",
                                   reasonCode=None if key else anchor.get("reason", "FROZEN_ANCHOR_NOT_IN_COMPLETE_CANONICAL_POOL"), selected=False))
    assignments, source_accounting, issues = [], [], []
    contexts_by_gene = {c["geneSymbol"]: c for c in contexts}
    for row in rows:
        source_accounting.append(dict(recordId=row["recordId"], sourceAlleleKey=row["sourceAlleleKey"], original=row["original"],
            normalizationStatus=row["normalizationStatus"], reasonCode=row["reasonCode"], canonicalKey=row["canonicalKey"],
            source=row["source"], memberships=row["memberships"], normalizationRecordLocator=row["normalizationRecordLocator"]))
    for key, aliases in sorted(groups.items()):
        pairs, valid = [], True
        for row in aliases:
            row_valid, row_pairs = source_gene_identity(row)
            valid &= row_valid
            pairs.extend(row_pairs)
        ids_by_symbol, symbols_by_id = collections.defaultdict(set), collections.defaultdict(set)
        for pair in pairs:
            ids_by_symbol[pair["symbol"]].add(pair["ncbiGeneId"])
            symbols_by_id[pair["ncbiGeneId"]].add(pair["symbol"])
        valid &= all(len(values) == 1 for values in list(ids_by_symbol.values()) + list(symbols_by_id.values()))
        if gene_bridge is not None:
            valid &= all(pair["symbol"] not in gene_bridge or pair["ncbiGeneId"] == gene_bridge[pair["symbol"]]["ncbiGeneId"] for pair in pairs)
        genes = {pair["symbol"] for pair in pairs if pair["symbol"] in contexts_by_gene}
        if not valid:
            issue = dict(canonicalKey=key, status="UNRESOLVED", reasonCode="SOURCE_GENE_IDENTITY_UNRESOLVED",
                         sourceRecordIds=[row["recordId"] for row in aliases])
            proof = unresolved_source_disjoint_proof(aliases, contexts, models)
            if proof is not None:
                issue.update(status="EXCLUDED", reasonCode="OUTSIDE_COMPLETE_MECHANICAL_MODEL_SPANS",
                    sourceGeneIdentityStatus="UNRESOLVED", sourceGeneIdentityReasonCode="SOURCE_GENE_IDENTITY_UNRESOLVED",
                    disjointnessProof=proof)
            issues.append(issue)
            continue
        if not genes:
            issues.append(dict(canonicalKey=key, status="EXCLUDED", reasonCode="NO_EXACT_FRAME_GENE_MEMBERSHIP", sourceRecordIds=[r["recordId"] for r in aliases]))
            continue
        af = historical_af(aliases)
        for context in contexts:
            gene = context["geneSymbol"]
            if gene not in genes:
                continue
            mapping = mechanical(aliases[0]["canonicalAllele"], models[gene])
            matched = [a for a in anchor_records if a["canonicalKey"] == key and a["geneSymbol"] == gene]
            candidate = dict(candidateId=sha((gene+"\n"+key).encode()), geneSymbol=gene,
                context={k: context[k] for k in ("geneSymbol", "hgncId", "mondoId", "moi", "mechanismSamplingFamily", "exposureRole")},
                canonicalKey=key, canonicalAllele=aliases[0]["canonicalAllele"], mapping=mapping,
                sourceRecordIds=[r["recordId"] for r in aliases], sourceAlleleKeys=sorted({r["sourceAlleleKey"] for r in aliases}),
                normalizationSourceLocators=[r["normalizationRecordLocator"] for r in aliases],
                originalGeneMembershipStatuses=sorted({s for r in aliases for s in r["geneMembershipStatuses"]}),
                mechanicalGeneAttribution="EXACT_SOURCE_MEMBERSHIP_AND_BOUND_MODEL" if mapping["status"] == "ASSIGNED" else "NOT_ESTABLISHED",
                diseaseAttribution="PENDING_INDEPENDENT_REVIEW", matchingAnchorIds=[a["anchorId"] for a in matched],
                sourceGeneIdentityBridge=gene_bridge[gene] if gene_bridge is not None else "SYNTHETIC_UNIT_INPUT_WITHOUT_PRODUCTION_BRIDGE",
                anchorExposure=[dict(anchorId=a["anchorId"], exposureRole=a["exposureRole"], criterionExposureRoles=a["criterionExposureRoles"],
                                    originalFixedAt=a["originalFixedAt"], sourceFile=a["sourceFile"], sourceFileSha256=a["sourceFileSha256"],
                                    sourceRecordLocator=a["sourceRecordLocator"]) for a in matched],
                sourceDiscoveryOverlap=discovery_overlap(key, discovery), historicalAf=af,
                independentRuleExpectations="PENDING_INDEPENDENT_RULE_INPUTS",
                rankSha256=rank(gene, mapping["stratumId"], key) if mapping["status"] == "ASSIGNED" else None)
            assignments.append(candidate)
            for anchor in matched:
                anchor.update(reconciliationStatus="RECONCILED_MECHANICAL_STRATUM" if mapping["status"] == "ASSIGNED" else "SUPPLEMENTARY_UNRESOLVED",
                              mapping=mapping, reasonCode=mapping["reasonCode"])
    for anchor in anchor_records:
        if anchor["reconciliationStatus"] == "CANONICAL_SOURCE_JOINED_PENDING_STRATUM":
            anchor.update(reconciliationStatus="SUPPLEMENTARY_UNRESOLVED", reasonCode="ANCHOR_EXACT_GENE_STRATUM_NOT_ESTABLISHED")
    selected, gaps, orders = allocate(contexts, assignments, anchor_keys)
    selected_pairs = {(row["geneSymbol"], row["canonicalKey"]) for row in selected}
    for anchor in anchor_records:
        anchor["selected"] = (anchor["geneSymbol"], anchor["canonicalKey"]) in selected_pairs
        anchor["retention"] = "SELECTED_AND_ORIGINAL_DENOMINATOR_RETAINED" if anchor["selected"] else "SUPPLEMENTARY_ORIGINAL_DENOMINATOR_RETAINED"
    return dict(assignments=assignments, sourceAccounting=source_accounting, sourceIssues=issues, anchors=anchor_records,
                selected=selected, gaps=gaps, hashOrders=orders)


def candidate_output(row):
    # Fixed shared identities live once in the manifest. Full original cells
    # remain in source-accounting; selected records retain the detailed trace.
    result = {key: value for key, value in row.items() if key not in ("context", "sourceGeneIdentityBridge", "normalizationSourceLocators", "historicalAf")}
    result["contextLocator"] = dict(manifestArray="frameContexts", geneSymbol=row["geneSymbol"])
    result["sourceIdentityLocator"] = dict(file="source-accounting.jsonl", recordIds=row["sourceRecordIds"])
    result["sourceDiscoveryOverlap"] = {k:v for k,v in row["sourceDiscoveryOverlap"].items() if k != "probeSourcePins"}
    if "probeSourcePins" in row["sourceDiscoveryOverlap"]:
        result["sourceDiscoveryOverlap"]["probeSourcePinsLocator"] = dict(manifestObject="sourceDiscoveryExposure", field="sourcePins")
    if row["mapping"]["stratumId"] == "SYNONYMOUS":
        result["historicalAf"] = row["historicalAf"]
    return result


def verify_gzip_jsonl(path, expected):
    """Verify one complete member, including CRC/EOF; never return a prefix identity."""
    compressed, decoded = hashlib.sha256(), hashlib.sha256()
    physical_bytes, decoded_bytes, rows, last = 0, 0, 0, b""
    decoder = zlib.decompressobj(31)
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            require(not decoder.eof, "JSONL_GZIP_TRAILING_BYTES_OR_MEMBER")
            compressed.update(chunk)
            physical_bytes += len(chunk)
            while chunk:
                data = decoder.decompress(chunk, 65536)
                decoded_bytes += len(data)
                require(decoded_bytes <= expected["bytes"], "JSONL_DECODED_IDENTITY_MISMATCH")
                decoded.update(data)
                rows += data.count(b"\n")
                if data:
                    last = data[-1:]
                require(not decoder.unused_data, "JSONL_GZIP_TRAILING_BYTES_OR_MEMBER")
                chunk = decoder.unconsumed_tail
    require(decoder.eof, "JSONL_GZIP_INCOMPLETE")
    actual = dict(bytes=decoded_bytes, sha256=decoded.hexdigest(), rowCount=rows)
    require(actual == expected and (decoded_bytes == 0 or last == b"\n"), "JSONL_DECODED_IDENTITY_MISMATCH")
    return dict(bytes=physical_bytes, sha256=compressed.hexdigest(), decoded=actual)


def write_gzip_jsonl(store, output, logical_name, rows, identities):
    """All compressed writes, including header/trailer, use the existing byte budget."""
    identity = dict(logicalFile=logical_name, file=logical_name + ".gz", encoding="gzip",
                    decodedEncoding="UTF-8", recordDelimiter="LF", encodingComplete=False)
    identities.append(identity)
    digest, count, size = hashlib.sha256(), 0, 0
    with store.open(output / identity["file"]) as stream:
        # Empty filename forbids path/header leakage. No timestamp enters the member.
        zipped = gzip.GzipFile(filename="", mode="wb", compresslevel=6, fileobj=stream, mtime=0)
        try:
            for row in rows:
                line = canonical(row) + b"\n"
                zipped.write(line)
                digest.update(line)
                size += len(line)
                count += 1
                identity["attemptedDecoded"] = dict(bytes=size, sha256=digest.hexdigest(), rowCount=count)
        except BaseException:
            # Preserve the original failure/cancellation if finalizing a retained
            # partial member also fails. The outer writer always closes its handle.
            try:
                zipped.close()
            except BaseException:
                pass
            raise
        else:
            zipped.close()  # Trailer/close failure must precede encodingComplete.
    expected = dict(bytes=size, sha256=digest.hexdigest(), rowCount=count)
    identity.update(verify_gzip_jsonl(output / identity["file"], expected))
    identity.pop("attemptedDecoded", None)
    identity["encodingComplete"] = True


def resolve_jsonl_artifact(root, manifest, logical_name):
    """Resolve a logical locator and verify bytes; this does not authorize panel finality."""
    entries = [entry for entry in manifest["artifactIdentities"] if entry.get("logicalFile") == logical_name]
    require(len(entries) == 1, "JSONL_LOGICAL_ARTIFACT_NOT_UNIQUE")
    entry = entries[0]
    require(re.fullmatch(r"[a-z-]+\.jsonl", logical_name) and entry.get("file") == logical_name + ".gz"
            and entry.get("encoding") == "gzip" and entry.get("encodingComplete") is True
            and entry.get("decodedEncoding") == "UTF-8" and entry.get("recordDelimiter") == "LF",
            "JSONL_ARTIFACT_ENCODING_INCOMPLETE_OR_INVALID")
    root = Path(root).resolve(strict=True)
    path = (root / entry["file"]).resolve(strict=True)
    require(path.parent == root, "JSONL_ARTIFACT_PATH_INVALID")
    require(type(entry.get("bytes")) is int and 0 <= entry["bytes"] <= MAX_OUTPUT
            and path.stat().st_size == entry["bytes"], "JSONL_COMPRESSED_IDENTITY_MISMATCH")
    expected = entry["decoded"]
    require(type(expected) is dict and set(expected) == {"bytes", "sha256", "rowCount"}
            and type(expected["bytes"]) is int and expected["bytes"] >= 0
            and type(expected["rowCount"]) is int and expected["rowCount"] >= 0
            and type(expected["sha256"]) is str and re.fullmatch(r"[0-9a-f]{64}", expected["sha256"]), "JSONL_DECODED_IDENTITY_INVALID")
    observed = verify_gzip_jsonl(path, expected)
    require(observed["bytes"] == entry["bytes"] and observed["sha256"] == entry["sha256"], "JSONL_COMPRESSED_IDENTITY_MISMATCH")
    return path


def execute(options):
    inputs = Inputs()
    contract = inputs.json(options.contract, options.contract_sha256, "stratificationContract")
    frame = inputs.json(options.frame, contract["bindings"]["frameSha256"], "originalSamplingFrame")
    contexts = validate_contract(contract, frame)
    resolution_contract = load_resolution_contract(inputs, options.anchor_resolution_contract, options.anchor_resolution_contract_sha256,
                                                  options.contract_sha256, contract["bindings"]["frameSha256"])
    task_root = Path(options.task_output_root).resolve(strict=True)
    output = Path(options.output).absolute()
    require(not str(task_root).startswith(("\\\\", "//")) and not output.exists()
            and output.parent.resolve(strict=True) == task_root, "NEW_TASK_PRIVATE_OUTPUT_DIRECTORY_REQUIRED")
    output = task_root / output.name
    output.mkdir()
    store = N.Artifacts(output, MAX_OUTPUT, 1048576)
    encoded_artifacts = []
    report = dict(schemaVersion="masked-public-panel-stratification-manifest-v1", toolVersion=VERSION, status="RUNNING",
        seed=SEED, contractSha256=options.contract_sha256, inputIdentities=[], artifactIdentities=[], allSourceRecordsAccountedFor=False,
        allFrozenAnchorsReconciled=False, panelComplete=False, selectedAlleles=0, selectedRecordsFinal=False,
        clinicalAcceptance=False, ruleAcceptance=False, formalTopNAcceptance=False, ruleExpectations="PENDING_INDEPENDENT_RULE_INPUTS",
        networkCalls=0, fastaReads=0, vepCalls=0, predictorCalls=0, criterionEvaluations=0, rawClassificationRowsRead=0,
        maximumInputBytes=MAX_INPUT, maximumOutputBytes=MAX_OUTPUT, startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        serialization=dict(format="DETERMINISTIC_GZIP_JSONL_V1", gzipMtime=0, gzipFilename="", compressionLevel=6,
            locatorConvention="LOGICAL_FILE_RESOLVED_BY_ARTIFACT_IDENTITIES_DECODED_JSONL_RECORDS",
            lineConvention="ONE_BASED_DECODED_UTF8_LINES_LF_TERMINATED", incompleteEncoding="PARTIAL_UNVERIFIED_NEVER_FINAL"),
        algorithmIdentity=dict(version=VERSION, sourceSha256=sha(Path(__file__).read_bytes()),
            normalizationHelperSha256=sha(Path(N.__file__).read_bytes()), transcriptHelperSha256=sha(Path(M.__file__).read_bytes())),
        boundary="Mechanical masked sampling only; incomplete artifacts are retained and cannot authorize a partial final panel or scientific rule acceptance")
    try:
        store.json("RUNNING.json", report)
        bindings = contract["bindings"]
        query = inputs.json(options.query_plan, bindings["queryPlanSha256"], "queryPlanV2")
        normalization_plan = inputs.json(options.normalization_plan, bindings["normalizationPlanSha256"], "normalizationPlan")
        require(normalization_plan["version"] == bindings["normalizationPlanVersion"]
                and query["frameBinding"]["fileSha256"] == bindings["frameSha256"]
                and normalization_plan["bindings"]["provisionalPrerankManifestSha256"] == bindings["privatePrerankManifestSha256"]
                and all(normalization_plan["bindings"][key] == bindings[key] for key in
                        ("frameSha256", "queryPlanSha256", "poolManifestSha256", "maskedProjectionSha256", "queryOccurrencesSha256")), "ORIGINAL_INPUT_CHAIN_MISMATCH")
        windows, lookup = N.validate_plan(normalization_plan, query)
        require(inputs.bytes + normalization_plan["budgets"]["maximumInputArtifactBytes"] <= MAX_INPUT, "INPUT_BUDGET_PREFLIGHT_FAILED")
        originals, original_identity = N.load_candidates(normalization_plan, query, options.pool_manifest, options.projection, options.occurrences, windows, lookup)
        inputs.bytes += original_identity["cumulativeBytes"]
        report["originalMaskedInputIdentity"] = original_identity
        rows, groups, normalization = load_normalized(inputs, contract, options.normalization_manifest, options.normalization_manifest_sha256, originals, original_identity)
        models = load_models(inputs, contract, contexts, options.models_manifest, options.model_source_review)
        gene_bridge = load_gene_bridge(inputs, contract, contexts, models, options.geneinfo_bridge, options.geneinfo_bridge_sha256)
        verify_header(inputs, contract, options.population_header_receipt, original_identity["sourceSnapshotIdentity"])
        anchors, anchor_review = load_anchors(inputs, contract, contexts, options.prerank_manifest, options.anchor_projection, options.anchor_projection_sha256,
                                             resolution_contract, options.prior_anchor_projection)
        discovery = load_discovery(inputs, options.source_discovery_projection, options.source_discovery_projection_sha256,
                                   options.source_discovery_prepare, options.source_discovery_vcf)
        result = assemble(contexts, models, rows, groups, anchors, gene_bridge, discovery)
        for name, key in (("source-accounting.jsonl", "sourceAccounting"), ("candidate-strata.jsonl", "assignments"),
                          ("source-issues.jsonl", "sourceIssues"), ("preserved-anchors.jsonl", "anchors"),
                          ("selected-panel.jsonl", "selected"), ("missing-slots.jsonl", "gaps"), ("complete-hash-order.jsonl", "hashOrders")):
            write_gzip_jsonl(store, output, name,
                             (candidate_output(row) if key == "assignments" else row for row in result[key]), encoded_artifacts)
        unresolved = sum(row["mapping"]["status"] == "UNRESOLVED" for row in result["assignments"]) + sum(row["status"] == "UNRESOLVED" for row in result["sourceIssues"])
        unreconciled = sum(row["reconciliationStatus"] != "RECONCILED_MECHANICAL_STRATUM" for row in result["anchors"])
        counts = collections.Counter(row["geneSymbol"] for row in result["selected"])
        unique = len({row["canonicalKey"] for row in result["selected"]})
        quota_complete = not result["gaps"] and unique == len(result["selected"]) == 200 and all(counts[c["geneSymbol"]] == 10 for c in contexts)
        blocking_anchors = sum(row["reconciliationStatus"] != "RECONCILED_MECHANICAL_STRATUM"
                               and not row.get("outsideFrameDisjointIdentity", False) for row in result["anchors"])
        exposure_complete = all(row["sourceDiscoveryOverlap"]["status"] in ("MATCHED", "NO_MATCH_IN_COMPLETE_PINNED_PROBE_SET") for row in result["assignments"])
        complete = quota_complete and not unresolved and not blocking_anchors and exposure_complete
        report.update(status="COMPLETE" if complete and not unreconciled else "INCOMPLETE", panelComplete=complete, selectedRecordsFinal=complete,
            quotaComplete=quota_complete,
            allSourceRecordsAccountedFor=True, sourceRecords=len(rows), canonicalAlleles=len(groups), candidateContexts=len(result["assignments"]),
            sourceAliasesAreNotAdditionalAlleles=True, selectedAlleles=len(result["selected"]), globallyDistinctSelectedAlleles=unique,
            unresolvedCandidateContexts=unresolved, missingSlots=len(result["gaps"]), frozenAnchorRecords=len(anchors), unreconciledAnchors=unreconciled,
            allFrozenAnchorsReconciled=unreconciled == 0, anchorProjectionReview=anchor_review,
            panelBlockingAnchors=blocking_anchors, sourceDiscoveryExposureAccountedFor=exposure_complete,
            sourceDiscoveryExposure=dict(projectionSha256=discovery["projectionSha256"], probeCount=discovery["probeCount"], sourcePins=discovery["sourcePins"],
                matchingCandidateContexts=sum(row["sourceDiscoveryOverlap"]["status"] == "MATCHED" for row in result["assignments"]),
                matchingSelectedAlleles=sum(row["sourceDiscoveryOverlap"]["status"] == "MATCHED" for row in result["selected"]),
                selectionEffect="NONE", ruleLabelExposureClaim=False, originalReferenceReadReceiptsVerified=True, freshReferenceRead=False),
            anchorResolutionContractSha256=options.anchor_resolution_contract_sha256,
            supplementaryReferenceMechanicalWorkPending=sum(row.get("outsideFrameDisjointIdentity", False) for row in result["anchors"]),
            remainingSupplementaryReasons=[dict(anchorId=row["anchorId"], reasonCode=row["reasonCode"],
                referenceValidationStatus="PENDING", normalizationStatus="PENDING", mechanicalStatus="PENDING")
                for row in result["anchors"] if row.get("outsideFrameDisjointIdentity", False)],
            perGeneSelectedCounts=[dict(geneSymbol=c["geneSymbol"], selected=counts[c["geneSymbol"]], required=10) for c in contexts],
            frameContexts=[{key:c[key] for key in ("geneSymbol", "hgncId", "mondoId", "moi", "mechanismSamplingFamily", "exposureRole")} for c in contexts],
            geneinfoIdentityBridge=dict(sha256=options.geneinfo_bridge_sha256, records=list(gene_bridge.values())),
            normalizationExecution=dict(sha256=options.normalization_manifest_sha256, referenceIdentity=normalization["referenceIdentity"],
                                        normalizerIdentity=normalization["normalizerIdentity"], newReferenceReads=0),
            exactMechanicalModels=[dict(geneSymbol=c["geneSymbol"], transcript=models[c["geneSymbol"]].value["transcript"],
                                        canonicalSha256=models[c["geneSymbol"]].identity) for c in contexts])
    except (Exception, KeyboardInterrupt) as error:
        report.update(status="CANCELLED" if isinstance(error, KeyboardInterrupt) else "FAILED", reasonCode=str(error) or type(error).__name__,
                      panelComplete=False, selectedRecordsFinal=False, allSourceRecordsAccountedFor=False)
    finally:
        artifacts = store.identities()
        for entry in artifacts:
            encoding = next((value for value in encoded_artifacts if value["file"] == entry["file"]), None)
            if encoding is not None:
                if encoding["encodingComplete"] and any(entry[key] != encoding[key] for key in ("bytes", "sha256")):
                    encoding["encodingComplete"] = False
                    encoding.pop("decoded", None)
                    report.update(status="FAILED", reasonCode="JSONL_COMPRESSED_IDENTITY_CHANGED", panelComplete=False,
                                  selectedRecordsFinal=False, allSourceRecordsAccountedFor=False)
                entry.update({key:value for key,value in encoding.items() if key not in ("bytes", "sha256")})
        report.update(inputIdentities=inputs.identities, inputBytes=inputs.bytes, artifactIdentities=artifacts,
                      finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        store.json("manifest.json", report, manifest=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("contract", "contract-sha256", "frame", "query-plan", "normalization-plan", "pool-manifest", "projection", "occurrences",
                 "normalization-manifest", "normalization-manifest-sha256", "models-manifest", "model-source-review", "population-header-receipt",
                 "geneinfo-bridge", "geneinfo-bridge-sha256",
                 "prerank-manifest", "anchor-projection", "anchor-projection-sha256", "anchor-resolution-contract", "anchor-resolution-contract-sha256",
                 "prior-anchor-projection", "source-discovery-projection", "source-discovery-projection-sha256", "source-discovery-prepare", "source-discovery-vcf",
                 "task-output-root", "output"):
        parser.add_argument("--" + name, required=True)
    try:
        report = execute(parser.parse_args())
        print(json.dumps({key: report.get(key) for key in ("status", "reasonCode", "selectedAlleles", "missingSlots", "unreconciledAnchors")}))
        return 0 if report["panelComplete"] else 2
    except (Exception, KeyboardInterrupt) as error:
        print(json.dumps(dict(status="PREFLIGHT_FAILED", reasonCode=str(error) or type(error).__name__)))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
