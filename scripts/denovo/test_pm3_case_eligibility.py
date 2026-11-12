"""Focused behavior checks for released PM3 case conditions."""
import unittest

import pm3_specification_context  # loads the shared released scope reader
from pm3_case_eligibility import DIAGNOSIS, NIGHT, ROD, phenotype
from pm3_published_partner import target_exclusion


def cell(field, heading, value, locator):
    return {'field': field, 'columnHeading': heading, 'value': value,
            'citation': {'sourceKind': 'SUPPLEMENT_CELL', 'bundleSha256': 'a' * 64, 'sourceSha256': 'b' * 64,
                         'sheet': 'Supplement', 'cell': locator, 'quote': value}}


class ReleasedCaseConditionsTest(unittest.TestCase):
    def phenotype(self, *facts):
        case = {'caseLabel': 'A', 'anchors': [cell('caseLabel', 'case', 'A', 'R2C1')['citation']],
                'primarySourceQualification': {'qualification': 'VERIFIED'}}
        return phenotype(case, {'facts': facts, 'reportedAffectedStatus': 'AFFECTED'})

    def test_erg_counts_only_in_the_same_case_and_does_not_relabel_diagnosis(self):
        report = cell('diagnosis', 'phenotype', 'Rod-cone dystrophy', 'R2C3')
        another_case = cell('reportedClinicalAssay', 'fferg', 'Non-recordable responses', 'R3C5')
        self.assertEqual('UNKNOWN', self.phenotype(report, another_case)['qualification'])
        same_case = cell('reportedClinicalAssay', 'fferg', 'Non-recordable responses', 'R2C5')
        proof = self.phenotype(report, same_case)
        routes = {x['route']: x['qualification'] for x in proof['routes']}
        self.assertEqual({ROD: 'VERIFIED', NIGHT: 'UNKNOWN', DIAGNOSIS: 'UNKNOWN'}, routes)
        self.assertEqual('VERIFIED', proof['qualification'])
        normal = cell('reportedClinicalAssay', 'rod erg', 'Normal', 'R2C6')
        self.assertEqual('CONFLICT', self.phenotype(report, same_case, normal)['qualification'])
        self.assertEqual('UNKNOWN', self.phenotype(report, normal)['qualification'])
        no_symptom = cell('reportedSymptom', 'congenital night blindness', 'No', 'R2C7')
        no_dx = cell('diagnosis', 'phenotype', 'LCA and EORD excluded', 'R2C3')
        self.assertEqual('FAILED', self.phenotype(no_dx, no_symptom, normal)['qualification'])
        symptom = cell('reportedSymptom', 'congenital night blindness', 'Yes', 'R2C7')
        self.assertEqual('VERIFIED', self.phenotype(report, symptom, normal)['qualification'])

    def test_target_dependency_changes_weight_and_incomplete_review_cannot_count(self):
        pp4 = 'The proband with the specific phenotype was described in PMID:123 (PP4_Strong).'
        pm3 = 'In trans with c.200G>T in PMID:456 (PM3_Strong).'
        lead = 'Observed with p.Ala40Val in the same proband (PMID:123).'
        text = (f'{pp4} {pm3} In summary, this variant meets criteria to be classified as pathogenic for Example '
                'based on the ACMG/AMP criteria applied: PP4_Strong, PM3_Strong, PP3_Moderate, PM2_Supporting. '
                '(VCEP specifications version 1.0.0; date of approval 04/12/2022)')
        source = {'scv': 'SCV000000001.1', 'sourceClassification': 'Pathogenic', 'transcript': 'NM_123456.1',
                  'comments': [{'quote': text, 'locator': '/partner/comment'}]}
        target = {'scv': 'SCV000000002.1', 'sourceSha256': 'c' * 64, 'sourceUrl': 'https://example.org/target',
                  'comments': [{'quote': lead, 'locator': '/target/comment'}]}
        review = {'PP4': {'quote': pp4, 'pmids': ['123'], 'targetAssertionQuote': lead},
                  'PM3': {'quote': pm3, 'pmids': ['456'], 'otherCodingAlleles': ['c.200G>T']}}
        partner = {'coding': 'c.119C>T', 'proteinChange': 'A40V'}

        def check(coding, evidence):
            return target_exclusion(source, target, {'coding': coding}, partner, evidence, '1.0.0')

        result = check('c.300G>T', review)
        self.assertEqual(('VERIFIED', 'LIKELY_PATHOGENIC', 7),
                         (result['independentClassification'], result['classification'], result['independentPoints']))
        self.assertEqual('EXCLUDED_TARGET_DEPENDENT', result['assertionAssessments'][0]['status'])
        self.assertEqual('FAILED', check('c.200G>T', review)['independentClassification'])
        unresolved = check('c.300G>T', {'PP4': review['PP4']})
        self.assertEqual('UNKNOWN', unresolved['independentClassification'])
        self.assertEqual(['PM3'], unresolved['missingCriteria'])


if __name__ == '__main__':
    unittest.main()
