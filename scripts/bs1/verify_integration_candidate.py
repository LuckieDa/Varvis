"""Independent frozen E1 identities, successor lineage and original PVS1 sequence proofs."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'varvis-server/src/main/resources/acmg'
sys.path.insert(0, str(ROOT))


def read(path):
    assert path.stat().st_size < 20_000_000
    return json.loads(path.read_text(encoding='utf-8-sig'))


def verify_joint(document):
    from scripts.evidence import acmg_evidence_v2_contract as e1
    expected = read(RES / 'acmg-business-execution-profile-joint-v4.json')
    previous = read(RES / 'acmg-business-execution-profile-joint-v3.json')
    frequency = read(RES / 'acmg-business-execution-profile-bs1-hnf1a-v1.json')
    assert expected['slices'] == previous['slices'] + [frequency['slices'][-1]]
    assert expected['enabledCriteria'] == previous['enabledCriteria'] + ['ACMG_AMP_2015:BS1']
    assert expected['predecessorProfileId'] == previous['profileId']
    assert expected['integratedProfileIds'] == [previous['profileId'], frequency['profileId']]
    for predecessor in expected['lineage']:
        raw = (RES / predecessor['resource']).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == predecessor['sourceByteSha256']
        assert json.loads(raw)['profileId'] == predecessor['profileId']
    for key, value in expected.items():
        assert document.get(key) == value, 'Execution profile field mismatch: ' + key
    assert document['manifestSha256'] == e1.canonical_sha256({k: v for k, v in document.items() if k != 'manifestSha256'})
    missense = next(s for s in expected['slices'] if 'ACMG_AMP_2015:PS1' in s['criterionIds'])
    assert e1.canonical_sha256(document['missenseScientificPolicy']) == missense['profileCanonicalSha256']


def verify(directory, dataset, formal_file=None):
    from scripts.evidence import acmg_evidence_v2_contract as e1
    from scripts.pvs1.verify_hnf1a_frameshift_business import verify_envelope, verify_profile
    saved = read(directory / 'first-response.json')
    verify_joint(saved['executionProfile'])
    raw = (directory / 'first-response.json').read_bytes()
    for label in ('saved-get', 'repeat', 'reconstructed-get', 'rebuilt-repeat'):
        assert (directory / (label + '-response.json')).read_bytes() == raw
        assert not any(read(directory / (label + '-event.json'))['callIncrement'].values())
    schemas = e1.ContractSchemas()
    projections = {
        'evidence-context-v2': ('contextId', 'context', e1.context_identity_projection),
        'atomic-evidence-v2': ('evidenceId', 'evidence', e1.evidence_identity_projection),
        'criterion-evaluation-v2': ('evaluationId', 'evaluation', e1.evaluation_identity_projection),
    }
    counts = {kind: set() for kind in projections}
    def walk(value):
        if isinstance(value, dict):
            kind = value.get('schemaVersion')
            if kind in projections:
                schemas.validate(value, 'urn:varvis:schema:' + kind)
                field, prefix, projection = projections[kind]
                assert value[field] == prefix + '-sha256-' + e1.canonical_sha256(projection(value))
                counts[kind].add(value[field])
                if kind == 'evidence-context-v2':
                    assert value['variant']['alleleId'] == 'allele-sha256-' + e1.canonical_sha256(e1.allele_identity_projection(value))
                if kind == 'atomic-evidence-v2':
                    assert value['deduplicationKey'] == 'dedup-sha256-' + e1.canonical_sha256(e1.evidence_deduplication_projection(value))
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)
    walk(saved)
    ba1_checked = 0
    population = {e1.canonical_sha256(a['pm2']['evidence']): a['pm2']
                  for c in saved['candidates'] for a in c.get('attempts', []) if 'evidence' in a.get('pm2', {})}
    for candidate in saved['candidates']:
        ba1 = candidate.get('ba1', {})
        if 'evidence' not in ba1:
            continue
        carrier, evaluation = ba1['evidence'], ba1['evaluation']
        assert ba1['evidenceId'] == 'population-evidence-sha256-' + e1.canonical_sha256(carrier)
        assert evaluation['schemaVersion'] == 'ba1-allele-evaluation-v1'
        assert evaluation['scope'] == 'CANDIDATE_ALLELE_GENERAL_POPULATION_EVIDENCE'
        assert evaluation['alleleId'] == ba1['alleleId']
        assert evaluation['evidenceIds'] == [ba1['evidenceId']]
        assert evaluation['evaluationId'] == 'ba1-allele-evaluation-sha256-' + e1.canonical_sha256(
            {k: v for k, v in evaluation.items() if k not in ('evaluationId', 'conflict', 'review')})
        if 'observationIdentity' in ba1:
            binding = copy.deepcopy(ba1['observationIdentity'])
            assert binding.pop('observationId') == 'population-observation-sha256-' + e1.canonical_sha256(binding)
            source = population[binding['sourceEvidenceSha256']]
            assert binding['executionSourceSha256'] == saved['executionSourceSha256']
            assert binding['variant'] == source['context']['variant']
            assert carrier['queryAllele'] == binding['variant']['evidenceQueryAllele']
            if binding.get('rawRecordSha256'):
                assert binding['rawRecordSha256'] == hashlib.sha256(source['rawPopulationRecord'].encode()).hexdigest()
        if carrier['queryOutcome']['status'] == 'NO_RECORD':
            assert not carrier['measurements'] and evaluation['evaluationResult']['status'] == 'NOT_EVALUATED'
        ba1_checked += 1
    assert len(saved['candidates']) == (12 if dataset == 'bs1' else 8)
    positives = []
    if dataset == 'pvs1':
        assert formal_file is not None
        assert (formal_file.parent / 'first-post-response.json').read_bytes() == (directory / 'historical-get-response.json').read_bytes()
        formal = {row['candidateId']: row for row in read(formal_file)}
        profile, model = verify_profile()
        for candidate in saved['candidates']:
            for attempt in candidate['attempts']:
                envelope = attempt.get('pvs1', {})
                if envelope.get('evaluation', {}).get('evaluationResult', {}).get('status') == 'MET':
                    positives.append(verify_envelope(envelope, formal[candidate['candidateId']], profile=profile, model=model, schemas=schemas))
                if len(formal[candidate['candidateId']]['observation']['key']['reference']) != 1 or len(formal[candidate['candidateId']]['observation']['key']['alternate']) != 1:
                    assert all(e['evaluationResult']['status'] == 'NOT_EVALUATED' for e in attempt['hnf1aFrequency']['criterionEvaluations'])
        assert {(p['allele']['position'], p['allele']['reference'], p['allele']['alternate']) for p in positives} == {
            (120989017, 'C', 'T'), (120993678, 'C', 'T'), (120993577, 'CA', 'C'), (120994314, 'G', 'GC')}
        assert len(positives) == 4
    # Actual saved-manifest corruptions must be rejected independently of its recomputed outer hash.
    negative = 0
    for change in ('old-pvs1', 'missing-bs1', 'wrong-lineage', 'altered-registry'):
        broken = copy.deepcopy(saved['executionProfile'])
        if change == 'old-pvs1':
            next(s for s in broken['slices'] if s['criterionIds'] == ['ACMG_AMP_2015:PVS1'])['profileVersion'] = '2.0.0'
        elif change == 'missing-bs1':
            broken['enabledCriteria'].remove('ACMG_AMP_2015:BS1')
        elif change == 'wrong-lineage':
            broken['lineage'][0]['sourceByteSha256'] = '0' * 64
        else:
            broken['slices'][-1]['registryBinding']['registrySha256'] = '0' * 64
        broken['manifestSha256'] = e1.canonical_sha256({k: v for k, v in broken.items() if k != 'manifestSha256'})
        try:
            verify_joint(broken)
        except AssertionError:
            negative += 1
        else:
            raise AssertionError('Corrupt successor accepted: ' + change)
    return dict(status='PASS', dataset=dataset, uniqueSchemaObjects={k: len(v) for k, v in counts.items()},
                ba1AlleleIdentitiesChecked=ba1_checked, pvs1SequenceProofs=positives,
                corruptedManifestsRejected=negative, originalResponseUnmodified=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('dataset', choices=('bs1', 'pvs1'))
    parser.add_argument('--validator-packages', type=Path, required=True)
    parser.add_argument('--formal-file', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sys.path.insert(0, str(args.validator_packages))
    result = verify(args.directory, args.dataset, args.formal_file)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))
