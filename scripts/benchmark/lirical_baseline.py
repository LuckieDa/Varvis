#!/usr/bin/env python3
"""Truth-free preparation and preflight helpers for the Stage 3A LIRICAL baseline.

The module deliberately has no truth input option. Evaluation is a separate,
post-lock step and must not share this execution process.
"""

import argparse
import csv
import gzip
import hashlib
import json
import os
import re
import stat
import subprocess
import tempfile
import time
from pathlib import Path
from statistics import median


SHA256 = re.compile(r"^[0-9a-f]{64}$")
HPO = re.compile(r"^HP:\d{7}$")
FORBIDDEN = ("truth", "truthmatches", "prediction", "rescue", "benchmark_rank")


class LiricalBaselineError(ValueError):
    pass


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_atomic(path, value):
    path = Path(path)
    encoded = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() == encoded:
            return "REUSED_IDENTICAL"
        raise LiricalBaselineError("OUTPUT_CONFLICT:" + path.name)
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
    return "CREATED"


def require(condition, code):
    if not condition:
        raise LiricalBaselineError(code)


def safe_request(entry):
    required = ("requestId", "caseId", "groupId", "selectionOrder", "vcfSha256", "tbiSha256",
                "vcfSampleId", "hpoTerms", "phenopacketSha256")
    require(all(key in entry for key in required), "MAPPING_FIELD_MISSING")
    require(entry["groupId"] in ("G0", "G1"), "GROUP_INVALID")
    require(SHA256.fullmatch(entry["vcfSha256"]) is not None, "VCF_SHA_INVALID")
    require(SHA256.fullmatch(entry["tbiSha256"]) is not None, "TBI_SHA_INVALID")
    require(SHA256.fullmatch(entry["phenopacketSha256"]) is not None, "PHENOPACKET_SHA_INVALID")
    terms = entry["hpoTerms"]
    require(isinstance(terms, list) and all(HPO.fullmatch(term) for term in terms), "HPO_INVALID")
    require(not terms if entry["groupId"] == "G0" else bool(terms), "G0_G1_HPO_CONTRACT_INVALID")
    result = {key: entry[key] for key in required}
    result["assembly"] = "GRCh38"
    result["truthFree"] = True
    return result


def freeze_matrix(mapping):
    requests = [safe_request(entry) for entry in mapping.get("requests", [])]
    require(len(requests) == 50, "REQUEST_COUNT_NOT_50")
    require(len({entry["requestId"] for entry in requests}) == 50, "REQUEST_ID_DUPLICATE")
    by_case = {}
    for entry in requests:
        by_case.setdefault(entry["caseId"], []).append(entry)
    require(len(by_case) == 25 and all({item["groupId"] for item in entries} == {"G0", "G1"}
                                        for entries in by_case.values()), "CASE_PAIRING_INVALID")
    for entries in by_case.values():
        require(len({item["selectionOrder"] for item in entries}) == 1, "PAIR_ORDER_INVALID")
    requests.sort(key=lambda item: (item["selectionOrder"], item["groupId"]))
    return {
        "schemaVersion": "stage3a-lirical-request-matrix-v1",
        "status": "FROZEN_TRUTH_FREE_EXECUTION_INPUT",
        "source": "stage3a-local-runtime-input-mapping-v1",
        "caseCount": 25,
        "requestCount": 50,
        "assembly": "GRCh38",
        "requests": requests,
    }


def freeze_g1_only_matrix(matrix):
    require(matrix.get("schemaVersion") == "stage3a-lirical-request-matrix-v1", "MATRIX_SCHEMA_INVALID")
    requests = [entry for entry in matrix.get("requests", []) if entry.get("groupId") == "G1"]
    require(len(requests) == 25, "G1_REQUEST_COUNT_NOT_25")
    require(all(entry.get("hpoTerms") for entry in requests), "G1_HPO_EMPTY")
    require(len({entry["caseId"] for entry in requests}) == 25, "G1_CASE_COVERAGE_INVALID")
    return {"schemaVersion": "stage3a-lirical-g1-only-request-matrix-v1",
            "status": "FROZEN_G1_ONLY_NON_EQUIVALENT_EXECUTION_INPUT",
            "sourceMatrix": "lirical-request-matrix.json", "caseCount": 25, "requestCount": 25,
            "skippedGroup": "G0_LIRICAL_CLI_REQUIRES_OBSERVED_HPO", "assembly": "GRCh38",
            "requests": requests}


def runtime_binding(mapping):
    """Return a machine-local, truth-free path binding (never committed)."""
    requests = []
    for entry in mapping.get("requests", []):
        request = safe_request(entry)
        vcf_path, tbi_path = entry.get("vcfPath"), entry.get("tbiPath")
        require(isinstance(vcf_path, str) and isinstance(tbi_path, str), "RUNTIME_PATH_MISSING")
        request.update({"vcfPath": vcf_path, "tbiPath": tbi_path})
        requests.append(request)
    require(len(requests) == 50, "REQUEST_COUNT_NOT_50")
    return {"schemaVersion": "stage3a-lirical-runtime-binding-v1", "status": "TRUTH_FREE_RUNTIME_BINDING",
            "assembly": "GRCh38", "caseCount": 25, "requestCount": 50, "requests": requests}


def request_yaml(request, vcf_path):
    require(request["truthFree"] is True, "TRUTH_FREE_FLAG_REQUIRED")
    forbidden_keys = ("truth", "prediction", "rescue", "benchmark_rank")
    require(not any(str(key).lower() != "truthfree" and any(word in str(key).lower() for word in forbidden_keys)
                    for key in request), "FORBIDDEN_REQUEST_FIELD")
    lines = ["---", "sampleId: " + json.dumps(request["vcfSampleId"]),
             "hpoIds: " + json.dumps(request["hpoTerms"]), "negatedHpoIds: []", "sex: UNKNOWN",
             "vcf: " + json.dumps(str(vcf_path))]
    return "\n".join(lines) + "\n"


def vcf_sample_id(path):
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#CHROM"):
                columns = line.rstrip("\n").split("\t")
                require(len(columns) == 10, "VCF_NOT_SINGLE_SAMPLE")
                return columns[9]
    raise LiricalBaselineError("VCF_HEADER_MISSING")


def wsl_path(path):
    path = str(path).replace("\\", "/")
    match = re.fullmatch(r"([A-Za-z]):/(.*)", path)
    return f"/mnt/{match.group(1).lower()}/{match.group(2)}" if match else path


