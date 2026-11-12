import tempfile
import unittest
from pathlib import Path
from test_bundle_model import configuration
from model_literature_contract import canonical,digest,parse
from model_transport import public_config
from resume_model_pilot import accept_unknown_charge


class Acceptance(unittest.TestCase):
    def test_server_failure_requires_review_and_retains_reserve_without_enabling_other_http_errors(self):
        for reason in ('HTTP_500','HTTP_502','HTTP_503','HTTP_504','HTTP_401','HTTP_403','HTTP_429'):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as folder:
                root=Path(folder);c=configuration();key='a'*64
                response=root/'responses'/key;response.mkdir(parents=True);(response/'request.json').write_bytes(b'old')
                call={'key':key,'status':'FAILED_RESERVED','reasonCode':'DENOVO_MODEL_'+reason,'chargedOrReservedMicroCredits':151882}
                state={'configurationSha256':digest(canonical(public_config(c))),'calls':[call],'stoppedReason':reason}
                raw=canonical(state);(root/'budget.json').write_bytes(raw);next_pin=digest(b'new checked request')
                auth={'schemaVersion':'model-unknown-charge-acceptance-v1',
                      'decision':'USER_ACCEPTS_UNKNOWN_CHARGE_RETAIN_FULL_RESERVATION',
                      'userStatement':'Synthetic explicit user acceptance for test only.'}
                with self.assertRaises(ValueError):accept_unknown_charge(c,root,digest(raw),next_pin,auth)
                self.assertEqual(raw,(root/'budget.json').read_bytes())
                auth.update(ongoingTaskAuthorization='USER_AUTHORIZED_AUTONOMOUS_CONTINUATION',offlineChecksPassed=True,
                            diagnosis='Reviewed transient service response; next source request is pinned after offline scheduling checks.',maxNextRequests=1)
                if reason in ('HTTP_401','HTTP_403','HTTP_429'):
                    with self.assertRaises(ValueError):accept_unknown_charge(c,root,digest(raw),next_pin,auth)
                    self.assertEqual(raw,(root/'budget.json').read_bytes())
                else:
                    accept_unknown_charge(c,root,digest(raw),next_pin,auth)
                    after=parse((root/'budget.json').read_bytes())
                    self.assertEqual([call],after['calls'])
                    self.assertEqual([next_pin],after['resumePermit']['requestSha256s'])
                    self.assertFalse(after['resumeHistory'][-1]['platformBillReconciled'])

    def test_ongoing_authorized_task_continues_after_three_without_resetting_ledger(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);c=configuration();key='a'*64
            response=root/'responses'/key;response.mkdir(parents=True);(response/'request.json').write_bytes(b'old')
            call={'key':key,'status':'FAILED_RESERVED','reasonCode':'DENOVO_MODEL_TIMEOUT','chargedOrReservedMicroCredits':151882}
            auth={'schemaVersion':'model-unknown-charge-acceptance-v1',
                  'decision':'USER_ACCEPTS_UNKNOWN_CHARGE_RETAIN_FULL_RESERVATION',
                  'userStatement':'Synthetic explicit continued task authorization, not a real request.'}
            state={'configurationSha256':digest(canonical(public_config(c))),'calls':[call],'stoppedReason':'TIMEOUT',
                   'resumeHistory':[dict(auth) for _ in range(3)]}
            raw=canonical(state);(root/'budget.json').write_bytes(raw);next_pin=digest(b'different reviewed request')
            with self.assertRaisesRegex(ValueError,'ADJUSTMENT_LIMIT'):accept_unknown_charge(c,root,digest(raw),next_pin,auth)
            self.assertEqual(raw,(root/'budget.json').read_bytes())
            auth.update(ongoingTaskAuthorization='USER_AUTHORIZED_AUTONOMOUS_CONTINUATION',offlineChecksPassed=True,
                        diagnosis='A different source with a validated schema is ready after local contract corrections.',maxNextRequests=1)
            for update in ({'offlineChecksPassed':False},{'maxNextRequests':2},{'diagnosis':'retry'}):
                with self.assertRaises(ValueError):accept_unknown_charge(c,root,digest(raw),next_pin,{**auth,**update})
            accept_unknown_charge(c,root,digest(raw),next_pin,auth)
            after=parse((root/'budget.json').read_bytes())
            self.assertEqual(state['calls'],after['calls']);self.assertEqual(state['resumeHistory'],after['resumeHistory'][:3])
            self.assertEqual([next_pin],after['resumePermit']['requestSha256s'])
            self.assertEqual(state['configurationSha256'],after['configurationSha256'])

    def test_user_acceptance_keeps_failed_charge_and_only_permits_next_pinned_request(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);c=configuration();key='a'*64
            response=root/'responses'/key;response.mkdir(parents=True);(response/'request.json').write_bytes(b'old')
            call={'key':key,'status':'FAILED_RESERVED','reasonCode':'DENOVO_MODEL_HTTP_0','chargedOrReservedMicroCredits':151882}
            state={'schemaVersion':'denovo-model-budget-v1','configurationSha256':digest(canonical(public_config(c))),
                   'calls':[call],'stoppedReason':'HTTP_0'}
            raw=canonical(state);(root/'budget.json').write_bytes(raw)
            next_pin=digest(b'new')
            with self.assertRaises(ValueError):accept_unknown_charge(c,root,digest(raw),next_pin,{})
            self.assertEqual(raw,(root/'budget.json').read_bytes())
            auth={'schemaVersion':'model-unknown-charge-acceptance-v1',
                  'decision':'USER_ACCEPTS_UNKNOWN_CHARGE_RETAIN_FULL_RESERVATION',
                  'userStatement':'Synthetic explicit user acceptance for test only.'}
            result=accept_unknown_charge(c,root,digest(raw),next_pin,auth)
            after=parse((root/'budget.json').read_bytes())
            self.assertEqual([call],after['calls']);self.assertFalse(result['platformBillReconciled'])
            self.assertEqual([next_pin],after['resumePermit']['requestSha256s'])
            self.assertEqual(raw,(root/'continuation-reviews'/(digest(raw)+'-budget.json')).read_bytes())
            with self.assertRaises(ValueError):accept_unknown_charge(c,root,digest(raw),next_pin,auth)

if __name__=='__main__':unittest.main()
