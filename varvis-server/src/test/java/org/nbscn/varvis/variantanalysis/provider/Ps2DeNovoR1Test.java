package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** R1 expectations are explicit refusals, never copied from the implementation's verdict. */
class Ps2DeNovoR1Test {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private ObjectNode read(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/ps2-denovo-core/" + name)) {
            assertNotNull(input); return (ObjectNode) mapper.readTree(input);
        }
    }
    private static ObjectNode at(JsonNode n, String path) { return (ObjectNode) n.at(path); }
    private static ObjectNode facts(ObjectNode b) { return at(b, "/observations/0/facts"); }
    private static ObjectNode clinical(ObjectNode b) { return at(facts(b), "/clinical"); }
    private static ObjectNode detection(ObjectNode b, int i) { return at(facts(b), "/members/" + i + "/detection"); }
    private void reseal(ObjectNode b) throws Exception {
        ObjectNode f = facts(b), projection = f.deepCopy(); projection.remove("sources");
        for (JsonNode item : f.path("sources")) {
            String raw = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projection);
            ((ObjectNode) item).put("rawUtf8", raw).put("rawSha256", Ps2DeNovoBundle.sha256(raw.getBytes(StandardCharsets.UTF_8)))
                    .put("canonicalSha256", adapter.canonicalSha256(projection));
        }
        ObjectNode a = at(b, "/observations/0/assessment"); a.put("factsSha256", adapter.canonicalSha256(f)); a.remove("contentSha256");
        a.put("contentSha256", adapter.canonicalSha256(a));
        at(b, "/policy").put("factsSha256", adapter.canonicalSha256(f)).put("assessmentSha256", adapter.canonicalSha256(a));
        for (JsonNode entry : b.at("/catalog/entries")) at(entry, "/policyBinding").put("policySha256", adapter.canonicalSha256(b.path("policy")));
        b.put("catalogSha256", adapter.canonicalSha256(b.path("catalog")));
    }
    private ObjectNode execute(String name, ObjectNode b) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(b); ObjectNode before = b.deepCopy();
        ObjectNode result = new Ps2DeNovoCriterionEvaluator(mapper).evaluate(Ps2DeNovoBundle.load(mapper, bytes,
                Ps2DeNovoBundle.sha256(bytes), adapter.canonicalSha256(b)));
        assertEquals(before, b);
        Path dir = Path.of(System.getProperty("varvis.ps2.artifacts")); Files.createDirectories(dir);
        Files.write(dir.resolve("r1-" + name + "-input.json"), bytes);
        Files.write(dir.resolve("r1-" + name + "-result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        return result;
    }
    private static void refused(ObjectNode result, String reason) {
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), "actual=" + result.at("/evaluation/evaluationResult/status") + "; points=" + result.path("deNovoPoints"));
        assertEquals(reason, result.at("/evaluationResult/reasonCode").asText());
        assertFalse(result.has("deNovoPoints")); assertFalse(result.has("evaluation"));
        JsonNode minimal = result.at("/criterionEvaluations/0");
        for (String key : List.of("evaluationId", "appliedStrength", "deterministicTrace")) assertFalse(minimal.has(key));
    }
    private static void samples(ObjectNode b, String father, String mother) {
        for (int i : List.of(1, 2)) {
            String id = i == 1 ? father : mother;
            at(facts(b), "/members/" + i).put("sampleId", id); at(facts(b), "/records/" + i).put("sampleId", id);
        }
    }
    @TestFactory Stream<DynamicTest> originalSchemaRootCauseRegressions() {
        record Case(String name, Consumer<ObjectNode> change, String reason) { }
        List<Case> cases = List.of(
            new Case("legacy-reviewed-no-conclusion", b -> { }, "PS2_CLINICAL_APPLICABILITY_MISSING"),
            new Case("legacy-unresolved-history", b -> { clinical(b).put("familyHistoryBasis", "TEST_ONLY related family history present and unresolved, review completed"); at(facts(b), "/members/1").put("affectedStatus", "AFFECTED"); }, "PS2_CLINICAL_APPLICABILITY_MISSING"),
            new Case("legacy-ft-conflict", b -> { detection(b, 1).put("sampleFormat", "GT:DP:GQ:AD:FT"); at(facts(b), "/records/1").put("rawSampleValue", "0/0:40:60:40,0:LowQual"); }, "PS2_RAW_FT_CONFLICT"),
            new Case("legacy-nfc-samples", b -> samples(b, "test-parent-é", "test-parent-e\u0301"), "PS2_MEMBER_OR_SAMPLE_CONFLICT"),
            new Case("legacy-long-alt", b -> detection(b, 0).put("targetAltIndex", 4294967297L), "PS2_ALLELIC_SCOPE_UNSUPPORTED"),
            new Case("legacy-long-ploidy", b -> detection(b, 0).put("ploidy", 4294967298L), "PS2_ALLELIC_SCOPE_UNSUPPORTED"),
            new Case("legacy-negative-wrap", b -> detection(b, 0).put("targetAltIndex", -4294967295L).put("ploidy", -4294967294L), "PS2_ALLELIC_SCOPE_UNSUPPORTED")
        );
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) tests.add(DynamicTest.dynamicTest(c.name(), () -> {
            ObjectNode b = read("alpha-bundle.json"); c.change().accept(b); reseal(b); refused(execute(c.name(), b), c.reason());
        }));
        return tests.stream();
    }

    private static void members(ObjectNode b, String father, String mother) {
        for (int i : List.of(1, 2)) {
            String id = i == 1 ? father : mother;
            at(facts(b), "/members/" + i).put("memberId", id);
            detection(b, i).put("memberId", id);
            at(facts(b), "/parentage/" + (i - 1)).put("parentMemberId", id);
        }
    }
    private static void ft(ObjectNode b, String value, String structured) {
        detection(b, 1).put("sampleFormat", "GT:DP:GQ:AD:FT");
        at(facts(b), "/records/1").put("rawSampleValue", "0/0:40:60:40,0:" + value).put("sampleFilter", structured);
    }
    @TestFactory Stream<DynamicTest> explicitConclusionMaterialGates() {
        record Case(String name, Consumer<ObjectNode> change, String reason) { }
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("valid-conclusion-unknown-parents", b -> { }, "MET"));
        cases.add(new Case("ft-pass", b -> ft(b, "PASS", "PASS"), "MET"));
        cases.add(new Case("ft-fail-pass", b -> ft(b, "LowQual", "PASS"), "PS2_RAW_FT_CONFLICT"));
        cases.add(new Case("ft-fail-fail", b -> ft(b, "LowQual", "LowQual"), "PS2_DETECTION_QUALITY_INCOMPLETE"));
        cases.add(new Case("ft-pass-fail", b -> ft(b, "PASS", "LowQual"), "PS2_DETECTION_QUALITY_INCOMPLETE"));
        cases.add(new Case("ft-dot", b -> ft(b, ".", "PASS"), "PS2_RAW_FT_MISSING"));
        cases.add(new Case("ft-empty", b -> ft(b, "", "PASS"), "PS2_RAW_FT_MISSING"));
        cases.add(new Case("no-ft-unknown-structured", b -> at(facts(b), "/records/1").putNull("sampleFilter"), "PS2_DETECTION_QUALITY_INCOMPLETE"));
        cases.add(new Case("no-ft-unknown-quality", b -> detection(b, 1).put("quality", "UNKNOWN"), "PS2_DETECTION_QUALITY_INCOMPLETE"));
        cases.add(new Case("nfc-samples", b -> samples(b, "test-parent-é", "test-parent-e\u0301"), "PS2_MEMBER_OR_SAMPLE_CONFLICT"));
        cases.add(new Case("nfc-members", b -> members(b, "test-parent-é", "test-parent-e\u0301"), "PS2_MEMBER_OR_SAMPLE_CONFLICT"));
        cases.add(new Case("distinct-nfc-samples", b -> samples(b, "test-parent-é", "test-other-e\u0301"), "MET"));
        cases.add(new Case("distinct-nfc-members", b -> members(b, "test-parent-é", "test-other-e\u0301"), "MET"));
        cases.add(new Case("case-not-folded", b -> samples(b, "test-parent-e", "test-parent-E"), "MET"));
        cases.add(new Case("space-not-folded", b -> samples(b, "test-parent-e", "test-parent-e "), "MET"));
        cases.add(new Case("record-exact-binding", b -> { samples(b, "test-parent-é", "test-other"); at(facts(b), "/records/1").put("sampleId", "test-parent-e\u0301"); }, "PS2_DETECTION_MEMBER_MISMATCH"));
        cases.add(new Case("detection-exact-binding", b -> { members(b, "test-parent-é", "test-other"); detection(b, 1).put("memberId", "test-parent-e\u0301"); }, "PS2_DETECTION_MEMBER_MISMATCH"));
        for (long[] vector : List.of(new long[]{2, 2}, new long[]{1, 3}, new long[]{4294967297L, 2}, new long[]{1, 4294967298L}, new long[]{4294967297L, 4294967298L}, new long[]{-4294967295L, -4294967294L}, new long[]{Long.MAX_VALUE, 2}, new long[]{1, Long.MIN_VALUE}))
            cases.add(new Case("integer-" + vector[0] + "-" + vector[1], b -> detection(b, 0).put("targetAltIndex", vector[0]).put("ploidy", vector[1]), "PS2_ALLELIC_SCOPE_UNSUPPORTED"));
        for (String value : List.of("PRESENT", "UNKNOWN", "CONFLICT", "UNRESOLVED"))
            cases.add(new Case("history-" + value, b -> at(clinical(b), "/applicability").put("relatedFamilyHistory", value), "PS2_RELATED_FAMILY_HISTORY_NOT_ABSENT"));
        for (String value : List.of("OUT_OF_SCOPE", "UNKNOWN", "CONFLICT", "UNRESOLVED"))
            cases.add(new Case("scope-" + value, b -> at(clinical(b), "/applicability").put("singleTrioScope", value), "PS2_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE"));
        cases.add(new Case("missing-conclusion", b -> clinical(b).remove("applicability"), "PS2_CLINICAL_APPLICABILITY_MISSING"));
        cases.add(new Case("bad-review-date", b -> at(clinical(b), "/applicability").put("date", "unknown"), "PS2_CLINICAL_APPLICABILITY_REVIEW_INCOMPLETE"));
        cases.add(new Case("bad-review-source", b -> at(clinical(b), "/applicability").putArray("sourceIds").add("MISSING_SOURCE"), "PS2_CLINICAL_APPLICABILITY_REVIEW_INCOMPLETE"));
        cases.add(new Case("affected-parent-unresolved", b -> { at(facts(b), "/members/1").put("affectedStatus", "AFFECTED"); at(clinical(b), "/applicability").put("relatedFamilyHistory", "UNRESOLVED"); }, "PS2_RELATED_FAMILY_HISTORY_NOT_ABSENT"));
        cases.add(new Case("affected-parent-reviewed-unrelated", b -> { at(facts(b), "/members/1").put("affectedStatus", "AFFECTED"); at(clinical(b), "/applicability").put("basis", "TEST_ONLY parent affected by explicitly reviewed unrelated condition; no related family history; single-trio scope applicable"); }, "MET"));
        cases.add(new Case("negative-unknown-history", b -> { clinical(b).put("phenotypeCategory", "NOT_CONSISTENT"); at(clinical(b), "/applicability").put("relatedFamilyHistory", "UNKNOWN"); }, "PS2_RELATED_FAMILY_HISTORY_NOT_ABSENT"));
        List<DynamicTest> tests = new ArrayList<>();
        for (String tag : List.of("alpha", "beta")) for (Case c : cases) tests.add(DynamicTest.dynamicTest(tag + "-" + c.name(), () -> {
            ObjectNode b = read("r1/" + tag + "-bundle.json"); c.change().accept(b); reseal(b);
            ObjectNode result = execute(tag + "-" + c.name(), b);
            if ("MET".equals(c.reason())) {
                assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.path("evaluationResult").toString());
                assertEquals(tag.equals("alpha") ? 2 : 0.5, result.path("deNovoPoints").asDouble());
                assertEquals(facts(b), result.at("/sourceBundle/observations/0/facts"));
            } else refused(result, c.reason());
        }));
        return tests.stream();
    }
    @Test void newConclusionCannotReuseOldReviewOrPolicy() throws Exception {
        ObjectNode b = read("r1/alpha-bundle.json");
        at(clinical(b), "/applicability").put("singleTrioScope", "UNKNOWN");
        refused(execute("stale-conclusion", b), "PS2_FACTS_PIN_MISMATCH");
        ObjectNode originalPolicy = at(b, "/policy").deepCopy(); reseal(b); b.set("policy", originalPolicy);
        refused(execute("stale-policy", b), "PS2_FACTS_PIN_MISMATCH");
        b = read("r1/alpha-bundle.json"); ObjectNode oldResult = execute("fresh-original-review", b);
        at(clinical(b), "/applicability").put("reviewer", "TEST_ONLY replacement reviewer");
        at(b, "/observations/0/assessment").put("assessmentId", "TEST_ONLY-new-r1-review"); reseal(b);
        ObjectNode next = execute("new-review-identity", b);
        assertEquals("MET", next.at("/evaluation/evaluationResult/status").asText());
        assertNotEquals(oldResult.at("/evaluation/evaluationId"), next.at("/evaluation/evaluationId"));
    }
}
