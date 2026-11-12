#!/usr/bin/env python3
"""Derive a deterministic protein-coding exon-union BED from GENCODE-like GTF."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import sys
import uuid
from collections import OrderedDict, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple


SCRIPT_VERSION = "exome-bed-derivation-v1"
TARGET_CONTIGS = [str(value) for value in range(1, 23)] + ["X"]
GTF_CONTIGS = {f"chr{value}": value for value in TARGET_CONTIGS}
GTF_CONTIGS.update({"chrY": "Y", "chrM": "MT", "chrMT": "MT"})
NUMERIC_CONTIGS = set(TARGET_CONTIGS) | {"Y", "M", "MT"}
ATTRIBUTE_NAME = re.compile(r"^([A-Za-z0-9_.-]+)\s+(?:\"([^\"]*)\"|([^;]+))$")


class DerivationError(Exception):
    """A structured, user-actionable derivation failure."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def reject_incomplete_input(path: Path) -> None:
    if path.name.lower().endswith((".part", ".crdownload", ".download")):
        raise DerivationError("INCOMPLETE_INPUT", "Input is marked as an incomplete download.")


def read_text(path: Path):
    if path.name.lower().endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", errors="replace", newline="")
    return path.open("rt", encoding="utf-8", errors="replace", newline="")


