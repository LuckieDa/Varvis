package org.nbscn.varvis.service.variant;

import org.nbscn.varvis.dto.variant.request.GeneSearchRequest;
import org.nbscn.varvis.dto.variant.response.DiseaseSummaryDTO;
import org.nbscn.varvis.dto.variant.response.GeneDetailDTO;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;
import org.nbscn.varvis.entity.variant.ClingenGeneCurationEntity;
import org.nbscn.varvis.entity.variant.GeneCoordinateEntity;

import java.util.List;

public interface VariantGeneService {

    GeneDetailDTO getGeneDetail(String geneSymbol);

    GeneCoordinateEntity getGeneCoordinate(String geneSymbol);

    List<GeneCoordinateEntity> listGeneCoordinates(List<String> geneSymbols);

    List<DiseaseSummaryDTO> listDiseasesByGene(String geneSymbol);

    List<ClingenGeneCurationEntity> listGeneCuration(String geneSymbol);

    List<GeneSummaryDTO> searchGenes(GeneSearchRequest request);
}
