from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from model_call_limits import extend,effective_call_limit
from model_literature_contract import canonical,digest,parse
from model_transport import public_config
from test_bundle_model import configuration,response


class CallLimits(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.c=configuration();self.c['maxCalls']=2
        self.state={'configurationSha256':digest(canonical(public_config(self.c))),
                    'calls':[{'key':str(i)*64,'status':'SUCCESS','chargedOrReservedMicroCredits':100} for i in range(2)],
                    'stoppedReason':None}
        self.save()

    def save(self):
        self.raw=canonical(self.state);(self.root/'budget.json').write_bytes(self.raw)

    def extension(self):
        return extend(self.c,self.root,digest(self.raw),4,authorization='USER_AUTHORIZED_CALL_LIMIT_EXTENSION',
                      reason='User authorized two independent public pedigree reads.')

    def test_preserves_failures_fees_stop_and_configuration(self):
        self.state['calls'][1]['status']='FAILED_RESERVED';self.state['stoppedReason']='SCHEMA_INVALID';self.save()
        self.extension();after=parse((self.root/'budget.json').read_bytes())
        self.assertEqual(self.state,{k:v for k,v in after.items() if k!='callLimitExtensions'})
        self.assertEqual(4,effective_call_limit(self.c,after,self.root))
        self.assertEqual(self.raw,(self.root/'call-limit-extensions'/(digest(self.raw)+'.json')).read_bytes())
        after['calls'][0]['chargedOrReservedMicroCredits']=0
        with self.assertRaisesRegex(ValueError,'HISTORY_CHANGED'):effective_call_limit(self.c,after,self.root)

    def test_two_calls_then_limit_and_money_are_still_enforced(self):
        from bundle_model_transport import invoke
        from pp1_pedigree import SCHEMA,VERSION
        self.extension();candidate={'schemaVersion':VERSION,'familyLabel':'Family X','panelBox':[0,0,1,1],'nodes':[],'parentEdges':[],'issues':[]}
        request={'model':self.c['model'],'max_tokens':self.c['maxOutputTokens'],
                 'response_format':{'json_schema':{'schema':SCHEMA}}}
        transport=lambda *args:response(candidate)
        for i in range(2):invoke(self.c,'fake-test-key',request,self.root,{'round':i},transport)
        after=parse((self.root/'budget.json').read_bytes());self.assertEqual(self.state['calls'],after['calls'][:2])
        with self.assertRaisesRegex(ValueError,'CALL_LIMIT'):invoke(self.c,'fake-test-key',request,self.root,{'round':2},transport)
        # Adding slots never adds money or releases old charges.
        self.c['budgetRmb']=0.000001;self.state['configurationSha256']=digest(canonical(public_config(self.c)));self.save();self.extension()
        with self.assertRaisesRegex(ValueError,'BUDGET_EXHAUSTED'):invoke(self.c,'fake-test-key',request,self.root,{'round':3},transport)

    def test_stale_unsettled_unauthorized_or_changed_archive_rejected(self):
        for change in ('stale','pending','unauthorized','too-large'):
            with self.subTest(change=change):
                args={'authorization':'USER_AUTHORIZED_CALL_LIMIT_EXTENSION','reason':'Explicitly authorized limited continuation.'}
                if change=='pending':self.state['calls'][0]['status']='RESERVED';self.save()
                if change=='unauthorized':args['authorization']='IMPLICIT'
                with self.assertRaises(ValueError):extend(self.c,self.root,'a'*64 if change=='stale' else digest(self.raw),
                                                         1001 if change=='too-large' else 4,**args)
                self.state['calls'][0]['status']='SUCCESS';self.save()
        self.extension();after=parse((self.root/'budget.json').read_bytes())
        (self.root/'call-limit-extensions'/(digest(self.raw)+'.json')).write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'ARCHIVE_CHANGED'):effective_call_limit(self.c,after,self.root)

if __name__=='__main__':unittest.main()
