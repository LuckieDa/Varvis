package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Actual Python continuation and original Java evaluator; entirely synthetic. */
class DeNovoReadingLoopTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings={"ps2", "pm6", "missing"})
    void cumulativeSourceCheckedFactsReachOriginalEvaluator(String mode) throws Exception {
        Path python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Code-local Python required");
        Path scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                "from test_denovo_reading_loop import write_java_loop_fixture; import sys; write_java_loop_fixture(sys.argv[1],sys.argv[2])",
                temporary.toString(), mode), scripts, Duration.ofSeconds(40));
        assertTrue(process.isSuccess(), process.getOutput());
        var mapper = new ObjectMapper();
        var result = mapper.readTree(Files.readAllBytes(temporary.resolve("loop-result.json")));
        var initial = mapper.readTree(Files.readAllBytes(temporary.resolve("initial-feedback.json")));
        assertFalse(initial.at("/cases/0/requirementsSatisfied").asBoolean());
        assertFalse(result.path("rounds").isEmpty());
        assertEquals("NOT_RUN", result.path("criterionDecision").asText());
        assertFalse(result.path("absenceProven").asBoolean());
        var group = new Group("GRCh38:1:123:A:G", "HGNC:999999997", "MONDO:1", "AD");
        byte[] policyBytes = Files.readAllBytes(temporary.resolve("loop-policy.json"));
        var policy = DeNovoApplicabilityPolicy.load(policyBytes, FamilyDeNovoJson.sha256(policyBytes),
                Map.of("paper", Files.readAllBytes(temporary.resolve("loop-knowledge.txt"))),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        var read = DeNovoCoreFacts.read(result.path("coreEvaluation"), result.at("/target/allele"), group, policy);
        if (mode.equals("missing")) {
            assertFalse(read.issues().isEmpty());
            assertFalse(result.at("/finalFeedback/requirementsSatisfied").asBoolean());
            assertEquals("NO_PENDING_RELEVANT_TARGET", result.path("stopReason").asText());
            return;
        }
        assertTrue(read.issues().isEmpty(), read.issues().toString());
        assertEquals("READY_FOR_ORIGINAL_EVALUATION", result.path("stopReason").asText());
        var observation = new Observation("synthetic-loop", read.eventId(), SourceKind.LITERATURE_CASE,
                read.facts(), Independence.ESTABLISHED, List.of("PMC999999997#A"), read.issues());
        var calculator = new DeNovoEvidenceCalculator();
        var score = calculator.evaluate(group, new Policy("test", true, false), List.of(observation));
        assertEquals(mode.equals("ps2") ? 2 : 1, score.effectiveQuarterPoints());
        assertEquals(mode.equals("ps2") ? "MET" : "NOT_MET", score.status());
        assertEquals(mode.equals("ps2") ? "PS2" : "NONE", score.code());
        var secondReport = new Observation("synthetic-loop-second-report", read.eventId(), SourceKind.LITERATURE_CASE,
                read.facts(), Independence.ESTABLISHED, List.of("PMC999999997#A"), read.issues());
        var duplicate = calculator.evaluate(group, new Policy("test", true, false), List.of(observation, secondReport));
        assertEquals(score.effectiveQuarterPoints(), duplicate.effectiveQuarterPoints());
        var repeated = mapper.readTree(Files.readAllBytes(temporary.resolve("repeat-result.json")));
        assertEquals(0, repeated.path("modelCalls").asInt());
        assertEquals(result.path("coreEvaluation"), repeated.path("coreEvaluation"));
    }
}
