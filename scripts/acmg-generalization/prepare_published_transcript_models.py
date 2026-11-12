"""Offline exact GENCODE/RefSeq bridges and explicitly coding-only RefSeq transfers.

Input models already carry the production qualified-reference exon proof. This
preparer verifies pinned originals, independent RNA, all exon boundaries and a
real MANE row. It grants technical sequence identity only, never policy approval.
"""
import argparse
import copy
import csv
import gzip
import io
import json
import re
from pathlib import Path

from build_transcript_model import bounded_bytes, parse_refseq, require, sha, CODONS

VERSION = "1.0.0"

# Exact GRCh38 primary-chromosome accessions used by the pinned MANE summary.
# Do not infer other assemblies or alternate loci by stripping accession versions.
GRCH38_PRIMARY_CONTIG = dict(zip(
    ("NC_000001.11", "NC_000002.12", "NC_000003.12", "NC_000004.12", "NC_000005.10", "NC_000006.12",
     "NC_000007.14", "NC_000008.11", "NC_000009.12", "NC_000010.11", "NC_000011.10", "NC_000012.12",
     "NC_000013.11", "NC_000014.9", "NC_000015.10", "NC_000016.10", "NC_000017.11", "NC_000018.10",
     "NC_000019.10", "NC_000020.11", "NC_000021.9", "NC_000022.11", "NC_000023.11", "NC_000024.10"),
    [str(i) for i in range(1, 23)] + ["X", "Y"]))


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def supplement_gene_identity(model_raw, annotation_raw, annotation_sha, index_manifest_sha):
    """Bind a missing Ensembl gene ID from an exact pinned full-exon annotation.

    Sequence, RNA equivalence and disease adoption are not recomputed or granted.
    A neighboring transcript version or a merely coding-equivalent model is not enough.
    """
    require(sha(annotation_raw)==annotation_sha and re.fullmatch('[0-9a-f]{64}',index_manifest_sha),
            'GENE_ID_ANNOTATION_PIN_MISMATCH')
    model=json.loads(model_raw);row=json.loads(annotation_raw)
    require(re.fullmatch(r'ENST[0-9]+\.[1-9][0-9]*',model.get('transcript',''))
            and re.fullmatch(r'ENSG[0-9]+(?:\.[1-9][0-9]*)?',row.get('geneId','')),
            'GENE_ID_EXACT_SOURCE_REQUIRED')
    for field in ('transcript','protein','hgncId','geneSymbol','assembly','contig','strand','cdsLength','exons'):
        require(model.get(field)==row.get(field),'GENE_ID_FULL_MODEL_MISMATCH')
    require(model.get('geneId') in (None,row['geneId']),'GENE_ID_EXISTING_CONFLICT')
    original=copy.deepcopy(model)
    model['geneId']=row['geneId']
    model['geneIdentitySupplement']={'method':'PINNED_EXACT_FULL_EXON_GENCODE_IDENTITY_V1',
        'sourceModelRawSha256':sha(model_raw),'annotationRowSha256':annotation_sha,'indexManifestSha256':index_manifest_sha,
        'annotation':row,'clinicalAdoption':False,'newSequenceOrRnaApproval':False}
    require({k:v for k,v in model.items() if k not in ('geneId','geneIdentitySupplement')}==
            {k:v for k,v in original.items() if k not in ('geneId','geneIdentitySupplement')},'GENE_ID_UNEXPECTED_MODEL_CHANGE')
    return model


def pinned(spec, maximum=4_000_000):
    content = bounded_bytes(spec["file"], maximum)
    require(sha(content) == spec["sha256"], "INPUT_RAW_PIN_MISMATCH")
    return content


