"""Read-only verification of saved PM2 evidence, identities and formal candidate order.

Uses the existing frozen E1 schemas and canonical identity functions. Engineering
input and resource observations do not establish clinical validation.
"""
import argparse
import copy
import json
from collections import Counter
from pathlib import Path
import sys


def verify(directory):
    import acmg_evidence_v2_contract as e1
    from verify_business_bundles import verify as verify_bundles

    def read(name):
        return json.loads((directory / name).read_text(encoding="utf-8-sig"))

    saved = read("first-response.json")
    original = copy.deepcopy(saved)
    formal = read("formal-top20.json")
    assert saved == read("saved-get.json") == read("repeat-post.json")
    assert read("first-calls.json") == read("result.json")["calls"]
    assert len(formal) == len(saved["candidates"]) == 20
    schemas = e1.ContractSchemas()
    resources = Path(__file__).resolve().parents[2] / "varvis-server/src/main/resources/acmg"
    profile = e1.load_json(resources / "gnomad-population-input-profile-v2-pm2-multigene.json")
    profile_hash = e1.canonical_sha256(profile)
    expected_snapshot = profile["evaluation"]["criteria"][2]["populationRule"]["policySnapshot"]
    pack = e1.load_json(resources / "pm2-cspec-reviewed-sources-20260909.json")
    assert e1.canonical_sha256(pack) == expected_snapshot["sourcePackSha256"]
    source_hashes = {e1.canonical_sha256(s) for s in pack["sources"]}
    assert e1.canonical_sha256({k: v for k, v in expected_snapshot.items() if k != "sha256"}) == expected_snapshot["sha256"]
    rows, counts = [], Counter()
    context_count = evidence_count = evaluation_count = 0
    for index, (candidate, actual) in enumerate(zip(formal, saved["candidates"]), 1):
        assert actual["candidateId"] == candidate["candidateId"]
        gene = candidate["exomiserCandidateFeatures"]["geneSymbol"]
        canonical = candidate["canonicalVariantId"]
        for attempt in actual["attempts"]:
            pm2 = attempt["pm2"]
            evaluation = pm2.get("evaluation", pm2)
            outcome = evaluation["evaluationResult"]
            counts[outcome["status"]] += 1
            row = {"rank": index, "candidateId": candidate["candidateId"], "allele": canonical,
                   "gene": gene, "status": outcome["status"], "reason": outcome.get("reasonCode"),
                   "blockers": attempt.get("blockers", []), "policy": pm2.get("policy", {}).get("policyId")}
            rows.append(row)
            if "context" not in pm2:
                continue
            context = pm2["context"]
            schemas.validate(context, "urn:varvis:schema:evidence-context-v2")
            assert context["variant"]["alleleId"] == e1.content_id("allele-sha256-", e1.allele_identity_projection(context))
            assert context["contextId"] == e1.content_id("context-sha256-", e1.context_identity_projection(context))
            allele = context["variant"]["evidenceQueryAllele"]
            assert "|".join(str(allele[k]) for k in ("assembly", "contig", "position", "reference", "alternate")) == canonical
            context_count += 1
            assert pm2["profileSha256"] == profile_hash
            assert pm2["policySnapshot"] == expected_snapshot
            atomic = ([pm2["evidence"]] if "evidence" in pm2 else []) + pm2.get("allSitesEvidence", [])
            for evidence in atomic:
                schemas.validate(evidence, "urn:varvis:schema:atomic-evidence-v2")
                digest = e1.canonical_sha256(e1.evidence_identity_projection(evidence))
                assert evidence["evidenceId"] == "evidence-sha256-" + digest
                assert evidence["provenance"]["identityProjectionSha256"] == digest
                assert evidence["deduplicationKey"] == e1.content_id("dedup-sha256-", e1.evidence_deduplication_projection(evidence))
                assert evidence["contextId"] == context["contextId"]
                evidence_count += 1
            if "evidence" in pm2:
                population = pm2["evidence"]
                row["populationQuery"] = population["queryOutcome"]["status"]
                row["measurements"] = population["measurements"]
                raw = pm2.get("rawPopulationRecord")
                if raw:
                    fields = raw.split("\t")
                    assert (fields[0].removeprefix("chr"), int(fields[1]), fields[3], fields[4]) == (allele["contig"].removeprefix("chr"), allele["position"], allele["reference"], allele["alternate"])
                if row["populationQuery"] == "NO_RECORD":
                    assert not raw and not population["measurements"]
            if "evaluation" not in pm2:
                continue
            selected = pm2["policySelection"]
            assert selected["selectionId"] == e1.content_id("cspec-selection-sha256-", {k: v for k, v in selected.items() if k != "selectionId"})
            policy = pm2["policy"]
            assert policy in expected_snapshot["records"]
            assert policy["authority"]["snapshotSha256"] in source_hashes
            assert policy["gene"]["hgncId"] == context["gene"]["hgncId"]
            assert policy["disease"]["mondoId"] == context["disease"]["mondoId"] and policy["moi"] == context["moi"]
            schemas.validate(evaluation, "urn:varvis:schema:criterion-evaluation-v2")
            assert evaluation["evaluationId"] == e1.content_id("evaluation-sha256-", e1.evaluation_identity_projection(evaluation))
            assert set(evaluation["evidenceIds"]) == {e["evidenceId"] for e in atomic}
            trace = evaluation["deterministicTrace"]
            if trace["executionStatus"] == "COMPLETED":
                assert trace["traceSha256"] == e1.canonical_sha256(e1.deterministic_trace_projection(trace, include_hash=False))
            row["trace"] = trace
            row["source"] = policy["authority"]["sourceUrl"]
            evaluation_count += 1
    assert saved == original
    genes = sorted({r["gene"] for r in rows})
    assert "G6PC3" in genes and len(genes) >= 4
    assert len({r["allele"] for r in rows if r["status"] in {"MET", "NOT_MET"} and r["gene"] != "VHL"}) >= 3
    return {"status": "VERIFIED", "dataClass": "SYNTHETIC", "clinicalValidation": False,
            "formalCandidates": len(formal), "independentAlleles": len({r["allele"] for r in rows}),
            "genes": genes, "states": dict(counts), "contexts": context_count,
            "atomicEvidence": evidence_count, "evaluations": evaluation_count,
            "originalBundles": verify_bundles(directory / "first-response.json"),
            "savedAndRepeatedResponsesIdentical": True, "newRepeatCalls": 0, "rows": rows}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--python-packages", type=Path, help="Optional existing isolated schema dependencies")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.python_packages:
        sys.path.insert(0, str(args.python_packages))
    root = Path(__file__).resolve().parents[1]
    sys.path[:0] = [str(root / "evidence"), str(root / "e4_b3")]
    report = verify(args.directory)
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in report.items() if k != "rows"}, ensure_ascii=False))
