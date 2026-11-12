"""T02: real synthetic source/receipts and task-bound continuation, no live APIs.

External retrieval/model transports are synthetic controls. Source registration,
request pins, artifact replay, cache binding, worker dispatch and qualification
execute their ordinary implementations; no saved score is adopted.
"""
from copy import deepcopy
from pathlib import Path
import unittest
from unittest.mock import patch

from model_literature_contract import canonical, digest, parse
from process_pp1_paper import process, paper_recovery
from pp1_reading_loop import recovery_state
import test_pp1_discovery as discovery_fixture
import test_pp1_paper_task as worker_fixture
import test_pp1_reading as reading_fixture
import test_pp1_segregation as segregation_fixture


class ReadingRecovery(unittest.TestCase):
    def setUp(self):
        self.f = reading_fixture.Pipeline(); self.f.setUp(); self.addCleanup(self.f.doCleanups)

    def test_actual_unread_source_stops_are_recoverable_without_becoming_source_absence(self):
        artifacts = self.f.seed_artifacts()
        for folder, options, code in [
                ('disabled', {}, 'PP1_READING_MODEL_DISABLED_CACHE_MISSING'),
                ('capacity', {'execute': True, 'maximum_calls': 0}, 'PP1_READING_CALL_LIMIT'),
                ('timeout', {'execute': True, 'seconds': 1}, 'PP1_READING_TIME_LIMIT')]:
            with self.subTest(folder=folder):
                value = self.f.loop(artifacts, folder, **options)
                self.assertEqual(code, value['stopReason'])
                self.assertEqual('NOT_EVALUATED', value['evaluation']['status'])
                self.assertTrue(value['recovery']['resumeAvailable'])
                self.assertFalse(value['recovery']['automaticRetryAllowed'])
                self.assertFalse(value['readingPlan']['wholePaperAbsenceClaim'])
                self.assertEqual(2, self.f.transports)
        continued = self.f.loop(artifacts, 'actual-resume', execute=True)
        self.assertEqual(('MET', 2, 4), (continued['evaluation']['status'], continued['modelAttempts'], self.f.transports))
        self.assertFalse(continued['recovery']['resumeAvailable'])
        cached = self.f.loop(continued['acceptedArtifacts'], 'readonly-replay')
        self.assertEqual(('MET', 0, 4), (cached['evaluation']['status'], cached['modelAttempts'], self.f.transports))

    def test_billing_failure_is_not_implicit_retry_and_source_failure_is_not_resumable(self):
        for code in ['DENOVO_MODEL_TIMEOUT', 'DENOVO_MODEL_RATE_LIMITED', 'DENOVO_MODEL_HTTP_503']:
            state = recovery_state(code)
            self.assertEqual('EXECUTION_REVIEW_REQUIRED', state['status'])
            self.assertTrue(state['sharedBudgetReconciliationRequired'])
            self.assertFalse(state['automaticRetryAllowed'])
        for code in ['PP1_SOURCE_AUDIT_REJECTED', 'PP1_CONTINUATION_SOURCE_MISMATCH', 'EXTERNAL_REQUIREMENT',
                     'AVAILABLE_SCOPE_REVIEWED', 'PP1_TYPED_SEMANTICS_UNRESOLVED']:
            self.assertFalse(paper_recovery(code)['resumeAvailable'])

    def test_unexecuted_pedigree_review_is_not_hidden_as_all_material_reviewed(self):
        artifacts = self.f.seed_artifacts()
        from pp1_reading_loop import assess, plan
        state = assess(self.f.library, artifacts, segregation_fixture.selection(),
                       segregation_fixture.context(), segregation_fixture.ledger(), reading_fixture.Resolver())
        def no_text(source, evaluated, qualified, target, attempted=()):
            value = plan(source, evaluated, qualified, target, attempted)
            value['selected'] = None; value['remainingChunks'] = 0
            return value
        item = {'key': 'fixture-image', 'family': 'F1'}
        def figure(*args):
            return None if 'fixture-image' in args[-1] else item
        with patch('pp1_reading_loop.assess', return_value=deepcopy(state)), \
                patch('pp1_reading_loop.plan', side_effect=no_text), \
                patch('pp1_pedigree_schedule.next_figure', side_effect=figure), \
                patch('pp1_pedigree_schedule.review', return_value={
                    'status': 'PENDING', 'reasonCode': 'PP1_PEDIGREE_PAIR_CALL_CAPACITY'}):
            result = self.f.loop(artifacts, 'pending-image')
        self.assertEqual('PP1_PEDIGREE_PAIR_CALL_CAPACITY', result['stopReason'])
        self.assertEqual('PEDIGREE_REVIEW', result['recovery']['stage'])
        self.assertTrue(result['recovery']['resumeAvailable'])
        self.assertEqual(2, self.f.transports)

    def test_duplicate_stored_pair_bytes_do_not_become_two_reviews_or_observations(self):
        artifacts = self.f.seed_artifacts()
        pairs = []
        for name in ['captured', 'native-copy']:
            folder = self.f.root / name; folder.mkdir()
            figure = folder / 'source.json'; figure.write_bytes(b'{}')
            pair = folder / 'pair.json'; pair.write_bytes(b'{"SYNTHETIC_INVALID_PAIR":"same bytes"}')
            pairs.append((figure, pair))
        from pp1_reading_loop import assess
        unused_source, unused_inventory, qualified, evaluated = assess(self.f.library, artifacts,
            segregation_fixture.selection(), segregation_fixture.context(), segregation_fixture.ledger(),
            reading_fixture.Resolver(), pairs)
        self.assertEqual(1, qualified['pedigreeReview']['registeredFigures'])
        self.assertEqual(0, qualified['pedigreeReview']['reviewedPairs'])
        self.assertEqual('NOT_EVALUATED', evaluated['status'])
        self.assertFalse(evaluated['events'])

    def test_rejected_source_paths_stay_private_while_public_pins_and_reasons_survive(self):
        artifacts = self.f.seed_artifacts()
        from pp1_reading_loop import plan
        from test_bundle_model import response
        audits = 0
        def transport(unused_config, unused_key, body):
            nonlocal audits
            self.f.transports += 1
            request = parse(parse(body)['messages'][1]['content'])
            candidate = self.f.candidate([u['unitId'] for u in request['units']])
            if 'original' not in request: return response(candidate)
            audits += 1; reviewed = self.f.audit(request['original'])
            if audits == 1:
                family = deepcopy(candidate['families'][0]); family['members'][0]['memberLabel'] = 'I-9'
                reviewed['additionalFamilies'] = [family]
            return response(reviewed)
        def two_blocks(source, evaluated, qualified, target, attempted=()):
            value = plan(source, evaluated, qualified, target, attempted)
            if len(attempted) < 2:
                ids = [self.f.seed['unitId'], self.f.extra['unitId']] if not attempted else [u['unitId'] for u in source['units']]
                value['selected'] = {'chunkKey': f'block-{len(attempted)}', 'unitIds': ids}
            return value
        binding = {'schemaVersion': 'pp1-analysis-binding-v1', 'selection': segregation_fixture.selection(),
                   'ledger': segregation_fixture.ledger()}
        output = self.f.root / 'paper-source-rejection'
        with patch('pp1_reading_loop.plan', side_effect=two_blocks):
            result = process(self.f.library, 'PMC123', self.f.pin, self.f.target, binding,
                segregation_fixture.context(), reading_fixture.Resolver(), self.f.config, self.f.cfg,
                self.f.root / 'budget', output, allow_model=True, transport=transport, accepted_artifacts=artifacts)
        self.assertEqual('EVALUATED', result['status'])
        public = result['reading']['rejectedArtifacts'][0]
        self.assertEqual('PP1_MEMBER_NOT_ANCHORED', public['reasonCode'])
        self.assertRegex(public['artifactSha256'], '[a-f0-9]{64}')
        self.assertNotIn('artifactPath', str(result['reading']))
        self.assertNotIn(str(self.f.root), str(result['reading']))
        private = parse((output / 'reading/result.json').read_bytes())
        self.assertTrue(Path(private['rejectedArtifacts'][0]['artifactPath']).is_file())
        self.assertEqual(public['artifactSha256'], private['rejectedArtifacts'][0]['artifactSha256'])
        self.assertIn('artifactPath', private['rounds'][0])

    def test_postprocessing_io_failure_preserves_current_source_and_exact_failed_stage(self):
        binding = {'schemaVersion': 'pp1-analysis-binding-v1', 'selection': segregation_fixture.selection(),
                   'ledger': segregation_fixture.ledger()}
        cases = [('pp1_pedigree_delivery.freeze', FileNotFoundError('PRIVATE_SOURCE_PATH'),
                  'PEDIGREE_CHECKPOINT_CAPTURE', 'PP1_SOURCE_FILE_UNAVAILABLE'),
                 ('pp1_reviewed_cache.register', PermissionError('PRIVATE_SOURCE_PATH'),
                  'REVIEW_CACHE_REGISTRATION', 'PP1_SOURCE_IO_FAILED'),
                 ('process_pp1_paper.public_reading_view', OSError('PRIVATE_SOURCE_PATH'),
                  'PUBLIC_SOURCE_PROJECTION', 'PP1_SOURCE_IO_FAILED')]
        saved = []
        for index, (location, error, stage, reason) in enumerate(cases):
            with self.subTest(stage=stage):
                output = self.f.root / f'io-{index}'
                with patch(location, side_effect=error):
                    result = process(self.f.library, 'PMC123', self.f.pin, self.f.target, binding,
                        segregation_fixture.context(), reading_fixture.Resolver(), self.f.config, self.f.cfg,
                        self.f.root / 'budget', output, allow_model=True, transport=self.f.transport)
                self.assertEqual(('PARTIAL', reason, stage),
                    (result['status'], result['stopReason'], result['readingState']['stage']))
                self.assertTrue(result['scientificExecution'])
                self.assertEqual('MET', result['evaluation']['status'])
                self.assertEqual([], result['readingState']['stageGaps'])
                self.assertEqual(1, result['readingState']['available']['acceptedReviewArtifacts'])
                self.assertGreater(result['readingState']['available']['qualifiedObservations'], 0)
                self.assertNotIn('PRIVATE_SOURCE_PATH', str(result))
                self.assertTrue(result['readingState']['recovery']['resumeAvailable'], 'Only explicit repaired-resource continuation')
                self.assertTrue(result['readingState']['recovery']['resourceRepairRequired'])
                self.assertFalse(result['readingState']['recovery']['automaticRetryAllowed'])
                self.assertEqual(2, self.f.transports, 'Later passes must reuse successful extraction and audit')
                manifest = parse((output / 'artifacts.json').read_bytes())
                self.assertEqual(1, len(manifest['acceptedArtifacts']))
                artifact = Path(manifest['acceptedArtifacts'][0]); self.assertTrue(artifact.is_file())
                saved.append((output / 'result.json', (output / 'result.json').read_bytes(), artifact, artifact.read_bytes()))
        recovered = process(self.f.library, 'PMC123', self.f.pin, self.f.target, binding,
            segregation_fixture.context(), reading_fixture.Resolver(), self.f.config, self.f.cfg,
            self.f.root / 'budget', self.f.root / 'explicit-repaired-source', allow_model=False,
            transport=self.f.transport, accepted_artifacts=[saved[-1][2]])
        self.assertEqual(('EVALUATED', 'MET', 0, 2),
            (recovered['status'], recovered['evaluation']['status'], recovered['modelAttempts'], self.f.transports))
        for result_path, result_bytes, artifact, artifact_bytes in saved:
            self.assertEqual(result_bytes, result_path.read_bytes()); self.assertEqual(artifact_bytes, artifact.read_bytes())


