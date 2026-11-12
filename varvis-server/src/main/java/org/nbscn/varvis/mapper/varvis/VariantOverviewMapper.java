package org.nbscn.varvis.mapper.varvis;

import org.apache.ibatis.annotations.Param;
import org.nbscn.varvis.dto.variant.response.ChromosomeSummaryDTO;
import org.nbscn.varvis.dto.variant.response.CytobandDTO;
import org.nbscn.varvis.dto.variant.response.VariantOverviewDTO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VariantOverviewMapper {

    List<ChromosomeSummaryDTO> listChromosomes();

    List<CytobandDTO> listBandsByChromosome(@Param("chromosome") String chromosome);

    VariantOverviewDTO getBandSummaryByBandId(@Param("bandId") Long bandId);

    VariantOverviewDTO getBandSummaryByRegion(@Param("chromosome") String chromosome,
                                              @Param("startBp") Long startBp,
                                              @Param("endBp") Long endBp);
}
