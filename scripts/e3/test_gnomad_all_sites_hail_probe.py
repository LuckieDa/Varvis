#!/usr/bin/env python3
"""Offline contract tests for the bounded all-sites Hail probe."""

from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts/e3/gnomad_all_sites_hail_probe.py"
PROFILE_PATH = ROOT / "docs/execution/E3_P0R_GNOMAD_REMOTE_RESOURCE_PROFILE.json"
SPEC = importlib.util.spec_from_file_location("gnomad_all_sites_hail_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class GnomadAllSitesHailProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.profile = json.loads(PROFILE_PATH.read_text(encoding="utf-8"))
        cls.resources = PROBE.resources_by_id(cls.profile)

    def test_profile_resources_are_probe_only_and_official(self) -> None:
        self.assertEqual(self.profile["status"], "RESOURCE_PROBE_ONLY")
        self.assertFalse(self.profile["activation"]["runtimeBound"])
        self.assertFalse(self.profile["activation"]["evaluationEnabled"])
        for resource_id in PROBE.RESOURCE_IDS:
            resource = self.resources[resource_id]
            self.assertIs(PROBE.verify_resource_identity(resource), resource["identity"])
            self.assertEqual(resource["assembly"], "GRCh38")
            self.assertTrue(
                resource["identity"]["tableUri"].startswith(
                    "gs://gcp-public-data--gnomad/release/4.1/ht/"
                )
            )
            self.assertEqual(
                resource["identity"]["strataResolution"]["selector"],
                {"group": "adj"},
            )
            self.assertFalse(
                resource["identity"]["strataResolution"]["fixedArrayIndexAllowed"]
            )
        self.assertEqual(PROBE.GCS_CONNECTOR_VERSION, "2.2.33")
        self.assertEqual(PROBE.GCS_CONNECTOR_BYTES, 38604231)
        self.assertEqual(len(PROBE.GCS_CONNECTOR_SHA256), 64)

    def test_strata_selector_is_exact_and_not_a_fixed_index(self) -> None:
        resolved = PROBE.resolve_strata(
            [{"group": "raw"}, {"group": "adj"}, {"group": "adj", "sex": "XX"}],
            [10, 8, 4],
            {"group": "adj"},
        )
        self.assertEqual(resolved["selectedIndex"], 1)
        self.assertEqual(resolved["selectedMetadata"], {"group": "adj"})
        self.assertEqual(resolved["selectedSampleCount"], 8)
        self.assertEqual(len(resolved["mapping"]), 3)

    def test_strata_selector_fails_closed_on_absent_or_duplicate_match(self) -> None:
        with self.assertRaisesRegex(PROBE.ProbeError, "ADJ_STRATA_SELECTOR_NOT_UNIQUE"):
            PROBE.resolve_strata([{"group": "raw"}], [10], {"group": "adj"})
        with self.assertRaisesRegex(PROBE.ProbeError, "ADJ_STRATA_SELECTOR_NOT_UNIQUE"):
            PROBE.resolve_strata(
                [{"group": "adj"}, {"group": "adj"}],
                [8, 8],
                {"group": "adj"},
            )

    def test_hit_and_no_record_cases_preserve_four_axes(self) -> None:
        resource = self.resources[PROBE.RESOURCE_IDS[0]]
        strata = PROBE.resolve_strata(
            [{"group": "raw"}, {"group": "adj"}],
            [10, 8],
            {"group": "adj"},
        )
        hit_row = {
            "AN": [20, 16],
            **{field: False for field in PROBE.EXOME_REGION_FIELDS},
        }
        cases = PROBE.build_cases(
            resource,
            {("chr3", 10052405): hit_row},
            strata,
        )
        self.assertEqual(cases[0]["axes"]["resourceExecution"]["status"], "SUCCEEDED")
        self.assertEqual(cases[0]["axes"]["queryOutcome"]["status"], "HIT")
        self.assertEqual(cases[0]["measurement"]["adjAlleleNumber"], 16)
        self.assertEqual(cases[1]["axes"]["queryOutcome"]["status"], "NO_RECORD")
        self.assertNotIn("measurement", cases[1])

    def test_fixed_hit_drift_or_invalid_an_fails_closed(self) -> None:
        resource = self.resources[PROBE.RESOURCE_IDS[1]]
        strata = PROBE.resolve_strata([{"group": "adj"}], [8], {"group": "adj"})
        with self.assertRaisesRegex(PROBE.ProbeError, "FIXED_CASE_ALL_SITES_AN_HIT_DRIFTED"):
            PROBE.build_cases(resource, {}, strata)
        with self.assertRaisesRegex(PROBE.ProbeError, "ADJ_ALLELE_NUMBER_INVALID"):
            PROBE.build_cases(
                resource,
                {("chr3", 10052405): {"AN": [None]}},
                strata,
            )

    def test_boundary_rejects_clinical_results_and_machine_paths(self) -> None:
        safe = {"status": "VERIFIED", "boundary": {"clinicalRuleEvaluationRun": False}}
        PROBE.verify_boundary(safe)
        with self.assertRaisesRegex(PROBE.ProbeError, "CLINICAL_PAYLOAD_LEAKED"):
            PROBE.verify_boundary({"criterion": "MET"})
        with self.assertRaisesRegex(PROBE.ProbeError, "CLINICAL_PAYLOAD_LEAKED"):
            PROBE.verify_boundary(
                {"evaluationResult": {"status": "NOT_EVALUATED"}}
            )
        with self.assertRaisesRegex(PROBE.ProbeError, "MACHINE_PATH_LEAKED"):
            PROBE.verify_boundary({"log": "/tmp/hail.log"})
        with self.assertRaisesRegex(PROBE.ProbeError, "MACHINE_PATH_LEAKED"):
            PROBE.verify_boundary({"log": "F:/synthetic/hail.log"})
        with self.assertRaisesRegex(PROBE.ProbeError, "MACHINE_PATH_LEAKED"):
            PROBE.verify_boundary({"log": "/root/hail.log"})

    def test_resource_identity_rejects_fingerprint_or_uri_drift(self) -> None:
        resource = json.loads(json.dumps(self.resources[PROBE.RESOURCE_IDS[0]]))
        resource["identityFingerprintSha256"] = "0" * 64
        with self.assertRaisesRegex(PROBE.ProbeError, "RESOURCE_IDENTITY_MISMATCH"):
            PROBE.verify_resource_identity(resource)
        resource = json.loads(json.dumps(self.resources[PROBE.RESOURCE_IDS[0]]))
        resource["identity"]["tableUri"] = (
            "gs://gcp-public-data--gnomad/release/4.1/ht/other/table.ht"
        )
        resource["identityFingerprintSha256"] = PROBE.sha256_json(
            resource["identity"]
        )
        with self.assertRaisesRegex(PROBE.ProbeError, "RESOURCE_IDENTITY_MISMATCH"):
            PROBE.verify_resource_identity(resource)

    def test_probe_source_keeps_interval_and_row_bounds(self) -> None:
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn("hl.filter_intervals(table, intervals, keep=True)", source)
        self.assertIn("bounded.n_partitions()", source)
        self.assertIn("bounded.take(3)", source)
        self.assertNotIn(".collect()", source)

    def test_batch_request_deduplicates_query_loci_but_preserves_callers(self) -> None:
        request = PROBE.normalize_batch_request(
            {
                "requestSchemaVersion": PROBE.BATCH_REQUEST_SCHEMA_VERSION,
                "requestId": "batch-1",
                "assembly": "GRCh38",
                "loci": [
                    {"locusId": "v1", "canonicalContig": "3", "position": 10},
                    {"locusId": "v2", "canonicalContig": "3", "position": 10},
                    {"locusId": "v3", "canonicalContig": "X", "position": 20},
                ],
            }
        )
        self.assertEqual(len(request["loci"]), 3)
        self.assertEqual(len(request["uniqueLoci"]), 2)
        self.assertEqual(request["uniqueLoci"][0]["resourceContig"], "chr3")
        self.assertEqual(request["uniqueLoci"][1]["resourceContig"], "chrX")

    def test_batch_hit_no_record_and_dynamic_adj_index(self) -> None:
        resource = self.resources[PROBE.RESOURCE_IDS[0]]
        request = PROBE.normalize_batch_request(
            {
                "requestSchemaVersion": PROBE.BATCH_REQUEST_SCHEMA_VERSION,
                "requestId": "batch-2",
                "assembly": "GRCh38",
                "loci": [
                    {"locusId": "hit", "canonicalContig": "3", "position": 10},
                    {"locusId": "missing", "canonicalContig": "3", "position": 11},
                ],
            }
        )
        strata = PROBE.resolve_strata(
            [{"group": "raw"}, {"group": "adj"}],
            [10, 8],
            {"group": "adj"},
        )
        observed = {
            ("chr3", 10): {
                "AN": [20, 16],
                **{field: False for field in PROBE.EXOME_REGION_FIELDS},
            }
        }
        loci = PROBE.build_batch_loci(resource, request["loci"], observed, strata)
        self.assertEqual(loci[0]["axes"]["queryOutcome"]["status"], "HIT")
        self.assertEqual(loci[0]["measurement"]["adjAlleleNumber"], 16)
        self.assertEqual(loci[0]["measurement"]["adjStrataIndex"], 1)
        self.assertEqual(
            set(loci[0]["measurement"]["regionFlags"]),
            set(PROBE.EXOME_REGION_FIELDS),
        )
        self.assertEqual(
            loci[1]["axes"]["queryOutcome"]["status"],
            "NO_RECORD",
        )
        self.assertNotIn("measurement", loci[1])
        self.assertNotIn("AF", PROBE.canonical_json(loci))
        for locus in loci:
            fingerprint = locus["axes"]["queryOutcome"]["queryFingerprint"]
            self.assertRegex(fingerprint, r"^[0-9a-f]{64}$")
        self.assertEqual(
            loci[0]["axes"]["queryOutcome"]["queryFingerprint"],
            "104c16d579251db5238dfb65571291910fa39bba6ba69b0dc781f0627ce7116b",
        )

    def test_batch_request_rejects_machine_path_and_unsupported_assembly(self) -> None:
        with self.assertRaisesRegex(PROBE.ProbeError, "MACHINE_PATH_LEAKED"):
            PROBE.normalize_batch_request(
                {
                    "requestSchemaVersion": PROBE.BATCH_REQUEST_SCHEMA_VERSION,
                    "requestId": "F:/private/request.json",
                    "assembly": "GRCh38",
                    "loci": [
                        {"locusId": "v1", "canonicalContig": "1", "position": 1}
                    ],
                }
            )
        with self.assertRaisesRegex(PROBE.ProbeError, "BATCH_ASSEMBLY_UNSUPPORTED"):
            PROBE.normalize_batch_request(
                {
                    "requestSchemaVersion": PROBE.BATCH_REQUEST_SCHEMA_VERSION,
                    "requestId": "batch-3",
                    "assembly": "GRCh37",
                    "loci": [
                        {"locusId": "v1", "canonicalContig": "1", "position": 1}
                    ],
                }
            )

    def test_hail_session_initializes_once_for_two_resource_queries(self) -> None:
        class FakeHail:
            def __init__(self) -> None:
                self.init_calls = 0
                self.stop_calls = 0

            def init(self, **_kwargs: object) -> None:
                self.init_calls += 1

            def stop(self) -> None:
                self.stop_calls += 1

        fake_hail = FakeHail()
        queried: list[str] = []

        def query_both_resources() -> list[str]:
            for resource_id in PROBE.RESOURCE_IDS:
                queried.append(resource_id)
            return queried

        result = PROBE.run_hail_session(
            fake_hail,
            ROOT,
            ROOT / "synthetic-connector.jar",
            query_both_resources,
        )
        self.assertEqual(fake_hail.init_calls, 1)
        self.assertEqual(fake_hail.stop_calls, 1)
        self.assertEqual(result, list(PROBE.RESOURCE_IDS))


if __name__ == "__main__":
    unittest.main()
