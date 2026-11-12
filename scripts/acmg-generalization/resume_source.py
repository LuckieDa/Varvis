"""Coordinator-only, single-attempt HTTPS Range continuation of a settled failure.

Preserves the old prefix and ledger entry. New network bytes share fetch_source's
exclusive ledger lock and reservation budget. No retries, redirects or installs.
"""
import argparse
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid

_spec = importlib.util.spec_from_file_location("varvis_existing_fetch_source", Path(__file__).with_name("fetch_source.py"))
_fetch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_fetch)
NoRedirect, save_json, utc_now = _fetch.NoRedirect, _fetch.save_json, _fetch.utc_now
SINGLE_FILE_MAXIMUM = 1_000_000_000
CUMULATIVE_MAXIMUM = 5_000_000_000
CHUNK_SIZE = 65536


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def integer(value, minimum=0):
    return type(value) is int and minimum <= value


def digest_matches(pattern, value):
    return type(value) is str and re.fullmatch(pattern, value) is not None


def _pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, "LEDGER_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def _file_identity(path, expected_size):
    digest, md5, size = hashlib.sha256(), hashlib.md5(), 0
    with path.open("rb") as stream:
        while size < expected_size:
            chunk = stream.read(min(CHUNK_SIZE, expected_size - size))
            if not chunk:
                break
            size += len(chunk)
            digest.update(chunk)
            md5.update(chunk)
        require(size == expected_size and stream.read(1) == b"", "LOCAL_FILE_SIZE_MISMATCH")
    return digest.hexdigest(), md5.hexdigest()


def _header(response, name, required=True):
    # HTTPMessage.get_all exposes duplicate headers instead of silently choosing one.
    values = response.headers.get_all(name, [])
    require(len(values) == 1 or (not required and len(values) == 0), "HTTP_HEADER_MISSING_OR_DUPLICATE:" + name)
    return values[0].strip() if values else None


def _validate_response(response, url, start, total):
    require(response.status == 206, "HTTP_EXACT_206_REQUIRED")
    require(response.url == url, "HTTP_EFFECTIVE_URL_CHANGED")
    length = _header(response, "Content-Length")
    require(length == str(total - start), "HTTP_CONTENT_LENGTH_MISMATCH")
    require(_header(response, "Content-Range") == "bytes %d-%d/%d" % (start, total - 1, total),
            "HTTP_CONTENT_RANGE_MISMATCH")
    encoding = _header(response, "Content-Encoding", required=False)
    require(encoding is None or encoding.lower() == "identity", "HTTP_CONTENT_ENCODING_UNSUPPORTED")
    require(_header(response, "Transfer-Encoding", required=False) is None, "HTTP_TRANSFER_ENCODING_UNSUPPORTED")


def _validate_ledger(ledger, args, prefix, destination):
    require(type(ledger) is dict and type(ledger.get("entries")) is list, "LEDGER_SCHEMA_INVALID")
    maximum, single = ledger.get("maximumBytes"), ledger.get("singleFileMaximumBytes")
    require(integer(maximum, 1) and maximum <= CUMULATIVE_MAXIMUM
            and integer(single, 1) and single <= SINGLE_FILE_MAXIMUM, "LEDGER_HARD_CAP_INVALID")
    require(integer(ledger.get("receivedBytes")) and integer(ledger.get("reservedBytes"))
            and ledger["reservedBytes"] == 0, "LEDGER_UNSETTLED_RESERVATION")
    ids, received_sum = set(), 0
    for entry in ledger["entries"]:
        require(type(entry) is dict and type(entry.get("id")) is str and entry["id"] not in ids
                and integer(entry.get("receivedBytes")) and integer(entry.get("reservedBytes")), "LEDGER_ENTRY_INVALID")
        ids.add(entry["id"])
        require(entry["reservedBytes"] == 0 and entry.get("status") != "RESERVED"
                and not entry.get("receivedBytesAreLowerBound") and not entry.get("unsettledReason"), "LEDGER_UNSETTLED_RESERVATION")
        received_sum += entry["receivedBytes"]
    require(received_sum == ledger["receivedBytes"] and received_sum <= maximum, "LEDGER_RECEIVE_ACCOUNTING_MISMATCH")
    matches = [entry for entry in ledger["entries"] if entry["id"] == args.failed_entry_id]
    require(len(matches) == 1, "FAILED_ENTRY_ID_NOT_UNIQUE")
    failed = matches[0]
    require(failed.get("status") == "FAILED" and failed.get("error") == "Truncated response"
            and failed.get("finishedAt") and not failed.get("resumeOf"), "SETTLED_ORIGINAL_EOF_FAILURE_REQUIRED")
    require(failed.get("url") == args.url and failed.get("effectiveUrl") == args.url
            and failed.get("version") == args.source_version, "FAILED_ENTRY_SOURCE_MISMATCH")
    require(failed.get("declaredContentLength") == args.total_size and type(failed.get("declaredContentLength")) is int
            and failed["receivedBytes"] == args.prefix_size, "FAILED_ENTRY_SIZE_MISMATCH")
    old_destination = Path(failed["destination"]).resolve()
    require(prefix == old_destination.with_suffix(old_destination.suffix + ".partial")
            and destination != old_destination, "FAILED_PREFIX_OR_NEW_DESTINATION_MISMATCH")
    require(args.total_size <= single and args.total_size - args.prefix_size <= maximum - received_sum,
            "INSUFFICIENT_UNRESERVED_BYTE_BUDGET")
    return failed


