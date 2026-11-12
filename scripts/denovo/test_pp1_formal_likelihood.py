"""Independent hand-calculated expectations for the conditional pedigree core.

All families and penetrance probabilities are synthetic. Expected fractions
were fixed before the calculator existed; this file contains no likelihood
solver and does not derive expected answers from the implementation.
"""
from copy import deepcopy
from fractions import Fraction
import json
from pathlib import Path
import unittest

from pp1_formal_likelihood import likelihood


EXPECTED = json.loads((Path(__file__).resolve().parents[2] / 'varvis-server' /
    'src/test/resources/acmg/pp1-formal-expectations-v1.json').read_text(encoding='utf-8'))
AD = ('0.25', '0.75', '0.75')
AR = ('0.25', '0.25', '0.75')


def nuclear(inheritance='AD', relatives=()):
    """Typed founders, an affected typed proband, and named full siblings."""
    recessive = inheritance == 'AR'
    members = {'F': (), 'M': (), 'P': ('F', 'M')}
    observed = {'F': 1, 'M': 1 if recessive else 0, 'P': 2 if recessive else 1}
    phenotypes = {'P': 'AFFECTED'}
    for member, dosage, phenotype in relatives:
        members[member] = ('F', 'M')
        observed[member] = dosage
        phenotypes[member] = phenotype
    vector = AR if recessive else AD
    return {'members': members, 'observed': observed, 'phenotypes': phenotypes,
        'penetrances': {member: vector for member in phenotypes}, 'proband': 'P'}


def grandchildren(count=2):
    """An affected founder proband, an untyped child, and typed grandchildren."""
    members = {'F': (), 'M': (), 'U': (), 'B': ('F', 'M')}
    observed = {'F': 1, 'M': 0, 'U': 0}
    phenotypes = {'F': 'AFFECTED'}
    for number in range(count):
        member = 'C' + str(number + 1)
        members[member] = ('B', 'U')
        observed[member] = 1
        phenotypes[member] = 'AFFECTED'
    return {'members': members, 'observed': observed, 'phenotypes': phenotypes,
        'penetrances': {member: AD for member in phenotypes}, 'proband': 'F'}


def first_cousins(count=2, complete=False):
    """Two first cousins have children; their uncle is the typed proband.

    F1/F2 are both heterozygous. Their children are B1/B2/P; B1 and B2 each
    have a child with a typed noncarrier. Those children C1/C2 have Z children.
    B1/B2/C1/C2 are all untyped. All unspecified phenotypes remain unknown.
    """
    members = {'F1': (), 'F2': (), 'U1': (), 'U2': (),
        'B1': ('F1', 'F2'), 'B2': ('F1', 'F2'), 'P': ('F1', 'F2'),
        'C1': ('B1', 'U1'), 'C2': ('B2', 'U2')}
    observed = {'F1': 1, 'F2': 1, 'U1': 0, 'U2': 0, 'P': 2}
    phenotypes = {'P': 'AFFECTED'}
    for number in range(count):
        member = 'Z' + str(number + 1)
        members[member] = ('C1', 'C2')
        observed[member] = 2
        phenotypes[member] = 'AFFECTED'
    vector = ('0', '0', '1') if complete else AR
    return {'members': members, 'observed': observed, 'phenotypes': phenotypes,
        'penetrances': {member: vector for member in phenotypes}, 'proband': 'P'}


