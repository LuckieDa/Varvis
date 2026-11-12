"""Independent exact-public-input, boundary, raw score, dependency and saved-bundle checks."""
import json
import sys
from pathlib import Path
from decimal import Decimal

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_business_bundles import verify


def verify_synonymous(directory, boundary=False):
    directory = Path(directory)
    saved_path = directory / "saved-business.json"
    summary = verify(saved_path)
    saved = json.loads(saved_path.read_text(encoding="utf-8"))
    raw = json.loads((directory / "actual-raw-batch.json").read_text(encoding="utf-8"))
    panel = json.loads((Path(__file__).parent / "bp7-synonymous-public-panel-v1.json").read_text(encoding="utf-8"))
    exon = panel["independentExon"]
    controls = {int(c["allele"].split(":")[2]): c for c in ([panel["additionalBoundaryControl"]] if boundary else panel["controls"])}
    seen = set()
    observations = []
    assert saved["status"] == "COMPLETED_WITH_BLOCKERS"
    assert saved["candidateIds"] == (["exomiser-v1-00000001"] if boundary else ["exomiser-v1-00000001", "exomiser-v1-00000003", "exomiser-v1-00000002", "exomiser-v1-00000004"])
    for candidate in saved["candidates"]:
        for attempt in candidate["attempts"]:
            tx = attempt.get("transcriptInput") or {}
            if tx.get("predictionPath") != "SPLICEAI_SYNONYMOUS":
                assert attempt["blockers"]
                continue
            p = tx["synonymousExonPosition"]
            pos = p["position"]
            assert pos in controls and pos not in seen
            seen.add(pos)
            assert p["assembly"] == "GRCh38" and p["contig"].removeprefix("chr") == "1"
            assert (p["reference"], p["alternate"]) == (("G", "A") if boundary else ("C", "T"))
            assert p["transcriptId"] == panel["selectedTranscript"] and tx["hgvsc"] == controls[pos]["hgvsc"]
            assert (p["exonStart"], p["exonEnd"], p["strand"]) == (exon["start"], exon["end"], exon["strand"])
            assert p["exonId"] == exon["id"].split(".")[0]
            row = next(r for r in raw["results"] if r["allele"]["position"] == pos)
            assert row["status"] == "HIT" and row["exactAlleleMatch"]
            annotation = next(a for a in row["annotations"] if a["geneSymbol"] == "LMNA")
            score = max(Decimal(str(v)) for v in annotation["deltaScores"].values())
            evaluations = {e["criterionId"].split(":")[-1]: e for e in attempt["criterionEvaluations"]}
            bp7 = evaluations["BP7"]
            expected = "NOT_EVALUATED" if boundary else "MET" if pos == 156138697 else "NOT_MET"
            observed = bp7["evaluationResult"]["status"]
            if boundary:
                assert p["position"] == p["exonEnd"]
                assert observed == expected and bp7["criterionApplicability"]["status"] == "NOT_APPLICABLE"
            elif pos == 156138697:
                assert observed == expected
                assert score <= Decimal("0.10") and bp7["appliedStrength"] == "SUPPORTING"
            elif observed == "NOT_MET":
                assert score > Decimal("0.10") and evaluations["BP4"]["evaluationResult"]["status"] == "NOT_MET"
            else:
                # Preserve a real biological counterexample as a failed acceptance, not a green replacement expectation.
                assert observed == "MET" and score <= Decimal("0.10")
            observations.append({"position": pos, "rawMaximum": str(score), "expectedBp7": expected, "observedBp7": observed, "matchedIndependentExpectation": expected == observed})
    assert seen == set(controls)
    for label in ("rebuilt-get", "repeat-post"):
        event = json.loads((directory / (label + "-http-event.json")).read_text(encoding="utf-8"))
        assert event["runId"] == saved["runId"] and all(n == 0 for n in event["actualCallIncrement"].values())
    assert json.loads((directory / "repeat-post-response.json").read_text(encoding="utf-8")) == saved
    return {**summary, "controls": observations, "runId": saved["runId"], "rebuildAndRepeatZeroCalls": True,
            "acceptancePassed": all(o["matchedIndependentExpectation"] for o in observations)}


if __name__ == "__main__":
    report = verify_synonymous(sys.argv[1], "--boundary" in sys.argv[2:])
    print(json.dumps(report, indent=2))
    sys.exit(0 if report["acceptancePassed"] else 2)
