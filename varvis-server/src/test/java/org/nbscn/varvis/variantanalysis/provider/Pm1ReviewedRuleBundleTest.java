package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Trusted file identity and selector routing; no legacy PM1 fallback or external acquisition. */
class Pm1ReviewedRuleBundleTest {
    private final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    @TempDir Path temporary;
    private Pm1GeneralTestFixture fixture;
    private Path archive;
    private int sequence;

    @BeforeEach void prepare(TestInfo info) throws Exception {
        String configured = System.getProperty("varvis.pm1.artifacts");
        archive = configured == null ? temporary.resolve("receipts") : Path.of(configured).resolve("bundle")
                .resolve(info.getDisplayName().replaceAll("[^A-Za-z0-9_-]", "_"));
        Files.createDirectories(archive);
        fixture = new Pm1GeneralTestFixture(mapper);
    }

    @Test void independentVersionLoadsActualFilesForBothBackgrounds() throws Exception {
        var bundle = load(fixture.document());
        assertTrue(bundle.supportsPm1());
        assertFalse(bundle.supportsPm4() || bundle.supportsPs1() || bundle.supportsPm5());
        assertEquals("reviewed-rule-bundle-binding-pm1-v5", bundle.identity().path("schemaVersion").asText());
        assertThrows(IllegalArgumentException.class, () -> bundle.evaluate(MissenseComparisonRuntime.PS1, fixture.context(0), fixture.target(0)));
        for (int i = 0; i < 2; i++) {
            var result = bundle.evaluate(Pm1CriterionEvaluator.CRITERION, fixture.context(i), fixture.target(i)); record(result);
            assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
            assertEquals(i == 0 ? "MODERATE" : "SUPPORTING", result.at("/evaluation/appliedStrength").asText());
            assertEquals(1, result.path("criterionEvaluations").size());
            assertFalse(ReviewedRuleBundle.usesPreexistingClause(result));
            assertFalse(result.path("adoptionSupported").asBoolean(true));
        }
        assertEquals(2, bundle.pm1RuntimeCalls()); assertEquals(2, bundle.pm1PositionCalculations());
        assertEquals(2, bundle.pm1EvaluatorCalls());
    }

    @ParameterizedTest @ValueSource(strings = {"MODEL_MISSING", "MODEL_SHA_MISMATCH", "REGION_MISSING", "REGION_SHA_MISMATCH", "RESOURCE_PROFILE_MISSING"})
    void missingOrCorruptPinnedMaterialsRefuseWithoutLegacyFallback(String fault) throws Exception {
        ObjectNode document = fixture.document();
        JsonNode policy = document.path("policies").get(0);
        ObjectNode materials = (ObjectNode) document.path("materials");
        if (fault.equals("RESOURCE_PROFILE_MISSING")) materials.remove("pm1ResourceProfile");
        else {
            boolean model = fault.startsWith("MODEL");
            String sha = policy.path(model ? "transcriptModelBinding" : "regionMaterialBinding").path("canonicalSha256").asText();
            ObjectNode collection = (ObjectNode) materials.path(model ? "transcriptModels" : "pm1Regions");
            if (fault.endsWith("MISSING")) collection.remove(sha);
            else ((ObjectNode) collection.path(sha)).put(model ? "modelVersion" : "materialVersion", "CHANGED_WITHOUT_REVIEW");
        }
        var bundle = load(document);
        var result = bundle.evaluate(Pm1CriterionEvaluator.CRITERION, fixture.context(0), fixture.target(0)); record(result);
        assertRefusal(result, "PM1_" + fault);
        assertEquals(0, bundle.pm1RuntimeCalls()); assertEquals(0, bundle.pm1PositionCalculations());
        assertEquals(0, bundle.pm1EvaluatorCalls());
    }

