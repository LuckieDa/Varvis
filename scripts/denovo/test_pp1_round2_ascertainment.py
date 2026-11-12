"""Round-two raw/JATS -> audit artifact -> normal calculate counterexamples.

Scientific policy, allele and model responses are synthetic. The actual source
loader, audit replay, source qualification and calculation are executed without
patches. Direct adapter guards additionally use those replayed qualifications.
"""
from copy import deepcopy
import unittest

from pp1_formal_segregation import evaluate
import test_pp1_formal_snapshot as snapshot


FIRST = 'Family F1 was ascertained on the basis of clinical phenotype before genotyping.'
LIMIT = 'Only variant-positive relatives were included in the reported segregation analysis.'
EQUIVALENT = ('Selection, sampling, genotyping and reporting in family F1 were independent '
              'of the target genotype conditional on clinical phenotype and clinical covariates.')
PGT = 'Embryos diagnosed as unaffected were selected for transfer.'


class RoundTwoAscertainment(unittest.TestCase):
    def setUp(self):
        # Composition avoids rediscovering the inherited first-round tests as
        # additional round-two tests.
        self.pipeline = snapshot.FormalSnapshot(methodName='runTest')
        self.pipeline.setUp()
        self.addCleanup(self.pipeline.doCleanups)

    def run_source(self, **kwargs):
        self.pipeline.build(**kwargs)
        result = self.pipeline.evaluate()
        self.assertEqual((0, 0, 1),
                         (result['modelCalls'], result['networkRequests'], result['evaluatorCalls']))
        self.assertEqual(2, self.pipeline.transports)
        self.assertFalse(result['clinicalApproval'])
        self.assertEqual(1, len(result['qualifications']))
        return result

    def mechanism(self, result):
        qualified = result['qualifications'][0]
        packet = qualified['ascertainmentReview']
        self.assertEqual(qualified['sourceIdentity'], packet['sourceIdentity'])
        self.assertEqual(qualified['target'], packet['target'])
        self.assertEqual(1, len(packet['families']))
        return next(iter(packet['families'].values()))

    def assert_restriction_blocks(self, result):
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertEqual(0, result['evaluation']['awardedPP1Points'])
        mechanism = self.mechanism(result)
        self.assertEqual('INELIGIBLE', mechanism['status'])
        self.assertTrue(mechanism['sufficientConditions'])
        self.assertTrue(mechanism['conflicts'])
        limitation = mechanism['conflicts'][0]['restrictionSourceRefs'][0]
        self.assertEqual('PMC123', limitation['pmcid'])
        self.assertEqual(self.pipeline.pin, limitation['bundleSha256'])
        self.assertTrue(limitation['unitId'] and limitation['sourceSha256'])
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_ASCERTAINMENT_GENOTYPE_DEPENDENT'
                            for n in result['evaluation']['needs']))

    def test_supported_first_sentence_cannot_hide_later_same_paragraph_restriction(self):
        result = self.run_source(ascertainment_text=FIRST + ' ' + LIMIT)
        self.assert_restriction_blocks(result)
        mechanism = self.mechanism(result)
        conflict = mechanism['conflicts'][0]
        self.assertEqual(conflict['supportSourceRefs'][0]['unitId'],
                         conflict['restrictionSourceRefs'][0]['unitId'])

    def test_uncited_later_paragraph_restriction_blocks_normal_calculate(self):
        result = self.run_source(extra_paragraphs=(LIMIT,))
        self.assert_restriction_blocks(result)
        mechanism = self.mechanism(result)
        conflict = mechanism['conflicts'][0]
        self.assertNotEqual(conflict['supportSourceRefs'][0]['unitId'],
                            conflict['restrictionSourceRefs'][0]['unitId'])

    def test_equivalent_mechanism_requires_compatible_adopted_model_condition(self):
        self.pipeline.policy.row['formalModel']['ascertainment'] = 'CONDITIONAL_SELECTION_AND_REPORTING_INDEPENDENCE'
        result = self.run_source(ascertainment_text=EQUIVALENT)
        self.assertEqual(('MET', 'SUPPORTING', 1),
                         (result['evaluation']['status'], result['evaluation']['strength'],
                          result['evaluation']['awardedPP1Points']))
        mechanism = self.mechanism(result)
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', mechanism['status'])
        self.assertEqual('SELECTION_AND_REPORTING_INDEPENDENT_OF_TARGET_GIVEN_PHENOTYPE',
                         mechanism['sufficientConditions'][0]['sourceBasis'])
        self.assertEqual([], result['evaluation']['needs'])

    def test_temporal_only_model_does_not_silently_adopt_equivalent_condition(self):
        result = self.run_source(ascertainment_text=EQUIVALENT)
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', self.mechanism(result)['status'])
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_ASCERTAINMENT_SOURCE_REQUIRED'
                            for n in result['evaluation']['needs']))

    def test_public_pgt_transfer_sentence_does_not_reject_synthetic_adult_mechanism(self):
        result = self.run_source(extra_paragraphs=(PGT,))
        self.assertEqual(('MET', 'SUPPORTING'),
                         (result['evaluation']['status'], result['evaluation']['strength']))
        reproductive = [r for r in self.mechanism(result)['records']
                        if r.get('purposeScope') == 'EMBRYO_TRANSFER_NOT_ADULT_SEGREGATION']
        self.assertEqual(1, len(reproductive))
        self.assertEqual('OUT_OF_SCOPE', reproductive[0]['classification'])

    def test_pgt_alone_does_not_authorize_adult_segregation(self):
        result = self.run_source(ascertainment=False, extra_paragraphs=(PGT,))
        self.assertEqual('UNKNOWN', self.mechanism(result)['status'])
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertFalse(self.mechanism(result)['sufficientConditions'])

    def test_legacy_typed_row_without_review_packet_cannot_authorize_adapter(self):
        replayed = self.run_source()
        qualified = deepcopy(replayed['qualifications'][0])
        self.assertTrue(any(r['kind'] == 'formalAscertainment' for r in qualified['observations']))
        qualified.pop('ascertainmentReview')
        result = evaluate([qualified], self.pipeline.policy.selected(), self.pipeline.binding['ledger'])
        self.assertEqual('NOT_EVALUATED', result['status'])
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_ASCERTAINMENT_SOURCE_REQUIRED' for n in result['needs']))

    def test_review_from_another_source_or_target_cannot_authorize_adapter(self):
        replayed = self.run_source()
        for mutate in (
            lambda packet: packet['sourceIdentity'].update(bundleSha256='f' * 64),
            lambda packet: packet['target'].update(diseaseId='OTHER:1'),
        ):
            qualified = deepcopy(replayed['qualifications'][0])
            mutate(qualified['ascertainmentReview'])
            result = evaluate([qualified], self.pipeline.policy.selected(), self.pipeline.binding['ledger'])
            self.assertEqual('NOT_EVALUATED', result['status'])
            self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_ASCERTAINMENT_SOURCE_REQUIRED' for n in result['needs']))

    def test_same_family_adverse_packet_cannot_be_masked_by_a_positive_source_packet(self):
        replayed = self.run_source()
        positive = replayed['qualifications'][0]
        adverse = deepcopy(positive)
        family = next(iter(adverse['ascertainmentReview']['families'].values()))
        family.update(status='INELIGIBLE', qualifiedSubjectLabels=[])
        result = evaluate([positive, adverse], self.pipeline.policy.selected(), self.pipeline.binding['ledger'])
        self.assertEqual('NOT_EVALUATED', result['status'])
        self.assertEqual(0, result['awardedPP1Points'])
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_ASCERTAINMENT_GENOTYPE_DEPENDENT' for n in result['needs']))


if __name__ == '__main__':
    unittest.main()
