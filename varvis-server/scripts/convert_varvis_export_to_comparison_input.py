#!/usr/bin/env python3
"""Convert a Varvis ranking export or variants page into comparison input JSON."""

import argparse
import hashlib
import json
import re
import sys
import textwrap
from pathlib import Path


SCHEMA_VERSION = "VARVIS_RANKING_COMPARISON_INPUT_V1"
SAFETY_NOTE = (
    "Varvis ranking, phenotype support, and external Exomiser comparison inputs "
    "are prioritization evidence only. They are not diagnoses, not ACMG "
    "classifications, and do not imply pathogenicity or benignity."
)
LOCAL_PATH_PATTERNS = (
    re.compile(r"[A-Za-z]:[\\/][^\s\t]*"),
    re.compile(r"(^|[\s;])/(?:mnt/[a-z]/|home/|Users/)[^\s\t]*", re.IGNORECASE),
)
IMPACT_ORDER = {"HIGH": 0, "MODERATE": 1, "LOW": 2, "MODIFIER": 3}


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Build a Varvis ranking JSON that can be consumed by the offline "
            "Varvis-versus-Exomiser comparison script. This command does not run "
            "Varvis, does not run Exomiser, and does not infer diagnosis."
        ),
        epilog=textwrap.dedent(
            """\
            Example:
              python varvis-server/scripts/convert_varvis_export_to_comparison_input.py \\
                --input-json varvis_variants_candidates.json \\
                --output-json varvis_ranking.json \\
                --sample-id HG001 \\
                --assembly GRCh38 \\
                --hpo-terms HP:0001250,HP:0004322 \\
                --phenotype-audit varvis_phenotype_overlap_audit.json \\
                --official-export-json varvis_export.json \\
                --manifest varvis_ranking_manifest.json
            """
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--input-json", required=True, help="Varvis export JSON or variants page JSON.")
    parser.add_argument("--output-json", required=True, help="Comparison input JSON to write.")
    parser.add_argument("--manifest", help="Optional conversion manifest JSON to write.")
    parser.add_argument("--case-id", default="", help="Optional case/analysis identifier.")
    parser.add_argument("--sample-id", required=True, help="Sample identifier, e.g. HG001.")
    parser.add_argument("--assembly", required=True, help="Genome assembly, e.g. GRCh38.")
    parser.add_argument(
        "--hpo-terms",
        default="",
        help="Comma, semicolon, or whitespace separated HPO terms used for the Varvis run.",
    )
    parser.add_argument("--phenotype-audit", help="Optional phenotype overlap audit JSON.")
    parser.add_argument("--job-json", help="Optional original Varvis job response JSON.")
    parser.add_argument("--official-export-json", help="Optional official Varvis export JSON for cap metadata.")
    parser.add_argument(
        "--phenotype-overlap-status",
        default="",
        help="Override phenotype overlap status, e.g. NO_GENE_OVERLAP.",
    )
    return parser.parse_args(argv)


def is_blank(value):
    return value is None or str(value).strip() == ""


def normalize_gene(value):
    return str(value or "").strip().upper()


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


def read_json(path):
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


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


def first_value(mapping, *names):
    if not isinstance(mapping, dict):
        return ""
    for name in names:
        value = mapping.get(name)
        if not is_blank(value):
            return value
    return ""


def source_candidates(payload):
    if isinstance(payload, list):
        return payload, {"inputKind": "candidate_list"}
    if not isinstance(payload, dict):
        raise ValueError("Varvis input JSON root must be an object or list.")
    if isinstance(payload.get("variants"), list):
        return payload["variants"], {
            "inputKind": "variant_page",
            "analysisId": payload.get("analysisId"),
            "view": payload.get("view"),
            "page": payload.get("page"),
            "pageSize": payload.get("pageSize"),
            "total": payload.get("total"),
            "candidateTotal": payload.get("candidateTotal"),
            "allTotal": payload.get("allTotal"),
            "frequencyFilteredTotal": payload.get("frequencyFilteredTotal"),
            "populationFrequencyThreshold": payload.get("populationFrequencyThreshold"),
        }
    if isinstance(payload.get("topCandidates"), list):
        return payload["topCandidates"], {
            "inputKind": "official_export",
            "generatedAt": payload.get("generatedAt"),
            "summary": export_summary_counts(payload),
        }
    for key in ("candidates", "rankedCandidates", "results", "data"):
        if isinstance(payload.get(key), list):
            return payload[key], {"inputKind": key}
    raise ValueError("Varvis input JSON does not contain a candidate list.")


def export_summary_counts(payload):
    summary = payload.get("summary") if isinstance(payload, dict) else {}
    if not isinstance(summary, dict):
        summary = {}
    return {
        "candidateCount": summary.get("candidateCount"),
        "exportedCount": summary.get("exportedCount"),
        "filteredTotalForRequest": summary.get("filteredTotalForRequest"),
        "exportView": summary.get("exportView"),
        "transcriptAnnotationStatus": summary.get("transcriptAnnotationStatus"),
        "phenotypeRankingApplied": (
            summary.get("phenotypeSummary", {}).get("rankingApplied")
            if isinstance(summary.get("phenotypeSummary"), dict)
            else None
        ),
    }


def flatten_observation(item):
    observation = item.get("observation") if isinstance(item.get("observation"), dict) else {}
    key = observation.get("key") if isinstance(observation.get("key"), dict) else {}
    return {
        "assembly": first_value(observation, "genomeAssembly") or first_value(key, "genomeAssembly"),
        "chrom": first_value(observation, "chromosome", "chrom") or first_value(key, "chromosome", "chrom"),
        "pos": first_value(observation, "position", "pos") or first_value(key, "position", "pos"),
        "ref": first_value(observation, "reference", "ref") or first_value(key, "reference", "ref"),
        "alt": first_value(observation, "alternate", "alt") or first_value(key, "alternate", "alt"),
        "sampleId": first_value(observation, "sampleId"),
        "genotype": first_value(observation, "genotype"),
        "filterStatus": first_value(observation, "filterStatus"),
        "variantType": first_value(observation, "variantType"),
    }


def variant_key(observation):
    if any(is_blank(observation.get(name)) for name in ("chrom", "pos", "ref", "alt")):
        return ""
    return "{chrom}:{pos}:{ref}:{alt}".format(
        chrom=str(observation["chrom"]).removeprefix("chr").removeprefix("Chr"),
        pos=str(observation["pos"]),
        ref=str(observation["ref"]).upper(),
        alt=str(observation["alt"]).upper(),
    )


def all_gene_annotations(item):
    rows = []

    def append_gene(value, source, annotation=None, order=0):
        gene = normalize_gene(value)
        if not gene:
            return
        annotation = annotation if isinstance(annotation, dict) else {}
        rows.append(
            {
                "gene": gene,
                "source": source,
                "order": order,
                "maneSelect": bool(annotation.get("maneSelect")),
                "canonical": bool(annotation.get("canonical")),
                "transcriptSource": annotation.get("transcriptSource"),
                "impact": str(annotation.get("impact") or "").upper(),
                "transcriptAccession": annotation.get("transcriptAccession")
                or annotation.get("transcriptId"),
            }
        )

    append_gene(first_value(item, "gene", "geneSymbol", "gene_symbol", "symbol"), "item")
    top = item.get("topConsequence") if isinstance(item.get("topConsequence"), dict) else {}
    append_gene(first_value(top, "geneSymbol", "gene", "gene_symbol"), "topConsequence", top)

    for index, consequence in enumerate(item.get("consequences") or []):
        if isinstance(consequence, dict):
            append_gene(
                first_value(consequence, "geneSymbol", "gene", "gene_symbol"),
                "consequence",
                consequence,
                index,
            )
    for index, annotation in enumerate(item.get("transcriptAnnotations") or []):
        if isinstance(annotation, dict):
            append_gene(
                first_value(annotation, "geneSymbol", "gene", "gene_symbol"),
                "transcriptAnnotation",
                annotation,
                index,
            )
    return rows


def choose_gene(gene_rows):
    if not gene_rows:
        return None, []
    seen = []
    seen_set = set()
    for row in gene_rows:
        if row["gene"] not in seen_set:
            seen.append(row["gene"])
            seen_set.add(row["gene"])

    def sort_key(row):
        source_order = {
            "item": 0,
            "topConsequence": 1,
            "consequence": 2,
            "transcriptAnnotation": 3,
        }.get(row["source"], 9)
        return (
            source_order,
            0 if row["maneSelect"] else 1,
            0 if row["canonical"] else 1,
            0 if row["transcriptSource"] == "protein_coding" else 1,
            IMPACT_ORDER.get(row["impact"], 9),
            row["order"],
            row["gene"],
        )

    return sorted(gene_rows, key=sort_key)[0], seen


def phenotype_support(item, ranking_applied, overlap_status):
    raw = item.get("phenotypeSupport")
    if not isinstance(raw, dict):
        raw = item.get("phenotype_support")
    if not isinstance(raw, dict):
        raw = {}
    return {
        "supported": bool(raw.get("supported", False)),
        "rankingApplied": bool(ranking_applied),
        "phenotypeOverlapStatus": overlap_status,
        "phenotypeScore": raw.get("phenotypeScore", raw.get("score")),
        "scoreBoost": raw.get("scoreBoost"),
        "phenotypeRank": raw.get("phenotypeRank", raw.get("genePriorRank")),
        "matchedHpoTerms": raw.get("matchedHpoTerms") or [],
        "source": raw.get("source"),
        "warning": raw.get("warning"),
    }


def compact_candidate(item, rank, ranking_applied, overlap_status):
    observation = flatten_observation(item)
    gene_row, all_genes = choose_gene(all_gene_annotations(item))
    base = {
        "rank": rank,
        "variantId": str(first_value(item, "variantId", "variant_id") or ""),
        "variant": {
            "chrom": str(observation.get("chrom") or ""),
            "pos": observation.get("pos") or "",
            "ref": str(observation.get("ref") or "").upper(),
            "alt": str(observation.get("alt") or "").upper(),
            "variantId": str(first_value(item, "variantId", "variant_id") or ""),
            "variantKey": variant_key(observation),
            "assembly": str(observation.get("assembly") or ""),
        },
        "sampleId": str(observation.get("sampleId") or ""),
        "genotype": str(observation.get("genotype") or ""),
        "filterStatus": str(observation.get("filterStatus") or ""),
        "variantType": str(observation.get("variantType") or ""),
        "tier": str(first_value(item, "tier", "priorityTier", "classificationTier") or ""),
        "totalScore": item.get("totalScore", item.get("rankingScore", item.get("score"))),
        "scoreBreakdown": item.get("scoreBreakdown") if isinstance(item.get("scoreBreakdown"), dict) else {},
        "positiveReasons": item.get("positiveReasons") or [],
        "negativeReasons": item.get("negativeReasons") or [],
        "filterReasons": item.get("filterReasons") or [],
        "warnings": item.get("warnings") or [],
        "phenotypeSupport": phenotype_support(item, ranking_applied, overlap_status),
    }
    if gene_row is None:
        base["geneMappingStatus"] = "MISSING_GENE_SYMBOL"
        return None, base
    base.update(
        {
            "gene": gene_row["gene"],
            "geneSymbol": gene_row["gene"],
            "geneMappingStatus": "MAPPED",
            "geneSource": gene_row["source"],
            "allGeneSymbols": all_genes,
        }
    )
    return base, None


def audit_metadata(audit):
    if not isinstance(audit, dict):
        return {}
    overlap = audit.get("overlap") if isinstance(audit.get("overlap"), dict) else {}
    prior = audit.get("phenotypePriorAudit") if isinstance(audit.get("phenotypePriorAudit"), dict) else {}
    candidates = audit.get("candidateGeneAudit") if isinstance(audit.get("candidateGeneAudit"), dict) else {}
    return {
        "rootCauseClassification": audit.get("rootCauseClassification"),
        "phenotypeOverlapStatus": audit.get("phenotypeOverlapStatus"),
        "candidateUniqueGenes": candidates.get("candidateUniqueGenes"),
        "allAnnotatedUniqueGenes": candidates.get("allAnnotatedUniqueGenes"),
        "hardGateEligibleUniqueGenes": candidates.get("hardGateEligibleUniqueGenes"),
        "missingGeneCandidateVariants": candidates.get("missingGeneCandidateVariants"),
        "top50PriorGenes": prior.get("returnedPriorGenes"),
        "providerSourceTotalGenes": prior.get("providerSourceTotalGenes"),
        "maxGenesConfig": prior.get("maxGenesConfig"),
        "maxGenesActsAsProviderAndRankingLimit": prior.get("maxGenesActsAsProviderAndRankingLimit"),
        "candidateVsTop50Count": overlap.get("candidateVsTop50Count"),
        "candidateVsTop50Genes": overlap.get("candidateVsTop50Genes"),
        "eligibleCandidateVsTop50Count": overlap.get("eligibleCandidateVsTop50Count"),
        "candidateVsFullProviderCount": overlap.get("candidateVsFullProviderCount"),
        "candidateVsFullProviderGenes": [
            row.get("gene")
            for row in overlap.get("candidateVsFullProviderGenes", [])
            if isinstance(row, dict) and row.get("gene")
        ],
    }


def official_export_metadata(path):
    if path is None:
        return {}
    payload = read_json(path)
    summary = export_summary_counts(payload)
    candidate_count = summary.get("candidateCount")
    exported_count = summary.get("exportedCount")
    complete = (
        candidate_count is not None
        and exported_count is not None
        and int(exported_count) >= int(candidate_count)
    )
    return {
        "fileName": path.name,
        "sha256": sha256_file(path),
        "candidateCount": candidate_count,
        "exportedCount": exported_count,
        "complete": complete,
        "summary": summary,
    }


def job_metadata(job):
    if not isinstance(job, dict):
        return {}
    phenotype = job.get("phenotypeSummary") if isinstance(job.get("phenotypeSummary"), dict) else {}
    return {
        "jobId": job.get("id"),
        "jobStatus": job.get("status"),
        "selectedSampleId": job.get("selectedSampleId"),
        "genomeAssembly": job.get("genomeAssembly"),
        "fileSha256": job.get("fileSha256"),
        "phenotypeRankingApplied": phenotype.get("rankingApplied"),
        "candidatesWithPhenotypeSupport": phenotype.get("candidatesWithPhenotypeSupport"),
        "genePriorSummary": phenotype.get("genePriorSummary"),
    }


def build_output(args):
    input_path = Path(args.input_json)
    payload = read_json(input_path)
    raw_candidates, source = source_candidates(payload)
    audit = read_json(Path(args.phenotype_audit)) if args.phenotype_audit else {}
    job = read_json(Path(args.job_json)) if args.job_json else {}

    audit_info = audit_metadata(audit)
    hpo_terms = parse_hpo_terms(args.hpo_terms)
    if not hpo_terms:
        audit_input = audit.get("input") if isinstance(audit.get("input"), dict) else {}
        hpo_terms = audit_input.get("hpoTerms") or []
    overlap_status = (
        args.phenotype_overlap_status
        or audit_info.get("phenotypeOverlapStatus")
        or "UNKNOWN"
    )
    ranking_applied = False

    candidates = []
    unmapped = []
    for index, item in enumerate(raw_candidates, start=1):
        if not isinstance(item, dict):
            raise ValueError("Candidate {} must be an object.".format(index))
        rank_value = first_value(item, "rank", "varvisRank", "priorityRank")
        rank = int(rank_value) if not is_blank(rank_value) else index
        mapped, missing = compact_candidate(item, rank, ranking_applied, overlap_status)
        if mapped is not None:
            candidates.append(mapped)
        else:
            unmapped.append(missing)

    unique_genes = sorted({candidate["gene"] for candidate in candidates})
    official = official_export_metadata(Path(args.official_export_json)) if args.official_export_json else {}
    official_complete = official.get("complete")
    warnings = [
        "rankingApplied=false is preserved; no phenotype support is forced.",
        "phenotypeOverlapStatus={}.".format(overlap_status),
        "Candidates without a gene symbol are retained under unmappedCandidates and are not used by the current comparison loader.",
    ]
    if official and official_complete is False:
        warnings.append(
            "Official Varvis export is capped at {exportedCount} of {candidateCount}; comparison input was built from the full candidate page.".format(
                exportedCount=official.get("exportedCount"),
                candidateCount=official.get("candidateCount"),
            )
        )

    output = {
        "schemaVersion": SCHEMA_VERSION,
        "caseId": args.case_id or source.get("analysisId") or "",
        "sampleId": args.sample_id,
        "assembly": args.assembly,
        "hpoTerms": hpo_terms,
        "rankingApplied": False,
        "phenotypeOverlapStatus": overlap_status,
        "safetyNote": SAFETY_NOTE,
        "source": {
            "inputFileName": input_path.name,
            "inputSha256": sha256_file(input_path),
            **source,
        },
        "officialExport": official,
        "job": job_metadata(job),
        "phenotypeAudit": audit_info,
        "statistics": {
            "rankingSourceCandidateCount": len(raw_candidates),
            "comparisonCandidateCount": len(candidates),
            "unmappedCandidateCount": len(unmapped),
            "uniqueGeneCount": len(unique_genes),
            "uniqueGenes": unique_genes,
            "variantCount": len(raw_candidates),
        },
        "warnings": warnings,
        "candidates": sorted(candidates, key=lambda item: (item["rank"], item["variant"]["variantKey"], item["gene"])),
        "unmappedCandidates": sorted(unmapped, key=lambda item: (item["rank"], item["variant"]["variantKey"])),
    }
    assert_no_local_paths(output, "varvis_ranking")
    return output


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=True, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def build_manifest(args, output):
    output_path = Path(args.output_json)
    manifest = {
        "schemaVersion": "VARVIS_RANKING_CONVERSION_MANIFEST_V1",
        "status": "real_varvis_ranking_ready_with_no_phenotype_overlap"
        if output.get("phenotypeOverlapStatus") == "NO_GENE_OVERLAP"
        else "real_varvis_ranking_ready",
        "inputFileName": Path(args.input_json).name,
        "inputSha256": output["source"]["inputSha256"],
        "outputFileName": output_path.name,
        "outputSha256": sha256_file(output_path),
        "rankingApplied": output["rankingApplied"],
        "phenotypeOverlapStatus": output["phenotypeOverlapStatus"],
        "candidateStatistics": output["statistics"],
        "officialExport": output.get("officialExport", {}),
        "rootCauseClassification": output.get("phenotypeAudit", {}).get("rootCauseClassification"),
        "comparisonExecuted": False,
        "noPatientData": True,
        "benchmarkRun": False,
        "varvisRankingModified": False,
        "databaseModified": False,
        "frontendModified": False,
        "safetyNote": SAFETY_NOTE,
    }
    assert_no_local_paths(manifest, "manifest")
    return manifest


def main(argv=None):
    args = parse_args(argv)
    try:
        output = build_output(args)
        output_path = Path(args.output_json)
        write_json(output_path, output)
        manifest = None
        if args.manifest:
            manifest = build_manifest(args, output)
            write_json(Path(args.manifest), manifest)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print("Varvis export conversion failed: {}".format(error), file=sys.stderr)
        return 1
    summary = {
        "output": Path(args.output_json).name,
        "candidateCount": output["statistics"]["rankingSourceCandidateCount"],
        "comparisonCandidateCount": output["statistics"]["comparisonCandidateCount"],
        "unmappedCandidateCount": output["statistics"]["unmappedCandidateCount"],
        "rankingApplied": output["rankingApplied"],
        "phenotypeOverlapStatus": output["phenotypeOverlapStatus"],
    }
    if manifest is not None:
        summary["manifest"] = Path(args.manifest).name
    print(json.dumps(summary, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
