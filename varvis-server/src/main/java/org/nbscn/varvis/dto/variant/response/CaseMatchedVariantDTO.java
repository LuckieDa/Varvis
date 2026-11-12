package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CaseMatchedVariantDTO {
    private String variantId;
    private String chromosome;
    private Long position;
    private Long startBp;
    private Long endBp;
    private String ref;
    private String alt;
    private String geneSymbol;
    private String variantType;
    private String variantClass;
    private String clinicalSignificance;
    private String knowledgeClinicalSignificance;
    private String exactKey;
}

