import unittest
from pathlib import Path
from unittest.mock import patch
from test_pm3_cache_reuse import CacheReuse
from test_bundle_model import response
from pm3_literature_contract import VERSION, SCHEMA
from pm3_quote_repair import quote_overflows
from model_literature_contract import parse
from run_pm3_literature_model import run, replay


class QuoteRepair(unittest.TestCase):
    def setUp(self):
        self.fixture=CacheReuse();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.x=self.fixture.x;self.calls=0
    def transport(self,c,key,body):
        self.calls+=1
        content=parse(parse(body)['messages'][1]['content'])
        unit=next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
        return response({'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
            'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':unit['unitId'],
                'quote':'x'*601 if self.calls==1 else unit['text']}}]})
    def test_one_repair_keeps_failed_response_and_reservation_and_cache(self):
        r=run(*self.fixture.args,self.x.root/'repaired',allow_model=True,allow_quote_repair=True,transport=self.transport)
        self.assertEqual(2,r['modelCalls']);self.assertEqual('SOURCE_CHECKED_SEMANTICS_UNVERIFIED',r['status'])
        state=parse((self.x.root/'budget/budget.json').read_bytes())
        self.assertEqual(['FAILED_RESERVED','SUCCESS'],[c['status'] for c in state['calls']])
        self.assertEqual(1,len(state['pm3QuoteRepairs']));self.assertIsNone(state['stoppedReason'])
        prior=state['calls'][0];self.assertGreater(prior['chargedOrReservedMicroCredits'],0)
        raw=parse((self.x.root/'budget/responses'/prior['key']/'response.json').read_bytes())
        self.assertEqual(601,len(parse(raw['choices'][0]['message']['content'])['sourceAssessments'][0]['citation']['quote']))
        artifact=replay(self.x.library,self.x.root/'repaired/artifact.json')
        self.assertEqual('pm3-quote-length-repair-v2',artifact['binding']['formatRepair'])
        again=run(*self.fixture.args,self.x.root/'again',allow_model=False)
        self.assertEqual(0,again['modelCalls']);self.assertEqual(2,self.calls)
    def test_other_schema_errors_not_relabelled_as_quote_overflow(self):
        bad={'schemaVersion':VERSION,'cases':[],'coverage':'invented',
             'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':'/x','quote':'x'*601}}]}
        with self.assertRaisesRegex(ValueError,'NOT_ONLY_QUOTE_LENGTH'):quote_overflows(bad,SCHEMA)
    def test_excerpts_are_bounded_exact_offsets_without_replacing_source(self):
        from pm3_quote_repair import request
        from model_literature_contract import canonical
        text=('A source sentence. '*90)+'last.'
        original={'messages':[{'content':'system'},{'content':canonical({'units':[{'unitId':'u','text':text}]}).decode()}]}
        corrected=parse(request(original)['messages'][1]['content'])
        self.assertEqual(text,corrected['units'][0]['text'])
        self.assertEqual(text,''.join(x['quote'] for x in corrected['boundedCitationExcerpts']))
        for x in corrected['boundedCitationExcerpts']:
            self.assertEqual(text[x['start']:x['end']],x['quote']);self.assertLessEqual(len(x['quote']),480)
    def test_transport_stop_cannot_be_cleared(self):
        def fail(*args):self.calls+=1;raise ValueError('DENOVO_MODEL_RATE_LIMITED')
        with self.assertRaisesRegex(ValueError,'RATE_LIMITED'):
            run(*self.fixture.args,self.x.root/'failed',allow_model=True,allow_quote_repair=True,transport=fail)
        state=parse((self.x.root/'budget/budget.json').read_bytes())
        self.assertEqual('RATE_LIMITED',state['stoppedReason']);self.assertEqual(1,self.calls)
        self.assertNotIn('pm3QuoteRepairs',state)
    def test_second_invalid_response_stays_stopped(self):
        def always_bad(*args):
            self.calls=0
            return self.transport(*args)
        with self.assertRaisesRegex(ValueError,'RESPONSE_SCHEMA_INVALID'):
            run(*self.fixture.args,self.x.root/'failed',allow_model=True,allow_quote_repair=True,transport=always_bad)
        state=parse((self.x.root/'budget/budget.json').read_bytes())
        self.assertEqual(2,len(state['calls']));self.assertEqual('RESPONSE_SCHEMA_INVALID',state['stoppedReason'])
        self.assertEqual(1,len(state['pm3QuoteRepairs']))


if __name__=='__main__':unittest.main()
