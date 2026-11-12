"""Synthetic provenance/refusal fixtures plus the unchanged OA contract."""
import json
import unittest

from author_manuscript_source import derive
from acquire_public_literature import acquire, verified_metadata
from discover_public_literature import paper
from paper_download_transport import url_for
from prepare_literature import sha
from test_literature_acquisition import FileTest, jats


def manuscript():
    article = jats().replace(b'<title-group>', (
        b'<article-id pub-id-type="manuscript-id">NIHMS123</article-id>'
        b'<article-id pub-id-type="manuscript-id-alternative">NIHMS123</article-id>'
        b'<article-id pub-id-type="manuscript-id-alternative">NIHPA123</article-id>'
        b'<permissions><license><license-p>This file is available for text mining. '
        b'It may also be used consistent with fair use.</license-p></license></permissions><title-group>'))
    return b'<pmc-articleset>' + article + b'</pmc-articleset>'


class ManuscriptTests(FileTest):
    def candidate(self, flags=True):
        row = {'source': 'MED', 'id': '456', 'pmcid': 'PMC123', 'doi': '10.1234/test',
               'title': 'Test source', 'isOpenAccess': 'N', 'hasSuppl': 'N',
               'authMan': 'Y' if flags else 'N', 'nihAuthMan': 'Y', 'manuscriptId': 'NIHMS123'}
        raw = json.dumps({'hitCount': 1, 'resultList': {'result': [row]}}).encode()
        (self.cache / (sha(raw) + '.body')).write_bytes(raw)
        result = paper(row)
        result['origins'] = [{'kind': 'SEARCH_RESPONSE', 'sha256': sha(raw)}]
        return result

    def test_derivative_retains_clinical_text_aliases_and_raw_pin(self):
        raw = manuscript()
        derived, doc, receipt = derive(raw, 'PMC123', 'NIHMS123')
        self.assertEqual(doc['identifiers']['manuscript-id-alternative'], 'NIHMS123; NIHPA123')
        self.assertEqual(receipt['rawSha256'], sha(raw))
        self.assertEqual(receipt['derivedSha256'], sha(derived))
        self.assertEqual([u['text'] for u in doc['units'] if u['kind'] == 'BODY_PARAGRAPH'], ['A public source.'])

    def test_refuses_absent_permission_wrong_id_body_and_ambiguous_article(self):
        raw = manuscript()
        changes = [
            (raw.replace(b'available for text mining.', b'not available for text mining.'), 'PERMISSION'),
            (raw.replace(b'NIHMS123', b'NIHMS999'), 'IDENTITY'),
            (raw.replace(b'<body><p>A public source.</p></body>', b''), 'BODY_REQUIRED'),
            (raw.replace(b'</pmc-articleset>', jats() + b'</pmc-articleset>'), 'SINGLE_ARTICLE'),
            (raw.replace(b'<title-group>', b'<article-id pub-id-type="pmid">999</article-id><title-group>'), 'IDENTIFIER_CONFLICT'),
        ]
        for changed, code in changes:
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, code):
                derive(changed, 'PMC123', 'NIHMS123')

    def test_candidate_flag_cannot_override_original_metadata(self):
        candidate = self.candidate(False)
        candidate.update(authorManuscript=True, openAccess='Y')
        with self.assertRaisesRegex(ValueError, 'OPEN_ACCESS_NOT_CONFIRMED'):
            verified_metadata(candidate, self.cache, with_route=True)

    def test_full_acquisition_preserves_original_and_official_url(self):
        path, pin = self.plan([self.candidate()])
        transport = self.fake()
        def get(pmcid, kind):
            self.assertEqual(kind, 'authorManuscriptXML')
            return manuscript()
        transport.get = get
        report = acquire(path, pin, self.library, transport, self.root / 'output')
        item = report['papers'][0]
        self.assertEqual(item['status'], 'REGISTERED', report)
        self.assertEqual(item['sourceKind'], 'OFFICIAL_AUTHOR_MANUSCRIPT_RESPONSE')
        base = self.library / 'papers/PMC123'
        bundle = json.loads((self.library / item['registration']['bundlePath']).read_bytes())
        self.assertEqual((base / bundle['authorManuscript']['rawPath']).read_bytes(), manuscript())
        meta = json.loads((base / 'metadata.json').read_bytes())
        self.assertEqual(meta['acquisitionUrl'], url_for('PMC123', 'authorManuscriptXML'))
        self.assertFalse(meta.get('openAccess', False))
        again = acquire(path, pin, self.library, transport, self.root / 'again')
        self.assertEqual(again['papers'][0]['registration']['bundleSha256'], item['registration']['bundleSha256'])
        from select_public_papers import select
        self.assertEqual(len(select(self.library, ['PS2', 'PM6'], ['PMC123'], True)['documents']), 1)
        # The derivative alone is not a substitute for the retrieved original.
        (base / bundle['authorManuscript']['rawPath']).write_bytes(manuscript().replace(b'A public source.', b'Changed source.'))
        with self.assertRaisesRegex(ValueError, 'LOCAL_MANUSCRIPT_BINDING_MISMATCH'):
            select(self.library, ['PS2'], ['PMC123'], True)

    def test_bad_supplement_retained_without_blocking_readable_main(self):
        from acquire_public_literature import source_bundle
        from prepare_literature import prepare
        from paper_library_registration import register
        from bundle_model_contract import load
        candidate = self.candidate()
        raw, doc, unused = derive(manuscript(), 'PMC123', 'NIHMS123')
        files, bundle = source_bundle(candidate, raw, doc, self.fake(supplement=b'<error>No OA files</error>'), True)
        self.assertNotIn('package', bundle)
        self.assertIn('unparsedPackage', bundle)
        self.assertEqual(bundle['supplementCoverage']['status'], 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED')
        reg = register(self.library, 'PMC123', files, bundle, {'sha256': sha(raw), 'acquisitionUrl': 'synthetic'})
        self.assertEqual(load(self.library, 'PMC123', reg['bundleSha256'])['main']['title'], 'Test source')

    def test_pp1_author_manuscript_reuse_retains_original_binding(self):
        path,pin=self.plan([self.candidate()]);transport=self.fake()
        transport.get=lambda pmcid,kind:manuscript()
        first=acquire(path,pin,self.library,transport,self.root/'pp1',criteria=('PP1',))
        item=first['papers'][0];self.assertEqual('REGISTERED',item['status'])
        def no_network(*args):raise AssertionError('local manuscript should be reused')
        transport.get=no_network
        second=acquire(path,pin,self.library,transport,self.root/'pp1-reused',criteria=('PP1',))
        self.assertEqual('REGISTERED',second['papers'][0]['status'])
        self.assertEqual(item['registration']['bundleSha256'],second['papers'][0]['registration']['bundleSha256'])

    def test_model_loader_reproduces_old_derivation_and_requires_original(self):
        from acquire_public_literature import source_bundle
        from paper_library_registration import register
        from bundle_model_contract import load
        original = manuscript()
        raw, doc, receipt = derive(original, 'PMC123', 'NIHMS123')
        files, bundle = source_bundle(self.candidate(), raw, doc, None, False)
        original_path = 'originals/' + sha(original) + '.xml'
        # Historical script identity may differ; byte-reproducible derivation
        # and all semantic receipt facts must still match.
        receipt.update(implementationSha256='1' * 64, rawPath=original_path)
        bundle['authorManuscript'] = receipt
        files[original_path] = original
        reg = register(self.library, 'PMC123', files, bundle,
                       {'sha256': sha(raw), 'acquisitionUrl': 'synthetic'})
        self.assertEqual(raw, load(self.library, 'PMC123', reg['bundleSha256'])['raw'])
        (self.library / 'papers/PMC123' / original_path).write_bytes(original + b' ')
        with self.assertRaisesRegex(ValueError, 'SOURCE_PIN_MISMATCH'):
            load(self.library, 'PMC123', reg['bundleSha256'])


if __name__ == '__main__':
    unittest.main()