def lock_artifacts(directory):
    artifacts = []
    for path in sorted(Path(directory).rglob("*")):
        if path.is_file():
            os.chmod(path, path.stat().st_mode & ~0o222)
            artifacts.append({"logicalPath": str(path.relative_to(directory)).replace("\\", "/"),
                              "bytes": path.stat().st_size, "sha256": sha256(path)})
    require(bool(artifacts), "OUTPUT_MISSING")
    return artifacts


def run_request(request, run_root, java, jar, lirical_data, exomiser_data, timeout_seconds):
    """Run one truth-free request and return its lock record."""
    root = Path(run_root)
    output = request_output_dir(root, request)
    require(not output.exists(), "OUTPUT_DIRECTORY_ALREADY_EXISTS:" + request["requestId"])
    output.mkdir(parents=True)
    yaml_path = output / "input.yml"
    yaml_path.write_text(request_yaml(request, wsl_path(request["vcfPath"])), encoding="utf-8")
    command = [wsl_path(java), "-jar", wsl_path(jar), "yaml", "-y", wsl_path(yaml_path),
               "--assembly", "hg38", "-ed38", wsl_path(Path(exomiser_data) / "2602_hg38"), "-d", wsl_path(lirical_data),
               "-o", wsl_path(output), "-x", "result", "-f", "json", "-f", "tsv", "-f", "html",
               "--validation-policy", "MINIMAL"]
    started = time.time()
    log = output / "lirical.stdout.log"
    try:
        completed = subprocess.run(["wsl.exe", "-d", "Ubuntu", "--", *command], stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, timeout=timeout_seconds, check=False)
        log.write_text(completed.stdout[-20000:], encoding="utf-8")
        exit_code, status = completed.returncode, "COMPLETED_LOCKED" if completed.returncode == 0 else "FAILED_NONZERO_EXIT"
    except subprocess.TimeoutExpired as error:
        log.write_text((error.stdout or "")[-20000:] if isinstance(error.stdout, str) else "TIMEOUT\n", encoding="utf-8")
        exit_code, status = None, "FAILED_TIMEOUT"
    record = {"schemaVersion": "stage3a-lirical-request-lock-v1", "requestId": request["requestId"],
              "caseId": request["caseId"], "groupId": request["groupId"], "selectionOrder": request["selectionOrder"],
              "inputVcfSha256": request["vcfSha256"], "tbiSha256": request["tbiSha256"],
              "hpoCount": len(request["hpoTerms"]), "truthRead": False, "exitCode": exit_code,
              "terminalStatus": status, "wallClockSeconds": round(time.time() - started, 3),
              "commandSha256": hashlib.sha256("\0".join(command).encode("utf-8")).hexdigest(),
              "yamlSha256": sha256(yaml_path), "logSha256": sha256(log)}
    if status == "COMPLETED_LOCKED":
        record["artifacts"] = lock_artifacts(output)
    return record


def request_output_dir(run_root, request):
    safe_case = re.sub(r"[^A-Za-z0-9._-]+", "_", request["caseId"])
    return Path(run_root) / "requests" / f"{request['selectionOrder']:02d}-{safe_case}" / request["groupId"]


def preflight(binding, matrix, exomiser_data, lirical_data):
    expected = {entry["requestId"]: entry for entry in matrix["requests"]}
    request_count = len(expected)
    require(request_count in (25, 50), "MATRIX_REQUEST_COUNT_INVALID")
    if request_count == 25:
        require(all(entry["groupId"] == "G1" and entry["hpoTerms"] for entry in expected.values()),
                "G1_ONLY_MATRIX_INVALID")
    verified = []
    sources = {source.get("requestId"): source for source in binding.get("requests", [])}
    for request_id, request in expected.items():
        source = sources.get(request_id)
        require(source is not None, "MAPPING_MATRIX_REQUEST_MISMATCH:" + request_id)
        vcf, tbi = Path(source["vcfPath"]), Path(source["tbiPath"])
        require(vcf.is_file() and tbi.is_file(), "INPUT_MISSING:" + request["requestId"])
        require(sha256(vcf) == request["vcfSha256"], "VCF_SHA_MISMATCH:" + request["requestId"])
        require(sha256(tbi) == request["tbiSha256"], "TBI_SHA_MISMATCH:" + request["requestId"])
        require(vcf_sample_id(vcf) == request["vcfSampleId"], "VCF_SAMPLE_MISMATCH:" + request["requestId"])
        verified.append(request["requestId"])
    exomiser_data = Path(exomiser_data)
    lirical_data = Path(lirical_data)
    require((exomiser_data / "2602_hg38" / "2602_hg38_variants.mv.db").is_file(), "EXOMISER_2602_VARIANTS_MISSING")
    require((exomiser_data / "2602_hg38" / "2602_hg38_clinvar.mv.db").is_file(), "EXOMISER_2602_CLINVAR_MISSING")
    required_lirical = ("hp.json", "phenotype.hpoa", "hgnc_complete_set.txt", "mim2gene_medgen", "en_product6.xml",
                        "hg38_refseq.ser")
    require(all((lirical_data / item).is_file() for item in required_lirical), "LIRICAL_DATA_INCOMPLETE")
    return {"status": "PASS", "requestCount": len(verified), "truthRead": False,
            "liricalResourceStatus": "PRESENT", "exomiserDataVersion": "2602"}


def pre_run_manifest(binding, matrix, java, jar, lirical_data, exomiser_data):
    """Freeze only truth-free execution identity before a batch is started."""
    requests = matrix["requests"]
    require(len(requests) == 25 and all(item["groupId"] == "G1" for item in requests),
            "G1_ONLY_MATRIX_INVALID")
    bound_ids = {item["requestId"] for item in binding.get("requests", [])}
    require(all(item["requestId"] in bound_ids for item in requests), "MAPPING_MATRIX_REQUEST_MISMATCH")
    return {"schemaVersion": "stage3a-lirical-g1-pre-run-v1", "status": "FROZEN_PRE_RUN",
            "requestCount": 25, "groups": ["G1"], "truthRead": False,
            "matrixSha256": sha256(matrix["_path"]) if "_path" in matrix else None,
            "java": {"path": str(java), "sha256": sha256(java)},
            "lirical": {"jar": str(jar), "sha256": sha256(jar), "dataDirectory": str(lirical_data), "version": "2.4.0"},
            "variantResources": {"dataDirectory": str(exomiser_data), "assembly": "hg38", "version": "2602"},
            "scope": "G1_ONLY_NON_EQUIVALENT", "noFallback": True}


