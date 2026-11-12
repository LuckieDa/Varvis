#!/usr/bin/env python3
"""Build the compact ClinVar condition-aware comparator sidecar.

The two ClinVar monthly TSV inputs are read directly from gzip streams.  The
builder keeps only native GRCh38 simple SNVs with one unambiguous exact allele
per Variation ID and joins each retained Variation ID to its current submitted
assertion rows.  The final BGZF TSV is indexed by Variation ID. Callers first
discover a tiny codon window in the existing ClinVar VCF, then use that ID for
a bounded sidecar lookup and re-verify GRCh38 chromosome, position, REF and ALT.

No ACMG criterion is evaluated here.  In particular, aggregate classifications
and submitted assertions remain separate source facts.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import itertools
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from typing import Dict, Iterable, Iterator, List, Optional, Sequence, TextIO, Tuple


BUILDER_VERSION = "1.1.0"
SIDECAR_FORMAT = "CLINVAR_CONDITION_AWARE_COMPARATOR_SIDECAR_V1"
MANIFEST_VERSION = "clinvar-comparator-sidecar-manifest-v1"
STAGED_DATABASE_VERSION = "clinvar-comparator-sidecar-work-v1"
STAGES = (
    "initialize",
    "load-variants",
    "prepare-eligible",
    "load-submissions",
    "bundle-variants",
    "bundle-submissions",
    "emit",
)
STAGE_PREREQUISITES = {
    "load-variants": "initialize",
    "prepare-eligible": "load-variants",
    "load-submissions": "prepare-eligible",
    "bundle-variants": "load-submissions",
    "bundle-submissions": "bundle-variants",
    "emit": "bundle-submissions",
}
STAGE_STATS = {
    "load-variants": ("variantSummaryRowsRead", "grch38SimpleSnvRows"),
    "prepare-eligible": ("eligibleVariationIds", "ambiguousVariationIdsExcluded"),
    "load-submissions": ("submissionSummaryRowsRead", "matchedSubmissionRows"),
    "emit": ("outputRows",),
}
VARIANT_REQUIRED_COLUMNS = {
    "Type",
    "GeneSymbol",
    "ClinicalSignificance",
    "LastEvaluated",
    "RCVaccession",
    "PhenotypeIDS",
    "PhenotypeList",
    "Assembly",
    "Chromosome",
    "ReviewStatus",
    "VariationID",
    "PositionVCF",
    "ReferenceAlleleVCF",
    "AlternateAlleleVCF",
}
SUBMISSION_REQUIRED_COLUMNS = {
    "VariationID",
    "ClinicalSignificance",
    "DateLastEvaluated",
    "SubmittedPhenotypeInfo",
    "ReportedPhenotypeInfo",
    "ReviewStatus",
    "SCV",
    "SubmittedGeneSymbol",
}
csv.field_size_limit(2_147_483_647)


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a BGZF+Tabix ClinVar comparator sidecar from monthly TSV gzip files."
    )
    parser.add_argument("--variant-summary", required=True, type=pathlib.Path)
    parser.add_argument("--submission-summary", required=True, type=pathlib.Path)
    parser.add_argument("--variant-summary-url", required=True)
    parser.add_argument("--variant-summary-published-at", required=True)
    parser.add_argument("--submission-summary-url", required=True)
    parser.add_argument("--submission-summary-published-at", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--manifest-output", type=pathlib.Path)
    parser.add_argument("--source-release", required=True)
    parser.add_argument("--resource-id", required=True)
    parser.add_argument("--base-clinvar-resource-id", required=True)
    parser.add_argument("--base-clinvar-resource-version", required=True)
    parser.add_argument("--base-clinvar-identity-fingerprint", required=True)
    parser.add_argument("--bgzip", default=os.environ.get("BGZIP") or "bgzip")
    parser.add_argument("--tabix", default=os.environ.get("TABIX") or "tabix")
    parser.add_argument("--force", action="store_true")
    parser.add_argument(
        "--stage",
        choices=("all",) + STAGES,
        default="all",
        help="Run one resumable build stage; the default 'all' keeps the original one-process behavior.",
    )
    parser.add_argument(
        "--work-database",
        type=pathlib.Path,
        help="Persistent SQLite work database required when --stage is not 'all'.",
    )
    return parser.parse_args(argv)


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def present(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    normalized = value.strip()
    if not normalized or normalized in {".", "-"}:
        return None
    return normalized


def split_accessions(value: Optional[str]) -> List[str]:
    normalized = present(value)
    if normalized is None:
        return []
    result: List[str] = []
    seen = set()
    for item in normalized.split("|"):
        accession = item.strip()
        if accession and accession not in seen:
            result.append(accession)
            seen.add(accession)
    return result


def normalized_reader(path: pathlib.Path) -> Iterator[Dict[str, str]]:
    with gzip.open(path, "rt", encoding="utf-8-sig", errors="strict", newline="") as handle:
        header, header_line = find_header(handle, path)
        reader = csv.DictReader(handle, fieldnames=header, delimiter="\t")
        for line_number, row in enumerate(reader, start=header_line + 1):
            row["__source_logical_name"] = path.name
            row["__source_line"] = str(line_number)
            yield row


def read_header(path: pathlib.Path) -> List[str]:
    with gzip.open(path, "rt", encoding="utf-8-sig", errors="strict", newline="") as handle:
        header, _line_number = find_header(handle, path)
    return header


def find_header(handle: TextIO, path: pathlib.Path) -> Tuple[List[str], int]:
    for line_number, line in enumerate(handle, start=1):
        if line.startswith("#"):
            if "\t" in line:
                return (
                    [name.lstrip("#").strip() for name in line.rstrip("\r\n").split("\t")],
                    line_number,
                )
            continue
        if line.strip():
            raise ValueError(f"Unexpected content before TSV header in {path.name}")
    raise ValueError(f"Missing TSV header in {path.name}")


def source_locator(row: Dict[str, str]) -> Dict[str, object]:
    return {
        "logicalName": row["__source_logical_name"],
        "line": int(row["__source_line"]),
    }


def require_columns(path: pathlib.Path, expected: Iterable[str]) -> None:
    columns = set(read_header(path))
    missing = sorted(set(expected) - columns)
    if missing:
        raise ValueError(f"{path.name} is missing required columns: {', '.join(missing)}")


def positive_integer(value: Optional[str]) -> Optional[int]:
    normalized = present(value)
    if normalized is None or not normalized.isdigit():
        return None
    number = int(normalized)
    return number if number > 0 else None


def canonical_snv_base(value: Optional[str]) -> Optional[str]:
    normalized = present(value)
    if normalized is None:
        return None
    normalized = normalized.upper()
    return normalized if normalized in {"A", "C", "G", "T"} else None


def is_simple_snv_type(value: Optional[str]) -> bool:
    return (present(value) or "").lower() in {
        "single nucleotide variant",
        "snv",
    }


def variant_source_record(row: Dict[str, str]) -> Dict[str, object]:
    classification = present(row.get("ClinicalSignificance"))
    return {
        "aggregateClassification": classification,
        "condition": {
            "identifiersRaw": present(row.get("PhenotypeIDS")),
            "namesRaw": present(row.get("PhenotypeList")),
        },
        "dateLastEvaluated": present(row.get("LastEvaluated")),
        "geneSymbol": present(row.get("GeneSymbol")),
        "rcvAccessions": split_accessions(row.get("RCVaccession")),
        "reviewStatus": present(row.get("ReviewStatus")),
        "sourceLocator": source_locator(row),
    }


def submission_source_record(row: Dict[str, str]) -> Dict[str, object]:
    return {
        "classification": present(row.get("ClinicalSignificance")),
        "condition": {
            "reportedRaw": present(row.get("ReportedPhenotypeInfo")),
            "submittedRaw": present(row.get("SubmittedPhenotypeInfo")),
        },
        "dateLastEvaluated": present(row.get("DateLastEvaluated")),
        "geneSymbol": present(row.get("SubmittedGeneSymbol")),
        "reviewStatus": present(row.get("ReviewStatus")),
        "scv": present(row.get("SCV")),
        "sourceLocator": source_locator(row),
    }


def create_database(path: pathlib.Path, staged: bool = False) -> sqlite3.Connection:
    connection = sqlite3.connect(str(path))
    if staged:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=FULL")
    else:
        connection.execute("PRAGMA journal_mode=OFF")
        connection.execute("PRAGMA synchronous=OFF")
    connection.execute("PRAGMA temp_store=FILE")
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS variant_rows (
            variation_id INTEGER NOT NULL,
            allele_key TEXT NOT NULL,
            chromosome TEXT NOT NULL,
            position INTEGER NOT NULL,
            reference_allele TEXT NOT NULL,
            alternate_allele TEXT NOT NULL,
            source_json TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS submission_rows (
            variation_id INTEGER NOT NULL,
            source_json TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS build_metadata (
            key TEXT PRIMARY KEY,
            value_json TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS build_stats (
            key TEXT PRIMARY KEY,
            value INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS completed_stages (
            stage TEXT PRIMARY KEY
        );
        """
    )
    return connection


