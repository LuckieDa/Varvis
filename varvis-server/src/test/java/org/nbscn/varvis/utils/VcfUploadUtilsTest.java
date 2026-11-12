package org.nbscn.varvis.utils;

import htsjdk.variant.vcf.VCFHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VcfUploadUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsVcfAndStoresItWithUuidOnlyName() throws IOException {
        VcfUploadUtils.UploadMetadata metadata = VcfUploadUtils.validateUpload(vcf("patient.vcf"));

        Path stored = VcfUploadUtils.createStoredFile(tempDir, metadata.getExtension());

        assertTrue(VcfUploadUtils.isStoredFileName(stored.getFileName().toString()));
        assertFalse(stored.getFileName().toString().contains("patient"));
    }

    @Test
    void rejectsEmptyUpload() {
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.validateUpload(file("empty.vcf", new byte[0]))
        );
    }

    @Test
    void rejectsPathTraversalAndAbsoluteFileNames() {
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.validateUpload(vcf("../patient.vcf"))
        );
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.validateUpload(vcf("C:\\temp\\patient.vcf"))
        );
    }

    @Test
    void rejectsUnsupportedExtensionAndForgedVcfContent() {
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.validateUpload(file("patient.txt", validVcf()))
        );
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.validateUpload(file("patient.vcf", "not a vcf".getBytes(StandardCharsets.UTF_8)))
        );
    }

    @Test
    void rejectsForgedGzipAndExpandedSizeLimit() throws IOException {
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.validateUpload(file("patient.vcf.gz", validVcf()))
        );

        byte[] compressed = gzip(("##fileformat=VCFv4.2\n" + repeat("A", 2048))
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.validateUpload(file("patient.vcf.gz", compressed), 4096, 128)
        );
    }

    @Test
    void rejectsUploadSizeLimit() {
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.validateUpload(vcf("patient.vcf"), 8, 1024)
        );
    }

    @Test
    void rejectsMissingVcfHeaderWhenSelectingSingleSample() {
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.requireSingleSample((VCFHeader) null)
        );
    }

    @Test
    void resolvesOnlyContainedUuidStoredFiles() throws IOException {
        Path stored = VcfUploadUtils.createStoredFile(tempDir, ".vcf");

        assertEquals(stored, VcfUploadUtils.resolveStoredFile(tempDir, stored.getFileName().toString()));
        assertThrows(
                InvalidVcfUploadException.class,
                () -> VcfUploadUtils.resolveStoredFile(tempDir, "../patient.vcf")
        );
    }

    @Test
    void deletesOnlyExpiredUuidStoredFiles() throws IOException {
        Path expired = VcfUploadUtils.createStoredFile(tempDir, ".vcf");
        Path recent = VcfUploadUtils.createStoredFile(tempDir, ".vcf");
        Path unrelated = tempDir.resolve("keep.txt");
        Files.write(expired, validVcf());
        Files.write(recent, validVcf());
        Files.write(unrelated, "keep".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(
                expired,
                java.nio.file.attribute.FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS))
        );

        int deleted = VcfUploadUtils.deleteExpiredStoredFiles(
                tempDir,
                Instant.now().minus(1, ChronoUnit.DAYS)
        );

        assertEquals(1, deleted);
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(unrelated));
    }

    private MockMultipartFile vcf(String fileName) {
        return file(fileName, validVcf());
    }

    private MockMultipartFile file(String fileName, byte[] content) {
        return new MockMultipartFile("vcfFile", fileName, "application/octet-stream", content);
    }

    private byte[] validVcf() {
        return ("##fileformat=VCFv4.2\n"
                + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                + "1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(content);
        }
        return output.toByteArray();
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
