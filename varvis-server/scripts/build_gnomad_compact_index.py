#!/usr/bin/env python3
"""Build compact gnomAD max-AF indexes for Varvis.

The full gnomAD site VCFs contain very long INFO fields. Runtime random queries
against those files are accurate but too slow for WES-scale candidate lists.
This script materializes one compact, tabix-indexed TSV per chromosome:

    CHROM  POS  REF  ALT  AF  AF_popmax  AC  AN  nhomalt  source_dataset  gnomad_version  filters

The Java provider still accepts the previous 6-column MAX_AF format, but new
indexes retain enough fields for provenance and future scoring.
"""

from __future__ import annotations

import argparse
import gzip
import io
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
from typing import Dict, Iterable, Iterator, Optional, Tuple


CHROM_PATTERN = re.compile(r"(?i)(?:^|[._-])(chr(?:[0-9]+|x|y|m|mt))[._-]")
PROGRESS_INTERVAL = 100_000
OFFICIAL_AF_FIELDS = [
    "AF",
    "AF_grpmax",
    "AF_grpmax_non_ukb",
    "AF_popmax",
    "gnomAD_AF",
    "gnomADg_AF",
    "gnomADe_AF",
]


def env_default(name: str) -> Optional[str]:
    value = os.environ.get(name)
    return value if value else None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build compact gnomAD max-AF indexes.")
    parser.add_argument(
        "--input-dir",
        default=env_default("GNOMAD_ROOT"),
        help="Directory containing gnomAD *.vcf.bgz files. Defaults to GNOMAD_ROOT.",
    )
    parser.add_argument(
        "--output-dir",
        default=env_default("GNOMAD_COMPACT_DIR"),
        help="Directory for compact *.tsv.gz and *.tbi files. Defaults to GNOMAD_COMPACT_DIR or <input-dir>/compact.",
    )
    parser.add_argument(
        "--bcftools",
        default=env_default("BCFTOOLS") or "bcftools",
        help="Path to bcftools executable. Defaults to BCFTOOLS or PATH lookup.",
    )
    parser.add_argument(
        "--bgzip",
        default=env_default("BGZIP") or "bgzip",
        help="Path to bgzip executable. Defaults to BGZIP or PATH lookup.",
    )
    parser.add_argument(
        "--tabix",
        default=env_default("TABIX") or "tabix",
        help="Path to tabix executable. Defaults to TABIX or PATH lookup.",
    )
    parser.add_argument(
        "--chromosomes",
        nargs="*",
        help="Optional chromosome names to build, for example chr1 chr2 chrX.",
    )
    parser.add_argument("--force", action="store_true", help="Rebuild existing compact indexes.")
    parser.add_argument(
        "--scan-all-af-fields",
        action="store_true",
        help="Exhaustively parse all AF-like INFO fields in Python. Slower; default uses official max-AF fields.",
    )
    return parser.parse_args()


def normalize_chromosome(value: str) -> str:
    normalized = value.strip()
    if normalized.lower().startswith("chr"):
        normalized = normalized[3:]
    if normalized.upper() in {"M", "MT"}:
        return "MT"
    return normalized.upper()


def chromosome_from_file(path: pathlib.Path) -> Optional[str]:
    match = CHROM_PATTERN.search(path.name)
    if not match:
        return None
    return normalize_chromosome(match.group(1))


def looks_like_af_field(key: str) -> bool:
    normalized = key.upper()
    return (
        normalized == "AF"
        or normalized.startswith("AF_")
        or normalized.endswith("_AF")
        or "_AF_" in normalized
        or "ALLELE_FREQUENCY" in normalized
    )


def parse_info(value: str) -> Dict[str, str]:
    result: Dict[str, str] = {}
    if not value or value == ".":
        return result
    for token in value.split(";"):
        if not token:
            continue
        if "=" not in token:
            result[token] = "true"
            continue
        key, raw = token.split("=", 1)
        result[key] = raw
    return result


def parse_float(value: str) -> Optional[float]:
    if not value or value == ".":
        return None
    try:
        return float(value)
    except ValueError:
        return None


