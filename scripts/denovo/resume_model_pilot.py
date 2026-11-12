"""Explicit reviewed continuation of the SAME ledger; no API call or key read.

Never removes failed calls or releases uncertain charges. A changed request is
required after a fully received response; timeout/HTTP/unknown charge is not an
automatically resumable case. At most three reviewed adjustment rounds per
explicitly opened task (or per legacy pilot); total money/call limits persist.
"""
from model_call_limits import effective_call_limit
import argparse
import json
import re
from pathlib import Path
import time
from model_literature_contract import canonical, digest, parse
from model_transport import (config, bounded, require, exclusive, atomic,
                             public_config, response_usage, units_cost)


def resume(c, state_dir, expected_sha, preflight_path, review_path):
    root = Path(state_dir)
    preflight_raw = bounded(preflight_path, 262144); preflight = parse(preflight_raw)
    review_raw = bounded(review_path, 65536); review = parse(review_raw)
    require(review.get("schemaVersion") == "denovo-pilot-continuation-review-v1"
            and review.get("authorization") in {"USER_GOAL_AUTHORIZED_DEVELOPMENT", "USER_TASK_AUTHORIZED_DEVELOPMENT"}
            and isinstance(review.get("diagnosis"), str) and len(review["diagnosis"]) >= 20
            and review.get("offlineChecksPassed") is True, "CONTINUATION_REVIEW_REQUIRED")
    require(preflight.get("executeRequested") is False and preflight.get("schemaVersion") == "denovo-model-preflight-v1"
            and len(preflight.get("requests", [])) == 1, "SINGLE_REVIEWED_REQUEST_REQUIRED")
    with exclusive(root):
        p = root / "budget.json"; original = bounded(p, 262144)
        require(digest(original) == expected_sha, "BUDGET_CHANGED_SINCE_REVIEW")
        state = parse(original)
        new_configuration = public_config(c)
        new_fingerprint = digest(canonical(new_configuration))
        previous_configuration = review.get("previousConfiguration")
        if state.get("configurationSha256") != new_fingerprint:
            require(isinstance(previous_configuration, dict)
                    and digest(canonical(previous_configuration)) == state.get("configurationSha256"), "BUDGET_CONFIGURATION_CHANGED")
            # The observed relay separates visible response and reasoning. Only
            # its explicit billed-output bound may change; never fees, money,
            # calls, model, endpoint, or the old calls' reservations.
            previous_base = {k: v for k, v in previous_configuration.items() if k != "maxBilledOutputTokens"}
            current_base = {k: v for k, v in new_configuration.items() if k != "maxBilledOutputTokens"}
            require(previous_base == current_base and review.get("billedOutputChangeReviewed") is True,
                    "BUDGET_CONFIGURATION_CHANGED")
        calls = state.get("calls", [])
        require(isinstance(calls, list) and 0 < len(calls) <= effective_call_limit(c,state,root)
                and all(a.get("status") in {"SUCCESS", "FAILED_RESERVED"}
                        and type(a.get("chargedOrReservedMicroCredits")) is int and a["chargedOrReservedMicroCredits"] >= 0
                        and re.fullmatch(r"[a-f0-9]{64}", a.get("key", "")) for a in calls), "UNSETTLED_CALL_REQUIRES_RECONCILIATION")
        last = calls[-1]
        require(last.get("status") == "FAILED_RESERVED" and state.get("stoppedReason")
                and last.get("reasonCode") == "DENOVO_MODEL_" + state["stoppedReason"], "REVIEWED_FAILURE_REQUIRED")
        permitted_failures = {"CASE_SCHEMA_INVALID", "SCHEMA_INVALID", "FIELD_SCHEMA_INVALID", "CITATION_SCHEMA_INVALID",
                              "INCOMPLETE_OR_AMBIGUOUS_OUTPUT", "OUTPUT_BILLING_LIMIT_EXCEEDED",
                              "VISIBLE_OUTPUT_LIMIT_EXCEEDED", "RESPONSE_NOT_JSON", "RESPONSE_SCHEMA_INVALID",
                              "SCHEMA_INVALID_RESPONSE_PROFILE_MISMATCH"}
        folder = root / "responses" / last["key"]
        raw = bounded(folder / "response.json", c["maxResponseBytes"])
        usage = response_usage(parse(raw), c)
        require(review.get("responseSha256") == digest(raw), "REVIEW_RESPONSE_PIN_MISMATCH")
        # Older bundle transport masked JSON errors as transport/storage errors.
        # Only a pinned, complete response with valid reported usage and invalid
        # content JSON can be diagnosed locally. Unknown HTTP/timeout/storage
        # failures remain stopped; neither old records nor reserves are rewritten.
        legacy_json_failure = False
        if state['stoppedReason'] == 'TRANSPORT_OR_STORAGE_FAILED':
            response = parse(raw)
            choices = response.get('choices')
            if (review.get('localFailureDiagnosis') == 'COMPLETE_RESPONSE_INVALID_JSON'
                    and last.get('responseSha256') == digest(raw)
                    and isinstance(choices, list) and len(choices) == 1
                    and choices[0].get('finish_reason') == 'stop'
                    and isinstance(choices[0].get('message', {}).get('content'), str)
                    and not choices[0]['message'].get('tool_calls') and not choices[0]['message'].get('refusal')):
                try:
                    parse(choices[0]['message']['content'])
                except ValueError:
                    legacy_json_failure = True
        require(state["stoppedReason"] in permitted_failures or legacy_json_failure,
                "FAILURE_REQUIRES_EXTERNAL_RECONCILIATION")
        estimated = units_cost(c, usage["prompt_tokens"], usage["completion_tokens"])
        require(estimated <= last["chargedOrReservedMicroCredits"], "USAGE_EXCEEDS_RESERVATION")
        next_request = preflight["requests"][0]["requestSha256"]
        require(isinstance(next_request, str) and re.fullmatch(r"[a-f0-9]{64}", next_request), "PREFLIGHT_REQUEST_PIN_INVALID")
        prior_request = digest(bounded(folder / "request.json", c["maxRequestBytes"]))
        require(next_request != prior_request and review.get("nextRequestSha256") == next_request,
                "CHANGED_REVIEWED_REQUEST_REQUIRED")
        history = state.setdefault("resumeHistory", [])
        prior_rounds = 0
        if review.get('authorization') == 'USER_TASK_AUTHORIZED_DEVELOPMENT':
            # A later explicitly opened task keeps the same money/call ledger.
            # Its reviewed adjustment count starts at an archived pre-task state,
            # never a new empty budget or an arbitrary claimed counter.
            start_pin = review.get('taskStartBudgetSha256', '')
            require(re.fullmatch(r'[a-f0-9]{64}', start_pin) is not None, 'TASK_START_BUDGET_REQUIRED')
            start_raw = bounded(root / 'continuation-reviews' / (start_pin + '-budget.json'), 262144)
            require(digest(start_raw) == start_pin, 'TASK_START_BUDGET_PIN_MISMATCH')
            start = parse(start_raw); previous = start.get('resumeHistory', [])
            # A newly authorized task may begin with a previously stopped pilot.
            # It must archive the ACTUAL stopped state, not fabricate a clean
            # prefix or open another budget. All failure/response/request checks
            # above still apply, and the unknown-charge reserve stays intact.
            stopped_start = (start.get('stoppedReason') in permitted_failures
                             and start.get('calls') == calls
                             and start.get('resumeHistory', []) == history)
            require(start.get('configurationSha256') == state.get('configurationSha256')
                    and (not start.get('stoppedReason') or stopped_start) and not start.get('resumePermit')
                    and (0 < len(start.get('calls', [])) < len(calls) or stopped_start)
                    and calls[:len(start['calls'])] == start['calls']
                    and all(x.get('status') != 'RESERVED' for x in start['calls'])
                    and history[:len(previous)] == previous, 'TASK_START_NOT_VALID_PREFIX')
            prior_rounds = len(previous)
        require(len(history) - prior_rounds < 3, "ADJUSTMENT_ROUND_LIMIT")
        review_sha = digest(review_raw)
        record = {"previousBudgetSha256": expected_sha, "previousStoppedReason": state["stoppedReason"],
                  "reviewSha256": review_sha, "preflightSha256": digest(preflight_raw), "responseSha256": digest(raw),
                  "reportedUsageEstimateMicroCredits": estimated, "retainedReservationMicroCredits": last["chargedOrReservedMicroCredits"],
                  "requestSha256s": [next_request], "reviewedAt": time.time(), "platformBillReconciled": False}
        if legacy_json_failure:
            record['localFailureDiagnosis'] = 'COMPLETE_RESPONSE_INVALID_JSON'
        if review.get('taskStartBudgetSha256'):
            record['taskStartBudgetSha256'] = review['taskStartBudgetSha256']
        if state["configurationSha256"] != new_fingerprint:
            record["configurationTransition"] = {"previous": previous_configuration, "current": new_configuration}
        archive = root / "continuation-reviews"; archive.mkdir(exist_ok=True)
        backup = archive / (expected_sha + "-budget.json")
        if not backup.exists():
            backup.write_bytes(original)
        (archive / (review_sha + "-review.json")).write_bytes(review_raw)
        history.append(record)
        state["stoppedReason"] = None
        state["configurationSha256"] = new_fingerprint
        state["resumePermit"] = {"reviewSha256": review_sha, "requestSha256s": [next_request]}
        atomic(p, state)
        return {"status": "ONE_CHANGED_REQUEST_PERMITTED", "priorCallsRetained": len(calls),
                "reservedMicroCreditsRetained": sum(a["chargedOrReservedMicroCredits"] for a in calls),
                "adjustmentRound": len(history), "modelCalls": 0, "networkCalls": 0}


