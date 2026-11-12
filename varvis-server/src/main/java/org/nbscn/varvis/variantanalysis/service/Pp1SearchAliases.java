package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Retrieval hints from the recovered candidate, never proof of allele equivalence. */
final class Pp1SearchAliases {
    static final int LIMIT = 20;
    static final String POLICY = "PP1_MANE_CANONICAL_FIRST_MAX20_V1";
    record Selection(ObjectNode target, ObjectNode coverage) {}

    static Selection select(ObjectMapper json, JsonNode candidate, String gene) {
        var transcripts = new ArrayList<JsonNode>();
        candidate.path("transcriptAnnotations").forEach(tx -> {
            if (gene.equals(tx.path("geneSymbol").asText())) transcripts.add(tx);
        });
        transcripts.sort(Comparator.comparingInt(Pp1SearchAliases::priority)
                .thenComparing(tx -> tx.path("transcriptAccession").asText())
                .thenComparing(mappedItem -> mappedItem.toString()));
        var aliases = new LinkedHashSet<String>();
        for (var tx : transcripts) for (String field : List.of("hgvsc", "hgvsp", "hgvsCoding", "hgvsProtein")) {
            String value = tx.path(field).asText("");
            if (value.length() > 200) continue;
            if (value.matches("(?:N[MRP]_[0-9]+\\.[1-9][0-9]*:)?[cgp]\\.[A-Za-z0-9_*+?>=().;\\[\\]-]+")) aliases.add(value);
            if (value.matches("(?:ENST|ENSP)[0-9]+\\.[1-9][0-9]*:[cp]\\.[A-Za-z0-9_*+?>=().;\\[\\]-]+"))
                aliases.add(value.substring(value.indexOf(':') + 1));
        }
        if (aliases.isEmpty()) throw new IllegalArgumentException("PP1_PUBLIC_SEARCH_ALIAS_MISSING");
        var target = json.createObjectNode().put("geneSymbol", gene);
        var selected = target.putArray("variantAliases");
        aliases.stream().limit(LIMIT).forEach(selected::add);
        var coverage = json.createObjectNode().put("policy", POLICY).put("retrievalOnly", true)
                .put("availableAliases", aliases.size()).put("selectedAliases", selected.size())
                .put("omittedAliases", aliases.size() - selected.size());
        var deferred = coverage.putArray("deferredAliases");
        aliases.stream().skip(LIMIT).forEach(deferred::add);
        return new Selection(target, coverage);
    }

    private static int priority(JsonNode tx) {
        return tx.path("maneSelect").asBoolean() ? 0 : tx.path("manePlusClinical").asBoolean() ? 1
                : tx.path("canonical").asBoolean() ? 2 : 3;
    }
}
