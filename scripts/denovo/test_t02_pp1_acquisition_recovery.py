"""Actual PP1 acquisition/registration with bounded synthetic HTTP transport.

Publication bytes/transport and selected background are synthetic, not clinical
validation. No model/provider calls or shared ledger edits occur in these tests.
"""
from contextlib import nullcontext
from copy import deepcopy
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

import discover_public_literature
import test_literature_acquisition
from test_bundle_model import configuration
from test_pp1_segregation import selection, ledger, context
from test_pp1_reading import Resolver
from model_literature_contract import canonical, digest, parse
from process_pp1_discovery import run

EXPECTED_RETRYABLE = frozenset({'PAPER_DOWNLOAD_TIMEOUT', 'PAPER_DOWNLOAD_TRANSPORT_FAILURE',
    'PAPER_DOWNLOAD_HTTP_FAILURE', 'PAPER_DOWNLOAD_WORKER_FAILURE',
    'PAPER_DOWNLOAD_BODY_TRUNCATED', 'PAPER_DOWNLOAD_BODY_SIZE_MISMATCH'})


class T02Pp1AcquisitionRecovery(unittest.TestCase):
    def setUp(self):
        self.fixture=test_literature_acquisition.FileTest();self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.f=self.fixture;self.root=self.f.root;(self.root/'local').mkdir()
        self.model=configuration();self.model_path=self.root/'model.json';self.model_path.write_bytes(canonical(self.model))
        self.target={'geneSymbol':'GENE1','variantAliases':['c.1A>G']}
        self.binding={'schemaVersion':'pp1-analysis-binding-v1','selection':selection(),'ledger':ledger()}
        self.found={'status':'DISCOVERY_ONLY_NOT_QUALIFIED','checkpoint':{'queries':[],
            'deferredCandidates':[],'searchMode':'LITVAR_ONLY','implementation':discover_public_literature.implementation_pin()},
            'queries':[],'networkRequests':0,'candidates':[self.f.candidate()],'resumeAvailable':False,'targets':[]}
        self.source=test_literature_acquisition.jats().replace(b'<p>A public source.</p>',b'<p>GENE1 c.1A&gt;G is reported in this synthetic source.</p>')

    def execute(self,label,transport,continuation=None,binding=None):
        with patch('process_pp1_discovery.discover',side_effect=AssertionError('resume candidate before new search')) if continuation else patch(
                'process_pp1_discovery.discover',return_value=self.found), patch(
                'process_pp1_discovery.DownloadTransport',return_value=nullcontext(transport)):
            return run(self.root,self.f.library,self.f.cache,self.root/'budget',self.model,self.model_path,
                self.target,binding or self.binding,context(),Resolver(),self.root/'local'/label,continuation=continuation)

    def continuation(self,label,result):
        return {'parentTaskId':'a'*64,'parentResultSha256':digest((self.root/'local'/label/'result.json').read_bytes()),
            'stateSha256':result['discoveryState']['sha256'],'statePath':f'local/{label}/discovery-state.json'}

    def publish(self,label):
        destination=os.environ.get('T02_PP1_ACQUISITION_OUTPUT')
        if not destination:return
        out=Path(destination)/label;out.mkdir(parents=True,exist_ok=True)
        source=self.root/'local'/label
        for name in ('result.json','discovery-state.json','artifacts.json'):
            if (source/name).is_file():(out/name).write_bytes((source/name).read_bytes())
        (out/'source-layer.json').write_bytes(canonical({'sourceClass':'SYNTHETIC_TEST_ONLY','actualAcquire':True,
            'actualExternalNetwork':0,'actualModelProviderCalls':0,'expectedSyntheticSourceSha256':digest(self.source)}))

    def test_timeout_stays_pending_then_registered_source_reuses_without_network_or_score(self):
        failed=self.f.fake(failure='PAPER_DOWNLOAD_TIMEOUT');first=self.execute('timeout',failed)
        self.assertEqual(1,failed.requests);self.assertEqual(1,first['discoveryState']['pendingPapers'])
        self.assertTrue(first['discoveryState']['resumeAvailable']);self.assertEqual([],first['papers'])
        self.assertEqual([],first['pendingRegisteredPapers'])
        first_state=(self.root/'local/timeout/discovery-state.json').read_bytes()
        state=parse(first_state);self.assertEqual([],state['attemptedPaperKeys'])
        self.assertEqual('PMC123',state['pendingCandidates'][0]['pmcid']);self.publish('timeout')
        success=self.f.fake(self.source);second=self.execute('registered',success,self.continuation('timeout',first))
        self.assertEqual(1,success.requests);self.assertEqual('REGISTERED',second['acquisition']['papers'][0]['status'])
        self.assertEqual('PP1_MODEL_DISABLED_CACHE_MISSING',second['papers'][0]['stopReason'])
        self.assertEqual(['PMC123'],second['pendingRegisteredPapers']);self.publish('registered')
        pin=second['acquisition']['papers'][0]['registration']['bundleSha256']
        forbid=self.f.fake(failure='UNEXPECTED_NETWORK_AFTER_REGISTERED_SOURCE')
        third=self.execute('reused',forbid,self.continuation('registered',second))
        self.assertEqual(0,forbid.requests);self.assertEqual(0,third['networkRequests'])
        self.assertEqual(pin,third['acquisition']['papers'][0]['registration']['bundleSha256'])
        self.assertEqual(0,third['modelAttempts']);self.assertFalse((self.root/'budget/budget.json').exists())
        self.assertFalse(third['scientificExecution']);self.assertFalse(third['businessPublishable'])
        self.assertFalse(third['wholeLiteratureAbsenceClaim']);self.publish('reused')
        self.assertEqual(first_state,(self.root/'local/timeout/discovery-state.json').read_bytes())

    def test_exact_six_transient_codes_remain_bound_pending_with_one_attempt_each(self):
        from process_pp1_discovery import RETRYABLE_ACQUISITION
        self.assertEqual(EXPECTED_RETRYABLE,RETRYABLE_ACQUISITION)
        for number,code in enumerate(sorted(EXPECTED_RETRYABLE)):
            with self.subTest(code=code):
                transport=self.f.fake(failure=code);label='transient-'+str(number)
                result=self.execute(label,transport)
                self.assertEqual(1,transport.requests);self.assertEqual(1,result['discoveryState']['pendingPapers'])
                self.assertTrue(result['discoveryState']['resumeAvailable'])
                self.assertEqual([],parse((self.root/'local'/label/'discovery-state.json').read_bytes())['attemptedPaperKeys'])
                self.assertEqual(code,result['acquisition']['papers'][0]['reasonCode'])
                self.assertEqual([],result['pendingRegisteredPapers']);self.assertEqual(0,result['modelAttempts'])
                self.publish(label)

    def test_state_pin_and_selected_allele_mismatch_reject_before_new_transport(self):
        first=self.execute('pinned',self.f.fake(failure='PAPER_DOWNLOAD_TIMEOUT'))
        before=(self.root/'local/pinned/discovery-state.json').read_bytes()
        for label,change in [('wrong-pin',{'stateSha256':'0'*64}),('wrong-scope',{})]:
            with self.subTest(label=label):
                continuation=self.continuation('pinned',first);continuation.update(change)
                bound=deepcopy(self.binding)
                if label=='wrong-scope':bound['selection']['allele']['position']+=1
                transport=self.f.fake(failure='UNEXPECTED_TRANSPORT')
                with self.assertRaisesRegex(ValueError,'DISCOVERY_CONTINUATION_(PIN_MISMATCH|IDENTITY)'):
                    self.execute(label,transport,continuation,binding=bound)
                self.assertEqual(0,transport.requests)
        self.assertEqual(before,(self.root/'local/pinned/discovery-state.json').read_bytes())

    def test_not_available_and_wrong_bibliographic_source_are_not_retryable_or_qualified(self):
        for label,transport in [('not-available',self.f.fake(failure='PAPER_DOWNLOAD_NOT_AVAILABLE')),
                ('wrong-source',self.f.fake(self.source.replace(b'>456<',b'>999<')))]:
            with self.subTest(label=label):
                result=self.execute(label,transport)
                self.assertEqual(1,transport.requests);self.assertEqual(0,result['discoveryState']['pendingPapers'])
                self.assertFalse(result['discoveryState']['resumeAvailable']);self.assertEqual([],result['papers'])
                state=parse((self.root/'local'/label/'discovery-state.json').read_bytes())
                self.assertEqual(['PMC123'],state['attemptedPaperKeys'])
                self.assertFalse(result['businessPublishable']);self.assertFalse(result['scientificExecution'])
                self.assertFalse(result['wholeLiteratureAbsenceClaim']);self.publish(label)


if __name__=='__main__':unittest.main()
