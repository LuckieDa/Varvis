package org.nbscn.varvis.variantanalysis.controller;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisExportService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisPreflightService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisService;
import org.nbscn.varvis.variantanalysis.service.VcfHeaderSampleDetectionService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VariantAnalysisHistoryControllerTest {
    @Test
    void historyResponsePreservesStatusesAndDoesNotClaimCurrentResourceReuse() throws Exception {
        var service = mock(VariantAnalysisService.class);
        var file = new MockMultipartFile("vcfFile", "synthetic.vcf", "text/plain", new byte[] {1});
        var ready = job("converted-ready");
        ready.complete("proband", true, "PICARD;LIFTOVER", null, List.of(), List.of());
        var running = job("already-running");
        running.transitionTo(VariantAnalysisStatus.RANKING);
        when(service.findHistory(file)).thenReturn(List.of(ready, running));

        mvc(service).perform(multipart("/variant-analyses/history").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchBasis").value("EXACT_FILE_SHA256"))
                .andExpect(jsonPath("$.historyOnly").value(true))
                .andExpect(jsonPath("$.matches.length()").value(2))
                .andExpect(jsonPath("$.matches[0].id").value("converted-ready"))
                .andExpect(jsonPath("$.matches[0].status").value("READY"))
                .andExpect(jsonPath("$.matches[0].genomeAssembly").value("GRCh37"))
                .andExpect(jsonPath("$.matches[0].analysisMode").value("WES"))
                .andExpect(jsonPath("$.matches[0].requestedSampleId").value("proband"))
                .andExpect(jsonPath("$.matches[0].selectedSampleId").value("proband"))
                .andExpect(jsonPath("$.matches[0].createdAt").exists())
                .andExpect(jsonPath("$.matches[0].updatedAt").exists())
                .andExpect(jsonPath("$.matches[1].status").value("RANKING"))
                .andExpect(jsonPath("$.matches[0].workspace").doesNotExist())
                .andExpect(jsonPath("$.matches[0].uploadedFile").doesNotExist())
                .andExpect(jsonPath("$.matches[0].fileSha256").doesNotExist());
        verify(service).findHistory(file);
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingFileIsRejectedBeforeCallingAnalysisService() throws Exception {
        var service = mock(VariantAnalysisService.class);
        mvc(service).perform(multipart("/variant-analyses/history"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private MockMvc mvc(VariantAnalysisService service) {
        return MockMvcBuilders.standaloneSetup(new VariantAnalysisController(service,
                mock(VariantAnalysisExportService.class), mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class), new VariantAnalysisProperties())).build();
    }

    private VariantAnalysisJob job(String id) {
        return new VariantAnalysisJob(id, "synthetic.vcf", "a".repeat(64), GenomeAssembly.GRCh37,
                AnalysisMode.WES, "proband", Path.of("private-workspace"), Path.of("private-upload.vcf"));
    }
}
