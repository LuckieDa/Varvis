"""Synthetic cohort controls; no clinical adoption or model calls."""
import unittest

from test_literature_case_structure import document, TABLE
from literature_case_structure import tables, structural_assertions
from qualify_literature_cases import source_case

BODY = (b'<sec><title>RESULTS</title><p>The parents of two of the two patients '
        b'(patients 3 and 7) underwent next-generation sequencing or Sanger sequencing to confirm inheritance of the finding. '
        b'All two cases involving parental testing determined the respective EXAMPLE alteration to be de novo; '
        b'inheritance of the remaining cases remain unknown.</p></sec>')


def facts(body=BODY, table=TABLE, label='3'):
    raw, doc = document(body, table)
    rows = tables(doc)
    row = next(r for r in rows if r['caseLabel'] == label)
    result = source_case(doc, rows, row, [])
    structural_assertions({'raw': raw, 'main': doc}, row, rows, result)
    return result


class CohortFactsTests(unittest.TestCase):
    def test_exact_cohort_result_and_row_method_reconciled(self):
        result = facts()
        self.assertEqual('PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO', result['targetTesting']['status'])
        self.assertEqual('SANGER', result['targetTesting']['method'])
        self.assertEqual('PUBLISHED_PARENTAGE_NOT_CONFIRMED', result['parentage']['status'])
        # A verified assay is not a free pass for unknown family context/identity.
        self.assertEqual('UNRESOLVED', result['contextVerification'])
        self.assertEqual('UNRESOLVED', result['alleleIdentity']['status'])
        self.assertEqual('UNKNOWN', result['familyHistory'])

    def test_row_alone_does_not_establish_target_results(self):
        self.assertEqual('UNKNOWN', facts(b'')['targetTesting']['status'])

    def test_not_assessed_row_never_borrows_group_testing(self):
        self.assertEqual('UNKNOWN', facts(label='7')['targetTesting']['status'])

    def test_wrong_or_ambiguous_group_is_rejected(self):
        for before, after in [(b'3 and 7', b'2 and 7'), (b'3 and 7', b'3 and 3'),
                              (b'parents of two', b'parents of three'),
                              (b'All two cases', b'All three cases'),
                              (b'of the two patients', b'of the eight patients'),
                              (b'EXAMPLE alteration', b'OTHER alteration'),
                              (b'All two cases', b'Not all two cases'),
                              (b'RESULTS', b'DISCUSSION')]:
            with self.subTest(after=after):
                self.assertEqual('UNKNOWN', facts(BODY.replace(before, after))['targetTesting']['status'])

    def test_other_table_or_incompatible_method_rejected(self):
        for method in (b'Unknown', b'tiro WGS', b'Sanger sequencing not performed'):
            with self.subTest(method=method):
                self.assertEqual('UNKNOWN', facts(table=TABLE.replace(b'Sanger sequencing', method))['targetTesting']['status'])

    def test_unassigned_maternal_findings_preserved_not_assigned(self):
        body = BODY.replace(b'</p>', b' Three of the mothers had recurrent prior miscarriages without a specific diagnosis.</p>')
        r = facts(body)
        self.assertEqual('PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO', r['targetTesting']['status'])
        self.assertIn('LITERATURE_FAMILY_CONTEXT_ATTRIBUTION_UNRESOLVED', r['issues'])
        self.assertIn('miscarriages', r['unresolvedContext'][0]['quote'])
        self.assertEqual('UNKNOWN', r['familyHistory'])

    def test_explicit_parental_conflict_blocks_target_status(self):
        for text in (b'Patient 3 inherited the variant from her mother.',
                     b'The father of patient 3 carried the variant.',
                     b'Parental samples were contaminated.'):
            with self.subTest(text=text):
                r = facts(BODY.replace(b'</p>', b' ' + text + b'</p>'))
                self.assertEqual('UNKNOWN', r['targetTesting']['status'])

    def test_duplicate_group_claim_not_chosen_arbitrarily(self):
        self.assertEqual('UNKNOWN', facts(BODY + BODY)['targetTesting']['status'])

    def test_genome_build_and_alleles_must_come_from_same_explicit_row(self):
        table = TABLE.replace(b'<th>Protein change</th>',
                              b'<th>Protein change</th><th>GRCh38/hg38</th><th>REF</th><th>ALT</th>')
        table = table.replace(b'<td>p.T41A</td>', b'<td>p.T41A</td><td>Chr3:100</td><td>A</td><td>G</td>')
        table = table.replace(b'<td>p.T42A</td>', b'<td>p.T42A</td><td>Chr3:200</td><td>C</td><td>T</td>')
        r = facts(table=table)
        observation = r['reportedGenomicObservation']
        self.assertEqual({'assembly': 'GRCh38', 'contig': '3', 'position': 100,
                          'reference': 'A', 'alternate': 'G'}, observation['allele'])
        self.assertEqual('UNRESOLVED', r['alleleIdentity']['status'])
        for before, after in [(b'GRCh38/hg38', b'GRCh37/hg19'), (b'Chr3:100', b'Chr3:100-101'),
                              (b'Chr3:100', b'Chr3:0'), (b'<td>A</td>', b'<td>N</td>'),
                              (b'<td>G</td>', b'<td>A</td>')]:
            with self.subTest(after=after):
                self.assertNotIn('allele', facts(table=table.replace(before, after))['reportedGenomicObservation'])

    def test_ngs_method_is_not_fabricated_as_sanger(self):
        r = facts(table=TABLE.replace(b'Sanger sequencing', b'trio WES'))
        self.assertEqual('PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO', r['targetTesting']['status'])
        self.assertEqual('TRIO_EXOME', r['targetTesting']['method'])
        self.assertEqual('PUBLISHED_PARENTAGE_NOT_CONFIRMED', r['parentage']['status'])

    def test_ngs_only_narrative_does_not_confirm_sanger_row(self):
        body = BODY.replace(b'next-generation sequencing or Sanger sequencing', b'next-generation sequencing')
        self.assertEqual('UNKNOWN', facts(body)['targetTesting']['status'])


if __name__ == '__main__':
    unittest.main()
