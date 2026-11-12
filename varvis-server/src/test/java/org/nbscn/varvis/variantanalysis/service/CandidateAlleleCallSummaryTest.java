package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.nbscn.varvis.variantanalysis.domain.*;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CandidateAlleleCallSummaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final NormalizedVariantKey KEY = new NormalizedVariantKey("GRCh38", "Y", 100, "A", "G");

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of("HOM_REF", "REF/REF", false, true),
                Arguments.of("HEMI_REF", "REF", false, true),
                Arguments.of("OTHER_ALT_ONLY", "OTHER_ALT/OTHER_ALT", false, true),
                Arguments.of("HET_TARGET", "REF/TARGET_ALT", true, true),
                Arguments.of("HOM_TARGET", "TARGET_ALT/TARGET_ALT", true, true),
                Arguments.of("HEMI_TARGET", "TARGET_ALT", true, true),
                Arguments.of("TARGET_WITH_OTHER_ALT", "OTHER_ALT/TARGET_ALT", true, true),
                Arguments.of("PARTIAL_TARGET", "OTHER_ALT/TARGET_ALT/.", true, false),
                Arguments.of("NO_CALL", "./.", null, false),
                Arguments.of("UNSUPPORTED", "REF/.", null, false));
    }

    @ParameterizedTest @MethodSource("matrix")
    void everyTypedCallHasIndependentReportAndCompletenessFacts(String name, String gt, Boolean reported, Boolean complete) throws Exception {
        var type = AnalysisVcfCall.TargetAlleleCall.valueOf(name);
        var input = call(type, gt, type == AnalysisVcfCall.TargetAlleleCall.PARTIAL_TARGET
                        || type == AnalysisVcfCall.TargetAlleleCall.NO_CALL || type == AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED ? List.of("GT") : List.of(),
                type == AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED ? List.of("GT") : List.of(), AnalysisVcfCall.FilterEvaluation.PASS);
        byte[] original = mapper.writeValueAsBytes(input);
        var facts = CandidateAlleleCallSummary.summarize(input, KEY);
        assertEquals(reported, facts.targetAltReported());
        assertEquals(complete, facts.genotypeComplete());
        assertEquals(type, facts.targetAlleleCall());
        assertEquals(gt, facts.candidateRelativeGenotype());
        assertEquals(2, facts.source().targetAlleleIndex());
        assertEquals(4, facts.source().matchedRecordOrdinal());
        assertEquals(input.getMissingFields(), facts.quality().missingFields());
        assertEquals(input.getInvalidFields(), facts.quality().invalidFields());
        assertArrayEquals(original, mapper.writeValueAsBytes(input));
        if (reported == null) assertTrue(mapper.valueToTree(facts).path("targetAltReported").isNull());
        assertTrue(facts.usageLimitations().contains("REPORTED_ALT_IS_NOT_CLINICAL_OR_SCIENTIFIC_ADOPTION"));
    }

    @Test void missingLegacyCallAndUnmatchedSourcesCannotBecomePatientEvidence() {
        assertUnknown(CandidateAlleleCallSummary.summarize((VariantInterpretation) null));
        for (var input : List.of(AnalysisVcfCall.notFound(GenomeAssembly.GRCh38), AnalysisVcfCall.ambiguous(GenomeAssembly.GRCh38, 2))) {
            var facts = CandidateAlleleCallSummary.summarize(input, KEY);
            assertUnknown(facts); assertEquals(input.getMatchStatus(), facts.source().matchStatus());
        }
    }

    @Test void absentIncompleteOrDifferentAssemblyOriginalAlleleKeepsReportUnknown() {
        var input = call(AnalysisVcfCall.TargetAlleleCall.HEMI_TARGET, "TARGET_ALT", List.of(), List.of(), AnalysisVcfCall.FilterEvaluation.PASS);
        for (var allele : new NormalizedVariantKey[]{null, new NormalizedVariantKey("GRCh38",null,1,"A","G"),
                new NormalizedVariantKey("GRCh38","Y",0,"A","G"), new NormalizedVariantKey("GRCh37","Y",100,"A","G"),
                new NormalizedVariantKey("PRIVATE_PATH", "Y",100,"A","G")}) {
            assertUnknown(CandidateAlleleCallSummary.summarize(input, allele));
        }
        assertEquals("ANALYSIS_ALLELE_ASSEMBLY_MISMATCH", CandidateAlleleCallSummary.summarize(input,
                new NormalizedVariantKey("GRCh37","Y",100,"A","G")).reasonCode());
    }

    @Test void qualityFlagsDoNotReplaceSourceReportsOrPromoteQualityToPass() {
        var input = AnalysisVcfCall.matched(AnalysisVcfCall.TargetAlleleCall.HET_TARGET, "REF/TARGET_ALT", 2,
                null, 0, null, null, null, AnalysisVcfCall.FilterEvaluation.FILTERED,
                AnalysisVcfCall.PhasingStatus.UNPHASED, null, 4, GenomeAssembly.GRCh38,
                List.of("DP","AD","AB"), List.of("GQ","PS:UNPHASED"));
        var facts = CandidateAlleleCallSummary.summarize(input, KEY);
        assertEquals(true, facts.targetAltReported()); assertEquals(true, facts.genotypeComplete());
        assertEquals(AnalysisVcfCall.FilterEvaluation.FILTERED, facts.quality().filterEvaluation());
        assertEquals(0, facts.quality().genotypeQuality()); assertNull(facts.quality().depth());
        assertEquals(input.getInvalidFields(), facts.quality().invalidFields());
        assertThrows(UnsupportedOperationException.class, () -> facts.quality().missingFields().add("GT"));
        var unassessed = call(AnalysisVcfCall.TargetAlleleCall.HEMI_REF, "REF", List.of(), List.of(), AnalysisVcfCall.FilterEvaluation.UNASSESSED);
        assertEquals(false, CandidateAlleleCallSummary.summarize(unassessed, KEY).targetAltReported());
        assertEquals(AnalysisVcfCall.FilterEvaluation.UNASSESSED, CandidateAlleleCallSummary.summarize(unassessed, KEY).quality().filterEvaluation());
    }

    @Test void incompatibleGtFlagsNeverBecomeAConfidentReport() {
        for (var input : List.of(call(AnalysisVcfCall.TargetAlleleCall.HEMI_TARGET,"TARGET_ALT",List.of(),List.of("GT"),AnalysisVcfCall.FilterEvaluation.PASS),
                call(AnalysisVcfCall.TargetAlleleCall.HEMI_REF,"REF",List.of("GT"),List.of(),AnalysisVcfCall.FilterEvaluation.PASS))) {
            var facts=CandidateAlleleCallSummary.summarize(input,KEY);assertUnknown(facts);
            assertEquals("SOURCE_GENOTYPE_FLAGS_INCOMPATIBLE",facts.reasonCode());
            assertEquals(input.getTargetAlleleCall(),facts.targetAlleleCall());
        }
    }

    @Test void candidateOverloadDoesNotPublishSampleIdsRawRecordsPathsOrClinicalApproval() throws Exception {
        var input=call(AnalysisVcfCall.TargetAlleleCall.HEMI_TARGET,"TARGET_ALT",List.of(),List.of(),AnalysisVcfCall.FilterEvaluation.PASS);
        var obs=new VariantQcObservation(KEY,"PRIVATE_SAMPLE","PRIVATE_GT",10,99,"PRIVATE_AD",0.5,"PRIVATE_FILTER","SNV","PRIVATE_RAW_RECORD");
        var candidate=new VariantInterpretation("private-input-path",obs,List.of(),List.of()).withAnalysisVcfCall(input);
        byte[] original=mapper.writeValueAsBytes(candidate);
        var facts=CandidateAlleleCallSummary.summarize(candidate);String json=mapper.writeValueAsString(facts);
        assertEquals(true,facts.targetAltReported());assertFalse(json.contains("PRIVATE"));assertFalse(json.contains("private-input-path"));
        for (String forbidden:List.of("sampleId","originalRecord","clinicalValidationCompleted","adoptionApproved","score","strength"))
            assertFalse(mapper.valueToTree(facts).has(forbidden));
        assertArrayEquals(original,mapper.writeValueAsBytes(candidate));
        assertUnknown(CandidateAlleleCallSummary.summarize(new VariantInterpretation("legacy",obs,List.of(),List.of())));
        assertEquals(64,requireNonNull(facts.source().originalAnalysisAlleleKeySha256()).length());
        assertNotEquals(facts.source().originalAnalysisAlleleKeySha256(),CandidateAlleleCallSummary.summarize(input,
                new NormalizedVariantKey("GRCh38","Y",100,"A","T")).source().originalAnalysisAlleleKeySha256());
    }

    private static AnalysisVcfCall call(AnalysisVcfCall.TargetAlleleCall type,String gt,List<String> missing,List<String> invalid,
                                        AnalysisVcfCall.FilterEvaluation filter) {
        return AnalysisVcfCall.matched(type,gt,2,20,99,10,10,0.5,filter,
                type==AnalysisVcfCall.TargetAlleleCall.NO_CALL || type==AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED
                        ? AnalysisVcfCall.PhasingStatus.UNKNOWN:AnalysisVcfCall.PhasingStatus.UNPHASED,
                null,4,GenomeAssembly.GRCh38,missing,invalid);
    }

    private static void assertUnknown(CandidateAlleleCallSummary.Summary facts) {
        assertNull(facts.targetAltReported());assertNull(facts.genotypeComplete());
    }
}