def accept_unknown_charge(c, state_dir, expected_sha, next_request_sha, authorization):
    """Explicit user acceptance of transport charge uncertainty, not reconciliation.

    Keep its full reservation and history. Only a different, pinned next request
    is allowed; a later failure stops normally. Never used as automatic retry.
    Beyond three adjustments, an explicitly authorized ongoing task also needs
    a concrete diagnosis and offline checks. Money/call caps never reset.
    """
    require(authorization.get('schemaVersion') == 'model-unknown-charge-acceptance-v1'
            and authorization.get('decision') == 'USER_ACCEPTS_UNKNOWN_CHARGE_RETAIN_FULL_RESERVATION'
            and isinstance(authorization.get('userStatement'), str) and len(authorization['userStatement']) >= 15,
            'UNKNOWN_CHARGE_USER_ACCEPTANCE_REQUIRED')
    require(re.fullmatch(r'[a-f0-9]{64}', next_request_sha or '') is not None, 'PREFLIGHT_REQUEST_PIN_INVALID')
    root=Path(state_dir)
    with exclusive(root):
        path=root/'budget.json'; original=bounded(path,262144); state=parse(original)
        require(digest(original)==expected_sha,'BUDGET_CHANGED_SINCE_REVIEW')
        require(state.get('configurationSha256')==digest(canonical(public_config(c))),'BUDGET_CONFIGURATION_CHANGED')
        calls=state.get('calls',[])
        require(isinstance(calls,list) and 0<len(calls)<effective_call_limit(c,state,root) and all(
            x.get('status') in ('SUCCESS','FAILED_RESERVED') and type(x.get('chargedOrReservedMicroCredits')) is int
            and x['chargedOrReservedMicroCredits']>=0 for x in calls),'UNSETTLED_CALL_REQUIRES_RECONCILIATION')
        last=calls[-1]
        reason=state.get('stoppedReason')
        transient_server_failure=reason in ('HTTP_500','HTTP_502','HTTP_503','HTTP_504')
        require(reason in ('HTTP_0','TIMEOUT') or transient_server_failure,
                'REVIEWED_TRANSPORT_FAILURE_REQUIRED')
        if transient_server_failure:
            require(authorization.get('ongoingTaskAuthorization')=='USER_AUTHORIZED_AUTONOMOUS_CONTINUATION'
                    and authorization.get('offlineChecksPassed') is True
                    and isinstance(authorization.get('diagnosis'),str) and len(authorization['diagnosis'])>=40
                    and authorization.get('maxNextRequests')==1,
                    'TRANSIENT_FAILURE_REVIEW_REQUIRED')
        require(last.get('status')=='FAILED_RESERVED'
                and last.get('reasonCode')=='DENOVO_MODEL_'+reason and not state.get('resumePermit'),
                'REVIEWED_TRANSPORT_FAILURE_REQUIRED')
        prior=[r for r in state.get('resumeHistory',[]) if r.get('userStatement')==authorization['userStatement']]
        if len(prior)>=3:
            require(authorization.get('ongoingTaskAuthorization')=='USER_AUTHORIZED_AUTONOMOUS_CONTINUATION'
                    and authorization.get('offlineChecksPassed') is True
                    and isinstance(authorization.get('diagnosis'),str) and len(authorization['diagnosis'])>=40
                    and authorization.get('maxNextRequests')==1,
                    'USER_ACCEPTED_TRANSPORT_ADJUSTMENT_LIMIT')
        key=last.get('key','');require(re.fullmatch(r'[a-f0-9]{64}',key) is not None,'BUDGET_STATE_INVALID')
        old_request=bounded(root/'responses'/key/'request.json',c['maxRequestBytes'])
        require(digest(old_request)!=next_request_sha,'CHANGED_REVIEWED_REQUEST_REQUIRED')
        accepted={**authorization,'previousBudgetSha256':expected_sha,'failedCallKey':key,
                  'previousStoppedReason':reason,
                  'nextRequestSha256':next_request_sha,'platformBillReconciled':False,
                  'retainedReservationMicroCredits':last['chargedOrReservedMicroCredits']}
        accepted_raw=canonical(accepted); pin=digest(accepted_raw)
        archive=root/'continuation-reviews';archive.mkdir(exist_ok=True)
        for name,raw in ((expected_sha+'-budget.json',original),(pin+'-user-acceptance.json',accepted_raw)):
            file=archive/name
            if file.exists():require(file.read_bytes()==raw,'CONTINUATION_ARCHIVE_CHANGED')
            else:file.write_bytes(raw)
        state.setdefault('resumeHistory',[]).append({**accepted,'reviewSha256':pin,'reviewedAt':time.time()})
        state['stoppedReason']=None
        state['resumePermit']={'reviewSha256':pin,'requestSha256s':[next_request_sha]}
        atomic(path,state)
        return {'status':'ONE_CHANGED_REQUEST_PERMITTED','priorCallsRetained':len(calls),
                'reservedMicroCreditsRetained':sum(x['chargedOrReservedMicroCredits'] for x in calls),
                'platformBillReconciled':False,'modelCalls':0,'networkCalls':0}


