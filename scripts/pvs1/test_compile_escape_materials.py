import copy
import json
from pathlib import Path
import tempfile
import unittest
from compile_escape_materials import compile_policy, compile_batch, candidate_bundle, content_hash
from assemble_materials import historical_materials, sha


class EscapeMaterialTest(unittest.TestCase):
    def setUp(self):
        model = {'proteinLength': 100, 'cdsLength': 303, 'exons': [
            {'ordinal': 1, 'cdsStart': 1, 'cdsEnd': 150}, {'ordinal': 2, 'cdsStart': 151, 'cdsEnd': 303}]}
        source = {'sourceId': 'TEST_ONLY_SOURCE', 'relativePath': 'source.txt', 'sha256': sha(b'TEST_ONLY frozen source')}
        p = {'schemaVersion': 'pvs1-truncating-nmd-policy-v1', 'policyId': 'TEST_ONLY', 'policyVersion': '1.0.0',
             'review': {'status': 'REVIEWED_IMPLEMENTED', 'reviewId': 'TEST_ONLY_BASE'},
             'scope': {'hgncId': 'HGNC:123', 'mondoId': 'MONDO:0000001'},
             'transcriptModelBinding': {'canonicalSha256': content_hash(model)},
             'scientificSources': [source], 'mechanism': {'status': 'ESTABLISHED_LOSS_OF_FUNCTION'},
             'nmdScope': {'exonRelevance': 'TEST_ONLY explicit exons', 'relevantExonOrdinals': [1, 2],
                          'engineeringSupportedExonOrdinals': [1], 'minimumDistanceToLastJunction': 51,
                          'minimumCdsPosition': 30, 'maximumExonLength': 400, 'sourceIds': ['TEST_ONLY_SOURCE']}}
        self.material = {'policy': p, 'policySha256': content_hash(p),
                         'modelMaterial': {'model': model}, 'sourceChecks': [{'source': source, 'status': 'RAW_SHA_MATCH'}]}
        self.recipe = {'basePolicySha256': content_hash(p), 'modelSha256': content_hash(model),
            'basis': 'TEST_ONLY source interpretation', 'sourceIds': ['TEST_ONLY_SOURCE'],
            'sourceLocators': [{'sourceId': 'TEST_ONLY_SOURCE', 'locator': 'TEST_ONLY paragraph 1', 'interpretation': 'TEST_ONLY critical tail'}],
            'populationFacts': [], 'positionConditions': [],
            'rules': [{'ruleId': 'TEST_ONLY_REGION', 'predicate': 'CRITICAL_REGION', 'start': 80, 'end': 95,
                       'minimumAffectedResidues': 5, 'strength': 'STRONG', 'basis': 'TEST_ONLY statement', 'sourceIds': ['TEST_ONLY_SOURCE']}]}

    def compile(self): return compile_policy(self.material, self.recipe)

    def test_unreviewed_material_preserves_base_and_unknown_population(self):
        before = copy.deepcopy(self.material); r = self.compile()
        self.assertEqual(before, self.material)
        self.assertEqual(before['policy'], r['basePolicy'])
        self.assertEqual(before['policy']['nmdScope'], r['policy']['nmdScope'])
        self.assertEqual('UNREVIEWED', r['policy']['review']['status'])
        self.assertEqual('UNREVIEWED', r['policy']['escapeScope']['status'])
        self.assertEqual(['UNKNOWN', 'UNKNOWN'], [x['populationLof'] for x in r['policy']['escapeScope']['exonAssessments']])
        self.assertFalse(r['runtimeEligible']); self.assertFalse(r['clinicalAcceptance'])
        self.assertEqual(r, self.compile())

    def test_source_status_pin_and_model_cannot_drift(self):
        for mutate in [lambda m: m['sourceChecks'][0].update(status='NOT_LOCATED'),
                       lambda m: m['policy'].update(policyId='DRIFT'),
                       lambda m: m['modelMaterial']['model'].update(proteinLength=101),
                       lambda m: m.update(modelMaterial=None)]:
            with self.subTest(mutate=mutate):
                old = copy.deepcopy(self.material); mutate(self.material)
                with self.assertRaises(ValueError): self.compile()
                self.material = old

    def test_unreviewed_or_pending_base_is_not_sufficient(self):
        for key, value in [('review', {'status': 'UNREVIEWED'}), ('mechanism', {'status': 'UNKNOWN'})]:
            old = copy.deepcopy(self.material); self.material['policy'][key] = value; self.repin()
            with self.assertRaisesRegex(ValueError, 'APPLICABILITY'): self.compile()
            self.material = old; self.repin()
        self.material['policy']['nmdScope']['exonRelevance'] = 'PENDING'; self.repin()
        with self.assertRaisesRegex(ValueError, 'APPLICABILITY'): self.compile()

    def repin(self):
        self.material['policySha256'] = self.recipe['basePolicySha256'] = content_hash(self.material['policy'])

    def test_gene_labels_do_not_control_generated_rules(self):
        expected = self.compile()['policy']['escapeScope']
        self.material['policy']['scope'].update(hgncId='HGNC:456', mondoId='MONDO:0000002'); self.repin()
        self.assertEqual(expected, self.compile()['policy']['escapeScope'])

    def test_missing_source_locator_cannot_be_ignored(self):
        self.recipe['sourceLocators'] = []
        with self.assertRaisesRegex(ValueError, 'SOURCE_LOCATOR'): self.compile()

    def test_rule_source_must_be_from_same_policy_and_located(self):
        self.recipe['rules'][0]['sourceIds'] = ['UNRELATED']
        with self.assertRaisesRegex(ValueError, 'RULE_IDENTITY'): self.compile()

    def test_no_interpretation_is_auto_approval(self):
        self.recipe['review'] = {'status': 'REVIEWED_IMPLEMENTED'}
        with self.assertRaisesRegex(ValueError, 'RECIPE_FIELDS'): self.compile()

    def test_region_bounds_and_unsupported_fields(self):
        for key, value in [('start', 0), ('end', 101), ('start', True), ('minimumAffectedResidues', 17),
                           ('minimumAffectedResidues', 5.0), ('strength', 'PATHOGENIC'), ('priority', 1)]:
            with self.subTest(key=key, value=value):
                old = copy.deepcopy(self.recipe); self.recipe['rules'][0][key] = value
                with self.assertRaises(ValueError): self.compile()
                self.recipe = old

    def fraction(self):
        r = self.recipe['rules'][0]
        self.recipe['rules'] = [{k: v for k, v in r.items() if k in {'ruleId', 'basis', 'sourceIds', 'strength'}} | {
            'predicate': 'PROTEIN_LOSS_FRACTION', 'metric': 'NET_PROTEIN_SHORTENING', 'comparison': 'LT', 'numerator': 1, 'denominator': 10}]

    def test_fraction_marks_missing_population_without_fabrication(self):
        self.fraction(); r = self.compile()
        self.assertTrue(r['gaps']['populationRequiredByRules']); self.assertEqual([1, 2], r['gaps']['populationUnknownExons'])

    def test_fraction_malformed_and_unallowed_strength(self):
        self.fraction()
        for key, value in [('metric', 'UNSPECIFIED_LOSS'), ('comparison', 'LE'), ('numerator', 10),
                           ('denominator', True), ('strength', 'VERY_STRONG')]:
            old = copy.deepcopy(self.recipe); self.recipe['rules'][0][key] = value
            with self.assertRaises(ValueError): self.compile()
            self.recipe = old

    def test_conditional_tree_must_not_be_flattened(self):
        critical = copy.deepcopy(self.recipe['rules'][0]); self.fraction()
        self.recipe['rules'][0]['ruleId'] = 'FALLBACK'; self.recipe['rules'].append(critical)
        with self.assertRaisesRegex(ValueError, 'MIXED_BRANCHES'): self.compile()

    def test_duplicate_rule_is_rejected(self):
        self.recipe['rules'] *= 2
        with self.assertRaisesRegex(ValueError, 'RULE_IDENTITY'): self.compile()

    def test_nonrelevant_exon_does_not_become_absent(self):
        self.material['policy']['nmdScope']['relevantExonOrdinals'] = [1]; self.repin()
        self.assertEqual('UNKNOWN', self.compile()['policy']['escapeScope']['exonAssessments'][1]['biologicalRelevance'])

    def test_explicit_population_fact_is_preserved_but_unreviewed(self):
        self.recipe['populationFacts'] = [{'ordinal': 2, 'populationLof': 'FREQUENT', 'basis': 'TEST_ONLY adverse fact', 'sourceIds': ['TEST_ONLY_SOURCE']}]
        r = self.compile(); self.assertEqual('FREQUENT', r['policy']['escapeScope']['exonAssessments'][1]['populationLof'])
        self.assertEqual([1], r['gaps']['populationUnknownExons']); self.assertEqual('UNREVIEWED', r['status'])

    def test_conflicting_duplicate_population_rows_are_not_selected(self):
        self.recipe['populationFacts'] = [{'ordinal': 2, 'populationLof': x, 'basis': 'TEST_ONLY', 'sourceIds': ['TEST_ONLY_SOURCE']} for x in ['FREQUENT', 'NOT_FREQUENT']]
        with self.assertRaisesRegex(ValueError, 'POPULATION_FACT'): self.compile()

    def test_reference_coordinate_conditions_are_preserved(self):
        c = {'conditionId': 'event', 'coordinate': 'REFERENCE_EVENT_SPAN', 'minimumInclusive': 200,
             'sourceIds': ['TEST_ONLY_SOURCE'], 'basis': 'TEST_ONLY'}
        self.recipe['positionConditions'] = [c]
        self.assertEqual([c], self.compile()['policy']['escapeScope']['positionConditions'])
        c['maximumExclusive'] = 200
        with self.assertRaisesRegex(ValueError, 'POSITION_CONDITION'): self.compile()

    def test_v2_base_position_conditions_are_not_lost(self):
        self.material['policy']['schemaVersion'] = 'pvs1-truncating-nmd-policy-v2'
        self.material['policy']['nmdScope']['positionConditions'] = [{'TEST_ONLY': 'original'}]; self.repin()
        self.assertEqual([{'TEST_ONLY': 'original'}], self.compile()['policy']['nmdScope']['positionConditions'])

    def test_compatible_candidate_bundle_round_trip_does_not_inherit_reviews(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def save(name, value):
                raw = json.dumps(value).encode(); (root/name).write_bytes(raw); return sha(raw)
            (root/'source.txt').write_bytes(b'TEST_ONLY frozen source')
            model = self.material['modelMaterial']['model']; save('model.json', model)
            catalog = {'catalogId': 'TEST_ONLY', 'review': {'status': 'REVIEWED'}, 'sources': [], 'entries': [
                {'entryId': 'TEST_ONLY', 'policyBinding': {'policyId': 'TEST_ONLY'}, 'review': {'status': 'REVIEWED'}, 'baseRuleRelationship': 'TEST_ONLY original scope'}]}
            original = {'schemaVersion': 'pvs1-truncating-nmd-bundle-candidate-v1', 'catalog': catalog, 'catalogSha256': content_hash(catalog),
                'activation': {}, 'runtimeResourceBinding': {}, 'policies': [self.material['policy']],
                'transcriptModelResources': [{'canonicalSha256': content_hash(model), 'relativePath': 'model.json'}]}
            self.material['originalCandidate'] = original
            r = self.compile(); bundle = candidate_bundle(original, {'TEST_ONLY': r['policy']}); self.assertEqual('UNREVIEWED', bundle['catalog']['review']['status'])
            self.assertEqual([], bundle['sourceReviewBindings']); self.assertEqual('REVIEWED', original['catalog']['review']['status'])
            pin = save('candidate.json', bundle)
            config = {'candidates': [{'file': str(root/'candidate.json'), 'sha256': pin, 'resourceRoot': str(root)}]}
            material = historical_materials(config)[0]
            self.assertEqual(r['policy'], material['policy']); self.assertFalse(material['runtimeEligible'])
            self.assertIn('POLICY_ESCAPE_DOMAIN_REVIEW_INCOMPLETE', material['reasonCodes'])
            original_pin = save('original.json', original)
            config['candidates'][0].update(file=str(root/'original.json'), sha256=original_pin)
            config_pin = save('config.json', config)
            report = compile_batch({'schemaVersion': 'pvs1-escape-supply-input-v1',
                'materialsConfig': {'file': str(root/'config.json'), 'sha256': config_pin},
                'recipes': [self.recipe, {'basePolicySha256': 'a'*64}]})
            self.assertEqual({'candidates': 1, 'blocked': 1, 'newScientificAdoptions': 0}, report['counts'])
            with self.assertRaisesRegex(ValueError, 'DUPLICATE_BASE_RECIPE'):
                compile_batch({'schemaVersion': 'pvs1-escape-supply-input-v1',
                    'materialsConfig': {'file': str(root/'config.json'), 'sha256': config_pin},
                    'recipes': [self.recipe, self.recipe]})

    def test_multi_transcript_catalog_has_one_replacement_per_original(self):
        p = self.material['policy']; second = copy.deepcopy(p); second['policyId'] = 'SECOND_TRANSCRIPT'
        catalog = {'catalogId': 'TEST_ONLY', 'entries': [
            {'entryId': x['policyId'], 'policyBinding': {'policyId': x['policyId']}, 'baseRuleRelationship': 'Preserve original restriction.'} for x in [p, second]]}
        original = {'policies': [p, second], 'catalog': catalog, 'runtimeResourceBinding': {}, 'transcriptModelResources': []}
        first = self.compile()['policy']; next_second = copy.deepcopy(first); next_second['policyId'] = 'NEW_SECOND'
        b = candidate_bundle(original, {'TEST_ONLY': first, 'SECOND_TRANSCRIPT': next_second})
        self.assertEqual([first['policyId'], 'NEW_SECOND'], [x['policyId'] for x in b['policies']])
        self.assertEqual([first['policyId'], 'NEW_SECOND'], [x['policyBinding']['policyId'] for x in b['catalog']['entries']])
        self.assertTrue(all(x['review']['status'] == 'UNREVIEWED' for x in b['catalog']['entries']))


if __name__ == '__main__': unittest.main()
