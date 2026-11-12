"""Synthetic adversarial/source controls, zero live calls or clinical adoption."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_bundle_qualification import article, registered, candidate
from bundle_model_contract import review
from qualify_bundle_candidates import qualify
from core_material_extraction import write_package
from core_material_review import review_package, read_input
from core_material_contract import facts, validate, digest as object_digest
from model_literature_contract import canonical, digest, parse


class SourceReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def setup_source(self, extra='', raw=None, proposals=None):
        raw = article() if raw is None else raw
        raw = raw.replace(b'Case A was studied.', ('Case A was studied. ' + extra).encode())
        self.library, self.source = registered(self.root, raw)
        proposed = candidate(self.source)
        if proposals:
            proposals(proposed)
        checked = review(self.source, proposed)
        checked['workUnit'] = {'targetUnitIds': [checked['cases'][0]['caseCitation']['unitId']]}
        view = qualify(self.source, [checked])
        self.input = self.root / 'input'
        write_package(self.source, view, [checked], self.input, data_class='SYNTHETIC')
        self.pin = digest((self.input / 'index.json').read_bytes())

    def run_review(self, name='review'):
        index = review_package(self.source, self.input, self.pin, self.root / name)
        case = index['cases'][0]
        doc = parse((self.root / name / case['path']).read_bytes())
        report = parse((self.root / name / case['reportPath']).read_bytes())
        ledger = parse((self.root / name / case['ledgerPath']).read_bytes())
        validate(doc, self.root / name, ledger, object_digest(ledger))
        return index, doc, report, ledger

    def test_native_source_rederives_trio_and_parentage_no_score(self):
        self.setup_source()
        index, doc, report, ledger = self.run_review()
        self.assertEqual('ABSENT', doc['groups']['mother']['targetResult']['value'])
        self.assertEqual('VERIFIED', doc['groups']['mother']['targetResult']['verification'])
        self.assertEqual('CONFIRMED', doc['groups']['parentage']['paternity']['value'])
        self.assertEqual('VERIFIED', doc['groups']['parentage']['paternity']['verification'])
        self.assertEqual('UNVERIFIED', doc['groups']['testing']['qualifiedForTarget']['verification'])
        self.assertEqual(0, index['modelCalls']); self.assertEqual(0, index['networkCalls'])
        self.assertFalse(index['businessPublishable']); self.assertEqual('NOT_RUN', index['criterionDecision'])
        self.assertFalse(any(p.startswith('/context/') for p in ledger['checks']))

    def test_second_pass_recovers_omitted_age_and_negatives(self):
        self.setup_source('The proband was 5-year-old. The proband had no seizures. The proband had ataxia.')
        index, doc, report, _ = self.run_review()
        self.assertEqual('5-year-old', doc['groups']['phenotype']['age']['value'])
        self.assertEqual(['seizures'], doc['groups']['phenotype']['negative']['value'])
        self.assertIn('ataxia', doc['groups']['phenotype']['positive']['value'])
        self.assertIn('/groups/phenotype/age', index['cases'][0]['recoveredFields'])
        self.assertEqual('PARTIAL', doc['reviewPlan']['secondPassStatus'])

    def test_mother_symptoms_not_assigned_to_proband(self):
        self.setup_source('The mother had migraine. The proband had no seizures.')
        _, doc, _, _ = self.run_review()
        self.assertNotIn('migraine', str(doc['groups']['phenotype']['positive']['value']))
        self.assertNotIn('seizures', str(doc['groups']['phenotype']['positive']['value']))

    def test_scope_absence_requires_explicit_statement(self):
        self.setup_source('The age was not reported.')
        _, doc, _, _ = self.run_review()
        age = doc['groups']['phenotype']['age']
        self.assertEqual('NOT_REPORTED_IN_CHECKED_SCOPE', age['availability'])
        self.assertIsNone(age['value']); self.assertTrue(age['checkedLocations'])
        self.assertEqual('NOT_EXTRACTED', doc['groups']['phenotype']['onset']['availability'])

    def test_no_matching_pattern_is_not_absence(self):
        self.setup_source('Adolescence was the relevant period.')
        _, doc, report, _ = self.run_review()
        self.assertEqual('NOT_EXTRACTED', doc['groups']['phenotype']['age']['availability'])
        self.assertEqual('NO_MATCH_IS_NOT_SOURCE_ABSENCE', report['missingMeaning'])

    def test_parentage_scoped_silence_stays_null_and_only_evaluation_view_is_assumed(self):
        from core_material_contract import evaluation_value
        raw = article().replace(b'SNP analysis confirmed both maternity and paternity.', b'')
        self.setup_source(raw=raw)
        _, doc, _, ledger = self.run_review()
        for name in ('maternity', 'paternity'):
            path = '/groups/parentage/' + name; fact = doc['groups']['parentage'][name]
            self.assertIsNone(fact['value']); self.assertEqual('NOT_REPORTED_IN_CHECKED_SCOPE', fact['availability'])
            self.assertTrue(fact['checkedLocations']); self.assertIn(path, ledger['checks'])
            self.assertEqual('ASSUMED_NOT_REPORTED', evaluation_value(path, fact))
            unreviewed = deepcopy(fact); unreviewed['verification']='UNVERIFIED'
            self.assertIsNone(evaluation_value(path, unreviewed))

    def test_unresolved_parentage_wording_is_not_silence(self):
        raw = article().replace(b'SNP analysis confirmed both maternity and paternity.',
                                b'DNA fingerprinting results were inconclusive.')
        self.setup_source(raw=raw)
        _, doc, _, _ = self.run_review()
        self.assertNotEqual('NOT_REPORTED_IN_CHECKED_SCOPE', doc['groups']['parentage']['paternity']['availability'])

    def test_multiple_direct_ages_leave_disagreement(self):
        self.setup_source('The proband was 5-year-old. The proband was 6-year-old.')
        _, doc, report, ledger = self.run_review()
        age = doc['groups']['phenotype']['age']
        self.assertEqual('CONFLICT', age['verification']); self.assertIsNone(age['value'])
        self.assertNotIn('/groups/phenotype/age', ledger['checks'])
        self.assertIn('/groups/phenotype/age', doc['reviewPlan']['unresolvedDisagreements'])

    def test_unverified_model_agreement_does_not_create_proof(self):
        def proposal(value):
            row = value['cases'][0]; ref = deepcopy(row['fields'][0]['citation'])
            row['fields'].append({'field': 'reportedPhenotype', 'sourceValue': 'migraine', 'subject': 'PROBAND', 'citation': ref})
        self.setup_source('The mother had migraine.', proposals=proposal)
        _, doc, report, _ = self.run_review()
        self.assertNotIn('migraine', str(doc['groups']['phenotype']['positive']['value']))
        audit = next(c for c in report['claims'] if c['rawField']['sourceValue'] == 'migraine')
        self.assertEqual('UNRESOLVED_CLAIM_NOT_ADOPTED', audit['status'])

    def test_other_case_text_not_borrowed(self):
        self.setup_source('Case B was 5-year-old. The proband had seizures.')
        _, doc, _, _ = self.run_review()
        self.assertEqual('NOT_EXTRACTED', doc['groups']['phenotype']['age']['availability'])
        self.assertNotIn('seizures', str(doc['groups']['phenotype']['positive']['value']))

    def test_not_tested_never_becomes_absent(self):
        self.setup_source('The father was not tested.')
        _, doc, _, ledger = self.run_review()
        self.assertNotIn('/groups/father/targetResult', ledger['checks'])
        self.assertIn('The father was not tested.', doc['groups']['testing']['limitations']['value'])

    def test_mixed_case_assay_not_borrowed_from_heading(self):
        self.setup_source('Case B was studied in this paragraph.')
        _, doc, _, ledger = self.run_review()
        self.assertNotIn('/groups/mother/targetResult', ledger['checks'])
        self.assertNotIn('/groups/parentage/paternity', ledger['checks'])

    def test_negative_table_cell_not_verified_as_positive(self):
        self.setup_source(raw=article().replace(b'Synthetic trait', b'No ataxia'))
        _, doc, _, ledger = self.run_review()
        self.assertNotIn('/groups/phenotype/positive', ledger['checks'])

    def test_same_trait_affirmed_and_denied_keeps_conflict(self):
        self.setup_source('The proband had seizures. The proband had no seizures.')
        _, doc, _, ledger = self.run_review()
        for polarity in ('positive', 'negative'):
            self.assertEqual('CONFLICT', doc['groups']['phenotype'][polarity]['verification'])
            self.assertNotIn('/groups/phenotype/' + polarity, ledger['checks'])

    def test_parent_phenotype_in_case_table_is_not_patient_fact(self):
        self.setup_source(raw=article().replace(b'Synthetic trait', b'Mother had migraine'))
        _, doc, _, ledger = self.run_review()
        self.assertNotIn('/groups/phenotype/positive', ledger['checks'])

    def test_trait_in_list_and_negative_still_requires_reconciliation(self):
        self.setup_source('The proband had seizures and ataxia. The proband had no seizures.')
        _, doc, _, ledger = self.run_review()
        self.assertEqual('CONFLICT', doc['groups']['phenotype']['positive']['verification'])
        self.assertNotIn('/groups/phenotype/positive', ledger['checks'])

    def test_arbitrary_discussion_case_heading_not_owned(self):
        self.setup_source('The proband was 5-year-old.', raw=article().replace(b'<title>Results</title>', b'<title>Discussion</title>'))
        _, doc, _, ledger = self.run_review()
        self.assertNotIn('/groups/phenotype/age', ledger['checks'])

    def test_missing_core_review_blocks_workflow_ready(self):
        import public_literature_workflow as workflow
        from test_bundle_model import configuration
        self.setup_source()
        local = self.root / 'local'; local.mkdir()
        (local / 'model.json').write_bytes(canonical(configuration()))
        config = {'schemaVersion': workflow.VERSION, 'enabled': True, 'projectRoot': str(self.root),
                  'library': 'local/library', 'searchCache': 'local/search', 'modelBudget': 'local/budget',
                  'modelConfig': 'local/model.json', 'allowModel': False}
        path = self.root / 'workflow.json'; path.write_bytes(canonical(config))
        request = self.root / 'request.json'
        request.write_bytes(canonical({'schemaVersion': workflow.VERSION, 'genes': ['EXAMPLE'], 'selectionSha256': 'a' * 64,
            'continuation': {'parentTaskId': 'a' * 64, 'parentResultSha256': 'b' * 64, 'pmcid': self.source['pmcid'],
                             'bundleSha256': self.source['bundleSha256'], 'plannedChunks': 1, 'completedTargets': []}}))
        def process(*args):
            item = {'completeTargetExtraction': True, 'coreMaterial': {'status':'EXTRACTED_NOT_VERIFIED'}, 'issues': []}
            args[7]['papers'].append(item)
            return item
        out = self.root / 'work'; out.mkdir()
        with patch('public_literature_workflow.process_paper', side_effect=process):
            result = workflow.run(request, out / 'result.json', path)
        self.assertEqual('PARTIAL', result['status'])
        self.assertEqual(0, result['modelCalls'])

    def test_input_and_source_metadata_pins_enforced(self):
        self.setup_source()
        with self.assertRaisesRegex(ValueError, 'INDEX_PIN_MISMATCH'):
            review_package(self.source, self.input, '0' * 64, self.root / 'bad')
        other = deepcopy(self.source); other['coverage']['status'] = 'DIFFERENT'
        with self.assertRaisesRegex(ValueError, 'SOURCE_SCOPE_CHANGED'):
            review_package(other, self.input, self.pin, self.root / 'bad')
        self.assertFalse((self.root / 'bad').exists())

    def test_changed_source_and_claims_rejected(self):
        self.setup_source()
        (self.input / 'observations.json').write_bytes(b'[]')
        with self.assertRaisesRegex(ValueError, 'INPUT_PIN_MISMATCH'):
            self.run_review()

    def test_forged_case_pin_rejected(self):
        self.setup_source()
        index = parse((self.input / 'index.json').read_bytes())
        path = self.input / index['cases'][0]['path']; doc = parse(path.read_bytes())
        doc['groups']['mother']['targetResult']['value'] = 'PRESENT'
        path.write_bytes(canonical(doc))
        with self.assertRaisesRegex(ValueError, 'CASE_PIN_MISMATCH'): self.run_review()

    def test_forged_input_verified_even_with_changed_index_rejected(self):
        self.setup_source()
        index = parse((self.input / 'index.json').read_bytes())
        path = self.input / index['cases'][0]['path']; doc = parse(path.read_bytes())
        doc['groups']['mother']['targetResult']['verification'] = 'VERIFIED'
        path.write_bytes(canonical(doc)); index['cases'][0]['sha256'] = digest(path.read_bytes())
        (self.input / 'index.json').write_bytes(canonical(index)); self.pin = digest(canonical(index))
        with self.assertRaisesRegex(ValueError, 'INPUT_ALREADY_VERIFIED'): self.run_review()

    def test_forged_derived_target_is_not_adopted(self):
        self.setup_source()
        index = parse((self.input / 'index.json').read_bytes())
        path = self.input / index['cases'][0]['path']; doc = parse(path.read_bytes())
        doc['groups']['mother']['targetResult']['value'] = 'PRESENT'
        path.write_bytes(canonical(doc)); index['cases'][0]['sha256'] = digest(path.read_bytes())
        (self.input / 'index.json').write_bytes(canonical(index)); self.pin = digest(canonical(index))
        _, reviewed, _, _ = self.run_review()
        self.assertEqual('ABSENT', reviewed['groups']['mother']['targetResult']['value'])

    def test_limit_leaves_all_semantics_unverified(self):
        self.setup_source()
        with patch('core_source_review.MAX_SCOPE_BYTES', 1):
            _, doc, report, ledger = self.run_review()
        self.assertEqual('SCOPE_LIMIT_EXCEEDED', report['scope']['status'])
        self.assertTrue(all(p.startswith('/groups/paper/') for p in ledger['checks']))
        self.assertEqual([], doc['reviewPlan']['checkedScope'])

    def test_originals_immutable_and_replay_deterministic(self):
        self.setup_source()
        before = {p.name: digest(p.read_bytes()) for p in self.input.iterdir()}
        first = self.run_review('one'); second = self.run_review('two')
        self.assertEqual(canonical(first), canonical(second))
        self.assertEqual(before, {p.name: digest(p.read_bytes()) for p in self.input.iterdir()})
        with self.assertRaisesRegex(ValueError, 'NEW_OUTPUT_REQUIRED'): self.run_review('one')

    def test_proof_mutation_cannot_reuse_ledger(self):
        self.setup_source(); index, doc, _, ledger = self.run_review()
        doc['groups']['mother']['targetResult']['value'] = 'PRESENT'
        with self.assertRaisesRegex(ValueError, 'FACT_REVIEW_REQUIRED'):
            validate(doc, self.root / 'review', ledger, object_digest(ledger))

    def test_unknown_attachments_remain_scope_gap(self):
        self.setup_source()
        self.source['coverage'] = {'status': 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED', 'reasonCode': 'PAPER_DOWNLOAD_TIMEOUT'}
        # Rebuild a genuinely pinned stage 1 package for this declared coverage.
        checked = review(self.source, candidate(self.source)); checked['workUnit'] = {'targetUnitIds': []}
        view = qualify(self.source, [checked])
        self.input = self.root / 'partial'
        write_package(self.source, view, [checked], self.input, data_class='SYNTHETIC')
        self.pin = digest((self.input / 'index.json').read_bytes())
        _, doc, report, _ = self.run_review()
        self.assertIn('SUPPLEMENT_SCOPE:PAPER_DOWNLOAD_TIMEOUT', report['unavailableSources'])
        self.assertEqual('NOT_EXTRACTED', doc['groups']['proband']['mosaicism']['availability'])


if __name__ == '__main__':
    unittest.main()
