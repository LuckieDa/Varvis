import copy
import unittest
import os
import hashlib
import json
from pathlib import Path
from prepare_bp1_published_knowledge import (disposition, prepare, truncating_source, truncating_interpretation,
                                             TRUNCATING_TYPE, TRUNCATING_INSTRUCTION, normalized)
from test_prepare_published_criterion_knowledge import PreparationTest
from prepare_published_criterion_knowledge import compile_plan, content_hash


class GrammarTest(unittest.TestCase):
    def source(self, gene='SYNTHETIC'):
        return {'applicability': 'Applicable', 'gene': [gene], 'baseStrength': 'Benign', 'defaultStrength': 'Benign Supporting',
            'instructionsToUse': f'Based on published and unpublished functional studies, {gene} has a low rate of missense variants that are non-functional in relevant assays. True missense pathogenic variants are not yet confirmed or refuted but are thought to be exceedingly rare. Given the very low likelihood that missense variants are pathogenic, this rule applies to all missense variants in {gene}.',
            'additionalComments': None, 'strengthDescriptor': [{'applicability': 'Applicable', 'strength': 'Supporting',
                'instructionsToUse': '', 'text': '\u00a0Apply to all missense variants.'}]}

    def test_data_driven_symbol_is_not_a_gene_allowlist(self):
        for gene in ['TEST1', 'ANOTHER2']:
            self.assertEqual('BP1_SUPPORTED_PUBLISHED_GRAMMAR', disposition({}, self.source(gene)))

    def test_no_applicability_or_lof_shortcut(self):
        self.assertNotEqual('BP1_SUPPORTED_PUBLISHED_GRAMMAR', disposition({}, {'applicability': 'Applicable', 'gene': ['X'], 'mechanism': 'LoF'}))

    def test_complete_conditions_and_strength_are_preserved(self):
        for field in ['additionalComments', 'instructionsToUse', 'defaultStrength']:
            c = self.source(); c[field] = 'Additional source condition'
            self.assertNotEqual('BP1_SUPPORTED_PUBLISHED_GRAMMAR', disposition({}, c))
        c = self.source(); c['strengthDescriptor'][0]['strength'] = 'Strong'
        self.assertEqual('BP1_PUBLISHED_STRENGTH_UNSUPPORTED', disposition({}, c))

    def test_missing_ambiguous_strengths_and_attachments_block(self):
        c = self.source(); c['strengthDescriptor'].append(copy.deepcopy(c['strengthDescriptor'][0]))
        self.assertEqual('BP1_PUBLISHED_STRENGTH_UNSUPPORTED', disposition({}, c))
        self.assertEqual('BP1_PUBLISHED_ATTACHMENTS_UNSUPPORTED', disposition({'ld': {'File': [{'name': 'Appendix'}]}}, self.source()))

    def test_not_applicable_stays_not_applicable(self):
        c = self.source(); c['applicability'] = 'Not applicable'; c['strengthDescriptor'] = []
        self.assertEqual('BP1_SOURCE_NOT_APPLICABLE', disposition({}, c))

    def test_top_level_non_applicability_cannot_hide_an_active_source_strength(self):
        c = self.source(); c['applicability'] = 'Not applicable'
        self.assertEqual('BP1_SOURCE_APPLICABILITY_CONFLICT', disposition({}, c))

    def test_unknown_applicability_is_not_expert_non_applicability(self):
        for value in [None, '', 'Unknown']:
            c = self.source(); c['applicability'] = value
            self.assertEqual('BP1_SOURCE_APPLICABILITY_UNRESOLVED', disposition({}, c))

    def test_explicit_prohibition_conflicting_with_applicable_is_retained(self):
        c = self.source(); c['additionalComments'] = 'Do not use: BP1 is not applicable.'
        self.assertEqual('BP1_SOURCE_APPLICABILITY_CONFLICT', disposition({}, c))


