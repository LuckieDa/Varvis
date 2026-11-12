"""Offline replay of a pinned model response; cannot make a provider call."""
import base64
from pathlib import Path
from model_literature_contract import (VERSION, PROMPT_VERSION, canonical, digest, model_request,
                                      replay_candidates, prepare)
from model_transport import decode_response, require

MODEL_FILES = ("model_literature_contract.py", "model_transport.py", "model_replay.py", "run_model_extraction.py")


def implementation():
    names = MODEL_FILES + ("prepare_literature.py", "extract_literature_cases.py",
                          "qualify_literature_cases.py", "literature_allele_identity.py")
    return {name: digest((Path(__file__).parent / name).read_bytes()) for name in names}


def binding(document):
    return {"sourceSha256": document["sourceSha256"], "pmcid": document["pmcid"],
            "promptVersion": PROMPT_VERSION, "candidateVersion": VERSION, "implementation": implementation()}


def replay_artifact(raw, pmcid, pin, artifact, allele_sources=()):
    require(artifact.get("schemaVersion") == "denovo-model-artifact-v1", "ARTIFACT_SCHEMA")
    document = prepare(raw, pmcid, pin)
    receipt = artifact["receipt"]
    identity = receipt["identity"]
    require(identity["binding"] == binding(document), "ARTIFACT_SOURCE_OR_IMPLEMENTATION_CHANGED")
    c = identity["configuration"]
    body = canonical(model_request(document, c["model"], c["maxOutputTokens"]))
    require(identity["requestSha256"] == digest(body), "ARTIFACT_REQUEST_MISMATCH")
    response = base64.b64decode(artifact["responseBase64"], validate=True)
    require(len(response) <= c["maxResponseBytes"] and receipt["responseSha256"] == digest(response), "ARTIFACT_RESPONSE_PIN_MISMATCH")
    proposed, usage = decode_response(response, c)
    require(receipt["usage"] == usage and receipt.get("modelCalls") == 1 and receipt.get("networkCalls") == 1, "ARTIFACT_RECEIPT_MISMATCH")
    require(receipt.get("transportKind") in {"REAL_PROVIDER", "SYNTHETIC_TEST"}, "ARTIFACT_TRANSPORT_ORIGIN_REQUIRED")
    facts = replay_candidates(raw, pmcid, pin, proposed, allele_sources)
    facts["modelProvenance"] = {"artifactSha256": digest(canonical(artifact)), "receipt": receipt,
                                "response": proposed, "providerResponseBase64": artifact["responseBase64"],
                                "currentReplayModelCalls": 0, "originalExtractionModelCalls": 1}
    return facts
