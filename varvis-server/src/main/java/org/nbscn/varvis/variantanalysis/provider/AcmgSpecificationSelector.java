package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.lang.Nullable;

/**
 * Deterministic clause selection from a separately reviewed, pinned catalog.
 * This class neither searches the Internet nor infers disease mechanisms. A
 * missing catalog entry cannot establish that no specialized clause exists.
 * Loading a draft catalog does not activate it in the business assembly.
 */
public final class AcmgSpecificationSelector {
    public static ObjectNode selectBs1(ObjectMapper mapper, java.util.List<JsonNode> policies, JsonNode context) {
        return Bs1Policy.select(mapper, policies, context);
    }
    public static final String SCHEMA = "acmg-specification-selection-v1";
    private static final String INCOMPLETE = "SELECTION_INPUT_INCOMPLETE";
    private static final String GAP = "SPECIALIZED_POLICY_GAP";
    private static final Set<String> CRITERIA = Set.of("BP1", "PP2", "BP3", "PVS1", "PS1", "PS3", "PM1", "PM2", "PM4", "PM5",
            "PP3", "BA1", "BS1", "BP4", "BP7");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode catalog;
    private final ArrayNode policies;
    private final String catalogSha256;
    @Nullable private final String familyCriterion;
    @Nullable private final String catalogIssue;

    public AcmgSpecificationSelector(ObjectMapper mapper, ObjectNode catalog,
                                     String expectedCatalogSha256, ArrayNode policies) {
        this(mapper, catalog, expectedCatalogSha256, policies, null);
    }

