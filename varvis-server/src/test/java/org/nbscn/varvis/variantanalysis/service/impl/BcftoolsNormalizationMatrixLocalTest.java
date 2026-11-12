package org.nbscn.varvis.variantanalysis.service.impl;

import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationAlleleMapping;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.VcfQcResult;
import org.nbscn.varvis.variantanalysis.service.NormalizationManifestWriter;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BcftoolsNormalizationMatrixLocalTest {

    @TempDir
    Path tempDir;

    @Test
    void splitsMultiAltAndPreservesSupportedInfoAndFormatSemantics() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("multi-alt"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path input = write(workspace, "input.vcf",
                baseHeader("chr1", 248956422L)
                        + "##INFO=<ID=IA,Number=A,Type=Integer,Description=\"Per ALT\">\n"
                        + "##INFO=<ID=IR,Number=R,Type=Integer,Description=\"Per allele\">\n"
                        + "##INFO=<ID=IG,Number=G,Type=Integer,Description=\"Per genotype\">\n"
                        + formatHeader()
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t50\t.\tA\tC,G\t.\tPASS\tIA=10,20;IR=5,10,20;IG=0,10,20,30,40,50"
                        + "\tGT:DP:GQ:AD:PL\t1/2:30:99:10,12,8:0,10,20,30,40,50\n");

        NormalizationResult result = normalizer(reference, null)
                .normalize(input, workspace, GenomeAssembly.GRCh38);
        List<VariantContext> variants = readVariants(result.getOutputFile());

        assertEquals(2, variants.size());
        assertNotNull(result.getManifest());
        assertEquals("PASSED", result.getManifest().getStatus());
        assertTrue(result.getManifest().getToolVersion().matches("\\d+\\.\\d+.*"));
        String bcftoolsLog = new String(
                Files.readAllBytes(workspace.resolve("bcftools.log")),
                StandardCharsets.UTF_8
        );
        assertEquals(Integer.valueOf(1), result.getManifest().getRawRecordCount(), bcftoolsLog);
        assertEquals(Integer.valueOf(1), result.getManifest().getSplitRecordCount());
        assertTrue(Files.isRegularFile(workspace.resolve(NormalizationManifestWriter.FILE_NAME)));
        for (VariantContext variant : variants) {
            assertEquals(1, variant.getAlternateAlleles().size());
            assertEquals(1, variant.getAttributeAsList("IA").size());
            assertEquals(2, variant.getAttributeAsList("IR").size());
            assertEquals(3, variant.getAttributeAsList("IG").size());
            Genotype genotype = variant.getGenotype("proband");
            assertEquals(30, genotype.getDP());
            assertEquals(99, genotype.getGQ());
            assertEquals(30, Arrays.stream(genotype.getAD()).sum());
            assertEquals(3, genotype.getPL().length);
            assertTrue(originalTag(variant).startsWith("chr1|50|A|C,G|"));
        }
    }

    @Test
    void leftAlignsIndelAndRetainsRawToCanonicalMapping() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("left-align"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path input = write(workspace, "input.vcf",
                baseHeader("chr1", 248956422L)
                        + formatHeader()
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t20\t.\tAA\tA\t.\tPASS\t.\tGT:DP:GQ:AD:PL\t0/1:20:80:8,12:0,20,40\n");

        NormalizationResult result = normalizer(reference, null)
                .normalize(input, workspace, GenomeAssembly.GRCh38);
        VcfQcResult qc = new VcfQcServiceImpl().extract(
                result.getOutputFile(), GenomeAssembly.GRCh38, "proband"
        );
        assertEquals("chr1|20|AA|A", qc.getObservations().get(0).getOriginalRecord());
        NormalizationAlleleMapping mapping = qc.getObservations().get(0).normalizationMapping();

        assertEquals(1, qc.getObservations().get(0).getKey().getPosition());
        assertEquals("chr1|20|AA|A", mapping.getRawRecordId());
        assertEquals(NormalizationAlleleMapping.NORMALIZED, mapping.getStatus());
        assertEquals("GRCh38|1|1|AA|A", mapping.getCanonicalVariantId());
    }

    @Test
    void appliesOnlyAnExplicitContigAliasAndPreservesHaploidGenotype() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("alias-haploid"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path aliases = write(workspace, "aliases.tsv", "1\tchr1\nX\tchrX\n");
        Path input = write(workspace, "input.vcf",
                "##fileformat=VCFv4.2\n"
                        + "##contig=<ID=1,length=248956422>\n"
                        + "##contig=<ID=X,length=156040895>\n"
                        + formatHeader()
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "1\t60\t.\tA\tT\t.\tPASS\t.\tGT:DP:GQ:AD:PL\t0/1:20:80:10,10:0,20,40\n"
                        + "X\t70\t.\tA\tC\t.\tPASS\t.\tGT:DP:GQ:AD:PL\t1:20:80:0,20:0,40\n");

        NormalizationResult result = normalizer(reference, aliases)
                .normalize(input, workspace, GenomeAssembly.GRCh38);
        List<VariantContext> variants = readVariants(result.getOutputFile());

        assertEquals("chr1", variants.get(0).getContig());
        assertEquals("chrX", variants.get(1).getContig());
        assertEquals(1, variants.get(1).getGenotype("proband").getPloidy());
        assertTrue(variants.get(1).getGenotype("proband").isHomVar());
    }

    @Test
    void rejectsReferenceMismatchBeforeProducingUsableOutput() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("ref-mismatch"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path input = write(workspace, "input.vcf",
                baseHeader("chr1", 248956422L)
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr1\t80\t.\tC\tG\t.\tPASS\t.\n");
        BcftoolsVcfNormalizer subject = normalizer(reference, null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> subject.normalize(input, workspace, GenomeAssembly.GRCh38)
        );

        assertTrue(exception.getMessage().contains("REF allele does not match"));
        String manifestJson = new String(
                Files.readAllBytes(workspace.resolve(NormalizationManifestWriter.FILE_NAME)),
                StandardCharsets.UTF_8
        );
        assertTrue(manifestJson.contains("\"status\" : \"FAILED\""));
        assertTrue(manifestJson.contains("\"failureCode\" : \"REFERENCE_MISMATCH\""));
    }

    @Test
    void normalizesNativeGrch37AgainstTheGrch37ProfileOnly() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("native-grch37"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh37, false);
        Path input = write(workspace, "input.vcf",
                baseHeader("1", 249250621L)
                        + formatHeader()
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "1\t90\t.\tA\tT\t.\tPASS\t.\tGT:DP:GQ:AD:PL\t0/1:20:80:10,10:0,20,40\n");

        NormalizationResult result = normalizer(reference, null, GenomeAssembly.GRCh37)
                .normalize(input, workspace, GenomeAssembly.GRCh37);
        List<VariantContext> variants = readVariants(result.getOutputFile());

        assertEquals(1, variants.size());
        assertEquals("1", variants.get(0).getContig());
        assertTrue(result.isNormalizationApplied());
    }

    @Test
    void trimsSharedAlleleContextToMinimalRepresentation() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("minimal"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path input = write(workspace, "input.vcf",
                baseHeader("chr1", 248956422L)
                        + formatHeader()
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t30\t.\tAA\tAC\t.\tPASS\t.\tGT:DP:GQ:AD:PL\t0/1:20:80:10,10:0,20,40\n");

        NormalizationResult result = normalizer(reference, null)
                .normalize(input, workspace, GenomeAssembly.GRCh38);
        VariantContext variant = readVariants(result.getOutputFile()).get(0);

        assertEquals(31, variant.getStart());
        assertEquals("A", variant.getReference().getDisplayString());
        assertEquals("C", variant.getAlternateAllele(0).getDisplayString());
    }

    @Test
    void preservesSupportedTrioFormatValuesAcrossNormalization() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("trio-format"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path input = write(workspace, "input.vcf",
                baseHeader("chr1", 248956422L)
                        + formatHeader()
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT"
                        + "\tproband\tfather\tmother\n"
                        + "chr1\t120\t.\tA\tG\t.\tPASS\t.\tGT:DP:GQ:AD:PL"
                        + "\t0/1:30:99:15,15:0,30,60"
                        + "\t0/0:28:90:28,0:0,50,100"
                        + "\t0/1:26:80:13,13:0,20,40\n");

        NormalizationResult result = normalizer(reference, null)
                .normalize(input, workspace, GenomeAssembly.GRCh38);
        VariantContext variant = readVariants(result.getOutputFile()).get(0);

        assertEquals("A/G", variant.getGenotype("proband").getGenotypeString());
        assertEquals(30, variant.getGenotype("proband").getDP());
        assertEquals(99, variant.getGenotype("proband").getGQ());
        assertEquals(28, variant.getGenotype("father").getAD()[0]);
        assertEquals(13, variant.getGenotype("mother").getAD()[1]);
    }

    @Test
    void mapsMitochondrialAliasExplicitly() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("mitochondrial-alias"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path aliases = write(workspace, "aliases.tsv", "MT\tchrM\n");
        Path input = write(workspace, "input.vcf",
                baseHeader("MT", 16569L)
                        + formatHeader()
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "MT\t100\t.\tA\tG\t.\tPASS\t.\tGT:DP:GQ:AD:PL\t1:20:80:0,20:0,40\n");

        NormalizationResult result = normalizer(reference, aliases)
                .normalize(input, workspace, GenomeAssembly.GRCh38);
        VariantContext variant = readVariants(result.getOutputFile()).get(0);

        assertEquals("chrM", variant.getContig());
        assertEquals(1, variant.getGenotype("proband").getPloidy());
    }

    @Test
    void emptyAltRecordDoesNotCreateACanonicalAllele() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("empty-alt"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path input = write(workspace, "input.vcf",
                baseHeader("chr1", 248956422L)
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t130\t.\tA\t.\t.\tPASS\t.\tGT\t0/0\n");

        NormalizationResult result = normalizer(reference, null)
                .normalize(input, workspace, GenomeAssembly.GRCh38);
        VcfQcResult qc = new VcfQcServiceImpl().extract(
                result.getOutputFile(), GenomeAssembly.GRCh38, "proband"
        );

        assertEquals(1, qc.getSummary().getInputSiteCount());
        assertEquals(0, qc.getSummary().getVariantCount());
        assertTrue(qc.getObservations().isEmpty());
    }

    @Test
    void symbolicAltRemainsExplicitAndIsNotTreatedAsASequenceIndel() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("symbolic-alt"));
        Path reference = writeSyntheticReference(workspace, GenomeAssembly.GRCh38, true);
        Path input = write(workspace, "input.vcf",
                baseHeader("chr1", 248956422L)
                        + "##INFO=<ID=END,Number=1,Type=Integer,Description=\"End coordinate\">\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t140\t.\tA\t<DEL>\t.\tPASS\tEND=150\tGT\t0/1\n");

        NormalizationResult result = normalizer(reference, null)
                .normalize(input, workspace, GenomeAssembly.GRCh38);
        VcfQcResult qc = new VcfQcServiceImpl().extract(
                result.getOutputFile(), GenomeAssembly.GRCh38, "proband"
        );

        assertEquals(1, qc.getObservations().size());
        assertEquals("<DEL>", qc.getObservations().get(0).getKey().getAlternate());
        assertEquals("OTHER", qc.getObservations().get(0).getVariantType());
    }

    private BcftoolsVcfNormalizer normalizer(Path reference, Path aliases) {
        return normalizer(reference, aliases, GenomeAssembly.GRCh38);
    }

    private BcftoolsVcfNormalizer normalizer(Path reference,
                                             Path aliases,
                                             GenomeAssembly assembly) {
        String executable = System.getProperty("varvis.bcftools.executable");
        if (!StringUtils.hasText(executable)) {
            executable = System.getenv("VARVIS_BCFTOOLS_EXECUTABLE");
        }
        assumeTrue(StringUtils.hasText(executable), "VARVIS_BCFTOOLS_EXECUTABLE is not configured");
        assumeTrue(Files.isRegularFile(Paths.get(executable)), "bcftools executable does not exist");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        properties.getNormalization().setBcftoolsExecutable(executable);
        if (assembly == GenomeAssembly.GRCh37) {
            properties.getNormalization().setGrch37Reference(reference.toString());
            properties.getNormalization().setGrch37ProfileId("synthetic-grch37-matrix");
            if (aliases != null) {
                properties.getNormalization().setGrch37ContigAliasFile(aliases.toString());
            }
        } else {
            properties.getNormalization().setGrch38Reference(reference.toString());
            properties.getNormalization().setGrch38ProfileId("synthetic-grch38-matrix");
            if (aliases != null) {
                properties.getNormalization().setGrch38ContigAliasFile(aliases.toString());
            }
        }
        return new BcftoolsVcfNormalizer(properties);
    }

    private Path writeSyntheticReference(Path workspace,
                                         GenomeAssembly assembly,
                                         boolean chrPrefixed) throws IOException {
        String chromosome1 = chrPrefixed ? "chr1" : "1";
        String chromosomeX = chrPrefixed ? "chrX" : "X";
        String sequence = repeated('A', 200);
        String firstHeader = ">" + chromosome1 + "\n";
        String secondHeader = ">" + chromosomeX + "\n";
        String chromosomeM = chrPrefixed ? "chrM" : "MT";
        String thirdHeader = ">" + chromosomeM + "\n";
        String fasta = firstHeader + sequence + "\n"
                + secondHeader + sequence + "\n"
                + thirdHeader + sequence + "\n";
        Path reference = write(workspace, "reference.fa", fasta);
        long firstOffset = firstHeader.getBytes(StandardCharsets.UTF_8).length;
        long secondOffset = (firstHeader + sequence + "\n" + secondHeader)
                .getBytes(StandardCharsets.UTF_8).length;
        long thirdOffset = (firstHeader + sequence + "\n"
                + secondHeader + sequence + "\n" + thirdHeader)
                .getBytes(StandardCharsets.UTF_8).length;
        long chromosome1Length = assembly == GenomeAssembly.GRCh37 ? 249250621L : 248956422L;
        long chromosomeXLength = assembly == GenomeAssembly.GRCh37 ? 155270560L : 156040895L;
        write(workspace, "reference.fa.fai",
                chromosome1 + "\t" + chromosome1Length + "\t" + firstOffset + "\t200\t201\n"
                        + chromosomeX + "\t" + chromosomeXLength + "\t" + secondOffset + "\t200\t201\n"
                        + chromosomeM + "\t16569\t" + thirdOffset + "\t200\t201\n");
        return reference;
    }

    private String baseHeader(String chromosome, long length) {
        return "##fileformat=VCFv4.2\n"
                + "##contig=<ID=" + chromosome + ",length=" + length + ">\n";
    }

    private String formatHeader() {
        return "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                + "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">\n"
                + "##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n"
                + "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allele depths\">\n"
                + "##FORMAT=<ID=PL,Number=G,Type=Integer,Description=\"Phred likelihoods\">\n";
    }

    private List<VariantContext> readVariants(Path output) {
        List<VariantContext> result = new ArrayList<VariantContext>();
        try (VCFFileReader reader = new VCFFileReader(output, false)) {
            for (VariantContext variant : reader) {
                result.add(variant);
            }
        }
        return result;
    }

    private String originalTag(VariantContext variant) {
        return variant.getAttributeAsList("VARVIS_ORIGINAL_VARIANT").stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private Path write(Path workspace, String fileName, String content) throws IOException {
        Path file = workspace.resolve(fileName);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String repeated(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }
}
