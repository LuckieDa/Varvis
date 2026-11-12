import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from assemble_runtime_bundle import assemble, content_hash, escape_review_gaps

class RuntimeAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.config={'authorization':{'bindingId':'TEST_ONLY','boundAt':'2026-01-01T00:00:00Z','validUntil':'2027-01-01T00:00:00Z','operator':'TEST_ONLY engineering binding','purpose':'PROJECT_RESEARCH','allowedDataClasses':['SYNTHETIC']}}
        self.model={'modelId':'TEST_ONLY','transcript':'ENST000001.1'}
        self.policy={'schemaVersion':'pvs1-truncating-nmd-policy-v1','policyId':'TEST_ONLY','review':{'status':'REVIEWED_IMPLEMENTED','reviewId':'TEST_ONLY_ORIGINAL_REVIEW'},
          'scope':{'hgncId':'HGNC:123','mondoId':'MONDO:0000001','moi':'AUTOSOMAL_DOMINANT','assembly':'GRCh38','transcripts':[{'accession':'ENST000001','version':1}]},
          'transcriptModelBinding':{'canonicalSha256':content_hash(self.model)},'mechanism':{'status':'ESTABLISHED_LOSS_OF_FUNCTION','sourceIds':['TEST_ONLY']},
          'scientificSources':[{'sourceId':'TEST_ONLY','text':'Original full source preserved'}],'nmdScope':{'exonRelevance':'TEST_ONLY explicit relevant exons','minimumCdsPosition':151}}
        self.material={'policy':self.policy,'policySha256':content_hash(self.policy),'catalog':{'sources':[{'sourceId':'TEST_ONLY','original':'full'}],'entries':[{'entryId':'TEST_ONLY','restriction':'preserved'}]},
          'modelMaterial':{'canonicalSha256':content_hash(self.model),'model':self.model},'sourceReviewChecks':[{'status':'RAW_SHA_MATCH'}],'sourceChecks':[{'status':'RAW_SHA_MATCH'}]}
        self.facts={'records':[{'sourceRecord':{'hgnc id':'123','disease MONDO':'MONDO:0000001','allelic requirement':'monoallelic_autosomal','molecular mechanism':'loss of function','confidence':'definitive'}}]}
        self.profile={'resources':[{'fixed':'TEST_ONLY'}],'vepResourceId':'TEST_ONLY','registryBinding':{'fixed':'TEST_ONLY'}}
    def run_assembly(self):return assemble(self.config,self.facts,'a'*64,[self.material],self.profile)
    def test_preserves_full_policy_and_source_with_exact_binding(self):
        before=copy.deepcopy(self.material);b,u,a=self.run_assembly()
        self.assertEqual(before,self.material);self.assertEqual(self.policy,b['policies'][0]);self.assertEqual(self.material['catalog']['entries'],b['catalog']['entries'])
        self.assertEqual(1,a['boundPolicies']);self.assertFalse(a['scientificPolicyChanges']);self.assertEqual(['SYNTHETIC'],u['entries'][0]['allowedDataClasses'])
    def test_missing_exact_g2p_background_is_not_fabricated(self):
        self.facts['records'][0]['sourceRecord']['disease MONDO']='MONDO:9999999';b,u,a=self.run_assembly()
        self.assertEqual(1,a['boundPolicies']);self.assertEqual(content_hash([]),u['entries'][0]['sourceRecordSetSha256'])
        self.assertEqual('MONDO:0000001',u['entries'][0]['scope']['mondoId'])
    def test_g2p_conflict_weakness_and_inheritance_remain_blocked(self):
        for field,value,reason in [('molecular mechanism','gain of function','G2P_MECHANISM_CONFLICT'),('confidence','limited','G2P_SOURCE_STRENGTH_REQUIRES_REVIEW'),('allelic requirement','biallelic_autosomal','G2P_INHERITANCE_CONFLICT')]:
            with self.subTest(field=field):
                saved=copy.deepcopy(self.facts);self.facts['records'][0]['sourceRecord'][field]=value;b,u,a=self.run_assembly()
                self.assertEqual([],u['entries']);self.assertIn(reason,a['policies'][0]['reasonCodes']);self.facts=saved
    def test_draft_or_missing_source_does_not_gain_adoption_and_restriction_stays(self):
        self.policy['review']['status']='UNREVIEWED';self.material['policySha256']=content_hash(self.policy)
        b,u,a=self.run_assembly();self.assertEqual([],u['entries']);self.assertEqual(self.material['catalog']['entries'],b['catalog']['entries'])
        self.assertIn('POLICY_SOURCE_REVIEW_INCOMPLETE',a['policies'][0]['reasonCodes'])
    def test_missing_model_review_original_or_exon_relevance_blocks(self):
        for key,value in [('modelMaterial',None),('sourceReviewChecks',[]),('sourceChecks',[])]:
            with self.subTest(key=key):
                original=self.material[key];self.material[key]=value;b,u,a=self.run_assembly();self.assertEqual(0,a['boundPolicies']);self.material[key]=original
        self.policy['nmdScope']['exonRelevance']='PENDING unknown relevance';self.material['policySha256']=content_hash(self.policy)
        self.assertEqual(0,self.run_assembly()[2]['boundPolicies'])
    def test_inline_original_review_is_preserved_but_cannot_mask_corrupt_external_review(self):
        self.material['sourceReviewChecks']=[]
        self.material['candidateBundleRawSha256']='a'*64
        self.material['originalCandidate']={'policies':[copy.deepcopy(self.policy)]}
        b,u,a=self.run_assembly();self.assertEqual(1,a['boundPolicies'])
        self.assertEqual('INLINE_REVIEW_IN_PINNED_ORIGINAL',a['policies'][0]['reviewWitness'])
        self.material['sourceReviewChecks']=[{'status':'INVALID'}]
        self.assertEqual(0,self.run_assembly()[2]['boundPolicies'])
    def test_no_implicit_patient_scope(self):
        self.config['authorization']['allowedDataClasses']=['PRIVACY_BOUNDED_RUNTIME']
        with self.assertRaisesRegex(ValueError,'SYNTHETIC_RESEARCH_BINDING_ONLY'):self.run_assembly()
    def test_conflicting_catalog_identity_never_silently_wins(self):
        other=copy.deepcopy(self.material);other['catalog']['entries'][0]['restriction']='removed'
        with self.assertRaisesRegex(ValueError,'CATALOG_ID_CONFLICT'):assemble(self.config,self.facts,'a'*64,[self.material,other],self.profile)
    def test_same_source_roles_are_retained_per_catalog_but_identity_changes_rejected(self):
        other=copy.deepcopy(self.material);p=other['policy'];p['policyId']='ANOTHER';other['policySha256']=content_hash(p)
        self.material['catalog']['sources'][0]['role']='Original first role'
        other['catalog']['sources'][0]['role']='Original second role'
        b,u,a=assemble(self.config,self.facts,'a'*64,[self.material,other],self.profile)
        records=b['catalog']['sources'][0]['originalCatalogSourceRecords']
        self.assertEqual(['Original first role','Original second role'],[r['record']['role'] for r in records])
        other['catalog']['sources'][0]['original']='DIFFERENT IDENTITY'
        with self.assertRaisesRegex(ValueError,'CATALOG_ID_CONFLICT'):assemble(self.config,self.facts,'a'*64,[self.material,other],self.profile)
    def test_new_background_requires_no_algorithm_change(self):
        other=copy.deepcopy(self.material);p=other['policy'];p['policyId']='ANOTHER';p['scope'].update(hgncId='HGNC:456',mondoId='MONDO:0000002');other['policySha256']=content_hash(p)
        b,u,a=assemble(self.config,self.facts,'a'*64,[self.material,other],self.profile)
        self.assertEqual(2,a['boundPolicies']);self.assertEqual(2,len(u['entries']));self.assertEqual(1,len(b['catalog']['entries']))
    def test_reviewed_outer_policy_does_not_approve_new_escape_domain(self):
        self.policy['schemaVersion']='pvs1-truncating-nmd-policy-v3'
        self.policy['escapeScope']={'status':'UNREVIEWED','reviewId':''}
        self.material['policySha256']=content_hash(self.policy)
        b,u,a=self.run_assembly();self.assertEqual(0,a['boundPolicies'])
        self.assertIn('POLICY_ESCAPE_DOMAIN_REVIEW_INCOMPLETE',a['policies'][0]['reasonCodes'])
        self.policy['escapeScope']={'status':'REVIEWED_FOR_PVS1_ESCAPE','reviewId':'TEST_ONLY_NEW_DOMAIN'}
        self.material['policySha256']=content_hash(self.policy)
        b,u,a=self.run_assembly();self.assertEqual(1,a['boundPolicies'])
        self.assertEqual(content_hash(self.policy),u['entries'][0]['policySha256'])

    def test_unadopted_escape_exon_gap_requires_pinned_exact_scope(self):
        self.policy['nmdScope']['relevantExonOrdinals'] = [1, 2]
        base_pin = content_hash(self.policy)
        self.material['policySha256'] = base_pin
        bundle, adoption, _ = self.run_assembly()
        prior = copy.deepcopy((bundle, adoption))
        draft = copy.deepcopy(self.policy)
        draft['review']['status'] = 'UNREVIEWED'
        draft['escapeScope'] = {
            'status': 'UNREVIEWED', 'sourceIds': ['TEST_ONLY'],
            'positionConditions': [{'coordinate': 'REFERENCE_PTC_SPAN', 'minimumInclusive': 450}],
            'exonAssessments': [{'ordinal': 2, 'biologicalRelevance': 'PRESENT', 'populationLof': 'UNKNOWN'}],
        }
        original = {'schemaVersion': 'pvs1-truncating-nmd-bundle-candidate-v1', 'policies': [draft],
                    'derivation': {'basePolicySha256': base_pin}}
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'escape-candidate.json'
            def reference():
                raw = json.dumps(original).encode()
                file.write_bytes(raw)
                return {'file': str(file), 'sha256': hashlib.sha256(raw).hexdigest()}

            ref = reference()
            gap = escape_review_gaps([ref], bundle, adoption)[base_pin]
            self.assertEqual('UNREVIEWED', gap['status'])
            self.assertEqual(ref['sha256'], gap['candidateRawSha256'])
            self.assertEqual(draft['escapeScope']['exonAssessments'], gap['exonAssessments'])
            self.assertEqual(prior, (bundle, adoption))
            file.write_bytes(b'changed original')
            with self.assertRaises(ValueError):
                escape_review_gaps([ref], bundle, adoption)
            draft['scope']['mondoId'] = 'MONDO:9999999'
            with self.assertRaisesRegex(ValueError, 'ESCAPE_REVIEW_CANDIDATE_SCOPE_MISMATCH'):
                escape_review_gaps([reference()], bundle, adoption)

if __name__=='__main__':unittest.main()
