import copy
from fractions import Fraction
from pathlib import Path
import tempfile
import unittest

import bs1_parameter_text as t
import bs1_knowledge as k
from bs1_parameters import specification_clauses
from test_bs1_parameters import SOURCE, spec, background


def parse(text):
    return t.proposal(next(specification_clauses(spec(text), SOURCE)))


def value(item, field):
    raw = item['parameters'][field]['candidateValue']
    return Fraction(int(raw['numerator']), int(raw['denominator'])) if raw else None


COMPLETE = ('Prevalence of 1:30,000, Allelic Contribution of 0.5, '
            'Genetic Contribution of 1, and Penetrance of 0.75.')


class TextParameterTest(unittest.TestCase):
    def test_four_parameters_with_exact_rationals_and_full_source(self):
        item = parse(COMPLETE)
        self.assertTrue(item['completeExplicitPointSet'])
        self.assertEqual(Fraction(1, 30000), value(item, 'prevalence'))
        self.assertEqual(Fraction(3, 4), value(item, 'penetrance'))
        self.assertEqual(Fraction(1), value(item, 'maximumGeneContribution'))
        self.assertEqual(Fraction(1, 2), value(item, 'maximumAlleleContribution'))
        self.assertEqual(COMPLETE, item['text']); self.assertEqual(SOURCE, item['source'])
        self.assertEqual('UNREVIEWED', item['status']); self.assertFalse(item['modelExecutable'])
        self.assertFalse(item['scientificallyAdopted'])

    def test_fraction_does_not_round_nonterminating_denominator(self):
        self.assertEqual(Fraction(22, 282), value(parse('Allelic heterogeneity of 22/282.'), 'maximumAlleleContribution'))

    def test_explicit_percentage_label_aliases(self):
        item = parse('Maximum genetic heterogeneity of 1%, penetrance estimate of 85%, allelic heterogeneity of 100%.')
        self.assertEqual(Fraction(1, 100), value(item, 'maximumGeneContribution'))
        self.assertEqual(Fraction(85, 100), value(item, 'penetrance'))

    def test_elliptical_conjoined_labels(self):
        for text in ['Genetic and allelic heterogeneity were set at 100%.',
                     'Allelic and gene heterogeneity of 1.']:
            item = parse(text)
            self.assertEqual(Fraction(1), value(item, 'maximumGeneContribution'))
            self.assertEqual(Fraction(1), value(item, 'maximumAlleleContribution'))

    def test_two_full_conjoined_labels(self):
        item = parse('Maximum allelic contribution and maximum genetic contribution at 100%.')
        self.assertEqual(Fraction(1), value(item, 'maximumGeneContribution'))
        self.assertEqual(Fraction(1), value(item, 'maximumAlleleContribution'))

    def test_two_different_values_cannot_bind_to_one_conjoined_label(self):
        item = parse('genetic and allelic heterogeneity of 0.2 and 0.3')
        self.assertIsNone(value(item, 'maximumGeneContribution'))
        self.assertIsNone(value(item, 'maximumAlleleContribution'))

    def test_bound_remains_bound(self):
        item = parse('Prevalence - <1/1,000,000')
        number = item['parsed']['mentions'][0]['numeric']
        self.assertEqual('BOUND', number['kind']); self.assertEqual('<', number['operator'])
        self.assertEqual(t.packed(Fraction(1, 1000000)), number['bound'])
        self.assertIsNone(value(item, 'prevalence'))

    def test_all_bound_operators(self):
        for operator in ['<', '<=', '≤', '>', '>=', '≥']:
            with self.subTest(operator=operator):
                number = parse('penetrance = ' + operator + '0.5')['parsed']['mentions'][0]['numeric']
                self.assertEqual(operator, number['operator'])

    def test_percentage_range_does_not_select_midpoint(self):
        number = parse('penetrance 45–70%')['parsed']['mentions'][0]['numeric']
        self.assertEqual('RANGE', number['kind'])
        self.assertEqual(t.packed(Fraction(45, 100)), number['lower'])
        self.assertEqual(t.packed(Fraction(70, 100)), number['upper'])
        self.assertEqual('UNSPECIFIED', number['boundaryInclusion'])
        self.assertIsNone(number['selectedValue'])

    def test_descending_ratios_keep_written_order_and_normalized_bounds(self):
        number = parse('prevalence of 1/200,000 – 1/1,000,000')['parsed']['mentions'][0]['numeric']
        self.assertEqual(t.packed(Fraction(1, 200000)), number['writtenEndpoints'][0])
        self.assertEqual(t.packed(Fraction(1, 1000000)), number['lower'])

    def test_mixed_or_qualified_range_is_unresolved(self):
        for text in ['penetrance >0.2–0.3', 'prevalence 1/1000–0.1']:
            item = parse(text)
            self.assertIsNone(item['parsed']['mentions'][0]['numeric'])
            self.assertIn('RANGE_UNIT_OR_QUALIFIER_AMBIGUOUS', item['parsed']['mentions'][0]['issues'])

    def test_approximation_retained_not_an_exact_set(self):
        for marker in ['~', '≈', 'about ', 'approximately ']:
            item = parse('allelic heterogeneity of ' + marker + '0.5')
            self.assertEqual('APPROXIMATE', item['parsed']['mentions'][0]['numeric']['kind'])
            self.assertIsNone(value(item, 'maximumAlleleContribution'))

    def test_no_implicit_units_or_defaults(self):
        item = parse('prevalence of 10 per 100000; penetrance full; genetic heterogeneity unknown')
        self.assertIsNone(value(item, 'prevalence')); self.assertIsNone(value(item, 'penetrance'))
        self.assertIsNone(value(item, 'maximumGeneContribution'))
        self.assertFalse(item['completeExplicitPointSet'])

    def test_population_frequency_not_called_prevalence(self):
        item = parse('population frequency of 1 in 2000 individuals; penetrance 100%')
        self.assertIsNone(value(item, 'prevalence'))
        self.assertIn('POPULATION_FREQUENCY_NOT_ASSUMED_TO_BE_PREVALENCE', item['parsed']['issues'])

    def test_does_not_pick_thresholds_or_unlabelled_case_counts(self):
        item = parse('BS1 FAF > 0.1%. Prevalence based on previous reports; 77% of families had variants.')
        self.assertIsNone(value(item, 'prevalence'))
        self.assertIsNone(value(item, 'maximumGeneContribution'))

    def test_bad_numeric_tokens_do_not_parse_prefixes(self):
        for number in ['NaN', 'Infinity', '-0.3', '1e-3', '1,23', '0.2.3', '0/0', '1/0', '10%', '101%']:
            # 10% is a positive control among malformed/out-of-range inputs.
            with self.subTest(number=number):
                item = parse('penetrance = ' + number)
                if number == '10%': self.assertEqual(Fraction(1, 10), value(item, 'penetrance'))
                else: self.assertIsNone(value(item, 'penetrance'))

    def test_zero_not_a_model_parameter(self):
        self.assertIsNone(value(parse('penetrance = 0'), 'penetrance'))

    def test_alternatives_equations_and_arithmetic_not_truncated(self):
        for expression in ['0.8 or 0.9', '0.5 + 0.1', '1/100 = 0.02', '0.5 * 2', '0.5 x 2']:
            with self.subTest(expression=expression):
                self.assertIsNone(value(parse('penetrance = ' + expression), 'penetrance'))

    def test_negated_or_unavailable_label_never_used(self):
        for text in ['not penetrance 0.5', 'no penetrance 0.5', 'penetrance is not 0.5', 'unknown penetrance 0.5']:
            self.assertIsNone(value(parse(text), 'penetrance'))

    def test_repeated_different_values_require_review(self):
        item = parse('penetrance 0.5; penetrance 0.9')
        self.assertEqual('MULTIPLE_VALUES_REQUIRE_REVIEW', item['parameters']['penetrance']['status'])
        self.assertIsNone(value(item, 'penetrance'))

    def test_range_and_explicit_selected_estimate_are_not_automatically_resolved(self):
        item = parse('prevalence 1/200000–1/1000000; prevalence of 1/500000 was used')
        self.assertIsNone(value(item, 'prevalence'))

    def test_cross_reference_plus_point_not_silently_ignored(self):
        item = parse('penetrance same as BA1. penetrance 0.5')
        self.assertIsNone(value(item, 'penetrance'))

    def test_full_conditions_and_unicode_spans_preserved(self):
        text = '中文 🧬 ' + COMPLETE + ' Except variant X; applies only at age 40, population Y.'
        item = parse(text)
        self.assertEqual(text, item['text'])
        for mention in item['parsed']['mentions']:
            for stem in ['label', 'value']:
                start, end = mention[stem+'Span']
                self.assertEqual(mention[stem+'Text'], text[start:end])
        self.assertIn('FULL_CLAUSE_AND_EXPERT_PRIORITY', item['reviewRequirements'])

    def test_markdown_lists_and_html_footnotes_are_not_arithmetic(self):
        item = parse('* Prevalence: 1:50000\n* Allelic heterogeneity: 1\n'
                     '* Genetic heterogeneity: 0.13\n* Penetrance: 100%\n\n'
                     '<sup>1</sup> Consider also bottleneck populations.')
        self.assertTrue(item['completeExplicitPointSet'])
        self.assertEqual(Fraction(13, 100), value(item, 'maximumGeneContribution'))

    def test_numeric_to_prose_is_not_a_range(self):
        item = parse('penetrance of 0.75 to allow for mild disease')
        self.assertEqual(Fraction(3, 4), value(item, 'penetrance'))

    def test_multiline_numeric_arithmetic_still_unresolved(self):
        self.assertIsNone(value(parse('penetrance 0.5\n * 2'), 'penetrance'))

    def test_uncertainty_or_qualified_second_endpoint_not_truncated(self):
        for expression in ['0.5±0.1', '0.5 +/- 0.1', '0.5 to <0.8', '0.5–~0.8', '0.5--0.8']:
            with self.subTest(expression=expression):
                self.assertIsNone(value(parse('penetrance ' + expression), 'penetrance'))

    def test_length_limit_is_explicit(self):
        self.assertEqual(['TEXT_SIZE_OR_TYPE_UNSUPPORTED'], t.extract_text('x'*(t.MAX_TEXT+1))['issues'])

    def test_no_parameter_set_stitched_across_clauses(self):
        first = spec('prevalence 0.01; penetrance 0.8')
        first['original']['ld']['CriteriaCode'][0]['entContent']['additionalComments'] = 'genetic contribution 1; allelic contribution 0.1'
        proposals, drafts, report = t.extract([first], [SOURCE], [background()])
        self.assertEqual(2, len(proposals)); self.assertEqual([], drafts[0]['completeProposalIds'])
        self.assertEqual(0, report['completeExplicitPointProposals'])

    def test_exact_scope_required_for_background_mapping(self):
        backgrounds = [background(), background(hgncId='HGNC:2'), background(mondoId='MONDO:0000002'),
                       background(moi='AUTOSOMAL_RECESSIVE')]
        _, drafts, _ = t.extract([spec(COMPLETE)], [SOURCE], backgrounds)
        self.assertEqual([True, False, False, False], [bool(d['completeProposalIds']) for d in drafts])

    def test_unknown_moi_cannot_gain_model_eligibility(self):
        s = spec(COMPLETE)
        s['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance'] = 'Unknown'
        proposals, drafts, _ = t.extract([s], [SOURCE], [background()])
        self.assertEqual([], drafts[0]['proposalIds'])
        self.assertIn('MOI_SCOPE_UNSUPPORTED_OR_UNRESOLVED', proposals[0]['reviewRequirements'])

    def test_repeat_deterministic_no_input_changes_or_duplicate_inflation(self):
        s = spec(COMPLETE); before = copy.deepcopy(s)
        one = t.extract([s], [SOURCE], [background()])
        self.assertEqual(one[0], t.extract([s, s], [SOURCE], [background()])[0])
        self.assertEqual(one, t.extract([s], [SOURCE], [background()]))
        self.assertEqual(before, s)

    def test_numeric_extraction_never_makes_runtime_record(self):
        from test_bs1_knowledge import KnowledgeTest
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp); manifest = KnowledgeTest().setup_manifest(folder)
            k.compile_manifest(manifest, folder/'out')
            bundle = k.read_json((folder/'out/bundle.json').read_bytes())
            report = k.read_json((folder/'out/parameter-text-coverage.json').read_bytes())
            self.assertEqual([], bundle['records']); self.assertEqual(0, report['newScientificAdoptions'])
            self.assertEqual(0, report['newExecutableBackgrounds'])


if __name__ == '__main__': unittest.main()
