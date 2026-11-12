package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import org.nbscn.varvis.variantanalysis.trio.*;

class Ps2DeNovoCoreTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private ObjectNode resource(String name) throws Exception {
        // Original b9c5bc0 fixtures stay immutable; positive R1 cases require newly reviewed conclusions.
        if (name.endsWith("-bundle.json") || name.endsWith("-pins.json")) name = "r1/" + name;
        try (var input = getClass().getResourceAsStream("/ps2-denovo-core/" + name)) {
            assertNotNull(input); return (ObjectNode) mapper.readTree(input);
        }
    }
    private ObjectNode base() throws Exception { return resource("alpha-bundle.json"); }
    private static ObjectNode object(JsonNode root, String pointer) { return (ObjectNode) root.at(pointer); }
    private static ObjectNode facts(ObjectNode b) { return object(b, "/observations/0/facts"); }
    private static ObjectNode assessment(ObjectNode b) { return object(b, "/observations/0/assessment"); }
    private static ObjectNode clinical(ObjectNode b) { return object(facts(b), "/clinical"); }
    private static ObjectNode genotype(ObjectNode b, int index) { return object(facts(b), "/records/" + index + "/observation"); }
    private static ObjectNode detection(ObjectNode b, int index) { return object(facts(b), "/members/" + index + "/detection"); }
    private Ps2DeNovoBundle load(ObjectNode b) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(b);
        return Ps2DeNovoBundle.load(mapper, bytes, Ps2DeNovoBundle.sha256(bytes), adapter.canonicalSha256(b));
    }
    private ObjectNode evaluate(ObjectNode b) throws Exception { return new Ps2DeNovoCriterionEvaluator(mapper).evaluate(load(b)); }
    private void archive(String name, ObjectNode input, ObjectNode result) throws Exception {
        Path dir = Path.of(System.getProperty("varvis.ps2.artifacts")); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + "-input.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(input));
        Files.writeString(dir.resolve(name + "-result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    /** Test-only issuance of NEW facts, reports, review and policy pins; expected outcomes never use production scoring. */
    private void reseal(ObjectNode b) throws Exception {
        ObjectNode f = facts(b), projection = f.deepCopy(); projection.remove("sources");
        for (JsonNode n : f.path("sources")) {
            ObjectNode s = (ObjectNode) n; String raw = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projection);
            s.put("rawUtf8", raw).put("rawSha256", Ps2DeNovoBundle.sha256(raw.getBytes(StandardCharsets.UTF_8)))
                    .put("canonicalSha256", adapter.canonicalSha256(projection));
        }
        ObjectNode a = assessment(b); a.put("factsSha256", adapter.canonicalSha256(f)); a.remove("contentSha256");
        a.put("contentSha256", adapter.canonicalSha256(a));
        ObjectNode p = object(b, "/policy"); p.put("factsSha256", adapter.canonicalSha256(f)).put("assessmentSha256", adapter.canonicalSha256(a));
        pinCatalog(b);
    }
    private void pinCatalog(ObjectNode b) {
        for (JsonNode entry : b.at("/catalog/entries")) object(entry, "/policyBinding").put("policySha256", adapter.canonicalSha256(b.path("policy")));
        b.put("catalogSha256", adapter.canonicalSha256(b.path("catalog")));
    }
    private void gt(ObjectNode b, int index, String value, String call, String ad) {
        genotype(b, index).put("genotype", value).put("call", call).put("alleleDepth", ad).put("phased", value.contains("|"));
        object(facts(b), "/records/" + index).put("rawSampleValue", value + ":40:60:" + ad);
    }
    private void refuse(String name, Consumer<ObjectNode> mutation, String reason, boolean seal) throws Exception {
        ObjectNode input = base(); mutation.accept(input); if (seal) reseal(input);
        ObjectNode output = evaluate(input); archive(name, input, output);
        assertEquals(reason, output.at("/evaluationResult/reasonCode").asText(), output.toString());
        assertEquals("NOT_EVALUATED", output.at("/evaluationResult/status").asText());
        assertFalse(output.has("deNovoPoints")); assertFalse(output.has("evaluation"));
        assertFalse(output.at("/criterionEvaluations/0").has("evaluationId"));
        assertFalse(output.at("/criterionEvaluations/0").has("appliedStrength"));
        assertFalse(output.at("/criterionEvaluations/0").has("deterministicTrace"));
    }
    @TestFactory Stream<DynamicTest> fixedIndependentPointVectors() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (String background : List.of("alpha", "beta")) for (JsonNode vector : resource("expected.json").path("vectors")) {
            String name = background + "-" + vector.path("category").asText() + "-" + vector.path("heterogeneity").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                ObjectNode b = resource(background + "-bundle.json");
                clinical(b).put("phenotypeCategory", vector.path("category").asText()).put("heterogeneity", vector.path("heterogeneity").asText());
                reseal(b); ObjectNode before = b.deepCopy(); ObjectNode result = evaluate(b); archive(name, b, result);
                assertEquals(vector.path("points").decimalValue(), result.path("deNovoPoints").decimalValue());
                assertEquals(vector.path("status").asText(), result.at("/evaluation/evaluationResult/status").asText());
                assertEquals(vector.path("strength").asText(), result.at("/evaluation/appliedStrength").asText());
                assertEquals("CASE_OBSERVATION", result.at("/evidence/evidenceType").asText());
                assertEquals(1, result.path("observationCount").asInt()); assertEquals("CORE_RESULT_ONLY", result.path("resultMode").asText());
                assertFalse(result.has("runId")); assertFalse(result.has("analysisId")); assertEquals(before, b);
                assertEquals(result, mapper.readTree(mapper.writeValueAsBytes(result))); assertEquals(result, evaluate(b));
            }));
        }
        return tests.stream();
    }
    @Test void originalFixedBytesAndActualRecordAdaptationBothBackgrounds() throws Exception {
        for (String tag : List.of("alpha", "beta")) {
            byte[] raw;
            try (var input = getClass().getResourceAsStream("/ps2-denovo-core/r1/" + tag + "-bundle.json")) { assertNotNull(input); raw = input.readAllBytes(); }
            ObjectNode pins = resource(tag + "-pins.json");
            var bundle = Ps2DeNovoBundle.load(mapper, raw, pins.path("rawSha256").asText(), pins.path("canonicalSha256").asText());
            ObjectNode b = bundle.snapshot(), f = facts(b), before = f.deepCopy(); List<TrioVcfGenotypeRecord> records = new ArrayList<>();
            for (JsonNode r : f.path("records")) {
                JsonNode k = r.path("variantKey"), g = r.path("observation"); TrioRole role = TrioRole.valueOf(r.path("role").asText());
                records.add(new TrioVcfGenotypeRecord(new TrioVcfVariantKey(k.path("assembly").asText(), k.path("chromosome").asText(), k.path("position").asLong(), k.path("reference").asText(), k.path("alternate").asText()), role, r.path("sampleId").asText(), r.path("geneSymbol").asText(), r.path("recordFilter").asText(), r.path("sampleFilter").asText(), r.path("rawSampleValue").asText(), new TrioGenotypeObservation(role, g.path("genotype").asText(), TrioGenotypeCall.valueOf(g.path("call").asText()), g.path("depth").asInt(), g.path("genotypeQuality").asInt(), g.path("alleleDepth").asText())));
            }
            CaseFamilyObservation adapted = new CaseFamilyObservationAdapter(mapper).adapt(f, records);
            assertEquals(before, mapper.readTree(mapper.writeValueAsBytes(adapted.snapshot()))); assertEquals(before, f);
            adapted.snapshot().removeAll(); assertEquals(adapter.canonicalSha256(before), adapter.canonicalSha256(adapted.snapshot()));
            object(b, "/observations/0").set("facts", adapted.snapshot());
            ObjectNode result = evaluate(b); archive(tag + "-actual-record-adapter", b, result);
            assertEquals(resource("expected.json").at("/backgrounds/" + tag).decimalValue(), result.path("deNovoPoints").decimalValue());
            byte[] reference;
            try (var input = getClass().getResourceAsStream("/ps2-denovo-core/" + tag + "-reference.json")) { assertNotNull(input); reference = input.readAllBytes(); }
            assertEquals(Ps2DeNovoBundle.sha256(reference), b.at("/context/variant/normalization/referenceSequenceSha256").asText());
            ObjectNode missing = new CaseFamilyObservationAdapter(mapper).adapt(f, List.of(records.get(0))).snapshot();
            object(b, "/observations/0").set("facts", missing); reseal(b);
            assertEquals("PS2_THREE_DETECTIONS_REQUIRED", evaluate(b).at("/evaluationResult/reasonCode").asText());
        }
    }
    @TestFactory Stream<DynamicTest> prerequisiteRefusals() {
        List<DynamicTest> tests = new ArrayList<>();
        record Case(String name, Consumer<ObjectNode> change, String reason) { }
        List<Case> cases = List.of(
            new Case("missing-parent", b -> ((ArrayNode) facts(b).path("records")).remove(2), "PS2_THREE_DETECTIONS_REQUIRED"),
            new Case("low-quality", b -> detection(b, 1).put("quality", "LOW_QUALITY"), "PS2_DETECTION_QUALITY_INCOMPLETE"),
            new Case("no-coverage", b -> detection(b, 1).put("coverage", "NOT_COVERED"), "PS2_DETECTION_QUALITY_INCOMPLETE"),
            new Case("unknown-capability", b -> detection(b, 1).put("capability", "UNKNOWN"), "PS2_DETECTION_QUALITY_INCOMPLETE"),
            new Case("filter-fail", b -> object(facts(b), "/records/1").put("recordFilter", "LowQual"), "PS2_DETECTION_QUALITY_INCOMPLETE"),
            new Case("sample-filter-fail", b -> object(facts(b), "/records/2").put("sampleFilter", "LowQual"), "PS2_DETECTION_QUALITY_INCOMPLETE"),
            new Case("gt-call-conflict", b -> gt(b, 1, "0/1", "HOM_REF", "20,20"), "PS2_GT_CALL_CONFLICT"),
            new Case("partial", b -> gt(b, 1, "./1", "HEMI_ALT", "20,20"), "PS2_GT_PARTIAL_OR_MULTIALLELIC_OR_PLOIDY_UNSUPPORTED"),
            new Case("multi-alt", b -> gt(b, 1, "0/2", "HET_ALT", "20,20"), "PS2_GT_PARTIAL_OR_MULTIALLELIC_OR_PLOIDY_UNSUPPORTED"),
            new Case("haploid", b -> gt(b, 1, "1", "HEMI_ALT", "0,40"), "PS2_GT_PARTIAL_OR_MULTIALLELIC_OR_PLOIDY_UNSUPPORTED"),
            new Case("proband-hom-alt", b -> gt(b, 0, "1/1", "HOM_ALT", "0,40"), "PS2_PROBAND_HOM_ALT_UNSUPPORTED"),
            new Case("same-sample", b -> object(facts(b), "/members/2").set("sampleId", facts(b).at("/members/1/sampleId")), "PS2_MEMBER_OR_SAMPLE_CONFLICT"),
            new Case("same-member", b -> object(facts(b), "/members/2").set("memberId", facts(b).at("/members/1/memberId")), "PS2_MEMBER_OR_SAMPLE_CONFLICT"),
            new Case("one-parentage", b -> ((ArrayNode) facts(b).path("parentage")).remove(1), "PS2_BOTH_PARENTAGE_REQUIRED"),
            new Case("wrong-relationship-member", b -> object(facts(b), "/parentage/0").put("parentMemberId", "wrong-member"), "PS2_PARENTAGE_MEMBER_MISMATCH"),
            new Case("wrong-detection-member", b -> detection(b, 1).put("memberId", "wrong-member"), "PS2_DETECTION_MEMBER_MISMATCH"),
            new Case("phenotype-unknown", b -> clinical(b).put("phenotypeCategory", "UNKNOWN"), "PS2_PHENOTYPE_ASSESSMENT_UNKNOWN"),
            new Case("heterogeneity-unknown", b -> clinical(b).put("heterogeneity", "UNKNOWN"), "PS2_PHENOTYPE_ASSESSMENT_UNKNOWN"),
            new Case("history-unknown", b -> clinical(b).put("familyHistory", "UNKNOWN"), "PS2_CLINICAL_OR_FAMILY_HISTORY_INCOMPLETE"),
            new Case("no-phenotype", b -> clinical(b).putArray("phenotypeObservations"), "PS2_CLINICAL_OR_FAMILY_HISTORY_INCOMPLETE"),
            new Case("mosaic", b -> clinical(b).put("mosaicism", "KNOWN"), "PS2_MOSAICISM_SCOPE_UNSUPPORTED"),
            new Case("mosaic-unknown", b -> clinical(b).put("mosaicism", "UNKNOWN"), "PS2_MOSAICISM_SCOPE_UNSUPPORTED"),
            new Case("sibling", b -> clinical(b).put("siblingRecurrence", "PRESENT"), "PS2_SIBLING_RECURRENCE_SCOPE_UNSUPPORTED"),
            new Case("sibling-unknown", b -> clinical(b).put("siblingRecurrence", "UNKNOWN"), "PS2_SIBLING_RECURRENCE_SCOPE_UNSUPPORTED"),
            new Case("negative-plus-missing", b -> { gt(b, 1, "0/1", "HET_ALT", "20,20"); clinical(b).put("phenotypeCategory", "UNKNOWN"); }, "PS2_PHENOTYPE_ASSESSMENT_UNKNOWN"),
            new Case("proband-negative-plus-missing", b -> { gt(b, 0, "0/0", "HOM_REF", "40,0"); detection(b, 2).put("quality", "UNKNOWN"); }, "PS2_DETECTION_QUALITY_INCOMPLETE"),
            new Case("false-not-unaffected", b -> object(facts(b), "/members/0").put("affectedStatus", "UNKNOWN"), "PS2_PROBAND_IDENTITY_MISMATCH"),
            new Case("invalid-alt-index", b -> detection(b, 0).put("targetAltIndex", 2), "PS2_ALLELIC_SCOPE_UNSUPPORTED"),
            new Case("invalid-ploidy", b -> detection(b, 1).put("ploidy", 1), "PS2_ALLELIC_SCOPE_UNSUPPORTED"),
            new Case("ad-conflict", b -> gt(b, 0, "0/1", "HET_ALT", "40,0"), "PS2_AD_CONFLICT"),
            new Case("raw-gt-conflict", b -> object(facts(b), "/records/1").put("rawSampleValue", "0/1:40:60:20,20"), "PS2_RAW_GT_CONFLICT"),
            new Case("raw-dp-conflict", b -> genotype(b, 1).put("depth", 30), "PS2_RAW_SAMPLE_CONFLICT"),
            new Case("clinical-target", b -> object(clinical(b), "/binding").put("mondoId", "MONDO:0999999"), "PS2_CLINICAL_BINDING_MISMATCH"),
            new Case("source-ref-unknown", b -> detection(b, 1).putArray("sourceIds").add("MISSING"), "PS2_DETECTION_QUALITY_INCOMPLETE")
        );
        for (Case c : cases) tests.add(DynamicTest.dynamicTest(c.name(), () -> refuse(c.name(), c.change(), c.reason(), true)));
        for (String call : List.of("UNOBSERVED", "UNCONFIRMED_HOM_REF", "NO_CALL", "OTHER"))
            tests.add(DynamicTest.dynamicTest(call, () -> refuse(call, b -> genotype(b, 1).put("call", call), "PS2_GT_UNINFORMATIVE", true)));
        for (String status : List.of("UNCONFIRMED", "UNKNOWN", "CONFLICT")) for (int index : List.of(0, 1))
            tests.add(DynamicTest.dynamicTest("parentage-" + index + status, () -> refuse("parentage-" + index + status, b -> object(facts(b), "/parentage/" + index).put("status", status), "PS2_PARENTAGE_NOT_CONFIRMED_NO_PM6", true)));
        for (String part : List.of("quality", "parentage", "clinical", "scope"))
            tests.add(DynamicTest.dynamicTest("unreviewed-" + part, () -> refuse("unreviewed-" + part, b -> object(assessment(b), "/" + part).put("status", "UNREVIEWED"), "PS2_" + part.toUpperCase(java.util.Locale.ROOT) + "_ASSESSMENT_INCOMPLETE", true)));
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> exactIdentityAndPins() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String field : List.of("observationId", "contextId", "alleleId", "hgncId", "mondoId", "moi", "patientContextId", "familyContextId"))
            tests.add(DynamicTest.dynamicTest(field, () -> refuse("binding-" + field, b -> object(facts(b), "/binding").put(field, "WRONG"), "PS2_OBSERVATION_CONTEXT_MISMATCH", true)));
        for (String field : List.of("assembly", "reference", "alternate", "chromosome"))
            tests.add(DynamicTest.dynamicTest("record-" + field, () -> refuse("record-" + field, b -> object(facts(b), "/records/1/variantKey").put(field, "WRONG"), "PS2_RECORD_ALLELE_MISMATCH", true)));
        tests.add(DynamicTest.dynamicTest("position", () -> refuse("record-position", b -> object(facts(b), "/records/1/variantKey").put("position", 1), "PS2_RECORD_ALLELE_MISMATCH", true)));
        tests.add(DynamicTest.dynamicTest("stale-parent", () -> refuse("stale-parent", b -> genotype(b, 1).put("call", "HET_ALT"), "PS2_FACTS_PIN_MISMATCH", false)));
        tests.add(DynamicTest.dynamicTest("stale-parentage", () -> refuse("stale-parentage", b -> object(facts(b), "/parentage/0").put("status", "UNKNOWN"), "PS2_FACTS_PIN_MISMATCH", false)));
        tests.add(DynamicTest.dynamicTest("stale-phenotype", () -> refuse("stale-phenotype", b -> clinical(b).put("phenotypeCategory", "NOT_CONSISTENT"), "PS2_FACTS_PIN_MISMATCH", false)));
        tests.add(DynamicTest.dynamicTest("stale-source", () -> refuse("stale-source", b -> object(facts(b), "/sources/0").put("version", "2"), "PS2_FACTS_PIN_MISMATCH", false)));
        tests.add(DynamicTest.dynamicTest("stale-review", () -> refuse("stale-review", b -> object(assessment(b), "/scope").put("basis", "changed"), "PS2_ASSESSMENT_PIN_MISMATCH", false)));
        tests.add(DynamicTest.dynamicTest("stale-policy", () -> refuse("stale-policy", b -> object(b, "/policy/review").put("reviewId", "changed"), "POLICY_INTEGRITY_MISMATCH", false)));
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> selectorGaps() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String disposition : List.of("SPECIALIZED_UNIMPLEMENTED", "SPECIALIZED_IMPLEMENTED", "SPECIALIZED_VERSION_CONFLICT", "SPECIALIZED_UNRESOLVED")) {
            String reason = switch (disposition) { case "SPECIALIZED_UNIMPLEMENTED" -> "APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED"; case "SPECIALIZED_IMPLEMENTED" -> "PS2_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED"; case "SPECIALIZED_VERSION_CONFLICT" -> "APPLICABLE_SPECIALIZED_VERSION_CONFLICT"; default -> "APPLICABLE_SPECIALIZED_CLAUSE_UNRESOLVED"; };
            tests.add(DynamicTest.dynamicTest(disposition, () -> refuse(disposition, b -> object(b, "/catalog/entries/0").put("disposition", disposition), reason, true)));
        }
        tests.add(DynamicTest.dynamicTest("uncovered", () -> refuse("uncovered", b -> object(b, "/catalog").putArray("entries"), "CLAUSE_SEARCH_SCOPE_NOT_COVERED", true)));
        tests.add(DynamicTest.dynamicTest("incomplete", () -> refuse("incomplete", b -> object(b, "/catalog/entries/0").put("searchStatus", "INCOMPLETE"), "CLAUSE_SEARCH_INCOMPLETE", true)));
        tests.add(DynamicTest.dynamicTest("failed-search", () -> refuse("failed-search", b -> object(b, "/catalog/entries/0").put("searchStatus", "FAILED"), "CLAUSE_SEARCH_FAILED", true)));
        tests.add(DynamicTest.dynamicTest("disabled-policy", () -> refuse("disabled-policy", b -> object(b, "/policy/review").put("status", "DISABLED"), "POLICY_NOT_REVIEWED_IMPLEMENTED", true)));
        tests.add(DynamicTest.dynamicTest("unknown-specialized-tx", () -> refuse("unknown-specialized-tx", b -> {
            ObjectNode special = object(b, "/catalog/entries/0").deepCopy().put("entryId", "special").put("disposition", "SPECIALIZED_UNIMPLEMENTED");
            object(special, "/scope").put("transcriptMode", "EXACT_ACCESSIONS").putArray("transcripts").addObject().put("accession", "NM_000001").put("version", 1);
            ((ArrayNode) b.at("/catalog/entries")).add(special);
        }, "POSSIBLE_SPECIALIZED_TRANSCRIPT_SCOPE_UNRESOLVED", true)));
        return tests.stream();
    }
    @Test void negativeAndMissingMetricsAndStableOccurrence() throws Exception {
        for (int index : List.of(0, 1, 2)) {
            ObjectNode b = base(); gt(b, index, index == 0 ? "0/0" : "0/1", index == 0 ? "HOM_REF" : "HET_ALT", index == 0 ? "40,0" : "20,20"); reseal(b);
            ObjectNode result = evaluate(b); archive("complete-negative-" + index, b, result);
            assertEquals("NOT_MET", result.at("/evaluation/evaluationResult/status").asText()); assertEquals(0, result.path("deNovoPoints").asInt());
        }
        ObjectNode b = base(); genotype(b, 1).putNull("alleleDepth"); object(facts(b), "/records/1").put("rawSampleValue", "0/0:40:60:."); reseal(b);
        assertEquals("MET", evaluate(b).at("/evaluation/evaluationResult/status").asText()); // Valid GT plus explicit independent detection review, not AD inference.
        ObjectNode source = object(facts(b), "/sources/0").deepCopy().put("sourceId", "SECOND_REFERENCE").put("recordId", "TEST_SECOND_REFERENCE").put("locator", "TEST_ONLY second reference same observation");
        ((ArrayNode) facts(b).path("sources")).add(source); reseal(b); ObjectNode result = evaluate(b); archive("two-references-one-occurrence", b, result);
        assertEquals(1, result.path("observationCount").asInt()); assertEquals(2, result.path("deNovoPoints").asInt());
        for (int count : List.of(2, 4)) {
            ObjectNode multi = b.deepCopy(); while (multi.path("observations").size() < count) ((ArrayNode) multi.path("observations")).add(multi.at("/observations/0").deepCopy());
            assertEquals("PS2_SINGLE_OBSERVATION_REQUIRED_NO_AGGREGATION", evaluate(multi).at("/evaluationResult/reasonCode").asText());
        }
    }
    @Test void nfcPreservesRawAndNewReviewHasNewIdentity() throws Exception {
        ObjectNode b = base(), nfd = b.deepCopy();
        object(nfd, "/context/disease").put("label", b.at("/context/disease/label").asText().replace("é", "e\u0301"));
        object(assessment(nfd), "/clinical").put("basis", "TEST_ONLY Café review"); reseal(nfd);
        ObjectNode equivalent = nfd.deepCopy(); object(assessment(equivalent), "/clinical").put("basis", "TEST_ONLY Cafe\u0301 review");
        ObjectNode before = equivalent.deepCopy(); assertEquals(evaluate(nfd).path("evaluation"), evaluate(equivalent).path("evaluation")); assertEquals(before, equivalent);
        ObjectNode originalResult = evaluate(b); clinical(b).put("phenotypeCategory", "CONSISTENT");
        assertEquals("PS2_FACTS_PIN_MISMATCH", evaluate(b).at("/evaluationResult/reasonCode").asText());
        assessment(b).put("assessmentId", "TEST_ONLY_NEW_REVIEW"); reseal(b); ObjectNode reviewed = evaluate(b);
        assertEquals("MODERATE", reviewed.at("/evaluation/appliedStrength").asText());
        assertNotEquals(originalResult.at("/evaluation/evaluationId"), reviewed.at("/evaluation/evaluationId"));
    }
    @Test void strictBytesAndForbiddenExternalVerdicts() throws Exception {
        ObjectNode b = base(); byte[] valid = mapper.writeValueAsBytes(b);
        assertThrows(IllegalArgumentException.class, () -> Ps2DeNovoBundle.load(mapper, valid, "0".repeat(64), adapter.canonicalSha256(b)));
        assertThrows(IllegalArgumentException.class, () -> Ps2DeNovoBundle.load(mapper, valid, Ps2DeNovoBundle.sha256(valid), "0".repeat(64)));
        for (String input : List.of("{} {}", "{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{\"x\":1e9999}", "{\"é\":1,\"e\\u0301\":2}", "{\"x\":".repeat(34) + "{}" + "}".repeat(34))) {
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8); assertThrows(IllegalArgumentException.class, () -> Ps2DeNovoBundle.load(mapper, bytes, Ps2DeNovoBundle.sha256(bytes), "0".repeat(64)));
        }
        byte[] large = new byte[Ps2DeNovoBundle.MAX_BYTES + 1]; assertThrows(IllegalArgumentException.class, () -> Ps2DeNovoBundle.load(mapper, large, Ps2DeNovoBundle.sha256(large), "0".repeat(64)));
        ObjectMapper permissive = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES);
        for (String nonJson : List.of("{/*comment*/\"x\":1}", "{'x':1}")) {
            byte[] raw = nonJson.getBytes(StandardCharsets.UTF_8);
            var error = assertThrows(IllegalArgumentException.class, () -> Ps2DeNovoBundle.load(permissive, raw, Ps2DeNovoBundle.sha256(raw), "0".repeat(64)));
            assertEquals("PS2_JSON_INVALID", error.getMessage());
        }
        for (String pointer : List.of("", "/policy", "/observations/0/facts", "/observations/0/assessment", "/catalog", "/context")) {
            ObjectNode changed = base(); object(changed, pointer).put("allowPs2", true); assertThrows(IllegalArgumentException.class, () -> load(changed));
        }
        for (String field : List.of("points", "appliedStrength", "hpoSimilarity", "exomiserScore", "DE_NOVO_CANDIDATE")) {
            ObjectNode changed = base(); object(changed, "/policy").put(field, 100); assertThrows(IllegalArgumentException.class, () -> load(changed));
        }
        ObjectNode bad = base(); clinical(bad).put("phenotypeCategory", "MADE_UP"); assertThrows(IllegalArgumentException.class, () -> load(bad));
        ObjectNode wrong = base(); detection(wrong, 1).put("targetAltIndex", "1"); assertThrows(IllegalArgumentException.class, () -> load(wrong));
        ObjectNode nil = base(); clinical(nil).putNull("familyHistory"); assertThrows(IllegalArgumentException.class, () -> load(nil));
    }
    @Test void oldSelectorRemainsV2AndSuccessorRejectsOtherCriteria() throws Exception {
        ObjectNode b = base(); ObjectNode context = object(b, "/context");
        assertThrows(IllegalArgumentException.class, () -> adapter.validateCompleteContext(context));
        ObjectNode legacy;
        try (var input = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) { assertNotNull(input); legacy = (ObjectNode) mapper.readTree(input); }
        ObjectNode oldContext = object(legacy, "/context"); adapter.validateCompleteContext(oldContext);
        assertEquals(legacy.path("expectedAtomicEvidence"), adapter.adapt(oldContext, object(legacy, "/carrier")));
        oldContext.remove("transcript"); assertThrows(IllegalArgumentException.class, () -> adapter.validateCompleteContext(oldContext));
        var selector = AcmgSpecificationSelector.forPs2Versioned(mapper, object(b, "/catalog"), b.path("catalogSha256").asText(), mapper.createArrayNode().add(b.path("policy")));
        assertNotNull(selector.select(context, Ps2DeNovoCriterionEvaluator.CRITERION).selectedPolicy());
        assertEquals("CRITERION_OUTSIDE_ENABLED_SCOPE", selector.select(context, "ACMG_AMP_2015:BP1").receipt().path("reasonCode").asText());
        var oldSelector = new AcmgSpecificationSelector(mapper, object(b, "/catalog"), b.path("catalogSha256").asText(), mapper.createArrayNode().add(b.path("policy")));
        assertEquals("CRITERION_OUTSIDE_ENABLED_SCOPE", oldSelector.select(context, Ps2DeNovoCriterionEvaluator.CRITERION).receipt().path("reasonCode").asText());
    }
    @Test void inheritedRegistryEntriesAndAuthorityBytesAreUnchanged() throws Exception {
        JsonNode previous, current;
        try (var input = getClass().getResourceAsStream("/acmg/rule-registry-v2/acmg-rule-registry-bp1-missense-2026-09-28.json")) { assertNotNull(input); previous = mapper.readTree(input); }
        try (var input = getClass().getResourceAsStream(Ps2DeNovoCriterionEvaluator.REGISTRY)) { assertNotNull(input); current = mapper.readTree(input); }
        assertNotNull(previous); assertNotNull(current);
        assertEquals(previous.path("authorityCatalogSha256"), current.path("authorityCatalogSha256"));
        for (int i = 0; i < previous.path("criteria").size(); i++) {
            JsonNode old = previous.path("criteria").get(i), next = current.path("criteria").get(i); assertNotNull(old); assertNotNull(next);
            if ("PS2".equals(old.path("code").asText())) { assertEquals(old.path("currentGeneral"), next.path("currentGeneral")); assertTrue(next.at("/currentGeneral/allowedStrengths").toString().contains("VERY_STRONG")); }
            else assertEquals(old, next);
        }
    }

    private void rebindContext(ObjectNode b) throws Exception {
        ObjectNode c = object(b, "/context"), v = object(c, "/variant"), f = facts(b), binding = object(f, "/binding");
        v.put("alleleId", adapter.canonicalAlleleId(v.path("normalizedAllele"), v.path("normalization")));
        c.put("contextId", adapter.canonicalVersionedContextId(c));
        binding.put("contextId", c.path("contextId").asText()).put("alleleId", v.path("alleleId").asText())
                .put("moi", c.path("moi").asText()).set("allele", v.path("evidenceQueryAllele").deepCopy());
        for (JsonNode member : f.path("members")) object(member, "/detection").set("binding", binding.deepCopy());
        for (JsonNode p : f.path("parentage")) ((ObjectNode) p).set("binding", binding.deepCopy());
        clinical(b).set("binding", binding.deepCopy());
        reseal(b);
    }
    @TestFactory Stream<DynamicTest> supportedBoundaryIsNotGeneralDeNovo() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String moi : List.of("AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE"))
            tests.add(DynamicTest.dynamicTest(moi, () -> {
                ObjectNode b = base(); object(b, "/context").put("moi", moi); rebindContext(b);
                ObjectNode result = evaluate(b); archive(moi, b, result);
                assertEquals("PS2_NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED", result.at("/evaluationResult/reasonCode").asText());
            }));
        for (String variant : List.of("MNV", "SMALL_INSERTION", "SMALL_DELETION", "SMALL_DELINS"))
            tests.add(DynamicTest.dynamicTest(variant, () -> {
                ObjectNode b = base(); object(b, "/context/variant").put("variantType", variant);
                for (String kind : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) object(b, "/context/variant/" + kind).put("alternate", "AG");
                rebindContext(b); ObjectNode result = evaluate(b); archive(variant, b, result);
                assertEquals("PS2_NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED", result.at("/evaluationResult/reasonCode").asText());
            }));
        tests.add(DynamicTest.dynamicTest("GRCh37", () -> {
            ObjectNode b = base(); for (String kind : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) object(b, "/context/variant/" + kind).put("assembly", "GRCh37");
            rebindContext(b); assertEquals("PS2_NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED", evaluate(b).at("/evaluationResult/reasonCode").asText());
        }));
        tests.add(DynamicTest.dynamicTest("projection", () -> {
            ObjectNode b = base(), v = object(b, "/context/variant");
            object(v, "/inputAllele").put("assembly", "GRCh37"); object(v, "/normalizedAllele").put("assembly", "GRCh37");
            ObjectNode mapping = object(v, "/assemblyLineage").put("mappingStatus", "MAPPED").putObject("coordinateMapping");
            mapping.set("sourceAllele", v.path("normalizedAllele").deepCopy()); mapping.set("targetAllele", v.path("evidenceQueryAllele").deepCopy());
            mapping.put("method", "TEST_ONLY_projection").put("methodVersion", "1").put("refAltValidation", "MATCH");
            rebindContext(b); ObjectNode result = evaluate(b); archive("projection", b, result);
            assertEquals("PS2_NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED", result.at("/evaluationResult/reasonCode").asText());
        }));
        for (String variant : List.of("CNV", "MULTIALLELIC")) tests.add(DynamicTest.dynamicTest(variant, () -> {
            ObjectNode b = base(); object(b, "/context/variant").put("variantType", variant);
            assertThrows(IllegalArgumentException.class, () -> load(b));
        }));
        tests.add(DynamicTest.dynamicTest("mitochondrial", () -> {
            ObjectNode b = base(); object(b, "/context/variant/evidenceQueryAllele").put("contig", "MT");
            assertThrows(IllegalArgumentException.class, () -> load(b));
        }));
        return tests.stream();
    }
    @Test void sourceBytesMustDescribeBoundMembersNotMerelyHaveValidHashes() throws Exception {
        ObjectNode b = base(); ObjectNode source = object(facts(b), "/sources/0");
        source.put("rawSha256", "0".repeat(64)); repinWithoutSourceRewriting(b);
        assertEquals("PS2_SOURCE_RAW_PIN_MISMATCH", evaluate(b).at("/evaluationResult/reasonCode").asText());
        b = base(); source = object(facts(b), "/sources/0"); ObjectNode wrongReport = (ObjectNode) mapper.readTree(source.path("rawUtf8").asText());
        object(wrongReport, "/parentage/0").put("parentMemberId", "wrong-person-in-report");
        String raw = mapper.writeValueAsString(wrongReport); source.put("rawUtf8", raw).put("rawSha256", Ps2DeNovoBundle.sha256(raw.getBytes(StandardCharsets.UTF_8)))
                .put("canonicalSha256", adapter.canonicalSha256(wrongReport)); repinWithoutSourceRewriting(b);
        ObjectNode result = evaluate(b); archive("valid-hashes-wrong-person-report", b, result);
        assertEquals("PS2_SOURCE_FACTS_MISMATCH", result.at("/evaluationResult/reasonCode").asText());
        ObjectNode invalid = base(); object(invalid, "/registryBinding").put("registrySha256", "0".repeat(64));
        assertEquals("PS2_REGISTRY_OR_AUTHORITY_INVALID", evaluate(invalid).at("/evaluationResult/reasonCode").asText());
    }
    private void repinWithoutSourceRewriting(ObjectNode b) {
        ObjectNode a = assessment(b); a.put("factsSha256", adapter.canonicalSha256(facts(b))); a.remove("contentSha256"); a.put("contentSha256", adapter.canonicalSha256(a));
        object(b, "/policy").put("factsSha256", adapter.canonicalSha256(facts(b))).put("assessmentSha256", adapter.canonicalSha256(a)); pinCatalog(b);
    }

    @Test @SuppressWarnings("removal") // JDK 21 test-only fail-closed network/exec probe; no product or null-analysis suppression.
    void realCoreRunsUnderFailingNetworkExecAndScientificDependencyGuards() throws Exception {
        Path actual = Ps2TestDirectory.create("execution-probe");
        assertTrue(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().startsWith(actual.getParent().getParent()));
        ObjectNode b = base(); byte[] bytes = mapper.writeValueAsBytes(b);
        String raw = Ps2DeNovoBundle.sha256(bytes), canonical = adapter.canonicalSha256(b);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        SecurityManager previous = System.getSecurityManager();
        SecurityManager guard = new SecurityManager() {
            @Override public void checkPermission(java.security.Permission permission) { }
            @Override public void checkConnect(String host, int port) { attempts.incrementAndGet(); throw new SecurityException("PS2_NETWORK_FORBIDDEN"); }
            @Override public void checkExec(String command) { attempts.incrementAndGet(); throw new SecurityException("PS2_EXEC_FORBIDDEN"); }
        };
        // Load each production core class in this child; all other project suppliers/DB classes are denied.
        var allowed = java.util.Set.of("FamilyDeNovoJson", "FamilyDeNovoPrerequisites", "Ps2DeNovoBundle", "Ps2DeNovoPolicy", "Ps2DeNovoCriterionEvaluator", "AcmgSpecificationSelector", "RuntimeEvidenceV2Adapter", "CaseFamilyObservation");
        var deniedLoads = new java.util.concurrent.atomic.AtomicInteger();
        try (var isolated = new java.net.URLClassLoader(new java.net.URL[] { getClass().getProtectionDomain().getCodeSource().getLocation() }, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.sql.") || name.startsWith("javax.sql.")) { deniedLoads.incrementAndGet(); throw new ClassNotFoundException("DB forbidden"); }
                if (!name.startsWith("org.nbscn.varvis.")) return super.loadClass(name, resolve);
                String simple = name.substring(name.lastIndexOf('.') + 1).split("\\$")[0];
                if (!allowed.contains(simple)) { deniedLoads.incrementAndGet(); throw new ClassNotFoundException("Scientific/project supplier forbidden: " + name); }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name); if (loaded == null) loaded = findClass(name); if (resolve) resolveClass(loaded); return loaded;
                }
            }
        }) {
            assertThrows(ClassNotFoundException.class, () -> isolated.loadClass("org.nbscn.varvis.variantanalysis.provider.CodingMissenseResolver"));
            assertThrows(ClassNotFoundException.class, () -> isolated.loadClass("java.sql.DriverManager"));
            assertEquals(2, deniedLoads.get());
            System.setSecurityManager(guard);
            assertThrows(SecurityException.class, () -> guard.checkConnect("127.0.0.1", 1));
            assertThrows(SecurityException.class, () -> guard.checkExec("TEST_ONLY_NOT_EXECUTED"));
            assertEquals(2, attempts.get());
            Class<?> bundleClass = isolated.loadClass("org.nbscn.varvis.variantanalysis.provider.Ps2DeNovoBundle");
            Object bundle = bundleClass.getMethod("load", ObjectMapper.class, byte[].class, String.class, String.class).invoke(null, mapper, bytes, raw, canonical);
            Class<?> evaluator = isolated.loadClass("org.nbscn.varvis.variantanalysis.provider.Ps2DeNovoCriterionEvaluator");
            Object instance = evaluator.getConstructor(ObjectMapper.class).newInstance(mapper);
            ObjectNode result = (ObjectNode) evaluator.getMethod("evaluate", bundleClass).invoke(instance, bundle);
            assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
            assertEquals(2, attempts.get()); assertEquals(2, deniedLoads.get());
            archive("guarded-real-core", b, result);
        } finally { System.setSecurityManager(previous); }
    }
}
