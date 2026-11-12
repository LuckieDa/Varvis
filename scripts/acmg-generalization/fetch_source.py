"""Coordinator-only, bounded source capture with a shared byte ledger.

No retries or decompression. Each invocation reserves its complete receive limit
before opening the URL; failed and incomplete transfers are charged too.
"""
import argparse
import datetime
import hashlib
import http.client
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Do not let urllib consume an unmetered redirect body before following."""
    def http_error_302(self, request, response, code, message, headers):
        response.close()
        raise ValueError("Redirect refused; separately review and reserve the canonical URL (HTTP %s)" % code)

    http_error_301 = http_error_302
    http_error_303 = http_error_302
    http_error_307 = http_error_302
    http_error_308 = http_error_302


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def save_json(path, value):
    temporary = path.with_name(path.name + ".writing")
    with temporary.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    os.replace(temporary, path)


def capture(args):
    ledger_path = Path(args.ledger).resolve(strict=True)
    destination = Path(args.destination).resolve()
    # A task-owned ledger is also the physical resource boundary.
    if ledger_path.parent not in destination.parents:
        raise ValueError("Destination must be inside the task ledger directory")
    if destination.exists() or destination.with_suffix(destination.suffix + ".partial").exists():
        raise FileExistsError(destination)
    lock_path = ledger_path.with_name(ledger_path.name + ".lock")
    with lock_path.open("x", encoding="utf-8") as lock:
        lock.write(str(os.getpid()))
    received = 0
    entry = None
    uncertain_read = False
    reservation_committed = False
    ledger_write_ok = True
    try:
        ledger = json.loads(ledger_path.read_text(encoding="utf-8-sig"))
        available = ledger["maximumBytes"] - ledger["receivedBytes"] - ledger["reservedBytes"]
        limit = min(args.max_bytes, ledger["singleFileMaximumBytes"], available)
        if args.max_bytes <= 0 or limit != args.max_bytes:
            raise ValueError("Insufficient unreserved byte budget")
        entry = dict(id=str(uuid.uuid4()), url=args.url, version=args.version,
                     purpose=args.purpose, sizeBasis=args.size_basis,
                     destination=str(destination), verification="SHA-256 and source format review",
                     startedAt=utc_now(), reservedBytes=limit, receivedBytes=0,
                     status="RESERVED", tool="existing Python standard library; no installation")
        ledger["entries"].append(entry)
        ledger["reservedBytes"] += limit
        ledger_write_ok = False
        save_json(ledger_path, ledger)
        reservation_committed = True
        ledger_write_ok = True
        destination.parent.mkdir(parents=True, exist_ok=True)
        partial = destination.with_suffix(destination.suffix + ".partial")
        request = urllib.request.Request(args.url, headers={
            "User-Agent": "Varvis-Research-Source-Audit/1.0", "Accept-Encoding": "identity"})
        digest = hashlib.sha256()
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=45) as response:
            entry["effectiveUrl"] = response.url
            entry["contentType"] = response.headers.get("Content-Type")
            length = response.headers.get("Content-Length")
            expected = int(length) if length is not None else None
            entry["declaredContentLength"] = expected
            if expected is not None and (expected < 0 or expected > limit):
                raise ValueError("Declared resource size exceeds reserved hard limit")
            with partial.open("xb") as output:
                while received < limit:
                    uncertain_read = True
                    try:
                        chunk = response.read(min(65536, limit - received))
                    except http.client.IncompleteRead as error:
                        # urllib exposes already-received bytes on truncated chunked bodies.
                        received += len(error.partial)
                        # Nested causes can contain a final partial chunk that the
                        # outer exception omits. Keep the remainder reserved.
                        raise
                    received += len(chunk)
                    uncertain_read = False
                    if not chunk:
                        break
                    digest.update(chunk)
                    output.write(chunk)
                    entry["receivedBytes"] = received
                    # Crash recovery conservatively retains outstanding reservation.
                    ledger_write_ok = False
                    save_json(ledger_path, ledger)
                    ledger_write_ok = True
            if expected is not None and received != expected:
                raise ValueError("Truncated response")
            if expected is None and received == limit:
                raise ValueError("Unknown-size response reached hard limit; no further bytes read")
        entry["sha256"] = digest.hexdigest()
        os.rename(partial, destination)
        entry["status"] = "CAPTURED_PENDING_SOURCE_REVIEW"
    except BaseException as error:
        if entry is not None:
            entry["status"] = "CANCELLED" if isinstance(error, (KeyboardInterrupt, SystemExit)) else "FAILED"
            entry["error"] = str(error)
            if uncertain_read:
                entry["unsettledReason"] = "Transport interrupted inside read; retain unused reservation conservatively"
                entry["receivedBytesAreLowerBound"] = True
            if isinstance(error, urllib.error.HTTPError):
                error.close()
        raise
    finally:
        if entry is not None and reservation_committed and ledger_write_ok:
            entry["receivedBytes"] = received
            entry["finishedAt"] = utc_now()
            ledger["receivedBytes"] += received
            unused = max(0, entry["reservedBytes"] - received) if uncertain_read else 0
            ledger["reservedBytes"] -= entry["reservedBytes"] - unused
            entry["reservedBytes"] = unused
            ledger_write_ok = False
            save_json(ledger_path, ledger)
            ledger_write_ok = True
        # A failed ledger write leaves the prior reservation and this lock for
        # coordinator recovery; an in-memory entry alone is not a saved reserve.
        if ledger_write_ok:
            lock_path.unlink()
    print(json.dumps(entry, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("ledger", "destination", "url", "version", "purpose", "size-basis"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--max-bytes", required=True, type=int)
    capture(parser.parse_args())