def bound_execution_requests(binding, matrix):
    """Return ordered requests only when runtime paths exactly match frozen inputs."""
    frozen = sorted(matrix["requests"], key=lambda item: item["selectionOrder"])
    require(len(frozen) == 25 and all(item["groupId"] == "G1" and item["hpoTerms"] for item in frozen),
            "G1_ONLY_MATRIX_INVALID")
    sources = {item["requestId"]: item for item in binding.get("requests", [])}
    result = []
    for item in frozen:
        source = sources.get(item["requestId"])
        require(source is not None, "MAPPING_MATRIX_REQUEST_MISMATCH:" + item["requestId"])
        for key in ("caseId", "groupId", "selectionOrder", "vcfSha256", "tbiSha256", "vcfSampleId", "hpoTerms"):
            require(source.get(key) == item.get(key), "RUNTIME_BINDING_DRIFT:" + key + ":" + item["requestId"])
        result.append({**item, "vcfPath": source["vcfPath"], "tbiPath": source["tbiPath"]})
    return result


def replace_atomic(path, value):
    path = Path(path)
    encoded = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(encoded)
    os.replace(temporary, path)


def run_batch(requests, run_root, java, jar, lirical_data, exomiser_data, timeout_seconds):
    """Run frozen G1 requests serially, preserving each terminal attempt and checkpoint."""
    root = Path(run_root)
    checkpoint = root / "manifest" / "checkpoint.json"
    records = []
    for request in requests:
        lock_path = root / "manifest" / (request["requestId"] + ".lock.json")
        if lock_path.exists():
            record = read_json(lock_path)
            require(record.get("terminalStatus") == "COMPLETED_LOCKED", "EXISTING_NONLOCKED_ATTEMPT:" + request["requestId"])
        else:
            record = run_request(request, root, java, jar, lirical_data, exomiser_data, timeout_seconds)
            write_atomic(lock_path, record)
        records.append(record)
        replace_atomic(checkpoint, {"schemaVersion": "stage3a-lirical-g1-checkpoint-v1", "truthRead": False,
                                    "completedRequestIds": [item["requestId"] for item in records],
                                    "terminalStatuses": {item["requestId"]: item["terminalStatus"] for item in records}})
    return {"truthRead": False, "completed": len(records),
            "successful": sum(item["terminalStatus"] == "COMPLETED_LOCKED" for item in records),
            "terminalStatus": records[-1]["terminalStatus"] if records else "NOT_STARTED"}


def read_ranked_tsv(tsv_path):
    tsv_path = Path(tsv_path)
    require(tsv_path.is_file(), "LIRICAL_TSV_MISSING")
    lines = [line for line in tsv_path.read_text(encoding="utf-8").splitlines()
             if line and not line.startswith("!")]
    require(bool(lines), "LIRICAL_TSV_EMPTY")
    rows = list(csv.DictReader(lines, delimiter="\t"))
    require(bool(rows), "LIRICAL_TSV_RESULTS_MISSING")
    require({"rank", "diseaseCurie", "variants"}.issubset(rows[0]), "LIRICAL_TSV_SCHEMA_INVALID")
    ranks = []
    disease_ids = set()
    for row in rows:
        try:
            rank = int(row["rank"])
        except (TypeError, ValueError):
            raise LiricalBaselineError("LIRICAL_TSV_RANK_INVALID") from None
        ranks.append(rank)
        disease_id = row.get("diseaseCurie")
        require(isinstance(disease_id, str) and disease_id, "LIRICAL_TSV_DISEASE_ID_MISSING")
        require(disease_id not in disease_ids, "LIRICAL_TSV_DISEASE_ID_DUPLICATE:" + disease_id)
        disease_ids.add(disease_id)
    require(ranks == list(range(1, len(rows) + 1)), "LIRICAL_TSV_RANK_NOT_CONTIGUOUS")
    return rows


def parse_output(json_path, tsv_path):
    """Use explicit native TSV ranks and enrich them with truth-free JSON evidence."""
    require(Path(json_path).is_file(), "LIRICAL_JSON_MISSING")
    require(Path(tsv_path).is_file(), "LIRICAL_TSV_MISSING")
    payload = read_json(json_path)
    results = payload.get("analysisResults")
    require(isinstance(results, list) and results, "LIRICAL_RESULTS_MISSING")
    by_disease = {}
    for result in results:
        disease_id = result.get("diseaseId")
        require(isinstance(disease_id, str) and disease_id, "LIRICAL_DISEASE_ID_MISSING")
        require(disease_id not in by_disease, "LIRICAL_JSON_DISEASE_ID_DUPLICATE:" + disease_id)
        by_disease[disease_id] = result
    rows = read_ranked_tsv(tsv_path)
    diseases, genes = [], {}
    gene_symbols = {}
    gene_ids_by_symbol = {}
    previous_probability = None
    for row in rows:
        rank = int(row["rank"])
        disease_id = row["diseaseCurie"]
        require(disease_id in by_disease, "LIRICAL_TSV_JSON_DISEASE_MISSING:" + disease_id)
        result = by_disease[disease_id]
        probability = result.get("posttestProbability")
        require(isinstance(probability, (int, float)), "LIRICAL_POSTTEST_PROBABILITY_MISSING:" + disease_id)
        require(previous_probability is None or probability <= previous_probability + 1e-15,
                "LIRICAL_TSV_POSTTEST_ORDER_INVALID:" + disease_id)
        previous_probability = probability
        genotype_evidence = result.get("genotypeLR") or {}
        require(isinstance(genotype_evidence, dict), "LIRICAL_GENOTYPE_EVIDENCE_INVALID:" + disease_id)
        gene = genotype_evidence.get("geneId")
        gene_id = gene.get("id") if isinstance(gene, dict) else None
        gene_symbol = gene.get("symbol") if isinstance(gene, dict) else None
        diseases.append({
            "rank": rank,
            "diseaseId": disease_id,
            "posttestProbability": probability,
            "compositeLR": result.get("compositeLR"),
            "geneId": gene_id,
            "geneSymbol": gene_symbol,
            "genotypeEvidence": genotype_evidence,
            "phenotypeEvidence": {
                "observed": result.get("observedPhenotypicFeatures", []),
                "excluded": result.get("excludedPhenotypicFeatures", []),
            },
            "supportingVariants": row.get("variants", ""),
        })
        if isinstance(gene, dict) and isinstance(gene.get("id"), str) and gene["id"]:
            require(isinstance(gene_symbol, str) and gene_symbol, "LIRICAL_GENE_SYMBOL_MISSING:" + gene["id"])
            if gene["id"] in gene_symbols:
                require(gene_symbols[gene["id"]] == gene_symbol,
                        "LIRICAL_GENE_SYMBOL_CONFLICT:" + gene["id"])
            if gene_symbol in gene_ids_by_symbol:
                require(gene_ids_by_symbol[gene_symbol] == gene["id"],
                        "LIRICAL_GENE_ID_CONFLICT:" + gene_symbol)
            gene_symbols[gene["id"]] = gene_symbol
            gene_ids_by_symbol[gene_symbol] = gene["id"]
            if gene["id"] not in genes:
                genes[gene["id"]] = {"rank": len(genes) + 1, "geneId": gene["id"],
                                      "geneSymbol": gene_symbol, "firstDiseaseRank": rank,
                                      "firstDiseaseId": disease_id}
    return {"schemaVersion": "stage3a-lirical-output-projection-v2",
            "parserVersion": "LIRICAL_TSV_RANK_V2", "rankSource": "LIRICAL_TSV_EXPLICIT_RANK",
            "truthRead": False, "rawJsonSha256": sha256(json_path), "rawTsvSha256": sha256(tsv_path),
            "nativeDiseaseCount": len(diseases),
            "nativeDiseaseRanks": diseases,
            "geneProjectionRule": "DENSE_FIRST_TSV_DISEASE_OCCURRENCE",
            "geneRanks": list(genes.values()),
            "variantRankStatus": "NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK"}


