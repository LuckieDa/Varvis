"""Bundle-schema consumer of the SAME shared pilot budget and HTTP owner.

Separate version keeps historical v1 receipts reproducible. Budget reservations,
unknown charges, cooldown/stop and explicit continuation permits are unchanged.
"""
from pathlib import Path
from model_call_limits import effective_call_limit
import re
import time
from model_literature_contract import canonical, digest, parse, request_matches_config
from model_transport import (require, bounded, public_config, units_cost, https_once,
                             response_usage, exclusive, atomic, money_limit)
# Imported inside decode_response to avoid a contract/transport initialization cycle.

VERSION = "denovo-bundle-model-transport-1.0.0"


def cache_identity(c, request, binding):
    identity = {'transportVersion':VERSION,'configuration':public_config(c),
                'requestSha256':digest(canonical(request)),'binding':binding}
    return identity, digest(canonical(identity))


def cached(c, request, state_dir, binding):
    """Read a successful exact cache only. No credential read, reserve or transport."""
    identity, key = cache_identity(c,request,binding)
    root=Path(state_dir); folder=root/'responses'/key
    if not (folder/'receipt.json').is_file():
        return None
    with exclusive(root):
        state=parse(bounded(root/'budget.json',262144))
        receipt=parse(bounded(folder/'receipt.json',65536))
        entries=[item for item in state['calls'] if item.get('key')==key and item.get('status')=='SUCCESS']
        recoveries=[item for item in state.get('receivedResponseRecoveries', []) if item.get('key') == key
                    and item.get('receiptSha256') == digest(canonical(receipt))]
        require(len(entries)==1 or (not entries and len(recoveries)==1), 'CACHE_SUCCESS_LEDGER_REQUIRED')
        if recoveries:
            recovery = recoveries[0]
            require(re.fullmatch(r'[a-f0-9]{64}', recovery.get('previousBudgetSha256', '')) is not None,
                    'RECOVERY_BUDGET_PIN')
            prior_raw = bounded(root/'continuation-reviews'/(recovery['previousBudgetSha256']+'-budget.json'),262144)
            require(digest(prior_raw) == recovery['previousBudgetSha256'], 'RECOVERY_BUDGET_PIN')
            prior = parse(prior_raw)
            failed = [x for x in prior['calls'] if x.get('key') == key and x.get('status') == 'FAILED_RESERVED']
            require(len(failed) == 1 and failed[0] in state['calls']
                    and receipt.get('receivedResponseRecovery', {}).get('failedEntrySha256') == digest(canonical(failed[0])),
                    'RECOVERY_FAILED_ENTRY_CHANGED')
        raw=bounded(folder/'response.json',c['maxResponseBytes'])
        require(receipt.get('identity')==identity and receipt.get('responseSha256')==digest(raw),'CACHE_PIN_MISMATCH')
        unused,usage=decode_response(raw,c, candidate_version(request))
        require(receipt.get('usage')==usage,'CACHE_USAGE_MISMATCH')
        return raw,receipt,True

def candidate_version(request):
    return request['response_format']['json_schema']['schema']['properties']['schemaVersion']['enum'][0]


