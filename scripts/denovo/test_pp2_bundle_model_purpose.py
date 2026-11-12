"""PP2 technical library/model reuse; no clinical review or causal conclusion."""
import unittest
from unittest.mock import patch

import test_bundle_model as fixtures
from acquire_public_literature import acquire
from model_literature_contract import canonical, digest
from bundle_model_contract import requests, load
from bundle_replay_compatibility import reviewed_profiles
from model_literature_contract import parse
from paper_library_registration import register
from run_bundle_model import run, replay


class Pp2PurposeTest(unittest.TestCase):
    def setUp(self):
        self.data = fixtures.BundleModelTests()
        self.data.setUp()
        self.addCleanup(self.data.doCleanups)

    def chunk(self, source):
        target = self.data.empty['sourceAssessments'][0]['citation']['unitId']
        return next(i for i, payload in enumerate(requests(source, max_request_bytes=self.data.c['maxRequestBytes']))
                    if target in parse(payload['messages'][1]['content'])['workUnit']['targetUnitIds'])

    def test_pp2_only_registered_source_is_loaded_replayed_and_cached_without_ps2_registration(self):
        data = self.data
        paper = data.library / 'papers' / 'PMC123'
        bundle = parse((paper / 'source-bundles' / (data.pin + '.json')).read_bytes())
        files = {p.relative_to(paper).as_posix(): p.read_bytes() for p in paper.rglob('*')
                 if p.is_file() and p.name != 'metadata.json' and 'source-bundles' not in p.relative_to(paper).parts}
        metadata = parse((paper / 'metadata.json').read_bytes())
        result = register(data.library, 'PMC123', files, bundle, metadata, criteria=('PP2',))
        self.assertEqual('REGISTERED', result['status'])
        for criterion in ('PS2', 'PM6'):
            (data.library / 'evidence' / criterion / 'index.json').unlink()
        source = load(data.library, 'PMC123', data.pin, criterion='PP2')
        index, calls = self.chunk(source), []
        def transport(*unused):
            calls.append(1)
            return fixtures.response(data.candidate())
        for i in range(2):
            if i:
                (data.root / 'key.txt').unlink()
            actual = run(data.library, 'PMC123', data.pin, data.c, data.config,
                data.root / 'pp2-budget', data.root / ('pp2-' + str(i)), transport,
                chunk_index=index, criterion='PP2')
            self.assertEqual(1 if i == 0 else 0, actual['modelCalls'])
        artifact_path = data.root / 'pp2-0' / 'artifact.json'
        artifact = parse(artifact_path.read_bytes())
        self.assertEqual('PP2', artifact['binding']['criterion'])
        checked = replay(data.library, artifact_path, criterion='PP2')
        self.assertEqual('CANDIDATES_QUOTE_CHECKED_NOT_QUALIFIED', checked['status'])
        self.assertFalse(checked['businessPublishable'])
        self.assertEqual('NONE', checked['clinicalAdoption'])
        self.assertEqual([1], calls)
        with self.assertRaisesRegex(ValueError, 'ARTIFACT_CRITERION_MISMATCH'):
            replay(data.library, artifact_path, criterion='PS2')

    def test_wrong_bibliography_cannot_register_a_new_pp2_purpose(self):
        data = self.data
        before = {str(p.relative_to(data.library)): p.read_bytes()
                  for p in data.library.rglob('*') if p.is_file()}
        for field, value in (('pmid', '999'), ('doi', '10.9999/wrong')):
            candidate = dict(pmcid='PMC123', pmid='456', doi='10.1234/test',
                             localReviewedBundleSha256=data.pin, flags=[])
            candidate[field] = value
            raw = canonical(dict(schemaVersion='denovo-literature-discovery-v1', candidates=[candidate]))
            path = data.root / ('wrong-' + field + '.json'); path.write_bytes(raw)
            class NoTransport:
                requests = transferred = 0
                events = []
                def get(self, *unused): raise AssertionError('local source must not fetch')
            result = acquire(path, digest(raw), data.library, NoTransport(),
                             data.root / ('acquire-' + field), criteria=('PP2',))
            self.assertEqual('SKIPPED', result['papers'][0]['status'])
            self.assertIn('BIBLIOGRAPHIC_IDENTITY_MISMATCH', result['papers'][0]['reasonCode'])
            self.assertFalse((data.library / 'evidence/PP2/index.json').exists())
            self.assertEqual(before, {str(p.relative_to(data.library)): p.read_bytes()
                                     for p in data.library.rglob('*') if p.is_file()})

    def test_known_pre_keyword_default_cache_reuses_unchanged_request_and_original_receipt(self):
        data = self.data
        # Exact frozen pre-keyword method tuple; the source and transport here
        # remain synthetic. Unknown future tuples are not added dynamically.
        profile = next(p for p in reviewed_profiles() if p.get('run_bundle_model.py') ==
            'fbe3a960dff269b1b1e4bfe1492cdd4f17f587f0b52a571e48e243978af00400')
        index, calls = self.chunk(data.source), []
        def transport(*unused):
            calls.append(1)
            return fixtures.response(data.candidate())
        with patch('run_bundle_model.implementation', return_value=profile):
            run(data.library, 'PMC123', data.pin, data.c, data.config, data.root / 'old-budget',
                data.root / 'old-output', transport, chunk_index=index)
        original = parse((data.root / 'old-output' / 'artifact.json').read_bytes())
        self.assertNotIn('criterion', original['binding'])
        (data.root / 'key.txt').unlink()
        new = run(data.library, 'PMC123', data.pin, data.c, data.config, data.root / 'old-budget',
            data.root / 'new-output', lambda *unused: self.fail('legacy cache hit must not call provider'), chunk_index=index)
        current = parse((data.root / 'new-output' / 'artifact.json').read_bytes())
        self.assertEqual(0, new['modelCalls'])
        self.assertEqual(original['requestSha256'], current['requestSha256'])
        self.assertEqual(original['responseBase64'], current['responseBase64'])
        self.assertEqual(original['receipt'], current['receipt'])
        self.assertEqual([1], calls)
        self.assertEqual(original['review'], replay(data.library, data.root / 'new-output' / 'artifact.json'))


if __name__ == '__main__':
    unittest.main()
