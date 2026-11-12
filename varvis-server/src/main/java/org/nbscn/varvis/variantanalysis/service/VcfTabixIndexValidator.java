package org.nbscn.varvis.variantanalysis.service;

import htsjdk.samtools.util.BlockCompressedInputStream;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.IndexFactory;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.index.tabix.TabixIndex;
import htsjdk.variant.vcf.VCFCodec;
import org.nbscn.varvis.exception.InvalidVcfUploadException;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Prepares a private, job-local Tabix index for a compressed VCF. */
public final class VcfTabixIndexValidator {

    private VcfTabixIndexValidator() {
    }

    public static PreparedVcf prepareForExomiser(Path uploadedVcf, String extension) throws IOException {
        if (!".vcf.gz".equals(extension)) {
            return new PreparedVcf(uploadedVcf, null, false);
        }
        Path executionVcf = uploadedVcf;
        boolean normalizedToBgzf = !isBgzf(uploadedVcf);
        if (normalizedToBgzf) {
            executionVcf = uploadedVcf.resolveSibling(
                    uploadedVcf.getFileName().toString().replace(".vcf.gz", ".exomiser.vcf.gz"));
            normalizeGzipToBgzf(uploadedVcf, executionVcf);
        }
        Path indexFile = executionVcf.resolveSibling(executionVcf.getFileName() + ".tbi");
        try {
            TabixIndex index = IndexFactory.createTabixIndex(
                    executionVcf, new VCFCodec(), TabixFormat.VCF, null);
            index.write(indexFile);
            verifyStoredPair(executionVcf, indexFile);
            return new PreparedVcf(executionVcf, indexFile, normalizedToBgzf);
        } catch (IOException ex) {
            Files.deleteIfExists(indexFile);
            throw new InvalidVcfUploadException(
                    "Compressed VCF could not be indexed for analysis; verify that records are coordinate-sorted.", ex);
        } catch (RuntimeException ex) {
            Files.deleteIfExists(indexFile);
            throw new InvalidVcfUploadException(
                    "Compressed VCF could not be indexed for analysis; verify that records are coordinate-sorted.", ex);
        }
    }

    public static void verifyStoredPair(Path vcfFile, Path indexFile) throws IOException {
        if (!Files.isRegularFile(vcfFile) || !Files.isRegularFile(indexFile)
                || !indexFile.getFileName().toString().equals(vcfFile.getFileName() + ".tbi")) {
            throw new InvalidVcfUploadException("Generated Tabix index is not paired with the uploaded VCF");
        }
        try {
            new TabixIndex(indexFile);
        } catch (IOException ex) {
            throw new InvalidVcfUploadException("Generated VCF index could not be read", ex);
        }
    }

    private static boolean isBgzf(Path vcfFile) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(vcfFile))) {
            return BlockCompressedInputStream.isValidFile(input);
        }
    }

    private static void normalizeGzipToBgzf(Path source, Path target) throws IOException {
        try (InputStream input = new GZIPInputStream(Files.newInputStream(source));
             OutputStream output = new BlockCompressedOutputStream(target.toFile())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ex) {
            Files.deleteIfExists(target);
            throw ex;
        }
    }

    public static final class PreparedVcf {
        private final Path executionVcf;
        private final Path tabixIndex;
        private final boolean normalizedToBgzf;

        private PreparedVcf(Path executionVcf, Path tabixIndex, boolean normalizedToBgzf) {
            this.executionVcf = executionVcf;
            this.tabixIndex = tabixIndex;
            this.normalizedToBgzf = normalizedToBgzf;
        }

        public Path getExecutionVcf() {
            return executionVcf;
        }

        public Path getTabixIndex() {
            return tabixIndex;
        }

        public boolean isNormalizedToBgzf() {
            return normalizedToBgzf;
        }

        public static PreparedVcf unindexed(Path executionVcf) {
            return new PreparedVcf(executionVcf, null, false);
        }
    }
}
