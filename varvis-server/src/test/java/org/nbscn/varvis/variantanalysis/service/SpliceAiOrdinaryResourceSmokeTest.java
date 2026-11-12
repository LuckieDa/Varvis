package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf;

/** One opt-in public panel through the current actual Exomiser publication path. No ranking/filter overrides. */
class SpliceAiOrdinaryResourceSmokeTest {
    @Test @EnabledIfSystemProperty(named="varvis.e4b3.runReal", matches="true")
    void fixedPublicIntronicPanelUsesOrdinaryAnalysisAndRecordsAnyAcceptanceBlocker() throws Exception {
        Path root=Path.of(System.getProperty("varvis.e4b3.resourceRoot"));
        Path work=Path.of("target/e4-b3-real-ordinary").toAbsolutePath(); Files.createDirectories(work);
        var base=new AcmgBusinessIntegrationTest() {
            @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations() {
                var v=properties.getVep(); v.setEnabled(true); v.setExecutionEnabled(true);
                v.setExecutable(root.resolve("Tool/vep/bin/varvis-vep.cmd").toString()); v.setCacheDir(root.resolve("Tool/vep/cache").toString());
                v.setFasta(root.resolve("Tool/vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa").toString());
                v.setVersion("116.0"); v.setEnsemblCacheVersion("116_GRCh38");
                var validator=new VepResourceValidator(properties);
                return new VepTranscriptAnnotationEnrichmentService(jobs,properties,validator,new VepCommandBuilder(properties),
                        new VepJsonOutputParser(mapper),new SystemExternalProcessRunner(),
                        new TranscriptAnnotationService(properties,new AnnTranscriptAnnotationProvider(),new InMemoryTranscriptAnnotationCache(),validator));
            }
        };
        base.temporary=work; base.setup(); base.expectedPublishedCount=-1;
        var e=base.properties.getExomiser(); e.setEnabled(true); e.setVersion("15.1.0"); e.setDataVersion("2602");
        e.setJavaExecutable(root.resolve("Tool/exomiser/java/temurin-21.0.11+10-x64-windows/bin/java.exe").toString());
        e.setJavaHeap("4g"); e.setCliJar(root.resolve("Tool/exomiser/env/exomiser-15.1.0/exomiser-cli-15.1.0.jar").toString());
        e.setDataDirectory(root.resolve("Tool/exomiser/data").toString());
        e.setAnalysisTemplate(root.resolve("Tool/exomiser/env/exomiser-15.1.0/examples/preset-exome-analysis.yml").toString());
        e.setResourceFingerprint("edee8dc48a95ca876f91e0105c4d8566b88b51924417d000df20b574591b7278"); e.setTimeout(Duration.ofMinutes(10));
        int[] exomiserCalls={0};
        var runner=new SystemExomiserProcessRunner();
        base.engineAdapterOverride=new ConfiguredExomiserEngineAdapter(base.properties,(cmd,dir,out,err,env,timeout)->{
            exomiserCalls[0]++; return runner.run(cmd,dir,out,err,env,timeout);
        },new ExomiserRuntimeValidator());
        // Fixed before any model output. Public identity sources are in the accompanying short task record.
        // c.315+16G>C (deep intronic) and c.92+5G>C (proximal, BP7 excluded). Synthetic genotype/case only.
        long start=System.nanoTime();
        var report=base.mapper.createObjectNode().put("dataClass","SYNTHETIC").put("inputVariantCount",2)
                .put("hpoMode","NONE").put("rankingTemplateUnchanged",true).put("spliceAiBatches",0).put("criterionCalls",0);
        try {
            base.publish(true,GenomeAssembly.GRCh38,vcf("chr11\t5226561\tpublic-hbb-intron16\tC\tG\t.\tPASS\t.\tGT\t1/1",
                    "chr11\t5226925\tpublic-hbb-intron5\tC\tG\t.\tPASS\t.\tGT\t1/1"));
            var candidates=base.jobs.findInterpretations(base.analysisId,0,100);
            report.set("formalCandidates",base.mapper.valueToTree(candidates));
            report.put("candidateCount",candidates.size());
            report.put("acceptanceStatus","ORDINARY_CANDIDATE_PATH_BLOCKED");
            report.put("reason","Current exome template excludes deep intronic consequences; no BP7 positive ordinary-chain acceptance claimed.");
            assertTrue(candidates.stream().noneMatch(c->c.getObservation().getKey().getPosition()==5226561),
                    "If the current ordinary path now retains a qualified candidate, continue with the real business batch instead of claiming this blocker.");
            if(!candidates.isEmpty()) {
                var request=base.request.deepCopy(); request.put("topN",Math.min(20,candidates.size())); request.putArray("hypotheses");
                var saved=base.postRequest(request); report.set("savedUnavailableDeclarationRun",saved);
                assertEquals(0,saved.path("providerCalls").asInt());
                assertEquals(saved,base.service.read(base.analysisId,saved.path("runId").asText()));
            }
        } finally {
            report.put("exomiserProcessCalls",exomiserCalls[0]).put("wallSeconds",(System.nanoTime()-start)/1_000_000_000.0);
            if(base.analysisId!=null) {
                var job=base.jobs.findById(base.analysisId).orElseThrow(); report.put("analysisStatus",job.getStatus().name());
                report.put("analysisError",job.getErrorMessage());
            }
            Files.writeString(work.resolve("public-panel-result.json"),report.toPrettyString()); base.closeDatabase();
        }
    }
}
