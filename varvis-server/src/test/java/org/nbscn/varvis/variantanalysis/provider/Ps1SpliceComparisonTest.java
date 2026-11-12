package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Ps1SpliceComparisonTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode model(int strand) {
        var m = mapper.createObjectNode().put("strand", strand); var e = m.putArray("exons");
        e.addObject().put("genomicStart", strand == 1 ? 100 : 300).put("genomicEnd", strand == 1 ? 199 : 399);
        e.addObject().put("genomicStart", strand == 1 ? 300 : 100).put("genomicEnd", strand == 1 ? 399 : 199);
        return m;
    }
    private ObjectNode allele(int pos, String alt) { return mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1").put("position", pos).put("reference", "G").put("alternate", alt); }
    private ObjectNode motif(int position) { return Ps1SpliceComparison.motif(mapper, model(1), position); }
    private ObjectNode event(String score) { return mapper.createObjectNode().put("event", "NATIVE_DONOR_LOSS").put("position", 199).put("score", score); }
    private ObjectNode raw(int dp, String ds) {
        var r = mapper.createObjectNode(); var scores = r.putObject("deltaScores"); var positions = r.putObject("deltaPositions");
        for (String k : new String[]{"AG", "AL", "DG", "DL"}) { scores.put("DS_" + k, k.equals("DL") ? ds : "0.00"); positions.put("DP_" + k, k.equals("DL") ? dp : 0); }
        return r;
    }

    @ParameterizedTest @CsvSource({"1,197,DONOR,false,true", "1,199,DONOR,false,true", "1,200,DONOR,true,false", "1,201,DONOR,true,false", "1,205,DONOR,false,false", "1,280,ACCEPTOR,false,false", "1,298,ACCEPTOR,true,false", "1,299,ACCEPTOR,true,false", "1,300,ACCEPTOR,false,true", "-1,302,DONOR,false,true", "-1,299,DONOR,true,false", "-1,298,DONOR,true,false", "-1,294,DONOR,false,false", "-1,219,ACCEPTOR,false,false", "-1,201,ACCEPTOR,true,false", "-1,200,ACCEPTOR,true,false", "-1,199,ACCEPTOR,false,true"})
    void motifBoundariesRespectTranscriptStrand(int strand, int pos, String kind, boolean canonical, boolean exonic) {
        var result = Ps1SpliceComparison.motif(mapper, model(strand), pos);
        assertEquals(kind, result.path("kind").asText()); assertEquals(canonical, result.path("canonical").asBoolean()); assertEquals(exonic, result.path("exonic").asBoolean());
    }
    @ParameterizedTest @ValueSource(ints = {196, 206, 279, 301})
    void outsideMotifNeverBroadenedByHighScores(int pos) { assertEquals("PS1_SPLICE_OUTSIDE_SUPPORTED_MOTIF", assertThrows(IllegalArgumentException.class, () -> motif(pos)).getMessage()); }

    @ParameterizedTest @CsvSource({"204,204,PATHOGENIC,PP3,STRONG", "204,204,LIKELY_PATHOGENIC,PP3,MODERATE", "204,200,PATHOGENIC,PP3,MODERATE", "204,200,LIKELY_PATHOGENIC,PP3,SUPPORTING", "200,201,PATHOGENIC,PVS1,SUPPORTING", "200,204,PATHOGENIC,PVS1,SUPPORTING", "200,204,LIKELY_PATHOGENIC,PVS1,SUPPORTING", "200,201,PATHOGENIC,PVS1_STRONG,STRONG", "200,204,PATHOGENIC,PVS1_MODERATE,MODERATE", "200,204,LIKELY_PATHOGENIC,PVS1_SUPPORTING,SUPPORTING"})
    void strengthMatchesTableTwo(int t, int r, String cls, String base, String strength) {
        assertEquals(strength, Ps1SpliceComparison.compare(mapper, allele(t,"A"), allele(r,"C"), motif(t), motif(r), event("0.98"), event("0.98"), cls, true, base, "NO_WEAKER").path("appliedStrength").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"PVS1", "PVS1_STRONG", "PVS1_MODERATE", "PVS1_SUPPORTING"})
    void canonicalLikelyPathogenicReferenceCannotQualifyCanonicalTarget(String base) {
        assertEquals("PS1_SPLICE_CANONICAL_LP_REFERENCE_NOT_APPLICABLE", assertThrows(IllegalArgumentException.class,
                () -> Ps1SpliceComparison.compare(mapper, allele(200,"A"), allele(201,"C"), motif(200), motif(201), event("0.98"), event("0.98"), "LIKELY_PATHOGENIC", true, base, "NO_WEAKER")).getMessage());
    }
    @Test void canonicalLpReferenceNeedsClinicalSupportForNoncanonicalTarget() {
        assertEquals("PS1_SPLICE_REFERENCE_CLINICAL_SUPPORT_MISSING", assertThrows(IllegalArgumentException.class,
                () -> Ps1SpliceComparison.compare(mapper, allele(204,"A"), allele(200,"C"), motif(204), motif(200), event("0.98"), event("0.98"), "LIKELY_PATHOGENIC", false, "PP3", "NO_WEAKER")).getMessage());
    }
    @Test void sameAbsoluteEventNotSameRelativeOffset() {
        var t = Ps1SpliceComparison.event(mapper, raw(-5,"0.98"), allele(204,"A"), motif(204));
        var r = Ps1SpliceComparison.event(mapper, raw(-1,"0.98"), allele(200,"C"), motif(200)); assertEquals(t,r);
        assertThrows(IllegalArgumentException.class, () -> Ps1SpliceComparison.event(mapper, raw(-1,"0.98"), allele(204,"A"), motif(204)));
    }
    @ParameterizedTest @ValueSource(strings = {"AG", "DG", "AL"})
    void competingPredictionsDoNotCollapseToMaximum(String other) {
        var r = raw(-5,"0.98"); ((ObjectNode) r.path("deltaScores")).put("DS_" + other,"0.11");
        assertEquals("PS1_SPLICE_MULTIPLE_OR_CRYPTIC_EVENTS_UNSUPPORTED", assertThrows(IllegalArgumentException.class,
                () -> Ps1SpliceComparison.event(mapper,r,allele(204,"A"),motif(204))).getMessage());
    }
    @ParameterizedTest @ValueSource(strings = {"NaN", "-0.1", "1.01", "0.199", "0.2"})
    void invalidOrInsufficientPrimaryScoreRejected(String ds) { assertThrows(IllegalArgumentException.class, () -> Ps1SpliceComparison.event(mapper,raw(-5,ds),allele(204,"A"),motif(204))); }
    @Test void explicitRelativeToleranceHasExactBoundaryAndDoesNotChangeDefault() {
        assertThrows(IllegalArgumentException.class, () -> Ps1SpliceComparison.compare(mapper,allele(204,"A"),allele(200,"C"),motif(204),motif(200),event("0.882"),event("0.98"),"PATHOGENIC",true,"PP3","NO_WEAKER"));
        assertEquals("MET",Ps1SpliceComparison.compare(mapper,allele(204,"A"),allele(200,"C"),motif(204),motif(200),event("0.882"),event("0.98"),"PATHOGENIC",true,"PP3","PUBLISHED_RELATIVE_TEN_PERCENT").path("status").asText());
        assertThrows(IllegalArgumentException.class, () -> Ps1SpliceComparison.compare(mapper,allele(204,"A"),allele(200,"C"),motif(204),motif(200),event("0.881999"),event("0.98"),"PATHOGENIC",true,"PP3","PUBLISHED_RELATIVE_TEN_PERCENT"));
    }
    @Test void identicalAlleleCannotSupportItself() { assertThrows(IllegalArgumentException.class, () -> Ps1SpliceComparison.compare(mapper,allele(204,"A"),allele(204,"A"),motif(204),motif(204),event("0.98"),event("0.98"),"PATHOGENIC",true,"PP3","NO_WEAKER")); }

    private ObjectNode full(int pos, String gain, int gainPosition) {
        var s=raw(199-pos,"0.98");((ObjectNode)s.path("deltaScores")).put("DS_DG",gain);
        ((ObjectNode)s.path("deltaPositions")).put("DP_DG",gainPosition-pos);
        return Ps1SpliceComparison.event(mapper,s,allele(pos,"A"),motif(pos),"MATCHED_NATIVE_LOSS_PROFILE");
    }
    @ParameterizedTest @CsvSource({"0.225,180,OK", "0.275,180,OK", "0.224999,180,PS1_SPLICE_SECONDARY_SCORE_INCOMPATIBLE", "0.275001,180,PS1_SPLICE_SECONDARY_SCORE_INCOMPATIBLE", "0.25,181,PS1_SPLICE_PROFILE_POSITION_MISMATCH", "0.2,180,PS1_SPLICE_PROFILE_GRAY_ZONE", "0.1,180,PS1_SPLICE_PROFILE_EVENT_SET_MISMATCH"})
    void completeProfilesHaveAbsolutePositionAndSymmetricSecondaryBounds(String score,int pos,String expected) {
        var t=full(204,score,pos);var r=full(200,"0.25",180);
        var action=(org.junit.jupiter.api.function.Executable)()->Ps1SpliceComparison.compare(mapper,allele(204,"A"),allele(200,"T"),motif(204),motif(200),t,r,"PATHOGENIC",true,"PP3","PUBLISHED_RELATIVE_TEN_PERCENT");
        if(expected.equals("OK"))assertDoesNotThrow(action);else assertEquals(expected,assertThrows(IllegalArgumentException.class,action).getMessage());
    }
    @Test void mixedDonorAndAcceptorPredictionRemainsUnresolved() {
        var t=full(204,"0.25",180);var r=full(200,"0.25",180);
        ((ObjectNode)t.at("/profile/AG")).put("score","0.30");((ObjectNode)r.at("/profile/AG")).put("score","0.30");
        assertEquals("PS1_SPLICE_MIXED_PROFILE_UNSUPPORTED",assertThrows(IllegalArgumentException.class,()->Ps1SpliceComparison.compare(mapper,allele(204,"A"),allele(200,"T"),motif(204),motif(200),t,r,"PATHOGENIC",true,"PP3","PUBLISHED_RELATIVE_TEN_PERCENT")).getMessage());
    }
    @Test void completeProfileKeepsIntegerIdentityAcrossJsonReadback() throws Exception {
        var profile=full(204,"0.23",180);assertEquals(profile,mapper.readTree(mapper.writeValueAsBytes(profile)));
    }
    @ParameterizedTest @ValueSource(strings={"NOT_MET","NOT_APPLICABLE","OTHER_REASON","OTHER_CONTEXT","CORE_EXECUTED"})
    void retainedBaselineCannotOverrideScientificResultsOrBorrowAnotherContext(String change) {
        var ctx=mapper.createObjectNode().put("contextId","same");var result=mapper.createObjectNode().put("evaluatorCalls",0);
        result.putObject("context").put("contextId","same");var s=result.putObject("evaluationResult").put("status","NOT_EVALUATED").put("reasonCode","CLAUSE_SEARCH_SCOPE_NOT_COVERED");
        assertTrue(Ps1SpliceRuntime.sourceBaselineAllowed(ctx,result));
        switch(change){case "NOT_MET", "NOT_APPLICABLE"->s.put("status",change);case "OTHER_REASON"->s.put("reasonCode","UNCERTAIN_BIOLOGICAL_OUTCOME");case "OTHER_CONTEXT"->result.withObject("/context").put("contextId","other");case "CORE_EXECUTED"->result.put("evaluatorCalls",1);}
        assertFalse(Ps1SpliceRuntime.sourceBaselineAllowed(ctx,result));
    }

    private ObjectNode policy() { var p=mapper.createObjectNode();p.putObject("scope").put("geneSymbol","TEST").put("mondoId","MONDO:1234567").put("expertOrgId","1");p.putArray("supportedOriginalVersions").add("1.1");return p; }
    @Test void oldModelEnsgIsBoundFromExactAnnotationAndVepWithoutMutation() {
        var model=model(1).put("transcript","ENST00000000001.1").put("geneSymbol","TEST").put("hgncId","HGNC:1").put("assembly","GRCh38").put("contig","1");
        var row=model.deepCopy().put("geneId","ENSG00000000001.2");
        var result=Ps1SpliceRuntime.bindNativeAnnotation(model,row,"ENSG00000000001");assertEquals("ENSG00000000001.2",result.path("geneId").asText());assertFalse(model.has("geneId"));
        assertThrows(IllegalArgumentException.class,()->Ps1SpliceRuntime.bindNativeAnnotation(model,row,"ENSG00000000002"));
        ((ObjectNode)row.at("/exons/0")).put("genomicEnd",198);assertThrows(IllegalArgumentException.class,()->Ps1SpliceRuntime.bindNativeAnnotation(model,row,"ENSG00000000001"));
    }
    @ParameterizedTest @CsvSource({"VERY_STRONG,PVS1", "STRONG,PVS1_STRONG", "MODERATE,PVS1_MODERATE", "SUPPORTING,PVS1_SUPPORTING"})
    void existingPvs1ResultNeedsBothExactContextBindings(String strength,String expected) {
        var ctx=mapper.createObjectNode().put("contextId","same");var result=mapper.createObjectNode();result.set("context",ctx.deepCopy());
        var evaluation=result.putObject("evaluation").put("contextId","same").put("criterionId","ACMG_AMP_2015:PVS1").put("appliedStrength",strength);
        evaluation.putObject("evaluationResult").put("status","MET");
        assertEquals(expected,Ps1SpliceRuntime.pvs1Baseline(ctx,result));
        evaluation.put("contextId","other");assertEquals("",Ps1SpliceRuntime.pvs1Baseline(ctx,result));
        evaluation.put("contextId","same");((ObjectNode)evaluation.path("evaluationResult")).put("status","NOT_EVALUATED");assertEquals("",Ps1SpliceRuntime.pvs1Baseline(ctx,result));
    }
    private ObjectNode reference(String... criteria) { var r=mapper.createObjectNode().put("geneSymbol","TEST").put("mondoId","MONDO:1234567").put("expertOrgId","1").put("originalVersion","1.1").put("classification","PATHOGENIC").put("scv","SCV000000001.1");var a=r.putArray("criteria");for(String c:criteria)a.add(c);return r; }
    @Test void explicitOriginalClinicalEvidenceStillSufficientAfterExcludingComparison() {
        var q=Ps1SpliceKnowledge.qualify(mapper,reference("PVS1","PP1_Strong","PS1_Supporting","PM2_Supporting"),policy());
        assertEquals("PATHOGENIC",q.path("classification").asText());assertTrue(q.path("clinicalSupport").asBoolean());assertEquals("PS1_Supporting",q.at("/excludedComparisonCriteria/0").asText());assertFalse(q.path("newClinicalClassification").asBoolean());
    }
    @Test void comparisonDependentPNotSilentlyDowngradedOrAdopted() { assertEquals("PS1_SPLICE_REFERENCE_INDEPENDENCE_INSUFFICIENT",assertThrows(IllegalArgumentException.class,()->Ps1SpliceKnowledge.qualify(mapper,reference("PVS1","PS1_Strong"),policy())).getMessage()); }
    @ParameterizedTest @ValueSource(strings={"BA1","BS1","PP5","PS1_Unknown","PVS1_Unknown","PM4"})
    void unsupportedEvidenceNeverDiscarded(String token) { assertThrows(IllegalArgumentException.class,()->Ps1SpliceKnowledge.qualify(mapper,reference("PVS1","PP1_Strong",token),policy())); }
    @Test void diseaseAndOriginalVersionNeverBorrowed() { var r=reference("PVS1","PP1_Strong");r.put("originalVersion","2.0");assertThrows(IllegalArgumentException.class,()->Ps1SpliceKnowledge.qualify(mapper,r,policy()));r.put("originalVersion","1.1").put("mondoId","MONDO:9999999");assertThrows(IllegalArgumentException.class,()->Ps1SpliceKnowledge.qualify(mapper,r,policy())); }
}
