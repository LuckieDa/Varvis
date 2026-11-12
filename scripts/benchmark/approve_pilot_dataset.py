#!/usr/bin/env python3
"""Validate and freeze the approved 25+5 public pilot dataset."""

import argparse
import copy
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path


APPROVAL_SCHEMA = "benchmark-pilot-dataset-approval-v1"
APPROVED_SCHEMA = "benchmark-pilot-dataset-approved-v1"
APPROVED_STATUS = "APPROVED_FOR_G2_BUILD"
EXPECTED_CHROMOSOMES = {str(value) for value in range(1, 23)} | {"X"}
SHA256 = re.compile(r"^[0-9a-f]{64}$")
GIT_COMMIT = re.compile(r"^[0-9a-f]{40}$")


class ApprovalError(ValueError):
    pass


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def json_fingerprint(value):
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def write_atomic(path, value):
    data = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() == data:
            return "REUSED_IDENTICAL"
        raise ApprovalError(f"APPROVAL_OUTPUT_CONFLICT:{path.name}")
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def require(condition, code):
    if not condition:
        raise ApprovalError(code)


def canonical_string(identity):
    required = ("genomeAssembly", "chromosome", "position", "reference", "alternate")
    require(all(identity.get(field) not in (None, "") for field in required), "TRUTH_IDENTITY_INCOMPLETE")
    require(identity["genomeAssembly"] == "GRCh38", "TRUTH_ASSEMBLY_INVALID")
    require(str(identity["chromosome"]) in EXPECTED_CHROMOSOMES, "TRUTH_CHROMOSOME_INVALID")
    require(int(identity["position"]) > 0, "TRUTH_POSITION_INVALID")
    reference = identity["reference"]
    alternate = identity["alternate"]
    require(re.fullmatch(r"[ACGTN]+", reference or "") is not None, "TRUTH_REFERENCE_INVALID")
    require(re.fullmatch(r"[ACGTN]+", alternate or "") is not None, "TRUTH_ALTERNATE_INVALID")
    require(max(len(reference), len(alternate)) <= 50, "TRUTH_NOT_SMALL_VARIANT")
    return "|".join(str(identity[field]) for field in required)


def reviewer_leaks(review_root, forbidden):
    leaks = []
    for path in sorted((review_root / "reviewer-visible").glob("*")):
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for category, values in forbidden.items():
            for value in values:
                if value and len(value) >= 3 and value in text:
                    leaks.append({"file": path.name, "category": category,
                                  "valueSha256": hashlib.sha256(value.encode("utf-8")).hexdigest()})
    return leaks


