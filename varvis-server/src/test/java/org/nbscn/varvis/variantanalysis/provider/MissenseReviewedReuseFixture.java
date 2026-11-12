package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Invented prior judgments for engineering tests only. */
final class MissenseReviewedReuseFixture {
    static ObjectNode library(ObjectMapper mapper, JsonNode declaration, JsonNode reference, JsonNode assertion, JsonNode pair) {
        ObjectNode library = mapper.createObjectNode().put("schemaVersion", "missense-reusable-review-v1")
                .put("status", "REVIEWED").put("libraryId", "TEST_ONLY_PRIOR_LIBRARY").put("reviewId", "TEST_ONLY_PRIOR_REVIEW");
        for (String field : List.of("dataClass", "purpose", "background", "referenceAudit", "rnaReview")) library.set(field, declaration.path(field).deepCopy());
        ObjectNode row = library.putArray("references").addObject();
        row.set("allele", reference.path("allele").deepCopy()); row.set("variationId", reference.path("variationId").deepCopy());
        row.set("sourceAssertion", assertion.path("sourceAssertion").deepCopy());
        for (JsonNode choice : declaration.path("references")) if ("ADOPT".equals(choice.path("decision").asText())) row.set("review", choice.path("review").deepCopy());
        ObjectNode rna = library.putArray("rnaComparisons").addObject();
        for (String field : List.of("targetAllele", "referenceAllele", "transcript")) rna.set(field, pair.path(field).deepCopy());
        rna.set("review", declaration.at("/rnaComparisons/0/review").deepCopy());
        return library;
    }

    static ObjectNode expertRow(ObjectMapper mapper, JsonNode reference, JsonNode assertion, String mondo, String gene, String sourceId) {
        ObjectNode row = mapper.createObjectNode().put("referenceId", "TEST_ONLY_EXPERT")
                .put("bindingStatus", "EXACT_SOURCE_BOUND").put("reviewStatus", "UNREVIEWED").put("scientificAdoption", false);
        row.set("allele", reference.path("allele").deepCopy()); row.set("variationId", reference.path("variationId").deepCopy());
        ObjectNode original = assertion.deepCopy(); original.put("recordStatus", "current");
        String text = "TEST_ONLY expert final classification; criteria applied (specification version TEST_ONLY@1): PS3.";
        original.putArray("comments").addObject().put("scope", "CLASSIFICATION").put("xpath", "/ClinVarResult-Set[1]/Comment[1]").put("text", text);
        row.set("originalAssertion", original); row.putArray("geneSymbols").add(gene); row.putArray("sourceIds").add(sourceId);
        row.putObject("disease").put("id", mondo);
        row.putArray("gapReasons"); row.putArray("appliedComparisonCriteria"); row.putArray("summaryMetCriteria"); row.putArray("criterionAssessments");
        row.put("sourceAppliedCriteriaStatus", "EXPLICIT_SOURCE_LIST");
        ObjectNode statement = row.putArray("sourceAppliedCriteriaStatements").addObject().put("status", "EXPLICIT_SOURCE_LIST")
                .put("sourceId", sourceId).put("xpath", "/ClinVarResult-Set[1]/Comment[1]").put("originalText", text)
                .put("rawSuffix", " PS3.").put("scientificAdoption", false);
        statement.putArray("specificationVersionMentions").add("TEST_ONLY@1");
        statement.putArray("criteria").addObject().put("original", "PS3").put("code", "PS3").putNull("strengthSuffix");
        return row;
    }
    static void sourceBindings(ObjectNode library, ObjectNode manifest) {
        var bindings = library.putArray("sourceBindings");
        var ids = new java.util.HashSet<String>();
        collect(library.path("referenceAudit"), ids); collect(library.path("rnaReview"), ids);
        for (JsonNode r : library.path("references")) collect(r.path("review"), ids);
        for (JsonNode r : library.path("rnaComparisons")) collect(r.path("review"), ids);
        for (String role : List.of("referenceDraft", "rnaDraft", "draftReport")) {
            ObjectNode record = manifest.path("factSources").path(role).deepCopy();
            if (ids.remove(record.path("sourceId").asText())) bindings.add(record.put("sha256", manifest.path(role).path("canonicalSha256").asText()));
        }
        for (JsonNode d : manifest.path("reviewEvidence")) {
            ObjectNode record = d.path("sourceRecord").deepCopy();
            if (ids.remove(record.path("sourceId").asText())) bindings.add(record.put("sha256", d.path("canonicalSha256").asText()));
        }
        if (!ids.isEmpty()) throw new IllegalStateException("Test source binding missing " + ids);
    }
    private static void collect(JsonNode node, java.util.Set<String> ids) {
        if (node.isObject() && node.has("sourceIds")) for (JsonNode id : node.path("sourceIds")) ids.add(id.asText());
        if (node.isContainerNode()) node.forEach(child -> collect(child, ids));
    }
}
