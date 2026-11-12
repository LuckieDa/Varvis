package org.nbscn.varvis.variantanalysis.benchmark.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkCaseResultManifest;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatabaseAttestation;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkFailureCode;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestCodec;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestWriter;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestSchema;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkResultBinding;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkRunGroup;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkRunManifest;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkStageRetention;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthMatch;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkAnalysisTelemetryManifest;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryValidator;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryWriter;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.persistence.PersistedRankingCandidate;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisRankReader;
import org.nbscn.varvis.variantanalysis.persistence.VariantRankLookup;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;
import org.nbscn.varvis.variantanalysis.ranking.RankingContract;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisService;
import org.springframework.lang.NonNull;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Executes one public benchmark case/group through the production analysis and persisted rank reader. */
public class BenchmarkRealCaseExecutor {

    private static final ConcurrentMap<String, Object> ARTIFACT_COMMIT_LOCKS =
            new ConcurrentHashMap<String, Object>();

    private final ObjectMapper objectMapper;
    private final VariantAnalysisService analysisService;
    private final VariantAnalysisJobRepository repository;
    private final DataSource dataSource;
    private final Clock clock;

    public BenchmarkRealCaseExecutor(ObjectMapper objectMapper,
                                     VariantAnalysisService analysisService,
                                     VariantAnalysisJobRepository repository,
                                     DataSource dataSource,
                                     Clock clock) {
        this.objectMapper = objectMapper;
        this.analysisService = analysisService;
        this.repository = repository;
        this.dataSource = dataSource;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Path execute(Path requestFile) throws Exception {
        BenchmarkRealCaseRequest request = objectMapper.readValue(
                requestFile.toFile(), BenchmarkRealCaseRequest.class);
        request.validate();
        requireRunManifest(request);
        requireDedicatedCatalog(request.getExpectedDatabaseCatalog());
        Path input = Paths.get(request.getInputVcf()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("INPUT_VCF_MISSING");
        }
        if (!request.getInputVcfSha256().equals(sha256(input))) {
            throw new IllegalArgumentException("INPUT_VCF_CHECKSUM_MISMATCH");
        }
        Path output = Paths.get(request.getOutputDirectory()).toAbsolutePath().normalize();
        Path acceptedResult = output.resolve(
                "case-result-" + request.getResultId() + ".json").normalize();
        if (acceptedResult.getParent() == null || !acceptedResult.getParent().equals(output)) {
            throw new IllegalArgumentException("BENCHMARK_CASE_RESULT_PATH_INVALID");
        }
        if (Files.exists(acceptedResult)) {
            throw new IllegalStateException("BENCHMARK_CASE_RESULT_ALREADY_EXISTS");
        }
        Files.createDirectories(output);
        Path commitLockPath = output.resolve(
                "case-result-" + request.getResultId() + ".commit.lock").normalize();
        if (commitLockPath.getParent() == null || !commitLockPath.getParent().equals(output)) {
            throw new IllegalArgumentException("BENCHMARK_ARTIFACT_COMMIT_LOCK_PATH_INVALID");
        }
        Object jvmCommitLock = ARTIFACT_COMMIT_LOCKS.computeIfAbsent(
                commitLockPath.toString(), ignored -> new Object());
        synchronized (jvmCommitLock) {
            try (FileChannel lockChannel = FileChannel.open(
                    commitLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                if (Files.exists(acceptedResult)) {
                    throw new IllegalStateException("BENCHMARK_CASE_RESULT_ALREADY_EXISTS");
                }
                requireNoIncompleteArtifactSet(output, request.getResultId());
                return executeLocked(request, input, output, acceptedResult);
            }
        }
    }

    private Path executeLocked(BenchmarkRealCaseRequest request,
                               Path input,
                               Path output,
                               Path acceptedResult) throws Exception {
        MultipartFile upload = new PathMultipartFile(input);
        List<NormalizedVariantKey> truthKeys = new ArrayList<NormalizedVariantKey>();
        for (BenchmarkRealCaseRequest.TruthVariant truth : request.getTruthVariants()) {
            truthKeys.add(truth.getCanonicalIdentity());
        }
        BenchmarkTelemetryCollector telemetry = new BenchmarkTelemetryCollector(
                request.getAttemptId(), request.getRequestFingerprint(),
                request.getConfigurationFingerprint(), truthKeys);
        VariantAnalysisJob submitted = analysisService.submitBenchmark(
                upload,
                request.getAssembly(),
                request.getAnalysisMode(),
                request.getSampleId(),
                request.getHpoTerms(),
                request.getHpoProvenance(),
                telemetry
        );
        VariantAnalysisJob terminal = awaitTerminal(submitted.getId(), request.getTimeoutSeconds());
        if (terminal.getStatus() != VariantAnalysisStatus.READY) {
            throw new IllegalStateException("ANALYSIS_FAILED: " + safeMessage(terminal.getErrorMessage()));
        }
        BenchmarkCaseResultManifest result = buildResult(request, terminal.getId(),
                new VariantAnalysisRankReader(repository));
        BenchmarkDatabaseAttestation attestation = databaseAttestation(request, terminal.getId(), result);
        result.setDatabaseAttestation(attestation);
        telemetry.observeRanking(result.getTruthMatches(), attestation.getRankedInterpretationCount());
        BenchmarkAnalysisTelemetryManifest telemetryManifest = telemetry.snapshot(
                terminal.getId(), terminal.getUpdatedAt());
        BenchmarkTelemetryValidator.requireValid(telemetryManifest);
        return commitArtifacts(output, acceptedResult, request, result, telemetryManifest);
    }

    void requireNoIncompleteArtifactSet(Path output, String resultId) throws IOException {
        Path[] dataArtifacts = {
                output.resolve("benchmark-telemetry-" + resultId + ".json")
        };
        for (Path artifact : dataArtifacts) {
            if (Files.exists(artifact)) {
                throw new IllegalStateException(
                        "BENCHMARK_INCOMPLETE_ARTIFACT_SET_EXISTS_USE_NEW_RESULT_ID");
            }
        }
        String[] temporaryPrefixes = {
                "benchmark-telemetry-" + resultId + ".json.",
                "case-result-" + resultId + ".json."
        };
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(output)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".tmp")) {
                    continue;
                }
                for (String prefix : temporaryPrefixes) {
                    if (name.startsWith(prefix)) {
                        throw new IllegalStateException(
                                "BENCHMARK_INCOMPLETE_ARTIFACT_SET_EXISTS_USE_NEW_RESULT_ID");
                    }
                }
            }
        }
    }

    private Path commitArtifacts(
            Path output,
            Path acceptedResult,
            BenchmarkRealCaseRequest request,
            BenchmarkCaseResultManifest result,
            BenchmarkAnalysisTelemetryManifest telemetryManifest) throws Exception {
        BenchmarkTelemetryWriter.WriteResult telemetryWrite = null;
        try {
            telemetryWrite = BenchmarkTelemetryWriter.writeArtifact(
                    output, request.getResultId(), telemetryManifest, objectMapper);
            result.setTelemetryArtifact(telemetryWrite.getArtifact());
            return BenchmarkManifestWriter.writeCaseResult(
                    output, result, new BenchmarkManifestCodec(objectMapper));
        } catch (Exception failure) {
            if (!Files.isRegularFile(acceptedResult)) {
                if (telemetryWrite != null && telemetryWrite.isCreated()) {
                    cleanupGeneratedArtifact(telemetryWrite.getPath(), failure);
                }
            }
            throw failure;
        }
    }

    private boolean cleanupGeneratedArtifact(Path path, Exception originalFailure) {
        try {
            Files.deleteIfExists(path);
            return !Files.exists(path);
        } catch (IOException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            return false;
        }
    }

    private void requireRunManifest(BenchmarkRealCaseRequest request) throws IOException {
        BenchmarkRunManifest run = new BenchmarkManifestCodec(objectMapper).readRun(
                Paths.get(request.getRunManifest()).toAbsolutePath().normalize());
        if (!request.getRunId().equals(run.getRunId())
                || !request.getDatasetId().equals(run.getDatasetId())
                || !request.getDatasetVersion().equals(run.getDatasetVersion())) {
            throw new IllegalArgumentException("RUN_MANIFEST_IDENTITY_MISMATCH");
        }
        if (!BenchmarkManifestSchema.RUN_V2.equals(run.getSchemaVersion())
                || run.getExecutionProvenance() == null) {
            throw new IllegalArgumentException("RUN_MANIFEST_PROVENANCE_REQUIRED");
        }
        String provenanceFingerprint = BenchmarkRequestFingerprint.executionProvenance(
                run.getExecutionProvenance());
        if (!request.getExecutionProvenanceFingerprint().equals(provenanceFingerprint)
                || !request.getConfigurationFingerprint().equals(
                run.getSharedConfigurationFingerprint())) {
            throw new IllegalArgumentException("RUN_MANIFEST_REQUEST_BINDING_MISMATCH");
        }
        int matches = 0;
        for (BenchmarkRunGroup group : run.getGroups()) {
            if (group != null && request.getCaseId().equals(group.getCaseId())
                    && request.getGroupId().equals(group.getGroupId())
                    && safeList(request.getHpoTerms()).equals(safeList(group.getHpoTerms()))) {
                matches++;
            }
        }
        if (matches != 1) {
            throw new IllegalArgumentException("RUN_MANIFEST_GROUP_MISMATCH");
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? Collections.<String>emptyList() : values;
    }

    BenchmarkCaseResultManifest buildResult(BenchmarkRealCaseRequest request,
                                             String analysisId,
                                             VariantAnalysisRankReader rankReader) {
        BenchmarkCaseResultManifest result = new BenchmarkCaseResultManifest();
        result.setSchemaVersion(BenchmarkManifestSchema.CASE_RESULT_V2);
        result.setResultId(request.getResultId());
        result.setCreatedAt(Instant.now(clock).toString());
        result.setRunId(request.getRunId());
        result.setDatasetId(request.getDatasetId());
        result.setCaseId(request.getCaseId());
        result.setGroupId(request.getGroupId());
        result.setAnalysisReference(analysisId);
        result.setResultBinding(resultBinding(request));

        List<BenchmarkTruthMatch> matches = new ArrayList<BenchmarkTruthMatch>();
        Map<String, String> providerStatuses = new LinkedHashMap<String, String>();
        boolean nonRanked = false;
        boolean belowFrozenHitCutoff = false;
        for (BenchmarkRealCaseRequest.TruthVariant truth : request.getTruthVariants()) {
            VariantRankLookup lookup = rankReader.find(analysisId, truth.getCanonicalIdentity());
            BenchmarkTruthMatch match = new BenchmarkTruthMatch();
            match.setTruthGene(truth.getGene());
            match.setTruthCanonicalIdentity(truth.getCanonicalIdentity());
            match.setAssembly(request.getAssembly());
            match.setRankingContractVersion(RankingContract.VERSION);
            match.setRankStatus(lookup.getStatus());
            match.setRank(lookup.getRank());
            match.setRankingUniverseSize(lookup.getRankingUniverseSize());
            PersistedRankingCandidate candidate = lookup.getCandidate();
            match.setMatchedCanonicalIdentity(candidate == null ? null : candidate.getKey());
            if (candidate != null) {
                java.util.Optional<VariantInterpretation> interpretation = candidate.getCandidateId() == null
                        ? repository.findInterpretation(analysisId, candidate.getVariantId())
                        : repository.findCandidate(analysisId, candidate.getCandidateId());
                if (!interpretation.isPresent()) {
                    throw new IllegalStateException("PERSISTED_INTERPRETATION_MISSING");
                }
                mergeProviderStatuses(providerStatuses, interpretation.get().getEvidence());
            }
            match.setAmbiguityStatus(lookup.getStatus() == RankStatus.RANK_UNAVAILABLE
                    ? "UNAVAILABLE" : "NONE");
            if (lookup.getStatus() == RankStatus.RANKED) {
                match.setMatchStatus("MATCHED");
                belowFrozenHitCutoff |= lookup.getRank() != null && lookup.getRank().intValue() > 50;
            } else if (lookup.getStatus() == RankStatus.NOT_IN_RANKING_UNIVERSE) {
                match.setMatchStatus("NOT_IN_RANKING_UNIVERSE");
                nonRanked = true;
            } else {
                match.setMatchStatus("RANK_UNAVAILABLE");
                nonRanked = true;
            }
            matches.add(match);
        }
        result.setTruthMatches(matches);
        result.setStageRetention(stageRetention(matches));
        result.setProviderStatuses(providerStatuses);
        if (nonRanked) {
            result.setCaseExecutionStatus("COMPLETED_WITH_FAILURES");
            result.setFailureNote("Truth was validated in the spiked input but no complete persisted rank was available; the available retained-stage evidence is insufficient to assign a frozen F0-F11 code.");
        } else if (belowFrozenHitCutoff) {
            result.setCaseExecutionStatus("COMPLETED_WITH_FAILURES");
            result.setPrimaryFailureCode(BenchmarkFailureCode.F9);
            result.setFailureNote("Truth is retained and ranked below the frozen Recall@50 smoke cutoff.");
        } else {
            result.setCaseExecutionStatus("COMPLETED");
        }
        result.setSecondaryFailureCodes(Collections.<String>emptyList());
        return result;
    }

    private BenchmarkResultBinding resultBinding(BenchmarkRealCaseRequest request) {
        BenchmarkResultBinding binding = new BenchmarkResultBinding();
        binding.setAttemptId(request.getAttemptId());
        binding.setRequestFingerprint(request.getRequestFingerprint());
        binding.setDatasetVersion(request.getDatasetVersion());
        binding.setPhenopacketSha256(request.getPhenopacketSha256());
        binding.setInputVcfSha256(request.getInputVcfSha256());
        binding.setTruthSetFingerprint(request.getTruthSetFingerprint());
        binding.setExecutionProvenanceFingerprint(request.getExecutionProvenanceFingerprint());
        binding.setConfigurationFingerprint(request.getConfigurationFingerprint());
        return binding;
    }

    private BenchmarkDatabaseAttestation databaseAttestation(BenchmarkRealCaseRequest request,
                                                              String analysisId,
                                                              BenchmarkCaseResultManifest result) {
        VariantAnalysisJob persisted = repository.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("DATABASE_JOB_MISSING"));
        if (persisted.getStatus() != VariantAnalysisStatus.READY) {
            throw new IllegalStateException("DATABASE_JOB_NOT_READY");
        }
        int persistedCount = repository.countInterpretations(analysisId);
        int rankedCount = persistedCount;
        for (BenchmarkTruthMatch match : result.getTruthMatches()) {
            if (match.getRankingUniverseSize() != null
                    && match.getRankingUniverseSize().intValue() != persistedCount) {
                throw new IllegalStateException("DATABASE_RANK_COUNT_MISMATCH");
            }
        }
        BenchmarkDatabaseAttestation attestation = new BenchmarkDatabaseAttestation();
        attestation.setAnalysisReference(analysisId);
        attestation.setJobStatus(persisted.getStatus().name());
        attestation.setPersistedInterpretationCount(persistedCount);
        attestation.setRankedInterpretationCount(rankedCount);
        attestation.setVerifiedAt(Instant.now(clock).toString());
        attestation.setAttestationFingerprint(BenchmarkRequestFingerprint.databaseAttestation(
                request.getRequestFingerprint(), analysisId, persisted.getStatus().name(),
                persistedCount, rankedCount));
        return attestation;
    }

    private void mergeProviderStatuses(Map<String, String> statuses, List<EvidenceItem> evidence) {
        if (evidence == null) {
            return;
        }
        for (EvidenceItem item : evidence) {
            if (item == null || item.getSource() == null) {
                continue;
            }
            String source = item.getSource().trim().toUpperCase(Locale.ROOT);
            if (!source.equals("CLINVAR") && !source.equals("GNOMAD")
                    && !source.equals("CADD") && !source.equals("SPLICEAI")) {
                continue;
            }
            String value = item.getStatus();
            if (value == null || value.trim().isEmpty()) {
                value = item.getApplicability();
            }
            value = value == null || value.trim().isEmpty() ? "UNDETERMINED" : value.trim();
            String existing = statuses.get(source);
            if (existing == null) {
                statuses.put(source, value);
            } else if (!existing.equals(value)) {
                statuses.put(source, "MIXED");
            }
        }
    }

    private List<BenchmarkStageRetention> stageRetention(List<BenchmarkTruthMatch> matches) {
        List<BenchmarkStageRetention> stages = new ArrayList<BenchmarkStageRetention>();
        stages.add(stage("SPIKED_INPUT", "PRESENT", matches.size(),
                "Exact truth presence was validated before Varvis submission."));
        boolean allRanked = true;
        boolean unavailable = false;
        int universe = 0;
        for (BenchmarkTruthMatch match : matches) {
            allRanked &= match.getRankStatus() == RankStatus.RANKED;
            unavailable |= match.getRankStatus() == RankStatus.RANK_UNAVAILABLE;
            if (match.getRankingUniverseSize() != null) {
                universe = Math.max(universe, match.getRankingUniverseSize());
            }
        }
        stages.add(stage("PERSISTED_RANKING_UNIVERSE",
                allRanked ? "PRESENT" : unavailable ? "UNAVAILABLE" : "ABSENT",
                universe,
                "Rank comes from the complete persisted ranking universe."));
        return stages;
    }

    private BenchmarkStageRetention stage(String name, String presence, int count, String note) {
        BenchmarkStageRetention stage = new BenchmarkStageRetention();
        stage.setStage(name);
        stage.setPresence(presence);
        stage.setObservedCount(count);
        stage.setNote(note);
        return stage;
    }

    private VariantAnalysisJob awaitTerminal(String analysisId, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            VariantAnalysisJob job = analysisService.get(analysisId);
            if (job.getStatus() == VariantAnalysisStatus.READY
                    || job.getStatus() == VariantAnalysisStatus.FAILED) {
                return job;
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("ANALYSIS_TIMEOUT");
    }

    private void requireDedicatedCatalog(String expected) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String actual = connection.getCatalog();
            if (actual == null || !actual.equalsIgnoreCase(expected)
                    || !actual.toLowerCase(Locale.ROOT).contains("benchmark")) {
                throw new IllegalStateException("ENVIRONMENT_ISOLATION_UNAVAILABLE");
            }
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format("%02x", item));
        return value.toString();
    }

    private String safeMessage(String message) {
        return message == null || message.trim().isEmpty() ? "UNAVAILABLE" : message.trim();
    }

    private static final class PathMultipartFile implements MultipartFile {
        private final Path path;

        private PathMultipartFile(Path path) { this.path = path; }
        @Override @NonNull public String getName() { return "vcf"; }
        @Override public String getOriginalFilename() { return path.getFileName().toString(); }
        @Override public String getContentType() { return "application/gzip"; }
        @Override public boolean isEmpty() { return size() == 0L; }
        @Override public long getSize() { return size(); }
        private long size() { try { return Files.size(path); } catch (IOException ex) { return 0L; } }
        @Override @NonNull public byte[] getBytes() throws IOException {
            return Objects.requireNonNull(Files.readAllBytes(path));
        }
        @Override @NonNull public InputStream getInputStream() throws IOException {
            return Objects.requireNonNull(Files.newInputStream(path));
        }
        @Override public void transferTo(@NonNull java.io.File destination) throws IOException {
            Files.copy(path, destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
