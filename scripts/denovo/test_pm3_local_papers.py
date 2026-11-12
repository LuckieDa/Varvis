"""Offline source routing; retrieval hits must never become case/score proof."""
import unittest
from unittest.mock import patch
from test_pm3_literature_contract import Runner
from model_literature_contract import canonical, parse
from pm3_local_papers import discover


class LocalPapers(unittest.TestCase):
    def setUp(self):
        self.f=Runner();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.x=self.f.fixture
        self.target={'geneSymbol':'GENE1','variantAliases':['p.Arg1Gly']}

    def test_pinned_supplement_match_is_only_retrieval_hint(self):
        row=discover(self.x.library,self.target)['candidates'][0]
        self.assertEqual('PMC123',row['pmcid'])
        self.assertEqual(self.x.pin,row['localReviewedBundleSha256'])
        self.assertNotIn('score',row)
        self.assertTrue(row['origins'][0]['matchedUnitIds'])

    def test_other_gene_or_notation_never_reuses_candidate(self):
        for target in ({**self.target,'geneSymbol':'OTHER'},
                       {**self.target,'variantAliases':['c.11A>G']},
                       {**self.target,'variantAliases':['c.1A>T']}):
            self.assertEqual([],discover(self.x.library,target)['candidates'])

    def test_changed_source_is_rejected_and_disclosed(self):
        with patch('pm3_local_papers.load',side_effect=ValueError('SOURCE_PIN_MISMATCH')):
            result=discover(self.x.library,self.target)
        self.assertFalse(result['complete']);self.assertEqual([],result['candidates'])
        self.assertEqual('SOURCE_PIN_MISMATCH',result['issues'][0]['reasonCode'])

    def test_ambiguous_bundles_not_arbitrarily_selected(self):
        p=self.x.library/'evidence/PM3/index.json';v=parse(p.read_bytes())
        v['documents'][0]['acquisitionBundles']*=2;p.write_bytes(canonical(v))
        self.assertEqual([],discover(self.x.library,self.target)['candidates'])

    def test_offline_discovery_reaches_existing_paper_processor(self):
        from process_pm3_discovery import run
        found={'status':'DISCOVERY_ONLY_NOT_QUALIFIED','checkpoint':{'queries':[],
            'implementation':__import__('discover_public_literature').implementation_pin()},
            'networkRequests':0,'candidates':[],'resumeAvailable':True,'targets':[]}
        with patch('process_pm3_discovery.discover',return_value=found):
            result=run(self.x.root,self.x.library,self.x.root/'cache',self.x.root/'budget',
                self.x.c,self.x.config,self.target,
                {'assembly':'GRCh38','contig':'1','position':1,'reference':'A','alternate':'G'},
                self.x.root/'offline-routing',stepwise=True)
        self.assertEqual(1,len(result['papers']),result['issues'])
        self.assertEqual('VERIFIED_REGISTERED_PINNED_BUNDLE_REUSE',result['acquisition']['papers'][0]['sourceKind'])
        self.assertEqual(0,result['networkRequests']);self.assertEqual(0,result['modelCalls'])
        self.assertFalse(result['businessPublishable'])
        self.assertIn('MODEL_CACHE_MISSING',result['papers'][0]['issues'])


if __name__=='__main__':unittest.main()