class BatchDispositionTest(unittest.TestCase):
    def setUp(self):
        self.fixture = PreparationTest(); self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.original = self.fixture.entity
        self.clause = self.original['ld']['CriteriaCode'][0]['entContent']
        self.clause.update(GrammarTest().source('TEST'), label='BP1')
        self.original['ld']['File'] = []

    def prepare(self):
        self.fixture.write_source()
        source = self.fixture.plan['catalog']
        return prepare(source['file'], source['sha256'], self.fixture.root)

    def test_known_non_executable_clauses_are_runtime_records_not_silently_missing(self):
        for applicability, comment, expected, reason in [
            ('Not applicable', '', 'NOT_APPLICABLE', 'BP1_SOURCE_NOT_APPLICABLE'),
            ('Applicable', 'Additional region exclusion', 'UNSUPPORTED_CONDITIONS', 'BP1_PUBLISHED_SOURCE_CONDITIONS_UNSUPPORTED'),
            ('Applicable', 'Do not use: this rule is not applicable.', 'SOURCE_CONFLICT', 'BP1_SOURCE_APPLICABILITY_CONFLICT')]:
            with self.subTest(status=expected):
                self.clause.update(applicability=applicability, additionalComments=comment)
                self.clause['strengthDescriptor'] = [] if applicability == 'Not applicable' else GrammarTest().source('TEST')['strengthDescriptor']
                plan, compiled, audit = self.prepare()
                self.assertEqual(1, len(compiled['records']))
                self.assertEqual(expected, compiled['records'][0]['status'])
                self.assertEqual(reason, compiled['records'][0]['reasonCode'])
                self.assertEqual(self.original, compiled['specifications'][0]['original'])
                self.assertEqual(0, audit['clauses'][0]['executableScopes'])

    def test_real_scope_conflict_does_not_erase_other_usable_scopes_or_invent_a_mapping(self):
        disease = self.original['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        disease['modesOfInheritance'] = [{'modeOfInheritance': 'Autosomal recessive inheritance'}]
        plan, compiled, audit = self.prepare()
        self.assertEqual([], compiled['records'])
        self.assertEqual('BP1_SOURCE_INHERITANCE_CONFLICT', audit['clauses'][0]['unresolvedScopes'][0]['reasonCode'])

    def test_transcript_missing_stays_explicitly_unresolved(self):
        self.original['ld']['RuleSet'][0]['entContent']['genes'][0]['preferredTranscript'] = ''
        _, compiled, audit = self.prepare()
        self.assertEqual([], compiled['records'])
        self.assertEqual('BP1_SOURCE_TRANSCRIPT_UNRESOLVED', audit['clauses'][0]['unresolvedScopes'][0]['reasonCode'])


class TruncatingSourceTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('T05_RAS_RAW'), 'Explicit fixed CSpec original required')
    def test_pinned_real_subtypes_preserve_lof_qualification_requirement(self):
        raw = Path(os.environ['T05_RAS_RAW']).read_bytes()
        self.assertEqual('0c112a41986f1e0b85491717e1d1b474e38cf639e68c3579a21294d5ad164d6c', hashlib.sha256(raw).hexdigest())
        originals = json.loads(raw)['data']
        for index in (0, 2):
            original = originals[index]
            clause = next(c['entContent'] for c in original['ld']['CriteriaCode'] if c['entContent']['label'] == 'BP1')
            self.assertTrue(truncating_source(original, clause))
            self.assertEqual('BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED', disposition(original, clause))
            self.assertTrue(truncating_interpretation()['requiresNoEstablishedLofDiseaseCorrelation'])
            self.assertEqual(TRUNCATING_TYPE, truncating_interpretation()['type'])
            for field, value in [('additionalComments', 'Ignore LoF correlation'), ('instructionsToUse', 'All missense variants'), ('applicability', 'Unknown')]:
                changed = copy.deepcopy(clause); changed[field] = value
                self.assertFalse(truncating_source(original, changed))
            attached = copy.deepcopy(original); attached['ld']['File'] = [{'name': 'Uninterpreted condition'}]
            self.assertFalse(truncating_source(attached, clause))
            unknown_notes = copy.deepcopy(original); unknown_notes['entContent']['notes'] += ' Extra condition.'
            self.assertFalse(truncating_source(unknown_notes, clause))


