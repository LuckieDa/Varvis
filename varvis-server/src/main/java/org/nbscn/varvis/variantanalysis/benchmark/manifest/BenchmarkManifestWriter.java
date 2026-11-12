package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.regex.Pattern;

public final class BenchmarkManifestWriter {

    private static final Pattern PROTECTED_TERMINAL_STATUS = Pattern.compile(
            "\"(?:status|caseExecutionStatus)\"\\s*:\\s*"
                    + "\"(?:FROZEN|COMPLETED|COMPLETED_WITH_FAILURES)\""
    );

    private BenchmarkManifestWriter() {
    }

    public static Path writeDataset(Path directory,
                                    BenchmarkDatasetManifest manifest,
                                    BenchmarkManifestCodec codec) throws IOException {
        BenchmarkManifestValidator.requireValid(manifest);
        return write(directory, "dataset-manifest.json", codec.toJson(manifest), true);
    }

    public static Path writeRun(Path directory,
                                BenchmarkRunManifest manifest,
                                BenchmarkManifestCodec codec) throws IOException {
        BenchmarkManifestValidator.requireValid(manifest);
        return write(directory, "run-manifest-" + safeFilePart(manifest.getRunId()) + ".json",
                codec.toJson(manifest), true);
    }

    public static Path writeCaseResult(Path directory,
                                       BenchmarkCaseResultManifest manifest,
                                       BenchmarkManifestCodec codec) throws IOException {
        BenchmarkManifestValidator.requireValid(manifest);
        return writeNewAtomic(directory,
                "case-result-" + safeFilePart(manifest.getResultId()) + ".json",
                codec.toJson(manifest));
    }

    public static Path writeTruthPolicy(Path directory,
                                        BenchmarkTruthPolicyManifest manifest,
                                        BenchmarkManifestCodec codec) throws IOException {
        BenchmarkTruthPolicyValidator.requireValid(manifest);
        return write(directory, "truth-policy-manifest.json", codec.toJson(manifest), true);
    }

    private static Path write(Path directory, String fileName, String json, boolean protectCompleted)
            throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("directory is required");
        }
        Files.createDirectories(directory);
        Path destination = directory.resolve(fileName);
        if (protectCompleted && Files.exists(destination)) {
            String existing = new String(Files.readAllBytes(destination), java.nio.charset.StandardCharsets.UTF_8);
            if (PROTECTED_TERMINAL_STATUS.matcher(existing).find()) {
                throw new IOException("COMPLETED_MANIFEST_EXISTS: " + fileName);
            }
        }
        Path temporary = directory.resolve(fileName + ".tmp");
        try {
            Files.write(temporary, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                return Files.move(temporary, destination,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                return Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path writeNewAtomic(Path directory, String fileName, String json)
            throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("directory is required");
        }
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDirectory);
        Path destination = normalizedDirectory.resolve(fileName).normalize();
        if (destination.getParent() == null
                || !destination.getParent().equals(normalizedDirectory)) {
            throw new IllegalArgumentException("manifest path is invalid");
        }
        if (Files.exists(destination)) {
            String existing = new String(
                    Files.readAllBytes(destination), java.nio.charset.StandardCharsets.UTF_8);
            if (PROTECTED_TERMINAL_STATUS.matcher(existing).find()) {
                throw new IOException("COMPLETED_MANIFEST_EXISTS: " + fileName);
            }
            throw new IOException("CASE_RESULT_MANIFEST_EXISTS: " + fileName);
        }
        byte[] content = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(
                normalizedDirectory, fileName + ".", ".tmp").normalize();
        boolean destinationCreated = false;
        try {
            Files.write(temporary, content,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                destinationCreated = true;
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IOException("CASE_RESULT_ATOMIC_MOVE_UNSUPPORTED", ex);
            }
            if (!Arrays.equals(content, Files.readAllBytes(destination))) {
                throw new IOException("CASE_RESULT_ARTIFACT_VERIFICATION_FAILED");
            }
            return destination;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
                if (destinationCreated) {
                    Files.deleteIfExists(destination);
                }
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String safeFilePart(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("manifest ID is not a safe file name");
        }
        return value;
    }
}
