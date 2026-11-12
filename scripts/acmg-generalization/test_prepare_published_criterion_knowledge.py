import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from prepare_published_criterion_knowledge import compile_plan, content_hash, parse


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.entity = {'entId': 'GN900', 'entContent': {'version': '1.0', 'states': [{'current': True, 'name': 'Released'}]},
            'ld': {'Gene': [{'entContent': {'HGNC': {'symbol': 'TEST', 'hgnc_id': 'HGNC:123'}}}],
                'RuleSet': [{'entContent': {'genes': [{'gene': 'TEST', 'preferredTranscript': 'NM_000001.2',
                    'diseases': [{'preferredModeOfInheritance': 'Autosomal dominant inheritance', 'preferredMondoId': 'MONDO:0000001'}]}]}}],
                'CriteriaCode': [{'entContent': {'label': 'PM1', 'gene': ['TEST'], 'disease': ['MONDO:0000001'],
                    'applicability': 'Applicable', 'text': 'TEST_ONLY entire clause'}}],
                'File': [{'fileName': 'restriction.pdf', 'url': 'https://example.org/restriction'}]}}
        self.plan = {'schemaVersion': 'published-criterion-interpretation-plan-v1',
            'adoptionMode': 'PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS', 'clinicalValidationCompleted': False,
            'policyId': 'TEST_ONLY', 'policyVersion': '1.0.0', 'captureDirectory': str(self.root),
            'records': [{'recordId': 'test-1', 'criterionId': 'ACMG_AMP_2015:PM1', 'specificationKey': 'GN900@1.0',
                'clausePointer': '/ld/CriteriaCode/0', 'scopePointer': '/ld/RuleSet/0/entContent/genes/0/diseases/0',
                'transcript': 'NM_000001.2', 'status': 'EXECUTABLE', 'reasonCode': 'TEST_SOURCE',
                'interpretation': {'type': 'TEST_ONLY', 'version': '1.0.0', 'conditions': ['full condition']}}]}
        self.write_source()

    def write_source(self):
        raw = json.dumps({'data': [self.entity]}).encode()
        (self.root/'raw.json').write_bytes(raw)
        source = {'rawRef': 'raw.json', 'rawSha256': hashlib.sha256(raw).hexdigest(),
                  'jsonPointer': '/data/0', 'sourceUrl': 'https://example.org/GN900'}
        catalog = {'schemaVersion': 'a01-ba1-cspec-snapshot-v1',
                   'metadata': {'status': 'COMPLETE', 'selectedReleasedCount': 1},
                   'entries': [{'specificationId': 'GN900', 'source': source,
                                'rawRef': source['rawRef'], 'rawSha256': source['rawSha256']}]}
        catalog['sha256'] = content_hash(catalog)
        raw = json.dumps(catalog).encode(); (self.root/'catalog.json').write_bytes(raw)
        self.plan['catalog'] = {'file': str(self.root/'catalog.json'), 'sha256': hashlib.sha256(raw).hexdigest()}

    def test_full_original_and_interpretation_are_distinct_and_unmodified(self):
        out = compile_plan(self.plan)
        self.assertEqual(self.entity, out['specifications'][0]['original'])
        self.assertEqual(self.plan['records'][0]['interpretation'], out['records'][0]['interpretation'])
        self.assertEqual('AUTOSOMAL_DOMINANT', out['records'][0]['scope']['moi'])
        self.assertFalse(out['preparation']['priorBa1ApprovalInherited'])
        self.assertFalse(out['clinicalValidationCompleted'])

    def test_wrong_raw_pin_fails_before_output(self):
        (self.root/'raw.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'SHA_MISMATCH'): compile_plan(self.plan)

    def test_source_scope_and_promotion_rejections(self):
        for field, value in [('criterionId', 'ACMG_AMP_2015:PP2'), ('transcript', 'NM_000001.3'),
                             ('scopePointer', '/ld/RuleSet/0/entContent/genes/0/diseases/1'), ('review', {'status': 'APPROVED'})]:
            with self.subTest(field=field):
                config = copy.deepcopy(self.plan); config['records'][0][field] = value
                with self.assertRaises(ValueError): compile_plan(config)

    def test_unreleased_or_inheritance_conflict_never_compiles(self):
        self.entity['entContent']['states'][0]['name'] = 'Draft'; self.write_source()
        with self.assertRaisesRegex(ValueError, 'SOURCE_NOT_RELEASED'): compile_plan(self.plan)
        self.entity['entContent']['states'][0]['name'] = 'Released'
        self.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['modesOfInheritance'] = [
            {'modeOfInheritance': 'Autosomal recessive inheritance'}]
        self.write_source()
        with self.assertRaisesRegex(ValueError, 'INHERITANCE_CONFLICT'): compile_plan(self.plan)

    def test_negative_clause_cannot_compile_as_executable_but_keeps_explanation(self):
        self.entity['ld']['CriteriaCode'][0]['entContent']['applicability'] = 'Not applicable'; self.write_source()
        with self.assertRaisesRegex(ValueError, 'CLAUSE_NOT_APPLICABLE'): compile_plan(self.plan)
        self.plan['records'][0]['status'] = 'NOT_APPLICABLE'
        self.assertEqual('NOT_APPLICABLE', compile_plan(self.plan)['records'][0]['status'])

    def mutate_catalog(self, mutate):
        path = self.root / 'catalog.json'
        catalog = json.loads(path.read_bytes())
        mutate(catalog)
        catalog['sha256'] = content_hash({k: v for k, v in catalog.items() if k != 'sha256'})
        raw = json.dumps(catalog).encode()
        path.write_bytes(raw)
        self.plan['catalog']['sha256'] = hashlib.sha256(raw).hexdigest()

    def expected_scope(self):
        # This expected identity is independently declared by the caller, not copied from compiler output.
        return {'hgncId': 'HGNC:123', 'mondoId': 'MONDO:0000001', 'moi': 'AUTOSOMAL_DOMINANT',
                'assembly': 'GRCh38', 'transcript': 'NM_000001.2'}

    def test_null_disease_is_not_missing_or_an_explicit_empty_disease_list(self):
        clause = self.entity['ld']['CriteriaCode'][0]['entContent']
        clause['disease'] = None
        self.write_source()
        with self.assertRaisesRegex(ValueError, 'CLAUSE_DISEASE_MISMATCH'):
            compile_plan(self.plan)
        for value in ([], ['MONDO:0000001']):
            clause['disease'] = value
            self.write_source()
            self.assertEqual(self.expected_scope(), compile_plan(self.plan)['records'][0]['scope'])
        del clause['disease']
        self.write_source()
        self.assertEqual(self.expected_scope(), compile_plan(self.plan)['records'][0]['scope'])

    def test_clause_genes_must_be_a_list_not_a_string_substring(self):
        clause = self.entity['ld']['CriteriaCode'][0]['entContent']
        for value in ('TEST', 'OTHER_TEST', None, {'TEST': True}, ['TEST', None]):
            with self.subTest(value=value):
                clause['gene'] = value
                self.write_source()
                with self.assertRaisesRegex(ValueError, 'CRITERION_SOURCE_MISMATCH'):
                    compile_plan(self.plan)

    def test_malformed_source_gene_graph_fails_with_a_controlled_reason(self):
        ruleset = self.entity['ld']['RuleSet'][0]['entContent']
        for value in ('TEST', {}, None, [None]):
            with self.subTest(value=value):
                ruleset['genes'] = value
                self.write_source()
                with self.assertRaisesRegex(ValueError, 'SOURCE_GRAPH_INVALID|EXACT_SOURCE_SCOPE_REQUIRED'):
                    compile_plan(self.plan)

    def test_transcripts_must_be_explicit_list_members_never_scalar_substrings(self):
        gene = self.entity['ld']['RuleSet'][0]['entContent']['genes'][0]
        gene.pop('preferredTranscript')
        for value in ('NM_000001.20', {'NM_000001.2': True}, [None], 2):
            with self.subTest(value=value):
                gene['transcripts'] = value
                self.write_source()
                with self.assertRaisesRegex(ValueError, 'EXPLICIT_SOURCE_TRANSCRIPT_REQUIRED'):
                    compile_plan(self.plan)
        gene['transcripts'] = ['NM_000001.20', 'NM_000001.2']
        self.write_source()
        self.assertEqual('NM_000001.2', compile_plan(self.plan)['records'][0]['scope']['transcript'])
        gene['preferredTranscript'] = {'accession': 'NM_000001.2'}
        self.write_source()
        with self.assertRaisesRegex(ValueError, 'EXPLICIT_SOURCE_TRANSCRIPT_REQUIRED'):
            compile_plan(self.plan)

    def test_malformed_modes_do_not_become_empty_or_conflicting_silent_defaults(self):
        disease = self.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        for value in (None, 'Autosomal dominant inheritance', {}, ['Autosomal dominant inheritance'], [{}],
                      [{'modeOfInheritance': None}], [{'modeOfInheritance': ['Autosomal dominant inheritance']}]):
            with self.subTest(value=value):
                disease['modesOfInheritance'] = value
                self.write_source()
                with self.assertRaisesRegex(ValueError, 'INHERITANCE_CONFLICT'):
                    compile_plan(self.plan)

    def test_only_known_same_moi_aliases_agree_and_source_is_unchanged(self):
        disease = self.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        for preferred, alternative, expected in (
                ('Autosomal dominant inheritance', 'Autosomal Dominant', 'AUTOSOMAL_DOMINANT'),
                ('Autosomal Recessive', 'Autosomal recessive inheritance', 'AUTOSOMAL_RECESSIVE')):
            with self.subTest(preferred=preferred):
                disease['preferredModeOfInheritance'] = preferred
                disease['modesOfInheritance'] = [{'modeOfInheritance': alternative}]
                self.write_source()
                before = copy.deepcopy(self.entity)
                result = compile_plan(self.plan)
                self.assertEqual(expected, result['records'][0]['scope']['moi'])
                self.assertEqual(before, self.entity)
                self.assertEqual(before, result['specifications'][0]['original'])
        disease['modesOfInheritance'] = [{'modeOfInheritance': 'Autosomal dominant inheritance'}]
        self.write_source()
        with self.assertRaisesRegex(ValueError, 'INHERITANCE_CONFLICT'):
            compile_plan(self.plan)
        disease['modesOfInheritance'] = [{'modeOfInheritance': 'autosomal recessive'}]
        self.write_source()
        with self.assertRaisesRegex(ValueError, 'INHERITANCE_CONFLICT'):
            compile_plan(self.plan)

    def test_explicit_expected_scope_pins_all_five_background_dimensions(self):
        self.plan['records'][0]['expectedScope'] = self.expected_scope()
        result = compile_plan(self.plan)
        self.assertEqual(self.expected_scope(), result['records'][0]['scope'])
        self.assertNotIn('expectedScope', result['records'][0])
        for field, value in [('hgncId', 'HGNC:999'), ('mondoId', 'MONDO:0000002'), ('moi', 'AUTOSOMAL_RECESSIVE'),
                             ('assembly', 'GRCh37'), ('transcript', 'NM_000001.3')]:
            with self.subTest(field=field):
                config = copy.deepcopy(self.plan)
                config['records'][0]['expectedScope'][field] = value
                with self.assertRaisesRegex(ValueError, 'EXPECTED_SCOPE_MISMATCH'):
                    compile_plan(config)
        for value in (None, {}, {**self.expected_scope(), 'geneSymbol': 'TEST'}):
            config = copy.deepcopy(self.plan)
            config['records'][0]['expectedScope'] = value
            with self.assertRaisesRegex(ValueError, 'EXPECTED_SCOPE_MISMATCH'):
                compile_plan(config)

    def test_expected_scope_cannot_silently_rebind_to_another_legal_source_association(self):
        self.plan['records'][0]['expectedScope'] = self.expected_scope()
        disease = self.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        disease['preferredMondoId'] = 'MONDO:0000002'
        self.entity['ld']['CriteriaCode'][0]['entContent']['disease'] = ['MONDO:0000002']
        self.write_source()
        with self.assertRaisesRegex(ValueError, 'EXPECTED_SCOPE_MISMATCH'):
            compile_plan(self.plan)

    def test_same_exact_selection_is_rejected_for_distinct_ids_statuses_and_order(self):
        row = self.plan['records'][0]
        for status in ('EXECUTABLE', 'UNSUPPORTED_CONDITIONS', 'SOURCE_CONFLICT'):
            for reverse in (False, True):
                with self.subTest(status=status, reverse=reverse):
                    other = {**copy.deepcopy(row), 'recordId': 'test-2', 'status': status}
                    config = copy.deepcopy(self.plan)
                    config['records'] = [other, row] if reverse else [row, other]
                    with self.assertRaisesRegex(ValueError, 'EXACT_SELECTION_AMBIGUOUS'):
                        compile_plan(config)
        self.plan['records'].append(copy.deepcopy(row))
        with self.assertRaisesRegex(ValueError, 'RECORD_IDENTITY_INVALID'):
            compile_plan(self.plan)

    def test_distinct_exact_transcripts_and_diseases_remain_distinct_records(self):
        gene = self.entity['ld']['RuleSet'][0]['entContent']['genes'][0]
        gene['transcripts'] = ['NM_000001.3']
        gene['diseases'].append({'preferredModeOfInheritance': 'Autosomal recessive inheritance',
                                 'preferredMondoId': 'MONDO:0000002'})
        self.entity['ld']['CriteriaCode'][0]['entContent']['disease'] = []
        row = self.plan['records'][0]
        self.plan['records'].extend([
            {**copy.deepcopy(row), 'recordId': 'other-transcript', 'transcript': 'NM_000001.3'},
            {**copy.deepcopy(row), 'recordId': 'other-background',
             'scopePointer': '/ld/RuleSet/0/entContent/genes/0/diseases/1'}])
        self.write_source()
        result = compile_plan(self.plan)
        self.assertEqual(3, len(result['records']))
        self.assertEqual(['MONDO:0000001', 'MONDO:0000001', 'MONDO:0000002'],
                         [r['scope']['mondoId'] for r in result['records']])

    def test_not_applicable_requires_explicit_source_nonapplicability(self):
        self.plan['records'][0]['status'] = 'NOT_APPLICABLE'
        clause = self.entity['ld']['CriteriaCode'][0]['entContent']
        for value in ('Applicable', 'Unknown', None, ''):
            with self.subTest(value=value):
                clause['applicability'] = value
                self.write_source()
                with self.assertRaisesRegex(ValueError, 'SOURCE_NONAPPLICABILITY_REQUIRED'):
                    compile_plan(self.plan)
        for value in ('Not applicable', 'Not Applicable', 'Not Applicable for this VCEP'):
            clause['applicability'] = value
            self.write_source()
            self.assertEqual('NOT_APPLICABLE', compile_plan(self.plan)['records'][0]['status'])

    def test_active_strength_contradicts_not_applicable_but_source_conflict_is_retained(self):
        row = self.plan['records'][0]
        clause = self.entity['ld']['CriteriaCode'][0]['entContent']
        clause['applicability'] = 'Not applicable'
        inactive = {'applicability': 'Not Applicable', 'strength': 'Moderate'}
        active = {'applicability': 'Applicable', 'strength': 'Supporting'}
        for descriptors in ([active, inactive], [inactive, active]):
            with self.subTest(descriptors=descriptors):
                clause['strengthDescriptor'] = descriptors
                self.write_source()
                row['status'] = 'NOT_APPLICABLE'
                with self.assertRaisesRegex(ValueError, 'SOURCE_APPLICABILITY_CONFLICT'):
                    compile_plan(self.plan)
                row['status'] = 'SOURCE_CONFLICT'
                result = compile_plan(self.plan)
                self.assertEqual('SOURCE_CONFLICT', result['records'][0]['status'])
                self.assertEqual(self.entity, result['specifications'][0]['original'])
                self.assertEqual('1.0.0', result['methodVersion'])
        clause['strengthDescriptor'] = [inactive]
        self.write_source()
        row['status'] = 'NOT_APPLICABLE'
        self.assertEqual('NOT_APPLICABLE', compile_plan(self.plan)['records'][0]['status'])

    def test_malformed_strength_descriptors_cannot_establish_not_applicable(self):
        row = self.plan['records'][0]
        clause = self.entity['ld']['CriteriaCode'][0]['entContent']
        clause['applicability'] = 'Not Applicable for this VCEP'
        for descriptors in (None, {}, 'Applicable', [None], [{}], [{'applicability': None}],
                            [{'applicability': []}], [{'applicability': ''}]):
            with self.subTest(descriptors=descriptors):
                clause['strengthDescriptor'] = descriptors
                self.write_source()
                row['status'] = 'NOT_APPLICABLE'
                with self.assertRaisesRegex(ValueError, 'SOURCE_STRENGTH_DESCRIPTORS_INVALID'):
                    compile_plan(self.plan)
                row['status'] = 'SOURCE_CONFLICT'
                self.assertEqual('SOURCE_CONFLICT', compile_plan(self.plan)['records'][0]['status'])

    def test_withdrawn_and_multiple_current_source_states_are_not_released(self):
        for states in ([{'current': True, 'name': 'Withdrawn'}],
                       [{'current': True, 'name': 'Released'}, {'current': True, 'name': 'Withdrawn'}],
                       [{'current': False, 'name': 'Released'}]):
            with self.subTest(states=states):
                self.entity['entContent']['states'] = states
                self.write_source()
                with self.assertRaisesRegex(ValueError, 'SOURCE_NOT_RELEASED'):
                    compile_plan(self.plan)

    def test_catalog_and_json_tamper_are_rejected(self):
        (self.root / 'catalog.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError, 'SHA_MISMATCH'):
            compile_plan(self.plan)
        for raw in (b'{"a":1,"a":2}', b'{"value":NaN}', b'{} {}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse(raw)

    def test_source_refs_must_match_the_pinned_loader_contract(self):
        for field, value in [('rawSha256', 'A' * 64), ('jsonPointer', '/data/-1'), ('jsonPointer', '/data/00'),
                             ('jsonPointer', '/other/0'), ('sourceUrl', 'http://example.org/spec'),
                             ('sourceUrl', 'https://example.org/a b'), ('rawRef', '../raw.json'),
                             ('rawRef', None)]:
            with self.subTest(field=field, value=value):
                self.write_source()
                def mutate(catalog):
                    catalog['entries'][0]['source'][field] = value
                    if field in ('rawRef', 'rawSha256'):
                        catalog['entries'][0][field] = value
                self.mutate_catalog(mutate)
                with self.assertRaisesRegex(ValueError, 'SOURCE_REF_INVALID|RESOURCE_PATH_INVALID'):
                    compile_plan(self.plan)
        self.write_source()
        self.mutate_catalog(lambda c: c['entries'][0].update(rawSha256='0' * 64))
        with self.assertRaisesRegex(ValueError, 'SOURCE_REF_INVALID'):
            compile_plan(self.plan)

    def test_ambiguous_catalog_sources_never_choose_the_last_item(self):
        def mutate(catalog):
            other = copy.deepcopy(catalog['entries'][0])
            other['source']['sourceUrl'] = 'https://example.org/other'
            catalog['entries'].append(other)
        self.mutate_catalog(mutate)
        with self.assertRaisesRegex(ValueError, 'SPECIFICATION_SOURCE_AMBIGUOUS'):
            compile_plan(self.plan)

    def test_clause_pointer_uses_canonical_bounded_indices(self):
        for pointer in ('/ld/CriteriaCode/00', '/ld/CriteriaCode/9', '/ld/CriteriaCode/-1'):
            with self.subTest(pointer=pointer):
                config = copy.deepcopy(self.plan)
                config['records'][0]['clausePointer'] = pointer
                with self.assertRaisesRegex(ValueError, 'CLAUSE_POINTER_INVALID'):
                    compile_plan(config)



if __name__ == '__main__':
    unittest.main()
