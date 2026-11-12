#!/usr/bin/env python3
"""Bind a locally built pilot application JAR without recording its host path."""

import argparse
import hashlib
import json
import os
import re
import tempfile
import zipfile
from pathlib import Path


SCHEMA_VERSION = "public-pilot-application-artifact-v1"
REQUIRED_CLASS = "BOOT-INF/classes/org/nbscn/varvis/variantanalysis/benchmark/execution/BenchmarkRealCaseExecutor.class"


class ArtifactError(ValueError):
    pass


def require(condition, code):
    if not condition:
        raise ArtifactError(code)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect(path, source_commit, logical_id):
    path = Path(path)
    require(path.is_file() and path.stat().st_size > 0, "APPLICATION_JAR_MISSING")
    require(re.fullmatch(r"[0-9a-f]{40}", source_commit or "") is not None,
            "APPLICATION_SOURCE_COMMIT_INVALID")
    require(logical_id and not re.match(r"^[A-Za-z]:|^/|^\\", logical_id)
            and ".." not in logical_id.replace("\\", "/").split("/"), "LOGICAL_ARTIFACT_ID_INVALID")
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())
    except zipfile.BadZipFile as error:
        raise ArtifactError("APPLICATION_JAR_INVALID") from error
    require(REQUIRED_CLASS in names, "APPLICATION_EXECUTION_ADAPTER_MISSING")
    forbidden = [name for name in names if name.lower().endswith(("database-local.properties", ".env", "application-local.yml"))]
    require(not forbidden, "APPLICATION_LOCAL_CONFIGURATION_EMBEDDED")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "status": "VALID",
        "logicalArtifactId": logical_id.replace("\\", "/"),
        "fileName": path.name,
        "applicationSourceCommit": source_commit,
        "sha256": sha256(path),
        "sizeBytes": path.stat().st_size,
        "requiredExecutionClass": REQUIRED_CLASS,
        "localConfigurationEmbedded": False,
    }


def write_atomic(path, value, replace_mismatched_provenance=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(value, indent=2) + "\n").encode("utf-8")
    if path.exists():
        if path.read_bytes() == encoded:
            return
        existing = json.loads(path.read_text(encoding="utf-8"))
        can_replace = (replace_mismatched_provenance
                       and existing.get("status") == "VALID"
                       and existing.get("logicalArtifactId") == value["logicalArtifactId"]
                       and existing.get("sha256") == value["sha256"]
                       and existing.get("sizeBytes") == value["sizeBytes"]
                       and existing.get("applicationSourceCommit") != value["applicationSourceCommit"])
        if not can_replace:
            raise ArtifactError("APPLICATION_ARTIFACT_OUTPUT_CONFLICT")
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(encoded)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--logical-artifact-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--replace-mismatched-provenance", action="store_true")
    args = parser.parse_args()
    value = inspect(args.jar, args.source_commit, args.logical_artifact_id)
    write_atomic(args.output, value, args.replace_mismatched_provenance)
    print(json.dumps({key: value[key] for key in ("status", "logicalArtifactId", "sha256", "sizeBytes")}, sort_keys=True))


if __name__ == "__main__":
    main()
