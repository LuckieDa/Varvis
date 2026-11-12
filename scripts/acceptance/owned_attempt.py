"""Exclusive local acceptance launcher. Never opens existing output for writing."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def launch(root, attempt_id, command, port, data_root, spawn=subprocess.Popen):
    root = Path(root).resolve(strict=True)
    data_root = Path(data_root).resolve(strict=True)
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", attempt_id):
        raise ValueError("invalid attempt ID")
    if not command or not 1024 <= port <= 65535:
        raise ValueError("command and isolated port required")
    directory = root / attempt_id
    # mkdir is atomic, unlike exists()+mkdir(exist_ok=True). A collision never reaches spawn.
    directory.mkdir(exist_ok=False)
    handles = []
    try:
        for name in ("stdout.log", "stderr.log", "ownership.json"):
            handles.append((directory / name).open("xb"))
        stdout, stderr, manifest = handles
        created = datetime.datetime.now(datetime.timezone.utc).isoformat()
        child = spawn(command, cwd=str(data_root), stdout=stdout, stderr=stderr,
                      stdin=subprocess.DEVNULL,
                      creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        identity = {"schemaVersion": "owned-attempt-v1", "attemptId": attempt_id,
                    "pid": child.pid, "launchedAt": created, "port": port,
                    "dataRoot": str(data_root), "executable": str(Path(command[0]).resolve()),
                    "commandSha256": hashlib.sha256(json.dumps(command).encode()).hexdigest()}
        # The full command may contain local credentials; store only its digest.
        if os.name == "nt" and hasattr(child, "_handle"):
            import ctypes
            from ctypes import wintypes
            times = [wintypes.FILETIME() for _ in range(4)]
            if ctypes.windll.kernel32.GetProcessTimes(wintypes.HANDLE(int(child._handle)),
                    *(ctypes.byref(t) for t in times)):
                identity["processCreationFileTime"] = (times[0].dwHighDateTime << 32) | times[0].dwLowDateTime
        manifest.write(json.dumps(identity, indent=2).encode())
        manifest.flush()
        return identity
    finally:
        for handle in handles:
            handle.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--command-file", required=True)
    parser.add_argument("--data-root", required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    command = json.loads(Path(args.command_file).read_text(encoding="utf-8-sig"))
    print(json.dumps(launch(args.root, args.attempt, command, args.port, args.data_root)))