def parse_batch(requests, run_root, projection_root):
    require(len(requests) == 25, "REQUEST_COVERAGE_NOT_25")
    require(len({request["requestId"] for request in requests}) == 25, "REQUEST_ID_DUPLICATE")
    records = []
    for request in requests:
        lock_path = Path(run_root) / "manifest" / (request["requestId"] + ".lock.json")
        lock = read_json(lock_path)
        require(lock.get("terminalStatus") == "COMPLETED_LOCKED", "REQUEST_NOT_LOCKED:" + request["requestId"])
        output_dir = request_output_dir(run_root, request)
        artifacts = {item["logicalPath"]: item for item in lock.get("artifacts", [])}
        for logical_path in ("result.json", "result.tsv"):
            require(logical_path in artifacts, "LOCKED_ARTIFACT_MISSING:" + request["requestId"] + ":" + logical_path)
            artifact_path = output_dir / logical_path
            require(artifact_path.is_file(), "OUTPUT_ARTIFACT_MISSING:" + request["requestId"] + ":" + logical_path)
            require(sha256(artifact_path) == artifacts[logical_path]["sha256"],
                    "OUTPUT_ARTIFACT_HASH_MISMATCH:" + request["requestId"] + ":" + logical_path)
        projection = parse_output(output_dir / "result.json", output_dir / "result.tsv")
        projection.update({"requestId": request["requestId"], "caseId": request["caseId"],
                           "groupId": request["groupId"], "selectionOrder": request["selectionOrder"]})
        projection_path = Path(projection_root) / (request["requestId"] + ".json")
        write_atomic(projection_path, projection)
        records.append({"requestId": request["requestId"], "rawJsonSha256": projection["rawJsonSha256"],
                        "rawTsvSha256": projection["rawTsvSha256"],
                        "projectionSha256": sha256(projection_path),
                        "nativeDiseaseCount": projection["nativeDiseaseCount"], "geneCount": len(projection["geneRanks"])})
    require(len(records) == 25, "REQUEST_COVERAGE_NOT_25")
    return {"schemaVersion": "stage3a-lirical-g1-projection-batch-v2", "truthRead": False,
            "requestCount": len(records), "records": records}


def freeze_batch_manifest(requests, run_root):
    locks = []
    for request in requests:
        lock_path = Path(run_root) / "manifest" / (request["requestId"] + ".lock.json")
        lock = read_json(lock_path)
        require(lock.get("terminalStatus") == "COMPLETED_LOCKED", "REQUEST_NOT_LOCKED:" + request["requestId"])
        locks.append({"requestId": request["requestId"], "lockSha256": sha256(lock_path),
                      "lockedAtEpoch": lock_path.stat().st_mtime})
    return {"schemaVersion": "stage3a-lirical-g1-batch-manifest-v1", "status": "OUTPUTS_LOCKED",
            "truthRead": False, "requestCount": len(locks), "successfulRequestCount": len(locks),
            "lastOutputLockEpoch": max(item["lockedAtEpoch"] for item in locks), "locks": locks}


def rank_metrics(rows, key, cutoffs=(1, 5, 10, 50)):
    """Summarize a rank field while retaining unranked cases in the denominator."""
    ranks = [row[key] for row in rows if isinstance(row.get(key), int)]
    count = len(rows)
    failed = sum(row.get("terminalStatus") != "COMPLETED_LOCKED" for row in rows)
    return {"eligible": count, "ranked": len(ranks), "unranked": count - len(ranks) - failed, "failed": failed,
            "recallAt": {str(k): sum(rank <= k for rank in ranks) / count for k in cutoffs},
            "mrr": sum(1 / rank for rank in ranks) / count if count else None,
            "medianRank": median(ranks) if ranks else None}


def count_summary(values):
    require(bool(values), "COUNT_SUMMARY_EMPTY")
    return {"minimum": min(values), "maximum": max(values), "median": median(values)}


