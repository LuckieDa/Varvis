import copy
import hashlib
import tempfile
from pathlib import Path
import unittest

import bs1_gencc_submission as gencc
import bs1_knowledge as knowledge
import bs1_qualification as qualification
import bs1_expert_sex_chromosome as sex
from bs1_expert_batch import content_hash, compile_reviews
import test_bs1_expert_sex_chromosome as sex_fixtures


URL = 'https://thegencc.org/submissions/SGC-900001.1'


def synthetic_html(term='X-linked recessive', hpo='HP:0001419'):
    # Fictional identities and classification; no real relationship is asserted.
    fields = [('Submitter', 'SYNTHETIC_SOURCE_\u6d4b\u8bd5'), ('Accession', 'SGC-900001.1'),
              ('Classification', 'Definitive GENCC:100001'),
              ('Gene', '<a href="https://thegencc.org/genes/HGNC:1">SYNTHETIC_GENE</a> HGNC:1'),
              ('Disease', '<div class="font-normal">SYNTHETIC disease</div> MONDO:0000001'),
              ('Mode Of Inheritance', term + ' ' + hpo),
              ('Evaluated Date', '11/03/2026'), ('Submitted Date', '11/03/2026')]
    return ('<!doctype html><html><body><div>' + ''.join(
        '<div class="col-span-2">' + label + ':</div><div class="col-span-10">' + value + '</div>'
        for label, value in fields) + '</div></body></html>').encode('utf-8')


