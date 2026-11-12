import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "benchmark"))

from diagnostic_hypothesis_contract import (  # noqa: E402
    CONTRACT_ROOT,
    ContractError,
    SCHEMAS,
    SCHEMA_ROOT,
    _validate_schema,
    hypothesis_id,
    validate_batch,
    validate_post_lock_interface,
)


FIXTURE = (
    CONTRACT_ROOT
    / "synthetic"
    / "diagnostic-hypothesis-batch.synthetic.json"
)


class DiagnosticHypothesisContractTest(unittest.TestCase):
    def setUp(self):
        self.batch = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def assert_rejected(self, mutation):
        candidate = copy.deepcopy(self.batch)
        mutation(candidate)
        with self.assertRaises(ContractError):
            validate_batch(candidate)

    def test_01_valid_exomiser_variant_and_gene_endpoints(self):
        validate_batch(self.batch)
        endpoints = {
            item["endpointType"]
            for item in self.batch["evidence"]
            if item["engine"] == "EXOMISER"
        }
        self.assertEqual({"VARIANT_NATIVE_RANK", "GENE_NATIVE_RANK"}, endpoints)

    def test_02_valid_lirical_disease_and_derived_gene_endpoints(self):
        validate_batch(self.batch)
        endpoints = {
            item["endpointType"]
            for item in self.batch["evidence"]
            if item["engine"] == "LIRICAL"
        }
        self.assertEqual(
            {"DISEASE_NATIVE_RANK", "GENE_DERIVED_DENSE_RANK"}, endpoints
        )

    def test_03_shared_gene_preserves_parallel_sources(self):
        validate_batch(self.batch)
        gene_a = [
            item
            for item in self.batch["evidence"]
            if item.get("gene", {}).get("stableId") == "HGNC:100"
        ]
        self.assertEqual(3, len(gene_a))
        self.assertEqual({"EXOMISER", "LIRICAL"}, {item["engine"] for item in gene_a})

    def test_04_one_to_many_disease_identities_are_preserved(self):
        candidate = copy.deepcopy(self.batch)
        disease = candidate["hypotheses"][0]["diseaseIdentities"][0]
        disease["curies"].append("ORPHA:1001")
        candidate["hypotheses"][0]["hypothesisId"] = hypothesis_id(
            candidate["hypotheses"][0]
        )
        validate_batch(candidate)
        self.assertEqual(2, len(disease["curies"]))

    def test_05_lirical_native_variant_rank_is_rejected(self):
        self.assert_rejected(
            lambda batch: batch["evidence"][2].update(
                {"endpointType": "VARIANT_NATIVE_RANK"}
            )
        )

    def test_06_derived_rank_without_derivation_is_rejected(self):
        self.assert_rejected(lambda batch: batch["evidence"][3].pop("derivation"))

    def test_07_silent_cross_assembly_merge_is_rejected(self):
        def mutate(batch):
            variant = batch["evidence"][0]["variant"]
            variant["assembly"] = "GRCh37"
            variant["canonicalId"] = "GRCh37:1:123:A:G"

        self.assert_rejected(mutate)

    def test_08_ref_alt_or_variant_identity_conflict_is_rejected(self):
        def mutate(batch):
            duplicate = copy.deepcopy(batch["evidence"][0])
            duplicate["evidenceId"] = "evidence-exomiser-conflict"
            duplicate["variant"]["alt"] = "T"
            batch["evidence"].append(duplicate)

        self.assert_rejected(mutate)

    def test_09_gene_id_symbol_conflict_is_rejected(self):
        self.assert_rejected(
            lambda batch: batch["evidence"][1]["gene"].update({"symbol": "OTHER"})
        )

    def test_10_disease_free_text_only_is_rejected(self):
        self.assert_rejected(
            lambda batch: batch["evidence"][2]["disease"].pop("curies")
        )

    def test_11_explicit_unavailable_state_is_accepted_without_fake_rank(self):
        candidate = copy.deepcopy(self.batch)
        unavailable = candidate["evidence"][3]
        unavailable.update(
            {
                "endpointType": "UNAVAILABLE",
                "mappingStatus": "UNAVAILABLE",
                "support": "UNAVAILABLE",
                "unavailableReason": "endpoint not emitted",
            }
        )
        unavailable.pop("nativeRank")
        unavailable.pop("derivation")
        validate_batch(candidate)
        self.assert_rejected(
            lambda batch: (
                batch["evidence"][3].update(
                    {
                        "endpointType": "UNAVAILABLE",
                        "mappingStatus": "UNAVAILABLE",
                        "support": "UNAVAILABLE",
                    }
                ),
                batch["evidence"][3].pop("nativeRank"),
                batch["evidence"][3].pop("derivation"),
            )
        )

    def test_12_truth_and_benchmark_fields_are_rejected(self):
        for key in ("truthVariant", "expectedRank", "hitAtK", "missTaxonomy"):
            with self.subTest(key=key):
                self.assert_rejected(lambda batch, key=key: batch.update({key: "x"}))

    def test_13_future_fusion_and_review_fields_are_rejected(self):
        for key in ("fusionScore", "decisionRuleVersion", "aiReview", "clinicianReview"):
            with self.subTest(key=key):
                self.assert_rejected(lambda batch, key=key: batch.update({key: "x"}))

    def test_14_provenance_sha_lock_and_traceability_are_required(self):
        self.assert_rejected(
            lambda batch: batch["rawArtifacts"][0].pop("artifactSha256")
        )
        self.assert_rejected(
            lambda batch: batch["rawArtifacts"][0].update({"lockStatus": "UNLOCKED"})
        )
        self.assert_rejected(
            lambda batch: batch["evidence"][0].update(
                {"sourceArtifactId": "artifact-does-not-exist"}
            )
        )

    def test_15_unknown_fields_and_schema_versions_are_rejected(self):
        self.assert_rejected(lambda batch: batch.update({"unknown": True}))
        self.assert_rejected(
            lambda batch: batch.update(
                {"schemaVersion": "diagnostic-hypothesis-batch-v2"}
            )
        )

    def test_16_round_trip_and_stable_hypothesis_ids(self):
        encoded = json.dumps(self.batch, sort_keys=True)
        decoded = json.loads(encoded)
        validate_batch(decoded)
        for hypothesis in decoded["hypotheses"]:
            self.assertEqual(hypothesis["hypothesisId"], hypothesis_id(hypothesis))

    def test_all_five_schemas_are_parseable_and_self_identifying(self):
        for version, filename in SCHEMAS.items():
            with self.subTest(schema=version):
                schema = json.loads((SCHEMA_ROOT / filename).read_text(encoding="utf-8"))
                self.assertEqual(
                    f"urn:varvis:schema:{version}",
                    schema["$id"],
                )
                _validate_schema(
                    version,
                    (
                        self.batch["rawArtifacts"][0]
                        if version == "raw-engine-result-ref-v1"
                        else self.batch["evidence"][0]
                        if version == "normalized-engine-evidence-v1"
                        else self.batch["hypotheses"][0]
                        if version == "diagnostic-hypothesis-v1"
                        else self.batch
                        if version == "diagnostic-hypothesis-batch-v1"
                        else {
                            "schemaVersion": "post-lock-hypothesis-evaluation-interface-v1",
                            "evaluationStatus": "NOT_RUN",
                            "hypothesisBatchSha256": "a" * 64,
                            "outputsLockedAt": "2026-07-27T00:00:00Z",
                            "truthInputRef": {
                                "artifactId": "future-controlled-ref",
                                "sha256": "b" * 64,
                            },
                            "truthFirstReadAt": "2026-07-27T00:00:01Z",
                        }
                    ),
                )

    def test_post_lock_interface_enforces_truth_read_order(self):
        interface = {
            "schemaVersion": "post-lock-hypothesis-evaluation-interface-v1",
            "evaluationStatus": "NOT_RUN",
            "hypothesisBatchSha256": "a" * 64,
            "outputsLockedAt": "2026-07-27T00:00:00Z",
            "truthInputRef": {
                "artifactId": "future-controlled-ref",
                "sha256": "b" * 64,
            },
            "truthFirstReadAt": "2026-07-27T00:00:01Z",
        }
        validate_post_lock_interface(interface)
        interface["truthFirstReadAt"] = interface["outputsLockedAt"]
        with self.assertRaises(ContractError):
            validate_post_lock_interface(interface)

    def test_freeze_manifest_artifact_hashes_match(self):
        manifest_path = CONTRACT_ROOT / "FREEZE_MANIFEST.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for artifact in manifest["artifacts"]:
            with self.subTest(path=artifact["path"]):
                digest = hashlib.sha256(
                    (ROOT / artifact["path"]).read_bytes()
                ).hexdigest()
                self.assertEqual(artifact["sha256"], digest)


if __name__ == "__main__":
    unittest.main()
