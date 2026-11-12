package org.nbscn.varvis.mapper.varvis;

import org.nbscn.varvis.dto.variant.response.BandDiseaseCountDTO;

import java.util.List;

public interface VariantBandSummaryAllMapper {
    List<BandDiseaseCountDTO> listAllBandDiseaseCounts();
}