def parse_coding_view(content):
    """Separate limited parser; does not weaken parse_refseq's complete-RNA rule.

    UTR exon omissions are disclosed. Every coding base and junction must still
    be represented once, contiguously, with exact translated CDS and identifiers.
    """
    text = content.decode("utf-8")
    versions = re.findall(r"^VERSION\s+(NM_\d+\.\d+)\s*$", text, re.M)
    cds = re.findall(r"^     CDS\s+(\d+)\.\.(\d+)\s*$", text, re.M)
    proteins = re.findall(r'/protein_id="(NP_\d+\.\d+)"', text)
    translations = re.findall(r'/translation="([A-Z\s]+)"', text)
    genes = set(re.findall(r'/gene="([^"]+)"', text))
    hgnc = set(re.findall(r'/db_xref="HGNC:(?:HGNC:)?(\d+)"', text))
    origins = re.findall(r"^ORIGIN[^\n]*\n([\s\S]*?)^//", text, re.M)
    require(len(versions) == len(cds) == len(proteins) == len(translations) == len(genes) == len(hgnc) == len(origins) == 1,
            "CODING_VIEW_IDENTITY_OR_CDS_AMBIGUOUS")
    sequence = re.sub(r"[\s\d]", "", origins[0]).upper()
    require(re.fullmatch("[ACGTN]+", sequence) and len(sequence) <= 1_000_000, "CODING_VIEW_RNA_INVALID")
    lo, hi = map(int, cds[0])
    coding = sequence[lo - 1:hi]
    protein = re.sub(r"\s", "", translations[0])
    require(1 <= lo <= hi <= len(sequence) and len(coding) % 3 == 0 and coding.startswith("ATG")
            and re.fullmatch("[ACGT]+", coding), "CODING_VIEW_CDS_INVALID")
    require("".join(CODONS[coding[i:i + 3]] for i in range(0, len(coding), 3)) == protein + "*",
            "CODING_VIEW_TRANSLATION_MISMATCH")
    exons = [(int(a), int(b)) for a, b in re.findall(r"^     exon\s+(\d+)\.\.(\d+)\s*$", text, re.M)]
    previous = 0
    for a, b in exons:
        require(previous < a <= b <= len(sequence), "CODING_VIEW_EXONS_INVALID")
        previous = b
    coding_exons = [[max(a, lo) - lo + 1, min(b, hi) - lo + 1] for a, b in exons if a <= hi and b >= lo]
    cursor = 1
    for a, b in coding_exons:
        require(a == cursor and b >= a, "CODING_VIEW_CODING_EXONS_INCOMPLETE")
        cursor = b + 1
    require(cursor == len(coding) + 1, "CODING_VIEW_CODING_EXONS_INCOMPLETE")
    full = bool(exons) and exons[0][0] == 1 and exons[-1][1] == len(sequence)
    full = full and all(exons[i][0] == exons[i - 1][1] + 1 for i in range(1, len(exons)))
    return dict(transcript=versions[0], protein=proteins[0], proteinSequence=protein, symbol=genes.pop(),
                hgncId="HGNC:" + hgnc.pop(), sequence=sequence, cdsSequence=coding, cdsStart=lo, cdsEnd=hi,
                exons=exons, codingExonIntervals=coding_exons, fullExonCoverage=full)


def mane_rows(raw):
    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
        text = stream.read(12_000_001)
    require(len(text) <= 12_000_000, "MANE_DECOMPRESSED_LIMIT")
    lines = text.decode("utf-8").splitlines(keepends=True)
    require(lines and lines[0].startswith("#NCBI_GeneID\t"), "MANE_HEADER_INVALID")
    header = next(csv.reader([lines[0]], delimiter="\t"))
    rows = []
    for line in lines[1:]:
        fields = next(csv.reader([line], delimiter="\t"))
        require(len(fields) == len(header), "MANE_ROW_INVALID")
        rows.append((dict(zip(header, fields)), line))
    return rows


def coding_intervals(model):
    length = model["cdsLength"]
    return [[max(1, e["cdsStart"]), min(length, e["cdsEnd"])] for e in model["exons"]
            if e["cdsEnd"] >= 1 and e["cdsStart"] <= length]


