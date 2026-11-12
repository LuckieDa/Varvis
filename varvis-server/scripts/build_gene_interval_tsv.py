#!/usr/bin/env python3
"""Build assembly-aware gene interval TSVs from GTF/GFF annotations.

The output is intentionally small and generic:

assembly	gene_symbol	chromosome	start	end

Coordinates are 1-based inclusive. Primary contigs 1..22, X, Y and MT are
kept; ALT, patch and unplaced contigs are skipped by default so runtime rescue
does not accidentally mix coordinate systems.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, Iterable, Iterator, Optional, Tuple


PRIMARY_CONTIGS = {str(i) for i in range(1, 23)} | {"X", "Y", "MT"}
CONTIG_ORDER = {str(i): i for i in range(1, 23)}
CONTIG_ORDER.update({"X": 23, "Y": 24, "MT": 25})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Input .gtf/.gff/.gz file")
    parser.add_argument("--assembly", required=True, help="Assembly label, e.g. GRCh37")
    parser.add_argument("--source", required=True, help="Source name, e.g. GENCODE")
    parser.add_argument("--source-version", required=True, help="Source version, e.g. GENCODE 19")
    parser.add_argument("--output", required=True, type=Path, help="Output interval TSV")
    parser.add_argument("--metadata-output", type=Path, help="Optional metadata JSON sidecar")
    parser.add_argument(
        "--include-non-primary-contigs",
        action="store_true",
        help="Include ALT/patch/unplaced contigs instead of keeping primary contigs only",
    )
    parser.add_argument(
        "--include-exon-intervals",
        action="store_true",
        help="Write gene and exon rows with a feature_type column for splice-aware rescue",
    )
    args = parser.parse_args()

    if not args.input.is_file():
        parser.error(f"input file does not exist: {args.input}")

    if args.include_exon_intervals:
        intervals, stats = build_feature_intervals(
            args.input, args.assembly, args.include_non_primary_contigs
        )
        write_feature_tsv(args.output, args.assembly, intervals)
    else:
        intervals, stats = build_intervals(args.input, args.assembly, args.include_non_primary_contigs)
        write_tsv(args.output, args.assembly, intervals)
    metadata = {
        "assembly": args.assembly,
        "source": args.source,
        "sourceVersion": args.source_version,
        "inputFileName": args.input.name,
        "inputSha256": sha256(args.input),
        "outputFileName": args.output.name,
        "outputSha256": sha256(args.output),
        "coordinateSystem": "1-based inclusive",
        "primaryContigsOnly": not args.include_non_primary_contigs,
        "outputColumns": ["assembly", "gene_symbol", "chromosome", "start", "end"]
        + (["feature_type"] if args.include_exon_intervals else []),
        "stats": stats,
    }
    if args.metadata_output:
        args.metadata_output.parent.mkdir(parents=True, exist_ok=True)
        args.metadata_output.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2, sort_keys=True))
    return 0


def build_intervals(
    input_path: Path,
    assembly: str,
    include_non_primary_contigs: bool,
) -> Tuple[Dict[Tuple[str, str], Tuple[str, int, int]], Dict[str, int]]:
    preferred: Dict[Tuple[str, str], Tuple[str, int, int]] = {}
    fallback: Dict[Tuple[str, str], Tuple[str, int, int]] = {}
    stats = Counter()

    for line in read_text(input_path):
        if not line or line.startswith("#"):
            continue
        fields = line.rstrip("\n").split("\t")
        if len(fields) < 9:
            stats["malformed_rows"] += 1
            continue
        raw_chromosome = fields[0]
        chrom = normalize_chromosome(raw_chromosome, include_non_primary_contigs)
        if not chrom:
            if not include_non_primary_contigs and is_non_primary_contig(raw_chromosome):
                stats["non_primary_contig_rows"] += 1
            else:
                stats["invalid_contig_rows"] += 1
            continue
        if not include_non_primary_contigs and chrom not in PRIMARY_CONTIGS:
            stats["non_primary_contig_rows"] += 1
            continue
        feature = fields[2].strip().lower()
        if feature not in {"gene", "transcript", "exon"}:
            stats["ignored_feature_rows"] += 1
            continue
        start = parse_positive_int(fields[3])
        end = parse_positive_int(fields[4])
        if start is None or end is None:
            stats["invalid_coordinate_rows"] += 1
            continue
        gene_symbol = gene_symbol_from_attributes(fields[8])
        if not gene_symbol:
            stats["missing_gene_symbol_rows"] += 1
            continue
        target = preferred if feature == "gene" else fallback
        add_interval(target, (gene_symbol.upper(), chrom), gene_symbol, start, end)
        stats[f"{feature}_rows"] += 1

    result = dict(fallback)
    result.update(preferred)
    stats["interval_rows"] = len(result)
    stats["unique_gene_symbols"] = len({value[0].upper() for value in result.values()})
    stats["multi_locus_gene_symbols"] = sum(
        1
        for count in Counter(value[0].upper() for value in result.values()).values()
        if count > 1
    )
    stats["assembly_rows"] = len(result) if assembly else 0
    return result, dict(stats)


def build_feature_intervals(
    input_path: Path,
    assembly: str,
    include_non_primary_contigs: bool,
) -> Tuple[Dict[Tuple[str, str, int, int, str], Tuple[str, str, int, int, str]], Dict[str, int]]:
    intervals: Dict[
        Tuple[str, str, int, int, str], Tuple[str, str, int, int, str]
    ] = {}
    stats = Counter()
    for line in read_text(input_path):
        if not line or line.startswith("#"):
            continue
        fields = line.rstrip("\n").split("\t")
        if len(fields) < 9:
            stats["malformed_rows"] += 1
            continue
        chrom = normalize_chromosome(fields[0], include_non_primary_contigs)
        if not chrom or (not include_non_primary_contigs and chrom not in PRIMARY_CONTIGS):
            stats["non_primary_contig_rows"] += 1
            continue
        feature = fields[2].strip().lower()
        if feature not in {"gene", "exon"}:
            stats["ignored_feature_rows"] += 1
            continue
        start = parse_positive_int(fields[3])
        end = parse_positive_int(fields[4])
        gene_symbol = gene_symbol_from_attributes(fields[8])
        if start is None or end is None or not gene_symbol:
            stats["invalid_feature_rows"] += 1
            continue
        left, right = min(start, end), max(start, end)
        feature_type = feature.upper()
        key = (gene_symbol.upper(), chrom, left, right, feature_type)
        intervals[key] = (gene_symbol, chrom, left, right, feature_type)
        stats[f"{feature}_rows"] += 1
    stats["interval_rows"] = len(intervals)
    stats["unique_gene_symbols"] = len({row[0].upper() for row in intervals.values()})
    stats["assembly_rows"] = len(intervals) if assembly else 0
    return intervals, dict(stats)


def read_text(path: Path) -> Iterator[str]:
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as handle:
            yield from handle
    else:
        with path.open("rt", encoding="utf-8", errors="replace") as handle:
            yield from handle


def add_interval(
    intervals: Dict[Tuple[str, str], Tuple[str, int, int]],
    key: Tuple[str, str],
    gene_symbol: str,
    start: int,
    end: int,
) -> None:
    left = min(start, end)
    right = max(start, end)
    existing = intervals.get(key)
    if existing is None:
        intervals[key] = (gene_symbol, left, right)
    else:
        intervals[key] = (existing[0], min(existing[1], left), max(existing[2], right))


def write_tsv(
    output_path: Path,
    assembly: str,
    intervals: Dict[Tuple[str, str], Tuple[str, int, int]],
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    rows = sorted(
        (
            gene_symbol,
            chrom,
            start,
            end,
        )
        for (_gene_key, chrom), (gene_symbol, start, end) in intervals.items()
    )
    rows.sort(key=lambda row: (CONTIG_ORDER.get(row[1], 999), row[2], row[3], row[0].upper()))
    with output_path.open("wt", encoding="utf-8", newline="\n") as handle:
        handle.write("assembly\tgene_symbol\tchromosome\tstart\tend\n")
        for gene_symbol, chrom, start, end in rows:
            handle.write(f"{assembly}\t{gene_symbol}\t{chrom}\t{start}\t{end}\n")


def write_feature_tsv(
    output_path: Path,
    assembly: str,
    intervals: Dict[Tuple[str, str, int, int, str], Tuple[str, str, int, int, str]],
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    rows = sorted(
        intervals.values(),
        key=lambda row: (
            CONTIG_ORDER.get(row[1], 999), row[2], row[3], row[0].upper(), row[4]
        ),
    )
    with output_path.open("wt", encoding="utf-8", newline="\n") as handle:
        handle.write("assembly\tgene_symbol\tchromosome\tstart\tend\tfeature_type\n")
        for gene_symbol, chrom, start, end, feature_type in rows:
            handle.write(
                f"{assembly}\t{gene_symbol}\t{chrom}\t{start}\t{end}\t{feature_type}\n"
            )


def gene_symbol_from_attributes(attributes: str) -> Optional[str]:
    for key in ("gene_name", "gene", "Name", "gene_symbol"):
        value = attribute_value(attributes, key)
        if value:
            return value
    return None


def attribute_value(attributes: str, key: str) -> Optional[str]:
    gtf = re.search(rf'(?:^|;\s*){re.escape(key)}\s+"([^"]+)"', attributes)
    if gtf:
        return gtf.group(1).strip()
    gff = re.search(rf'(?:^|;){re.escape(key)}=([^;]+)', attributes)
    if gff:
        return gff.group(1).strip()
    return None


def normalize_chromosome(value: str, allow_non_primary: bool = False) -> Optional[str]:
    if value is None:
        return None
    chrom = value.strip()
    if chrom.lower().startswith("chr"):
        chrom = chrom[3:]
    if chrom in {"M", "Mt", "mt", "m"}:
        chrom = "MT"
    chrom = chrom.upper()
    if not chrom:
        return None
    if not allow_non_primary and ("_" in chrom or "." in chrom):
        return None
    return chrom


def is_non_primary_contig(value: str) -> bool:
    chrom = value or ""
    return "_" in chrom or "." in chrom


def parse_positive_int(value: str) -> Optional[int]:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return None
    return number if number > 0 else None


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


if __name__ == "__main__":
    sys.exit(main())
