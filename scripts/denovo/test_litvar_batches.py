"""Batch continuation, source isolation and exact per-paper provenance."""
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from discover_public_literature import discover, implementation_pin, make_plan
from test_discover_public_literature import target, response, row
from test_litvar_literature import LitFake, publications
from test_litvar_interface import entry, Fixture, query, pubs
from litvar_literature import resolve
from literature_discovery_state import persist, load_parent
from model_literature_contract import canonical, digest


class Batches(unittest.TestCase):
    def test_single_litvar_mode_never_uses_original_discovery_queries(self):
        fake = LitFake(text=lambda q, c: response([row()]) if 'EXT_ID:' in q else self.fail(q))
        r = discover([target(aliases=['p.T41A'])], fake, precision_first=True, litvar=True, search_mode='LITVAR_ONLY')
        self.assertEqual(['123'], [c['pmid'] for c in r['candidates']])
        self.assertEqual({'LITVAR_VARIANT', 'LITVAR_PUBLICATIONS'}, {q['tier'] for q in r['queries']})
        for mode in ('ORIGINAL', 'COMBINED'):
            with self.assertRaisesRegex(ValueError, 'MODE_RETIRED'):
                make_plan([target()], search_mode=mode)


    def test_metadata_batches_resume_without_refetching_completed_pages(self):
        import re
        fake = LitFake(pubs=publications([str(i) for i in range(1, 103)]), text=lambda q,c:
                       response([row(p) for p in re.findall(r'EXT_ID:([0-9]+)', q)]))
        targets = [target(aliases=['p.T41A'])]
        batches, checkpoint, all_ids = [], None, []
        for _ in range(4):
            r = discover(targets, fake, max_pages=2, litvar=True, search_mode='LITVAR_ONLY', checkpoint=checkpoint)
            batches.append(r); checkpoint = r['checkpoint']
            all_ids += [c['pmid'] for c in r['candidates']]
            if not r['resumeAvailable']: break
        self.assertEqual(3, len(batches))
        self.assertEqual(102, len(all_ids)); self.assertEqual(102, len(set(all_ids)))
        self.assertEqual(6, len(fake.queries)); self.assertEqual(6, len(set(fake.queries)))
        self.assertEqual(3, len(fake.lit_calls))
        self.assertFalse(batches[-1]['resumeAvailable'])

    def test_changed_target_or_mode_rejects_checkpoint(self):
        r = discover([target(aliases=['p.T41A'])], LitFake(), litvar=True, search_mode='LITVAR_ONLY')
        with self.assertRaisesRegex(ValueError, 'CHECKPOINT_IDENTITY'):
            discover([target(aliases=['p.T41V'])], LitFake(), litvar=True, search_mode='LITVAR_ONLY', checkpoint=r['checkpoint'])
        with self.assertRaisesRegex(ValueError, 'CHECKPOINT_IDENTITY'):
            discover([target(aliases=['p.T41A'])], LitFake(), litvar=True, checkpoint={**r['checkpoint'], 'searchMode':'ORIGINAL'})

    def test_legacy_keyword_checkpoint_rejected_before_network(self):
        r = discover([target(aliases=['p.T41A'])], LitFake())
        r['checkpoint']['queries'][0]['tier'] = 'VARIANT_TERMS'
        fake = LitFake()
        with self.assertRaisesRegex(ValueError, 'CHECKPOINT_RETIRED_QUERY_TIER'):
            discover([target(aliases=['p.T41A'])], fake, checkpoint=r['checkpoint'])
        self.assertEqual(0, fake.requests)

    def test_missing_aliases_are_not_reported_as_successful_empty_search(self):
        r = discover([target(aliases=[])], LitFake(), search_mode='LITVAR_ONLY')
        self.assertEqual('SEARCH_INCOMPLETE', r['targets'][0]['status'])
        self.assertIn('LITERATURE_SEARCH_VARIANT_CLUES_UNAVAILABLE', r['targets'][0]['issues'])

    def test_distinct_entry_links_are_not_assigned_to_unrelated_papers(self):
        a, b = entry(), entry('litvar@rs124##', 'c.123A>G')
        f = Fixture({('autocomplete', 'EXAMPLE p.T41A'): [a], ('autocomplete','EXAMPLE c.123A>G'): [b],
                     ('summary', a['_id']): a, ('summary',b['_id']): b,
                     ('publications',a['_id']): pubs('111'), ('publications',b['_id']): pubs('222')})
        f.requests, f.events = 0, []
        f.get = lambda q, c: response([row('111'), row('222')])
        r = discover([target(aliases=['p.T41A','c.123A>G'])], f, litvar=True, search_mode='LITVAR_ONLY')
        self.assertEqual(2,len(r['candidates']))
        links = {c['pmid']: c['matches'][0]['litvarVariantIds'] for c in r['candidates']}
        self.assertEqual({'111':[a['_id']], '222':[b['_id']]}, links)

    def test_pending_acquisition_durable_pin_and_selection_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); work=root/'local/work'; work.mkdir(parents=True)
            request={'genes':['EXAMPLE'], 'searchTargets':[], 'searchMode':'LITVAR_ONLY'}
            found=discover([target(aliases=['p.T41A'])],LitFake(),litvar=True,search_mode='LITVAR_ONLY')
            found['checkpoint']['implementation'] = implementation_pin()
            found['candidates'] += [{**found['candidates'][0], 'pmid':'456','pmcid':'PMC789'}]
            result=persist(work,request,found,{'papers':[{'pmid':'123','pmcid':'PMC456','status':'REGISTERED'},
                {'pmid':'456','pmcid':'PMC789','reasonCode':'PAPER_ACQUISITION_PAPER_BUDGET'}]})
            self.assertEqual(1,result['pendingPapers']); self.assertEqual(1,result['attemptedPapers'])
            request['discoveryContinuation']={'parentTaskId':'a'*64,'parentResultSha256':'b'*64,
                'stateSha256':result['sha256'],'statePath':'local/work/discovery-state.json'}
            self.assertEqual('456',load_parent(request,root)['pendingCandidates'][0]['pmid'])
            request['genes']=['OTHER']
            with self.assertRaisesRegex(ValueError,'IDENTITY'): load_parent(request,root)
            request['genes']=['EXAMPLE']
            (work/'discovery-state.json').write_bytes(b'{}')
            with self.assertRaisesRegex(ValueError,'PIN_MISMATCH'): load_parent(request,root)

    def test_workflow_resume_uses_pending_papers_and_never_repeats_completed_model(self):
        from public_literature_workflow import run, VERSION
        from model_literature_contract import parse
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'local').mkdir()
            (root/'local/model.json').write_bytes(b'{}')
            config=root/'config.json'; config.write_bytes(canonical({
                'schemaVersion':VERSION,'enabled':True,'projectRoot':str(root),'library':'local/library',
                'searchCache':'local/cache','modelBudget':'local/budget','modelConfig':'local/model.json',
                'allowModel':False,'allowPublicNetwork':False}))
            request={'schemaVersion':VERSION,'genes':['EXAMPLE'],'selectionSha256':'a'*64,
                     'searchMode':'LITVAR_ONLY'}
            found=discover([target(aliases=['p.T41A'])],LitFake(),litvar=True,search_mode='LITVAR_ONLY')
            found['checkpoint']['implementation']=implementation_pin()
            found['candidates'] += [{**found['candidates'][0],'pmid':'456','pmcid':'PMC789'}]
            acquired={'networkRequests':0,'papers':[
                {'pmcid':p['pmcid'],'status':'REGISTERED','registration':{'bundleSha256':'f'*64}}
                for p in found['candidates']]}
            processed=[]
            def process(*args,**kw):
                processed.append(args[1]); item={'pmcid':args[1], 'issues':['PAPER_TEST_STOP'] if args[1]=='PMC456' else [],
                    'completeTargetExtraction':False}
                args[7]['papers'].append(item); return item
            first=root/'local/first';first.mkdir(); req=first/'request.json';req.write_bytes(canonical(request))
            with patch('public_literature_workflow.discover',return_value=found), \
                 patch('public_literature_workflow.acquire',return_value=acquired), \
                 patch('public_literature_workflow.process_paper',side_effect=process):
                result=run(req,first/'result.json',config)
            self.assertEqual(['PMC456'],processed)
            self.assertEqual(1,result['discoveryState']['pendingPapers'])
            request['discoveryContinuation']={'parentTaskId':'b'*64,'parentResultSha256':'c'*64,
                'statePath':'local/first/pipeline/discovery-state.json','stateSha256':result['discoveryState']['sha256']}
            second=root/'local/second';second.mkdir();req=second/'request.json';req.write_bytes(canonical(request))
            with patch('public_literature_workflow.discover',side_effect=AssertionError('no discovery repeat')), \
                 patch('public_literature_workflow.acquire',return_value={**acquired,'papers':[acquired['papers'][1]]}), \
                 patch('public_literature_workflow.process_paper',side_effect=process):
                result=run(req,second/'result.json',config)
            self.assertEqual(['PMC456','PMC789'],processed)
            self.assertFalse(result['discoveryState']['resumeAvailable'])
            self.assertEqual(2,result['discoveryState']['attemptedPapers'])
            self.assertEqual(0,result['modelCalls']); self.assertEqual(0,result['networkRequests'])
            with patch('discover_public_literature.implementation_pin',return_value={}):
                with self.assertRaisesRegex(ValueError,'IMPLEMENTATION_CHANGED'): load_parent(request,root)


if __name__ == '__main__': unittest.main()
