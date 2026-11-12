"""Task-private CONNECT transport meter for bounded gnomAD tabix queries.

TLS stays end to end: no certificate replacement, request rewriting or payload
inspection. Upstream bytes returned by recv (including TLS overhead) count
against the shared download budget. This is an application receive meter, not
a wire/interface meter or source checksum; unread kernel buffers are not counted.
The allowlist proves the host, not an encrypted object path. Only the coordinator
starts this helper; its ledger lock excludes other downloads until shutdown.
A crash or uncertain receive retains the unsettled reservation.
"""
import argparse
import json
import os
from pathlib import Path
import select
import socket
import socketserver
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch_source import save_json, utc_now


class Budget:
    def __init__(self, limit, checkpoint=lambda: None):
        if limit <= 0:
            raise ValueError("Positive transport byte limit required")
        self.limit, self.received, self.checkpoint = limit, 0, checkpoint
        self.uncertain_read = False

    def receive(self, upstream):
        if self.uncertain_read:
            raise ValueError("TRANSPORT_ACCOUNTING_UNSETTLED")
        remaining = self.limit - self.received
        if remaining <= 0:
            raise ValueError("TRANSPORT_BYTE_LIMIT_REACHED")
        self.uncertain_read = True
        block = upstream.recv(min(65536, remaining))
        self.received += len(block)
        self.checkpoint()
        self.uncertain_read = False
        return block


class ConnectHandler(socketserver.BaseRequestHandler):
    def handle(self):
        server = self.server
        self.request.settimeout(5)
        header = bytearray()
        while len(header) < 8192 and not header.endswith(b"\r\n\r\n"):
            item = self.request.recv(1)
            if not item:
                return
            header.extend(item)
        first = bytes(header).split(b"\r\n", 1)[0].decode("ascii", errors="replace").split()
        if len(first) != 3 or first[0] != "CONNECT" or first[1] != server.allowed:
            self.request.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
            return
        receipt = {"target": server.allowed, "startedAt": utc_now(), "status": "CONNECT_RECEIVED"}
        server.connections.append(receipt)
        server.budget.checkpoint()
        if server.deny_upstream:
            receipt["status"] = "PREFLIGHT_DENIED_WITHOUT_UPSTREAM_CONNECTION"
            self.request.sendall(b"HTTP/1.1 502 Preflight denied\r\nContent-Length: 0\r\n\r\n")
            server.budget.checkpoint()
            return
        before = server.budget.received
        try:
            if server.budget.uncertain_read:
                raise ValueError("TRANSPORT_ACCOUNTING_UNSETTLED")
            host, port = server.allowed.rsplit(":", 1)
            with socket.create_connection((host, int(port)), timeout=15) as upstream:
                upstream.settimeout(5)
                self.request.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
                while time.monotonic() < server.deadline and not server.stop_file.exists():
                    readable, _, _ = select.select([self.request, upstream], [], [], 0.5)
                    for source in readable:
                        block = server.budget.receive(upstream) if source is upstream else source.recv(65536)
                        if not block:
                            receipt["status"] = "CONNECTION_CLOSED"
                            return
                        (self.request if source is upstream else upstream).sendall(block)
                receipt["status"] = "TASK_TRANSPORT_STOPPED"
        except (OSError, ValueError) as error:
            receipt["status"] = "TRANSPORT_FAILED"
            receipt["error"] = str(error)
        finally:
            receipt["receivedTlsBytes"] = server.budget.received - before
            receipt["finishedAt"] = utc_now()
            server.budget.checkpoint()


def run(args):
    budget = Budget(args.max_bytes)
    if args.duration_seconds <= 0 or not 0 <= args.port <= 65535:
        raise ValueError("Positive duration and valid port required")
    ledger_path = Path(args.ledger).resolve(strict=True)
    receipt_path = Path(args.receipt).resolve()
    stop_file = Path(args.stop_file).resolve()
    for path in (receipt_path, stop_file):
        if ledger_path.parent not in path.parents or path.exists():
            raise ValueError("New control and receipt paths inside task resource root required")
    lock_path = ledger_path.with_name(ledger_path.name + ".lock")
    with lock_path.open("x", encoding="utf-8") as lock:
        lock.write(str(os.getpid()))
    entry = None
    connections = []
    try:
        ledger = json.loads(ledger_path.read_text(encoding="utf-8-sig"))
        available = ledger["maximumBytes"] - ledger["receivedBytes"] - ledger["reservedBytes"]
        if args.max_bytes > min(available, ledger["singleFileMaximumBytes"]):
            raise ValueError("Insufficient shared receive budget")
        entry = dict(id=str(uuid.uuid4()), url="https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/",
                     version="gnomAD joint v4.1 GRCh38", purpose=args.purpose,
                     sizeBasis="Bounded tabix loci; enforced aggregate TLS receive cap; not full-resource capture",
                     destination=str(receipt_path), startedAt=utc_now(), reservedBytes=args.max_bytes,
                     receivedBytes=0, status="TRANSPORT_RESERVED", connections=connections,
                     byteAccounting="APPLICATION_RECV_TLS_BYTES_INCLUDING_OVERHEAD_EXCLUDING_UNREAD_KERNEL_AND_WIRE_BYTES",
                     allowedHost="storage.googleapis.com:443", encryptedObjectPathVerified=False,
                     tool="existing Python standard library and existing tabix; no installation")
        ledger["reservedBytes"] += args.max_bytes
        ledger["entries"].append(entry)

        def checkpoint():
            entry["receivedBytes"] = budget.received
            save_json(ledger_path, ledger)

        budget.checkpoint = checkpoint
        checkpoint()
        receipt_path.parent.mkdir(parents=True, exist_ok=True)
        with socketserver.TCPServer(("127.0.0.1", args.port), ConnectHandler) as server:
            server.allowed = "storage.googleapis.com:443"
            server.budget, server.connections = budget, connections
            server.deny_upstream, server.stop_file = args.deny_upstream, stop_file
            server.deadline = time.monotonic() + args.duration_seconds
            server.timeout = 0.5
            save_json(receipt_path, {"pid": os.getpid(), "port": server.server_address[1],
                                    "ledgerEntryId": entry["id"], "status": "LISTENING",
                                    "denyUpstream": args.deny_upstream, "startedAt": utc_now()})
            while time.monotonic() < server.deadline and not stop_file.exists():
                server.handle_request()
        entry["status"] = "TRANSPORT_FINISHED"
    except BaseException as error:
        if entry is not None:
            entry["status"] = "CANCELLED" if isinstance(error, (KeyboardInterrupt, SystemExit)) else "TRANSPORT_FAILED"
            entry["error"] = str(error)
        raise
    finally:
        try:
            if entry is not None:
                entry["receivedBytes"] = budget.received
                entry["finishedAt"] = utc_now()
                ledger["receivedBytes"] += budget.received
                unsettled = max(0, args.max_bytes - budget.received) if budget.uncertain_read else 0
                ledger["reservedBytes"] -= args.max_bytes - unsettled
                entry["reservedBytes"] = unsettled
                if budget.uncertain_read:
                    entry["receivedBytesAreLowerBound"] = True
                    entry["unsettledReason"] = "Interrupted inside receive/accounting; unused reservation retained"
                save_json(ledger_path, ledger)
                save_json(receipt_path, entry)
        finally:
            lock_path.unlink()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("ledger", "receipt", "stop-file", "purpose"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--max-bytes", type=int, required=True)
    parser.add_argument("--duration-seconds", type=int, default=1800)
    parser.add_argument("--deny-upstream", action="store_true")
    run(parser.parse_args())
