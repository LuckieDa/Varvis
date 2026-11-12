#!/usr/bin/env python3
"""Synthetic unit and smoke tests for the exome-like BED preparation tools."""

from __future__ import annotations

import gzip
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DERIVE = ROOT / "scripts" / "benchmark" / "derive_exome_bed.py"
CHECK = ROOT / "scripts" / "benchmark" / "check_truth_region.py"
FIXTURE_GTF = ROOT / "scripts" / "benchmark" / "fixtures" / "exome-bed-synthetic.gtf"
FIXTURE_FAI = ROOT / "scripts" / "benchmark" / "fixtures" / "exome-bed-synthetic.fai"


def run_json(command: list[str], expected_returncode: int = 0) -> dict:
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
    if completed.returncode != expected_returncode:
        raise AssertionError(f"command returned {completed.returncode}: {completed.stderr}\n{completed.stdout}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise AssertionError(f"command did not produce JSON: {completed.stdout}") from error


class ExomeBedPreparationTest(unittest.TestCase):
    def test_gzip_derivation_union_manifest_and_idempotence(self) -> None:
        with tempfile.TemporaryDirectory(prefix="varvis-exome-bed-test-") as temporary:
            workspace = Path(temporary)
            gtf_gz = workspace / "synthetic.gtf.gz"
            with FIXTURE_GTF.open("rb") as source, gzip.open(gtf_gz, "wb") as target:
                target.write(source.read())
            output = workspace / "derived.bed"
            manifest = workspace / "derived.manifest.json"
            command = [
                sys.executable, str(DERIVE), "--gtf", str(gtf_gz), "--contigs", str(FIXTURE_FAI),
                "--output", str(output), "--manifest", str(manifest), "--assembly", "GRCh38",
                "--gene-model", "GENCODE", "--gencode-release", "v47", "--padding", "20",
                "--input-contig-style", "gencode", "--output-contig-style", "numeric",
                "--generated-at", "2026-01-01T00:00:00Z", "--synthetic",
            ]
            first = run_json(command)
            self.assertEqual(first["resourceKind"], "SYNTHETIC")
            self.assertEqual(output.read_text(encoding="utf-8"), "1\t0\t50\n1\t929\t980\n2\t79\t180\nX\t969\t1000\n")
            self.assertGreater(first["statistics"]["malformedRows"], 0)
            self.assertGreater(first["statistics"]["nonProteinCodingRows"], 0)
            self.assertGreater(first["statistics"]["missingTranscriptTypeRows"], 0)
            self.assertGreater(first["statistics"]["nonTargetContigRows"], 0)
            self.assertEqual(first["statistics"]["finalIntervalCount"], 4)
            self.assertEqual(first["source"]["sha256"], hashlib.sha256(gtf_gz.read_bytes()).hexdigest())
            self.assertEqual(first["output"]["sha256"], output_hash := hashlib.sha256(output.read_bytes()).hexdigest())
            self.assertEqual(first["perChromosome"]["1"]["mergedIntervalCount"], 2)
            self.assertEqual(first["perChromosome"]["2"]["mergedIntervalCount"], 1)
            self.assertEqual(first["perChromosome"]["X"]["coveredBases"], 31)
            self.assertNotIn("D:\\", json.dumps(first))
            self.assertNotIn("C:\\", json.dumps(first))
            self.assertNotIn("..", json.dumps(first))
            self.assertNotIn("hostname", json.dumps(first).lower())
            second = run_json(command)
            self.assertEqual(second["parameterFingerprint"], first["parameterFingerprint"])
            self.assertEqual(output_hash, hashlib.sha256(output.read_bytes()).hexdigest())
            output.write_text("1\t0\t1\n", encoding="utf-8", newline="\n")
            mismatch = run_json(command, 1)
            self.assertEqual(mismatch["error"]["code"], "OUTPUT_EXISTS_MISMATCH")

    def test_plain_gtf_and_failure_for_missing_contig_lengths(self) -> None:
        with tempfile.TemporaryDirectory(prefix="varvis-exome-bed-test-") as temporary:
            workspace = Path(temporary)
            output = workspace / "derived.bed"
            manifest = workspace / "derived.manifest.json"
            command = [
                sys.executable, str(DERIVE), "--gtf", str(FIXTURE_GTF), "--contigs", str(FIXTURE_FAI),
                "--output", str(output), "--manifest", str(manifest), "--input-contig-style", "gencode",
            ]
            self.assertEqual(run_json(command)["assembly"], "GRCh38")
            missing_command = [
                sys.executable, str(DERIVE), "--gtf", str(FIXTURE_GTF), "--contigs", str(workspace / "missing.fai"),
                "--output", str(output), "--manifest", str(manifest), "--input-contig-style", "gencode",
            ]
            failure = run_json(missing_command, 1)
            self.assertEqual(failure["error"]["code"], "CONTIG_LENGTHS_MISSING")

            incomplete_gtf = workspace / "synthetic.gtf.crdownload"
            incomplete_gtf.write_bytes(FIXTURE_GTF.read_bytes())
            incomplete_command = [
                sys.executable, str(DERIVE), "--gtf", str(incomplete_gtf), "--contigs", str(FIXTURE_FAI),
                "--output", str(workspace / "incomplete.bed"), "--manifest", str(workspace / "incomplete.json"),
                "--input-contig-style", "gencode",
            ]
            incomplete = run_json(incomplete_command, 1)
            self.assertEqual(incomplete["error"]["code"], "INCOMPLETE_INPUT")

    def test_truth_inside_indel_boundary_assembly_and_contig(self) -> None:
        with tempfile.TemporaryDirectory(prefix="varvis-exome-bed-test-") as temporary:
            workspace = Path(temporary)
            bed = workspace / "derived.bed"
            manifest = workspace / "derived.manifest.json"
            run_json([
                sys.executable, str(DERIVE), "--gtf", str(FIXTURE_GTF), "--contigs", str(FIXTURE_FAI),
                "--output", str(bed), "--manifest", str(manifest), "--input-contig-style", "gencode",
                "--generated-at", "2026-01-01T00:00:00Z", "--synthetic",
            ])
            inside = run_json([sys.executable, str(CHECK), "--bed", str(bed), "--assembly", "GRCh38", "--contig", "1", "--pos", "50", "--ref", "A", "--alt", "G"])
            self.assertEqual(inside["status"], "INSIDE_BENCHMARK_REGION")
            inside_gencode = run_json([sys.executable, str(CHECK), "--bed", str(bed), "--assembly", "GRCh38", "--contig", "chr1", "--contig-style", "gencode", "--pos", "50", "--ref", "A", "--alt", "G"])
            self.assertEqual(inside_gencode["status"], "INSIDE_BENCHMARK_REGION")
            outside = run_json([sys.executable, str(CHECK), "--bed", str(bed), "--assembly", "GRCh38", "--contig", "1", "--pos", "50", "--ref", "AC", "--alt", "A"])
            self.assertEqual(outside["status"], "OUTSIDE_BENCHMARK_REGION")
            mismatch = run_json([sys.executable, str(CHECK), "--bed", str(bed), "--assembly", "GRCh37", "--contig", "1", "--pos", "50", "--ref", "A", "--alt", "G"], 1)
            self.assertEqual(mismatch["status"], "ASSEMBLY_MISMATCH")
            unsupported = run_json([sys.executable, str(CHECK), "--bed", str(bed), "--assembly", "GRCh38", "--contig", "Y", "--pos", "50", "--ref", "A", "--alt", "G"], 1)
            self.assertEqual(unsupported["status"], "CONTIG_UNSUPPORTED")
            incomplete_bed = workspace / "derived.bed.part"
            incomplete_bed.write_bytes(bed.read_bytes())
            incomplete = run_json([sys.executable, str(CHECK), "--bed", str(incomplete_bed), "--assembly", "GRCh38", "--contig", "1", "--pos", "50", "--ref", "A", "--alt", "G"], 1)
            self.assertEqual(incomplete["status"], "INCOMPLETE_INPUT")


if __name__ == "__main__":
    unittest.main()
