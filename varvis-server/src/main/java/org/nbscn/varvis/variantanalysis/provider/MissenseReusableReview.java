package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reuses exact declarations or policy-qualified sources; it never creates final material approval. */
final class MissenseReusableReview {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;

    MissenseReusableReview(ObjectMapper mapper) {
        this.mapper = mapper;
        adapter = new RuntimeEvidenceV2Adapter(mapper);
    }

    /** Called only after the original mapping has validated the declaration and all draft relationships. */
    ObjectNode capture(JsonNode review, JsonNode draft, JsonNode rna, List<? extends JsonNode> registered) {
        boolean automated = MissenseRnaApplicability.automated(review.path("rnaReview"))
                || "SOURCE_VERIFIED".equals(review.at("/referenceAudit/status").asText());
        ObjectNode library = mapper.createObjectNode().put("schemaVersion", automated ? "missense-reusable-review-v2" : "missense-reusable-review-v1")
                .put("status", automated ? "POLICY_QUALIFIED" : "REVIEWED").put("libraryId", "prior-review-sha256-" + adapter.canonicalSha256(review))
                .put("reviewId", review.at("/referenceAudit/reviewId").asText());
        for (String field : List.of("dataClass", "purpose", "background", "referenceAudit", "rnaReview"))
            library.set(field, review.path(field).deepCopy());
        var references = library.putArray("references");
        for (JsonNode selection : review.path("references")) if ("ADOPT".equals(selection.path("decision").asText())) {
            for (JsonNode ref : draft.path("references")) if (ref.path("referenceDraftId").equals(selection.path("referenceDraftId")))
                for (JsonNode assertion : ref.path("assertions")) if (assertion.path("assertionDraftId").equals(selection.path("assertionDraftId"))) {
                    ObjectNode row = references.addObject();
                    row.set("allele", ref.path("allele").deepCopy()); row.set("variationId", ref.path("variationId").deepCopy());
                    row.set("sourceAssertion", assertion.path("sourceAssertion").deepCopy()); row.set("review", selection.path("review").deepCopy());
                }
        }
        var pairs = library.putArray("rnaComparisons");
        for (JsonNode selection : review.path("rnaComparisons")) for (JsonNode pair : rna.path("pairs"))
            if (selection.path("pairDraftId").equals(pair.path("pairDraftId"))) {
                ObjectNode row = pairs.addObject();
                for (String field : List.of("targetAllele", "referenceAllele", "transcript")) row.set(field, pair.path(field).deepCopy());
                row.set("review", selection.path("review").deepCopy());
            }
        var bindings = library.putArray("sourceBindings");
        Set<String> required = requiredSources(library);
        for (JsonNode source : registered) if (required.contains(source.path("sourceId").asText())) bindings.add(source.deepCopy());
        checkSources(library, registered);
        return library;
    }

    void checkSources(JsonNode library, List<? extends JsonNode> registered) {
        require(MissenseComparisonPolicy.sourcesValid(library.path("sourceBindings")), "REUSABLE_SOURCE_BINDINGS_REQUIRED");
        Map<String, JsonNode> actual = new HashMap<>();
        for (JsonNode record : registered) actual.put(record.path("sourceId").asText(), record);
        Set<String> bound = new HashSet<>();
        for (JsonNode record : library.path("sourceBindings")) {
            String id = record.path("sourceId").asText();
            require(bound.add(id) && record.equals(actual.get(id)), "REUSABLE_SOURCE_BINDING_CHANGED_OR_MISSING");
        }
        require(bound.equals(requiredSources(library)), "REUSABLE_SOURCE_BINDINGS_INCOMPLETE");
    }

    private Set<String> requiredSources(JsonNode library) {
        Set<String> ids = new HashSet<>();
        collectSources(library.path("referenceAudit"), ids); collectSources(library.path("rnaReview"), ids);
        for (JsonNode row : library.path("references")) collectSources(row.path("review"), ids);
        for (JsonNode row : library.path("rnaComparisons")) collectSources(row.path("review"), ids);
        return ids;
    }

