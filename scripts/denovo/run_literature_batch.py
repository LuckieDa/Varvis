"""Offline source-reader protocol. Accept pinned originals, never model claims."""
import argparse
import base64
import hashlib
import json
import re
from pathlib import Path
from qualify_literature_cases import qualify, VERSION as QUALIFIER_VERSION

VERSION = "denovo-literature-batch-reader-1.0.0"
FILES = ("run_literature_batch.py", "prepare_literature.py", "extract_literature_cases.py",
         "qualify_literature_cases.py", "literature_allele_identity.py")
MAX_INPUT = 24 * 1024 * 1024
MAX_OUTPUT = 1024 * 1024
MAX_CORE_OUTPUT = 4 * 1024 * 1024


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("DENOVO_LITERATURE_DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def process(raw, work_root=None):
    if not 0 < len(raw) <= MAX_INPUT:
        raise ValueError("DENOVO_LITERATURE_REQUEST_SIZE_LIMIT")
    request = json.loads(raw, object_pairs_hook=unique)
    mode = request.get("schemaVersion")
    if mode not in {"denovo-literature-reader-request-v1", "denovo-literature-reader-request-v2-model-replay"}:
        raise ValueError("DENOVO_LITERATURE_REQUEST_SCHEMA")
    documents = request["documents"]
    if not isinstance(documents, list) or len(documents) > 32:
        raise ValueError("DENOVO_LITERATURE_DOCUMENT_LIMIT")
    model_replay = mode == "denovo-literature-reader-request-v2-model-replay"
    names = FILES
    if model_replay:
        from model_replay import MODEL_FILES, replay_artifact
        names += MODEL_FILES
    bundle_intake = any(a.get('schemaVersion') == 'denovo-bundle-candidate-intake-v1'
                        for a in request.get('modelArtifacts', {}).values())
    if bundle_intake:
        from bundle_candidate_intake import FILES as BUNDLE_FILES, replay_intake
        names += BUNDLE_FILES
    core = request.get('coreContext')
    reviewed = any('reviewedPaperSha256' in doc for doc in documents)
    if reviewed:
        from reviewed_denovo_case import replay as replay_reviewed_paper
        names += ('reviewed_denovo_case.py',)
    if core is not None:
        if not bundle_intake and not reviewed:
            raise ValueError('DENOVO_CORE_BUNDLE_INTAKE_REQUIRED')
        from core_evaluation_intake import FILES as CORE_FILES
        names += CORE_FILES
    scripts = Path(__file__).resolve().parent
    implementation = {name: digest(((scripts.parent / 'ps1_pm5' if name == 'pp2_relationship_material.py'
                                    else scripts) / name).read_bytes()) for name in names}
    if request.get("implementation") != implementation:
        raise ValueError("DENOVO_LITERATURE_IMPLEMENTATION_CHANGED")
    originals = {}
    for pin, encoded in request["originals"].items():
        original = base64.b64decode(encoded, validate=True)
        if digest(original) != pin:
            raise ValueError("DENOVO_LITERATURE_ORIGINAL_PIN_MISMATCH")
        originals[pin] = original
    if len(originals) > 100 or sum(len(v) for v in originals.values()) > 16 * 1024 * 1024:
        raise ValueError("DENOVO_LITERATURE_ORIGINAL_BUDGET")
    results = []
    for source in documents:
        result = {"sourceId": source["sourceId"], "pmcid": source["pmcid"], "sourceSha256": source["sha256"]}
        try:
            alleles = [(originals[a["sha256"]], a["sha256"], a["vcv"]) for a in source["alleles"]]
            artifact = request.get("modelArtifacts", {}).get(source["sha256"]) if model_replay else None
            if 'reviewedPaperSha256' in source:
                if artifact is not None:
                    raise ValueError('DENOVO_REVIEWED_MODEL_SOURCE_OVERLAP')
                try:
                    result['facts'] = replay_reviewed_paper(originals[source['sha256']], source,
                        source['reviewedPaperSha256'], originals, core, alleles)
                except (OSError, TypeError, AttributeError) as error:
                    raise ValueError('DENOVO_REVIEWED_SOURCE_UNAVAILABLE') from error
            elif artifact is not None:
                if artifact.get('schemaVersion') == 'denovo-bundle-candidate-intake-v1':
                    result['facts'] = replay_intake(originals[source['sha256']], source['pmcid'], source['sha256'], artifact, alleles, work_root, core)
                elif core is not None:
                    raise ValueError('DENOVO_CORE_BUNDLE_INTAKE_REQUIRED')
                else:
                    result["facts"] = replay_artifact(originals[source["sha256"]], source["pmcid"], source["sha256"], artifact, alleles)
            elif core is not None:
                raise ValueError('DENOVO_CORE_BUNDLE_INTAKE_REQUIRED')
            else:
                result["facts"] = qualify(originals[source["sha256"]], source["pmcid"], source["sha256"], alleles)
                if model_replay:
                    result["facts"]["issues"].append("DENOVO_MODEL_ARTIFACT_NOT_AVAILABLE")
            result["status"] = "SOURCE_READ"
        except (ValueError, KeyError, UnicodeError) as error:
            reason = str(error) if re.fullmatch(r"DENOVO_(?:MODEL|BUNDLE|CORE|IDENTITY|REVIEWED)[A-Z_]{1,120}", str(error)) else "DENOVO_LITERATURE_SOURCE_INVALID"
            result.update(status="UNAVAILABLE", reasonCode=reason, detail=str(error)[:240])
        results.append(result)
    result = {"schemaVersion": "denovo-literature-reader-result-v1", "readerVersion": VERSION,
              "qualifierVersion": QUALIFIER_VERSION, "requestSha256": digest(raw), "implementation": implementation,
              "documents": results, "modelCalls": 0, "networkCalls": 0, "clinicalAdoption": "NONE"}
    if model_replay:
        result.update(schemaVersion="denovo-literature-reader-result-v2-model-replay",
                      readerVersion="denovo-literature-model-replay-1.0.0",
                      modelArtifactIndexSha256=request["modelArtifactIndexSha256"],
                      originalModelCalls=sum({d['facts']['modelProvenance']['artifactSha256']:
                          d['facts']['modelProvenance']['originalExtractionModelCalls'] +
                          d['facts']['modelProvenance'].get('originalSourceReviewModelCalls', 0) for d in results
                          if 'modelProvenance' in d.get('facts', {})}.values()))
    encode = lambda value: json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    limit = MAX_CORE_OUTPUT if core is not None else MAX_OUTPUT
    output = encode(result)
    # One large audit must not erase other valid papers. Do not truncate its
    # claims/citations or silently adopt a subset: withhold that whole paper,
    # retain its source identity and a specific technical limitation. Pinned
    # inputs and original model receipts remain in their original archive.
    while len(output) > limit:
        candidates = [(len(encode(d)), i) for i, d in enumerate(results) if d.get('status') == 'SOURCE_READ']
        if not candidates:
            raise ValueError("DENOVO_LITERATURE_RESULT_SIZE_LIMIT")
        size, index = max(candidates)
        d = results[index]
        results[index] = {k:d[k] for k in ('sourceId','pmcid','sourceSha256')}
        results[index].update(status='UNAVAILABLE', reasonCode='DENOVO_LITERATURE_DOCUMENT_RESULT_SIZE_LIMIT',
                             detail='Complete paper audit exceeds remaining result budget; no partial facts adopted.',
                             completeDocumentBytes=size, aggregateLimitBytes=limit)
        output = encode(result)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    with args.request.open("rb") as stream:
        raw = stream.read(MAX_INPUT + 1)
    output = process(raw, args.output.parent)
    with args.output.open("xb") as stream:
        stream.write(output)
    print(json.dumps({"requestSha256": digest(raw), "resultBytes": len(output), "modelCalls": 0, "networkCalls": 0}))


if __name__ == "__main__":
    main()
