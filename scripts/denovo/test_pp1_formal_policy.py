"""Synthetic formal parameter admission and explicit unsupported boundaries."""
from copy import deepcopy
import unittest

from pp1_formal_policy import validate_model


def model():
    return {'schemaVersion': 'pp1-formal-parameter-model-v1', 'version': '1.0.0',
            'genotypeModel': 'AUTOSOMAL_SINGLE_ALLELE_DOSAGE',
            'founderConditioning': 'ALL_FOUNDERS_TYPED',
            'ascertainment': 'PHENOTYPE_BEFORE_GENOTYPING', 'pointBase': '2.08',
            'phenotypeModel': {'kind': 'AGE_INDEPENDENT_BINARY_STATUS',
                               'probabilities': {'0': '0.25', '1': '0.75', '2': '0.75'}}}


def age_model():
    value = model()
    value['phenotypeModel'] = {'kind': 'ATTAINED_AGE_BANDS', 'bands': [
        {'minimumAge': 0, 'maximumAgeExclusive': 18,
         'probabilities': {'0': '0.01', '1': '0.10', '2': '0.20'}},
        {'minimumAge': 18, 'maximumAgeExclusive': None,
         'probabilities': {'0': '0.02', '1': '0.70', '2': '0.90'}}]}
    return value


class ParameterAdmission(unittest.TestCase):
    def test_ad_and_ar_keep_explicit_different_dosage2(self):
        value = model()
        value['phenotypeModel']['probabilities']['2'] = '0.95'
        for inheritance in ('AD', 'AR'):
            self.assertEqual([], validate_model(value, inheritance))
        self.assertEqual('0.95', value['phenotypeModel']['probabilities']['2'])

    def test_conditional_independence_is_explicitly_adopted_not_a_rewritten_default(self):
        value = model()
        value['ascertainment'] = 'CONDITIONAL_SELECTION_AND_REPORTING_INDEPENDENCE'
        self.assertEqual([], validate_model(value, 'AD'))
        self.assertEqual('PHENOTYPE_BEFORE_GENOTYPING', model()['ascertainment'])
        value['ascertainment'] = 'PROBAND_LABEL_ONLY'
        self.assertTrue(validate_model(value, 'AD'))

    def test_unimplemented_inheritance_is_reviewable_not_simple(self):
        for inheritance in ('XLR', 'XLD', None, []):
            needs = validate_model(model(), inheritance)
            self.assertEqual('PP1_FORMAL_INHERITANCE_UNSUPPORTED', needs[0]['reasonCode'])

    def test_missing_model_and_every_required_parameter(self):
        self.assertEqual('PP1_FORMAL_MODEL_REQUIRED', validate_model(None, 'AD')[0]['reasonCode'])
        for field in model():
            value = model()
            del value[field]
            with self.subTest(field=field):
                self.assertTrue(validate_model(value, 'AD'))

    def test_dosage2_is_required_in_both_models(self):
        for value in (model(), age_model()):
            phenotype = value['phenotypeModel']
            probabilities = phenotype.get('probabilities') or phenotype['bands'][0]['probabilities']
            del probabilities['2']
            needs = validate_model(value, 'AD')
            self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_DOSAGE_PROBABILITIES_REQUIRED' for n in needs))
            self.assertNotIn('2', probabilities)

    def test_exact_small_decimal_and_zero_one_boundaries(self):
        value = model()
        for probability in ('0', '1', '0.00001', '1e-200', '1e-500', '1.00000000000000000000000'):
            value['phenotypeModel']['probabilities']['0'] = probability
            with self.subTest(probability=probability):
                self.assertEqual([], validate_model(value, 'AD'))

    def test_invalid_probabilities_are_not_coerced_or_rounded(self):
        for probability in (0.25, True, None, 'NaN', 'Infinity', '-0.1', '1.001', '0,25',
                            '1e-501', '1e999', '0.1234567890123456789012345', ' 0.25', '+0.25'):
            value = model()
            value['phenotypeModel']['probabilities']['1'] = probability
            with self.subTest(probability=probability):
                needs = validate_model(value, 'AD')
                self.assertEqual('PP1_FORMAL_DOSAGE_PROBABILITY_INVALID', needs[0]['reasonCode'])
                self.assertEqual(probability, needs[0]['actual'])

    def test_unknown_parameters_and_unimplemented_models_cannot_be_ignored(self):
        mutations = [lambda m: m.update(deNovoRate='0.01'),
                     lambda m: m.update(pointBase='2.080'),
                     lambda m: m.update(founderConditioning='POPULATION_FREQUENCY'),
                     lambda m: m['phenotypeModel'].update(kind='ONSET_DENSITY'),
                     lambda m: m['phenotypeModel'].update(survival='UNSUPPORTED')]
        for mutate in mutations:
            value = model()
            mutate(value)
            with self.subTest(value=value):
                needs = validate_model(value, 'AD')
                self.assertTrue(needs)
                self.assertTrue(all({'reasonCode', 'requirement', 'expected', 'actual'} <= set(n) for n in needs))

    def test_age_bands_are_complete_disjoint_and_cumulative(self):
        self.assertEqual([], validate_model(age_model(), 'AD'))
        mutations = [lambda b: b[0].update(minimumAge=1),
                     lambda b: b[0].update(minimumAge=True),
                     lambda b: b[1].update(minimumAge=17),
                     lambda b: b[1].update(minimumAge=19),
                     lambda b: b[1].update(maximumAgeExclusive=100),
                     lambda b: b[0].update(maximumAgeExclusive=None),
                     lambda b: b[1]['probabilities'].update({'1': '0.05'}),
                     lambda b: b[0].update(onsetDensity='0.5')]
        for mutate in mutations:
            value = age_model()
            mutate(value['phenotypeModel']['bands'])
            with self.subTest(value=value):
                self.assertTrue(validate_model(value, 'AD'))

    def test_malformed_age_rows_do_not_raise(self):
        for bands in (None, [], [None], ['unknown'], [{'probabilities': None}]):
            value = age_model()
            value['phenotypeModel']['bands'] = deepcopy(bands)
            self.assertTrue(validate_model(value, 'AD'))


if __name__ == '__main__':
    unittest.main()
