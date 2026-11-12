"""Prepare source-preserving G2P mechanism candidates; never emit an executable PVS1 policy."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import io
import json
from pathlib import Path
import re

VERSION = "1.0.0"
SOURCE_URL = "https://www.ebi.ac.uk/gene2phenotype/api/panel/all/download/"
REQUIRED = {"g2p id", "gene symbol", "hgnc id", "disease name", "disease MONDO",
            "allelic requirement", "confidence", "molecular mechanism", "molecular mechanism support",
            "molecular mechanism categorisation", "molecular mechanism evidence", "variant consequence",
            "variant types", "publications", "panel", "comments", "date of last review", "review"}
MECHANISMS = {"loss of function": "LOSS_OF_FUNCTION", "gain of function": "GAIN_OF_FUNCTION",
              "dominant negative": "DOMINANT_NEGATIVE", "undetermined": "UNDETERMINED",
              "undetermined non-loss-of-function": "UNDETERMINED_NON_LOF"}
MOI = {"monoallelic_autosomal": "AUTOSOMAL_DOMINANT", "biallelic_autosomal": "AUTOSOMAL_RECESSIVE"}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def prepare(raw: bytes, expected_sha: str, source_version: str):
    digest = hashlib.sha256(raw).hexdigest()
    if not re.fullmatch(r"[a-f0-9]{64}", expected_sha) or digest != expected_sha:
        raise ValueError("G2P_SOURCE_SHA_MISMATCH")
    if not source_version.strip() or len(raw) > 8_000_000:
        raise ValueError("G2P_SOURCE_VERSION_OR_SIZE_INVALID")
    reader = csv.DictReader(io.StringIO(raw.decode("utf-8-sig")), strict=True)
    fields = reader.fieldnames or []
    if len(fields) != len(set(fields)) or not REQUIRED.issubset(fields):
        raise ValueError("G2P_SOURCE_COLUMNS_INVALID")
    records, groups, counts = [], defaultdict(list), Counter()
    for number, row in enumerate(reader, 1):
        if number > 20_000 or None in row or any(value is None for value in row.values()):
            raise ValueError("G2P_SOURCE_ROW_INVALID")
        # Keep every original field, including comments, restrictions, evidence and source review text.
        original = dict(row)
        text = {k: v.strip() for k, v in row.items()}
        if not re.fullmatch(r"G2P\d+", text["g2p id"]):
            raise ValueError("G2P_RECORD_ID_INVALID")
        hgnc = text["hgnc id"]
        if re.fullmatch(r"[1-9]\d*", hgnc):
            hgnc = "HGNC:" + hgnc
        if not re.fullmatch(r"HGNC:[1-9]\d*", hgnc):
            hgnc = None
        mondo = text["disease MONDO"]
        if not re.fullmatch(r"MONDO:\d{7}", mondo):
            mondo = None
        mechanism = MECHANISMS.get(text["molecular mechanism"], "UNRECOGNIZED_SOURCE_LABEL")
        allelic = text["allelic requirement"]
        reasons = ["SOURCE_RECORD_NOT_ADOPTED_FOR_PVS1", "TRANSCRIPT_EXON_APPLICABILITY_NOT_PROVIDED",
                   "NMD_SCOPE_NOT_PROVIDED"]
        if hgnc is None or mondo is None:
            reasons.append("EXACT_BACKGROUND_IDENTITY_UNRESOLVED")
        if mechanism != "LOSS_OF_FUNCTION":
            reasons.append("SOURCE_DOES_NOT_ASSERT_LOSS_OF_FUNCTION")
        if text["confidence"] not in {"strong", "definitive"}:
            reasons.append("SOURCE_ASSOCIATION_REQUIRES_ASSESSMENT")
        if allelic not in MOI:
            reasons.append("ALLELIC_REQUIREMENT_RETAINED_WITHOUT_MOI_INFERENCE")
        if text.get("cross cutting modifier") or text["molecular mechanism categorisation"] or text["comments"]:
            reasons.append("SOURCE_SCOPE_DETAILS_REQUIRE_REVIEW")
        record = {"recordId": f"{text['g2p id']}:{number}", "sourceRecordId": text["g2p id"],
                  "sourceRow": number, "sourceRecordSha256": hashlib.sha256(canonical(original)).hexdigest(),
                  "sourceRecord": original, "hgncId": hgnc, "mondoId": mondo,
                  "allelicRequirement": allelic, "moiCandidate": MOI.get(allelic),
                  "mechanismCandidate": mechanism, "sourceConfidence": text["confidence"],
                  "mechanismSupport": text["molecular mechanism support"],
                  "status": "UNREVIEWED", "runtimeEligible": False, "reasonCodes": reasons}
        records.append(record)
        counts[mechanism] += 1
        # Do not join unrelated records merely because both have missing disease IDs.
        if hgnc is not None and mondo is not None:
            groups[(hgnc, mondo, allelic)].append(record)
    if not records:
        raise ValueError("G2P_SOURCE_EMPTY")
    backgrounds = []
    for (hgnc, mondo, allelic), rows in sorted(groups.items()):
        mechanisms = sorted({r["mechanismCandidate"] for r in rows})
        backgrounds.append({"hgncId": hgnc, "mondoId": mondo, "allelicRequirement": allelic,
                            "recordIds": [r["recordId"] for r in rows], "sourceMechanisms": mechanisms,
                            "status": "MULTIPLE_SOURCE_MECHANISMS_REQUIRE_REVIEW" if len(mechanisms) > 1 else "UNREVIEWED",
                            "runtimeEligible": False})
    return {"schemaVersion": "pvs1-mechanism-source-candidates-v1", "methodVersion": VERSION,
            "status": "UNREVIEWED", "runtimeEligible": False, "clinicalAcceptance": False,
            "source": {"url": SOURCE_URL, "version": source_version, "sha256": digest,
                       "bytes": len(raw), "columns": fields},
            "counts": {"sourceRecords": len(records), "exactBackgroundGroups": len(backgrounds),
                       "mechanismLabels": dict(sorted(counts.items())), "adoptedPvs1Backgrounds": 0},
            "records": records, "backgrounds": backgrounds}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--source-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    before = args.input.stat()
    if before.st_size > 8_000_000:
        parser.error("G2P_SOURCE_TOO_LARGE")
    with args.input.open("rb") as stream:
        raw = stream.read(8_000_001)
    after = args.input.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        parser.error("G2P_SOURCE_CHANGED_DURING_READ")
    result = prepare(raw, args.expected_sha256, args.source_version)
    # Exclusive creation also rejects input=output; never change the source or an earlier preparation.
    with args.output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write("\n")
    print(json.dumps(result["counts"], ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
