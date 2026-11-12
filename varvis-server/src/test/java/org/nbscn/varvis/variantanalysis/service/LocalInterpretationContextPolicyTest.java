package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalInterpretationContextPolicyTest {
    static AnalysisVcfCall call(String gt, AnalysisVcfCall.TargetAlleleCall call) {
        return AnalysisVcfCall.matched(call, gt, 2, null, null, null, null, null,
                AnalysisVcfCall.FilterEvaluation.PASS,
                call == AnalysisVcfCall.TargetAlleleCall.NO_CALL ? AnalysisVcfCall.PhasingStatus.UNKNOWN
                    : gt.contains("|") ? AnalysisVcfCall.PhasingStatus.RECORD_ONLY : AnalysisVcfCall.PhasingStatus.UNPHASED,
                null, 1, GenomeAssembly.GRCh38, List.of(), List.of());
    }
    private VariantInterpretation candidate(String chromosome, String gt, AnalysisVcfCall.TargetAlleleCall call) {
        var c = new AcmgRecommendationsTest().candidate("HBB");
        when(c.getObservation()).thenReturn(new VariantQcObservation(new NormalizedVariantKey("GRCh38", chromosome, 2, "T", "A"),
                "synthetic", null, null, null, null, null, "PASS", "SNV", null));
        when(c.getAnalysisVcfCall()).thenReturn(call(gt, call));
        return c;
    }
    @ParameterizedTest @CsvSource({"REF/TARGET_ALT,HET_TARGET", "TARGET_ALT|REF,HET_TARGET", "TARGET_ALT|TARGET_ALT,HOM_TARGET",
            "OTHER_ALT/TARGET_ALT,TARGET_WITH_OTHER_ALT"})
    void usesPublishedTargetCallInsteadOfEmptyRankingObservation(String gt, AnalysisVcfCall.TargetAlleleCall call) {
        var a = LocalInterpretationContextPolicy.assess(candidate("11", gt, call), "AD");
        assertNull(a.reasonCode()); assertTrue(a.requiresExplicitSelection()); assertFalse(a.diagnosisClaimed());
        assertEquals("AUTOSOMAL_RECESSIVE", a.rankingMoi()); assertEquals("AUTOSOMAL_DOMINANT", a.interpretationMoi());
    }
    @Test void heterozygousArAlleleEvaluationDoesNotAssertSecondAffectedAllele() {
        var c = candidate("11", "REF|TARGET_ALT", AnalysisVcfCall.TargetAlleleCall.HET_TARGET);
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        var a = LocalInterpretationContextPolicy.assess(c, "AR");
        assertNull(a.reasonCode()); assertFalse(a.diagnosisClaimed());
    }
    @ParameterizedTest @CsvSource({"REF/REF,HOM_REF,INTERPRETATION_TARGET_ALT_NOT_PRESENT",
            "REF/OTHER_ALT,OTHER_ALT_ONLY,INTERPRETATION_TARGET_ALT_NOT_PRESENT",
            "./TARGET_ALT,PARTIAL_TARGET,INTERPRETATION_CALLED_DIPLOID_GT_REQUIRED",
            "TARGET_ALT,HEMI_TARGET,INTERPRETATION_CALLED_DIPLOID_GT_REQUIRED",
            "TARGET_ALT/TARGET_ALT/TARGET_ALT,HOM_TARGET,INTERPRETATION_CALLED_DIPLOID_GT_REQUIRED",
            "./.,NO_CALL,INTERPRETATION_CALLED_DIPLOID_GT_REQUIRED"})
    void rejectsInvalidScopeOrAbsentTarget(String gt, AnalysisVcfCall.TargetAlleleCall call, String reason) {
        assertEquals(reason, LocalInterpretationContextPolicy.assess(candidate("11", gt, call), "AD").reasonCode());
    }
    @ParameterizedTest @ValueSource(strings = {"X", "Y", "MT", "23"})
    void nonAutosomesRemainOutsideNewBridge(String chromosome) {
        assertEquals("INTERPRETATION_NATIVE_AUTOSOMAL_SNV_REQUIRED", LocalInterpretationContextPolicy.assess(
                candidate(chromosome, "REF/TARGET_ALT", AnalysisVcfCall.TargetAlleleCall.HET_TARGET), "AD").reasonCode());
    }
    @Test void noAmbiguousOrMissingWitnessAndNoObservationFallback() {
        var c = candidate("11", "REF/TARGET_ALT", AnalysisVcfCall.TargetAlleleCall.HET_TARGET);
        for (var call : java.util.Arrays.asList(null, AnalysisVcfCall.ambiguous(GenomeAssembly.GRCh38, 2), AnalysisVcfCall.notFound(GenomeAssembly.GRCh38))) {
            when(c.getAnalysisVcfCall()).thenReturn(call);
            assertEquals("INTERPRETATION_ANALYSIS_VCF_CALL_REQUIRED", LocalInterpretationContextPolicy.assess(c, "AD").reasonCode());
        }
    }
    @Test void rankingSourcesMustRemainConsistent() {
        var c = candidate("11", "REF/TARGET_ALT", AnalysisVcfCall.TargetAlleleCall.HET_TARGET);
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        assertEquals("CANDIDATE_MOI_CONFLICT", LocalInterpretationContextPolicy.assess(c, "AD").reasonCode());
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn(null);
        assertEquals("CANDIDATE_MOI_REQUIRED", LocalInterpretationContextPolicy.assess(c, "AD").reasonCode());
    }
    @Test void sameContextRetainsExistingBehavior() {
        var c = candidate("11", "./.", AnalysisVcfCall.TargetAlleleCall.NO_CALL);
        var a = LocalInterpretationContextPolicy.assess(c, "AR");
        assertNull(a.reasonCode()); assertFalse(a.requiresExplicitSelection());
    }
}