def accept_identical_transient_retry(c, state_dir, expected_sha, request_sha, authorization):
    """One reviewed literature retry after a response-free transient HTTP failure.

    This is not an automatic retry policy. Preserve the old request directory and
    charge reservation, leave successful/partial responses untouched, and use the
    SAME ledger/configuration/request. A second identical recovery is forbidden.
    """
    require(authorization.get('schemaVersion') == 'model-unknown-charge-acceptance-v1'
            and authorization.get('decision') == 'USER_ACCEPTS_UNKNOWN_CHARGE_RETAIN_FULL_RESERVATION'
            and isinstance(authorization.get('userStatement'), str) and len(authorization['userStatement']) >= 15
            and authorization.get('ongoingTaskAuthorization') == 'USER_AUTHORIZED_AUTONOMOUS_CONTINUATION'
            and authorization.get('offlineChecksPassed') is True
            and authorization.get('retryIdenticalRequest') is True
            and authorization.get('maxNextRequests') == 1
            and isinstance(authorization.get('diagnosis'), str) and len(authorization['diagnosis']) >= 40,
            'IDENTICAL_RETRY_EXPLICIT_REVIEW_REQUIRED')
    require(re.fullmatch(r'[a-f0-9]{64}', request_sha or '') is not None, 'PREFLIGHT_REQUEST_PIN_INVALID')
    root=Path(state_dir).resolve(strict=True)
    with exclusive(root):
        path=root/'budget.json'; original=bounded(path,262144); state=parse(original)
        require(digest(original)==expected_sha,'BUDGET_CHANGED_SINCE_REVIEW')
        require(state.get('configurationSha256')==digest(canonical(public_config(c))),'BUDGET_CONFIGURATION_CHANGED')
        calls=state.get('calls',[])
        require(isinstance(calls,list) and 0<len(calls)<effective_call_limit(c,state,root) and all(
            x.get('status') in ('SUCCESS','FAILED_RESERVED')
            and type(x.get('chargedOrReservedMicroCredits')) is int and x['chargedOrReservedMicroCredits']>=0
            for x in calls),'UNSETTLED_CALL_REQUIRES_RECONCILIATION')
        reason=state.get('stoppedReason');last=calls[-1]
        require(reason in ('HTTP_500','HTTP_502','HTTP_503','HTTP_504')
                and last.get('status')=='FAILED_RESERVED'
                and last.get('reasonCode')=='DENOVO_MODEL_'+reason and not state.get('resumePermit'),
                'REVIEWED_TRANSIENT_HTTP_FAILURE_REQUIRED')
        require(not any(r.get('identicalRequestRetry') is True and r.get('nextRequestSha256')==request_sha
                        for r in state.get('resumeHistory',[])),'IDENTICAL_RETRY_LIMIT')
        started=last.get('startedAt');elapsed=last.get('elapsedSeconds');now=time.time()
        require(type(started) in (int,float) and type(elapsed) in (int,float)
                and started>=0 and elapsed>=0 and now-started-elapsed>=60,'IDENTICAL_RETRY_COOLDOWN_REQUIRED')
        key=last.get('key','');require(re.fullmatch(r'[a-f0-9]{64}',key) is not None,'BUDGET_STATE_INVALID')
        folder=root/'responses'/key
        require(folder.is_dir() and not folder.is_symlink() and folder.resolve()==folder
                and {p.name for p in folder.iterdir()}=={'request.json'}
                and not (folder/'request.json').is_symlink(),'IDENTICAL_RETRY_RESPONSE_OR_PATH_CONFLICT')
        raw=bounded(folder/'request.json',c['maxRequestBytes'])
        require(digest(raw)==request_sha,'IDENTICAL_RETRY_REQUEST_CHANGED')
        request=parse(raw)
        version=request.get('response_format',{}).get('json_schema',{}).get('schema',{}).get('properties',{}) \
                .get('schemaVersion',{}).get('enum')
        from model_literature_contract import request_matches_config
        require(version in (['pm3-literature-candidates-v1'], ['pp1-family-facts-v1'],
                            ['pp1-family-source-audit-v1'])
                and request_matches_config(request,c),
                'IDENTICAL_RETRY_LITERATURE_REQUEST_REQUIRED')
        from model_transport import money_limit
        reserved=sum(x['chargedOrReservedMicroCredits'] for x in calls)
        next_reservation=units_cost(c,2*len(raw)+1024,c.get('maxBilledOutputTokens',c['maxOutputTokens']))
        limit=money_limit(c)
        require(reserved+next_reservation<=limit,'BUDGET_EXHAUSTED')
        archive=root/'continuation-reviews'; archive.mkdir(exist_ok=True)
        require(not archive.is_symlink() and archive.resolve()==archive,'CONTINUATION_ARCHIVE_PATH_INVALID')
        destination=archive/('failed-literature-attempt-'+key+'-'+digest(canonical(last)))
        require(destination.parent.resolve()==archive and not destination.exists(),'CONTINUATION_ARCHIVE_CHANGED')
        accepted={**authorization,'identicalRequestRetry':True,'previousBudgetSha256':expected_sha,
            'failedCallKey':key,'failedEntrySha256':digest(canonical(last)),
            'failedAttemptArchive':destination.relative_to(root).as_posix(),
            'previousStoppedReason':reason,'nextRequestSha256':request_sha,'platformBillReconciled':False,
            'retainedReservationMicroCredits':last['chargedOrReservedMicroCredits'],'reviewedAt':now}
        accepted_raw=canonical(accepted);pin=digest(accepted_raw)
        for name,content in ((expected_sha+'-budget.json',original),(pin+'-identical-retry.json',accepted_raw)):
            file=archive/name
            if file.exists():require(file.read_bytes()==content,'CONTINUATION_ARCHIVE_CHANGED')
            else:file.write_bytes(content)
        state.setdefault('resumeHistory',[]).append({**accepted,'reviewSha256':pin})
        state['stoppedReason']=None
        state['resumePermit']={'reviewSha256':pin,'requestSha256s':[request_sha]}
        folder.rename(destination)
        try:atomic(path,state)
        except Exception:
            # A failed budget write cannot leave a usable permit with lost evidence.
            destination.rename(folder)
            raise
        return {'status':'ONE_IDENTICAL_REQUEST_PERMITTED','priorCallsRetained':len(calls),
                'reservedMicroCreditsRetained':reserved,'failedAttemptArchive':accepted['failedAttemptArchive'],
                'platformBillReconciled':False,'modelCalls':0,'networkCalls':0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--expected-budget-sha256", required=True)
    parser.add_argument("--preflight", type=Path, required=True)
    parser.add_argument("--review", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(resume(config(args.config), args.state, args.expected_budget_sha256, args.preflight, args.review)))
    except Exception as error:
        reason = str(error) if isinstance(error, ValueError) and str(error).startswith("DENOVO_MODEL_") else "DENOVO_MODEL_CONTINUATION_INVALID"
        print(json.dumps({"status": "STOPPED", "reasonCode": reason})); raise SystemExit(2)


if __name__ == "__main__":
    main()
