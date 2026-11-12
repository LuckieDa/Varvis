package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic source-bound examples, not new scientific policy approvals. */
class Pvs1NmdEscapeTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final TruncatingNmdPvs1RuntimeTest fixture = new TruncatingNmdPvs1RuntimeTest();

    static ObjectNode scope(ObjectNode policy, int exonCount) {
        policy.put("schemaVersion", "pvs1-truncating-nmd-policy-v3");
        var s=policy.putObject("escapeScope").put("status","REVIEWED_FOR_PVS1_ESCAPE")
                .put("reviewId","SYNTHETIC_ESCAPE_ENGINEERING_ONLY").put("basis","Synthetic boundary checks; not scientific approval");
        s.putArray("sourceIds").add("synthetic-source");
        var exons=s.putArray("exonAssessments");
        for(int i=1;i<=exonCount;i++) {
            var e=exons.addObject().put("ordinal",i).put("biologicalRelevance","PRESENT")
                    .put("populationLof","NOT_FREQUENT").put("basis","Synthetic explicit exon facts");
            e.putArray("sourceIds").add("synthetic-source");
        }
        s.putArray("rules");return s;
    }
    static ObjectNode critical(ObjectNode s,int start,int end,int minimum,String strength) {
        var r=s.withArray("rules").addObject().put("ruleId","critical-"+start).put("predicate","CRITICAL_REGION")
                .put("start",start).put("end",end).put("minimumAffectedResidues",minimum)
                .put("strength",strength).put("basis","Synthetic source supports this exact overlap requirement");
        r.putArray("sourceIds").add("synthetic-source");return r;
    }
    static ObjectNode length(ObjectNode s,String metric,String comparison,int numerator,int denominator,String strength) {
        var r=s.withArray("rules").addObject().put("ruleId","length-"+comparison).put("predicate","PROTEIN_LOSS_FRACTION")
                .put("metric",metric).put("comparison",comparison).put("numerator",numerator).put("denominator",denominator)
                .put("strength",strength).put("basis","Synthetic exact-rational length rule with explicit metric");
        r.putArray("sourceIds").add("synthetic-source");return r;
    }
    ObjectNode run(TruncatingNmdPvs1RuntimeTest.Inputs d) {
        d.policy.withObject("transcriptModelBinding").put("canonicalSha256",adapter.canonicalSha256(d.model));
        d.selection.put("policySha256",adapter.canonicalSha256(d.policy));d.selection.remove("selectionId");
        d.selection.put("selectionId","cspec-selection-sha256-"+adapter.canonicalSha256(d.selection));
        return new TruncatingNmdPvs1Runtime(mapper,fixture.resourceProfile(),d.model).evaluate(d.context,d.candidate,d.policy,d.selection);
    }
    JsonNode status(ObjectNode r){return r.has("evaluation")?r.at("/evaluation/evaluationResult"):r.path("evaluationResult");}
    void reason(ObjectNode r,String value){assertEquals(value,status(r).path("reasonCode").asText(),r.toPrettyString());}

    @ParameterizedTest @ValueSource(ints={1,-1})
    void terminalNonsenseUsesReviewedCriticalRegionOnBothStrands(int strand)throws Exception {
        var d=fixture.nonsense(strand==1?1:2,strand,499);var s=scope(d.policy,4);critical(s,160,190,10,"STRONG");
        var r=run(d);assertEquals("MET",status(r).path("status").asText(),r.toPrettyString());
        assertEquals("STRONG",r.at("/evaluation/appliedStrength").asText());
        assertEquals(167,r.at("/resolvedInput/escapeAssessment/firstAlteredResidue").asInt());
        assertEquals(33,r.at("/resolvedInput/escapeAssessment/referenceResiduesAffected").asInt());
        assertEquals(33,r.at("/resolvedInput/escapeAssessment/netProteinShortening").asInt());
        assertEquals("NMD_NOT_PREDICTED",r.at("/resolvedInput/nmdPrediction").asText());
    }
    @Test void exactFractionBoundaryIsNotSilentlyRoundedOrAssigned()throws Exception {
        var d=fixture.nonsense(1,1,499);var s=scope(d.policy,4);
        length(s,"NET_PROTEIN_SHORTENING","GT",33,199,"STRONG");length(s,"NET_PROTEIN_SHORTENING","LT",33,199,"MODERATE");
        reason(run(d),"PVS1_ESCAPE_FRACTION_BOUNDARY_UNSPECIFIED");
        ((ObjectNode)s.at("/rules/0")).put("numerator",32);s.withArray("rules").remove(1);
        assertEquals("STRONG",run(d).at("/evaluation/appliedStrength").asText());
    }
    @ParameterizedTest @ValueSource(strings={"population","relevance","frequent","absent","scope","unreviewed","source","overlap","strength","unknown","model"})
    void incompleteConflictingOrUnreviewedFactsNeverBecomePositive(String failure)throws Exception {
        var d=fixture.nonsense(1,1,499);var s=scope(d.policy,4);length(s,"NET_PROTEIN_SHORTENING","GT",1,10,"STRONG");
        var e=(ObjectNode)s.at("/exonAssessments/3");String expected;
        switch(failure) {
            case "population" -> {e.put("populationLof","UNKNOWN");expected="PVS1_ESCAPE_POPULATION_TOLERANCE_UNRESOLVED";}
            case "relevance" -> {e.put("biologicalRelevance","UNKNOWN");expected="PVS1_ESCAPE_EXON_RELEVANCE_UNRESOLVED";}
            case "frequent" -> {e.put("populationLof","FREQUENT");expected="PVS1_ESCAPE_EXON_LOF_TOLERANT";}
            case "absent" -> {e.put("biologicalRelevance","ABSENT");expected="PVS1_ESCAPE_EXON_NOT_RELEVANT";}
            case "scope" -> {s.withArray("exonAssessments").remove(3);expected="PVS1_ESCAPE_EXON_ASSESSMENT_MISSING";}
            case "unreviewed" -> {s.put("status","UNREVIEWED");expected="PVS1_ESCAPE_SCOPE_NOT_REVIEWED";}
            case "source" -> {e.putArray("sourceIds").add("missing");expected="PVS1_ESCAPE_POLICY_INVALID";}
            case "overlap" -> {critical(s,160,190,1,"VERY_STRONG");expected="PVS1_ESCAPE_RULES_AMBIGUOUS";}
            case "strength" -> {((ObjectNode)s.at("/rules/0")).put("strength","VERY_STRONG");expected="PVS1_ESCAPE_POLICY_INVALID";}
            case "unknown" -> {s.put("ignoreConflicts",true);expected="PVS1_ESCAPE_POLICY_INVALID";}
            case "model" -> {((ObjectNode)s.at("/rules/0")).put("denominator",0);expected="PVS1_ESCAPE_POLICY_INVALID";}
            default -> throw new AssertionError();
        }
        var r=run(d);reason(r,expected);assertNotEquals("MET",status(r).path("status").asText());
    }
    @Test void criticalEvidenceCannotBypassKnownToleranceButDoesNotInventAPopulationSurvey()throws Exception {
        var d=fixture.nonsense(1,1,499);var s=scope(d.policy,4);critical(s,160,190,1,"STRONG");
        ((ObjectNode)s.at("/exonAssessments/3")).put("populationLof","UNKNOWN");
        assertEquals("MET",status(run(d)).path("status").asText());
        ((ObjectNode)s.at("/exonAssessments/3")).put("populationLof","FREQUENT");
        var r=run(d);reason(r,"PVS1_ESCAPE_EXON_LOF_TOLERANT");assertEquals("NOT_MET",status(r).path("status").asText());
    }
    @Test void noHitCannotBecomeNotMetAndGeneralSelectionCannotInheritSpecializedVeryStrong()throws Exception {
        var d=fixture.nonsense(1,1,499);var s=scope(d.policy,4);critical(s,10,20,1,"STRONG");reason(run(d),"PVS1_ESCAPE_NO_REVIEWED_RULE_MATCH");
        ((ObjectNode)s.at("/rules/0")).put("start",160).put("end",190).put("strength","VERY_STRONG");
        d.selection.put("outcome","GENERAL_POLICY_SELECTED");reason(run(d),"PVS1_ESCAPE_SPECIALIZED_STRENGTH_REQUIRED");
    }
    @Test void ordinaryNmdAndLegacyEscapeRemainUnchanged()throws Exception {
        var d=fixture.nonsense(1,1,31);var original=run(d);var oldPolicy=d.policy.deepCopy();
        var s=scope(d.policy,4);critical(s,160,190,1,"STRONG");var result=run(d);
        assertEquals("MET",status(result).path("status").asText());assertEquals("VERY_STRONG",result.at("/evaluation/appliedStrength").asText());
        assertEquals(original.path("resolvedInput"),result.path("resolvedInput"));
        d=fixture.nonsense(1,1,499);reason(run(d),"PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED");
        assertEquals("pvs1-truncating-nmd-policy-v1",oldPolicy.path("schemaVersion").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MATCH", "OTHER_POLICY", "OTHER_MODEL", "OTHER_EXON", "OUTSIDE_SCOPE"})
    void unresolvedEscapeUsesOnlyScopeMatchedExonFacts(String match) throws Exception {
        var d = fixture.nonsense(1, 1, 499);
        var result = run(d);
        var before = result.deepCopy();
        var reviews = mapper.createObjectNode();
        String policyPin = "OTHER_POLICY".equals(match) ? "a".repeat(64) : result.path("policySha256").asText();
        var review = reviews.putObject(policyPin).put("status", "UNREVIEWED").put("candidateRawSha256", "b".repeat(64))
                .put("transcriptModelSha256", "OTHER_MODEL".equals(match) ? "c".repeat(64) : adapter.canonicalSha256(d.model));
        review.putArray("sourceIds").add("synthetic-source");
        review.putArray("exonAssessments").addObject().put("ordinal", "OTHER_EXON".equals(match) ? 1 : 4)
                .put("biologicalRelevance", "PRESENT").put("populationLof", "UNKNOWN");
        var condition = review.putArray("positionConditions").addObject().put("conditionId", "tail")
                .put("coordinate", "REFERENCE_PTC_SPAN").put("minimumInclusive", "OUTSIDE_SCOPE".equals(match) ? 500 : 499)
                .put("basis", "Synthetic receipt with an explicit positional scope");
        condition.putArray("sourceIds").add("synthetic-source");
        Pvs1UnresolvedAssessment.recordEscape(mapper, d.model, reviews, result);
        var assessment = result.remove("unresolvedAssessment");
        assertNotNull(assessment);
        assertEquals(before, result);
        assertEquals(4, assessment.at("/affectedExonOrdinals/0").asInt());
        assertEquals(166, assessment.path("alteredProteinLength").asInt());
        assertEquals("ESCAPE_SCOPE_NOT_ADOPTED", assessment.at("/missingFacts/0").asText());
        assertEquals("MATCH".equals(match), assessment.has("exonAssessments"));
        assertEquals("MATCH".equals(match), "EXON_POPULATION_LOF_TOLERANCE".equals(assessment.at("/missingFacts/1").asText()));
    }

    @ParameterizedTest @ValueSource(ints={1,-1})
    void frameshiftDistinguishesAlteredReferenceTailFromNetShortening(int strand)throws Exception {
        var d=fixture.frameshift(true,strand,112);var s=scope(d.policy,10);
        length(s,"NET_PROTEIN_SHORTENING","GT",178,199,"STRONG");
        var r=run(d);reason(r,"PVS1_ESCAPE_FRACTION_BOUNDARY_UNSPECIFIED");
        assertEquals(189,r.at("/resolvedInput/escapeAssessment/referenceResiduesAffected").asInt());
        assertEquals(178,r.at("/resolvedInput/escapeAssessment/netProteinShortening").asInt());
        ((ObjectNode)s.at("/rules/0")).put("metric","REFERENCE_TAIL_ALTERED_OR_REMOVED");
        assertEquals("STRONG",run(d).at("/evaluation/appliedStrength").asText());
    }
    @Test void stricterNmdExclusionCannotBeReinterpretedAsBiologicalEscape()throws Exception {
        var d=fixture.frameshift(true,1,120);d.policy.withObject("nmdScope").put("minimumDistanceToLastJunction",60);
        var s=scope(d.policy,10);critical(s,10,190,1,"STRONG");reason(run(d),"PVS1_ESCAPE_NMD_STATUS_UNRESOLVED");
    }
    @Test void malformedVepStillFailsBeforeEscapeDecision()throws Exception {
        var d=fixture.nonsense(1,1,499);var s=scope(d.policy,4);critical(s,160,190,1,"STRONG");
        ObjectNode annotation=mapper.valueToTree(d.candidate.getTranscriptAnnotations().get(0));
        annotation.retain("geneSymbol","transcriptAccession","hgvsCoding","hgvsProtein","consequence","exon",
                "proteinPosition","aminoAcids","codons","source","status","version","timestamp","maneSelect");
        annotation.put("codons","CGA/AAA");
        d.candidate.applyTranscriptAnnotations(java.util.List.of(mapper.treeToValue(annotation,
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        reason(run(d),"PVS1_PROTEIN_CODON_MISMATCH");
    }
    @Test void escapePositionConditionsRetainExactSourceScope()throws Exception {
        var d=fixture.nonsense(1,1,499);var s=scope(d.policy,4);critical(s,160,190,1,"STRONG");
        var c=s.putArray("positionConditions").addObject().put("conditionId","tail")
                .put("coordinate","REFERENCE_PTC_SPAN").put("minimumInclusive",499).put("maximumExclusive",501)
                .put("basis","TEST_ONLY full stop codon must fit reviewed range");c.putArray("sourceIds").add("synthetic-source");
        reason(run(d),"PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE");
        c.put("maximumExclusive",502);assertEquals("MET",status(run(d)).path("status").asText());
        c.put("coordinate","PROTEIN_GUESS");reason(run(d),"PVS1_ESCAPE_POLICY_INVALID");
    }
    @Test void lessThanTenPercentGivesModerateOnlyWithReviewedPopulationFacts()throws Exception {
        var d=fixture.nonsense(1,1,568);var s=scope(d.policy,4);
        length(s,"NET_PROTEIN_SHORTENING","LT",1,10,"MODERATE");
        assertEquals("MODERATE",run(d).at("/evaluation/appliedStrength").asText());
        ((ObjectNode)s.at("/exonAssessments/3")).put("populationLof","UNKNOWN");
        reason(run(d),"PVS1_ESCAPE_POPULATION_TOLERANCE_UNRESOLVED");
    }
    @ParameterizedTest @ValueSource(strings={"ABSENT","FREQUENT"})
    void mixedExonFactsCannotNegateDamageElsewhere(String fact)throws Exception {
        var d=fixture.frameshift(true,1,112);var s=scope(d.policy,10);critical(s,160,190,1,"STRONG");
        ((ObjectNode)s.at("/exonAssessments/9")).put("ABSENT".equals(fact)?"biologicalRelevance":"populationLof",fact);
        var r=run(d);reason(r,"PVS1_ESCAPE_EXON_FACTS_CONFLICT");assertEquals("NOT_EVALUATED",status(r).path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings={"duplicateRule","duplicateExon","duplicateSource","float","overflow","range"})
    void malformedScopeIsRejectedBeforeProjection(String kind)throws Exception {
        var d=fixture.nonsense(1,1,499);var s=scope(d.policy,4);var rule=critical(s,160,190,1,"STRONG");
        switch(kind) {
            case "duplicateRule" -> s.withArray("rules").add(rule.deepCopy());
            case "duplicateExon" -> ((ObjectNode)s.at("/exonAssessments/3")).put("ordinal",3);
            case "duplicateSource" -> rule.withArray("sourceIds").add("synthetic-source");
            case "float" -> rule.put("minimumAffectedResidues",new java.math.BigDecimal("1.5"));
            case "overflow" -> rule.put("end",2147483648L);
            case "range" -> rule.put("minimumAffectedResidues",32);
            default -> throw new AssertionError();
        }
        if("float".equals(kind)) {
            assertEquals("PVS1_ESCAPE_POLICY_INVALID",Pvs1NmdEscape.policyIssue(d.policy,d.model));
            assertThrows(IllegalArgumentException.class,()->adapter.canonicalSha256(d.policy));
            return;
        }
        var r=run(d);reason(r,"PVS1_ESCAPE_POLICY_INVALID");assertEquals(0,r.path("evaluatorCalls").asInt());
    }
}
