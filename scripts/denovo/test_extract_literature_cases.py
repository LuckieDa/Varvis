import hashlib
import unittest
from prepare_literature import prepare, verify_citation
from extract_literature_cases import extract, table_grid


def source(table):
    return ('''<article><front><article-meta><article-id pub-id-type="pmcid">PMC999999998</article-id>
<title-group><article-title>SYNTHETIC case extraction only</article-title></title-group></article-meta></front>
<body><sec><title>Results</title>''' + table + '</sec></body></article>').encode()


def cases(table):
    raw = source(table)
    doc = prepare(raw, "PMC999999998", hashlib.sha256(raw).hexdigest())
    return extract(doc), doc, raw


COLUMN = '''<table-wrap id="table1"><table><tr><th></th><th>Case 1</th><th>Case 2</th></tr>
<tr><td>Mutated gene</td><td>EXAMPLE1</td><td>EXAMPLE2</td></tr>
<tr><td>Nucleotide</td><td>123A&gt;G</td><td>Not applicable</td></tr>
<tr><td>Parentage</td><td>Not reported</td><td>Confirmed</td></tr>
<tr><td>Father genotype</td><td>Not tested</td><td>0/0</td></tr></table></table-wrap>'''
ROW = '''<table-wrap id="table1"><table><tr><th>Case</th><th>Gene</th><th>Variant</th></tr>
<tr><td>Case 1</td><td>EXAMPLE1</td><td>NM_000001.1:c.123A&gt;G</td></tr>
<tr><td>Case 2</td><td>EXAMPLE2</td><td>NM_000002.2:c.456T&gt;A</td></tr></table></table-wrap>'''


