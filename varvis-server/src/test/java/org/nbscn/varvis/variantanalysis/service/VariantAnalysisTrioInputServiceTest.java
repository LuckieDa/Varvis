package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.service.impl.VariantAnalysisServiceImpl;
import org.nbscn.varvis.variantanalysis.trio.RequestBackedTrioPipelineInputProvider;
import org.nbscn.varvis.variantanalysis.trio.TrioInputMode;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInputResolver;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisTrioInputServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void jointTrioInputIsRegisteredAndSkipsReadyJobReuse() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        RequestBackedTrioPipelineInputProvider provider = new RequestBackedTrioPipelineInputProvider();
        VariantAnalysisServiceImpl service = service(repository, processor, provider);
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "JOINT_VCF",
                true,
                "proband",
                "father",
                "mother",
                null,
                null,
                true,
                false,
                false
        );

        VariantAnalysisJob job = service.submit(
                jointVcf(),
                "GRCh38",
                "WES",
                "proband",
                Collections.emptyList(),
                null,
                request,
                null,
                null
        );
        TrioPipelineInput input = provider.inputFor(job);

        assertTrue(input.isPresent());
        assertEquals(TrioInputMode.JOINT_VCF, input.getInputMode());
        assertEquals("father", input.getSampleMapping().getFatherSampleId());
        verify(repository, never()).findReusableReadyJob(
                any(String.class),
                eq(GenomeAssembly.GRCh38.name()),
                eq(AnalysisMode.WES.name()),
                eq("proband")
        );
        verify(repository).appendEvent(
                job.getId(),
                VariantAnalysisStatus.UPLOADED,
                "Trio evidence input captured; evidence-only segregation analysis will be attempted"
        );
        verify(processor).process(eq(job.getId()), any());
    }

    @Test
    void threeVcfTrioInputStoresParentFiles() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        RequestBackedTrioPipelineInputProvider provider = new RequestBackedTrioPipelineInputProvider();
        VariantAnalysisServiceImpl service = service(repository, processor, provider);
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "THREE_SINGLE_SAMPLE_VCFS",
                true,
                "proband",
                "father",
                "mother",
                null,
                null,
                true,
                true,
                true
        );

        VariantAnalysisJob job = service.submit(
                singleSampleVcf("proband"),
                "GRCh38",
                "WES",
                "proband",
                Collections.emptyList(),
                null,
                request,
                singleSampleVcf("father"),
                singleSampleVcf("mother")
        );
        TrioPipelineInput input = provider.inputFor(job);

        assertTrue(input.isPresent());
        assertEquals(TrioInputMode.THREE_SINGLE_SAMPLE_VCFS, input.getInputMode());
        assertTrue(java.nio.file.Files.isRegularFile(input.getProbandVcfPath()));
        assertTrue(java.nio.file.Files.isRegularFile(input.getFatherVcfPath()));
        assertTrue(java.nio.file.Files.isRegularFile(input.getMotherVcfPath()));
    }

    @Test
    void invalidTrioInputReturnsBadRequestBeforeJobProcessing() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisServiceImpl service = service(repository, processor, new RequestBackedTrioPipelineInputProvider());
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "THREE_SINGLE_SAMPLE_VCFS",
                true,
                "proband",
                "father",
                "mother",
                null,
                null,
                true,
                false,
                true
        );

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.submit(
                singleSampleVcf("proband"),
                "GRCh38",
                "WES",
                "proband",
                Collections.emptyList(),
                null,
                request,
                null,
                singleSampleVcf("mother")
        ));

        assertTrue(String.valueOf(error.getReason()).contains("FATHER_VCF_REQUIRED"));
        verify(processor, never()).process(any(String.class), any());
    }

    @Test
    void jointTrioInputRejectsSampleMissingFromHeader() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisServiceImpl service = service(repository, processor, new RequestBackedTrioPipelineInputProvider());
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "JOINT_VCF",
                true,
                "missing-proband",
                "father",
                "mother",
                null,
                null,
                true,
                false,
                false
        );

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.submit(
                jointVcf(),
                "GRCh38",
                "WES",
                "missing-proband",
                Collections.emptyList(),
                null,
                request,
                null,
                null
        ));

        assertTrue(String.valueOf(error.getReason()).contains("TRIO_SAMPLE_NOT_FOUND_IN_JOINT_VCF"));
        verify(processor, never()).process(any(String.class), any());
    }

    @Test
    void exomiserAcceptsOnlyACompleteExplicitJointTrioRequest() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        RequestBackedTrioPipelineInputProvider provider = new RequestBackedTrioPipelineInputProvider();
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        when(repository.findReusableReadyJob(any(String.class), any(String.class), any(String.class), any(String.class)))
                .thenReturn(Optional.<VariantAnalysisJob>empty());
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(repository,
                mock(VariantEnhancementRepository.class), processor, mock(VariantEnhancementProcessor.class),
                properties, provider, new TrioPipelineInputResolver());
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true, "JOINT_VCF", true, "proband", "father", "mother",
                "FEMALE", "MALE", "FEMALE", true, false, false,
                null, null, true, false, false);

        VariantAnalysisJob job = service.submit(jointVcf(), "GRCh38", "WES", "proband",
                Collections.emptyList(), null, request, null, null);

        assertTrue(provider.inputFor(job).isPresent());
        assertEquals("proband", job.getRequestedSampleId());
        verify(processor).process(eq(job.getId()), any());
    }

    // Seed the deprecated reuse API to preserve the legacy-input test fixture.
    private VariantAnalysisServiceImpl service(VariantAnalysisJobRepository repository,
                                               VariantAnalysisJobProcessor processor,
                                               RequestBackedTrioPipelineInputProvider provider) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        when(repository.findReusableReadyJob(
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class)
        )).thenReturn(Optional.<VariantAnalysisJob>empty());
        return new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties,
                provider,
                new TrioPipelineInputResolver()
        );
    }

    private MockMultipartFile jointVcf() {
        return new MockMultipartFile(
                "vcfFile",
                "joint.vcf",
                "text/plain",
                ("##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tmother\tproband\tfather\n"
                        + "1\t100\t.\tA\tT\t.\tPASS\tGENE=GENE1\tGT:DP:GQ\t0/0:20:99\t0/1:20:99\t0/0:20:99\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile singleSampleVcf(String sampleId) {
        return new MockMultipartFile(
                sampleId + "VcfFile",
                sampleId + ".vcf",
                "text/plain",
                ("##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t" + sampleId + "\n"
                        + "1\t100\t.\tA\tT\t.\tPASS\tGENE=GENE1\tGT:DP:GQ\t0/1:20:99\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
    }
}
