package org.nbscn.varvis.variantanalysis.service;

import htsjdk.samtools.util.BlockCompressedOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VcfTabixIndexValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void createsPrivateTabixIndexForBgzfUploadWithoutClientSidecar() throws Exception {
        Path uploaded = writeVcf(tempDir.resolve("case.vcf.gz"), true);

        VcfTabixIndexValidator.PreparedVcf prepared =
                VcfTabixIndexValidator.prepareForExomiser(uploaded, ".vcf.gz");

        assertEquals(uploaded, prepared.getExecutionVcf());
        assertFalse(prepared.isNormalizedToBgzf());
        assertTrue(Files.isRegularFile(prepared.getTabixIndex()));
        VcfTabixIndexValidator.verifyStoredPair(prepared.getExecutionVcf(), prepared.getTabixIndex());
    }

    @Test
    void normalizesPlainGzipAndCreatesPrivateTabixIndexWithoutClientSidecar() throws Exception {
        Path uploaded = writeVcf(tempDir.resolve("case.vcf.gz"), false);

        VcfTabixIndexValidator.PreparedVcf prepared =
                VcfTabixIndexValidator.prepareForExomiser(uploaded, ".vcf.gz");

        assertTrue(prepared.isNormalizedToBgzf());
        assertFalse(uploaded.equals(prepared.getExecutionVcf()));
        assertTrue(Files.isRegularFile(prepared.getExecutionVcf()));
        assertTrue(Files.isRegularFile(prepared.getTabixIndex()));
        VcfTabixIndexValidator.verifyStoredPair(prepared.getExecutionVcf(), prepared.getTabixIndex());
    }

    private Path writeVcf(Path target, boolean bgzf) throws Exception {
        String vcf = "##fileformat=VCFv4.2\n"
                + "##contig=<ID=1,length=249250621>\n"
                + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                + "1\t100\t.\tA\tC\t.\tPASS\t.\tGT\t0/1\n";
        try (OutputStream output = bgzf
                ? new BlockCompressedOutputStream(target.toFile())
                : new GZIPOutputStream(Files.newOutputStream(target))) {
            output.write(vcf.getBytes(StandardCharsets.UTF_8));
        }
        return target;
    }
}
