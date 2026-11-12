package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned toy originals and the real selected-policy/module gates; no clinical adoption. */
class Pvs1FullRnaCriterionTest {
    @TempDir Path root;
    record Data(FullRnaCriterionFixture rna,ObjectNode policy,ObjectNode selection) { }
    Data data(int strand,String scenario,int lastJunction) throws Exception {
        var f=new FullRnaCriterionFixture(root,strand);
        if(lastJunction>0) {
            f.f.configure("CCATGAAACCCGGGTTTGAATAA"+"A".repeat(80),3,23,8,17,lastJunction,103);f.intronicAndScope();
            f.edit("insert-ptc",11,11,"TAA");f.product("CCATGAAACCCTAAGGGTTTGAATAA"+"A".repeat(80),8,20,lastJunction+3);
        } else f.scenario(scenario);
        var old=new Pvs1SpliceConsequenceTest().data(strand,false,false);var p=old.input().policy.deepCopy();var model=f.f.token.model();
        var scope=p.withObject("scope");scope.set("hgncId",model.path("hgncId"));scope.set("mondoId",f.f.context.at("/disease/mondoId"));
        scope.set("moi",f.f.context.path("moi"));scope.set("assembly",model.path("assembly"));
        scope.putArray("transcripts").addObject().set("accession",f.f.context.at("/transcript/accession"));
        ((ObjectNode)scope.at("/transcripts/0")).set("version",f.f.context.at("/transcript/version"));
        p.putObject("transcriptModelBinding").put("modelId",model.path("modelId").asText()).put("modelVersion",model.path("modelVersion").asText())
                .put("canonicalSha256",f.f.token.modelSha256());
        var nmd=p.withObject("nmdScope").put("minimumCdsPosition",4).put("minimumDistanceToLastJunction",51).put("longExonScope","REVIEWED_WITHOUT_LENGTH_LIMIT");
        nmd.putArray("relevantExonOrdinals").add(1).add(2).add(3).add(4);nmd.putArray("engineeringSupportedExonOrdinals").add(1).add(2).add(3);
        nmd.putObject("utrJunctionApplicability").put("status","REVIEWED_FOR_3_PRIME_UTR_JUNCTION").put("basis","SYNTHETIC explicit UTR applicability")
                .putArray("sourceIds").add("synthetic-source");
        var escape=Pvs1NmdEscapeTest.scope(p,4);Pvs1NmdEscapeTest.critical(escape,2,6,1,"STRONG");
        p.put("schemaVersion",Pvs1SpliceConsequence.SCHEMA);
        var inframe=p.withObject("spliceScope").putObject("inFrameScope").put("status","REVIEWED_FOR_PVS1_SPLICE_INFRAME")
                .put("reviewId","SYNTHETIC_FRAME_POLICY").put("basis","SYNTHETIC exact residue three functional deletion");
        inframe.putArray("sourceIds").add("synthetic-source");var exons=inframe.putArray("exonAssessments");
        for(int i=1;i<=4;i++)exons.addObject().put("ordinal",i).put("biologicalRelevance","PRESENT").put("populationLof","NOT_FREQUENT")
                .put("basis","SYNTHETIC qualified exon").putArray("sourceIds").add("synthetic-source");
        inframe.putArray("rules").addObject().put("ruleId","SYNTHETIC_P3").put("predicate","CRITICAL_REGION")
                .put("start",3).put("end",3).put("minimumAffectedResidues",1).put("strength","STRONG").put("basis","SYNTHETIC source rule")
                .putArray("sourceIds").add("synthetic-source");
        return new Data(f,p,old.input().selection.deepCopy());
    }
    void bind(Data d) throws Exception {
        var f=d.rna();var sel=d.selection();var p=d.policy();
        sel.put("contextId",f.f.context.path("contextId").asText()).put("policyId",p.path("policyId").asText()).put("policyVersion",p.path("policyVersion").asText())
                .put("policySha256",f.f.adapter.canonicalSha256(p));sel.remove("selectionId");sel.put("selectionId","cspec-selection-sha256-"+f.f.adapter.canonicalSha256(sel));
        assertDoesNotThrow(()->f.f.adapter.validateCompleteContext(f.f.context));
        var source=f.source();var a=f.original.putObject("pvs1Assessment").put("method","SOURCE_BOUND_FULL_RNA_PVS1")
                .put("policySha256",f.f.adapter.canonicalSha256(p)).put("mechanicalEffectSha256",source.facts().mechanicalEffectSha256());
        var review=a.putObject("parentReview").put("status","REVIEWED_FOR_PVS1_SPLICE").put("reviewId","SYNTHETIC_FULL_EFFECT")
                .put("basis","SYNTHETIC reviewed complete product, no clinical adoption").put("maximumStrength","VERY_STRONG")
                .put("functionalRescue","EXCLUDED_IN_REVIEWED_SCOPE").put("outcomeMultiplicity","SINGLE_SUPPORTED_OUTCOME")
                .put("residualNormalTranscript","NOT_DETECTED_IN_ASSESSED_SCOPE");
        review.putArray("sourceIds").add("synthetic-source").add("SYNTHETIC_FULL_RNA_ORIGINAL");
        var inframe=a.putObject("inFrameReview").put("status","REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId","SYNTHETIC_FRAME_EFFECT")
                .put("basis","SYNTHETIC exact complete effect");inframe.putArray("sourceIds").add("SYNTHETIC_FULL_RNA_ORIGINAL");
        inframe.putObject("compositeEffectAssessment").put("schemaVersion","pvs1-composite-inframe-effect-review-v1")
                .put("status","REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT").put("mechanicalEffectSha256",source.facts().mechanicalEffectSha256())
                .put("reviewId","SYNTHETIC_COMPLETE_EFFECT").put("basis","SYNTHETIC whole product qualification")
                .putArray("sourceIds").add("SYNTHETIC_FULL_RNA_ORIGINAL");
    }
    String run(Data d,ObjectNode input) throws Exception {return Pvs1SpliceConsequence.resolveFullRnaForIntegration(d.rna().f.context,d.policy(),d.selection(),d.rna().source(),input);}
    ObjectNode input(Data d){return d.rna().f.mapper.createObjectNode();}
    @ParameterizedTest @CsvSource({"1,60,49,STRONG","-1,60,49,STRONG","1,61,50,STRONG","-1,61,50,STRONG","1,62,51,VERY_STRONG","-1,62,51,VERY_STRONG"})
    void actualRnaCoordinatesPreserveTheIndependentFiftyBaseBoundary(int strand,int junction,int distance,String strength) throws Exception {
        var d=data(strand,"ptc",junction);bind(d);var out=input(d);assertNull(run(d,out),out::toPrettyString);
        assertEquals(distance,out.path("distanceToLastJunction").asInt());assertEquals("MET",out.at("/fullRnaAssessment/status").asText());
        assertEquals(strength,out.at("/fullRnaAssessment/adoptedStrength").asText());assertEquals(junction+3-2,out.path("lastJunctionCdsPosition").asInt());
    }
    @ParameterizedTest @CsvSource({"1,deletion,0,STRONG", "-1,deletion,0,STRONG", "1,ptc,62,VERY_STRONG", "-1,ptc,62,VERY_STRONG"})
    void opaqueSourceReachesRealRuntimeWithItsOwnStrengthAndMethod(int strand,String scenario,int junction,String strength) throws Exception {
        var d=data(strand,scenario,junction);bind(d);var f=d.rna();var source=f.source();
        var runtime=new TruncatingNmdPvs1Runtime(f.f.mapper,new TruncatingNmdPvs1RuntimeTest().resourceProfile(),f.f.model,true);
        var actual=runtime.evaluateFullRna(f.f.context,f.f.candidate,d.policy(),d.selection(),source);
        assertEquals("MET",actual.at("/evaluation/evaluationResult/status").asText(),actual::toPrettyString);
        assertEquals(strength,actual.at("/evaluation/appliedStrength").asText());
        assertEquals(TruncatingNmdPvs1Runtime.FULL_RNA_EVALUATOR_VERSION,actual.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        assertTrue(actual.at("/evaluation/deterministicTrace/steps").toString().contains("SOURCE_BOUND_FULL_RNA_PRODUCT_AND_POLICY_VERIFIED"));
        assertEquals(1,runtime.storedVepProjections());assertEquals(1,runtime.evaluatorCalls());
        boolean foundFactsPin=false;
        for(var parameter:actual.at("/evaluation/deterministicTrace/parameters")) {
            assertNotEquals("fullRnaFacts",parameter.path("name").asText());
            if(parameter.path("name").asText().equals("fullRnaFactsSha256")) {
                foundFactsPin=true;assertEquals(source.facts().canonicalSha256(),parameter.path("value").asText());
            }
        }
        assertTrue(foundFactsPin);assertEquals(source.facts().snapshot(),actual.at("/resolvedInput/fullRnaFacts"));
        String directory=System.getProperty("varvis.t07.consumer.artifacts","");
        if(!directory.isBlank()) {
            var target=java.nio.file.Path.of(directory).resolve(scenario+"-"+strand);java.nio.file.Files.createDirectories(target);
            java.nio.file.Files.write(target.resolve("original.json"),source.originalBytes());
            var sample=f.f.mapper.createObjectNode().put("layer","SYNTHETIC_REAL_PROVIDER_NOT_NORMAL_SELECTED_BUSINESS")
                    .put("sourceOriginalSha256",source.originalSha256()).put("scientificAdoptionGranted",false);
            sample.set("context",f.f.context);sample.set("model",f.f.model);sample.set("policy",d.policy());sample.set("selection",d.selection());
            sample.set("runtimeResult",actual);f.f.mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("provider-result.json").toFile(),sample);
        }
    }
    @ParameterizedTest @ValueSource(strings={"selection","vep"})
    void fullRnaRuntimeRetainsNormalTranscriptConfirmationAndVepVersionChecks(String fault) throws Exception {
        var d=data(1,"deletion",0);var f=d.rna();bind(d);var source=f.source();
        f.f.context.withObject("transcript").put(fault.equals("selection")?"selectionPolicy":"annotationSourceVersion",fault.equals("selection")?"PM2_CONTEXT_ONLY":"117.0");
        var runtime=new TruncatingNmdPvs1Runtime(f.f.mapper,new TruncatingNmdPvs1RuntimeTest().resourceProfile(),f.f.model,true);
        var actual=runtime.evaluateFullRna(f.f.context,f.f.candidate,d.policy(),d.selection(),source);
        assertEquals(fault.equals("selection")?"PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED":"PVS1_VEP_VERSION_NOT_SUPPORTED",actual.at("/evaluationResult/reasonCode").asText(),actual::toPrettyString);
        assertEquals(0,runtime.evaluatorCalls());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void prematureTerminationIsNotDeterminedByTotalProteinLength(int strand) throws Exception {
        var d=data(strand,"ptc",62);var f=d.rna();
        ((ObjectNode)f.original.at("/events/rnaEdits/0")).put("insertedSequence","A".repeat(30)+"TAA");
        f.product("CCATGAAACCC"+"A".repeat(30)+"TAA"+"GGGTTTGAATAA"+"A".repeat(80),8,50,95);
        bind(d);var source=f.source();assertEquals(13,source.facts().snapshot().path("alteredProteinLength").asInt());
        var out=input(d);assertNull(run(d,out),out::toPrettyString);
        assertEquals(51,out.path("distanceToLastJunction").asInt());
        assertEquals("VERY_STRONG",out.at("/fullRnaAssessment/adoptedStrength").asText());
    }
    @Test void unmappableCdsUtrBoundaryCannotBecomeAnAssumedUtrLocation() throws Exception {
        var d=data(1,"ptc",70);var f=d.rna();f.edit("cross-cds-utr",21,24,"CGC");
        f.product("CCATGAAACCCTAAGGGTTTGAATCGC"+"A".repeat(79),8,20,73);
        bind(d);assertEquals(-1,f.source().result().mapReferenceRnaBoundary(23,CodingTranscriptEdits.Bias.BEFORE));var out=input(d);
        assertEquals("PVS1_FULL_RNA_CDS_UTR_BOUNDARY_UNRESOLVED",run(d,out),out::toPrettyString);
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void actualInFrameDeletionUsesWholeEffectReviewAndEachIndependentCap(int strand) throws Exception {
        var d=data(strand,"deletion",0);d.policy().put("appliedStrength","SUPPORTING");bind(d);
        d.rna().original.withObject("pvs1Assessment").withObject("parentReview").put("maximumStrength","MODERATE");
        var out=input(d);assertNull(run(d,out),out::toPrettyString);assertEquals("STRONG",out.at("/inFrameAssessment/strength").asText());
        assertEquals("SUPPORTING",out.at("/fullRnaAssessment/adoptedStrength").asText());
        assertEquals(1,out.at("/inFrameAssessment/deletedProteinResidues").asInt());
    }
    @ParameterizedTest @CsvSource({"start-loss,PVS1_FULL_RNA_INITIATION_MECHANISM_UNRESOLVED", "no-stop,PVS1_FULL_RNA_TERMINATION_UNRESOLVED",
            "stop-loss,PVS1_FULL_RNA_NON_TRUNCATING_EXTENSION_REQUIRES_INDEPENDENT_EVIDENCE", "cross-utr,PVS1_FULL_RNA_NON_TRUNCATING_EXTENSION_REQUIRES_INDEPENDENT_EVIDENCE",
            "utr,PVS1_FULL_RNA_PROTEIN_UNCHANGED"})
    void differentMechanismsAreNotForcedIntoNmd(String scenario,String reason) throws Exception {
        var d=data(1,scenario,0);bind(d);var out=input(d);assertEquals(reason,run(d,out),out::toPrettyString);
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
    }
    @ParameterizedTest @ValueSource(strings={"policy","effect","review","quality","tissue","residual","rescue","topology","utr","reference-ptc","threshold","missing-exon"})
    void sourceConditionsAndUnknownsCannotBeReplacedByMechanicalSuccess(String fault) throws Exception {
        var d=data(1,"ptc",62);bind(d);String expected;
        switch(fault) {
            case "policy" -> {d.rna().original.withObject("pvs1Assessment").put("policySha256","0".repeat(64));expected="PVS1_FULL_RNA_EFFECT_REVIEW_BINDING_MISMATCH";}
            case "effect" -> {d.rna().original.withObject("pvs1Assessment").put("mechanicalEffectSha256","0".repeat(64));expected="PVS1_FULL_RNA_EFFECT_REVIEW_BINDING_MISMATCH";}
            case "review" -> {d.rna().original.remove("pvs1Assessment");expected="PVS1_FULL_RNA_EFFECT_REVIEW_REQUIRED";}
            case "quality" -> {d.rna().original.withObject("assay").put("assayQuality","UNKNOWN");expected="PVS1_FULL_RNA_ASSAY_ASSAYQUALITY_UNRESOLVED";}
            case "tissue" -> {d.rna().original.withObject("assay").put("tissueRelevance","UNKNOWN");expected="PVS1_FULL_RNA_ASSAY_TISSUERELEVANCE_UNRESOLVED";}
            case "residual" -> {d.rna().original.withObject("assay").put("residualNormalTranscript","DETECTED_IN_ASSESSED_SCOPE");expected="PVS1_FULL_RNA_ASSAY_RESIDUALNORMALTRANSCRIPT_UNRESOLVED";}
            case "rescue" -> {d.rna().original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue","UNKNOWN");expected="PVS1_FULL_RNA_FUNCTIONAL_RESCUE_UNRESOLVED";}
            case "topology" -> {d.rna().original.withObject("topology").put("completeness","UNKNOWN").putArray("junctions0");expected="PVS1_FULL_RNA_TOPOLOGY_UNRESOLVED";}
            case "utr" -> {d.policy().withObject("nmdScope").remove("utrJunctionApplicability");bind(d);expected="PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED";}
            case "reference-ptc" -> {d.policy().withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId","ref-stop")
                    .put("coordinate","REFERENCE_PTC_SPAN").put("minimumInclusive",4).put("basis","SYNTHETIC exact reference PTC")
                    .putArray("sourceIds").add("synthetic-source");bind(d);expected="PVS1_POLICY_POSITION_UNRESOLVED";}
            case "threshold" -> {d.policy().withObject("nmdScope").put("minimumDistanceToLastJunction",60);bind(d);expected="PVS1_ESCAPE_NMD_STATUS_UNRESOLVED";}
            case "missing-exon" -> {d.policy().withObject("nmdScope").putArray("relevantExonOrdinals").add(1).add(3).add(4);
                    d.policy().withObject("nmdScope").putArray("engineeringSupportedExonOrdinals").add(1).add(3);bind(d);expected="PVS1_EXON_RELEVANCE_UNRESOLVED";}
            default -> throw new AssertionError(fault);
        }
        var out=input(d);assertEquals(expected,run(d,out),out::toPrettyString);assertNotEquals("MET",out.at("/fullRnaAssessment/status").asText());
    }
    @Test void inFrameNativeStopCannotSupplyARequiredPrematureStopPosition() throws Exception {
        var d=data(1,"deletion",0);d.policy().withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId","ptc")
                .put("coordinate","REFERENCE_PTC_SPAN").put("minimumInclusive",1).put("basis","SYNTHETIC PTC clause").putArray("sourceIds").add("synthetic-source");
        bind(d);var out=input(d);assertEquals("PVS1_POLICY_POSITION_UNRESOLVED",run(d,out),out::toPrettyString);
    }
}
