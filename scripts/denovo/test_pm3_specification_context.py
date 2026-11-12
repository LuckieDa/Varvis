import unittest
from copy import deepcopy
from pm3_specification_context import SpecificationContext, DELEGATION, POLICY


def specification():
    clause = {'label': 'PM3', 'gene': ['TEST'], 'disease': ['MONDO:0000001'], 'applicability': 'Applicable',
              'baseStrength': 'Pathogenic', 'defaultStrength': 'Pathogenic Moderate',
              'strengthDescriptor': [{'strength': s, 'applicability': 'Applicable', 'status': 'approved', 'text': DELEGATION}
                                     for s in ['Supporting', 'Moderate', 'Strong', 'Very Strong']]}
    return {'specificationKey': 'GN001@1.0', 'source': {'rawSha256': 'a' * 64}, 'original': {
        'entContent': {}, 'ld': {'Gene': [{'entContent': {'HGNC': {'symbol': 'TEST', 'hgnc_id': 'HGNC:1'}}}],
        'RuleSet': [{'entContent': {'genes': [{'gene': 'TEST', 'diseases': [{'preferredMondoId': 'MONDO:0000001'}]}]}}],
        'CriteriaCode': [{'entContent': clause}]}}}


class Policies(unittest.TestCase):
    def setUp(self):
        self.supply = SpecificationContext.__new__(SpecificationContext)
        self.supply.source = {'catalogRawSha256': 'b' * 64, 'worldwideAbsenceClaim': False}
        self.spec = specification(); self.supply.specifications = [self.spec]
        self.row = {'identities': {'target': {'status': 'LOCAL_IDENTITY_VERIFIED', 'hgncId': 'HGNC:1', 'geneSymbol': 'TEST'}},
                    'diseaseContext': {'qualification': 'VERIFIED', 'inheritance': 'AR', 'diseaseId': 'MONDO:0000001'}}

    def result(self): return self.supply.select(self.row)

    def test_complete_delegation_uses_independently_verified_inheritance(self):
        r = self.result()
        self.assertEqual('SELECTED', r['status']); self.assertEqual(POLICY, r['policy'])
        self.assertEqual('EXACT_QUALIFIED_GENE_DISEASE_RELATIONSHIP', r['matches'][0]['inheritanceSupply'])
        self.assertFalse(r['clinicalApproval']); self.assertFalse(r['businessPublishable'])

    def test_child_specification_cannot_be_hidden_by_parent_context(self):
        self.row['diseaseContext'].update(reportedDiseaseIds=['MONDO:0000002'],
            diseaseScopeMapping={'method':'DIRECT_SUBCLASS_TO_EXISTING_GENE_DISEASE_CONTEXT'})
        self.assertEqual('SELECTED',self.result()['status'])
        child=deepcopy(self.spec);child['specificationKey']='CHILD@1'
        child['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredMondoId']='MONDO:0000002'
        child['original']['ld']['CriteriaCode'][0]['entContent']['disease']=['MONDO:0000002']
        self.supply.specifications.append(child)
        r=self.result();self.assertEqual('UNSUPPORTED',r['status'])
        self.assertEqual('PM3_SUBTYPE_SPECIFICATION_REQUIRES_EXACT_CONTEXT',r['reasonCode'])
        self.assertIsNone(r['policy'])

    def test_no_match_is_only_local_snapshot_absence(self):
        self.supply.specifications = []
        self.assertEqual('PM3_GENERAL_WITH_NO_MATCH_IN_LOCAL_SNAPSHOT', self.result()['reasonCode'])
        self.row['diseaseContext']['qualification'] = 'UNKNOWN'
        self.assertEqual('UNRESOLVED', self.result()['status'])

    def test_extra_conditions_or_missing_strength_cannot_use_general_fallback(self):
        for mutate in [lambda c: c.update(instructionsToUse='Only after functional testing'),
                       lambda c: c['strengthDescriptor'].pop(),
                       lambda c: c.update(additionalComments='Do not use homozygotes'),
                       lambda c: c.update(newUnknownCondition='required'),
                       lambda c: c['strengthDescriptor'][0].update(text='Use 10 unrelated families')]:
            self.spec = specification(); self.supply.specifications = [self.spec]
            mutate(self.spec['original']['ld']['CriteriaCode'][0]['entContent'])
            self.assertEqual('UNSUPPORTED', self.result()['status'])
            self.assertIsNone(self.result()['policy'])

    def test_inapplicable_and_conflict_are_separate(self):
        self.spec['original']['ld']['CriteriaCode'][0]['entContent']['applicability'] = 'Not applicable'
        self.assertEqual('NOT_APPLICABLE', self.result()['status'])
        self.supply.specifications.append(deepcopy(self.spec))
        self.assertEqual('CONFLICT', self.result()['status'])

    def test_missing_clause_and_scope_do_not_imply_absence(self):
        self.spec['original']['ld']['CriteriaCode'] = []
        self.assertEqual('PM3_MATCHING_SPECIFICATION_CLAUSE_MISSING', self.result()['reasonCode'])
        self.spec = specification(); self.supply.specifications = [self.spec]
        self.spec['original']['ld']['RuleSet'] = []
        self.assertEqual('PM3_PUBLISHED_SCOPE_UNRESOLVED', self.result()['reasonCode'])

    def test_missing_reviewed_attachment_takes_precedence_over_legacy_transcript_check(self):
        self.spec['original']['ld']['CriteriaCode'][0]['entContent']['additionalComments'] = 'Use the attached case table'
        association = self.spec['original']['ld']['RuleSet'][0]['entContent']['genes'][0]
        association['preferredTranscript'] = 'NM_000001.2'
        self.row['identities']['target']['transcript'] = 'NM_000001.1'
        self.supply.reviewed_release_issues = {self.spec['specificationKey']: 'PM3_PUBLISHED_ATTACHMENT_ORIGINAL_UNAVAILABLE'}
        result = self.result()
        self.assertEqual('UNRESOLVED', result['status'])
        self.assertEqual('PM3_PUBLISHED_ATTACHMENT_ORIGINAL_UNAVAILABLE', result['reasonCode'])
        self.assertIsNone(result['policy'])
        association['diseases'][0]['preferredModeOfInheritance'] = 'Autosomal dominant inheritance'
        self.assertEqual('PM3_PUBLISHED_SCOPE_UNRESOLVED', self.result()['reasonCode'])

    def test_conflicting_moi_gene_transcript_and_missing_disease_are_blocked(self):
        for mutate in [lambda g: g['diseases'][0].update(preferredModeOfInheritance='Autosomal dominant inheritance'),
                       lambda g: g['diseases'][0].update(modesOfInheritance=[{'modeOfInheritance':'Autosomal dominant inheritance'}]),
                       lambda g: g.update(preferredTranscript='NM_000001.1'),
                       lambda g: g['diseases'][0].pop('preferredMondoId')]:
            self.spec = specification(); self.supply.specifications = [self.spec]
            mutate(self.spec['original']['ld']['RuleSet'][0]['entContent']['genes'][0])
            self.assertEqual('UNRESOLVED', self.result()['status'])
        self.spec = specification(); self.supply.specifications = [self.spec]
        self.spec['original']['ld']['Gene'][0]['entContent']['HGNC']['hgnc_id'] = 'HGNC:2'
        self.assertEqual('UNRESOLVED', self.result()['status'])

    def test_unreviewed_entries_are_not_attached_as_case_facts(self):
        observations={'cases': [{'resolvedObservations': [self.row]}], 'unreviewedExtraction': {'cases':[{}]}}
        self.supply.attach(observations)
        self.assertEqual('SELECTED', observations['cases'][0]['resolvedObservations'][0]['policySelection']['status'])
        self.assertEqual({'cases':[{}]}, observations['unreviewedExtraction'])


if __name__ == '__main__': unittest.main()
