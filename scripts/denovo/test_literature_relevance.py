"""Offline behavioral checks. Model responses here are explicitly synthetic."""
from copy import deepcopy
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from model_literature_contract import canonical, digest, parse
from model_transport import atomic
from test_bundle_model import configuration, response
from literature_relevance import (VERSION, screen, options, order, material_items, payload, validate, missing_topics)
from literature_discovery_state import persist


class Relevance(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.config = configuration()
        self.config_path = self.root/'config.json'; atomic(self.config_path,self.config)
        (self.root/'key.txt').write_text('synthetic-not-secret')
        self.work = self.root/'work'; self.work.mkdir()
        source = {'geneSymbol':'GENE1','variantAliases':['c.1A>G','p.Lys1Arg']}
        atomic(self.work/'target.json',source)
        self.targets = [{'targetId':'target-0','source':{'path':'target.json','sha256':digest((self.work/'target.json').read_bytes())}}]
        self.candidates = [self.paper(1),self.paper(2)]
        self.calls = 0

    def paper(self, n):
        return {'pmcid':f'PMC{n}','pmid':str(n),'title':f'Case {n} GENE1',
            'abstract':'GENE1 c.1A>G was observed. Parents were tested.',
            'matches':[{'targetId':'target-0','metadataVariantMentions':['c.1A>G']}], 'flags':[]}

    def transport(self, unused, key, body):
        self.calls += 1
        items = parse(parse(body)['messages'][1]['content'])['items']
        return response({'schemaVersion':VERSION,'items':[{'id':i['id'],
            'priority':'HIGH' if i['paper']['pmcid']=='PMC2' else 'NORMAL','reason':'原文有病例与父母检测线索。',
            'hints':['CASE','PARENT_TESTING'], 'citations':[{'field':'abstract','quote':'Parents were tested.'}]} for i in items]})

    def run_screen(self, candidates=None, allow=True, criteria=('PS2','PM6'), **kwargs):
        result = {'modelCalls':0,'modelAttempts':0}
        out = screen(candidates if candidates is not None else self.candidates,self.targets,self.work,criteria,
            [{'allele':'local-only'}], self.config,self.config_path,self.root/'budget',result,lambda:None,
            time.monotonic()+60,allow,transport=kwargs.pop('transport',self.transport),**kwargs)
        return out,result

    def test_rank_citations_shared_ledger_and_cross_pool_reuse_without_credentials(self):
        out,result=self.run_screen()
        self.assertEqual(['PMC2','PMC1'],[p['pmcid'] for p in out]);self.assertEqual(1,self.calls)
        self.assertEqual(1,result['modelCalls']);self.assertGreater(result['chargedOrReservedMicroCreditsThisRun'],0)
        (self.root/'key.txt').unlink()
        out,again=self.run_screen([self.candidates[1]],allow=False)
        self.assertEqual(1,again['readingPriority']['cacheHits']);self.assertEqual(0,again['modelCalls'])
        self.assertEqual(1,self.calls);self.assertFalse(again['readingPriority']['scientificQualification'])

    def test_changed_source_question_and_model_invalidate(self):
        self.run_screen()
        changed=deepcopy(self.candidates);changed[0]['abstract']+=' New data.'
        out,r=self.run_screen(changed,False);self.assertEqual(1,r['readingPriority']['cacheHits'])
        out,r=self.run_screen(allow=False,criteria=('PM3',));self.assertEqual(0,r['readingPriority']['cacheHits'])
        self.config['model']='gemini-other'
        out,r=self.run_screen(allow=False);self.assertEqual(0,r['readingPriority']['cacheHits'])

    def test_prompt_change_invalidates_only_advice(self):
        self.run_screen()
        def changed(items,model):
            value=payload(items,model);value['messages'][0]['content']+=' Changed policy.';return value
        with patch('literature_relevance.payload',side_effect=changed):
            out,r=self.run_screen(allow=False)
        self.assertEqual(0,r['readingPriority']['cacheHits']);self.assertEqual(1,self.calls)

    def test_bad_quote_never_changes_order_or_creates_reusable_advice(self):
        def wrong(*args):
            data=parse(self.transport(*args));v=parse(data['choices'][0]['message']['content'])
            v['items'][0]['citations'][0]['quote']='invented fact'
            return response(v)
        out,r=self.run_screen(transport=wrong)
        self.assertEqual(self.candidates,out);self.assertEqual('PARTIAL',r['readingPriority']['status'])
        self.assertFalse((self.root/'budget/reading-advice').exists())
        self.assertIn('DENOVO_BUNDLE_RELEVANCE_QUOTE_NOT_IN_SOURCE',r['readingPriority']['issues'])

    def test_failure_stops_after_one_request_and_keeps_every_candidate(self):
        def fail(*unused): self.calls+=1;raise ValueError('DENOVO_HTTP_429')
        out,r=self.run_screen(transport=fail,settings={'batchSize':1,'maximumCalls':8})
        self.assertEqual(1,self.calls);self.assertEqual(self.candidates,out)
        self.assertEqual(1,r['modelCalls']);self.assertGreater(r['chargedOrReservedMicroCreditsThisRun'],0)
        out,r=self.run_screen(transport=fail);self.assertEqual(1,self.calls)

    def test_missing_abstract_and_conflict_cannot_be_promoted_or_excluded(self):
        items=material_items(self.candidates,self.targets,self.work,('PP1',),[])
        items[0]['paper']['abstract']='';items[1]['identityState']='CONFLICT'
        value={'schemaVersion':VERSION,'items':[{'id':i['id'],'priority':'LOW' if n==0 else 'HIGH',
            'reason':'model suggestion','hints':[], 'citations':[{'field':'title','quote':i['paper']['title']}]} for n,i in enumerate(items)]}
        rows=validate(value,items)
        self.assertEqual(['UNRESOLVED','UNRESOLVED'],[r['priority'] for r in rows.values()])

    def test_identity_conflict_is_program_owned_and_alias_is_not_exact(self):
        self.candidates[1]['matches'][0]['litvarLinks']=[{'discoveryRelation':'GENE_CONFLICT'}]
        out,r=self.run_screen()
        self.assertEqual(['ALIAS_MATCH_ONLY','CONFLICT'],[i['identityState'] for i in r['readingPriority']['items']])
        self.assertEqual(['PMC1','PMC2'],[p['pmcid'] for p in out])

    def test_wrong_ids_duplicate_ids_and_unquoted_high_rejected(self):
        items=material_items(self.candidates,self.targets,self.work,('PM3',),[])
        value={'schemaVersion':VERSION,'items':[{'id':i['id'],'priority':'HIGH','reason':'r','hints':[],'citations':[]} for i in items]}
        with self.assertRaisesRegex(ValueError,'CITATION_REQUIRED'):validate(value,items)
        value['items'][1]['id']=value['items'][0]['id']
        with self.assertRaisesRegex(ValueError,'TARGET_MISMATCH'):validate(value,items)

    def test_batched_limit_and_timeout_leave_unreviewed_items(self):
        out,r=self.run_screen(settings={'batchSize':1,'maximumCalls':1})
        self.assertEqual(1,self.calls);self.assertEqual(2,len(out))
        self.assertEqual('PARTIAL',r['readingPriority']['status'])
        self.assertEqual('UNRESOLVED',r['readingPriority']['items'][1]['priority'])

    def test_program_budget_and_no_model_still_preserve_all(self):
        for bad in (False, [], {'maximumPrefetch':51},{'batchSize':13},{'maximumCalls':True},{'maximumDeepRead':21}):
            with self.assertRaises(ValueError):options(bad)
        out,r=self.run_screen(allow=False)
        self.assertEqual(self.candidates,out);self.assertEqual(0,self.calls)

    def test_metadata_only_no_local_input_or_acmg_decision_in_request(self):
        items=material_items(self.candidates,self.targets,self.work,('PS2',),[{'sample':'secret'}])
        request=canonical(payload([{k:v for k,v in i.items() if not k.startswith('_')} for i in items],self.config))
        self.assertNotIn(b'secret',request);self.assertNotIn(b'_paperKey',request)
        self.assertIn(b'NOT read the full text',request)

    def test_unknown_gets_fifth_slot_and_needs_only_reorder_not_supply_facts(self):
        papers=[self.paper(i) for i in range(1,9)]
        report={'items':[{'paperKey':p['pmcid'],'priority':'HIGH' if n<7 else 'UNRESOLVED',
                         'identityState':'ALIAS_MATCH_ONLY','hints':['PHASE'] if n==3 else []} for n,p in enumerate(papers)]}
        queue=papers;read=[]
        for i in range(5):
            queue=order(queue,report,exploration_slot=4-i);read.append(queue.pop(0)['pmcid'])
        self.assertEqual('PMC8',read[-1])
        self.assertEqual('PMC4',order(papers,report,{'PHASE'})[0]['pmcid'])
        self.assertEqual({'PHASE'},missing_topics({'needReading':{'cases':[{'feedback':{'missingFields':['PHASE']}}]}}))

    def test_prefetched_unread_not_marked_attempted(self):
        found={'candidates':self.candidates,'checkpoint':{},'resumeAvailable':False,'queries':[], 'status':'COMPLETE','targets':[]}
        acquired={'papers':[{'pmcid':p['pmcid'],'status':'REGISTERED'} for p in self.candidates]}
        persist(self.work,{'genes':['GENE1']},found,acquired,pending_model={'PMC2'})
        state=parse((self.work/'discovery-state.json').read_bytes())
        self.assertEqual(['PMC1'],state['attemptedPaperKeys']);self.assertEqual('PMC2',state['pendingCandidates'][0]['pmcid'])

    def test_acquisition_transport_budget_defers_paper_instead_of_losing_it(self):
        found={'candidates':self.candidates,'checkpoint':{},'resumeAvailable':False,'queries':[], 'status':'COMPLETE','targets':[]}
        acquired={'papers':[{'pmcid':p['pmcid'],'status':'UNAVAILABLE','reasonCode':'PAPER_DOWNLOAD_REQUEST_BUDGET'} for p in self.candidates]}
        persist(self.work,{'genes':['GENE1']},found,acquired)
        state=parse((self.work/'discovery-state.json').read_bytes())
        self.assertEqual([],state['attemptedPaperKeys']);self.assertEqual(2,len(state['pendingCandidates']))

    def test_normal_workflow_prefetches_twenty_but_reads_five_and_keeps_rest(self):
        from public_literature_workflow import run, VERSION as workflow_version
        root=self.root;local=root/'local';local.mkdir();atomic(local/'model.json',self.config)
        cfg={'schemaVersion':workflow_version,'enabled':True,'projectRoot':str(root),
            'library':'local/library','searchCache':'local/cache','modelBudget':'local/budget',
            'modelConfig':'local/model.json','allowModel':False,'allowPublicNetwork':False}
        atomic(root/'workflow.json',cfg)
        request={'schemaVersion':workflow_version,'genes':['GENE1'],'selectionSha256':'a'*64}
        atomic(root/'request.json',request)
        candidates=[self.paper(i) for i in range(1,21)]
        found={'candidates':candidates,'checkpoint':{},'resumeAvailable':False,'queries':[],
               'status':'COMPLETE','targets':[],'networkRequests':0}
        acquired={'networkRequests':0,'papers':[{'pmcid':p['pmcid'],'status':'REGISTERED',
                    'registration':{'bundleSha256':'f'*64}} for p in candidates]}
        def process(*args,**kwargs):
            item={'pmcid':args[1],'issues':[],'completeTargetExtraction':False}
            args[7]['papers'].append(item);return item
        output=local/'job';output.mkdir()
        with patch('public_literature_workflow.discover',return_value=found), \
             patch('public_literature_workflow.acquire',return_value=acquired) as acquire, \
             patch('public_literature_workflow.process_paper',side_effect=process):
            value=run(root/'request.json',output/'result.json',root/'workflow.json')
        self.assertEqual(20,acquire.call_args.kwargs['maximum'])
        self.assertEqual(5,len(value['papers']));self.assertEqual(15,value['discoveryState']['pendingPapers'])

    def test_large_target_pool_preserved_with_bounded_advice_artifact(self):
        candidates=[self.paper(i) for i in range(1,202)]
        out,r=self.run_screen(candidates,allow=False)
        self.assertEqual(201,len(out));self.assertEqual(200,len(r['readingPriority']['items']))
        self.assertEqual(201,r['readingPriority']['totalTargetPaperPairs'])
        self.assertEqual('PARTIAL',r['readingPriority']['status'])


if __name__ == '__main__': unittest.main()
