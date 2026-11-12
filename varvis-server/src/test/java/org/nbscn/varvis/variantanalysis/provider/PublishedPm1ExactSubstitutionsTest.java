package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** GN121@2.3 native descriptor; peptide/codon probes are explicitly synthetic, not JAK3 variant replay. */
class PublishedPm1ExactSubstitutionsTest {
    private static final String SOURCE_TEXT = "Defined to include missense alterations of two JH2 domain residues: R651W and C759R (PMID: 11668610).";
    // Independently extracted canonical descriptor pin from raw-024-contents-80.json,
    // raw SHA f69c4f00d82115b09288f771aceeec167c419b088e284cb4c3922f6bb3461198,
    // /data/3/ld/CriteriaCode/14/entContent/strengthDescriptor/3.
    private static final String DESCRIPTOR_SHA = "4826ec3c26b756ae135473e578c587cbfb7d38cb6317c27ac9ac4851693f33f6";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private ObjectNode selection, model, descriptor;

    private void setup() throws Exception {
        descriptor = (ObjectNode) mapper.readTree("""
                {"applicability":"Applicable","id":"006","instructionsToUse":"","specificationType":["Gene-specific"],
                 "status":"approved","strength":"Moderate","strengthSepioID":"SEPIO:0000216",
                 "text":"Defined to include missense alterations of two JH2 domain residues: R651W and C759R (PMID: 11668610).",
                 "type":"EvidenceLineStrength"}
                """);
        selection = mapper.createObjectNode().put("declaredTranscript", "NM_000215.4");
        var record = selection.putObject("record").put("clausePointer", "/ld/CriteriaCode/0");
        var interpretation = record.putObject("interpretation").put("type", PublishedPm1RegionAssessment.TYPE)
                .put("version", "1.0.0").put("consequenceScope", "PURE_MISSENSE_SNV")
                .put("qualificationBasis", "PUBLISHED_EXPERT_SPECIFICATION");
        interpretation.putObject("proteinIdentity").put("mode", "SOURCE_TRANSCRIPT_DEFINED");
        interpretation.putArray("residualConditions"); interpretation.putArray("crossCriterionRelations"); interpretation.putArray("precedence");
        interpretation.putArray("rules");
        selection.putObject("specification").putObject("original").putObject("ld").putArray("CriteriaCode")
                .addObject().putObject("entContent").put("label", "PM1").putArray("strengthDescriptor").add(descriptor);
        String[] codons = new String[800]; Arrays.fill(codons, "GCT");
        codons[0] = "ATG"; codons[650] = "CGG"; codons[758] = "TGT";
        model = mapper.createObjectNode().put("cdsSequence", String.join("", codons) + "TAA").put("proteinLength", 800)
                .put("transcript", "NM_000215.4").put("protein", "NP_000206.2");
        addRule("first-source-substitution", 651, "R", "W");
        addRule("second-source-substitution", 759, "C", "R");
    }

    private ArrayNode rules() { return (ArrayNode) selection.at("/record/interpretation/rules"); }
    @Test void omittingTheCompilerFieldCannotTurnAnExactSubstitutionIntoAPlainLocation() throws Exception {
        setup(); rule(0).remove("exactSubstitutions");
        var input = association(651, "CGG", "TGG");
        var out = new PublishedPm1RegionAssessment(mapper).assess(selection, model, input);
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("PM1_PUBLISHED_EXACT_SUBSTITUTIONS_REQUIRED", out.path("reasonCode").asText());
        assertEquals("NONE", out.path("appliedStrength").asText());
    }
    private ObjectNode rule(int index) { return (ObjectNode) rules().get(index); }
    private ObjectNode addRule(String id, int position, String reference, String alternate) {
        var rule = rules().addObject().put("ruleId", id).put("start", position).put("end", position)
                .put("regionType", "CRITICAL_RESIDUE").put("effect", "SUPPORT").put("strength", "MODERATE")
                .put("sourcePointer", "/ld/CriteriaCode/0/entContent/strengthDescriptor/0").put("sourceSha256", adapter.canonicalSha256(descriptor));
        rule.putArray("referenceResidues").addObject().put("position", position).put("aminoAcid", reference);
        rule.putArray("exactSubstitutions").addObject().put("position", position).put("refAminoAcid", reference).put("altAminoAcid", alternate);
        return rule;
    }
    private ObjectNode association(int position, String reference, String alternate) {
        String cds = model.path("cdsSequence").asText();
        String original = cds.substring((position - 1) * 3, position * 3);
        int difference = -1;
        for (int index = 0; index < 3; index++) if (reference.charAt(index) != alternate.charAt(index)) difference = index;
        return mapper.createObjectNode().put("proteinPosition", position).put("cdsPosition", (position - 1) * 3 + difference + 1)
                .put("refCodon", reference).put("altCodon", alternate)
                .put("refAminoAcid", Character.toString(TruncatingSequence.translateCodon(original)))
                .put("altAminoAcid", Character.toString(TruncatingSequence.translateCodon(alternate)))
                .put("nativeModelSha256", adapter.canonicalSha256(model));
    }
    private ObjectNode assess(ObjectNode association) { return new PublishedPm1RegionAssessment(mapper).assess(selection, model, association); }

