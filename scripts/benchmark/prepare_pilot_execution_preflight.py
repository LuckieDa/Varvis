#!/usr/bin/env python3
"""Prepare a no-execution public-pilot manifest, dry-run plan and preflight report."""

import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path


SCHEMA_VERSION = "approved-public-pilot-execution-manifest-v1"
PREFLIGHT_VERSION = "approved-public-pilot-preflight-v1"
DRY_RUN_VERSION = "approved-public-pilot-dry-run-v1"
RUN_ID = "stage2b5-approved-public-pilot-v1"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class PreflightError(ValueError):
    pass


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def value_fingerprint(value):
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def term_checksum(values):
    return hashlib.sha256("\n".join(sorted(values)).encode("utf-8")).hexdigest()


def parse_properties(path):
    result = {}
    if not path.is_file():
        return result
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def require(condition, code):
    if not condition:
        raise PreflightError(code)


def write_atomic(path, value):
    encoded = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() == encoded:
            return "REUSED_IDENTICAL"
        raise PreflightError(f"PREFLIGHT_OUTPUT_CONFLICT:{path.name}")
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def configured(properties, key, environment_key=None):
    return bool(properties.get(key)) or bool(environment_key and os.environ.get(environment_key))


def existing_configured_paths(properties, keys):
    values = [properties.get(key, "") for key in keys]
    paths = []
    for value in values:
        for part in re.split(r"[,;]", value):
            if part.strip():
                paths.append(Path(part.strip()))
    return bool(paths) and all(path.exists() for path in paths)


def provider_status(properties):
    statuses = []
    definitions = [
        ("CLINVAR", "variant-analysis.clinvar.enabled", ["variant-analysis.clinvar.grch38-file"], "VARVIS_CLINVAR_GRCH38"),
        ("GNOMAD", "variant-analysis.gnomad.enabled", ["variant-analysis.gnomad.grch38-files", "variant-analysis.gnomad.grch38-compact-files"], "VARVIS_GNOMAD_GRCH38_FILES"),
        ("CADD", "variant-analysis.cadd.enabled", ["variant-analysis.cadd.grch38-snv-file", "variant-analysis.cadd.grch38-indel-file"], "VARVIS_CADD_GRCH38_SNV"),
        ("SPLICEAI", "variant-analysis.splice-ai.enabled", ["variant-analysis.splice-ai.snv-resource-path", "variant-analysis.splice-ai.indel-resource-path"], "VARVIS_SPLICEAI_SNV_RESOURCE"),
        ("VEP", "variant-analysis.vep.execution-enabled", ["variant-analysis.vep.executable", "variant-analysis.vep.cache-dir", "variant-analysis.vep.fasta"], "VARVIS_VEP_EXECUTABLE"),
        ("PHENOTYPE_PRIOR", "variant-analysis.phenotype-prior.enabled", ["variant-analysis.phenotype-prior.local-file", "variant-analysis.phenotype-prior.manifest-file"], "VARVIS_PHENOTYPE_PRIOR_LOCAL_FILE"),
    ]
    for name, enabled_key, file_keys, environment_key in definitions:
        enabled = properties.get(enabled_key, "").lower() == "true"
        environment_present = bool(os.environ.get(environment_key))
        paths_present = existing_configured_paths(properties, file_keys)
        status = "CONFIGURED_LOCAL" if enabled and (paths_present or environment_present) else (
            "DISABLED" if not enabled else "CONFIGURATION_INCOMPLETE")
        statuses.append({
            "provider": name,
            "enabled": enabled,
            "resourceConfigured": paths_present or environment_present,
            "credentialRequired": False,
            "credentialPresent": None,
            "status": status,
        })
    return statuses


def resource(root, logical, expected_sha256=None, directory=False):
    path = root / Path(logical)
    present = path.is_dir() if directory else path.is_file()
    result = {"logicalPath": logical.replace("\\", "/"), "present": present,
              "kind": "DIRECTORY" if directory else "FILE"}
    if present and not directory:
        actual = file_sha256(path)
        result["sha256"] = actual
        result["checksumStatus"] = "MATCH" if expected_sha256 is None or actual == expected_sha256 else "MISMATCH"
    else:
        result["checksumStatus"] = "NOT_APPLICABLE" if directory else "MISSING"
    return result


