package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.AnnTranscriptAnnotationProvider;
import org.nbscn.varvis.variantanalysis.provider.VepReadinessReport;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranscriptAnnotationServiceTest {

    @Test
    void vepDisabledUsesAnnFallbackWithoutExternalExecution() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        TranscriptAnnotationService service = service(properties);
        VariantInterpretation variant = variantWithAnn();

        service.annotate(Collections.singletonList(variant), context());

        TranscriptConsequence annotation = variant.getTranscriptAnnotations().get(0);
        assertEquals("GENE1", annotation.getGeneSymbol());
        assertEquals("ENST000001", annotation.getTranscriptId());
        assertEquals("missense_variant", annotation.getConsequence());
        assertEquals("c.1A>G", annotation.getHgvsc());
        assertEquals("p.Lys1Arg", annotation.getHgvsp());
        assertEquals("ANN_ONLY", annotation.getSource());
        assertEquals("COMPLETED", annotation.getStatus());
    }

    @Test
    void noAnnAndVepDisabledReturnsNoRecordTranscriptAnnotation() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        TranscriptAnnotationService service = service(properties);
        VariantInterpretation variant = variantWithoutAnn();

        service.annotate(Collections.singletonList(variant), context());

        TranscriptConsequence annotation = variant.getTranscriptAnnotations().get(0);
        assertEquals("ANN_ONLY", annotation.getSource());
        assertEquals("NO_RECORD", annotation.getStatus());
    }

    @Test
    void noAnnAndReadyVepMarksCandidateForAsyncTranscriptAnnotation() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        TranscriptAnnotationService service = readyVepService(properties);
        VariantInterpretation variant = variantWithoutAnn();

        service.annotate(Collections.singletonList(variant), context());

        TranscriptConsequence annotation = variant.getTranscriptAnnotations().get(0);
        assertEquals("VEP_PENDING", annotation.getSource());
        assertEquals("PENDING", annotation.getStatus());
    }

    @Test
    void vepEnabledStillUsesAnnFallbackDuringBaseAnalysis() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        TranscriptAnnotationService service = service(properties);
        VariantInterpretation variant = variantWithAnn();

        service.annotate(Collections.singletonList(variant), context());

        TranscriptConsequence annotation = variant.getTranscriptAnnotations().get(0);
        assertEquals("ANN_ONLY", annotation.getSource());
        assertEquals("COMPLETED", annotation.getStatus());
    }

    @Test
    void cacheKeyIncludesVariantAndTranscriptResourceVersions() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setVersion("vep-110");
        properties.getVep().setEnsemblCacheVersion("110_GRCh38");
        properties.getVep().setManeVersion("MANE-1.4");
        properties.getVep().setLofteeVersion("loftee-1.0");
        TranscriptAnnotationService service = service(properties);

        TranscriptAnnotationCacheKey key = service.cacheKey(variantWithAnn());

        assertEquals("GRCh38", key.getGenomeAssembly());
        assertEquals("1", key.getChromosome());
        assertEquals(100, key.getPosition());
        assertEquals("A", key.getReference());
        assertEquals("G", key.getAlternate());
        assertEquals("vep-110", key.getVepVersion());
        assertEquals("110_GRCh38", key.getEnsemblCacheVersion());
        assertEquals("MANE-1.4", key.getManeVersion());
        assertEquals("loftee-1.0", key.getLofteeVersion());
        assertEquals("vep", key.getProviderName());
        assertEquals(64, key.getOptionsHash().length());
    }

    @Test
    void cacheKeyNormalizesChromosomeButSeparatesAssembly() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        TranscriptAnnotationService service = service(properties);

        TranscriptAnnotationCacheKey grch38Chr = service.cacheKey(variant("GRCh38", "chr4"));
        TranscriptAnnotationCacheKey grch38NoChr = service.cacheKey(variant("GRCh38", "4"));
        TranscriptAnnotationCacheKey grch37NoChr = service.cacheKey(variant("GRCh37", "4"));

        assertEquals(grch38Chr, grch38NoChr);
        assertEquals("4", grch38Chr.getChromosome());
        org.junit.jupiter.api.Assertions.assertNotEquals(grch38Chr, grch37NoChr);
    }

    private TranscriptAnnotationService service(VariantAnalysisProperties properties) {
        return new TranscriptAnnotationService(
                properties,
                new AnnTranscriptAnnotationProvider(),
                new InMemoryTranscriptAnnotationCache()
        );
    }

    private TranscriptAnnotationService readyVepService(VariantAnalysisProperties properties) {
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        properties.getVep().setVersion("vep-test");
        return new TranscriptAnnotationService(
                properties,
                new AnnTranscriptAnnotationProvider(),
                new InMemoryTranscriptAnnotationCache(),
                new VepResourceValidator(properties) {
                    @Override
                    public VepReadinessReport validate() {
                        return validate(GenomeAssembly.GRCh38);
                    }

                    @Override
                    public VepReadinessReport validate(GenomeAssembly assembly) {
                        return new VepReadinessReport(
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                false,
                                false,
                                false,
                                false,
                                "vep-test",
                                "110_GRCh38",
                                "MANE-test",
                                "LOFTEE-test",
                                "READY",
                                Collections.emptyList(),
                                Collections.emptyList(),
                                "VEP resources are ready and execution is explicitly enabled."
                        );
                    }
                }
        );
    }

    private AnalysisContext context() {
        return new AnalysisContext("analysis", GenomeAssembly.GRCh38, AnalysisMode.WES);
    }

    private VariantInterpretation variantWithAnn() {
        return new VariantInterpretation(
                "GRCh38:1:100:A:G",
                observation(),
                Collections.singletonList(TranscriptConsequence.ann(
                        "GENE1",
                        "ENSG000001",
                        "ENST000001",
                        "transcript",
                        "missense_variant",
                        "MODERATE",
                        "c.1A>G",
                        "p.Lys1Arg",
                        "1/10",
                        null,
                        "1/300",
                        null,
                        null,
                        Instant.now()
                )),
                Collections.emptyList()
        );
    }

    private VariantInterpretation variantWithoutAnn() {
        return new VariantInterpretation(
                "GRCh38:1:100:A:G",
                observation(),
                Collections.singletonList(new TranscriptConsequence(
                        null,
                        null,
                        null,
                        null,
                        null,
                        "SNV"
                )),
                Collections.emptyList()
        );
    }

    private VariantInterpretation variant(String assembly, String chromosome) {
        return new VariantInterpretation(
                assembly + ":" + chromosome + ":100:A:G",
                new VariantQcObservation(
                        new NormalizedVariantKey(assembly, chromosome, 100, "A", "G"),
                        "proband",
                        "0/1",
                        30,
                        99,
                        "18,12",
                        0.4d,
                        "PASS",
                        "SNV",
                        chromosome + "|100|A|G|1"
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private VariantQcObservation observation() {
        return new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "G"),
                "proband",
                "0/1",
                30,
                99,
                "18,12",
                0.4d,
                "PASS",
                "SNV",
                "chr1|100|A|G|1"
        );
    }
}
