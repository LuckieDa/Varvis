"""Synthetic retrieval transport; real PM3 registration and paper processing."""
from contextlib import nullcontext
from unittest.mock import patch
import unittest
from test_literature_acquisition import FileTest,jats
from test_bundle_model import configuration
from model_literature_contract import canonical,digest
from process_pm3_discovery import run

class Discovery(FileTest):
    def test_litvar_routes_real_acquisition_to_pm3_without_model_or_ps2(self):
        candidate=self.candidate();config=configuration();config_path=self.root/'model.json'
        config_path.write_bytes(canonical(config))
        target={'geneSymbol':'GENE1','variantAliases':['c.1A>G']}
        (self.root/'local').mkdir();output=self.root/'local/run'
        allele={'assembly':'GRCh38','contig':'1','position':1,'reference':'A','alternate':'G'}
        found={'status':'DISCOVERY_ONLY_NOT_QUALIFIED','checkpoint':{'queries':[],'implementation':__import__('discover_public_literature').implementation_pin()},'networkRequests':0,'candidates':[candidate],'resumeAvailable':False,'targets':[]}
        with patch('process_pm3_discovery.discover',return_value=found) as discovery,patch(
                'process_pm3_discovery.DownloadTransport',return_value=nullcontext(self.fake(jats()))):
            result=run(self.root,self.library,self.cache,self.root/'budget',config,config_path,target,allele,output)
        self.assertEqual('LITVAR_ONLY',discovery.call_args.kwargs['search_mode'])
        self.assertFalse(discovery.call_args.kwargs['online'])
        self.assertEqual('REGISTERED',result['acquisition']['papers'][0]['status'])
        self.assertEqual('PARTIAL',result['status']);self.assertEqual(0,result['modelCalls'])
        self.assertIn('MODEL_CACHE_MISSING',result['papers'][0]['issues'])
        self.assertTrue((self.library/'evidence/PM3/index.json').exists())
        self.assertFalse((self.library/'evidence/PS2/index.json').exists())
        self.assertFalse((self.root/'budget/budget.json').exists())
        self.assertFalse(result['businessPublishable'])
        self.assertEqual(1,result['discoveryState']['pendingPapers'])
        continuation={'parentTaskId':'a'*64,'parentResultSha256':digest((output/'result.json').read_bytes()),
                      'stateSha256':result['discoveryState']['sha256'],'statePath':'local/run/discovery-state.json'}
        transport=self.fake(failure='UNEXPECTED_DOWNLOAD')
        with patch('process_pm3_discovery.discover',side_effect=AssertionError('must consume pending first')),patch(
                'process_pm3_discovery.DownloadTransport',return_value=nullcontext(transport)):
            second=run(self.root,self.library,self.cache,self.root/'budget',config,config_path,target,allele,
                       self.root/'local/next',continuation=continuation)
        self.assertEqual(0,second['networkRequests']);self.assertEqual(0,second['modelCalls'])
        self.assertEqual(1,second['discoveryState']['pendingPapers'])
        with self.assertRaisesRegex(ValueError,'CONTINUATION_PIN_MISMATCH'):
            run(self.root,self.library,self.cache,self.root/'budget',config,config_path,target,allele,
                self.root/'local/tampered',continuation={**continuation,'stateSha256':'0'*64})

    def test_no_hits_differs_from_unfinished_discovery(self):
        config=configuration();config_path=self.root/'model.json';config_path.write_bytes(canonical(config))
        for i,unfinished in enumerate((True,False)):
            found={'status':'DISCOVERY_ONLY_NOT_QUALIFIED','checkpoint':{'queries':[],'implementation':__import__('discover_public_literature').implementation_pin()},'networkRequests':0,'candidates':[],'resumeAvailable':unfinished,
                   'targets':[{'status':'SEARCH_INCOMPLETE' if unfinished else 'NO_HITS_IN_EXECUTED_QUERIES'}]}
            with patch('process_pm3_discovery.discover',return_value=found):
                result=run(self.root,self.library,self.cache,self.root/'budget',config,config_path,
                           {'geneSymbol':'GENE1','variantAliases':['c.1A>G']},
                           {'assembly':'GRCh38','contig':'1','position':1,'reference':'A','alternate':'G'},self.root/str(i))
            self.assertIn('PM3_DISCOVERY_NO_CANDIDATES_SEARCH_INCOMPLETE' if unfinished else 'PM3_NO_CANDIDATES_IN_COMPLETED_SEARCH',result['issues'])

if __name__=='__main__':unittest.main()
