#!/usr/bin/env python3
"""Validate the minimal E3-P1B gnomAD PM2 successor contracts."""

from __future__ import annotations

import copy
import json
import re
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Mapping

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.e3_p1a import validate_acmg_rule_registry_v2 as v2_registry
from scripts.evidence import acmg_evidence_v2_contract as e1


ACMG_RESOURCE_ROOT = (
    REPO_ROOT / "varvis-server" / "src" / "main" / "resources" / "acmg"
)
PREDECESSOR_PROFILE_PATH = ACMG_RESOURCE_ROOT / "gnomad-population-input-profile-v2.json"
PROFILE_PATH = ACMG_RESOURCE_ROOT / "gnomad-population-input-profile-v2-pm2.json"
PREDECESSOR_REGISTRY_PATH = (
    ACMG_RESOURCE_ROOT
    / "rule-registry-v2"
    / "acmg-rule-registry-e3-p1a-2026-09-02.json"
)
REGISTRY_PATH = (
    ACMG_RESOURCE_ROOT
    / "rule-registry-v2"
    / "acmg-rule-registry-e3-p1b-2026-09-02.json"
)
SMOKE_PATH = REPO_ROOT / "docs" / "execution" / "E3_P1B_GNOMAD_PM2_SUPPORTING_SMOKE.json"

PREDECESSOR_PROFILE_CANONICAL_SHA256 = (
    "626320cc24bb480adb4d34c97646326c3ca358c5c68b8282eb7edf177731a0a3"
)
PREDECESSOR_REGISTRY_CANONICAL_SHA256 = (
    "833fc8b1b87f02dcf9d6cdbf9152419ce9d237fa82354d32ea687d0c242382bb"
)
EXPECTED_PROFILE_ID = "GNOMAD_POPULATION_INPUT_V2_PM2_SUPPORTING"
EXPECTED_PROFILE_VERSION = "2.1.0"
EXPECTED_REGISTRY_ID = "acmg-rule-registry-e3-p1b-2026-09-02"
PM2_CODE = "PM2"
PM2_CRITERION_ID = "ACMG_AMP_2015:PM2"
EXPECTED_RESOURCE_IDENTITIES = {
    "GNOMAD_JOINT_V4_1_GRCH38_SITES": {
        "resourceVersion": "gnomAD joint v4.1 GRCh38",
        "identityFingerprintSha256": (
            "c7ab8fc9194cc8474c29fbc493cb6ce05c4958264d00c9faa40a6d569bc2ef2f"
        ),
    },
    "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN": {
        "resourceVersion": "gnomAD exomes v4.1 all-sites allele number GRCh38",
        "identityFingerprintSha256": (
            "374ff5d05de95fd5ddecce732b89fd34ab91492dd4dfd820ff7d4157f591cb18"
        ),
    },
    "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN": {
        "resourceVersion": "gnomAD genomes v4.1 all-sites allele number GRCh38",
        "identityFingerprintSha256": (
            "0f957b54aa0f5c08bb12cd2e5210aac878a4b35e86ee7eaeb55b7f6d18554b57"
        ),
    },
}
EXOME_FLAGS = [
    "outside_broad_capture_region",
    "outside_ukb_capture_region",
    "outside_broad_calling_region",
    "outside_ukb_calling_region",
]


def _fail(message: str) -> None:
    raise e1.ContractError(message)


def _criterion(document: Mapping[str, Any], criterion_id: str) -> Mapping[str, Any]:
    matches = [
        criterion
        for criterion in document["evaluation"]["criteria"]
        if criterion.get("criterionId") == criterion_id
    ]
    if len(matches) != 1:
        _fail(f"profile must contain exactly one {criterion_id} criterion")
    return matches[0]


def _without_sha256(snapshot: Mapping[str, Any]) -> dict[str, Any]:
    projection = copy.deepcopy(dict(snapshot))
    projection.pop("sha256", None)
    return projection