def evaluate_g1(projection_root, locked_batch_manifest, approved_dataset_path, matrix):
    """Validate all locked projections before opening the approved truth manifest."""
    require(locked_batch_manifest.get("status") == "OUTPUTS_LOCKED", "OUTPUTS_NOT_LOCKED")
    require(locked_batch_manifest.get("truthRead") is False, "EXECUTION_TRUTH_CONTAMINATION")
    require(locked_batch_manifest.get("requestCount") == 25, "LOCKED_REQUEST_COVERAGE_INVALID")
    require(locked_batch_manifest.get("successfulRequestCount") == 25, "LOCKED_REQUEST_SUCCESS_INVALID")
    last_lock = locked_batch_manifest.get("lastOutputLockEpoch")
    require(isinstance(last_lock, (int, float)), "LAST_OUTPUT_LOCK_TIME_INVALID")
    locked_ids = [item.get("requestId") for item in locked_batch_manifest.get("locks", [])]
    require(len(locked_ids) == 25 and len(set(locked_ids)) == 25, "LOCKED_REQUEST_IDS_INVALID")

    requests = sorted(matrix.get("requests", []), key=lambda item: item.get("selectionOrder", 0))
    require(matrix.get("requestCount") == 25 and len(requests) == 25, "G1_MATRIX_COVERAGE_INVALID")
    require([item.get("selectionOrder") for item in requests] == list(range(1, 26)),
            "G1_MATRIX_ORDER_INVALID")
    require(all(item.get("groupId") == "G1" and item.get("truthFree") is True for item in requests),
            "G1_MATRIX_SCOPE_INVALID")
    request_ids = [item.get("requestId") for item in requests]
    case_ids = [item.get("caseId") for item in requests]
    require(len(set(request_ids)) == 25 and len(set(case_ids)) == 25, "G1_MATRIX_ID_DUPLICATE")
    require(set(request_ids) == set(locked_ids), "LOCK_MATRIX_REQUEST_MISMATCH")

    projection_root = Path(projection_root)
    projection_files = sorted(projection_root.glob("*.json"))
    require(len(projection_files) == 25, "PROJECTION_FILE_COVERAGE_INVALID")
    require({path.stem for path in projection_files} == set(request_ids), "PROJECTION_REQUEST_COVERAGE_INVALID")
    projections = {}
    for request in requests:
        request_id = request["requestId"]
        projection = read_json(projection_root / (request_id + ".json"))
        require(projection.get("schemaVersion") == "stage3a-lirical-output-projection-v2",
                "PROJECTION_SCHEMA_INVALID:" + request_id)
        require(projection.get("rankSource") == "LIRICAL_TSV_EXPLICIT_RANK",
                "PROJECTION_RANK_SOURCE_INVALID:" + request_id)
        require(projection.get("truthRead") is False, "PROJECTION_TRUTH_CONTAMINATION:" + request_id)
        require(projection.get("requestId") == request_id and projection.get("caseId") == request["caseId"],
                "PROJECTION_REQUEST_BINDING_INVALID:" + request_id)
        require(projection.get("selectionOrder") == request["selectionOrder"],
                "PROJECTION_ORDER_INVALID:" + request_id)
        require(projection.get("variantRankStatus")
                == "NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK",
                "PROJECTION_VARIANT_STATUS_INVALID:" + request_id)
        projections[request_id] = projection

    truth_read_at = time.time()
    require(truth_read_at > last_lock, "TRUTH_READ_NOT_AFTER_LAST_OUTPUT_LOCK")
    approved_dataset_path = Path(approved_dataset_path)
    approved_dataset = read_json(approved_dataset_path)
    require(approved_dataset.get("mainCaseCount") == 25 and approved_dataset.get("backupCaseCount") == 5,
            "APPROVED_DATASET_COUNTS_INVALID")
    main_cases = [case for case in approved_dataset.get("cases", [])
                  if case.get("inclusionStatus") == "APPROVED_MAIN"]
    backup_cases = [case for case in approved_dataset.get("cases", [])
                    if case.get("inclusionStatus") == "APPROVED_BACKUP"]
    require(len(main_cases) == 25 and len(backup_cases) == 5, "APPROVED_DATASET_ROLE_INVALID")
    truth_by_case = {case.get("caseId"): case for case in main_cases}
    require(len(truth_by_case) == 25 and set(truth_by_case) == set(case_ids),
            "APPROVED_MAIN_CASE_COVERAGE_INVALID")

    evaluated = []
    for request in requests:
        request_id, case_id = request["requestId"], request["caseId"]
        projection, truth = projections[request_id], truth_by_case[case_id]
        truth_diseases = {str(item) for item in truth.get("diseaseIds", [])}
        truth_genes = {str(item).upper() for item in truth.get("truthGenes", [])}
        require(bool(truth_diseases), "DISEASE_TRUTH_MISSING:" + case_id)
        require(bool(truth_genes), "GENE_TRUTH_MISSING:" + case_id)
        disease_matches = [row["rank"] for row in projection["nativeDiseaseRanks"]
                           if row.get("diseaseId") in truth_diseases]
        gene_matches = [row["rank"] for row in projection["geneRanks"]
                        if str(row.get("geneSymbol", "")).upper() in truth_genes]
        disease_rank = min(disease_matches) if disease_matches else None
        gene_rank = min(gene_matches) if gene_matches else None
        evaluated.append({
            "requestId": request_id, "caseId": case_id, "selectionOrder": request["selectionOrder"],
            "terminalStatus": "COMPLETED_LOCKED",
            "diseaseRank": disease_rank,
            "diseaseHitAtK": {str(k): disease_rank is not None and disease_rank <= k
                              for k in (1, 3, 5, 10, 50)},
            "diseaseMissTaxonomy": "HIT" if disease_rank is not None
            else "TRUTH_DISEASE_ABSENT_FROM_NATIVE_TSV",
            "geneRank": gene_rank,
            "geneHitAtK": {str(k): gene_rank is not None and gene_rank <= k for k in (1, 5, 10, 50)},
            "geneMissTaxonomy": "HIT" if gene_rank is not None
            else "TRUTH_GENE_ABSENT_FROM_DERIVED_GENE_PROJECTION",
            "candidateDiseaseCount": len(projection["nativeDiseaseRanks"]),
            "candidateGeneCount": len(projection["geneRanks"]),
            "variantRank": None,
            "variantRankStatus": "NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK",
        })
    disease_metrics = rank_metrics(evaluated, "diseaseRank", (1, 3, 5, 10, 50))
    gene_metrics = rank_metrics(evaluated, "geneRank")
    return {
        "schemaVersion": "stage3a-lirical-g1-evaluation-v2",
        "status": "CORRECTED_POST_LOCK_EVALUATION",
        "truthRead": True,
        "truthReadAtEpoch": truth_read_at,
        "lastOutputLockEpoch": last_lock,
        "truthReadAfterLastOutputLock": True,
        "approvedDatasetSha256": sha256(approved_dataset_path),
        "matrixSha256": matrix.get("_sha256"),
        "scope": "G1_ONLY_NON_EQUIVALENT",
        "requestCount": len(evaluated),
        "evaluated": evaluated,
        "diseaseEndpoint": {"type": "LIRICAL_NATIVE_DISEASE_RANK", "metrics": disease_metrics},
        "geneEndpoint": {"type": "DERIVED_DENSE_GENE_RANK", "metrics": gene_metrics},
        "variantEndpoint": {
            "status": "NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK",
            "metrics": None,
        },
        "candidateCounts": {
            "diseasesPerCase": count_summary([item["candidateDiseaseCount"] for item in evaluated]),
            "genesPerCase": count_summary([item["candidateGeneCount"] for item in evaluated]),
        },
    }


