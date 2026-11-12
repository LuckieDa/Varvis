"""Native Windows CPU feasibility adapter; not an adopted business runtime.

Uses unchanged SpliceAI 1.3.1 Annotator/get_delta_scores, with validated JSON
records instead of the official pysam VCF CLI. No transcript identity is inferred
from a returned gene symbol. A caller must enforce the process timeout.
"""
from __future__ import annotations

import argparse
import ctypes
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import re
import sys
import tempfile
import time
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e3_c2r"))
import spliceai_raw_resource_probe as raw

ADAPTER_VERSION = "1.0.3-probe"
RESULT_SCHEMA = "spliceai-native-feasibility-result-v1"
MAX_VARIANTS = 10
MAX_ALLELE_LENGTH = 4096
# The profile is a source of fixed asset pins, not the new runtime's identity.
EXPECTED_PROFILE_SHA = "8efd4d5f34e238bab94abaaec99f7ea394729a8bf79083e618814ff837536edc"
CORE_PACKAGES = {
    "spliceai": "1.3.1", "tensorflow-cpu": "2.15.1",
    "tensorflow-intel": "2.15.1", "tensorflow-io-gcs-filesystem": "0.31.0",
    "keras": "2.15.0", "numpy": "1.26.4", "h5py": "3.10.0",
    "pandas": "2.2.3", "pyfaidx": "0.8.1.4", "setuptools": "80.9.0",
}
CPU_ENV = {
    "CUDA_VISIBLE_DEVICES": "-1", "TF_ENABLE_ONEDNN_OPTS": "0",
    "TF_NUM_INTRAOP_THREADS": "4", "TF_NUM_INTEROP_THREADS": "1",
    "OMP_NUM_THREADS": "4", "TF_CPP_MIN_LOG_LEVEL": "2",
}
KERAS_CONFIG = {"floatx": "float32", "epsilon": 1e-7,
                "backend": "tensorflow", "image_data_format": "channels_last"}


def keras_config_path() -> Path:
    return Path(sys.prefix).parent / "keras" / "keras.json"


def verify_keras_config() -> str:
    if raw.load_json_object(keras_config_path()) != KERAS_CONFIG:
        raise raw.ProbeError("NATIVE_KERAS_CONFIGURATION_MISMATCH")
    return raw.sha256_file(keras_config_path())


def load_request(path: Path) -> dict:
    if path.stat().st_size > 1024 * 1024:
        raise raw.ProbeError("NATIVE_REQUEST_TOO_LARGE")
    request = raw.load_batch_request(path)
    if len(request["variants"]) > MAX_VARIANTS:
        raise raw.ProbeError("NATIVE_BATCH_LIMIT_EXCEEDED")
    for variant in request["variants"]:
        allele = variant["allele"]
        if not re.fullmatch(r"(?:chr)?(?:[1-9]|1[0-9]|2[0-2]|X|Y|M|MT)", allele["contig"]):
            raise raw.ProbeError("NATIVE_CONTIG_UNSUPPORTED")
        for name in ("reference", "alternate"):
            if not re.fullmatch(r"[ACGT]+", allele[name]) or len(allele[name]) > MAX_ALLELE_LENGTH:
                raise raw.ProbeError("NATIVE_SEQUENCE_ALLELE_REQUIRED")
        if allele["reference"] == allele["alternate"]:
            raise raw.ProbeError("NATIVE_IDENTICAL_ALLELES")
    return request


