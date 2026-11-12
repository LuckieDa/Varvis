#!/usr/bin/env python3
"""Import externally reviewed pilot HPO decisions without changing term decisions."""

import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path


SCHEMA_VERSION = "pilot-hpo-decision-import-v1"
DECISION_SCHEMA = "truth-masked-hpo-review-decision-v1"
REVIEW_PROTOCOL = "truth-masked-development-review-v1"
REVIEW_METHOD = "TRUTH_MASKED_DEVELOPMENT_REVIEW"
AI_SCOPE = "HPO_ID_LABEL_AND_INTERNAL_CONSISTENCY_CHECK"
HPO_VERSION = "2026-06-23"
HPO_SOURCE = "https://purl.obolibrary.org/obo/hp.obo"
BROKEN_APPROVAL_KEY = '"humanApprovalStatement"'
LABEL_NOTE = re.compile(
    r"Human-approved label discrepancy: (HP:\d{7}) source label '([^']+)' "
    r"versus current official label '([^']+)'; decision (KEEP_OBSERVED|KEEP_EXCLUDED)\."
)


class ImportErrorDetail(ValueError):
    pass


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_bounded_broken_json(path):
    lines = path.read_text(encoding="utf-8", errors="strict").splitlines()
    hits = [index for index, line in enumerate(lines) if BROKEN_APPROVAL_KEY in line]
    if len(hits) != 1:
        raise ImportErrorDetail(f"BROKEN_APPROVAL_FIELD_COUNT:{path.name}:{len(hits)}")
    del lines[hits[0]]
    try:
        return json.loads("\n".join(lines))
    except json.JSONDecodeError as exc:
        raise ImportErrorDetail(f"PACKAGE_JSON_INVALID_AFTER_BOUNDED_REPAIR:{path.name}:{exc.msg}") from exc