def validate_and_build(root, source_commit):
    proposal_path = root / "dataset-proposal-manifest.json"
    acceptance_path = root / "selection-acceptance.json"
    truth_path = root / "truth-policy-manifest.json"
    prior_path = root / "prior-smoke-exclusion-registry.json"
    review_root = root / "truth-masked-hpo-review-v1"
    mapping_path = review_root / "internal" / "DO_NOT_PROVIDE_TO_REVIEWER.mapping.json"
    decision_root = review_root / "final-decisions"
    proposal = read_json(proposal_path)
    acceptance = read_json(acceptance_path)
    truth_policy = read_json(truth_path)
    prior = read_json(prior_path)
    mapping = read_json(mapping_path)

    require(proposal["status"] == "PROPOSED_NOT_APPROVED", "PROPOSAL_STATUS_INVALID")
    require(proposal["humanReviewStatus"] == "HUMAN_REVIEW_COMPLETED", "HUMAN_REVIEW_NOT_COMPLETED")
    require(proposal["executionStatus"] == "NOT_STARTED", "PILOT_ALREADY_EXECUTED")
    require(proposal["license"] == "BSD-3-Clause", "SOURCE_LICENSE_INVALID")
    require(proposal["assembly"] == "GRCh38", "DATASET_ASSEMBLY_INVALID")
    require(SHA256.fullmatch(proposal["archiveSha256"]) is not None, "ARCHIVE_CHECKSUM_INVALID")
    cases = proposal["cases"]
    require(len(cases) == 30, "CASE_COUNT_INVALID")
    require(sum(case["inclusionStatus"] == "PROPOSED_MAIN" for case in cases) == 25, "MAIN_COUNT_INVALID")
    require(sum(case["inclusionStatus"] == "PROPOSED_BACKUP" for case in cases) == 5, "BACKUP_COUNT_INVALID")

    acceptance_cases = acceptance["cases"]
    require(len(acceptance_cases) == 30, "ACCEPTANCE_CASE_COUNT_INVALID")
    by_case = {item["caseId"]: item for item in acceptance_cases}
    require(len(by_case) == 30 and set(by_case) == {case["caseId"] for case in cases}, "CASE_IDENTITY_SET_MISMATCH")
    require([item["selectionOrder"] for item in acceptance_cases] == list(range(1, 31)), "SELECTION_ORDER_INVALID")
    require(all(item["proposalRole"] == ("MAIN" if item["selectionOrder"] <= 25 else "BACKUP")
                for item in acceptance_cases), "MAIN_BACKUP_ORDER_INVALID")

    genes = set()
    chromosomes = set()
    truth_ids = set()
    source_checksums = set()
    truth_set_fingerprints = set()
    forbidden = {"caseId": set(), "source": set(), "gene": set(), "disease": set(), "truth": set()}
    for case in cases:
        report = by_case[case["caseId"]]
        require(case["assembly"] == "GRCh38", "CASE_ASSEMBLY_INVALID")
        require(case["sourceLicense"] == "BSD-3-Clause", "CASE_LICENSE_INVALID")
        require(len(case.get("truthVariants", [])) == 1, "MULTI_TRUTH_NOT_ELIGIBLE")
        identity = canonical_string(case["truthVariants"][0]["canonicalIdentity"])
        require(report["truthSemantics"] == "SINGLE_REQUIRED_ALLELE", "TRUTH_SEMANTICS_INVALID")
        require(report["truthCanonicalIdentities"] == [identity], "TRUTH_REPORT_MISMATCH")
        require(report["truthRegionStatus"] == "INSIDE_FROZEN_BED", "TRUTH_OUTSIDE_BENCHMARK_REGION")
        require(report["hpoReviewStatus"] == "HUMAN_REVIEW_COMPLETED", "CASE_REVIEW_NOT_COMPLETED")
        require(SHA256.fullmatch(report["phenopacketSha256"]) is not None, "SOURCE_CHECKSUM_INVALID")
        require(SHA256.fullmatch(report["truthSetFingerprint"]) is not None, "TRUTH_SET_FINGERPRINT_INVALID")
        require(report["phenopacketSha256"] not in source_checksums, "DUPLICATE_SOURCE")
        require(identity not in truth_ids, "DUPLICATE_TRUTH")
        require(report["truthSetFingerprint"] not in truth_set_fingerprints, "DUPLICATE_TRUTH_SET")
        source_checksums.add(report["phenopacketSha256"])
        truth_ids.add(identity)
        truth_set_fingerprints.add(report["truthSetFingerprint"])
        chromosomes.add(str(case["truthVariants"][0]["canonicalIdentity"]["chromosome"]))
        genes.update(case.get("truthGenes", []))
        forbidden["caseId"].add(case["caseId"])
        forbidden["source"].add(report["sourceRelativePath"])
        forbidden["truth"].add(identity)
        forbidden["gene"].update(case.get("truthGenes", []))
        forbidden["disease"].update(item for disease in case.get("diseases", [])
                                    for item in (disease.get("id"), disease.get("label")) if item)
    require(len(genes) == 30, "UNIQUE_GENE_COUNT_INVALID")
    require(chromosomes == EXPECTED_CHROMOSOMES, "CHROMOSOME_COVERAGE_INVALID")
    strata = acceptance["strata"]
    require(strata["variantTypeCounts"] == {"SMALL_INDEL": 15, "SNV": 15}, "VARIANT_STRATA_INVALID")
    require(strata["observedHpoCountBands"] == {"HPO_1_3": 10, "HPO_4_8": 10, "HPO_9_PLUS": 10}, "HPO_STRATA_INVALID")

    assessments = {item["caseId"]: item for item in truth_policy["assessments"]}
    require(set(assessments) == set(by_case), "TRUTH_POLICY_CASE_SET_MISMATCH")
    require(all(item["eligibilityStatus"] == "ELIGIBLE"
                and item["semantics"] == "SINGLE_REQUIRED_ALLELE"
                and item["sourceRelationship"] == "SINGLE_VARIANT"
                and not item["exclusionReasons"] for item in assessments.values()), "TRUTH_POLICY_NOT_ELIGIBLE")

    require(prior["handling"] == "INTERNAL_SELECTION_CONTROL_NOT_REVIEWER_VISIBLE", "PRIOR_REGISTRY_EXPOSURE_INVALID")
    require(not ({case["caseId"] for case in cases} & set(prior["caseIds"])), "PRIOR_SMOKE_CASE_NOT_EXCLUDED")
    require(not (source_checksums & set(prior["phenopacketSha256"])), "PRIOR_SMOKE_SOURCE_NOT_EXCLUDED")
    require(not (truth_ids & set(prior["truthCanonicalIdentities"])), "PRIOR_SMOKE_TRUTH_NOT_EXCLUDED")
    require(not (truth_set_fingerprints & set(prior["truthSetFingerprints"])), "PRIOR_SMOKE_TRUTH_SET_NOT_EXCLUDED")

    mappings = mapping["mappings"]
    require(mapping["handling"] == "DO_NOT_PROVIDE_TO_REVIEWER", "REVIEW_MAPPING_HANDLING_INVALID")
    require(len(mappings) == 30, "REVIEW_MAPPING_COUNT_INVALID")
    require(len({item["reviewId"] for item in mappings}) == 30, "DUPLICATE_REVIEW_ID")
    require({item["caseId"] for item in mappings} == set(by_case), "REVIEW_CASE_MAPPING_INVALID")
    for item in mappings:
        review_id = item["reviewId"]
        packet = read_json(review_root / "reviewer-visible" / f"{review_id}.packet.json")
        decision = read_json(decision_root / f"{review_id}.decision.json")
        require(item["datasetId"] == proposal["datasetId"], "REVIEW_DATASET_MISMATCH")
        require(packet["reviewId"] == decision["reviewId"] == review_id, "REVIEW_ID_MISMATCH")
        require(packet["packetFingerprint"] == decision["packetFingerprint"] == item["packetFingerprint"], "REVIEW_FINGERPRINT_MISMATCH")
        require(packet["packetContentChecksum"] == decision["packetContentChecksum"], "REVIEW_CONTENT_CHECKSUM_MISMATCH")
        require(decision["reviewCompletionStatus"] == "READY_IDENTICAL_TO_G1", "REVIEW_NOT_READY")
        require(len(packet["terms"]) == len(decision["termDecisions"]), "REVIEW_TERM_COUNT_MISMATCH")
        for term, decided in zip(packet["terms"], decision["termDecisions"]):
            require(term["hpoId"] == decided["hpoId"] and term["originalStatus"] == decided["originalStatus"], "REVIEW_TERM_IDENTITY_MISMATCH")
    leaks = reviewer_leaks(review_root, forbidden)
    require(not leaks, "REVIEWER_VISIBLE_TRUTH_LEAK")

    source_artifacts = [proposal_path, acceptance_path, truth_path, prior_path, mapping_path,
                        decision_root / "formal-decision-import-manifest.json"]
    source_artifacts.extend(sorted((review_root / "reviewer-visible").glob("PILOT-HPO-*.packet.json")))
    source_artifacts.extend(sorted(decision_root.glob("PILOT-HPO-*.decision.json")))
    artifacts = [{"logicalPath": path.relative_to(root).as_posix(), "sha256": sha256(path)}
                 for path in source_artifacts]
    replacement = {
        "mainExecutionOrder": [item["caseId"] for item in acceptance_cases[:25]],
        "backupOrder": [item["caseId"] for item in acceptance_cases[25:]],
        "rule": "REPLACE_ONLY_PRE_EXECUTION_INELIGIBLE_MAIN_WITH_FIRST_UNUSED_BACKUP_IN_FROZEN_ORDER",
        "resultBasedReplacementProhibited": True,
        "replacementRequiresRecordedReason": True,
    }
    fingerprint_input = {
        "datasetId": proposal["datasetId"], "datasetVersion": proposal["datasetVersion"],
        "selectorFingerprint": proposal["selectorFingerprint"], "truthPolicyVersion": proposal["truthPolicyVersion"],
        "caseOrder": [item["caseId"] for item in acceptance_cases],
        "sourceChecksums": sorted(source_checksums), "truthSetFingerprints": sorted(truth_set_fingerprints),
        "replacement": replacement, "artifacts": artifacts,
    }
    approved = copy.deepcopy(proposal)
    approved["schemaVersion"] = APPROVED_SCHEMA
    approved["status"] = APPROVED_STATUS
    approved["approvalStatus"] = APPROVED_STATUS
    approved["executionStatus"] = "NOT_STARTED"
    approved["approval"] = {
        "approvalContractVersion": APPROVAL_SCHEMA,
        "approvalDate": "2026-07-17",
        "authorizationSource": "CONDITIONAL_USER_AUTHORIZATION_AFTER_ALL_HARD_GATES",
        "sourceCommit": source_commit,
        "datasetFingerprint": json_fingerprint(fingerprint_input),
        "replacementPolicy": replacement,
        "artifacts": artifacts,
        "g2Status": "NOT_BUILT",
        "pilotExecutionStatus": "NOT_STARTED",
    }
    for case in approved["cases"]:
        case["inclusionStatus"] = "APPROVED_MAIN" if case["inclusionStatus"] == "PROPOSED_MAIN" else "APPROVED_BACKUP"
    report = {
        "schemaVersion": APPROVAL_SCHEMA,
        "status": "PASSED",
        "datasetId": proposal["datasetId"],
        "sourceCommit": source_commit,
        "datasetFingerprint": approved["approval"]["datasetFingerprint"],
        "hardGates": {
            "mainCount": 25, "backupCount": 5, "uniqueGeneCount": 30,
            "chromosomeCoverage": [str(value) for value in range(1, 23)] + ["X"],
            "snvCount": 15, "smallIndelCount": 15,
            "hpoBands": {"HPO_1_3": 10, "HPO_4_8": 10, "HPO_9_PLUS": 10},
            "truthPolicy": "SINGLE_REQUIRED_ALLELE_ONLY",
            "priorSmokeExclusionDimensions": ["CASE_ID", "SOURCE_SHA256", "CANONICAL_TRUTH", "TRUTH_SET_FINGERPRINT"],
            "reviewDecisionCount": 30, "reviewerVisibleLeakCount": 0,
            "license": "BSD-3-Clause",
        },
        "replacementPolicy": replacement,
        "artifactCount": len(artifacts),
        "g2Authorized": True,
        "pilotExecutionAuthorized": False,
    }
    return approved, report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    require(GIT_COMMIT.fullmatch(args.source_commit.lower()) is not None, "SOURCE_COMMIT_INVALID")
    root = args.dataset_root.resolve()
    approved, report = validate_and_build(root, args.source_commit.lower())
    write_atomic(root / "approved-dataset-manifest.json", approved)
    write_atomic(root / "dataset-approval-validation.json", report)
    print(json.dumps({"status": report["status"], "datasetFingerprint": report["datasetFingerprint"],
                      "artifactCount": report["artifactCount"]}, sort_keys=True))


if __name__ == "__main__":
    main()