def runtime_identity(reference: Path) -> dict:
    if platform.system() != "Windows" or platform.machine().upper() not in ("AMD64", "X86_64"):
        raise raw.ProbeError("NATIVE_WINDOWS_X64_REQUIRED")
    if sys.version_info[:2] != (3, 11):
        raise raw.ProbeError("NATIVE_PYTHON_VERSION_MISMATCH")
    versions = {key: importlib.metadata.version(key) for key in CORE_PACKAGES}
    if versions != CORE_PACKAGES:
        raise raw.ProbeError("NATIVE_PACKAGE_VERSION_MISMATCH")
    keras_config_sha = verify_keras_config()
    profile_path = Path(__file__).resolve().parents[2] / "docs/execution/E3_C2R_SPLICEAI_RAW_RESOURCE_PROFILE.json"
    if raw.sha256_file(profile_path) != EXPECTED_PROFILE_SHA:
        raise raw.ProbeError("NATIVE_ASSET_PROFILE_MISMATCH")
    profile = raw.load_json_object(profile_path)
    expected = profile["resource"]["identity"]
    root = raw.package_root()
    verified = []
    for item in expected["models"] + [expected["geneAnnotation"]] + expected["cliSources"]:
        path = root / item["logicalPath"].removeprefix("spliceai/")
        if path.stat().st_size != item["byteLength"] or raw.sha256_file(path) != item["sha256"]:
            raise raw.ProbeError("NATIVE_PACKAGE_ASSET_MISMATCH")
        verified.append(item)
    # pkg_resources adds setuptools/_vendor to sys.path during import. Enumerate
    # the installed venv itself, so those bundled libraries do not masquerade as
    # packages installed mid-inference or shadow the installed lock inventory.
    packages = sorted((dist.metadata["Name"].lower(), dist.version)
                      for dist in importlib.metadata.distributions(path=[str(Path(sys.prefix) / "Lib/site-packages")]))
    reference_stat = reference.stat()
    identity = {
        "runtimeKind": "WINDOWS_CPU_OFFICIAL_FUNCTION_ADAPTER_PROBE_ONLY",
        "adapterVersion": ADAPTER_VERSION, "adapterSha256": raw.sha256_file(Path(__file__)),
        "helperSha256": raw.sha256_file(Path(raw.__file__)),
        "pythonVersion": platform.python_version(), "platform": platform.platform(),
        "packages": packages, "packagesSha256": raw.canonical_sha256(packages),
        "verifiedAssets": verified, "cpuEnvironment": CPU_ENV,
        "kerasConfiguration": KERAS_CONFIG,
        "kerasConfigurationSha256": keras_config_sha,
        "reference": raw.reference_inventory(reference),
        "referenceMtimeNs": reference_stat.st_mtime_ns,
        "legacyRuntimeIdentityReused": False, "pysamCliUsed": False,
        "transcriptAssociationValidated": False, "distanceBp": 4999, "mask": 0,
    }
    identity["identitySha256"] = raw.canonical_sha256(identity)
    return identity


def peak_working_set_bytes() -> int:
    from ctypes import wintypes

    class MemoryCounters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in (
                "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
                "PagefileUsage", "PeakPagefileUsage")]

    counters = MemoryCounters()
    counters.cb = ctypes.sizeof(counters)
    get_current = ctypes.windll.kernel32.GetCurrentProcess
    get_current.restype = wintypes.HANDLE
    get_memory = ctypes.windll.psapi.GetProcessMemoryInfo
    get_memory.argtypes = [wintypes.HANDLE, ctypes.POINTER(MemoryCounters), wintypes.DWORD]
    if not get_memory(get_current(), ctypes.byref(counters), counters.cb):
        raise OSError("PROCESS_MEMORY_MEASUREMENT_FAILED")
    return counters.PeakWorkingSetSize


def evaluate_record(variant: dict, annotator, official) -> dict:
    allele = variant["allele"]
    record = SimpleNamespace(chrom=allele["contig"], pos=allele["position"],
                             ref=allele["reference"], alts=(allele["alternate"],))
    result = {"queryId": variant["queryId"], "allele": dict(allele),
              "status": "UNAVAILABLE", "exactAlleleMatch": False, "annotations": []}
    chrom = official.normalise_chrom(record.chrom, list(annotator.ref_fasta.keys())[0])
    try:
        sequence = annotator.ref_fasta[chrom]
        if record.pos + len(record.ref) - 1 > len(sequence):
            result["reasonCode"] = "POSITION_OUTSIDE_REFERENCE"
            return result
        if sequence[record.pos - 1:record.pos - 1 + len(record.ref)].seq.upper() != record.ref:
            result["reasonCode"] = "REFERENCE_ALLELE_MISMATCH"
            return result
    except KeyError:
        result["reasonCode"] = "REFERENCE_CONTIG_MISSING"
        return result
    if len(record.ref) > 1 and len(record.alts[0]) > 1:
        result["reasonCode"] = "OFFICIAL_COMPLEX_ALLELE_UNSUPPORTED"
        return result
    _, _, indices = annotator.get_name_and_strand(record.chrom, record.pos)
    if len(indices) == 0:
        # Confirm the official function's empty result, without a model prediction.
        if official.get_delta_scores(record, annotator, 4999, 0):
            raise raw.ProbeError("OFFICIAL_EMPTY_ANNOTATION_INCONSISTENT")
        result.update(status="NO_RECORD", reasonCode="NO_MATCHING_GENE_ANNOTATION")
        return result
    window = 10000 + 2 * 4999 + 1
    if record.pos - window // 2 - 1 < 0 or record.pos + window // 2 > len(sequence):
        result["reasonCode"] = "REFERENCE_WINDOW_INCOMPLETE"
        return result
    scores = official.get_delta_scores(record, annotator, 4999, 0)
    if not scores:
        result["reasonCode"] = "OFFICIAL_FUNCTION_RETURNED_NO_SCORE"
        return result
    annotations = [raw.parse_annotation(value) for value in scores]
    for annotation in annotations:
        if annotation["allele"] != allele["alternate"]:
            raise raw.ProbeError("NATIVE_OUTPUT_ALLELE_MISMATCH")
        for value in annotation["deltaScores"].values():
            number = raw.Decimal(value)
            if not number.is_finite() or not 0 <= number <= 1:
                raise raw.ProbeError("NATIVE_OUTPUT_SCORE_INVALID")
    result.update(status="HIT", exactAlleleMatch=True, annotations=annotations)
    return result


