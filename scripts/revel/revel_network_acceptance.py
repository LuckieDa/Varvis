"""Three fixed public HNF1A missense controls through the production loopback HTTP/H2 harness.

The existing JOINT launcher owns the private instance. This client neither launches
nor stops a service. Prepare, evaluate, and reuse are separate, recorded stages;
reuse requires that the caller has restarted that same task-owned JVM.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.ps1_pm5.network_acceptance import Client, joint_declaration, multipart, patient_context_id, save

PROFILE = "acmg-explicit-business-joint-v4"
CRITERIA = {"ACMG_AMP_2015:" + code for code in ("PP3", "BP4", "BA1", "BP7", "PM2", "PS1", "PM5", "PVS1")}
CONTROLS = {
    ("12", 120993601, "G", "A"): {"score": "0.958", "PP3": "MET", "BP4": "NOT_MET"},
    ("12", 120999498, "A", "G"): {"score": "0.145", "PP3": "NOT_MET", "BP4": "MET"},
    ("12", 120999580, "G", "A"): {"score": "0.213", "PP3": "NOT_MET", "BP4": "NOT_MET"},
}
CONTEXT = [{"geneSymbol": "HNF1A", "hgncId": "HGNC:11621", "mondoId": "MONDO:0015967", "moi": "AUTOSOMAL_DOMINANT"}]


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def allele_key(allele):
    if allele.get("genomeAssembly", allele.get("assembly")) != "GRCh38":
        raise ValueError("Only native GRCh38 controls are permitted")
    return (allele.get("chromosome", allele.get("contig")), allele["position"], allele["reference"], allele["alternate"])


def validate_public_vcf(raw):
    if len(raw) > 1024 * 1024 or not raw.startswith(b"##fileformat=VCF"):
        raise ValueError("Only the small fixed public control VCF is permitted")
    lines = raw.decode("utf-8").splitlines()
    header = [line.split("\t") for line in lines if line.startswith("#CHROM\t")]
    if len(header) != 1 or header[0][9:] != ["synthetic-proband"]:
        raise ValueError("Only the explicitly synthetic sample is permitted")
    observed = []
    for line in lines:
        if line.startswith("#") or not line:
            continue
        fields = line.split("\t")
        if len(fields) != 10:
            raise ValueError("Expected exactly one synthetic sample")
        observed.append((fields[0].removeprefix("chr"), int(fields[1]), fields[3], fields[4]))
        genotype = dict(zip(fields[8].split(":"), fields[9].split(":"), strict=True))
        if genotype.get("GT") != "0/1":
            raise ValueError("The fixed engineering genotype is heterozygous")
    if observed != list(CONTROLS):
        raise ValueError("The three public inputs and their order were fixed before formal analysis")


def verify_instance(current, acceptance_root, instance_id, head, port):
    expected = {"instanceId": instance_id, "sourceHead": head, "branch": "main", "profileId": PROFILE,
                "port": port, "mode": "JOINT", "transport": "REAL_NETWORK_HTTP", "database": "PRIVATE_H2"}
    if any(current.get(key) != value for key, value in expected.items()):
        raise ValueError("Task-owned instance/source/profile identity mismatch")
    if Path(current.get("instanceRoot", "")).resolve() != acceptance_root.resolve() / "network" / instance_id:
        raise ValueError("Task-owned output root mismatch")
    profile = current.get("executionProfile", {})
    rules = profile.get("enabledCriteria", [])
    if (profile.get("profileId") != PROFILE or len(rules) != len(CRITERIA) or set(rules) != CRITERIA
            or profile.get("automaticTrigger") is not False or profile.get("classificationEnabled") is not False):
        raise ValueError("The exact eight-rule successor profile with explicit execution is required")


def explicit_declaration(options):
    if set(options.get("enabledCriteria", [])) != CRITERIA:
        raise ValueError("All eight rules must remain available")
    rows = options["candidates"]
    if len({row["candidateId"] for row in rows}) != len(rows):
        raise ValueError("Formal candidate identities must be unique")
    expected_ad = {row["candidateId"]: allele_key(row["allele"]) for row in rows
                   if row.get("candidateIdentity", {}).get("modeOfInheritance") == "AD"}
    if len(expected_ad) != len(CONTROLS) or set(expected_ad.values()) != set(CONTROLS):
        raise ValueError("Each fixed public allele must reach the original formal AD candidates")
    declaration = joint_declaration(options, "synthetic-proband", 10, patient_context_id(options["analysisId"]),
                                    confirmed_contexts=CONTEXT)
    selected = {item["hypothesis"]["candidateId"] for item in declaration["hypotheses"]}
    if selected != set(expected_ad):
        raise ValueError("Each formal HNF1A AD control needs an explicitly confirmable transcript")
    for item in declaration["hypotheses"]:
        transcript = item["transcript"]
        if transcript["accession"] != "ENST00000257555" or transcript["version"] != 11:
            raise ValueError("The exact reviewed HNF1A transcript is required")
    return declaration


def require_revel_controls(saved, options):
    """Threshold assertions; the separate verifier audits original source and scientific envelopes."""
    formal = {row["candidateId"]: row for row in options["candidates"]}
    if saved.get("candidateIds") != [row["candidateId"] for row in options["candidates"]]:
        raise AssertionError("Formal candidate order changed")
    observed = []
    for candidate in saved["candidates"]:
        row = formal[candidate["candidateId"]]
        key = allele_key(row["allele"])
        for attempt in candidate.get("attempts", []):
            tx = attempt.get("transcriptInput") or {}
            if tx.get("predictionPath") != "HNF1A_REVEL":
                continue
            if row.get("candidateIdentity", {}).get("modeOfInheritance") != "AD" or key not in CONTROLS:
                raise AssertionError("Unexpected REVEL candidate lineage")
            expected = CONTROLS[key]
            if tx.get("score") != expected["score"]:
                raise AssertionError("The actual precomputed REVEL score differs from the fixed control")
            evaluations = attempt.get("criterionEvaluations", [])
            if len(evaluations) != 2 or {e["criterionId"] for e in evaluations} != {"ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4"}:
                raise AssertionError("One exact PP3/BP4 pair is required; no extra prediction count")
            for evaluation in evaluations:
                criterion = evaluation["criterionId"].split(":")[-1]
                status = evaluation.get("evaluationResult", {}).get("status")
                if status != expected[criterion] or status == "MET" and evaluation.get("appliedStrength") != "SUPPORTING":
                    raise AssertionError("Scoped REVEL threshold result differs")
            if tx.get("bp7DependencyEligible") is not False or attempt.get("bp7", {}).get("evaluation", {}).get("evaluationResult", {}).get("status") == "MET":
                raise AssertionError("Protein prediction cannot satisfy BP7")
            observed.append({"candidateId": candidate["candidateId"], "allele": list(key), **expected})
    if len(observed) != len(CONTROLS) or {tuple(x["allele"]) for x in observed} != set(CONTROLS):
        raise AssertionError("The three fixed REVEL control branches were not all evaluated")
    return observed


def raw_response(client):
    event = client.events[-1]
    path = Path(event["eventFile"].removesuffix(".event.json") + ".response-body")
    raw = path.read_bytes()
    if sha(raw) != event["responseSha256"]:
        raise AssertionError("Recorded HTTP response bytes changed")
    return raw


def measured(client, label, method, path, body=None):
    before = client.call(label + "-calls-before", "GET", "/__varvis_ps1pm5_calls")
    value = client.call(label, method, path, body)
    raw = raw_response(client)
    event = dict(client.events[-1])
    after = client.call(label + "-calls-after", "GET", "/__varvis_ps1pm5_calls")
    event.update(runId=value["runId"], callIncrement={key: after[key] - before[key] for key in after})
    (client.output / (label + "-response.json")).write_bytes(raw)
    save(client.output / (label + "-event.json"), event)
    return value, raw, event


def verify_reuse(saved_raw, read_raw, repeated_raw, read_event, repeated_event):
    if saved_raw != read_raw or saved_raw != repeated_raw:
        raise AssertionError("Complete HTTP response bytes changed on saved GET or repeat POST")
    saved = json.loads(saved_raw)
    for event in (read_event, repeated_event):
        if event["runId"] != saved["runId"] or any(event["callIncrement"].values()):
            raise AssertionError("Saved run identity changed or reuse invoked a runtime/provider/evaluator")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("prepare", "evaluate", "reuse"))
    parser.add_argument("--acceptance-root", type=Path, required=True)
    parser.add_argument("--instance-id", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--vcf", type=Path)
    parser.add_argument("--attempt", default="hnf1a-revel", help="Fresh retained client subdirectory for this attempt")
    parser.add_argument("--resume-analysis-state", type=Path, help="Prepare from the same private READY analysis without invoking Exomiser or VEP again")
    parser.add_argument("--confirm-prepared", action="store_true")
    parser.add_argument("--timeout", type=int, default=3600)
    args = parser.parse_args()
    uuid.UUID(args.instance_id.removeprefix("instance-"))
    if not re.fullmatch(r"hnf1a-revel(?:-[a-z0-9-]+)?", args.attempt):
        raise ValueError("A simple task-specific client subdirectory is required")
    instance_root = args.acceptance_root.resolve() / "network" / args.instance_id
    output = instance_root / args.attempt
    if args.stage == "prepare":
        output.mkdir(exist_ok=False)
    elif not output.is_dir():
        raise ValueError("Prepare this exact private instance first")
    report_path = output / (args.stage + "-report.json")
    if report_path.exists():
        raise ValueError("This stage has already been recorded; retain its original result and failure evidence")
    client = Client(f"http://127.0.0.1:{args.port}", output, args.timeout, joint=True, expected_port=args.port)
    current = client.call(args.stage + "-instance", "GET", "/__varvis_ps1pm5_instance")
    verify_instance(current, args.acceptance_root, args.instance_id, args.head, args.port)
    report = {"status": "BLOCKED", "transport": "REAL_NETWORK_HTTP", "pageCovered": False,
              "dataClass": "SYNTHETIC", "stage": args.stage, "instance": current}
    before = client.call(args.stage + "-calls-before", "GET", "/__varvis_ps1pm5_calls")
    state_path = output / "network-state.json"
    try:
        if args.stage == "prepare":
            if args.vcf is None and args.resume_analysis_state is None:
                raise ValueError("Prepare requires the fixed public VCF")
            if args.resume_analysis_state is not None:
                source_state_path = args.resume_analysis_state.resolve()
                if args.vcf is not None or not source_state_path.is_relative_to(instance_root):
                    raise ValueError("Resume only a previously recorded analysis within this same private instance")
                source_state = json.loads(source_state_path.read_bytes())
                if any(source_state[key] != current[key] for key in ("instanceId", "sourceHead", "configurationSha256", "executionProfile")):
                    raise ValueError("Resume analysis belongs to a different source/configuration/instance")
                vcf = (source_state_path.parent / "fixed-public.vcf").read_bytes()
                if sha(vcf) != source_state["vcfSha256"]:
                    raise ValueError("The previously uploaded public VCF changed")
            else:
                vcf = args.vcf.read_bytes()
            validate_public_vcf(vcf)
            (output / "fixed-public.vcf").write_bytes(vcf)
            if args.resume_analysis_state is not None:
                analysis_id = str(uuid.UUID(source_state["analysisId"]))
                uploaded = client.call("resume-analysis-get", "GET", "/variant-analyses/" + analysis_id)
                if uploaded.get("id") != analysis_id or uploaded.get("status") != "READY" or uploaded.get("fileSha256") != sha(vcf):
                    raise ValueError("The same original READY analysis and VCF identity are required")
                report.update(analysisReused=True, uploadPerformed=False, resumedStateSha256=sha(source_state_path.read_bytes()))
            else:
                readiness = client.call("preflight", "GET", "/variant-analyses/preflight?genomeAssembly=GRCh38")
                if not readiness.get("applicationReady") or readiness.get("blockingErrors"):
                    raise RuntimeError("Original readiness did not pass")
                body, kind = multipart(vcf, "synthetic-proband", "STANDARD")
                uploaded = client.call("upload", "POST", "/variant-analyses", body, kind)
            state = {"instanceId": args.instance_id, "sourceHead": args.head, "analysisId": uploaded["id"],
                     "configurationSha256": current["configurationSha256"], "executionProfile": current["executionProfile"],
                     "processIdBeforeRestart": current["processId"], "vcfSha256": sha(vcf), "clientSha256": sha(Path(__file__).read_bytes())}
            save(state_path, state)
            analysis = uploaded["id"]
            deadline = time.monotonic() + args.timeout
            while uploaded["status"] != "READY":
                if uploaded["status"] in {"FAILED", "CANCELLED"} or time.monotonic() >= deadline:
                    raise RuntimeError("Original analysis failed or timed out: " + json.dumps(uploaded))
                time.sleep(2)
                uploaded = client.call("analysis-poll", "GET", "/variant-analyses/" + analysis)
            save(output / "formal-analysis.json", uploaded)
            options = client.call("input-options", "GET", f"/variant-analyses/{analysis}/acmg/input-options?topN=10")
            (output / "input-options.json").write_bytes(raw_response(client))
            formal_raw = []
            for row in options["candidates"]:
                client.call("formal-candidate-" + row["candidateId"], "GET", f"/variant-analyses/{analysis}/candidates/{row['candidateId']}")
                formal_raw.append(raw_response(client))
            (output / "formal-topn.json").write_bytes(b"[\n" + b",\n".join(formal_raw) + b"\n]\n")
            declaration = explicit_declaration(options)
            save(output / "prepared-declaration.json", declaration)
            state["preparedSha256"] = sha((output / "prepared-declaration.json").read_bytes())
            save(state_path, state)
            report.update(status="FORMAL_CONTROLS_PREPARED", analysisId=analysis,
                          candidateIds=options["candidateIds"], explicitHypotheses=len(declaration["hypotheses"]))
        else:
            state = json.loads(state_path.read_bytes())
            if (any(state[key] != current[key] for key in ("instanceId", "sourceHead", "configurationSha256", "executionProfile"))
                    or state["clientSha256"] != sha(Path(__file__).read_bytes())):
                raise ValueError("Saved source/configuration/client identity changed")
            prepared = (output / "prepared-declaration.json").read_bytes()
            if sha(prepared) != state["preparedSha256"]:
                raise ValueError("The previously reviewed declaration changed")
            analysis = state["analysisId"]
            run_path = f"/variant-analyses/{analysis}/acmg/runs"
            options = json.loads((output / "input-options.json").read_bytes())
            if args.stage == "evaluate":
                if not args.confirm_prepared or (output / "saved-business.json").exists():
                    raise ValueError("Use --confirm-prepared for a fresh explicitly authorized evaluation")
                saved, saved_raw, event = measured(client, "first-post", "POST", run_path, prepared)
                (output / "saved-business.json").write_bytes(saved_raw)
                state["runId"] = saved["runId"]; save(state_path, state)
                report["firstPostCallIncrement"] = event["callIncrement"]
            else:
                if state["processIdBeforeRestart"] == current["processId"]:
                    raise ValueError("The reuse stage requires the same task instance in a separately restarted JVM")
                saved_raw = (output / "saved-business.json").read_bytes(); saved = json.loads(saved_raw)
            prefix = "" if args.stage == "evaluate" else "rebuilt-"
            _, read_raw, read_event = measured(client, prefix + "saved-get", "GET", run_path + "/" + saved["runId"])
            _, repeat_raw, repeat_event = measured(client, prefix + "repeat-post", "POST", run_path, prepared)
            verify_reuse(saved_raw, read_raw, repeat_raw, read_event, repeat_event)
            report.update(status="REVEL_PUBLIC_CONTROLS_AND_SAVE_REUSE_VERIFIED", analysisId=analysis, runId=saved["runId"],
                          responseSha256=sha(saved_raw), savedGetIdentical=True, repeatPostIdentical=True,
                          savedGetCallIncrement=read_event["callIncrement"], repeatPostCallIncrement=repeat_event["callIncrement"],
                          jvmRestartCovered=args.stage == "reuse")
            report["revelControls"] = require_revel_controls(saved, options)
    except Exception as failure:
        report["status"] = "BLOCKED"; report["failure"] = repr(failure); raise
    finally:
        try:
            after = client.call(args.stage + "-calls-after", "GET", "/__varvis_ps1pm5_calls")
            report["callIncrement"] = {key: after[key] - before[key] for key in after}
        except Exception as failure:
            report["finalCounterObservationFailure"] = repr(failure)
        save(report_path, report)
        print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
