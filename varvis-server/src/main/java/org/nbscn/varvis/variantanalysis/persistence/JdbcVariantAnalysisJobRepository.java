package org.nbscn.varvis.variantanalysis.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.nbscn.varvis.exception.VariantAnalysisPersistenceException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
public class JdbcVariantAnalysisJobRepository implements VariantAnalysisJobRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcVariantAnalysisJobRepository.class);
    private static final String PHENOTYPE_SUMMARY_SCHEMA_VERSION = "1";
    private static final int COMPACT_TEXT_LIMIT = 512;
    private static final int TRANSCRIPT_ANNOTATION_UPDATE_BATCH_LIMIT = 50;
    private static final String INSERT_OBSERVATION_SQL =
            "INSERT INTO variant_analysis_observation ("
                    + "analysis_id, variant_order, genome_assembly, chromosome, variant_position, "
                    + "reference_allele, alternate_allele, sample_id, genotype, depth, genotype_quality, "
                    + "allele_depths, allele_balance, filter_status, variant_type, original_record"
                    + ") VALUES ("
                    + ":analysisId, :variantOrder, :genomeAssembly, :chromosome, :variantPosition, "
                    + ":referenceAllele, :alternateAllele, :sampleId, :genotype, :depth, :genotypeQuality, "
                    + ":alleleDepths, :alleleBalance, :filterStatus, :variantType, :originalRecord"
                    + ")";
    private static final String INSERT_INTERPRETATION_SQL =
            "INSERT INTO variant_analysis_interpretation ("
                    + "analysis_id, variant_order, candidate_id, canonical_variant_id, variant_id, "
                    + "priority_tier, ranking_score, "
                    + "genome_assembly, chromosome, variant_position, reference_allele, alternate_allele, "
                    + "sample_id, genotype, filter_status, variant_type, consequences_json, "
                    + "cadd_score, alpha_missense_score, alpha_genome_score, carried_alt, "
                    + "analysis_bucket, summary_only, large_file_mode, expensive_annotation_skipped, "
                    + "expensive_annotation_skipped_reason, cheap_gate_reason_text, "
                    + "full_interpretation_available, payload_json"
                    + ") VALUES ("
                    + ":analysisId, :variantOrder, :candidateId, :canonicalVariantId, :variantId, "
                    + ":priorityTier, :rankingScore, "
                    + ":genomeAssembly, :chromosome, :variantPosition, :referenceAllele, :alternateAllele, "
                    + ":sampleId, :genotype, :filterStatus, :variantType, :consequencesJson, "
                    + ":caddScore, :alphaMissenseScore, :alphaGenomeScore, :carriedAlt, "
                    + ":analysisBucket, :summaryOnly, :largeFileMode, :expensiveAnnotationSkipped, "
                    + ":expensiveAnnotationSkippedReason, :cheapGateReasonText, "
                    + ":fullInterpretationAvailable, :payloadJson"
                    + ")";

    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<List<String>>() { };
    private static final TypeReference<List<TranscriptConsequence>> CONSEQUENCE_LIST_TYPE =
            new TypeReference<List<TranscriptConsequence>>() { };

    private static final String JOB_COLUMNS =
            "id, original_file_name, stored_file_name, file_sha256, genome_assembly, analysis_mode, "
                    + "requested_sample_id, selected_sample_id, status, normalization_applied, "
                    + "normalization_engine, warnings_json, error_message, sample_count, input_site_count, "
                    + "variant_count, multi_allelic_site_count, snv_count, indel_count, mnv_count, "
                    + "other_count, pass_count, filtered_count, missing_depth_count, "
                    + "missing_genotype_quality_count, phenotype_summary_schema_version, "
                    + "phenotype_summary_json, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final VariantAnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate persistenceChunkTransactionTemplate;

    public JdbcVariantAnalysisJobRepository(
            @Qualifier("varvisNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
            @Qualifier("varvisTxManager") PlatformTransactionManager transactionManager,
            VariantAnalysisProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.jdbcTemplate = new TableScopedNamedParameterJdbcTemplate(
                Objects.requireNonNull(jdbcTemplate, "jdbcTemplate"),
                new VariantAnalysisTableNames(properties.getPersistence().getTablePrefix())
        );
        this.objectMapper = objectMapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        this.persistenceChunkTransactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager")
        );
        this.persistenceChunkTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void verifyPersistenceSchema() {
        // Resolve the actual insert columns without reading candidate data or issuing DDL.
        String columns = INSERT_INTERPRETATION_SQL.substring(
                INSERT_INTERPRETATION_SQL.indexOf('(') + 1,
                INSERT_INTERPRETATION_SQL.indexOf(')'));
        try {
            jdbcTemplate.query("SELECT " + columns + " FROM variant_analysis_interpretation WHERE 1=0",
                    new MapSqlParameterSource(), (row, index) -> Boolean.TRUE);
            jdbcTemplate.query("SELECT " + JOB_COLUMNS + " FROM variant_analysis_job WHERE 1=0",
                    new MapSqlParameterSource(), (row, index) -> Boolean.TRUE);
        } catch (BadSqlGrammarException incompatible) {
            throw new VariantAnalysisPersistenceException("ANALYSIS_DATABASE_SCHEMA_INCOMPATIBLE",
                    "分析数据库表结构与当前程序不兼容，请完成授权的表结构迁移后再提交。VCF 尚未开始分析。",
                    incompatible);
        } catch (DataAccessException unavailable) {
            throw new VariantAnalysisPersistenceException("ANALYSIS_DATABASE_UNAVAILABLE",
                    "分析数据库暂不可用，请检查连接和访问权限后重试。VCF 尚未开始分析。", unavailable);
        }
    }

    @Override
    @Transactional(transactionManager = "varvisTxManager")
    public void save(VariantAnalysisJob job) {
        VariantQcSummary summary = job.getQcSummary();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", job.getId())
                .addValue("originalFileName", job.getOriginalFileName())
                .addValue("storedFileName", job.getStoredFileName())
                .addValue("fileSha256", job.getFileSha256())
                .addValue("genomeAssembly", job.getGenomeAssembly().name())
                .addValue("analysisMode", job.getAnalysisMode().name())
                .addValue("requestedSampleId", job.getRequestedSampleId())
                .addValue("selectedSampleId", job.getSelectedSampleId())
                .addValue("status", job.getStatus().name())
                .addValue("normalizationApplied", job.isNormalizationApplied())
                .addValue("normalizationEngine", job.getNormalizationEngine())
                .addValue("warningsJson", writeWarnings(job.getWarnings()))
                .addValue("errorMessage", job.getErrorMessage())
                .addValue("sampleCount", summary == null ? null : summary.getSampleCount())
                .addValue("inputSiteCount", summary == null ? null : summary.getInputSiteCount())
                .addValue("variantCount", summary == null ? null : summary.getVariantCount())
                .addValue("multiAllelicSiteCount", summary == null ? null : summary.getMultiAllelicSiteCount())
                .addValue("snvCount", summary == null ? null : summary.getSnvCount())
                .addValue("indelCount", summary == null ? null : summary.getIndelCount())
                .addValue("mnvCount", summary == null ? null : summary.getMnvCount())
                .addValue("otherCount", summary == null ? null : summary.getOtherCount())
                .addValue("passCount", summary == null ? null : summary.getPassCount())
                .addValue("filteredCount", summary == null ? null : summary.getFilteredCount())
                .addValue("missingDepthCount", summary == null ? null : summary.getMissingDepthCount())
                .addValue(
                        "missingGenotypeQualityCount",
                        summary == null ? null : summary.getMissingGenotypeQualityCount()
                )
                .addValue("phenotypeSummarySchemaVersion", phenotypeSummarySchemaVersion(job))
                .addValue("phenotypeSummaryJson", writePhenotypeSummary(job.getPhenotypeSummary()))
                .addValue("createdAt", Timestamp.from(job.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(job.getUpdatedAt()));
        jdbcTemplate.update(
                "INSERT INTO variant_analysis_job ("
                        + JOB_COLUMNS
                        + ") VALUES ("
                        + ":id, :originalFileName, :storedFileName, :fileSha256, :genomeAssembly, :analysisMode, "
                        + ":requestedSampleId, :selectedSampleId, :status, :normalizationApplied, "
                        + ":normalizationEngine, :warningsJson, :errorMessage, :sampleCount, :inputSiteCount, "
                        + ":variantCount, :multiAllelicSiteCount, :snvCount, :indelCount, :mnvCount, "
                        + ":otherCount, :passCount, :filteredCount, :missingDepthCount, "
                        + ":missingGenotypeQualityCount, :phenotypeSummarySchemaVersion, "
                        + ":phenotypeSummaryJson, :createdAt, :updatedAt"
                        + ") ON DUPLICATE KEY UPDATE "
                        + "selected_sample_id = VALUES(selected_sample_id), "
                        + "status = VALUES(status), "
                        + "normalization_applied = VALUES(normalization_applied), "
                        + "normalization_engine = VALUES(normalization_engine), "
                        + "warnings_json = VALUES(warnings_json), "
                        + "error_message = VALUES(error_message), "
                        + "sample_count = VALUES(sample_count), "
                        + "input_site_count = VALUES(input_site_count), "
                        + "variant_count = VALUES(variant_count), "
                        + "multi_allelic_site_count = VALUES(multi_allelic_site_count), "
                        + "snv_count = VALUES(snv_count), "
                        + "indel_count = VALUES(indel_count), "
                        + "mnv_count = VALUES(mnv_count), "
                        + "other_count = VALUES(other_count), "
                        + "pass_count = VALUES(pass_count), "
                        + "filtered_count = VALUES(filtered_count), "
                        + "missing_depth_count = VALUES(missing_depth_count), "
                        + "missing_genotype_quality_count = VALUES(missing_genotype_quality_count), "
                        + "phenotype_summary_schema_version = VALUES(phenotype_summary_schema_version), "
                        + "phenotype_summary_json = VALUES(phenotype_summary_json), "
                        + "updated_at = VALUES(updated_at)",
                parameters
        );
        persistExomiserMetadata(job);
    }

    @Override
    public Optional<VariantAnalysisJob> findById(String id) {
        List<VariantAnalysisJob> jobs = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM variant_analysis_job WHERE id = :id",
                Objects.requireNonNull(idParameters(id), "parameters"),
                Objects.requireNonNull(jobRowMapper(), "jobRowMapper")
        );
        return jobs.isEmpty() ? Optional.empty() : Optional.of(attachExomiserMetadata(jobs.get(0)));
    }

    @Override
    public Optional<VariantAnalysisJob> findReusableReadyJob(String fileSha256,
                                                            String genomeAssembly,
                                                            String analysisMode,
                                                            String requestedSampleId) {
        List<VariantAnalysisJob> jobs = findReusableReadyJobs(
                fileSha256,
                genomeAssembly,
                analysisMode,
                requestedSampleId
        );
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.get(0));
    }

    @Override
    public List<VariantAnalysisJob> findReusableReadyJobs(String fileSha256,
                                                          String genomeAssembly,
                                                          String analysisMode,
                                                          String requestedSampleId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("fileSha256", fileSha256)
                .addValue("genomeAssembly", genomeAssembly)
                .addValue("analysisMode", analysisMode)
                .addValue("requestedSampleId", requestedSampleId);
        List<VariantAnalysisJob> jobs = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM variant_analysis_job "
                        + "WHERE file_sha256 = :fileSha256 "
                        + "AND genome_assembly = :genomeAssembly "
                        + "AND analysis_mode = :analysisMode "
                        + "AND status = 'READY' "
                        + "AND EXISTS (SELECT 1 FROM variant_analysis_interpretation interpretation "
                        + "WHERE interpretation.analysis_id = variant_analysis_job.id LIMIT 1) "
                        + "AND ((:requestedSampleId IS NULL AND requested_sample_id IS NULL) "
                        + "OR requested_sample_id = :requestedSampleId) "
                        + "ORDER BY updated_at DESC",
                parameters,
                Objects.requireNonNull(jobRowMapper(), "jobRowMapper")
        );
        List<VariantAnalysisJob> restored = new ArrayList<VariantAnalysisJob>(jobs.size());
        for (VariantAnalysisJob job : jobs) {
            restored.add(attachExomiserMetadata(job));
        }
        return restored;
    }

    @Override
    public List<VariantAnalysisJob> findHistoryByFileSha256(String fileSha256) {
        return jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM variant_analysis_job WHERE file_sha256 = :fileSha256 "
                        + "ORDER BY created_at DESC, id DESC LIMIT 20",
                new MapSqlParameterSource("fileSha256", fileSha256),
                Objects.requireNonNull(jobRowMapper(), "jobRowMapper"));
    }

    @Override
    public List<VariantAnalysisJob> findAll() {
        return jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM variant_analysis_job ORDER BY created_at",
                new MapSqlParameterSource(),
                Objects.requireNonNull(jobRowMapper(), "jobRowMapper")
        );
    }

    @Override
    public void replaceObservations(String id, List<VariantQcObservation> observations) {
        executePersistenceChunk(
                () -> jdbcTemplate.update(
                        "DELETE FROM variant_analysis_observation WHERE analysis_id = :id",
                        Objects.requireNonNull(idParameters(id), "parameters")
                )
        );
        if (!properties.getPersistence().isPersistObservations()) {
            LOGGER.info(
                    "Skipped persisting variant QC observation rows: analysisId={}, rows={}, "
                            + "persistObservations=false",
                    id,
                    observations == null ? 0 : observations.size()
            );
            return;
        }
        if (observations == null || observations.isEmpty()) {
            return;
        }
        int batchSize = properties.getPersistence().normalizedObservationBatchSize();
        long started = System.nanoTime();
        LOGGER.info(
                "Persisting variant QC observations: analysisId={}, rows={}, batchSize={}",
                id,
                observations.size(),
                batchSize
        );
        for (int start = 0; start < observations.size(); start += batchSize) {
            int chunkStart = start;
            int chunkEnd = Math.min(start + batchSize, observations.size());
            executePersistenceChunk(
                    () -> insertObservationChunk(id, observations, chunkStart, chunkEnd)
            );
        }
        LOGGER.info(
                "Persisted variant QC observations: analysisId={}, rows={}, elapsedMs={}",
                id,
                observations.size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        );
    }

    @Override
    public List<VariantQcObservation> findObservations(String id, int offset, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("offset", offset)
                .addValue("limit", limit);
        return jdbcTemplate.query(
                "SELECT genome_assembly, chromosome, variant_position, reference_allele, alternate_allele, "
                        + "sample_id, genotype, depth, genotype_quality, allele_depths, allele_balance, "
                        + "filter_status, variant_type, original_record "
                        + "FROM variant_analysis_observation "
                        + "WHERE analysis_id = :id ORDER BY variant_order LIMIT :limit OFFSET :offset",
                parameters,
                (resultSet, rowNumber) -> new VariantQcObservation(
                        new NormalizedVariantKey(
                                resultSet.getString("genome_assembly"),
                                resultSet.getString("chromosome"),
                                resultSet.getInt("variant_position"),
                                resultSet.getString("reference_allele"),
                                resultSet.getString("alternate_allele")
                        ),
                        resultSet.getString("sample_id"),
                        resultSet.getString("genotype"),
                        nullableInteger(resultSet, "depth"),
                        nullableInteger(resultSet, "genotype_quality"),
                        resultSet.getString("allele_depths"),
                        nullableDouble(resultSet, "allele_balance"),
                        resultSet.getString("filter_status"),
                        resultSet.getString("variant_type"),
                        resultSet.getString("original_record")
                )
        );
    }

    @Override
    public int countObservations(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM variant_analysis_observation WHERE analysis_id = :id",
                Objects.requireNonNull(idParameters(id), "parameters"),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public void replaceInterpretations(String id, List<VariantInterpretation> interpretations) {
        List<VariantInterpretation> persistedInterpretations =
                interpretations == null || interpretations.isEmpty()
                        ? Collections.<VariantInterpretation>emptyList()
                        : persistedInterpretations(interpretations);
        executePersistenceChunk(
                () -> jdbcTemplate.update(
                        "DELETE FROM variant_analysis_interpretation WHERE analysis_id = :id",
                        Objects.requireNonNull(idParameters(id), "parameters")
                )
        );
        if (persistedInterpretations.isEmpty()) {
            return;
        }
        int batchSize = properties.getPersistence().normalizedInterpretationBatchSize();
        int batchMaxBytes = properties.getPersistence().normalizedInterpretationBatchMaxBytes();
        int compactPayloads = countCompactPayloads(persistedInterpretations);
        int skippedRows = Objects.requireNonNull(interpretations).size() - persistedInterpretations.size();
        long started = System.nanoTime();
        LOGGER.info(
                "Persisting variant interpretations: analysisId={}, rows={}, batchSize={}, "
                        + "payloadBatchMaxBytes={}, compactPayloads={}, fullPayloads={}, skippedSummaryOnlyRows={}",
                id,
                persistedInterpretations.size(),
                batchSize,
                batchMaxBytes,
                compactPayloads,
                persistedInterpretations.size() - compactPayloads,
                skippedRows
        );
        for (int start = 0; start < persistedInterpretations.size(); start += batchSize) {
            int chunkStart = start;
            int chunkEnd = Math.min(start + batchSize, persistedInterpretations.size());
            executePersistenceChunk(
                    () -> insertInterpretationChunk(id, persistedInterpretations, chunkStart, chunkEnd)
            );
        }
        LOGGER.info(
                "Persisted variant interpretations: analysisId={}, rows={}, skippedSummaryOnlyRows={}, elapsedMs={}",
                id,
                persistedInterpretations.size(),
                skippedRows,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        );
    }

    private List<VariantInterpretation> persistedInterpretations(List<VariantInterpretation> interpretations) {
        List<VariantInterpretation> persisted;
        if (properties.getPersistence().isPersistSummaryOnlyInterpretations()) {
            persisted = new ArrayList<VariantInterpretation>(interpretations);
        } else {
            persisted = new ArrayList<VariantInterpretation>(interpretations.size());
            for (VariantInterpretation interpretation : interpretations) {
                if (interpretation == null) {
                    continue;
                }
                if (interpretation.isLargeFileMode() && interpretation.isSummaryOnly()) {
                    continue;
                }
                persisted.add(interpretation);
            }
        }
        return ensureUniqueCandidateIds(persisted);
    }

    private List<VariantInterpretation> ensureUniqueCandidateIds(
            List<VariantInterpretation> interpretations) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (VariantInterpretation interpretation : interpretations) {
            if (interpretation == null) {
                throw new IllegalArgumentException("INTERPRETATION_REQUIRED");
            }
            counts.put(
                    interpretation.getCandidateId(),
                    counts.getOrDefault(interpretation.getCandidateId(), 0) + 1
            );
        }
        List<VariantInterpretation> normalized = new ArrayList<VariantInterpretation>(interpretations.size());
        for (int index = 0; index < interpretations.size(); index++) {
            VariantInterpretation interpretation = interpretations.get(index);
            if (counts.get(interpretation.getCandidateId()) == 1) {
                normalized.add(interpretation);
                continue;
            }
            if (!"LEGACY".equals(interpretation.getCandidateIdentity().getSource())) {
                throw new IllegalStateException("DUPLICATE_CANDIDATE_ID:" + interpretation.getCandidateId());
            }
            CandidateIdentity identity = CandidateIdentity.legacy(
                    String.format(java.util.Locale.ROOT, "legacy-v1-row-%010d", index),
                    interpretation.getCanonicalVariantId()
            );
            normalized.add(interpretation.withCandidateIdentity(identity));
        }
        return normalized;
    }

    private void executePersistenceChunk(Runnable runnable) {
        int maxAttempts = properties.getPersistence().normalizedRetryAttempts();
        Duration retryBackoff = properties.getPersistence().normalizedRetryBackoff();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                persistenceChunkTransactionTemplate.execute(status -> {
                    runnable.run();
                    return null;
                });
                return;
            } catch (RuntimeException ex) {
                if (attempt >= maxAttempts || !isTransientPersistenceFailure(ex)) {
                    throw ex;
                }
                LOGGER.warn(
                        "Transient database failure while persisting variant analysis chunk; "
                                + "retrying attempt {}/{} after {} ms: {}",
                        attempt + 1,
                        maxAttempts,
                        retryBackoff.toMillis(),
                        rootCauseMessage(ex)
                );
                sleepBeforePersistenceRetry(retryBackoff, ex);
            }
        }
    }

    private boolean isTransientPersistenceFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof TransientDataAccessException
                    || current instanceof SQLTransientException
                    || current instanceof SQLRecoverableException
                    || current instanceof SQLNonTransientConnectionException
                    || current instanceof SocketException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
            if (current instanceof SQLException) {
                String sqlState = ((SQLException) current).getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            if (isTransientConnectionMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTransientConnectionMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("communications link failure")
                || normalized.contains("connection reset")
                || normalized.contains("socket write error")
                || normalized.contains("connection is closed")
                || normalized.contains("no operations allowed after connection closed")
                || normalized.contains("no operations allowed after statement closed");
    }

    private String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private void sleepBeforePersistenceRetry(Duration retryBackoff, RuntimeException originalFailure) {
        long delayMillis = retryBackoff.toMillis();
        if (delayMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            originalFailure.addSuppressed(interrupted);
            throw originalFailure;
        }
    }

    @Override
    public List<VariantInterpretation> findInterpretationSummaries(
            String id,
            int offset,
            int limit,
            VariantInterpretationView view,
            VariantInterpretationFilter filter) {
        VariantInterpretationFilter normalized =
                filter == null ? VariantInterpretationFilter.empty() : filter;
        if (normalized.requiresPostQueryFiltering()) {
            return findPostFilteredInterpretationSummaries(id, offset, limit, view, normalized);
        }
        MapSqlParameterSource parameters = idParameters(id)
                .addValue("offset", offset)
                .addValue("limit", limit);
        String filterPredicate = interpretationFilterPredicate(normalized, parameters);
        return jdbcTemplate.query(
                "SELECT candidate_id, canonical_variant_id, variant_id, variant_order, "
                        + "priority_tier, ranking_score, genome_assembly, chromosome, "
                        + "variant_position, reference_allele, alternate_allele, sample_id, genotype, "
                        + "filter_status, variant_type, consequences_json, cadd_score, alpha_missense_score "
                        + ", alpha_genome_score, analysis_bucket, summary_only, large_file_mode, "
                        + "expensive_annotation_skipped, expensive_annotation_skipped_reason, "
                        + "cheap_gate_reason_text, full_interpretation_available, payload_json "
                        + "FROM variant_analysis_interpretation "
                        + "WHERE analysis_id = :id "
                        + interpretationViewPredicate(view)
                        + filterPredicate
                        + "ORDER BY ranking_score DESC, variant_order ASC LIMIT :limit OFFSET :offset",
                Objects.requireNonNull(parameters, "parameters"),
                this::summaryRowMapper
        );
    }

    @Override
    public List<VariantInterpretation> findInterpretations(String id, int offset, int limit) {
        MapSqlParameterSource parameters = idParameters(id)
                .addValue("offset", offset)
                .addValue("limit", limit);
        return jdbcTemplate.query(
                "SELECT candidate_id, canonical_variant_id, payload_json "
                        + "FROM variant_analysis_interpretation "
                        + "WHERE analysis_id = :id "
                        + "ORDER BY ranking_score DESC, variant_order ASC LIMIT :limit OFFSET :offset",
                parameters,
                (resultSet, rowNumber) -> readPersistedInterpretation(resultSet)
        );
    }

    @Override
    public List<PersistedRankingCandidate> findRankingPage(String id, int offset, int limit) {
        MapSqlParameterSource parameters = idParameters(id)
                .addValue("offset", offset)
                .addValue("limit", limit);
        return jdbcTemplate.query(
                "SELECT candidate_id, variant_id, variant_order, ranking_score, genome_assembly, chromosome, "
                        + "variant_position, reference_allele, alternate_allele "
                        + "FROM variant_analysis_interpretation "
                        + "WHERE analysis_id = :id "
                        + "ORDER BY ranking_score DESC, variant_order ASC LIMIT :limit OFFSET :offset",
                Objects.requireNonNull(parameters, "parameters"),
                (resultSet, rowNumber) -> new PersistedRankingCandidate(
                        resultSet.getString("candidate_id"),
                        resultSet.getString("variant_id"),
                        PersistedRankingCandidate.key(
                                resultSet.getString("genome_assembly"),
                                resultSet.getString("chromosome"),
                                nullableInteger(resultSet, "variant_position"),
                                resultSet.getString("reference_allele"),
                                resultSet.getString("alternate_allele")
                        ),
                        nullableDouble(resultSet, "ranking_score"),
                        nullableInteger(resultSet, "variant_order")
                )
        );
    }

    @Override
    public Optional<VariantInterpretation> findInterpretation(String id, String variantId) {
        List<VariantInterpretation> interpretations = findInterpretationsByVariantId(id, variantId);
        if (interpretations.size() > 1) {
            throw new AmbiguousVariantIdException(variantId);
        }
        return interpretations.isEmpty() ? Optional.empty() : Optional.of(interpretations.get(0));
    }

    @Override
    public Optional<VariantInterpretation> findCandidate(String id, String candidateId) {
        MapSqlParameterSource parameters = idParameters(id).addValue("candidateId", candidateId);
        List<VariantInterpretation> interpretations = jdbcTemplate.query(
                "SELECT candidate_id, canonical_variant_id, payload_json "
                        + "FROM variant_analysis_interpretation "
                        + "WHERE analysis_id = :id AND candidate_id = :candidateId",
                parameters,
                (resultSet, rowNumber) -> readPersistedInterpretation(resultSet)
        );
        if (interpretations.size() > 1) {
            throw new IllegalStateException("DUPLICATE_CANDIDATE_ID:" + candidateId);
        }
        return interpretations.isEmpty() ? Optional.empty() : Optional.of(interpretations.get(0));
    }

    @Override
    public List<VariantInterpretation> findInterpretationsByVariantId(String id, String variantId) {
        MapSqlParameterSource parameters = idParameters(id).addValue("variantId", variantId);
        return jdbcTemplate.query(
                "SELECT candidate_id, canonical_variant_id, payload_json "
                        + "FROM variant_analysis_interpretation "
                        + "WHERE analysis_id = :id AND variant_id = :variantId "
                        + "ORDER BY variant_order ASC",
                parameters,
                (resultSet, rowNumber) -> readPersistedInterpretation(resultSet)
        );
    }

    @Override
    public void updateTranscriptAnnotations(String id,
                                            Map<String, List<TranscriptConsequence>> annotationsByVariantId) {
        if (annotationsByVariantId == null || annotationsByVariantId.isEmpty()) {
            return;
        }
        List<Map.Entry<String, List<TranscriptConsequence>>> entries =
                new ArrayList<Map.Entry<String, List<TranscriptConsequence>>>(annotationsByVariantId.entrySet());
        int batchSize = Math.min(
                TRANSCRIPT_ANNOTATION_UPDATE_BATCH_LIMIT,
                Math.max(1, properties.getVep().getBatchSize())
        );
        for (int start = 0; start < entries.size(); start += batchSize) {
            int chunkStart = start;
            int chunkEnd = Math.min(start + batchSize, entries.size());
            executePersistenceChunk(
                    () -> updateTranscriptAnnotationChunk(id, entries, chunkStart, chunkEnd)
            );
        }
    }

    private void updateTranscriptAnnotationChunk(
            String id,
            List<Map.Entry<String, List<TranscriptConsequence>>> entries,
            int start,
            int end) {
        for (int index = start; index < end; index++) {
            Map.Entry<String, List<TranscriptConsequence>> entry = entries.get(index);
            List<VariantInterpretation> matching = findInterpretationsByVariantId(id, entry.getKey());
            for (VariantInterpretation interpretation : matching) {
                interpretation.applyTranscriptAnnotations(entry.getValue());
                jdbcTemplate.update(
                        "UPDATE variant_analysis_interpretation "
                                + "SET payload_json = :payloadJson "
                                + "WHERE analysis_id = :id AND candidate_id = :candidateId",
                        idParameters(id)
                                .addValue("candidateId", interpretation.getCandidateId())
                                .addValue("payloadJson", writeInterpretation(interpretation))
                );
            }
        }
    }

    private String interpretationViewPredicate(VariantInterpretationView view) {
        VariantInterpretationView normalized = view == null ? VariantInterpretationView.CANDIDATES : view;
        if (normalized == VariantInterpretationView.FREQUENCY_FILTERED) {
            return "AND priority_tier IN ('POPULATION_FILTERED', 'FILTERED_COMMON') ";
        }
        if (normalized == VariantInterpretationView.CANDIDATES) {
            return "AND (priority_tier IS NULL "
                    + "OR priority_tier NOT IN ('POPULATION_FILTERED', 'FILTERED_COMMON', 'FILTERED_QC')) ";
        }
        return "";
    }

    @Override
    public int countInterpretations(String id, VariantInterpretationView view) {
        return countInterpretations(id, view, VariantInterpretationFilter.empty());
    }

    @Override
    public int countInterpretations(String id,
                                    VariantInterpretationView view,
                                    VariantInterpretationFilter filter) {
        VariantInterpretationFilter normalized =
                filter == null ? VariantInterpretationFilter.empty() : filter;
        if (normalized.requiresPostQueryFiltering()) {
            return postFilteredInterpretations(id, view, normalized).size();
        }
        MapSqlParameterSource parameters = idParameters(id);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM variant_analysis_interpretation WHERE analysis_id = :id "
                        + interpretationViewPredicate(view)
                        + interpretationFilterPredicate(normalized, parameters),
                Objects.requireNonNull(parameters, "parameters"),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private List<VariantInterpretation> findPostFilteredInterpretationSummaries(
            String id,
            int offset,
            int limit,
            VariantInterpretationView view,
            VariantInterpretationFilter filter) {
        List<VariantInterpretation> values = postFilteredInterpretations(id, view, filter);
        if (offset >= values.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(offset + limit, values.size());
        return new ArrayList<VariantInterpretation>(values.subList(offset, toIndex));
    }

    private List<VariantInterpretation> postFilteredInterpretations(
            String id,
            VariantInterpretationView view,
            VariantInterpretationFilter filter) {
        MapSqlParameterSource parameters = idParameters(id);
        String filterPredicate = interpretationFilterPredicate(filter, parameters);
        List<VariantInterpretation> values = jdbcTemplate.query(
                "SELECT candidate_id, canonical_variant_id, payload_json "
                        + "FROM variant_analysis_interpretation "
                        + "WHERE analysis_id = :id "
                        + interpretationViewPredicate(view)
                        + filterPredicate
                        + "ORDER BY ranking_score DESC, variant_order ASC",
                Objects.requireNonNull(parameters, "parameters"),
                (resultSet, rowNumber) -> readPersistedInterpretation(resultSet)
        );
        List<VariantInterpretation> result = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation interpretation : values) {
            if (filter.matches(interpretation)) {
                result.add(interpretation);
            }
        }
        return result;
    }

    @Override
    public VariantInterpretationCounts countInterpretationViews(String id) {
        String bucketExpression = analysisBucketSqlExpression();
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) AS all_count, "
                        + "COALESCE(SUM(CASE WHEN priority_tier IN "
                        + "('POPULATION_FILTERED', 'FILTERED_COMMON') THEN 1 ELSE 0 END), 0) "
                        + "AS frequency_filtered_count, "
                        + "COALESCE(SUM(CASE WHEN priority_tier IS NULL OR priority_tier NOT IN "
                        + "('POPULATION_FILTERED', 'FILTERED_COMMON', 'FILTERED_QC') "
                        + "THEN 1 ELSE 0 END), 0) AS candidate_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'CANDIDATE' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_candidate_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'KNOWN_CLINICAL' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_known_clinical_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'HIGH_PRIORITY' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_high_priority_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'REVIEW' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_review_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'FILTERED_QC' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_filtered_qc_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'FILTERED_COMMON' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_filtered_common_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'FILTERED_NOT_CARRIED' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_filtered_not_carried_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'DEFERRED_LOW_PRIORITY' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_deferred_low_priority_count, "
                        + "COALESCE(SUM(CASE WHEN derived_bucket = 'DEFERRED_NO_EXPENSIVE_ANNOTATION' "
                        + "THEN 1 ELSE 0 END), 0) AS bucket_deferred_no_expensive_annotation_count, "
                        + "COALESCE(SUM(CASE WHEN summary_only = TRUE THEN 1 ELSE 0 END), 0) "
                        + "AS summary_only_count, "
                        + "COALESCE(SUM(CASE WHEN full_interpretation_available = TRUE THEN 1 ELSE 0 END), 0) "
                        + "AS full_interpretation_count, "
                        + "COALESCE(SUM(CASE WHEN expensive_annotation_skipped = TRUE THEN 1 ELSE 0 END), 0) "
                        + "AS expensive_annotation_skipped_count, "
                        + "COALESCE(SUM(CASE WHEN large_file_mode = TRUE THEN 1 ELSE 0 END), 0) "
                        + "AS large_file_mode_count, "
                        + "COALESCE(SUM(CASE WHEN carried_alt = TRUE THEN 1 ELSE 0 END), 0) "
                        + "AS carried_alt_count, "
                        + "COALESCE(SUM(CASE WHEN carried_alt = FALSE THEN 1 ELSE 0 END), 0) "
                        + "AS not_carried_alt_count, "
                        + "COALESCE(SUM(CASE WHEN analysis_bucket IS NULL OR analysis_bucket = '' "
                        + "THEN 1 ELSE 0 END), 0) AS missing_bucket_count "
                        + "FROM (SELECT priority_tier, carried_alt, summary_only, "
                        + "full_interpretation_available, expensive_annotation_skipped, large_file_mode, "
                        + "analysis_bucket, " + bucketExpression + " AS derived_bucket "
                        + "FROM variant_analysis_interpretation WHERE analysis_id = :id) counts",
                Objects.requireNonNull(idParameters(id), "parameters"),
                (resultSet, rowNumber) -> interpretationCountsRowMapper(resultSet)
        );
    }

    private VariantInterpretationCounts interpretationCountsRowMapper(ResultSet resultSet) throws SQLException {
        Map<String, Integer> bucketCounts = new LinkedHashMap<String, Integer>();
        bucketCounts.put(VariantAnalysisBucket.CANDIDATE.name(), resultSet.getInt("bucket_candidate_count"));
        bucketCounts.put(
                VariantAnalysisBucket.KNOWN_CLINICAL.name(),
                resultSet.getInt("bucket_known_clinical_count")
        );
        bucketCounts.put(
                VariantAnalysisBucket.HIGH_PRIORITY.name(),
                resultSet.getInt("bucket_high_priority_count")
        );
        bucketCounts.put(VariantAnalysisBucket.REVIEW.name(), resultSet.getInt("bucket_review_count"));
        bucketCounts.put(VariantAnalysisBucket.FILTERED_QC.name(), resultSet.getInt("bucket_filtered_qc_count"));
        bucketCounts.put(
                VariantAnalysisBucket.FILTERED_COMMON.name(),
                resultSet.getInt("bucket_filtered_common_count")
        );
        bucketCounts.put(
                VariantAnalysisBucket.FILTERED_NOT_CARRIED.name(),
                resultSet.getInt("bucket_filtered_not_carried_count")
        );
        bucketCounts.put(
                VariantAnalysisBucket.DEFERRED_LOW_PRIORITY.name(),
                resultSet.getInt("bucket_deferred_low_priority_count")
        );
        bucketCounts.put(
                VariantAnalysisBucket.DEFERRED_NO_EXPENSIVE_ANNOTATION.name(),
                resultSet.getInt("bucket_deferred_no_expensive_annotation_count")
        );
        int missingBucketCount = resultSet.getInt("missing_bucket_count");
        return new VariantInterpretationCounts(
                resultSet.getInt("candidate_count"),
                resultSet.getInt("frequency_filtered_count"),
                resultSet.getInt("all_count"),
                bucketCounts,
                resultSet.getInt("summary_only_count"),
                resultSet.getInt("full_interpretation_count"),
                resultSet.getInt("expensive_annotation_skipped_count"),
                resultSet.getInt("large_file_mode_count"),
                resultSet.getInt("carried_alt_count"),
                resultSet.getInt("not_carried_alt_count"),
                missingBucketCount == 0,
                missingBucketCount > 0
        );
    }

    private String analysisBucketSqlExpression() {
        return "CASE "
                + "WHEN analysis_bucket IS NOT NULL AND analysis_bucket <> '' THEN analysis_bucket "
                + "WHEN priority_tier = 'KNOWN_CLINICAL' THEN 'KNOWN_CLINICAL' "
                + "WHEN priority_tier = 'HIGH_PRIORITY' THEN 'HIGH_PRIORITY' "
                + "WHEN priority_tier = 'REVIEW' THEN 'REVIEW' "
                + "WHEN priority_tier IN ('FILTERED_COMMON', 'POPULATION_FILTERED') THEN 'FILTERED_COMMON' "
                + "WHEN priority_tier = 'FILTERED_QC' AND carried_alt = FALSE THEN 'FILTERED_NOT_CARRIED' "
                + "WHEN priority_tier = 'FILTERED_QC' THEN 'FILTERED_QC' "
                + "ELSE 'CANDIDATE' END";
    }

    private String interpretationFilterPredicate(VariantInterpretationFilter filter,
                                                 MapSqlParameterSource parameters) {
        VariantInterpretationFilter normalized =
                filter == null ? VariantInterpretationFilter.empty() : filter;
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder predicate = new StringBuilder();
        if (!normalized.getTierValues().isEmpty()) {
            parameters.addValue("filterTiers", normalized.getTierValues());
            predicate.append("AND priority_tier IN (:filterTiers) ");
        }
        if (normalized.isHideFilteredCommon()) {
            predicate.append("AND priority_tier NOT IN ('POPULATION_FILTERED', 'FILTERED_COMMON') ");
        }
        if (normalized.isHideFilteredQc()) {
            predicate.append("AND priority_tier <> 'FILTERED_QC' ");
        }
        if ("PLP".equals(normalized.getClinvarClass())) {
            predicate.append("AND LOWER(COALESCE(payload_json, '')) LIKE '%clinvar%' ")
                    .append("AND LOWER(COALESCE(payload_json, '')) LIKE '%pathogenic%' ")
                    .append("AND LOWER(COALESCE(payload_json, '')) NOT LIKE '%benign%' ");
        } else if ("VUS".equals(normalized.getClinvarClass())) {
            predicate.append("AND LOWER(COALESCE(payload_json, '')) LIKE '%clinvar%' ")
                    .append("AND (LOWER(COALESCE(payload_json, '')) LIKE '%uncertain%' ")
                    .append("OR LOWER(COALESCE(payload_json, '')) LIKE '%vus%') ");
        }
        if (!normalized.getAcmgCodeValues().isEmpty()) {
            int index = 0;
            predicate.append("AND (");
            for (String code : normalized.getAcmgCodeValues()) {
                String parameter = "acmgCode" + index++;
                if (index > 1) {
                    predicate.append("OR ");
                }
                parameters.addValue(
                        parameter,
                        "%\"code\":\"" + code.toLowerCase() + "\"%"
                );
                predicate.append("LOWER(COALESCE(payload_json, '')) LIKE :")
                        .append(parameter)
                        .append(' ');
            }
            predicate.append(") ");
        }
        if (normalized.getAcmgStatus() != null) {
            parameters.addValue(
                    "acmgStatus",
                    "%\"status\":\"" + normalized.getAcmgStatus().toLowerCase() + "\"%"
            );
            predicate.append("AND LOWER(COALESCE(payload_json, '')) LIKE :acmgStatus ");
        }
        if (normalized.getManualReviewRequired() != null) {
            parameters.addValue(
                    "manualReviewRequired",
                    "%\"manualReviewRequired\":" + normalized.getManualReviewRequired() + "%"
            );
            predicate.append("AND COALESCE(payload_json, '') LIKE :manualReviewRequired ");
        }
        if (Boolean.TRUE.equals(normalized.getHasAcmgSuggestion())) {
            parameters.addValue("hasAcmgSuggestion", "%\"acmgEvidenceSuggestions\":[{%");
            predicate.append("AND COALESCE(payload_json, '') LIKE :hasAcmgSuggestion ");
        } else if (Boolean.FALSE.equals(normalized.getHasAcmgSuggestion())) {
            parameters.addValue("hasAcmgSuggestion", "%\"acmgEvidenceSuggestions\":[{%");
            predicate.append("AND COALESCE(payload_json, '') NOT LIKE :hasAcmgSuggestion ");
        }
        if (Boolean.TRUE.equals(normalized.getHasPhenotypeSupport())
                || normalized.getPhenotypeGene() != null
                || normalized.getMinPhenotypeScore() != null) {
            predicate.append("AND ")
                    .append(phenotypeSupportSqlPredicate())
                    .append(' ');
        } else if (Boolean.FALSE.equals(normalized.getHasPhenotypeSupport())) {
            predicate.append("AND NOT ")
                    .append(phenotypeSupportSqlPredicate())
                    .append(' ');
        }
        if (normalized.getPhenotypeGene() != null) {
            parameters.addValue(
                    "phenotypeGene",
                    "%\"genesymbol\":\"" + normalized.getPhenotypeGene().toLowerCase() + "\"%"
            );
            predicate.append("AND LOWER(COALESCE(payload_json, '')) LIKE :phenotypeGene ");
        }
        if (!normalized.getAnalysisBucketValues().isEmpty()) {
            parameters.addValue("analysisBucketValues", normalized.getAnalysisBucketValues());
            predicate.append("AND ")
                    .append(analysisBucketSqlExpression())
                    .append(" IN (:analysisBucketValues) ");
        }
        if (normalized.getSummaryOnly() != null) {
            parameters.addValue("summaryOnly", normalized.getSummaryOnly());
            predicate.append("AND summary_only = :summaryOnly ");
        }
        if (normalized.getLargeFileMode() != null) {
            parameters.addValue("largeFileMode", normalized.getLargeFileMode());
            predicate.append("AND large_file_mode = :largeFileMode ");
        }
        if (normalized.getExpensiveAnnotationSkipped() != null) {
            parameters.addValue("expensiveAnnotationSkipped", normalized.getExpensiveAnnotationSkipped());
            predicate.append("AND expensive_annotation_skipped = :expensiveAnnotationSkipped ");
        }
        if (normalized.getSearch() != null) {
            String search = normalized.getSearch().toLowerCase();
            parameters.addValue("search", "%" + search + "%");
            predicate.append("AND (")
                    .append("LOWER(COALESCE(candidate_id, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(canonical_variant_id, variant_id, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(variant_id, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(priority_tier, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(genome_assembly, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(chromosome, '')) LIKE :search ")
                    .append("OR CAST(variant_position AS CHAR) LIKE :search ")
                    .append("OR LOWER(COALESCE(reference_allele, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(alternate_allele, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(sample_id, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(genotype, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(filter_status, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(variant_type, '')) LIKE :search ");
            // Keep this predicate broad and let VariantInterpretationFilter.matches perform the
            // exact gene-symbol check.  Restricting gene-shaped tokens here would otherwise make
            // structured values such as rs123, AD and result.variants.tsv disappear in JDBC while
            // the in-memory repository still finds them.
            predicate.append("OR LOWER(COALESCE(consequences_json, '')) LIKE :search ")
                    .append("OR LOWER(COALESCE(payload_json, '')) LIKE :search ");
            predicate
                    .append(") ");
        }
        return predicate.toString();
    }

    private String phenotypeSupportSqlPredicate() {
        return "("
                + "LOWER(COALESCE(payload_json, '')) LIKE '%\"phenotype_support\"%' "
                + "OR LOWER(COALESCE(payload_json, '')) LIKE '%\"evidencetype\":\"phenotype_gene_prior\"%' "
                + "OR (LOWER(COALESCE(payload_json, '')) LIKE '%phenotype%' "
                + "AND LOWER(COALESCE(payload_json, '')) LIKE '%support%')"
                + ")";
    }

    @Override
    public void appendEvent(String id, VariantAnalysisStatus status, String message) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("analysisId", id)
                .addValue("status", status.name())
                .addValue("message", message)
                .addValue("createdAt", Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                "INSERT INTO variant_analysis_event (analysis_id, status, message, created_at) "
                        + "VALUES (:analysisId, :status, :message, :createdAt)",
                parameters
        );
    }

    @Override
    public List<VariantAnalysisEvent> findEvents(String id) {
        return jdbcTemplate.query(
                "SELECT id, analysis_id, status, message, created_at "
                        + "FROM variant_analysis_event "
                        + "WHERE analysis_id = :id ORDER BY created_at, id",
                Objects.requireNonNull(idParameters(id), "parameters"),
                (resultSet, rowNumber) -> new VariantAnalysisEvent(
                        resultSet.getLong("id"),
                        resultSet.getString("analysis_id"),
                        VariantAnalysisStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("message"),
                        resultSet.getTimestamp("created_at").toInstant()
                )
        );
    }

    @Override
    @Transactional(transactionManager = "varvisTxManager")
    public int failInterruptedJobs() {
        List<String> interruptedIds = jdbcTemplate.queryForList(
                "SELECT id FROM variant_analysis_job "
                        + "WHERE status IN ('UPLOADED', 'VALIDATING', 'NORMALIZING', 'QC_EXTRACTING', "
                        + "'BASE_ANNOTATING', 'RANKING')",
                new MapSqlParameterSource(),
                String.class
        );
        if (interruptedIds.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        String message = "Application restarted before analysis completed";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("ids", interruptedIds)
                .addValue("status", VariantAnalysisStatus.FAILED.name())
                .addValue("message", message)
                .addValue("updatedAt", Timestamp.from(now));
        jdbcTemplate.update(
                "UPDATE variant_analysis_job SET status = :status, error_message = :message, "
                        + "updated_at = :updatedAt WHERE id IN (:ids)",
                parameters
        );
        for (String id : interruptedIds) {
            appendEvent(id, VariantAnalysisStatus.FAILED, message);
        }
        return interruptedIds.size();
    }

    @Override
    public void deleteById(String id) {
        int batchSize = properties.getPersistence().normalizedDeletionBatchSize();
        long started = System.nanoTime();
        long deletedRows = 0L;
        deletedRows += deleteAnalysisRowsInChunks("variant_analysis_interpretation", id, batchSize);
        deletedRows += deleteAnalysisRowsInChunks("variant_analysis_observation", id, batchSize);
        deletedRows += deleteAnalysisRowsInChunks("variant_analysis_enhancement", id, batchSize);
        deletedRows += deleteAnalysisRowsInChunks("variant_analysis_event", id, batchSize);
        deletedRows += deleteAnalysisRowsInChunks("exomiser_analysis_job", id, batchSize);
        executePersistenceChunk(
                () -> jdbcTemplate.update(
                        "DELETE FROM variant_analysis_job WHERE id = :id",
                        Objects.requireNonNull(idParameters(id), "parameters")
                )
        );
        LOGGER.info(
                "Deleted variant analysis job in chunks: analysisId={}, childRows={}, batchSize={}, elapsedMs={}",
                id,
                deletedRows,
                batchSize,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        );
    }

    private long deleteAnalysisRowsInChunks(String table, String id, int batchSize) {
        long total = 0L;
        int deleted;
        do {
            int[] chunkDeleted = new int[1];
            executePersistenceChunk(() -> chunkDeleted[0] = jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE analysis_id = :id LIMIT :limit",
                    idParameters(id).addValue("limit", batchSize)
            ));
            deleted = chunkDeleted[0];
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }

    private RowMapper<VariantAnalysisJob> jobRowMapper() {
        return (resultSet, rowNumber) -> {
            String id = resultSet.getString("id");
            Path workspace = properties.getStorageRoot().toAbsolutePath().normalize().resolve(id).normalize();
            Path uploadedFile = workspace.resolve(resultSet.getString("stored_file_name")).normalize();
            return VariantAnalysisJob.restore(
                    id,
                    resultSet.getString("original_file_name"),
                    resultSet.getString("file_sha256"),
                    GenomeAssembly.valueOf(resultSet.getString("genome_assembly")),
                    AnalysisMode.valueOf(resultSet.getString("analysis_mode")),
                    resultSet.getString("requested_sample_id"),
                    workspace,
                    uploadedFile,
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant(),
                    VariantAnalysisStatus.valueOf(resultSet.getString("status")),
                    resultSet.getString("selected_sample_id"),
                    resultSet.getBoolean("normalization_applied"),
                    resultSet.getString("normalization_engine"),
                    readSummary(resultSet),
                    readWarnings(resultSet.getString("warnings_json")),
                    resultSet.getString("error_message"),
                    readPhenotypeSummary(
                            resultSet.getString("phenotype_summary_schema_version"),
                            resultSet.getString("phenotype_summary_json")
                    )
            );
        };
    }

    private static MapSqlParameterSource idParameters(String id) {
        return new MapSqlParameterSource("id", id);
    }

    private VariantQcSummary readSummary(ResultSet resultSet) throws SQLException {
        if (resultSet.getObject("sample_count") == null) {
            return null;
        }
        return new VariantQcSummary(
                resultSet.getInt("sample_count"),
                resultSet.getInt("input_site_count"),
                resultSet.getInt("variant_count"),
                resultSet.getInt("multi_allelic_site_count"),
                resultSet.getInt("snv_count"),
                resultSet.getInt("indel_count"),
                resultSet.getInt("mnv_count"),
                resultSet.getInt("other_count"),
                resultSet.getInt("pass_count"),
                resultSet.getInt("filtered_count"),
                resultSet.getInt("missing_depth_count"),
                resultSet.getInt("missing_genotype_quality_count")
        );
    }

    private String writeWarnings(List<String> warnings) {
        try {
            return objectMapper.writeValueAsString(warnings == null ? Collections.emptyList() : warnings);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize variant analysis warnings", ex);
        }
    }

    private String phenotypeSummarySchemaVersion(VariantAnalysisJob job) {
        return job != null && job.getPhenotypeSummary() != null
                && job.getPhenotypeSummary().hasAnyPhenotypeInput()
                ? PHENOTYPE_SUMMARY_SCHEMA_VERSION
                : null;
    }

    private void persistExomiserMetadata(VariantAnalysisJob job) {
        if (job == null) {
            return;
        }
        EngineMetadata metadata = job.getEngineMetadata();
        if (metadata == null || metadata.getEngine() != org.nbscn.varvis.variantanalysis.domain.AnalysisEngine.EXOMISER) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO exomiser_analysis_job ("
                        + "analysis_id, schema_version, engine_version, ranking_mode, execution_status, "
                        + "unavailable_reason, resource_fingerprint, output_sha256, fallback_used, created_at, updated_at"
                        + ") VALUES ("
                        + ":analysisId, 'v1', :engineVersion, :rankingMode, :executionStatus, "
                        + ":unavailableReason, :resourceFingerprint, :outputSha256, :fallbackUsed, :createdAt, :updatedAt"
                        + ") ON DUPLICATE KEY UPDATE "
                        + "engine_version = VALUES(engine_version), ranking_mode = VALUES(ranking_mode), "
                        + "execution_status = VALUES(execution_status), unavailable_reason = VALUES(unavailable_reason), "
                        + "resource_fingerprint = VALUES(resource_fingerprint), output_sha256 = VALUES(output_sha256), "
                        + "fallback_used = VALUES(fallback_used), updated_at = VALUES(updated_at)",
                new MapSqlParameterSource()
                        .addValue("analysisId", job.getId())
                        .addValue("engineVersion", metadata.getEngineVersion())
                        .addValue("rankingMode", metadata.getRankingMode())
                        .addValue("executionStatus", metadata.getStatus().name())
                        .addValue("unavailableReason", metadata.getUnavailableReason())
                        .addValue("resourceFingerprint", metadata.getResourceFingerprint())
                        .addValue("outputSha256", metadata.getOutputSha256())
                        .addValue("fallbackUsed", metadata.isFallbackUsed())
                        .addValue("createdAt", Timestamp.from(job.getCreatedAt()))
                        .addValue("updatedAt", Timestamp.from(job.getUpdatedAt()))
        );
    }

    private VariantAnalysisJob attachExomiserMetadata(VariantAnalysisJob job) {
        if (job == null) {
            return null;
        }
        List<EngineMetadata> values = jdbcTemplate.query(
                "SELECT engine_version, ranking_mode, execution_status, unavailable_reason, "
                        + "resource_fingerprint, output_sha256, fallback_used "
                        + "FROM exomiser_analysis_job WHERE analysis_id = :analysisId",
                new MapSqlParameterSource("analysisId", job.getId()),
                (resultSet, rowNum) -> new EngineMetadata(
                        org.nbscn.varvis.variantanalysis.domain.AnalysisEngine.EXOMISER,
                        resultSet.getString("engine_version"),
                        resultSet.getString("ranking_mode"),
                        org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus.valueOf(
                                resultSet.getString("execution_status")
                        ),
                        resultSet.getString("unavailable_reason"),
                        resultSet.getString("resource_fingerprint"),
                        resultSet.getString("output_sha256"),
                        resultSet.getBoolean("fallback_used")
                )
        );
        if (!values.isEmpty()) {
            job.restoreEngineMetadata(values.get(0));
        }
        return job;
    }

    private String writePhenotypeSummary(
            org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary summary) {
        if (summary == null || !summary.hasAnyPhenotypeInput()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize phenotype summary", ex);
        }
    }

    private org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary readPhenotypeSummary(
            String schemaVersion,
            String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return schemaVersion == null || schemaVersion.trim().isEmpty()
                    ? org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary.disabled()
                    : org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary.historicalSummaryUnavailable();
        }
        if (!PHENOTYPE_SUMMARY_SCHEMA_VERSION.equals(schemaVersion)) {
            LOGGER.warn("Unsupported phenotype summary schema version {}; summary is unavailable", schemaVersion);
            return org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary.historicalSummaryUnavailable();
        }
        try {
            return objectMapper.readValue(
                    payload,
                    org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary.class
            );
        } catch (IOException ex) {
            LOGGER.warn("Could not restore persisted phenotype summary; summary is unavailable", ex);
            return org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary.historicalSummaryUnavailable();
        }
    }

    private String writeInterpretation(VariantInterpretation interpretation) {
        try {
            return objectMapper.writeValueAsString(interpretation);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize variant interpretation", ex);
        }
    }

    private String writeInterpretationPayload(VariantInterpretation interpretation, int totalInterpretations) {
        if (shouldWriteCompactPayload(interpretation, totalInterpretations)) {
            return writeInterpretation(compactInterpretation(interpretation));
        }
        return writeInterpretation(interpretation);
    }

    private int countCompactPayloads(List<VariantInterpretation> interpretations) {
        if (interpretations == null || interpretations.isEmpty()) {
            return 0;
        }
        int count = 0;
        int totalInterpretations = interpretations.size();
        for (VariantInterpretation interpretation : interpretations) {
            if (shouldWriteCompactPayload(interpretation, totalInterpretations)) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldWriteCompactPayload(VariantInterpretation interpretation, int totalInterpretations) {
        return interpretation != null && interpretation.isSummaryOnly();
    }

    private VariantInterpretation compactInterpretation(VariantInterpretation interpretation) {
        VariantInterpretation compact = new VariantInterpretation(
                interpretation.getVariantId(),
                compactObservation(interpretation.getObservation()),
                interpretation.getConsequences(),
                interpretation.getTranscriptAnnotations(),
                compactEvidence(interpretation.getEvidence()),
                interpretation.getPriorityTier(),
                interpretation.getRankingScore(),
                interpretation.getScoreBreakdown(),
                Collections.emptyList(),
                compactStrings(interpretation.getWarnings(), 3),
                interpretation.getPriorityTier(),
                interpretation.getRankingScore(),
                interpretation.getTierReason(),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList(),
                compactStrings(interpretation.getFilterReasons(), 5),
                compactAcmgEvidenceSuggestions(interpretation.getAcmgEvidenceSuggestions()),
                interpretation.getAnalysisBucket(),
                interpretation.isLargeFileMode(),
                interpretation.isSummaryOnly(),
                interpretation.isExpensiveAnnotationSkipped(),
                interpretation.getExpensiveAnnotationSkippedReason(),
                interpretation.getCheapGateReasons(),
                false,
                interpretation.getCandidateIdentity(),
                interpretation.getCandidateId(),
                interpretation.getExomiserCandidateFeatures(),
                interpretation.getAnalysisVcfCall()
        );
        return compact;
    }

    private VariantQcObservation compactObservation(VariantQcObservation observation) {
        if (observation == null) {
            return null;
        }
        return new VariantQcObservation(
                observation.getKey(),
                observation.getSampleId(),
                observation.getGenotype(),
                observation.getDepth(),
                observation.getGenotypeQuality(),
                observation.getAlleleDepths(),
                observation.getAlleleBalance(),
                observation.getFilterStatus(),
                observation.getVariantType(),
                null
        );
    }

    private List<EvidenceItem> compactEvidence(List<EvidenceItem> evidenceItems) {
        if (evidenceItems == null || evidenceItems.isEmpty()) {
            return Collections.emptyList();
        }
        List<EvidenceItem> compact = new ArrayList<EvidenceItem>();
        for (EvidenceItem evidence : evidenceItems) {
            if (shouldKeepCompactEvidence(evidence)) {
                compact.add(evidence.withPresentation(
                        compactText(evidence.getLabel()),
                        compactText(evidence.getSummary()),
                        compactEvidenceDetails(evidence.getDetails())
                ));
            }
        }
        return compact;
    }

    private boolean shouldKeepCompactEvidence(EvidenceItem evidence) {
        if (evidence == null) {
            return false;
        }
        String source = evidence.getSource() == null ? "" : evidence.getSource().trim().toUpperCase();
        String applicability = evidence.getApplicability() == null
                ? ""
                : evidence.getApplicability().trim().toUpperCase();
        if ("APPLICABLE".equals(applicability) || "CONFLICTING".equals(applicability)) {
            return true;
        }
        if ("GNOMAD".equals(source)) {
            return "NO_RECORD".equals(applicability) || "UNAVAILABLE".equals(applicability);
        }
        return false;
    }

    private Map<String, Object> compactEvidenceDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> compact = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (shouldKeepCompactDetail(entry.getKey())) {
                Object value = compactDetailValue(entry.getValue());
                if (value != null) {
                    compact.put(entry.getKey(), value);
                }
            }
        }
        return compact;
    }

    private boolean shouldKeepCompactDetail(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase();
        return "source".equals(normalized)
                || "status".equals(normalized)
                || "value".equals(normalized)
                || "sourceversion".equals(normalized)
                || "clinicalsignificance".equals(normalized)
                || "reviewstatus".equals(normalized)
                || "matchtype".equals(normalized)
                || "variationid".equals(normalized)
                || "clinvarvariationid".equals(normalized)
                || "rsid".equals(normalized)
                || "querysource".equals(normalized)
                || "maximumallelefrequency".equals(normalized)
                || "populationmaximumallelefrequency".equals(normalized)
                || "allelefrequency".equals(normalized)
                || "frequencyfield".equals(normalized)
                || "allelecount".equals(normalized)
                || "allelenumber".equals(normalized)
                || "homozygousalternatecount".equals(normalized)
                || "sourcedataset".equals(normalized)
                || "gnomadversion".equals(normalized)
                || "filters".equals(normalized)
                || "rawscore".equals(normalized)
                || "phred".equals(normalized)
                || "classification".equals(normalized)
                || "genome".equals(normalized)
                || "transcriptid".equals(normalized)
                || "proteinvariant".equals(normalized);
    }

    private Object compactDetailValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence) {
            return compactText(value.toString());
        }
        return null;
    }

    private List<AcmgEvidenceSuggestion> compactAcmgEvidenceSuggestions(
            List<AcmgEvidenceSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }
        List<AcmgEvidenceSuggestion> compact = new ArrayList<AcmgEvidenceSuggestion>();
        for (AcmgEvidenceSuggestion suggestion : suggestions) {
            if (suggestion == null) {
                continue;
            }
            if ("SUGGESTED".equals(suggestion.getStatus())
                    || suggestion.isManualReviewRequired()
                    || !suggestion.getWarnings().isEmpty()) {
                compact.add(new AcmgEvidenceSuggestion(
                        suggestion.getCode(),
                        suggestion.getStrength(),
                        suggestion.getStatus(),
                        suggestion.getSourceEvidence(),
                        compactText(suggestion.getExplanation()),
                        suggestion.isManualReviewRequired(),
                        suggestion.getRuleVersion(),
                        compactStrings(suggestion.getWarnings(), 3)
                ));
            }
        }
        return compact;
    }

    private List<String> compactStrings(List<String> values, int limit) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<String> compact = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                compact.add(compactText(value));
            }
            if (compact.size() >= limit) {
                break;
            }
        }
        return compact;
    }

    private String compactText(String value) {
        if (value == null || value.length() <= COMPACT_TEXT_LIMIT) {
            return value;
        }
        return value.substring(0, COMPACT_TEXT_LIMIT) + "...";
    }

    private String writeConsequences(List<TranscriptConsequence> consequences) {
        try {
            return objectMapper.writeValueAsString(
                    consequences == null ? Collections.emptyList() : consequences
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize variant consequences", ex);
        }
    }

    private VariantInterpretation readInterpretation(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, VariantInterpretation.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not deserialize variant interpretation", ex);
        }
    }

    private VariantInterpretation readPersistedInterpretation(ResultSet resultSet) throws SQLException {
        VariantInterpretation interpretation = readInterpretation(resultSet.getString("payload_json"));
        String persistedCandidateId = resultSet.getString("candidate_id");
        String persistedCanonicalId = resultSet.getString("canonical_variant_id");
        String canonicalId = persistedCanonicalId == null || persistedCanonicalId.trim().isEmpty()
                ? interpretation.getCanonicalVariantId()
                : persistedCanonicalId.trim();
        if (!canonicalId.equals(interpretation.getCanonicalVariantId())) {
            throw new IllegalStateException("PERSISTED_CANONICAL_VARIANT_ID_MISMATCH");
        }
        CandidateIdentity identity = interpretation.getCandidateIdentity();
        if (!"LEGACY".equals(identity.getSource())) {
            if (persistedCandidateId == null
                    || !persistedCandidateId.trim().equals(identity.getCandidateId())) {
                throw new IllegalStateException("PERSISTED_CANDIDATE_ID_MISMATCH");
            }
            return interpretation;
        }
        identity = CandidateIdentity.legacy(persistedCandidateId, canonicalId);
        return interpretation.withCandidateIdentity(identity);
    }

    private List<TranscriptConsequence> readConsequences(String consequencesJson) {
        if (consequencesJson == null || consequencesJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(consequencesJson, CONSEQUENCE_LIST_TYPE);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not deserialize variant consequences", ex);
        }
    }

    private List<String> readWarnings(String warningsJson) {
        if (warningsJson == null || warningsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(warningsJson, STRING_LIST_TYPE);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not deserialize variant analysis warnings", ex);
        }
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private VariantInterpretation summaryRowMapper(ResultSet resultSet, int rowNumber) throws SQLException {
        VariantInterpretation persistedPayload = readPersistedSummaryPayload(resultSet);
        VariantQcObservation observation = readObservationSummary(resultSet);
        List<EvidenceItem> evidence = new ArrayList<EvidenceItem>();
        Double caddScore = nullableDouble(resultSet, "cadd_score");
        if (caddScore != null) {
            evidence.add(summaryEvidence(
                    "CADD",
                    properties.getCadd().getVersion(),
                    caddScore,
                    "CADD PHRED",
                    "CADD PHRED score is a ranking aid, not a clinical classification"
            ));
        }
        Double alphaMissenseScore = nullableDouble(resultSet, "alpha_missense_score");
        if (alphaMissenseScore != null) {
            evidence.add(summaryEvidence(
                    "ALPHAMISSENSE",
                    properties.getAlphaMissense().getVersion(),
                    alphaMissenseScore,
                    "AlphaMissense score",
                    "AlphaMissense score is a missense prioritization aid, not a clinical classification"
            ));
        }
        VariantInterpretation summary = new VariantInterpretation(
                resultSet.getString("variant_id"),
                observation,
                readConsequences(resultSet.getString("consequences_json")),
                evidence,
                resultSet.getString("priority_tier"),
                nullableDouble(resultSet, "ranking_score"),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        String persistedBucket = resultSet.getString("analysis_bucket");
        summary.applyLargeFileClassification(
                persistedBucket(persistedBucket, summary),
                nullableBoolean(resultSet, "large_file_mode", false),
                nullableBoolean(resultSet, "summary_only", false),
                nullableBoolean(resultSet, "expensive_annotation_skipped", false),
                resultSet.getString("expensive_annotation_skipped_reason"),
                readReasonText(resultSet.getString("cheap_gate_reason_text"))
        );
        summary.applyFullInterpretationAvailable(
                nullableBoolean(resultSet, "full_interpretation_available", true)
        );
        copySummaryFieldsFromPayload(
                summary,
                persistedPayload,
                persistedBucket == null || persistedBucket.trim().isEmpty()
        );
        CandidateIdentity identity = persistedPayload == null
                ? legacySummaryIdentity(resultSet, summary)
                : persistedPayload.getCandidateIdentity();
        VariantInterpretation hydratedSummary = summary.withCandidateIdentityAndExomiserFeatures(
                identity,
                persistedPayload == null
                        ? null
                        : persistedPayload.getExomiserCandidateFeatures()
        );
        return persistedPayload == null
                ? hydratedSummary
                : hydratedSummary.withAnalysisVcfCall(persistedPayload.getAnalysisVcfCall());
    }

    private VariantInterpretation readPersistedSummaryPayload(ResultSet resultSet) throws SQLException {
        try {
            return readPersistedInterpretation(resultSet);
        } catch (RuntimeException failure) {
            String candidateId = resultSet.getString("candidate_id");
            String canonicalVariantId = resultSet.getString("canonical_variant_id");
            boolean migratedLegacyRow = candidateId != null
                    && candidateId.matches("legacy-v1-[0-9]{10}")
                    && (canonicalVariantId == null || canonicalVariantId.trim().isEmpty());
            if (!migratedLegacyRow
                    || !"Could not deserialize variant interpretation".equals(failure.getMessage())
                    || containsStructuredPayloadIntegrityFailure(failure)
                    || containsNonNullStructuredPayload(resultSet.getString("payload_json"))) {
                throw failure;
            }
            LOGGER.warn(
                    "Using relational summary fallback for unreadable legacy candidate payload: candidateId={}",
                    candidateId
            );
            return null;
        }
    }

    private boolean containsStructuredPayloadIntegrityFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof IllegalArgumentException
                    && message != null
                    && (message.startsWith("EXOMISER_FEATURE_")
                    || message.startsWith("EXOMISER_RANK_SEMANTICS_INVALID")
                    || message.startsWith("EXOMISER_NORMALIZED_MOI_INVALID")
                    || message.startsWith("EXOMISER_MOI_NORMALIZATION_")
                    || message.startsWith("ANALYSIS_VCF_CALL_"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsNonNullStructuredPayload(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            return hasNonNullField(root, "exomiserCandidateFeatures")
                    || hasNonNullField(root, "analysisVcfCall");
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean hasNonNullField(JsonNode root, String fieldName) {
        return root != null && root.has(fieldName) && !root.get(fieldName).isNull();
    }

    private CandidateIdentity legacySummaryIdentity(
            ResultSet resultSet,
            VariantInterpretation summary) throws SQLException {
        String persistedCanonicalId = resultSet.getString("canonical_variant_id");
        String summaryCanonicalId = summary.getCanonicalVariantId();
        String canonicalId = persistedCanonicalId == null || persistedCanonicalId.trim().isEmpty()
                ? summaryCanonicalId
                : persistedCanonicalId.trim();
        if (!canonicalId.equals(summaryCanonicalId)) {
            throw new IllegalStateException("PERSISTED_CANONICAL_VARIANT_ID_MISMATCH");
        }
        return CandidateIdentity.legacy(resultSet.getString("candidate_id"), canonicalId);
    }

    private VariantAnalysisBucket persistedBucket(String persistedBucket, VariantInterpretation summary) {
        if (persistedBucket != null && !persistedBucket.trim().isEmpty()) {
            try {
                return VariantAnalysisBucket.valueOf(persistedBucket.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                LOGGER.debug("Unknown persisted analysis bucket '{}'; deriving from tier", persistedBucket);
            }
        }
        return bucketFromTier(summary.getPriorityTier(), summary.getObservation());
    }

    private boolean nullableBoolean(ResultSet resultSet, String column, boolean defaultValue) throws SQLException {
        boolean value = resultSet.getBoolean(column);
        return resultSet.wasNull() ? defaultValue : value;
    }

    private List<String> readReasonText(String reasonText) {
        if (reasonText == null || reasonText.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> reasons = new ArrayList<String>();
        for (String line : reasonText.split("\\r?\\n")) {
            if (line != null && !line.trim().isEmpty()) {
                reasons.add(line.trim());
            }
        }
        return reasons;
    }

    private void copySummaryFieldsFromPayload(VariantInterpretation summary,
                                              VariantInterpretation payload,
                                              boolean copyLargeFileClassification) {
        if (summary == null || payload == null) {
            return;
        }
        try {
            if (copyLargeFileClassification) {
                summary.copyLargeFileClassificationFrom(payload);
            }
            summary.addEvidence(payload.getEvidence());
            List<TranscriptConsequence> transcriptAnnotations = payload.getTranscriptAnnotations();
            if (transcriptAnnotations != null && !transcriptAnnotations.isEmpty()) {
                summary.applyTranscriptAnnotations(transcriptAnnotations);
            }
        } catch (RuntimeException ex) {
            LOGGER.debug("Could not read summary fields from interpretation payload: {}",
                    rootCauseMessage(ex));
        }
    }

    private VariantAnalysisBucket bucketFromTier(String tier, VariantQcObservation observation) {
        if ("KNOWN_CLINICAL".equals(tier)) {
            return VariantAnalysisBucket.KNOWN_CLINICAL;
        }
        if ("HIGH_PRIORITY".equals(tier)) {
            return VariantAnalysisBucket.HIGH_PRIORITY;
        }
        if ("REVIEW".equals(tier)) {
            return VariantAnalysisBucket.REVIEW;
        }
        if ("FILTERED_COMMON".equals(tier) || "POPULATION_FILTERED".equals(tier)) {
            return VariantAnalysisBucket.FILTERED_COMMON;
        }
        if ("FILTERED_QC".equals(tier)) {
            return observation != null && !carriesAlternateAllele(observation.getGenotype())
                    ? VariantAnalysisBucket.FILTERED_NOT_CARRIED
                    : VariantAnalysisBucket.FILTERED_QC;
        }
        return VariantAnalysisBucket.CANDIDATE;
    }

    private VariantQcObservation readObservationSummary(ResultSet resultSet) throws SQLException {
        String genomeAssembly = resultSet.getString("genome_assembly");
        String chromosome = resultSet.getString("chromosome");
        String reference = resultSet.getString("reference_allele");
        String alternate = resultSet.getString("alternate_allele");
        NormalizedVariantKey key = genomeAssembly == null || chromosome == null
                || reference == null || alternate == null
                ? null
                : new NormalizedVariantKey(
                        genomeAssembly,
                        chromosome,
                        resultSet.getInt("variant_position"),
                        reference,
                        alternate
                );
        return new VariantQcObservation(
                key,
                resultSet.getString("sample_id"),
                resultSet.getString("genotype"),
                null,
                null,
                null,
                null,
                resultSet.getString("filter_status"),
                resultSet.getString("variant_type"),
                null
        );
    }

    private EvidenceItem summaryEvidence(String source,
                                         String version,
                                         Double score,
                                         String label,
                                         String summary) {
        return new EvidenceItem(
                source,
                version,
                "COMPUTATIONAL",
                "APPLICABLE",
                score,
                label,
                summary,
                Collections.emptyMap()
        );
    }

    private void insertObservationChunk(String id,
                                        List<VariantQcObservation> observations,
                                        int start,
                                        int end) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[end - start];
        for (int index = start; index < end; index++) {
            int suffix = index - start;
            batch[suffix] = observationParameters(id, index, observations.get(index));
        }
        jdbcTemplate.batchUpdate(INSERT_OBSERVATION_SQL, batch);
    }

    private MapSqlParameterSource observationParameters(String id,
                                                       int variantOrder,
                                                       VariantQcObservation observation) {
        NormalizedVariantKey key = observation.getKey();
        return new MapSqlParameterSource()
                .addValue("analysisId", id)
                .addValue("variantOrder", variantOrder)
                .addValue("genomeAssembly", key.getGenomeAssembly())
                .addValue("chromosome", key.getChromosome())
                .addValue("variantPosition", key.getPosition())
                .addValue("referenceAllele", key.getReference())
                .addValue("alternateAllele", key.getAlternate())
                .addValue("sampleId", observation.getSampleId())
                .addValue("genotype", observation.getGenotype())
                .addValue("depth", observation.getDepth())
                .addValue("genotypeQuality", observation.getGenotypeQuality())
                .addValue("alleleDepths", observation.getAlleleDepths())
                .addValue("alleleBalance", observation.getAlleleBalance())
                .addValue("filterStatus", observation.getFilterStatus())
                .addValue("variantType", observation.getVariantType())
                .addValue("originalRecord", observation.getOriginalRecord());
    }

    private void insertInterpretationChunk(String id,
                                           List<VariantInterpretation> interpretations,
                                           int start,
                                           int end) {
        int totalInterpretations = interpretations.size();
        int maxBatchBytes = properties.getPersistence().normalizedInterpretationBatchMaxBytes();
        List<MapSqlParameterSource> batch = new ArrayList<MapSqlParameterSource>(end - start);
        int estimatedBatchBytes = 0;
        for (int index = start; index < end; index++) {
            VariantInterpretation interpretation = interpretations.get(index);
            MapSqlParameterSource parameters = interpretationParameters(
                    id,
                    index,
                    interpretation,
                    totalInterpretations
            );
            int rowBytes = estimatedInterpretationBatchBytes(parameters);
            if (!batch.isEmpty() && maxBatchBytes > 0 && estimatedBatchBytes + rowBytes > maxBatchBytes) {
                flushInterpretationBatch(batch);
                batch.clear();
                estimatedBatchBytes = 0;
            }
            batch.add(parameters);
            estimatedBatchBytes += rowBytes;
        }
        flushInterpretationBatch(batch);
    }

    private MapSqlParameterSource interpretationParameters(String id,
                                                           int variantOrder,
                                                           VariantInterpretation interpretation,
                                                           int totalInterpretations) {
        VariantQcObservation observation = interpretation.getObservation();
        NormalizedVariantKey key = observation.getKey();
        boolean compactPayload = shouldWriteCompactPayload(interpretation, totalInterpretations);
        return new MapSqlParameterSource()
                .addValue("analysisId", id)
                .addValue("variantOrder", variantOrder)
                .addValue("candidateId", interpretation.getCandidateId())
                .addValue("canonicalVariantId", interpretation.getCanonicalVariantId())
                .addValue("variantId", interpretation.getVariantId())
                .addValue("priorityTier", interpretation.getPriorityTier())
                .addValue("rankingScore", interpretation.getRankingScore())
                .addValue("genomeAssembly", key.getGenomeAssembly())
                .addValue("chromosome", key.getChromosome())
                .addValue("variantPosition", key.getPosition())
                .addValue("referenceAllele", key.getReference())
                .addValue("alternateAllele", key.getAlternate())
                .addValue("sampleId", observation.getSampleId())
                .addValue("genotype", observation.getGenotype())
                .addValue("filterStatus", observation.getFilterStatus())
                .addValue("variantType", observation.getVariantType())
                .addValue("consequencesJson", writeConsequences(interpretation.getConsequences()))
                .addValue("caddScore", applicableScore(interpretation, "CADD"))
                .addValue("alphaMissenseScore", applicableScore(interpretation, "ALPHAMISSENSE"))
                .addValue("alphaGenomeScore", applicableScore(interpretation, "ALPHAGENOME"))
                .addValue("carriedAlt", carriesAlternateAllele(observation.getGenotype()))
                .addValue(
                        "analysisBucket",
                        interpretation.getAnalysisBucket() == null ? null : interpretation.getAnalysisBucket().name()
                )
                .addValue("summaryOnly", interpretation.isSummaryOnly())
                .addValue("largeFileMode", interpretation.isLargeFileMode())
                .addValue("expensiveAnnotationSkipped", interpretation.isExpensiveAnnotationSkipped())
                .addValue("expensiveAnnotationSkippedReason", interpretation.getExpensiveAnnotationSkippedReason())
                .addValue("cheapGateReasonText", joinReasonText(interpretation.getCheapGateReasons()))
                .addValue("fullInterpretationAvailable", !compactPayload)
                .addValue("payloadJson", writeInterpretationPayload(interpretation, totalInterpretations));
    }

    private String joinReasonText(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String reason : reasons) {
            if (reason == null || reason.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(reason.trim());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private int estimatedInterpretationBatchBytes(MapSqlParameterSource parameters) {
        long bytes = 1024L;
        bytes += estimatedUtf8Bytes(parameters.getValue("variantId"));
        bytes += estimatedUtf8Bytes(parameters.getValue("candidateId"));
        bytes += estimatedUtf8Bytes(parameters.getValue("canonicalVariantId"));
        bytes += estimatedUtf8Bytes(parameters.getValue("genomeAssembly"));
        bytes += estimatedUtf8Bytes(parameters.getValue("chromosome"));
        bytes += estimatedUtf8Bytes(parameters.getValue("referenceAllele"));
        bytes += estimatedUtf8Bytes(parameters.getValue("alternateAllele"));
        bytes += estimatedUtf8Bytes(parameters.getValue("sampleId"));
        bytes += estimatedUtf8Bytes(parameters.getValue("genotype"));
        bytes += estimatedUtf8Bytes(parameters.getValue("filterStatus"));
        bytes += estimatedUtf8Bytes(parameters.getValue("variantType"));
        bytes += estimatedUtf8Bytes(parameters.getValue("consequencesJson"));
        bytes += estimatedUtf8Bytes(parameters.getValue("payloadJson"));
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    private int estimatedUtf8Bytes(Object value) {
        if (!(value instanceof CharSequence)) {
            return 0;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private void flushInterpretationBatch(List<MapSqlParameterSource> batch) {
        if (batch.isEmpty()) {
            return;
        }
        SqlParameterSource[] parameters = batch.toArray(new SqlParameterSource[0]);
        jdbcTemplate.batchUpdate(
                INSERT_INTERPRETATION_SQL,
                Objects.requireNonNull(parameters, "parameters")
        );
    }

    private Double applicableScore(VariantInterpretation interpretation, String source) {
        for (EvidenceItem evidence : interpretation.getEvidence()) {
            if (source.equals(evidence.getSource()) && "APPLICABLE".equals(evidence.getApplicability())) {
                return evidence.getRawScore();
            }
        }
        return null;
    }

    private boolean carriesAlternateAllele(String genotype) {
        if (genotype == null || genotype.trim().isEmpty()) {
            return true;
        }
        String[] alleles = genotype.trim().split("[/|]");
        boolean sawCalledAllele = false;
        for (String allele : alleles) {
            if (allele == null || allele.trim().isEmpty() || ".".equals(allele.trim())) {
                continue;
            }
            sawCalledAllele = true;
            if (!"0".equals(allele.trim())) {
                return true;
            }
        }
        return !sawCalledAllele;
    }
}
