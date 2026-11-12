#!/usr/bin/env python3
"""Convert real Exomiser 15.1.0 output into Varvis EXOMISER_BASELINE TSV."""

import argparse
import csv
import hashlib
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

import validate_exomiser_baseline_import as baseline_validator


CONVERTER_VERSION = "1.0.0"
SOURCE = "EXOMISER_BASELINE"
REQUIRED_VARIANT_COLUMNS = {
    "#RANK",
    "ID",
    "GENE_SYMBOL",
    "ENTREZ_GENE_ID",
    "MOI",
    "EXOMISER_GENE_COMBINED_SCORE",
    "EXOMISER_GENE_PHENO_SCORE",
    "EXOMISER_GENE_VARIANT_SCORE",
    "EXOMISER_VARIANT_SCORE",
    "CONTIG",
    "START",
    "REF",
    "ALT",
    "FILTER",
    "GENOTYPE",
    "FUNCTIONAL_CLASS",
    "HGVS",
}
REQUIRED_GENE_COLUMNS = {
    "#RANK",
    "GENE_SYMBOL",
    "ENTREZ_GENE_ID",
    "MOI",
    "EXOMISER_GENE_COMBINED_SCORE",
    "EXOMISER_GENE_PHENO_SCORE",
    "EXOMISER_GENE_VARIANT_SCORE",
}
MOI_MAP = {
    "AD": "AUTOSOMAL_DOMINANT",
    "AR": "AUTOSOMAL_RECESSIVE",
    "XR": "X_RECESSIVE",
    "XD": "X_DOMINANT",
    "MT": "MITOCHONDRIAL",
}
LOCAL_PATH_PATTERNS = (
    re.compile(r"[A-Za-z]:[\\/][^\s\t]*"),
    re.compile(r"(^|[\s;])/(?:mnt/[a-z]/|home/|Users/)[^\s\t]*", re.IGNORECASE),
)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Convert Exomiser native tiny-run output into the canonical "
            "Varvis EXOMISER_BASELINE TSV. This does not run Exomiser."
        )
    )
    parser.add_argument("--input-dir", required=True, help="Exomiser output directory.")
    parser.add_argument("--output-tsv", required=True, help="Canonical TSV output path.")
    parser.add_argument("--sample-id", required=True, help="Case/sample ID.")
    parser.add_argument("--assembly", required=True, help="Genome assembly, e.g. GRCh38.")
    parser.add_argument("--source-version", required=True, help="Exomiser source version.")
    parser.add_argument(
        "--hpo-terms",
        default="HP:0001250;HP:0004322",
        help="Semicolon/comma/space separated HPO IDs used for the Exomiser run.",
    )
    parser.add_argument("--manifest", help="Optional provenance JSON output path.")
    parser.add_argument(
        "--genes-tsv",
        help="Optional explicit Exomiser genes TSV path; defaults to *genes.tsv in input dir.",
    )
    parser.add_argument(
        "--variants-tsv",
        help="Optional explicit Exomiser variants TSV path; defaults to *variants.tsv in input dir.",
    )
    parser.add_argument(
        "--jsonl",
        help="Optional explicit Exomiser JSONL path; defaults to *.jsonl in input dir.",
    )
    return parser.parse_args(argv)


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def script_sha256():
    return sha256_file(Path(__file__).resolve())


def find_one(directory, pattern, label):
    matches = sorted(directory.glob(pattern))
    if len(matches) != 1:
        raise ValueError(
            "Expected exactly one {} matching {!r} in {}, found {}".format(
                label, pattern, directory, len(matches)
            )
        )
    return matches[0]


def read_tsv(path, required_columns):
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        fieldnames = reader.fieldnames or []
        missing = sorted(required_columns - set(fieldnames))
        if missing:
            raise ValueError("{} missing required columns: {}".format(path.name, ",".join(missing)))
        return fieldnames, list(reader)


