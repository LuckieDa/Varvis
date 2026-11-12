import unittest
from prepare_literature import prepare, sha
from pm3_observations import ownership
from pm3_supplement_context import context
import test_pm3_pdf_observations as row_tests


class SupplementContext(unittest.TestCase):
    def setUp(self):
        fixture = row_tests.AnonymousRows(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        self.source = fixture.source
        raw = b'''<article xmlns:xlink="http://www.w3.org/1999/xlink"><front><article-meta>
        <article-id pub-id-type="pmcid">123</article-id><title-group><article-title>Study</article-title></title-group></article-meta></front><body>
        <sec><title>Introduction</title><p>We investigated the outcome in a large cohort of Dutch GENE1-deficient patients.</p></sec>
        <sec><title>Methods</title><p>In a study, data were collected from 30 patients who participated in a trial in three medical centres.</p></sec>
        <sec><title>Results</title><p>An overview of all 30 complete genotypes is available in Additional file <xref ref-type="supplementary-material" rid="S1">1</xref>. The table shows mutations of the GENE1 gene.</p></sec>
        <sec><title>Discussion</title><p>This study, in a large cohort of DX patients, reports outcomes.</p></sec>
        <sec><title>Abbreviations</title><p>DX: Disease example; TXT: Text.</p></sec>
        </body><back><supplementary-material id="S1"><media xlink:href="supp.pdf"/></supplementary-material></back></article>'''
        self.reset(raw)
        for u in self.source['units']:
            if u.get('pdfTextOnly'): u['originalName'] = 'supp.pdf'
        self.label = fixture.candidate()['cases'][0]['caseLabel']

    def reset(self, raw):
        doc = prepare(raw, 'PMC123', sha(raw))
        pdf = [u for u in self.source['units'] if u.get('pdfTextOnly')]
        self.source.update(raw=raw, main=doc, units=[{**u, 'sourceSha256': sha(raw)} for u in doc['units']] + pdf)

    def run_context(self):
        return context(self.source, ownership(self.source, self.label))

    def test_explicit_chain_no_phase_transcript_or_independence_invented(self):
        r = self.run_context()
        self.assertTrue(r['primarySourceVerified'])
        self.assertEqual(['geneSymbol', 'diagnosis', 'affectedStatus'], [f['field'] for f in r['facts']])
        self.assertEqual('Disease example', r['facts'][1]['sourceValue'])
        self.assertFalse(r['bindings'][0]['independenceEstablished'])
        self.assertEqual('supp.pdf', r['bindings'][0]['originalName'])

    def test_no_inheritance_from_wrong_supplement_or_partial_or_mixed_cohort(self):
        original = self.source['raw']
        for old, new in [(b'rid="S1"', b'rid="S2"'), (b'href="supp.pdf"', b'href="other.pdf"'),
                         (b'all 30 complete', b'some complete'),
                         (b'We investigated', b'Previously we investigated'),
                         (b'Dutch GENE1-deficient', b'Dutch GENE2-deficient'),
                         (b'reports outcomes.', b'reports outcomes with healthy controls.')]:
            with self.subTest(old=old):
                self.reset(original.replace(old, new))
                self.assertEqual([], self.run_context()['facts'])

    def test_ambiguous_gene_or_duplicate_xml_id_rejected(self):
        original = self.source['raw']
        for extra in [b'<p>We investigated a cohort of GENE2-deficient patients.</p>',
                      b'<p id="S1">Other item.</p>']:
            self.reset(original.replace(b'</body>', extra + b'</body>'))
            self.assertEqual([], self.run_context()['facts'])

    def test_unresolved_diagnosis_does_not_erase_valid_gene_or_make_transcript(self):
        self.reset(self.source['raw'].replace(b'DX: Disease example', b'DY: Disease example'))
        r = self.run_context()
        self.assertEqual(['geneSymbol'], [f['field'] for f in r['facts']])
        self.assertTrue(r['primarySourceVerified'])

    def test_unrelated_negative_does_not_negate_collection_but_negated_collection_does(self):
        raw = self.source['raw']
        self.reset(raw.replace(b'three medical centres.', b'three medical centres. Pregnant patients were not eligible.'))
        self.assertTrue(self.run_context()['primarySourceVerified'])
        self.reset(raw.replace(b'data were collected', b'data were not collected'))
        self.assertFalse(self.run_context()['primarySourceVerified'])


if __name__ == '__main__': unittest.main()
