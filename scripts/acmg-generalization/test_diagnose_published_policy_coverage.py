"""Independent expected diagnoses: these assertions do not derive from the compiler."""
import copy
import hashlib
import json
import unittest

import test_prepare_published_criterion_knowledge as fixtures
from diagnose_published_policy_coverage import diagnose
from prepare_published_criterion_knowledge import compile_plan


class CoverageTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PreparationTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.config = {
            'schemaVersion': 'published-policy-coverage-diagnostic-input-v1',
            'catalog': self.fixture.plan['catalog'], 'captureDirectory': str(self.fixture.root),
            'requests': [{'requestId': 'explicit-background', 'criterionId': 'ACMG_AMP_2015:PM1',
                          'geneSymbol': 'TEST', 'scope': {'hgncId': 'HGNC:123', 'mondoId': 'MONDO:0000001',
                          'moi': 'AUTOSOMAL_DOMINANT', 'assembly': 'GRCh38', 'transcript': 'NM_000001.2'}}],
        }

    def reason(self):
        return diagnose(self.config)['requests'][0]['reasonCode']

    def supply(self, name='knowledge.json', doc=None):
        if doc is None:
            doc = compile_plan(self.fixture.plan)
        path = self.fixture.root / name
        raw = json.dumps(doc).encode()
        path.write_bytes(raw)
        self.config.setdefault('knowledge', []).append({'file': str(path), 'sha256': hashlib.sha256(raw).hexdigest()})
        return doc

    def test_source_available_but_record_absent_does_not_award(self):
        out = diagnose(self.config)
        self.assertEqual('SOURCE_MATCH_RECORD_NOT_SUPPLIED', out['requests'][0]['reasonCode'])
        self.assertEqual(1, out['requests'][0]['exactSourceCount'])
        self.assertFalse(out['runtimeEligible'])
        self.assertFalse(out['requests'][0]['evidenceAwarded'])
        self.assertFalse(out['requests'][0]['generalRouteEstablishedByThisAudit'])
        candidate = out['requests'][0]['sourceCandidates'][0]
        self.assertEqual('/ld/CriteriaCode/0', candidate['clausePointer'])
        self.assertEqual('raw.json', candidate['source']['rawRef'])

    def test_supplied_record_requires_actual_runtime_not_automatic_recovery(self):
        self.supply()
        self.assertEqual('RECORD_SUPPLIED_RUNTIME_VALIDATION_REQUIRED', self.reason())

    def test_wrong_disease_moi_transcript_and_symbol_are_separate_dimensions(self):
        for key, bad in [('mondoId', 'MONDO:0000002'), ('moi', 'AUTOSOMAL_RECESSIVE'),
                         ('transcript', 'NM_000001.3')]:
            with self.subTest(key=key):
                config = copy.deepcopy(self.config)
                config['requests'][0]['scope'][key] = bad
                row = diagnose(config)['requests'][0]
                self.assertEqual('SOURCE_IDENTITY_OR_SCOPE_NOT_MATCHED', row['reasonCode'])
                self.assertIn(key, row['sourceCandidates'][0]['mismatchedDimensions'])
        config = copy.deepcopy(self.config)
        config['requests'][0]['geneSymbol'] = 'OTHER'
        self.assertIn('geneSymbol', diagnose(config)['requests'][0]['sourceCandidates'][0]['mismatchedDimensions'])

    def test_snapshot_miss_does_not_allow_general_route_or_declare_no_knowledge(self):
        self.config['requests'][0]['scope']['hgncId'] = 'HGNC:999'
        row = diagnose(self.config)['requests'][0]
        self.assertEqual('NO_GENE_CRITERION_MATCH_IN_PINNED_SOURCE_INVENTORY', row['reasonCode'])
        self.assertFalse(row['generalRouteEstablishedByThisAudit'])

    def test_assembly_not_silently_rebound_to_grch38(self):
        self.config['requests'][0]['scope']['assembly'] = 'GRCh37'
        self.assertEqual('ASSEMBLY_OUTSIDE_EXISTING_COMPILER_CONTRACT', self.reason())

    def test_unknown_moi_never_substituted_with_ad(self):
        self.config['requests'][0]['scope']['moi'] = 'UNKNOWN'
        self.assertEqual('SOURCE_IDENTITY_OR_SCOPE_NOT_MATCHED', self.reason())

    def test_true_secondary_inheritance_conflict_is_visible_alias_is_not_conflict(self):
        disease = self.fixture.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        for term, expected in [('Autosomal Dominant', False), ('Autosomal recessive inheritance', True)]:
            with self.subTest(term=term):
                disease['modesOfInheritance'] = [{'modeOfInheritance': term}]
                self.fixture.write_source()
                self.config['catalog'] = self.fixture.plan['catalog']
                candidate = diagnose(self.config)['requests'][0]['sourceCandidates'][0]
                self.assertEqual(expected, 'sourceInheritanceConflict' in candidate['mismatchedDimensions'])

    def test_source_nonapplicability_and_missing_supply_remain_distinct(self):
        self.fixture.entity['ld']['CriteriaCode'][0]['entContent']['applicability'] = 'Not applicable'
        self.fixture.write_source()
        self.config['catalog'] = self.fixture.plan['catalog']
        self.assertEqual('SOURCE_MARKED_NOT_APPLICABLE_RECORD_NOT_SUPPLIED', self.reason())
        self.fixture.plan['records'][0]['status'] = 'NOT_APPLICABLE'
        self.supply()
        self.assertEqual('SUPPLIED_RECORD_NOT_APPLICABLE', self.reason())

    def test_explicit_operator_gap_is_not_record_absence(self):
        self.fixture.plan['records'][0]['status'] = 'UNSUPPORTED_CONDITIONS'
        self.fixture.plan['records'][0]['reasonCode'] = 'TEST_NEW_OPERATOR_REQUIRED'
        self.supply()
        self.assertEqual('SUPPLIED_RECORD_UNSUPPORTED_CONDITIONS', self.reason())

    def test_policy_domains_only_have_source_identity_audited(self):
        for criterion in ('PS1', 'PM5', 'PS2', 'PM6', 'BA1', 'BS1', 'PM2'):
            with self.subTest(criterion=criterion):
                self.fixture.entity['ld']['CriteriaCode'][0]['entContent']['label'] = criterion
                self.fixture.write_source()
                self.config['catalog'] = self.fixture.plan['catalog']
                self.config['requests'][0]['criterionId'] = 'ACMG_AMP_2015:' + criterion
                self.assertEqual('SOURCE_MATCH_POLICY_ROUTE_REQUIRES_RUNTIME_VALIDATION', self.reason())

    def test_two_loaded_record_packs_do_not_pick_array_order(self):
        self.supply()
        self.fixture.plan['policyId'] = 'TEST_ONLY_SECOND_PACK'
        self.fixture.plan['records'][0]['recordId'] = 'test-2'
        self.supply('other.json')
        self.assertEqual('MULTIPLE_SUPPLIED_RECORDS_REQUIRE_SELECTION', self.reason())

    def test_repeated_identical_pack_ref_is_input_error_not_double_evidence(self):
        self.supply()
        self.config['knowledge'].append(copy.deepcopy(self.config['knowledge'][0]))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_KNOWLEDGE_INPUT'):
            diagnose(self.config)

    def test_modified_compiled_source_clause_or_scope_rejected_even_with_new_outer_pin(self):
        for kind in ('original', 'scope', 'clause'):
            with self.subTest(kind=kind):
                self.config.pop('knowledge', None)
                doc = compile_plan(self.fixture.plan)
                if kind == 'original':
                    doc['specifications'][0]['original']['entContent']['version'] = '99.0'
                elif kind == 'scope':
                    doc['records'][0]['scope']['mondoId'] = 'MONDO:0000002'
                else:
                    doc['records'][0]['clauseSha256'] = '0' * 64
                self.supply(kind + '.json', doc)
                with self.assertRaises(ValueError):
                    diagnose(self.config)

    def test_source_tamper_and_withdrawal_never_produce_valid_diagnostic(self):
        (self.fixture.root / 'raw.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'SHA_MISMATCH'):
            diagnose(self.config)
        self.fixture.entity['entContent']['states'][0]['name'] = 'Withdrawn'
        self.fixture.write_source()
        self.config['catalog'] = self.fixture.plan['catalog']
        with self.assertRaisesRegex(ValueError, 'SOURCE_NOT_RELEASED'):
            diagnose(self.config)

    def test_null_clause_disease_not_treated_as_absent_scope(self):
        self.fixture.entity['ld']['CriteriaCode'][0]['entContent']['disease'] = None
        self.fixture.write_source()
        self.config['catalog'] = self.fixture.plan['catalog']
        row = diagnose(self.config)['requests'][0]
        self.assertEqual('SOURCE_IDENTITY_OR_SCOPE_NOT_MATCHED', row['reasonCode'])
        self.assertIn('clauseDiseaseScope', row['sourceCandidates'][0]['mismatchedDimensions'])

    def test_wrong_snapshot_and_duplicate_request_rejected(self):
        doc = compile_plan(self.fixture.plan)
        doc['preparation']['catalogRawSha256'] = '0' * 64
        self.supply(doc=doc)
        with self.assertRaisesRegex(ValueError, 'SOURCE_INVENTORY_MISMATCH'):
            diagnose(self.config)
        self.config.pop('knowledge')
        self.config['requests'].append(copy.deepcopy(self.config['requests'][0]))
        with self.assertRaisesRegex(ValueError, 'REQUEST_IDENTITY_INVALID'):
            diagnose(self.config)

    def test_malformed_request_and_missing_source_fail_with_controlled_reasons(self):
        config = copy.deepcopy(self.config)
        config['requests'][0]['criterionId'] = []
        with self.assertRaisesRegex(ValueError, 'REQUEST_IDENTITY_INVALID'):
            diagnose(config)
        config.pop('catalog')
        with self.assertRaisesRegex(ValueError, 'SOURCE_REF_INVALID'):
            diagnose(config)


if __name__ == '__main__':
    unittest.main()
