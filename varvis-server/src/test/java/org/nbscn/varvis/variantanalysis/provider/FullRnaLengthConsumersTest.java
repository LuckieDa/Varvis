package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine source/sequence/annotation/functional assessment methods over explicit invented originals. */
class FullRnaLengthConsumersTest {
    @TempDir Path root;
    @ParameterizedTest @CsvSource({"1,false,true,NO_KNOWN_FUNCTION,MET,NOT_MET", "-1,false,true,NO_KNOWN_FUNCTION,MET,NOT_MET",
            "1,true,false,NO_KNOWN_FUNCTION,NOT_MET,MET", "-1,true,false,NO_KNOWN_FUNCTION,NOT_MET,MET",
            "1,true,false,KNOWN_FUNCTION,NOT_MET,NOT_MET", "1,true,false,MISSING,NOT_MET,NOT_EVALUATED",
            "1,false,false,MISSING,NOT_EVALUATED,NOT_EVALUATED"})
    void sameActualRnaFactHasIndependentPm4AndBp3Qualifications(int strand,boolean repeat,boolean nonRepeat,String function,String pm4,String bp3) throws Exception {
        var rna=new FullRnaCriterionFixture(root.resolve("rna"),strand);rna.scenario("deletion");var source=rna.source();
        var f=new FullRnaRegionTestFixture(root.resolve("regions"),rna,repeat,nonRepeat,function);
        var a=f.pm4().evaluateFullRna(rna.f.context,rna.f.candidate,source,rna.f.mapper.createObjectNode());
        var b=f.bp3().evaluateFullRna(rna.f.context,rna.f.candidate,source,rna.f.mapper.createObjectNode());
        assertEquals(pm4,a.path("status").asText(),a::toPrettyString);assertEquals(bp3,b.path("status").asText(),b::toPrettyString);
        assertEquals(source.facts().canonicalSha256(),a.at("/sequenceMaterial/alleleAssociation/factsSha256").asText());
        assertEquals(a.at("/sequenceMaterial/alleleAssociation/factsSha256"),b.at("/sequenceMaterial/alleleAssociation/factsSha256"));
        assertFalse(a.at("/sequenceMaterial/alleleAssociation").has("equivalentEditEnvelope"));
        assertFalse(a.has("publicSummary"));assertFalse(b.has("publicSummary"));
        if(pm4.equals("MET"))assertTrue(a.at("/sharedEffectUse/mayNotCombineWithPvs1ForSameEffect").asBoolean());
        String directory=System.getProperty("varvis.t07.consumer.artifacts","");
        if(!directory.isBlank()) {
            var target=Path.of(directory).resolve("regions-"+strand+"-repeat-"+repeat+"-"+function);
            Files.createDirectories(target);Files.write(target.resolve("original.json"),source.originalBytes());
            var sample=rna.f.mapper.createObjectNode().put("layer","SYNTHETIC_REAL_PROVIDER_NOT_NORMAL_SELECTED_BUSINESS")
                    .put("scientificAdoptionGranted",false).put("sourceOriginalSha256",source.originalSha256());
            sample.set("context",rna.f.context);sample.set("model",rna.f.model);sample.set("PM4",a);sample.set("BP3",b);
            rna.f.mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("provider-results.json").toFile(),sample);
        }
    }
    @ParameterizedTest @ValueSource(strings={"stop-loss","cross-utr"})
    void stopLossExtensionUsesActualResolvedProteinAndDoesNotBecomeBp3(String scenario) throws Exception {
        var rna=new FullRnaCriterionFixture(root.resolve("rna"),1);rna.scenario(scenario);var source=rna.source();
        var f=new FullRnaRegionTestFixture(root.resolve("regions"),rna,false,false,"MISSING");
        var a=f.pm4().evaluateFullRna(rna.f.context,rna.f.candidate,source,rna.f.mapper.createObjectNode());
        var b=f.bp3().evaluateFullRna(rna.f.context,rna.f.candidate,source,rna.f.mapper.createObjectNode());
        assertEquals("MET",a.path("status").asText(),a::toPrettyString);assertEquals("MODERATE",a.at("/evaluation/appliedStrength").asText());
        assertFalse(a.has("proteinRegionEffects"));assertEquals("NOT_EVALUATED",b.path("status").asText());
        assertEquals("BP3_SOURCE_BOUND_RNA_INFRAME_CHANGE_REQUIRED",b.path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"quality","tissue","residual","source","combined","context"})
    void invalidOrIncompleteMaterialNeverInheritsAFormerPositive(String failure) throws Exception {
        var rna=new FullRnaCriterionFixture(root.resolve("rna"),1);rna.scenario("deletion");
        var f=new FullRnaRegionTestFixture(root.resolve("regions"),rna,false,true,"NO_KNOWN_FUNCTION");
        var engine=f.pm4();var out=engine.evaluateFullRna(rna.f.context,rna.f.candidate,rna.source(),rna.f.mapper.createObjectNode());assertEquals("MET",out.path("status").asText());
        switch(failure) {
            case "quality" -> rna.original.withObject("assay").put("assayQuality","UNKNOWN");
            case "tissue" -> rna.original.withObject("assay").put("tissueRelevance","UNKNOWN");
            case "residual" -> rna.original.withObject("assay").put("residualNormalTranscript","DETECTED_IN_ASSESSED_SCOPE");
            case "combined" -> Files.writeString(f.root.resolve("combined.json"),"{}");
            case "context" -> {rna.f.context.put("moi","AUTOSOMAL_RECESSIVE");rna.f.context.put("contextId",rna.f.adapter.canonicalContextId(rna.f.context));}
            case "source" -> { }
            default -> throw new AssertionError(failure);
        }
        var source=failure.equals("context")?null:rna.source();
        var actual=engine.evaluateFullRna(rna.f.context,rna.f.candidate,failure.equals("source")?null:source,out);
        assertEquals("NOT_EVALUATED",actual.path("status").asText(),actual::toPrettyString);assertEquals("NONE",actual.at("/evaluation/appliedStrength").asText());
        assertFalse(actual.has("publicSummary"));
        if(failure.equals("source") || failure.equals("context")) {
            assertFalse(actual.has("sharedEffectUse"));assertFalse(actual.has("fullRnaRegionAssessments"));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void configuredReleaseMismatchIsAnErrorAndNeverTriggersAReplacementFetch(boolean bp3) throws Exception {
        var rna=new FullRnaCriterionFixture(root.resolve("rna"),1);rna.scenario("deletion");
        var f=new FullRnaRegionTestFixture(root.resolve("regions"),rna,false,false,"MISSING");
        var demand=new ProteinRegionDemandSupply(rna.f.mapper,new ProteinRegionDemandSupply.Settings(
                root.resolve("demand"),"1999_01","110.0","0",false,1,1,10,5,1024));
        var supply=new ProteinLengthRegionFactSupply(rna.f.mapper,rna.f.supply,f.uniprot,f.combined,demand);
        var sequence=supply.resolveFullRnaSequence(rna.f.context,rna.f.candidate,rna.source());
        var result=supply.resolveFullRnaRegions(sequence,bp3).snapshot();
        assertEquals("PARTIAL",result.at("/proteinRegionEffects/status").asText());
        assertEquals("PROTEIN_REGION_RELEASE_MISMATCH",result.at("/proteinRegionEffects/intervals/0/proteinAnnotations/reasonCode").asText());
        assertEquals(0,demand.counts().path("httpRequests").asInt());assertEquals(0,demand.counts().path("published").asInt());
    }
    @Test void fullRnaRegionAcquisitionUsesEveryActualEffectIntervalAndNoGap() throws Exception {
        var rna=new FullRnaCriterionFixture(root.resolve("rna"),1);rna.edit("delete-p3",8,11,"");rna.edit("delete-f5",14,17,"");
        rna.product("CCATGAAAGGGGAATAAGGGTGA",8,11,19);
        var f=new FullRnaRegionTestFixture(root.resolve("regions"),rna,false,false,"MISSING");var source=rna.source();
        var sequence=f.facts.resolveFullRnaSequence(rna.f.context,rna.f.candidate,source);assertTrue(sequence.material().ready(),sequence.snapshot()::toPrettyString);
        var result=f.facts.resolveFullRnaRegions(sequence,true).snapshot();var rows=result.at("/proteinRegionEffects/intervals");
        assertEquals(2,rows.size(),result::toPrettyString);
        assertEquals(3,rows.at("/0/proteinAnnotations/queryRange/start").asInt());assertEquals(3,rows.at("/0/proteinAnnotations/queryRange/end").asInt());
        assertEquals(5,rows.at("/1/proteinAnnotations/queryRange/start").asInt());assertEquals(5,rows.at("/1/proteinAnnotations/queryRange/end").asInt());
        assertEquals("READY",result.at("/proteinRegionEffects/status").asText());
        for(var row:rows)assertFalse(row.path("proteinAnnotations").has("equivalentEditEnvelope"));
    }
}
