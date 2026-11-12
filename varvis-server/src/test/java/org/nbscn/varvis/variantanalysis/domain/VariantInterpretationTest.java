package org.nbscn.varvis.variantanalysis.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VariantInterpretationTest {

    @Test
    void replacesUnavailableVcfClinvarWithApplicableLocalClinvar() {
        VariantInterpretation interpretation = interpretation(
                EvidenceItem.unavailable(
                        "CLINVAR",
                        "VCF_INFO",
                        "CLINICAL",
                        "No ClinVar fields were present in the normalized VCF"
                )
        );

        interpretation.addEvidence(Collections.singletonList(evidence(
                "CLINVAR",
                "ClinVar 2026-03-21 GRCh38",
                "CLINICAL",
                "APPLICABLE",
                null,
                "Pathogenic",
                "ClinVar local GRCh38 record"
        )));

        assertEquals(1, interpretation.getEvidence().size());
        assertEquals("APPLICABLE", interpretation.getEvidence().get(0).getApplicability());
        assertEquals("ClinVar 2026-03-21 GRCh38", interpretation.getEvidence().get(0).getSourceVersion());
    }

    @Test
    void replacesVcfInfoGnomadWhenLocalGnomadIsApplicable() {
        VariantInterpretation interpretation = interpretation(evidence(
                "GNOMAD",
                "VCF_INFO",
                "POPULATION",
                "APPLICABLE",
                0.5d,
                "Maximum allele frequency",
                "Population frequency carried by the input VCF"
        ));

        interpretation.addEvidence(Collections.singletonList(evidence(
                "GNOMAD",
                "gnomAD exomes v4.1 GRCh38",
                "POPULATION",
                "APPLICABLE",
                0.00001d,
                "Maximum allele frequency",
                "gnomAD local allele frequency"
        )));

        assertEquals(1, interpretation.getEvidence().size());
        assertEquals(0.00001d, interpretation.getEvidence().get(0).getRawScore());
        assertEquals("gnomAD exomes v4.1 GRCh38", interpretation.getEvidence().get(0).getSourceVersion());
    }

    @Test
    void replacesVcfGnomadFrequencyWhenLocalFrequencyIsHigher() {
        VariantInterpretation interpretation = interpretation(evidence(
                "GNOMAD",
                "VCF_INFO",
                "POPULATION",
                "APPLICABLE",
                0.00001d,
                "Maximum allele frequency",
                "Population frequency carried by the input VCF"
        ));

        interpretation.addEvidence(Collections.singletonList(evidence(
                "GNOMAD",
                "gnomAD exomes v4.1 GRCh38",
                "POPULATION",
                "APPLICABLE",
                0.02d,
                "Maximum allele frequency",
                "gnomAD local allele frequency"
        )));

        assertEquals(1, interpretation.getEvidence().size());
        assertEquals(0.02d, interpretation.getEvidence().get(0).getRawScore());
        assertEquals("gnomAD exomes v4.1 GRCh38", interpretation.getEvidence().get(0).getSourceVersion());
    }

    @Test
    void replacesVcfInfoWithLocalNoRecordWhenLocalProviderIsAvailable() {
        VariantInterpretation interpretation = interpretation(evidence(
                "GNOMAD",
                "VCF_INFO",
                "POPULATION",
                "APPLICABLE",
                0.5d,
                "Maximum allele frequency",
                "Population frequency carried by the input VCF"
        ));

        interpretation.addEvidence(Collections.singletonList(evidence(
                "GNOMAD",
                "gnomAD exomes v4.1 GRCh38",
                "POPULATION",
                "NO_RECORD",
                null,
                "No record",
                "Variant has no record in the configured gnomAD GRCh38 compact indexes"
        )));

        assertEquals(1, interpretation.getEvidence().size());
        assertEquals("NO_RECORD", interpretation.getEvidence().get(0).getApplicability());
        assertEquals("gnomAD exomes v4.1 GRCh38", interpretation.getEvidence().get(0).getSourceVersion());
    }

    @Test
    void keepsVcfInfoFallbackWhenLocalProviderIsUnavailable() {
        VariantInterpretation interpretation = interpretation(evidence(
                "CLINVAR",
                "VCF_INFO",
                "CLINICAL",
                "APPLICABLE",
                null,
                "Pathogenic",
                "ClinVar fields carried by the input VCF"
        ));

        interpretation.addEvidence(Collections.singletonList(EvidenceItem.unavailable(
                "CLINVAR",
                "ClinVar local",
                "CLINICAL",
                "Configured ClinVar GRCh38 file or Tabix index is unavailable"
        )));

        assertEquals(1, interpretation.getEvidence().size());
        assertEquals("APPLICABLE", interpretation.getEvidence().get(0).getApplicability());
        assertEquals("VCF_INFO", interpretation.getEvidence().get(0).getSourceVersion());
    }

    @Test
    void keepsVcfInfoFallbackWhenUnavailableProviderStatusHasStableIdentity() {
        VariantInterpretation interpretation = interpretation(evidence(
                "CLINVAR",
                "VCF_INFO",
                "CLINICAL",
                "APPLICABLE",
                null,
                "Pathogenic",
                "ClinVar fields carried by the input VCF"
        ));

        interpretation.addEvidence(Collections.singletonList(unifiedStatusEvidence(
                "clinvar|GRCh37|UNAVAILABLE|native_tabix",
                "UNAVAILABLE"
        )));

        assertEquals(1, interpretation.getEvidence().size());
        assertEquals("APPLICABLE", interpretation.getEvidence().get(0).getApplicability());
        assertEquals("VCF_INFO", interpretation.getEvidence().get(0).getSourceVersion());
    }

    @Test
    void mergesDuplicateEvidenceWhenLoadingLegacyPayload() {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "19", 1037767, "G", "A");
        VariantInterpretation interpretation = new VariantInterpretation(
                "GRCh38:19:1037767:G:A",
                new VariantQcObservation(
                        key,
                        "proband",
                        "0/1",
                        30,
                        99,
                        "18,12",
                        0.4d,
                        "PASS",
                        "SNV",
                        "chr19|1037767|G|A|1"
                ),
                Collections.emptyList(),
                Arrays.asList(
                        EvidenceItem.unavailable(
                                "CLINVAR",
                                "VCF_INFO",
                                "CLINICAL",
                                "No ClinVar fields were present in the normalized VCF"
                        ),
                        evidence(
                                "GNOMAD",
                                "VCF_INFO",
                                "POPULATION",
                                "APPLICABLE",
                                0.5d,
                                "Maximum allele frequency",
                                "Population frequency carried by the input VCF"
                        ),
                        evidence(
                                "CLINVAR",
                                "ClinVar 2026-03-21 GRCh38",
                                "CLINICAL",
                                "APPLICABLE",
                                null,
                                "Pathogenic",
                                "ClinVar local GRCh38 record"
                        ),
                        EvidenceItem.unavailable(
                                "GNOMAD",
                                "gnomAD exomes v4.1 GRCh38",
                                "POPULATION",
                                "gnomAD local lookup is disabled"
                        )
                )
        );

        assertEquals(2, interpretation.getEvidence().size());
        assertEquals("CLINVAR", interpretation.getEvidence().get(0).getSource());
        assertEquals("APPLICABLE", interpretation.getEvidence().get(0).getApplicability());
        assertEquals("GNOMAD", interpretation.getEvidence().get(1).getSource());
        assertEquals("APPLICABLE", interpretation.getEvidence().get(1).getApplicability());
        assertEquals(0.5d, interpretation.getEvidence().get(1).getRawScore());
    }

    @Test
    void keepsApplicableVcfInfoSpliceAiWhenLocalProviderIsDisabled() {
        VariantInterpretation interpretation = interpretation(evidence(
                "SPLICEAI",
                "VCF_INFO",
                "COMPUTATIONAL",
                "APPLICABLE",
                0.82d,
                "SpliceAI maximum delta score",
                "SpliceAI fields carried by the input VCF"
        ));

        interpretation.addEvidence(Collections.singletonList(evidence(
                "SPLICEAI",
                "v1.3.1",
                "COMPUTATIONAL",
                "DISABLED",
                null,
                "Disabled",
                "SpliceAI local lookup is disabled"
        )));

        assertEquals(1, interpretation.getEvidence().size());
        assertEquals("APPLICABLE", interpretation.getEvidence().get(0).getApplicability());
        assertEquals("VCF_INFO", interpretation.getEvidence().get(0).getSourceVersion());
        assertEquals(0.82d, interpretation.getEvidence().get(0).getRawScore());
    }

    @Test
    void replacesVcfInfoSpliceAiNoRecordWithLocalNotApplicable() {
        VariantInterpretation interpretation = interpretation(evidence(
                "SPLICEAI",
                "VCF_INFO",
                "COMPUTATIONAL",
                "NO_RECORD",
                null,
                "No record",
                "No allele-matched SpliceAI INFO evidence was present"
        ));

        interpretation.addEvidence(Collections.singletonList(evidence(
                "SPLICEAI",
                "v1.3.1",
                "COMPUTATIONAL",
                "NOT_APPLICABLE",
                null,
                "Not applicable",
                "SpliceAI is queried only for splice-relevant consequences"
        )));

        assertEquals(1, interpretation.getEvidence().size());
        assertEquals("NOT_APPLICABLE", interpretation.getEvidence().get(0).getApplicability());
        assertEquals("v1.3.1", interpretation.getEvidence().get(0).getSourceVersion());
    }

    @Test
    void bindsEvidenceToNormalizedVariantContext() {
        VariantInterpretation interpretation = interpretation(evidence(
                "GNOMAD",
                "gnomAD 4.1",
                "POPULATION",
                "NO_RECORD",
                null,
                "No record",
                "No matching normalized allele"
        ));

        EvidenceItem stored = interpretation.getEvidence().get(0);
        assertEquals(EvidenceItem.CURRENT_SCHEMA_VERSION, stored.getSchemaVersion());
        assertEquals("GRCh38|1|100|A|G", stored.getCanonicalVariantId());
        assertEquals("GRCh38", stored.getAssembly());
        assertEquals("NO_RECORD", stored.getStatus());
        assertEquals(EvidenceItem.INDETERMINATE_STRENGTH, stored.getEvidenceStrength());
    }

    @Test
    void keepsMultipleProviderRecordsWhenStableEvidenceIdsDiffer() {
        VariantInterpretation interpretation = interpretation(unifiedEvidence("clinvar:VCV1", "VCV1"));

        interpretation.addEvidence(Collections.singletonList(unifiedEvidence("clinvar:VCV2", "VCV2")));

        assertEquals(2, interpretation.getEvidence().size());
        assertNotEquals(interpretation.getEvidence().get(0).getId(), interpretation.getEvidence().get(1).getId());
    }

    @Test
    void deduplicatesExactStableIdentityWithoutChangingOrder() {
        EvidenceItem first = unifiedEvidence("clinvar:VCV1", "VCV1");
        VariantInterpretation interpretation = interpretation(first);

        interpretation.addEvidence(Arrays.asList(
                unifiedEvidence("clinvar:VCV1", "VCV1"),
                unifiedEvidence("clinvar:VCV2", "VCV2")
        ));
        interpretation.addEvidence(Collections.singletonList(unifiedEvidence("clinvar:VCV1", "VCV1")));

        assertEquals(2, interpretation.getEvidence().size());
        assertEquals("clinvar:VCV1", interpretation.getEvidence().get(0).getId());
        assertEquals("clinvar:VCV2", interpretation.getEvidence().get(1).getId());
    }

    @Test
    void keepsSourceRecordsAndConditionContextsDistinctWithoutExplicitId() {
        VariantInterpretation interpretation = interpretation(unifiedEvidence(
                null,
                "SCV1",
                "MONDO:1",
                "CLINICAL"
        ));

        interpretation.addEvidence(Arrays.asList(
                unifiedEvidence(null, "SCV2", "MONDO:1", "CLINICAL"),
                unifiedEvidence(null, "SCV1", "MONDO:2", "CLINICAL"),
                unifiedEvidence(null, "SCV1", "MONDO:1", "REVIEW_STATUS"),
                unifiedEvidence(null, "SCV1", "MONDO:1", "CLINICAL")
        ));

        assertEquals(4, interpretation.getEvidence().size());
    }

    @Test
    void keepsLegacyAndIdentityAwareEvidenceWithoutDroppingEither() {
        VariantInterpretation interpretation = interpretation(evidence(
                "CLINVAR",
                "VCF_INFO",
                "CLINICAL",
                "NO_RECORD",
                null,
                "No record",
                "Legacy fallback"
        ));

        interpretation.addEvidence(Collections.singletonList(unifiedEvidence("clinvar:VCV1", "VCV1")));

        assertEquals(2, interpretation.getEvidence().size());
        assertEquals("VCF_INFO", interpretation.getEvidence().get(0).getSourceVersion());
        assertEquals("clinvar:VCV1", interpretation.getEvidence().get(1).getId());
    }

    @Test
    void doesNotInventVariantContextWhenAssemblyIsUnresolved() {
        NormalizedVariantKey unresolved = new NormalizedVariantKey(null, "1", 100, "A", "G");
        VariantInterpretation interpretation = new VariantInterpretation(
                "unresolved",
                new VariantQcObservation(
                        unresolved,
                        "proband",
                        "0/1",
                        30,
                        99,
                        "18,12",
                        0.4d,
                        "PASS",
                        "SNV",
                        "1|100|A|G|1"
                ),
                Collections.emptyList(),
                Collections.singletonList(evidence(
                        "GNOMAD",
                        "unknown",
                        "POPULATION",
                        "UNAVAILABLE",
                        null,
                        "Unavailable",
                        "Assembly unresolved"
                ))
        );

        EvidenceItem stored = interpretation.getEvidence().get(0);
        assertNull(stored.getAssembly());
        assertNull(stored.getCanonicalVariantId());
    }

    @Test
    void analysisVcfCallEnrichmentAndIdentityHydrationPreserveInterpretationState() {
        VariantInterpretation source = interpretation(evidence(
                "CADD",
                "test",
                "PREDICTION",
                "APPLICABLE",
                24.0d,
                "CADD",
                "Synthetic evidence"
        ));
        TranscriptConsequence annotation = new TranscriptConsequence(
                "GENE1",
                null,
                "NM_SYNTHETIC.1",
                null,
                null,
                "missense_variant"
        );
        source.applyTranscriptAnnotations(Collections.singletonList(annotation));
        source.applyRanking(
                "HIGH_PRIORITY",
                0.91d,
                Collections.singletonMap("CADD", 0.5d),
                Collections.singletonList("positive"),
                Collections.singletonList("warning")
        );
        source.applyLargeFileClassification(
                VariantAnalysisBucket.HIGH_PRIORITY,
                true,
                true,
                true,
                "SYNTHETIC_SKIP",
                Collections.singletonList("cheap-gate")
        );
        source.applyFullInterpretationAvailable(false);
        AnalysisVcfCall call = AnalysisVcfCall.matched(
                AnalysisVcfCall.TargetAlleleCall.HET_TARGET,
                "REF/TARGET_ALT",
                1,
                40,
                90,
                24,
                16,
                0.4d,
                AnalysisVcfCall.FilterEvaluation.PASS,
                AnalysisVcfCall.PhasingStatus.UNPHASED,
                null,
                7,
                GenomeAssembly.GRCh38,
                Collections.emptyList(),
                Collections.emptyList()
        );

        VariantInterpretation enriched = source.withAnalysisVcfCall(call);
        VariantInterpretation reidentified = enriched.withCandidateIdentity(
                enriched.getCandidateIdentity()
        );

        assertEquals(call, reidentified.getAnalysisVcfCall());
        assertEquals(source.getObservation(), reidentified.getObservation());
        assertEquals(1, reidentified.getEvidence().size());
        assertEquals("CADD", reidentified.getEvidence().get(0).getSource());
        assertEquals(24.0d, reidentified.getEvidence().get(0).getRawScore());
        assertEquals(source.getConsequences(), reidentified.getConsequences());
        assertEquals(source.getTranscriptAnnotations(), reidentified.getTranscriptAnnotations());
        assertEquals("HIGH_PRIORITY", reidentified.getPriorityTier());
        assertEquals(0.91d, reidentified.getRankingScore());
        assertEquals(Collections.singletonMap("CADD", 0.5d), reidentified.getScoreBreakdown());
        assertEquals(Collections.singletonList("positive"), reidentified.getRationale());
        assertEquals(Collections.singletonList("warning"), reidentified.getWarnings());
        assertEquals(VariantAnalysisBucket.HIGH_PRIORITY, reidentified.getAnalysisBucket());
        assertEquals(true, reidentified.isLargeFileMode());
        assertEquals(true, reidentified.isSummaryOnly());
        assertEquals(true, reidentified.isExpensiveAnnotationSkipped());
        assertEquals("SYNTHETIC_SKIP", reidentified.getExpensiveAnnotationSkippedReason());
        assertEquals(Collections.singletonList("cheap-gate"), reidentified.getCheapGateReasons());
        assertEquals(false, reidentified.isFullInterpretationAvailable());
    }

    @Test
    void rejectsAnAnalysisVcfCallFromADifferentAssembly() {
        VariantInterpretation source = interpretation(evidence(
                "CADD",
                "test",
                "PREDICTION",
                "APPLICABLE",
                24.0d,
                "CADD",
                "Synthetic evidence"
        ));
        AnalysisVcfCall grch37Call = AnalysisVcfCall.notFound(GenomeAssembly.GRCh37);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> source.withAnalysisVcfCall(grch37Call)
        );

        assertEquals("ANALYSIS_VCF_CALL_CANDIDATE_ASSEMBLY_MISMATCH", failure.getMessage());
    }

    private VariantInterpretation interpretation(EvidenceItem evidence) {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        return new VariantInterpretation(
                "GRCh38:1:100:A:G",
                new VariantQcObservation(
                        key,
                        "proband",
                        "0/1",
                        30,
                        99,
                        "18,12",
                        0.4d,
                        "PASS",
                        "SNV",
                        "chr1|100|A|G|1"
                ),
                Collections.emptyList(),
                Collections.singletonList(evidence)
        );
    }

    private EvidenceItem evidence(String source,
                                  String version,
                                  String type,
                                  String applicability,
                                  Double rawScore,
                                  String label,
                                  String summary) {
        return new EvidenceItem(
                source,
                version,
                type,
                applicability,
                rawScore,
                label,
                summary,
                Collections.emptyMap()
        );
    }

    private EvidenceItem unifiedEvidence(String id, String sourceRecordId) {
        return unifiedEvidence(id, sourceRecordId, null, "CLINICAL");
    }

    private EvidenceItem unifiedEvidence(String id,
                                         String sourceRecordId,
                                         String diseaseId,
                                         String evidenceType) {
        return new EvidenceItem(
                EvidenceItem.CURRENT_SCHEMA_VERSION,
                id,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                diseaseId,
                "CLINVAR",
                sourceRecordId,
                "ClinVar legacy",
                "2026-03",
                null,
                null,
                evidenceType,
                "APPLICABLE",
                "APPLICABLE",
                null,
                "uncertain_significance",
                null,
                null,
                EvidenceItem.INDETERMINATE_STRENGTH,
                "ClinVar record",
                "Independent source record",
                Collections.emptyMap(),
                null,
                "CLINVAR_RECORD",
                Collections.emptyMap()
        );
    }

    private EvidenceItem unifiedStatusEvidence(String id, String applicability) {
        return new EvidenceItem(
                EvidenceItem.CURRENT_SCHEMA_VERSION,
                id,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "CLINVAR",
                null,
                "ClinVar local",
                "ClinVar local",
                null,
                "GRCh37",
                "CLINICAL",
                applicability,
                applicability,
                null,
                null,
                null,
                null,
                EvidenceItem.INDETERMINATE_STRENGTH,
                "Unavailable",
                "Configured local ClinVar resource is unavailable",
                Collections.emptyMap(),
                null,
                null,
                Collections.emptyMap()
        );
    }
}
