import unittest
from single_case_scope import units, owns, primary


class SingleCaseTests(unittest.TestCase):
    def test_only_substitution_operator_spacing_is_normalized(self):
        from core_local_identity import identifiers
        def parsed(value):
            return identifiers({'groups': {'gene': {'symbol': {'value': 'EXAMPLE', 'verification': 'VERIFIED', 'availability': 'REPORTED'}},
                'variant': {'representations': {'value': [value, 'p.Arg762Lys', 'NM_123.1'], 'verification': 'VERIFIED', 'availability': 'REPORTED'}}}})
        for value in ('c.2285G > A', 'c.2285G\u2009>\u2009A', 'c.2285G>A'):
            self.assertEqual('c.2285G>A', parsed(value)[1])
        for value in ('c.22 85G>A', 'c.2285G>A or C', 'c.2285 G>A', 'c.2285G>A; c.1A>G'):
            with self.assertRaises(ValueError): parsed(value)

    def document(self):
        return {'units': [{'unitId': f'/article[1]/body[1]/sec[1]/p[{i}]', 'kind': 'BODY_PARAGRAPH',
                          'sections': ['Case report'], 'text': t} for i, t in enumerate([
            'The reported patient was a 2-year-old boy. He was referred to our hospital.',
            'We performed whole-exome sequencing on the patient and parents.',
            'The patient had ataxia.'], 1)]}

    def test_unique_clinical_section_is_owned_and_primary(self):
        d = self.document()
        self.assertEqual(3, len(units(d, 'proband')))
        self.assertTrue(primary(d, d['units'][1], 'proband'))
        self.assertFalse(primary(d, d['units'][2], 'proband'))
        self.assertFalse(owns(d, d['units'][0], 'invented-id'))

    def test_ambiguous_cases_and_sections_fail_closed(self):
        for suffix in (' Another patient was examined.', ' A second case was recruited.',
                       ' Case B had similar symptoms.', ' Two patients were sequenced.'):
            d = self.document(); d['units'][1]['text'] += suffix
            self.assertEqual([], units(d, 'proband'), suffix)
        d = self.document(); d['units'][0]['text'] = 'A study reported several boys.'
        self.assertEqual([], units(d, 'proband'))
        d = self.document(); d['units'][1]['unitId'] = '/article[1]/body[1]/sec[2]/p[1]'
        self.assertEqual([], units(d, 'proband'))

    def test_other_material_is_never_owned(self):
        for kind, title in [('CAPTION', 'Case report'), ('BODY_PARAGRAPH', 'Discussion'),
                            ('BODY_PARAGRAPH', 'Methods')]:
            d = self.document(); extra = dict(d['units'][1], unitId='/different', kind=kind, sections=[title])
            d['units'].append(extra)
            self.assertFalse(owns(d, extra, 'proband'))

    def test_prior_variant_is_not_prior_case_and_negative_assay_is_not_primary(self):
        from literature_case_structure import prior_case
        self.assertFalse(prior_case('The variation was not reported previously.'))
        self.assertFalse(prior_case('The variant was previously reported.'))
        self.assertTrue(prior_case('The patient was previously reported.'))
        d = self.document(); d['units'][1]['text'] += ' The variation was not reported previously.'
        self.assertTrue(primary(d, d['units'][1], 'proband'))
        d['units'][1]['text'] = 'We performed whole-exome sequencing but not on this patient.'
        self.assertFalse(primary(d, d['units'][1], 'proband'))

    def test_single_case_anchor_still_requires_exact_quote_and_case_scope(self):
        import re
        from test_bundle_qualification import article
        from model_literature_contract import digest
        from prepare_literature_prompt import validate_candidates, SCHEMA
        # Exercise the real quote-check entry, with no disease/gene-specific code.
        from test_bundle_qualification import registered
        import tempfile
        from pathlib import Path
        raw = re.sub(rb'<body>.*</body>', b'<body><sec><title>Case report</title>'
            b'<p>The reported patient was a 2-year-old boy. He was referred to our hospital.</p>'
            b'<p>We performed whole-exome sequencing. EXAMPLE c.123A&gt;G p.T41A was identified in the proband.</p>'
            b'</sec></body>', article(), flags=re.S)
        with tempfile.TemporaryDirectory() as d:
            _, source = registered(Path(d), raw)
            u = next(u for u in source['main']['units'] if 'was identified' in u['text'])
            ref = {'unitId': u['unitId'], 'quote': u['text']}
            value = {'schemaVersion': SCHEMA['properties']['schemaVersion']['enum'][0],
                'cases': [{'caseLabel': 'proband', 'caseOrigin': 'AUTHOR_COHORT',
                           'caseCitation': ref, 'originCitations': [ref], 'fields': []}]}
            validate_candidates(raw, source['pmcid'], digest(raw), value)
            value['cases'][0]['caseLabel'] = 'another'
            with self.assertRaisesRegex(ValueError, 'CASE_LABEL_NOT_ANCHORED'):
                validate_candidates(raw, source['pmcid'], digest(raw), value)
            value['cases'][0]['caseLabel'] = 'proband'
            ref['quote'] = 'An invented sentence absent from the source.'
            with self.assertRaises(ValueError):
                validate_candidates(raw, source['pmcid'], digest(raw), value)


if __name__ == '__main__': unittest.main()