def source_record(transcript, raw, source):
    require(source["version"] == transcript and source["sha256"] == sha(raw)
            and source["url"].startswith("https://"), "REFSEQ_SOURCE_BINDING_MISMATCH")
    return dict(sourceId="RefSeq:" + transcript, sourceKind="NCBI_VERSIONED_REFSEQ_GENBANK",
                version=transcript, sha256=sha(raw), url=source["url"])


def enrich(model_raw, refseq_raw, source, mane_raw, mane_version, model_version):
    model = json.loads(model_raw)
    r = parse_refseq(refseq_raw)
    require(model["assembly"] == "GRCh38" and model["hgncId"] == r["hgncId"]
            and model["geneSymbol"] == r["symbol"], "MODEL_REFSEQ_GENE_MISMATCH")
    matches = [(row, raw) for row, raw in mane_rows(mane_raw)
               if row["RefSeq_nuc"] == r["transcript"] and row["Ensembl_nuc"] == model["transcript"]]
    require(len(matches) == 1, "EXACT_MANE_ROW_REQUIRED")
    row, row_raw = matches[0]
    require(row["HGNC_ID"] == r["hgncId"] and row["symbol"] == r["symbol"]
            and row["RefSeq_prot"] == r["protein"] and row["Ensembl_prot"] == model["protein"]
            and row["GRCh38_chr"] in GRCH38_PRIMARY_CONTIG
            and GRCH38_PRIMARY_CONTIG[row["GRCh38_chr"]] == model["contig"]
            and type(model["strand"]) is int and model["strand"] in (1, -1)
            and row["chr_strand"] == ("+" if model["strand"] == 1 else "-")
            and int(row["chr_start"]) == min(e["genomicStart"] for e in model["exons"])
            and int(row["chr_end"]) == max(e["genomicEnd"] for e in model["exons"]), "MANE_IDENTITY_OR_BOUNDS_MISMATCH")
    require(r["maneMatches"] == [(model["transcript"], model["protein"])], "REFSEQ_MANE_VERSIONED_BRIDGE_MISMATCH")
    proof = model["sequenceVerification"]
    require(proof["method"] == "PINNED_GTF_PLUS_QUALIFIED_REFERENCE"
            and proof["referenceIdentity"]["verificationStatus"] == "VERIFIED"
            and proof["referenceIdentity"]["expectedSha256"] == proof["referenceIdentity"]["observedSha256"],
            "MODEL_QUALIFIED_REFERENCE_REQUIRED")
    require(proof["fullSplicedTranscriptSha256"] == sha(r["sequence"].encode())
            and proof["queriedBases"] == len(r["sequence"]), "MODEL_REFSEQ_FULL_RNA_MISMATCH")
    require(model["cdsSequence"] == r["cdsSequence"] and model["cdsLength"] == len(r["cdsSequence"])
            and proof["cdsSha256"] == sha(r["cdsSequence"].encode())
            and model["proteinLength"] == len(r["proteinSequence"]), "MODEL_REFSEQ_CDS_OR_PROTEIN_MISMATCH")
    require(len(model["exons"]) == len(r["exons"]), "MODEL_REFSEQ_EXON_COUNT_MISMATCH")
    previous = None
    for ordinal, (exon, (a, b)) in enumerate(zip(model["exons"], r["exons"]), 1):
        require(exon["ordinal"] == ordinal and exon["genomicStart"] >= 1
                and exon["genomicEnd"] - exon["genomicStart"] == b - a
                and exon["cdsStart"] == a - r["cdsStart"] + 1
                and exon["cdsEnd"] == b - r["cdsStart"] + 1, "MODEL_REFSEQ_FULL_EXON_MISMATCH")
        require(previous is None or (exon["genomicStart"] > previous["genomicEnd"] if model["strand"] == 1
                                     else exon["genomicEnd"] < previous["genomicStart"]), "MODEL_EXON_ORDER_INVALID")
        previous = exon
    sources = model["sourceRecords"]
    require(len(sources) == 1 and sources[0]["sourceKind"] == "GENCODE_GTF_LOCAL_SNAPSHOT"
            and sources[0]["sourceId"] == "GENCODE:" + model["transcript"], "MODEL_GENCODE_SOURCE_REQUIRED")
    for key in ("sha256", "annotationRowSha256", "rawGtfSha256", "indexManifestSha256"):
        require(re.fullmatch("[0-9a-f]{64}", sources[0][key]), "MODEL_GENCODE_PIN_REQUIRED")
    rsource = source_record(r["transcript"], refseq_raw, source)
    sources.append(rsource)
    sources.append(dict(sourceId="MANE:" + mane_version + ":" + model["transcript"], sourceKind="NCBI_MANE_SUMMARY_ROW",
                        version=mane_version, url="https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/",
                        sha256=sha(mane_raw), rowRawSha256=sha(row_raw.encode()), rowRawUtf8=row_raw))
    model["modelVersion"] = model_version
    model["usageScope"] = "TECHNICAL_SEQUENCE_IDENTITY_NO_POLICY_ADOPTION"
    model["transcriptBridge"] = dict(status="FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE", refseqTranscript=r["transcript"],
                                     refseqProtein=r["protein"], ensemblTranscript=model["transcript"], ensemblProtein=model["protein"])
    proof["fastaIndexSha256"] = proof["referenceIdentity"]["indexSha256"]
    proof["independentRnaVerification"] = True
    proof["independentRnaMethod"] = "PINNED_GENCODE_REFERENCE_AND_REFSEQ_FULL_RNA_V1"
    proof["rnaEquivalence"] = dict(schemaVersion="gencode-refseq-full-equivalence-v1", sourceModelRawSha256=sha(model_raw),
        refseqRawSha256=sha(refseq_raw), refseqTranscript=r["transcript"], refseqProtein=r["protein"], hgncId=r["hgncId"],
        fullSplicedTranscriptSha256=sha(r["sequence"].encode()), cdsSha256=sha(r["cdsSequence"].encode()),
        fullExonLengths=[b - a + 1 for a, b in r["exons"]], codingExonIntervals=coding_intervals(model),
        maneRawSha256=sha(mane_raw), maneRowRawSha256=sha(row_raw.encode()), maneRow=row,
        scientificPolicyAdoption=False)
    return model


