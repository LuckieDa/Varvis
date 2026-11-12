"""Explicit public-literature pilot worker. No patient inputs or scientific scores.

Offline --preflight validates pins and constructs the exact requests without
credentials or network. --execute is opt-in. Output manifest is read-only input
to the existing literature reader; GET/history never invokes this worker.
"""
import argparse
import base64
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

from model_literature_contract import canonical, digest, parse, prepare, model_request
from model_transport import config, credentials, bounded, require, invoke, atomic
from model_replay import binding, replay_artifact


def sources(manifest):
    doc = parse(bounded(manifest, 65536))
    require(doc.get("schemaVersion") == "denovo-public-model-input-v1", "INPUT_MANIFEST_SCHEMA")
    entries = doc.get("documents")
    require(isinstance(entries, list) and 0 < len(entries) <= 20, "DOCUMENT_BUDGET")
    seen = set(); result = []
    for entry in entries:
        require(entry.get("sourceClass") == "PUBLIC_PAPER", "PUBLIC_SOURCES_ONLY")
        pmcid, pin = entry["pmcid"], entry["sha256"]
        require(re.fullmatch(r"PMC[1-9][0-9]*", pmcid) and re.fullmatch(r"[a-f0-9]{64}", pin), "SOURCE_IDENTITY_INVALID")
        url = urlsplit(entry.get("acquisitionUrl", ""))
        require(url.scheme == "https" and url.hostname == "www.ebi.ac.uk"
                and url.path == "/europepmc/webservices/rest/" + pmcid + "/fullTextXML"
                and not url.username and not url.query and not url.fragment, "PUBLIC_ACQUISITION_REFERENCE_REQUIRED")
        require(pin not in seen, "DUPLICATE_SOURCE")
        seen.add(pin)
        path = (Path(manifest).resolve().parent / entry["path"]).resolve()
        raw = bounded(path, 2097152)
        prepared = prepare(raw, pmcid, pin)
        # Origins remain private and exact. A URL label alone is not scientific
        # approval; the operator freezes this manifest from the acquisition ledger.
        result.append((entry, raw, prepared))
    return result


def run(manifest, config_path, state_dir, output_dir, execute=False):
    c = config(config_path)
    prepared = sources(manifest)
    requests = [model_request(d, c["model"], c["maxOutputTokens"]) for unused, unused2, d in prepared]
    for request in requests:
        require(len(canonical(request)) <= c["maxRequestBytes"], "INPUT_TOO_LARGE_NOT_TRUNCATED")
    output = Path(output_dir)
    require(not output.exists(), "NEW_OUTPUT_DIRECTORY_REQUIRED")
    output.mkdir(parents=True)
    preflight = {"schemaVersion": "denovo-model-preflight-v1", "sourceManifestSha256": digest(Path(manifest).read_bytes()),
                 "requests": [{"pmcid": d["pmcid"], "sourceSha256": d["sourceSha256"],
                     "requestSha256": digest(canonical(r)), "requestBytes": len(canonical(r)),
                     "binding": binding(d)} for (unused, unused2, d), r in zip(prepared, requests)],
                 "executeRequested": execute, "modelCalls": 0, "networkCalls": 0}
    atomic(output / "preflight.json", preflight)
    if not execute:
        return preflight
    key = credentials(c, config_path)
    index = {"schemaVersion": "denovo-model-artifact-index-v1", "artifacts": []}
    counters = {"modelCalls": 0, "networkCalls": 0, "cacheHits": 0}
    for (entry, raw, document), request in zip(prepared, requests):
        response, receipt, cached = invoke(c, key, request, state_dir, binding(document))
        counters["cacheHits" if cached else "modelCalls"] += 1
        if not cached:
            counters["networkCalls"] += 1
        artifact = {"schemaVersion": "denovo-model-artifact-v1", "receipt": receipt,
                    "responseBase64": base64.b64encode(response).decode("ascii")}
        # Reconstruct before publication. Missing allele sources here are an
        # explicit identity gap; business replay uses its own pinned VCV records.
        facts = replay_artifact(raw, document["pmcid"], document["sourceSha256"], artifact)
        name = document["sourceSha256"] + ".json"
        atomic(output / name, artifact)
        atomic(output / (document["sourceSha256"] + "-inspection.json"), facts)
        index["artifacts"].append({"sourceSha256": document["sourceSha256"], "pmcid": document["pmcid"],
                                   "path": name, "sha256": digest((output / name).read_bytes())})
    atomic(output / "model-artifacts.json", index)
    result = {"schemaVersion": "denovo-model-worker-result-v1", **counters,
              "artifactIndexSha256": digest((output / "model-artifacts.json").read_bytes()),
              "clinicalAdoption": "NONE", "status": "CANDIDATES_READY_FOR_SOURCE_AND_POLICY_CHECKS"}
    atomic(output / "result.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.manifest, args.config, args.state, args.output, args.execute)))
    except Exception as error:
        # No key, request text or provider error body in console/tracebacks.
        reason = str(error) if isinstance(error, ValueError) and str(error).startswith(("DENOVO_", "LITERATURE_")) else "DENOVO_MODEL_INPUT_OR_STORAGE_INVALID"
        print(json.dumps({"status": "STOPPED", "reasonCode": reason}))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