def load_jsonl_gene_context(path):
    context = {}
    if path is None or not path.is_file():
        return context, 0, set()
    key_sets = set()
    count = 0
    with path.open("r", encoding="utf-8-sig") as handle:
        for line in handle:
            if not line.strip():
                continue
            obj = json.loads(line)
            count += 1
            key_sets.add(tuple(sorted(obj.keys())))
            gene = str(obj.get("geneSymbol") or "").strip()
            if not gene:
                continue
            disease = first_compatible_disease(obj)
            query_terms = []
            hiphive = (obj.get("priorityResults") or {}).get("HIPHIVE_PRIORITY") or {}
            for term in hiphive.get("queryPhenotypeTerms") or []:
                term_id = term.get("id")
                if term_id:
                    query_terms.append(term_id)
            context[gene] = {
                "disease_id": disease.get("diseaseId", ""),
                "disease_label": disease.get("diseaseName", ""),
                "matched_phenotypes": ";".join(dict.fromkeys(query_terms)),
            }
    return context, count, key_sets


def first_compatible_disease(obj):
    for gene_score in obj.get("geneScores") or []:
        for match in gene_score.get("compatibleDiseaseMatches") or []:
            model = match.get("model") or {}
            if model.get("diseaseId") or model.get("diseaseName"):
                return model
    hiphive = (obj.get("priorityResults") or {}).get("HIPHIVE_PRIORITY") or {}
    for match in hiphive.get("diseaseMatches") or []:
        model = match.get("model") or {}
        disease = model.get("disease") or model
        if disease.get("diseaseId") or disease.get("diseaseName"):
            return disease
    return {}


def normalize_hpo_terms(value):
    return baseline_validator.normalize_hpo_terms(value)


def normalize_chromosome(value):
    chrom = str(value or "").strip()
    if chrom.lower().startswith("chr"):
        chrom = chrom[3:]
    return chrom.upper()


def variant_key(assembly, chrom, pos, ref, alt):
    return "{}:{}:{}:{}:{}".format(
        assembly,
        normalize_chromosome(chrom),
        str(pos).strip(),
        str(ref).strip().upper(),
        str(alt).strip().upper(),
    )


def convert_moi(value):
    raw = str(value or "").strip()
    return MOI_MAP.get(raw.upper(), raw)


def blank_if_zero(value):
    text = str(value or "").strip()
    return "" if text in {"", "0", "0.0", "0.0000"} else text


def contains_local_absolute_path(value):
    return any(pattern.search(str(value)) for pattern in LOCAL_PATH_PATTERNS)


def evidence_from_variant(row):
    parts = [
        "primary_data_source=variants.tsv",
        "raw_exomiser_id={}".format(row.get("ID", "")),
        "filter={}".format(row.get("FILTER", "")),
        "genotype={}".format(row.get("GENOTYPE", "")),
        "functional_class={}".format(row.get("FUNCTIONAL_CLASS", "")),
        "hgvs={}".format(row.get("HGVS", "")),
        "contributing_variant={}".format(row.get("CONTRIBUTING_VARIANT", "")),
    ]
    if row.get("EXOMISER_GENE_VARIANT_SCORE"):
        parts.append("gene_variant_score={}".format(row["EXOMISER_GENE_VARIANT_SCORE"]))
    if row.get("MAX_FREQ"):
        parts.append("max_freq={}:{}".format(row.get("MAX_FREQ_SOURCE", ""), row.get("MAX_FREQ", "")))
    if row.get("EXOMISER_ACMG_CLASSIFICATION"):
        parts.append("exomiser_acmg_classification={}".format(row["EXOMISER_ACMG_CLASSIFICATION"]))
    return "; ".join(part for part in parts if not contains_local_absolute_path(part))


def evidence_from_gene(row):
    parts = [
        "primary_data_source=genes.tsv",
        "gene_only_row=true",
        "raw_exomiser_gene_id={}".format(row.get("ID", "")),
        "gene_variant_score={}".format(row.get("EXOMISER_GENE_VARIANT_SCORE", "")),
    ]
    return "; ".join(part for part in parts if not contains_local_absolute_path(part))