def compare_g1(evaluation, locked_baseline, locked_baseline_sha256=None):
    """Compare post-lock LIRICAL G1 gene ranks with locked Exomiser G1 ranks."""
    require(evaluation.get("schemaVersion") == "stage3a-lirical-g1-evaluation-v2",
            "LIRICAL_EVALUATION_SCHEMA_INVALID")
    require(evaluation.get("truthRead") is True, "LIRICAL_EVALUATION_NOT_UNLOCKED")
    require(evaluation.get("truthReadAfterLastOutputLock") is True, "LIRICAL_TRUTH_ORDER_INVALID")
    expected = {item["requestId"]: item for item in locked_baseline.get("requests", []) if item.get("groupId") == "G1"}
    observed = {item["requestId"]: item for item in evaluation.get("evaluated", [])}
    require(len(expected) == 25 and set(expected) == set(observed), "G1_COMPARISON_COVERAGE_INVALID")
    paired = []
    counts = {"liricalImproved": 0, "tied": 0, "liricalWorsened": 0, "incomparable": 0}
    for request_id, exomiser in expected.items():
        lirical = observed[request_id]
        lirical_rank, exomiser_rank = lirical.get("geneRank"), exomiser.get("geneRank")
        if not isinstance(lirical_rank, int) or not isinstance(exomiser_rank, int):
            outcome = "INCOMPARABLE"
            counts["incomparable"] += 1
        elif lirical_rank < exomiser_rank:
            outcome = "LIRICAL_IMPROVED"
            counts["liricalImproved"] += 1
        elif lirical_rank == exomiser_rank:
            outcome = "TIED"
            counts["tied"] += 1
        else:
            outcome = "LIRICAL_WORSENED"
            counts["liricalWorsened"] += 1
        paired.append({"requestId": request_id, "caseId": exomiser["caseId"],
                       "selectionOrder": exomiser["selectionOrder"], "liricalGeneRank": lirical_rank,
                       "exomiserGeneRank": exomiser_rank, "outcome": outcome})
    lirical_metrics = rank_metrics(list(observed.values()), "geneRank")
    exomiser_metrics = rank_metrics(list(expected.values()), "geneRank")
    require(lirical_metrics == evaluation["geneEndpoint"]["metrics"], "LIRICAL_METRIC_RECALCULATION_MISMATCH")
    return {"schemaVersion": "stage3a-lirical-vs-exomiser-g1-comparison-v2",
            "scope": "G1_ONLY_NON_EQUIVALENT", "comparisonEndpoint": "GENE_RANK",
            "lockedExomiserBaselineSha256": locked_baseline_sha256,
            "lirical": lirical_metrics,
            "exomiser": exomiser_metrics,
            "candidateGeneCounts": {
                "lirical": count_summary([item["candidateGeneCount"] for item in observed.values()]),
                "exomiser": count_summary([item["candidateGeneCount"] for item in expected.values()]),
            },
            "paired": counts, "perRequest": paired,
            "variantComparison": "NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK",
            "diseaseComparison": "NOT_COMPARABLE_EXOMISER_LOCKED_BASELINE_HAS_NO_DISEASE_ENDPOINT",
            "resourceTimepoint": "RESOURCE_TIMEPOINT_NOT_STRICTLY_MATCHED"}