def decode_response(raw, c, expected_candidate_version=None):
    response = parse(raw)
    usage = response_usage(response, c)
    require(usage["completion_tokens"] <= c.get("maxBilledOutputTokens", c["maxOutputTokens"]), "OUTPUT_BILLING_LIMIT_EXCEEDED")
    thoughts = (usage.get("completion_tokens_details") or {}).get("reasoning_tokens", 0)
    require(usage["completion_tokens"] - thoughts <= c["maxOutputTokens"], "VISIBLE_OUTPUT_LIMIT_EXCEEDED")
    choices = response.get("choices")
    require(isinstance(choices, list) and len(choices) == 1 and choices[0].get("finish_reason") == "stop", "INCOMPLETE_OR_AMBIGUOUS_OUTPUT")
    message = choices[0].get("message", {})
    require(not message.get("tool_calls") and not message.get("refusal")
            and isinstance(message.get("content"), str), "CONTENT_UNAVAILABLE")
    content = message['content']
    # A single complete JSON fence is presentation, not a different schema.
    # Preserve raw response bytes; never extract JSON from prose/multiple blocks.
    fenced = re.fullmatch(r'\s*```json\s*\r?\n([\s\S]*?)\r?\n```\s*', content)
    if fenced:
        content = fenced[1]
    try:
        candidate = parse(content)
    except ValueError:
        raise ValueError('DENOVO_MODEL_RESPONSE_NOT_JSON') from None
    require(isinstance(candidate, dict) and (expected_candidate_version is None or candidate.get('schemaVersion') == expected_candidate_version),
            'SCHEMA_INVALID_RESPONSE_PROFILE_MISMATCH')
    try:
        if expected_candidate_version == 'literature-reading-advice-v1':
            from literature_relevance import check_candidate
        elif expected_candidate_version in ('pp1-pedigree-visual-v1','pp1-pedigree-visual-v2'):
            from pp1_pedigree import check_candidate
        elif expected_candidate_version in ('pp1-family-facts-v1', 'pp1-family-source-audit-v1'):
            from pp1_literature_contract import check_candidate
        elif expected_candidate_version == 'pm3-literature-candidates-v1':
            from pm3_literature_contract import check_candidate
        elif expected_candidate_version == 'denovo-core-source-audit-v1':
            from core_model_review_contract import check_candidate
        elif expected_candidate_version == 'pp2-relationship-material-candidates-v1':
            from bundle_model_contract import relationship_material
            from prepare_literature_prompt import check_shape
            def check_candidate(value):
                check_shape(value, relationship_material().SCHEMA)
        else:
            from bundle_model_contract import check_candidate
        check_candidate(candidate)
    except ValueError:
        raise ValueError('DENOVO_MODEL_RESPONSE_SCHEMA_INVALID') from None
    # OpenAI-compatible completion_tokens includes reasoning; do not double count.
    return candidate, usage


