"""Read-only validation of PS1/PM5 scientific artifacts and saved evidence-v2 bundles."""
import argparse
import copy
from decimal import Decimal
import json
import hashlib
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
if os.environ.get("VARVIS_CONTRACT_PYTHON_DEPS"):
    sys.path.insert(0, os.environ["VARVIS_CONTRACT_PYTHON_DEPS"])
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts/evidence"))
import acmg_evidence_v2_contract as e1
from scripts.e3_p1a.validate_acmg_rule_registry_v2 import _validate_schema

RESOURCES = ROOT / "varvis-server/src/main/resources/acmg"
AUTHORITY = RESOURCES / "authority-catalog-ps1-pm5-2026-09-09.json"
RULES = RESOURCES / "rule-registry-v2/acmg-rule-registry-ps1-pm5-2026-09-09.json"
OVERLAY = RESOURCES / "cspec-overlay-registry-v1/cspec-overlay-registry-ps1-pm5-2026-09-09.json"
PROFILE_RESOURCES = {
    "1.0.0": ("clinvar-missense-business-profile-v1.json", "clinvar-missense-braf-reference-audit-v1.json"),
    "2.0.0": ("clinvar-missense-business-profile-v2.json", "clinvar-missense-braf-reference-audit-v2.json"),
}
AMINO_ACIDS = {"Phe": "F", "Leu": "L", "Val": "V", "Gln": "Q", "Thr": "T", "Lys": "K", "Arg": "R"}


def scientific_resources(version):
    assert version in PROFILE_RESOURCES, "UNREGISTERED_SCIENTIFIC_PROFILE_VERSION"
    profile_file, audit_file = PROFILE_RESOURCES[version]
    policy = e1.load_json(RESOURCES / profile_file)
    audit = e1.load_json(RESOURCES / audit_file)
    assert policy["profileVersion"] == version, "REGISTERED_PROFILE_VERSION_MISMATCH"
    assert policy["referencePolicy"]["resource"] == "/acmg/" + audit_file, "REFERENCE_AUDIT_RESOURCE_MISMATCH"
    assert policy["referencePolicy"]["auditSha256"] == e1.sha256_file(RESOURCES / audit_file), "REFERENCE_AUDIT_FILE_HASH_MISMATCH"
    proof_path = RESOURCES / "spliceai-braf-mane-window-equivalence-v1.json"
    assert policy["splicePolicy"]["annotationEquivalenceSha256"] == e1.sha256_file(proof_path), "ANNOTATION_EQUIVALENCE_FILE_HASH_MISMATCH"
    policy["referenceAuditSha256"] = e1.canonical_sha256(audit)
    policy["annotationEquivalence"] = e1.load_json(proof_path)
    if version == "2.0.0":
        rna_file = "braf-missense-rna-review-v1.json"
        assert policy["rnaReviewPolicy"]["snapshotResource"] == "/acmg/" + rna_file, "RNA_REVIEW_RESOURCE_MISMATCH"
        assert policy["rnaReviewPolicy"]["snapshotSha256"] == e1.sha256_file(RESOURCES / rna_file), "RNA_REVIEW_FILE_HASH_MISMATCH"
        policy["rnaEvidenceReview"] = e1.load_json(RESOURCES / rna_file)
    return policy, audit