def add_coding_transfer(model, previous_raw, previous_source, current_raw):
    old, current = parse_coding_view(previous_raw), parse_refseq(current_raw)
    require(old["transcript"] != current["transcript"] and old["transcript"].split(".")[0] == current["transcript"].split(".")[0],
            "TRANSFER_SAME_ACCESSION_DISTINCT_VERSION_REQUIRED")
    require(old["hgncId"] == current["hgncId"] == model["hgncId"] and old["symbol"] == current["symbol"] == model["geneSymbol"],
            "TRANSFER_GENE_MISMATCH")
    require(old["protein"] == current["protein"] == model["transcriptBridge"]["refseqProtein"]
            and old["cdsSequence"] == current["cdsSequence"] == model["cdsSequence"]
            and old["codingExonIntervals"] == coding_intervals(model), "TRANSFER_CODING_IDENTITY_MISMATCH")
    require(model["transcriptBridge"]["refseqTranscript"] == current["transcript"]
            and model["sequenceVerification"]["rnaEquivalence"]["refseqRawSha256"] == sha(current_raw), "TRANSFER_TARGET_BINDING_MISMATCH")
    model = copy.deepcopy(model)
    model["sourceRecords"].append(source_record(old["transcript"], previous_raw, previous_source))
    model["codingTranscriptTransfers"] = [dict(schemaVersion="refseq-coding-missense-transfer-v1",
        status="CDS_PROTEIN_AND_CODING_EXONS_IDENTICAL", allowedPurpose="PURE_CODING_MISSENSE_SNV",
        sourceTranscript=old["transcript"], targetTranscript=current["transcript"], refseqProtein=old["protein"],
        ensemblTranscript=model["transcript"], ensemblProtein=model["protein"], hgncId=model["hgncId"], geneSymbol=model["geneSymbol"],
        sourceRawSha256=sha(previous_raw), targetRawSha256=sha(current_raw), cdsSha256=sha(old["cdsSequence"].encode()),
        codingExonIntervals=old["codingExonIntervals"], sourceRnaSha256=sha(old["sequence"].encode()),
        targetRnaSha256=sha(current["sequence"].encode()), fullRnaEquivalent=old["sequence"] == current["sequence"],
        fullTranscriptEquivalenceClaimed=False, sourceFullExonCoverage=old["fullExonCoverage"], scientificPolicyAdoption=False)]
    return model


