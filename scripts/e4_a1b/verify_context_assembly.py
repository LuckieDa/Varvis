"""Offline E4-A1B acceptance of named Java assembly results, with frozen E1 checks.

The input is synthetic smoke output, not clinical validation. Source references are
retained for audit; this verifier does not authenticate their biological claims.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.evidence.acmg_evidence_v2_contract import (
    ContractSchemas,
    _assert_context_scope,
    allele_identity_projection,
    content_id,
    context_identity_projection,
)


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise ValueError(reason)


def fields(value: object, names: tuple[str, ...]) -> dict | None:
    if value is None:
        return None
    require(isinstance(value, dict), "SOURCE_REFERENCE_OBJECT_REQUIRED")
    return {name: value.get(name) for name in names}


def source_references(attempt: dict) -> dict:
    provenance = attempt.get("provenance") or {}
    lineage = provenance.get("assemblyLineage") or {}
    keys = ("source", "version", "recordLocator", "artifactSha256")
    references = {name: fields(provenance.get(name), keys)
                  for name in ("transcript", "patient", "normalization", "reference")}
    references["assemblyLineage"] = fields(lineage.get("source"), keys)
    binding_provenance = (attempt.get("binding") or {}).get("provenance") or {}
    hypothesis = binding_provenance.get("patientHypothesis") or {}
    references["hypothesis"] = fields(hypothesis.get("provenance"), keys[:3])
    resource_keys = ("resourceId", "resourceVersion", "rawSha256", "derivedSha256")
    references["mane"] = fields(binding_provenance.get("maneResourceIdentity"), resource_keys)
    references["clingen"] = fields(binding_provenance.get("clingenResourceIdentity"), resource_keys)
    return references


def verify(results: dict) -> dict:
    require(isinstance(results, dict) and bool(results), "NAMED_ASSEMBLY_RESULTS_REQUIRED")
    schemas = ContractSchemas()
    contexts, attempts, cases = [], [], []
    reason_counts = Counter()
    for case_name, result in results.items():
        require(isinstance(result, dict), "ASSEMBLY_RESULT_REQUIRED")
        candidate_ids, candidates = result.get("candidateIds"), result.get("candidates")
        require(isinstance(candidate_ids, list) and isinstance(candidates, list), "CANDIDATE_ROWS_REQUIRED")
        require(type(result.get("selectedCount")) is int
                and result["selectedCount"] == len(candidate_ids) == len(candidates), "SELECTED_ROW_COUNT_CHANGED")
        require([row.get("candidateId") for row in candidates] == candidate_ids, "CANDIDATE_ID_ORDER_CHANGED")
        require(result.get("candidateOrderingChanged") is False, "CANDIDATE_ORDERING_CHANGED")
        unmatched = result.get("unmatchedInputs")
        require(isinstance(unmatched, list), "UNMATCHED_INPUTS_REQUIRED")
        case_successes, case_failures = 0, 0
        located_attempts = []
        for candidate_ordinal, row in enumerate(candidates):
            row_attempts = row.get("attempts")
            require(isinstance(row_attempts, list) and bool(row_attempts), "CANDIDATE_ATTEMPTS_REQUIRED")
            for attempt_ordinal, attempt in enumerate(row_attempts):
                require(attempt.get("candidateId") == candidate_ids[candidate_ordinal], "ATTEMPT_CANDIDATE_ID_CHANGED")
                located_attempts.append(("SELECTED", candidate_ordinal, attempt_ordinal, attempt))
        located_attempts.extend(("UNMATCHED", None, ordinal, attempt) for ordinal, attempt in enumerate(unmatched))
        for location, candidate_ordinal, attempt_ordinal, attempt in located_attempts:
            context, seed, blockers = attempt.get("context"), attempt.get("seed"), attempt.get("blockers")
            require(isinstance(blockers, list), "STRUCTURED_BLOCKERS_REQUIRED")
            references = source_references(attempt)
            row = {"case": case_name, "location": location, "candidateOrdinal": candidate_ordinal,
                   "attemptOrdinal": attempt_ordinal, "candidateId": attempt.get("candidateId"),
                   "hypothesisId": attempt.get("hypothesisId"), "sourceRefs": references}
            row["assemblyLineage"] = fields((attempt.get("provenance") or {}).get("assemblyLineage"),
                                            ("originalInputAssembly", "preRankingMappingStatus",
                                             "originalInputAllele", "transformationId"))
            if context is not None:
                require(location == "SELECTED" and isinstance(context, dict)
                        and isinstance(seed, dict) and not blockers, "SUCCESS_SHAPE_INVALID")
                require(seed.get("candidateId") == attempt.get("candidateId"), "SEED_CANDIDATE_ID_CHANGED")
                schemas.validate(context, "urn:varvis:schema:evidence-context-v2")
                _assert_context_scope(context)
                require(context["dataClass"] == "SYNTHETIC"
                        and (attempt.get("provenance") or {}).get("dataClass") == "SYNTHETIC",
                        "SYNTHETIC_CONTEXT_REQUIRED")
                variant = context["variant"]
                require(variant["assemblyLineage"]["mappingStatus"] == "NATIVE"
                        and all(variant[name]["assembly"] == "GRCh38" for name in (
                            "inputAllele", "normalizedAllele", "evidenceQueryAllele")), "NATIVE_GRCH38_REQUIRED")
                require(variant["alleleId"] == content_id("allele-sha256-", allele_identity_projection(context)),
                        "ALLELE_ID_RECOMPUTATION_MISMATCH")
                require(context["contextId"] == content_id("context-sha256-", context_identity_projection(context)),
                        "CONTEXT_ID_RECOMPUTATION_MISMATCH")
                binding = attempt.get("binding") or {}
                require(binding.get("status") == "READY" and binding.get("candidateId") == attempt.get("candidateId")
                        and binding.get("hypothesisId") == attempt.get("hypothesisId")
                        and binding.get("hgncId") == context["gene"]["hgncId"]
                        and binding.get("mondoId") == context["disease"]["mondoId"]
                        and binding.get("normalizedMoi") == context["moi"], "CONTEXT_BINDING_IDENTITY_CONFLICT")
                for name in ("transcript", "patient", "normalization", "reference", "assemblyLineage"):
                    ref = references[name]
                    require(isinstance(ref, dict) and all(isinstance(ref[key], str) and ref[key]
                            for key in ("source", "version", "recordLocator", "artifactSha256"))
                            and bool(re.fullmatch(r"[0-9a-f]{64}", ref["artifactSha256"])), "SUCCESS_SOURCE_REFERENCE_REQUIRED")
                contexts.append({
                    "case": case_name, "candidateOrdinal": candidate_ordinal, "attemptOrdinal": attempt_ordinal,
                    "candidateId": attempt.get("candidateId"), "hypothesisId": attempt.get("hypothesisId"),
                    "alleleId": variant["alleleId"], "contextId": context["contextId"],
                    "hgncId": context["gene"]["hgncId"], "symbol": context["gene"]["symbol"],
                    "mondoId": context["disease"]["mondoId"], "moi": context["moi"],
                    "transcript": fields(context["transcript"], ("accession", "version")),
                    "familyContextPresent": "familyContextId" in context["patientContext"],
                })
                row.update(status="READY", blockerReasons=[])
                case_successes += 1
            else:
                require(seed is None and bool(blockers), "FAILED_SEED_OR_BLOCKERS_INVALID")
                reasons = []
                for blocker in blockers:
                    require(isinstance(blocker, dict) and blocker.get("status") == "CONTEXT_NOT_READY"
                            and isinstance(blocker.get("reasonCode"), str) and bool(blocker["reasonCode"])
                            and (blocker.get("field") is None or isinstance(blocker["field"], str)), "BLOCKER_STRUCTURE_INVALID")
                    reasons.append(blocker["reasonCode"])
                reason_counts.update(reasons)
                row.update(status="CONTEXT_NOT_READY", blockerReasons=reasons)
                case_failures += 1
            attempts.append(row)
        cases.append({"case": case_name, "selectedCount": result["selectedCount"],
                      "candidateIds": candidate_ids, "successfulContexts": case_successes,
                      "blockedAttempts": case_failures, "unmatchedInputCount": len(unmatched)})
    genes = {row["hgncId"] for row in contexts}
    pairs = {(row["hgncId"], row["mondoId"]) for row in contexts}
    require(len(genes) >= 2 and len(pairs) >= 2, "TWO_DISTINCT_GENE_DISEASE_SUCCESSES_REQUIRED")
    return {
        "status": "PASS", "dataClass": "SYNTHETIC", "nativeScope": "GRCh38_NATIVE_SMALL_VARIANT_ONLY",
        "acceptanceScope": "NATIVE_GRCH38_EXPLICIT_INPUTS",
        "clinicalValidation": False, "evaluatorExecuted": False, "classificationExecuted": False,
        "frozenSchemaAndScopePassed": True, "independentAlleleAndContextIdsPassed": True,
        "selectedRowCountsAndIdOrderPassed": True, "candidateOrderingChanged": False,
        "caseCount": len(cases), "successfulContexts": len(contexts), "distinctGenes": len(genes),
        "distinctGeneDiseasePairs": len(pairs), "blockedAttempts": len(attempts) - len(contexts),
        "blockerReasonCounts": dict(sorted(reason_counts.items())),
        "sourceReferenceBoundary": "Caller-supplied receipt identities retained; checksum is not biological authentication.",
        "cases": cases, "contexts": contexts, "attempts": attempts,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Named Java results from varvis.e4a1b.contextOutput")
    parser.add_argument("--summary-output", type=Path)
    args = parser.parse_args()
    summary = verify(json.loads(args.input.read_text(encoding="utf-8")))
    if args.summary_output:
        args.summary_output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                                       encoding="utf-8", newline="\n")
    print(json.dumps({key: summary[key] for key in (
        "status", "caseCount", "successfulContexts", "distinctGenes", "distinctGeneDiseasePairs",
        "blockedAttempts", "blockerReasonCounts", "nativeScope", "clinicalValidation")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