    /** Explicit core-only successor; legacy selectors keep their v2/validated-transcript contract. */
    public static AcmgSpecificationSelector forPs2Versioned(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "PS2");
    }

    /** Explicit PM6-only core route; never widens PS2 or legacy v2. */
    public static AcmgSpecificationSelector forPm6Versioned(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "PM6");
    }

    /** Opt-in shared PS2/PM6 selection over an already source-validated selected context. */
    public static AcmgSpecificationSelector forDeNovoSelected(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "DENOVO");
    }

    private boolean allowed(String designation) {
        return "DENOVO".equals(familyCriterion) ? Set.of("PS2", "PM6").contains(designation)
                : familyCriterion != null ? familyCriterion.equals(designation) : CRITERIA.contains(designation);
    }

    /** Explicit PM3-only core route. Ordinary, PS2 and PM6 entry points remain isolated. */
    public static AcmgSpecificationSelector forPm3Versioned(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "PM3");
    }

    /** Explicit BP2 core only; the ordinary criteria set stays unchanged. */
    public static AcmgSpecificationSelector forBp2Versioned(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "BP2");
    }

    /** Explicit PP1 nuclear-family core only; no change to the ordinary criteria set. */
    public static AcmgSpecificationSelector forPp1Versioned(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "PP1");
    }

    /** Explicit BS4-only nuclear-family entry. Ordinary and prior family entries stay isolated. */
    public static AcmgSpecificationSelector forBs4Versioned(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "BS4");
    }

    /** Explicit PP4 single-case core only; ordinary and all prior entries stay isolated. */
    public static AcmgSpecificationSelector forPp4Versioned(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "PP4");
    }

    /** Opt-in BS2 only. Ordinary and the seven older versioned routes stay isolated. */
    public static AcmgSpecificationSelector forBs2Versioned(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies) {
        return new AcmgSpecificationSelector(mapper, catalog, expectedCatalogSha256, policies, "BS2");
    }

    private AcmgSpecificationSelector(ObjectMapper mapper, ObjectNode catalog,
            String expectedCatalogSha256, ArrayNode policies, @Nullable String familyCriterion) {
        this.familyCriterion = familyCriterion;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.catalog = Objects.requireNonNull(catalog, "catalog").deepCopy();
        this.policies = Objects.requireNonNull(policies, "policies").deepCopy();
        this.catalogSha256 = adapter.canonicalSha256(this.catalog);
        this.catalogIssue = validateCatalog(expectedCatalogSha256);
    }

    /** Offline preparation may inspect catalog integrity without inventing a patient context. */
    @Nullable String configurationIssue() { return catalogIssue; }

    public Selection select(JsonNode context, String criterionId) {
        ObjectNode receipt = mapper.createObjectNode();
        receipt.put("schemaVersion", SCHEMA);
        receipt.put("contextId", context.path("contextId").asText());
        receipt.putArray("criterionIds").add(criterionId);
        receipt.put("catalogId", catalog.path("catalogId").asText());
        receipt.put("catalogVersion", catalog.path("catalogVersion").asText());
        receipt.put("catalogSha256", catalogSha256);
        receipt.set("searchedAt", catalog.path("searchedAt").deepCopy());
        receipt.set("searchScope", catalog.path("searchScope").deepCopy());
        String designation = criterionId.startsWith("ACMG_AMP_2015:")
                ? criterionId.substring("ACMG_AMP_2015:".length()) : "";
        if (!allowed(designation))
            return refuse(receipt, INCOMPLETE, "CRITERION_OUTSIDE_ENABLED_SCOPE");
        try {
            if (familyCriterion != null) {
                if (!"DENOVO".equals(familyCriterion) && !"evidence-context-v3".equals(context.path("schemaVersion").asText()))
                    throw new IllegalArgumentException(familyCriterion + " requires explicit v3 context");
                adapter.validateVersionedContext(context);
            } else adapter.validateCompleteContext(context);
        } catch (IllegalArgumentException error) {
            return refuse(receipt, INCOMPLETE, "CONTEXT_IDENTITY_INCOMPLETE_OR_INVALID");
        }
        if (catalogIssue != null) return refuse(receipt, INCOMPLETE, catalogIssue);
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode entry : catalog.path("entries")) {
            if (familyCriterion != null && criterionId.equals(entry.path("criterionId").asText())
                    && matchesBasicScope(context, entry.path("scope"))
                    && "EXACT_ACCESSIONS".equals(entry.at("/scope/transcriptMode").asText())
                    && !context.has("transcript"))
                return refuse(receipt, GAP, "POSSIBLE_SPECIALIZED_TRANSCRIPT_SCOPE_UNRESOLVED");
            if (criterionId.equals(entry.path("criterionId").asText())
                    && matchesScope(context, entry.path("scope"))) matches.add(entry);
        }
        if (matches.isEmpty()) return refuse(receipt, INCOMPLETE, "CLAUSE_SEARCH_SCOPE_NOT_COVERED");
        if (matches.size() != 1) return refuse(receipt, GAP, "CLAUSE_SELECTION_CONFLICT");
        JsonNode entry = matches.get(0);
        receipt.put("catalogEntryId", entry.path("entryId").asText());
        receipt.set("scope", entry.path("scope").deepCopy());
        if (!"COMPLETE".equals(entry.path("searchStatus").asText()))
            return refuse(receipt, INCOMPLETE, "FAILED".equals(entry.path("searchStatus").asText())
                    ? "CLAUSE_SEARCH_FAILED" : "CLAUSE_SEARCH_INCOMPLETE");
        if (!"REVIEWED".equals(entry.at("/review/status").asText())
                || !nonblank(entry.at("/review/reviewId")) || !boundSources(entry.path("sourceIds")))
            return refuse(receipt, INCOMPLETE, "CLAUSE_REVIEW_OR_SOURCE_IDENTITY_MISSING");
        String disposition = entry.path("disposition").asText();
        receipt.put("disposition", disposition);
        switch (disposition) {
            case "SPECIALIZED_UNIMPLEMENTED":
                return refuse(receipt, GAP, "APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED");
            case "SPECIALIZED_VERSION_CONFLICT":
                return refuse(receipt, GAP, "APPLICABLE_SPECIALIZED_VERSION_CONFLICT");
            case "SPECIALIZED_UNRESOLVED":
                return refuse(receipt, GAP, "APPLICABLE_SPECIALIZED_CLAUSE_UNRESOLVED");
            case "SPECIALIZED_IMPLEMENTED", "NO_APPLICABLE_SPECIALIZATION", "GENERAL_CLAUSE_ALLOWED":
                break;
            default:
                return refuse(receipt, INCOMPLETE, "CLAUSE_DISPOSITION_UNKNOWN");
        }
        boolean specialized = "SPECIALIZED_IMPLEMENTED".equals(disposition);
        if (familyCriterion != null && specialized)
            return refuse(receipt, GAP, familyCriterion + "_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED");
        if (!nonblank(entry.path("baseRuleRelationship")))
            return refuse(receipt, INCOMPLETE, "GENERAL_RULE_RELATIONSHIP_UNREVIEWED");
        receipt.set("baseRuleRelationship", entry.path("baseRuleRelationship").deepCopy());
        if (!specialized && (!"COMPLETE".equals(entry.at("/generalPrerequisites/status").asText())
                || !boundSources(entry.at("/generalPrerequisites/sourceIds"))))
            return refuse(receipt, INCOMPLETE, "GENERAL_RULE_PREREQUISITES_MISSING");
        JsonNode binding = entry.path("policyBinding");
        if (!nonblank(binding.path("policyId")) || !nonblank(binding.path("policyVersion")))
            return refuse(receipt, INCOMPLETE, "POLICY_BINDING_IDENTITY_MISSING");
        List<ObjectNode> selected = new ArrayList<>();
        for (JsonNode candidate : policies) {
            if (candidate instanceof ObjectNode object
                    && binding.path("policyId").equals(object.path("policyId"))
                    && binding.path("policyVersion").equals(object.path("policyVersion"))) selected.add(object);
        }
        if (selected.size() != 1)
            return refuse(receipt, specialized ? GAP : INCOMPLETE,
                    selected.isEmpty() ? "IMPLEMENTED_POLICY_NOT_AVAILABLE" : "POLICY_VERSION_CONFLICT");
        ObjectNode policy = selected.get(0);
        if ("BS2".equals(familyCriterion) && (policy.path("criterionIds").size() != 1
                || !criterionId.equals(policy.at("/criterionIds/0").asText())))
            return refuse(receipt, INCOMPLETE, "POLICY_CRITERION_SCOPE_MISMATCH");
        String policySha256 = adapter.canonicalSha256(policy);
        if (!policySha256.equals(binding.path("policySha256").asText()))
            return refuse(receipt, INCOMPLETE, "POLICY_INTEGRITY_MISMATCH");
        if (!"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || !nonblank(policy.at("/review/reviewId")))
            return refuse(receipt, specialized ? GAP : INCOMPLETE, "POLICY_NOT_REVIEWED_IMPLEMENTED");
        boolean criterionBound = false;
        if (policy.path("criterionIds").isArray()) for (JsonNode id : policy.path("criterionIds"))
            if (id.isTextual() && criterionId.equals(id.asText())) criterionBound = true;
        if (!criterionBound) return refuse(receipt, INCOMPLETE, "POLICY_CRITERION_SCOPE_MISMATCH");
        if (!matchesPolicyScope(context, policy.path("scope"))
                || !matchesVariantScope(context, policy.path("scope")))
            return refuse(receipt, INCOMPLETE, "POLICY_CONTEXT_SCOPE_MISMATCH");
        receipt.put("outcome", specialized ? "SPECIALIZED_POLICY_SELECTED" : "GENERAL_POLICY_SELECTED");
        receipt.put("policyId", policy.path("policyId").asText());
        receipt.put("policyVersion", policy.path("policyVersion").asText());
        receipt.put("policySha256", policySha256);
        receipt.set("sourceIds", entry.path("sourceIds").deepCopy());
        return finish(policy.deepCopy(), receipt);
    }

    @Nullable private String validateCatalog(String expectedSha256) {
        if (!catalogSha256.equals(expectedSha256)) return "CATALOG_INTEGRITY_MISMATCH";
        if (!"acmg-specification-catalog-v1".equals(catalog.path("schemaVersion").asText())
                || !nonblank(catalog.path("catalogId")) || !nonblank(catalog.path("catalogVersion"))
                || !nonblank(catalog.path("searchScope")) || !catalog.path("entries").isArray()
                || !"REVIEWED".equals(catalog.at("/review/status").asText())
                || !nonblank(catalog.at("/review/reviewId"))) return "CATALOG_IDENTITY_OR_REVIEW_INCOMPLETE";
        try {
            Instant.parse(catalog.path("searchedAt").asText());
        } catch (DateTimeParseException error) {
            return "CATALOG_SEARCH_DATE_INVALID";
        }
        JsonNode sources = catalog.path("sources");
        if (!sources.isArray() || sources.isEmpty()) return "CATALOG_SOURCE_IDENTITY_MISSING";
        java.util.HashSet<String> sourceIds = new java.util.HashSet<>();
        for (JsonNode source : sources) {
            if (!nonblank(source.path("sourceId")) || !sourceIds.add(source.path("sourceId").asText())
                    || !source.path("url").asText().startsWith("https://")
                    || !nonblank(source.path("version"))
                    || !source.path("sha256").asText().matches("[0-9a-f]{64}"))
                return "CATALOG_SOURCE_IDENTITY_MISSING";
        }
        Set<String> entryIds = new java.util.HashSet<>();
        for (JsonNode entry : catalog.path("entries")) {
            String criterion = entry.path("criterionId").asText();
            if (!nonblank(entry.path("entryId")) || !entryIds.add(entry.path("entryId").asText())
                    || !criterion.startsWith("ACMG_AMP_2015:")
                    || !allowed(criterion.substring("ACMG_AMP_2015:".length())))
                return "CATALOG_CLAUSE_IDENTITY_INVALID";
            JsonNode scope = entry.path("scope");
            if (!scope.path("hgncId").asText().matches("HGNC:[1-9][0-9]*")
                    || !scope.path("mondoId").asText().matches("MONDO:[0-9]{7}")
                    || !Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE", "Y_LINKED")
                            .contains(scope.path("moi").asText()) || !Set.of("GRCh38", "GRCh37").contains(scope.path("assembly").asText())
                    || !(familyCriterion != null ? Set.of("TRANSCRIPT_NOT_REQUIRED", "EXACT_ACCESSIONS")
                        : Set.of("ANY_VALIDATED_TRANSCRIPT", "EXACT_ACCESSIONS")).contains(scope.path("transcriptMode").asText())
                    || ("EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText()) || scope.has("transcripts"))
                        && !validTranscriptScope(scope.path("transcripts")))
                return "CATALOG_CLAUSE_SCOPE_INVALID";
        }
        return null;
    }

    private static boolean validTranscriptScope(JsonNode transcripts) {
        if (!transcripts.isArray() || transcripts.isEmpty()) return false;
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode transcript : transcripts) {
            JsonNode version = transcript.path("version");
            if (!transcript.path("accession").asText().matches("(?:(?:NM|NR)_[A-Za-z0-9]+|ENST[0-9]+)") || !version.isIntegralNumber()
                    || !version.canConvertToInt() || version.asInt() < 1
                    || !ids.add(transcript.path("accession").asText() + "." + version.asInt())) return false;
        }
        return true;
    }

    private boolean boundSources(JsonNode ids) {
        if (!ids.isArray() || ids.isEmpty()) return false;
        for (JsonNode id : ids) {
            boolean found = false;
            for (JsonNode source : catalog.path("sources"))
                if (id.isTextual() && id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }

    private boolean matchesPolicyScope(JsonNode context, JsonNode scope) {
        return matchesBasicScope(context, scope) && matchesPolicyTranscript(context, scope);
    }

    private static boolean matchesBasicScope(JsonNode context, JsonNode scope) {
        return nonblank(scope.path("hgncId")) && scope.path("hgncId").equals(context.at("/gene/hgncId"))
                && nonblank(scope.path("mondoId")) && scope.path("mondoId").equals(context.at("/disease/mondoId"))
                && nonblank(scope.path("moi")) && scope.path("moi").equals(context.path("moi"))
                && nonblank(scope.path("assembly"))
                && scope.path("assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"));
    }

    private boolean matchesPolicyTranscript(JsonNode context, JsonNode scope) {
        if (familyCriterion != null && "TRANSCRIPT_NOT_REQUIRED".equals(scope.path("transcriptMode").asText()))
            return !scope.has("transcripts");
        if (scope.has("transcriptMode")) {
            String mode = scope.path("transcriptMode").asText();
            if ("EXACT_ACCESSIONS".equals(mode)) return matchesTranscript(context, scope.path("transcripts"));
            if (!"ANY_VALIDATED_TRANSCRIPT".equals(mode)) return false;
        }
        return !scope.has("transcripts") || matchesTranscript(context, scope.path("transcripts"));
    }

    private static boolean matchesVariantScope(JsonNode context, JsonNode scope) {
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())) return false;
        return switch (scope.path("variantType").asText()) {
            case "NATIVE_NONPROJECTED_SNV" -> "SNV".equals(context.at("/variant/variantType").asText())
                    && isSnvAllele(context.at("/variant/normalizedAllele"))
                    && isSnvAllele(context.at("/variant/evidenceQueryAllele"));
            case "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT" -> true; // Complete context enforces the 50-nt bound.
            default -> false;
        };
    }

    private static boolean isSnvAllele(JsonNode allele) {
        String reference = allele.path("reference").asText();
        String alternate = allele.path("alternate").asText();
        return reference.matches("[ACGT]") && alternate.matches("[ACGT]") && !reference.equals(alternate);
    }

    private boolean matchesScope(JsonNode context, JsonNode scope) {
        if (!matchesPolicyScope(context, scope)) return false;
        if (familyCriterion != null && "TRANSCRIPT_NOT_REQUIRED".equals(scope.path("transcriptMode").asText())) return true;
        if ("ANY_VALIDATED_TRANSCRIPT".equals(scope.path("transcriptMode").asText())) return true;
        if (!"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText())) return false;
        return matchesTranscript(context, scope.path("transcripts"));
    }

    private static boolean matchesTranscript(JsonNode context, JsonNode transcripts) {
        if (!transcripts.isArray() || transcripts.isEmpty()) return false;
        for (JsonNode transcript : transcripts) {
            if (nonblank(transcript.path("accession")) && transcript.path("version").isIntegralNumber()
                    && transcript.path("version").asInt() > 0
                    && transcript.path("accession").equals(context.at("/transcript/accession"))
                    && transcript.path("version").equals(context.at("/transcript/version"))) return true;
        }
        return false;
    }

    private static boolean nonblank(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank();
    }

    private Selection refuse(ObjectNode receipt, String outcome, String reason) {
        receipt.put("outcome", outcome);
        receipt.put("reasonCode", reason);
        return finish(null, receipt);
    }

    private Selection finish(@Nullable ObjectNode policy, ObjectNode receipt) {
        receipt.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(receipt));
        return new Selection(policy, receipt);
    }

    public record Selection(@Nullable ObjectNode selectedPolicy, ObjectNode receipt) { }
}
