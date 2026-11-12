package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class Hnf1aFunctionalRuntimeTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Hnf1aFunctionalRuntime runtime = new Hnf1aFunctionalRuntime(mapper);
    public ObjectNode result(String name) throws Exception { var v=candidate(name); return runtime.evaluate(context(v),v); }

    @Test void publishedP112LIsModerateAndRequiresSeparateAdoption() throws Exception {
        var r=result("P112L"); assertEquals("MET",r.at("/evaluation/evaluationResult/status").asText());
        assertEquals("MODERATE",r.at("/evaluation/appliedStrength").asText());
        assertEquals("REQUIRED",r.at("/evaluation/review/status").asText());
        assertEquals("NONE",r.at("/evaluation/review/decision").asText());
        assertEquals(2,r.path("atomicEvidence").size()); assertEquals(r.at("/evaluation/evaluationId"),result("P112L").at("/evaluation/evaluationId"));
        assertEquals("FUNCTIONAL_STUDY",r.at("/atomicEvidence/0/evidenceType").asText());
        assertEquals("[20,35]",r.at("/atomicEvidence/0/measurements/0/rawValue").asText());
        assertFalse(r.at("/atomicEvidence/0/measurements/0").has("normalizedValue"));
        assertEquals(r.at("/atomicEvidence/0/correlationGroupId"),r.at("/atomicEvidence/1/correlationGroupId"));
        assertNotEquals(r.at("/atomicEvidence/0/evidenceId"),r.at("/atomicEvidence/1/evidenceId"));
        assertEquals(1,r.at("/evaluation/evidenceIds").size());
        assertEquals(r.at("/atomicEvidence/0/evidenceId"),r.at("/evaluation/evidenceIds/0"));
        assertEquals(3,r.at("/bundle/reviewRecords").size());
        var e=(ObjectNode)r.path("evaluation").deepCopy();e.remove(List.of("evaluationId","review","conflict"));
        assertEquals("evaluation-sha256-"+adapter.canonicalSha256(e),r.at("/evaluation/evaluationId").asText());
    }
    @Test void exportPublishedFactsWithSyntheticContextForIndependentContractValidation() throws Exception {
        var artifacts=mapper.createArrayNode();
        for (String record : List.of("P112L", "Q175R", "D526N")) artifacts.add(result(record));
        var output = java.nio.file.Path.of(System.getProperty("varvis.functional.engineering-output", "target/hnf1a-functional-engineering-results.json"));
        java.nio.file.Files.writeString(output, artifacts.toPrettyString());
    }
    @Test void sourceHeaderCorrectionPreservesThreeAlleleScientificScope() throws Exception {
        var facts = result("P112L").path("factPackage");
        assertEquals("1.0.1", facts.path("version").asText());
        assertEquals("CDS position NM_000545.5", facts.at("/audit/sourceHeaderCorrection/literal").asText());
        assertEquals("b6bc7e1b1cc2b76a8ee39c9edb1040f2a9955085dee17e6df22e087acd620d99", facts.at("/audit/sourceHeaderCorrection/sha256").asText());
        assertEquals(3, facts.path("records").size());
        assertEquals("MODERATE", result("P112L").at("/evaluation/appliedStrength").asText());
    }
    @ParameterizedTest @CsvSource({"Q175R,HOLD_NORMAL_TA_DOES_NOT_SUPPORT_PS3,HOLD_SPLICE_APPLICABILITY_UNRESOLVED", "D526N,HOLD_INTERMEDIATE,HOLD_CROSS_CONDITION_CONFLICT"})
    void holdsAreNotNotMetOrBenign(String label,String reason,String bs3) throws Exception {
        var r=result(label); assertEquals("NOT_EVALUATED",r.at("/evaluation/evaluationResult/status").asText());
        assertEquals(reason,r.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals(bs3,r.at("/factRecord/gates/bs3").asText());assertEquals("NONE",r.at("/evaluation/appliedStrength").asText());
        assertEquals(2,r.path("atomicEvidence").size());
    }
    @Test void productionPs3EnabledIndependentlyAndBs3Disabled() {
        var profile=new AcmgExecutionProfile(mapper);
        assertTrue(profile.criteria().contains(Hnf1aFunctionalRuntime.CRITERION));
        assertFalse(profile.criteria().contains("ACMG_AMP_2015:BS3"));
        assertFalse(profile.document().path("automaticTrigger").asBoolean(true));
        assertFalse(profile.document().path("classificationEnabled").asBoolean(true));
    }
    @ParameterizedTest @CsvSource({"40,40,LTE,true", "40,40,LT,false", "39,39,LT,true", "39,41,LTE,false", "75,75,GTE,true", "74,76,GTE,false", "76,90,GTE,true"})
    void syntheticEngineeringThresholdBoundaries(int lo,int hi,String op,boolean expected) {
        var observation=engineeringRange(lo,hi);
        assertEquals(expected,Hnf1aFunctionalRuntime.thresholdSatisfied(observation,op,"GTE".equals(op)?75:40));
    }
    @Test void ambiguousFiguresMissingValuesOrWrongUnitsNeverPass() {
        var o=engineeringRange(20,35);o.put("technicalReview","UNREADABLE"); assertFalse(Hnf1aFunctionalRuntime.thresholdSatisfied(o,"LTE",40));
        o=engineeringRange(20,35);o.remove("upper");assertFalse(Hnf1aFunctionalRuntime.thresholdSatisfied(o,"LTE",40));
        o=engineeringRange(20,35);o.put("unit","fraction");assertFalse(Hnf1aFunctionalRuntime.thresholdSatisfied(o,"LTE",40));
        o=engineeringRange(40,40);o.put("upperInclusive",false);assertTrue(Hnf1aFunctionalRuntime.thresholdSatisfied(o,"LT",40));
    }
    @Test void identityAssemblyAndExplicitTranscriptBoundaries() throws Exception {
        var v=candidate("P112L");var c=context(v); ((ObjectNode)c.path("gene")).put("hgncId","HGNC:1"); refresh(c);
        assertReason(runtime.evaluate(c,v),"FUNCTIONAL_POLICY_NOT_APPLICABLE");
        c=context(v);((ObjectNode)c.path("transcript")).put("version",10);refresh(c);assertReason(runtime.evaluate(c,v),"FUNCTIONAL_TRANSCRIPT_NOT_SUPPORTED");
        c=context(v);((ObjectNode)c.path("transcript")).put("selectionPolicy","PM2_CONTEXT_ONLY");refresh(c);assertReason(runtime.evaluate(c,v),"FUNCTIONAL_TRANSCRIPT_CONFIRMATION_REQUIRED");
        c=context(v);((ObjectNode)c.at("/variant/assemblyLineage")).put("mappingStatus","MAPPED");
        assertEquals("NOT_EVALUATED",runtime.evaluate(c,v).at("/evaluationResult/status").asText());
        assertReason(runtime.evaluate(context(candidate("Q175R")),v),"FUNCTIONAL_VEP_SOURCE_IDENTITY_INVALID");
    }
    @Test void missingRecordAndAnnotationConflictAreUnknown() throws Exception {
        var v=RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh38","12",120988841,"C","G"));
        assertReason(runtime.evaluate(context(v),v),"FUNCTIONAL_NO_REVIEWED_RECORD");
        v=candidate("P112L");var c=context(v);var tx=v.getTranscriptAnnotations().get(0);v.applyTranscriptAnnotations(List.of(tx,tx));
        assertReason(runtime.evaluate(c,v),"FUNCTIONAL_SELECTED_TRANSCRIPT_NOT_UNIQUE");
    }
    @Test void populationOnlyContextCannotAuthorizeIndependentFunctionalEvaluation() throws Exception {
        var candidate = candidate("P112L");
        for (String selection : List.of("PM2_CONTEXT_ONLY",
                org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.PM2_CONTEXT_SELECTION,
                org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.FREQUENCY_CONTEXT_SELECTION)) {
            var context = context(candidate);
            ((ObjectNode) context.path("transcript")).put("selectionPolicy", selection);
            refresh(context);
            var result = runtime.evaluate(context, candidate);
            assertReason(result, "FUNCTIONAL_TRANSCRIPT_CONFIRMATION_REQUIRED");
            assertEquals(0, result.path("providerCalls").asInt());
            assertEquals(0, result.path("evaluatorCalls").asInt());
            assertFalse(result.has("storedVepProjections"));
            assertFalse(result.has("atomicEvidence"));
            assertFalse(result.has("evaluation"));
            assertFalse(result.has("bundle"));
        }
        var explicit = context(candidate);
        ((ObjectNode) explicit.path("transcript")).put("selectionPolicy",
                org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.POLICY_ID + "@"
                        + org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.VERSION);
        refresh(explicit);
        var result = runtime.evaluate(explicit, candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText());
        assertEquals(1, result.path("providerCalls").asInt());
        assertEquals(1, result.path("evaluatorCalls").asInt());
    }
    @Test void sameObservationCitationsAreNotAdditionalEvidenceAndLabelsCannotBeCircular() throws Exception {
        var material=(ObjectNode)result("P112L").path("factPackage").deepCopy();
        var first=(ObjectNode)material.at("/records/0/observations/0");
        ((ObjectNode)material.at("/records/0/observations/1")).put("originObservationId",first.path("originObservationId").asText());
        assertThrows(IllegalArgumentException.class,()->Hnf1aFunctionalRuntime.validateObservations(material));
        var circular=(ObjectNode)result("P112L").path("factPackage").deepCopy();
        ((ObjectNode)circular.at("/records/0/observations/0")).put("independentCalibrator",true);
        assertThrows(IllegalArgumentException.class,()->Hnf1aFunctionalRuntime.validateObservations(circular));
        var changed=(ObjectNode)result("P112L").path("factPackage").deepCopy();
        changed.put("version","2.0.0");assertNotEquals(adapter.canonicalSha256(changed),result("P112L").at("/atomicEvidence/0/source/resourceSha256").asText());
    }
    private ObjectNode engineeringRange(int lo,int hi) { return mapper.createObjectNode().put("valueRepresentation","SYNTHETIC_ENGINEERING_RANGE")
            .put("technicalReview","CLEAR_FOR_RECORDED_BOUND_ONLY").put("unit","percent_of_study_WT")
            .put("lower",lo).put("upper",hi).put("lowerInclusive",true).put("upperInclusive",true); }
    private void assertReason(ObjectNode r,String reason) { assertEquals("NOT_EVALUATED",r.at("/evaluationResult/status").asText());assertEquals(reason,r.at("/evaluationResult/reasonCode").asText()); }
    private VariantInterpretation candidate(String name) {
        int g=name.equals("P112L")?120988841:name.equals("Q175R")?120989030:120999342;
        int c=name.equals("P112L")?335:name.equals("Q175R")?524:1576;
        String ref=name.equals("P112L")?"C":name.equals("Q175R")?"A":"G";
        String alt=name.equals("P112L")?"T":name.equals("Q175R")?"G":"A";
        String protein=name.equals("P112L")?"Pro112Leu":name.equals("Q175R")?"Gln175Arg":"Asp526Asn";
        var v=RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh38","12",g,ref,alt));
        v.applyTranscriptAnnotations(List.of(new TranscriptConsequence("HNF1A","ENSG00000135100","ENST00000257555",
                "ENST00000257555.11:c."+c+ref+">"+alt,"ENSP00000257555.5:p."+protein,
                name.equals("Q175R")?"missense_variant&splice_region_variant":"missense_variant","ENSG00000135100","ENST00000257555","Ensembl","MODERATE",name.equals("D526N")?"8/10":"2/10",null,
                true,true,false,name.replaceAll("[^0-9]",""),name.charAt(0)+"/"+name.charAt(name.length()-1),null,null,null,null,"VEP_CACHED","COMPLETED","116.0",Instant.parse("2026-09-10T00:00:00Z"))));return v;
    }
    private ObjectNode context(VariantInterpretation v) throws Exception {
        var c=RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper,adapter,v.getObservation().getKey(),"ENST00000257555");
        ((ObjectNode)c.path("gene")).put("hgncId","HGNC:11621").put("symbol","HNF1A");
        ((ObjectNode)c.path("disease")).put("mondoId","MONDO:0015967").put("label","monogenic diabetes");c.put("moi","AUTOSOMAL_DOMINANT");
        ((ObjectNode)c.path("transcript")).put("version",11).put("annotationSourceVersion","116.0");refresh(c);return c;
    }
    private void refresh(ObjectNode c) { c.put("contextId",adapter.canonicalContextId(c)); }
}
