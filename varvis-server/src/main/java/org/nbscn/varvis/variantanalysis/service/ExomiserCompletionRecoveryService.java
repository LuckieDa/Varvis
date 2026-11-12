package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ArtifactKind;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.RawScanResult;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ScanException;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ScanResult;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finishes only proven Exomiser post-processing checkpoints after an
 * application interruption. Recovery is intentionally stricter than a normal
 * engine parse: it requires the persisted terminal EXOMISER/RANKED attestation,
 * an exact variants digest match, and a complete stable variants/genes/JSONL
 * companion set. It never invokes Exomiser, rewrites engine provenance, or re-ranks.
 */
@Service
public class ExomiserCompletionRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExomiserCompletionRecoveryService.class);
    private static final String INCOMPLETE_CODE = "EXOMISER_PERSISTENCE_INCOMPLETE";
    private static final String RECOVERY_FAILED_CODE = "EXOMISER_RECOVERY_FAILED";
    private static final String ROW_COUNT_MISMATCH_CODE = "EXOMISER_RECOVERY_ROW_COUNT_MISMATCH";
    private static final String OUTPUT_SET_INCOMPLETE_CODE = "EXOMISER_RECOVERY_OUTPUT_SET_INCOMPLETE";
    private static final String ATTESTATION_INVALID_CODE = "EXOMISER_RECOVERY_ENGINE_ATTESTATION_INVALID";
    private static final String OUTPUT_SHA_MISMATCH_CODE = "EXOMISER_RECOVERY_OUTPUT_SHA_MISMATCH";
    private static final String TSV_FAILURE_PREFIX = "EXOMISER_TSV_";
    private static final String RAW_FAILURE_PREFIX = "EXOMISER_RAW_";
    private static final long JSONL_MAX_BYTES = 128L * 1024L * 1024L;
    private static final String RESTART_INTERRUPTION_MESSAGE = "Application restarted before analysis completed";
    private static final Pattern PARTIAL_LIFTOVER_EVENT = Pattern.compile(
            "PARTIAL_LIFTOVER:\\s*source=(\\d+),\\s*lifted=(\\d+),\\s*rejected=(\\d+)"
    );
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final VariantAnalysisJobRepository repository;
    private final ExomiserTsvScanner tsvScanner;

    @Autowired
    public ExomiserCompletionRecoveryService(VariantAnalysisJobRepository repository,
                                              VariantAnalysisProperties properties) {
        this(repository, properties, new ExomiserTsvScanner());
    }

    ExomiserCompletionRecoveryService(VariantAnalysisJobRepository repository,
                                      VariantAnalysisProperties properties,
                                      ExomiserTsvScanner tsvScanner) {
        this.repository = Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(properties, "properties");
        this.tsvScanner = Objects.requireNonNull(tsvScanner, "tsvScanner");
    }

    public int recoverInterruptedExomiserFinalization() {
        int recovered = 0;
        for (VariantAnalysisJob job : repository.findAll()) {
            if (recover(job)) {
                recovered++;
            }
        }
        return recovered;
    }

    boolean recover(VariantAnalysisJob job) {
        if (!isRecoverableStatus(job)) {
            return false;
        }
        try {
            Path output = completedOutput(job);
            if (output == null) {
                return false;
            }
            EngineMetadata checkpoint = job.getEngineMetadata();
            if (!validTerminalAttestation(checkpoint)) {
                closeIncomplete(job, ATTESTATION_INVALID_CODE);
                return false;
            }
            Path outputDirectory = output.getParent();
            Path genes = outputDirectory.resolve("result.genes.tsv");
            Path jsonl = outputDirectory.resolve("result.jsonl");
            ScanResult scan;
            try {
                scan = tsvScanner.inspect(output, ArtifactKind.VARIANTS);
            } catch (ScanException exception) {
                closeIncomplete(job, stableTsvFailureCode(exception));
                return false;
            }
            if (!attestedHashMatches(checkpoint.getOutputSha256(), scan.rawSha256())) {
                closeIncomplete(job, OUTPUT_SHA_MISMATCH_CODE);
                return false;
            }
            ScanResult geneScan;
            try {
                geneScan = tsvScanner.inspect(genes, ArtifactKind.GENES);
            } catch (ScanException exception) {
                closeIncomplete(job, companionTsvFailureCode(exception));
                return false;
            }
            RawScanResult jsonlScan;
            try {
                jsonlScan = tsvScanner.inspectRaw(jsonl, JSONL_MAX_BYTES);
            } catch (ScanException exception) {
                closeIncomplete(job, companionRawFailureCode(exception));
                return false;
            }
            if (jsonlScan.byteCount() == 0L) {
                closeIncomplete(job, OUTPUT_SET_INCOMPLETE_CODE);
                return false;
            }
            try {
                tsvScanner.verifyUnchanged(output, scan);
                tsvScanner.verifyUnchanged(genes, geneScan);
            } catch (ScanException exception) {
                closeIncomplete(job, stableTsvFailureCode(exception));
                return false;
            }
            try {
                tsvScanner.verifyRawUnchanged(jsonl, jsonlScan);
            } catch (ScanException exception) {
                closeIncomplete(job, stableFailureCode(RAW_FAILURE_PREFIX, exception));
                return false;
            }
            if (!safeDirectory(outputDirectory, noFollowAttributes(outputDirectory))) {
                closeIncomplete(job, OUTPUT_SET_INCOMPLETE_CODE);
                return false;
            }
            int expectedRows = Math.toIntExact(scan.dataRowCount());
            int persistedRows = repository.countInterpretations(job.getId());
            if (expectedRows <= 0 || persistedRows != expectedRows) {
                closeIncomplete(job, ROW_COUNT_MISMATCH_CODE);
                return false;
            }
            LiftoverRecovery liftover = liftoverRecovery(job);
            job.recoverCompletedPersistence(liftover.applied, liftover.engineLabel);
            repository.save(job);
            repository.appendEvent(
                    job.getId(),
                    VariantAnalysisStatus.READY,
                    "EXOMISER_PERSISTENCE_RECOVERED: output and persisted candidate counts match; Exomiser was not re-run"
            );
            LOGGER.info("Recovered completed Exomiser persistence checkpoint for job {} with {} rows", job.getId(), persistedRows);
            return true;
        } catch (IOException | RuntimeException exception) {
            closeIncomplete(job, RECOVERY_FAILED_CODE);
            return false;
        }
    }

    private boolean isRecoverableStatus(VariantAnalysisJob job) {
        if (job == null) {
            return false;
        }
        if (job.getStatus() == VariantAnalysisStatus.RANKING) {
            return true;
        }
        return job.getStatus() == VariantAnalysisStatus.FAILED
                && RESTART_INTERRUPTION_MESSAGE.equals(job.getErrorMessage());
    }

    private LiftoverRecovery liftoverRecovery(VariantAnalysisJob job) {
        String existing = job.getNormalizationEngine();
        if (existing != null && !existing.trim().isEmpty()) {
            return new LiftoverRecovery(job.isNormalizationApplied(), existing);
        }
        List<VariantAnalysisEvent> events = repository.findEvents(job.getId());
        if (events == null) {
            return new LiftoverRecovery(false, null);
        }
        for (VariantAnalysisEvent event : events) {
            if (event == null || event.getMessage() == null) {
                continue;
            }
            Matcher partial = PARTIAL_LIFTOVER_EVENT.matcher(event.getMessage());
            if (partial.find()) {
                String source = partial.group(1);
                String lifted = partial.group(2);
                String rejected = partial.group(3);
                return new LiftoverRecovery(true, "PICARD;PARTIAL_LIFTOVER:" + rejected
                        + ";SOURCE=" + source + ";LIFTED=" + lifted);
            }
        }
        return new LiftoverRecovery(false, null);
    }

    private void closeIncomplete(VariantAnalysisJob job, String detail) {
        job.fail(INCOMPLETE_CODE + ":" + detail);
        try {
            repository.save(job);
            repository.appendEvent(job.getId(), VariantAnalysisStatus.FAILED, job.getErrorMessage());
        } catch (RuntimeException persistFailure) {
            LOGGER.warn("Could not close incomplete Exomiser job {}", job.getId());
        }
    }

    private Path completedOutput(VariantAnalysisJob job) throws IOException {
        Path workspace = job.getWorkspace();
        if (workspace == null) {
            return null;
        }
        BasicFileAttributes workspaceAttributes;
        try {
            workspaceAttributes = noFollowAttributes(workspace);
        } catch (NoSuchFileException exception) {
            return null;
        }
        if (!safeDirectory(workspace, workspaceAttributes)) {
            throw new IOException();
        }
        List<Path> checkpointDirectories;
        try (Stream<Path> paths = Files.list(workspace)) {
            checkpointDirectories = paths
                    .filter(path -> path.getFileName().toString().startsWith("exomiser-"))
                    .collect(Collectors.toList());
        }
        List<Path> completeOutputs = new ArrayList<>();
        List<Path> incompleteOutputs = new ArrayList<>();
        for (Path checkpoint : checkpointDirectories) {
            BasicFileAttributes attributes = noFollowAttributes(checkpoint);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || isDosReparsePoint(checkpoint)) {
                throw new IOException();
            }
            if (attributes.isDirectory()) {
                Path output = checkpoint.resolve("output");
                BasicFileAttributes outputAttributes;
                try {
                    outputAttributes = noFollowAttributes(output);
                } catch (NoSuchFileException exception) {
                    continue;
                }
                if (!safeDirectory(output, outputAttributes)) {
                    throw new IOException();
                }
                Path variants = output.resolve("result.variants.tsv");
                if (preliminaryCompleteOutputSet(output)) {
                    completeOutputs.add(variants);
                } else {
                    incompleteOutputs.add(variants);
                }
            }
        }
        if (completeOutputs.size() == 1) {
            return completeOutputs.get(0);
        }
        return completeOutputs.isEmpty() && incompleteOutputs.size() == 1
                ? incompleteOutputs.get(0)
                : null;
    }

    /** Selection prefilter only; the scanner performs the authoritative bounded verification. */
    private boolean preliminaryCompleteOutputSet(Path output) throws IOException {
        return preliminaryRegularNonEmpty(output.resolve("result.variants.tsv"))
                && preliminaryRegularNonEmpty(output.resolve("result.genes.tsv"))
                && preliminaryRegularNonEmpty(output.resolve("result.jsonl"));
    }

    private boolean preliminaryRegularNonEmpty(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = noFollowAttributes(path);
            return attributes.isRegularFile()
                    && !attributes.isSymbolicLink()
                    && !attributes.isOther()
                    && !isDosReparsePoint(path)
                    && attributes.size() > 0L;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private boolean safeDirectory(Path path, BasicFileAttributes attributes) throws IOException {
        return attributes.isDirectory()
                && !attributes.isSymbolicLink()
                && !attributes.isOther()
                && !isDosReparsePoint(path);
    }

    private BasicFileAttributes noFollowAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isDosReparsePoint(Path path) throws IOException {
        DosFileAttributeView view = Files.getFileAttributeView(
                path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return false;
        }
        final Object rawAttributes;
        try {
            rawAttributes = Files.getAttribute(
                    path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return false;
        }
        if (!(rawAttributes instanceof Number)) {
            throw new IOException();
        }
        return ((((Number) rawAttributes).intValue()) & 0x400) != 0;
    }

    private String stableTsvFailureCode(ScanException exception) {
        return stableFailureCode(TSV_FAILURE_PREFIX, exception);
    }

    private String companionTsvFailureCode(ScanException exception) {
        return isMissingOrInvalid(exception)
                ? OUTPUT_SET_INCOMPLETE_CODE
                : stableTsvFailureCode(exception);
    }

    private String companionRawFailureCode(ScanException exception) {
        return isMissingOrInvalid(exception)
                ? OUTPUT_SET_INCOMPLETE_CODE
                : stableFailureCode(RAW_FAILURE_PREFIX, exception);
    }

    private boolean isMissingOrInvalid(ScanException exception) {
        return exception.code() == ExomiserTsvScanner.Code.FILE_MISSING
                || exception.code() == ExomiserTsvScanner.Code.FILE_INVALID;
    }

    private String stableFailureCode(String prefix, ScanException exception) {
        String scannerCode = exception.code().name();
        String normalizedCode = scannerCode.startsWith("TSV_")
                ? scannerCode.substring("TSV_".length())
                : scannerCode;
        return prefix + normalizedCode;
    }

    private boolean validTerminalAttestation(EngineMetadata metadata) {
        return metadata != null
                && metadata.getEngine() == AnalysisEngine.EXOMISER
                && metadata.getStatus() == EngineExecutionStatus.RANKED
                && !metadata.isFallbackUsed()
                && !nonblank(metadata.getUnavailableReason())
                && nonblank(metadata.getEngineVersion())
                && ("EXOMISER_G0".equals(metadata.getRankingMode())
                || "EXOMISER_G1".equals(metadata.getRankingMode()))
                && nonblank(metadata.getResourceFingerprint())
                && SHA256.matcher(metadata.getOutputSha256()).matches();
    }

    private boolean attestedHashMatches(String attested, String observed) {
        return MessageDigest.isEqual(
                attested.getBytes(StandardCharsets.US_ASCII),
                observed.getBytes(StandardCharsets.US_ASCII));
    }

    private boolean nonblank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class LiftoverRecovery {

        private final boolean applied;
        private final String engineLabel;

        private LiftoverRecovery(boolean applied, String engineLabel) {
            this.applied = applied;
            this.engineLabel = engineLabel;
        }
    }
}
