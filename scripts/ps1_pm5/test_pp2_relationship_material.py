"""Synthetic literal material controls; no clinical or causal truth claims."""
from copy import deepcopy
import os
from pathlib import Path
import sys
import tempfile
import unittest

import pp2_relationship_material as material

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from acquire_public_literature import source_bundle
from bundle_model_contract import load, requests as core_requests
from model_literature_contract import canonical, digest, parse
from paper_library_registration import register
from prepare_literature import prepare
from test_literature_acquisition import jats
from literature_work_units import plans as native_plans


class RelationshipMaterialTest(unittest.TestCase):
    def setUp(self):
        private = Path(os.environ.get('VARVIS_PP2_MATERIAL_TEST_WORK',
            r'D:/LSD/Project/Code/local/runtime/pp2-material-engineering-tests'))
        private.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='material-', dir=private)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.library = self.root / 'library'; self.library.mkdir()
        self.clinical = ('Case 1 had hypertrophic cardiomyopathy followed by dilation; '
                         'primary dilated cardiomyopathy was not diagnosed.')
        self.functional = ('The variant-specific assay showed 30% activity relative to wild type; '
                           'the authors stated that clinical causality remained uncertain.')
        self.raw = jats(False).replace(b'<p>A public source.</p>',
            ('<sec><title>Clinical report</title><p>' + self.clinical +
             '</p></sec><sec><title>Experiments</title><p>' + self.functional +
             '</p></sec><table-wrap id="t1"><table><tbody>'
             '<tr><th>Allele</th><th>Control</th><th>Observed result</th></tr>'
             '<tr><td>GENE1 p.Arg1Gly</td><td>Wild type</td><td>30% activity</td></tr>'
             '</tbody></table></table-wrap>').encode())
        self.source = self.register(self.raw)
        self.payloads = material.requests(self.source)

    def register(self, raw):
        class Transport:
            def get(unused, *args):
                raise AssertionError('No public transport is allowed in this test')
        files, bundle = source_bundle({'pmcid': 'PMC123'}, raw,
            prepare(raw, 'PMC123', digest(raw)), Transport(), False)
        result = register(self.library, 'PMC123', files, bundle,
            {'sha256': digest(raw), 'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'},
            criteria=('PP2',))
        return load(self.library, 'PMC123', result['bundleSha256'], criterion='PP2')

    def payload_for(self, text):
        return next(p for p in self.payloads if any(u['text'] == text
            and u['unitId'] in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds']
            for u in parse(p['messages'][1]['content'])['units']))

    def candidate(self, payload, *, field='reportedObservationOrResult', material_kind='FUNCTIONAL_STUDY'):
        content = parse(payload['messages'][1]['content'])
        target = next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'] and u['text'])
        ref = dict(unitId=target['unitId'], quote=target['text'])
        return dict(schemaVersion=material.CANDIDATE_VERSION,
            sourceIdentity=deepcopy(content['questionContract']['sourceIdentity']),
            workUnitSha256=content['questionContract']['workUnitSha256'],
            records=[dict(materialKind=material_kind, anchor=deepcopy(ref),
                          facts=[dict(field=field, sourceValue=target['text'], citation=deepcopy(ref))])],
            coverage='EXTRACTED_WITHIN_TARGET',
            sourceAssessments=[dict(materialKind=material_kind, citation=deepcopy(ref))])

    def test_requests_partition_all_original_units_with_distinct_question_contract(self):
        targets = []
        for payload in self.payloads:
            content = parse(payload['messages'][1]['content'])
            targets.extend(content['workUnit']['targetUnitIds'])
            self.assertEqual(material.QUESTION_VERSION, content['questionContract']['questionVersion'])
            self.assertEqual('NOT_EXTRACTED_NOT_ABSENT', content['questionContract']['missingMeaning'])
            self.assertEqual(material.SCHEMA, content['outputSchema'])
            self.assertEqual(material.SCHEMA, payload['response_format']['json_schema']['schema'])
            self.assertLessEqual(len(canonical(payload)), 196608)
            self.assertNotIn('coreMaterialExtraction', content)
        self.assertEqual(sorted(u['unitId'] for u in self.source['units']), sorted(targets))
        self.assertEqual(len(targets), len(set(targets)))
        previous = core_requests(self.source, extraction_profile='core-material-v2')
        self.assertEqual(len(previous), len(self.payloads))
        self.assertTrue(all(canonical(a) != canonical(b) for a, b in zip(previous, self.payloads)))

    def test_long_multi_unit_source_covers_all_targets_with_actual_budget_and_no_context_truncation(self):
        sections = ''.join('<sec><title>Source section ' + str(index) + '</title>'
            '<p>' + ('Literal observation A ' + str(index) + '. ') * 90 + '</p>'
            '<p>' + ('Literal observation B ' + str(index) + '. ') * 90 + '</p></sec>' for index in range(6))
        raw = jats(False).replace(b'<p>A public source.</p>', sections.encode())
        self.library = self.root / 'long-library'; self.library.mkdir()
        source = self.register(raw)
        full = material.requests(source, maxRequestBytes=262144)
        legacy = core_requests(source, max_request_bytes=262144, extraction_profile='legacy-v1')
        self.assertGreaterEqual(len(full), 12)
        self.assertTrue(all(len(canonical(a)) > len(canonical(b)) for a, b in zip(full, legacy)))
        budget = max(len(canonical(p)) for p in full)
        bounded = material.requests(source, maxRequestBytes=budget)
        self.assertEqual(len(full), len(bounded))
        expected = native_plans(source)
        targets = []
        for payload, plan in zip(bounded, expected):
            self.assertLessEqual(len(canonical(payload)), budget)
            content = parse(payload['messages'][1]['content'])
            self.assertEqual(plan, content['workUnit'])
            sent = set(plan['targetUnitIds'] + plan['contextUnitIds'])
            self.assertEqual([u for u in source['units'] if u['unitId'] in sent], content['units'])
            targets.extend(plan['targetUnitIds'])
        self.assertEqual(sorted(u['unitId'] for u in source['units']), sorted(targets))
        self.assertEqual(len(targets), len(set(targets)))
        # Existing work plans are complete semantic units with complete context,
        # not a byte-budget splitter. A smaller limit that fits the old header
        # but cannot fit the real PP2 packet is an explicit technical refusal.
        legacy_only_limit = max(len(canonical(p)) for p in legacy) + 1
        self.assertEqual(len(legacy), len(core_requests(source, max_request_bytes=legacy_only_limit,
                                                       extraction_profile='legacy-v1')))
        with self.assertRaisesRegex(ValueError, 'PP2_RELATIONSHIP_MATERIAL_INPUT_TOO_LARGE_NOT_TRUNCATED'):
            material.requests(source, maxRequestBytes=legacy_only_limit)

    def test_functional_and_cohort_materials_are_retained_without_any_case_label(self):
        payload = self.payload_for(self.functional)
        for kind in ('FUNCTIONAL_STUDY', 'COHORT', 'AUTHOR_DISCUSSION'):
            with self.subTest(kind=kind):
                candidate = self.candidate(payload, material_kind=kind)
                out = material.review(self.source, candidate, payload)
                self.assertEqual(kind, out['records'][0]['materialKindProposal'])
                self.assertEqual(self.functional, out['records'][0]['facts'][0]['sourceValue'])
                self.assertNotIn('cases', out)
                self.assertNotIn('caseLabel', out['records'][0])
                self.assertEqual('NOT_GRANTED', out['records'][0]['ownershipQualification'])
                self.assertEqual('NOT_GRANTED', out['relationQualification'])
                self.assertFalse(out['scientificAdoption'])
                self.assertFalse(out['businessPublishable'])
                self.assertEqual('NONE', out['clinicalAdoption'])

    def test_original_clinical_negation_and_course_are_literal_not_distinct_disease_decision(self):
        payload = self.payload_for(self.clinical)
        candidate = self.candidate(payload, field='reportedDiseaseCourse', material_kind='INDIVIDUAL_CASE')
        out = material.review(self.source, candidate, payload)
        fact = out['records'][0]['facts'][0]
        self.assertEqual(self.clinical, fact['sourceValue'])
        self.assertIn('was not diagnosed', fact['citation']['quote'])
        self.assertEqual('LITERAL_TEXT_MATCHED_NOT_SEMANTICALLY_QUALIFIED', fact['citation']['verification'])
        self.assertEqual(digest(canonical(next(u for u in self.source['units']
            if u['unitId'] == fact['citation']['unitId']))), fact['citation']['originalUnitSha256'])
        self.assertNotIn('diseaseRelationship', out)
        self.assertNotIn('causesOther', out)

    def test_table_cells_keep_actual_original_locator(self):
        payload = self.payload_for('30% activity')
        candidate = self.candidate(payload)
        content = parse(payload['messages'][1]['content'])
        cell = next(u for u in content['units'] if u['text'] == '30% activity')
        candidate['records'][0]['anchor'] = dict(unitId=cell['unitId'], quote=cell['text'])
        candidate['records'][0]['facts'][0].update(sourceValue='30% activity',
            citation=dict(unitId=cell['unitId'], quote=cell['text']))
        out = material.review(self.source, candidate, payload)
        ref = out['records'][0]['facts'][0]['citation']
        self.assertEqual(cell['sourceSha256'], ref['sourceSha256'])
        self.assertEqual(cell['unitId'], ref['unitId'])
        self.assertEqual(0, ref['start'])
        self.assertEqual(len(cell['text']), ref['end'])

    def test_empty_extraction_does_not_create_absence_no_support_or_completion(self):
        payload = self.payload_for(self.functional)
        candidate = self.candidate(payload); candidate['records'] = []
        out = material.review(self.source, candidate, payload)
        self.assertEqual([], out['records'])
        self.assertIn('NO_EXTRACTION_NOT_PROVEN_NO_RELATIONSHIP', out['issues'])
        for forbidden in ('NO_SUPPORT', 'scopeComplete', 'readingComplete', 'causal', 'points', 'evidenceStatus'):
            self.assertNotIn(forbidden, out)
        self.assertEqual('NOT_GRANTED', out['relationQualification'])
        candidate = self.candidate(payload)
        candidate['records'][0]['facts'] = []
        empty_record = material.review(self.source, candidate, payload)
        self.assertIn('RECORD_WITHOUT_EXTRACTED_FACTS', empty_record['issues'])
        self.assertEqual('NOT_GRANTED', empty_record['relationQualification'])

    def test_more_records_or_context_insufficient_remain_incomplete_proposals(self):
        payload = self.payload_for(self.functional)
        for coverage in ('MORE_RECORDS_OR_FIELDS', 'CONTEXT_INSUFFICIENT'):
            with self.subTest(coverage=coverage):
                candidate = self.candidate(payload); candidate['coverage'] = coverage
                out = material.review(self.source, candidate, payload)
                self.assertEqual(coverage, out['extractionCoverageProposal'])
                self.assertIn('TARGET_EXTRACTION_INCOMPLETE', out['issues'])

    def test_schema_rejects_model_qualification_fields_and_unknown_fact_names(self):
        payload = self.payload_for(self.functional)
        for mutation in ('causal', 'field', 'record', 'over-limit'):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                candidate = self.candidate(payload)
                if mutation == 'causal':
                    candidate['causesOther'] = True
                elif mutation == 'field':
                    candidate['records'][0]['facts'][0]['field'] = 'qualifiedRelationship'
                elif mutation == 'record':
                    candidate['records'][0]['approved'] = 'yes'
                else:
                    candidate['records'] *= 5
                material.review(self.source, candidate, payload)

    def test_changed_source_identity_and_chunk_binding_are_rejected(self):
        payload = self.payload_for(self.functional)
        for name in ('pmcid', 'bundleSha256', 'sourceRawSha256', 'unitManifestSha256', 'sourceViewSha256', 'workUnitSha256'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                candidate = self.candidate(payload)
                if name == 'workUnitSha256':
                    candidate[name] = 'f' * 64
                else:
                    candidate['sourceIdentity'][name] = 'PMC999' if name == 'pmcid' else 'f' * 64
                material.review(self.source, candidate, payload)

    def test_quote_value_and_unsent_unit_mutations_are_rejected(self):
        payload = self.payload_for(self.functional)
        content = parse(payload['messages'][1]['content'])
        unsent = next(u for u in self.source['units'] if u['unitId'] not in {v['unitId'] for v in content['units']})
        for mutation in ('quote', 'value', 'unsent'):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                candidate = self.candidate(payload)
                field = candidate['records'][0]['facts'][0]
                if mutation == 'quote':
                    field['citation']['quote'] = 'Invented result'
                elif mutation == 'value':
                    field['sourceValue'] = '90% activity'
                else:
                    field['citation'] = dict(unitId=unsent['unitId'], quote=unsent['text'])
                    field['sourceValue'] = unsent['text']
                material.review(self.source, candidate, payload)

    def test_ambiguous_literal_quote_and_duplicate_source_units_are_rejected(self):
        payload = self.payload_for(self.clinical)
        candidate = self.candidate(payload)
        field = candidate['records'][0]['facts'][0]
        field['sourceValue'] = 'cardiomyopathy'
        field['citation']['quote'] = 'cardiomyopathy'
        with self.assertRaisesRegex(ValueError, 'CITATION_NOT_EXACT_OR_AMBIGUOUS'):
            material.review(self.source, candidate, payload)
        changed = deepcopy(self.source)
        changed['units'].append(deepcopy(changed['units'][0]))
        with self.assertRaisesRegex(ValueError, 'SOURCE_UNIT_IDENTITY_INVALID'):
            material.requests(changed)

    def test_request_unit_schema_or_prompt_mutation_cannot_be_replayed(self):
        payload = self.payload_for(self.functional)
        for mutation in ('unit', 'schema', 'prompt', 'question'):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                changed = deepcopy(payload)
                content = parse(changed['messages'][1]['content'])
                if mutation == 'unit':
                    content['units'][0]['text'] = 'different actual view'
                elif mutation == 'schema':
                    content['outputSchema']['required'] = []
                elif mutation == 'question':
                    content['questionContract']['questionVersion'] = 'core-material-v2'
                else:
                    changed['messages'][0]['content'] = 'Award PP2'
                changed['messages'][1]['content'] = canonical(content).decode()
                material.review(self.source, self.candidate(payload), changed)

    def test_source_view_change_rejects_previous_candidate_and_request(self):
        payload = self.payload_for(self.functional)
        changed = deepcopy(self.source)
        changed['coverage']['status'] = 'DIFFERENT_ACTUAL_SOURCE_VIEW'
        with self.assertRaises(ValueError):
            material.review(changed, self.candidate(payload), payload)

    def test_context_only_record_and_assessment_cannot_become_target_material(self):
        payload = self.payload_for(self.functional)
        content = parse(payload['messages'][1]['content'])
        context = next(u for u in content['units'] if u['unitId'] in content['workUnit']['contextUnitIds'] and u['text'])
        for mutation in ('record', 'assessment'):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                candidate = self.candidate(payload)
                ref = dict(unitId=context['unitId'], quote=context['text'])
                if mutation == 'record':
                    candidate['records'][0]['anchor'] = deepcopy(ref)
                    candidate['records'][0]['facts'][0].update(sourceValue=context['text'], citation=deepcopy(ref))
                else:
                    candidate['sourceAssessments'][0]['citation'] = ref
                material.review(self.source, candidate, payload)

    def test_core_v2_response_and_missing_assessment_are_rejected(self):
        payload = self.payload_for(self.functional)
        for mutation in ('old-core', 'no-assessment'):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                candidate = self.candidate(payload)
                if mutation == 'old-core':
                    candidate = dict(schemaVersion='denovo-core-material-candidates-v1', cases=[],
                                     coverage='EXTRACTED_WITHIN_TARGET', sourceAssessments=[])
                else:
                    candidate['sourceAssessments'] = []
                material.review(self.source, candidate, payload)

    def test_output_is_detached_and_request_size_or_oversized_target_is_not_truncated(self):
        payload = self.payload_for(self.functional)
        candidate = self.candidate(payload)
        original = canonical(candidate)
        out = material.review(self.source, candidate, payload)
        self.assertEqual(original, canonical(candidate))
        out['records'][0]['facts'][0]['sourceValue'] = 'edited output'
        self.assertEqual(original, canonical(candidate))
        with self.assertRaises(ValueError):
            material.requests(self.source, maxRequestBytes=1024)
        large = deepcopy(self.source)
        target = next(u for u in large['units'] if u['text'] == self.functional)
        target['text'] = 'x' * 6001
        with self.assertRaisesRegex(ValueError, 'WORK_LIMIT_NOT_TRUNCATED'):
            material.requests(large)


if __name__ == '__main__':
    unittest.main()

