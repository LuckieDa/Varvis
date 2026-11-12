#!/usr/bin/env python3
"""Offline checks for the E3-P0R gnomAD resource-probe candidate profile."""

from __future__ import annotations

import hashlib
import json
import re
import runpy
import sys
import unittest
import unicodedata
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


sys.dont_write_bytecode = True

REPO_ROOT = Path(__file__).resolve().parents[2]
PROFILE_PATH = (
    REPO_ROOT
    / "docs"
    / "execution"
    / "E3_P0R_GNOMAD_REMOTE_RESOURCE_PROFILE.json"
)
SMOKE_PATH = (
    REPO_ROOT
    / "docs"
    / "execution"
    / "E3_P0R_GNOMAD_REMOTE_RESOURCE_SMOKE.json"
)
PROBE_PATH = REPO_ROOT / "scripts" / "e3" / "gnomad_remote_resource_probe.py"
V1_PROFILE_PATH = (
    REPO_ROOT
    / "varvis-server"
    / "src"
    / "main"
    / "resources"
    / "acmg"
    / "gnomad-population-input-profile-v1.json"
)
ABSOLUTE_LOCAL_PATH = re.compile(
    r"(?:^[A-Za-z]:[\\/]|^\\\\|^file:|^/(?!/))", re.IGNORECASE
)
SENSITIVE_KEY = re.compile(r"(?:credential|password|secret|token)", re.IGNORECASE)