def verify_resources():
    schemas = e1.ContractSchemas()
    authority, rules, overlay = map(e1.load_json, (AUTHORITY, RULES, OVERLAY))
    e1.validate_authority_catalog(authority, schemas)
    _validate_schema(rules)
    old_rules = e1.load_json(RESOURCES / "rule-registry-v2/acmg-rule-registry-e3-k1b-2026-09-04.json")
    assert rules["criteria"] == old_rules["criteria"], "INHERITED_RULES_CHANGED"
    assert {key for key in rules if rules[key] != old_rules[key]} == {
        "registryId", "authorityCatalogId", "authorityCatalogSha256", "authorityCutoff", "asOf"}
    e1.validate_cspec_registry(overlay, authority, rules, schemas,
                              authority_path=AUTHORITY, rule_registry_path=RULES,
                              successor_alias_identities={('GN049', '2.3.0'): {(
                                  '2.3.0', 'CSpec:GN049@2.3.0', '643243119', '_i2nUog----',
                                  'Zenodo:21433906/GN049_2.3.0.json',
                                  'c4d6bd2dab599a4b2e66a4704dc1238ca873dfc9be0e3a9f5f9944d7b7133aa4')}})
    for version in PROFILE_RESOURCES:
        policy, audit = scientific_resources(version)
        assert policy["registryBinding"]["registrySha256"] == e1.sha256_file(RULES)
        assert policy["scientificArtifactBinding"]["overlaySha256"] == e1.sha256_file(OVERLAY)
        for reference in audit["references"]:
            assert not any(code.startswith(("PS1", "PM5")) for code in reference["appliedCriteria"])
            resource = RESOURCES / Path(reference["erepoSnapshotResource"]).name
            assert e1.sha256_file(resource) == reference["erepoSnapshotSha256"], "REFERENCE_SOURCE_SNAPSHOT_HASH_MISMATCH"
        for relation in audit["pm5PhysicochemicalReview"]["reviewedRelations"]:
            resource = RESOURCES / Path(relation["snapshotResource"]).name
            assert e1.sha256_file(resource) == relation["snapshotSha256"], "RELATIONSHIP_SNAPSHOT_HASH_MISMATCH"
        for relation in policy["pm5RelationshipPolicy"]["relationships"]:
            reviewed = [item for item in audit["pm5PhysicochemicalReview"]["reviewedRelations"]
                        if all(relation[policy_key] == AMINO_ACIDS.get(item[audit_key], item[audit_key])
                               for policy_key, audit_key in (("referenceAminoAcid", "referenceAminoAcid"),
                                                             ("targetAlternate", "targetAlternateAminoAcid"),
                                                             ("comparatorAlternate", "comparatorAlternateAminoAcid")))]
            assert len(reviewed) == 1 and reviewed[0]["reviewOutcome"] == "CONCORDANT", "POLICY_RELATIONSHIP_NOT_AUDITED"
            assert relation["snapshotSha256"] == reviewed[0]["snapshotSha256"], "POLICY_RELATIONSHIP_SNAPSHOT_MISMATCH"
        if version == "2.0.0":
            rna = policy["rnaEvidenceReview"]
            assert rna["legacyReferenceScvs"] == ["SCV000927045.1", "SCV000927041.1"], "RNA_LEGACY_EXCEPTION_DRIFT"
            assert "SCV001424734.1" in rna["requiredReferenceScvs"], "RNA_NEW_REFERENCE_REVIEW_REQUIRED"
            for review in rna["reviews"]:
                for artifact in review["sourceArtifacts"]:
                    assert len(artifact["sha256"]) == 64 and all(c in "0123456789abcdef" for c in artifact["sha256"]), "RNA_SOURCE_HASH_MALFORMED"
                    assert artifact["sourceUrl"] in review["sourceUrls"], "RNA_SOURCE_URL_NOT_DECLARED"
                    if artifact.get("storage") == "CLASSPATH":
                        resource = RESOURCES / Path(artifact["classpathResource"]).name
                        assert e1.sha256_file(resource) == artifact["sha256"], "RNA_SOURCE_SNAPSHOT_HASH_MISMATCH"
    return schemas, authority, rules, overlay


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def normalized_audit(audit):
    audit = copy.deepcopy(audit)
    for ref in audit["references"]:
        ref.update(scv=ref["scvAccession"], vcv=ref["vcvAccessionAtReview"], geneSymbol="BRAF",
                   mondoId=ref["erepoDiseaseMondoId"], transcriptId="ENST00000646891.2", proteinChange=ref["proteinHgvs"],
                   independentEvidenceStatus="VERIFIED" if ref["independentPathogenicClassification"] else "UNVERIFIED",
                   independentCriteriaSufficient=ref["independentPathogenicClassification"],
                   independentCriteria=copy.deepcopy(ref["appliedCriteria"]), independentEvidenceReason=ref["independenceBasis"],
                   allele={"assembly": ref["assembly"], "contig": ref["chromosome"], "position": ref["position"],
                           "reference": ref["referenceAllele"], "alternate": ref["alternateAllele"]})
        for field in ("referenceAminoAcid", "alternateAminoAcid"):
            ref[field] = AMINO_ACIDS.get(ref[field], ref[field])
    return audit