def value_at(value: Optional[str], alt_index: int) -> Optional[float]:
    if value is None:
        return None
    values = value.split(",")
    selected = values[alt_index] if alt_index < len(values) else value
    return parse_float(selected)


def max_frequency(info: Dict[str, str], alt_index: int) -> Tuple[Optional[float], Optional[str]]:
    maximum: Optional[float] = None
    maximum_field: Optional[str] = None
    for key, raw in info.items():
        if not looks_like_af_field(key):
            continue
        value = value_at(raw, alt_index)
        if value is None or value < 0.0 or value > 1.0:
            continue
        if maximum is None or value > maximum:
            maximum = value
            maximum_field = key

    if maximum is not None:
        return maximum, maximum_field

    ac = value_at(info.get("AC"), alt_index)
    an = value_at(info.get("AN"), 0)
    if ac is not None and an is not None and an > 0:
        return ac / an, "AC/AN"
    return None, None


def format_float(value: Optional[float]) -> str:
    if value is None:
        return "."
    return f"{value:.8g}"


def iter_vcf_records(path: pathlib.Path) -> Iterator[str]:
    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if line and not line.startswith("#"):
                yield line.rstrip("\n")


def compact_output_name(input_file: pathlib.Path) -> str:
    chromosome = chromosome_from_file(input_file)
    if chromosome is None:
        stem = input_file.name.replace(".vcf.bgz", "").replace(".vcf.gz", "")
        return f"{stem}.max-af.tsv.gz"
    label = "chrM" if chromosome == "MT" else f"chr{chromosome}"
    return f"gnomad.exomes.v4.1.max-af.{label}.tsv.gz"


def source_dataset_from_file(input_file: pathlib.Path) -> str:
    name = input_file.name.lower()
    if "genomes" in name:
        return "genomes"
    if "exomes" in name:
        return "exomes"
    return "unknown"


def version_from_file(input_file: pathlib.Path) -> str:
    match = re.search(r"v\d+(?:\.\d+)*", input_file.name)
    return f"gnomAD {match.group(0)}" if match else "gnomAD local"


def run_checked(command: Iterable[str]) -> None:
    completed = subprocess.run(list(command), text=True, capture_output=True)
    if completed.returncode != 0:
        raise RuntimeError(
            "Command failed: "
            + " ".join(command)
            + "\n"
            + (completed.stderr or completed.stdout)
        )


