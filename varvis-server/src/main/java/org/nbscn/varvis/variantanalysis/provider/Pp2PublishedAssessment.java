package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Finite translation of explicit published PP2 assertions; not a new constraint-score policy. */
final class Pp2PublishedAssessment {
    static final String TYPE = "PP2_PUBLISHED_TWO_ASSERTIONS_V1";
    static final String VERSION = "1.0.0";
    static final String ASSERTION = "Missense z score is >3.09 in gnomAD.";
    static final String DEFINITION = "Missense variant in a gene that has a low rate of benign missense variation and where missense variants are a common mechanism of disease.";
    static final String APPLICATION = "PP2 is applicable to all RASopathy genes described and curated herein.";
    static final String NOTES = "These criteria should only be used to classify germline variants potentially associated with a RASopathy phenotype. Please note that these adapted criteria are not currently designed to classify variants relative to non-RASopathy phenotypes (e.g. loss of function variants in PTPN11 related to metachondromatosis); however, information about these other genotype:phenotype correlations are noted within the supplemental material. These criteria are also not designed to classify somatic variation in these genes. It is well-known that information about known somatic mutations can be utilized as supporting evidence for classifying variants relative to the RASopathy spectrum disorders given the disease mechanisms are directly correlated. Future initiatives in conjunction with the ClinGen somatic working group will aim to define this relationship in subsequent versions of this documentation. Currently, specific phenotype:genotype correlations regarding somatic variants should not be used as evidence to support germline pathogenicity.";

    private Pp2PublishedAssessment() { }

