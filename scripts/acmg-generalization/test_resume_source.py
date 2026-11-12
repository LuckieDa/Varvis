"""Bounded continuation tests with mock HTTP responses only; never open a URL."""
import argparse
import copy
from email.message import Message
import hashlib
import http.client
import importlib.util
import inspect
import io
import json
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("resume_source", Path(__file__).with_name("resume_source.py"))
resume_source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resume_source)
URL = "https://example.org/public/source.zip"
WHOLE = b"prefix-rest"
PREFIX = b"prefix"


class Response(io.BytesIO):
    def __init__(self, payload=b"-rest", status=206, headers=None, failure=None):
        super().__init__(payload)
        self.status = status
        self.url = URL
        self.headers = Message()
        for key, value in (headers if headers is not None else [
                ("Content-Length", "5"), ("Content-Range", "bytes 6-10/11")]):
            self.headers.add_header(key, value)
        self.failure = failure
        self.read_requests = []

    def read(self, size=-1):
        self.read_requests.append(size)
        if self.failure:
            raise self.failure
        return super().read(size)


class ResumeSourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="varvis-resume-test-")
        self.root = Path(self.temporary.name).resolve()
        self.ledger_path = self.root / "download-ledger.json"
        self.prefix = self.root / "original.zip.partial"
        self.prefix.write_bytes(PREFIX)
        self.old = dict(id="original-failed", status="FAILED", error="Truncated response", reservedBytes=0,
                        receivedBytes=6, declaredContentLength=11, finishedAt="synthetic-finish", url=URL,
                        effectiveUrl=URL, version="synthetic-v1", destination=str(self.root / "original.zip"))
        self.initial = dict(owner="coordinator", maximumBytes=100, singleFileMaximumBytes=20,
                            reservedBytes=0, receivedBytes=8, entries=[self.old, dict(id="previous-failed",
                            status="FAILED", reservedBytes=0, receivedBytes=2)])
        self.write_ledger(self.initial)
        self.args = argparse.Namespace(task_root=str(self.root), ledger=str(self.ledger_path),
            failed_entry_id="original-failed", url=URL, source_version="synthetic-v1", prefix=str(self.prefix),
            prefix_size=6, prefix_sha256=hashlib.sha256(PREFIX).hexdigest(), total_size=11,
            whole_md5=hashlib.md5(WHOLE).hexdigest(), destination=str(self.root / "resumed.zip"), purpose="offline test")
        self.segment = self.root / "resumed.zip.range.partial"
        self.assembled = self.root / "resumed.zip.assembling"
        self.destination = self.root / "resumed.zip"
        self.lock = self.root / "download-ledger.json.lock"

    def tearDown(self):
        # Only the resolved temporary directory created by this test is removed.
        self.temporary.cleanup()

    def write_ledger(self, value):
        self.ledger_path.write_text(json.dumps(value), encoding="utf-8")

    def state(self):
        return json.loads(self.ledger_path.read_text(encoding="utf-8"))

    def invoke(self, response=None, before_open=None, open_error=None):
        response = response if response is not None else Response()
        with patch.object(resume_source.urllib.request, "build_opener") as factory:
            def open_once(request, timeout):
                state = self.state()
                self.assertEqual(5, state["reservedBytes"])
                self.assertEqual("RESERVED", state["entries"][-1]["status"])
                self.assertEqual("bytes=6-10", request.get_header("Range"))
                self.assertEqual("identity", request.get_header("Accept-encoding"))
                self.assertEqual(URL, request.full_url)
                self.assertTrue(self.lock.exists())
                if before_open:
                    before_open()
                if open_error:
                    raise open_error
                return response
            factory.return_value.open.side_effect = open_once
            try:
                return resume_source.resume(self.args)
            finally:
                self.assertLessEqual(factory.return_value.open.call_count, 1)

    def assert_preflight_refused(self, reason):
        before = self.ledger_path.read_bytes()
        with patch.object(resume_source.urllib.request, "build_opener") as factory:
            with self.assertRaisesRegex((ValueError, FileExistsError), reason):
                resume_source.resume(self.args)
            factory.assert_not_called()
        self.assertEqual(before, self.ledger_path.read_bytes())
        self.assertEqual(PREFIX, self.prefix.read_bytes())
        self.assertFalse(self.lock.exists())

    def test_success_reserves_before_open_and_preserves_failure_evidence(self):
        response = Response()
        entry = self.invoke(response)
        self.assertEqual(WHOLE, self.destination.read_bytes())
        self.assertEqual(PREFIX, self.prefix.read_bytes())
        self.assertEqual(b"-rest", self.segment.read_bytes())
        self.assertFalse(self.assembled.exists())
        self.assertEqual(self.old, self.state()["entries"][0])
        self.assertEqual(13, self.state()["receivedBytes"])  # Includes both original failures.
        self.assertEqual(0, self.state()["reservedBytes"])
        self.assertEqual(hashlib.sha256(WHOLE).hexdigest(), entry["sha256"])
        self.assertEqual(hashlib.md5(WHOLE).hexdigest(), entry["md5"])
        self.assertEqual(11, entry["sizeBytes"])
        self.assertEqual("CAPTURED_PENDING_SOURCE_REVIEW", entry["status"])
        self.assertEqual([5], response.read_requests)  # No extra EOF probe beyond reservation.
        self.assertFalse(self.lock.exists())

    def test_failed_entry_id_source_and_size_are_pinned(self):
        for field, value, reason in (("failed_entry_id", "missing", "FAILED_ENTRY_ID"),
                                     ("url", "https://example.org/changed", "SOURCE_MISMATCH"),
                                     ("source_version", "other-version", "SOURCE_MISMATCH"),
                                     ("total_size", 12, "SIZE_MISMATCH"),
                                     ("prefix_size", 5, "SIZE_MISMATCH")):
            with self.subTest(field=field):
                old = getattr(self.args, field)
                setattr(self.args, field, value)
                self.assert_preflight_refused(reason)
                setattr(self.args, field, old)

    def test_prefix_digest_cannot_be_replaced_by_entry_id_alone(self):
        self.args.prefix_sha256 = "0" * 64
        self.assert_preflight_refused("PREFIX_SHA_MISMATCH")

    def test_local_prefix_size_must_match_exact_bytes(self):
        self.prefix.write_bytes(PREFIX + b"x")
        with patch.object(resume_source.urllib.request, "build_opener") as factory:
            with self.assertRaisesRegex(ValueError, "LOCAL_FILE_SIZE_MISMATCH"):
                resume_source.resume(self.args)
            factory.assert_not_called()
        self.assertEqual(PREFIX + b"x", self.prefix.read_bytes())

    def test_only_original_normal_eof_failure_accepted(self):
        for field, value in (("status", "CANCELLED"), ("error", "timed out"), ("resumeOf", "another-entry")):
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.initial)
                ledger["entries"][0][field] = value
                self.write_ledger(ledger)
                self.assert_preflight_refused("SETTLED_ORIGINAL_EOF_FAILURE_REQUIRED")

    def test_global_and_entry_unsettled_state_prevents_network(self):
        cases = [dict(reservedBytes=1), dict(receivedBytesAreLowerBound=True), dict(unsettledReason="unknown read")]
        for change in cases:
            with self.subTest(change=change):
                ledger = copy.deepcopy(self.initial)
                ledger["entries"][1].update(change)
                self.write_ledger(ledger)
                self.assert_preflight_refused("UNSETTLED_RESERVATION")
        ledger = copy.deepcopy(self.initial)
        ledger["reservedBytes"] = 1
        self.write_ledger(ledger)
        self.assert_preflight_refused("UNSETTLED_RESERVATION")

    def test_whole_file_cap_and_cumulative_failure_bytes_apply(self):
        for changes, reason in ((dict(singleFileMaximumBytes=10), "BYTE_BUDGET"),
                                (dict(maximumBytes=12), "BYTE_BUDGET"),
                                (dict(maximumBytes=5_000_000_001), "HARD_CAP"),
                                (dict(singleFileMaximumBytes=1_000_000_001), "HARD_CAP"),
                                (dict(receivedBytes=6), "ACCOUNTING_MISMATCH")):
            with self.subTest(changes=changes):
                self.write_ledger(dict(self.initial, **changes))
                self.assert_preflight_refused(reason)

    def test_paths_stay_inside_task_and_do_not_alias_ledger_or_original_destination(self):
        for destination, reason in ((self.root.parent / "outside.zip", "TASK_ROOT"),
                                     (self.root / "original.zip", "NEW_DESTINATION_MISMATCH"),
                                     (self.root / "download-ledger.json.writing", "PATH_COLLISION")):
            with self.subTest(destination=destination):
                self.args.destination = str(destination)
                self.assert_preflight_refused(reason)

    def test_existing_outputs_never_reused_or_overwritten(self):
        for path in (self.destination, self.segment, self.assembled):
            with self.subTest(path=path):
                path.write_bytes(b"preserve")
                self.assert_preflight_refused("NEW_OUTPUTS_REQUIRED")
                self.assertEqual(b"preserve", path.read_bytes())
                path.unlink()  # This test created the exact file above.

    def test_existing_lock_is_never_removed(self):
        self.lock.write_text("other-coordinator")
        with patch.object(resume_source.urllib.request, "build_opener") as factory:
            with self.assertRaises(FileExistsError):
                resume_source.resume(self.args)
            factory.assert_not_called()
        self.assertEqual("other-coordinator", self.lock.read_text())

    def test_200_and_malformed_headers_refused_without_body_read(self):
        cases = [(200, [("Content-Length", "5"), ("Content-Range", "bytes 6-10/11")], "EXACT_206"),
                 (206, [("Content-Length", "4"), ("Content-Range", "bytes 6-10/11")], "CONTENT_LENGTH"),
                 (206, [("Content-Length", "5"), ("Content-Range", "bytes 5-9/11")], "CONTENT_RANGE"),
                 (206, [("Content-Length", "5"), ("Content-Range", "bytes 6-10/12")], "CONTENT_RANGE"),
                 (206, [("Content-Length", "5"), ("Content-Range", "bytes 6-10/*")], "CONTENT_RANGE"),
                 (206, [("Content-Range", "bytes 6-10/11")], "HEADER_MISSING"),
                 (206, [("Content-Length", "5"), ("Content-Length", "5"), ("Content-Range", "bytes 6-10/11")], "HEADER_MISSING"),
                 (206, [("Content-Length", "5"), ("Content-Range", "bytes 6-10/11"), ("Content-Encoding", "gzip")], "CONTENT_ENCODING"),
                 (206, [("Content-Length", "5"), ("Content-Range", "bytes 6-10/11"), ("Transfer-Encoding", "chunked")], "TRANSFER_ENCODING")]
        for status, headers, reason in cases:
            with self.subTest(status=status, headers=headers):
                response = Response(status=status, headers=headers)
                with self.assertRaisesRegex(ValueError, reason):
                    self.invoke(response)
                self.assertEqual([], response.read_requests)
                self.assertTrue(response.closed)
                self.assertFalse(self.segment.exists())
                self.assertEqual(8, self.state()["receivedBytes"])
                self.assertEqual(0, self.state()["reservedBytes"])

    def test_changed_effective_url_never_read(self):
        response = Response()
        response.url = "https://example.org/elsewhere"
        with self.assertRaisesRegex(ValueError, "EFFECTIVE_URL_CHANGED"):
            self.invoke(response)
        self.assertEqual([], response.read_requests)

    def test_same_redirect_handler_closes_all_redirects_without_body(self):
        self.assertIs(resume_source.NoRedirect, resume_source._fetch.NoRedirect)
        for status in (301, 302, 303, 307, 308):
            response = Response(b"large-unmetered-body")
            with self.assertRaisesRegex(ValueError, "Redirect refused"):
                getattr(resume_source.NoRedirect(), "http_error_" + str(status))(
                    None, response, status, "redirect", {"Location": "https://example.org/other"})
            self.assertTrue(response.closed)
            self.assertEqual([], response.read_requests)

    def test_normal_eof_charges_exact_fragment_and_preserves_it(self):
        with self.assertRaisesRegex(ValueError, "Truncated response"):
            self.invoke(Response(b"-r"))
        self.assertEqual(10, self.state()["receivedBytes"])
        self.assertEqual(0, self.state()["reservedBytes"])
        self.assertEqual(b"-r", self.segment.read_bytes())
        self.assertEqual(PREFIX, self.prefix.read_bytes())
        self.assertFalse(self.destination.exists())
        self.assertEqual(self.old, self.state()["entries"][0])

    def test_incomplete_read_preserves_exposed_bytes_and_unknown_reservation(self):
        error = http.client.IncompleteRead(b"-r", 3)
        error.__cause__ = http.client.IncompleteRead(b"e", 2)
        with self.assertRaises(http.client.IncompleteRead):
            self.invoke(Response(failure=error))
        self.assertEqual(10, self.state()["receivedBytes"])
        self.assertEqual(3, self.state()["reservedBytes"])
        self.assertTrue(self.state()["entries"][-1]["receivedBytesAreLowerBound"])
        self.assertEqual(b"-r", self.segment.read_bytes())
        self.assertFalse(self.lock.exists())

    def test_timeout_and_cancellation_inside_read_retain_reservation(self):
        for error, status in ((TimeoutError("read stalled"), "FAILED"), (KeyboardInterrupt(), "CANCELLED")):
            with self.subTest(status=status):
                self.write_ledger(copy.deepcopy(self.initial))
                if self.segment.exists():
                    self.segment.unlink()  # Exact fragment created by the prior subtest.
                with self.assertRaises(type(error)):
                    self.invoke(Response(failure=error))
                self.assertEqual(5, self.state()["reservedBytes"])
                self.assertEqual(8, self.state()["receivedBytes"])
                self.assertEqual(status, self.state()["entries"][-1]["status"])

    def test_connection_failure_before_read_settles_zero_without_retry(self):
        with self.assertRaises(TimeoutError):
            self.invoke(open_error=TimeoutError("connect stalled"))
        self.assertEqual(8, self.state()["receivedBytes"])
        self.assertEqual(0, self.state()["reservedBytes"])
        self.assertFalse(self.segment.exists())

    def test_wrong_whole_md5_preserves_all_new_evidence_without_promotion(self):
        self.args.whole_md5 = "0" * 32
        with self.assertRaisesRegex(ValueError, "WHOLE_SOURCE_MD5_MISMATCH"):
            self.invoke()
        self.assertEqual(13, self.state()["receivedBytes"])
        self.assertEqual(0, self.state()["reservedBytes"])
        self.assertEqual(WHOLE, self.assembled.read_bytes())
        self.assertEqual(b"-rest", self.segment.read_bytes())
        self.assertFalse(self.destination.exists())

    def test_prefix_is_rechecked_during_assembly(self):
        assemble = resume_source._assemble
        def change_prefix(*args):
            self.prefix.write_bytes(b"prEFix")
            return assemble(*args)
        with patch.object(resume_source, "_assemble", side_effect=change_prefix):
            with self.assertRaisesRegex(ValueError, "ASSEMBLY_INPUT_CHANGED"):
                self.invoke()
        self.assertFalse(self.destination.exists())
        self.assertEqual(13, self.state()["receivedBytes"])

    def test_atomic_reservation_write_failure_never_opens_network_and_retains_lock(self):
        with patch.object(resume_source, "save_json", side_effect=OSError("disk write failure")), \
                patch.object(resume_source.urllib.request, "build_opener") as factory:
            with self.assertRaises(OSError):
                resume_source.resume(self.args)
            factory.assert_not_called()
        self.assertTrue(self.lock.exists())
        self.assertEqual(self.initial, self.state())

    def test_cancel_before_first_reservation_save_never_settles_uncommitted_bytes(self):
        lines, first = inspect.getsourcelines(resume_source.resume)
        for token in ('ledger["entries"].append(entry)', 'ledger["reservedBytes"] += limit', 'ledger_write_ok = False'):
            with self.subTest(before=token):
                target = first + next(i for i, line in enumerate(lines) if line.strip() == token)
                def cancel(frame, event, _):
                    if event == "line" and frame.f_code is resume_source.resume.__code__ and frame.f_lineno == target:
                        raise KeyboardInterrupt("synthetic pre-reservation cancellation")
                    return cancel
                with patch.object(resume_source.urllib.request, "build_opener") as factory:
                    try:
                        sys.settrace(cancel)
                        with self.assertRaises(KeyboardInterrupt):
                            resume_source.resume(self.args)
                    finally:
                        sys.settrace(None)
                    factory.assert_not_called()
                self.assertEqual(self.initial, self.state())
                self.assertFalse(self.lock.exists())

    def test_cancel_after_reservation_save_before_acknowledgement_retains_lock_and_budget(self):
        lines, first = inspect.getsourcelines(resume_source.resume)
        target = first + next(i for i, line in enumerate(lines) if line.strip() == "reservation_committed = True")
        def cancel(frame, event, _):
            if event == "line" and frame.f_code is resume_source.resume.__code__ and frame.f_lineno == target:
                raise KeyboardInterrupt("synthetic reservation acknowledgement cancellation")
            return cancel
        with patch.object(resume_source.urllib.request, "build_opener") as factory:
            try:
                sys.settrace(cancel)
                with self.assertRaises(KeyboardInterrupt):
                    resume_source.resume(self.args)
            finally:
                sys.settrace(None)
            factory.assert_not_called()
        self.assertEqual(5, self.state()["reservedBytes"])
        self.assertEqual(8, self.state()["receivedBytes"])
        self.assertEqual("RESERVED", self.state()["entries"][-1]["status"])
        self.assertTrue(self.lock.exists())

    def test_progress_write_failure_retains_full_reserved_budget_and_lock(self):
        save = resume_source.save_json
        calls = []
        def fail_progress(path, ledger):
            calls.append(1)
            if len(calls) == 2:
                raise OSError("progress write failure")
            return save(path, ledger)
        with patch.object(resume_source, "save_json", side_effect=fail_progress), patch.object(resume_source, "CHUNK_SIZE", 2):
            with self.assertRaises(OSError):
                self.invoke()
        self.assertEqual(5, self.state()["reservedBytes"])
        self.assertEqual(b"-r", self.segment.read_bytes())
        self.assertTrue(self.lock.exists())
        self.assertFalse(self.destination.exists())

    def test_final_ledger_write_failure_leaves_verified_file_and_recovery_lock(self):
        save = resume_source.save_json
        def fail_final(path, ledger):
            if ledger["entries"][-1].get("status") == "CAPTURED_PENDING_SOURCE_REVIEW":
                raise OSError("final write failure")
            return save(path, ledger)
        with patch.object(resume_source, "save_json", side_effect=fail_final):
            with self.assertRaises(OSError):
                self.invoke()
        self.assertEqual(WHOLE, self.destination.read_bytes())
        self.assertEqual(5, self.state()["reservedBytes"])
        self.assertTrue(self.lock.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
