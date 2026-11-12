package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.AnnTranscriptAnnotationProvider;
import org.nbscn.varvis.variantanalysis.provider.ExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.ProcessResult;
import org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.VepCommandBuilder;
import org.nbscn.varvis.variantanalysis.provider.VepJsonOutputParser;
import org.nbscn.varvis.variantanalysis.provider.VepReadinessReport;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealVepPreRankingSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void realVepProviderCacheAndMetricsSmoke() throws IOException {
        Assumptions.assumeTrue(
                enabled("VARVIS_RUN_REAL_VEP_SMOKE"),
                "Set VARVIS_RUN_REAL_VEP_SMOKE=true to run the real VEP tiny smoke."
        );
        GenomeAssembly assembly = GenomeAssembly.require(valueOrDefault(
                env("VARVIS_REAL_VEP_ASSEMBLY", "VARVIS_VEP_ASSEMBLY"),
                "GRCh38"
        ));

        VariantAnalysisProperties properties = realVepProperties(assembly);
        VepResourceValidator validator = new VepResourceValidator(properties);
        VepReadinessReport readiness = validator.validate(assembly);
        Assumptions.assumeTrue(
                "READY".equals(readiness.getStatus()) && readiness.isExecutionEnabled(),
                readiness.getExplanation()
        );

        Path cacheFile = localCacheFile();
        PersistentSmokeTranscriptAnnotationCache firstCache =
                new PersistentSmokeTranscriptAnnotationCache(cacheFile);
        TranscriptAnnotationService keyService = transcriptAnnotationService(properties, validator, firstCache);
        VariantInterpretation firstVariant = syntheticBrafSnv(assembly);
        TranscriptAnnotationCacheKey targetKey = keyService.cacheKey(firstVariant);
        firstCache.remove(targetKey);
        assertEquals(0, firstCache.matchingRows(targetKey));
        TranscriptAnnotationCacheKey grch38SameCoordinateKey = keyService.cacheKey(
                syntheticBrafSnv(GenomeAssembly.GRCh38, 140453136)
        );
        assertEquals(0, firstCache.matchingRows(grch38SameCoordinateKey),
                "GRCh38 cache lookup must not hit the GRCh37 smoke key");

        CountingExternalProcessRunner firstRunner = new CountingExternalProcessRunner();
        PreRankingTranscriptAnnotationService service =
                preRankingService(properties, validator, firstCache, firstRunner);

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary first =
                service.annotateCandidates(Collections.singletonList(firstVariant), context(assembly));

        assertEquals("AVAILABLE", first.getStatus());
        assertEquals(assembly.name(), targetKey.getGenomeAssembly());
        assertEquals("7", targetKey.getChromosome());
        assertEquals(140453136, targetKey.getPosition());
        assertEquals("A", targetKey.getReference());
        assertEquals("T", targetKey.getAlternate());
        assertEquals(1, first.getCacheMissCount());
        assertEquals(0, first.getCacheHitCount());
        assertTrue(first.getProviderBatchCount() >= 1);
        assertTrue(firstRunner.invocationCount() >= 1);
        assertTrue(firstRunner.invokedWith("--assembly", "GRCh37"));
        assertTrue(firstRunner.invokedPathContaining("GRCh37"));
        assertFalse(firstRunner.invokedPathContaining("GRCh38"));
        assertTrue(first.getAnnotatedAvailableCount() >= 1);
        assertTrue(first.getElapsedMillis() >= 0);
        assertEquals(properties.getVep().normalizedPreRankingProvider(), first.getProviderName());
        assertEquals("116.0", first.getProviderVersion());
        assertEquals("116_GRCh37", first.getCacheVersion());
        assertEquals(64, first.getOptionsHash().length());
        TranscriptConsequence firstAnnotation = assertHasBrafAnnotation(firstVariant);
        assertEquals(1, firstCache.matchingRows(targetKey));
        assertEquals("BRAF", firstCache.firstGeneSymbol(targetKey));

        VariantInterpretation secondVariant = syntheticBrafSnv(assembly);
        PersistentSmokeTranscriptAnnotationCache secondCache =
                new PersistentSmokeTranscriptAnnotationCache(cacheFile);
        CountingExternalProcessRunner secondRunner = new CountingExternalProcessRunner();
        PreRankingTranscriptAnnotationService secondService =
                preRankingService(properties, validator, secondCache, secondRunner);
        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary second =
                secondService.annotateCandidates(Collections.singletonList(secondVariant), context(assembly));

        assertEquals("AVAILABLE", second.getStatus());
        assertEquals(1, second.getCacheHitCount());
        assertEquals(0, second.getCacheMissCount());
        assertEquals(0, second.getProviderBatchCount());
        assertEquals(0, secondRunner.invocationCount());
        TranscriptConsequence secondAnnotation = assertHasBrafAnnotation(secondVariant);
        assertEquals(firstAnnotation.getGeneSymbol(), secondAnnotation.getGeneSymbol());
        assertEquals(firstAnnotation.getTranscriptId(), secondAnnotation.getTranscriptId());
        assertEquals(firstAnnotation.getConsequence(), secondAnnotation.getConsequence());
        assertEquals(1, secondCache.matchingRows(targetKey));
        assertEquals(0, secondCache.matchingRows(grch38SameCoordinateKey));

        System.out.println("P4.2_FIRST_RUN assembly=" + assembly.name()
                + " cacheMiss=" + first.getCacheMissCount()
                + " cacheHit=" + first.getCacheHitCount()
                + " providerBatchCount=" + first.getProviderBatchCount()
                + " providerVariantCount=" + firstRunner.invocationCount()
                + " annotatedAvailable=" + first.getAnnotatedAvailableCount()
                + " geneSymbol=" + firstAnnotation.getGeneSymbol()
                + " transcript=" + firstAnnotation.getTranscriptId()
                + " consequence=" + firstAnnotation.getConsequence()
                + " status=" + first.getStatus()
                + " optionsHash=" + first.getOptionsHash());
        System.out.println("P4.2_SECOND_RUN assembly=" + assembly.name()
                + " cacheMiss=" + second.getCacheMissCount()
                + " cacheHit=" + second.getCacheHitCount()
                + " providerBatchCount=" + second.getProviderBatchCount()
                + " providerVariantCount=" + secondRunner.invocationCount()
                + " annotatedAvailable=" + second.getAnnotatedAvailableCount()
                + " geneSymbol=" + secondAnnotation.getGeneSymbol()
                + " transcript=" + secondAnnotation.getTranscriptId()
                + " consequence=" + secondAnnotation.getConsequence()
                + " status=" + second.getStatus()
                + " resultConsistent=true");
    }

    @Test
    void missingRealVepConfigurationFailsOpenWithoutRunningProvider() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        properties.getVep().setPreRankingFailOpen(true);
        PreRankingTranscriptAnnotationService service =
                new PreRankingTranscriptAnnotationService(properties, new VepResourceValidator(properties), null);
        VariantInterpretation variant = syntheticBrafSnv();

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service.annotateCandidates(Collections.singletonList(variant), context(GenomeAssembly.GRCh38));

        assertEquals("UNAVAILABLE", summary.getStatus());
        assertTrue(summary.isFailOpenUsed());
        assertEquals("UNAVAILABLE", variant.getTranscriptAnnotations().get(0).getStatus());
        assertEquals(0, summary.getProviderBatchCount());

        properties.getVep().setPreRankingFailOpen(false);
        assertThrows(IllegalStateException.class, () ->
                new PreRankingTranscriptAnnotationService(properties, new VepResourceValidator(properties), null)
                        .annotateCandidates(Collections.singletonList(syntheticBrafSnv()), context(GenomeAssembly.GRCh38))
        );
    }

    private PreRankingTranscriptAnnotationService preRankingService(VariantAnalysisProperties properties,
                                                                    VepResourceValidator validator,
                                                                    TranscriptAnnotationCache cache,
                                                                    ExternalProcessRunner processRunner) {
        TranscriptAnnotationService transcriptAnnotationService = transcriptAnnotationService(
                properties,
                validator,
                cache
        );
        VepTranscriptAnnotationEnrichmentService vepService = new VepTranscriptAnnotationEnrichmentService(
                new InMemoryVariantAnalysisJobRepository(),
                properties,
                validator,
                new VepCommandBuilder(properties),
                new VepJsonOutputParser(new ObjectMapper()),
                processRunner == null ? new SystemExternalProcessRunner() : processRunner,
                transcriptAnnotationService
        );
        return new PreRankingTranscriptAnnotationService(properties, validator, vepService);
    }

    private TranscriptAnnotationService transcriptAnnotationService(VariantAnalysisProperties properties,
                                                                    VepResourceValidator validator,
                                                                    TranscriptAnnotationCache cache) {
        return new TranscriptAnnotationService(
                properties,
                new AnnTranscriptAnnotationProvider(),
                cache,
                validator
        );
    }

    private VariantAnalysisProperties realVepProperties(GenomeAssembly assembly) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantAnalysisProperties.Vep vep = properties.getVep();
        vep.setEnabled(true);
        vep.setExecutionEnabled(true);
        vep.setExecutable(requiredEnv(
                "VARVIS_VEP_" + assembly.name().toUpperCase() + "_EXECUTABLE",
                "VARVIS_VEP_EXECUTABLE",
                "VARVIS_VEP_BINARY"
        ));
        vep.setCacheDir(env(
                "VARVIS_VEP_" + assembly.name().toUpperCase() + "_CACHE",
                "VARVIS_VEP_CACHE_DIR"
        ));
        vep.setFasta(env(
                "VARVIS_VEP_" + assembly.name().toUpperCase() + "_FASTA",
                "VARVIS_VEP_FASTA"
        ));
        vep.setPluginsDir(env("VARVIS_VEP_PLUGINS_DIR"));
        vep.setLofteeEnabled(enabled("VARVIS_LOFTEE_ENABLED"));
        vep.setPreRankingEnabled(true);
        vep.setPreRankingMaxCandidates(3);
        vep.setPreRankingBatchSize(3);
        vep.setPreRankingCacheEnabled(true);
        vep.setPreRankingFailOpen(true);
        vep.setPreRankingTempDir(tempDir.resolve("vep-smoke").toString());
        vep.setPreRankingTimeoutSeconds(intValue(env("VARVIS_VEP_PRE_RANKING_TIMEOUT_SECONDS"), 120));
        vep.setVersion(valueOrDefault(env("VARVIS_VEP_VERSION"), "smoke-local-vep"));
        vep.setEnsemblCacheVersion(valueOrDefault(env("VARVIS_ENSEMBL_CACHE_VERSION"), "smoke-local-cache"));
        vep.setManeVersion(valueOrDefault(env("VARVIS_MANE_VERSION"), "not-configured"));
        vep.setLofteeVersion(valueOrDefault(env("VARVIS_LOFTEE_VERSION"), "not-configured"));
        VariantAnalysisProperties.Vep.ResourceProfile profile =
                new VariantAnalysisProperties.Vep.ResourceProfile();
        profile.setEnabled(true);
        profile.setAssembly(assembly.name());
        profile.setExecutable(requiredEnv(
                "VARVIS_VEP_" + assembly.name().toUpperCase() + "_EXECUTABLE",
                "VARVIS_VEP_EXECUTABLE",
                "VARVIS_VEP_BINARY"
        ));
        profile.setCacheDir(requiredEnv(
                "VARVIS_VEP_" + assembly.name().toUpperCase() + "_CACHE",
                assembly == GenomeAssembly.GRCh38 ? "VARVIS_VEP_CACHE_DIR" : "VARVIS_VEP_GRCH37_CACHE"
        ));
        profile.setFasta(requiredEnv(
                "VARVIS_VEP_" + assembly.name().toUpperCase() + "_FASTA",
                assembly == GenomeAssembly.GRCh38 ? "VARVIS_VEP_FASTA" : "VARVIS_VEP_GRCH37_FASTA"
        ));
        profile.setPluginsDir(env(
                "VARVIS_VEP_" + assembly.name().toUpperCase() + "_PLUGINS_DIR",
                "VARVIS_VEP_PLUGINS_DIR"
        ));
        profile.setVersion(valueOrDefault(
                env("VARVIS_VEP_" + assembly.name().toUpperCase() + "_VERSION", "VARVIS_VEP_VERSION"),
                "smoke-local-vep"
        ));
        profile.setEnsemblCacheVersion(valueOrDefault(
                env("VARVIS_VEP_" + assembly.name().toUpperCase() + "_CACHE_VERSION",
                        "VARVIS_ENSEMBL_CACHE_VERSION"),
                "smoke-local-cache"
        ));
        profile.setManeVersion(valueOrDefault(env("VARVIS_MANE_VERSION"), "not-configured"));
        profile.setLofteeVersion(valueOrDefault(env("VARVIS_LOFTEE_VERSION"), "not-configured"));
        vep.getResources().put(assembly.name(), profile);
        return properties;
    }

    private VariantInterpretation syntheticBrafSnv() {
        return syntheticBrafSnv(GenomeAssembly.GRCh38);
    }

    private VariantInterpretation syntheticBrafSnv(GenomeAssembly assembly) {
        int position = assembly == GenomeAssembly.GRCh37 ? 140453136 : 140753336;
        return syntheticBrafSnv(assembly, position);
    }

    private VariantInterpretation syntheticBrafSnv(GenomeAssembly assembly, int position) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey(assembly.name(), "7", position, "A", "T"),
                "synthetic-smoke",
                "0/1",
                40,
                99,
                "20,20",
                0.5d,
                "PASS",
                "SNV",
                "synthetic-smoke|" + assembly.name() + "|7|" + position + "|A|T"
        );
        return new VariantInterpretation(
                assembly.name() + ":7:" + position + ":A:T",
                observation,
                Collections.<TranscriptConsequence>emptyList(),
                Collections.emptyList(),
                "LOW_PRIORITY",
                10.0d,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private AnalysisContext context(GenomeAssembly assembly) {
        return new AnalysisContext("real-vep-tiny-smoke", assembly, AnalysisMode.WES);
    }

    private TranscriptConsequence assertHasBrafAnnotation(VariantInterpretation variant) {
        assertFalse(variant.getTranscriptAnnotations().isEmpty(), "transcriptAnnotations should not be empty");
        for (TranscriptConsequence annotation : variant.getTranscriptAnnotations()) {
            if ("BRAF".equals(annotation.getGeneSymbol())) {
                assertEquals("COMPLETED", annotation.getStatus());
                assertTrue(hasText(annotation.getTranscriptId()) || hasText(annotation.getTranscriptAccession()),
                        "real VEP annotation should contain transcript");
                assertTrue(hasText(annotation.getConsequence()),
                        "real VEP annotation should contain consequence");
                assertNotNull(annotation.getTimestamp());
                return annotation;
            }
        }
        throw new AssertionError("Expected real VEP annotation to contain BRAF.");
    }

    private Path localCacheFile() throws IOException {
        String configured = env("VARVIS_REAL_VEP_CACHE_FILE");
        Path cacheFile = hasText(configured)
                ? Paths.get(configured)
                : Paths.get("local", "grch37-real-vep-smoke", "grch37-smoke-cache.json");
        if (cacheFile.getParent() != null) {
            Files.createDirectories(cacheFile.getParent());
        }
        return cacheFile;
    }

    private String requiredEnv(String... names) {
        String value = env(names);
        Assumptions.assumeTrue(hasText(value),
                "Missing required environment variable: " + String.join(" or ", names));
        return value;
    }

    private String env(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean enabled(String name) {
        return "true".equalsIgnoreCase(env(name));
    }

    private int intValue(String value, int fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static class CountingExternalProcessRunner implements ExternalProcessRunner {

        private final ExternalProcessRunner delegate = new SystemExternalProcessRunner();
        private final List<List<String>> commands = new ArrayList<List<String>>();

        @Override
        public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
            commands.add(new ArrayList<String>(command));
            return delegate.run(command, workingDirectory, timeout);
        }

        private int invocationCount() {
            return commands.size();
        }

        private boolean invokedWith(String option, String value) {
            for (List<String> command : commands) {
                for (int index = 0; index < command.size() - 1; index++) {
                    if (option.equals(command.get(index)) && value.equals(command.get(index + 1))) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean invokedPathContaining(String value) {
            for (List<String> command : commands) {
                for (String part : command) {
                    if (part != null && part.contains(value)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static class PersistentSmokeTranscriptAnnotationCache implements TranscriptAnnotationCache {

        private final Path file;
        private final ObjectMapper objectMapper = new ObjectMapper();

        private PersistentSmokeTranscriptAnnotationCache(Path file) {
            this.file = file;
        }

        @Override
        public List<TranscriptConsequence> get(TranscriptAnnotationCacheKey key) {
            SmokeCacheRecord record = find(key);
            if (record == null || record.annotations == null || record.annotations.isEmpty()) {
                return null;
            }
            List<TranscriptConsequence> result =
                    new ArrayList<TranscriptConsequence>(record.annotations.size());
            for (SmokeAnnotationRecord annotation : record.annotations) {
                result.add(annotation.toTranscriptConsequence());
            }
            return result;
        }

        @Override
        public void put(TranscriptAnnotationCacheKey key, List<TranscriptConsequence> annotations) {
            if (key == null || annotations == null || annotations.isEmpty()) {
                return;
            }
            SmokeCacheFile cache = read();
            remove(cache, key);
            cache.records.add(SmokeCacheRecord.from(key, annotations));
            write(cache);
        }

        private void remove(TranscriptAnnotationCacheKey key) {
            SmokeCacheFile cache = read();
            remove(cache, key);
            write(cache);
        }

        private int matchingRows(TranscriptAnnotationCacheKey key) {
            SmokeCacheRecord record = find(key);
            return record == null ? 0 : 1;
        }

        private String firstGeneSymbol(TranscriptAnnotationCacheKey key) {
            SmokeCacheRecord record = find(key);
            if (record == null || record.annotations == null || record.annotations.isEmpty()) {
                return null;
            }
            return record.annotations.get(0).geneSymbol;
        }

        private SmokeCacheRecord find(TranscriptAnnotationCacheKey key) {
            if (key == null) {
                return null;
            }
            String hash = key.cacheKeyHash();
            for (SmokeCacheRecord record : read().records) {
                if (hash.equals(record.cacheKeyHash)) {
                    return record;
                }
            }
            return null;
        }

        private void remove(SmokeCacheFile cache, TranscriptAnnotationCacheKey key) {
            if (cache == null || cache.records == null || key == null) {
                return;
            }
            String hash = key.cacheKeyHash();
            List<SmokeCacheRecord> retained = new ArrayList<SmokeCacheRecord>();
            for (SmokeCacheRecord record : cache.records) {
                if (!hash.equals(record.cacheKeyHash)) {
                    retained.add(record);
                }
            }
            cache.records = retained;
        }

        private SmokeCacheFile read() {
            if (file == null || !Files.isRegularFile(file)) {
                return new SmokeCacheFile();
            }
            try {
                SmokeCacheFile cache = objectMapper.readValue(file.toFile(), SmokeCacheFile.class);
                return cache == null ? new SmokeCacheFile() : cache;
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read isolated smoke transcript annotation cache.", ex);
            }
        }

        private void write(SmokeCacheFile cache) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), cache);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to write isolated smoke transcript annotation cache.", ex);
            }
        }
    }

    public static class SmokeCacheFile {

        public List<SmokeCacheRecord> records = new ArrayList<SmokeCacheRecord>();
    }

    public static class SmokeCacheRecord {

        public String cacheKeyHash;
        public String genomeAssembly;
        public String chromosome;
        public int position;
        public String reference;
        public String alternate;
        public String providerName;
        public String providerVersion;
        public String cacheResourceVersion;
        public String optionsHash;
        public List<SmokeAnnotationRecord> annotations = new ArrayList<SmokeAnnotationRecord>();

        public static SmokeCacheRecord from(TranscriptAnnotationCacheKey key,
                                            List<TranscriptConsequence> annotations) {
            SmokeCacheRecord record = new SmokeCacheRecord();
            record.cacheKeyHash = key.cacheKeyHash();
            record.genomeAssembly = key.getGenomeAssembly();
            record.chromosome = key.getChromosome();
            record.position = key.getPosition();
            record.reference = key.getReference();
            record.alternate = key.getAlternate();
            record.providerName = key.getProviderName();
            record.providerVersion = key.getProviderVersion();
            record.cacheResourceVersion = key.getCacheResourceVersion();
            record.optionsHash = key.getOptionsHash();
            for (TranscriptConsequence annotation : annotations) {
                record.annotations.add(SmokeAnnotationRecord.from(annotation));
            }
            return record;
        }
    }

    public static class SmokeAnnotationRecord {

        public String geneSymbol;
        public String entrezGeneId;
        public String transcriptAccession;
        public String hgvsCoding;
        public String hgvsProtein;
        public String consequence;
        public String geneId;
        public String transcriptId;
        public String transcriptSource;
        public String impact;
        public String exon;
        public String intron;
        public Boolean canonical;
        public Boolean maneSelect;
        public Boolean manePlusClinical;
        public String proteinPosition;
        public String aminoAcids;
        public String codons;
        public String lof;
        public String lofFilter;
        public String lofFlags;
        public String source;
        public String status;
        public String version;
        public String timestamp;

        public static SmokeAnnotationRecord from(TranscriptConsequence annotation) {
            SmokeAnnotationRecord record = new SmokeAnnotationRecord();
            record.geneSymbol = annotation.getGeneSymbol();
            record.entrezGeneId = annotation.getEntrezGeneId();
            record.transcriptAccession = annotation.getTranscriptAccession();
            record.hgvsCoding = annotation.getHgvsc();
            record.hgvsProtein = annotation.getHgvsp();
            record.consequence = annotation.getConsequence();
            record.geneId = annotation.getGeneId();
            record.transcriptId = annotation.getTranscriptId();
            record.transcriptSource = annotation.getTranscriptSource();
            record.impact = annotation.getImpact();
            record.exon = annotation.getExon();
            record.intron = annotation.getIntron();
            record.canonical = annotation.getCanonical();
            record.maneSelect = annotation.getManeSelect();
            record.manePlusClinical = annotation.getManePlusClinical();
            record.proteinPosition = annotation.getProteinPosition();
            record.aminoAcids = annotation.getAminoAcids();
            record.codons = annotation.getCodons();
            record.lof = annotation.getLof();
            record.lofFilter = annotation.getLofFilter();
            record.lofFlags = annotation.getLofFlags();
            record.source = annotation.getSource();
            record.status = annotation.getStatus();
            record.version = annotation.getVersion();
            record.timestamp = annotation.getTimestamp() == null
                    ? null
                    : annotation.getTimestamp().toString();
            return record;
        }

        public TranscriptConsequence toTranscriptConsequence() {
            return new TranscriptConsequence(
                    geneSymbol,
                    entrezGeneId,
                    transcriptAccession,
                    hgvsCoding,
                    hgvsProtein,
                    consequence,
                    geneId,
                    transcriptId,
                    transcriptSource,
                    impact,
                    exon,
                    intron,
                    canonical,
                    maneSelect,
                    manePlusClinical,
                    proteinPosition,
                    aminoAcids,
                    codons,
                    lof,
                    lofFilter,
                    lofFlags,
                    source,
                    status,
                    version,
                    timestamp == null ? null : Instant.parse(timestamp)
            );
        }
    }
}
