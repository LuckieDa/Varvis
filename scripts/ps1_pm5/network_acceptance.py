"""Real loopback HTTP acceptance client. Public synthetic VCF only; no candidate injection.

Stages: prepare uploads and obtains official recommendations; evaluate explicitly confirms
the prepared source-backed declaration; reuse verifies a separately restarted JVM.
The launcher and model/resource readiness are independent prerequisites.
"""
import argparse
import hashlib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def save(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")


def declaration(options, sample, gene, disease, moi, top_n, case_id):
    """Use explicit natural disease/MOI choices to resolve server-issued opaque identities."""
    if not re.fullmatch(r"patient-context-[a-z0-9][a-z0-9._-]{7,95}", case_id):
        raise ValueError("Patient context identity must satisfy the frozen evidence-context-v2 contract")
    def source(locator):
        return {"source": "SYNTHETIC_CALLER_DECLARATION", "version": "1.0.0", "recordLocator": locator}

    request = {"topN": top_n, "declarationVersion": "ordinary-ui-v3", "sampleId": sample,
               "dataClass": "SYNTHETIC", "patient": {"patientContextId": case_id, "role": "PROBAND",
               "sex": "UNKNOWN", "source": source("public-network-control/case")}, "hypotheses": [], "recommendations": []}
    for row in options["candidates"]:
        recommendation = row["recommendation"]
        relations = [item for item in recommendation["relationships"] if item.get("selectable")
                     and item.get("hgncId") == gene and item.get("mondoId") == disease
                     and item.get("moi") in {moi, "AD" if moi == "AUTOSOMAL_DOMINANT" else moi}
                     and item.get("relationshipType") == "VCEP_SPECIFICATION_SCOPE"]
        if len(relations) != 1:
            raise ValueError("Explicit disease/MOI does not resolve exactly one selectable source-backed relationship")
        relation = relations[0]
        transcript = recommendation.get("ps1Pm5CompatibleTranscript")
        if not transcript or not transcript.get("ps1Pm5Compatible"):
            raise ValueError("No source-backed PS1/PM5 compatible transcript; do not fabricate one")
        selection = {field: recommendation[field] for field in ("recommendationId", "policyVersion", "policyHash", "generatedAt")}
        selection.update(candidateId=row["candidateId"], stableRelationshipKey=relation["stableRelationshipKey"], annotationId=transcript["annotationId"])
        request["recommendations"].append(selection)
        accession, version = transcript["versionedTranscriptId"].rsplit(".", 1)
        request["hypotheses"].append({"affectedStatus": "UNKNOWN", "hypothesis": {
            "candidateId": row["candidateId"], "hypothesisId": recommendation["hypothesisId"],
            **{field: relation[field] for field in ("hgncId", "mondoId", "moi")},
            "provenance": source("public-network-control/explicit-disease-hypothesis")}, "transcript": {
                "accession": accession, "version": int(version), "annotationSource": transcript["annotationSource"],
                "annotationSourceVersion": transcript["annotationSourceVersion"],
                "selectionPolicy": recommendation["policyId"] + "@" + recommendation["policyVersion"],
                "selectionReason": "Explicit public synthetic disease/MOI and source-backed transcript confirmation; not a clinical diagnosis",
                "source": source("recommendations/" + recommendation["recommendationId"] + "/selection")}})
    if not request["hypotheses"]:
        raise ValueError("No formal candidates; do not inject a control")
    return request


def parse_confirmed_contexts(raw):
    """Public domain choices only; never caller-supplied internal recommendation identities."""
    if len(raw) > 65536:
        raise ValueError("Confirmed contexts must be a small JSON array")
    values = json.loads(raw)
    if not isinstance(values, list) or not values:
        raise ValueError("Confirmed contexts must be a nonempty JSON array")
    genes = set()
    for row in values:
        if not isinstance(row, dict) or set(row) != {"geneSymbol", "hgncId", "mondoId", "moi"}:
            raise ValueError("Only geneSymbol, hgncId, mondoId and moi are permitted")
        if (not all(isinstance(v, str) and v for v in row.values())
                or not re.fullmatch(r"HGNC:[0-9]+", row["hgncId"])
                or not re.fullmatch(r"MONDO:[0-9]{7}", row["mondoId"])):
            raise ValueError("Confirmed context identifiers are incomplete")
        if row["geneSymbol"] in genes:
            raise ValueError("Choose one explicit disease/MOI per gene for this bounded declaration")
        genes.add(row["geneSymbol"])
    return values


def verify_confirmed_context_identity(output, state, supplied_sha=None):
    saved_sha = state.get("confirmedContextsSha256")
    if saved_sha is None:
        if supplied_sha is not None:
            raise ValueError("Cannot introduce new confirmed contexts after preparation")
        return None
    raw = (output / "confirmed-contexts.json").read_bytes()
    if hashlib.sha256(raw).hexdigest() != saved_sha or (supplied_sha is not None and supplied_sha != saved_sha):
        raise ValueError("Confirmed domain choices changed; prepare a fresh declaration")
    parse_confirmed_contexts(raw)
    return saved_sha


def normalized_moi(value):
    return {"AD": "AUTOSOMAL_DOMINANT", "AR": "AUTOSOMAL_RECESSIVE"}.get(value, value)


def confirmed_transcript(row, relation):
    """Mirror the current route recommendation, then verify the original annotation index."""
    recommendation = row["recommendation"]
    resource = recommendation.get("resourceCompatibleTranscript")
    missense = recommendation.get("ps1Pm5CompatibleTranscript") if relation.get("relationshipType") == "VCEP_SPECIFICATION_SCOPE" else None
    selected = (missense or resource) if resource and resource.get("predictionPath") == "POPULATION_ONLY" else (resource or missense)
    if not selected:
        return None
    matches = [a for a in recommendation.get("annotations", []) if a.get("annotationId") == selected.get("annotationId")
               and a.get("contextSelectable", a.get("identityCompatible", False))]
    if len(matches) != 1:
        return None
    annotation = matches[0]
    index = annotation.get("annotationIndex")
    originals = row.get("transcriptAnnotations", [])
    if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(originals):
        return None
    original = originals[index]
    choices = [choice for choice in row.get("transcriptChoices", []) if choice.get("annotationIndex") == index]
    if (len(choices) != 1 or choices[0].get("versionedTranscriptId") != annotation.get("versionedTranscriptId")
            or original.get("geneSymbol") != row.get("geneSymbol") or original.get("status") != "COMPLETED"
            or original.get("source") != annotation.get("annotationSource")
            or original.get("version") != annotation.get("annotationSourceVersion")):
        return None
    return annotation


def joint_declaration(options, sample, top_n, case_id, population_only=False, confirmed_contexts=None):
    """Confirm only unambiguous server recommendations, retaining unresolved formal candidates."""
    if not re.fullmatch(r"patient-context-[a-z0-9][a-z0-9._-]{7,95}", case_id):
        raise ValueError("Invalid explicit synthetic patient context")
    def source(locator):
        return {"source": "SYNTHETIC_CALLER_DECLARATION", "version": "1.0.0", "recordLocator": locator}
    request = {"topN": top_n, "declarationVersion": "ordinary-ui-v3", "sampleId": sample,
               "dataClass": "SYNTHETIC", "patient": {"patientContextId": case_id, "role": "PROBAND",
               "sex": "UNKNOWN", "source": source("joint-public-control/case")}, "hypotheses": [], "recommendations": []}
    for row in options["candidates"]:
        recommendation = row["recommendation"]
        if confirmed_contexts is not None:
            choices = [c for c in confirmed_contexts if c["geneSymbol"] == row.get("geneSymbol")]
            relations = [r for r in recommendation["relationships"] if r.get("selectable") and len(choices) == 1
                         and r.get("hgncId") == choices[0]["hgncId"] and r.get("mondoId") == choices[0]["mondoId"]
                         and normalized_moi(r.get("moi")) == normalized_moi(choices[0]["moi"])]
            relation = relations[0] if len(relations) == 1 else None
            transcript = confirmed_transcript(row, relation) if relation and not population_only else None
        else:
            relations = [r for r in recommendation["relationships"] if r.get("selectable")
                         and r.get("stableRelationshipKey") == recommendation.get("stableRelationshipKey")]
            relation = relations[0] if len(relations) == 1 else None
            annotations = [a for a in recommendation.get("annotations", [])
                           if a.get("annotationId") == recommendation.get("recommendedAnnotationId")
                           and a.get("contextSelectable", a.get("identityCompatible", False))]
            transcript = annotations[0] if len(annotations) == 1 and relation and not population_only else None
        selection = {field: recommendation[field] for field in ("recommendationId", "policyVersion", "policyHash", "generatedAt")}
        selection.update(candidateId=row["candidateId"], stableRelationshipKey=relation["stableRelationshipKey"] if relation else None,
                         annotationId=transcript["annotationId"] if transcript else None)
        request["recommendations"].append(selection)
        if transcript:
            accession, version = transcript["versionedTranscriptId"].rsplit(".", 1)
            request["hypotheses"].append({"affectedStatus": "UNKNOWN", "hypothesis": {
                "candidateId": row["candidateId"], "hypothesisId": recommendation["hypothesisId"],
                **{field: relation[field] for field in ("hgncId", "mondoId", "moi")},
                "provenance": source("joint-public-control/explicit-disease-hypothesis")}, "transcript": {
                    "accession": accession, "version": int(version), "annotationSource": transcript["annotationSource"],
                    "annotationSourceVersion": transcript["annotationSourceVersion"],
                    "selectionPolicy": recommendation["policyId"] + "@" + recommendation["policyVersion"],
                    "selectionReason": "Explicitly confirmed unambiguous server recommendation for public joint acceptance; not a diagnosis",
                    "source": source("recommendations/" + recommendation["recommendationId"] + "/selection")}})
    if not request["recommendations"]:
        raise ValueError("No formal candidates; do not inject controls")
    return request


def patient_context_id(analysis_id):
    return "patient-context-public-" + str(uuid.UUID(analysis_id))


def resume_analysis(client, source_state, current, script_sha, require_positive):
    """Recover only a READY analysis through its real GET endpoint; never copy candidate records."""
    raw = source_state.read_bytes()
    old = json.loads(raw)
    if any(old.get(key) != current[key] for key in ("instanceId", "sourceHead")):
        raise ValueError("Resume state belongs to a different isolated instance/source")
    analysis = str(uuid.UUID(old["analysisId"]))
    actual = client.call("resume-analysis-get", "GET", "/variant-analyses/" + analysis)
    if actual.get("id") != analysis or actual.get("status") != "READY":
        raise ValueError("Only the same actual READY analysis may be resumed")
    if not old.get("vcfSha256") or actual.get("fileSha256") != old["vcfSha256"]:
        raise ValueError("Resumed analysis original VCF identity differs from recorded upload")
    state = {"instanceId": current["instanceId"], "sourceHead": current["sourceHead"], "analysisId": analysis,
             "processIdBeforeRestart": current["processId"], "vcfSha256": old["vcfSha256"],
             "requirePm5Met": bool(old.get("requirePm5Met") or require_positive), "clientScriptSha256": script_sha,
             "resumedFromState": str(source_state), "resumedFromStateSha256": hashlib.sha256(raw).hexdigest()}
    return actual, state


class Client:
    def __init__(self, base, output, timeout, joint=False, expected_port=None):
        port = expected_port if expected_port is not None else (18227 if joint else 18217)
        if not isinstance(port, int) or not 1024 <= port <= 65535:
            raise ValueError("An explicit unprivileged task-owned port is required")
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.port != port or parsed.path not in ("", "/"):
            raise ValueError("Only the explicitly isolated http://127.0.0.1:18217 instance is permitted")
        self.events = []
        self.base, self.output, self.timeout = base.rstrip("/"), output, timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, name, method, path, body=None, content_type="application/json"):
        payload = body if isinstance(body, bytes) else (json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None)
        request = urllib.request.Request(self.base + path, data=payload, method=method,
                                         headers={"Content-Type": content_type, "Accept": "application/json"})
        prefix = self.output / (name + "-" + uuid.uuid4().hex[:8])
        if payload is not None:
            prefix.with_suffix(".request-body").write_bytes(payload)
        start = time.monotonic()
        try:
            response = self.opener.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            response = error
        except OSError as error:
            event = {"transport": "REAL_NETWORK_HTTP", "method": method, "url": self.base + path,
                     "status": "TRANSPORT_FAILED", "error": repr(error), "seconds": time.monotonic() - start,
                     "requestSha256": hashlib.sha256(payload).hexdigest() if payload is not None else None}
            save(prefix.with_suffix(".event.json"), event)
            self.events.append(event)
            raise
        with response:
            raw = response.read()
            event = {"transport": "REAL_NETWORK_HTTP", "method": method, "url": self.base + path,
                     "status": response.status, "responseHeaders": dict(response.headers),
                     "requestSha256": hashlib.sha256(payload).hexdigest() if payload is not None else None,
                     "responseSha256": hashlib.sha256(raw).hexdigest(), "seconds": time.monotonic() - start}
        prefix.with_suffix(".response-body").write_bytes(raw)
        event["eventFile"] = str(prefix.with_suffix(".event.json"))
        self.events.append(event)
        save(prefix.with_suffix(".event.json"), event)
        if not 200 <= event["status"] < 300:
            raise RuntimeError("HTTP failure: " + json.dumps(event))
        return json.loads(raw)


