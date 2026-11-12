"""Offline stage gate for the one-shot PP3 v3 public-control acceptance.

This is not an alternative production evaluator. The caller must supply fresh
normal API records and this attempt's receipt; READY_FULL alone never permits POST.
"""

PHASES = {"LEGACY_ANNOTATION", "EXPLICIT_RAW"}
CONTROLS = {"GRCh38|11|5227002|T|A", "GRCh38|13|20189473|C|T"}


def budget_counts(ledger, task_id, analysis_id, resource_identity):
    if (ledger.get("schemaVersion") != "am-task-phase-budget-v1"
            or ledger.get("taskId") != task_id
            or ledger.get("analysisId") != analysis_id
            or ledger.get("resourceIdentity") != resource_identity
            or ledger.get("status") != "ACTIVE" or ledger.get("stopReason")):
        raise ValueError("AM_BUDGET_BINDING_OR_STATE_INVALID")
    counts = {}
    for field in ("invocations", "transportAttempts", "legacyCacheHits"):
        values = ledger.get(field)
        if not isinstance(values, dict) or any(
                phase not in PHASES or type(n) is not int or n < 0
                for phase, n in values.items()):
            raise ValueError("AM_BUDGET_COUNTER_INVALID")
        # Empty persisted maps mean zero recorded operations, not missing proof.
        counts[field] = {phase: values.get(phase, 0) for phase in sorted(PHASES)}
    transport = counts["transportAttempts"]
    if any(n > 2 for n in transport.values()) or sum(transport.values()) > 4:
        raise ValueError("AM_TRANSPORT_CAP_EXCEEDED")
    if ledger.get("trustedRawReuse") != 0:
        raise ValueError("AM_UNAUTHORIZED_RAW_REUSE")
    events = ledger.get("events")
    if not isinstance(events, list):
        raise ValueError("AM_BUDGET_EVENTS_MISSING")
    seen = set()
    for event in events:
        key = (event.get("purpose"), event.get("queryAllele"))
        if (key[0] not in PHASES or key[1] not in CONTROLS or key in seen
                or event.get("taskId") != task_id or event.get("analysisId") != analysis_id
                or event.get("resourceIdentity") != resource_identity):
            raise ValueError("AM_BUDGET_EVENT_INVALID")
        seen.add(key)
    if any(sum(p == phase for p, _ in seen) != n for phase, n in transport.items()):
        raise ValueError("AM_BUDGET_EVENT_COUNT_MISMATCH")
    return counts


def require_raw_ready(job, receipt, candidates, ledger, task_id, resource_identity):
    if job.get("status") != "READY":
        raise ValueError("MAIN_ANALYSIS_NOT_READY")
    if (receipt.get("runnerVersion") != "vep-windows-job-v3"
            or receipt.get("purpose") != "VEP_ANNOTATION" or receipt.get("failure") != ""
            or receipt.get("rootExitCodeObserved") != 0 or receipt.get("executionExitCode") != 0
            or receipt.get("terminationRequested") is not False
            or receipt.get("activeProcessCount") != 0
            or not all(receipt.get(key) is True for key in (
                "jobBound", "rootExitObserved", "exitConfirmed", "cleanupWithinBudget",
                "stdoutStderrHandlesClosed", "outputReadComplete"))):
        raise ValueError("V3_SCIENTIFIC_VEP_NOT_VERIFIED")
    counts = budget_counts(ledger, task_id, job["id"], resource_identity)
    if counts["transportAttempts"]["EXPLICIT_RAW"] or counts["invocations"]["EXPLICIT_RAW"]:
        raise ValueError("EXPLICIT_ATTEMPT_ALREADY_STARTED")
    if not candidates or any(c.get("variantId") not in CONTROLS for c in candidates):
        raise ValueError("PUBLIC_CANDIDATE_IDENTITY_INVALID")
    eligible = []
    for candidate in candidates:
        annotations = candidate.get("transcriptAnnotations") or []
        if any(a.get("status") == "FAILED" or a.get("source") == "VEP_UNAVAILABLE"
               for a in annotations):
            raise ValueError("PUBLISHED_VEP_FAILED")
        if any(a.get("status") == "COMPLETED" and a.get("source", "").startswith("VEP")
               and a.get("transcriptId") for a in annotations):
            eligible.append(candidate["candidateId"])
    if not eligible:
        raise ValueError("NO_ACCEPTED_VEP_ANNOTATIONS")
    return {"eligibleCandidateIds": eligible, "counts": counts}
