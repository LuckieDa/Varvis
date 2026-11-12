"""Pinned GN027 publication context; not scoring or source-count qualification.

All original inputs are optional and must be explicitly supplied. The MAIN
importer and its project dependencies are loaded from one committed Git tree in
memory; neither an active worktree importer nor a replacement file is used.
"""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import types
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile


MAIN_COMMIT = 'd1f4365aee51cc63bbaad50c18753971d1c013c6'
IMPORTER_SHA256 = '8640dd4dcbd19486b0e81b4f5bdd4d8753bdd45bba1eb377d0dadd1126eb8035'
RAW_SHA256 = '01df51cabebe125cf9dc19bad5d02116000be49eaf96ac2307b7095603c0e289'
DOCX_SHA256 = '04cc77b47e065738e9f647ed946ae4474ed7c08dd9e9bcf326cee4cccb991d6f'
REVIEW_SHA256 = '72e172d17d6f353949737b68ad44e4c9eb0e35593d8ae6658e336db982d39122'
ENVIRONMENTS = ('VARVIS_T03_GN027_ORIGINAL', 'VARVIS_T03_GN027_BAYESIAN_DOCX',
                'VARVIS_T03_GN027_SEMANTIC_REVIEW')
W = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
STRENGTH_ROWS = [
    ['', 'Supporting', 'Moderate', 'Strong', 'Very Strong'],
    ['Pathogenic', '+1', '+2', '+4', '+8'],
    ['Benign', '-1', '-2', '-4', '-8'],
]
CLASSIFICATION_ROWS = [
    ['Total points', 'Variant Classification'],
    ['≥10', 'Pathogenic'], ['6 to 9', 'Likely Pathogenic'],
    ['-1 to 5', 'Variant of Uncertain Significance'],
    ['-2 to -6', 'Likely Benign'], ['≤-7', 'Benign'],
]
PARAGRAPHS = [
    'Bayesian Points System - CCDS VCEP',
    'Assign Bayesian points for each criteria awarded as follows:',
    'Total points should be summed across all evidence codes',
    'Determine the variant classification by the sum of the points from the evidence collected '
    'according to the following Bayesian scale:',
]


def checked_bytes(path, expected_sha):
    """Test input binding only; not a claim that compile_ba1 reads raw files."""
    raw = Path(path).read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError('TEST_ORIGINAL_SHA_MISMATCH')
    return raw


def pinned_modules(repository):
    """Execute only fixed commit blobs, restoring any active module bindings."""
    modules = {}
    for name in ('fetch_source', 'cspec_ba1_semantics', 'import_cspec_catalog'):
        path = 'scripts/acmg-generalization/' + name + '.py'
        raw = subprocess.check_output(['git', 'show', MAIN_COMMIT + ':' + path], cwd=str(repository))
        if name == 'import_cspec_catalog' and hashlib.sha256(raw).hexdigest() != IMPORTER_SHA256:
            raise ValueError('PINNED_IMPORTER_SHA_MISMATCH')
        module = types.ModuleType('_t03_pinned_' + name)
        module.__file__ = 'git:' + MAIN_COMMIT + ':' + path
        with patch.dict(sys.modules, modules):
            exec(compile(raw, module.__file__, 'exec'), module.__dict__)
        modules[name] = module
    return modules['import_cspec_catalog'], modules['cspec_ba1_semantics']


def native_document(raw):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        # No headers, footnotes, embedded images or secondary text-bearing Word
        # parts are present in this pinned artifact. Do not ignore a future one.
        secondary = [name for name in archive.namelist()
                     if re.fullmatch(r'word/(?:header\d*|footer\d*|footnotes|endnotes|comments)\.xml', name)]
        if secondary or any(name.startswith('word/media/') for name in archive.namelist()):
            raise ValueError('UNREVIEWED_DOCX_SECONDARY_CONTENT')
        return ET.fromstring(archive.read('word/document.xml'))


def paragraph_text(node):
    return ''.join(part.text or '' for part in node.findall('.//w:t', W))


def native_tables(document):
    return [[[paragraph_text(cell) for cell in row.findall('w:tc', W)]
             for row in table.findall('w:tr', W)]
            for table in document.findall('w:body/w:tbl', W)]


@unittest.skipUnless(all(os.environ.get(name) for name in ENVIRONMENTS),
                     'requires explicit pinned GN027 raw, Bayesian DOCX and semantic review paths')
