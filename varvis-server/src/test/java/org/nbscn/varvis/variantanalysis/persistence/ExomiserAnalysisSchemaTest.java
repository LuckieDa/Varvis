package org.nbscn.varvis.variantanalysis.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExomiserAnalysisSchemaTest {

    @Test
    void createsOnlyTheAdditiveExomiserMetadataTable() throws Exception {
        String schema = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/db/exomiser-analysis-schema.sql")), StandardCharsets.UTF_8);

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS exomiser_analysis_job"));
        assertTrue(schema.contains("output_sha256 CHAR(64) NULL"));
        assertTrue(schema.contains("fallback_used BOOLEAN NOT NULL DEFAULT FALSE"));
        assertFalse(schema.contains("ALTER TABLE"));
        assertFalse(schema.contains("variant_analysis_job"));
        assertFalse(schema.contains("DROP "));
        assertFalse(schema.contains("TRUNCATE "));
    }
}
