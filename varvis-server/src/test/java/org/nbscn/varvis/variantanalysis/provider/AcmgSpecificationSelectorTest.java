package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic control-flow tests; these are not scientific policy acceptance. */
class AcmgSpecificationSelectorTest {
    private static final String BS1 = "ACMG_AMP_2015:BS1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private ObjectNode context;
    private ObjectNode catalog;
    private ObjectNode entry;
    private ObjectNode policy;
    private ArrayNode policies;

    @BeforeEach void setUp() throws Exception {
        context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter,
                RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 100000, "A", "G"));
        ObjectNode scope = mapper.createObjectNode();
        scope.set("hgncId", context.at("/gene/hgncId"));
        scope.set("mondoId", context.at("/disease/mondoId"));
        scope.set("moi", context.path("moi"));
        scope.put("assembly", "GRCh38");
        policy = mapper.createObjectNode();
        policy.put("policyId", "synthetic-policy").put("policyVersion", "1");
        policy.putArray("criterionIds").add(BS1);
        policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "synthetic-review");
        policy.set("scope", scope.deepCopy());
        ((ObjectNode) policy.path("scope")).put("variantType", "NATIVE_NONPROJECTED_SNV");
        policies = mapper.createArrayNode().add(policy);
        catalog = mapper.createObjectNode();
        catalog.put("schemaVersion", "acmg-specification-catalog-v1");
        catalog.put("catalogId", "synthetic-catalog").put("catalogVersion", "1");
        catalog.put("searchedAt", "2026-09-13T14:00:00Z");
        catalog.put("searchScope", "Synthetic unit-test source only; no scientific applicability claim");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "synthetic-review");
        catalog.putArray("sources").addObject().put("sourceId", "synthetic-source")
                .put("url", "https://example.org/synthetic-only").put("version", "1").put("sha256", "a".repeat(64));
        entry = catalog.putArray("entries").addObject();
        entry.put("entryId", "synthetic-bs1-clause").put("criterionId", BS1);
        scope.put("transcriptMode", "ANY_VALIDATED_TRANSCRIPT");
        entry.set("scope", scope);
        entry.put("searchStatus", "COMPLETE").put("disposition", "SPECIALIZED_IMPLEMENTED");
        entry.putObject("review").put("status", "REVIEWED").put("reviewId", "synthetic-review");
        entry.putArray("sourceIds").add("synthetic-source");
        entry.put("baseRuleRelationship", "Synthetic specialized clause replaces synthetic general threshold");
        entry.putObject("policyBinding").put("policyId", "synthetic-policy").put("policyVersion", "1")
                .put("policySha256", adapter.canonicalSha256(policy));
    }

    private AcmgSpecificationSelector selector() {
        return new AcmgSpecificationSelector(mapper, catalog, adapter.canonicalSha256(catalog), policies);
    }

    private String reason() { return selector().select(context, BS1).receipt().path("reasonCode").asText(); }

    @Test void malformedCatalogScopeCannotLookLikeAnUncoveredClause() {
        ObjectNode scope = (ObjectNode) entry.path("scope");
        scope.remove("transcriptMode");
        assertEquals("CATALOG_CLAUSE_SCOPE_INVALID", reason());
        scope.put("transcriptMode", "EXACT_ACCESSIONS");
        assertEquals("CATALOG_CLAUSE_SCOPE_INVALID", reason());
        var transcript = scope.putArray("transcripts").addObject();
        transcript.set("accession", context.at("/transcript/accession"));
        transcript.put("version", 4294967297L);
        assertEquals("CATALOG_CLAUSE_SCOPE_INVALID", reason());
        transcript.set("version", context.at("/transcript/version"));
        assertNotNull(selector().select(context, BS1).selectedPolicy());
        transcript.put("accession", "NM_BAD.1");
        assertEquals("CATALOG_CLAUSE_SCOPE_INVALID", reason());
        transcript.set("accession", context.at("/transcript/accession"));
        scope.put("moi", "AD");
        assertEquals("CATALOG_CLAUSE_SCOPE_INVALID", reason());
        scope.set("moi", context.path("moi"));
        scope.put("hgncId", "HGNC:0");
        assertEquals("CATALOG_CLAUSE_SCOPE_INVALID", reason());
    }

    @Test void reviewedSpecializationIsSelectedAndIdentityChangesWithCatalog() {
        var first = selector().select(context, BS1);
        assertEquals("SPECIALIZED_POLICY_SELECTED", first.receipt().path("outcome").asText());
        assertEquals(policy, first.selectedPolicy());
        assertEquals(first.receipt(), selector().select(context, BS1).receipt());
        catalog.put("catalogVersion", "2");
        assertNotEquals(first.receipt().path("selectionId"), selector().select(context, BS1).receipt().path("selectionId"));
    }

    @Test void scopedNoSpecializationRequiresAffirmativeGeneralPremises() {
        entry.put("disposition", "NO_APPLICABLE_SPECIALIZATION");
        assertEquals("GENERAL_RULE_PREREQUISITES_MISSING", reason());
        entry.putObject("generalPrerequisites").put("status", "COMPLETE")
                .putArray("sourceIds").add("synthetic-source");
        assertEquals("GENERAL_POLICY_SELECTED", selector().select(context, BS1).receipt().path("outcome").asText());
        entry.put("disposition", "GENERAL_CLAUSE_ALLOWED");
        assertEquals("GENERAL_POLICY_SELECTED", selector().select(context, BS1).receipt().path("outcome").asText());
    }

    @Test void applicableUnimplementedVersionNeverFallsBackToAnAvailablePolicy() {
        entry.put("disposition", "SPECIALIZED_UNIMPLEMENTED");
        var selected = selector().select(context, BS1);
        assertNull(selected.selectedPolicy());
        assertEquals("SPECIALIZED_POLICY_GAP", selected.receipt().path("outcome").asText());
        assertEquals("APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED", reason());
        entry.put("disposition", "SPECIALIZED_VERSION_CONFLICT");
        assertEquals("APPLICABLE_SPECIALIZED_VERSION_CONFLICT", reason());
        entry.put("disposition", "SPECIALIZED_UNRESOLVED");
        assertEquals("APPLICABLE_SPECIALIZED_CLAUSE_UNRESOLVED", reason());
    }

    @Test void failedOrAbsentSearchIsNotProofOfNoSpecialization() {
        entry.put("searchStatus", "FAILED");
        assertEquals("CLAUSE_SEARCH_FAILED", reason());
        entry.put("searchStatus", "INCOMPLETE");
        assertEquals("CLAUSE_SEARCH_INCOMPLETE", reason());
        ((ArrayNode) catalog.path("entries")).removeAll();
        assertEquals("CLAUSE_SEARCH_SCOPE_NOT_COVERED", reason());
    }

    @Test void oneClauseDoesNotBlanketBlockOtherRulesAndExactDiseaseIsRequired() {
        assertEquals("CLAUSE_SEARCH_SCOPE_NOT_COVERED", selector().select(context, "ACMG_AMP_2015:BA1")
                .receipt().path("reasonCode").asText());
        ((ObjectNode) entry.path("scope")).put("mondoId", "MONDO:0000002");
        assertEquals("CLAUSE_SEARCH_SCOPE_NOT_COVERED", reason());
    }

    @Test void transcriptScopedClauseCannotCrossVersion() {
        ObjectNode scope = (ObjectNode) entry.path("scope");
        scope.put("transcriptMode", "EXACT_ACCESSIONS");
        ObjectNode transcript = scope.putArray("transcripts").addObject();
        transcript.set("accession", context.at("/transcript/accession"));
        transcript.set("version", context.at("/transcript/version"));
        assertNotNull(selector().select(context, BS1).selectedPolicy());
        transcript.put("version", context.at("/transcript/version").asInt() + 1);
        assertEquals("CLAUSE_SEARCH_SCOPE_NOT_COVERED", reason());
    }

    @Test void tamperedAndUnreviewedInputsCannotActivatePolicy() {
        var wrongHash = new AcmgSpecificationSelector(mapper, catalog, "b".repeat(64), policies);
        assertEquals("CATALOG_INTEGRITY_MISMATCH", wrongHash.select(context, BS1).receipt().path("reasonCode").asText());
        policy.put("policyVersion", "2");
        assertEquals("IMPLEMENTED_POLICY_NOT_AVAILABLE", reason());
        policy.put("policyVersion", "1").put("changed", true);
        assertEquals("POLICY_INTEGRITY_MISMATCH", reason());
        policy.remove("changed");
        ((ObjectNode) policy.path("review")).put("status", "DRAFT");
        ((ObjectNode) entry.path("policyBinding")).put("policySha256", adapter.canonicalSha256(policy));
        assertEquals("POLICY_NOT_REVIEWED_IMPLEMENTED", reason());
    }

    @Test void ambiguousEntriesAndInvalidContextRefuseWithDistinctReasons() {
        ((ArrayNode) catalog.path("entries")).add(entry.deepCopy().put("entryId", "second-reviewed-clause"));
        assertEquals("CLAUSE_SELECTION_CONFLICT", reason());
        context.put("contextId", "tampered-context");
        assertEquals("CONTEXT_IDENTITY_INCOMPLETE_OR_INVALID", reason());
    }

    @Test void constructorSnapshotsAreIndependentOfCallerMutation() {
        var frozen = selector();
        catalog.put("catalogVersion", "changed");
        policy.put("changed", true);
        assertNotNull(frozen.select(context, BS1).selectedPolicy());
        assertEquals("1", frozen.select(context, BS1).receipt().path("catalogVersion").asText());
    }

    private void rebindPolicy() {
        ((ObjectNode) entry.path("policyBinding")).put("policySha256", adapter.canonicalSha256(policy));
    }

    @Test void policyOwnTranscriptConstraintCannotBeBroadenedByCatalog() {
        ObjectNode transcript = ((ObjectNode) policy.path("scope")).putArray("transcripts").addObject();
        transcript.set("accession", context.at("/transcript/accession"));
        transcript.put("version", context.at("/transcript/version").asInt() + 1);
        rebindPolicy();
        assertEquals("POLICY_CONTEXT_SCOPE_MISMATCH", reason());
        transcript.set("version", context.at("/transcript/version"));
        rebindPolicy();
        assertNotNull(selector().select(context, BS1).selectedPolicy());
    }

    @Test void policyOwnCriterionAndVariantScopeCannotBeBroadenedByCatalog() {
        policy.putArray("criterionIds").add("ACMG_AMP_2015:BA1");
        rebindPolicy();
        assertEquals("POLICY_CRITERION_SCOPE_MISMATCH", reason());
        policy.putArray("criterionIds").add(BS1);
        ((ObjectNode) policy.path("scope")).put("variantType", "UNIMPLEMENTED_VARIANT_TYPE");
        rebindPolicy();
        assertEquals("POLICY_CONTEXT_SCOPE_MISMATCH", reason());
    }

    @Test void matchingMissingIdentitiesCannotCreateAnAnonymousSelectedPolicy() {
        policy.remove("policyVersion");
        ((ObjectNode) entry.path("policyBinding")).remove("policyVersion");
        rebindPolicy();
        assertEquals("POLICY_BINDING_IDENTITY_MISSING", reason());
    }

    @Test void variantTypeLabelCannotTurnActualInsertionIntoSnv() throws Exception {
        context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter,
                RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 100000, "A", "AT"));
        assertEquals("SNV", context.at("/variant/variantType").asText());
        assertEquals("POLICY_CONTEXT_SCOPE_MISMATCH", reason());
    }

    @Test void explicitTranscriptModeCannotBeIgnoredWhenItsListIsMissing() {
        ((ObjectNode) policy.path("scope")).put("transcriptMode", "EXACT_ACCESSIONS");
        rebindPolicy();
        assertEquals("POLICY_CONTEXT_SCOPE_MISMATCH", reason());
        ((ObjectNode) policy.path("scope")).put("transcriptMode", "UNKNOWN_MODE");
        rebindPolicy();
        assertEquals("POLICY_CONTEXT_SCOPE_MISMATCH", reason());
    }
}
