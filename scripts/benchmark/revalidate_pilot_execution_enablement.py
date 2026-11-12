#!/usr/bin/env python3
"""Read-only revalidation for the immutable, not-yet-authorized pilot package."""

import argparse
import json
from pathlib import Path

import build_approved_pilot_execution_package as builder


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--artifact-manifest", type=Path, required=True)
    parser.add_argument("--catalog-manifest", type=Path, required=True)
    parser.add_argument("--configuration-fingerprint", required=True)
    parser.add_argument("--background-sha256", required=True)
    parser.add_argument("--output-relative", default="execution-package-v2")
    args = parser.parse_args()
    root, expected, cases, requests = builder.build(
        args.repository_root, read(args.artifact_manifest), read(args.catalog_manifest),
        args.configuration_fingerprint, args.background_sha256)
    builder.safe_logical(args.output_relative, "outputRelative")
    package_root = root / args.output_relative
    actual = read(package_root / "execution-package.json")
    if actual != expected:
        raise builder.PackageError("EXECUTION_PACKAGE_STALE_OR_CONFLICTING")
    if len(list((package_root / "cases").glob("*.json"))) != 30:
        raise builder.PackageError("EXECUTION_CASE_MANIFEST_COUNT_INVALID")
    if len(list((package_root / "requests").glob("*.json"))) != 90:
        raise builder.PackageError("EXECUTION_REQUEST_MANIFEST_COUNT_INVALID")
    for request in requests:
        if read(package_root / "requests" / (request["requestId"] + ".json")) != request:
            raise builder.PackageError("EXECUTION_REQUEST_CONTENT_MISMATCH:" + request["requestId"])
    report = {
        "schemaVersion": "approved-public-pilot-execution-enable-preflight-v1",
        "status": "READY_PENDING_EXECUTION_AUTHORIZATION",
        "executionStatus": "NOT_STARTED",
        "packageFingerprint": actual["packageFingerprint"],
        "caseCount": 30, "groupCount": 90,
        "catalogStatus": actual["catalog"]["status"],
        "applicationArtifactStatus": "VALID",
        "providerCallCount": 0,
        "analysisResultDatabaseWriteCount": 0,
        "authorizationGate": "PENDING_EXPLICIT_USER_AUTHORIZATION",
    }
    builder.write_atomic(package_root / "execution-enable-preflight.json", report)
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