def invoke(c, key, request, state_dir, request_identity, transport=https_once):
    body = canonical(request)
    require(len(body) <= c["maxRequestBytes"], "INPUT_TOO_LARGE_NOT_TRUNCATED")
    require(request_matches_config(request, c), "REQUEST_CONFIG_MISMATCH")
    identity = {"transportVersion": VERSION, "configuration": public_config(c),
                "requestSha256": digest(body), "binding": request_identity}
    cache_key = digest(canonical(identity))
    root = Path(state_dir); cache = root / "responses" / cache_key
    with exclusive(root):
        state_path = root / "budget.json"
        fingerprint = digest(canonical(public_config(c)))
        state = parse(bounded(state_path, 262144)) if state_path.exists() else {
            "schemaVersion": "denovo-model-budget-v1", "configurationSha256": fingerprint,
            "calls": [], "stoppedReason": None}
        require(state.get("configurationSha256") == fingerprint, "BUDGET_CONFIGURATION_CHANGED")
        require(isinstance(state.get("calls"), list) and len(state["calls"]) <= effective_call_limit(c,state,root), "BUDGET_STATE_INVALID")
        require(all(isinstance(a, dict) and type(a.get("chargedOrReservedMicroCredits")) is int
                    and a["chargedOrReservedMicroCredits"] >= 0
                    and a.get("status") in {"RESERVED", "SUCCESS", "FAILED_RESERVED"}
                    for a in state["calls"]), "BUDGET_STATE_INVALID")
        # An interrupted reservation is not free and cannot be retried implicitly.
        require(all(a.get("status") != "RESERVED" for a in state["calls"]), "UNSETTLED_CALL_REQUIRES_RECONCILIATION")
        if cache.exists():
            require((cache / "receipt.json").is_file() and (cache / "response.json").is_file(), "CACHE_INCOMPLETE")
            receipt = parse(bounded(cache / "receipt.json", 65536))
            raw = bounded(cache / "response.json", c["maxResponseBytes"])
            require(receipt.get("identity") == identity and receipt.get("responseSha256") == digest(raw), "CACHE_PIN_MISMATCH")
            decode_response(raw, c, candidate_version(request))
            return raw, receipt, True
        require(not state.get("stoppedReason"), state.get("stoppedReason") or "STOPPED")
        permit = state.get("resumePermit")
        if permit:
            require(identity["requestSha256"] in permit["requestSha256s"], "REVIEWED_CONTINUATION_REQUEST_REQUIRED")
        require(len(state["calls"]) < effective_call_limit(c,state,root), "CALL_LIMIT")
        # UTF-8 bytes overestimate ordinary text tokens; include JSON/schema and
        # generous framing overhead. If usage exceeds this reservation, stop.
        input_bound = 2 * len(body) + 1024
        reservation = units_cost(c, input_bound, c.get("maxBilledOutputTokens", c["maxOutputTokens"]))
        budget = money_limit(c)
        require(sum(a["chargedOrReservedMicroCredits"] for a in state["calls"]) + reservation <= budget, "BUDGET_EXHAUSTED")
        entry = {"key": cache_key, "status": "RESERVED", "startedAt": time.time(),
                 "chargedOrReservedMicroCredits": reservation}
        if permit:
            entry["continuationReviewSha256"] = permit["reviewSha256"]
            state["resumePermit"] = None
        state["calls"].append(entry); atomic(state_path, state)
        cache.mkdir(parents=True)
        (cache / "request.json").write_bytes(body)
        started = time.monotonic()
        try:
            raw = transport(c, key, body)
            require(0 < len(raw) <= c["maxResponseBytes"], "RESPONSE_SIZE_LIMIT")
            # Redact no source facts, but never persist an echoed API credential.
            require(key.encode() not in raw, "CREDENTIAL_ECHO_REJECTED")
            (cache / "response.json").write_bytes(raw)
            unused, usage = decode_response(raw, c, candidate_version(request))
            actual = units_cost(c, usage["prompt_tokens"], usage["completion_tokens"])
            require(usage["prompt_tokens"] <= input_bound and actual <= reservation, "USAGE_EXCEEDS_RESERVATION")
            receipt = {"identity": identity, "responseSha256": digest(raw), "usage": usage,
                       "estimatedMicroCredits": actual, "billing": "RATE_ESTIMATE_PENDING_PLATFORM_RECONCILIATION",
                       "elapsedSeconds": round(time.monotonic() - started, 3), "modelCalls": 1, "networkCalls": 1,
                       "transportKind": "REAL_PROVIDER" if transport is https_once else "SYNTHETIC_TEST"}
            atomic(cache / "receipt.json", receipt)
            entry.update(status="SUCCESS", chargedOrReservedMicroCredits=actual)
            atomic(state_path, state)
            return raw, receipt, False
        except Exception as error:
            reason = str(error) if isinstance(error, ValueError) and str(error).startswith("DENOVO_MODEL_") else "DENOVO_MODEL_TRANSPORT_OR_STORAGE_FAILED"
            entry.update(status="FAILED_RESERVED", reasonCode=reason, elapsedSeconds=round(time.monotonic() - started, 3))
            # A malformed candidate can still be billed. Preserve validated usage
            # separately; it does not create a success receipt or release reserve.
            try:
                if (cache / "response.json").exists():
                    failed_raw = bounded(cache / "response.json", c["maxResponseBytes"])
                    failed_usage = response_usage(parse(failed_raw), c)
                    entry.update(responseSha256=digest(failed_raw), reportedUsage=failed_usage,
                        usageRateEstimatedMicroCredits=units_cost(c, failed_usage["prompt_tokens"], failed_usage["completion_tokens"]))
            except Exception:
                pass
            # Failures stop the shared pilot, including 429 even with unreadable
            # bodies. No implicit retry, new-size attempt or provider switch.
            state["stoppedReason"] = reason.removeprefix("DENOVO_MODEL_")
            atomic(state_path, state)
            raise ValueError(reason) from None
