package org.nbscn.varvis.variantanalysis.benchmark.telemetry;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class BenchmarkTelemetryWriter {

    private static final ConcurrentMap<String, Object> JVM_LOCKS =
            new ConcurrentHashMap<String, Object>();

    private BenchmarkTelemetryWriter() { }

    public static BenchmarkTelemetryArtifact write(Path outputDirectory,
                                                    String resultId,
                                                    BenchmarkAnalysisTelemetryManifest manifest,
                                                    ObjectMapper objectMapper) throws Exception {
        return writeArtifact(outputDirectory, resultId, manifest, objectMapper).getArtifact();
    }

    public static WriteResult writeArtifact(Path outputDirectory,
                                            String resultId,
                                            BenchmarkAnalysisTelemetryManifest manifest,
                                            ObjectMapper objectMapper) throws Exception {
        BenchmarkTelemetryValidator.requireValid(manifest);
        if (outputDirectory == null || objectMapper == null) {
            throw new IllegalArgumentException("BENCHMARK_TELEMETRY_WRITER_INPUT_REQUIRED");
        }
        if (resultId == null || !resultId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("BENCHMARK_TELEMETRY_RESULT_ID_INVALID");
        }
        Path normalizedOutput = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutput);
        String fileName = "benchmark-telemetry-" + resultId + ".json";
        Path target = normalizedOutput.resolve(fileName).normalize();
        Path lockTarget = normalizedOutput.resolve(fileName + ".lock").normalize();
        if (target.getParent() == null || !target.getParent().equals(normalizedOutput)
                || lockTarget.getParent() == null || !lockTarget.getParent().equals(normalizedOutput)) {
            throw new IllegalArgumentException("BENCHMARK_TELEMETRY_PATH_INVALID");
        }
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        byte[] content = objectMapper.writer(prettyPrinter).writeValueAsString(manifest)
                .concat("\n").getBytes(StandardCharsets.UTF_8);
        Object jvmLock = JVM_LOCKS.computeIfAbsent(
                lockTarget.toString(), ignored -> new Object());
        synchronized (jvmLock) {
            try (FileChannel lockChannel = FileChannel.open(
                    lockTarget, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                return writeLocked(normalizedOutput, target, fileName, content);
            }
        }
    }

    private static WriteResult writeLocked(Path outputDirectory,
                                           Path target,
                                           String fileName,
                                           byte[] content) throws Exception {
        if (Files.exists(target)) {
            if (!Arrays.equals(content, Files.readAllBytes(target))) {
                throw new IllegalStateException("BENCHMARK_TELEMETRY_ARTIFACT_CONFLICT");
            }
            return new WriteResult(target, artifact(fileName, content), false);
        }
        Path temporary = Files.createTempFile(outputDirectory, fileName + ".", ".tmp").normalize();
        if (temporary.getParent() == null || !temporary.getParent().equals(outputDirectory)) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("BENCHMARK_TELEMETRY_TEMP_PATH_INVALID");
        }
        boolean targetCreated = false;
        try {
            Files.write(temporary, content,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                targetCreated = true;
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IllegalStateException("BENCHMARK_TELEMETRY_ATOMIC_MOVE_UNSUPPORTED", ex);
            }
            if (!Arrays.equals(content, Files.readAllBytes(target))) {
                throw new IllegalStateException("BENCHMARK_TELEMETRY_ARTIFACT_VERIFICATION_FAILED");
            }
        } catch (Exception ex) {
            Files.deleteIfExists(temporary);
            if (targetCreated) {
                Files.deleteIfExists(target);
            }
            throw ex;
        }
        return new WriteResult(target, artifact(fileName, content), true);
    }

    private static BenchmarkTelemetryArtifact artifact(String fileName, byte[] content)
            throws Exception {
        BenchmarkTelemetryArtifact artifact = new BenchmarkTelemetryArtifact();
        artifact.setSchemaVersion(BenchmarkAnalysisTelemetryManifest.SCHEMA_VERSION);
        artifact.setLogicalFileName(fileName);
        artifact.setSha256(sha256(content));
        artifact.setStatus("COMPLETED");
        return artifact;
    }

    private static String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] value = digest.digest(content);
        StringBuilder text = new StringBuilder();
        for (byte item : value) text.append(String.format("%02x", item));
        return text.toString();
    }

    public static final class WriteResult {
        private final Path path;
        private final BenchmarkTelemetryArtifact artifact;
        private final boolean created;

        private WriteResult(Path path, BenchmarkTelemetryArtifact artifact, boolean created) {
            this.path = path;
            this.artifact = artifact;
            this.created = created;
        }

        public Path getPath() { return path; }
        public BenchmarkTelemetryArtifact getArtifact() { return artifact; }
        public boolean isCreated() { return created; }
    }
}
