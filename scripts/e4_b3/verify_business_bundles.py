"""Validate actual saved Java bundles without changing their nodes or identities."""
import copy
import json
from pathlib import Path
import sys
from decimal import Decimal

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "evidence"))
import acmg_evidence_v2_contract as e1


def verify(path):
    saved = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    original = copy.deepcopy(saved)
    root = Path(__file__).resolve().parents[2] / "varvis-server/src/main/resources/acmg"
    cspec_root = root / "cspec-overlay-registry-v1"
    previous = e1.load_json(cspec_root / "cspec-overlay-registry-e4-a2a-2026-09-05.json")
    successor = e1.load_json(cspec_root / "cspec-overlay-registry-e4-b3-2026-09-08.json")
    assert set(previous) == set(successor)
    assert {k for k in previous if previous[k] != successor[k]} == {
        "registryId", "baseRuleRegistryId", "baseRuleRegistrySha256"}
    schemas = e1.ContractSchemas()
    count = 0
    splice_count = 0
    for item in saved["bundles"]:
        bundle = item["bundle"]
        binding = bundle["authorityBinding"]
        rule_path = root / "rule-registry-v2" / (binding["ruleRegistryId"] + ".json")
        cspec_path = root / "cspec-overlay-registry-v1" / (binding["cspecOverlayRegistryId"] + ".json")
        assert e1.rehash_bundle(copy.deepcopy(bundle)) == bundle, "ORIGINAL_JAVA_IDENTITY_MISMATCH"
        e1.validate_bundle(bundle, e1.load_json(e1.AUTHORITY_PATH), e1.load_json(rule_path),
                           e1.load_json(cspec_path), schemas, rule_registry_path=rule_path, cspec_registry_path=cspec_path)
        evaluations = {e["criterionId"].split(":")[-1]: e for e in bundle["criterionEvaluations"]}
        if "BP7" in evaluations:
            splice_count += 1
            assert len(evaluations) == 3
            splice = next(e for e in bundle["atomicEvidence"] if e["source"]["sourceId"].startswith("SPLICEAI_"))
            vep = next(e for e in bundle["atomicEvidence"] if e["source"]["sourceId"] == "VEP_116_GRCH38")
            assert evaluations["BP4"]["evidenceIds"] == [splice["evidenceId"]]
            assert set(evaluations["BP7"]["evidenceIds"]) == {splice["evidenceId"], vep["evidenceId"]}
            params = {p["name"]: p["value"] for p in evaluations["BP7"]["deterministicTrace"]["parameters"]}
            if evaluations["BP7"]["evaluationResult"]["status"] == "MET":
                assert params["dependency.bp4EvaluationId"] == evaluations["BP4"]["evaluationId"]
                assert evaluations["BP4"]["evaluationResult"]["status"] == "MET"
                profile = e1.load_json(root / "spliceai-bp7-supporting-profile-v1.json")
                if "position.synonymousAtExonFirstBase" in params:
                    assert str(params["position.synonymousAtExonFirstBase"]).lower() == "false"
                    assert int(params["position.synonymousDistanceFromExonEnd"]) >= int(params["threshold.synonymousMinimumEndDistance"])
                    assert int(params["threshold.synonymousMinimumEndDistance"]) == 3
                else:
                    position = profile["bp7PositionRules"]["intronicVariant"]
                    offset = int(params["position.intronicSignedOffset"])
                    assert offset >= position["donorMinimumSignedOffsetInclusive"] or offset <= position["acceptorMaximumSignedOffsetInclusive"]
                measurements = {m["metricId"]: m["rawValue"] for m in splice["measurements"]}
                maximum = max(Decimal(measurements["spliceai.DS_" + name]) for name in ("AG", "AL", "DG", "DL"))
                assert maximum == Decimal(measurements["spliceai.maximumRawDeltaScore"])
                assert maximum <= Decimal(profile["thresholds"]["benignMaxInclusive"]["rawValue"])
        count += 1
    assert saved == original, "JAVA_OUTPUT_MUTATED"
    return {"bundles": count, "spliceBundles": splice_count, "javaNodesUnchanged": True}


if __name__ == "__main__":
    print(json.dumps(verify(sys.argv[1])))