class GenccSubmissionTest(unittest.TestCase):
    def test_explicit_terms_are_replayed_and_each_locator_addresses_original_bytes(self):
        for term, hpo, canonical in [('X-linked recessive', 'HP:0001419', 'X_LINKED_RECESSIVE'),
                                     ('X-linked dominant', 'HP:0001423', 'X_LINKED_DOMINANT'),
                                     ('Y-linked inheritance', 'HP:0001450', 'Y_LINKED')]:
            with self.subTest(term=term):
                raw = synthetic_html(term, hpo)
                node, = gencc.parse_submission(raw, URL)
                self.assertEqual(('HGNC:1', 'MONDO:0000001', canonical, 'Definitive'),
                                 tuple(node[k] for k in ('hgncId', 'mondoId', 'moi', 'classification')))
                self.assertEqual(hashlib.sha256(raw).hexdigest(), node['original']['rawSha256'])
                for binding in node['original']['fieldBindings'].values():
                    start, end = map(int, binding['locator'].removeprefix('utf8-bytes:').split('-'))
                    fragment = raw[start:end]
                    self.assertEqual(hashlib.sha256(fragment).hexdigest(), binding['contentSha256'])
                    self.assertTrue(fragment.decode('utf-8').startswith('<div'))
                self.assertEqual([node], gencc.parse_submission(raw, URL))

    def test_generic_x_is_preserved_without_dominance_and_hemizygosity_cannot_select_it(self):
        node, = gencc.parse_submission(synthetic_html('X-linked', 'HP:0001417'), URL)
        self.assertEqual('HP:0001417', node['moi'])
        for term, code in [('X-linked recessive', 'HP:0001423'),
                           ('X-linked hemizygous', 'HP:0001417'),
                           ('X-linked', 'HP:0001419')]:
            with self.subTest(term=term, code=code), self.assertRaisesRegex(ValueError, 'MOI_LABEL_CONFLICT'):
                gencc.parse_submission(synthetic_html(term, code), URL)

    def test_missing_duplicate_ambiguous_and_changed_identity_fields_fail_closed(self):
        original = synthetic_html()
        changes = [original.replace(b'Mode Of Inheritance:', b'Missing label:'),
                   original.replace(b'</body>', b'<div class="col-span-2">Mode Of Inheritance:</div><div>X-linked</div></body>'),
                   original.replace(b'</a> HGNC:1', b'</a> HGNC:1 HGNC:2'),
                   original.replace(b'MONDO:0000001', b'MONDO:0000001 MONDO:0000002'),
                   original.replace(b'SGC-900001.1', b'SGC-900002.1'),
                   original.replace(b'Definitive GENCC:100001', b'Limited GENCC:100001'),
                   b'<html><head><title>Checking your browser</title></head></html>']
        for raw in changes:
            with self.subTest(raw=raw[:80]), self.assertRaises(ValueError):
                gencc.parse_submission(raw, URL)
        for url in [URL.replace('https:', 'http:'), URL.replace('thegencc.org', 'example.org'), URL + '?x=1']:
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, 'URL_INVALID'):
                gencc.parse_submission(original, url)

    def manifest_fixture(self, directory, dataset='EXOMES', supporting=False):
        f, approved, _ = sex_fixtures.SexChromosomeExpertTest().fixture(dataset)
        raw = synthetic_html()
        node, = gencc.parse_submission(raw, URL)
        f.sources[1].update(url=URL, sha256=knowledge.sha(raw))
        approved['reviewedModeMappings'][0]['contentSha256'] = content_hash(node)
        if dataset == 'JOINT':
            f.review['dataScopePolicyId'] = 'BS1_JOINT_SEX_V1'
            approved['decision'] = 'ADOPT_JOINT_SEX'
            approved['populationStatistic']['metricId'] = 'gnomad.joint.sex_ancestry_max_faf95'
        knowledge.write(directory / 'cspec.json', {'status': {'code': 200}, 'data': [f.entity]})
        (directory / 'moi.html').write_bytes(raw)
        sources = []
        for filename, source_id, kind, url in [('cspec.json', 'raw', 'CSPEC_RAW', 'https://example.org/SYNTHETIC'),
                ('moi.html', 'independent-moi', 'GENCC_SUBMISSION_HTML', URL),
                ]:
            sources.append(dict(sourceId=source_id, kind=kind, path=filename, url=url,
                                version='SYNTHETIC-1', sha256=knowledge.sha((directory / filename).read_bytes())))
        if supporting:
            for filename, source_id, kind, raw in [
                    ('support.pdf', 'guidance', 'PINNED_PDF_REFERENCE', b'%PDF-1.4\nSYNTHETIC identity-only control\n%%EOF\n'),
                    ('header.txt', 'header', 'PINNED_TEXT_REFERENCE', b'##SYNTHETIC header original\n')]:
                (directory / filename).write_bytes(raw)
                source = dict(sourceId=source_id, kind=kind, path=filename, url='https://example.org/SYNTHETIC/' + filename,
                              version='SYNTHETIC-1', sha256=knowledge.sha(raw))
                sources.append(source)
                node = knowledge.source_document(raw, source)['reference']
                approved.setdefault('supportingSourceBindings', []).append(dict(sourceId=source_id,
                    locator='/reference', contentSha256=content_hash(node), interpretationBasis='SYNTHETIC explicit review; no automatic semantics'))
        knowledge.write(directory / 'review.json', f.review)
        sources.append(dict(sourceId='review', kind='EXPERT_CLAUSE_REVIEW', path='review.json',
                            url='https://example.org/SYNTHETIC-REVIEW', version='SYNTHETIC-1',
                            sha256=knowledge.sha((directory / 'review.json').read_bytes())))
        manifest = dict(schemaVersion='bs1-knowledge-input-v1', version='SYNTHETIC-1', sources=sources)
        knowledge.write(directory / 'manifest.json', manifest)
        return f, approved, manifest, node

    def test_manifest_and_qualification_both_replay_html_original_for_joint_and_exomes(self):
        for dataset in ('EXOMES', 'JOINT'):
            with self.subTest(dataset=dataset), tempfile.TemporaryDirectory() as tmp:
                directory = Path(tmp)
                f, approved, manifest, node = self.manifest_fixture(directory, dataset, supporting=True)
                knowledge.compile_manifest(directory / 'manifest.json', directory / 'compiled')
                bundle = knowledge.read_json((directory / 'compiled/bundle.json').read_bytes())
                record, = bundle['records']
                self.assertEqual('X_LINKED_RECESSIVE', record['scope']['modes'][0])
                self.assertEqual(approved['populationStatistic']['metricId'], record['expertPolicy']['statistic']['metricId'])
                original_docs = {s['sourceId']: knowledge.source_document((directory / s['path']).read_bytes(), s)
                                 for s in manifest['sources'] if s['kind'] != 'EXPERT_CLAUSE_REVIEW'}
                locations = {(s['sourceId'], s['locator']) for s in record['sources']}
                locations.add(('raw', approved['sexChromosomeScope']['sourceLocator']))
                locations.add(('raw', approved['populationStatistic']['locator']))
                entry = {'record': record, 'payloadSha256': content_hash(qualification.payload(record)),
                         'sourceBindings': [{'sourceId': sid, 'locator': loc,
                              'contentSha256': content_hash(qualification.resolve(original_docs[sid], loc))}
                              for sid, loc in sorted(locations)],
                         'checks': {name: {'status': 'UNRESOLVED' if name == 'POPULATION_METRIC' else
                              'PUBLISHED_THRESHOLD_NOT_RECALCULATED' if name == 'PARAMETER_SCOPE' else 'SATISFIED',
                              'basis': 'SYNTHETIC contract only', 'sourceIds': ['raw', 'independent-moi']}
                              for name in qualification.CATEGORIES}}
                review = dict(schemaVersion='bs1-qualification-input-v1', reviewer='SYNTHETIC',
                              reviewedAt='2026-11-03', basis='SYNTHETIC no scientific adoption', entries=[entry])
                qualified, = qualification.qualify_from_manifest([review], manifest, directory / 'manifest.json',
                                                                 bundle['sources'], [record])
                self.assertEqual('UNRESOLVED', qualified['qualification']['checks']['POPULATION_METRIC']['status'])
                self.assertEqual({'raw', 'independent-moi', 'guidance', 'header'}, {s['sourceId'] for s in record['sources']})
                for filename in ('support.pdf', 'header.txt'):
                    original = (directory / filename).read_bytes()
                    (directory / filename).write_bytes(original + b' changed')
                    with self.assertRaisesRegex(ValueError, 'SOURCE_SHA_CHANGED'):
                        qualification.qualify_from_manifest([review], manifest, directory / 'manifest.json', bundle['sources'], [record])
                    (directory / filename).write_bytes(original)
                (directory / 'moi.html').write_bytes(synthetic_html('X-linked dominant', 'HP:0001423'))
                with self.assertRaisesRegex(ValueError, 'SOURCE_SHA_CHANGED'):
                    qualification.qualify_from_manifest([review], manifest, directory / 'manifest.json', bundle['sources'], [record])

    def test_precise_source_conflicts_and_cspec_qualifiers_remain_unresolved(self):
        f, approved, docs = sex_fixtures.SexChromosomeExpertTest().fixture()
        other = copy.deepcopy(docs['independent-moi'][0]); other['moi'] = 'X_LINKED_DOMINANT'
        docs['other-independent-source'] = [other]
        result = sex.prepare_reviews(f.review, f.specs, f.sources, docs)
        self.assertEqual(('UNRESOLVED', 'EXPERT_SEX_MOI_SOURCE_CONFLICT'), (result['status'], result['reasonCode']))
        docs.pop('other-independent-source')
        disease = f.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        disease['adjective'] = 'X-linked dominant (HP:0001423)'
        approved['entitySha256'] = content_hash(f.entity)
        result = sex.prepare_reviews(f.review, f.specs, f.sources, docs)
        self.assertEqual('EXPERT_SEX_MOI_SOURCE_CONFLICT', result['reasonCode'])
        with self.assertRaisesRegex(ValueError, 'MOI_SOURCE_CONFLICT'):
            sex.mode('X-linked dominant inheritance', approved, 'HGNC:1', 'MONDO:0000001', f.sources, docs)

    def test_supporting_sources_cannot_be_missing_duplicated_changed_or_mislocated(self):
        for fault in ('source', 'document', 'hash', 'locator', 'duplicate', 'extra'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as tmp:
                directory = Path(tmp)
                f, approved, manifest, _ = self.manifest_fixture(directory, supporting=True)
                sources = [{k: s[k] for k in ('sourceId', 'url', 'version', 'sha256')} for s in manifest['sources']]
                docs = {s['sourceId']: knowledge.source_document((directory / s['path']).read_bytes(), s)
                        for s in manifest['sources'] if s['kind'] != 'EXPERT_CLAUSE_REVIEW'}
                binding = approved['supportingSourceBindings'][0]
                if fault == 'source': sources = [s for s in sources if s['sourceId'] != 'guidance']
                elif fault == 'document': docs.pop('guidance')
                elif fault == 'hash': binding['contentSha256'] = '0' * 64
                elif fault == 'locator': binding['locator'] = '/missing'
                elif fault == 'duplicate': approved['supportingSourceBindings'].append(copy.deepcopy(binding))
                elif fault == 'extra': binding['implicitApproval'] = True
                with self.assertRaises(ValueError):
                    compile_reviews(f.review, f.specs, sources, docs)

    def test_reference_identity_does_not_treat_html_as_a_pdf_or_decode_invalid_text(self):
        with self.assertRaisesRegex(ValueError, 'PDF_BYTES_INVALID'):
            knowledge.source_document(b'<html>Preparing to download</html>', {'kind': 'PINNED_PDF_REFERENCE'})
        with self.assertRaisesRegex(ValueError, 'UTF8_INVALID'):
            knowledge.source_document(b'\xff', {'kind': 'PINNED_TEXT_REFERENCE'})

    def test_joint_uses_its_own_review_and_statistic_and_readiness_matches_dataset(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            _, approved, _, _ = self.manifest_fixture(directory, 'JOINT')
            scope = approved['sexChromosomeScope']
            self.assertEqual('ELIGIBLE_FOR_RUNTIME_VALIDATION', sex.dataset_readiness(scope, True, 'JOINT')['status'])
            self.assertEqual('BS1_RESOURCE_EXECUTION_UNAVAILABLE', sex.dataset_readiness(scope, False, 'JOINT')['reasonCode'])
            self.assertEqual('BS1_SEX_RESOURCE_DATASET_MISMATCH', sex.dataset_readiness(scope, True, 'EXOMES')['reasonCode'])
            approved['populationStatistic']['metricId'] = 'gnomad.exomes.sex_ancestry_max_faf95'
            with self.assertRaisesRegex(ValueError, 'STATISTIC_INVALID'):
                sex.contract(approved, [{'sourceId': 'raw'}])


if __name__ == '__main__':
    unittest.main()
