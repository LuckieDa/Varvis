"""Synthetic contract tests; test review ledgers are not production approvals."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from core_material_contract import (assess, canonical, context_binding, digest,
                                    facts, parse, pointer, validate_shape)
from core_material_schema import SCHEMA
from project_core_material import blank, missing, observed, project


def test_ledger(document):
    """Bind only deliberately constructed test facts. Never called by production."""
    return {'schemaVersion': 'denovo-core-review-ledger-v1',
            'scope': 'OFFLINE_CONTRACT_VALIDATION_ONLY',
            'contextBinding': context_binding(document),
            'checks': {p: {'factSha256': digest(f), 'method': 'SYNTHETIC_TEST_EXPECTATION',
                           'basis': 'Fixed synthetic input, no clinical approval'}
                       for p, f in facts(document).items() if f['verification'] == 'VERIFIED'}}


class Fixture:
    def __init__(self, root):
        self.root, self.claims = Path(root), {}
        self.doc = blank('synthetic-case-1', 'SYNTHETIC')
        allele = {'assembly': 'GRCh38', 'contig': '1', 'position': 100, 'reference': 'A', 'alternate': 'G'}
        values = {
            '/groups/paper/identity': 'SYNTHETIC-PAPER', '/groups/paper/availability': 'MAIN_AVAILABLE',
            '/groups/case/label': 'case-1', '/groups/case/origin': 'AUTHOR_COHORT',
            '/groups/gene/symbol': 'TEST_GENE', '/groups/variant/representations': ['synthetic chr1:100 A>G'],
            '/groups/variant/resolvedAllele': allele,
            '/groups/proband/targetResult': 'PRESENT', '/groups/mother/targetResult': 'ABSENT',
            '/groups/father/targetResult': 'ABSENT', '/groups/testing/qualifiedForTarget': True,
            '/groups/testing/participants': ['PROBAND', 'MOTHER', 'FATHER'],
            '/groups/parentage/maternity': 'CONFIRMED', '/groups/parentage/paternity': 'CONFIRMED',
            '/groups/phenotype/positive': ['synthetic phenotype'], '/groups/family/compatibility': 'COMPATIBLE',
            '/groups/overlap/eventId': 'synthetic-event-1',
            '/groups/overlap/independence': 'UNIQUE_WITHIN_CHECKED_SCOPE',
            '/context/allele': allele, '/context/hgncId': 'HGNC:SYNTHETIC', '/context/diseaseId': 'MONDO:SYNTHETIC',
            '/context/inheritance': 'AD', '/context/variantScope': 'SMALL_SEQUENCE_VARIANT',
            '/context/geneDiseaseApplicability': 'APPLICABLE', '/context/phenotypeCategory': 'CONSISTENT',
            '/context/policyId': 'SYNTHETIC-AD', '/context/policyVersion': '1',
            '/context/expertPolicyStatus': 'GENERAL_ALLOWED', '/context/allowAssumedParentage': True,
            '/conditions/specialFamilyModel': 'NONE_IDENTIFIED_IN_CHECKED_SCOPE',
        }
        for path, value in values.items():
            self.set(path, value)

    def set(self, path, value):
        # Separate files for context and case facts exercise reuse separation.
        source = 'facts' if path.startswith('/groups/') else 'context'
        self.claims.setdefault(source, {})[path] = value
        subject = pointer(self.doc, path)['subject'] if path in facts(self.doc) else 'CONTEXT'
        locator = '/' + path.replace('~', '~0').replace('/', '~1')
        raw = value if isinstance(value, str) else canonical(value).decode()
        f = observed(self.doc['caseKey'], subject, value, raw,
                     [{'sourceId': source, 'kind': 'JSON_POINTER', 'locator': locator, 'quote': raw}])
        f['verification'] = 'VERIFIED'
        parent, name = path.rsplit('/', 1)
        pointer(self.doc, parent)[name] = f

    def seal(self):
        for name, claims in self.claims.items():
            raw = canonical(claims)
            (self.root / (name + '.json')).write_bytes(raw)
            self.doc['sources'][name] = {'path': name + '.json', 'sha256': hashlib.sha256(raw).hexdigest(),
                                         'role': 'DERIVED_FACTS' if name == 'facts' else 'EVALUATION_KNOWLEDGE',
                                         'format': 'JSON', 'pmcid': None}
        self.ledger = test_ledger(self.doc)
        self.pin = digest(self.ledger)
        return self

    def run(self):
        return assess(self.doc, self.root, self.ledger, self.pin)


class CoreMaterialTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[2] / 'local' / 'temp'
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='core-material-test-', dir=root)
        self.addCleanup(self.temp.cleanup)
        self.f = Fixture(self.temp.name).seal()

    def codes(self, result):
        return {b['code'] for b in result['blockers']}

    def test_full_contract_is_ready_without_award(self):
        result = self.f.run()
        self.assertEqual('READY_FOR_EVALUATION', result['status'])
        self.assertEqual('NOT_RUN', result['criterionDecision'])
        self.assertNotIn('score', result)

    def test_each_required_fact_missing_blocks(self):
        required = self.f.run()['requiredFactPaths']
        for path in required:
            with self.subTest(path=path):
                f = Fixture(self.temp.name)
                parent, name = path.rsplit('/', 1)
                pointer(f.doc, parent)[name] = missing(f.doc['caseKey'], pointer(f.doc, path)['subject'])
                self.assertEqual('INSUFFICIENT_DATA', f.seal().run()['status'])
                self.assertTrue(any(b['path'] == path for b in f.run()['blockers']))

    def test_key_deletion_is_schema_failure(self):
        del self.f.doc['groups']['father']['targetResult']
        with self.assertRaisesRegex(ValueError, 'SCHEMA_REQUIRED'):
            self.f.run()

    def test_missing_parent_not_negative(self):
        self.f.doc['groups']['father']['targetResult'] = missing(self.f.doc['caseKey'], 'FATHER')
        r = self.f.seal().run()
        self.assertEqual('INSUFFICIENT_DATA', r['status'])
        self.assertFalse(r['observedNegativeFacts']['parentalTargetPresent'])

    def test_qualified_negative_is_evaluable(self):
        self.f.set('/groups/mother/targetResult', 'PRESENT')
        self.f.set('/context/phenotypeCategory', 'NOT_CONSISTENT')
        r = self.f.seal().run()
        self.assertEqual('READY_FOR_EVALUATION', r['status'])
        self.assertTrue(all(r['observedNegativeFacts'].values()))

    def test_assumed_parentage_is_not_missing_target_tests(self):
        self.f.set('/groups/parentage/paternity', 'ASSUMED_NOT_CONFIRMED')
        self.assertEqual('READY_FOR_EVALUATION', self.f.seal().run()['status'])
        self.f.set('/context/allowAssumedParentage', False)
        self.assertIn('ASSUMED_PARENTAGE_POLICY_NOT_APPLICABLE', self.codes(self.f.seal().run()))

    def test_not_reported_requires_bounded_scan(self):
        path = '/groups/father/targetResult'
        self.f.doc['groups']['father']['targetResult'] = missing(self.f.doc['caseKey'], 'FATHER', 'NOT_REPORTED_IN_CHECKED_SCOPE')
        with self.assertRaisesRegex(ValueError, 'ABSENCE_SCOPE_REQUIRED'):
            self.f.seal().run()
        self.f.doc['groups']['father']['targetResult']['checkedLocations'] = [
            {'sourceId': 'facts', 'kind': 'JSON_POINTER', 'locator': '/' + path.replace('/', '~1')}]
        self.assertIn('NOT_REPORTED_IN_CHECKED_SCOPE', self.codes(self.f.seal().run()))

    def test_verified_fact_tamper_needs_new_review(self):
        self.f.doc['groups']['father']['targetResult']['value'] = 'PRESENT'
        with self.assertRaisesRegex(ValueError, 'FACT_REVIEW_REQUIRED'):
            self.f.run()

    def test_wrong_ledger_pin_rejected(self):
        self.f.pin = '0' * 64
        with self.assertRaisesRegex(ValueError, 'REVIEW_LEDGER_PIN_MISMATCH'):
            self.f.run()

    def test_model_cannot_self_verify(self):
        self.f.doc['groups']['father']['targetResult']['origin'] = 'MODEL_PROPOSAL'
        with self.assertRaisesRegex(ValueError, 'MODEL_SELF_VERIFICATION'):
            self.f.seal().run()

    def test_case_and_parent_identity_binding(self):
        for name, value, message in [('caseKey', 'other-case', 'FACT_CASE_MISMATCH'),
                                     ('subject', 'MOTHER', 'FACT_SUBJECT_MISMATCH')]:
            with self.subTest(name=name):
                f = Fixture(self.temp.name)
                f.doc['groups']['father']['targetResult'][name] = value
                with self.assertRaisesRegex(ValueError, message):
                    f.seal().run()

    def test_quote_tamper(self):
        self.f.doc['groups']['father']['targetResult']['citations'][0]['quote'] = 'invented'
        with self.assertRaisesRegex(ValueError, 'CITATION_TEXT_MISMATCH'):
            self.f.seal().run()

    def test_source_bytes_tamper(self):
        (self.f.root / 'facts.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError, 'SOURCE_SHA_MISMATCH'):
            self.f.run()

    def test_absolute_source_path_rejected(self):
        self.f.doc['sources']['facts']['path'] = str(self.f.root / 'facts.json')
        with self.assertRaisesRegex(ValueError, 'SOURCE_PATH_OUTSIDE_PACKAGE'):
            self.f.run()

    def test_unqualified_target_and_inconclusive_parentage(self):
        self.f.set('/groups/testing/qualifiedForTarget', False)
        self.f.set('/groups/parentage/paternity', 'INCONCLUSIVE')
        r = self.f.seal().run()
        self.assertEqual('INSUFFICIENT_DATA', r['status'])
        self.assertTrue({'TARGET_TESTS_NOT_QUALIFIED', 'PARENTAGE_INCONCLUSIVE'} <= self.codes(r))

    def test_expert_condition_cannot_point_into_unrelated_fact(self):
        self.f.doc['conditions']['expertRequirements'] = [{'path': '/groups/gene/symbol', 'acceptedValues': []}]
        with self.assertRaisesRegex(ValueError, 'EXPERT_REQUIREMENT_PATH_INVALID'):
            self.f.seal().run()

    def test_context_tamper_requires_review(self):
        self.f.doc['context']['diseaseId']['value'] = 'OTHER'
        with self.assertRaisesRegex(ValueError, 'CONTEXT_REVIEW_BINDING_MISMATCH'):
            self.f.run()

    def test_similar_allele_does_not_match(self):
        self.f.set('/context/allele', {'assembly': 'GRCh38', 'contig': '1', 'position': 101, 'reference': 'A', 'alternate': 'G'})
        self.assertEqual('NOT_APPLICABLE', self.f.seal().run()['status'])

    def test_ar_requires_extensions_but_is_not_supported(self):
        self.f.set('/context/inheritance', 'AR')
        self.f.set('/conditions/phaseRequired', True)
        r = self.f.seal().run()
        self.assertEqual('SOFTWARE_UNSUPPORTED', r['status'])
        self.assertIn('/extensions/recessive/phase', r['requiredFactPaths'])
        self.assertIn('recessive', r['activeExtensions'])

    def test_xlinked_never_falls_back_to_ad(self):
        self.f.set('/context/inheritance', 'X_LINKED')
        r = self.f.seal().run()
        self.assertEqual('SOFTWARE_UNSUPPORTED', r['status'])
        self.assertIn('/extensions/xlinked/eventPerson', r['requiredFactPaths'])

    def test_mosaic_observation_cannot_hide_in_none_condition(self):
        self.f.set('/groups/mother/mosaicism', 'REPORTED_PRESENT')
        r = self.f.seal().run()
        self.assertEqual('SOFTWARE_UNSUPPORTED', r['status'])
        self.assertIn('mosaic', r['activeExtensions'])

    def test_unverified_mosaic_blocks_review(self):
        self.f.set('/groups/mother/mosaicism', 'REPORTED_PRESENT')
        self.f.doc['groups']['mother']['mosaicism']['verification'] = 'UNVERIFIED'
        self.assertEqual('INSUFFICIENT_DATA', self.f.seal().run()['status'])

    def test_optional_conflict_does_not_disappear(self):
        self.f.doc['groups']['mother']['mosaicism']['verification'] = 'CONFLICT'
        self.assertIn('FACT_CONFLICT', self.codes(self.f.seal().run()))

    def test_expert_required_field_and_value(self):
        self.f.set('/context/expertPolicyStatus', 'APPLICABLE_IMPLEMENTED')
        p = '/extensions/expert/clinicalCheck'
        self.f.doc['extensions']['expert']['clinicalCheck'] = missing(self.f.doc['caseKey'], 'CONTEXT')
        self.f.doc['conditions']['expertRequirements'] = [{'path': p, 'acceptedValues': ['MATCH']}]
        self.assertEqual('INSUFFICIENT_DATA', self.f.seal().run()['status'])
        self.f.set(p, 'OTHER')
        self.assertIn('EXPERT_CONDITION_NOT_MET', self.codes(self.f.seal().run()))
        self.f.set(p, 'MATCH')
        self.assertEqual('READY_FOR_EVALUATION', self.f.seal().run()['status'])

    def test_duplicate_and_possible_overlap_are_different(self):
        self.f.set('/groups/overlap/independence', 'POSSIBLE_OVERLAP')
        self.assertEqual('INSUFFICIENT_DATA', self.f.seal().run()['status'])
        self.f.set('/groups/overlap/independence', 'KNOWN_DUPLICATE')
        self.assertIn('DUPLICATE_NOT_ADDITIONAL_EVENT', self.codes(self.f.seal().run()))

    def test_rule_change_reuses_facts_not_evaluation(self):
        before = self.f.run()
        self.f.set('/context/policyVersion', '2')
        after = self.f.seal().run()
        self.assertEqual(before['verifiedFactsKey'], after['verifiedFactsKey'])
        self.assertNotEqual(before['evaluationKey'], after['evaluationKey'])

    def test_fact_change_invalidates_fact_reuse(self):
        before = self.f.run()
        self.f.set('/groups/proband/genotype', '0/1')
        self.assertNotEqual(before['verifiedFactsKey'], self.f.seal().run()['verifiedFactsKey'])

    def test_review_disagreement_blocks(self):
        old_key = self.f.run()['evaluationKey']
        self.f.doc['reviewPlan']['unresolvedDisagreements'] = ['Mother result disputed']
        with self.assertRaisesRegex(ValueError, 'CONTEXT_REVIEW_BINDING_MISMATCH'):
            self.f.run()
        self.assertIn('REVIEW_DISAGREEMENT_UNRESOLVED', self.codes(self.f.seal().run()))
        self.assertNotEqual(old_key, self.f.run()['evaluationKey'])

    def test_knowledge_bytes_change_invalidates_evaluation(self):
        before = self.f.run()
        self.f.claims['context']['newSourceStatement'] = 'Updated knowledge, same copied category'
        after = self.f.seal().run()
        self.assertEqual(before['verifiedFactsKey'], after['verifiedFactsKey'])
        self.assertNotEqual(before['evaluationKey'], after['evaluationKey'])

    def test_text_attachment_invalidates_extraction_source_key(self):
        before = self.f.run()
        raw = b'Synthetic appendix, not a real paper'
        (self.f.root / 'attachment.txt').write_bytes(raw)
        self.f.doc['sources']['attachment'] = {'path': 'attachment.txt', 'sha256': hashlib.sha256(raw).hexdigest(),
                                               'role': 'PAPER_ATTACHMENT', 'format': 'TEXT', 'pmcid': None}
        with self.assertRaisesRegex(ValueError, 'CONTEXT_REVIEW_BINDING_MISMATCH'):
            self.f.run()
        after = self.f.seal().run()
        self.assertNotEqual(before['extractionSourceKey'], after['extractionSourceKey'])
        self.assertNotEqual(before['evaluationKey'], after['evaluationKey'])

    def test_changed_source_pin_needs_new_review_even_if_quote_survives(self):
        raw = (self.f.root / 'facts.json').read_bytes() + b'\n'
        (self.f.root / 'facts.json').write_bytes(raw)
        self.f.doc['sources']['facts']['sha256'] = hashlib.sha256(raw).hexdigest()
        with self.assertRaisesRegex(ValueError, 'CONTEXT_REVIEW_BINDING_MISMATCH'):
            self.f.run()

    def test_schema_is_closed_and_reproducible(self):
        saved = json.loads(Path(__file__).with_name('core-material.schema.json').read_text(encoding='utf-8'))
        self.assertEqual(SCHEMA, saved)
        self.f.doc['clinicalAward'] = 'PS2'
        with self.assertRaisesRegex(ValueError, 'SCHEMA_UNKNOWN_FIELD'):
            self.f.run()
        with self.assertRaisesRegex(ValueError, 'SCHEMA_KEYWORD_UNSUPPORTED'):
            validate_shape({}, {'unknownValidator': True})

    def test_duplicate_json_and_negative_pointer_rejected(self):
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_JSON_KEY'):
            parse(b'{"x":1,"x":2}')
        with self.assertRaises(ValueError):
            pointer(['a'], '/-1')

    def test_projection_never_grants_verification(self):
        source = {'pmcid': 'PMC1', 'cases': [{'caseLabel': '1', 'fields': [],
                   'targetTesting': {'status': 'PUBLISHED_TRIO_SANGER_DENOVO', 'method': 'SANGER',
                                     'proband': 'PRESENT', 'mother': 'REPORTED_ABSENT', 'father': 'REPORTED_ABSENT'}}]}
        projected = project(source, '1', {}, 'legacy')
        self.assertTrue(all(f['verification'] == 'UNVERIFIED' for f in facts(projected).values()))
        self.assertEqual('ABSENT', projected['groups']['mother']['targetResult']['value'])
        self.assertIsNone(projected['groups']['parentage']['maternity']['value'])


if __name__ == '__main__':
    unittest.main()
