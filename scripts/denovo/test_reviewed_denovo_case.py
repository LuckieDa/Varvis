from unittest import TestCase
from unittest.mock import patch
from copy import deepcopy

from model_literature_contract import canonical, digest
from reviewed_denovo_case import PmcArticle, replay


PMCID = 'PMC999999999'
IMAGE_URL = 'https://cdn.ncbi.nlm.nih.gov/pmc/blobs/1234/999999999/example/figure.jpg'
IMAGE = b'\xff\xd8\xffsynthetic image placeholder'
ARTICLE = f'''<meta name="citation_title" content="Synthetic test article">
<meta name="citation_fulltext_html_url" content="https://pmc.ncbi.nlm.nih.gov/articles/{PMCID}/">
<article><section id="case">Child A, chr1:g.100G>A, c.100G>A. Reviewed findings.</section>
<figure id="F1"><img src="{IMAGE_URL}"><figcaption>Test figure legend.</figcaption></figure></article>'''.encode()
FOLLOW_UP_PMC = 'PMC999999998'
PRIMARY_DOI = '10.0000/primary'
ALIAS_ARTICLE = ARTICLE.replace(b'<article>', f'''<meta name="citation_pmid" content="99999999">
<meta name="citation_doi" content="{PRIMARY_DOI}"><article>'''.encode()).replace(
    b'c.100G>A. Reviewed', b'c.100G>A and p.Val34Ile. Reviewed').replace(b'</article>', b'''
<section id="T1"><table>
<tr><th>Case</th><th>Child A</th><th>Child B</th></tr>
<tr><td>Coding</td><td>c.100G&gt;A</td><td>c.101G&gt;A</td></tr>
<tr><td>Protein</td><td>p.Val34Ile</td><td>p.Val34Ala</td></tr>
<tr><td>Birth weight</td><td>2,160 g</td><td>2,900 g</td></tr>
<tr><td>Platelets</td><td>5,000/ul</td><td>8,000/ul</td></tr></table></section></article>''')
FOLLOW_UP = f'''<meta name="citation_title" content="Synthetic follow-up">
<meta name="citation_fulltext_html_url" content="https://pmc.ncbi.nlm.nih.gov/articles/{FOLLOW_UP_PMC}/">
<article><section id="cases"><p id="follow1">Pt 1 was followed after birth. <a href="#REF1">1</a></p>
<p id="follow2">Pt 2 had a different history. <a href="#REF1">1</a></p></section>
<section id="T1"><table><tr><th>Pt No</th><th>1</th><th>2</th></tr>
<tr><td>Coding</td><td>c.100G &gt; A</td><td>c.100G &gt; A</td></tr>
<tr><td>Protein</td><td>p.Val34Ile</td><td>p.Val34Ile</td></tr>
<tr><td>Birth weight (g)</td><td>2160</td><td>2900</td></tr>
<tr><td>Platelets (/ul)</td><td>5000</td><td>8000</td></tr></table></section>
<li id="REF1"><a href="https://doi.org/{PRIMARY_DOI}">Original paper</a></li></article>'''.encode()


