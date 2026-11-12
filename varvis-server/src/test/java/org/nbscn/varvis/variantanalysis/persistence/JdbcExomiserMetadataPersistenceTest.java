package org.nbscn.varvis.variantanalysis.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcExomiserMetadataPersistenceTest {

    @Test
    // Mockito matchers/captors return dummy nulls while recording, not executing SQL.
    @SuppressWarnings({"unchecked", "null"})
    void loadingExomiserMetadataPreservesThePersistedJobTimestamp() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        JdbcVariantAnalysisJobRepository repository = new JdbcVariantAnalysisJobRepository(
                jdbcTemplate,
                transactionManager,
                new VariantAnalysisProperties(),
                new ObjectMapper()
        );
        Instant createdAt = Instant.parse("2026-08-27T00:00:00Z");
        Instant persistedUpdatedAt = Instant.parse("2026-08-27T00:01:00Z");
        VariantAnalysisJob persisted = VariantAnalysisJob.restore(
                "00000000-0000-0000-0000-000000000003",
                "input.vcf.gz",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "SAMPLE",
                Paths.get("target", "metadata-test"),
                Paths.get("target", "metadata-test", "input.vcf.gz"),
                createdAt,
                persistedUpdatedAt,
                VariantAnalysisStatus.READY,
                "SAMPLE",
                false,
                "EXOMISER",
                null,
                Collections.emptyList(),
                null
        );
        EngineMetadata metadata = EngineMetadata.exomiserRanked(
                "15.1.0", "fingerprint", "output", "EXOMISER_G1");
        org.mockito.Mockito.doReturn(Collections.singletonList(persisted))
                .doReturn(Collections.singletonList(metadata))
                .when(jdbcTemplate)
                .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));

        VariantAnalysisJob loaded = repository.findById(persisted.getId()).orElseThrow(
                () -> new AssertionError("job not restored"));

        assertEquals(persistedUpdatedAt, loaded.getUpdatedAt());
        assertEquals("output", loaded.getEngineMetadata().getOutputSha256());
    }

    @Test
    // Mockito matchers return dummy nulls while recording, not executing SQL.
    @SuppressWarnings({"unchecked", "null"})
    void reusableReadyLookupRestoresTerminalExomiserMetadata() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        JdbcVariantAnalysisJobRepository repository = new JdbcVariantAnalysisJobRepository(
                jdbcTemplate,
                transactionManager,
                new VariantAnalysisProperties(),
                new ObjectMapper()
        );
        VariantAnalysisJob persisted = VariantAnalysisJob.restore(
                "00000000-0000-0000-0000-000000000004",
                "input.vcf.gz",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "SAMPLE",
                Paths.get("target", "metadata-test"),
                Paths.get("target", "metadata-test", "input.vcf.gz"),
                Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-27T00:01:00Z"),
                VariantAnalysisStatus.READY,
                "SAMPLE",
                false,
                "EXOMISER",
                null,
                Collections.emptyList(),
                null
        );
        EngineMetadata metadata = EngineMetadata.exomiserRanked(
                "15.1.0", "fingerprint", "c".repeat(64), "EXOMISER_G0");
        org.mockito.Mockito.doReturn(Collections.singletonList(persisted))
                .doReturn(Collections.singletonList(metadata))
                .when(jdbcTemplate)
                .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));

        VariantAnalysisJob loaded = repository.findReusableReadyJobs(
                persisted.getFileSha256(), "GRCh38", "WES", "SAMPLE").get(0);

        assertEquals(metadata.getOutputSha256(), loaded.getEngineMetadata().getOutputSha256());
        assertEquals(metadata.getResourceFingerprint(), loaded.getEngineMetadata().getResourceFingerprint());
    }

    @Test
    void persistsExomiserMetadataInTheDedicatedTableOnly() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        JdbcVariantAnalysisJobRepository repository = new JdbcVariantAnalysisJobRepository(
                jdbcTemplate,
                transactionManager,
                new VariantAnalysisProperties(),
                new ObjectMapper()
        );
        VariantAnalysisJob job = new VariantAnalysisJob(
                "00000000-0000-0000-0000-000000000001",
                "input.vcf.gz",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "SAMPLE",
                Paths.get("target", "metadata-test"),
                Paths.get("target", "metadata-test", "input.vcf.gz")
        );
        job.applyEngineMetadata(EngineMetadata.exomiserRunning(
                "15.1.0",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "EXOMISER_G0"
        ));

        repository.save(job);

        java.util.List<String> sql = new java.util.ArrayList<String>();
        org.mockito.Mockito.mockingDetails(jdbcTemplate).getInvocations().forEach(invocation -> {
            if ("update".equals(invocation.getMethod().getName()) && invocation.getArguments().length > 0) {
                sql.add(String.valueOf(invocation.getArguments()[0]));
            }
        });
        assertEquals(2, sql.size());
        assertTrue(sql.stream().anyMatch(value -> value.contains("INSERT INTO exomiser_analysis_job")));
        assertFalse(sql.stream().anyMatch(value -> value.contains("engine_metadata_json")));
        assertFalse(sql.stream().anyMatch(value -> value.contains("engine_metadata_schema_version")));
    }

    @Test
    void routesAcceptanceMetadataAndJobRowsOnlyToTheConfiguredPrefixedTables() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPersistence().setTablePrefix("exomiser_acceptance_");
        JdbcVariantAnalysisJobRepository repository = new JdbcVariantAnalysisJobRepository(
                jdbcTemplate, transactionManager, properties, new ObjectMapper());
        VariantAnalysisJob job = new VariantAnalysisJob(
                "00000000-0000-0000-0000-000000000002",
                "input.vcf.gz",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "SAMPLE",
                Paths.get("target", "metadata-test"),
                Paths.get("target", "metadata-test", "input.vcf.gz")
        );
        job.applyEngineMetadata(EngineMetadata.exomiserRunning(
                "15.1.0",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "EXOMISER_G0"
        ));

        repository.save(job);

        java.util.List<String> sql = new java.util.ArrayList<String>();
        org.mockito.Mockito.mockingDetails(jdbcTemplate).getInvocations().forEach(invocation -> {
            if ("update".equals(invocation.getMethod().getName()) && invocation.getArguments().length > 0) {
                sql.add(String.valueOf(invocation.getArguments()[0]));
            }
        });
        assertTrue(sql.stream().allMatch(value -> !value.contains("INSERT INTO variant_analysis_job")));
        assertTrue(sql.stream().allMatch(value -> !value.contains("INSERT INTO exomiser_analysis_job")));
        assertTrue(sql.stream().anyMatch(value -> value.contains("exomiser_acceptance_variant_analysis_job")));
        assertTrue(sql.stream().anyMatch(value -> value.contains("exomiser_acceptance_exomiser_analysis_job")));
    }
}
