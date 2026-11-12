package org.nbscn.varvis.utils;

import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public final class VcfUploadUtils {

    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 512L * 1024L * 1024L;
    public static final long DEFAULT_MAX_EXPANDED_SIZE_BYTES = 1024L * 1024L * 1024L;

    private static final int PREFIX_SIZE = 4096;
    private static final Pattern STORED_FILE_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:vcf|vcf\\.gz|bcf)$",
            Pattern.CASE_INSENSITIVE);

    private VcfUploadUtils() {
    }

    public static UploadMetadata validateUpload(MultipartFile file) throws IOException {
        return validateUpload(file, DEFAULT_MAX_FILE_SIZE_BYTES, DEFAULT_MAX_EXPANDED_SIZE_BYTES);
    }

    public static UploadMetadata validateUpload(MultipartFile file,
                                                long maxFileSizeBytes,
                                                long maxExpandedSizeBytes) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new InvalidVcfUploadException("VCF file is empty");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new InvalidVcfUploadException("VCF file exceeds the configured upload size limit");
        }

        String originalFileName = validateOriginalFileName(file.getOriginalFilename());
        String extension = requireAllowedExtension(originalFileName);
        try (InputStream inputStream = file.getInputStream()) {
            validateContent(inputStream, extension, maxExpandedSizeBytes);
        }
        return new UploadMetadata(originalFileName, extension);
    }

    public static void validateStoredFile(Path file,
                                          long maxFileSizeBytes,
                                          long maxExpandedSizeBytes) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new InvalidVcfUploadException("VCF file does not exist");
        }
        long size = Files.size(file);
        if (size <= 0) {
            throw new InvalidVcfUploadException("VCF file is empty");
        }
        if (size > maxFileSizeBytes) {
            throw new InvalidVcfUploadException("VCF file exceeds the configured upload size limit");
        }
        String extension = requireAllowedExtension(file.getFileName().toString());
        try (InputStream inputStream = Files.newInputStream(file)) {
            validateContent(inputStream, extension, maxExpandedSizeBytes);
        }
    }

    public static String requireSingleSample(Path file) {
        try (VCFFileReader reader = new VCFFileReader(file, false)) {
            return requireSingleSample(reader.getFileHeader());
        } catch (InvalidVcfUploadException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new InvalidVcfUploadException("VCF or BCF file could not be parsed", ex);
        }
    }

    public static String requireSingleSample(VCFHeader header) {
        List<String> sampleNames = header == null ? null : header.getGenotypeSamples();
        int sampleCount = sampleNames == null ? 0 : sampleNames.size();
        if (sampleNames == null || sampleCount != 1) {
            throw new InvalidVcfUploadException(
                    "Exactly one sample is required in this version; found " + sampleCount);
        }
        return sampleNames.get(0);
    }

    public static Path createStoredFile(Path uploadDirectory, String extension) throws IOException {
        requireAllowedExtension("placeholder" + extension);
        Path root = normalizedRoot(uploadDirectory);
        Path storedFile = root.resolve(UUID.randomUUID().toString() + extension).normalize();
        requireContained(root, storedFile);
        return storedFile;
    }

    public static Path resolveStoredFile(Path uploadDirectory, String storedFileName) throws IOException {
        if (!StringUtils.hasText(storedFileName) || !STORED_FILE_NAME.matcher(storedFileName).matches()) {
            throw new InvalidVcfUploadException("Invalid stored VCF file name");
        }
        Path root = normalizedRoot(uploadDirectory);
        Path storedFile = root.resolve(storedFileName).normalize();
        requireContained(root, storedFile);
        if (Files.exists(storedFile)) {
            requireContained(root, storedFile.toRealPath());
        }
        return storedFile;
    }

    public static int deleteExpiredStoredFiles(Path uploadDirectory, Instant cutoff) throws IOException {
        Path root = normalizedRoot(uploadDirectory);
        int deleted = 0;
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file) || !isStoredFileName(file.getFileName().toString())) {
                    continue;
                }
                FileTime lastModified = Files.getLastModifiedTime(file);
                if (lastModified.toInstant().isBefore(cutoff) && Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    public static boolean isStoredFileName(String fileName) {
        return StringUtils.hasText(fileName) && STORED_FILE_NAME.matcher(fileName).matches();
    }

    private static String validateOriginalFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new InvalidVcfUploadException("VCF file name is required");
        }
        String trimmed = fileName.trim();
        if (!trimmed.equals(fileName)
                || trimmed.contains("/")
                || trimmed.contains("\\")
                || trimmed.contains(":")
                || trimmed.indexOf('\0') >= 0) {
            throw new InvalidVcfUploadException("VCF file name must not contain a path");
        }
        return trimmed;
    }

    private static String requireAllowedExtension(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".vcf.gz")) {
            return ".vcf.gz";
        }
        if (normalized.endsWith(".vcf")) {
            return ".vcf";
        }
        if (normalized.endsWith(".bcf")) {
            return ".bcf";
        }
        throw new InvalidVcfUploadException("Only .vcf, .vcf.gz and .bcf files are allowed");
    }

    private static void validateContent(InputStream source,
                                        String extension,
                                        long maxExpandedSizeBytes) throws IOException {
        BufferedInputStream inputStream = new BufferedInputStream(source);
        inputStream.mark(PREFIX_SIZE);
        byte[] sourcePrefix = readPrefix(inputStream);
        inputStream.reset();

        if (".vcf".equals(extension)) {
            requireVcfHeader(sourcePrefix);
            return;
        }

        if (".vcf.gz".equals(extension)) {
            requireGzip(sourcePrefix);
            PrefixAndSize expanded = readExpandedPrefixAndSize(inputStream, maxExpandedSizeBytes);
            requireVcfHeader(expanded.prefix);
            return;
        }

        if (isGzip(sourcePrefix)) {
            PrefixAndSize expanded = readExpandedPrefixAndSize(inputStream, maxExpandedSizeBytes);
            requireBcfHeader(expanded.prefix);
        } else {
            requireBcfHeader(sourcePrefix);
        }
    }

    private static PrefixAndSize readExpandedPrefixAndSize(InputStream source,
                                                           long maxExpandedSizeBytes) throws IOException {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(source)) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            long total = 0;
            int read;
            while ((read = gzipInputStream.read(buffer)) != -1) {
                total += read;
                if (total > maxExpandedSizeBytes) {
                    throw new InvalidVcfUploadException("Compressed VCF expands beyond the configured size limit");
                }
                if (prefix.size() < PREFIX_SIZE) {
                    int remaining = PREFIX_SIZE - prefix.size();
                    prefix.write(buffer, 0, Math.min(remaining, read));
                }
            }
            return new PrefixAndSize(prefix.toByteArray(), total);
        } catch (InvalidVcfUploadException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new InvalidVcfUploadException("Compressed VCF or BCF file is invalid", ex);
        }
    }

    private static byte[] readPrefix(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[PREFIX_SIZE];
        int total = 0;
        int read;
        while (total < buffer.length && (read = inputStream.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        byte[] prefix = new byte[total];
        System.arraycopy(buffer, 0, prefix, 0, total);
        return prefix;
    }

    private static void requireVcfHeader(byte[] prefix) {
        String text = new String(prefix, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (!text.startsWith("##fileformat=VCF")) {
            throw new InvalidVcfUploadException("VCF content does not start with a valid fileformat header");
        }
    }

    private static void requireGzip(byte[] prefix) {
        if (!isGzip(prefix)) {
            throw new InvalidVcfUploadException(".vcf.gz files must use gzip or BGZF compression");
        }
    }

    private static boolean isGzip(byte[] prefix) {
        return prefix.length >= 2 && prefix[0] == (byte) 0x1f && prefix[1] == (byte) 0x8b;
    }

    private static void requireBcfHeader(byte[] prefix) {
        if (prefix.length < 5
                || prefix[0] != 'B'
                || prefix[1] != 'C'
                || prefix[2] != 'F'
                || prefix[3] != 2) {
            throw new InvalidVcfUploadException("BCF content does not start with a supported BCF 2 header");
        }
    }

    private static Path normalizedRoot(Path uploadDirectory) throws IOException {
        if (uploadDirectory == null) {
            throw new InvalidVcfUploadException("VCF upload directory is not configured");
        }
        Files.createDirectories(uploadDirectory);
        return uploadDirectory.toRealPath();
    }

    private static void requireContained(Path root, Path file) {
        if (!file.startsWith(root) || !root.equals(file.getParent())) {
            throw new InvalidVcfUploadException("VCF file path escapes the configured upload directory");
        }
    }

    public static final class UploadMetadata {
        private final String originalFileName;
        private final String extension;

        private UploadMetadata(String originalFileName, String extension) {
            this.originalFileName = originalFileName;
            this.extension = extension;
        }

        public String getOriginalFileName() {
            return originalFileName;
        }

        public String getExtension() {
            return extension;
        }
    }

    private static final class PrefixAndSize {
        private final byte[] prefix;
        @SuppressWarnings("unused")
        private final long size;

        private PrefixAndSize(byte[] prefix, long size) {
            this.prefix = prefix;
            this.size = size;
        }
    }
}