def prepare(repository, tool_root, source_commit):
    dataset_root = repository / "docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1"
    approved_path = dataset_root / "approved-dataset-manifest.json"
    approval_path = dataset_root / "dataset-approval-validation.json"
    g2_path = dataset_root / "truth-masked-hpo-review-v1/approved-g2/pilot-g2-input-manifest.json"
    g2_validation_path = dataset_root / "truth-masked-hpo-review-v1/approved-g2/pilot-g2-validation.json"
    approved = read_json(approved_path)
    approval = read_json(approval_path)
    g2 = read_json(g2_path)
    g2_validation = read_json(g2_validation_path)
    require(approved["status"] == "APPROVED_FOR_G2_BUILD", "PREFLIGHT_DATASET_NOT_APPROVED")
    require(approval["status"] == "PASSED", "PREFLIGHT_APPROVAL_INVALID")
    require(g2["status"] == "READY_IDENTICAL_TO_G1" and g2_validation["status"] == "PASSED",
            "PREFLIGHT_G2_NOT_READY")
    require(g2["approvedDatasetFingerprint"] == approval["datasetFingerprint"],
            "PREFLIGHT_DATASET_G2_FINGERPRINT_MISMATCH")

    background_manifest_path = tool_root / "benchmark/1000_genomes/grch38_20190312/merged/na12878_exome_v1/merged-resource-manifest.json"
    hpo_manifest_path = tool_root / "benchmark/ontology/hpo_2026_06_23/resource-manifest.json"
    background_manifest = read_json(background_manifest_path) if background_manifest_path.is_file() else {}
    hpo_manifest = read_json(hpo_manifest_path) if hpo_manifest_path.is_file() else {}
    resources = [
        resource(tool_root, "benchmark/1000_genomes/grch38_20190312/merged/na12878_exome_v1/merged-resource-manifest.json"),
        resource(tool_root, "benchmark/1000_genomes/grch38_20190312/merged/na12878_exome_v1/NA12878.GRCh38.exome-like.chr1-22-X.vcf.gz", background_manifest.get("derived", {}).get("sha256")),
        resource(tool_root, "benchmark/1000_genomes/grch38_20190312/merged/na12878_exome_v1/NA12878.GRCh38.exome-like.chr1-22-X.vcf.gz.tbi", background_manifest.get("derived", {}).get("indexSha256")),
        resource(tool_root, "benchmark/ontology/hpo_2026_06_23/resource-manifest.json"),
        resource(tool_root, "benchmark/ontology/hpo_2026_06_23/hp.obo", hpo_manifest.get("sha256")),
        resource(tool_root, "benchmark/phenopacket_store/0.1.27/extracted/0.1.27", directory=True),
        resource(tool_root, "benchmark/pheval/0.7.14/venv/Scripts/pheval-utils.exe"),
        resource(tool_root, "msys64/ucrt64/bin/bcftools.exe"),
        resource(tool_root, "msys64/ucrt64/bin/tabix.exe"),
    ]
    require(all(item["present"] and item["checksumStatus"] != "MISMATCH" for item in resources),
            "PREFLIGHT_RESOURCE_INVALID")
    require(background_manifest.get("status") == "COMPLETED"
            and background_manifest.get("assembly") == "GRCh38"
            and background_manifest.get("sampleId") == "NA12878", "PREFLIGHT_BACKGROUND_INVALID")
    require(hpo_manifest.get("releaseVersion") == "2026-06-23"
            and hpo_manifest.get("validationStatus") == "VALID", "PREFLIGHT_HPO_INVALID")

    properties_path = repository / "varvis-server/config/variant-analysis-local.properties"
    database_path = repository / "varvis-server/config/database-local.properties"
    properties = parse_properties(properties_path)
    database = parse_properties(database_path)
    providers = provider_status(properties)
    database_ready = all(bool(database.get(key)) for key in (
        "varvis.datasource.jdbc-url", "varvis.datasource.username", "varvis.datasource.password"))
    reference_ready = configured(properties, "variant-analysis.normalization.grch38-reference")
    environment = {
        "analysisConfigurationPresent": properties_path.is_file(),
        "databaseConfigurationPresent": database_path.is_file(),
        "databaseRequiredFieldsPresent": database_ready,
        "dedicatedDatabaseCatalog": "varvis_benchmark_stage2b5_pilot",
        "databaseCreationDeferredUntilAuthorizedExecution": True,
        "grch38ReferenceConfigured": reference_ready,
        "secretsPrinted": False,
    }
    require(environment["analysisConfigurationPresent"] and database_ready and reference_ready,
            "PREFLIGHT_LOCAL_CONFIGURATION_INCOMPLETE")

    by_case = {entry["caseId"]: entry for entry in g2["cases"]}
    cases = []
    groups = []
    for order, dataset_case in enumerate(approved["cases"], 1):
        case_id = dataset_case["caseId"]
        g2_case = by_case.get(case_id)
        require(g2_case is not None and g2_case["selectionOrder"] == order, "PREFLIGHT_G2_CASE_ORDER_MISMATCH")
        observed = g2_case["observedHpoTerms"]
        base = {
            "selectionOrder": order,
            "role": "MAIN" if order <= 25 else "BACKUP",
            "caseId": case_id,
            "truthCanonicalIdentities": g2_case["truthCanonicalIdentities"],
            "sourceRecordId": dataset_case["sourceRecordId"],
        }
        cases.append(base)
        for group_id, mode, terms in (("G0", "NONE", []), ("G1", "SOURCE_PROVIDED", observed),
                                      ("G2", "HUMAN_REVIEWED", observed)):
            groups.append({
                "caseId": case_id, "groupId": group_id, "hpoInputMode": mode,
                "hpoTerms": terms, "hpoInputChecksum": term_checksum(terms),
                "nonHpoConfigurationFingerprint": approval["datasetFingerprint"],
                "state": "DRY_RUN_READY", "executionMode": "DRY_RUN_NOT_EXECUTED",
            })
    require(len(groups) == 90, "PREFLIGHT_GROUP_COUNT_INVALID")
    for case_id in by_case:
        g1 = next(item for item in groups if item["caseId"] == case_id and item["groupId"] == "G1")
        g2_item = next(item for item in groups if item["caseId"] == case_id and item["groupId"] == "G2")
        require(g1["hpoInputChecksum"] == g2_item["hpoInputChecksum"], "PREFLIGHT_G1_G2_MISMATCH")

    config = {
        "assembly": "GRCh38", "analysisMode": "WES",
        "normalizationProfile": "grch38-primary-chr", "hpoOntologyVersion": "2026-06-23",
        "rankingContractVersion": "ranking-contract-v1", "candidateHardCap": 20000,
        "vepCap": 8000, "backgroundFingerprint": background_manifest.get("derived", {}).get("sha256"),
        "featureFlags": {"compactClinvar": False, "preRankingVep": True,
                         "phenotypePrior": True, "trioInputMode": "DISABLED"},
    }
    config["configurationFingerprint"] = value_fingerprint(config)
    replacement = approved["approval"]["replacementPolicy"]
    command = [
        "powershell", "-File", "scripts/benchmark/Invoke-ApprovedPublicPilot.ps1",
        "-RepositoryRoot", "<repository_root>", "-ToolRoot", "<tool_root>",
        "-ApplicationJar", "<approved_application_jar>",
        "-ApplicationSourceCommit", "<approved_application_commit>",
        "-BenchmarkDatabaseCatalog", "varvis_benchmark_stage2b5_pilot",
        "-RunId", RUN_ID, "-Execute",
    ]
    execution = {
        "schemaVersion": SCHEMA_VERSION,
        "runId": RUN_ID,
        "status": "PREFLIGHT_READY_PENDING_EXECUTION_AUTHORIZATION",
        "executionStatus": "NOT_STARTED",
        "datasetId": approved["datasetId"], "datasetVersion": approved["datasetVersion"],
        "approvedDatasetFingerprint": approval["datasetFingerprint"],
        "approvedDatasetManifestSha256": file_sha256(approved_path),
        "g2ManifestFingerprint": g2["manifestFingerprint"], "g2ManifestSha256": file_sha256(g2_path),
        "orchestrationSourceCommit": source_commit,
        "applicationArtifact": {"status": "BUILD_REQUIRED_BEFORE_EXECUTION", "sha256": None},
        "configuration": config, "resources": resources, "providers": providers,
        "environment": environment, "cases": cases, "groups": groups,
        "replacementPolicy": replacement, "resumePolicy": {
            "completedResultReuse": "EXACT_FINGERPRINT_ONLY",
            "failedAttemptPreserved": True, "maximumInfrastructureRetryPerGroup": 1,
            "nonRankedOutcomeIsRetryable": False,
        },
        "terminalStates": ["COMPLETED", "COMPLETED_WITH_FAILURES", "FAILED", "CANCELLED"],
        "commandTokenPlan": command,
        "authorizationRequired": "EXPLICIT_USER_AUTHORIZATION_FOR_REAL_PILOT_EXECUTION",
    }
    execution["manifestFingerprint"] = value_fingerprint(execution)
    dry_run = {
        "schemaVersion": DRY_RUN_VERSION, "status": "PASSED", "dryRun": True,
        "runId": RUN_ID, "caseCount": 30, "groupCount": 90,
        "mainCount": 25, "backupCount": 5,
        "readyGroupCount": 90, "executedGroupCount": 0,
        "providerCallCount": 0, "databaseMutationCount": 0, "externalCost": 0,
        "g1G2EquivalentCaseCount": 30,
        "resourcePendingCount": 0,
        "replacementSimulation": "NOT_PERFORMED_NO_INELIGIBLE_MAIN",
        "manifestFingerprint": execution["manifestFingerprint"],
    }
    incomplete_providers = [item["provider"] for item in providers if item["status"] == "CONFIGURATION_INCOMPLETE"]
    preflight = {
        "schemaVersion": PREFLIGHT_VERSION,
        "status": "READY_PENDING_EXECUTION_AUTHORIZATION" if not incomplete_providers else "READY_WITH_PROVIDER_CONFIGURATION_GAPS",
        "executionStatus": "NOT_STARTED", "resourceCount": len(resources),
        "resourceReadyCount": sum(item["present"] and item["checksumStatus"] != "MISMATCH" for item in resources),
        "providerConfigurationGaps": incomplete_providers,
        "databaseIsolation": "PLANNED_DEDICATED_CATALOG_NOT_CREATED",
        "applicationArtifact": "BUILD_REQUIRED_BEFORE_EXECUTION",
        "estimatedSerialAnalysisCount": 90,
        "estimatedWallClockRangeHours": [15, 45],
        "estimatedAdditionalStorageGiB": [5, 15],
        "estimatedNetwork": "NO_LARGE_RESOURCE_DOWNLOAD; LOCAL_PROVIDER_RESOURCES_EXPECTED",
        "estimatedExternalFees": "ZERO_WHILE_PAID_EXTERNAL_APIS_REMAIN_DISABLED",
        "remainingAuthorization": "EXPLICIT_USER_AUTHORIZATION_FOR_REAL_PILOT_EXECUTION",
        "dryRunStatus": "PASSED",
    }
    return execution, dry_run, preflight


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--tool-root", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"[0-9a-f]{40}", args.source_commit.lower()) is not None,
            "PREFLIGHT_SOURCE_COMMIT_INVALID")
    execution, dry_run, preflight = prepare(args.repository_root.resolve(), args.tool_root.resolve(),
                                            args.source_commit.lower())
    output = args.repository_root.resolve() / "docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1/execution-preflight"
    write_atomic(output / "pilot-execution-manifest.json", execution)
    write_atomic(output / "pilot-dry-run-report.json", dry_run)
    write_atomic(output / "pilot-preflight-report.json", preflight)
    print(json.dumps({"status": preflight["status"], "groups": dry_run["groupCount"],
                      "providersWithGaps": preflight["providerConfigurationGaps"]}, sort_keys=True))


if __name__ == "__main__":
    main()