def freeze_corrected_baseline(v2_root, projection_batch_path, evaluation_path, comparison_path,
                              old_evaluation_path, old_comparison_path, batch_manifest_path,
                              expected_old_evaluation_sha256, expected_old_comparison_sha256,
                              expected_batch_manifest_sha256, parser_commit, evaluator_commit,
                              supersession_path, lock_path):
    """Freeze corrected artifacts while preserving and identifying the invalid v1 evaluation."""
    for value, code in (
            (expected_old_evaluation_sha256, "EXPECTED_OLD_EVALUATION_SHA_INVALID"),
            (expected_old_comparison_sha256, "EXPECTED_OLD_COMPARISON_SHA_INVALID"),
            (expected_batch_manifest_sha256, "EXPECTED_BATCH_MANIFEST_SHA_INVALID")):
        require(isinstance(value, str) and SHA256.fullmatch(value), code)
    require(re.fullmatch(r"[0-9a-f]{7,40}", parser_commit or "") is not None, "PARSER_COMMIT_INVALID")
    require(re.fullmatch(r"[0-9a-f]{7,40}", evaluator_commit or "") is not None, "EVALUATOR_COMMIT_INVALID")
    require(sha256(old_evaluation_path) == expected_old_evaluation_sha256, "OLD_EVALUATION_HASH_DRIFT")
    require(sha256(old_comparison_path) == expected_old_comparison_sha256, "OLD_COMPARISON_HASH_DRIFT")
    require(sha256(batch_manifest_path) == expected_batch_manifest_sha256, "BATCH_MANIFEST_HASH_DRIFT")
    batch_manifest = read_json(batch_manifest_path)
    require(batch_manifest.get("status") == "OUTPUTS_LOCKED"
            and batch_manifest.get("requestCount") == 25
            and batch_manifest.get("successfulRequestCount") == 25
            and batch_manifest.get("truthRead") is False, "RAW_BATCH_MANIFEST_INVALID")
    raw_run_root = Path(batch_manifest_path).parent.parent
    raw_artifact_count = 0
    lock_summaries = batch_manifest.get("locks", [])
    require(len(lock_summaries) == 25, "RAW_LOCK_COVERAGE_INVALID")
    for lock_summary in lock_summaries:
        request_id = lock_summary.get("requestId")
        lock_file = Path(batch_manifest_path).parent / (str(request_id) + ".lock.json")
        require(lock_file.is_file() and sha256(lock_file) == lock_summary.get("lockSha256"),
                "RAW_LOCK_HASH_MISMATCH:" + str(request_id))
        lock_record = read_json(lock_file)
        require(lock_record.get("terminalStatus") == "COMPLETED_LOCKED"
                and lock_record.get("truthRead") is False, "RAW_LOCK_STATUS_INVALID:" + str(request_id))
        output_root = request_output_dir(raw_run_root, lock_record)
        for artifact in lock_record.get("artifacts", []):
            artifact_path = output_root / artifact.get("logicalPath", "")
            require(artifact_path.is_file(), "RAW_ARTIFACT_MISSING:" + str(request_id))
            require(artifact_path.stat().st_size == artifact.get("bytes")
                    and sha256(artifact_path) == artifact.get("sha256"),
                    "RAW_ARTIFACT_HASH_MISMATCH:" + str(request_id) + ":" + artifact_path.name)
            attributes = getattr(artifact_path.stat(), "st_file_attributes", 0)
            require(not artifact_path.stat().st_mode & 0o222
                    or bool(attributes & getattr(stat, "FILE_ATTRIBUTE_READONLY", 0)),
                    "RAW_ARTIFACT_NOT_READ_ONLY:" + str(request_id) + ":" + artifact_path.name)
            raw_artifact_count += 1
    require(raw_artifact_count > 0, "RAW_ARTIFACT_COVERAGE_INVALID")

    v2_root = Path(v2_root)
    projection_root = v2_root / "projections"
    projection_files = sorted(projection_root.glob("*.json"))
    require(len(projection_files) == 25, "CORRECTED_PROJECTION_COVERAGE_INVALID")
    projection_batch = read_json(projection_batch_path)
    require(projection_batch.get("schemaVersion") == "stage3a-lirical-g1-projection-batch-v2"
            and projection_batch.get("requestCount") == 25
            and projection_batch.get("truthRead") is False, "CORRECTED_PROJECTION_BATCH_INVALID")
    projection_records = {item.get("requestId"): item for item in projection_batch.get("records", [])}
    require(len(projection_records) == 25, "CORRECTED_PROJECTION_RECORDS_INVALID")
    for path in projection_files:
        require(path.stem in projection_records, "CORRECTED_PROJECTION_UNRECORDED:" + path.stem)
        require(sha256(path) == projection_records[path.stem].get("projectionSha256"),
                "CORRECTED_PROJECTION_HASH_MISMATCH:" + path.stem)

    evaluation = read_json(evaluation_path)
    comparison = read_json(comparison_path)
    require(evaluation.get("schemaVersion") == "stage3a-lirical-g1-evaluation-v2"
            and evaluation.get("requestCount") == 25
            and evaluation.get("truthReadAfterLastOutputLock") is True,
            "CORRECTED_EVALUATION_INVALID")
    require(comparison.get("schemaVersion") == "stage3a-lirical-vs-exomiser-g1-comparison-v2",
            "CORRECTED_COMPARISON_INVALID")
    corrected_artifacts = [
        {"logicalPath": "projection-batch.json", "sha256": sha256(projection_batch_path)},
        {"logicalPath": "lirical-g1-evaluation-v2.json", "sha256": sha256(evaluation_path)},
        {"logicalPath": "lirical-vs-exomiser-g1-comparison-v2.json", "sha256": sha256(comparison_path)},
    ]
    supersession = {
        "schemaVersion": "stage3a-lirical-g1-evaluation-supersession-v1",
        "status": "LIRICAL_G1_BASELINE_CORRECTED_AND_LOCKED",
        "supersededReason": "SUPERSEDED_INVALID_JSON_ARRAY_ORDER_AS_RANK",
        "supersededArtifacts": [
            {"logicalPath": "evaluation/lirical-g1-evaluation.json",
             "sha256": expected_old_evaluation_sha256,
             "status": "SUPERSEDED_INVALID_JSON_ARRAY_ORDER_AS_RANK"},
            {"logicalPath": "evaluation/lirical-vs-exomiser-g1-comparison.json",
             "sha256": expected_old_comparison_sha256,
             "status": "SUPERSEDED_INVALID_JSON_ARRAY_ORDER_AS_RANK"},
        ],
        "correctedArtifacts": corrected_artifacts,
        "rawExecution": {
            "batchManifestSha256": expected_batch_manifest_sha256,
            "requestCount": 25,
            "lockCount": len(lock_summaries),
            "artifactCount": raw_artifact_count,
            "rawOutputsRerun": False,
            "rawOutputsModified": False,
        },
        "parserCommit": parser_commit,
        "evaluatorCommit": evaluator_commit,
        "reevaluatedAtEpoch": time.time(),
    }
    write_atomic(supersession_path, supersession)
    expected_files = set(projection_files) | {
        Path(projection_batch_path), Path(evaluation_path), Path(comparison_path), Path(supersession_path)}
    actual_files = {path for path in v2_root.rglob("*") if path.is_file()}
    require(actual_files == expected_files, "CORRECTED_ARTIFACT_SCOPE_INVALID")
    artifacts = lock_artifacts(v2_root)
    lock = {
        "schemaVersion": "stage3a-lirical-g1-corrected-baseline-lock-v1",
        "status": "LIRICAL_G1_BASELINE_CORRECTED_AND_LOCKED",
        "truthReadAfterLastOutputLock": True,
        "artifactCount": len(artifacts),
        "artifacts": artifacts,
    }
    write_atomic(lock_path, lock)
    os.chmod(lock_path, Path(lock_path).stat().st_mode & ~0o222)
    return lock


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    freeze = commands.add_parser("freeze-matrix")
    freeze.add_argument("--source-mapping", type=Path, required=True)
    freeze.add_argument("--output", type=Path, required=True)
    bind = commands.add_parser("freeze-runtime-binding")
    bind.add_argument("--source-mapping", type=Path, required=True)
    bind.add_argument("--output", type=Path, required=True)
    g1 = commands.add_parser("freeze-g1-only")
    g1.add_argument("--matrix", type=Path, required=True)
    g1.add_argument("--output", type=Path, required=True)
    check = commands.add_parser("preflight")
    check.add_argument("--runtime-binding", type=Path, required=True)
    check.add_argument("--matrix", type=Path, required=True)
    check.add_argument("--exomiser-data", type=Path, required=True)
    check.add_argument("--lirical-data", type=Path, required=True)
    check.add_argument("--output", type=Path, required=True)
    pre_run = commands.add_parser("freeze-pre-run")
    pre_run.add_argument("--runtime-binding", type=Path, required=True)
    pre_run.add_argument("--matrix", type=Path, required=True)
    pre_run.add_argument("--java", type=Path, required=True)
    pre_run.add_argument("--jar", type=Path, required=True)
    pre_run.add_argument("--lirical-data", type=Path, required=True)
    pre_run.add_argument("--exomiser-data", type=Path, required=True)
    pre_run.add_argument("--output", type=Path, required=True)
    yaml_command = commands.add_parser("write-yaml")
    yaml_command.add_argument("--request", type=Path, required=True)
    yaml_command.add_argument("--vcf", type=Path, required=True)
    yaml_command.add_argument("--output", type=Path, required=True)
    run = commands.add_parser("run-one")
    run.add_argument("--runtime-binding", type=Path, required=True)
    run.add_argument("--matrix", type=Path, required=True)
    run.add_argument("--request-id", required=True)
    run.add_argument("--run-root", type=Path, required=True)
    run.add_argument("--java", type=Path, required=True)
    run.add_argument("--jar", type=Path, required=True)
    run.add_argument("--lirical-data", type=Path, required=True)
    run.add_argument("--exomiser-data", type=Path, required=True)
    run.add_argument("--timeout-seconds", type=int, default=900)
    batch = commands.add_parser("run-batch")
    batch.add_argument("--runtime-binding", type=Path, required=True)
    batch.add_argument("--matrix", type=Path, required=True)
    batch.add_argument("--run-root", type=Path, required=True)
    batch.add_argument("--java", type=Path, required=True)
    batch.add_argument("--jar", type=Path, required=True)
    batch.add_argument("--lirical-data", type=Path, required=True)
    batch.add_argument("--exomiser-data", type=Path, required=True)
    batch.add_argument("--timeout-seconds", type=int, default=900)
    parse = commands.add_parser("parse-output")
    parse.add_argument("--json", type=Path, required=True)
    parse.add_argument("--tsv", type=Path, required=True)
    parse.add_argument("--output", type=Path, required=True)
    parse_batch_command = commands.add_parser("parse-batch")
    parse_batch_command.add_argument("--runtime-binding", type=Path, required=True)
    parse_batch_command.add_argument("--matrix", type=Path, required=True)
    parse_batch_command.add_argument("--run-root", type=Path, required=True)
    parse_batch_command.add_argument("--projection-root", type=Path, required=True)
    parse_batch_command.add_argument("--output", type=Path, required=True)
    freeze_batch = commands.add_parser("freeze-batch-manifest")
    freeze_batch.add_argument("--runtime-binding", type=Path, required=True)
    freeze_batch.add_argument("--matrix", type=Path, required=True)
    freeze_batch.add_argument("--run-root", type=Path, required=True)
    freeze_batch.add_argument("--output", type=Path, required=True)
    evaluate = commands.add_parser("evaluate-g1")
    evaluate.add_argument("--projection-root", type=Path, required=True)
    evaluate.add_argument("--batch-manifest", type=Path, required=True)
    evaluate.add_argument("--approved-dataset", type=Path, required=True)
    evaluate.add_argument("--matrix", type=Path, required=True)
    evaluate.add_argument("--output", type=Path, required=True)
    compare = commands.add_parser("compare-g1")
    compare.add_argument("--evaluation", type=Path, required=True)
    compare.add_argument("--locked-baseline", type=Path, required=True)
    compare.add_argument("--output", type=Path, required=True)
    correction = commands.add_parser("freeze-corrected-baseline")
    correction.add_argument("--v2-root", type=Path, required=True)
    correction.add_argument("--projection-batch", type=Path, required=True)
    correction.add_argument("--evaluation", type=Path, required=True)
    correction.add_argument("--comparison", type=Path, required=True)
    correction.add_argument("--old-evaluation", type=Path, required=True)
    correction.add_argument("--old-comparison", type=Path, required=True)
    correction.add_argument("--batch-manifest", type=Path, required=True)
    correction.add_argument("--expected-old-evaluation-sha256", required=True)
    correction.add_argument("--expected-old-comparison-sha256", required=True)
    correction.add_argument("--expected-batch-manifest-sha256", required=True)
    correction.add_argument("--parser-commit", required=True)
    correction.add_argument("--evaluator-commit", required=True)
    correction.add_argument("--supersession", type=Path, required=True)
    correction.add_argument("--lock", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "freeze-matrix":
        result = freeze_matrix(read_json(args.source_mapping))
        write_atomic(args.output, result)
    elif args.command == "freeze-runtime-binding":
        result = runtime_binding(read_json(args.source_mapping))
        write_atomic(args.output, result)
    elif args.command == "freeze-g1-only":
        result = freeze_g1_only_matrix(read_json(args.matrix))
        write_atomic(args.output, result)
    elif args.command == "preflight":
        result = preflight(read_json(args.runtime_binding), read_json(args.matrix), args.exomiser_data, args.lirical_data)
        write_atomic(args.output, result)
    elif args.command == "freeze-pre-run":
        matrix = read_json(args.matrix)
        matrix["_path"] = str(args.matrix)
        result = pre_run_manifest(read_json(args.runtime_binding), matrix, args.java, args.jar,
                                  args.lirical_data, args.exomiser_data)
        write_atomic(args.output, result)
    elif args.command == "run-one":
        requests = bound_execution_requests(read_json(args.runtime_binding), read_json(args.matrix))
        request = next((item for item in requests if item["requestId"] == args.request_id), None)
        require(request is not None, "REQUEST_NOT_FOUND")
        result = run_request(request, args.run_root, args.java, args.jar, args.lirical_data,
                             args.exomiser_data, args.timeout_seconds)
        write_atomic(args.run_root / "manifest" / (args.request_id + ".lock.json"), result)
    elif args.command == "run-batch":
        requests = bound_execution_requests(read_json(args.runtime_binding), read_json(args.matrix))
        result = run_batch(requests, args.run_root, args.java, args.jar, args.lirical_data,
                           args.exomiser_data, args.timeout_seconds)
    elif args.command == "parse-output":
        result = parse_output(args.json, args.tsv)
        write_atomic(args.output, result)
    elif args.command == "parse-batch":
        requests = bound_execution_requests(read_json(args.runtime_binding), read_json(args.matrix))
        result = parse_batch(requests, args.run_root, args.projection_root)
        write_atomic(args.output, result)
    elif args.command == "freeze-batch-manifest":
        requests = bound_execution_requests(read_json(args.runtime_binding), read_json(args.matrix))
        result = freeze_batch_manifest(requests, args.run_root)
        write_atomic(args.output, result)
    elif args.command == "evaluate-g1":
        matrix = read_json(args.matrix)
        matrix["_sha256"] = sha256(args.matrix)
        result = evaluate_g1(args.projection_root, read_json(args.batch_manifest),
                             args.approved_dataset, matrix)
        write_atomic(args.output, result)
    elif args.command == "compare-g1":
        result = compare_g1(read_json(args.evaluation), read_json(args.locked_baseline),
                            sha256(args.locked_baseline))
        write_atomic(args.output, result)
    elif args.command == "freeze-corrected-baseline":
        result = freeze_corrected_baseline(
            args.v2_root, args.projection_batch, args.evaluation, args.comparison,
            args.old_evaluation, args.old_comparison, args.batch_manifest,
            args.expected_old_evaluation_sha256, args.expected_old_comparison_sha256,
            args.expected_batch_manifest_sha256, args.parser_commit, args.evaluator_commit,
            args.supersession, args.lock)
    else:
        request = read_json(args.request)
        text = request_yaml(request, args.vcf)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        result = {"status": "CREATED", "sha256": sha256(args.output), "truthRead": False}
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