class DiscoveryRecovery(unittest.TestCase):
    # Reuse existing source/transport fixture, not inherited tests in our count.
    setUp = discovery_fixture.Discovery.setUp
    execute = discovery_fixture.Discovery.execute
    def test_actual_reading_limit_stays_in_saved_discovery_checkpoint(self):
        artifacts = self.f.seed_artifacts()
        self.found['candidates'] = self.found['candidates'][:1]
        self.acquired['papers'] = self.acquired['papers'][:1]
        result = self.execute(allow_model=True, maximum_calls=0,
                              prior_artifacts={('PMC123', self.f.pin): artifacts})
        self.assertEqual('PP1_READING_CALL_LIMIT', result['papers'][0]['stopReason'])
        state = parse((self.f.root / 'discovery-output/discovery-state.json').read_bytes())
        self.assertEqual(['PMC123'], [p['pmcid'] for p in state['pendingCandidates']])
        self.assertEqual([], state['attemptedPaperKeys'])
        self.assertEqual(['PMC123'], result['pendingRegisteredPapers'])
        self.assertEqual(2, self.f.transports)


class FixedPaperRecovery(unittest.TestCase):
    setUp = worker_fixture.Worker.setUp
    execute = worker_fixture.Worker.execute
    def prepare_partial(self):
        self.c.update(allowModel=True, pp1MaximumCalls=1)
        self.config.write_bytes(canonical(self.c))
        (self.root / 'local/key.txt').write_bytes((self.root / 'key.txt').read_bytes())
        parent = self.root / 'local/work/pp1-artifacts' / ('1' * 64) / 'paper'
        def actual(*args, **kwargs):
            return process(*args, transport=self.f.transport, **kwargs)
        with patch('run_pp1_paper_task.process', side_effect=actual):
            first = self.execute(parent)
        self.assertEqual(('PARTIAL', 'PP1_CALL_LIMIT', 1),
                         (first['status'], first['stopReason'], self.f.transports))
        self.request['processingContinuation'] = {
            'parentTaskId': '1' * 64, 'parentResultSha256': digest((parent / 'result.json').read_bytes())}
        return parent, actual

    def test_worker_resumes_only_failed_audit_and_replays_successful_extraction(self):
        parent, actual = self.prepare_partial()
        raw = (parent / 'result.json').read_bytes()
        with patch('run_pp1_paper_task.process', side_effect=actual):
            second = self.execute(self.root / 'local/work/pp1-artifacts' / ('2' * 64) / 'paper')
        self.assertEqual(('EVALUATED', 1, 2), (second['status'], second['modelAttempts'], self.f.transports))
        self.assertEqual('NOT_EVALUATED', second['evaluation']['status'], 'No adopted context was supplied; do not invent it')
        self.assertEqual('EXTERNAL_REQUIREMENT', second['stopReason'])
        self.assertEqual(1, second['readingState']['available']['acceptedReviewArtifacts'])
        self.assertTrue(second['readingState']['missing'])
        self.assertFalse(second['readingState']['wholePaperAbsenceClaim'])
        self.assertEqual(raw, (parent / 'result.json').read_bytes())

    def test_parent_result_and_manifest_tamper_rejected_before_process(self):
        parent, unused = self.prepare_partial()
        (parent / 'artifacts.json').write_bytes(canonical({'acceptedArtifacts': [], 'changed': True}))
        with patch('run_pp1_paper_task.process') as called:
            with self.assertRaisesRegex(ValueError, 'PP1_PROCESSING_CONTINUATION_MANIFEST_CHANGED'):
                self.execute(self.root / 'local/work/child')
        called.assert_not_called(); self.assertEqual(1, self.f.transports)

    def test_different_selection_cannot_reuse_parent_checkpoint(self):
        self.prepare_partial(); self.request['selectionSha256'] = 'b' * 64
        with patch('run_pp1_paper_task.process') as called:
            with self.assertRaisesRegex(ValueError, 'PP1_PROCESSING_CONTINUATION_IDENTITY'):
                self.execute(self.root / 'local/work/child')
        called.assert_not_called()

    def test_raw_extraction_cannot_be_promoted_to_accepted_review_by_parent_manifest(self):
        parent, unused = self.prepare_partial()
        extraction = parent / 'initial-extraction/artifact.json'
        self.assertTrue(extraction.is_file())
        manifest = parent / 'artifacts.json'
        manifest.write_bytes(canonical({'acceptedArtifacts': [str(extraction)]}))
        result = parse((parent / 'result.json').read_bytes())
        result['artifactManifestSha256'] = digest(manifest.read_bytes())
        (parent / 'result.json').write_bytes(canonical(result))
        self.request['processingContinuation']['parentResultSha256'] = digest((parent / 'result.json').read_bytes())
        with patch('run_pp1_paper_task.process') as called:
            with self.assertRaisesRegex(ValueError, 'PP1_PROCESSING_CONTINUATION_ARTIFACT_IDENTITY'):
                self.execute(self.root / 'local/work/child')
        called.assert_not_called(); self.assertEqual(1, self.f.transports)

    def test_semantic_or_shared_billing_failure_is_not_an_execution_continuation(self):
        parent, unused = self.prepare_partial()
        for code in ['EXTERNAL_REQUIREMENT', 'PP1_SOURCE_AUDIT_REJECTED', 'DENOVO_MODEL_TIMEOUT']:
            with self.subTest(code=code):
                value = parse((parent / 'result.json').read_bytes()); value['stopReason'] = code
                (parent / 'result.json').write_bytes(canonical(value))
                self.request['processingContinuation']['parentResultSha256'] = digest((parent / 'result.json').read_bytes())
                with patch('run_pp1_paper_task.process') as called:
                    with self.assertRaisesRegex(ValueError, 'PP1_PROCESSING_CONTINUATION_NOT_RESUMABLE'):
                        self.execute(self.root / 'local/work/child')
                called.assert_not_called()

    def attach_reviewed_original(self, parent):
        """Synthetic immutable-source archive used by historical-task controls.

        The source/audit responses and receipts are generated normally. Only
        the stored task's historical metadata/derived JSON is varied below;
        those fields must never become inputs to current qualification.
        """
        from run_pp1_literature_model import run as model_run
        extraction = parent / 'initial-extraction/artifact.json'
        source = parse(extraction.read_bytes())
        b = source['binding']; folder = parent / 'saved-reviewed-original'
        model_run(self.f.library, b['pmcid'], b['bundleSha256'], b['target'], b['unitIds'],
                  self.f.config, self.f.cfg, self.root / 'local/budget', folder,
                  previous=extraction, execute=True, transport=self.f.transport)
        artifact = folder / 'artifact.json'
        manifest = parent / 'artifacts.json'; manifest.write_bytes(canonical({'acceptedArtifacts': [str(artifact)]}))
        result_path = parent / 'result.json'; result = parse(result_path.read_bytes())
        result['artifactManifestSha256'] = digest(manifest.read_bytes())
        # This represents a different historical task implementation, not an
        # approved replay profile for the raw artifact. Those pins stay strict.
        result['historicalTaskImplementationSha256'] = 'a' * 64
        result.pop('readingState', None)
        result_path.write_bytes(canonical(result))
        self.request['processingContinuation']['parentResultSha256'] = digest(result_path.read_bytes())
        return artifact

    def test_historical_task_derived_score_and_checked_inventory_are_not_adopted(self):
        self.request['analysisBinding']['ledger'].update(status='UNKNOWN', pp4Points=None)
        parent, actual = self.prepare_partial()
        artifact = self.attach_reviewed_original(parent)
        old = parse(artifact.read_bytes())
        old['checked'] = {'status': 'MET', 'awardedPP1Points': 99, 'sourceIdentity': {'forged': True}}
        artifact.write_bytes(canonical(old))
        result_path = parent / 'result.json'; prior = parse(result_path.read_bytes())
        prior['evaluation'] = {'status': 'MET', 'strength': 'VERY_STRONG', 'awardedPP1Points': 99,
                               'events': [{'memberLabel': 'FORGED_OLD_MEMBER'}]}
        result_path.write_bytes(canonical(prior)); parent_bytes = result_path.read_bytes()
        self.request['processingContinuation']['parentResultSha256'] = digest(parent_bytes)
        calls = self.f.transports
        with patch('run_pp1_paper_task.process', side_effect=actual):
            result = self.execute(self.root / 'local/work/pp1-artifacts' / ('2' * 64) / 'paper')
        self.assertEqual(('EVALUATED', 0, calls), (result['status'], result['modelAttempts'], self.f.transports))
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertEqual('UNKNOWN', result['evaluation']['ledger']['status'])
        self.assertEqual(0, result['evaluation']['awardedPP1Points'])
        self.assertNotIn('FORGED_OLD_MEMBER', str(result))
        self.assertNotIn('forged', str(result['reading']['qualification']))
        self.assertEqual(1, result['readingState']['available']['acceptedReviewArtifacts'])
        self.assertEqual(parent_bytes, result_path.read_bytes())

    def test_historical_task_reassembly_does_not_accept_unknown_raw_implementation(self):
        parent, unused = self.prepare_partial()
        artifact = self.attach_reviewed_original(parent)
        old = parse(artifact.read_bytes())
        old['binding']['implementation']['run_pp1_literature_model.py'] = 'f' * 64
        artifact.write_bytes(canonical(old))
        with patch('run_pp1_paper_task.process') as called:
            with self.assertRaisesRegex(ValueError, 'PP1_IMPLEMENTATION_CHANGED'):
                self.execute(self.root / 'local/work/child')
        called.assert_not_called()

    def test_historical_task_reassembly_does_not_accept_unknown_raw_prompt(self):
        parent, unused = self.prepare_partial()
        artifact = self.attach_reviewed_original(parent)
        old = parse(artifact.read_bytes()); old['binding']['promptVersion'] = 'unknown-historical-prompt-v0'
        artifact.write_bytes(canonical(old))
        with patch('run_pp1_paper_task.process') as called:
            with self.assertRaisesRegex(ValueError, 'PP1_PROMPT_VERSION'):
                self.execute(self.root / 'local/work/child')
        called.assert_not_called()

    def prepare_postprocessing_io_parent(self):
        self.c.update(allowModel=True, pp1MaximumCalls=8)
        self.config.write_bytes(canonical(self.c))
        (self.root / 'local/key.txt').write_bytes((self.root / 'key.txt').read_bytes())
        parent = self.root / 'local/work/pp1-artifacts' / ('1' * 64) / 'paper'
        def actual(*args, **kwargs):
            return process(*args, transport=self.f.transport, **kwargs)
        with patch('run_pp1_paper_task.process', side_effect=actual), \
                patch('pp1_reviewed_cache.register', side_effect=OSError('PRIVATE_SOURCE_PATH')):
            first = self.execute(parent)
        self.assertEqual(('PARTIAL', 'PP1_SOURCE_IO_FAILED', 2),
                         (first['status'], first['stopReason'], self.f.transports))
        self.assertEqual('REVIEW_CACHE_REGISTRATION', first['readingState']['stage'])
        self.assertTrue(first['readingState']['recovery']['resourceRepairRequired'])
        self.request['processingContinuation'] = {
            'parentTaskId': '1' * 64, 'parentResultSha256': digest((parent / 'result.json').read_bytes())}
        return parent, actual

    def test_explicit_worker_resource_repair_replays_current_source_without_new_model_or_old_score(self):
        self.request['analysisBinding']['ledger'].update(status='UNKNOWN', pp4Points=None)
        parent, actual = self.prepare_postprocessing_io_parent()
        result_path = parent / 'result.json'; prior = parse(result_path.read_bytes())
        prior['evaluation'] = {'status': 'MET', 'strength': 'VERY_STRONG', 'awardedPP1Points': 99,
                               'events': [{'memberLabel': 'FORGED_OLD_MEMBER'}]}
        result_path.write_bytes(canonical(prior)); before = result_path.read_bytes()
        self.request['processingContinuation']['parentResultSha256'] = digest(before)
        with patch('run_pp1_paper_task.process', side_effect=actual):
            result = self.execute(self.root / 'local/work/pp1-artifacts' / ('2' * 64) / 'paper')
        self.assertEqual(('EVALUATED', 0, 2), (result['status'], result['modelAttempts'], self.f.transports))
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertEqual('UNKNOWN', result['evaluation']['ledger']['status'])
        self.assertNotIn('FORGED_OLD_MEMBER', str(result))
        self.assertEqual(1, result['readingState']['available']['acceptedReviewArtifacts'])
        self.assertFalse(result['readingState']['recovery']['resumeAvailable'])
        self.assertEqual(before, result_path.read_bytes())

    def test_resource_repair_requires_post_stage_and_actual_verified_source_checkpoint(self):
        parent, unused = self.prepare_postprocessing_io_parent()
        result_path = parent / 'result.json'; original = parse(result_path.read_bytes())
        cases = ['initial-stage', 'empty-checkpoint', 'wrong-checkpoint-pin', 'wrong-checkpoint-paper', 'billing-reconciliation']
        for case in cases:
            with self.subTest(case=case):
                value = deepcopy(original); state = value['readingState']
                if case == 'initial-stage': state['stage'] = 'INITIAL_EXTRACTION'
                if case == 'empty-checkpoint':
                    state['checkpoint']['acceptedArtifactSha256s'] = []; state['available']['acceptedReviewArtifacts'] = 0
                if case == 'wrong-checkpoint-pin': state['checkpoint']['acceptedArtifactSha256s'] = ['f' * 64]
                if case == 'wrong-checkpoint-paper': state['checkpoint']['pmcid'] = 'PMC999'
                if case == 'billing-reconciliation': state['sourceExecutionRecovery']['sharedBudgetReconciliationRequired'] = True
                result_path.write_bytes(canonical(value))
                self.request['processingContinuation']['parentResultSha256'] = digest(result_path.read_bytes())
                reason = ('PP1_PROCESSING_CONTINUATION_CHECKPOINT_CHANGED' if case in {'wrong-checkpoint-pin', 'wrong-checkpoint-paper'}
                          else 'PP1_PROCESSING_CONTINUATION_NOT_RESUMABLE')
                with patch('run_pp1_paper_task.process') as called:
                    with self.assertRaisesRegex(ValueError, reason):
                        self.execute(self.root / 'local/work/child')
                called.assert_not_called(); self.assertEqual(2, self.f.transports)


if __name__ == '__main__': unittest.main()
