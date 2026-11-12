#!/usr/bin/env python3
"""Validate the E3-P1A ACMG rule-registry v2 successor contract."""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path
from typing import Any, Mapping

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Resource

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.evidence import acmg_evidence_v2_contract as e1


SUCCESSOR_ROOT = (
    e1.REPO_ROOT
    / "varvis-server"
    / "src"
    / "main"
    / "resources"
    / "acmg"
    / "rule-registry-v2"
)
SCHEMA_PATH = SUCCESSOR_ROOT / "acmg-rule-registry-v2.schema.json"
REGISTRY_PATH = SUCCESSOR_ROOT / "acmg-rule-registry-e3-p1a-2026-09-02.json"
SCHEMA_ID = "urn:varvis:schema:acmg-rule-registry-v2"
IMPLEMENTED_CODES = ("BA1", "BS1")


def _validate_schema(instance: Mapping[str, Any], schema_path: Path = SCHEMA_PATH) -> None:
    schema = e1.load_json(schema_path)
    Draft202012Validator.check_schema(schema)
    if schema.get("$id") != SCHEMA_ID:
        raise e1.ContractError("successor schema ID mismatch")

    e1_schemas = e1.ContractSchemas()
    registry = e1_schemas.registry.with_resources(
        [(SCHEMA_ID, Resource.from_contents(schema))]
    )
    validator = Draft202012Validator(
        schema,
        registry=registry,
        format_checker=FormatChecker(),
    )
    errors = sorted(validator.iter_errors(instance), key=lambda error: list(error.path))
    if errors:
        details = []
        for error in errors[:12]:
            path = "/".join(str(part) for part in error.absolute_path) or "<root>"
            details.append(f"{path}: {error.message}")
        suffix = "" if len(errors) <= 12 else f" (+{len(errors) - 12} more)"
        raise e1.ContractError(
            f"schema {SCHEMA_ID} failed: {'; '.join(details)}{suffix}"
        )


def validate_successor_registry(
    successor: Mapping[str, Any],
    base_registry: Mapping[str, Any] | None = None,
    *,
    schema_path: Path = SCHEMA_PATH,
) -> None:
    """Validate the v2 schema, canonical lineage, and permitted E3-P1A delta."""

    _validate_schema(successor, schema_path)
    base = base_registry or e1.load_json(e1.RULE_REGISTRY_PATH)
    lineage = successor["lineage"]

    if lineage["baseRegistryId"] != base["registryId"]:
        raise e1.ContractError("successor base registry ID mismatch")
    if lineage["baseSchemaVersion"] != base["schemaVersion"]:
        raise e1.ContractError("successor base schema version mismatch")
    expected_base_hash = e1.canonical_sha256(base)
    if lineage["baseRegistrySha256"] != expected_base_hash:
        raise e1.ContractError("successor base registry canonical SHA-256 mismatch")
    if successor["registryId"] == base["registryId"]:
        raise e1.ContractError("successor registry ID must differ from its base")

    for field in (
        "authorityCatalogId",
        "authorityCatalogSha256",
        "authorityCutoff",
        "asOf",
        "scope",
    ):
        if successor[field] != base[field]:
            raise e1.ContractError(f"successor root field changed from base: {field}")

    base_criteria = base["criteria"]
    successor_criteria = successor["criteria"]
    base_codes = [criterion["code"] for criterion in base_criteria]
    successor_codes = [criterion["code"] for criterion in successor_criteria]
    if successor_codes != base_codes:
        raise e1.ContractError("successor criterion set or order changed from base")

    for base_criterion, successor_criterion in zip(
        base_criteria, successor_criteria, strict=True
    ):
        code = base_criterion["code"]
        expected = copy.deepcopy(base_criterion)
        if code in IMPLEMENTED_CODES:
            expected["implementationStatus"] = "V2_IMPLEMENTED"
        if successor_criterion != expected:
            if code in IMPLEMENTED_CODES:
                raise e1.ContractError(
                    f"{code} changed beyond the permitted implementationStatus transition"
                )
            raise e1.ContractError(f"unauthorized successor criterion change: {code}")


def validate_committed_successor() -> dict[str, Any]:
    successor = e1.load_json(REGISTRY_PATH)
    base = e1.load_json(e1.RULE_REGISTRY_PATH)
    validate_successor_registry(successor, base)
    return {
        "status": "PASS",
        "schemaVersion": successor["schemaVersion"],
        "registryId": successor["registryId"],
        "baseRegistrySha256": successor["lineage"]["baseRegistrySha256"],
        "implementedCriteria": list(IMPLEMENTED_CODES),
    }


def main() -> int:
    try:
        result = validate_committed_successor()
    except (e1.ContractError, OSError, ValueError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
