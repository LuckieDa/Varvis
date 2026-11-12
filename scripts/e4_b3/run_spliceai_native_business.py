"""Pinned Windows RAW transport. Association/adoption remain Java-owned gates."""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import sys
import run_spliceai_native_probe as engine

VERSION = "1.1.0"
SCHEMA = "spliceai-native-business-result-v1"
REQUEST_SCHEMA = "spliceai-native-selected-request-v1"


def identity(reference):
    return json.loads(json.dumps({"schemaVersion": "spliceai-native-business-identity-v1", "version": VERSION,
            "adapterSha256": engine.raw.sha256_file(Path(__file__)),
            "engine": engine.runtime_identity(reference),
            "associationMethod": "SELECTED_GENCODE_CUSTOM_ANNOTATION_AND_QUALIFIED_WINDOW_V1",
            "legacyRuntimeIdentityReused": False}))


def require(condition, reason):
    if not condition:
        raise engine.raw.ProbeError(reason)


def validate_selected_request(request):
    require(isinstance(request, dict) and request.get("requestSchemaVersion") == REQUEST_SCHEMA,
            "NATIVE_SELECTED_REQUEST_REQUIRED")
    require(request.get("assembly") == "GRCh38" and type(request.get("distanceBp")) is int
            and request["distanceBp"] == 4999 and type(request.get("mask")) is int and request["mask"] == 0,
            "NATIVE_SELECTED_PARAMETERS_INVALID")
    require(isinstance(request.get("requestId"), str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", request["requestId"]),
            "NATIVE_SELECTED_REQUEST_ID_INVALID")
    variants = request.get("variants")
    require(isinstance(variants, list) and 1 <= len(variants) <= 10, "NATIVE_BATCH_LIMIT_EXCEEDED")
    ids, units = set(), set()
    for value in variants:
        require(isinstance(value, dict), "NATIVE_SELECTED_VARIANT_INVALID")
        query = value.get("queryId")
        require(isinstance(query, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", query)
                and query not in ids, "NATIVE_SELECTED_QUERY_DUPLICATE_OR_INVALID")
        ids.add(query)
        allele = value.get("allele")
        require(isinstance(allele, dict) and allele.get("assembly") == "GRCh38"
                and isinstance(allele.get("contig"), str) and re.fullmatch(r"(?:[1-9]|1[0-9]|2[0-2]|X|Y)", allele["contig"])
                and type(allele.get("position")) is int and 0 < allele["position"] <= 2147483647
                and all(isinstance(allele.get(k), str) and re.fullmatch(r"[ACGT]", allele[k]) for k in ("reference", "alternate"))
                and allele["reference"] != allele["alternate"], "NATIVE_SELECTED_ALLELE_INVALID")
        ann = value.get("selectedAnnotation")
        require(isinstance(ann, dict) and isinstance(ann.get("transcriptId"), str)
                and re.fullmatch(r"ENST[0-9]+\.[1-9][0-9]*", ann["transcriptId"])
                and isinstance(ann.get("geneId"), str) and re.fullmatch(r"ENSG[0-9]+(?:\.[1-9][0-9]*)?", ann["geneId"])
                and isinstance(ann.get("geneSymbol"), str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", ann["geneSymbol"])
                and ann.get("contig") == allele["contig"] and ann.get("strand") in ("+", "-")
                and ann["transcriptId"] == value.get("selectedTranscriptId")
                and ann["geneSymbol"] == value.get("geneSymbol"), "NATIVE_SELECTED_ANNOTATION_IDENTITY_INVALID")
        exons = ann.get("exons")
        require(isinstance(exons, list) and 1 <= len(exons) <= 1000, "NATIVE_SELECTED_EXONS_INVALID")
        previous = 0
        for exon in exons:
            require(isinstance(exon, list) and len(exon) == 2 and all(type(n) is int for n in exon)
                    and previous < exon[0] <= exon[1] <= 2147483647, "NATIVE_SELECTED_EXONS_INVALID")
            previous = exon[1]
        require(type(ann.get("start")) is int and type(ann.get("end")) is int
                and ann["start"] == exons[0][0] and ann["end"] == exons[-1][1]
                and ann["start"] <= allele["position"] <= ann["end"], "NATIVE_SELECTED_TRANSCRIPT_RANGE_INVALID")
        unit = engine.raw.canonical_sha256({"allele": allele, "annotation": ann})
        require(unit not in units, "NATIVE_SELECTED_UNIT_DUPLICATED")
        units.add(unit)
    return request


def write_selected_annotations(request, path):
    """Official file format is zero-based starts, exclusive ends, on both strands."""
    validate_selected_request(request)
    lines = ["#NAME\tCHROM\tSTRAND\tTX_START\tTX_END\tEXON_START\tEXON_END"]
    for value in request["variants"]:
        ann = value["selectedAnnotation"]
        lines.append("\t".join([ann["geneSymbol"], ann["contig"], ann["strand"], str(ann["start"] - 1), str(ann["end"]),
                               "".join(str(e[0] - 1) + "," for e in ann["exons"]),
                               "".join(str(e[1]) + "," for e in ann["exons"])]))
    with path.open("x", encoding="ascii", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")
    return engine.raw.sha256_file(path)


def selected_evaluator(request, annotation_sha):
    indices = {value["queryId"]: i for i, value in enumerate(request["variants"])}

    def evaluate(variant, annotator, official):
        # A per-record view preserves the official methods and shares immutable models/FASTA.
        # Overlapping genes/transcripts in the batch must not participate in this query.
        idx = indices[variant["queryId"]]
        view = copy.copy(annotator)
        for name in ("genes", "chroms", "strands", "tx_starts", "tx_ends"):
            setattr(view, name, getattr(annotator, name)[[idx]])
        for name in ("exon_starts", "exon_ends"):
            setattr(view, name, [getattr(annotator, name)[idx]])
        expected = variant["selectedAnnotation"]
        actual = {"geneSymbol": str(view.genes[0]), "contig": str(view.chroms[0]), "strand": str(view.strands[0]),
                  "start": int(view.tx_starts[0]), "end": int(view.tx_ends[0]),
                  "exons": [[int(s), int(e)] for s, e in zip(view.exon_starts[0], view.exon_ends[0])]}
        require(all(actual[k] == expected[k] for k in actual), "NATIVE_SELECTED_ANNOTATION_PARSE_MISMATCH")
        result = witnessed_record(variant, view, official)
        result.update(selectedAnnotation=copy.deepcopy(expected), annotationFileSha256=annotation_sha)
        return result
    return evaluate


def witnessed_record(variant, annotator, official):
    result = engine.evaluate_record(variant, annotator, official)
    if result["status"] != "HIT":
        return result
    allele = variant["allele"]
    _, _, indices = annotator.get_name_and_strand(allele["contig"], allele["position"])
    rows = []
    for idx in indices:
        rows.append({"geneSymbol": str(annotator.genes[idx]), "contig": str(annotator.chroms[idx]),
                     "strand": str(annotator.strands[idx]), "start": int(annotator.tx_starts[idx]),
                     "end": int(annotator.tx_ends[idx]),
                     "exons": [[int(s), int(e)] for s, e in zip(annotator.exon_starts[idx], annotator.exon_ends[idx])]})
    result["annotationRows"] = rows
    chrom = official.normalise_chrom(allele["contig"], list(annotator.ref_fasta.keys())[0])
    start = allele["position"] - 9999
    sequence = annotator.ref_fasta[chrom][start - 1:start - 1 + 19999].seq.upper()
    result["referenceWindow"] = {"start": start, "length": len(sequence),
                                 "sha256": hashlib.sha256(sequence.encode("ascii")).hexdigest()}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-fasta", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch-request", type=Path)
    parser.add_argument("--binding", type=Path)
    parser.add_argument("--readiness-only", action="store_true")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output exists")
    current = identity(args.reference_fasta)
    if args.readiness_only:
        engine.write_new_json(args.output, current)
        return 0
    if args.binding is None or args.batch_request is None:
        parser.error("binding and batch request required")
    if args.binding.stat().st_size > 65536 or engine.raw.load_json_object(args.binding) != current:
        raise engine.raw.ProbeError("NATIVE_RUNTIME_BINDING_MISMATCH")
    require(args.batch_request.stat().st_size <= 1024 * 1024, "NATIVE_REQUEST_TOO_LARGE")
    request = validate_selected_request(engine.raw.load_json_object(args.batch_request))
    annotation_file = args.output.with_name("selected-annotations.tsv")
    annotation_sha = write_selected_annotations(request, annotation_file)
    result = engine.execute(request, args.reference_fasta, selected_evaluator(request, annotation_sha), annotation_file)
    require(engine.raw.sha256_file(annotation_file) == annotation_sha, "NATIVE_SELECTED_ANNOTATION_CHANGED")
    result["annotationFileSha256"] = annotation_sha
    if json.loads(json.dumps(result["identity"])) != current["engine"] or identity(args.reference_fasta) != current:
        result.update(status="FAILED", reasonCode="NATIVE_RUNTIME_CHANGED")
        engine.write_new_json(args.output, result)
        raise engine.raw.ProbeError("NATIVE_RUNTIME_CHANGED")
    result.update(resultSchemaVersion=SCHEMA, scope="RAW_FACTS_REQUIRE_JAVA_ASSOCIATION",
                  identity=current, request=request)
    engine.write_new_json(args.output, result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
