"""Derive the complete selected-transcript HNF1A subset from an official REVEL CSV.

The source transcript identifier is deliberately retained without an invented
version. Mapping is accepted only after independent coordinate, FASTA and
versioned-CDS amino-acid checks; the source's GRCh38 coordinates remain a
publisher liftOver product. This script does not infer absent scores.
"""

import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path


FIELDS = [
    "chr", "hg19_pos", "grch38_pos", "ref", "alt", "aaref", "aaalt",
    "REVEL", "Ensembl_transcriptid",
]
CODONS = {
    a + b + c: aa
    for (a, b, c), aa in zip(
        ((a, b, c) for a in "TCAG" for b in "TCAG" for c in "TCAG"),
        "FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG",
    )
}


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


class IndexedFasta:
    """Read tiny intervals, never scan or copy a genome FASTA."""

    def __init__(self, path, contig):
        self.path = Path(path)
        entries = [line.split("\t") for line in Path(str(path) + ".fai").read_text().splitlines()]
        row = next(row for row in entries if row[0] == contig)
        self.length, self.offset, self.line_bases, self.line_bytes = map(int, row[1:5])
        self.handle = self.path.open("rb")
        self.read_bases = 0

    def base(self, position):
        if not 1 <= position <= self.length:
            raise ValueError("FASTA coordinate outside contig")
        index = position - 1
        offset = self.offset + index // self.line_bases * self.line_bytes + index % self.line_bases
        self.handle.seek(offset)
        result = self.handle.read(1).decode("ascii").upper()
        if result not in "ACGT":
            raise ValueError("FASTA reference is not an unambiguous base")
        self.read_bases += 1
        return result

    def close(self):
        self.handle.close()


def cds_position(model, position):
    positions = [
        exon["cdsStart"] + position - exon["genomicStart"]
        for exon in model["exons"]
        if exon["genomicStart"] <= position <= exon["genomicEnd"]
    ]
    if len(positions) != 1 or not 1 <= positions[0] <= model["cdsLength"]:
        return None
    return positions[0]


def enumerate_missense(model):
    result = set()
    cds = model["cdsSequence"]
    for exon in model["exons"]:
        for position in range(exon["genomicStart"], exon["genomicEnd"] + 1):
            cdsp = cds_position(model, position)
            if cdsp is None:
                continue
            start = (cdsp - 1) // 3 * 3
            codon = cds[start:start + 3]
            offset = (cdsp - 1) % 3
            for alternate in "ACGT":
                altered = codon[:offset] + alternate + codon[offset + 1:]
                if CODONS[codon] != "*" and CODONS[altered] != "*" and CODONS[codon] != CODONS[altered]:
                    result.add((position, cds[cdsp - 1], alternate))
    return result


def verify_record(row, model, fasta38, fasta37):
    if row["grch38_pos"] == ".":
        return {"status": "MAPPING_MISSING"}
    position = int(row["grch38_pos"])
    cdsp = cds_position(model, position)
    if cdsp is None:
        return {"status": "OUTSIDE_SELECTED_CDS"}
    reference, alternate = row["ref"], row["alt"]
    if len(reference) != 1 or len(alternate) != 1 or reference == alternate or any(x not in "ACGT" for x in reference + alternate):
        return {"status": "INVALID_ALLELE"}
    if fasta38.base(position) != reference or fasta37.base(int(row["hg19_pos"])) != reference:
        return {"status": "FASTA_REF_MISMATCH"}
    cds = model["cdsSequence"]
    if cds[cdsp - 1] != reference:
        return {"status": "CDS_REF_MISMATCH"}
    codon_start = (cdsp - 1) // 3 * 3
    ref_codon = cds[codon_start:codon_start + 3]
    offset = (cdsp - 1) % 3
    alt_codon = ref_codon[:offset] + alternate + ref_codon[offset + 1:]
    aa_ref, aa_alt = CODONS[ref_codon], CODONS[alt_codon]
    if (aa_ref, aa_alt) != (row["aaref"], row["aaalt"]):
        return {"status": "AMINO_ACID_MISMATCH", "computed": aa_ref + "/" + aa_alt}
    score = float(row["REVEL"])
    if not 0 <= score <= 1:
        return {"status": "INVALID_SCORE"}
    return {
        "status": "VERIFIED", "cdsPosition": cdsp,
        "proteinPosition": codon_start // 3 + 1,
        "codon": ref_codon + "/" + alt_codon,
        "scoreBin": "LOW" if score <= 0.15 else "HIGH" if score >= 0.70 else "MIDDLE",
    }


