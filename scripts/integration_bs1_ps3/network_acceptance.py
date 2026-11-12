"""One new joint calculation per restored, immutable public formal analysis.

No service lifecycle, database copying, upload, analysis creation or automatic retry.
The caller verifies an offline database and supplies a bounded source inventory.
Run evaluate, optional review, restart the same private instance, then run reuse.
"""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.ps1_pm5.network_acceptance import Client, joint_declaration, patient_context_id
from scripts.pvs1.frameshift_network_acceptance import raw_response, verify_reuse, require_pvs1_positives
from scripts.ps1_pm5.hnf1a_network_acceptance import require_comparison_controls

PROFILE = 'acmg-explicit-business-joint-v7'
RULES = {'ACMG_AMP_2015:' + c for c in ('PP3', 'BP4', 'BA1', 'BP7', 'PM2', 'PS1', 'PM5', 'PVS1', 'BS1', 'PS3')}
CONTEXT = [{'geneSymbol': 'HNF1A', 'hgncId': 'HGNC:11621', 'mondoId': 'MONDO:0015967', 'moi': 'AUTOSOMAL_DOMINANT'}]
CONTROLS = {
    'bs1': {(120978778, 'A', 'G'), (120978819, 'C', 'T'), (120978828, 'G', 'T'),
            (120988847, 'G', 'A'), (120989017, 'C', 'T'), (120993678, 'C', 'T')},
    'ps3': {(120988841, 'C', 'T'), (120999342, 'G', 'A'), (120989030, 'A', 'G')},
    'ps1-pm5': {(120978826, 'G', 'C'), (120993601, 'G', 'A'), (120993611, 'G', 'C')},
    'pvs1': {(120989017, 'C', 'T'), (120993577, 'CA', 'C'), (120993678, 'C', 'T'), (120994314, 'G', 'GC')},
}
FACT_SHA = 'a70b1e1640293bd43897a7a8d8517fbc1b3fdcb9e0b45ff6bb24113bf341a427'
FUNCTIONAL_PROFILE_SHA = 'aecb00a1f5f0c65cba3484e0d1dc4b62856fa6e7d66f7219eb1915bf4044970a'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def parsed(raw):
    return json.loads(raw, parse_float=Decimal)


def read(path):
    return parsed(Path(path).read_bytes().decode('utf-8-sig'))


def write(path, value):
    raw = json.dumps(value, ensure_ascii=False, indent=2).encode('utf-8') + b'\n'
    with Path(path).open('xb') as stream:
        stream.write(raw)
    return raw


def checked_file(entry):
    path = Path(entry['path'])
    raw = path.read_bytes()
    if sha(raw) != entry['sha256'] or len(raw) != entry['bytes']:
        raise ValueError('Source receipt changed: ' + str(path))
    return raw


def require_zero(event):
    if any(event['callIncrement'].values()):
        raise AssertionError('Read/reuse/review invoked a scientific runtime: ' + str(event['callIncrement']))


def validate_instance(identity, args, source):
    expected = {'sourceHead': args.head, 'branch': args.branch, 'instanceId': args.instance_id,
                'profileId': PROFILE, 'port': args.port, 'mode': 'JOINT',
                'transport': 'REAL_NETWORK_HTTP', 'database': 'PRIVATE_H2',
                'sourceDatabaseSha256': source['databaseSha256']}
    if any(identity.get(k) != v for k, v in expected.items()):
        raise ValueError('Task instance, source or restored database identity mismatch')
    if Path(identity['instanceRoot']).resolve() != args.acceptance_root.resolve() / 'network' / args.instance_id:
        raise ValueError('Instance does not belong to this private acceptance root')
    # Logical published paths are part of the immutable source identity: never resolve junctions.
    if Path(identity['readOnlyPublishedJobsRoot']) != Path(source['jobs']):
        raise ValueError('Original logical published jobs identity changed')
    working = Path(identity.get('businessWorkingRoot', '')).resolve()
    if (working != args.acceptance_root.resolve() / 'network' / args.instance_id / 'business-work'
            or working.is_relative_to(Path(source['jobs']).resolve())):
        raise ValueError('New scientific work and reviews must use the exact private instance business-work directory')
    profile = identity['executionProfile']
    if (profile.get('profileId') != PROFILE or set(profile.get('enabledCriteria', [])) != RULES
            or len(profile['enabledCriteria']) != 10 or profile.get('automaticTrigger') is not False
            or profile.get('classificationEnabled') is not False):
        raise ValueError('Exact explicit ten-rule successor required; BS3 must remain disabled')


