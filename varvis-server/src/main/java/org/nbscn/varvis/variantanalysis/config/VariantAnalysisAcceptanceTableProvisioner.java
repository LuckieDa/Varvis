package org.nbscn.varvis.variantanalysis.config;

import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisTableNames;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

/**
 * Creates a disposable, prefixed copy of the established analysis tables for
 * a controlled acceptance run. It is off by default and never alters source
 * tables or copies their rows.
 */
@Component
@ConditionalOnBean(name = "varvisJdbcTemplate")
public class VariantAnalysisAcceptanceTableProvisioner {

    private final JdbcTemplate jdbcTemplate;
    private final VariantAnalysisProperties properties;

    public VariantAnalysisAcceptanceTableProvisioner(
            @Qualifier("varvisJdbcTemplate") JdbcTemplate jdbcTemplate,
            VariantAnalysisProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @PostConstruct
    public void provisionIfEnabled() {
        VariantAnalysisProperties.Persistence persistence = properties.getPersistence();
        if (!persistence.isAcceptanceTableBootstrapEnabled()) {
            return;
        }
        VariantAnalysisTableNames names = new VariantAnalysisTableNames(persistence.getTablePrefix());
        if (!names.isIsolated()) {
            throw new IllegalStateException(
                    "Acceptance table bootstrap requires a non-empty variant-analysis.persistence.table-prefix"
            );
        }
        for (TableCopy copy : copies(names)) {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + copy.target + " LIKE " + copy.source);
            Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + copy.target, Integer.class);
            if (rows == null || rows.intValue() != 0) {
                throw new IllegalStateException("Acceptance table is not empty: " + copy.target);
            }
        }
    }

    private List<TableCopy> copies(VariantAnalysisTableNames names) {
        return Arrays.asList(
                new TableCopy(names.job(), "variant_analysis_job"),
                new TableCopy(names.observation(), "variant_analysis_observation"),
                new TableCopy(names.event(), "variant_analysis_event"),
                new TableCopy(names.interpretation(), "variant_analysis_interpretation"),
                new TableCopy(names.enhancement(), "variant_analysis_enhancement"),
                new TableCopy(names.transcriptAnnotationCache(), "variant_transcript_annotation_cache"),
                new TableCopy(names.exomiserMetadata(), "exomiser_analysis_job")
        );
    }

    private static class TableCopy {

        private final String target;
        private final String source;

        private TableCopy(String target, String source) {
            this.target = target;
            this.source = source;
        }
    }
}
