"""Bounded, phased HTTP acceptance on the retained public PS3 analysis.

Requires the task's baseline-manifest.json and identity.json in a new output directory.
No upload/model API is used. Existing HTTP artifacts are never overwritten. Stop on
failure; do not rerun a phase to invent a new identity or clear prior evidence.
"""
import argparse
import copy
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

FACT = 'a70b1e1640293bd43897a7a8d8517fbc1b3fdcb9e0b45ff6bb24113bf341a427'
PROFILE = 'aecb00a1f5f0c65cba3484e0d1dc4b62856fa6e7d66f7219eb1915bf4044970a'


def parsed(data):
    return json.loads(data, parse_float=Decimal)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def functions(run):
    return [a['functional'] for c in run['candidates'] for a in c['attempts']
            if a.get('functional', {}).get('recordId')]


def counts(runs):
    return {'runs': len(runs), 'provider': sum(r.get('providerCalls', 0) for r in runs),
            'evaluator': sum(r.get('evaluatorCalls', 0) for r in runs),
            'functionalProvider': sum(r.get('functionalCalls', {}).get('provider', 0) for r in runs),
            'functionalEvaluator': sum(r.get('functionalCalls', {}).get('evaluator', 0) for r in runs)}


class Acceptance:
    def __init__(self, args):
        self.out = Path(args.output).resolve(strict=True)
        self.task = Path(args.task_cache).resolve(strict=True)
        assert self.out.is_relative_to(self.task)
        self.baseline = self.read('baseline-manifest')
        self.analysis = self.baseline['analysisId']
        self.old = self.baseline['runId']
        self.base = args.base.rstrip('/') + '/variant-analyses/' + self.analysis + '/acmg'
        assert urllib.parse.urlparse(self.base).hostname == '127.0.0.1'
        self.verify_files()

    def read(self, label):
        return parsed((self.out / (label + '.json')).read_bytes().decode('utf-8-sig'))

    def write(self, label, value):
        body = json.dumps(value, ensure_ascii=False, indent=2).encode('utf-8') + b'\n'
        with (self.out / (label + '.json')).open('xb') as stream:
            stream.write(body)
        return body

    def call(self, label, suffix, data=None, status=200):
        target = self.out / (label + '.json')
        assert not target.exists(), 'Existing HTTP artifact; inspect it instead of rerunning this phase'
        # Preserve and re-send the same request bytes, including on JVM reconstruction.
        if data is not None:
            with (self.out / (label + '.request.json')).open('xb') as stream:
                stream.write(data)
        req = urllib.request.Request(self.base + suffix, data=data, headers={'Content-Type': 'application/json'})
        started = datetime.now(timezone.utc).isoformat()
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                body, actual = response.read(), response.status
        except urllib.error.HTTPError as failure:
            body, actual = failure.read(), failure.code
        with target.open('xb') as stream:
            stream.write(body)
        event = {'label': label, 'startedAt': started, 'finishedAt': datetime.now(timezone.utc).isoformat(),
                 'method': req.get_method(), 'path': suffix, 'status': actual, 'sha256': sha(body),
                 'requestSha256': None if data is None else sha(data)}
        with (self.out / 'http-events.jsonl').open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(event) + '\n')
        assert actual == status, event
        return parsed(body)

    def verify_files(self):
        for entry in self.baseline['files']:
            assert sha((self.task / entry['relativePath']).read_bytes()) == entry['sha256'], entry
        for entry in self.read('source-manifest'):
            assert sha(Path(entry['path']).read_bytes()) == entry['sha256'], entry

    def check_run(self, run):
        old = self.read('prepare-old-calculation')
        assert run['analysisId'] == self.analysis and run['runId'] != self.old
        assert run['candidateIds'] == old['candidateIds'] and run['selectedCount'] == 6
        assert run['executionSourceSha256'] == old['executionSourceSha256']
        assert run['candidateOrderingChanged'] is False and run['automaticTrigger'] is False
        assert run['executionProfile']['manifestVersion'] == '1.3.1'
        assert run['providerCalls'] == run['evaluatorCalls'] == 6
        assert run['functionalCalls'] == {'provider': 3, 'evaluator': 3, 'wetExperiments': 0, 'externalModels': 0}
        values = functions(run)
        assert {v['recordId'] for v in values} == {'P112L', 'Q175R', 'D526N'}
        old_ids = {v['evaluation']['evaluationId'] for v in functions(old)}
        for value in values:
            assert value['profileSha256'] == PROFILE
            assert value['atomicEvidence'][0]['source']['resourceSha256'] == FACT
            facts_path = Path(__file__).resolve().parents[2] / 'varvis-server/src/main/resources/acmg/hnf1a-functional-facts-v1.json'
            assert value['factPackage'] == parsed(facts_path.read_bytes()) and value['factPackage']['version'] == '1.0.1'
            assert value['classificationEnabled'] is False
            assert any(d['criterionId'] == 'ACMG_AMP_2015:BS3' for d in value['disabledCriteria'])
            assert len(value['atomicEvidence']) == 2
            assert value['evaluation']['evaluationId'] not in old_ids
            expected = 'MET' if value['recordId'] == 'P112L' else 'NOT_EVALUATED'
            assert value['evaluation']['evaluationResult']['status'] == expected
            assert value['evaluation']['review']['decision'] == 'NONE'
            if value['recordId'] == 'P112L':
                assert value['evaluation']['appliedStrength'] == 'MODERATE'
                assert value['evaluation']['evidenceIds'] == [value['atomicEvidence'][0]['evidenceId']]
                parameters = {p['name']: p['value'] for p in value['evaluation']['deterministicTrace']['parameters']}
                assert parameters['factPackageSha256'] == FACT and parameters['profileSha256'] == PROFILE
                assert parameters['pairedConditionUse'] == 'REVIEW_BOTH_COUNT_HELA_ONLY_NO_STRENGTH_ADDITION'
        for candidate in run['candidates']:
            if candidate['candidateId'] not in {'exomiser-v1-00000001', 'exomiser-v1-00000002', 'exomiser-v1-00000003'}:
                assert candidate['attempts']
                for attempt in candidate['attempts']:
                    held = attempt['functional']
                    assert 'evaluation' not in held
                    assert held['evaluationResult'] == {'status': 'NOT_EVALUATED', 'reasonCode': 'FUNCTIONAL_VERIFIED_CONTEXT_MISSING'}
        return values

    def evaluate(self):
        archived = {Path(r['relativePath']).name: self.task / r['relativePath'] for r in self.baseline['files']}
        assert self.read('prepare-old-calculation') == parsed(archived['evaluation-post.json'].read_bytes())
        assert self.read('prepare-old-export')['records'] == parsed(archived['review-export.json'].read_bytes())['records']
        before = self.call('evaluate-runs-before', '/runs')
        previous = [self.call('historical-' + r['runId'], '/runs/' + r['runId']) for r in before]
        options = self.call('evaluate-input-options', '/input-options?topN=20')
        assert options['executionSourceRecovered'] and options['runtimeConfigured'] and not options['blockers']
        assert options['candidateIds'] == self.read('prepare-old-calculation')['candidateIds']
        current = [r for r in previous if any(v.get('profileSha256') == PROFILE for v in functions(r))]
        if current:
            for run in current:
                self.check_run(run)
            self.write('existing-current-runs', {'runIds': [r['runId'] for r in current]})
            raise RuntimeError('Current-identity run exists: inspect and reuse it; no new evaluation was sent')
        old_request = next(self.task / r['relativePath'] for r in self.baseline['files']
                           if Path(r['relativePath']).name == 'evaluation-request.json')
        declaration = json.loads(old_request.read_text('utf-8'))
        declaration.pop('retryOf', None)
        for choice in declaration['recommendations']:
            row = next(c for c in options['candidates'] if c['candidateId'] == choice['candidateId'])
            recommendation = row['recommendation']
            assert recommendation['functionalProfileSha256'] == PROFILE
            for key in ('recommendationId', 'generatedAt', 'policyVersion', 'policyHash'):
                choice[key] = recommendation[key]
            if choice.get('annotationId'):
                tx = recommendation['resourceCompatibleTranscript']
                assert tx['versionedTranscriptId'] == 'ENST00000257555.11'
                assert tx['predictionPath'] == 'HNF1A_FUNCTIONAL_CONTEXT' and not tx['predictionCompatible']
                assert any(r['stableRelationshipKey'] == choice['stableRelationshipKey'] and r['selectable']
                           and r['moi'] == 'AD' and r['mondoId'] == 'MONDO:0015967' for r in recommendation['relationships'])
                choice['annotationId'] = tx['annotationId']
                hypothesis = next(h for h in declaration['hypotheses'] if h['hypothesis']['candidateId'] == choice['candidateId'])
                assert hypothesis['hypothesis']['hypothesisId'] == recommendation['hypothesisId']
                hypothesis['transcript']['selectionPolicy'] = recommendation['policyId'] + '@' + recommendation['policyVersion']
                hypothesis['transcript']['source']['recordLocator'] = 'recommendations/' + recommendation['recommendationId'] + '/selection'
        request = self.write('evaluation-request', declaration)
        self.write('evaluation-plan', {'before': counts(previous), 'expectedNewRuns': 1, 'expectedProviderEvaluator': [6, 6],
                                      'expectedFunctional': [3, 3], 'expectedLocalAm': [3, 3], 'newExomiserVep': [0, 0],
                                      'retryOf': None, 'automaticRetry': False})
        run = self.call('evaluation-post', '/runs', request)
        values = self.check_run(run)
        after = self.call('evaluate-runs-after', '/runs')
        assert len(after) == len(before) + 1
        assert {r['runId'] for r in after} - {r['runId'] for r in before} == {run['runId']}
        suffix = '/runs/' + run['runId']
        assert self.call('initial-calculation-get', suffix) == run
        initial = self.call('initial-reviews', suffix + '/functional-reviews')
        export = self.call('initial-export', suffix + '/functional-reviews/export')
        assert initial['records'] == export['records'] == [] and export['calculation'] == run
        self.write('evaluation-summary', {'runId': run['runId'], 'status': run['status'], 'before': counts(previous),
                                        'after': counts(previous + [run]), 'initialHumanReviewCount': 0,
                                        'results': {v['recordId']: v['evaluation']['evaluationResult']['status'] for v in values}})

    def review(self):
        run = self.read('evaluation-post')
        values = self.check_run(run)
        value = next(v for v in values if v['recordId'] == 'P112L')
        suffix = '/runs/' + run['runId'] + '/functional-reviews'
        assert not self.call('review-start', suffix)['records']
        request = {'evaluationId': value['evaluation']['evaluationId'], 'factPackageSha256': FACT,
                   'profileSha256': PROFILE, 'previousReviewRecordId': None, 'decision': 'NONE',
                   'actorId': 'reviewer-synthetic-functional-v101', 'role': 'SYSTEM_ADMINISTRATOR',
                   'rationale': 'Public synthetic data engineering acceptance: pending materials. Not a user/expert clinical decision.'}
        chain = []
        for decision in ('NONE', 'ACCEPTED', 'REJECTED'):
            request['decision'] = decision
            request['rationale'] = 'Public synthetic data engineering acceptance: ' + decision + '. Not a user/expert clinical decision.'
            data = self.write('review-' + decision.lower() + '-input', request)
            record = self.call('review-' + decision.lower(), suffix, data)
            assert record['reviewRecord']['decision'] == decision
            assert record['actorIdentityAssurance'] == 'CALLER_DECLARED_NOT_INDEPENDENTLY_AUTHENTICATED'
            assert record['clinicalConclusionClaimed'] is False
            expected_previous = chain[-1]['reviewRecord']['reviewRecordId'] if chain else value['evaluation']['review']['reviewRecordIds'][0]
            assert record['reviewRecord']['previousReviewRecordId'] == expected_previous
            assert self.call('review-' + decision.lower() + '-identical', suffix, data) == record
            chain.append(record)
            request['previousReviewRecordId'] = record['reviewRecord']['reviewRecordId']
        self.review_checks()

    def review_checks(self):
        # Resume checks on the actual preserved chain, never recreate an empty state.
        run = self.read('evaluation-post')
        values = self.check_run(run)
        suffix = '/runs/' + run['runId'] + '/functional-reviews'
        chain = [self.read('review-' + decision) for decision in ('none', 'accepted', 'rejected')]
        current = self.call('review-checks-start', suffix)
        assert {r['reviewRecord']['reviewRecordId'] for r in current['records']} == {r['reviewRecord']['reviewRecordId'] for r in chain}
        request = self.read('review-rejected-input')
        stale = copy.deepcopy(request)
        stale['previousReviewRecordId'] = chain[0]['reviewRecord']['reviewRecordId']
        if (self.out / 'review-stale-predecessor.json').exists():
            assert self.read('review-stale-input') == stale
            failure = self.read('review-stale-predecessor')
        else:
            failure = self.call('review-stale-predecessor', suffix, self.write('review-stale-input', stale), status=409)
        # Spring's existing error envelope exposes HTTP status/error/path, not the
        # internal ResponseStatusException reason. Preserve that API contract.
        assert failure['status'] == 409 and failure['error'] == 'Conflict'
        for held in (v for v in values if v['recordId'] != 'P112L'):
            negative = dict(request, evaluationId=held['evaluation']['evaluationId'], decision='ACCEPTED', previousReviewRecordId=None)
            failure = self.call('review-held-' + held['recordId'], suffix, self.write('review-held-' + held['recordId'] + '-input', negative), status=409)
            assert failure['status'] == 409 and failure['error'] == 'Conflict'
        export = self.call('final-export', suffix + '/export')
        assert export['calculation'] == run and len(export['records']) == 3
        assert {r['reviewRecord']['reviewRecordId'] for r in export['records']} == {r['reviewRecord']['reviewRecordId'] for r in chain}
        self.write('review-summary', {'newEvents': 3, 'finalDecision': 'REJECTED', 'identicalRetries': 3,
                                    'stalePredecessorRejected': True, 'heldObjectsRejected': 2,
                                    'chain': [r['reviewRecord']['reviewRecordId'] for r in chain]})
        self.reuse('same-jvm')

    def reuse(self, label):
        run = self.read('evaluation-post')
        self.check_run(run)
        suffix = '/runs/' + run['runId']
        assert self.call(label + '-calculation-get', suffix) == run
        assert self.call(label + '-export', suffix + '/functional-reviews/export') == self.read('final-export')
        review = self.call(label + '-reviews', suffix + '/functional-reviews')
        assert review['records'] == self.read('final-export')['records']
        before = self.call(label + '-runs-before', '/runs')
        assert before == self.read('evaluate-runs-after')
        data = (self.out / 'evaluation-request.json').read_bytes()
        assert self.call(label + '-identical-post', '/runs', data) == run
        assert self.call(label + '-runs-after', '/runs') == before
        assert self.call(label + '-old-calculation', '/runs/' + self.old) == self.read('prepare-old-calculation')
        assert self.call(label + '-old-export', '/runs/' + self.old + '/functional-reviews/export') == self.read('prepare-old-export')
        self.verify_files()
        self.write(label + '-summary', {'runId': run['runId'], 'fullCalculationEqual': True, 'reviewChainEqual': True,
                                      'oldRunUnchanged': True, 'originalFilesUnchanged': True,
                                      'newRuns': 0, 'newProviderEvaluator': [0, 0], 'newReviewEvents': 0})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['evaluate', 'review', 'review-checks', 'restart', 'verify-files'])
    parser.add_argument('--output', required=True)
    parser.add_argument('--task-cache', required=True)
    parser.add_argument('--base', default='http://127.0.0.1:8236')
    args = parser.parse_args()
    acceptance = Acceptance(args)
    if args.phase == 'restart':
        acceptance.reuse('new-jvm')
    elif args.phase == 'verify-files':
        acceptance.verify_files()
    else:
        getattr(acceptance, args.phase.replace('-', '_'))()
    print(json.dumps({'phase': args.phase, 'status': 'PASS', 'output': str(acceptance.out)}))