def validate_local_manifest(manifest, args, source):
    expected = {'sourceHead': args.head, 'branch': args.branch, 'instanceId': args.instance_id,
                'port': args.port, 'mode': 'JOINT', 'sourceDatabaseSha256': source['databaseSha256']}
    if any(manifest.get(k) != v for k, v in expected.items()):
        raise ValueError('Local instance must belong to this committed task before creating client artifacts')


def formal_projection(row):
    return {k: row.get(k) for k in ('candidateId', 'geneSymbol', 'allele', 'candidateIdentity', 'transcriptAnnotations')}


def validate_formal(options, original, dataset, source):
    rows = options['candidates']
    if options.get('analysisId') != source['analysisId'] or len(rows) != 2 * len(CONTROLS[dataset]):
        raise ValueError('Unexpected original formal analysis or candidate count')
    if [formal_projection(r) for r in rows] != [formal_projection(r) for r in original['candidates']]:
        raise ValueError('Original formal order, allele, candidate identity or stored VEP changed')
    if len({r['candidateId'] for r in rows}) != len(rows):
        raise ValueError('Duplicate formal candidate identity')
    for moi in ('AD', 'AR'):
        selected = [r for r in rows if r['candidateIdentity']['modeOfInheritance'] == moi]
        actual = set()
        for row in selected:
            a = row['allele']
            if a.get('genomeAssembly') != 'GRCh38' or a['chromosome'] not in ('12', 'chr12') or row['geneSymbol'] != 'HNF1A':
                raise ValueError('Only the fixed native GRCh38 public HNF1A controls are permitted')
            actual.add((a['position'], a['reference'], a['alternate']))
        if len(selected) != len(CONTROLS[dataset]) or actual != CONTROLS[dataset]:
            raise ValueError('The fixed public panel must remain intact in each original MOI')
    if set(options.get('enabledCriteria', [])) != RULES or len(options['enabledCriteria']) != 10:
        raise ValueError('Input options did not expose the exact new ten criteria')


def declaration(options, dataset, source):
    request = joint_declaration(options, 'synthetic-proband', source['topN'], patient_context_id(source['analysisId']),
                                population_only=dataset == 'bs1', confirmed_contexts=CONTEXT)
    ad = {r['candidateId'] for r in options['candidates'] if r['candidateIdentity']['modeOfInheritance'] == 'AD'}
    selected = {r['candidateId'] for r in request['recommendations'] if r.get('stableRelationshipKey')}
    hypotheses = {h['hypothesis']['candidateId'] for h in request['hypotheses']}
    if selected != ad or hypotheses != (set() if dataset == 'bs1' else ad):
        raise ValueError('Only each original AD relationship may be explicitly confirmed')
    for h in request['hypotheses']:
        if (h['transcript']['accession'], h['transcript']['version']) != ('ENST00000257555', 11):
            raise ValueError('The original versioned HNF1A transcript is required')
    return request


def validate_budget(budget):
    if not isinstance(budget, dict):
        raise ValueError('Freeze the explicit per-panel call budget before evaluation')
    for key in ('provider', 'evaluator', 'vepComparator', 'spliceRawBatch', 'pm2AllSitesBatch'):
        if key not in budget or not isinstance(budget[key], int) or budget[key] < 0:
            raise ValueError('An explicit per-panel call budget is required: ' + key)
    if any(not isinstance(value, int) or isinstance(value, bool) or value < 0 for value in budget.values()):
        raise ValueError('Every call budget must be a nonnegative integer')


def check_budget(event, budget):
    validate_budget(budget)
    delta = event['callIncrement']
    for key in ('exomiser', 'vepPublication', 'blockedModelLaunch'):
        if delta.get(key) != 0:
            raise AssertionError('Original analysis/model publication must not rerun: ' + key)
    for key, maximum in budget.items():
        if key not in delta or delta[key] > maximum or delta[key] < 0:
            raise AssertionError('Recorded call budget exceeded or counter unavailable: ' + key)


