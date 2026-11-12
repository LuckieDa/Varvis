package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Public projection only: no clinical policy or source qualification is created by this test. */
class Pvs1DeliveryProjectionTest {
    final Pvs1ModelExceptionsTest fixture = new Pvs1ModelExceptionsTest();

    void artifact(String name, ObjectNode result) throws Exception {
        String target = System.getProperty("varvis.pvs1.delivery.output");
        if (target == null) return;
        Path root = Path.of(target); Files.createDirectories(root);
        Files.write(root.resolve(name + ".json"), fixture.helper.mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(result), StandardOpenOption.CREATE_NEW);
    }

    @ParameterizedTest @ValueSource(strings={"SINGLE_STOP", "SINGLE_FRAME", "UTR_NMD", "SINGLE_GAP", "UTR_GAP"})
    void specialModelsHavePublicStructureWithoutFullSequenceOrPrivateSources(String kind) throws Exception {
        boolean single = kind.startsWith("SINGLE");
        var d = fixture.data(kind.equals("SINGLE_FRAME") ? "INSERTION" : "STOP", 1, single, 621);
        if (!kind.endsWith("GAP")) {
            if (single) Pvs1NmdEscapeTest.critical(Pvs1NmdEscapeTest.scope(d.policy, 1), 160, 199, 1, "STRONG");
            else fixture.utrScope(d.policy);
        }
        var result = fixture.helper.run(d);
        result.putObject("knowledgeAssessment").put("status", "READY").put("reasonCode", "SYNTHETIC_TEST_KNOWLEDGE_BOUND");
        String original = result.toString();
        var facts = Pvs1SelectedBatch.publicSummary(fixture.helper.mapper, result);
        assertEquals(original, result.toString());
        assertEquals("1.1.0", facts.path("methodVersion").asText());
        assertEquals(single ? "SINGLE_EXON" : "LAST_JUNCTION_IN_3_PRIME_UTR", facts.at("/transcriptTopology/kind").asText());
        if (single) assertTrue(facts.path("lastJunctionCdsPosition").isNull());
        assertFalse(facts.has("policy")); assertFalse(facts.has("evidence"));
        assertFalse(facts.toString().contains("cdsSequence")); assertFalse(facts.has("storedAnnotation"));
        var criterion = fixture.helper.mapper.createObjectNode().put("criterionId", TruncatingNmdPvs1Runtime.CRITERION)
                .put("status", result.at("/evaluation/evaluationResult/status").asText())
                .put("appliedStrength", result.at("/evaluation/appliedStrength").asText());
        criterion.putObject("details").set("pvs1Facts", facts);
        artifact(kind, criterion);
    }

    @Test void sourceQualifiedSupplyProjectsOnlyAllowlistedFacts() throws Exception {
        var result = fixture.helper.mapper.createObjectNode();
        result.putObject("policy").put("appliedStrength", "STRONG");
        result.putObject("knowledgeAssessment").putObject("adoption").putObject("qualification").put("secretPath", "not-public");
        result.putObject("transcriptMaterialSupply").put("status", "READY").put("reasonCode", "PVS1_FULL_TRANSCRIPT_SEQUENCE_AND_STRUCTURE_VERIFIED")
                .put("bindingMethod", "EXACT_FULL_TRANSCRIPT_EQUIVALENCE").put("verifiedExonCount", 4).put("policyModelUnchanged", true)
                .put("privatePath", "not-public").put("cdsSequence", "PRIVATE_SEQUENCE");
        var facts = Pvs1SelectedBatch.publicSummary(fixture.helper.mapper, result);
        assertEquals("STRONG", facts.path("policyStrengthCap").asText());
        assertTrue(facts.path("sourceQualifiedResearch").asBoolean());
        assertEquals(4, facts.at("/materialSupply/verifiedExonCount").asInt());
        assertEquals(5, facts.path("materialSupply").size());
        assertFalse(facts.toString().contains("not-public")); assertFalse(facts.toString().contains("PRIVATE_SEQUENCE"));
    }

    @Test void missingInputIsNotClaimedAsVerified() {
        var facts = Pvs1SelectedBatch.publicSummary(fixture.helper.mapper, fixture.helper.mapper.createObjectNode());
        assertEquals("UNAVAILABLE", facts.path("policyStrengthCap").asText());
        assertFalse(facts.path("sourceQualifiedResearch").asBoolean());
        assertFalse(facts.has("materialSupply")); assertFalse(facts.has("transcriptTopology"));
    }

    @Test void startLossPublicSummaryKeepsOnlyBoundedFactsAndNoSequence() {
        var result = fixture.helper.mapper.createObjectNode();
        result.putObject("knowledgeAssessment").put("status", "UNAVAILABLE").put("reasonCode", "START_LOSS_MATERIALS_NOT_READY");
        result.putObject("policy").put("schemaVersion", "pvs1-start-loss-policy-v1");
        result.putObject("startLossFacts").put("schemaVersion", "pvs1-start-loss-sequence-facts-v1")
                .put("methodVersion", "1.0.0").put("status", "AVAILABLE").put("selectedTranscript", "NM_000001.1")
                .put("strand", -1).put("cdsPosition", 1).put("referenceCodon", "ATG").put("alternateCodon", "GTG")
                .put("candidateRestartCdsPosition", 10).put("candidateRestartInterpretation", "POTENTIAL_ATG_NOT_OBSERVED_TRANSLATION")
                .put("transcriptReviewStatus", "NOT_PREPARED").put("upstreamReferenceReviewStatus", "NOT_PREPARED")
                .put("reviewReasonCode", "PVS1_START_LOSS_FUNCTIONAL_TRANSCRIPT_AND_REFERENCE_REVIEW_REQUIRED")
                .put("clinicalScoringPerformed", false).put("mutantCds", "MUST_NOT_LEAK");
        var facts = Pvs1SelectedBatch.publicSummary(fixture.helper.mapper, result);
        assertEquals("1.2.0", facts.path("methodVersion").asText());
        assertEquals("AVAILABLE", facts.at("/startLossAssessment/status").asText());
        assertFalse(facts.toString().contains("MUST_NOT_LEAK"));
        assertFalse(facts.has("policy"));
    }
}
