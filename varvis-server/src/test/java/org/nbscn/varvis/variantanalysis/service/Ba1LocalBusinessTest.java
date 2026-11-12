package org.nbscn.varvis.variantanalysis.service;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class Ba1LocalBusinessTest {
    @TempDir Path temporary;
    Ba1BusinessIntegrationTest fixture;
    @BeforeEach void setup() throws Exception {
        fixture=new Ba1BusinessIntegrationTest(); fixture.temporary=temporary; fixture.setup();
        var normalization=new TopNSelectedNormalizationTest(); normalization.temporary=temporary;
        fixture.base.producer=normalization.fixture("ATCG").producer();
    }
    @AfterEach void close() { fixture.close(); }
    void local(String mode) throws Exception {
        var snapshot=fixture.syntheticCatalog("local-business-synthetic");
        var mappings=snapshot.putArray("geneMappings");
        var seen=new java.util.HashSet<String>();
        for(var candidate:fixture.base.jobs.findInterpretations(fixture.base.analysisId,0,2)) for(var a:candidate.getTranscriptAnnotations()) {
            if(!seen.add(a.getGeneId()+":"+a.getGeneSymbol())) continue;
            var row=mappings.addObject().put("hgncId","HBB".equals(a.getGeneSymbol()) ? "HGNC:4827" : "GJB2".equals(a.getGeneSymbol()) ? "HGNC:4284" : "HGNC:999999");
            row.putArray("geneSymbols").add(a.getGeneSymbol()); row.putArray("ensemblGeneIds").add(a.getGeneId());
        }
        snapshot.putObject("geneMappingSource").put("status","COMPLETE").put("rawSha256","b".repeat(64)).put("url","https://example.test/synthetic").put("recordCount",mappings.size());
        var catalog=new Ba1SpecificationCatalog(fixture.base.mapper,snapshot);
        fixture.configureCatalog(catalog);
        Path script=temporary.resolve("synthetic-tabix.cmd");
        String raw=Files.readString(script).replace("_joint", "").replace("faf95=0.054", "faf95=0.055").replace("faf95_nfe=0.054", "faf95_nfe=0.055");
        if (mode.equals("NO_RECORD")) raw="@echo off\r\necho x>> \"%~dp0queries.txt\"\r\nexit /b 0\r\n";
        if (mode.equals("FILTER")) raw=raw.replace("PASS", "AC0");
        Files.writeString(script,raw);
        var settings=fixture.populationSettings(script.toString());
        settings.getGnomad().setBa1LocalExomesEnabled(true);
        java.util.List<String> files=new java.util.ArrayList<>();
        for(String chr:java.util.List.of("11","13")) {
            Path f=temporary.resolve("gnomad.exomes.v4.1.sites.chr"+chr+".vcf.bgz");
            Files.writeString(f,"synthetic transport fixture"); Files.writeString(Path.of(f+".tbi"),"synthetic index"); files.add(f.toString());
        }
        settings.getGnomad().setGrch38Files(String.join(",",files));
        fixture.ba1=new Ba1AlleleRuntime(fixture.base.mapper,settings,catalog);
        fixture.ba1Binding=fixture.ba1.identity(); fixture.wire();
        for(var h:fixture.base.request.path("hypotheses"))
            ((com.fasterxml.jackson.databind.node.ObjectNode)h.path("transcript")).put("selectionPolicy",AcmgRecommendations.BA1_CONTEXT_SELECTION);
    }
    @Test void savesReadsAndReopensLocalResultsWithoutNewQueries() throws Exception {
        local("HIT");
        var r=fixture.post();
        for(var row:r.path("candidates")) {
            assertEquals("MET",row.at("/ba1/evaluation/evaluationResult/status").asText(),r.path("candidates").toPrettyString());
            assertEquals("EXOMES",row.at("/ba1/dataScope/dataset").asText());
            assertFalse(row.at("/ba1/evidence").toString().contains("gnomad.joint."));
        }
        Path counts=temporary.resolve("queries.txt"); String calls=Files.readString(counts);
        assertEquals(r,fixture.base.service.read(fixture.base.analysisId,r.path("runId").asText()));
        assertEquals(r,fixture.post());
        fixture.base.closeDatabase(); fixture.base.recreateRepositories(); fixture.wire();
        assertEquals(r,fixture.base.service.read(fixture.base.analysisId,r.path("runId").asText()));
        assertEquals(r,fixture.post()); assertEquals(calls,Files.readString(counts));
        assertEquals(0,r.at("/pm2Calls/provider").asInt());
    }
    @Test void noRecordAndFailedNativeQualityStayExplainable() throws Exception {
        local("NO_RECORD");var r=fixture.post();
        for(var row:r.path("candidates")) {
            assertEquals("NOT_EVALUATED",row.at("/ba1/evaluation/evaluationResult/status").asText(),r.path("candidates").toPrettyString());
            assertFalse(row.at("/ba1/evaluation/evaluationResult/reasonCode").asText().isBlank());
            assertEquals("EXOMES",row.at("/ba1/dataScope/dataset").asText());
        }
    }
}
