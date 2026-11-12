package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PublishedPm1RegionAssessmentTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    ObjectNode selection, model;
    void setup() throws Exception {
        selection = (ObjectNode) mapper.readTree("""
          {"declaredTranscript":"NM_000001.2","record":{"clausePointer":"/ld/CriteriaCode/0","interpretation":{
            "type":"PUBLISHED_PM1_PROTEIN_LOCATIONS","version":"1.0.0","consequenceScope":"PURE_MISSENSE_SNV",
            "qualificationBasis":"PUBLISHED_EXPERT_SPECIFICATION","proteinIdentity":{"mode":"SOURCE_TRANSCRIPT_DEFINED"},
            "residualConditions":[],"crossCriterionRelations":[],"precedence":[],"rules":[{
              "ruleId":"test-region","start":2,"end":4,"regionType":"CRITICAL_FUNCTIONAL_REGION",
              "effect":"SUPPORT","strength":"MODERATE","sourcePointer":"/ld/CriteriaCode/0/entContent/strengthDescriptor/0",
              "referenceResidues":[{"position":2,"aminoAcid":"E"}],"expectedSequence":"EPG"}]}},
           "specification":{"original":{"ld":{"CriteriaCode":[{"entContent":{"label":"PM1","strengthDescriptor":[{
             "applicability":"Applicable","status":"approved","strength":"Moderate","text":"TEST_ONLY residues2-4; firstE; EPG motif"}]}}]}}}}
          """);
        model = mapper.createObjectNode().put("cdsSequence", "ATGGAACCTGGCTTTAAATAA").put("proteinLength", 6).put("protein", "NP_000001.1");
        refresh();
    }
    ObjectNode interpretation() { return (ObjectNode) selection.at("/record/interpretation"); }
    ObjectNode rule() { return (ObjectNode) interpretation().at("/rules/0"); }
    ObjectNode original() { return (ObjectNode) selection.at("/specification/original"); }
    void refresh() {
        for (var r : interpretation().path("rules")) ((ObjectNode)r).put("sourceSha256",adapter.canonicalSha256(original().at(r.path("sourcePointer").asText())));
    }
    ObjectNode assess(int position) { return new PublishedPm1RegionAssessment(mapper).assess(selection,model,mapper.createObjectNode().put("proteinPosition",position)); }
    @ParameterizedTest @CsvSource({"1,NOT_EVALUATED", "2,MET", "4,MET", "5,NOT_EVALUATED"})
    void boundedLocationsNeverTurnNoMatchIntoNegative(int position,String status) throws Exception {
        setup();var before=selection.deepCopy();var result=assess(position);
        assertEquals(status,result.path("status").asText(),result.toPrettyString());assertEquals(before,selection);
        assertEquals(status.equals("MET")?"MODERATE":"NONE",result.path("appliedStrength").asText());
        assertFalse(result.path("benignCountsInferred").asBoolean(true));assertFalse(result.path("createsReviewReceipt").asBoolean(true));
    }
    @ParameterizedTest @ValueSource(strings={"EXTRA_CONDITION","OTHER_CRITERION","ORDINARY_DOMAIN","STRONG","SOURCE_CHANGED","NOT_APPROVED",
            "WRONG_STRENGTH","REF_AA","MOTIF","UNKNOWN_OPERATOR","INTERVAL","PROTEIN","FRACTIONAL_POSITION","ALTERNATE_AA","MISSING_CONDITIONS"})
    void incompleteOrUnsupportedInterpretationsFailExplicitly(String mutation) throws Exception {
        setup();
        switch(mutation) {
            case "EXTRA_CONDITION" -> interpretation().withArray("residualConditions").add("requiresPM2");
            case "MISSING_CONDITIONS" -> interpretation().remove("residualConditions");
            case "OTHER_CRITERION" -> interpretation().withArray("crossCriterionRelations").addObject().put("criterionId","ACMG_AMP_2015:PP3").put("relation","MAX_COMBINED_STRONG");
            case "ORDINARY_DOMAIN" -> rule().put("regionType","ORDINARY_DOMAIN");
            case "STRONG" -> rule().put("strength","STRONG");
            case "SOURCE_CHANGED" -> rule().put("sourceSha256","0".repeat(64));
            case "NOT_APPROVED" -> { ((ObjectNode)original().at(rule().path("sourcePointer").asText())).put("status","not approved"); refresh(); }
            case "WRONG_STRENGTH" -> { ((ObjectNode)original().at(rule().path("sourcePointer").asText())).put("strength","Supporting"); refresh(); }
            case "REF_AA" -> ((ObjectNode)rule().at("/referenceResidues/0")).put("aminoAcid","R");
            case "MOTIF" -> rule().put("expectedSequence","EEE");
            case "UNKNOWN_OPERATOR" -> interpretation().put("ignoreBenignVariation",true);
            case "ALTERNATE_AA" -> rule().put("allowedAlternateAminoAcid","R");
            case "INTERVAL" -> rule().put("end",7);
            case "PROTEIN" -> {
                var p=interpretation().putObject("proteinIdentity").put("mode","DECLARED_PROTEIN").put("accession","NP_999999.1")
                        .put("sourcePointer",rule().path("sourcePointer").asText()).put("sourceSha256",rule().path("sourceSha256").asText());
                assertTrue(p.has("accession"));
            }
            default -> {var out=new PublishedPm1RegionAssessment(mapper).assess(selection,model,mapper.createObjectNode().put("proteinPosition",2.5));
                assertEquals("PM1_PUBLISHED_POSITION_INVALID",out.path("reasonCode").asText());return;}
        }
        var out=assess(2);assertEquals("NOT_EVALUATED",out.path("status").asText(),out.toPrettyString());
        assertNotEquals("PM1_PUBLISHED_SOURCE_SCOPE_NOT_COVERED",out.path("reasonCode").asText());assertEquals("NONE",out.path("appliedStrength").asText());
    }
    @Test void explicitExclusionAndPrecedenceAreSharedWithLegacyPositionLogic() throws Exception {
        setup();original().putObject("entContent").put("note","TEST_ONLY exclude position3 and prioritise over wider region");
        var ex=interpretation().withArray("rules").addObject().put("ruleId","exclusion").put("start",3).put("end",3)
                .put("regionType","EXCLUSION").put("effect","EXCLUDE").put("strength","NONE").put("sourcePointer","/entContent/note");
        ex.putArray("referenceResidues");refresh();
        assertEquals("PM1_PUBLISHED_REGION_CONFLICT",assess(3).path("reasonCode").asText());
        var rel=interpretation().withArray("precedence").addObject().put("higherRuleId","exclusion").put("lowerRuleId","test-region")
                .put("sourcePointer","/entContent/note").put("sourceSha256",adapter.canonicalSha256(original().at("/entContent/note")));
        assertEquals("NOT_MET",assess(3).path("status").asText());assertEquals("MET",assess(2).path("status").asText());
        interpretation().withArray("precedence").add(rel.deepCopy().put("higherRuleId","test-region").put("lowerRuleId","exclusion"));
        assertEquals("PM1_PUBLISHED_PRECEDENCE_CONFLICT",assess(3).path("reasonCode").asText());
    }
    @Test void sourcePolicyCanAllowPm5ButCannotEraseConditionalExclusions() throws Exception {
        setup();original().putObject("entContent").put("note","TEST_ONLY not mutually exclusive withPM5");
        var relation=interpretation().withArray("crossCriterionRelations").addObject().put("criterionId","ACMG_AMP_2015:PM5")
                .put("relation","NOT_MUTUALLY_EXCLUSIVE").put("sourcePointer","/entContent/note")
                .put("sourceSha256",adapter.canonicalSha256(original().at("/entContent/note")));
        assertEquals("MET",assess(2).path("status").asText());
        relation.put("relation","EXCLUDE_IF_STRONG");
        assertEquals("PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED",assess(2).path("reasonCode").asText());
    }
    @Test void declaredProteinCannotBorrowBareTranscriptBridgeOrDifferentVersion() throws Exception {
        setup();var p=interpretation().putObject("proteinIdentity").put("mode","DECLARED_PROTEIN").put("accession","NP_000002.1")
                .put("sourcePointer",rule().path("sourcePointer").asText()).put("sourceSha256",rule().path("sourceSha256").asText());
        var b=model.putObject("transcriptBridge").put("status","FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE")
                .put("refseqTranscript","NM_000001.2").put("refseqProtein","NP_000002.1");
        assertEquals("MET",assess(2).path("status").asText());
        b.put("refseqProtein","NP_000002.2");assertEquals("PM1_PUBLISHED_PROTEIN_IDENTITY_MISMATCH",assess(2).path("reasonCode").asText());
        b.put("refseqProtein","NP_000002.1").put("refseqTranscript","NM_000001.3");assertEquals("NOT_EVALUATED",assess(2).path("status").asText());
        assertEquals("NP_000002.1",p.path("accession").asText());
    }
}