def warnings_for_variant(row):
    warnings = [
        "exomiser_variant_rank_not_provided_by_native_output",
        "variant_match_status_means_complete_exomiser_variant_coordinates_not_varvis_comparison",
        "hpo_terms_are_test_inputs_not_hg001_clinical_phenotypes",
    ]
    if not row.get("EXOMISER_ACMG_DISEASE_ID"):
        warnings.append("disease_fields_missing_in_variant_tsv")
    return "; ".join(warnings)


def warnings_for_gene_only():
    return "; ".join(
        [
            "gene_only_row_added_from_genes_tsv_no_variant_row_in_variants_tsv",
            "exomiser_variant_rank_not_applicable",
            "hpo_terms_are_test_inputs_not_hg001_clinical_phenotypes",
        ]
    )


def canonical_variant_row(row, sample_id, hpo_terms, assembly, source_version, gene_context):
    gene = str(row.get("GENE_SYMBOL") or "").strip()
    if not gene:
        raise ValueError("variant row missing GENE_SYMBOL")
    for field in ("CONTIG", "START", "REF", "ALT"):
        if not str(row.get(field) or "").strip():
            raise ValueError("variant row for {} missing {}".format(gene, field))
    disease_id = row.get("EXOMISER_ACMG_DISEASE_ID") or gene_context.get(gene, {}).get("disease_id", "")
    disease_label = row.get("EXOMISER_ACMG_DISEASE_NAME") or gene_context.get(gene, {}).get("disease_label", "")
    return {
        "case_id": sample_id,
        "hpo_terms": hpo_terms,
        "gene_symbol": gene,
        "gene_id": row.get("ENTREZ_GENE_ID", ""),
        "variant_id": variant_key(
            assembly,
            row.get("CONTIG", ""),
            row.get("START", ""),
            row.get("REF", ""),
            row.get("ALT", ""),
        ),
        "chrom": normalize_chromosome(row.get("CONTIG", "")),
        "pos": row.get("START", ""),
        "ref": str(row.get("REF", "")).upper(),
        "alt": str(row.get("ALT", "")).upper(),
        "exomiser_gene_rank": row.get("#RANK", ""),
        "exomiser_variant_rank": "",
        "exomiser_score": row.get("EXOMISER_GENE_COMBINED_SCORE", ""),
        "exomiser_gene_score": row.get("EXOMISER_GENE_PHENO_SCORE", ""),
        "exomiser_variant_score": row.get("EXOMISER_VARIANT_SCORE", ""),
        "disease_id": disease_id,
        "disease_label": disease_label,
        "mode_of_inheritance": convert_moi(row.get("MOI", "")),
        "matched_phenotypes": gene_context.get(gene, {}).get("matched_phenotypes", hpo_terms),
        "source": SOURCE,
        "source_version": source_version,
        "genome_build": assembly,
        "variant_match_status": "MATCHED",
        "evidence": evidence_from_variant(row),
        "warnings": warnings_for_variant(row),
    }


def canonical_gene_only_row(row, sample_id, hpo_terms, assembly, source_version, gene_context):
    gene = str(row.get("GENE_SYMBOL") or "").strip()
    if not gene:
        raise ValueError("gene row missing GENE_SYMBOL")
    return {
        "case_id": sample_id,
        "hpo_terms": hpo_terms,
        "gene_symbol": gene,
        "gene_id": row.get("ENTREZ_GENE_ID", ""),
        "variant_id": "",
        "chrom": "",
        "pos": "",
        "ref": "",
        "alt": "",
        "exomiser_gene_rank": row.get("#RANK", ""),
        "exomiser_variant_rank": "",
        "exomiser_score": row.get("EXOMISER_GENE_COMBINED_SCORE", ""),
        "exomiser_gene_score": row.get("EXOMISER_GENE_PHENO_SCORE", ""),
        "exomiser_variant_score": "",
        "disease_id": gene_context.get(gene, {}).get("disease_id", ""),
        "disease_label": gene_context.get(gene, {}).get("disease_label", ""),
        "mode_of_inheritance": convert_moi(row.get("MOI", "")),
        "matched_phenotypes": gene_context.get(gene, {}).get("matched_phenotypes", hpo_terms),
        "source": SOURCE,
        "source_version": source_version,
        "genome_build": assembly,
        "variant_match_status": "GENE_ONLY",
        "evidence": evidence_from_gene(row),
        "warnings": warnings_for_gene_only(),
    }


