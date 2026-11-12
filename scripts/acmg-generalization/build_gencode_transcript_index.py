"""Index a fixed local GENCODE GTF for exact-version, on-demand coding models.

No downloads, reference reads, disease policies or approval. The source digest is
a measurement of the supplied local snapshot, not an official checksum claim.
"""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

VERSION = "1.0.0"
SCHEMA = "gencode-transcript-index-v1"
TX = re.compile(r"ENST[0-9]+\.[1-9][0-9]*")
ATTR = re.compile(r'([A-Za-z0-9_]+) (?:"([^"\r\n]*)"|([0-9]+));\s*')


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n").encode("ascii")


def attrs(text):
    result, at = {}, 0
    for match in ATTR.finditer(text):
        require(match.start() == at, "GTF_ATTRIBUTES_INVALID")
        key, quoted, number = match.groups()
        require(key not in result or key in {"tag", "ont"}, "GTF_ATTRIBUTE_DUPLICATE")
        result.setdefault(key, []).append(quoted if quoted is not None else number)
        at = match.end()
    require(at == len(text), "GTF_ATTRIBUTES_INVALID")
    return result


def annotation(raw):
    rows = []
    for line in raw.decode("utf-8").splitlines():
        cells = line.split("\t")
        require(len(cells) == 9, "GTF_COLUMNS_INVALID")
        rows.append((cells, attrs(cells[8])))
    first, identity = rows[0]
    one = lambda key: identity.get(key, [""])[0]
    tx, contig = one("transcript_id"), first[0].removeprefix("chr")
    require(first[2] == "transcript" and TX.fullmatch(tx), "GTF_EXACT_TRANSCRIPT_REQUIRED")
    require(one("transcript_type") == "protein_coding", "GTF_NOT_PROTEIN_CODING")
    require(re.fullmatch(r"(?:[1-9]|1[0-9]|2[0-2]|X|Y)", contig), "GTF_NON_PRIMARY_OR_MT")
    require(first[6] in {"+", "-"}, "GTF_STRAND_INVALID")
    require(re.fullmatch(r"HGNC:[0-9]+", one("hgnc_id")) and one("gene_name"), "GTF_GENE_IDENTITY_MISSING")
    strand = 1 if first[6] == "+" else -1
    exons, features, proteins, tags = [], [], set(), set()
    for c, a in rows:
        for key in ("transcript_id", "gene_id", "gene_name", "hgnc_id", "transcript_type"):
            require(a.get(key) == identity.get(key), "GTF_ROW_IDENTITY_CONFLICT")
        require(c[0] == first[0] and c[6] == first[6], "GTF_ROW_COORDINATE_CONFLICT")
        lo, hi = int(c[3]), int(c[4])
        require(1 <= lo <= hi <= 2_147_483_647, "GTF_COORDINATES_INVALID")
        tags.update(a.get("tag", []))
        proteins.update(a.get("protein_id", []))
        if c[2] == "exon":
            exons.append(dict(ordinal=int(a.get("exon_number", ["0"])[0]), genomicStart=lo, genomicEnd=hi))
        elif c[2] in {"CDS", "start_codon", "stop_codon"}:
            features.append((c[2], lo, hi, c[7]))
        elif c[2] == "Selenocysteine":
            raise ValueError("GTF_NONSTANDARD_TRANSLATION")
        else:
            require(c[2] in {"transcript", "UTR"}, "GTF_FEATURE_UNSUPPORTED")
    require(not tags.intersection({"cds_start_NF", "cds_end_NF", "mRNA_start_NF", "mRNA_end_NF", "readthrough_transcript"}), "GTF_INCOMPLETE_OR_READTHROUGH")
    require(len(proteins) == 1 and re.fullmatch(r"ENSP[0-9]+\.[1-9][0-9]*", next(iter(proteins))), "GTF_PROTEIN_VERSION_MISSING")
    exons.sort(key=lambda e: e["ordinal"])
    require(0 < len(exons) <= 1000, "GTF_EXON_LIMIT")
    cursor, previous = 1, None
    for i, e in enumerate(exons, 1):
        require(e["ordinal"] == i and (previous is None or
                (e["genomicStart"] > previous["genomicEnd"] if strand == 1 else e["genomicEnd"] < previous["genomicStart"])), "GTF_EXON_ORDER_INVALID")
        e["transcriptStart"] = cursor
        cursor += e["genomicEnd"] - e["genomicStart"] + 1
        previous = e
    require(cursor - 1 <= 1_000_000, "GTF_TRANSCRIPT_BASE_LIMIT")
    require(min(e["genomicStart"] for e in exons) == int(first[3]) and max(e["genomicEnd"] for e in exons) == int(first[4]), "GTF_TRANSCRIPT_BOUNDS_MISMATCH")
    projected = {"CDS": [], "start_codon": [], "stop_codon": []}
    for kind, lo, hi, phase in features:
        matches = [e for e in exons if e["genomicStart"] <= lo <= hi <= e["genomicEnd"]]
        require(len(matches) == 1, "GTF_CODING_FEATURE_OUTSIDE_EXON")
        e = matches[0]
        a = e["transcriptStart"] + (lo - e["genomicStart"] if strand == 1 else e["genomicEnd"] - hi)
        projected[kind].append((a, a + hi - lo, phase))
    for value in projected.values():
        value.sort()
        require(value and all(value[i][0] == value[i-1][1] + 1 for i in range(1, len(value))), "GTF_CODING_FEATURE_GAP_OR_OVERLAP")
    cds, start, stop = (projected[k] for k in ("CDS", "start_codon", "stop_codon"))
    begin, end = cds[0][0], stop[-1][1]
    require(start[0][0] == begin and start[-1][1] == begin + 2 and stop[0][0] == cds[-1][1] + 1
            and end == stop[0][0] + 2 and (end - begin + 1) % 3 == 0, "GTF_START_STOP_BOUNDARIES_INVALID")
    consumed = 0
    for a, b, phase in cds:
        require(phase == str((-consumed) % 3), "GTF_CDS_PHASE_MISMATCH")
        consumed += b - a + 1
    for e in exons:
        e["cdsStart"] = e.pop("transcriptStart") - begin + 1
        e["cdsEnd"] = e["cdsStart"] + e["genomicEnd"] - e["genomicStart"]
    return dict(transcript=tx, protein=next(iter(proteins)), hgncId=one("hgnc_id"), geneSymbol=one("gene_name"),
                geneId=one("gene_id"), contig=contig, assembly="GRCh38", strand=strand, exons=exons,
                cdsLength=end-begin+1, transcriptLength=cursor-1, rawGtfSha256=digest(raw))


