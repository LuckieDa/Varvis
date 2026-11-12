"""Pinned synthetic originals exercise trusted v4 qualification, not clinical adoption."""
import copy
import json
import unittest
from assemble_materials import content_hash, sha
from assemble_runtime_bundle import assemble
import test_splice_material_supply as transport


class SpliceSourceQualificationTest(unittest.TestCase):
    def setUp(self):
        self.f = transport.SpliceMaterialSupplyTest();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.policy, self.model, self.outcome = self.f.policy, self.f.model, self.f.outcome
        self.model.update(contig='1', cdsSequence='ATGAAACCTGGTTTTGAATAA', proteinLength=6,
                          exons=[dict(ordinal=1, cdsStart=1, cdsEnd=6), dict(ordinal=2, cdsStart=7, cdsEnd=12), dict(ordinal=3, cdsStart=13, cdsEnd=21)])
        self.policy['appliedStrength'] = 'STRONG'
        self.outcome['event'].update(deleteStartCds=7, deleteEndCds=12)
        self.outcome['review'].update(maximumStrength='STRONG', functionalRescue='EXCLUDED_IN_REVIEWED_SCOPE',
                                     outcomeMultiplicity='SINGLE_SUPPORTED_OUTCOME', residualNormalTranscript='NOT_DETECTED_IN_ASSESSED_SCOPE')
        self.outcome['assessment'].update(sourceIds=['TEST_ONLY'], tissueRelevance='ASSESSED', alleleAttribution='ASSESSED',
                                         assayQuality='ASSESSED', alternativeOutcomesAssessment='ASSESSED')
        self.config = dict(schemaVersion='pvs1-source-qualified-assembly-input-v1',
                           release=dict(bindingId='TEST_ONLY_SPLICE_SOURCE_RELEASE', assembledAt='2026-01-01T00:00:00Z'),
                           sourceQualification=dict(policyId='TEST_ONLY', version='1.0.0', purpose='PROJECT_RESEARCH',
                                                    allowedDataClasses=['SYNTHETIC', 'PRIVACY_BOUNDED_RUNTIME'], clinicalAcceptance=False, createsExpertReview=False))

    def run_assembly(self):
        self.f.files.write('model.json', self.model)
        self.f.doc['transcriptModelResources'][0]['canonicalSha256'] = content_hash(self.model)
        self.policy['transcriptModelBinding']['canonicalSha256'] = content_hash(self.model)
        self.outcome['transcriptModelSha256'] = content_hash(self.model)
        self.f.set_outcomes([self.outcome])
        return assemble(self.config, self.f.base.facts, 'a'*64, self.f.materials(), self.f.base.profile)

    def test_actual_pinned_originals_preserve_scope_assay_and_exact_outcome_identity(self):
        bundle, use, audit = self.run_assembly()
        self.assertEqual(1, audit['boundPolicies'], audit)
        q = use['entries'][0]['qualification']
        self.assertEqual(self.policy['spliceScope'], q['knowledge']['spliceScope'])
        self.assertEqual(self.model, q['spliceVerification']['model'])
        proof = q['spliceVerification']['outcomes'][0]
        self.assertEqual(self.outcome, proof['outcome'])
        self.assertEqual(content_hash(self.outcome), proof['canonicalSha256'])
        self.assertGreater(proof['bytes'], 0)
        self.assertEqual([self.outcome], bundle['materials']['pvs1SpliceOutcomes'])
        self.assertFalse(q['createsExpertReview'])
        self.assertNotIn('SOURCE_QUALIFICATION_SPLICE_SCOPE_NOT_ENABLED', str(audit))

    def test_review_only_assay_unknown_source_corruption_and_wrong_exact_binding_remain_blocked(self):
        for mutation in ['review', 'assay', 'source', 'model', 'allele', 'composition']:
            with self.subTest(mutation=mutation):
                saved = copy.deepcopy((self.outcome, self.policy))
                if mutation == 'review': self.outcome['review']['status'] = 'UNREVIEWED'
                if mutation == 'assay': self.outcome['assessment']['alleleAttribution'] = 'UNKNOWN'
                if mutation == 'source': self.outcome['sourceRecords'][0]['sha256'] = '0'*64
                if mutation == 'model': self.outcome['transcript'] = 'ENST999999.1'
                if mutation == 'allele': self.outcome['referenceWindow']['sequence'] = 'CT'
                if mutation == 'composition': self.outcome['codingEditComposition'] = dict(status='REVIEWED_FOR_SAME_ALLELE_COMPOSITION')
                _, use, audit = self.run_assembly()
                # Materials for another transcript are not borrowed; no event is claimed qualified.
                if mutation == 'model':
                    self.assertEqual([], use['entries'][0]['qualification']['spliceVerification']['outcomes'])
                else:
                    self.assertEqual(0, audit['boundPolicies'], audit)
                self.outcome.clear();self.outcome.update(saved[0]);self.policy.clear();self.policy.update(saved[1])

    def test_independent_inframe_scope_and_functional_sources_are_not_inherited_from_outer_review(self):
        scope = dict(status='REVIEWED_FOR_PVS1_SPLICE_INFRAME', reviewId='TEST_ONLY_FUNCTION', basis='Toy explicit function review', sourceIds=['TEST_ONLY'],
                     exonAssessments=[dict(ordinal=2, biologicalRelevance='PRESENT', populationLof='NOT_FREQUENT', basis='Toy exon facts', sourceIds=['TEST_ONLY'])],
                     rules=[dict(ruleId='local', predicate='CRITICAL_REGION', start=3, end=4, minimumAffectedResidues=2, strength='STRONG', basis='Toy local rule', sourceIds=['TEST_ONLY'])])
        self.policy['spliceScope']['inFrameScope'] = scope
        self.outcome['inFrameReview'] = dict(status='REVIEWED_FOR_PVS1_SPLICE_INFRAME', reviewId='TEST_ONLY_EFFECT', basis='Toy independently reviewed function', sourceIds=['TEST_ONLY'])
        self.assertEqual(1, self.run_assembly()[2]['boundPolicies'])
        scope['status'] = 'UNREVIEWED'
        self.assertEqual(0, self.run_assembly()[2]['boundPolicies'])
        scope['status'] = 'REVIEWED_FOR_PVS1_SPLICE_INFRAME'
        scope['rules'][0]['sourceIds'] = ['missing-function-source']
        self.assertEqual(0, self.run_assembly()[2]['boundPolicies'])

    def test_residual_transcript_matrix_preserves_prediction_unknowns_and_rejects_conflicts(self):
        # Expectations are fixed before production changes in the boundary review.
        # Reuse ordinary pinned-file assembly; do not create a new qualification stub.
        vectors = [
            ('missing', None, True),
            ('not-measured', 'NOT_MEASURED', True),
            ('unknown', 'UNKNOWN', True),
            ('not-detected', 'NOT_DETECTED_IN_ASSESSED_SCOPE', True),
            ('present', 'PRESENT', False),
            ('detected', 'DETECTED_IN_ASSESSED_SCOPE', False),
            ('json-null', None, False),
            ('boolean', False, False),
            ('number', 0, False),
            ('array', [], False),
            ('object', {}, False),
            ('unknown-label', 'UNRECOGNIZED_SOURCE_LABEL', False),
            ('empty-string', '', False),
        ]
        original = copy.deepcopy(self.outcome)
        self.policy['spliceScope']['evidenceKinds'] = ['OBSERVED_RNA', 'PREDICTED_CANONICAL']
        for predicted in [True, False]:
            for label, value, prediction_compatible in vectors:
                with self.subTest(predicted=predicted, residual=label):
                    self.outcome.clear()
                    self.outcome.update(copy.deepcopy(original))
                    self.outcome['evidenceKind'] = 'PREDICTED_CANONICAL' if predicted else 'OBSERVED_RNA'
                    self.outcome['assessment'].update(
                        status='QUALIFIED_CANONICAL_PREDICTION' if predicted else 'QUALIFIED_RNA_ASSAY',
                        nearbyRescueAssessment='ASSESSED')
                    if label == 'missing':
                        self.outcome['review'].pop('residualNormalTranscript', None)
                    else:
                        self.outcome['review']['residualNormalTranscript'] = copy.deepcopy(value)
                    compatible = prediction_compatible if predicted else label == 'not-detected'
                    bundle, use, audit = self.run_assembly()
                    self.assertEqual(1, audit['boundPolicies'], audit)
                    proof = use['entries'][0]['qualification']['spliceVerification']['outcomes'][0]
                    self.assertEqual(self.outcome, proof['outcome'])
                    self.assertEqual(self.outcome['review'], proof['outcome']['review'])
                    self.assertEqual('RAW_SHA_MATCH', proof['status'])
                    self.assertGreater(proof['bytes'], 0)
                    self.assertEqual([self.outcome], bundle['materials']['pvs1SpliceOutcomes'])
                    unresolved = [] if compatible else [
                        dict(outcomeId=self.outcome['outcomeId'], reasonCode='PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED')
                    ]
                    self.assertEqual(unresolved, audit['policies'][0].get('unresolvedSpliceOutcomes', []))
    def test_missing_source_bytes_are_read_again_and_never_turned_into_a_review(self):
        self.assertEqual(1, self.run_assembly()[2]['boundPolicies'])
        (self.f.files.root/'source.txt').write_bytes(b'changed original')
        _, use, audit = self.run_assembly()
        self.assertEqual([], use['entries'])
        self.assertEqual(0, audit['boundPolicies'])