    @ParameterizedTest @ValueSource(strings = {"UNCOVERED", "CATALOG_HASH", "POLICY_HASH", "POLICY_MISSING", "POLICY_UNREVIEWED"})
    void selectionFailureNeverRoutesToPreexistingHnf1a(String fault) throws Exception {
        ObjectNode document = fixture.document();
        String reason;
        if (fault.equals("UNCOVERED")) {
            ((ObjectNode) document.path("catalog")).putArray("entries");
            document.put("catalogSha256", adapter.canonicalSha256(document.path("catalog")));
            reason = "CLAUSE_SEARCH_SCOPE_NOT_COVERED";
        } else if (fault.equals("CATALOG_HASH")) {
            document.put("catalogSha256", "0".repeat(64)); reason = "CATALOG_INTEGRITY_MISMATCH";
        } else if (fault.equals("POLICY_MISSING")) {
            document.putArray("policies"); reason = "IMPLEMENTED_POLICY_NOT_AVAILABLE";
        } else if (fault.equals("POLICY_UNREVIEWED")) {
            ((ObjectNode) fixture.policies.get(0).path("review")).put("status", "UNREVIEWED");
            document = fixture.document(); reason = "POLICY_NOT_REVIEWED_IMPLEMENTED";
        } else {
            ((ObjectNode) document.at("/policies/0/review")).put("reviewId", "CHANGED_WITHOUT_REBINDING"); reason = "POLICY_INTEGRITY_MISMATCH";
        }
        var bundle = load(document);
        var result = bundle.evaluate(Pm1CriterionEvaluator.CRITERION, fixture.context(0), fixture.target(0)); record(result);
        assertRefusal(result, reason); assertEquals(0, bundle.pm1RuntimeCalls());
    }

    @Test void callerAndDiskMutationCannotChangeLoadedSnapshot() throws Exception {
        ObjectNode document = fixture.document(), original = document.deepCopy();
        Path path = write(document);
        var bundle = ReviewedRuleBundle.load(mapper, path, adapter.canonicalSha256(document));
        ObjectNode identity = bundle.identity();
        ((ObjectNode) document.path("materials")).removeAll();
        Files.writeString(path, "{}");
        bundle.identity().removeAll();
        var result = bundle.evaluate(Pm1CriterionEvaluator.CRITERION, fixture.context(0), fixture.target(0)); record(result);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(original.path("policies").get(0), result.path("policy"));
        assertEquals(identity, bundle.identity());
        assertThrows(IllegalArgumentException.class, () -> ReviewedRuleBundle.load(mapper, path, adapter.canonicalSha256(original)));
    }

    @Test void serverDigestReviewAndActualCandidateAreMandatory() throws Exception {
        ObjectNode document = fixture.document(); Path file = write(document);
        assertThrows(IllegalArgumentException.class, () -> ReviewedRuleBundle.load(mapper, file, "0".repeat(64)));
        ((ObjectNode) document.path("review")).put("status", "DRAFT");
        Path draft = write(document);
        assertThrows(IllegalArgumentException.class, () -> ReviewedRuleBundle.load(mapper, draft, adapter.canonicalSha256(document)));
        var bundle = load(fixture.document());
        var result = bundle.evaluate(Pm1CriterionEvaluator.CRITERION, fixture.context(0), null); record(result);
        assertRefusal(result, "REVIEWED_RULE_VERIFIED_CONTEXT_MISSING"); assertEquals(0, bundle.pm1RuntimeCalls());
    }

    private void assertRefusal(JsonNode result, String reason) {
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(reason, result.at("/evaluationResult/reasonCode").asText(), result.toPrettyString());
        assertEquals(reason, result.at("/criterionEvaluations/0/evaluationResult/reasonCode").asText());
        assertEquals(1, result.path("criterionEvaluations").size());
        assertFalse(ReviewedRuleBundle.usesPreexistingClause(result));
    }
    private ReviewedRuleBundle load(ObjectNode document) throws Exception {
        return ReviewedRuleBundle.load(mapper, write(document), adapter.canonicalSha256(document));
    }
    private Path write(JsonNode document) throws Exception {
        Path file = temporary.resolve("input-" + sequence++ + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
        Files.copy(file, archive.resolve(file.getFileName())); return file;
    }
    private void record(JsonNode result) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(archive.resolve("output-" + sequence++ + ".json").toFile(), result);
    }
}
