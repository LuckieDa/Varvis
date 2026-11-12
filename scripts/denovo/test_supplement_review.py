"""Supplement response, attribution and replay controls; synthetic only."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from model_literature_contract import canonical, digest, parse
from bundle_model_transport import decode_response
from model_transport import public_config
from resume_model_pilot import resume
from test_bundle_model import configuration, response


class ResponseRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.c = configuration()
        self.raw = parse(response({'cases': []}))
        self.raw['choices'][0]['message']['content'] = '```json\n{"cases": []}\n```'

    def fixture(self):
        start = {'schemaVersion': 'denovo-model-budget-v1',
                 'configurationSha256': digest(canonical(public_config(self.c))),
                 'calls': [{'key': 'b'*64, 'status': 'SUCCESS', 'chargedOrReservedMicroCredits': 20}],
                 'stoppedReason': None, 'resumeHistory': [{}, {}, {}]}
        archive = self.root/'continuation-reviews'; archive.mkdir()
        start_pin = digest(canonical(start)); (archive/(start_pin+'-budget.json')).write_bytes(canonical(start))
        state = deepcopy(start); state['stoppedReason'] = 'TRANSPORT_OR_STORAGE_FAILED'
        raw = canonical(self.raw); folder = self.root/'responses'/('a'*64); folder.mkdir(parents=True)
        (folder/'response.json').write_bytes(raw); (folder/'request.json').write_bytes(b'{"old":true}')
        state['calls'].append({'key': 'a'*64, 'status': 'FAILED_RESERVED', 'chargedOrReservedMicroCredits': 100000,
                              'reasonCode': 'DENOVO_MODEL_TRANSPORT_OR_STORAGE_FAILED', 'responseSha256': digest(raw)})
        (self.root/'budget.json').write_bytes(canonical(state))
        preflight = self.root/'preflight.json'; preflight.write_bytes(canonical({
            'schemaVersion': 'denovo-model-preflight-v1', 'executeRequested': False, 'requests': [{'requestSha256': 'c'*64}]}))
        review = self.root/'review.json'; review.write_bytes(canonical({
            'schemaVersion': 'denovo-pilot-continuation-review-v1', 'authorization': 'USER_TASK_AUTHORIZED_DEVELOPMENT',
            'diagnosis': 'Complete response has Markdown and incompatible JSON contract; no unknown network failure.',
            'offlineChecksPassed': True, 'responseSha256': digest(raw), 'nextRequestSha256': 'c'*64,
            'taskStartBudgetSha256': start_pin, 'localFailureDiagnosis': 'COMPLETE_RESPONSE_INVALID_JSON'}))
        return state, preflight, review

    def test_invalid_json_reports_format_not_network_and_never_repairs_schema(self):
        self.raw['choices'][0]['message']['content'] = 'explanation before ```json\n{}\n```'
        with self.assertRaisesRegex(ValueError, 'RESPONSE_NOT_JSON'):
            decode_response(canonical(self.raw), self.c)

    def test_single_fence_exact_schema_and_prose_rejected(self):
        from test_bundle_model import RESPONSE_VERSION
        candidate = {'schemaVersion': RESPONSE_VERSION, 'cases': [], 'coverage': 'EXTRACTED_WITHIN_TARGET',
                     'sourceAssessments': [{'materialKind': 'UNCLEAR', 'citation': {'unitId': 'unit', 'quote': 'source'}}]}
        outer = parse(response(candidate)); text = outer['choices'][0]['message']['content']
        outer['choices'][0]['message']['content'] = '```json\n'+text+'\n```'
        self.assertEqual(candidate, decode_response(canonical(outer), self.c)[0])
        for suffix in ('\nprose', '\n```json\n{}\n```'):
            outer['choices'][0]['message']['content'] = '```json\n'+text+'\n```'+suffix
            with self.assertRaises(ValueError): decode_response(canonical(outer), self.c)

    def test_received_response_recovery_keeps_failed_record_and_zero_transport(self):
        from bundle_model_transport import cache_identity, cached
        from recover_bundle_response import recover
        from test_bundle_model import RESPONSE_VERSION
        candidate = {'schemaVersion': RESPONSE_VERSION, 'cases': [], 'coverage': 'EXTRACTED_WITHIN_TARGET',
                     'sourceAssessments': [{'materialKind': 'UNCLEAR', 'citation': {'unitId': 'unit', 'quote': 'source'}}]}
        payload = {'response_format': {'json_schema': {'schema': {'properties': {'schemaVersion': {'enum': [RESPONSE_VERSION]}}}}}}
        binding = {'synthetic': True}; identity, key = cache_identity(self.c,payload,binding)
        outer = parse(response(candidate)); outer['choices'][0]['message']['content'] = '```json\n'+canonical(candidate).decode()+'\n```'
        raw = canonical(outer); folder=self.root/'responses'/key; folder.mkdir(parents=True)
        (folder/'request.json').write_bytes(canonical(payload)); (folder/'response.json').write_bytes(raw)
        unused, usage=decode_response(raw,self.c)
        failed={'key':key,'status':'FAILED_RESERVED','chargedOrReservedMicroCredits':100000,
                'reasonCode':'DENOVO_MODEL_RESPONSE_NOT_JSON','responseSha256':digest(raw),'reportedUsage':usage,'elapsedSeconds':1}
        state={'configurationSha256':digest(canonical(public_config(self.c))),'calls':[failed],'stoppedReason':'RESPONSE_NOT_JSON'}
        (self.root/'budget.json').write_bytes(canonical(state))
        def rejected(v): raise ValueError('WRONG_SOURCE')
        with self.assertRaisesRegex(ValueError,'WRONG_SOURCE'):
            recover(self.c,self.root,binding,payload,digest(canonical(state)),rejected,transport_kind='SYNTHETIC_TEST')
        self.assertFalse((folder/'receipt.json').exists())
        result=recover(self.c,self.root,binding,payload,digest(canonical(state)),lambda v:{'checked':True},transport_kind='SYNTHETIC_TEST')
        self.assertEqual(0,result['networkCalls']); self.assertEqual([failed],parse((self.root/'budget.json').read_bytes())['calls'])
        self.assertTrue(cached(self.c,payload,self.root,binding)[2])
        after=parse((self.root/'budget.json').read_bytes());after['calls'][0]['chargedOrReservedMicroCredits']-=1
        (self.root/'budget.json').write_bytes(canonical(after))
        with self.assertRaisesRegex(ValueError,'RECOVERY_FAILED_ENTRY_CHANGED'):cached(self.c,payload,self.root,binding)

    def test_new_task_keeps_all_calls_reserves_and_changed_request_permit(self):
        state, p, r = self.fixture()
        result = resume(self.c, self.root, digest(canonical(state)), p, r)
        after = parse((self.root/'budget.json').read_bytes())
        self.assertEqual(state['calls'], after['calls'])
        self.assertEqual(after['resumePermit']['requestSha256s'], ['c'*64])
        self.assertEqual(len(after['resumeHistory']), 4)
        self.assertEqual(result['modelCalls'], 0)

    def test_unknown_failure_and_wrong_pin_cannot_resume(self):
        for mode in ('unknown', 'pin', 'prefix', 'rounds'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as d:
                old = self.root; self.root = Path(d)
                state, p, r = self.fixture()
                if mode == 'unknown':
                    self.raw['choices'][0]['message']['content'] = '{}'
                    raw = canonical(self.raw); (self.root/'responses'/('a'*64)/'response.json').write_bytes(raw)
                    state['calls'][-1]['responseSha256'] = digest(raw)
                    v = parse(r.read_bytes()); v['responseSha256'] = digest(raw); r.write_bytes(canonical(v))
                elif mode == 'pin': state['calls'][-1]['responseSha256'] = '0'*64
                elif mode == 'prefix': state['calls'][0]['chargedOrReservedMicroCredits'] += 1
                else: state['resumeHistory'] += [{}, {}, {}]
                (self.root/'budget.json').write_bytes(canonical(state))
                with self.assertRaises(ValueError): resume(self.c, self.root, digest(canonical(state)), p, r)
                self.root = old
                self.raw['choices'][0]['message']['content'] = '```json\n{}\n```'


class SupplementFactTests(unittest.TestCase):
    def setUp(self):
        from test_bundle_qualification import article, registered
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.library, self.source = registered(self.root, article())

    def attach(self, body):
        from test_official_docx import docx
        from literature_attachments import parse_attachment
        raw = docx(body); pin = digest(raw); parsed = parse_attachment('case.docx', raw)
        self.assertEqual(parsed['status'], 'TEXT_READ_NOT_SEMANTICALLY_INTERPRETED')
        source = deepcopy(self.source)
        source['attachments'] = [{'originalName': 'case.docx', 'status': parsed['status']}]
        source['units'] += [{**u, 'unitId': f'attachment:{pin}:{u["location"]}', 'sourceSha256': pin,
                            'originalName': 'case.docx', 'sections': [], 'cellSemantics': 'TEXT',
                            'emptyCell': not u['text']} for u in parsed['units']]
        return source

    def test_owned_age_and_identifiers_not_parentage_or_assay_qualification(self):
        from test_official_docx import p
        from supplement_case_review import proofs
        source = self.attach(p('Patient A: A 5-year-old child had EXAMPLE variant c.123A&gt;G; p.T41A. '
                               'Sanger verified. Confirmed de novo. Mother had seizures.'))
        facts, scope = proofs(source, 'A')
        self.assertEqual('5-year-old', facts['/groups/phenotype/age'][0]['value'])
        self.assertEqual(1, len(scope['ownedUnitIds']))
        self.assertFalse(any('parentage' in p or 'testing' in p or 'family' in p for p in facts))

    def test_other_case_multiple_case_wrong_gene_or_allele_refuse(self):
        from test_official_docx import p
        from supplement_case_review import proofs
        base = 'Patient A: A 5-year-old child had EXAMPLE variant c.123A&gt;G; p.T41A.'
        for text in (base.replace('Patient A', 'Patient B'), base+' Patient B was tested.',
                     base.replace('EXAMPLE', 'OTHER'), base.replace('123A', '124A'), base.replace('T41A', 'T42A'),
                     base.replace('child had', 'mother had'), base.replace('child had', 'child did not have')):
            with self.subTest(text=text): self.assertFalse(proofs(self.attach(p(text)), 'A')[0])
        self.assertFalse(proofs(self.attach(p(base)+p(base)), 'A')[0])

    def test_explicit_table_id_not_row_number_and_classification_excluded(self):
        from test_official_docx import p
        from supplement_case_review import proofs
        def row(values): return '<w:tr>'+''.join('<w:tc>'+p(v)+'</w:tc>' for v in values)+'</w:tr>'
        body = '<w:tbl>'+row(['Patient ID','EXAMPLE','ACMG Classification'])+row(['A','c.123A&gt;G; p.T41A','PM6 assumed de novo; parentage confirmed'])+'</w:tbl>'
        facts, scope = proofs(self.attach(body), 'A')
        self.assertEqual(2, len(scope['ownedUnitIds']))
        self.assertEqual(1, len(scope['authorClassificationUnitIds']))
        self.assertFalse(any('parentage' in p for p in facts))
        self.assertFalse(proofs(self.attach(body.replace('<w:t>A</w:t>', '<w:t>B</w:t>')), 'A')[0])

    def test_package_rechecks_attachment_view_and_preserves_ledger_quotes(self):
        from test_official_docx import p
        from test_bundle_qualification import candidate
        from bundle_model_contract import review
        from qualify_bundle_candidates import qualify
        from core_material_extraction import write_package
        from core_material_review import review_package, read_input
        source = self.attach(p('Patient A: A 5-year-old child had EXAMPLE variant c.123A&gt;G.'))
        checked = review(source, candidate(source)); checked['workUnit'] = {'targetUnitIds': []}
        raw = self.root/'raw'; write_package(source, qualify(source,[checked]), [checked], raw)
        pin = digest((raw/'index.json').read_bytes())
        out = self.root/'review'; result = review_package(source,raw,pin,out)
        doc = parse((out/result['cases'][0]['path']).read_bytes())
        age = doc['groups']['phenotype']['age']
        self.assertEqual('VERIFIED', age['verification'])
        self.assertTrue(any(r['sourceId']=='units' for r in age['citations']))
        self.assertEqual([], doc['reviewPlan']['unavailableSources'])
        unit_path = raw/'source-units.json'; v = parse(unit_path.read_bytes()); v[-1]['text']='forged'; unit_path.write_bytes(canonical(v))
        with self.assertRaisesRegex(ValueError,'SOURCE_UNIT_RECONSTRUCTION'): read_input(source,raw,pin)


if __name__ == '__main__': unittest.main()
