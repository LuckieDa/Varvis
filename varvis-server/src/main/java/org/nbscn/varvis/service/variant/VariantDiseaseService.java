package org.nbscn.varvis.service.variant;

import org.nbscn.varvis.dto.variant.request.BandDiseaseQueryRequest;
import org.nbscn.varvis.dto.variant.request.DiseaseGeneQueryRequest;
import org.nbscn.varvis.dto.variant.response.DiseaseDetailDTO;
import org.nbscn.varvis.dto.variant.response.DiseaseSummaryDTO;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;

import java.util.List;
import java.util.Map;

public interface VariantDiseaseService {

    List<DiseaseSummaryDTO> listDiseasesByBand(BandDiseaseQueryRequest request);

    DiseaseDetailDTO getDiseaseDetail(String diseaseId);

    List<GeneSummaryDTO> listGenesByDisease(DiseaseGeneQueryRequest request);

    List<GeneSummaryDTO> listGenesByBand(Long bandId);

    Map<String, Object> getDiseaseMeta(String diseaseId);
}