class Ba1PublicationContextTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = Path(__file__).resolve().parents[2]
        cls.catalog, cls.semantics = pinned_modules(cls.repository)
        cls.raw = checked_bytes(os.environ[ENVIRONMENTS[0]], RAW_SHA256)
        cls.docx = checked_bytes(os.environ[ENVIRONMENTS[1]], DOCX_SHA256)
        cls.review_raw = checked_bytes(os.environ[ENVIRONMENTS[2]], REVIEW_SHA256)
        cls.entity = json.loads(cls.raw)['data'][3]
        cls.document = native_document(cls.docx)
        cls.review = cls.semantics.load_review(os.environ[ENVIRONMENTS[2]], REVIEW_SHA256)
        cls.source = {'url': 'https://cspec.genome.network/cspec/SequenceVariantInterpretation/id',
                      'sha256': RAW_SHA256, 'specificationId': 'GN027', 'version': '2.1',
                      'jsonPointer': '/data/3/ld/CriteriaCode/18/entContent/strengthDescriptor/0/text'}

    def compile(self, entity):
        return self.catalog.compile_ba1(entity, self.source, semantic_review=self.review)

    def test_full_original_stays_at_publication_gate_and_every_source_is_unchanged(self):
        before = copy.deepcopy((self.entity, self.source, self.review))
        result = self.compile(self.entity)
        self.assertEqual(('GN027', '2.1'), (self.entity['entId'], self.entity['entContent']['version']))
        self.assertEqual(('UNSUPPORTED', 'BA1_GLOBAL_PUBLICATION_TEXT_UNSUPPORTED'),
                         (result['disposition'], result['reasonCode']))
        self.assertEqual([self.entity['ld']['CriteriaCode'][18]], result['clauses'])
        self.assertEqual([], result['conditions'])
        self.assertEqual(before, (self.entity, self.source, self.review))
        self.assertEqual(self.raw, checked_bytes(os.environ[ENVIRONMENTS[0]], RAW_SHA256))
        self.assertEqual(self.docx, checked_bytes(os.environ[ENVIRONMENTS[1]], DOCX_SHA256))
        self.assertEqual(self.review_raw, checked_bytes(os.environ[ENVIRONMENTS[2]], REVIEW_SHA256))
        # Full-source compilation did not create a population statistic or a
        # count/ploidy/completeness qualification from the publication's points.
        for key in ('expression', 'value', 'score', 'countQualified', 'individualCountQualified'):
            self.assertNotIn(key, result)

    def test_standalone_default_point_sentinel_is_neither_inapplicability_nor_numeric_zero(self):
        criterion = self.entity['ld']['CriteriaCode'][18]['entContent']
        descriptor, = [entry for entry in criterion['strengthDescriptor'] if entry['strength'] == 'Stand Alone']
        self.assertEqual(('Applicable', 'Applicable', 'Stand Alone', 'Not Applicable'),
                         (criterion['applicability'], descriptor['applicability'],
                          descriptor['strength'], descriptor['defaultPoint']))
        self.assertIs(type(descriptor['defaultPoint']), str)
        self.assertNotEqual('DISABLED', self.compile(self.entity)['disposition'])
        for value in (0, '0', None, False, 'Not applicable', 'Not Applicable '):
            with self.subTest(value=value):
                changed = copy.deepcopy(self.entity)
                changed['ld']['CriteriaCode'][18]['entContent']['strengthDescriptor'][0]['defaultPoint'] = value
                result = self.compile(changed)
                self.assertEqual(('UNSUPPORTED', 'BA1_UNKNOWN_OPERATIONAL_FIELD'),
                                 (result['disposition'], result['reasonCode']))
                self.assertEqual('Not Applicable', descriptor['defaultPoint'])

    def test_native_attachment_tables_and_prose_do_not_define_ba1_points_or_precedence(self):
        self.assertEqual([STRENGTH_ROWS, CLASSIFICATION_ROWS], native_tables(self.document))
        self.assertEqual(PARAGRAPHS, [paragraph_text(node) for node in self.document.findall('w:body/w:p', W)
                                      if paragraph_text(node)])
        full_text = '\n'.join(part.text or '' for part in self.document.findall('.//w:t', W))
        self.assertNotRegex(full_text, r'(?i)\bBA1\b|\bstand[\s-]*alone\b|\bprecedence\b|\boverride\b')
        self.assertNotRegex(full_text, r'(?i)\bhemi\w*\b|\bploidy\b|\bgnomad\b|\bdeduplicat\w*\b|\badj\b|\bAC\b')
        self.assertEqual([1, 2, 4, 8], list(map(int, STRENGTH_ROWS[1][1:])))
        self.assertEqual([-1, -2, -4, -8], list(map(int, STRENGTH_ROWS[2][1:])))
        # The strength table has no Stand Alone column. Benign Very Strong=-8
        # supplies neither a BA1=-8 assignment nor BA1/pathogenic precedence.
        self.assertNotIn('Stand Alone', STRENGTH_ROWS[0])

    def test_api_likely_benign_comparators_conflict_with_attachment_interval_without_repair(self):
        before = copy.deepcopy(self.entity)
        rules = self.entity['ld']['RuleSet'][0]['entContent']['rules']['pointRules']['items']
        rule, = [entry for entry in rules if entry['rule'] == 'Rule4']
        self.assertEqual('Likely Benign', rule['inference'])
        self.assertEqual(['>=-6', '>=-2'], [entry['condition'] for entry in rule['conditions']])
        row, = [row for row in native_tables(self.document)[1] if row[1] == 'Likely Benign']
        self.assertEqual(['-2 to -6', 'Likely Benign'], row)
        ends = re.fullmatch(r'(-?[0-9]+) to (-?[0-9]+)', row[0])
        lower, upper = sorted(map(int, ends.groups()))
        self.assertEqual((-6, -2), (lower, upper))
        # Independent integer counterexamples, not a product classifier. Both
        # original representations remain intact; no source operator is edited.
        self.assertTrue(lower <= -6 <= upper)
        self.assertFalse(-6 >= -6 and -6 >= -2)
        self.assertFalse(lower <= -1 <= upper)
        self.assertTrue(-1 >= -6 and -1 >= -2)
        self.assertEqual(before, self.entity)
        self.assertEqual(self.raw, checked_bytes(os.environ[ENVIRONMENTS[0]], RAW_SHA256))

    def test_wrong_source_sha_and_tampered_review_are_rejected_before_use(self):
        for environment in ENVIRONMENTS:
            with self.subTest(source=environment), self.assertRaisesRegex(ValueError, '^TEST_ORIGINAL_SHA_MISMATCH$'):
                checked_bytes(os.environ[environment], '0' * 64)
        # Actual pinned production loader, not a fake source-approval boolean.
        with self.assertRaisesRegex(ValueError, '^SEMANTIC_REVIEW_HASH_MISMATCH$'):
            self.semantics.load_review(os.environ[ENVIRONMENTS[2]], '0' * 64)


if __name__ == '__main__':
    unittest.main()
