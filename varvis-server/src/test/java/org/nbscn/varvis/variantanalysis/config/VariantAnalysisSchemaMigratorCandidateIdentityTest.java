package org.nbscn.varvis.variantanalysis.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisSchemaMigratorCandidateIdentityTest {

    @Test
    void upgradesLegacyH2SchemaAndIsIdempotent() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:candidate_migration_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "CREATE TABLE variant_analysis_interpretation ("
                        + "analysis_id VARCHAR(36) NOT NULL, variant_order INT NOT NULL, "
                        + "variant_id VARCHAR(1024) NOT NULL, "
                        + "priority_tier VARCHAR(32) NULL, ranking_score DOUBLE NOT NULL, "
                        + "payload_json LONGTEXT NOT NULL, "
                        + "PRIMARY KEY (analysis_id, variant_order))"
        );
        jdbcTemplate.execute(
                "CREATE TABLE variant_transcript_annotation_cache (cache_key VARCHAR(255) PRIMARY KEY)"
        );
        jdbcTemplate.update(
                "INSERT INTO variant_transcript_annotation_cache (cache_key) VALUES ('legacy-cache-row')"
        );
        jdbcTemplate.execute("CREATE TABLE variant_analysis_job (id VARCHAR(36) PRIMARY KEY)");
        jdbcTemplate.update(
                "INSERT INTO variant_analysis_interpretation "
                        + "(analysis_id, variant_order, variant_id, priority_tier, ranking_score, payload_json) "
                        + "VALUES ('analysis', 0, 'legacy', 'TIER_1', 1.0, '{}')"
        );
        jdbcTemplate.update(
                "INSERT INTO variant_analysis_interpretation "
                        + "(analysis_id, variant_order, variant_id, priority_tier, ranking_score, payload_json) "
                        + "VALUES ('analysis', 1, 'legacy', 'TIER_2', 0.5, '{}')"
        );
        VariantAnalysisSchemaMigrator migrator =
                new VariantAnalysisSchemaMigrator(dataSource, jdbcTemplate);

        migrator.migrate();
        migrator.migrate();

        List<Map<String, Object>> identities = jdbcTemplate.queryForList(
                "SELECT candidate_id, canonical_variant_id "
                        + "FROM variant_analysis_interpretation ORDER BY variant_order"
        );
        assertEquals(2, identities.size());
        assertEquals("legacy-v1-0000000000", identities.get(0).get("candidate_id"));
        assertEquals("legacy-v1-0000000001", identities.get(1).get("candidate_id"));
        assertNull(identities.get(0).get("canonical_variant_id"));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT candidate_id) FROM variant_analysis_interpretation",
                Integer.class
        ));
        Map<String, Object> migratedCacheRow = jdbcTemplate.queryForMap(
                "SELECT exon, intron, mane_plus_clinical, protein_position, amino_acids, codons, "
                        + "lof, lof_filter, lof_flags FROM variant_transcript_annotation_cache "
                        + "WHERE cache_key = 'legacy-cache-row'"
        );
        assertEquals(9, migratedCacheRow.size());
        assertTrue(migratedCacheRow.values().stream().allMatch(value -> value == null));
    }

    @Test
    // Mockito matchers/captors return dummy nulls while recording, not executing SQL.
    @SuppressWarnings({"null"})
    void addsAndBackfillsCandidateIdentityWithoutInventingLegacyCanonicalIdentity() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet emptyColumns = mock(ResultSet.class);
        ResultSet emptyIndexes = mock(ResultSet.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("catalog");
        when(metadata.getColumns(eq("catalog"), any(), any(), any())).thenReturn(emptyColumns);
        when(metadata.getIndexInfo(eq("catalog"), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(emptyIndexes);
        when(emptyColumns.next()).thenReturn(false);
        when(emptyIndexes.next()).thenReturn(false);

        new VariantAnalysisSchemaMigrator(dataSource, jdbcTemplate).migrate();

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(ddl.capture());
        List<String> statements = ddl.getAllValues();
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN candidate_id VARCHAR(128) NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN canonical_variant_id VARCHAR(1024) NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN exon VARCHAR(64) NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN intron VARCHAR(64) NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN mane_plus_clinical BOOLEAN NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN protein_position TEXT NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN amino_acids TEXT NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN codons TEXT NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN lof VARCHAR(64) NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN lof_filter TEXT NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.contains(
                "ADD COLUMN lof_flags TEXT NULL")));
        assertTrue(statements.stream().anyMatch(value -> value.equals(
                "CREATE UNIQUE INDEX uq_variant_analysis_interpretation_candidate_id "
                        + "ON variant_analysis_interpretation (analysis_id, candidate_id)")));

        ArgumentCaptor<String> backfill = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(backfill.capture());
        assertTrue(backfill.getValue().contains("SET candidate_id ="));
        assertTrue(backfill.getValue().contains("legacy-v1-"));
        assertEquals(false, backfill.getValue().contains("canonical_variant_id = variant_id"));
    }
}
