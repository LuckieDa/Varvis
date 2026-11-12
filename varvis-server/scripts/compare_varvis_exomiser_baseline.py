#!/usr/bin/env python3
"""Build a Varvis versus Exomiser technical comparison report."""

import argparse
import csv
import hashlib
import json
import re
import sys
import textwrap
from pathlib import Path

import validate_exomiser_baseline_import as exomiser_validator


SCHEMA_VERSION = "VARVIS_EXOMISER_TECHNICAL_COMPARISON_V1"
RANK_NOT_COMPUTABLE = "NOT_COMPUTABLE_NO_EXOMISER_VARIANT_RANK"
SAFETY_NOTE = (
    "This is a technical software comparison smoke test. HG001/NA12878 is a "
    "public reference sample, not a patient case in this task. The HPO terms "
    "are test-only inputs and are not clinical phenotypes of HG001. There is no "
    "gold-standard causal gene or variant, so no hit@k, sensitivity, "
    "specificity, or diagnostic accuracy can be calculated. This report is not "
    "a diagnosis, not an ACMG classification, and not pathogenicity evidence. "
    "A high rank does not mean pathogenic, and a low rank or no match does not "
    "mean benign."
)

DISCORDANCE_CATEGORIES = {
    "VARVIS_HIGH_EXOMISER_HIGH",
    "VARVIS_HIGH_EXOMISER_LOW",
    "VARVIS_LOW_EXOMISER_HIGH",
    "BOTH_LOW",
    "UNMATCHED_VARIANT",
    "BUILD_OR_NORMALIZATION_MISMATCH",
    "INSUFFICIENT_PHENOTYPE",
    "NO_TARGET_TECHNICAL_COMPARISON",
}

TECHNICAL_DISCORDANCE_CATEGORIES = (
    "EXACT_VARIANT_MATCH",
    "GENE_MATCH_VARIANT_DIFFERENT",
    "VARVIS_ONLY_VARIANT",
    "EXOMISER_ONLY_VARIANT",
    "VARVIS_GENE_UNMAPPED",
    "EXOMISER_GENE_ONLY",
    "RANK_NOT_COMPARABLE",
    "PHENOTYPE_RERANK_NOT_APPLIED",
)

