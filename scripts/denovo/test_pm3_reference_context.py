import unittest
import tempfile
from pathlib import Path
from test_pm3_automatic_facts import source
from test_core_context_review import LocalFixture
from pm3_source_aliases import crosswalks, matching_coding
from pm3_identity import resolve_case


def material(note='Reference sequence: NM_000001.1; explanation.', extra=''):
    return source('<table-wrap><caption><p>EXAMPLE pathogenic variants identified by NGS in 18 patients.</p></caption>'
        '<table><thead><tr><th>Nucleotide Aberration</th><th>Protein Change</th></tr></thead><tbody>'
        '<tr><td>c.2C&gt;G</td><td>T1S</td></tr></tbody></table>'
        '<table-wrap-foot><fn><p>'+note+'</p></fn>'+extra+'</table-wrap-foot></table-wrap>')


class ReferenceContext(unittest.TestCase):
    def test_exact_table_foot_provides_reference_with_original_citation(self):
        rows = crosswalks(material())
        self.assertEqual(1,len(rows)); self.assertEqual('NM_000001.1',rows[0]['transcript'])
        self.assertTrue(any('/table-wrap-foot[' in c['unitId'] for c in rows[0]['citations']))
        self.assertEqual(('NM_000001.1','T1S'),matching_coding({'sourceAlleleCrosswalks':rows},'EXAMPLE','c.2C>G')[:2])

    def test_coding_only_observation_uses_same_paper_reference_and_sequence(self):
        with tempfile.TemporaryDirectory() as temp:
            fixture=LocalFixture(Path(temp))
            case={'caseLabel':'1','sourceAlleleCrosswalks':crosswalks(material()),'semantics':{'observations':[{
                'geneSymbol':'EXAMPLE','targetRepresentation':'c.2C>G','partnerRepresentation':None,'reportedTranscripts':[]}]}}
            result=resolve_case(case,fixture.resolver())[0]
            self.assertEqual('LOCAL_IDENTITIES_VERIFIED',result['status'])
            self.assertEqual(101,result['identities']['target']['allele']['position'])
            self.assertEqual('T1S',result['identities']['target']['proteinChange'])
            case['semantics']['observations'][0]['reportedProteins']={'target':'T1A'}
            self.assertEqual('PM3_SOURCE_PROTEIN_CONFLICT',resolve_case(case,fixture.resolver())[0]['issues'][0]['reasonCode'])

    def test_ambiguous_negated_or_unversioned_reference_not_supplied(self):
        for note in ['Not the reference sequence: NM_000001.1.', 'Reference sequence: NM_000001.',
                     'Reference sequence: NM_000001.1 or NM_000001.2.']:
            with self.subTest(note=note): self.assertIsNone(crosswalks(material(note))[0]['transcript'])
        self.assertIsNone(crosswalks(material(extra='<fn><p>Reference sequence: NM_000001.2.</p></fn>'))[0]['transcript'])
        with self.assertRaisesRegex(ValueError, 'UNRESOLVED'):
            matching_coding({'sourceAlleleCrosswalks':crosswalks(material('Reference sequence: NM_000001.1 or NM_000001.2.'))},
                            'EXAMPLE','c.2C>G')

    def test_conflicting_rows_and_wrong_gene_do_not_choose_convenient_mapping(self):
        rows=crosswalks(material()); self.assertIsNone(matching_coding({'sourceAlleleCrosswalks':rows},'OTHER','c.2C>G'))
        with self.assertRaisesRegex(ValueError,'TRANSCRIPT_CONFLICT'):
            matching_coding({'sourceAlleleCrosswalks':rows+[{**rows[0],'transcript':'NM_000001.2'}]},'EXAMPLE','c.2C>G')


if __name__ == '__main__': unittest.main()
