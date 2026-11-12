package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pm3TestMaterials.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Permanent regression derived from the independent missing-family probe; fixed fixtures remain read-only. */
class Pm3MissingFamilyTest {
    @TestFactory Stream<DynamicTest> requiredFamilyIsRuleLocalAndPreservesOtherRejections() {
        return Stream.of("alpha", "beta").flatMap(tag -> Stream.of("baseline", "different-family", "missing-family",
                "null-family", "blank-family", "invalid-family", "source-pin", "assessment-pin", "facts-mismatch")
                .map(mode -> DynamicTest.dynamicTest(tag + "-" + mode, () -> {
                    ObjectNode input = base(tag), patient = object(input, "/context/patientContext");
                    if (List.of("missing-family", "source-pin", "assessment-pin", "facts-mismatch").contains(mode)) patient.remove("familyContextId");
                    if (mode.equals("different-family")) patient.put("familyContextId", "family-context-test-pm3-other");
                    if (mode.equals("null-family")) patient.putNull("familyContextId");
                    if (mode.equals("blank-family")) patient.put("familyContextId", "");
                    if (mode.equals("invalid-family")) patient.put("familyContextId", "not-a-family-context");
                    // Reissue only the explicit TEST_ONLY context/source/review bindings. Do not fill the missing field.
                    if (!mode.equals("baseline")) rebindFamily(input);
                    if (mode.equals("source-pin")) { object(facts(input), "/sources/0").put("rawSha256", "0".repeat(64)); pinsOnly(input); }
                    if (mode.equals("assessment-pin")) object(observation(input), "/assessment").put("contentSha256", "0".repeat(64));
                    if (mode.equals("facts-mismatch")) { facts(input).put("familyId", "family-context-test-pm3-other"); reissue(input, "facts"); }
                    ObjectNode before = input.deepCopy();
                    byte[] bytes = M.writeValueAsBytes(input);
                    Path folder = Path.of(System.getProperty("varvis.ps2.artifacts"), "pm3-r1", tag + "-" + mode);
                    Files.createDirectories(folder);
                    Files.write(folder.resolve("loader-bytes.json"), bytes);
                    Files.writeString(folder.resolve("pins.json"), M.writeValueAsString(M.createObjectNode().put("rawSha256", raw(bytes)).put("canonicalSha256", hash(input))));
                    var adapter = new RuntimeEvidenceV2Adapter(M);
                    boolean invalidV3 = List.of("null-family", "blank-family", "invalid-family").contains(mode);
                    if (invalidV3) assertThrows(IllegalArgumentException.class, () -> adapter.validateVersionedContext(input.path("context")));
                    else adapter.validateVersionedContext(input.path("context"));
                    var bundle = Pm3InTransBundle.load(M, bytes, raw(bytes), hash(input));
                    Files.writeString(folder.resolve("preconditions.json"), M.writeValueAsString(M.createObjectNode()
                            .put("contextValidated", !invalidV3).put("loaderAccepted", true).put("testOnlyReissued", !mode.equals("baseline"))));
                    ObjectNode actual;
                    try { actual = new Pm3InTransCriterionEvaluator(M).evaluate(bundle); }
                    catch (RuntimeException failure) {
                        StringWriter stack = new StringWriter(); failure.printStackTrace(new PrintWriter(stack));
                        Files.writeString(folder.resolve("uncaught.txt"), stack.toString());
                        fail("Loaded material must return structured PM3 rejection, not escape: " + failure, failure);
                        return;
                    }
                    Files.write(folder.resolve("output.json"), M.writerWithDefaultPrettyPrinter().writeValueAsBytes(actual));
                    assertEquals(before, input); assertEquals(before, bundle.snapshot()); assertEquals(before, actual.path("sourceBundle"));
                    if (mode.equals("baseline")) {
                        assertEquals("MET", actual.at("/evaluation/evaluationResult/status").asText());
                        assertEquals(1, actual.path("pm3Points").intValue()); assertEquals("MODERATE", actual.at("/evaluation/appliedStrength").asText());
                        assertEquals(actual, evaluate(base(tag)));
                    } else {
                        String expected = switch (mode) {
                            case "different-family" -> "PM3_CONTEXT_BINDING_MISMATCH";
                            case "source-pin" -> "PM3_SOURCE_RAW_PIN_MISMATCH";
                            case "assessment-pin" -> "PM3_ASSESSMENT_PIN_MISMATCH";
                            case "facts-mismatch" -> "PM3_FACT_IDENTITY_MISMATCH";
                            case "null-family", "blank-family", "invalid-family" -> "PM3_CONTEXT_IDENTITY_INVALID";
                            default -> "PM3_FAMILY_CONTEXT_REQUIRED";
                        };
                        assertEquals("NOT_EVALUATED", actual.at("/evaluationResult/status").asText());
                        assertEquals(expected, actual.at("/evaluationResult/reasonCode").asText());
                        for (String field : List.of("pm3Points", "evidence", "evaluation", "evaluationId", "appliedStrength", "deterministicTrace")) assertFalse(actual.has(field), field);
                        var criterion = actual.at("/criterionEvaluations/0");
                        assertEquals(3, criterion.size());
                        assertEquals("NOT_EVALUATED", criterion.at("/evaluationResult/status").asText());
                        assertEquals(expected, criterion.at("/evaluationResult/reasonCode").asText());
                        assertEquals("UNKNOWN", criterion.at("/criterionApplicability/status").asText());
                    }
                })));
    }
}
