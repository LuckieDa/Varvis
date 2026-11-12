package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic language/identity cases; these do not establish biological function. */
class Pvs1StartLossFunctionalApplicabilityTest {
    final ObjectMapper mapper = new ObjectMapper();
    private static final String TX = "NM_000001.2", SHA = "a".repeat(64);

    ObjectNode transcripts() {
        var review = mapper.createObjectNode().put("status", "UNRESOLVED").put("unresolvedCount", 1).put("complete", false);
        review.putArray("findings").addObject().put("transcript", TX).put("sourceSha256", "b".repeat(64))
                .put("sourceLocator", "/transcripts/1").put("disposition", "UNRESOLVED").put("differentStart", true)
                .putArray("startPositions").add(101).add(102).add(103);
        return review;
    }
    ObjectNode report(String prefix, String sentence) {
        String quote = prefix + sentence; int start = prefix.codePointCount(0, prefix.length());
        var report = mapper.createObjectNode();
        var doc = report.putArray("documents").addObject().put("pmcid", "PMC123");
        doc.putArray("pendingUnits");
        var f = doc.putArray("findings").addObject().put("unitId", "/body/1").put("sourceContext", "PRIMARY_OR_DISCUSSION_REQUIRES_REVIEW");
        f.putObject("citation").put("unitId", "/body/1").put("quote", quote).put("sourceSha256", SHA)
                .put("start", 7).put("end", 7 + quote.codePointCount(0, quote.length()));
        doc.putObject("experimentalAssessment").putArray("observations").addObject().put("kind", "FRAGMENT_CONSTRUCT_REPORTED")
                .put("unitId", "/body/1").put("sourceSha256", SHA).put("sourceQuote", sentence)
                .put("sourceStart", 7 + start).put("sourceEnd", 7 + quote.codePointCount(0, quote.length()))
                .put("sourceRole", "PRIMARY_OR_DISCUSSION_REQUIRES_REVIEW");
        return report;
    }
    ObjectNode assess(ObjectNode report) { return Pvs1StartLossFunctionalApplicability.assess(mapper, transcripts(), report); }

