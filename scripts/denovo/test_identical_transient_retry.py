import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from copy import deepcopy
from test_bundle_model import configuration, response
from model_literature_contract import canonical, digest, parse
from bundle_model_transport import invoke, cached
import resume_model_pilot


class IdenticalRetry(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.c=configuration();self.binding={'source':'fixed-public-pm3'}
        self.request={'model':self.c['model'],'max_tokens':self.c['maxOutputTokens'],
            'response_format':{'json_schema':{'schema':{'properties':{
                'schemaVersion':{'enum':['pm3-literature-candidates-v1']}}}}},
            'messages':[{'role':'user','content':'Fixed public source; do not change to retry.'}]}
        def fail(*unused):raise ValueError('DENOVO_MODEL_HTTP_503')
        with patch('model_transport.time.time',return_value=100),self.assertRaisesRegex(ValueError,'HTTP_503'):
            invoke(self.c,'fake-secret',self.request,self.root,self.binding,fail)
        self.raw=(self.root/'budget.json').read_bytes();self.state=parse(self.raw)
        self.folder=self.root/'responses'/self.state['calls'][0]['key']
        self.auth={'schemaVersion':'model-unknown-charge-acceptance-v1',
            'decision':'USER_ACCEPTS_UNKNOWN_CHARGE_RETAIN_FULL_RESERVATION',
            'userStatement':'Synthetic explicit authorization for an ongoing public PM3 test only.',
            'ongoingTaskAuthorization':'USER_AUTHORIZED_AUTONOMOUS_CONTINUATION',
            'offlineChecksPassed':True,'maxNextRequests':1,
            'diagnosis':'Reviewed transient server failure; preserve the identical scientific input and its full failed reservation.',
            'retryIdenticalRequest':True}

    def permit(self,auth=None,now=200):
        with patch('resume_model_pilot.time.time',return_value=now):
            return resume_model_pilot.accept_identical_transient_retry(self.c,self.root,digest(self.raw),
                digest(canonical(self.request)),auth or self.auth)

    def test_identical_request_failure_is_preserved_then_success_is_cached(self):
        original=(self.folder/'request.json').read_bytes();result=self.permit()
        self.assertEqual('ONE_IDENTICAL_REQUEST_PERMITTED',result['status'])
        after=parse((self.root/'budget.json').read_bytes());self.assertEqual(self.state['calls'],after['calls'])
        archived=self.root/after['resumeHistory'][-1]['failedAttemptArchive']
        self.assertEqual(original,(archived/'request.json').read_bytes());self.assertFalse(self.folder.exists())
        candidate={'schemaVersion':'pm3-literature-candidates-v1','cases':[],
                   'coverage':'EXTRACTED_WITHIN_TARGET','sourceAssessments':[
                       {'materialKind':'UNCLEAR','citation':{'unitId':'fixed-unit','quote':'Fixed public source'}}]}
        count=[]
        def success(*args):count.append(1);return response(candidate)
        raw,receipt,reused=invoke(self.c,'fake-secret',self.request,self.root,self.binding,success)
        self.assertFalse(reused);self.assertEqual(1,len(count))
        state=parse((self.root/'budget.json').read_bytes())
        self.assertEqual(self.state['calls'][0],state['calls'][0]);self.assertEqual(2,len(state['calls']))
        self.assertEqual('SUCCESS',state['calls'][1]['status']);self.assertIsNone(state['resumePermit'])
        self.assertTrue(cached(self.c,self.request,self.root,self.binding)[2])
        self.assertEqual(original,(archived/'request.json').read_bytes())

    def test_no_default_retry_no_early_retry_no_changed_request(self):
        for edit,now in [({'retryIdenticalRequest':False},200),({'offlineChecksPassed':False},200),
                         ({'maxNextRequests':2},200),({},120)]:
            with self.subTest(edit=edit,now=now),self.assertRaises(ValueError):self.permit({**self.auth,**edit},now)
            self.assertEqual(self.raw,(self.root/'budget.json').read_bytes());self.assertTrue(self.folder.exists())
        with self.assertRaises(ValueError):
            resume_model_pilot.accept_identical_transient_retry(self.c,self.root,digest(self.raw),digest(b'changed'),self.auth)

    def test_pp1_same_payload_recovery_keeps_reservation_and_does_not_call_model(self):
        for version in ('pp1-family-facts-v1','pp1-family-source-audit-v1'):
            with self.subTest(version=version):
                root=self.root/version
                req=deepcopy(self.request)
                req['response_format']['json_schema']['schema']['properties']['schemaVersion']['enum']=[version]
                def fail(*unused):raise ValueError('DENOVO_MODEL_HTTP_503')
                with patch('model_transport.time.time',return_value=100),self.assertRaisesRegex(ValueError,'HTTP_503'):
                    invoke(self.c,'fake-secret',req,root,self.binding,fail)
                before=(root/'budget.json').read_bytes()
                with patch('resume_model_pilot.time.time',return_value=200):
                    result=resume_model_pilot.accept_identical_transient_retry(self.c,root,digest(before),digest(canonical(req)),self.auth)
                after=parse((root/'budget.json').read_bytes())
                self.assertEqual(parse(before)['calls'],after['calls'])
                self.assertEqual(0,result['modelCalls'])
                self.assertEqual([digest(canonical(req))],after['resumePermit']['requestSha256s'])

    def test_unregistered_contract_still_cannot_be_retried(self):
        req=deepcopy(self.request)
        req['response_format']['json_schema']['schema']['properties']['schemaVersion']['enum']=['arbitrary-v1']
        (self.folder/'request.json').write_bytes(canonical(req))
        with patch('resume_model_pilot.time.time',return_value=200),self.assertRaisesRegex(ValueError,'LITERATURE_REQUEST_REQUIRED'):
            resume_model_pilot.accept_identical_transient_retry(self.c,self.root,digest(self.raw),digest(canonical(req)),self.auth)
        self.assertEqual(self.raw,(self.root/'budget.json').read_bytes())
        self.assertTrue(self.folder.is_dir())

    def test_completed_response_or_extra_files_prevent_relocation(self):
        for name in ('response.json','receipt.json','unexpected.txt'):
            p=self.folder/name;p.write_text('preserved')
            with self.subTest(name=name),self.assertRaises(ValueError):self.permit()
            self.assertEqual('preserved',p.read_text());p.unlink()
        self.assertEqual(self.raw,(self.root/'budget.json').read_bytes())

    def test_rate_limit_authentication_unknown_transport_and_second_retry_refused(self):
        for reason in ('HTTP_401','HTTP_403','HTTP_429','RATE_LIMITED','HTTP_0','TIMEOUT'):
            state=deepcopy(self.state);state['stoppedReason']=reason
            state['calls'][0]['reasonCode']='DENOVO_MODEL_'+reason
            self.raw=canonical(state);(self.root/'budget.json').write_bytes(self.raw)
            with self.subTest(reason=reason),self.assertRaises(ValueError):self.permit()
        self.raw=canonical(self.state);(self.root/'budget.json').write_bytes(self.raw);self.permit()
        def fail(*args):raise ValueError('DENOVO_MODEL_HTTP_503')
        with self.assertRaisesRegex(ValueError,'HTTP_503'):invoke(self.c,'fake-secret',self.request,self.root,self.binding,fail)
        self.raw=(self.root/'budget.json').read_bytes()
        with self.assertRaisesRegex(ValueError,'IDENTICAL_RETRY_LIMIT'):self.permit(now=10**12)
        self.assertEqual(self.raw,(self.root/'budget.json').read_bytes())

    def test_budget_write_failure_restores_original_failed_folder(self):
        with patch('resume_model_pilot.atomic',side_effect=OSError('simulated disk failure')):
            with self.assertRaises(OSError):self.permit()
        self.assertTrue(self.folder.is_dir());self.assertEqual(self.raw,(self.root/'budget.json').read_bytes())

    def test_original_budget_call_limit_and_pins_remain_enforced(self):
        for edit in ({'budgetRmb':0.00001},{'maxCalls':1}):
            c={**self.c,**edit};state=deepcopy(self.state)
            state['configurationSha256']=digest(canonical(resume_model_pilot.public_config(c)))
            raw=canonical(state);(self.root/'budget.json').write_bytes(raw)
            with self.subTest(edit=edit),self.assertRaises(ValueError):
                resume_model_pilot.accept_identical_transient_retry(c,self.root,digest(raw),
                    digest(canonical(self.request)),self.auth)
            self.assertEqual(raw,(self.root/'budget.json').read_bytes());self.assertTrue(self.folder.is_dir())
        (self.root/'budget.json').write_bytes(self.raw)
        for budget_pin,request_pin in [('0'*64,digest(canonical(self.request))),
                                       (digest(self.raw),'0'*64)]:
            with self.assertRaises(ValueError):
                resume_model_pilot.accept_identical_transient_retry(self.c,self.root,budget_pin,request_pin,self.auth)
            self.assertEqual(self.raw,(self.root/'budget.json').read_bytes());self.assertTrue(self.folder.is_dir())

    def test_no_resume_while_unsettled_and_no_other_schema(self):
        state=deepcopy(self.state);state['calls'][0]['status']='RESERVED'
        self.raw=canonical(state);(self.root/'budget.json').write_bytes(self.raw)
        with self.assertRaises(ValueError):self.permit()
        self.raw=canonical(self.state);(self.root/'budget.json').write_bytes(self.raw)
        altered=deepcopy(self.request)
        altered['response_format']['json_schema']['schema']['properties']['schemaVersion']['enum']=['other']
        (self.folder/'request.json').write_bytes(canonical(altered))
        with self.assertRaises(ValueError):
            resume_model_pilot.accept_identical_transient_retry(self.c,self.root,digest(self.raw),digest(canonical(altered)),self.auth)


if __name__=='__main__':unittest.main()
