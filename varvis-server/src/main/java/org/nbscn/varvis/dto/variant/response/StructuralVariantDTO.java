package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class StructuralVariantDTO {
    private String variantId;
    private String chromosome;
    private Long startBp;
    private Long endBp;
    private String svType;
    private Integer copyNumber;
    private String geneSymbol;
    private String clinicalSignificance;
    private Boolean knowledgeMatched;
    private Boolean patientOnly;
    private String knowledgeClinicalSignificance;
}
