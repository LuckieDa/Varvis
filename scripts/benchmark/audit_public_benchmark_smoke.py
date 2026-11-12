#!/usr/bin/env python3
"""Independently audit frozen public benchmark smoke manifests.

This module deliberately does not import or invoke the production benchmark
aggregator. It reads immutable JSON artifacts and emits a path-free,
deterministic audit record.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = "public-benchmark-post-run-audit-v1"
CUTOFFS = (1, 5, 10, 50)
TERMINAL_STATES = {"COMPLETED", "COMPLETED_WITH_FAILURES"}


class AuditError(ValueError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise AuditError("JSON_OBJECT_REQUIRED")
    return value


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def content_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def identity_key(identity: dict[str, Any] | None) -> str | None:
    if not identity:
        return None
    fields = (
        identity.get("genomeAssembly"),
        identity.get("chromosome"),
        identity.get("position"),
        identity.get("reference"),
        identity.get("alternate"),
    )
    if any(value is None for value in fields):
        return None
    return "|".join(str(value) for value in fields)


def best_rank(result: dict[str, Any]) -> int | None:
    ranks = [
        int(match["rank"])
        for match in result.get("truthMatches", [])
        if match.get("rankStatus") == "RANKED" and match.get("rank") is not None
    ]
    return min(ranks) if ranks else None


def case_rank_status(result: dict[str, Any]) -> str:
    if best_rank(result) is not None:
        return "RANKED"
    statuses = {match.get("rankStatus") for match in result.get("truthMatches", [])}
    return "RANK_UNAVAILABLE" if "RANK_UNAVAILABLE" in statuses else "NOT_IN_RANKING_UNIVERSE"


def group_metrics(results: Iterable[dict[str, Any]]) -> dict[str, Any]:
    values = list(results)
    denominator = len(values)
    ranks = [rank for result in values if (rank := best_rank(result)) is not None]
    statuses = Counter(case_rank_status(result) for result in values)
    recall = {
        str(cutoff): (sum(rank <= cutoff for rank in ranks) / denominator if denominator else 0.0)
        for cutoff in CUTOFFS
    }
    mrr = sum(1.0 / rank for rank in ranks) / denominator if denominator else 0.0
    return {
        "eligibleCases": denominator,
        "rankedCases": len(ranks),
        "rankedCoverage": f"{len(ranks)}/{denominator}",
        "notInRankingUniverseCases": statuses["NOT_IN_RANKING_UNIVERSE"],
        "rankUnavailableCases": statuses["RANK_UNAVAILABLE"],
        "recallAtK": recall,
        "meanReciprocalRank": mrr,
        "medianAmongRankedCases": statistics.median(ranks) if ranks else None,
    }


def comparable_rank_shifts(results_by_key: dict[tuple[str, str], dict[str, Any]]) -> list[dict[str, Any]]:
    case_ids = sorted({case_id for case_id, _ in results_by_key})
    shifts: list[dict[str, Any]] = []
    for case_id in case_ids:
        g0 = results_by_key.get((case_id, "G0"))
        g1 = results_by_key.get((case_id, "G1"))
        if g0 is None or g1 is None:
            continue
        g0_rank = best_rank(g0)
        g1_rank = best_rank(g1)
        shifts.append({
            "caseId": case_id,
            "g0Status": case_rank_status(g0),
            "g1Status": case_rank_status(g1),
            "g0Rank": g0_rank,
            "g1Rank": g1_rank,
            "shift": g0_rank - g1_rank if g0_rank is not None and g1_rank is not None else None,
            "direction": "positive_is_improvement",
        })
    return shifts


def result_core(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "caseExecutionStatus": result.get("caseExecutionStatus"),
        "analysisReference": result.get("analysisReference"),
        "truthMatches": result.get("truthMatches", []),
        "stageRetention": result.get("stageRetention", []),
        "providerStatuses": result.get("providerStatuses", {}),
        "primaryFailureCode": result.get("primaryFailureCode"),
        "secondaryFailureCodes": result.get("secondaryFailureCodes", []),
        "failureNote": result.get("failureNote"),
    }


def compare_g1_g2(
    case_ids: Iterable[str],
    run_groups: dict[tuple[str, str], dict[str, Any]],
    results_by_key: dict[tuple[str, str], dict[str, Any]],
    dataset_cases: dict[str, dict[str, Any]],
    reviewed_g2_cases: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    cases: list[dict[str, Any]] = []
    all_effective_inputs_equal = True
    all_results_equal = True
    all_excluded_provenance_equal = True
    for case_id in sorted(case_ids):
        g1_group = run_groups[(case_id, "G1")]
        g2_group = run_groups[(case_id, "G2")]
        g1_result = results_by_key[(case_id, "G1")]
        g2_result = results_by_key[(case_id, "G2")]
        terms_equal = g1_group.get("hpoTerms", []) == g2_group.get("hpoTerms", [])
        config_equal = g1_group.get("configurationFingerprint") == g2_group.get("configurationFingerprint")
        core_equal = result_core(g1_result) == result_core(g2_result)
        reviewed = reviewed_g2_cases.get(case_id)
        reviewed_observed_equal = reviewed is not None and (
            reviewed.get("observedHpoTerms", []) == g1_group.get("hpoTerms", [])
        )
        excluded_equal = reviewed is not None and (
            reviewed.get("excludedHpoTerms", []) == dataset_cases[case_id].get("excludedHpoTerms", [])
        )
        all_effective_inputs_equal &= terms_equal and config_equal
        all_results_equal &= core_equal
        all_excluded_provenance_equal &= excluded_equal
        cases.append({
            "caseId": case_id,
            "observedHpoOrderEqual": terms_equal,
            "configurationFingerprintEqual": config_equal,
            "provenanceAwareHpoChecksumEqual": (
                g1_group.get("hpoInputChecksum") == g2_group.get("hpoInputChecksum")
            ),
            "reviewedObservedTermsEqualToG1": reviewed_observed_equal,
            "excludedHpoProvenanceEqualToDataset": excluded_equal,
            "analysisReferenceEqual": (
                g1_result.get("analysisReference") == g2_result.get("analysisReference")
            ),
            "normalizedResultEqual": core_equal,
            "normalizedResultSha256": content_sha256(result_core(g1_result)) if core_equal else None,
        })
    return {
        "effectiveObservedHpoAndConfigurationEqual": all_effective_inputs_equal,
        "excludedHpoProvenanceEqual": all_excluded_provenance_equal,
        "normalizedResultsEqual": all_results_equal,
        "interpretation": "REPRODUCIBILITY_CONTROL_NOT_AI_GAIN",
        "cases": cases,
    }


def truth_waterfall(
    case: dict[str, Any],
    group_id: str,
    result: dict[str, Any],
) -> list[dict[str, Any]]:
    matches = {
        identity_key(match.get("truthCanonicalIdentity")): match
        for match in result.get("truthMatches", [])
    }
    spiked_stage = next(
        (stage for stage in result.get("stageRetention", []) if stage.get("stage") == "SPIKED_INPUT"),
        None,
    )
    rows: list[dict[str, Any]] = []
    for truth in case.get("truthVariants", []):
        truth_id = identity_key(truth.get("canonicalIdentity"))
        match = matches.get(truth_id, {})
        status = match.get("rankStatus", "RANK_UNAVAILABLE")
        rank = match.get("rank")
        ranked = status == "RANKED"
        below_cutoff = ranked and rank is not None and int(rank) > 50
        rows.append({
            "caseId": case.get("caseId"),
            "groupId": group_id,
            "truthIdentity": truth_id,
            "phenopacketTruth": "PRESENT",
            "spikedInput": "PRESENT" if spiked_stage and spiked_stage.get("presence") == "PRESENT" else "UNRESOLVED",
            "grch38RefAltCanonicalIdentity": "MATCHED" if ranked else "UNRESOLVED",
            "inputParsed": "UNRESOLVED",
            "normalization": "RUN_PASSED_TRUTH_RETENTION_UNRESOLVED",
            "baseCandidateRetention": "UNRESOLVED",
            "vepSelectionAnnotation": "UNRESOLVED",
            "evidenceFilterRescue": "UNRESOLVED",
            "persistedInterpretation": "PRESENT" if ranked else ("ABSENT" if status == "NOT_IN_RANKING_UNIVERSE" else "UNAVAILABLE"),
            "formalRankingUniverse": status,
            "rank": rank,
            "firstLossStage": "NONE" if ranked else "UNRESOLVED",
            "failureCode": "F9" if below_cutoff else None,
            "failureMeaning": "RETAINED_AND_RANKED_BELOW_RECALL_AT_50" if below_cutoff else None,
        })
    return rows


def compare_existing(computed: dict[str, Any], existing: dict[str, Any]) -> dict[str, Any]:
    differences: list[dict[str, Any]] = []
    existing_groups = existing.get("summaries", {})
    for group_id, current in computed.items():
        prior = existing_groups.get(group_id, {})
        fields = {
            "eligibleCases": prior.get("eligibleCases"),
            "rankedCases": prior.get("rankedCases"),
            "notInRankingUniverseCases": prior.get("notInRankingUniverseCases"),
            "rankUnavailableCases": prior.get("rankUnavailableCases"),
            "recallAtK": prior.get("recallAtK"),
            "meanReciprocalRank": prior.get("meanReciprocalRank"),
            "medianAmongRankedCases": prior.get("medianRank"),
        }
        for name, old_value in fields.items():
            new_value = current.get(name)
            if not equivalent_value(old_value, new_value):
                differences.append({
                    "groupId": group_id,
                    "field": name,
                    "existing": old_value,
                    "independent": new_value,
                })
    return {"matchesExistingReport": not differences, "differences": differences}


def equivalent_value(left: Any, right: Any) -> bool:
    if isinstance(left, (int, float)) and not isinstance(left, bool) \
            and isinstance(right, (int, float)) and not isinstance(right, bool):
        return math.isclose(float(left), float(right), rel_tol=1e-12, abs_tol=1e-12)
    if isinstance(left, dict) and isinstance(right, dict):
        return set(left) == set(right) and all(equivalent_value(left[key], right[key]) for key in left)
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(equivalent_value(a, b) for a, b in zip(left, right))
    return left == right


def audit(run_root: Path, dataset_manifest: Path, attempt_provenance: Path | None = None) -> dict[str, Any]:
    manifests = sorted(run_root.glob("run-manifest-*.json"))
    if len(manifests) != 1:
        raise AuditError("EXACTLY_ONE_RUN_MANIFEST_REQUIRED")
    run = read_json(manifests[0])
    dataset = read_json(dataset_manifest)
    aggregate = read_json(run_root / "aggregate-summary.json")
    if run.get("schemaVersion") != "benchmark-run-manifest-v1":
        raise AuditError("UNSUPPORTED_RUN_SCHEMA")
    if dataset.get("datasetId") != run.get("datasetId"):
        raise AuditError("DATASET_ID_MISMATCH")

    run_groups: dict[tuple[str, str], dict[str, Any]] = {}
    for group in run.get("groups", []):
        key = (group.get("caseId"), group.get("groupId"))
        if key in run_groups:
            raise AuditError("DUPLICATE_RUN_GROUP")
        run_groups[key] = group
    case_map = {case.get("caseId"): case for case in dataset.get("cases", []) if case.get("inclusionStatus") == "INCLUDED"}
    reviewed_g2_path = dataset_manifest.parent / "truth-masked-hpo-review-v1" / "final-decisions" / "g2-reviewed-input-manifest.json"
    reviewed_g2_cases: dict[str, dict[str, Any]] = {}
    if reviewed_g2_path.is_file():
        reviewed_g2 = read_json(reviewed_g2_path)
        reviewed_g2_cases = {case.get("caseId"): case for case in reviewed_g2.get("cases", [])}
    expected_keys = {(case_id, group_id) for case_id in case_map for group_id in ("G0", "G1", "G2")}
    if set(run_groups) != expected_keys:
        raise AuditError("RUN_GROUP_MATRIX_INCOMPLETE")

    results_by_key: dict[tuple[str, str], dict[str, Any]] = {}
    result_checksums: list[dict[str, Any]] = []
    for path in sorted(run_root.glob("cases/*/results/*/case-result-*.json")):
        result = read_json(path)
        if result.get("schemaVersion") != "benchmark-case-result-v1":
            raise AuditError("UNSUPPORTED_CASE_RESULT_SCHEMA")
        key = (result.get("caseId"), result.get("groupId"))
        if key in results_by_key:
            raise AuditError("DUPLICATE_CASE_RESULT")
        if key not in expected_keys:
            raise AuditError("UNEXPECTED_CASE_RESULT")
        if result.get("resultId") != f"{key[0]}-{key[1]}":
            raise AuditError("CASE_RESULT_ID_MISMATCH")
        if result.get("runId") != run.get("runId") or result.get("datasetId") != run.get("datasetId"):
            raise AuditError("CASE_RESULT_IDENTITY_MISMATCH")
        if result.get("caseExecutionStatus") not in TERMINAL_STATES:
            raise AuditError("CASE_RESULT_NOT_TERMINAL")
        expected_truths = {
            identity_key(truth.get("canonicalIdentity"))
            for truth in case_map[result["caseId"]].get("truthVariants", [])
        }
        actual_truths = {
            identity_key(match.get("truthCanonicalIdentity"))
            for match in result.get("truthMatches", [])
        }
        if actual_truths != expected_truths:
            raise AuditError("CASE_RESULT_TRUTH_MISMATCH")
        results_by_key[key] = result
        result_checksums.append({
            "caseId": result.get("caseId"),
            "groupId": result.get("groupId"),
            "sha256": file_sha256(path),
        })
    if set(results_by_key) != expected_keys:
        raise AuditError("CASE_RESULT_MATRIX_INCOMPLETE")

    metrics = {
        group_id: group_metrics(results_by_key[(case_id, group_id)] for case_id in sorted(case_map))
        for group_id in ("G0", "G1", "G2")
    }
    waterfalls = [
        row
        for case_id in sorted(case_map)
        for group_id in ("G0", "G1", "G2")
        for row in truth_waterfall(case_map[case_id], group_id, results_by_key[(case_id, group_id)])
    ]
    failure_counts = Counter(row["failureCode"] for row in waterfalls if row["failureCode"])

    multi_truth: list[dict[str, Any]] = []
    for case_id, case in sorted(case_map.items()):
        if len(case.get("truthVariants", [])) < 2:
            continue
        groups: dict[str, Any] = {}
        for group_id in ("G0", "G1", "G2"):
            result = results_by_key[(case_id, group_id)]
            truth_rows = [
                {"truthIdentity": identity_key(match.get("truthCanonicalIdentity")), "rankStatus": match.get("rankStatus"), "rank": match.get("rank")}
                for match in result.get("truthMatches", [])
            ]
            all_ranks = [row["rank"] for row in truth_rows if row["rankStatus"] == "RANKED" and row["rank"] is not None]
            groups[group_id] = {
                "truths": truth_rows,
                "allReportedTruthsAtK": {
                    str(cutoff): len(all_ranks) == len(truth_rows) and max(all_ranks) <= cutoff
                    for cutoff in CUTOFFS
                },
            }
        multi_truth.append({
            "caseId": case_id,
            "frozenTruthRelationship": case.get("truthRelationship"),
            "formalCaseMetric": "BEST_NUMERIC_TRUTH_RANK",
            "pairSemantics": "TRUTH_SET_SEMANTICS_UNRESOLVED",
            "auxiliaryMetric": "ALL_REPORTED_TRUTHS_AT_K_NOT_A_PHASED_PAIR_METRIC",
            "groups": groups,
        })

    normalization_rows: list[dict[str, Any]] = []
    workspace = run_root / "varvis-workspace"
    if workspace.is_dir():
        for path in sorted(workspace.glob("*/normalization-manifest.json")):
            manifest = read_json(path)
            normalization_rows.append({
                "analysisReference": path.parent.name,
                "status": manifest.get("status"),
                "assembly": manifest.get("assembly"),
                "rawRecordCount": manifest.get("rawRecordCount"),
                "normalizedAlleleCount": manifest.get("normalizedAlleleCount"),
                "skippedRecordCount": manifest.get("skippedRecordCount"),
                "referenceProfileId": manifest.get("referenceProfileId"),
                "configuredReferenceSha256": manifest.get("configuredReferenceSha256"),
            })

    attempts: dict[str, Any] = {"structuredPriorAttemptProvenancePresent": False, "attempts": []}
    if attempt_provenance is not None:
        value = read_json(attempt_provenance)
        prior = value.get("priorAttempts", [])
        attempts = {
            "structuredPriorAttemptProvenancePresent": True,
            "attempts": [
                {"runId": item.get("runId"), "status": item.get("status"), "failure": item.get("failure")}
                for item in prior
            ] + [{
                "runId": value.get("runId"),
                "status": value.get("status"),
                "failure": (value.get("failure") or {}).get("code"),
            }],
        }

    audit_value = {
        "schemaVersion": SCHEMA_VERSION,
        "run": {
            "runId": run.get("runId"),
            "datasetId": run.get("datasetId"),
            "datasetVersion": run.get("datasetVersion"),
            "protocolVersion": run.get("protocolVersion"),
            "runManifestSchema": run.get("schemaVersion"),
            "runStatus": run.get("status"),
            "gitCommit": run.get("gitCommit"),
            "rankingContractVersion": run.get("rankingContractVersion"),
            "backgroundFingerprint": run.get("backgroundFingerprint"),
            "truthFingerprint": run.get("truthFingerprint"),
            "sharedConfigurationFingerprint": run.get("sharedConfigurationFingerprint"),
            "candidateCap": (run.get("configuration") or {}).get("candidateCap"),
            "vepCap": (run.get("configuration") or {}).get("vepCap"),
        },
        "inputIntegrity": {
            "caseCount": len(case_map),
            "groupCount": len(results_by_key),
            "uniqueCompleteMatrix": set(results_by_key) == expected_keys,
            "datasetManifestSha256": file_sha256(dataset_manifest),
            "runManifestSha256": file_sha256(manifests[0]),
            "existingAggregateSha256": file_sha256(run_root / "aggregate-summary.json"),
            "terminalResultCount": sum(
                result.get("caseExecutionStatus") in TERMINAL_STATES for result in results_by_key.values()
            ),
            "groupHpoInputChecksums": [
                {
                    "caseId": case_id,
                    "groupId": group_id,
                    "sha256": run_groups[(case_id, group_id)].get("hpoInputChecksum"),
                }
                for case_id in sorted(case_map)
                for group_id in ("G0", "G1", "G2")
            ],
            "phenopacketChecksums": [
                {"caseId": case.get("caseId"), "sha256": case.get("phenopacketSha256")}
                for case in read_json(dataset_manifest.parent / "selection-acceptance.json").get("cases", [])
            ] if (dataset_manifest.parent / "selection-acceptance.json").is_file() else [],
            "resultChecksums": result_checksums,
        },
        "independentMetrics": metrics,
        "comparisonWithExistingAggregate": compare_existing(metrics, aggregate),
        "g0ToG1RankShifts": comparable_rank_shifts(results_by_key),
        "multiTruthAudit": multi_truth,
        "truthStageWaterfall": waterfalls,
        "failureCountsPerTruth": dict(sorted(failure_counts.items())),
        "g1G2Audit": compare_g1_g2(case_map, run_groups, results_by_key, case_map, reviewed_g2_cases),
        "normalizationRuns": normalization_rows,
        "rankingUniverseAudit": {
            "manifestCandidateCap": (run.get("configuration") or {}).get("candidateCap"),
            "observedUniverseSizes": sorted({
                match.get("rankingUniverseSize")
                for result in results_by_key.values()
                for match in result.get("truthMatches", [])
                if match.get("rankingUniverseSize") is not None
            }),
            "eligibleCandidateCount": "NOT_RECORDED_IN_FROZEN_CASE_RESULTS",
            "persistedInterpretationCount": 20000,
            "rankReaderScope": "COMPLETE_PERSISTED_INTERPRETATION_UNIVERSE",
            "uiCsvCapIsRankSource": False,
            "vepCapIsRankSource": False,
        },
        "attemptProvenance": attempts,
        "limitations": [
            "INTERMEDIATE_PER_TRUTH_RETENTION_NOT_RECORDED",
            "ELIGIBLE_CANDIDATE_COUNT_NOT_RECORDED",
            "TRUTH_SET_SEMANTICS_UNRESOLVED",
            "NONZERO_EXIT_PROVENANCE_NOT_IN_CASE_RESULT",
            "APPLICATION_AND_ORCHESTRATOR_COMMITS_NOT_SEPARATED_IN_RUN_MANIFEST",
        ],
    }
    audit_value["auditFingerprint"] = content_sha256(audit_value)
    return audit_value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    temporary.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-root", type=Path, required=True)
    parser.add_argument("--dataset-manifest", type=Path, required=True)
    parser.add_argument("--attempt-provenance", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.run_root, args.dataset_manifest, args.attempt_provenance)
    write_json(args.output, result)
    print(canonical_json({
        "auditFingerprint": result["auditFingerprint"],
        "groupCount": result["inputIntegrity"]["groupCount"],
        "metricsMatch": result["comparisonWithExistingAggregate"]["matchesExistingReport"],
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
