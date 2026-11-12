"""Synthetic corruption/structure controls; never source or clinical approval."""
from pathlib import Path
import tempfile
import unittest

from test_literature_acquisition import zipped, jats
from acquire_public_literature import source_bundle, acquire
from bundle_model_contract import load, cite, requests, review, RESPONSE_VERSION
from literature_attachments import parse_attachment
from model_literature_contract import digest, parse
from official_supplement_source import validate, VERSION
from paper_library_registration import encoded, register
from prepare_literature import prepare
from register_single_supplements import import_captured

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'


def docx(body, extra=()):
    return zipped([('[Content_Types].xml', '<Types><Override PartName="/word/document.xml" '
                   'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>'),
                   ('word/document.xml', f'<w:document xmlns:w="{W}"><w:body>{body}</w:body></w:document>'), *extra])


def p(text): return f'<w:p><w:r><w:t>{text}</w:t></w:r></w:p>'


def table():
    return '<w:tbl><w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc>' + p('Case') + '</w:tc><w:tc>' + p('Finding') + (
        '</w:tc></w:tr><w:tr><w:tc>' + p('Case 3') + '</w:tc><w:tc>' +
        p('confirmed de novo') + p('PM6 assumed; parentage not confirmed') + '</w:tc></w:tr></w:tbl>')