def multipart(vcf, sample, scope):
    boundary = "varvis-public-" + uuid.uuid4().hex
    chunks = []
    for key, value in {"genomeAssembly": "GRCh38", "analysisMode": "WES", "analysisScope": scope, "sampleId": sample}.items():
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="vcfFile"; filename="public-controls.vcf"\r\nContent-Type: text/plain\r\n\r\n'.encode())
    chunks.extend([vcf, f"\r\n--{boundary}--\r\n".encode()])
    return b"".join(chunks), "multipart/form-data; boundary=" + boundary


def require_pm5_met(saved, formal_candidate_ids):
    """Require an actual formal-candidate PM5 Moderate result, independent of persistence success."""
    formal = set(formal_candidate_ids) & set(saved.get("candidateIds", []))
    positives = []
    for candidate in saved.get("candidates", []):
        if candidate.get("candidateId") not in formal:
            continue
        for attempt in candidate.get("attempts", []):
            evaluations = attempt.get("missenseComparison", {}).get("criterionEvaluations", [])
            pm5 = [item for item in evaluations if item.get("criterionId") == "ACMG_AMP_2015:PM5"]
            ps1 = [item for item in evaluations if item.get("criterionId") == "ACMG_AMP_2015:PS1"]
            if len(pm5) != 1 or len(ps1) != 1:
                continue
            if (pm5[0].get("evaluationResult", {}).get("status") == "MET"
                    and pm5[0].get("appliedStrength") == "MODERATE"
                    and pm5[0].get("criterionApplicability", {}).get("status") == "APPLICABLE"
                    and ps1[0].get("evaluationResult", {}).get("status") in {"NOT_MET", "NOT_EVALUATED"}):
                positives.append({"candidateId": candidate["candidateId"], "contextId": pm5[0].get("contextId"),
                                  "PM5": "MET", "appliedStrength": "MODERATE", "PS1": ps1[0]["evaluationResult"]["status"]})
    if not positives:
        raise AssertionError("Required formal PM5 MET/MODERATE with non-MET PS1 was not observed; save/reuse success is independent")
    return positives


