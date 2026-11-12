package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual runtime coding vectors and independent coordinate oracles. These synthetic rules
 * exercise public mechanics, not source qualification or new scientific acceptance. The
 * source compiler/normal selected service fixtures cover source-qualified research use.
 */
class Pvs1CodingEscapePublicFactsTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final TruncatingNmdPvs1RuntimeTest fixture = new TruncatingNmdPvs1RuntimeTest();

    TruncatingNmdPvs1RuntimeTest.Inputs stop(int strand) throws Exception {
        var data = fixture.nonsense(strand == 1 ? 1 : 2, strand, 499);
        reviewed(data, 4);return data;
    }
    void reviewed(TruncatingNmdPvs1RuntimeTest.Inputs data, int exons) {
        var scope = Pvs1NmdEscapeTest.scope(data.policy, exons);
        Pvs1NmdEscapeTest.critical(scope, 160, 190, 10, "STRONG");
        data.policy.put("appliedStrength", "MODERATE");fixture.bind(data);
    }
    ObjectNode summary(ObjectNode result) { return Pvs1SelectedBatch.publicSummary(mapper, result); }
    JsonNode available(ObjectNode result) {
        var summary = summary(result);var coding = summary.path("codingEscapeAssessment");
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result::toPrettyString);
        assertEquals("AVAILABLE", summary.at("/escapeAdoptionProof/status").asText(), summary::toPrettyString);
        assertEquals("pvs1-coding-escape-assessment-v1", coding.path("schemaVersion").asText());
        assertEquals("1.0.0", coding.path("methodVersion").asText());
        assertEquals("AVAILABLE", coding.path("status").asText(), coding::toPrettyString);
        assertEquals("#/escapeAdoptionProof", coding.path("proofPointer").asText());
        assertFalse(coding.path("scientificApprovalCreated").asBoolean());assertFalse(coding.path("rescoringPerformed").asBoolean());
        assertFalse(summary.path("sourceQualifiedResearch").asBoolean());assertFalse(coding.path("sourceQualifiedResearch").asBoolean());
        assertEquals("NOT_AVAILABLE", coding.at("/sourceQualification/status").asText());
        var caps = summary.at("/escapeAdoptionProof/strengthDerivation");
        assertEquals("STRONG", caps.path("sourceRuleStrength").asText());
        assertEquals("MODERATE", caps.path("parentPolicyCap").asText());assertEquals("MODERATE", caps.path("adoptedStrength").asText());
        for (String name : List.of("spliceScopeCap", "outcomeReviewCap", "localMaximumStrength")) assertFalse(caps.has(name));
        assertEquals(result.path("transcriptModelFacts"), coding.path("transcriptModelFacts"));
        return coding;
    }
    void event(JsonNode event, int start, int end, int inserted, String kind) {
        assertEquals("REFERENCE_CDS_0_BASED_HALF_OPEN", event.path("coordinateSystem").asText());
        assertEquals(start, event.path("start0").asInt());assertEquals(end, event.path("end0").asInt());
        assertEquals(end - start, event.path("deletedBaseCount").asInt());assertEquals(inserted, event.path("insertedBaseCount").asInt());
        assertEquals(kind, event.path("kind").asText());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void ordinaryStopUsesReferenceSpanWithoutInventingAFrameshiftMapping(int strand) throws Exception {
        var result = fixture.evaluate(stop(strand));var coding = available(result);var positions = coding.path("positions");
        assertEquals("1.1.0", summary(result).path("methodVersion").asText());
        assertEquals("STOP_GAINED", coding.path("mechanism").asText());assertEquals("REFERENCE_CDS", coding.path("coordinateSystem").asText());
        assertEquals(499, positions.path("cdsPosition").asInt());assertEquals(167, positions.path("proteinPosition").asInt());
        assertEquals("REFERENCE_CDS_1_BASED_INCLUSIVE", positions.at("/ptcSpan/coordinateSystem").asText());
        assertEquals(499, positions.at("/ptcSpan/start").asInt());assertEquals(501, positions.at("/ptcSpan/end").asInt());
        event(positions.path("referenceEvent"), 498, 499, 1, "SUBSTITUTION");
        event(positions.path("hgvscEvent"), 498, 499, 1, "SUBSTITUTION");
        assertFalse(result.path("resolvedInput").has("ptcReferenceMiddle"));assertFalse(positions.has("referencePtc"));
        assertEquals("critical-160", summary(result).at("/escapeAdoptionProof/selectedRule/ruleId").asText());
        assertEquals(24, summary(result).at("/escapeAdoptionProof/ruleCheck/affectedResiduesInRegion").asInt());
    }

    @ParameterizedTest @CsvSource({"1,true", "-1,true", "1,false", "-1,false"})
    void simpleFrameshiftKeepsNormalizedAndHgvsBoundariesDistinct(int strand, boolean insertion) throws Exception {
        var data = fixture.frameshift(insertion, strand, 112);reviewed(data, 10);
        var result = fixture.evaluate(data);var coding = available(result);var positions = coding.path("positions");
        assertEquals("1.1.0", summary(result).path("methodVersion").asText());
        assertEquals("FRAMESHIFT", coding.path("mechanism").asText());assertEquals("ALTERED_CDS", coding.path("coordinateSystem").asText());
        int normalStart = !insertion && strand == -1 ? 31 : 30;
        event(positions.path("referenceEvent"), normalStart, normalStart + (insertion ? 0 : 1), insertion ? 1 : 0, insertion ? "INSERTION" : "DELETION");
        event(positions.path("hgvscEvent"), 30, insertion ? 30 : 31, insertion ? 1 : 0, insertion ? "INSERTION" : "DELETION");
        assertTrue(positions.at("/hgvscEvent/sequenceEquivalent").asBoolean());
        assertEquals(normalStart + 1, positions.path("cdsPosition").asInt());assertEquals(11, positions.path("firstChangedProteinPosition").asInt());
        assertEquals(insertion ? 64 : 61, positions.at("/ptcSpan/start").asInt());assertEquals(insertion ? 66 : 63, positions.at("/ptcSpan/end").asInt());
        assertEquals("ALTERED_CDS_1_BASED_INCLUSIVE", positions.at("/ptcSpan/coordinateSystem").asText());
        assertEquals(mapper.valueToTree(insertion ? List.of(63, 64, 65) : List.of(62, 63, 64)), positions.at("/referencePtc/positions"));
        assertEquals("REFERENCE_BASES", positions.at("/referencePtc/status").asText());
    }

    @Test void supportedComplexCodingUsesItsActualTypedDelinsWithoutSpliceCaps() throws Exception {
        int strand = 1;
        var data = fixture.frameshift(false, strand, 100);
        var annotation = fixture.annotation(data, "frameshift_variant", "31_32delinsG", "Pro11AspfsTer11", "3/10", "11", "P/X", "CCA/GA");
        fixture.candidate(data, fixture.genomic(data.model, strand == 1 ? 31 : 32), strand == 1 ? "CC" : "GG", strand == 1 ? "G" : "C", annotation);
        TruncatingNmdPolicyComplexScopeTest.enable(data.policy, 50);reviewed(data, 10);
        var result = new TruncatingNmdPvs1Runtime(mapper, fixture.resourceProfile(), data.model, true).evaluate(data.context, data.candidate, data.policy, data.selection);
        var coding = available(result);
        event(coding.at("/positions/referenceEvent"), 30, 32, 1, "DELINS");event(coding.at("/positions/hgvscEvent"), 30, 32, 1, "DELINS");
        assertEquals(mapper.valueToTree(List.of(62, 63, 64)), coding.at("/positions/referencePtc/positions"));
        assertEquals(61, coding.at("/positions/ptcSpan/start").asInt());assertEquals(63, coding.at("/positions/ptcSpan/end").asInt());
        assertEquals("SOURCE_BOUND_CODING_EDITS", summary(result).path("method").asText());
    }

    /** Rehash an adversarial private result copy, never create an approval or run the scorer. */
    void reseal(ObjectNode result) {
        var input = result.path("resolvedInput");var evaluation = result.withObject("evaluation");var trace = evaluation.withObject("deterministicTrace");
        for (var row : trace.path("parameters")) {
            String name = row.path("name").asText();
            if ("resolvedInputSha256".equals(name)) ((ObjectNode) row).put("value", adapter.canonicalSha256(input));
            else if (input.has(name)) ((ObjectNode) row).put("value", input.path(name).isContainerNode() ? input.path(name).toString() : input.path(name).asText());
        }
        trace.remove("traceSha256");trace.put("traceSha256", adapter.canonicalSha256(trace));
        var identity = evaluation.deepCopy();identity.remove(List.of("evaluationId", "conflict", "review"));
        evaluation.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(identity));
    }
    void unavailable(ObjectNode result) {
        var coding = summary(result).path("codingEscapeAssessment");
        assertEquals("UNAVAILABLE", coding.path("status").asText(), coding::toPrettyString);
        assertTrue(coding.path("reasonCode").asText().startsWith("PVS1_"));assertFalse(coding.has("positions"));
    }

    @ParameterizedTest @ValueSource(strings = {"normalBoundary", "hgvsBoundary", "mappingMiddle", "missingMappingMiddle"})
    void resealedFrameshiftCannotInventOrOmitTypedPositions(String mutation) throws Exception {
        var data = fixture.frameshift(false, 1, 112);reviewed(data, 10);var result = fixture.evaluate(data);
        var input = result.withObject("resolvedInput");
        switch (mutation) {
            case "normalBoundary" -> input.put("referenceEditStart0", 31).put("referenceEditEnd0", 32);
            case "hgvsBoundary" -> input.put("hgvscReferenceEditStart0", 31).put("hgvscReferenceEditEnd0", 32);
            case "mappingMiddle" -> input.put("ptcReferenceMiddle", 62);
            case "missingMappingMiddle" -> input.remove("ptcReferenceMiddle");
            default -> throw new AssertionError(mutation);
        }
        reseal(result);
        // These old proof mechanics remain intact; the optional coding contract rejects the inconsistent facts.
        assertEquals("AVAILABLE", summary(result).at("/escapeAdoptionProof/status").asText());unavailable(result);
    }

    @ParameterizedTest @ValueSource(strings = {"missingHgvs", "wrongHgvs", "wrongAnnotationSha", "wrongCodon"})
    void stopRequiresActualBoundAnnotationAndMatchingReferenceCodon(String mutation) throws Exception {
        var result = fixture.evaluate(stop(1));var input = result.withObject("resolvedInput");var annotation = result.withObject("storedAnnotation");
        switch (mutation) {
            case "missingHgvs" -> annotation.remove("hgvsCoding");
            case "wrongHgvs" -> annotation.put("hgvsCoding", "NM_000001.1:c.500G>A");
            case "wrongAnnotationSha" -> input.put("storedAnnotationSha256", "f".repeat(64));
            case "wrongCodon" -> input.put("altCodon", "TAA");
            default -> throw new AssertionError(mutation);
        }
        if (!"wrongAnnotationSha".equals(mutation)) input.put("storedAnnotationSha256", adapter.canonicalSha256(annotation));
        reseal(result);assertEquals("AVAILABLE", summary(result).at("/escapeAdoptionProof/status").asText());unavailable(result);
    }

    @Test void readyLabelAndQualificationObjectDoNotConferSourceQualification() throws Exception {
        var result = fixture.evaluate(stop(1));var knowledge = result.putObject("knowledgeAssessment").put("status", "READY");
        knowledge.putObject("adoption").putObject("qualification");
        var summary = summary(result);assertFalse(summary.path("sourceQualifiedResearch").asBoolean());
        assertFalse(summary.at("/codingEscapeAssessment/sourceQualifiedResearch").asBoolean());
        assertEquals("INVALID", summary.at("/codingEscapeAssessment/sourceQualification/status").asText());unavailable(result);
    }
    @Test void wrongOrMissingActualBindingsCannotProduceAvailableCodingFactsOrRepairLegacyMaterial() throws Exception {
        for (String mutation : List.of("source", "model", "missingModelFacts")) {
            var result = fixture.evaluate(stop(1));
            if ("source".equals(mutation)) ((ObjectNode) result.at("/policy/scientificSources/0")).put("sha256", "f".repeat(64));
            else if ("model".equals(mutation)) result.withObject("transcriptModelFacts").put("modelSha256", "f".repeat(64));
            else result.remove("transcriptModelFacts");
            unavailable(result);assertFalse(summary(result).path("sourceQualifiedResearch").asBoolean());
            assertEquals("1.1.0", summary(result).path("methodVersion").asText());
            if ("missingModelFacts".equals(mutation)) assertFalse(result.has("transcriptModelFacts"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"FREQUENT", "UNKNOWN"})
    void negativeOrMissingScientificFactsNeverBecomeAdoptedCodingProof(String fact) throws Exception {
        var data = stop(1);var exon = (ObjectNode) data.policy.at("/escapeScope/exonAssessments/3");
        if ("FREQUENT".equals(fact)) exon.put("populationLof", fact);else exon.put("biologicalRelevance", fact);
        fixture.bind(data);var result = fixture.evaluate(data);var summary = summary(result);
        assertNotEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("NOT_ADOPTED", summary.at("/escapeAdoptionProof/status").asText());
        assertEquals("NOT_ADOPTED", summary.at("/codingEscapeAssessment/status").asText());
        assertFalse(summary.path("codingEscapeAssessment").has("positions"));
    }
    @Test void projectionIsRepeatableImmutableAndContainsNoPrivateSequenceOrOriginalText() throws Exception {
        var result = fixture.evaluate(stop(-1));String secret = "PRIVATE_TEST_ORIGINAL_MUST_NEVER_BE_PUBLIC_8c32";
        result.withObject("storedAnnotation").put("privateOriginalText", secret);
        result.withObject("resolvedInput").put("privateOriginalText", secret).put("storedAnnotationSha256", adapter.canonicalSha256(result.path("storedAnnotation")));
        reseal(result);var before = result.deepCopy();var first = summary(result);
        assertEquals("AVAILABLE", first.at("/codingEscapeAssessment/status").asText());assertEquals(first, summary(result));assertEquals(before, result);
        String json = first.toString();assertFalse(json.contains(secret));
        for (String privateName : List.of("storedAnnotation", "alteredCdsSequence", "cdsSequence", "alteredProteinThroughTermination", "refCodon", "altCodon", "insertedSequence"))
            assertFalse(json.contains('"' + privateName + '"'), privateName);
    }
}
