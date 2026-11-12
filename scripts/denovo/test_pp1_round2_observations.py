"""Synthetic originals exercise round-two clinical binding through normal calculate.

Policy probabilities and resolver are explicitly synthetic. Independent expected
BF1296/529 follows (2*.9/(.25+.9))**2; it is not calibrated to a real case.
"""
from copy import deepcopy
import unittest

import test_pp1_formal_snapshot as snapshot
from pp1_formal_segregation import evaluate


class RoundTwoObservations(unittest.TestCase):
    def setUp(self):
        self.pipeline = snapshot.FormalSnapshot(methodName='runTest')
        self.pipeline.setUp()
        self.addCleanup(self.pipeline.doCleanups)
        self.pipeline.policy.row['formalModel']['phenotypeModel'] = {
            'kind': 'ATTAINED_AGE_BANDS', 'bands': [
                {'minimumAge': 0, 'maximumAgeExclusive': 18,
                 'probabilities': {'0': '0.25', '1': '0.75', '2': '0.75'}},
                {'minimumAge': 18, 'maximumAgeExclusive': None,
                 'probabilities': {'0': '0.25', '1': '0.9', '2': '0.95'}}]}

    def build(self, *, statements=None, extras=(), claims=()):
        texts = {m: f'{m} was clinically affected with TEST disease at age 40 years.'
                 for m in ('II-1', 'II-2', 'II-3')}
        texts.update(statements or {})
        self.pipeline.build(relatives=[('II-2', 1, True), ('II-3', 1, True)],
                            clinical_texts=texts, extra_paragraphs=extras, clinical_claims=claims)
        result = self.pipeline.evaluate()
        self.assertEqual((0, 0, 1), (result['modelCalls'], result['networkRequests'], result['evaluatorCalls']))
        self.assertFalse(result['clinicalApproval'])
        return result

    def reasons(self, result):
        return {n['reasonCode'] for n in result['evaluation']['needs']}

    def assert_blocked(self, result):
        self.assertEqual(('NOT_EVALUATED', 0), (result['evaluation']['status'], result['evaluation']['awardedPP1Points']))
        self.assertNotIn('bayesFactor', result['evaluation'])
        self.assertEqual([], result['evaluation']['familyAssessments'])

    def test_same_observation_age_selects_adopted_band_and_independent_exact_bf(self):
        result = self.build()
        evaluation = result['evaluation']
        self.assertEqual(('MET', 'SUPPORTING', 1), (evaluation['status'], evaluation['strength'], evaluation['awardedPP1Points']))
        self.assertEqual({'numerator': '1296', 'denominator': '529'}, evaluation['bayesFactor'])
        states = evaluation['clinicalObservationReview']['memberStates']
        self.assertEqual(3, len(states))
        self.assertTrue(all(r['attainedAge'] == 40 and r['status'] == 'AFFECTED' for r in states))
        self.assertTrue(all(r['ageFactIds'] and r['statusFactIds'] and r['assessmentIds'] for r in states))
        self.assertEqual([], evaluation['needs'])

    def test_status_at_ten_and_exam_at_forty_cannot_become_unaffected_at_forty(self):
        result = self.build(statements={'II-2': 'II-2 was clinically unaffected at age 10 years.'},
                            claims=[('F05.4', 'II-2', 'II-2 was examined at age 40 years.')])
        self.assert_blocked(result)
        self.assertIn('PP1_FORMAL_MULTIPLE_CLINICAL_OBSERVATIONS_REQUIRE_SELECTION', self.reasons(result))
        observed = result['evaluation']['clinicalObservationReview']['observations']
        member = next(r['memberKey'] for r in observed if r['memberLabel'] == 'II-2')
        self.assertEqual([], [r for r in result['evaluation']['clinicalObservationReview']['memberStates'] if r['memberKey'] == member])
        self.assertTrue(any(r['status'] == 'UNAFFECTED' and r['attainedAge'] == 10 for r in observed))
        self.assertTrue(any(r['status'] is None and r['attainedAge'] == 40 for r in observed))

    def test_separate_source_assertions_with_explicit_same_visit_are_bound(self):
        result = self.build(statements={'II-2': 'At visit V1, II-2 was clinically affected with TEST disease.'},
                            claims=[('F05.4', 'II-2', 'At visit V1, II-2 was examined at age 40 years.')])
        self.assertEqual('MET', result['evaluation']['status'])
        row = next(r for r in result['evaluation']['clinicalObservationReview']['memberStates'] if r['memberLabel'] == 'II-2')
        self.assertEqual(('AFFECTED', 40, 'EXPLICIT_NAMED_VISIT'), (row['status'], row['attainedAge'], row['bindingBasis']))
        self.assertEqual(2, len(row['claimIds']))

    def test_age_from_other_member_does_not_complete_missing_state_time(self):
        result = self.build(statements={'II-2': 'II-2 was clinically affected with TEST disease.'},
                            claims=[('F05.4', 'II-3', 'II-3 was examined at age 40 years.')])
        self.assert_blocked(result)
        self.assertIn('PP1_FORMAL_STATUS_AGE_OBSERVATION_REQUIRED', self.reasons(result))

    def test_explicit_other_disease_status_is_never_target_disease_state(self):
        result = self.build(statements={'II-2': 'II-2 was clinically unaffected with respect to OTHER disease at age 40 years.'})
        self.assert_blocked(result)
        self.assertIn('PP1_FORMAL_ASSESSMENT_DISEASE_SCOPE_UNRESOLVED', self.reasons(result))

    def test_later_contradictory_visit_cannot_be_silently_discarded(self):
        result = self.build(statements={'II-2': 'At visit V1, II-2 was clinically unaffected at age 10 years.'},
                            claims=[('F05.2', 'II-2', 'At visit V2, II-2 was clinically affected with TEST disease at age 40 years.')])
        self.assert_blocked(result)
        self.assertIn('PP1_FORMAL_MULTIPLE_CLINICAL_OBSERVATIONS_REQUIRE_SELECTION', self.reasons(result))

    def test_later_unparsed_clinical_event_is_review_need_instead_of_hidden_history(self):
        result = self.build(statements={'II-2': 'II-2 was clinically unaffected at age 10 years.'},
                            extras=('At visit V2, II-2 developed TEST disease at age 40 years.',))
        self.assert_blocked(result)
        self.assertIn('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW', self.reasons(result))

    def test_adjacent_disease_qualification_cannot_be_cut_out_of_status_quote(self):
        result = self.build(statements={'II-2': 'II-2 was clinically unaffected at age 40 years.'},
                            extras=('For II-2, this status refers to OTHER disease.',))
        self.assert_blocked(result)
        self.assertIn('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW', self.reasons(result))

    def test_old_typed_rows_and_unbound_age_do_not_reconstruct_qualified_observation(self):
        result = self.build()
        qualified = deepcopy(result['qualifications'][0])
        qualified.pop('clinicalAssessments')
        replay = evaluate([qualified], self.pipeline.policy.selected(), self.pipeline.binding['ledger'])
        self.assertEqual('NOT_EVALUATED', replay['status'])
        self.assertIn('PP1_FORMAL_OBSERVATION_BINDING_REQUIRED', {n['reasonCode'] for n in replay['needs']})

    def test_observation_package_is_target_bound(self):
        result = self.build()
        qualified = deepcopy(result['qualifications'][0])
        qualified['clinicalAssessments']['target']['diseaseId'] = 'OTHER:1'
        replay = evaluate([qualified], self.pipeline.policy.selected(), self.pipeline.binding['ledger'])
        self.assertEqual('NOT_EVALUATED', replay['status'])
        self.assertIn('PP1_FORMAL_ASSESSMENT_TARGET_MISMATCH', {n['reasonCode'] for n in replay['needs']})


if __name__ == '__main__':
    unittest.main()
