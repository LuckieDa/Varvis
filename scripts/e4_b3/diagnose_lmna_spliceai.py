"""Opt-in single-allele official SpliceAI diagnosis; no Varvis runner/parser imports.

Uses the installed official CLI unchanged. A read-only Python return trace records
its own intermediate arrays; it does not replace inference, inputs or arithmetic.
All outputs are new files. Existing business results are read-only comparison data.
"""
import argparse
import csv
import hashlib
import importlib.metadata
import importlib
import json
import runpy
import sys
from decimal import Decimal
from pathlib import Path

POSITION = 156138613
ALLELE = ("1", POSITION, "C", "T")
DS = ("DS_AG", "DS_AL", "DS_DG", "DS_DL")
DP = ("DP_AG", "DP_AL", "DP_DG", "DP_DL")


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def save(path, value):
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def fasta_region(path, contig, start, end):
    """Read only the requested 1-based inclusive interval through the existing FAI."""
    with Path(str(path) + ".fai").open() as stream:
        rows = [line.rstrip().split("\t") for line in stream]
    row = next(row for row in rows if row[0] == contig)
    length, offset, bases, width = map(int, row[1:5])
    if not 1 <= start <= end <= length:
        raise ValueError("REFERENCE_INTERVAL_INVALID")
    first = offset + (start - 1) // bases * width + (start - 1) % bases
    last = offset + (end - 1) // bases * width + (end - 1) % bases
    with Path(path).open("rb") as stream:
        stream.seek(first)
        sequence = stream.read(last - first + 1).replace(b"\n", b"").replace(b"\r", b"")
    assert len(sequence) == end - start + 1
    return sequence.decode("ascii")


def parse_official_vcf(path):
    """Independent, deliberately strict parser for this one public biallelic SNV."""
    rows = [line.split("\t") for line in Path(path).read_text().splitlines() if line and not line.startswith("#")]
    if len(rows) != 1 or len(rows[0]) < 8:
        raise ValueError("EXPECTED_ONE_OFFICIAL_RECORD")
    row = rows[0]
    if (row[0], int(row[1]), row[3], row[4]) != ALLELE:
        raise ValueError("OFFICIAL_ALLELE_MISMATCH")
    info = [item[9:] for item in row[7].split(";") if item.startswith("SpliceAI=")]
    if len(info) != 1:
        raise ValueError("OFFICIAL_INFO_MISSING_OR_DUPLICATED")
    annotations = [item.split("|") for item in info[0].split(",")]
    if len(annotations) != 1 or len(annotations[0]) != 10 or annotations[0][:2] != ["T", "LMNA"]:
        raise ValueError("OFFICIAL_GENE_OR_ALT_MISMATCH")
    values = annotations[0]
    scores = dict(zip(DS, values[2:6]))
    positions = dict(zip(DP, map(int, values[6:10])))
    if not all(Decimal("0") <= Decimal(v) <= Decimal("1") for v in scores.values()):
        raise ValueError("OFFICIAL_SCORE_INVALID")
    if not all(abs(v) <= 4999 for v in positions.values()):
        raise ValueError("OFFICIAL_POSITION_INVALID")
    return {"geneSymbol": "LMNA", "deltaScores": scores, "deltaPositions": positions,
            "maximumRawScore": max(scores.values(), key=Decimal)}


