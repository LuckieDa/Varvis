package org.nbscn.varvis.controller.variant;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.request.BandDiseaseQueryRequest;
import org.nbscn.varvis.dto.variant.request.DiseaseGeneQueryRequest;
import org.nbscn.varvis.dto.variant.response.DiseaseDetailDTO;
import org.nbscn.varvis.dto.variant.response.DiseaseSummaryDTO;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;
import org.nbscn.varvis.service.variant.VariantDiseaseService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/variantBasedMap")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantDiseaseController {

    private final VariantDiseaseService variantDiseaseService;

    @GetMapping("/bands/{bandId}/diseases")
    public List<DiseaseSummaryDTO> listDiseasesByBand(@PathVariable("bandId") Long bandId,
                                                      @RequestParam(name = "limit", required = false) Integer limit,
                                                      @RequestParam(name = "page", required = false) Integer page,
                                                      @RequestParam(name = "pageSize", required = false) Integer pageSize,
                                                      @RequestParam(name = "sortBy", required = false) String sortBy,
                                                      @RequestParam(name = "sortOrder", required = false) String sortOrder) {
        BandDiseaseQueryRequest request = new BandDiseaseQueryRequest();
        request.setBandId(bandId);
        request.setLimit(limit);
        request.setPage(page);
        request.setPageSize(pageSize);
        request.setSortBy(sortBy);
        request.setSortOrder(sortOrder);
        return variantDiseaseService.listDiseasesByBand(request);
    }

    @GetMapping("/bands/{bandId}/genes")
    public List<GeneSummaryDTO> listGenesByBand(@PathVariable("bandId") Long bandId) {
        return variantDiseaseService.listGenesByBand(bandId);
    }

    @GetMapping("/diseases/{diseaseId}")
    public DiseaseDetailDTO getDisease(@PathVariable("diseaseId") String diseaseId) {
        return variantDiseaseService.getDiseaseDetail(diseaseId);
    }

    @GetMapping("/diseases/{diseaseId}/detail")
    public DiseaseDetailDTO getDiseaseDetail(@PathVariable("diseaseId") String diseaseId) {
        return variantDiseaseService.getDiseaseDetail(diseaseId);
    }

    @GetMapping("/diseases/{diseaseId}/genes")
    public List<GeneSummaryDTO> listGenesByDisease(@PathVariable("diseaseId") String diseaseId,
                                                   @RequestParam(name = "limit", required = false) Integer limit,
                                                   @RequestParam(name = "page", required = false) Integer page,
                                                   @RequestParam(name = "pageSize", required = false) Integer pageSize,
                                                   @RequestParam(name = "sortBy", required = false) String sortBy,
                                                   @RequestParam(name = "sortOrder", required = false) String sortOrder) {
        DiseaseGeneQueryRequest request = new DiseaseGeneQueryRequest();
        request.setDiseaseId(diseaseId);
        request.setLimit(limit);
        request.setPage(page);
        request.setPageSize(pageSize);
        request.setSortBy(sortBy);
        request.setSortOrder(sortOrder);
        return variantDiseaseService.listGenesByDisease(request);
    }

    @GetMapping("/diseases/{diseaseId}/meta")
    public Map<String, Object> getDiseaseMeta(@PathVariable("diseaseId") String diseaseId) {
        return variantDiseaseService.getDiseaseMeta(diseaseId);
    }
}