def _validate_positive_decimal(raw_value: Any, field: str) -> None:
    if not isinstance(raw_value, str):
        _fail(f"{field} must preserve a decimal string")
    try:
        value = Decimal(raw_value)
    except InvalidOperation as exc:
        raise e1.ContractError(f"{field} is not a decimal") from exc
    if not value.is_finite() or value < 0 or value > 1:
        _fail(f"{field} must be a finite decimal fraction from zero through one")


def _validate_positive_integer(raw_value: Any, field: str) -> None:
    if not isinstance(raw_value, str) or not re.fullmatch(r"[1-9][0-9]*", raw_value):
        _fail(f"{field} must preserve a positive integer string")


def validate_policy_record(
    record: Mapping[str, Any], resources: Mapping[str, Mapping[str, Any]]
) -> None:
    """Validate one explicit gene-disease-MOI PM2 policy record."""

    required = {
        "policyId",
        "version",
        "gene",
        "disease",
        "moi",
        "resourceReleaseBindings",
        "maximumAlleleFrequency",
        "allowWellCoveredAbsence",
        "allSitesCallability",
        "authority",
    }
    if set(record) != required:
        _fail("PM2 policy record fields do not match the frozen contract")
    if not re.fullmatch(r"HGNC:[1-9][0-9]*", str(record["gene"].get("hgncId", ""))):
        _fail("PM2 policy gene.hgncId is invalid")
    if not re.fullmatch(r"MONDO:[0-9]{7}", str(record["disease"].get("mondoId", ""))):
        _fail("PM2 policy disease.mondoId is invalid")
    for field in ("policyId", "version", "moi"):
        if not isinstance(record[field], str) or not record[field].strip():
            _fail(f"PM2 policy {field} is required")

    bindings = record["resourceReleaseBindings"]
    if not isinstance(bindings, list) or len(bindings) != len(resources):
        _fail("PM2 policy must bind all three population resources")
    seen: set[str] = set()
    for binding in bindings:
        if set(binding) != {
            "resourceId",
            "resourceVersion",
            "identityFingerprintSha256",
        }:
            _fail("PM2 resource release binding fields are invalid")
        resource_id = binding["resourceId"]
        if resource_id in seen or resource_id not in resources:
            _fail("PM2 resource release binding set is invalid")
        seen.add(resource_id)
        expected = resources[resource_id]
        if binding["resourceVersion"] != expected["resourceVersion"]:
            _fail(f"PM2 resource version mismatch: {resource_id}")
        if binding["identityFingerprintSha256"] != expected["identityFingerprintSha256"]:
            _fail(f"PM2 resource fingerprint mismatch: {resource_id}")

    maximum = record["maximumAlleleFrequency"]
    if set(maximum) != {"metricId", "rawValue", "comparisonOperator"}:
        _fail("PM2 maximumAlleleFrequency fields are invalid")
    if maximum["metricId"] != "gnomad.joint.faf95max":
        _fail("PM2 frequency metric must be gnomad.joint.faf95max")
    if maximum["comparisonOperator"] not in {"LESS_THAN", "LESS_THAN_OR_EQUAL"}:
        _fail("PM2 frequency comparator is unsupported")
    _validate_positive_decimal(maximum["rawValue"], "maximumAlleleFrequency.rawValue")
    if not isinstance(record["allowWellCoveredAbsence"], bool):
        _fail("allowWellCoveredAbsence must be boolean")

    callability = record["allSitesCallability"]
    if set(callability) != {
        "satisfactionMode",
        "independentlySufficientDatasets",
        "exomes",
        "genomes",
    }:
        _fail("PM2 allSitesCallability fields are invalid")
    if callability["satisfactionMode"] != "ANY_INDEPENDENT":
        _fail("PM2 callability satisfactionMode must be ANY_INDEPENDENT")
    datasets = callability["independentlySufficientDatasets"]
    if (
        not isinstance(datasets, list)
        or not datasets
        or len(datasets) != len(set(datasets))
        or not set(datasets).issubset({"EXOMES", "GENOMES"})
    ):
        _fail("PM2 independently sufficient dataset tokens are invalid")

    for name, resource_id in (
        ("exomes", "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN"),
        ("genomes", "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN"),
    ):
        dataset = callability[name]
        expected_fields = {"resourceId", "minimumAdjAlleleNumber"}
        if name == "exomes":
            expected_fields.add("excludedWhenAnyFlagsTrue")
        if set(dataset) != expected_fields or dataset["resourceId"] != resource_id:
            _fail(f"PM2 {name} callability binding is invalid")
        minimum = dataset["minimumAdjAlleleNumber"]
        if set(minimum) != {"rawValue", "comparisonOperator"}:
            _fail(f"PM2 {name} minimum adj AN fields are invalid")
        if minimum["comparisonOperator"] not in {
            "GREATER_THAN",
            "GREATER_THAN_OR_EQUAL",
        }:
            _fail(f"PM2 {name} adj AN comparator is unsupported")
        _validate_positive_integer(minimum["rawValue"], f"{name}.minimumAdjAlleleNumber")
    if callability["exomes"]["excludedWhenAnyFlagsTrue"] != EXOME_FLAGS:
        _fail("PM2 exome exclusion flags do not match the frozen all-sites row fields")

    authority = record["authority"]
    if set(authority) != {"authoritySourceId", "authorityVersion", "snapshotSha256"}:
        _fail("PM2 authority fields are invalid")
    if not authority["authoritySourceId"] or not authority["authorityVersion"]:
        _fail("PM2 authority identity and version are required")
    if not re.fullmatch(r"[0-9a-f]{64}", str(authority["snapshotSha256"])):
        _fail("PM2 authority snapshot SHA-256 is invalid")


