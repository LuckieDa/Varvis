"""Independent source-expression controls, not policy adoption or clinical tests."""
from decimal import getcontext
import hashlib
import json
import os
from pathlib import Path
import unittest

from cspec_frequency_expression import parse_comparison


class ComparisonTests(unittest.TestCase):
    def test_comparators_and_decimal_boundaries(self):
        for operator, expected in [('>', 'GT'), ('>=', 'GTE'), ('≥', 'GTE'), ('<', 'LT'), ('<=', 'LTE'), ('≤', 'LTE')]:
            with self.subTest(operator=operator):
                self.assertEqual({'originalExpression': operator + ' 0.000083', 'operator': expected, 'fraction': '0.000083'},
                                 parse_comparison(operator + ' 0.000083'))
        self.assertEqual('0', parse_comparison('<=0')['fraction'])
        self.assertEqual('1', parse_comparison('≥ 1.0000')['fraction'])
        self.assertIsNone(parse_comparison('>1.0000000001'))
        self.assertIsNone(parse_comparison('<-0.1'))

    def test_plain_decimal_scale_does_not_consume_significant_digit_budget(self):
        for zeros in (48, 49, 77, 78, 99):
            fraction = '0.' + '0' * zeros + '1'
            with self.subTest(zeros=zeros):
                self.assertEqual(fraction, parse_comparison('≥' + fraction)['fraction'])
        self.assertIsNone(parse_comparison('≥0.' + '1' * 51))
        self.assertIsNone(parse_comparison('≥0.' + '0' * 100 + '1'))

    def test_gn106_scientific_atom_is_exact_and_original_is_unchanged(self):
        expression = '≥8.3x10<sup>-5 </sup>'
        self.assertEqual({'originalExpression': expression, 'operator': 'GTE', 'fraction': '0.000083'},
                         parse_comparison(expression))
        alternative = ' \t< 8.3 × 10<sup> -5 </sup>\t '
        self.assertEqual(alternative, parse_comparison(alternative)['originalExpression'])
        self.assertEqual('0.000083', parse_comparison(alternative)['fraction'])

    def test_scientific_conversion_has_no_float_or_inherited_rounding(self):
        previous = getcontext().prec
        try:
            getcontext().prec = 3
            self.assertEqual('0.00123456789012345678901234567890123456789',
                             parse_comparison('>123456789012345678901234567890123456789x10<sup>-41</sup>')['fraction'])
            self.assertEqual('0.000083', parse_comparison('>=0.0000083x10<sup>+1</sup>')['fraction'])
        finally:
            getcontext().prec = previous

    def test_percent_conversion_and_equivalent_duplicate_are_exact(self):
        for expression in ['≥0.0083%', '≥0.000083 (0.0083%)', '≥8.3x10<sup>-5 </sup> (>=0.0083%)']:
            with self.subTest(expression=expression):
                self.assertEqual('0.000083', parse_comparison(expression)['fraction'])
        for expression in ['≥0.000083 (0.083%)', '≥0.000083 (>0.0083%)', '>=0.000083 (<0.0083%)', '≥0.000083 (or 0.0083%)']:
            self.assertIsNone(parse_comparison(expression), expression)

    def test_numeric_atom_does_not_swallow_context_or_conditions(self):
        for expression in ['Allele frequency in males ≥0.000083', '≥0.000083 in males',
                           '≥8.3x10<sup>-5 </sup> (based on the most frequent pathogenic allele)',
                           '≥0.000083; AN>=2000', '≥0.000083 and <0.01', '0.000083',
                           '≥0.000083\n', '\n≥0.000083', '≥0.000083\x00']:
            self.assertIsNone(parse_comparison(expression), repr(expression))

    def test_unknown_markup_or_number_notation_is_not_executed_or_stripped(self):
        for expression in ['<script>1</script>', '≥8.3x10<sup class="x">-5</sup>',
                           '≥<b>0.000083</b>', '&ge;0.000083', '≥8.3x10<SUP>-5</SUP>',
                           '≥8.3e-5', '≥.000083', '≥00.000083', '≥NaN', '≥Infinity',
                           '≥8.3*10^-5', '≥__import__("os").system("anything")']:
            self.assertIsNone(parse_comparison(expression), expression)

    def test_resource_limits_and_invalid_inputs_reject_without_overflow(self):
        for expression in [None, True, 0.1, {}, [], '>0.' + '1' * 600,
                           '>1x10<sup>-101</sup>', '>1x10<sup>999999999999999</sup>',
                           '>1x10<sup>+0001</sup>', '>0.' + '1' * 51,
                           '>1x10<sup>+100</sup>', '>1x10<sup>-100</sup>%']:
            self.assertIsNone(parse_comparison(expression), repr(expression))
        self.assertEqual('0.' + '0' * 99 + '1', parse_comparison('>1x10<sup>-100</sup>')['fraction'])


