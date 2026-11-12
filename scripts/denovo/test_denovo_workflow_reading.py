"""Actual existing paper workflow plus continuation; synthetic transport only."""
from pathlib import Path
import time
import unittest
import shutil
from unittest.mock import patch

import test_denovo_reading_loop as fixtures
from model_literature_contract import canonical,parse,digest
from core_context_review import load_config
from continue_literature_paper import process_paper
from denovo_reading_loop import run as real_loop
from bundle_candidate_intake import replay_intake


class WorkflowReadingTests(unittest.TestCase):
    def setUp(self):
        self.fixture=fixtures.LoopTests();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.fixture.need_source();self.fixture.seed(True)
        self.counter=0

    def step(self,allow=True,profile='core-material-v2'):
        f=self.fixture;self.counter+=1;work=f.root/('job-'+str(self.counter));work.mkdir()
        result={'papers':[],'modelCalls':0,'modelAttempts':0,'estimatedMicroCreditsThisRun':0,
                'chargedOrReservedMicroCreditsThisRun':0,'issues':[]}
        context,pin=load_config(f.root,f.context)
        opts={'targets':[{'geneSymbol':'EXAMPLE','allele':{'genomeAssembly':'GRCh38','chromosome':'1',
                 'position':123,'reference':'A','alternate':'G'},'variantAliases':['c.123A>G','p.T41A']}],
              'contextSpec':{'path':f.context['path'],'sha256':f.context['sha256']},'maximumCalls':8,'maximumChunks':8}
        def save():(work/'progress.json').write_bytes(canonical(result))
        def run(*a,**kw):return real_loop(*a,**kw,transport=f.transport)
        with patch('denovo_workflow_reading.read_loop',side_effect=run):
            item=process_paper(f.library,f.source['pmcid'],f.source['bundleSha256'],f.config,
                f.config_path,f.budget,work,result,save,time.monotonic()+120,allow,
                extraction_profile=profile,source_review=True,
                core_context={'root':f.root,'config':context,'sha256':pin},need_reading=opts)
        return item,result,work

    def test_existing_paper_job_automatically_fills_need_then_cache_reuses(self):
        item,result,work=self.step()
        self.assertEqual('MATERIAL_READY',item['needReading']['status'],item['needReading'])
        self.assertEqual(2,result['modelCalls']);self.assertEqual(2,len(item['chunks']))
        self.assertEqual(1,item['coreEvaluation']['readyCases'])
        intake=parse((work/item['candidateIntake']['path']).read_bytes())
        f=self.fixture
        facts=replay_intake(f.source['raw'],f.source['pmcid'],digest(f.source['raw']),intake,
                            work_root=f.root,core_context=f.context)
        self.assertEqual('READY_FOR_EVALUATION',facts['cases'][0]['coreEvaluation']['status'])
        self.assertEqual(facts['cases'][0]['alleleIdentity']['issues'],
                         item['cumulativeCases']['cases'][0]['alleleIdentityIssues'])
        again,second,_=self.step(False)
        self.assertEqual(0,second['modelCalls'])
        self.assertEqual('MATERIAL_READY',again['needReading']['status'])

    def test_disabled_retains_missing_reasons_and_original_intake(self):
        item,result,work=self.step(False)
        self.assertEqual('PARTIAL',item['needReading']['status'])
        self.assertEqual(0,result['modelCalls']);self.assertTrue(item['candidateIntake'])
        self.assertTrue(item['needReading']['cases'][0]['feedback']['needs'])
        self.assertTrue(item['continuation']['eligible'])

    def test_resume_disabled_job_from_exact_cache_does_not_repeat_paid_calls(self):
        first,_,_=self.step(False)
        self.assertEqual('PARTIAL',first['needReading']['status'])
        ready,paid,_=self.step(True)
        self.assertEqual('MATERIAL_READY',ready['needReading']['status'])
        self.assertEqual(2,paid['modelCalls'])
        recovered,repeat,_=self.step(False)
        self.assertEqual(0,repeat['modelCalls'])
        self.assertEqual(ready['candidateIntake']['sha256'],recovered['candidateIntake']['sha256'])

    def test_existing_v1_materials_are_not_forced_to_paid_reextraction(self):
        with patch('test_denovo_reading_loop.PROFILE','core-material-v1'):
            self.fixture=fixtures.LoopTests();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
            self.fixture.need_source();self.fixture.seed(True)
            item,result,_=self.step(profile='core-material-v1')
            self.assertEqual('MATERIAL_READY',item['needReading']['status'])
            self.assertEqual(2,result['modelCalls'])
            again,cached,_=self.step(False,profile='core-material-v1')
            self.assertEqual('MATERIAL_READY',again['needReading']['status'])
            self.assertEqual(0,cached['modelCalls'])

    def test_full_worker_entry_reuses_cache_and_reports_material_readiness(self):
        self.step();f=self.fixture
        shutil.copytree(f.library,f.root/'local/library')
        shutil.copytree(f.budget,f.root/'local/budget')
        config={'schemaVersion':'denovo-public-literature-workflow-v1','enabled':True,
            'allowPublicNetwork':False,'allowModel':False,'needDrivenReading':True,
            'extractionProfile':'core-material-v2','sourceReview':True,'projectRoot':str(f.root),
            'coreContext':{'path':f.context['path'],'sha256':f.context['sha256']},
            'library':'local/library','searchCache':'local/search','modelBudget':'local/budget',
            'modelConfig':'local/model.json'}
        (f.root/'local/model.json').write_bytes(canonical(f.config))
        (f.root/'workflow.json').write_bytes(canonical(config))
        request={'schemaVersion':config['schemaVersion'],'selectionSha256':'a'*64,'genes':['EXAMPLE'],
            'searchTargets':[{'geneSymbol':'EXAMPLE','allele':{'genomeAssembly':'GRCh38','chromosome':'1',
                'position':123,'reference':'A','alternate':'G'},'variantAliases':['c.123A>G','p.T41A']}],
            'continuation':{'parentTaskId':'b'*64,'parentResultSha256':'c'*64,'pmcid':f.source['pmcid'],
                'bundleSha256':f.source['bundleSha256'],'plannedChunks':len(f.packets),
                'completedTargets':[{'chunkIndex':f.seed_index,'artifactSha256':digest(f.seeds[0][0].read_bytes())}]}}
        (f.root/'request.json').write_bytes(canonical(request));job=f.root/'local/full-worker';job.mkdir()
        from public_literature_workflow import run
        result=run(f.root/'request.json',job/'result.json',f.root/'workflow.json',120)
        self.assertEqual('REVIEW_READY',result['status'],result['issues'])
        self.assertEqual(0,result['modelCalls']);self.assertEqual(0,result['networkRequests'])
        self.assertEqual('MATERIAL_READY',result['papers'][0]['needReading']['status'])
        self.assertFalse(result['papers'][0]['completeTargetExtraction'])


if __name__=='__main__':unittest.main()