def audit(reference, profile):
    package = Path(importlib.metadata.distribution("spliceai").locate_file("spliceai"))
    identity = json.loads(Path(profile).read_text())["resource"]["identity"]
    assets = []
    for item in identity["models"] + [identity["geneAnnotation"]] + identity["cliSources"]:
        path = package / item["logicalPath"].removeprefix("spliceai/")
        actual = {"logicalPath": item["logicalPath"], "byteLength": path.stat().st_size, "sha256": sha256(path)}
        assert actual["byteLength"] == item["byteLength"] and actual["sha256"] == item["sha256"], actual
        assets.append(actual)
    with (package / "annotations/grch38.txt").open() as stream:
        genes = [row for row in csv.DictReader(stream, delimiter="\t") if row["#NAME"] == "LMNA"]
    assert len(genes) == 1 and genes[0]["STRAND"] == "+"
    sequence = fasta_region(reference, "1", POSITION - 9999, POSITION + 9999)
    assert sequence[9999] == "C"
    versions = {key: importlib.metadata.version(key) for key in ("spliceai", "tensorflow-cpu", "keras", "numpy", "pyfaidx", "pysam")}
    assert versions["spliceai"] == "1.3.1" and versions["tensorflow-cpu"] == "2.15.1" and versions["keras"] == "2.15.0"
    reference_identity = identity["reference"]
    assert sha256(str(reference) + ".fai") == reference_identity["indexSha256"]
    assert reference.stat().st_size == reference_identity["byteLength"]
    return {"versions": versions, "assets": assets, "annotationArgument": "grch38", "lmnaAnnotation": genes[0],
            "referenceIndexSha256": sha256(str(reference) + ".fai"), "fullFastaHashClaimed": False,
            "genomicWindow": {"start": POSITION - 9999, "end": POSITION + 9999, "length": len(sequence),
                              "sha256": hashlib.sha256(sequence.encode()).hexdigest(), "center21": sequence[9989:10010]},
            "distance": 4999, "mask": 0}


def verify_saved_business(path, official):
    """Read existing JSON without Java/Python production projection or evaluation."""
    saved = json.loads(Path(path).read_text())
    attempts = [a for c in saved["candidates"] for a in c["attempts"]
                if (a.get("transcriptInput") or {}).get("hgvsc") == "ENST00000368300.9:c.1824C>T"]
    assert len(attempts) == 1
    attempt = attempts[0]
    proof = attempt["transcriptInput"]["synonymousExonPosition"]
    assert (proof["assembly"], proof["contig"].removeprefix("chr"), proof["position"], proof["reference"], proof["alternate"]) == ("GRCh38", *ALLELE)
    assert (proof["exonStart"], proof["exonEnd"], proof["strand"]) == (156138488, 156138757, 1)
    records = [e for e in attempt["atomicEvidence"] if any(m["metricId"] == "spliceai.DS_DG" for m in e["measurements"])]
    assert len(records) == 1
    measurements = {m["metricId"]: m["rawValue"] for m in records[0]["measurements"]}
    for key, value in {**official["deltaScores"], **official["deltaPositions"]}.items():
        assert measurements["spliceai." + key] == str(value)
    assert measurements["spliceai.maximumRawDeltaScore"] == official["maximumRawScore"]
    evaluations = {e["criterionId"].split(":")[-1]: e for e in attempt["criterionEvaluations"]}
    return {"runId": saved["runId"], "savedBusinessSha256": sha256(path), "allEightAtomicFieldsMatchOfficial": True,
            "evaluations": {code: {"status": e["evaluationResult"]["status"], "conflict": e["conflict"], "review": e["review"]} for code, e in evaluations.items()},
            "note": "NONE/NOT_REQUIRED in the old computational output does not establish absence of external experimental conflict"}


def trace_official(observations):
    def trace(frame, event, arg):
        if frame.f_code.co_name != "get_delta_scores" or not frame.f_code.co_filename.replace("\\", "/").endswith("/spliceai/utils.py"):
            return None
        if event == "return" and "y" in frame.f_locals:
            v = frame.f_locals
            assert len(v["idxs"]) == 1 and len(v["record"].alts) == 1
            y = v["y"]
            extrema = {}
            for name, index_name, channel in (("AG", "idx_pa", 1), ("AL", "idx_na", 1), ("DG", "idx_pd", 2), ("DL", "idx_nd", 2)):
                index = int(v[index_name])
                extrema[name] = {"offset": index - v["cov"] // 2, "referenceProbability": float(y[0, index, channel]),
                                 "alternateProbability": float(y[1, index, channel])}
            observations.append({"officialReturn": arg, "position": v["record"].pos, "gene": str(v["genes"][0]),
                                 "strand": str(v["strands"][0]), "modelCount": len(v["ann"].models),
                                 "distance": v["dist_var"], "mask": v["mask"], "inputLength": v["wid"], "outputLength": v["cov"],
                                 "padding": list(map(int, v["pad_size"])), "annotationDistances": list(map(int, v["dist_ann"])),
                                 "extrema": extrema})
        return trace
    return trace


