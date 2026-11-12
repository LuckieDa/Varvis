package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.service.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Bp7SelectedBatchTest {
    @TempDir Path temporary;
    @ParameterizedTest @ValueSource(strings={"OK","MISSING","DUPLICATE","SOURCE","ROW_PLAN","WRONG_CANDIDATE"})
    void originalPreparationAndSelectedSourceAreIndependentOfTheSavedRowsPlan(String mutation) throws Exception {
        var f=new Bp7ComputationalRuntimeTest();f.directory=temporary;
        var s=f.saved("0.0","synonymous_variant");
        String cid=s.plan().path("candidateId").asText(), hid=s.plan().path("hypothesisId").asText();
        var source=(ObjectNode)s.plan().path("sourceProjection").deepCopy();
        s.plan().set("sourceProjection",source.deepCopy());f.reseal(s);
        var selected=mock(SelectedEvidencePreparation.class);var projection=mock(LegacyContextProjection.class);
        when(selected.legacyProjection(cid,hid)).thenReturn(projection);
        when(projection.context()).thenReturn((ObjectNode)s.plan().path("context").deepCopy());
        when(projection.snapshot()).thenReturn(source.deepCopy());when(projection.queryCandidate()).thenReturn(s.candidate());
        var prepared=f.mapper.createObjectNode();prepared.putArray("plans").add(s.plan().deepCopy());
        switch(mutation) {
            case "MISSING" -> prepared.putArray("plans");
            case "DUPLICATE" -> prepared.withArray("plans").add(s.plan().deepCopy());
            case "SOURCE" -> when(projection.snapshot()).thenReturn(f.mapper.createObjectNode().put("original","OTHER"));
            case "ROW_PLAN" -> {
                ((ObjectNode)s.row().path("plan")).put("route","FORGED");
                f.prediction.seal(s.row());
            }
            case "WRONG_CANDIDATE" -> ((ObjectNode)prepared.at("/plans/0")).put("candidateId","other");
        }
        var runtime=new Bp7SelectedBatch(f.mapper,f.source(f.publicCatalog).captureCatalog());
        var result=runtime.evaluate(selected,cid,hid,prepared,s.row());
        if(mutation.equals("OK")) assertEquals("MET",result.path("status").asText(),result.toPrettyString());
        else {
            assertEquals("NOT_EVALUATED",result.path("status").asText(),result.toPrettyString());
            if(!mutation.equals("ROW_PLAN")) assertEquals(0,runtime.evaluatorCalls());
        }
        assertEquals(1,s.calls().get(),"BP7 may not launch a second prediction");
    }
    @Test void summaryContainsGeometryAndSharedFactIdentityButNoRawContext() throws Exception {
        var f=new Bp7ComputationalRuntimeTest();f.directory=temporary;
        var s=f.saved("0.0","synonymous_variant");var result=f.evaluate(s,f.source(f.publicCatalog).captureCatalog());
        var publicView=Bp7SelectedBatch.publicSummary(f.mapper,result);
        assertEquals("MET",publicView.path("coreStatus").asText());assertTrue(publicView.path("geometry").isObject());
        assertTrue(publicView.path("policySha256").asText().matches("[0-9a-f]{64}"));
        assertEquals(s.row().path("resultId"),publicView.path("sharedPredictionResultId"));
        assertFalse(publicView.has("context"));assertFalse(publicView.has("sourceSnapshot"));
        assertFalse(publicView.path("experimentalNormalityEstablished").asBoolean());
    }

    @Test void onlyExactPublicRnaMatchPublishesAndRetainsItsSource() throws Exception {
        var f=new Bp7ComputationalRuntimeTest();f.directory=temporary;
        var record=(ObjectNode)f.document().at("/records/1");
        var plan=f.planForPublicRecord(record);
        var catalog=f.source(f.publicCatalog).captureCatalog();
        var raw=f.mapper.createObjectNode();
        raw.set("experimentalConflictCheck",new Bp7ComputationalRuntime(f.mapper).assessExperiments(plan,catalog));
        var view=Bp7SelectedBatch.publicSummary(f.mapper,raw);
        assertEquals(record.at("/source/url"),view.at("/experimentSources/0/url"));
        var assets=new LiteratureSourceAssets(f.mapper,temporary,true);
        assets.retainExperiment(raw.at("/experimentalConflictCheck/matches/0/record"));
        try(var paths=java.nio.file.Files.list(temporary.resolve("reviewed-evidence/PMC3440543/reviews/RNA"))){
            var stored=f.mapper.readTree(java.nio.file.Files.readAllBytes(paths.findFirst().orElseThrow()));
            assertEquals(record,stored.path("material"));
            assertFalse(stored.path("scoringAuthority").asBoolean());
        }
        var context=plan.withObject("/context");
        context.withObject("/transcript").put("version",999);
        context.put("contextId",new RuntimeEvidenceV2Adapter(f.mapper).canonicalContextId(context));
        raw.set("experimentalConflictCheck",new Bp7ComputationalRuntime(f.mapper).assessExperiments(plan,catalog));
        assertFalse(Bp7SelectedBatch.publicSummary(f.mapper,raw).has("experimentSources"));
    }

    @ParameterizedTest @ValueSource(strings={"inframe_deletion", "inframe_insertion", "missense_variant", "synonymous_variant", "intron_variant"})
    void blockedPredictionIsNotAnExecutablePlanAndApplicabilityUsesSelectedAnnotation(String consequence) throws Exception {
        var f=new Bp7ComputationalRuntimeTest();f.directory=temporary;
        var s=f.saved("0.0","synonymous_variant");
        ObjectNode candidateJson=f.mapper.valueToTree(s.candidate());
        for(var annotation:candidateJson.path("transcriptAnnotations")) ((ObjectNode)annotation).put("consequence",consequence);
        var candidate=f.mapper.readerFor(org.nbscn.varvis.variantanalysis.domain.VariantInterpretation.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<org.nbscn.varvis.variantanalysis.domain.VariantInterpretation>readValue(candidateJson);
        String cid=s.plan().path("candidateId").asText(),hid=s.plan().path("hypothesisId").asText();
        var selected=mock(SelectedEvidencePreparation.class);var projection=mock(LegacyContextProjection.class);
        when(selected.legacyProjection(cid,hid)).thenReturn(projection);
        when(projection.context()).thenReturn((ObjectNode)s.plan().path("context").deepCopy());
        when(projection.snapshot()).thenReturn((ObjectNode)s.plan().path("sourceProjection").deepCopy());
        when(projection.queryCandidate()).thenReturn(candidate);
        // Exact shape of the production early-return: no prepared context/source projection.
        var prepared=f.mapper.createObjectNode();prepared.putArray("plans").addObject()
                .put("candidateId",cid).put("hypothesisId",hid).put("route","BLOCKED")
                .put("status","NOT_EVALUATED").put("reasonCode","PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE");
        var runtime=new Bp7SelectedBatch(f.mapper,f.source(f.publicCatalog).captureCatalog());
        var result=runtime.evaluate(selected,cid,hid,prepared,s.row());
        boolean applicable=consequence.equals("synonymous_variant")||consequence.equals("intron_variant");
        assertEquals(applicable?"NOT_EVALUATED":"NOT_APPLICABLE",result.path("status").asText(),result.toPrettyString());
        assertEquals(applicable?"BP7_PREDICTION_PREPARATION_BLOCKED":"BP7_CONSEQUENCE_NOT_APPLICABLE",result.path("reasonCode").asText());
        assertEquals(0,runtime.evaluatorCalls());
        assertEquals(1,s.calls().get(),"BP7 adds no prediction calls");
        if(!applicable) {
            when(selected.legacyProjection(cid,hid)).thenThrow(new IllegalArgumentException("SELECTED_TRANSCRIPT_ANNOTATION_UNAVAILABLE"));
            assertEquals("SELECTED_TRANSCRIPT_ANNOTATION_UNAVAILABLE",runtime.evaluate(selected,cid,hid,prepared,s.row()).path("reasonCode").asText(),
                    "Blocked-plan labels must not bypass actual selected-source qualification");
        }
    }
}