    @Test void nativeApprovedDescriptorMatchesTheIndependentlyPinnedOriginal() throws Exception {
        setup(); assertEquals(SOURCE_TEXT, descriptor.path("text").asText());
        assertEquals(DESCRIPTOR_SHA, adapter.canonicalSha256(descriptor));
    }

    @ParameterizedTest @CsvSource({"651,CGG,TGG,MET,MODERATE", "759,TGT,CGT,MET,MODERATE",
            "651,CGG,CAG,NOT_EVALUATED,NONE", "759,TGT,TAT,NOT_EVALUATED,NONE",
            "650,GCT,GTT,NOT_EVALUATED,NONE", "760,GCT,GTT,NOT_EVALUATED,NONE"})
    void exactSubstitutionsAndIndependentBoundaryControls(int position, String ref, String alt, String status, String strength) throws Exception {
        setup(); var sourceBefore = selection.deepCopy(); var modelBefore = model.deepCopy();
        var input = association(position, ref, alt); var inputBefore = input.deepCopy(); var result = assess(input);
        assertEquals(status, result.path("status").asText(), result::toPrettyString);
        assertEquals(strength, result.path("appliedStrength").asText());
        if (position == 651 || position == 759) {
            assertEquals("MET".equals(status) ? "MATCH" : "NO_MATCH", result.at("/conditionChecks/0/status").asText());
            assertEquals("MET".equals(status) ? "PM1_PUBLISHED_CRITICAL_LOCATION_MATCH"
                    : "PM1_PUBLISHED_EXACT_SUBSTITUTION_NOT_COVERED", result.path("reasonCode").asText());
        } else assertEquals("PM1_PUBLISHED_SOURCE_SCOPE_NOT_COVERED", result.path("reasonCode").asText());
        assertEquals(sourceBefore, selection); assertEquals(modelBefore, model); assertEquals(inputBefore, input);
    }