class Gn106OriginalTests(unittest.TestCase):
    def test_pinned_original_expression_and_raw_byte_locator(self):
        source = os.environ.get('VARVIS_T03_GN106_SOURCE')
        expected = os.environ.get('VARVIS_T03_GN106_SOURCE_SHA256')
        if source is None and expected is None:
            self.skipTest('Explicit GN106 original path and SHA required; synthetic tests are not original replay')
        self.assertTrue(source and expected, 'Both explicit original path and SHA are required')
        path = Path(source)
        self.assertTrue(path.is_absolute(), 'Original path must be explicit and absolute')
        raw = path.read_bytes()
        self.assertEqual('570b3f71debab0b7bc971983dbb115d3e5fd11358ed83c1dc94a48982c89ebf9', expected)
        self.assertEqual(expected, hashlib.sha256(raw).hexdigest())
        document = json.loads(raw)
        entity = document['data'][0]
        self.assertEqual('GN106', entity['entId'])
        self.assertEqual('1.0', entity['entContent']['version'])
        criterion = entity['ld']['CriteriaCode'][20]['entContent']
        self.assertEqual('BS1', criterion['label'])
        text = criterion['strengthDescriptor'][2]['text']
        expression = '≥8.3x10<sup>-5 </sup>'
        self.assertIn(expression, text)
        self.assertIn('in males', text)
        self.assertIn('based on the most frequent pathogenic allele', text)
        self.assertIsNone(parse_comparison(text), 'Complete applicability prose must not become a numeric-only approval')
        projection = parse_comparison(expression)
        self.assertEqual({'originalExpression': expression, 'operator': 'GTE', 'fraction': '0.000083'}, projection)
        fragment = expression.encode('utf-8')
        self.assertEqual(1, raw.count(fragment))
        start = raw.index(fragment)
        self.assertEqual(fragment, raw[start:start + len(fragment)])
        self.assertEqual(expected, hashlib.sha256(path.read_bytes()).hexdigest())
        output = os.environ.get('VARVIS_T03_FREQUENCY_EXPRESSION_RECEIPT')
        if output:
            receipt = {'schemaVersion': 't03-finite-frequency-expression-original-replay-v1',
                       'scope': 'NUMERIC_ATOM_ONLY_NOT_GN106_POLICY_OR_CLINICAL_ADOPTION',
                       'sourcePath': str(path), 'sourceRawSha256': expected,
                       'fieldJsonPointer': '/data/0/ld/CriteriaCode/20/entContent/strengthDescriptor/2/text',
                       'fieldUtf8Sha256': hashlib.sha256(text.encode('utf-8')).hexdigest(),
                       'expressionByteLocator': {'startInclusive': start, 'endExclusive': start + len(fragment),
                                                 'utf8Sha256': hashlib.sha256(fragment).hexdigest()},
                       'projection': projection, 'wholeSourceProseRejected': True,
                       'maleAndMostFrequentPathogenicBasisStillRequireSourceScopeReview': True,
                       'newCanonicalMoiOrMetricMigrationOrPolicyApproval': False}
            target = Path(output)
            self.assertTrue(target.is_absolute())
            self.assertFalse(target.exists(), 'Keep prior receipts; choose a fresh output')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((json.dumps(receipt, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


if __name__ == '__main__':
    unittest.main()
