"""Synthetic source-to-identity chain; no real model or clinical adoption."""
from pathlib import Path
import tempfile
import unittest
from test_literature_acquisition import jats,zipped
from test_bundle_model import configuration,response
from test_core_context_review import LocalFixture
from acquire_public_literature import source_bundle
from paper_library_registration import register
from prepare_literature import prepare
from model_literature_contract import canonical,digest,parse
from pm3_literature_contract import VERSION
from process_pm3_paper import process


class Pipeline(unittest.TestCase):
    def test_windows_path_failure_is_not_missing_paper_data(self):
        from public_literature_workflow import safe_error
        error=OSError('private filesystem location must not enter public result')
        error.winerror=206
        self.assertEqual('DENOVO_LOCAL_PATH_TOO_LONG',safe_error(error))
        self.assertEqual('DENOVO_WORKFLOW_EXECUTION_FAILED',safe_error(OSError('private path')))

    def test_registered_sources_model_review_observation_identity_and_cache(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);library=root/'library';library.mkdir()
            raw=jats(True)
            package=zipped([('table.csv',b'Case,Gene,Transcript,Paternal allele,Maternal allele\nCase 1,EXAMPLE,NM_000001.1,c.2C>G,c.3C>T\n')])
            class Download:
                def get(self,*args):return package
            files,bundle=source_bundle({'pmcid':'PMC123'},raw,prepare(raw,'PMC123',digest(raw)),Download(),True)
            registration=register(library,'PMC123',files,bundle,{'sha256':digest(raw),
                'acquisitionUrl':'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'},criteria=('PM3',))
            config=configuration();config_path=root/'config.json';config_path.write_bytes(canonical(config))
            (root/'key.txt').write_text('synthetic-not-secret')
            local=LocalFixture(root);calls=[]
            def transport(c,key,body):
                content=parse(parse(body)['messages'][1]['content']);calls.append(content)
                units={u['text']:u for u in content['units'] if u['kind']=='SUPPLEMENT_CELL'}
                target_ids=set(content['workUnit']['targetUnitIds'])
                assessment=next(u for u in content['units'] if u['unitId'] in target_ids)
                candidate={'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
                    'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':assessment['unitId'],'quote':assessment['text']}}]}
                field_values=[('geneSymbol','EXAMPLE'),('transcript','NM_000001.1'),('targetVariant','c.2C>G'),('partnerVariant','c.3C>T')]
                if all(v in units for _,v in field_values) and 'Case 1' in units and any(units[v]['unitId'] in target_ids for _,v in field_values):
                    quote=lambda v:{'unitId':units[v]['unitId'],'quote':v}
                    candidate['cases']=[{'caseLabel':'1','caseCitation':quote('Case 1'),'caseOrigin':'UNCLEAR','originCitations':[],
                        'fields':[{'field':name,'sourceValue':v,'subject':'PROBAND','citation':quote(v)} for name,v in field_values]}]
                return response(candidate)
            args=(library,'PMC123',registration['bundleSha256'],{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']},config,config_path,root/'budget')
            partial=process(*args,root/'partial',allow_model=True,transport=transport,maximum_chunks=1)
            self.assertGreater(partial['chunkCount'],1)
            self.assertEqual('PARTIAL',partial['status']);self.assertEqual(partial['chunkProcessingOrder'][:1],partial['completedReviewChunks'])
            first=process(*args,root/'first',allow_model=True,transport=transport,identity_resolver=local.resolver())
            self.assertEqual('SOURCE_REVIEWED_SEMANTICS_PENDING',first['status'])
            self.assertNotIn('PM3_CHUNK_BUDGET_REACHED',first['issues'])
            self.assertEqual([],first['pendingReviewChunks'])
            self.assertTrue(first['observations']['cases'])
            case=first['observations']['cases'][0]
            self.assertEqual('TRANS',case['semantics']['observations'][0]['phase'])
            self.assertEqual('LOCAL_IDENTITIES_VERIFIED',case['resolvedObservations'][0]['status'])
            self.assertFalse(first['businessPublishable'])
            count=len(calls)
            second=process(*args,root/'second',allow_model=False,identity_resolver=local.resolver(),maximum_chunks=1)
            self.assertEqual('SOURCE_REVIEWED_SEMANTICS_PENDING',second['status'])
            self.assertEqual(0,second['modelCalls']);self.assertEqual(count,len(calls))
            self.assertEqual(first['observations'],second['observations'])
            # Production jobs nest full identity digests: chunk artifact paths
            # can exceed MAX_PATH even though the paper result itself fits.
            from literature_search_transport import filesystem_path
            deep=filesystem_path(root/('a'*64)/('b'*64)/('c'*64)/'paper')
            try:
                repeated=process(*args,deep,allow_model=False,identity_resolver=local.resolver())
                self.assertEqual(first['observations'],repeated['observations'])
                self.assertEqual(0,repeated['modelCalls'])
                self.assertEqual(count,len(calls))
            finally:
                import shutil
                shutil.rmtree(filesystem_path(root/('a'*64)),ignore_errors=False)

if __name__=='__main__':unittest.main()
