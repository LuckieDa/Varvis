#!/usr/bin/env python3
"""Check Exomiser 2602 download status without reading ZIP contents."""

import argparse
import json
import os
import re
import sys
import textwrap
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_HG38_SIZE = 23576028714
DEFAULT_PHENOTYPE_SIZE = 13478530504
PROGRESS_PATTERN = re.compile(
    r"DOWNLOAD_PROGRESS\s+name=(?P<name>\S+)\s+bytes=(?P<bytes>\d+)\s+"
    r"expected=(?P<expected>\d+)\s+time=(?P<time>\S+)"
)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Check Exomiser 2602 data download status from file sizes, logs, "
            "and an optional PID. This does not run Exomiser, read ZIP "
            "contents, extract archives, or run finalize."
        ),
        epilog=textwrap.dedent(
            """\
            Example WSL command:
              wsl.exe -d Ubuntu -- python3 <PROJECT_ROOT>/varvis-server/scripts/check_exomiser_download_status.py \\
                --tool-dir <EXOMISER_ROOT> \\
                --pid 13612 \\
                --stale-minutes 10

            The script may display paths supplied by the caller in console or
            JSON status output. It does not write paths into TSV/CSV/clinical
            exports and does not inspect archive contents.
            """
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--tool-dir", required=True, help="Exomiser tool root.")
    parser.add_argument("--pid", type=int, help="Optional background download PID.")
    parser.add_argument(
        "--stale-minutes",
        type=float,
        default=10,
        help="Minutes after which latest progress is considered stale.",
    )
    parser.add_argument(
        "--json-output",
        help="Optional JSON status output file. Human-readable summary is still printed.",
    )
    parser.add_argument(
        "--expected-hg38-size",
        type=int,
        default=DEFAULT_HG38_SIZE,
        help="Expected final byte size for 2602_hg38.zip.",
    )
    parser.add_argument(
        "--expected-phenotype-size",
        type=int,
        default=DEFAULT_PHENOTYPE_SIZE,
        help="Expected final byte size for 2602_phenotype.zip.",
    )
    return parser.parse_args(argv)


def utc_now():
    return datetime.now(timezone.utc)


def file_status(path, expected_size):
    if not path.exists():
        current = 0
        status = "MISSING"
    else:
        current = path.stat().st_size
        if current == expected_size:
            status = "COMPLETE"
        elif current > expected_size:
            status = "OVERSIZED"
        else:
            status = "IN_PROGRESS"
    percent = (current / expected_size * 100.0) if expected_size else 0.0
    return {
        "path": str(path),
        "current_bytes": current,
        "expected_bytes": expected_size,
        "percent": round(percent, 6),
        "status": status,
    }


def pid_status(pid):
    if pid is None:
        return "UNKNOWN"
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return "NOT_RUNNING"
    except PermissionError:
        return "RUNNING"
    except OSError:
        return "NOT_RUNNING"
    return "RUNNING"


def tail_lines(path, max_bytes=65536):
    with path.open("rb") as handle:
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        handle.seek(max(0, size - max_bytes), os.SEEK_SET)
        data = handle.read().decode("utf-8", errors="replace")
    return data.splitlines()


def parse_timestamp(value):
    if not value:
        return None
    normalized = value
    if normalized.endswith("Z"):
        normalized = normalized[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def latest_log_status(tool_dir, now=None, stale_minutes=10):
    log_dir = tool_dir / "logs"
    logs = sorted(
        log_dir.glob("exomiser_data_download_*.log"),
        key=lambda path: path.stat().st_mtime if path.exists() else 0,
    )
    if not logs:
        return {
            "latest_log_file": None,
            "latest_progress_timestamp": None,
            "latest_progress_bytes": None,
            "latest_progress_name": None,
            "stale_status": "UNKNOWN",
            "stale_minutes": stale_minutes,
        }
    latest = logs[-1]
    latest_progress = None
    for line in tail_lines(latest):
        match = PROGRESS_PATTERN.search(line)
        if match:
            latest_progress = match
    if latest_progress is None:
        return {
            "latest_log_file": str(latest),
            "latest_progress_timestamp": None,
            "latest_progress_bytes": None,
            "latest_progress_name": None,
            "stale_status": "UNKNOWN",
            "stale_minutes": stale_minutes,
        }
    timestamp_text = latest_progress.group("time")
    timestamp = parse_timestamp(timestamp_text)
    if timestamp is None:
        stale_status = "UNKNOWN"
    else:
        now_value = now or utc_now()
        age_minutes = (now_value - timestamp.astimezone(timezone.utc)).total_seconds() / 60.0
        stale_status = "STALE" if age_minutes > stale_minutes else "FRESH"
    return {
        "latest_log_file": str(latest),
        "latest_progress_timestamp": timestamp_text,
        "latest_progress_bytes": int(latest_progress.group("bytes")),
        "latest_progress_name": latest_progress.group("name"),
        "stale_status": stale_status,
        "stale_minutes": stale_minutes,
    }


def recommended_action(hg38, phenotype, pid, log):
    statuses = {hg38["status"], phenotype["status"]}
    if "OVERSIZED" in statuses:
        return "STOP_OVERSIZED_FILE"
    if hg38["status"] == "COMPLETE" and phenotype["status"] == "COMPLETE":
        return "READY_FOR_FINALIZE"
    if "MISSING" in statuses and not (
        hg38["status"] == "MISSING" and phenotype["status"] == "MISSING"
    ):
        if log["latest_log_file"] is None:
            return "CHECK_LOG_PATH"
        if log["stale_status"] == "STALE":
            return "CHECK_OR_RESTART_RESUME"
        if pid == "NOT_RUNNING":
            return "RESTART_RESUME_REQUIRED"
        if pid == "RUNNING" or log["stale_status"] == "FRESH":
            return "KEEP_WAITING"
        return "CHECK_DOWNLOAD_SCRIPT"
    if "MISSING" in statuses:
        return "CHECK_DOWNLOAD_SCRIPT"
    if log["latest_log_file"] is None:
        return "CHECK_LOG_PATH"
    if log["stale_status"] == "STALE":
        return "CHECK_OR_RESTART_RESUME"
    if pid == "NOT_RUNNING":
        return "RESTART_RESUME_REQUIRED"
    if log["stale_status"] == "FRESH":
        return "KEEP_WAITING"
    if pid == "RUNNING":
        return "KEEP_WAITING"
    return "CHECK_OR_RESTART_RESUME"


def build_status(tool_dir, pid=None, stale_minutes=10, expected_hg38_size=DEFAULT_HG38_SIZE, expected_phenotype_size=DEFAULT_PHENOTYPE_SIZE, now=None):
    tool_dir = Path(tool_dir)
    hg38 = file_status(tool_dir / "data" / "2602_hg38.zip", expected_hg38_size)
    phenotype = file_status(
        tool_dir / "data" / "2602_phenotype.zip",
        expected_phenotype_size,
    )
    pid_value = pid_status(pid)
    log = latest_log_status(tool_dir, now=now, stale_minutes=stale_minutes)
    status = {
        "tool_dir": str(tool_dir),
        "hg38": hg38,
        "phenotype": phenotype,
        "pid_status": pid_value,
        "latest_log_file": log["latest_log_file"],
        "latest_progress_timestamp": log["latest_progress_timestamp"],
        "latest_progress_bytes": log["latest_progress_bytes"],
        "latest_progress_name": log["latest_progress_name"],
        "stale_status": log["stale_status"],
        "stale_minutes": stale_minutes,
    }
    status["recommended_next_action"] = recommended_action(
        hg38,
        phenotype,
        pid_value,
        log,
    )
    return status


def render_summary(status):
    lines = [
        "Exomiser 2602 Download Status",
        "tool dir: {}".format(status["tool_dir"]),
        "hg38 current bytes: {}".format(status["hg38"]["current_bytes"]),
        "hg38 expected bytes: {}".format(status["hg38"]["expected_bytes"]),
        "hg38 percent: {:.6f}".format(status["hg38"]["percent"]),
        "hg38 status: {}".format(status["hg38"]["status"]),
        "phenotype current bytes: {}".format(status["phenotype"]["current_bytes"]),
        "phenotype expected bytes: {}".format(status["phenotype"]["expected_bytes"]),
        "phenotype percent: {:.6f}".format(status["phenotype"]["percent"]),
        "phenotype status: {}".format(status["phenotype"]["status"]),
        "pid status: {}".format(status["pid_status"]),
        "latest log file: {}".format(status["latest_log_file"] or ""),
        "latest progress timestamp: {}".format(status["latest_progress_timestamp"] or ""),
        "latest progress bytes: {}".format(
            "" if status["latest_progress_bytes"] is None else status["latest_progress_bytes"]
        ),
        "latest progress name: {}".format(status["latest_progress_name"] or ""),
        "stale status: {}".format(status["stale_status"]),
        "recommended next action: {}".format(status["recommended_next_action"]),
    ]
    return "\n".join(lines)


def main(argv=None):
    args = parse_args(argv)
    try:
        status = build_status(
            args.tool_dir,
            pid=args.pid,
            stale_minutes=args.stale_minutes,
            expected_hg38_size=args.expected_hg38_size,
            expected_phenotype_size=args.expected_phenotype_size,
        )
        print(render_summary(status))
        if args.json_output:
            output_path = Path(args.json_output)
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(
                json.dumps(status, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
    except (OSError, ValueError) as error:
        print("Status check failed: {}".format(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
