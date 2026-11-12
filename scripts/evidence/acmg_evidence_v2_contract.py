#!/usr/bin/env python3
"""Offline validator for the frozen Varvis ACMG evidence v2 E1 contract.

This module validates contract engineering only. It does not implement an
ACMG/ClinGen criterion or combination algorithm and makes no clinical claim.
"""

from __future__ import annotations

import argparse
import calendar
import copy
import hashlib
import importlib.metadata
import json
import math
import re
import sys
import unicodedata
from datetime import date, datetime
from pathlib import Path
from typing import Any, Iterable, Mapping

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


REPO_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = REPO_ROOT / "docs" / "contracts" / "acmg-evidence-v2"
SCHEMA_ROOT = CONTRACT_ROOT / "schemas"
SYNTHETIC_ROOT = CONTRACT_ROOT / "synthetic"
MANIFEST_PATH = CONTRACT_ROOT / "FREEZE_MANIFEST.json"

AUTHORITY_PATH = CONTRACT_ROOT / "AUTHORITY_SOURCES.json"
RULE_REGISTRY_PATH = CONTRACT_ROOT / "ACMG_RULE_REGISTRY.json"
CSPEC_REGISTRY_PATH = CONTRACT_ROOT / "CSPEC_OVERLAY_REGISTRY.json"

CANONICALIZATION_PROFILE = "varvis-json-c14n-v1"
MANIFEST_RELATIVE_PATH = "docs/contracts/acmg-evidence-v2/FREEZE_MANIFEST.json"
GOVERNED_ROOTS = (
    "docs/contracts/acmg-evidence-v2",
    "scripts/evidence",
)
OFFICIAL_DISCOVERY_INDEX_URLS = {
    "https://www.clinicalgenome.org/tools/clingen-variant-classification-guidance/",
    "https://www.clinicalgenome.org/docs/",
    "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id?detail=low",
}
REQUIRED_AUTHORITY_TRIGGERS = {
    "authority-trigger-svc-release": "SVC_RELEASE",
    "authority-trigger-clingen-guidance-change": "CLINGEN_GUIDANCE_CHANGE",
    "authority-trigger-cspec-release-or-replacement": "CSPEC_RELEASE_OR_REPLACEMENT",
    "authority-trigger-correction-or-retraction": "CORRECTION_OR_RETRACTION",
    "authority-trigger-scope-change": "SCOPE_CHANGE",
    "authority-trigger-snapshot-hash-drift": "SNAPSHOT_HASH_DRIFT",
    "authority-trigger-official-index-discovery": "AUTHORITY_INDEX_DISCOVERY",
}
REQUIRED_TRIGGER_URLS = {
    "authority-trigger-svc-release": {"https://github.com/clingen-data-model/svcv4-model"},
    "authority-trigger-clingen-guidance-change": set(),
    "authority-trigger-cspec-release-or-replacement": {
        "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id?detail=low"
    },
    "authority-trigger-correction-or-retraction": set(),
    "authority-trigger-scope-change": set(),
    "authority-trigger-snapshot-hash-drift": set(),
    "authority-trigger-official-index-discovery": OFFICIAL_DISCOVERY_INDEX_URLS,
}
CSPEC_DEDUPLICATION_IDENTITY_FIELDS = {
    "CSPEC_ID",
    "CANONICAL_VERSION",
    "API_LDH_ID",
    "API_REVISION",
    "SNAPSHOT_SHA256",
}
FROZEN_CSPEC_ALIAS_IDENTITIES = {
    ("GN007", "3.1.0"): {
        (
            "3.1",
            "CSpec:GN007@3.1",
            "135637579",
            "_gFqivn6--_",
            "Zenodo:21421473/GN007_3.1.0.json",
            "6f340df40991990af73f95feb8285e8831a983f78479584ece96711fbabeecd9",
        ),
        (
            "3.1.0",
            "CSpec:GN007@3.1.0",
            "135637579",
            "_gFqivn6--_",
            "Zenodo:21421473/GN007_3.1.0.json",
            "6f340df40991990af73f95feb8285e8831a983f78479584ece96711fbabeecd9",
        ),
        (
            "3.1.1",
            "CSpec:GN007@3.1.1",
            "135637579",
            "_gFqivn6--_",
            "Zenodo:21421473/GN007_3.1.0.json",
            "6f340df40991990af73f95feb8285e8831a983f78479584ece96711fbabeecd9",
        ),
    }
}

ORIGINAL_CODES_AND_STRENGTHS: dict[str, tuple[str, str]] = {
    "PVS1": ("PATHOGENIC", "VERY_STRONG"),
    **{code: ("PATHOGENIC", "STRONG") for code in ("PS1", "PS2", "PS3", "PS4")},
    **{
        code: ("PATHOGENIC", "MODERATE")
        for code in ("PM1", "PM2", "PM3", "PM4", "PM5", "PM6")
    },
    **{
        code: ("PATHOGENIC", "SUPPORTING")
        for code in ("PP1", "PP2", "PP3", "PP4", "PP5")
    },
    "BA1": ("BENIGN", "STANDALONE"),
    **{code: ("BENIGN", "STRONG") for code in ("BS1", "BS2", "BS3", "BS4")},
    **{
        code: ("BENIGN", "SUPPORTING")
        for code in ("BP1", "BP2", "BP3", "BP4", "BP5", "BP6", "BP7")
    },
}

WINDOWS_ABSOLUTE_PATH = re.compile(r"(?i)(?:^|[\s\"'])[a-z]:[\\/]")


class ContractError(ValueError):
    """Raised when a schema, registry, semantic or freeze invariant fails."""


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_value(value: Any) -> Any:
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if isinstance(value, bool) or value is None or isinstance(value, int):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ContractError("canonical JSON forbids non-finite numbers")
        raise ContractError(
            "identity projections forbid binary floating-point values; use a canonical decimal string"
        )
    if isinstance(value, list):
        return [_canonical_value(item) for item in value]
    if isinstance(value, dict):
        return {
            unicodedata.normalize("NFC", str(key)): _canonical_value(item)
            for key, item in value.items()
        }
    raise ContractError(f"unsupported canonical JSON type: {type(value).__name__}")