def verify_raw_query_identity(value):
    """Rebuild exact runner inputs; this is an identity replay, not an archived request.json claim."""
    transcript = value["context"]["transcript"]
    transcript_id = transcript["accession"] + "." + str(transcript["version"])
    queries = {"TARGET": value["context"]["variant"]["evidenceQueryAllele"]}
    for reference in value["references"]:
        queries.setdefault(reference["scv"], reference["allele"])
    variants = [{"queryId": query_id,
                 "allele": {key: allele[key] for key in ("assembly", "contig", "position", "reference", "alternate")},
                 "geneSymbol": "BRAF", "selectedTranscriptId": transcript_id} for query_id, allele in queries.items()]
    def pretty(node, level=0, newline="\r\n"):
        if isinstance(node, dict):
            return "{" + ",".join(newline + "  " * (level + 1) + json.dumps(key) + " : " + pretty(item, level + 1, newline)
                                  for key, item in node.items()) + newline + "  " * level + "}"
        if isinstance(node, list):
            return "[ " + ", ".join(pretty(item, level, newline) for item in node) + " ]"
        return json.dumps(node, ensure_ascii=False, separators=(",", ":"))
    renderings = {"JACKSON_COMPACT": json.dumps(variants, ensure_ascii=False, separators=(",", ":")),
                  "JACKSON_DEFAULT_PRETTY_CRLF": pretty(variants),
                  "JACKSON_DEFAULT_PRETTY_LF": pretty(variants, newline="\n")}
    expected = {"spliceai-batch-sha256-" + hashlib.sha256(text.encode("utf-8")).hexdigest(): style for style, text in renderings.items()}
    request_id = value["rawSpliceBatch"]["requestId"]
    assert request_id in expected, "RAW_REQUEST_INPUT_IDENTITY_MISMATCH"
    assert {row["queryId"] for row in value["rawSpliceBatch"]["results"]} == set(queries), "RAW_QUERY_SET_MISMATCH"
    return expected[request_id]


def verify_rna_comparison(policy, ref, target, fields):
    """Check literature-review coverage independently of the low prediction score."""
    if policy["profileVersion"] == "1.0.0":
        return
    rna = policy["rnaEvidenceReview"]
    for concern in rna["knownConcerns"]:
        if concern["allele"] in (target, ref["allele"]):
            assert concern["reviewStatus"] == "RESOLVED_NO_COMPARISON_CONFLICT", "MET_WITH_UNRESOLVED_RNA_CONCERN"
    if ref["scv"] in rna["legacyReferenceScvs"]:
        return
    assert all(rna["scope"][key] == policy["applicability"][key]
               for key in ("assembly", "geneSymbol", "hgncId", "mondoId", "moi", "transcript", "protein")), "RNA_REVIEW_SCOPE_MISMATCH"
    relation = {"referenceAminoAcid": fields["vep.target.referenceAminoAcid"],
                "targetAlternate": fields["vep.target.alternateAminoAcid"],
                "comparatorAlternate": ref["alternateAminoAcid"]}
    reviews = [review for review in rna["reviews"] if review["referenceScv"] == ref["scv"]
               and review["aminoAcidRelation"] == relation]
    assert len(reviews) == 1, "RNA_REVIEW_COMPARISON_MISSING_OR_AMBIGUOUS"
    review = reviews[0]
    assert review["status"] == "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED", "RNA_REVIEW_NOT_REVIEWED"
    assert review["rnaEffect"] == "UNKNOWN" and review["normalRnaClaimed"] is False, "RNA_REVIEW_CANNOT_CLAIM_NORMAL_RNA"
    assert review["sourceUrls"] and review["sourceArtifacts"], "RNA_REVIEW_SOURCES_MISSING"
    return review


