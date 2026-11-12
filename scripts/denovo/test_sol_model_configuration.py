"""Sol request compatibility and explicitly uncapped cumulative accounting."""
from pathlib import Path
import tempfile
import unittest

from model_literature_contract import canonical, parse, digest, completion_options, request_matches_config
from model_transport import config, public_config, money_limit
from model_call_limits import effective_call_limit
from bundle_model_transport import invoke
from test_bundle_model import configuration
from pp1_pedigree import SCHEMA, VERSION, request as pedigree_request


class SolConfiguration(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.c = configuration()
        self.c.update(model='gpt-6.1-sol', expectedResponseModel='gpt-6.1-sol',
                      inputCreditsPerMillion=6, outputCreditsPerMillion=37.5,
                      maxCalls=None, budgetRmb=None, cumulativeLimitPolicy='TRACK_ONLY')
        self.request = {'model': self.c['model'], **completion_options(self.c['model'], self.c['maxOutputTokens']),
                        'response_format': {'json_schema': {'schema': SCHEMA}}}
        self.candidate = {'schemaVersion': VERSION, 'familyLabel': 'Family X', 'panelBox': [0,0,1000,1000],
                          'nodes': [], 'parentEdges': [], 'issues': ['Synthetic transport fixture']}
        self.count = 0

    def transport(self, *args):
        self.count += 1
        return canonical({'model': self.c['model'], 'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30},
                          'choices': [{'finish_reason': 'stop', 'message': {'content': canonical(self.candidate).decode()}}]})

    def test_config_explicit_opt_in_and_exact_model(self):
        path = self.root/'config.json'; path.write_bytes(canonical(self.c))
        self.assertEqual(config(path), self.c)
        for change in ({'cumulativeLimitPolicy': 'TYPO'}, {'maxCalls': 100}, {'budgetRmb': 16.66},
                       {'model': 'gpt-unknown'}, {'expectedResponseModel': 'gemini-3-flash'}):
            path.write_bytes(canonical({**self.c, **change}))
            with self.subTest(change=change), self.assertRaises(ValueError): config(path)

    def test_sol_image_request_and_legacy_options(self):
        request = pedigree_request({'width':100,'height':100}, b'fixture-image', 'Family X', {}, self.c, 'EXTRACT')
        self.assertTrue(request_matches_config(request, self.c))
        self.assertEqual(request['reasoning_effort'], 'high')
        self.assertNotIn('temperature', request); self.assertNotIn('max_tokens', request)
        self.assertIn('image_url', request['messages'][1]['content'][1])
        self.assertEqual(completion_options('gemini-3-flash',8192,temperature=True,legacy_reasoning=True),
                         {'max_tokens':8192,'temperature':0,'reasoning_effort':'minimal'})

    def test_uncapped_calls_and_cost_still_accounted_cache_zero_calls(self):
        old = [{'key':str(i),'status':'SUCCESS','chargedOrReservedMicroCredits':10**8} for i in range(101)]
        state = {'configurationSha256':digest(canonical(public_config(self.c))), 'calls':old, 'stoppedReason':None}
        (self.root/'budget.json').write_bytes(canonical(state))
        self.assertEqual(effective_call_limit(self.c,state,self.root), float('inf'))
        self.assertEqual(money_limit(self.c), float('inf'))
        first = invoke(self.c,'synthetic-test-key',self.request,self.root,{'test':1},self.transport)
        second = invoke(self.c,'synthetic-test-key',self.request,self.root,{'test':1},self.transport)
        self.assertFalse(first[2]); self.assertTrue(second[2]); self.assertEqual(self.count,1)
        after = parse((self.root/'budget.json').read_bytes())
        self.assertEqual(after['calls'][:101],old)
        self.assertEqual(after['calls'][-1]['chargedOrReservedMicroCredits'],810)

    def test_stop_and_unknown_reservation_remain(self):
        for status, reason in [('FAILED_RESERVED','HTTP_429'),('RESERVED',None)]:
            state={'configurationSha256':digest(canonical(public_config(self.c))),
                   'calls':[{'key':'old','status':status,'chargedOrReservedMicroCredits':42}], 'stoppedReason':reason}
            before=canonical(state); (self.root/'budget.json').write_bytes(before)
            with self.subTest(status=status), self.assertRaises(ValueError):
                invoke(self.c,'synthetic-test-key',self.request,self.root,{},self.transport)
            self.assertEqual((self.root/'budget.json').read_bytes(),before)
        self.assertEqual(self.count,0)

    def test_wrong_model_or_request_is_not_silently_accepted(self):
        for change in ({'max_tokens':8192},{'reasoning_effort':'minimal'},{'temperature':0}):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError,'REQUEST_CONFIG_MISMATCH'):
                invoke(self.c,'synthetic-test-key',{**self.request,**change},self.root,{},self.transport)
        def wrong(*args):
            response=parse(self.transport()); response['model']='gemini-3-flash'; return canonical(response)
        with self.assertRaisesRegex(ValueError,'RESPONSE_MODEL_MISMATCH'):
            invoke(self.c,'synthetic-test-key',self.request,self.root,{},wrong)
        state=parse((self.root/'budget.json').read_bytes())
        self.assertEqual(state['calls'][-1]['status'],'FAILED_RESERVED')
        self.assertGreater(state['calls'][-1]['chargedOrReservedMicroCredits'],0)

    def test_old_ledger_is_not_reused_under_new_model_or_prices(self):
        old=configuration()
        before=canonical({'configurationSha256':digest(canonical(public_config(old))),'calls':[],'stoppedReason':None})
        (self.root/'budget.json').write_bytes(before)
        with self.assertRaisesRegex(ValueError,'BUDGET_CONFIGURATION_CHANGED'):
            invoke(self.c,'synthetic-test-key',self.request,self.root,{},self.transport)
        self.assertEqual((self.root/'budget.json').read_bytes(),before)
        self.assertEqual(self.count,0)


if __name__=='__main__': unittest.main()
