package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisExportBundleResponse;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.nbscn.varvis.variantanalysis.service.impl.VariantAnalysisServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.VcfInfoBasicVariantAnnotator;
import org.nbscn.varvis.variantanalysis.service.impl.VcfQcServiceImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VariantAnalysisDemoSuiteTest {

    @TempDir
    Path tempDir;

    @Test
    void knownClinicalDemoRanksKnownClinicalAndExportsCsvAndJson() throws IOException {
        DemoRun run = process("known_clinical_pathogenic.vcf");

        VariantInterpretation top = top(run.repository, run.job.getId(), VariantInterpretationView.CANDIDATES);
        assertEquals("KNOWN_CLINICAL", top.getTier());
        assertEquals("VCF_INFO", evidence(top, "CLINVAR").getSourceVersion());
        assertEquals("VCF_INFO", evidence(top, "GNOMAD").getSourceVersion());
        assertEquals("111111", evidence(top, "VCF_IDENTIFIER").getDetails().get("clinVarVariationId"));

        VariantAnalysisExportService exportService = exportService(run);
        String csv = exportService.exportCsv(
                run.job.getId(),
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        assertTrue(csv.contains("rank,tier,chrom,pos"));
        assertTrue(csv.contains("KNOWN_CLINICAL"));
        assertTrue(csv.contains("Pathogenic"));

        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                run.job.getId(),
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        assertEquals("GRCh38", bundle.getReference().get("genomeBuild"));
        assertEquals("tier-breakdown-v1", bundle.getRanking().get("version"));
        assertFalse(bundle.getTopCandidates().isEmpty());
        assertNotNull(bundle.getProviderStatus());
        assertNotNull(bundle.getGeneratedAt());
    }

    @Test
    void commonBenignDemoIsDeprioritizedByClinVarAndFrequency() throws IOException {
        DemoRun run = process("common_benign.vcf");

        VariantInterpretation variant = top(run.repository, run.job.getId(), VariantInterpretationView.ALL);
        assertEquals("BENIGN_LIKELY", variant.getTier());
        assertTrue(text(variant.getNegativeReasons()).contains("benign"));
        assertEquals(1, run.repository.countInterpretations(run.job.getId(), VariantInterpretationView.CANDIDATES));
    }

    @Test
    void qcWarningDemoProducesFilteredQcReasons() throws IOException {
        DemoRun run = process("qc_warning.vcf");

        assertEquals(2, run.repository.countInterpretations(run.job.getId(), VariantInterpretationView.ALL));
        assertEquals(0, run.repository.countInterpretations(run.job.getId(), VariantInterpretationView.CANDIDATES));
        List<VariantInterpretation> variants = run.repository.findInterpretationSummaries(
                run.job.getId(),
                0,
                10,
                VariantInterpretationView.ALL
        );
        for (VariantInterpretation variant : variants) {
            assertEquals("FILTERED_QC", variant.getTier());
            assertFalse(variant.getFilterReasons().isEmpty());
        }
    }

    @Test
    void mixedPriorityDemoKeepsKnownClinicalFirstAndFilteredViewsSeparate() throws IOException {
        DemoRun run = process("mixed_priority.vcf");

        VariantInterpretation topCandidate =
                top(run.repository, run.job.getId(), VariantInterpretationView.CANDIDATES);
        assertEquals("KNOWN_CLINICAL", topCandidate.getTier());
        assertEquals(2, run.repository.countInterpretations(run.job.getId(), VariantInterpretationView.CANDIDATES));
        assertEquals(
                1,
                run.repository.countInterpretations(
                        run.job.getId(),
                        VariantInterpretationView.FREQUENCY_FILTERED
                )
        );
    }

    @Test
    void multiallelicDemoCreatesOneObservationPerAlternateAllele() throws IOException {
        DemoRun run = process("multiallelic_normalization.vcf");

        assertEquals(2, run.repository.countObservations(run.job.getId()));
        assertTrue(run.job.getWarnings().stream().anyMatch(message -> message.contains("multi-allelic")));
        List<VariantInterpretation> variants = run.repository.findInterpretationSummaries(
                run.job.getId(),
                0,
                10,
                VariantInterpretationView.ALL
        );
        assertEquals(2, variants.size());
        assertTrue(hasAlt(variants, "A"));
        assertTrue(hasAlt(variants, "T"));
    }

    private DemoRun process(String fileName) throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(repository, fileName);
        processor(repository, properties).process(job.getId());
        VariantAnalysisJob completed = repository.findById(job.getId()).orElse(null);
        assertNotNull(completed);
        assertEquals(VariantAnalysisStatus.READY, completed.getStatus());
        return new DemoRun(repository, completed, properties);
    }

    private VariantAnalysisJobProcessor processor(InMemoryVariantAnalysisJobRepository repository,
                                                  VariantAnalysisProperties properties) {
        return new VariantAnalysisJobProcessor(
                repository,
                noOpBuildConverter(),
                copyOnlyNormalizer(),
                new VcfQcServiceImpl(),
                new VariantInterpretationServiceImpl(
                        new VcfInfoBasicVariantAnnotator(properties),
                        Collections.emptyList(),
                        new TransparentVariantRankingService(properties)
                ),
                properties
        );
    }

    private VariantAnalysisExportService exportService(DemoRun run) {
        VariantAnalysisService analysisService = new VariantAnalysisServiceImpl(
                run.repository,
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                run.properties
        );
        return new VariantAnalysisExportService(analysisService, run.properties);
    }

    private VariantInterpretation top(InMemoryVariantAnalysisJobRepository repository,
                                      String analysisId,
                                      VariantInterpretationView view) {
        List<VariantInterpretation> variants = repository.findInterpretationSummaries(
                analysisId,
                0,
                10,
                view
        );
        assertFalse(variants.isEmpty());
        return variants.get(0);
    }

    private VariantAnalysisJob saveJob(InMemoryVariantAnalysisJobRepository repository,
                                       String fileName) throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve(fileName.replace(".vcf", "")));
        Path input = workspace.resolve("input.vcf");
        Files.copy(demoPath(fileName), input);
        VariantAnalysisJob job = new VariantAnalysisJob(
                fileName.replace(".vcf", ""),
                fileName,
                "demo-sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                workspace,
                input
        );
        repository.save(job);
        return job;
    }

    private Path demoPath(String fileName) {
        return Paths.get("src", "test", "resources", "variantanalysis", "demo", fileName);
    }

    private VcfBuildConverter noOpBuildConverter() {
        return (inputFile, workspace, inputAssembly, analysisAssembly) -> new BuildConversionResult(
                inputFile,
                false,
                "NONE",
                Collections.emptyList()
        );
    }

    private VcfNormalizer copyOnlyNormalizer() {
        return (inputFile, workspace, genomeAssembly) -> {
            Path output = workspace.resolve("qc-input.vcf");
            Files.copy(inputFile, output);
            return new NormalizationResult(
                    output,
                    false,
                    "COPY_ONLY",
                    Collections.singletonList("Reference-based normalization was not applied.")
            );
        };
    }

    private EvidenceItem evidence(VariantInterpretation variant, String source) {
        for (EvidenceItem item : variant.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        return null;
    }

    private boolean hasAlt(List<VariantInterpretation> variants, String alternate) {
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            if (alternate.equals(key.getAlternate())) {
                return true;
            }
        }
        return false;
    }

    private String text(List<String> values) {
        return String.join(" ", values).toLowerCase();
    }

    private static class DemoRun {

        private final InMemoryVariantAnalysisJobRepository repository;
        private final VariantAnalysisJob job;
        private final VariantAnalysisProperties properties;

        private DemoRun(InMemoryVariantAnalysisJobRepository repository,
                        VariantAnalysisJob job,
                        VariantAnalysisProperties properties) {
            this.repository = repository;
            this.job = job;
            this.properties = properties;
        }
    }
}
