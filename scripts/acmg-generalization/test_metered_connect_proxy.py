import importlib.util
import argparse
import json
from pathlib import Path
import socket
import socketserver
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("metered_connect_proxy", ROOT / "metered_connect_proxy.py")
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class MeterTest(unittest.TestCase):
    def test_cap_never_reads_unreserved_bytes(self):
        sender, receiver = socket.socketpair()
        try:
            sender.sendall(b"abcdefghij")
            meter = proxy.Budget(6)
            self.assertEqual(b"abcdef", meter.receive(receiver))
            self.assertEqual(6, meter.received)
            with self.assertRaisesRegex(ValueError, "BYTE_LIMIT"):
                meter.receive(receiver)
            self.assertEqual(b"ghij", receiver.recv(4))
        finally:
            sender.close()
            receiver.close()

    def test_previous_bytes_survive_later_transport_failure(self):
        class Upstream:
            calls = 0
            def recv(self, size):
                self.calls += 1
                if self.calls == 1:
                    return b"data"
                raise OSError("closed")
        meter = proxy.Budget(20)
        source = Upstream()
        meter.receive(source)
        with self.assertRaises(OSError):
            meter.receive(source)
        self.assertEqual(4, meter.received)
        self.assertTrue(meter.uncertain_read)

    def test_uncertain_receive_cancellation_retains_reservation_and_unlocks(self):
        class Upstream:
            def recv(self, size):
                raise KeyboardInterrupt("read interrupted with unknown transport count")
        class Server:
            server_address = ("127.0.0.1", 54321)
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def handle_request(self): self.budget.receive(Upstream())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ledger = root / "ledger.json"
            ledger.write_text(json.dumps(dict(maximumBytes=100, singleFileMaximumBytes=100,
                                             receivedBytes=0, reservedBytes=0, entries=[])))
            args = argparse.Namespace(ledger=str(ledger), receipt=str(root / "receipt.json"),
                                      stop_file=str(root / "stop"), max_bytes=20,
                                      port=0, duration_seconds=2, purpose="SYNTHETIC", deny_upstream=False)
            with mock.patch.object(proxy.socketserver, "TCPServer", return_value=Server()):
                with self.assertRaises(KeyboardInterrupt): proxy.run(args)
            result = json.loads(ledger.read_text())
            self.assertEqual(20, result["reservedBytes"])
            self.assertEqual(0, result["receivedBytes"])
            self.assertEqual("CANCELLED", result["entries"][0]["status"])
            self.assertFalse(ledger.with_name("ledger.json.lock").exists())
            args.max_bytes = 0
            with self.assertRaises(ValueError): proxy.run(args)
            self.assertFalse(ledger.with_name("ledger.json.lock").exists())

    def test_checkpoint_interruption_does_not_erase_observed_or_unsettled_bytes(self):
        class Upstream:
            def recv(self, size): return b"abc"
        def interrupted(): raise KeyboardInterrupt("checkpoint interrupted")
        meter = proxy.Budget(20, interrupted)
        with self.assertRaises(KeyboardInterrupt): meter.receive(Upstream())
        self.assertEqual(3, meter.received)
        self.assertTrue(meter.uncertain_read)
        with self.assertRaisesRegex(ValueError, "ACCOUNTING_UNSETTLED"):
            meter.receive(Upstream())

    def exercise_server(self, target, deny=False, payload=None):
        with tempfile.TemporaryDirectory() as directory:
            with socketserver.TCPServer(("127.0.0.1", 0), proxy.ConnectHandler) as server:
                server.allowed = target
                server.budget = proxy.Budget(1024)
                server.connections = []
                server.deny_upstream = deny
                server.stop_file = Path(directory) / "stop"
                server.deadline = time.monotonic() + 4
                thread = threading.Thread(target=server.handle_request)
                thread.start()
                with socket.create_connection(server.server_address, timeout=3) as client:
                    client.sendall(("CONNECT " + target + " HTTP/1.1\r\n\r\n").encode())
                    header = bytearray()
                    while not header.endswith(b"\r\n\r\n"):
                        header.extend(client.recv(1))
                    result = b""
                    if payload is not None:
                        client.sendall(payload)
                        while len(result) < len(payload):
                            result += client.recv(len(payload) - len(result))
                thread.join(5)
                self.assertFalse(thread.is_alive())
                return bytes(header), result, server.budget.received, server.connections

    def test_deny_preflight_does_not_open_any_upstream(self):
        header, _, received, records = self.exercise_server("storage.googleapis.com:443", deny=True)
        self.assertIn(b"502", header)
        self.assertEqual(0, received)
        self.assertEqual("PREFLIGHT_DENIED_WITHOUT_UPSTREAM_CONNECTION", records[0]["status"])

    def test_tunnel_preserves_opaque_bytes_and_counts_upstream_only(self):
        class Echo(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.sendall(self.request.recv(256))
        with socketserver.TCPServer(("127.0.0.1", 0), Echo) as upstream:
            thread = threading.Thread(target=upstream.handle_request)
            thread.start()
            opaque = b"\x16\x03\x01\xff\x00opaque end-to-end bytes"
            header, result, count, records = self.exercise_server(
                "127.0.0.1:" + str(upstream.server_address[1]), payload=opaque)
            thread.join(5)
        self.assertIn(b"200", header)
        self.assertEqual(opaque, result)
        self.assertEqual(len(opaque), count)
        self.assertEqual(count, records[0]["receivedTlsBytes"])


if __name__ == "__main__":
    unittest.main()