def validate_successor_registry(
    successor: Mapping[str, Any], predecessor: Mapping[str, Any]
) -> None:
    """Validate v2 schema plus the immediate E3-P1A -> E3-P1B PM2-only delta."""

    v2_registry._validate_schema(successor)
    if e1.canonical_sha256(predecessor) != PREDECESSOR_REGISTRY_CANONICAL_SHA256:
        _fail("immediate predecessor registry canonical SHA-256 mismatch")
    if successor["registryId"] != EXPECTED_REGISTRY_ID:
        _fail("E3-P1B registry ID mismatch")
    if successor["registryId"] == predecessor["registryId"]:
        _fail("successor registry ID must differ from its immediate predecessor")
    if successor["lineage"] != predecessor["lineage"]:
        _fail("successor frozen E1 schema lineage changed")

    expected = copy.deepcopy(dict(predecessor))
    expected["registryId"] = EXPECTED_REGISTRY_ID
    pm2 = next(item for item in expected["criteria"] if item["code"] == PM2_CODE)
    pm2["implementationStatus"] = "V2_IMPLEMENTED"
    if successor != expected:
        for actual, wanted in zip(successor["criteria"], expected["criteria"], strict=True):
            if actual != wanted:
                _fail(f"unauthorized successor criterion change: {actual.get('code')}")
        _fail("successor registry changed beyond ID and PM2 implementationStatus")


