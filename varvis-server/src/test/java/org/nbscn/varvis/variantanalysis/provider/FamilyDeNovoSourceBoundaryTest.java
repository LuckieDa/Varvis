package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pm6TestMaterials.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Source rejection expectations, including the two independently reproduced loader payloads. */
class FamilyDeNovoSourceBoundaryTest {
    enum Route { PM6_PARENTAGE, PM6_FACTS, PS2_FACTS }

    private void pinOuter(ObjectNode b, Route route) {
        if (route != Route.PS2_FACTS) {
            if (route == Route.PM6_FACTS) basis(b).put("factsSha256", hash(facts(b)));
            pinReview(b);
            return;
        }
        ObjectNode assessment = at(b, "/observations/0/assessment");
        assessment.put("factsSha256", hash(facts(b))).remove("contentSha256");
        assessment.put("contentSha256", hash(assessment));
        at(b, "/policy").put("factsSha256", hash(facts(b))).put("assessmentSha256", hash(assessment));
        pinCatalog(b);
    }

    private ObjectNode input(Route route, String mutation) throws Exception {
        ObjectNode b = route == Route.PS2_FACTS ? resource("/ps2-denovo-core/r1/alpha-bundle.json") : base("alpha");
        if (mutation.equals("normal")) return b;
        ObjectNode s = at(route == Route.PM6_PARENTAGE ? basis(b) : facts(b), "/sources/0");
        ObjectNode report = (ObjectNode) M.readTree(s.path("rawUtf8").asText());
        if (mutation.equals("finite-float")) report.put("independentProbeFiniteFloat", 0.25);
        if (mutation.equals("nested-decimal")) report.putArray("boundaryProbe").addObject().put("decimal", 1.0);
        if (mutation.equals("string-mismatch")) {
            if (route == Route.PM6_PARENTAGE) at(report, "/relations/0").put("method", "INDEPENDENT_PROBE_DIFFERENT_METHOD");
            else report.put("boundaryProbe", "TEST_ONLY_DIFFERENT_FACT");
            s.put("canonicalSha256", hash(report));
        }
        String serialized = mutation.equals("invalid-json") ? "{broken" : M.writeValueAsString(report);
        s.put("rawUtf8", serialized).put("rawSha256", raw(serialized.getBytes(StandardCharsets.UTF_8)));
        if (mutation.equals("pin-mismatch")) s.put("canonicalSha256", "0".repeat(64));
        // Keep the malformed report unchanged; only issue outer test review/policy/catalog pins.
        pinOuter(b, route);
        return b;
    }

    private ObjectNode execute(Route route, String mutation) throws Exception {
        ObjectNode b = input(route, mutation), before = b.deepCopy();
        byte[] bytes = M.writeValueAsBytes(b);
        if (mutation.equals("finite-float") && route != Route.PS2_FACTS) {
            String expected = route == Route.PM6_PARENTAGE
                    ? "84ce52df6686c9cdfe3ae2c76f877a943092da46c1cf580272006952edbaccf9"
                    : "84091a92dd94b30e39fbf67887d4110951888df34ae31db1552700a245e9e245";
            assertEquals(expected, raw(bytes), "Exact independent probe loader payload, not pretty copy");
        }
        Path dir = Path.of(System.getProperty("varvis.ps2.artifacts"), "source-boundary");
        Files.createDirectories(dir);
        String name = route + "-" + mutation;
        Files.write(dir.resolve(name + "-loader-bytes.json"), bytes);
        ObjectNode receipt = M.createObjectNode().put("route", route.name()).put("mutation", mutation)
                .put("rawSha256", raw(bytes)).put("canonicalSha256", hash(b));
        try {
            ObjectNode result;
            if (route == Route.PS2_FACTS) {
                Ps2DeNovoBundle loaded = Ps2DeNovoBundle.load(M, bytes, raw(bytes), hash(b));
                receipt.put("loader", "ACCEPTED");
                result = new Ps2DeNovoCriterionEvaluator(M).evaluate(loaded);
            } else {
                Pm6DeNovoBundle loaded = Pm6DeNovoBundle.load(M, bytes, raw(bytes), hash(b));
                receipt.put("loader", "ACCEPTED");
                result = new Pm6DeNovoCriterionEvaluator(M).evaluate(loaded);
            }
            receipt.set("result", result);
            assertEquals(before, b);
            return result;
        } catch (RuntimeException error) {
            receipt.put("exceptionClass", error.getClass().getName()).put("exception", error.getMessage());
            throw error;
        } finally {
            Files.write(dir.resolve(name + "-receipt.json"), M.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
        }
    }

    private void minimal(ObjectNode result, String reason) {
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertEquals(reason, result.at("/evaluationResult/reasonCode").asText());
        assertFalse(result.has("evaluation"));
        for (JsonNode node : List.of(result, result.at("/criterionEvaluations/0"))) {
            for (String key : List.of("deNovoPoints", "evaluationId", "appliedStrength", "baseStrength", "strength", "deterministicTrace")) {
                assertFalse(node.has(key), key);
            }
        }
    }

    @TestFactory Stream<DynamicTest> reportsRejectWithoutLeakingCanonicalExceptions() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Route route : Route.values()) {
            for (String mutation : List.of("normal", "string-mismatch", "pin-mismatch", "finite-float", "nested-decimal", "invalid-json")) {
                tests.add(DynamicTest.dynamicTest(route + ":" + mutation, () -> {
                    ObjectNode result = assertDoesNotThrow(() -> execute(route, mutation));
                    if (mutation.equals("normal")) {
                        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
                    } else {
                        String prefix = route == Route.PM6_PARENTAGE ? "PM6_PARENTAGE_" : route == Route.PM6_FACTS ? "PM6_" : "PS2_";
                        String suffix = switch (mutation) {
                            case "string-mismatch" -> "SOURCE_FACTS_MISMATCH";
                            case "pin-mismatch" -> "SOURCE_CANONICAL_PIN_MISMATCH";
                            default -> "SOURCE_DOCUMENT_INVALID";
                        };
                        minimal(result, prefix + suffix);
                    }
                }));
            }
        }
        return tests.stream();
    }
}
