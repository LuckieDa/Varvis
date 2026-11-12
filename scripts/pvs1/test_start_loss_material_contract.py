import copy
import unittest

from assemble_materials import canonical, sha
from start_loss_material_contract import SCHEMA, validate


def fixture():
    """Synthetic declarations, explicitly NOT validated scientific source facts."""
    raw = canonical({'policy': 'TEST_ONLY', 'mechanism': 'TEST_ONLY',
                     'structure': 'TEST_ONLY', 'transcripts': 'TEST_ONLY',
                     'reference': 'TEST_ONLY', 'search': {'status': 'complete'},
                     'a/b': {'~value': ['TEST_ONLY']}})
    ref = lambda path: [{'sourceId': 'test', 'jsonPointer': '/' + path}]
    bg = {'hgncId': 'HGNC:123', 'mondoId': 'MONDO:0000001', 'moi': 'AUTOSOMAL_DOMINANT',
          'transcript': 'ENST000001.1'}
    target = {'assembly': 'GRCh38', 'contig': '1', 'position': 100, 'reference': 'A', 'alternate': 'G'}
    material = {'schemaVersion': SCHEMA, 'dataClass': 'SYNTHETIC', 'runtimeEligible': False,
                'context': {'allele': target, 'background': bg},
                'policy': {'status': 'GENERAL_SELECTED', 'policyId': 'TEST_ONLY', 'version': '1',
                           'sha256': sha(raw), 'citations': ref('policy')},
                'mechanism': {'status': 'ESTABLISHED_LOF', 'citations': ref('mechanism')},
                'startEdit': {'status': 'AVAILABLE', 'strand': 1, 'cdsPosition': 1,
                              'referenceCodon': 'ATG', 'alternateCodon': 'GTG',
                              'modelSha256': 'a'*64, 'citations': ref('structure')},
                'transcriptReview': {'status': 'NOT_FOUND_IN_ASSESSED_SET', 'complete': True,
                    'checkedTranscripts': [bg['transcript']], 'findings': [
                        {'transcript': bg['transcript'], 'functionallyRelevant': True, 'differentStart': False,
                         'targetUnaffected': False, 'citations': ref('transcripts')}],
                    'issues': [], 'citations': ref('transcripts')},
                'restart': {'status': 'CANDIDATE_FOUND', 'cdsPosition': 10, 'citations': ref('structure')},
                'upstreamReview': {'status': 'NONE_IN_ASSESSED_SOURCES', 'records': [],
                    'requiredSourceIds': ['test'], 'queries': [
                        {'sourceId': 'test', 'status': 'COMPLETE', 'cdsStart': 1, 'cdsEnd': 9,
                         'citations': ref('search')}], 'issues': []},
                'sources': [{'sourceId': 'test', 'sha256': sha(raw), 'sourceUrl': 'https://example.invalid/test',
                             'capturedAt': '2026-11-01T00:00:00Z', 'dataClass': 'SYNTHETIC',
                             'captureStatus': 'AVAILABLE', 'reason': None}]}
    return material, {'test': raw}


