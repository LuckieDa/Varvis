import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import check_exomiser_download_status as checker


NOW = datetime(2026, 6, 24, 12, 0, 0, tzinfo=timezone.utc)


def make_tool_dir(root):
    tool_dir = Path(root) / "exomiser"
    (tool_dir / "data").mkdir(parents=True)
    (tool_dir / "logs").mkdir(parents=True)
    return tool_dir


def write_file(path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"x" * size)


def write_progress(tool_dir, name, bytes_value, minutes_ago=1):
    timestamp = (NOW - timedelta(minutes=minutes_ago)).isoformat()
    log = tool_dir / "logs" / "exomiser_data_download_20260624_120000.log"
    log.write_text(
        "BEGIN_DOWNLOAD name={name}\n"
        "DOWNLOAD_PROGRESS name={name} bytes={bytes_value} expected=100 time={time}\n".format(
            name=name,
            bytes_value=bytes_value,
            time=timestamp,
        ),
        encoding="utf-8",
    )
    return log


class CheckExomiserDownloadStatusTest(unittest.TestCase):

    def test_missing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)

            status = checker.build_status(
                tool_dir,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )

        self.assertEqual("MISSING", status["hg38"]["status"])
        self.assertEqual("MISSING", status["phenotype"]["status"])
        self.assertEqual("UNKNOWN", status["pid_status"])
        self.assertEqual("CHECK_DOWNLOAD_SCRIPT", status["recommended_next_action"])

    def test_in_progress_hg38(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)
            write_file(tool_dir / "data" / "2602_hg38.zip", 25)
            write_progress(tool_dir, "2602_hg38.zip", 25)

            status = checker.build_status(
                tool_dir,
                pid=None,
                stale_minutes=10,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )

        self.assertEqual("IN_PROGRESS", status["hg38"]["status"])
        self.assertEqual("MISSING", status["phenotype"]["status"])
        self.assertEqual(25.0, status["hg38"]["percent"])
        self.assertEqual("KEEP_WAITING", status["recommended_next_action"])

    def test_complete_hg38_missing_phenotype(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)
            write_file(tool_dir / "data" / "2602_hg38.zip", 100)
            write_progress(tool_dir, "2602_hg38.zip", 100)

            status = checker.build_status(
                tool_dir,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )

        self.assertEqual("COMPLETE", status["hg38"]["status"])
        self.assertEqual("MISSING", status["phenotype"]["status"])
        self.assertEqual("KEEP_WAITING", status["recommended_next_action"])

    def test_both_complete_ready_for_finalize(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)
            write_file(tool_dir / "data" / "2602_hg38.zip", 100)
            write_file(tool_dir / "data" / "2602_phenotype.zip", 50)
            write_progress(tool_dir, "2602_phenotype.zip", 50)

            status = checker.build_status(
                tool_dir,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )

        self.assertEqual("READY_FOR_FINALIZE", status["recommended_next_action"])

    def test_oversized_file(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)
            write_file(tool_dir / "data" / "2602_hg38.zip", 101)
            write_progress(tool_dir, "2602_hg38.zip", 101)

            status = checker.build_status(
                tool_dir,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )

        self.assertEqual("OVERSIZED", status["hg38"]["status"])
        self.assertEqual("STOP_OVERSIZED_FILE", status["recommended_next_action"])

    def test_stale_progress(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)
            write_file(tool_dir / "data" / "2602_hg38.zip", 25)
            write_file(tool_dir / "data" / "2602_phenotype.zip", 1)
            write_progress(tool_dir, "2602_hg38.zip", 25, minutes_ago=30)

            status = checker.build_status(
                tool_dir,
                pid=None,
                stale_minutes=10,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )

        self.assertEqual("STALE", status["stale_status"])
        self.assertEqual("CHECK_OR_RESTART_RESUME", status["recommended_next_action"])

    def test_fresh_progress(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)
            write_file(tool_dir / "data" / "2602_hg38.zip", 25)
            write_file(tool_dir / "data" / "2602_phenotype.zip", 1)
            write_progress(tool_dir, "2602_hg38.zip", 25, minutes_ago=1)

            status = checker.build_status(
                tool_dir,
                pid=None,
                stale_minutes=10,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )

        self.assertEqual("FRESH", status["stale_status"])
        self.assertEqual("KEEP_WAITING", status["recommended_next_action"])

    def test_pid_unknown_when_not_provided(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)

            status = checker.build_status(
                tool_dir,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )

        self.assertEqual("UNKNOWN", status["pid_status"])

    def test_json_output_basic_shape(self):
        with tempfile.TemporaryDirectory() as directory:
            tool_dir = make_tool_dir(directory)
            json_output = Path(directory) / "status.json"
            write_file(tool_dir / "data" / "2602_hg38.zip", 100)
            write_file(tool_dir / "data" / "2602_phenotype.zip", 50)
            write_progress(tool_dir, "2602_phenotype.zip", 50)
            status = checker.build_status(
                tool_dir,
                expected_hg38_size=100,
                expected_phenotype_size=50,
                now=NOW,
            )
            json_output.write_text(json.dumps(status), encoding="utf-8")
            payload = json.loads(json_output.read_text(encoding="utf-8"))

        self.assertEqual("COMPLETE", payload["hg38"]["status"])
        self.assertEqual("COMPLETE", payload["phenotype"]["status"])
        self.assertEqual("READY_FOR_FINALIZE", payload["recommended_next_action"])


if __name__ == "__main__":
    unittest.main()
