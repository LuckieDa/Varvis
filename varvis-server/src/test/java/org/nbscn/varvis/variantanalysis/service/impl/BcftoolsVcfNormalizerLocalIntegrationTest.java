package org.nbscn.varvis.variantanalysis.service.impl;

import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BcftoolsVcfNormalizerLocalIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void normalizesGrch38VcfWithInstalledBcftools() throws IOException {
        VariantAnalysisProperties properties = configuredProperties();
        Path input = tempDir.resolve("input.vcf");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allele depths\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t1000000\t.\tG\tA,T\t.\tPASS\t.\tGT:AD\t1/2:10,12,8\n"
                        + "chr1\t1000001\t.\tGT\tG\t.\tPASS\t.\tGT:AD\t0/1:11,9\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        NormalizationResult result = new BcftoolsVcfNormalizer(properties)
                .normalize(input, tempDir, GenomeAssembly.GRCh38);

        assertTrue(result.isNormalizationApplied());
        assertEquals("bcftools norm + sort", result.getEngine());
        assertTrue(Files.isRegularFile(result.getOutputFile()));
        assertTrue(Files.isRegularFile(Paths.get(result.getOutputFile().toString() + ".csi")));
        List<VariantContext> variants = new ArrayList<VariantContext>();
        try (VCFFileReader reader = new VCFFileReader(result.getOutputFile(), false)) {
            for (VariantContext variant : reader) {
                variants.add(variant);
            }
        }
        assertEquals(3, variants.size());
        assertEquals(
                "chr1|1000000|G|A,T|1",
                variants.get(0).getAttributeAsList("VARVIS_ORIGINAL_VARIANT").stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","))
        );
    }

    @Test
    void normalizesVcfWithoutAlleleDepthFormat() throws IOException {
        VariantAnalysisProperties properties = configuredProperties();
        Path workspace = Files.createDirectory(tempDir.resolve("without-ad"));
        Path input = workspace.resolve("input.vcf");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t1000000\t.\tG\tA\t.\tPASS\t.\tGT\t0/1\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        NormalizationResult result = new BcftoolsVcfNormalizer(properties)
                .normalize(input, workspace, GenomeAssembly.GRCh38);

        assertTrue(result.isNormalizationApplied());
        try (VCFFileReader reader = new VCFFileReader(result.getOutputFile(), false)) {
            assertEquals(1, reader.iterator().stream().count());
        }
    }

    @Test
    void rejectsReferenceAlleleMismatch() throws IOException {
        VariantAnalysisProperties properties = configuredProperties();
        Path workspace = Files.createDirectory(tempDir.resolve("invalid-reference"));
        Path input = workspace.resolve("input.vcf");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr1\t1000001\t.\tGG\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties)
                        .normalize(input, workspace, GenomeAssembly.GRCh38)
        );

        assertTrue(exception.getMessage().contains("Uploaded VCF REF allele does not match"));
    }

    private VariantAnalysisProperties configuredProperties() {
        String executable = System.getenv("VARVIS_BCFTOOLS_EXECUTABLE");
        String reference = System.getenv("VARVIS_GRCH38_REFERENCE");
        assumeTrue(StringUtils.hasText(executable), "VARVIS_BCFTOOLS_EXECUTABLE is not configured");
        assumeTrue(StringUtils.hasText(reference), "VARVIS_GRCH38_REFERENCE is not configured");
        assumeTrue(Files.isRegularFile(Paths.get(executable)), "bcftools executable does not exist");
        assumeTrue(Files.isRegularFile(Paths.get(reference)), "GRCh38 reference FASTA does not exist");
        assumeTrue(Files.isRegularFile(Paths.get(reference + ".fai")), "GRCh38 reference index does not exist");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setBcftoolsExecutable(executable);
        properties.getNormalization().setGrch38Reference(reference);
        return properties;
    }
}
