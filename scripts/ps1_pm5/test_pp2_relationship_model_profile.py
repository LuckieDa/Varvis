"""Actual synthetic runner/cache/replay controls for raw PP2 questions only."""
import base64
from copy import deepcopy
import os
from pathlib import Path
import sys
import unittest
import zipfile

import pp2_relationship_material as material
import test_pp2_relationship_material as material_fixture

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from bundle_model_contract import requests, load, review, implementation
from bundle_model_transport import decode_response
from model_literature_contract import canonical, digest, parse
from run_bundle_model import run, replay, requalify_saved, identity
from test_bundle_model import configuration


PROFILE = 'pp2-relationship-material-v1'
PRECHANGE_ARCHIVE_SHA = 'fa2a5a5bac3a60cd3929713ffad6ccf284ab71b03b07e82868568da9f127a2eb'


class RelationshipModelProfileTest(unittest.TestCase):
    def setUp(self):
        fixture = material_fixture.RelationshipMaterialTest(
            'test_functional_and_cohort_materials_are_retained_without_any_case_label')
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.fixture = fixture
        self.root, self.library, self.source = fixture.root, fixture.library, fixture.source
        self.pin = self.source['bundleSha256']
        self.c = configuration()
        self.config = self.root / 'model.json'; self.config.write_bytes(canonical(self.c))
        self.key = self.root / 'key.txt'; self.key.write_text('synthetic-profile-credential-not-real', encoding='utf8')
        self.state = self.root / 'budget'
        self.calls = 0
        self.plans = requests(self.source, self.c['model'], self.c['maxOutputTokens'],
                              self.c['maxRequestBytes'], PROFILE)
        self.index = next(i for i, p in enumerate(self.plans) if any(
            u['text'] == fixture.functional and u['unitId'] in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds']
            for u in parse(p['messages'][1]['content'])['units']))

    def response(self, c, key, body):
        self.calls += 1
        payload = parse(body)
        candidate = self.fixture.candidate(payload)
        return self.protocol(candidate, c)

    def protocol(self, candidate, c=None):
        c = c or self.c
        return canonical(dict(model=c['expectedResponseModel'],
            usage=dict(prompt_tokens=10, completion_tokens=20, total_tokens=30),
            choices=[dict(finish_reason='stop', message=dict(content=canonical(candidate).decode()))]))

    def execute(self, name='first', **kwargs):
        return run(self.library, 'PMC123', self.pin, self.c, self.config, self.state, self.root / name,
                   kwargs.pop('transport', self.response), chunk_index=self.index,
                   extraction_profile=kwargs.pop('extraction_profile', PROFILE),
                   criterion=kwargs.pop('criterion', 'PP2'), **kwargs)

    def artifact(self, name='first'):
        return parse((self.root / name / 'artifact.json').read_bytes())

    def test_actual_new_request_invoke_artifact_replay_and_cache_without_credentials(self):
        first = self.execute()
        self.assertEqual(1, first['modelCalls'])
        artifact = self.artifact()
        self.assertEqual(PROFILE, artifact['binding']['extractionProfile'])
        self.assertEqual('PP2', artifact['binding']['criterion'])
        self.assertEqual(digest(Path(material.__file__).read_bytes()),
                         artifact['binding']['implementation']['pp2_relationship_material.py'])
        self.assertEqual(digest(canonical(self.plans[self.index])), artifact['requestSha256'])
        self.assertEqual('SYNTHETIC_TEST', artifact['receipt']['transportKind'])
        self.assertEqual(first['review'], replay(self.library, self.root / 'first' / 'artifact.json', criterion='PP2'))
        self.assertEqual('NOT_GRANTED', first['review']['relationQualification'])
        self.assertFalse(first['review']['scientificAdoption'])
        self.key.unlink()
        second = self.execute('cached', transport=lambda *args: self.fail('cached replay called provider'))
        self.assertEqual(0, second['modelCalls'])
        self.assertTrue(second['cacheHit'])
        self.assertEqual(1, self.calls)
        self.assertEqual(artifact['binding'], self.artifact('cached')['binding'])
        self.assertEqual(artifact['receipt'], self.artifact('cached')['receipt'])

    def test_cache_only_miss_never_reads_credentials_or_reserves_budget(self):
        self.key.unlink()
        result = self.execute(cache_only=True, transport=lambda *args: self.fail('cache-only miss called provider'))
        self.assertIsNone(result)
        self.assertFalse((self.state / 'budget.json').exists())
        self.assertFalse((self.root / 'first').exists())

    def test_new_profile_rejects_other_purposes_before_source_or_model_execution(self):
        for purpose in ('PS2', 'PM6', 'PM3', 'PP1', 'PVS1'):
            with self.subTest(purpose=purpose), self.assertRaisesRegex(ValueError, 'RELATIONSHIP_PROFILE_REQUIRES_PP2'):
                self.execute('wrong-' + purpose, criterion=purpose,
                             transport=lambda *args: self.fail('wrong purpose called provider'))
            with self.subTest(identity_purpose=purpose), self.assertRaises(ValueError):
                identity('PMC123', self.pin, 0, len(self.plans), PROFILE, criterion=purpose)
        self.assertEqual(0, self.calls)
        self.assertFalse(self.state.exists())

    def test_replay_refuses_saved_wrong_profile_or_missing_question_implementation(self):
        self.execute()
        original = self.artifact()
        for mutation in ('purpose', 'profile', 'question-pin'):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                artifact = deepcopy(original)
                if mutation == 'purpose':
                    artifact['binding']['criterion'] = 'PS2'
                elif mutation == 'profile':
                    artifact['binding']['extractionProfile'] = 'core-material-v2'
                else:
                    del artifact['binding']['implementation']['pp2_relationship_material.py']
                path = self.root / ('mutated-' + mutation + '.json')
                path.write_bytes(canonical(artifact))
                replay(self.library, path)

    def test_contract_requires_actual_payload_and_rejects_core_response_for_new_questions(self):
        payload = self.plans[self.index]
        candidate = self.fixture.candidate(payload)
        with self.assertRaisesRegex(ValueError, 'RELATIONSHIP_ACTUAL_REQUEST_REQUIRED'):
            review(self.source, candidate)
        core = dict(schemaVersion='denovo-core-material-candidates-v1', cases=[],
                    coverage='EXTRACTED_WITHIN_TARGET', sourceAssessments=[])
        with self.assertRaisesRegex(ValueError, 'CANDIDATE_ACTUAL_REQUEST_PROFILE_MISMATCH'):
            review(self.source, core, actual_payload=payload)
        changed = deepcopy(payload)
        content = parse(changed['messages'][1]['content'])
        content['units'][0]['text'] = 'altered actual source view'
        changed['messages'][1]['content'] = canonical(content).decode()
        with self.assertRaises(ValueError):
            review(self.source, candidate, actual_payload=changed)

    def test_decoder_and_failed_budget_do_not_accept_causal_or_old_core_schema(self):
        candidate = self.fixture.candidate(self.plans[self.index])
        candidate['causesOther'] = True
        with self.assertRaisesRegex(ValueError, 'SCHEMA_INVALID'):
            decode_response(self.protocol(candidate), self.c, material.CANDIDATE_VERSION)
        core = dict(schemaVersion='denovo-core-material-candidates-v1', cases=[],
                    coverage='EXTRACTED_WITHIN_TARGET', sourceAssessments=[])
        with self.assertRaisesRegex(ValueError, 'PROFILE_MISMATCH'):
            decode_response(self.protocol(core), self.c, material.CANDIDATE_VERSION)
        def malformed(c, key, body):
            self.calls += 1
            return self.protocol(candidate, c)
        with self.assertRaises(ValueError):
            self.execute(transport=malformed)
        state = parse((self.state / 'budget.json').read_bytes())
        self.assertEqual('FAILED_RESERVED', state['calls'][0]['status'])
        self.assertTrue(state['stoppedReason'])
        folder = self.state / 'responses' / state['calls'][0]['key']
        self.assertTrue((folder / 'response.json').is_file())
        self.assertFalse((folder / 'receipt.json').exists())
        self.assertEqual(1, self.calls)

    def test_saved_response_with_wrong_literal_quote_is_refused_even_after_matching_raw_pin(self):
        self.execute()
        artifact = self.artifact()
        response = parse(base64.b64decode(artifact['responseBase64'], validate=True))
        candidate = parse(response['choices'][0]['message']['content'])
        candidate['records'][0]['facts'][0]['citation']['quote'] = 'Invented experimental observation'
        response['choices'][0]['message']['content'] = canonical(candidate).decode()
        raw = canonical(response)
        artifact['responseBase64'] = base64.b64encode(raw).decode()
        artifact['receipt']['responseSha256'] = digest(raw)
        path = self.root / 'wrong-literal.json'; path.write_bytes(canonical(artifact))
        with self.assertRaisesRegex(ValueError, 'CITATION_NOT_EXACT_OR_AMBIGUOUS'):
            replay(self.library, path)

    def test_original_source_change_cannot_be_replaced_by_derived_view_or_cached_response(self):
        self.execute()
        path = self.library / 'papers' / 'PMC123' / 'PMC123.xml'
        path.write_bytes(path.read_bytes().replace(b'30% activity', b'90% activity'))
        with self.assertRaises(ValueError):
            replay(self.library, self.root / 'first' / 'artifact.json')

    def test_requalify_reconstructs_new_payload_and_preserves_actual_original_receipt(self):
        self.execute()
        artifact = self.artifact()
        receipt = artifact['receipt']
        key = digest(canonical(receipt['identity']))
        folder = self.state / 'responses' / key
        self.key.unlink()
        result = requalify_saved(self.library, folder / 'request.json', folder / 'response.json',
                                 folder / 'receipt.json', digest(canonical(receipt)), self.root / 'offline')
        self.assertEqual(artifact['review'], result)
        saved = self.artifact('offline')
        self.assertEqual(receipt, saved['receipt'])
        self.assertEqual('PP2', saved['binding']['criterion'])
        self.assertEqual(PROFILE, saved['binding']['extractionProfile'])
        self.assertEqual(0, saved['offlineRequalification']['modelCalls'])
        self.assertEqual(0, saved['offlineRequalification']['networkCalls'])
        self.assertEqual(1, self.calls)
        self.assertEqual(result, replay(self.library, self.root / 'offline' / 'artifact.json', criterion='PP2'))
        changed = self.root / 'changed-request.json'
        altered = parse((folder / 'request.json').read_bytes())
        altered['messages'][0]['content'] = 'Changed question'
        changed.write_bytes(canonical(altered))
        with self.assertRaisesRegex(ValueError, 'ORIGINAL_REQUEST_CHANGED'):
            requalify_saved(self.library, changed, folder / 'response.json', folder / 'receipt.json',
                             digest(canonical(receipt)), self.root / 'rejected-offline')

    @unittest.skipUnless(os.environ.get('VARVIS_PP2_PROFILE_PRECHANGE_FIXTURE'),
                         'Explicit pinned prechange cache fixture required')
    def test_actual_24f_core_cache_and_default_request_bytes_remain_compatible(self):
        archive = Path(os.environ['VARVIS_PP2_PROFILE_PRECHANGE_FIXTURE'])
        self.assertEqual(PRECHANGE_ARCHIVE_SHA, digest(archive.read_bytes()))
        historical = self.root / 'historical'
        with zipfile.ZipFile(archive) as saved:
            self.assertTrue(all(not Path(name).is_absolute() and '..' not in Path(name).parts
                                for name in saved.namelist()))
            saved.extractall(historical)
        baseline = parse((historical / 'baseline.json').read_bytes())
        source = load(historical / 'library', 'PMC123', baseline['bundleSha256'], criterion='PS2')
        self.assertEqual(canonical(baseline['defaultRequests']), canonical(requests(
            source, self.c['model'], self.c['maxOutputTokens'], self.c['maxRequestBytes'])))
        self.assertEqual(canonical(baseline['coreRequests']), canonical(requests(
            source, self.c['model'], self.c['maxOutputTokens'], self.c['maxRequestBytes'], 'core-material-v2')))
        self.key.unlink()
        value = run(historical / 'library', 'PMC123', baseline['bundleSha256'], self.c,
                    self.config, historical / 'budget', self.root / 'old-cache',
                    lambda *args: self.fail('existing old cache called provider'),
                    extraction_profile='core-material-v2', criterion='PS2')
        self.assertTrue(value['cacheHit'])
        self.assertEqual(0, value['modelCalls'])
        current = self.artifact('old-cache')
        self.assertEqual(baseline['originalArtifact']['receipt'], current['receipt'])
        self.assertEqual(baseline['implementation'], current['binding']['implementation'])
        self.assertNotIn('criterion', current['binding'])
        self.assertEqual(value['review'], replay(historical / 'library',
                                                self.root / 'old-cache' / 'artifact.json', criterion='PS2'))
        # The old exact implementation is permitted for its old purpose, never
        # retrospectively endowed with a question module that did not exist.
        modified = deepcopy(current)
        modified['binding']['extractionProfile'] = PROFILE
        modified['binding']['criterion'] = 'PP2'
        mutated = self.root / 'fake-historical-question.json'; mutated.write_bytes(canonical(modified))
        with self.assertRaisesRegex(ValueError, 'RELATIONSHIP_QUESTION_IMPLEMENTATION_REQUIRED'):
            replay(historical / 'library', mutated)


if __name__ == '__main__':
    unittest.main()