def prepare(source_csv, model_path, fasta38_path, fasta37_path, receipt_path, destination):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    model = json.loads(Path(model_path).read_text(encoding="utf-8"))
    if model["assembly"] != "GRCh38" or model["strand"] != 1 or model["transcript"] != "ENST00000257555.11":
        raise ValueError("Unsupported transcript model")
    receipt = json.loads(Path(receipt_path).read_text())
    if sha(source_csv) != receipt["csvSha256"]:
        raise ValueError("Source CSV hash differs from acquisition receipt")
    tx = model["transcript"].split(".")[0]
    selected, checks, total = [], [], 0
    fasta38 = IndexedFasta(fasta38_path, "chr12")
    fasta37 = IndexedFasta(fasta37_path, "12")
    try:
        with Path(source_csv).open(newline="", encoding="utf-8") as source:
            reader = csv.DictReader(source)
            if reader.fieldnames != FIELDS:
                raise ValueError("Unexpected official CSV schema")
            for source_line, row in enumerate(reader, 2):
                total += 1
                if tx not in row["Ensembl_transcriptid"].split(";"):
                    continue
                if row["chr"] != "12":
                    raise ValueError("Unexpected selected-transcript chromosome")
                row["sourceLine"] = str(source_line)
                selected.append(row)
                checks.append({"sourceLine": source_line, **verify_record(row, model, fasta38, fasta37)})
    finally:
        fasta38.close()
        fasta37.close()
    counts = Counter(check["status"] for check in checks)
    # Refuse to silently discard incompatible rows or select a favorable score.
    if any(key not in {"VERIFIED", "MAPPING_MISSING"} for key in counts):
        write_json(destination / "failed-row-checks.json", checks)
        raise ValueError("Selected transcript contains incompatible rows: " + repr(counts))
    if not selected:
        raise ValueError("No selected-transcript source records")
    alleles = [(int(row["grch38_pos"]), row["ref"], row["alt"]) for row in selected if row["grch38_pos"] != "."]
    expected = enumerate_missense(model)
    coverage = {
        "expectedMissenseAllelesByPinnedCds": len(expected), "sourceUniqueAlleles": len(set(alleles)),
        "duplicateSourceAlleles": len(alleles) - len(set(alleles)),
        "missingExpectedAlleles": [list(allele) for allele in sorted(expected - set(alleles))],
        "unexpectedAlleles": [list(allele) for allele in sorted(set(alleles) - expected)],
    }
    write_json(destination / "coding-coverage-check.json", coverage)
    with (destination / "hnf1a-revel.tsv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=FIELDS + ["sourceLine"], delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(selected)
    # This is a local derived resource, not a source score field.
    write_json(destination / "selected-transcript-validation.json", {
        "transcript": model["transcript"], "refseqTranscript": model["refseqTranscript"],
        "sourceTranscriptVersion": None, "sourceProteinPosition": None,
        "method": "publisher_GRCh38_coordinate_and_both_FASTA_REF_then_pinned_CDS_codon_translation",
        "sourceRowsScanned": total, "selectedRows": len(selected), "statusCounts": dict(counts),
        "fasta38BasesRead": fasta38.read_bases, "fasta37BasesRead": fasta37.read_bases,
        "scoreBins": dict(Counter(check.get("scoreBin") for check in checks if check["status"] == "VERIFIED")),
        "records": checks,
    })
    source_copy = destination / "segment.csv"
    with Path(source_csv).open("rb") as source, source_copy.open("xb") as output:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            output.write(block)
    write_json(destination / "segment-download-receipt.json", receipt)
    mapped = [int(row["grch38_pos"]) for row in selected if row["grch38_pos"] != "."]
    original = [int(row["hg19_pos"]) for row in selected]
    manifest = {
        "schemaVersion": "revel-hnf1a-resource-v1", "resourceVersion": "1.3",
        "derivationVersion": "hnf1a-revel-cds-map-v1",
        "dataFile": "hnf1a-revel.tsv", "dataSha256": sha(destination / "hnf1a-revel.tsv"),
        "transcriptModelSha256": hashlib.sha256(json.dumps(model, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest(),
        "transcriptModelByteSha256": sha(model_path),
        "transcriptModelCanonicalization": "varvis-json-c14n-v1",
        "scope": {
            "hgncId": "HGNC:11621", "transcript": model["transcript"],
            "refseqTranscript": model["refseqTranscript"], "assembly": "GRCh38", "contig": "12",
            "minPosition": min(mapped), "maxPosition": max(mapped),
            "originalMinPosition": min(original), "originalMaxPosition": max(original),
        },
        "source": {
            "url": receipt["url"], "file": "segment.csv", "sha256": sha(source_copy),
            "version": "1.3", "publicationDate": "2021-05-03", "retrievedAt": receipt["retrievedAt"],
            "originalAssembly": "GRCh37", "publishedAssembly": "GRCh38",
            "mappingMethod": "REVEL_publisher_liftOver", "transcriptVersionProvided": False,
            "proteinPositionProvided": False, "memberName": receipt["memberName"],
            "memberCrc32": receipt["memberCrc32"], "zipCrcVerified": True,
            "acquisitionReceiptFile": "segment-download-receipt.json",
            "acquisitionReceiptSha256": sha(destination / "segment-download-receipt.json"),
            "archiveMd5RecordedNotVerified": receipt["archiveMd5RecordedNotVerified"],
            "sourceSegmentHg19Min": 112191609, "sourceSegmentHg19Max": 123463733,
        },
        "selectedTranscriptValidation": {
            "file": "selected-transcript-validation.json",
            "sha256": sha(destination / "selected-transcript-validation.json"),
            "selectedRows": len(selected), "statusCounts": dict(counts),
            "sourceTranscriptVersion": None, "sourceProteinPosition": None,
            "method": "publisher_GRCh38_coordinate_and_both_FASTA_REF_then_pinned_CDS_codon_translation",
            "codingCoverageFile": "coding-coverage-check.json",
            "codingCoverageSha256": sha(destination / "coding-coverage-check.json"),
            "codingCoverage": coverage,
        },
        "missingRecordMeaning": "NO_RECORD_is_not_a_zero_score",
        "useConditions": {
            "officialSite": "Scores are freely available for non-commercial use; contact the authors for other uses.",
            "zenodoDeclaredLicense": "odc-odbl", "notice": "Both source declarations are retained; no expanded license is inferred.",
        },
    }
    write_json(destination / "revel-hnf1a.manifest.json", manifest)
    return {"rows": len(selected), "statusCounts": dict(counts), "dataSha256": manifest["dataSha256"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source-csv", "model", "fasta38", "fasta37", "receipt", "destination"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.source_csv, args.model, args.fasta38, args.fasta37, args.receipt, args.destination), indent=2))


if __name__ == "__main__":
    main()
