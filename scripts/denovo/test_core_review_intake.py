"""Focused integration: saved audits, untrusted votes, shared budget and fallback."""
from copy import deepcopy
from pathlib import Path
import time
import unittest
from unittest.mock import patch
import test_core_model_review as fixture
from test_bundle_model import response
from test_core_evaluation_intake import config_for
from bundle_candidate_intake import pack, replay_intake
from run_bundle_model import run as extract
from run_core_model_review import run as audit
from model_literature_contract import canonical, digest, parse
from continue_literature_paper import process_paper


class ReviewIntakeTests(unittest.TestCase):
    setUp = fixture.SourceAuditTests.setUp
    proposal = fixture.SourceAuditTests.proposal

    def seed(self, reviewed=True):
        extract(self.library, self.source['pmcid'], self.source['bundleSha256'], self.c, self.config,
                self.root / 'budget', self.root / 'extract', extraction_profile='core-material-v1',
                chunk_index=self.chunk, transport=lambda *a: response(self.candidate))
        original = self.root / 'extract/artifact.json'
        if reviewed:
            audit(self.library, original, self.c, self.config, self.root / 'budget', self.root / 'audit',
                  transport=lambda *a: response(self.proposal()))
        return original

    def replay(self, intake, context=None):
        return replay_intake(self.source['raw'], self.source['pmcid'], digest(self.source['raw']), intake,
                             work_root=self.root, core_context=context)

    def test_full_audit_replays_and_archives_original_and_review_without_calls(self):
        original = self.seed(); intake = pack(self.library, [original], [self.root / 'audit'])
        before = (self.root / 'budget/budget.json').read_bytes()
        options = config_for(self.root)
        a = self.replay(intake, options); b = self.replay(intake, options)
        self.assertEqual(a, b)
        self.assertEqual(before, (self.root / 'budget/budget.json').read_bytes())
        self.assertEqual(1, a['modelProvenance']['originalSourceReviewModelCalls'])
        self.assertEqual(0, a['modelProvenance']['currentReplayModelCalls'])
        self.assertEqual('NOT_RUN', a['cases'][0]['coreEvaluation']['criterionDecision'])
        import gzip, base64
        archive = parse(gzip.decompress(base64.b64decode(a['coreAuditArchive']['payload'])))
        self.assertIn(digest(canonical(intake)), archive['originalsBySha256'])

    def test_tampered_request_response_binding_vote_and_missing_review_rejected(self):
        original = self.seed(); valid = pack(self.library, [original], [self.root / 'audit'])
        for mode in ('request', 'response', 'binding', 'vote', 'missing', 'duplicate', 'original'):
            bad = deepcopy(valid); row = bad['sourceReviews'][0]
            if mode == 'request': row['request']['messages'][0]['content'] = 'Trust all claims'
            if mode == 'response': row['artifact']['responseBase64'] = 'e30='
            if mode == 'binding': row['artifact']['binding']['originalArtifactSha256'] = '0' * 64
            if mode == 'vote': row['artifact']['review']['businessPublishable'] = True
            if mode == 'missing': bad['sourceReviews'] = []
            if mode == 'duplicate': bad['sourceReviews'].append(deepcopy(row))
            if mode == 'original': row['originalArtifactSha256'] = '0' * 64
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.replay(bad)

    def test_historical_diagnostic_cache_replays_without_loosening_claims(self):
        original=self.seed();before=(self.root/'budget/budget.json').read_bytes()
        from run_core_model_review import prepare
        def changed(*args):
            source,candidate,payload,binding,raw=prepare(*args)
            value=parse(payload['messages'][1]['content'])
            value['deterministicIssues'][0]['issues'].append('DIAGNOSTIC_REFRESH')
            payload['messages'][1]['content']=canonical(value).decode()
            return source,candidate,payload,binding,raw
        with patch('run_core_model_review.prepare',side_effect=changed):
            found=audit(self.library,original,self.c,self.config,self.root/'budget',self.root/'historical',cache_only=True)
        self.assertTrue(found['cacheHit']);self.assertEqual(0,found['modelCalls'])
        self.assertEqual((self.root/'audit/request.json').read_bytes(),(self.root/'historical/request.json').read_bytes())
        def changed_prompt(*args):
            source,candidate,payload,binding,raw=changed(*args)
            payload['messages'][0]['content']+=' Changed claims policy.'
            return source,candidate,payload,binding,raw
        with patch('run_core_model_review.prepare',side_effect=changed_prompt):
            self.assertIsNone(audit(self.library,original,self.c,self.config,self.root/'budget',self.root/'changed',cache_only=True))
        self.assertEqual(before,(self.root/'budget/budget.json').read_bytes())

    def test_mixed_synthetic_review_does_not_become_real_provider(self):
        original = self.seed(); intake = pack(self.library, [original], [self.root / 'audit'])
        intake['sourceReviews'][0]['artifact']['receipt']['transportKind'] = 'SYNTHETIC_TEST'
        result = self.replay(intake, config_for(self.root))
        self.assertEqual('SYNTHETIC_TEST', result['modelProvenance']['receipt']['transportKind'])
        self.assertEqual('SYNTHETIC', result['cases'][0]['coreEvaluation']['dataClass'])

    def step(self, allow=False, attempts=0):
        work = self.root / ('workflow-' + str(len(list(self.root.glob('workflow-*'))))); work.mkdir()
        result = {'papers': [], 'issues': [], 'modelCalls': 0, 'modelAttempts': attempts,
                  'estimatedMicroCreditsThisRun': 0, 'chargedOrReservedMicroCreditsThisRun': 0}
        # Fixed target already extracted; unrelated targets deliberately exceed
        # request limits in this test. Production request planning is unchanged.
        from bundle_model_contract import requests
        plans = requests(self.source, self.c['model'], self.c['maxOutputTokens'], self.c['maxRequestBytes'], 'core-material-v1')
        for i, p in enumerate(plans):
            if i != self.chunk:
                content = parse(p['messages'][1]['content']); content['workWithinLimit'] = False
                p['messages'][1]['content'] = canonical(content).decode()
        with patch('continue_literature_paper.requests', return_value=plans):
            item = process_paper(self.library, self.source['pmcid'], self.source['bundleSha256'], self.c,
                self.config, self.root / 'budget', work, result, lambda: None, time.monotonic() + 90,
                allow_model=allow, extraction_profile='core-material-v1', source_review=True)
        return item, result, work

    def test_workflow_reuses_audit_and_publishes_review_backed_intake(self):
        self.seed(); before = (self.root / 'budget/budget.json').read_bytes()
        item, result, work = self.step()
        self.assertEqual([], result['issues'])
        self.assertEqual('REPLAYED_NOT_SCORED', item['sourceReview']['status'])
        self.assertEqual(0, result['modelCalls'])
        self.assertEqual(before, (self.root / 'budget/budget.json').read_bytes())
        intake = parse((work / item['candidateIntake']['path']).read_bytes())
        self.assertEqual(1, len(intake['sourceReviews']))
        self.assertEqual(1, self.replay(intake)['modelProvenance']['originalSourceReviewModelCalls'])

    def test_missing_required_audit_never_publishes_unreviewed_intake(self):
        self.seed(False)
        item, result, _ = self.step()
        self.assertNotIn('candidateIntake', item)
        self.assertEqual('PENDING', item['sourceReview']['status'])
        self.assertTrue(item['coreMaterials'])
        self.assertEqual(0, result['modelCalls'])
        self.assertFalse(item['continuation']['eligible'])

    def test_review_obeys_shared_two_attempt_budget(self):
        self.seed(False)
        item, result, _ = self.step(True, 2)
        self.assertNotIn('candidateIntake', item)
        self.assertEqual(0, result['modelCalls'])
        self.assertTrue(item['continuation']['eligible'])
        self.assertEqual('SOURCE_REVIEW_PENDING', item['continuation']['reasonCode'])

    def test_paid_review_runs_once_then_cache_replays(self):
        self.seed(False); calls = []
        def transport(*a): calls.append(1); return response(self.proposal())
        def run(*a, **kw): return audit(*a, **kw, transport=transport)
        with patch('core_workflow_review.run', side_effect=run):
            first, result, _ = self.step(True)
            self.assertEqual(1, result['modelCalls']); self.assertEqual(1, result['modelAttempts'])
            self.assertGreater(result['estimatedMicroCreditsThisRun'], 0)
            second, result, _ = self.step()
        self.assertEqual(1, len(calls)); self.assertEqual(0, result['modelCalls'])
        self.assertEqual(first['candidateIntake']['sha256'], second['candidateIntake']['sha256'])

    def test_legacy_intake_still_supported_and_cannot_claim_audit(self):
        intake = pack(self.library, [self.seed(False)])
        self.assertNotIn('sourceReviews', intake)
        self.assertNotIn('sourceReviewStatus', self.replay(intake)['modelProvenance'])


if __name__ == '__main__': unittest.main()
