"""Invented papers/identities; no network or clinical approval."""
import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import supply_selected_literature as supply
from test_literature_source_assertions import RECORD, ARTICLE, VCV, PMCID, PMID, record
from test_fetch_public_literature import metadata
from fetch_public_literature import sha, url_for


class SelectedSupplyTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        r=record();self.target={k:r[k] for k in ('hgncId','allele')}
        self.req={'schemaVersion':'denovo-selected-literature-request-v1','targets':[self.target],
            'records':[{'sha256':sha(RECORD),'vcv':VCV}],
            'documents':[{'sourceId':'synthetic-paper','pmcid':PMCID,'sha256':sha(ARTICLE),'alleles':[]}],
            'originals':{sha(b):base64.b64encode(b).decode() for b in (RECORD,ARTICLE)},
            'implementation':{n:sha((Path(supply.__file__).parent/n).read_bytes()) for n in supply.FILES}}

    def run_supply(self,online=False):
        with patch('fetch_public_literature.subprocess.run',side_effect=AssertionError('Unexpected network subprocess')):
            return json.loads(supply.process(json.dumps(self.req).encode(),self.root/'work',self.root/'cache',online))

    def test_existing_paper_automatically_gets_exact_binding_without_network(self):
        r=self.run_supply(True)
        self.assertEqual([{'sha256':sha(RECORD),'vcv':VCV}],r['documents'][0]['alleles'])
        self.assertEqual(0,r['retrieval']['networkRequests']);self.assertEqual([],r['targets'][0]['reasonCodes'])
        self.assertEqual('NONE',r['clinicalApproval']);self.assertEqual(0,r['modelCalls'])

    def test_unselected_record_is_never_retrieved_or_bound(self):
        self.target['allele']['position']+=1;r=self.run_supply(True)
        self.assertEqual([],r['retrieval']['records']);self.assertEqual([],r['documents'][0]['alleles'])
        self.assertEqual(['DENOVO_PUBLIC_NO_EXACT_CITATION_SOURCE'],r['targets'][0]['reasonCodes'])

    def test_offline_cache_supplies_missing_paper(self):
        self.req['documents']=[];self.req['originals'].pop(sha(ARTICLE));cache=self.root/'cache';cache.mkdir()
        for kind,id_,raw in [('metadata',PMID,metadata()),('fulltext',PMCID,ARTICLE)]:
            url=url_for(kind,id_);pin=sha(raw);(cache/(pin+'.body')).write_bytes(raw)
            (cache/(sha(url.encode())+'.json')).write_text(json.dumps({'url':url,'sha256':pin,'retrievedAt':'SYNTHETIC'}))
        r=self.run_supply();self.assertEqual(1,len(r['documents']));self.assertEqual(0,r['retrieval']['networkRequests'])
        self.assertEqual(ARTICLE,base64.b64decode(r['originals'][sha(ARTICLE)]))

    def test_offline_missing_cache_is_specific_not_absent_case(self):
        self.req['documents']=[];r=self.run_supply()
        self.assertEqual([],r['documents']);self.assertIn('DENOVO_PUBLIC_NETWORK_DISABLED',r['targets'][0]['reasonCodes'])

    def test_duplicate_citation_retains_full_binding_without_repeat(self):
        self.req['records']*=2;r=self.run_supply()
        self.assertEqual(1,len(r['documents'][0]['alleles']));self.assertEqual('DUPLICATE_CITATION',r['retrieval']['records'][1]['citations'][0]['status'])
        self.assertEqual(sha(ARTICLE),r['retrieval']['records'][1]['citations'][0]['sha256'])

    def test_corrupt_original_and_changed_implementation_are_rejected(self):
        self.req['originals'][sha(RECORD)]=base64.b64encode(b'altered').decode()
        with self.assertRaisesRegex(ValueError,'ORIGINAL_INVALID'):self.run_supply()
        self.req['implementation']['supply_selected_literature.py']='0'*64
        with self.assertRaisesRegex(ValueError,'REQUEST_INVALID'):self.run_supply()

    def test_extra_patient_fields_rejected(self):
        self.target['patient']='SYNTHETIC private'
        with self.assertRaisesRegex(ValueError,'TARGET_SCHEMA'):self.run_supply()

    def test_malformed_record_remains_explicit_issue(self):
        self.req['records']=[None];r=self.run_supply()
        self.assertIn('DENOVO_SUPPLY_CONFIGURED_VCV_INVALID',r['targets'][0]['reasonCodes'])


if __name__=='__main__':unittest.main()
