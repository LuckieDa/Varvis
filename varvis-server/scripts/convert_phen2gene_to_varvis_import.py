#!/usr/bin/env python3
"""Convert Phen2Gene JSON or TSV output to the Varvis import TSV schema."""

import argparse
import csv
import json
import math
import re
import sys
import textwrap
from pathlib import Path


OUTPUT_COLUMNS = [
    "hpo_terms",
    "gene_symbol",
    "gene_id",
    "phenotype_score",
    "phenotype_rank",
    "source",
    "source_version",
    "matched_hpo_terms",
    "evidence",
    "status",
]
HPO_PATTERN = re.compile(r"^HP:\d{7}$", re.IGNORECASE)
DEFAULT_EVIDENCE = "Phen2Gene candidate gene prior"


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Convert Phen2Gene JSON, TSV, or standard .associated_gene_list "
            "output to the Varvis PHEN2GENE_IMPORT TSV schema."
        ),
        epilog=textwrap.dedent(
            """\
            Local CLI example:
              --source-version "Phen2Gene-1.2.3;hpo2gene-1.1.0"

            Input and output filesystem paths are used only for file access.
            They are not written into the generated TSV rows.
            """
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--input",
        required=True,
        help=(
            "Phen2Gene .json, .tsv, .txt, or standard "
            ".associated_gene_list input file."
        ),
    )
    parser.add_argument("--output", required=True, help="Varvis import TSV output file.")
    parser.add_argument(
        "--hpo-terms",
        required=True,
        help="Input HPO terms separated by semicolon, comma, whitespace, or newline.",
    )
    parser.add_argument(
        "--source-version",
        default="phen2gene-api-unknown-version",
        help=(
            "Phen2Gene tool/API and KB version recorded in every output row; "
            "for local runs use a value such as "
            "'Phen2Gene-1.2.3;hpo2gene-1.1.0'."
        ),
    )
    return parser.parse_args(argv)


def normalized_hpo_terms(value):
    terms = []
    seen = set()
    for token in re.split(r"[\s,;]+", value.strip()):
        normalized = token.upper()
        if not HPO_PATTERN.match(normalized) or normalized in seen:
            continue
        terms.append(normalized)
        seen.add(normalized)
    if not terms:
        raise ValueError("At least one valid HPO term is required.")
    return terms


def first_value(item, *names):
    for name in names:
        if name in item and item[name] is not None:
            return str(item[name]).strip()
    return ""


def normalized_gene_id(value):
    return value if value.isdigit() else ""


def parse_item(item, item_number, warnings):
    if not isinstance(item, dict):
        warnings.append("Item {} skipped: expected an object.".format(item_number))
        return None
    gene = first_value(item, "Gene", "gene", "gene_symbol", "Gene Symbol")
    gene_id = normalized_gene_id(first_value(item, "Gene ID", "gene_id", "ID", "id"))
    score_text = first_value(item, "Score", "score", "phenotype_score")
    rank_text = first_value(item, "Rank", "rank", "phenotype_rank")
    status = first_value(item, "Status", "status")
    if not gene or not score_text or not rank_text:
        warnings.append(
            "Item {} skipped: gene, score, or rank is missing.".format(item_number)
        )
        return None
    try:
        score = float(score_text)
        rank = int(float(rank_text))
    except ValueError:
        warnings.append("Item {} skipped: score or rank is invalid.".format(item_number))
        return None
    if not math.isfinite(score) or score < 0 or rank < 1:
        warnings.append("Item {} skipped: score or rank is out of range.".format(item_number))
        return None
    return {
        "gene_symbol": gene,
        "gene_id": gene_id,
        "score": score,
        "rank": rank,
        "status": status,
    }


def read_json(path):
    with path.open("r", encoding="utf-8-sig") as handle:
        payload = json.load(handle)
    if isinstance(payload, list):
        return payload, []
    if not isinstance(payload, dict):
        raise ValueError("Phen2Gene JSON root must be an object or list.")
    results = payload.get("results", [])
    errors = payload.get("errors", [])
    if not isinstance(results, list):
        raise ValueError("Phen2Gene JSON 'results' must be a list.")
    if not isinstance(errors, list):
        errors = [str(errors)]
    return results, [str(value) for value in errors if str(value).strip()]


def read_tsv(path):
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError("Phen2Gene TSV header is missing.")
        return list(reader), []


def load_items(path):
    suffix = path.suffix.lower()
    if suffix == ".json":
        return read_json(path)
    if suffix in (".tsv", ".txt", ".associated_gene_list"):
        return read_tsv(path)
    raise ValueError(
        "Unsupported input format; use .json, .tsv, .txt, or .associated_gene_list."
    )


def convert(input_path, output_path, hpo_terms, source_version):
    raw_items, upstream_warnings = load_items(input_path)
    warnings = list(upstream_warnings)
    parsed = []
    for index, item in enumerate(raw_items, start=1):
        value = parse_item(item, index, warnings)
        if value is not None:
            parsed.append(value)

    max_score = max((item["score"] for item in parsed), default=0.0)
    normalize_scores = max_score > 1.0
    joined_hpo = ";".join(hpo_terms)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=OUTPUT_COLUMNS,
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        for item in sorted(parsed, key=lambda value: (value["rank"], -value["score"])):
            score = item["score"] / max_score if normalize_scores else item["score"]
            writer.writerow(
                {
                    "hpo_terms": joined_hpo,
                    "gene_symbol": item["gene_symbol"],
                    "gene_id": item["gene_id"],
                    "phenotype_score": format(score, ".12g"),
                    "phenotype_rank": item["rank"],
                    "source": "PHEN2GENE_IMPORT",
                    "source_version": source_version,
                    "matched_hpo_terms": joined_hpo,
                    "evidence": DEFAULT_EVIDENCE,
                    "status": item["status"],
                }
            )
    return {
        "input_items": len(raw_items),
        "output_rows": len(parsed),
        "skipped_items": len(raw_items) - len(parsed),
        "score_strategy": (
            "normalized_by_max_input_score" if normalize_scores else "preserved_0_to_1"
        ),
        "warnings": warnings,
    }


def main(argv=None):
    args = parse_args(argv)
    try:
        hpo_terms = normalized_hpo_terms(args.hpo_terms)
        summary = convert(
            Path(args.input),
            Path(args.output),
            hpo_terms,
            args.source_version.strip() or "phen2gene-api-unknown-version",
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print("Conversion failed: {}".format(error), file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=True, sort_keys=True))
    for warning in summary["warnings"]:
        print("Warning: {}".format(warning), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
