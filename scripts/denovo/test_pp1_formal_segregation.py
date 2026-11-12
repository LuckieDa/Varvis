"""Formal method dispatch, source/parameter needs, ledger and calibration.

Synthetic qualified observations exercise the adapter. Source text replay is
tested separately; these fixtures are never claimed as scientific adoption.
"""
from copy import deepcopy
from fractions import Fraction
from decimal import Decimal
import unittest

from model_literature_contract import canonical, digest
from pp1_segregation import evaluate
from pp1_formal_policy import FORMAL_METHOD
from test_pp1_segregation import fixture, add, context, ledger
from test_pp1_formal_policy import model
from test_pp1_formal_likelihood import nuclear, first_cousins
from pp1_formal_segregation import calibrate


def qualified(data, inheritance='AD', family='F1'):
    q = fixture(inheritance, family)
    add(q, 'FAMILY', 'candidateScope', 'SINGLE_REPORTED_CANDIDATE', family)
    ascertainment = add(q, 'FAMILY', 'formalAscertainment', 'PHENOTYPE_BEFORE_GENOTYPING', family)
    ascertainment['claimIds'] = ['SYNTHETIC_MECHANISM']
    ascertainment['sourceRefs'] = [{**ref, **q['sourceIdentity']} for ref in ascertainment['sourceRefs']]
    q['families'][family]['sourceFamilyLabel'] = family
    q['ascertainmentReview'] = {'schemaVersion': 'pp1-formal-ascertainment-review-v1',
        'dataClass': 'SYNTHETIC_TEST', 'target': deepcopy(q['target']), 'sourceIdentity': deepcopy(q['sourceIdentity']),
        'families': {family: {'familyKey': family, 'sourceFamilyLabel': family,
            'status': 'QUALIFIED_SUFFICIENT_CONDITION', 'qualifiedSubjectLabels': ['FAMILY'],
            'sufficientConditions': [{'subjectLabel': 'FAMILY', 'sourceBasis': ascertainment['value'],
                                     'claimIds': ascertainment['claimIds'], 'sourceRefs': ascertainment['sourceRefs']}],
            'records': [], 'unresolved': [], 'conflicts': []}}}
    for member, parents in data['members'].items():
        q['families'][family]['members'][member] = member
        for parent in parents:
            add(q, parent, 'parentOf', member, family)
    names = {0: 'NONCARRIER', 1: 'HETEROZYGOUS', 2: 'HOMOZYGOUS'}
    for member, dosage in data['observed'].items():
        add(q, member, 'genotype', names[dosage], family)
    for member, phenotype in data['phenotypes'].items():
        add(q, member, 'diagnosis' if phenotype == 'AFFECTED' else 'clinical',
            'TEST disease' if phenotype == 'AFFECTED' else 'UNAFFECTED', family)
    add(q, data['proband'], 'proband', True, family)
    assessments = []
    for member, status in data['phenotypes'].items():
        identity = {'kind': 'SYNTHETIC_ADAPTER_FIXTURE', **q['sourceIdentity'],
                    'familyKey': family, 'memberKey': member, 'unitId': 'SYNTHETIC/' + member}
        refs = [{**q['sourceIdentity'], 'unitId': 'SYNTHETIC/' + member, 'quote': status}]
        assessments.append({'id': digest(canonical(['SYNTHETIC', identity, status])), 'sourceObservationId': digest(canonical(identity)),
            'observationIdentity': identity, 'familyKey': family, 'memberKey': member, 'memberLabel': member,
            'status': status, 'attainedAge': None, 'diseaseTerm': 'TEST disease' if status == 'AFFECTED' else None,
            'statusFactIds': ['SYNTHETIC/' + member], 'ageFactIds': [], 'claimIds': ['SYNTHETIC/' + member],
            'sourceRefs': refs, 'bindingBasis': 'SYNTHETIC_ADAPTER_FIXTURE', 'issues': []})
    q['clinicalAssessments'] = {'schemaVersion': 'pp1-formal-clinical-observations-v1',
        'dataClass': 'SYNTHETIC_TEST', 'sourceIdentity': deepcopy(q['sourceIdentity']), 'target': deepcopy(q['target']),
        'observations': assessments, 'needs': []}
    return q