def execute(request: dict, reference: Path, record_evaluator=None, annotation_file=None) -> dict:
    started = time.perf_counter()
    identity = runtime_identity(reference)
    before = (reference.stat().st_size, reference.stat().st_mtime_ns,
              raw.sha256_file(Path(str(reference) + ".fai")))
    os.environ.update(CPU_ENV)
    os.environ["KERAS_HOME"] = str(keras_config_path().parent)
    print(json.dumps({"nativeStage": "IMPORTING_RUNTIME"}), flush=True)
    import_started = time.perf_counter()
    from spliceai import utils as official
    import_seconds = time.perf_counter() - import_started
    counters = {"modelLoads": 0, "modelPredictCalls": 0}
    original_loader = official.load_model

    def counted_loader(*args, **kwargs):
        model = original_loader(*args, **kwargs)
        counters["modelLoads"] += 1
        original_predict = model.predict

        def counted_predict(*inputs, **options):
            counters["modelPredictCalls"] += 1
            return original_predict(*inputs, **options)

        model.predict = counted_predict
        return model

    model_started = time.perf_counter()
    official.load_model = counted_loader
    try:
        annotator = official.Annotator(str(reference), str(annotation_file) if annotation_file is not None else "grch38")
    finally:
        official.load_model = original_loader
    model_seconds = time.perf_counter() - model_started
    print(json.dumps({"nativeStage": "MODELS_READY", "modelLoads": counters["modelLoads"]}), flush=True)
    results = []
    timings = []
    try:
        for variant in request["variants"]:
            variant_started = time.perf_counter()
            calls_before = counters["modelPredictCalls"]
            results.append((record_evaluator or evaluate_record)(variant, annotator, official))
            timings.append({"queryId": variant["queryId"],
                            "seconds": time.perf_counter() - variant_started,
                            "predictCalls": counters["modelPredictCalls"] - calls_before})
    finally:
        annotator.ref_fasta.close()
    after = (reference.stat().st_size, reference.stat().st_mtime_ns,
             raw.sha256_file(Path(str(reference) + ".fai")))
    if after != before:
        raise raw.ProbeError("REFERENCE_CHANGED_DURING_PROBE")
    if verify_keras_config() != identity["kerasConfigurationSha256"]:
        raise raw.ProbeError("KERAS_CONFIGURATION_CHANGED_DURING_PROBE")
    if counters["modelLoads"] != 5:
        raise raw.ProbeError("NATIVE_MODEL_LOAD_COUNT_INVALID")
    return {"resultSchemaVersion": RESULT_SCHEMA, "requestId": request["requestId"],
            "status": "SUCCEEDED", "scope": "FEASIBILITY_ONLY_NOT_BUSINESS_ADOPTED",
            "identity": identity, "results": results,
            "metrics": {"annotatorInitializations": 1, **counters,
                        "importSeconds": import_seconds, "modelLoadSeconds": model_seconds,
                        "variants": timings, "wallSeconds": time.perf_counter() - started,
                        "peakWorkingSetBytes": peak_working_set_bytes()}}


def write_new_json(path: Path, value: dict) -> None:
    if path.exists():
        raise FileExistsError("Output exists; original evidence will not be overwritten")
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     prefix="native-splice-", suffix=".tmp", delete=False) as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, allow_nan=False)
        handle.write("\n")
        temporary = Path(handle.name)
    try:
        # On Windows rename fails if the destination exists; do not replace evidence.
        temporary.rename(path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch-request", type=Path)
    parser.add_argument("--reference-fasta", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--readiness-only", action="store_true")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists")
    if args.readiness_only:
        result = {"status": "ASSETS_VERIFIED_NOT_INFERENCE_VALIDATED",
                  "identity": runtime_identity(args.reference_fasta)}
    else:
        if args.batch_request is None:
            parser.error("--batch-request is required")
        try:
            request = load_request(args.batch_request)
            result = execute(request, args.reference_fasta)
        except (Exception, SystemExit) as failure:
            result = {"resultSchemaVersion": RESULT_SCHEMA, "status": "FAILED",
                      "reasonCode": str(failure) if isinstance(failure, raw.ProbeError)
                      else "NATIVE_PROBE_FAILED", "exceptionType": type(failure).__name__,
                      "results": []}
            write_new_json(args.output, result)
            raise
    write_new_json(args.output, result)
    print(json.dumps({"status": result["status"], "adapterVersion": ADAPTER_VERSION}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
