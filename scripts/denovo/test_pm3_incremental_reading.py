import unittest
from pathlib import Path
from unittest.mock import patch
from test_pm3_cache_reuse import CacheReuse
from process_pm3_paper import process
from bundle_model_contract import load, requests
from model_literature_contract import parse


class IncrementalReading(unittest.TestCase):
    def setUp(self):
        self.fixture=CacheReuse();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.x=self.fixture.x
        self.args=self.fixture.args

    def test_rejected_review_does_not_erase_other_chunks_or_become_absence(self):
        from test_bundle_model import response
        from pm3_literature_contract import VERSION
        planned=requests(load(self.x.library,'PMC123',self.x.pin,'PM3'),self.x.c['model'],self.x.c['maxOutputTokens'],self.x.c['maxRequestBytes'])
        selected={'chunks':[{'chunkIndex':i} for i in range(len(planned))],
                  'orderedRelevantChunks':[0,1], 'scope':'SYNTHETIC_TEST_SCOPE'}
        def transport(c,key,body):
            content=parse(parse(body)['messages'][1]['content'])
            if content['chunkIndex']!=0:return self.fixture.transport(c,key,body)
            unit=next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
            return response({'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
                'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':unit['unitId'],'quote':'invented and absent text'}}]})
        with patch('pm3_reading_plan.plan',return_value=selected):
            result=process(*self.args,self.x.root/'rejected',stepwise=True,allow_model=True,maximum_chunks=2,transport=transport)
        self.assertEqual([1],result['completedReviewChunks'])
        self.assertEqual([0],result['readingPlan']['blockedReviewChunks'])
        self.assertIn('PM3_MODEL_REVIEW_SOURCE_CHECK_REJECTED',result['issues'])
        self.assertFalse(result['observations']['allChunksProcessed'])

    def test_incremental_round_preserves_prior_facts_and_replays_without_new_calls(self):
        x=self.x
        planned=requests(load(x.library,'PMC123',x.pin,'PM3'),x.c['model'],x.c['maxOutputTokens'],x.c['maxRequestBytes'])
        self.assertGreater(len(planned),1)
        # Deterministic scheduling fixture; model and all source validation stay
        # real production code, only synthetic transport supplies the responses.
        selected={'schemaVersion':'pm3-need-reading-plan-v1','pmcid':'PMC123','bundleSha256':x.pin,
            'chunks':[{'chunkIndex':i} for i in range(len(planned))],
            'orderedRelevantChunks':[0,1], 'scope':'SYNTHETIC_TEST_SCOPE','unselectedChunks':list(range(2,len(planned)))}
        with patch('pm3_reading_plan.plan',return_value=selected):
            initial=process(*self.args,x.root/'cache-first',stepwise=True,allow_model=False)
            self.assertEqual(0,initial['modelCalls']);self.assertEqual([0,1],initial['readingPlan']['pendingRelevantChunks'])
            one=process(*self.args,x.root/'round-one',stepwise=True,allow_model=True,maximum_chunks=1,transport=self.fixture.transport)
            self.assertEqual([0],one['completedReviewChunks']);self.assertEqual(2,one['modelCalls'])
            two=process(*self.args,x.root/'round-two',stepwise=True,allow_model=True,maximum_chunks=1,transport=self.fixture.transport)
            self.assertEqual([0,1],two['completedReviewChunks']);self.assertEqual(2,two['modelCalls'])
            self.assertEqual([],two['readingPlan']['pendingRelevantChunks'])
            repeat=process(*self.args,x.root/'repeat',stepwise=True,allow_model=False)
            self.assertEqual(0,repeat['modelCalls']);self.assertEqual(two['observations'],repeat['observations'])
            self.assertEqual([],one['cacheReuse']['reviewChunks'])
            self.assertEqual([0],two['cacheReuse']['reviewChunks'])
            self.assertEqual([0,1],repeat['cacheReuse']['reviewChunks'])
            self.assertFalse(repeat['cacheReuse']['savedScoreAdopted'])
            self.assertEqual(4,self.fixture.calls)
            for stage in ['extraction','review']:
                self.assertEqual((x.root/'round-one'/f'chunk-0000-{stage}'/'artifact.json').read_bytes(),
                                 (x.root/'round-two'/f'chunk-0000-{stage}'/'artifact.json').read_bytes())


if __name__=='__main__':unittest.main()
