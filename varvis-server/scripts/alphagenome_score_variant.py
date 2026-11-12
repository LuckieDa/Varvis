#!/usr/bin/env python3
"""Score one de-identified GRCh38 candidate with the official AlphaGenome API."""

import argparse
import json
import math
import os
import sys

from alphagenome.data import genome
from alphagenome.models import dna_client
from alphagenome.models import variant_scorers


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chromosome", required=True)
    parser.add_argument("--position", required=True, type=int)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--alternate", required=True)
    parser.add_argument("--sequence-length", required=True, type=int)
    return parser.parse_args()


def chromosome_name(value):
    normalized = value.strip()
    if normalized.lower().startswith("chr"):
        return normalized
    if normalized.upper() in ("M", "MT"):
        return "chrM"
    return "chr" + normalized


def json_value(value):
    if value is None:
        return None
    if hasattr(value, "item"):
        value = value.item()
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def scorer_name(score):
    scorer = score.uns.get("variant_scorer")
    return getattr(scorer, "name", scorer.__class__.__name__)


def strongest_row(score):
    frame = variant_scorers.tidy_anndata(score, include_extended_metadata=True)
    if frame.empty or "raw_score" not in frame:
        return None
    numeric_scores = frame["raw_score"].astype(float)
    if numeric_scores.dropna().empty:
        return None
    row = frame.loc[numeric_scores.abs().idxmax()]
    signed_score = float(row["raw_score"])
    if signed_score > 0:
        direction = "INCREASE"
    elif signed_score < 0:
        direction = "DECREASE"
    else:
        direction = "UNCHANGED"
    result = {
        "outputType": json_value(row.get("output_type")),
        "scorer": scorer_name(score),
        "maximumAbsoluteScore": abs(signed_score),
        "signedScore": signed_score,
        "direction": direction,
    }
    for column in ("track_name", "gene_name", "gene_id", "ontology_curie", "ontology_name"):
        if column in row and json_value(row[column]) is not None:
            result[column] = json_value(row[column])
    return result


def main():
    args = parse_args()
    api_key = os.environ.get("ALPHAGENOME_API_KEY")
    if not api_key:
        raise RuntimeError("ALPHAGENOME_API_KEY is required")

    chromosome = chromosome_name(args.chromosome)
    variant = genome.Variant(
        chromosome=chromosome,
        position=args.position,
        reference_bases=args.reference.upper(),
        alternate_bases=args.alternate.upper(),
    )
    start = max(0, variant.start - args.sequence_length // 2)
    interval = genome.Interval(
        chromosome=chromosome,
        start=start,
        end=start + args.sequence_length,
    )

    model = dna_client.create(api_key)
    scores = model.score_variant(interval=interval, variant=variant)
    modality_summaries = []
    for score in scores:
        summary = strongest_row(score)
        if summary is not None:
            modality_summaries.append(summary)
    strongest_effect = max(
        modality_summaries,
        key=lambda summary: summary["maximumAbsoluteScore"],
        default=None,
    )
    print(json.dumps({
        "apiClient": "google-deepmind/alphagenome",
        "request": {
            "chromosome": chromosome,
            "position": args.position,
            "reference": args.reference.upper(),
            "alternate": args.alternate.upper(),
            "sequenceLength": args.sequence_length,
        },
        "modalitySummaries": modality_summaries,
        "strongestEffect": strongest_effect,
        "note": "Signed scores summarize ALT minus REF effects; no full track arrays are retained.",
    }, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