    @Test void exactMentionAndUnicodePositionReplayStillDoNotQualifyFunction() {
        var r = assess(report("\uD83E\uDDEC Background. ", TX + " protein fragments retained binding."));
        assertEquals("IDENTITY_MENTION_ONLY_FUNCTION_UNRESOLVED", r.at("/candidates/0/status").asText());
        assertEquals("QUOTE_AND_LOCATION_MATCH", r.at("/candidates/0/documents/0/observations/0/sourceReplay").asText());
        assertEquals("FRAGMENT_ASSAY_NOT_WHOLE_ISOFORM", r.at("/candidates/0/documents/0/observations/0/useDisposition").asText());
        assertFalse(r.path("functionalQualificationGranted").asBoolean());
        assertFalse(r.path("negativeFunctionalCoverageGranted").asBoolean());
    }
    @Test void missingVersionsDifferentVersionsAndIsoformLabelsRemainDifferentProblems() {
        for (var item : java.util.Map.of("NM_000001", "UNVERSIONED_ACCESSION_REQUIRES_BINDING",
                "NM_000001.3", "TRANSCRIPT_VERSION_MISMATCH", "E2", "NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT").entrySet()) {
            var r = assess(report("", item.getKey() + " protein fragments retained binding."));
            assertEquals(item.getValue(), r.at("/candidates/0/documents/0/identityRelation").asText());
            assertEquals("IDENTITY_NOT_ESTABLISHED", r.at("/candidates/0/status").asText());
        }
    }
    @Test void terminalPeriodIsPunctuationButAdditionalIdentifierSuffixIsNotTruncated() {
        assertEquals("EXACT_VERSION_MENTION_NOT_FUNCTION", assess(report("", "The construct used " + TX + "."))
                .at("/candidates/0/documents/0/identityRelation").asText());
        assertEquals("UNVERSIONED_ACCESSION_REQUIRES_BINDING", assess(report("", "The construct used NM_000001."))
                .at("/candidates/0/documents/0/identityRelation").asText());
        for (String suffix : java.util.List.of(".3", "X", "_fragment"))
            assertEquals("NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT", assess(report("", "Construct " + TX + suffix))
                    .at("/candidates/0/documents/0/identityRelation").asText());
    }
    @Test void paragraphMentionAndMultiSubjectSentenceDoNotBindExperimentToCandidate() {
        var context = assess(report(TX + " was discussed. ", "Other protein fragments retained binding."));
        assertEquals("TRANSCRIPT_MENTION_ONLY_IN_CONTEXT", context.at("/candidates/0/documents/0/observations/0/identityRelation").asText());
        var mixed = assess(report("", TX + " and NM_000002.1 protein fragments retained binding."));
        assertEquals("AMBIGUOUS_EXPERIMENT_SUBJECT", mixed.at("/candidates/0/documents/0/observations/0/identityRelation").asText());
        assertEquals("EXPERIMENT_SUBJECT_NOT_BOUND", mixed.at("/candidates/0/documents/0/observations/0/useDisposition").asText());
    }
    @Test void quoteHashOffsetsSourceRoleAndDuplicateUnitsAreReplayedNotTrusted() {
        for (String field : java.util.List.of("sourceQuote", "sourceSha256", "sourceStart", "sourceEnd", "sourceRole", "duplicateUnit")) {
            var report = report("", TX + " protein fragments retained binding.");
            var observation = (ObjectNode) report.at("/documents/0/experimentalAssessment/observations/0");
            if (field.equals("duplicateUnit")) ((ObjectNode) report.at("/documents/0")).withArray("findings").add(report.at("/documents/0/findings/0").deepCopy());
            else if (field.equals("sourceStart")) observation.put(field, Integer.MIN_VALUE);
            else if (field.equals("sourceEnd")) observation.put(field, Integer.MAX_VALUE);
            else observation.put(field, "changed");
            var r = assess(report);
            assertEquals("OBSERVATION_PROVENANCE_INVALID", r.at("/candidates/0/status").asText(), field);
            assertEquals(1, r.at("/candidates/0/documents/0/invalidObservationCount").asInt(), field);
            assertTrue(r.at("/candidates/0/documents/0/observations").isEmpty());
        }
    }
    @Test void secondarySourceAndEachExperimentalLimitAreKeptWithoutFalseConflict() {
        for (var entry : java.util.Map.of("EXON_DELETION_INTERVENTION_REPORTED", "INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION",
                "OVEREXPRESSION_INTERVENTION_REPORTED", "INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION",
                "PARTIAL_READOUT_RESCUE_REPORTED", "PARTIAL_ENDPOINT_NOT_WHOLE_FUNCTION",
                "PROTEIN_ABUNDANCE_INCREASE_REPORTED", "ABUNDANCE_NOT_FUNCTIONAL_RESCUE").entrySet()) {
            var r = report("", TX + " experiment observation.");
            ((ObjectNode) r.at("/documents/0/experimentalAssessment/observations/0")).put("kind", entry.getKey());
            assertEquals(entry.getValue(), assess(r).at("/candidates/0/documents/0/observations/0/useDisposition").asText());
        }
        var r = report("", TX + " protein fragments retained binding.");
        ((ObjectNode) r.at("/documents/0/findings/0")).put("sourceContext", "CITED_OR_BACKGROUND");
        ((ObjectNode) r.at("/documents/0/experimentalAssessment/observations/0")).put("sourceRole", "CITED_OR_BACKGROUND");
        var result = assess(r);
        assertEquals("PRIMARY_EXPERIMENT_NOT_ESTABLISHED", result.at("/candidates/0/documents/0/observations/0/useDisposition").asText());
        assertEquals("EXPERIMENT_CONTEXT_NOT_SUFFICIENT_TO_COMPARE", result.path("contradictionAssessment").asText());
    }
    @Test void sharedStartsAndResolvedCandidatesNeedNoExperimentalGateAndInputIsUnchanged() {
        var review = transcripts(); var before = review.deepCopy();
        var row = (ObjectNode) review.at("/findings/0"); row.put("differentStart", false);
        var r = Pvs1StartLossFunctionalApplicability.assess(mapper, review, report("", "E2 protein fragments retained binding."));
        assertEquals("NO_STRUCTURALLY_BOUND_CANDIDATE_TO_ASSESS", r.path("status").asText());
        assertEquals(0, r.path("candidateCount").asInt());
        var original = before.deepCopy();
        Pvs1StartLossFunctionalApplicability.assess(mapper, before, report("", TX + " protein fragments retained binding."));
        assertEquals(original, before);
    }
    @Test void unknownStructureIsCountedAndUnrelatedSourceFailureDoesNotTaintCandidate() {
        var review = transcripts(); ((ObjectNode) review.at("/findings/0")).remove("differentStart");
        var result = Pvs1StartLossFunctionalApplicability.assess(mapper, review, report("", "E2 protein fragments retained binding."));
        assertEquals(1, result.path("unassessedUnresolvedRowCount").asInt());
        assertEquals("NO_STRUCTURALLY_BOUND_CANDIDATE_TO_ASSESS", result.path("status").asText());
        var report = report("", "Unrelated protein fragments retained binding.");
        ((ObjectNode) report.at("/documents/0/experimentalAssessment/observations/0")).put("sourceQuote", "changed");
        var r = assess(report);
        assertEquals("IDENTITY_NOT_ESTABLISHED", r.at("/candidates/0/status").asText());
        assertEquals(1, r.at("/candidates/0/documents/0/invalidObservationCount").asInt());
    }
    @Test void publicLimitsPreserveFullPrivateCandidateAndObservationLists() {
        var full = assess(report("", TX + " protein fragments retained binding."));
        var candidates = full.withArray("candidates"); var template = candidates.get(0).deepCopy();
        for (int i = 1; i < 35; i++) candidates.add(template.deepCopy());
        full.put("candidateCount", 35);
        var visible = full.deepCopy(); Pvs1StartLossFunctionalApplicability.summarize(mapper, visible);
        assertEquals(30, visible.path("candidates").size()); assertTrue(visible.path("displayTruncated").asBoolean());
        assertEquals(35, full.path("candidates").size());
    }
}
