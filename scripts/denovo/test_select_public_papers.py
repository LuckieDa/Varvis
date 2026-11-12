"""Offline routing and integrity checks; no scientific adoption or provider calls."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from select_public_papers import select
from run_model_extraction import sources
from test_literature_source_assertions import ARTICLE, PMCID, sha


class SelectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = self.root / 'papers' / PMCID
        source.mkdir(parents=True)
        self.paper = source / (PMCID + '.xml')
        self.paper.write_bytes(ARTICLE)
        self.entry = {'sourceClass': 'PUBLIC_PAPER', 'pmcid': PMCID, 'sha256': sha(ARTICLE),
                      'path': '../../papers/' + PMCID + '/' + PMCID + '.xml',
                      'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/' + PMCID + '/fullTextXML'}
        for criterion in ('PS2', 'PM6'):
            self.index(criterion, [self.entry])

    def index(self, criterion, documents):
        folder = self.root / 'evidence' / criterion
        folder.mkdir(parents=True, exist_ok=True)
        (folder / 'index.json').write_text(json.dumps({'schemaVersion': 'public-paper-evidence-index-v1',
              'criterion': criterion, 'documents': documents}), encoding='utf-8')

    def test_shared_paper_selected_once_without_directory_scan_and_old_worker_accepts(self):
        # An unreadable/unrelated index cannot affect selection; directory enumeration is forbidden.
        other = self.root / 'evidence' / 'PP1'
        other.mkdir(); (other / 'index.json').write_bytes(b'not json')
        inbox = self.root / 'inbox'
        inbox.mkdir(); (inbox / (PMCID + '.xml')).write_bytes(b'unregistered version')
        with patch('os.scandir', side_effect=AssertionError('must not scan library')):
            result = select(self.root, ['PS2', 'PM6'], denovo=True)
        self.assertEqual(1, len(result['documents']))
        manifest = self.root / 'selected.json'
        manifest.write_text(json.dumps(result), encoding='utf-8')
        self.assertEqual(ARTICLE, sources(manifest)[0][1])

    def test_corrupt_original_is_not_accepted(self):
        self.paper.write_bytes(ARTICLE + b'changed')
        with self.assertRaisesRegex(ValueError, 'PAPER_SHA_MISMATCH'):
            select(self.root, ['PS2'], denovo=True)

    def test_conflicting_evidence_indexes_do_not_silently_choose_one(self):
        self.index('PM6', [{**self.entry, 'sha256': '0' * 64}])
        with self.assertRaisesRegex(ValueError, 'PAPER_INDEX_CONFLICT'):
            select(self.root, ['PS2', 'PM6'], denovo=True)

    def test_path_escape_rejected(self):
        (self.root / 'outside.xml').write_bytes(ARTICLE)
        self.index('PS2', [{**self.entry, 'path': '../../outside.xml'}])
        with self.assertRaisesRegex(ValueError, 'PAPER_PATH_OUTSIDE_SOURCES'):
            select(self.root, ['PS2'])

    def test_unregistered_criterion_is_explicit_without_legacy_fallback(self):
        legacy = self.root / 'PP1'
        legacy.mkdir()
        (legacy / 'index.json').write_text(json.dumps({'schemaVersion': 'public-paper-evidence-index-v1',
              'criterion': 'PP1', 'documents': [self.entry]}), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'PAPER_CRITERION_NOT_REGISTERED'):
            select(self.root, ['PP1'])

    def test_empty_other_evidence_and_unregistered_requested_paper_are_explicit(self):
        self.index('PP1', [])
        self.assertEqual([], select(self.root, ['PP1'])['documents'])
        with self.assertRaisesRegex(ValueError, 'PAPER_NOT_IN_SELECTED_EVIDENCE'):
            select(self.root, ['PS2'], pmcids=['PMC999999'])
        self.index('PS2', [])
        with self.assertRaisesRegex(ValueError, 'PAPER_NO_REGISTERED_DOCUMENTS'):
            select(self.root, ['PS2'], denovo=True)

    def test_disabled_and_wrong_model_group_rejected(self):
        with self.assertRaisesRegex(ValueError, 'PAPER_CRITERION_INVALID_OR_DISABLED'):
            select(self.root, ['PP5'])
        with self.assertRaisesRegex(ValueError, 'DENOVO_CRITERION_NOT_SUPPORTED'):
            select(self.root, ['PP1'], denovo=True)

    def test_index_identity_and_public_source_contract_preserved(self):
        self.index('PS2', [{**self.entry, 'acquisitionUrl': 'https://example.invalid/paper'}])
        with self.assertRaisesRegex(ValueError, 'PAPER_DENOVO_SOURCE_UNSUPPORTED'):
            select(self.root, ['PS2'], denovo=True)
        path = self.root / 'evidence' / 'PS2' / 'index.json'
        value = json.loads(path.read_bytes()); value['criterion'] = 'PM6'
        path.write_text(json.dumps(value), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'PAPER_INDEX_IDENTITY_INVALID'):
            select(self.root, ['PS2'])


if __name__ == '__main__':
    unittest.main()
