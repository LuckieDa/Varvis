package org.nbscn.varvis.mapper.varvis;

import org.apache.ibatis.annotations.Param;
import org.nbscn.varvis.dto.variant.response.BandDiseaseCountDTO;

import java.util.List;

public interface VariantBandSummaryMapper {

    List<BandDiseaseCountDTO> listBandDiseaseCountsByChromosome(@Param("chromosome") String chromosome);
}
