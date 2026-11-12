package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ChromosomeSummaryDTO {
    private String chromosome;
    private Long lengthBp;
    private String displayName;
    private Integer cytobandCount;
}
