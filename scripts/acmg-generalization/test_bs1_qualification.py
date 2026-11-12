import copy
import tempfile
from pathlib import Path
import unittest

from bs1_expert_batch import content_hash
import bs1_qualification as q
import bs1_knowledge as k
from test_bs1_parameters import SOURCE


def fixture():
    r = {'recordId':'SYNTHETIC', 'version':'1', 'kind':'EXPERT_POLICY',
         'scope':{'hgncId':'HGNC:1','mondoId':'MONDO:0000001','modes':['AUTOSOMAL_DOMINANT']},
         'sources':[dict(SOURCE,locator='/whole')], 'expertPolicy':{'SYNTHETIC':'not a production policy'}}
    r['review'] = {'status':'REVIEWED_FOR_LOCAL_USE','contentSha256':content_hash(r),'reviewer':'SYNTHETIC_ORIGINAL',
                   'reviewedAt':'2026-10-09','basis':'synthetic test only','dataScopePolicyId':'BS1_LOCAL_EXOMES_V1'}
    original = {'whole': {'text':'full source including conditions and exceptions'}}
    e = {'record':r, 'payloadSha256':content_hash(q.payload(r)),
         'sourceBindings':[{'sourceId':'raw','locator':'/whole','contentSha256':content_hash(original['whole'])}],
         'checks':{name:{'status':'PUBLISHED_THRESHOLD_NOT_RECALCULATED' if name=='PARAMETER_SCOPE' else 'SATISFIED',
                         'basis':'Synthetic explicit scope only','sourceIds':['raw']} for name in q.CATEGORIES}}
    doc = {'schemaVersion':'bs1-qualification-input-v1','reviewer':'SYNTHETIC_IMPLEMENTER','reviewedAt':'2026-10-09',
           'basis':'Synthetic qualification; not independent scientific approval','entries':[e]}
    return doc, original


def compile_doc(doc, original): return q.compile_qualifications(doc, [SOURCE], {'raw':original})