def load_variant_rows(connection: sqlite3.Connection, path: pathlib.Path) -> Dict[str, int]:
    stats = {
        "variantSummaryRowsRead": 0,
        "grch38SimpleSnvRows": 0,
    }
    pending: List[Tuple[object, ...]] = []
    for row in normalized_reader(path):
        stats["variantSummaryRowsRead"] += 1
        if present(row.get("Assembly")) != "GRCh38" or not is_simple_snv_type(row.get("Type")):
            continue
        variation_id = positive_integer(row.get("VariationID"))
        position = positive_integer(row.get("PositionVCF"))
        reference = canonical_snv_base(row.get("ReferenceAlleleVCF"))
        alternate = canonical_snv_base(row.get("AlternateAlleleVCF"))
        chromosome = present(row.get("Chromosome"))
        if (
            variation_id is None
            or position is None
            or chromosome is None
            or reference is None
            or alternate is None
            or reference == alternate
        ):
            continue
        allele_key = f"{chromosome}:{position}:{reference}:{alternate}"
        pending.append(
            (
                variation_id,
                allele_key,
                chromosome,
                position,
                reference,
                alternate,
                canonical_json(variant_source_record(row)),
            )
        )
        stats["grch38SimpleSnvRows"] += 1
        if len(pending) >= 10_000:
            connection.executemany(
                "INSERT INTO variant_rows VALUES (?, ?, ?, ?, ?, ?, ?)", pending
            )
            pending.clear()
    if pending:
        connection.executemany(
            "INSERT INTO variant_rows VALUES (?, ?, ?, ?, ?, ?, ?)", pending
        )
    connection.execute(
        "CREATE INDEX variant_rows_variation_id_idx ON variant_rows(variation_id)"
    )
    return stats


