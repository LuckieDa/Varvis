package org.nbscn.varvis.variantanalysis.persistence;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.mockito.ArgumentCaptor;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcTranscriptAnnotationCacheTest {

    @Test
    void cachesCompletedAnnotationInScalarColumns() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyNonNullString(), anySqlParameterSource())).thenReturn(1);
        JdbcTranscriptAnnotationCache cache = new JdbcTranscriptAnnotationCache(
                jdbcTemplate, new VariantAnalysisProperties());

        cache.put(key("GRCh38", "chr4", "G"), Collections.singletonList(annotation("COMPLETED")));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource[]> batchCaptor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate).batchUpdate(captureSql(sqlCaptor), captureBatch(batchCaptor));
        assertTrue(sqlCaptor.getValue().contains("variant_transcript_annotation_cache"));
        assertTrue(sqlCaptor.getValue().contains("gene_symbol"));
        assertTrue(sqlCaptor.getValue().contains("transcript_source"));
        assertTrue(sqlCaptor.getValue().contains("annotation_status"));
        assertEquals("DEMO1", batchCaptor.getValue()[0].getValue("geneSymbol"));
        assertEquals("protein_coding", batchCaptor.getValue()[0].getValue("transcriptSource"));
        assertEquals("COMPLETED", batchCaptor.getValue()[0].getValue("annotationStatus"));
    }

    @Test
    void roundTripsExtendedVepFieldsAndManePlusClinicalTriState() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:transcript_cache_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        new JdbcTemplate(dataSource).execute(java.util.Objects.requireNonNull(cacheTableDdl()));
        JdbcTranscriptAnnotationCache cache = new JdbcTranscriptAnnotationCache(
                new NamedParameterJdbcTemplate(dataSource),
                new VariantAnalysisProperties()
        );
        List<TranscriptConsequence> annotations = Arrays.asList(
                extendedAnnotation("null", null),
                extendedAnnotation("true", true),
                extendedAnnotation("false", false),
                extendedAnnotation("long", true)
        );

        cache.put(key("GRCh38", "4", "G"), annotations);
        List<TranscriptConsequence> restored = cache.get(key("GRCh38", "chr4", "G"));

        assertEquals(4, restored.size());
        for (int index = 0; index < annotations.size(); index++) {
            assertExtendedFieldsEqual(annotations.get(index), restored.get(index));
        }
    }

    @Test
    void doesNotPersistProviderFailureStatuses() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        JdbcTranscriptAnnotationCache cache = new JdbcTranscriptAnnotationCache(
                jdbcTemplate, new VariantAnalysisProperties());

        cache.put(key("GRCh38", "4", "G"), Collections.singletonList(annotation("PROVIDER_ERROR")));

        verify(jdbcTemplate, never()).update(anyNonNullString(), anySqlParameterSource());
        verify(jdbcTemplate, never()).batchUpdate(anyNonNullString(), anySqlParameterSourceArray());
    }

    @Test
    void normalizedKeyHashIsSharedForChrPrefixOnly() {
        TranscriptAnnotationCacheKey chr4 = key("GRCh38", "chr4", "G");
        TranscriptAnnotationCacheKey noChr4 = key("GRCh38", "4", "G");
        TranscriptAnnotationCacheKey grch37 = key("GRCh37", "4", "G");

        assertEquals(chr4.cacheKeyHash(), noChr4.cacheKeyHash());
        org.junit.jupiter.api.Assertions.assertNotEquals(chr4.cacheKeyHash(), grch37.cacheKeyHash());
    }

    @Test
    void schemaAvoidsMySql55IncompatibleCacheColumnsAndIndexes() throws IOException {
        String schema = new String(
                Files.readAllBytes(Paths.get("src/main/resources/db/variant-analysis-schema.sql")),
                StandardCharsets.UTF_8
        );
        int tableStart = schema.indexOf("CREATE TABLE IF NOT EXISTS variant_transcript_annotation_cache");
        assertTrue(tableStart >= 0);
        String cacheTable = schema.substring(tableStart);

        assertFalse(cacheTable.contains(" JSON"));
        assertFalse(cacheTable.contains("CURRENT_TIMESTAMP"));
        assertTrue(cacheTable.contains("PRIMARY KEY (cache_key_hash, annotation_index)"));
        assertFalse(cacheTable.contains("INDEX idx_variant_transcript_annotation_cache_variant"));
        assertTrue(cacheTable.contains("cache_key_hash CHAR(64) NOT NULL"));
        assertTrue(cacheTable.contains("reference_allele TEXT NOT NULL"));
        assertTrue(cacheTable.contains("alternate_allele TEXT NOT NULL"));
        assertTrue(cacheTable.contains("transcript_source VARCHAR(64) NULL"));
        assertTrue(cacheTable.contains("exon VARCHAR(64) NULL"));
        assertTrue(cacheTable.contains("intron VARCHAR(64) NULL"));
        assertTrue(cacheTable.contains("mane_plus_clinical BOOLEAN NULL"));
        assertTrue(cacheTable.contains("protein_position TEXT NULL"));
        assertTrue(cacheTable.contains("amino_acids TEXT NULL"));
        assertTrue(cacheTable.contains("codons TEXT NULL"));
        assertTrue(cacheTable.contains("lof VARCHAR(64) NULL"));
        assertTrue(cacheTable.contains("lof_filter TEXT NULL"));
        assertTrue(cacheTable.contains("lof_flags TEXT NULL"));
    }

    private TranscriptAnnotationCacheKey key(String assembly, String chromosome, String alternate) {
        return TranscriptAnnotationCacheKey.from(
                new NormalizedVariantKey(assembly, chromosome, 100, "A", alternate),
                "vep",
                "vep-test",
                "110_GRCh38",
                "MANE-1.4",
                "loftee-1.0",
                "options"
        );
    }

    private TranscriptConsequence annotation(String status) {
        return new TranscriptConsequence(
                "DEMO1",
                "ENSG00000000001",
                "ENST00000000001",
                "c.392A>G",
                "p.Asn131Ser",
                "missense_variant",
                "ENSG00000000001",
                "ENST00000000001",
                "protein_coding",
                "MODERATE",
                null,
                null,
                true,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "PRE_RANKING_VEP",
                status,
                "vep-test",
                Instant.now()
        );
    }

    private TranscriptConsequence extendedAnnotation(String marker, Boolean manePlusClinical) {
        String freeText = "long".equals(marker)
                ? String.join("", Collections.nCopies(1200, "X"))
                : marker;
        return new TranscriptConsequence(
                "DEMO-" + marker,
                "ENSG-" + marker,
                "ENST-" + marker,
                "c." + marker,
                "p." + marker,
                "consequence-" + marker,
                "ENSG-" + marker,
                "ENST-" + marker,
                "source-" + marker,
                "impact-" + marker,
                "exon-" + marker,
                "intron-" + marker,
                true,
                false,
                manePlusClinical,
                "protein-position-" + freeText,
                "amino-acids-" + freeText,
                "codons-" + freeText,
                "lof-" + marker,
                "lof-filter-" + freeText,
                "lof-flags-" + freeText,
                "PRE_RANKING_VEP",
                "COMPLETED",
                "vep-test",
                Instant.now()
        );
    }

    private void assertExtendedFieldsEqual(TranscriptConsequence expected,
                                           TranscriptConsequence actual) {
        assertEquals(expected.getExon(), actual.getExon());
        assertEquals(expected.getIntron(), actual.getIntron());
        assertEquals(expected.getManePlusClinical(), actual.getManePlusClinical());
        assertEquals(expected.getProteinPosition(), actual.getProteinPosition());
        assertEquals(expected.getAminoAcids(), actual.getAminoAcids());
        assertEquals(expected.getCodons(), actual.getCodons());
        assertEquals(expected.getLof(), actual.getLof());
        assertEquals(expected.getLofFilter(), actual.getLofFilter());
        assertEquals(expected.getLofFlags(), actual.getLofFlags());
    }

    private String cacheTableDdl() {
        return "CREATE TABLE variant_transcript_annotation_cache ("
                + "cache_key_hash CHAR(64) NOT NULL, annotation_index INT NOT NULL, "
                + "genome_assembly VARCHAR(16), chromosome VARCHAR(64), variant_position INT, "
                + "reference_allele TEXT, alternate_allele TEXT, provider_name VARCHAR(64), "
                + "provider_version VARCHAR(128), cache_resource_version VARCHAR(255), options_hash VARCHAR(64), "
                + "gene_symbol VARCHAR(255), gene_id VARCHAR(255), transcript_id VARCHAR(255), "
                + "transcript_accession VARCHAR(255), transcript_source VARCHAR(64), consequence VARCHAR(1000), "
                + "impact VARCHAR(64), hgvsc VARCHAR(1000), hgvsp VARCHAR(1000), canonical BOOLEAN, "
                + "mane_select BOOLEAN, exon VARCHAR(64), intron VARCHAR(64), mane_plus_clinical BOOLEAN, "
                + "protein_position TEXT, amino_acids TEXT, codons TEXT, "
                + "lof VARCHAR(64), lof_filter TEXT, lof_flags TEXT, "
                + "annotation_status VARCHAR(32), error_code VARCHAR(64), error_message VARCHAR(1000), "
                + "created_at DATETIME, updated_at DATETIME, PRIMARY KEY (cache_key_hash, annotation_index))";
    }

    @SuppressWarnings("null")
    private static @NonNull String anyNonNullString() {
        return anyString();
    }

    @SuppressWarnings("null")
    private static @NonNull SqlParameterSource anySqlParameterSource() {
        return any(SqlParameterSource.class);
    }

    @SuppressWarnings("null")
    private static @NonNull SqlParameterSource[] anySqlParameterSourceArray() {
        return any(SqlParameterSource[].class);
    }

    @SuppressWarnings("null")
    private static @NonNull String captureSql(ArgumentCaptor<String> captor) {
        return captor.capture();
    }

    @SuppressWarnings("null")
    private static @NonNull SqlParameterSource[] captureBatch(ArgumentCaptor<SqlParameterSource[]> captor) {
        return captor.capture();
    }
}
