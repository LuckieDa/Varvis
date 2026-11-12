"""Discovery orchestration tests; synthetic retrieval, no live network/model."""
from copy import deepcopy
from pathlib import Path
import unittest
from unittest.mock import patch

from model_literature_contract import canonical, digest, parse
from model_transport import atomic
from process_pp1_discovery import run
import test_process_pp1_paper as fixtures
from test_pp1_reading import Resolver
from test_pp1_segregation import context


class Discovery(unittest.TestCase):
    def test_manual_review_is_idempotent_and_rejects_changed_source_locator(self):
        from bundle_model_contract import cite
        from literature_manual_review import VERSION, entries, register
        f = self.f
        target = self.p.binding['selection']
        source = cite(f.source, {'unitId':f.seed['unitId'],'quote':f.seed['text']})
        review = {'schemaVersion':VERSION,'criterion':'PP1',
                  'binding':{'stage':'SOURCE_REVIEW','pmcid':'PMC123','bundleSha256':f.pin,'target':f.target},
                  'review':{'reviewedBy':'Fixture reviewer','recordSha256':'a'*64,'basis':'Public source review'},
                  'material':{'scope':{k:target[k] for k in ('geneSymbol','diseaseId','inheritance','allele')},
                              'families':{'F1':['I-1']},'observations':[{'family':'F1','member':'I-1',
                                  'kind':'proband','value':True,'sourceRefs':[source]}]}}
        pin = digest(canonical(review))
        self.assertEqual(register(f.library,review,pin),register(f.library,review,pin))
        self.assertEqual(1,len(list(entries(f.library,'PP1'))))
        changed = deepcopy(review)
        changed['material']['observations'][0]['sourceRefs'][0]['start'] += 1
        with self.assertRaisesRegex(ValueError,'TEXT_CITATION_CHANGED'):
            register(f.library,changed,digest(canonical(changed)))

    def test_pinned_cache_cannot_fall_back_to_rebuilt_source_when_pin_missing(self):
        from acquire_public_literature import acquire
        self.acquire.side_effect=acquire
        self.found['candidates']=[{'pmcid':'PMC123','localReviewedBundleSha256':'a'*64}]
        result=self.execute()
        self.assertEqual([],result['papers'])
        self.assertNotEqual('REGISTERED',result['acquisition']['papers'][0]['status'])
        self.assertEqual(0,result['networkRequests']);self.assertEqual(0,result['modelAttempts'])
    def test_sufficient_local_review_avoids_litvar_even_when_network_is_allowed(self):
        from pp1_reviewed_cache import register
        seeded = self.f.loop(self.f.seed_artifacts(),execute=True)
        register(self.f.library,self.f.root/'budget',seeded['acceptedArtifacts'])
        self.f.target['variantAliases'].append('c.*200A>G')
        result=self.execute(allow_network=True,allow_model=True)
        self.assertEqual(['PMC123'],[p['pmcid'] for p in result['localCacheDiscovery']['papers']])
        self.assertEqual('EVALUATED',result['papers'][0]['status'])
        self.assertEqual(self.f.pin,result['papers'][0]['bundleSha256'])
        self.assertEqual(0,result['modelAttempts']);self.assertEqual(0,result['networkRequests'])
        self.search.assert_not_called();self.acquire.assert_not_called()

    def test_only_nonliterature_blocker_with_sufficient_local_facts_stops_search(self):
        from process_pp1_discovery import local_stop
        evaluation={'status':'NOT_EVALUATED','rawSegregationPoints':'1','context':{'status':'QUALIFIED'},'needs':[
            {'blockingEffect':'BLOCKS_SCORING','route':'BUSINESS_CONTEXT'},
            {'blockingEffect':'MAY_ADD_EVENT','route':'LITERATURE'}]}
        self.assertEqual(local_stop(evaluation),'PP1_LOCAL_FACTS_READY_FOR_CURRENT_ALLOCATION')
        self.assertIsNone(local_stop({**evaluation,'rawSegregationPoints':'0.5'}))
        evaluation['needs'].append({'blockingEffect':'BLOCKS_SCORING','route':'LITERATURE'})
        self.assertIsNone(local_stop(evaluation))
    def setUp(self):
        self.p=fixtures.PaperTask();self.p.setUp();self.addCleanup(self.p.doCleanups)
        self.f=self.p.fixture
        self.candidates=[{'pmcid':'PMC123','pmid':'123'}, {'pmcid':'PMC456','pmid':'456'}]
        self.found={'status':'COMPLETE','candidates':deepcopy(self.candidates),'targets':[],
                    'queries':[],'checkpoint':{'queries':[]},'networkRequests':0,'resumeAvailable':False}
        self.acquired={'networkRequests':0,'papers':[{'pmcid':'PMC123','status':'REGISTERED',
                        'registration':{'bundleSha256':self.f.pin}},
                       {'pmcid':'PMC456','status':'UNAVAILABLE','reasonCode':'PAPER_ACQUISITION_PAPER_BUDGET'}]}
        self.search=self.enterContext(patch('process_pp1_discovery.discover',return_value=self.found))
        self.acquire=self.enterContext(patch('process_pp1_discovery.acquire',return_value=self.acquired))

    def item(self, *args, **kwargs):
        folder=Path(args[10]);folder.mkdir(parents=True)
        atomic(folder/'artifacts.json',{'acceptedArtifacts':[]})
        return {'schemaVersion':'pp1-paper-process-v1','pmcid':args[1],'bundleSha256':args[2],
                'target':args[3],'status':'EVALUATED','scientificExecution':True,'businessPublishable':False,
                'modelAttempts':0,'evaluation':{'status':'NOT_EVALUATED'},'stopReason':'EXTERNAL_REQUIREMENT',
                'artifactManifestSha256':digest((folder/'artifacts.json').read_bytes())}

    def execute(self, **kwargs):
        f=self.f
        return run(f.root,f.library,f.root/'search-cache',f.root/'budget',f.config,f.cfg,f.target,self.p.binding,
                   context(),Resolver(),f.root/'discovery-output',**kwargs)

    def test_litvar_only_pp1_registration_and_unread_papers_retained(self):
        with patch('process_pp1_discovery.process',side_effect=self.item): result=self.execute()
        self.assertEqual('LITVAR_ONLY',self.search.call_args.kwargs['search_mode'])
        self.assertEqual(('PP1',),self.acquire.call_args.kwargs['criteria'])
        self.assertFalse(self.search.call_args.kwargs['online'])
        state=parse((self.f.root/'discovery-output/discovery-state.json').read_bytes())
        self.assertEqual(['PMC123'],state['attemptedPaperKeys'])
        self.assertEqual(['PMC456'],[p['pmcid'] for p in state['pendingCandidates']])
        self.assertFalse(result['wholeLiteratureAbsenceClaim'])
        self.assertEqual(0,result['modelAttempts'])

    def test_cache_only_real_paper_path_does_not_fake_no_evidence(self):
        result=self.execute()
        self.assertEqual('PP1_MODEL_DISABLED_CACHE_MISSING',result['papers'][0]['stopReason'])
        self.assertEqual(['PMC123'],result['pendingRegisteredPapers'])
        self.assertEqual(2,result['discoveryState']['pendingPapers'])

    def test_actual_acquisition_reaches_paper_worker_without_denovo_alias(self):
        from acquire_public_literature import acquire
        from bundle_model_contract import load
        source=load(self.f.library,'PMC123',self.f.pin,criterion='PP1')
        self.found['candidates']=[{'pmcid':'PMC123','pmid':source['main']['identifiers']['pmid']}]
        self.acquire.side_effect=acquire
        result=self.execute()
        self.assertEqual('REGISTERED',result['acquisition']['papers'][0]['status'])
        self.assertEqual('PP1_MODEL_DISABLED_CACHE_MISSING',result['papers'][0]['stopReason'])
        self.assertEqual(0,result['networkRequests'])
        self.assertEqual(0,result['modelAttempts'])

    def test_paper_limit_checked_before_spending_discovery_budget(self):
        from acquire_public_literature import MAX_PAPERS
        with self.assertRaisesRegex(ValueError,'PP1_PAPER_BUDGET_INVALID'):
            self.execute(maximum_papers=MAX_PAPERS+1)
        self.search.assert_not_called();self.acquire.assert_not_called()

    def test_current_budget_shared_across_papers(self):
        self.acquired['papers'][1]={'pmcid':'PMC456','status':'REGISTERED','registration':{'bundleSha256':'b'*64}}
        seen=[]
        def paid(*args,**kwargs):
            seen.append(kwargs['maximum_calls']);out=self.item(*args,**kwargs);out['modelAttempts']=2;return out
        with patch('process_pp1_discovery.process',side_effect=paid):result=self.execute(maximum_calls=4)
        self.assertEqual([4,2],seen);self.assertEqual(4,result['modelAttempts'])

    def test_good_lower_bound_stops_without_discarding_second_paper(self):
        self.acquired['papers'][1]={'pmcid':'PMC456','status':'REGISTERED','registration':{'bundleSha256':'b'*64}}
        def positive(*args,**kwargs):
            out=self.item(*args,**kwargs);out['evaluation']['status']='MET';return out
        with patch('process_pp1_discovery.process',side_effect=positive) as called:result=self.execute()
        self.assertEqual(1,called.call_count)
        self.assertEqual('PP1_EVALUABLE_LOWER_BOUND_REACHED',result['stopReason'])
        self.assertEqual(['PMC456'],result['pendingRegisteredPapers'])

    def test_resume_pending_papers_skips_discovery_and_reuses_artifacts(self):
        previous={'summary':self.found,'pendingCandidates':self.candidates,'checkpoint':{'queries':[]},
                  'discoveryComplete':True,'attemptedPaperKeys':[]}
        accepted={('PMC123',self.f.pin):[self.f.root/'old-audit.json']}
        with patch('process_pp1_discovery.load_parent',return_value=previous), \
                patch('process_pp1_discovery.process',side_effect=self.item) as process:
            result=self.execute(continuation={'scope':'synthetic'},prior_artifacts=accepted)
        self.search.assert_not_called()
        self.assertEqual(accepted[('PMC123',self.f.pin)],process.call_args.kwargs['accepted_artifacts'])
        self.assertEqual(0,result['networkRequests'])

    def test_no_hits_and_incomplete_search_distinguished(self):
        self.found['candidates']=[];self.found['resumeAvailable']=True;self.acquired['papers']=[]
        result=self.execute()
        self.assertIn('PP1_DISCOVERY_SEARCH_INCOMPLETE',result['issues'])
        self.assertTrue(result['discoveryState']['resumeAvailable'])

    def test_cancel_before_search_stops_without_requests(self):
        result=self.execute(cancelled=lambda:True)
        self.search.assert_not_called();self.acquire.assert_not_called()
        self.assertEqual('PP1_USER_CANCELLED',result['stopReason'])


if __name__=='__main__':unittest.main()