def prepare_eligible_variants(connection: sqlite3.Connection) -> Dict[str, int]:
    stats: Dict[str, int] = {}
    connection.execute(
        """
        CREATE TABLE eligible_variants AS
        SELECT variation_id,
               MIN(chromosome) AS chromosome,
               MIN(position) AS position,
               MIN(reference_allele) AS reference_allele,
               MIN(alternate_allele) AS alternate_allele
        FROM variant_rows
        GROUP BY variation_id
        HAVING COUNT(DISTINCT allele_key) = 1
        """
    )
    connection.execute(
        "CREATE UNIQUE INDEX eligible_variants_id_idx ON eligible_variants(variation_id)"
    )
    connection.execute(
        "CREATE INDEX eligible_variants_locus_idx "
        "ON eligible_variants(chromosome, position, variation_id)"
    )
    distinct_variants = connection.execute(
        "SELECT COUNT(DISTINCT variation_id) FROM variant_rows"
    ).fetchone()[0]
    eligible_variants = connection.execute(
        "SELECT COUNT(*) FROM eligible_variants"
    ).fetchone()[0]
    stats["eligibleVariationIds"] = int(eligible_variants)
    stats["ambiguousVariationIdsExcluded"] = int(distinct_variants - eligible_variants)
    return stats


def load_variants(connection: sqlite3.Connection, path: pathlib.Path) -> Dict[str, int]:
    stats = load_variant_rows(connection, path)
    stats.update(prepare_eligible_variants(connection))
    connection.commit()
    return stats


