"""Synthetic layout contracts; no invented clinical qualification."""
import unittest
from test_pm3_automatic_facts import source
from pm3_observations import ownership
from pm3_case_semantics import qualify


def document(head, body=None):
    body = body or '<tr><td>27</td><td>c.2C&gt;G</td><td>c.8A&gt;T</td></tr>'
    return source('<table-wrap><caption><p>EXAMPLE genotypes.</p></caption><table><thead>'
                  + head + '</thead><tbody>' + body + '</tbody></table></table-wrap>')


FLAT = '<tr><th>Case</th><th>Mutation 1</th><th>Mutation 2</th></tr>'
STACKED = ('<tr><th rowspan="2">Case</th><th colspan="2">Mutation</th></tr>'
           '<tr><th>1</th><th>2</th></tr>')


def case(s):
    scope = ownership(s, '27')
    facts = []
    for field, value in [('targetVariant', 'c.2C>G'), ('partnerVariant', 'c.8A>T')]:
        u = next(u for u in s['units'] if u['text'] == value)
        facts.append({'field': field, 'sourceValue': value, 'subject': 'PROBAND', 'issues': [],
                      'citation': {'unitId': u['unitId'], 'quote': value}, 'ownership': scope.get(u['unitId'])})
    from pm3_source_context import table_context_facts
    facts.extend(table_context_facts(s, scope))
    return {'caseLabel': '27', 'facts': facts}


