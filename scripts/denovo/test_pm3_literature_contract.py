import unittest
from copy import deepcopy
from pm3_literature_contract import requests, SCHEMA, VERSION, review
from model_literature_contract import canonical, parse
from prepare_literature_prompt import check_shape

class Contract(unittest.TestCase):
    def payload(self):
        return {'messages':[{'role':'system','content':'legacy'}, {'role':'user','content':canonical({'units':[], 'workUnit':{'targetUnitIds':[]}}).decode()}],
                'response_format':{'json_schema':{}}}
    def test_target_and_schema_without_mutating_shared_request(self):
        p=self.payload(); old=deepcopy(p)
        r=requests([p],{'geneSymbol':'GENE','variantAliases':['c.1A>G']})[0]
        self.assertEqual(old,p)
        self.assertEqual(SCHEMA,parse(r['messages'][1]['content'])['outputSchema'])
        self.assertIn('Compound heterozygous',r['messages'][0]['content'])
        self.assertIn('not an exhaustive list',r['messages'][0]['content'])
        self.assertIn('do not invent their coding HGVS',r['messages'][0]['content'])
    def test_private_target_field_rejected(self):
        with self.assertRaises(ValueError):
            requests([self.payload()],{'geneSymbol':'GENE','variantAliases':[], 'sampleId':'private'})
    def test_input_limit_no_truncation(self):
        with self.assertRaisesRegex(ValueError,'NOT_TRUNCATED'):
            requests([self.payload()],{'geneSymbol':'GENE','variantAliases':[]},maximum=10)
    def test_scoring_output_rejected(self):
        candidate={'schemaVersion':VERSION,'cases':[], 'coverage':'EXTRACTED_WITHIN_TARGET','sourceAssessments':[], 'score':1}
        with self.assertRaises(ValueError): check_shape(candidate,SCHEMA)
    def test_review_cannot_accept_other_schema(self):
        with self.assertRaises(ValueError): review({}, {'schemaVersion':'denovo-bounded-candidates-v1'})

import test_bundle_model
class SourceContract(unittest.TestCase):
    def setUp(self):
        self.fixture=test_bundle_model.BundleModelTests(); self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
    def candidate(self):
        c=self.fixture.candidate(); c['schemaVersion']=VERSION
        c['cases'][0]['fields'][0]['field']='partnerVariant'
        return c
    def test_review_feedback_locates_bad_quote_and_case_without_relaxing_checks(self):
        from pm3_literature_contract import source_findings
        c=self.candidate();source=self.fixture.source
        content={'units':source['units'],'workUnit':{'targetUnitIds':[u['unitId'] for u in source['units']]}}
        c['cases'][0]['fields'][0]['citation']['quote']='not in the original'
        c['cases'][0]['caseLabel']='unrelated patient'
        issues=source_findings(source,c,content)
        self.assertTrue(any(x['path']=='/cases/0/fields/0/citation' for x in issues))
        self.assertTrue(any(x['reasonCode']=='PM3_CASE_LABEL_NOT_ANCHORED' for x in issues))
        self.assertEqual('not in the original',c['cases'][0]['fields'][0]['citation']['quote'])
        with self.assertRaises(ValueError):review(source,c)
    def test_feedback_rejects_unseen_units_even_if_real(self):
        from pm3_literature_contract import source_findings
        issues=source_findings(self.fixture.source,self.candidate(),{'units':[],'workUnit':{'targetUnitIds':[]}})
        self.assertTrue(any('CITATION_OUTSIDE_ACTUAL_INPUT' in x['reasonCode'] for x in issues))
        self.assertTrue(all(not x.get('validAnchorUnitIds') for x in issues))
    def test_bad_field_is_quarantined_without_discarding_other_exact_fields(self):
        from pm3_literature_contract import review_isolated
        c=self.candidate();source=self.fixture.source
        valid=deepcopy(c['cases'][0]['fields'][0]);bad=deepcopy(valid)
        bad['citation']['quote']='unsupported quotation';c['cases'][0]['fields'].append(bad)
        ids={u['unitId'] for u in source['units']}
        result=review_isolated(source,c,ids,ids)
        self.assertEqual(1,len(result['cases']))
        self.assertEqual(1,len(result['cases'][0]['fields']))
        self.assertEqual(valid['sourceValue'],result['cases'][0]['fields'][0]['sourceValue'])
        self.assertEqual('/cases/0/fields/1',result['rejectedSourceItems'][0]['path'])
        self.assertEqual('MORE_CASES_OR_FIELDS',result['extractionCoverageProposal'])
        self.assertFalse(result['businessPublishable'])
        self.assertEqual(2,len(c['cases'][0]['fields']))
    def test_real_attachment_locator_preserved_without_semantic_upgrade(self):
        c=self.candidate(); r=review(self.fixture.source,c)
        self.assertEqual('R2C2',r['cases'][0]['fields'][0]['citation']['cell'])
        self.assertEqual('partnerVariant',r['cases'][0]['fields'][0]['field'])
        self.assertFalse(r['businessPublishable'])
        self.assertIn('ATTACHMENT_CASE_ATTRIBUTION_NOT_VERIFIED',r['cases'][0]['issues'])
    def test_bad_material_kind_quote_does_not_destroy_independently_anchored_case(self):
        from pm3_literature_contract import review_isolated
        c=self.candidate();source=self.fixture.source
        c['sourceAssessments'][0]['citation']['quote']='not present in source'
        ids={u['unitId'] for u in source['units']}
        result=review_isolated(source,c,ids,ids)
        self.assertEqual(1,len(result['cases']))
        self.assertEqual([],result['sourceAssessments'])
        self.assertIn('SOURCE_MATERIAL_KIND_UNVERIFIED',result['issues'])
        self.assertFalse(result['businessPublishable'])
        c['cases'][0]['caseLabel']='unrelated'
        with self.assertRaisesRegex(ValueError,'TARGET_ASSESSMENT_REQUIRED'):
            review_isolated(source,c,ids,ids)
    def test_forged_quote_and_unseen_unit_rejected(self):
        c=self.candidate(); c['cases'][0]['fields'][0]['citation']['quote']='invented variant'
        with self.assertRaises(ValueError): review(self.fixture.source,c)
        with self.assertRaises(ValueError): review(self.fixture.source,self.candidate(),allowed_units=set())
    def test_pm3_requires_own_registration(self):
        from bundle_model_contract import load
        with self.assertRaises(OSError): load(self.fixture.library,'PMC123',self.fixture.pin,criterion='PM3')
        with self.assertRaises(ValueError): load(self.fixture.library,'PMC123',self.fixture.pin,criterion='../PS2')
    def test_pm3_anchor_requires_own_explicit_unique_case_cell(self):
        c=self.candidate();c['cases'][0]['caseLabel']='2'
        with self.assertRaisesRegex(ValueError,'CASE_LABEL_NOT_ANCHORED'):review(self.fixture.source,c)
        c=self.candidate();c['cases'][0]['caseCitation']=deepcopy(c['cases'][0]['fields'][0]['citation'])
        with self.assertRaisesRegex(ValueError,'CASE_LABEL_NOT_ANCHORED'):review(self.fixture.source,c)
    def test_ptid_header_is_a_case_id_not_a_phenotype_or_scoring_claim(self):
        source=deepcopy(self.fixture.source)
        for u in source['units']:
            if u.get('cell')=='R1C1':u['text']='PtID'
        checked=review(source,self.candidate())
        self.assertEqual('1',checked['cases'][0]['caseLabel']);self.assertFalse(checked['businessPublishable'])

