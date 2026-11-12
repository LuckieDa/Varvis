"""Offline regression of byte-budget failure paths; no resource requests."""
import argparse
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

spec = importlib.util.spec_from_file_location("fetch_source", Path(__file__).with_name("fetch_source.py"))
fetch_source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch_source)


class FakeResponse(io.BytesIO):
    def __init__(self, payload=b"hello", length="5", failure=None):
        super().__init__(payload)
        self.headers = {"Content-Type": "text/plain"}
        if length is not None:
            self.headers["Content-Length"] = length
        self.url = "https://example.org/source"
        self.failure = failure
        self.read_requests = []

    def read(self, size=-1):
        self.read_requests.append(size)
        if self.failure:
            raise self.failure
        return super().read(size)


class CaptureBudgetTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="varvis-download-test-")
        self.root = Path(self.temporary.name).resolve()
        self.ledger = self.root / "download-ledger.json"
        self.ledger.write_text(json.dumps(dict(maximumBytes=100, singleFileMaximumBytes=10,
                                               reservedBytes=0, receivedBytes=0, entries=[])))
        self.args = argparse.Namespace(ledger=str(self.ledger), destination=str(self.root / "source.txt"),
                                       url="https://example.org/source", version="synthetic",
                                       purpose="offline test", size_basis="synthetic 10B cap", max_bytes=10)

    def tearDown(self):
        # TemporaryDirectory owns exactly the fresh resolved directory above.
        self.temporary.cleanup()

    def state(self):
        return json.loads(self.ledger.read_text())

    def run_capture(self, response):
        with patch.object(fetch_source.urllib.request, "build_opener") as factory:
            factory.return_value.open.return_value = response
            with patch("sys.stdout", new=io.StringIO()):
                fetch_source.capture(self.args)

    def test_success_settles_exact_bytes_without_reservation(self):
        response = FakeResponse()
        self.run_capture(response)
        self.assertEqual(5, self.state()["receivedBytes"])
        self.assertEqual(0, self.state()["reservedBytes"])
        self.assertEqual(b"hello", (self.root / "source.txt").read_bytes())
        self.assertTrue(all(0 < n <= 10 for n in response.read_requests))

    def test_unknown_size_never_reads_past_hard_cap(self):
        response = FakeResponse(b"x" * 100, length=None)
        with self.assertRaisesRegex(ValueError, "hard limit"):
            self.run_capture(response)
        self.assertEqual(10, self.state()["receivedBytes"])
        self.assertEqual([10], response.read_requests)
        self.assertFalse((self.root / "source.txt").exists())

    def test_partial_chunk_is_charged_and_unknown_remainder_stays_reserved(self):
        with self.assertRaises(http.client.IncompleteRead):
            self.run_capture(FakeResponse(failure=http.client.IncompleteRead(b"12345", 5)))
        state = self.state()
        self.assertEqual(5, state["receivedBytes"])
        self.assertEqual(5, state["reservedBytes"])
        self.assertTrue(state["entries"][0]["receivedBytesAreLowerBound"])

    def test_cancel_during_read_retains_budget_and_records_cancellation(self):
        with self.assertRaises(KeyboardInterrupt):
            self.run_capture(FakeResponse(failure=KeyboardInterrupt()))
        state = self.state()
        self.assertEqual("CANCELLED", state["entries"][0]["status"])
        self.assertEqual(10, state["reservedBytes"])
        self.assertFalse(self.ledger.with_name(self.ledger.name + ".lock").exists())

    def test_redirect_does_not_consume_body_or_follow_location(self):
        for code in (301, 302, 303, 307, 308):
            response = FakeResponse(b"x" * 10000, length="10000")
            handler = fetch_source.NoRedirect()
            method = getattr(handler, "http_error_" + str(code))
            with self.assertRaisesRegex(ValueError, "Redirect refused"):
                method(None, response, code, "redirect", {"Location": "https://example.org/next"})
            self.assertEqual([], response.read_requests)
            self.assertTrue(response.closed)

    def test_oversized_header_and_existing_destination_do_not_read(self):
        response = FakeResponse(length="11")
        with self.assertRaisesRegex(ValueError, "hard limit"):
            self.run_capture(response)
        self.assertEqual([], response.read_requests)
        self.assertEqual(0, self.state()["receivedBytes"])
        (self.root / "source.txt").write_bytes(b"user-owned")
        with self.assertRaises(FileExistsError):
            self.run_capture(FakeResponse())
        self.assertEqual(b"user-owned", (self.root / "source.txt").read_bytes())

    def test_cancel_before_reservation_save_preserves_original_ledger_bytes(self):
        lines, first = inspect.getsourcelines(fetch_source.capture)
        for token in ('ledger["entries"].append(entry)', 'ledger["reservedBytes"] += limit', 'ledger_write_ok = False'):
            with self.subTest(before=token):
                before = self.ledger.read_bytes()
                target = first + next(i for i, line in enumerate(lines) if line.strip() == token)
                def cancel(frame, event, _):
                    if event == "line" and frame.f_code is fetch_source.capture.__code__ and frame.f_lineno == target:
                        raise KeyboardInterrupt("synthetic pre-reservation cancellation")
                    return cancel
                with patch.object(fetch_source.urllib.request, "build_opener") as factory:
                    try:
                        sys.settrace(cancel)
                        with self.assertRaises(KeyboardInterrupt):
                            fetch_source.capture(self.args)
                    finally:
                        sys.settrace(None)
                    factory.assert_not_called()
                self.assertEqual(before, self.ledger.read_bytes())
                self.assertFalse(self.ledger.with_name(self.ledger.name + ".lock").exists())

    def test_cancel_after_saved_reservation_before_ack_keeps_lock_and_budget(self):
        lines, first = inspect.getsourcelines(fetch_source.capture)
        target = first + next(i for i, line in enumerate(lines) if line.strip() == "reservation_committed = True")
        def cancel(frame, event, _):
            if event == "line" and frame.f_code is fetch_source.capture.__code__ and frame.f_lineno == target:
                raise KeyboardInterrupt("synthetic acknowledgement cancellation")
            return cancel
        with patch.object(fetch_source.urllib.request, "build_opener") as factory:
            try:
                sys.settrace(cancel)
                with self.assertRaises(KeyboardInterrupt):
                    fetch_source.capture(self.args)
            finally:
                sys.settrace(None)
            factory.assert_not_called()
        self.assertEqual(10, self.state()["reservedBytes"])
        self.assertEqual(0, self.state()["receivedBytes"])
        self.assertTrue(self.ledger.with_name(self.ledger.name + ".lock").exists())

    def test_initial_atomic_write_failure_keeps_lock_and_never_opens_network(self):
        before = self.ledger.read_bytes()
        with patch.object(fetch_source, "save_json", side_effect=OSError("reservation write failed")), \
                patch.object(fetch_source.urllib.request, "build_opener") as factory:
            with self.assertRaises(OSError):
                fetch_source.capture(self.args)
            factory.assert_not_called()
        self.assertEqual(before, self.ledger.read_bytes())
        self.assertTrue(self.ledger.with_name(self.ledger.name + ".lock").exists())

    def test_progress_atomic_write_failure_preserves_prior_full_reservation(self):
        save = fetch_source.save_json
        calls = []
        def fail_progress(path, ledger):
            calls.append(1)
            if len(calls) == 2:
                raise OSError("progress write failed")
            return save(path, ledger)
        with patch.object(fetch_source, "save_json", side_effect=fail_progress):
            with self.assertRaises(OSError):
                self.run_capture(FakeResponse())
        self.assertEqual(10, self.state()["reservedBytes"])
        self.assertEqual(0, self.state()["receivedBytes"])
        self.assertEqual(b"hello", (self.root / "source.txt.partial").read_bytes())
        self.assertEqual(2, len(calls))  # No speculative second write in finally.
        self.assertTrue(self.ledger.with_name(self.ledger.name + ".lock").exists())

    def test_final_atomic_write_failure_keeps_file_and_coordinator_recovery_lock(self):
        save = fetch_source.save_json
        def fail_final(path, ledger):
            if ledger["entries"][-1].get("status") == "CAPTURED_PENDING_SOURCE_REVIEW":
                raise OSError("final write failed")
            return save(path, ledger)
        with patch.object(fetch_source, "save_json", side_effect=fail_final):
            with self.assertRaises(OSError):
                self.run_capture(FakeResponse())
        self.assertEqual(b"hello", (self.root / "source.txt").read_bytes())
        self.assertEqual(10, self.state()["reservedBytes"])
        self.assertEqual(0, self.state()["receivedBytes"])
        self.assertTrue(self.ledger.with_name(self.ledger.name + ".lock").exists())


if __name__ == "__main__":
    unittest.main()
