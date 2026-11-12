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
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
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

class SpecialistTabixProvidersLocalIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void queriesAlphaMissenseBgzipAndTabixFile() throws Exception {
        Path compressed = compressAndIndex(
                "alphamissense.tsv",
                "#CHROM\tPOS\tREF\tALT\tgenome\tuniprot_id\ttranscript_id\tprotein_variant\tam_pathogenicity\tam_class\n"
                        + "chr1\t100\tA\tG\thg38\tP12345\tENST0001\tK34R\t0.9234\tlikely_pathogenic\n"
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getAlphaMissense().setEnabled(true);
        properties.getAlphaMissense().setGrch38File(compressed.toString());

        EvidenceItem evidence = evidence(
                new AlphaMissenseProvider(properties).annotate(
                        Collections.singletonList(variant("missense_variant")),
                        context()
                )
        );

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals(0.9234d, evidence.getRawScore());
        assertEquals("K34R", evidence.getDetails().get("proteinVariant"));
    }

    @Test
    void queriesSpliceAiBgzipAndTabixVcf() throws Exception {
        Path compressed = compressAndIndex(
                "spliceai.vcf",
                "##fileformat=VCFv4.2\n"
                        + "##INFO=<ID=SpliceAI,Number=.,Type=String,Description=\"SpliceAI\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\tSpliceAI=G|GENE1|0.01|0.02|0.91|0.04|1|2|-3|4\n"
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getSpliceAi().setEnabled(true);
        properties.getSpliceAi().setGrch38File(compressed.toString());

        EvidenceItem evidence = evidence(
                new SpliceAiProvider(properties).annotate(
                        Collections.singletonList(variant("splice_donor_variant")),
                        context()
                )
        );

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals(0.91d, evidence.getRawScore());
    }

    private Path compressAndIndex(String fileName, String content) throws Exception {
        Path bgzip = executable("VARVIS_BGZIP_EXECUTABLE");
        Path tabix = executable("VARVIS_TABIX_EXECUTABLE");
        Path source = tempDir.resolve(fileName);
        Files.write(source, content.getBytes(StandardCharsets.UTF_8));
        run(bgzip.toString(), "-f", source.toString());
        Path compressed = tempDir.resolve(fileName + ".gz");
        run(tabix.toString(), "-f", "-s", "1", "-b", "2", "-e", "2", compressed.toString());
        return compressed;
    }

    private EvidenceItem evidence(Map<NormalizedVariantKey, List<EvidenceItem>> result) {
        return result.values().iterator().next().get(0);
    }

    private VariantInterpretation variant(String consequence) {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        return new VariantInterpretation(
                "GRCh38:1:100:A:G",
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "chr1|100|A|G|1"
                ),
                Collections.singletonList(new TranscriptConsequence(null, null, null, null, null, consequence)),
                Collections.emptyList()
        );
    }

    private AnalysisContext context() {
        return new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES);
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

