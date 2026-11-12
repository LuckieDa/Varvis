package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import org.nbscn.varvis.entity.variant.ClingenRegionCurationEntity;
import org.nbscn.varvis.entity.variant.RecurrentCnvRegionEntity;

import java.util.List;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RegionDetailDTO {
    private String chromosome;
    private Long startBp;
    private Long endBp;
    private Integer diseaseCount;
    private Integer geneCount;
    private Integer smallVariantCount;
    private Integer structuralVariantCount;
    private List<DiseaseSummaryDTO> diseases;
    private List<GeneSummaryDTO> genes;
    private List<ClingenRegionCurationEntity> regionCuration;
    private List<RecurrentCnvRegionEntity> recurrentCnvRegions;
}
