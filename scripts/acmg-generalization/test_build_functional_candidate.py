"""Offline packaging regressions: independent tiny source, native DNA and annotation.

Synthetic contexts exercise the same builder without any source download or
claimed disease validity. Actual reviewed source execution is a separate check.
"""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("builder", Path(__file__).with_name("build_functional_candidate.py"))
b = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(b)


def workbook_bytes(cells):
    out = io.BytesIO()
    strings, xml = [], []
    for column, cell in cells.items():
        if cell["type"] == "s":
            strings.append(cell["raw"])
            assert cell["xmlStorageValue"] == str(len(strings) - 1)
        xml.append('<c r="' + column + '2" t="' + cell["type"] + '"><v>' + cell["xmlStorageValue"] + '</v></c>')
    with zipfile.ZipFile(out, "w") as z:
        z.writestr("xl/workbook.xml", '<workbook xmlns="' + b.NS["s"] + '" xmlns:r="' + b.REL_NS
                   + '"><sheets><sheet name="Experiment" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels", '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr("xl/sharedStrings.xml", '<sst xmlns="' + b.NS["s"] + '">' + ''.join('<si><t>' + s + '</t></si>' for s in strings) + '</sst>')
        z.writestr("xl/worksheets/sheet1.xml", '<worksheet xmlns="' + b.NS["s"] + '"><sheetData><row r="2">'
                   + ''.join(xml) + '</row></sheetData></worksheet>')
    return out.getvalue()


class CandidateBuilderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="functional-builder-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def put(self, name, value):
        data = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
        path = self.root / name
        path.write_bytes(data)
        return {"path": str(path), "sha256": b.sha(data)}

    def fixture(self, gene="SYNTHETIC_A", hgnc="HGNC:900001", transcript="ENST900001.1", protein="ENSP900001.1"):
        source_file = self.put("study.txt", b"Independent synthetic study source. No clinical validity.")
        model_file = self.put("model-source.txt", b"Independent synthetic ATG GGT TAA coding locus.")
        source = {"sourceId": "STUDY", "version": "synthetic-1", "url": "https://example.org/study", "sha256": source_file["sha256"]}
        model_source = {"sourceId": "MODEL", "version": "synthetic-1", "url": "https://example.org/model", "sha256": model_file["sha256"]}
        columns = dict(zip(("proteinVariantKey", "proteinVariantThreeLetter", "wildTypeCodon", "experimentalMutantCodon", "mutationType", "measuredScore", "highConfidence", "imputedScore"), "ABDEFGIK"))
        cells = {}
        for index, (column, value) in enumerate(zip("ABDEF", ["G2D", "Gly2Asp", "GGT", "GAT", "missense"])):
            cells[column] = {"type": "s", "raw": value, "xmlStorageValue": str(index)}
        for column, kind, value in [("G", "n", "-2.34567890123456789"), ("I", "b", "1"), ("K", "n", "-9")]:
            cells[column] = {"type": kind, "raw": value, "xmlStorageValue": value}
        workbook = self.put("experiment.xlsx", workbook_bytes(cells))
        workbook_source = {"sourceId": "WORKBOOK", "version": "synthetic-1", "url": "https://example.org/experiment.xlsx", "sha256": workbook["sha256"]}
        scope = {"hgncId": hgnc, "geneSymbol": gene, "mondoId": "MONDO:900001", "moi": "AUTOSOMAL_DOMINANT",
                 "assembly": "GRCh38", "variantType": "NATIVE_NONPROJECTED_SNV", "supportedVariantEffects": ["MISSENSE"], "startCodonPolicy": "EXCLUDE"}
        model = {"schemaVersion": "native-coding-transcript-model-v1", "modelId": "SYNTHETIC", "modelVersion": "1",
                 "hgncId": hgnc, "geneSymbol": gene, "assembly": "GRCh38", "contig": "1", "strand": 1,
                 "transcript": transcript, "protein": protein, "cdsSequence": "ATGGGTTAA", "cdsLength": 9, "proteinLength": 2,
                 "exons": [{"ordinal": 1, "genomicStart": 100, "genomicEnd": 108, "cdsStart": 1, "cdsEnd": 9}],
                 "sourceRecords": [model_source], "sequenceVerification": {"fastaIndexSha256": "a" * 64}}
        row = {"recordId": "ROW2", "proteinVariantKey": "G2D", "sourceId": "WORKBOOK", "sheet": "Experiment", "row": 2, "sourceCells": cells}
        raw = {"schemaVersion": "prospective-raw-source-rows-v1", "source": workbook_source, "primaryMethodsSource": source, "rows": [row]}
        allele = {"id": "SYNTHETIC_G2D", "assembly": "GRCh38", "chromosome": "1", "position": 104, "reference": "G", "alternate": "A",
                  "hgvsc": transcript + ":c.5G>A", "hgvsp": protein + ":p.Gly2Asp", "sheet": "Experiment", "row": 2, "proteinVariantKey": "G2D"}
        expectations = {"schemaVersion": "prospective-source-expectations-v1", "recordedBeforeImplementationOutputs": True,
                        "scope": scope, "sources": [source, workbook_source], "publicDevelopmentAlleles": [allele]}
        assay = {"representation": "MEASURED_SCALAR", "unit": "SYNTHETIC_MEASUREMENT", "qualityGate": "EXCEL_BOOLEAN_TRUE", "sourceId": "WORKBOOK",
                 "sourceColumns": columns, "threshold": {"operator": "LTE", "decimal": "-1.25", "strength": "MODERATE"}}
        method = dict.fromkeys(b.METHOD_FIELDS, "synthetic source review")
        method.update({"methodId": "SYNTHETIC_METHOD", "conditionId": scope["mondoId"], "unit": assay["unit"], "sourceColumns": columns,
                       "maximumStrength": "MODERATE", "sourceIds": ["STUDY", "WORKBOOK"], "evidenceDomain": "PROTEIN_FUNCTION",
                       "evidenceOrigin": "PUBLISHED_EXPERIMENTAL_MEASUREMENT", "constructTransfer": "REVIEWED_SAME_PROTEIN_CHANGE",
                       "calibrationStatus": "VCEP_NAMED_ASSAY", "combination": "SINGLE_ASSAY_NO_STRENGTH_ADDITION", "spliceConsequenceHandling": "ABSTAIN"})
        review = {"schemaVersion": "reviewed-functional-construction-inputs-v1", "inputId": "SYNTHETIC_REVIEW", "review": {
            "status": "REVIEWED_SOURCE_AND_BOUNDED_METHOD", "reviewId": "SYNTHETIC_ONLY", "reviewer": "TEST_FIXTURE"},
            "scope": scope, "nativeModels": [{"transcript": transcript, "protein": protein, "canonicalSha256": b.canonical_sha(model)}],
            "assay": assay, "method": method, "inputArtifacts": [], "activation": {"defaultEnabled": False, "classificationEnabled": False,
            "adoptionSupported": False, "clinicalAcceptance": False}}
        identity = {"fasta": {"indexSha256": "a" * 64}}
        annotation = {"resources": [{"resourceId": "VEP_TEST", "provider": "VEP", "assembly": "GRCh38", "resourceVersion": "synthetic",
            "identity": identity, "identityFingerprintSha256": b.canonical_sha(identity)}]}
        tx = {"transcript_id": transcript.split('.')[0], "protein_id": protein.split('.')[0], "gene_symbol": gene, "hgnc_id": hgnc,
              "strand": 1, "variant_allele": "A", "hgvsc": allele["hgvsc"], "hgvsp": allele["hgvsp"], "consequence_terms": ["missense_variant"],
              "exon": "1/1", "amino_acids": "G/D", "codons": "gGt/gAt", "cds_start": 5, "cds_end": 5, "protein_start": 2, "protein_end": 2}
        vep = {"assembly_name": "GRCh38", "seq_region_name": "1", "start": 104, "end": 104, "allele_string": "G/A",
               "input": "1\t104\tSYNTHETIC_G2D\tG\tA\t60\tPASS\t.", "transcript_consequences": [tx]}
        artifacts = {"expectations": self.put("expectations.json", expectations), "rawRows": self.put("rows.json", raw),
            "annotationIdentity": self.put("annotation.json", annotation), "registry": self.put("registry.json", {"schemaVersion": "acmg-rule-registry-v2",
            "registryId": "TEST_REGISTRY", "criteria": [{"criterionId": "ACMG_AMP_2015:PS3", "currentGeneral": {"allowedStrengths": ["MODERATE"]}}]}), "vepJsonl": self.put("vep.jsonl", vep),
            "vepExecutionStart": self.put("start.json", {"batchOrdinal": 1, "alleles": 1, "startedAt": "2026-09-14T00:00:00Z",
                "arguments": ["--offline", "--cache", "--json", "--hgvs", "--protein", "--symbol", "--numbers", "--mane", "--assembly", "GRCh38"]}),
            "vepExecutionFinish": self.put("finish.json", {"vepBatches": 1, "exitCode": 0, "formalTopNRun": False, "backendStarted": False, "startedAt": "2026-09-14T00:00:00Z"})}
        artifacts["vepInputManifest"] = self.put("input.json", {"schemaVersion": "varvis-isolated-annotation-check-v1", "status": "INPUTS_FIXED_BEFORE_VEP",
            "partition": "EXPOSED_DEVELOPER_NOT_HELD_OUT", "inputAlleles": 1, "formalTopNRun": False, "clinicalAcceptance": False,
            "sourceExpectations": [{"sha256": artifacts["expectations"]["sha256"]}]})
        for key in ("expectations", "rawRows"):
            value = b.parse_json(Path(artifacts[key]["path"]).read_bytes())
            review["inputArtifacts"].append({"relativePath": Path(artifacts[key]["path"]).name,
                "fileSha256": artifacts[key]["sha256"], "canonicalSha256": b.canonical_sha(value)})
        artifacts["construction"] = self.put("review.json", review)
        return {"schemaVersion": "functional-candidate-build-manifest-v1", "candidateId": "SYNTHETIC", "version": "1",
                "artifacts": artifacts, "models": {transcript: self.put("model.json", model)},
                "sourceFiles": {"STUDY": source_file, "WORKBOOK": workbook, "MODEL": model_file},
                "annotationResourceId": "VEP_TEST", "registryClasspathResource": "/acmg/rule-registry-v2/registry.json"}

    def update(self, manifest, key, modify):
        binding = manifest["artifacts"][key]
        value = b.parse_json(Path(binding["path"]).read_bytes())
        modify(value)
        manifest["artifacts"][key] = self.put(Path(binding["path"]).name, value)

    def add_reviewed_alias(self, manifest):
        original_binding = manifest["models"]["ENST900001.1"]
        original = b.parse_json(Path(original_binding["path"]).read_bytes())
        bridge = {"refseqTranscript": "NM_900001.2", "refseqProtein": "NP_900001.3",
                  "ensemblTranscript": "ENST900001.1", "ensemblProtein": "ENSP900001.1",
                  "status": "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE"}
        original["transcriptBridge"] = bridge
        original["sequenceVerification"]["fullSplicedTranscriptSha256"] = b.sha(original["cdsSequence"].encode())
        alias = copy.deepcopy(original)
        alias.update(transcript=bridge["refseqTranscript"], protein=bridge["refseqProtein"], modelId="SEPARATE_ALIAS_MODEL")
        manifest["models"] = {original["transcript"]: self.put("model.json", original), alias["transcript"]: self.put("alias.json", alias)}
        def review_alias(review):
            review["nativeModels"] = [{"transcript": m["transcript"], "protein": m["protein"], "canonicalSha256": b.canonical_sha(m)} for m in (original, alias)]
            review["reviewedBridge"] = {"status": "EXACT_REVIEWED_CDS_PROTEIN_AND_GENOMIC_EXONS", "transcriptBridge": bridge,
                "modelExonsEqual": True, "cdsEqual": True, "noVersionStripping": True, "sourceRecords": original["sourceRecords"],
                "cdsLengthIncludingStop": original["cdsLength"], "proteinLength": original["proteinLength"],
                "cdsSha256": b.sha(original["cdsSequence"].encode()), "splicedTranscriptSha256": original["sequenceVerification"]["fullSplicedTranscriptSha256"]}
        self.update(manifest, "construction", review_alias)
        self.update(manifest, "vepJsonl", lambda x: x["transcript_consequences"][0].update(mane_select=bridge["refseqTranscript"]))
        return manifest

    def test_two_distinct_synthetic_genes_use_identical_builder(self):
        for gene, hgnc, tx, protein in [("SYNTHETIC_A", "HGNC:900001", "ENST900001.1", "ENSP900001.1"),
                                       ("SYNTHETIC_B", "HGNC:900002", "NM_900002.4", "NP_900002.2")]:
            with self.subTest(gene=gene):
                candidate = b.build(self.fixture(gene, hgnc, tx, protein))[0]
                self.assertEqual(candidate["facts"]["nativeModel"]["transcript"], tx)
                self.assertEqual(candidate["policy"]["scope"]["geneSymbol"], gene)
                self.assertEqual(candidate["validation"]["typedSourceCellsVerified"], 8)
                self.assertFalse(any(candidate["activation"].values()))
                self.assertEqual(candidate["policy"]["review"]["status"], "PENDING_ASSEMBLED_BUNDLE_REVIEW")
                self.assertEqual(candidate["facts"]["records"][0]["sourceCells"]["G"]["raw"], "-2.34567890123456789")
                self.assertEqual(candidate["buildProvenance"]["additionalVepBatches"], 0)

    def test_pinned_source_bytes_changed(self):
        manifest = self.fixture()
        Path(manifest["sourceFiles"]["STUDY"]["path"]).write_bytes(b"changed")
        with self.assertRaisesRegex(b.InputError, "ARTIFACT_BYTE_SHA_MISMATCH"):
            b.build(manifest)

    def test_raw_cells_do_not_become_true_by_rehashing_json(self):
        manifest = self.fixture()
        self.update(manifest, "rawRows", lambda raw: raw["rows"][0]["sourceCells"]["I"].update(raw="true"))
        binding = manifest["artifacts"]["rawRows"]
        raw = b.parse_json(Path(binding["path"]).read_bytes())
        self.update(manifest, "construction", lambda r: r["inputArtifacts"][1].update(fileSha256=binding["sha256"], canonicalSha256=b.canonical_sha(raw)))
        with self.assertRaisesRegex(b.InputError, "SOURCE_CELL_MISMATCH"):
            b.build(manifest)

    def test_readme_cannot_mask_forged_measurement_or_boolean_source_cell(self):
        for column, forged in [("G", "-99"), ("I", "0")]:
            with self.subTest(column=column):
                manifest = self.fixture()
                def mask_raw_row(raw):
                    original = copy.deepcopy(raw["rows"][0]["sourceCells"][column])
                    raw["rows"][0]["sourceCells"][column].update(raw=forged, xmlStorageValue=forged)
                    raw["readmeSheet"] = "Experiment"
                    raw["readme"] = [{"row": 2, "cells": {column: original}}]
                self.update(manifest, "rawRows", mask_raw_row)
                binding = manifest["artifacts"]["rawRows"]
                raw = b.parse_json(Path(binding["path"]).read_bytes())
                self.update(manifest, "construction", lambda r: r["inputArtifacts"][1].update(
                    fileSha256=binding["sha256"], canonicalSha256=b.canonical_sha(raw)))
                with self.assertRaisesRegex(b.InputError, "DUPLICATE_SOURCE_CELL"):
                    b.build(manifest)

    def test_even_equal_readme_locators_cannot_overwrite_a_previous_readme_entry(self):
        manifest = self.fixture()
        raw = b.parse_json(Path(manifest["artifacts"]["rawRows"]["path"]).read_bytes())
        raw["readmeSheet"] = "README"
        raw["readme"] = [{"row": 3, "cells": {"A": {"type": "s", "raw": "same", "xmlStorageValue": "0"}}}] * 2
        with self.assertRaisesRegex(b.InputError, "DUPLICATE_SOURCE_CELL"):
            b.verify_workbook(Path(manifest["sourceFiles"]["WORKBOOK"]["path"]).read_bytes(), raw)

    def test_wrong_model_alias_hash_is_not_accepted(self):
        manifest = self.fixture()
        tx, binding = next(iter(manifest["models"].items()))
        model = b.parse_json(Path(binding["path"]).read_bytes())
        model["transcript"] = "ENST900001.2"
        manifest["models"][tx] = self.put("model.json", model)
        with self.assertRaisesRegex(b.InputError, "REVIEWED_MODEL_SHA_MISMATCH"):
            b.build(manifest)

    def test_explicit_alias_retains_original_vep_and_distinct_native_model(self):
        candidates = b.build(self.add_reviewed_alias(self.fixture()))
        self.assertEqual(len(candidates), 2)
        self.assertNotEqual(candidates[0]["policy"]["nativeModelSha256"], candidates[1]["policy"]["nativeModelSha256"])
        observed = candidates[1]["validation"]["publicDeveloperAlleles"][0]
        self.assertEqual(observed["originalVepTranscript"]["hgvsc"], "ENST900001.1:c.5G>A")
        self.assertEqual(observed["nativeIdentity"]["hgvsc"], "NM_900001.2:c.5G>A")
        self.assertEqual(observed["annotationRoute"], "EXPLICIT_REVIEWED_NATIVE_MODEL_BRIDGE_NO_VEP_RELABELING")

    def test_missing_bridge_or_mismatched_mane_does_not_enable_alias(self):
        manifest = self.add_reviewed_alias(self.fixture())
        self.update(manifest, "construction", lambda r: r.pop("reviewedBridge"))
        with self.assertRaisesRegex(b.InputError, "ALIAS_BRIDGE_REVIEW_MISSING"):
            b.build(manifest)
        manifest = self.add_reviewed_alias(self.fixture())
        self.update(manifest, "vepJsonl", lambda x: x["transcript_consequences"][0].update(mane_select="NM_900001.9"))
        with self.assertRaisesRegex(b.InputError, "VEP_MANE_ALIAS_MISMATCH"):
            b.build(manifest)

    def test_bridge_unknown_requirements_and_false_sequence_lengths_are_rejected(self):
        for field, value in [("additionalRequirements", ["unimplemented"]), ("cdsLengthIncludingStop", 999), ("proteinLength", 999)]:
            with self.subTest(field=field):
                manifest = self.add_reviewed_alias(self.fixture())
                self.update(manifest, "construction", lambda r: r["reviewedBridge"].update({field: value}))
                with self.assertRaises(b.InputError):
                    b.build(manifest)

    def test_transcript_bridge_status_and_vocabulary_are_explicit(self):
        for field, value in [("status", "UNREVIEWED"), ("additionalRequirements", ["unimplemented"])]:
            manifest = self.add_reviewed_alias(self.fixture())
            self.update(manifest, "construction", lambda r: r["reviewedBridge"]["transcriptBridge"].update({field: value}))
            with self.assertRaises(b.InputError):
                b.build(manifest)

    def test_same_protein_vep_wrong_transcript_version_is_rejected(self):
        manifest = self.fixture()
        self.update(manifest, "vepJsonl", lambda x: x["transcript_consequences"][0].update(hgvsc="ENST900001.2:c.5G>A"))
        with self.assertRaisesRegex(b.InputError, "VEP_EXACT_TRANSCRIPT"):
            b.build(manifest)

    def test_vep_local_field_and_genomic_tampering_fail(self):
        for key, value in [("codons", "gGt/gCt"), ("hgnc_id", "HGNC:900002"), ("exon", "2/2"), ("amino_acids", "G/A")]:
            with self.subTest(key=key):
                manifest = self.fixture()
                self.update(manifest, "vepJsonl", lambda x: x["transcript_consequences"][0].update({key: value}))
                with self.assertRaisesRegex(b.InputError, "VEP_DNA_PROTEIN_IDENTITY_MISMATCH"):
                    b.build(manifest)

    def test_unknown_operator_and_unreviewed_input_fail_closed(self):
        for change in [lambda x: x.update(additionalRequirements={"hiddenCeiling": True}),
                       lambda x: x["assay"]["threshold"].update(upperBound="0"),
                       lambda x: x["method"].update(extraStudies="required"),
                       lambda x: x["review"].update(status="DRAFT")]:
            manifest = self.fixture()
            self.update(manifest, "construction", change)
            with self.assertRaises(b.InputError):
                b.build(manifest)

    def test_activation_cannot_be_requested_by_input(self):
        manifest = self.fixture()
        self.update(manifest, "construction", lambda x: x["activation"].update(defaultEnabled=True))
        with self.assertRaisesRegex(b.InputError, "ACTIVATION_FORBIDDEN"):
            b.build(manifest)

    def test_annotation_resource_fingerprint_cannot_hide_changed_fasta(self):
        manifest = self.fixture()
        self.update(manifest, "annotationIdentity", lambda x: x["resources"][0]["identity"]["fasta"].update(indexSha256="b" * 64))
        with self.assertRaisesRegex(b.InputError, "ANNOTATION_RESOURCE_IDENTITY_MISMATCH"):
            b.build(manifest)

    def test_execution_assembly_and_prospective_binding(self):
        for key, change in [("vepExecutionStart", lambda x: x["arguments"].__setitem__(-1, "GRCh37")),
                            ("vepInputManifest", lambda x: x.update(sourceExpectations=[]))]:
            manifest = self.fixture()
            self.update(manifest, key, change)
            with self.assertRaises(b.InputError):
                b.build(manifest)

    def test_registry_does_not_silently_authorize_other_strength(self):
        manifest = self.fixture()
        self.update(manifest, "registry", lambda x: x["criteria"][0]["currentGeneral"].update(allowedStrengths=["SUPPORTING"]))
        with self.assertRaisesRegex(b.InputError, "REGISTRY_STRENGTH_NOT_ALLOWED"):
            b.build(manifest)

    def test_failed_quality_and_imputation_stay_typed_source_values(self):
        manifest = self.fixture()
        raw = b.parse_json(Path(manifest["artifacts"]["rawRows"]["path"]).read_bytes())
        raw["rows"][0]["sourceCells"]["I"] = {"type": "b", "raw": "0", "xmlStorageValue": "0"}
        model = b.parse_json(Path(next(iter(manifest["models"].values()))["path"]).read_bytes())
        review = b.parse_json(Path(manifest["artifacts"]["construction"]["path"]).read_bytes())
        records = b.make_records(raw, model, review)
        self.assertEqual(records[0]["sourceCells"]["I"]["raw"], "0")
        self.assertEqual(records[0]["sourceCells"]["K"]["raw"], "-9")
        self.assertNotIn("evaluation", records[0])
        self.assertEqual(records[0]["conflictReview"]["status"], "PENDING_ASSEMBLED_BUNDLE_REVIEW")

    def test_reviewed_protein_transfer_allows_different_synonymous_experimental_codon(self):
        manifest = self.fixture()
        raw = b.parse_json(Path(manifest["artifacts"]["rawRows"]["path"]).read_bytes())
        raw["rows"][0]["sourceCells"]["E"]["raw"] = "GAC"
        model = b.parse_json(Path(next(iter(manifest["models"].values()))["path"]).read_bytes())
        review = b.parse_json(Path(manifest["artifacts"]["construction"]["path"]).read_bytes())
        record = b.make_records(raw, model, review)[0]
        self.assertEqual(record["experimentalMutantCodon"], "GAC")
        self.assertEqual(record["proteinHgvs"], "ENSP900001.1:p.Gly2Asp")

    def test_canonical_rejects_duplicates_floats_and_normalized_key_collision(self):
        self.assertEqual(b.canonical_sha({"e": "e\u0301"}), b.canonical_sha({"e": "\u00e9"}))
        for data in [b'{"a":1,"a":2}', b'{"a":0.1}', b'{"a":NaN}']:
            with self.assertRaises(b.InputError):
                b.parse_json(data)
        with self.assertRaises(b.InputError):
            b.canonical_sha({"e\u0301": 1, "\u00e9": 2})


if __name__ == "__main__":
    unittest.main()
