package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.service.TranscriptAnnotationCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Primary
@Repository
@ConditionalOnBean(name = "varvisNamedParameterJdbcTemplate")
@ConditionalOnProperty(
        prefix = "variant-analysis.vep",
        name = "pre-ranking-cache-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class JdbcTranscriptAnnotationCache implements TranscriptAnnotationCache {

    private static final String INSERT_SQL =
            "INSERT INTO variant_transcript_annotation_cache ("
                    + "cache_key_hash, annotation_index, genome_assembly, chromosome, variant_position, "
                    + "reference_allele, alternate_allele, provider_name, provider_version, "
                    + "cache_resource_version, options_hash, gene_symbol, gene_id, transcript_id, "
                    + "transcript_accession, transcript_source, consequence, impact, hgvsc, hgvsp, canonical, mane_select, "
                    + "exon, intron, mane_plus_clinical, protein_position, amino_acids, codons, "
                    + "lof, lof_filter, lof_flags, "
                    + "annotation_status, error_code, error_message, created_at, updated_at"
                    + ") VALUES ("
                    + ":cacheKeyHash, :annotationIndex, :genomeAssembly, :chromosome, :variantPosition, "
                    + ":referenceAllele, :alternateAllele, :providerName, :providerVersion, "
                    + ":cacheResourceVersion, :optionsHash, :geneSymbol, :geneId, :transcriptId, "
                    + ":transcriptAccession, :transcriptSource, :consequence, :impact, :hgvsc, :hgvsp, :canonical, :maneSelect, "
                    + ":exon, :intron, :manePlusClinical, :proteinPosition, :aminoAcids, :codons, "
                    + ":lof, :lofFilter, :lofFlags, "
                    + ":annotationStatus, :errorCode, :errorMessage, :createdAt, :updatedAt"
                    + ")";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcTranscriptAnnotationCache(
            @Qualifier("varvisNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
            VariantAnalysisProperties properties
    ) {
        this.jdbcTemplate = new TableScopedNamedParameterJdbcTemplate(
                Objects.requireNonNull(jdbcTemplate, "jdbcTemplate"),
                new VariantAnalysisTableNames(properties.getPersistence().getTablePrefix())
        );
    }

    @Override
    public List<TranscriptConsequence> get(TranscriptAnnotationCacheKey key) {
        if (key == null) {
            return null;
        }
        SqlParameterSource queryParameters = parameters(key);
        RowMapper<TranscriptConsequence> transcriptRowMapper = rowMapper();
        List<TranscriptConsequence> rows = jdbcTemplate.query(
                "SELECT gene_symbol, gene_id, transcript_id, transcript_accession, transcript_source, consequence, impact, "
                        + "hgvsc, hgvsp, exon, intron, canonical, mane_select, mane_plus_clinical, "
                        + "protein_position, amino_acids, codons, lof, lof_filter, lof_flags, "
                        + "annotation_status, provider_name, "
                        + "provider_version, updated_at "
                        + "FROM variant_transcript_annotation_cache "
                        + "WHERE cache_key_hash = :cacheKeyHash "
                        + "ORDER BY annotation_index",
                queryParameters,
                transcriptRowMapper
        );
        return rows.isEmpty() ? null : rows;
    }

    @Override
    public void put(TranscriptAnnotationCacheKey key, List<TranscriptConsequence> annotations) {
        if (key == null || annotations == null || annotations.isEmpty()) {
            return;
        }
        List<TranscriptConsequence> cacheable = cacheableAnnotations(annotations);
        if (cacheable.isEmpty()) {
            return;
        }
        SqlParameterSource keyParameters = parameters(key);
        jdbcTemplate.update(
                "DELETE FROM variant_transcript_annotation_cache WHERE cache_key_hash = :cacheKeyHash",
                keyParameters
        );
        SqlParameterSource[] batch = new SqlParameterSource[cacheable.size()];
        Instant now = Instant.now();
        for (int index = 0; index < cacheable.size(); index++) {
            batch[index] = parameters(key, cacheable.get(index), index, now);
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, batch);
    }

    private List<TranscriptConsequence> cacheableAnnotations(List<TranscriptConsequence> annotations) {
        List<TranscriptConsequence> cacheable = new ArrayList<TranscriptConsequence>();
        for (TranscriptConsequence annotation : annotations) {
            if (annotation != null && isCacheableStatus(annotation.getStatus())) {
                cacheable.add(annotation);
            }
        }
        return cacheable;
    }

    private boolean isCacheableStatus(String status) {
        return "COMPLETED".equals(status) || "AVAILABLE".equals(status) || "NO_RECORD".equals(status);
    }

    private @NonNull MapSqlParameterSource parameters(TranscriptAnnotationCacheKey key) {
        return new MapSqlParameterSource()
                .addValue("cacheKeyHash", key.cacheKeyHash())
                .addValue("genomeAssembly", key.getGenomeAssembly())
                .addValue("chromosome", key.getChromosome())
                .addValue("variantPosition", key.getPosition())
                .addValue("referenceAllele", key.getReference())
                .addValue("alternateAllele", key.getAlternate())
                .addValue("providerName", key.getProviderName())
                .addValue("providerVersion", key.getProviderVersion())
                .addValue("cacheResourceVersion", key.getCacheResourceVersion())
                .addValue("optionsHash", key.getOptionsHash());
    }

    private @NonNull MapSqlParameterSource parameters(TranscriptAnnotationCacheKey key,
                                                      TranscriptConsequence annotation,
                                                      int index,
                                                      Instant now) {
        String status = annotation.getStatus() == null ? "COMPLETED" : annotation.getStatus();
        return parameters(key)
                .addValue("annotationIndex", index)
                .addValue("geneSymbol", annotation.getGeneSymbol())
                .addValue("geneId", annotation.getGeneId())
                .addValue("transcriptId", annotation.getTranscriptId())
                .addValue("transcriptAccession", annotation.getTranscriptAccession())
                .addValue("transcriptSource", annotation.getTranscriptSource())
                .addValue("consequence", annotation.getConsequence())
                .addValue("impact", annotation.getImpact())
                .addValue("hgvsc", annotation.getHgvsc())
                .addValue("hgvsp", annotation.getHgvsp())
                .addValue("canonical", annotation.getCanonical())
                .addValue("maneSelect", annotation.getManeSelect())
                .addValue("exon", annotation.getExon())
                .addValue("intron", annotation.getIntron())
                .addValue("manePlusClinical", annotation.getManePlusClinical())
                .addValue("proteinPosition", annotation.getProteinPosition())
                .addValue("aminoAcids", annotation.getAminoAcids())
                .addValue("codons", annotation.getCodons())
                .addValue("lof", annotation.getLof())
                .addValue("lofFilter", annotation.getLofFilter())
                .addValue("lofFlags", annotation.getLofFlags())
                .addValue("annotationStatus", status)
                .addValue("errorCode", isErrorStatus(status) ? status : null)
                .addValue("errorMessage", isErrorStatus(status) ? trim(annotation.getConsequence(), 1000) : null)
                .addValue("createdAt", Timestamp.from(now))
                .addValue("updatedAt", Timestamp.from(now));
    }

    private @NonNull RowMapper<TranscriptConsequence> rowMapper() {
        return new RowMapper<TranscriptConsequence>() {
            @Override
            public TranscriptConsequence mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
                Timestamp updatedAt = rs.getTimestamp("updated_at");
                return new TranscriptConsequence(
                        rs.getString("gene_symbol"),
                        rs.getString("gene_id"),
                        rs.getString("transcript_accession"),
                        rs.getString("hgvsc"),
                        rs.getString("hgvsp"),
                        rs.getString("consequence"),
                        rs.getString("gene_id"),
                        rs.getString("transcript_id"),
                        rs.getString("transcript_source"),
                        rs.getString("impact"),
                        rs.getString("exon"),
                        rs.getString("intron"),
                        boxedBoolean(rs, "canonical"),
                        boxedBoolean(rs, "mane_select"),
                        boxedBoolean(rs, "mane_plus_clinical"),
                        rs.getString("protein_position"),
                        rs.getString("amino_acids"),
                        rs.getString("codons"),
                        rs.getString("lof"),
                        rs.getString("lof_filter"),
                        rs.getString("lof_flags"),
                        rs.getString("provider_name"),
                        rs.getString("annotation_status"),
                        rs.getString("provider_version"),
                        updatedAt == null ? null : updatedAt.toInstant()
                );
            }
        };
    }

    private Boolean boxedBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private boolean isErrorStatus(String status) {
        return status != null && !"COMPLETED".equals(status) && !"AVAILABLE".equals(status);
    }

    private String trim(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
