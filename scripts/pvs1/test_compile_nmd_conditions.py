import copy
import unittest
from compile_nmd_conditions import compile_policy, content_hash


class NmdCompilationTest(unittest.TestCase):
    def setUp(self):
        self.model={'modelId':'TEST','modelVersion':'1','cdsLength':303,
                    'exons':[{'ordinal':1,'cdsStart':1,'cdsEnd':80},
                             {'ordinal':2,'cdsStart':81,'cdsEnd':230},
                             {'ordinal':3,'cdsStart':231,'cdsEnd':303}]}
        self.policy={'schemaVersion':'pvs1-truncating-nmd-policy-v1','policyId':'TEST','review':{'status':'REVIEWED_IMPLEMENTED','reviewId':'TEST_ONLY'},
                     'transcriptModelBinding':{'canonicalSha256':content_hash(self.model)},
                     'scientificSources':[{'sourceId':'test-source'}], 'mechanism':{'status':'ESTABLISHED_LOSS_OF_FUNCTION'},
                     'nmdScope':{'relevantExonOrdinals':[1,2,3],'engineeringSupportedExonOrdinals':[2],
                                 'minimumDistanceToLastJunction':51,'minimumCdsPosition':81,'maximumExonLength':400,
                                 'exonRelevance':'TEST_ONLY included exons','sourceIds':['test-source']}}
        self.recipe={'basePolicySha256':content_hash(self.policy),'modelSha256':content_hash(self.model),
                     'minimumCdsPosition':60,'sourceIds':['test-source'],'basis':'TEST_ONLY explicit partial-exon domain',
                     'conditions':[{'conditionId':'bound','coordinate':'REFERENCE_EVENT_SPAN','minimumInclusive':60,
                                    'maximumExclusive':200,'sourceIds':['test-source'],'basis':'TEST_ONLY frozen limit'}]}

    def test_partial_exon_is_generated_but_original_review_not_inherited(self):
        original=copy.deepcopy(self.policy);r=compile_policy(self.policy,self.model,self.recipe)
        self.assertEqual([1,2],r['policy']['nmdScope']['engineeringSupportedExonOrdinals'])
        self.assertEqual('UNREVIEWED',r['policy']['review']['status'])
        self.assertEqual(original,r['basePolicy']);self.assertEqual(original,self.policy)
        self.assertFalse(r['runtimeEligible']);self.assertFalse(r['clinicalAcceptance'])
        self.assertEqual(51,r['policy']['nmdScope']['minimumDistanceToLastJunction'])
        self.assertEqual(400,r['policy']['nmdScope']['maximumExonLength'])

    def test_deterministic_for_other_names_without_gene_code(self):
        one=compile_policy(self.policy,self.model,self.recipe)
        self.assertEqual(one,compile_policy(self.policy,self.model,self.recipe))
        self.policy['policyId']='UNRELATED_BACKGROUND';self.recipe['basePolicySha256']=content_hash(self.policy)
        other=compile_policy(self.policy,self.model,self.recipe)
        self.assertEqual(one['policy']['nmdScope'],other['policy']['nmdScope'])

    def test_invalid_recipes(self):
        cases=[('minimumCdsPosition',True),('minimumCdsPosition',0),('minimumCdsPosition',303),
               ('minimumCdsPosition',60.0),('basePolicySha256','a'*64),('modelSha256','b'*64),
               ('conditions',[]),('sourceIds',['unknown']),('basis','')]
        for key,value in cases:
            with self.subTest(key=key,value=value):
                recipe=copy.deepcopy(self.recipe);recipe[key]=value
                with self.assertRaises(ValueError):compile_policy(self.policy,self.model,recipe)

    def test_invalid_conditions(self):
        for key,value in [('coordinate','UNKNOWN'),('minimumInclusive',200),('minimumInclusive',True),
                          ('maximumExclusive',2**40),('maximumExclusive',200.0),('sourceIds',['missing']),('basis',''),('softFail',True)]:
            with self.subTest(key=key,value=value):
                recipe=copy.deepcopy(self.recipe);recipe['conditions'][0][key]=value
                with self.assertRaises(ValueError):compile_policy(self.policy,self.model,recipe)

    def test_duplicate_condition_rejected(self):
        self.recipe['conditions']*=2
        with self.assertRaisesRegex(ValueError,'CONDITION_ID_INVALID'):compile_policy(self.policy,self.model,self.recipe)

    def test_missing_relevance_cannot_be_compiled(self):
        self.policy['nmdScope']['exonRelevance']='PENDING source review';self.recipe['basePolicySha256']=content_hash(self.policy)
        with self.assertRaisesRegex(ValueError,'BASE_APPLICABILITY_UNRESOLVED'):compile_policy(self.policy,self.model,self.recipe)

    def test_unreviewed_base_does_not_gain_review(self):
        self.policy['review']['status']='UNREVIEWED';self.recipe['basePolicySha256']=content_hash(self.policy)
        with self.assertRaisesRegex(ValueError,'BASE_APPLICABILITY_UNRESOLVED'):compile_policy(self.policy,self.model,self.recipe)

    def test_unrecognized_recipe_field_rejected(self):
        self.recipe['autoApprove']=True
        with self.assertRaisesRegex(ValueError,'RECIPE_FIELDS_INVALID'):compile_policy(self.policy,self.model,self.recipe)


if __name__=='__main__':unittest.main()
