package org.nbscn.varvis.variantanalysis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Component
@DependsOn("variantAnalysisDataSourceInitializer")
@ConditionalOnProperty(
        prefix = "variant-analysis.persistence",
        name = "legacy-schema-migration-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class VariantAnalysisSchemaMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantAnalysisSchemaMigrator.class);
    private static final String INTERPRETATION_TABLE = "variant_analysis_interpretation";
    private static final String TRANSCRIPT_CACHE_TABLE = "variant_transcript_annotation_cache";
    private static final String JOB_TABLE = "variant_analysis_job";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public VariantAnalysisSchemaMigrator(@Qualifier("varvisDataSource") DataSource dataSource,
                                         @Qualifier("varvisJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() throws SQLException {
        migrateInterpretationSummaryColumns();
        migrateTranscriptAnnotationCacheColumns();
        migratePhenotypeSummaryColumns();
    }

    private void migrateInterpretationSummaryColumns() throws SQLException {
        Set<String> columns = existingColumns(INTERPRETATION_TABLE);
        List<ColumnDefinition> missingColumns = new ArrayList<ColumnDefinition>();
        addIfMissing(columns, missingColumns, "candidate_id", "VARCHAR(128) NULL");
        addIfMissing(columns, missingColumns, "canonical_variant_id", "VARCHAR(1024) NULL");
        addIfMissing(columns, missingColumns, "genome_assembly", "VARCHAR(16) NULL");
        addIfMissing(columns, missingColumns, "chromosome", "VARCHAR(64) NULL");
        addIfMissing(columns, missingColumns, "variant_position", "INT NULL");
        addIfMissing(columns, missingColumns, "reference_allele", "TEXT NULL");
        addIfMissing(columns, missingColumns, "alternate_allele", "TEXT NULL");
        addIfMissing(columns, missingColumns, "sample_id", "VARCHAR(255) NULL");
        addIfMissing(columns, missingColumns, "genotype", "VARCHAR(64) NULL");
        addIfMissing(columns, missingColumns, "filter_status", "VARCHAR(255) NULL");
        addIfMissing(columns, missingColumns, "variant_type", "VARCHAR(32) NULL");
        addIfMissing(columns, missingColumns, "consequences_json", "TEXT NULL");
        addIfMissing(columns, missingColumns, "cadd_score", "DOUBLE NULL");
        addIfMissing(columns, missingColumns, "alpha_missense_score", "DOUBLE NULL");
        addIfMissing(columns, missingColumns, "alpha_genome_score", "DOUBLE NULL");
        addIfMissing(columns, missingColumns, "carried_alt", "BOOLEAN NOT NULL DEFAULT TRUE");
        addIfMissing(columns, missingColumns, "analysis_bucket", "VARCHAR(64) NULL");
        addIfMissing(columns, missingColumns, "summary_only", "BOOLEAN NOT NULL DEFAULT FALSE");
        addIfMissing(columns, missingColumns, "large_file_mode", "BOOLEAN NOT NULL DEFAULT FALSE");
        addIfMissing(
                columns,
                missingColumns,
                "expensive_annotation_skipped",
                "BOOLEAN NOT NULL DEFAULT FALSE"
        );
        addIfMissing(columns, missingColumns, "expensive_annotation_skipped_reason", "TEXT NULL");
        addIfMissing(columns, missingColumns, "cheap_gate_reason_text", "TEXT NULL");
        addIfMissing(columns, missingColumns, "full_interpretation_available", "BOOLEAN NOT NULL DEFAULT TRUE");
        addColumns(INTERPRETATION_TABLE, missingColumns);
        backfillInterpretationIdentities();
        addIndexIfMissing(
                INTERPRETATION_TABLE,
                "uq_variant_analysis_interpretation_candidate_id",
                "CREATE UNIQUE INDEX uq_variant_analysis_interpretation_candidate_id "
                        + "ON " + INTERPRETATION_TABLE + " (analysis_id, candidate_id)"
        );
        addIndexIfMissing(
                INTERPRETATION_TABLE,
                "idx_variant_analysis_interpretation_candidate",
                "CREATE INDEX idx_variant_analysis_interpretation_candidate "
                        + "ON " + INTERPRETATION_TABLE + " (analysis_id, carried_alt, ranking_score)"
        );
        addIndexIfMissing(
                INTERPRETATION_TABLE,
                "idx_variant_analysis_interpretation_tier",
                "CREATE INDEX idx_variant_analysis_interpretation_tier "
                        + "ON " + INTERPRETATION_TABLE + " (analysis_id, priority_tier)"
        );
        addIndexIfMissing(
                INTERPRETATION_TABLE,
                "idx_variant_analysis_interpretation_bucket",
                "CREATE INDEX idx_variant_analysis_interpretation_bucket "
                        + "ON " + INTERPRETATION_TABLE + " (analysis_id, analysis_bucket)"
        );
    }

    private void backfillInterpretationIdentities() {
        jdbcTemplate.update(
                "UPDATE " + INTERPRETATION_TABLE + " SET candidate_id = "
                        + "CONCAT('legacy-v1-', LPAD(variant_order, 10, '0')) "
                        + "WHERE candidate_id IS NULL OR candidate_id = ''"
        );
    }

    private void migrateTranscriptAnnotationCacheColumns() throws SQLException {
        Set<String> columns = existingColumns(TRANSCRIPT_CACHE_TABLE);
        List<ColumnDefinition> missingColumns = new ArrayList<ColumnDefinition>();
        addIfMissing(columns, missingColumns, "transcript_source", "VARCHAR(64) NULL");
        addIfMissing(columns, missingColumns, "exon", "VARCHAR(64) NULL");
        addIfMissing(columns, missingColumns, "intron", "VARCHAR(64) NULL");
        addIfMissing(columns, missingColumns, "mane_plus_clinical", "BOOLEAN NULL");
        addIfMissing(columns, missingColumns, "protein_position", "TEXT NULL");
        addIfMissing(columns, missingColumns, "amino_acids", "TEXT NULL");
        addIfMissing(columns, missingColumns, "codons", "TEXT NULL");
        addIfMissing(columns, missingColumns, "lof", "VARCHAR(64) NULL");
        addIfMissing(columns, missingColumns, "lof_filter", "TEXT NULL");
        addIfMissing(columns, missingColumns, "lof_flags", "TEXT NULL");
        addColumns(TRANSCRIPT_CACHE_TABLE, missingColumns);
    }

    private void migratePhenotypeSummaryColumns() throws SQLException {
        Set<String> columns = existingColumns(JOB_TABLE);
        List<ColumnDefinition> missingColumns = new ArrayList<ColumnDefinition>();
        addIfMissing(columns, missingColumns, "phenotype_summary_schema_version", "VARCHAR(16) NULL");
        addIfMissing(columns, missingColumns, "phenotype_summary_json", "LONGTEXT NULL");
        addColumns(JOB_TABLE, missingColumns);
    }

    private void addIfMissing(Set<String> existingColumns,
                              List<ColumnDefinition> missingColumns,
                              String columnName,
                              String definition) {
        if (existingColumns.contains(columnName.toLowerCase(Locale.ROOT))) {
            return;
        }
        missingColumns.add(new ColumnDefinition(columnName, definition));
    }

    private void addColumns(String table, List<ColumnDefinition> columns) {
        if (columns.isEmpty()) {
            return;
        }
        // H2 1.4 and MySQL both accept one ADD COLUMN per ALTER statement.  H2 1.4 does
        // not accept the MySQL-style comma-separated ADD COLUMN list used previously.
        for (ColumnDefinition column : columns) {
            String ddl = "ALTER TABLE " + table
                    + " ADD COLUMN " + column.name + " " + column.definition;
            jdbcTemplate.execute(Objects.requireNonNull(ddl, "ddl"));
        }
        LOGGER.info("Added {} missing column(s) on {}", columns.size(), table);
    }

    private void addIndexIfMissing(String table, String indexName, String ddl) throws SQLException {
        if (existingIndexes(table).contains(indexName.toLowerCase(Locale.ROOT))) {
            return;
        }
        jdbcTemplate.execute(Objects.requireNonNull(ddl, "ddl"));
        LOGGER.info("Added {} on {}", indexName, table);
    }

    private Set<String> existingColumns(String table) throws SQLException {
        Set<String> result = new HashSet<String>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet columns = metaData.getColumns(
                    connection.getCatalog(),
                    null,
                    table,
                    null
            )) {
                while (columns.next()) {
                    result.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private Set<String> existingIndexes(String table) throws SQLException {
        Set<String> result = new HashSet<String>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet indexes = metaData.getIndexInfo(
                    connection.getCatalog(),
                    null,
                    table,
                    false,
                    false
            )) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    if (name != null) {
                        result.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return result;
    }

    private static class ColumnDefinition {

        private final String name;
        private final String definition;

        private ColumnDefinition(String name, String definition) {
            this.name = name;
            this.definition = definition;
        }
    }
}
