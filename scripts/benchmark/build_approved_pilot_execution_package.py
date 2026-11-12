#!/usr/bin/env python3
"""Build and validate the immutable, authorization-gated public pilot package.

The package is intentionally an execution *plan*. It binds public benchmark
inputs but never invokes PhEval, Varvis, a provider, or a database mutation.
"""

import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path


SCHEMA_VERSION = "approved-public-pilot-execution-package-v2"
REQUEST_SCHEMA_VERSION = "approved-public-pilot-execution-request-v2"
RUN_ID = "stage2b5-approved-public-pilot-v1"
CATALOG_ID = "varvis_benchmark_pilot_0_1_27"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class PackageError(ValueError):
    pass


def require(condition, code):
    if not condition:
        raise PackageError(code)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode("utf-8")).hexdigest()


def truth_set_fingerprint(values):
    """Matches BenchmarkRequestFingerprint.truthSet for canonical identities."""
    return hashlib.sha256("\n".join(sorted(values)).encode("utf-8")).hexdigest()


def truth_policy_fingerprint(values, semantics):
    material = "public-singleton-truth-policy-v1\n" + semantics + "\n" + "\n".join(sorted(values))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_logical(value, field):
    require(isinstance(value, str) and value and not re.match(r"^[A-Za-z]:[\\/]", value),
            "UNSAFE_LOGICAL_PATH:" + field)
    require(not value.startswith(("/", "\\"))
            and ".." not in value.replace("\\", "/").split("/"),
            "UNSAFE_LOGICAL_PATH:" + field)
    return value.replace("\\", "/")


def write_atomic(path, value):
    encoded = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() == encoded:
            return "REUSED_IDENTICAL"
        raise PackageError("EXECUTION_PACKAGE_OUTPUT_CONFLICT:" + path.name)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "wb") as target:
            target.write(encoded)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return "CREATED"


def validate_artifact(value):
    require(value.get("status") == "VALID", "APPLICATION_ARTIFACT_NOT_VALID")
    require(SHA256.fullmatch(value.get("sha256", "")) is not None, "APPLICATION_ARTIFACT_SHA256_INVALID")
    require(re.fullmatch(r"[0-9a-f]{40}", value.get("applicationSourceCommit", "")) is not None,
            "APPLICATION_ARTIFACT_COMMIT_INVALID")
    safe_logical(value.get("logicalArtifactId"), "logicalArtifactId")
    require(isinstance(value.get("sizeBytes"), int) and value["sizeBytes"] > 0,
            "APPLICATION_ARTIFACT_SIZE_INVALID")


def validate_catalog(value):
    require(value.get("logicalCatalogId") == CATALOG_ID, "CATALOG_ID_MISMATCH")
    require(value.get("status") == "READY_EMPTY", "CATALOG_NOT_READY_EMPTY")
    require(value.get("analysisResultRowCount") == 0, "CATALOG_RESULT_ROWS_PRESENT")
    require(SHA256.fullmatch(value.get("schemaFingerprint", "")) is not None,
            "CATALOG_SCHEMA_FINGERPRINT_INVALID")


def package_inputs(repository):
    root = Path(repository) / "docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1"
    approved = read_json(root / "approved-dataset-manifest.json")
    approval = read_json(root / "dataset-approval-validation.json")
    g2 = read_json(root / "truth-masked-hpo-review-v1/approved-g2/pilot-g2-input-manifest.json")
    selection = read_json(root / "selection-acceptance.json")
    require(approved.get("status") == "APPROVED_FOR_G2_BUILD", "DATASET_NOT_APPROVED")
    require(approval.get("status") == "PASSED", "APPROVAL_NOT_VALID")
    require(g2.get("status") == "READY_IDENTICAL_TO_G1", "G2_NOT_READY")
    require(g2.get("approvedDatasetFingerprint") == approval.get("datasetFingerprint"),
            "DATASET_G2_FINGERPRINT_MISMATCH")
    return root, approved, approval, g2, selection


def input_preparation_fingerprint(phenopacket_sha256, truth_set_fingerprint,
                                  background_sha256):
    """Bind the future PhEval output without claiming it already exists."""
    return hashlib.sha256(("approved-public-pilot-input-preparation-v1\n"
                           + phenopacket_sha256 + "\n"
                           + truth_set_fingerprint + "\n"
                           + background_sha256).encode("utf-8")).hexdigest()


