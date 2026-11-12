from copy import deepcopy
import unittest
from pm3_partner_qualification import qualify

class Qualification(unittest.TestCase):
    def source(self):
        return {'status':'EXACT_SOURCE_ASSERTIONS_AVAILABLE','assertions':[{
            'scv':'SCV123.1','recordStatus':'current','sourceClassification':'Pathogenic',
            'reviewStatus':'reviewed by expert panel','dateLastEvaluated':'2025-01-01',
            'conditions':[{'tag':'TraitSet','children':[{'tag':'XRef','attributes':{'DB':'MONDO','ID':'0000001'}}]}],
            'criteriaStatements':[{'status':'EXPLICIT_SOURCE_LIST','criteria':[{'code':'PS3'},{'code':'PM2'},{'code':'PP3'}]}]}]}
    context={'qualification':'VERIFIED','diseaseId':'MONDO:0000001'}
    def test_explicit_expert_list_and_exact_disease(self):
        result=qualify(self.source(),self.context)
        self.assertEqual('PATHOGENIC',result['classification']);self.assertEqual('VERIFIED',result['independentClassification'])
        self.assertFalse(result['businessPublishable']);self.assertEqual(['SCV123.1'],result['qualifiedScvs'])
        self.assertEqual('UNKNOWN',qualify(self.source(),{**self.context,'diseaseId':'MONDO:0000002'})['classification'])
    def test_label_alone_or_comparison_or_unreviewed_cannot_qualify(self):
        for change in ({'criteriaStatements':[]},{'reviewStatus':'criteria provided, single submitter'},
                       {'dateLastEvaluated':'2025-01-01not-a-date'},{'recordStatus':'replaced'}):
            source=self.source();source['assertions'][0].update(change)
            self.assertEqual('UNKNOWN',qualify(source,self.context)['classification'])
        for code in ('PM3','PS1','PM5','PP5','BP6','PS4','PP1','PS2','PM6','UNKNOWN'):
            source=self.source();source['assertions'][0]['criteriaStatements'][0]['criteria'].append({'code':code})
            self.assertEqual('UNKNOWN',qualify(source,self.context)['independentClassification'])
    def test_conflicting_submitter_is_not_ignored(self):
        source=self.source();other=deepcopy(source['assertions'][0]);other.update(scv='SCV999.1',sourceClassification='Benign',reviewStatus='criteria provided, single submitter')
        source['assertions'].append(other)
        self.assertEqual('CONFLICT',qualify(source,self.context)['independentClassification'])

    def test_orphanet_exact_mapping_and_ambiguity_are_not_guessed(self):
        from unittest.mock import Mock
        source=self.source();source['assertions'][0]['conditions']=[{'tag':'XRef','attributes':{'DB':'Orphanet','ID':'ORPHA716'}}]
        names=Mock();names.lookup.return_value=[{'mapping':{'subject_id':'MONDO:0000001'}}]
        result=qualify(source,self.context,names)
        self.assertEqual('VERIFIED',result['independentClassification'])
        self.assertTrue(result['sourceAssertions'][0]['diseaseMappingProof'])
        names.lookup.assert_called_once_with('Orphanet:716')
        self.assertNotIn('diseaseMappingProof',source['assertions'][0])
        names.lookup.return_value.append({'mapping':{'subject_id':'MONDO:0000002'}})
        self.assertEqual(['PM3_PARTNER_DISEASE_MAPPING_AMBIGUOUS'],qualify(source,self.context,names)['issues'])
        self.assertEqual('UNKNOWN',qualify(source,self.context)['classification'])

    def test_nonexpert_and_absent_basis_are_reported_together(self):
        source=self.source();source['assertions'][0].update(reviewStatus='criteria provided, single submitter',criteriaStatements=[])
        result=qualify(source,self.context)
        self.assertIn('PM3_PARTNER_SUBMITTER_SOURCE_BINDING_INCOMPLETE',result['issues'])
        self.assertIn('PM3_PARTNER_COMPLETE_CRITERIA_LIST_MISSING',result['issues'])
        self.assertEqual(result['issues'],sorted(result['assertionAssessments'][0]['issues']))

if __name__=='__main__':unittest.main()