class CompositeOriginalQualificationTest(unittest.TestCase):
    """Transport of actual toy source bytes; Java performs the actual A5 replay.

    Fixed reference ATG AAA CCC GGG TTT GAA TAA; RNA c7_12del plus
    retained c13T>C -> ATG AAA CTT GAA TAA = MKLE*. The outer effect hash
    is deliberately a context binding input, not a Python-computed score.
    """
    def setUp(self):
        self.base = SpliceSourceQualificationTest();self.base.setUp();self.addCleanup(self.base.doCleanups)
        self.policy, self.model, self.outcome = self.base.policy, self.base.model, self.base.outcome
        self.model.update(schemaVersion='native-coding-transcript-model-v1', modelId='TEST_ONLY_COMPOSITE', modelVersion='1',
                          assembly='GRCh38', contig='1', hgncId='HGNC:123', geneSymbol='TEST', transcript='ENST000001.1',
                          protein='ENSP000001.1', strand=1, cdsSequence='ATGAAACCCGGGTTTGAATAA', cdsLength=21, proteinLength=6,
                          exons=[dict(ordinal=1, cdsStart=1, cdsEnd=6, genomicStart=100, genomicEnd=105),
                                 dict(ordinal=2, cdsStart=7, cdsEnd=12, genomicStart=200, genomicEnd=205),
                                 dict(ordinal=3, cdsStart=13, cdsEnd=21, genomicStart=300, genomicEnd=308)])
        self.outcome['allele'].update(position=300, reference='T', alternate='C')
        self.outcome['referenceWindow'].update(start=300, sequence='T')
        self.policy['transcriptModelBinding']['canonicalSha256'] = content_hash(self.model)
        self.outcome['transcriptModelSha256'] = content_hash(self.model)
        self.policy['spliceScope']['inFrameScope'] = dict(status='REVIEWED_FOR_PVS1_SPLICE_INFRAME', reviewId='TEST_ONLY_FUNCTION',
            basis='Toy reviewed function', sourceIds=['TEST_ONLY'],
            exonAssessments=[dict(ordinal=i, biologicalRelevance='PRESENT', populationLof='NOT_FREQUENT', basis='Toy exon fact', sourceIds=['TEST_ONLY']) for i in [1, 2, 3]],
            rules=[dict(ruleId='deleted3to4', predicate='CRITICAL_REGION', start=3, end=4, minimumAffectedResidues=2, strength='STRONG', basis='Toy complete codons', sourceIds=['TEST_ONLY'])])
        self.outcome['codingEditComposition'] = dict(schemaVersion='pvs1-splice-coding-composition-v1', status='REVIEWED_FOR_SAME_ALLELE_COMPOSITION',
            reviewId='TEST_ONLY_SAME_ALLELE', basis='Toy exact same physical allele', sourceIds=['TEST_ONLY'],
            alleleGroupSha256=content_hash(self.outcome['allele']), referenceCdsSha256=sha(self.model['cdsSequence'].encode('ascii')),
            spliceEventSha256=content_hash(self.outcome['event']), sequenceScope='COMPLETE_ALLELIC_CDS', alteredCdsSequence='ATGAAACTTGAATAA')
        self.outcome['inFrameReview'] = dict(status='REVIEWED_FOR_PVS1_SPLICE_INFRAME', reviewId='TEST_ONLY_OUTCOME_FUNCTION',
                                           basis='Toy actual whole-effect review', sourceIds=['TEST_ONLY'])
        frame = copy.deepcopy(self.outcome['inFrameReview'])
        effect = dict(schemaVersion='pvs1-composite-inframe-effect-review-v1', status='REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT',
                      reviewId='TEST_ONLY_WHOLE_ORIGINAL', basis='Toy original reviews actual complete effect', sourceIds=['COMPOSITE_ORIGINAL'],
                      mechanicalEffectSha256='b'*64)
        self.outcome['inFrameReview']['compositeEffectAssessment'] = effect
        self.original = dict(schemaVersion='pvs1-composite-effect-review-original-v1', reviewId=effect['reviewId'],
            scope={k:copy.deepcopy(self.outcome[k]) for k in ['hgncId', 'mondoId', 'moi', 'transcript', 'transcriptModelSha256', 'allele']},
            sequence=dict(referenceCdsSha256=sha(self.model['cdsSequence'].encode('ascii')), alteredCdsSequence='ATGAAACTTGAATAA', alteredCdsSha256=sha(b'ATGAAACTTGAATAA')),
            events=dict(rnaEvent=copy.deepcopy(self.outcome['event']), codingAllele=copy.deepcopy(self.outcome['allele']), codingEditComposition=copy.deepcopy(self.outcome['codingEditComposition'])),
            conditions=dict(parentReview=copy.deepcopy(self.outcome['review']), assayAssessment=copy.deepcopy(self.outcome['assessment']), inFrameReview=frame),
            use=dict(purpose='PROJECT_RESEARCH', allowedDataClasses=['SYNTHETIC', 'PRIVACY_BOUNDED_RUNTIME'], clinicalAcceptance=False, createsExpertReview=False))
        self.review_source = dict(sourceId='COMPOSITE_ORIGINAL', version='TEST_ONLY', url='https://example.test/composite-original',
                                  sha256='0'*64, relativePath='composite-original.json')
        self.outcome['sourceRecords'].append(self.review_source)
        self.seal()

    def seal(self, raw=None):
        raw = json.dumps(self.original, ensure_ascii=False) if raw is None else raw
        data = raw.encode('utf-8')
        self.base.f.files.write('composite-original.json', data)
        self.review_source['sha256'] = sha(data)
        self.outcome['inFrameReview']['compositeEffectAssessment']['originalReview'] = dict(sourceId='COMPOSITE_ORIGINAL', structuredOriginalUtf8=raw)

    def assembly(self):
        return self.base.run_assembly()

    def test_actual_toy_original_bytes_and_use_are_retained_but_sequence_replay_stays_required(self):
        bundle, use, audit = self.assembly()
        self.assertEqual(1, audit['boundPolicies'], audit)
        self.assertEqual('CORE_VALIDATION_REQUIRED', audit['policies'][0]['spliceMaterialStatus'])
        self.assertEqual(self.outcome, bundle['materials']['pvs1SpliceOutcomes'][0])
        self.assertEqual(self.outcome, use['entries'][0]['qualification']['spliceVerification']['outcomes'][0]['outcome'])
        self.assertFalse(use['qualificationPolicy']['clinicalAcceptance'])
        self.assertFalse(use['qualificationPolicy']['createsExpertReview'])

    def test_bare_status_hash_and_bad_original_bytes_do_not_create_qualification(self):
        baseline = copy.deepcopy(self.outcome)
        for mutation in ['missing', 'unknownSource', 'unreferenced', 'utf8Changed', 'fileChanged']:
            with self.subTest(mutation=mutation):
                self.outcome.clear();self.outcome.update(copy.deepcopy(baseline))
                effect = self.outcome['inFrameReview']['compositeEffectAssessment']
                if mutation == 'missing': effect.pop('originalReview')
                if mutation == 'unknownSource': effect['originalReview']['sourceId'] = 'unknown'
                if mutation == 'unreferenced': effect['sourceIds'] = ['TEST_ONLY']
                if mutation == 'utf8Changed': effect['originalReview']['structuredOriginalUtf8'] = 'changed'
                if mutation == 'fileChanged': (self.base.f.files.root/'composite-original.json').write_bytes(b'changed')
                self.assertEqual(0, self.assembly()[2]['boundPolicies'])
                (self.base.f.files.root/'composite-original.json').write_bytes(baseline['inFrameReview']['compositeEffectAssessment']['originalReview']['structuredOriginalUtf8'].encode('utf-8'))

    def test_even_rehashed_original_scope_events_conditions_and_use_are_exact(self):
        baseline = copy.deepcopy(self.original)
        for mutation in ['scope', 'model', 'sequence', 'rna', 'coding', 'composition', 'parent', 'assay', 'function', 'purpose', 'classes', 'clinical', 'expert', 'reviewId', 'extra', 'invalidJson', 'duplicateJson', 'trailingJson', 'trailingScalar', 'deepJson', 'tooLarge']:
            with self.subTest(mutation=mutation):
                self.original = copy.deepcopy(baseline)
                if mutation == 'scope': self.original['scope']['mondoId'] = 'MONDO:wrong'
                if mutation == 'model': self.original['scope']['transcriptModelSha256'] = '0'*64
                if mutation == 'sequence': self.original['sequence']['alteredCdsSequence'] = 'ATGTAA'
                if mutation == 'rna': self.original['events']['rnaEvent']['deleteEndCds'] = 11
                if mutation == 'coding': self.original['events']['codingAllele']['alternate'] = 'A'
                if mutation == 'composition': self.original['events']['codingEditComposition']['reviewId'] = 'different'
                if mutation == 'parent': self.original['conditions']['parentReview']['maximumStrength'] = 'SUPPORTING'
                if mutation == 'assay': self.original['conditions']['assayAssessment']['tissueRelevance'] = 'UNKNOWN'
                if mutation == 'function': self.original['conditions']['inFrameReview']['reviewId'] = 'single-deletion'
                if mutation == 'purpose': self.original['use']['purpose'] = 'CLINICAL'
                if mutation == 'classes': self.original['use']['allowedDataClasses'] = ['PUBLIC_REFERENCE']
                if mutation == 'clinical': self.original['use']['clinicalAcceptance'] = True
                if mutation == 'expert': self.original['use']['createsExpertReview'] = True
                if mutation == 'reviewId': self.original['reviewId'] = 'different'
                if mutation == 'extra': self.original['selfAuthority'] = True
                raw = json.dumps(self.original)
                if mutation == 'invalidJson': raw = 'not json'
                if mutation == 'duplicateJson': raw = raw[:-1]+',"reviewId":"TEST_ONLY_WHOLE_ORIGINAL"}'
                if mutation == 'trailingJson': raw += '{}'
                if mutation == 'trailingScalar': raw += ' true'
                if mutation == 'deepJson': raw = '['*2000+'0'+']'*2000
                if mutation == 'tooLarge': raw = ' '*65537
                self.seal(raw)
                self.assertEqual(0, self.assembly()[2]['boundPolicies'])

    def test_missing_applicable_inframe_scope_is_not_created_by_a_review_original(self):
        self.policy['spliceScope'].pop('inFrameScope')
        _, _, audit = self.assembly()
        self.assertEqual(0, audit['boundPolicies'])
        self.assertIn('SPLICE_COMPOSITE_POLICY_SCOPE_REQUIRED', audit['policies'][0]['reasonCodes'])


if __name__ == '__main__': unittest.main()
