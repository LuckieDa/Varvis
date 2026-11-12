"""Synthetic source/transport controls. No live model or scientific adoption."""
from copy import deepcopy
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from test_bundle_qualification import article, registered, candidate
from test_bundle_model import configuration, response
from test_literature_continuation import candidate_response, parent_for
from model_literature_contract import canonical, digest, parse
from bundle_model_contract import requests, review, check_candidate, implementation
from bundle_model_transport import decode_response, cache_identity
from run_bundle_model import run, replay, identity
from bundle_replay_compatibility import reviewed_profiles, compatible
from core_extraction_prompt import PROFILE, CANDIDATE_VERSION, GROUP_GUIDE
from core_material_extraction import write_package
from core_material_contract import facts as all_facts
from core_material_schema import GROUPS
from qualify_bundle_candidates import qualify
from continue_literature_paper import process_paper
import test_bundle_model as attachment_fixture
import public_literature_workflow as workflow


EXTRA = (' The proband was five years old, onset in infancy, with ataxia and no seizures. '
         'Mother carried no target variant; father not tested. Trio Sanger sequencing was used. '
         'Low mosaicism was not assessed. One unaffected sister. Family F1. '
         'The mother had migraine. Previously published in Paper X. '
         'A later record states six years old.')


class CoreExtractionTests(unittest.TestCase):
    def test_v2_body_message_has_same_closed_schema_without_rewriting_v1(self):
        v1 = requests(self.source, self.c['model'], self.c['maxOutputTokens'],
                      self.c['maxRequestBytes'], 'core-material-v1')
        v2 = requests(self.source, self.c['model'], self.c['maxOutputTokens'],
                      self.c['maxRequestBytes'], 'core-material-v2')
        self.assertEqual(len(v1), len(v2))
        for old, new in zip(v1, v2):
            before, after = [parse(p['messages'][1]['content']) for p in (old, new)]
            self.assertNotIn('outputSchema', before)
            self.assertEqual(before['units'], after['units'])
            self.assertEqual(before['workUnit'], after['workUnit'])
            self.assertEqual(after['outputSchema'], new['response_format']['json_schema']['schema'])
            self.assertEqual(after['coreMaterialExtraction']['profile'], 'core-material-v2')
        # The compatibility path reconstructs the old payload, not the new schema hint.
        self.assertEqual(v1, requests(self.source, self.c['model'], self.c['maxOutputTokens'],
                                    self.c['maxRequestBytes'], 'core-material-v1'))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        raw = article().replace(b'Case A was studied.', ('Case A was studied.' + EXTRA).encode())
        self.library, self.source = registered(self.root, raw)
        self.c = configuration(); self.config = self.root / 'config.json'
        self.config.write_bytes(canonical(self.c)); (self.root / 'key.txt').write_text('synthetic-not-secret')
        self.pmcid = self.source['pmcid']; self.pin = self.source['bundleSha256']

    def proposed(self):
        value = candidate(self.source); value['schemaVersion'] = CANDIDATE_VERSION
        row = value['cases'][0]; uid = row['caseCitation']['unitId']
        # Short exact quotes, including negative findings and non-proband claims.
        fields = [('geneSymbol', 'EXAMPLE', 'PROBAND'), ('variantText', 'c.123A>G', 'PROBAND'),
                  ('proteinChange', 'p.T41A', 'PROBAND'), ('reportedAge', 'five years old', 'PROBAND'),
                  ('reportedOnset', 'infancy', 'PROBAND'), ('reportedPhenotype', 'ataxia', 'PROBAND'),
                  ('reportedPhenotypeNegation', 'no seizures', 'PROBAND'),
                  ('reportedMotherTargetResult', 'Mother carried no target variant', 'MOTHER'),
                  ('reportedFatherTargetResult', 'father not tested', 'FATHER'),
                  ('reportedVariantTestingMethod', 'Trio Sanger sequencing', 'FAMILY'),
                  ('reportedTestingLimitations', 'Low mosaicism was not assessed', 'FAMILY'),
                  ('reportedSiblings', 'One unaffected sister', 'FAMILY'),
                  ('reportedFamilyId', 'F1', 'FAMILY'), ('reportedPhenotype', 'migraine', 'MOTHER'),
                  ('reportedPreviousPublication', 'Previously published in Paper X', 'PROBAND'),
                  ('reportedAge', 'six years old', 'PROBAND')]
        row['fields'] = [{'field': n, 'sourceValue': v, 'subject': s, 'citation': {'unitId': uid, 'quote': v}}
                         for n, v, s in fields]
        value['sourceAssessments'][0]['citation']['quote'] = 'Case A was studied.'
        return value

    def checked(self, value=None):
        result = review(self.source, value or self.proposed())
        uid = result['cases'][0]['caseCitation']['unitId']
        result['workUnit'] = {'targetUnitIds': [uid]}
        return result

    def package(self, value=None, output='package'):
        checked = self.checked(value); view = qualify(self.source, [checked])
        index = write_package(self.source, view, [checked], self.root / output, data_class='SYNTHETIC')
        doc = parse((self.root / output / index['cases'][0]['path']).read_bytes())
        return index, doc

    def test_new_profile_has_twelve_groups_old_default_unchanged(self):
        old = requests(self.source); explicit = requests(self.source, extraction_profile='legacy-v1')
        self.assertEqual(canonical(old), canonical(explicit))
        new = requests(self.source, extraction_profile=PROFILE)
        self.assertNotEqual(canonical(old), canonical(new)); self.assertEqual(len(old), len(new))
        self.assertEqual(set(GROUPS), {r['group'] for r in GROUP_GUIDE})
        content = parse(new[0]['messages'][1]['content'])
        self.assertEqual('NOT_EXTRACTED_NOT_ABSENT', content['coreMaterialExtraction']['missingMeaning'])
        self.assertNotIn('and eight fields per case', new[0]['messages'][0]['content'])

    def test_sixteen_fields_all_retained_and_seventeenth_rejected(self):
        checked = self.checked(); self.assertEqual(16, len(checked['cases'][0]['fields']))
        self.assertEqual([f['field'] for f in self.proposed()['cases'][0]['fields']],
                         [f['field'] for f in checked['cases'][0]['fields']])
        bad = self.proposed(); bad['cases'][0]['fields'].append(deepcopy(bad['cases'][0]['fields'][0]))
        with self.assertRaisesRegex(ValueError, 'SCHEMA_INVALID'): check_candidate(bad)

    def test_duplicate_labels_across_cases_rejected(self):
        value = self.proposed(); value['cases'].append(deepcopy(value['cases'][0]))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_CASE_LABEL'): review(self.source, value)

    def test_exact_body_section_can_anchor_a_quote_without_repeated_case_number(self):
        value = self.proposed()
        value['cases'][0]['caseCitation']['quote'] = 'The EXAMPLE variant c.123A>G, p.T41A, was observed.'
        checked = self.checked(value)
        self.assertEqual('A', checked['cases'][0]['caseLabel'])
        self.assertFalse(checked['businessPublishable'])
        self.assertEqual(value['cases'][0]['caseCitation']['quote'], checked['cases'][0]['caseCitation']['quote'])

    def test_section_anchor_does_not_authorize_other_case_or_unowned_methods(self):
        for mode in ('wrong-case', 'methods'):
            value = self.proposed(); row = value['cases'][0]
            row['caseCitation']['quote'] = 'The EXAMPLE variant c.123A>G, p.T41A, was observed.'
            if mode == 'wrong-case': row['caseLabel'] = 'B'
            else: row['caseCitation'] = deepcopy(row['originCitations'][0])
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, 'CASE_LABEL_NOT_ANCHORED'):
                self.checked(value)

    def test_section_anchor_rejects_a_paragraph_that_also_mentions_another_case(self):
        folder = self.root / 'mixed-case'; folder.mkdir()
        raw = article().replace(b'Case A was studied.', b'Case A was studied. Case B was also discussed.')
        library, source = registered(folder, raw)
        value = candidate(source); value['schemaVersion'] = CANDIDATE_VERSION
        value['cases'][0]['caseCitation']['quote'] = 'The EXAMPLE variant c.123A>G, p.T41A, was observed.'
        with self.assertRaisesRegex(ValueError, 'CASE_LABEL_NOT_ANCHORED'):
            review(source, value)

    def test_quotes_actual_input_and_targets_still_enforced(self):
        for mode in ('quote', 'value', 'unsent', 'target'):
            value = self.proposed()
            if mode == 'quote': value['cases'][0]['fields'][10]['citation']['quote'] = 'fabricated'
            if mode == 'value': value['cases'][0]['fields'][10]['sourceValue'] = 'fabricated'
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                review(self.source, value, set() if mode == 'unsent' else None, set() if mode == 'target' else None)

    def test_profile_mismatch_and_nonobjects_are_rejected(self):
        for value in ([], None, 7, {'schemaVersion': 'fake'}, candidate(self.source)):
            with self.subTest(value=type(value).__name__), self.assertRaises(ValueError):
                decode_response(response(value), self.c, CANDIDATE_VERSION)

    def test_no_scoring_or_review_authority_in_model_schema(self):
        for key in ('verification', 'evidenceStrength', 'readyForEvaluation'):
            value = self.proposed(); value[key] = True
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'SCHEMA_INVALID'): check_candidate(value)

    def test_twelve_groups_missingness_and_all_unverified(self):
        index, doc = self.package()
        self.assertEqual(set(GROUPS), set(doc['groups']))
        self.assertFalse(index['businessPublishable']); self.assertEqual(0, index['modelCalls'])
        self.assertFalse(any(f['verification'] == 'VERIFIED' for f in all_facts(doc).values()))
        self.assertEqual('NOT_EXTRACTED', doc['context']['policyId']['availability'])
        self.assertEqual('NOT_EXTRACTED', doc['groups']['proband']['mosaicism']['availability'])
        self.assertEqual('NOT_RUN', doc['reviewPlan']['secondPassStatus'])
        self.assertEqual([], doc['reviewPlan']['checkedScope'])

    def test_raw_negative_parent_test_not_semantically_adopted(self):
        unused, doc = self.package()
        mother = doc['groups']['mother']['targetResult']; father = doc['groups']['father']['targetResult']
        self.assertEqual('Mother carried no target variant', mother['rawValue'])
        self.assertIsNone(mother['value']); self.assertEqual('MOTHER', mother['subject'])
        self.assertEqual('father not tested', father['rawValue']); self.assertIsNone(father['value'])
        self.assertEqual('NOT_EXTRACTED', doc['groups']['parentage']['paternity']['availability'])

    def test_maternal_phenotype_and_conflicting_age_preserved(self):
        index, doc = self.package()
        self.assertNotIn('migraine', doc['groups']['phenotype']['positive']['value'])
        observations = parse((self.root / 'package/observations.json').read_bytes())
        maternal = [observations[i] for i in index['cases'][0]['unassignedClaims']]
        self.assertTrue(any(r['rawField']['sourceValue'] == 'migraine' and r['subject'] == 'MOTHER' for r in maternal))
        age = doc['groups']['phenotype']['age']; self.assertIsNone(age['value'])
        self.assertIn('five years old', age['rawValue']); self.assertIn('six years old', age['rawValue'])
        self.assertIn('MULTIPLE_SOURCE_VALUES_REQUIRE_RECONCILIATION', age['issues'])

    def test_every_candidate_claim_and_originals_retained(self):
        checked = self.checked(); view = qualify(self.source, [checked]); before = canonical([self.source['main'], view, checked])
        write_package(self.source, view, [checked], self.root / 'package', intake={'synthetic': True})
        observations = parse((self.root / 'package/observations.json').read_bytes())
        keys = {digest(canonical(r['rawField'])) for r in observations}
        self.assertTrue(all(digest(canonical(f)) in keys for f in checked['cases'][0]['fields']))
        self.assertEqual(before, canonical([self.source['main'], view, checked]))
        self.assertEqual(self.source['raw'], (self.root / 'package/main.xml').read_bytes())
        self.assertEqual(canonical(view), (self.root / 'package/source-facts.json').read_bytes())

    def test_no_case_is_not_absence(self):
        value = self.proposed(); value['cases'] = []
        checked = review(self.source, value); checked['workUnit'] = {'targetUnitIds': []}
        view = qualify(self.source, [checked])
        index = write_package(self.source, view, [checked], self.root / 'empty')
        self.assertEqual([], index['cases']); self.assertEqual('EXTRACTED_NOT_VERIFIED', index['status'])
        self.assertEqual('NOT_EXTRACTED_IS_NOT_PROOF_OF_ABSENCE', index['missingMeaning'])

    def test_package_never_overwrites_and_source_pin_must_match(self):
        self.package()
        with self.assertRaisesRegex(ValueError, 'NEW_OUTPUT_REQUIRED'): self.package()
        checked = self.checked(); view = qualify(self.source, [checked]); view['sourceSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'SOURCE_MISMATCH'):
            write_package(self.source, view, [checked], self.root / 'bad')

    def test_unassigned_claim_citation_is_also_checked(self):
        checked = self.checked(); view = qualify(self.source, [checked])
        checked['cases'][0]['fields'][13]['citation']['quote'] = 'forged mother'
        with self.assertRaisesRegex(ValueError, 'CITATION_MISMATCH'):
            write_package(self.source, view, [checked], self.root / 'bad')

    def test_supplement_failure_explicit_and_bounded_package_not_truncated(self):
        source = deepcopy(self.source)
        source['coverage'] = {'status': 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED', 'reasonCode': 'PAPER_DOWNLOAD_TIMEOUT'}
        checked = self.checked(); view = qualify(source, [checked])
        index = write_package(source, view, [checked], self.root / 'missing')
        doc = parse((self.root / 'missing' / index['cases'][0]['path']).read_bytes())
        self.assertEqual('PARTIAL', doc['groups']['paper']['availability']['value'])
        self.assertIn('SUPPLEMENT_SCOPE:PAPER_DOWNLOAD_TIMEOUT', doc['reviewPlan']['unavailableSources'])
        with patch('core_material_extraction.MAX_PACKAGE', 1), self.assertRaisesRegex(ValueError, 'PACKAGE_LIMIT_NOT_TRUNCATED'):
            write_package(source, view, [checked], self.root / 'limited')
        self.assertFalse((self.root / 'limited').exists())

    def test_new_profile_transport_cache_replay_without_credentials(self):
        value = self.proposed(); uid = value['cases'][0]['caseCitation']['unitId']
        plans = requests(self.source, max_request_bytes=self.c['maxRequestBytes'], extraction_profile=PROFILE)
        idx = next(i for i, p in enumerate(plans) if uid in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])
        calls = []
        def transport(*args): calls.append(1); return response(value)
        first = run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget', self.root / 'a',
                    transport, chunk_index=idx, extraction_profile=PROFILE)
        original = (self.root / 'a/artifact.json').read_bytes()
        (self.root / 'key.txt').unlink()
        second = run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget', self.root / 'b',
                     transport, chunk_index=idx, cache_only=True, extraction_profile=PROFILE)
        self.assertEqual([1], calls); self.assertEqual(0, second['modelCalls'])
        self.assertEqual(original, (self.root / 'b/artifact.json').read_bytes())
        self.assertEqual(first['review'], replay(self.library, self.root / 'b/artifact.json'))

    def test_legacy_cache_not_misrepresented_as_new_model_extraction(self):
        original = candidate(self.source); uid = original['cases'][0]['caseCitation']['unitId']
        plans = requests(self.source, max_request_bytes=self.c['maxRequestBytes'])
        idx = next(i for i, p in enumerate(plans) if uid in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])
        run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget', self.root / 'old',
            lambda *a: response(original), chunk_index=idx)
        with patch('run_bundle_model.credentials', side_effect=AssertionError('must not request credentials')):
            self.assertIsNone(run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget',
                                  self.root / 'new', chunk_index=idx, cache_only=True, extraction_profile=PROFILE))

    def test_known_core_profile_reader_upgrade_reuses_exact_cache_without_credentials(self):
        value = self.proposed(); uid = value['cases'][0]['caseCitation']['unitId']
        plans = requests(self.source, max_request_bytes=self.c['maxRequestBytes'], extraction_profile=PROFILE)
        idx = next(i for i, p in enumerate(plans) if uid in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])
        calls = []
        def transport(*args): calls.append(1); return response(value)
        with patch('run_bundle_model.implementation', return_value=reviewed_profiles()[0]):
            old = run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget',
                      self.root / 'old-core', transport, chunk_index=idx, extraction_profile=PROFILE)
        original = (self.root / 'old-core/artifact.json').read_bytes()
        budget = (self.root / 'budget/budget.json').read_bytes()
        (self.root / 'key.txt').unlink()
        with patch('run_bundle_model.credentials', side_effect=AssertionError('no paid call')):
            now = run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget',
                      self.root / 'now-core', transport, chunk_index=idx, cache_only=True,
                      extraction_profile=PROFILE, expected_artifact_sha256=old['artifactSha256'])
        self.assertIsNotNone(now)
        self.assertEqual(0, now['modelCalls']); self.assertEqual([1], calls)
        self.assertEqual(budget, (self.root / 'budget/budget.json').read_bytes())
        self.assertEqual(original, (self.root / 'now-core/artifact.json').read_bytes())
        self.assertEqual(now['review'], replay(self.library, self.root / 'now-core/artifact.json'))

    def test_only_known_whole_implementation_profiles_accepted(self):
        old = reviewed_profiles()[0]
        self.assertTrue(compatible(old, implementation()))
        bad = {**old, 'unknown.py': '0' * 64}; self.assertFalse(compatible(bad, implementation()))

    def test_known_legacy_cache_and_parent_bytes_survive_reader_upgrade(self):
        proposed = candidate(self.source); uid = proposed['cases'][0]['caseCitation']['unitId']
        plans = requests(self.source, max_request_bytes=self.c['maxRequestBytes'])
        idx = next(i for i, p in enumerate(plans) if uid in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])
        calls = []
        def transport(*args): calls.append(1); return response(proposed)
        with patch('run_bundle_model.implementation', return_value=reviewed_profiles()[0]):
            old = run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget',
                      self.root / 'old', transport, chunk_index=idx)
        original = (self.root / 'old/artifact.json').read_bytes()
        (self.root / 'key.txt').unlink()
        now = run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget',
                  self.root / 'now', transport, chunk_index=idx, cache_only=True,
                  expected_artifact_sha256=old['artifactSha256'])
        self.assertEqual([1], calls); self.assertEqual(0, now['modelCalls'])
        self.assertEqual(original, (self.root / 'now/artifact.json').read_bytes())
        self.assertEqual(now['review'], replay(self.library, self.root / 'now/artifact.json'))
        with self.assertRaisesRegex(ValueError, 'CONTINUATION_CACHE_CHANGED'):
            run(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget',
                self.root / 'bad', transport, chunk_index=idx, cache_only=True, expected_artifact_sha256='0' * 64)
        self.assertEqual([1], calls)

    def test_attachment_raw_units_and_unread_pdf_preserved(self):
        fixture = attachment_fixture.BundleModelTests(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        value = fixture.candidate(); value['schemaVersion'] = CANDIDATE_VERSION
        checked = review(fixture.source, value); checked['workUnit'] = {'targetUnitIds': []}
        view = qualify(fixture.source, [checked])
        index = write_package(fixture.source, view, [checked], self.root / 'attachment', data_class='SYNTHETIC')
        doc = parse((self.root / 'attachment' / index['cases'][0]['path']).read_bytes())
        gene = doc['groups']['gene']['symbol']
        self.assertEqual('GENE1', gene['value']); self.assertEqual('UNVERIFIED', gene['verification'])
        self.assertTrue(any(c['sourceId'] == 'units' for c in gene['citations']))
        self.assertTrue(any('unknown.pdf:ARCHIVED_NOT_PARSED' in s for s in doc['reviewPlan']['unavailableSources']))
        self.assertFalse(any(f['verification'] == 'VERIFIED' for f in all_facts(doc).values()))

    def test_continuation_emits_package_and_reuses_without_paid_call(self):
        calls = []
        def transport(unused, key, body):
            calls.append(body)
            outer = parse(candidate_response(body)); inner = parse(outer['choices'][0]['message']['content'])
            inner['schemaVersion'] = CANDIDATE_VERSION
            return response(inner)
        def extract(*args, **kwargs): return run(*args, **kwargs, transport=transport)
        parent = None
        with patch('continue_literature_paper.extract', side_effect=extract):
            for turn in range(16):
                work = self.root / f'pass-{turn}'; work.mkdir()
                result = {'papers': [], 'issues': [], 'modelCalls': 0, 'modelAttempts': 0,
                          'estimatedMicroCreditsThisRun': 0, 'chargedOrReservedMicroCreditsThisRun': 0}
                item = process_paper(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget',
                                     work, result, lambda: None, time.monotonic() + 60, True, parent, PROFILE)
                if not item['continuation']['eligible']: break
                parent = parent_for(item)
            self.assertTrue(item['completeTargetExtraction'], item['issues'])
            self.assertIn('coreMaterial', item, item['issues'])
            self.assertEqual('EXTRACTED_NOT_VERIFIED', item['coreMaterial']['status'])
            self.assertEqual('SOURCE_REVIEWED_CONTEXT_PENDING', item['coreReview']['status'])
            self.assertEqual('CONTEXT_REVIEWED_EVALUATION_PENDING', item['coreContext']['status'])
            self.assertFalse(item['coreContext']['businessPublishable'])
            before = len(calls); work = self.root / 'reuse'; work.mkdir()
            result.update(papers=[], modelCalls=0, modelAttempts=0, issues=[])
            again = process_paper(self.library, self.pmcid, self.pin, self.c, self.config, self.root / 'budget',
                                  work, result, lambda: None, time.monotonic() + 60, False, parent_for(item), PROFILE)
            self.assertEqual(before, len(calls)); self.assertEqual(0, result['modelCalls'])
            self.assertEqual(item['coreMaterial']['sha256'], again['coreMaterial']['sha256'])
            self.assertEqual(item['coreReview']['sha256'], again['coreReview']['sha256'])
            self.assertEqual(item['coreContext']['sha256'], again['coreContext']['sha256'])

    def test_workflow_selects_profile_and_requires_core_output_before_ready(self):
        local = self.root / 'local'; local.mkdir()
        (local / 'model.json').write_bytes(canonical(self.c))
        config = {'schemaVersion': workflow.VERSION, 'enabled': True, 'projectRoot': str(self.root),
                  'library': 'local/library', 'searchCache': 'local/search', 'modelBudget': 'local/budget',
                  'modelConfig': 'local/model.json', 'allowModel': False, 'extractionProfile': PROFILE}
        path = self.root / 'workflow-config.json'; path.write_bytes(canonical(config))
        request = self.root / 'request.json'
        request.write_bytes(canonical({'schemaVersion': workflow.VERSION, 'genes': ['EXAMPLE'], 'selectionSha256': 'a' * 64,
            'continuation': {'parentTaskId': 'a' * 64, 'parentResultSha256': 'b' * 64, 'pmcid': self.pmcid,
                             'bundleSha256': self.pin, 'plannedChunks': 1, 'completedTargets': []}}))
        context_raw = canonical({'schemaVersion': 'denovo-core-context-config-v1', 'contexts': []})
        (local / 'context.json').write_bytes(context_raw)
        for package_present, configured, intake_present in ((False, False, False), (True, False, True),
                                                             (True, True, True), (True, True, False)):
            if configured:
                config['coreContext'] = {'path': 'local/context.json', 'sha256': digest(context_raw)}
                path.write_bytes(canonical(config))
            out = self.root / (str(package_present) + str(configured) + str(intake_present)); out.mkdir()
            def process(*args, **kwargs):
                self.assertEqual(PROFILE, args[-1]); self.assertFalse(args[-3])
                self.assertEqual(configured, 'core_context' in kwargs)
                if configured:
                    self.assertEqual(digest(context_raw), kwargs['core_context']['sha256'])
                    self.assertEqual([], kwargs['core_context']['config']['contexts'])
                item = {'completeTargetExtraction': True, 'issues': []}
                if package_present: item['coreMaterial'] = {'status': 'EXTRACTED_NOT_VERIFIED'}
                if package_present: item['coreReview'] = {'status': 'SOURCE_REVIEWED_CONTEXT_PENDING'}
                if package_present: item['coreContext'] = {'status': 'CONTEXT_REVIEWED_EVALUATION_PENDING'}
                if intake_present: item['candidateIntake'] = {'status': 'CANDIDATES_PREPARED_NOT_ADOPTED'}
                args[7]['papers'].append(item)
                return item
            with patch('public_literature_workflow.process_paper', side_effect=process), \
                 patch('public_literature_workflow.discover', side_effect=AssertionError('no discovery')):
                result = workflow.run(request, out / 'result.json', path)
                self.assertEqual('REVIEW_READY' if package_present and intake_present else 'PARTIAL', result['status'])
                self.assertEqual(0, result['modelCalls']); self.assertEqual(0, result['networkRequests'])


if __name__ == '__main__':
    unittest.main()
