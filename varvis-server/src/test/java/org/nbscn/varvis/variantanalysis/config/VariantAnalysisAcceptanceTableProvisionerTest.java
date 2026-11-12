package org.nbscn.varvis.variantanalysis.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VariantAnalysisAcceptanceTableProvisionerTest {

    @Test
    @SuppressWarnings("null") // Mockito matchers are non-null at invocation time.
    void createsOnlyEmptyPrefixedCopiesOfTheEstablishedTables() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<String> sql = new ArrayList<String>();
        doAnswer(invocation -> {
            sql.add(invocation.getArgument(0));
            return null;
        }).when(jdbcTemplate).execute(anyString());
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<Integer>>any()))
                .thenAnswer(invocation -> {
                    sql.add(invocation.getArgument(0));
                    return Integer.valueOf(0);
                });
        VariantAnalysisProperties properties = properties("exomiser_acceptance_", true);

        new VariantAnalysisAcceptanceTableProvisioner(jdbcTemplate, properties).provisionIfEnabled();

        assertEquals(14, sql.size());
        assertFalse(sql.stream().anyMatch(value -> value.startsWith("CREATE TABLE IF NOT EXISTS variant_analysis_")));
        assertFalse(sql.stream().anyMatch(value -> value.startsWith("INSERT INTO") || value.startsWith("ALTER TABLE")));
    }

    @Test
    void refusesBootstrapWithoutAnExplicitIsolationPrefix() {
        VariantAnalysisProperties properties = properties("", true);
        assertThrows(IllegalStateException.class,
                () -> new VariantAnalysisAcceptanceTableProvisioner(mock(JdbcTemplate.class), properties)
                        .provisionIfEnabled());
    }

    private VariantAnalysisProperties properties(String prefix, boolean enabled) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPersistence().setTablePrefix(prefix);
        properties.getPersistence().setAcceptanceTableBootstrapEnabled(enabled);
        return properties;
    }
}
