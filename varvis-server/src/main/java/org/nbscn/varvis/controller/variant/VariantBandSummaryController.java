package org.nbscn.varvis.controller.variant;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.response.BandDiseaseCountDTO;
import org.nbscn.varvis.service.variant.VariantBandSummaryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/variantBasedMap")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantBandSummaryController {

    private final VariantBandSummaryService variantBandSummaryService;

    @GetMapping("/chromosomes/{chr}/bandDiseaseCounts")
    public List<BandDiseaseCountDTO> listBandDiseaseCounts(@PathVariable("chr") String chromosome) {
        return variantBandSummaryService.listBandDiseaseCountsByChromosome(chromosome);
    }
}
