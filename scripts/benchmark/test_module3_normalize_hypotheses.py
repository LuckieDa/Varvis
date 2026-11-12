import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "benchmark"))

from diagnostic_hypothesis_contract import ContractError, hypothesis_id, validate_batch
from module3_normalize_hypotheses import (
    HPO_HASH_RULE,
    LIRICAL_GENE_RULE,
    LIRICAL_VARIANT_STATUS,
    Module3Error,
    _case_documents,
    cleanup_derived_temp,
    _hpo_hash,
    normalize_case,
)


class Module3NormalizerTest(unittest.TestCase):
    def setUp(self):
        self.request = {
            "requestId": "synthetic-g1-request",
            "caseId": "synthetic-case",
            "groupId": "G1",
            "selectionOrder": 1,
            "vcfSha256": "a" * 64,
            "tbiSha256": "b" * 64,
            "hpoTerms": ["HP:0000001"],
            "assembly": "GRCh38",
            "truthFree": True,
        }
        self.artifacts = [
            self.artifact(
                "artifact-exomiser-genes-synthetic",
                "EXOMISER",
                "exomiser-15.1-genes-tsv",
            ),
            self.artifact(
                "artifact-exomiser-variants-synthetic",
                "EXOMISER",
                "exomiser-15.1-variants-tsv",
            ),
            self.artifact(
                "artifact-lirical-projection-synthetic",
                "LIRICAL",
                "lirical-corrected-projection-v2",
            ),
            self.artifact(
                "artifact-lirical-tsv-synthetic",
                "LIRICAL",
                "lirical-2.4-native-disease-tsv",
            ),
        ]
        self.exo_genes = [
            self.gene_row(1, "101", "GENEA", "0.95"),
            self.gene_row(2, "202", "GENEB", "0.80"),
        ]
        self.exo_variants = [
            self.variant_row(1, "101", "GENEA", "1", "100", "A", "G", "0.99"),
            self.variant_row(2, "202", "GENEB", "2", "200", "C", "T", "0.75"),
        ]
        self.projection = {
            "schemaVersion": "lirical-corrected-projection-v2",
            "requestId": self.request["requestId"],
            "caseId": self.request["caseId"],
            "groupId": "G1",
            "selectionOrder": 1,
            "truthRead": False,
            "geneProjectionRule": LIRICAL_GENE_RULE,
            "variantRankStatus": LIRICAL_VARIANT_STATUS,
            "nativeDiseaseRanks": [
                self.disease_row(1, "OMIM:1", "NCBIGene:101", "GENEA"),
                self.disease_row(2, "ORPHA:2", "NCBIGene:101", "GENEA"),
            ],
            "geneRanks": [
                {
                    "rank": 1,
                    "geneId": "NCBIGene:101",
                    "geneSymbol": "GENEA",
                    "firstDiseaseRank": 1,
                    "firstDiseaseId": "OMIM:1",
                }
            ],
        }
        self.labels = {(1, "OMIM:1"): "Disease A", (2, "ORPHA:2"): "Disease B"}

    def artifact(self, artifact_id, engine, schema):
        return {
            "schemaVersion": "raw-engine-result-ref-v1",
            "artifactId": artifact_id,
            "engine": engine,
            "engineVersion": "synthetic",
            "runId": "synthetic-run",
            "requestId": self.request["requestId"],
            "artifactPath": f"external://{artifact_id}",
            "artifactSha256": "c" * 64,
            "artifactSchema": schema,
            "assembly": "GRCh38",
            "resourceVersions": {"resource": "synthetic"},
            "inputHashes": {
                "vcfSha256": self.request["vcfSha256"],
                "hpoSha256": _hpo_hash(self.request["hpoTerms"]),
            },
            "lockStatus": "LOCKED",
            "lockedAt": "2026-07-27T00:00:00Z",
            "provenance": {
                "producer": "synthetic",
                "createdAt": "2026-07-27T00:00:00Z",
                "verificationCommand": "synthetic",
            },
        }

    @staticmethod
    def gene_row(rank, gene_id, symbol, score):
        return {
            "#RANK": str(rank),
            "ID": f"{symbol}_AD",
            "GENE_SYMBOL": symbol,
            "ENTREZ_GENE_ID": gene_id,
            "MOI": "AD",
            "P-VALUE": "0.01",
            "EXOMISER_GENE_COMBINED_SCORE": score,
            "EXOMISER_GENE_PHENO_SCORE": "0.70",
            "EXOMISER_GENE_VARIANT_SCORE": "0.90",
            "PHIVE_ALL_SPECIES_SCORE": "0.70",
        }

    @staticmethod
    def variant_row(rank, gene_id, symbol, chrom, pos, ref, alt, score):
        return {
            "#RANK": str(rank),
            "ID": f"{chrom}-{pos}-{ref}-{alt}_AD",
            "GENE_SYMBOL": symbol,
            "ENTREZ_GENE_ID": gene_id,
            "MOI": "AD",
            "CONTIG": chrom,
            "START": pos,
            "REF": ref,
            "ALT": alt,
            "EXOMISER_VARIANT_SCORE": score,
            "GENOTYPE": "0/1",
            "FILTER": "PASS",
            "QUAL": "100",
            "FUNCTIONAL_CLASS": "MISSENSE_VARIANT",
            "HGVS": "synthetic",
            "EXOMISER_ACMG_CLASSIFICATION": "",
            "EXOMISER_ACMG_EVIDENCE": "",
            "CLINVAR_PRIMARY_INTERPRETATION": "",
            "MAX_FREQ": "0.001",
        }

    @staticmethod
    def disease_row(rank, disease_id, gene_id, symbol):
        return {
            "rank": rank,
            "diseaseId": disease_id,
            "posttestProbability": 0.8 / rank,
            "compositeLR": 10.0 / rank,
            "geneId": gene_id,
            "geneSymbol": symbol,
            "genotypeEvidence": {
                "geneId": gene_id,
                "matchType": "LIRICAL_GT_MODEL",
                "lr": 1.0,
                "explanation": "synthetic",
            },
            "phenotypeEvidence": {"observed": [], "excluded": []},
            "supportingVariants": "synthetic variant evidence",
        }

    def normalize(self, **overrides):
        arguments = {
            "request": copy.deepcopy(self.request),
            "raw_artifacts": copy.deepcopy(self.artifacts),
            "exomiser_gene_rows": copy.deepcopy(self.exo_genes),
            "exomiser_variant_rows": copy.deepcopy(self.exo_variants),
            "lirical_projection": copy.deepcopy(self.projection),
            "disease_labels": copy.deepcopy(self.labels),
            "created_at": "2026-07-27T00:00:00Z",
        }
        arguments.update(overrides)
        return normalize_case(**arguments)

    def test_valid_normalization_preserves_native_endpoints(self):
        batch, summary = self.normalize()
        validate_batch(batch)
        endpoints = [item["endpointType"] for item in batch["evidence"]]
        self.assertIn("GENE_NATIVE_RANK", endpoints)
        self.assertIn("VARIANT_NATIVE_RANK", endpoints)
        self.assertIn("DISEASE_NATIVE_RANK", endpoints)
        self.assertIn("GENE_DERIVED_DENSE_RANK", endpoints)
        self.assertEqual(9, summary["normalizedEvidence"])

    def test_one_gene_can_form_multiple_disease_hypotheses(self):
        batch, summary = self.normalize()
        gene_a = [
            item
            for item in batch["hypotheses"]
            if item["geneIdentities"][0]["stableId"] == "NCBIGene:101"
        ]
        self.assertEqual(2, len(gene_a))
        self.assertEqual(2, summary["diseaseGeneHypotheses"])

    def test_exomiser_only_gene_is_preserved(self):
        batch, summary = self.normalize()
        gene_b = [
            item
            for item in batch["hypotheses"]
            if item["geneIdentities"][0]["stableId"] == "NCBIGene:202"
        ]
        self.assertEqual(1, len(gene_b))
        self.assertEqual([], gene_b[0]["diseaseIdentities"])
        self.assertEqual(1, summary["exomiserOnlyGeneHypotheses"])

    def test_lirical_variant_rank_is_explicitly_not_comparable(self):
        batch, _ = self.normalize()
        states = [
            item
            for item in batch["evidence"]
            if item["engine"] == "LIRICAL"
            and item["endpointType"] == "NOT_COMPARABLE"
        ]
        self.assertEqual(1, len(states))
        self.assertNotIn("nativeRank", states[0])
        self.assertEqual(LIRICAL_VARIANT_STATUS, states[0]["unavailableReason"])

    def test_exomiser_disease_rank_is_not_invented(self):
        batch, _ = self.normalize()
        states = [
            item
            for item in batch["evidence"]
            if item["engine"] == "EXOMISER"
            and item["endpointType"] == "NOT_COMPARABLE"
        ]
        self.assertEqual(1, len(states))

    def test_exact_gene_match_is_counted_without_cross_endpoint_rank(self):
        batch, summary = self.normalize()
        self.assertEqual(1, summary["exactMatchedGenes"])
        self.assertFalse(batch["fusionApplied"])
        self.assertNotIn("fusionScore", json.dumps(batch))

    def test_gene_id_symbol_conflict_fails_closed(self):
        projection = copy.deepcopy(self.projection)
        projection["nativeDiseaseRanks"][0]["geneSymbol"] = "OTHER"
        with self.assertRaises(Module3Error):
            self.normalize(lirical_projection=projection)

    def test_invalid_variant_allele_fails_closed(self):
        variants = copy.deepcopy(self.exo_variants)
        variants[0]["ALT"] = "<DEL>"
        with self.assertRaises(Module3Error):
            self.normalize(exomiser_variant_rows=variants)

    def test_truth_field_in_source_payload_is_rejected(self):
        projection = copy.deepcopy(self.projection)
        projection["nativeDiseaseRanks"][0]["phenotypeEvidence"]["truthGene"] = "X"
        with self.assertRaises((Module3Error, ContractError)):
            self.normalize(lirical_projection=projection)

    def test_fusion_field_in_source_payload_is_rejected(self):
        projection = copy.deepcopy(self.projection)
        projection["nativeDiseaseRanks"][0]["genotypeEvidence"]["fusionScore"] = 1
        with self.assertRaises((Module3Error, ContractError)):
            self.normalize(lirical_projection=projection)

    def test_source_engine_mismatch_is_rejected(self):
        artifacts = copy.deepcopy(self.artifacts)
        artifacts[0]["engine"] = "LIRICAL"
        with self.assertRaises((Module3Error, ContractError, KeyError)):
            self.normalize(raw_artifacts=artifacts)

    def test_hypothesis_ids_are_stable(self):
        batch, _ = self.normalize()
        for hypothesis in batch["hypotheses"]:
            self.assertEqual(hypothesis["hypothesisId"], hypothesis_id(hypothesis))

    def test_hpo_hash_is_ordered_and_validated(self):
        self.assertEqual(_hpo_hash(["HP:0000001"]), _hpo_hash(["HP:0000001"]))
        self.assertNotEqual(
            _hpo_hash(["HP:0000001", "HP:0000002"]),
            _hpo_hash(["HP:0000002", "HP:0000001"]),
        )
        with self.assertRaises(Module3Error):
            _hpo_hash(["HP:1"])
        self.assertEqual("ordered-json-array-sha256-v1", HPO_HASH_RULE)

    def test_case_documents_do_not_copy_hpo_terms(self):
        batch, summary = self.normalize()
        documents = _case_documents(
            batch,
            self.request,
            summary,
            "2026-07-27T00:00:00Z",
            {"exomiser": "d" * 64, "lirical": "e" * 64},
        )
        encoded = json.dumps(documents)
        self.assertNotIn("HP:0000001", encoded)
        self.assertIn("hpoSha256", encoded)

    def test_round_trip_is_deterministic(self):
        first, first_summary = self.normalize()
        second, second_summary = self.normalize()
        self.assertEqual(first, json.loads(json.dumps(first, sort_keys=True)))
        self.assertEqual(first, second)
        self.assertEqual(first_summary, second_summary)

    def test_all_evidence_is_traceable_to_locked_artifact(self):
        batch, _ = self.normalize()
        artifact_ids = {item["artifactId"] for item in batch["rawArtifacts"]}
        self.assertTrue(
            all(item["sourceArtifactId"] in artifact_ids for item in batch["evidence"])
        )
        self.assertTrue(
            all(item["lockStatus"] == "LOCKED" for item in batch["rawArtifacts"])
        )

    def test_temporary_cleanup_is_strictly_scoped(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            allowed = root / "allowed"
            allowed.mkdir()
            target = allowed / "module3-recalculation"
            target.mkdir()
            (target / "temporary.json").write_text("{}", encoding="utf-8")
            result = cleanup_derived_temp(allowed, target)
            self.assertEqual("PASS", result["status"])
            self.assertFalse(target.exists())
            recalculation_root = root / "recalculation-root"
            recalculation_root.mkdir()
            nested = recalculation_root / "run-id"
            nested.mkdir()
            cleanup_derived_temp(recalculation_root, nested)
            self.assertFalse(nested.exists())
            unsafe = allowed / "final-run"
            unsafe.mkdir()
            with self.assertRaises(Module3Error):
                cleanup_derived_temp(allowed, unsafe)


if __name__ == "__main__":
    unittest.main()
