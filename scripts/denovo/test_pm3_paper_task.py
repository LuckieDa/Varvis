import subprocess
import sys
from pathlib import Path
import unittest
import test_bundle_model
from model_literature_contract import canonical,parse,digest
from run_pm3_paper_task import execute


class Task(unittest.TestCase):
    def test_automatic_partner_supply_is_in_normal_task_and_network_switch_is_preserved(self):
        from unittest.mock import patch,Mock
        from test_pm3_clinvar_supply import RAW,ALLELE
        root=self.f.root;(root/'local/record.xml').write_bytes(RAW)
        spec={'path':'local/record.xml','sha256':digest(RAW),'accession':'VCV000000123'}
        c=parse(self.config.read_bytes());c.update(coreContext={},pm3PartnerSources={'cache':'local/clinvar-cache'})
        c['coreContext']={'configured':True};self.config.write_bytes(canonical(c))
        path,pin=self.write()
        supply=Mock();supply.queries=1
        supply.lookup.return_value={'status':'EXACT_RECORDS_ACQUIRED','records':[spec],'issues':[]}
        row={'status':'LOCAL_IDENTITIES_VERIFIED','identities':{'partner':{'allele':ALLELE},
             'target':{'allele':{**ALLELE,'position':102}}},
             'reportedObservation':{'geneSymbol':'TEST'},'diseaseContext':{'qualification':'VERIFIED','diseaseId':'MONDO:0000001'}}
        def worker(*args,**kwargs):
            args[7].mkdir();return {'status':'PARTIAL','modelCalls':0,'observations':{'cases':[{'resolvedObservations':[row]}]}}
        with patch('run_pm3_paper_task.process',side_effect=worker), \
                patch('core_context_review.load_config',return_value=({'identity':{}},None)), \
                patch('core_local_identity.LocalIdentity'),patch('pm3_clinvar_supply.ClinVarSupply',return_value=supply) as factory:
            result=execute(path,pin,self.config,root/'local/task-out')
            self.assertFalse(factory.call_args.args[3].online)
        self.assertEqual('VERIFIED',row['partnerQualification']['independentClassification'])
        self.assertEqual('BOUNDED_EXACT_SOURCE_TARGET_EXCLUSION_V1',row['partnerQualification']['dependencyGraph']['method'])
        self.assertEqual(0,result['partnerAcquisition']['networkRequests'])
        self.assertEqual(1,result['partnerAcquisition']['localQueries'])
        self.assertEqual('EXACT_SOURCE_ASSERTIONS_AVAILABLE',row['partnerClassificationSources']['status'])

    def test_reason_codes_survive_but_private_exception_details_do_not(self):
        from public_literature_workflow import safe_error
        self.assertEqual('PM3_TRANSCRIPT_CONFLICT',safe_error(ValueError('PM3_TRANSCRIPT_CONFLICT')))
        for text in ('PM3_FAILED https://example.invalid/?key=secret','PM3_C:\\private',
                     'PM3_'+'A'*157,'PM3_秘密','PM3_bad'):
            self.assertEqual('DENOVO_WORKFLOW_EXECUTION_FAILED',safe_error(ValueError(text)))

    def setUp(self):
        self.f=test_bundle_model.BundleModelTests();self.f.setUp();self.addCleanup(self.f.doCleanups)
        f=self.f;(f.root/'local').mkdir();f.library.rename(f.root/'local/library')
        index=f.root/'local/library/evidence/PS2/index.json'
        row=parse(index.read_bytes());row['criterion']='PM3'
        folder=index.parent.parent/'PM3';folder.mkdir();(folder/'index.json').write_bytes(canonical(row))
        (f.root/'local/model.json').write_bytes(canonical(f.c))
        self.config=f.root/'local/workflow.json'
        self.config.write_bytes(canonical({'schemaVersion':'denovo-public-literature-workflow-v1','enabled':True,
            'projectRoot':str(f.root),'library':'local/library','modelBudget':'local/shared-budget',
            'modelConfig':'local/model.json','allowModel':False}))
        self.request={'schemaVersion':'pm3-public-paper-task-v1','pmcid':'PMC123','bundleSha256':f.pin,
            'target':{'geneSymbol':'GENE1','variantAliases':['c.1A>G']},'selectionSha256':'a'*64,
            'selectedAllele':{'assembly':'GRCh38','contig':'1','position':1,'reference':'A','alternate':'G'}}
    def write(self):
        path=self.f.root/'local/request.json';raw=canonical(self.request);path.write_bytes(raw)
        return path,digest(raw)
    def test_actual_cli_without_cache_is_explicit_and_never_reads_key(self):
        path,pin=self.write();out=self.f.root/'local/output'
        result=subprocess.run([sys.executable,str(Path(__file__).with_name('run_pm3_paper_task.py')),
            '--request',str(path),'--request-sha256',pin,'--config',str(self.config),'--output',str(out)],
            capture_output=True,timeout=20,text=True)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('PARTIAL',parse(result.stdout)['status'])
        saved=parse((out/'result.json').read_bytes())
        self.assertEqual(0,saved['modelCalls']);self.assertEqual(pin,saved['requestSha256'])
        self.assertFalse((self.f.root/'local/shared-budget/budget.json').exists())
    def test_local_pass_replays_registered_review_with_both_live_switches_enabled(self):
        from copy import deepcopy
        from unittest.mock import patch
        from literature_manual_review import register
        from pm3_literature_contract import VERSION
        from pm3_reviewed_material import reviewed_papers

        library=self.f.root/'local/library'
        candidate=self.f.candidate()
        candidate['schemaVersion']=VERSION
        candidate['cases'][0]['fields'][0]['field']='targetVariant'
        review={'schemaVersion':'public-literature-manual-review-v1','criterion':'PM3',
                'binding':{'stage':'SOURCE_REVIEW',**{k:self.request[k] for k in ('target','pmcid','bundleSha256')}},
                'review':{'reviewedBy':'source curator','recordSha256':'b'*64,'basis':'Original table row checked'},
                'material':candidate}
        bad=deepcopy(review)
        bad['material']['cases'][0]['fields'][0]['citation']['quote']='not in the original source'
        with self.assertRaises(ValueError):
            register(library,bad,digest(canonical(bad)))
        review_pin=digest(canonical(review))
        entry=register(library,review,review_pin)
        self.assertEqual(entry,register(library,review,review_pin))
        self.assertEqual(({},[]),reviewed_papers(library,{'geneSymbol':'OTHER','variantAliases':['c.1A>G']}))
        self.request['schemaVersion']='pm3-public-discovery-task-v1'
        self.request.pop('pmcid');self.request.pop('bundleSha256')
        cfg=parse(self.config.read_bytes())
        cfg.update(searchCache='local/search-cache',allowModel=True,allowPublicNetwork=True)
        self.config.write_bytes(canonical(cfg))
        path,pin=self.write()
        with patch('process_pm3_discovery.discover',side_effect=AssertionError('public search')), \
                patch('run_pm3_paper_task.read_model_config',side_effect=AssertionError('model enabled')), \
                patch('process_pm3_paper.run',side_effect=AssertionError('empty cache replay')):
            result=execute(path,pin,self.config,self.f.root/'local/first-pass',local_first_pass=True)
        self.assertEqual((0,0),(result['modelCalls'],result['networkRequests']))
        self.assertEqual(review_pin,result['papers'][0]['manualReviews'][0]['artifactSha256'])
        self.assertEqual(1,len(result['papers'][0]['observations']['cases']))
        self.assertFalse(result['papers'][0]['observations']['allChunksProcessed'])
        entry.write_bytes(canonical(bad))
        self.assertEqual('LITERATURE_MANUAL_REGISTERED_BYTES_CHANGED',
                         reviewed_papers(library,self.request['target'])[1][0]['reasonCode'])

    def test_private_fields_or_free_text_alias_refused_before_processing(self):
        self.request['target']['sampleId']='private'
        path,pin=self.write()
        with self.assertRaisesRegex(ValueError,'TARGET_SCHEMA'):execute(path,pin,self.config,self.f.root/'local/out')
        self.request['target'].pop('sampleId');self.request['target']['variantAliases']=['patient has seizures']
        path,pin=self.write()
        with self.assertRaisesRegex(ValueError,'TARGET_SCHEMA'):execute(path,pin,self.config,self.f.root/'local/out')
    def test_request_pin_and_output_scope(self):
        path,pin=self.write()
        with self.assertRaisesRegex(ValueError,'PIN_MISMATCH'):execute(path,'0'*64,self.config,self.f.root/'local/out')
        with self.assertRaisesRegex(ValueError,'LOCAL_OUTPUT'):execute(path,pin,self.config,self.f.root/'outside')

    def test_continuation_lineage_is_preserved_without_accepting_old_scores(self):
        self.request['processingContinuation']={'parentTaskId':'b'*64,'parentResultSha256':'c'*64}
        path,pin=self.write()
        result=execute(path,pin,self.config,self.f.root/'local/continued')
        origin=result['reassemblyOrigin']
        self.assertEqual('b'*64,origin['parentTaskId'])
        self.assertEqual('c'*64,origin['parentResultSha256'])
        self.assertFalse(origin['savedScoreAdopted']);self.assertEqual(0,result['modelCalls'])
        self.assertFalse((self.f.root/'local/shared-budget/budget.json').exists())
        self.request['processingContinuation']['oldScore']=1
        path,pin=self.write()
        with self.assertRaisesRegex(ValueError,'PM3_PROCESSING_CONTINUATION_INVALID'):
            execute(path,pin,self.config,self.f.root/'local/rejected')

    def test_configured_new_chunk_budget_is_bounded_and_forwarded(self):
        from unittest.mock import patch
        path,pin=self.write();c=parse(self.config.read_bytes())
        c['pm3MaximumNewChunksPerPaper']=1;self.config.write_bytes(canonical(c))
        def worker(*args,**kwargs):
            args[7].mkdir()
            return {'status':'PARTIAL','modelCalls':0}
        with patch('run_pm3_paper_task.process',side_effect=worker) as process:
            execute(path,pin,self.config,self.f.root/'local/output')
            self.assertEqual(1,process.call_args.kwargs['maximum_chunks'])
        for bad in (0,257,True,1.5,'1'):
            c['pm3MaximumNewChunksPerPaper']=bad;self.config.write_bytes(canonical(c))
            with self.assertRaisesRegex(ValueError,'PM3_CHUNK_BUDGET'):
                execute(path,pin,self.config,self.f.root/'local/unused')

    def test_discovery_request_dispatch_preserves_selection_receipts(self):
        from unittest.mock import patch
        self.request['schemaVersion']='pm3-public-discovery-task-v1'
        self.request.pop('pmcid');self.request.pop('bundleSha256')
        c=parse(self.config.read_bytes());c['searchCache']='local/search-cache';self.config.write_bytes(canonical(c))
        path,pin=self.write()
        found={'status':'DISCOVERY_ONLY_NOT_QUALIFIED','checkpoint':{'queries':[],'implementation':__import__('discover_public_literature').implementation_pin()},'networkRequests':0,'candidates':[],'resumeAvailable':False,'targets':[{'status':'NO_HITS_IN_EXECUTED_QUERIES'}]}
        with patch('process_pm3_discovery.discover',return_value=found):
            result=execute(path,pin,self.config,self.f.root/'local/discovery-output')
        self.assertEqual('pm3-discovery-process-v1',result['schemaVersion'])
        self.assertEqual(pin,result['requestSha256']);self.assertEqual('a'*64,result['selectionSha256'])
        self.assertEqual(0,result['modelCalls']);self.assertFalse(result['businessPublishable'])

if __name__=='__main__':unittest.main()
