package org.nbscn.varvis.controller.variant;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.request.GeneVariantFilterRequest;
import org.nbscn.varvis.dto.variant.response.RegionDetailDTO;
import org.nbscn.varvis.dto.variant.response.SmallVariantDTO;
import org.nbscn.varvis.dto.variant.response.StructuralVariantDTO;
import org.nbscn.varvis.entity.variant.ClingenRegionCurationEntity;
import org.nbscn.varvis.entity.variant.RecurrentCnvRegionEntity;
import org.nbscn.varvis.service.variant.VariantPositionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/variantBasedMap")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantPositionController {

    private final VariantPositionService variantPositionService;

    @GetMapping("/genes/{geneSymbol}/smallVariants")
    public List<SmallVariantDTO> listSmallVariantsByGene(
            @PathVariable("geneSymbol") String geneSymbol,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "50") int pageSize,
            @RequestParam(name = "sortBy", required = false) String sortBy,
            @RequestParam(name = "sortOrder", required = false) String sortOrder) {
        return variantPositionService.listSmallVariantsByGene(geneSymbol, page, pageSize, sortBy, sortOrder);
    }

    @GetMapping("/genes/{geneSymbol}/structuralVariants")
    public List<StructuralVariantDTO> listStructuralVariantsByGene(
            @PathVariable("geneSymbol") String geneSymbol,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "50") int pageSize,
            @RequestParam(name = "sortBy", required = false) String sortBy,
            @RequestParam(name = "sortOrder", required = false) String sortOrder) {
        return variantPositionService.listStructuralVariantsByGene(geneSymbol, page, pageSize, sortBy, sortOrder);
    }

    @PostMapping("/positions/small/filter")
    public List<SmallVariantDTO> filterSmallVariants(@RequestBody GeneVariantFilterRequest request) {
        return variantPositionService.filterSmallVariants(request);
    }

    @PostMapping("/positions/structural/filter")
    public List<StructuralVariantDTO> filterStructuralVariants(@RequestBody GeneVariantFilterRequest request) {
        return variantPositionService.filterStructuralVariants(request);
    }

    @GetMapping("/regions/overlap")
    public List<ClingenRegionCurationEntity> listRegionOverlap(
            @RequestParam("chr") String chr,
            @RequestParam("start") Long start,
            @RequestParam("end") Long end) {
        return variantPositionService.listRegionOverlap(chr, start, end);
    }

    @GetMapping("/recurrentCnv")
    public List<RecurrentCnvRegionEntity> listRecurrentCnv(
            @RequestParam("chr") String chr,
            @RequestParam("start") Long start,
            @RequestParam("end") Long end) {
        return variantPositionService.listRecurrentCnv(chr, start, end);
    }

    @GetMapping("/regions/detail")
    public RegionDetailDTO getRegionDetail(
            @RequestParam("chr") String chr,
            @RequestParam("start") Long start,
            @RequestParam("end") Long end) {
        return variantPositionService.getRegionDetail(chr, start, end);
    }
}
