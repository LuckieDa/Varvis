package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VepTranscriptAnnotationProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void fakeRunnerSuccessReturnsVepAnnotations() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        FakeRunner runner = new FakeRunner(true);
        VepTranscriptAnnotationProvider provider = provider(properties, runner);

        List<TranscriptConsequence> annotations = provider.annotate(variant(), context());

        assertEquals(1, runner.calls);
        assertEquals("GENE1", annotations.get(0).getGeneSymbol());
        assertEquals("VEP_CACHED", annotations.get(0).getSource());
        assertEquals("COMPLETED", annotations.get(0).getStatus());
        assertTrue(runner.command.contains("--json"));
        assertEquals(false, runner.command.contains("--plugin"));
    }

    @Test
    void fakeRunnerFailureReturnsFailedAnnotationWithoutThrowing() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VepTranscriptAnnotationProvider provider = provider(properties, new FakeRunner(false));

        List<TranscriptConsequence> annotations = provider.annotate(variant(), context());

        assertEquals("VEP_CACHED", annotations.get(0).getSource());
        assertEquals("FAILED", annotations.get(0).getStatus());
    }

    @Test
    void readyResourcesButExecutionDisabledDoNotRunVep() throws IOException {
        VariantAnalysisProperties properties = readyProperties(false);
        FakeRunner runner = new FakeRunner(true);
        VepTranscriptAnnotationProvider provider = provider(properties, runner);

        List<TranscriptConsequence> annotations = provider.annotate(variant(), context());

        assertEquals(0, runner.calls);
        assertEquals("DISABLED", annotations.get(0).getStatus());
    }

    @Test
    void fakeRunnerTimeoutRemainsTimeout() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VepTranscriptAnnotationProvider provider = provider(properties, new FakeRunner(false, true));

        List<TranscriptConsequence> annotations = provider.annotate(variant(), context());

        assertEquals("TIMEOUT", annotations.get(0).getStatus());
    }

    @Test
    void runtimeFailureReturnsFailedAnnotationWithoutThrowing() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VepTranscriptAnnotationProvider provider = provider(properties, (command, workingDirectory, timeout) -> {
            throw new IllegalStateException("simulated provider runtime failure");
        });

        List<TranscriptConsequence> annotations = provider.annotate(variant(), context());

        assertEquals("VEP_CACHED", annotations.get(0).getSource());
        assertEquals("FAILED", annotations.get(0).getStatus());
    }

    @Test
    void nestedTimeoutRuntimeRemainsTimeoutWithoutThrowing() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VepTranscriptAnnotationProvider provider = provider(properties, (command, workingDirectory, timeout) -> {
            throw new CompletionException(new TimeoutException("simulated nested timeout"));
        });

        List<TranscriptConsequence> annotations = provider.annotate(variant(), context());

        assertEquals("VEP_CACHED", annotations.get(0).getSource());
        assertEquals("TIMEOUT", annotations.get(0).getStatus());
    }

    @Test
    void batchAnnotatesMultipleVariantsWithOneVepProcess() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        BatchFakeRunner runner = new BatchFakeRunner();
        VepTranscriptAnnotationProvider provider = provider(properties, runner);
        VariantInterpretation first = variantAt(100, "G");
        VariantInterpretation second = variantAt(101, "T");

        Map<NormalizedVariantKey, List<TranscriptConsequence>> annotations =
                provider.annotateBatch(List.of(first, second), context());

        assertEquals(1, runner.calls);
        assertEquals(2, runner.inputRecordCount);
        assertEquals(2, annotations.size());
        assertEquals("COMPLETED", annotations.get(first.getObservation().getKey()).get(0).getStatus());
        assertEquals("COMPLETED", annotations.get(second.getObservation().getKey()).get(0).getStatus());
    }

    private VepTranscriptAnnotationProvider provider(VariantAnalysisProperties properties,
                                                     ExternalProcessRunner runner) {
        return new VepTranscriptAnnotationProvider(
                properties,
                new VepResourceValidator(properties),
                new VepCommandBuilder(properties),
                new VepJsonOutputParser(new ObjectMapper()),
                runner
        );
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
        return properties;
    }

    @Test
    void cleansOnlyOwnedScratchAfterReadingAnnotationsAndKeepsPersistentFiles() throws IOException {
        var properties = readyProperties(true);
        Path scratch = Files.createDirectory(tempDir.resolve("scratch"));
        properties.getVep().setTempDir(scratch.toString());
        Path saved = Files.writeString(tempDir.resolve("saved-acmg.json"), "{\"saved\":true}");
        Path unrelated = Files.writeString(scratch.resolve("unrelated-input.vcf"), "keep");
        var runner = new FakeRunner(true);
        var annotations = provider(properties, runner).annotate(variant(), context());
        assertEquals("COMPLETED", annotations.get(0).getStatus());
        assertTrue(runner.workspace.startsWith(scratch));
        assertEquals(false, Files.exists(runner.workspace));
        assertEquals("{\"saved\":true}", Files.readString(saved));
        assertEquals("keep", Files.readString(unrelated));
    }

    private VariantInterpretation variant() {
        return variantAt(100, "G");
    }

    private VariantInterpretation variantAt(int position, String alternate) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", position, "A", alternate),
                "proband",
                "0/1",
                30,
                99,
                "18,12",
                0.4d,
                "PASS",
                "SNV",
                "chr1|" + position + "|A|" + alternate + "|1"
        );
        return new VariantInterpretation(
                "GRCh38:1:" + position + ":A:" + alternate,
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private AnalysisContext context() {
        return new AnalysisContext("analysis", GenomeAssembly.GRCh38, AnalysisMode.WES);
    }

    private static class FakeRunner implements ExternalProcessRunner {

        private final boolean success;
        private final boolean timedOut;
        private int calls;
        private List<String> command;
        private Path workspace;

        private FakeRunner(boolean success) {
            this(success, false);
        }

        private FakeRunner(boolean success, boolean timedOut) {
            this.success = success;
            this.timedOut = timedOut;
        }

        @Override
        public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
            this.calls++;
            this.command = command;
            this.workspace = workingDirectory;
            if (!success) {
                return new ProcessResult(2, "simulated failure", timedOut);
            }
            Path output = outputPath(command);
            Files.write(output, ("{\"transcript_consequences\":[{"
                    + "\"gene_symbol\":\"GENE1\","
                    + "\"gene_id\":\"ENSG000001\","
                    + "\"transcript_id\":\"ENST000001\","
                    + "\"consequence_terms\":[\"missense_variant\"],"
                    + "\"impact\":\"MODERATE\""
                    + "}]}\n").getBytes(StandardCharsets.UTF_8));
            return new ProcessResult(0, "ok", false);
        }

        private Path outputPath(List<String> command) {
            int index = command.indexOf("--output_file");
            return java.nio.file.Paths.get(command.get(index + 1));
        }
    }

    private static final class BatchFakeRunner implements ExternalProcessRunner {
        private int calls;
        private int inputRecordCount;

        @Override
        public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
            calls++;
            Path input = argumentPath(command, "--input_file");
            Path output = argumentPath(command, "--output_file");
            List<String> records = Files.readAllLines(input, StandardCharsets.UTF_8);
            StringBuilder json = new StringBuilder();
            for (String line : records) {
                if (line.startsWith("#")) {
                    continue;
                }
                inputRecordCount++;
                String[] columns = line.split("\\t", -1);
                json.append("{\"id\":\"").append(columns[2]).append("\",")
                        .append("\"transcript_consequences\":[{")
                        .append("\"gene_symbol\":\"GENE1\",")
                        .append("\"gene_id\":\"ENSG000001\",")
                        .append("\"transcript_id\":\"ENST000001\",")
                        .append("\"consequence_terms\":[\"missense_variant\"],")
                        .append("\"impact\":\"MODERATE\"}]}\n");
            }
            Files.write(output, json.toString().getBytes(StandardCharsets.UTF_8));
            return new ProcessResult(0, "ok", false);
        }

        private Path argumentPath(List<String> command, String name) {
            int index = command.indexOf(name);
            return java.nio.file.Paths.get(command.get(index + 1));
        }
    }
}
