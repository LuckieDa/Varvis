"""Verify a pinned diagnostic export and create disabled lookup-shaped candidates.

No VEP, network, sequence retrieval, source-ledger update or policy activation.
The output is VEP cache provenance, never an Ensembl REST or independent RNA claim.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

API_MODULES = {"Bio/EnsEMBL/" + name + ".pm" for name in (
    "ApiVersion", "Transcript", "Biotype", "Translation", "Exon", "Feature", "Slice", "CoordSystem",
    "Variation/Utils/BaseVepPlugin")}
SOURCE_KIND = "VEP116_OFFLINE_CACHE_TRANSCRIPT_OBJECT_NOT_REST"
PENDING = "EXTRACTED_PENDING_INDEPENDENT_REFSEQ_AND_NATIVE_FASTA_CHECK"


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(content):
    return hashlib.sha256(content).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def keys(value, expected, reason="UNSUPPORTED_OR_MISSING_FIELD"):
    require(type(value) is dict and set(value) == set(expected.split()), reason)


def integer(value, minimum=1, maximum=2147483647):
    return type(value) is int and minimum <= value <= maximum


def match(pattern, value):
    return type(value) is str and re.fullmatch(pattern, value) is not None


def digest(value):
    return match(r"[0-9a-f]{64}", value)


def strict_json(content):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    def invalid_number(_):
        raise ValueError("NON_INTEGER_JSON_NUMBER")
    return json.loads(content, object_pairs_hook=pairs, parse_float=invalid_number, parse_constant=invalid_number)


def pinned(path, expected_sha, maximum):
    require(digest(expected_sha), "EXPECTED_SHA_REQUIRED")
    with Path(path).open("rb") as stream:
        content = stream.read(maximum + 1)
    require(len(content) <= maximum, "SOURCE_SIZE_LIMIT")
    require(sha(content) == expected_sha, "SOURCE_SHA_MISMATCH")
    return content


def validate_request(request):
    keys(request, "schemaVersion requestId assembly species cacheVersion requestedTranscripts cacheInfo apiSourceSha256")
    require(request["schemaVersion"] == "vep116-transcript-export-request-v1"
            and match(r"[A-Za-z0-9_.-]+", request["requestId"]) and request["assembly"] == "GRCh38"
            and request["species"] == "homo_sapiens" and request["cacheVersion"] == "116_GRCh38", "REQUEST_SCOPE_INVALID")
    targets = request["requestedTranscripts"]
    require(type(targets) is list and 1 <= len(targets) <= 32
            and all(match(r"ENST[0-9]+\.[1-9][0-9]*", target) for target in targets)
            and len(set(targets)) == len(targets), "REQUEST_EXACT_TRANSCRIPTS_INVALID")
    keys(request["cacheInfo"], "path sha256")
    require(type(request["cacheInfo"]["path"]) is str and request["cacheInfo"]["path"]
            and digest(request["cacheInfo"]["sha256"]), "CACHE_INFO_BINDING_INVALID")
    api = request["apiSourceSha256"]
    require(type(api) is dict and set(api) == API_MODULES and all(digest(value) for value in api.values()), "API_BINDING_INVALID")


def validate_body(body, target):
    keys(body, "status lookup codingBoundaries objectSequenceChecks reviewStatus")
    require(body["status"] == "EXPORTED_UNIQUE" and body["reviewStatus"] == PENDING, "TRANSCRIPT_NOT_UNIQUE_PENDING")
    lookup, coding, checks = (body[key] for key in ("lookup", "codingBoundaries", "objectSequenceChecks"))
    keys(checks, "cdsSha256 peptideSha256 source independence")
    require(digest(checks["cdsSha256"]) and digest(checks["peptideSha256"])
            and checks["source"] == "ALREADY_PRESENT_VEP_OBJECT_CACHE_NO_SEQUENCE_GETTERS_CALLED"
            and checks["independence"] == "NOT_INDEPENDENT_RNA", "SEQUENCE_PROVENANCE_INVALID")
    keys(lookup, "id version object_type biotype assembly_name species seq_region_name strand start end length Exon Translation")
    require(match(r"ENST[0-9]+", lookup["id"]) and integer(lookup["version"])
            and lookup["id"] + "." + str(lookup["version"]) == target
            and lookup["assembly_name"] == "GRCh38" and lookup["species"] == "homo_sapiens"
            and lookup["object_type"] == "Transcript" and lookup["biotype"] == "protein_coding",
            "TRANSCRIPT_IDENTITY_INVALID")
    strand, contig = lookup["strand"], lookup["seq_region_name"]
    require(type(strand) is int and strand in (-1, 1) and match(r"(?:[1-9]|1[0-9]|2[0-2]|X|Y)", contig), "NATIVE_IDENTITY_INVALID")
    exons = lookup["Exon"]
    require(type(exons) is list and 1 <= len(exons) <= 1000, "EXON_COUNT_INVALID")
    previous, cursor, ids = None, 1, {}
    for ordinal, exon in enumerate(exons, 1):
        keys(exon, "id ordinal start end strand assembly_name seq_region_name cdnaStart cdnaEnd exonVersionStatus")
        lo, hi = exon["start"], exon["end"]
        require(integer(lo) and integer(hi) and lo <= hi and match(r"ENSE[0-9]+", exon["id"])
                and exon["id"] not in ids and integer(exon["ordinal"]) and exon["ordinal"] == ordinal
                and exon["assembly_name"] == "GRCh38" and exon["seq_region_name"] == contig
                and type(exon["strand"]) is int and exon["strand"] == strand, "EXON_IDENTITY_INVALID")
        require(previous is None or (lo > previous[1] if strand == 1 else hi < previous[0]), "EXON_ORDER_OR_OVERLAP")
        require(integer(exon["cdnaStart"]) and integer(exon["cdnaEnd"])
                and exon["cdnaStart"] == cursor and exon["cdnaEnd"] == cursor + hi - lo
                and exon["exonVersionStatus"] == "CACHE_DOES_NOT_PRESERVE_EXON_VERSION", "EXON_CDNA_OR_VERSION_INVALID")
        cursor += hi - lo + 1
        ids[exon["id"]], previous = exon, (lo, hi)
    require(integer(lookup["length"], maximum=1_000_000) and lookup["length"] == cursor - 1
            and integer(lookup["start"]) and integer(lookup["end"])
            and lookup["start"] == min(exon["start"] for exon in exons)
            and lookup["end"] == max(exon["end"] for exon in exons), "TRANSCRIPT_SPAN_INVALID")
    translation = lookup["Translation"]
    keys(translation, "id version Parent start end length")
    require(match(r"ENSP[0-9]+", translation["id"]) and integer(translation["version"])
            and translation["Parent"] == lookup["id"] and integer(translation["length"]), "PROTEIN_IDENTITY_INVALID")
    keys(coding, "cdnaStart cdnaEnd genomicBiologicalStart genomicBiologicalEnd cdsLengthIncludingStop proteinLength "
                 "translationStartExon translationEndExon translationStartExonOffset translationEndExonOffset")
    for key, value in coding.items():
        if key in ("translationStartExon", "translationEndExon"):
            require(type(value) is str and value in ids, "CDS_BOUNDARY_EXON_MISSING")
        else:
            require(integer(value), "CDS_BOUNDARY_INTEGER_REQUIRED")
    for edge in ("Start", "End"):
        exon = ids[coding["translation" + edge + "Exon"]]
        offset = coding["translation" + edge + "ExonOffset"]
        require(offset <= exon["end"] - exon["start"] + 1, "CDS_EXON_OFFSET_INVALID")
        genomic = exon["start"] + offset - 1 if strand == 1 else exon["end"] - offset + 1
        require(coding["genomicBiological" + edge] == genomic and coding["cdna" + edge] == exon["cdnaStart"] + offset - 1,
                "CDS_BOUNDARY_MAPPING_MISMATCH")
    require(coding["cdnaStart"] <= coding["cdnaEnd"] <= lookup["length"]
            and coding["cdsLengthIncludingStop"] == coding["cdnaEnd"] - coding["cdnaStart"] + 1
            and coding["cdsLengthIncludingStop"] == 3 * (coding["proteinLength"] + 1)
            and translation["length"] == coding["proteinLength"]
            and integer(translation["start"]) and integer(translation["end"])
            and [translation["start"], translation["end"]] == sorted([coding["genomicBiologicalStart"], coding["genomicBiologicalEnd"]]),
            "CDS_TRANSLATION_BOUNDARY_MISMATCH")


def validate_export(request, request_sha, records, exporter_sha):
    validate_request(request)
    require(digest(request_sha) and digest(exporter_sha), "SOURCE_IDENTITY_REQUIRED")
    require(type(records) is list and 3 <= len(records) <= 66, "EXPORT_RECORD_COUNT_INVALID")
    header, footer = records[0], records[-1]
    keys(header, "recordType schemaVersion requestId requestSha256 requestedTranscripts sourceKind cacheVersion "
                 "cacheInfoSha256 apiSourceSha256 exporterVersion exporterSha256 sequenceIndependence cacheDataIntegrity defaultEnabled clinicalAcceptance")
    require(header["recordType"] == "HEADER" and header["schemaVersion"] == "vep116-transcript-source-export-v1"
            and header["requestId"] == request["requestId"] and header["requestSha256"] == request_sha
            and header["requestedTranscripts"] == request["requestedTranscripts"]
            and header["cacheInfoSha256"] == request["cacheInfo"]["sha256"]
            and header["apiSourceSha256"] == request["apiSourceSha256"] and header["cacheVersion"] == "116_GRCh38"
            and header["exporterSha256"] == exporter_sha and header["exporterVersion"] == "1.0.1"
            and header["sourceKind"] == SOURCE_KIND and header["sequenceIndependence"] == "NO_INDEPENDENT_RNA_SEQUENCE_CLAIM"
            and header["cacheDataIntegrity"] == "INFO_METADATA_ONLY_NOT_ALL_CACHE_SHARDS"
            and header["defaultEnabled"] is False and header["clinicalAcceptance"] is False, "EXPORT_HEADER_IDENTITY_MISMATCH")
    keys(footer, "recordType status transcripts")
    require(footer["recordType"] == "FOOTER" and footer["status"] == "EXPORT_COMPLETED", "EXPORT_NORMAL_FINISH_REQUIRED")
    bodies, hashes = {}, {}
    for record in records[1:-1]:
        require(type(record) is dict and record.get("recordType") == "TRANSCRIPT", "EXPORT_CONFLICT_OR_UNEXPECTED_RECORD")
        keys(record, "recordType requestedTranscript objectSha256 body")
        target = record["requestedTranscript"]
        require(type(target) is str and target in request["requestedTranscripts"] and target not in bodies, "EXPORT_TARGET_DUPLICATE_OR_UNREQUESTED")
        require(record["objectSha256"] == sha(canonical(record["body"])), "EXPORT_OBJECT_SHA_MISMATCH")
        validate_body(record["body"], target)
        bodies[target], hashes[target] = record["body"], record["objectSha256"]
    summaries = footer["transcripts"]
    require(type(summaries) is list and len(summaries) == len(request["requestedTranscripts"]), "EXPORT_FOOTER_SCOPE_MISMATCH")
    for target, summary in zip(request["requestedTranscripts"], summaries):
        keys(summary, "requestedTranscript observations status sha256", "EXPORT_REQUEST_NOT_UNIQUELY_OBSERVED")
        require(summary["requestedTranscript"] == target and summary["status"] == "EXPORTED_UNIQUE"
                and integer(summary["observations"]) and target in bodies and summary["sha256"] == hashes[target],
                "EXPORT_REQUEST_NOT_UNIQUELY_OBSERVED")
    require(set(bodies) == set(request["requestedTranscripts"]), "EXPORT_REQUEST_NOT_UNIQUELY_OBSERVED")
    return bodies


def convert(request_path, request_sha, export_path, export_sha, exporter_sha, output_dir):
    request_bytes = pinned(request_path, request_sha, 100_000)
    request = strict_json(request_bytes)
    require(request_bytes in (canonical(request), canonical(request) + b"\n"), "CANONICAL_REQUEST_REQUIRED")
    export_bytes = pinned(export_path, export_sha, 16_000_000)
    require(export_bytes.endswith(b"\n"), "EXPORT_TERMINAL_NEWLINE_REQUIRED")
    records = [strict_json(line) for line in export_bytes.splitlines()]
    bodies = validate_export(request, request_sha, records, exporter_sha)
    destination = Path(output_dir)
    # Validate the whole batch before writing; never salvage a partially failed run.
    destination.mkdir(parents=False, exist_ok=False)
    manifest = dict(schemaVersion="vep116-transcript-lookup-candidates-v1", sourceKind=SOURCE_KIND,
                    requestSha256=request_sha, exportSha256=export_sha, exporterSha256=exporter_sha,
                    cacheInfoSha256=request["cacheInfo"]["sha256"], apiSourceSha256=request["apiSourceSha256"],
                    defaultEnabled=False, clinicalAcceptance=False, reviewStatus=PENDING,
                    sequenceIndependence="NO_INDEPENDENT_RNA_SEQUENCE_CLAIM", entries=[])
    for target, body in bodies.items():
        filename = target + ".lookup-candidate.json"
        content = canonical(body["lookup"]) + b"\n"
        with (destination / filename).open("xb") as stream:
            stream.write(content)
        manifest["entries"].append(dict(transcript=target, file=filename, sha256=sha(content),
                                        objectSha256=sha(canonical(body)), codingBoundaries=body["codingBoundaries"],
                                        objectSequenceChecks=body["objectSequenceChecks"]))
    with (destination / "manifest.json").open("xb") as stream:
        stream.write(canonical(manifest) + b"\n")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("request", "request-sha256", "export", "export-sha256", "exporter-sha256", "output-dir"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    result = convert(args.request, args.request_sha256, args.export, args.export_sha256, args.exporter_sha256, args.output_dir)
    print(json.dumps(dict(status="LOOKUP_CANDIDATES_PENDING_INDEPENDENT_REVIEW", count=len(result["entries"]),
                         manifestCanonicalSha256=sha(canonical(result)))))


if __name__ == "__main__":
    main()
