#!/usr/bin/env python3
"""Check whether a GRCh38 VCF truth allele is fully inside a BED interval."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import OrderedDict
from pathlib import Path
from typing import Dict, List, Optional, Tuple


TARGET_CONTIGS = [str(value) for value in range(1, 23)] + ["X"]
GTF_TO_NUMERIC = {f"chr{value}": value for value in TARGET_CONTIGS}
GTF_TO_NUMERIC.update({"chrY": "Y", "chrM": "MT", "chrMT": "MT"})
VALID_BASES = re.compile(r"^[ACGTN]+$", re.IGNORECASE)


class TruthError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def reject_incomplete_input(path: Path) -> None:
    if path.name.lower().endswith((".part", ".crdownload", ".download")):
        raise TruthError("INCOMPLETE_INPUT", "Input is marked as an incomplete download.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bed", required=True, type=Path)
    parser.add_argument("--assembly", required=True)
    parser.add_argument("--contig", required=True)
    parser.add_argument("--contig-style", choices=("numeric", "gencode"), default="numeric")
    parser.add_argument("--pos", required=True, type=int)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--alt", required=True)
    return parser.parse_args()


def normalize_contig(raw: str, style: str) -> str:
    if style == "gencode":
        mapped = GTF_TO_NUMERIC.get(raw)
    else:
        mapped = raw if raw in set(TARGET_CONTIGS) | {"Y", "M", "MT"} else None
        if mapped == "M":
            mapped = "MT"
    if mapped is None:
        raise TruthError("CONTIG_UNSUPPORTED", "Truth contig is not in the explicit supported allow-list.")
    if mapped == "Y":
        raise TruthError("CONTIG_UNSUPPORTED", "chrY/Y is not applicable in the frozen benchmark scope.")
    if mapped == "MT":
        raise TruthError("CONTIG_UNSUPPORTED", "mitochondrial contigs are unavailable in the frozen benchmark scope.")
    return mapped


def read_bed(path: Path) -> Dict[str, List[Tuple[int, int]]]:
    reject_incomplete_input(path)
    if not path.is_file():
        raise TruthError("BED_UNAVAILABLE", "BED file does not exist.")
    intervals: Dict[str, List[Tuple[int, int]]] = {}
    try:
        with path.open("rt", encoding="utf-8", errors="strict") as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip() or line.startswith("#"):
                    continue
                fields = line.rstrip("\r\n").split("\t")
                if len(fields) < 3:
                    raise TruthError("INVALID_BED", f"BED row {line_number} has fewer than three columns.")
                try:
                    start, end = int(fields[1]), int(fields[2])
                except ValueError as error:
                    raise TruthError("INVALID_BED", f"BED row {line_number} has non-numeric coordinates.") from error
                if fields[0] not in TARGET_CONTIGS or start < 0 or end <= start:
                    raise TruthError("INVALID_BED", f"BED row {line_number} is outside the frozen BED3 contract.")
                intervals.setdefault(fields[0], []).append((start, end))
    except UnicodeError as error:
        raise TruthError("INVALID_BED", "BED is not valid UTF-8.") from error
    return intervals


def result(status: str, args: argparse.Namespace, message: str, **extra) -> dict:
    payload = OrderedDict([
        ("schemaVersion", "benchmark-truth-region-result-v1"),
        ("status", status),
        ("assembly", args.assembly),
        ("bedFilename", args.bed.name),
        ("message", message),
    ])
    payload.update(extra)
    return payload


def main() -> int:
    args = parse_args()
    if args.assembly != "GRCh38":
        payload = result("ASSEMBLY_MISMATCH", args, "Truth assembly must be GRCh38.")
        print(json.dumps(payload, indent=2))
        return 1
    if args.pos <= 0 or not VALID_BASES.fullmatch(args.ref) or not VALID_BASES.fullmatch(args.alt) or "," in args.alt:
        payload = result("INVALID_TRUTH_ALLELE", args, "Truth POS/REF/ALT is not a supported SNV or small-indel allele.")
        print(json.dumps(payload, indent=2))
        return 1
    try:
        contig = normalize_contig(args.contig, args.contig_style)
        intervals = read_bed(args.bed)
        start = args.pos - 1
        end = start + len(args.ref)
        inside = any(start >= bed_start and end <= bed_end for bed_start, bed_end in intervals.get(contig, []))
        status = "INSIDE_BENCHMARK_REGION" if inside else "OUTSIDE_BENCHMARK_REGION"
        payload = result(status, args, "Truth reference span is covered by a BED interval." if inside else "Truth reference span is not fully covered by a BED interval.", contig=contig, referenceStart0=start, referenceEnd0=end)
        print(json.dumps(payload, indent=2))
        return 0
    except TruthError as error:
        payload = result(error.code, args, str(error))
        print(json.dumps(payload, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
