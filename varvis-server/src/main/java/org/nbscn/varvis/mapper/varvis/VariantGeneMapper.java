package org.nbscn.varvis.mapper.varvis;

import org.apache.ibatis.annotations.Param;
import org.nbscn.varvis.dto.variant.response.DiseaseSummaryDTO;
import org.nbscn.varvis.dto.variant.response.GeneDetailDTO;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;
import org.nbscn.varvis.entity.variant.ClingenGeneCurationEntity;
import org.nbscn.varvis.entity.variant.GeneCoordinateEntity;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VariantGeneMapper {

    GeneDetailDTO getGeneDetail(@Param("geneSymbol") String geneSymbol);

    GeneCoordinateEntity getGeneCoordinate(@Param("geneSymbol") String geneSymbol);

    List<DiseaseSummaryDTO> listDiseasesByGene(@Param("geneSymbol") String geneSymbol);

    List<ClingenGeneCurationEntity> listGeneCuration(@Param("geneSymbol") String geneSymbol);

    List<GeneSummaryDTO> searchGenes(@Param("keyword") String keyword,
                                     @Param("limit") int limit);
}
