package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Negative scope proof only. This never translates a threshold or adopts a specialist clause. */
final class PredictionSpecificationScope {
    private PredictionSpecificationScope() {}

    static String relation(JsonNode original, JsonNode context, String criterion) {
        String hgnc = context.at("/gene/hgncId").asText(), disease = context.at("/disease/mondoId").asText();
        if (!disease.matches("MONDO:[0-9]{7}")) return "SCOPE_UNRESOLVED";
        var symbols = new HashSet<String>();
        for (var gene : original.at("/ld/Gene")) if (hgnc.equals(gene.at("/entContent/HGNC/hgnc_id").asText()))
            symbols.add(gene.at("/entContent/HGNC/symbol").asText());
        if (symbols.size() != 1 || symbols.contains("")) return "SCOPE_UNRESOLVED";
        String symbol = symbols.iterator().next();
        boolean found = false;
        for (var code : original.at("/ld/CriteriaCode")) {
            var clause = code.path("entContent");
            if (!criterion.equals(clause.path("label").asText())) continue;
            found = true;
            var genes = clause.path("gene");
            if (!strings(genes)) return "SCOPE_UNRESOLVED";
            if (!genes.isEmpty() && !contains(genes, symbol)) continue;
            var diseases = clause.path("disease");
            if (!diseases.isMissingNode() && !strings(diseases)) return "SCOPE_UNRESOLVED";
            if (diseases.isArray() && !diseases.isEmpty() && !contains(diseases, disease)) continue;
            boolean geneFound = false, diseaseFound = false;
            for (var rule : original.at("/ld/RuleSet")) {
                if (!rule.at("/entContent/genes").isArray()) return "SCOPE_UNRESOLVED";
                for (var gene : rule.at("/entContent/genes")) {
                    if (!symbol.equals(gene.path("gene").asText())) continue;
                    geneFound = true;
                    if (!gene.path("diseases").isArray() || gene.path("diseases").isEmpty()) return "SCOPE_UNRESOLVED";
                    for (var item : gene.path("diseases")) {
                        if (!item.path("preferredMondoId").asText().matches("MONDO:[0-9]{7}")) return "SCOPE_UNRESOLVED";
                        if (!disease.equals(item.path("preferredMondoId").asText())) continue;
                        diseaseFound = true;
                        String mode = mode(item.path("preferredModeOfInheritance").asText());
                        if (mode.isEmpty()) return "SCOPE_UNRESOLVED";
                        for (var other : item.path("modesOfInheritance"))
                            if (!mode.equals(mode(other.path("modeOfInheritance").asText()))) return "SCOPE_UNRESOLVED";
                        if (!Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED").contains(context.path("moi").asText())) return "SCOPE_UNRESOLVED";
                        if (mode.equals(context.path("moi").asText())) return "MATCHING_CLAUSE_REQUIRES_SELECTION";
                    }
                }
            }
            if (!geneFound || !diseaseFound && diseases.isArray() && !diseases.isEmpty()) return "SCOPE_UNRESOLVED";
        }
        return found ? "OUTSIDE_EXPLICIT_SCOPE" : "CRITERION_SCOPE_NOT_RECORDED";
    }

    private static boolean strings(JsonNode array) {
        if (!array.isArray()) return false;
        for (var value : array) if (!value.isTextual() || value.asText().isBlank()) return false;
        return true;
    }
    private static boolean contains(JsonNode array, String text) { for (var v : array) if (text.equals(v.asText())) return true; return false; }
    private static String mode(String text) {
        return switch (text.strip()) {
            case "Autosomal dominant inheritance", "Autosomal Dominant" -> "AUTOSOMAL_DOMINANT";
            case "Autosomal recessive inheritance", "Autosomal Recessive" -> "AUTOSOMAL_RECESSIVE";
            case "X-linked inheritance" -> "X_LINKED";
            default -> "";
        };
    }
}
