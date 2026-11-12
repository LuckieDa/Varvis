package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Finite source semantics for PM1 and PM5 combinations within the same specification.
 * Resolving actual same-context adopted PM5 facts remains the central caller's duty. */
final class PublishedPm1Pm5Exclusion {
    static final String VERSION = "1.1.0";
    private static final String SOURCE_TEXT = "Missense change at an amino acid residue where a different missense change "
            + "determined to be pathogenic has been seen before. * \u22652 different missense changes affecting the amino acid residue. "
            + "* Do not apply PM1 in these situations.";
    private static final String CO_USE = "* Not mutually exclusive with PM1.";
    private static final String CO_USE_WITH_PM5_ONLY_REQUIREMENTS =
            "* Do not apply for any variant that meets BS1 or BA1. " + CO_USE
            + " * Comparison variant must have a Pathogenic or Likely Pathogenic classification reached using these VCEP-specified rules without using PM5."
            + " * The variant of interest must have an equal or higher Grantham score than the Pathogenic / Likely Pathogenic comparison variant "
            + "([https://en.wikipedia.org/wiki/Amino_acid_replacement#Grantham's_distance](https://en.wikipedia.org/wiki/Amino_acid_replacement#Grantham's_distance))."
            + " * SpliceAI must be used to examine both variants for lack of effect on splicing (SpliceAI Δ score \\<0.2).";
    private PublishedPm1Pm5Exclusion() { }

    /** Validates a native PM5 Strong descriptor in the same original specification as the selected PM1 clause. */
    static void validate(JsonNode relation, JsonNode selection, RuntimeEvidenceV2Adapter adapter) {
        require(only(relation, Set.of("criterionId", "relation", "minimumStrength", "sourcePointer", "sourceSha256"))
                && "ACMG_AMP_2015:PM5".equals(relation.path("criterionId").asText())
                && "EXCLUDE_IF_MET_AT_LEAST".equals(relation.path("relation").asText())
                && "STRONG".equals(relation.path("minimumStrength").asText()),
                "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED");
        String pointer = relation.path("sourcePointer").asText(), pm1Pointer = selection.at("/record/clausePointer").asText();
        require(pointer.matches("/ld/CriteriaCode/[0-9]+/entContent/strengthDescriptor/[0-9]+")
                && pm1Pointer.matches("/ld/CriteriaCode/[0-9]+"), "PM1_PUBLISHED_PM5_SOURCE_INVALID");
        var original = selection.at("/specification/original");
        var pm1 = original.at(pm1Pointer).path("entContent");
        String pm5Pointer = pointer.substring(0, pointer.indexOf("/entContent/strengthDescriptor/"));
        var pm5 = original.at(pm5Pointer).path("entContent"); var descriptor = original.at(pointer);
        require("PM1".equals(pm1.path("label").asText()) && "Applicable".equals(pm1.path("applicability").asText())
                && "PM5".equals(pm5.path("label").asText()) && "Applicable".equals(pm5.path("applicability").asText())
                && "Applicable".equals(descriptor.path("applicability").asText())
                && "approved".equals(descriptor.path("status").asText())
                && "Strong".equals(descriptor.path("strength").asText())
                && adapter.canonicalSha256(descriptor).equals(relation.path("sourceSha256").asText()),
                "PM1_PUBLISHED_PM5_SOURCE_INVALID");
        require(diseaseMatches(pm5, selection), "PM1_PUBLISHED_PM5_DISEASE_SCOPE_MISMATCH");
        var genes = genes(pm1.path("gene"));
        require(!genes.isEmpty() && genes.equals(genes(pm5.path("gene"))), "PM1_PUBLISHED_PM5_GENE_SCOPE_MISMATCH");
        require(only(descriptor, Set.of("applicability", "id", "instructionsToUse", "specificationType", "status",
                        "strength", "strengthSepioID", "text", "type"))
                && blank(pm5.path("instructionsToUse")) && blank(pm5.path("additionalComments"))
                && blank(descriptor.path("instructionsToUse"))
                && SOURCE_TEXT.equals(normalize(descriptor.path("text").asText())),
                "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED");
    }

