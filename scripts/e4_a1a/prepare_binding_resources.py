"""Prepare the authorized E4-A1A snapshots offline using only the standard library.

This creates a gene identity crosswalk, not a transcript-selection resource.
ClinGen classifications remain source evidence, not patient diagnoses.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import gzip
import hashlib
import io
import json
import re
from collections import Counter, defaultdict
from email.utils import parsedate_to_datetime
from pathlib import Path


PARSER_VERSION = "1.0.0"
LIMIT = 100_000_000
MANE_NAME = "MANE.GRCh38.v1.5.summary.txt.gz"
MANE_URL = "https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/release_1.5/" + MANE_NAME
CLINGEN_URL = "https://search.clinicalgenome.org/kb/gene-validity/download"
MANE_HEADER = [
    "#NCBI_GeneID", "Ensembl_Gene", "HGNC_ID", "symbol", "name", "RefSeq_nuc",
    "RefSeq_prot", "Ensembl_nuc", "Ensembl_prot", "MANE_status", "GRCh38_chr",
    "chr_start", "chr_end", "chr_strand",
]
CLINGEN_HEADER = [
    "GENE SYMBOL", "GENE ID (HGNC)", "DISEASE LABEL", "DISEASE ID (MONDO)",
    "MOI", "SOP", "CLASSIFICATION", "ONLINE REPORT", "CLASSIFICATION DATE", "GCEP",
]
CLASSIFICATIONS = {
    "Definitive", "Strong", "Moderate", "Limited", "Disputed", "Refuted",
    "No Known Disease Relationship", "",
}
MOIS = {"AD", "AR", "XL", "SD", "UD", "MT", ""}
INTAKE_POLICY = {
    "policyId": "PATIENT_DISEASE_BINDING_INTAKE", "version": "1.0.0",
    "readyClassifications": ["Definitive", "Strong", "Moderate"],
    "reviewRequiredClassifications": ["Limited"],
    "notReadyClassifications": ["Disputed", "Refuted", "No Known Disease Relationship", ""],
    "catalogMoiMappings": {"AD": "AUTOSOMAL_DOMINANT", "AR": "AUTOSOMAL_RECESSIVE"},
    "unmappedCatalogMoi": ["XL", "SD", "UD", "MT"],
    "claim": "VARVIS_BINDING_INTAKE_ONLY_NOT_PATIENT_DIAGNOSIS",
}


class ResourceError(ValueError):
    """An invalid snapshot must not yield a partially trusted catalog."""


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def header_sha256(header: list[str]) -> str:
    """SHA-256 of the compact UTF-8 JSON header array, with no final newline."""
    return sha256(json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise ResourceError(reason)


def require_retrieval_utc(value: object) -> None:
    require(isinstance(value, str) and bool(re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
        r"(?:\.[0-9]{1,9})?(?:Z|\+00:00)", value)), "RETRIEVAL_UTC_REQUIRED")
    try:
        retrieved = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ResourceError("RETRIEVAL_UTC_REQUIRED") from error
    require(retrieved.utcoffset() == dt.timedelta(0), "RETRIEVAL_UTC_REQUIRED")


def rows_with_lines(raw: bytes, delimiter: str) -> list[tuple[int, list[str]]]:
    try:
        reader = csv.reader(io.StringIO(raw.decode("utf-8-sig"), newline=""),
                            delimiter=delimiter, strict=True)
        result = []
        previous_line = 0
        for row in reader:
            result.append((previous_line + 1, row))
            previous_line = reader.line_num
        return result
    except (UnicodeError, csv.Error) as error:
        raise ResourceError("CSV_FORMAT_DRIFT") from error


def parse_mane(raw: bytes) -> tuple[list[dict], dict]:
    rows = rows_with_lines(raw, "\t")
    require(bool(rows) and rows[0][1] == MANE_HEADER, "MANE_HEADER_DRIFT")
    identities: dict[tuple[str, str, str], list[int]] = defaultdict(list)
    rejected = Counter()
    missing_hgnc = []
    for line, values in rows[1:]:
        require(len(values) == len(MANE_HEADER), "MANE_ROW_WIDTH_DRIFT")
        row = dict(zip(MANE_HEADER, values))
        require(bool(re.fullmatch(r"GeneID:[1-9][0-9]*", row["#NCBI_GeneID"])),
                "MANE_NCBIGENE_DRIFT")
        require(bool(row["symbol"]) and row["symbol"] == row["symbol"].strip(),
                "MANE_SYMBOL_DRIFT")
        require(row["MANE_status"] in {"MANE Select", "MANE Plus Clinical"},
                "MANE_STATUS_DRIFT")
        if not row["HGNC_ID"]:
            rejected["HGNC_ID_MISSING"] += 1
            missing_hgnc.append((row["#NCBI_GeneID"][7:], row["symbol"]))
            continue
        require(bool(re.fullmatch(r"HGNC:[1-9][0-9]*", row["HGNC_ID"])),
                "MANE_HGNC_DRIFT")
        key = (row["#NCBI_GeneID"][7:], row["HGNC_ID"], row["symbol"])
        identities[key].append(line)
    # An incomplete row must not be hidden if it overlaps a retained identity.
    ncbi_ids = {key[0] for key in identities}
    symbols = {key[2] for key in identities}
    require(not any(ncbi in ncbi_ids or symbol in symbols for ncbi, symbol in missing_hgnc),
            "MANE_INCOMPLETE_IDENTITY_OVERLAP")
    records = [{"ncbiGeneId": key[0], "hgncId": key[1], "symbol": key[2],
                "sourceRows": source_rows} for key, source_rows in identities.items()]
    accepted = sum(len(lines) for lines in identities.values())
    return records, {
        "sourceCount": len(rows) - 1, "acceptedCount": accepted,
        "rejectedCount": sum(rejected.values()), "rejectedReasonCounts": dict(rejected),
        "aggregatedDuplicateCount": accepted - len(records),
    }


def parse_clingen(raw: bytes) -> tuple[list[dict], dict, str]:
    rows = rows_with_lines(raw, ",")
    require(len(rows) >= 6 and rows[4][1] == CLINGEN_HEADER, "CLINGEN_HEADER_DRIFT")
    require(all(len(row) == len(CLINGEN_HEADER) for _, row in rows),
            "CLINGEN_ROW_WIDTH_DRIFT")
    require(rows[0][1] == ["CLINGEN GENE DISEASE VALIDITY CURATIONS"] + [""] * 9,
            "CLINGEN_PREAMBLE_DRIFT")
    require(rows[2][1] == ["WEBPAGE: https://search.clinicalgenome.org/kb/gene-validity"]
            + [""] * 9, "CLINGEN_PREAMBLE_DRIFT")
    require(rows[1][1][1:] == [""] * 9 and bool(re.fullmatch(
        r"FILE CREATED: [0-9]{4}-[0-9]{2}-[0-9]{2}", rows[1][1][0])),
        "CLINGEN_FILE_DATE_DRIFT")
    file_date = rows[1][1][0][14:]
    try:
        dt.date.fromisoformat(file_date)
    except ValueError as error:
        raise ResourceError("CLINGEN_FILE_DATE_DRIFT") from error
    require(all(bool(value) and set(value) == {"+"} for index in (3, 5)
                for value in rows[index][1]), "CLINGEN_SEPARATOR_DRIFT")
    records = []
    for line, values in rows[6:]:
        row = dict(zip(CLINGEN_HEADER, values))
        require(bool(re.fullmatch(r"HGNC:[1-9][0-9]*", row["GENE ID (HGNC)"])),
                "CLINGEN_HGNC_DRIFT")
        require(bool(re.fullmatch(r"MONDO:[0-9]{7}", row["DISEASE ID (MONDO)"])),
                "CLINGEN_MONDO_DRIFT")
        require(bool(row["GENE SYMBOL"]) and bool(row["DISEASE LABEL"]),
                "CLINGEN_LABEL_DRIFT")
        require(row["MOI"] in MOIS, "CLINGEN_MOI_DRIFT")
        require(row["CLASSIFICATION"] in CLASSIFICATIONS, "CLINGEN_CLASSIFICATION_DRIFT")
        require(bool(re.fullmatch(r"SOP[1-9][0-9]*", row["SOP"])), "CLINGEN_SOP_DRIFT")
        require(bool(re.fullmatch(
            r"https://search\.clinicalgenome\.org/kb/gene-validity/(CGGV|CGGCIEX):[^\s?#]+",
            row["ONLINE REPORT"])),
            "CLINGEN_REPORT_LOCATOR_DRIFT")
        if row["CLASSIFICATION DATE"]:
            try:
                dt.datetime.fromisoformat(row["CLASSIFICATION DATE"].replace("Z", "+00:00"))
            except ValueError as error:
                raise ResourceError("CLINGEN_CLASSIFICATION_DATE_DRIFT") from error
        records.append({
            "hgncId": row["GENE ID (HGNC)"], "symbol": row["GENE SYMBOL"],
            "mondoId": row["DISEASE ID (MONDO)"], "diseaseLabel": row["DISEASE LABEL"],
            "moi": row["MOI"], "classification": row["CLASSIFICATION"],
            "classificationDate": row["CLASSIFICATION DATE"], "gcep": row["GCEP"],
            "reportLocator": row["ONLINE REPORT"], "sourceRow": line, "sop": row["SOP"],
        })
    return records, {
        "sourceCount": len(records), "acceptedCount": len(records), "rejectedCount": 0,
        "rejectedReasonCounts": {}, "classificationCounts": dict(Counter(
            row["classification"] for row in records)),
        "moiCounts": dict(Counter(row["moi"] for row in records)),
    }, file_date


def bounded_read(path: Path) -> bytes:
    require(path.stat().st_size <= LIMIT, "RESOURCE_LIMIT_EXCEEDED")
    return path.read_bytes()


def decompress(raw: bytes) -> bytes:
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
            decoded = stream.read(LIMIT + 1)
        require(len(decoded) <= LIMIT, "RESOURCE_LIMIT_EXCEEDED")
        return decoded
    except (EOFError, OSError) as error:
        raise ResourceError("GZIP_INVALID") from error


def metadata_resources(metadata: dict) -> dict:
    if isinstance(metadata["resources"], list):
        return {row["resource"]: {
            "sourceUrl": row["source_url"], "retrievedAt": row["retrieval_utc"],
            "rawSha256": row["raw_sha256"],
            "httpLastModified": row["http_headers"]["Last-Modified"],
        } for row in metadata["resources"]}
    return metadata["resources"]


def prepare(resource_root: Path, snapshot_dir: Path, metadata: dict) -> dict:
    resource_root, snapshot_dir = resource_root.resolve(), snapshot_dir.resolve()
    require(snapshot_dir.is_relative_to(resource_root), "SNAPSHOT_OUTSIDE_RESOURCE_ROOT")
    metadata = metadata_resources(metadata)
    mane_raw = bounded_read(snapshot_dir / MANE_NAME)
    clingen_files = list(snapshot_dir.glob("Clingen-Gene-Disease-Summary-*.csv.gz"))
    if not clingen_files:
        clingen_files = list(snapshot_dir.glob("Clingen-Gene-Disease-Summary-*.csv"))
    require(len(clingen_files) == 1, "CLINGEN_SNAPSHOT_NOT_UNIQUE")
    clingen_input = clingen_files[0]
    clingen_archive = bounded_read(clingen_input)
    clingen_raw = decompress(clingen_archive) if clingen_input.suffix == ".gz" else clingen_archive
    raw_data = {"mane": mane_raw, "clingen": clingen_raw}
    for name, url in (("mane", MANE_URL), ("clingen", CLINGEN_URL)):
        require(metadata[name]["sourceUrl"] == url, "SOURCE_URL_MISMATCH")
        require(sha256(raw_data[name]) == metadata[name]["rawSha256"], "RAW_HASH_MISMATCH")
        require_retrieval_utc(metadata[name]["retrievedAt"])
    mane, mane_counts = parse_mane(decompress(mane_raw))
    clingen, clingen_counts, file_date = parse_clingen(clingen_raw)
    require(clingen_input.name in {f"Clingen-Gene-Disease-Summary-{file_date}.csv",
            f"Clingen-Gene-Disease-Summary-{file_date}.csv.gz"}, "CLINGEN_FILENAME_DATE_MISMATCH")
    require(bool(mane) and bool(clingen), "EMPTY_RESOURCE")
    if clingen_input.suffix != ".gz":
        clingen_archive = gzip.compress(clingen_raw, mtime=0)
    clingen_archive_path = snapshot_dir / f"Clingen-Gene-Disease-Summary-{file_date}.csv.gz"
    manifest = {"profileId": "PATIENT_DISEASE_BINDING_INTAKE", "profileVersion": "1.0.0",
                "parserVersion": PARSER_VERSION, "intakePolicy": INTAKE_POLICY, "resources": {}}
    prepared = {}
    for name, records, counts, header, raw_path, archive in (
        ("mane", mane, mane_counts, MANE_HEADER, snapshot_dir / MANE_NAME, mane_raw),
        ("clingen", clingen, clingen_counts, CLINGEN_HEADER, clingen_archive_path, clingen_archive),
    ):
        derived_path = snapshot_dir / ("mane-crosswalk.json" if name == "mane" else "clingen-catalog.json")
        derived = json_bytes(records)
        source = metadata[name]
        modified = source["httpLastModified"]
        if name == "mane":
            source_date = parsedate_to_datetime(modified).date().isoformat()
        else:
            source_date = file_date
        manifest["resources"][name] = {
            "resourceId": "MANE_GRCh38_SUMMARY" if name == "mane" else "CLINGEN_GENE_DISEASE_VALIDITY",
            "resourceVersion": "1.5" if name == "mane" else file_date,
            "assembly": "GRCh38" if name == "mane" else "NOT_APPLICABLE",
            "sourceUrl": source["sourceUrl"], "retrievedAt": source["retrievedAt"],
            "fileDate": source_date,
            "fileDateSource": "HTTP Last-Modified" if name == "mane" else "CSV FILE CREATED preamble",
            "httpLastModified": modified, "bytes": len(raw_data[name]),
            "rawSha256": sha256(raw_data[name]), "archiveSha256": sha256(archive),
            "archiveBytes": len(archive), "rawHashScope": "original HTTP response bytes",
            "headerSha256": header_sha256(header), "header": header,
            "headerHashEncoding": "compact UTF-8 JSON header array; no trailing newline",
            "parserVersion": PARSER_VERSION, **counts,
            "filterPolicy": (
                "Exact NCBIGene/HGNC/symbol tuples only; reject empty HGNC and fail on overlap; "
                "aggregate transcript duplicates with sourceRows; retain conflicting complete tuples; "
                "no transcript selection."
                if name == "mane" else
                "Retain all relationships, exact-key duplicates, all known classifications including "
                "blank, and raw MOI codes; malformed identities or unknown domains fail the snapshot. "
                "Varvis binding intake v1 accepts Definitive/Strong/Moderate; Limited requires review; "
                "other classifications are NOT_READY. These are not patient diagnoses."
            ),
            "derivedSha256": sha256(derived), "derivedRecordCount": len(records),
            "rawRelativePath": raw_path.relative_to(resource_root).as_posix(),
            "derivedRelativePath": derived_path.relative_to(resource_root).as_posix(),
        }
        prepared[derived_path] = derived
    # Validate both sources before producing outputs; no download or network calls occur here.
    prepared[clingen_archive_path] = clingen_archive
    prepared[snapshot_dir / "manifest.json"] = json_bytes(manifest)
    for path, data in prepared.items():
        path.write_bytes(data)
        require(sha256(bounded_read(path)) == sha256(data), "WRITE_VERIFICATION_FAILED")
    verify(resource_root, manifest)
    return manifest


def verify(resource_root: Path, manifest: dict) -> None:
    root = resource_root.resolve()
    require(manifest["profileId"] == "PATIENT_DISEASE_BINDING_INTAKE"
            and manifest["profileVersion"] == "1.0.0"
            and manifest["parserVersion"] == PARSER_VERSION, "PROFILE_VERSION_MISMATCH")
    require(manifest["intakePolicy"] == INTAKE_POLICY, "INTAKE_POLICY_MISMATCH")
    require(set(manifest["resources"]) == {"mane", "clingen"}, "RESOURCE_SET_MISMATCH")
    for name, resource in manifest["resources"].items():
        require_retrieval_utc(resource["retrievedAt"])
        expected_header = MANE_HEADER if name == "mane" else CLINGEN_HEADER
        require(resource["header"] == expected_header
                and resource["headerSha256"] == header_sha256(expected_header), "HEADER_IDENTITY_MISMATCH")
        require(resource["parserVersion"] == PARSER_VERSION, "PARSER_VERSION_MISMATCH")
        require(resource["resourceId"] == ("MANE_GRCh38_SUMMARY" if name == "mane"
                else "CLINGEN_GENE_DISEASE_VALIDITY"), "RESOURCE_ID_MISMATCH")
        require(resource["sourceUrl"] == (MANE_URL if name == "mane" else CLINGEN_URL),
                "SOURCE_URL_MISMATCH")
        require(resource["assembly"] == ("GRCh38" if name == "mane" else "NOT_APPLICABLE"),
                "RESOURCE_ASSEMBLY_MISMATCH")
        paths = [root / resource[key] for key in ("rawRelativePath", "derivedRelativePath")]
        require(all(path.resolve().is_relative_to(root) for path in paths), "RESOURCE_PATH_ESCAPE")
        archive, derived = [bounded_read(path) for path in paths]
        require(len(archive) == resource["archiveBytes"], "ARCHIVE_BYTES_MISMATCH")
        require(sha256(archive) == resource["archiveSha256"], "ARCHIVE_HASH_MISMATCH")
        raw = decompress(archive) if name == "clingen" else archive
        require(len(raw) == resource["bytes"] and sha256(raw) == resource["rawSha256"],
                "RAW_HASH_MISMATCH")
        require(sha256(derived) == resource["derivedSha256"], "DERIVED_HASH_MISMATCH")
        records = json.loads(derived)
        require(len(records) == resource["derivedRecordCount"], "DERIVED_COUNT_MISMATCH")
        require(resource["sourceCount"] == resource["acceptedCount"] + resource["rejectedCount"],
                "SOURCE_COUNT_MISMATCH")
        if name == "mane":
            reparsed, counts = parse_mane(decompress(raw))
            source_date = parsedate_to_datetime(resource["httpLastModified"]).date().isoformat()
            require(resource["resourceVersion"] == "1.5", "RESOURCE_VERSION_MISMATCH")
            require(resource["fileDateSource"] == "HTTP Last-Modified", "FILE_DATE_SOURCE_MISMATCH")
        else:
            reparsed, counts, source_date = parse_clingen(raw)
            require(resource["resourceVersion"] == source_date, "RESOURCE_VERSION_MISMATCH")
            require(resource["fileDateSource"] == "CSV FILE CREATED preamble", "FILE_DATE_SOURCE_MISMATCH")
        require(resource["fileDate"] == source_date, "FILE_DATE_MISMATCH")
        require(records == reparsed, "DERIVED_CONTENT_MISMATCH")
        require(all(resource[key] == value for key, value in counts.items()), "PARSED_COUNT_MISMATCH")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resource-root", type=Path, required=True)
    parser.add_argument("--snapshot-dir", type=Path, required=True,
                        help="Directory relative to the resource root")
    parser.add_argument("--metadata", default="resource-probe.json",
                        help="Acquisition probe or existing manifest filename in snapshot directory")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    require(not args.snapshot_dir.is_absolute(), "RELATIVE_SNAPSHOT_DIR_REQUIRED")
    snapshot_dir = args.resource_root / args.snapshot_dir
    metadata = json.loads((snapshot_dir / args.metadata).read_text(encoding="utf-8"))
    if args.verify_only:
        verify(args.resource_root, metadata)
        print("PASS: resource hashes, records and counts verified offline")
    else:
        result = prepare(args.resource_root, snapshot_dir, metadata)
        print(json.dumps({"status": "PASS", "resources": {
            name: {key: row[key] for key in ("sourceCount", "acceptedCount", "rejectedCount",
                                           "derivedRecordCount", "derivedSha256")}
            for name, row in result["resources"].items()}}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
