"""Offline checks for provenance, bounded calls and synthetic review safeguards."""
import copy
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from scripts.integration_bs1_ps3.network_acceptance import (
    CONTROLS, PROFILE, RULES, check_budget, parsed,
    require_zero, same_review_records, sha, validate_budget, validate_formal, validate_instance, validate_local_manifest,
    validate_review_copies,
)


class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.source = {'analysisId': 'public-analysis', 'databaseSha256': 'd' * 64, 'jobs': str(Path('original-jobs'))}
        self.args = SimpleNamespace(head='a' * 40, branch='feature/integration', instance_id='instance-' + 'b' * 36,
                                    acceptance_root=Path('private-acceptance'), port=18327)
        self.identity = {'sourceHead': self.args.head, 'branch': self.args.branch, 'instanceId': self.args.instance_id,
                         'profileId': PROFILE, 'port': self.args.port, 'mode': 'JOINT',
                         'transport': 'REAL_NETWORK_HTTP', 'database': 'PRIVATE_H2',
                         'sourceDatabaseSha256': self.source['databaseSha256'],
                         'instanceRoot': str(self.args.acceptance_root / 'network' / self.args.instance_id),
                         'readOnlyPublishedJobsRoot': self.source['jobs'],
                         'businessWorkingRoot': str(self.args.acceptance_root / 'network' / self.args.instance_id / 'business-work'),
                         'executionProfile': {'profileId': PROFILE, 'enabledCriteria': sorted(RULES),
                                              'automaticTrigger': False, 'classificationEnabled': False}}
        rows = []
        for number, (position, reference, alternate) in enumerate(sorted(CONTROLS['ps3'])):
            for moi in ('AD', 'AR'):
                rows.append({'candidateId': f'candidate-{number}-{moi}', 'geneSymbol': 'HNF1A',
                             'candidateIdentity': {'modeOfInheritance': moi},
                             'allele': {'genomeAssembly': 'GRCh38', 'chromosome': '12', 'position': position,
                                        'reference': reference, 'alternate': alternate},
                             'transcriptAnnotations': [{'source': 'Ensembl VEP', 'version': '116.0',
                                                        'proteinChange': 'retained-public-source'}]})
        self.original = {'candidates': rows}
        self.options = {'analysisId': self.source['analysisId'], 'candidates': copy.deepcopy(rows),
                        'enabledCriteria': sorted(RULES)}

    def test_full_json_precision_does_not_hide_nanosecond_difference(self):
        self.assertNotEqual(parsed('{"time":1.123456789012345678}'), parsed('{"time":1.123456789012345679}'))
        self.assertEqual(parsed('{"time":1.123456789012345678}'), parsed('{ "time" : 1.123456789012345678 }'))

    def test_identity_accepts_exact_ten_rule_private_source(self):
        validate_instance(self.identity, self.args, self.source)

    def test_wrong_local_source_is_rejected_before_artifact_creation(self):
        validate_local_manifest(self.identity, self.args, self.source)
        self.identity['sourceHead'] = 'c' * 40
        with self.assertRaises(ValueError):
            validate_local_manifest(self.identity, self.args, self.source)

    def test_duplicate_rule_cannot_replace_missing_ps3(self):
        self.identity['executionProfile']['enabledCriteria'].remove('ACMG_AMP_2015:PS3')
        self.identity['executionProfile']['enabledCriteria'].append('ACMG_AMP_2015:BA1')
        with self.assertRaises(ValueError):
            validate_instance(self.identity, self.args, self.source)

    def test_historical_profile_cannot_impersonate_successor(self):
        self.identity['profileId'] = 'acmg-explicit-business-joint-v4'
        with self.assertRaises(ValueError):
            validate_instance(self.identity, self.args, self.source)

    def test_wrong_database_or_instance_root_rejected(self):
        for field, value in [('sourceDatabaseSha256', 'e' * 64), ('instanceRoot', str(Path('other-instance'))),
                             ('businessWorkingRoot', self.source['jobs'])]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.identity)
                changed[field] = value
                with self.assertRaises(ValueError):
                    validate_instance(changed, self.args, self.source)

    def test_formal_candidates_are_original_and_keep_order(self):
        validate_formal(self.options, self.original, 'ps3', self.source)
        self.options['candidates'].reverse()
        with self.assertRaises(ValueError):
            validate_formal(self.options, self.original, 'ps3', self.source)

    def test_changed_vep_annotation_is_not_a_reusable_candidate(self):
        self.options['candidates'][0]['transcriptAnnotations'][0]['version'] = '115.0'
        with self.assertRaises(ValueError):
            validate_formal(self.options, self.original, 'ps3', self.source)

    def test_changed_recommendation_is_allowed_but_not_changed_original_allele(self):
        self.options['candidates'][0]['recommendation'] = {'policyVersion': 'new-explicit-joint'}
        validate_formal(self.options, self.original, 'ps3', self.source)
        self.options['candidates'][0]['allele']['alternate'] = 'G'
        with self.assertRaises(ValueError):
            validate_formal(self.options, self.original, 'ps3', self.source)

    def test_copied_but_out_of_panel_source_is_rejected(self):
        for data in (self.options, self.original):
            data['candidates'][0]['allele']['position'] += 1
        with self.assertRaises(ValueError):
            validate_formal(self.options, self.original, 'ps3', self.source)


