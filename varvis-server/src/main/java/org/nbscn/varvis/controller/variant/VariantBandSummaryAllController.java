package org.nbscn.varvis.controller.variant;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.response.BandDiseaseCountDTO;
import org.nbscn.varvis.service.variant.VariantBandSummaryAllService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/variantBasedMap")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantBandSummaryAllController {

    private final VariantBandSummaryAllService variantBandSummaryAllService;

    @GetMapping("/bandDiseaseCounts")
    public List<BandDiseaseCountDTO> listAllBandDiseaseCounts() {
        return variantBandSummaryAllService.listAllBandDiseaseCounts();
    }
}