def build_one_with_bcftools(
    input_file: pathlib.Path,
    output_dir: pathlib.Path,
    bcftools: str,
    bgzip: str,
    tabix: str,
    force: bool,
) -> None:
    output_file = output_dir / compact_output_name(input_file)
    index_file = output_file.with_name(output_file.name + ".tbi")
    if output_file.exists() and index_file.exists() and not force:
        print(f"[skip] {output_file} already exists", flush=True)
        return

    output_dir.mkdir(parents=True, exist_ok=True)
    temporary_file = output_file.with_name(output_file.name + ".tmp")
    if temporary_file.exists():
        temporary_file.unlink()

    print(f"[start] {input_file} -> {output_file}", flush=True)
    started = time.time()
    query_format = "%CHROM\\t%POS\\t%REF\\t%ALT\\t%FILTER"
    for field in OFFICIAL_AF_FIELDS:
        query_format += f"\\t%INFO/{field}"
    query_format += "\\t%INFO/AC\\t%INFO/AN\\t%INFO/nhomalt\\n"

    bcftools_process = subprocess.Popen(
        [bcftools, "query", "-u", "-f", query_format, str(input_file)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert bcftools_process.stdout is not None
    records = 0
    rows = 0
    with temporary_file.open("wb") as compressed_output:
        bgzip_process = subprocess.Popen(
            [bgzip, "-c"],
            stdin=subprocess.PIPE,
            stdout=compressed_output,
            stderr=subprocess.PIPE,
        )
        assert bgzip_process.stdin is not None
        try:
            text_output = io.TextIOWrapper(bcftools_process.stdout, encoding="utf-8", errors="replace")
            for line in text_output:
                columns = line.rstrip("\n").split("\t")
                expected = 5 + len(OFFICIAL_AF_FIELDS) + 3
                if len(columns) < expected:
                    continue
                records += 1
                chrom, pos, ref, alt_field, filters = columns[:5]
                values = columns[5 : 5 + len(OFFICIAL_AF_FIELDS)]
                af_value = values[0] if values else "."
                popmax_value = values[OFFICIAL_AF_FIELDS.index("AF_popmax")]
                ac_value = columns[5 + len(OFFICIAL_AF_FIELDS)]
                an_value = columns[6 + len(OFFICIAL_AF_FIELDS)]
                nhomalt_value = columns[7 + len(OFFICIAL_AF_FIELDS)]
                source_dataset = source_dataset_from_file(input_file)
                gnomad_version = version_from_file(input_file)
                for alt_index, alt in enumerate(alt_field.split(",")):
                    af = value_at(af_value, alt_index)
                    popmax = value_at(popmax_value, alt_index)
                    ac = value_at(ac_value, alt_index)
                    an = value_at(an_value, 0)
                    nhomalt = value_at(nhomalt_value, alt_index)
                    row = (
                        f"{chrom}\t{pos}\t{ref}\t{alt}\t"
                        f"{format_float(af)}\t{format_float(popmax)}\t"
                        f"{format_float(ac)}\t{format_float(an)}\t{format_float(nhomalt)}\t"
                        f"{source_dataset}\t{gnomad_version}\t{filters or '.'}\n"
                    )
                    bgzip_process.stdin.write(row.encode("utf-8"))
                    rows += 1
                if records % PROGRESS_INTERVAL == 0:
                    elapsed = time.time() - started
                    print(
                        f"[progress] {input_file.name}: records={records:,} rows={rows:,} "
                        f"elapsed={elapsed / 60:.1f} min",
                        flush=True,
                    )
        finally:
            bgzip_process.stdin.close()

        bgzip_stderr = bgzip_process.stderr.read().decode("utf-8", errors="replace") if bgzip_process.stderr else ""
        bgzip_return_code = bgzip_process.wait()
        if bgzip_return_code != 0:
            bcftools_process.kill()
            raise RuntimeError(f"bgzip failed for {input_file}: {bgzip_stderr}")

    bcftools_stderr = bcftools_process.stderr.read().decode("utf-8", errors="replace") if bcftools_process.stderr else ""
    bcftools_return_code = bcftools_process.wait()
    if bcftools_return_code != 0:
        raise RuntimeError(f"bcftools query failed for {input_file}: {bcftools_stderr}")

    if output_file.exists():
        output_file.unlink()
    temporary_file.rename(output_file)
    run_checked([tabix, "-f", "-s", "1", "-b", "2", "-e", "2", str(output_file)])
    elapsed = time.time() - started
    print(
        f"[done] {output_file} records={records:,} rows={rows:,} elapsed={elapsed / 60:.1f} min",
        flush=True,
    )


def max_frequency_from_query_values(values: Iterable[str], alt_index: int) -> Tuple[Optional[float], Optional[str]]:
    maximum: Optional[float] = None
    maximum_field: Optional[str] = None
    for field, raw in zip(OFFICIAL_AF_FIELDS, values):
        value = value_at(raw, alt_index)
        if value is None or value < 0.0 or value > 1.0:
            continue
        if maximum is None or value > maximum:
            maximum = value
            maximum_field = field
    return maximum, maximum_field


def build_one_by_scanning_all_af_fields(input_file: pathlib.Path, output_dir: pathlib.Path, bgzip: str, tabix: str, force: bool) -> None:
    output_file = output_dir / compact_output_name(input_file)
    index_file = output_file.with_name(output_file.name + ".tbi")
    if output_file.exists() and index_file.exists() and not force:
        print(f"[skip] {output_file} already exists", flush=True)
        return

    output_dir.mkdir(parents=True, exist_ok=True)
    temporary_file = output_file.with_name(output_file.name + ".tmp")
    if temporary_file.exists():
        temporary_file.unlink()

    print(f"[start] {input_file} -> {output_file}", flush=True)
    started = time.time()
    records = 0
    rows = 0
    with temporary_file.open("wb") as compressed_output:
        bgzip_process = subprocess.Popen(
            [bgzip, "-c"],
            stdin=subprocess.PIPE,
            stdout=compressed_output,
            stderr=subprocess.PIPE,
        )
        assert bgzip_process.stdin is not None
        try:
            for line in iter_vcf_records(input_file):
                columns = line.split("\t")
                if len(columns) < 8:
                    continue
                records += 1
                chrom, pos, _identifier, ref, alt_field = columns[:5]
                info = parse_info(columns[7])
                for alt_index, alt in enumerate(alt_field.split(",")):
                    af = value_at(info.get("AF"), alt_index)
                    popmax = value_at(info.get("AF_popmax"), alt_index)
                    ac = value_at(info.get("AC"), alt_index)
                    an = value_at(info.get("AN"), 0)
                    nhomalt = value_at(info.get("nhomalt"), alt_index)
                    row = (
                        f"{chrom}\t{pos}\t{ref}\t{alt}\t"
                        f"{format_float(af)}\t{format_float(popmax)}\t"
                        f"{format_float(ac)}\t{format_float(an)}\t{format_float(nhomalt)}\t"
                        f"{source_dataset_from_file(input_file)}\t{version_from_file(input_file)}\t"
                        f"{columns[6] or '.'}\n"
                    )
                    bgzip_process.stdin.write(row.encode("utf-8"))
                    rows += 1
                if records % PROGRESS_INTERVAL == 0:
                    elapsed = time.time() - started
                    print(
                        f"[progress] {input_file.name}: records={records:,} rows={rows:,} "
                        f"elapsed={elapsed / 60:.1f} min",
                        flush=True,
                    )
        finally:
            bgzip_process.stdin.close()
        stderr = bgzip_process.stderr.read().decode("utf-8", errors="replace") if bgzip_process.stderr else ""
        return_code = bgzip_process.wait()
        if return_code != 0:
            raise RuntimeError(f"bgzip failed for {input_file}: {stderr}")

    if output_file.exists():
        output_file.unlink()
    temporary_file.rename(output_file)
    run_checked([tabix, "-f", "-s", "1", "-b", "2", "-e", "2", str(output_file)])
    elapsed = time.time() - started
    print(
        f"[done] {output_file} records={records:,} rows={rows:,} elapsed={elapsed / 60:.1f} min",
        flush=True,
    )


def main() -> int:
    args = parse_args()
    if args.input_dir is None:
        print("gnomAD input directory is required: pass --input-dir or set GNOMAD_ROOT.", file=sys.stderr)
        return 2
    input_dir = pathlib.Path(args.input_dir)
    output_dir = pathlib.Path(args.output_dir) if args.output_dir else input_dir / "compact"
    selected = {normalize_chromosome(value) for value in args.chromosomes or []}
    files = sorted(input_dir.glob("gnomad.exomes.v4.1.sites.chr*.vcf.bgz"))
    if selected:
        files = [path for path in files if chromosome_from_file(path) in selected]
    if not files:
        print("No gnomAD input files found.", file=sys.stderr)
        return 2
    if shutil.which(args.bcftools) is None and not pathlib.Path(args.bcftools).exists():
        print(f"bcftools not found: {args.bcftools}", file=sys.stderr)
        return 2
    if shutil.which(args.bgzip) is None and not pathlib.Path(args.bgzip).exists():
        print(f"bgzip not found: {args.bgzip}", file=sys.stderr)
        return 2
    if shutil.which(args.tabix) is None and not pathlib.Path(args.tabix).exists():
        print(f"tabix not found: {args.tabix}", file=sys.stderr)
        return 2

    for input_file in files:
        if args.scan_all_af_fields:
            build_one_by_scanning_all_af_fields(input_file, output_dir, args.bgzip, args.tabix, args.force)
        else:
            build_one_with_bcftools(input_file, output_dir, args.bcftools, args.bgzip, args.tabix, args.force)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
