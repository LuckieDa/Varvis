"""Synthetic public-source fixtures only; all transports in this suite are fake."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from fetch_public_literature import collect, Retriever, pmcid_from_metadata, url_for, sha, NoRedirect, worker, MAX_BODY
from test_literature_source_assertions import RECORD, ARTICLE, VCV, PMCID, PMID, record


def metadata(**change):
    row={'id':PMID,'source':'MED','pmcid':PMCID,'isOpenAccess':'Y',**change}
    return json.dumps({'hitCount':1,'resultList':{'result':[row]}}).encode()


class FetchTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        (self.root/'vcv.xml').write_bytes(RECORD)
        r=record();self.item={'path':'vcv.xml','sha256':sha(RECORD),'vcv':VCV,'hgncId':r['hgncId'],'allele':r['allele']}
        self.request={'schemaVersion':'denovo-public-citation-request-v1','records':[self.item]}
        self.fixture={'metadata':metadata(),'fulltext':ARTICLE};self.calls=[]

    def collect(self):
        fixture,calls=self.fixture,self.calls
        class Fake:
            def __init__(s,*args):s.requests=0;s.events=[]
            def get(s,kind,identifier):
                s.requests+=1;calls.append((kind,identifier));s.events.append({'kind':kind,'identifier':identifier,'attribution':'SYNTHETIC_TRANSPORT'})
                value=fixture[kind]
                if isinstance(value,Exception):raise value
                return value
        p=self.root/'request.json';p.write_text(json.dumps(self.request),encoding='utf-8')
        return collect(p,self.root/'output',Fake,self.root/'cache',True)

    def test_exact_pinned_record_drives_only_existing_public_citations(self):
        r=self.collect();self.assertEqual([('metadata',PMID),('fulltext',PMCID)],self.calls)
        self.assertEqual(ARTICLE,(self.root/'output/papers'/f'{PMCID}.xml').read_bytes())
        self.assertEqual('SOURCE_READY_FOR_QUALIFICATION',r['records'][0]['citations'][0]['status'])
        self.assertFalse(r['completeLiteratureSearch']);self.assertEqual(0,r['modelCalls'])

    def test_wrong_target_or_pin_stops_before_network(self):
        self.item['allele']['position']+=1;r=self.collect();self.assertEqual([],self.calls)
        self.assertEqual('DENOVO_PUBLIC_TARGET_IDENTITY_MISMATCH',r['records'][0]['reasonCode'])

    def test_bad_pin_is_not_ignored(self):
        self.item['sha256']='0'*64;r=self.collect();self.assertEqual([],self.calls);self.assertEqual('UNAVAILABLE',r['records'][0]['status'])

    def test_path_escape_is_rejected(self):
        self.item['path']='../outside.xml';r=self.collect();self.assertEqual([],self.calls);self.assertEqual('DENOVO_PUBLIC_PATH_OUTSIDE_REQUEST',r['records'][0]['reasonCode'])

    def test_duplicate_citations_do_not_repeat_transport(self):
        self.request['records'].append(dict(self.item));r=self.collect();self.assertEqual(2,len(self.calls));self.assertEqual('DUPLICATE_CITATION',r['records'][1]['citations'][0]['status'])

    def test_wrong_fulltext_pmid_not_accepted(self):
        self.fixture['fulltext']=ARTICLE.replace(('>'+PMID+'</article-id><title-group>').encode(),b'>111</article-id><title-group>')
        r=self.collect();self.assertEqual('DENOVO_PUBLIC_FULLTEXT_PMID_MISMATCH',r['records'][0]['citations'][0]['reasonCode']);self.assertEqual([],list((self.root/'output/papers').iterdir()))

    def test_closed_access_and_missing_citations_are_distinct(self):
        self.fixture['metadata']=metadata(isOpenAccess='N');r=self.collect();self.assertEqual(1,len(self.calls));self.assertEqual('DENOVO_PUBLIC_FULLTEXT_UNAVAILABLE',r['records'][0]['citations'][0]['reasonCode'])
        with self.assertRaisesRegex(ValueError,'CITATION_NOT_FOUND'):pmcid_from_metadata(b'{"hitCount":0,"resultList":{"result":[]}}',PMID)

    def test_metadata_wrong_record_and_ambiguity_rejected(self):
        for data in [metadata(id='123'),metadata(source='PMC'),b'{"hitCount":2,"resultList":{"result":[]}}']:
            with self.subTest(data=data),self.assertRaisesRegex(ValueError,'IDENTITY_CONFLICT'):pmcid_from_metadata(data,PMID)

    def test_malformed_metadata_is_a_specific_issue(self):
        for data in [b'[]',b'{"resultList":[]}',b'{"hitCount":1,"resultList":{"result":[null]}}']:
            with self.subTest(data=data), self.assertRaises(ValueError):pmcid_from_metadata(data,PMID)

    def test_citation_budget_records_omissions(self):
        with patch('fetch_public_literature.read_record',return_value={**record(),'pubmed':[{'pmid':str(i)} for i in range(1,100)]}):
            r=self.collect()
        self.assertEqual(4,len(r['records'][0]['citations']));self.assertEqual(95,r['records'][0]['omittedCitationCount'])

    def test_invalid_record_is_local_issue(self):
        self.request['records']=[None];r=self.collect();self.assertEqual('DENOVO_PUBLIC_RECORD_SCHEMA',r['records'][0]['reasonCode'])

    def test_arbitrary_url_and_redirect_are_rejected(self):
        for kind,identifier in [('metadata','1&url=evil'),('fulltext','../secret'),('patient','123')]:
            with self.assertRaises(ValueError):url_for(kind,identifier)
        with self.assertRaisesRegex(ValueError,'REDIRECT'):NoRedirect().redirect_request(None,None,302,'',{},'https://example.com')

    def retriever(self,online=True):
        a=self.root/'archive';a.mkdir();return Retriever(self.root/'cache',a,online)

    def test_offline_is_explicit_and_spawns_nothing(self):
        r=self.retriever(False)
        with patch('fetch_public_literature.subprocess.run') as run,self.assertRaisesRegex(ValueError,'NETWORK_DISABLED'):r.get('metadata',PMID)
        run.assert_not_called();self.assertEqual(0,r.requests)

    def test_successful_cache_is_reusable_offline_and_corruption_fails(self):
        r=self.retriever();url=url_for('metadata',PMID);raw=metadata();pin=sha(raw)
        (r.cache/(pin+'.body')).write_bytes(raw);(r.cache/(sha(url.encode())+'.json')).write_text(json.dumps({'url':url,'sha256':pin,'retrievedAt':'SYNTHETIC'}))
        self.assertEqual(raw,r.get('metadata',PMID));self.assertEqual(0,r.requests)
        (r.cache/(pin+'.body')).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'SOURCE_OR_CACHE_INVALID'):r.get('metadata',PMID)

    def test_hard_subprocess_timeout_stops_following_requests(self):
        r=self.retriever()
        with patch('fetch_public_literature.subprocess.run',side_effect=subprocess.TimeoutExpired('synthetic',20)) as run:
            for kind,id_ in [('metadata',PMID),('fulltext',PMCID)]:
                with self.assertRaisesRegex(ValueError,'TIMEOUT'):r.get(kind,id_)
        self.assertEqual(1,run.call_count);self.assertEqual(1,r.requests)

    def test_429_shared_cooldown_even_without_body(self):
        r=self.retriever()
        def fake(args,stdout,**kwargs):
            stdout.write(b'{"status":"UNAVAILABLE","reasonCode":"DENOVO_PUBLIC_RATE_LIMIT","httpStatus":429}')
            stdout.flush();return subprocess.CompletedProcess(args,0)
        with patch('fetch_public_literature.subprocess.run',side_effect=fake) as run:
            with self.assertRaisesRegex(ValueError,'RATE_LIMIT'):r.get('metadata',PMID)
            other=Retriever(r.cache,r.archive,True)
            with self.assertRaisesRegex(ValueError,'COOLDOWN'):other.get('metadata','123')
        self.assertEqual(1,run.call_count)

    def test_request_and_time_budget_do_not_spawn(self):
        for budget in ['count','time']:
            r=Retriever(self.root/'cache',self.root,True)
            if budget=='count':r.requests=8
            else:r.started-=121
            with patch('fetch_public_literature.subprocess.run') as run,self.assertRaisesRegex(ValueError,'BUDGET|TIMEOUT'):r.get('metadata',PMID)
            run.assert_not_called()

    def test_worker_body_limit_never_writes_extra_bytes(self):
        class Response:
            status=200;headers={}
            def __enter__(s):return s
            def __exit__(s,*a):pass
            def read(s,n):return b'x'*n
        with patch('fetch_public_literature.urllib.request.build_opener') as build:
            build.return_value.open.return_value=Response();r=worker('fulltext',PMCID,self.root/'too-large.body')
        self.assertEqual('DENOVO_PUBLIC_BODY_LIMIT',r['reasonCode']);self.assertEqual(MAX_BODY,(self.root/'too-large.body').stat().st_size)


if __name__=='__main__':unittest.main()
