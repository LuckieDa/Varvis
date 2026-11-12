"""Audit frozen literal expectations against saved candidates, with zero API calls.

This measures only the explicitly audited source fields, not clinical accuracy or
correctness of arbitrary extra model claims. Raw source review remains necessary.
"""
import argparse
import json
from pathlib import Path
from model_literature_contract import parse
from model_transport import bounded, require, decode_response
import base64


def evaluate(expectations, index_file):
    gold = parse(bounded(expectations, 262144))
    index = parse(bounded(index_file, 65536)); root = Path(index_file).resolve().parent
    require(index.get("schemaVersion") == "denovo-model-artifact-index-v1", "INDEX_INVALID")
    by_paper = {}; receipts = []
    from model_literature_contract import digest
    for entry in index["artifacts"]:
        path = (root / entry["path"]).resolve()
        require(path.is_relative_to(root), "ARTIFACT_PATH_INVALID")
        raw = bounded(path, 1048576)
        require(digest(raw) == entry["sha256"], "ARTIFACT_PIN_MISMATCH")
        a = parse(raw); receipt = a["receipt"]; response = base64.b64decode(a["responseBase64"], validate=True)
        require(digest(response) == receipt["responseSha256"], "ARTIFACT_RESPONSE_PIN_MISMATCH")
        candidate, unused = decode_response(response, receipt["identity"]["configuration"])
        require(receipt["identity"]["binding"]["pmcid"] == entry["pmcid"]
                and receipt["identity"]["binding"]["sourceSha256"] == entry["sourceSha256"], "ARTIFACT_SOURCE_MISMATCH")
        require(entry["pmcid"] not in by_paper, "DUPLICATE_PAPER")
        by_paper[entry["pmcid"]] = candidate; receipts.append(receipt)
    checks = []
    for case in gold["cases"]:
        matches = [r for r in by_paper.get(case["pmcid"], {}).get("cases", []) if r["caseLabel"] == case["caseLabel"]]
        for expected in case["expectedFields"]:
            values = [r for r in matches[0]["fields"] if r["field"] == expected["field"]] if len(matches) == 1 else []
            # Explicit non-records may be omitted; they may never be invented.
            not_reported = expected["sourceValue"] in {"None found", "Not applicable", "Under investigation",
                                                       "Recessive mutations identified in putative novel gene"}
            correct = (not_reported and not values and len(matches) == 1) or len(values) == 1 and any(
                r["sourceValue"] == expected["sourceValue"] and r["citation"]["unitId"] == expected["unitId"] for r in values)
            status = "PASS" if correct else "MISSING" if not values else "MISMATCH_REQUIRES_SOURCE_REVIEW"
            checks.append({"pmcid": case["pmcid"], "caseLabel": case["caseLabel"], "field": expected["field"], "status": status})
    unique = {r["identity"]["requestSha256"]: r for r in receipts}.values()
    return {"scope": "FROZEN_LITERAL_FIELDS_ONLY_NOT_CLINICAL_ACCURACY", "checks": checks,
            "passed": sum(c["status"] == "PASS" for c in checks), "audited": len(checks),
            "realProviderResponses": sum(r["transportKind"] == "REAL_PROVIDER" for r in unique),
            "syntheticResponses": sum(r["transportKind"] == "SYNTHETIC_TEST" for r in unique),
            "estimatedCredits": sum(r["estimatedMicroCredits"] for r in unique) / 1000000,
            "platformBillReconciled": False, "clinicalAdoption": "NONE", "modelCalls": 0, "networkCalls": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expectations", type=Path, required=True)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = evaluate(args.expectations, args.index)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in result.items() if k != "checks"}))


if __name__ == "__main__":
    main()