def mane_annotation(transcript):
    if (transcript.get("id"), transcript.get("version"), transcript.get("assembly_name"), transcript.get("strand"), transcript.get("seq_region_name")) != ("ENST00000368300", 9, "GRCh38", 1, "1"):
        raise ValueError("DIAGNOSTIC_MANE_IDENTITY_MISMATCH")
    exons = sorted(transcript["Exon"], key=lambda exon: exon["start"])
    if len(exons) != 12 or not any((e["start"], e["end"]) == (156138488, 156138757) for e in exons):
        raise ValueError("DIAGNOSTIC_MANE_EXON_MISMATCH")
    assert exons[0]["start"] == transcript["start"] and exons[-1]["end"] == transcript["end"]
    return {"#NAME": "LMNA", "CHROM": "1", "STRAND": "+", "TX_START": str(transcript["start"] - 1),
            "TX_END": str(transcript["end"]), "EXON_START": "".join(str(e["start"] - 1) + "," for e in exons),
            "EXON_END": "".join(str(e["end"]) + "," for e in exons)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("audit", "run"))
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--saved-raw", type=Path)
    parser.add_argument("--mane-json", type=Path, help="Optional diagnostic-only single-variable annotation control")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    inventory = audit(args.reference, args.profile)
    annotation = "grch38"
    if args.mane_json:
        row = mane_annotation(json.loads(args.mane_json.read_text()))
        annotation = str(args.output_dir / "diagnostic-mane-annotation.tsv")
        with Path(annotation).open("x", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(row), delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerow(row)
        inventory["annotationControl"] = {"sourceJsonSha256": sha256(args.mane_json), "annotationSha256": sha256(annotation), "actualLmnaAnnotation": row}
        inventory["annotationArgument"] = "DIAGNOSTIC_ONLY_MANE_ENST00000368300.9"
    save(args.output_dir / (args.mode + "-inventory.json"), inventory)
    if args.mode == "audit":
        print(json.dumps(inventory, indent=2))
        return
    if args.saved_raw is None:
        parser.error("run requires --saved-raw")
    before = sha256(args.saved_raw)
    input_path, output_path = args.output_dir / "official-input.vcf", args.output_dir / "official-output.vcf"
    if output_path.exists():
        raise FileExistsError(output_path)
    with input_path.open("x") as stream:
        stream.write("##fileformat=VCFv4.2\n##reference=GRCh38\n##contig=<ID=1,length=248956422>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n1\t156138613\tLMNA-public-counterexample\tC\tT\t.\tPASS\t.\n")
    argv = ["spliceai", "-I", str(input_path), "-O", str(output_path), "-R", str(args.reference), "-A", annotation, "-D", "4999", "-M", "0"]
    save(args.output_dir / "invocation.json", {"argv": argv, "execution": "unmodified installed official __main__; read-only return tracing", "inputSha256": sha256(input_path)})
    observations = []
    sys.argv = argv
    # Load heavy dependencies before enabling observation; inference is still executed by __main__.
    importlib.import_module("spliceai.utils")
    sys.settrace(trace_official(observations))
    try:
        runpy.run_module("spliceai", run_name="__main__")
    except SystemExit as finished:
        if finished.code not in (None, 0):
            raise
    finally:
        sys.settrace(None)
        save(args.output_dir / "official-internals.json", observations)
    official = parse_official_vcf(output_path)
    historical = json.loads(args.saved_raw.read_text())
    row = next(row for row in historical["results"] if row["allele"]["position"] == POSITION)
    assert row["allele"] == {"assembly": "GRCh38", "contig": "1", "position": POSITION, "reference": "C", "alternate": "T"}
    assert len(observations) == 1 and observations[0]["modelCount"] == 5
    comparison = {"official": official, "saved": row["annotations"], "matchesSaved": row["annotations"] == [official],
                  "historicalRawSha256": before, "historicalUnchanged": before == sha256(args.saved_raw),
                  "officialOutputSha256": sha256(output_path), "officialModelBatches": 1,
                  "biologicalAcceptancePassed": False}
    save(args.output_dir / "comparison.json", comparison)
    assert comparison["historicalUnchanged"]
    if not args.mane_json:
        assert comparison["matchesSaved"]
    print(json.dumps(comparison, indent=2))


if __name__ == "__main__":
    main()