def identity(client, args):
    current = client.call("instance", "GET", "/__varvis_ps1pm5_instance")
    expected = {"instanceId": args.instance_id, "sourceHead": args.head, "branch": args.branch,
                "profileId": args.profile_id, "port": 18227 if getattr(args, "joint", False) else 18217, "transport": "REAL_NETWORK_HTTP", "database": "PRIVATE_H2"}
    if any(current.get(key) != value for key, value in expected.items()):
        raise ValueError("Isolated instance identity mismatch")
    if getattr(args, "joint", False):
        profile = current.get("executionProfile", {})
        expected_rules = {"ACMG_AMP_2015:" + code for code in ("PP3", "BP4", "BA1", "BP7", "PM2", "PS1", "PM5", "PVS1")}
        rules = profile.get("enabledCriteria", profile.get("enabledRuleIds", []))
        if (current.get("mode") != "JOINT" or profile.get("profileId") != "acmg-explicit-business-joint-v1"
                or profile.get("automaticTrigger") is not False or profile.get("classificationEnabled") is not False):
            raise ValueError("Joint instance must expose the actual eight-rule production manifest")
        if len(rules) != 8 or set(rules) != expected_rules:
            raise ValueError("Joint manifest rule identity mismatch")
        expected_instance = args.acceptance_root.resolve() / "network" / args.instance_id
        if Path(current.get("instanceRoot", "")).resolve() != expected_instance:
            raise ValueError("Joint instance output identity mismatch")
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("prepare", "evaluate", "reuse"))
    parser.add_argument("--base", default=None)
    parser.add_argument("--joint", action="store_true")
    parser.add_argument("--acceptance-root", type=Path)
    parser.add_argument("--confirmed-contexts", type=Path, help="Fixed public {geneSymbol,hgncId,mondoId,moi} array; resolves current server keys")
    parser.add_argument("--population-only", action="store_true", help="Confirm relationships only; no prediction/PVS1/PS1 transcript confirmation")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--instance-id", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--branch", default="feature/ps1-pm5")
    parser.add_argument("--profile-id", required=True)
    parser.add_argument("--vcf", type=Path)
    parser.add_argument("--resume-analysis-state", type=Path, help="Prepare a fresh declaration from this instance's existing READY analysis")
    parser.add_argument("--sample", default="synthetic-proband")
    parser.add_argument("--gene", default="HGNC:1097")
    parser.add_argument("--disease", default="MONDO:0021060")
    parser.add_argument("--moi", default="AUTOSOMAL_DOMINANT")
    parser.add_argument("--scope", choices=("STANDARD", "INCLUDE_INTRONS"), default="STANDARD")
    parser.add_argument("--top-n", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=3600)
    parser.add_argument("--confirm-prepared", action="store_true")
    parser.add_argument("--require-pm5-met", action="store_true", help="Persist a separate formal PM5 MET/Moderate acceptance requirement")
    args = parser.parse_args()
    args.base = args.base or ("http://127.0.0.1:18227" if args.joint else "http://127.0.0.1:18217")
    if args.joint and not args.acceptance_root:
        raise ValueError("Joint acceptance requires explicit external acceptance-root")
    if args.confirmed_contexts and not args.joint:
        raise ValueError("Confirmed contexts are a joint-mode option")
    confirmed_raw = args.confirmed_contexts.read_bytes() if args.confirmed_contexts else None
    confirmed_contexts = parse_confirmed_contexts(confirmed_raw) if confirmed_raw is not None else None
    confirmed_sha = hashlib.sha256(confirmed_raw).hexdigest() if confirmed_raw is not None else None
    if args.population_only and not args.joint:
        raise ValueError("Population-only confirmation is a joint-mode option")
    args.output = args.output.resolve()
    allowed = Path("varvis-server/target/ps1-pm5-network").resolve()
    if args.joint:
        allowed = args.acceptance_root.resolve() / "network" / args.instance_id
    if not args.output.is_relative_to(allowed):
        raise ValueError("Client output must remain under this worktree varvis-server/target/ps1-pm5-network")
    if args.stage == "prepare" and args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Prepare requires a fresh empty output directory; old acceptance artifacts remain unchanged")
    if args.resume_analysis_state:
        args.resume_analysis_state = args.resume_analysis_state.resolve()
        if args.stage != "prepare" or args.vcf is not None or not args.resume_analysis_state.is_relative_to(allowed):
            raise ValueError("Resume is a prepare-only action using an existing state in this worktree's isolated output")
    script_sha = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.stage == "prepare" and confirmed_raw is not None:
        (args.output / "confirmed-contexts.json").write_bytes(confirmed_raw)
    client = Client(args.base, args.output, args.timeout, joint=args.joint)
    current = identity(client, args)
    state_file = args.output / "network-state.json"
    report = {"stage": args.stage, "transport": "REAL_NETWORK_HTTP", "browserCovered": False, "status": "BLOCKED",
              "instance": current, "clientScriptSha256": script_sha, "confirmedContextsSha256": confirmed_sha}
    calls_before = client.call("calls-before", "GET", "/__varvis_ps1pm5_calls")
    try:
        if args.stage == "prepare":
            if state_file.exists() or (args.vcf is None and args.resume_analysis_state is None):
                raise ValueError("Prepare requires a fresh output and a public VCF")
            if args.resume_analysis_state:
                uploaded, state = resume_analysis(client, args.resume_analysis_state, current, script_sha, args.require_pm5_met)
                report.update(analysisReused=True, uploadPerformed=False, resumedFromStateSha256=state["resumedFromStateSha256"])
            else:
                vcf = args.vcf.read_bytes()
                if len(vcf) > 1024 * 1024 or not vcf.startswith(b"##fileformat=VCF"):
                    raise ValueError("This public-control harness accepts only a small ordinary VCF")
                (args.output / "uploaded-public.vcf").write_bytes(vcf)
                readiness = client.call("preflight", "GET", "/variant-analyses/preflight?genomeAssembly=GRCh38")
                if not readiness.get("applicationReady") or readiness.get("blockingErrors"):
                    raise RuntimeError("Original preflight is not READY; stop before uploading/engines")
                body, kind = multipart(vcf, args.sample, args.scope)
                uploaded = client.call("upload", "POST", "/variant-analyses", body, kind)
                state = {"instanceId": current["instanceId"], "sourceHead": current["sourceHead"], "analysisId": uploaded["id"],
                         "processIdBeforeRestart": current["processId"], "vcfSha256": hashlib.sha256(vcf).hexdigest(),
                         "requirePm5Met": args.require_pm5_met, "clientScriptSha256": script_sha}
            if confirmed_sha is not None:
                state["confirmedContextsSha256"] = confirmed_sha
                state["confirmedContextsFile"] = "confirmed-contexts.json"
            analysis = uploaded["id"]
            save(state_file, state)
            deadline = time.monotonic() + args.timeout
            while uploaded["status"] != "READY":
                if uploaded["status"] in {"FAILED", "CANCELLED"} or time.monotonic() >= deadline:
                    raise RuntimeError("Original analysis did not reach READY: " + json.dumps(uploaded))
                time.sleep(2)
                uploaded = client.call("analysis-poll", "GET", "/variant-analyses/" + analysis)
            save(args.output / "formal-analysis.json", uploaded)
            options = client.call("input-options", "GET", f"/variant-analyses/{analysis}/acmg/input-options?topN={args.top_n}")
            save(args.output / "input-options.json", options)
            state["formalCandidateIds"] = [row["candidateId"] for row in options["candidates"]]
            if args.joint:
                expected = {"ACMG_AMP_2015:" + code for code in ("PP3", "BP4", "BA1", "BP7", "PM2", "PS1", "PM5", "PVS1")}
                if set(options.get("enabledCriteria", [])) != expected:
                    raise ValueError("Normal input-options did not expose all eight joint rules")
                prepared = joint_declaration(options, args.sample, args.top_n, patient_context_id(analysis), args.population_only, confirmed_contexts)
            else:
                prepared = declaration(options, args.sample, args.gene, args.disease, args.moi, args.top_n, patient_context_id(analysis))
            save(args.output / "prepared-declaration.json", prepared)
            state["preparedSha256"] = hashlib.sha256((args.output / "prepared-declaration.json").read_bytes()).hexdigest()
            save(state_file, state)
            report["status"] = "PREPARED_REQUIRES_EXPLICIT_EVALUATE"
        else:
            state = json.loads(state_file.read_bytes())
            report["confirmedContextsSha256"] = verify_confirmed_context_identity(args.output, state, confirmed_sha)
            if state["instanceId"] != current["instanceId"] or state["sourceHead"] != current["sourceHead"]:
                raise ValueError("Saved client state belongs to a different instance/source")
            state["requirePm5Met"] = bool(state.get("requirePm5Met") or args.require_pm5_met)
            state["clientScriptSha256"] = script_sha
            save(state_file, state)
            report["requirePm5Met"] = state["requirePm5Met"]
            prepared_bytes = (args.output / "prepared-declaration.json").read_bytes()
            if hashlib.sha256(prepared_bytes).hexdigest() != state["preparedSha256"]:
                raise ValueError("Prepared declaration changed; obtain fresh recommendations")
            request = json.loads(prepared_bytes)
            analysis = state["analysisId"]
            if args.stage == "evaluate":
                if not args.confirm_prepared or (args.output / "saved-business.json").exists():
                    raise ValueError("Evaluate requires --confirm-prepared and no existing saved result")
                saved = client.call("explicit-post", "POST", f"/variant-analyses/{analysis}/acmg/runs", request)
                save(args.output / "saved-business.json", saved)
                state["runId"] = saved["runId"]
                save(state_file, state)
            else:
                if current["processId"] == state["processIdBeforeRestart"]:
                    raise ValueError("Reuse stage requires an actual separately restarted JVM, not a service object reconstruction")
                saved = json.loads((args.output / "saved-business.json").read_bytes())
            before_reuse = client.call("calls-before-reuse", "GET", "/__varvis_ps1pm5_calls")
            read = client.call("saved-get", "GET", f"/variant-analyses/{analysis}/acmg/runs/{saved['runId']}")
            repeated = client.call("repeat-post", "POST", f"/variant-analyses/{analysis}/acmg/runs", request)
            after_reuse = client.call("calls-after-reuse", "GET", "/__varvis_ps1pm5_calls")
            if saved != read or saved != repeated or before_reuse != after_reuse:
                raise AssertionError("Saved GET / repeat POST content or zero-new-call contract failed")
            report.update(status="NETWORK_SAVE_REUSE_VERIFIED", runId=saved["runId"], savedGetIdentical=True,
                          repeatIdentical=True, reuseCallIncrement={key: after_reuse[key] - before_reuse[key] for key in after_reuse},
                          processRestartCovered=args.stage == "reuse", engineeringSaveReusePassed=True,
                          actualRepeatPostEvent=next(event for event in reversed(client.events)
                              if event["method"] == "POST" and event["url"].endswith("/acmg/runs")),
                          actualSavedGetEvent=next(event for event in reversed(client.events)
                              if event["method"] == "GET" and event["url"].endswith("/acmg/runs/" + saved["runId"])))
            if state["requirePm5Met"]:
                report["pm5PositiveControls"] = require_pm5_met(saved, state.get("formalCandidateIds", []))
                report["pm5PositivePassed"] = True
    except Exception as error:
        report["status"] = "BLOCKED"
        report["failure"] = repr(error)
        raise
    finally:
        calls_after = client.call("calls-after", "GET", "/__varvis_ps1pm5_calls")
        report["callIncrement"] = {key: calls_after[key] - calls_before.get(key, 0) for key in calls_after}
        save(args.output / (args.stage + "-report.json"), report)
        print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
