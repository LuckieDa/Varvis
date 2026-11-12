"""Check integrity/hand-authored geometry of the future scientific oracle.

These tests do NOT run a PVS1 evaluator or validate its expected clinical outcomes.
"""
from pathlib import Path
import unittest

from assemble_materials import parse

ORACLE = Path(__file__).resolve().parents[2] / 'varvis-server/src/test/resources/acmg/pvs1-start-loss-expectations-v1.json'


class StartLossExpectationsTest(unittest.TestCase):
    def setUp(self):
        self.oracle = parse(ORACLE.read_bytes())

    def test_oracle_cannot_be_mistaken_for_executed_scientific_results(self):
        self.assertEqual('SYNTHETIC', self.oracle['dataClass'])
        self.assertFalse(self.oracle['runtimeEligible'])
        self.assertEqual('EXPECTED_BEFORE_IMPLEMENTATION_NOT_EXECUTED', self.oracle['status'])
        cases = self.oracle['cases']
        self.assertEqual(18, len(cases))
        self.assertEqual(18, len({c['id'] for c in cases}))
        for case in cases:
            with self.subTest(case=case['id']):
                outcome = case['expected']
                if outcome['display'] == 'MET':
                    self.assertIn(outcome['strength'], ('MODERATE', 'SUPPORTING'))
                else:
                    self.assertEqual('NONE', outcome['strength'])

    def test_hand_authored_strand_alleles_and_codons_agree(self):
        complement = str.maketrans('ACGT', 'TGCA')
        for case in self.oracle['cases'][:2]:
            for variant in case['variants']:
                with self.subTest(case=case['id'], base=variant['cdsPosition']):
                    negative = case['overrides']['strand'] == -1
                    ref = variant['ref'].translate(complement) if negative else variant['ref']
                    alt = variant['alt'].translate(complement) if negative else variant['alt']
                    codon = list('ATG'); index = variant['cdsPosition'] - 1
                    self.assertEqual(codon[index], ref)
                    codon[index] = alt
                    self.assertEqual(variant['mutantCodon'], ''.join(codon))
                    self.assertEqual((102 - index) if negative else (100 + index), variant['genomicPosition'])

    def test_hand_authored_restart_and_interval_agree_with_sequence(self):
        base = self.oracle['baseFacts']
        cds = base['referenceCds']
        self.assertEqual('ATG', cds[9:12])
        self.assertEqual(['AAA', 'CCC'], [cds[3:6], cds[6:9]])
        self.assertEqual(10, base['nearestDownstreamInFrameAtgCdsPosition'])
        self.assertEqual([1, 9], base['strictUpstreamCdsInterval'])
        no_restart = next(c for c in self.oracle['cases'] if c['id'] == 'SL11')['overrides']
        cds = no_restart['referenceCds']
        self.assertFalse(any(cds[i:i+3] == 'ATG' for i in range(3, len(cds), 3)))


if __name__ == '__main__':
    unittest.main()
