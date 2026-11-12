package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit synthetic service capabilities, not clinical or resource acceptance. */
public class ReviewedRuleContextPreparationTest {
    /** A reviewed empty synthetic catalog offers a capability, never a scientific criterion policy. */
    public static ObjectNode emptyBundleDocument(ObjectMapper mapper, String version) {
        ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-v1")
                .put("bundleId", "SYNTHETIC_CONTEXT_ONLY").put("bundleVersion", version);
        value.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "SYNTHETIC_ENGINEERING_ONLY");
        value.putObject("activation").put("automaticTrigger", false).put("classificationEnabled", false);
        value.putObject("materials"); value.putArray("policies");
        ObjectNode catalog = value.putObject("catalog").put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "SYNTHETIC_EMPTY_CATALOG").put("catalogVersion", "1")
                .put("searchedAt", "2026-09-13T00:00:00Z").put("searchScope", "Empty synthetic scope for context/cache tests only");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ENGINEERING_ONLY");
        catalog.putArray("entries");
        catalog.putArray("sources").addObject().put("sourceId", "SYNTHETIC").put("version", "1")
                .put("url", "https://example.invalid/synthetic").put("sha256", "a".repeat(64));
        value.put("catalogSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(catalog));
        return value;
    }

    public static ObjectNode binding(ObjectMapper mapper) {
        ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-binding-v1")
                .put("status", "CONFIGURED").put("bundleId", "SYNTHETIC_CONTEXT_ONLY").put("bundleVersion", "1")
                .put("bundleSha256", "a".repeat(64)).put("catalogSha256", "b".repeat(64))
                .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putObject("algorithms").put(TruncatingNmdPvs1Runtime.EVALUATOR_ID, TruncatingNmdPvs1Runtime.EVALUATOR_VERSION);
        return value;
    }
    @Test void unavailableOrDifferentCapabilityCannotAuthorizeSelection() {
        var mapper = new ObjectMapper(); var raw = binding(mapper);
        var capability = new ReviewedRuleContextPreparation(mapper, raw);
        assertTrue(capability.accepts(capability.selectionPolicy()));
        assertFalse(capability.accepts("PM2_ONLY_VERIFIED_CLINICAL_ANNOTATION@1.0.0"));
        raw.put("bundleSha256", "c".repeat(64));
        assertFalse(new ReviewedRuleContextPreparation(mapper, raw).accepts(capability.selectionPolicy()));
        capability.identity().removeAll();
        assertFalse(capability.identity().isEmpty());
        raw.put("status", "UNAVAILABLE");
        assertThrows(IllegalArgumentException.class, () -> new ReviewedRuleContextPreparation(mapper, raw));
        assertThrows(IllegalArgumentException.class, () -> new ReviewedRuleContextPreparation(mapper, null));
    }
    @Test void representationScopeDoesNotInventComplexOrLongSmallVariants() {
        assertTrue(ReviewedRuleContextPreparation.smallAllele("A", "G"));
        assertTrue(ReviewedRuleContextPreparation.smallAllele("A", "A" + "G".repeat(49)));
        assertTrue(ReviewedRuleContextPreparation.smallAllele("AC", "A"));
        for (String[] allele : new String[][]{{"A", "A"}, {"A", "G".repeat(51)}, {"AC", "GT"}, {"A", "*"}, {"A", "<DEL>"}, {"AN", "A"}})
            assertFalse(ReviewedRuleContextPreparation.smallAllele(allele[0], allele[1]));
    }

    @Test void executionIdentityBindsTheSameCurrentCapabilityAndPreservesPredecessors() {
        var mapper = new ObjectMapper(); var raw = binding(mapper);
        var preparation = new ReviewedRuleContextPreparation(mapper, raw).identity();
        ObjectNode predecessor = new AcmgExecutionProfile(mapper, null, null, raw).document();
        assertEquals(predecessor, new AcmgExecutionProfile(mapper, null, null, raw, null).document());
        ObjectNode current = new AcmgExecutionProfile(mapper, null, null, raw, preparation).document();
        assertEquals(predecessor, current.path("baseExecutionProfile"));
        assertEquals(preparation, current.path("ruleContextPreparationBinding"));
        assertEquals("acmg-explicit-business-generalization-v3", current.path("profileId").asText());
        assertNotEquals(predecessor.path("manifestSha256"), current.path("manifestSha256"));
        ObjectNode changed = preparation.deepCopy().put("version", "obsolete");
        assertThrows(IllegalArgumentException.class, () -> new AcmgExecutionProfile(mapper, null, null, raw, changed));
        raw.put("bundleSha256", "c".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new AcmgExecutionProfile(mapper, null, null, raw, preparation));
        ObjectNode next = new AcmgExecutionProfile(mapper, null, null, raw,
                new ReviewedRuleContextPreparation(mapper, raw).identity()).document();
        assertNotEquals(current.path("manifestSha256"), next.path("manifestSha256"));
        assertThrows(IllegalArgumentException.class, () -> new AcmgExecutionProfile(mapper, null, null, null, preparation));
    }
}