class LofAssociationRawSourceTest(unittest.TestCase):
    """Rule-native RAW transport: notes wording and absent dates grant no qualification."""
    DECLARATION = ('No established germline loss-of-function disease association is known for TEST (HGNC:123) '
                   'across all human diseases. Expert review date: 2026-01-02. Valid through: 2027-01-02.')

    def setUp(self):
        self.fixture = PreparationTest(); self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.primary = self.fixture.entity
        self.primary['ld']['File'] = []
        self.primary['ld']['CriteriaCode'][0]['entContent'].update(GrammarTest().source('TEST'), label='BP1',
                instructionsToUse=TRUNCATING_INSTRUCTION)
        self.declaration = copy.deepcopy(self.primary)
        self.declaration['entId'] = 'GN901'
        self.declaration['entContent']['notes'] = self.DECLARATION
        self.declaration['ld']['CriteriaCode'] = []
        self.declaration['uninterpretedOriginalGraph'] = {'text': 'SYNTHETIC café, retained whole', 'items': [1, 2]}
        self.catalog_file = self.fixture.root / 'lof-catalog.json'
        self.raw_files = []

    def write(self, captures=None, mutate_catalog=None, byte_length=None, encoding='utf-8'):
        captures = captures if captures is not None else [[self.primary, self.declaration]]
        entries = []; self.raw_files = []
        for ci, entities in enumerate(captures):
            raw = (json.dumps({'data': entities}, ensure_ascii=False, indent=1) + '\n').encode(encoding)
            if byte_length is not None:
                self.assertLessEqual(len(raw), byte_length)
                raw += b' ' * (byte_length - len(raw))
            path = self.fixture.root / ('capture-' + str(ci) + '.json')
            path.write_bytes(raw); self.raw_files.append((path, raw))
            for index, entity in enumerate(entities):
                source = {'rawRef': path.name, 'rawSha256': hashlib.sha256(raw).hexdigest(),
                          'jsonPointer': '/data/' + str(index), 'sourceUrl': 'https://example.invalid/synthetic-source/' + entity['entId']}
                entries.append({'specificationId': entity['entId'], 'source': source,
                                'rawRef': source['rawRef'], 'rawSha256': source['rawSha256']})
        catalog = {'schemaVersion': 'a01-ba1-cspec-snapshot-v1',
                   'metadata': {'status': 'COMPLETE', 'selectedReleasedCount': len({entry['specificationId'] for entry in entries})},
                   'entries': entries}
        if mutate_catalog is not None:
            mutate_catalog(catalog)
        catalog['sha256'] = content_hash(catalog)
        raw = json.dumps(catalog).encode()
        self.catalog_file.write_bytes(raw); self.catalog_sha = hashlib.sha256(raw).hexdigest()

    def prepare(self):
        return prepare(self.catalog_file, self.catalog_sha, self.fixture.root)

    def test_complete_raw_roundtrip_and_recordless_spec_are_preserved_without_grant(self):
        self.declaration['entContent']['notes'] = self.DECLARATION.replace(' ', '\u00a0\n')
        original = copy.deepcopy(self.declaration)
        self.write(); plan, compiled, audit = self.prepare()
        self.assertEqual(1, len(plan['records']))
        self.assertEqual(['GN900@1.0'], [row['specificationKey'] for row in compiled['records']])
        specifications = {spec['specificationKey']: spec for spec in compiled['specifications']}
        self.assertEqual({'GN900@1.0'}, set(specifications))
        self.assertNotIn('GN901@1.0', specifications, 'Recordless future statement has no scientific standing')
        raw_source, = compiled['bp1LofAssociationRawSources']
        self.assertEqual({'rawSha256', 'rawJsonUtf8'}, set(raw_source))
        self.assertEqual(self.raw_files[0][1], raw_source['rawJsonUtf8'].encode('utf-8'))
        self.assertEqual(hashlib.sha256(self.raw_files[0][1]).hexdigest(), raw_source['rawSha256'])
        self.assertEqual(original, json.loads(raw_source['rawJsonUtf8'])['data'][1])
        self.assertFalse(compiled['clinicalValidationCompleted'])
        self.assertFalse(compiled['preparation']['priorBa1ApprovalInherited'])
        self.assertEqual(1, len(audit['clauses']), 'Independent original creates no BP1 clause or scoring record')

    def test_two_declarations_and_repeated_catalog_capture_retain_one_raw(self):
        second = copy.deepcopy(self.declaration); second['entId'] = 'GN902'
        self.write([[self.primary, self.declaration, second]],
                   mutate_catalog=lambda c: c['entries'].append(copy.deepcopy(c['entries'][1])))
        _, compiled, _ = self.prepare()
        self.assertEqual(1, len(compiled['bp1LofAssociationRawSources']))
        self.assertEqual({'GN900@1.0'}, {s['specificationKey'] for s in compiled['specifications']})
        self.assertEqual(1, len(compiled['records']))

    def test_native_truncating_rule_is_retained_without_a_future_declaration(self):
        self.write([[self.primary]])
        plan, compiled, _ = self.prepare()
        self.assertIn('bp1LofAssociationRawSources', compiled)
        without_raw = copy.deepcopy(compiled); without_raw.pop('bp1LofAssociationRawSources')
        self.assertEqual(compile_plan(plan), without_raw)

    def test_recordless_notes_never_create_extra_scientific_sources_or_records(self):
        for notes in [None, '', 'Only gain-of-function variants are known to cause this disease.',
                      'Haploinsufficiency score: 0.', 'No disease record was found.',
                      'PTPN11 has established loss-of-function disease; therefore TEST has no such association.',
                      'No loss-of-function disease association is known for ANOTHER gene; TEST is the complement.',
                      self.DECLARATION + ' Additional unimplemented condition.',
                      self.DECLARATION.replace('across all human diseases', 'for this selected disease only'),
                      self.DECLARATION.lower(), {'reviewed': True, 'claim': self.DECLARATION}]:
            with self.subTest(notes=notes):
                self.declaration['entContent']['notes'] = notes
                self.declaration['reviewed'] = True; self.declaration['claim'] = 'NO_ESTABLISHED_CORRELATION'
                self.write(); _, compiled, _ = self.prepare()
                self.assertEqual(1, len(compiled['bp1LofAssociationRawSources']))
                self.assertEqual(['GN900@1.0'], [spec['specificationKey'] for spec in compiled['specifications']])

    def test_original_raw_sha_and_entity_pointer_are_required(self):
        self.write(); self.raw_files[0][0].write_bytes(self.raw_files[0][1] + b' ')
        with self.assertRaisesRegex(ValueError, 'SHA_MISMATCH'):
            self.prepare()
        def wrong_pointer(catalog):
            catalog['entries'][1]['source']['jsonPointer'] = '/data/0'
        self.write(mutate_catalog=wrong_pointer)
        with self.assertRaisesRegex(ValueError, 'SOURCE_ENTITY_MISMATCH'):
            self.prepare()

    def test_raw_paths_are_confined_to_the_selected_capture_directory(self):
        for invalid_path in ['../outside.json', '..\\outside.json', 'capture.json:stream', str(self.fixture.root / 'capture-0.json')]:
            with self.subTest(path=invalid_path):
                def wrong_path(catalog):
                    row = catalog['entries'][1]; row['rawRef'] = invalid_path; row['source']['rawRef'] = invalid_path
                self.write(mutate_catalog=wrong_path)
                with self.assertRaisesRegex(ValueError, 'RESOURCE_PATH_INVALID'):
                    self.prepare()

    def test_expired_and_future_dates_are_only_original_bytes_and_mtime_cannot_renew_them(self):
        for reviewed, through in [('2000-01-01', '2001-01-01'), ('2998-01-01', '2999-01-01')]:
            with self.subTest(reviewed=reviewed):
                self.declaration['entContent']['notes'] = self.DECLARATION.replace('2026-01-02', reviewed).replace('2027-01-02', through)
                self.write(); _, compiled, _ = self.prepare()
                source, = compiled['bp1LofAssociationRawSources']
                self.assertEqual({'rawSha256', 'rawJsonUtf8'}, set(source))
                self.assertIn(reviewed, source['rawJsonUtf8']); self.assertIn(through, source['rawJsonUtf8'])
                self.assertEqual(['GN900@1.0'], [row['specificationKey'] for row in compiled['records']])
                os.utime(self.raw_files[0][0], (2100000000, 2100000000))
                self.assertEqual(compiled, self.prepare()[1], 'Capture mtime cannot change reviewed/valid-through facts')

    def test_at_most_eight_distinct_raw_sources_are_retained(self):
        declarations = []
        for i in range(9):
            entity = copy.deepcopy(self.primary); entity['entId'] = 'GN' + str(910 + i)
            # Exercise RAW transport limits without inventing duplicate executable scopes.
            entity['ld']['RuleSet'][0]['entContent']['genes'][0]['preferredTranscript'] = ''
            declarations.append(entity)
        self.write([[self.primary]] + [[entity] for entity in declarations[:7]])
        self.assertEqual(8, len(self.prepare()[1]['bp1LofAssociationRawSources']))
        self.write([[self.primary]] + [[entity] for entity in declarations[:8]])
        with self.assertRaisesRegex(ValueError, 'BP1_LOF_ASSOCIATION_RAW_SOURCE_LIMIT'):
            self.prepare()

    def test_raw_byte_limit_is_checked_before_publishing_full_capture(self):
        self.write(byte_length=4_000_000)
        source, = self.prepare()[1]['bp1LofAssociationRawSources']
        self.assertEqual(4_000_000, len(source['rawJsonUtf8'].encode('utf-8')))
        self.write(byte_length=4_000_001)
        with self.assertRaisesRegex(ValueError, 'INPUT_SIZE_OR_PIN_INVALID'):
            self.prepare()

    def test_only_utf8_original_bytes_can_be_transported_as_raw_json_utf8(self):
        self.write(encoding='utf-16')
        with self.assertRaisesRegex(ValueError, 'BP1_LOF_ASSOCIATION_RAW_UTF8_REQUIRED'):
            self.prepare()


if __name__ == '__main__': unittest.main()
