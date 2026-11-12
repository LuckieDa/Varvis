package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import org.nbscn.varvis.entity.variant.ClingenGeneCurationEntity;
import org.nbscn.varvis.entity.variant.GeneCoordinateEntity;

import java.util.List;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GeneDetailDTO {
    private String geneSymbol;
    private String geneName;
    private String geneId;
    private String chromosome;
    private GeneCoordinateEntity coordinate;
    private Integer smallVariantCount;
    private Integer structuralVariantCount;
    private List<DiseaseSummaryDTO> diseases;
    private List<ClingenGeneCurationEntity> curation;
}
