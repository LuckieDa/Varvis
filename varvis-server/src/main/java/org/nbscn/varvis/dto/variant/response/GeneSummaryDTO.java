package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GeneSummaryDTO {
    private String geneSymbol;
    private String geneName;
    private String chromosome;
    private Long startBp;
    private Long endBp;
    private Integer diseaseCount;
}
