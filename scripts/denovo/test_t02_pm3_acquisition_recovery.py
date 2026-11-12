"""Bounded discovery continuation with synthetic transport and real acquisition.

These checks exercise unfinished system transfers, not variant qualification or
scientific scoring. Source bytes and the shared ledger are never repaired.
"""
from contextlib import nullcontext
from unittest.mock import patch
import unittest
import discover_public_literature
import test_literature_acquisition
from test_bundle_model import configuration
from model_literature_contract import canonical,digest,parse
from process_pm3_discovery import run,RETRYABLE_ACQUISITION


class T02Pm3AcquisitionRecovery(unittest.TestCase):
    def setUp(self):
        self.fixture=test_literature_acquisition.FileTest();self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.f=self.fixture;self.root=self.f.root;(self.root/'local').mkdir()
        self.config=configuration();self.config_path=self.root/'model.json'
        self.config_path.write_bytes(canonical(self.config))
        self.target={'geneSymbol':'GENE1','variantAliases':['c.1A>G']}
        self.allele={'assembly':'GRCh38','contig':'1','position':1,'reference':'A','alternate':'G'}
        self.found={'status':'DISCOVERY_ONLY_NOT_QUALIFIED','checkpoint':{'queries':[],
            'implementation':discover_public_literature.implementation_pin()},'networkRequests':0,
            'candidates':[self.f.candidate()],'resumeAvailable':False,'targets':[]}

    def execute(self,label,transport,continuation=None):
        with patch('process_pm3_discovery.discover',side_effect=AssertionError('pending candidate must resume before new discovery')) if continuation else patch(
                'process_pm3_discovery.discover',return_value=self.found), patch(
                'process_pm3_discovery.DownloadTransport',return_value=nullcontext(transport)):
            return run(self.root,self.f.library,self.f.cache,self.root/'budget',self.config,self.config_path,
                       self.target,self.allele,self.root/'local'/label,continuation=continuation)

    def continuation(self,label,result):
        return {'parentTaskId':'a'*64,'parentResultSha256':digest((self.root/'local'/label/'result.json').read_bytes()),
                'stateSha256':result['discoveryState']['sha256'],'statePath':f'local/{label}/discovery-state.json'}

    def test_transfer_failure_stays_pending_then_reuses_registered_success_without_redownload(self):
        failed_transport=self.f.fake(failure='PAPER_DOWNLOAD_TIMEOUT')
        first=self.execute('first',failed_transport)
        self.assertEqual(1,failed_transport.requests);self.assertEqual(1,first['discoveryState']['pendingPapers'])
        self.assertTrue(first['discoveryState']['resumeAvailable'])
        self.assertEqual('ACQUISITION',first['acquisitionRecovery'][0]['stage'])
        self.assertFalse(first['acquisitionRecovery'][0]['sourceAbsenceProven'])
        state=parse((self.root/'local/first/discovery-state.json').read_bytes())
        self.assertEqual([],state['attemptedPaperKeys'])
        successful=self.f.fake(test_literature_acquisition.jats())
        second=self.execute('second',successful,self.continuation('first',first))
        self.assertEqual(1,successful.requests);self.assertEqual('REGISTERED',second['acquisition']['papers'][0]['status'])
        pin=second['acquisition']['papers'][0]['registration']['bundleSha256']
        forbid=self.f.fake(failure='UNEXPECTED_NETWORK_AFTER_REGISTRATION')
        third=self.execute('third',forbid,self.continuation('second',second))
        self.assertEqual(0,forbid.requests);self.assertEqual(0,third['networkRequests'])
        self.assertEqual(pin,third['acquisition']['papers'][0]['registration']['bundleSha256'])
        self.assertEqual(0,third['modelCalls']);self.assertFalse((self.root/'budget/budget.json').exists())
        self.assertEqual('PARTIAL',third['status']);self.assertFalse(third['businessPublishable'])
        self.assertFalse(third['scientificExecution'])

    def test_retryable_system_codes_each_have_one_attempt_and_remain_source_bound(self):
        for number,reason in enumerate(sorted(RETRYABLE_ACQUISITION)):
            with self.subTest(reason=reason):
                transport=self.f.fake(failure=reason);label='transfer-'+str(number)
                result=self.execute(label,transport)
                self.assertEqual(1,transport.requests)
                self.assertEqual(reason,result['acquisitionRecovery'][0]['reasonCode'])
                self.assertEqual(1,result['discoveryState']['pendingPapers'])
                bad=self.continuation(label,result);bad['stateSha256']='0'*64
                with self.assertRaisesRegex(ValueError,'CONTINUATION_PIN_MISMATCH'):
                    self.execute('wrong-'+str(number),self.f.fake(failure='UNEXPECTED'),bad)

    def test_unavailable_or_wrong_bibliographic_source_is_not_silently_retried_or_qualified(self):
        for label,transport in [('not-available',self.f.fake(failure='PAPER_DOWNLOAD_NOT_AVAILABLE')),
                ('wrong-source',self.f.fake(test_literature_acquisition.jats().replace(b'>456<',b'>999<')))]:
            with self.subTest(label=label):
                result=self.execute(label,transport)
                self.assertEqual([],result['acquisitionRecovery'])
                self.assertEqual(0,result['discoveryState']['pendingPapers'])
                self.assertEqual([],result['papers']);self.assertEqual('PARTIAL',result['status'])
                self.assertFalse(result['businessPublishable']);self.assertFalse(result['scientificExecution'])


if __name__=='__main__':unittest.main()