def functional_values(run):
    return [a['functional'] for c in run['candidates'] for a in c['attempts'] if a.get('functional', {}).get('recordId')]


def check_functional(run):
    values = functional_values(run)
    if len(values) != 3 or {v['recordId'] for v in values} != {'P112L', 'Q175R', 'D526N'}:
        raise AssertionError('All three original public functional records are required')
    for value in values:
        evaluation = value['evaluation']
        if (value['profileSha256'] != FUNCTIONAL_PROFILE_SHA or value['factPackage']['version'] != '1.0.1'
                or len(value['atomicEvidence']) != 2 or value['atomicEvidence'][0]['source']['resourceSha256'] != FACT_SHA
                or value.get('classificationEnabled') is not False
                or not any(d['criterionId'] == 'ACMG_AMP_2015:BS3' for d in value['disabledCriteria'])
                or evaluation['review']['decision'] != 'NONE'):
            raise AssertionError('Frozen PS3 1.0.1 source/review/BS3 identity mismatch')
        expected = 'MET' if value['recordId'] == 'P112L' else 'NOT_EVALUATED'
        if evaluation['evaluationResult']['status'] != expected:
            raise AssertionError('PS3 scientific result changed: ' + value['recordId'])
        if expected == 'MET' and (evaluation['appliedStrength'] != 'MODERATE'
                                  or evaluation['evidenceIds'] != [value['atomicEvidence'][0]['evidenceId']]):
            raise AssertionError('Paired condition must not be independently added to strength')
    return values


def check_bs1(run, options):
    formal = {r['candidateId']: r for r in options['candidates']}
    observed = {}
    for candidate in run['candidates']:
        row = formal[candidate['candidateId']]
        if row['candidateIdentity']['modeOfInheritance'] != 'AD':
            continue
        position = row['allele']['position']
        pairs = [e for a in candidate['attempts'] for e in a.get('hnf1aFrequency', {}).get('criterionEvaluations', [])]
        by_code = {e['criterionId'].split(':')[-1]: e for e in pairs}
        if len(pairs) != 2 or set(by_code) != {'BA1', 'BS1'}:
            raise AssertionError('Each confirmed BS1 control requires independent disease BA1 and BS1 results')
        expected = 'NOT_EVALUATED' if position == 120989017 else 'MET' if position == 120988847 else 'NOT_MET'
        if by_code['BS1']['evaluationResult']['status'] != expected:
            raise AssertionError('Fixed FAF95 BS1 outcome changed')
        if expected == 'MET' and by_code['BS1']['appliedStrength'] != 'STRONG':
            raise AssertionError('BS1 positive strength changed')
        if by_code['BA1']['evaluationResult']['status'] != ('NOT_EVALUATED' if position == 120989017 else 'NOT_MET'):
            raise AssertionError('Disease BA1 must retain its separate threshold')
        observed[position] = expected
    if set(observed) != {p for p, _, _ in CONTROLS['bs1']}:
        raise AssertionError('The six fixed BS1 controls were not all evaluated')


def same_review_records(actual, expected):
    def keyed(records):
        return {r['reviewRecord']['reviewRecordId']: r for r in records}
    return len(actual) == len(expected) == len(keyed(actual)) and keyed(actual) == keyed(expected)


