"""Original source/core pipeline with synthetic transport; no external calls."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
import re

from test_bundle_qualification import article, registered, candidate
from test_bundle_model import configuration, response
from test_core_evaluation_intake import config_for
from model_literature_contract import canonical, digest, parse
from bundle_model_contract import requests
from core_model_review_contract import GROUPS
from run_bundle_model import run as extract
from run_core_model_review import run as audit
from denovo_reading_loop import run
from denovo_reading_plan import PROFILE


class LoopTests(unittest.TestCase):
    def setUp(self):
        root=Path(__file__).resolve().parents[2]/'local/temp'
        self.temp=tempfile.TemporaryDirectory(prefix='denovo-loop-',dir=root)
        self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.config=configuration();self.config_path=self.root/'config.json'
        self.config_path.write_bytes(canonical(self.config));(self.root/'key.txt').write_text('synthetic-not-secret')
        self.context=config_for(self.root);self.budget=self.root/'budget'
        self.target={'allele':{'assembly':'GRCh38','contig':'1','position':123,'reference':'A','alternate':'G'},
            'hgncId':'HGNC:999999997','diseaseId':'MONDO:1','inheritance':'AD','geneSymbol':'EXAMPLE',
            'variantAliases':['c.123A>G','p.T41A']}
        self.calls=[]
        self.supply_identifiers=True
        self.prepare_source(article())

    def need_source(self,extra=b'',assumed=False):
        raw=re.sub(b'<table-wrap>.*?</table-wrap>',b'',article(),flags=re.S)
        if assumed:raw=raw.replace(b'SNP analysis confirmed both maternity and paternity.',b'No parentage testing was performed.')
        raw=raw.replace(b'</p></sec></sec>',b'</p><p>Case A was studied. The EXAMPLE variant c.123A&gt;G, p.T41A, was observed again. Case A had Synthetic trait. He is the second child of healthy non-consanguineous parents, with no family history of neurological disorders. '+extra+b'</p></sec></sec>')
        self.prepare_source(raw)

    def prepare_source(self,raw):
        parent=self.root/('source-'+str(len(list(self.root.glob('source-*')))));parent.mkdir()
        self.library,self.source=registered(parent,raw)
        self.key=self.source['pmcid']+'#A'
        self.full=deepcopy(self.full) if hasattr(self,'full') else candidate(self.source)
        own=next(u for u in self.source['main']['units'] if u['kind']=='BODY_PARAGRAPH' and 'c.123A>G' in u['text'])
        self.full['cases'][0]['caseCitation']={'unitId':own['unitId'],'quote':'Case A was studied.'}
        for f in self.full['cases'][0]['fields']:f['citation']={'unitId':own['unitId'],'quote':own['text']}
        self.full['sourceAssessments'][0]['citation']={'unitId':own['unitId'],'quote':own['text']}
        self.full['schemaVersion']='denovo-core-material-candidates-v1'
        self.packets=requests(self.source,self.config['model'],self.config['maxOutputTokens'],self.config['maxRequestBytes'],PROFILE)
        uid=self.full['cases'][0]['caseCitation']['unitId']
        self.seed_index=next(i for i,p in enumerate(self.packets) if uid in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])

    def transport(self,c,key,body):
        packet=parse(body);content=parse(packet['messages'][1]['content']);self.calls.append(content)
        if packet['response_format']['json_schema']['schema']['properties']['schemaVersion']['enum'][0]=='denovo-core-source-audit-v1':
            rows=[]
            for row in content['cases']:
                rows.append({'caseLabel':row['caseLabel'],
                    'audits':[{'claimId':claim['claimId'],'verdict':'SUPPORTED','reason':'Synthetic literal test.',
                               'evidence':[claim['citation']]} for claim in row['claims']],
                    'additions':[],'groupChecks':[{'group':g,'outcome':'UNRESOLVED','evidence':[]} for g in GROUPS],
                    'coverage':'REVIEWED_SUPPLIED_SCOPE'})
            return response({'schemaVersion':'denovo-core-source-audit-v1','cases':rows})
        value=deepcopy(self.full)
        targets=set(content['workUnit']['targetUnitIds'])
        unit=next((u for u in content['units'] if u['unitId'] in targets and 'The EXAMPLE variant c.123A>G, p.T41A, was observed again.' in u['text']),None)
        if unit and self.supply_identifiers:
            for row in value['cases']:
                row['caseCitation']={'unitId':unit['unitId'],'quote':'Case A was studied.'}
                for field in row['fields']:field['citation']={'unitId':unit['unitId'],'quote':unit['text']}
            value['sourceAssessments'][0]['citation']={'unitId':unit['unitId'],'quote':unit['text']}
        else:
            value['cases']=[];unit=next(u for u in content['units'] if u['unitId'] in targets)
            value['sourceAssessments']=[{'materialKind':'UNCLEAR','citation':{'unitId':unit['unitId'],'quote':unit['text']}}]
        return response(value)

    def seed(self, sparse=False):
        value=deepcopy(self.full)
        if sparse:value['cases'][0]['fields']=value['cases'][0]['fields'][:1]
        folder=self.root/'seed-extraction'
        extract(self.library,self.source['pmcid'],self.source['bundleSha256'],self.config,self.config_path,
            self.budget,folder,chunk_index=self.seed_index,extraction_profile=PROFILE,transport=lambda *a:response(value))
        original=folder/'artifact.json';review=self.root/'seed-review'
        audit(self.library,original,self.config,self.config_path,self.budget,review,transport=self.transport)
        self.seeds=[(original,review)];self.calls.clear()

    def loop(self,name='run',**kw):
        return run(self.library,self.source['pmcid'],self.source['bundleSha256'],self.key,self.target,
            self.config,self.config_path,self.budget,self.context,self.root/name,self.seeds,transport=self.transport,**kw)

    def test_ready_seed_needs_no_model_or_new_score(self):
        self.seed();before=(self.budget/'budget.json').read_bytes()
        result=self.loop()
        self.assertEqual('READY_FOR_ORIGINAL_EVALUATION',result['stopReason'],result['finalFeedback'])
        self.assertEqual([],result['rounds']);self.assertEqual(0,result['modelCalls'])
        self.assertEqual('NOT_RUN',result['criterionDecision']);self.assertEqual(before,(self.budget/'budget.json').read_bytes())

    def test_sparse_seed_automatically_reads_then_reassesses(self):
        self.need_source()
        self.seed(True);result=self.loop(allow_model=True)
        self.assertEqual('READY_FOR_ORIGINAL_EVALUATION',result['stopReason'],[(n['fieldPath'],n['reasonCode']) for n in result['finalFeedback']['needs']])
        self.assertTrue(result['rounds']);self.assertGreater(result['replayedArtifactCount'],1)
        self.assertEqual([],result['finalFeedback']['needs']);self.assertGreater(result['modelCalls'],0)
        again=self.loop('repeat')
        self.assertEqual('READY_FOR_ORIGINAL_EVALUATION',again['stopReason'])
        self.assertEqual(0,again['modelCalls']);self.assertEqual(result['coreEvaluation'],again['coreEvaluation'])
        first=parse((self.root/'run/round-000/feedback.json').read_bytes())['cases'][0]
        self.assertFalse(first['requirementsSatisfied'])
        self.assertTrue(first['needs']);self.assertEqual([],result['rounds'][-1]['afterNeedIds'])
        self.assertEqual(2,len(parse((self.root/'run'/result['candidateIntake']['path']).read_bytes())['artifacts']))

    def test_cache_miss_disabled_does_not_call_model_or_erase_seed(self):
        self.need_source();self.seed(True)
        seed_bytes=self.seeds[0][0].read_bytes();result=self.loop()
        self.assertEqual('DENOVO_BUNDLE_READING_MODEL_DISABLED',result['stopReason'])
        self.assertEqual([],self.calls);self.assertEqual(1,result['replayedArtifactCount'])
        self.assertTrue(result['finalFeedback']['needs']);self.assertFalse(result['absenceProven'])
        self.assertEqual(seed_bytes,self.seeds[0][0].read_bytes())

    def test_call_budget_stops_before_unreviewed_fact_is_adopted(self):
        self.need_source();self.seed(True);result=self.loop(allow_model=True,maximum_calls=1)
        self.assertEqual('DENOVO_BUNDLE_READING_CALL_LIMIT',result['stopReason'])
        self.assertEqual(1,result['modelCalls']);self.assertEqual(1,result['replayedArtifactCount'])
        self.assertTrue(result['finalFeedback']['needs'])
        self.assertTrue(any((self.root/'run').glob('chunk-*-extraction/artifact.json')))
        self.assertFalse(any((self.root/'run').glob('chunk-*-review/artifact.json')))

    def test_no_progress_continues_to_other_targets_without_repeating(self):
        self.need_source();self.seed(True);self.supply_identifiers=False
        result=self.loop(allow_model=True)
        self.assertEqual('NO_PENDING_RELEVANT_TARGET',result['stopReason'])
        indices=[r['chunkIndex'] for r in result['rounds']]
        self.assertGreater(len(indices),1);self.assertEqual(len(indices),len(set(indices)))
        self.assertFalse(result['finalFeedback']['requirementsSatisfied']);self.assertFalse(result['absenceProven'])
        self.assertEqual([],result['readingPlan']['selectedChunks'])
        self.assertEqual(1+len(indices),len(result['checkpoint']['entries']))

    def test_chunk_limit_preserves_unread_scope(self):
        self.need_source();self.seed(True);self.supply_identifiers=False
        result=self.loop(allow_model=True,maximum_chunks=1)
        self.assertEqual('READING_CHUNK_LIMIT',result['stopReason'])
        self.assertEqual(1,len(result['rounds']));self.assertTrue(result['readingPlan']['selectedChunks'])
        self.assertFalse(result['finalFeedback']['requirementsSatisfied'])

    def test_insufficient_time_does_not_start_a_paid_call(self):
        self.need_source();self.seed(True)
        result=self.loop(allow_model=True,timeout_seconds=5)
        self.assertEqual('DENOVO_BUNDLE_READING_TIME_LIMIT',result['stopReason'])
        self.assertEqual(0,result['modelCalls']);self.assertEqual([],self.calls)

    def test_duplicate_seed_does_not_duplicate_fact_or_event(self):
        self.seed();one=self.loop('one');self.seeds*=2;two=self.loop('two')
        self.assertEqual(1,two['replayedArtifactCount']);self.assertEqual(one['coreEvaluation'],two['coreEvaluation'])
        self.assertEqual(0,two['modelCalls'])

    def test_changed_target_is_rejected_before_transport(self):
        self.seed();self.target['allele']['position']=124;result=self.loop(allow_model=True)
        self.assertEqual('DENOVO_BUNDLE_READING_TARGET_CHANGED',result['stopReason'])
        self.assertEqual([],self.calls);self.assertEqual(0,result['modelCalls'])

    def test_tampered_seed_is_not_trusted_as_a_checkpoint(self):
        self.seed();p=self.seeds[0][0];v=parse(p.read_bytes());v['review']['cases'][0]['caseLabel']='B'
        p.write_bytes(canonical(v))
        with self.assertRaises(ValueError):self.loop(allow_model=True)
        self.assertEqual([],self.calls)

    def test_invalid_model_output_keeps_previous_qualified_facts(self):
        self.need_source();self.seed(True)
        self.transport=lambda *a: response({'schemaVersion':'wrong'})
        result=self.loop(allow_model=True)
        self.assertEqual(1,result['replayedArtifactCount']);self.assertTrue(result['issues'])
        self.assertFalse(result['finalFeedback']['requirementsSatisfied'])
        self.assertEqual('FAILED',result['rounds'][0]['status']);self.assertFalse(result['absenceProven'])

    def test_external_policy_gap_does_not_trigger_more_model_reading(self):
        self.seed()
        path=self.root/self.context['path'];value=parse(path.read_bytes());value['contexts']=[]
        path.write_bytes(canonical(value));self.context['sha256']=digest(path.read_bytes())
        result=self.loop(allow_model=True)
        self.assertEqual('EXTERNAL_OR_UNSUPPORTED_REQUIREMENT',result['stopReason'])
        self.assertEqual([],self.calls);self.assertTrue(result['finalFeedback']['needs'])

    def test_source_conflict_is_preserved_instead_of_last_value_winning(self):
        self.need_source(b'The EXAMPLE variant c.123A&gt;T was also discussed.')
        self.seed(True)
        self.full['cases'][0]['fields'].append({'field':'variantText','sourceValue':'c.123A>T',
            'subject':'PROBAND','citation':{}})
        result=self.loop(allow_model=True)
        self.assertFalse(result['finalFeedback']['requirementsSatisfied'])
        facts=parse((self.root/'run'/result['assessedFacts']['path']).read_bytes())
        fields=facts['cases'][0]['fields']
        self.assertEqual({'c.123A>G','c.123A>T'},{f['sourceValue'] for f in fields if f['field']=='variantText'})
        self.assertTrue(any('CONFLICT' in i for i in facts['cases'][0]['issues']))
        self.assertEqual('NOT_RUN',result['criterionDecision'])

    def test_another_case_is_not_selected_to_fill_target_identity(self):
        raw=re.sub(b'<table-wrap>.*?</table-wrap>',b'',article(),flags=re.S)
        raw=raw.replace(b'</sec></body>',b'<sec><title>Case B</title><p>Case B was studied. The EXAMPLE variant c.123A&gt;G, p.T41A, was observed again.</p></sec></sec></body>')
        self.prepare_source(raw);self.seed(True);self.supply_identifiers=False
        result=self.loop(allow_model=True)
        foreign=[c for c in result['readingPlan']['chunks'] if c['reasonCode']=='OTHER_CASE_ONLY']
        self.assertTrue(foreign)
        self.assertTrue(set(c['chunkIndex'] for c in foreign).isdisjoint(r['chunkIndex'] for r in result['rounds']))
        self.assertFalse(result['finalFeedback']['requirementsSatisfied'])


def write_java_loop_fixture(root,mode):
    """Real loop/source adapters, synthetic paper/transport/policy for Java scorer."""
    root=Path(root);test=LoopTests();test.setUp()
    try:
        test.need_source(assumed=mode=='pm6');test.seed(True)
        if mode=='missing':test.supply_identifiers=False
        result=test.loop(allow_model=True)
        (root/'loop-result.json').write_bytes(canonical(result))
        (root/'initial-feedback.json').write_bytes((test.root/'run/round-000/feedback.json').read_bytes())
        (root/'loop-policy.json').write_bytes((test.root/'local/policy.json').read_bytes())
        (root/'loop-knowledge.txt').write_bytes((test.root/'local/knowledge.txt').read_bytes())
        if mode!='missing':
            repeat=test.loop('repeat')
            (root/'repeat-result.json').write_bytes(canonical(repeat))
    finally:test.doCleanups()


if __name__=='__main__':unittest.main()
