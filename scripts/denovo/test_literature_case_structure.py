"""Synthetic layout/adversarial controls, not real-case scientific validation."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from prepare_literature import prepare, sha
from literature_case_structure import tables, footnotes, paragraph_owned, primary_origin, structural_assertions
from qualify_literature_cases import source_case
from prepare_literature_prompt import validate_candidates
from model_literature_contract import canonical, parse
from bundle_model_contract import requests
from bundle_replay_compatibility import PRE_STRUCTURE
from run_bundle_model import run, replay, requalify_saved, identity
from test_bundle_qualification import registered, article, candidate
from test_bundle_model import configuration, response

TABLE = b'''<table-wrap id="T"><caption>EXAMPLE variants identified in cohort</caption><table>
<thead><tr><th colspan="2"></th><th colspan="2">Transcript NM_123.1 (3 exons)</th></tr>
<tr><th>Patient (method)</th><th>De novo variants (Parental testing)</th>
<th>CDS change</th><th>Protein change</th></tr></thead><tbody>
<tr><td>3 (NGS panel)</td><td>Assumed de novo<xref ref-type="table-fn" rid="F">*</xref> (Sanger sequencing)</td><td>c.123A&gt;G</td><td>p.T41A</td></tr>
<tr><td>7 (WES)</td><td>Unknown</td><td>c.124A&gt;G</td><td>p.T42A</td></tr>
</tbody></table><table-wrap-foot><fn id="F"><p>Assumed de novo as paternity and maternity were not confirmed.</p></fn>
</table-wrap-foot></table-wrap>'''


def document(body=b'', table=TABLE):
    raw = b'''<article><front><article-meta><article-id pub-id-type="pmc">123</article-id>
<article-id pub-id-type="pmid">456</article-id><title-group><article-title>SYNTHETIC</article-title>
</title-group></article-meta></front><body>''' + body + b'</body><floats-group>' + table + b'</floats-group></article>'
    return raw, prepare(raw, 'PMC123', sha(raw))


class StructureTests(unittest.TestCase):
    def table_facts(self, table=TABLE, label='3'):
        raw, d = document(table=table)
        native = tables(d)
        case = next(c for c in native if c['caseLabel'] == label)
        result = source_case(d, native, case, [])
        structural_assertions({'raw': raw, 'main': d}, case, native, result)
        return result

    def test_numeric_rows_multiheader_and_transcript_scope(self):
        raw, d = document()
        rows = tables(d)
        self.assertEqual(['3', '7'], [c['caseLabel'] for c in rows])
        fields = {f['field']: f['sourceValue'] for f in rows[0]['fields']}
        self.assertEqual('NM_123.1', fields['transcript'])
        self.assertEqual('EXAMPLE', fields['geneSymbol'])
        self.assertEqual('c.123A>G', fields['variantText'])
        self.assertEqual('NGS panel', fields['reportedVariantTestingMethod'])

    def test_footnote_applies_only_to_linked_row(self):
        raw, d = document()
        rows = tables(d)
        self.assertEqual(1, len(footnotes(raw, d, rows[0])['citations']))
        self.assertEqual([], footnotes(raw, d, rows[1])['citations'])

    def test_unconfirmed_not_misrepresented_as_confirmed_or_not_tested(self):
        result = self.table_facts()
        self.assertEqual('PUBLISHED_PARENTAGE_NOT_CONFIRMED', result['parentage']['status'])
        self.assertEqual('UNKNOWN', result['targetTesting']['status'])
        self.assertIn('LITERATURE_PARENTAL_TARGET_RESULTS_NOT_VERIFIED', result['issues'])
        self.assertEqual('UNKNOWN', self.table_facts(label='7')['parentage']['status'])

    def test_contradictory_linked_parentage_footnotes_block(self):
        table = TABLE.replace(b'rid="F"', b'rid="F H"').replace(
            b'</table-wrap-foot>', b'<fn id="H"><p>Both maternity and paternity were confirmed.</p></fn></table-wrap-foot>')
        result = self.table_facts(table)
        self.assertEqual('UNKNOWN', result['parentage']['status'])
        self.assertIn('LITERATURE_PARENTAGE_CONFLICT_OR_UNSUPPORTED_FOOTNOTE', result['issues'])

    def test_footnote_raw_pin_is_rechecked(self):
        raw, d = document()
        with self.assertRaisesRegex(ValueError, 'SOURCE_SHA_MISMATCH'):
            footnotes(raw + b' ', d, tables(d)[0])

    def test_bare_paragraph_can_reuse_explicit_assay_rules(self):
        body = (b'<p>Case A is a boy who presented with ataxia. '
                b'The EXAMPLE variant c.123A&gt;G, p.T41A, was observed. '
                b'Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents. '
                b'SNP analysis confirmed both maternity and paternity.</p>')
        raw, d = document(body, b'')
        unit = d['units'][0]
        case = {'observationId': 'synthetic', 'caseLabel': 'A', 'caseCitation': {},
                'issues': [], 'fields': [{'field': f, 'sourceValue': v, 'findingState': 'REPORTED'}
                    for f, v in [('geneSymbol', 'EXAMPLE'), ('variantText', 'c.123A>G'), ('proteinChange', 'p.T41A')]]}
        result = source_case(d, [case], case, [])
        structural_assertions({'raw': raw, 'main': d}, case, [], result)
        self.assertEqual('PUBLISHED_TRIO_SANGER_DENOVO', result['targetTesting']['status'])
        self.assertEqual('PUBLISHED_BOTH_PARENTS_CONFIRMED', result['parentage']['status'])
        self.assertNotIn('LITERATURE_PRIMARY_CASE_SECTION_UNRESOLVED', result['issues'])
        self.assertIn('LITERATURE_ALLELE_ALIAS_NOT_FOUND', result['issues'])
        # Full identity qualification is still needed despite supported prose.
        self.assertEqual('UNRESOLVED', result['alleleIdentity']['status'])

    def test_duplicate_or_missing_footnote_never_borrowed(self):
        for table in (TABLE.replace(b'<fn id="F"', b'<fn id="G"'),
                      TABLE.replace(b'</table-wrap-foot>', b'<fn id="F"><p>Confirmed.</p></fn></table-wrap-foot>')):
            raw, d = document(table=table)
            result = footnotes(raw, d, tables(d)[0])
            self.assertTrue(result['issues'])
            self.assertFalse(result['citations'])

    def test_cross_table_footnote_rejected(self):
        first = TABLE.replace(b'<fn id="F">', b'<fn id="G">')
        extra = b'<table-wrap id="X"><table-wrap-foot><fn id="F"><p>Confirmed.</p></fn></table-wrap-foot></table-wrap>'
        raw, d = document(table=first + extra)
        self.assertTrue(footnotes(raw, d, tables(d)[0])['issues'])

    def test_duplicate_patient_rows_not_resolved(self):
        raw, d = document(table=TABLE.replace(b'7 (WES)', b'3 (WES)'))
        self.assertEqual([], tables(d))

    def test_unlabelled_numeric_column_not_patient_identity(self):
        raw, d = document(table=TABLE.replace(b'Patient (method)', b'Row (method)'))
        self.assertEqual([], tables(d))

    def test_transcript_header_must_cover_coding_column(self):
        wrong = TABLE.replace(b'<th colspan="2"></th><th colspan="2">Transcript NM_123.1 (3 exons)</th>',
                              b'<th colspan="2">Transcript NM_123.1 (3 exons)</th><th colspan="2"></th>')
        raw, d = document(table=wrong)
        self.assertFalse(any(f['field'] == 'transcript' for f in tables(d)[0]['fields']))

    def test_paragraph_case_scope_no_neighbour_or_multiple_labels(self):
        raw, d = document(b'<p>Case 3 is a boy. WES revealed a variant.</p><p>His mother was examined.</p>', b'')
        self.assertTrue(paragraph_owned(d, d['units'][0], '3'))
        self.assertFalse(paragraph_owned(d, d['units'][1], '3'))
        for body in (b'<p>Case 3 is a boy. Patient 7 is his mother.</p>',
                     b'<p>Case 3 is a boy.</p><p>Case 3 is a girl.</p>'):
            raw, d = document(body, b'')
            self.assertFalse(paragraph_owned(d, d['units'][0], '3'))

    def test_case_paragraph_in_discussion_not_primary(self):
        raw, d = document(b'<sec><title>Discussion</title><p>Case 3 is a boy. WES revealed a variant.</p></sec>', b'')
        self.assertFalse(paragraph_owned(d, d['units'][0], '3'))

    def test_primary_observation_not_equal_to_new_recruitment_or_independence(self):
        for sentence, expected in [
            ('Case 3 is a boy who presented with ataxia. WES revealed a variant.', True),
            ('Case 3 is a boy previously reported elsewhere who presented with ataxia. WES revealed a variant.', False),
            ('Case 3 is a boy. We report a variant.', False)]:
            raw, d = document(('<p>' + sentence + '</p>').encode(), b'')
            u = d['units'][0]
            self.assertEqual(expected, primary_origin(d, {'caseLabel': '3', 'caseOriginProposal': 'AUTHOR_COHORT',
                'originCitations': [{'unitId': u['unitId'], 'quote': sentence}]}))

    def test_retrospective_literature_aggregation_is_not_primary(self):
        for text, expected in [
            ('we performed a retrospective review of eight unrelated patients referred to our clinic.', True),
            ('we performed a retrospective review of published patients referred to clinics.', False),
            ('we performed a retrospective review of eight patients from the literature.', False)]:
            raw, d = document(('<sec><title>Methods</title><p>' + text + '</p></sec>').encode(), b'')
            u = d['units'][0]
            self.assertEqual(expected, primary_origin(d, {'caseLabel': '3', 'caseOriginProposal': 'AUTHOR_COHORT',
                'originCitations': [{'unitId': u['unitId'], 'quote': text}]}))

    def test_wrong_case_table_quote_is_rejected(self):
        raw, d = document()
        rows = tables(d)
        f = next(f for f in rows[1]['fields'] if f['field'] == 'variantText')
        proposed = {'schemaVersion': 'denovo-literature-screening-candidates-v2', 'cases': [{
            'caseLabel': '3', 'caseOrigin': 'UNCLEAR', 'originCitations': [],
            'caseCitation': {k: rows[0]['caseCitation'][k] for k in ('unitId', 'quote')},
            'fields': [{'field': 'variantText', 'subject': 'PROBAND', 'sourceValue': f['sourceValue'],
                        'citation': {k: f['citation'][k] for k in ('unitId', 'quote')}}]}]}
        with self.assertRaisesRegex(ValueError, 'OTHER_CASE_TABLE_CELL'):
            validate_candidates(raw, 'PMC123', sha(raw), proposed)


class SavedResponseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.library, self.source = registered(self.root, article())
        self.c = configuration()
        self.config = self.root / 'config.json'
        self.config.write_bytes(canonical(self.c))
        (self.root / 'key.txt').write_text('synthetic-not-secret')
        self.proposed = candidate(self.source)
        self.target = self.proposed['cases'][0]['fields'][0]['citation']['unitId']
        self.index = next(i for i, p in enumerate(requests(self.source)) if self.target in
                          parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])
        binding = identity(self.source['pmcid'], self.source['bundleSha256'], self.index, len(requests(self.source)))
        binding['implementation'] = PRE_STRUCTURE
        with patch('run_bundle_model.identity', return_value=binding):
            run(self.library, self.source['pmcid'], self.source['bundleSha256'], self.c, self.config,
                self.root / 'cache', self.root / 'old', lambda *a: response(self.proposed), chunk_index=self.index)
        self.provider = next((self.root / 'cache/responses').iterdir())

    def recheck(self, name='new'):
        receipt = self.provider / 'receipt.json'
        return requalify_saved(self.library, self.provider / 'request.json', self.provider / 'response.json',
                              receipt, sha(receipt.read_bytes()), self.root / name)

    def test_old_artifact_replays_without_changing_original_receipt(self):
        old = (self.root / 'old/artifact.json').read_bytes()
        self.assertEqual('CANDIDATES_QUOTE_CHECKED_NOT_QUALIFIED', replay(self.library, self.root / 'old/artifact.json')['status'])
        self.recheck()
        new = parse((self.root / 'new/artifact.json').read_bytes())
        self.assertEqual(parse(old)['receipt'], new['receipt'])
        self.assertEqual(PRE_STRUCTURE, new['binding']['implementation'])
        self.assertEqual(0, new['offlineRequalification']['modelCalls'])

    def test_cache_only_fallback_does_not_call_provider(self):
        def forbidden(*args):
            self.fail('new model transport was called')
        result = run(self.library, self.source['pmcid'], self.source['bundleSha256'], self.c, self.config,
                     self.root / 'cache', self.root / 'cached', forbidden, chunk_index=self.index, cache_only=True)
        self.assertEqual(0, result['modelCalls'])
        self.assertTrue(result['cacheHit'])
        replay(self.library, self.root / 'cached/artifact.json')

    def test_unknown_implementation_rejected(self):
        receipt = parse((self.provider / 'receipt.json').read_bytes())
        receipt['identity']['binding']['implementation']['prepare_literature.py'] = '0' * 64
        (self.provider / 'receipt.json').write_bytes(canonical(receipt))
        with self.assertRaisesRegex(ValueError, 'IMPLEMENTATION_CHANGED'):
            self.recheck()

    def test_modified_request_rejected_even_if_receipt_pin_known(self):
        path = self.provider / 'request.json'
        value = parse(path.read_bytes())
        value['messages'][0]['content'] += 'modified'
        path.write_bytes(canonical(value))
        with self.assertRaisesRegex(ValueError, 'ORIGINAL_REQUEST_CHANGED'):
            self.recheck()

    def test_modified_response_and_untrusted_receipt_rejected(self):
        path = self.provider / 'response.json'
        path.write_bytes(path.read_bytes() + b' ')
        with self.assertRaisesRegex(ValueError, 'RESPONSE_PIN_MISMATCH'):
            self.recheck()
        with self.assertRaisesRegex(ValueError, 'ORIGINAL_RECEIPT_PIN_MISMATCH'):
            requalify_saved(self.library, self.provider / 'request.json', path,
                           self.provider / 'receipt.json', '0' * 64, self.root / 'untrusted')

    def test_changed_requalification_review_not_accepted(self):
        self.recheck()
        path = self.root / 'new/artifact.json'
        value = parse(path.read_bytes())
        value['review']['cases'][0]['caseLabel'] = 'OTHER'
        path.write_bytes(canonical(value))
        with self.assertRaisesRegex(ValueError, 'REVIEW_CHANGED'):
            replay(self.library, path)


if __name__ == '__main__':
    unittest.main()
