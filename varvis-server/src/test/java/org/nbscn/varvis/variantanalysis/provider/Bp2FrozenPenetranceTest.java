package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Bp2TestMaterials.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class Bp2FrozenPenetranceTest {
    @TestFactory Stream<DynamicTest> frozenReviewerBytesMustNotYieldBenignSupport() {
        return Stream.of("sex", "age", "population").map(kind -> DynamicTest.dynamicTest(kind, () -> {
            String name = "restricted-" + kind + "-patient-mismatch";
            byte[] bytes = resource("r1/frozen/" + name + "/loader-bytes.json");
            var pins = M.readTree(resource("r1/frozen/" + name + "/pins.json"));
            assertEquals(pins.path("rawSha256").asText(), raw(bytes));
            var bundle = Bp2PhaseBundle.load(M, bytes, pins.path("rawSha256").asText(), pins.path("canonicalSha256").asText());
            ObjectNode result = new Bp2PhaseCriterionEvaluator(M).evaluate(bundle);
            Path dir = Path.of(System.getProperty("varvis.ps2.artifacts"), "frozen", name); Files.createDirectories(dir);
            Files.write(dir.resolve("loader-bytes.json"), bytes);
            Files.write(dir.resolve("pins.json"), resource("r1/frozen/" + name + "/pins.json"));
            Files.write(dir.resolve("output.json"), M.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
            assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
            assertEquals("BP2_PENETRANCE_CONDITIONS_NOT_ESTABLISHED", result.at("/evaluationResult/reasonCode").asText());
            for (String field : List.of("evidence", "evaluation", "evaluationId", "evidenceId", "deterministicTrace", "appliedStrength", "bp2Points")) assertFalse(result.has(field));
            assertEquals(3, result.at("/criterionEvaluations/0").size());
            assertEquals("UNKNOWN", result.at("/criterionApplicability/status").asText());
        }));
    }
}