def validate_review_copies(source, working_root):
    records = source.get('functionalReviewFiles', [])
    if source.get('dataset') == 'ps3' and (len(records) != 8 or source.get('externalReviewTotal') != 8):
        raise ValueError('The two historical PS3 runs require all eight original external review snapshots')
    root = (Path(working_root) / 'functional-experiment-reviews').resolve()
    for entry in records:
        relative = Path(entry['relativePath'])
        target = (root / relative).resolve()
        if relative.is_absolute() or not target.is_relative_to(root) or '..' in relative.parts:
            raise ValueError('Historical review copy must remain inside the new private working directory')
        original = checked_file(entry)
        if target.read_bytes() != original:
            raise ValueError('Copied historical review differs from its exact source snapshot')


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.source = read(args.source_inventory)[args.dataset]
        if self.source.get('dataset') != args.dataset:
            raise ValueError('Source inventory dataset identity mismatch')
        validate_budget(self.source['maxCounters'])
        instance = args.acceptance_root.resolve() / 'network' / args.instance_id
        validate_local_manifest(read(instance / 'instance-manifest.json'), args, self.source)
        self.out = instance / args.attempt
        if args.phase == 'evaluate':
            self.out.mkdir(exist_ok=False)
        elif not self.out.is_dir():
            raise ValueError('Evaluate this exact private instance first')
        if (self.out / (args.phase + '-report.json')).exists():
            raise ValueError('This phase was already recorded; never overwrite or automatically retry')
        self.client = Client(f'http://127.0.0.1:{args.port}', self.out, args.timeout, joint=True, expected_port=args.port)
        self.identity, _, _ = self.call(args.phase + '-identity', '/__varvis_ps1pm5_instance', measured=False)
        validate_instance(self.identity, args, self.source)
        validate_review_copies(self.source, self.identity['businessWorkingRoot'])
        self.base = '/variant-analyses/' + self.source['analysisId'] + '/acmg'
        self.original = parsed(checked_file(self.source['formalOptions']))
        for old in self.source['historicalRuns']:
            checked_file(old['calculation'])
            if old.get('reviews'):
                checked_file(old['reviews'])
        if args.phase == 'evaluate':
            write(self.out / 'source-inventory.json', self.source)
        elif read(self.out / 'source-inventory.json') != self.source:
            raise ValueError('Source inventory or call budget changed after evaluation')

    def call(self, label, path, body=None, status=200, measured=True):
        marker = self.out / (label + '-event.json')
        if marker.exists():
            raise ValueError('Existing HTTP evidence; do not repeat this action: ' + label)
        before = self.client.call(label + '-calls-before', 'GET', '/__varvis_ps1pm5_calls') if measured else {}
        method = 'POST' if body is not None else 'GET'
        try:
            self.client.call(label, method, path, body)
        except RuntimeError:
            if not self.client.events or self.client.events[-1]['status'] != status:
                raise
        raw = raw_response(self.client)
        event = dict(self.client.events[-1])
        if event['status'] != status:
            raise AssertionError('Unexpected HTTP response: ' + str(event))
        value = parsed(raw)
        after = self.client.call(label + '-calls-after', 'GET', '/__varvis_ps1pm5_calls') if measured else {}
        if before.keys() != after.keys():
            raise AssertionError('Observation counter set changed during request')
        event.update(runId=value.get('runId') if isinstance(value, dict) else None,
                     callIncrement={k: after[k] - before[k] for k in before})
        with (self.out / (label + '-response.json')).open('xb') as stream:
            stream.write(raw)
        write(marker, event)
        return value, raw, event

    def historical(self, prefix):
        for old in self.source['historicalRuns']:
            suffix = '/runs/' + old['runId']
            value, _, event = self.call(prefix + '-history-' + old['runId'], self.base + suffix)
            require_zero(event)
            if value != parsed(checked_file(old['calculation'])):
                raise AssertionError('Historical calculation changed')
            if old.get('reviews'):
                export, _, event = self.call(prefix + '-reviews-' + old['runId'], self.base + suffix + '/functional-reviews/export')
                require_zero(event)
                saved = parsed(checked_file(old['reviews']))
                if (len(export['records']) != old['expectedExternalReviewCount']
                        or export['records'] != saved['records'] or export['calculation'] != value):
                    raise AssertionError('Historical review records or original calculation changed')

    def evaluate(self):
        self.historical('initial')
        options, raw, _ = self.call('input-options', self.base + '/input-options?topN=' + str(self.source['topN']))
        validate_formal(options, self.original, self.args.dataset, self.source)
        # Only server identity strings and explicit synthetic choices enter the request.
        request = declaration(json.loads(raw), self.args.dataset, self.source)
        request_raw = write(self.out / 'request.json', request)
        first, first_raw, event = self.call('first', self.base + '/runs', request_raw)
        old = parsed(checked_file(self.source['historicalRuns'][-1]['calculation']))
        if (first['runId'] in {o['runId'] for o in self.source['historicalRuns']}
                or first['requestSha256'] == old['requestSha256']
                or first['executionSourceSha256'] != old['executionSourceSha256']
                or first['candidateIds'] != [r['candidateId'] for r in options['candidates']]
                or first['executionProfile'] != self.identity['executionProfile']
                or first.get('candidateOrderingChanged') is not False
                or first.get('automaticTrigger') is not False):
            raise AssertionError('New profile did not create an isolated run from the unchanged formal source')
        if any(b.get('reasonCode') == 'EXECUTION_SNAPSHOT_CANDIDATE_CHANGED' for c in first['candidates']
               for a in c['attempts'] for b in a.get('blockers', [])):
            raise AssertionError('Restored candidate snapshot mismatch; retain failure and stop')
        check_budget(event, self.source['maxCounters'])
        if event['callIncrement']['provider'] != first['providerCalls'] or event['callIncrement']['evaluator'] != first['evaluatorCalls']:
            raise AssertionError('Raw response and measured provider/evaluator counters disagree')
        if self.args.dataset == 'ps3':
            check_functional(first)
        elif self.args.dataset == 'ps1-pm5':
            require_comparison_controls(first, options)
        elif self.args.dataset == 'pvs1':
            require_pvs1_positives(first, options)
        elif self.args.dataset == 'bs1':
            check_bs1(first, options)
        self.saved_reuse('saved', first_raw, request_raw)
        report = {'status': 'PASS', 'transport': 'REAL_NETWORK_HTTP', 'pageCovered': False,
                  'dataset': self.args.dataset, 'processId': self.identity['processId'],
                  'runId': first['runId'], 'analysisId': first['analysisId'], 'formalRows': len(options['candidates']),
                  'firstCallIncrement': event['callIncrement'], 'responseSha256': sha(first_raw)}
        write(self.out / 'evaluate-report.json', report)

    def saved_reuse(self, label, first_raw, request_raw):
        first = parsed(first_raw)
        _, get_raw, get_event = self.call(label + '-get', self.base + '/runs/' + first['runId'])
        _, repeat_raw, repeat_event = self.call(label + '-repeat', self.base + '/runs', request_raw)
        verify_reuse(first_raw, get_raw, repeat_raw, get_event, repeat_event)

    def review(self):
        if self.args.dataset != 'ps3':
            raise ValueError('Only the fixed PS3 source permits this explicit synthetic review exercise')
        run = read(self.out / 'first-response.json')
        values = check_functional(run)
        value = next(v for v in values if v['recordId'] == 'P112L')
        suffix = self.base + '/runs/' + run['runId'] + '/functional-reviews'
        initial, _, event = self.call('review-initial', suffix)
        require_zero(event)
        if initial['records']:
            raise AssertionError('New joint run must start with no external review events')
        request = {'evaluationId': value['evaluation']['evaluationId'], 'factPackageSha256': FACT_SHA,
                   'profileSha256': FUNCTIONAL_PROFILE_SHA, 'previousReviewRecordId': None,
                   'actorId': 'reviewer-synthetic-bs1-ps3-main-integration', 'role': 'SYSTEM_ADMINISTRATOR'}
        chain = []
        for decision in ('NONE', 'ACCEPTED', 'REJECTED'):
            request.update(decision=decision, rationale='Public synthetic engineering acceptance: ' + decision + '. Not a user or expert clinical decision.')
            body = write(self.out / ('review-' + decision.lower() + '-request.json'), request)
            record, _, event = self.call('review-' + decision.lower(), suffix, body)
            require_zero(event)
            previous = chain[-1]['reviewRecord']['reviewRecordId'] if chain else value['evaluation']['review']['reviewRecordIds'][0]
            if (record['reviewRecord']['decision'] != decision or record['reviewRecord']['previousReviewRecordId'] != previous
                    or record['actorIdentityAssurance'] != 'CALLER_DECLARED_NOT_INDEPENDENTLY_AUTHENTICATED'
                    or record['clinicalConclusionClaimed'] is not False):
                raise AssertionError('Synthetic review identity/chain changed')
            repeated, _, event = self.call('review-' + decision.lower() + '-identical', suffix, body)
            require_zero(event)
            if repeated != record:
                raise AssertionError('Identical review request must be idempotent')
            chain.append(record)
            request['previousReviewRecordId'] = record['reviewRecord']['reviewRecordId']
        stale = dict(request, previousReviewRecordId=chain[0]['reviewRecord']['reviewRecordId'])
        self.rejected_review('review-stale', suffix, stale)
        # A failed append must leave a readable chain before any following request.
        safe, _, event = self.call('review-after-stale', suffix)
        require_zero(event)
        if not same_review_records(safe['records'], chain):
            raise AssertionError('Rejected stale append changed the review chain')
        for held in (v for v in values if v['recordId'] != 'P112L'):
            negative = dict(request, evaluationId=held['evaluation']['evaluationId'], decision='ACCEPTED', previousReviewRecordId=None)
            self.rejected_review('review-held-' + held['recordId'], suffix, negative)
        export, _, event = self.call('review-final-export', suffix + '/export')
        require_zero(event)
        if not same_review_records(export['records'], chain) or export['calculation'] != run:
            raise AssertionError('Review changed original calculation or added an unexpected event')
        self.saved_reuse('review-saved', (self.out / 'first-response.json').read_bytes(), (self.out / 'request.json').read_bytes())
        self.historical('after-review')
        write(self.out / 'review-report.json', {'status': 'PASS', 'newEvents': 3, 'identicalRetries': 3,
              'stalePredecessorRejected': True, 'safeGetAfterStale': True, 'heldAcceptsRejected': 2,
              'actorId': request['actorId'], 'clinicalConclusionClaimed': False})

    def rejected_review(self, label, suffix, request):
        failure, _, event = self.call(label, suffix, write(self.out / (label + '-request.json'), request), status=409)
        require_zero(event)
        if failure.get('status') != 409 or failure.get('error') != 'Conflict':
            raise AssertionError('Expected the existing structured conflict response')

    def reuse(self):
        initial = read(self.out / 'evaluate-identity-response.json')
        if self.identity['processId'] == initial['processId']:
            raise AssertionError('A distinct task JVM is required')
        for key in ('sourceHead', 'configurationSha256', 'executionProfile', 'instanceId', 'readOnlyPublishedJobsRoot',
                    'sourceDatabaseSha256', 'businessWorkingRoot'):
            if self.identity[key] != initial[key]:
                raise AssertionError('Restart changed the saved instance identity: ' + key)
        self.saved_reuse('rebuilt', (self.out / 'first-response.json').read_bytes(), (self.out / 'request.json').read_bytes())
        if self.args.dataset == 'ps3':
            expected = read(self.out / 'review-final-export-response.json')
            actual, _, event = self.call('rebuilt-review-export', self.base + '/runs/' + expected['calculation']['runId'] + '/functional-reviews/export')
            require_zero(event)
            if actual != expected:
                raise AssertionError('Review export did not survive task JVM reconstruction')
        self.historical('rebuilt')
        write(self.out / 'reuse-report.json', {'status': 'PASS', 'previousPid': initial['processId'],
              'processId': self.identity['processId'], 'savedCalculationBytesEqual': True,
              'newScientificCalls': 0, 'historicalRunsUnchanged': True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('evaluate', 'review', 'reuse'))
    parser.add_argument('dataset', choices=tuple(CONTROLS))
    parser.add_argument('--source-inventory', type=Path, required=True)
    parser.add_argument('--acceptance-root', type=Path, required=True)
    parser.add_argument('--instance-id', required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('--branch', default='feature/bs1-ps3-main-integration-20260911')
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--attempt', default='integration-01')
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    if not re.fullmatch(r'instance-[a-f0-9-]{36}', args.instance_id) or not re.fullmatch(r'[a-f0-9]{40}', args.head):
        parser.error('Exact instance UUID and full committed source SHA are required')
    if not re.fullmatch(r'integration-[a-z0-9-]+', args.attempt):
        parser.error('A simple dedicated attempt directory is required')
    acceptance = Acceptance(args)
    getattr(acceptance, args.phase)()
    print(json.dumps({'status': 'PASS', 'phase': args.phase, 'dataset': args.dataset, 'output': str(acceptance.out)}))


if __name__ == '__main__':
    main()
