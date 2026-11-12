package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Preserves a published native expert disease assertion. No comparison-reference qualification or new classification. */
final class Pp2NativeExpertAssertion {
    static final String VERSION = "pp2-native-expert-assertion-v1";
    private Pp2NativeExpertAssertion() { }

    @Nullable static String issue(JsonNode facts, JsonNode sourcePins, JsonNode actualOriginals) {
        JsonNode original = facts.path("originalAssertion"), summary = facts.path("originalExpertSummary");
        if (!facts.path("allele").isObject() || !facts.path("variationId").asText().matches("[1-9][0-9]{0,11}")
                || !original.path("scv").asText().matches("SCV[0-9]{9}\\.[1-9][0-9]*"))
            return "PP2_NATIVE_EXPERT_ASSERTION_IDENTITY_INVALID";
        if (!"current".equals(original.path("recordStatus").asText())) return "PP2_NATIVE_EXPERT_ASSERTION_NOT_CURRENT";
        if (!Set.of("reviewed by expert panel", "practice guideline").contains(original.path("reviewStatus").asText()))
            return "PP2_NATIVE_EXPERT_GRADE_NOT_ESTABLISHED";
        String classification = normal(original.path("classification").asText());
        if (!Set.of("pathogenic", "likely pathogenic").contains(classification))
            return "PP2_NATIVE_EXPERT_ORIGINAL_CLASS_NOT_PATHOGENIC_OR_LIKELY_PATHOGENIC";
        if (!facts.path("erepoUuid").asText().matches("[a-f0-9-]{36}")
                || !facts.path("erepoVersion").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            return "PP2_NATIVE_EXPERT_EREPO_IDENTITY_UNRESOLVED";
        if (!facts.at("/disease/id").asText().matches("MONDO:[0-9]{7}") || facts.at("/disease/label").asText().isBlank())
            return "PP2_NATIVE_EXPERT_DISEASE_UNRESOLVED";
        if (summary.isObject()) {
            if (!summary.path("retracted").isBoolean() || summary.path("retracted").asBoolean())
                return "PP2_NATIVE_EXPERT_SOURCE_RETRACTED_OR_STATUS_UNRESOLVED";
            if (!facts.path("variationId").asText().equals(summary.path("cvId").asText())
                    || !facts.path("erepoUuid").equals(summary.path("uuid"))
                    || !facts.path("erepoVersion").equals(summary.path("docVersion"))
                    || !facts.at("/disease/id").equals(summary.path("mondoId"))
                    || !classification.equals(normal(summary.path("classification").asText())))
                return "PP2_NATIVE_EXPERT_SUMMARY_ASSERTION_CONFLICT";
            boolean sameGene = false;
            for (var gene : facts.path("geneSymbols")) if (gene.equals(summary.path("gene"))) sameGene = true;
            if (!sameGene) return "PP2_NATIVE_EXPERT_SUMMARY_GENE_CONFLICT";
        } else {
            JsonNode metadata = facts.path("originalRecordMetadata");
            if (!metadata.isObject() || !facts.path("erepoVersion").equals(metadata.path("version")))
                return "PP2_NATIVE_EXPERT_PUBLISHED_LINK_UNRESOLVED";
            if (metadata.path("retracted").asBoolean(false)) return "PP2_NATIVE_EXPERT_SOURCE_RETRACTED_OR_STATUS_UNRESOLVED";
        }
        if (!facts.path("sourceIds").isArray() || facts.path("sourceIds").isEmpty()) return "PP2_NATIVE_EXPERT_SOURCE_PIN_MISMATCH";
        Set<String> ids = new HashSet<>();
        for (var source : facts.path("sourceIds")) {
            if (!source.isTextual() || !ids.add(source.asText())) return "PP2_NATIVE_EXPERT_SOURCE_PIN_MISMATCH";
            String pin = sourcePins.path(source.asText()).asText();
            if (!pin.matches("[a-f0-9]{64}") || !actualOriginals.has(pin)
                    || !actualOriginals.path(pin).path("rawSha256").asText().equals(pin)) return "PP2_NATIVE_EXPERT_SOURCE_PIN_MISMATCH";
        }
        // The controlled native reader already replayed exact XML/summary/SEPIO linkage from these bytes.
        // The original expert classification may legitimately use PS1 or PM5. Neither code is an exclusion here.
        return null;
    }
    private static String normal(String value) { return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
}