    /** Source restrictions survive an omitted or emptied compiled relation array. */
    static void requireDeclared(JsonNode selection, RuntimeEvidenceV2Adapter adapter) {
        var original = selection.at("/specification/original");
        var pm1 = original.at(selection.at("/record/clausePointer").asText()).path("entContent");
        var pm1Genes = genes(pm1.path("gene"));
        require("PM1".equals(pm1.path("label").asText()), "PM1_PUBLISHED_PM5_SOURCE_INVALID");
        var clauses = original.at("/ld/CriteriaCode");
        require(clauses.isArray(), "PM1_PUBLISHED_PM5_SOURCE_INVALID");
        for (int clauseIndex = 0; clauseIndex < clauses.size(); clauseIndex++) {
            var pm5 = clauses.path(clauseIndex).path("entContent");
            if (!"PM5".equals(pm5.path("label").asText())) continue;
            var pm5Genes = genes(pm5.path("gene"));
            if (!pm1Genes.isEmpty() && !pm5Genes.isEmpty() && java.util.Collections.disjoint(pm1Genes, pm5Genes)) continue;
            // A source explicitly scoped to another disease is no obligation in this selected scope.
            // An explicit compiled reference to it still fails validate above.
            if (explicitOtherDisease(pm5, selection)) continue;
            var descriptors = pm5.path("strengthDescriptor");
            if (inactive(pm5.path("applicability").asText())) {
                for (var descriptor : descriptors) if (!inactive(descriptor.path("applicability").asText()) && relevantDescriptor(descriptor))
                    throw new IllegalArgumentException("PM1_PUBLISHED_PM5_SOURCE_INVALID");
                continue;
            }
            boolean clauseMention = mentionsPm1(pm5.path("instructionsToUse").asText()) || mentionsPm1(pm5.path("additionalComments").asText());
            require(!clauseMention, "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED");
            for (int index = 0; index < descriptors.size(); index++) {
                var descriptor = descriptors.path(index);
                if (inactive(descriptor.path("applicability").asText())) continue;
                String text = normalize(descriptor.path("text").asText());
                if (!relevantDescriptor(descriptor)) continue;
                require("Applicable".equals(pm5.path("applicability").asText())
                        && "Applicable".equals(descriptor.path("applicability").asText()), "PM1_PUBLISHED_PM5_SOURCE_INVALID");
                if (CO_USE.equals(text) || CO_USE_WITH_PM5_ONLY_REQUIREMENTS.equals(text)) {
                    require("approved".equals(descriptor.path("status").asText()) && blank(descriptor.path("instructionsToUse"))
                            && !pm1Genes.isEmpty() && pm1Genes.equals(pm5Genes) && diseaseMatches(pm5, selection),
                            "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED");
                    // Permission is scoped to the pair; other requirements here govern PM5 adoption.
                    continue;
                }
                require(SOURCE_TEXT.equals(text), "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED");
                String pointer = "/ld/CriteriaCode/" + clauseIndex + "/entContent/strengthDescriptor/" + index;
                var expected = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("criterionId", "ACMG_AMP_2015:PM5").put("relation", "EXCLUDE_IF_MET_AT_LEAST")
                        .put("minimumStrength", "STRONG").put("sourcePointer", pointer).put("sourceSha256", adapter.canonicalSha256(descriptor));
                validate(expected, selection, adapter);
                boolean found = false;
                for (var relation : selection.at("/record/interpretation/crossCriterionRelations"))
                    if (pointer.equals(relation.path("sourcePointer").asText())) {
                        validate(relation, selection, adapter); found = true;
                    }
                require(found, "PM1_PUBLISHED_REQUIRED_PEER_CONDITION_OMITTED");
            }
        }
    }

    private static boolean diseaseMatches(JsonNode clause, JsonNode selection) {
        if (!clause.has("disease")) return true;
        var diseases = clause.path("disease");
        if (!diseases.isArray()) return false;
        if (diseases.isEmpty()) return true;
        String target = selection.at("/record/scope/mondoId").asText(); boolean found = false;
        if (!target.matches("MONDO:[0-9]{7}")) return false;
        for (var disease : diseases) {
            if (!disease.isTextual() || !disease.asText().matches("MONDO:[0-9]{7}")) return false;
            if (target.equals(disease.asText())) found = true;
        }
        return found;
    }
    private static boolean explicitOtherDisease(JsonNode clause, JsonNode selection) {
        if (!clause.path("disease").isArray() || clause.path("disease").isEmpty()) return false;
        String target = selection.at("/record/scope/mondoId").asText();
        if (!target.matches("MONDO:[0-9]{7}")) return false;
        for (var disease : clause.path("disease"))
            if (!disease.isTextual() || !disease.asText().matches("MONDO:[0-9]{7}") || target.equals(disease.asText())) return false;
        return true;
    }
    private static boolean mentionsPm1(String text) { return java.util.regex.Pattern.compile("\\bPM1\\b").matcher(text).find(); }
    private static boolean relevantDescriptor(JsonNode descriptor) {
        String text = normalize(descriptor.path("text").asText());
        return mentionsPm1(text) || mentionsPm1(descriptor.path("instructionsToUse").asText())
                || text.startsWith("Missense change at an amino acid residue where a different missense change")
                && text.contains("different missense changes affecting the amino acid residue.");
    }
    private static boolean inactive(String value) {
        return Set.of("not applicable", "not applicable for this vcep").contains(value.strip().toLowerCase(java.util.Locale.ROOT));
    }

    private static Set<String> genes(JsonNode value) {
        var genes = new HashSet<String>();
        if (!value.isArray()) return Set.of();
        for (var gene : value) if (!gene.isTextual() || gene.asText().isBlank() || !genes.add(gene.asText())) return Set.of();
        return genes;
    }
    private static boolean blank(JsonNode value) { return value.isMissingNode() || value.isNull() || value.isTextual() && value.asText().isBlank(); }
    private static String normalize(String value) { return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").strip(); }
    private static boolean only(JsonNode value, Set<String> fields) {
        if (!value.isObject()) return false; var names = value.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false; return true;
    }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
}
