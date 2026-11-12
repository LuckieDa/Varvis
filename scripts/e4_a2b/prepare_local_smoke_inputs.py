"""Bounded public-input preparation; never reads AlphaMissense or chooses by score.

The two alleles and MANE transcript versions are fixed before VEP execution.
Patient/disease hypotheses remain synthetic. All run attempts are retained.
Only the existing local reference is streamed once for SHA-256; allele reads
use its existing FASTA index. This is a smoke script, not a runtime producer.
"""

import argparse
import concurrent.futures
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import time


CHOICES = [
    {"caseId": "public-braf-v600e", "assembly": "GRCh38", "contig": "7",
     "position": 140753336, "reference": "A", "alternate": "T",
     "symbol": "BRAF", "ncbiGeneId": "673", "hgncId": "HGNC:1097",
     "transcript": "ENST00000646891.2", "maneSourceRow": 472,
     "clinvarVariationId": "13961", "syntheticHypothesisMondoId": "MONDO:0015280"},
    {"caseId": "public-kras-g12d", "assembly": "GRCh38", "contig": "12",
     "position": 25245350, "reference": "C", "alternate": "T",
     "symbol": "KRAS", "ncbiGeneId": "3845", "hgncId": "HGNC:6407",
     "transcript": "ENST00000311936.8", "maneSourceRow": 2622,
     "clinvarVariationId": "12582", "syntheticHypothesisMondoId": "MONDO:0018997"},
]
HBB_TP53_CHOICES = [
    {"caseId": "public-hbb-sickle", "assembly": "GRCh38", "contig": "11",
     "position": 5227002, "reference": "T", "alternate": "A",
     "symbol": "HBB", "ncbiGeneId": "3043", "hgncId": "HGNC:4827",
     "transcript": "ENST00000335295.4", "maneSourceRow": 2084,
     "clinvarVariationId": "15333", "clinvarHgvs": "NC_000011.10:g.5227002T>A",
     "syntheticHypothesisMondoId": "MONDO:0013517", "syntheticHypothesisMoi": "AUTOSOMAL_RECESSIVE"},
    # Retained opaque preregistration ID: the fixed coordinate's actual VEP change
    # is R248Q, not the initially mistaken R175H mnemonic. Never derive protein from this ID.
    {"caseId": "public-tp53-r175h", "assembly": "GRCh38", "contig": "17",
     "position": 7674220, "reference": "C", "alternate": "T",
     "symbol": "TP53", "ncbiGeneId": "7157", "hgncId": "HGNC:11998",
     "transcript": "ENST00000269305.9", "maneSourceRow": 4935,
     "clinvarVariationId": "12356", "clinvarHgvs": "NC_000017.11:g.7674220C>T",
     "syntheticHypothesisMondoId": "MONDO:0018875", "syntheticHypothesisMoi": "AUTOSOMAL_DOMINANT"},
]
CHOICE_SETS = {"braf-kras": CHOICES, "hbb-tp53": HBB_TP53_CHOICES}
EXPECTED_REFERENCE_SHA = "8d5e868ddc6d9600b82cd7b7e625c240a43f369f6f1a9f16b0c516eb781e7988"


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write(path, report):
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ref_observation(fasta, choice):
    rows = [line.split("\t") for line in Path(str(fasta) + ".fai").read_text(encoding="utf-8").splitlines()]
    names = {row[0] for row in rows}
    contig = choice["contig"] if choice["contig"] in names else "chr" + choice["contig"]
    row = next(row for row in rows if row[0] == contig)
    length, start, bases_per_line, bytes_per_line = map(int, row[1:5])
    position = choice["position"]
    if not 1 <= position <= length:
        raise ValueError("Reference position is out of range")
    offset = start + ((position - 1) // bases_per_line) * bytes_per_line + (position - 1) % bases_per_line
    with fasta.open("rb") as stream:
        stream.seek(offset)
        observed = stream.read(len(choice["reference"])).decode("ascii")
    return {"assembly": choice["assembly"], "contig": choice["contig"],
            "referenceContig": contig, "position": position, "expectedReference": choice["reference"],
            "observedReference": observed, "status": "MATCH" if observed == choice["reference"] else "MISMATCH",
            "method": "FASTA_FAI_BYTE_OFFSET", "methodVersion": "1.0.0",
            "indexRow": "\t".join(row), "byteOffset": offset, "observedAt": now()}


def checksum_receipt(fasta):
    started = now()
    before = fasta.stat()
    actual = sha(fasta)
    after = fasta.stat()
    unchanged = (before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns)
    return {"resourceId": "GRCH38_P14_PRIMARY_CHR", "assembly": "GRCh38",
            "logicalName": "Tool/reference/grch38.p14.primary-chr.fa",
            "resourceVersion": "GRCh38.p14", "expectedSha256": EXPECTED_REFERENCE_SHA,
            "expectedSha256Source": "local-normalization-properties",
            "observedSha256": actual, "hashAlgorithm": "SHA-256", "hashScope": "RAW_BYTES",
            "hashStatus": "VERIFIED" if unchanged and actual == EXPECTED_REFERENCE_SHA else "FAILED",
            "byteLength": before.st_size, "mtimeNs": before.st_mtime_ns,
            "unchangedDuringHash": unchanged, "startedAt": started, "completedAt": now(),
            "indexSha256": sha(Path(str(fasta) + ".fai"))}


def reuse_or_checksum(fasta, earlier_attempts, receipt_from=None):
    stat = fasta.stat()
    if receipt_from is not None:
        previous = json.loads(receipt_from.read_text(encoding="utf-8"))
        earlier_attempts = previous["attempts"]
    for earlier in reversed(earlier_attempts):
        receipt = earlier.get("referenceManifest", {})
        if (receipt.get("hashStatus") == "VERIFIED" and receipt.get("byteLength") == stat.st_size
                and receipt.get("mtimeNs") == stat.st_mtime_ns
                and receipt.get("observedSha256") == EXPECTED_REFERENCE_SHA
                and receipt.get("expectedSha256") == EXPECTED_REFERENCE_SHA
                and receipt.get("unchangedDuringHash") is True
                and receipt.get("logicalName") == "Tool/reference/grch38.p14.primary-chr.fa"
                and receipt.get("indexSha256") == sha(Path(str(fasta) + ".fai"))):
            reused = {**receipt, "reusedFromAttempt": earlier["attemptNumber"], "reuseCheckedAt": now()}
            if receipt_from is not None:
                reused["reusedFromPreparationArtifact"] = receipt_from.name
                reused["reusedFromPreparationSha256"] = sha(receipt_from)
            return reused
    if receipt_from is not None:
        raise ValueError("Supplied reference receipt does not match the current file/index; refusing another full FASTA read")
    return checksum_receipt(fasta)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resource-root", type=Path, required=True)
    parser.add_argument("--binding-resource-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--choice-set", choices=CHOICE_SETS, default="braf-kras")
    parser.add_argument("--reference-receipt-from", type=Path,
                        help="Reuse an existing verified preparation receipt only if current size/mtime/index match; never rehash on mismatch")
    args = parser.parse_args()
    choices = CHOICE_SETS[args.choice_set]
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        report = json.loads(output.read_text(encoding="utf-8"))
        if report["fixedChoices"] != choices:
            raise ValueError("Existing fixed choices differ; refusing replacement")
    else:
        report = {"schemaVersion": "e4-a2b-public-input-preparation-v1", "choicesFixedAt": now(),
                  "resourceDataClass": "PUBLIC", "patientContextDataClass": "SYNTHETIC",
                  "clinicalValidation": False, "alphaMissenseReadByThisScript": False,
                  "selectionBasis": "Public fixed alleles, exact MANE 1.5 transcript versions; no score selection",
                  "choiceSet": args.choice_set, "fixedChoices": choices, "attempts": []}
    write(output, report)  # Freeze choices on disk before touching any execution result.
    root = args.resource_root.resolve()
    tool = root / "Tool"
    fasta = tool / "reference/grch38.p14.primary-chr.fa"
    vep_fasta = tool / "vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa"
    wrapper = tool / "vep/bin/varvis-vep.cmd"
    attempt_number = len(report["attempts"]) + 1
    prefix = "e4-a2b-preparation-attempt-" if args.choice_set == "braf-kras" else "e4-a2b-" + args.choice_set + "-preparation-attempt-"
    work = output.parent / (prefix + str(attempt_number))
    work.mkdir(exist_ok=False)
    attempt = {"attemptNumber": attempt_number, "startedAt": now(), "status": "RUNNING"}
    report["attempts"].append(attempt)
    write(output, report)
    try:
        catalog_file = args.binding_resource_dir / "clingen-catalog.json"
        catalog = json.loads(catalog_file.read_text(encoding="utf-8"))
        attempt["diseaseCatalogRecords"] = [row for choice in choices for row in catalog
                                            if row["hgncId"] == choice["hgncId"]
                                            and row["mondoId"] == choice["syntheticHypothesisMondoId"]]
        attempt["diseaseCatalogSha256"] = sha(catalog_file)
        attempt["diseaseCatalogManifestSha256"] = sha(args.binding_resource_dir / "manifest.json")
        attempt["referenceObservations"] = [ref_observation(fasta, choice) for choice in choices]
        attempt["vepReferenceObservations"] = [ref_observation(vep_fasta, choice) for choice in choices]
        input_vcf = work / "fixed-public-inputs.vcf"
        lines = ["##fileformat=VCFv4.2", "##reference=GRCh38", "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO"]
        for choice in choices:
            lines.append("\t".join([choice["contig"], str(choice["position"]), choice["caseId"],
                                     choice["reference"], choice["alternate"], ".", "PASS", "."]))
        input_vcf.write_text("\n".join(lines) + "\n", encoding="utf-8")
        output_json = work / "real-vep-output.jsonl"
        command = ["cmd.exe", "/d", "/c", str(wrapper), "--offline", "--cache", "--assembly", "GRCh38",
                   "--species", "homo_sapiens", "--cache_version", "116", "--dir_cache", (tool / "vep/cache").as_posix(),
                   "--fasta", vep_fasta.as_posix(), "--format", "vcf", "--json", "--input_file", input_vcf.as_posix(),
                   "--output_file", output_json.as_posix(), "--no_stats", "--symbol", "--canonical", "--mane",
                   "--hgvs", "--protein", "--biotype", "--numbers", "--transcript_version", "--uniprot"]
        attempt["vep"] = {"toolVersion": "116.0", "cacheVersion": "116_GRCh38", "genebuild": "GENCODE 50",
                          "wrapperSha256": sha(wrapper), "cacheInfoSha256": sha(tool / "vep/cache/homo_sapiens/116_GRCh38/info.txt"),
                          "inputVcfSha256": sha(input_vcf), "inputVcf": str(input_vcf.relative_to(output.parent)),
                          "outputJson": str(output_json.relative_to(output.parent)),
                          "referenceLogicalName": "Tool/vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa",
                          "referenceIndexSha256": sha(Path(str(vep_fasta) + ".fai")),
                          "options": command[4:]}
        write(output, report)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            checksum = executor.submit(reuse_or_checksum, fasta, report["attempts"][:-1], args.reference_receipt_from)
            started = time.monotonic()
            try:
                run = subprocess.run(command, cwd=work, capture_output=True, timeout=480)
                attempt["vep"]["exitCode"] = run.returncode
                (work / "vep.stdout.txt").write_bytes(run.stdout)
                (work / "vep.stderr.txt").write_bytes(run.stderr)
                attempt["vep"]["stderrBytes"] = len(run.stderr)
                attempt["vep"]["stdoutBytes"] = len(run.stdout)
            finally:
                attempt["referenceManifest"] = checksum.result()
                attempt["vep"]["elapsedSeconds"] = round(time.monotonic() - started, 3)
        if output_json.exists():
            records = [json.loads(line) for line in output_json.read_text(encoding="utf-8").splitlines() if line.strip()]
            attempt["vep"]["outputSha256"] = sha(output_json)
            attempt["vep"]["outputRecordCount"] = len(records)
            selected = []
            for choice in choices:
                source = [row for row in records if row.get("id") == choice["caseId"]]
                annotations = [tc for row in source for tc in row.get("transcript_consequences", [])
                               if tc.get("transcript_id") == choice["transcript"]]
                selected.append({"caseId": choice["caseId"], "recordCount": len(source),
                                 "selectedTranscriptCount": len(annotations), "selectedAnnotations": annotations})
            attempt["selectedVepAnnotations"] = selected
        attempt["status"] = "PREPARED" if (attempt["vep"].get("exitCode") == 0
                and attempt["referenceManifest"]["hashStatus"] == "VERIFIED"
                and all(row["status"] == "MATCH" for row in attempt["referenceObservations"])
                and all(row["status"] == "MATCH" for row in attempt["vepReferenceObservations"])
                and all(row["selectedTranscriptCount"] == 1 for row in attempt.get("selectedVepAnnotations", []))
                and len(attempt.get("selectedVepAnnotations", [])) == len(choices)) else "NOT_READY"
    except Exception as failure:
        attempt["status"] = "FAILED"
        attempt["errorType"] = type(failure).__name__
        attempt["error"] = str(failure)
    attempt["completedAt"] = now()
    write(output, report)
    print(json.dumps({"status": attempt["status"], "attemptNumber": attempt_number,
                      "referenceHashStatus": attempt.get("referenceManifest", {}).get("hashStatus"),
                      "vepExitCode": attempt.get("vep", {}).get("exitCode"),
                      "selectedCounts": [row["selectedTranscriptCount"] for row in attempt.get("selectedVepAnnotations", [])]}))


if __name__ == "__main__":
    main()