def load(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def walk(value: Any):
    yield value
    if isinstance(value, dict):
        for nested in value.values():
            yield from walk(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from walk(nested)


def canonical_value(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if isinstance(value, list):
        return [canonical_value(item) for item in value]
    if isinstance(value, dict):
        return {
            unicodedata.normalize("NFC", str(key)): canonical_value(item)
            for key, item in value.items()
        }
    raise TypeError(f"unsupported canonical JSON type: {type(value).__name__}")


def canonical_sha256(value: Any) -> str:
    encoded = json.dumps(
        canonical_value(value),
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


class GnomadRemoteResourceProbeProfileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.profile = load(PROFILE_PATH)
        cls.probe_module = runpy.run_path(str(PROBE_PATH))
        cls.resources = {
            resource["resourceId"]: resource
            for resource in cls.profile["resources"]
        }

    def test_profile_is_probe_only_and_does_not_self_hash_or_enable_evaluation(self) -> None:
        self.assertEqual(
            "gnomad-population-input-profile-v2-candidate",
            self.profile["profileSchemaVersion"],
        )
        self.assertEqual(
            "GNOMAD_POPULATION_INPUT_V2_CANDIDATE", self.profile["profileId"]
        )
        self.assertEqual("RESOURCE_PROBE_ONLY", self.profile["status"])
        self.assertFalse(self.profile["activation"]["runtimeBound"])
        self.assertFalse(self.profile["activation"]["evaluationEnabled"])
        self.assertFalse(self.profile["activation"]["patientFlowEnabled"])
        self.assertFalse(self.profile["activation"]["classificationEnabled"])
        self.assertFalse(
            self.profile["resourceIdentitySemantics"]["byteIntegrityClaimed"]
        )
        self.assertNotIn("profileSha256", self.profile)

        for value in walk(self.profile):
            if isinstance(value, dict):
                self.assertFalse(any(SENSITIVE_KEY.search(key) for key in value))
                self.assertFalse(
                    {"criteria", "criterionEvaluations", "criterionResult"}
                    & set(value)
                )
            elif isinstance(value, str):
                self.assertIsNone(ABSOLUTE_LOCAL_PATH.search(value), value)
                self.assertNotIn(value, {"MET", "NOT_MET"})

    def test_three_resource_identities_have_recomputable_canonical_hashes(self) -> None:
        self.assertEqual(
            {
                "GNOMAD_JOINT_V4_1_GRCH38_SITES",
                "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN",
                "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN",
            },
            set(self.resources),
        )
        self.assertEqual(3, len(self.profile["resources"]))
        for resource in self.resources.values():
            self.assertEqual("GNOMAD", resource["provider"])
            self.assertEqual("GRCh38", resource["assembly"])
            self.assertEqual(resource["provider"], resource["identity"]["provider"])
            self.assertEqual(resource["assembly"], resource["identity"]["assembly"])
            self.assertEqual(
                canonical_sha256(resource["identity"]),
                resource["identityFingerprintSha256"],
                resource["resourceId"],
            )

        uris = [
            self.resources["GNOMAD_JOINT_V4_1_GRCH38_SITES"]["identity"][
                "dataUriTemplate"
            ],
            self.resources["GNOMAD_JOINT_V4_1_GRCH38_SITES"]["identity"][
                "indexUriTemplate"
            ],
            self.resources["GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN"][
                "identity"
            ]["tableUri"],
            self.resources["GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN"][
                "identity"
            ]["tableUri"],
        ]
        for resource_id in (
            "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN",
            "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN",
        ):
            uris.extend(
                anchor["httpsUri"]
                for anchor in self.resources[resource_id]["identity"][
                    "metadataAnchors"
                ]
            )
        for uri in uris:
            parsed = urlsplit(uri)
            self.assertIn(parsed.scheme, {"https", "gs"})
            self.assertIsNone(parsed.username)
            self.assertIsNone(parsed.password)
            self.assertEqual("", parsed.query)
            self.assertEqual("", parsed.fragment)

    def test_joint_identity_freezes_sizes_fields_filters_and_qc_only_conflict(self) -> None:
        identity = self.resources["GNOMAD_JOINT_V4_1_GRCH38_SITES"]["identity"]
        self.assertEqual(24, identity["dataComponentCount"])
        self.assertEqual(876875887798, identity["dataTotalBytes"])
        self.assertEqual(24, identity["indexComponentCount"])
        self.assertEqual(2831328, identity["indexTotalBytes"])
        self.assertEqual(
            [str(value) for value in range(1, 23)] + ["X", "Y"],
            identity["chromosomes"],
        )

        expected_primary = {
            "AC_joint": ("A", "Integer"),
            "AN_joint": ("1", "Integer"),
            "AF_joint": ("A", "Float"),
            "nhomalt_joint": ("A", "Integer"),
            "grpmax_joint": ("A", "String"),
            "AC_grpmax_joint": ("A", "Integer"),
            "AN_grpmax_joint": ("A", "Integer"),
            "AF_grpmax_joint": ("A", "Float"),
            "nhomalt_grpmax_joint": ("A", "Integer"),
            "faf95_joint": ("A", "Float"),
            "fafmax_faf95_max_joint": ("A", "Float"),
            "fafmax_faf95_max_gen_anc_joint": ("A", "String"),
        }
        actual_primary = {
            field["infoId"]: (field["number"], field["type"])
            for field in identity["fieldContract"]["primaryFields"]
        }
        self.assertEqual(expected_primary, actual_primary)
        self.assertEqual(
            {
                "AC_exomes": ("A", "Integer"),
                "AN_exomes": ("1", "Integer"),
                "AF_exomes": ("A", "Float"),
                "AC_genomes": ("A", "Integer"),
                "AN_genomes": ("1", "Integer"),
                "AF_genomes": ("A", "Float"),
                "exomes_filters": (".", "String"),
                "genomes_filters": (".", "String"),
            },
            {
                field["infoId"]: (field["number"], field["type"])
                for field in identity["fieldContract"]["componentFields"]
            },
        )
        self.assertEqual(
            {
                "AC_joint_{ancestry}": ("A", "Integer", None),
                "AN_joint_{ancestry}": ("1", "Integer", None),
                "AF_joint_{ancestry}": ("A", "Float", None),
                "nhomalt_joint_{ancestry}": ("A", "Integer", None),
                "faf95_joint_{ancestry}": (
                    "A",
                    "Float",
                    ("afr", "amr", "eas", "mid", "nfe", "sas"),
                ),
            },
            {
                field["infoIdTemplate"]: (
                    field["number"],
                    field["type"],
                    tuple(field["appliesTo"]) if "appliesTo" in field else None,
                )
                for field in identity["fieldContract"]["ancestryFieldTemplates"]
            },
        )
        self.assertEqual(
            {
                "stat_union_p_value": ("A", "Float"),
                "stat_union_test_name": ("A", "String"),
                "stat_union_gen_ancs": (".", "String"),
            },
            {
                field["infoId"]: (field["number"], field["type"])
                for field in identity["fieldContract"]["auxiliaryQcFields"]
            },
        )
        self.assertEqual(
            {"PASS", "EXOMES_FILTERED", "GENOMES_FILTERED", "BOTH_FILTERED"},
            set(self.resources["GNOMAD_JOINT_V4_1_GRCH38_SITES"]["inputSemantics"]["filterSemantics"]),
        )
        self.assertEqual(
            "Either passed all variant filters in both exomes and genomes, or passed all variant filters in either exomes or genomes while being absent from the other dataset",
            self.resources["GNOMAD_JOINT_V4_1_GRCH38_SITES"]["inputSemantics"]["filterSemantics"]["PASS"],
        )
        conflict = self.resources["GNOMAD_JOINT_V4_1_GRCH38_SITES"]["inputSemantics"]["frequencyConflictQc"]
        self.assertEqual("stat_union_p_value", conflict["sourceField"])
        self.assertEqual("LESS_THAN", conflict["conflictOperator"])
        self.assertEqual("1e-4", conflict["thresholdRaw"])
        self.assertEqual("QC_ONLY", conflict["status"])
        self.assertEqual("UNKNOWN", conflict["missingValueStatus"])
        self.assertEqual("NONE", conflict["automaticDecisionEffect"])

        cases = {
            case["caseId"]: case
            for case in self.resources["GNOMAD_JOINT_V4_1_GRCH38_SITES"][
                "probeState"
            ]["cases"]
        }
        self.assertEqual(
            {
                "PASS_HIT": ("GRCh38", "1", "chr1", 12591, "C", "T", "HIT"),
                "EXACT_ALLELE_NO_RECORD": (
                    "GRCh38",
                    "1",
                    "chr1",
                    12591,
                    "C",
                    "A",
                    "NO_RECORD",
                ),
                "DISCORDANCE_QC_HIT": (
                    "GRCh38",
                    "1",
                    "chr1",
                    55006970,
                    "G",
                    "T",
                    "HIT",
                ),
            },
            {
                case_id: (
                    case["queryAllele"]["assembly"],
                    case["queryAllele"]["canonicalContig"],
                    case["queryAllele"]["resourceContig"],
                    case["queryAllele"]["position"],
                    case["queryAllele"]["reference"],
                    case["queryAllele"]["alternate"],
                    case["queryOutcome"]["status"],
                )
                for case_id, case in cases.items()
            },
        )
        for case in cases.values():
            self.assertEqual("SUCCEEDED", case["resourceExecution"]["status"])
            self.assertEqual("AVAILABLE", case["resourceAvailability"]["status"])
            self.assertEqual("APPLICABLE", case["resourceApplicability"]["status"])
            self.assertEqual(
                1 if case["queryOutcome"]["status"] == "HIT" else 0,
                case["queryOutcome"]["recordCount"],
            )

    def test_all_sites_tables_remain_distinct_and_fixed_queries_are_verified(self) -> None:
        expected = {
            "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN": {
                "dataset": "exomes",
                "bytes": 69713764492,
                "partitions": 9557,
                "rows": 222684797,
                "sampleCount": 730947,
                "mappingEntryCount": 329,
                "adjAlleleNumber": 1458958,
                "extraFields": {
                    "outside_broad_capture_region",
                    "outside_ukb_capture_region",
                    "outside_broad_calling_region",
                    "outside_ukb_calling_region",
                },
                "metadataAnchors": {
                    "README.txt": (148, 1713312041430075, "d936393dd3b1be6b091f31c325681a6c"),
                    "metadata.json.gz": (26036, 1713312295446395, "515af03ae299cfbda26026e935ff7372"),
                },
            },
            "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN": {
                "dataset": "genomes",
                "bytes": 120595947830,
                "partitions": 9546,
                "rows": 2873082661,
                "sampleCount": 76215,
                "mappingEntryCount": 34,
                "adjAlleleNumber": 152256,
                "extraFields": set(),
                "metadataAnchors": {
                    "README.txt": (148, 1713312131592548, "d9f6f9db717e9b098bb481f2123aa976"),
                    "metadata.json.gz": (10444, 1713312085432898, "5fe09a8b8fc3211ad5430fdca793f440"),
                },
            },
        }
        for resource_id, frozen in expected.items():
            resource = self.resources[resource_id]
            identity = resource["identity"]
            self.assertEqual(frozen["dataset"], identity["dataset"])
            self.assertEqual(frozen["bytes"], identity["totalBytes"])
            self.assertEqual(frozen["partitions"], identity["partitionCount"])
            self.assertEqual(frozen["rows"], identity["rowCount"])
            self.assertEqual("0.2.122-be9d88a80695", identity["writerHailVersion"])
            self.assertEqual(67328, identity["nativeFileVersion"])
            self.assertEqual(
                frozen["metadataAnchors"],
                {
                    anchor["logicalSuffix"]: (
                        anchor["byteLength"],
                        anchor["generation"],
                        anchor["etag"],
                    )
                    for anchor in identity["metadataAnchors"]
                },
            )
            self.assertEqual(["locus"], identity["keyFields"])
            row_fields = {field["field"]: field["type"] for field in identity["rowSchema"]}
            self.assertEqual("Locus<GRCh38>", row_fields.pop("locus"))
            self.assertEqual("Array<Int64>", row_fields.pop("AN"))
            self.assertEqual(frozen["extraFields"], set(row_fields))
            self.assertEqual(
                {
                    "strata_meta": "Array[Dict[str,str]]",
                    "strata_sample_count": "Array[Int32]",
                },
                {
                    field["field"]: field["type"]
                    for field in identity["globalsSchema"]
                },
            )
            resolution = identity["strataResolution"]
            self.assertEqual({"group": "adj"}, resolution["selector"])
            self.assertEqual(
                "MATCH_STRATA_META_ENTRY_AT_QUERY_TIME",
                resolution["indexResolution"],
            )
            self.assertFalse(resolution["fixedArrayIndexAllowed"])

            probe = resource["probeState"]
            self.assertEqual("VERIFIED", probe["metadataStatus"])
            self.assertEqual("VERIFIED", probe["remotePointQueryStatus"])
            self.assertEqual(
                "NOT_IMPLEMENTED", probe["runtimeIntegrationStatus"]
            )
            self.assertEqual(
                "PUBLIC_FIXED_LOCUS_HAIL_FILTER_INTERVALS",
                probe["verificationScope"],
            )
            selection = probe["observedStrataSelection"]
            self.assertEqual({"group": "adj"}, selection["selector"])
            self.assertEqual(0, selection["selectedIndex"])
            self.assertEqual({"group": "adj"}, selection["selectedMetadata"])
            self.assertEqual(frozen["sampleCount"], selection["selectedSampleCount"])
            self.assertEqual(
                frozen["mappingEntryCount"], selection["mappingEntryCount"]
            )
            cases = {case["caseId"]: case for case in probe["cases"]}
            self.assertEqual(
                "HIT", cases["ALL_SITES_AN_HIT"]["queryOutcome"]["status"]
            )
            self.assertEqual(
                frozen["adjAlleleNumber"],
                cases["ALL_SITES_AN_HIT"]["measurement"]["adjAlleleNumber"],
            )
            self.assertEqual(
                "NO_RECORD",
                cases["ALL_SITES_AN_NO_RECORD"]["queryOutcome"]["status"],
            )
            self.assertNotIn("measurement", cases["ALL_SITES_AN_NO_RECORD"])

        semantics = self.profile["datasetSemantics"]
        self.assertTrue(semantics["allSitesRemotePointQueryVerified"])
        self.assertFalse(semantics["noRecordInputReady"])
        self.assertEqual("DEFERRED", semantics["allSitesAnMergeSemantics"])
        self.assertEqual("NOT_READY", semantics["generalInputReadiness"]["status"])

    def test_probe_evidence_labels_and_bounds_are_explicit_without_network(self) -> None:
        probe = self.probe_module
        timestamp = "2026-09-01T00:00:00Z"
        no_record = probe["no_record_case"](
            "TEST_RESOURCE", [], 12591, "C", "A", timestamp, timestamp
        )
        self.assertEqual("EXACT_ALLELE_NO_RECORD", no_record["caseId"])
        self.assertEqual(
            {
                "assembly": "GRCh38",
                "canonicalContig": "1",
                "resourceContig": "chr1",
                "position": 12591,
                "reference": "C",
                "alternate": "A",
            },
            no_record["variant"],
        )
        self.assertNotIn("recordedCompressedLogicalReadEstimate", no_record)
        self.assertNotIn("remoteReadBound", no_record)

        info = {
            field_id: "1"
            for field_id in probe["OUTPUT_FIELDS"]
        }
        info.update(
            {
                "grpmax_joint": "nfe",
                "fafmax_faf95_max_gen_anc_joint": "nfe",
                "stat_union_p_value": "1e-5",
                "stat_union_test_name": "contingency_table_test",
                "stat_union_gen_ancs": "afr,nfe",
            }
        )
        discordance_hit = probe["hit_case"](
            "TEST_RESOURCE",
            probe["DISCORDANCE_QC_HIT_CASE_ID"],
            [
                {
                    "chromosome": "chr1",
                    "position": 55006970,
                    "reference": "G",
                    "alternates": ["T"],
                    "filter": "PASS",
                    "info": info,
                }
            ],
            55006970,
            "G",
            "T",
            timestamp,
            timestamp,
        )
        self.assertEqual("DISCORDANCE_QC_HIT", discordance_hit["caseId"])

        transport = probe["transport_evidence"](
            {"contentLength": 10},
            {"bytesRead": 1},
            {"contentLength": 5},
            {"bytesRead": 1},
        )
        self.assertEqual(2, transport["explicitOneByteProbeBodyBytesRead"])
        self.assertNotIn("rangeBodyBytesRead", transport)

        source = PROBE_PATH.read_text(encoding="utf-8")
        self.assertNotIn("TBI_DERIVED", source)
        self.assertNotIn("FREQUENCY_DISCORDANCE_HIT", source)

    def test_all_sites_output_separates_live_heads_from_declared_schema_offline(self) -> None:
        verify = self.probe_module["verify_all_sites_identity"]
        observed_by_uri = {}
        resources = [
            self.resources["GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN"],
            self.resources["GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN"],
        ]
        for resource in resources:
            for anchor in resource["identity"]["metadataAnchors"]:
                observed_by_uri[anchor["httpsUri"]] = {
                    "contentLength": anchor["byteLength"],
                    "generation": str(anchor["generation"]),
                    "etag": anchor["etag"],
                }

        function_globals = verify.__globals__
        original_head_request = function_globals["head_request"]
        function_globals["head_request"] = lambda uri: observed_by_uri[uri]
        try:
            results = [verify(resource) for resource in resources]
        finally:
            function_globals["head_request"] = original_head_request

        for result in results:
            self.assertEqual(
                {
                    "anchorHeadStatus": "ANCHOR_HEAD_MATCHED",
                    "schemaSource": "PROFILE_DECLARED",
                },
                result["metadataVerification"],
            )
            self.assertIn("liveAnchorHeadMetadata", result)
            self.assertNotIn("profileDeclaredSchema", result)
            self.assertNotIn("liveMetadata", result)
            self.assertNotIn("table", result)
            self.assertNotIn("profileFixedPointState", result)

    def _synthetic_hail_artifact(self) -> dict[str, Any]:
        probe = self.probe_module
        artifact_resources = []
        for resource_id in probe["ALL_SITES_RESOURCE_IDS"]:
            resource = self.resources[resource_id]
            state = resource["probeState"]
            selection = state["observedStrataSelection"]
            mapping = [
                {
                    "index": index,
                    "metadata": (
                        selection["selectedMetadata"]
                        if index == selection["selectedIndex"]
                        else {"group": "synthetic", "index": str(index)}
                    ),
                    "sampleCount": (
                        selection["selectedSampleCount"]
                        if index == selection["selectedIndex"]
                        else index
                    ),
                }
                for index in range(selection["mappingEntryCount"])
            ]
            allele_numbers = [0] * selection["mappingEntryCount"]
            expected_cases = {case["caseId"]: case for case in state["cases"]}
            allele_numbers[selection["selectedIndex"]] = expected_cases[
                "ALL_SITES_AN_HIT"
            ]["measurement"]["adjAlleleNumber"]
            cases = []
            for case_id in ("ALL_SITES_AN_HIT", "ALL_SITES_AN_NO_RECORD"):
                expected_case = expected_cases[case_id]
                case = {
                    "caseId": case_id,
                    "queryLocus": expected_case["queryLocus"],
                    "axes": probe["profile_case_axes"](expected_case),
                }
                if case_id == "ALL_SITES_AN_HIT":
                    measurement = {
                        "adjAlleleNumber": expected_case["measurement"][
                            "adjAlleleNumber"
                        ],
                        "adjStrataIndex": selection["selectedIndex"],
                        "alleleNumberArray": allele_numbers,
                    }
                    if "regionFlags" in expected_case["measurement"]:
                        measurement["regionFlags"] = expected_case["measurement"][
                            "regionFlags"
                        ]
                    case["measurement"] = measurement
                cases.append(case)
            artifact_resources.append(
                {
                    "resourceId": resource_id,
                    "resourceVersion": resource["resourceVersion"],
                    "assembly": resource["assembly"],
                    "identityFingerprintSha256": resource[
                        "identityFingerprintSha256"
                    ],
                    "officialTableUri": resource["identity"]["tableUri"],
                    "execution": {
                        "startedAt": "2026-09-01T00:00:00Z",
                        "completedAt": "2026-09-01T00:00:01Z",
                        "queryMode": "HAIL_FILTER_INTERVALS_FIXED_PUBLIC_LOCI",
                        "maximumRowsCollected": 3,
                        "partitionCountPlanned": 2,
                    },
                    "strata": {
                        "selector": selection["selector"],
                        "selectedIndex": selection["selectedIndex"],
                        "selectedMetadata": selection["selectedMetadata"],
                        "selectedSampleCount": selection["selectedSampleCount"],
                        "mapping": mapping,
                    },
                    "cases": cases,
                }
            )
        return {
            "probeSchemaVersion": probe["ALL_SITES_HAIL_PROBE_SCHEMA_VERSION"],
            "status": "VERIFIED",
            "observedAt": "2026-09-01T00:00:01Z",
            "profile": {
                "profileId": self.profile["profileId"],
                "profileVersion": self.profile["profileVersion"],
                "profileSha256": probe["sha256_json"](self.profile),
            },
            "tool": {
                **self.probe_module["EXPECTED_HAIL_TOOL"],
                "javaVersion": 'openjdk version "11.0.32" test-build',
            },
            "execution": {
                "startedAt": "2026-09-01T00:00:00Z",
                "completedAt": "2026-09-01T00:00:01Z",
            },
            "resources": artifact_resources,
            "boundary": {
                "fixedPublicLociOnly": True,
                "indexedIntervalsOnly": True,
                "fullHailTableDownloaded": False,
                "derivedResourceCacheCreated": False,
                "runtimeIntegrationRun": False,
                "patientDataUsed": False,
                "clinicalRuleEvaluationRun": False,
                "machinePathsIncluded": False,
            },
        }

    def test_all_sites_hail_artifact_is_bound_and_compacted_offline(self) -> None:
        artifact = self._synthetic_hail_artifact()
        result = self.probe_module["validate_all_sites_hail_result"](
            artifact, self.profile, self.resources
        )
        self.assertEqual(
            set(self.probe_module["ALL_SITES_RESOURCE_IDS"]),
            set(result["resources"]),
        )
        for resource in result["resources"].values():
            self.assertGreater(resource["strata"]["mappingEntryCount"], 0)
            self.assertEqual(64, len(resource["strata"]["mappingSha256"]))
            hit = resource["cases"][0]["measurement"]
            self.assertEqual(64, len(hit["alleleNumberArraySha256"]))
            self.assertNotIn("alleleNumberArray", hit)
            self.assertNotIn("mapping", resource["strata"])

    def test_all_sites_hail_artifact_rejects_profile_drift(self) -> None:
        artifact = self._synthetic_hail_artifact()
        artifact["profile"]["profileSha256"] = "0" * 64
        with self.assertRaisesRegex(
            self.probe_module["ProbeError"], "ALL_SITES_HAIL_PROFILE_MISMATCH"
        ):
            self.probe_module["validate_all_sites_hail_result"](
                artifact, self.profile, self.resources
            )

    def test_all_sites_hail_artifact_rejects_untrusted_structure(self) -> None:
        validate = self.probe_module["validate_all_sites_hail_result"]
        error = self.probe_module["ProbeError"]

        artifact = self._synthetic_hail_artifact()
        artifact["tool"]["backend"] = "SYNTHETIC"
        with self.assertRaisesRegex(error, "TOOL_PROVENANCE_MISMATCH"):
            validate(artifact, self.profile, self.resources)

        artifact = self._synthetic_hail_artifact()
        artifact.pop("execution")
        with self.assertRaisesRegex(error, "EXECUTION_PROVENANCE_MISMATCH"):
            validate(artifact, self.profile, self.resources)

        artifact = self._synthetic_hail_artifact()
        artifact["resources"][1] = artifact["resources"][0]
        with self.assertRaisesRegex(error, "RESOURCE_IDS_MISMATCH"):
            validate(artifact, self.profile, self.resources)

        artifact = self._synthetic_hail_artifact()
        artifact["resources"][0]["cases"][1] = artifact["resources"][0][
            "cases"
        ][0]
        with self.assertRaisesRegex(error, "CASE_IDS_MISMATCH"):
            validate(artifact, self.profile, self.resources)

        artifact = self._synthetic_hail_artifact()
        artifact["resources"][0]["strata"]["mapping"][1]["metadata"] = {
            "group": "adj"
        }
        with self.assertRaisesRegex(error, "STRATA_SELECTOR_NOT_UNIQUE"):
            validate(artifact, self.profile, self.resources)

        artifact = self._synthetic_hail_artifact()
        artifact["tool"]["evaluationResult"] = {"status": "NOT_EVALUATED"}
        with self.assertRaisesRegex(error, "CLINICAL_PAYLOAD_LEAKED"):
            validate(artifact, self.profile, self.resources)
        artifact = self._synthetic_hail_artifact()
        artifact["tool"]["log"] = r"F:\local\hail.log"
        with self.assertRaisesRegex(
            self.probe_module["ProbeError"], "ALL_SITES_HAIL_MACHINE_PATH_LEAKED"
        ):
            self.probe_module["validate_all_sites_hail_result"](
                artifact, self.profile, self.resources
            )

    def test_committed_smoke_is_verified_bounded_and_profile_bound(self) -> None:
        smoke = load(SMOKE_PATH)
        self.assertEqual(
            self.probe_module["PROBE_SCHEMA_VERSION"],
            smoke["probeSchemaVersion"],
        )
        self.assertEqual("VERIFIED", smoke["status"])
        self.assertEqual(
            {
                "profileId": self.profile["profileId"],
                "profileVersion": self.profile["profileVersion"],
                "profileSha256": self.probe_module["sha256_json"](self.profile),
            },
            smoke["profile"],
        )
        joint_profile = self.resources[self.probe_module["JOINT_RESOURCE_ID"]]
        self.assertEqual(
            joint_profile["identityFingerprintSha256"],
            smoke["joint"]["identityFingerprintSha256"],
        )
        self.assertEqual(
            {
                "PASS_HIT": "HIT",
                "EXACT_ALLELE_NO_RECORD": "NO_RECORD",
                "DISCORDANCE_QC_HIT": "HIT",
            },
            {
                case["caseId"]: case["axes"]["queryOutcome"]["status"]
                for case in smoke["joint"]["cases"]
            },
        )
        joint_cases = {
            case["caseId"]: case for case in smoke["joint"]["cases"]
        }
        self.assertEqual(
            joint_profile["probeState"]["cases"][0]["queryAllele"],
            joint_cases["PASS_HIT"]["variant"],
        )
        self.assertEqual("PASS", joint_cases["PASS_HIT"]["filterRaw"])
        self.assertFalse(
            joint_cases["PASS_HIT"]["frequencyDiscordance"]["isDiscordant"]
        )
        self.assertTrue(
            joint_cases["DISCORDANCE_QC_HIT"]["frequencyDiscordance"][
                "isDiscordant"
            ]
        )
        all_sites = {
            resource["resourceId"]: resource
            for resource in smoke["allSites"]["resources"]
        }
        self.assertEqual(
            set(self.probe_module["ALL_SITES_RESOURCE_IDS"]), set(all_sites)
        )
        frozen_hashes = {
            "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN": {
                "mapping": "13ace9662f17f584a2f67212494686f5334addd59765532fb16aa080129323a2",
                "alleleNumbers": "ac2658cd81377ea62c8c95ceafd8a667ee675486691a2b1eaf0ce25bd7572f41",
            },
            "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN": {
                "mapping": "f48116a4fbe5fa207812e5507275ef3565b9b28321b83011d08aa9be1a3872f4",
                "alleleNumbers": "a9e2ac1e28d7f4cc20fab2d9ba9300c72edc8540f5b3f9c8d9f86fcf5750bc73",
            },
        }
        for resource_id, resource in all_sites.items():
            profile_resource = self.resources[resource_id]
            query = resource["remotePointQuery"]
            self.assertIn(query["execution"]["partitionCountPlanned"], {1, 2})
            self.assertEqual(
                profile_resource["identityFingerprintSha256"],
                resource["identityFingerprintSha256"],
            )
            expected_selection = profile_resource["probeState"][
                "observedStrataSelection"
            ]
            for key in (
                "selector",
                "selectedIndex",
                "selectedMetadata",
                "selectedSampleCount",
                "mappingEntryCount",
            ):
                self.assertEqual(expected_selection[key], query["strata"][key])
            self.assertEqual(
                frozen_hashes[resource_id]["mapping"],
                query["strata"]["mappingSha256"],
            )
            self.assertNotIn("mapping", query["strata"])
            cases = {case["caseId"]: case for case in query["cases"]}
            self.assertEqual(
                "HIT",
                cases["ALL_SITES_AN_HIT"]["axes"]["queryOutcome"]["status"],
            )
            self.assertEqual(
                "NO_RECORD",
                cases["ALL_SITES_AN_NO_RECORD"]["axes"]["queryOutcome"]["status"],
            )
            self.assertNotIn("measurement", cases["ALL_SITES_AN_NO_RECORD"])
            profile_cases = {
                case["caseId"]: case
                for case in profile_resource["probeState"]["cases"]
            }
            for case_id, case in cases.items():
                self.assertEqual(
                    profile_cases[case_id]["queryLocus"], case["queryLocus"]
                )
                self.assertEqual(
                    self.probe_module["profile_case_axes"](profile_cases[case_id]),
                    case["axes"],
                )
            hit = cases["ALL_SITES_AN_HIT"]["measurement"]
            self.assertEqual(
                profile_cases["ALL_SITES_AN_HIT"]["measurement"][
                    "adjAlleleNumber"
                ],
                hit["adjAlleleNumber"],
            )
            self.assertEqual(
                expected_selection["mappingEntryCount"],
                hit["alleleNumberArrayLength"],
            )
            self.assertEqual(
                frozen_hashes[resource_id]["alleleNumbers"],
                hit["alleleNumberArraySha256"],
            )
            self.assertEqual(
                profile_cases["ALL_SITES_AN_HIT"]["measurement"].get(
                    "regionFlags"
                ),
                hit.get("regionFlags"),
            )
        expected_tool = self.probe_module["EXPECTED_HAIL_TOOL"]
        for key, value in expected_tool.items():
            self.assertEqual(value, smoke["allSites"]["tool"][key])
        self.assertTrue(
            smoke["allSites"]["tool"]["javaVersion"].startswith(
                'openjdk version "11.0.32"'
            )
        )
        self.assertEqual(
            {
                "retrieval": "REMOTE_INDEXED_FIXED_POINTS_ONLY",
                "fullVcfDownloaded": False,
                "fullHailTableDownloaded": False,
                "derivedResourceCacheCreated": False,
                "allSitesLocusQueryRun": True,
                "runtimeIntegrationRun": False,
                "patientDataUsed": False,
                "clinicalRuleEvaluationRun": False,
                "machinePathsIncluded": False,
            },
            smoke["boundary"],
        )
        self.probe_module["reject_clinical_payload"](smoke)
        serialized = self.probe_module["canonical_json"](smoke)
        self.assertIsNone(self.probe_module["LOCAL_PATH_PATTERN"].search(serialized))

    def test_v1_runtime_profile_lineage_is_unchanged_and_still_not_dataset_ready(self) -> None:
        v1 = load(V1_PROFILE_PATH)
        self.assertEqual("GNOMAD_POPULATION_INPUT_V1", v1["profileId"])
        self.assertEqual("1.0.0", v1["profileVersion"])
        self.assertEqual("INPUT_ONLY", v1["status"])
        self.assertFalse(v1["evaluation"]["evaluationEnabled"])
        self.assertEqual("EXOMES", v1["datasetSemantics"]["datasetType"])
        self.assertFalse(v1["datasetSemantics"]["jointOrCombinedAvailable"])
        self.assertFalse(v1["datasetSemantics"]["allSitesAlleleNumberAvailable"])
        self.assertEqual(
            "108243d789af1e71162a7407c61bdc79a1668df826033b36378e8bf232cc4bac",
            canonical_sha256(v1),
        )


if __name__ == "__main__":
    unittest.main()
