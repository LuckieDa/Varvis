package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossDecisionTest {
    private Pvs1StartLossDecision.Facts base() {
        return new Pvs1StartLossDecision.Facts("AVAILABLE", "PERMITS", "ESTABLISHED_LOF",
                "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET", true, 10, "COMPLETE", true, true);
    }
    @Test void qualifiedUpstreamPathogenicReferenceIsModerate() {
        var r = Pvs1StartLossDecision.evaluate(base());
        assertEquals(Pvs1StartLossDecision.Outcome.MET, r.outcome()); assertEquals("MODERATE", r.strength());
    }
    @Test void completeNoReferenceIsSupporting() {
        var b = base(); b = new Pvs1StartLossDecision.Facts(b.sequence(), b.policy(), b.mechanism(), b.transcriptReview(), b.transcriptSetComplete(), b.restartCdsPosition(), b.upstreamReview(), b.upstreamSearchComplete(), false);
        var r = Pvs1StartLossDecision.evaluate(b); assertEquals(Pvs1StartLossDecision.Outcome.MET, r.outcome()); assertEquals("SUPPORTING", r.strength());
    }
    @Test void incompleteFactsNeverBecomeNegative() {
        var r = Pvs1StartLossDecision.evaluate(new Pvs1StartLossDecision.Facts("AVAILABLE", "PERMITS", "ESTABLISHED_LOF", "UNRESOLVED", false, 10, "INCOMPLETE", false, false));
        assertEquals(Pvs1StartLossDecision.Outcome.INSUFFICIENT_INFORMATION, r.outcome()); assertEquals("NONE", r.strength());
    }
    @Test void specialistAndMechanismGatesAreDistinct() {
        var b = base();
        assertEquals(Pvs1StartLossDecision.Outcome.NOT_APPLICABLE, Pvs1StartLossDecision.evaluate(new Pvs1StartLossDecision.Facts(b.sequence(), "PROHIBITED", b.mechanism(), b.transcriptReview(), true, 10, "COMPLETE", true, true)).outcome());
        assertEquals(Pvs1StartLossDecision.Outcome.RULE_NOT_IMPLEMENTED, Pvs1StartLossDecision.evaluate(new Pvs1StartLossDecision.Facts(b.sequence(), "UNSUPPORTED", b.mechanism(), b.transcriptReview(), true, 10, "COMPLETE", true, true)).outcome());
        assertEquals(Pvs1StartLossDecision.Outcome.NOT_APPLICABLE, Pvs1StartLossDecision.evaluate(new Pvs1StartLossDecision.Facts(b.sequence(), b.policy(), "NOT_LOF", b.transcriptReview(), true, 10, "COMPLETE", true, true)).outcome());
    }
    @Test void sourceTimeoutIsNotSupporting() {
        var b = base();
        var r = Pvs1StartLossDecision.evaluate(new Pvs1StartLossDecision.Facts(b.sequence(), b.policy(), b.mechanism(), b.transcriptReview(), true, 10, "UNAVAILABLE", false, false));
        assertEquals(Pvs1StartLossDecision.Outcome.SOURCE_UNAVAILABLE, r.outcome()); assertEquals("NONE", r.strength());
    }

    @Test void irrelevantUpstreamConflictDoesNotOverrideEstablishedAlternativeOrPolicyExclusion() {
        var alternative = new Pvs1StartLossDecision.Facts("AVAILABLE", "PERMITS", "ESTABLISHED_LOF",
                "FOUND_FUNCTIONAL_ALTERNATIVE", false, null, "CONFLICT", false, false);
        assertEquals(Pvs1StartLossDecision.Outcome.NOT_APPLICABLE, Pvs1StartLossDecision.evaluate(alternative).outcome());
        var excluded = new Pvs1StartLossDecision.Facts("AVAILABLE", "PROHIBITED", "UNRESOLVED",
                "UNRESOLVED", false, null, "CONFLICT", false, false);
        assertEquals(Pvs1StartLossDecision.Outcome.NOT_APPLICABLE, Pvs1StartLossDecision.evaluate(excluded).outcome());
        var conflictingTranscripts = new Pvs1StartLossDecision.Facts("AVAILABLE", "PERMITS", "ESTABLISHED_LOF",
                "CONFLICT", false, 10, "COMPLETE", true, true);
        assertEquals(Pvs1StartLossDecision.Outcome.CONFLICT, Pvs1StartLossDecision.evaluate(conflictingTranscripts).outcome());
    }

    @Test void allAuthoredIndependentExpectationOutcomesMatchTheDecisionTable() throws Exception {
        var mapper = new ObjectMapper(); JsonNode document;
        try (var stream = getClass().getResourceAsStream("/acmg/pvs1-start-loss-expectations-v1.json")) {
            assertNotNull(stream); document = mapper.readTree(stream);
        }
        assertEquals("EXPECTED_BEFORE_IMPLEMENTATION_NOT_EXECUTED", document.path("status").asText());
        for (JsonNode test : document.path("cases")) {
            var facts = expectation(test.path("id").asText());
            var actual = Pvs1StartLossDecision.evaluate(facts);
            String expected = test.at("/expected/display").asText();
            assertEquals(expected, switch (actual.outcome()) {
                case MET -> "MET"; case NOT_APPLICABLE -> "NOT_APPLICABLE";
                case INSUFFICIENT_INFORMATION -> "INSUFFICIENT_INFORMATION"; case SOURCE_UNAVAILABLE -> "SOURCE_UNAVAILABLE";
                case CONFLICT -> "CONFLICT"; case RULE_NOT_IMPLEMENTED -> "RULE_NOT_IMPLEMENTED"; case SOFTWARE_UNSUPPORTED -> "SOFTWARE_UNSUPPORTED";
            }, test.path("id").asText());
            assertEquals(test.at("/expected/strength").asText(), actual.strength(), test.path("id").asText());
        }
        assertEquals(18, document.path("cases").size());
    }

    private Pvs1StartLossDecision.Facts expectation(String id) {
        String sequence = "AVAILABLE", policy = "PERMITS", mechanism = "ESTABLISHED_LOF";
        String transcript = "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET", upstream = "COMPLETE";
        boolean transcriptComplete = true, upstreamComplete = true, reference = true;
        Integer restart = 10;
        switch (id) {
            case "SL01", "SL02", "SL17" -> { }
            case "SL03", "SL08", "SL09" -> reference = false;
            case "SL04" -> transcript = "FOUND_FUNCTIONAL_ALTERNATIVE";
            case "SL05" -> transcript = "UNRESOLVED";
            case "SL06" -> transcriptComplete = false;
            case "SL07" -> { upstream = "UNAVAILABLE"; upstreamComplete = false; reference = false; }
            case "SL10" -> upstream = "CONFLICT";
            case "SL11" -> restart = null;
            case "SL12" -> policy = "PROHIBITED";
            case "SL13" -> policy = "UNSUPPORTED";
            case "SL14" -> mechanism = "UNRESOLVED";
            case "SL15" -> mechanism = "NOT_LOF";
            case "SL16" -> sequence = "CONFLICT";
            case "SL18" -> sequence = "UNSUPPORTED";
            default -> throw new IllegalArgumentException("Unexpected authored test " + id);
        }
        return new Pvs1StartLossDecision.Facts(sequence, policy, mechanism, transcript, transcriptComplete,
                restart, upstream, upstreamComplete, reference);
    }
}