class TableHeaders(unittest.TestCase):
    def test_stacked_headers_qualify_same_case_without_inventing_phase(self):
        s = document(STACKED)
        result = qualify(case(s), {'geneSymbol': 'EXAMPLE', 'variantAliases': ['c.2C>G']})
        self.assertEqual('REPORTED_GENOTYPE_VERIFIED', result['status'])
        self.assertEqual('UNKNOWN', result['observations'][0]['phase'])
        self.assertFalse(result['observations'][0]['businessPublishable'])
        proof = result['verifiedLiteralFacts'][0]['headingEvidence']
        self.assertEqual(['Mutation', '1'], [x['quote'] for x in proof['sourceHeadings']])
        self.assertEqual('Mutation 1', proof['effectiveHeading'])

    def test_detected_mutation_systematic_names_keep_full_header_proof(self):
        head=('<tr><th rowspan="2">Case</th><th>Detected Mutation</th><th>Detected Variant</th></tr>'
              '<tr><th>Systematic Name</th><th>HGVS</th></tr>')
        result=qualify(case(document(head)),{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']})
        self.assertEqual('REPORTED_GENOTYPE_VERIFIED',result['status'])
        self.assertEqual('UNKNOWN',result['observations'][0]['phase'])
        proof=result['verifiedLiteralFacts'][0]['headingEvidence']
        self.assertEqual(['Detected Mutation','Systematic Name'],[s['quote'] for s in proof['sourceHeadings']])
        for group in ['Predicted Mutation','Maternal Mutation','Previous Mutation','No Detected Mutation']:
            bad=document(head.replace('Detected Mutation',group))
            self.assertEqual([],qualify(case(bad),{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']})['observations'])

    def test_detected_group_other_fields_are_not_alleles(self):
        from pm3_table_headers import compose
        for leaf in ['FI','MI','Frequency','Classification','Protein Activity','Unknown']:
            self.assertNotEqual('RESOLVED',compose(['Detected Mutation',leaf])[1])
        self.assertNotEqual('RESOLVED',compose(['Mother','Detected Mutation','Systematic Name'])[1])
        self.assertNotEqual('RESOLVED',compose(['Systematic Name'])[1])

    def test_break_is_boundary_not_deleted_qualifier(self):
        s = document(FLAT.replace('Mutation 1', 'Mutation<break/>1'))
        result = qualify(case(s), {'geneSymbol': 'EXAMPLE', 'variantAliases': ['c.2C>G']})
        self.assertEqual('REPORTED_GENOTYPE_VERIFIED', result['status'])
        self.assertEqual('Mutation1', result['verifiedLiteralFacts'][0]['headingEvidence']['sourceHeadings'][0]['quote'])
        bad = document(FLAT.replace('Mutation 1', 'Mutation 1<break/>AV'))
        result = qualify(case(bad), {'geneSymbol': 'EXAMPLE', 'variantAliases': ['c.2C>G']})
        self.assertEqual([], result['observations'])
        f = result['unverifiedFindings'][0]
        self.assertEqual('PM3_TABLE_HEADER_MEANING_AMBIGUOUS', f['reasonCode'])
        self.assertEqual(['Mutation 1', 'AV'], f['headingEvidence']['sourceHeadings'][0]['lines'])

    def test_parent_context_not_discarded(self):
        for group in ['Mother', 'Unaffected relatives', 'Predicted', 'AV', 'Previous reports']:
            with self.subTest(group=group):
                s = document(STACKED.replace('>Mutation<', '>' + group + '<').replace('>1<', '>Mutation 1<').replace('>2<', '>Mutation 2<'))
                result = qualify(case(s), {'geneSymbol': 'EXAMPLE', 'variantAliases': ['c.2C>G']})
                self.assertEqual([], result['observations'])

    def test_explicit_genotype_group_and_leaf(self):
        s = document(STACKED.replace('>Mutation<', '>Genotype<').replace('>1<', '>Mutation 1<').replace('>2<', '>Mutation 2<'))
        self.assertEqual('REPORTED_GENOTYPE_VERIFIED', qualify(case(s), {'geneSymbol': 'EXAMPLE', 'variantAliases': ['c.2C>G']})['status'])

    def test_duplicate_case_and_merged_body_stay_unowned(self):
        row = '<tr><td>27</td><td>c.2C&gt;G</td><td>c.8A&gt;T</td></tr>'
        for body in [row + row, row.replace('<td>27', '<td colspan="2">27'),
                     row.replace('<td>27', '<td rowspan="2">27') + '<tr><td>c.4G&gt;T</td><td>c.9T&gt;A</td></tr>']:
            with self.subTest(body=body):
                self.assertEqual({}, ownership(document(STACKED, body), '27'))

    def test_header_span_crossing_body_and_ragged_header_rejected(self):
        for head in [STACKED.replace('rowspan="2"', 'rowspan="3"'),
                     STACKED.replace('<th>2</th>', ''),
                     STACKED.replace('colspan="2"', 'colspan="3"')]:
            with self.subTest(head=head):
                self.assertEqual({}, ownership(document(head), '27'))

    def test_other_case_not_owned(self):
        s = document(STACKED, '<tr><td>28</td><td>c.3A&gt;G</td><td>c.9T&gt;A</td></tr>'
                             '<tr><td>27</td><td>c.2C&gt;G</td><td>c.8A&gt;T</td></tr>')
        scoped = ownership(s, '27')
        self.assertFalse(any(v.get('cellText') == 'c.3A>G' for v in scoped.values()))
        self.assertTrue(any(v.get('cellText') == 'c.2C>G' for v in scoped.values()))

    def test_single_header_flat_behavior_kept(self):
        self.assertEqual('REPORTED_GENOTYPE_VERIFIED', qualify(case(document(FLAT)),
                         {'geneSymbol': 'EXAMPLE', 'variantAliases': ['c.2C>G']})['status'])

    def test_explicit_header_associations_not_replaced_with_guesses(self):
        for head, body in [(FLAT.replace('<th>Mutation 1', '<th scope="row">Mutation 1'), None),
                           (FLAT, '<tr><td>27</td><td headers="unrelated">c.2C&gt;G</td><td>c.8A&gt;T</td></tr>')]:
            issues=[]
            self.assertEqual({}, ownership(document(head, body), '27', issues))
            self.assertEqual('PM3_TABLE_HEADER_ASSOCIATION_UNSUPPORTED', issues[0]['reasonCode'])

    def test_footer_not_case_data(self):
        s=source('<table-wrap><table><thead>'+FLAT+'</thead><tbody>'
                 '<tr><td>28</td><td>c.1A&gt;T</td><td>c.2C&gt;G</td></tr></tbody><tfoot>'
                 '<tr><td>27</td><td>c.2C&gt;G</td><td>c.8A&gt;T</td></tr></tfoot></table></table-wrap>')
        self.assertEqual({}, ownership(s, '27'))

    def test_heading_position_and_citation_follow_column_reordering(self):
        s=document('<tr><th colspan="2">Mutation</th><th rowspan="2">Case</th></tr>'
                   '<tr><th>2</th><th>1</th></tr>',
                   '<tr><td>c.8A&gt;T</td><td>c.2C&gt;G</td><td>27</td></tr>')
        result=qualify(case(s),{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']})
        self.assertEqual('REPORTED_GENOTYPE_VERIFIED',result['status'])
        target=result['verifiedLiteralFacts'][0]
        self.assertEqual('Mutation 1',target['columnHeading'])
        self.assertEqual('1',target['headingEvidence']['sourceHeadings'][1]['citation']['quote'])
        self.assertTrue(target['headingUnitId'].endswith('/tr[2]/th[2]'))


if __name__ == '__main__':
    unittest.main()