def _assemble(prefix, segment, assembled, destination, args, segment_sha):
    combined_sha, combined_md5 = hashlib.sha256(), hashlib.md5()
    with assembled.open("xb") as output:
        for path, length, expected_sha in ((prefix, args.prefix_size, args.prefix_sha256),
                                            (segment, args.total_size - args.prefix_size, segment_sha)):
            part_sha, copied = hashlib.sha256(), 0
            with path.open("rb") as source:
                while copied < length:
                    chunk = source.read(min(CHUNK_SIZE, length - copied))
                    require(bool(chunk), "ASSEMBLY_INPUT_TRUNCATED")
                    copied += len(chunk)
                    part_sha.update(chunk)
                    combined_sha.update(chunk)
                    combined_md5.update(chunk)
                    output.write(chunk)
                require(source.read(1) == b"" and part_sha.hexdigest() == expected_sha, "ASSEMBLY_INPUT_CHANGED")
        output.flush()
        os.fsync(output.fileno())
    require(combined_md5.hexdigest() == args.whole_md5, "WHOLE_SOURCE_MD5_MISMATCH")
    # Verify actual completed bytes on disk, including size, before final promotion.
    actual_sha, actual_md5 = _file_identity(assembled, args.total_size)
    require(actual_sha == combined_sha.hexdigest() and actual_md5 == args.whole_md5, "ASSEMBLED_FILE_VERIFICATION_FAILED")
    require(not destination.exists(), "FINAL_DESTINATION_EXISTS")
    os.rename(assembled, destination)
    return actual_sha


