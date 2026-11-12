package org.nbscn.varvis.service.variant;

import org.nbscn.varvis.dto.variant.response.BandDiseaseCountDTO;

import java.util.List;

public interface VariantBandSummaryAllService {
    List<BandDiseaseCountDTO> listAllBandDiseaseCounts();
}