class ExtractCasesTest(unittest.TestCase):
    def test_column_cases_have_exact_field_and_case_sources(self):
        result, doc, raw = cases(COLUMN)
        self.assertEqual(2, len(result['cases']))
        first, second = result['cases']
        self.assertEqual('NOT_ASSESSED', first['cohortRelation'])
        self.assertEqual('1', first['caseLabel'])
        self.assertEqual(['EXAMPLE1', '123A>G', 'Not reported', 'Not tested'], [f['sourceValue'] for f in first['fields']])
        self.assertEqual(['REPORTED', 'REPORTED', 'UNKNOWN', 'NOT_TESTED'], [f['findingState'] for f in first['fields']])
        self.assertEqual('NOT_APPLICABLE_OR_UNRESOLVED', second['fields'][1]['findingState'])
        for case in result['cases']:
            self.assertEqual('EXTRACTED_NOT_QUALIFIED', case['qualification'])
            self.assertEqual('NOT_ASSESSED', case['independence'])
            citations = [case['caseCitation']] + [c for f in case['fields'] for c in [f['citation'], f['fieldHeaderCitation']] if c]
            for c in citations:
                self.assertEqual('CITATION_MATCHED_SEMANTICS_NOT_VERIFIED', verify_citation(raw, doc['pmcid'], doc['sourceSha256'], c))

    def test_row_layout_and_nonfirst_case_column(self):
        for table in [ROW, ROW.replace('<th>Case</th><th>Gene</th>', '<th>Gene</th><th>Case</th>')
                      .replace('<td>Case 1</td><td>EXAMPLE1</td>', '<td>EXAMPLE1</td><td>Case 1</td>')
                      .replace('<td>Case 2</td><td>EXAMPLE2</td>', '<td>EXAMPLE2</td><td>Case 2</td>')]:
            result, unused, unused_raw = cases(table)
            self.assertEqual(2, len(result['cases']))
            self.assertEqual('NM_000002.2:c.456T>A', result['cases'][1]['fields'][1]['sourceValue'])

    def test_never_converts_de_novo_or_zero_gt_into_confirmed_negative(self):
        result, unused, unused_raw = cases(COLUMN.replace('Parentage', 'Inheritance').replace('Not reported', 'De novo'))
        self.assertEqual('reportedInheritance', result['cases'][0]['fields'][2]['field'])
        self.assertEqual('De novo', result['cases'][0]['fields'][2]['sourceValue'])
        self.assertEqual('0/0', result['cases'][1]['fields'][3]['sourceValue'])
        self.assertEqual('NONE', result['clinicalAdoption'])
        self.assertNotIn('points', str(result))

    def test_duplicate_case_label_rejects_ambiguous_table(self):
        for table in [COLUMN.replace('Case 2', 'Case 1'), ROW.replace('Case 2', 'Case 1')]:
            result, unused, unused_raw = cases(table)
            self.assertEqual([], result['cases'])
            self.assertEqual('LITERATURE_CASE_ID_AMBIGUOUS', result['issues'][0]['reasonCode'])

    def test_multiple_cohorts_with_same_label_not_silently_merged(self):
        result, unused, unused_raw = cases(COLUMN + COLUMN.replace('table1', 'table2'))
        self.assertEqual(4, len(result['cases']))
        self.assertEqual(4, len({c['observationId'] for c in result['cases']}))
        self.assertTrue(all(c['independence'] == 'NOT_ASSESSED' for c in result['cases']))

    def test_conflicting_rows_preserved(self):
        table = COLUMN.replace('</table>', '<tr><td>Gene</td><td>CONFLICT</td><td>EXAMPLE2</td></tr></table>')
        result, unused, unused_raw = cases(table)
        self.assertIn('LITERATURE_FIELD_CONFLICT:geneSymbol', result['cases'][0]['issues'])
        self.assertEqual(2, sum(f['field'] == 'geneSymbol' for f in result['cases'][0]['fields']))

    def test_spanning_value_has_same_source_pointer_for_each_case(self):
        table = COLUMN.replace('<td>EXAMPLE1</td><td>EXAMPLE2</td>', '<td colspan="2">EXAMPLE1</td>')
        result, unused, unused_raw = cases(table)
        self.assertEqual(result['cases'][0]['fields'][0]['citation'], result['cases'][1]['fields'][0]['citation'])
        self.assertNotEqual(result['cases'][0]['caseCitation'], result['cases'][1]['caseCitation'])

    def test_merged_case_header_is_ambiguous(self):
        result, unused, unused_raw = cases(COLUMN.replace('<th>Case 1</th><th>Case 2</th>', '<th colspan="2">Case 1</th>'))
        self.assertEqual([], result['cases'])
        self.assertEqual('LITERATURE_CASE_ID_AMBIGUOUS', result['issues'][0]['reasonCode'])

    def test_outside_span_and_missing_cells_do_not_shift_identity(self):
        result, unused, unused_raw = cases(COLUMN.replace('<td>EXAMPLE1</td>', '<td rowspan="100">EXAMPLE1</td>'))
        self.assertEqual([], result['cases'])
        self.assertEqual('LITERATURE_TABLE_SPAN_OUTSIDE_GRID', result['issues'][0]['reasonCode'])
        result, unused, unused_raw = cases(COLUMN.replace('<td>Not applicable</td>', ''))
        self.assertFalse(any(f['field'] == 'variantText' for f in result['cases'][1]['fields']))

    def test_no_case_table_is_not_no_public_cases(self):
        result, unused, unused_raw = cases('<p>A narrative case without table.</p>')
        self.assertEqual([], result['cases'])
        self.assertEqual('PENDING_MODEL_OR_VERIFIED_STRUCTURED_SOURCE', result['narrativeExtraction'])
        self.assertEqual(1, len(result['remainingUnits']))

    def test_prepared_cell_tampering_detected(self):
        unused, doc, unused_raw = cases(COLUMN)
        doc['tables'][0]['rows'][1]['cells'][1]['text'] = 'INVENTED'
        with self.assertRaisesRegex(ValueError, 'TABLE_UNIT_MISMATCH'): extract(doc)

    def test_source_format_version_must_match(self):
        unused, doc, unused_raw = cases(ROW)
        doc['schema'] = 'unknown'
        with self.assertRaisesRegex(ValueError, 'PREPARED_VERSION_UNSUPPORTED'): extract(doc)


if __name__ == '__main__':
    unittest.main()
