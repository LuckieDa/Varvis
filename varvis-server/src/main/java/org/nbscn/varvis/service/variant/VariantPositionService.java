package org.nbscn.varvis.service.variant;

import org.nbscn.varvis.dto.variant.request.GeneVariantFilterRequest;
import org.nbscn.varvis.dto.variant.response.RegionDetailDTO;
import org.nbscn.varvis.dto.variant.response.SmallVariantDTO;
import org.nbscn.varvis.dto.variant.response.StructuralVariantDTO;
import org.nbscn.varvis.entity.variant.ClingenRegionCurationEntity;
import org.nbscn.varvis.entity.variant.RecurrentCnvRegionEntity;

import java.util.List;

public interface VariantPositionService {

    List<SmallVariantDTO> listSmallVariantsByGene(String geneSymbol, int page, int pageSize, String sortBy, String sortOrder);

    List<StructuralVariantDTO> listStructuralVariantsByGene(String geneSymbol, int page, int pageSize, String sortBy, String sortOrder);

    List<SmallVariantDTO> filterSmallVariants(GeneVariantFilterRequest request);

    List<StructuralVariantDTO> filterStructuralVariants(GeneVariantFilterRequest request);

    List<ClingenRegionCurationEntity> listRegionOverlap(String chromosome, Long startBp, Long endBp);

    List<RecurrentCnvRegionEntity> listRecurrentCnv(String chromosome, Long startBp, Long endBp);

    RegionDetailDTO getRegionDetail(String chromosome, Long startBp, Long endBp);
}
