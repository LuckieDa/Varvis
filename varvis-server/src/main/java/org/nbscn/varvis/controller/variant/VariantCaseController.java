package org.nbscn.varvis.controller.variant;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.response.CaseBandOverlapDTO;
import org.nbscn.varvis.dto.variant.response.CaseMatchedVariantDTO;
import org.nbscn.varvis.dto.variant.response.CaseVcfUploadResponseDTO;
import org.nbscn.varvis.dto.variant.response.SmallVariantDTO;
import org.nbscn.varvis.dto.variant.response.StructuralVariantDTO;
import org.nbscn.varvis.service.variant.VariantCaseService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/variantBasedMap/cases")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantCaseController {

    private final VariantCaseService variantCaseService;

    @PostMapping(value = "/vcf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CaseVcfUploadResponseDTO uploadCaseVcf(@RequestPart("vcfFile") MultipartFile vcfFile,
                                                  @RequestParam("genomeAssembly") String genomeAssembly) throws IOException {
        return variantCaseService.uploadCaseVcf(vcfFile, genomeAssembly);
    }

    @DeleteMapping("/{caseId}")
    public boolean removeCase(@PathVariable("caseId") String caseId) {
        return variantCaseService.removeCase(caseId);
    }

    @GetMapping("/{caseId}/smallVariants")
    public List<SmallVariantDTO> listCaseSmallVariants(
            @PathVariable("caseId") String caseId,
            @RequestParam(name = "chr", required = false) String chromosome,
            @RequestParam(name = "start", required = false) Long start,
            @RequestParam(name = "end", required = false) Long end,
            @RequestParam(name = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "200") Integer pageSize) {
        return variantCaseService.listCaseSmallVariants(caseId, chromosome, start, end, page, pageSize);
    }

    @GetMapping("/{caseId}/structuralVariants")
    public List<StructuralVariantDTO> listCaseStructuralVariants(
            @PathVariable("caseId") String caseId,
            @RequestParam(name = "chr", required = false) String chromosome,
            @RequestParam(name = "start", required = false) Long start,
            @RequestParam(name = "end", required = false) Long end,
            @RequestParam(name = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "200") Integer pageSize) {
        return variantCaseService.listCaseStructuralVariants(caseId, chromosome, start, end, page, pageSize);
    }

    @GetMapping("/{caseId}/genes/{geneSymbol}/smallVariants")
    public List<SmallVariantDTO> listCaseSmallVariantsByGene(
            @PathVariable("caseId") String caseId,
            @PathVariable("geneSymbol") String geneSymbol,
            @RequestParam(name = "chr", required = false) String chromosome,
            @RequestParam(name = "start", required = false) Long start,
            @RequestParam(name = "end", required = false) Long end,
            @RequestParam(name = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "200") Integer pageSize) {
        return variantCaseService.listCaseSmallVariantsByGene(
                caseId, geneSymbol, chromosome, start, end, page, pageSize
        );
    }

    @GetMapping("/{caseId}/genes/{geneSymbol}/structuralVariants")
    public List<StructuralVariantDTO> listCaseStructuralVariantsByGene(
            @PathVariable("caseId") String caseId,
            @PathVariable("geneSymbol") String geneSymbol,
            @RequestParam(name = "chr", required = false) String chromosome,
            @RequestParam(name = "start", required = false) Long start,
            @RequestParam(name = "end", required = false) Long end,
            @RequestParam(name = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "200") Integer pageSize) {
        return variantCaseService.listCaseStructuralVariantsByGene(
                caseId, geneSymbol, chromosome, start, end, page, pageSize
        );
    }

    @GetMapping("/{caseId}/chromosomes/{chr}/overlapBands")
    public List<CaseBandOverlapDTO> listCaseOverlapBandsByChromosome(
            @PathVariable("caseId") String caseId,
            @PathVariable("chr") String chromosome) {
        return variantCaseService.listCaseOverlapBandsByChromosome(caseId, chromosome);
    }

    @GetMapping("/{caseId}/matchedVariants")
    public List<CaseMatchedVariantDTO> listCaseMatchedVariants(
            @PathVariable("caseId") String caseId,
            @RequestParam(name = "chr", required = false) String chromosome,
            @RequestParam(name = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "200") Integer pageSize) {
        return variantCaseService.listCaseMatchedVariants(caseId, chromosome, page, pageSize);
    }
}
