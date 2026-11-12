package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectedCriterionRoutingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SelectedCriterionRouting routing = new SelectedCriterionRouting(json);
    private ObjectNode context(String moi) {
        var c = json.createObjectNode().put("moi", moi).put("contextId", "context-fixture");
        c.putObject("gene").put("symbol", "TEST");
        c.putObject("transcript").put("accession", "ENST00000000001").put("version", 1).put("annotationSourceVersion", "116.0");
        return c;
    }
    private TranscriptConsequence annotation(String effect) {
        var tx = mock(TranscriptConsequence.class);
        when(tx.getConsequence()).thenReturn(effect); when(tx.getVersionedTranscriptId()).thenReturn("ENST00000000001.1");
        when(tx.getGeneSymbol()).thenReturn("TEST"); when(tx.getSource()).thenReturn("VEP");
        when(tx.getStatus()).thenReturn("COMPLETED"); when(tx.getVersion()).thenReturn("116.0");
        return tx;
    }
    @ParameterizedTest @ValueSource(strings={"missense_variant", "synonymous_variant", "missense_variant&NMD_transcript_variant"})
    void exactNonLengthAnnotationSkipsLengthResourcesButDoesNotExcludeSpliceOrLofKnowledge(String effect) {
        var plan = routing.decide(context("AUTOSOMAL_DOMINANT"), annotation(effect), "MISSING");
        assertEquals(18, plan.path("criteria").size());
        for (String code : List.of("PM4", "BP3", "PM3")) assertTrue(SelectedCriterionRouting.skip(plan, code));
        for (String code : List.of("BA1", "BS1", "PM2", "PVS1", "PS1", "PP3", "BP4", "PS2", "PM6", "PP1"))
            assertFalse(SelectedCriterionRouting.skip(plan, code), code);
    }
    @ParameterizedTest @ValueSource(strings={"", "new_consequence", "missense_variant&new_consequence", "missense_variant&synonymous_variant", "NMD_transcript_variant"})
    void UnknownOrConflictingConsequenceCannotBecomeNonApplicability(String effect) {
        var plan = routing.decide(context("UNKNOWN"), annotation(effect), "MISSING");
        for (String code : SelectedCriterionRouting.CODES) assertFalse(SelectedCriterionRouting.skip(plan, code), code);
    }
    @Test void unknownAnnotationDoesNotOverruleKnownDiseaseButIsNotNoRecord() {
        var plan = routing.decide(context("AUTOSOMAL_DOMINANT"), null, "QUERY_ANNOTATION_TIMEOUT");
        assertTrue(SelectedCriterionRouting.skip(plan, "PM3"));
        assertEquals("UNRESOLVED", plan.at("/criteria/PM4/status").asText());
        assertEquals("QUERY_ANNOTATION_TIMEOUT", plan.at("/criteria/PM4/reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"AUTOSOMAL_DOMINANT", "X_LINKED_DOMINANT", "MITOCHONDRIAL"})
    void nonRecessivePm3ExclusionBindsItsContextBeforeAnyAnnotationReturn(String moi) {
        for (boolean annotated : List.of(false, true)) {
            var context = context(moi);
            var plan = routing.decide(context, annotated ? annotation("missense_variant") : null, "ANNOTATION_UNAVAILABLE");
            var pm3 = plan.at("/criteria/PM3");
            assertTrue(SelectedCriterionRouting.skip(plan, "PM3"));
            assertEquals("NOT_APPLICABLE", pm3.path("status").asText());
            assertEquals("ROUTING_PM3_NON_RECESSIVE_CONTEXT", pm3.path("reasonCode").asText());
            assertEquals(context.path("contextId"), pm3.path("contextId"));
            assertEquals(context.path("moi"), pm3.path("moi"));
            if (!annotated) assertFalse(pm3.has("transcript"), "Inheritance exclusion needs no invented annotation");
        }
    }
    @ParameterizedTest @ValueSource(strings={"AUTOSOMAL_RECESSIVE", "UNKNOWN"})
    void recessiveOrUnknownInheritanceCannotAcquirePm3Exclusion(String moi) {
        for (boolean annotated : List.of(false, true)) {
            var plan = routing.decide(context(moi), annotated ? annotation("missense_variant") : null, "ANNOTATION_UNAVAILABLE");
            assertFalse(SelectedCriterionRouting.skip(plan, "PM3"));
            assertNotEquals("NOT_APPLICABLE", plan.at("/criteria/PM3/status").asText());
        }
    }
    @ParameterizedTest @ValueSource(strings={"inframe_deletion", "stop_lost", "frameshift_variant", "splice_donor_variant", "missense_variant&splice_region_variant"})
    void complexOrSpliceAndLengthBranchesAreNotLost(String effect) {
        var plan = routing.decide(context("AUTOSOMAL_RECESSIVE"), annotation(effect), "MISSING");
        assertFalse(SelectedCriterionRouting.skip(plan, "PM3"));
        assertFalse(SelectedCriterionRouting.skip(plan, "PM4"));
        assertFalse(SelectedCriterionRouting.skip(plan, "BP3"));
        assertFalse(SelectedCriterionRouting.skip(plan, "PS1"));
        assertFalse(SelectedCriterionRouting.skip(plan, "PVS1"));
        if (!effect.contains("missense")) {
            assertEquals("REQUIRES_RULE_CHECK", plan.at("/criteria/BP1/status").asText());
            assertFalse(SelectedCriterionRouting.skip(plan, "BP1"));
            assertEquals("SOFTWARE_UNSUPPORTED", plan.at("/criteria/PM1/status").asText());
        }
    }
    @ParameterizedTest @ValueSource(strings={"stop_gained", "frameshift_variant", "stop_gained&NMD_transcript_variant"})
    void truncatingBp1NeedsItsPublishedSubtypeInsteadOfAMissenseExclusion(String effect) {
        var plan = routing.decide(context("AUTOSOMAL_DOMINANT"), annotation(effect), "MISSING");
        assertFalse(plan.path("scientificApproval").asBoolean(true));
        assertFalse(SelectedCriterionRouting.skip(plan, "BP1"));
        assertEquals("REQUIRES_RULE_CHECK", plan.at("/criteria/BP1/status").asText());
        assertEquals("ROUTING_ORIGINAL_RULE_CHECK_REQUIRED", plan.at("/criteria/BP1/reasonCode").asText());
        for (String code : List.of("PP2", "PM5")) {
            assertTrue(SelectedCriterionRouting.skip(plan, code));
            assertEquals("ROUTING_MISSENSE_REQUIRED", plan.at("/criteria/" + code + "/reasonCode").asText());
        }
        assertFalse(SelectedCriterionRouting.skip(plan, "PVS1"));
    }
    @Test void missingIdentityIsNotMistakenForNonApplicability() {
        var selected = mock(SelectedEvidencePreparation.class);
        when(selected.context("a", "h")).thenThrow(new IllegalArgumentException("SOURCE_NOT_TRUSTED"));
        var plan = routing.prepare(selected, "a", "h");
        for (String code : SelectedCriterionRouting.CODES) assertFalse(SelectedCriterionRouting.skip(plan, code));
        assertEquals("SOURCE_NOT_TRUSTED", plan.at("/criteria/PM4/reasonCode").asText());
    }
    @Test void excludedRouteDoesNotOpenFactoryAndSurvivesPublicJsonAndRepeatedRead() throws Exception {
        var analysis = new AnalysisContext("routing-fixture", GenomeAssembly.GRCh38, AnalysisMode.WES);
        var context = context("AUTOSOMAL_DOMINANT");
        var selected = mock(SelectedEvidencePreparation.class);
        when(selected.assembly()).thenReturn(new AssemblyResult("routing-fixture", 1, 1, List.of("a"),
                List.of(new CandidateAssembly("a", List.of(new Attempt("a", "h", null, null, null, context, List.of())))), List.of(), false));
        when(selected.sources()).thenReturn(List.of()); when(selected.provenance()).thenReturn(json.createObjectNode());
        when(selected.formalCandidate("a")).thenReturn(json.createObjectNode().put("candidateId", "a"));
        when(selected.context("a", "h")).thenReturn(context);
        var projection = mock(LegacyContextProjection.class); var variant = mock(VariantInterpretation.class);
        var tx = annotation("missense_variant");
        when(variant.getTranscriptAnnotations()).thenReturn(List.of(tx));
        when(variant.getConsequences()).thenReturn(List.of());
        when(projection.context()).thenReturn(context); when(projection.queryCandidate()).thenReturn(variant);
        when(selected.legacyProjection("a", "h")).thenReturn(projection);
        var calls = new AtomicInteger();
        var runtime = new LocalEvidenceBatchRuntime(json, json.createObjectNode(), null, null, null, null,
                () -> { calls.incrementAndGet(); throw new IllegalArgumentException("DO_NOT_OPEN"); });
        var result = runtime.execute(selected, analysis, "a".repeat(64));
        assertEquals(0, calls.get());
        var publicResult = runtime.publicView(result);
        var pm4 = publicResult.at("/results/0/localBatch/criteria/5");
        assertEquals("ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE", pm4.path("reasonCode").asText(), publicResult.toPrettyString());
        assertEquals("NOT_EVALUATED", pm4.path("status").asText());
        assertEquals("NONE", pm4.path("appliedStrength").asText());
        assertEquals("NOT_APPLICABLE", pm4.at("/details/applicabilityRouting/status").asText());
        assertTrue(pm4.path("reason").asText().contains("跳过"));
        assertEquals("NOT_REQUIRED", pm4.at("/details/materialPreparation/status").asText());
        assertEquals(pm4.path("reasonCode"), pm4.at("/details/materialPreparation/reasonCode"));
        assertArrayEquals(json.writeValueAsBytes(publicResult), json.writeValueAsBytes(json.readTree(json.writeValueAsBytes(publicResult))));
        runtime.execute(selected, analysis, "a".repeat(64)); assertEquals(0, calls.get());
        String output = System.getProperty("routing.output");
        if (output != null) json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of(output).toFile(), publicResult);

        // Positive synthetic dispatch control: an in-frame annotation must still reach the existing evaluator.
        when(tx.getConsequence()).thenReturn("inframe_deletion");
        var pm4Module = mock(GeneralPm4Runtime.class);
        when(pm4Module.identity()).thenReturn(json.createObjectNode()); when(pm4Module.supplyCounts()).thenReturn(json.createObjectNode());
        var positive = json.createObjectNode();
        positive.putObject("evaluation").put("criterionId", "ACMG_AMP_2015:PM4").put("appliedStrength", "MODERATE")
                .putObject("evaluationResult").put("status", "MET").put("reasonCode", "SYNTHETIC_ROUTING_CONTROL_MET");
        when(pm4Module.evaluate(selected, "a", "h")).thenReturn(positive);
        var eligible = new LocalEvidenceBatchRuntime(json, json.createObjectNode(), null, null, null, null, () -> pm4Module);
        var scored = eligible.execute(selected, analysis, "a".repeat(64));
        verify(pm4Module, times(1)).evaluate(selected, "a", "h");
        assertEquals("MET", scored.at("/results/0/localBatch/criteria/5/status").asText());
        assertEquals("MODERATE", scored.at("/results/0/localBatch/criteria/5/appliedStrength").asText());
        assertEquals("EVALUATED", scored.at("/results/0/localBatch/criteria/5/details/materialPreparation/status").asText());
        eligible.execute(selected, analysis, "a".repeat(64)); verify(pm4Module, times(1)).evaluate(selected, "a", "h");

        // A changed annotation version cannot establish non-applicability or silence the original failure.
        when(tx.getConsequence()).thenReturn("missense_variant"); when(tx.getVersion()).thenReturn("OTHER_VERSION");
        var unknown = new LocalEvidenceBatchRuntime(json, json.createObjectNode(), null, null, null, null,
                () -> { calls.incrementAndGet(); throw new IllegalArgumentException("TEST_CONFIGURATION_UNAVAILABLE"); });
        var unresolved = unknown.execute(selected, analysis, "a".repeat(64));
        assertEquals(1, calls.get());
        assertEquals("UNRESOLVED", unresolved.at("/results/0/localBatch/criteria/5/details/applicabilityRouting/status").asText());
        assertEquals("TEST_CONFIGURATION_UNAVAILABLE", unresolved.at("/results/0/localBatch/criteria/5/reasonCode").asText());
    }
}