class DocxTests(unittest.TestCase):
    def test_text_exact_breaks_tabs_and_blank_paragraphs(self):
        r = parse_attachment('a.docx', docx(p('A') + p('') +
            '<w:p><w:r><w:t>B</w:t><w:cr/><w:t>C</w:t><w:tab/><w:t>D</w:t></w:r></w:p>'))
        self.assertEqual(r['status'], 'TEXT_READ_NOT_SEMANTICALLY_INTERPRETED')
        self.assertEqual([u['text'] for u in r['units']], ['A', '', 'B\nC\tD'])
        self.assertEqual(r['units'][2]['sourcePath'], '/document/body/p[3]')

    def test_table_cells_and_explicit_case_not_row_inference(self):
        r = parse_attachment('a.docx', docx(table()))
        self.assertEqual([u['cell'] for u in r['units']], ['R1C1', 'R1C2', 'R2C1', 'R2C2'])
        self.assertEqual(r['units'][-1]['text'], 'confirmed de novo\nPM6 assumed; parentage not confirmed')
        self.assertTrue(r['units'][0]['header'])

    def test_merges_preserved_without_copying_to_other_cases(self):
        r = parse_attachment('a.docx', docx('<w:tbl><w:tr><w:tc><w:tcPr>'
            '<w:gridSpan w:val="2"/><w:vMerge w:val="restart"/></w:tcPr>' + p('Case 1') +
            '</w:tc><w:tc>' + p('x') + '</w:tc></w:tr><w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr>' +
            p('') + '</w:tc></w:tr></w:tbl>'))
        self.assertEqual(r['units'][0]['gridSpan'], 2)
        self.assertEqual(r['units'][1]['cell'], 'R1C3')
        self.assertEqual(r['units'][2]['text'], '')
        self.assertEqual(r['units'][2]['verticalMerge'], 'continue')

    def test_notes_and_header_positions_distinct(self):
        r = parse_attachment('a.docx', docx(p('A'), [
            ('word/footnotes.xml', f'<w:footnotes xmlns:w="{W}"><w:footnote w:id="1">' + p('A') + '</w:footnote></w:footnotes>'),
            ('word/header1.xml', f'<w:hdr xmlns:w="{W}">' + p('A') + '</w:hdr>')]))
        self.assertEqual(len({u['location'] for u in r['units']}), 3)

    def test_unbound_content_control_text_preserved_but_bound_value_rejected(self):
        body = '<w:sdt><w:sdtPr/><w:sdtContent>' + p('Bibliography text') + '</w:sdtContent></w:sdt>'
        r = parse_attachment('a.docx', docx(body))
        self.assertEqual(r['units'][0]['text'], 'Bibliography text')
        self.assertIn('/sdt[1]/sdtContent/p[1]', r['units'][0]['sourcePath'])
        for kind in ['dataBinding', 'showingPlcHdr']:
            r = parse_attachment('a.docx', docx(body.replace('<w:sdtPr/>', f'<w:sdtPr><w:{kind}/></w:sdtPr>')))
            self.assertEqual(r['status'], 'ARCHIVED_NOT_PARSED')

    def test_visual_text_not_misreported_as_normal_prose(self):
        body = '<w:p><w:r><w:t>Main text</w:t><w:pict><w:txbxContent>' + p('Image text') + '</w:txbxContent></w:pict></w:r></w:p>'
        r = parse_attachment('a.docx', docx(body))
        self.assertEqual(r['units'][0]['text'], 'Main text')
        self.assertEqual(len(r['limitations']), 1)

    def test_revisions_fields_external_chunks_not_flattened(self):
        for fragment in ['<w:del>' + p('not') + '</w:del>', '<w:altChunk/>',
                         '<w:p><w:fldSimple/></w:p>', '<w:sdt>' + p('X') + '</w:sdt>',
                         '<w:p><w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden</w:t></w:r></w:p>']:
            with self.subTest(fragment=fragment):
                r = parse_attachment('a.docx', docx(fragment))
                self.assertEqual(r['reasonCode'], 'PAPER_ATTACHMENT_DOCX_TEXT_SEMANTICS_UNSUPPORTED')

    def test_html_not_word(self):
        self.assertEqual(parse_attachment('a.docx', b'<html>Access denied</html>')['status'], 'ARCHIVED_NOT_PARSED')

    def test_macro_nested_table_and_entity_rejected(self):
        values = [docx(p('x'), [('word/vbaProject.bin', b'x')]),
                  docx('<w:tbl><w:tr><w:tc>' + table() + '</w:tc></w:tr></w:tbl>'),
                  docx(p('x')).replace(b'<Types>', b'<!DOCTYPE Types [<!ENTITY a "b">]><Types>')]
        for raw in values:
            self.assertEqual(parse_attachment('x.docx', raw)['status'], 'ARCHIVED_NOT_PARSED')

    def test_valid_zip_with_entity_declaration_rejected(self):
        raw = zipped([('[Content_Types].xml', '<!DOCTYPE Types [<!ENTITY a "b">]><Types/>'),
                      ('word/document.xml', '<unused/>')])
        self.assertEqual(parse_attachment('a.docx', raw)['reasonCode'], 'PAPER_ATTACHMENT_XML_ENTITY_FORBIDDEN')

    def test_bad_grid_and_oversize_text_rejected(self):
        for body in [p('x' * 32769), '<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="999"/>'
                     '</w:tcPr>' + p('x') + '</w:tc></w:tr></w:tbl>']:
            self.assertEqual(parse_attachment('a.docx', docx(body))['status'], 'ARCHIVED_NOT_PARSED')


class SingleFileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.library = self.root / 'library'; self.library.mkdir()
        self.raw = jats(True).replace(b'table.csv', b'table.docx')
        self.document = prepare(self.raw, 'PMC123', digest(self.raw))
        class Transport:
            def get(self, *args): raise ValueError('PAPER_DOWNLOAD_NOT_AVAILABLE')
        files, bundle = source_bundle({'pmcid': 'PMC123'}, self.raw, self.document, Transport(), True)
        self.parent = register(self.library, 'PMC123', files, bundle, {'sha256': digest(self.raw),
            'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'})['bundleSha256']
        self.paper = self.library / 'papers/PMC123'
        self.body = docx(p('Patient 3 has finding X.') + table())
        self.name = 'supplementary/' + digest(self.body) + '.docx'
        (self.paper / 'supplementary').mkdir(exist_ok=True); (self.paper / self.name).write_bytes(self.body)
        self.url = 'https://pmc.ncbi.nlm.nih.gov/articles/instance/123/bin/table.docx'
        self.page_url = 'https://pmc.ncbi.nlm.nih.gov/articles/PMC123/'
        self.html = (f'<link rel="canonical" href="{self.page_url}"><meta name="citation_pmid" content="456">'
                     '<meta name="citation_doi" content="10.1234/test">'
                     f'<a href="{self.url}">Attachment</a>').encode()
        self.capture = {'downloads': [{'originalName': 'table.docx', 'url': self.url,
            'sha256': digest(self.body), 'bytes': len(self.body), 'path': self.name,
            'articleHtmlSha256': digest(self.html), 'method': 'CODEX_IAB_OBSERVED_LINK_DOWNLOAD_MEDIA'}]}
        self.page_record = {'url': self.page_url, 'finalUrl': self.page_url, 'status': 200,
                            'sha256': digest(self.html), 'bytes': len(self.html)}
        self.item = {**self.capture['downloads'][0], 'association': 'EXACT_JATS_FILENAME',
                     'matchedSourceUnits': [self.document['links'][0]['sourceUnitId']]}
        self.evidence = {'html': self.html, 'record': encoded(self.page_record), 'capture': encoded(self.capture)}
        self.receipt = {'schemaVersion': VERSION, 'pmcid': 'PMC123', 'mainSha256': digest(self.raw),
                        'article': {'path': 'html'}, 'articleRecord': {'path': 'record'},
                        'acquisitionRecord': {'path': 'capture'}}

    def test_exact_chain_then_import_load_plan_and_quote(self):
        self.assertFalse(validate(self.document, self.item, self.body, self.receipt,
                                 lambda r, n: self.evidence[r['path']])['scientificAdoption'])
        for key, value in self.evidence.items(): (self.root / key).write_bytes(value)
        result = import_captured(self.library, 'PMC123', self.parent, self.root / 'capture',
                                  self.root / 'html', self.root / 'record')
        source = load(self.library, 'PMC123', result['bundleSha256'])
        word = [u for u in source['units'] if u.get('sourcePart')]
        self.assertEqual(len(word), 5)
        self.assertEqual(len({u['unitId'] for u in word}), 5)
        packet = requests(source, extraction_profile='core-material-v1')
        targets = [u for v in packet for u in parse(v['messages'][1]['content'])['workUnit']['targetUnitIds']]
        self.assertEqual(targets, [u['unitId'] for u in source['units']])
        quoted = cite(source, {'unitId': word[0]['unitId'], 'quote': 'Patient 3'})
        self.assertEqual(quoted['sourcePart'], 'word/document.xml')
        self.assertEqual(quoted['sourceSha256'], digest(self.body))
        for bad in [{'unitId': word[0]['unitId'], 'quote': 'Patient 4'},
                    {'unitId': word[1]['unitId'], 'quote': 'Patient 3'}]:
            with self.assertRaises(ValueError): cite(source, bad)
        self.assertFalse(source['coverage']['completePublicationProven'])
        anchor = {'unitId': word[0]['unitId'], 'quote': 'Patient 3'}
        value = {'schemaVersion': RESPONSE_VERSION, 'cases': [{'caseLabel': '3', 'caseCitation': anchor,
            'caseOrigin': 'UNCLEAR', 'originCitations': [], 'fields': [{
                'field': 'reportedParentage', 'subject': 'BOTH_PARENTS', 'sourceValue': 'confirmed de novo',
                'citation': {'unitId': word[-1]['unitId'], 'quote': 'confirmed de novo'}}]}],
            'coverage': 'EXTRACTED_WITHIN_TARGET',
            'sourceAssessments': [{'materialKind': 'INDIVIDUAL_CASE_MATERIAL', 'citation': anchor}]}
        checked = review(source, value)
        self.assertFalse(checked['businessPublishable'])
        self.assertIn('ATTACHMENT_CASE_ATTRIBUTION_NOT_VERIFIED', checked['cases'][0]['issues'])
        # Persist exact provenance, reconstruct a fresh library, and replay an
        # empty synthetic model proposal without calling a network service.
        from test_bundle_model import configuration, response
        from run_bundle_model import run
        from bundle_candidate_intake import pack, replay_intake
        config = configuration(); config_path = self.root / 'config.json'
        config_path.write_bytes(encoded(config)); (self.root / 'key.txt').write_text('synthetic-not-secret')
        empty = {**value, 'cases': []}
        index = next(i for i, pkt in enumerate(requests(source, max_request_bytes=config['maxRequestBytes']))
                     if word[0]['unitId'] in parse(pkt['messages'][1]['content'])['workUnit']['targetUnitIds'])
        run(self.library, 'PMC123', result['bundleSha256'], config, config_path, self.root / 'budget',
            self.root / 'model', transport=lambda *args: response(empty), chunk_index=index)
        intake = pack(self.library, [self.root / 'model/artifact.json'])
        self.assertEqual(len([p for p in intake['files'] if '/provenance/' in p]), 4)
        facts = replay_intake(self.raw, 'PMC123', digest(self.raw), intake, work_root=self.root)
        self.assertEqual(facts['cases'], [])
        plan = self.root / 'discovery.json'
        plan.write_bytes(encoded({'schemaVersion': 'denovo-literature-discovery-v1',
                                  'candidates': [{'pmcid': 'PMC123', 'hasSupplement': 'Y'}]}))
        class NoNetwork:
            requests = 0
            transferred = 0
            events = []
            def get(self, *args): raise AssertionError('Must reuse captured sources')
        acquired = acquire(plan, digest(plan.read_bytes()), self.library, NoNetwork(), self.root / 'acquire')
        self.assertEqual(acquired['papers'][0]['registration']['bundleSha256'], result['bundleSha256'])
        self.assertEqual(acquired['networkRequests'], 0)
        (self.paper / self.name).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'PIN_MISMATCH'): load(self.library, 'PMC123', result['bundleSha256'])
        bad = acquire(plan, digest(plan.read_bytes()), self.library, NoNetwork(), self.root / 'bad')
        self.assertEqual(bad['papers'][0]['reasonCode'], 'DENOVO_BUNDLE_SOURCE_PIN_MISMATCH')

    def test_wrong_bindings_and_arbitrary_host_rejected(self):
        for key, value in [('url', self.url.replace('pmc.ncbi.nlm.nih.gov', 'evil.invalid')),
                           ('originalName', '../table.docx'), ('sha256', '0' * 64),
                           ('matchedSourceUnits', []), ('bytes', 1)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate(self.document, {**self.item, key: value}, self.body, self.receipt,
                         lambda r, n: self.evidence[r['path']])

    def test_page_identity_link_and_acquisition_cannot_be_replaced(self):
        for key, value in [('html', self.html.replace(b'PMC123', b'PMC456')),
                           ('html', self.html.replace(b'table.docx', b'other.docx')),
                           ('capture', encoded({'downloads': []})),
                           ('capture', encoded({'downloads': [self.capture['downloads'][0]] * 2})),
                           ('capture', encoded({'downloads': [{**self.capture['downloads'][0], 'sha256': '0' * 64}]})),
                           ('record', encoded({**self.page_record, 'status': 403}))]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate(self.document, self.item, self.body, self.receipt,
                         lambda r, n: ({**self.evidence, key: value})[r['path']])

    def test_naked_bundle_not_trusted(self):
        bundle = parse((self.paper / 'source-bundles' / (self.parent + '.json')).read_bytes())
        bundle['attachments'] = [self.item]
        result = register(self.library, 'PMC123', {'PMC123.xml': self.raw}, bundle, {'sha256': digest(self.raw)})
        with self.assertRaisesRegex(ValueError, 'NOT_IN_OFFICIAL_PACKAGE'):
            load(self.library, 'PMC123', result['bundleSha256'])


if __name__ == '__main__': unittest.main()
