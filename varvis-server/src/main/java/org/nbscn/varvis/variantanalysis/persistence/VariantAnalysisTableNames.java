package org.nbscn.varvis.variantanalysis.persistence;

import org.springframework.util.StringUtils;
import org.springframework.lang.NonNull;

/**
 * Resolves the variant-analysis persistence tables. A non-empty prefix is an
 * explicit acceptance-environment isolation boundary; it is never inferred.
 */
public final class VariantAnalysisTableNames {

    private static final String PREFIX_PATTERN = "[a-z][a-z0-9_]{0,30}";

    private final String prefix;

    public VariantAnalysisTableNames(String prefix) {
        String normalized = prefix == null ? "" : prefix.trim();
        if (StringUtils.hasText(normalized) && !normalized.matches(PREFIX_PATTERN)) {
            throw new IllegalArgumentException(
                    "variant-analysis.persistence.table-prefix must use lowercase letters, digits, and underscores"
            );
        }
        this.prefix = normalized;
    }

    public String job() {
        return table("variant_analysis_job");
    }

    public String observation() {
        return table("variant_analysis_observation");
    }

    public String event() {
        return table("variant_analysis_event");
    }

    public String interpretation() {
        return table("variant_analysis_interpretation");
    }

    public String enhancement() {
        return table("variant_analysis_enhancement");
    }

    public String transcriptAnnotationCache() {
        return table("variant_transcript_annotation_cache");
    }

    public String exomiserMetadata() {
        return table("exomiser_analysis_job");
    }

    public boolean isIsolated() {
        return StringUtils.hasText(prefix);
    }

    public String prefix() {
        return prefix;
    }

    @NonNull
    @SuppressWarnings("null") // String replacement preserves the non-null SQL contract.
    public String rewrite(@NonNull String sql) {
        if (!isIsolated()) {
            return sql;
        }
        return sql
                .replace("variant_transcript_annotation_cache", transcriptAnnotationCache())
                .replace("variant_analysis_interpretation", interpretation())
                .replace("variant_analysis_observation", observation())
                .replace("variant_analysis_enhancement", enhancement())
                .replace("variant_analysis_event", event())
                .replace("variant_analysis_job", job())
                .replace("exomiser_analysis_job", exomiserMetadata());
    }

    private String table(String baseName) {
        String name = prefix + baseName;
        if (name.length() > 64) {
            throw new IllegalArgumentException("Variant analysis table name exceeds the MySQL 64-character limit");
        }
        return name;
    }
}
