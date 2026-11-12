package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcSummary;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.AnnTranscriptAnnotationProvider;
import org.nbscn.varvis.variantanalysis.provider.ExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.ProcessResult;
import org.nbscn.varvis.variantanalysis.provider.VepCommandBuilder;
import org.nbscn.varvis.variantanalysis.provider.VepJsonOutputParser;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VepTranscriptAnnotationEnrichmentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledVepDoesNotTriggerRunnerAndKeepsReadyJob() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS);
        InMemoryVariantAnalysisJobRepository repository = repositoryWithVariants(properties, variant(1, 100.0d));

        VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary =
                service(repository, properties, runner).enrichNow("analysis");

        assertEquals("DISABLED", summary.getStatus());
        assertEquals(0, runner.calls);
        assertEquals(VariantAnalysisStatus.READY, repository.findById("analysis").get().getStatus());
        assertEquals("ANN_ONLY",
                repository.findInterpretations("analysis", 0, 1).get(0).getTranscriptAnnotations().get(0).getSource());
    }

    @Test
    void executionDisabledDoesNotTriggerRunner() throws IOException {
        VariantAnalysisProperties properties = readyProperties(false);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS);
        InMemoryVariantAnalysisJobRepository repository = repositoryWithVariants(properties, variant(1, 100.0d));

        VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary =
                service(repository, properties, runner).enrichNow("analysis");

        assertEquals("UNAVAILABLE", summary.getStatus());
        assertEquals(0, runner.calls);
    }

    @Test
    void unavailableReadinessDoesNotTriggerRunner() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS);
        InMemoryVariantAnalysisJobRepository repository = repositoryWithVariants(properties, variant(1, 100.0d));

        VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary =
                service(repository, properties, runner).enrichNow("analysis");

        assertEquals("UNAVAILABLE", summary.getStatus());
        assertEquals(0, runner.calls);
    }

    @Test
    void readyFakeRunnerAnnotatesTopNCandidatesInBatches() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        properties.getVep().setMaxCandidates(2);
        properties.getVep().setBatchSize(1);
        properties.getVep().setTimeoutSeconds(7);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS);
        InMemoryVariantAnalysisJobRepository repository = repositoryWithVariants(
                properties,
                variant(1, 300.0d),
                variant(2, 200.0d),
                variant(3, 100.0d)
        );

        VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary =
                service(repository, properties, runner).enrichNow("analysis");

        assertEquals("COMPLETED", summary.getStatus());
        assertEquals(2, summary.getSelectedCount());
        assertEquals(2, summary.getSuccessCount());
        assertEquals(2, runner.calls);
        assertEquals(Duration.ofSeconds(7), runner.lastTimeout);
        List<VariantInterpretation> variants = repository.findInterpretations("analysis", 0, 10);
        assertEquals("VEP_COMPUTED", variants.get(0).getTranscriptAnnotations().get(0).getSource());
        assertEquals("VEP_COMPUTED", variants.get(1).getTranscriptAnnotations().get(0).getSource());
        assertEquals("ANN_ONLY", variants.get(2).getTranscriptAnnotations().get(0).getSource());
    }

    @Test
    void cacheHitReturnsCachedSourceWithoutRunner() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS);
        VariantInterpretation variant = variant(1, 100.0d);
        InMemoryVariantAnalysisJobRepository repository = repositoryWithVariants(properties, variant);
        VepTranscriptAnnotationEnrichmentService service = service(repository, properties, runner);
        serviceTranscriptCache(service, runner);

        VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary = service.enrichNow("analysis");

        assertEquals("COMPLETED", summary.getStatus());
        assertEquals(1, summary.getCacheHitCount());
        assertEquals(0, runner.calls);
        assertEquals("VEP_CACHED",
                repository.findInterpretations("analysis", 0, 1).get(0).getTranscriptAnnotations().get(0).getSource());
    }

    @Test
    void runnerFailureDoesNotFailBaseJob() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.FAIL);
        InMemoryVariantAnalysisJobRepository repository = repositoryWithVariants(properties, variant(1, 100.0d));

        VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary =
                service(repository, properties, runner).enrichNow("analysis");

        assertEquals("FAILED", summary.getStatus());
        assertEquals(1, summary.getFailedCount());
        assertEquals(VariantAnalysisStatus.READY, repository.findById("analysis").get().getStatus());
        assertEquals("FAILED",
                repository.findInterpretations("analysis", 0, 1).get(0).getTranscriptAnnotations().get(0).getStatus());
    }

    @Test
    void partialParserResultKeepsSuccessfulCandidate() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        properties.getVep().setBatchSize(2);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.PARTIAL);
        InMemoryVariantAnalysisJobRepository repository = repositoryWithVariants(
                properties,
                variant(1, 200.0d),
                variant(2, 100.0d)
        );

        VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary =
                service(repository, properties, runner).enrichNow("analysis");

        assertEquals("COMPLETED", summary.getStatus());
        assertEquals(2, summary.getSuccessCount());
        assertEquals(0, summary.getFailedCount());
        List<VariantInterpretation> variants = repository.findInterpretations("analysis", 0, 2);
        assertEquals("VEP_COMPUTED", variants.get(0).getTranscriptAnnotations().get(0).getSource());
        assertEquals("VEP_COMPUTED", variants.get(1).getTranscriptAnnotations().get(0).getSource());
        assertEquals("NO_RECORD", variants.get(1).getTranscriptAnnotations().get(0).getStatus());
    }

    @Test
    void restartRecoveryTerminatesInterruptedEnrichmentWithoutChangingReadyJob() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS);
        InMemoryVariantAnalysisJobRepository repository = repositoryWithVariants(properties, variant(1, 100.0d));
        repository.appendEvent(
                "analysis",
                VariantAnalysisStatus.READY,
                "VEP transcript annotation PENDING: selected 1 top candidates, batchSize=1"
        );
        repository.appendEvent("analysis", VariantAnalysisStatus.READY, "VEP transcript annotation RUNNING");

        VepTranscriptAnnotationEnrichmentService service = service(repository, properties, runner);
        service.recoverInterruptedEnrichment();
        int eventCountAfterRecovery = repository.findEvents("analysis").size();
        service.recoverInterruptedEnrichment();

        List<org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent> events = repository.findEvents("analysis");
        assertEquals(eventCountAfterRecovery, events.size());
        String latest = events.get(events.size() - 1).getMessage();
        assertTrue(latest.startsWith("VEP transcript annotation UNAVAILABLE: selected=1, processed=0"));
        assertEquals(VariantAnalysisStatus.READY, repository.findById("analysis").get().getStatus());
        assertEquals(0, runner.calls);
    }

    private VepTranscriptAnnotationEnrichmentService service(InMemoryVariantAnalysisJobRepository repository,
                                                            VariantAnalysisProperties properties,
                                                            FakeRunner runner) {
        TranscriptAnnotationService transcriptAnnotationService = new TranscriptAnnotationService(
                properties,
                new AnnTranscriptAnnotationProvider(),
                new InMemoryTranscriptAnnotationCache()
        );
        return new VepTranscriptAnnotationEnrichmentService(
                repository,
                properties,
                new VepResourceValidator(properties),
                new VepCommandBuilder(properties),
                new VepJsonOutputParser(new ObjectMapper()),
                runner,
                transcriptAnnotationService
        );
    }

    private void serviceTranscriptCache(VepTranscriptAnnotationEnrichmentService service,
                                        FakeRunner runner) {
        service.enrichNow("analysis");
        runner.calls = 0;
    }

    private InMemoryVariantAnalysisJobRepository repositoryWithVariants(VariantAnalysisProperties properties,
                                                                        VariantInterpretation... variants)
            throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        Path workspace = Files.createDirectory(tempDir.resolve("analysis-" + System.nanoTime()));
        Path uploadedFile = workspace.resolve("input.vcf");
        Files.write(uploadedFile, new byte[] {1});
        VariantAnalysisJob job = new VariantAnalysisJob(
                "analysis",
                "input.vcf",
                "sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                workspace,
                uploadedFile
        );
        job.complete(
                "proband",
                false,
                "COPY_ONLY",
                new VariantQcSummary(1, variants.length, variants.length, 0, variants.length, 0, 0, 0,
                        variants.length, 0, 0, 0),
                Collections.emptyList(),
                Collections.emptyList()
        );
        repository.save(job);
        TranscriptAnnotationService transcriptAnnotationService = new TranscriptAnnotationService(
                properties,
                new AnnTranscriptAnnotationProvider(),
                new InMemoryTranscriptAnnotationCache()
        );
        transcriptAnnotationService.annotate(Arrays.asList(variants), context());
        repository.replaceInterpretations("analysis", Arrays.asList(variants));
        return repository;
    }

    private VariantAnalysisProperties readyProperties(boolean executionEnabled) throws IOException {
        Path executable = tempDir.resolve("vep");
        Path cache = tempDir.resolve("cache");
        Path fasta = tempDir.resolve("GRCh38.fa");
        Files.write(executable, new byte[] {1});
        Files.createDirectories(cache);
        Files.write(fasta, new byte[] {1});
        Files.write(tempDir.resolve("GRCh38.fa.fai"), new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(executionEnabled);
        properties.getVep().setExecutable(executable.toString());
        properties.getVep().setCacheDir(cache.toString());
        properties.getVep().setFasta(fasta.toString());
        properties.getVep().setVersion("vep-test");
        properties.getVep().setEnsemblCacheVersion("110_GRCh38");
        properties.getVep().setManeVersion("MANE-1.4");
        properties.getVep().setLofteeVersion("loftee-1.0");
        return properties;
    }

    private VariantInterpretation variant(int index, double score) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100 + index, "A", "G"),
                "proband",
                "0/1",
                30,
                99,
                "18,12",
                0.4d,
                "PASS",
                "SNV",
                "chr1|" + (100 + index) + "|A|G|1"
        );
        return new VariantInterpretation(
                "GRCh38:1:" + (100 + index) + ":A:G",
                observation,
                Collections.singletonList(TranscriptConsequence.ann(
                        "GENE" + index,
                        "ENSG" + index,
                        "ENST" + index,
                        "transcript",
                        "missense_variant",
                        "MODERATE",
                        "c.1A>G",
                        "p.Lys1Arg",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now()
                )),
                Collections.emptyList(),
                "LOW_PRIORITY",
                score,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private org.nbscn.varvis.variantanalysis.domain.AnalysisContext context() {
        return new org.nbscn.varvis.variantanalysis.domain.AnalysisContext(
                "analysis",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES
        );
    }

    private static class FakeRunner implements ExternalProcessRunner {

        private enum Mode {
            SUCCESS,
            FAIL,
            PARTIAL
        }

        private final Mode mode;
        private int calls;
        private Duration lastTimeout;

        private FakeRunner(Mode mode) {
            this.mode = mode;
        }

        @Override
        public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
            this.calls++;
            this.lastTimeout = timeout;
            if (mode == Mode.FAIL) {
                return new ProcessResult(2, "simulated failure", false);
            }
            Path output = outputPath(command);
            List<String> ids = variantIds(inputPath(command));
            StringBuilder json = new StringBuilder();
            int emitted = 0;
            for (String id : ids) {
                if (mode == Mode.PARTIAL && emitted > 0) {
                    continue;
                }
                json.append("{\"id\":\"").append(id).append("\",")
                        .append("\"transcript_consequences\":[{")
                        .append("\"gene_symbol\":\"GENE1\",")
                        .append("\"gene_id\":\"ENSG000001\",")
                        .append("\"transcript_id\":\"ENST000001\",")
                        .append("\"consequence_terms\":[\"missense_variant\"],")
                        .append("\"impact\":\"MODERATE\"")
                        .append("}]}\n");
                emitted++;
            }
            if (mode == Mode.PARTIAL) {
                json.append("{broken-json\n");
            }
            Files.write(output, json.toString().getBytes(StandardCharsets.UTF_8));
            return new ProcessResult(0, "ok", false);
        }

        private Path inputPath(List<String> command) {
            int index = command.indexOf("--input_file");
            return java.nio.file.Paths.get(command.get(index + 1));
        }

        private Path outputPath(List<String> command) {
            int index = command.indexOf("--output_file");
            return java.nio.file.Paths.get(command.get(index + 1));
        }

        private List<String> variantIds(Path input) throws IOException {
            List<String> ids = new java.util.ArrayList<String>();
            for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                ids.add(fields[2]);
            }
            return ids;
        }
    }
}
