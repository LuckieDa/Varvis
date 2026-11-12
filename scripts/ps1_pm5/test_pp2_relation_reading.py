"""Small synthetic source/cache controls, never clinical relationship evidence."""
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
import zipfile
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import patch

import pp2_relation_reading as reader

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from test_bundle_model import configuration
from test_literature_acquisition import jats, zipped
from discover_public_literature import paper
from literature_search_transport import endpoint
from paper_download_transport import url_for
from model_literature_contract import canonical, digest, parse
from acquire_public_literature import source_bundle
from paper_library_registration import register
from prepare_literature import prepare


class RelationReadingTest(unittest.TestCase):
    def setUp(self):
        private = Path(os.environ.get('VARVIS_PP2_READING_TEST_WORK',
            r'D:/LSD/Project/Code/local/runtime/pp2-reading-engineering-tests'))
        private.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='reading-', dir=private)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.cache, self.library = self.root / 'cache', self.root / 'library'
        self.cache.mkdir(); self.library.mkdir()
        self.calls = 0
        self.profiles = []
        self.c = configuration()
        self.model_path = self.root / 'model.json'
        self.model_path.write_bytes(canonical(self.c))
        (self.root / 'key.txt').write_text('synthetic-reading-credential-not-real', encoding='utf8')
        self.config = dict(schemaVersion='denovo-public-literature-workflow-v1', enabled=True,
            projectRoot=str(self.root), library='library', searchCache='cache', modelBudget='budget', modelConfig='model.json',
            allowPublicNetwork=False, allowModel=True, maximumReadingCalls=8, maximumReadingChunks=8)
        self.config_path = self.root / 'workflow.json'
        self.raw = jats(False).replace(b'A public source.', b'Case 1 was studied. No parents were tested.')
        self.row = dict(source='MED', id='456', pmcid='PMC123', doi='10.1234/test', title='Test source',
                        isOpenAccess='Y', hasSuppl='N')
        self.metadata = canonical(dict(hitCount=1, resultList=dict(result=[self.row])))
        query = 'EXT_ID:456 AND SRC:MED'
        self.cache_response(endpoint('europepmc', query, '*')[0], self.metadata)
        self.cache_response(url_for('PMC123', 'fullTextXML'), self.raw)
        self.known = [dict(pmid='456', sourceRawSha256='a' * 64,
                           citation=dict(sourceSha256='a' * 64, xpath='SYNTHETIC_SOURCE_REFERENCE_ONLY'))]

    def cache_response(self, url, raw):
        pin = digest(raw)
        (self.cache / (pin + '.body')).write_bytes(raw)
        (self.cache / (digest(url.encode()) + '.json')).write_bytes(canonical(dict(
            url=url, sha256=pin, retrievedAt='SYNTHETIC_SAVED_PUBLIC_RESPONSE', retrievedEpoch=time.time())))
        return pin

    def candidates(self):
        candidate = paper(self.row)
        candidate['origins'] = [dict(kind='SEARCH_RESPONSE', sha256=digest(self.metadata))]
        return [candidate]

    def response(self, c, key, body, *, coverage='EXTRACTED_WITHIN_TARGET'):
        self.calls += 1
        request = parse(body)
        content = parse(request['messages'][1]['content'])
        target = set(content['workUnit']['targetUnitIds'])
        unit = next(u for u in content['units'] if u['unitId'] in target and u['text'])
        if 'questionContract' in content:
            self.profiles.append(reader.RELATIONSHIP_PROFILE)
            citation = dict(unitId=unit['unitId'], quote=unit['text'][:120])
            candidate = dict(schemaVersion='pp2-relationship-material-candidates-v1',
                sourceIdentity=content['questionContract']['sourceIdentity'],
                workUnitSha256=content['questionContract']['workUnitSha256'],
                records=[dict(materialKind='COHORT', anchor=citation,
                    facts=[dict(field='reportedObservationOrResult', sourceValue=unit['text'][:120], citation=citation)])],
                coverage='MORE_RECORDS_OR_FIELDS' if coverage == 'MORE_CASES_OR_FIELDS' else coverage,
                sourceAssessments=[dict(materialKind='COHORT', citation=citation)])
        else:
            self.profiles.append(reader.MODEL_PROFILE)
            candidate = dict(schemaVersion='denovo-core-material-candidates-v1', cases=[], coverage=coverage,
                sourceAssessments=[dict(materialKind='AGGREGATE_OR_METHODS',
                    citation=dict(unitId=unit['unitId'], quote=unit['text'][:120]))])
        return canonical(dict(model=c['expectedResponseModel'],
            usage=dict(prompt_tokens=10, completion_tokens=20, total_tokens=30),
            choices=[dict(finish_reason='stop', message=dict(content=canonical(candidate).decode()))]))

    def run_scope(self, name='first', candidates=None, known=None, **kwargs):
        raw = canonical(self.config)
        self.config_path.write_bytes(raw)
        with patch.dict(os.environ, {'VARVIS_TEST_FAKE': ''}):
            return reader.read_scope(self.config_path, digest(raw), candidates if candidates is not None else [],
                known if known is not None else self.known, self.root / name, model_transport=kwargs.pop('model_transport', self.response), **kwargs)

    def verify_originals(self, output, name='first'):
        seen = set()
        for pin, value in output['originals'].items():
            self.assertNotIn(pin, seen); seen.add(pin)
            path = (self.root / name / value['relativePath']).resolve()
            self.assertTrue(path.is_relative_to((self.root / name / 'originals').resolve()))
            raw = path.read_bytes()
            self.assertEqual(pin, digest(raw))
            self.assertEqual(len(raw), value['bytes'])
            self.assertLessEqual(len(raw), 4 * 1024 * 1024)
        self.assertLessEqual(sum(v['bytes'] for v in output['originals'].values()), 32 * 1024 * 1024)

    def seed_ps2_cache(self, *, package=None):
        class Transport:
            def get(unused_self, *unused):
                return package
        files, bundle = source_bundle({'pmcid': 'PMC123'}, self.raw,
            prepare(self.raw, 'PMC123', digest(self.raw)), Transport(), package is not None)
        registered = register(self.library, 'PMC123', files, bundle,
            {'sha256': digest(self.raw), 'acquisitionUrl': url_for('PMC123', 'fullTextXML')}, criteria=('PS2',))
        pin = registered['bundleSha256']
        source = reader.load(self.library, 'PMC123', pin, criterion='PS2')
        plans = reader.requests(source, self.c['model'], self.c['maxOutputTokens'], self.c['maxRequestBytes'], reader.MODEL_PROFILE)
        values = []
        for index in range(len(plans)):
            folder = self.root / ('original-ps2-' + str(index))
            reader.model_run(self.library, 'PMC123', pin, self.c, self.model_path, self.root / 'budget', folder,
                self.response, chunk_index=index, extraction_profile=reader.MODEL_PROFILE, criterion='PS2')
            values.append(parse((folder / 'artifact.json').read_bytes()))
        return pin, plans, values

    def test_direct_known_citation_is_retrieved_and_raw_read_without_litvar_candidate(self):
        output = self.run_scope()
        self.assertEqual('RAW_SCOPE_READ', output['status'], output['pending'])
        self.assertTrue(output['readingComplete'])
        self.assertEqual('PMC123', output['knownCitationMetadata'][0]['pmcid'])

        self.assertEqual('RAW_WORK_SCOPE_READ', output['papers'][0]['status'])
        self.assertEqual(2, output['modelCalls'])
        self.assertEqual('NOT_GRANTED', output['relationQualification'])
        self.assertFalse(output['scientificAdoption'])
        self.assertFalse(output['businessPublishable'])
        self.assertEqual('NONE', output['clinicalAdoption'])
        self.assertTrue(output['papers'][0]['materialIssues'])
        self.assertEqual('PP2_READING_EMPTY_EXTRACTION_IS_NOT_ABSENCE', output['papers'][0]['materialIssues'][0]['reasonCode'])
        self.verify_originals(output)
        self.assertFalse(output['wholePublicationRead'])
        for value in output['originals'].values():
            self.assertNotIn(b'synthetic-reading-credential-not-real',
                             (self.root / 'first' / value['relativePath']).read_bytes())

    def test_registered_known_citation_reuses_original_and_cached_extraction(self):
        self.run_scope()
        self.config['allowPublicNetwork'] = True
        output = self.run_scope(name='local-citation', search_transport_factory=
            lambda *unused: self.fail('registered PMID entered remote metadata lookup'),
            model_transport=lambda *unused: self.fail('replayed extraction entered model'))
        self.assertTrue(output['readingComplete'], output['pending'])
        self.assertEqual('VERIFIED_REGISTERED_ARTICLE', output['knownCitationMetadata'][0]['sourceKind'])
        self.assertEqual(0, output['networkRequests'])
        self.assertEqual(0, output['modelCalls'])

    def test_cached_chunks_reuse_original_paid_receipt_without_credentials_or_second_provider_call(self):
        first = self.run_scope()
        (self.root / 'key.txt').unlink()
        second = self.run_scope('second', model_transport=lambda *unused: self.fail('cache hit must not call provider'))
        self.assertEqual(2, self.calls)
        self.assertTrue(second['readingComplete'], second['pending'])
        self.assertEqual(0, second['modelCalls'])
        self.assertEqual(2, second['cacheHits'])
        old, new = first['papers'][0]['checkedChunks'][0], second['papers'][0]['checkedChunks'][0]
        for field in ('requestRawSha256', 'responseRawSha256', 'receiptSha256'):
            self.assertEqual(old[field], new[field])
        self.verify_originals(second, 'second')

    def test_wrong_or_missing_known_metadata_remains_pending_and_preserves_actual_bad_response(self):
        wrong = canonical(dict(hitCount=1, resultList=dict(result=[dict(self.row, id='999')])))
        self.cache_response(endpoint('europepmc', 'EXT_ID:456 AND SRC:MED', '*')[0], wrong)
        output = self.run_scope()
        self.assertFalse(output['readingComplete'])
        self.assertEqual([], output['papers'])
        self.assertEqual('PP2_READING_KNOWN_CITATION_METADATA_ID_MISMATCH', output['pending'][0]['reasonCode'])
        self.assertIn(digest(wrong), output['originals'])
        self.assertEqual(0, output['modelAttempts'])
        metadata_url = endpoint('europepmc', 'EXT_ID:456 AND SRC:MED', '*')[0]
        (self.cache / (digest(metadata_url.encode()) + '.json')).unlink()
        missing = self.run_scope('missing')
        self.assertFalse(missing['readingComplete'])
        self.assertEqual({}, missing['originals'])
        self.assertFalse(any(r.get('rawSha256') or r.get('sha256') for r in missing['transportReads']))

    def test_model_disabled_cache_miss_is_not_empty_source_or_success(self):
        self.config['allowModel'] = False
        (self.root / 'key.txt').unlink()
        output = self.run_scope(model_transport=lambda *unused: self.fail('disabled model called'))
        self.assertFalse(output['readingComplete'])
        self.assertEqual(0, output['modelAttempts'])
        self.assertEqual(0, output['modelCalls'])
        self.assertTrue(any(g['reasonCode'] == 'PP2_READING_MODEL_DISABLED_CACHE_MISSING' for g in output['pending']))
        self.assertTrue(output['papers'][0]['originalRawSha256s'])

    def test_partial_model_coverage_never_closes_the_read_scope(self):
        output = self.run_scope(model_transport=lambda *args: self.response(*args, coverage='MORE_CASES_OR_FIELDS'))
        self.assertFalse(output['readingComplete'])
        self.assertEqual('PARTIAL', output['papers'][0]['status'])
        self.assertTrue(any(g['reasonCode'] == 'PP2_READING_EXTRACTION_INCOMPLETE' for g in output['pending']))

    def test_unparsed_explicit_attachment_remains_pending_even_when_all_available_text_is_read(self):
        self.row['hasSuppl'] = 'Y'
        self.raw = jats(True).replace(b'A public source.', b'Case 1 was studied.')
        self.metadata = canonical(dict(hitCount=1, resultList=dict(result=[self.row])))
        self.cache_response(endpoint('europepmc', 'EXT_ID:456 AND SRC:MED', '*')[0], self.metadata)
        self.cache_response(url_for('PMC123', 'fullTextXML'), self.raw)
        package = zipped([('unknown.pdf', b'%PDF-not-readable')])
        self.cache_response(url_for('PMC123', 'supplementaryFiles'), package)
        output = self.run_scope()
        self.assertFalse(output['readingComplete'])
        self.assertTrue(any(g['reasonCode'] in ('PP2_READING_ATTACHMENT_NOT_FULLY_READ', 'PP2_READING_ATTACHMENT_COVERAGE_INCOMPLETE')
                            for g in output['pending']))
        self.assertIn(digest(package), output['originals'])

    def test_billed_bad_response_and_failed_reservation_are_preserved_and_not_retried(self):
        def malformed(c, key, body):
            self.calls += 1
            return canonical(dict(model=c['expectedResponseModel'],
                usage=dict(prompt_tokens=10, completion_tokens=20, total_tokens=30),
                choices=[dict(finish_reason='stop', message=dict(content='invalid synthetic JSON'))]))
        output = self.run_scope(model_transport=malformed)
        self.assertFalse(output['readingComplete'])
        self.assertEqual('FAILED_OR_UNKNOWN_RESERVATION_RETAINED', output['modelAccountingState'])
        failed = output['papers'][0]['failedModelWork'][0]
        self.assertEqual('FAILED_RESERVED', failed['reservation']['status'])
        self.assertIn('response.json', failed['originals'])
        self.assertIn(failed['originals']['response.json'], output['originals'])
        self.assertEqual(1, self.calls)
        retry = self.run_scope('second', model_transport=lambda *unused: self.fail('failed shared reservation was retried'))
        self.assertFalse(retry['readingComplete'])
        self.assertEqual(1, self.calls)

    def test_source_configuration_change_during_extraction_invalidates_read_scope(self):
        def changing(*args):
            raw = self.response(*args)
            self.config_path.write_bytes(canonical(dict(self.config, allowModel=False)))
            return raw
        output = self.run_scope(model_transport=changing)
        self.assertFalse(output['readingComplete'])
        self.assertTrue(any(g['reasonCode'] == 'PP2_READING_CONFIG_CHANGED_DURING_READING' for g in output['pending']))
        self.assertFalse(output['scientificAdoption'])
        self.assertEqual(1, self.calls, 'Changed permission/configuration must stop the next question call')

    def test_new_work_cap_preserves_all_unread_plans_and_later_calls_advance_using_cache(self):
        self.config.update(maximumReadingCalls=1, maximumReadingChunks=1)
        raw = self.raw.replace(b'</body>', b'<p>Case 2 was studied.</p><p>Case 3 was studied.</p></body>')
        self.cache_response(url_for('PMC123', 'fullTextXML'), raw)
        first = self.run_scope()
        self.assertFalse(first['readingComplete'])
        self.assertEqual(3, len(first['papers'][0]['workPlans']))
        self.assertEqual(1, first['modelCalls'])
        second = self.run_scope('second')
        self.assertFalse(second['readingComplete'])
        self.assertEqual(1, second['modelCalls'])
        self.assertEqual(1, second['cacheHits'])
        third = self.run_scope('third')
        self.assertFalse(third['readingComplete'])
        self.assertEqual(1, third['modelCalls'])
        self.assertEqual(2, third['cacheHits'])
        for name in ('fourth', 'fifth', 'sixth'):
            later = self.run_scope(name)
            self.assertEqual(1, later['modelCalls'])
        self.assertTrue(later['readingComplete'], later['pending'])
        self.assertEqual(5, later['cacheHits'])
        self.assertEqual(6, self.calls)

    def test_bad_workflow_pin_does_not_open_sources_or_model(self):
        raw = canonical(self.config)
        self.config_path.write_bytes(raw)
        output = reader.read_scope(self.config_path, 'f' * 64, [], self.known, self.root / 'first',
            model_transport=lambda *unused: self.fail('bad config pin invoked model'))
        self.assertEqual('UNAVAILABLE', output['status'])
        self.assertFalse(output['readingComplete'])
        self.assertEqual({}, output['originals'])
        self.assertEqual('PP2_READING_CONFIG_PIN_MISMATCH', output['pending'][0]['reasonCode'])

    def test_corrupted_metadata_cache_preserves_observed_bytes_without_claiming_the_expected_pin(self):
        actual = b'{not the pinned metadata response}'
        (self.cache / (digest(self.metadata) + '.body')).write_bytes(actual)
        output = self.run_scope()
        self.assertFalse(output['readingComplete'])
        failed = next(r for r in output['transportReads'] if r.get('rawSha256'))
        self.assertEqual(digest(self.metadata), failed['expectedRawSha256'])
        self.assertEqual(digest(actual), failed['rawSha256'])
        self.assertIn(digest(actual), output['originals'])
        self.assertEqual(0, output['modelAttempts'])

    def test_corrupted_successful_model_cache_is_preserved_and_never_repaired_by_a_new_call(self):
        first = self.run_scope()
        artifact = first['papers'][0]['checkedChunks'][0]
        self.assertTrue(artifact['responseRawSha256'])
        ledger = parse((self.root / 'budget' / 'budget.json').read_bytes())
        key = ledger['calls'][0]['key']
        body = self.root / 'budget' / 'responses' / key / 'response.json'
        actual = b'{corrupted saved model response}'
        body.write_bytes(actual)
        second = self.run_scope('second', model_transport=lambda *unused: self.fail('corrupt cache triggered a new call'))
        self.assertFalse(second['readingComplete'])
        failed = second['papers'][0]['failedModelWork'][0]
        self.assertEqual('FAILED_CACHE_READ_NO_NEW_CALL', failed['accountingState'])
        self.assertEqual(digest(actual), failed['originals']['response.json'])
        self.assertIn(digest(actual), second['originals'])
        self.assertEqual(2, self.calls)

    def test_original_archive_limits_preserve_local_complete_bytes_and_deduplicate_without_formal_pins(self):
        work = self.root / 'limits'; work.mkdir()
        originals = reader.Originals(work)
        limit = 4 * 1024 * 1024
        pins = [originals.add(bytes([index]) * limit, source_kind='SYNTHETIC_ARCHIVE_BUDGET_INPUT') for index in range(8)]
        self.assertEqual(32 * 1024 * 1024, originals.total)
        self.assertEqual([], originals.missing_archive(pins))
        excess = b'actual ninth original'
        pin = originals.add(excess, source_kind='SYNTHETIC_ARCHIVE_BUDGET_INPUT')
        self.assertNotIn(pin, originals.entries)
        self.assertFalse(originals.unretained[pin]['archiveRetained'])
        self.assertEqual(excess, (work / originals.unretained[pin]['relativePath']).read_bytes())
        before = originals.local_total
        self.assertEqual(pin, originals.add(excess, source_kind='SYNTHETIC_DUPLICATE_SOURCE'))
        self.assertEqual(before, originals.local_total)
        self.assertEqual(32 * 1024 * 1024, originals.total)

    def test_oversized_actual_package_remains_local_but_skips_every_model_path(self):
        self.row['hasSuppl'] = 'Y'
        self.raw = jats(True).replace(b'A public source.', b'Case 1 was studied.')
        self.metadata = canonical(dict(hitCount=1, resultList=dict(result=[self.row])))
        self.cache_response(endpoint('europepmc', 'EXT_ID:456 AND SRC:MED', '*')[0], self.metadata)
        self.cache_response(url_for('PMC123', 'fullTextXML'), self.raw)
        package = zipped([('table.csv', b'Case,Gene\n1,GENE1\n'), ('large.bin', b'x' * (4 * 1024 * 1024 + 1))])
        self.cache_response(url_for('PMC123', 'supplementaryFiles'), package)
        with patch.object(reader, 'model_run', side_effect=AssertionError('unarchivable paper entered model path')):
            output = self.run_scope()
        self.assertFalse(output['readingComplete'])
        self.assertEqual(0, output['modelAttempts'])
        self.assertEqual(0, self.calls)
        paper_result = output['papers'][0]
        gap = next(g for g in paper_result['pending'] if g['reasonCode'] == 'PP2_READING_ARCHIVE_TECHNICAL_LIMIT')
        self.assertTrue(gap['modelSkipped'])
        self.assertIn(digest(package), output['unretainedOriginals'])
        self.assertNotIn(digest(package), output['originals'])
        original = output['unretainedOriginals'][digest(package)]
        self.assertEqual(package, (self.root / 'first' / original['relativePath']).read_bytes())
        self.assertTrue(list((self.library / 'papers' / 'PMC123' / 'supplementary').glob('*.zip')))
        self.verify_originals(output)

    def test_model_bytes_that_exhaust_archive_budget_remain_local_and_cannot_be_checked_chunks(self):
        first = self.run_scope()
        source_bytes = sum(v['bytes'] for v in first['originals'].values() if not any(
            p['sourceKind'] in {'ACTUAL_MODEL_ARTIFACT', 'EXACT_MODEL_REQUEST_RECONSTRUCTION',
                               'ACTUAL_RAW_MODEL_RESPONSE', 'ORIGINAL_MODEL_RECEIPT_CANONICAL_JSON'} for p in v['provenance']))
        with patch.object(reader, 'MAX_ARCHIVE_RETAINED', source_bytes + 1):
            output = self.run_scope('small-archive', model_transport=lambda *unused: self.fail('cache invoked provider'))
        self.assertFalse(output['readingComplete'])
        self.assertEqual([], output['papers'][0]['checkedChunks'])
        self.assertTrue(output['papers'][0]['unretainedModelWork'])
        self.assertEqual(2, self.calls)
        for pin, value in output['unretainedOriginals'].items():
            raw = (self.root / 'small-archive' / value['relativePath']).read_bytes()
            self.assertEqual(pin, digest(raw))
            self.assertFalse(value['archiveRetained'])

    def test_actual_registered_ps2_core_cache_reuses_receipt_as_literal_facts_without_credentials(self):
        pin, plans, artifacts = self.seed_ps2_cache()
        (self.root / 'key.txt').unlink()
        self.config['allowModel'] = False
        output = self.run_scope(model_transport=lambda *unused: self.fail('PS2 cache-only reuse called provider'))
        self.assertFalse(output['readingComplete'], 'PS2 literal cache cannot answer unexecuted PP2 questions')
        self.assertEqual(0, output['modelCalls'])
        self.assertEqual(len(plans), output['borrowedLiteralFactCacheHits'])
        self.assertEqual(len(plans), self.calls)
        self.assertEqual(pin, output['papers'][0]['bundleSha256'])
        reader.load(self.library, 'PMC123', pin, criterion='PP2')
        chunk = output['papers'][0]['checkedChunks'][0]
        self.assertEqual('PS2', chunk['originalExtractionCriterion'])
        self.assertTrue(chunk['purposeBorrowedLiteralFacts'])
        self.assertEqual('NOT_ESTABLISHED', chunk['otherPhenotypeQuestionCoverage'])
        self.assertEqual(digest(canonical(artifacts[0]['receipt'])), chunk['receiptSha256'])
        replayed = parse((self.root / 'first' / output['originals'][chunk['artifactRawSha256']]['relativePath']).read_bytes())
        self.assertEqual(artifacts[0]['binding'], replayed['binding'])
        self.assertNotIn('criterion', replayed['binding'])  # Original PS2 identity is unchanged.
        self.assertFalse(output['scientificAdoption'])
        self.verify_originals(output)

    def test_ps2_cache_without_actual_purpose_membership_is_not_borrowed(self):
        self.seed_ps2_cache()
        (self.library / 'evidence' / 'PS2' / 'index.json').unlink()
        output = self.run_scope()
        self.assertTrue(output['readingComplete'], output['pending'])
        self.assertEqual(0, output['borrowedLiteralFactCacheHits'])
        self.assertEqual(2, output['modelCalls'])
        self.assertEqual('PP2', output['papers'][0]['checkedChunks'][0]['originalExtractionCriterion'])
        self.assertEqual(3, self.calls)

    def test_same_bundle_different_registered_attachment_view_and_units_cannot_borrow_ps2_cache(self):
        self.raw = jats(True).replace(b'table.csv', b'inner.zip')
        inner = zipped([('table.csv', b'Case,Gene\nCase 1,GENE1\n')])
        package = zipped([('inner.zip', inner)])
        pin, ps2_plans, unused = self.seed_ps2_cache(package=package)
        reader_source = reader.load(self.library, 'PMC123', pin, criterion='PS2')
        self.assertFalse(any(u['kind'] == 'SUPPLEMENT_CELL' for u in reader_source['units']))
        candidates = self.candidates()
        candidates[0]['localReviewedBundleSha256'] = pin
        output = self.run_scope(candidates=candidates, known=[])
        self.assertEqual(0, output['borrowedLiteralFactCacheHits'])
        source = reader.load(self.library, 'PMC123', pin, criterion='PP2')
        self.assertTrue(any(u['kind'] == 'SUPPLEMENT_CELL' for u in source['units']))
        pp2_plans = reader.requests(source, self.c['model'], self.c['maxOutputTokens'], self.c['maxRequestBytes'], reader.MODEL_PROFILE)
        self.assertTrue(all(reader.canonical(a) != reader.canonical(b) for a in pp2_plans for b in ps2_plans))
        self.assertTrue(output['papers'][0]['checkedChunks'], output['pending'])
        self.assertTrue(all(not c['purposeBorrowedLiteralFacts'] and c['originalExtractionCriterion'] == 'PP2'
                            for c in output['papers'][0]['checkedChunks']))

    def test_changed_source_bytes_under_old_registered_pin_cannot_borrow_or_repair_cache(self):
        pin, unused, unused_artifacts = self.seed_ps2_cache()
        (self.library / 'papers' / 'PMC123' / 'PMC123.xml').write_bytes(self.raw.replace(b'Case 1', b'Case 2'))
        output = self.run_scope(model_transport=lambda *unused: self.fail('invalid old source entered model path'))
        self.assertFalse(output['readingComplete'])
        self.assertEqual(0, output['borrowedLiteralFactCacheHits'])
        self.assertEqual(0, output['modelAttempts'])
        self.assertEqual(1, self.calls)
        self.assertFalse(any(p['checkedChunks'] for p in output['papers']))

    def test_distinct_question_request_retains_real_pins_and_aggregate_function_material(self):
        def mixed(c, key, body):
            response = parse(self.response(c, key, body))
            candidate = parse(response['choices'][0]['message']['content'])
            if candidate['schemaVersion'] == 'pp2-relationship-material-candidates-v1':
                other = deepcopy(candidate['records'][0]); other['materialKind'] = 'FUNCTIONAL_STUDY'
                candidate['records'].append(other)
                response['choices'][0]['message']['content'] = canonical(candidate).decode()
            return canonical(response)
        output = self.run_scope(model_transport=mixed)
        paper_result = output['papers'][0]
        self.assertEqual('1.2.0', output['methodVersion'])
        self.assertEqual([reader.MODEL_PROFILE, reader.RELATIONSHIP_PROFILE], self.profiles)
        self.assertEqual('NOT_ESTABLISHED', paper_result['checkedChunks'][0]['otherPhenotypeQuestionCoverage'])
        self.assertEqual('RAW_QUESTION_SCOPE_READ', paper_result['questionCoverage']['status'])
        self.assertEqual((1, 1), (paper_result['questionCoverage']['expectedPlans'], paper_result['questionCoverage']['checkedPlans']))
        checked = paper_result['relationshipChunks'][0]
        self.assertEqual(reader.RELATIONSHIP_PROFILE, checked['questionVersion'])
        self.assertEqual(reader.RELATIONSHIP_PROFILE, checked['originalExtractionProfile'])
        self.assertEqual('PP2', checked['originalExtractionCriterion'])
        self.assertNotIn('cases', checked['review'])
        self.assertEqual(['COHORT', 'FUNCTIONAL_STUDY'], [r['materialKindProposal'] for r in checked['review']['records']])
        for field in ('artifactRawSha256', 'requestRawSha256', 'responseRawSha256', 'receiptSha256'):
            self.assertIn(checked[field], output['originals'])
        request = parse((self.root / 'first' / output['originals'][checked['requestRawSha256']]['relativePath']).read_bytes())
        self.assertEqual(reader.RELATIONSHIP_PROFILE, parse(request['messages'][1]['content'])['questionContract']['questionVersion'])
        artifact = parse((self.root / 'first' / output['originals'][checked['artifactRawSha256']]['relativePath']).read_bytes())
        self.assertEqual(reader.RELATIONSHIP_PROFILE, artifact['binding']['extractionProfile'])
        self.assertEqual('PP2', artifact['binding']['criterion'])
        self.assertEqual(checked['workUnitSha256'], checked['review']['workUnitSha256'])
        self.assertFalse(checked['review']['scientificAdoption'])
        self.verify_originals(output)

    def shared_limit(self):
        first = self.run_scope()
        paper_result = first['papers'][0]
        self.assertEqual(1, first['modelAttempts']); self.assertEqual(1, first['modelCalls'])
        self.assertEqual(1, len(paper_result['checkedChunks']))
        self.assertEqual([], paper_result['relationshipChunks'])
        self.assertEqual(1, len(paper_result['relationshipWorkPlans']))
        self.assertTrue(any(g['reasonCode'] == 'PP2_READING_NEW_WORK_LIMIT'
                            and g['extractionProfile'] == reader.RELATIONSHIP_PROFILE for g in paper_result['pending']))
        self.assertEqual('INCOMPLETE', paper_result['questionCoverage']['status'])
        second = self.run_scope('second')
        self.assertTrue(second['readingComplete'], second['pending'])
        self.assertEqual(1, second['modelCalls']); self.assertEqual(1, second['modelAttempts'])
        self.assertEqual(1, second['cacheHits']); self.assertEqual(2, self.calls)
        budget = parse((self.root / 'budget/budget.json').read_bytes())
        self.assertEqual(2, len(budget['calls']))
        self.assertEqual([reader.MODEL_PROFILE, reader.RELATIONSHIP_PROFILE], self.profiles)

    def test_question_stage_does_not_reset_new_call_limit_after_core(self):
        self.config.update(maximumReadingCalls=1, maximumReadingChunks=8)
        self.shared_limit()

    def test_question_stage_does_not_reset_new_chunk_limit_after_core(self):
        self.config.update(maximumReadingCalls=8, maximumReadingChunks=1)
        self.shared_limit()

    def test_question_stage_uses_the_original_deadline(self):
        clock = [0]
        def spent(c, key, body):
            response = self.response(c, key, body); clock[0] = 29
            return response
        with patch.object(reader, 'time', SimpleNamespace(monotonic=lambda: clock[0])):
            output = self.run_scope(model_transport=spent, remaining_seconds=30)
        self.assertEqual(1, self.calls)
        self.assertEqual(1, output['modelAttempts'])
        self.assertEqual([], output['papers'][0]['relationshipChunks'])
        self.assertTrue(any(g['reasonCode'] == 'PP2_READING_TIME_BUDGET'
                            and g['extractionProfile'] == reader.RELATIONSHIP_PROFILE for g in output['pending']))
        self.assertFalse(output['readingComplete'])

    def test_empty_question_records_remain_real_read_artifacts_and_pending_not_absence(self):
        def empty(c, key, body):
            response = parse(self.response(c, key, body))
            candidate = parse(response['choices'][0]['message']['content'])
            if 'records' in candidate:
                candidate['records'] = []
                response['choices'][0]['message']['content'] = canonical(candidate).decode()
            return canonical(response)
        output = self.run_scope(model_transport=empty)
        self.assertEqual(2, self.calls)
        self.assertEqual(1, len(output['papers'][0]['relationshipChunks']))
        self.assertEqual('INCOMPLETE', output['papers'][0]['questionCoverage']['status'])
        self.assertTrue(any(g['reasonCode'] == 'PP2_READING_RELATIONSHIP_EMPTY_EXTRACTION_IS_NOT_ABSENCE' for g in output['pending']))
        self.assertFalse(output['readingComplete']); self.assertFalse(output['scientificAdoption'])
        self.assertNotIn('outcome', output)

    def test_core_response_to_question_request_keeps_actual_failed_question_reservation(self):
        def wrong(c, key, body):
            response = parse(self.response(c, key, body))
            candidate = parse(response['choices'][0]['message']['content'])
            if 'records' in candidate:
                response['choices'][0]['message']['content'] = canonical(dict(
                    schemaVersion='denovo-core-material-candidates-v1', cases=[], coverage='EXTRACTED_WITHIN_TARGET',
                    sourceAssessments=[])).decode()
            return canonical(response)
        first = self.run_scope(model_transport=wrong)
        failed = first['papers'][0]['failedModelWork'][0]
        self.assertEqual(reader.RELATIONSHIP_PROFILE, failed['originalExtractionProfile'])
        self.assertEqual('PP2', failed['originalExtractionCriterion'])
        self.assertEqual('FAILED_RESERVED', failed['reservation']['status'])
        self.assertIn('response.json', failed['originals'])
        self.assertEqual(1, len(first['papers'][0]['checkedChunks']))
        self.assertEqual([], first['papers'][0]['relationshipChunks'])
        self.assertFalse(first['readingComplete']); self.assertEqual(2, self.calls)
        second = self.run_scope('second', model_transport=lambda *unused: self.fail('failed question reservation was retried'))
        self.assertFalse(second['readingComplete']); self.assertEqual(2, self.calls)
        self.assertEqual(1, second['cacheHits'])
        self.assertEqual(reader.RELATIONSHIP_PROFILE, second['papers'][0]['failedModelWork'][0]['originalExtractionProfile'])

    def test_partial_question_extraction_remains_pending_with_the_original_question_artifacts(self):
        def partial(c, key, body):
            content = parse(parse(body)['messages'][1]['content'])
            return self.response(c, key, body, coverage='MORE_RECORDS_OR_FIELDS'
                                 if 'questionContract' in content else 'EXTRACTED_WITHIN_TARGET')
        output = self.run_scope(model_transport=partial)
        paper_result = output['papers'][0]
        self.assertEqual(1, len(paper_result['checkedChunks']))
        self.assertEqual(1, len(paper_result['relationshipChunks']))
        self.assertEqual('INCOMPLETE', paper_result['questionCoverage']['status'])
        self.assertTrue(any(g.get('extractionProfile') == reader.RELATIONSHIP_PROFILE
                            and g['reasonCode'] == 'PP2_READING_EXTRACTION_INCOMPLETE' for g in output['pending']))
        self.assertFalse(output['readingComplete']); self.verify_originals(output)

    def test_question_anchor_without_facts_is_not_read_coverage_or_a_negative_relationship(self):
        def anchor(c, key, body):
            response = parse(self.response(c, key, body))
            candidate = parse(response['choices'][0]['message']['content'])
            if 'records' in candidate:
                candidate['records'][0]['facts'] = []
                response['choices'][0]['message']['content'] = canonical(candidate).decode()
            return canonical(response)
        output = self.run_scope(model_transport=anchor)
        self.assertEqual('INCOMPLETE', output['papers'][0]['questionCoverage']['status'])
        self.assertTrue(any(g['reasonCode'] == 'PP2_READING_RELATIONSHIP_RECORD_WITHOUT_EXTRACTED_FACTS' for g in output['pending']))
        self.assertFalse(output['readingComplete']); self.assertFalse(output['scientificAdoption'])

    def test_corrupted_question_cache_keeps_failed_bytes_and_never_pays_to_repair_it(self):
        first = self.run_scope()
        chunk = first['papers'][0]['relationshipChunks'][0]
        receipt = parse((self.root / 'first' / first['originals'][chunk['receiptSha256']]['relativePath']).read_bytes())
        key = digest(canonical(receipt['identity']))
        response = self.root / 'budget/responses' / key / 'response.json'
        corrupted = b'{actual corrupted saved question response}'
        response.write_bytes(corrupted)
        budget_before = (self.root / 'budget/budget.json').read_bytes()
        second = self.run_scope('second', model_transport=lambda *unused: self.fail('invalid saved questions were repurchased'))
        failed = second['papers'][0]['failedModelWork'][0]
        self.assertEqual(reader.RELATIONSHIP_PROFILE, failed['originalExtractionProfile'])
        self.assertEqual('FAILED_CACHE_READ_NO_NEW_CALL', failed['accountingState'])
        self.assertEqual(digest(corrupted), failed['originals']['response.json'])
        self.assertIn(digest(corrupted), second['originals'])
        self.assertEqual(1, len(second['papers'][0]['checkedChunks']))
        self.assertEqual([], second['papers'][0]['relationshipChunks'])
        self.assertFalse(second['readingComplete']); self.assertEqual(2, self.calls)
        self.assertEqual(budget_before, (self.root / 'budget/budget.json').read_bytes())

    def test_ps2_core_cache_can_be_borrowed_while_only_new_questions_charge(self):
        unused, plans, artifacts = self.seed_ps2_cache()
        output = self.run_scope()
        self.assertTrue(output['readingComplete'], output['pending'])
        self.assertEqual(len(plans), output['borrowedLiteralFactCacheHits'])
        self.assertEqual(1, output['modelCalls']); self.assertEqual(1, output['modelAttempts'])
        core = output['papers'][0]['checkedChunks'][0]
        self.assertEqual(digest(canonical(artifacts[0]['receipt'])), core['receiptSha256'])
        self.assertEqual('NOT_ESTABLISHED', core['otherPhenotypeQuestionCoverage'])
        self.assertEqual(1, len(output['papers'][0]['relationshipChunks']))
        self.assertEqual([reader.MODEL_PROFILE, reader.RELATIONSHIP_PROFILE], self.profiles)

    @unittest.skipUnless(os.environ.get('VARVIS_PP2_PROFILE_PRECHANGE_FIXTURE'), 'Explicit pinned historical source/core cache fixture required')
    def test_original_prechange_ps2_receipt_is_not_resigned_as_new_question_coverage(self):
        archive = Path(os.environ['VARVIS_PP2_PROFILE_PRECHANGE_FIXTURE'])
        self.assertEqual('fa2a5a5bac3a60cd3929713ffad6ccf284ab71b03b07e82868568da9f127a2eb', digest(archive.read_bytes()))
        historical = self.root / 'historical'
        with zipfile.ZipFile(archive) as saved:
            self.assertTrue(all(not Path(n).is_absolute() and '..' not in Path(n).parts for n in saved.namelist()))
            saved.extractall(historical)
        baseline = parse((historical / 'baseline.json').read_bytes())
        self.config.update(library=str(historical / 'library'), modelBudget=str(historical / 'budget'), allowModel=False)
        (self.root / 'key.txt').unlink()
        before = (historical / 'budget/budget.json').read_bytes()
        candidate = self.candidates()[0]; candidate['localReviewedBundleSha256'] = baseline['bundleSha256']
        output = self.run_scope(candidates=[candidate], known=[], model_transport=lambda *unused: self.fail('old cache read invoked provider'))
        self.assertEqual(0, output['modelCalls']); self.assertEqual(0, self.calls)
        self.assertTrue(output['papers'][0]['checkedChunks'], output['pending'])
        core = output['papers'][0]['checkedChunks'][0]
        self.assertEqual('PS2', core['originalExtractionCriterion'])
        self.assertEqual(digest(canonical(baseline['originalArtifact']['receipt'])), core['receiptSha256'])
        artifact = parse((self.root / 'first' / output['originals'][core['artifactRawSha256']]['relativePath']).read_bytes())
        self.assertEqual(baseline['implementation'], artifact['binding']['implementation'])
        self.assertEqual('NOT_ESTABLISHED', core['otherPhenotypeQuestionCoverage'])
        self.assertEqual([], output['papers'][0]['relationshipChunks'])
        self.assertFalse(output['readingComplete'])
        self.assertEqual(before, (historical / 'budget/budget.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
