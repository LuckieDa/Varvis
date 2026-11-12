"""Task2 selection controls; public-shaped synthetic sources, no live transport."""
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
from xml.sax.saxutils import escape

from model_literature_contract import canonical, digest, parse
from bundle_model_contract import requests
from bundle_model_transport import invoke, cache_identity
from run_bundle_model import identity
from test_bundle_model import configuration, response
import test_bundle_model as bundle_fixture
from test_bundle_qualification import article, registered
import test_denovo_reading_feedback as feedback_fixture
from denovo_reading_plan import plan, prepare, PROFILE


class ReadingPlanTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='denovo-plan-', dir=Path(__file__).resolve().parents[2]/'local/temp')
        self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name)
        self.config = configuration()
        self.target = {'allele':{'assembly':'GRCh38','contig':'1','position':123,'reference':'A','alternate':'G'}, 'hgncId':'HGNC:999999997',
                       'diseaseId':'MONDO:1', 'inheritance':'AD', 'geneSymbol':'EXAMPLE',
                       'variantAliases':['NM_999999997.1:c.123A>G','p.Thr41Ala']}

    def source(self, texts, suffix=''):
        body = '<body><sec><title>Results</title>' + ''.join('<p>'+escape(t)+'</p>' for t in texts) + suffix + '</sec></body>'
        raw = re.sub(rb'<body>.*</body>', body.encode(), article(), flags=re.S)
        self.library, self.s = registered(self.root, raw)
        self.key = self.s['pmcid']+'#A'
        self.feedback = {'schemaVersion':'denovo-reading-feedback-package-v1',
            'pmcid':self.s['pmcid'], 'bundleSha256':self.s['bundleSha256'], 'cases':[{
                'schemaVersion':'denovo-reading-feedback-v1',
                'bindings':{'caseKey':self.key, 'sourcePins':{'main':{
                    'role':'PAPER_PRIMARY','sha256':self.s['main']['sourceSha256']}}},
                'context':{'/context/'+k:self.target[k] for k in ('allele','hgncId','diseaseId','inheritance')},
                'needs':[]}]}
        self.needs('FAMILY')

    def needs(self, *groups, route='LITERATURE_READING'):
        self.feedback['cases'][0]['needs']=[{'caseKey':self.key,'needId':digest(g.encode()),
            'fieldPath':'/groups/'+g.lower()+'/test','group':g,'route':route} for g in groups]

    def run_plan(self, **kw):
        return plan(self.s,self.feedback,self.key,self.target,self.config,**kw)

    def text(self, result, index):
        ids=result['chunks'][index]['targetUnitIds']
        return ' '.join(u['text'] for u in self.s['units'] if u['unitId'] in ids)

    def test_missing_field_changes_order(self):
        self.source(['Case A had healthy parents.', 'Case A: SNP analysis confirmed paternity.'])
        family=self.run_plan(); self.assertIn('healthy',self.text(family,family['selectedChunks'][0]))
        self.needs('PARENTAGE');parentage=self.run_plan()
        self.assertIn('SNP',self.text(parentage,parentage['selectedChunks'][0]))
        self.assertFalse(parentage['absenceProven']);self.assertFalse(parentage['scientificExecution'])

    def test_other_case_not_rescued_by_same_variant_or_context(self):
        self.source(['Case B had healthy parents and c.123A>G.', 'Case A: family history was unknown.'])
        result=self.run_plan(limit=64)
        other=next(c for c in result['chunks'] if 'Case B' in self.text(result,c['chunkIndex']))
        self.assertEqual('OTHER_CASE_ONLY',other['reasonCode'])
        self.assertNotIn(other['chunkIndex'],result['selectedChunks'])

    def test_context_is_not_target_reading(self):
        self.source(['Administrative note.', 'Case A: family history was unknown.'])
        result=self.run_plan(limit=64)
        administrative=next(c for c in result['chunks'] if self.text(result,c['chunkIndex'])=='Administrative note.')
        self.assertEqual('NO_NEED_MATCH',administrative['reasonCode'])
        self.assertTrue(administrative['contextUnitIds'])
        self.assertEqual([],result['reviewedChunks'])

    def test_conflicting_and_negative_sources_remain_eligible(self):
        self.source(['Case A: both parents were healthy.', 'Case A: the father was affected; this contradicts the earlier pedigree.'])
        self.needs('FAMILY',route='CONFLICT_REVIEW')
        result=self.run_plan(limit=64)
        self.assertEqual(2,len(result['selectedChunks']))

    def test_unlinked_table_not_assigned_from_neighbour_header(self):
        table='<table-wrap><table><tr><th>Case A</th><th>Family history</th></tr><tr><td>B</td><td>Parents healthy</td></tr></table></table-wrap>'
        self.source([],table)
        result=self.run_plan(limit=64)
        other=next(c for c in result['chunks'] if 'Parents healthy' in self.text(result,c['chunkIndex']))
        self.assertEqual('UNLINKED_TABLE_ROW',other['reasonCode'])

    def test_external_gap_and_complete_inputs_do_not_trigger_reading(self):
        self.source(['Case A had healthy parents.'])
        self.needs('CONTEXT',route='EXTERNAL_RESOURCE');result=self.run_plan()
        self.assertEqual([],result['selectedChunks']);self.assertTrue(result['deferredNeedIds'])
        self.needs();self.assertEqual([],self.run_plan()['selectedChunks'])

    def test_alias_boundaries_and_protein_spelling(self):
        self.source(['Parents healthy, c.123A>GG.', 'Parents healthy, p.T41A.'])
        result=self.run_plan()
        rows=[c for c in result['chunks'] if c['state']=='PENDING']
        self.assertEqual([False,True],[c['variantMention'] for c in rows])
        self.assertIn('T41A',self.text(result,result['selectedChunks'][0]))

    def test_checkpoint_skips_reviewed_and_holds_failed_incomplete(self):
        self.source(['Case A: parents healthy.', 'Case A: mother healthy.', 'Case A: father healthy.'])
        old=self.run_plan(limit=64)
        entries=[{'chunkIndex':i,'requestSha256':old['chunks'][i]['requestSha256'],
                  'artifactSha256':'a'*64,'status':status} for i,status in zip(old['selectedChunks'],['REVIEWED','FAILED','INCOMPLETE'])]
        state={'schemaVersion':'denovo-reading-checkpoint-v1','scope':old['scope'],'entries':entries}
        result=self.run_plan(history=state)
        self.assertEqual([],result['selectedChunks']);self.assertEqual(1,len(result['reviewedChunks']))
        self.assertEqual(2,len(result['pendingAllChunks']));self.assertFalse(result['absenceProven'])
        state['entries'][0]['requestSha256']='b'*64
        with self.assertRaisesRegex(ValueError,'BINDING_INVALID'):self.run_plan(history=state)

    def test_stale_source_target_and_checkpoint_rejected(self):
        self.source(['Case A: parents healthy.']);old=self.run_plan()
        self.target['allele']={**self.target['allele'],'position':124}
        with self.assertRaisesRegex(ValueError,'TARGET_CHANGED'):self.run_plan()
        self.target['allele']={**self.target['allele'],'position':123}
        state={'schemaVersion':'denovo-reading-checkpoint-v1','scope':{**old['scope'],'caseKey':'other'},'entries':[]}
        with self.assertRaisesRegex(ValueError,'SCOPE_CHANGED'):self.run_plan(history=state)
        self.feedback['bundleSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):self.run_plan()

    def test_input_unchanged_and_requests_not_rewritten_for_ranking(self):
        self.source(['Case A: parents healthy.'])
        before=canonical([self.feedback,self.target])
        packets=requests(self.s,self.config['model'],self.config['maxOutputTokens'],self.config['maxRequestBytes'],PROFILE)
        result=self.run_plan()
        self.assertEqual(before,canonical([self.feedback,self.target]))
        self.assertEqual([digest(canonical(p)) for p in packets],[c['requestSha256'] for c in result['chunks']])
        self.assertEqual(result,self.run_plan())

    def test_work_limit_remains_visible_without_truncation(self):
        self.source(['Case A: parents healthy. '+'Details. '*760])
        result=self.run_plan()
        self.assertEqual([],result['selectedChunks'])
        self.assertTrue(any(c['reasonCode']=='TARGET_WORK_LIMIT_NOT_TRUNCATED' for c in result['chunks']))

    def test_exact_cache_reused_without_transport_or_marking_reviewed(self):
        self.source(['Case A: parents healthy.', 'Case A: father unaffected.'])
        packets=requests(self.s,self.config['model'],self.config['maxOutputTokens'],self.config['maxRequestBytes'],PROFILE)
        index=self.run_plan(limit=64)['selectedChunks'][1]
        content=parse(packets[index]['messages'][1]['content'])
        unit=next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
        candidate={'schemaVersion':'denovo-core-material-candidates-v1','cases':[],
            'coverage':'EXTRACTED_WITHIN_TARGET','sourceAssessments':[{'materialKind':'UNCLEAR',
            'citation':{'unitId':unit['unitId'],'quote':unit['text']}}]}
        binding=identity(self.s['pmcid'],self.s['bundleSha256'],index,len(packets),PROFILE)
        budget=self.root/'synthetic-cache'
        invoke(self.config,'synthetic-key-not-present',packets[index],budget,binding,lambda *args:response(candidate))
        with (patch('model_transport.credentials',side_effect=AssertionError('credential access')),
              patch('bundle_model_transport.https_once',side_effect=AssertionError('network'))):
            result=self.run_plan(cache_dir=budget)
        self.assertEqual([index],result['selectedChunks']);self.assertEqual([],result['reviewedChunks'])
        self.assertEqual('REPLAY_REQUIRED',result['chunks'][index]['cacheState'])
        _,key=cache_identity(self.config,packets[index],binding)
        (budget/'responses'/key/'response.json').write_bytes(b'{}')
        result=self.run_plan(cache_dir=budget)
        self.assertNotIn(index,result['selectedChunks']);self.assertEqual('HELD',result['chunks'][index]['state'])

    def test_actual_task1_package_to_registered_source(self):
        fixture=feedback_fixture.ReadingFeedbackTests();fixture.setUp();self.addCleanup(fixture.doCleanups)
        feedback,core=fixture.run_feedback(article().replace(b'<tr><td>Family history</td><td>None</td></tr>',b''))
        target={**self.target,**{k:core['values']['/context/'+k] for k in ('allele','hgncId','diseaseId','inheritance')}}
        result=prepare(fixture.root/'library',fixture.package,fixture.pin,core['caseKey'],target,self.config)
        self.assertTrue(result['selectedChunks']);self.assertEqual(core['caseKey'],result['scope']['caseKey'])
        self.assertEqual(0,result['modelCalls']);self.assertEqual(0,result['networkCalls'])

    def test_failed_cached_call_without_receipt_is_held(self):
        self.source(['Case A: parents healthy.'])
        baseline=self.run_plan();index=baseline['selectedChunks'][0]
        packets=requests(self.s,self.config['model'],self.config['maxOutputTokens'],self.config['maxRequestBytes'],PROFILE)
        binding=identity(self.s['pmcid'],self.s['bundleSha256'],index,len(packets),PROFILE)
        _,key=cache_identity(self.config,packets[index],binding)
        budget=self.root/'failed-cache';budget.mkdir()
        (budget/'budget.json').write_bytes(canonical({'calls':[{'key':key,'status':'FAILED_RESERVED'}]}))
        result=self.run_plan(cache_dir=budget)
        self.assertEqual([],result['selectedChunks'])
        self.assertEqual('CACHE_RECORDED_CALL_REQUIRES_RECOVERY',result['chunks'][index]['reasonCode'])

    def test_attachment_rows_keep_source_identity_and_other_cases_separate(self):
        fixture=bundle_fixture.BundleModelTests()
        # Reuse the actual CSV/XLSX/unsupported-PDF registration fixture, but
        # keep its temporary files Code-local for this task.
        original=tempfile.TemporaryDirectory
        with patch('test_bundle_model.tempfile.TemporaryDirectory',lambda:original(dir=self.root)):
            fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.s=fixture.source;self.key='PMC123#1'
        self.feedback={'schemaVersion':'denovo-reading-feedback-package-v1','pmcid':'PMC123',
            'bundleSha256':self.s['bundleSha256'],'cases':[{'schemaVersion':'denovo-reading-feedback-v1',
            'bindings':{'caseKey':self.key,'sourcePins':{'main':{'role':'PAPER_PRIMARY','sha256':self.s['main']['sourceSha256']}}},
            'context':{},'needs':[]}]}
        self.needs('TESTING')
        result=self.run_plan(limit=64)
        chosen=[c for c in result['chunks'] if c['chunkIndex'] in result['selectedChunks']]
        self.assertTrue(any(any(uid.startswith('attachment:') for uid in c['targetUnitIds']) for c in chosen))
        other=[c for c in result['chunks'] if 'Case 2' in self.text(result,c['chunkIndex'])]
        self.assertTrue(other);self.assertTrue(all(c['reasonCode']=='OTHER_CASE_ONLY' for c in other))
        self.assertEqual(self.s['coverage'],result['coverageLimitations'])


if __name__=='__main__':unittest.main()
