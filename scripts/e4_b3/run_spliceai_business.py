"""Bounded business invocation of the retained raw runner; no resource download or VCF rescan."""
import importlib.metadata
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e3_c2r"))
import spliceai_raw_resource_probe as raw


def readiness(reference):
    profile = raw.load_json_object(Path(__file__).resolve().parents[2] /
                                   "docs/execution/E3_C2R_SPLICEAI_RAW_RESOURCE_PROFILE.json")
    if raw.canonical_sha256(profile) != "ae3f4079aac95a96ea60a0c5b7fa039673027acc422ac139c3144bfa0704663d":
        raise raw.ProbeError("SPLICEAI_PROFILE_IDENTITY_MISMATCH")
    versions = {name: importlib.metadata.version(name) for name in ("spliceai", "tensorflow-cpu", "keras")}
    if versions != {"spliceai": "1.3.1", "tensorflow-cpu": "2.15.1", "keras": "2.15.0"}:
        raise raw.ProbeError("SPLICEAI_RUNTIME_VERSION_MISMATCH")
    identity = profile["resource"]["identity"]
    root = raw.package_root()
    # Verify installed assets and CLI sources, without requiring a retained installer or hashing the large FASTA.
    for item in identity["models"] + [identity["geneAnnotation"]] + identity["cliSources"]:
        path = root / item["logicalPath"].removeprefix("spliceai/")
        if path.stat().st_size != item["byteLength"] or raw.sha256_file(path) != item["sha256"]:
            raise raw.ProbeError("SPLICEAI_PACKAGE_ASSET_MISMATCH")
    return {"versions": versions, "installedAssetHashesVerified": True,
            "reference": raw.reference_inventory(reference)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch-request", type=Path)
    parser.add_argument("--reference-fasta", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--readiness-only", action="store_true")
    args = parser.parse_args()
    if args.readiness_only:
        if args.batch_request is not None:
            parser.error("readiness-only must not execute a batch")
        result = readiness(args.reference_fasta)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": "READY", "modelInvocations": 0}))
        sys.exit(0)
    if args.batch_request is None:
        raise raw.ProbeError("BUSINESS_BATCH_REQUIRED")
    request = raw.load_batch_request(args.batch_request)
    try:
        verified = readiness(args.reference_fasta)
        result = raw.run_batch_request(request, args.reference_fasta, args.timeout_seconds)
    except (OSError, ValueError, raw.ProbeError) as error:
        result = {"resultSchemaVersion": raw.BATCH_RESULT_SCHEMA, "requestId": request["requestId"],
                  "status": "FAILED", "reasonCode": "SPLICEAI_RUNTIME_IDENTITY_UNAVAILABLE", "results": []}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"]}))