def build(source, output, release):
    source, output = Path(source).resolve(), Path(output)
    before = source.stat()
    output.mkdir(parents=False, exist_ok=False)
    counts, seen, source_hash = Counter(), set(), hashlib.sha256()
    index, inventory_index, block, active, line_no, header = [], [], bytearray(), None, 0, []
    with (output / "annotations.jsonl").open("xb") as data, (output / "excluded.jsonl").open("xb") as rejected, \
            (output / "inventory.jsonl").open("xb") as inventory:
        def flush():
            if not block:
                return
            # Preserve the source universe BEFORE coding-model exclusions. A rejected
            # model is not an absent transcript and cannot silently support a negative.
            cells = block.decode("utf-8").splitlines()[0].split("\t")
            identity = attrs(cells[8])
            item = dict(transcript=active, hgncId=identity.get("hgnc_id", [""])[0],
                        geneId=identity.get("gene_id", [""])[0], geneSymbol=identity.get("gene_name", [""])[0],
                        transcriptType=identity.get("transcript_type", [""])[0], assembly="GRCh38",
                        contig=cells[0].removeprefix("chr"), rawGtfSha256=digest(block))
            try:
                row = annotation(bytes(block))
                raw = encoded(row)
                require(len(raw) <= 1_000_000, "GTF_MODEL_RECORD_LIMIT")
                index.append(f'{row["transcript"]}\t{data.tell()}\t{len(raw)}\t{digest(raw)}\n')
                data.write(raw)
                counts["indexed"] += 1
                item.update(row)
                item["structureStatus"] = "AVAILABLE"
            except ValueError as error:
                counts[str(error)] += 1
                item.update(structureStatus="UNRESOLVED", reason=str(error))
                rejected.write(encoded(item))
            raw_inventory = encoded(item)
            require(len(raw_inventory) <= 1_000_000, "GTF_INVENTORY_RECORD_LIMIT")
            # Missing HGNC identities remain visible by gene ID/symbol in the raw
            # inventory; they must not make a gene-level negative look complete.
            inventory_index.append(f'{active}\t{item["hgncId"]}\t{item["geneSymbol"]}\t{inventory.tell()}\t{len(raw_inventory)}\t{digest(raw_inventory)}\n')
            inventory.write(raw_inventory)
        with source.open("rb") as stream:
            for raw in stream:
                source_hash.update(raw)
                line_no += 1
                require(len(raw) <= 65536 and raw.endswith(b"\n"), "GTF_LINE_INVALID")
                if raw.startswith(b"#"):
                    require(active is None, "GTF_HEADER_AFTER_DATA")
                    header.append(raw.decode("utf-8").rstrip())
                    continue
                cells = raw.rstrip(b"\r\n").split(b"\t")
                require(len(cells) == 9, "GTF_COLUMNS_INVALID")
                if cells[2] == b"gene":
                    flush(); block.clear(); active = None
                    continue
                match = re.search(rb'(?:^|; )transcript_id "([^"]+)";', cells[8])
                require(match is not None, "GTF_ROW_TRANSCRIPT_MISSING")
                tx = match[1].decode("ascii")
                if tx != active:
                    flush(); block.clear()
                    require(tx not in seen and cells[2] == b"transcript", "GTF_NONCONTIGUOUS_OR_DUPLICATE_TRANSCRIPT")
                    seen.add(tx); active = tx
                    require(len(seen) <= 500_000, "GTF_TRANSCRIPT_COUNT_LIMIT")
                block.extend(raw)
                require(len(block) <= 4_000_000, "GTF_TRANSCRIPT_SOURCE_LIMIT")
        flush()
    require(any(f"version {release} (Ensembl " in h and "(GRCh38)" in h for h in header), "GTF_RELEASE_HEADER_MISMATCH")
    require((before.st_size, before.st_mtime_ns) == (source.stat().st_size, source.stat().st_mtime_ns), "GTF_SOURCE_CHANGED")
    (output / "index.tsv").write_bytes("".join(index).encode("ascii"))
    (output / "inventory-index.tsv").write_bytes("".join(inventory_index).encode("utf-8"))
    def file_info(name):
        p = output / name
        with p.open("rb") as stream:
            sha = hashlib.file_digest(stream, "sha256").hexdigest()
        return dict(file=name, bytes=p.stat().st_size, sha256=sha)
    manifest = dict(schemaVersion=SCHEMA, methodVersion=VERSION, release=release, assembly="GRCh38", activation="TECHNICAL_MATERIAL_ONLY",
                    source=dict(file=source.name, bytes=before.st_size, mtimeNs=str(before.st_mtime_ns), sha256=source_hash.hexdigest(),
                    checksumAuthority="MEASURED_LOCAL_SNAPSHOT_NOT_OFFICIAL_CHECKSUM", header=header,
                    url=f"https://ftp.ebi.ac.uk/pub/databases/gencode/Gencode_human/release_{release}/gencode.v{release}.annotation.gtf.gz"),
                    data=file_info("annotations.jsonl"), index=file_info("index.tsv"), excluded=file_info("excluded.jsonl"),
                    inventory=file_info("inventory.jsonl"), inventoryIndex=file_info("inventory-index.tsv"),
                    inventoryCoverage="ALL_SOURCE_TRANSCRIPTS_BEFORE_MODEL_FILTER",
                    sourceLines=line_no, transcripts=len(seen), counts=dict(counts), independentRnaVerification=False)
    (output / "manifest.json").write_bytes(encoded(manifest))
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--release", required=True, type=int)
    args = parser.parse_args()
    print(json.dumps(build(args.source, args.output, args.release), indent=2))
