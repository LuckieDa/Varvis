"""Source fidelity and cache tests; these do not stand in for browser UI QA."""
import base64
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile
from unittest.mock import patch
import xml.etree.ElementTree as ET

import build_literature_source_screenshots as source


XML = '''<article><front><article-meta><title-group><article-title>Saved &amp; original</article-title></title-group></article-meta></front>
<body><sec><title>Results</title><p>The variant was homozygous.</p>
<table-wrap id="T1"><label>TABLE 1</label><caption><p>Original results.</p></caption><table><thead><tr><td>Member</td><td>Result</td></tr></thead><tbody><tr><td>P1</td><td>Homozygous</td></tr></tbody></table></table-wrap>
<table-wrap id="T2"><label>TABLE 2</label><caption><p>Second results table.</p></caption><table><tbody><tr><td>Case</td></tr></tbody></table></table-wrap>
<fig id="F1"><label>FIGURE 1</label><caption><p>Original pedigree.</p></caption><graphic xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="figure.png"/></fig>
</sec></body></article>'''
PARAGRAPH = '/article[1]/body[1]/sec[1]/p[1]'
CELL = '/article[1]/body[1]/sec[1]/table-wrap[1]/table[1]/tbody[1]/tr[1]/td[2]'


class SourceScreenshotTest(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[2] / 'local/temp/literature-source-tests'
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=root)
        self.root = Path(self.temp.name).resolve()
        self.assertTrue(self.root.is_relative_to(root.resolve()))
        self.library, self.output = self.root / 'library', self.root / 'screenshots'
        self.xml = self.library / 'papers/PMC101/PMC101.xml'
        self.xml.parent.mkdir(parents=True)
        self.xml.write_text(XML, encoding='utf-8')
        self.doc = {'pmcid': 'PMC101', 'cases': [{'rows': [{'sourceRefs': [
            {'unitId': PARAGRAPH, 'quote': 'The variant was homozygous.'},
            {'unitId': CELL, 'quote': 'Homozygous'},
        ]}]}]}

    def tearDown(self):
        self.temp.cleanup()

    def test_exact_source_locations_and_table_ancestors_are_preserved(self):
        root = ET.fromstring(XML)
        by_path, paths = source.index_elements(root)
        self.assertEqual('Homozygous', source.element_text(by_path[CELL]))
        self.assertEqual('/article[1]/body[1]/sec[1]/table-wrap[1]', source.region_for(CELL, by_path))
        html = source.render_full_text(root, paths, {}, 'PMC101')
        self.assertIn('The variant was homozygous.', html)
        self.assertIn('id="' + source.anchor(CELL) + '"', html)
        self.assertIn('<table', html)
        self.assertIn('本地全文视图', html)
        with self.assertRaises(ValueError):
            source.region_for('/article[1]/missing[1]', by_path)

    def test_source_content_cannot_add_scripts_or_active_attributes(self):
        root = ET.fromstring(XML.replace('The variant was homozygous.', '&lt;script&gt;bad()&lt;/script&gt;<ext-link xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="javascript:bad()" onclick="bad()">Text</ext-link>'))
        _, paths = source.index_elements(root)
        html = source.render_full_text(root, paths, {}, 'PMC101')
        self.assertNotIn('<script>', html)
        self.assertNotIn('href="javascript:', html)
        self.assertNotIn('onclick=', html)
        self.assertIn('&lt;script&gt;', html)

    def test_workbench_prepares_only_cited_regions(self):
        self.doc['includeGallery'] = False
        _, manifest, jobs = source.plan_document(self.doc, self.library, self.output)
        self.assertEqual(2, len(jobs))
        self.assertEqual([], self.doc['sourceGallery'])
        self.assertEqual(2, len(manifest['annotations']))
        kinds = {citation['sourceKind'] for job in jobs
                 for citation in job['annotation']['citations']}
        self.assertEqual({'p', 'td'}, kinds)

    def test_bad_quote_does_not_hide_valid_body_and_registered_supplement_table(self):
        import io
        package = io.BytesIO()
        with ZipFile(package, 'w') as file:
            file.writestr('[Content_Types].xml', '<Types><Override PartName="/word/document.xml" '
                'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
            file.writestr('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                '<w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Parent result</w:t></w:r></w:p></w:tc>'
                '<w:tc><w:p><w:r><w:t>Not detected</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>')
        raw = package.getvalue()
        pin = source.digest(raw)
        directory = self.xml.parent / 'supplementary'
        directory.mkdir()
        (directory / (pin + '.docx')).write_bytes(raw)
        locator = f'attachment:{pin}:word/document.xml:/document/body/tbl[1]/tr[1]/tc[2]'
        self.doc.update(includeGallery=False, skipUnavailableReferences=True)
        self.doc['cases'][0]['rows'][0]['sourceRefs'][:0] = [
            {'unitId': locator, 'quote': 'Not detected'},
            {'unitId': PARAGRAPH, 'quote': 'A quote not in the original'},
        ]
        manifest_path, manifest, jobs = source.plan_document(self.doc, self.library, self.output)
        self.assertEqual([pin], manifest['attachmentSha256s'])
        self.assertEqual(3, len(jobs))
        html = (manifest_path.parent / 'fulltext.html').read_text(encoding='utf-8')
        self.assertIn('Parent result', html)
        self.assertIn(f'id="{source.anchor(locator)}"', html)
        self.assertEqual('td', next(c for job in jobs for c in job['annotation']['citations'] if c['unitId'] == locator)['sourceKind'])
        self.assertNotIn('A quote not in the original', json.dumps(manifest))

    def test_floating_regions_and_list_inside_a_cited_paragraph_keep_rendered_ids(self):
        root = ET.fromstring('<article><body><p>Lead<list><list-item><p>Details</p></list-item></list></p></body>'
                             '<floats-group><fig><caption><p>Pedigree</p></caption></fig></floats-group></article>')
        _, paths = source.index_elements(root)
        html = source.render_full_text(root, paths, {}, 'PMC101')
        self.assertIn(f'<div id="{source.anchor("/article[1]/body[1]/p[1]")}" class="source-paragraph">Lead<ul', html)
        self.assertIn(source.anchor('/article[1]/floats-group[1]/fig[1]/caption[1]'), html)

    def test_unchanged_content_reuses_png_and_changed_source_gets_a_new_folder(self):
        before = self.xml.read_bytes()
        manifest_path, manifest, jobs = source.plan_document(self.doc, self.library, self.output)
        self.assertEqual(4, len(jobs))  # one grouped annotation image per source region
        self.assertEqual(['表格 · TABLE 1', '表格 · TABLE 2', '家系图 · FIGURE 1'],
                         [item['label'] for item in self.doc['sourceGallery']])
        annotation_jobs = [job for job in jobs if job.get('annotation')]
        self.assertEqual({'p', 'td', 'table-wrap', 'fig'},
                         {citation['sourceKind'] for job in annotation_jobs
                          for citation in job['annotation']['citations']})
        table_job = next(job for job in annotation_jobs
                         if any(citation['sourceKind'] == 'table-wrap'
                                for citation in job['annotation']['citations']))
        self.assertEqual(2, len(table_job['annotation']['citations']))
        self.assertTrue(self.doc['sourceScreenshots'][PARAGRAPH][0]['fullTextUrl'].endswith(
            '#' + source.anchor(PARAGRAPH)))
        self.assertNotIn('originalImageUrl', self.doc['sourceScreenshots'][PARAGRAPH][0])
        self.assertFalse(any(key.startswith('original') for key in manifest['regions']))
        # A valid 1x1 PNG is sufficient for checking the file identity contract.
        png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j66kAAAAASUVORK5CYII=')
        for annotation in manifest['annotations'].values():
            Path(annotation['path']).write_bytes(png)
            annotation['sha256'] = source.digest(png)
            annotation['available'] = True
        manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
        _, repeated, jobs = source.plan_document(self.doc, self.library, self.output)
        self.assertEqual([], jobs)
        self.assertTrue(all(region['reused'] for region in repeated['annotations'].values()))
        self.assertEqual(before, self.xml.read_bytes())
        self.xml.write_text(XML.replace('</body>', '<p>Additional original paragraph.</p></body>'), encoding='utf-8')
        changed_path, _, changed_jobs = source.plan_document(self.doc, self.library, self.output)
        self.assertNotEqual(manifest_path, changed_path)
        self.assertTrue(changed_jobs)

    def test_requests_for_other_regions_preserve_published_images(self):
        paragraph, cell = self.doc['cases'][0]['rows'][0]['sourceRefs']
        png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j66kAAAAASUVORK5CYII=')

        def capture(args, **kwargs):
            jobs = json.loads(Path(args[-1]).read_text(encoding='utf-8'))['jobs']
            for job in jobs:
                Path(job['outputPath']).write_bytes(png)
            return source.subprocess.CompletedProcess(args, 0, stdout='\n'.join(
                json.dumps({'annotationKey': job['annotationKey']}) for job in jobs))

        def build(ref):
            return source.build({'documents': [{'pmcid': 'PMC101', 'includeGallery': False,
                'cases': [{'rows': [{'sourceRefs': [ref]}]}]}]}, self.library, self.output, 'browser', 'node')

        with patch.object(source.subprocess, 'run', side_effect=capture) as worker:
            self.assertEqual(1, build(paragraph)['generated'])
            manifest_path = next(self.output.glob('PMC101/*/manifest.json'))
            first = json.loads(manifest_path.read_text(encoding='utf-8'))
            self.assertEqual(1, build(cell)['generated'])
            combined = json.loads(manifest_path.read_text(encoding='utf-8'))
            self.assertEqual(2, len(combined['annotations']))
            for key, annotation in first['annotations'].items():
                self.assertEqual(annotation, combined['annotations'][key])
            self.assertEqual(1, build(paragraph)['reused'])
            self.assertEqual(2, worker.call_count)

    def test_mismatched_quote_is_rejected_and_changed_png_is_not_reused(self):
        self.doc['cases'][0]['rows'][0]['sourceRefs'][0]['quote'] = 'Different original text.'
        with self.assertRaises(ValueError):
            source.plan_document(self.doc, self.library, self.output)


if __name__ == '__main__':
    unittest.main()
