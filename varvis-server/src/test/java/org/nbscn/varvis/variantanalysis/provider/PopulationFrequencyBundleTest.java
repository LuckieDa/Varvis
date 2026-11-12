package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic integration of selector, pinned bundle and frequency runtime; no external scientific queries. */
class PopulationFrequencyBundleTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final PopulationFrequencyRuntimeTest inputs = new PopulationFrequencyRuntimeTest();

    private ObjectNode document() {
        ObjectNode policy = inputs.policy("MYH7");
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "population-frequency-bundle-v1")
                .put("bundleId", "synthetic-population-bundle").put("bundleVersion", "1");
        result.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "synthetic-review");
        result.putArray("policies").add(policy);
        ObjectNode catalog = result.putObject("catalog").put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "synthetic-catalog").put("catalogVersion", "1")
                .put("searchedAt", "2026-09-13T14:00:00Z").put("searchScope", "Synthetic source scope; no clinical claim");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "synthetic-review");
        catalog.putArray("sources").addObject().put("sourceId", "synthetic-source")
                .put("url", "https://example.org/synthetic").put("version", "1").put("sha256", "a".repeat(64));
        var entries = catalog.putArray("entries");
        for (String criterion : List.of(PopulationFrequencyRuntime.BA1, PopulationFrequencyRuntime.BS1)) {
            var entry = entries.addObject().put("entryId", "synthetic-" + criterion).put("criterionId", criterion)
                    .put("searchStatus", "COMPLETE").put("disposition", "SPECIALIZED_IMPLEMENTED")
                    .put("baseRuleRelationship", "Synthetic reviewed threshold replaces synthetic general condition");
            entry.putObject("review").put("status", "REVIEWED").put("reviewId", "synthetic-review");
            entry.putArray("sourceIds").add("synthetic-source");
            ObjectNode scope = ((ObjectNode) policy.path("scope")).deepCopy();
            scope.put("transcriptMode", "ANY_VALIDATED_TRANSCRIPT"); entry.set("scope", scope);
            entry.putObject("policyBinding").setAll(mapper.createObjectNode()
                    .put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText())
                    .put("policySha256", adapter.canonicalSha256(policy)));
        }
        result.put("catalogSha256", adapter.canonicalSha256(catalog));
        return result;
    }

    private PopulationFrequencyBundle load(ObjectNode document) {
        return new PopulationFrequencyBundle(mapper, document, adapter.canonicalSha256(document));
    }

    private ObjectNode evaluate(PopulationFrequencyBundle bundle) throws Exception {
        var context = inputs.diseaseContext("MYH7");
        return bundle.evaluate(context, inputs.observation(context, inputs.jointInfo("0.001", "10000", "0.1"), "PASS"), "a".repeat(64));
    }

    @Test void bothClausesReuseOneObservationAndCountPopulationStrengthOnce() throws Exception {
        var result = evaluate(load(document()));
        assertTrue(PopulationFrequencyBundle.usesCurrentClause(result, PopulationFrequencyRuntime.BA1));
        assertEquals(2, result.path("criterionEvaluations").size());
        for (var value : result.path("criterionEvaluations")) assertEquals("MET", value.at("/evaluationResult/status").asText());
        assertEquals(0, result.path("providerCalls").asInt());
        assertEquals(2, result.path("evaluatorCalls").asInt());
        assertEquals(1, result.at("/overlap/populationStrengthCount").asInt());
        assertEquals(PopulationFrequencyRuntime.BA1, result.at("/overlap/preferredPopulationCriterion").asText());
    }

    @Test void reviewedMyh7BundleMatchesItsPinnedCatalogAndPopulationParser() throws Exception {
        try (var source = getClass().getResourceAsStream("/acmg/generalization/population-myh7-hcm-bundle-v1.json")) {
            if (source == null) throw new AssertionError("Reviewed policy candidate missing");
            ObjectNode document = (ObjectNode) mapper.readTree(source);
            var result = evaluate(load(document));
            for (var value : result.path("criterionEvaluations")) assertEquals("MET", value.at("/evaluationResult/status").asText(), result.toString());
            assertEquals(2, result.path("evaluatorCalls").asInt());
            assertEquals(0, result.path("providerCalls").asInt());
        }
    }

    @Test void unimplementedClauseCannotFallBackAndDoesNotDisableOtherClause() throws Exception {
        var document = document();
        ((ObjectNode) document.at("/catalog/entries/0")).put("disposition", "SPECIALIZED_UNIMPLEMENTED");
        document.put("catalogSha256", adapter.canonicalSha256(document.path("catalog")));
        var result = evaluate(load(document));
        assertEquals("APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED", result.at("/criterionEvaluations/0/evaluationResult/reasonCode").asText());
        assertEquals("MET", result.at("/criterionEvaluations/1/evaluationResult/status").asText());
        assertEquals(1, result.path("evaluatorCalls").asInt());
        assertEquals(PopulationFrequencyRuntime.BS1, result.at("/overlap/preferredPopulationCriterion").asText());
    }

    @Test void tamperedInnerCatalogRemainsExplicitlyBlockedEvenWithMatchingOuterHash() throws Exception {
        var document = document(); document.put("catalogSha256", "b".repeat(64));
        var result = evaluate(load(document));
        for (var value : result.path("criterionEvaluations"))
            assertEquals("CATALOG_INTEGRITY_MISMATCH", value.at("/evaluationResult/reasonCode").asText());
        assertEquals(0, result.path("evaluatorCalls").asInt());
        assertTrue(PopulationFrequencyBundle.usesCurrentClause(result, PopulationFrequencyRuntime.BA1));
        assertTrue(PopulationFrequencyBundle.usesCurrentClause(result, PopulationFrequencyRuntime.BS1));
    }

    @Test void clauseRoutingPreservesOnlyTheIndividuallyUncoveredPredecessor() throws Exception {
        var document = document();
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.at("/catalog/entries")).remove(1);
        document.put("catalogSha256", adapter.canonicalSha256(document.path("catalog")));
        var result = evaluate(load(document));
        assertTrue(PopulationFrequencyBundle.usesCurrentClause(result, PopulationFrequencyRuntime.BA1));
        assertFalse(PopulationFrequencyBundle.usesCurrentClause(result, PopulationFrequencyRuntime.BS1));
        ((ObjectNode) document.at("/catalog/entries/0")).put("searchStatus", "FAILED");
        document.put("catalogSha256", adapter.canonicalSha256(document.path("catalog")));
        result = evaluate(load(document));
        assertTrue(PopulationFrequencyBundle.usesCurrentClause(result, PopulationFrequencyRuntime.BA1));
        assertEquals("CLAUSE_SEARCH_FAILED", result.at("/criterionEvaluations/0/evaluationResult/reasonCode").asText());
    }

    @Test void absentOrNullContextReturnsStructuredRefusalsInsteadOfCrashingBusinessAppend() {
        var bundle = load(document());
        for (var context : List.of(mapper.nullNode(), mapper.missingNode(), mapper.createObjectNode())) {
            var result = assertDoesNotThrow(() -> bundle.evaluate(context, mapper.missingNode(), "a".repeat(64)));
            assertEquals(2, result.path("criterionEvaluations").size());
            for (var value : result.path("criterionEvaluations")) assertEquals("NOT_EVALUATED", value.at("/evaluationResult/status").asText());
            assertEquals(0, result.path("evaluatorCalls").asInt());
            assertEquals("NOT_COMBINABLE", result.at("/overlap/status").asText());
        }
    }

    @Test void policyAndCatalogChangesChangeExecutionIdentityWithoutChangingJointV7() throws Exception {
        var original = document(); var first = load(original);
        var joint = new AcmgExecutionProfile(mapper).document();
        var enabled = new AcmgExecutionProfile(mapper, null, first.identity()).document();
        assertEquals(joint, new AcmgExecutionProfile(mapper, null, null).document());
        assertNotEquals(joint.path("manifestSha256"), enabled.path("manifestSha256"));
        assertFalse(enabled.path("classificationEnabled").asBoolean(true));
        assertFalse(enabled.path("automaticTrigger").asBoolean(true));
        ((ObjectNode) original.path("catalog")).put("catalogVersion", "2");
        original.put("catalogSha256", adapter.canonicalSha256(original.path("catalog")));
        var second = load(original);
        assertNotEquals(enabled, new AcmgExecutionProfile(mapper, null, second.identity()).document());
        assertEquals("1", first.identity().path("bundleVersion").asText());
        assertEquals("MET", evaluate(first).at("/criterionEvaluations/0/evaluationResult/status").asText());
    }

    @Test void loaderRequiresPinnedReviewedBoundedJson() throws Exception {
        var document = document(); Path file = temporary.resolve("bundle.json");
        Files.writeString(file, mapper.writeValueAsString(document));
        assertEquals(load(document).identity(), PopulationFrequencyBundle.load(mapper, file, adapter.canonicalSha256(document)).identity());
        assertThrows(IllegalArgumentException.class, () -> PopulationFrequencyBundle.load(mapper, file, "f".repeat(64)));
        ((ObjectNode) document.path("review")).put("status", "DRAFT");
        assertThrows(IllegalArgumentException.class, () -> load(document));
        Files.writeString(file, " ".repeat(2_000_001));
        assertThrows(IllegalArgumentException.class, () -> PopulationFrequencyBundle.load(mapper, file, "f".repeat(64)));
    }

    @Test void unavailableConfigurationProducesTwoStructuredRefusalsWithoutScientificCalls() throws Exception {
        var bundle = PopulationFrequencyBundle.unavailable(mapper, "c".repeat(64));
        var result = evaluate(bundle);
        for (var value : result.path("criterionEvaluations")) {
            assertEquals("NOT_EVALUATED", value.at("/evaluationResult/status").asText());
            assertEquals("POPULATION_POLICY_CONFIGURATION_UNAVAILABLE", value.at("/evaluationResult/reasonCode").asText());
        }
        assertEquals(2, result.path("criterionEvaluations").size());
        assertEquals(0, result.path("evaluatorCalls").asInt());
        assertNotEquals(new AcmgExecutionProfile(mapper).document(), new AcmgExecutionProfile(mapper, null, bundle.identity()).document());
    }
}