class Runner(unittest.TestCase):
    def setUp(self):
        self.fixture=test_bundle_model.BundleModelTests();self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        from model_literature_contract import parse,canonical
        f=self.fixture
        row=parse((f.library/'evidence/PS2/index.json').read_bytes()); row['criterion']='PM3'
        folder=f.library/'evidence/PM3';folder.mkdir()
        (folder/'index.json').write_bytes(canonical(row))
    def test_successful_transport_with_rejected_citation_counts_once(self):
        from run_pm3_literature_model import run,replay
        from test_bundle_model import response
        f=self.fixture;calls=[]
        def transport(c,key,body):
            calls.append(body)
            candidate={'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
                'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':'not-in-input','quote':'not supplied'}}]}
            return response(candidate)
        args=(f.library,'PMC123',f.pin,{'geneSymbol':'GENE1','variantAliases':['c.1A>G']},f.c,f.config,f.root/'budget')
        for name,count in [('first',1),('replay',0)]:
            result=run(*args,f.root/name,allow_model=True,transport=transport)
            self.assertEqual('SOURCE_CHECK_REJECTED',result['status']);self.assertEqual(count,result['modelCalls'])
            artifact=replay(f.library,f.root/name/'artifact.json')
            self.assertEqual([],artifact['review']['cases'])
        self.assertEqual(1,len(calls))
    def test_review_corrects_rejected_proposal_without_reextracting(self):
        from process_pm3_paper import process
        from test_bundle_model import response
        f=self.fixture;calls=[]
        def transport(c,key,body):
            content=parse(parse(body)['messages'][1]['content']);calls.append(content)
            unit=next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
            candidate={'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
                'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':unit['unitId'] if 'previousExtraction' in content else 'not-in-input','quote':unit['text'][:100]}}]}
            return response(candidate)
        args=(f.library,'PMC123',f.pin,{'geneSymbol':'GENE1','variantAliases':['c.1A>G']},f.c,f.config,f.root/'budget')
        result=process(*args,f.root/'corrected',allow_model=True,transport=transport,maximum_chunks=1)
        self.assertEqual(2,result['modelCalls']);self.assertEqual(1,len(result['completedReviewChunks']))
        self.assertEqual([],result['observations']['unreviewedExtraction']['cases'])
        self.assertEqual(1,len(result['observations']['sourceRejectedProposals']))
        self.assertIn('previousExtraction',calls[1]);self.assertEqual(2,len(calls))
    def test_rejected_review_never_reverts_to_initial_proposal(self):
        from process_pm3_paper import process
        from test_bundle_model import response
        f=self.fixture;calls=[]
        def transport(c,key,body):
            calls.append(body)
            return response({'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
                'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':'not-in-input','quote':'not supplied'}}]})
        args=(f.library,'PMC123',f.pin,{'geneSymbol':'GENE1','variantAliases':['c.1A>G']},f.c,f.config,f.root/'budget')
        for folder,count in [('rejected',2),('cached-rejected',0)]:
            r=process(*args,f.root/folder,allow_model=True,transport=transport,maximum_chunks=1)
            self.assertEqual(count,r['modelCalls']);self.assertEqual([],r['completedReviewChunks'])
            self.assertEqual([],r['observations']['cases']);self.assertEqual([],r['observations']['unreviewedExtraction']['cases'])
            self.assertIn('PM3_MODEL_REVIEW_SOURCE_CHECK_REJECTED',r['issues'])
        self.assertEqual(2,len(calls))
    def test_failed_reserved_attempt_is_counted_but_stopped_repeat_is_not(self):
        from run_pm3_literature_model import run,ModelAttemptFailure
        from process_pm3_paper import process
        f=self.fixture; calls=[]
        def timeout(c,key,body):
            calls.append(body)
            raise ValueError('DENOVO_MODEL_TIMEOUT')
        args=(f.library,'PMC123',f.pin,{'geneSymbol':'GENE1','variantAliases':['c.1A>G']},
              f.c,f.config,f.root/'budget')
        result=process(*args,f.root/'failed',allow_model=True,transport=timeout)
        self.assertEqual(1,result['modelCalls'])
        self.assertEqual('PARTIAL',result['status'])
        self.assertIn('DENOVO_MODEL_TIMEOUT',result['issues'])
        self.assertEqual([],result['completedReviewChunks'])
        with self.assertRaises(ModelAttemptFailure) as stopped:
            run(*args,f.root/'stopped',allow_model=True,transport=timeout)
        self.assertEqual(0,stopped.exception.model_calls)
        self.assertEqual(1,len(calls))
        ledger=parse((f.root/'budget/budget.json').read_bytes())
        self.assertEqual(1,len(ledger['calls']))
        self.assertEqual('FAILED_RESERVED',ledger['calls'][0]['status'])
        self.assertGreater(ledger['calls'][0]['chargedOrReservedMicroCredits'],0)
    def test_extraction_review_replay_cache_and_tamper(self):
        from run_pm3_literature_model import run,replay
        from model_literature_contract import parse,canonical
        from test_bundle_model import response
        f=self.fixture; calls=[]
        def transport(c,key,body):
            payload=parse(body);calls.append(payload)
            content=parse(payload['messages'][1]['content'])
            unit=next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
            candidate={'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
                'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':unit['unitId'],'quote':unit['text'][:100]}}]}
            return response(candidate)
        target={'geneSymbol':'GENE1','variantAliases':['c.1A>G']}
        args=(f.library,'PMC123',f.pin,target,f.c,f.config,f.root/'budget')
        self.assertEqual('MODEL_CACHE_MISSING',run(*args,f.root/'missing')['status'])
        self.assertEqual([],calls)
        first=run(*args,f.root/'extract',allow_model=True,transport=transport)
        self.assertEqual(1,first['modelCalls'])
        replay(f.library,f.root/'extract/artifact.json')
        cached=run(*args,f.root/'cached',allow_model=False)
        self.assertEqual(0,cached['modelCalls']);self.assertEqual(1,len(calls))
        second=run(*args,f.root/'review',previous=f.root/'extract/artifact.json',allow_model=True,transport=transport)
        self.assertEqual(1,second['modelCalls']);self.assertEqual(2,len(calls))
        value=replay(f.library,f.root/'review/artifact.json')
        self.assertFalse(value['businessPublishable'])
        from pm3_observations import collect
        observations=collect(f.library,[f.root/'extract/artifact.json', f.root/'review/artifact.json'])
        self.assertEqual([0],observations['processedChunks'])
        self.assertFalse(observations['businessPublishable'])
        self.assertEqual(2,len(calls))
        from process_pm3_paper import process
        pipeline=process(*args,f.root/'pipeline',maximum_chunks=1,allow_model=False)
        self.assertEqual(0,pipeline['modelCalls'])
        self.assertEqual([0],pipeline['completedReviewChunks'])
        self.assertEqual(2,len(calls))
        self.assertEqual('PARTIAL' if pipeline['chunkCount']>1 else 'SOURCE_REVIEWED_SEMANTICS_PENDING',pipeline['status'])
        value['request']['messages'][0]['content']='tampered'
        (f.root/'bad.json').write_bytes(canonical(value))
        with self.assertRaisesRegex(ValueError,'REQUEST_CHANGED'): replay(f.library,f.root/'bad.json')

if __name__=='__main__': unittest.main()
