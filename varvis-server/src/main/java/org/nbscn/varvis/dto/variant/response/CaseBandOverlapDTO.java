package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CaseBandOverlapDTO {
    private Long bandId;
    private String chromosome;
    private String bandName;
    private Long startBp;
    private Long endBp;
    private Integer overlapCount;
    private String anchorVariantId;
    private String anchorGeneSymbol;
    private Long anchorStartBp;
    private Long anchorEndBp;
    private String anchorRef;
    private String anchorAlt;
    private String anchorVariantType;
    private String anchorExactKey;
    private String anchorKnowledgeClinicalSignificance;
}