def policy(data, inheritance='AD'):
    c = context(inheritance)
    c['policy'].update(method=FORMAL_METHOD, formalModel=model(), penetrance='EXPLICIT_FORMAL_MODEL', phenocopy='EXPLICIT_FORMAL_MODEL')
    c['policy']['formalModel']['phenotypeModel']['probabilities'] = dict(zip(('0', '1', '2'), next(iter(data['penetrances'].values()))))
    return c


class FormalAdapter(unittest.TestCase):
    def test_calibration_exact_thresholds_and_fractional_capacity(self):
        base, tiny = Fraction(52, 25), Fraction(1, 10**40)
        for power, expected in ((1, 1), (2, 2), (4, 4)):
            exact = calibrate(base ** power, Decimal(5))
            self.assertEqual(expected, exact['awardedPP1Points'])
            self.assertEqual(Decimal(power), Decimal(exact['allocatedPP1Points']))
            self.assertEqual(expected, calibrate(base ** power + tiny, Decimal(5))['awardedPP1Points'])
            self.assertLess(calibrate(base ** power - tiny, Decimal(5))['awardedPP1Points'], expected)
        self.assertEqual(1, calibrate(Fraction(100), Decimal('1.999999999999'))['awardedPP1Points'])
        self.assertEqual(0, calibrate(Fraction(100), Decimal('0.999999999999'))['awardedPP1Points'])
        self.assertEqual('NEGATIVE_INFINITY', calibrate(Fraction(0), Decimal(5))['log10BayesFactorStatus'])
        self.assertLess(Decimal(calibrate(Fraction(1, 10), Decimal(5))['rawCalibratedPoints']), 0)

    def run_family(self, data, inheritance='AD', mutate=None, pp4=0):
        q, c, log = qualified(data, inheritance), policy(data, inheritance), ledger(inheritance, pp4)
        if mutate:
            mutate(q, c, log)
        before = deepcopy((q, c, log))
        result = evaluate([q], c, log)
        self.assertEqual(before, (q, c, log))
        self.assertEqual('pp1-segregation-evaluation-v2', result['schemaVersion'])
        return result

    def test_method_does_not_masquerade_as_old_events_or_simple_points(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 0, 'UNAFFECTED')])
        result = self.run_family(data)
        self.assertEqual(('MET', 'SUPPORTING', 1), (result['status'], result['strength'], result['awardedPP1Points']))
        self.assertEqual({'numerator': '9', 'denominator': '4'}, result['bayesFactor'])
        self.assertEqual([], result['events'])
        self.assertNotIn('rawSegregationPoints', result)

    def test_formal_two_is_below_exact_calibrated_supporting_boundary(self):
        data = nuclear(relatives=[('S', 1, 'AFFECTED')])
        data['penetrances'] = {m: ('0', '1', '1') for m in data['phenotypes']}
        result = self.run_family(data)
        self.assertEqual(('NOT_MET', 0), (result['status'], result['awardedPP1Points']))
        self.assertEqual({'numerator': '2', 'denominator': '1'}, result['bayesFactor'])

    def test_first_cousin_loop_is_joint_and_uses_explicit_dosage_two_model(self):
        result = self.run_family(first_cousins(), 'AR')
        self.assertEqual(('MET', 'MODERATE', 2), (result['status'], result['strength'], result['awardedPP1Points']))
        self.assertEqual({'numerator': '48', 'denominator': '7'}, result['bayesFactor'])

    def test_signed_opposition_and_zero_are_not_auto_bs4_or_missing_parameters(self):
        for vector in [('0.25', '0.75', '0.75'), ('0', '1', '1')]:
            data = nuclear(relatives=[('S', 0, 'AFFECTED')])
            data['penetrances'] = {m: vector for m in data['phenotypes']}
            result = self.run_family(data)
            self.assertEqual('NOT_MET', result['status'])
            self.assertEqual('OPPOSES', result['direction'])
            self.assertFalse(result['calibration']['negativeEvidenceAutomaticallyAssignedToBS4'])
            self.assertEqual([], result['needs'])

    def test_pp4_remaining_capacity_is_applied_after_formal_result(self):
        data = first_cousins(complete=True)
        for pp4, awarded in [(0, 4), (2, 2), (4, 1), (5, 0)]:
            with self.subTest(pp4=pp4):
                result = self.run_family(data, 'AR', pp4=pp4)
                self.assertEqual(awarded, result['awardedPP1Points'])
                self.assertEqual({'numerator': '64', 'denominator': '1'}, result['bayesFactor'])

    def test_same_family_reports_and_observations_never_add_bf(self):
        data = first_cousins(); q, c = qualified(data, 'AR'), policy(data, 'AR')
        repeated = deepcopy(q); repeated['observations'] += deepcopy(q['observations'])
        result = evaluate([q, repeated], c, ledger('AR'))
        self.assertEqual(1, len(result['familyAssessments']))
        self.assertEqual({'numerator': '48', 'denominator': '7'}, result['bayesFactor'])

    def test_family_identity_and_exclusive_facts_prevent_method_switch_recount(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        q, c, log = qualified(data), policy(data), ledger()
        initial = evaluate([q], c, log)
        event = initial['familyAssessments'][0]
        for field, ids in [('eventIds', [event['familyContributionId']]),
                           ('exclusiveFactIds', [event['factIds'][0]])]:
            used = deepcopy(log); used[field] = ids
            result = evaluate([q], c, used)
            self.assertEqual(('NOT_MET', 0), (result['status'], result['awardedPP1Points']))
        legacy = digest(canonical([q['target']['allele'], log['scope'], 'F1', 'AD_AFFECTED_TRANSMISSION', 'S1', ['F']]))
        log['eventIds'] = [legacy]
        self.assertEqual(0, evaluate([q], c, log)['awardedPP1Points'])

    def test_unrelated_families_never_multiply_and_opposition_is_not_hidden(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        q, c = qualified(data), policy(data)
        other = qualified(data, family='F2')
        result = evaluate([q, other], c, ledger())
        self.assertEqual({'numerator': '9', 'denominator': '4'}, result['bayesFactor'])
        opposing = qualified(nuclear(relatives=[('S', 1, 'UNAFFECTED')]), family='F3')
        result = evaluate([q, opposing], c, ledger())
        self.assertEqual('NOT_EVALUATED', result['status'])
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_FAMILIES_DISAGREE' for n in result['needs']))

    def test_source_requirements_are_explicit_and_do_not_fall_back_to_simple(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        for kind, reason in [('formalAscertainment', 'PP1_FORMAL_ASCERTAINMENT_SOURCE_REQUIRED'),
                             ('proband', 'PP1_FORMAL_PROBAND_REQUIRED'),
                             ('candidateScope', 'PP1_FORMAL_CANDIDATE_ALLOCATION_REQUIRED'),
                             ('candidateMethod', 'PP1_CANDIDATE_ASSESSMENT_SCOPE_UNQUALIFIED')]:
            result = self.run_family(data, mutate=lambda q,c,l: q.update(observations=[r for r in q['observations'] if r['kind'] != kind]))
            self.assertEqual('NOT_EVALUATED', result['status'])
            self.assertTrue(any(n['reasonCode'] == reason for n in result['needs']), result)
            self.assertEqual('SOURCE_REVIEW' if kind == 'formalAscertainment' else 'LITERATURE',
                             next(n for n in result['needs'] if n['reasonCode'] == reason)['route'])

    def test_malformed_context_ledger_and_founder_needs_preserve_known_facts(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        result = self.run_family(data, mutate=lambda q,c,l: c['policy']['formalModel']['phenotypeModel']['probabilities'].pop('2'))
        self.assertEqual('NOT_EVALUATED', result['status']); self.assertTrue(result['needs'][0]['expected'])
        def missing(q, c, log):
            q['observations'] = [r for r in q['observations'] if not (r['memberKey'] == 'M' and r['kind'] == 'genotype')]
        result = self.run_family(data, mutate=missing)
        issue = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_FOUNDER_GENOTYPE_REQUIRED')
        self.assertEqual(['M'], issue['actual']['untypedFounderKeys']); self.assertTrue(issue['sourceLocators'])
        result = self.run_family(data, mutate=lambda q,c,l: l.update(status='UNKNOWN'))
        self.assertEqual('PP1_PP4_LEDGER_UNKNOWN', result['needs'][0]['reasonCode'])

    def test_unknown_phase_conflicts_and_unresolved_adverse_source_block(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        for mutation in (lambda q,c,l: add(q, 'S1', 'biallelic', {'phase': 'UNKNOWN'}),
                         lambda q,c,l: add(q, 'S1', 'genotype', 'NONCARRIER'),
                         lambda q,c,l: add(q, 'FAMILY', 'caution', 'Possible conflicting evidence.'),
                         lambda q,c,l: q['unresolved'].append({'familyKey':'F1','fieldId':'F09.1','sourceValue':'Unresolved model caution','sourceRefs':[]})):
            result = self.run_family(data, mutate=mutation)
            self.assertEqual('NOT_EVALUATED', result['status']); self.assertTrue(result['needs'])

    def test_age_bands_require_typed_examination_not_onset_or_generic_age(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        q, c = qualified(data), policy(data)
        probabilities = c['policy']['formalModel']['phenotypeModel']['probabilities']
        c['policy']['formalModel']['phenotypeModel'] = {'kind':'ATTAINED_AGE_BANDS','bands':[
            {'minimumAge':0,'maximumAgeExclusive':None,'probabilities':probabilities}]}
        for member in data['phenotypes']:
            add(q, member, 'age', 40)
        result = evaluate([q], c, ledger())
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_STATUS_AGE_OBSERVATION_REQUIRED' for n in result['needs']))
        for member in data['phenotypes']:
            add(q, member, 'examinationAge', 40)
        self.assertEqual('NOT_EVALUATED', evaluate([q], c, ledger())['status'])
        for observation in q['clinicalAssessments']['observations']:
            observation['attainedAge'] = 40
            observation['ageFactIds'] = ['SYNTHETIC_SAME_OBSERVATION_AGE']
        self.assertEqual('MET', evaluate([q], c, ledger())['status'])

    def test_formal_to_simple_method_cannot_reallocate_same_family(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        data['phenotypes']['F'] = 'AFFECTED'
        q, c, log = qualified(data), policy(data), ledger()
        first = evaluate([q], c, log)
        self.assertEqual('MET', first['status'])
        log['eventIds'] = [first['selectedFamilyContributionId']]
        legacy = evaluate([q], context(), log)
        self.assertEqual(0, legacy['awardedPP1Points'])

    def test_allocated_adverse_family_is_still_a_counterexample(self):
        positive = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        q, c, log = qualified(positive), policy(positive), ledger()
        adverse = qualified(nuclear(relatives=[('S', 1, 'UNAFFECTED')]), family='F2')
        prior = evaluate([adverse], c, log)
        log['eventIds'] = [prior['selectedFamilyContributionId']]
        result = evaluate([q, adverse], c, log)
        self.assertEqual('NOT_EVALUATED', result['status'])
        self.assertIn('PP1_FORMAL_FAMILIES_DISAGREE', [n['reasonCode'] for n in result['needs']])

    def test_source_local_family_key_does_not_establish_prior_independence(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        q, c, log = qualified(data), policy(data), ledger()
        previous = evaluate([q], c, log)
        log['eventIds'] = [previous['selectedFamilyContributionId']]
        relabelled = qualified(data, family='ANOTHER_PAPER_FAMILY')
        result = evaluate([relabelled], c, log)
        self.assertEqual('NOT_EVALUATED', result['status'])
        self.assertEqual('PP1_FORMAL_PRIOR_FAMILY_IDENTITY_UNRESOLVED', result['needs'][0]['reasonCode'])
        log['eventIds'] = []
        log['exclusiveFactIds'] = previous['familyAssessments'][0]['factIds']
        result = evaluate([relabelled], c, log)
        self.assertEqual('NOT_EVALUATED', result['status'])
        self.assertEqual('PP1_FORMAL_PRIOR_FAMILY_IDENTITY_UNRESOLVED', result['needs'][0]['reasonCode'])

    def test_simple_method_with_relabelled_family_cannot_bypass_opaque_prior_ledger(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        q, c, log = qualified(data), policy(data), ledger()
        prior = evaluate([q], c, log)
        relabelled = qualified(data, family='OTHER_PAPER')
        add(relabelled, 'F', 'diagnosis', 'TEST disease', 'OTHER_PAPER')
        for field, values in [('eventIds', [prior['selectedFamilyContributionId']]),
                              ('exclusiveFactIds', prior['familyAssessments'][0]['factIds'])]:
            current = deepcopy(log); current[field] = values
            result = evaluate([relabelled], context(), current)
            self.assertEqual('NOT_EVALUATED', result['status'])
            self.assertEqual(0, result['awardedPP1Points'])
            self.assertIn('PP1_PRIOR_FAMILY_IDENTITY_UNRESOLVED', [n['reasonCode'] for n in result['needs']])

    def test_pending_candidate_partner_or_diagnostic_evidence_is_not_silently_omitted(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        cases = [lambda q,c,l: q['unresolved'].append({'familyKey':'F1','fieldId':'F02.5','sourceValue':'Other candidates','sourceRefs':[]}),
                 lambda q,c,l: q['unresolved'].append({'reasonCode':'PP1_PARTNER_IDENTITY_UNRESOLVED','sourceRefs':[]}),
                 lambda q,c,l: add(q, 'M', 'reportedDiagnosticCriteria', 'Reported diagnostic fulfillment'),
                 lambda q,c,l: add(q, 'S1', 'fullSiblingOf', 'F')]
        for mutate in cases:
            result = self.run_family(data, mutate=mutate)
            self.assertEqual('NOT_EVALUATED', result['status']); self.assertTrue(result['needs'])

    def test_pending_addition_can_use_existing_reading_review_route(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        def pending(q, c, log):
            q['unresolved'].append({'familyKey':'F1','memberKey':'S1','memberLabel':'S1',
                'fieldId':'F02.5','claimId':'pending-candidate','reviewStatus':'PENDING_ADDITION_REVIEW',
                'sourceValue':'Other candidates','sourceRefs':[]})
        result = self.run_family(data, mutate=pending)
        need = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW')
        self.assertEqual('SOURCE_REVIEW', need['route'])
        self.assertEqual('pending-candidate', need['scope']['claimId'])

    def test_individual_ascertainment_cannot_guarantee_family_enrollment(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        def individual(q, c, log):
            q['observations'] = [r for r in q['observations'] if r['kind'] != 'formalAscertainment']
            add(q, 'P', 'formalAscertainment', 'PHENOTYPE_BEFORE_GENOTYPING')
        result = self.run_family(data, mutate=individual)
        self.assertIn('PP1_FORMAL_ASCERTAINMENT_SOURCE_REQUIRED', [n['reasonCode'] for n in result['needs']])

    def test_autosomal_method_never_infers_x_diploidy(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        q, c, log = qualified(data), policy(data), ledger()
        q['target']['allele']['contig'] = log['allele']['contig'] = 'X'
        result = evaluate([q], c, log)
        self.assertEqual('PP1_FORMAL_AUTOSOMAL_ALLELE_REQUIRED', result['needs'][0]['reasonCode'])

    def test_actual_full_sibling_statement_matches_both_parent_sets(self):
        data = nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 1, 'AFFECTED')])
        result = self.run_family(data, mutate=lambda q,c,l: add(q, 'S1', 'fullSiblingOf', 'S2'))
        self.assertEqual('MET', result['status'])

    def test_large_exact_four_sums_serialize_without_disabling_interpreter_limits(self):
        from pp1_formal_likelihood import likelihood
        data = nuclear(relatives=[('S' + str(i), 1, 'AFFECTED') for i in range(9)])
        data['penetrances'] = {m: ('1e-500', '1e-500', '1e-500') for m in data['phenotypes']}
        result = likelihood(**data)
        self.assertEqual('COMPUTED', result['status'])
        self.assertEqual({'numerator':'1','denominator':'1'}, result['bayesFactor'])
        self.assertGreater(len(result['fourSums']['phenotypeAll']['denominator']), 4300)


if __name__ == '__main__':
    unittest.main()
