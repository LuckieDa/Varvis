package org.nbscn.varvis.variantanalysis.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisVariantPageResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisJobResponse;
import org.nbscn.varvis.variantanalysis.dto.VcfSampleDetectionResult;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisExportService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisPreflightService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisService;
import org.nbscn.varvis.variantanalysis.service.VcfHeaderSampleDetectionService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VariantAnalysisControllerTest {

    @Test
    void explicitNewSubmissionForwardsScopeAndPhenotypeInputs() throws Exception {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisJob job = mock(VariantAnalysisJob.class);
        when(job.getId()).thenReturn("new-analysis");
        when(job.getStatus()).thenReturn(VariantAnalysisStatus.UPLOADED);
        when(job.getGenomeAssembly()).thenReturn(GenomeAssembly.GRCh38);
        when(job.getAnalysisMode()).thenReturn(AnalysisMode.WES);
        when(analysisService.submit(
                any(MockMultipartFile.class),
                eq("GRCh38"),
                eq("WES"),
                eq("proband"),
                eq(Collections.singletonList("HP:0000001")),
                isNull(),
                eq(Collections.singletonList("HP:0000002")),
                any(TrioAnalysisInputRequest.class),
                isNull(),
                isNull(),
                eq("INCLUDE_INTRONS"),
                eq(true)
        )).thenReturn(job);
        VariantAnalysisController controller = new VariantAnalysisController(
                analysisService,
                mock(VariantAnalysisExportService.class),
                mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class),
                new VariantAnalysisProperties()
        );
        MockMultipartFile vcf = new MockMultipartFile(
                "vcfFile", "synthetic.vcf", "text/plain", new byte[]{1});

        MockMvcBuilders.standaloneSetup(controller).build().perform(multipart("/variant-analyses").file(vcf)
                        .param("genomeAssembly", "GRCh38").param("analysisMode", "WES")
                        .param("analysisScope", "INCLUDE_INTRONS").param("createNewAnalysis", "true")
                        .param("sampleId", "proband").param("hpoTerms", "HP:0000001")
                        .param("excludedHpoTerms", "HP:0000002"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("new-analysis"));

        verify(analysisService).submit(
                eq(vcf),
                eq("GRCh38"),
                eq("WES"),
                eq("proband"),
                eq(Collections.singletonList("HP:0000001")),
                isNull(),
                eq(Collections.singletonList("HP:0000002")),
                any(TrioAnalysisInputRequest.class),
                isNull(),
                isNull(),
                eq("INCLUDE_INTRONS"),
                eq(true)
        );
    }

    @Test
    void submitReturnsCompletedPayloadWhenServiceRestoresAReadyAnalysis() throws Exception {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisJob job = mock(VariantAnalysisJob.class);
        when(job.getId()).thenReturn("existing-analysis");
        when(job.getStatus()).thenReturn(VariantAnalysisStatus.READY);
        when(job.getGenomeAssembly()).thenReturn(GenomeAssembly.GRCh38);
        when(job.getAnalysisMode()).thenReturn(AnalysisMode.WES);
        when(analysisService.submit(
                any(MockMultipartFile.class),
                eq("GRCh38"),
                eq("WES"),
                eq("proband"),
                isNull(),
                isNull(),
                isNull(),
                any(TrioAnalysisInputRequest.class),
                isNull(),
                isNull(),
                isNull(),
                eq(false)
        )).thenReturn(job);
        var counts = mock(org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts.class);
        when(analysisService.countInterpretations("existing-analysis")).thenReturn(counts);
        VariantAnalysisController controller = new VariantAnalysisController(
                analysisService,
                mock(VariantAnalysisExportService.class),
                mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class),
                new VariantAnalysisProperties()
        );
        MockMultipartFile vcf = new MockMultipartFile(
                "vcfFile", "synthetic.vcf", "text/plain", new byte[]{1});

        MockMvcBuilders.standaloneSetup(controller).build().perform(multipart("/variant-analyses").file(vcf)
                        .param("genomeAssembly", "GRCh38").param("analysisMode", "WES").param("sampleId", "proband"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("existing-analysis"))
                .andExpect(jsonPath("$.interpretationCounts").exists());
        verify(analysisService).countInterpretations("existing-analysis");
    }

    @Test
    void statusPollingSkipsHeavyCountsUntilAnalysisIsReady() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisJob job = mock(VariantAnalysisJob.class);
        when(job.getStatus()).thenReturn(VariantAnalysisStatus.RANKING);
        when(job.getGenomeAssembly()).thenReturn(GenomeAssembly.GRCh37);
        when(job.getAnalysisMode()).thenReturn(AnalysisMode.WES);
        when(analysisService.get("analysis")).thenReturn(job);
        VariantAnalysisController controller = new VariantAnalysisController(
                analysisService,
                mock(VariantAnalysisExportService.class),
                mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class),
                new VariantAnalysisProperties()
        );

        VariantAnalysisJobResponse response = controller.get("analysis");

        assertEquals(VariantAnalysisStatus.RANKING, response.getStatus());
        assertNull(response.getInterpretationCounts());
        verify(analysisService, never()).countInterpretations(anyString());
    }

    @Test
    void listVariantsAcceptsPhenotypeFilterParameters() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = mock(VariantAnalysisExportService.class);
        when(analysisService.listVariants(
                eq("analysis"),
                eq(2),
                eq(25),
                eq(VariantInterpretationView.CANDIDATES),
                any(VariantInterpretationFilter.class)
        )).thenReturn(emptyPage());
        VariantAnalysisController controller = new VariantAnalysisController(
                analysisService,
                exportService,
                mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class),
                new VariantAnalysisProperties()
        );

        controller.listVariants(
                "analysis",
                2,
                25,
                "CANDIDATES",
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                "mecp2",
                0.8d,
                null,
                null,
                null,
                null
        );

        ArgumentCaptor<VariantInterpretationFilter> filterCaptor =
                ArgumentCaptor.forClass(VariantInterpretationFilter.class);
        verify(analysisService).listVariants(
                eq("analysis"),
                eq(2),
                eq(25),
                eq(VariantInterpretationView.CANDIDATES),
                filterCaptor.capture()
        );
        VariantInterpretationFilter filter = filterCaptor.getValue();
        assertTrue(filter.getHasPhenotypeSupport());
        assertEquals("mecp2", filter.getPhenotypeGene());
        assertEquals(0.8d, filter.getMinPhenotypeScore());
    }

    @Test
    void exportsAcceptPhenotypeFilterParameters() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = mock(VariantAnalysisExportService.class);
        when(exportService.exportCsv(
                eq("analysis"),
                eq(50),
                eq(VariantInterpretationView.ALL),
                any(VariantInterpretationFilter.class)
        )).thenReturn("csv");
        VariantAnalysisController controller = new VariantAnalysisController(
                analysisService,
                exportService,
                mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class),
                new VariantAnalysisProperties()
        );

        controller.exportCsv(
                "analysis",
                50,
                "ALL",
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                "SCN1A",
                0.7d,
                null,
                null,
                null,
                null
        );

        ArgumentCaptor<VariantInterpretationFilter> filterCaptor =
                ArgumentCaptor.forClass(VariantInterpretationFilter.class);
        verify(exportService).exportCsv(
                eq("analysis"),
                eq(50),
                eq(VariantInterpretationView.ALL),
                filterCaptor.capture()
        );
        VariantInterpretationFilter filter = filterCaptor.getValue();
        assertTrue(filter.getHasPhenotypeSupport());
        assertEquals("SCN1A", filter.getPhenotypeGene());
        assertEquals(0.7d, filter.getMinPhenotypeScore());
    }

    @Test
    void listVariantsAcceptsLargeFileFilterParameters() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = mock(VariantAnalysisExportService.class);
        when(analysisService.listVariants(
                eq("analysis"),
                eq(1),
                eq(20),
                eq(VariantInterpretationView.ALL),
                any(VariantInterpretationFilter.class)
        )).thenReturn(emptyPage());
        VariantAnalysisController controller = new VariantAnalysisController(
                analysisService,
                exportService,
                mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class),
                new VariantAnalysisProperties()
        );

        controller.listVariants(
                "analysis",
                1,
                20,
                "ALL",
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "FILTERED_COMMON,DEFERRED_LOW_PRIORITY",
                true,
                true,
                true
        );

        ArgumentCaptor<VariantInterpretationFilter> filterCaptor =
                ArgumentCaptor.forClass(VariantInterpretationFilter.class);
        verify(analysisService).listVariants(
                eq("analysis"),
                eq(1),
                eq(20),
                eq(VariantInterpretationView.ALL),
                filterCaptor.capture()
        );
        VariantInterpretationFilter filter = filterCaptor.getValue();
        assertTrue(filter.getAnalysisBucketValues().contains("FILTERED_COMMON"));
        assertTrue(filter.getAnalysisBucketValues().contains("DEFERRED_LOW_PRIORITY"));
        assertTrue(filter.getSummaryOnly());
        assertTrue(filter.getLargeFileMode());
        assertTrue(filter.getExpensiveAnnotationSkipped());
    }

    @Test
    void sampleDetectionDelegatesToHeaderService() {
        VcfHeaderSampleDetectionService detectionService = mock(VcfHeaderSampleDetectionService.class);
        MockMultipartFile vcfFile = new MockMultipartFile(
                "vcfFile",
                "joint.vcf",
                "text/plain",
                new byte[] { 1 }
        );
        when(detectionService.detect(vcfFile)).thenReturn(VcfSampleDetectionResult.success(
                Arrays.asList("mother", "proband", "father"),
                Collections.singletonList("1"),
                "GRCh38",
                "VCF",
                Collections.<String>emptyList()
        ));
        VariantAnalysisController controller = new VariantAnalysisController(
                mock(VariantAnalysisService.class),
                mock(VariantAnalysisExportService.class),
                mock(VariantAnalysisPreflightService.class),
                detectionService,
                new VariantAnalysisProperties()
        );

        VcfSampleDetectionResult result = controller.detectSamples(vcfFile);

        assertEquals(3, result.getSamples().size());
        assertEquals("proband", result.getSamples().get(1));
        verify(detectionService).detect(vcfFile);
    }

    private VariantAnalysisVariantPageResponse emptyPage() {
        return new VariantAnalysisVariantPageResponse(
                "analysis",
                VariantInterpretationView.CANDIDATES,
                1,
                25,
                0,
                0,
                0,
                0,
                0.01d,
                Collections.emptyList()
        );
    }
}
