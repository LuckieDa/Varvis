package org.nbscn.varvis.service.variant.impl;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.dto.variant.response.CaseVcfUploadResponseDTO;
import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.nbscn.varvis.mapper.varvis.VariantOverviewMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class VariantCaseServiceImplTest {

    @Test
    void uploadRequiresAndReturnsExplicitGenomeAssembly() throws IOException {
        CaseVcfUploadResponseDTO response = service().uploadCaseVcf(vcf(), "GRCh38");

        assertNotNull(response.getCaseId());
        assertEquals("patient.vcf", response.getFileName());
        assertEquals("proband", response.getSampleName());
        assertEquals("GRCh38", response.getGenomeAssembly());
        assertEquals(1, response.getTotalVariants());
    }

    @Test
    void uploadRejectsMissingGenomeAssembly() {
        assertThrows(InvalidVcfUploadException.class, () -> service().uploadCaseVcf(vcf(), null));
    }

    @Test
    void uploadRejectsUnsupportedGenomeAssembly() {
        assertThrows(InvalidVcfUploadException.class, () -> service().uploadCaseVcf(vcf(), "GRCh37"));
    }

    private VariantCaseServiceImpl service() {
        return new VariantCaseServiceImpl(
                mock(NamedParameterJdbcTemplate.class),
                mock(VariantOverviewMapper.class));
    }

    private MockMultipartFile vcf() {
        String content = "##fileformat=VCFv4.2\n"
                + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                + "1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n";
        return new MockMultipartFile(
                "vcfFile", "patient.vcf", "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }
}
