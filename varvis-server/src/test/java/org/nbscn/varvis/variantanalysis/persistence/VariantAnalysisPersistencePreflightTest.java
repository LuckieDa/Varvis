package org.nbscn.varvis.variantanalysis.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.exception.VariantAnalysisPersistenceException;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.lang.NonNull;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VariantAnalysisPersistencePreflightTest {
    @Test
    void detectsMissingIdentityColumnWithoutChangingSchemaOrRows() {
        var source = database();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("ALTER TABLE variant_analysis_interpretation DROP COLUMN canonical_variant_id");
        var repository = repository(source, new VariantAnalysisProperties());
        var error = assertThrows(VariantAnalysisPersistenceException.class, repository::verifyPersistenceSchema);
        assertEquals("ANALYSIS_DATABASE_SCHEMA_INCOMPATIBLE", error.getErrorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM variant_analysis_interpretation", Integer.class));
        assertThrows(org.springframework.jdbc.BadSqlGrammarException.class,
                () -> jdbc.queryForList("SELECT canonical_variant_id FROM variant_analysis_interpretation WHERE 1=0"));
        jdbc.execute("ALTER TABLE variant_analysis_interpretation ADD canonical_variant_id VARCHAR(1024)");
        assertDoesNotThrow(repository::verifyPersistenceSchema); // No stale negative cache after migration.
    }

    @Test
    void checksConfiguredPrefixInsteadOfAnUnrelatedCompatibleTable() {
        var source = database();
        var properties = new VariantAnalysisProperties();
        properties.getPersistence().setTablePrefix("isolated_");
        assertThrows(VariantAnalysisPersistenceException.class,
                () -> repository(source, properties).verifyPersistenceSchema());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE isolated_variant_analysis_interpretation AS SELECT * FROM variant_analysis_interpretation WHERE 1=0");
        jdbc.execute("CREATE TABLE isolated_variant_analysis_job AS SELECT * FROM variant_analysis_job WHERE 1=0");
        assertDoesNotThrow(repository(source, properties)::verifyPersistenceSchema);
    }

    private @NonNull DriverManagerDataSource database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:preflight_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/variant-analysis-schema.sql")).execute(source);
        return source;
    }

    private JdbcVariantAnalysisJobRepository repository(@NonNull DriverManagerDataSource source, VariantAnalysisProperties properties) {
        return new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(source),
                new DataSourceTransactionManager(source), properties, new ObjectMapper());
    }
}
