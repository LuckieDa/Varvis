package org.nbscn.varvis.variantanalysis.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLNonTransientConnectionException;
import java.sql.ResultSet;
import java.time.Duration;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
class JdbcVariantAnalysisJobRepositoryTest {

    @Test
    void uploadHistoryUsesExactDigestPredicateAndBoundedDatabaseQueryAcrossStatuses() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<?>>any())).thenReturn(Collections.emptyList());
        var repository = repository(jdbcTemplate, 10);
        String sha = "a".repeat(64);

        assertTrue(repository.findHistoryByFileSha256(sha).isEmpty());

        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(sql.capture(), parameters.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<?>>any());
        String query = sql.getValue().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertTrue(query.contains("where file_sha256 = :filesha256"));
        assertTrue(query.contains("order by created_at desc, id desc"));
        assertTrue(query.contains("limit 20"));
        assertFalse(query.contains("status ="));
        assertEquals(sha, parameters.getValue().getValue("fileSha256"));
        verify(jdbcTemplate, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void aggregatesCandidateCountsWithOneDatabaseQuery() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        VariantInterpretationCounts expected = new VariantInterpretationCounts(13, 81, 100);
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretationCounts>>any()
        )).thenReturn(expected);

        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);
        VariantInterpretationCounts actual = repository.countInterpretationViews("analysis");

        assertEquals(13, actual.getCandidates());
        assertEquals(81, actual.getFrequencyFiltered());
        assertEquals(100, actual.getAll());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
                sqlCaptor.capture(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretationCounts>>any()
        );
        assertTrue(sqlCaptor.getValue().contains("analysis_bucket"));
        assertTrue(sqlCaptor.getValue().contains("AS derived_bucket"));
        assertTrue(sqlCaptor.getValue().contains("FROM (SELECT"));
        assertTrue(sqlCaptor.getValue().contains("summary_only_count"));
        assertTrue(sqlCaptor.getValue().contains("full_interpretation_count"));
        assertTrue(sqlCaptor.getValue().contains("carried_alt_count"));
        assertTrue(sqlCaptor.getValue().contains("not_carried_alt_count"));
    }

    @Test
    void legacyReadyLookupHasNoTerminalLimitButDoesNotProveScientificEquivalence() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<?>>any()
        )).thenReturn(Collections.emptyList());

        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);
        repository.findReusableReadyJobs("sha", "GRCh38", "WES", null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<?>>any()
        );
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("file_sha256 = :fileSha256"));
        assertTrue(sql.contains("status = 'READY'"));
        assertTrue(sql.contains("ORDER BY updated_at DESC"));
        assertFalse(sql.contains("ORDER BY updated_at DESC LIMIT 1"));
    }

    @Test
    void deletesLargeAnalysisChildrenInBoundedChunksBeforeParent() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        Map<String, Integer> calls = new HashMap<String, Integer>();
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("DELETE FROM variant_analysis_job")) {
                return 1;
            }
            String table = sql.split(" ")[2];
            int call = calls.containsKey(table) ? calls.get(table) : 0;
            calls.put(table, call + 1);
            return call == 0 ? 2 : 0;
        });

        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 2);
        repository.deleteById("analysis");

        assertEquals(2, calls.get("variant_analysis_interpretation").intValue());
        assertEquals(2, calls.get("variant_analysis_observation").intValue());
        assertEquals(2, calls.get("variant_analysis_enhancement").intValue());
        assertEquals(2, calls.get("variant_analysis_event").intValue());
        assertEquals(2, calls.get("exomiser_analysis_job").intValue());
        verify(jdbcTemplate).update(
                eq("DELETE FROM variant_analysis_job WHERE id = :id"),
                any(SqlParameterSource.class)
        );
    }

    @Test
    void retriesChunkDeletionAfterTransientLockTimeout() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AtomicInteger interpretationDeleteAttempts = new AtomicInteger();
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("DELETE FROM variant_analysis_interpretation")) {
                if (interpretationDeleteAttempts.incrementAndGet() == 1) {
                    throw new CannotAcquireLockException("lock wait timeout");
                }
                return 0;
            }
            return sql.startsWith("DELETE FROM variant_analysis_job") ? 1 : 0;
        });

        JdbcVariantAnalysisJobRepository repository = repository(
                jdbcTemplate,
                2,
                500,
                500,
                3,
                Duration.ZERO
        );
        repository.deleteById("analysis");

        assertEquals(2, interpretationDeleteAttempts.get());
        verify(jdbcTemplate).update(
                eq("DELETE FROM variant_analysis_job WHERE id = :id"),
                any(SqlParameterSource.class)
        );
    }

    @Test
    void updatesTranscriptAnnotationsInBoundedTransactions() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        )).thenReturn(Collections.singletonList(
                interpretation("existing", observation("chr1", 100, "A", "G"))
        ));
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(
                invocation -> new SimpleTransactionStatus()
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setBatchSize(500);
        JdbcVariantAnalysisJobRepository repository = new JdbcVariantAnalysisJobRepository(
                jdbcTemplate,
                transactionManager,
                properties,
                new ObjectMapper()
        );
        Map<String, List<TranscriptConsequence>> annotations =
                new LinkedHashMap<String, List<TranscriptConsequence>>();
        for (int index = 0; index < 51; index++) {
            annotations.put("variant-" + index, Collections.emptyList());
        }

        repository.updateTranscriptAnnotations("analysis", annotations);

        verify(transactionManager, times(2)).getTransaction(any());
        verify(jdbcTemplate, times(51)).update(
                eq("UPDATE variant_analysis_interpretation "
                        + "SET payload_json = :payloadJson "
                        + "WHERE analysis_id = :id AND candidate_id = :candidateId"),
                any(SqlParameterSource.class)
        );
    }

    @Test
    void updatesDuplicateCanonicalRowsByCandidateIdWithoutCopyingFirstPayload() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        VariantInterpretation dominant = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        "GRCh38|1|100|A|T", 1, "GENE1", "AUTOSOMAL_DOMINANT", 1
                ),
                observation("1", 100, "A", "T"),
                Collections.emptyList(),
                Collections.emptyList()
        );
        VariantInterpretation recessive = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        "GRCh38|1|100|A|T", 2, "GENE1", "AUTOSOMAL_RECESSIVE", 2
                ),
                observation("1", 100, "A", "T"),
                Collections.emptyList(),
                Collections.emptyList()
        );
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        )).thenAnswer(invocation -> {
            RowMapper<VariantInterpretation> mapper = invocation.getArgument(2);
            ResultSet first = mock(ResultSet.class);
            when(first.getString("candidate_id")).thenReturn(dominant.getCandidateId());
            when(first.getString("canonical_variant_id")).thenReturn(dominant.getCanonicalVariantId());
            when(first.getString("payload_json")).thenReturn(objectMapper.writeValueAsString(dominant));
            ResultSet second = mock(ResultSet.class);
            when(second.getString("candidate_id")).thenReturn(recessive.getCandidateId());
            when(second.getString("canonical_variant_id")).thenReturn(recessive.getCanonicalVariantId());
            when(second.getString("payload_json")).thenReturn(objectMapper.writeValueAsString(recessive));
            return Arrays.asList(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
        });
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);
        Map<String, List<TranscriptConsequence>> annotations =
                new LinkedHashMap<String, List<TranscriptConsequence>>();
        annotations.put("GRCh38|1|100|A|T", Collections.singletonList(
                new TranscriptConsequence("GENE1", null, "NM_1", null, null, "missense_variant")
        ));

        repository.updateTranscriptAnnotations("analysis", annotations);

        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate, times(2)).update(
                eq("UPDATE variant_analysis_interpretation "
                        + "SET payload_json = :payloadJson "
                        + "WHERE analysis_id = :id AND candidate_id = :candidateId"),
                parameters.capture()
        );
        Set<String> candidateIds = new HashSet<String>();
        Set<String> modes = new HashSet<String>();
        for (SqlParameterSource value : parameters.getAllValues()) {
            candidateIds.add(String.valueOf(value.getValue("candidateId")));
            JsonNode payload = objectMapper.readTree(String.valueOf(value.getValue("payloadJson")));
            modes.add(payload.path("candidateIdentity").path("modeOfInheritance").asText());
            assertEquals(1, payload.path("transcriptAnnotations").size());
        }
        assertEquals(new HashSet<String>(Arrays.asList(
                dominant.getCandidateId(), recessive.getCandidateId())), candidateIds);
        assertEquals(new HashSet<String>(Arrays.asList(
                "AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE")), modes);
    }

    @Test
    void readsLegacyEvidencePayloadWithoutDatabaseRewrite() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        String legacyPayload = legacyPayload();
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        )).thenAnswer(invocation -> {
            RowMapper<VariantInterpretation> rowMapper = invocation.getArgument(2);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("payload_json")).thenReturn(legacyPayload);
            return Collections.singletonList(rowMapper.mapRow(resultSet, 0));
        });
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);

        List<VariantInterpretation> values = repository.findInterpretations("analysis", 0, 1);

        assertEquals(1, values.size());
        EvidenceItem noRecord = values.get(0).getEvidence().get(0);
        EvidenceItem unavailable = values.get(0).getEvidence().get(1);
        assertEquals(EvidenceItem.CURRENT_SCHEMA_VERSION, noRecord.getSchemaVersion());
        assertEquals("GRCh38|1|101|A|G", noRecord.getCanonicalVariantId());
        assertEquals("NO_RECORD", noRecord.getEffectiveStatus());
        assertEquals(null, noRecord.getRawScore());
        assertTrue(noRecord.getProvenance().isEmpty());
        assertEquals("UNAVAILABLE", unavailable.getEffectiveStatus());
        assertEquals(null, unavailable.getRawScore());
        assertTrue(unavailable.getProvenance().isEmpty());
        verify(jdbcTemplate, times(0)).update(anyString(), any(SqlParameterSource.class));
    }

    private String legacyPayload() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/variantanalysis/evidence/legacy-evidence-payload-v0.json")) {
            assertTrue(input != null, "Synthetic legacy payload fixture must be present");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsCurrentEvidencePayloadWithoutChangingStoredIdentity() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        VariantInterpretation source = new VariantInterpretation(
                "current",
                observation("1", 100, "A", "G"),
                Collections.emptyList(),
                Collections.singletonList(new EvidenceItem(
                        "GNOMAD",
                        "gnomAD 4.1",
                        "POPULATION",
                        "NO_RECORD",
                        null,
                        "No record",
                        "Current payload",
                        Collections.emptyMap()
                ))
        );
        String payload = objectMapper.writeValueAsString(source);
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        )).thenAnswer(invocation -> {
            RowMapper<VariantInterpretation> rowMapper = invocation.getArgument(2);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("payload_json")).thenReturn(payload);
            return Collections.singletonList(rowMapper.mapRow(resultSet, 0));
        });
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);

        EvidenceItem restored = repository.findInterpretations("analysis", 0, 1)
                .get(0)
                .getEvidence()
                .get(0);

        assertEquals(EvidenceItem.CURRENT_SCHEMA_VERSION, restored.getSchemaVersion());
        assertEquals("GRCh38|1|100|A|G", restored.getCanonicalVariantId());
        assertEquals("GRCh38", restored.getAssembly());
        assertEquals("NO_RECORD", restored.getStatus());
        verify(jdbcTemplate, times(0)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void persistsObservationsWithPreparedBatchInsteadOfGeneratedMultiValueSql() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            SqlParameterSource[] batch = invocation.getArgument(1);
            assertTrue(sql.startsWith("INSERT INTO variant_analysis_observation"));
            assertFalse(sql.contains(":analysisId0"));
            assertTrue(batch[0].hasValue("analysisId"));
            calls.incrementAndGet();
            rows.addAndGet(batch.length);
            return new int[batch.length];
        });

        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10000, 2, 2);
        repository.replaceObservations(
                "analysis",
                Arrays.asList(
                        observation("chr1", 100, "A", "G"),
                        observation("chr1", 200, "C", "T"),
                        observation("chr2", 300, "G", "A")
                )
        );

        assertEquals(2, calls.get());
        assertEquals(3, rows.get());
    }

    @Test
    void canSkipRedundantObservationRowPersistenceForLargeAnalyses() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

        JdbcVariantAnalysisJobRepository repository =
                repository(jdbcTemplate, 10000, 2, 2, 3, Duration.ofSeconds(1), 1048576, 100000, false);
        repository.replaceObservations(
                "analysis",
                Arrays.asList(
                        observation("chr1", 100, "A", "G"),
                        observation("chr1", 200, "C", "T")
                )
        );

        verify(jdbcTemplate).update(
                eq("DELETE FROM variant_analysis_observation WHERE analysis_id = :id"),
                any(SqlParameterSource.class)
        );
        verify(jdbcTemplate, times(0)).batchUpdate(
                anyString(),
                any(SqlParameterSource[].class)
        );
    }

    @Test
    void persistsInterpretationsWithPreparedBatchInsteadOfGeneratedMultiValueSql() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            SqlParameterSource[] batch = invocation.getArgument(1);
            assertTrue(sql.startsWith("INSERT INTO variant_analysis_interpretation"));
            assertFalse(sql.contains(":analysisId0"));
            assertTrue(batch[0].hasValue("payloadJson"));
            calls.incrementAndGet();
            rows.addAndGet(batch.length);
            return new int[batch.length];
        });

        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10000, 2, 2);
        repository.replaceInterpretations(
                "analysis",
                Arrays.asList(
                        interpretation("v1", observation("chr1", 100, "A", "G")),
                        interpretation("v2", observation("chr1", 200, "C", "T")),
                        interpretation("v3", observation("chr2", 300, "G", "A"))
                )
        );

        assertEquals(2, calls.get());
        assertEquals(3, rows.get());
    }

    @Test
    void validatesCandidateIdentityBeforeDeletingExistingInterpretations() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10000, 2, 2);
        VariantQcObservation observation = observation("chr1", 100, "A", "G");
        CandidateIdentity duplicateIdentity = CandidateIdentity.exomiser(
                observation.getKey().canonicalId(),
                1,
                "GENE1",
                "AUTOSOMAL_DOMINANT",
                1
        );
        List<VariantInterpretation> invalid = Arrays.asList(
                new VariantInterpretation(
                        duplicateIdentity,
                        observation,
                        Collections.emptyList(),
                        Collections.emptyList()
                ),
                new VariantInterpretation(
                        duplicateIdentity,
                        observation,
                        Collections.emptyList(),
                        Collections.emptyList()
                )
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> repository.replaceInterpretations("analysis", invalid)
        );

        assertTrue(failure.getMessage().startsWith("DUPLICATE_CANDIDATE_ID:"));
        verify(jdbcTemplate, never()).update(
                eq("DELETE FROM variant_analysis_interpretation WHERE analysis_id = :id"),
                any(SqlParameterSource.class)
        );
    }

    @Test
    void splitsInterpretationBatchesByEstimatedPayloadBytes() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenAnswer(invocation -> {
            SqlParameterSource[] batch = invocation.getArgument(1);
            assertEquals(1, batch.length);
            calls.incrementAndGet();
            rows.addAndGet(batch.length);
            return new int[batch.length];
        });

        JdbcVariantAnalysisJobRepository repository =
                repository(jdbcTemplate, 10000, 3, 3, 3, Duration.ofSeconds(1), 1);
        repository.replaceInterpretations(
                "analysis",
                Arrays.asList(
                        interpretation("v1", observation("chr1", 100, "A", "G")),
                        interpretation("v2", observation("chr1", 200, "C", "T")),
                        interpretation("v3", observation("chr2", 300, "G", "A"))
                )
        );

        assertEquals(3, calls.get());
        assertEquals(3, rows.get());
    }

    @Test
    void summaryOnlyInterpretationPayloadOmitsDuplicatedOriginalRecord() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenAnswer(invocation -> new int[((SqlParameterSource[]) invocation.getArgument(1)).length]);
        String originalRecord = "chr1\t100\t.\tA\tG\t.\tPASS\t"
                + String.join("", Collections.nCopies(200, "LONG_INFO_FIELD;"));

        JdbcVariantAnalysisJobRepository repository =
                repository(jdbcTemplate, 10000, 2, 2, 3, Duration.ofSeconds(1), 1048576, 1);
        repository.replaceInterpretations(
                "analysis",
                Arrays.asList(
                        summaryOnlyInterpretation("v1", observation("chr1", 100, "A", "G", originalRecord)),
                        summaryOnlyInterpretation("v2", observation("chr1", 200, "C", "T", originalRecord))
                )
        );

        ArgumentCaptor<SqlParameterSource[]> batchCaptor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate).batchUpdate(anyString(), batchCaptor.capture());
        String payloadJson = (String) batchCaptor.getValue()[0].getValue("payloadJson");
        JsonNode payload = new ObjectMapper().readTree(payloadJson);
        assertTrue(payload.path("observation").path("originalRecord").isNull());
        assertFalse(payload.path("fullInterpretationAvailable").asBoolean(true));
        assertFalse(payloadJson.contains("LONG_INFO_FIELD"));
    }

    @Test
    void canSkipLargeFileSummaryOnlyInterpretationRows() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        AtomicInteger rows = new AtomicInteger();
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenAnswer(invocation -> {
            SqlParameterSource[] batch = invocation.getArgument(1);
            rows.addAndGet(batch.length);
            return new int[batch.length];
        });

        JdbcVariantAnalysisJobRepository repository =
                repository(jdbcTemplate, 10000, 2, 2, 3, Duration.ofSeconds(1), 1048576, 1, false, false);
        repository.replaceInterpretations(
                "analysis",
                Arrays.asList(
                        summaryOnlyInterpretation("summary", observation("chr1", 100, "A", "G")),
                        interpretation("candidate", observation("chr1", 200, "C", "T"))
                )
        );

        assertEquals(1, rows.get());
    }

    @Test
    void retriesTransientInterpretationBatchFailure() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenThrow(new TransientDataAccessResourceException("temporary connection failure"))
                .thenReturn(new int[] {1});

        JdbcVariantAnalysisJobRepository repository =
                repository(jdbcTemplate, 10000, 1, 1, 2, Duration.ZERO);
        repository.replaceInterpretations(
                "analysis",
                Collections.singletonList(
                        interpretation("v1", observation("chr1", 100, "A", "G"))
                )
        );

        verify(jdbcTemplate, times(2)).batchUpdate(
                anyString(),
                any(SqlParameterSource[].class)
        );
    }

    @Test
    void retriesClosedConnectionInterpretationBatchFailure() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenThrow(new RuntimeException(new SQLNonTransientConnectionException(
                        "No operations allowed after connection closed",
                        "08003"
                )))
                .thenReturn(new int[] {1});

        JdbcVariantAnalysisJobRepository repository =
                repository(jdbcTemplate, 10000, 1, 1, 2, Duration.ZERO);
        repository.replaceInterpretations(
                "analysis",
                Collections.singletonList(
                        interpretation("v1", observation("chr1", 100, "A", "G"))
                )
        );

        verify(jdbcTemplate, times(2)).batchUpdate(
                anyString(),
                any(SqlParameterSource[].class)
        );
    }

    @Test
    void findInterpretationSummariesUsesLightweightColumnsAndCurrentPagePayloadClassification() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        )).thenReturn(Collections.emptyList());
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);

        repository.findInterpretationSummaries(
                "analysis",
                0,
                50,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        );
        assertTrue(sqlCaptor.getValue().contains("payload_json"));
        assertTrue(sqlCaptor.getValue().contains("consequences_json"));
        assertTrue(sqlCaptor.getValue().contains("LIMIT :limit OFFSET :offset"));
    }

    @Test
    void geneLikeSearchUsesBroadPayloadPrefilterBeforeExactJavaMatch() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        )).thenReturn(Collections.emptyList());
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);

        repository.countInterpretations(
                "analysis",
                VariantInterpretationView.CANDIDATES,
                new VariantInterpretationFilter(null, false, false, null, "PPA2")
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parametersCaptor =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                parametersCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        );
        String sql = sqlCaptor.getValue();
        SqlParameterSource parameters = parametersCaptor.getValue();
        assertTrue(sql.contains("payload_json"));
        assertTrue(sql.contains("consequences_json"));
        assertTrue(sql.contains("payload_json, '')) LIKE :search"));
        assertFalse(sql.contains(":geneSymbolSearch"));
        assertEquals("%ppa2%", parameters.getValue("search"));
    }

    @Test
    void coordinateLikeSearchKeepsGenericPayloadSearch() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        )).thenReturn(Collections.emptyList());
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);

        repository.countInterpretations(
                "analysis",
                VariantInterpretationView.CANDIDATES,
                new VariantInterpretationFilter(null, false, false, null, "c.392A>G")
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        );
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("payload_json"));
        assertTrue(sql.contains("LIKE :search"));
        assertFalse(sql.contains(":geneSymbolSearch"));
    }

    @Test
    void countInterpretationsAddsPhenotypeSupportPredicate() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(SqlParameterSource.class),
                eq(Integer.class)
        )).thenReturn(2);
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);

        int count = repository.countInterpretations(
                "analysis",
                VariantInterpretationView.CANDIDATES,
                phenotypeFilter(true, null, null)
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
                sqlCaptor.capture(),
                any(SqlParameterSource.class),
                eq(Integer.class)
        );
        assertEquals(2, count);
        assertTrue(sqlCaptor.getValue().contains("phenotype_support"));
        assertTrue(sqlCaptor.getValue().contains("phenotype_gene_prior"));
    }

    @Test
    void largeFileExecutionFiltersUsePersistedColumnsWithoutPostQueryPayloadScan() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(SqlParameterSource.class),
                eq(Integer.class)
        )).thenReturn(1);
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);

        int count = repository.countInterpretations(
                "analysis",
                VariantInterpretationView.ALL,
                new VariantInterpretationFilter(
                        null,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "FILTERED_COMMON",
                        true,
                        true,
                        true
                )
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
                sqlCaptor.capture(),
                any(SqlParameterSource.class),
                eq(Integer.class)
        );
        assertEquals(1, count);
        assertTrue(sqlCaptor.getValue().contains("analysis_bucket"));
        assertTrue(sqlCaptor.getValue().contains("summary_only = :summaryOnly"));
        assertTrue(sqlCaptor.getValue().contains("large_file_mode = :largeFileMode"));
        assertTrue(sqlCaptor.getValue().contains("expensive_annotation_skipped = :expensiveAnnotationSkipped"));
        assertFalse(sqlCaptor.getValue().contains("analysisbucket"));
    }

    @Test
    void minPhenotypeScoreUsesPostQueryFilteringWithoutChangingOrderSql() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        )).thenReturn(Collections.emptyList());
        JdbcVariantAnalysisJobRepository repository = repository(jdbcTemplate, 10);

        int count = repository.countInterpretations(
                "analysis",
                VariantInterpretationView.CANDIDATES,
                phenotypeFilter(null, "MECP2", 0.8d)
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<VariantInterpretation>>any()
        );
        assertEquals(0, count);
        assertTrue(sqlCaptor.getValue().contains("phenotype_support"));
        assertTrue(sqlCaptor.getValue().contains("payload_json"));
        assertTrue(sqlCaptor.getValue().contains("ORDER BY ranking_score DESC, variant_order ASC"));
        assertFalse(sqlCaptor.getValue().contains("LIMIT"));
    }

    private JdbcVariantAnalysisJobRepository repository(NamedParameterJdbcTemplate jdbcTemplate,
                                                        int deletionBatchSize) {
        return repository(jdbcTemplate, deletionBatchSize, 500, 500);
    }

    private JdbcVariantAnalysisJobRepository repository(NamedParameterJdbcTemplate jdbcTemplate,
                                                         int deletionBatchSize,
                                                         int observationBatchSize,
                                                         int interpretationBatchSize) {
        return repository(
                jdbcTemplate,
                deletionBatchSize,
                observationBatchSize,
                interpretationBatchSize,
                3,
                Duration.ofSeconds(1)
        );
    }

    private JdbcVariantAnalysisJobRepository repository(NamedParameterJdbcTemplate jdbcTemplate,
                                                         int deletionBatchSize,
                                                         int observationBatchSize,
                                                         int interpretationBatchSize,
                                                         int retryAttempts,
                                                         Duration retryBackoff) {
        return repository(
                jdbcTemplate,
                deletionBatchSize,
                observationBatchSize,
                interpretationBatchSize,
                retryAttempts,
                retryBackoff,
                1048576
        );
    }

    private JdbcVariantAnalysisJobRepository repository(NamedParameterJdbcTemplate jdbcTemplate,
                                                         int deletionBatchSize,
                                                         int observationBatchSize,
                                                         int interpretationBatchSize,
                                                         int retryAttempts,
                                                         Duration retryBackoff,
                                                         int interpretationBatchMaxBytes) {
        return repository(
                jdbcTemplate,
                deletionBatchSize,
                observationBatchSize,
                interpretationBatchSize,
                retryAttempts,
                retryBackoff,
                interpretationBatchMaxBytes,
                100000
        );
    }

    private JdbcVariantAnalysisJobRepository repository(NamedParameterJdbcTemplate jdbcTemplate,
                                                         int deletionBatchSize,
                                                         int observationBatchSize,
                                                         int interpretationBatchSize,
                                                         int retryAttempts,
                                                         Duration retryBackoff,
                                                         int interpretationBatchMaxBytes,
                                                         int compactModeThreshold) {
        return repository(
                jdbcTemplate,
                deletionBatchSize,
                observationBatchSize,
                interpretationBatchSize,
                retryAttempts,
                retryBackoff,
                interpretationBatchMaxBytes,
                compactModeThreshold,
                true
        );
    }

    private JdbcVariantAnalysisJobRepository repository(NamedParameterJdbcTemplate jdbcTemplate,
                                                         int deletionBatchSize,
                                                         int observationBatchSize,
                                                         int interpretationBatchSize,
                                                         int retryAttempts,
                                                         Duration retryBackoff,
                                                         int interpretationBatchMaxBytes,
                                                         int compactModeThreshold,
                                                         boolean persistObservations) {
        return repository(
                jdbcTemplate,
                deletionBatchSize,
                observationBatchSize,
                interpretationBatchSize,
                retryAttempts,
                retryBackoff,
                interpretationBatchMaxBytes,
                compactModeThreshold,
                persistObservations,
                true
        );
    }

    private JdbcVariantAnalysisJobRepository repository(NamedParameterJdbcTemplate jdbcTemplate,
                                                         int deletionBatchSize,
                                                         int observationBatchSize,
                                                         int interpretationBatchSize,
                                                         int retryAttempts,
                                                         Duration retryBackoff,
                                                         int interpretationBatchMaxBytes,
                                                         int compactModeThreshold,
                                                         boolean persistObservations,
                                                         boolean persistSummaryOnlyInterpretations) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(
                invocation -> new SimpleTransactionStatus()
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPersistence().setDeletionBatchSize(deletionBatchSize);
        properties.getPersistence().setObservationBatchSize(observationBatchSize);
        properties.getPersistence().setInterpretationBatchSize(interpretationBatchSize);
        properties.getPersistence().setInterpretationBatchMaxBytes(interpretationBatchMaxBytes);
        properties.getPersistence().setRetryAttempts(retryAttempts);
        properties.getPersistence().setRetryBackoff(retryBackoff);
        properties.getPersistence().setPersistObservations(persistObservations);
        properties.getPersistence().setPersistSummaryOnlyInterpretations(persistSummaryOnlyInterpretations);
        properties.getRanking().setCompactModeThreshold(compactModeThreshold);
        assertTrue(properties.getPersistence().normalizedDeletionBatchSize() > 0);
        return new JdbcVariantAnalysisJobRepository(
                jdbcTemplate,
                transactionManager,
                properties,
                new ObjectMapper()
        );
    }

    private VariantInterpretation interpretation(String variantId, VariantQcObservation observation) {
        return new VariantInterpretation(
                variantId,
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private VariantInterpretation summaryOnlyInterpretation(String variantId, VariantQcObservation observation) {
        VariantInterpretation interpretation = interpretation(variantId, observation);
        interpretation.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_COMMON,
                true,
                true,
                true,
                "LARGE_FILE_COMMON_AF",
                Collections.singletonList("common")
        );
        return interpretation;
    }

    private VariantQcObservation observation(String chromosome, int position, String reference, String alternate) {
        return observation(
                chromosome,
                position,
                reference,
                alternate,
                chromosome + "\t" + position + "\t.\t" + reference + "\t" + alternate
        );
    }

    private VariantQcObservation observation(String chromosome,
                                             int position,
                                             String reference,
                                             String alternate,
                                             String originalRecord) {
        return new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", chromosome, position, reference, alternate),
                "sample",
                "0/1",
                50,
                99,
                "10,40",
                0.8d,
                "PASS",
                reference.length() == 1 && alternate.length() == 1 ? "SNV" : "INDEL",
                originalRecord
        );
    }

    private VariantInterpretationFilter phenotypeFilter(Boolean hasSupport, String gene, Double minScore) {
        return new VariantInterpretationFilter(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                hasSupport,
                gene,
                minScore
        );
    }
}
