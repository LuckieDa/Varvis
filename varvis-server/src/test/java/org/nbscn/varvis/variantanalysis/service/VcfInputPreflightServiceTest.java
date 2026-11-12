package org.nbscn.varvis.variantanalysis.service;

import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.TribbleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.exception.VariantAnalysisInputException;

import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VcfInputPreflightServiceTest {

    @TempDir
    Path tempDir;

    private final VcfInputPreflightService service = new VcfInputPreflightService();

    @Test
    void scansPlainVcfSequentiallyWithoutAnIndex() throws Exception {
        VcfInputPreflightService.Result result = service.inspect(
                writePlain("sample.vcf", ordinaryVcf()),
                "synthetic"
        );

        assertOrdinaryResult(result);
    }

    @Test
    void singleVariantReviewRequiresOneCalledAutosomalBiallelicSnvAndOneSample() throws Exception {
        String single = ordinaryVcf();
        assertOrdinaryResult(service.inspectSingleVariant(writePlain("single.vcf", single), "synthetic"));
        for (String invalid : List.of(header(),
                single + single.substring(header().length()).replace("\t100\t", "\t101\t"),
                single.replace("\tsynthetic\n", "\tsynthetic\tsecond\n").replace(":10,10\n", ":10,10\t0/1:20:99:10,10\n"),
                single.replace("\n1\t100", "\nX\t100"),
                single.replace("\tA\tC\t", "\tA\tC,G\t"),
                single.replace("\tA\tC\t", "\tA\tCT\t"),
                single.replace("\t0/1:", "\t0/0:"),
                single.replace("\t0/1:", "\t./1:"),
                single.replace("\t0/1:", "\t1:"))) {
            var input = writePlain("not-single.vcf", invalid);
            var error = assertThrows(VariantAnalysisInputException.class,
                    () -> service.inspectSingleVariant(input, "synthetic"));
            assertTrue(error.getMessage().startsWith("SINGLE_VARIANT_REVIEW_INPUT_INVALID"));
        }
    }

    @Test
    void scansOrdinaryGzipSequentiallyWithoutAnIndex() throws Exception {
        Path input = tempDir.resolve("sample.vcf.gz");
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(input))) {
            output.write(ordinaryVcf().getBytes(StandardCharsets.UTF_8));
        }

        VcfInputPreflightService.Result result = service.inspect(input, "synthetic");

        assertOrdinaryResult(result);
    }

    @Test
    void scansBgzfSequentiallyWithoutAnIndex() throws Exception {
        Path input = tempDir.resolve("sample.bgzf.vcf.gz");
        try (OutputStream output = new BlockCompressedOutputStream(input.toFile())) {
            output.write(ordinaryVcf().getBytes(StandardCharsets.UTF_8));
        }

        VcfInputPreflightService.Result result = service.inspect(input, "synthetic");

        assertOrdinaryResult(result);
    }

    @Test
    void parsesTheEntireFileAndMapsAMalformedTailToASafeFixedError() throws Exception {
        Path input = writePlain(
                "malformed-tail.vcf",
                ordinaryVcf() + "1\tnot-a-position\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n"
        );

        VariantAnalysisInputException error = assertThrows(
                VariantAnalysisInputException.class,
                () -> service.inspect(input, "synthetic")
        );

        assertEquals("INVALID_INPUT", error.getErrorCode());
        assertEquals(
                "VCF_CONTENT_PARSE_FAILED: uploaded VCF content could not be parsed completely.",
                error.getMessage()
        );
        assertFalse(error.getMessage().contains(input.toString()));
        assertFalse(error.getMessage().contains("not-a-position"));
    }

    @Test
    void mapsADefiniteDecodeFailureButNeverHidesAWrappedIoFailureAsBadInput() {
        TribbleException parseFailure = new TribbleException("synthetic parse detail");
        RuntimeException mapped = service.mapFormatFailure(parseFailure);
        assertTrue(mapped instanceof VariantAnalysisInputException);
        assertEquals(
                "VCF_CONTENT_PARSE_FAILED: uploaded VCF content could not be parsed completely.",
                mapped.getMessage()
        );

        TribbleException ioFailure = new TribbleException("synthetic infrastructure detail");
        ioFailure.initCause(new IOException("synthetic disk failure"));
        assertSame(ioFailure, service.mapFormatFailure(ioFailure));
    }

    @Test
    void truncatedCompressedContentIsNeverReclassifiedAsAClientParseError() throws Exception {
        Path input = tempDir.resolve("truncated.vcf.gz");
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(input))) {
            output.write(ordinaryVcf().getBytes(StandardCharsets.UTF_8));
        }
        byte[] complete = Files.readAllBytes(input);
        Files.write(input, Arrays.copyOf(complete, complete.length - 8));

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> service.inspect(input, "synthetic")
        );

        assertFalse(failure instanceof VariantAnalysisInputException);
    }

    @Test
    void mapsLazyInvalidDpDecodeToTheSameSafeFixedErrorWithoutLeakingInput() throws Exception {
        String invalidValue = "not-an-int";
        Path input = writePlain(
                "invalid-dp.vcf",
                header()
                        + "1\t100\t.\tA\tC\t50\tPASS\tSYNTHETIC=1\tGT:DP:GQ:AD\t"
                        + "0/1:" + invalidValue + ":99:10,10\n"
        );

        VariantAnalysisInputException error = assertThrows(
                VariantAnalysisInputException.class,
                () -> service.inspect(input, "synthetic")
        );

        assertEquals("INVALID_INPUT", error.getErrorCode());
        assertEquals(
                "VCF_CONTENT_PARSE_FAILED: uploaded VCF content could not be parsed completely.",
                error.getMessage()
        );
        assertFalse(error.getMessage().contains(invalidValue));
        assertFalse(error.getMessage().contains(input.toString()));
    }

    @Test
    void acceptsHeaderOnlyAndOtherSupportedBoundaryRecordsAsWarningsAndCounts() throws Exception {
        VcfInputPreflightService.Result empty = service.inspect(
                writePlain("header-only.vcf", header()),
                "synthetic"
        );
        assertEquals(0L, empty.getRecordCount());
        assertTrue(empty.getWarnings().contains("NO_VARIANT_RECORDS"));

        String boundary = header()
                + "1\t100\t.\tA\t.\t.\t.\t.\t.\t.\n"
                + "1\t101\t.\tA\t<DEL>,*,<NON_REF>,A]1:200]\t.\tq10\t.\tGT\t./.\n";
        VcfInputPreflightService.Result result = service.inspect(
                writePlain("boundaries.vcf", boundary),
                "synthetic"
        );

        assertEquals(2L, result.getRecordCount());
        assertEquals(4L, result.getAlternateAlleleCount());
        assertEquals(0L, result.getSelectedCalledGenotypeCount());
        assertEquals(1L, result.getMultiAllelicRecordCount());
        assertTrue(result.getSymbolicAlleleCount() >= 1L);
        assertEquals(1L, result.getBreakendAlleleCount());
        assertEquals(1L, result.getSpanningDeletionAlleleCount());
        assertEquals(1L, result.getNonRefAlleleCount());
        assertEquals(1L, result.getFilteredRecordCount());
        assertEquals(2L, result.getMissingQualRecordCount());
        assertEquals(1L, result.getMissingFilterRecordCount());
        assertEquals(2L, result.getMissingInfoRecordCount());
        assertTrue(result.getWarnings().contains("SELECTED_GENOTYPE_DP_MISSING"));
        assertTrue(result.getWarnings().contains("SELECTED_GENOTYPE_NO_CALL_PRESENT"));
        assertTrue(result.getWarnings().contains("FILTERED_RECORDS_PRESENT"));
    }

    private void assertOrdinaryResult(VcfInputPreflightService.Result result) {
        assertEquals(1L, result.getRecordCount());
        assertEquals(1L, result.getAlternateAlleleCount());
        assertEquals(1L, result.getSelectedCalledGenotypeCount());
        assertEquals(0L, result.getSelectedNoCallGenotypeCount());
        assertTrue(result.getWarnings().isEmpty());
        assertEquals(
                "Input content preflight passed: records=1, alternateAlleles=1, "
                        + "selectedCalledGenotypes=1, warnings=0",
                result.safeEventMessage()
        );
    }

    private Path writePlain(String name, String content) throws Exception {
        Path output = tempDir.resolve(name);
        Files.write(output, content.getBytes(StandardCharsets.UTF_8));
        return output;
    }

    private String ordinaryVcf() {
        return header()
                + "1\t100\t.\tA\tC\t50\tPASS\tSYNTHETIC=1\tGT:DP:GQ:AD\t0/1:20:99:10,10\n";
    }

    private String header() {
        return "##fileformat=VCFv4.2\n"
                + "##contig=<ID=1,length=249250621>\n"
                + "##FILTER=<ID=q10,Description=\"Synthetic filtered boundary\">\n"
                + "##INFO=<ID=SYNTHETIC,Number=1,Type=Integer,Description=\"Synthetic marker\">\n"
                + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                + "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Read depth\">\n"
                + "##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n"
                + "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allele depth\">\n"
                + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic\n";
    }
}
