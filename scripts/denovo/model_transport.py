"""Single-flight, budget-reserved HTTPS transport for an explicit public-only pilot.

No SDK installation, automatic retry, redirects, provider fallback or paid calls
on cache reads. A failed/unknown charge keeps its reservation. Raw API responses
are private artifacts, never logs. The endpoint must be configured explicitly.
"""
from model_call_limits import effective_call_limit
import contextlib
from decimal import Decimal, ROUND_CEILING
import http.client
import json
import multiprocessing
import os
from pathlib import Path
import re
import time
from urllib.parse import urlsplit

from model_literature_contract import canonical, digest, parse, check_shape, request_matches_config

VERSION = "denovo-model-transport-1.1.0"


def require(test, reason):
    if not test:
        raise ValueError("DENOVO_MODEL_" + reason)


def bounded(path, maximum):
    with Path(path).open("rb") as stream:
        raw = stream.read(maximum + 1)
    require(0 < len(raw) <= maximum, "FILE_SIZE_LIMIT")
    return raw


def config(path):
    c = parse(bounded(path, 16384))
    require(c.get("schemaVersion") == "denovo-model-config-v1", "CONFIG_SCHEMA")
    require(c.get("enabled") is True, "DISABLED")
    require(c.get("pricingConfirmed") is True, "PRICING_NOT_CONFIRMED")
    require(c.get("protocol") == "openai-chat-completions-json-schema", "PROTOCOL_UNSUPPORTED")
    url = urlsplit(c.get("endpoint", ""))
    require(url.scheme == "https" and url.hostname and not url.username and not url.password
            and not url.query and not url.fragment and url.path.endswith("/chat/completions"), "ENDPOINT_INVALID")
    for field in ("model", "expectedResponseModel"):
        require(isinstance(c.get(field), str) and (re.fullmatch(r"gemini-[a-zA-Z0-9._-]{1,100}", c[field])
                or c[field] == 'gpt-6.1-sol'), "MODEL_MAPPING_REQUIRED")
    require(c['model'].startswith('gemini-') == c['expectedResponseModel'].startswith('gemini-'), 'MODEL_MAPPING_REQUIRED')
    policy = c.get('cumulativeLimitPolicy', 'ENFORCED')
    require(policy in ('ENFORCED', 'TRACK_ONLY'), 'CUMULATIVE_POLICY_INVALID')
    if policy == 'TRACK_ONLY':
        require(c.get('maxCalls') is None and c.get('budgetRmb') is None, 'TRACK_ONLY_LIMITS_MUST_BE_NULL')
    else:
        require(type(c.get('maxCalls')) is int and 1 <= c['maxCalls'] <= 100, 'CONFIG_LIMIT_INVALID')
        value = Decimal(str(c.get('budgetRmb', '0')))
        require(value.is_finite() and 0 < value <= 20, 'AUTHORIZED_BUDGET_EXCEEDED')
    for field, low, high in (("maxOutputTokens", 256, 16384),
                             ("timeoutSeconds", 1, 120), ("maxRequestBytes", 1024, 262144),
                             ("maxResponseBytes", 1024, 1048576)):
        require(type(c.get(field)) is int and low <= c[field] <= high, "CONFIG_LIMIT_INVALID")
    billed = c.get("maxBilledOutputTokens", c["maxOutputTokens"])
    require(type(billed) is int and c["maxOutputTokens"] <= billed <= 32768, "BILLED_OUTPUT_LIMIT_INVALID")
    for field in ("inputCreditsPerMillion", "outputCreditsPerMillion", "creditsPerRmb"):
        v = Decimal(str(c.get(field, "0")))
        require(v.is_finite() and v > 0, "PRICING_INVALID")
    require(Decimal(str(c["creditsPerRmb"])) == 3, "AUTHORIZED_BUDGET_EXCEEDED")
    require(re.fullmatch(r"[A-Z_][A-Z0-9_]*", c.get("apiKeyEnvironment", "")), "KEY_CONFIGURATION_INVALID")
    require("_identityTimeoutSeconds" not in c, "CONFIG_LIMIT_INVALID")
    if "requestTimeoutSeconds" in c:
        timeout = c.pop("requestTimeoutSeconds")
        require(type(timeout) is int and 1 <= timeout <= 180, "CONFIG_LIMIT_INVALID")
        # Keep the legacy value in existing cache and budget identities.
        c["_identityTimeoutSeconds"] = c["timeoutSeconds"]
        c["timeoutSeconds"] = timeout
    return c


def credentials(c, config_path):
    key = os.environ.get(c["apiKeyEnvironment"], "")
    if not key and c.get("apiKeyFile"):
        path = (Path(config_path).resolve().parent / c["apiKeyFile"]).resolve()
        # Keys stay in the same private configuration directory, never selected
        # through a source document or fetched from a remote URL.
        require(path.parent == Path(config_path).resolve().parent, "KEY_PATH_OUTSIDE_PRIVATE_CONFIG")
        key = bounded(path, 8192).decode("utf-8-sig").strip()
    require(0 < len(key) <= 8192 and not re.search(r"[\s\x00-\x1f\x7f]", key), "CREDENTIALS_MISSING_OR_INVALID")
    return key


