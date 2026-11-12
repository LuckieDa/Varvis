package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateRankingExplanationAssemblerTest {

    private final CandidateRankingExplanationAssembler assembler = new CandidateRankingExplanationAssembler();

    @Test
    void usesPersistedComponentsAndClassifiesTheirRecordedScores() {
        VariantInterpretation variant = variant();
        Map<String, ScoreBreakdownComponent> components = new LinkedHashMap<String, ScoreBreakdownComponent>();
        components.put("pathogenicity", new ScoreBreakdownComponent(25.0, Collections.singletonList("CADD threshold met")));
        components.put("population", new ScoreBreakdownComponent(-50.0, Collections.singletonList("Common allele")));
        components.put("splice", new ScoreBreakdownComponent(0.0, Collections.singletonList("No splice score")));
        variant.applyRanking("HIGH", 75.0, Collections.emptyMap(), components, "Persisted tier",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);

        assertEquals(CandidateRankingExplanationResponse.Completeness.COMPLETE, response.getCompleteness());
        assertEquals(3, response.getContributions().size());
        assertEquals(1, response.getPositiveContributions().size());
        assertEquals(1, response.getNegativeContributions().size());
        assertEquals(1, response.getNeutralContributions().size());
        assertEquals(75.0, response.getRankingScore());
        assertFalse(response.getProvenance().isRescored());
    }

    @Test
    void fallsBackToPersistedLegacyBreakdownWithoutReconstructingScores() {
        VariantInterpretation variant = variant();
        Map<String, Double> breakdown = new LinkedHashMap<String, Double>();
        breakdown.put("old_component", 7.0);
        variant.applyRanking("REVIEW", 7.0, breakdown,
                Collections.singletonList("legacy rationale"), Collections.emptyList());

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);

        assertEquals(CandidateRankingExplanationResponse.Completeness.LEGACY_PARTIAL, response.getCompleteness());
        assertEquals(7.0, response.getContributions().get(0).getScore());
        assertEquals("SCORE_BREAKDOWN", response.getContributions().get(0).getDerivedFrom());
    }

    @Test
    void reportsSummaryOnlyBeforeClaimingCompleteDetails() {
        VariantInterpretation variant = variant();
        variant.applyRanking("HIGH", 1.0, Collections.singletonMap("known", 1.0),
                Collections.emptyList(), Collections.emptyList());
        variant.applyLargeFileClassification(null, true, true, true, "compact", Collections.emptyList());

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);

        assertEquals(CandidateRankingExplanationResponse.Completeness.SUMMARY_ONLY, response.getCompleteness());
        assertFalse(response.getWarnings().isEmpty());
    }

    @Test
    void returnsUnavailableForOldCandidateWithoutPersistedExplanation() {
        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant());
        assertEquals(CandidateRankingExplanationResponse.Completeness.UNAVAILABLE, response.getCompleteness());
        assertTrue(response.getContributions().isEmpty());
    }

    @Test
    void reportsLegacyPositiveAndNegativeDirectionsWithoutCopyingNarrativesOrInventingScores() {
        VariantInterpretation variant = variant();
        variant.applyRanking("REVIEW", 4.0, Collections.emptyMap(), Collections.emptyMap(), null,
                Collections.singletonList("legacy support"), Collections.singletonList("legacy concern"),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);

        assertEquals(CandidateRankingExplanationResponse.Completeness.LEGACY_PARTIAL, response.getCompleteness());
        assertEquals(1, response.getPositiveContributions().size());
        assertEquals(1, response.getNegativeContributions().size());
        assertNull(response.getPositiveContributions().get(0).getScore());
    }

    @Test
    void categorizesEvidenceAvailabilityAndSerializesOnlyWhitelistedProvenance() throws Exception {
        Map<String, Object> unsafe = new LinkedHashMap<String, Object>();
        unsafe.put("path", "D:\\secret\\patient.vcf");
        unsafe.put("sampleId", "patient-7");
        VariantInterpretation variant = new VariantInterpretation(
                "synthetic-variant", null, Collections.emptyList(), Arrays.asList(
                new EvidenceItem("CADD", "v1", "PREDICTION", "APPLICABLE", 21.0, "high", "ok", unsafe),
                new EvidenceItem("CLINVAR", "v2", "CLINICAL", "CONFLICTING", null, "mixed", "mixed", unsafe),
                new EvidenceItem("GNOMAD", "v3", "POPULATION", "UNAVAILABLE", null, "missing", "missing", unsafe)));

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);
        String json = new ObjectMapper().writeValueAsString(response);

        assertEquals(1, response.getAvailableEvidence().size());
        assertEquals(1, response.getConflictingEvidence().size());
        assertEquals(1, response.getMissingEvidence().size());
        assertFalse(json.contains("patient.vcf"));
        assertFalse(json.contains("patient-7"));
        assertFalse(json.contains("details"));
    }

    @Test
    void treatsNoRecordNullAndUnknownEvidenceAsMissingFailClosed() {
        VariantInterpretation variant = new VariantInterpretation(
                "synthetic-variant", null, Collections.emptyList(), Arrays.asList(
                new EvidenceItem("GNOMAD", "v1", "POPULATION", "NO_RECORD", null, null, null, Collections.emptyMap()),
                new EvidenceItem("CADD", "v1", "PREDICTION", null, null, null, null, Collections.emptyMap()),
                new EvidenceItem("FUTURE", "v1", "OTHER", "FUTURE_STATUS", null, null, null, Collections.emptyMap())));

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);

        assertEquals(3, response.getMissingEvidence().size());
        assertTrue(response.getAvailableEvidence().isEmpty());
        assertEquals("NO_SOURCE_RECORD_REPORTED", response.getMissingEvidence().get(0).getAvailabilityMeaning());
        assertEquals("EVIDENCE_AVAILABILITY_UNPROVEN", response.getMissingEvidence().get(1).getAvailabilityMeaning());
    }

    @Test
    void omitsNonFiniteScoresAndAddsFixedWarning() {
        VariantInterpretation variant = variant();
        Map<String, ScoreBreakdownComponent> components = new LinkedHashMap<String, ScoreBreakdownComponent>();
        components.put("nan", new ScoreBreakdownComponent(Double.NaN, Collections.singletonList("patient-A")));
        components.put("infinity", new ScoreBreakdownComponent(Double.POSITIVE_INFINITY, Collections.emptyList()));
        variant.applyRanking("HIGH", Double.NEGATIVE_INFINITY, Collections.emptyMap(), components, null,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);

        assertNull(response.getRankingScore());
        assertNull(response.getContributions().get(0).getScore());
        assertNull(response.getContributions().get(1).getScore());
        assertTrue(response.getWarnings().stream().anyMatch(value -> value.contains("non-finite")));
    }

    @Test
    void assemblyIsADeepReadOnlySnapshotAndWithholdsAllFreeTextCanaries() throws Exception {
        Map<String, Object> canaries = new LinkedHashMap<String, Object>();
        canaries.put("sampleId", "PATIENT-CANARY");
        canaries.put("command", "powershell secret.ps1");
        EvidenceItem evidence = new EvidenceItem("PATIENT-CANARY", "sample-7", "TYPE", "APPLICABLE",
                1.0, "PATIENT-CANARY", "sample patient", canaries);
        VariantInterpretation variant = new VariantInterpretation(
                "synthetic-variant", null, Collections.emptyList(), Collections.singletonList(evidence));
        Map<String, ScoreBreakdownComponent> components = Collections.singletonMap(
                "safe_key", new ScoreBreakdownComponent(1.0,
                Collections.singletonList("/home/patient/sample PATIENT-CANARY powershell")));
        variant.applyRanking("HIGH", 1.0, Collections.emptyMap(), components, "/home/patient/tier",
                Collections.singletonList("PATIENT-CANARY"), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        ObjectMapper mapper = new ObjectMapper();
        String before = mapper.writeValueAsString(variant);

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);
        String after = mapper.writeValueAsString(variant);
        String output = mapper.writeValueAsString(response);

        assertEquals(before, after);
        assertFalse(output.contains("PATIENT-CANARY"));
        assertFalse(output.contains("/home/"));
        assertFalse(output.contains("sample-7"));
        assertFalse(output.toLowerCase().contains("powershell"));
        assertNull(response.getAvailableEvidence().get(0).getSource());
        assertNull(response.getAvailableEvidence().get(0).getResourceVersion());
    }

    @Test
    void preservesSafeUnknownKeysButSuppressesUnsafeKeyAndNarrative() {
        VariantInterpretation variant = variant();
        Map<String, ScoreBreakdownComponent> components = new LinkedHashMap<String, ScoreBreakdownComponent>();
        components.put("future_component.v2", new ScoreBreakdownComponent(3.0, Collections.singletonList("future reason")));
        components.put("D:\\secret", new ScoreBreakdownComponent(-1.0, Collections.singletonList("D:\\secret\\file")));
        variant.applyRanking("HIGH", 2.0, Collections.emptyMap(), components, "D:\\secret",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);

        assertEquals("future_component.v2", response.getContributions().get(0).getKey());
        assertEquals("unknown_component", response.getContributions().get(1).getKey());
        assertEquals("A negative persisted score contribution was recorded.",
                response.getContributions().get(1).getReasons().get(0));
        assertFalse(response.getContributions().get(1).getReasons().get(0).contains("secret"));
        assertNull(response.getTierReason());
    }

    @Test
    void exposesWhitelistedEngineContextWithoutAddingContributionsOrChangingLegacyJson() throws Exception {
        CandidateIdentity identity = CandidateIdentity.exomiser(
                "GRCh38|1|100|A|G", 2, "GENE1", "AR", 4
        );
        VariantInterpretation variant = new VariantInterpretation(
                identity,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                exomiserFeatures()
        );
        variant.applyRanking(
                "REVIEW",
                0.75d,
                Collections.singletonMap("persisted", 0.75d),
                Collections.emptyList(),
                Collections.emptyList()
        );

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);

        assertEquals("candidate-ranking-explanation-v2", response.getSchemaVersion());
        assertFalse(response.getProvenance().isRescored());
        assertEquals(1, response.getContributions().size());
        assertEquals("GENE1", response.getEngineCandidateContext().getGeneSymbol());
        assertEquals("EXOMISER_GENE_MOI_GROUP_NATIVE_RANK",
                response.getEngineCandidateContext().getGeneMoiGroupRankSemantics());
        assertEquals(0.81d, response.getEngineCandidateContext().getVariantScore());
        assertEquals("EXOMISER_VARIANT_SCORE",
                response.getEngineCandidateContext().getRankingScoreSourceColumn());
        assertNull(response.getEngineCandidateContext().getSourceRecordId());
        assertEquals(
                Arrays.asList("P-VALUE", "MOI:SOURCE_CONFLICT"),
                response.getEngineCandidateContext().getInvalidFields()
        );
        assertEquals(Collections.singletonMap("geneSymbol", "VARIANT_ROW"),
                response.getEngineCandidateContext().getFieldSources());

        String legacyJson = new ObjectMapper().writeValueAsString(
                assembler.assembleLegacy("analysis-1", variant)
        );
        assertTrue(legacyJson.contains("candidate-ranking-explanation-v1"));
        assertFalse(legacyJson.contains("engineCandidateContext"));
        assertFalse(legacyJson.contains("EXOMISER_VARIANT_SCORE"));
    }

    @Test
    void exposesOnlyWhitelistedAnalysisVcfCallFactsInV2AndKeepsV1Unchanged() throws Exception {
        AnalysisVcfCall call = AnalysisVcfCall.matched(
                AnalysisVcfCall.TargetAlleleCall.HET_TARGET,
                "REF|TARGET_ALT",
                2,
                null,
                null,
                18,
                12,
                0.4d,
                AnalysisVcfCall.FilterEvaluation.FILTERED,
                AnalysisVcfCall.PhasingStatus.PHASE_SET,
                "0123456789abcdef01234567",
                7,
                GenomeAssembly.GRCh38,
                Collections.singletonList("DP"),
                Collections.singletonList("GQ")
        );
        VariantInterpretation variant = variant().withAnalysisVcfCall(call);

        CandidateRankingExplanationResponse response = assembler.assemble("analysis-1", variant);
        CandidateRankingExplanationResponse.AnalysisVcfCallContext context =
                response.getAnalysisVcfCallContext();
        String json = new ObjectMapper().writeValueAsString(response);
        String legacyJson = new ObjectMapper().writeValueAsString(
                assembler.assembleLegacy("analysis-1", variant)
        );

        assertEquals("candidate-ranking-explanation-v2", response.getSchemaVersion());
        assertEquals("analysis-vcf-call-v1", context.getSchemaVersion());
        assertEquals("MATCHED", context.getMatchStatus());
        assertEquals("HET_TARGET", context.getTargetAlleleCall());
        assertEquals("REF|TARGET_ALT", context.getCandidateRelativeGenotype());
        assertEquals(18, context.getReferenceDepth());
        assertEquals(12, context.getTargetAlleleDepth());
        assertEquals("FILTERED", context.getFilterEvaluation());
        assertEquals("PHASE_SET", context.getPhasingStatus());
        assertEquals(Collections.singletonList("DP"), context.getMissingFields());
        assertEquals(Collections.singletonList("GQ"), context.getInvalidFields());
        assertTrue(json.contains("analysisVcfCallContext"));
        assertFalse(json.contains("sampleId"));
        assertFalse(json.contains("rawGenotype"));
        assertFalse(json.contains("originalRecord"));
        assertFalse(json.contains("rawPhaseSet"));
        assertFalse(json.toLowerCase().contains("path"));
        assertTrue(legacyJson.contains("candidate-ranking-explanation-v1"));
        assertFalse(legacyJson.contains("analysisVcfCallContext"));
        assertNull(assembler.assembleLegacy("analysis-1", variant).getAnalysisVcfCallContext());
    }

    private VariantInterpretation variant() {
        return new VariantInterpretation("synthetic-variant", null, Collections.emptyList(), Collections.emptyList());
    }

    private ExomiserCandidateFeatures exomiserFeatures() {
        return new ExomiserCandidateFeatures(
                null,
                "15.1.0",
                "GENE1",
                "123",
                "AR",
                "AUTOSOMAL_RECESSIVE",
                4,
                null,
                "Alice123",
                0.81d,
                0.93d,
                0.82d,
                0.74d,
                "missense_variant",
                "NM_000001.1:c.1A>G",
                true,
                "result.variants.tsv",
                "result.genes.tsv",
                2,
                1,
                "EXACT_RANK_GENE_MOI",
                "EXOMISER_VARIANT_SCORE",
                Collections.singletonList("MAX_FREQ"),
                Arrays.asList("P-VALUE", "MOI:SOURCE_CONFLICT", "patient:secret"),
                Collections.singletonMap("geneSymbol", "VARIANT_ROW")
        );
    }
}
