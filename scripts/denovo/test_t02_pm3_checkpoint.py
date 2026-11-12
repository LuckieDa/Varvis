"""Synthetic transport failures; actual registration, model ledger and cache replay.

No cached proposal is a scientific verdict, and recovery never clears the shared
ledger's stopped reason or repeats a failed paid call inside the paper round.
"""
import unittest
from pathlib import Path
from unittest.mock import patch
from bundle_model_contract import load, requests
from model_literature_contract import parse
from pm3_chunk_priority import order
from process_pm3_paper import process
from run_pm3_literature_model import run
import test_pm3_cache_reuse


class T02Pm3Checkpoint(unittest.TestCase):
    def setUp(self):
        self.fixture=test_pm3_cache_reuse.CacheReuse();self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.x=self.fixture.x;self.args=self.fixture.args
        planned=requests(load(self.x.library,'PMC123',self.x.pin,'PM3'),self.x.c['model'],
                         self.x.c['maxOutputTokens'],self.x.c['maxRequestBytes'])
        self.indices=order(planned,self.args[3]);self.assertGreater(len(self.indices),1)
        self.first,self.later=self.indices[0],self.indices[-1]

    def populate(self,index,prefix):
        for stage in ('extraction','review'):
            run(*self.args,self.x.root/(prefix+'-'+stage),chunk_index=index,
                previous=self.x.root/(prefix+'-extraction/artifact.json') if stage=='review' else None,
                allow_model=True,transport=self.fixture.transport)

    def fail_once(self, calls):
        def transport(config,key,body):
            calls.append(parse(parse(body)['messages'][1]['content']))
            raise ValueError('DENOVO_MODEL_TIMEOUT')
        return transport

    def test_failed_earlier_extraction_preserves_later_valid_cache_and_exact_checkpoint(self):
        self.populate(self.later,'later');calls=[]
        result=process(*self.args,self.x.root/'failed-extraction',allow_model=True,maximum_chunks=1,
                       transport=self.fail_once(calls))
        self.assertEqual(1,len(calls));self.assertEqual(1,result['modelCalls'])
        self.assertEqual([self.later],result['completedReviewChunks'])
        self.assertFalse(result['observations']['allChunksProcessed'])
        self.assertEqual('PARTIAL',result['status'])
        checkpoint=result['processingCheckpoint']
        self.assertEqual('PMC123',checkpoint['pmcid']);self.assertEqual(self.x.pin,checkpoint['bundleSha256'])
        self.assertEqual(self.args[3],checkpoint['target']);self.assertFalse(checkpoint['savedScoreAdopted'])
        self.assertTrue(checkpoint['newModelWorkStoppedAfterFailure'])
        failure=next(s for s in checkpoint['stages'] if s['chunkIndex']==self.first)
        self.assertEqual(('EXTRACTION','TEMPORARY_FAILURE','DENOVO_MODEL_TIMEOUT'),
                         (failure['stage'],failure['status'],failure['reasonCode']))
        self.assertEqual((self.x.root/'later-review/artifact.json').read_bytes(),
                         (self.x.root/'failed-extraction'/f'chunk-{self.later:04d}-review/artifact.json').read_bytes())
        ledger=parse((self.x.root/'budget/budget.json').read_bytes())
        self.assertEqual('TIMEOUT',ledger['stoppedReason'])
        self.assertEqual(1,sum(c['status']=='FAILED_RESERVED' for c in ledger['calls']))

    def test_failed_review_does_not_erase_extraction_or_later_successful_review(self):
        self.populate(self.later,'later')
        run(*self.args,self.x.root/'first-extraction',chunk_index=self.first,
            allow_model=True,transport=self.fixture.transport)
        calls=[]
        result=process(*self.args,self.x.root/'failed-review',allow_model=True,maximum_chunks=1,
                       transport=self.fail_once(calls))
        self.assertEqual(1,len(calls));self.assertEqual(1,result['modelCalls'])
        self.assertEqual({self.first,self.later},set(result['completedExtractionChunks']))
        self.assertEqual([self.later],result['completedReviewChunks'])
        stages=result['processingCheckpoint']['stages']
        failure=next(s for s in stages if s['chunkIndex']==self.first and s['stage']=='REVIEW')
        self.assertEqual('TEMPORARY_FAILURE',failure['status'])
        self.assertTrue(any(s['chunkIndex']==self.first and s['stage']=='EXTRACTION'
                            and s['status']=='SOURCE_CHECKED' and s['artifactSha256'] for s in stages))
        self.assertIn('DENOVO_MODEL_TIMEOUT',result['issues'])
        self.assertFalse(result['businessPublishable']);self.assertFalse(result['scientificExecution'])

    def test_scheduler_deadline_before_run_has_no_unknown_paid_attempt_and_keeps_pending_checkpoint(self):
        with patch('process_pm3_paper.time.monotonic', side_effect=[0, 2]), patch(
                'process_pm3_paper.run', wraps=run) as worker:
            result=process(*self.args,self.x.root/'preflight-deadline',allow_model=True,seconds=1)
        worker.assert_not_called()
        self.assertEqual(0,result['modelCalls'])
        self.assertNotIn('callAccountingStatus',result)
        self.assertFalse((self.x.root/'budget/budget.json').exists())
        self.assertEqual('PARTIAL',result['status'])
        self.assertIn('DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED',result['issues'])
        self.assertEqual([{'chunkIndex':self.first,'stage':'EXTRACTION','status':'NOT_EXTRACTED',
            'modelCalls':0,'reasonCode':'DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED'}],result['processingCheckpoint']['stages'])
        self.assertFalse(result['processingCheckpoint']['newModelWorkStoppedAfterFailure'])
        replay=process(*self.args,self.x.root/'preflight-resumed',allow_model=False)
        self.assertEqual(0,replay['modelCalls'])
        self.assertNotIn('callAccountingStatus',replay)
        self.assertEqual(result['pendingReviewChunks'],replay['pendingReviewChunks'])

    def test_review_preflight_preserves_successful_extraction_and_its_accounted_call(self):
        expired=[False]
        def completed(*args,**kwargs):
            result=run(*args,**kwargs);expired[0]=True
            return result
        with patch('process_pm3_paper.time.monotonic',side_effect=lambda: 2 if expired[0] else 0), patch(
                'process_pm3_paper.run',side_effect=completed) as worker:
            result=process(*self.args,self.x.root/'review-preflight',allow_model=True,seconds=1,
                           transport=self.fixture.transport)
        self.assertEqual(1,worker.call_count)
        self.assertEqual(1,result['modelCalls'])
        self.assertEqual([self.first],result['completedExtractionChunks'])
        self.assertEqual([],result['completedReviewChunks'])
        self.assertNotIn('callAccountingStatus',result)
        stages=result['processingCheckpoint']['stages']
        self.assertEqual('SOURCE_CHECKED',stages[0]['status'])
        self.assertTrue(stages[0]['artifactSha256'])
        self.assertEqual(('REVIEW','NOT_REVIEWED',0),(stages[1]['stage'],stages[1]['status'],stages[1]['modelCalls']))
        self.assertEqual(1,len(parse((self.x.root/'budget/budget.json').read_bytes())['calls']))

    def test_successful_transport_then_artifact_write_failure_is_still_unknown_accounting(self):
        calls=[];original_write=Path.write_bytes
        def source_transport(config,key,body):
            calls.append(body)
            return self.fixture.transport(config,key,body)
        def fail_artifact(path,data):
            if path.name=='artifact.json':
                raise OSError('synthetic private artifact write failed')
            return original_write(path,data)
        with patch.object(Path,'write_bytes',fail_artifact):
            result=process(*self.args,self.x.root/'post-call-write-failed',allow_model=True,transport=source_transport)
        self.assertEqual(1,len(calls))
        self.assertEqual('CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT',result['callAccountingStatus'])
        self.assertTrue(result['processingCheckpoint']['newModelWorkStoppedAfterFailure'])
        self.assertEqual(1,len(parse((self.x.root/'budget/budget.json').read_bytes())['calls']))

    def test_same_deadline_code_from_inside_worker_is_not_proof_that_no_call_was_attempted(self):
        with patch('process_pm3_paper.run',side_effect=ValueError('DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED')) as worker:
            result=process(*self.args,self.x.root/'unknown-worker-deadline',allow_model=True)
        self.assertGreater(worker.call_count,0)
        self.assertEqual('CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT',result['callAccountingStatus'])
        self.assertTrue(result['processingCheckpoint']['newModelWorkStoppedAfterFailure'])

    def test_scheduler_deadline_after_failed_transport_does_not_clear_pending_accounting(self):
        expired=[False];calls=[]
        def failed(config,key,body):
            calls.append(body);expired[0]=True
            raise ValueError('DENOVO_MODEL_TIMEOUT')
        with patch('process_pm3_paper.time.monotonic',side_effect=lambda: 2 if expired[0] else 0):
            result=process(*self.args,self.x.root/'failure-then-deadline',allow_model=True,seconds=1,transport=failed)
        self.assertEqual(1,len(calls))
        self.assertEqual(1,result['modelCalls'])
        self.assertEqual('CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT',result['callAccountingStatus'])
        self.assertIn('DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED',result['issues'])
        ledger=parse((self.x.root/'budget/budget.json').read_bytes())
        self.assertEqual(1,sum(c['status']=='FAILED_RESERVED' for c in ledger['calls']))

    def test_offline_reassembly_keeps_valid_receipts_without_clearing_failed_attempt(self):
        self.populate(self.later,'later');calls=[]
        process(*self.args,self.x.root/'failed',allow_model=True,transport=self.fail_once(calls))
        before=(self.x.root/'budget/budget.json').read_bytes()
        replayed=process(*self.args,self.x.root/'read-only-cache',allow_model=False,maximum_chunks=1)
        self.assertEqual(0,replayed['modelCalls']);self.assertEqual([self.later],replayed['completedReviewChunks'])
        self.assertEqual(before,(self.x.root/'budget/budget.json').read_bytes())
        self.assertFalse(replayed['processingCheckpoint']['savedScoreAdopted'])
        self.assertFalse(replayed['observations']['allChunksProcessed'])

    def test_source_rejected_review_does_not_hide_later_verified_cache_or_adopt_initial_proposal(self):
        self.populate(self.later,'later')
        run(*self.args,self.x.root/'first-extraction',chunk_index=self.first,
            allow_model=True,transport=self.fixture.transport)
        from test_bundle_model import response
        from pm3_literature_contract import VERSION
        calls=[]
        def rejected(config,key,body):
            calls.append(body)
            return response({'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
                'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':'not-in-input','quote':'not supplied'}}]})
        result=process(*self.args,self.x.root/'source-rejected',allow_model=True,maximum_chunks=1,transport=rejected)
        self.assertEqual(1,len(calls));self.assertEqual(1,result['modelCalls'])
        self.assertEqual([self.later],result['completedReviewChunks'])
        self.assertEqual([],result['observations']['unreviewedExtraction']['cases'])
        self.assertTrue(any(s['chunkIndex']==self.first and s['stage']=='REVIEW' and s['status']=='SOURCE_REJECTED'
                            for s in result['processingCheckpoint']['stages']))
        self.assertIn('PM3_MODEL_REVIEW_SOURCE_CHECK_REJECTED',result['issues'])
        self.assertFalse(result['businessPublishable']);self.assertFalse(result['observations']['allChunksProcessed'])


if __name__=='__main__':unittest.main()