def verify_scientific_slice(value):
    """The saved business slice is mandatory: a comparator-only bundle cannot attest to raw splice inputs."""
    policy, audit = scientific_resources(value["policy"].get("profileVersion"))
    proof = policy["annotationEquivalence"]
    assert value["policy"] == policy, "SAVED_SCIENTIFIC_POLICY_DRIFT"
    normalized = normalized_audit(audit)
    refs = {ref["scv"]: ref for ref in normalized["references"]}
    for displayed in value.get("references", []):
        expected = refs.get(displayed["scv"])
        assert expected is not None, "UNREVIEWED_REFERENCE_IN_SAVED_RESULT"
        assert {key: displayed[key] for key in expected} == expected, "SAVED_REFERENCE_AUDIT_DRIFT"
        if "rnaReview" in displayed:
            assert displayed["rnaReview"] in policy.get("rnaEvidenceReview", {}).get("reviews", []), "SAVED_RNA_DISPLAY_DRIFT"
    for displayed in value.get("rnaReviewEvidence", []):
        rna = policy.get("rnaEvidenceReview", {})
        assert displayed in rna.get("reviews", []) + rna.get("knownConcerns", []), "SAVED_RNA_EVIDENCE_DRIFT"
    decisions = value["scientificDecisions"]
    for evaluation in value["criterionEvaluations"]:
        parameters = {item["name"]: item["value"] for item in evaluation["deterministicTrace"]["parameters"]}
        assert parameters["business.profile.sha256"] == e1.canonical_sha256(policy), "PROFILE_TRACE_HASH_MISMATCH"
        assert parameters["business.science.sha256"] == e1.canonical_sha256(decisions), "SCIENTIFIC_TRACE_HASH_MISMATCH"
    raw = value.get("rawSpliceBatch")
    if raw is None:
        assert all(evaluation["evaluationResult"]["status"] != "MET" for evaluation in value["criterionEvaluations"])
        return
    verify_raw_query_identity(value)
    if "rawSpliceBatchSha256" in decisions:
        assert decisions["rawSpliceBatchSha256"] == e1.canonical_sha256(raw), "RAW_SPLICE_BATCH_HASH_MISMATCH"
        assert decisions["referenceAuditSha256"] == e1.canonical_sha256(normalized), "REFERENCE_AUDIT_TRACE_HASH_MISMATCH"
    rows = {row["queryId"]: row for row in raw["results"]}
    assert len(rows) == len(raw["results"]), "DUPLICATED_RAW_QUERY"
    target = value["context"]["variant"]["evidenceQueryAllele"]
    assert rows["TARGET"]["allele"] == target, "RAW_TARGET_IDENTITY_MISMATCH"
    maximum = {}
    for evidence in value["spliceEvidence"]:
        row = rows[evidence["queryId"]]
        assert evidence["allele"] == row["allele"], "SPLICE_DISPLAY_ALLELE_MISMATCH"
        if evidence["status"] != "AVAILABLE":
            continue
        assert row["status"] == "HIT" and row["exactAlleleMatch"] is True
        assert len(row["annotations"]) == 1 and row["annotations"][0]["geneSymbol"] == "BRAF"
        scores = row["annotations"][0]["deltaScores"]
        values = [Decimal(str(scores["DS_" + suffix])) for suffix in ("AG", "AL", "DG", "DL")]
        assert all(score.is_finite() and 0 <= score <= 1 for score in values)
        maximum[evidence["queryId"]] = max(values)
        assert Decimal(str(evidence["maximumScore"])) == max(values), "SPLICE_SCORE_DISPLAY_MISMATCH"
        assert proof["inferencePolicy"]["minimumUnpaddedPositionInclusive"] <= row["allele"]["position"] <= proof["inferencePolicy"]["maximumUnpaddedPositionInclusive"]
    atomics = {atomic["evidenceId"]: atomic for atomic in value["atomicEvidence"]}
    for evaluation in value["criterionEvaluations"]:
        if evaluation["evaluationResult"]["status"] != "MET":
            continue
        assert "rawSpliceBatchSha256" in decisions and "referenceAuditSha256" in decisions
        rule = evaluation["criterionId"].split(":")[-1]
        assert evaluation["baseStrength"] == {"PS1": "STRONG", "PM5": "MODERATE"}[rule]
        positive_reference_ids = decisions[rule]["evidenceIds"]
        assert set(evaluation["evidenceIds"]) == set(atomics), "COMPARATOR_SCREENING_INPUTS_NOT_FULLY_DECLARED"
        assert positive_reference_ids and set(positive_reference_ids).issubset(evaluation["evidenceIds"]), "MET_WITHOUT_AUDITED_REFERENCE_SUBSET"
        for evidence_id in positive_reference_ids:
            atomic = atomics[evidence_id]
            fields = {measurement["metricId"]: str(measurement["rawValue"]) for measurement in atomic["measurements"]}
            ref = refs[fields["clinvar.assertion.scv"]]
            assert ref["independentPathogenicClassification"] is True
            assert ref["mondoId"] == value["context"]["disease"]["mondoId"]
            assert all(fields["clinvar.comparator." + key] == str(ref["allele"][key]) for key in ref["allele"])
            assert rows[ref["scv"]]["allele"] == ref["allele"] and ref["allele"] != target
            assert maximum["TARGET"] <= Decimal("0.1") and maximum[ref["scv"]] <= Decimal("0.1")
            reviewed = verify_rna_comparison(policy, ref, target, fields)
            if reviewed is not None:
                assert any(displayed.get("rnaReview") == reviewed for displayed in value["references"]
                           if displayed["scv"] == ref["scv"]), "MET_RNA_REVIEW_NOT_DISPLAYED"
            expected_relation = "SAME_AMINO_ACID_CHANGE" if rule == "PS1" else "SAME_RESIDUE_DIFFERENT_AMINO_ACID"
            assert fields["vep.proteinRelation"] == expected_relation
            assert fields["vep.target.proteinPosition"] == fields["vep.comparator.proteinPosition"] == str(ref["proteinPosition"])
            assert fields["vep.target.referenceAminoAcid"] == fields["vep.comparator.referenceAminoAcid"] == ref["referenceAminoAcid"]
            assert fields["vep.target.transcriptId"] == fields["vep.comparator.transcriptId"] == policy["applicability"]["transcript"]
            assert fields["vep.comparator.alternateAminoAcid"] == ref["alternateAminoAcid"]
            if rule == "PS1":
                assert fields["vep.target.alternateAminoAcid"] == ref["alternateAminoAcid"]
            else:
                triple = {"referenceAminoAcid": fields["vep.target.referenceAminoAcid"], "targetAlternate": fields["vep.target.alternateAminoAcid"], "comparatorAlternate": ref["alternateAminoAcid"]}
                assert any(all(review[key] == item for key, item in triple.items()) for review in policy["pm5RelationshipPolicy"]["relationships"])


