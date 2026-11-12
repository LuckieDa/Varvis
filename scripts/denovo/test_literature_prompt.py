"""Offline synthetic controls; no real model execution or clinical validation."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from prepare_literature_prompt import (SCHEMA_VERSION, SYSTEM, request, validate_candidates,
    plan, review, supplement_coverage, MAX_REQUEST_BYTES)
from model_literature_contract import canonical, check_shape, digest, model_request
from prepare_literature import prepare
from test_literature_source_assertions import ARTICLE, PMCID, sha


RAW = ARTICLE.replace(b'<body>', b'''<body><sec><title>Methods</title>
<p>We recruited Case A in this study. Case B was described only in a previous publication.
Case A and Case B must not be merged. No parentage testing was performed for Case A.</p></sec>''').replace(
    b'</body>', b'''<sec><title>Case B</title><p>Case B had no seizures.</p></sec>
<supplementary-material id="supp1" xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="supp1.xlsx">
<caption><p>Supplementary table 1: additional patients.</p></caption></supplementary-material>
</body><back><ref-list><ref id="r1"><mixed-citation>Case B: previously published case.</mixed-citation></ref></ref-list></back>''')


def document(raw=RAW):
    return prepare(raw, PMCID, sha(raw))


def citation(fragment, raw=RAW):
    u = next(u for u in document(raw)['units'] if fragment in u['text'])
    return {'unitId': u['unitId'], 'quote': u['text']}


def candidates(raw=RAW):
    return {'schemaVersion': SCHEMA_VERSION, 'cases': [
        {'caseLabel': 'A', 'caseCitation': citation('Case A', raw), 'caseOrigin': 'AUTHOR_COHORT',
         'originCitations': [citation('We recruited Case A', raw)], 'fields': [
             {'field': 'reportedParentageTestingResult', 'subject': 'PROBAND',
              'sourceValue': 'No parentage testing was performed for Case A.',
              'citation': citation('No parentage testing', raw)},
             {'field': 'reportedVariantTestingMethod', 'subject': 'FAMILY', 'sourceValue': 'Sanger sequencing',
              'citation': citation('Sanger sequencing confirmed', raw)}]},
        {'caseLabel': 'B', 'caseCitation': citation('Case B had no seizures.', raw),
         'caseOrigin': 'CITED_ONLY', 'originCitations': [citation('previously published case.', raw)],
         'fields': [{'field': 'reportedPhenotypeNegation', 'subject': 'PROBAND',
                     'sourceValue': 'no seizures', 'citation': citation('Case B had no seizures.', raw)}]}]}


class PromptTest(unittest.TestCase):
    def test_configured_sol_keeps_source_schema_and_existing_gemini_body(self):
        old=request(document(), 'gemini-3-flash')
        sol=request(document(), 'gpt-6.1-sol')
        expected={**old, 'model':'gpt-6.1-sol', 'max_completion_tokens':old['max_tokens'],
                  'reasoning_effort':'high'}
        expected.pop('max_tokens');expected.pop('temperature',None)
        self.assertEqual(expected,sol)
        for model in ('unknown-model', 'gpt-6.1-sol\n', None):
            with self.assertRaises(ValueError): request(document(),model)

    def test_request_retains_all_context_and_different_schema_from_v1(self):
        d = document(); v2 = request(d); v1 = model_request(d, 'gemini-3-flash', 8192)
        content = json.loads(v2['messages'][1]['content'])
        self.assertEqual(d['units'], content['units'])
        self.assertEqual(d['tables'], content['tables'])
        self.assertEqual(['A', 'B'], content['sourceCaseLabels'])
        self.assertNotEqual(digest(canonical(v1)), digest(canonical(v2)))
        self.assertIn('Sanger', SYSTEM)
        self.assertIn('Do not browse', SYSTEM)
        self.assertNotIn('tools', v2)

    def test_primary_and_cited_candidates_preserve_separate_methods_not_adoption(self):
        result = validate_candidates(RAW, PMCID, sha(RAW), candidates())
        self.assertFalse(result['businessPublishable'])
        self.assertEqual('NONE', result['clinicalAdoption'])
        self.assertEqual([], result['missingSourceCaseLabels'])
        a, b = result['cases']
        self.assertIn('CASE_ORIGIN_SEMANTICS_NOT_VERIFIED', a['issues'])
        self.assertIn('MODEL_PROPOSED_CITED_ONLY_NOT_PRIMARY_COHORT', b['issues'])
        self.assertIn('PRIMARY_COHORT_NOT_ESTABLISHED_BY_SOURCE_KIND', b['issues'])
        self.assertEqual('No parentage testing was performed for Case A.', a['fields'][0]['sourceValue'])
        self.assertEqual('Sanger sequencing', a['fields'][1]['sourceValue'])
        self.assertNotIn('reportedParentageTestingMethod', [f['field'] for f in a['fields']])
        self.assertIn('reportedParentageTestingMethod', a['notExtractedFields'])
        self.assertEqual('no seizures', b['fields'][0]['sourceValue'])

    def test_changed_source_and_made_up_quote_fail(self):
        with self.assertRaisesRegex(ValueError, 'SOURCE_SHA_MISMATCH'):
            validate_candidates(RAW + b' ', PMCID, sha(RAW), candidates())
        p = candidates(); p['cases'][0]['fields'][0]['citation']['quote'] = 'Parentage confirmed by STR.'
        with self.assertRaisesRegex(ValueError, 'CITATION_NOT_EXACT'):
            validate_candidates(RAW, PMCID, sha(RAW), p)

    def test_existing_quote_does_not_allow_invented_normalized_value(self):
        p = candidates(); p['cases'][0]['fields'][0]['sourceValue'] = 'BOTH_PARENTS_CONFIRMED'
        with self.assertRaisesRegex(ValueError, 'VALUE_NOT_IN_QUOTE'):
            validate_candidates(RAW, PMCID, sha(RAW), p)

    def test_other_case_field_cannot_be_reassigned(self):
        p = candidates(); p['cases'][0]['fields'].append(deepcopy(p['cases'][1]['fields'][0]))
        with self.assertRaisesRegex(ValueError, 'OTHER_CASE_CITATION'):
            validate_candidates(RAW, PMCID, sha(RAW), p)

    def test_other_case_table_cell_cannot_be_reassigned(self):
        p = candidates()
        p['cases'][1]['fields'].append({'field': 'geneSymbol', 'subject': 'PROBAND',
            'sourceValue': 'EXAMPLE', 'citation': citation('EXAMPLE')})
        with self.assertRaisesRegex(ValueError, 'OTHER_CASE_TABLE_CELL'):
            validate_candidates(RAW, PMCID, sha(RAW), p)

    def test_duplicate_aliases_do_not_inflate_cases(self):
        p = candidates(); repeat = deepcopy(p['cases'][0]); repeat['caseLabel'] = 'Case A'; p['cases'].append(repeat)
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_CASE_LABEL'):
            validate_candidates(RAW, PMCID, sha(RAW), p)

    def test_family_members_and_previous_report_are_preserved_without_count(self):
        p = candidates(); p['cases'][1]['caseOrigin'] = 'PREVIOUSLY_REPORTED_REEXAMINED'
        for row in p['cases']:
            row['fields'].append({'field': 'reportedRelatedCases', 'subject': 'FAMILY',
                'sourceValue': 'Case A and Case B', 'citation': citation('must not be merged.')})
        result = validate_candidates(RAW, PMCID, sha(RAW), p)
        self.assertEqual(2, len(result['cases']))
        self.assertIn('PRIOR_PUBLICATION_OVERLAP_REQUIRES_CHECK', result['cases'][1]['issues'])
        self.assertNotIn('points', result)
        self.assertNotIn('independentEvents', result)

    def test_no_cases_is_explicit_incomplete_extraction_not_negative_finding(self):
        result = validate_candidates(RAW, PMCID, sha(RAW), {'schemaVersion': SCHEMA_VERSION, 'cases': []})
        self.assertEqual(['A', 'B'], result['missingSourceCaseLabels'])
        self.assertIn('MODEL_NO_EXTRACTED_CASES_NOT_PROVEN_NO_CASES', result['issues'])

    def test_origin_needs_quote_but_unknown_can_remain_unknown(self):
        p = candidates(); p['cases'][0]['originCitations'] = []
        with self.assertRaisesRegex(ValueError, 'ORIGIN_CITATION_REQUIRED'):
            validate_candidates(RAW, PMCID, sha(RAW), p)
        p['cases'][0]['caseOrigin'] = 'UNCLEAR'
        self.assertFalse(validate_candidates(RAW, PMCID, sha(RAW), p)['businessPublishable'])

    def test_supplement_pointer_not_parsed_content_and_unprovided_unit_fails(self):
        coverage = supplement_coverage(document())
        self.assertEqual('REFERENCED_SUPPLEMENTS_NOT_INCLUDED', coverage['status'])
        self.assertFalse(coverage['externalSupplementContentProvided'])
        self.assertEqual('supp1.xlsx', coverage['referencedSupplements'][0]['href'])
        p = candidates(); p['cases'][0]['fields'][0]['citation']['unitId'] = 'supp1.xlsx/row2'
        with self.assertRaisesRegex(ValueError, 'CITATION_UNIT_MISSING'):
            validate_candidates(RAW, PMCID, sha(RAW), p)
        self.assertEqual('NO_EXPLICIT_SUPPLEMENT_LINK_DETECTED', supplement_coverage(document(ARTICLE))['status'])

    def test_model_decisions_and_v1_compatibility_cannot_be_smuggled(self):
        p = candidates(); p['cases'][0]['points'] = 2
        with self.assertRaisesRegex(ValueError, 'SCHEMA_INVALID'):
            validate_candidates(RAW, PMCID, sha(RAW), p)
        with self.assertRaisesRegex(ValueError, 'SCHEMA_INVALID'):
            check_shape(candidates())

    def test_oversized_inputs_are_not_silently_trimmed(self):
        d = document(); d['units'][0]['text'] += 'x' * MAX_REQUEST_BYTES
        with self.assertRaisesRegex(ValueError, 'INPUT_TOO_LARGE_NOT_TRUNCATED'):
            request(d)
        with patch('prepare_literature_prompt.source_labels', return_value={str(i) for i in range(41)}):
            with self.assertRaisesRegex(ValueError, 'CASE_LIMIT_NOT_TRUNCATED'):
                request(document())

    def test_offline_plan_and_candidate_review_do_not_touch_network_or_reuse_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root / 'paper.xml').write_bytes(RAW)
            manifest = root / 'input.json'
            manifest.write_text(json.dumps({'schemaVersion': 'denovo-public-model-input-v1', 'documents': [{
                'sourceClass': 'PUBLIC_PAPER', 'pmcid': PMCID, 'path': 'paper.xml', 'sha256': sha(RAW),
                'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/' + PMCID + '/fullTextXML'}]}), encoding='utf-8')
            with patch('socket.socket', side_effect=AssertionError('offline only')):
                result = plan(manifest, root / 'plan')
                self.assertEqual(0, result['networkCalls'])
                self.assertEqual(1, len(result['documents']))
                candidate_file = root / 'candidate.json'
                candidate_file.write_text(json.dumps(candidates()), encoding='utf-8')
                review_result = review(manifest, candidate_file, PMCID, root / 'review.json')
                self.assertEqual('CALLER_SUPPLIED_CANDIDATE_NOT_PROOF_OF_REAL_MODEL_EXECUTION', review_result['inputOrigin'])
                with self.assertRaisesRegex(ValueError, 'NEW_OUTPUT_DIRECTORY_REQUIRED'):
                    plan(manifest, root / 'plan')


if __name__ == '__main__':
    unittest.main()
