"""Source-to-typed-fact counterexamples for formal PP1 prerequisites.

All sources and identities are synthetic. No model, network or scientific
evaluation is executed; the native fixture replays the existing audit chain.
"""
import unittest

from model_literature_contract import canonical, digest
from pp1_source_qualification import qualify
import test_pp1_native_results as native
from test_pp1_segregation import selection


class FormalSourceQualification(unittest.TestCase):
    qualify = native.NativeResults.qualify

    def observations(self, prose, field, member='I:1', **kwargs):
        return self.qualify(prose, member, field=field, **kwargs)['observations']

    def values(self, prose, field, member='I:1', **kwargs):
        return [(row['kind'], row['value']) for row in self.observations(prose, field, member, **kwargs)]

    def test_family_ascertainment_requires_named_phenotype_first_scope(self):
        text = 'Family F1 was ascertained on the basis of clinical phenotype before genotyping.'
        rows = self.observations(text, 'F04.1', 'FAMILY')
        self.assertEqual([('formalAscertainment', 'PHENOTYPE_BEFORE_GENOTYPING')],
                         [(row['kind'], row['value']) for row in rows])
        self.assertEqual('FAMILY', rows[0]['memberLabel'])
        self.assertTrue(rows[0]['claimIds'])
        self.assertTrue(all(ref['pmcid'] == 'PMC123' and ref['bundleSha256']
                            for ref in rows[0]['sourceRefs']))
        self.assertFalse(self.values(text.replace('F1', 'F9'), 'F04.1', 'FAMILY'))

    def test_explicit_member_ascertainment_is_not_generalized_to_family(self):
        text = 'The individual I:1 was recruited based on clinical diagnosis prior to genetic testing.'
        self.assertIn(('formalAscertainment', 'PHENOTYPE_BEFORE_GENOTYPING'),
                      self.values(text, 'F04.1'))
        self.assertFalse(self.values(text, 'F04.1', 'FAMILY'))
        self.assertFalse(self.values(text + ' II:2 was examined.', 'F04.1', 'II:2'))

    def test_role_timing_or_generic_cohort_is_not_phenotype_ascertainment(self):
        for prose in (
            'Family F1 was ascertained through the proband I:1.',
            'Family F1 was ascertained on the basis of clinical phenotype.',
            'Family F1 was selected after genotyping based on clinical phenotype.',
            'Family F1 was ascertained by variant-positive relatives before genotyping.',
            'Genotyped carriers in family F1 were recruited for segregation analysis.',
            'Segregating families including family F1 were recruited.',
            'Families were ascertained on the basis of clinical phenotype before genotyping.',
            'Family F1 was ascertained on the basis of clinical phenotype before genotyping, but only positive relatives were included.',
        ):
            with self.subTest(prose=prose):
                self.assertNotIn(('formalAscertainment', 'PHENOTYPE_BEFORE_GENOTYPING'),
                                 self.values(prose, 'F04.1', 'FAMILY'))

    def test_short_ascertainment_quote_cannot_hide_outer_negation(self):
        quote = 'Family F1 was ascertained on the basis of clinical phenotype before genotyping.'
        self.assertFalse(self.values('It is not true that ' + quote, 'F04.1', 'FAMILY', quote=quote))
        self.assertFalse(self.values('Whether ' + quote, 'F04.1', 'FAMILY', quote=quote))

    def test_unqualified_family_ascertainment_retains_reported_text_and_scope(self):
        prose = 'Family F1 was ascertained on the basis of clinical phenotype.'
        result = self.qualify(prose, 'FAMILY', field='F04.1')
        self.assertFalse(result['observations'])
        issue = next(row for row in result['unresolved'] if row['reasonCode']=='PP1_TYPED_SEMANTICS_UNRESOLVED')
        self.assertEqual(('F04.1','FAMILY',prose),
                         (issue['fieldId'],issue['memberLabel'],issue['sourceValue']))
        self.assertTrue(issue['claimId'] and issue['familyKey'] and issue['memberKey'])
        self.assertTrue(all(ref['quote']==prose and ref['pmcid']=='PMC123' for ref in issue['sourceRefs']))

    def test_attained_age_requires_examination_or_follow_up(self):
        for prose in (
            'I:1 was clinically examined at age 30 years.',
            'I:1 underwent clinical examination at the age of 30 years.',
            'I:1 was last followed up at 30 years of age.',
            'I:1 was 30 years old at the last follow-up.',
            'At her last examination, I:1 was 30 years old.',
        ):
            with self.subTest(prose=prose):
                self.assertIn(('examinationAge', 30), self.values(prose, 'F05.4'))

    def test_onset_or_ordinary_age_does_not_supply_examination_age(self):
        for prose in (
            'I:1 was 30 years old.',
            'I:1 was aged 30 years old.',
            'I:1 had disease onset at age 30 years.',
            'I:1 first developed symptoms at age 30 years.',
            'I:1 was assessed at age 30 years.',
            'I:1 was evaluated at age 30 years.',
            'I:1 may have been examined at age 30 years.',
            'I:1 was not examined at age 30 years.',
        ):
            with self.subTest(prose=prose):
                self.assertFalse(any(kind == 'examinationAge' for kind, value in self.values(prose, 'F05.4')))
        # The existing simple-method age fact remains available separately.
        self.assertIn(('age', 30), self.values('I:1 was 30 years old.', 'F05.4'))

    def test_onset_and_examination_ages_remain_distinct_in_one_claim(self):
        rows = self.values('I:1 was 10 years old at onset. I:1 was clinically examined at age 30 years.', 'F05.4')
        self.assertIn(('age', 10), rows)
        self.assertIn(('examinationAge', 30), rows)
        self.assertNotIn(('examinationAge', 10), rows)

    def test_age_cannot_borrow_another_member_or_hide_outer_uncertainty(self):
        self.assertNotIn(('examinationAge', 30),
                         self.values('I:1 was examined at age 30 years. II:2 was healthy.', 'F05.4', 'II:2'))
        quote = 'I:1 was examined at age 30 years.'
        self.assertNotIn(('examinationAge', 30),
                         self.values('It is possible that ' + quote, 'F05.4', quote=quote))

    def table_age(self, heading, cell, *, reviewed=True, merged=False):
        labels = ['Individual ID', heading, 'I:1', cell]
        units = [{'unitId': f'U{i}', 'text': value, 'sourceSha256': 'c' * 64}
                 for i, value in enumerate(labels)]
        cells = [{'unitId': unit['unitId'], 'text': unit['text'], 'sourceCellIndex': i % 2,
                  'header': i < 2, 'rowspan': 1, 'colspan': 2 if merged and i == 3 else 1}
                 for i, unit in enumerate(units)]
        source = {'pmcid': 'PMC123', 'bundleSha256': 'a' * 64,
                  'main': {'sourceSha256': 'b' * 64, 'tables': [{'rows': [{'cells': cells[:2]}, {'cells': cells[2:]}]}]},
                  'units': units}
        family = digest(canonical(['PMC123', 'b' * 64, 'F1']))
        member = digest(canonical(['PMC123', 'b' * 64, 'F1', 'I:1']))
        fact = {'claimId': 'd' * 64, 'familyKey': family, 'memberKey': member,
                'raw': {'familyLabel': 'F1', 'memberLabel': 'I:1', 'fieldId': 'F05.4', 'sourceValue': cell},
                'issues': [], 'sourceAudit': {'verdict': 'SUPPORTED'},
                'sourceRefs': [{'unitId': 'U3', 'quote': cell, 'sourceSha256': 'c' * 64}]}
        inventory = {'sourceIdentity': {'pmcid': 'PMC123', 'bundleSha256': 'a' * 64},
                     'target': {'geneSymbol': 'GENE1', 'variantAliases': ['c.1A>G']},
                     'inputScope': {'unitIds': ['U0', 'U1', 'U2', 'U3'] if reviewed else ['U2', 'U3']},
                     'families': {'F1': {'canonicalLabel': 'F1', 'members': {'I:1': {}}}}, 'facts': [fact]}
        return qualify(source, inventory, selection())['observations']

    def test_table_examination_age_keeps_header_subject_and_unit_proof(self):
        for heading, cell in [('Age at examination (years)', '30'), ('Age at last follow-up', '30 years')]:
            with self.subTest(heading=heading, cell=cell):
                rows = self.table_age(heading, cell)
                self.assertEqual([('examinationAge', 30)], [(r['kind'], r['value']) for r in rows])
                self.assertEqual({'U0', 'U1', 'U2', 'U3'}, {r['unitId'] for r in rows[0]['sourceRefs']})

    def test_table_onset_unitless_or_unreviewed_headers_do_not_supply_risk_age(self):
        for heading, cell in [('Age at onset (years)', '30'), ('Age (years)', '30'),
                              ('Age at examination', '30'), ('Age at examination (months)', '30')]:
            with self.subTest(heading=heading, cell=cell):
                self.assertFalse(self.table_age(heading, cell))
        self.assertFalse(self.table_age('Age at examination (years)', '30', reviewed=False))
        self.assertFalse(self.table_age('Age at examination (years)', '30', merged=True))

    def test_consanguinity_retains_same_claim_and_proof_as_caution(self):
        for prose, member in [('The parents of I:1 were consanguineous.', 'I:1'),
                              ('I:1 was born to consanguineous parents.', 'I:1'),
                              ('Consanguinity was reported in family F1.', 'FAMILY')]:
            with self.subTest(prose=prose):
                rows = self.observations(prose, 'F07.3', member)
                caution = next(r for r in rows if r['kind'] == 'caution')
                typed = next(r for r in rows if r['kind'] == 'consanguinity')
                self.assertIs(typed['value'], True)
                self.assertEqual(caution['claimIds'], typed['claimIds'])
                self.assertEqual(caution['sourceRefs'], typed['sourceRefs'])
                self.assertNotEqual(caution['id'], typed['id'])

    def test_consanguinity_does_not_absorb_other_cautions(self):
        for prose in (
            'The parents of I:1 were not consanguineous.',
            'The parents of I:1 might be consanguineous.',
            'The parents of I:1 were consanguineous. Another candidate was reported.',
            'The parents of I:1 were consanguineous and another candidate was reported.',
            'I:1 had an uncertain diagnosis.',
        ):
            with self.subTest(prose=prose):
                rows = self.observations(prose, 'F07.3')
                self.assertTrue(any(r['kind'] == 'caution' for r in rows))
                self.assertFalse(any(r['kind'] == 'consanguinity' for r in rows))

    def test_new_typed_facts_require_field_scope_and_supported_audit(self):
        examples = [('Family F1 was ascertained on the basis of clinical phenotype before genotyping.', 'F04.1', 'FAMILY', 'formalAscertainment'),
                    ('I:1 was examined at age 30 years.', 'F05.4', 'I:1', 'examinationAge'),
                    ('The parents of I:1 were consanguineous.', 'F07.3', 'I:1', 'consanguinity')]
        for prose, field, member, kind in examples:
            for verdict in ('AMBIGUOUS', 'WRONG_SUBJECT_OR_FIELD', 'CONTRADICTED'):
                with self.subTest(field=field, verdict=verdict):
                    self.assertFalse(any(r['kind'] == kind for r in self.observations(
                        prose, field, member, audit_verdict=verdict)))
        self.assertFalse(any(r['kind'] == 'formalAscertainment' for r in
                             self.observations(examples[0][0], 'F04.2', 'FAMILY')))
        self.assertFalse(any(r['kind'] == 'consanguinity' for r in
                             self.observations(examples[2][0], 'F09.2')))


if __name__ == '__main__':
    unittest.main()
