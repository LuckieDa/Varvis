package org.nbscn.varvis.mapper.varvis;

import org.apache.ibatis.annotations.Param;
import org.nbscn.varvis.dto.variant.response.SmallVariantDTO;
import org.nbscn.varvis.dto.variant.response.StructuralVariantDTO;
import org.nbscn.varvis.entity.variant.ClingenRegionCurationEntity;
import org.nbscn.varvis.entity.variant.RecurrentCnvRegionEntity;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VariantPositionMapper {

    List<SmallVariantDTO> listSmallVariantsByGene(@Param("geneSymbol") String geneSymbol,
                                                  @Param("offset") int offset,
                                                  @Param("limit") int limit);

    List<StructuralVariantDTO> listStructuralVariantsByGene(@Param("geneSymbol") String geneSymbol,
                                                            @Param("offset") int offset,
                                                            @Param("limit") int limit);

    Integer countSmallVariantsByGene(@Param("geneSymbol") String geneSymbol);

    Integer countStructuralVariantsByGene(@Param("geneSymbol") String geneSymbol);

    List<ClingenRegionCurationEntity> listRegionCurationOverlap(@Param("chromosome") String chromosome,
                                                                @Param("startBp") Long startBp,
                                                                @Param("endBp") Long endBp);

    List<RecurrentCnvRegionEntity> listRecurrentCnv(@Param("chromosome") String chromosome,
                                                    @Param("startBp") Long startBp,
                                                    @Param("endBp") Long endBp);
}
