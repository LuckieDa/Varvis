package org.nbscn.varvis.variantanalysis.config;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAnalysisPropertiesTest {

    @Test
    void bindsDualAssemblyNormalizationReferenceProfiles() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("variant-analysis.normalization.grch37-reference", "grch37.fa");
        values.put("variant-analysis.normalization.grch38-reference", "grch38.fa");
        values.put("variant-analysis.normalization.grch37-profile-id", "grch37-test");
        values.put("variant-analysis.normalization.grch38-profile-id", "grch38-test");
        values.put("variant-analysis.normalization.grch37-reference-sha256", "sha37");
        values.put("variant-analysis.normalization.grch38-reference-sha256", "sha38");
        values.put("variant-analysis.normalization.grch37-contig-alias-file", "grch37.aliases");
        values.put("variant-analysis.normalization.grch38-contig-alias-file", "grch38.aliases");
        environment.getPropertySources().addFirst(new MapPropertySource("normalization-profiles", values));

        VariantAnalysisProperties properties = Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .get();

        assertEquals("grch37.fa", properties.getNormalization()
                .referenceFor(GenomeAssembly.GRCh37).toString());
        assertEquals("grch38.fa", properties.getNormalization()
                .referenceFor(GenomeAssembly.GRCh38).toString());
        assertEquals("grch37-test", properties.getNormalization().profileIdFor(GenomeAssembly.GRCh37));
        assertEquals("sha38", properties.getNormalization().referenceSha256For(GenomeAssembly.GRCh38));
        assertEquals("grch37.aliases", properties.getNormalization()
                .contigAliasFileFor(GenomeAssembly.GRCh37).toString());
    }

    @Test
    void applicationYamlBindsLargeFilePersistenceSettingsAtVariantAnalysisRoot() throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : sources) {
            if (source != null) {
                environment.getPropertySources().addLast(source);
            }
        }

        VariantAnalysisProperties properties = Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .get();

        assertFalse(properties.getPersistence().isPersistSummaryOnlyInterpretations());
        assertEquals(200, properties.getPersistence().normalizedInterpretationBatchSize());
        assertEquals(262144, properties.getPersistence().normalizedInterpretationBatchMaxBytes());
        assertEquals(10000, properties.getPersistence().normalizedDeletionBatchSize());
        assertEquals(3, properties.getPersistence().normalizedRetryAttempts());
        assertEquals("", properties.getPersistence().getTablePrefix());
        assertFalse(properties.getPersistence().isAcceptanceTableBootstrapEnabled());
        assertTrue(properties.getTrio().isEnabled());
        assertTrue(properties.getTrio().isPipelineEnabled());
        assertTrue(properties.getTrio().isEvidenceOnly());
        assertTrue(properties.getTrio().isRankingEnabled());
        assertEquals(2.0d, properties.getTrio().getRankingMaxScoreContribution());
        assertFalse(properties.getTrio().isRankingAllowNegativePenalty());
        assertTrue(properties.getTrio().isRankingRequireSupportedConfidence());
        assertFalse(properties.getTrio().isRankingAllowPossibleBoost());
        assertFalse(properties.getTrio().isRankingEnableSharedParentPenalty());
        assertTrue(properties.getPhenotypeRanking().isConfidenceAware());
        assertEquals(0.9d, properties.getPhenotypeRanking().getManualSourceConfidenceCap());
        assertEquals(0.75d, properties.getPhenotypeRanking().getAiSourceConfidenceCap());
        assertEquals(0.5d, properties.getPhenotypeRanking().getMinimumMatchedTermCoverageFactor());
        assertEquals(0.5d, properties.getPhenotypeRanking().getMinimumConfidenceForBoost());
    }

    @Test
    void defaultsDoNotPersistLargeFileSummaryOnlyInterpretations() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();

        assertFalse(properties.getExomiser().isEnabled());
        assertEquals("15.1.0", properties.getExomiser().getVersion());
        assertEquals("2602", properties.getExomiser().getDataVersion());
        assertFalse(properties.getPersistence().isPersistSummaryOnlyInterpretations());
        assertFalse(properties.getTrio().isRankingEnabled());
        assertEquals(0.0d, properties.getTrio().normalizedRankingMaxScoreContribution());
        assertFalse(properties.getTrio().isRankingAllowNegativePenalty());
        assertTrue(properties.getTrio().isRankingRequireSupportedConfidence());
        assertFalse(properties.getTrio().isRankingAllowPossibleBoost());
        assertFalse(properties.getTrio().isRankingEnableSharedParentPenalty());
    }

    @Test
    void bindsTrioRankingSkeletonSettingsFromProperties() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("variant-analysis.trio.ranking-enabled", "true");
        values.put("variant-analysis.trio.ranking-max-score-contribution", "2.5");
        values.put("variant-analysis.trio.ranking-allow-negative-penalty", "true");
        values.put("variant-analysis.trio.ranking-require-supported-confidence", "false");
        values.put("variant-analysis.trio.ranking-allow-possible-boost", "true");
        values.put("variant-analysis.trio.ranking-enable-shared-parent-penalty", "true");
        environment.getPropertySources().addFirst(new MapPropertySource("trio-ranking", values));

        VariantAnalysisProperties properties = Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .get();

        assertTrue(properties.getTrio().isRankingEnabled());
        assertEquals(2.5d, properties.getTrio().getRankingMaxScoreContribution());
        assertTrue(properties.getTrio().isRankingAllowNegativePenalty());
        assertFalse(properties.getTrio().isRankingRequireSupportedConfidence());
        assertTrue(properties.getTrio().isRankingAllowPossibleBoost());
        assertTrue(properties.getTrio().isRankingEnableSharedParentPenalty());
    }

    @Test
    void bindsDualAssemblyVepResourceProfilesFromLocalProperties() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("variant-analysis.analysis-assembly-policy", "PRESERVE_INPUT");
        values.put("variant-analysis.vep.enabled", "true");
        values.put("variant-analysis.vep.execution-enabled", "true");
        values.put("variant-analysis.vep.pre-ranking-enabled", "true");
        values.put("variant-analysis.vep.resources.GRCh37.enabled", "true");
        values.put("variant-analysis.vep.resources.GRCh37.assembly", "GRCh37");
        values.put("variant-analysis.vep.resources.GRCh37.executable", "vep");
        values.put("variant-analysis.vep.resources.GRCh37.cache-dir", "cache");
        values.put("variant-analysis.vep.resources.GRCh37.fasta", "grch37.fa");
        values.put("variant-analysis.vep.resources.GRCh37.version", "116.0");
        values.put("variant-analysis.vep.resources.GRCh37.ensembl-cache-version", "116_GRCh37");
        values.put("variant-analysis.vep.resources.GRCh38.enabled", "true");
        values.put("variant-analysis.vep.resources.GRCh38.assembly", "GRCh38");
        values.put("variant-analysis.vep.resources.GRCh38.executable", "vep");
        values.put("variant-analysis.vep.resources.GRCh38.cache-dir", "cache");
        values.put("variant-analysis.vep.resources.GRCh38.fasta", "grch38.fa");
        values.put("variant-analysis.vep.resources.GRCh38.version", "116.0");
        values.put("variant-analysis.vep.resources.GRCh38.ensembl-cache-version", "116_GRCh38");
        environment.getPropertySources().addFirst(new MapPropertySource("local", values));

        VariantAnalysisProperties properties = Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .get();

        assertEquals("PRESERVE_INPUT", properties.getAnalysisAssemblyPolicy());
        assertTrue(properties.getVep().isEnabled());
        assertTrue(properties.getVep().isExecutionEnabled());
        assertTrue(properties.getVep().isPreRankingEnabled());
        assertEquals("GRCh37", properties.getVep().getResources().get("GRCh37").normalizedAssembly());
        assertEquals("116_GRCh37",
                properties.getVep().getResources().get("GRCh37").getEnsemblCacheVersion());
        assertEquals("GRCh38", properties.getVep().getResources().get("GRCh38").normalizedAssembly());
        assertEquals("116_GRCh38",
                properties.getVep().getResources().get("GRCh38").getEnsemblCacheVersion());
    }

    @Test
    void bindsClinVarVersionsPerAssemblyWithLegacyFallback() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("variant-analysis.clin-var.version", "ClinVar legacy combined");
        values.put("variant-analysis.clin-var.grch37-version", "ClinVar 2026-07-06 GRCh37");
        values.put("variant-analysis.clin-var.grch38-version", "ClinVar 2026-03-21 GRCh38");
        environment.getPropertySources().addFirst(new MapPropertySource("clinvar-versions", values));

        VariantAnalysisProperties properties = Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .get();

        assertEquals("ClinVar 2026-07-06 GRCh37", properties.getClinVar().versionFor("GRCh37"));
        assertEquals("ClinVar 2026-03-21 GRCh38", properties.getClinVar().versionFor("GRCh38"));
        assertEquals("ClinVar legacy combined", properties.getClinVar().versionFor("unknown"));
    }

    @Test
    void preRankingOptionsHashIncludesTranscriptProjectionVersion() {
        VariantAnalysisProperties.Vep vep = new VariantAnalysisProperties.Vep();
        vep.setPreRankingProvider("vep");
        vep.setPreRankingAssemblyPolicy("strict");
        vep.setLofteeEnabled(true);

        assertEquals(
                // Current main uses transcript-consequence-v3-nearest-exon-jb-8789d689.
                "7f8df8839a0eda55ca5b408cdf6aecdd945c1cb2066ff1a9a42aebbc980aa300",
                vep.preRankingOptionsHash(
                        "GRCh38",
                        "116.0",
                        "116_GRCh38",
                        "1.3",
                        "1.0.4"
                )
        );
    }
}