def load_submissions(
    connection: sqlite3.Connection, path: pathlib.Path, eligible_ids: set[int]
) -> Dict[str, int]:
    stats = {
        "submissionSummaryRowsRead": 0,
        "matchedSubmissionRows": 0,
    }
    pending: List[Tuple[int, str]] = []
    for row in normalized_reader(path):
        stats["submissionSummaryRowsRead"] += 1
        variation_id = positive_integer(row.get("VariationID"))
        if variation_id is None or variation_id not in eligible_ids:
            continue
        pending.append((variation_id, canonical_json(submission_source_record(row))))
        stats["matchedSubmissionRows"] += 1
        if len(pending) >= 10_000:
            connection.executemany("INSERT INTO submission_rows VALUES (?, ?)", pending)
            pending.clear()
    if pending:
        connection.executemany("INSERT INTO submission_rows VALUES (?, ?)", pending)
    connection.execute(
        "CREATE INDEX submission_rows_variation_id_idx ON submission_rows(variation_id)"
    )
    return stats


def json_groups(cursor: sqlite3.Cursor) -> Iterator[Tuple[int, str]]:
    for variation_id, values in itertools.groupby(cursor, key=lambda item: int(item[0])):
        # source_json is already canonical and the query orders it explicitly.
        # Joining those values is byte-equivalent to parsing and serializing the
        # same ordered list, while avoiding millions of redundant JSON decodes.
        yield variation_id, "[" + ",".join(str(item[1]) for item in values) + "]"


def conflict_state(records: Sequence[Dict[str, object]]) -> str:
    classifications = [
        str(record.get("aggregateClassification") or "").lower() for record in records
    ]
    if any("conflict" in classification for classification in classifications):
        return "CONFLICTING"
    if any(classifications):
        return "NO_CONFLICT_REPORTED"
    return "UNKNOWN"


def gene_symbols(
    variant_records: Sequence[Dict[str, object]],
    submission_records: Sequence[Dict[str, object]],
) -> List[str]:
    result = {
        str(record["geneSymbol"])
        for record in list(variant_records) + list(submission_records)
        if record.get("geneSymbol")
    }
    return sorted(result)


def create_record_bundle(connection: sqlite3.Connection, table: str, source: str) -> None:
    if (table, source) not in {
        ("variant_bundles", "variant_rows"),
        ("submission_bundles", "submission_rows"),
    }:
        raise ValueError("Unsupported record bundle")
    connection.execute(
        f"CREATE TABLE {table} (variation_id INTEGER PRIMARY KEY, records_json TEXT NOT NULL)"
    )
    cursor = connection.execute(
        f"SELECT variation_id, source_json FROM {source} ORDER BY variation_id, source_json"
    )
    pending: List[Tuple[int, str]] = []
    for variation_id, records_json in json_groups(cursor):
        pending.append((variation_id, records_json))
        if len(pending) >= 10_000:
            connection.executemany(f"INSERT INTO {table} VALUES (?, ?)", pending)
            pending.clear()
    if pending:
        connection.executemany(f"INSERT INTO {table} VALUES (?, ?)", pending)


def create_record_bundles(connection: sqlite3.Connection) -> None:
    create_record_bundle(connection, "variant_bundles", "variant_rows")
    create_record_bundle(connection, "submission_bundles", "submission_rows")
    connection.commit()


def iter_sidecar_rows(connection: sqlite3.Connection) -> Iterator[List[str]]:
    cursor = connection.execute(
        """
        SELECT variation_id, chromosome, position, reference_allele, alternate_allele
        FROM eligible_variants INDEXED BY eligible_variants_id_idx
        ORDER BY variation_id
        """
    )
    while True:
        eligible = cursor.fetchmany(10_000)
        if not eligible:
            return
        first_id = int(eligible[0][0])
        last_id = int(eligible[-1][0])
        variants = {
            int(row[0]): str(row[1])
            for row in connection.execute(
                "SELECT variation_id, records_json FROM variant_bundles "
                "WHERE variation_id BETWEEN ? AND ? ORDER BY variation_id",
                (first_id, last_id),
            )
        }
        submissions_by_id = {
            int(row[0]): str(row[1])
            for row in connection.execute(
                "SELECT variation_id, records_json FROM submission_bundles "
                "WHERE variation_id BETWEEN ? AND ? ORDER BY variation_id",
                (first_id, last_id),
            )
        }
        for value in eligible:
            variation_id = int(value[0])
            chromosome = str(value[1])
            position = int(value[2])
            reference = str(value[3])
            alternate = str(value[4])
            variant_records = json.loads(variants[variation_id])
            submissions = json.loads(submissions_by_id.get(variation_id, "[]"))
            yield [
                "VID",
                str(variation_id),
                str(variation_id),
                str(variation_id),
                "GRCh38",
                chromosome,
                str(position),
                reference,
                alternate,
                canonical_json(gene_symbols(variant_records, submissions)),
                conflict_state(variant_records),
                canonical_json(variant_records),
                canonical_json(submissions),
            ]