class BudgetAndReviewTest(unittest.TestCase):
    def setUp(self):
        self.budget = {'provider': 30, 'evaluator': 60, 'vepComparator': 3, 'spliceRawBatch': 2, 'pm2AllSitesBatch': 1}
        self.counts = dict(self.budget, exomiser=0, vepPublication=0, blockedModelLaunch=0)

    def test_explicit_reference_budget_allows_no_original_model_rerun(self):
        check_budget({'callIncrement': self.counts}, self.budget)
        self.counts['exomiser'] = 1
        with self.assertRaises(AssertionError):
            check_budget({'callIncrement': self.counts}, self.budget)

    def test_missing_budget_is_rejected_before_post(self):
        del self.budget['vepComparator']
        with self.assertRaises(ValueError):
            validate_budget(self.budget)

    def test_reference_overrun_or_missing_observation_fails(self):
        self.counts['spliceRawBatch'] = 3
        with self.assertRaises(AssertionError):
            check_budget({'callIncrement': self.counts}, self.budget)
        self.counts['spliceRawBatch'] = 2
        del self.counts['blockedModelLaunch']
        with self.assertRaises(AssertionError):
            check_budget({'callIncrement': self.counts}, self.budget)

    def test_read_and_review_must_have_every_counter_zero(self):
        require_zero({'callIncrement': {'functionalProvider': 0, 'evaluator': 0}})
        with self.assertRaises(AssertionError):
            require_zero({'callIncrement': {'functionalProvider': 1, 'evaluator': 0}})

    def test_review_order_is_not_guessed_but_duplicate_or_mutation_rejected(self):
        records = [{'reviewRecord': {'reviewRecordId': name, 'decision': decision}}
                   for name, decision in [('r1', 'NONE'), ('r2', 'ACCEPTED'), ('r3', 'REJECTED')]]
        self.assertTrue(same_review_records(list(reversed(records)), records))
        self.assertFalse(same_review_records([records[0], records[0], records[2]], records))
        changed = copy.deepcopy(records)
        changed[2]['reviewRecord']['decision'] = 'ACCEPTED'
        self.assertFalse(same_review_records(changed, records))

    def test_review_copy_must_be_identical_and_confined(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raw = b'{"reviewRecordId":"retained-historical-id"}'
            original = root / 'original.json'
            original.write_bytes(raw)
            work = root / 'work'
            (work / 'functional-experiment-reviews').mkdir(parents=True)
            copied = work / 'functional-experiment-reviews' / 'copy.json'
            copied.write_bytes(raw)
            entry = {'path': str(original), 'bytes': len(raw), 'sha256': sha(raw), 'relativePath': 'copy.json'}
            source = {'functionalReviewFiles': [entry]}
            validate_review_copies(source, work)
            copied.write_bytes(b'{"changed":true}')
            with self.assertRaises(ValueError):
                validate_review_copies(source, work)
            entry['relativePath'] = '../../original.json'
            with self.assertRaises(ValueError):
                validate_review_copies(source, work)

    def test_ps3_cannot_omit_any_of_the_eight_historical_reviews(self):
        with self.assertRaises(ValueError):
            validate_review_copies({'dataset': 'ps3', 'externalReviewTotal': 8, 'functionalReviewFiles': []}, Path('work'))


if __name__ == '__main__':
    unittest.main()
