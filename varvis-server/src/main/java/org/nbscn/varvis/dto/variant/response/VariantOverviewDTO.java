package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class VariantOverviewDTO {
    private Long bandId;
    private String chromosome;
    private String bandName;
    private Long startBp;
    private Long endBp;
    private Integer diseaseCount;
    private Integer geneCount;
    private Integer smallVariantCount;
    private Integer structuralVariantCount;
}
