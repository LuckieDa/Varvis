"""Build a reviewable native transcript model from two pinned sources and tiny FASTA reads.

No network, annotation inference, clinical relevance inference or policy activation.
RefSeq and expanded Ensembl exon structure, full transcript and CDS must agree.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

MAX_TRANSCRIPT_BASES = 1_000_000
BASES = "TCAG"
AMINO = "FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG"
CODONS = dict(zip((a + b + c for a in BASES for b in BASES for c in BASES), AMINO))


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(value):
    return hashlib.sha256(value).hexdigest()


def reverse_complement(sequence):
    require(bool(re.fullmatch("[ACGTN]+", sequence)), "AMBIGUOUS_OR_INVALID_SEQUENCE")
    return sequence.translate(str.maketrans("ACGTN", "TGCAN"))[::-1]


def bounded_bytes(path, maximum=2_000_000):
    with Path(path).open("rb") as stream:
        value = stream.read(maximum + 1)
    require(len(value) <= maximum, "SOURCE_EXCEEDS_LOCAL_READ_BOUND")
    return value


def parse_refseq(content):
    text = content.decode("utf-8")
    versions = re.findall(r"^VERSION\s+(NM_\d+\.\d+)\s*$", text, re.M)
    cds = re.findall(r"^     CDS\s+(\d+)\.\.(\d+)\s*$", text, re.M)
    proteins = re.findall(r'/protein_id="(NP_\d+\.\d+)"', text)
    translations = re.findall(r'/translation="([A-Z\s]+)"', text)
    genes = set(re.findall(r'/gene="([^"]+)"', text))
    hgnc = set(re.findall(r'/db_xref="HGNC:(?:HGNC:)?(\d+)"', text))
    mane = re.findall(r"MANE Ensembl match\s*::\s*(ENST\d+\.\d+)/\s*(ENSP\d+\.\d+)", text)
    require(len(versions) == len(cds) == len(proteins) == len(translations) == len(genes) == len(hgnc) == 1,
            "REFSEQ_IDENTITY_OR_CDS_AMBIGUOUS")
    origins = re.findall(r"^ORIGIN[^\n]*\n([\s\S]*?)^//", text, re.M)
    require(len(origins) == 1, "REFSEQ_SEQUENCE_MISSING")
    sequence = re.sub(r"[\s\d]", "", origins[0]).upper()
    require(bool(re.fullmatch("[ACGTN]+", sequence)) and len(sequence) <= MAX_TRANSCRIPT_BASES, "REFSEQ_SEQUENCE_INVALID")
    exons = [(int(lo), int(hi)) for lo, hi in re.findall(r"^     exon\s+(\d+)\.\.(\d+)\s*$", text, re.M)]
    cursor = 1
    for lo, hi in exons:
        require(lo == cursor and hi >= lo, "REFSEQ_EXONS_NOT_CONTIGUOUS")
        cursor = hi + 1
    require(exons and cursor == len(sequence) + 1, "REFSEQ_EXONS_INCOMPLETE")
    start, end = map(int, cds[0])
    coding = sequence[start - 1:end]
    protein = re.sub(r"\s", "", translations[0])
    require(1 <= start <= end <= len(sequence) and len(coding) % 3 == 0
            and coding.startswith("ATG") and bool(re.fullmatch("[ACGT]+", coding)), "REFSEQ_CDS_INVALID")
    translated = "".join(CODONS[coding[i:i + 3]] for i in range(0, len(coding), 3))
    require(translated == protein + "*", "REFSEQ_TRANSLATION_MISMATCH")
    return dict(transcript=versions[0], protein=proteins[0], proteinSequence=protein,
                symbol=genes.pop(), hgncId="HGNC:" + hgnc.pop(), sequence=sequence,
                cdsSequence=coding, cdsStart=start, cdsEnd=end, exons=exons, maneMatches=mane)


class IndexedFasta:
    def __init__(self, path):
        self.path = Path(path)
        self.index_bytes = bounded_bytes(str(path) + ".fai", 1_000_000)
        self.index = {}
        self.bases_read = 0
        for line in self.index_bytes.decode("ascii").splitlines():
            fields = line.split("\t")
            require(len(fields) >= 5 and fields[0] not in self.index, "FASTA_INDEX_INVALID")
            self.index[fields[0]] = tuple(map(int, fields[1:5]))

    def region(self, contig, start, end):
        require(contig in self.index, "FASTA_CONTIG_MISSING")
        length, offset, line_bases, line_bytes = self.index[contig]
        require(1 <= start <= end <= length and line_bases > 0 and line_bases <= line_bytes <= line_bases + 2,
                "FASTA_REGION_OR_INDEX_INVALID")
        require(self.bases_read + end - start + 1 <= MAX_TRANSCRIPT_BASES, "FASTA_QUERY_BUDGET_EXCEEDED")
        pieces = []
        position = start - 1
        with self.path.open("rb") as stream:
            while position < end:
                count = min(line_bases - position % line_bases, end - position)
                stream.seek(offset + position // line_bases * line_bytes + position % line_bases)
                piece = stream.read(count)
                require(len(piece) == count, "FASTA_TRUNCATED")
                pieces.append(piece)
                position += count
        self.bases_read += end - start + 1
        sequence = b"".join(pieces).decode("ascii").upper()
        require(bool(re.fullmatch("[ACGTN]+", sequence)), "FASTA_INDEX_SEQUENCE_INVALID")
        return sequence


def build_model(refseq, lookup, fasta, source_records, transcript_kind, model_version):
    require(lookup.get("assembly_name") == "GRCh38" and lookup.get("object_type") == "Transcript"
            and lookup.get("biotype") == "protein_coding" and lookup.get("species") == "homo_sapiens",
            "ENSEMBL_NATIVE_TRANSCRIPT_REQUIRED")
    strand = lookup.get("strand")
    require(type(strand) is int and strand in (-1, 1), "ENSEMBL_STRAND_INVALID")
    require(re.fullmatch(r"ENST\d+", lookup.get("id", "")) and type(lookup.get("version")) is int
            and lookup["version"] > 0, "ENSEMBL_TRANSCRIPT_VERSION_MISSING")
    translation = lookup.get("Translation", {})
    require(re.fullmatch(r"ENSP\d+", translation.get("id", "")) and type(translation.get("version")) is int
            and translation["version"] > 0 and translation.get("Parent") == lookup["id"], "ENSEMBL_PROTEIN_IDENTITY_INVALID")
    exons = lookup.get("Exon", [])
    require(len(exons) == len(refseq["exons"]) and 1 <= len(exons) <= 1000, "TRANSCRIPT_EXON_COUNT_MISMATCH")
    require(lookup.get("length") == len(refseq["sequence"]), "TRANSCRIPT_LENGTH_MISMATCH")
    contig = str(lookup["seq_region_name"])
    require(bool(re.fullmatch(r"(?:[1-9]|1[0-9]|2[0-2]|X|Y)", contig)), "NON_PRIMARY_CONTIG_UNSUPPORTED")
    sequence_parts = []
    model_exons = []
    previous = None
    for ordinal, (exon, bounds) in enumerate(zip(exons, refseq["exons"]), 1):
        lo, hi = exon.get("start"), exon.get("end")
        require(type(lo) is int and type(hi) is int and 1 <= lo <= hi, "GENOMIC_EXON_COORDINATE_INVALID")
        require(exon.get("assembly_name") == "GRCh38" and exon.get("seq_region_name") == contig
                and exon.get("strand") == strand, "EXON_NATIVE_IDENTITY_MISMATCH")
        require(previous is None or (lo > previous[1] if strand == 1 else hi < previous[0]), "EXON_ORDER_OR_OVERLAP_INVALID")
        require(hi - lo == bounds[1] - bounds[0], "EXON_LENGTH_MISMATCH")
        previous = lo, hi
        sequence = fasta.region(contig, lo, hi)
        sequence_parts.append(sequence if strand == 1 else reverse_complement(sequence))
        model_exons.append(dict(ordinal=ordinal, genomicStart=lo, genomicEnd=hi,
                                cdsStart=bounds[0] - refseq["cdsStart"] + 1,
                                cdsEnd=bounds[1] - refseq["cdsStart"] + 1))
    spliced = "".join(sequence_parts)
    require(spliced == refseq["sequence"], "GENOMIC_REFSEQ_FULL_TRANSCRIPT_MISMATCH")
    require(translation.get("length") == len(refseq["proteinSequence"]), "ENSEMBL_REFSEQ_PROTEIN_LENGTH_MISMATCH")
    ensembl_transcript = lookup["id"] + "." + str(lookup["version"])
    ensembl_protein = translation["id"] + "." + str(translation["version"])
    require(refseq["maneMatches"] == [(ensembl_transcript, ensembl_protein)], "REFSEQ_MANE_VERSIONED_BRIDGE_MISMATCH")
    coding_boundaries = []
    for position in (1, len(refseq["cdsSequence"])):
        matches = [exon for exon in model_exons if exon["cdsStart"] <= position <= exon["cdsEnd"]]
        require(len(matches) == 1, "CDS_BOUNDARY_MAPPING_AMBIGUOUS")
        exon = matches[0]
        coding_boundaries.append(exon["genomicStart"] + position - exon["cdsStart"] if strand == 1
                                 else exon["genomicEnd"] - position + exon["cdsStart"])
    require([translation.get("start"), translation.get("end")] == sorted(coding_boundaries),
            "ENSEMBL_REFSEQ_CDS_BOUNDARIES_MISMATCH")
    transcript = ensembl_transcript if transcript_kind == "ensembl" else refseq["transcript"]
    protein = ensembl_protein if transcript_kind == "ensembl" else refseq["protein"]
    model = dict(schemaVersion="pvs1-transcript-model-v1", modelId="NATIVE_CDS_" + transcript,
                 modelVersion=model_version, hgncId=refseq["hgncId"], geneSymbol=refseq["symbol"],
                 assembly="GRCh38", contig=contig, strand=strand, transcript=transcript, protein=protein,
                 cdsSequence=refseq["cdsSequence"], cdsLength=len(refseq["cdsSequence"]),
                 proteinLength=len(refseq["proteinSequence"]), exons=model_exons, sourceRecords=source_records,
                 reviewStatus="MECHANICALLY_VERIFIED_PENDING_POLICY_REVIEW",
                 transcriptBridge=dict(refseqTranscript=refseq["transcript"], refseqProtein=refseq["protein"],
                                       ensemblTranscript=ensembl_transcript, ensemblProtein=ensembl_protein,
                                       status="FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE"),
                 sequenceVerification=dict(fastaIndexSha256=sha(fasta.index_bytes),
                                           fullSplicedTranscriptSha256=sha(spliced.encode("ascii")),
                                           cdsSha256=sha(refseq["cdsSequence"].encode("ascii")),
                                           queriedBases=fasta.bases_read,
                                           sourceScope="Exact transcript exons only; no whole-genome checksum claim"))
    if len(model_exons) > 1:
        model["lastJunctionCdsPosition"] = model_exons[-2]["cdsEnd"]
    return model


def source_records(refseq, lookup, refseq_path, lookup_path, ledger):
    records = []
    for label, path, identity, kind in (
            ('RefSeq', refseq_path, refseq['transcript'], 'NCBI_VERSIONED_REFSEQ_GENBANK'),
            ('Ensembl', lookup_path, lookup['id'] + '.' + str(lookup['version']), 'ENSEMBL_VERSIONED_TRANSCRIPT_LOOKUP')):
        content = bounded_bytes(path)
        matches = [entry for entry in ledger['entries'] if Path(entry['destination']).resolve() == Path(path).resolve()
                   and entry.get('sha256') == sha(content) and entry.get('status') == 'CAPTURED_PENDING_SOURCE_REVIEW']
        require(len(matches) == 1, 'PINNED_SOURCE_LEDGER_MISMATCH')
        entry = matches[0]
        require(entry.get('version') == identity, 'SOURCE_LEDGER_VERSION_MISMATCH')
        records.append(dict(sourceId=label + ':' + identity, sourceKind=kind, version=identity,
                            url=entry['url'], sha256=entry['sha256']))
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("refseq", "lookup", "fasta", "source-ledger", "output", "model-version"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--transcript-kind", choices=("ensembl", "refseq"), required=True)
    args = parser.parse_args()
    destination = Path(args.output)
    require(not destination.exists(), "OUTPUT_EXISTS_REFUSE_OVERWRITE")
    ledger = json.loads(bounded_bytes(args.source_ledger))
    refseq = parse_refseq(bounded_bytes(args.refseq)); lookup = json.loads(bounded_bytes(args.lookup))
    records = source_records(refseq, lookup, args.refseq, args.lookup, ledger)
    model = build_model(refseq, lookup,
                        IndexedFasta(args.fasta), records, args.transcript_kind, args.model_version)
    destination.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(model, indent=2, ensure_ascii=False) + "\n"
    with destination.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(serialized)
    canonical = json.dumps(model, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    print(json.dumps(dict(transcript=model["transcript"], cdsSha256=model["sequenceVerification"]["cdsSha256"],
                          canonicalSha256=sha(canonical), queriedBases=model["sequenceVerification"]["queriedBases"])))


if __name__ == "__main__":
    main()
