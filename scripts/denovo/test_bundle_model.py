import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_literature_acquisition import jats, zipped, xlsx
from acquire_public_literature import source_bundle
from paper_library_registration import register
from prepare_literature import prepare
from model_literature_contract import canonical, digest, parse
from bundle_model_contract import load, requests, review, cite, RESPONSE_VERSION
from bundle_model_transport import invoke
from run_bundle_model import run, replay


def configuration():
    return {'schemaVersion': 'denovo-model-config-v1', 'enabled': True, 'pricingConfirmed': True,
            'protocol': 'openai-chat-completions-json-schema', 'endpoint': 'https://example.invalid/v1/chat/completions',
            'model': 'gemini-3-flash', 'expectedResponseModel': 'gemini-3-flash', 'maxCalls': 100,
            'maxOutputTokens': 8192, 'maxBilledOutputTokens': 16384, 'timeoutSeconds': 1,
            'maxRequestBytes': 262144, 'maxResponseBytes': 1048576, 'inputCreditsPerMillion': 1,
            'outputCreditsPerMillion': 6, 'creditsPerRmb': 3, 'budgetRmb': 16.66,
            'apiKeyEnvironment': 'VARVIS_TEST_FAKE', 'apiKeyFile': 'key.txt'}


def response(candidate):
    return canonical({'model': 'gemini-3-flash', 'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30},
                      'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(candidate)}}]})


class BundleModelTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.library = self.root / 'library'; self.library.mkdir()
        raw = jats(True).replace(b'A public source.', b'Case 1 was studied. No parents were tested.')
        package = zipped([('table.csv', b'Case,Gene,Finding\nCase 1,GENE1,not detected\nCase 2,GENE2,detected\n'),
                          ('extra.xlsx', xlsx()), ('unknown.pdf', b'%PDF-')])
        class Transport:
            def get(self, *args): return package
        files, bundle = source_bundle({'pmcid': 'PMC123'}, raw, prepare(raw, 'PMC123', digest(raw)), Transport(), True)
        result = register(self.library, 'PMC123', files, bundle, {'sha256': digest(raw), 'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'})
        self.pin = result['bundleSha256']; self.source = load(self.library, 'PMC123', self.pin)
        self.c = configuration(); self.config = self.root / 'config.json'
        self.config.write_bytes(canonical(self.c)); (self.root / 'key.txt').write_text('synthetic-not-secret')
        cell=next(u for u in self.source['units'] if u['kind']=='SUPPLEMENT_CELL' and u['text']=='Case 1')
        self.empty = {'schemaVersion': RESPONSE_VERSION, 'cases': [],'coverage':'EXTRACTED_WITHIN_TARGET',
            'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':cell['unitId'],'quote':'Case 1'}}]}

    def candidate(self):
        cells = {u['text']: u for u in self.source['units'] if u['kind'] == 'SUPPLEMENT_CELL'}
        quote = lambda text: {'unitId': cells[text]['unitId'], 'quote': text}
        return {**self.empty, 'cases': [{'caseLabel': '1', 'caseCitation': quote('Case 1'), 'caseOrigin': 'UNCLEAR',
            'originCitations': [], 'fields': [{'field': 'geneSymbol', 'subject': 'PROBAND', 'sourceValue': 'GENE1', 'citation': quote('GENE1')}]}]}

    def test_actual_csv_and_xlsx_units_bound_to_individual_sources(self):
        c = self.candidate(); r = review(self.source, c)
        quote = r['cases'][0]['fields'][0]['citation']
        self.assertEqual(quote['cell'], 'R2C2')
        self.assertNotEqual(quote['sourceSha256'], self.source['main']['sourceSha256'])
        self.assertFalse(r['businessPublishable'])
        self.assertIn('ATTACHMENT_CASE_ATTRIBUTION_NOT_VERIFIED', r['cases'][0]['issues'])
        self.assertEqual(r['attachments'][-1]['status'], 'ARCHIVED_NOT_PARSED')

    def test_wrong_unit_and_changed_quote_rejected(self):
        for change in ('unit', 'quote', 'value'):
            c = self.candidate(); f = c['cases'][0]['fields'][0]
            if change == 'unit': f['citation']['unitId'] += '-different-file'
            if change == 'quote': f['citation']['quote'] = 'GENE3'
            if change == 'value': f['sourceValue'] = 'GENE3'
            with self.subTest(change=change), self.assertRaises(ValueError): review(self.source,c)

    def test_unsent_quote_rejected(self):
        with self.assertRaisesRegex(ValueError,'OUTSIDE_ACTUAL_INPUT'):
            review(self.source,self.candidate(),set())

    def test_formula_and_hidden_cells_never_promoted(self):
        source = self.source
        cell = next(u for u in source['units'] if u.get('formula'))
        c = self.candidate(); f=c['cases'][0]['fields'][0]
        f.update(sourceValue=cell['text'],citation={'unitId':cell['unitId'],'quote':cell['text']})
        r=review(source,c)
        self.assertIn('SPREADSHEET_VALUE_SEMANTICS_NOT_VERIFIED',r['cases'][0]['fields'][0]['issues'])

    def test_raw_source_mutation_refused_even_with_existing_derived(self):
        item=next((self.library/'papers/PMC123/supplementary').glob('*.csv'))
        item.write_bytes(b'forged')
        with self.assertRaisesRegex(ValueError,'PIN_MISMATCH'): load(self.library,'PMC123',self.pin)

    def test_registered_empty_attachment_does_not_abort_readable_material(self):
        library = self.root / 'empty-library'; library.mkdir()
        raw = jats(True)
        package = zipped([('table.csv', b'Case,Gene\nCase 1,GENE1\n'), ('empty.xlsx', b'')])
        class Transport:
            def get(self, *args): return package
        files, bundle = source_bundle({'pmcid': 'PMC123'}, raw,
            prepare(raw, 'PMC123', digest(raw)), Transport(), True)
        entry = register(library, 'PMC123', files, bundle, {'sha256': digest(raw),
                         'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'},
                         criteria=('PS2', 'PM6', 'PM3', 'PP1'))
        for criterion in ('PS2', 'PM6', 'PM3', 'PP1'):
            with self.subTest(criterion=criterion):
                source = load(library, 'PMC123', entry['bundleSha256'], criterion)
                unread = next(a for a in source['attachments'] if a['originalName'] == 'empty.xlsx')
                self.assertEqual('ARCHIVED_NOT_PARSED', unread['status'])
                self.assertEqual('REGISTERED_EMPTY_ATTACHMENT_UNREAD', unread['readingLimitation'])
                self.assertTrue(any(u['text'] == 'GENE1' for u in source['units']))
                self.assertFalse(source['coverage']['completePublicationProven'])
                self.assertEqual(1, source['coverage']['unparsedAttachments'])
        empty = next(a for a in bundle['attachments'] if a['originalName'] == 'empty.xlsx')
        path = library / 'papers' / 'PMC123' / empty['path']
        path.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'EMPTY_ATTACHMENT_CHANGED'):
            load(library, 'PMC123', entry['bundleSha256'], 'PP1')
        path.unlink()
        with self.assertRaises(OSError):
            load(library, 'PMC123', entry['bundleSha256'], 'PP1')

    def test_unregistered_bundle_and_path_rejected(self):
        for pmcid,pin in [('PMC123','0'*64),('../PMC123',self.pin)]:
            with self.assertRaises(ValueError): load(self.library,pmcid,pin)

    def test_source_order_partition_is_exhaustive_and_bounded(self):
        source=copy.deepcopy(self.source)
        source['main']['tables']=[]
        source['units']=[{**source['units'][0],'unitId':str(i),'kind':'BODY_PARAGRAPH','sections':[],'text':'x'*20000} for i in range(30)]
        values=requests(source)
        targets=[i for p in values for i in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds']]
        self.assertEqual(targets,[u['unitId'] for u in source['units']]);self.assertGreater(len(values),1)
        self.assertTrue(all(len(canonical(p))<=262144 for p in values))
        self.assertTrue(all(parse(p['messages'][1]['content'])['inputScope']=='BOUNDED_TARGET_WITH_CONTEXT' for p in values))

    def test_artifact_replay_cache_no_new_call_and_tamper_refused(self):
        count=[]
        def transport(*args): count.append(1);return response(self.candidate())
        idx=next(i for i,p in enumerate(requests(self.source,max_request_bytes=self.c['maxRequestBytes']))
            if self.empty['sourceAssessments'][0]['citation']['unitId'] in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])
        for i in range(2):
            if i:
                (self.root/'key.txt').unlink()
            result=run(self.library,'PMC123',self.pin,self.c,self.config,self.root/'budget',self.root/f'out{i}',transport,chunk_index=idx)
            self.assertEqual(result['modelCalls'],1 if i==0 else 0)
        self.assertEqual(len(count),1)
        path=self.root/'out0/artifact.json'
        self.assertEqual(replay(self.library,path)['cases'][0]['caseLabel'],'1')
        artifact=parse(path.read_bytes());artifact['review']['cases']=[];path.write_bytes(canonical(artifact))
        with self.assertRaisesRegex(ValueError,'REVIEW_CHANGED'): replay(self.library,path)

    def test_escaped_attachment_chunks_fit_actual_default_transport_limit(self):
        source=copy.deepcopy(self.source)
        source['main']['tables']=[]
        source['units']=[{**source['units'][0],'unitId':str(i),'kind':'BODY_PARAGRAPH','sections':[],'text':'"\\value'*4000} for i in range(30)]
        values=requests(source)
        self.assertTrue(all(len(canonical(p))<=196608 for p in values))
        self.assertEqual([i for p in values for i in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds']],
                         [u['unitId'] for u in source['units']])

    def test_smaller_configured_request_limit_is_used(self):
        source=copy.deepcopy(self.source)
        source['main']['tables']=[]
        source['units']=[{**source['units'][0],'unitId':str(i),'kind':'BODY_PARAGRAPH','sections':[],'text':'value '*1000} for i in range(30)]
        values=requests(source,max_request_bytes=32768)
        self.assertTrue(all(len(canonical(p))<=32768 for p in values))
        self.assertEqual([i for p in values for i in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds']],
                         [u['unitId'] for u in source['units']])
        with self.assertRaisesRegex(ValueError,'INPUT_TOO_LARGE'):
            requests(source,max_request_bytes=100)

    def test_shared_failed_reservation_blocks_new_call(self):
        def failure(*args): raise ValueError('DENOVO_MODEL_RATE_LIMITED')
        payload=requests(self.source)[0]
        with self.assertRaisesRegex(ValueError,'RATE_LIMITED'):
            invoke(self.c,'fake',payload,self.root/'budget',{},failure)
        state=parse((self.root/'budget/budget.json').read_bytes())
        self.assertEqual(state['calls'][0]['status'],'FAILED_RESERVED')
        with self.assertRaisesRegex(ValueError,'RATE_LIMITED'):
            invoke(self.c,'fake',payload,self.root/'budget',{'different':1},lambda *a:self.fail('network retried'))

    def test_v1_response_cannot_be_mislabelled_v2(self):
        with self.assertRaisesRegex(ValueError,'SCHEMA_INVALID'):
            invoke(self.c,'fake',requests(self.source)[0],self.root/'budget',{},lambda *a:response({'cases':[]}))

    def test_no_cases_is_not_absence(self):
        self.assertIn('NO_EXTRACTION_NOT_PROVEN_NO_CASES',review(self.source,self.empty)['issues'])

    def test_rows_preserve_header_neighbours_and_each_target_once(self):
        values=[parse(p['messages'][1]['content']) for p in requests(self.source)]
        targets=[uid for value in values for uid in value['workUnit']['targetUnitIds']]
        self.assertEqual(targets,[u['unitId'] for u in self.source['units']])
        cell=next(u for u in self.source['units'] if u['text']=='Case 1')
        value=next(v for v in values if cell['unitId'] in v['workUnit']['targetUnitIds'])
        texts={u['text'] for u in value['units']}
        self.assertTrue({'Case','Gene','Finding','not detected','Case 2'}.issubset(texts))
        self.assertTrue(all(u in self.source['units'] for v in values for u in v['units']))

    def test_context_case_cannot_be_extracted_as_target(self):
        with self.assertRaisesRegex(ValueError,'OUTSIDE_TARGET'):
            review(self.source,self.candidate(),{u['unitId'] for u in self.source['units']},set())

    def test_empty_main_table_cell_keeps_structure_not_fake_quote(self):
        source=copy.deepcopy(self.source)
        unit=source['units'][0]
        source['main']['tables']=[{'tableId':'table','rows':[{'sourceRowIndex':0,'cells':[
            {'unitId':unit['unitId'],'text':unit['text'],'header':True,'rowspan':1,'colspan':1},
            {'unitId':'empty-no-unit','text':'','header':True,'rowspan':1,'colspan':1}]}]}]
        values=[parse(p['messages'][1]['content']) for p in requests(source)]
        table=next(v for v in values if v['workUnit']['kind']=='MAIN_TABLE_ROW')
        self.assertEqual('',table['workUnit']['structure']['rows'][0]['cells'][1]['text'])
        self.assertNotIn('empty-no-unit',{u['unitId'] for u in table['units']})

    def test_output_work_limit_and_partial_are_explicit(self):
        c=self.candidate(); c['cases'][0]['fields']*=9
        with self.assertRaisesRegex(ValueError,'SCHEMA_INVALID'): review(self.source,c)
        c=self.candidate();c['coverage']='MORE_CASES_OR_FIELDS'
        self.assertIn('TARGET_EXTRACTION_INCOMPLETE',review(self.source,c)['issues'])

    def test_cache_miss_does_not_read_credentials_or_reserve(self):
        with patch('run_bundle_model.credentials',side_effect=AssertionError('credential read')):
            value=run(self.library,'PMC123',self.pin,self.c,self.config,self.root/'budget',self.root/'missing',
                lambda *a:self.fail('network'),cache_only=True)
        self.assertIsNone(value);self.assertFalse((self.root/'budget/budget.json').exists())


if __name__=='__main__': unittest.main()
