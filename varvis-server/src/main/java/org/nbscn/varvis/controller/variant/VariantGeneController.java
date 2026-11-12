package org.nbscn.varvis.controller.variant;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.request.GeneSearchRequest;
import org.nbscn.varvis.dto.variant.response.DiseaseSummaryDTO;
import org.nbscn.varvis.dto.variant.response.GeneDetailDTO;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;
import org.nbscn.varvis.entity.variant.ClingenGeneCurationEntity;
import org.nbscn.varvis.entity.variant.GeneCoordinateEntity;
import org.nbscn.varvis.service.variant.VariantGeneService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/variantBasedMap/genes")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantGeneController {

    private final VariantGeneService variantGeneService;

    @GetMapping("/{geneSymbol}")
    public GeneDetailDTO getGene(@PathVariable("geneSymbol") String geneSymbol) {
        return variantGeneService.getGeneDetail(geneSymbol);
    }

    @GetMapping("/{geneSymbol}/detail")
    public GeneDetailDTO getGeneDetail(@PathVariable("geneSymbol") String geneSymbol) {
        return variantGeneService.getGeneDetail(geneSymbol);
    }

    @GetMapping("/{geneSymbol}/coordinate")
    public GeneCoordinateEntity getGeneCoordinate(@PathVariable("geneSymbol") String geneSymbol) {
        return variantGeneService.getGeneCoordinate(geneSymbol);
    }

    @PostMapping("/coordinates")
    public List<GeneCoordinateEntity> listGeneCoordinates(@RequestBody List<String> geneSymbols) {
        return variantGeneService.listGeneCoordinates(geneSymbols);
    }

    @GetMapping("/{geneSymbol}/diseases")
    public List<DiseaseSummaryDTO> listDiseasesByGene(@PathVariable("geneSymbol") String geneSymbol) {
        return variantGeneService.listDiseasesByGene(geneSymbol);
    }

    @GetMapping("/{geneSymbol}/curation")
    public List<ClingenGeneCurationEntity> listGeneCuration(@PathVariable("geneSymbol") String geneSymbol) {
        return variantGeneService.listGeneCuration(geneSymbol);
    }

    @GetMapping("/search")
    public List<GeneSummaryDTO> searchGenes(@RequestParam(name = "keyword", required = false, defaultValue = "") String keyword,
                                            @RequestParam(name = "limit", required = false) Integer limit,
                                            @RequestParam(name = "page", required = false) Integer page,
                                            @RequestParam(name = "pageSize", required = false) Integer pageSize,
                                            @RequestParam(name = "sortBy", required = false) String sortBy,
                                            @RequestParam(name = "sortOrder", required = false) String sortOrder) {
        GeneSearchRequest request = new GeneSearchRequest();
        request.setKeyword(keyword);
        request.setLimit(limit);
        request.setPage(page);
        request.setPageSize(pageSize);
        request.setSortBy(sortBy);
        request.setSortOrder(sortOrder);
        return variantGeneService.searchGenes(request);
    }
}