def canonical_json_bytes(value: Any) -> bytes:
    normalized = _canonical_value(value)
    return json.dumps(
        normalized,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_sha256(value: Any) -> str:
    return sha256_bytes(canonical_json_bytes(value))


def content_id(prefix: str, value: Any) -> str:
    return f"{prefix}{canonical_sha256(value)}"


def _iter_refs(value: Any) -> Iterable[str]:
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "$ref" and isinstance(child, str):
                yield child
            else:
                yield from _iter_refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from _iter_refs(child)


class ContractSchemas:
    def __init__(self, schema_root: Path = SCHEMA_ROOT) -> None:
        self.schema_root = schema_root
        self.schemas_by_id: dict[str, dict[str, Any]] = {}
        self.schemas_by_name: dict[str, dict[str, Any]] = {}
        for path in sorted(schema_root.glob("*.schema.json")):
            schema = load_json(path)
            Draft202012Validator.check_schema(schema)
            schema_id = schema.get("$id")
            if not isinstance(schema_id, str) or not schema_id.startswith("urn:varvis:schema:"):
                raise ContractError(f"schema {path.name} has no offline urn:varvis $id")
            if schema_id in self.schemas_by_id:
                raise ContractError(f"duplicate schema $id: {schema_id}")
            self.schemas_by_id[schema_id] = schema
            self.schemas_by_name[path.name] = schema

        if not self.schemas_by_id:
            raise ContractError("no contract schemas found")

        for schema_id, schema in self.schemas_by_id.items():
            for ref in _iter_refs(schema):
                if ref.startswith("#"):
                    continue
                target_id = ref.split("#", 1)[0]
                if not target_id.startswith("urn:varvis:schema:"):
                    raise ContractError(f"network or non-URN $ref in {schema_id}: {ref}")
                if target_id not in self.schemas_by_id:
                    raise ContractError(f"unresolved offline $ref in {schema_id}: {ref}")

        resources = [
            (schema_id, Resource.from_contents(schema))
            for schema_id, schema in self.schemas_by_id.items()
        ]
        self.registry = Registry().with_resources(resources)

    def validate(self, instance: Any, schema_id: str) -> None:
        schema = self.schemas_by_id.get(schema_id)
        if schema is None:
            raise ContractError(f"unknown schema id: {schema_id}")
        validator = Draft202012Validator(
            schema,
            registry=self.registry,
            format_checker=FormatChecker(),
        )
        errors = sorted(validator.iter_errors(instance), key=lambda error: list(error.path))
        if errors:
            details = []
            for error in errors[:12]:
                path = "/".join(str(part) for part in error.absolute_path) or "<root>"
                details.append(f"{path}: {error.message}")
            suffix = "" if len(errors) <= 12 else f" (+{len(errors) - 12} more)"
            raise ContractError(f"schema {schema_id} failed: {'; '.join(details)}{suffix}")


def _parse_date(value: str) -> date:
    return date.fromisoformat(value)


def _partial_date_bounds(value: str, precision: str) -> tuple[date, date]:
    if precision == "DAY":
        parsed = _parse_date(value)
        return parsed, parsed
    if precision == "MONTH":
        year_text, month_text = value.split("-")
        year = int(year_text)
        month = int(month_text)
        return date(year, month, 1), date(year, month, calendar.monthrange(year, month)[1])
    if precision == "YEAR":
        year = int(value)
        return date(year, 1, 1), date(year, 12, 31)
    raise ContractError(f"unsupported authority date precision: {precision}")


def _source_effective_upper_dates(source: Mapping[str, Any]) -> list[date]:
    effective_dates: list[date] = []
    if source.get("publicationDate"):
        _, publication_upper = _partial_date_bounds(
            source["publicationDate"], source["publicationDatePrecision"]
        )
        effective_dates.append(publication_upper)
    for field in ("approvalDate", "sourceUpdatedDate"):
        if source.get(field):
            effective_dates.append(_parse_date(source[field]))
    return effective_dates


def _parse_datetime(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _assert_unique(values: Iterable[str], label: str) -> None:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for value in values:
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    if duplicates:
        raise ContractError(f"duplicate {label}: {sorted(duplicates)}")


def validate_authority_catalog(catalog: Mapping[str, Any], schemas: ContractSchemas) -> None:
    schemas.validate(catalog, "urn:varvis:schema:authority-source-catalog-v1")
    cutoff = _parse_date(catalog["authorityCutoff"])
    catalog_as_of = _parse_datetime(catalog["asOf"])
    if cutoff > catalog_as_of.date():
        raise ContractError("authorityCutoff must not be after asOf")
    if set(catalog["officialDiscoveryIndexUrls"]) != OFFICIAL_DISCOVERY_INDEX_URLS:
        raise ContractError("official authority discovery indexes are not the frozen set")

    sources = catalog["sources"]
    source_ids = [source["authoritySourceId"] for source in sources]
    _assert_unique(source_ids, "authority source IDs")
    source_map = {source["authoritySourceId"]: source for source in sources}

    for source in sources:
        publication_status = source["publicationStatus"]
        eligibility = source["normativeEligibility"]
        if publication_status in {"PENDING", "RETRACTED"} and eligibility != "INELIGIBLE":
            raise ContractError(f"{source['authoritySourceId']} is non-released but eligible")
        if eligibility == "ELIGIBLE":
            effective_dates = _source_effective_upper_dates(source)
            if publication_status != "RELEASED" or not effective_dates or max(effective_dates) > cutoff:
                raise ContractError(
                    f"{source['authoritySourceId']} is not released on or before cutoff"
                )
        locator = source["sourceLocator"]
        locator_hash = locator.get("artifactSha256")
        if locator_hash != source["snapshotSha256"]:
            raise ContractError(
                f"{source['authoritySourceId']} source locator and snapshot hashes differ"
            )
        retrieved_at = locator.get("retrievedAt")
        if retrieved_at is None or _parse_datetime(retrieved_at) > catalog_as_of:
            raise ContractError(
                f"{source['authoritySourceId']} locator retrieval is missing or after catalog asOf"
            )
        if locator.get("hashScope") is None:
            raise ContractError(f"{source['authoritySourceId']} locator hash scope is missing")

    snapshot_watch_ids: set[str] = set()
    correction_watch_ids: set[str] = set()
    discovery_watch_urls: set[str] = set()
    triggers = catalog["updateTriggers"]
    trigger_ids = [trigger["triggerId"] for trigger in triggers]
    _assert_unique(trigger_ids, "authority update trigger IDs")
    trigger_map = {trigger["triggerId"]: trigger for trigger in triggers}
    actual_trigger_contract = {
        trigger_id: trigger["triggerType"] for trigger_id, trigger in trigger_map.items()
    }
    if actual_trigger_contract != REQUIRED_AUTHORITY_TRIGGERS:
        raise ContractError("authority update trigger set is not the frozen closed-world set")
    for trigger in triggers:
        watched_ids = trigger.get("watchedAuthoritySourceIds", [])
        for source_id in watched_ids:
            if source_id not in source_map:
                raise ContractError(f"update trigger references missing source: {source_id}")
        if trigger["automaticActivation"] is not False:
            raise ContractError("authority update triggers must never auto-activate")
        if trigger["requiredAction"] != "REVIEW_AND_ISSUE_NEW_VERSION":
            raise ContractError("authority update triggers must require a reviewed new version")
        if set(trigger.get("watchedUrls", [])) != REQUIRED_TRIGGER_URLS[trigger["triggerId"]]:
            raise ContractError(f"authority trigger URLs drifted: {trigger['triggerId']}")
        if trigger["triggerType"] == "SNAPSHOT_HASH_DRIFT":
            snapshot_watch_ids.update(watched_ids)
        if trigger["triggerType"] == "CORRECTION_OR_RETRACTION":
            correction_watch_ids.update(watched_ids)
        if trigger["triggerType"] == "AUTHORITY_INDEX_DISCOVERY":
            discovery_watch_urls.update(trigger.get("watchedUrls", []))

    all_source_ids = set(source_map)
    if snapshot_watch_ids != all_source_ids:
        missing = sorted(all_source_ids - snapshot_watch_ids)
        extra = sorted(snapshot_watch_ids - all_source_ids)
        raise ContractError(
            f"snapshot hash trigger must cover every authority source; missing={missing}, extra={extra}"
        )
    eligible_source_ids = {
        source["authoritySourceId"]
        for source in sources
        if source["publicationStatus"] == "RELEASED"
        and source["normativeEligibility"] == "ELIGIBLE"
    }
    missing_correction_coverage = sorted(eligible_source_ids - correction_watch_ids)
    if missing_correction_coverage:
        raise ContractError(
            "correction/retraction trigger misses eligible authority sources: "
            f"{missing_correction_coverage}"
        )
    if correction_watch_ids != all_source_ids:
        raise ContractError("correction/retraction trigger must exactly cover all registered sources")
    if discovery_watch_urls != set(catalog["officialDiscoveryIndexUrls"]):
        raise ContractError("authority discovery trigger does not exactly cover official indexes")

    if set(trigger_map["authority-trigger-svc-release"]["watchedAuthoritySourceIds"]) != {
        "authority-svc-v4-pending-2026"
    }:
        raise ContractError("SVC release trigger does not cover the frozen pending source")
    cspec_source_ids = {
        source_id for source_id in all_source_ids if source_id.startswith("authority-clingen-cspec-")
    }
    if set(
        trigger_map["authority-trigger-cspec-release-or-replacement"][
            "watchedAuthoritySourceIds"
        ]
    ) != cspec_source_ids:
        raise ContractError("CSpec release trigger does not cover every registered CSpec source")
    if set(trigger_map["authority-trigger-scope-change"]["watchedAuthoritySourceIds"]) != {
        "authority-acmg-amp-2015-richards"
    }:
        raise ContractError("scope-change trigger must bind the original 2015 authority")
    clingen_guidance_source_ids = {
        source_id
        for source_id in all_source_ids
        if source_id.startswith("authority-clingen-")
        and not source_id.startswith("authority-clingen-cspec-")
    }
    if set(
        trigger_map["authority-trigger-clingen-guidance-change"][
            "watchedAuthoritySourceIds"
        ]
    ) != clingen_guidance_source_ids:
        raise ContractError("ClinGen guidance trigger does not cover every registered guidance source")

    pending = source_map.get("authority-svc-v4-pending-2026")
    if not pending or pending["publicationStatus"] != "PENDING":
        raise ContractError("SVC v4.0 must be retained as PENDING at the cutoff")
    if pending["normativeEligibility"] != "INELIGIBLE":
        raise ContractError("pending SVC v4.0 must be non-normative")
    immutable_revision = pending.get("immutableRevision", "")
    if not re.fullmatch(r"[0-9a-f]{40}", immutable_revision):
        raise ContractError("pending SVC v4.0 must pin a full Git commit SHA")
    if immutable_revision not in pending["sourceUrl"]:
        raise ContractError("pending SVC v4.0 source URL is not pinned to immutableRevision")
    source_record_id = pending["sourceLocator"].get("sourceRecordId", "")
    if immutable_revision not in source_record_id or "main:" in source_record_id:
        raise ContractError("pending SVC v4.0 source record is branch-based or revision-mismatched")


def validate_rule_registry(
    registry: Mapping[str, Any],
    catalog: Mapping[str, Any],
    schemas: ContractSchemas,
    *,
    authority_path: Path = AUTHORITY_PATH,
) -> None:
    schemas.validate(registry, "urn:varvis:schema:acmg-rule-registry-v1")
    if registry["authorityCatalogId"] != catalog["catalogId"]:
        raise ContractError("rule registry authority catalog ID mismatch")
    if registry["authorityCatalogSha256"] != sha256_file(authority_path):
        raise ContractError("rule registry authority catalog fingerprint mismatch")
    if registry["authorityCutoff"] != catalog["authorityCutoff"]:
        raise ContractError("rule registry authority cutoff mismatch")
    if registry["asOf"] != catalog["asOf"]:
        raise ContractError("rule registry asOf must equal the frozen authority catalog asOf")

    source_map = {source["authoritySourceId"]: source for source in catalog["sources"]}
    criteria = registry["criteria"]
    codes = [criterion["code"] for criterion in criteria]
    _assert_unique(codes, "criterion codes")
    if set(codes) != set(ORIGINAL_CODES_AND_STRENGTHS):
        missing = sorted(set(ORIGINAL_CODES_AND_STRENGTHS) - set(codes))
        extra = sorted(set(codes) - set(ORIGINAL_CODES_AND_STRENGTHS))
        raise ContractError(f"original 28 criterion set mismatch; missing={missing}, extra={extra}")

    cutoff = _parse_date(registry["authorityCutoff"])
    registry_as_of = _parse_datetime(registry["asOf"])

    def validate_branch_provenance(
        code: str, branch_name: str, branch: Mapping[str, Any]
    ) -> None:
        branch_sources: list[Mapping[str, Any]] = []
        for source_id in branch["authoritySourceIds"]:
            source = source_map.get(source_id)
            if source is None:
                raise ContractError(f"{code} {branch_name} references missing authority {source_id}")
            if source["publicationStatus"] != "RELEASED":
                raise ContractError(f"{code} {branch_name} references non-released authority {source_id}")
            if source["normativeEligibility"] != "ELIGIBLE":
                raise ContractError(f"{code} {branch_name} references ineligible authority {source_id}")
            effective_dates = _source_effective_upper_dates(source)
            if not effective_dates or max(effective_dates) > cutoff:
                raise ContractError(f"{code} {branch_name} references authority after cutoff {source_id}")
            branch_sources.append(source)

        expected_hashes = {source["snapshotSha256"] for source in branch_sources}
        located_hashes: set[str] = set()
        for locator in branch["sourceLocators"]:
            locator_hash = locator.get("artifactSha256")
            retrieved_at = locator.get("retrievedAt")
            if locator_hash is None or retrieved_at is None:
                raise ContractError(f"{code} {branch_name} locator lacks hash or retrieval time")
            if locator_hash not in expected_hashes:
                raise ContractError(f"{code} {branch_name} locator is not bound to a cited authority")
            if _parse_datetime(retrieved_at) > registry_as_of:
                raise ContractError(f"{code} {branch_name} locator retrieval is after registry asOf")
            matching_scopes = {
                source["sourceLocator"]["hashScope"]
                for source in branch_sources
                if source["snapshotSha256"] == locator_hash
            }
            if locator.get("hashScope") not in {None, *matching_scopes}:
                raise ContractError(f"{code} {branch_name} locator hash scope disagrees with authority")
            located_hashes.add(locator_hash)
        if located_hashes != expected_hashes:
            raise ContractError(f"{code} {branch_name} does not locate every cited authority snapshot")

    for criterion in criteria:
        code = criterion["code"]
        expected_direction, expected_strength = ORIGINAL_CODES_AND_STRENGTHS[code]
        if criterion["criterionId"] != f"ACMG_AMP_2015:{code}":
            raise ContractError(f"criterion ID mismatch for {code}")
        if criterion["direction"] != expected_direction:
            raise ContractError(f"original direction mismatch for {code}")
        if criterion["original2015"]["strength"] != expected_strength:
            raise ContractError(f"original 2015 strength mismatch for {code}")
        if criterion["original2015"]["status"] != "ORIGINAL_2015":
            raise ContractError(f"original status mismatch for {code}")
        if criterion["original2015"]["authoritySourceIds"] != [
            "authority-acmg-amp-2015-richards"
        ]:
            raise ContractError(f"original 2015 authority mismatch for {code}")
        validate_branch_provenance(code, "original2015", criterion["original2015"])

        current = criterion["currentGeneral"]
        validate_branch_provenance(code, "currentGeneral", current)

        if current["useStatus"] == "ACTIVE_BASELINE_2015":
            if current["changeReasonCode"] != "NO_RELEASED_GENERAL_REPLACEMENT_FOUND_AT_CUTOFF":
                raise ContractError(f"baseline status for {code} overclaims current approval")
            if (
                current["designation"] != code
                or current["strength"] != expected_strength
                or current["authoritySourceIds"] != ["authority-acmg-amp-2015-richards"]
                or set(current.get("allowedStrengths", [expected_strength]))
                != {expected_strength}
            ):
                raise ContractError(f"active 2015 baseline drift for {code}")
        allowed_strengths = current.get("allowedStrengths")
        if allowed_strengths is not None and current["strength"] not in allowed_strengths:
            raise ContractError(f"{code} current strength is outside allowedStrengths")
        if code in {"PP5", "BP6"}:
            if current["useStatus"] != "NOT_IN_CURRENT_USE" or current["strength"] != "NONE":
                raise ContractError(f"{code} must be historical and disabled for current use")
            if criterion["automationDisposition"] != "NOT_USED":
                raise ContractError(f"{code} must not be automation eligible")
        if code == "PM2":
            if expected_strength != "MODERATE":
                raise ContractError("PM2 original 2015 strength must remain Moderate")
            if (
                current["designation"] != "PM2_SUPPORTING"
                or current["strength"] != "SUPPORTING"
                or current["useStatus"] != "ACTIVE_MODIFIED"
            ):
                raise ContractError("PM2 current general state must be PM2_Supporting")

        exact_general_strengths = {
            "PM5": ({"MODERATE"}, "FIXED_GENERAL"),
            "BS1": ({"STRONG"}, "FIXED_GENERAL"),
            "PP3": ({"STRONG", "MODERATE", "SUPPORTING"}, "PINNED_CALIBRATION_PROFILE"),
            "BP4": ({"STRONG", "MODERATE", "SUPPORTING"}, "PINNED_CALIBRATION_PROFILE"),
        }
        if code in exact_general_strengths:
            expected_strengths, expected_policy = exact_general_strengths[code]
            if set(current.get("allowedStrengths", [])) != expected_strengths:
                raise ContractError(f"{code} current-general allowed strengths are not authority-bound")
            if current.get("strengthResolutionPolicy") != expected_policy:
                raise ContractError(f"{code} strength resolution policy is not frozen correctly")


def validate_cspec_registry(
    registry: Mapping[str, Any],
    catalog: Mapping[str, Any],
    rule_registry: Mapping[str, Any],
    schemas: ContractSchemas,
    *,
    authority_path: Path = AUTHORITY_PATH,
    rule_registry_path: Path = RULE_REGISTRY_PATH,
    successor_alias_identities: Mapping[tuple[str, str], set[tuple[str, ...]]] | None = None,
) -> None:
    schemas.validate(registry, "urn:varvis:schema:cspec-overlay-registry-v1")
    if registry["authorityCatalogId"] != catalog["catalogId"]:
        raise ContractError("CSpec authority catalog ID mismatch")
    if registry["authorityCatalogSha256"] != sha256_file(authority_path):
        raise ContractError("CSpec authority catalog fingerprint mismatch")
    if registry["baseRuleRegistryId"] != rule_registry["registryId"]:
        raise ContractError("CSpec base rule registry ID mismatch")
    if registry["baseRuleRegistrySha256"] != sha256_file(rule_registry_path):
        raise ContractError("CSpec base rule registry fingerprint mismatch")
    if registry["authorityCutoff"] != catalog["authorityCutoff"]:
        raise ContractError("CSpec authority cutoff mismatch")
    if registry["asOf"] != catalog["asOf"] or rule_registry["asOf"] != catalog["asOf"]:
        raise ContractError("CSpec, rule and authority registries must share one frozen asOf")

    source_map = {source["authoritySourceId"]: source for source in catalog["sources"]}
    criterion_ids = {criterion["criterionId"] for criterion in rule_registry["criteria"]}
    overlays = registry["overlays"]
    overlay_ids = [overlay["overlayId"] for overlay in overlays]
    _assert_unique(overlay_ids, "CSpec overlay IDs")
    overlay_map = {overlay["overlayId"]: overlay for overlay in overlays}
    cutoff = _parse_date(registry["authorityCutoff"])
    registry_as_of = _parse_datetime(registry["asOf"])

    policy = registry["selectionPolicy"]
    required_failure_reasons = {
        "singleNonSelectableMatch": "CSPEC_MATCH_NOT_EXECUTABLE",
        "multipleExactMatches": "CSPEC_MULTIPLE_EXACT_MATCHES",
        "contextConflict": "CSPEC_CONTEXT_CONFLICT",
    }
    for outcome_name, reason_code in required_failure_reasons.items():
        outcome = policy["outcomes"][outcome_name]
        if outcome["combinationDisposition"] != "ABSTAIN":
            raise ContractError(f"CSpec {outcome_name} must force combination abstention")
        if outcome.get("reasonCode") != reason_code:
            raise ContractError(f"CSpec {outcome_name} has an unstable abstain reason code")

    for overlay in overlays:
        for source_id in overlay["authoritySourceIds"]:
            source = source_map.get(source_id)
            if source is None:
                raise ContractError(f"CSpec overlay references missing authority: {source_id}")
            if overlay["normativeEligibility"] == "ELIGIBLE" and (
                source["publicationStatus"] != "RELEASED"
                or source["normativeEligibility"] != "ELIGIBLE"
            ):
                raise ContractError(f"eligible overlay references ineligible authority: {source_id}")
        if _parse_date(overlay["approvalDate"]) > cutoff:
            raise ContractError(f"overlay approved after cutoff: {overlay['overlayId']}")
        if overlay["overlayId"] != f"cspec-overlay-{overlay['cspecId'].lower()}-{overlay['version']}":
            raise ContractError(f"CSpec overlay ID/version mismatch: {overlay['overlayId']}")

        aliases = overlay["versionAliases"]
        raw_versions = [alias["rawVersion"] for alias in aliases]
        _assert_unique(raw_versions, f"CSpec raw version aliases for {overlay['overlayId']}")
        if overlay["version"] not in raw_versions:
            raise ContractError(f"CSpec canonical version is missing from aliases: {overlay['overlayId']}")
        if any(alias["canonicalVersion"] != overlay["version"] for alias in aliases):
            raise ContractError(f"CSpec version alias maps outside canonical version: {overlay['overlayId']}")
        for alias in aliases:
            expected_source_record = f"CSpec:{overlay['cspecId']}@{alias['rawVersion']}"
            if alias["sourceRecordId"] != expected_source_record:
                raise ContractError(f"CSpec alias source record is not canonical: {overlay['overlayId']}")
            if (
                alias["snapshotSourceRecordId"] != overlay["snapshotSourceRecordId"]
                or alias["snapshotSha256"] != overlay["snapshotSha256"]
            ):
                raise ContractError(f"CSpec alias is not bound to the normative snapshot: {overlay['overlayId']}")
        identity_key = (overlay["cspecId"], overlay["version"])
        frozen_aliases = FROZEN_CSPEC_ALIAS_IDENTITIES.get(identity_key)
        if successor_alias_identities and identity_key in successor_alias_identities:
            if frozen_aliases is not None:
                raise ContractError("successor cannot override a frozen CSpec alias identity")
            frozen_aliases = successor_alias_identities[identity_key]
        actual_aliases = {
            (
                alias["rawVersion"],
                alias["sourceRecordId"],
                alias["apiLdhId"],
                str(alias["apiRevision"]),
                alias["snapshotSourceRecordId"],
                alias["snapshotSha256"],
            )
            for alias in aliases
        }
        if frozen_aliases is None or actual_aliases != frozen_aliases:
            raise ContractError(f"CSpec aliases are not the observed frozen set: {overlay['overlayId']}")
        expected_alias_set_hash = canonical_sha256(
            sorted(aliases, key=lambda alias: alias["rawVersion"])
        )
        if overlay["versionAliasSetSha256"] != expected_alias_set_hash:
            raise ContractError(f"CSpec version alias set hash drift: {overlay['overlayId']}")
        deduplication = overlay["versionDeduplication"]
        if set(deduplication["identityFields"]) != CSPEC_DEDUPLICATION_IDENTITY_FIELDS:
            raise ContractError(f"CSpec deduplication identity fields are incomplete: {overlay['overlayId']}")
        alias_identities = {
            (
                overlay["cspecId"],
                alias["canonicalVersion"],
                alias["apiLdhId"],
                str(alias["apiRevision"]),
                alias["snapshotSha256"],
            )
            for alias in aliases
        }
        if len(aliases) == 1:
            expected_deduplication_status = "UNIQUE"
        elif len(alias_identities) == 1:
            expected_deduplication_status = "DEDUPLICATED"
        else:
            expected_deduplication_status = "IDENTITY_CONFLICT"
        if deduplication["status"] != expected_deduplication_status:
            raise ContractError(
                f"CSpec deduplication status does not match observed aliases: {overlay['overlayId']}"
            )
        if deduplication["status"] in {"IDENTITY_CONFLICT", "NOT_EVALUATED"} and (
            overlay["selectionStatus"] != "NOT_SELECTABLE"
        ):
            raise ContractError(f"unresolved CSpec identity cannot be selected: {overlay['overlayId']}")
        if deduplication["reasonCode"] != "CSPEC_VERSION_ALIAS_IDENTITY_CONFLICT":
            raise ContractError(f"CSpec alias conflict reason is not stable: {overlay['overlayId']}")

        locator_map = {
            locator.get("sourceRecordId"): locator
            for locator in overlay["sourceLocators"]
            if locator.get("sourceRecordId")
        }
        snapshot_locator = locator_map.get(overlay["snapshotSourceRecordId"])
        if snapshot_locator is None:
            raise ContractError(f"CSpec snapshot source record is missing: {overlay['overlayId']}")
        if snapshot_locator.get("artifactSha256") != overlay["snapshotSha256"]:
            raise ContractError(f"CSpec snapshot source/hash mismatch: {overlay['overlayId']}")
        json_artifacts = [
            locator
            for locator in overlay["sourceLocators"]
            if locator.get("artifactRole") == "CSPEC_API_EXPORT_JSON"
        ]
        pdf_artifacts = [
            locator
            for locator in overlay["sourceLocators"]
            if locator.get("artifactRole") == "CSPEC_SPECIFICATION_PDF"
        ]
        if overlay["publicationStatus"] == "RELEASED" and (
            len(json_artifacts) != 1 or len(pdf_artifacts) != 1
        ):
            raise ContractError(
                f"released CSpec overlay requires one immutable JSON and one PDF artifact: {overlay['overlayId']}"
            )
        if any(locator.get("hashScope") != "RAW_BYTES" for locator in json_artifacts + pdf_artifacts):
            raise ContractError(f"CSpec immutable artifacts must hash raw bytes: {overlay['overlayId']}")
        for locator in json_artifacts + pdf_artifacts:
            if (
                locator.get("locatorType") != "REPOSITORY_ARTIFACT"
                or not locator.get("artifactUrl")
                or not locator.get("artifactSha256")
                or not locator.get("sourceRecordId")
                or not locator.get("retrievedAt")
            ):
                raise ContractError(f"CSpec immutable artifact provenance is incomplete: {overlay['overlayId']}")
            if _parse_datetime(locator["retrievedAt"]) > registry_as_of:
                raise ContractError(f"CSpec artifact retrieval is after registry asOf: {overlay['overlayId']}")
        if json_artifacts:
            json_artifact = json_artifacts[0]
            if (
                json_artifact.get("artifactMediaType") != "application/json"
                or json_artifact.get("sourceRecordId") != overlay["snapshotSourceRecordId"]
                or json_artifact.get("artifactSha256") != overlay["snapshotSha256"]
            ):
                raise ContractError(f"CSpec JSON artifact does not bind the replay snapshot: {overlay['overlayId']}")
        if pdf_artifacts:
            pdf_artifact = pdf_artifacts[0]
            if pdf_artifact.get("artifactMediaType") != "application/pdf":
                raise ContractError(f"CSpec PDF artifact has the wrong media type: {overlay['overlayId']}")
        if json_artifacts and pdf_artifacts:
            json_artifact = json_artifacts[0]
            pdf_artifact = pdf_artifacts[0]
            if any(
                json_artifact[field] == pdf_artifact[field]
                for field in ("sourceRecordId", "artifactUrl", "artifactSha256")
            ):
                raise ContractError(f"CSpec JSON and PDF artifacts must have independent identity: {overlay['overlayId']}")
        for source_id in overlay["authoritySourceIds"]:
            source = source_map[source_id]
            if source["snapshotSha256"] != overlay["snapshotSha256"]:
                raise ContractError(f"CSpec overlay and authority snapshot differ: {overlay['overlayId']}")
        if overlay["selectionStatus"] == "SELECTABLE":
            selector = overlay["selector"]
            required_selector = {"hgncId", "mondoId", "moi"}
            if not required_selector.issubset(selector):
                raise ContractError(f"selectable overlay has incomplete selector: {overlay['overlayId']}")
            if overlay["transcriptRequired"] and "transcript" not in selector:
                raise ContractError(f"selectable overlay lacks required transcript: {overlay['overlayId']}")
            if overlay["implementationStatus"] != "IMPLEMENTED":
                raise ContractError(f"metadata-only overlay cannot be selected: {overlay['overlayId']}")
            if not overlay["criterionSpecifications"]:
                raise ContractError(f"selectable overlay has no criterion specifications: {overlay['overlayId']}")
        if overlay["implementationStatus"] == "METADATA_ONLY":
            if overlay["selectionStatus"] != "NOT_SELECTABLE":
                raise ContractError(f"metadata-only overlay must be disabled: {overlay['overlayId']}")
            if overlay["criterionSpecifications"]:
                raise ContractError(
                    f"metadata-only overlay must not expose unreviewed executable criteria: {overlay['overlayId']}"
                )
        selector = overlay["selector"]
        if overlay["transcriptRequired"] and "transcript" not in selector:
            raise ContractError(f"CSpec overlay lacks its required transcript: {overlay['overlayId']}")
        if not overlay["transcriptRequired"] and "transcript" in selector:
            raise ContractError(f"CSpec overlay has an undeclared transcript restriction: {overlay['overlayId']}")
        for specification in overlay["criterionSpecifications"]:
            if specification["criterionId"] not in criterion_ids:
                raise ContractError(
                    f"overlay {overlay['overlayId']} references unknown criterion {specification['criterionId']}"
                )
        replaced_by = overlay.get("replacedByOverlayId")
        if replaced_by and replaced_by not in overlay_map:
            raise ContractError(f"overlay replacement target missing: {replaced_by}")

    for overlay in overlays:
        seen: set[str] = set()
        current = overlay
        while current.get("replacedByOverlayId"):
            current_id = current["overlayId"]
            if current_id in seen:
                raise ContractError(f"CSpec replacement cycle at {current_id}")
            seen.add(current_id)
            current = overlay_map[current["replacedByOverlayId"]]


def _without(mapping: Mapping[str, Any], *keys: str) -> dict[str, Any]:
    excluded = set(keys)
    return {key: copy.deepcopy(value) for key, value in mapping.items() if key not in excluded}


def _sorted_objects(values: list[Any]) -> list[Any]:
    return sorted(copy.deepcopy(values), key=canonical_json_bytes)


def deterministic_trace_projection(
    trace: Mapping[str, Any], *, include_hash: bool = True
) -> dict[str, Any]:
    projection = copy.deepcopy(trace) if include_hash else _without(trace, "traceSha256")
    projection["inputEvidenceIds"] = sorted(projection["inputEvidenceIds"])
    projection["parameters"] = _sorted_objects(projection["parameters"])
    for step in projection["steps"]:
        step["inputEvidenceIds"] = sorted(step["inputEvidenceIds"])
    return projection


def combination_trace_projection(
    trace: Mapping[str, Any], *, include_hash: bool = True
) -> dict[str, Any]:
    projection = copy.deepcopy(trace) if include_hash else _without(trace, "traceSha256")
    projection["parameters"] = _sorted_objects(projection["parameters"])
    return projection


def allele_identity_projection(context: Mapping[str, Any]) -> dict[str, Any]:
    variant = context["variant"]
    normalization = variant["normalization"]
    return {
        "normalizedAllele": variant["normalizedAllele"],
        "normalizer": {
            "method": normalization["method"],
            "methodVersion": normalization["methodVersion"],
            "referenceSequenceSha256": normalization["referenceSequenceSha256"],
            "refValidation": normalization["refValidation"],
        },
    }


def context_identity_projection(context: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "scope": context["scope"],
        "alleleId": context["variant"]["alleleId"],
        "hgncId": context["gene"]["hgncId"],
        "mondoId": context["disease"]["mondoId"],
        "moi": context["moi"],
        "transcript": {
            "accession": context["transcript"]["accession"],
            "version": context["transcript"]["version"],
        },
        "patientContextId": context["patientContext"]["patientContextId"],
        "familyContextId": context["patientContext"].get("familyContextId"),
    }


def cspec_selection_identity_projection(selection: Mapping[str, Any]) -> dict[str, Any]:
    projection = _without(selection, "selectionId", "conflict", "review")
    projection["candidateOverlayIds"] = sorted(projection["candidateOverlayIds"])
    return projection


def evidence_deduplication_projection(evidence: Mapping[str, Any]) -> dict[str, Any]:
    records = sorted(record["recordId"] for record in evidence["source"]["sourceRecords"])
    return {
        "contextId": evidence["contextId"],
        "evidenceType": evidence["evidenceType"],
        "sourceId": evidence["source"]["sourceId"],
        "sourceRecordIds": records,
        "queryFingerprint": evidence["queryOutcome"].get("queryFingerprint"),
    }


def evidence_identity_projection(evidence: Mapping[str, Any]) -> dict[str, Any]:
    projection = _without(
        evidence,
        "evidenceId",
        "deduplicationKey",
        "provenance",
        "conflict",
        "review",
        "legacyAdapterRecordId",
    )
    provenance = evidence["provenance"]
    projection["provenance"] = {
        "producerId": provenance["producerId"],
        "producerVersion": provenance["producerVersion"],
        "sourcePayloadSha256": provenance["sourcePayloadSha256"],
    }
    source = _without(evidence["source"], "retrievedAt")
    source["sourceLocators"] = _sorted_objects(
        [_without(locator, "retrievedAt") for locator in source["sourceLocators"]]
    )
    source["sourceRecords"] = _sorted_objects(
        [
            {
                "recordId": record["recordId"],
                "locator": _without(record["locator"], "retrievedAt"),
            }
            for record in source["sourceRecords"]
        ]
    )
    projection["source"] = source
    projection["resourceExecution"] = _without(
        evidence["resourceExecution"], "attemptedAt", "completedAt"
    )
    projection["measurements"] = _sorted_objects(projection["measurements"])
    projection["derivedFromEvidenceIds"] = sorted(projection["derivedFromEvidenceIds"])
    return projection


def evaluation_identity_projection(evaluation: Mapping[str, Any]) -> dict[str, Any]:
    projection = _without(evaluation, "evaluationId", "conflict", "review")
    projection["evidenceIds"] = sorted(projection["evidenceIds"])
    projection["ruleBinding"]["authoritySourceIds"] = sorted(
        projection["ruleBinding"]["authoritySourceIds"]
    )
    projection["deterministicTrace"] = deterministic_trace_projection(
        projection["deterministicTrace"]
    )
    return projection


def review_identity_projection(review: Mapping[str, Any]) -> dict[str, Any]:
    projection = _without(review, "reviewRecordId")
    if "sourceLocators" in projection:
        projection["sourceLocators"] = _sorted_objects(projection["sourceLocators"])
    return projection


def conflict_identity_projection(conflict: Mapping[str, Any]) -> dict[str, Any]:
    projection = _without(conflict, "conflictId")
    projection["participantIds"] = sorted(projection["participantIds"])
    return projection


def combination_identity_projection(combination: Mapping[str, Any]) -> dict[str, Any]:
    projection = _without(combination, "combinationId", "conflict", "review")
    projection["criterionEvaluationIds"] = sorted(projection["criterionEvaluationIds"])
    if "combinationSpecification" in projection:
        projection["combinationSpecification"]["authoritySourceIds"] = sorted(
            projection["combinationSpecification"]["authoritySourceIds"]
        )
    if "trace" in projection:
        projection["trace"] = combination_trace_projection(projection["trace"])
    return projection


def adapter_identity_projection(adapter: Mapping[str, Any]) -> dict[str, Any]:
    projection = _without(adapter, "adapterRecordId")
    projection["warnings"] = sorted(projection["warnings"])
    projection["outputEvidenceIds"] = sorted(projection["outputEvidenceIds"])
    return projection


def bundle_identity_projection(bundle: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "sourceOfTruth": bundle["sourceOfTruth"],
        "dataClass": bundle["dataClass"],
        "producer": bundle["producer"],
        "authorityBinding": bundle["authorityBinding"],
        "contextId": bundle["context"]["contextId"],
        "cspecSelectionId": bundle["cspecSelection"]["selectionId"],
        "evidenceIds": sorted(evidence["evidenceId"] for evidence in bundle["atomicEvidence"]),
        "evaluationIds": sorted(
            evaluation["evaluationId"] for evaluation in bundle["criterionEvaluations"]
        ),
        "reviewRecordIds": sorted(
            review["reviewRecordId"] for review in bundle["reviewRecords"]
        ),
        "conflictIds": sorted(conflict["conflictId"] for conflict in bundle["conflicts"]),
        "adapterRecordIds": sorted(
            adapter["adapterRecordId"] for adapter in bundle["legacyAdapterRecords"]
        ),
        "combinationId": bundle["combination"]["combinationId"],
        "candidateOrderingChanged": bundle["candidateOrderingChanged"],
        "clinicalConclusionClaimed": bundle["clinicalConclusionClaimed"],
    }


def _replace_ids(value: Any, replacements: Mapping[str, str]) -> Any:
    if isinstance(value, str):
        return replacements.get(value, value)
    if isinstance(value, list):
        return [_replace_ids(item, replacements) for item in value]
    if isinstance(value, dict):
        return {key: _replace_ids(item, replacements) for key, item in value.items()}
    return value


def rehash_bundle(bundle: Mapping[str, Any]) -> dict[str, Any]:
    """Return a deep-copied bundle with all contract-owned content IDs recalculated."""

    result = copy.deepcopy(bundle)
    replacements: dict[str, str] = {}

    context = result["context"]
    old_allele_id = context["variant"]["alleleId"]
    new_allele_id = content_id("allele-sha256-", allele_identity_projection(context))
    context["variant"]["alleleId"] = new_allele_id
    replacements[old_allele_id] = new_allele_id
    old_context_id = context["contextId"]
    new_context_id = content_id("context-sha256-", context_identity_projection(context))
    context["contextId"] = new_context_id
    replacements[old_context_id] = new_context_id
    result = _replace_ids(result, replacements)

    selection = result["cspecSelection"]
    old_selection_id = selection["selectionId"]
    new_selection_id = content_id(
        "cspec-selection-sha256-", cspec_selection_identity_projection(selection)
    )
    selection["selectionId"] = new_selection_id
    result = _replace_ids(result, {old_selection_id: new_selection_id})

    replacements = {}
    evidence_items = result["atomicEvidence"]
    old_evidence_ids = {evidence["evidenceId"] for evidence in evidence_items}
    pending_evidence = list(evidence_items)
    while pending_evidence:
        progressed = False
        for evidence in list(pending_evidence):
            unresolved_parents = {
                parent_id
                for parent_id in evidence["derivedFromEvidenceIds"]
                if parent_id in old_evidence_ids and parent_id not in replacements
            }
            if unresolved_parents:
                continue
            old_evidence_id = evidence["evidenceId"]
            updated_evidence = _replace_ids(evidence, replacements)
            evidence.clear()
            evidence.update(updated_evidence)
            evidence["deduplicationKey"] = content_id(
                "dedup-sha256-", evidence_deduplication_projection(evidence)
            )
            digest = canonical_sha256(evidence_identity_projection(evidence))
            evidence["provenance"]["identityProjectionSha256"] = digest
            new_evidence_id = f"evidence-sha256-{digest}"
            evidence["evidenceId"] = new_evidence_id
            replacements[old_evidence_id] = new_evidence_id
            pending_evidence.remove(evidence)
            progressed = True
        if not progressed:
            raise ContractError("cannot rehash cyclic derived evidence")
    result = _replace_ids(result, replacements)

    replacements = {}
    for evaluation in result["criterionEvaluations"]:
        trace = evaluation["deterministicTrace"]
        if trace["executionStatus"] == "COMPLETED":
            trace["traceSha256"] = canonical_sha256(
                deterministic_trace_projection(trace, include_hash=False)
            )
        old_evaluation_id = evaluation["evaluationId"]
        new_evaluation_id = content_id(
            "evaluation-sha256-", evaluation_identity_projection(evaluation)
        )
        evaluation["evaluationId"] = new_evaluation_id
        replacements[old_evaluation_id] = new_evaluation_id
    result = _replace_ids(result, replacements)

    replacements = {}
    for adapter in result["legacyAdapterRecords"]:
        old_adapter_id = adapter["adapterRecordId"]
        new_adapter_id = content_id("adapter-sha256-", adapter_identity_projection(adapter))
        adapter["adapterRecordId"] = new_adapter_id
        replacements[old_adapter_id] = new_adapter_id
    result = _replace_ids(result, replacements)

    combination = result["combination"]
    if combination.get("trace"):
        combination["trace"]["traceSha256"] = canonical_sha256(
            combination_trace_projection(combination["trace"], include_hash=False)
        )
    old_combination_id = combination["combinationId"]
    new_combination_id = content_id(
        "combination-sha256-", combination_identity_projection(combination)
    )
    combination["combinationId"] = new_combination_id
    result = _replace_ids(result, {old_combination_id: new_combination_id})

    replacements = {}
    review_records = result["reviewRecords"]
    old_review_ids = {review["reviewRecordId"] for review in review_records}
    pending_reviews = list(review_records)
    while pending_reviews:
        progressed = False
        for review in list(pending_reviews):
            previous_id = review.get("previousReviewRecordId")
            if previous_id in old_review_ids and previous_id not in replacements:
                continue
            old_review_id = review["reviewRecordId"]
            updated_review = _replace_ids(review, replacements)
            review.clear()
            review.update(updated_review)
            new_review_id = content_id("review-sha256-", review_identity_projection(review))
            review["reviewRecordId"] = new_review_id
            replacements[old_review_id] = new_review_id
            pending_reviews.remove(review)
            progressed = True
        if not progressed:
            raise ContractError("cannot rehash cyclic review history")
    result = _replace_ids(result, replacements)

    replacements = {}
    for conflict in result["conflicts"]:
        old_conflict_id = conflict["conflictId"]
        new_conflict_id = content_id("conflict-sha256-", conflict_identity_projection(conflict))
        conflict["conflictId"] = new_conflict_id
        replacements[old_conflict_id] = new_conflict_id
    result = _replace_ids(result, replacements)

    result["bundleId"] = content_id("bundle-sha256-", bundle_identity_projection(result))
    return result


def _assert_context_scope(context: Mapping[str, Any]) -> None:
    if context["contextCompleteness"]["status"] != "COMPLETE":
        raise ContractError("E1 requires a complete core interpretation context")
    normalized = context["variant"]["normalizedAllele"]
    if max(len(normalized["reference"]), len(normalized["alternate"])) > 50:
        raise ContractError("normalized allele exceeds the E1 50 bp small-variant boundary")
    if normalized["reference"] == normalized["alternate"]:
        raise ContractError("normalized REF and ALT must differ")
    variant = context["variant"]
    lineage = variant["assemblyLineage"]
    input_allele = variant["inputAllele"]
    query_allele = variant["evidenceQueryAllele"]
    input_assembly = input_allele["assembly"]
    normalized_assembly = normalized["assembly"]
    query_assembly = query_allele["assembly"]
    if input_assembly != normalized_assembly:
        raise ContractError("normalization cannot silently change assembly")
    if variant["normalization"]["status"] == "UNCHANGED" and input_allele != normalized:
        raise ContractError("UNCHANGED normalization must preserve the complete input allele")
    if input_assembly != query_assembly and lineage["mappingStatus"] != "MAPPED":
        raise ContractError("cross-assembly evidence requires explicit MAPPED lineage")
    if lineage["mappingStatus"] == "NATIVE" and query_allele != normalized:
        raise ContractError("NATIVE evidence query allele must equal the normalized allele")
    if lineage["mappingStatus"] == "MAPPED":
        mapping = lineage["coordinateMapping"]
        if normalized_assembly == query_assembly:
            raise ContractError("MAPPED lineage must cross assemblies")
        if mapping["sourceAllele"] != normalized:
            raise ContractError("coordinate mapping source is not the normalized allele")
        if mapping["targetAllele"] != query_allele:
            raise ContractError("coordinate mapping target is not the evidence query allele")
    if lineage["mappingStatus"] in {"UNMAPPED", "REF_MISMATCH"}:
        raise ContractError("unmapped or REF-mismatched context cannot be evaluated")


def _assert_atomic_state(evidence: Mapping[str, Any]) -> None:
    execution = evidence["resourceExecution"]["status"]
    availability = evidence["resourceAvailability"]["status"]
    query = evidence["queryOutcome"]["status"]
    applicability = evidence["resourceApplicability"]["status"]
    records = evidence["source"]["sourceRecords"]
    record_count = evidence["queryOutcome"]["recordCount"]

    if query in {"HIT", "NO_RECORD"}:
        if execution != "SUCCEEDED" or availability != "AVAILABLE":
            raise ContractError(
                f"{query} requires SUCCEEDED execution and AVAILABLE resource"
            )
    if execution == "SUCCEEDED" and query not in {"HIT", "NO_RECORD"}:
        raise ContractError("SUCCEEDED resource execution must have a query outcome")
    if execution in {"NOT_RUN", "FAILED", "TIMEOUT", "DISABLED"} and query != "NOT_QUERIED":
        raise ContractError(f"{execution} cannot be mixed with {query}")
    if availability == "UNAVAILABLE" and query != "NOT_QUERIED":
        raise ContractError("UNAVAILABLE cannot be mixed with HIT or NO_RECORD")
    if query == "HIT":
        if record_count != len(records) or not records:
            raise ContractError("HIT requires located source records matching recordCount")
    if query == "NO_RECORD":
        if records or record_count != 0 or evidence["measurements"]:
            raise ContractError("NO_RECORD must retain no record or measurement; it is not AF=0")
    if query == "NOT_QUERIED" and (records or evidence["measurements"]):
        raise ContractError("NOT_QUERIED cannot contain source records or measurements")
    if applicability == "APPLICABLE" and execution in {"FAILED", "TIMEOUT", "DISABLED"}:
        raise ContractError("failed or disabled resources cannot assert an applicable result")


def _overlay_context_match(overlay: Mapping[str, Any], context: Mapping[str, Any]) -> bool:
    selector = overlay["selector"]
    if selector.get("hgncId") != context["gene"]["hgncId"]:
        return False
    if selector.get("mondoId") != context["disease"]["mondoId"]:
        return False
    if selector.get("moi") != context["moi"]:
        return False
    if not overlay["transcriptRequired"]:
        return True
    transcript = selector.get("transcript")
    if transcript is None:
        return False
    if (
        transcript["accession"] != context["transcript"]["accession"]
        or transcript["version"] != context["transcript"]["version"]
    ):
        return False
    return True


def _exact_overlay_match(overlay: Mapping[str, Any], context: Mapping[str, Any]) -> bool:
    return overlay["selectionStatus"] == "SELECTABLE" and _overlay_context_match(
        overlay, context
    )


def validate_bundle(
    bundle: Mapping[str, Any],
    catalog: Mapping[str, Any],
    rule_registry: Mapping[str, Any],
    cspec_registry: Mapping[str, Any],
    schemas: ContractSchemas,
    *,
    authority_path: Path = AUTHORITY_PATH,
    rule_registry_path: Path = RULE_REGISTRY_PATH,
    cspec_registry_path: Path = CSPEC_REGISTRY_PATH,
) -> None:
    schemas.validate(bundle, "urn:varvis:schema:evidence-bundle-v2")
    if bundle["dataClass"] == "SYNTHETIC" and bundle["context"]["dataClass"] != "SYNTHETIC":
        raise ContractError("synthetic bundle must carry a synthetic context")
    if bundle["sourceOfTruth"] != "ACMG_EVIDENCE_V2":
        raise ContractError("evidence-v2 must be the sole source of truth")
    if bundle["candidateOrderingChanged"] or bundle["clinicalConclusionClaimed"]:
        raise ContractError("E1 cannot change ranking or claim a clinical conclusion")

    binding = bundle["authorityBinding"]
    expected_bindings = {
        "authorityCatalogId": catalog["catalogId"],
        "authorityCatalogSha256": sha256_file(authority_path),
        "ruleRegistryId": rule_registry["registryId"],
        "ruleRegistrySha256": sha256_file(rule_registry_path),
        "cspecOverlayRegistryId": cspec_registry["registryId"],
        "cspecOverlayRegistrySha256": sha256_file(cspec_registry_path),
        "authorityCutoff": catalog["authorityCutoff"],
        "asOf": catalog["asOf"],
    }
    for key, expected in expected_bindings.items():
        if binding[key] != expected:
            raise ContractError(f"bundle authority binding mismatch for {key}")
    if rule_registry["asOf"] != catalog["asOf"] or cspec_registry["asOf"] != catalog["asOf"]:
        raise ContractError("bundle registries do not share the frozen authority asOf")
    bundle_created_at = _parse_datetime(bundle["createdAt"])
    if bundle_created_at < _parse_datetime(binding["asOf"]):
        raise ContractError("bundle cannot predate the authority snapshots it binds")

    context = bundle["context"]
    _assert_context_scope(context)
    expected_allele_id = content_id("allele-sha256-", allele_identity_projection(context))
    if context["variant"]["alleleId"] != expected_allele_id:
        raise ContractError("allele stable ID drift")
    expected_context_id = content_id("context-sha256-", context_identity_projection(context))
    if context["contextId"] != expected_context_id:
        raise ContractError("context stable ID drift")

    context_id = context["contextId"]
    overlay_map = {overlay["overlayId"]: overlay for overlay in cspec_registry["overlays"]}
    selection = bundle["cspecSelection"]
    if selection["contextId"] != context_id:
        raise ContractError("CSpec selection context mismatch")
    if selection["overlayRegistryId"] != cspec_registry["registryId"]:
        raise ContractError("CSpec selection registry ID mismatch")
    if selection["overlayRegistrySha256"] != sha256_file(cspec_registry_path):
        raise ContractError("CSpec selection registry fingerprint mismatch")
    if selection["policyId"] != cspec_registry["selectionPolicy"]["policyId"]:
        raise ContractError("CSpec selection policy mismatch")
    candidate_ids = selection["candidateOverlayIds"]
    for overlay_id in candidate_ids:
        if overlay_id not in overlay_map:
            raise ContractError(f"CSpec selection candidate missing: {overlay_id}")
    exact_matches = [
        overlay["overlayId"]
        for overlay in cspec_registry["overlays"]
        if _overlay_context_match(overlay, context)
    ]
    if not exact_matches:
        expected_selection_outcome = "CONTEXT_CONFLICT" if candidate_ids else "ZERO_MATCH"
        if candidate_ids and any(overlay_id in exact_matches for overlay_id in candidate_ids):
            raise ContractError("context-conflicting CSpec candidates cannot be exact matches")
    elif len(exact_matches) == 1:
        expected_candidate = exact_matches[0]
        if candidate_ids != [expected_candidate]:
            raise ContractError("single CSpec match must be retained as the only candidate")
        expected_selection_outcome = (
            "SINGLE_SELECTABLE_MATCH"
            if overlay_map[expected_candidate]["selectionStatus"] == "SELECTABLE"
            else "SINGLE_NON_SELECTABLE_MATCH"
        )
    else:
        if set(candidate_ids) != set(exact_matches):
            raise ContractError("multiple exact CSpec matches must all be retained")
        expected_selection_outcome = "MULTIPLE_EXACT_MATCHES"
    if selection["outcome"] != expected_selection_outcome:
        raise ContractError("CSpec selection outcome does not match the frozen policy")

    selection_policy_key = {
        "ZERO_MATCH": "zeroMatch",
        "SINGLE_SELECTABLE_MATCH": "singleSelectableMatch",
        "SINGLE_NON_SELECTABLE_MATCH": "singleNonSelectableMatch",
        "MULTIPLE_EXACT_MATCHES": "multipleExactMatches",
        "CONTEXT_CONFLICT": "contextConflict",
    }[selection["outcome"]]
    policy_outcome = cspec_registry["selectionPolicy"]["outcomes"][selection_policy_key]
    if selection.get("reasonCode") != policy_outcome.get("reasonCode"):
        raise ContractError("CSpec selection reason does not match the frozen policy")
    if selection["outcome"] == "SINGLE_SELECTABLE_MATCH":
        selected_id = selection["selectedOverlayId"]
        if selected_id != candidate_ids[0]:
            raise ContractError("selected CSpec overlay is not the sole exact candidate")
        if selection["selectedOverlaySha256"] != overlay_map[selected_id]["snapshotSha256"]:
            raise ContractError("selected CSpec snapshot mismatch")
    if selection["selectionId"] != content_id(
        "cspec-selection-sha256-", cspec_selection_identity_projection(selection)
    ):
        raise ContractError("CSpec selection stable ID drift")

    evidence_items = bundle["atomicEvidence"]
    evidence_ids = [evidence["evidenceId"] for evidence in evidence_items]
    dedup_keys = [evidence["deduplicationKey"] for evidence in evidence_items]
    _assert_unique(evidence_ids, "evidence IDs")
    _assert_unique(dedup_keys, "evidence deduplication keys")
    evidence_map = {evidence["evidenceId"]: evidence for evidence in evidence_items}

    for evidence in evidence_items:
        if evidence["contextId"] != context_id:
            raise ContractError("atomic evidence context mismatch")
        _assert_atomic_state(evidence)
        if _parse_datetime(evidence["source"]["retrievedAt"]) > bundle_created_at:
            raise ContractError("atomic evidence source retrieval is after bundle creation")
        evidence_locators = list(evidence["source"]["sourceLocators"]) + [
            record["locator"] for record in evidence["source"]["sourceRecords"]
        ]
        for locator in evidence_locators:
            retrieved_at = locator.get("retrievedAt")
            if retrieved_at is None or _parse_datetime(retrieved_at) > bundle_created_at:
                raise ContractError("atomic evidence locator retrieval is missing or after bundle creation")
        expected_dedup = content_id(
            "dedup-sha256-", evidence_deduplication_projection(evidence)
        )
        if evidence["deduplicationKey"] != expected_dedup:
            raise ContractError(f"deduplication key drift: {evidence['evidenceId']}")
        expected_digest = canonical_sha256(evidence_identity_projection(evidence))
        if evidence["provenance"]["identityProjectionSha256"] != expected_digest:
            raise ContractError(f"evidence identity projection drift: {evidence['evidenceId']}")
        if evidence["evidenceId"] != f"evidence-sha256-{expected_digest}":
            raise ContractError(f"evidence stable ID drift: {evidence['evidenceId']}")
        for parent_id in evidence["derivedFromEvidenceIds"]:
            if parent_id not in evidence_map:
                raise ContractError(f"derived evidence parent missing: {parent_id}")
        if evidence["conflict"]["status"] == "PRESENT" and evidence["review"]["status"] == "NOT_REQUIRED":
            raise ContractError("conflicting evidence must enter review")

    def visit_evidence(evidence_id: str, active: set[str], complete: set[str]) -> None:
        if evidence_id in active:
            raise ContractError(f"derived evidence cycle at {evidence_id}")
        if evidence_id in complete:
            return
        active.add(evidence_id)
        for parent_id in evidence_map[evidence_id]["derivedFromEvidenceIds"]:
            visit_evidence(parent_id, active, complete)
        active.remove(evidence_id)
        complete.add(evidence_id)

    complete_evidence: set[str] = set()
    for evidence_id in evidence_ids:
        visit_evidence(evidence_id, set(), complete_evidence)

    review_records = bundle["reviewRecords"]
    review_ids = [review["reviewRecordId"] for review in review_records]
    _assert_unique(review_ids, "review record IDs")
    review_map = {review["reviewRecordId"]: review for review in review_records}
    for review in review_records:
        if review["contextId"] != context_id:
            raise ContractError("review context mismatch")
        if _parse_datetime(review["createdAt"]) > bundle_created_at:
            raise ContractError("review record is after bundle creation")
        if review["reviewRecordId"] != content_id(
            "review-sha256-", review_identity_projection(review)
        ):
            raise ContractError(f"review stable ID drift: {review['reviewRecordId']}")
        previous = review.get("previousReviewRecordId")
        if previous and previous not in review_map:
            raise ContractError(f"review predecessor missing: {previous}")

    conflicts = bundle["conflicts"]
    conflict_ids = [conflict["conflictId"] for conflict in conflicts]
    _assert_unique(conflict_ids, "conflict IDs")
    conflict_map = {conflict["conflictId"]: conflict for conflict in conflicts}
    declared_evaluation_ids = {
        evaluation["evaluationId"] for evaluation in bundle["criterionEvaluations"]
    }
    declared_adapter_ids = {
        adapter["adapterRecordId"] for adapter in bundle["legacyAdapterRecords"]
    }
    known_subject_ids = (
        set(evidence_ids)
        | set(review_ids)
        | declared_evaluation_ids
        | declared_adapter_ids
        | set(overlay_map)
        | {selection["selectionId"], bundle["combination"]["combinationId"]}
    )
    for conflict in conflicts:
        if conflict["conflictId"] != content_id(
            "conflict-sha256-", conflict_identity_projection(conflict)
        ):
            raise ContractError(f"conflict stable ID drift: {conflict['conflictId']}")
        for participant_id in conflict["participantIds"]:
            if participant_id not in known_subject_ids:
                raise ContractError(f"conflict participant missing: {participant_id}")
        resolution_review = conflict.get("resolutionReviewRecordId")
        if resolution_review and resolution_review not in review_map:
            raise ContractError(f"conflict resolution review missing: {resolution_review}")

    criterion_map = {
        criterion["criterionId"]: criterion for criterion in rule_registry["criteria"]
    }
    evaluations = bundle["criterionEvaluations"]
    evaluation_ids = [evaluation["evaluationId"] for evaluation in evaluations]
    _assert_unique(evaluation_ids, "criterion evaluation IDs")
    evaluation_map = {evaluation["evaluationId"]: evaluation for evaluation in evaluations}

    for evaluation in evaluations:
        if evaluation["contextId"] != context_id:
            raise ContractError("criterion evaluation context mismatch")
        criterion = criterion_map.get(evaluation["criterionId"])
        if criterion is None:
            raise ContractError(f"unknown criterion: {evaluation['criterionId']}")
        current = criterion["currentGeneral"]
        rule_binding = evaluation["ruleBinding"]
        if rule_binding["ruleRegistryId"] != rule_registry["registryId"]:
            raise ContractError("criterion rule registry ID mismatch")
        if rule_binding["ruleRegistrySha256"] != sha256_file(rule_registry_path):
            raise ContractError("criterion rule registry fingerprint mismatch")
        if rule_binding["currentGeneralDesignation"] != current["designation"]:
            raise ContractError("criterion current-general designation mismatch")
        if set(rule_binding["authoritySourceIds"]) != set(current["authoritySourceIds"]):
            raise ContractError("criterion authority source binding mismatch")
        if rule_binding["cspecSelectionId"] != selection["selectionId"]:
            raise ContractError("criterion does not bind the context CSpec selection")
        result_status = evaluation["evaluationResult"]["status"]
        if criterion["code"] in {"PP5", "BP6"} and result_status == "MET":
            raise ContractError(f"{criterion['code']} is retained historically but cannot be MET")
        expected_base_strength = current["strength"]
        if selection["outcome"] == "SINGLE_SELECTABLE_MATCH":
            selected_overlay = overlay_map[selection["selectedOverlayId"]]
            selected_specification = next(
                (
                    specification
                    for specification in selected_overlay["criterionSpecifications"]
                    if specification["criterionId"] == evaluation["criterionId"]
                ),
                None,
            )
            if selected_specification is not None:
                expected_base_strength = selected_specification["strength"]
        if evaluation["baseStrength"] != expected_base_strength:
            raise ContractError("criterion base strength does not match its pinned rule/overlay")
        if policy_outcome["criterionEvaluationConstraint"] == "FORCE_NOT_EVALUATED":
            if evaluation["evaluationResult"]["status"] != "NOT_EVALUATED":
                raise ContractError("failed CSpec selection cannot produce a criterion result")
            selection_reason = selection["reasonCode"]
            if evaluation["evaluationResult"].get("reasonCode") != selection_reason:
                raise ContractError("CSpec selection reason was not propagated to criterion result")
            if evaluation["criterionApplicability"].get("reasonCode") != selection_reason:
                raise ContractError("CSpec selection reason was not propagated to criterion applicability")
            if evaluation["deterministicTrace"].get("reasonCode") != selection_reason:
                raise ContractError("CSpec selection reason was not propagated to criterion trace")

        referenced_evidence = evaluation["evidenceIds"]
        for evidence_id in referenced_evidence:
            if evidence_id not in evidence_map:
                raise ContractError(f"criterion evidence reference missing: {evidence_id}")
        trace = evaluation["deterministicTrace"]
        for evidence_id in trace["inputEvidenceIds"]:
            if evidence_id not in evidence_map:
                raise ContractError(f"trace evidence reference missing: {evidence_id}")
        if set(trace["inputEvidenceIds"]) != set(referenced_evidence):
            raise ContractError("criterion trace inputs and evidence references differ")
        if trace["executionStatus"] == "COMPLETED":
            expected_trace_hash = canonical_sha256(
                deterministic_trace_projection(trace, include_hash=False)
            )
            if trace["traceSha256"] != expected_trace_hash:
                raise ContractError("criterion deterministic trace hash drift")

        if result_status in {"MET", "NOT_MET"}:
            if criterion["implementationStatus"] != "V2_IMPLEMENTED":
                raise ContractError(
                    f"E1 registry does not authorize executable v2 result for {criterion['code']}"
                )
            if current["useStatus"] == "NOT_IN_CURRENT_USE":
                raise ContractError(f"disabled criterion cannot be evaluated: {criterion['code']}")
            for evidence_id in referenced_evidence:
                evidence = evidence_map[evidence_id]
                if (
                    evidence["resourceExecution"]["status"] != "SUCCEEDED"
                    or evidence["resourceAvailability"]["status"] != "AVAILABLE"
                    or evidence["queryOutcome"]["status"] != "HIT"
                    or evidence["resourceApplicability"]["status"] != "APPLICABLE"
                ):
                    raise ContractError(
                        f"{result_status} cannot be inferred from missing, failed or inapplicable evidence"
                    )

        correlation_groups: dict[str, int] = {}
        for evidence_id in referenced_evidence:
            group = evidence_map[evidence_id].get("correlationGroupId")
            if group:
                correlation_groups[group] = correlation_groups.get(group, 0) + 1
        duplicated_groups = [group for group, count in correlation_groups.items() if count > 1]
        if duplicated_groups:
            raise ContractError(
                f"criterion double counts correlated evidence groups: {sorted(duplicated_groups)}"
            )
        for conflict_id in evaluation["conflict"]["conflictIds"]:
            if conflict_id not in conflict_map:
                raise ContractError(f"criterion conflict reference missing: {conflict_id}")
        for review_id in evaluation["review"]["reviewRecordIds"]:
            if review_id not in review_map:
                raise ContractError(f"criterion review reference missing: {review_id}")
        if evaluation["evaluationId"] != content_id(
            "evaluation-sha256-", evaluation_identity_projection(evaluation)
        ):
            raise ContractError(f"criterion evaluation ID drift: {evaluation['evaluationId']}")

    adapters = bundle["legacyAdapterRecords"]
    adapter_ids = [adapter["adapterRecordId"] for adapter in adapters]
    _assert_unique(adapter_ids, "adapter record IDs")
    adapter_map = {adapter["adapterRecordId"]: adapter for adapter in adapters}
    for adapter in adapters:
        if adapter["adapterRecordId"] != content_id(
            "adapter-sha256-", adapter_identity_projection(adapter)
        ):
            raise ContractError(f"adapter stable ID drift: {adapter['adapterRecordId']}")
        for evidence_id in adapter["outputEvidenceIds"]:
            if evidence_id not in evidence_map:
                raise ContractError(f"adapter output evidence missing: {evidence_id}")
            if evidence_map[evidence_id].get("legacyAdapterRecordId") != adapter["adapterRecordId"]:
                raise ContractError("adapter output evidence must bind back to its adapter record")
        if adapter["inputSchemaVersion"] == "acmg-ready-v1":
            if adapter["eligibleForCriterionEvaluation"] or adapter["eligibleForCombination"]:
                raise ContractError("legacy suggestion cannot become criterion or combination evidence")
            if adapter["outputEvidenceIds"]:
                raise ContractError("acmg-ready-v1 suggestions must remain quarantined")
            promotion = adapter["semanticPromotion"]
            if promotion["suggestedToMet"] or promotion["notSuggestedToNotMet"]:
                raise ContractError("legacy suggestion status semantic promotion is forbidden")
        else:
            mapping_status = adapter["mappingStatus"]
            if mapping_status == "LEGACY_ONLY":
                raise ContractError("LEGACY_ONLY mapping is reserved for acmg-ready-v1")
            if mapping_status == "UNMAPPED" and (
                adapter["outputEvidenceIds"]
                or adapter["eligibleForCriterionEvaluation"]
                or adapter["eligibleForCombination"]
            ):
                raise ContractError("UNMAPPED evidence-item-v1 input must remain quarantined")
            if mapping_status == "PARTIAL" and (
                not adapter["warnings"] or adapter["eligibleForCombination"]
            ):
                raise ContractError("PARTIAL evidence-item-v1 mapping requires warnings and cannot combine")
    for evidence in evidence_items:
        adapter_id = evidence.get("legacyAdapterRecordId")
        if adapter_id is None:
            continue
        adapter = adapter_map.get(adapter_id)
        if adapter is None:
            raise ContractError("atomic evidence references a missing legacy adapter record")
        if evidence["evidenceId"] not in adapter["outputEvidenceIds"]:
            raise ContractError("atomic evidence is not declared by its legacy adapter record")
        consuming_evaluations = {
            evaluation["evaluationId"]
            for evaluation in evaluations
            if evidence["evidenceId"] in evaluation["evidenceIds"]
        }
        if consuming_evaluations and not adapter["eligibleForCriterionEvaluation"]:
            raise ContractError("criterion consumed adapter evidence without criterion eligibility")
        combination_evaluations = set(bundle["combination"]["criterionEvaluationIds"])
        if (
            bundle["combination"]["executionStatus"] == "COMPLETED"
            and consuming_evaluations & combination_evaluations
            and not adapter["eligibleForCombination"]
        ):
            raise ContractError("combination consumed adapter evidence without combination eligibility")

    combination = bundle["combination"]
    if combination["contextId"] != context_id:
        raise ContractError("combination context mismatch")
    for evaluation_id in combination["criterionEvaluationIds"]:
        if evaluation_id not in evaluation_map:
            raise ContractError(f"combination criterion reference missing: {evaluation_id}")
    if combination.get("trace"):
        expected_trace = canonical_sha256(
            combination_trace_projection(combination["trace"], include_hash=False)
        )
        if combination["trace"]["traceSha256"] != expected_trace:
            raise ContractError("combination trace hash drift")
    if combination["result"] == "ABSTAIN" and not combination.get("reasonCode"):
        raise ContractError("ABSTAIN requires reasonCode")
    if (
        combination["executionStatus"] != "NOT_RUN"
        or combination["result"] != "NO_RESULT"
        or combination.get("reasonCode") != "E1_COMBINATION_NOT_IMPLEMENTED"
        or combination.get("combinationSpecification") is not None
        or combination.get("trace") is not None
    ):
        raise ContractError(
            "E1 combination gate requires NOT_RUN/NO_RESULT/E1_COMBINATION_NOT_IMPLEMENTED"
        )
    open_conflicts = [
        conflict for conflict in conflicts if conflict["resolutionStatus"] == "OPEN"
    ]
    has_blocking_selection = policy_outcome["combinationDisposition"] == "ABSTAIN"
    if combination["executionStatus"] == "COMPLETED" and (
        open_conflicts or has_blocking_selection
    ):
        if combination["result"] != "ABSTAIN":
            raise ContractError("unresolved conflict or CSpec ambiguity must combine to ABSTAIN")
        if has_blocking_selection and combination.get("reasonCode") != selection.get("reasonCode"):
            raise ContractError("CSpec selection abstain reason was not propagated to combination")
    if combination["combinationId"] != content_id(
        "combination-sha256-", combination_identity_projection(combination)
    ):
        raise ContractError("combination stable ID drift")

    review_subject_ids = {
        "ATOMIC_EVIDENCE": set(evidence_ids),
        "CRITERION_EVALUATION": set(evaluation_ids),
        "CSPEC_SELECTION": {selection["selectionId"]},
        "COMBINATION": {combination["combinationId"]},
    }
    for review in review_records:
        if review["subjectId"] not in review_subject_ids[review["subjectType"]]:
            raise ContractError(
                f"review subject type/ID mismatch: {review['subjectType']} {review['subjectId']}"
            )

    def assert_review_summary(
        summary: Mapping[str, Any], subject_type: str, subject_id: str
    ) -> None:
        for review_id in summary["reviewRecordIds"]:
            review = review_map.get(review_id)
            if review is None:
                raise ContractError(f"review summary record missing: {review_id}")
            if review["subjectType"] != subject_type or review["subjectId"] != subject_id:
                raise ContractError("review summary points to a review for a different subject")

    assert_review_summary(selection["review"], "CSPEC_SELECTION", selection["selectionId"])
    for evidence in evidence_items:
        assert_review_summary(evidence["review"], "ATOMIC_EVIDENCE", evidence["evidenceId"])
    for evaluation in evaluations:
        assert_review_summary(
            evaluation["review"], "CRITERION_EVALUATION", evaluation["evaluationId"]
        )
    assert_review_summary(combination["review"], "COMBINATION", combination["combinationId"])

    if bundle["bundleId"] != content_id("bundle-sha256-", bundle_identity_projection(bundle)):
        raise ContractError("bundle stable ID drift")


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def governed_files(repo_root: Path = REPO_ROOT) -> dict[str, Path]:
    files: dict[str, Path] = {}
    case_keys: dict[str, str] = {}
    for root_relative in GOVERNED_ROOTS:
        root = (repo_root / root_relative).resolve()
        if not _is_within(root, repo_root.resolve()):
            raise ContractError(f"governed root escapes repository: {root_relative}")
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                raise ContractError(f"symlink is forbidden in governed roots: {path}")
            if not path.is_file():
                continue
            relative = path.relative_to(repo_root).as_posix()
            if relative == MANIFEST_RELATIVE_PATH:
                continue
            case_key = relative.casefold()
            previous = case_keys.get(case_key)
            if previous and previous != relative:
                raise ContractError(f"case-colliding governed paths: {previous}, {relative}")
            case_keys[case_key] = relative
            files[relative] = path
    return files


def validate_manifest(
    manifest: Mapping[str, Any],
    schemas: ContractSchemas,
    *,
    repo_root: Path = REPO_ROOT,
) -> None:
    schemas.validate(manifest, "urn:varvis:schema:freeze-manifest-v1")
    if tuple(manifest["governedRoots"]) != GOVERNED_ROOTS:
        raise ContractError("manifest governed roots differ from the frozen allowlist")
    if manifest["excludedFromHash"] != [MANIFEST_RELATIVE_PATH]:
        raise ContractError("manifest must exclude only itself")

    artifacts = manifest["artifacts"]
    paths = [artifact["path"] for artifact in artifacts]
    _assert_unique(paths, "manifest artifact paths")
    if MANIFEST_RELATIVE_PATH in paths:
        raise ContractError("freeze manifest must not hash itself")
    expected_files = governed_files(repo_root)
    if set(paths) != set(expected_files):
        missing = sorted(set(expected_files) - set(paths))
        extra = sorted(set(paths) - set(expected_files))
        raise ContractError(f"closed-world manifest mismatch; missing={missing}, extra={extra}")

    for artifact in artifacts:
        path = expected_files[artifact["path"]]
        resolved = path.resolve()
        if not _is_within(resolved, repo_root.resolve()):
            raise ContractError(f"manifest artifact escapes repository: {artifact['path']}")
        if artifact["sha256"] != sha256_file(path):
            raise ContractError(f"manifest hash mismatch: {artifact['path']}")
        if artifact["bytes"] != path.stat().st_size:
            raise ContractError(f"manifest size mismatch: {artifact['path']}")

    serialized = json.dumps(manifest, ensure_ascii=False)
    if WINDOWS_ABSOLUTE_PATH.search(serialized):
        raise ContractError("manifest contains a machine-local absolute path")


def _artifact_role(path: str) -> str:
    name = Path(path).name
    if path.endswith(".schema.json"):
        return "SCHEMA"
    if "/synthetic/" in path:
        return "SYNTHETIC_FIXTURE"
    if name in {"AUTHORITY_SOURCES.json"}:
        return "AUTHORITY_SNAPSHOT"
    if name in {"ACMG_RULE_REGISTRY.json", "CSPEC_OVERLAY_REGISTRY.json"}:
        return "REGISTRY"
    if name == "PROTOCOL.md":
        return "PROTOCOL"
    if name == "DATA_MODEL.md":
        return "DATA_MODEL"
    if "AUDIT" in name or "COMPLETION" in name:
        return "AUDIT_REPORT"
    if name.startswith("test_"):
        return "TEST"
    return "VALIDATOR"


def build_manifest(repo_root: Path = REPO_ROOT) -> dict[str, Any]:
    files = governed_files(repo_root)
    artifacts = []
    for relative, path in sorted(files.items()):
        suffix = path.suffix.lower()
        media_type = {
            ".json": "application/json",
            ".py": "text/x-python",
            ".md": "text/markdown",
        }.get(suffix, "application/octet-stream")
        artifacts.append(
            {
                "path": relative,
                "sha256": sha256_file(path),
                "bytes": path.stat().st_size,
                "mediaType": media_type,
                "role": _artifact_role(relative),
            }
        )
    referencing_version = importlib.metadata.version("referencing")
    return {
        "schemaVersion": "freeze-manifest-v1",
        "manifestId": "acmg-evidence-v2-freeze-2026-08-31",
        "status": "E1_FROZEN_CONTRACT",
        "frozenAt": "2026-08-31T12:00:00Z",
        "closedWorld": True,
        "selfHashPolicy": "MANIFEST_EXCLUDED",
        "governedRoots": list(GOVERNED_ROOTS),
        "excludedFromHash": [MANIFEST_RELATIVE_PATH],
        "tools": [
            {"name": "CPython", "version": platform_python_version()},
            {"name": "jsonschema", "version": importlib.metadata.version("jsonschema")},
            {"name": "referencing", "version": referencing_version},
            {"name": "JSON Schema", "version": "Draft 2020-12"},
        ],
        "artifacts": artifacts,
        "validationClaims": {
            "contractEngineeringValidated": True,
            "syntheticOnly": True,
            "clinicalValidationClaimed": False,
            "resourceValidationClaimed": False,
            "diagnosticPerformanceClaimed": False,
            "runtimeImplementationClaimed": False,
        },
    }


def platform_python_version() -> str:
    return ".".join(str(part) for part in sys.version_info[:3])


def validate_contract(*, require_manifest: bool = True) -> list[str]:
    schemas = ContractSchemas()
    catalog = load_json(AUTHORITY_PATH)
    rule_registry = load_json(RULE_REGISTRY_PATH)
    cspec_registry = load_json(CSPEC_REGISTRY_PATH)
    validate_authority_catalog(catalog, schemas)
    validate_rule_registry(rule_registry, catalog, schemas)
    validate_cspec_registry(cspec_registry, catalog, rule_registry, schemas)

    validated = [
        "schemas",
        AUTHORITY_PATH.relative_to(REPO_ROOT).as_posix(),
        RULE_REGISTRY_PATH.relative_to(REPO_ROOT).as_posix(),
        CSPEC_REGISTRY_PATH.relative_to(REPO_ROOT).as_posix(),
    ]
    for path in sorted(SYNTHETIC_ROOT.glob("*.json")):
        bundle = load_json(path)
        validate_bundle(bundle, catalog, rule_registry, cspec_registry, schemas)
        validated.append(path.relative_to(REPO_ROOT).as_posix())

    if require_manifest:
        if not MANIFEST_PATH.exists():
            raise ContractError("freeze manifest is required")
        validate_manifest(load_json(MANIFEST_PATH), schemas)
        validated.append(MANIFEST_RELATIVE_PATH)
    return validated


def _print_json(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate_parser = subparsers.add_parser("validate", help="validate the frozen contract")
    validate_parser.add_argument(
        "--without-manifest", action="store_true", help="bootstrap validation before freeze"
    )
    rehash_parser = subparsers.add_parser(
        "rehash-bundle", help="print a bundle with recalculated contract-owned IDs"
    )
    rehash_parser.add_argument("path", type=Path)
    subparsers.add_parser("build-manifest", help="print a closed-world manifest candidate")
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            validated = validate_contract(require_manifest=not args.without_manifest)
            _print_json(
                {
                    "status": "PASS",
                    "claim": "CONTRACT_ENGINEERING_ONLY",
                    "clinicalValidation": False,
                    "validated": validated,
                }
            )
        elif args.command == "rehash-bundle":
            _print_json(rehash_bundle(load_json(args.path)))
        elif args.command == "build-manifest":
            _print_json(build_manifest())
        return 0
    except (ContractError, json.JSONDecodeError, OSError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
