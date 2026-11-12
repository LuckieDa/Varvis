#!/usr/bin/env python3
"""Validate the minimal E3-L1A HNF1A fixed-point PVS1 successors."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Mapping


REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.e3_p1a import validate_acmg_rule_registry_v2 as v2_registry  # noqa: E402
from scripts.evidence import acmg_evidence_v2_contract as e1  # noqa: E402


ACMG_ROOT = REPO_ROOT / "varvis-server" / "src" / "main" / "resources" / "acmg"
PREDECESSOR_RULE_REGISTRY_PATH = (
    ACMG_ROOT / "rule-registry-v2" / "acmg-rule-registry-e3-k1b-2026-09-04.json"
)
RULE_REGISTRY_PATH = (
    ACMG_ROOT / "rule-registry-v2" / "acmg-rule-registry-e3-l1a-2026-09-05.json"
)
PREDECESSOR_CSPEC_REGISTRY_PATH = e1.CSPEC_REGISTRY_PATH
CSPEC_REGISTRY_PATH = (
    ACMG_ROOT
    / "cspec-overlay-registry-v1"
    / "cspec-overlay-registry-e3-l1a-2026-09-05.json"
)
PREREQUISITE_PROFILE_PATH = (
    ACMG_ROOT / "pvs1-early-exon-stop-gained-prerequisite-profile-v1.json"
)
PROFILE_PATH = ACMG_ROOT / "pvs1-hnf1a-fixed-point-evaluation-profile-v1.json"

EXPECTED_RULE_REGISTRY_ID = "acmg-rule-registry-e3-l1a-2026-09-05"
EXPECTED_CSPEC_REGISTRY_ID = "cspec-overlay-registry-e3-l1a-2026-09-05"
EXPECTED_PROFILE_ID = "HNF1A_FIXED_POINT_PVS1_VERY_STRONG"
PVS1 = "ACMG_AMP_2015:PVS1"

RULE_SCHEMA_RAW_SHA256 = (
    "b1904ee863e0c2bcb6d3e4324214e8873d79cf71647a3bd9badbee5e1784ce07"
)
PREDECESSOR_RULE_RAW_SHA256 = (
    "4f2e96746541bf6269a9d06a919d3eb0759ee3bf079f06f4868a23561514e9ed"
)
PREDECESSOR_RULE_CANONICAL_SHA256 = (
    "a9a5485463ace52e6a21ded2e9a129a016ddec2aa3ebb5c9086c7987c5e46c1f"
)
RULE_RAW_SHA256 = (
    "3cd5b36002a00c39b3af54ac9c2c409337f8e05b76482c274216c2e3c5f9db42"
)
RULE_CANONICAL_SHA256 = (
    "2aacc86af0b974a4f52120a32350bfd5912cf641f8c41014519ac16020c1d6f3"
)
PREDECESSOR_CSPEC_RAW_SHA256 = (
    "aee206e3b9b22ebad02f54fe010ac10d9e8a9c1ffd7d41f5e37e859fe8b97295"
)
PREDECESSOR_CSPEC_CANONICAL_SHA256 = (
    "0dabff535bfa0960260e0ff02cdbd633b4b41c12ca8354f6cfb7a7516cc5d43e"
)
CSPEC_RAW_SHA256 = (
    "26c1e5a5cbd1dca3b4b5af53c6b413ee869aa665246bc918d2ec022a12f34273"
)
CSPEC_CANONICAL_SHA256 = (
    "647b4031c1ba2573887939810127cf13044733ead2223633320e0f2308f7abf4"
)
PREREQUISITE_PROFILE_RAW_SHA256 = (
    "501e23987571332ddbb94ad5f51712e63cf3ba07eab0891b6f608bf6f5c22261"
)
PREREQUISITE_PROFILE_CANONICAL_SHA256 = (
    "66b9c089cbcf6f4eaedb6ba6de387fd00a11cfb0e52449ada9f0386bb8192822"
)
PROFILE_RAW_SHA256 = (
    "37b20c0feaeee6ee980ca41dc0d469e66e849187246658cab7145c11aa6108e8"
)
PROFILE_CANONICAL_SHA256 = (
    "bb8124f9eb6d77ce158b637b2f37aa966f246f504c87b4fee9c0c8240ef05328"
)
AUTHORITY_RAW_SHA256 = (
    "9c4d12df8eba350318624e25d50321f845c023f1d136bff934ecfbb89d45e627"
)

RUNTIME_INSTANCE_FIELDS = {
    "evidenceContext",
    "atomicEvidence",
    "cspecSelection",
    "criterionEvaluation",
}
WINDOWS_ABSOLUTE_PATH = re.compile(r'(?i)(?:^|["\s])[a-z]:[\\/]')


def _fail(message: str) -> None:
    raise e1.ContractError(message)


def _expected_profile() -> dict[str, Any]:
    return {
        "profileSchemaVersion": "pvs1-hnf1a-fixed-point-evaluation-profile-v1",
        "profileId": EXPECTED_PROFILE_ID,
        "profileVersion": "1.0.0",
        "status": "RUNTIME_EVALUATION_SLICE",
        "canonicalizationProfile": "varvis-json-c14n-v1",
        "authorityCutoff": "2026-09-05",
        "asOf": "2026-09-05T00:00:00Z",
        "scope": "GERMLINE_MENDELIAN_SMALL_SEQUENCE_VARIANT",
        "activation": {
            "runtimeBound": True,
            "evaluationEnabled": True,
            "patientFlowEnabled": False,
            "classificationEnabled": False,
        },
        "prerequisiteBinding": {
            "profileId": "HNF1A_EARLY_EXON_STOP_GAINED_PVS1_PREREQUISITE",
            "profileVersion": "1.0.0",
            "profileCanonicalSha256": PREREQUISITE_PROFILE_CANONICAL_SHA256,
            "profileClasspathResource": (
                "/acmg/pvs1-early-exon-stop-gained-prerequisite-profile-v1.json"
            ),
            "requiredStatus": "INPUT_READY",
            "requiredNmdPrediction": "PREDICTED_NMD",
        },
        "authorityCatalogBinding": {
            "catalogId": "authority-catalog-acmg-evidence-v2-2026-08-31",
            "catalogSha256": AUTHORITY_RAW_SHA256,
            "catalogClasspathBoundary": (
                "docs/contracts/acmg-evidence-v2/AUTHORITY_SOURCES.json"
            ),
        },
        "ruleRegistryBinding": {
            "registryId": EXPECTED_RULE_REGISTRY_ID,
            "registrySchemaVersion": "acmg-rule-registry-v2",
            "registrySha256": RULE_RAW_SHA256,
            "registryCanonicalSha256": RULE_CANONICAL_SHA256,
            "registryClasspathResource": (
                "/acmg/rule-registry-v2/"
                "acmg-rule-registry-e3-l1a-2026-09-05.json"
            ),
        },
        "cspecRegistryBinding": {
            "registryId": EXPECTED_CSPEC_REGISTRY_ID,
            "registrySchemaVersion": "cspec-overlay-registry-v1",
            "registrySha256": CSPEC_RAW_SHA256,
            "registryCanonicalSha256": CSPEC_CANONICAL_SHA256,
            "registryClasspathResource": (
                "/acmg/cspec-overlay-registry-v1/"
                "cspec-overlay-registry-e3-l1a-2026-09-05.json"
            ),
            "selectionPolicyId": "CSPEC_EXACT_CONTEXT_SELECTION_V1",
            "requiredOutcome": "ZERO_MATCH",
            "requiredCandidateOverlayIds": [],
            "zeroMatchMeaning": (
                "NO_EXECUTABLE_EXACT_MATCH_IN_BOUND_REGISTRY_"
                "NOT_AN_ASSERTION_THAT_GN017_DOES_NOT_EXIST"
            ),
        },
        "criterion": {
            "criterionId": PVS1,
            "currentGeneralDesignation": "PVS1",
            "baseStrength": "VERY_STRONG",
            "appliedStrength": "VERY_STRONG",
            "authoritySourceIds": [
                "authority-clingen-svi-pvs1-2018",
                "authority-clingen-svi-splicing-2023",
            ],
        },
        "evaluationPolicy": {
            "matchingResult": {
                "criterionApplicability": "APPLICABLE",
                "evaluationResult": "MET",
                "conflict": "NONE",
                "review": "NOT_REQUIRED",
            },
            "nonReadyResult": {
                "criterionApplicability": "UNKNOWN",
                "evaluationResult": "NOT_EVALUATED",
                "appliedStrength": "NONE",
            },
            "notMetOutputAllowed": False,
            "evidenceReferencePolicy": "SOLE_VEP_ATOMIC_EVIDENCE",
            "legacyPvs1CandidateAllowed": False,
            "lofteeAllowed": False,
            "combinationPerformed": False,
            "classificationPerformed": False,
        },
        "clinicalBoundary": {
            "exactFixedPointOnly": True,
            "generalPvs1Implemented": False,
            "patientFlowEnabled": False,
            "classificationEnabled": False,
            "clinicalValidationClaimed": False,
        },
    }


def validate_rule_successor(
    successor: Mapping[str, Any], predecessor: Mapping[str, Any]
) -> None:
    """Allow only the registry ID and PVS1 implementation-status transition."""

    if e1.sha256_file(v2_registry.SCHEMA_PATH) != RULE_SCHEMA_RAW_SHA256:
        _fail("acmg-rule-registry-v2 schema changed")
    if e1.sha256_file(PREDECESSOR_RULE_REGISTRY_PATH) != PREDECESSOR_RULE_RAW_SHA256:
        _fail("E3-K1B predecessor rule registry raw SHA-256 mismatch")
    if e1.canonical_sha256(predecessor) != PREDECESSOR_RULE_CANONICAL_SHA256:
        _fail("E3-K1B predecessor rule registry canonical SHA-256 mismatch")
    if predecessor.get("registryId") != "acmg-rule-registry-e3-k1b-2026-09-04":
        _fail("E3-L1A rule registry does not use E3-K1B as its direct predecessor")

    v2_registry._validate_schema(successor)
    expected = copy.deepcopy(dict(predecessor))
    expected["registryId"] = EXPECTED_RULE_REGISTRY_ID
    for criterion in expected["criteria"]:
        if criterion["code"] == "PVS1":
            criterion["implementationStatus"] = "V2_IMPLEMENTED"

    if successor != expected:
        if successor.get("registryId") != EXPECTED_RULE_REGISTRY_ID:
            _fail("E3-L1A rule registry ID mismatch")
        actual_criteria = successor.get("criteria", [])
        for actual, wanted in zip(actual_criteria, expected["criteria"], strict=False):
            if actual != wanted:
                code = actual.get("code", "UNKNOWN")
                if code == "PVS1":
                    _fail("PVS1 changed beyond implementationStatus")
                _fail(f"unauthorized successor criterion change: {code}")
        _fail("rule successor changed beyond ID and PVS1 implementationStatus")


def validate_cspec_successor(
    successor: Mapping[str, Any],
    predecessor: Mapping[str, Any],
    rule_registry: Mapping[str, Any],
    catalog: Mapping[str, Any],
    schemas: e1.ContractSchemas,
) -> None:
    """Allow only CSpec registry identity and the new base-rule binding."""

    if e1.sha256_file(PREDECESSOR_CSPEC_REGISTRY_PATH) != PREDECESSOR_CSPEC_RAW_SHA256:
        _fail("frozen E1 CSpec predecessor raw SHA-256 mismatch")
    if e1.canonical_sha256(predecessor) != PREDECESSOR_CSPEC_CANONICAL_SHA256:
        _fail("frozen E1 CSpec predecessor canonical SHA-256 mismatch")

    expected = copy.deepcopy(dict(predecessor))
    expected["registryId"] = EXPECTED_CSPEC_REGISTRY_ID
    expected["baseRuleRegistryId"] = EXPECTED_RULE_REGISTRY_ID
    expected["baseRuleRegistrySha256"] = RULE_RAW_SHA256
    if successor != expected:
        for field in (
            "authorityCatalogId",
            "authorityCatalogSha256",
            "authorityCutoff",
            "asOf",
            "selectionPolicy",
            "overlays",
        ):
            if successor.get(field) != predecessor.get(field):
                _fail(f"CSpec successor changed frozen predecessor field: {field}")
        _fail("CSpec successor changed beyond ID and base-rule binding")

    e1.validate_cspec_registry(
        successor,
        catalog,
        rule_registry,
        schemas,
        authority_path=e1.AUTHORITY_PATH,
        rule_registry_path=RULE_REGISTRY_PATH,
    )


def validate_profile(profile: Mapping[str, Any]) -> None:
    """Validate the exact, non-self-hashed executable fixed-point profile."""

    if profile.get("profileSha256") is not None:
        _fail("PVS1 evaluation profile must not self-hash")
    if dict(profile) != _expected_profile():
        _fail("PVS1 evaluation profile differs from the fixed E3-L1A contract")
    serialized = json.dumps(profile, ensure_ascii=False)
    if (
        WINDOWS_ABSOLUTE_PATH.search(serialized)
        or "/mnt/" in serialized
        or "/home/" in serialized
    ):
        _fail("PVS1 evaluation profile contains a machine-local absolute path")


def _text_measurements(atomic: Mapping[str, Any]) -> dict[str, list[str]]:
    values: dict[str, list[str]] = {}
    for measurement in atomic.get("measurements", []):
        if not isinstance(measurement, Mapping):
            continue
        metric = measurement.get("metricId")
        raw = measurement.get("rawValue")
        if isinstance(metric, str) and isinstance(raw, str):
            values.setdefault(metric, []).append(raw)
    return values


def _assert_runtime_slice(
    context: Mapping[str, Any],
    atomic: Mapping[str, Any],
    selection: Mapping[str, Any],
    evaluation: Mapping[str, Any],
) -> None:
    allele = context.get("variant", {}).get("evidenceQueryAllele", {})
    transcript = context.get("transcript", {})
    if (
        allele
        != {
            "assembly": "GRCh38",
            "contig": "12",
            "position": 120989032,
            "reference": "C",
            "alternate": "T",
        }
        or context.get("variant", {}).get("variantType") != "SNV"
        or context.get("variant", {}).get("assemblyLineage", {}).get("mappingStatus")
        != "NATIVE"
        or context.get("gene", {}).get("hgncId") != "HGNC:11621"
        or context.get("gene", {}).get("symbol") != "HNF1A"
        or context.get("disease", {}).get("mondoId") != "MONDO:0015967"
        or context.get("moi") != "AUTOSOMAL_DOMINANT"
        or transcript.get("accession") != "ENST00000257555"
        or transcript.get("version") != 11
    ):
        _fail("runtime evidence context is outside the single HNF1A fixed point")

    measurements = _text_measurements(atomic)
    consequence_values = measurements.get("consequenceTerm", [])
    if (
        atomic.get("contextId") != context.get("contextId")
        or atomic.get("evidenceType") != "VARIANT_CONSEQUENCE"
        or atomic.get("source", {}).get("sourceId") != "VEP_116_GRCH38"
        or atomic.get("source", {}).get("resourceSha256")
        != "43c0422c1d2f2bc2221c60a96433f1602de7205113f107d027deea0b7eabdbed"
        or measurements.get("vep.selectedTranscriptId") != ["ENST00000257555.11"]
        or len(consequence_values) != 1
        or "stop_gained" not in consequence_values[0].split("&")
        or measurements.get("vep.exon") != ["2/10"]
        or any("loftee" in metric.lower() for metric in measurements)
    ):
        _fail("runtime atomic evidence is not the sole fixed-point VEP evidence")

    expected_selection_id = e1.content_id(
        "cspec-selection-sha256-",
        e1.cspec_selection_identity_projection(selection),
    )
    if (
        selection.get("contextId") != context.get("contextId")
        or selection.get("overlayRegistryId") != EXPECTED_CSPEC_REGISTRY_ID
        or selection.get("overlayRegistrySha256") != CSPEC_RAW_SHA256
        or selection.get("policyId") != "CSPEC_EXACT_CONTEXT_SELECTION_V1"
        or selection.get("outcome") != "ZERO_MATCH"
        or selection.get("candidateOverlayIds") != []
        or selection.get("selectionId") != expected_selection_id
    ):
        _fail("runtime CSpec selection is not the canonical bound ZERO_MATCH")

    trace = evaluation.get("deterministicTrace", {})
    expected_trace = e1.canonical_sha256(
        e1.deterministic_trace_projection(trace, include_hash=False)
    )
    expected_evaluation_id = e1.content_id(
        "evaluation-sha256-", e1.evaluation_identity_projection(evaluation)
    )
    evidence_id = atomic.get("evidenceId")
    if (
        evaluation.get("contextId") != context.get("contextId")
        or evaluation.get("criterionId") != PVS1
        or evaluation.get("ruleBinding", {}).get("ruleRegistryId")
        != EXPECTED_RULE_REGISTRY_ID
        or evaluation.get("ruleBinding", {}).get("ruleRegistrySha256")
        != RULE_RAW_SHA256
        or evaluation.get("ruleBinding", {}).get("cspecSelectionId")
        != selection.get("selectionId")
        or evaluation.get("criterionApplicability", {}).get("status") != "APPLICABLE"
        or evaluation.get("evaluationResult", {}).get("status") != "MET"
        or evaluation.get("baseStrength") != "VERY_STRONG"
        or evaluation.get("appliedStrength") != "VERY_STRONG"
        or evaluation.get("evidenceIds") != [evidence_id]
        or trace.get("inputEvidenceIds") != [evidence_id]
        or trace.get("traceSha256") != expected_trace
        or evaluation.get("evaluationId") != expected_evaluation_id
        or evaluation.get("conflict", {}).get("status") != "NONE"
        or evaluation.get("review", {}).get("status") != "NOT_REQUIRED"
    ):
        _fail("runtime PVS1 evaluation is not the canonical fixed-point MET result")
    if "NOT_MET" in json.dumps(evaluation, ensure_ascii=False):
        _fail("E3-L1A must never emit NOT_MET")


def _parse_timestamp(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        _fail("runtime timestamp has no timezone")
    return parsed.astimezone(timezone.utc)


def _bundle_created_at(catalog: Mapping[str, Any], atomic: Mapping[str, Any]) -> str:
    timestamps = [catalog["asOf"], atomic["source"]["retrievedAt"]]
    timestamps.extend(
        locator["retrievedAt"]
        for locator in atomic["source"]["sourceLocators"]
        if "retrievedAt" in locator
    )
    timestamps.extend(
        record["locator"]["retrievedAt"]
        for record in atomic["source"]["sourceRecords"]
        if "retrievedAt" in record.get("locator", {})
    )
    latest = max(_parse_timestamp(value) for value in timestamps)
    return (latest + timedelta(microseconds=1)).isoformat().replace("+00:00", "Z")


def validate_runtime_instances(instances: Mapping[str, Any]) -> dict[str, Any]:
    """Compose and validate one in-memory E1 bundle from Java runtime instances."""

    if set(instances) != RUNTIME_INSTANCE_FIELDS:
        _fail("runtime instances must contain exactly the four E3-L1A objects")
    context = instances["evidenceContext"]
    atomic = instances["atomicEvidence"]
    selection = instances["cspecSelection"]
    evaluation = instances["criterionEvaluation"]
    if not all(
        isinstance(value, Mapping)
        for value in (context, atomic, selection, evaluation)
    ):
        _fail("runtime instances must be JSON objects")

    schemas = e1.ContractSchemas()
    schemas.validate(context, "urn:varvis:schema:evidence-context-v2")
    schemas.validate(atomic, "urn:varvis:schema:atomic-evidence-v2")
    schemas.validate(selection, "urn:varvis:schema:cspec-selection-v1")
    schemas.validate(evaluation, "urn:varvis:schema:criterion-evaluation-v2")
    _assert_runtime_slice(context, atomic, selection, evaluation)

    catalog = e1.load_json(e1.AUTHORITY_PATH)
    rule_registry = e1.load_json(RULE_REGISTRY_PATH)
    cspec_registry = e1.load_json(CSPEC_REGISTRY_PATH)
    e1.validate_authority_catalog(catalog, schemas)

    combination = {
        "schemaVersion": "combination-result-v2",
        "combinationId": "combination-sha256-" + "0" * 64,
        "contextId": context["contextId"],
        "executionStatus": "NOT_RUN",
        "result": "NO_RESULT",
        "reasonCode": "E1_COMBINATION_NOT_IMPLEMENTED",
        "provisional": True,
        "clinicalSignoffStatus": "NOT_SIGNED",
        "criterionEvaluationIds": [evaluation["evaluationId"]],
        "conflict": {"status": "NONE", "conflictIds": []},
        "review": {
            "status": "NOT_REQUIRED",
            "decision": "NONE",
            "reviewRecordIds": [],
        },
    }
    draft = {
        "schemaVersion": "evidence-bundle-v2",
        "bundleId": "bundle-sha256-" + "0" * 64,
        "sourceOfTruth": "ACMG_EVIDENCE_V2",
        "dataClass": "SYNTHETIC",
        "createdAt": _bundle_created_at(catalog, atomic),
        "producer": {
            "producerId": "e3-l1a-hnf1a-fixed-point-bundle-validator",
            "producerVersion": "1.0.0",
            "canonicalizationProfile": "varvis-json-c14n-v1",
        },
        "authorityBinding": {
            "authorityCatalogId": catalog["catalogId"],
            "authorityCatalogSha256": AUTHORITY_RAW_SHA256,
            "ruleRegistryId": EXPECTED_RULE_REGISTRY_ID,
            "ruleRegistrySha256": RULE_RAW_SHA256,
            "cspecOverlayRegistryId": EXPECTED_CSPEC_REGISTRY_ID,
            "cspecOverlayRegistrySha256": CSPEC_RAW_SHA256,
            "authorityCutoff": catalog["authorityCutoff"],
            "asOf": catalog["asOf"],
        },
        "context": copy.deepcopy(dict(context)),
        "cspecSelection": copy.deepcopy(dict(selection)),
        "atomicEvidence": [copy.deepcopy(dict(atomic))],
        "criterionEvaluations": [copy.deepcopy(dict(evaluation))],
        "reviewRecords": [],
        "conflicts": [],
        "legacyAdapterRecords": [],
        "combination": combination,
        "candidateOrderingChanged": False,
        "clinicalConclusionClaimed": False,
    }
    bundle = e1.rehash_bundle(draft)
    if (
        bundle["context"] != context
        or bundle["atomicEvidence"][0] != atomic
        or bundle["cspecSelection"] != selection
        or bundle["criterionEvaluations"][0] != evaluation
    ):
        _fail("Java runtime instance stable identity is not independently recomputable")

    e1.validate_bundle(
        bundle,
        catalog,
        rule_registry,
        cspec_registry,
        schemas,
        authority_path=e1.AUTHORITY_PATH,
        rule_registry_path=RULE_REGISTRY_PATH,
        cspec_registry_path=CSPEC_REGISTRY_PATH,
    )
    return bundle


def validate_committed_contracts() -> dict[str, Any]:
    predecessor_rule = e1.load_json(PREDECESSOR_RULE_REGISTRY_PATH)
    rule_registry = e1.load_json(RULE_REGISTRY_PATH)
    predecessor_cspec = e1.load_json(PREDECESSOR_CSPEC_REGISTRY_PATH)
    cspec_registry = e1.load_json(CSPEC_REGISTRY_PATH)
    prerequisite = e1.load_json(PREREQUISITE_PROFILE_PATH)
    profile = e1.load_json(PROFILE_PATH)
    catalog = e1.load_json(e1.AUTHORITY_PATH)
    schemas = e1.ContractSchemas()

    validate_rule_successor(rule_registry, predecessor_rule)
    validate_cspec_successor(
        cspec_registry,
        predecessor_cspec,
        rule_registry,
        catalog,
        schemas,
    )
    validate_profile(profile)

    pinned_files = (
        (RULE_REGISTRY_PATH, RULE_RAW_SHA256, RULE_CANONICAL_SHA256),
        (CSPEC_REGISTRY_PATH, CSPEC_RAW_SHA256, CSPEC_CANONICAL_SHA256),
        (
            PREREQUISITE_PROFILE_PATH,
            PREREQUISITE_PROFILE_RAW_SHA256,
            PREREQUISITE_PROFILE_CANONICAL_SHA256,
        ),
        (PROFILE_PATH, PROFILE_RAW_SHA256, PROFILE_CANONICAL_SHA256),
    )
    for path, raw_hash, canonical_hash in pinned_files:
        if e1.sha256_file(path) != raw_hash:
            _fail(f"committed artifact raw SHA-256 mismatch: {path.name}")
        if e1.canonical_sha256(e1.load_json(path)) != canonical_hash:
            _fail(f"committed artifact canonical SHA-256 mismatch: {path.name}")
    if e1.sha256_file(e1.AUTHORITY_PATH) != AUTHORITY_RAW_SHA256:
        _fail("frozen authority catalog raw SHA-256 mismatch")

    return {
        "status": "PASS",
        "profileId": profile["profileId"],
        "ruleRegistryId": rule_registry["registryId"],
        "cspecRegistryId": cspec_registry["registryId"],
        "advancedCriteria": ["PVS1"],
        "cspecChangedFields": [
            "registryId",
            "baseRuleRegistryId",
            "baseRuleRegistrySha256",
        ],
        "evaluationEnabled": True,
        "patientFlowEnabled": False,
        "classificationEnabled": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--runtime-instances",
        type=Path,
        help="Java-generated context/atomic/selection/evaluation JSON",
    )
    args = parser.parse_args(argv)
    try:
        result = validate_committed_contracts()
        if args.runtime_instances is not None:
            bundle = validate_runtime_instances(e1.load_json(args.runtime_instances))
            result["runtimeBundle"] = {
                "status": "PASS",
                "bundleId": bundle["bundleId"],
                "contextId": bundle["context"]["contextId"],
                "selectionId": bundle["cspecSelection"]["selectionId"],
                "evaluationId": bundle["criterionEvaluations"][0]["evaluationId"],
            }
    except (
        e1.ContractError,
        json.JSONDecodeError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
