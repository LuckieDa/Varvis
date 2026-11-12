package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Bp2TestMaterials.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class Bp2PhaseCoreTest {
    private interface Mutation { void apply(ObjectNode root) throws Exception; }
    private record Vector(String name, String reason, Mutation mutation) { }
    private static void minimal(ObjectNode result, String reason) {
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result::toPrettyString);
        assertEquals(reason, result.at("/evaluationResult/reasonCode").asText());
        for (String field : List.of("bp2Points", "deNovoPoints", "evidence", "evaluation", "evaluationId", "strength", "deterministicTrace")) assertFalse(result.has(field), field);
        JsonNode criterion = result.at("/criterionEvaluations/0");
        assertEquals(3, criterion.size()); assertFalse(result.path("classificationEnabled").asBoolean()); assertFalse(result.path("adoptionSupported").asBoolean());
    }
    private static void met(ObjectNode result) {
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result::toPrettyString);
        assertFalse(result.has("bp2Points")); assertEquals("SUPPORTING", result.at("/evaluation/appliedStrength").asText());
        assertEquals("COMPLETED", result.at("/evaluation/deterministicTrace/executionStatus").asText());
        assertTrue(result.at("/evaluation/evaluationId").asText().startsWith("evaluation-sha256-"));
        assertTrue(new RuntimeEvidenceV2Adapter(M).savedAtomicIdentityMatches(object(result, "/evidence")));
    }
    @TestFactory Stream<DynamicTest> fixedIndependentBackgrounds() {
        return Stream.of("alpha", "beta", "gamma").flatMap(tag -> Stream.of(false, true).map(phased -> DynamicTest.dynamicTest(tag + "-phased-" + phased, () -> {
            ObjectNode root = base(tag), pins = (ObjectNode) M.readTree(resource(tag + "-pins.json"));
            assertEquals(pins.path("rawSha256").asText(), raw(resource(tag + "-bundle.json")));
            assertEquals(pins.path("canonicalSha256").asText(), hash(root));
            JsonNode expected = M.readTree(resource(tag + "-expected.json"));
            if (phased) {
                for (JsonNode r : facts(root).path("records")) call((ObjectNode) r, r.at("/observation/genotype").asText().replace('/', '|'));
                reissue(root, "facts");
            }
            ObjectNode before = facts(root).deepCopy(), adapted = throughAdapter(before);
            // Adapter emits Java long positions; parser uses IntNode when small. JSON numbers are unchanged.
            assertEquals(hash(before), hash(adapted)); observation(root).set("facts", adapted);
            ObjectNode result = evaluate(root); met(result);
            assertEquals(expected.path("contextId"), result.at("/evaluation/contextId"));
            assertEquals(expected.path("pairId"), facts(root).at("/binding/pairId"));
            assertEquals(expected.path("derivedPhase"), result.path("derivedPhase"));
            assertEquals(expected.path("direction"), result.path("direction"));
            assertEquals(expected.path("strength"), result.at("/evaluation/appliedStrength"));
            assertEquals(expected.path("status"), result.at("/evaluation/evaluationResult/status"));
            assertEquals(expected.path("caseId"), result.path("caseId"));
            assertEquals("PATHOGENIC", observation(root).at("/partnerClassification/classification").asText());
            assertEquals(result, evaluate(root)); assertEquals(hash(before), hash(facts(root)));
            var loaded = load(root); ObjectNode copy = loaded.snapshot(); copy.removeAll(); assertEquals(hash(root), hash(loaded.snapshot()));
            artifact(tag + "-phased-" + phased, root, result);
        })));
    }
    private static Vector science(String name, String reason, String part, Mutation mutation) {
        return new Vector(name, reason, root -> { mutation.apply(root); reissue(root, part); });
    }
    private static Vector fact(String name, String reason, Mutation mutation) { return science(name, reason, "facts", mutation); }
    private static Vector field(String name, String reason, String part, String pointer, String field, String value) {
        return science(name, reason, part, root -> object(observation(root), pointer).put(field, value));
    }
    @TestFactory Stream<DynamicTest> boundedNegativeMatrix() {
        List<Vector> vectors = new ArrayList<>();
        vectors.add(fact("missing-sixth-record", "BP2_SIX_RECORDS_REQUIRED", r -> ((ArrayNode) facts(r).path("records")).remove(5)));
        vectors.add(fact("duplicate-record", "BP2_RECORD_BINDING_CONFLICT", r -> ((ArrayNode) facts(r).path("records")).add(facts(r).at("/records/0").deepCopy())));
        vectors.add(fact("wrong-sample", "BP2_RECORD_BINDING_CONFLICT", r -> object(facts(r), "/records/2").put("sampleId", "other")));
        vectors.add(fact("wrong-record-allele", "BP2_RECORD_BINDING_CONFLICT", r -> object(facts(r), "/records/2/variantKey").put("position", 99)));
        vectors.add(fact("wrong-call-role", "BP2_CALL_MISSING_OR_ROLE_CONFLICT", r -> object(facts(r), "/records/2/observation").put("role", "MOTHER")));
        vectors.add(fact("raw-GT-conflict", "BP2_RAW_GT_CONFLICT", r -> object(facts(r), "/records/0/observation").put("genotype", "1/0")));
        vectors.add(fact("phased-bool-conflict", "BP2_RAW_GT_CONFLICT", r -> object(facts(r), "/records/0/observation").put("phased", true)));
        vectors.add(fact("raw-FT-fail-structured-pass", "BP2_RAW_FT_MISSING_OR_FAILED", r -> object(facts(r), "/records/2").put("rawSampleValue", "0/1:52:60:26,26:LowQual")));
        vectors.add(fact("FT-fail-consistent", "BP2_FILTER_FAILED", r -> { object(facts(r), "/records/2").put("sampleFilter", "LowQual").put("rawSampleValue", "0/1:52:60:26,26:LowQual"); }));
        vectors.add(fact("missing-FT", "BP2_RAW_FT_MISSING_OR_FAILED", r -> { object(facts(r), "/detections/0").put("sampleFormat", "GT:DP:GQ:AD"); object(facts(r), "/records/0").put("rawSampleValue", "0/1:44:60:22,22"); }));
        vectors.add(fact("raw-FORMAT-count", "BP2_RAW_FORMAT_CONFLICT", r -> object(facts(r), "/detections/0").put("sampleFormat", "GT:DP:GQ:AD:FT:PS")));
        vectors.add(fact("duplicate-FORMAT", "BP2_RAW_FORMAT_CONFLICT", r -> object(facts(r), "/detections/0").put("sampleFormat", "GT:DP:GQ:AD:GT")));
        vectors.add(fact("no-call", "BP2_GT_INCOMPLETE_OR_UNSUPPORTED", r -> object(facts(r), "/records/0/observation").put("genotype", "./.").put("call", "NO_CALL")));
        vectors.add(fact("DP-missing", "BP2_RAW_QUALITY_MISSING_OR_CONFLICT", r -> object(facts(r), "/records/0/observation").putNull("depth")));
        vectors.add(fact("GQ-conflict", "BP2_RAW_QUALITY_MISSING_OR_CONFLICT", r -> object(facts(r), "/records/0/observation").put("genotypeQuality", 5)));
        vectors.add(fact("negative-GQ", "BP2_RAW_QUALITY_INVALID", r -> { object(facts(r), "/records/0/observation").put("genotypeQuality", -1); object(facts(r), "/records/0").put("rawSampleValue", "0/1:44:-1:22,22:PASS"); }));
        vectors.add(fact("AD-no-alt", "BP2_AD_CALL_CONFLICT", r -> { object(facts(r), "/records/0/observation").put("alleleDepth", "44,0"); object(facts(r), "/records/0").put("rawSampleValue", "0/1:44:60:44,0:PASS"); }));
        vectors.add(fact("call-label-conflict", "BP2_GT_CALL_CONFLICT", r -> object(facts(r), "/records/0/observation").put("call", "HOM_REF")));
        vectors.add(field("record-filter", "BP2_FILTER_FAILED", "facts", "/facts/records/0", "recordFilter", "LowQual"));
        for (String[] pair : List.of(new String[]{"coverage", "UNKNOWN"}, new String[]{"quality", "LOW_QUALITY"}, new String[]{"capability", "UNKNOWN"}))
            vectors.add(field("detection-" + pair[0], "BP2_DETECTION_INCOMPLETE_OR_CONFLICT", "facts", "/facts/detections/1", pair[0], pair[1]));
        vectors.add(fact("duplicate-detection", "BP2_DETECTION_INCOMPLETE_OR_CONFLICT", r -> ((ArrayNode) facts(r).path("detections")).add(facts(r).at("/detections/0").deepCopy())));
        vectors.add(fact("detection-source-missing", "BP2_DETECTION_INCOMPLETE_OR_CONFLICT", r -> object(facts(r), "/detections/0").putArray("sourceIds").add("missing")));
        for (long alt : new long[]{2, 4294967297L, -4294967295L}) vectors.add(fact("ALT-" + alt, "BP2_DETECTION_INCOMPLETE_OR_CONFLICT", r -> object(facts(r), "/detections/0").put("targetAltIndex", alt)));
        for (long ploidy : new long[]{3, 4294967298L, -4294967294L}) vectors.add(fact("ploidy-" + ploidy, "BP2_DETECTION_INCOMPLETE_OR_CONFLICT", r -> object(facts(r), "/detections/0").put("ploidy", ploidy)));
        vectors.add(fact("single-parent", "BP2_COMPLETE_TRIO_REQUIRED", r -> ((ArrayNode) facts(r).path("members")).remove(2)));
        for (String key : List.of("memberId", "sampleId")) vectors.add(fact("NFC-duplicate-" + key, "BP2_MEMBERS_NOT_DISTINCT", r -> { object(facts(r), "/members/1").put(key, "parent-é"); object(facts(r), "/members/2").put(key, "parent-e\u0301"); }));
        vectors.add(field("unconfirmed-parentage", "BP2_PARENTAGE_NOT_CONFIRMED", "facts", "/facts/parentage/0", "conclusion", "UNCONFIRMED"));
        vectors.add(field("parentage-unknown-method", "BP2_PARENTAGE_NOT_CONFIRMED", "facts", "/facts/parentage/0", "method", "UNKNOWN"));
        vectors.add(field("detection-unknown-method", "BP2_DETECTION_INCOMPLETE_OR_CONFLICT", "facts", "/facts/detections/0", "method", "UNKNOWN"));
        vectors.add(field("parentage-invalid-result", "BP2_PARENTAGE_NOT_CONFIRMED", "facts", "/facts/parentage/0", "result", "UNKNOWN"));
        vectors.add(field("parentage-unreviewed", "BP2_PARENTAGE_NOT_CONFIRMED", "facts", "/facts/parentage/0/review", "status", "UNREVIEWED"));
        vectors.add(fact("one-parentage", "BP2_BOTH_PARENTAGE_CONFIRMATIONS_REQUIRED", r -> ((ArrayNode) facts(r).path("parentage")).remove(1)));
        vectors.add(field("clinical-unknown", "BP2_CLINICAL_APPLICABILITY_INCOMPLETE", "facts", "/facts/clinical", "diseaseApplicability", "UNKNOWN"));
        vectors.add(field("mosaic", "BP2_CLINICAL_APPLICABILITY_INCOMPLETE", "facts", "/facts/clinical", "mosaicism", "KNOWN"));
        vectors.add(fact("homozygous", "BP2_TWO_PROBAND_HET_CALLS_REQUIRED", r -> call(object(facts(r), "/records/0"), "1/1")));
        vectors.add(fact("unresolved-phase", "BP2_PHASE_UNRESOLVED", r -> call(object(facts(r), "/records/4"), "0/1")));
        vectors.add(field("phase-unknown", "BP2_PHASE_DECLARATION_CONFLICT", "phase", "/phase", "conclusion", "UNKNOWN"));
        vectors.add(field("phase-parent-conflict", "BP2_PHASE_DECLARATION_CONFLICT", "phase", "/phase", "targetParent", "MOTHER"));
        vectors.add(field("phase-GT-only", "BP2_PHASE_DECLARATION_CONFLICT", "phase", "/phase", "method", "PHASED_GT_ONLY"));
        vectors.add(field("phase-locator-conflict", "BP2_PHASE_RECORD_LOCATOR_CONFLICT", "phase", "/phase/recordRefs/0", "sampleId", "other"));
        vectors.add(science("phase-locator-missing", "BP2_PHASE_RECORD_LOCATOR_INCOMPLETE", "phase", r -> ((ArrayNode) observation(r).at("/phase/recordRefs")).remove(0)));
        for (String classification : List.of("LIKELY_PATHOGENIC", "VUS", "BENIGN", "LIKELY_BENIGN", "UNKNOWN")) vectors.add(field("partner-" + classification, "BP2_PARTNER_CLASSIFICATION_UNSUITABLE", "partnerClassification", "/partnerClassification", "classification", classification));
        vectors.add(field("partner-conflict", "BP2_PARTNER_CLASSIFICATION_UNSUITABLE", "partnerClassification", "/partnerClassification/assertion", "conflict", "CONFLICT"));
        vectors.add(field("partner-label-only", "BP2_PARTNER_CLASSIFICATION_UNSUITABLE", "partnerClassification", "/partnerClassification/assertion", "status", "UNREVIEWED"));
        vectors.add(field("partner-unreviewed", "BP2_PARTNER_CLASSIFICATION_UNSUITABLE", "partnerClassification", "/partnerClassification/review", "status", "UNREVIEWED"));
        vectors.add(field("partner-assertion-changed", "BP2_PARTNER_CLASSIFICATION_UNSUITABLE", "partnerClassification", "/partnerClassification/assertion", "classification", "VUS"));
        vectors.add(field("partner-independence-unknown", "BP2_CLASSIFICATION_INDEPENDENCE_UNRESOLVED", "partnerClassification", "/partnerClassification/independence", "conclusion", "UNKNOWN"));
        for (String contribution : List.of("targetContribution", "pairContribution", "caseContribution", "bp2Contribution", "compoundContribution")) vectors.add(field("dependent-" + contribution, "BP2_CLASSIFICATION_DEPENDENT_OR_UNKNOWN", "partnerClassification", "/partnerClassification/independence", contribution, "PRESENT"));
        vectors.add(field("dependency-current-BP2", "BP2_CLASSIFICATION_CIRCULAR", "partnerClassification", "/partnerClassification/independence/dependencies/0", "kind", "CURRENT_BP2"));
        vectors.add(science("dependency-current-case", "BP2_CLASSIFICATION_CIRCULAR", "partnerClassification", r -> object(observation(r), "/partnerClassification/independence/dependencies/0").put("identity", facts(r).path("caseId").asText())));
        vectors.add(new Vector("duplicate-occurrence", "BP2_SINGLE_OBSERVATION_REQUIRED_NO_AGGREGATION", r -> ((ArrayNode) r.path("observations")).add(observation(r).deepCopy())));
        vectors.add(new Vector("empty-observations", "BP2_SINGLE_OBSERVATION_REQUIRED_NO_AGGREGATION", r -> r.putArray("observations")));
        vectors.add(new Vector("stale-policy-inner-pin", "BP2_PART_PIN_MISMATCH", r -> { object(observation(r), "/phase/review").put("basis", "new TEST_ONLY review"); source(object(observation(r), "/phase")); }));
        vectors.add(new Vector("stale-assessment-pin", "BP2_ASSESSMENT_PIN_MISMATCH", r -> object(observation(r), "/assessment/review").put("basis", "changed")));
        vectors.add(new Vector("stale-source-after-outer-reseal", "BP2_SOURCE_FACTS_MISMATCH", r -> { object(facts(r), "/clinical/review").put("basis", "different"); pinsOnly(r); }));
        for (String part : PARTS) {
            vectors.add(new Vector(part + "-embedded-float", "BP2_SOURCE_DOCUMENT_INVALID", r -> { ObjectNode s = object(observation(r), "/" + part + "/sources/0"); String bytes = "{\"finite\":0.25}"; s.put("rawUtf8", bytes).put("rawSha256", raw(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8))); pinsOnly(r); }));
            vectors.add(new Vector(part + "-embedded-invalid-JSON", "BP2_SOURCE_DOCUMENT_INVALID", r -> { ObjectNode s = object(observation(r), "/" + part + "/sources/0"); String bytes = "{\"bad\":}"; s.put("rawUtf8", bytes).put("rawSha256", raw(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8))); pinsOnly(r); }));
        }
        vectors.add(new Vector("source-raw-pin", "BP2_SOURCE_RAW_PIN_MISMATCH", r -> { object(facts(r), "/sources/0").put("rawSha256", "0".repeat(64)); pinsOnly(r); }));
        vectors.add(new Vector("source-canonical-pin", "BP2_SOURCE_CANONICAL_PIN_MISMATCH", r -> { object(facts(r), "/sources/0").put("canonicalSha256", "0".repeat(64)); pinsOnly(r); }));
        vectors.add(new Vector("source-no-report", "BP2_SOURCE_MISSING", r -> { facts(r).putArray("sources"); pinsOnly(r); }));
        vectors.add(new Vector("source-NFC-collision", "BP2_SOURCE_ID_COLLISION", r -> { object(facts(r), "/sources/0").put("sourceId", "é"); object(observation(r), "/phase/sources/0").put("sourceId", "e\u0301"); reissue(r, "facts", "phase"); }));
        vectors.add(new Vector("registry-pin", "BP2_REGISTRY_OR_AUTHORITY_INVALID", r -> object(r, "/registryBinding").put("registrySha256", "0".repeat(64))));
        vectors.add(new Vector("authority-version", "BP2_REGISTRY_OR_AUTHORITY_INVALID", r -> { object(r, "/policy/scientificSources/0").put("version", "2.0"); catalogPin(r); }));
        vectors.add(new Vector("authority-URL", "BP2_REGISTRY_OR_AUTHORITY_INVALID", r -> { object(r, "/policy/scientificSources/0").put("url", "https://example.invalid/changed"); catalogPin(r); }));
        vectors.add(new Vector("authority-SHA", "BP2_REGISTRY_OR_AUTHORITY_INVALID", r -> { object(r, "/policy/scientificSources/0").put("sha256", "0".repeat(64)); catalogPin(r); }));
        for (String disposition : List.of("SPECIALIZED_UNIMPLEMENTED", "SPECIALIZED_IMPLEMENTED", "SPECIALIZED_VERSION_CONFLICT", "SPECIALIZED_UNRESOLVED")) {
            String reason = switch (disposition) {
                case "SPECIALIZED_IMPLEMENTED" -> "BP2_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED";
                case "SPECIALIZED_VERSION_CONFLICT" -> "APPLICABLE_SPECIALIZED_VERSION_CONFLICT";
                case "SPECIALIZED_UNRESOLVED" -> "APPLICABLE_SPECIALIZED_CLAUSE_UNRESOLVED";
                default -> "APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED";
            };
            vectors.add(new Vector(disposition, reason, r -> { object(r, "/catalog/entries/0").put("disposition", disposition); catalogPin(r); }));
        }
        vectors.add(new Vector("required-transcript-missing", "POSSIBLE_SPECIALIZED_TRANSCRIPT_SCOPE_UNRESOLVED", r -> { object(r, "/catalog/entries/0/scope").put("transcriptMode", "EXACT_ACCESSIONS").putArray("transcripts").addObject().put("accession", "NM_999999").put("version", 1); catalogPin(r); }));
        vectors.add(new Vector("catalog-pin", "CATALOG_INTEGRITY_MISMATCH", r -> r.put("catalogSha256", "0".repeat(64))));
        return vectors.stream().map(v -> DynamicTest.dynamicTest(v.name(), () -> {
            ObjectNode root = base("alpha"); v.mutation().apply(root); ObjectNode result = evaluate(root);
            artifact(v.name(), root, result); minimal(result, v.reason());
        }));
    }
    @Test void explicitIndependentConclusionNotEmptyDependencies() throws Exception {
        ObjectNode root = base("alpha"); object(observation(root), "/partnerClassification/independence").putArray("dependencies");
        reissue(root, "partnerClassification"); minimal(evaluate(root), "BP2_CLASSIFICATION_INDEPENDENCE_UNRESOLVED");
        object(observation(root), "/partnerClassification/independence").put("conclusion", "UNKNOWN"); reissue(root, "partnerClassification");
        minimal(evaluate(root), "BP2_CLASSIFICATION_INDEPENDENCE_UNRESOLVED");
    }
}