class ReviewedDeNovoCaseTest(TestCase):
    def setUp(self):
        self.source = {'pmcid': PMCID, 'sha256': digest(ARTICLE), 'reviewedMediaSha256s': [digest(IMAGE)]}
        self.identity = {'allele': {'assembly': 'GRCh38', 'contig': '1', 'position': 100,
                                   'reference': 'G', 'alternate': 'A'}, 'hgncId': 'HGNC:123',
                         'transcript': 'NM_123456.1', 'coding': 'c.100G>A', 'proteinChange': 'V34I',
                         'gencodeRecordSha256': 'a' * 64}
        evidence = {'locator': 'case', 'quote': 'Reviewed findings.'}
        self.case = {'label': 'Child A', 'geneSymbol': 'EXAMPLE', 'diseaseId': 'MONDO:0100000',
                     'transcript': 'NM_123456.1', 'coding': 'c.100G>A', 'reportedCoding': 'c.100G>A',
                     'reportedGenomic': 'chr1:g.100G>A',
                     'members': {'proband': {'id': 'Child A', 'target': 'PRESENT', 'method': 'SANGER'},
                                 'mother': {'id': 'I/2', 'target': 'PRESENT', 'method': 'SANGER'},
                                 'father': {'id': 'I/1', 'target': 'ABSENT', 'method': 'SANGER'}},
                     'parentage': {'maternity': 'ASSUMED_NOT_REPORTED', 'paternity': 'ASSUMED_NOT_REPORTED'},
                     'phenotype': ['Synthetic feature'], 'family': 'MATERNALLY_INHERITED',
                     'refs': {key: [evidence] for key in ('proband', 'father', 'maternity', 'paternity', 'phenotype', 'family')}}
        self.case['refs']['variant'] = [{'locator': 'case', 'quote': 'Child A, chr1:g.100G>A, c.100G>A.'}]
        self.case['refs']['mother'] = [{'locator': 'F1', 'quote': 'Test figure legend.', 'imageSha256': digest(IMAGE),
                                       'imageUrl': IMAGE_URL, 'panel': 'I/2'}]

    def replay_case(self, article=ARTICLE, data_class=None):
        source = {**self.source, 'sha256': digest(article)}
        review = canonical({'schemaVersion': 'denovo-reviewed-paper-v1', 'pmcid': PMCID,
                            'sourceSha256': source['sha256'], 'format': 'PMC_HTML', 'reviewedBy': 'test',
                            'recordSha256': 'b' * 64, 'cases': [self.case],
                            **({'dataClass': data_class} if data_class else {})})
        with patch('reviewed_denovo_case.load_config', return_value=({'identity': {}}, 'pin')), \
                patch('reviewed_denovo_case.LocalIdentity') as resolver:
            resolver.return_value.resolve_coding.return_value = self.identity
            result = replay(article, source, digest(review), {digest(review): review, digest(IMAGE): IMAGE},
                            {'root': '.', 'path': 'test', 'sha256': 'c' * 64})
            return result['cases'][0]['reviewedCase']

    def test_exact_trio_keeps_positive_parent_and_pinned_figure(self):
        result = self.replay_case()
        self.assertEqual('READY_FOR_EVALUATION', result['display']['status'])
        self.assertEqual('PRESENT', result['members']['mother']['target'])
        self.assertEqual('ASSUMED_NOT_REPORTED', result['parentage']['paternity'])
        mother = next(row for row in result['display']['fields'] if row['path'] == '/groups/mother/targetResult')
        self.assertEqual(digest(IMAGE), mother['sourceEvidence'][0]['sourceSha256'])

    def test_missing_and_conflicting_tests_remain_separate(self):
        self.case['family'] = 'UNKNOWN'
        self.case['members']['mother'].update(target='NOT_TESTED', method='UNKNOWN')
        self.case['members']['father']['target'] = 'CONFLICT'
        display = self.replay_case()['display']
        self.assertEqual('INSUFFICIENT_DATA', display['status'])
        self.assertEqual(['DENOVO_MOTHER_TARGET_NOT_TESTED', 'DENOVO_FATHER_TARGET_CONFLICT'],
                         [row['code'] for row in display['blockers']])

    def test_reported_result_without_qualified_assay_is_retained_as_a_gap(self):
        self.case['members']['father']['method'] = 'UNKNOWN'
        result = self.replay_case()
        self.assertEqual('ABSENT', result['members']['father']['target'])
        self.assertEqual('DENOVO_FATHER_TARGET_METHOD_REQUIRED', result['display']['blockers'][0]['code'])

    def test_quantitative_conditions_replay_only_the_named_case_and_literal_value(self):
        article = ARTICLE.replace(b'Reviewed findings.</section>', b'''Reviewed findings. QTc was 501.5 ms.
            Mother had no QT prolongation.</section>
            <section id="cohort">QTc was 501.5 ms in the cohort.</section>''')
        self.identity['maneMapping'] = {'Ensembl_nuc': 'ENST00000012345.1'}
        measurement = {'value': 501.5, 'ref': {'locator': 'case', 'quote': 'QTc was 501.5 ms.'}}
        self.case['publishedConditions'] = {'qtcMs': measurement, 'findings': [
            {'role': 'MOTHER', 'finding': 'QT_PROLONGATION', 'status': 'ABSENT',
             'ref': {'locator': 'case', 'quote': 'Mother had no QT prolongation.'}}]}
        result = self.replay_case(article, 'SYNTHETIC')
        self.assertEqual('SYNTHETIC', result['display']['dataClass'])
        self.assertEqual(501.5, result['publishedConditions']['qtcMs'])
        fields = {field['path']: field for field in result['display']['fields']}
        self.assertEqual('ABSENT', fields['/groups/family/MOTHER/QT_PROLONGATION']['value'])
        self.assertEqual(digest(article), fields['/groups/phenotype/qtcMs']['sourceEvidence'][0]['sourceSha256'])
        measurement['value'] = 502
        with self.assertRaisesRegex(ValueError, 'QTC_VALUE_UNBOUND'):
            self.replay_case(article)
        measurement.update(value=501.5, ref={'locator': 'cohort', 'quote': 'QTc was 501.5 ms in the cohort.'})
        with self.assertRaisesRegex(ValueError, 'PUBLISHED_CONDITION_CASE_UNBOUND'):
            self.replay_case(article)

    def test_reviewed_figure_can_supply_case_findings_via_a_named_case_reference(self):
        article = ARTICLE.replace(b'</section>', b'''</section>
            <section id="figure-cases"><p id="case-a">Child A is shown in <a href="#F1">Figure 1</a>.</p>
            <p id="case-b">Child B is shown in <a href="#F1">Figure 1</a>.</p>
            <p id="unlinked">Child A was followed separately.</p></section>''', 1)
        self.identity['maneMapping'] = {'Ensembl_nuc': 'ENST00000012345.1'}
        ref = {**self.case['refs']['mother'][0], 'caseLocator': 'case-a'}
        self.case['publishedConditions'] = {'findings': [
            {'role': 'MOTHER', 'finding': 'QT_PROLONGATION', 'status': 'ABSENT', 'ref': ref}]}
        result = self.replay_case(article)
        fields = {field['path']: field for field in result['display']['fields']}
        evidence = fields['/groups/family/MOTHER/QT_PROLONGATION']['sourceEvidence'][0]
        self.assertEqual(digest(IMAGE), evidence['sourceSha256'])
        self.assertEqual('F1 · I/2', evidence['locator'])
        for anchor in ('case-b', 'unlinked'):
            ref['caseLocator'] = anchor
            with self.subTest(anchor=anchor), self.assertRaisesRegex(ValueError, 'FIGURE_CASE_UNBOUND'):
                self.replay_case(article)
        ref.pop('caseLocator')
        with self.assertRaisesRegex(ValueError, 'PUBLISHED_CONDITION_CASE_UNBOUND'):
            self.replay_case(article)

    def test_reviewed_phenotype_negatives_are_separate_from_unreported_findings(self):
        self.case['phenotype'] = []
        self.case['phenotypeNegatives'] = ['Synthetic feature']
        self.case['refs']['phenotypeNegatives'] = [{'locator': 'case', 'quote': 'Reviewed findings.'}]
        result = self.replay_case()
        fields = {field['path']: field for field in result['display']['fields']}
        self.assertEqual('NOT_REPORTED_IN_CHECKED_SCOPE', fields['/groups/phenotype/positive']['availability'])
        self.assertEqual(['Synthetic feature'], result['phenotypeNegatives'])
        negative = fields['/groups/phenotype/negative']
        self.assertEqual('REPORTED', negative['availability'])
        self.assertEqual('case', negative['sourceEvidence'][0]['locator'])
        self.case['refs'].pop('phenotypeNegatives')
        with self.assertRaisesRegex(ValueError, 'CASE_CITATIONS'):
            self.replay_case()

    def test_changed_locator_or_locus_refuses_the_observation(self):
        self.source['reviewedMediaSha256s'] = []
        with self.assertRaisesRegex(ValueError, 'FIGURE_SOURCE_CHANGED'):
            self.replay_case()
        self.source['reviewedMediaSha256s'] = [digest(IMAGE)]
        self.identity['allele']['position'] = 101
        with self.assertRaisesRegex(ValueError, 'REPORTED_LOCUS_MISMATCH'):
            self.replay_case()

    def test_independence_keeps_local_proof_and_rejects_self_relation(self):
        pair = {'pmcid': FOLLOW_UP_PMC, 'caseLabel': 'Other family', 'sourceSha256': digest(FOLLOW_UP),
                'refs': [{'locator': 'case', 'quote': 'Child A'}]}
        self.case['independentOf'] = [pair]
        saved = self.replay_case()
        self.assertEqual(PMCID, saved['independentOf'][0]['citations'][0]['paperId'])
        pair.update(pmcid=PMCID, caseLabel='Child A', sourceSha256=digest(ARTICLE))
        with self.assertRaisesRegex(ValueError, 'INDEPENDENT_CASE_IDENTITY'):
            self.replay_case()

    def test_exact_table_row_binding_rejects_missing_or_duplicate_label(self):
        article = PmcArticle(ALIAS_ARTICLE, PMCID)
        self.assertEqual('Coding | c.100G>A | c.101G>A', article.case_row('T1', 'Coding'))
        self.assertEqual('p.Val34Ala', article.case_column('T1', 'Child B')['Protein'])
        article.tables['T1'].append(article.tables['T1'][1])
        with self.assertRaisesRegex(ValueError, 'CASE_TABLE_ROW_UNBOUND'):
            article.case_row('T1', 'Coding')

    def test_table_observation_cannot_borrow_another_case_column(self):
        case = self.with_follow_up()
        case['refs']['father'] = [{'locator': 'T1', 'columnLabel': 'Child B',
                                   'rowLabel': 'Coding', 'quote': 'c.101G>A'}]
        with self.assertRaisesRegex(ValueError, 'CASE_TABLE_CELL_UNBOUND'):
            self.replay_alias(case)

    def replay_alias(self, case, include_follow_up=True):
        primary = digest(ALIAS_ARTICLE)
        review = canonical({'schemaVersion': 'denovo-reviewed-paper-v1', 'pmcid': PMCID,
                            'sourceSha256': primary, 'format': 'PMC_HTML', 'reviewedBy': 'test',
                            'recordSha256': 'b' * 64, 'cases': [case]})
        document = {**self.source, 'sha256': primary,
                    'reviewedFollowUpSha256s': [digest(FOLLOW_UP)] if include_follow_up else []}
        bridge = {key: self.identity[key] for key in ('allele', 'hgncId')}
        originals = {digest(raw): raw for raw in (review, IMAGE, FOLLOW_UP)}
        with patch('reviewed_denovo_case.load_config', return_value=({'identity': {}}, 'pin')), \
                patch('reviewed_denovo_case.LocalIdentity') as resolver, \
                patch('reviewed_denovo_case.read_record') as clinvar, \
                patch('reviewed_denovo_case.resolve', return_value=bridge) as alias:
            resolver.return_value.resolve_coding.return_value = self.identity.copy()
            result = replay(ALIAS_ARTICLE, document, digest(review), originals, {'root': '.', 'path': 'test', 'sha256': 'c' * 64},
                            [(b'allele XML', 'd' * 64, 'VCV000000001')])
            clinvar.assert_called_once_with(b'allele XML', 'd' * 64, 'VCV000000001')
            self.assertEqual('99999999', alias.call_args.args[1])
            return result['cases']

    def with_follow_up(self):
        case = deepcopy(self.case)
        case.pop('reportedGenomic')
        case.update(reportedProtein='p.Val34Ile', variantTable='T1')
        case['refs']['variant'] = [{'locator': 'case', 'quote': 'c.100G>A and p.Val34Ile.'}]
        case['followUps'] = [{
            'pmcid': FOLLOW_UP_PMC, 'sourceSha256': digest(FOLLOW_UP), 'label': 'Pt 1', 'table': 'T1',
            'primaryReference': 'REF1', 'caseReference': {'locator': 'follow1', 'quote': 'Pt 1 was followed after birth.'},
            'matches': [{'primaryRow': 'Birth weight', 'followUpRow': 'Birth weight (g)', 'number': '2160'},
                        {'primaryRow': 'Platelets', 'followUpRow': 'Platelets (/ul)', 'number': '5000'}],
        }]
        return case

    def test_linked_follow_up_adds_citations_to_one_primary_case(self):
        case = self.with_follow_up()
        alone = deepcopy(case)
        alone.pop('followUps')
        before, = self.replay_alias(alone)
        after, = self.replay_alias(case)
        self.assertEqual(before['alleleIdentity'], after['alleleIdentity'])
        self.assertEqual(before['reviewedCase']['members'], after['reviewedCase']['members'])
        result = after['reviewedCase']
        self.assertEqual('Pt 1', result['followUpReports'][0]['caseLabel'])
        grouping = next(f for f in result['display']['fields'] if f['path'] == '/groups/overlap/eventGrouping')
        self.assertEqual('VERIFIED_FOLLOW_UP_SAME_EVENT', grouping['value'])
        self.assertEqual({PMCID, FOLLOW_UP_PMC}, {r['paperId'] for r in grouping['sourceEvidence']})

    def test_same_allele_in_a_different_column_does_not_prove_same_case(self):
        case = self.with_follow_up()
        case['followUps'][0].update(label='Pt 2', caseReference={'locator': 'follow2', 'quote': 'Pt 2 had a different history.'})
        with self.assertRaisesRegex(ValueError, 'FOLLOW_UP_CASE_DETAILS_MISMATCH'):
            self.replay_alias(case)

    def test_follow_up_requires_a_bound_paper_and_a_direct_citation(self):
        case = self.with_follow_up()
        with self.assertRaisesRegex(ValueError, 'FOLLOW_UP_SOURCE_UNBOUND'):
            self.replay_alias(case, include_follow_up=False)
        case['followUps'][0]['primaryReference'] = 'OTHER'
        with self.assertRaisesRegex(ValueError, 'FOLLOW_UP_CASE_REFERENCE_UNBOUND'):
            self.replay_alias(case)
