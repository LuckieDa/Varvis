"""Synthetic source-binding and evidence-preservation tests, no clinical approval."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import prepare_expert_reference_evidence as subject
from test_prepare_reference_evidence import XML, ALLELE

UUID = '00000000-0000-0000-0000-000000000001'
CV = (XML.replace(b'criteria provided, single submitter', b'reviewed by expert panel')
    .replace(b'<ClinVarAccession Accession="SCV000001"',
             ('<ClinVarSubmissionID localKey="' + UUID + '"/><ClinVarAccession Accession="SCV000001"').encode())
    .replace(b'Assembly="GRCh38" Chr="1"', b'Assembly="GRCh38" Accession="NC_000001.11" Chr="1"')
    .replace(b'<XRef DB="MedGen" ID="C123"/>', b'<XRef DB="MONDO" ID="0000001"/>'))


def assessment(code, outcome):
    return dict(type='CriterionAssessment', criterion=dict(label=code,
        defaultStrength=dict(label='Pathogenic Supporting')),
        statementOutcome=dict(label=outcome), evidenceLine=[dict(evidenceItem=[dict(
            contribution=[dict(comments='Synthetic study PMID:123; exact original observation.')])])])


class ExpertEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.ref = dict(referenceId='ref-1', variationId='123', scv='SCV000001.2', allele=ALLELE,
            erepoUuid=UUID, erepoVersion='1.0.0', clinvarSource='cv', sepioSource='ep', summarySource='summary')
        self.ep = dict(data=dict(uuid=UUID, metadata=dict(version='1.0.0'),
            type='VariantPathogenicityInterpretation', condition=dict(id='MONDO:0000001'),
            variant=dict(id='CAR:CA1'), statementOutcome=dict(label='Pathogenic'),
            assertionMethod=dict(version='2.0'), evidenceLine=[assessment('PS3', 'Met'), assessment('PS1', 'Not Met')]))
        self.summary = dict(data=[dict(uuid=UUID, docVersion='1.0.0', cvId='123', gene='TEST',
            caId='CA1', mondoId='MONDO:0000001', classification='Pathogenic', retracted=False,
            hgvs=['NC_000001.11:g.101G>A'], metCodes=['PS3_Supporting'], unMetCodes=['PS1'],
            summaryDesc='ACMG/AMP criteria applied (specification version 2.0): PS3_Supporting.')])
        self.xml = CV

    def sources(self):
        def record(kind, url, value, version):
            raw = value if isinstance(value, bytes) else json.dumps(value).encode()
            return dict(kind=kind, url=url, rawSha256=subject.base.sha(raw), version=version), raw
        return {
            'cv': record('CLINVAR_VCV', 'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&id=VCV000000123&rettype=vcv', self.xml, 'VCV000000123.4'),
            'ep': record('EREPO_SEPIO', 'https://erepo.genome.network/evrepo/api/summary/classification/' + UUID + '/doc/sepio/version/1.0.0', self.ep, '1.0.0'),
            'summary': record('EREPO_SUMMARY', 'https://erepo.genome.network/evrepo/api/summary/classifications?cvId=123', self.summary, 'snapshot-2026-10-13')}

    def extract(self):
        return subject.extract(self.ref, self.sources())

    def test_exact_source_preservation_without_approval(self):
        row = self.extract()
        self.assertEqual('EXACT_SOURCE_BOUND', row['bindingStatus'])
        self.assertEqual('UNREVIEWED', row['reviewStatus'])
        self.assertFalse(row['scientificAdoption'])
        self.assertEqual('NOT_EVALUATED', row['independence'])
        self.assertEqual(self.ep['data']['evidenceLine'], [r['original'] for r in row['criterionAssessments']])
        self.assertEqual(self.summary['data'][0], row['originalExpertSummary'])
        self.assertEqual([], row['appliedComparisonCriteria'])
        self.assertEqual('PS1', row['summaryUnmetCriteria'][0]['code'])

    def test_source_pointers_resolve_from_whole_original_document(self):
        row = self.extract()
        def resolve(doc, pointer):
            for key in pointer.split('/')[1:]:
                key = key.replace('~1', '/').replace('~0', '~')
                doc = doc[int(key)] if isinstance(doc, list) else doc[key]
            return doc
        for criterion in row['criterionAssessments']:
            self.assertEqual(criterion['original'], resolve(self.ep, criterion['pointer']))
            for comment in criterion['comments']:
                self.assertEqual(comment['value'], resolve(self.ep, comment['pointer']))
        for clue in row['reviewClues']:
            if 'pointer' in clue:
                doc = self.ep if clue['sourceId'] == 'ep' else self.summary
                self.assertEqual(clue['value'], resolve(doc, clue['pointer']))

    def test_not_met_is_not_circular_even_when_mentioned(self):
        row = self.extract()
        self.assertNotIn('COMPARISON_CRITERION_MET_REQUIRES_INDEPENDENT_REASSESSMENT', row['gapReasons'])
        self.assertTrue(row['reviewClues'])  # Text is retained for human target-specific review.

    def test_met_comparison_is_flagged_not_silently_removed(self):
        self.ep['data']['evidenceLine'][1]['statementOutcome']['label'] = 'Met'
        self.summary['data'][0]['metCodes'].append('PS1')
        self.summary['data'][0]['unMetCodes'] = []
        row = self.extract()
        self.assertEqual(['PS1'], row['appliedComparisonCriteria'])
        self.assertIn('COMPARISON_CRITERION_MET_REQUIRES_INDEPENDENT_REASSESSMENT', row['gapReasons'])
        self.assertIn('PS1', row['originalExpertSummary']['metCodes'])

    def test_missing_sepio_labels_never_inferred_from_ids(self):
        del self.ep['data']['evidenceLine'][0]['criterion']
        self.ep['data']['evidenceLine'][0]['id'] = '/0052'
        row = self.extract()
        self.assertIsNone(row['criterionAssessments'][0]['code'])
        self.assertIn('SEPIO_CRITERION_LABEL_MISSING', row['gapReasons'])

    def test_malformed_strength_retained_not_guessed(self):
        self.summary['data'][0]['metCodes'] = ['PM6_Very']
        row = self.extract()
        self.assertIsNone(row['summaryMetCriteria'][0]['code'])
        self.assertEqual('PM6_Very', row['summaryMetCriteria'][0]['original'])
        self.assertIn('SUMMARY_CRITERION_TOKEN_UNSUPPORTED', row['gapReasons'])

    def test_criteria_disagreement_visible(self):
        self.summary['data'][0]['metCodes'] = ['PM2']
        self.assertIn('SUMMARY_SEPIO_CRITERIA_DISAGREE', self.extract()['gapReasons'])

    def test_versions_not_relabelled(self):
        self.ep['data']['assertionMethod']['version'] = '3.0'
        row = self.extract()
        self.assertEqual('3.0', row['specification']['originalAssertionMethod']['version'])
        self.assertEqual(['2.0'], row['specification']['narrativeVersionMentions'])
        self.assertIn('SPECIFICATION_VERSION_DISAGREEMENT', row['gapReasons'])

    def test_missing_structured_spec_preserves_narrative(self):
        del self.ep['data']['assertionMethod']['version']
        row = self.extract()
        self.assertIn('STRUCTURED_SPECIFICATION_VERSION_MISSING', row['gapReasons'])
        self.assertEqual(['2.0'], row['specification']['narrativeVersionMentions'])

    def test_narrative_difference_does_not_imply_same_revision(self):
        self.assertIn('SCV_EREPO_NARRATIVE_EQUIVALENCE_NOT_ESTABLISHED', self.extract()['gapReasons'])

    def test_unknown_sepio_outcome_kept_and_flagged(self):
        self.ep['data']['evidenceLine'][0]['statementOutcome']['label'] = 'Uncertain'
        row = self.extract()
        self.assertIn('SEPIO_CRITERION_OUTCOME_UNSUPPORTED', row['gapReasons'])
        self.assertEqual('Uncertain', row['criterionAssessments'][0]['outcome'])

    def test_new_scv_not_substituted(self):
        self.ref['scv'] = 'SCV000001.3'
        with self.assertRaisesRegex(ValueError, 'EXACT_SCV_VERSION_NOT_PRESENT'):
            self.extract()

    def test_wrong_submission_uuid_rejected(self):
        self.ref['erepoUuid'] = '11111111-1111-1111-1111-111111111111'
        with self.assertRaisesRegex(ValueError, 'EXACT_SCV_EREPO_LINK_REQUIRED'):
            self.extract()

    def test_version_mismatch_rejected(self):
        self.ep['data']['metadata']['version'] = '2.0.0'
        with self.assertRaisesRegex(ValueError, 'EREPO_IDENTITY_MISMATCH'):
            self.extract()

    def test_descriptor_version_checked(self):
        sources = self.sources()
        sources['cv'][0]['version'] = 'VCV000000123.5'
        with self.assertRaisesRegex(ValueError, 'VCV_DESCRIPTOR_VERSION_MISMATCH'):
            subject.extract(self.ref, sources)

    def test_wrong_disease_rejected_no_gene_level_fallback(self):
        self.ep['data']['condition']['id'] = 'MONDO:0000002'
        with self.assertRaisesRegex(ValueError, 'EXACT_SCV_DISEASE_BINDING_MISSING'):
            self.extract()

    def test_non_mondo_condition_retains_evidence_but_is_not_fully_bound(self):
        self.xml = self.xml.replace(b'<XRef DB="MONDO" ID="0000001"/>',
                                    b'<XRef DB="Orphanet" ID="ORPHA123"/>')
        row = self.extract()
        self.assertEqual('EXACT_RECORD_BOUND_CONDITION_UNRESOLVED', row['bindingStatus'])
        self.assertIn('EXACT_SCV_DISEASE_MAPPING_UNRESOLVED', row['gapReasons'])
        self.assertFalse(row['scientificAdoption'])
        self.assertFalse(row['normalRnaClaimed'])
        self.assertEqual(self.ep['data']['evidenceLine'], [r['original'] for r in row['criterionAssessments']])
        self.assertEqual('Orphanet', next(n for n in subject.xml_nodes(row['originalAssertion']['condition'])
                         if n['tag'] == 'XRef')['attributes']['DB'])

    def test_composite_submission_key_requires_exact_single_condition(self):
        self.xml = self.xml.replace(b'<XRef DB="MONDO" ID="0000001"/>',
                                    b'<XRef DB="Orphanet" ID="ORPHA123"/>')
        self.xml = self.xml.replace(('localKey="'+UUID+'"').encode(),
                                   ('localKey="'+UUID+'|Orphanet:ORPHA123"').encode())
        self.assertEqual('EXACT_RECORD_BOUND_CONDITION_UNRESOLVED', self.extract()['bindingStatus'])
        self.xml = self.xml.replace(b'|Orphanet:ORPHA123', b'|Orphanet:ORPHA124')
        with self.assertRaisesRegex(ValueError, 'EXACT_SCV_EREPO_LINK_REQUIRED'):
            self.extract()

    def test_composite_key_does_not_accept_conflicting_conditions(self):
        self.xml = self.xml.replace(('localKey="'+UUID+'"').encode(),
                                   ('localKey="'+UUID+'|MONDO:0000001"').encode())
        self.xml = self.xml.replace(b'<XRef DB="MONDO" ID="0000001"/>',
            b'<XRef DB="MONDO" ID="0000001"/><XRef DB="MONDO" ID="0000002"/>')
        with self.assertRaisesRegex(ValueError, 'EXACT_SCV_EREPO_LINK_REQUIRED'):
            self.extract()

    def test_unmapped_condition_does_not_relax_expert_source_agreement(self):
        self.xml = self.xml.replace(b'<XRef DB="MONDO" ID="0000001"/>',
                                    b'<XRef DB="Orphanet" ID="ORPHA123"/>')
        self.summary['data'][0]['mondoId'] = 'MONDO:0000002'
        with self.assertRaisesRegex(ValueError, 'SUMMARY_SEPIO_CONTEXT_MISMATCH'):
            self.extract()

    def test_unmapped_condition_does_not_relax_native_allele_check(self):
        self.xml = self.xml.replace(b'<XRef DB="MONDO" ID="0000001"/>',
                                    b'<XRef DB="Orphanet" ID="ORPHA123"/>')
        self.summary['data'][0]['hgvs'] = ['NC_000001.11:g.101G>C']
        with self.assertRaisesRegex(ValueError, 'SUMMARY_NATIVE_ALLELE_MISMATCH'):
            self.extract()

    def test_unmapped_record_is_not_counted_or_attached_as_fully_bound(self):
        self.xml = self.xml.replace(b'<XRef DB="MONDO" ID="0000001"/>',
                                    b'<XRef DB="Orphanet" ID="ORPHA123"/>')
        with tempfile.TemporaryDirectory() as temp:
            packet = self.manifest_run(Path(temp))
        self.assertEqual(0, packet['boundReferences'])
        row = packet['references'][0]
        full = dict(schemaVersion='missense-full-reference-evidence-v1', reviewStatus='UNREVIEWED',
            scientificAdoption=False, references=[dict(variationId='123', allele=ALLELE,
                assertionBindings=[dict(sourceAssertion=row['originalAssertion'], sourceBindingStatus='EXACT_VERSION_BOUND')])])
        attached = subject.attach_existing(full, [row])
        self.assertEqual('NOT_AVAILABLE_FOR_EXACT_ASSERTION',
            attached['references'][0]['assertionBindings'][0]['expertEvidence']['status'])

    def test_composite_uuid_prefix_or_extra_suffix_is_rejected(self):
        original = self.xml
        for key in (UUID + '|MONDO:0000001|extra', 'prefix-' + UUID + '|MONDO:0000001'):
            with self.subTest(key=key):
                self.xml = original.replace(('localKey="'+UUID+'"').encode(), ('localKey="'+key+'"').encode())
                with self.assertRaisesRegex(ValueError, 'EXACT_SCV_EREPO_LINK_REQUIRED'):
                    self.extract()

    def test_same_protein_different_dna_rejected(self):
        self.summary['data'][0]['hgvs'] = ['NC_000001.11:g.101G>C']
        with self.assertRaisesRegex(ValueError, 'SUMMARY_NATIVE_ALLELE_MISMATCH'):
            self.extract()

    def test_retracted_summary_rejected(self):
        self.summary['data'][0]['retracted'] = True
        with self.assertRaisesRegex(ValueError, 'RETRACTED'):
            self.extract()

    def test_conflicting_expert_class_not_overwritten(self):
        self.ep['data']['statementOutcome']['label'] = 'Likely pathogenic'
        with self.assertRaisesRegex(ValueError, 'CLASSIFICATION_MISMATCH'):
            self.extract()

    def test_nonexpert_scv_rejected_even_when_aggregate_expert(self):
        self.xml = self.xml.replace(b'<ReviewStatus>reviewed by expert panel</ReviewStatus><GermlineClassification>Pathogenic',
            b'<ReviewStatus>criteria provided, single submitter</ReviewStatus><GermlineClassification>Pathogenic')
        with self.assertRaisesRegex(ValueError, 'EXPERT_SCV_REQUIRED'):
            self.extract()

    def test_missing_summary_retains_sepio_and_gap(self):
        self.ref.pop('summarySource')
        row = self.extract()
        self.assertIn('SUMMARY_VERSION_RETRACTION_AND_FINAL_CRITERIA_NOT_BOUND', row['gapReasons'])
        self.assertEqual(2, len(row['criterionAssessments']))

    def test_missing_sepio_retains_summary_circularity_and_gap(self):
        self.ref.pop('sepioSource')
        self.summary['data'][0]['metCodes'].append('PM5_Supporting')
        row = self.extract()
        self.assertIn('VERSIONED_SEPIO_SOURCE_NOT_PROVIDED', row['gapReasons'])
        self.assertEqual(['PM5'], row['appliedComparisonCriteria'])

    def test_missing_all_expert_sources_rejected(self):
        self.ref.pop('sepioSource'); self.ref.pop('summarySource')
        with self.assertRaisesRegex(ValueError, 'EXPERT_EVIDENCE_SOURCE_NOT_PROVIDED'):
            self.extract()

    def test_gene_agnostic(self):
        self.xml = self.xml.replace(b'Symbol="TEST"', b'Symbol="ANOTHER"')
        self.summary['data'][0]['gene'] = 'ANOTHER'
        self.assertEqual(['ANOTHER'], self.extract()['geneSymbols'])

    def packet(self, scv='SCV000001.2'):
        return dict(schemaVersion='missense-full-reference-evidence-v1', reviewStatus='UNREVIEWED', scientificAdoption=False,
            references=[dict(variationId='123', allele=ALLELE, assertionBindings=[dict(sourceBindingStatus='EXACT_VERSION_BOUND',
                sourceAssertion=dict(scv=scv, classification='Pathogenic', reviewStatus='reviewed by expert panel',
                    dateLastEvaluated='2020-01-02', geneSymbol='TEST'))])])

    def test_exact_attachment_preserves_original_whole_packet(self):
        packet = self.packet(); original = copy.deepcopy(packet)
        result = subject.attach_existing(packet, [self.extract()])
        binding = result['references'][0]['assertionBindings'][0]
        self.assertEqual('EXACT_SOURCE_BOUND', binding.pop('expertEvidence')['status'])
        self.assertEqual(original, result)
        self.assertEqual(original, packet)

    def test_attachment_never_upgrades_frozen_scv(self):
        result = subject.attach_existing(self.packet('SCV000001.1'), [self.extract()])
        self.assertEqual('NOT_AVAILABLE_FOR_EXACT_ASSERTION',
            result['references'][0]['assertionBindings'][0]['expertEvidence']['status'])

    def test_attachment_wrong_date_unbound(self):
        packet = self.packet()
        packet['references'][0]['assertionBindings'][0]['sourceAssertion']['dateLastEvaluated'] = '2022-01-02'
        result = subject.attach_existing(packet, [self.extract()])
        self.assertEqual('UNBOUND', result['references'][0]['assertionBindings'][0]['expertEvidence']['status'])

    def test_official_source_url_only(self):
        for url in ['http://erepo.genome.network/evrepo/api/summary/classifications',
                    'https://example.org/evrepo/api/summary/classifications',
                    'https://erepo.genome.network/other']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                subject.official_source(dict(kind='EREPO_SUMMARY', url=url))

    def test_original_official_alias_and_variation_query_preserved(self):
        sources = self.sources()
        sources['ep'][0]['url'] = sources['ep'][0]['url'].replace('erepo.genome.network', 'erepo.clinicalgenome.org')
        sources['cv'][0]['url'] = 'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&id=123&rettype=vcv&is_variationid'
        for desc, _ in sources.values():
            subject.official_source(desc)
        self.assertEqual('EXACT_SOURCE_BOUND', subject.extract(self.ref, sources)['bindingStatus'])
        sources['cv'][0]['url'] = sources['cv'][0]['url'].replace('&is_variationid', '')
        with self.assertRaises(ValueError):
            subject.official_source(sources['cv'][0])

    def manifest_run(self, folder, mutate=None):
        sources = []
        for name, (desc, raw) in self.sources().items():
            path = folder / (name + '.raw'); path.write_bytes(raw)
            sources.append(dict(desc, sourceId=name, path=path.name))
        manifest = dict(schemaVersion='missense-expert-evidence-manifest-v1', references=[self.ref], sources=sources)
        if mutate: mutate(manifest)
        raw = json.dumps(manifest).encode(); path = folder / 'manifest.json'; path.write_bytes(raw)
        return subject.prepare(path, subject.base.sha(raw), folder / 'output')

    def test_batch_and_raw_source_descriptor_compatibility(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            packet = self.manifest_run(folder)
            self.assertEqual(1, packet['boundReferences'])
            desc = json.loads((folder / 'output/review-evidence-sources.json').read_text())['reviewEvidence']
            for d in desc:
                self.assertEqual('BYTES', d['format']); self.assertEqual('RAW_BYTES', d['sourceDigestKind'])
                self.assertEqual(d['sourceRecord']['sha256'], subject.base.sha((folder / 'output' / d['path']).read_bytes()))
            with self.assertRaisesRegex(ValueError, 'OUTPUT_EXISTS'):
                self.manifest_run(folder)

    def test_tamper_rejected_before_output(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            with self.assertRaisesRegex(ValueError, 'RAW_SHA256_MISMATCH'):
                self.manifest_run(folder, lambda m: m['sources'][0].update(rawSha256='0'*64))
            self.assertFalse((folder / 'output').exists())

    def test_one_bad_reference_does_not_hide_good_record(self):
        with tempfile.TemporaryDirectory() as temp:
            def add(m):
                m['references'].append(dict(self.ref, referenceId='bad', scv='SCV000001.99'))
            packet = self.manifest_run(Path(temp), add)
            self.assertEqual(1, packet['boundReferences'])
            self.assertEqual('BLOCKED_SOURCE_BINDING', packet['references'][1]['bindingStatus'])

    def test_duplicate_assertion_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, 'DUPLICATE_REFERENCE_ASSERTION'):
                self.manifest_run(Path(temp), lambda m: m['references'].append(dict(self.ref, referenceId='alias')))


class AppliedCriteriaStatementTest(unittest.TestCase):
    def summary(self, codes='PS3, PM3_Strong, PP4_Moderate, PP3'):
        return ('Example-specific ACMG/AMP criteria applied: PS3: enzyme activity; PM3: trans observations. '
                'In summary this variant meets criteria to be classified as pathogenic for the disease '
                'in an autosomal recessive manner based on the ACMG/AMP criteria applied as specified '
                'by the Expert Panel: (' + codes + ').')

    def test_final_summary_after_narrative_preserves_literal_list_and_original(self):
        text = self.summary()
        row, = self.parse(text)
        self.assertEqual('EXPLICIT_SOURCE_LIST', row['status'])
        self.assertEqual(['PS3', 'PM3_Strong', 'PP4_Moderate', 'PP3'], [c['original'] for c in row['criteria']])
        self.assertEqual(text, row['originalText'])
        self.assertTrue(text.endswith(row['rawSuffix']))
        self.assertFalse(row['scientificAdoption'])
        self.assertEqual([], row['specificationVersionMentions'])

    def test_final_summary_does_not_hide_comparison_dependency(self):
        row, = self.parse(self.summary('PS3, PM5, PP3'))
        self.assertEqual('EXPLICIT_SOURCE_LIST', row['status'])
        self.assertIn('PM5', [c['code'] for c in row['criteria']])

    def test_summary_negation_ambiguity_and_invalid_codes_remain_unparsed(self):
        for text in [self.summary().replace('meets criteria', 'does not meet criteria'),
                     self.summary().replace('meets criteria', 'may meet criteria'),
                     self.summary('PS3, PS3_Supporting'), self.summary('PM3_Very, PP3'),
                     self.summary() + ' PS1 may also apply.',
                     self.summary().replace('Example-specific ACMG/AMP criteria applied: PS3: enzyme activity; PM3: trans observations.',
                                            'ACMG/AMP criteria applied: PS1, PP3.'),
                     self.summary().replace('by the Expert Panel:', 'by the hypothetical Expert Panel:')]:
            with self.subTest(text=text):
                row, = self.parse(text)
                self.assertEqual('UNPARSED_SOURCE_STATEMENT', row['status'])

    def parse(self, text, xpath='/ClinicalAssertion[1]/Classification[1]/Comment[1]'):
        return subject.applied_criteria_statements([dict(text=text, xpath=xpath)], 'cv')

    def test_exact_final_list_retains_text_locator_and_suffixes(self):
        text = ('PS4 was not met. ACMG/AMP criteria applied, as specified by the expert panel '
                '(specification version 3.0.0, approved 6/30/2025): PM1, PM2_Supporting, PP3, PP4_Moderate.')
        row, = self.parse(text)
        self.assertEqual('EXPLICIT_SOURCE_LIST', row['status'])
        self.assertEqual(['PM1', 'PM2_Supporting', 'PP3', 'PP4_Moderate'],
                         [c['original'] for c in row['criteria']])
        self.assertEqual(text, row['originalText'])
        self.assertEqual('cv', row['sourceId'])
        self.assertEqual(['3.0.0'], row['specificationVersionMentions'])
        self.assertIsNone(row['criteria'][0]['strengthSuffix'])
        self.assertFalse(row['scientificAdoption'])

    def test_source_trailing_parenthesis_is_preserved(self):
        row, = self.parse('ACMG/AMP criteria applied (specification version 1.0): PP1_Strong, PP3).')
        self.assertEqual('EXPLICIT_SOURCE_LIST', row['status'])
        self.assertTrue(row['rawSuffix'].endswith(').'))

    def test_mentions_without_final_list_are_not_adopted(self):
        self.assertEqual([], self.parse('PS1 Not Met; evidence PS3 and PP3 were discussed.'))

    def test_negated_sentence_is_not_a_list(self):
        row, = self.parse('No ACMG/AMP criteria applied: PS1.')
        self.assertEqual('UNPARSED_SOURCE_STATEMENT', row['status'])
        self.assertEqual([], row['criteria'])

    def test_trailing_explanation_does_not_become_an_applied_list(self):
        row, = self.parse('ACMG/AMP criteria applied: PS3, PP3. PS1 was not met.')
        self.assertEqual('UNPARSED_SOURCE_STATEMENT', row['status'])

    def test_malformed_strength_is_not_silently_repaired(self):
        row, = self.parse('ACMG/AMP criteria applied: PM6_Very, PP3.')
        self.assertEqual('UNPARSED_SOURCE_STATEMENT', row['status'])
        self.assertIn('PM6_Very', row['rawSuffix'])

    def test_duplicate_code_at_different_strengths_is_ambiguous(self):
        row, = self.parse('ACMG/AMP criteria applied: PS3, PS3_Supporting.')
        self.assertEqual('UNPARSED_SOURCE_STATEMENT', row['status'])

    def test_two_lists_in_one_comment_are_not_arbitrarily_selected(self):
        row, = self.parse('ACMG/AMP criteria applied: PS1. ACMG/AMP criteria applied: PP3.')
        self.assertEqual('UNPARSED_SOURCE_STATEMENT', row['status'])

    def test_observation_comment_is_not_classification_statement(self):
        self.assertEqual([], self.parse('ACMG/AMP criteria applied: PS1.', '/ObservedIn[1]/Comment[1]'))

    def test_missing_colon_stays_unparsed(self):
        row, = self.parse('ACMG/AMP criteria applied PS3, PP3.')
        self.assertEqual('UNPARSED_SOURCE_STATEMENT', row['status'])

    def test_conditional_header_stays_unparsed(self):
        row, = self.parse('ACMG/AMP criteria applied, as specified by hypothetical guidance: PS1.')
        self.assertEqual('UNPARSED_SOURCE_STATEMENT', row['status'])


class AppliedCriteriaBindingTest(unittest.TestCase):
    setUp = ExpertEvidenceTest.setUp
    sources = ExpertEvidenceTest.sources
    extract = ExpertEvidenceTest.extract

    def with_statement(self, text):
        self.xml = self.xml.replace(b'Fictional evidence mentions PS1; no independence inferred.', text.encode())

    def test_narrative_supplements_missing_labels_without_filling_them(self):
        del self.ref['summarySource']
        for a in self.ep['data']['evidenceLine']:
            a['criterion'].pop('label')
        self.with_statement('ACMG/AMP criteria applied (specification version 2.0): PS3_Supporting.')
        row = self.extract()
        self.assertEqual('EXPLICIT_SOURCE_LIST', row['sourceAppliedCriteriaStatus'])
        self.assertEqual([], row['summaryMetCriteria'])
        self.assertTrue(all(a['code'] is None for a in row['criterionAssessments']))
        self.assertIn('SEPIO_CRITERION_LABEL_MISSING', row['gapReasons'])
        self.assertEqual('NOT_EVALUATED', row['independence'])

    def test_scv_comparison_dependency_is_visible_even_if_summary_omits_it(self):
        self.with_statement('ACMG/AMP criteria applied: PS3_Supporting, PS1.')
        row = self.extract()
        self.assertEqual(['PS1'], row['appliedComparisonCriteria'])
        self.assertIn('SCV_SUMMARY_APPLIED_CRITERIA_DISAGREE', row['gapReasons'])
        self.assertIn('SCV_SEPIO_APPLIED_CRITERIA_DISAGREE', row['gapReasons'])

    def test_strength_disagreement_is_retained_without_picking_a_winner(self):
        self.with_statement('ACMG/AMP criteria applied: PS3_Strong.')
        row = self.extract()
        self.assertIn('SCV_SUMMARY_APPLIED_CRITERIA_DISAGREE', row['gapReasons'])
        self.assertEqual('PS3_Supporting', row['summaryMetCriteria'][0]['original'])

    def test_sepio_default_strength_is_not_an_adopted_strength(self):
        self.ep['data']['evidenceLine'][0]['criterion']['defaultStrength']['label'] = 'Pathogenic Strong'
        row = self.extract()
        self.assertEqual('SEPIO_CRITERION_DEFAULT_NOT_ADOPTED', row['criterionStrengthSemantics'])
        self.assertEqual([], row['sourceAppliedCriteriaStatements'])
        self.assertEqual('PS3_Supporting', row['summaryMetCriteria'][0]['original'])
        self.assertEqual('Pathogenic Strong', row['criterionAssessments'][0]['sourceDeclaredStrength']['label'])

    def test_not_met_narrative_mentions_do_not_become_dependencies(self):
        self.with_statement('PS1 was not met. ACMG/AMP criteria applied: PS3_Supporting.')
        self.assertEqual([], self.extract()['appliedComparisonCriteria'])

    def test_statement_version_does_not_overwrite_structured_version(self):
        self.with_statement('ACMG/AMP criteria applied (specification version 1.0): PS3_Supporting.')
        row = self.extract()
        self.assertIn('SCV_STATEMENT_SPECIFICATION_VERSION_DISAGREEMENT', row['gapReasons'])
        self.assertEqual('2.0', row['specification']['originalAssertionMethod']['version'])
        self.assertEqual(['1.0'], row['sourceAppliedCriteriaStatements'][0]['specificationVersionMentions'])

    def test_multiple_conflicting_comments_keep_both_original_lists(self):
        self.with_statement('ACMG/AMP criteria applied: PS3_Supporting.')
        self.xml = self.xml.replace(b'</Classification>', b'<Comment>ACMG/AMP criteria applied: PS1.</Comment></Classification>')
        row = self.extract()
        self.assertEqual('CONFLICTING_SOURCE_LISTS', row['sourceAppliedCriteriaStatus'])
        self.assertEqual(2, len(row['sourceAppliedCriteriaStatements']))
        self.assertEqual(['PS1'], row['appliedComparisonCriteria'])


if __name__ == '__main__':
    unittest.main()