def prepare(plan, output):
    require(plan["schemaVersion"] == "published-transcript-model-plan-v1", "PLAN_SCHEMA_INVALID")
    output = Path(output)
    require(not output.exists(), "OUTPUT_EXISTS_REFUSE_OVERWRITE")
    mane_raw = pinned(plan["mane"])
    ledger = json.loads(pinned(plan["sourceLedger"]))
    models, entries = [], []
    for spec in plan["models"]:
        model_raw, rna_raw = pinned(spec["model"]), pinned(spec["refseq"])
        rows = [r for r in ledger["entries"] if r["sha256"] == sha(rna_raw)
                and Path(r["destination"]).resolve() == Path(spec["refseq"]["file"]).resolve()
                and r["status"] == "CAPTURED_PENDING_SOURCE_REVIEW"]
        require(len(rows) == 1, "REFSEQ_LEDGER_MISMATCH")
        model = enrich(model_raw, rna_raw, rows[0], mane_raw, plan["mane"]["version"], plan["modelVersion"])
        if "codingTransferFrom" in spec:
            old_raw = pinned(spec["codingTransferFrom"])
            old_rows = [r for r in ledger["entries"] if r["sha256"] == sha(old_raw)
                        and Path(r["destination"]).resolve() == Path(spec["codingTransferFrom"]["file"]).resolve()
                        and r["status"] == "CAPTURED_PENDING_SOURCE_REVIEW"]
            require(len(old_rows) == 1, "TRANSFER_REFSEQ_LEDGER_MISMATCH")
            model = add_coding_transfer(model, old_raw, old_rows[0], rna_raw)
        require(model["transcript"] not in [m["transcript"] for m in models], "DUPLICATE_MODEL_TRANSCRIPT")
        models.append(model)
    output.mkdir(parents=True)
    for model in models:
        raw = (json.dumps(model, indent=2, ensure_ascii=False) + "\n").encode()
        name = model["transcript"] + ".model.json"
        (output / name).write_bytes(raw)
        entries.append(dict(transcript=model["transcript"], file=name, fileBytes=len(raw), fileSha256=sha(raw),
                            canonicalSha256=sha(canonical(model)), modelId=model["modelId"], modelVersion=model["modelVersion"]))
    manifest = dict(schemaVersion="portable-native-transcript-model-derivation-v1", method="PUBLISHED_EXACT_TRANSCRIPT_PREPARATION",
                    methodVersion=VERSION, planSha256=sha(canonical(plan)), scientificPolicyAdoption=False, entries=entries)
    (output / "manifest.json").write_bytes((json.dumps(manifest, indent=2) + "\n").encode())
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = prepare(json.loads(bounded_bytes(args.plan)), args.output)
    print(json.dumps(dict(models=len(result["entries"]), manifestSha256=sha(bounded_bytes(Path(args.output) / "manifest.json")))))
