"""Source qualification is engineering reuse, never new scientific approval."""
import copy
import tempfile
from pathlib import Path
from assemble_materials import source_checks, sha
import test_assemble_runtime_bundle as legacy


class SourceQualificationTest(legacy.RuntimeAssemblyTest):
    def setUp(self):
        super().setUp()
        self.config={'schemaVersion':'pvs1-source-qualified-assembly-input-v1',
            'release':{'bindingId':'TEST_ONLY_SOURCE_RELEASE','assembledAt':'2026-01-01T00:00:00Z'},
            'sourceQualification':{'policyId':'TEST_ONLY_PROJECT_USE','version':'1.0.0','purpose':'PROJECT_RESEARCH',
                'allowedDataClasses':['SYNTHETIC','PRIVACY_BOUNDED_RUNTIME'], 'createsExpertReview':False,'clinicalAcceptance':False}}
        self.raw=b'TEST_ONLY fixed source text'
        self.policy['scientificSources']=[{'sourceId':'TEST_ONLY','sha256':sha(self.raw),'relativePath':'original.txt'}]
        self.policy['appliedStrength']='VERY_STRONG'
        self.repin()

    def repin(self):
        from assemble_runtime_bundle import content_hash
        self.material['policySha256']=content_hash(self.policy)
        self.material['candidateBundleRawSha256']='a'*64
        self.material['originalCandidate']={'policies':[copy.deepcopy(self.policy)]}
        self.material['sourceChecks']=[{'source':copy.deepcopy(s),'status':'RAW_SHA_MATCH','bytes':len(self.raw)} for s in self.policy['scientificSources']]

    # The base suite is run separately for the unchanged v2 path.
    def test_preserves_full_policy_and_source_with_exact_binding(self):
        before=copy.deepcopy(self.material);bundle,knowledge,audit=self.run_assembly()
        self.assertEqual(before,self.material);self.assertEqual(self.policy,bundle['policies'][0])
        self.assertEqual(1,audit['boundPolicies']);entry=knowledge['entries'][0]
        self.assertNotIn('review',entry);self.assertNotIn('validUntil',str(entry))
        self.assertEqual(self.policy['review'],entry['qualification']['originalPolicyReview'])
        self.assertEqual(['SYNTHETIC','PRIVACY_BOUNDED_RUNTIME'],entry['allowedDataClasses'])
        self.assertFalse(entry['qualification']['createsExpertReview'])

    def test_missing_model_review_original_or_exon_relevance_blocks(self):
        for key,value in [('modelMaterial',None),('sourceChecks',[])]:
            before=copy.deepcopy(self.material);self.material[key]=value
            self.assertEqual(0,self.run_assembly()[2]['boundPolicies']);self.material=before

    def test_no_implicit_patient_scope(self):
        for field,value in [('purpose','CLINICAL'),('clinicalAcceptance',True),('createsExpertReview',True),('allowedDataClasses',['SYNTHETIC'])]:
            before=copy.deepcopy(self.config);self.config['sourceQualification'][field]=value
            with self.assertRaisesRegex(ValueError,'SOURCE_QUALIFICATION_POLICY_INVALID'):self.run_assembly()
            self.config=before

    def test_inline_original_review_is_preserved_but_cannot_mask_corrupt_external_review(self):
        self.material['sourceReviewChecks']=[]
        self.assertEqual(1,self.run_assembly()[2]['boundPolicies'])
        self.material['sourceReviewChecks']=[{'status':'INVALID'}]
        self.assertEqual(0,self.run_assembly()[2]['boundPolicies'])

    def test_reviewed_outer_policy_does_not_approve_new_escape_domain(self):
        self.policy['schemaVersion']='pvs1-truncating-nmd-policy-v3'
        self.policy['escapeScope']={'status':'UNREVIEWED','reviewId':''};self.repin()
        self.assertEqual(0,self.run_assembly()[2]['boundPolicies'])

    def test_source_bytes_are_really_checked_before_qualification(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'original.txt';p.write_bytes(self.raw)
            self.material['sourceChecks']=source_checks(self.policy['scientificSources'],{'resourceRoot':folder})
            self.assertEqual(1,self.run_assembly()[2]['boundPolicies'])
            p.write_bytes(self.raw+b'changed')
            self.material['sourceChecks']=source_checks(self.policy['scientificSources'],{'resourceRoot':folder})
            self.assertEqual(0,self.run_assembly()[2]['boundPolicies'])

    def test_flags_cannot_substitute_for_full_source_identity_and_bytes(self):
        for change in [lambda c:c.pop('source'),lambda c:c.update(bytes=0),lambda c:c.update(bytes=4_000_001),lambda c:c['source'].update(sha256='0'*64)]:
            before=copy.deepcopy(self.material);change(self.material['sourceChecks'][0])
            self.assertEqual(0,self.run_assembly()[2]['boundPolicies']);self.material=before

    def test_no_new_variant_or_expiry_required_for_reusable_source_release(self):
        bundle,knowledge,audit=self.run_assembly()
        self.assertEqual({'hgncId','mondoId','moi','assembly','transcript'},set(knowledge['entries'][0]['scope']))
        self.assertNotIn('variant',str(knowledge['entries'][0]['scope']))
        self.assertEqual('SOURCE_QUALIFIED_PROJECT_RESEARCH',audit['policies'][0]['status'])

    def test_original_synthetic_only_scope_cannot_be_promoted(self):
        self.policy['review']['kind']='IMPLEMENTER_SOURCE_TRANSLATION_SYNTHETIC_RESEARCH_ONLY';self.repin()
        bundle,knowledge,audit=self.run_assembly()
        self.assertEqual(0,audit['boundPolicies'])
        self.assertIn('POLICY_ORIGINAL_USE_RESTRICTED',audit['policies'][0]['reasonCodes'])


if __name__=='__main__':
    import unittest
    unittest.main()
