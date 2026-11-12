"""Explicit, bounded public compatibility preparation; never evaluates AM scores.

Panel probing stops at the first exact versioned-transcript/amino-acid identity
intersection and leaves the choice to the caller. Fixture preparation preserves
the caller's already-fixed transcript policy. Neither mode invokes a provider or
evaluator, downloads data, or hashes a complete reference/resource again.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.e4_a2b.prepare_local_smoke_inputs import now, ref_observation, reuse_or_checksum, sha


IDENTITY_COLUMNS = ("chromosome", "position", "reference", "alternate", "genome",
                    "uniprotId", "transcriptId", "proteinVariant")
POLICY = "EXPLICIT_PUBLIC_VEP_AM_IDENTITY_COMPATIBILITY_V1"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8", newline="\n")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def scrub(value, resource_root, target_root):
    text = str(value)
    for root, label in ((target_root, "TARGET_ROOT"), (resource_root, "RESOURCE_ROOT")):
        for spelling in (str(root), root.as_posix(), str(root).replace("/", "\\")):
            text = text.replace(spelling, label)
    # Diagnostics may mention Perl/user temporary directories outside either root.
    text = re.sub(r'''(["'])(?:[A-Za-z]:[\\/]|\\\\|/)[^"'\r\n]*\1''',
                  '"[LOCAL_PATH]"', text)
    text = re.sub(r'''(?i:file:/{2,3})[^\s"'<>]*''', "[LOCAL_PATH]", text)
    text = re.sub(r'''(?<![A-Za-z0-9:/])(?:[A-Za-z]:[\\/]|\\\\|/(?!/))[^\s"'<>]*''',
                  "[LOCAL_PATH]", text)
    return text


def validate_choices(choices, fixtures):
    require(isinstance(choices, list) and 1 <= len(choices) <= (2 if fixtures else 3),
            "BOUNDED_CHOICE_COUNT_REQUIRED")
    require(len({c["symbol"] for c in choices}) == len(choices), "ONE_SNV_PER_GENE_REQUIRED")
    for choice in choices:
        require(choice["assembly"] == "GRCh38", "NATIVE_GRCH38_REQUIRED")
        require(isinstance(choice["position"], int) and choice["position"] > 0,
                "POSITIVE_POSITION_REQUIRED")
        require(choice["reference"] in "ACGT" and len(choice["reference"]) == 1
                and choice["alternate"] in "ACGT" and len(choice["alternate"]) == 1
                and choice["reference"] != choice["alternate"], "SNV_REQUIRED")
        if fixtures:
            require(re.fullmatch(r"ENST[0-9]+\.[1-9][0-9]*", choice["transcript"]),
                    "VERSIONED_EXPLICIT_TRANSCRIPT_REQUIRED")
            require(choice["selectionPolicy"] == POLICY, "EXPLICIT_SELECTION_POLICY_REQUIRED")
            for key in ("selectionBasis", "selectionSourceLocator", "selectionSourceSha256",
                        "identityReceiptLocator", "expectedHgvsp", "expectedProteinIdentity",
                        "expectedAminoAcidChange"):
                require(isinstance(choice.get(key), str) and choice[key], "FIXTURE_SOURCE_REQUIRED:" + key)


def amino_acid_change(annotation):
    amino = annotation.get("amino_acids", "")
    position = annotation.get("protein_start")
    if (not re.fullmatch(r"[ACDEFGHIKLMNPQRSTVWY]/[ACDEFGHIKLMNPQRSTVWY]", amino)
            or not isinstance(position, int) or position < 1
            or annotation.get("protein_end", position) != position
            or "missense_variant" not in annotation.get("consequence_terms", [])):
        return None
    return amino[0] + str(position) + amino[2]


def project_am_identity(raw):
    """Retain only columns 1--8; never interpret or retain the remaining fields."""
    projected = []
    for line in raw.splitlines():
        if not line or line.startswith(b"#"):
            continue
        columns = line.split(b"\t", 8)[:8]
        require(len(columns) == 8, "AM_IDENTITY_COLUMN_COUNT")
        projected.append(dict(zip(IDENTITY_COLUMNS, (v.decode("ascii") for v in columns))))
    return projected


def fixed_vep_record(choice, records):
    source = [r for r in records if r.get("id") == choice["caseId"]]
    require(len(source) == 1, "VEP_EXACT_INPUT_RECORD_REQUIRED")
    record = source[0]
    expected = [choice["contig"], str(choice["position"]), choice["caseId"],
                choice["reference"], choice["alternate"], ".", "PASS", "."]
    require(record.get("input", "").split("\t") == expected, "VEP_ORIGINAL_INPUT_MISMATCH")
    require(record.get("assembly_name") == choice["assembly"]
            and record.get("seq_region_name") == choice["contig"]
            and record.get("start") == choice["position"]
            and record.get("end") == choice["position"]
            and record.get("allele_string") == choice["reference"] + "/" + choice["alternate"],
            "VEP_RECORD_ALLELE_MISMATCH")
    return record


def check_annotation_allele(choice, annotation):
    require(annotation.get("variant_allele") == choice["alternate"], "VEP_ANNOTATION_ALLELE_MISMATCH")
    if "allele_num" in annotation:
        require(type(annotation["allele_num"]) is int and annotation["allele_num"] == 1,
                "VEP_ANNOTATION_ALLELE_NUMBER_MISMATCH")


def exact_intersections(choice, records, identities):
    source = fixed_vep_record(choice, records)
    matches = []
    for annotation in source.get("transcript_consequences", []):
        transcript = annotation.get("transcript_id", "")
        change = amino_acid_change(annotation)
        if (not re.fullmatch(r"ENST[0-9]+\.[1-9][0-9]*", transcript) or not change
                or annotation.get("gene_symbol") != choice["symbol"]):
            continue
        check_annotation_allele(choice, annotation)
        for identity in identities:
            if (identity["chromosome"].removeprefix("chr") == choice["contig"]
                    and identity["position"] == str(choice["position"])
                    and identity["reference"] == choice["reference"]
                    and identity["alternate"] == choice["alternate"]
                    and identity["genome"] == "hg38"
                    and identity["transcriptId"] == transcript
                    and identity["proteinVariant"] == change):
                matches.append({"transcript": transcript, "aminoAcidChange": change,
                                "hgvsp": annotation.get("hgvsp"),
                                "proteinIdentity": annotation.get("hgvsp", "").split(":", 1)[0],
                                "alphaMissenseIdentity": identity,
                                "selectedVepAnnotation": annotation})
    return matches


def checked_selected_annotations(choices, records):
    selected = []
    for choice in choices:
        source = fixed_vep_record(choice, records)
        annotations = [a for a in source.get("transcript_consequences", [])
                       if a.get("transcript_id") == choice["transcript"]]
        require(len(annotations) == 1, "EXACT_SELECTED_TRANSCRIPT_REQUIRED")
        annotation = annotations[0]
        check_annotation_allele(choice, annotation)
        require(annotation.get("gene_symbol") == choice["symbol"], "SELECTED_GENE_MISMATCH")
        require(annotation.get("hgvsp") == choice["expectedHgvsp"], "SELECTED_HGVSP_MISMATCH")
        # VEP --transcript_version versions transcript/HGVSp; protein_id may be unversioned.
        protein = annotation.get("hgvsp", "").split(":", 1)[0]
        require(protein == choice["expectedProteinIdentity"], "SELECTED_PROTEIN_MISMATCH")
        require(amino_acid_change(annotation) == choice["expectedAminoAcidChange"],
                "SELECTED_AMINO_ACID_MISMATCH")
        selected.append({"caseId": choice["caseId"], "recordCount": 1,
                         "selectedTranscriptCount": 1, "selectedAnnotations": annotations})
    return selected


def run_vep(args, report, attempt, choices, work):
    tool = args.resource_root / "Tool"
    fasta = tool / "reference/grch38.p14.primary-chr.fa"
    vep_fasta = tool / "vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa"
    wrapper = tool / "vep/bin/varvis-vep.cmd"
    # This helper refuses a receipt mismatch instead of falling back to a full read.
    attempt["referenceManifest"] = reuse_or_checksum(fasta, [], args.reference_receipt_from)
    attempt["referenceObservations"] = [ref_observation(fasta, c) for c in choices]
    attempt["vepReferenceObservations"] = [ref_observation(vep_fasta, c) for c in choices]
    require(all(r["status"] == "MATCH" for r in attempt["referenceObservations"]
                + attempt["vepReferenceObservations"]), "REFERENCE_MISMATCH")
    catalog_path = args.binding_resource_dir / "clingen-catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    attempt["diseaseCatalogRecords"] = [r for c in choices for r in catalog
        if r["hgncId"] == c["hgncId"] and r["mondoId"] == c["syntheticHypothesisMondoId"]]
    attempt["diseaseCatalogSha256"] = sha(catalog_path)
    attempt["diseaseCatalogManifestSha256"] = sha(args.binding_resource_dir / "manifest.json")
    input_vcf, output_json = work / "fixed-public-inputs.vcf", work / "real-vep-output.jsonl"
    lines = ["##fileformat=VCFv4.2", "##reference=GRCh38", "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO"]
    lines += ["\t".join([c["contig"], str(c["position"]), c["caseId"], c["reference"],
                         c["alternate"], ".", "PASS", "."]) for c in choices]
    input_vcf.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    command = ["cmd.exe", "/d", "/c", str(wrapper), "--offline", "--cache", "--assembly", "GRCh38",
               "--species", "homo_sapiens", "--cache_version", "116", "--dir_cache", (tool / "vep/cache").as_posix(),
               "--fasta", vep_fasta.as_posix(), "--format", "vcf", "--json", "--input_file", input_vcf.as_posix(),
               "--output_file", output_json.as_posix(), "--no_stats", "--symbol", "--canonical", "--mane",
               "--hgvs", "--protein", "--biotype", "--numbers", "--transcript_version", "--uniprot"]
    vep = attempt["vep"] = {"toolVersion": "116.0", "cacheVersion": "116_GRCh38", "genebuild": "GENCODE 50",
        "wrapperSha256": sha(wrapper), "cacheInfoSha256": sha(tool / "vep/cache/homo_sapiens/116_GRCh38/info.txt"),
        "inputVcfSha256": sha(input_vcf), "inputVcf": input_vcf.relative_to(args.output.parent).as_posix(),
        "outputJson": output_json.relative_to(args.output.parent).as_posix(),
        "referenceLogicalName": "Tool/vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa",
        "referenceIndexSha256": sha(Path(str(vep_fasta) + ".fai")), "timeoutSeconds": 480,
        "options": [scrub(c, args.resource_root, args.output.parent) for c in command[4:]], "startedAt": now()}
    write(args.output, report)
    started = time.monotonic()
    try:
        result = subprocess.run(command, cwd=work, capture_output=True, timeout=480)
        stdout, stderr = result.stdout, result.stderr
        vep["exitCode"] = result.returncode
    except subprocess.TimeoutExpired as error:
        stdout, stderr = error.stdout or b"", error.stderr or b""
        vep["failureReason"] = "VEP_TIMEOUT"
    vep.update({"elapsedSeconds": round(time.monotonic() - started, 3), "completedAt": now(),
                "stdoutBytes": len(stdout), "stderrBytes": len(stderr),
                "rawStdoutSha256": digest(stdout), "rawStderrSha256": digest(stderr)})
    for name, raw in (("stdout", stdout), ("stderr", stderr)):
        path = work / ("vep." + name + ".txt")
        path.write_text(scrub(raw.decode("utf-8", errors="replace"), args.resource_root, args.output.parent),
                        encoding="utf-8", newline="\n")
        vep[name + "SanitizedSha256"] = sha(path)
    records = []
    if output_json.exists():
        vep["outputSha256"] = sha(output_json)
        records = [json.loads(line) for line in output_json.read_text(encoding="utf-8").splitlines() if line.strip()]
        vep["outputRecordCount"] = len(records)
    write(args.output, report)
    require(vep.get("exitCode") == 0, vep.get("failureReason", "VEP_EXECUTION_FAILED"))
    return records


def query_am_identity(args, report, attempt, choice, work):
    tool = args.resource_root / "Tool"
    region = "chr" + choice["contig"] + ":" + str(choice["position"]) + "-" + str(choice["position"])
    query = attempt["alphaMissenseIdentityQuery"] = {"region": region, "startedAt": now(),
        "resourceLogicalName": "Tool/m3/alphamissense_hg38.tabix.tsv.gz", "timeoutSeconds": 30,
        "columnsRetained": list(IDENTITY_COLUMNS), "scoreFieldsParsed": False,
        "classificationFieldsParsed": False, "providerCalls": 0, "evaluatorCalls": 0}
    write(args.output, report)
    stdout, stderr = b"", b""
    try:
        result = subprocess.run([str(tool / "msys64/ucrt64/bin/tabix.exe"),
            str(tool / "m3/alphamissense_hg38.tabix.tsv.gz"), region], capture_output=True, timeout=30)
        stdout, stderr = result.stdout, result.stderr
        query["exitCode"] = result.returncode
    except subprocess.TimeoutExpired as error:
        stdout, stderr = error.stdout or b"", error.stderr or b""
        query["failureReason"] = "AM_IDENTITY_QUERY_TIMEOUT"
    query.update({"completedAt": now(), "rawStdoutSha256": digest(stdout),
                  "rawStderrSha256": digest(stderr), "rawStdoutRetained": False,
                  "stderr": scrub(stderr.decode("utf-8", errors="replace"), args.resource_root, args.output.parent)})
    identities = project_am_identity(stdout)
    projected_path = work / "alphamissense-identity-columns.json"
    write(projected_path, identities)
    query.update({"identityProjectionSha256": sha(projected_path), "returnedIdentityRowCount": len(identities),
                  "identityProjection": projected_path.relative_to(args.output.parent).as_posix()})
    write(args.output, report)
    require(query.get("exitCode") == 0, query.get("failureReason", "AM_IDENTITY_QUERY_FAILED"))
    return identities


def execute(args, source):
    fixture_mode = args.fixtures is not None
    choices = source["fixedChoices" if fixture_mode else "candidates"]
    validate_choices(choices, fixture_mode)
    if not fixture_mode:
        require([c["symbol"] for c in choices] == ["GJB2", "BRCA1", "HFE"], "REGISTERED_PANEL_ORDER_REQUIRED")
        require([c["queryOrder"] for c in choices] == [1, 2, 3], "REGISTERED_PANEL_ORDER_REQUIRED")
    source_path = args.fixtures if fixture_mode else args.panel
    report = {"schemaVersion": "e4-a2b-public-input-preparation-v1" if fixture_mode else "e4-a2b-bounded-compatibility-probe-v1",
        "choicesFixedAt": source.get("choicesFixedAt", source.get("registeredAt")),
        "resourceDataClass": "PUBLIC", "patientContextDataClass": "SYNTHETIC", "clinicalValidation": False,
        "alphaMissenseReadByThisScript": not fixture_mode, "scoreFieldsParsed": False,
        "providerCalls": 0, "evaluatorCalls": 0, "fixedChoices": choices, "attempts": [],
        "sourceArtifact": source_path.name, "sourceArtifactSha256": sha(source_path),
        "selectionPolicy": source.get("selectionPolicy"), "status": "RUNNING"}
    write(args.output, report)
    groups = [choices] if fixture_mode else [[choice] for choice in choices]
    for number, group in enumerate(groups, 1):
        work = args.output.parent / (args.output.stem + "-attempt-" + str(number))
        work.mkdir(exist_ok=False)
        attempt = {"attemptNumber": number, "startedAt": now(), "status": "RUNNING",
                   "caseIds": [c["caseId"] for c in group]}
        report["attempts"].append(attempt)
        write(args.output, report)
        print(json.dumps({"event": "STARTED", "attempt": number, "genes": [c["symbol"] for c in group]}), flush=True)
        try:
            records = run_vep(args, report, attempt, group, work)
            if fixture_mode:
                attempt["selectedVepAnnotations"] = checked_selected_annotations(group, records)
                attempt["status"] = "PREPARED"
            else:
                identities = query_am_identity(args, report, attempt, group[0], work)
                attempt["identityIntersections"] = exact_intersections(group[0], records, identities)
                attempt["status"] = "AWAITING_EXPLICIT_TRANSCRIPT_CHOICE" if attempt["identityIntersections"] else "NO_IDENTITY_INTERSECTION"
        except Exception as error:
            attempt.update({"status": "FAILED", "errorType": type(error).__name__,
                            "error": scrub(error, args.resource_root, args.output.parent)})
        attempt["completedAt"] = now()
        write(args.output, report)
        print(json.dumps({"event": "COMPLETED", "attempt": number, "status": attempt["status"],
                          "identityIntersectionCount": len(attempt.get("identityIntersections", []))}), flush=True)
        if attempt["status"] == "AWAITING_EXPLICIT_TRANSCRIPT_CHOICE":
            report["status"] = attempt["status"]
            report["unqueriedRegisteredGenes"] = [c["symbol"] for c in choices[number:]]
            break
    else:
        report["status"] = report["attempts"][-1]["status"] if fixture_mode else "PANEL_EXHAUSTED_RESOURCE_BLOCKED"
    report["completedAt"] = now()
    write(args.output, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--panel", type=Path)
    modes.add_argument("--fixtures", type=Path)
    parser.add_argument("--resource-root", type=Path, required=True)
    parser.add_argument("--binding-resource-dir", type=Path, required=True)
    parser.add_argument("--reference-receipt-from", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for key in ("resource_root", "binding_resource_dir", "reference_receipt_from", "output"):
        setattr(args, key, getattr(args, key).resolve())
    require(args.output.parent == (REPO_ROOT / "varvis-server/target").resolve()
            and args.output.name.startswith("e4-a2b-acceptance-"), "NEW_ACCEPTANCE_TARGET_REQUIRED")
    require(not args.output.exists(), "OUTPUT_ALREADY_EXISTS")
    source = json.loads((args.fixtures or args.panel).read_text(encoding="utf-8"))
    result = execute(args, source)
    print(json.dumps({"status": result["status"], "output": args.output.name,
                      "outputSha256": sha(args.output)}), flush=True)
    return 0 if result["status"] in ("PREPARED", "AWAITING_EXPLICIT_TRANSCRIPT_CHOICE") else 1


if __name__ == "__main__":
    raise SystemExit(main())
