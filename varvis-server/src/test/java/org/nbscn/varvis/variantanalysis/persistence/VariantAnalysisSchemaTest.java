package org.nbscn.varvis.variantanalysis.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAnalysisSchemaTest {

    @Test
    void jobSchemaContainsMySqlCompatiblePhenotypeSummaryColumns() throws Exception {
        String schema = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/db/variant-analysis-schema.sql")), StandardCharsets.UTF_8);

        assertTrue(schema.contains("phenotype_summary_schema_version VARCHAR(16) NULL"));
        assertTrue(schema.contains("phenotype_summary_json LONGTEXT NULL"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS variant_analysis_job"));
    }

    @Test
    void unifiedEvidenceFoundationDoesNotAddPerEvidenceTableOrBackfillColumn() throws Exception {
        String schema = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/db/variant-analysis-schema.sql")), StandardCharsets.UTF_8);

        assertFalse(schema.contains("variant_analysis_evidence"));
        assertFalse(schema.contains("evidence_schema_version"));
    }
}
