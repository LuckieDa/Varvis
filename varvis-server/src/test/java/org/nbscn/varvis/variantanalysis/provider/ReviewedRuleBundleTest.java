package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Assembly and identity controls with synthetic models/candidates; no real VEP or clinical acceptance. */
class ReviewedRuleBundleTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    @TempDir Path temporary;

    @Test void catalogSelectedStopAndPublishedAssayUseTheirExistingStrictRuntimes() throws Exception {
        var pvs = new TruncatingNmdPvs1RuntimeTest();
        var stop = pvs.nonsense(1, -1, 31);
        var functional = new FunctionalEvidenceRuntimeTest(); functional.load();
        var candidate = functional.candidate("R130Q");
        ObjectNode context = functional.context(candidate);
        ObjectNode materials = mapper.createObjectNode();
        materials.set("pvs1ResourceProfile", pvs.resourceProfile());
        materials.putObject("transcriptModels").set(adapter.canonicalSha256(stop.model), stop.model);
        materials.putObject("functionalFacts").set(adapter.canonicalSha256(functional.facts), functional.facts);
        ObjectNode document = document(List.of(stop.policy, functional.policy), materials);
        ReviewedRuleBundle bundle = load(document);
        var truncating = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, stop.context, stop.candidate);
        var measured = bundle.evaluate(FunctionalEvidenceRuntime.PS3, context, candidate);
        assertEquals("MET", truncating.at("/evaluation/evaluationResult/status").asText(), truncating.toPrettyString());
        assertEquals("MET", measured.at("/evaluation/evaluationResult/status").asText(), measured.toPrettyString());
        assertEquals("VERY_STRONG", truncating.at("/evaluation/appliedStrength").asText());
        assertEquals("MODERATE", measured.at("/evaluation/appliedStrength").asText());
        assertFalse(truncating.path("adoptionSupported").asBoolean(true));
        assertFalse(measured.path("adoptionSupported").asBoolean(true));
        assertEquals(0, truncating.path("providerCalls").asInt());
        assertEquals(1, measured.path("providerCalls").asInt());
        assertEquals(truncating, bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, stop.context, stop.candidate));
        ((ObjectNode) document.path("materials")).removeAll();
        bundle.identity().removeAll();
        ObjectNode repeated = bundle.evaluate(FunctionalEvidenceRuntime.PS3, context, candidate);
        // A new direct evaluation records a new execution/review-request time. Snapshot isolation
        // concerns the pinned inputs and scientific result; business reuse is tested separately.
        for (String field : List.of("bundleIdentity", "factPackage", "factRecord", "proteinIdentity", "policy"))
            assertEquals(measured.path(field), repeated.path(field), field);
        assertEquals(measured.at("/evaluation/evaluationResult"), repeated.at("/evaluation/evaluationResult"));
        assertEquals(measured.at("/evaluation/appliedStrength"), repeated.at("/evaluation/appliedStrength"));
    }

    @Test void onlyValidUncoveredClauseCanUseItsPredecessor() throws Exception {
        var pvs = new TruncatingNmdPvs1RuntimeTest(); var data = pvs.nonsense(1, 1, 31);
        ObjectNode document = document(List.of(data.policy), mapper.createObjectNode());
        assertTrue(ReviewedRuleBundle.usesPreexistingClause(load(document).evaluate(FunctionalEvidenceRuntime.PS3, data.context, data.candidate)));
        ((ObjectNode) document.at("/catalog/entries/0")).put("criterionId", FunctionalEvidenceRuntime.PS3)
                .put("disposition", "SPECIALIZED_UNIMPLEMENTED");
        rehashCatalog(document);
        ObjectNode gap = load(document).evaluate(FunctionalEvidenceRuntime.PS3, data.context, data.candidate);
        assertFalse(ReviewedRuleBundle.usesPreexistingClause(gap));
        assertEquals("SPECIALIZED_POLICY_GAP", gap.at("/policySelection/outcome").asText());
        document.put("catalogSha256", "f".repeat(64));
        ObjectNode broken = load(document).evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);
        assertFalse(ReviewedRuleBundle.usesPreexistingClause(broken));
        assertEquals("NOT_EVALUATED", broken.at("/evaluationResult/status").asText());
    }

    @Test void materialHashAndAbsentCandidateNeverProduceAnEvaluation() throws Exception {
        var pvs = new TruncatingNmdPvs1RuntimeTest(); var data = pvs.nonsense(1, 1, 31);
        ObjectNode materials = mapper.createObjectNode();
        materials.set("pvs1ResourceProfile", pvs.resourceProfile());
        ObjectNode wrongModel = data.model.deepCopy().put("modelVersion", "wrong");
        materials.putObject("transcriptModels").set(adapter.canonicalSha256(data.model), wrongModel);
        ReviewedRuleBundle bundle = load(document(List.of(data.policy), materials));
        for (var result : List.of(bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate),
                bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, null),
                bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, mapper.createObjectNode(), data.candidate))) {
            assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
            assertEquals(0, result.path("evaluatorCalls").asInt());
            assertFalse(result.has("evaluation"));
            assertFalse(ReviewedRuleBundle.usesPreexistingClause(result));
        }
    }

    @Test void configurationFailuresAreBoundAndDraftsCannotLoad() throws Exception {
        var pvs = new TruncatingNmdPvs1RuntimeTest(); var data = pvs.nonsense(1, 1, 31);
        ObjectNode document = document(List.of(data.policy), mapper.createObjectNode());
        assertThrows(IllegalArgumentException.class, () -> new ReviewedRuleBundle(mapper, document, "f".repeat(64)));
        ((ObjectNode) document.path("review")).put("status", "PENDING");
        assertThrows(IllegalArgumentException.class, () -> load(document));
        var absent = ReviewedRuleBundle.unavailable(mapper, "a".repeat(64));
        var other = ReviewedRuleBundle.unavailable(mapper, "b".repeat(64));
        assertNotEquals(absent.identity(), other.identity());
        assertFalse(ReviewedRuleBundle.usesPreexistingClause(absent.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate)));
        Path file = temporary.resolve("large.json");
        try (var output = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            output.position(8_000_000); output.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        assertThrows(IllegalArgumentException.class, () -> ReviewedRuleBundle.load(mapper, file, "a".repeat(64)));
        assertTrue(Files.size(file) > 8_000_000);
    }

    @Test void reviewedHashesDoNotImplementAdditionalScientificRequirements() throws Exception {
        var pvs = new TruncatingNmdPvs1RuntimeTest(); var data = pvs.nonsense(1, 1, 31);
        var functional = new FunctionalEvidenceRuntimeTest(); functional.load();
        var candidate = functional.candidate("R130Q");
        ObjectNode materials = mapper.createObjectNode();
        materials.set("pvs1ResourceProfile", pvs.resourceProfile());
        materials.putObject("transcriptModels").set(adapter.canonicalSha256(data.model), data.model);
        materials.putObject("functionalFacts").set(adapter.canonicalSha256(functional.facts), functional.facts);
        for (String path : List.of("", "/scope", "/nmdScope", "/mechanism")) {
            ObjectNode policy = data.policy.deepCopy();
            ((ObjectNode) policy.at(path)).putArray("additionalRequirements").add("REQUIRE_INDEPENDENT_RNA_PROOF");
            var result = load(document(List.of(policy), materials)).evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);
            assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
            assertEquals("PVS1_POLICY_OPERATOR_UNSUPPORTED", result.at("/evaluationResult/reasonCode").asText());
        }
        for (String path : List.of("", "/scope", "/assay")) {
            ObjectNode policy = functional.policy.deepCopy();
            ((ObjectNode) policy.at(path)).putArray("additionalRequirements").add("REQUIRE_SECOND_INDEPENDENT_ASSAY");
            var result = load(document(List.of(policy), materials)).evaluate(FunctionalEvidenceRuntime.PS3, functional.context(candidate), candidate);
            assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
            assertEquals("FUNCTIONAL_POLICY_OPERATOR_UNSUPPORTED", result.at("/evaluationResult/reasonCode").asText());
            assertEquals(0, result.path("providerCalls").asInt());
        }
    }

    @Test void optionalProfileBindingPreservesOldIdentityAndChangesNewCacheIdentity() {
        ObjectNode old = new AcmgExecutionProfile(mapper).document();
        assertEquals(old, new AcmgExecutionProfile(mapper, null, null, null).document());
        var binding = ReviewedRuleBundle.unavailable(mapper, "a".repeat(64)).identity();
        ObjectNode current = new AcmgExecutionProfile(mapper, null, null, binding).document();
        assertEquals(old, current.path("baseExecutionProfile"));
        assertEquals("acmg-explicit-business-generalization-v2", current.path("profileId").asText());
        assertFalse(current.path("classificationEnabled").asBoolean(true));
        assertNotEquals(old.path("manifestSha256"), current.path("manifestSha256"));
        binding.put("expectedBundleSha256", "b".repeat(64));
        assertNotEquals(current.path("manifestSha256"), new AcmgExecutionProfile(mapper, null, null, binding).document().path("manifestSha256"));
    }

    private ReviewedRuleBundle load(ObjectNode document) {
        return new ReviewedRuleBundle(mapper, document, adapter.canonicalSha256(document));
    }

    private void rehashCatalog(ObjectNode document) {
        document.put("catalogSha256", adapter.canonicalSha256(document.path("catalog")));
    }

    private ObjectNode document(List<ObjectNode> policies, ObjectNode materials) {
        ObjectNode document = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-v1")
                .put("bundleId", "SYNTHETIC_ENGINEERING_BUNDLE").put("bundleVersion", "1");
        document.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "SYNTHETIC_ONLY");
        document.putObject("activation").put("automaticTrigger", false).put("classificationEnabled", false);
        document.set("materials", materials.deepCopy());
        var policyNodes = document.putArray("policies"); policies.forEach(p -> policyNodes.add(p.deepCopy()));
        ObjectNode catalog = document.putObject("catalog").put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "SYNTHETIC_ENGINEERING_CATALOG").put("catalogVersion", "1")
                .put("searchedAt", "2026-09-13T16:00:00Z").put("searchScope", "Synthetic wiring tests only; not clinical coverage");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ONLY");
        var sources = new LinkedHashMap<String, JsonNode>();
        var entries = catalog.putArray("entries");
        for (ObjectNode policy : policies) {
            JsonNode sourceRecords = policy.has("scientificSources") ? policy.path("scientificSources") : policy.path("sources");
            ObjectNode entry = entries.addObject().put("entryId", policy.path("policyId").asText())
                    .put("criterionId", policy.at("/criterionIds/0").asText()).put("searchStatus", "COMPLETE")
                    .put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "Synthetic engineering clause only");
            ObjectNode scope = policy.path("scope").deepCopy();
            if (!scope.has("transcriptMode")) scope.put("transcriptMode", "EXACT_ACCESSIONS");
            entry.set("scope", scope);
            entry.putObject("review").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ONLY");
            var ids = entry.putArray("sourceIds");
            for (JsonNode source : sourceRecords) { sources.put(source.path("sourceId").asText(), source); ids.add(source.path("sourceId").asText()); }
            entry.putObject("policyBinding").put("policyId", policy.path("policyId").asText())
                    .put("policyVersion", policy.path("policyVersion").asText()).put("policySha256", adapter.canonicalSha256(policy));
        }
        var sourceNodes = catalog.putArray("sources"); sources.values().forEach(sourceNodes::add);
        rehashCatalog(document);
        return document;
    }
}
