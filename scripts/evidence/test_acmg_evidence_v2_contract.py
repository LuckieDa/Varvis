#!/usr/bin/env python3
"""Contract-engineering tests for the synthetic ACMG evidence v2 E1 freeze.

These tests exercise schemas and deterministic safety invariants only. They do
not validate clinical correctness, resources, patients, or diagnostic yield.
"""

from __future__ import annotations

import copy
import sys
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

import acmg_evidence_v2_contract as contract  # noqa: E402


class AcmgEvidenceV2ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schemas = contract.ContractSchemas()
        cls.catalog = contract.load_json(contract.AUTHORITY_PATH)
        cls.rule_registry = contract.load_json(contract.RULE_REGISTRY_PATH)
        cls.cspec_registry = contract.load_json(contract.CSPEC_REGISTRY_PATH)
        cls.valid_bundle = contract.load_json(
            contract.SYNTHETIC_ROOT / "valid-evidence-bundle.json"
        )

    def bundle(self) -> dict[str, Any]:
        return copy.deepcopy(self.valid_bundle)

    def rules(self) -> dict[str, Any]:
        return copy.deepcopy(self.rule_registry)

    def catalog_copy(self) -> dict[str, Any]:
        return copy.deepcopy(self.catalog)

    def cspec(self) -> dict[str, Any]:
        return copy.deepcopy(self.cspec_registry)

    @staticmethod
    def criterion(registry: dict[str, Any], code: str) -> dict[str, Any]:
        return next(item for item in registry["criteria"] if item["code"] == code)

    def validate_bundle(
        self,
        bundle: dict[str, Any],
        *,
        rules: dict[str, Any] | None = None,
        cspec: dict[str, Any] | None = None,
    ) -> None:
        contract.validate_bundle(
            bundle,
            self.catalog,
            rules or self.rule_registry,
            cspec or self.cspec_registry,
            self.schemas,
        )

    def completed_criterion_result(
        self,
        bundle: dict[str, Any],
        code: str,
        evidence_index: int,
        *,
        result: str = "MET",
    ) -> dict[str, Any]:
        criterion = self.criterion(self.rule_registry, code)
        evaluation = bundle["criterionEvaluations"][0]
        evidence_id = bundle["atomicEvidence"][evidence_index]["evidenceId"]
        current = criterion["currentGeneral"]
        base_strength = current["strength"]
        if base_strength == "NONE":
            base_strength = criterion["original2015"]["strength"]

        evaluation["criterionId"] = criterion["criterionId"]
        evaluation["ruleBinding"] = {
            "ruleRegistryId": self.rule_registry["registryId"],
            "ruleRegistrySha256": contract.sha256_file(contract.RULE_REGISTRY_PATH),
            "currentGeneralDesignation": current["designation"],
            "authoritySourceIds": copy.deepcopy(current["authoritySourceIds"]),
            "cspecSelectionId": bundle["cspecSelection"]["selectionId"],
        }
        evaluation["criterionApplicability"] = {"status": "APPLICABLE"}
        evaluation["evaluationResult"] = {"status": result}
        evaluation["baseStrength"] = base_strength
        evaluation["appliedStrength"] = base_strength if result == "MET" else "NONE"
        evaluation["evidenceIds"] = [evidence_id]
        evaluation["deterministicTrace"] = {
            "executionStatus": "COMPLETED",
            "evaluatorId": "synthetic-negative-test",
            "evaluatorVersion": "1.0.0",
            "algorithmId": "synthetic-negative-test-algorithm",
            "algorithmVersion": "1.0.0",
            "inputEvidenceIds": [evidence_id],
            "parameters": [],
            "steps": [
                {
                    "stepCode": "SYNTHETIC_NEGATIVE_TEST",
                    "outcome": "TRUE",
                    "inputEvidenceIds": [evidence_id],
                }
            ],
            "traceSha256": "0" * 64,
        }
        evaluation["conflict"] = {"status": "NONE", "conflictIds": []}
        evaluation["review"] = {
            "status": "NOT_REQUIRED",
            "decision": "NONE",
            "reviewRecordIds": [],
        }
        return contract.rehash_bundle(bundle)

    def completed_combination(
        self,
        bundle: dict[str, Any],
        *,
        result: str,
        include_reason: bool,
    ) -> dict[str, Any]:
        combination = bundle["combination"]
        combination["executionStatus"] = "COMPLETED"
        combination["result"] = result
        if include_reason:
            combination["reasonCode"] = "SYNTHETIC_ABSTENTION"
        else:
            combination.pop("reasonCode", None)
        combination["combinationSpecification"] = {
            "specificationId": "synthetic-combination-test",
            "version": "1.0.0",
            "authoritySourceIds": [self.catalog["sources"][0]["authoritySourceId"]],
            "specificationSha256": "a" * 64,
        }
        combination["trace"] = {
            "engineId": "synthetic-combination-engine",
            "engineVersion": "1.0.0",
            "parameters": [],
            "traceSha256": "0" * 64,
        }
        return contract.rehash_bundle(bundle)

    def single_nonselectable_cspec_bundle(self) -> dict[str, Any]:
        bundle = self.bundle()
        overlay = self.cspec_registry["overlays"][0]
        selector = overlay["selector"]
        context = bundle["context"]
        context["gene"] = {"hgncId": selector["hgncId"], "symbol": "CDH1"}
        context["disease"] = {
            "mondoId": selector["mondoId"],
            "label": "Synthetic CDH1-associated disorder",
        }
        context["moi"] = selector["moi"]
        context["transcript"]["accession"] = selector["transcript"]["accession"]
        context["transcript"]["version"] = selector["transcript"]["version"]

        selection = bundle["cspecSelection"]
        selection["outcome"] = "SINGLE_NON_SELECTABLE_MATCH"
        selection["candidateOverlayIds"] = [overlay["overlayId"]]
        selection["reasonCode"] = "CSPEC_MATCH_NOT_EXECUTABLE"
        for evaluation in bundle["criterionEvaluations"]:
            evaluation["criterionApplicability"]["reasonCode"] = (
                "CSPEC_MATCH_NOT_EXECUTABLE"
            )
            evaluation["evaluationResult"]["reasonCode"] = (
                "CSPEC_MATCH_NOT_EXECUTABLE"
            )
            evaluation["deterministicTrace"]["reasonCode"] = (
                "CSPEC_MATCH_NOT_EXECUTABLE"
            )
        placeholder_review_id = "review-sha256-" + "0" * 64
        selection["review"] = {
            "status": "REQUIRED",
            "decision": "NONE",
            "reviewRecordIds": [placeholder_review_id],
        }
        bundle["reviewRecords"] = [
            {
                "schemaVersion": "review-record-v2",
                "reviewRecordId": placeholder_review_id,
                "contextId": context["contextId"],
                "subjectType": "CSPEC_SELECTION",
                "subjectId": selection["selectionId"],
                "eventType": "REQUESTED",
                "status": "REQUIRED",
                "decision": "NONE",
                "actor": {
                    "actorId": "reviewer-synthetic-curator",
                    "role": "CURATOR",
                },
                "reasonCode": "CSPEC_MATCH_NOT_EXECUTABLE",
                "createdAt": bundle["createdAt"],
            }
        ]
        return contract.rehash_bundle(bundle)

    @contextmanager
    def temporary_manifest(self) -> Iterator[tuple[Path, dict[str, Any]]]:
        with tempfile.TemporaryDirectory(prefix="varvis-e1-manifest-") as raw_root:
            root = Path(raw_root)
            contract_dir = root / "docs" / "contracts" / "acmg-evidence-v2"
            script_dir = root / "scripts" / "evidence"
            contract_dir.mkdir(parents=True)
            script_dir.mkdir(parents=True)
            (contract_dir / "PROTOCOL.md").write_text(
                "# Synthetic manifest fixture\n", encoding="utf-8"
            )
            (script_dir / "validator.py").write_text(
                "# synthetic manifest fixture\n", encoding="utf-8"
            )
            yield root, contract.build_manifest(root)

    def test_schemas_registries_and_valid_bundle_pass(self) -> None:
        self.assertGreater(len(self.schemas.schemas_by_id), 0)
        contract.validate_authority_catalog(self.catalog, self.schemas)
        contract.validate_rule_registry(self.rule_registry, self.catalog, self.schemas)
        contract.validate_cspec_registry(
            self.cspec_registry,
            self.catalog,
            self.rule_registry,
            self.schemas,
        )
        self.validate_bundle(self.bundle())
        self.validate_bundle(contract.rehash_bundle(self.bundle()))

    def test_timeout_cannot_be_mixed_with_hit(self) -> None:
        bundle = self.bundle()
        evidence = bundle["atomicEvidence"][0]
        evidence["resourceExecution"] = {
            "status": "TIMEOUT",
            "reasonCode": "PROVIDER_TIMEOUT",
        }
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_no_record_cannot_have_a_measurement(self) -> None:
        bundle = self.bundle()
        evidence = bundle["atomicEvidence"][1]
        evidence["measurements"] = [
            {
                "metricId": "alleleFrequency",
                "rawValue": "0",
                "normalizedValue": "0",
                "unit": "fraction",
            }
        ]
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_not_queried_cannot_have_a_measurement(self) -> None:
        bundle = self.bundle()
        evidence = bundle["atomicEvidence"][2]
        evidence["measurements"] = [
            {
                "metricId": "syntheticUnavailableScore",
                "rawValue": "0.5",
            }
        ]
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_normalization_cannot_silently_change_assembly(self) -> None:
        bundle = self.bundle()
        bundle["context"]["variant"]["normalizedAllele"]["assembly"] = "GRCh37"
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "silently change assembly"):
            self.validate_bundle(invalid)

    def test_unchanged_normalization_must_preserve_input_allele(self) -> None:
        bundle = self.bundle()
        bundle["context"]["variant"]["normalizedAllele"]["position"] += 1
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "UNCHANGED normalization"):
            self.validate_bundle(invalid)

    def test_native_query_must_equal_normalized_allele(self) -> None:
        bundle = self.bundle()
        bundle["context"]["variant"]["evidenceQueryAllele"]["position"] += 1
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "NATIVE evidence query"):
            self.validate_bundle(invalid)

    def test_mapped_lineage_must_bind_exact_source_and_target_alleles(self) -> None:
        bundle = self.bundle()
        variant = bundle["context"]["variant"]
        variant["evidenceQueryAllele"] = {
            "assembly": "GRCh37",
            "contig": "1",
            "position": 99999,
            "reference": "A",
            "alternate": "G",
        }
        variant["assemblyLineage"] = {
            "mappingStatus": "MAPPED",
            "coordinateMapping": {
                "sourceAllele": copy.deepcopy(variant["normalizedAllele"]),
                "targetAllele": copy.deepcopy(variant["evidenceQueryAllele"]),
                "method": "synthetic-liftover",
                "methodVersion": "1.0.0",
                "refAltValidation": "MATCH",
            },
        }
        variant["assemblyLineage"]["coordinateMapping"]["sourceAllele"][
            "position"
        ] += 1
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "mapping source"):
            self.validate_bundle(invalid)

    def test_e1_rejects_incomplete_or_placeholder_core_context(self) -> None:
        bundle = self.bundle()
        bundle["context"]["contextCompleteness"] = {
            "status": "INCOMPLETE",
            "missingFields": ["MONDO_DISEASE"],
        }
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_unavailable_resource_cannot_produce_a_met_criterion(self) -> None:
        bundle = self.bundle()
        evidence = bundle["atomicEvidence"][2]
        evidence["resourceExecution"] = {
            "status": "NOT_RUN",
            "reasonCode": "RESOURCE_UNAVAILABLE",
        }
        evidence["resourceAvailability"] = {
            "status": "UNAVAILABLE",
            "reasonCode": "RESOURCE_UNAVAILABLE",
        }
        evidence["queryOutcome"] = {"status": "NOT_QUERIED", "recordCount": 0}
        evidence["resourceApplicability"] = {
            "status": "UNKNOWN",
            "reasonCode": "RESOURCE_UNAVAILABLE",
        }
        invalid = self.completed_criterion_result(bundle, "PM2", 2)
        rules = self.rules()
        # This in-memory marker exists only to reach the missing-evidence guard;
        # the frozen E1 registry itself intentionally permits no v2 execution.
        self.criterion(rules, "PM2")["implementationStatus"] = "V2_IMPLEMENTED"

        with self.assertRaisesRegex(
            contract.ContractError, "cannot be inferred from missing"
        ):
            self.validate_bundle(invalid, rules=rules)

    def test_disabled_resource_cannot_produce_a_met_criterion(self) -> None:
        bundle = self.completed_criterion_result(self.bundle(), "PM2", 2)
        rules = self.rules()
        self.criterion(rules, "PM2")["implementationStatus"] = "V2_IMPLEMENTED"

        with self.assertRaisesRegex(
            contract.ContractError, "cannot be inferred from missing"
        ):
            self.validate_bundle(bundle, rules=rules)

    def test_pp5_and_bp6_cannot_be_met(self) -> None:
        for code in ("PP5", "BP6"):
            with self.subTest(code=code):
                invalid = self.completed_criterion_result(self.bundle(), code, 0)
                with self.assertRaisesRegex(contract.ContractError, f"{code}.*cannot be MET"):
                    self.validate_bundle(invalid)

    def test_pm2_current_general_strength_cannot_revert_to_moderate(self) -> None:
        registry = self.rules()
        self.criterion(registry, "PM2")["currentGeneral"]["strength"] = "MODERATE"

        with self.assertRaises(contract.ContractError):
            contract.validate_rule_registry(registry, self.catalog, self.schemas)

    def test_current_general_strength_sets_are_authority_bound(self) -> None:
        invalid_additions = (
            ("PM5", "SUPPORTING"),
            ("BS1", "MODERATE"),
            ("BS1", "SUPPORTING"),
            ("PP3", "VERY_STRONG"),
            ("BP4", "VERY_STRONG"),
        )
        for code, extra_strength in invalid_additions:
            with self.subTest(code=code, extra_strength=extra_strength):
                registry = self.rules()
                current = self.criterion(registry, code)["currentGeneral"]
                current["allowedStrengths"].append(extra_strength)
                with self.assertRaisesRegex(
                    contract.ContractError, "allowed strengths are not authority-bound"
                ):
                    contract.validate_rule_registry(registry, self.catalog, self.schemas)

    def test_calibrated_strengths_require_the_pinned_profile(self) -> None:
        registry = self.rules()
        self.criterion(registry, "PP3")["currentGeneral"][
            "strengthResolutionPolicy"
        ] = "FIXED_GENERAL"

        with self.assertRaisesRegex(contract.ContractError, "resolution policy"):
            contract.validate_rule_registry(registry, self.catalog, self.schemas)

    def test_month_precision_uses_the_conservative_upper_bound(self) -> None:
        catalog = self.catalog_copy()
        source = next(
            item
            for item in catalog["sources"]
            if item["authoritySourceId"]
            == "authority-clingen-multiple-disorders-v1-2024"
        )
        source["publicationDate"] = "2026-08"
        source["publicationDatePrecision"] = "MONTH"
        catalog["authorityCutoff"] = "2026-08-15"

        with self.assertRaisesRegex(contract.ContractError, "not released on or before cutoff"):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_partial_date_precision_mismatch_is_rejected(self) -> None:
        catalog = self.catalog_copy()
        source = next(
            item
            for item in catalog["sources"]
            if item["authoritySourceId"]
            == "authority-clingen-multiple-disorders-v1-2024"
        )
        source["publicationDatePrecision"] = "DAY"

        with self.assertRaises(contract.ContractError):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_every_authority_source_requires_hash_drift_coverage(self) -> None:
        catalog = self.catalog_copy()
        trigger = next(
            item
            for item in catalog["updateTriggers"]
            if item["triggerType"] == "SNAPSHOT_HASH_DRIFT"
        )
        trigger["watchedAuthoritySourceIds"].remove(
            "authority-clingen-svi-ps2-pm6-v1.1"
        )

        with self.assertRaisesRegex(contract.ContractError, "must cover every authority source"):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_official_discovery_indexes_cannot_be_substituted(self) -> None:
        catalog = self.catalog_copy()
        catalog["officialDiscoveryIndexUrls"][-1] = "https://example.invalid/not-official"
        discovery = next(
            trigger
            for trigger in catalog["updateTriggers"]
            if trigger["triggerType"] == "AUTHORITY_INDEX_DISCOVERY"
        )
        discovery["watchedUrls"][-1] = "https://example.invalid/not-official"

        with self.assertRaises(contract.ContractError):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_authority_trigger_contract_is_closed_world(self) -> None:
        catalog = self.catalog_copy()
        catalog["updateTriggers"] = [
            trigger
            for trigger in catalog["updateTriggers"]
            if trigger["triggerId"] != "authority-trigger-scope-change"
        ]

        with self.assertRaisesRegex(contract.ContractError, "closed-world"):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_correction_trigger_must_cover_pending_and_service_sources(self) -> None:
        catalog = self.catalog_copy()
        trigger = next(
            item
            for item in catalog["updateTriggers"]
            if item["triggerType"] == "CORRECTION_OR_RETRACTION"
        )
        trigger["watchedAuthoritySourceIds"].remove("authority-svc-v4-pending-2026")

        with self.assertRaisesRegex(contract.ContractError, "exactly cover all"):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_authority_locator_requires_hash_scope_and_retrieval_time(self) -> None:
        for field in ("hashScope", "retrievedAt"):
            with self.subTest(field=field):
                catalog = self.catalog_copy()
                catalog["sources"][0]["sourceLocator"].pop(field)
                with self.assertRaises(contract.ContractError):
                    contract.validate_authority_catalog(catalog, self.schemas)

    def test_authority_locator_cannot_postdate_catalog_as_of(self) -> None:
        catalog = self.catalog_copy()
        catalog["sources"][0]["sourceLocator"]["retrievedAt"] = "2099-01-01T00:00:00Z"

        with self.assertRaisesRegex(contract.ContractError, "after catalog asOf"):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_rule_registry_as_of_must_match_authority_freeze(self) -> None:
        registry = self.rules()
        registry["asOf"] = "2026-08-31T11:19:59Z"

        with self.assertRaisesRegex(contract.ContractError, "asOf"):
            contract.validate_rule_registry(registry, self.catalog, self.schemas)

    def test_original_2015_authority_reference_is_validated(self) -> None:
        registry = self.rules()
        self.criterion(registry, "PVS1")["original2015"]["authoritySourceIds"] = [
            "authority-clingen-svi-pvs1-2018"
        ]

        with self.assertRaises(contract.ContractError):
            contract.validate_rule_registry(registry, self.catalog, self.schemas)

    def test_active_2015_baseline_cannot_be_semantically_rewritten(self) -> None:
        registry = self.rules()
        self.criterion(registry, "PS4")["currentGeneral"]["designation"] = (
            "SYNTHETIC_REPLACEMENT"
        )

        with self.assertRaisesRegex(contract.ContractError, "active 2015 baseline drift"):
            contract.validate_rule_registry(registry, self.catalog, self.schemas)

    def test_rule_locator_hash_must_bind_a_cited_authority(self) -> None:
        registry = self.rules()
        self.criterion(registry, "PVS1")["original2015"]["sourceLocators"][0][
            "artifactSha256"
        ] = "0" * 64

        with self.assertRaisesRegex(contract.ContractError, "not bound to a cited authority"):
            contract.validate_rule_registry(registry, self.catalog, self.schemas)

    def test_pending_svc_v4_cannot_use_a_branch_reference(self) -> None:
        catalog = self.catalog_copy()
        pending = next(
            source
            for source in catalog["sources"]
            if source["authoritySourceId"] == "authority-svc-v4-pending-2026"
        )
        pending["sourceLocator"]["sourceRecordId"] = "main:README.md"

        with self.assertRaisesRegex(contract.ContractError, "branch-based"):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_pending_svc_v4_cannot_be_activated(self) -> None:
        catalog = self.catalog_copy()
        pending = next(
            source
            for source in catalog["sources"]
            if source["authoritySourceId"] == "authority-svc-v4-pending-2026"
        )
        pending["normativeEligibility"] = "ELIGIBLE"

        with self.assertRaises(contract.ContractError):
            contract.validate_authority_catalog(catalog, self.schemas)

    def test_metadata_only_cspec_cannot_be_selectable(self) -> None:
        registry = self.cspec()
        registry["overlays"][0]["selectionStatus"] = "SELECTABLE"

        with self.assertRaises(contract.ContractError):
            contract.validate_cspec_registry(
                registry,
                self.catalog,
                self.rule_registry,
                self.schemas,
            )

    def test_cspec_alias_identity_conflict_is_not_silently_deduplicated(self) -> None:
        registry = self.cspec()
        registry["overlays"][0]["versionAliases"][1]["apiRevision"] = "different"

        with self.assertRaisesRegex(
            contract.ContractError,
            "observed frozen set|alias set hash drift|aliases disagree",
        ):
            contract.validate_cspec_registry(
                registry,
                self.catalog,
                self.rule_registry,
                self.schemas,
            )

    def test_cspec_deduplication_identity_fields_cannot_be_weakened(self) -> None:
        registry = self.cspec()
        registry["overlays"][0]["versionDeduplication"]["identityFields"] = [
            "CSPEC_ID"
        ]

        with self.assertRaisesRegex(contract.ContractError, "identity fields are incomplete"):
            contract.validate_cspec_registry(
                registry,
                self.catalog,
                self.rule_registry,
                self.schemas,
            )

    def test_cspec_deduplication_status_must_match_observed_aliases(self) -> None:
        for status in ("UNIQUE", "NOT_EVALUATED"):
            with self.subTest(status=status):
                registry = self.cspec()
                registry["overlays"][0]["versionDeduplication"]["status"] = status
                with self.assertRaisesRegex(
                    contract.ContractError, "status does not match observed aliases"
                ):
                    contract.validate_cspec_registry(
                        registry,
                        self.catalog,
                        self.rule_registry,
                        self.schemas,
                    )

    def test_cspec_transcript_required_flag_is_not_ambiguous(self) -> None:
        mutations = (
            (True, True),
            (False, False),
        )
        for transcript_required, remove_transcript in mutations:
            with self.subTest(transcript_required=transcript_required):
                registry = self.cspec()
                overlay = registry["overlays"][0]
                overlay["transcriptRequired"] = transcript_required
                if remove_transcript:
                    overlay["selector"].pop("transcript")
                with self.assertRaises(contract.ContractError):
                    contract.validate_cspec_registry(
                        registry,
                        self.catalog,
                        self.rule_registry,
                        self.schemas,
                    )

    def test_cspec_alias_must_bind_the_normative_snapshot(self) -> None:
        registry = self.cspec()
        registry["overlays"][0]["versionAliases"][0]["snapshotSha256"] = "0" * 64

        with self.assertRaisesRegex(contract.ContractError, "normative snapshot"):
            contract.validate_cspec_registry(
                registry,
                self.catalog,
                self.rule_registry,
                self.schemas,
            )

    def test_cspec_aliases_are_the_observed_frozen_set(self) -> None:
        registry = self.cspec()
        overlay = registry["overlays"][0]
        alias = overlay["versionAliases"][0]
        alias["rawVersion"] = "99.9"
        alias["sourceRecordId"] = "CSpec:GN007@99.9"
        overlay["versionAliasSetSha256"] = contract.canonical_sha256(
            sorted(overlay["versionAliases"], key=lambda item: item["rawVersion"])
        )

        with self.assertRaisesRegex(contract.ContractError, "observed frozen set"):
            contract.validate_cspec_registry(
                registry,
                self.catalog,
                self.rule_registry,
                self.schemas,
            )

    def test_identity_conflicting_cspec_cannot_be_selectable(self) -> None:
        registry = self.cspec()
        overlay = registry["overlays"][0]
        overlay["versionDeduplication"]["status"] = "IDENTITY_CONFLICT"
        overlay["implementationStatus"] = "IMPLEMENTED"
        overlay["selectionStatus"] = "SELECTABLE"
        overlay["criterionSpecifications"] = [
            {
                "criterionId": "ACMG_AMP_2015:PM2",
                "action": "USE_BASE",
                "designation": "PM2_SUPPORTING",
                "strength": "SUPPORTING",
                "authoritySourceIds": copy.deepcopy(overlay["authoritySourceIds"]),
                "sourceLocators": copy.deepcopy(overlay["sourceLocators"]),
            }
        ]

        with self.assertRaises(contract.ContractError):
            contract.validate_cspec_registry(
                registry,
                self.catalog,
                self.rule_registry,
                self.schemas,
            )

    def test_released_cspec_requires_distinct_json_and_pdf_artifacts(self) -> None:
        registry = self.cspec()
        registry["overlays"][0]["sourceLocators"] = [
            registry["overlays"][0]["sourceLocators"][0]
        ]

        with self.assertRaisesRegex(contract.ContractError, "one immutable JSON and one PDF"):
            contract.validate_cspec_registry(
                registry,
                self.catalog,
                self.rule_registry,
                self.schemas,
            )

    def test_cspec_artifact_roles_bind_media_and_url(self) -> None:
        mutations = (
            ("CSPEC_API_EXPORT_JSON", "artifactMediaType", "application/pdf"),
            ("CSPEC_SPECIFICATION_PDF", "artifactUrl", None),
        )
        for role, field, value in mutations:
            with self.subTest(role=role, field=field):
                registry = self.cspec()
                locator = next(
                    item
                    for item in registry["overlays"][0]["sourceLocators"]
                    if item["artifactRole"] == role
                )
                if value is None:
                    locator.pop(field)
                else:
                    locator[field] = value
                with self.assertRaises(contract.ContractError):
                    contract.validate_cspec_registry(
                        registry,
                        self.catalog,
                        self.rule_registry,
                        self.schemas,
                    )

    def test_cspec_selection_failures_have_stable_abstain_reasons(self) -> None:
        registry = self.cspec()
        registry["selectionPolicy"]["outcomes"]["multipleExactMatches"][
            "reasonCode"
        ] = "CHANGED_REASON"

        with self.assertRaisesRegex(contract.ContractError, "unstable abstain reason"):
            contract.validate_cspec_registry(
                registry,
                self.catalog,
                self.rule_registry,
                self.schemas,
            )

    def test_multiple_exact_cspec_matches_cannot_be_silently_resolved(self) -> None:
        registry = self.cspec()
        context = self.valid_bundle["context"]
        template = registry["overlays"][0]
        matches = []
        for suffix in ("a", "b"):
            overlay = copy.deepcopy(template)
            overlay["overlayId"] = f"cspec-overlay-synthetic-match-{suffix}"
            overlay["implementationStatus"] = "IMPLEMENTED"
            overlay["selectionStatus"] = "SELECTABLE"
            overlay["transcriptRequired"] = True
            overlay["selector"] = {
                "hgncId": context["gene"]["hgncId"],
                "mondoId": context["disease"]["mondoId"],
                "moi": context["moi"],
                "transcript": {
                    "accession": context["transcript"]["accession"],
                    "version": context["transcript"]["version"],
                },
            }
            overlay["criterionSpecifications"] = [
                {
                    "criterionId": "ACMG_AMP_2015:PM2",
                    "action": "USE_BASE",
                    "designation": "PM2_SUPPORTING",
                    "strength": "SUPPORTING",
                    "authoritySourceIds": copy.deepcopy(overlay["authoritySourceIds"]),
                    "sourceLocators": copy.deepcopy(overlay["sourceLocators"]),
                }
            ]
            matches.append(overlay)
        registry["overlays"].extend(matches)

        with self.assertRaisesRegex(contract.ContractError, "multiple exact CSpec matches"):
            self.validate_bundle(self.bundle(), cspec=registry)

    def test_single_nonselectable_cspec_enters_type_safe_review(self) -> None:
        self.validate_bundle(self.single_nonselectable_cspec_bundle())

    def test_cspec_failure_reason_must_propagate_to_criterion(self) -> None:
        bundle = self.single_nonselectable_cspec_bundle()
        bundle["criterionEvaluations"][0]["evaluationResult"]["reasonCode"] = (
            "E1_RULE_NOT_IMPLEMENTED"
        )
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "criterion result"):
            self.validate_bundle(invalid)

    def test_e1_combination_gate_rejects_every_completed_result(self) -> None:
        results = (
            "PATHOGENIC",
            "LIKELY_PATHOGENIC",
            "UNCERTAIN_SIGNIFICANCE",
            "LIKELY_BENIGN",
            "BENIGN",
            "ABSTAIN",
        )
        for result in results:
            with self.subTest(result=result):
                invalid = self.completed_combination(
                    self.bundle(),
                    result=result,
                    include_reason=result == "ABSTAIN",
                )
                with self.assertRaisesRegex(contract.ContractError, "E1 combination gate"):
                    self.validate_bundle(invalid)

    def test_review_subject_type_must_match_subject_id(self) -> None:
        bundle = self.single_nonselectable_cspec_bundle()
        bundle["reviewRecords"][0]["subjectType"] = "ATOMIC_EVIDENCE"
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "type/ID mismatch"):
            self.validate_bundle(invalid)

    def test_bundle_cannot_predate_its_authority_snapshots(self) -> None:
        bundle = self.bundle()
        bundle["createdAt"] = "2026-08-31T11:19:59Z"
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "cannot predate"):
            self.validate_bundle(invalid)

    def test_evidence_locator_cannot_postdate_bundle_creation(self) -> None:
        bundle = self.bundle()
        bundle["atomicEvidence"][0]["source"]["sourceLocators"][0][
            "retrievedAt"
        ] = "2099-01-01T00:00:00Z"
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "after bundle creation"):
            self.validate_bundle(invalid)

    def test_legacy_status_semantic_promotion_is_forbidden(self) -> None:
        mutations = (
            ("SUGGESTED", "suggestedToMet"),
            ("NOT_SUGGESTED", "notSuggestedToNotMet"),
        )
        for legacy_status, promotion_key in mutations:
            with self.subTest(status=legacy_status):
                bundle = self.bundle()
                adapter = bundle["legacyAdapterRecords"][0]
                adapter["legacySuggestionStatus"] = legacy_status
                adapter["semanticPromotion"][promotion_key] = True
                invalid = contract.rehash_bundle(bundle)
                with self.assertRaises(contract.ContractError):
                    self.validate_bundle(invalid)

    def test_acmg_ready_adapter_cannot_emit_atomic_evidence(self) -> None:
        bundle = self.bundle()
        adapter = bundle["legacyAdapterRecords"][0]
        adapter["outputEvidenceIds"] = [bundle["atomicEvidence"][0]["evidenceId"]]
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_atomic_evidence_cannot_reference_a_missing_legacy_adapter(self) -> None:
        bundle = self.bundle()
        bundle["atomicEvidence"][0]["legacyAdapterRecordId"] = (
            "adapter-sha256-" + "9" * 64
        )
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "missing legacy adapter"):
            self.validate_bundle(invalid)

    def test_atomic_evidence_requires_reverse_adapter_declaration(self) -> None:
        bundle = self.bundle()
        bundle["atomicEvidence"][0]["legacyAdapterRecordId"] = bundle[
            "legacyAdapterRecords"
        ][0]["adapterRecordId"]
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "not declared"):
            self.validate_bundle(invalid)

    def test_legacy_observation_type_is_not_active_in_e1(self) -> None:
        bundle = self.bundle()
        bundle["atomicEvidence"][0]["evidenceType"] = "LEGACY_OBSERVATION"
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_evidence_item_adapter_has_recomputable_bidirectional_ids(self) -> None:
        bundle = self.bundle()
        evidence = bundle["atomicEvidence"][0]
        adapter = bundle["legacyAdapterRecords"][0]
        adapter["inputSchemaVersion"] = "evidence-item-v1"
        adapter["mappingStatus"] = "EXACT"
        adapter.pop("legacySuggestionStatus")
        adapter["eligibleForCriterionEvaluation"] = True
        adapter["outputEvidenceIds"] = [evidence["evidenceId"]]
        evidence["legacyAdapterRecordId"] = adapter["adapterRecordId"]
        valid = contract.rehash_bundle(bundle)

        self.validate_bundle(valid)
        self.assertEqual(valid, contract.rehash_bundle(valid))

    def test_adapter_criterion_eligibility_is_enforced(self) -> None:
        bundle = self.bundle()
        evidence = bundle["atomicEvidence"][1]
        adapter = bundle["legacyAdapterRecords"][0]
        adapter["inputSchemaVersion"] = "evidence-item-v1"
        adapter["mappingStatus"] = "EXACT"
        adapter.pop("legacySuggestionStatus")
        adapter["eligibleForCriterionEvaluation"] = False
        adapter["outputEvidenceIds"] = [evidence["evidenceId"]]
        evidence["legacyAdapterRecordId"] = adapter["adapterRecordId"]
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "without criterion eligibility"):
            self.validate_bundle(invalid)

    def test_unmapped_evidence_item_adapter_cannot_emit_or_be_eligible(self) -> None:
        bundle = self.bundle()
        adapter = bundle["legacyAdapterRecords"][0]
        adapter["inputSchemaVersion"] = "evidence-item-v1"
        adapter["mappingStatus"] = "UNMAPPED"
        adapter.pop("legacySuggestionStatus")
        adapter["eligibleForCriterionEvaluation"] = True
        adapter["outputEvidenceIds"] = [bundle["atomicEvidence"][0]["evidenceId"]]
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_partial_evidence_item_adapter_requires_warning_and_cannot_combine(self) -> None:
        for warnings, eligible_for_combination in (([], False), (["LOSSY_MAPPING"], True)):
            with self.subTest(
                warnings=warnings,
                eligible_for_combination=eligible_for_combination,
            ):
                bundle = self.bundle()
                adapter = bundle["legacyAdapterRecords"][0]
                adapter["inputSchemaVersion"] = "evidence-item-v1"
                adapter["mappingStatus"] = "PARTIAL"
                adapter.pop("legacySuggestionStatus")
                adapter["warnings"] = warnings
                adapter["eligibleForCombination"] = eligible_for_combination
                invalid = contract.rehash_bundle(bundle)

                with self.assertRaises(contract.ContractError):
                    self.validate_bundle(invalid)

    def test_legacy_only_mapping_is_reserved_for_acmg_ready(self) -> None:
        bundle = self.bundle()
        adapter = bundle["legacyAdapterRecords"][0]
        adapter["inputSchemaVersion"] = "evidence-item-v1"
        adapter.pop("legacySuggestionStatus")
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_abstain_requires_a_reason_code(self) -> None:
        invalid = self.completed_combination(
            self.bundle(), result="ABSTAIN", include_reason=False
        )

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_not_run_cannot_have_an_abstain_result(self) -> None:
        bundle = self.bundle()
        bundle["combination"]["result"] = "ABSTAIN"
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_stable_id_drift_is_rejected(self) -> None:
        invalid = self.bundle()
        invalid["context"]["variant"]["alleleId"] = "allele-sha256-" + "0" * 64

        with self.assertRaisesRegex(contract.ContractError, "allele stable ID drift"):
            self.validate_bundle(invalid)

    def test_set_order_does_not_change_selection_or_evaluation_ids(self) -> None:
        selection_a = copy.deepcopy(self.valid_bundle["cspecSelection"])
        selection_a["candidateOverlayIds"] = ["overlay-b", "overlay-a"]
        selection_b = copy.deepcopy(selection_a)
        selection_b["candidateOverlayIds"].reverse()
        self.assertEqual(
            contract.content_id(
                "cspec-selection-sha256-",
                contract.cspec_selection_identity_projection(selection_a),
            ),
            contract.content_id(
                "cspec-selection-sha256-",
                contract.cspec_selection_identity_projection(selection_b),
            ),
        )

        bundle_a = self.bundle()
        evaluation = bundle_a["criterionEvaluations"][0]
        evidence_ids = [item["evidenceId"] for item in bundle_a["atomicEvidence"][:2]]
        evaluation["evidenceIds"] = evidence_ids
        evaluation["deterministicTrace"]["inputEvidenceIds"] = copy.deepcopy(
            evidence_ids
        )
        bundle_a = contract.rehash_bundle(bundle_a)
        bundle_b = copy.deepcopy(bundle_a)
        evaluation_b = bundle_b["criterionEvaluations"][0]
        evaluation_b["evidenceIds"].reverse()
        evaluation_b["deterministicTrace"]["inputEvidenceIds"].reverse()
        evaluation_b["ruleBinding"]["authoritySourceIds"].reverse()
        bundle_b = contract.rehash_bundle(bundle_b)

        self.assertEqual(
            bundle_a["criterionEvaluations"][0]["evaluationId"],
            bundle_b["criterionEvaluations"][0]["evaluationId"],
        )

    def test_criterion_base_strength_is_bound_to_current_rule(self) -> None:
        bundle = self.bundle()
        bundle["criterionEvaluations"][0]["baseStrength"] = "STRONG"
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "base strength"):
            self.validate_bundle(invalid)

    def test_dangling_evidence_reference_is_rejected(self) -> None:
        bundle = self.bundle()
        missing_id = "evidence-sha256-" + "f" * 64
        evaluation = bundle["criterionEvaluations"][0]
        evaluation["evidenceIds"] = [missing_id]
        evaluation["deterministicTrace"]["inputEvidenceIds"] = [missing_id]
        invalid = contract.rehash_bundle(bundle)

        with self.assertRaisesRegex(contract.ContractError, "evidence reference missing"):
            self.validate_bundle(invalid)

    def test_duplicate_deduplication_key_is_rejected(self) -> None:
        invalid = self.bundle()
        duplicate = copy.deepcopy(invalid["atomicEvidence"][0])
        duplicate["evidenceId"] = "evidence-sha256-" + "f" * 64
        duplicate["provenance"]["identityProjectionSha256"] = "f" * 64
        invalid["atomicEvidence"].append(duplicate)

        with self.assertRaisesRegex(
            contract.ContractError, "duplicate evidence deduplication keys"
        ):
            self.validate_bundle(invalid)

    def test_derived_evidence_cycle_is_rejected(self) -> None:
        invalid = self.bundle()
        first = invalid["atomicEvidence"][0]
        second = invalid["atomicEvidence"][1]
        first["derivedFromEvidenceIds"] = [second["evidenceId"]]
        second["derivedFromEvidenceIds"] = [first["evidenceId"]]

        # A cycle necessarily also changes each content-addressed identity. Either
        # integrity guard is sufficient to reject the cyclic graph.
        with self.assertRaises(contract.ContractError):
            self.validate_bundle(invalid)

    def test_normalized_allele_over_50_bp_is_out_of_e1_scope(self) -> None:
        invalid = self.bundle()
        invalid["context"]["variant"]["normalizedAllele"]["reference"] = "A" * 51

        with self.assertRaisesRegex(contract.ContractError, "50 bp small-variant boundary"):
            self.validate_bundle(invalid)

    def test_valid_closed_world_manifest_passes(self) -> None:
        with self.temporary_manifest() as (root, manifest):
            contract.validate_manifest(manifest, self.schemas, repo_root=root)

    def test_manifest_must_not_hash_itself(self) -> None:
        with self.temporary_manifest() as (root, manifest):
            self_entry = copy.deepcopy(manifest["artifacts"][0])
            self_entry["path"] = contract.MANIFEST_RELATIVE_PATH
            manifest["artifacts"].append(self_entry)

            with self.assertRaisesRegex(contract.ContractError, "must not hash itself"):
                contract.validate_manifest(manifest, self.schemas, repo_root=root)

    def test_manifest_rejects_an_extra_artifact(self) -> None:
        with self.temporary_manifest() as (root, manifest):
            extra = copy.deepcopy(manifest["artifacts"][0])
            extra["path"] = "scripts/evidence/nonexistent-extra.py"
            manifest["artifacts"].append(extra)

            with self.assertRaisesRegex(contract.ContractError, "closed-world manifest mismatch"):
                contract.validate_manifest(manifest, self.schemas, repo_root=root)

    def test_manifest_rejects_a_missing_artifact(self) -> None:
        with self.temporary_manifest() as (root, manifest):
            manifest["artifacts"].pop()

            with self.assertRaisesRegex(contract.ContractError, "closed-world manifest mismatch"):
                contract.validate_manifest(manifest, self.schemas, repo_root=root)

    def test_manifest_rejects_path_escape(self) -> None:
        with self.temporary_manifest() as (root, manifest):
            manifest["artifacts"][0]["path"] = "../outside-contract.txt"

            with self.assertRaises(contract.ContractError):
                contract.validate_manifest(manifest, self.schemas, repo_root=root)

    def test_manifest_rejects_a_wrong_hash(self) -> None:
        with self.temporary_manifest() as (root, manifest):
            manifest["artifacts"][0]["sha256"] = "0" * 64

            with self.assertRaisesRegex(contract.ContractError, "manifest hash mismatch"):
                contract.validate_manifest(manifest, self.schemas, repo_root=root)


if __name__ == "__main__":
    unittest.main()