class FormalLikelihood(unittest.TestCase):
    def assert_fraction(self, actual, expected):
        fraction = Fraction(expected)
        self.assertEqual({'numerator': str(fraction.numerator),
            'denominator': str(fraction.denominator)}, actual)

    def computed(self, family, expected, sums=None, **limits):
        before = deepcopy(family)
        result = likelihood(**family, **limits)
        self.assertEqual(before, family, 'the core must not mutate qualified inputs')
        self.assertEqual('pp1-conditional-likelihood-v1', result['schemaVersion'])
        self.assertEqual('COMPUTED', result['status'], result)
        self.assert_fraction(result['bayesFactor'], expected)
        self.assertIs(type(result['operationCount']), int)
        self.assertGreaterEqual(result['operationCount'], 0)
        self.assertLessEqual(result['operationCount'], limits.get('max_operations', 1000000))
        self.assertEqual({'genotypeAnchor', 'genotypeAll', 'phenotypeAnchor',
            'phenotypeAll'}, set(result['fourSums']))
        for part in result['fourSums'].values():
            value = Fraction(int(part['numerator']), int(part['denominator']))
            self.assert_fraction(part, value)
            self.assertGreaterEqual(value, 0)
            self.assertLessEqual(value, 1)
        if sums is not None:
            for key, value in sums.items():
                self.assert_fraction(result['fourSums'][key], value)
        return result

    def vector(self, family, key, sums=None):
        return self.computed(family, EXPECTED['vectors'][key], sums)

    def unresolved(self, family, reason, **limits):
        before = deepcopy(family)
        result = likelihood(**family, **limits)
        self.assertEqual(before, family)
        self.assertEqual('pp1-conditional-likelihood-v1', result['schemaVersion'])
        self.assertEqual('UNRESOLVED', result['status'], result)
        self.assertEqual(reason, result['reasonCode'], result)
        self.assertFalse(result.get('bayesFactor'), 'an unresolved result cannot invent a BF')
        self.assertIs(type(result['operationCount']), int)
        self.assertGreaterEqual(result['operationCount'], 0)
        return result

    def test_frozen_expectations_are_synthetic_and_preimplementation(self):
        self.assertEqual('EXPECTED_BEFORE_IMPLEMENTATION', EXPECTED['status'])
        self.assertEqual('SYNTHETIC_TEST', EXPECTED['dataClass'])

    def test_ad_proband_anchor_alone_has_no_segregation_information(self):
        self.vector(nuclear(), 'ad_anchor_only', {'genotypeAnchor': '1/2',
            'genotypeAll': '1/2', 'phenotypeAnchor': '3/8', 'phenotypeAll': '3/8'})

    def test_ad_affected_heterozygote(self):
        self.vector(nuclear(relatives=[('S', 1, 'AFFECTED')]), 'ad_affected_het',
            {'genotypeAnchor': '1/2', 'genotypeAll': '1/4',
             'phenotypeAnchor': '3/16', 'phenotypeAll': '9/64'})

    def test_ad_unaffected_heterozygote_is_finite_reverse_evidence(self):
        self.vector(nuclear(relatives=[('S', 1, 'UNAFFECTED')]), 'ad_unaffected_het',
            {'genotypeAnchor': '1/2', 'genotypeAll': '1/4',
             'phenotypeAnchor': '3/16', 'phenotypeAll': '3/64'})

    def test_ad_affected_noncarrier_is_allowed_by_explicit_phenocopy(self):
        self.vector(nuclear(relatives=[('S', 0, 'AFFECTED')]), 'ad_affected_noncarrier')

    def test_ad_affected_and_unaffected_relatives(self):
        self.vector(nuclear(relatives=[('S1', 1, 'AFFECTED'), ('S2', 0, 'UNAFFECTED')]),
            'ad_affected_and_unaffected_noncarrier', {'genotypeAnchor': '1/2',
             'genotypeAll': '1/8', 'phenotypeAnchor': '3/32', 'phenotypeAll': '27/512'})

    def test_ad_homozygous_penetrance_is_explicit_and_can_differ(self):
        family = nuclear(relatives=[('S', 2, 'AFFECTED')])
        family['observed']['M'] = 1
        family['penetrances'] = {member: ('0.25', '0.75', '0.5')
            for member in family['phenotypes']}
        self.computed(family, '8/9', {'genotypeAnchor': '1/2', 'genotypeAll': '1/8',
            'phenotypeAnchor': '27/128', 'phenotypeAll': '3/64'})

    def test_ar_affected_homozygous_sibling(self):
        self.vector(nuclear('AR', [('S', 2, 'AFFECTED')]), 'ar_affected_homozygous',
            {'genotypeAnchor': '1/4', 'genotypeAll': '1/16',
             'phenotypeAnchor': '9/128', 'phenotypeAll': '9/256'})

    def test_ar_unaffected_heterozygous_sibling(self):
        self.vector(nuclear('AR', [('S', 1, 'UNAFFECTED')]), 'ar_unaffected_het',
            {'genotypeAnchor': '1/4', 'genotypeAll': '1/8',
             'phenotypeAnchor': '15/128', 'phenotypeAll': '9/128'})

    def test_ar_mixed_siblings(self):
        self.vector(nuclear('AR', [('S1', 2, 'AFFECTED'), ('S2', 1, 'UNAFFECTED')]),
            'ar_affected_and_unaffected_het', {'genotypeAnchor': '1/4',
             'genotypeAll': '1/32', 'phenotypeAnchor': '45/1024', 'phenotypeAll': '27/2048'})

    def test_ar_reverse_observations_are_not_ignored(self):
        for dosage, phenotype, expected in [(2, 'UNAFFECTED', '2/5'),
                (1, 'AFFECTED', '2/3')]:
            with self.subTest(dosage=dosage, phenotype=phenotype):
                self.computed(nuclear('AR', [('S', dosage, phenotype)]), expected)

    def test_complete_penetrance_ar_exact_unaffected_factor(self):
        for dosage, phenotype, expected in [(2, 'AFFECTED', '4'),
                (1, 'UNAFFECTED', '4/3')]:
            with self.subTest(dosage=dosage):
                family = nuclear('AR', [('S', dosage, phenotype)])
                family['penetrances'] = {member: ('0', '0', '1')
                    for member in family['phenotypes']}
                self.computed(family, expected)

    def test_unknown_intermediate_is_marginalized_jointly(self):
        self.computed(grandchildren(1), '2')
        self.vector(grandchildren(2), 'ad_unknown_intermediate_two_grandchildren',
            {'genotypeAnchor': '1', 'genotypeAll': '1/8',
             'phenotypeAnchor': '15/128', 'phenotypeAll': '27/512'})
        self.assertNotEqual(Fraction('18/5'), Fraction('2') ** 2)

    def test_legal_first_cousin_marriage_loop(self):
        self.computed(first_cousins(1), '8/3')
        self.vector(first_cousins(), 'ar_first_cousins_two_affected',
            EXPECTED['firstCousinsFourSums'])
        self.assertNotEqual(Fraction('48/7'), Fraction('8/3') ** 2)

    def test_legal_first_cousin_loop_complete_penetrance(self):
        self.vector(first_cousins(complete=True), 'ar_first_cousins_complete_penetrance',
            {'genotypeAnchor': '1/4', 'genotypeAll': '1/256',
             'phenotypeAnchor': '1/256', 'phenotypeAll': '1/256'})

    def test_genotype_independent_phenotypes_give_unit_bf(self):
        family = first_cousins()
        family['penetrances'] = {member: ('0.5', '0.5', '0.5')
            for member in family['phenotypes']}
        self.vector(family, 'genotype_independent_phenotype', {'genotypeAnchor': '1/4',
            'genotypeAll': '1/256', 'phenotypeAnchor': '1/32', 'phenotypeAll': '1/2048'})

    def test_unreported_phenotypes_are_not_unaffected(self):
        family = first_cousins()
        family['phenotypes'] = {}
        family['penetrances'] = {}
        self.computed(family, '1', {'genotypeAnchor': '1/4', 'genotypeAll': '1/256',
            'phenotypeAnchor': '1/4', 'phenotypeAll': '1/256'})

    def test_tiny_probabilities_do_not_underflow(self):
        family = nuclear(relatives=[('S', 1, 'AFFECTED')])
        family['penetrances'] = {member: ('1e-200', '3e-200', '3e-200')
            for member in family['phenotypes']}
        self.vector(family, 'ad_tiny_probabilities', {'genotypeAnchor': '1/2',
            'genotypeAll': '1/4', 'phenotypeAnchor': Fraction(3, 10 ** 400),
            'phenotypeAll': Fraction(9, 4 * 10 ** 400)})

    def test_probability_complements_remain_exact_near_one(self):
        family = nuclear(relatives=[('S', 1, 'UNAFFECTED')])
        # The API takes decimal strings. The unaffected emissions are exactly
        # 1e-24, 3e-24, 3e-24, below binary float precision near one.
        p0 = '0.' + '9' * 24
        p1 = '0.' + '9' * 23 + '7'
        family['penetrances'] = {member: (p0, p1, p1)
            for member in family['phenotypes']}
        self.computed(family, '3/2')

    def test_zero_bf_is_computed_reverse_evidence(self):
        family = nuclear(relatives=[('S', 0, 'AFFECTED')])
        family['penetrances'] = {member: ('0', '1', '1')
            for member in family['phenotypes']}
        self.computed(family, '0', {'genotypeAnchor': '1/2', 'genotypeAll': '1/4',
            'phenotypeAnchor': '1/4', 'phenotypeAll': '0'})

    def test_mendelian_impossibility_is_not_a_zero_bf(self):
        self.unresolved(nuclear(relatives=[('S', 2, 'AFFECTED')]),
            'PP1_FORMAL_MENDELIAN_CONFLICT')

    def test_impossible_anchor_phenotype_is_model_conflict(self):
        family = nuclear()
        family['penetrances']['P'] = ('1', '0', '0')
        self.unresolved(family, 'PP1_FORMAL_ASCERTAINMENT_MODEL_CONFLICT')

    def test_missing_founder_genotype_does_not_get_an_implicit_prior(self):
        family = nuclear()
        del family['observed']['M']
        self.unresolved(family, 'PP1_FORMAL_FOUNDER_GENOTYPE_REQUIRED')

    def test_proband_must_be_explicit_and_typed(self):
        for proband in (None, ''):
            with self.subTest(proband=proband):
                family = nuclear()
                family['proband'] = proband
                self.unresolved(family, 'PP1_FORMAL_PROBAND_REQUIRED')
        family = nuclear()
        del family['observed']['P']
        self.unresolved(family, 'PP1_FORMAL_PROBAND_REQUIRED')

    def test_invalid_parentage_is_rejected_before_calculation(self):
        for parents in [('F',), ('F', 'M', 'X'), ('S', 'M'), ('F', 'F'), ('F', 'missing')]:
            with self.subTest(parents=parents):
                family = nuclear(relatives=[('S', 1, 'AFFECTED')])
                family['members']['S'] = parents
                self.unresolved(family, 'PP1_FORMAL_PEDIGREE_INVALID')

    def test_biological_parent_child_cycle_is_rejected(self):
        family = nuclear(relatives=[('S', 1, 'AFFECTED')])
        family['members']['F'] = ('S', 'M')
        self.unresolved(family, 'PP1_FORMAL_PEDIGREE_INVALID')

    def test_observation_outside_roster_is_not_a_new_member(self):
        family = nuclear()
        family['observed']['not-in-roster'] = 1
        self.unresolved(family, 'PP1_FORMAL_PEDIGREE_INVALID')

    def test_member_limit_is_structured_and_does_not_truncate_the_family(self):
        family = {'members': {str(i): () for i in range(32)},
            'observed': {str(i): 0 for i in range(32)},
            'phenotypes': {'0': 'AFFECTED'},
            'penetrances': {'0': ('0.5', '0.5', '0.5')}, 'proband': '0'}
        self.computed(family, '1')
        family['members']['33'] = ()
        family['observed']['33'] = 0
        self.unresolved(family, 'PP1_FORMAL_PEDIGREE_INVALID')

    def test_penetrance_model_requires_all_three_explicit_valid_probabilities(self):
        for vector in [None, ('0.25', '0.75'), ('0.25', '0.75', '0.75', '0.75'),
                ('-0.1', '0.75', '0.75'), ('0.25', '1.1', '0.75'),
                ('NaN', '0.75', '0.75'), ('0.25', 'Infinity', '0.75')]:
            with self.subTest(vector=vector):
                family = nuclear()
                if vector is None:
                    family['penetrances'] = {}
                else:
                    family['penetrances']['P'] = vector
                self.unresolved(family, 'PP1_FORMAL_PHENOTYPE_MODEL_INVALID')

    def test_unknown_phenotype_label_is_not_unaffected(self):
        family = nuclear()
        family['phenotypes']['P'] = 'UNKNOWN'
        self.unresolved(family, 'PP1_FORMAL_PHENOTYPE_MODEL_INVALID')

    def test_dosage_api_cannot_invent_phase_or_compound_genotypes(self):
        for genotype in [True, -1, 3, 'HETEROZYGOUS', (1, 1),
                {'alleles': ['TARGET', 'PARTNER'], 'phase': 'UNKNOWN'}]:
            with self.subTest(genotype=genotype):
                family = nuclear(relatives=[('S', 1, 'AFFECTED')])
                family['observed']['S'] = genotype
                self.unresolved(family, 'PP1_FORMAL_GENOTYPE_INVALID')

    def test_input_order_and_parent_order_do_not_change_statistics(self):
        family = first_cousins()
        baseline = self.vector(family, 'ar_first_cousins_two_affected')
        reordered = deepcopy(family)
        for key in ('members', 'observed', 'phenotypes', 'penetrances'):
            reordered[key] = dict(reversed(list(reordered[key].items())))
        reordered['members'] = {member: tuple(reversed(parents))
            for member, parents in reordered['members'].items()}
        actual = self.vector(reordered, 'ar_first_cousins_two_affected')
        self.assertEqual(baseline['bayesFactor'], actual['bayesFactor'])
        self.assertEqual(baseline['fourSums'], actual['fourSums'])

    def test_repeated_identical_dictionary_entries_do_not_multiply_people(self):
        family = nuclear(relatives=[('S', 1, 'AFFECTED')])
        for key in ('members', 'observed', 'phenotypes', 'penetrances'):
            family[key] = dict(list(family[key].items()) * 2)
        self.vector(family, 'ad_affected_het')

    def test_operation_budget_does_not_return_partial_statistics(self):
        result = self.unresolved(first_cousins(), 'PP1_FORMAL_COMPUTATION_LIMIT',
            max_operations=1)
        self.assertFalse(result.get('fourSums'))

    def test_factor_size_budget_does_not_return_partial_statistics(self):
        result = self.unresolved(first_cousins(), 'PP1_FORMAL_COMPUTATION_LIMIT',
            max_factor_cells=1)
        self.assertFalse(result.get('fourSums'))


if __name__ == '__main__':
    unittest.main()
