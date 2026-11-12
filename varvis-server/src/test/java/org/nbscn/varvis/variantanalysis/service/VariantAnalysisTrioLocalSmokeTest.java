package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.nbscn.varvis.variantanalysis.service.impl.VariantAnalysisServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.VcfInfoBasicVariantAnnotator;
import org.nbscn.varvis.variantanalysis.service.impl.VcfQcServiceImpl;
import org.nbscn.varvis.variantanalysis.trio.RequestBackedTrioPipelineInputProvider;
import org.nbscn.varvis.variantanalysis.trio.TrioEvidenceReport;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineEvidenceService;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInputResolver;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VariantAnalysisTrioLocalSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void localTinyJointTrioUploadAddsEvidenceOnlySegregationWithoutChangingRanking() throws Exception {
        Harness harness = harness();
        VariantAnalysisJob baseline = harness.service.submit(
                multipart("vcfFile", "joint_trio_de_novo.synthetic.vcf"),
                "GRCh37",
                "WES",
                "proband",
                Collections.emptyList(),
                null
        );
        VariantInterpretation baselineInterpretation = firstInterpretation(harness.repository, baseline.getId());

        VariantAnalysisJob trio = harness.service.submit(
                multipart("vcfFile", "joint_trio_de_novo.synthetic.vcf"),
                "GRCh37",
                "WES",
                "proband",
                Collections.emptyList(),
                null,
                trioRequest("JOINT_VCF", false, false),
                null,
                null
        );
        VariantInterpretation trioInterpretation = firstInterpretation(harness.repository, trio.getId());
        EvidenceItem trioEvidence = evidence(trioInterpretation, TrioPipelineEvidenceService.SOURCE);

        assertEquals(VariantAnalysisStatus.READY, harness.repository.findById(trio.getId()).get().getStatus());
        assertNull(evidence(baselineInterpretation, TrioPipelineEvidenceService.SOURCE));
        assertNotNull(trioEvidence);
        assertEquals("SEGREGATION", trioEvidence.getEvidenceType());
        assertNull(trioEvidence.getRawScore());
        assertEquals("DE_NOVO_CANDIDATE", trioEvidence.getDetails().get("model"));
        assertEquals("SUPPORTED", trioEvidence.getDetails().get("confidence"));
        assertEquals(TrioEvidenceReport.RANKING_EFFECT, trioEvidence.getDetails().get("rankingEffect"));
        assertEquals(TrioEvidenceReport.HARD_GATE_POLICY, trioEvidence.getDetails().get("hardGatePolicy"));
        assertEquals(baselineInterpretation.getRankingScore(), trioInterpretation.getRankingScore());
        assertEquals(baselineInterpretation.getPriorityTier(), trioInterpretation.getPriorityTier());
        assertEquals(baselineInterpretation.getAnalysisBucket(), trioInterpretation.getAnalysisBucket());
    }

    @Test
    void localTinyThreeVcfUploadPreservesUnobservedParentAsUninformativeEvidence() throws Exception {
        Harness harness = harness();

        VariantAnalysisJob trio = harness.service.submit(
                multipart("vcfFile", "three_single_proband.synthetic.vcf"),
                "GRCh37",
                "WES",
                "proband",
                Collections.emptyList(),
                null,
                trioRequest("THREE_SINGLE_SAMPLE_VCFS", true, true),
                multipart("fatherVcfFile", "three_single_father_missing.synthetic.vcf"),
                multipart("motherVcfFile", "three_single_mother.synthetic.vcf")
        );
        VariantInterpretation interpretation = firstInterpretation(harness.repository, trio.getId());
        EvidenceItem trioEvidence = evidence(interpretation, TrioPipelineEvidenceService.SOURCE);

        assertEquals(VariantAnalysisStatus.READY, harness.repository.findById(trio.getId()).get().getStatus());
        assertNotNull(trioEvidence);
        assertEquals("SEGREGATION", trioEvidence.getEvidenceType());
        assertNull(trioEvidence.getRawScore());
        assertEquals("UNINFORMATIVE", trioEvidence.getDetails().get("model"));
        assertEquals("UNINFORMATIVE", trioEvidence.getDetails().get("confidence"));
        assertEquals("UNOBSERVED", trioEvidence.getDetails().get("fatherGenotype"));
        assertEquals("HOM_REF (0/0)", trioEvidence.getDetails().get("motherGenotype"));
        assertFalse("DE_NOVO_CANDIDATE".equals(trioEvidence.getDetails().get("model")));
        assertEquals(VariantAnalysisBucket.CANDIDATE, interpretation.getAnalysisBucket());
    }

    @Test
    void localTinyUploadWithoutTrioInputDoesNotAttachTrioEvidence() throws Exception {
        Harness harness = harness();

        VariantAnalysisJob job = harness.service.submit(
                multipart("vcfFile", "joint_trio_de_novo.synthetic.vcf"),
                "GRCh37",
                "WES",
                "proband",
                Collections.emptyList(),
                null
        );
        VariantInterpretation interpretation = firstInterpretation(harness.repository, job.getId());

        assertEquals(VariantAnalysisStatus.READY, harness.repository.findById(job.getId()).get().getStatus());
        assertNull(evidence(interpretation, TrioPipelineEvidenceService.SOURCE));
    }

    private Harness harness() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        properties.setAnalysisAssemblyPolicy("PRESERVE_INPUT");
        properties.getTrio().setEnabled(true);
        properties.getTrio().setPipelineEnabled(true);
        properties.getTrio().setFailOpen(false);

        RequestBackedTrioPipelineInputProvider inputProvider = new RequestBackedTrioPipelineInputProvider();
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                noOpBuildConverter(),
                copyOnlyNormalizer(),
                new VcfQcServiceImpl(),
                new VariantInterpretationServiceImpl(
                        new VcfInfoBasicVariantAnnotator(),
                        Collections.emptyList(),
                        new TransparentVariantRankingService(properties)
                ),
                properties,
                null,
                new PhenotypeAwareRerankingService(properties),
                null,
                new TransparentVariantRankingService(properties),
                new TrioPipelineEvidenceService(properties),
                inputProvider
        );
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                org.mockito.Mockito.mock(VariantEnhancementRepository.class),
                processor,
                org.mockito.Mockito.mock(VariantEnhancementProcessor.class),
                properties,
                inputProvider,
                new TrioPipelineInputResolver()
        );
        return new Harness(repository, service);
    }

    private TrioAnalysisInputRequest trioRequest(String inputMode,
                                                 boolean fatherVcfProvided,
                                                 boolean motherVcfProvided) {
        return new TrioAnalysisInputRequest(
                true,
                inputMode,
                Boolean.TRUE,
                "proband",
                "father",
                "mother",
                null,
                null,
                true,
                fatherVcfProvided,
                motherVcfProvided
        );
    }

    private MockMultipartFile multipart(String partName, String resourceName) throws Exception {
        String safePartName = Objects.requireNonNull(partName, "partName");
        String safeResourceName = Objects.requireNonNull(resourceName, "resourceName");
        return new MockMultipartFile(
                safePartName,
                safeResourceName,
                "text/plain",
                Files.readAllBytes(resource(safeResourceName))
        );
    }

    private Path resource(String resourceName) throws URISyntaxException {
        return Paths.get(getClass().getResource("/trio/" + resourceName).toURI());
    }

    private VcfBuildConverter noOpBuildConverter() {
        return (inputFile, workspace, inputAssembly, analysisAssembly) ->
                new BuildConversionResult(inputFile, false, "NONE", Collections.emptyList());
    }

    private VcfNormalizer copyOnlyNormalizer() {
        return (inputFile, workspace, genomeAssembly) ->
                new NormalizationResult(inputFile, false, "COPY_ONLY", Collections.emptyList());
    }

    private VariantInterpretation firstInterpretation(InMemoryVariantAnalysisJobRepository repository, String jobId) {
        assertEquals(1, repository.countInterpretations(jobId));
        return repository.findInterpretations(jobId, 0, 1).get(0);
    }

    private EvidenceItem evidence(VariantInterpretation interpretation, String source) {
        for (EvidenceItem item : interpretation.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        return null;
    }

    private static final class Harness {
        private final InMemoryVariantAnalysisJobRepository repository;
        private final VariantAnalysisServiceImpl service;

        private Harness(InMemoryVariantAnalysisJobRepository repository, VariantAnalysisServiceImpl service) {
            this.repository = repository;
            this.service = service;
        }
    }
}
