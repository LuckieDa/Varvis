#!/usr/bin/env python3
"""Validate and optionally normalize Varvis EXOMISER_BASELINE import TSV files."""

import argparse
import csv
import json
import math
import re
import sys
import textwrap
from pathlib import Path


CANONICAL_COLUMNS = [
    "case_id",
    "hpo_terms",
    "gene_symbol",
    "gene_id",
    "variant_id",
    "chrom",
    "pos",
    "ref",
    "alt",
    "exomiser_gene_rank",
    "exomiser_variant_rank",
    "exomiser_score",
    "exomiser_gene_score",
    "exomiser_variant_score",
    "disease_id",
    "disease_label",
    "mode_of_inheritance",
    "matched_phenotypes",
    "source",
    "source_version",
    "genome_build",
    "variant_match_status",
    "evidence",
    "warnings",
]

ALLOWED_VARIANT_MATCH_STATUSES = {
    "MATCHED",
    "UNMATCHED",
    "GENE_ONLY",
    "UNKNOWN",
    "BUILD_OR_NORMALIZATION_MISMATCH",
}
RANK_FIELDS = ("exomiser_gene_rank", "exomiser_variant_rank")
SCORE_FIELDS = (
    "exomiser_score",
    "exomiser_gene_score",
    "exomiser_variant_score",
)
REQUIRED_FIELDS = (
    "case_id",
    "hpo_terms",
    "source",
    "source_version",
    "genome_build",
    "variant_match_status",
)
HPO_PATTERN = re.compile(r"^HP:\d{7}$", re.IGNORECASE)
LOCAL_PATH_PATTERNS = (
    re.compile(r"[A-Za-z]:[\\/][^\s\t]*"),
    re.compile(r"(^|[\s;])/(?:mnt/[a-z]/|home/|Users/)[^\s\t]*", re.IGNORECASE),
)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Validate a Varvis canonical EXOMISER_BASELINE import TSV. "
            "This does not read Exomiser data files and does not run Exomiser."
        ),
        epilog=textwrap.dedent(
            """\
            Example:
              python varvis-server/scripts/validate_exomiser_baseline_import.py \\
                --input varvis-server/scripts/fixtures/exomiser_baseline_synthetic.tsv

            The canonical TSV is an internal Varvis baseline import format, not
            raw Exomiser JSON/TSV/HTML output. Synthetic fixtures are not real
            Exomiser output and must not be described as resource validation.
            """
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--input", required=True, help="Canonical import TSV file.")
    parser.add_argument(
        "--output",
        help=(
            "Optional normalized TSV output path. Only valid rows are written; "
            "the command still exits non-zero if invalid rows were found."
        ),
    )
    return parser.parse_args(argv)


def is_blank(value):
    return value is None or str(value).strip() == ""


def normalize_hpo_terms(value):
    terms = []
    seen = set()
    for token in re.split(r"[\s,;]+", str(value).strip()):
        if not token:
            continue
        normalized = token.upper()
        if not HPO_PATTERN.match(normalized):
            raise ValueError("invalid HPO term '{}'".format(token))
        if normalized not in seen:
            terms.append(normalized)
            seen.add(normalized)
    if not terms:
        raise ValueError("hpo_terms must include at least one HPO ID")
    return ";".join(terms)


def validate_positive_integer(value, field_name):
    if is_blank(value):
        return None
    try:
        parsed = int(str(value).strip())
    except ValueError:
        raise ValueError("{} must be a positive integer or empty".format(field_name))
    if parsed < 1:
        raise ValueError("{} must be a positive integer or empty".format(field_name))
    return str(parsed)


def validate_number(value, field_name):
    if is_blank(value):
        return None
    try:
        parsed = float(str(value).strip())
    except ValueError:
        raise ValueError("{} must be numeric or empty".format(field_name))
    if not math.isfinite(parsed):
        raise ValueError("{} must be finite or empty".format(field_name))
    return format(parsed, ".12g")


def contains_local_absolute_path(value):
    text = str(value)
    return any(pattern.search(text) for pattern in LOCAL_PATH_PATTERNS)


def has_variant_coordinates(row):
    return any(not is_blank(row.get(field)) for field in ("chrom", "pos", "ref", "alt"))


def validate_header(fieldnames):
    if fieldnames == CANONICAL_COLUMNS:
        return
    fieldnames = fieldnames or []
    missing = [name for name in CANONICAL_COLUMNS if name not in fieldnames]
    extra = [name for name in fieldnames if name not in CANONICAL_COLUMNS]
    if missing or extra:
        raise ValueError(
            "Header must match canonical schema exactly. Missing: {}; extra: {}".format(
                ",".join(missing) or "none",
                ",".join(extra) or "none",
            )
        )
    raise ValueError("Header columns must follow the canonical order.")


def normalized_row(row):
    cleaned = {name: (row.get(name) or "").strip() for name in CANONICAL_COLUMNS}
    for field in REQUIRED_FIELDS:
        if is_blank(cleaned[field]):
            raise ValueError("{} is required".format(field))
    for field, value in cleaned.items():
        if contains_local_absolute_path(value):
            raise ValueError("{} contains a local absolute path".format(field))

    cleaned["hpo_terms"] = normalize_hpo_terms(cleaned["hpo_terms"])
    cleaned["source"] = cleaned["source"].upper()
    if cleaned["source"] != "EXOMISER_BASELINE":
        raise ValueError("source must be EXOMISER_BASELINE")

    cleaned["variant_match_status"] = cleaned["variant_match_status"].upper()
    if cleaned["variant_match_status"] not in ALLOWED_VARIANT_MATCH_STATUSES:
        raise ValueError(
            "variant_match_status must be one of {}".format(
                ",".join(sorted(ALLOWED_VARIANT_MATCH_STATUSES))
            )
        )

    for field in RANK_FIELDS:
        value = validate_positive_integer(cleaned[field], field)
        cleaned[field] = value or ""
    for field in SCORE_FIELDS:
        value = validate_number(cleaned[field], field)
        cleaned[field] = value or ""
    if not is_blank(cleaned["pos"]):
        cleaned["pos"] = validate_positive_integer(cleaned["pos"], "pos") or ""

    if cleaned["variant_match_status"] == "GENE_ONLY":
        if is_blank(cleaned["gene_symbol"]):
            raise ValueError("gene_symbol is required for GENE_ONLY rows")
        if is_blank(cleaned["exomiser_gene_rank"]):
            raise ValueError("exomiser_gene_rank is required for GENE_ONLY rows")
    elif has_variant_coordinates(cleaned):
        for field in ("chrom", "pos", "ref", "alt"):
            if is_blank(cleaned[field]):
                raise ValueError("{} is required for variant-level rows".format(field))
    elif is_blank(cleaned["gene_symbol"]):
        raise ValueError("gene_symbol is required when variant coordinates are absent")

    cleaned["ref"] = cleaned["ref"].upper()
    cleaned["alt"] = cleaned["alt"].upper()
    return cleaned


def validate_file(input_path, output_path=None):
    valid_rows = []
    row_errors = []
    total_rows = 0
    with input_path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        validate_header(reader.fieldnames)
        for index, row in enumerate(reader, start=2):
            total_rows += 1
            try:
                valid_rows.append(normalized_row(row))
            except ValueError as error:
                row_errors.append("line {}: {}".format(index, error))

    unique_genes = {
        row["gene_symbol"] for row in valid_rows if not is_blank(row["gene_symbol"])
    }
    variant_level_rows = sum(1 for row in valid_rows if has_variant_coordinates(row))
    gene_only_rows = len(valid_rows) - variant_level_rows
    summary = {
        "total_rows": total_rows,
        "valid_rows": len(valid_rows),
        "warning_rows": sum(1 for row in valid_rows if not is_blank(row["warnings"])),
        "invalid_rows": len(row_errors),
        "unique_genes": len(unique_genes),
        "variant_level_rows": variant_level_rows,
        "gene_only_rows": gene_only_rows,
        "errors": row_errors,
    }

    if output_path is not None:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(
                handle,
                fieldnames=CANONICAL_COLUMNS,
                delimiter="\t",
                lineterminator="\n",
            )
            writer.writeheader()
            writer.writerows(valid_rows)
    return summary


def main(argv=None):
    args = parse_args(argv)
    try:
        summary = validate_file(
            Path(args.input),
            Path(args.output) if args.output else None,
        )
    except (OSError, ValueError) as error:
        print("Validation failed: {}".format(error), file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=True, sort_keys=True))
    for error in summary["errors"]:
        print("Invalid: {}".format(error), file=sys.stderr)
    return 1 if summary["invalid_rows"] else 0


if __name__ == "__main__":
    sys.exit(main())