def resume(args):
    task_root = Path(args.task_root).resolve(strict=True)
    ledger_path = Path(args.ledger).resolve(strict=True)
    prefix = Path(args.prefix).resolve(strict=True)
    destination = Path(args.destination).resolve()
    segment = destination.with_suffix(destination.suffix + ".range.partial")
    assembled = destination.with_suffix(destination.suffix + ".assembling")
    lock_path = ledger_path.with_name(ledger_path.name + ".lock")
    protected = {ledger_path, lock_path, ledger_path.with_name(ledger_path.name + ".writing")}
    require(task_root.is_dir() and ledger_path.parent == task_root
            and all(task_root in path.parents for path in (prefix, destination, segment, assembled)), "TASK_ROOT_BOUNDARY_MISMATCH")
    require(not protected.intersection((prefix, destination, segment, assembled))
            and prefix not in (destination, segment, assembled), "INPUT_OUTPUT_PATH_COLLISION")
    require(integer(args.prefix_size, 1) and integer(args.total_size, 1)
            and args.prefix_size < args.total_size <= SINGLE_FILE_MAXIMUM, "PINNED_SIZE_INVALID")
    require(digest_matches(r"[0-9a-f]{64}", args.prefix_sha256)
            and digest_matches(r"[0-9a-f]{32}", args.whole_md5), "PINNED_DIGEST_INVALID")
    parsed_url = urllib.parse.urlsplit(args.url)
    require(parsed_url.scheme == "https" and parsed_url.hostname and not parsed_url.username
            and not parsed_url.password and not parsed_url.fragment, "PINNED_HTTPS_URL_REQUIRED")
    # Same exclusive filename and create-only convention as fetch_source.capture.
    with lock_path.open("x", encoding="utf-8") as lock:
        lock.write(str(os.getpid()))
    entry, ledger = None, None
    received, uncertain_read, ledger_write_ok, reservation_committed = 0, False, True, False
    try:
        require(all(not path.exists() for path in (destination, segment, assembled)), "NEW_OUTPUTS_REQUIRED")
        with ledger_path.open("rb") as stream:
            ledger_bytes = stream.read(4_000_001)
        require(len(ledger_bytes) <= 4_000_000, "LEDGER_SIZE_LIMIT")
        ledger = json.loads(ledger_bytes.decode("utf-8-sig"), object_pairs_hook=_pairs)
        failed = _validate_ledger(ledger, args, prefix, destination)
        require(_file_identity(prefix, args.prefix_size)[0] == args.prefix_sha256, "PREFIX_SHA_MISMATCH")
        limit = args.total_size - args.prefix_size
        entry = dict(id=str(uuid.uuid4()), resumeOf=failed["id"], url=args.url, version=args.source_version,
                     purpose=args.purpose, destination=str(destination), startedAt=utc_now(), status="RESERVED",
                     reservedBytes=limit, receivedBytes=0, declaredWholeSize=args.total_size, expectedWholeMd5=args.whole_md5,
                     rangeStart=args.prefix_size, rangeEnd=args.total_size - 1, prefix=str(prefix),
                     prefixSize=args.prefix_size, prefixSha256=args.prefix_sha256, rangePartial=str(segment),
                     assemblyTemporary=str(assembled),
                     failedEntrySha256=hashlib.sha256(json.dumps(failed, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest(),
                     verification="Pinned prefix SHA256; exact HTTP range; whole source MD5; on-disk size and SHA256",
                     tool="existing Python standard library; single bounded Range continuation; no installation")
        ledger["entries"].append(entry)
        ledger["reservedBytes"] += limit
        ledger_write_ok = False
        save_json(ledger_path, ledger)
        reservation_committed = True
        ledger_write_ok = True
        destination.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(args.url, headers={"User-Agent": "Varvis-Research-Source-Audit/1.0",
            "Accept-Encoding": "identity", "Range": "bytes=%d-%d" % (args.prefix_size, args.total_size - 1)})
        digest = hashlib.sha256()
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=45) as response:
            entry["effectiveUrl"] = response.url
            entry["httpStatus"] = response.status
            _validate_response(response, args.url, args.prefix_size, args.total_size)
            entry["declaredContentLength"] = limit
            entry["contentRange"] = _header(response, "Content-Range")
            with segment.open("xb") as output:
                while received < limit:
                    read_size = min(CHUNK_SIZE, limit - received)
                    uncertain_read = True
                    try:
                        chunk = response.read(read_size)
                    except http.client.IncompleteRead as error:
                        # Preserve exposed partial bytes; nested causes can hide more.
                        received += len(error.partial)
                        digest.update(error.partial)
                        output.write(error.partial)
                        raise
                    received += len(chunk)
                    uncertain_read = False
                    require(type(chunk) is bytes and len(chunk) <= read_size, "HTTP_READ_CONTRACT_VIOLATION")
                    if not chunk:
                        break
                    digest.update(chunk)
                    output.write(chunk)
                    entry["receivedBytes"] = received
                    ledger_write_ok = False
                    save_json(ledger_path, ledger)
                    ledger_write_ok = True
                output.flush()
                os.fsync(output.fileno())
            require(received == limit, "Truncated response")
        entry["rangeSha256"] = digest.hexdigest()
        entry["sha256"] = _assemble(prefix, segment, assembled, destination, args, digest.hexdigest())
        entry["md5"] = args.whole_md5
        entry["sizeBytes"] = args.total_size
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
        # A failed atomic ledger write leaves the lock and prior full reservation
        # intact for coordinator recovery. Never clear an uncertain ledger state.
        if entry is not None and reservation_committed and ledger_write_ok:
            entry["receivedBytes"] = received
            entry["finishedAt"] = utc_now()
            unused = max(0, entry["reservedBytes"] - received) if uncertain_read else 0
            ledger["receivedBytes"] += received
            ledger["reservedBytes"] -= entry["reservedBytes"] - unused
            entry["reservedBytes"] = unused
            ledger_write_ok = False
            save_json(ledger_path, ledger)
            ledger_write_ok = True
        if ledger_write_ok:
            lock_path.unlink()
    return entry


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("task-root", "ledger", "failed-entry-id", "url", "source-version", "prefix", "prefix-sha256", "whole-md5", "destination", "purpose"):
        parser.add_argument("--" + name, required=True)
    for name in ("prefix-size", "total-size"):
        parser.add_argument("--" + name, required=True, type=int)
    print(json.dumps(resume(parser.parse_args()), ensure_ascii=False))