    @ParameterizedTest @ValueSource(strings = {"missing-alt", "missing-codon", "missing-model-sha", "wrong-model-sha",
            "wrong-label", "wrong-codon", "wrong-cds-position", "mnv", "synonymous", "fractional-position", "wrong-reference"})
    void incompleteOrInconsistentAssociationCannotAcquireAnExactMatch(String mutation) throws Exception {
        setup(); var association = association(651, "CGG", "TGG");
        switch (mutation) {
            case "missing-alt" -> association.remove("altAminoAcid");
            case "missing-codon" -> association.remove("altCodon");
            case "missing-model-sha" -> association.remove("nativeModelSha256");
            case "wrong-model-sha" -> association.put("nativeModelSha256", "0".repeat(64));
            case "wrong-label" -> association.put("altAminoAcid", "Q");
            case "wrong-codon" -> association.put("refCodon", "AGA");
            case "wrong-cds-position" -> association.put("cdsPosition", 1952);
            case "mnv" -> association.put("altCodon", "TGA");
            case "synonymous" -> association.put("altCodon", "CGT").put("altAminoAcid", "R").put("cdsPosition", 1953);
            case "fractional-position" -> association.put("proteinPosition", 651.5);
            case "wrong-reference" -> association.put("refAminoAcid", "K");
            default -> throw new AssertionError(mutation);
        }
        var result = assess(association);
        assertEquals("NOT_EVALUATED", result.path("status").asText(), result::toPrettyString);
        assertEquals("NONE", result.path("appliedStrength").asText()); assertFalse(result.has("selectedRule"));
        assertNotEquals("PM1_PUBLISHED_EXACT_SUBSTITUTION_NOT_COVERED", result.path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"invented-alt", "duplicate-entry", "extra-field", "empty-list", "fractional-condition",
            "wrong-source-sha", "wrong-pointer", "unapproved", "source-negation", "source-extra-condition", "source-count", "omitted-source-substitution"})
    void sourceBoundFiniteGrammarCannotBeBroadenedOrPartiallyCompiled(String mutation) throws Exception {
        setup(); var condition = (ObjectNode) rule(0).at("/exactSubstitutions/0");
        switch (mutation) {
            case "invented-alt" -> condition.put("altAminoAcid", "L");
            case "duplicate-entry" -> rule(0).withArray("exactSubstitutions").add(condition.deepCopy());
            case "extra-field" -> condition.put("ignoreMissenseEffect", true);
            case "empty-list" -> rule(0).putArray("exactSubstitutions");
            case "fractional-condition" -> condition.put("position", 651.5);
            case "wrong-source-sha" -> rule(0).put("sourceSha256", "0".repeat(64));
            case "wrong-pointer" -> rule(0).put("sourcePointer", "/ld/CriteriaCode/1/entContent/strengthDescriptor/0");
            case "unapproved" -> descriptor.put("status", "not approved");
            case "source-negation" -> descriptor.put("text", "Do not apply to R651W and C759R.");
            case "source-extra-condition" -> descriptor.put("text", SOURCE_TEXT + " Apply only when PM2 is met.");
            case "source-count" -> descriptor.put("text", SOURCE_TEXT.replace("two", "three"));
            case "omitted-source-substitution" -> rule(0).put("end", 759).put("regionType", "CRITICAL_FUNCTIONAL_REGION");
            default -> throw new AssertionError(mutation);
        }
        if (mutation.startsWith("source-") || mutation.equals("unapproved"))
            for (var rule : rules()) ((ObjectNode) rule).put("sourceSha256", adapter.canonicalSha256(descriptor));
        var result = assess(association(651, "CGG", "TGG"));
        assertEquals("NOT_EVALUATED", result.path("status").asText(), result::toPrettyString);
        assertEquals("NONE", result.path("appliedStrength").asText()); assertFalse(result.has("selectedRule"));
    }

    @Test void anotherSyntheticGeneUsesTheSameParameterizedSourceGrammar() throws Exception {
        setup(); model.put("transcript", "NM_999999.1").put("protein", "NP_999999.1").put("geneSymbol", "SYNTHETIC_OTHER");
        String[] codons = {"ATG", "GAA", "CCT", "GGC", "TTT", "AAA"};
        model.put("proteinLength", 6).put("cdsSequence", String.join("", codons) + "TAA");
        descriptor.put("text", "Defined to include missense alterations of one catalytic domain residue: E2D (PMID: 1234).");
        rules().removeAll(); addRule("independent-synthetic-source", 2, "E", "D");
        assertEquals("MET", assess(association(2, "GAA", "GAC")).path("status").asText());
        assertEquals("NOT_EVALUATED", assess(association(2, "GAA", "GTA")).path("status").asText());
    }

    @Test void duplicateRulesRemainAConflictAndNeverStackEvidence() throws Exception {
        setup(); rules().add(rule(0).deepCopy().put("ruleId", "duplicate-source-alias"));
        var result = assess(association(651, "CGG", "TGG"));
        assertEquals("PM1_PUBLISHED_REGION_CONFLICT", result.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED", result.path("status").asText()); assertEquals("NONE", result.path("appliedStrength").asText());
    }
}