def build(repository, artifact, catalog, configuration_fingerprint, background_sha256):
    root, approved, approval, g2, selection = package_inputs(repository)
    validate_artifact(artifact)
    validate_catalog(catalog)
    require(SHA256.fullmatch(configuration_fingerprint) is not None,
            "CONFIGURATION_FINGERPRINT_INVALID")
    require(SHA256.fullmatch(background_sha256) is not None,
            "BACKGROUND_SHA256_INVALID")
    g2_by_case = {item["caseId"]: item for item in g2["cases"]}
    selected_by_case = {item["caseId"]: item for item in selection["cases"]}
    cases, requests = [], []
    for order, dataset_case in enumerate(approved["cases"], 1):
        case_id = dataset_case["caseId"]
        g2_case = g2_by_case.get(case_id)
        selected_case = selected_by_case.get(case_id)
        require(g2_case is not None and g2_case.get("selectionOrder") == order,
                "G2_CASE_ORDER_MISMATCH:" + case_id)
        require(selected_case is not None and selected_case.get("selectionOrder") == order,
                "SELECTION_CASE_ORDER_MISMATCH:" + case_id)
        require(g2_case.get("excludedTermsArePositiveInput") is False,
                "EXCLUDED_HPO_INPUT_VIOLATION:" + case_id)
        role = "MAIN" if order <= 25 else "BACKUP"
        truth_set = sorted(g2_case["truthCanonicalIdentities"])
        truth_policy = truth_policy_fingerprint(truth_set, selected_case.get("truthSemantics", ""))
        require(truth_policy == selected_case.get("truthSetFingerprint"),
                "SELECTION_TRUTH_FINGERPRINT_MISMATCH:" + case_id)
        truth_fingerprint = truth_set_fingerprint(truth_set)
        preparation_fingerprint = input_preparation_fingerprint(
            selected_case["phenopacketSha256"], truth_fingerprint, background_sha256)
        case_manifest = {
            "schemaVersion": "approved-public-pilot-case-v1", "runId": RUN_ID,
            "selectionOrder": order, "role": role, "caseId": case_id,
            "reviewId": g2_case["reviewId"], "packetFingerprint": g2_case["packetFingerprint"],
            "packetContentChecksum": g2_case["packetContentChecksum"],
            "decisionChecksum": g2_case["decisionSha256"],
            "truthPolicyFingerprint": truth_policy,
            "truthSetFingerprint": truth_fingerprint,
            "phenopacketSha256": selected_case["phenopacketSha256"],
            "g2InputChecksum": g2_case["hpoInputChecksum"],
            "caseFingerprint": None,
        }
        case_manifest["caseFingerprint"] = fingerprint(case_manifest)
        cases.append(case_manifest)
        observed = list(g2_case["observedHpoTerms"])
        excluded = list(g2_case["excludedHpoTerms"])
        for group, mode, positive in (("G0", "NONE", []), ("G1", "SOURCE_PROVIDED", observed),
                                      ("G2", "HUMAN_REVIEWED", observed)):
            request_id = "pilot-" + hashlib.sha256((RUN_ID + "\n" + case_id + "\n" + group).encode("utf-8")).hexdigest()[:24]
            item = {
                "schemaVersion": REQUEST_SCHEMA_VERSION, "runId": RUN_ID,
                "requestId": request_id, "attemptId": request_id,
                "caseId": case_id, "groupId": group, "selectionOrder": order, "role": role,
                "hpoInputMode": mode, "positiveObservedHpoTerms": positive,
                "excludedHpoTermsProvenance": excluded,
                "excludedTermsArePositiveInput": False,
                "truthSetFingerprint": truth_fingerprint,
                "truthPolicyFingerprint": truth_policy,
                "phenopacketSha256": selected_case["phenopacketSha256"],
                "inputArtifact": {
                    "logicalArtifactId": "runtime-inputs/" + request_id + ".vcf.gz",
                    "status": "PENDING_AUTHORIZED_PHEVAL_PREPARATION",
                    "backgroundSha256": background_sha256,
                    "preparationFingerprint": preparation_fingerprint,
                },
                "packetFingerprint": g2_case["packetFingerprint"],
                "decisionChecksum": g2_case["decisionSha256"],
                "approvedDatasetFingerprint": approval["datasetFingerprint"],
                "g2ManifestFingerprint": g2["manifestFingerprint"],
                "configurationFingerprint": configuration_fingerprint,
                "applicationArtifactSha256": artifact["sha256"],
                "applicationSourceCommit": artifact["applicationSourceCommit"],
                "logicalCatalogId": CATALOG_ID,
                "rankingContractVersion": "ranking-contract-v1",
                "candidateHardCap": 20000, "vepCap": 8000,
                "expectedOutputIdentity": request_id + ".case-result.json",
                "authorization": "PENDING_EXPLICIT_USER_AUTHORIZATION",
                "runtimeRequestFingerprintStatus": "BOUND_AFTER_AUTHORIZED_PHEVAL_PREPARATION",
                "requestFingerprint": None,
            }
            item["requestFingerprint"] = fingerprint(item)
            requests.append(item)
    require(len(cases) == 30 and len(requests) == 90, "EXECUTION_PACKAGE_COUNT_INVALID")
    require(len({item["requestId"] for item in requests}) == 90, "REQUEST_ID_NOT_UNIQUE")
    require(len({item["requestFingerprint"] for item in requests}) == 90, "REQUEST_FINGERPRINT_NOT_UNIQUE")
    for case in cases:
        group_set = [item for item in requests if item["caseId"] == case["caseId"]]
        require([item["groupId"] for item in group_set] == ["G0", "G1", "G2"],
                "GROUP_ORDER_INVALID:" + case["caseId"])
        require(group_set[1]["positiveObservedHpoTerms"] == group_set[2]["positiveObservedHpoTerms"],
                "G1_G2_INPUT_MISMATCH:" + case["caseId"])
    package = {
        "schemaVersion": SCHEMA_VERSION, "runId": RUN_ID,
        "status": "READY_PENDING_EXECUTION_AUTHORIZATION", "executionStatus": "NOT_STARTED",
        "datasetId": approved["datasetId"], "datasetVersion": approved["datasetVersion"],
        "approvedDatasetFingerprint": approval["datasetFingerprint"],
        "g2ManifestFingerprint": g2["manifestFingerprint"],
        "applicationArtifact": {key: artifact[key] for key in ("logicalArtifactId", "applicationSourceCommit", "sha256", "sizeBytes")},
        "catalog": {key: catalog[key] for key in ("logicalCatalogId", "schemaFingerprint", "status")},
        "configurationFingerprint": configuration_fingerprint,
        "backgroundSha256": background_sha256,
        "rankingContractVersion": "ranking-contract-v1", "candidateHardCap": 20000, "vepCap": 8000,
        "caseCount": 30, "groupCount": 90, "mainCount": 25, "backupCount": 5,
        "providerCallCount": 0, "analysisResultDatabaseWriteCount": 0,
        "authorizationRequired": "EXPLICIT_USER_AUTHORIZATION_FOR_REAL_PILOT_EXECUTION",
        "replacementPolicy": approved["approval"]["replacementPolicy"],
        "terminalRecovery": "EXACT_FINGERPRINT_ONLY", "requests": [item["requestId"] for item in requests],
        "packageFingerprint": None,
    }
    package["packageFingerprint"] = fingerprint(package)
    return root, package, cases, requests


def write_package(root, package, cases, requests, output_relative):
    safe_logical(output_relative, "outputRelative")
    output = root / output_relative
    write_atomic(output / "execution-package.json", package)
    for case in cases:
        write_atomic(output / "cases" / (case["caseId"] + ".json"), case)
    for request in requests:
        write_atomic(output / "requests" / (request["requestId"] + ".json"), request)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--artifact-manifest", type=Path, required=True)
    parser.add_argument("--catalog-manifest", type=Path, required=True)
    parser.add_argument("--configuration-fingerprint", required=True)
    parser.add_argument("--background-sha256", required=True)
    parser.add_argument("--output-relative", default="execution-package-v2")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    artifact, catalog = read_json(args.artifact_manifest), read_json(args.catalog_manifest)
    root, package, cases, requests = build(args.repository_root, artifact, catalog,
                                            args.configuration_fingerprint, args.background_sha256)
    if not args.verify_only:
        write_package(root, package, cases, requests, args.output_relative)
    print(json.dumps({"status": package["status"], "caseCount": len(cases),
                      "groupCount": len(requests), "packageFingerprint": package["packageFingerprint"]},
                     sort_keys=True))


if __name__ == "__main__":
    main()
