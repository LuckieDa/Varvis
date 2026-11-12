package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BcftoolsVcfNormalizerTest {

    @TempDir
    Path tempDir;

    @Test
    void bcftoolsModeRejectsUnresolvedAssemblyExplicitly() throws IOException {
        Path input = tempDir.resolve("input.vcf");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "1\t100\t.\tA\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties).normalize(input, tempDir, null)
        );

        assertTrue(exception.getMessage().contains("assembly must be resolved"));
    }

    @Test
    void copyOnlyModeIsExplicitlyMarkedAsDegraded() throws IOException {
        Path input = tempDir.resolve("input.vcf");
        Files.write(input, "##fileformat=VCFv4.2\n".getBytes(StandardCharsets.UTF_8));
        VariantAnalysisProperties properties = new VariantAnalysisProperties();

        NormalizationResult result = new BcftoolsVcfNormalizer(properties)
                .normalize(input, tempDir, GenomeAssembly.GRCh38);

        assertFalse(result.isNormalizationApplied());
        assertEquals("COPY_ONLY", result.getEngine());
        assertTrue(Files.isRegularFile(result.getOutputFile()));
        assertEquals(1, result.getWarnings().size());
    }

    @Test
    void bcftoolsModeRejectsMissingReferenceFasta() throws IOException {
        Path input = tempDir.resolve("input.vcf");
        Files.write(input, "##fileformat=VCFv4.2\n".getBytes(StandardCharsets.UTF_8));
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties).normalize(input, tempDir, GenomeAssembly.GRCh38)
        );

        assertTrue(exception.getMessage().contains("Reference FASTA"));
    }

    @Test
    void grch37DoesNotFallBackToConfiguredGrch38Reference() throws IOException {
        Path input = tempDir.resolve("input-grch37.vcf");
        Path grch38Reference = tempDir.resolve("grch38.fa");
        Files.write(input, "##fileformat=VCFv4.2\n".getBytes(StandardCharsets.UTF_8));
        Files.write(grch38Reference, ">chr1\nA\n".getBytes(StandardCharsets.UTF_8));
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setGrch38Reference(grch38Reference.toString());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties).normalize(
                        input,
                        tempDir,
                        GenomeAssembly.GRCh37
                )
        );

        assertTrue(exception.getMessage().contains("GRCh37"));
    }

    @Test
    void bcftoolsModeRejectsMissingReferenceFastaIndex() throws IOException {
        Path input = tempDir.resolve("input.vcf");
        Path reference = tempDir.resolve("reference.fa");
        Files.write(input, "##fileformat=VCFv4.2\n".getBytes(StandardCharsets.UTF_8));
        Files.write(reference, ">chr1\nA\n".getBytes(StandardCharsets.UTF_8));
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setGrch38Reference(reference.toString());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties).normalize(input, tempDir, GenomeAssembly.GRCh38)
        );

        assertTrue(exception.getMessage().contains("index"));
    }

    @Test
    void bcftoolsModeRejectsMismatchedAssemblyBeforeRunningCommand() throws IOException {
        Path input = tempDir.resolve("input.vcf");
        Path reference = tempDir.resolve("reference.fa");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##contig=<ID=chr1,length=249250621>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        Files.write(reference, ">chr1\nA\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                tempDir.resolve("reference.fa.fai"),
                "chr1\t248956422\t6\t1\t2\n".getBytes(StandardCharsets.UTF_8)
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setGrch38Reference(reference.toString());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties).normalize(input, tempDir, GenomeAssembly.GRCh38)
        );

        assertTrue(exception.getMessage().contains("GRCh38"));
        assertTrue(exception.getMessage().contains("chr1 length is 249250621"));
        assertTrue(exception.getMessage().contains("selected reference expects 248956422"));
        assertTrue(exception.getMessage().contains("Confirm assembly and reference FASTA"));
    }

    @Test
    void bcftoolsModeRequiresExplicitAliasWhenContigNamesDiffer() throws IOException {
        Path input = tempDir.resolve("input-no-chr.vcf");
        Path reference = tempDir.resolve("reference-chr.fa");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##contig=<ID=1,length=248956422>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "1\t100\t.\tA\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        Files.write(reference, ">chr1\nA\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                tempDir.resolve("reference-chr.fa.fai"),
                "chr1\t248956422\t6\t1\t2\n".getBytes(StandardCharsets.UTF_8)
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setGrch38Reference(reference.toString());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties).normalize(input, tempDir, GenomeAssembly.GRCh38)
        );

        assertTrue(exception.getMessage().contains("explicit assembly-specific contig alias file"));
    }

    @Test
    void bcftoolsModeDerivesVerifiedGrch37UCSCGlAlias() throws IOException {
        Path input = tempDir.resolve("input-ucsc-random.vcf");
        Path reference = tempDir.resolve("reference-grch37.fa");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##contig=<ID=chr1_gl000191_random,length=106433>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr1_gl000191_random\t100\t.\tA\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        Files.write(reference, ">1\nA\n>GL000191.1\nA\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                tempDir.resolve("reference-grch37.fa.fai"),
                ("1\t249250621\t3\t1\t2\n"
                        + "GL000191.1\t106433\t3152017595\t60\t61\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setBcftoolsExecutable("missing-bcftools");
        properties.getNormalization().setGrch37Reference(reference.toString());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties).normalize(input, tempDir, GenomeAssembly.GRCh37)
        );

        assertTrue(exception.getMessage().contains("Could not start bcftools"));
        assertTrue(
                Files.readAllLines(tempDir.resolve("resolved-contig-aliases.tsv"), StandardCharsets.UTF_8)
                        .contains("chr1_gl000191_random\tGL000191.1")
        );
    }

    @Test
    void bcftoolsModePreservesKnownUnsupportedGrch37HaplotypeContigs() throws IOException {
        Path input = tempDir.resolve("input-ucsc-haplotype.vcf");
        Path reference = tempDir.resolve("reference-grch37-primary.fa");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##contig=<ID=chr4_ctg9_hap1,length=590426>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr4_ctg9_hap1\t100\t.\tA\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        Files.write(reference, ">1\nA\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                tempDir.resolve("reference-grch37-primary.fa.fai"),
                "1\t249250621\t3\t1\t2\n".getBytes(StandardCharsets.UTF_8)
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setGrch37Reference(reference.toString());

        NormalizationResult result = new BcftoolsVcfNormalizer(properties)
                .normalize(input, tempDir, GenomeAssembly.GRCh37);

        assertFalse(result.isNormalizationApplied());
        assertEquals("COPY_ONLY_REFERENCE_PROFILE_INCOMPATIBLE", result.getEngine());
        assertTrue(result.getWarnings().get(0).contains("chr4_ctg9_hap1"));
        assertTrue(Files.isRegularFile(result.getOutputFile()));
    }

    @Test
    void bcftoolsModePreservesKnownGrch37MitochondrialReferenceMismatch() throws IOException {
        Path input = tempDir.resolve("input-ucsc-mitochondrial.vcf");
        Path reference = tempDir.resolve("reference-grch37-primary.fa");
        Path aliases = tempDir.resolve("aliases.tsv");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##contig=<ID=chrM,length=16571>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chrM\t100\t.\tA\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        Files.write(reference, ">1\nA\n>MT\nA\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                tempDir.resolve("reference-grch37-primary.fa.fai"),
                "1\t249250621\t3\t1\t2\nMT\t16569\t6\t1\t2\n".getBytes(StandardCharsets.UTF_8)
        );
        Files.write(aliases, "chrM\tMT\n".getBytes(StandardCharsets.UTF_8));
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setGrch37Reference(reference.toString());
        properties.getNormalization().setGrch37ContigAliasFile(aliases.toString());

        NormalizationResult result = new BcftoolsVcfNormalizer(properties)
                .normalize(input, tempDir, GenomeAssembly.GRCh37);

        assertFalse(result.isNormalizationApplied());
        assertEquals("COPY_ONLY_REFERENCE_PROFILE_INCOMPATIBLE", result.getEngine());
        assertTrue(result.getWarnings().get(0).contains("16571 bp"));
        assertTrue(Files.isRegularFile(result.getOutputFile()));
    }

    @Test
    void bcftoolsModeClassifiesCombinedUCSCHg19SupplementaryProfileInOnePass() throws IOException {
        Path input = tempDir.resolve("input-ucsc-hg19-profile.vcf");
        Path reference = tempDir.resolve("reference-grch37-primary.fa");
        Path aliases = tempDir.resolve("aliases.tsv");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##contig=<ID=chrM,length=16571>\n"
                        + "##contig=<ID=chr1,length=249250621>\n"
                        + "##contig=<ID=chr1_gl000191_random,length=106433>\n"
                        + "##contig=<ID=chrUn_gl000211,length=166566>\n"
                        + "##contig=<ID=chr4_ctg9_hap1,length=590426>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        Files.write(reference, ">1\nA\n>MT\nA\n>GL000191.1\nA\n>GL000211.1\nA\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                tempDir.resolve("reference-grch37-primary.fa.fai"),
                ("1\t249250621\t3\t1\t2\n"
                        + "MT\t16569\t6\t1\t2\n"
                        + "GL000191.1\t106433\t9\t1\t2\n"
                        + "GL000211.1\t166566\t12\t1\t2\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        Files.write(aliases, "chrM\tMT\nchr1\t1\n".getBytes(StandardCharsets.UTF_8));
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setGrch37Reference(reference.toString());
        properties.getNormalization().setGrch37ContigAliasFile(aliases.toString());

        NormalizationResult result = new BcftoolsVcfNormalizer(properties)
                .normalize(input, tempDir, GenomeAssembly.GRCh37);

        assertFalse(result.isNormalizationApplied());
        assertEquals("COPY_ONLY_REFERENCE_PROFILE_INCOMPATIBLE", result.getEngine());
        assertEquals(2, result.getWarnings().size());
        assertTrue(result.getWarnings().toString().contains("16571 bp"));
        assertTrue(result.getWarnings().toString().contains("chr4_ctg9_hap1"));
        assertTrue(Files.isRegularFile(result.getOutputFile()));
    }

    @Test
    void bcftoolsModeRejectsSyntheticGrch38CoordinatesBeforeRunningCommand() throws IOException {
        Path input = tempDir.resolve("synthetic.vcf");
        Path reference = tempDir.resolve("reference.fa");
        Files.write(
                input,
                ("##fileformat=VCFv4.2\n"
                        + "##INFO=<ID=SYNTHETIC_GRCH38_POS,Number=0,Type=Flag,Description=\"Not clinical-grade liftover\">\n"
                        + "##contig=<ID=chr1,length=248956422>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\tSYNTHETIC_GRCH38_POS\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        Files.write(reference, ">chr1\nA\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                tempDir.resolve("reference.fa.fai"),
                "chr1\t248956422\t6\t1\t2\n".getBytes(StandardCharsets.UTF_8)
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setGrch38Reference(reference.toString());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new BcftoolsVcfNormalizer(properties).normalize(input, tempDir, GenomeAssembly.GRCh38)
        );

        assertTrue(exception.getMessage().contains("SYNTHETIC_GRCH38_POS"));
        assertTrue(exception.getMessage().contains("validated liftOver"));
    }
}
