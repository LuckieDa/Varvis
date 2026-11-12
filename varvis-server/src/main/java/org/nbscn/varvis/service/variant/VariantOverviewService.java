package org.nbscn.varvis.service.variant;

import org.nbscn.varvis.dto.variant.response.ChromosomeSummaryDTO;
import org.nbscn.varvis.dto.variant.response.CytobandDTO;
import org.nbscn.varvis.dto.variant.response.VariantOverviewDTO;

import java.util.List;

public interface VariantOverviewService {

    List<ChromosomeSummaryDTO> listChromosomes();

    List<CytobandDTO> listBandsByChromosome(String chromosome);

    VariantOverviewDTO getBandSummary(Long bandId);

    VariantOverviewDTO getChromosomeOverview(String chromosome);
}
