package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.trio.TrioMember;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineEvidenceService;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.nbscn.varvis.variantanalysis.trio.TrioRole;
import org.nbscn.varvis.variantanalysis.trio.TrioSex;
import org.nbscn.varvis.variantanalysis.trio.TrioVcfSampleMapping;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VariantAnalysisJobProcessorTrioEvidenceTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledTrioPipelinePreservesExistingAnalysisBehavior() throws Exception {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantAnalysisJob job = saveJob(repository, "disabled-trio-job");

        processor(repository, properties, null).process(job.getId());

        VariantInterpretation persisted = repository.findInterpretations(job.getId(), 0, 1).get(0);
        assertNull(evidence(persisted, TrioPipelineEvidenceService.SOURCE));
        assertEquals(VariantAnalysisStatus.READY, repository.findById(job.getId()).get().getStatus());
    }

    @Test
    void trioEvidenceIsAttachedBeforeInterpretationsArePersistedWithoutChangingRanking() throws Exception {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getTrio().setEnabled(true);
        properties.getTrio().setPipelineEnabled(true);
        VariantAnalysisJob job = saveJob(repository, "trio-evidence-job");

        processor(
                repository,
                properties,
                jointInput("joint_trio_de_novo.synthetic.vcf", TrioSex.FEMALE)
        ).process(job.getId());

        VariantInterpretation persisted = repository.findInterpretations(job.getId(), 0, 1).get(0);
        assertEquals("REVIEW", persisted.getPriorityTier());
        assertEquals(10.0d, persisted.getRankingScore(), 0.0001d);
        assertEquals(VariantAnalysisBucket.CANDIDATE, persisted.getAnalysisBucket());
        EvidenceItem trio = evidence(persisted, TrioPipelineEvidenceService.SOURCE);
        assertNotNull(trio);
        assertEquals("APPLICABLE", trio.getApplicability());
        assertEquals("DE_NOVO_CANDIDATE", trio.getDetails().get("model"));
        assertEquals(TrioEvidenceReportConstants.RANKING_EFFECT, trio.getDetails().get("rankingEffect"));
        assertEquals(TrioEvidenceReportConstants.HARD_GATE_POLICY, trio.getDetails().get("hardGatePolicy"));
    }

    @Test
    void weightedTrioRankingAppliesBoundedScoreOnlyWhenFeatureFlagEnabled() throws Exception {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getTrio().setEnabled(true);
        properties.getTrio().setPipelineEnabled(true);
        properties.getTrio().setRankingEnabled(true);
        properties.getTrio().setRankingMaxScoreContribution(2.0d);
        VariantAnalysisJob job = saveJob(repository, "weighted-trio-job");

        processor(
                repository,
                properties,
                jointInput("joint_trio_de_novo.synthetic.vcf", TrioSex.FEMALE)
        ).process(job.getId());

        VariantInterpretation persisted = repository.findInterpretations(job.getId(), 0, 1).get(0);
        assertEquals("REVIEW", persisted.getPriorityTier());
        assertEquals(12.0d, persisted.getRankingScore(), 0.0001d);
        assertEquals(2.0d, persisted.getScoreBreakdown().get("trio_segregation_support"));
        assertEquals(VariantAnalysisBucket.CANDIDATE, persisted.getAnalysisBucket());
        EvidenceItem trio = evidence(persisted, TrioPipelineEvidenceService.SOURCE);
        assertNotNull(trio);
        assertNull(trio.getRawScore());
        assertEquals("FEATURE_FLAGGED_TRIO_WEIGHTED_RANKING_APPLIED",
                trio.getDetails().get("rankingEffect"));
        assertEquals(TrioEvidenceReportConstants.HARD_GATE_POLICY, trio.getDetails().get("hardGatePolicy"));
    }

    private VariantAnalysisJobProcessor processor(InMemoryVariantAnalysisJobRepository repository,
                                                  VariantAnalysisProperties properties,
                                                  TrioPipelineInput input) {
        VariantInterpretationService interpretationService = (normalizedVcf, observations, context) -> {
            VariantInterpretation interpretation = new VariantInterpretation(
                    "denovo-1",
                    observations.get(0),
                    Collections.emptyList(),
                    Collections.emptyList());
            interpretation.applyRanking(
                    "REVIEW",
                    10.0d,
                    Collections.singletonMap("base", 10.0d),
                    Collections.singletonList("Base ranking"),
                    Collections.emptyList());
            return Collections.singletonList(interpretation);
        };
        return new VariantAnalysisJobProcessor(
                repository,
                noOpBuildConverter(),
                copyOnlyNormalizer(),
                new org.nbscn.varvis.variantanalysis.service.impl.VcfQcServiceImpl(),
                interpretationService,
                properties,
                null,
                new PhenotypeAwareRerankingService(properties),
                null,
                null,
                new TrioPipelineEvidenceService(properties),
                job -> input == null ? TrioPipelineInput.absent() : input);
    }

    private VariantAnalysisJob saveJob(InMemoryVariantAnalysisJobRepository repository, String jobId) throws Exception {
        Path workspace = tempDir.resolve(jobId);
        Files.createDirectories(workspace);
        Path uploaded = workspace.resolve("analysis.vcf");
        Files.write(
                uploaded,
                ("##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\tdenovo-1\tA\tG\t.\tPASS\t.\tGT\t0/1\n")
                        .getBytes(StandardCharsets.UTF_8));
        VariantAnalysisJob job = new VariantAnalysisJob(
                jobId,
                "analysis.vcf",
                "sha256",
                GenomeAssembly.GRCh37,
                AnalysisMode.WES,
                "proband",
                workspace,
                uploaded);
        repository.save(job);
        return job;
    }

    private VcfBuildConverter noOpBuildConverter() {
        return (inputFile, workspace, inputAssembly, analysisAssembly) ->
                new BuildConversionResult(inputFile, false, "NONE", Collections.emptyList());
    }

    private VcfNormalizer copyOnlyNormalizer() {
        return (inputFile, workspace, genomeAssembly) ->
                new NormalizationResult(inputFile, false, "COPY_ONLY", Collections.emptyList());
    }

    private TrioPipelineInput jointInput(String resourceName, TrioSex probandSex) throws Exception {
        return TrioPipelineInput.jointVcf(
                resource(resourceName),
                new TrioVcfSampleMapping("proband", "father", "mother"),
                members(probandSex));
    }

    private List<TrioMember> members(TrioSex probandSex) {
        return Arrays.asList(
                new TrioMember(TrioRole.PROBAND, "proband", probandSex, true),
                new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false));
    }

    private Path resource(String resourceName) throws URISyntaxException {
        return java.nio.file.Paths.get(getClass().getResource("/trio/" + resourceName).toURI());
    }

    private EvidenceItem evidence(VariantInterpretation interpretation, String source) {
        for (EvidenceItem item : interpretation.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        return null;
    }

    private static class TrioEvidenceReportConstants {
        private static final String RANKING_EFFECT = "EVIDENCE_ONLY_NO_RANKING_WEIGHT_CHANGE";
        private static final String HARD_GATE_POLICY = "TRIO_SUPPORT_DOES_NOT_OVERRIDE_HARD_GATES";
    }
}
