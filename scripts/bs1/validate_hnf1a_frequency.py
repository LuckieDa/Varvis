#!/usr/bin/env python3
"""Independent E1 schema/identity and decimal cross-check of the HNF1A business artifact.

Use the existing validator packages through --validator-packages when Python site
initialization is unavailable. Reads only the supplied small public result files.
"""
import argparse
import copy
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact_directory", type=Path)
    parser.add_argument("--validator-packages", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--expected-contexts", type=int, default=6)
    parser.add_argument("--expected-raw-records", type=int, default=5)
    parser.add_argument("--network-artifacts", action="store_true", help="Use the existing measured HTTP client's response filenames")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    sys.path.insert(0, str(root))
    if args.validator_packages:
        sys.path.insert(0, str(args.validator_packages))
    from scripts.evidence import acmg_evidence_v2_contract as e1
    from scripts.e3_p1a.validate_acmg_rule_registry_v2 import _validate_schema
    from jsonschema import Draft202012Validator, FormatChecker

    def read(path):
        assert path.stat().st_size < 20_000_000, "Bounded public artifact required"
        return json.loads(path.read_text(encoding="utf-8-sig"))

    def digest(value):
        return e1.canonical_sha256(value)

    profile_path = root / "varvis-server/src/main/resources/acmg/gnomad-population-input-profile-hnf1a-v1.json"
    profile = read(profile_path)
    profile_hash = digest(profile)
    java = (root / "varvis-server/src/main/java/org/nbscn/varvis/variantanalysis/provider/GnomadPopulationInputProfile.java").read_text(encoding="utf-8")
    assert re.search(r'HNF1A_PROFILE_SHA256 = "([a-f0-9]{64})"', java).group(1) == profile_hash
    registry_path = root / "varvis-server/src/main/resources" / profile["registryBinding"]["registryClasspathResource"].lstrip("/")
    assert hashlib.sha256(registry_path.read_bytes()).hexdigest() == profile["registryBinding"]["registrySha256"]
    _validate_schema(read(registry_path))
    schemas = e1.ContractSchemas()
    schema_documents = [read(p) for p in e1.SCHEMA_ROOT.glob("*.json")]

    def schema_check(value):
        schema_id = "urn:varvis:schema:" + value["schemaVersion"]
        schema = next(s for s in schema_documents if s.get("$id") == schema_id)
        Draft202012Validator(schema, registry=schemas.registry, format_checker=FormatChecker()).validate(value)

    directory = args.artifact_directory
    result = read(directory / "first-response.json")
    assert len(result["enabledCriteria"]) == len(set(result["enabledCriteria"])) == 9
    assert not result["executionProfile"]["automaticTrigger"]
    assert not result["executionProfile"]["classificationEnabled"]
    for filename in (("reconstructed-get-response.json" if args.network_artifacts else "reconstructed-get.json"), "repeat-response.json"):
        assert result == read(directory / filename), filename
    for filename in ("reconstructed-get-call-increment.json", "repeat-call-increment.json"):
        assert set(read(directory / filename).values()) == {0}, filename

    checks = {"contexts": 0, "atomicEvidence": 0, "evaluations": 0, "rawRecords": 0,
              "bs1Met": 0, "bs1NotMet": 0, "bs1NotEvaluated": 0}
    records = []
    for candidate in result["candidates"]:
        for attempt in candidate.get("attempts", []):
            frequency = attempt.get("hnf1aFrequency", {})
            if "evidence" not in frequency:
                continue
            context = frequency["context"]
            schema_check(context)
            assert context["contextId"] == "context-sha256-" + digest(e1.context_identity_projection(context))
            assert context["variant"]["alleleId"] == "allele-sha256-" + digest(e1.allele_identity_projection(context))
            assert context == attempt["context"] == attempt["pm2"]["context"]
            checks["contexts"] += 1
            evidence = frequency["evidence"]
            schema_check(evidence)
            assert evidence["evidenceId"] == "evidence-sha256-" + digest(e1.evidence_identity_projection(evidence))
            assert evidence["deduplicationKey"] == "dedup-sha256-" + digest(e1.evidence_deduplication_projection(evidence))
            assert evidence["contextId"] == context["contextId"]
            checks["atomicEvidence"] += 1
            binding = copy.deepcopy(frequency["observationIdentity"])
            observation_id = binding.pop("observationId")
            assert observation_id == "population-observation-sha256-" + digest(binding)
            assert binding["sourceEvidenceSha256"] == digest(attempt["pm2"]["evidence"])
            assert binding["executionSourceSha256"] == result["executionSourceSha256"]
            assert binding["dataClass"] == context["dataClass"]
            assert binding["variant"] == context["variant"]
            selection = copy.deepcopy(frequency["policySelection"])
            selection_id = selection.pop("selectionId")
            assert selection_id == "cspec-selection-sha256-" + digest(selection)
            assert selection["contextId"] == context["contextId"]
            assert selection["profileSha256"] == frequency["profileSha256"] == profile_hash
            assert selection["policySha256"] == digest(profile["diseaseFrequencyPolicy"])
            assert selection["registryBinding"] == profile["registryBinding"]
            allele = context["variant"]["evidenceQueryAllele"]
            values = {m["metricId"]: m["rawValue"] for m in evidence["measurements"]}
            record = {"position": allele["position"], "query": evidence["queryOutcome"]["status"]}
            if record["query"] == "HIT":
                raw = attempt["pm2"]["rawPopulationRecord"]
                assert binding["rawRecordSha256"] == hashlib.sha256(raw.encode()).hexdigest()
                fields = raw.split("\t")
                assert fields[0].removeprefix("chr") == allele["contig"]
                assert int(fields[1]) == allele["position"] and fields[3] == allele["reference"]
                alts = fields[4].split(","); assert alts.count(allele["alternate"]) == 1
                alt_index = alts.index(allele["alternate"])
                info = dict(part.split("=", 1) if "=" in part else (part, "true") for part in fields[7].split(";"))
                selected = lambda field: info[field].split(",")[alt_index]
                faf = selected("fafmax_faf95_max_joint")
                ancestry = selected("fafmax_faf95_max_gen_anc_joint")
                assert ancestry in {"afr", "amr", "eas", "mid", "nfe", "sas"}
                assert faf == values["gnomad.joint.faf95max"]
                assert Decimal(faf) == Decimal(selected("faf95_joint_" + ancestry))
                assert values["gnomad.joint.faf95max.ancestry"] == ancestry
                ac, an = selected("AC_joint_" + ancestry), selected("AN_joint_" + ancestry)
                assert 0 <= int(ac) <= int(an) and int(an) > 0
                assert values["gnomad.joint.ancestry." + ancestry + ".ac"] == ac
                assert values["gnomad.joint.ancestry." + ancestry + ".an"] == an
                assert Decimal(selected("stat_union_p_value")) >= Decimal("0.0001")
                assert fields[6] == "PASS"
                record.update(faf95=faf, fafAncestry=ancestry, ac=ac, an=an, afAncestry=selected("grpmax_joint"))
                checks["rawRecords"] += 1
            for evaluation in frequency["criterionEvaluations"]:
                schema_check(evaluation)
                assert evaluation["evaluationId"] == "evaluation-sha256-" + digest(e1.evaluation_identity_projection(evaluation))
                assert evaluation["evidenceIds"] == [evidence["evidenceId"]]
                assert evaluation["ruleBinding"]["cspecSelectionId"] == selection_id
                trace = evaluation["deterministicTrace"]
                if "traceSha256" in trace:
                    assert trace["traceSha256"] == digest(e1.deterministic_trace_projection(trace, include_hash=False))
                checks["evaluations"] += 1
                criterion = evaluation["criterionId"].split(":")[1]
                state = evaluation["evaluationResult"]["status"]
                record[criterion] = state
                if criterion == "BS1":
                    checks[{"MET": "bs1Met", "NOT_MET": "bs1NotMet", "NOT_EVALUATED": "bs1NotEvaluated"}[state]] += 1
                if record["query"] == "HIT":
                    expected = Decimal(record["faf95"]) >= Decimal("0.0001" if criterion == "BA1" else "0.000033")
                    assert state == ("MET" if expected else "NOT_MET")
                else:
                    assert not values and state == "NOT_EVALUATED"
                    assert evaluation["evaluationResult"]["reasonCode"] == "NO_RECORD_IS_NOT_FREQUENCY_ZERO"
            records.append(record)
    assert 0 < args.expected_contexts <= 100 and 0 <= args.expected_raw_records <= args.expected_contexts
    assert checks["contexts"] == args.expected_contexts and checks["rawRecords"] == args.expected_raw_records
    assert checks["evaluations"] == 2 * args.expected_contexts
    summary = {"status": "PASS", "validator": "existing independent E1 schemas and identity projections plus BS1 decimal/raw-record cross-check",
               "profileCanonicalSha256": profile_hash, "checks": checks, "records": records,
               "savedGetAndReconstructedGetAndRepeatEqual": True, "repeatNewCalls": 0}
    output = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(output, encoding="utf-8", newline="\n")
    print(output)


if __name__ == "__main__":
    main()