def atomic_write(path: Path, content: str) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("wt", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gtf", required=True, type=Path)
    parser.add_argument("--contigs", required=True, type=Path, help="FAI or two-column contig length file")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--assembly", default="GRCh38")
    parser.add_argument("--gene-model", default="GENCODE")
    parser.add_argument("--gencode-release", default="v47")
    parser.add_argument("--feature", default="exon")
    parser.add_argument("--padding", default=20, type=int)
    parser.add_argument("--input-contig-style", choices=("gencode", "numeric"), required=True)
    parser.add_argument("--output-contig-style", choices=("numeric", "gencode"), default="numeric")
    parser.add_argument("--generated-at", help="UTC ISO-8601 value for reproducible tests")
    parser.add_argument("--synthetic", action="store_true")
    return parser.parse_args()


def parse_contig_lengths(path: Path) -> Tuple[Dict[str, int], str]:
    if not path.is_file():
        raise DerivationError("CONTIG_LENGTHS_MISSING", "Contig length source does not exist.")
    lengths: Dict[str, int] = {}
    try:
        with path.open("rt", encoding="utf-8", errors="strict") as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                fields = line.rstrip("\r\n").split("\t")
                if len(fields) < 2:
                    raise DerivationError("CONTIG_LENGTHS_MALFORMED", f"Malformed contig length row {line_number}.")
                try:
                    length = int(fields[1])
                except ValueError as error:
                    raise DerivationError("CONTIG_LENGTHS_MALFORMED", f"Invalid contig length row {line_number}.") from error
                if length <= 0 or fields[0] in lengths or not fields[0]:
                    raise DerivationError("CONTIG_LENGTHS_MALFORMED", f"Invalid or duplicate contig row {line_number}.")
                lengths[fields[0]] = length
    except UnicodeError as error:
        raise DerivationError("CONTIG_LENGTHS_UNREADABLE", "Contig length source is not valid UTF-8.") from error
    return lengths, path.name


def parse_attributes(raw: str) -> Dict[str, str]:
    attributes: Dict[str, str] = {}
    malformed = False
    for segment in raw.split(";"):
        segment = segment.strip()
        if not segment:
            continue
        match = ATTRIBUTE_NAME.match(segment)
        if not match:
            malformed = True
            continue
        attributes[match.group(1)] = (match.group(2) or match.group(3) or "").strip()
    if malformed:
        raise ValueError("malformed GTF attribute")
    return attributes


def map_contig(raw: str, style: str) -> Tuple[Optional[str], str]:
    if style == "gencode":
        if raw in GTF_CONTIGS:
            mapped = GTF_CONTIGS[raw]
        elif raw.startswith("chr"):
            return None, "ALTERNATE_OR_UNSUPPORTED_CONTIG"
        else:
            return None, "INPUT_CONTIG_STYLE_MISMATCH"
    else:
        if raw in NUMERIC_CONTIGS:
            mapped = "MT" if raw in {"M", "MT"} else raw
        else:
            return None, "INPUT_CONTIG_STYLE_MISMATCH"
    if mapped == "Y":
        return None, "Y_NOT_APPLICABLE"
    if mapped == "MT":
        return None, "MT_SOURCE_UNAVAILABLE"
    if mapped not in TARGET_CONTIGS:
        return None, "ALTERNATE_OR_UNSUPPORTED_CONTIG"
    return mapped, ""


def output_contig(contig: str, style: str) -> str:
    return f"chr{contig}" if style == "gencode" else contig


def interval_sort_key(contig: str, start: int, end: int) -> Tuple[int, int, int]:
    return (TARGET_CONTIGS.index(contig), start, end)


def merge_intervals(intervals: Iterable[Tuple[int, int]]) -> List[Tuple[int, int]]:
    ordered = sorted(intervals)
    merged: List[List[int]] = []
    for start, end in ordered:
        if not merged or start > merged[-1][1]:
            merged.append([start, end])
        else:
            merged[-1][1] = max(merged[-1][1], end)
    return [(start, end) for start, end in merged]


def parameter_fingerprint(parameters: dict) -> str:
    encoded = json.dumps(parameters, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def existing_output_is_compatible(args: argparse.Namespace, manifest_path: Path, output_path: Path, expected: dict) -> bool:
    if not manifest_path.is_file() or not output_path.is_file():
        return False
    try:
        existing = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return (
        existing.get("parameterFingerprint") == expected["parameterFingerprint"]
        and existing.get("source", {}).get("sha256") == expected["source"]["sha256"]
        and existing.get("output", {}).get("sha256") == sha256_file(output_path)
    )


def derive(args: argparse.Namespace) -> dict:
    if args.assembly != "GRCh38":
        raise DerivationError("ASSEMBLY_UNSUPPORTED", "This frozen BED contract only supports GRCh38.")
    if args.padding < 0:
        raise DerivationError("INVALID_PADDING", "Padding must be non-negative.")
    if args.feature != "exon":
        raise DerivationError("INVALID_FEATURE", "The frozen feature type is exon.")
    reject_incomplete_input(args.gtf)
    reject_incomplete_input(args.contigs)
    if not args.gtf.is_file():
        raise DerivationError("GTF_MISSING", "GTF source does not exist.")

    contig_lengths, contig_source_filename = parse_contig_lengths(args.contigs)
    source_sha = sha256_file(args.gtf)
    contig_source_sha = sha256_file(args.contigs)
    parameters = {
        "assembly": args.assembly,
        "geneModel": args.gene_model,
        "gencodeRelease": args.gencode_release,
        "feature": args.feature,
        "padding": args.padding,
        "inputContigStyle": args.input_contig_style,
        "outputContigStyle": args.output_contig_style,
        "chromosomeScope": TARGET_CONTIGS,
    }
    fingerprint = parameter_fingerprint(parameters)
    expected_existing = {
        "parameterFingerprint": fingerprint,
        "source": {"sha256": source_sha},
    }
    if args.output.exists() or args.manifest.exists():
        if existing_output_is_compatible(args, args.manifest, args.output, expected_existing):
            return json.loads(args.manifest.read_text(encoding="utf-8"))
        raise DerivationError("OUTPUT_EXISTS_MISMATCH", "Existing output or manifest is not compatible; refusing silent overwrite.")

    intervals: Dict[str, List[Tuple[int, int]]] = defaultdict(list)
    stats = OrderedDict([
        ("totalRows", 0),
        ("exonRows", 0),
        ("proteinCodingTranscriptExonRows", 0),
        ("nonProteinCodingRows", 0),
        ("missingTranscriptTypeRows", 0),
        ("nonTargetContigRows", 0),
        ("malformedRows", 0),
        ("clippedStartIntervals", 0),
        ("clippedEndIntervals", 0),
        ("discardedEmptyIntervals", 0),
        ("finalIntervalCount", 0),
        ("coveredBases", 0),
    ])
    exclusions = OrderedDict([
        ("Y_NOT_APPLICABLE", 0),
        ("MT_SOURCE_UNAVAILABLE", 0),
        ("ALTERNATE_OR_UNSUPPORTED_CONTIG", 0),
        ("INPUT_CONTIG_STYLE_MISMATCH", 0),
        ("MALFORMED_ROW", 0),
        ("MALFORMED_ATTRIBUTES", 0),
        ("MISSING_TRANSCRIPT_TYPE", 0),
        ("NON_PROTEIN_CODING_TRANSCRIPT", 0),
        ("INVALID_INTERVAL", 0),
    ])

    try:
        with read_text(args.gtf) as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip() or line.startswith("#"):
                    continue
                stats["totalRows"] += 1
                fields = line.rstrip("\r\n").split("\t")
                if len(fields) != 9:
                    stats["malformedRows"] += 1
                    exclusions["MALFORMED_ROW"] += 1
                    continue
                if fields[2] != args.feature:
                    continue
                stats["exonRows"] += 1
                try:
                    gtf_start = int(fields[3])
                    gtf_end = int(fields[4])
                    if gtf_start < 1 or gtf_end < gtf_start:
                        raise ValueError
                except ValueError:
                    stats["malformedRows"] += 1
                    exclusions["INVALID_INTERVAL"] += 1
                    continue
                try:
                    attributes = parse_attributes(fields[8])
                except ValueError:
                    stats["malformedRows"] += 1
                    exclusions["MALFORMED_ATTRIBUTES"] += 1
                    continue
                mapped, reason = map_contig(fields[0], args.input_contig_style)
                if mapped is None:
                    stats["nonTargetContigRows"] += 1
                    exclusions[reason] = exclusions.get(reason, 0) + 1
                    continue
                transcript_type = attributes.get("transcript_type")
                if transcript_type is None:
                    transcript_type = attributes.get("transcript_biotype")
                if transcript_type is None:
                    stats["missingTranscriptTypeRows"] += 1
                    exclusions["MISSING_TRANSCRIPT_TYPE"] += 1
                    continue
                if transcript_type != "protein_coding":
                    stats["nonProteinCodingRows"] += 1
                    exclusions["NON_PROTEIN_CODING_TRANSCRIPT"] += 1
                    continue
                stats["proteinCodingTranscriptExonRows"] += 1
                start = gtf_start - 1 - args.padding
                end = gtf_end + args.padding
                contig_length = contig_lengths.get(fields[0])
                if contig_length is None:
                    contig_length = contig_lengths.get(output_contig(mapped, args.output_contig_style))
                if contig_length is None:
                    raise DerivationError("CONTIG_LENGTH_MISSING", f"No explicit contig length for {fields[0]}.")
                if start < 0:
                    start = 0
                    stats["clippedStartIntervals"] += 1
                if end > contig_length:
                    end = contig_length
                    stats["clippedEndIntervals"] += 1
                if start >= end:
                    stats["discardedEmptyIntervals"] += 1
                    exclusions["INVALID_INTERVAL"] += 1
                    continue
                intervals[mapped].append((start, end))
    except OSError as error:
        raise DerivationError("GTF_UNREADABLE", "GTF source could not be read.") from error

    if stats["exonRows"] == 0 or stats["proteinCodingTranscriptExonRows"] == 0:
        raise DerivationError("GTF_UNPARSEABLE", "GTF did not contain a usable protein-coding exon row.")

    merged_by_contig: Dict[str, List[Tuple[int, int]]] = {}
    per_chromosome = OrderedDict()
    bed_lines: List[str] = []
    for contig in TARGET_CONTIGS:
        merged = merge_intervals(intervals.get(contig, []))
        merged_by_contig[contig] = merged
        input_count = len(intervals.get(contig, []))
        covered = sum(end - start for start, end in merged)
        per_chromosome[output_contig(contig, args.output_contig_style)] = OrderedDict([
            ("inputProteinCodingExonCount", input_count),
            ("mergedIntervalCount", len(merged)),
            ("coveredBases", covered),
        ])
        for start, end in merged:
            bed_lines.append(f"{output_contig(contig, args.output_contig_style)}\t{start}\t{end}\n")
    stats["finalIntervalCount"] = len(bed_lines)
    stats["coveredBases"] = sum(value["coveredBases"] for value in per_chromosome.values())
    if not bed_lines:
        raise DerivationError("NO_OUTPUT_INTERVALS", "No target intervals were produced.")

    output_content = "".join(bed_lines)
    output_sha = hashlib.sha256(output_content.encode("utf-8")).hexdigest()
    generated_at = args.generated_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    script_sha = sha256_file(Path(__file__))
    manifest = OrderedDict([
        ("schemaVersion", "benchmark-exome-bed-manifest-v1"),
        ("resourceKind", "SYNTHETIC" if args.synthetic else "DERIVED_BENCHMARK_RESOURCE"),
        ("validationStatus", "SYNTHETIC_VALIDATED" if args.synthetic else "DERIVED_NOT_REAL_RESOURCE_ACCEPTANCE"),
        ("assembly", args.assembly),
        ("geneModel", args.gene_model),
        ("gencodeRelease", args.gencode_release),
        ("source", OrderedDict([
            ("filename", args.gtf.name),
            ("size", args.gtf.stat().st_size),
            ("sha256", source_sha),
        ])),
        ("contigLengthsSource", OrderedDict([
            ("filename", contig_source_filename),
            ("sha256", contig_source_sha),
        ])),
        ("transcriptSelection", "feature=exon; transcript_type=protein_coding; fallback=transcript_biotype"),
        ("feature", args.feature),
        ("utrIncluded", True),
        ("paddingBp", args.padding),
        ("inputContigStyle", args.input_contig_style),
        ("outputContigStyle", args.output_contig_style),
        ("chromosomeScope", TARGET_CONTIGS),
        ("exclusionPolicy", ["Y_NOT_APPLICABLE", "MT_SOURCE_UNAVAILABLE", "ALTERNATE_OR_UNSUPPORTED_CONTIG", "NON_PROTEIN_CODING_TRANSCRIPT", "MISSING_TRANSCRIPT_TYPE"]),
        ("statistics", stats),
        ("exclusions", exclusions),
        ("perChromosome", per_chromosome),
        ("output", OrderedDict([
            ("filename", args.output.name),
            ("size", len(output_content.encode("utf-8"))),
            ("sha256", output_sha),
            ("format", "BED3; 0-based; half-open; UTF-8 LF"),
        ])),
        ("scriptVersion", SCRIPT_VERSION),
        ("scriptSha256", script_sha),
        ("parameters", parameters),
        ("parameterFingerprint", fingerprint),
        ("generatedAtUtc", generated_at),
    ])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    atomic_write(args.output, output_content)
    atomic_write(args.manifest, json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    return manifest


def main() -> int:
    args = parse_arguments()
    try:
        manifest = derive(args)
        print(json.dumps(manifest, ensure_ascii=False, indent=2))
        return 0
    except DerivationError as error:
        print(json.dumps(OrderedDict([
            ("schemaVersion", "benchmark-exome-bed-manifest-v1"),
            ("status", "FAILED"),
            ("error", OrderedDict([("code", error.code), ("message", str(error))])),
        ]), ensure_ascii=False, indent=2))
        return 1
    except (OSError, ValueError) as error:
        print(json.dumps(OrderedDict([
            ("schemaVersion", "benchmark-exome-bed-manifest-v1"),
            ("status", "FAILED"),
            ("error", OrderedDict([("code", "UNEXPECTED_ERROR"), ("message", str(error))])),
        ]), ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
