package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.dto.VcfSampleDetectionResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VcfHeaderSampleDetectionServiceTest {

    private final VcfHeaderSampleDetectionService service = new VcfHeaderSampleDetectionService();

    @Test
    void detectsPlainVcfHeaderSamplesAndAssemblyHint() {
        VcfSampleDetectionResult result = service.detect(input(
                "##fileformat=VCFv4.2\n"
                        + "##contig=<ID=1,length=249250621,assembly=GRCh37>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tmother\tproband\tfather\n"
                        + "1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\t0/1\t0/0\n"
        ), "joint.vcf");

        assertNull(result.getErrorCode());
        assertEquals("VCF", result.getFileType());
        assertEquals("GRCh37", result.getAssemblyHint());
        assertEquals("EXPLICIT_HEADER", result.getAssemblyConfidence());
        assertTrue(!result.isAssemblyConfirmationRequired());
        assertEquals(3, result.getSamples().size());
        assertEquals("mother", result.getSamples().get(0));
        assertEquals("proband", result.getSamples().get(1));
        assertEquals("father", result.getSamples().get(2));
    }

    @Test
    void detectsGzippedVcfHeaderSamples() throws Exception {
        VcfSampleDetectionResult result = service.detect(gzip(
                "##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
        ), "single.vcf.gz");

        assertNull(result.getErrorCode());
        assertEquals("VCF_GZ", result.getFileType());
        assertEquals(1, result.getSamples().size());
        assertEquals("proband", result.getSamples().get(0));
    }

    @Test
    void headerWithoutSamplesReturnsWarning() {
        VcfSampleDetectionResult result = service.detect(input(
                "##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
        ), "sites.vcf");

        assertNull(result.getErrorCode());
        assertTrue(result.getSamples().isEmpty());
        assertTrue(result.getWarnings().toString().contains("no sample columns"));
    }

    @Test
    void malformedHeaderReturnsClearIssue() {
        VcfSampleDetectionResult result = service.detect(input(
                "##fileformat=VCFv4.2\n"
                        + "1\t100\t.\tA\tG\t.\tPASS\t.\n"
        ), "malformed.vcf");

        assertEquals("VCF_HEADER_MALFORMED", result.getErrorCode());
        assertTrue(result.getErrorMessage().contains("#CHROM"));
    }

    @Test
    void stopsAfterChromHeaderWithoutScanningBody() {
        VcfSampleDetectionResult result = service.detect(new HeaderOnlyInputStream(
                "##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
        ), "large-body.vcf");

        assertNull(result.getErrorCode());
        assertEquals(1, result.getSamples().size());
    }

    @Test
    void detectsGrch38FromCanonicalContigLengthWithoutReferenceLabel() {
        VcfSampleDetectionResult result = service.detect(input(
                "##fileformat=VCFv4.2\n"
                        + "##contig=<ID=chr1,length=248956422>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
        ), "contig-only.vcf");

        assertEquals("GRCh38", result.getAssemblyHint());
        assertEquals("CONTIG_SIGNATURE", result.getAssemblyConfidence());
        assertEquals("UCSC_CHR", result.getContigStyle());
        assertTrue(!result.isAssemblyConfirmationRequired());
    }

    @Test
    void requiresConfirmationWhenDeclaredAndCanonicalContigEvidenceConflict() {
        VcfSampleDetectionResult result = service.detect(input(
                "##fileformat=VCFv4.2\n"
                        + "##reference=GRCh37\n"
                        + "##contig=<ID=1,length=248956422>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
        ), "conflicting.vcf");

        assertNull(result.getAssemblyHint());
        assertEquals("CONFLICTING_EVIDENCE", result.getAssemblyConfidence());
        assertTrue(result.isAssemblyConfirmationRequired());
        assertTrue(result.getWarnings().toString().contains("conflicting"));
    }

    private InputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private InputStream gzip(String text) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(output)) {
            gzipOutputStream.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static class HeaderOnlyInputStream extends InputStream {

        private final byte[] header;
        private int offset;

        private HeaderOnlyInputStream(String header) {
            this.header = header.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() throws IOException {
            if (offset >= header.length) {
                throw new IOException("VCF body should not be read for sample detection");
            }
            return header[offset++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }
            int value = read();
            buffer[off] = (byte) value;
            return 1;
        }
    }
}