def verify(path=None):
    schemas, authority, rules, overlay = verify_resources()
    count = 0
    if path:
        saved = e1.load_json(Path(path))
        seen = set()
        scientific_bundles = set()
        for item in walk(saved):
            if "scientificDecisions" in item and "criterionEvaluations" in item:
                verify_scientific_slice(item)
                scientific_bundles.add(item["bundle"]["bundleId"])
        for bundle in walk(saved):
            if bundle.get("schemaVersion") != "evidence-bundle-v2" or bundle.get("authorityBinding", {}).get("ruleRegistryId") != rules["registryId"]:
                continue
            if bundle["bundleId"] in seen:
                continue
            seen.add(bundle["bundleId"])
            assert bundle["bundleId"] in scientific_bundles, "COMPLETE_SCIENTIFIC_INPUTS_REQUIRED_WITH_BUNDLE"
            assert e1.rehash_bundle(copy.deepcopy(bundle)) == bundle, "ORIGINAL_JAVA_IDENTITY_MISMATCH"
            e1.validate_bundle(bundle, authority, rules, overlay, schemas,
                               authority_path=AUTHORITY, rule_registry_path=RULES, cspec_registry_path=OVERLAY)
            assert {evaluation["criterionId"] for evaluation in bundle["criterionEvaluations"]} == {"ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5"}
            assert not all(evaluation["evaluationResult"]["status"] == "MET" for evaluation in bundle["criterionEvaluations"])
            count += 1
        assert count, "NO_PS1_PM5_SAVED_BUNDLE"
    return {"scientificResources": "PASS", "missenseBundles": count, "javaNodesUnchanged": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("saved", nargs="?")
    print(json.dumps(verify(parser.parse_args().saved)))