    static String validate(ObjectNode selection, boolean germlineDeclared) {
        JsonNode row = selection.path("record"), interpretation = row.path("interpretation");
        if (Pp2HcmPublishedSource.TYPE.equals(interpretation.path("type").asText()))
            return Pp2HcmPublishedSource.issue(new com.fasterxml.jackson.databind.ObjectMapper(), selection, germlineDeclared);
        if (Pp2PublishedSpectrum.TYPE.equals(interpretation.path("type").asText())) {
            if (!Pp2PublishedSpectrum.TYPE.equals(selection.at("/authoritySpectrum/method").asText())
                    || !Pp2PublishedSpectrum.negativeConditions(interpretation.path("conditions")))
                return "PP2_AUTHORITY_SPECTRUM_INVALID";
            return germlineDeclared ? null : "PP2_GERMLINE_SCOPE_NOT_DECLARED";
        }
        if (Pp2PublishedSpectrum.CONSTRAINT_TYPE.equals(interpretation.path("type").asText())) {
            if (!Pp2PublishedSpectrum.CONSTRAINT_TYPE.equals(selection.at("/authoritySpectrum/method").asText())
                    || !"PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED".equals(Pp2ConditionDecision.reason(interpretation.path("conditions"))))
                return "PP2_AUTHORITY_SPECTRUM_INVALID";
            return germlineDeclared ? null : "PP2_GERMLINE_SCOPE_NOT_DECLARED";
        }
        if (!only(interpretation, Set.of("type", "version", "appliedStrength", "germlineOnly", "conditions", "sourceTextBindings"))
                || !TYPE.equals(interpretation.path("type").asText()) || !VERSION.equals(interpretation.path("version").asText()))
            return "PP2_PUBLISHED_INTERPRETATION_UNSUPPORTED";
        if (!"SUPPORTING".equals(interpretation.path("appliedStrength").asText())
                || !interpretation.path("germlineOnly").isBoolean() || !interpretation.path("germlineOnly").asBoolean())
            return "PP2_PUBLISHED_SCOPE_OR_STRENGTH_INVALID";
        JsonNode original = selection.at("/specification/original");
        JsonNode clause = original.at(row.path("clausePointer").asText()).path("entContent");
        if (!DEFINITION.equals(clause.path("instructionsToUse").asText()))
            return "PP2_PUBLISHED_TOP_LEVEL_INSTRUCTION_UNSUPPORTED";
        JsonNode binding = interpretation.path("sourceTextBindings");
        if (!only(binding, Set.of("supportingText", "comments", "notes"))) return "PP2_PUBLISHED_BINDING_INVALID";
        int count = 0;
        for (JsonNode strength : clause.path("strengthDescriptor")) {
            String app = strength.path("applicability").asText();
            if (!app.toLowerCase(java.util.Locale.ROOT).startsWith("applicable")) continue;
            count++;
            if (!"Applicable".equals(app) || !"approved".equals(strength.path("status").asText())
                    || !"Supporting".equals(strength.path("strength").asText())
                    || !strength.path("instructionsToUse").asText().isBlank()
                    || !ASSERTION.equals(strength.path("text").asText())
                    || !strength.path("text").equals(binding.path("supportingText")))
                return "PP2_PUBLISHED_ACTIVE_CLAUSE_UNSUPPORTED";
        }
        if (count != 1 || !APPLICATION.equals(clause.path("additionalComments").asText())
                || !clause.path("additionalComments").equals(binding.path("comments"))
                || !NOTES.equals(original.at("/entContent/notes").asText())
                || !original.at("/entContent/notes").equals(binding.path("notes")))
            return "PP2_PUBLISHED_SOURCE_CONDITIONS_CHANGED";
        String scopePointer = row.path("scopePointer").asText();
        String rulePointer = scopePointer.substring(0, scopePointer.indexOf("/entContent/genes/"));
        if (!original.at(rulePointer).at("/entContent/generalComments").asText().isBlank())
            return "PP2_PUBLISHED_GENERAL_COMMENTS_UNSUPPORTED";
        // No other active criterion may quietly impose an additional PP2 dependency.
        for (JsonNode other : original.at("/ld/CriteriaCode")) {
            JsonNode c = other.path("entContent");
            if ("PP2".equals(c.path("label").asText())) continue;
            // An explicitly retired criterion cannot impose a current dependency.
            // Both levels must agree; unknown/missing or contradictory states remain checked.
            if (entirelyInactive(c)) continue;
            if (c.path("additionalComments").asText().contains("PP2") || c.path("instructionsToUse").asText().contains("PP2"))
                return "PP2_PUBLISHED_CROSS_RULE_CONDITION_UNSUPPORTED";
            for (JsonNode strength : c.path("strengthDescriptor")) {
                if (inactive(strength.path("applicability").asText())) continue;
                if (strength.path("text").asText().contains("PP2") || strength.path("instructionsToUse").asText().contains("PP2"))
                    return "PP2_PUBLISHED_CROSS_RULE_CONDITION_UNSUPPORTED";
            }
        }
        JsonNode conditions = interpretation.path("conditions");
        if (!only(conditions, Set.of("missenseMechanism", "lowBenignMissense")) || conditions.size() != 2)
            return "PP2_PUBLISHED_TWO_CONDITIONS_REQUIRED";
        for (String key : List.of("missenseMechanism", "lowBenignMissense")) {
            JsonNode condition = conditions.path(key);
            String basis = "missenseMechanism".equals(key) ? "PUBLISHED_PP2_APPLICATION" : "PUBLISHED_CONSTRAINT_ASSERTION";
            if (!only(condition, Set.of("conclusion", "basisType")) || !"SUPPORTED".equals(condition.path("conclusion").asText())
                    || !basis.equals(condition.path("basisType").asText())) return "PP2_PUBLISHED_CONDITION_NOT_BOUND";
        }
        return germlineDeclared ? null : "PP2_GERMLINE_SCOPE_NOT_DECLARED";
    }

    static boolean entirelyInactive(JsonNode clause) {
        if (!inactive(clause.path("applicability").asText())
                || !clause.path("strengthDescriptor").isArray() || clause.path("strengthDescriptor").isEmpty()) return false;
        for (JsonNode strength : clause.path("strengthDescriptor"))
            if (!inactive(strength.path("applicability").asText())) return false;
        return true;
    }

    private static boolean inactive(String value) {
        return Set.of("Not applicable", "Not Applicable", "Not Applicable for this VCEP").contains(value);
    }

    private static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        var names = node.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }
}
