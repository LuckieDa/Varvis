"""Re-evaluate two separately restored public analyses through the production HTTP harness.

No upload, analysis creation, snapshot rewriting, model execution or server lifecycle.
Run evaluate, restart the same isolated JVM, then run reuse with the same directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.ps1_pm5.network_acceptance import Client, joint_declaration, patient_context_id, save
from scripts.pvs1.frameshift_network_acceptance import measured, verify_reuse

RULES = {'ACMG_AMP_2015:' + code for code in ('PP3', 'BP4', 'BA1', 'BP7', 'PM2', 'PS1', 'PM5', 'PVS1', 'BS1')}
CONTEXT = [{'geneSymbol': 'HNF1A', 'hgncId': 'HGNC:11621', 'mondoId': 'MONDO:0015967', 'moi': 'AUTOSOMAL_DOMINANT'}]
MODEL_COUNTERS = ('exomiser', 'vepPublication', 'vepComparator', 'spliceRawBatch', 'blockedModelLaunch')


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('evaluate', 'reuse'))
    parser.add_argument('dataset', choices=('bs1', 'pvs1'))
    parser.add_argument('--source-inventory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    source = read(args.source_inventory)[args.dataset]
    client = Client('http://127.0.0.1:18227', args.output, 600, joint=True)
    identity = client.call(args.phase + '-identity', 'GET', '/__varvis_ps1pm5_instance')
    assert identity['sourceHead'] == args.head
    assert identity['branch'] == 'feature/bs1-pvs1-integration-candidate'
    assert identity['profileId'] == 'acmg-explicit-business-joint-v4'
    assert identity['sourceDatabaseSha256'] == source['databaseSha256']
    assert Path(identity['readOnlyPublishedJobsRoot']) == Path(source['jobs'])
    assert set(identity['executionProfile']['enabledCriteria']) == RULES
    assert len(identity['executionProfile']['enabledCriteria']) == len(RULES)
    assert identity['executionProfile']['automaticTrigger'] is False
    assert identity['executionProfile']['classificationEnabled'] is False
    save(args.output / (args.phase + '-identity.json'), identity)
    base = '/variant-analyses/' + source['analysisId']
    if args.phase == 'evaluate':
        assert not (args.output / 'first-response.json').exists(), 'Never overwrite a previous attempt'
        old, _, old_event = measured(client, 'historical-get', 'GET', base + '/acmg/runs/' + source['oldRunId'])
        assert not any(old_event['callIncrement'].values())
        if source.get('oldResponse'):
            assert old == read(Path(source['oldResponse']))
        options = client.call('input-options', 'GET', base + '/acmg/input-options?topN=' + str(source['topN']))
        save(args.output / 'input-options.json', options)
        assert len(options['candidates']) == source['formalRows']
        request = joint_declaration(options, 'synthetic-proband', source['topN'], patient_context_id(source['analysisId']),
                                    population_only=args.dataset == 'bs1', confirmed_contexts=CONTEXT)
        selected = {r['candidateId'] for r in request['recommendations'] if r.get('stableRelationshipKey')}
        ad = {r['candidateId'] for r in options['candidates'] if r['candidateIdentity']['modeOfInheritance'] == 'AD'}
        assert selected == ad and len(ad) == source['formalRows'] // 2
        assert len(request['hypotheses']) == (0 if args.dataset == 'bs1' else 4)
        save(args.output / 'request.json', request)
        first, raw, event = measured(client, 'first', 'POST', base + '/acmg/runs', request)
        assert first['runId'] != old['runId'] and first['requestSha256'] != old['requestSha256']
        assert first['executionSourceSha256'] == old['executionSourceSha256']
        assert all(blocker.get('reasonCode') != 'EXECUTION_SNAPSHOT_CANDIDATE_CHANGED'
                   for row in first['candidates'] for attempt in row.get('attempts', []) for blocker in attempt.get('blockers', [])), 'Restored formal candidates must match the immutable source snapshot'
        assert first['candidateIds'] == [r['candidateId'] for r in options['candidates']]
        assert first['executionProfile'] == identity['executionProfile']
        assert event['callIncrement']['provider'] == first['providerCalls']
        assert event['callIncrement']['evaluator'] == first['evaluatorCalls']
        assert all(event['callIncrement'][key] == 0 for key in MODEL_COUNTERS)
        _, get_raw, get_event = measured(client, 'saved-get', 'GET', base + '/acmg/runs/' + first['runId'])
        _, repeat_raw, repeat_event = measured(client, 'repeat', 'POST', base + '/acmg/runs', request)
        verify_reuse(raw, get_raw, repeat_raw, get_event, repeat_event)
        save(args.output / 'repeat-call-increment.json', repeat_event['callIncrement'])
        report = dict(status='PASS', phase='evaluate', dataset=args.dataset, processId=identity['processId'],
                      analysisId=source['analysisId'], oldRunId=old['runId'], runId=first['runId'],
                      requestSha256=first['requestSha256'], responseSha256=hashlib.sha256(raw).hexdigest(),
                      firstCallIncrement=event['callIncrement'], formalRows=len(options['candidates']), confirmedAd=len(ad))
    else:
        initial = read(args.output / 'evaluate-identity.json')
        assert identity['processId'] != initial['processId'], 'A new JVM PID is required'
        for key in ('sourceHead', 'configurationSha256', 'executionProfile', 'instanceId', 'readOnlyPublishedJobsRoot'):
            assert identity[key] == initial[key], 'Restart identity changed: ' + key
        raw = (args.output / 'first-response.json').read_bytes()
        first, request = json.loads(raw), read(args.output / 'request.json')
        _, get_raw, get_event = measured(client, 'reconstructed-get', 'GET', base + '/acmg/runs/' + first['runId'])
        _, repeat_raw, repeat_event = measured(client, 'rebuilt-repeat', 'POST', base + '/acmg/runs', request)
        verify_reuse(raw, get_raw, repeat_raw, get_event, repeat_event)
        save(args.output / 'reconstructed-get-call-increment.json', get_event['callIncrement'])
        report = dict(status='PASS', phase='reuse', previousPid=initial['processId'], processId=identity['processId'],
                      runId=first['runId'], getCallIncrement=get_event['callIncrement'], repeatCallIncrement=repeat_event['callIncrement'])
    save(args.output / (args.phase + '-report.json'), report)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
