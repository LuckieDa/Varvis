package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Missing-only inheritance join. This supplies a disease association fact,
 * never a LoF mechanism, transcript function or permission to score PVS1.
 * Instances come from the pinned knowledge catalog, not API JSON. */
final class Pvs1StartLossDiseaseScope {
    private final ObjectMapper mapper;
    private final ObjectNode context;
    private final ObjectNode binding;
    private final List<ObjectNode> records;

    Pvs1StartLossDiseaseScope(ObjectMapper mapper, ObjectNode context, ObjectNode binding, List<ObjectNode> records) {
        this.mapper = mapper;
        this.context = context.deepCopy();
        this.binding = binding.deepCopy();
        var copies = new ArrayList<ObjectNode>(records.size());
        for (var record : records) copies.add(record.deepCopy());
        this.records = List.copyOf(copies);
    }

    ObjectNode supplement(JsonNode original, JsonNode requested) {
        var out = mapper.createObjectNode().put("status", "UNRESOLVED").put("methodVersion", "1.0.0")
                .put("basis", "PINNED_EXACT_DISEASE_MISSING_INHERITANCE_JOIN")
                .put("reasonCode", "PVS1_START_LOSS_DISEASE_INHERITANCE_UNRESOLVED");
        out.set("binding", binding.deepCopy());
        if (!context.equals(requested) || records.isEmpty() || !sourceAllowed()) return out;
        String expected = context.path("moi").asText();
        String sourceText = switch (expected) {
            case "AUTOSOMAL_DOMINANT" -> "Autosomal dominant inheritance";
            case "AUTOSOMAL_RECESSIVE" -> "Autosomal recessive inheritance";
            default -> ""; // Do not collapse sex-specific X-linked or other modes.
        };
        if (sourceText.isEmpty() || !collectReferences(out.putArray("records"), expected)) return out;
        if (!(original instanceof ObjectNode object)) return out;

        // Derivation is used only for the relation check. Original bytes remain
        // unchanged and all interpreted clauses are read from the original.
        var derived = object.deepCopy();
        var filled = out.putArray("missingFieldLocators");
        if (!fillRules(derived.at("/ld/RuleSet"), sourceText, filled) || filled.isEmpty()
                || !"MATCHING_CLAUSE_REQUIRES_SELECTION".equals(
                        PredictionSpecificationScope.relation(derived, context, "PVS1"))) return out;
        return out.put("status", "QUALIFIED").put("resolvedMode", expected)
                .put("reasonCode", "PVS1_START_LOSS_DISEASE_INHERITANCE_JOINED")
                .put("originalSourceModified", false).put("mechanismEstablished", false);
    }

    private boolean sourceAllowed() {
        return switch (context.path("dataClass").asText()) {
            case "SYNTHETIC" -> true;
            case "PRIVACY_BOUNDED_RUNTIME" -> "PUBLIC_REFERENCE".equals(binding.path("sourceDataClass").asText());
            default -> false;
        };
    }

    private boolean collectReferences(ArrayNode references, String expected) {
        for (var row : records) {
            if (!matchesRecord(row.path("sourceRecord"), expected)) return false;
            references.add(row.deepCopy());
        }
        return true;
    }

    private boolean matchesRecord(JsonNode raw, String expected) {
        String hgnc = raw.path("hgnc id").asText().strip();
        if (hgnc.matches("[1-9]\\d*")) hgnc = "HGNC:" + hgnc;
        String actual = switch (raw.path("allelic requirement").asText().strip()) {
            case "monoallelic_autosomal" -> "AUTOSOMAL_DOMINANT";
            case "biallelic_autosomal" -> "AUTOSOMAL_RECESSIVE";
            default -> "";
        };
        return hgnc.equals(context.at("/gene/hgncId").asText())
                && raw.path("gene symbol").asText().equals(context.at("/gene/symbol").asText())
                && raw.path("disease MONDO").asText().strip().equals(context.at("/disease/mondoId").asText())
                && expected.equals(actual) && Set.of("strong", "definitive").contains(raw.path("confidence").asText())
                && raw.path("comments").asText().isBlank() && raw.path("review").asText().isBlank()
                && raw.path("cross cutting modifier").asText().isBlank()
                && validReviewDate(raw.path("date of last review").asText());
    }

    private static boolean validReviewDate(String date) {
        try {
            OffsetDateTime.parse(date.replace(' ', 'T'));
            return true;
        } catch (DateTimeException invalid) {
            return false;
        }
    }

    private boolean fillRules(JsonNode rules, String sourceText, ArrayNode filled) {
        int ruleIndex = 0;
        for (var rule : rules) {
            int geneIndex = 0;
            for (var gene : rule.at("/entContent/genes")) {
                String locator = "/ld/RuleSet/" + ruleIndex + "/entContent/genes/" + geneIndex++;
                if (!fillGene(gene, locator, sourceText, filled)) return false;
            }
            ruleIndex++;
        }
        return true;
    }

    private boolean fillGene(JsonNode gene, String locator, String sourceText, ArrayNode filled) {
        if (!gene.path("gene").asText().equals(context.at("/gene/symbol").asText())) return true;
        int diseaseIndex = 0;
        for (var disease : gene.path("diseases")) {
            String diseaseLocator = locator + "/diseases/" + diseaseIndex++;
            if (!disease.path("preferredMondoId").asText().equals(context.at("/disease/mondoId").asText())) continue;
            // Explicit unknown/null/empty/conflicting values are not missing fields.
            if (!(disease instanceof ObjectNode item)
                    || item.has("preferredModeOfInheritance") || item.has("modesOfInheritance")) return false;
            item.put("preferredModeOfInheritance", sourceText);
            filled.add(diseaseLocator + "/preferredModeOfInheritance");
        }
        return true;
    }
}
