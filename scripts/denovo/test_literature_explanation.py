"""Public explanation regressions. Synthetic source, no network/model/scoring."""
import unittest
import test_core_evaluation_intake as fixtures
from test_bundle_qualification import article
from literature_explanation import PublicSources, build_explanation


class LiteratureExplanationTests(unittest.TestCase):
    setUp = fixtures.CoreEvaluationTests.setUp
    run_case = fixtures.CoreEvaluationTests.run_case

    def test_ready_explains_precheck_without_awarding_score(self):
        core, *_ = self.run_case()
        e = core['display']['explanation']
        self.assertEqual('literature-explanation-v1', e['schemaVersion'])
        self.assertEqual([], e['items'])
        self.assertIn('实际规则结果', e['summary'])
        self.assertFalse(any(k in e for k in ('score', 'appliedStrength')))
        for f in core['display']['fields']:
            for c in f['sourceEvidence']:
                self.assertEqual('ARTICLE_LANDING_PAGE', c['linkScope'])
                self.assertTrue(c['quote'])
                self.assertNotIn(str(self.root), str(c))

    def test_missing_family_grouped_and_parental_negative_not_clinical_health(self):
        raw = article().replace(b'<tr><td>Family history</td><td>None</td></tr>', b'')
        self.assertNotEqual(raw, article())
        core, *_ = self.run_case(raw)
        e = core['display']['explanation']
        family = [x for x in e['items'] if x['label'] == '家系与新发遗传相容']
        self.assertEqual(1, len(family), e)
        self.assertEqual(2, len(family[0]['fieldPaths']))
        self.assertIn('不能据此推定', family[0]['distinction'])
        self.assertIn('/groups/mother/targetResult', family[0]['observedFieldPaths'])
        self.assertTrue(family[0]['checkedScope'])
        self.assertIn('不等于全部附件', family[0]['scopeStatement'])

    def test_context_missing_explains_knowledge_not_missing_paper(self):
        core, *_ = self.run_case(config_change=lambda c: c.update(contexts=[]))
        entries = core['display']['explanation']['items']
        self.assertTrue(any(x['category'] == 'KNOWLEDGE_MISSING' and '不是已证明论文遗漏' in x['explanation'] for x in entries))

    def test_category_distinctions_and_no_collapse_of_unrelated_causes(self):
        core, *_ = self.run_case()
        view = core['display']; fields = view['fields']
        doc = {'caseKey': view['caseKey'], 'groups': {}, 'context': {}, 'extensions': {},
               'conditions': {'phaseRequired': {}, 'specialFamilyModel': {}}}
        class Sources:
            pmcid = 'PMC123'; title = 'Test'; url = 'https://pmc.ncbi.nlm.nih.gov/articles/PMC123/'
        categories = ['EXTRACTION_OR_REVIEW_INCOMPLETE', 'SOURCE_UNAVAILABLE', 'CONFLICT', 'SOFTWARE_UNSUPPORTED', 'NOT_APPLICABLE', 'EXECUTION_FAILURE']
        blockers = [{'path': '/context/diseaseId', 'category': c, 'code': c, 'meaning': 'QUALIFICATION_REQUIRED'} for c in categories]
        e = build_explanation(doc, fields, blockers, {}, Sources())
        self.assertEqual(6, len(e['items']))
        self.assertIn('不能据此断言论文没有记载', e['items'][0]['explanation'])
        self.assertIn('尚未取得', e['items'][1]['explanation'])
        self.assertIn('不能选择有利陈述', e['items'][2]['explanation'])
        self.assertIn('程序范围不足', e['items'][3]['explanation'])

    def test_source_resolution_refuses_wrong_quote_or_sha(self):
        src = PublicSources.__new__(PublicSources)
        src.pmcid = 'PMC123'; src.title = 'Test'; src.url = 'https://pmc.ncbi.nlm.nih.gov/articles/PMC123/'
        src.units = {'/p[1]': {'unitId': '/p[1]', 'sourceSha256': 'a' * 64, 'text': 'Both parents tested negative.', 'sections': ['Results']}}
        good = {'unitId': '/p[1]', 'sourceSha256': 'a' * 64, 'quote': 'Both parents tested negative.'}
        self.assertEqual('Results · 所在章节第1段', src.native(good)['locationLabel'])
        for key, value in [('sourceSha256', 'b' * 64), ('quote', 'Both parents unaffected.')]:
            with self.assertRaisesRegex(ValueError, 'CITATION_MISMATCH'):
                src.native({**good, key: value})


if __name__ == '__main__':
    unittest.main()
