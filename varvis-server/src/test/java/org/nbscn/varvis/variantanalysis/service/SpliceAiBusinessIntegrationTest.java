package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpliceAiBusinessIntegrationTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    Ba1BusinessIntegrationTest population;
    BusinessSpliceAiFixture splice = new BusinessSpliceAiFixture();
    BusinessAlphaMissenseFixture am = new BusinessAlphaMissenseFixture();
    @BeforeEach void setup() throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary=temporary; base.setup();
        var map = new HashMap<>(base.fixture.annotations);
        var t=map.get("public-hbb-compatible-e7v").stream()
                .filter(a -> "ENST00000647020.1".equals(a.getVersionedTranscriptId())).findFirst().orElseThrow();
        map.put("public-hbb-compatible-e7v", List.of(spliceTranscript(t)));
        base.fixture.annotations=map;
        base.publish(false,GenomeAssembly.GRCh38,null);
        ((ObjectNode) base.request.at("/hypotheses/0/transcript")).put("annotationSource","VEP_SYNTHETIC");
        // This publication used the existing adapter/parser/VCF association and source snapshot path.
        population=new Ba1BusinessIntegrationTest(); population.base=base; population.temporary=temporary; population.am=am;
        population.configure("0.055","9000","HIT"); wire();
    }
    TranscriptConsequence spliceTranscript(TranscriptConsequence t) {
        return new TranscriptConsequence(t.getGeneSymbol(),t.getEntrezGeneId(),t.getTranscriptAccession(),
                t.getVersionedTranscriptId()+":c.100+7A>T",null,"intron_variant",t.getGeneId(),t.getTranscriptId(),
                "protein_coding","MODIFIER",null,"1/2",true,true,false,null,null,null,null,null,null,
                "VEP_SYNTHETIC","COMPLETED","116.0",Instant.parse("2026-09-01T00:00:00Z"));
    }
    String expectedPath() { return "SPLICEAI_INTRONIC"; }
    @AfterEach void close() { base.closeDatabase(); }
    void wire() {
        var recommendation = new AcmgRecommendations(base.mapper,c->BusinessAlphaMissenseFixture.identityFixture(c.getObservation().getKey(),
                List.of("13\t3\tC\tT\thg38\tSYNTHETIC\tENST00000382848.5\tV37I\t0.01\tignored")));
        base.service=new AcmgBusinessService(base.mapper,base.jobs,base.store,base.sources,
                ()->new ExplicitCaseInputCoordinator(base.mapper,base.fixture.resolver,base.producer,base.fixture.sources,
                        new ExplicitAlphaMissenseDispatcher(base.mapper,am,()->splice.runtime(base.mapper))),
                ()->new AcmgBusinessService.Ba1Runtime(base.producer,population.ba1),recommendation);
        base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    @Test void ordinaryRecommendationPublicationSaveRecreateAndRepeat() throws Exception {
        var helper=new AcmgRecommendationBusinessTest(); helper.base=base;
        var options=helper.options(); var rec=options.at("/candidates/0/recommendation");
        assertEquals(expectedPath(),rec.at("/resourceCompatibleTranscript/predictionPath").asText(),rec.toPrettyString());
        var request=helper.recommendationRequest(options);
        var result=base.postRequest(request);
        assertEquals(base.mapper.valueToTree(new org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile(base.mapper).criteria()), result.path("enabledCriteria"));
        assertEquals("MET",result.at("/candidates/0/attempts/0/criterionEvaluations/2/evaluationResult/status").asText(),result.toPrettyString());
        assertEquals(1,splice.batches); assertEquals(1,am.calls);
        assertEquals(2,result.at("/ba1Calls/provider").asInt()); assertEquals(2,result.path("bundles").size());
        base.recreateRepositories(); wire();
        var read=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,result.path("runId").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(result,read); assertEquals(result,base.postRequest(request)); assertEquals(1,splice.batches); assertEquals(1,am.calls);
        assertEquals(2,Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertFalse(result.toString().contains("synthetic-proband"));
        var report=System.getProperty("varvis.e4b3.fixtureOutput"); if(report!=null) Files.writeString(Path.of(report),result.toPrettyString());
    }
    @Test void spliceFailureDoesNotBlockAmOrPopulation() throws Exception {
        splice.failure="SYNTHETIC_SPLICEAI_FAILED";
        var result=base.postRequest(base.request);
        assertEquals("NOT_EVALUATED",result.at("/candidates/0/attempts/0/criterionEvaluations/2/evaluationResult/status").asText(),result.toPrettyString());
        assertEquals(1,am.calls); assertEquals(1,splice.batches); assertEquals(2,result.at("/ba1Calls/provider").asInt());
        assertEquals("MET",result.at("/candidates/0/ba1/evaluation/evaluationResult/status").asText());
    }
}
