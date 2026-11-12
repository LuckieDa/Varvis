package org.nbscn.varvis.controller.variant;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.response.ChromosomeSummaryDTO;
import org.nbscn.varvis.dto.variant.response.CytobandDTO;
import org.nbscn.varvis.dto.variant.response.VariantOverviewDTO;
import org.nbscn.varvis.service.variant.VariantOverviewService;
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
public class VariantOverviewController {

    private final VariantOverviewService variantOverviewService;

    @GetMapping("/chromosomes")
    public List<ChromosomeSummaryDTO> listChromosomes() {
        return variantOverviewService.listChromosomes();
    }

    @GetMapping("/chromosomes/{chr}/bands")
    public List<CytobandDTO> listBandsByChromosome(@PathVariable("chr") String chromosome) {
        return variantOverviewService.listBandsByChromosome(chromosome);
    }

    @GetMapping("/bands/{bandId}/summary")
    public VariantOverviewDTO getBandSummary(@PathVariable("bandId") Long bandId) {
        return variantOverviewService.getBandSummary(bandId);
    }

    @GetMapping("/chromosomes/{chr}/overview")
    public VariantOverviewDTO getChromosomeOverview(@PathVariable("chr") String chromosome) {
        return variantOverviewService.getChromosomeOverview(chromosome);
    }
}