def read_json(path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if path.exists():
        if path.read_bytes() == encoded:
            return "REUSED_IDENTICAL"
        raise ImportErrorDetail(f"OUTPUT_CONFLICT:{path.name}")
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return "CREATED"


def require_equal(actual, expected, code):
    if actual != expected:
        raise ImportErrorDetail(code)


def semantic_notes(raw, packet):
    results = []
    labels = {term["hpoId"]: term.get("label") for term in packet["terms"]}
    for text in raw.get("semanticAuditNotes", []):
        match = LABEL_NOTE.fullmatch(text)
        if not match:
            continue
        hpo_id, source_label, canonical_label, decision = match.groups()
        require_equal(labels.get(hpo_id), source_label, f"SEMANTIC_NOTE_SOURCE_LABEL_MISMATCH:{hpo_id}")
        results.append({
            "hpoId": hpo_id,
            "sourceLabel": source_label,
            "canonicalLabelAtSemanticReference": canonical_label,
            "relationship": "SOURCE_LABEL_DIFFERS_FROM_CURRENT_LABEL",
            "decisionImpact": "NONE",
        })
    return results


def normalize(raw, packet):
    for field in ("reviewId", "reviewProtocolVersion", "packetFingerprint", "packetContentChecksum"):
        require_equal(raw.get(field), packet.get(field), f"{field.upper()}_MISMATCH:{packet.get('reviewId')}")
    require_equal(raw.get("schemaVersion"), DECISION_SCHEMA, "DECISION_SCHEMA_UNSUPPORTED")
    require_equal(raw.get("reviewProtocolVersion"), REVIEW_PROTOCOL, "REVIEW_PROTOCOL_UNSUPPORTED")
    require_equal(raw.get("reviewMethod"), REVIEW_METHOD, "REVIEW_METHOD_INVALID")
    require_equal(raw.get("priorTruthExposure"), "NOT_ATTESTED", "PRIOR_TRUTH_EXPOSURE_UNEXPECTED")
    require_equal(raw.get("activeTruthReviewReported"), False, "ACTIVE_TRUTH_REVIEW_INVALID")
    require_equal(raw.get("eligibleForStrictBlindedEvaluation"), False, "STRICT_BLINDED_ELIGIBILITY_INVALID")
    require_equal(raw.get("additions"), [], "ADDITIONS_NOT_EMPTY")

    raw_terms = raw.get("termDecisions")
    packet_terms = packet.get("terms")
    if not isinstance(raw_terms, list) or len(raw_terms) != len(packet_terms):
        raise ImportErrorDetail(f"TERM_COUNT_MISMATCH:{packet['reviewId']}")
    normalized_terms = []
    for index, (decision, term) in enumerate(zip(raw_terms, packet_terms)):
        expected = "KEEP_OBSERVED" if term["originalStatus"] == "OBSERVED" else "KEEP_EXCLUDED"
        require_equal(decision.get("hpoId"), term.get("hpoId"), f"TERM_ID_OR_ORDER_MISMATCH:{packet['reviewId']}:{index}")
        require_equal(decision.get("originalStatus"), term.get("originalStatus"), f"TERM_STATUS_MISMATCH:{packet['reviewId']}:{index}")
        require_equal(decision.get("decision"), expected, f"TERM_DECISION_INVALID:{packet['reviewId']}:{index}")
        require_equal(decision.get("mappingErrorReason"), None, f"MAPPING_ERROR_REASON_UNEXPECTED:{packet['reviewId']}:{index}")
        normalized_terms.append({
            "hpoId": decision["hpoId"],
            "originalStatus": decision["originalStatus"],
            "decision": decision["decision"],
            "mappingErrorReason": None,
        })

    ai = raw.get("aiAssistance")
    if not isinstance(ai, dict) or ai.get("used") is not True or ai.get("humanApprovalRecorded") is not True:
        raise ImportErrorDetail(f"AI_OR_HUMAN_APPROVAL_PROVENANCE_INVALID:{packet['reviewId']}")
    require_equal(ai.get("provider"), "OpenAI Codex", f"AI_PROVIDER_UNEXPECTED:{packet['reviewId']}")

    return {
        "schemaVersion": DECISION_SCHEMA,
        "reviewProtocolVersion": REVIEW_PROTOCOL,
        "reviewId": packet["reviewId"],
        "packetFingerprint": packet["packetFingerprint"],
        "packetContentChecksum": packet["packetContentChecksum"],
        "reviewMethod": REVIEW_METHOD,
        "reviewerRole": raw.get("reviewerRole"),
        "priorTruthExposure": "NOT_RECORDED",
        "activeTruthReviewReported": False,
        "eligibleForStrictBlindedEvaluation": False,
        "sourceContext": packet.get("sourceContext"),
        "aiAssistance": {
            "aiAssistanceUsed": True,
            "assistanceScope": AI_SCOPE,
            "humanFinalConfirmationRequired": True,
            "humanFinalConfirmation": True,
            "provider": "OpenAI Codex",
            "modelIdentifier": "NOT_RECORDED",
            "sourceHpoOntologyVersion": packet.get("hpoOntologyVersion"),
            "semanticReferenceHpoVersion": HPO_VERSION,
            "semanticReferenceSource": HPO_SOURCE,
        },
        "semanticAuditNotes": semantic_notes(raw, packet),
        "reviewCompletionStatus": "READY_IDENTICAL_TO_G1",
        "reviewDate": raw.get("reviewDate"),
        "termDecisions": normalized_terms,
        "additions": [],
        "decisionCounts": {
            "totalOriginalTerms": len(normalized_terms),
            "completedOriginalTermDecisions": len(normalized_terms),
            "additions": 0,
        },
    }


def import_decisions(package_root, review_root, output_root, package_sha256):
    formal_root = package_root / "formal-decisions"
    dropin_root = package_root / "drop-in-completed-templates"
    expected_ids = [f"PILOT-HPO-{number:03d}" for number in range(1, 31)]
    actual = sorted(path.stem.replace(".decision", "") for path in formal_root.glob("*.decision.json"))
    require_equal(actual, expected_ids, "FORMAL_DECISION_SET_MISMATCH")

    totals = {"reviews": 0, "terms": 0, "keepObserved": 0, "keepExcluded": 0,
              "remove": 0, "uncertain": 0, "additions": 0, "labelDiscrepancies": 0}
    entries = []
    for review_id in expected_ids:
        source = formal_root / f"{review_id}.decision.json"
        duplicate = dropin_root / f"{review_id}.decision-template.json"
        if not duplicate.exists() or sha256(source) != sha256(duplicate):
            raise ImportErrorDetail(f"CANONICAL_DROPIN_MISMATCH:{review_id}")
        packet = read_json(review_root / "reviewer-visible" / f"{review_id}.packet.json")
        normalized = normalize(load_bounded_broken_json(source), packet)
        output = output_root / f"{review_id}.decision.json"
        write_json_atomic(output, normalized)
        observed = sum(term["decision"] == "KEEP_OBSERVED" for term in normalized["termDecisions"])
        excluded = sum(term["decision"] == "KEEP_EXCLUDED" for term in normalized["termDecisions"])
        totals["reviews"] += 1
        totals["terms"] += len(normalized["termDecisions"])
        totals["keepObserved"] += observed
        totals["keepExcluded"] += excluded
        totals["labelDiscrepancies"] += len(normalized["semanticAuditNotes"])
        entries.append({
            "reviewId": review_id,
            "packetFingerprint": packet["packetFingerprint"],
            "decisionSha256": sha256(output),
            "termCount": len(normalized["termDecisions"]),
            "keepObserved": observed,
            "keepExcluded": excluded,
            "readiness": "READY_IDENTICAL_TO_G1",
        })

    require_equal(totals, {"reviews": 30, "terms": 577, "keepObserved": 208,
                            "keepExcluded": 369, "remove": 0, "uncertain": 0,
                            "additions": 0, "labelDiscrepancies": 2}, "AGGREGATE_COUNTS_MISMATCH")
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "status": "HUMAN_REVIEW_COMPLETED",
        "sourcePackageFileName": "Varvis_Pilot_HPO_Formal_Decisions_2026-07-17.zip",
        "sourcePackageSha256": package_sha256,
        "normalization": {
            "boundedRepair": "REMOVE_SINGLE_BROKEN_HUMAN_APPROVAL_STATEMENT_LINE",
            "priorTruthExposure": "NOT_ATTESTED_TO_NOT_RECORDED",
            "aiAssistance": "MAPPED_TO_REPOSITORY_PROVENANCE_SCHEMA",
            "decisionStatus": "VALIDATOR_DERIVED_READY_IDENTICAL_TO_G1",
            "hpoTermDecisionsChanged": False,
        },
        "summary": totals,
        "g2Status": "NOT_AUTHORIZED",
        "decisions": entries,
    }
    write_json_atomic(output_root / "formal-decision-import-manifest.json", manifest)
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--review-root", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--package-sha256", required=True)
    args = parser.parse_args()
    manifest = import_decisions(args.package_root.resolve(), args.review_root.resolve(),
                                args.output_root.resolve(), args.package_sha256.lower())
    print(json.dumps(manifest["summary"], sort_keys=True))


if __name__ == "__main__":
    main()
