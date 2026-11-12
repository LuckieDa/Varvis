"""Cache reuse must preserve original receipts and cannot broaden source identity."""
import unittest
from unittest.mock import patch
from test_pm3_literature_contract import Runner
from test_bundle_model import response
from model_literature_contract import parse
from pm3_literature_contract import VERSION
from run_pm3_literature_model import run, replay
from process_pm3_paper import process


class CacheReuse(unittest.TestCase):
    def setUp(self):
        self.f = Runner(); self.f.setUp(); self.addCleanup(self.f.doCleanups)
        self.x = self.f.fixture; self.calls = 0
        x = self.x
        self.args = (x.library, 'PMC123', x.pin, {'geneSymbol':'GENE1','variantAliases':['c.1A>G']},
                     x.c, x.config, x.root/'budget')

    def transport(self, c, key, body):
        self.calls += 1
        content = parse(parse(body)['messages'][1]['content'])
        unit = next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
        return response({'schemaVersion':VERSION, 'cases':[], 'coverage':'EXTRACTED_WITHIN_TARGET',
            'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':unit['unitId'],'quote':unit['text']}}]})

    def test_approved_unchanged_request_reuses_original_receipt_and_parent(self):
        x = self.x
        for stage in ('extraction','review'):
            run(*self.args, x.root/stage, previous=x.root/'extraction/artifact.json' if stage=='review' else None,
                allow_model=True, transport=self.transport)
        old = parse((x.root/'extraction/artifact.json').read_bytes())['binding']['implementation']
        changed = {k:v for k,v in old.items() if k not in ('pm3_literature_contract.py','run_pm3_literature_model.py','pm3_pdf_observations.py')}
        changed['literature_attachments.py'] = 'f'*64
        with patch('run_pm3_literature_model.implementation', return_value=changed), \
                patch('bundle_replay_compatibility.reviewed_profiles', return_value=[old]):
            for stage in ('extraction','review'):
                r = run(*self.args, x.root/('new-'+stage),
                    previous=x.root/'new-extraction/artifact.json' if stage=='review' else None, allow_model=False)
                self.assertEqual('SOURCE_CHECKED_SEMANTICS_UNVERIFIED',r['status'])
                self.assertEqual(0,r['modelCalls'])
                replay(x.library,x.root/('new-'+stage)/'artifact.json')
                self.assertEqual((x.root/stage/'artifact.json').read_bytes(),(x.root/('new-'+stage)/'artifact.json').read_bytes())
        self.assertEqual(2,self.calls)

    def test_unapproved_implementation_or_changed_target_cannot_reuse(self):
        x = self.x
        run(*self.args,x.root/'old',allow_model=True,transport=self.transport)
        old = parse((x.root/'old/artifact.json').read_bytes())['binding']['implementation']
        changed = {k:v for k,v in old.items() if k not in ('pm3_literature_contract.py','run_pm3_literature_model.py','pm3_pdf_observations.py')}
        changed['literature_attachments.py'] = 'f'*64
        with patch('run_pm3_literature_model.implementation',return_value=changed), \
                patch('bundle_replay_compatibility.reviewed_profiles',return_value=[]):
            self.assertEqual('MODEL_CACHE_MISSING',run(*self.args,x.root/'unknown',allow_model=False)['status'])
        args = list(self.args); args[3] = {'geneSymbol':'GENE1','variantAliases':['c.2C>T']}
        self.assertEqual('MODEL_CACHE_MISSING',run(*args,x.root/'different',allow_model=False)['status'])
        self.assertEqual(1,self.calls)

    def test_offline_missing_earlier_chunks_does_not_hide_completed_later_chunk(self):
        x = self.x
        from bundle_model_contract import load, requests
        from pm3_chunk_priority import order
        planned = requests(load(x.library,'PMC123',x.pin,'PM3'),x.c['model'],x.c['maxOutputTokens'],x.c['maxRequestBytes'])
        index = order(planned,self.args[3])[-1]
        for stage in ('extraction','review'):
            run(*self.args,x.root/stage,chunk_index=index,
                previous=x.root/'extraction/artifact.json' if stage=='review' else None,
                allow_model=True,transport=self.transport)
        result = process(*self.args,x.root/'offline',allow_model=False,maximum_chunks=1)
        self.assertEqual([index],result['completedReviewChunks'])
        self.assertEqual('PARTIAL',result['status'])
        self.assertIn('MODEL_CACHE_MISSING',result['issues'])
        self.assertEqual(0,result['modelCalls']); self.assertEqual(2,self.calls)
        self.assertFalse(result['observations']['allChunksProcessed'])


if __name__ == '__main__': unittest.main()