def tool_version(command: str) -> str:
    completed = subprocess.run(
        [command, "--version"], text=True, capture_output=True, check=False
    )
    line = (completed.stdout or completed.stderr).splitlines()
    return line[0].strip() if line else "unknown"


def write_bgzf_sidecar(
    connection: sqlite3.Connection, output: pathlib.Path, bgzip: str, force: bool
) -> int:
    if output.exists() and not force:
        raise FileExistsError(f"Output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    if temporary.exists():
        temporary.unlink()
    header = [
        "#index_contig",
        "index_start",
        "index_end",
        "variation_id",
        "assembly",
        "chromosome",
        "position",
        "reference_allele",
        "alternate_allele",
        "gene_symbols_json",
        "conflict_state",
        "variant_summary_records_json",
        "submissions_json",
    ]
    row_count = 0
    with temporary.open("wb") as compressed:
        process = subprocess.Popen(
            [bgzip, "-c"],
            stdin=subprocess.PIPE,
            stdout=compressed,
            stderr=subprocess.PIPE,
        )
        assert process.stdin is not None
        with process.stdin:
            process.stdin.write(("\t".join(header) + "\n").encode("utf-8"))
            for row in iter_sidecar_rows(connection):
                process.stdin.write(("\t".join(row) + "\n").encode("utf-8"))
                row_count += 1
        stderr = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
        if process.stderr is not None:
            process.stderr.close()
        return_code = process.wait()
    if return_code != 0:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(f"bgzip failed: {stderr.strip()}")
    os.replace(temporary, output)
    return row_count


def create_tabix_index(output: pathlib.Path, tabix: str, force: bool) -> pathlib.Path:
    command = [tabix]
    if force:
        command.append("-f")
    command.extend(["-s", "1", "-b", "2", "-e", "3", "-S", "1", str(output)])
    completed = subprocess.run(command, text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"tabix failed: {(completed.stderr or completed.stdout).strip()}")
    index = pathlib.Path(str(output) + ".tbi")
    if not index.is_file():
        raise RuntimeError(f"tabix did not create {index.name}")
    return index


def source_identity(
    path: pathlib.Path, source_url: str, published_at: str
) -> Dict[str, object]:
    return {
        "logicalName": path.name,
        "sourceUrl": source_url,
        "publishedAt": published_at,
        "byteLength": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def build_manifest(
    args: argparse.Namespace,
    output: pathlib.Path,
    index: pathlib.Path,
    stats: Dict[str, int],
    source_identities: Optional[Dict[str, Dict[str, object]]] = None,
) -> Dict[str, object]:
    variant_source = (
        source_identities["variantSummary"]
        if source_identities is not None
        else source_identity(
            args.variant_summary,
            args.variant_summary_url,
            args.variant_summary_published_at,
        )
    )
    submission_source = (
        source_identities["submissionSummary"]
        if source_identities is not None
        else source_identity(
            args.submission_summary,
            args.submission_summary_url,
            args.submission_summary_published_at,
        )
    )
    identity = {
        "assembly": "GRCh38",
        "baseClinvarIdentityFingerprint": args.base_clinvar_identity_fingerprint,
        "format": SIDECAR_FORMAT,
        "sourceRelease": args.source_release,
        "submissionSummaryPublishedAt": args.submission_summary_published_at,
        "submissionSummarySha256": submission_source["sha256"],
        "variantSummaryPublishedAt": args.variant_summary_published_at,
        "variantSummarySha256": variant_source["sha256"],
    }
    return {
        "manifestVersion": MANIFEST_VERSION,
        "resourceId": args.resource_id,
        "resourceVersion": args.source_release,
        "identityFingerprintSha256": hashlib.sha256(
            canonical_json(identity).encode("utf-8")
        ).hexdigest(),
        "identity": identity,
        "baseClinvarResource": {
            "resourceId": args.base_clinvar_resource_id,
            "resourceVersion": args.base_clinvar_resource_version,
            "identityFingerprintSha256": args.base_clinvar_identity_fingerprint,
        },
        "sources": {
            "variantSummary": variant_source,
            "submissionSummary": submission_source,
        },
        "derivation": {
            "builder": "build_clinvar_comparator_sidecar.py",
            "builderVersion": BUILDER_VERSION,
            "assembly": "GRCh38",
            "variantScope": "simple unique SNV",
            "joinKey": "VariationID",
            "conditionHandling": "preserve each source row and raw condition grouping",
            "submittedAssertionHandling": "one submission_summary row per submissions JSON element",
            "vcfClnsigscvUsage": "not used as submitted assertion content",
            "pythonVersion": ".".join(str(value) for value in sys.version_info[:3]),
        },
        "output": {
            "logicalName": output.name,
            "byteLength": output.stat().st_size,
            "sha256": sha256_file(output),
            "indexLogicalName": index.name,
            "indexByteLength": index.stat().st_size,
            "indexSha256": sha256_file(index),
            "indexType": "TABIX_TBI",
            "indexCoordinates": "Variation ID; native GRCh38 exact allele is revalidated on lookup",
            "bgzipVersion": tool_version(args.bgzip),
            "tabixVersion": tool_version(args.tabix),
        },
        "counts": stats,
        "clinicalValidation": False,
        "patientDataIncluded": False,
    }


def write_manifest(path: pathlib.Path, manifest: Dict[str, object], force: bool) -> None:
    if path.exists() and not force:
        raise FileExistsError(f"Manifest already exists: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def manifest_output_path(args: argparse.Namespace) -> pathlib.Path:
    output = args.output.resolve()
    return (
        args.manifest_output.resolve()
        if args.manifest_output
        else output.with_name(output.name + ".manifest.json")
    )


def staged_configuration(args: argparse.Namespace) -> Dict[str, object]:
    return {
        "databaseVersion": STAGED_DATABASE_VERSION,
        "sourceRelease": args.source_release,
        "resourceId": args.resource_id,
        "baseClinvarResourceId": args.base_clinvar_resource_id,
        "baseClinvarResourceVersion": args.base_clinvar_resource_version,
        "baseClinvarIdentityFingerprint": args.base_clinvar_identity_fingerprint,
        "variantSummaryLogicalName": args.variant_summary.name,
        "variantSummaryUrl": args.variant_summary_url,
        "variantSummaryPublishedAt": args.variant_summary_published_at,
        "submissionSummaryLogicalName": args.submission_summary.name,
        "submissionSummaryUrl": args.submission_summary_url,
        "submissionSummaryPublishedAt": args.submission_summary_published_at,
        "outputLogicalName": args.output.name,
        "manifestLogicalName": manifest_output_path(args).name,
    }


def metadata_get(connection: sqlite3.Connection, key: str) -> Optional[object]:
    row = connection.execute(
        "SELECT value_json FROM build_metadata WHERE key = ?", (key,)
    ).fetchone()
    return json.loads(row[0]) if row else None


def metadata_put(connection: sqlite3.Connection, key: str, value: object) -> None:
    connection.execute(
        "INSERT OR REPLACE INTO build_metadata(key, value_json) VALUES (?, ?)",
        (key, canonical_json(value)),
    )


def stage_completed(connection: sqlite3.Connection, stage: str) -> bool:
    return (
        connection.execute(
            "SELECT 1 FROM completed_stages WHERE stage = ?", (stage,)
        ).fetchone()
        is not None
    )


def require_stage_prerequisite(connection: sqlite3.Connection, stage: str) -> None:
    prerequisite = STAGE_PREREQUISITES.get(stage)
    if prerequisite and not stage_completed(connection, prerequisite):
        raise ValueError(f"Stage {stage} requires completed stage {prerequisite}")


def invalidate_stage_and_later(connection: sqlite3.Connection, stage: str) -> None:
    start = STAGES.index(stage)
    invalidated = STAGES[start:]
    connection.executemany(
        "DELETE FROM completed_stages WHERE stage = ?",
        ((value,) for value in invalidated),
    )
    keys = [key for value in invalidated for key in STAGE_STATS.get(value, ())]
    connection.executemany(
        "DELETE FROM build_stats WHERE key = ?", ((key,) for key in keys)
    )


def save_stats(connection: sqlite3.Connection, stats: Dict[str, int]) -> None:
    connection.executemany(
        "INSERT OR REPLACE INTO build_stats(key, value) VALUES (?, ?)",
        ((key, int(value)) for key, value in stats.items()),
    )


def load_saved_stats(connection: sqlite3.Connection) -> Dict[str, int]:
    return {
        str(row[0]): int(row[1])
        for row in connection.execute("SELECT key, value FROM build_stats")
    }


def finish_stage(
    connection: sqlite3.Connection, stage: str, stats: Optional[Dict[str, int]] = None
) -> None:
    if stats:
        save_stats(connection, stats)
    connection.execute(
        "INSERT OR REPLACE INTO completed_stages(stage) VALUES (?)", (stage,)
    )


def verify_staged_configuration(
    connection: sqlite3.Connection, args: argparse.Namespace
) -> Dict[str, Dict[str, object]]:
    stored = metadata_get(connection, "configuration")
    if stored is None or not stage_completed(connection, "initialize"):
        raise ValueError("Work database has not completed stage initialize")
    if stored != staged_configuration(args):
        raise ValueError("Work database configuration does not match this invocation")
    sources = metadata_get(connection, "sourceIdentities")
    if not isinstance(sources, dict):
        raise ValueError("Work database is missing source identities")
    return sources  # type: ignore[return-value]


def verify_source_identity(path: pathlib.Path, expected: Dict[str, object]) -> None:
    actual = {
        "logicalName": path.name,
        "byteLength": path.stat().st_size,
        "sha256": sha256_file(path),
    }
    for key, value in actual.items():
        if expected.get(key) != value:
            raise ValueError(f"Source identity mismatch for {path.name}: {key}")


def close_staged_database(connection: sqlite3.Connection) -> None:
    try:
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    finally:
        connection.close()


def initialize_staged_build(args: argparse.Namespace) -> Dict[str, object]:
    assert args.work_database is not None
    require_columns(args.variant_summary, VARIANT_REQUIRED_COLUMNS)
    require_columns(args.submission_summary, SUBMISSION_REQUIRED_COLUMNS)
    sources = {
        "variantSummary": source_identity(
            args.variant_summary,
            args.variant_summary_url,
            args.variant_summary_published_at,
        ),
        "submissionSummary": source_identity(
            args.submission_summary,
            args.submission_summary_url,
            args.submission_summary_published_at,
        ),
    }
    database = args.work_database.resolve()
    database.parent.mkdir(parents=True, exist_ok=True)
    connection = create_database(database, staged=True)
    try:
        previous_configuration = metadata_get(connection, "configuration")
        previous_sources = metadata_get(connection, "sourceIdentities")
        if previous_configuration not in (None, staged_configuration(args)):
            raise ValueError("Work database belongs to a different build configuration")
        if previous_sources not in (None, sources):
            raise ValueError("Work database belongs to different source files")
        with connection:
            metadata_put(connection, "configuration", staged_configuration(args))
            metadata_put(connection, "sourceIdentities", sources)
            finish_stage(connection, "initialize")
    finally:
        close_staged_database(connection)
    return {"stage": "initialize", "status": "COMPLETED"}


def run_staged_build(args: argparse.Namespace) -> Dict[str, object]:
    if args.work_database is None:
        raise ValueError("--work-database is required with a staged build")
    if args.stage == "initialize":
        return initialize_staged_build(args)
    database = args.work_database.resolve()
    if not database.is_file():
        raise ValueError("Work database does not exist; run stage initialize first")
    connection = create_database(database, staged=True)
    try:
        sources = verify_staged_configuration(connection, args)
        require_stage_prerequisite(connection, args.stage)
        stage_stats: Dict[str, int] = {}
        if args.stage == "load-variants":
            verify_source_identity(args.variant_summary, sources["variantSummary"])
            with connection:
                invalidate_stage_and_later(connection, args.stage)
                connection.execute("DROP INDEX IF EXISTS variant_rows_variation_id_idx")
                connection.execute("DELETE FROM variant_rows")
                stage_stats = load_variant_rows(connection, args.variant_summary)
                finish_stage(connection, args.stage, stage_stats)
        elif args.stage == "prepare-eligible":
            with connection:
                invalidate_stage_and_later(connection, args.stage)
                connection.execute("DROP TABLE IF EXISTS eligible_variants")
                stage_stats = prepare_eligible_variants(connection)
                finish_stage(connection, args.stage, stage_stats)
        elif args.stage == "load-submissions":
            verify_source_identity(args.submission_summary, sources["submissionSummary"])
            eligible_ids = {
                int(row[0])
                for row in connection.execute("SELECT variation_id FROM eligible_variants")
            }
            with connection:
                invalidate_stage_and_later(connection, args.stage)
                connection.execute("DROP INDEX IF EXISTS submission_rows_variation_id_idx")
                connection.execute("DELETE FROM submission_rows")
                stage_stats = load_submissions(
                    connection, args.submission_summary, eligible_ids
                )
                finish_stage(connection, args.stage, stage_stats)
        elif args.stage == "bundle-variants":
            with connection:
                invalidate_stage_and_later(connection, args.stage)
                connection.execute("DROP TABLE IF EXISTS variant_bundles")
                create_record_bundle(connection, "variant_bundles", "variant_rows")
                finish_stage(connection, args.stage)
        elif args.stage == "bundle-submissions":
            with connection:
                invalidate_stage_and_later(connection, args.stage)
                connection.execute("DROP TABLE IF EXISTS submission_bundles")
                create_record_bundle(connection, "submission_bundles", "submission_rows")
                finish_stage(connection, args.stage)
        elif args.stage == "emit":
            stats = load_saved_stats(connection)
            required_stats = {
                key for stage in STAGES for key in STAGE_STATS.get(stage, ()) if key != "outputRows"
            }
            if not required_stats.issubset(stats):
                raise ValueError("Work database is missing prerequisite build counts")
            output = args.output.resolve()
            row_count = write_bgzf_sidecar(connection, output, args.bgzip, True)
            index = create_tabix_index(output, args.tabix, True)
            stats["outputRows"] = row_count
            manifest = build_manifest(args, output, index, stats, sources)
            write_manifest(manifest_output_path(args), manifest, True)
            with connection:
                invalidate_stage_and_later(connection, args.stage)
                finish_stage(connection, args.stage, {"outputRows": row_count})
            return manifest
        else:
            raise ValueError(f"Unsupported stage: {args.stage}")
        return {"stage": args.stage, "status": "COMPLETED", "counts": stage_stats}
    finally:
        close_staged_database(connection)


def build(args: argparse.Namespace) -> Dict[str, object]:
    if args.stage != "all":
        return run_staged_build(args)
    require_columns(args.variant_summary, VARIANT_REQUIRED_COLUMNS)
    require_columns(args.submission_summary, SUBMISSION_REQUIRED_COLUMNS)
    output = args.output.resolve()
    manifest_output = (
        args.manifest_output.resolve()
        if args.manifest_output
        else output.with_name(output.name + ".manifest.json")
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".clinvar-sidecar-build-", dir=output.parent) as directory:
        connection = create_database(pathlib.Path(directory) / "join.sqlite")
        try:
            stats = load_variants(connection, args.variant_summary)
            eligible_ids = {
                int(row[0]) for row in connection.execute("SELECT variation_id FROM eligible_variants")
            }
            stats.update(load_submissions(connection, args.submission_summary, eligible_ids))
            create_record_bundles(connection)
            row_count = write_bgzf_sidecar(connection, output, args.bgzip, args.force)
        finally:
            connection.close()
    index = create_tabix_index(output, args.tabix, args.force)
    stats["outputRows"] = row_count
    manifest = build_manifest(args, output, index, stats)
    write_manifest(manifest_output, manifest, args.force)
    return manifest


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    result = build(args)
    if "manifestVersion" in result:
        result = {
            "resourceId": result["resourceId"],
            "identityFingerprintSha256": result["identityFingerprintSha256"],
            "outputRows": result["counts"]["outputRows"],
        }
    print(canonical_json(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
