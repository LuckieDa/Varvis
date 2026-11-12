"""Independent CFTR identity/position/raw-score/experiment linkage and zero-call verification.

Coordinates and exon mapping were independently verified against MANE/Ensembl before inference;
no expected model score or clinical classification is asserted.
"""
import json
import sys
from pathlib import Path
from decimal import Decimal


def verify(directory, previous_failure):
    directory = Path(directory)
    load = lambda name: json.loads((directory / name).read_text(encoding="utf-8"))
    saved, raw = load("saved-business.json"), load("actual-raw-batch.json")
    assert saved["status"] == "COMPLETED" and len(saved["candidates"]) == 1
    attempt = saved["candidates"][0]["attempts"][0]
    tx = attempt["transcriptInput"]
    assert tx["predictionPath"] == "SPLICEAI_SYNONYMOUS"
    assert tx["hgvsc"] == "ENST00000003084.11:c.915C>T"
    p = tx["synonymousExonPosition"]
    assert (p["assembly"], p["contig"].removeprefix("chr"), p["position"], p["reference"], p["alternate"]) == ("GRCh38", "7", 117540145, "C", "T")
    assert (p["transcriptId"], p["exonId"], p["exonStart"], p["exonEnd"], p["strand"]) == ("ENST00000003084.11", "ENSE00000718634", 117540100, 117540346, 1)
    row, = raw["results"]
    assert row["status"] == "HIT" and row["exactAlleleMatch"]
    assert row["allele"]["position"] == p["position"]
    annotation, = [a for a in row["annotations"] if a["geneSymbol"] == "CFTR"]
    diagnostic, = directory.glob("cli-diagnostic/*/diagnostic.json")
    execution = json.loads(diagnostic.read_text(encoding="utf-8"))
    command = execution["command"]
    assert execution["returnCode"] == 0 and execution["automaticRetry"] is False
    assert command[1:3] == ["-m", "spliceai"]
    assert command[command.index("-D") + 1] == "4999" and command[command.index("-M") + 1] == "0"
    line, = [line for line in diagnostic.with_name("output.vcf").read_text().splitlines() if not line.startswith("#")]
    fields = line.split("\t")
    assert (fields[0], fields[1], fields[3], fields[4]) == ("7", "117540145", "C", "T")
    official, = [part.split("|") for part in fields[7].removeprefix("SpliceAI=").split(",") if part.split("|")[:2] == ["T", "CFTR"]]
    for index, name in enumerate(["AG", "AL", "DG", "DL"]):
        assert Decimal(official[index + 2]) == Decimal(annotation["deltaScores"]["DS_" + name])
        assert int(official[index + 6]) == annotation["deltaPositions"]["DP_" + name]
    score = max(Decimal(str(v)) for v in annotation["deltaScores"].values())
    evaluations = {e["criterionId"].split(":")[-1]: e for e in attempt["criterionEvaluations"]}
    expected = {"PP3": score >= Decimal("0.20"), "BP4": score <= Decimal("0.10"), "BP7": score <= Decimal("0.10")}
    for code, met in expected.items():
        assert evaluations[code]["evaluationResult"]["status"] == ("MET" if met else "NOT_MET")
        assert evaluations[code]["appliedStrength"] == ("SUPPORTING" if met else "NONE")
    checks = load("experiment-check-phase-0.json")
    assert checks == load("experiment-check-phase-1.json")
    assert checks["runId"] == saved["runId"] and checks["analysisId"] == saved["analysisId"]
    catalog = checks["material"]["catalog"]
    cftr, = [r for r in catalog["records"] if r["geneSymbol"] == "CFTR"]
    for check in checks["checks"]:
        assert check["recordIds"] == [cftr["recordId"]]
        assert check["molecularApplicability"] == "RECORDED_ASSAY_CONTEXT_ONLY"
        assert check["diseaseApplicability"] == "NOT_ASSESSED_NO_CLINICAL_INFERENCE"
        assert check["status"] == ("OBSERVATION_MISMATCH_REVIEW_REQUIRED" if expected["BP4"] else "UNVERIFIED")
        assert check["calculation"] == evaluations[check["criterionId"].split(":")[-1]]
    for name in ["rebuilt-get", "repeat-post"]:
        event = load(name + "-http-event.json")
        assert event["runId"] == saved["runId"] and all(n == 0 for n in event["actualCallIncrement"].values())
    assert saved == load("repeat-post-response.json")
    previous = json.loads(Path(previous_failure).read_text(encoding="utf-8"))
    assert saved["candidates"][0]["ba1ReusedFromRunId"] == previous["runId"]
    assert saved["candidates"][0]["ba1"] == previous["candidates"][0]["ba1"]
    return {"status": "VERIFIED", "rawMaximum": str(score), "deltaScores": annotation["deltaScores"],
            "runId": saved["runId"], "checkId": checks["checkId"], "calculationAndExperimentConsistent": True,
            "officialOutputAndRawBatchIdentical": True, "ba1ReuseIdentical": True,
            "rebuildReuseZeroCalls": True, "clinicalClassification": "NOT_PERFORMED", "browserCovered": False}


if __name__ == "__main__":
    print(json.dumps(verify(sys.argv[1], sys.argv[2]), indent=2))
