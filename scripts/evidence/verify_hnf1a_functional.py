"""Read-only verification of the restricted HNF1A functional slice and saved Java artifacts."""
import argparse
import copy
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
if os.environ.get('VARVIS_CONTRACT_PYTHON_DEPS'):
    sys.path.insert(0, os.environ['VARVIS_CONTRACT_PYTHON_DEPS'])
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'scripts/evidence'))
import acmg_evidence_v2_contract as e1
from scripts.e3_p1a.validate_acmg_rule_registry_v2 import _validate_schema

R = ROOT / 'varvis-server/src/main/resources/acmg'
A = R / 'authority-catalog-functional-hnf1a-2026-09-10.json'
G = R / 'rule-registry-v2/acmg-rule-registry-functional-hnf1a-2026-09-10.json'
O = R / 'cspec-overlay-registry-v1/cspec-overlay-registry-functional-hnf1a-2026-09-10.json'

def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)

def verify(paths):
    schemas = e1.ContractSchemas()
    authority, rules, overlays = map(e1.load_json, (A, G, O))
    e1.validate_authority_catalog(authority, schemas)
    _validate_schema(rules)
    identity = ('3.1.0', 'CSpec:GN017@3.1.0', '467903868', '_ka7YRPu---',
                'Zenodo:21421568/GN017_3.1.0.json', '6195d0bf2f4cd7e86016ff13cdd78ad2696168b224fafc60f10cfc2409bc0ec1')
    e1.validate_cspec_registry(overlays, authority, rules, schemas, authority_path=A, rule_registry_path=G,
                              successor_alias_identities={('GN017','3.1.0'):{identity}})
    profile = e1.load_json(R / 'hnf1a-functional-business-profile-v1.json')
    facts = e1.load_json(R / 'hnf1a-functional-facts-v1.json')
    assert profile['factPackage']['canonicalSha256'] == e1.canonical_sha256(facts)
    assert profile['registryBinding']['registrySha256'] == e1.sha256_file(G)
    assert profile['authorityBinding']['sha256'] == e1.sha256_file(A)
    assert profile['overlayBinding']['sha256'] == e1.sha256_file(O)
    assert profile['enabledCriteria'] == ['ACMG_AMP_2015:PS3']
    assert profile['policy']['ps3Oxford'] == {'operator':'LTE','threshold':40,'strength':'MODERATE'}
    assert profile['policy']['ps3General']['operator'] == 'LT'
    assert profile['policy']['bs3']['operator'] == 'GTE' and not profile['policy']['bs3']['enabled']
    assert {r['recordId'] for r in facts['records']} == {'P112L','Q175R','D526N'}
    assert [r['recordId'] for r in facts['records'] if r['gates']['ps3']=='PASS'] == ['P112L']
    bundles = set()
    reviews = set()
    for path in paths:
        saved = e1.load_json(Path(path))
        count = 0
        for value in walk(saved):
            if value.get('schemaVersion') == 'evidence-bundle-v2' and value.get('producer',{}).get('producerId') == 'varvis-hnf1a-functional-business-entry':
                assert e1.rehash_bundle(copy.deepcopy(value)) == value, 'JAVA_IDENTITY_MISMATCH'
                e1.validate_bundle(value, authority, rules, overlays, schemas, authority_path=A,rule_registry_path=G,cspec_registry_path=O)
                assert len(value['atomicEvidence']) == 2
                assert value['combination']['executionStatus'] == 'NOT_RUN'
                assert {e['criterionId'] for e in value['criterionEvaluations']} == {'ACMG_AMP_2015:PS3'}
                assert all(e['review']['decision']=='NONE' for e in value['criterionEvaluations'])
                bundles.add(value['bundleId']); count += 1
            if value.get('schemaVersion') == 'review-record-v2':
                schemas.validate(value, 'urn:varvis:schema:review-record-v2')
                assert value['reviewRecordId'] == 'review-sha256-'+e1.canonical_sha256(e1.review_identity_projection(value))
                reviews.add(value['reviewRecordId'])
        assert count or any(v.get('schemaVersion')=='review-record-v2' for v in walk(saved)), 'NO_FUNCTIONAL_ARTIFACTS'
    return {'scientificResources':'PASS','functionalBundles':len(bundles),'reviewRecords':len(reviews),'javaNodesUnchanged':True}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('saved', nargs='*')
    print(json.dumps(verify(parser.parse_args().saved)))