def sort_key(row):
    gene_rank = int(row["exomiser_gene_rank"]) if row["exomiser_gene_rank"] else 10**9
    pos = int(row["pos"]) if row["pos"] else 10**12
    return (
        gene_rank,
        row["gene_symbol"],
        row["variant_match_status"] != "MATCHED",
        row["chrom"],
        pos,
        row["ref"],
        row["alt"],
        row["variant_id"],
    )


def write_tsv_atomic(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(
        prefix=path.name + ".",
        suffix=".tmp",
        dir=str(path.parent),
        text=True,
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(
                handle,
                fieldnames=baseline_validator.CANONICAL_COLUMNS,
                delimiter="\t",
                lineterminator="\n",
            )
            writer.writeheader()
            for row in rows:
                writer.writerow({name: row.get(name, "") for name in baseline_validator.CANONICAL_COLUMNS})
        os.replace(temp_name, path)
    except Exception:
        try:
            os.unlink(temp_name)
        except OSError:
            pass
        raise


def abstract_path(path):
    parts = Path(path).as_posix().split("/")
    if "exomiser" in parts:
        index = parts.index("exomiser")
        return "<EXOMISER_ROOT>/" + "/".join(parts[index + 1 :])
    return Path(path).name


def source_file_entry(path):
    return {
        "path": abstract_path(path),
        "name": path.name,
        "sha256": sha256_file(path),
        "sizeBytes": path.stat().st_size,
    }


def convert(args):
    input_dir = Path(args.input_dir)
    if not input_dir.is_dir():
        raise ValueError("input-dir does not exist or is not a directory: {}".format(input_dir))
    genes_tsv = Path(args.genes_tsv) if args.genes_tsv else find_one(input_dir, "*.genes.tsv", "genes TSV")
    variants_tsv = (
        Path(args.variants_tsv)
        if args.variants_tsv
        else find_one(input_dir, "*.variants.tsv", "variants TSV")
    )
    jsonl = Path(args.jsonl) if args.jsonl else find_one(input_dir, "*.jsonl", "JSONL")
    vcf_files = sorted(input_dir.glob("*.vcf.gz"))
    tbi_files = sorted(input_dir.glob("*.vcf.gz.tbi"))
    hpo_terms = normalize_hpo_terms(args.hpo_terms)

    _gene_header, gene_rows = read_tsv(genes_tsv, REQUIRED_GENE_COLUMNS)
    _variant_header, variant_rows = read_tsv(variants_tsv, REQUIRED_VARIANT_COLUMNS)
    if not gene_rows:
        raise ValueError("genes TSV has no data rows")
    if not variant_rows:
        raise ValueError("variants TSV has no data rows")

    gene_context, jsonl_line_count, jsonl_key_sets = load_jsonl_gene_context(jsonl)
    canonical_rows = [
        canonical_variant_row(
            row,
            args.sample_id,
            hpo_terms,
            args.assembly,
            args.source_version,
            gene_context,
        )
        for row in variant_rows
    ]
    variant_genes = {row["gene_symbol"] for row in canonical_rows}
    for gene_row in gene_rows:
        if str(gene_row.get("GENE_SYMBOL") or "").strip() not in variant_genes:
            canonical_rows.append(
                canonical_gene_only_row(
                    gene_row,
                    args.sample_id,
                    hpo_terms,
                    args.assembly,
                    args.source_version,
                    gene_context,
                )
            )
    canonical_rows.sort(key=sort_key)

    output_tsv = Path(args.output_tsv)
    write_tsv_atomic(output_tsv, canonical_rows)
    validation = baseline_validator.validate_file(output_tsv)
    if validation["invalid_rows"]:
        raise ValueError("converted TSV failed validation: {}".format("; ".join(validation["errors"])))

    unique_variant_keys = {
        row["variant_id"] for row in canonical_rows if row["variant_match_status"] == "MATCHED"
    }
    duplicate_variant_count = (
        sum(1 for row in canonical_rows if row["variant_match_status"] == "MATCHED")
        - len(unique_variant_keys)
    )
    missing_gene_count = sum(1 for row in canonical_rows if not row["gene_symbol"])
    missing_coordinate_count = sum(
        1
        for row in canonical_rows
        if row["variant_match_status"] == "MATCHED"
        and not all(row[field] for field in ("chrom", "pos", "ref", "alt"))
    )
    missing_score_count = sum(1 for row in canonical_rows if not row["exomiser_score"])
    missing_rank_count = sum(1 for row in canonical_rows if not row["exomiser_gene_rank"])
    warning_count = sum(1 for row in canonical_rows if row["warnings"])

    manifest = {
        "converterVersion": CONVERTER_VERSION,
        "converterScript": "varvis-server/scripts/convert_exomiser_output_to_baseline.py",
        "converterScriptSha256": script_sha256(),
        "conversionTimestamp": datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "exomiserVersion": args.source_version,
        "genomeDataVersion": "2602",
        "phenotypeDataVersion": "2602",
        "assembly": args.assembly,
        "sampleId": args.sample_id,
        "hpoTerms": hpo_terms,
        "hpoTestOnlyNotice": (
            "HPO terms are software test inputs only and are not clinical phenotypes of HG001/NA12878."
        ),
        "sourceOutputFiles": [
            source_file_entry(path)
            for path in [genes_tsv, variants_tsv, jsonl, *vcf_files, *tbi_files]
            if path and path.exists()
        ],
        "sourceRowCounts": {
            "genesTsv": len(gene_rows),
            "variantsTsv": len(variant_rows),
            "jsonl": jsonl_line_count,
            "vcfRecords": count_vcf_records(vcf_files[0]) if vcf_files else None,
        },
        "selectedPrimaryDataSource": "variants.tsv",
        "secondaryDataSources": ["genes.tsv", "jsonl"],
        "mappingSchemaVersion": "EXOMISER_BASELINE_CANONICAL_V1",
        "canonicalTsvPath": abstract_path(output_tsv),
        "canonicalTsvSha256": sha256_file(output_tsv),
        "outputRowCount": len(canonical_rows),
        "uniqueGeneCount": len({row["gene_symbol"] for row in canonical_rows if row["gene_symbol"]}),
        "uniqueVariantCount": len(unique_variant_keys),
        "duplicateVariantKeyCount": duplicate_variant_count,
        "missingGeneCount": missing_gene_count,
        "missingCoordinateCount": missing_coordinate_count,
        "missingScoreCount": missing_score_count,
        "missingRankCount": missing_rank_count,
        "warningCount": warning_count,
        "jsonlTopLevelKeySets": [list(keys) for keys in sorted(jsonl_key_sets)],
        "validatorSummary": validation,
        "patientData": False,
        "publicReferenceBenchmark": True,
        "clinicalInterpretationIntended": False,
        "comparisonRun": False,
        "notes": [
            "exomiser_variant_rank is blank because Exomiser 15.1.0 variants.tsv did not expose an official global variant rank.",
            "variant_match_status=MATCHED indicates complete Exomiser variant coordinates in the canonical TSV; Varvis comparison was not run.",
            "Exomiser ranks and scores are external baseline evidence only, not diagnoses or ACMG classifications.",
        ],
    }
    if args.manifest:
        manifest_path = Path(args.manifest)
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        json.loads(manifest_path.read_text(encoding="utf-8"))
    return manifest


def count_vcf_records(path):
    import gzip

    count = 0
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        for line in handle:
            if line and not line.startswith("#"):
                count += 1
    return count


def main(argv=None):
    args = parse_args(argv)
    try:
        manifest = convert(args)
    except (OSError, ValueError, json.JSONDecodeError, csv.Error) as error:
        print("Conversion failed: {}".format(error), file=sys.stderr)
        return 1
    print(json.dumps(manifest, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
