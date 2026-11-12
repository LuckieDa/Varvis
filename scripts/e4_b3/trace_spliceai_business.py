"""Opt-in acceptance observer: preserve CLI diagnostics without altering commands, output or retry policy.

Only use with public/synthetic acceptance inputs and a task-private diagnostic directory.
The production business runner and frozen resource/profile files remain unchanged.
"""
import argparse
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--diagnostic-directory", type=Path, required=True)
    args, remaining = parser.parse_known_args()
    args.diagnostic_directory.mkdir(parents=True, exist_ok=True)
    original = subprocess.run

    def observed(command, *positional, **keywords):
        if not isinstance(command, list) or command[1:3] != ["-m", "spliceai"]:
            return original(command, *positional, **keywords)
        folder = args.diagnostic_directory / str(uuid.uuid4())
        folder.mkdir()
        shutil.copyfile(command[command.index("-I") + 1], folder / "input.vcf")
        started = time.monotonic()
        report = {"command": command, "automaticRetry": False, "dataClass": "SYNTHETIC_PUBLIC_ACCEPTANCE"}
        try:
            completed = original(command, *positional, **keywords)
            report.update(returnCode=completed.returncode, stdout=completed.stdout, stderr=completed.stderr)
            output = Path(command[command.index("-O") + 1])
            if output.is_file():
                shutil.copyfile(output, folder / "output.vcf")
            return completed
        except BaseException as error:
            report.update(errorType=type(error).__name__, error=str(error))
            raise
        finally:
            report["elapsedSeconds"] = time.monotonic() - started
            (folder / "diagnostic.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    subprocess.run = observed
    entry = Path(__file__).with_name("run_spliceai_business.py")
    sys.argv = [str(entry), *remaining]
    try:
        runpy.run_path(str(entry), run_name="__main__")
    finally:
        subprocess.run = original


if __name__ == "__main__":
    main()
