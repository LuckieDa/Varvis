package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticSpikedVcfValidatorTest {

    @Test
    void validatesChr1To22AndTruthSnvAndIndel(@TempDir Path directory) throws Exception {
        Path vcf = copyFixture(directory);
        SyntheticVcfValidationRequest request = request();
        SyntheticVcfValidationResult result = new SyntheticSpikedVcfValidator().validate(vcf, request);
        assertTrue(result.isValid(), result.getIssues().toString());
        assertEquals(2, result.getMatchedTruthVariants().size());
        assertEquals("SYNTHETIC_SAMPLE", result.getSampleId());
    }

    @Test
    void rejectsChr22OnlyScopeAndAssemblyMismatch(@TempDir Path directory) throws Exception {
        Path vcf = copyFixture(directory);
        Path chr22Only = directory.resolve("chr22-only.vcf");
        Files.write(chr22Only, ("##fileformat=VCFv4.2\n##reference=GRCh38\n"
                + "##contig=<ID=chr22>\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tSYNTHETIC_SAMPLE\n"
                + "chr22\t220\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(SyntheticVcfValidationStatus.SCOPE_INCOMPLETE,
                new SyntheticSpikedVcfValidator().validate(chr22Only, request()).getStatus());
        SyntheticVcfValidationRequest request = request();
        request.setRequiredChromosomes(Arrays.asList("22", "1"));
        assertEquals(SyntheticVcfValidationStatus.VALID,
                new SyntheticSpikedVcfValidator().validate(vcf, request).getStatus());
        request.setExpectedAssembly("GRCh37");
        assertEquals(SyntheticVcfValidationStatus.ASSEMBLY_MISMATCH,
                new SyntheticSpikedVcfValidator().validate(vcf, request).getStatus());
    }

    @Test
    void rejectsMissingTruthAndMalformedRecord(@TempDir Path directory) throws Exception {
        Path vcf = copyFixture(directory);
        SyntheticVcfValidationRequest request = request();
        request.setTruthVariants(Arrays.asList(NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, "1", 999, "A", "G")));
        assertEquals(SyntheticVcfValidationStatus.TRUTH_MISSING,
                new SyntheticSpikedVcfValidator().validate(vcf, request).getStatus());
        Files.write(vcf, ("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tSYNTHETIC_SAMPLE\n"
                + "chr1\tbad\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(SyntheticVcfValidationStatus.MALFORMED_RECORD,
                new SyntheticSpikedVcfValidator().validate(vcf, request()).getStatus());
    }

    private SyntheticVcfValidationRequest request() {
        SyntheticVcfValidationRequest request = new SyntheticVcfValidationRequest();
        request.setExpectedAssembly("GRCh38");
        request.setExpectedSampleId("SYNTHETIC_SAMPLE");
        request.setTruthVariants(Arrays.asList(
                NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, "1", 100, "A", "G"),
                NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, "2", 200, "AT", "A")));
        return request;
    }

    private Path copyFixture(Path directory) throws IOException {
        Path path = directory.resolve("synthetic.vcf");
        try (InputStream input = getClass().getResourceAsStream("/benchmark/synthetic-background-grch38-chr1-22.vcf")) {
            Files.copy(input, path);
        }
        return path;
    }
}
