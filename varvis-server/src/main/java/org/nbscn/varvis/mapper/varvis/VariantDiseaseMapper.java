package org.nbscn.varvis.mapper.varvis;

import org.apache.ibatis.annotations.Param;
import org.nbscn.varvis.dto.variant.response.DiseaseDetailDTO;
import org.nbscn.varvis.dto.variant.response.DiseaseSummaryDTO;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VariantDiseaseMapper {

    List<DiseaseSummaryDTO> listDiseasesByBandId(@Param("bandId") Long bandId);

    List<DiseaseSummaryDTO> listDiseasesByRegion(@Param("chromosome") String chromosome,
                                                 @Param("startBp") Long startBp,
                                                 @Param("endBp") Long endBp);

    DiseaseDetailDTO getDiseaseDetail(@Param("diseaseId") String diseaseId);

    List<GeneSummaryDTO> listGenesByDisease(@Param("diseaseId") String diseaseId);

    List<GeneSummaryDTO> listGenesByBandId(@Param("bandId") Long bandId);

    List<String> listDiseaseMoi(@Param("diseaseId") String diseaseId);

    List<String> listDiseaseOnset(@Param("diseaseId") String diseaseId);

    List<String> listDiseaseLinks(@Param("diseaseId") String diseaseId);
}
