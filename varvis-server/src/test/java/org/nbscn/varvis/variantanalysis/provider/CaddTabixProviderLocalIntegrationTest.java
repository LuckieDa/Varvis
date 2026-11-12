package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaddTabixProviderLocalIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void queriesLocalBgzipAndTabixCaddFile() throws Exception {
        Path bgzip = executable("VARVIS_BGZIP_EXECUTABLE");
        Path tabix = executable("VARVIS_TABIX_EXECUTABLE");
        Path source = tempDir.resolve("cadd.tsv");
        Files.write(
                source,
                ("#Chrom\tPos\tRef\tAlt\tRawScore\tPHRED\n"
                        + "1\t100\tA\tG\t2.100000\t24.300\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        run(bgzip.toString(), "-f", source.toString());
        Path compressed = tempDir.resolve("cadd.tsv.gz");
        run(tabix.toString(), "-f", "-s", "1", "-b", "2", "-e", "2", compressed.toString());
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setEnabled(true);
        properties.getCadd().setGrch38SnvFile(compressed.toString());

        Map<NormalizedVariantKey, List<EvidenceItem>> result = new CaddTabixProvider(properties).annotate(
                Collections.singletonList(variant()),
                new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES)
        );

        assertEquals(24.3d, result.get(variant().getObservation().getKey()).get(0).getRawScore());
    }

    @Test
    void missingAlleleReturnsNoRecord() throws Exception {
        Path bgzip = executable("VARVIS_BGZIP_EXECUTABLE");
        Path tabix = executable("VARVIS_TABIX_EXECUTABLE");
        Path source = tempDir.resolve("cadd.tsv");
        Files.write(
                source,
                ("#Chrom\tPos\tRef\tAlt\tRawScore\tPHRED\n"
                        + "1\t100\tA\tG\t2.100000\t24.300\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        run(bgzip.toString(), "-f", source.toString());
        Path compressed = tempDir.resolve("cadd.tsv.gz");
        run(tabix.toString(), "-f", "-s", "1", "-b", "2", "-e", "2", compressed.toString());
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setEnabled(true);
        properties.getCadd().setGrch38SnvFile(compressed.toString());
        VariantInterpretation missingAllele = variant("GRCh38:1:100:A:T", "T");

        Map<NormalizedVariantKey, List<EvidenceItem>> result = new CaddTabixProvider(properties).annotate(
                Collections.singletonList(missingAllele),
                new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES)
        );

        assertEquals("NO_RECORD", result.get(missingAllele.getObservation().getKey()).get(0).getApplicability());
    }

    private VariantInterpretation variant() {
        return variant("GRCh38:1:100:A:G", "G");
    }

    private VariantInterpretation variant(String id, String alternate) {
        return new VariantInterpretation(
                id,
                new VariantQcObservation(
                        new NormalizedVariantKey("GRCh38", "1", 100, "A", alternate),
                        "proband",
                        "0/1",
                        30,
                        99,
                        "18,12",
                        0.4d,
                        "PASS",
                        "SNV",
                        "chr1|100|A|" + alternate + "|1"
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private Path executable(String variable) {
        String value = System.getenv(variable);
        Assumptions.assumeTrue(value != null && !value.trim().isEmpty(), variable + " is not configured");
        Path path = Paths.get(value);
        Assumptions.assumeTrue(Files.isRegularFile(path), variable + " is not a file");
        return path;
    }

    private void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed with exit code " + exitCode);
        }
    }
}
