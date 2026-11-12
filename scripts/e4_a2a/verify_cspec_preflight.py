"""Offline registry pairing and synthetic CSpec preflight checks using frozen E1 rules."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.e3_c1 import validate_alphamissense_supporting_successor as c1
from scripts.evidence import acmg_evidence_v2_contract as e1


ACMG_ROOT = REPO_ROOT / "varvis-server" / "src" / "main" / "resources" / "acmg"
RULE_PATH = ACMG_ROOT / "rule-registry-v2" / "acmg-rule-registry-e3-c1-2026-09-02.json"
OVERLAY_PATH = ACMG_ROOT / "cspec-overlay-registry-v1" / "cspec-overlay-registry-e4-a2a-2026-09-05.json"
L1A_OVERLAY_PATH = ACMG_ROOT / "cspec-overlay-registry-v1" / "cspec-overlay-registry-e3-l1a-2026-09-05.json"
RULE_ID = "acmg-rule-registry-e3-c1-2026-09-02"
OVERLAY_ID = "cspec-overlay-registry-e4-a2a-2026-09-05"
AS_OF = "2026-08-31T11:20:00Z"
ALLOWED_CHANGED_FIELDS = {"registryId", "baseRuleRegistryId", "baseRuleRegistrySha256"}

RULE_FILE_SHA256 = "73fca655b6d76700242fc6b12ba4c6c4b9f34ac5c79b38621fe03fecc8303469"
RULE_CANONICAL_SHA256 = "61617eb005138ebc814b9c14afd9f3f34189fb7a93442c7c6843c780161b647e"
OVERLAY_FILE_SHA256 = "5ffe4ebb0d2b0509271203427802307ab4443a1ae974a344b8fadb5b46708b48"
OVERLAY_CANONICAL_SHA256 = "f2e9aed3008e13a462225db43f1a3672bb80f7fde6abab44faa7f2741cc1bd0e"
FROZEN_OVERLAY_FILE_SHA256 = "aee206e3b9b22ebad02f54fe010ac10d9e8a9c1ffd7d41f5e37e859fe8b97295"
FROZEN_OVERLAY_CANONICAL_SHA256 = "0dabff535bfa0960260e0ff02cdbd633b4b41c12ca8354f6cfb7a7516cc5d43e"
L1A_OVERLAY_FILE_SHA256 = "26c1e5a5cbd1dca3b4b5af53c6b413ee869aa665246bc918d2ec022a12f34273"
L1A_OVERLAY_CANONICAL_SHA256 = "647b4031c1ba2573887939810127cf13044733ead2223633320e0f2308f7abf4"
AUTHORITY_FILE_SHA256 = "9c4d12df8eba350318624e25d50321f845c023f1d136bff934ecfbb89d45e627"
AUTHORITY_CANONICAL_SHA256 = "63db590058808737bdd03af7fd82533c572b80bfabae9b50f0632da78cd803f9"
REVIEW_FIELDS = {"schemaVersion", "reviewRecordId", "contextId", "subjectType", "subjectId",
                 "eventType", "status", "decision", "actor", "reasonCode", "createdAt", "rationaleSummary"}
REVIEW_UNSUPPORTED_FIELDS = {"sourceLocators", "previousReviewRecordId", "override"}
CONFLICT_FIELDS = {"conflictId", "conflictType", "participantIds", "resolutionStatus", "detectedAt", "reasonCode"}


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise e1.ContractError(reason)


def require_utc(value: object, reason: str) -> None:
    require(isinstance(value, str) and bool(re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
        r"(?:\.[0-9]{1,9})?(?:Z|\+00:00)", value)), reason)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise e1.ContractError(reason) from error
    require(parsed.utcoffset() == timedelta(0), reason)


def validate_review_envelope(review: dict) -> None:
    require(isinstance(review, dict), "CSPEC_REVIEW_RECORD_INVALID")
    require(REVIEW_UNSUPPORTED_FIELDS.isdisjoint(review), "CSPEC_REVIEW_EVENT_UNSUPPORTED")
    require(set(review) <= REVIEW_FIELDS, "CSPEC_REVIEW_FIELD_UNKNOWN")
    require((review.get("eventType"), review.get("status"), review.get("decision"))
            == ("REQUESTED", "REQUIRED", "NONE"), "CSPEC_REVIEW_EVENT_UNSUPPORTED")
    actor = review.get("actor")
    require(isinstance(actor, dict), "CSPEC_REVIEW_ACTOR_INVALID")
    require(set(actor) <= {"actorId", "role"}, "CSPEC_REVIEW_FIELD_UNKNOWN")
    require_utc(review.get("createdAt"), "CSPEC_REVIEW_TIME_INVALID")


def validate_conflict_envelope(conflict: dict) -> None:
    require(isinstance(conflict, dict), "CSPEC_CONFLICT_RECORD_INVALID")
    require("resolutionReviewRecordId" not in conflict, "CSPEC_CONFLICT_EVENT_UNSUPPORTED")
    require(set(conflict) <= CONFLICT_FIELDS, "CSPEC_CONFLICT_FIELD_UNKNOWN")
    require(conflict.get("resolutionStatus") == "OPEN"
            and conflict.get("conflictType") == "RULE_PROFILE_MULTIPLE_MATCH", "CSPEC_CONFLICT_EVENT_UNSUPPORTED")
    require_utc(conflict.get("detectedAt"), "CSPEC_CONFLICT_TIME_INVALID")


def pinned_json(path: Path, file_sha256: str, canonical_sha256: str) -> dict:
    raw = path.read_bytes()
    require(e1.sha256_bytes(raw) == file_sha256, "REGISTRY_FILE_SHA256_MISMATCH")
    value = json.loads(raw)
    require(e1.canonical_sha256(value) == canonical_sha256, "REGISTRY_CANONICAL_SHA256_MISMATCH")
    return value


def validate_pairing(overlay: dict, rule: dict, authority: dict, schemas: e1.ContractSchemas) -> None:
    require(rule.get("registryId") == RULE_ID
            and e1.canonical_sha256(rule) == RULE_CANONICAL_SHA256, "E3_C1_RULE_IDENTITY_CHANGED")
    require(e1.canonical_sha256(authority) == AUTHORITY_CANONICAL_SHA256, "AUTHORITY_IDENTITY_CHANGED")
    e1.validate_cspec_registry(overlay, authority, rule, schemas,
                             authority_path=e1.AUTHORITY_PATH, rule_registry_path=RULE_PATH)


def validate_derivation(overlay: dict, baseline: dict, rule: dict,
                        authority: dict, schemas: e1.ContractSchemas) -> None:
    require(e1.canonical_sha256(baseline) == FROZEN_OVERLAY_CANONICAL_SHA256,
            "FROZEN_OVERLAY_BASELINE_CHANGED")
    expected = copy.deepcopy(baseline)
    expected.update(registryId=OVERLAY_ID, baseRuleRegistryId=RULE_ID,
                    baseRuleRegistrySha256=RULE_FILE_SHA256)
    require(overlay == expected, "CSPEC_DERIVATION_EXCEEDS_THREE_FIELDS")
    validate_pairing(overlay, rule, authority, schemas)


def verify_resources() -> dict:
    schemas = e1.ContractSchemas()
    authority = pinned_json(e1.AUTHORITY_PATH, AUTHORITY_FILE_SHA256, AUTHORITY_CANONICAL_SHA256)
    rule = pinned_json(RULE_PATH, RULE_FILE_SHA256, RULE_CANONICAL_SHA256)
    frozen = pinned_json(e1.CSPEC_REGISTRY_PATH, FROZEN_OVERLAY_FILE_SHA256, FROZEN_OVERLAY_CANONICAL_SHA256)
    l1a = pinned_json(L1A_OVERLAY_PATH, L1A_OVERLAY_FILE_SHA256, L1A_OVERLAY_CANONICAL_SHA256)
    overlay = pinned_json(OVERLAY_PATH, OVERLAY_FILE_SHA256, OVERLAY_CANONICAL_SHA256)
    e1.validate_authority_catalog(authority, schemas)
    c1.validate_successor_registry(rule, e1.load_json(c1.PREDECESSOR_REGISTRY_PATH))
    validate_derivation(overlay, frozen, rule, authority, schemas)
    require({key for key in frozen if frozen[key] != l1a[key]} == ALLOWED_CHANGED_FIELDS,
            "L1A_OVERLAY_BASELINE_CHANGED")
    require(set(l1a) == set(frozen), "L1A_OVERLAY_BASELINE_CHANGED")
    require({key for key in frozen if frozen[key] != overlay[key]} == ALLOWED_CHANGED_FIELDS,
            "CSPEC_DERIVATION_FIELDS_CHANGED")
    require(overlay["asOf"] == rule["asOf"] == authority["asOf"] == AS_OF, "REGISTRY_AS_OF_CHANGED")
    return {
        "status": "PASS", "asOf": AS_OF, "changedFields": sorted(ALLOWED_CHANGED_FIELDS),
        "authority": {"catalogId": authority["catalogId"], "fileSha256": AUTHORITY_FILE_SHA256,
                      "canonicalSha256": AUTHORITY_CANONICAL_SHA256},
        "ruleRegistry": {"registryId": RULE_ID, "fileSha256": RULE_FILE_SHA256,
                         "canonicalSha256": RULE_CANONICAL_SHA256},
        "overlayRegistry": {"registryId": OVERLAY_ID, "fileSha256": OVERLAY_FILE_SHA256,
                            "canonicalSha256": OVERLAY_CANONICAL_SHA256,
                            "baseRuleRegistryId": overlay["baseRuleRegistryId"],
                            "baseRuleRegistrySha256": overlay["baseRuleRegistrySha256"]},
        "selectionPolicyId": overlay["selectionPolicy"]["policyId"],
        "unchangedOverlayCount": len(overlay["overlays"]),
        "overlayStates": [{key: row[key] for key in (
            "overlayId", "cspecId", "version", "implementationStatus", "selectionStatus")}
            for row in overlay["overlays"]],
        "frozenPolicyAndOverlaysUnchanged": True,
        "clinicalValidation": False,
    }


def selection_registry(row: dict, schemas: e1.ContractSchemas) -> tuple[dict, str, str]:
    formal = e1.load_json(OVERLAY_PATH)
    artifact = row.get("overlayRegistryArtifact")
    if artifact is None:
        return formal, OVERLAY_FILE_SHA256, "PINNED_PRODUCTION_REGISTRY"
    require(row.get("dataClass") == "SYNTHETIC" and isinstance(artifact, str),
            "SYNTHETIC_REGISTRY_DECLARATION_REQUIRED")
    registry = json.loads(artifact)
    schemas.validate(registry, "urn:varvis:schema:cspec-overlay-registry-v1")
    require(registry["registryId"].startswith("cspec-overlay-registry-synthetic-e4-a2a"),
            "SYNTHETIC_REGISTRY_ID_REQUIRED")
    for field in ("authorityCatalogId", "authorityCatalogSha256", "baseRuleRegistryId",
                  "baseRuleRegistrySha256", "authorityCutoff", "asOf", "selectionPolicy"):
        require(registry[field] == formal[field], "SYNTHETIC_REGISTRY_PAIRING_CHANGED")
    return registry, e1.sha256_bytes(artifact.encode("utf-8")), "SYNTHETIC_BRANCH_FIXTURE"


def verify_preflight_rows(rows: list[dict]) -> dict:
    require(isinstance(rows, list) and bool(rows), "PREFLIGHT_ROWS_REQUIRED")
    schemas = e1.ContractSchemas()
    conflict_validator = e1.Draft202012Validator(
        {"$ref": "urn:varvis:schema:common-definitions-v1#/$defs/conflictRecord"},
        registry=schemas.registry, format_checker=e1.FormatChecker())
    identities, production_outcomes, synthetic_outcomes = [], Counter(), Counter()
    for ordinal, row in enumerate(rows):
        context, selection = row["context"], row["selection"]
        schemas.validate(context, "urn:varvis:schema:evidence-context-v2")
        e1._assert_context_scope(context)
        variant = context["variant"]
        require(variant["assemblyLineage"]["mappingStatus"] == "NATIVE"
                and all(variant[field]["assembly"] == "GRCh38" for field in (
                    "inputAllele", "normalizedAllele", "evidenceQueryAllele"))
                and variant["evidenceQueryAllele"] == variant["normalizedAllele"], "NATIVE_GRCH38_REQUIRED")
        require(context["dataClass"] == "SYNTHETIC", "SYNTHETIC_SMOKE_CONTEXT_REQUIRED")
        require(context["variant"]["alleleId"] == e1.content_id(
            "allele-sha256-", e1.allele_identity_projection(context)), "ALLELE_ID_MISMATCH")
        require(context["contextId"] == e1.content_id(
            "context-sha256-", e1.context_identity_projection(context)), "CONTEXT_ID_MISMATCH")
        registry, registry_sha, source_scope = selection_registry(row, schemas)
        schemas.validate(selection, "urn:varvis:schema:cspec-selection-v1")
        require(selection["contextId"] == context["contextId"], "SELECTION_CONTEXT_MISMATCH")
        require(selection["overlayRegistryId"] == registry["registryId"]
                and selection["overlayRegistrySha256"] == registry_sha, "SELECTION_REGISTRY_IDENTITY_MISMATCH")
        require(selection["policyId"] == registry["selectionPolicy"]["policyId"], "SELECTION_POLICY_MISMATCH")
        require(selection["selectionId"] == e1.content_id(
            "cspec-selection-sha256-", e1.cspec_selection_identity_projection(selection)), "SELECTION_ID_MISMATCH")
        overlays = {overlay["overlayId"]: overlay for overlay in registry["overlays"]}
        require(len(overlays) == len(registry["overlays"]), "OVERLAY_ID_AMBIGUOUS")
        candidates = selection["candidateOverlayIds"]
        require(all(candidate in overlays for candidate in candidates), "SELECTION_CANDIDATE_MISSING")
        exact = [overlay_id for overlay_id, overlay in overlays.items() if e1._overlay_context_match(overlay, context)]
        if not exact:
            require(not candidates, "NO_EXACT_MATCH_CANDIDATES_MUST_BE_EMPTY")
            outcome = "ZERO_MATCH"
        elif len(exact) == 1:
            require(candidates == exact, "SINGLE_EXACT_CANDIDATE_MISMATCH")
            outcome = ("SINGLE_SELECTABLE_MATCH" if overlays[exact[0]]["selectionStatus"] == "SELECTABLE"
                       else "SINGLE_NON_SELECTABLE_MATCH")
        else:
            require(set(candidates) == set(exact), "MULTIPLE_EXACT_CANDIDATES_MISMATCH")
            outcome = "MULTIPLE_EXACT_MATCHES"
        require(selection["outcome"] == outcome, "SELECTION_OUTCOME_MISMATCH")
        policy_key = {"ZERO_MATCH": "zeroMatch", "SINGLE_SELECTABLE_MATCH": "singleSelectableMatch",
                      "SINGLE_NON_SELECTABLE_MATCH": "singleNonSelectableMatch",
                      "MULTIPLE_EXACT_MATCHES": "multipleExactMatches", "CONTEXT_CONFLICT": "contextConflict"}[outcome]
        policy = registry["selectionPolicy"]["outcomes"][policy_key]
        require(selection.get("reasonCode") == policy.get("reasonCode"), "SELECTION_REASON_MISMATCH")
        if outcome == "SINGLE_SELECTABLE_MATCH":
            require(selection["selectedOverlayId"] == exact[0]
                    and selection["selectedOverlaySha256"] == overlays[exact[0]]["snapshotSha256"],
                    "SELECTED_OVERLAY_IDENTITY_MISMATCH")
        reviews, conflicts = row["reviewRecords"], row["conflicts"]
        require(isinstance(reviews, list) and isinstance(conflicts, list), "EVENT_ARRAYS_REQUIRED")
        review_ids, conflict_ids = [], []
        for review in reviews:
            validate_review_envelope(review)
            schemas.validate(review, "urn:varvis:schema:review-record-v2")
            require(review["reviewRecordId"] == e1.content_id(
                "review-sha256-", e1.review_identity_projection(review)), "REVIEW_ID_MISMATCH")
            require(review["contextId"] == context["contextId"]
                    and review["subjectType"] == "CSPEC_SELECTION"
                    and review["subjectId"] == selection["selectionId"], "REVIEW_SUBJECT_MISMATCH")
            require((review["eventType"], review["status"], review["decision"]) == ("REQUESTED", "REQUIRED", "NONE")
                    and review["reasonCode"] == selection.get("reasonCode"), "REVIEW_EVENT_STATE_MISMATCH")
            review_ids.append(review["reviewRecordId"])
        for conflict in conflicts:
            validate_conflict_envelope(conflict)
            require(conflict_validator.is_valid(conflict), "CONFLICT_SCHEMA_INVALID")
            require(conflict["conflictId"] == e1.content_id(
                "conflict-sha256-", e1.conflict_identity_projection(conflict)), "CONFLICT_ID_MISMATCH")
            require(conflict["resolutionStatus"] == "OPEN"
                    and conflict["conflictType"] == "RULE_PROFILE_MULTIPLE_MATCH"
                    and ("reasonCode" not in conflict or conflict["reasonCode"] == selection.get("reasonCode")),
                    "CONFLICT_EVENT_STATE_MISMATCH")
            require(set(conflict["participantIds"]) == {selection["selectionId"], *candidates},
                    "CONFLICT_PARTICIPANT_MISMATCH")
            conflict_ids.append(conflict["conflictId"])
        require(len(review_ids) == len(set(review_ids)) and len(conflict_ids) == len(set(conflict_ids)),
                "EVENT_IDS_AMBIGUOUS")
        require(set(selection["review"]["reviewRecordIds"]) == set(review_ids), "REVIEW_REFERENCE_MISMATCH")
        require(set(selection["conflict"]["conflictIds"]) == set(conflict_ids), "CONFLICT_REFERENCE_MISMATCH")
        review_required = policy["reviewConstraint"] == "REQUIRED"
        conflict_required = outcome in {"CONTEXT_CONFLICT", "MULTIPLE_EXACT_MATCHES"}
        require(bool(reviews) == review_required and bool(conflicts) == conflict_required, "EVENT_PRESENCE_MISMATCH")
        require(selection["review"]["status"] == ("REQUIRED" if review_required else "NOT_REQUIRED")
                and selection["review"]["decision"] == "NONE", "REVIEW_SUMMARY_STATE_MISMATCH")
        counts = production_outcomes if source_scope == "PINNED_PRODUCTION_REGISTRY" else synthetic_outcomes
        counts.update([outcome])
        identities.append({
            "ordinal": ordinal, "sourceScope": source_scope, "contextId": context["contextId"],
            "alleleId": context["variant"]["alleleId"], "hgncId": context["gene"]["hgncId"],
            "mondoId": context["disease"]["mondoId"], "selectionId": selection["selectionId"], "outcome": outcome,
            "overlayRegistryId": registry["registryId"], "overlayRegistryFileSha256": registry_sha,
            "overlayRegistryCanonicalSha256": e1.canonical_sha256(registry),
            "candidateOverlayIds": candidates, "selectedOverlayId": selection.get("selectedOverlayId"),
            "reviewRecordIds": review_ids, "conflictIds": conflict_ids,
        })
    return {"status": "PASS", "contextCount": len(rows), "dataClass": "SYNTHETIC",
            "nativeScope": "GRCh38_NATIVE_SMALL_VARIANT_ONLY",
            "productionSnapshotOutcomeCounts": dict(sorted(production_outcomes.items())),
            "syntheticBranchCoverage": dict(sorted(synthetic_outcomes.items())),
            "independentContextAlleleSelectionAndEventIdsPassed": True,
            "exactSubjectsAndReferencesPassed": True, "clinicalValidation": False, "identities": identities}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contexts", type=Path, action="append", default=[],
                        help="Repeatable Java smoke array; synthetic registry artifacts must be declared")
    parser.add_argument("--summary-output", type=Path)
    args = parser.parse_args()
    summary = verify_resources()
    if args.contexts:
        summary["preflightChecks"] = [verify_preflight_rows(e1.load_json(path)) for path in args.contexts]
    text = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    if args.summary_output:
        args.summary_output.write_text(text, encoding="utf-8", newline="\n")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