class QualificationTest(unittest.TestCase):
    def test_full_binding_preserves_prior_attribution_and_records_new_author(self):
        doc, original = fixture(); before = copy.deepcopy(doc)
        r = compile_doc(doc, original)[0]
        self.assertEqual('SYNTHETIC_IMPLEMENTER',r['review']['reviewer'])
        self.assertEqual(before['entries'][0]['record']['review'],r['qualification']['priorReview'])
        self.assertEqual('SYNTHETIC_IMPLEMENTER',r['qualification']['reviewer'])
        self.assertEqual(content_hash({k:v for k,v in r.items() if k!='review'}),r['review']['contentSha256'])
        self.assertEqual(before,doc)
        self.assertEqual(compile_doc(doc,original),compile_doc(doc,original))

    def test_unreviewed_text_cannot_turn_into_adopted_record(self):
        d,o=fixture();d['entries'][0]['record'].pop('review')
        with self.assertRaisesRegex(ValueError,'PRIOR_ADOPTION_REQUIRED'):compile_doc(d,o)

    def test_source_or_record_change_invalidates_binding(self):
        for change in ['source','record','raw_sha']:
            with self.subTest(change=change):
                d,o=fixture()
                if change=='source':o['whole']['text']='changed'
                elif change=='record':d['entries'][0]['record']['scope']['hgncId']='HGNC:2'
                else:d['entries'][0]['record']['sources'][0]['sha256']='b'*64
                with self.assertRaises(ValueError):compile_doc(d,o)

    def test_missing_or_unknown_checks_reject(self):
        for name in q.CATEGORIES:
            d,o=fixture();d['entries'][0]['checks'].pop(name)
            with self.assertRaisesRegex(ValueError,'CHECKS_INCOMPLETE'):compile_doc(d,o)
        d,o=fixture();d['entries'][0]['checks']['ignoredCondition']={}
        with self.assertRaisesRegex(ValueError,'CHECKS_INCOMPLETE'):compile_doc(d,o)

    def test_unresolved_category_retained_never_auto_satisfied(self):
        for category in q.CATEGORIES:
            d,o=fixture();d['entries'][0]['checks'][category]['status']='UNRESOLVED'
            r=compile_doc(d,o)[0]
            self.assertEqual('UNRESOLVED',r['qualification']['checks'][category]['status'])

    def test_source_locator_cannot_be_fabricated(self):
        d,o=fixture();d['entries'][0]['sourceBindings'][0]['locator']='/missing'
        with self.assertRaisesRegex(ValueError,'LOCATOR_UNRESOLVED'):compile_doc(d,o)

    def test_nested_cspec_and_json_pointer_with_escaping(self):
        raw={'data':[{'entId':'GN1','ld':{'array':[{'x':1}]}}]}
        self.assertEqual(1,q.resolve(raw,'GN1/ld/array/0/x'))
        self.assertEqual(3,q.resolve({'a/b':{'~':3}},'/a~1b/~0'))
        for locator in ['GN2','GN1/ld/array/-1','GN1/ld/array/01','GN1/ld/array/2']:
            with self.assertRaisesRegex(ValueError,'LOCATOR_UNRESOLVED'):q.resolve(raw,locator)

    def test_duplicate_record_binding_and_check_source_reject(self):
        d,o=fixture();d['entries'].append(copy.deepcopy(d['entries'][0]))
        with self.assertRaisesRegex(ValueError,'DUPLICATE_RECORD'):compile_doc(d,o)
        d,o=fixture();d['entries'][0]['sourceBindings']*=2
        with self.assertRaisesRegex(ValueError,'DUPLICATE_BINDING'):compile_doc(d,o)
        d,o=fixture();d['entries'][0]['checks']['EXCEPTIONS']['sourceIds']=['unknown']
        with self.assertRaisesRegex(ValueError,'CHECK_SOURCE_UNBOUND'):compile_doc(d,o)

    def test_unknown_input_fields_and_unbound_review_source_reject(self):
        d,o=fixture();d['ignoreMe']='age limit'
        with self.assertRaisesRegex(ValueError,'UNKNOWN_FIELD'):compile_doc(d,o)
        d,o=fixture();d['entries'][0]['sourceBindings'][0]['sourceId']='unknown'
        with self.assertRaisesRegex(ValueError,'BINDING_INVALID'):compile_doc(d,o)

    def test_model_requires_model_specific_parameter_review(self):
        d,o=fixture();e=d['entries'][0];r=e['record'];r['kind']='MAX_CREDIBLE_AF'
        e['payloadSha256']=content_hash(q.payload(r));r['review']['contentSha256']=e['payloadSha256']
        with self.assertRaisesRegex(ValueError,'CHECK_INVALID'):compile_doc(d,o)
        e['checks']['PARAMETER_SCOPE']['status']='MODEL_PARAMETERS_MATCHED'
        self.assertEqual('MAX_CREDIBLE_AF',compile_doc(d,o)[0]['kind'])

    def test_compile_path_verifies_raw_then_replaces_only_identical_payload(self):
        d,o=fixture()
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);k.write(folder/'source.json',o)
            # Replace the fixture pin consistently with this actual source.
            actual=dict(SOURCE,sha256=k.sha((folder/'source.json').read_bytes()))
            e=d['entries'][0];r=e['record'];r['sources'][0]['sha256']=actual['sha256']
            e['payloadSha256']=content_hash(q.payload(r));r['review']['contentSha256']=e['payloadSha256']
            manifest={'sources':[dict(actual,path='source.json')]}
            prior=copy.deepcopy(r)
            records=q.qualify_from_manifest([d],manifest,folder/'manifest.json',[actual],[prior])
            self.assertEqual(1,len(records));self.assertIn('qualification',records[0]);self.assertNotIn('qualification',prior)
            prior['version']='changed'
            with self.assertRaisesRegex(ValueError,'CANNOT_REPLACE'):q.qualify_from_manifest([d],manifest,folder/'manifest.json',[actual],[prior])
            (folder/'source.json').write_text('{}',encoding='utf8')
            with self.assertRaisesRegex(ValueError,'SOURCE_SHA_CHANGED'):q.qualify_from_manifest([d],manifest,folder/'manifest.json',[actual],[])


if __name__=='__main__':unittest.main()
