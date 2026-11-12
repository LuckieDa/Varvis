"""Focused checks for the authorized three-field E4-A2A registry derivation."""

from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from scripts.e4_a2a import verify_cspec_preflight as verify
from scripts.evidence import acmg_evidence_v2_contract as e1


class CspecPreflightRegistryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schemas = e1.ContractSchemas()
        cls.overlay = e1.load_json(verify.OVERLAY_PATH)
        cls.baseline = e1.load_json(e1.CSPEC_REGISTRY_PATH)
        cls.rule = e1.load_json(verify.RULE_PATH)
        cls.authority = e1.load_json(e1.AUTHORITY_PATH)

    def test_committed_registry_pair_preserves_frozen_policy_and_metadata_only_overlay(self) -> None:
        result = verify.verify_resources()
        self.assertEqual("PASS", result["status"])
        self.assertEqual("2026-08-31T11:20:00Z", result["asOf"])
        self.assertEqual(verify.ALLOWED_CHANGED_FIELDS, set(result["changedFields"]))
        self.assertEqual("METADATA_ONLY", self.overlay["overlays"][0]["implementationStatus"])
        self.assertEqual("NOT_SELECTABLE", self.overlay["overlays"][0]["selectionStatus"])
        self.assertEqual([], self.overlay["overlays"][0]["criterionSpecifications"])

    def test_protected_authority_asof_policy_and_overlay_fields_cannot_drift(self) -> None:
        for field in ("authorityCatalogId", "authorityCatalogSha256", "authorityCutoff", "asOf",
                      "selectionPolicy", "overlays"):
            changed = copy.deepcopy(self.overlay)
            changed[field] = "drift"
            with self.subTest(field=field), self.assertRaisesRegex(e1.ContractError, "EXCEEDS_THREE_FIELDS"):
                verify.validate_derivation(changed, self.baseline, self.rule, self.authority, self.schemas)

    def test_binding_requires_c1_id_and_file_hash_not_canonical_hash(self) -> None:
        for field, value in (("baseRuleRegistryId", "acmg-rule-registry-e3-l1a-2026-09-05"),
                             ("baseRuleRegistrySha256", verify.RULE_CANONICAL_SHA256)):
            changed = copy.deepcopy(self.overlay)
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(e1.ContractError):
                verify.validate_pairing(changed, self.rule, self.authority, self.schemas)

    def test_changed_rule_or_authority_or_baseline_is_not_a_valid_pair(self) -> None:
        changed_rule = copy.deepcopy(self.rule)
        changed_rule["asOf"] = "2026-09-05T00:00:00Z"
        with self.assertRaisesRegex(e1.ContractError, "RULE_IDENTITY_CHANGED"):
            verify.validate_pairing(self.overlay, changed_rule, self.authority, self.schemas)
        changed_authority = copy.deepcopy(self.authority)
        changed_authority["asOf"] = "2026-09-05T00:00:00Z"
        with self.assertRaisesRegex(e1.ContractError, "AUTHORITY_IDENTITY_CHANGED"):
            verify.validate_pairing(self.overlay, self.rule, changed_authority, self.schemas)
        changed_baseline = copy.deepcopy(self.baseline)
        changed_baseline["overlays"][0]["selectionStatus"] = "SELECTABLE"
        with self.assertRaisesRegex(e1.ContractError, "BASELINE_CHANGED"):
            verify.validate_derivation(self.overlay, changed_baseline, self.rule, self.authority, self.schemas)

    def test_file_hash_remains_distinct_from_canonical_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "reformatted.json"
            raw = verify.OVERLAY_PATH.read_bytes() + b"\n"
            path.write_bytes(raw)
            self.assertEqual(verify.OVERLAY_CANONICAL_SHA256, e1.canonical_sha256(e1.load_json(path)))
            with self.assertRaisesRegex(e1.ContractError, "FILE_SHA256_MISMATCH"):
                verify.pinned_json(path, verify.OVERLAY_FILE_SHA256, verify.OVERLAY_CANONICAL_SHA256)
            with self.assertRaisesRegex(e1.ContractError, "CANONICAL_SHA256_MISMATCH"):
                verify.pinned_json(path, e1.sha256_bytes(raw), "0" * 64)

    def preflight_row(self, outcome: str, synthetic_registry: bool = False) -> dict:
        context = e1.load_json(e1.SYNTHETIC_ROOT / "valid-evidence-bundle.json")["context"]
        overlay = copy.deepcopy(self.overlay)
        if outcome in {"SINGLE_NON_SELECTABLE_MATCH", "MULTIPLE_EXACT_MATCHES"}:
            selector = overlay["overlays"][0]["selector"]
            context["gene"]["hgncId"] = selector["hgncId"]
            context["disease"]["mondoId"] = selector["mondoId"]
            context["transcript"].update(selector["transcript"])
        context["contextId"] = e1.content_id("context-sha256-", e1.context_identity_projection(context))
        row = {"context": context, "reviewRecords": [], "conflicts": []}
        registry_sha = verify.OVERLAY_FILE_SHA256
        if synthetic_registry:
            overlay["registryId"] = "cspec-overlay-registry-synthetic-e4-a2a-python-test"
            if outcome == "MULTIPLE_EXACT_MATCHES":
                second = copy.deepcopy(overlay["overlays"][0])
                second["overlayId"] = "cspec-overlay-synthetic-gn007-second"
                overlay["overlays"].append(second)
            artifact = json.dumps(overlay, ensure_ascii=False, separators=(",", ":"))
            row.update(dataClass="SYNTHETIC", overlayRegistryArtifact=artifact)
            registry_sha = e1.sha256_bytes(artifact.encode("utf-8"))
        selection = {
            "schemaVersion": "cspec-selection-v1", "canonicalizationProfile": "varvis-json-c14n-v1",
            "contextId": context["contextId"], "overlayRegistryId": overlay["registryId"],
            "overlayRegistrySha256": registry_sha, "policyId": "CSPEC_EXACT_CONTEXT_SELECTION_V1",
            "outcome": outcome, "candidateOverlayIds": [] if outcome == "ZERO_MATCH"
            else [item["overlayId"] for item in overlay["overlays"]],
            "conflict": {"status": "NONE", "conflictIds": []},
            "review": {"status": "NOT_REQUIRED", "decision": "NONE", "reviewRecordIds": []},
        }
        if outcome != "ZERO_MATCH":
            selection["reasonCode"] = {
                "SINGLE_NON_SELECTABLE_MATCH": "CSPEC_MATCH_NOT_EXECUTABLE",
                "MULTIPLE_EXACT_MATCHES": "CSPEC_MULTIPLE_EXACT_MATCHES",
                "CONTEXT_CONFLICT": "CSPEC_CONTEXT_CONFLICT",
            }[outcome]
            selection["review"]["status"] = "REQUIRED"
        selection["selectionId"] = e1.content_id("cspec-selection-sha256-", e1.cspec_selection_identity_projection(selection))
        if outcome != "ZERO_MATCH":
            review = {"schemaVersion": "review-record-v2", "contextId": context["contextId"],
                      "subjectType": "CSPEC_SELECTION", "subjectId": selection["selectionId"],
                      "eventType": "REQUESTED", "status": "REQUIRED", "decision": "NONE",
                      "actor": {"actorId": "reviewer-synthetic-e4-a2a", "role": "CURATOR"},
                      "reasonCode": selection["reasonCode"], "createdAt": verify.AS_OF}
            review["reviewRecordId"] = e1.content_id("review-sha256-", e1.review_identity_projection(review))
            row["reviewRecords"].append(review)
            selection["review"]["reviewRecordIds"].append(review["reviewRecordId"])
        if outcome in {"CONTEXT_CONFLICT", "MULTIPLE_EXACT_MATCHES"}:
            conflict = {"conflictType": "RULE_PROFILE_MULTIPLE_MATCH", "resolutionStatus": "OPEN",
                        "participantIds": [selection["selectionId"], *selection["candidateOverlayIds"]],
                        "reasonCode": selection["reasonCode"], "detectedAt": verify.AS_OF}
            conflict["conflictId"] = e1.content_id("conflict-sha256-", e1.conflict_identity_projection(conflict))
            row["conflicts"].append(conflict)
            selection["conflict"] = {"status": "PRESENT", "conflictIds": [conflict["conflictId"]]}
        row["selection"] = selection
        return row

    def test_formal_and_declared_synthetic_preflight_rows_are_verified_and_counted_separately(self) -> None:
        rows = [self.preflight_row("ZERO_MATCH"), self.preflight_row("SINGLE_NON_SELECTABLE_MATCH"),
                self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)]
        result = verify.verify_preflight_rows(rows)
        self.assertEqual({"ZERO_MATCH": 1, "SINGLE_NON_SELECTABLE_MATCH": 1}, result["productionSnapshotOutcomeCounts"])
        self.assertEqual({"MULTIPLE_EXACT_MATCHES": 1}, result["syntheticBranchCoverage"])
        self.assertEqual(3, result["contextCount"])

    def test_independent_ids_and_event_subject_references_detect_tampering(self) -> None:
        row = self.preflight_row("SINGLE_NON_SELECTABLE_MATCH")
        row["context"]["gene"]["hgncId"] = "HGNC:2"
        with self.assertRaisesRegex(e1.ContractError, "CONTEXT_ID_MISMATCH"):
            verify.verify_preflight_rows([row])
        row = self.preflight_row("SINGLE_NON_SELECTABLE_MATCH")
        row["selection"]["selectionId"] = "cspec-selection-sha256-" + "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "SELECTION_ID_MISMATCH"):
            verify.verify_preflight_rows([row])
        row = self.preflight_row("SINGLE_NON_SELECTABLE_MATCH")
        review = row["reviewRecords"][0]
        review["subjectId"] = "cspec-selection-sha256-" + "0" * 64
        review["reviewRecordId"] = e1.content_id("review-sha256-", e1.review_identity_projection(review))
        with self.assertRaisesRegex(e1.ContractError, "REVIEW_SUBJECT_MISMATCH"):
            verify.verify_preflight_rows([row])
        row = self.preflight_row("SINGLE_NON_SELECTABLE_MATCH")
        row["selection"]["review"]["reviewRecordIds"] = ["review-sha256-" + "0" * 64]
        with self.assertRaisesRegex(e1.ContractError, "REVIEW_REFERENCE_MISMATCH"):
            verify.verify_preflight_rows([row])

    def test_conflict_participants_must_match_the_exact_selection_and_candidates(self) -> None:
        row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
        conflict = row["conflicts"][0]
        conflict["participantIds"][0] = "cspec-selection-sha256-" + "0" * 64
        conflict["conflictId"] = e1.content_id("conflict-sha256-", e1.conflict_identity_projection(conflict))
        with self.assertRaisesRegex(e1.ContractError, "CONFLICT_PARTICIPANT_MISMATCH"):
            verify.verify_preflight_rows([row])

    def test_synthetic_registry_requires_explicit_scope_exact_artifact_hash_and_real_pairing(self) -> None:
        row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
        row.pop("dataClass")
        with self.assertRaisesRegex(e1.ContractError, "SYNTHETIC_REGISTRY_DECLARATION_REQUIRED"):
            verify.verify_preflight_rows([row])
        row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
        row["overlayRegistryArtifact"] += "\n"
        with self.assertRaisesRegex(e1.ContractError, "SELECTION_REGISTRY_IDENTITY_MISMATCH"):
            verify.verify_preflight_rows([row])
        row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
        artifact = json.loads(row["overlayRegistryArtifact"])
        artifact["baseRuleRegistrySha256"] = verify.RULE_CANONICAL_SHA256
        row["overlayRegistryArtifact"] = json.dumps(artifact)
        with self.assertRaisesRegex(e1.ContractError, "SYNTHETIC_REGISTRY_PAIRING_CHANGED"):
            verify.verify_preflight_rows([row])

    def test_automatic_preflight_rejects_partial_matches_or_fabricated_candidates(self) -> None:
        row = self.preflight_row("CONTEXT_CONFLICT", synthetic_registry=True)
        with self.assertRaisesRegex(e1.ContractError, "NO_EXACT_MATCH_CANDIDATES_MUST_BE_EMPTY"):
            verify.verify_preflight_rows([row])
        for field, key, value in (("gene", "hgncId", "HGNC:2"),
                                  ("disease", "mondoId", "MONDO:0000002"),
                                  ("transcript", "version", 6)):
            row = self.preflight_row("SINGLE_NON_SELECTABLE_MATCH")
            row["context"][field][key] = value
            row["context"]["contextId"] = e1.content_id("context-sha256-", e1.context_identity_projection(row["context"]))
            row["selection"]["contextId"] = row["context"]["contextId"]
            row["selection"]["selectionId"] = e1.content_id(
                "cspec-selection-sha256-", e1.cspec_selection_identity_projection(row["selection"]))
            with self.subTest(field=field), self.assertRaisesRegex(e1.ContractError, "NO_EXACT_MATCH_CANDIDATES_MUST_BE_EMPTY"):
                verify.verify_preflight_rows([row])
        row = self.preflight_row("SINGLE_NON_SELECTABLE_MATCH")
        row["selection"]["candidateOverlayIds"] = ["cspec-overlay-nonexistent"]
        row["selection"]["selectionId"] = e1.content_id(
            "cspec-selection-sha256-", e1.cspec_selection_identity_projection(row["selection"]))
        with self.assertRaisesRegex(e1.ContractError, "SELECTION_CANDIDATE_MISSING"):
            verify.verify_preflight_rows([row])

    def test_conflict_reason_is_optional_but_must_agree_when_present(self) -> None:
        for reason in (None, "DIFFERENT_SYNTHETIC_REASON"):
            row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
            conflict = row["conflicts"][0]
            if reason is None:
                conflict.pop("reasonCode")
            else:
                conflict["reasonCode"] = reason
            conflict["conflictId"] = e1.content_id("conflict-sha256-", e1.conflict_identity_projection(conflict))
            row["selection"]["conflict"]["conflictIds"] = [conflict["conflictId"]]
            if reason is None:
                self.assertEqual("PASS", verify.verify_preflight_rows([row])["status"])
            else:
                with self.assertRaisesRegex(e1.ContractError, "CONFLICT_EVENT_STATE_MISMATCH"):
                    verify.verify_preflight_rows([row])

    def test_grch37_native_or_different_query_allele_is_outside_a2a_scope(self) -> None:
        row = self.preflight_row("ZERO_MATCH")
        for field in ("inputAllele", "normalizedAllele", "evidenceQueryAllele"):
            row["context"]["variant"][field]["assembly"] = "GRCh37"
        e1._assert_context_scope(row["context"])
        with self.assertRaisesRegex(e1.ContractError, "NATIVE_GRCH38_REQUIRED"):
            verify.verify_preflight_rows([row])
        row = self.preflight_row("ZERO_MATCH")
        row["context"]["variant"]["evidenceQueryAllele"]["position"] += 1
        with self.assertRaisesRegex(e1.ContractError, "NATIVE evidence query allele must equal"):
            verify.verify_preflight_rows([row])

    def stamp_events(self, row: dict) -> None:
        for review in row["reviewRecords"]:
            review["reviewRecordId"] = e1.content_id("review-sha256-", e1.review_identity_projection(review))
        for conflict in row["conflicts"]:
            conflict["conflictId"] = e1.content_id("conflict-sha256-", e1.conflict_identity_projection(conflict))
        row["selection"]["review"]["reviewRecordIds"] = [item["reviewRecordId"] for item in row["reviewRecords"]]
        row["selection"]["conflict"]["conflictIds"] = [item["conflictId"] for item in row["conflicts"]]

    def test_explicit_event_utc_timestamps_remain_unchanged(self) -> None:
        for timestamp in ("2026-09-05T00:00:00Z", "2026-09-05T00:00:00+00:00",
                          "2026-09-05T00:00:00.123456789Z"):
            row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
            row["reviewRecords"][0]["createdAt"] = timestamp
            row["conflicts"][0]["detectedAt"] = timestamp
            self.stamp_events(row)
            original = copy.deepcopy(row)
            self.assertEqual("PASS", verify.verify_preflight_rows([row])["status"])
            self.assertEqual(original, row)

    def test_invalid_event_times_are_rejected_even_with_valid_recomputed_ids(self) -> None:
        invalid_times = ("2026-02-30T00:00:00Z", "0000-09-05T00:00:00Z", "2026-09-05",
                         "2026-09-05T00:00:00", "2026-09-05T08:00:00+08:00",
                         "2026-09-05T00:00:00-00:00", "2026-09-05T00:00:00+00:00:00",
                         "2026-09-05 00:00:00Z", "2026-09-05T24:00:00Z",
                         "2026-09-05T00:00:00.1234567890Z")
        for array, field, reason in (("reviewRecords", "createdAt", "CSPEC_REVIEW_TIME_INVALID"),
                                      ("conflicts", "detectedAt", "CSPEC_CONFLICT_TIME_INVALID")):
            for timestamp in invalid_times:
                row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
                row[array][0][field] = timestamp
                self.stamp_events(row)
                with self.subTest(field=field, timestamp=timestamp), self.assertRaisesRegex(e1.ContractError, reason):
                    verify.verify_preflight_rows([row])

    def test_review_extensions_and_lifecycle_states_are_rejected_after_rehash(self) -> None:
        mutations = ({"sourceLocators": []}, {"previousReviewRecordId": "review-sha256-" + "a" * 64},
                     {"override": {"field": "CSPEC_SELECTION", "previousValue": "NONE",
                                   "newValue": "SELECTED", "rationaleCode": "SYNTHETIC_OVERRIDE"}},
                     {"eventType": "STARTED"}, {"status": "IN_PROGRESS"},
                     {"eventType": "DECIDED", "status": "COMPLETED", "decision": "ACCEPTED"})
        for mutation in mutations:
            row = self.preflight_row("SINGLE_NON_SELECTABLE_MATCH")
            row["reviewRecords"][0].update(mutation)
            self.stamp_events(row)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(e1.ContractError, "CSPEC_REVIEW_EVENT_UNSUPPORTED"):
                verify.verify_preflight_rows([row])

    def test_unknown_review_actor_and_conflict_fields_are_rejected_after_rehash(self) -> None:
        for target, reason in (("review", "CSPEC_REVIEW_FIELD_UNKNOWN"),
                               ("actor", "CSPEC_REVIEW_FIELD_UNKNOWN"),
                               ("conflict", "CSPEC_CONFLICT_FIELD_UNKNOWN")):
            row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
            node = (row["reviewRecords"][0]["actor"] if target == "actor" else row["reviewRecords"][0]
                    if target == "review" else row["conflicts"][0])
            node["unexpectedField"] = "SYNTHETIC_UNKNOWN_VALUE"
            self.stamp_events(row)
            with self.subTest(target=target), self.assertRaisesRegex(e1.ContractError, reason):
                verify.verify_preflight_rows([row])

    def test_conflict_resolution_and_other_types_are_rejected_after_rehash(self) -> None:
        for mutation in ({"resolutionStatus": "RESOLVED"},
                         {"resolutionReviewRecordId": "review-sha256-" + "a" * 64},
                         {"conflictType": "SOURCE_VALUE_DISAGREEMENT"}):
            row = self.preflight_row("MULTIPLE_EXACT_MATCHES", synthetic_registry=True)
            row["conflicts"][0].update(mutation)
            self.stamp_events(row)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(e1.ContractError, "CSPEC_CONFLICT_EVENT_UNSUPPORTED"):
                verify.verify_preflight_rows([row])


if __name__ == "__main__":
    unittest.main()