LOCAL_PATH_PATTERNS = (
    re.compile(r"[A-Za-z]:[\\/][^\s\t]*"),
    re.compile(r"(^|[\s;])/(?:mnt/[a-z]/|home/|Users/)[^\s\t]*", re.IGNORECASE),
)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Compare Varvis ranking JSON with canonical EXOMISER_BASELINE TSV. "
            "This command does not run Varvis or Exomiser and does not evaluate "
            "diagnostic accuracy."
        ),
        epilog=textwrap.dedent(
            """\
            Example:
              python varvis-server/scripts/compare_varvis_exomiser_baseline.py \\
                --varvis-json varvis_ranking.json \\
                --exomiser-tsv exomiser_baseline.tsv \\
                --case-id HG001 \\
                --sample-id HG001 \\
                --assembly GRCh38 \\
                --hpo-terms HP:0001250,HP:0004322 \\
                --output-md comparison.md \\
                --output-json comparison.json \\
                --output-manifest comparison_manifest.json

            Target gene and target variant are optional. Omit them for the
            no-target technical smoke used when no clinical gold standard exists.
            """
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--varvis-json", required=True, help="Varvis ranking JSON.")
    parser.add_argument(
        "--exomiser-tsv",
        required=True,
        help="Canonical EXOMISER_BASELINE import TSV.",
    )
    parser.add_argument("--case-id", required=True, help="Case identifier to report.")
    parser.add_argument("--sample-id", help="Expected sample identifier.")
    parser.add_argument("--assembly", help="Expected genome assembly.")
    parser.add_argument(
        "--hpo-terms",
        help="Comma, semicolon, or whitespace separated expected HPO terms.",
    )
    parser.add_argument("--target-gene", help="Optional expected target gene symbol.")
    parser.add_argument(
        "--target-variant",
        help="Optional target variant as chrom:pos:ref:alt using GRCh38 coordinates.",
    )
    parser.add_argument("--top-k", type=int, default=10, help="Top-k cutoff, default 10.")
    parser.add_argument("--output-md", required=True, help="Markdown report output path.")
    parser.add_argument("--output-json", help="Optional JSON summary output path.")
    parser.add_argument("--output-manifest", help="Optional comparison manifest output path.")
    parser.add_argument(
        "--varvis-path-label",
        default="<VARVIS_RANKING_JSON>",
        help="Path label to write into report/manifest instead of a local absolute path.",
    )
    parser.add_argument(
        "--exomiser-path-label",
        default="<EXOMISER_BASELINE_TSV>",
        help="Path label to write into report/manifest instead of a local absolute path.",
    )
    return parser.parse_args(argv)


def is_blank(value):
    return value is None or str(value).strip() == ""


def contains_local_absolute_path(value):
    text = str(value)
    return any(pattern.search(text) for pattern in LOCAL_PATH_PATTERNS)


def assert_no_local_paths(value, context="value"):
    if isinstance(value, dict):
        for key, child in value.items():
            assert_no_local_paths(child, "{}.{}".format(context, key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            assert_no_local_paths(child, "{}[{}]".format(context, index))
    elif value is not None and contains_local_absolute_path(value):
        raise ValueError("{} contains a local absolute path".format(context))


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalized_gene(value):
    return str(value or "").strip().upper()


def normalized_chrom(value):
    text = str(value or "").strip()
    if text.lower().startswith("chr"):
        text = text[3:]
    return text.upper()


def normalized_variant(chrom, pos, ref, alt):
    if is_blank(chrom) or is_blank(pos) or is_blank(ref) or is_blank(alt):
        return None
    return (
        normalized_chrom(chrom),
        str(pos).strip(),
        str(ref).strip().upper(),
        str(alt).strip().upper(),
    )


def normalized_variant_identity(assembly, chrom, pos, ref, alt):
    variant = normalized_variant(chrom, pos, ref, alt)
    if variant is None:
        return None
    return (str(assembly or "").strip(),) + variant


def variant_identity_to_text(variant):
    if not variant:
        return ""
    return "{}:{}:{}:{}:{}".format(*variant)


def variant_to_text(variant):
    if not variant:
        return ""
    return "{}:{}:{}:{}".format(*variant)


def parse_hpo_terms(value):
    terms = []
    seen = set()
    for token in re.split(r"[\s,;]+", str(value or "").strip()):
        if not token:
            continue
        normalized = token.upper()
        if normalized not in seen:
            terms.append(normalized)
            seen.add(normalized)
    return terms


def parse_target_variant(value):
    if is_blank(value):
        return None
    parts = str(value).strip().split(":")
    if len(parts) != 4:
        raise ValueError("target variant must use chrom:pos:ref:alt format")
    return normalized_variant(parts[0], parts[1], parts[2], parts[3])


def first_value(mapping, *names):
    for name in names:
        if isinstance(mapping, dict) and name in mapping and not is_blank(mapping[name]):
            return mapping[name]
    return ""


def candidate_list_from_payload(payload):
    if isinstance(payload, list):
        return payload
    if not isinstance(payload, dict):
        raise ValueError("Varvis JSON root must be an object or list.")
    for key in ("candidates", "rankedCandidates", "variants", "results", "data"):
        value = payload.get(key)
        if isinstance(value, list):
            return value
    raise ValueError("Varvis JSON does not contain a candidate list.")


def load_json(path):
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def candidate_to_record(item, index, default_assembly="", require_gene=True):
    if not isinstance(item, dict):
        raise ValueError("Varvis candidate {} must be an object.".format(index))
    variant = item.get("variant") if isinstance(item.get("variant"), dict) else {}
    gene = first_value(
        item,
        "gene",
        "geneSymbol",
        "gene_symbol",
        "symbol",
    ) or first_value(variant, "gene", "geneSymbol", "gene_symbol")
    if require_gene and is_blank(gene):
        raise ValueError("Varvis candidate {} is missing gene.".format(index))
    rank_value = first_value(item, "rank", "varvisRank", "varvis_rank", "priorityRank")
    rank = int(rank_value) if not is_blank(rank_value) else index
    assembly = (
        first_value(item, "assembly", "genomeAssembly")
        or first_value(variant, "assembly", "genomeAssembly")
        or default_assembly
    )
    variant_tuple = normalized_variant(
        first_value(item, "chrom", "chromosome") or first_value(variant, "chrom", "chromosome"),
        first_value(item, "pos", "position", "start") or first_value(variant, "pos", "position", "start"),
        first_value(item, "ref") or first_value(variant, "ref"),
        first_value(item, "alt") or first_value(variant, "alt"),
    )
    variant_identity = normalized_variant_identity(
        assembly,
        first_value(item, "chrom", "chromosome") or first_value(variant, "chrom", "chromosome"),
        first_value(item, "pos", "position", "start") or first_value(variant, "pos", "position", "start"),
        first_value(item, "ref") or first_value(variant, "ref"),
        first_value(item, "alt") or first_value(variant, "alt"),
    )
    return {
        "gene": normalized_gene(gene),
        "display_gene": str(gene or "").strip(),
        "rank": rank,
        "tier": str(first_value(item, "tier", "classificationTier") or ""),
        "score": item.get("totalScore", item.get("score", item.get("rankingScore"))),
        "variant": variant_tuple,
        "variant_identity": variant_identity,
        "variant_id": str(
            first_value(item, "variant_id", "variantId")
            or first_value(variant, "variant_id", "variantId")
            or ""
        ),
        "phenotype_support": item.get("phenotypeSupport", item.get("phenotype_support")),
        "gene_mapping_status": item.get("geneMappingStatus")
        or ("MAPPED" if not is_blank(gene) else "MISSING_GENE_SYMBOL"),
    }


def load_varvis_payload(path):
    payload = load_json(path)
    assert_no_local_paths(payload, "varvis_json")
    if not isinstance(payload, dict):
        raw_candidates = candidate_list_from_payload(payload)
        mapped_items = raw_candidates
        unmapped_items = []
        metadata = {}
    else:
        mapped_items = candidate_list_from_payload(payload)
        unmapped_items = payload.get("unmappedCandidates") or []
        metadata = payload

    default_assembly = (
        first_value(metadata, "assembly", "genomeAssembly", "genomeBuild")
        if isinstance(metadata, dict)
        else ""
    )
    mapped = [
        candidate_to_record(item, index, default_assembly=default_assembly, require_gene=True)
        for index, item in enumerate(mapped_items, start=1)
    ]
    unmapped = [
        candidate_to_record(item, index, default_assembly=default_assembly, require_gene=False)
        for index, item in enumerate(unmapped_items, start=1)
    ]
    mapped.sort(key=lambda item: item["rank"])
    unmapped.sort(key=lambda item: item["rank"])
    return {
        "payload": payload,
        "metadata": metadata if isinstance(metadata, dict) else {},
        "mapped_candidates": mapped,
        "unmapped_candidates": unmapped,
    }


def load_varvis_candidates(path):
    return load_varvis_payload(path)["mapped_candidates"]


def load_exomiser_rows(path):
    validation = exomiser_validator.validate_file(path)
    if validation["invalid_rows"]:
        raise ValueError(
            "Exomiser TSV is invalid: {}".format("; ".join(validation["errors"]))
        )
    rows = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        exomiser_validator.validate_header(reader.fieldnames)
        for row in reader:
            cleaned = exomiser_validator.normalized_row(row)
            assembly = cleaned["genome_build"]
            rows.append(
                {
                    "case_id": cleaned["case_id"],
                    "gene": normalized_gene(cleaned["gene_symbol"]),
                    "display_gene": cleaned["gene_symbol"],
                    "gene_rank": int(cleaned["exomiser_gene_rank"])
                    if not is_blank(cleaned["exomiser_gene_rank"])
                    else None,
                    "variant_rank": int(cleaned["exomiser_variant_rank"])
                    if not is_blank(cleaned["exomiser_variant_rank"])
                    else None,
                    "score": cleaned["exomiser_score"],
                    "gene_score": cleaned["exomiser_gene_score"],
                    "variant_score": cleaned["exomiser_variant_score"],
                    "variant": normalized_variant(
                        cleaned["chrom"],
                        cleaned["pos"],
                        cleaned["ref"],
                        cleaned["alt"],
                    ),
                    "variant_identity": normalized_variant_identity(
                        assembly,
                        cleaned["chrom"],
                        cleaned["pos"],
                        cleaned["ref"],
                        cleaned["alt"],
                    ),
                    "variant_match_status": cleaned["variant_match_status"],
                    "source_version": cleaned["source_version"],
                    "genome_build": assembly,
                    "hpo_terms": cleaned["hpo_terms"],
                    "warnings": cleaned["warnings"],
                }
            )
    return rows


def first_rank_by_gene(items, rank_field):
    ranks = {}
    for item in items:
        gene = item["gene"]
        rank = item.get(rank_field)
        if gene and rank is not None:
            ranks[gene] = min(rank, ranks.get(gene, rank))
    return ranks


def first_by_variant(items, rank_field):
    values = {}
    for item in items:
        variant = item.get("variant")
        if variant is None:
            continue
        rank = item.get(rank_field)
        if variant not in values or (
            rank is not None and rank < values[variant].get(rank_field, rank)
        ):
            values[variant] = item
    return values


def first_by_variant_identity(items):
    values = {}
    for item in items:
        variant = item.get("variant_identity")
        if variant is None:
            continue
        rank = item.get("rank")
        if variant not in values or (rank is not None and rank < values[variant].get("rank", rank)):
            values[variant] = item
    return values


def top_genes(rank_by_gene, top_k):
    return [
        gene
        for gene, _rank in sorted(rank_by_gene.items(), key=lambda item: (item[1], item[0]))
        if _rank <= top_k
    ]


def jaccard(intersection_count, union_count):
    if union_count == 0:
        return None
    return intersection_count / union_count


def categorize(varvis_rank, exomiser_rank, top_k, target_variant_summary=None, exomiser_rows=None):
    if target_variant_summary:
        status = target_variant_summary.get("variant_match_status") or ""
        if status == "BUILD_OR_NORMALIZATION_MISMATCH":
            return "BUILD_OR_NORMALIZATION_MISMATCH"
        if (
            target_variant_summary.get("varvis_rank") is None
            or target_variant_summary.get("exomiser_variant_rank") is None
        ):
            return "UNMATCHED_VARIANT"
    if exomiser_rows and all(is_blank(row.get("gene")) for row in exomiser_rows):
        return "INSUFFICIENT_PHENOTYPE"
    varvis_high = varvis_rank is not None and varvis_rank <= top_k
    exomiser_high = exomiser_rank is not None and exomiser_rank <= top_k
    if varvis_high and exomiser_high:
        return "VARVIS_HIGH_EXOMISER_HIGH"
    if varvis_high and not exomiser_high:
        return "VARVIS_HIGH_EXOMISER_LOW"
    if not varvis_high and exomiser_high:
        return "VARVIS_LOW_EXOMISER_HIGH"
    return "BOTH_LOW"


def validate_input_equivalence(varvis_info, exomiser_rows, expected_sample=None, expected_assembly=None, expected_hpo_terms=None):
    errors = []
    metadata = varvis_info["metadata"]
    varvis_sample = metadata.get("sampleId") or metadata.get("sample_id")
    varvis_assembly = metadata.get("assembly") or metadata.get("genomeBuild")
    varvis_hpo = metadata.get("hpoTerms") or []
    varvis_hpo = parse_hpo_terms(";".join(varvis_hpo) if isinstance(varvis_hpo, list) else varvis_hpo)

    exomiser_samples = sorted({row["case_id"] for row in exomiser_rows})
    exomiser_assemblies = sorted({row["genome_build"] for row in exomiser_rows})
    exomiser_hpo_values = sorted({row["hpo_terms"] for row in exomiser_rows})
    exomiser_hpo = parse_hpo_terms(exomiser_hpo_values[0] if exomiser_hpo_values else "")

    if expected_sample and expected_sample not in exomiser_samples:
        errors.append("expected sample {} not found in Exomiser rows".format(expected_sample))
    if expected_sample and varvis_sample and expected_sample != varvis_sample:
        errors.append("expected sample {} does not match Varvis sample {}".format(expected_sample, varvis_sample))
    if expected_assembly and exomiser_assemblies != [expected_assembly]:
        errors.append("Exomiser assembly does not match {}".format(expected_assembly))
    if expected_assembly and varvis_assembly and varvis_assembly != expected_assembly:
        errors.append("Varvis assembly does not match {}".format(expected_assembly))
    expected_hpo = parse_hpo_terms(expected_hpo_terms)
    if expected_hpo and exomiser_hpo != expected_hpo:
        errors.append("Exomiser HPO terms do not match expected terms")
    if expected_hpo and varvis_hpo and varvis_hpo != expected_hpo:
        errors.append("Varvis HPO terms do not match expected terms")
    if varvis_hpo and exomiser_hpo and varvis_hpo != exomiser_hpo:
        errors.append("Varvis and Exomiser HPO terms differ")

    return {
        "valid": not errors,
        "errors": errors,
        "sampleId": expected_sample or (exomiser_samples[0] if len(exomiser_samples) == 1 else None),
        "varvisSampleId": varvis_sample,
        "exomiserSamples": exomiser_samples,
        "assembly": expected_assembly or (exomiser_assemblies[0] if len(exomiser_assemblies) == 1 else None),
        "varvisAssembly": varvis_assembly,
        "exomiserAssemblies": exomiser_assemblies,
        "hpoTerms": expected_hpo or exomiser_hpo,
        "varvisHpoTerms": varvis_hpo,
        "exomiserHpoTerms": exomiser_hpo,
    }


def row_counts(exomiser_rows):
    variant_rows = [row for row in exomiser_rows if row["variant_identity"] is not None]
    gene_only_rows = [row for row in exomiser_rows if row["variant_match_status"] == "GENE_ONLY"]
    unique_variants = {row["variant_identity"] for row in variant_rows}
    unique_genes = {row["gene"] for row in exomiser_rows if row["gene"]}
    return {
        "canonicalRows": len(exomiser_rows),
        "variantLevelRows": len(variant_rows),
        "geneOnlyRows": len(gene_only_rows),
        "uniqueVariants": len(unique_variants),
        "uniqueGenes": len(unique_genes),
    }


def compare(varvis_json, exomiser_tsv, case_id, target_gene=None, target_variant=None, top_k=10, expected_sample=None, expected_assembly=None, expected_hpo_terms=None):
    if top_k < 1:
        raise ValueError("top-k must be positive.")
    varvis_info = load_varvis_payload(varvis_json)
    varvis_candidates = varvis_info["mapped_candidates"]
    varvis_unmapped = varvis_info["unmapped_candidates"]
    all_varvis_candidates = varvis_candidates + varvis_unmapped
    exomiser_rows = [
        row for row in load_exomiser_rows(exomiser_tsv) if row["case_id"] == case_id
    ]
    if not exomiser_rows:
        raise ValueError("No Exomiser rows found for case_id '{}'.".format(case_id))

    input_equivalence = validate_input_equivalence(
        varvis_info,
        exomiser_rows,
        expected_sample=expected_sample,
        expected_assembly=expected_assembly,
        expected_hpo_terms=expected_hpo_terms,
    )
    if not input_equivalence["valid"]:
        raise ValueError("Input equivalence failed: {}".format("; ".join(input_equivalence["errors"])))

    varvis_gene_ranks = first_rank_by_gene(varvis_candidates, "rank")
    exomiser_gene_ranks = first_rank_by_gene(exomiser_rows, "gene_rank")
    varvis_top = set(top_genes(varvis_gene_ranks, top_k))
    exomiser_top = set(top_genes(exomiser_gene_ranks, top_k))
    top_k_overlap = sorted(varvis_top & exomiser_top, key=lambda gene: (varvis_gene_ranks[gene], gene))

    varvis_genes = set(varvis_gene_ranks)
    exomiser_genes = {row["gene"] for row in exomiser_rows if row["gene"]}
    gene_overlap = sorted(varvis_genes & exomiser_genes)
    gene_union = sorted(varvis_genes | exomiser_genes)
    varvis_only_genes = sorted(varvis_genes - exomiser_genes)
    exomiser_only_genes = sorted(exomiser_genes - varvis_genes)

    exomiser_variant_rows = [row for row in exomiser_rows if row["variant_identity"] is not None]
    exomiser_gene_only_rows = [row for row in exomiser_rows if row["variant_match_status"] == "GENE_ONLY"]
    exomiser_variants = {row["variant_identity"] for row in exomiser_variant_rows}
    varvis_variants_all = {row["variant_identity"] for row in all_varvis_candidates if row["variant_identity"] is not None}
    varvis_variants_mapped = {row["variant_identity"] for row in varvis_candidates if row["variant_identity"] is not None}
    exact_matches = sorted(varvis_variants_all & exomiser_variants)
    mapped_exact_matches = sorted(varvis_variants_mapped & exomiser_variants)
    variant_union = sorted(varvis_variants_all | exomiser_variants)
    varvis_only_variants = sorted(varvis_variants_all - exomiser_variants)
    exomiser_only_variants = sorted(exomiser_variants - varvis_variants_all)
    varvis_by_variant_identity = first_by_variant_identity(all_varvis_candidates)
    exomiser_by_variant_identity = {
        row["variant_identity"]: row
        for row in sorted(exomiser_variant_rows, key=lambda item: (item["gene_rank"] or 10**9, item["gene"], variant_identity_to_text(item["variant_identity"])))
    }

    matched_variant_details = []
    for variant in exact_matches:
        varvis_item = varvis_by_variant_identity.get(variant, {})
        exomiser_item = exomiser_by_variant_identity.get(variant, {})
        matched_variant_details.append(
            {
                "variant": variant_identity_to_text(variant),
                "varvisRank": varvis_item.get("rank"),
                "varvisTier": varvis_item.get("tier"),
                "varvisScore": varvis_item.get("score"),
                "varvisGene": varvis_item.get("gene") or None,
                "varvisGeneMappingStatus": varvis_item.get("gene_mapping_status"),
                "exomiserGene": exomiser_item.get("gene"),
                "exomiserGeneRank": exomiser_item.get("gene_rank"),
                "exomiserVariantRank": exomiser_item.get("variant_rank"),
                "variantRankDeltaStatus": RANK_NOT_COMPUTABLE
                if exomiser_item.get("variant_rank") is None
                else "COMPUTABLE",
            }
        )

    overlapping_gene_details = []
    for gene in gene_overlap:
        overlapping_gene_details.append(
            {
                "gene": gene,
                "varvisBestCandidateRank": varvis_gene_ranks.get(gene),
                "exomiserNativeGeneRank": exomiser_gene_ranks.get(gene),
                "rankComparability": "SIDE_BY_SIDE_ONLY_DIFFERENT_RANK_LEVELS",
            }
        )

    varvis_mapped_variants_by_gene = {}
    for row in varvis_candidates:
        if row["gene"] and row["variant_identity"] is not None:
            varvis_mapped_variants_by_gene.setdefault(row["gene"], set()).add(row["variant_identity"])
    exomiser_variants_by_gene = {}
    for row in exomiser_variant_rows:
        if row["gene"] and row["variant_identity"] is not None:
            exomiser_variants_by_gene.setdefault(row["gene"], set()).add(row["variant_identity"])
    gene_match_variant_different_genes = sorted(
        gene
        for gene in gene_overlap
        if varvis_mapped_variants_by_gene.get(gene)
        and exomiser_variants_by_gene.get(gene)
        and not (varvis_mapped_variants_by_gene[gene] & exomiser_variants_by_gene[gene])
    )

    varvis_by_variant = first_by_variant(varvis_candidates, "rank")
    exomiser_by_variant = first_by_variant(exomiser_rows, "variant_rank")
    target_gene_key = normalized_gene(target_gene) if target_gene else None
    parsed_target_variant = parse_target_variant(target_variant)
    target_gene_summary = None
    if target_gene_key:
        varvis_rank = varvis_gene_ranks.get(target_gene_key)
        exomiser_rank = exomiser_gene_ranks.get(target_gene_key)
        target_gene_summary = {
            "gene": target_gene_key,
            "varvis_rank": varvis_rank,
            "exomiser_gene_rank": exomiser_rank,
            "rank_delta": (
                varvis_rank - exomiser_rank
                if varvis_rank is not None and exomiser_rank is not None
                else None
            ),
        }

    target_variant_summary = None
    if parsed_target_variant:
        varvis_variant = varvis_by_variant.get(parsed_target_variant)
        exomiser_variant = exomiser_by_variant.get(parsed_target_variant)
        target_variant_summary = {
            "variant": variant_to_text(parsed_target_variant),
            "varvis_rank": varvis_variant["rank"] if varvis_variant else None,
            "exomiser_variant_rank": (
                exomiser_variant["variant_rank"] if exomiser_variant else None
            ),
            "variant_match_status": (
                exomiser_variant["variant_match_status"] if exomiser_variant else "UNMATCHED"
            ),
            "rank_delta": (
                varvis_variant["rank"] - exomiser_variant["variant_rank"]
                if varvis_variant
                and exomiser_variant
                and exomiser_variant["variant_rank"] is not None
                else None
            ),
        }

    if target_gene_key or parsed_target_variant:
        category_gene = target_gene_key
        if category_gene is None and parsed_target_variant:
            if parsed_target_variant in varvis_by_variant:
                category_gene = varvis_by_variant[parsed_target_variant]["gene"]
            elif parsed_target_variant in exomiser_by_variant:
                category_gene = exomiser_by_variant[parsed_target_variant]["gene"]
        category = categorize(
            varvis_gene_ranks.get(category_gene) if category_gene else None,
            exomiser_gene_ranks.get(category_gene) if category_gene else None,
            top_k,
            target_variant_summary,
            exomiser_rows,
        )
    else:
        category = "NO_TARGET_TECHNICAL_COMPARISON"

    comparison_genes = sorted(
        set(top_genes(varvis_gene_ranks, top_k))
        | set(top_genes(exomiser_gene_ranks, top_k))
        | ({target_gene_key} if target_gene_key else set()),
        key=lambda gene: (
            min(
                varvis_gene_ranks.get(gene, 10**9),
                exomiser_gene_ranks.get(gene, 10**9),
            ),
            gene,
        ),
    )
    gene_comparisons = []
    no_target_mode = not bool(target_gene_key or parsed_target_variant)
    for gene in comparison_genes:
        varvis_rank = varvis_gene_ranks.get(gene)
        exomiser_rank = exomiser_gene_ranks.get(gene)
        gene_comparisons.append(
            {
                "gene": gene,
                "varvis_rank": varvis_rank,
                "exomiser_gene_rank": exomiser_rank,
                "rank_delta": None if no_target_mode else (
                    varvis_rank - exomiser_rank
                    if varvis_rank is not None and exomiser_rank is not None
                    else None
                ),
                "rank_comparability": (
                    "SIDE_BY_SIDE_ONLY_DIFFERENT_RANK_LEVELS"
                    if no_target_mode
                    else "TARGET_GENE_CONTEXT"
                ),
            }
        )

    metadata = varvis_info["metadata"]
    varvis_stats = metadata.get("statistics") if isinstance(metadata.get("statistics"), dict) else {}
    official_export = metadata.get("officialExport") if isinstance(metadata.get("officialExport"), dict) else {}
    exomiser_counts = row_counts(exomiser_rows)
    discordance_counts = {
        "EXACT_VARIANT_MATCH": len(exact_matches),
        "GENE_MATCH_VARIANT_DIFFERENT": len(gene_match_variant_different_genes),
        "VARVIS_ONLY_VARIANT": len(varvis_only_variants),
        "EXOMISER_ONLY_VARIANT": len(exomiser_only_variants),
        "VARVIS_GENE_UNMAPPED": len(varvis_unmapped),
        "EXOMISER_GENE_ONLY": len(exomiser_gene_only_rows),
        "RANK_NOT_COMPARABLE": len([row for row in matched_variant_details if row["variantRankDeltaStatus"] == RANK_NOT_COMPUTABLE]),
        "PHENOTYPE_RERANK_NOT_APPLIED": 0 if metadata.get("rankingApplied") else 1,
    }

    technical = {
        "inputEquivalence": input_equivalence,
        "varvisInput": {
            "sourceCandidateCount": varvis_stats.get("rankingSourceCandidateCount", len(all_varvis_candidates)),
            "comparisonCandidateCount": len(varvis_candidates),
            "missingGeneCandidateCount": len(varvis_unmapped),
            "uniqueMappedGenes": len(varvis_genes),
            "uniqueComparableVariants": len(varvis_variants_all),
            "rankingApplied": bool(metadata.get("rankingApplied", False)),
            "phenotypeOverlapStatus": metadata.get("phenotypeOverlapStatus"),
            "officialExportComplete": official_export.get("complete"),
            "officialExportCandidateCount": official_export.get("candidateCount"),
            "officialExportExportedCount": official_export.get("exportedCount"),
        },
        "exomiserInput": exomiser_counts,
        "geneOverlap": {
            "overlapCount": len(gene_overlap),
            "overlapGenes": gene_overlap,
            "varvisOnlyCount": len(varvis_only_genes),
            "varvisOnlyGenes": varvis_only_genes,
            "exomiserOnlyCount": len(exomiser_only_genes),
            "exomiserOnlyGenes": exomiser_only_genes,
            "unionCount": len(gene_union),
            "jaccard": jaccard(len(gene_overlap), len(gene_union)),
            "overlappingGeneDetails": overlapping_gene_details,
        },
        "variantOverlap": {
            "exactMatchedVariantCount": len(exact_matches),
            "exactMatchedVariants": [variant_identity_to_text(variant) for variant in exact_matches],
            "mappedGeneExactMatchedVariantCount": len(mapped_exact_matches),
            "mappedGeneExactMatchedVariants": [variant_identity_to_text(variant) for variant in mapped_exact_matches],
            "varvisOnlyVariantCount": len(varvis_only_variants),
            "exomiserOnlyVariantCount": len(exomiser_only_variants),
            "variantUnionCount": len(variant_union),
            "variantJaccard": jaccard(len(exact_matches), len(variant_union)),
            "exomiser_output_coverage_in_varvis": jaccard(len(exact_matches), len(exomiser_variants)),
            "varvis_output_overlap_with_exomiser": jaccard(len(exact_matches), len(varvis_variants_all)),
            "matchedVariantDetails": matched_variant_details,
            "exomiserOnlyVariants": [variant_identity_to_text(variant) for variant in exomiser_only_variants],
            "geneMatchVariantDifferentGenes": gene_match_variant_different_genes,
        },
        "rankComparability": {
            "variantRankDeltaStatus": RANK_NOT_COMPUTABLE,
            "reason": "Exomiser canonical TSV has no official global variant rank; TSV row order is not used as a rank.",
            "geneRankComparability": "SIDE_BY_SIDE_ONLY_DIFFERENT_RANK_LEVELS",
        },
        "phenotypeRankingStatus": {
            "varvisRankingApplied": bool(metadata.get("rankingApplied", False)),
            "phenotypeOverlapStatus": metadata.get("phenotypeOverlapStatus"),
            "candidatesWithPhenotypeSupport": metadata.get("job", {}).get("candidatesWithPhenotypeSupport"),
            "top50PriorOverlap": metadata.get("phenotypeAudit", {}).get("candidateVsTop50Count"),
            "exomiserUsedSameHpoTerms": input_equivalence["varvisHpoTerms"] == input_equivalence["exomiserHpoTerms"]
            if input_equivalence["varvisHpoTerms"]
            else None,
        },
        "discordanceSummary": discordance_counts,
        "coverageLimitations": [
            "{} Varvis candidates lack mapped gene symbols and are excluded from gene overlap, but complete-coordinate unmapped candidates are included in exact variant overlap.".format(len(varvis_unmapped)),
            "Exomiser gene-only rows participate in gene overlap only, not exact variant overlap.",
            "The official Varvis export was capped at {}/{}; this comparison uses the full saved Varvis ranking JSON.".format(
                official_export.get("exportedCount"),
                official_export.get("candidateCount"),
            )
            if official_export
            else "Official Varvis export cap metadata is unavailable.",
        ],
    }

    summary = {
        "schemaVersion": SCHEMA_VERSION,
        "case_id": case_id,
        "top_k": top_k,
        "noTargetMode": no_target_mode,
        "safety_note": SAFETY_NOTE,
        "manual_review": "Required for any clinically relevant interpretation; this smoke has no gold-standard target.",
        "discordance_category": category,
        "top_k_gene_overlap_count": len(top_k_overlap),
        "top_k_gene_overlap_genes": top_k_overlap,
        "target_gene": target_gene_summary,
        "target_variant": target_variant_summary,
        "gene_comparisons": gene_comparisons,
        "varvis_candidate_count": len(varvis_candidates),
        "exomiser_row_count": len(exomiser_rows),
        "synthetic_boundary": (
            "Synthetic fixtures are allowed for parser/report tests only; "
            "this report may also be generated from real public-reference inputs. "
            "Neither mode is patient data or benchmark evidence."
        ),
        "technicalComparison": technical,
        "explicitNonConclusions": [
            "No diagnosis, ACMG classification, sensitivity, specificity, or hit@k is reported.",
            "High rank is not pathogenic and low rank or no match is not benign.",
            "Discordance is not interpreted as a tool defect or clinical misclassification.",
        ],
    }
    assert_no_local_paths(summary, "summary")
    return summary


def render_value(value):
    return "" if value is None else str(value)


def format_float(value):
    if value is None:
        return "not computable"
    return "{:.6f}".format(value)


def render_gene_list(genes):
    return ", ".join("`{}`".format(gene) for gene in genes) or "none"


def render_markdown(summary):
    technical = summary["technicalComparison"]
    varvis_input = technical["varvisInput"]
    exomiser_input = technical["exomiserInput"]
    gene = technical["geneOverlap"]
    variant = technical["variantOverlap"]
    phenotype = technical["phenotypeRankingStatus"]
    rank = technical["rankComparability"]
    discordance = technical["discordanceSummary"]

    lines = [
        "# Varvis vs Exomiser Technical Comparison Smoke",
        "",
        "<!-- Legacy title: # Varvis vs Exomiser Baseline Comparison -->",
        "",
        "## Scope",
        "",
        "This report compares one real Varvis ranking JSON with one real canonical EXOMISER_BASELINE TSV for technical output overlap only.",
        "No target gene or target variant was supplied." if summary["noTargetMode"] else "A target was supplied for this run.",
        "",
        "## Input Equivalence",
        "",
        "- Case ID: `{}`".format(summary["case_id"]),
        "- Sample: `{}`".format(technical["inputEquivalence"].get("sampleId")),
        "- Assembly: `{}`".format(technical["inputEquivalence"].get("assembly")),
        "- HPO terms: {}".format(render_gene_list(technical["inputEquivalence"].get("hpoTerms") or [])),
        "- HPO status: test-only software smoke input, not clinical phenotype evidence.",
        "- Patient data: `false`",
        "- Clinical gold standard: `false`",
        "",
        "## Safety Note",
        "",
        SAFETY_NOTE,
        "",
        "## Safety/Interpretation Notice",
        "",
        SAFETY_NOTE,
        "",
        "Variant overlap measures pipeline output concordance only. Varvis and Exomiser rank different entities and use different filtering and annotation methods.",
        "",
        "## Input Counts",
        "",
        "| Source | Count |",
        "| --- | ---: |",
        "| Varvis source candidates | {} |".format(varvis_input["sourceCandidateCount"]),
        "| Varvis comparison candidates with mapped genes | {} |".format(varvis_input["comparisonCandidateCount"]),
        "| Varvis candidates without mapped gene | {} |".format(varvis_input["missingGeneCandidateCount"]),
        "| Varvis unique mapped genes | {} |".format(varvis_input["uniqueMappedGenes"]),
        "| Exomiser canonical rows | {} |".format(exomiser_input["canonicalRows"]),
        "| Exomiser variant-level rows | {} |".format(exomiser_input["variantLevelRows"]),
        "| Exomiser gene-only rows | {} |".format(exomiser_input["geneOnlyRows"]),
        "| Exomiser unique variants | {} |".format(exomiser_input["uniqueVariants"]),
        "| Exomiser unique genes | {} |".format(exomiser_input["uniqueGenes"]),
        "",
        "## Gene Overlap",
        "",
        "- Overlapping genes: `{}`".format(gene["overlapCount"]),
        "- Overlap list: {}".format(render_gene_list(gene["overlapGenes"])),
        "- Varvis-only genes (`{}`): {}".format(gene["varvisOnlyCount"], render_gene_list(gene["varvisOnlyGenes"])),
        "- Exomiser-only genes (`{}`): {}".format(gene["exomiserOnlyCount"], render_gene_list(gene["exomiserOnlyGenes"])),
        "- Gene union count: `{}`".format(gene["unionCount"]),
        "- Gene Jaccard: `{}`".format(format_float(gene["jaccard"])),
        "",
        "Overlapping gene ranks are side-by-side only because Exomiser reports native gene-level rank while Varvis reports best candidate rank.",
        "",
        "## Variant Overlap",
        "",
        "- Exact matched variants: `{}`".format(variant["exactMatchedVariantCount"]),
        "- Matched variants with mapped Varvis gene: `{}`".format(variant["mappedGeneExactMatchedVariantCount"]),
        "- Varvis-only variants: `{}`".format(variant["varvisOnlyVariantCount"]),
        "- Exomiser-only variants: `{}`".format(variant["exomiserOnlyVariantCount"]),
        "- Variant union count: `{}`".format(variant["variantUnionCount"]),
        "- Variant Jaccard: `{}`".format(format_float(variant["variantJaccard"])),
        "- exomiser_output_coverage_in_varvis: `{}`".format(format_float(variant["exomiser_output_coverage_in_varvis"])),
        "- varvis_output_overlap_with_exomiser: `{}`".format(format_float(variant["varvis_output_overlap_with_exomiser"])),
        "",
        "Exact variant matching uses assembly, chromosome, position, REF, and ALT only. No liftover, fuzzy coordinate matching, or gene/HGVS-only matching is performed.",
        "",
        "## Rank Comparability",
        "",
        "- Variant rank delta status: `{}`".format(rank["variantRankDeltaStatus"]),
        "- Reason: {}".format(rank["reason"]),
        "- Gene rank comparability: `{}`".format(rank["geneRankComparability"]),
        "",
        "## Phenotype-Ranking Status",
        "",
        "- Varvis `rankingApplied`: `{}`".format(str(phenotype["varvisRankingApplied"]).lower()),
        "- Varvis `phenotypeOverlapStatus`: `{}`".format(phenotype["phenotypeOverlapStatus"]),
        "- Varvis top-50 prior overlap: `{}`".format(render_value(phenotype["top50PriorOverlap"])),
        "- Exomiser used same HPO terms: `{}`".format(str(phenotype["exomiserUsedSameHpoTerms"]).lower()),
        "- Interpretation: `NO_GENE_OVERLAP` is not negative phenotype evidence.",
        "",
        "## Discordance Summary",
        "",
        "| Category | Count |",
        "| --- | ---: |",
    ]
    for category in TECHNICAL_DISCORDANCE_CATEGORIES:
        lines.append("| `{}` | {} |".format(category, discordance.get(category, 0)))
    lines.extend(
        [
            "",
            "## Data Coverage Limitations",
            "",
        ]
    )
    for limitation in technical["coverageLimitations"]:
        lines.append("- {}".format(limitation))
    lines.extend(
        [
            "",
            "## Technical Conclusions",
            "",
            "- Gene overlap and exact variant overlap were computed with explicit denominators.",
            "- Exomiser variant rank is missing in the canonical TSV and was not inferred from row order.",
            "- Varvis unmapped candidates were disclosed and complete-coordinate unmapped candidates were included in exact variant overlap.",
            "",
            "## Explicit Non-Conclusions",
            "",
        ]
    )
    for item in summary["explicitNonConclusions"]:
        lines.append("- {}".format(item))

    if summary["target_gene"]:
        target = summary["target_gene"]
        lines.extend(
            [
                "",
                "## Target Gene",
                "",
                "| Gene | Varvis rank | Exomiser gene rank | Rank delta (Varvis - Exomiser) |",
                "| --- | ---: | ---: | ---: |",
                "| `{gene}` | {varvis} | {exomiser} | {delta} |".format(
                    gene=target["gene"],
                    varvis=render_value(target["varvis_rank"]),
                    exomiser=render_value(target["exomiser_gene_rank"]),
                    delta=render_value(target["rank_delta"]),
                ),
            ]
        )
    if summary["target_variant"]:
        target = summary["target_variant"]
        lines.extend(
            [
                "",
                "## Target Variant",
                "",
                "| Variant | Varvis rank | Exomiser variant rank | Match status | Rank delta |",
                "| --- | ---: | ---: | --- | ---: |",
                "| `{variant}` | {varvis} | {exomiser} | `{status}` | {delta} |".format(
                    variant=target["variant"],
                    varvis=render_value(target["varvis_rank"]),
                    exomiser=render_value(target["exomiser_variant_rank"]),
                    status=target["variant_match_status"],
                    delta=render_value(target["rank_delta"]),
                ),
            ]
        )

    lines.extend(
        [
            "",
            "## Gene Rank Comparison",
            "",
        ]
    )
    if summary["noTargetMode"]:
        lines.extend(
            [
                "| Gene | Varvis best candidate rank | Exomiser native gene rank | Rank comparability |",
                "| --- | ---: | ---: | --- |",
            ]
        )
        for row in summary["gene_comparisons"]:
            lines.append(
                "| `{gene}` | {varvis} | {exomiser} | `{status}` |".format(
                    gene=row["gene"],
                    varvis=render_value(row["varvis_rank"]),
                    exomiser=render_value(row["exomiser_gene_rank"]),
                    status=row["rank_comparability"],
                )
            )
    else:
        lines.extend(
            [
                "| Gene | Varvis rank | Exomiser gene rank | Rank delta (Varvis - Exomiser) |",
                "| --- | ---: | ---: | ---: |",
            ]
        )
        for row in summary["gene_comparisons"]:
            lines.append(
                "| `{gene}` | {varvis} | {exomiser} | {delta} |".format(
                    gene=row["gene"],
                    varvis=render_value(row["varvis_rank"]),
                    exomiser=render_value(row["exomiser_gene_rank"]),
                    delta=render_value(row["rank_delta"]),
                )
            )
    lines.extend(["", "## Manual Review Notes", "", "- Manual review is required before any clinical use."])
    markdown = "\n".join(lines) + "\n"
    assert_no_local_paths(markdown, "markdown")
    return markdown


def build_manifest(summary, varvis_json, exomiser_tsv, output_md, output_json, varvis_path_label, exomiser_path_label):
    technical = summary["technicalComparison"]
    manifest = {
        "schemaVersion": "VARVIS_EXOMISER_TECHNICAL_COMPARISON_MANIFEST_V1",
        "status": "real_varvis_vs_exomiser_technical_comparison_completed",
        "comparisonScriptPath": "varvis-server/scripts/compare_varvis_exomiser_baseline.py",
        "comparisonScriptSha256": sha256_file(Path(__file__)),
        "comparisonSchemaVersion": summary["schemaVersion"],
        "caseId": summary["case_id"],
        "sampleId": technical["inputEquivalence"].get("sampleId"),
        "assembly": technical["inputEquivalence"].get("assembly"),
        "hpoTerms": technical["inputEquivalence"].get("hpoTerms"),
        "hpoTestOnlyNotice": "The HPO terms are software smoke-test inputs, not clinical phenotypes of HG001.",
        "patientData": False,
        "clinicalGoldStandard": False,
        "targetGene": summary["target_gene"]["gene"] if summary["target_gene"] else None,
        "targetVariant": summary["target_variant"]["variant"] if summary["target_variant"] else None,
        "varvisInput": {
            "path": varvis_path_label,
            "sha256": sha256_file(varvis_json),
            **technical["varvisInput"],
        },
        "exomiserInput": {
            "path": exomiser_path_label,
            "sha256": sha256_file(exomiser_tsv),
            **technical["exomiserInput"],
        },
        "geneOverlap": technical["geneOverlap"],
        "variantOverlap": {
            key: value
            for key, value in technical["variantOverlap"].items()
            if key != "matchedVariantDetails"
        },
        "rankComparability": technical["rankComparability"],
        "phenotypeRankingStatus": technical["phenotypeRankingStatus"],
        "markdownReport": {
            "path": output_md.name,
            "sha256": sha256_file(output_md),
        },
        "jsonReport": {
            "path": output_json.name if output_json is not None else None,
            "sha256": sha256_file(output_json) if output_json is not None else None,
        },
        "warnings": technical["coverageLimitations"],
        "comparisonExecuted": True,
        "benchmarkExecuted": False,
        "diagnosisEvaluationPerformed": False,
    }
    assert_no_local_paths(manifest, "manifest")
    return manifest


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=True, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def write_report(summary, output_md, output_json=None, output_manifest=None, varvis_json=None, exomiser_tsv=None, varvis_path_label="<VARVIS_RANKING_JSON>", exomiser_path_label="<EXOMISER_BASELINE_TSV>"):
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_md.write_text(render_markdown(summary), encoding="utf-8")
    if output_json is not None:
        write_json(output_json, summary)
    if output_manifest is not None:
        if varvis_json is None or exomiser_tsv is None:
            raise ValueError("varvis_json and exomiser_tsv are required to write a manifest")
        manifest = build_manifest(
            summary,
            varvis_json,
            exomiser_tsv,
            output_md,
            output_json,
            varvis_path_label,
            exomiser_path_label,
        )
        write_json(output_manifest, manifest)


def main(argv=None):
    args = parse_args(argv)
    try:
        varvis_json = Path(args.varvis_json)
        exomiser_tsv = Path(args.exomiser_tsv)
        summary = compare(
            varvis_json,
            exomiser_tsv,
            args.case_id,
            target_gene=args.target_gene,
            target_variant=args.target_variant,
            top_k=args.top_k,
            expected_sample=args.sample_id,
            expected_assembly=args.assembly,
            expected_hpo_terms=args.hpo_terms,
        )
        write_report(
            summary,
            Path(args.output_md),
            Path(args.output_json) if args.output_json else None,
            Path(args.output_manifest) if args.output_manifest else None,
            varvis_json=varvis_json,
            exomiser_tsv=exomiser_tsv,
            varvis_path_label=args.varvis_path_label,
            exomiser_path_label=args.exomiser_path_label,
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print("Comparison failed: {}".format(error), file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