def validate_successor_profile(
    profile: Mapping[str, Any],
    predecessor: Mapping[str, Any],
    registry: Mapping[str, Any],
) -> None:
    if e1.canonical_sha256(predecessor) != PREDECESSOR_PROFILE_CANONICAL_SHA256:
        _fail("predecessor profile canonical SHA-256 mismatch")
    if profile.get("profileSha256") is not None:
        _fail("successor profile must not self-hash")
    if profile["profileSchemaVersion"] != "gnomad-population-input-profile-v2":
        _fail("successor profile must reuse the v2 profile contract")
    if profile["profileId"] != EXPECTED_PROFILE_ID or profile["profileVersion"] != EXPECTED_PROFILE_VERSION:
        _fail("successor profile identity mismatch")
    if profile["lineage"] != {
        "predecessorProfileId": predecessor["profileId"],
        "predecessorProfileVersion": predecessor["profileVersion"],
        "predecessorProfileSha256": PREDECESSOR_PROFILE_CANONICAL_SHA256,
    }:
        _fail("successor profile lineage mismatch")
    if profile["activation"] != {
        "runtimeBound": True,
        "evaluationEnabled": True,
        "patientFlowEnabled": False,
        "classificationEnabled": False,
    }:
        _fail("successor activation boundary changed")

    binding = profile["registryBinding"]
    if binding != {
        "registryId": EXPECTED_REGISTRY_ID,
        "registrySchemaVersion": "acmg-rule-registry-v2",
        "registrySha256": e1.sha256_file(REGISTRY_PATH),
        "registryClasspathResource": (
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-p1b-2026-09-02.json"
        ),
        "criterionIds": [
            "ACMG_AMP_2015:BA1",
            "ACMG_AMP_2015:BS1",
            PM2_CRITERION_ID,
        ],
    }:
        _fail("successor profile registry binding mismatch")
    if registry["registryId"] != binding["registryId"]:
        _fail("bound successor registry ID mismatch")

    for field in (
        "status",
        "canonicalizationProfile",
        "authorityCutoff",
        "asOf",
        "scope",
        "outputPolicy",
        "resourceIdentitySemantics",
        "qualityGate",
        "numericSemantics",
        "fieldContract",
    ):
        if profile[field] != predecessor[field]:
            _fail(f"successor profile changed frozen predecessor field: {field}")
    for criterion_id in ("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1"):
        if _criterion(profile, criterion_id) != _criterion(predecessor, criterion_id):
            _fail(f"successor profile changed {criterion_id} semantics")

    predecessor_authorities = list(predecessor["authorityBindings"])
    if profile["authorityBindings"] != predecessor_authorities + [{
        "authoritySourceId": "authority-clingen-svi-pm2-v1.0",
        "snapshotSha256": (
            "30745e928c7146a479afdfb7f22230192a8d1d66f9239a463c53667dd9598c59"
        ),
    }]:
        _fail("successor PM2 authority binding is invalid")

    resources = {resource["resourceId"]: resource for resource in profile["resources"]}
    if list(resources) != list(EXPECTED_RESOURCE_IDENTITIES):
        _fail("successor profile must bind exactly joint, exome AN and genome AN")
    if resources["GNOMAD_JOINT_V4_1_GRCH38_SITES"] != predecessor["resources"][0]:
        _fail("successor profile changed the E3-P1A joint resource")
    for resource_id, expected in EXPECTED_RESOURCE_IDENTITIES.items():
        resource = resources[resource_id]
        for field, value in expected.items():
            if resource[field] != value:
                _fail(f"resource identity mismatch: {resource_id}.{field}")
        if resource["provider"] != "GNOMAD" or resource["assembly"] != "GRCh38":
            _fail(f"resource provider or assembly mismatch: {resource_id}")
        if e1.canonical_sha256(resource["identity"]) != resource["identityFingerprintSha256"]:
            _fail(f"resource identity fingerprint is not canonical: {resource_id}")
        if "probeState" in resource:
            _fail("runtime profile must not contain historical probe state")
    serialized = json.dumps(profile, ensure_ascii=False)
    if re.search(r"(?i)(?:^|[\"\s])[a-z]:[\\/]", serialized):
        _fail("successor profile contains a local absolute path")

    dataset = profile["datasetSemantics"]
    if (
        dataset["datasetType"] != "JOINT_WITH_SEPARATE_ALL_SITES_AN"
        or not dataset["allSitesAlleleNumberAvailable"]
        or not dataset["noRecordInputReady"]
        or dataset["allSitesAnMergeSemantics"]
        != "SEPARATE_EXOME_GENOME_NEVER_SUMMED_ANY_INDEPENDENT_POLICY_DEFINED"
    ):
        _fail("successor all-sites dataset semantics are invalid")

    pm2 = _criterion(profile, PM2_CRITERION_ID)
    if (
        pm2.get("currentDesignation") != "PM2_SUPPORTING"
        or pm2.get("baseStrength") != "SUPPORTING"
        or pm2.get("evaluationEnabled") is not True
    ):
        _fail("PM2 Supporting designation or strength is invalid")
    rule = pm2["populationRule"]
    absence = rule["jointAbsenceRequirement"]
    if absence != {
        "exactAlleleRequired": True,
        "requiredResourceExecutionStatus": "SUCCEEDED",
        "requiredResourceAvailabilityStatus": "AVAILABLE",
        "requiredQueryOutcomeStatus": "NO_RECORD",
        "requiredResourceApplicabilityStatus": "APPLICABLE",
        "noRecordCreatesFrequencyMeasurement": False,
    }:
        _fail("PM2 joint exact-allele absence requirement is invalid")
    if rule["policySelectionKeys"] != ["gene.hgncId", "disease.mondoId", "moi"]:
        _fail("PM2 context policy selection keys are invalid")
    expected_record_contract = {
        "requiredFields": [
            "policyId",
            "version",
            "gene.hgncId",
            "disease.mondoId",
            "moi",
            "resourceReleaseBindings",
            "maximumAlleleFrequency",
            "allowWellCoveredAbsence",
            "allSitesCallability",
            "authority",
        ],
        "resourceReleaseBindingFields": [
            "resourceId",
            "resourceVersion",
            "identityFingerprintSha256",
        ],
        "maximumAlleleFrequencyFields": [
            "metricId",
            "rawValue",
            "comparisonOperator",
        ],
        "allowedFrequencyComparisonOperators": ["LESS_THAN", "LESS_THAN_OR_EQUAL"],
        "callability": {
            "satisfactionMode": "ANY_INDEPENDENT",
            "datasetTokens": ["EXOMES", "GENOMES"],
            "minimumAdjAlleleNumberFields": ["rawValue", "comparisonOperator"],
            "allowedAlleleNumberComparisonOperators": [
                "GREATER_THAN",
                "GREATER_THAN_OR_EQUAL",
            ],
            "exomeExcludedWhenAnyFlagsTrue": EXOME_FLAGS,
            "exomeAndGenomeAlleleNumbersCombined": False,
        },
        "authorityFields": [
            "authoritySourceId",
            "authorityVersion",
            "snapshotSha256",
        ],
    }
    if rule["policyRecordContract"] != expected_record_contract:
        _fail("PM2 policy record contract changed")
    snapshot = rule["policySnapshot"]
    if snapshot["records"] != []:
        _fail("production PM2 policy snapshot must remain empty in E3-P1B")
    if snapshot["sha256"] != e1.canonical_sha256(_without_sha256(snapshot)):
        _fail("PM2 policy snapshot canonical SHA-256 mismatch")


def validate_committed_successors() -> dict[str, Any]:
    predecessor_registry = e1.load_json(PREDECESSOR_REGISTRY_PATH)
    registry = e1.load_json(REGISTRY_PATH)
    validate_successor_registry(registry, predecessor_registry)
    predecessor_profile = e1.load_json(PREDECESSOR_PROFILE_PATH)
    profile = e1.load_json(PROFILE_PATH)
    validate_successor_profile(profile, predecessor_profile, registry)
    return {
        "status": "PASS",
        "profileId": profile["profileId"],
        "profileCanonicalSha256": e1.canonical_sha256(profile),
        "registryId": registry["registryId"],
        "registryCanonicalSha256": e1.canonical_sha256(registry),
        "implementedCriteria": ["BA1", "BS1", "PM2"],
        "productionPm2PolicyRecordCount": 0,
    }


def main() -> int:
    try:
        result = validate_committed_successors()
    except (e1.ContractError, KeyError, OSError, TypeError, ValueError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