class StartLossContractTest(unittest.TestCase):
    def setUp(self):
        self.material, self.raw = fixture()
        self.context = copy.deepcopy(self.material['context'])

    def run_check(self):
        return validate(self.material, self.context, self.raw)

    def rejected(self, mutate, reason):
        original = copy.deepcopy(self.material)
        mutate(self.material)
        with self.assertRaisesRegex(ValueError, reason):
            self.run_check()
        self.material = original

    def reference(self):
        record = {'allele': dict(self.context['allele'], position=103, alternate='T'),
                  'background': copy.deepcopy(self.context['background']), 'cdsStart': 4, 'cdsEnd': 4,
                  'classification': 'PATHOGENIC', 'eligibility': 'ELIGIBLE', 'reason': 'TEST_ONLY_DECLARATION',
                  'citations': [{'sourceId': 'test', 'jsonPointer': '/reference'}]}
        self.material['upstreamReview'].update(status='QUALIFYING_FOUND', records=[record])
        return record

    def test_complete_declaration_never_scores_and_does_not_mutate(self):
        before = copy.deepcopy(self.material)
        result = self.run_check()
        self.assertEqual('STRUCTURALLY_VALID', result['status'])
        self.assertTrue(result['requiresSemanticReplay'])
        self.assertFalse(result['runtimeEligible'])
        self.assertFalse(result['scientificEvaluationPerformed'])
        self.assertNotIn('appliedStrength', result)
        self.assertEqual(before, self.material)
        self.assertEqual(result, self.run_check())

    def test_source_bytes_and_pointers_are_checked(self):
        self.raw['test'] += b' '
        with self.assertRaisesRegex(ValueError, 'SOURCE_SHA_MISMATCH'): self.run_check()
        self.raw['test'] = self.raw['test'][:-1]
        self.rejected(lambda m: m['policy']['citations'][0].update(jsonPointer='/missing'), 'SOURCE_POINTER_INVALID')
        self.rejected(lambda m: m['policy']['citations'][0].update(sourceId='absent'), 'CITATION_SOURCE_MISSING')
        self.rejected(lambda m: m['policy']['citations'][0].update(jsonPointer='/a~2b'), 'SOURCE_POINTER_INVALID')
        self.material['policy']['citations'][0]['jsonPointer'] = '/a~1b/~0value/0'
        self.run_check()
        self.rejected(lambda m: m['policy']['citations'][0].update(jsonPointer='/a~1b/~0value/-1'), 'SOURCE_POINTER_INVALID')

    def test_duplicate_json_fields_and_nonfinite_values_are_rejected(self):
        for raw in (b'{"policy":1,"policy":2}', b'{"policy":NaN}'):
            with self.subTest(raw=raw):
                self.raw['test'] = raw
                self.material['sources'][0]['sha256'] = sha(raw)
                with self.assertRaises(ValueError): self.run_check()

    def test_context_is_bound_to_independent_caller_input(self):
        for block, key, value in [('background', 'mondoId', 'MONDO:0000002'),
                                  ('background', 'transcript', 'ENST000001.2'),
                                  ('background', 'moi', 'AUTOSOMAL_RECESSIVE'),
                                  ('allele', 'position', 101)]:
            self.rejected(lambda m: m['context'][block].update({key: value}), 'CONTEXT_MISMATCH')

    def test_unknown_and_missing_are_preserved_not_normalized_to_absence(self):
        self.material['transcriptReview'].update(status='UNRESOLVED', complete=False,
                                                findings=[], checkedTranscripts=[], citations=[], issues=['NOT_FETCHED'])
        self.material['upstreamReview'].update(status='INCOMPLETE', records=[], queries=[], issues=['TIMEOUT'])
        before = copy.deepcopy(self.material)
        result = self.run_check()
        self.assertEqual(before, self.material)
        self.assertFalse(result['runtimeEligible'])

    def test_transcript_negative_requires_complete_assessed_set(self):
        self.rejected(lambda m: m['transcriptReview'].update(complete=False), 'NEGATIVE_TRANSCRIPT_REVIEW_INCOMPLETE')
        self.rejected(lambda m: m['transcriptReview'].update(findings=[]), 'NEGATIVE_TRANSCRIPT_REVIEW_INCOMPLETE')
        self.rejected(lambda m: m['transcriptReview'].update(issues=['PAGE_TRUNCATED']), 'NEGATIVE_TRANSCRIPT_REVIEW_INCOMPLETE')
        def uncertain_alternative(m):
            review = m['transcriptReview']
            review['checkedTranscripts'].append('ENST000002.1')
            review['findings'].append(dict(review['findings'][0], transcript='ENST000002.1',
                                          functionallyRelevant=None, differentStart=True, targetUnaffected=True))
        self.rejected(uncertain_alternative, 'NEGATIVE_TRANSCRIPT_REVIEW_INCOMPLETE')

    def test_found_alternative_requires_function_start_and_target_witness(self):
        review = self.material['transcriptReview']
        review['status'] = 'FOUND'
        with self.assertRaisesRegex(ValueError, 'ALTERNATIVE_TRANSCRIPT_WITNESS_REQUIRED'): self.run_check()
        review['checkedTranscripts'].append('ENST000002.1')
        review['findings'].append(dict(review['findings'][0], transcript='ENST000002.1',
                                      functionallyRelevant=True, differentStart=True, targetUnaffected=True))
        self.run_check()

    def test_negative_reference_requires_all_sources_and_exact_interval(self):
        for status in ('TIMEOUT', 'TRUNCATED', 'UNAVAILABLE'):
            self.rejected(lambda m: m['upstreamReview']['queries'][0].update(status=status),
                          'NEGATIVE_REFERENCE_SEARCH_INCOMPLETE')
        self.rejected(lambda m: m['upstreamReview'].update(queries=[]), 'NEGATIVE_REFERENCE_SEARCH_INCOMPLETE')
        self.rejected(lambda m: m['upstreamReview'].update(requiredSourceIds=[]), 'SEARCH_RECEIPT_INVALID')
        self.rejected(lambda m: m['upstreamReview']['queries'][0].update(cdsEnd=8), 'NEGATIVE_REFERENCE_SEARCH_INCOMPLETE')
        self.rejected(lambda m: m['upstreamReview'].update(issues=['UNREAD_ATTACHMENT']), 'NEGATIVE_REFERENCE_SEARCH_INCOMPLETE')

    def test_reference_target_wrong_background_lp_and_boundary_cannot_be_eligible(self):
        self.reference()
        for field, value in [('allele', self.context['allele']),
                             ('background', dict(self.context['background'], mondoId='MONDO:0000002')),
                             ('classification', 'LIKELY_PATHOGENIC'), ('cdsEnd', 10)]:
            self.rejected(lambda m: m['upstreamReview']['records'][0].update({field: value}),
                          'REFERENCE_ELIGIBILITY_CONTRADICTION')

    def test_reference_duplicates_are_not_independent_hits(self):
        record = self.reference()
        self.assertEqual(1, self.run_check()['declaredEligibleReferenceCount'])
        self.material['upstreamReview']['records'].append(copy.deepcopy(record))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_ELIGIBLE_REFERENCE'): self.run_check()

    def test_pending_reference_prevents_negative_declaration(self):
        record = self.reference()
        record.update(eligibility='UNRESOLVED', classification='CONFLICT')
        self.material['upstreamReview']['status'] = 'NONE_IN_ASSESSED_SOURCES'
        with self.assertRaisesRegex(ValueError, 'NEGATIVE_REFERENCE_SEARCH_INCOMPLETE'): self.run_check()
        self.material['upstreamReview']['status'] = 'CONFLICT'
        self.run_check()

    def test_restart_candidate_is_in_frame_not_claimed_translation(self):
        for value in (True, 4.0, 3, 5):
            self.rejected(lambda m: m['restart'].update(cdsPosition=value), 'RESTART_POSITION_INVALID')
        self.material['restart'].update(status='NO_CANDIDATE', cdsPosition=None)
        self.material['upstreamReview'].update(status='INCOMPLETE', queries=[], issues=['NO_RESTART'])
        self.run_check()

    def test_malformed_statuses_fail_as_contract_errors(self):
        for block in ('policy', 'mechanism', 'startEdit', 'transcriptReview', 'restart', 'upstreamReview'):
            self.rejected(lambda m: m[block].update(status=[]), 'MALFORMED_INPUT')

    def test_unsupported_policy_preserves_known_identity(self):
        self.material['policy']['status'] = 'UNSUPPORTED'
        before = copy.deepcopy(self.material['policy'])
        self.run_check()
        self.assertEqual(before, self.material['policy'])

    def test_mitochondrial_edit_and_self_alternative_are_rejected(self):
        self.material['context']['allele']['contig'] = 'MT'
        self.context['allele']['contig'] = 'MT'
        with self.assertRaisesRegex(ValueError, 'EDIT_OUTSIDE_FIRST_RELEASE'): self.run_check()
        self.material['context']['allele']['contig'] = self.context['allele']['contig'] = '1'
        self.rejected(lambda m: m['transcriptReview']['findings'][0].update(differentStart=True),
                      'SELECTED_TRANSCRIPT_IS_NOT_ALTERNATIVE')

    def test_no_score_injection_or_synthetic_relabeling(self):
        self.rejected(lambda m: m.update(appliedStrength='VERY_STRONG'), 'FIELDS_INVALID')
        self.rejected(lambda m: m.update(runtimeEligible=True), 'MATERIAL_USE_INVALID')
        self.rejected(lambda m: m.update(dataClass='PUBLIC_REFERENCE'), 'SOURCE_CLASS_MISMATCH')
        self.rejected(lambda m: m.update(schemaVersion='pvs1-start-loss-candidate-material-v99'), 'SCHEMA_UNSUPPORTED')

    def test_source_scheme_time_and_count_are_bounded(self):
        self.rejected(lambda m: m['sources'][0].update(sourceUrl='file:///secret'), 'SOURCE_METADATA_INVALID')
        self.rejected(lambda m: m['sources'][0].update(capturedAt='2026-11-01'), 'SOURCE_METADATA_INVALID')
        self.rejected(lambda m: m['sources'].append(copy.deepcopy(m['sources'][0])), 'SOURCE_IDENTITY_INVALID')
        self.rejected(lambda m: m.update(sources=m['sources']*129), 'SOURCE_LIMIT')

    def test_unavailable_sources_keep_receipts_without_inventing_originals(self):
        source = dict(self.material['sources'][0], sourceId='offline', sha256=None,
                      captureStatus='UNAVAILABLE', reason='HTTP_TIMEOUT')
        self.material['sources'].append(source)
        upstream = self.material['upstreamReview']
        upstream['requiredSourceIds'].append('offline')
        upstream['queries'].append({'sourceId': 'offline', 'status': 'UNAVAILABLE',
                                    'cdsStart': 1, 'cdsEnd': 9, 'citations': []})
        upstream.update(status='INCOMPLETE', issues=['SOURCE_UNAVAILABLE'])
        self.assertEqual(1, self.run_check()['unavailableSourceCount'])
        self.rejected(lambda m: m['upstreamReview'].update(status='NONE_IN_ASSESSED_SOURCES', issues=[]),
                      'NEGATIVE_REFERENCE_SEARCH_INCOMPLETE')
        self.raw['offline'] = b''
        with self.assertRaisesRegex(ValueError, 'UNAVAILABLE_SOURCE_HAS_FAKE_ORIGINAL'): self.run_check()

    def test_unsupported_edit_is_retained_without_fake_nmd_or_sequence(self):
        self.material['startEdit'] = dict.fromkeys(self.material['startEdit'])
        self.material['startEdit'].update(status='UNSUPPORTED', citations=[])
        self.run_check()
        self.rejected(lambda m: m['startEdit'].update(nmdPrediction='PREDICTED'), 'FIELDS_INVALID')

    def test_not_yet_prepared_input_does_not_require_invented_sources(self):
        self.material['sources'] = []
        self.raw = {}
        self.material['policy'].update(status='UNRESOLVED', policyId=None, version=None, sha256=None, citations=[])
        self.material['mechanism'].update(status='UNRESOLVED', citations=[])
        self.material['startEdit'] = dict.fromkeys(self.material['startEdit'])
        self.material['startEdit'].update(status='UNRESOLVED', citations=[])
        self.material['transcriptReview'].update(status='UNRESOLVED', complete=False,
            checkedTranscripts=[], findings=[], issues=['NOT_PREPARED'], citations=[])
        self.material['restart'].update(status='UNRESOLVED', cdsPosition=None, citations=[])
        self.material['upstreamReview'].update(status='INCOMPLETE', records=[], requiredSourceIds=[],
                                               queries=[], issues=['NOT_PREPARED'])
        result = self.run_check()
        self.assertEqual(0, result['sourceCount'])
        self.assertFalse(result['runtimeEligible'])


if __name__ == '__main__':
    unittest.main()
