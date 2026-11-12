import unittest

from pm3_literature_contract import VERSION, review
from pm3_observations import native_projection
from test_pm3_automatic_facts import source


CONTEXT = ('<sec><title>Introduction</title><p>Example syndrome (ES; MIM#123456) is an '
           'autosomal recessive disorder caused by mutations in EXAMPLE (Example gene; GenBank: NM_000001.1).</p></sec>')
FIGURE = '<xref ref-type="fig" rid="pedigree">1</xref>'
INTRO = f'<p>A 9-year-old boy (II-3, Figure {FIGURE}) was referred to the clinic.</p>'
GENOTYPE = ('<p>The ES diagnosis was supported by positive ABC. Sequencing revealed two heterozygous variants: '
            'c.9G&gt;A (p.Gly3Arg) and c.12_14del (p.Leu5del).</p>')
PHASE = ('<p>The segregation analysis revealed that the two variants were in cis, paternally inherited as a complex allele. '
         f'The data were confirmed by cloning assay (Figure {FIGURE}) and allele-specific PCR.</p>')
CAPTION = ('<fig id="pedigree"><caption><p>c.[9G&gt;a;12_14del] in the proband and his father.</p></caption></fig>')
TARGET = {'geneSymbol': 'EXAMPLE', 'variantAliases': ['c.9G>A']}


class CisCaseReport(unittest.TestCase):
    def case(self, phase=PHASE, caption=CAPTION, extra='', origin='AUTHOR_COHORT'):
        s = source(CONTEXT + INTRO + GENOTYPE + phase + extra + caption)
        s.update(coverage={}, attachments=[])
        unit = next(u for u in s['units'] if 'was referred to the clinic' in u['text'])
        quote = {'unitId': unit['unitId'], 'quote': unit['text']}
        material = {'schemaVersion': VERSION, 'coverage': 'EXTRACTED_WITHIN_TARGET', 'sourceAssessments': [],
                    'cases': [{'caseLabel': 'II-3', 'caseOrigin': origin, 'caseCitation': quote,
                               'originCitations': [quote], 'fields': []}]}
        checked = review(s, material, allow_missing_assessment=True)
        return native_projection(s, TARGET, 1, [checked])['cases'][0]

    def test_bound_same_side_assay(self):
        case = self.case()
        observation, = case['semantics']['observations']
        self.assertEqual('VERIFIED', case['occurrenceQualification']['qualification'])
        self.assertEqual('CIS', observation['phase'])
        self.assertEqual('FATHER', observation['phaseAssayProof']['parent'])
        self.assertEqual(['c.12_14del', 'c.9G>A'], observation['phaseAssayProof']['alleles'])
        self.assertEqual('pedigree', observation['phaseAssayProof']['references'][-1]['sourceObjectId'])

    def test_wrong_figure_allele_parent_or_origin_cannot_supply_phase(self):
        for case in (self.case(caption=CAPTION.replace('pedigree', 'other')),
                     self.case(caption=CAPTION.replace('12_14del', '13_15del')),
                     self.case(caption=CAPTION.replace('his father', 'his mother')),
                     self.case(phase=PHASE.replace('were in cis', 'might be in cis')),
                     self.case(origin='CITED_ONLY')):
            self.assertEqual([], case['semantics']['observations'])

    def test_conflicting_same_case_phase_is_retained(self):
        case = self.case(extra='<p>The two variants were in trans.</p>')
        self.assertEqual('CONFLICT', case['semantics']['observations'][0]['phase'])


if __name__ == '__main__':
    unittest.main()
