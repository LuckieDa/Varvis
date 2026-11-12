package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic source grammar and actual material resolver; no scientific approval. */
class Bp1RegionPolicyTest {
    @TempDir Path root;
    GeneralBp1RuntimeTest f=new GeneralBp1RuntimeTest();
    ObjectNode policy;
    void setup() throws Exception {
        f.root=root;f.setup();
        var c=(ObjectNode)f.doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent");
        c.put("instructionsToUse",Bp1RegionPolicy.HEADER);
        c.putArray("strengthDescriptor").addObject().put("applicability","Applicable").put("strength","Strong")
                .put("instructionsToUse","").put("text",Bp1RegionPolicy.PREFIX+"SYNTH1 synthetic region aa 4-6"+Bp1RegionPolicy.SUFFIX);
        policy=((ObjectNode)f.doc.at("/records/0")).putObject("interpretation");
        policy.put("type",Bp1RegionPolicy.TYPE).put("version","1.0.0").put("appliedStrength","STRONG")
                .put("supportedConsequence","PURE_MISSENSE_SNV").put("maximumRawDelta","0.1").put("distanceBp",10000).put("mask",0)
                .put("clinicalAdoption",false).put("absenceMeansNormal",false);
        policy.putArray("excludedProteinRegions").addObject().put("label","synthetic region").put("start",4).put("end",6);
        var a=policy.putObject("sourceAssessment").put("purpose","PROJECT_RESEARCH_SOURCE_INTERPRETATION")
                .put("windowRecommendation",Bp1RegionPolicy.WINDOW).put("sourceTranscript","ENST000001.4");
        a.set("specificationKey",f.doc.at("/records/0/specificationKey"));
        for(String role:java.util.List.of("SPECIFICATIONS","APPENDIX"))a.withArray("attachments").addObject()
                .put("role",role).put("url","https://cspec.genome.network/cspec/File/id/synthetic/data").put("rawSha256","a".repeat(64));
        a.putArray("functionalObservations");
        f.rawIdentity.put("scoreMode","RAW").put("distance",10000).put("mask",0);
    }
    ObjectNode run(boolean wide) {
        new PublishedCriterionKnowledgeTest().refresh(f.doc);
        ((ObjectNode)policy.path("sourceAssessment")).set("entitySha256",f.doc.at("/specifications/0/entitySha256"));
        var runtime=f.runtime();
        if(wide) runtime.withWideRaw((c,m,q)->SpliceAiRawBatchRunner.BatchResult.succeeded(f.raw(c,m,q.get(0).getQueryId()),0),f.rawIdentity);
        return runtime.evaluate(f.fixture.input.fixture().context(),f.fixture.input.fixture().candidate(),f.mapper.createObjectNode().put("candidateId","synthetic"));
    }
    @Test void outsideRegionUsesPublishedStrongAndWideRaw() throws Exception {
        setup();var result=run(true);assertEquals("MET",result.path("status").asText(),result.toPrettyString());
        assertEquals("STRONG",result.at("/evaluation/appliedStrength").asText());assertEquals(1,f.calls.get());
        assertTrue(result.at("/publicSummary/expertBp1SpliceThreshold").asBoolean());
        assertEquals(10000,result.at("/publicSummary/requiredDistanceBp").asInt());
    }
    @Test void missingWideCannotReuseLegacyScore() throws Exception {
        setup();assertEquals("BP1_REQUIRED_WIDE_SPLICE_SOURCE_UNAVAILABLE",run(false).path("reasonCode").asText());assertEquals(0,f.calls.get());
    }
    @ParameterizedTest @ValueSource(ints={1,2}) void inclusiveRegionBoundaryStopsBeforePrediction(int start) throws Exception {
        setup();var c=(ObjectNode)f.doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent/strengthDescriptor/0");
        c.put("text",Bp1RegionPolicy.PREFIX+"SYNTH1 synthetic region aa "+start+"-2"+Bp1RegionPolicy.SUFFIX);
        ((ObjectNode)policy.at("/excludedProteinRegions/0")).put("start",start).put("end",2);
        assertEquals("BP1_INSIDE_PUBLISHED_EXCLUDED_REGION",run(true).path("reasonCode").asText());assertEquals(0,f.calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"0.100001","0.2"}) void abovePublishedBoundaryDoesNotGiveStrong(String score) throws Exception {
        setup();f.score=score;assertEquals("BP1_PUBLISHED_SPLICE_CONDITION_NOT_MET",run(true).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"RANGE","STRENGTH","DISTANCE","NOTES","SOURCE","UNKNOWN_FIELD","LEGACY_WIDE"})
    void forgedOrIncompleteConditionDoesNotAward(String fault) throws Exception {
        setup();switch(fault) {
            case "RANGE"->((ObjectNode)policy.at("/excludedProteinRegions/0")).put("start",5);
            case "STRENGTH"->policy.put("appliedStrength","SUPPORTING");
            case "DISTANCE"->policy.put("distanceBp",4999);
            case "NOTES"->((ObjectNode)f.doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent")).put("additionalComments","Extra condition");
            case "SOURCE"->((ObjectNode)policy.path("sourceAssessment")).put("sourceTranscript","NM_000001.1");
            case "UNKNOWN_FIELD"->policy.put("override",true);
            default->f.rawIdentity.put("distance",4999);
        }
        assertEquals("NOT_EVALUATED",run(true).path("status").asText());assertEquals(0,f.calls.get());
    }
    @Test void sameProteinPublishedAdverseFindingBlocks() throws Exception {
        setup();var m=f.supply.resolve(f.fixture.input.fixture().context(),f.fixture.input.fixture().candidate());
        var row=policy.withArray("/sourceAssessment/functionalObservations").addObject().put("codingChange","c.4A>G")
                .put("reviewRequired",true).put("spliceReviewRequired",false).put("sourceRow",5);
        row.set("proteinVariantKey",m.snapshot().at("/alleleAssociation/proteinVariantKey"));row.putArray("originalCells").add("synthetic adverse result");
        assertEquals("BP1_PUBLISHED_REGION_FUNCTIONAL_REVIEW_REQUIRED",run(true).path("reasonCode").asText());assertEquals(0,f.calls.get());
    }
}