def public_config(c):
    result = {k: c[k] for k in ("schemaVersion", "endpoint", "model", "expectedResponseModel", "protocol",
            "inputCreditsPerMillion", "outputCreditsPerMillion", "creditsPerRmb", "budgetRmb", "maxCalls",
            "maxOutputTokens", "timeoutSeconds", "maxRequestBytes", "maxResponseBytes")}
    if "_identityTimeoutSeconds" in c:
        result["timeoutSeconds"] = c["_identityTimeoutSeconds"]
    if "maxBilledOutputTokens" in c:
        result["maxBilledOutputTokens"] = c["maxBilledOutputTokens"]
    if 'cumulativeLimitPolicy' in c:
        result['cumulativeLimitPolicy'] = c['cumulativeLimitPolicy']
    return result


def money_limit(c):
    """Track all usage/reservations even when the user removed cumulative caps."""
    if c.get('cumulativeLimitPolicy') == 'TRACK_ONLY':
        require(c.get('budgetRmb') is None and c.get('maxCalls') is None, 'TRACK_ONLY_LIMITS_MUST_BE_NULL')
        return float('inf')
    return int(Decimal(str(c['budgetRmb'])) * Decimal(str(c['creditsPerRmb'])) * 1000000)


def units_cost(c, inputs, outputs):
    # Integer micro-credits: avoids floating point rounding under reservation.
    return int((Decimal(str(c["inputCreditsPerMillion"])) * inputs
                + Decimal(str(c["outputCreditsPerMillion"])) * outputs).to_integral_value(rounding=ROUND_CEILING))


def _http_child(endpoint, key, body, timeout, limit, pipe):
    connection = None
    try:
        url = urlsplit(endpoint)
        connection = http.client.HTTPSConnection(url.hostname, url.port or 443, timeout=timeout)
        connection.request("POST", url.path, body=body, headers={"Authorization": "Bearer " + key,
                           "Content-Type": "application/json", "Accept": "application/json"})
        response = connection.getresponse()
        # Do not read error bodies (which may echo credentials). No redirects.
        if response.status != 200:
            pipe.send((response.status, b"", response.getheader("Retry-After")))
            return
        raw = response.read(limit + 1)
        pipe.send((200, raw, None))
    except Exception:
        pipe.send((0, b"", None))  # never expose a credential-bearing exception
    finally:
        if connection is not None:
            connection.close()
        pipe.close()


def https_once(c, key, body):
    return bounded_transport(c, key, body, _http_child)


def bounded_transport(c, key, body, worker):
    # The parent enforces TOTAL time, including DNS/TLS and a trickling body.
    ctx = multiprocessing.get_context("spawn")
    receiver, sender = ctx.Pipe(duplex=False)
    child = ctx.Process(target=worker, args=(c["endpoint"], key, body, c["timeoutSeconds"], c["maxResponseBytes"], sender))
    child.start(); sender.close()
    try:
        if not receiver.poll(c["timeoutSeconds"]):
            raise ValueError("DENOVO_MODEL_TIMEOUT")
        try:
            status, raw, retry_after = receiver.recv()
        except EOFError as error:
            raise ValueError("DENOVO_MODEL_TRANSPORT_FAILED") from error
        if status == 429:
            raise ValueError("DENOVO_MODEL_RATE_LIMITED")
        require(status == 200, "HTTP_" + str(status))
        require(0 < len(raw) <= c["maxResponseBytes"], "RESPONSE_SIZE_LIMIT")
        return raw
    finally:
        receiver.close()
        child.join(0.1)
        if child.is_alive():
            child.terminate(); child.join(3)
        if child.is_alive():
            child.kill(); child.join(3)
        require(not child.is_alive(), "TRANSPORT_PROCESS_NOT_EXITED")


def response_usage(response, c):
    require(isinstance(response, dict) and response.get("model") == c["expectedResponseModel"], "RESPONSE_MODEL_MISMATCH")
    usage = response.get("usage", {})
    for field in ("prompt_tokens", "completion_tokens", "total_tokens"):
        require(type(usage.get(field)) is int and usage[field] >= 0, "USAGE_REQUIRED")
    require(usage["prompt_tokens"] > 0 and usage["total_tokens"] == usage["prompt_tokens"] + usage["completion_tokens"], "USAGE_INCONSISTENT")
    details = usage.get("completion_tokens_details") or {}
    thoughts = details.get("reasoning_tokens", 0)
    require(type(thoughts) is int and 0 <= thoughts <= usage["completion_tokens"], "THINKING_USAGE_INVALID")
    return usage


def decode_response(raw, c):
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
    candidate = parse(message["content"])
    check_shape(candidate)
    # OpenAI-compatible completion_tokens includes reasoning; do not double count.
    return candidate, usage


@contextlib.contextmanager
def exclusive(directory):
    directory.mkdir(parents=True, exist_ok=True)
    lock = directory / "pilot.lock"
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as error:
        raise ValueError("DENOVO_MODEL_PILOT_BUSY_OR_INTERRUPTED") from error
    try:
        os.write(descriptor, str(os.getpid()).encode()); os.fsync(descriptor)
        yield
    finally:
        os.close(descriptor)
        lock.unlink()


def atomic(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("wb") as stream:
        stream.write(canonical(value)); stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, path)


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
            decode_response(raw, c)
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
            unused, usage = decode_response(raw, c)
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
