package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SmallVariantDTO {
    private String variantId;
    private String chromosome;
    private Long position;
    private String ref;
    private String alt;
    private String geneSymbol;
    private String variantType;
    private String clinicalSignificance;
    private Boolean knowledgeMatched;
    private Boolean patientOnly;
    private String knowledgeClinicalSignificance;
    private String hgvsC;
    private String hgvsP;
    private String genotype;
}
