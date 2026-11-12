package org.nbscn.varvis.variantanalysis.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAnalysisProviderStatusResponseTest {

    @Test
    void reportsNormalizationProfilesWithoutExposingAbsolutePaths() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        Path grch37 = tempDir.resolve("grch37.fa");
        Path grch38 = tempDir.resolve("grch38.fa");
        Files.write(grch37, ">1\nA\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(grch38, ">chr1\nA\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("grch37.fa.fai"),
                "1\t249250621\t3\t1\t2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("grch38.fa.fai"),
                "chr1\t248956422\t6\t1\t2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        properties.getNormalization().setGrch37Reference(grch37.toString());
        properties.getNormalization().setGrch38Reference(grch38.toString());

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertEquals("COPY_ONLY", response.getNormalizationMode());
        assertEquals("READY", response.getNormalizationProfiles().get("GRCh37").getStatus());
        assertEquals("grch37.fa",
                response.getNormalizationProfiles().get("GRCh37").getReferenceFileName());
        assertFalse(response.getNormalizationProfiles().get("GRCh37")
                .getReferenceFileName().contains(tempDir.toString()));
    }

    @TempDir
    Path tempDir;

    @Test
    void reportsDisabledOptionalProviders() {
        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(new VariantAnalysisProperties());

        assertEquals("VARVIS", response.getDefaultEngine());
        assertFalse(response.getExomiser().isEnabled());
        assertEquals("DISABLED", response.getExomiser().getStatus());
        assertFalse(response.getExomiser().getRuntimePlatform().isEmpty());
        assertFalse(response.getSpliceAi().isEnabled());
        assertFalse(response.getSpliceAi().isReady());
        assertEquals("DISABLED", response.getSpliceAi().getStatus());
        assertEquals("VCF_INFO_SPLICEAI", response.getSpliceAi().getFallbackEvidenceSource());
        assertFalse(response.getVep().isEnabled());
        assertEquals("DISABLED", response.getVep().getStatus());
        assertEquals("DISABLED", response.getVepProfiles().get("GRCh37").getStatus());
        assertEquals("DISABLED", response.getVepProfiles().get("GRCh38").getStatus());
        assertEquals("DISABLED", response.getMane().getStatus());
        assertEquals("DISABLED", response.getLoftee().getStatus());
    }

    @Test
    void reportsVepEnabledButNotConfiguredAsUnavailable() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertTrue(response.getVep().isEnabled());
        assertFalse(response.getVep().isReady());
        assertEquals("UNAVAILABLE", response.getVep().getStatus());
        assertTrue(response.getVep().getMissingResources().contains("vep.resources.GRCh38.executable"));
        assertEquals("ANN_ONLY", response.getVep().getFallbackEvidenceSource());
        assertEquals("MISSING_RESOURCE", response.getVepProfiles().get("GRCh38").getStatus());
    }

    @Test
    void readyVepAndManeDoNotInheritLofteeDisabledWarning() throws IOException {
        Path executable = tempDir.resolve("varvis-vep.cmd");
        Path cache = tempDir.resolve("cache");
        Path fasta = tempDir.resolve("GRCh38.fa");
        Files.write(executable, new byte[] {1});
        Files.createDirectories(cache);
        Files.write(fasta, new byte[] {1});
        Files.write(tempDir.resolve("GRCh38.fa.fai"), new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        properties.getVep().setExecutable(executable.toString());
        properties.getVep().setCacheDir(cache.toString());
        properties.getVep().setFasta(fasta.toString());
        properties.getVep().setVersion("116.0");
        properties.getVep().setEnsemblCacheVersion("116_GRCh38");
        properties.getVep().setManeVersion("Ensembl_VEP_116_cache");
        properties.getVep().setLofteeEnabled(false);

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertEquals("READY", response.getVep().getStatus());
        assertTrue(response.getVep().getWarnings().isEmpty());
        assertEquals("READY", response.getMane().getStatus());
        assertTrue(response.getMane().getWarnings().isEmpty());
        assertEquals("DISABLED", response.getLoftee().getStatus());
    }

    @Test
    void reportsDualAssemblyVepProfileReadinessWithoutCollapsingToDisabled() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setAnalysisAssemblyPolicy("PRESERVE_INPUT");
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        configureProfile(properties, "GRCh37");
        configureProfile(properties, "GRCh38");

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertTrue(response.isVepExecutionEnabled());
        assertTrue(response.isVepPreRankingEnabled());
        assertEquals("PRESERVE_INPUT", response.getAnalysisAssemblyPolicy());
        assertEquals("READY", response.getVep().getStatus());
        assertEquals("READY", response.getVepProfiles().get("GRCh37").getStatus());
        assertEquals("116_GRCh37", response.getVepProfiles().get("GRCh37").getCacheVersion());
        assertEquals("READY", response.getVepProfiles().get("GRCh38").getStatus());
        assertEquals("116_GRCh38", response.getVepProfiles().get("GRCh38").getCacheVersion());
        assertTrue(response.getVep().getMessage().contains("GRCh37 READY"));
        assertTrue(response.getVep().getMessage().contains("GRCh38 READY"));
    }

    @Test
    void reportsOneReadyVepProfileWithoutPretendingBothAreReady() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        configureProfile(properties, "GRCh37");

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertEquals("READY", response.getVep().getStatus());
        assertEquals("READY", response.getVepProfiles().get("GRCh37").getStatus());
        assertEquals("MISSING_RESOURCE", response.getVepProfiles().get("GRCh38").getStatus());
    }

    @Test
    void reportsMissingGrch37FastaWithoutFallingBackToReadyGrch38() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        configureProfile(properties, "GRCh37");
        configureProfile(properties, "GRCh38");
        Path grch37Fasta = properties.getVep().getResources().get("GRCh37").getFasta();
        Files.delete(grch37Fasta.resolveSibling(grch37Fasta.getFileName() + ".fai"));
        Files.delete(grch37Fasta);

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertEquals("READY", response.getVep().getStatus());
        assertEquals("MISSING_RESOURCE", response.getVepProfiles().get("GRCh37").getStatus());
        assertEquals("READY", response.getVepProfiles().get("GRCh38").getStatus());
        assertTrue(response.getVepProfiles().get("GRCh37").getMissingResources()
                .contains("vep.resources.GRCh37.fasta.exists"));
    }

    @Test
    void reportsMissingGrch38CacheWithoutFallingBackToReadyGrch37() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        configureProfile(properties, "GRCh37");
        configureProfile(properties, "GRCh38");
        Files.delete(properties.getVep().getResources().get("GRCh38").getCacheDir());

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertEquals("READY", response.getVep().getStatus());
        assertEquals("READY", response.getVepProfiles().get("GRCh37").getStatus());
        assertEquals("MISSING_RESOURCE", response.getVepProfiles().get("GRCh38").getStatus());
        assertTrue(response.getVepProfiles().get("GRCh38").getMissingResources()
                .contains("vep.resources.GRCh38.cache-dir.exists"));
    }

    @Test
    void reportsReadyResourceButUnavailableTranscriptExecutionWhenExecutionDisabled() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(false);
        configureProfile(properties, "GRCh37");

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertFalse(response.isVepExecutionEnabled());
        assertEquals("READY", response.getVepProfiles().get("GRCh37").getStatus());
        assertFalse(response.getVepProfiles().get("GRCh37").isExecutionEnabled());
        assertEquals("UNAVAILABLE", response.getVepProfiles().get("GRCh37").getTranscriptAnnotationStatus());
    }

    @Test
    void reportsSpliceAiLocalFileReadiness() throws IOException {
        Path file = tempDir.resolve("spliceai.vcf.gz");
        Files.write(file, new byte[] {1});
        Files.write(tempDir.resolve("spliceai.vcf.gz.tbi"), new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getSpliceAi().setEnabled(true);
        properties.getSpliceAi().setGrch38File(file.toString());

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertTrue(response.getSpliceAi().isFileReady());
        assertTrue(response.getSpliceAi().isReady());
        assertEquals("LOCAL_SPLICEAI", response.getSpliceAi().getPrimaryEvidenceSource());
        assertEquals("VCF_INFO_SPLICEAI", response.getSpliceAi().getFallbackEvidenceSource());
        assertEquals(500, response.getSpliceAi().getBatchSize());
    }

    @Test
    void reportsSpliceAiSplitSnvAndIndelReadiness() throws IOException {
        Path snv = tempDir.resolve("spliceai_scores.masked.snv.hg38.vcf.gz");
        Path indel = tempDir.resolve("spliceai_scores.masked.indel.hg38.vcf.gz");
        Files.write(snv, new byte[] {1});
        Files.write(tempDir.resolve("spliceai_scores.masked.snv.hg38.vcf.gz.tbi"), new byte[] {1});
        Files.write(indel, new byte[] {1});
        Files.write(tempDir.resolve("spliceai_scores.masked.indel.hg38.vcf.gz.tbi"), new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getSpliceAi().setEnabled(true);
        properties.getSpliceAi().setSnvResourcePath(snv.toString());
        properties.getSpliceAi().setIndelResourcePath(indel.toString());

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertTrue(response.getSpliceAi().isFileReady());
        assertTrue(response.getSpliceAi().isReady());
        assertEquals("LOCAL_SPLICEAI", response.getSpliceAi().getPrimaryEvidenceSource());
    }

    @Test
    void reportsSpliceAiEnabledMissingResourceAsUnavailable() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getSpliceAi().setEnabled(true);

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertEquals("UNAVAILABLE", response.getSpliceAi().getStatus());
        assertFalse(response.getSpliceAi().isReady());
        assertTrue(response.getSpliceAi().getMissingResources().contains("spliceai.snv-resource"));
        assertTrue(response.getSpliceAi().getMissingResources().contains("spliceai.indel-resource"));
        assertTrue(response.getSpliceAi().getExplanation().contains("NO_RECORD"));
    }

    @Test
    void reportsLocalAndFallbackEvidenceSourcesForClinVarAndGnomad() throws IOException {
        Path clinVar = tempDir.resolve("clinvar.vcf.gz");
        Path gnomad = tempDir.resolve("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Files.write(clinVar, new byte[] {1});
        Files.write(tempDir.resolve("clinvar.vcf.gz.tbi"), new byte[] {1});
        Files.write(gnomad, new byte[] {1});
        Files.write(tempDir.resolve("gnomad.exomes.v4.1.max-af.chr1.tsv.gz.tbi"), new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setGrch38File(clinVar.toString());
        properties.getGnomad().setEnabled(true);
        properties.getGnomad().setGrch38CompactFiles(gnomad.toString());

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertTrue(response.getClinVar().isReady());
        assertEquals("LOCAL_CLINVAR", response.getClinVar().getPrimaryEvidenceSource());
        assertEquals("VCF_INFO_CLINVAR", response.getClinVar().getFallbackEvidenceSource());
        assertTrue(response.getGnomad().isReady());
        assertEquals("LOCAL_GNOMAD", response.getGnomad().getPrimaryEvidenceSource());
        assertEquals("VCF_INFO_GNOMAD", response.getGnomad().getFallbackEvidenceSource());
    }

    @Test
    void doesNotReportEmptyFilesOrIndexesAsReadyResources() throws IOException {
        Path cadd = tempDir.resolve("cadd.tsv.gz");
        Path caddIndex = tempDir.resolve("cadd.tsv.gz.tbi");
        Files.createFile(cadd);
        Files.createFile(caddIndex);

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setEnabled(true);
        properties.getCadd().setGrch38SnvFile(cadd.toString());

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertFalse(response.getCadd().isReady());
        assertFalse(response.getCadd().isSnvReady());
        assertEquals("UNAVAILABLE", response.getCadd().getStatus());
    }

    @Test
    void reportsNativeClinVarResourcesForBothAssemblies() throws IOException {
        Path grch37 = tempDir.resolve("clinvar.grch37.vcf.gz");
        Path grch38 = tempDir.resolve("clinvar.grch38.vcf.gz");
        Files.write(grch37, new byte[] {1});
        Files.write(tempDir.resolve("clinvar.grch37.vcf.gz.tbi"), new byte[] {1});
        Files.write(grch38, new byte[] {1});
        Files.write(tempDir.resolve("clinvar.grch38.vcf.gz.tbi"), new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setGrch37File(grch37.toString());
        properties.getClinVar().setGrch38File(grch38.toString());

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertTrue(response.getClinVar().isReady());
        assertEquals(2, response.getClinVar().getAvailableFileCount());
        assertTrue(response.getClinVar().getMessage().contains("GRCh37 and GRCh38"));
    }

    @Test
    void reportsClinVarCompactIndexAsPrimarySourceWhenEnabled() throws IOException {
        Path compact = tempDir.resolve("clinvar.compact.tsv");
        Files.write(compact, new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().getCompactIndex().setEnabled(true);
        properties.getClinVar().getCompactIndex().setFile(compact.toString());

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertTrue(response.getClinVar().isReady());
        assertEquals("LOCAL_CLINVAR_COMPACT", response.getClinVar().getPrimaryEvidenceSource());
        assertTrue(response.getClinVar().getMessage().contains("compact exact allele index"));
    }

    @Test
    void reportsClinVarCompactUnavailableWhenFallbackDisabled() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().getCompactIndex().setEnabled(true);
        properties.getClinVar().getCompactIndex().setFile(tempDir.resolve("missing.tsv").toString());
        properties.getClinVar().getCompactIndex().setAllowTabixFallback(false);

        VariantAnalysisProviderStatusResponse response =
                VariantAnalysisProviderStatusResponse.from(properties);

        assertEquals("UNAVAILABLE", response.getClinVar().getStatus());
        assertEquals("LOCAL_CLINVAR_COMPACT", response.getClinVar().getPrimaryEvidenceSource());
        assertTrue(response.getClinVar().getMessage().contains("fallback is disabled"));
    }

    private void configureProfile(VariantAnalysisProperties properties, String assembly) throws IOException {
        Path executable = tempDir.resolve("varvis-vep.cmd");
        Path cache = tempDir.resolve("cache").resolve("homo_sapiens").resolve("116_" + assembly);
        Path fasta = tempDir.resolve("Homo_sapiens." + assembly + ".fa");
        Files.write(executable, new byte[] {1});
        Files.createDirectories(cache);
        Files.write(fasta, new byte[] {1});
        Files.write(tempDir.resolve("Homo_sapiens." + assembly + ".fa.fai"), new byte[] {1});

        VariantAnalysisProperties.Vep.ResourceProfile profile =
                new VariantAnalysisProperties.Vep.ResourceProfile();
        profile.setEnabled(true);
        profile.setAssembly(assembly);
        profile.setExecutable(executable.toString());
        profile.setCacheDir(cache.toString());
        profile.setFasta(fasta.toString());
        profile.setVersion("116.0");
        profile.setEnsemblCacheVersion("116_" + assembly);
        profile.setManeVersion("Ensembl_VEP_116_cache");
        properties.getVep().getResources().put(assembly, profile);
    }

}
