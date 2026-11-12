package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Deliberately narrow published-text grammar, independent of any particular gene name.
 * New source conditions require a new interpretation version, never an Applicable-label fallback. */
final class Bp1PublishedPolicy {
    static final String TYPE = "PUBLISHED_ALL_MISSENSE_SUPPORTING_V1";
    static final String RESTRICTION = "PROJECT_RESEARCH_CONSERVATIVE_SPLICE_EXCLUSION_V1";
    static final String TRUNCATING_TYPE = "PUBLISHED_TRUNCATING_GOF_SUPPORTING_V1";
    static final String TRUNCATING_INSTRUCTION = "Truncating, LOF variant in a gene for which primarily missense, GOF variants are known to cause disease.";
    static final String TRUNCATING_COMMENT = "This rule has contraindications for use with RASopathies. Given the disease mechanism is gain-of-function for RASopathies, BP1 should be used for any truncating variant (nonsense, frameshift, affects canonical splice sites, initiation codon, entire gene or multi exon deletion) in genes without established LOF correlation to disease. See the supplemental material regarding dosage sensitivity information for each individual gene and potential association to disorders associated with LOF variants.";
    private Bp1PublishedPolicy() { }

    static void validate(JsonNode selected) {
        var record = selected.path("record"); var interpretation = record.path("interpretation");
        require(TYPE.equals(interpretation.path("type").asText())
                && "1.0.0".equals(interpretation.path("version").asText())
                && RESTRICTION.equals(interpretation.path("projectRestriction").asText())
                && interpretation.size() == 3, "BP1_PUBLISHED_INTERPRETATION_UNSUPPORTED");
        var original = selected.at("/specification/original");
        var clause = original.at(record.path("clausePointer").asText()).path("entContent");
        require("BP1".equals(clause.path("label").asText()) && "Applicable".equals(clause.path("applicability").asText())
                && "Benign".equals(clause.path("baseStrength").asText())
                && "Benign Supporting".equals(clause.path("defaultStrength").asText()), "BP1_PUBLISHED_SOURCE_SEMANTICS_UNSUPPORTED");
        require(clause.path("gene").isArray() && clause.path("gene").size() == 1,
                "BP1_PUBLISHED_SOURCE_SEMANTICS_UNSUPPORTED");
        String gene = clause.path("gene").get(0).asText();
        require(gene.matches("[A-Za-z0-9_.-]{1,100}") && normalize(clause.path("instructionsToUse").asText()).equals(instructions(gene))
                && normalize(clause.path("additionalComments").asText("")).isEmpty(), "BP1_PUBLISHED_SOURCE_CONDITIONS_UNSUPPORTED");
        require(!original.at("/ld/File").isArray() || original.at("/ld/File").isEmpty(), "BP1_PUBLISHED_ATTACHMENTS_UNSUPPORTED");
        int active = 0;
        require(clause.path("strengthDescriptor").isArray(), "BP1_PUBLISHED_STRENGTH_UNSUPPORTED");
        for (var strength : clause.path("strengthDescriptor")) {
            if (!"Applicable".equals(strength.path("applicability").asText())) {
                require(List.of("Not Applicable", "Not Applicable for this VCEP").contains(strength.path("applicability").asText()),
                        "BP1_PUBLISHED_STRENGTH_UNSUPPORTED");
                continue;
            }
            active++;
            require("Supporting".equals(strength.path("strength").asText())
                    && "Apply to all missense variants.".equals(normalize(strength.path("text").asText()))
                    && normalize(strength.path("instructionsToUse").asText()).isEmpty(), "BP1_PUBLISHED_STRENGTH_UNSUPPORTED");
        }
        require(active == 1, "BP1_PUBLISHED_STRENGTH_UNSUPPORTED");
    }
    static String instructions(String gene) {
        return "Based on published and unpublished functional studies, " + gene
                + " has a low rate of missense variants that are non-functional in relevant assays. True missense pathogenic variants are not yet confirmed or refuted but are thought to be exceedingly rare. Given the very low likelihood that missense variants are pathogenic, this rule applies to all missense variants in " + gene + ".";
    }
    /** Recognise the complete finite subtype, not permission to score truncating or missense alleles. */
    static void validateTruncatingSource(JsonNode selected) {
        var row = selected.path("record"); var i = row.path("interpretation");
        require(i.isObject() && i.size() == 5 && TRUNCATING_TYPE.equals(i.path("type").asText())
                && "1.0.0".equals(i.path("version").asText()) && "SUPPORTING".equals(i.path("appliedStrength").asText())
                && i.path("germlineOnly").isBoolean() && i.path("germlineOnly").asBoolean()
                && i.path("requiresNoEstablishedLofDiseaseCorrelation").isBoolean()
                && i.path("requiresNoEstablishedLofDiseaseCorrelation").asBoolean(), "BP1_TRUNCATING_INTERPRETATION_UNSUPPORTED");
        var original = selected.at("/specification/original"); var clause = original.at(row.path("clausePointer").asText()).path("entContent");
        require("BP1".equals(clause.path("label").asText()) && "Applicable".equals(clause.path("applicability").asText())
                && "Benign".equals(clause.path("baseStrength").asText()) && "Benign Supporting".equals(clause.path("defaultStrength").asText())
                && clause.path("gene").isArray() && clause.path("gene").size() == 1
                && clause.path("gene").get(0).asText().matches("[A-Za-z0-9_.-]{1,100}")
                && TRUNCATING_INSTRUCTION.equals(normalize(clause.path("instructionsToUse").asText()))
                && TRUNCATING_COMMENT.equals(normalize(clause.path("additionalComments").asText())), "BP1_TRUNCATING_SOURCE_CONDITIONS_UNSUPPORTED");
        require(Pp2PublishedAssessment.NOTES.equals(normalize(original.at("/entContent/notes").asText())), "BP1_TRUNCATING_GENERAL_SCOPE_UNSUPPORTED");
        var attachments = original.at("/ld/File");
        require(attachments.isMissingNode() || attachments.isArray() && attachments.isEmpty(),
                "BP1_TRUNCATING_ATTACHMENTS_REQUIRE_INTERPRETATION");
        String scope = row.path("scopePointer").asText(); int split = scope.indexOf("/entContent/genes/");
        require(split > 0 && normalize(original.at(scope.substring(0, split)).at("/entContent/generalComments").asText()).isEmpty(),
                "BP1_TRUNCATING_GENERAL_SCOPE_UNSUPPORTED");
        int active = 0;
        require(clause.path("strengthDescriptor").isArray(), "BP1_TRUNCATING_STRENGTH_UNSUPPORTED");
        for (var strength : clause.path("strengthDescriptor")) {
            if (!"Applicable".equals(strength.path("applicability").asText())) {
                require(List.of("Not applicable", "Not Applicable", "Not Applicable for this VCEP").contains(strength.path("applicability").asText()),
                        "BP1_TRUNCATING_STRENGTH_UNSUPPORTED"); continue;
            }
            active++;
            require("Supporting".equals(strength.path("strength").asText()) && "approved".equals(strength.path("status").asText())
                    && TRUNCATING_COMMENT.equals(normalize(strength.path("text").asText()))
                    && normalize(strength.path("instructionsToUse").asText()).isEmpty(), "BP1_TRUNCATING_STRENGTH_UNSUPPORTED");
        }
        require(active == 1, "BP1_TRUNCATING_STRENGTH_UNSUPPORTED");
        for (var other : original.at("/ld/CriteriaCode")) {
            var c = other.path("entContent"); if ("BP1".equals(c.path("label").asText())) continue;
            require(!c.path("instructionsToUse").asText().contains("BP1") && !c.path("additionalComments").asText().contains("BP1"),
                    "BP1_TRUNCATING_CROSS_RULE_UNSUPPORTED");
            for (var s : c.path("strengthDescriptor")) require(!s.path("text").asText().contains("BP1")
                    && !s.path("instructionsToUse").asText().contains("BP1"), "BP1_TRUNCATING_CROSS_RULE_UNSUPPORTED");
        }
    }
    private static String normalize(String value) { return value.replace('\u00a0', ' ').strip().replaceAll("\\s+", " "); }
    static void require(boolean ok, String code) { if (!ok) throw new IllegalArgumentException(code); }
}