    private void collectSources(JsonNode value, Set<String> ids) {
        if (value.isObject() && value.has("sourceIds")) for (JsonNode id : value.path("sourceIds")) ids.add(id.asText());
        if (value.isContainerNode()) value.forEach(child -> collectSources(child, ids));
    }

    ObjectNode expand(JsonNode library, JsonNode draft, JsonNode rna, ObjectNode binding) {
        boolean v2 = "missense-reusable-review-v2".equals(library.path("schemaVersion").asText());
        boolean automatedRna = v2 && MissenseRnaApplicability.automated(library.path("rnaReview"));
        require((v2 || "missense-reusable-review-v1".equals(library.path("schemaVersion").asText()))
                && (v2 ? "POLICY_QUALIFIED" : "REVIEWED").equals(library.path("status").asText())
                && MissenseComparisonPolicy.text(library.path("libraryId"))
                && MissenseComparisonPolicy.text(library.path("reviewId")), "REUSABLE_REVIEW_NOT_REVIEWED");
        require(!library.has("approval") && !library.has("draftBinding"), "REUSABLE_REVIEW_APPROVAL_OR_DRAFT_BINDING_FORBIDDEN");
        require(MissenseComparisonPolicy.only(library, Set.of("schemaVersion", "status", "libraryId", "reviewId",
                "dataClass", "purpose", "background", "referenceAudit", "rnaReview", "references", "rnaComparisons", "sourceBindings")),
                "REUSABLE_REVIEW_FIELDS_UNSUPPORTED");
        bounded(library.path("references"), 256); bounded(library.path("rnaComparisons"), 1024);
        require(!MissenseRnaApplicability.automated(library.path("rnaReview")) || automatedRna && library.path("rnaComparisons").isEmpty(),
                "REUSABLE_AUTOMATED_RNA_VERSION_OR_PAIRS_INVALID");
        Map<String, JsonNode> references = new HashMap<>(), pairs = new HashMap<>();
        for (JsonNode row : library.path("references")) {
            require(MissenseComparisonPolicy.only(row, Set.of("allele", "variationId", "sourceAssertion", "review"))
                    && row.path("sourceAssertion").isObject() && row.path("review").isObject(), "REUSABLE_REFERENCE_INVALID");
            String key = referenceKey(row.path("allele"), row.path("variationId"), row.at("/sourceAssertion/scv"));
            require(references.putIfAbsent(key, row) == null, "REUSABLE_REFERENCE_DUPLICATE");
        }
        for (JsonNode row : library.path("rnaComparisons")) {
            require(MissenseComparisonPolicy.only(row, Set.of("targetAllele", "referenceAllele", "transcript", "review"))
                    && row.path("review").isObject(), "REUSABLE_RNA_PAIR_INVALID");
            require(pairs.putIfAbsent(pairKey(row), row) == null, "REUSABLE_RNA_PAIR_DUPLICATE");
        }
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "missense-reference-review-declaration-v1");
        for (String field : List.of("dataClass", "purpose", "background", "referenceAudit", "rnaReview")) {
            require(library.has(field), "REUSABLE_REVIEW_FIELD_MISSING");
            result.set(field, library.path(field).deepCopy());
        }
        result.set("draftBinding", binding.deepCopy());
        ObjectNode provenance = result.putObject("reuseProvenance").put("libraryId", library.path("libraryId").asText())
                .put("libraryReviewId", library.path("reviewId").asText())
                .put("libraryCanonicalSha256", adapter.canonicalSha256(library))
                .put("mode", automatedRna ? "REFERENCE_REUSE_WITH_POLICY_RNA_SCREEN_NO_PAIR_APPROVAL" : "EXACT_PRIOR_DECLARATION_REUSE_NO_NEW_APPROVAL");
        var selections = result.putArray("references");
        var rnaSelections = result.putArray("rnaComparisons");
        Map<String, JsonNode> exactPairs = new HashMap<>();
        for (JsonNode pair : rna.path("pairs")) {
            String id = pair.path("pairDraftId").asText();
            require(!id.isEmpty() && exactPairs.putIfAbsent(id, pair) == null, "RNA_DRAFT_ID_DUPLICATE");
        }
        Set<String> selectedPairs = new HashSet<>(), matchedLibraryKeys = new HashSet<>();
        int adopted = 0, rejected = 0;
        for (JsonNode ref : draft.path("references")) for (JsonNode assertion : ref.path("assertions")) {
            require(selections.size() < 256, "REFERENCE_SELECTION_LIMIT_OR_ARRAY_INVALID");
            JsonNode original = assertion.path("sourceAssertion");
            String key = referenceKey(ref.path("allele"), ref.path("variationId"), original.path("scv"));
            JsonNode prior = references.get(key);
            ObjectNode selected = selections.addObject().put("referenceDraftId", ref.path("referenceDraftId").asText())
                    .put("assertionDraftId", assertion.path("assertionDraftId").asText());
            if (prior == null || !prior.path("sourceAssertion").equals(original)) {
                selected.put("decision", "DO_NOT_ADOPT").put("reason", prior == null
                        ? "REUSABLE_REFERENCE_NOT_FOUND" : "REUSABLE_REFERENCE_ASSERTION_CHANGED");
                rejected++; continue;
            }
            JsonNode matchedPair = null, priorPair = null;
            for (JsonNode pair : exactPairs.values()) if (pair.path("referenceAllele").equals(ref.path("allele"))) {
                require(matchedPair == null, "RNA_REVIEW_AMBIGUOUS");
                matchedPair = pair; priorPair = pairs.get(pairKey(pair));
            }
            if (matchedPair == null || !automatedRna && priorPair == null) {
                selected.put("decision", "DO_NOT_ADOPT").put("reason", "REUSABLE_EXACT_RNA_PAIR_NOT_FOUND");
                rejected++; continue;
            }
            selected.put("decision", "ADOPT").set("review", prior.path("review").deepCopy());
            adopted++; matchedLibraryKeys.add(key);
            String pairId = matchedPair.path("pairDraftId").asText();
            if (!automatedRna && selectedPairs.add(pairId) && priorPair != null) rnaSelections.addObject().put("pairDraftId", pairId)
                    .set("review", priorPair.path("review").deepCopy());
        }
        provenance.put("matchedAssertions", adopted).put("unmatchedAssertions", rejected)
                .put("unusedLibraryReferences", references.size() - matchedLibraryKeys.size())
                .put("matchedRnaPairs", selectedPairs.size());
        return result;
    }

    private String referenceKey(JsonNode allele, JsonNode variationId, JsonNode scv) {
        require(allele.isObject() && MissenseComparisonPolicy.text(variationId)
                && variationId.asText().matches("[1-9][0-9]*") && MissenseComparisonPolicy.text(scv)
                && scv.asText().matches("SCV[0-9]+\\.[1-9][0-9]*"), "REUSABLE_REFERENCE_IDENTITY_INVALID");
        ObjectNode identity = mapper.createObjectNode(); identity.set("allele", allele);
        identity.set("variationId", variationId); identity.set("scv", scv);
        return adapter.canonicalSha256(identity);
    }

    private String pairKey(JsonNode pair) {
        require(pair.path("targetAllele").isObject() && pair.path("referenceAllele").isObject()
                && MissenseComparisonPolicy.text(pair.path("transcript")), "REUSABLE_RNA_PAIR_INVALID");
        ObjectNode identity = mapper.createObjectNode();
        for (String field : List.of("targetAllele", "referenceAllele", "transcript")) identity.set(field, pair.path(field));
        return adapter.canonicalSha256(identity);
    }

    private static void bounded(JsonNode value, int maximum) {
        require(value.isArray() && value.size() <= maximum, "REUSABLE_REVIEW_COUNT_INVALID");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
