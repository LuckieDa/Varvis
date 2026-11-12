package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent residual-transcript expectations fixed in PVS1_SPLICE_BOUNDARY_REVIEW_2026-11-04.
 * Ordinary source fixture writes/reads actual pinned toy originals; this is engineering
 * qualification, not clinical RNA, scientific adoption or a new quantity threshold.
 */
class Pvs1SpliceResidualQualificationTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    static Stream<Arguments> residualCases() {
        var cases = new java.util.ArrayList<Arguments>();
        for (int strand : new int[]{1, -1}) for (boolean predicted : new boolean[]{true, false})
            for (String value : List.of("missing", "not-measured", "unknown", "not-detected", "present", "detected",
                    "json-null", "boolean", "number", "array", "object", "unknown-label", "empty-string")) {
                boolean compatible = value.equals("not-detected")
                        || predicted && List.of("missing", "not-measured", "unknown").contains(value);
                cases.add(Arguments.of(strand, predicted, value, compatible));
            }
        return cases.stream();
    }

    @ParameterizedTest(name = "strand={0}, predicted={1}, residual={2}, compatible={3}")
    @MethodSource("residualCases")
    void sourceQualificationPreservesUnknownPredictionsButCannotDiscardKnownOrMalformedResidualFacts(
            int strand, boolean predicted, String value, boolean compatible) throws Exception {
        var d = new Pvs1SpliceConsequenceTest().data(strand, false, predicted);
        var policy = d.input().policy;var model = d.input().model;var outcome = d.material();
        var review = outcome.withObject("review");
        switch (value) {
            case "missing" -> review.remove("residualNormalTranscript");
            case "not-measured" -> review.put("residualNormalTranscript", "NOT_MEASURED");
            case "unknown" -> review.put("residualNormalTranscript", "UNKNOWN");
            case "not-detected" -> review.put("residualNormalTranscript", "NOT_DETECTED_IN_ASSESSED_SCOPE");
            case "present" -> review.put("residualNormalTranscript", "PRESENT");
            case "detected" -> review.put("residualNormalTranscript", "DETECTED_IN_ASSESSED_SCOPE");
            case "json-null" -> review.putNull("residualNormalTranscript");
            case "boolean" -> review.put("residualNormalTranscript", false);
            case "number" -> review.put("residualNormalTranscript", 0);
            case "array" -> review.putArray("residualNormalTranscript");
            case "object" -> review.putObject("residualNormalTranscript");
            case "unknown-label" -> review.put("residualNormalTranscript", "UNRECOGNIZED_SOURCE_LABEL");
            case "empty-string" -> review.put("residualNormalTranscript", "");
            default -> fail("Unknown independent test vector");
        }
        Path fixtureDirectory = root;boolean emitArtifact = false;
        String configuredOutput = System.getProperty("varvis.a2.boundary.source.output");
        if (configuredOutput != null && !configuredOutput.isBlank() && predicted
                && List.of("not-measured", "present", "json-null").contains(value)) {
            fixtureDirectory = Path.of(configuredOutput).resolve((strand == 1 ? "plus" : "minus") + "-" + value);
            emitArtifact = true;
        }
        var originals = Pvs1SpliceSourceQualificationTest.configureOriginals(mapper, policy, model, List.of(outcome));
        // Selected cases write the fixture directly into the requested directory, never copy it after the test.
        var qualification = Pvs1SpliceSourceQualificationTest.qualification(mapper, fixtureDirectory, policy, model, List.of(outcome), originals);
        var entry = new Pvs1SpliceSourceQualificationTest().entry(policy, qualification);
        assertNull(Pvs1SourceQualification.issue(entry, policy, adapter));
        var assessment = mapper.createObjectNode().put("status", "READY");
        assessment.set("adoption", entry);
        String issue = Pvs1SourceQualification.spliceMaterialIssue(assessment, d.input().context, policy, model, outcome, adapter);
        if (emitArtifact) {
            Files.write(fixtureDirectory.resolve("qualification.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(qualification));
            var actual = mapper.createObjectNode().put("schemaVersion", "pvs1-splice-residual-source-replay-v1")
                    .put("dataClass", "SYNTHETIC").put("purpose", "PROJECT_RESEARCH").put("clinicalAcceptance", false)
                    .put("scientificAdoptionGranted", false).put("stage", "SOURCE_QUALIFICATION_ONLY")
                    .put("strand", strand).put("predicted", predicted).put("independentExpectedCompatible", compatible)
                    .put("residualVector", value).put("sourceQualificationAccepted", issue == null);
            if (issue == null) actual.putNull("actualIssue");else actual.put("actualIssue", issue);
            Files.write(fixtureDirectory.resolve("issue.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(actual));
        }
        // Every case is tested after actual original/outcome bytes were read and pinned.
        byte[] actualOutcome = Files.readAllBytes(fixtureDirectory.resolve("outcome-0.json"));
        assertEquals(outcome, mapper.readTree(actualOutcome));
        assertEquals(CodingTranscriptMaterialSupply.sha(actualOutcome), qualification.at("/spliceVerification/outcomes/0/rawSha256").asText());
        assertEquals(actualOutcome.length, qualification.at("/spliceVerification/outcomes/0/bytes").asInt());
        assertEquals(outcome.path("review"), qualification.at("/spliceVerification/outcomes/0/outcome/review"));
        if (compatible) assertNull(issue, value);
        else assertEquals("PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED", issue, value);
        assertFalse(qualification.path("createsExpertReview").asBoolean());
        assertFalse(qualification.path("clinicalAcceptance").asBoolean());
    }
}
