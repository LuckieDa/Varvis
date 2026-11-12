"""Bounded, read-only runtime probes; each invocation preserves a new private report."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


def decode(data):
    # WSL may prepend a UTF-16 warning to a child's UTF-8 stderr.
    boundary = b"\r\0\n\0"
    if data.startswith(b"w\0s\0l\0") and boundary in data:
        head, _, tail = data.partition(boundary)
        return head.decode("utf-16-le", errors="replace") + "\n" + decode(tail)
    if data.count(b"\0") > len(data) // 8:
        return data.decode("utf-16-le", errors="replace")
    return data.decode("utf-8", errors="replace")


def probe(name, command, cwd, timeout, directory):
    report = {"stage": name, "command": command, "workingDirectory": str(cwd),
              "timeoutSeconds": timeout, "startedAt": datetime.now(timezone.utc).isoformat()}
    target = directory / (name + ".json")
    target.write_text(json.dumps(report, indent=2), encoding="utf-8")
    start = time.monotonic()
    try:
        child = subprocess.Popen(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError as error:
        report.update(exitCode=None, timedOut=False, elapsedSeconds=time.monotonic() - start, launchError=str(error))
        target.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report), flush=True)
        return False
    timed_out = False
    try:
        stdout, stderr = child.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        child.kill()  # Only the client launched by this probe; never stop WSL or a service.
        stdout, stderr = child.communicate(timeout=5)
    (directory / (name + ".stdout.bin")).write_bytes(stdout)
    (directory / (name + ".stderr.bin")).write_bytes(stderr)
    report.update(exitCode=child.returncode, timedOut=timed_out, clientTerminatedByProbe=timed_out, elapsedSeconds=time.monotonic() - start,
                  stdout=decode(stdout)[:20000], stderr=decode(stderr)[:20000])
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return child.returncode == 0 and not timed_out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--resource-root", required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--stage", choices=["all", "wsl", "wsl-list", "wsl-root", "python-script", "wrapper", "reference", "readiness"], default="all")
    args = parser.parse_args()
    properties = dict(line.split("=", 1) for line in args.config.read_text(encoding="utf-8-sig").splitlines()
                      if line.startswith("variant-analysis.acmg-business.") and "=" in line)
    key = "variant-analysis.acmg-business."
    command = json.loads(properties[key + "spliceai-raw-command-json"])
    reference = properties[key + "spliceai-reference-fasta"].replace("${VARVIS_RESOURCE_ROOT}", args.resource_root)
    if len(command) < 8 or command[3:5] != ["--", "bash"]:
        raise ValueError("Unrecognized configured wrapper shape; inspect configuration instead of guessing")
    prefix, wrapper, python, script = command[:4], command[5], command[6], command[7]
    if not re.fullmatch(r"[A-Za-z]:/.*", reference):
        raise ValueError("Expected configured Windows reference path")
    linux_reference = "/mnt/" + reference[0].lower() + reference[2:]
    work = args.work.resolve()
    directory = work / ("runtime-diagnostic-" + uuid.uuid4().hex)
    directory.mkdir(parents=True, exist_ok=False)
    output = directory / "fresh-readiness.json"
    paths = ["--reference-fasta", reference, "--output", output.as_posix()]
    reference_code = ("import pathlib,sys,json,hashlib; p=pathlib.Path(sys.argv[1]); "
                      "f=p.open('rb'); b=f.read(1); f.close(); i=pathlib.Path(str(p)+'.fai'); "
                      "print(json.dumps(dict(path=str(p),bytes=p.stat().st_size,firstByte=b.hex(),"
                      "indexSha256=hashlib.sha256(i.read_bytes()).hexdigest())))")
    stages = [
        ("wsl-list", [command[0], "--list", "--verbose"], 20),
        ("wsl-root", command[:3] + ["--cd", "/", "--", "/bin/true"], 20),
        ("wsl", prefix + ["/bin/true"], 20),
        ("python-script", prefix + [python, script, "--help"], 60),
        ("wrapper", prefix + ["bash", "-x", wrapper, python, script] + command[8:] + paths + ["--help"], 60),
        ("reference", prefix + [python, "-c", reference_code, linux_reference], 60),
        ("readiness", command + ["--readiness-only"] + paths, 120),
    ]
    print(str(directory), flush=True)
    for name, argv, timeout in stages:
        if args.stage == "all" and name in ("wsl-list", "wsl-root"):
            continue
        if args.stage not in ("all", name):
            continue
        if name == "readiness" and output.exists():
            raise RuntimeError("Readiness output must not exist before this command")
        if not probe(name, argv, work, timeout, directory):
            return 1
    if args.stage in ("all", "readiness"):
        if not output.is_file():
            raise RuntimeError("Command succeeded without creating its new readiness output")
        result = {"file": str(output), "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
                  "result": json.loads(output.read_text(encoding="utf-8"))}
        (directory / "fresh-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
