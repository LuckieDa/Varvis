package org.nbscn.varvis.variantanalysis.persistence;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.nbscn.varvis.variantanalysis.service.AcmgReadyEvidenceMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTranscriptAnnotationSemanticEquivalenceTest {

    @Test
    void cacheHitPreservesProteinRankingAndLofteePvs1Semantics() {
        DataSource dataSource = dataSource();
        createCacheTable(dataSource);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        JdbcTranscriptAnnotationCache cache = new JdbcTranscriptAnnotationCache(
                new NamedParameterJdbcTemplate(java.util.Objects.requireNonNull(dataSource)),
                properties
        );
        TranscriptAnnotationCacheKey key = cacheKey();
        TranscriptConsequence cacheMissAnnotation = completeFrameshiftAnnotation();

        cache.put(key, Collections.singletonList(cacheMissAnnotation));
        List<TranscriptConsequence> cacheHitAnnotations = cache.get(key);

        assertNotNull(cacheHitAnnotations);
        assertEquals(1, cacheHitAnnotations.size());
        TranscriptConsequence cacheHitAnnotation = cacheHitAnnotations.get(0);
        assertEquals(cacheMissAnnotation.getProteinPosition(), cacheHitAnnotation.getProteinPosition());
        assertEquals(cacheMissAnnotation.getLof(), cacheHitAnnotation.getLof());

        VariantInterpretation cacheMiss = interpretation(cacheMissAnnotation);
        VariantInterpretation cacheHit = interpretation(cacheHitAnnotation);
        TransparentVariantRankingService rankingService = new TransparentVariantRankingService(properties);
        rankingService.rank(Collections.singletonList(cacheMiss));
        rankingService.rank(Collections.singletonList(cacheHit));

        assertEquals("HIGH_PRIORITY", cacheMiss.getPriorityTier());
        assertEquals(cacheMiss.getPriorityTier(), cacheHit.getPriorityTier());
        assertEquals(cacheMiss.getRankingScore(), cacheHit.getRankingScore());
        assertEquals(cacheMiss.getScoreBreakdown(), cacheHit.getScoreBreakdown());
        assertEquals(cacheMiss.getScoreComponents().keySet(), cacheHit.getScoreComponents().keySet());
        cacheMiss.getScoreComponents().forEach((name, component) -> {
            assertEquals(component.getScore(), cacheHit.getScoreComponents().get(name).getScore());
            assertEquals(component.getReasons(), cacheHit.getScoreComponents().get(name).getReasons());
        });
        assertTrue(cacheHit.getPositiveReasons().stream()
                .anyMatch(reason -> reason.contains("High-impact consequence")));

        AcmgReadyEvidenceMapper evidenceMapper = new AcmgReadyEvidenceMapper(properties);
        AcmgEvidenceSuggestion cacheMissPvs1 = suggestion(evidenceMapper, cacheMiss, "PVS1_candidate");
        AcmgEvidenceSuggestion cacheHitPvs1 = suggestion(evidenceMapper, cacheHit, "PVS1_candidate");

        assertEquals("SUGGESTED", cacheHitPvs1.getStatus());
        assertTrue(cacheHitPvs1.getExplanation().contains("LOFTEE high-confidence"));
        assertEquals(cacheMissPvs1.getStatus(), cacheHitPvs1.getStatus());
        assertEquals(cacheMissPvs1.getStrength(), cacheHitPvs1.getStrength());
        assertEquals(cacheMissPvs1.getExplanation(), cacheHitPvs1.getExplanation());
        assertEquals(cacheMissPvs1.getWarnings(), cacheHitPvs1.getWarnings());
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:transcript_semantics_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
    }

    private void createCacheTable(DataSource dataSource) {
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).execute(
                "CREATE TABLE variant_transcript_annotation_cache ("
                        + "cache_key_hash CHAR(64) NOT NULL, "
                        + "annotation_index INT NOT NULL, "
                        + "genome_assembly VARCHAR(16) NOT NULL, "
                        + "chromosome VARCHAR(64) NOT NULL, "
                        + "variant_position INT NOT NULL, "
                        + "reference_allele TEXT NOT NULL, "
                        + "alternate_allele TEXT NOT NULL, "
                        + "provider_name VARCHAR(64) NOT NULL, "
                        + "provider_version VARCHAR(128) NOT NULL, "
                        + "cache_resource_version VARCHAR(255) NOT NULL, "
                        + "options_hash VARCHAR(64) NOT NULL, "
                        + "gene_symbol VARCHAR(255) NULL, "
                        + "gene_id VARCHAR(255) NULL, "
                        + "transcript_id VARCHAR(255) NULL, "
                        + "transcript_accession VARCHAR(255) NULL, "
                        + "transcript_source VARCHAR(64) NULL, "
                        + "consequence VARCHAR(1000) NULL, "
                        + "impact VARCHAR(64) NULL, "
                        + "hgvsc VARCHAR(1000) NULL, "
                        + "hgvsp VARCHAR(1000) NULL, "
                        + "canonical BOOLEAN NULL, "
                        + "mane_select BOOLEAN NULL, "
                        + "exon VARCHAR(64) NULL, "
                        + "intron VARCHAR(64) NULL, "
                        + "mane_plus_clinical BOOLEAN NULL, "
                        + "protein_position TEXT NULL, "
                        + "amino_acids TEXT NULL, "
                        + "codons TEXT NULL, "
                        + "lof VARCHAR(64) NULL, "
                        + "lof_filter TEXT NULL, "
                        + "lof_flags TEXT NULL, "
                        + "annotation_status VARCHAR(32) NOT NULL, "
                        + "error_code VARCHAR(64) NULL, "
                        + "error_message VARCHAR(1000) NULL, "
                        + "created_at DATETIME NOT NULL, "
                        + "updated_at DATETIME NOT NULL, "
                        + "PRIMARY KEY (cache_key_hash, annotation_index))"
        );
    }

    private TranscriptAnnotationCacheKey cacheKey() {
        return TranscriptAnnotationCacheKey.from(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "G"),
                "vep",
                "vep-test",
                "110_GRCh38",
                "MANE-1.4",
                "loftee-1.0",
                "transcript-consequence-v2"
        );
    }

    private TranscriptConsequence completeFrameshiftAnnotation() {
        return new TranscriptConsequence(
                "GENE1",
                "ENSG00000000001",
                "ENST00000000001",
                "ENST00000000001:c.100del",
                null,
                "frameshift_variant",
                "ENSG00000000001",
                "ENST00000000001",
                "protein_coding",
                "HIGH",
                "2/10",
                null,
                true,
                true,
                true,
                "34/450",
                "G/-",
                "ggc/-",
                "HC",
                null,
                "SINGLE_EXON",
                "PRE_RANKING_VEP",
                "COMPLETED",
                "vep-test",
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private VariantInterpretation interpretation(TranscriptConsequence annotation) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "G"),
                "synthetic-proband",
                "0/1",
                30,
                99,
                "16,14",
                0.46d,
                "PASS",
                "INDEL",
                "synthetic-record"
        );
        VariantInterpretation interpretation = new VariantInterpretation(
                "GRCh38:1:100:A:G",
                observation,
                Collections.singletonList(
                        new TranscriptConsequence("GENE1", null, null, null, null, "INDEL")
                ),
                Collections.emptyList()
        );
        interpretation.applyTranscriptAnnotations(Collections.singletonList(annotation));
        return interpretation;
    }

    private AcmgEvidenceSuggestion suggestion(AcmgReadyEvidenceMapper mapper,
                                              VariantInterpretation interpretation,
                                              String code) {
        for (AcmgEvidenceSuggestion suggestion : mapper.suggestions(interpretation)) {
            if (code.equals(suggestion.getCode())) {
                return suggestion;
            }
        }
        throw new AssertionError("Missing ACMG-ready suggestion: " + code);
    }
}
