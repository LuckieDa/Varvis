package org.nbscn.varvis.entity.variant;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RecurrentCnvRegionEntity {
    private Long recurrentCnvId;
    private String chromosome;
    private Long startBp;
    private Long endBp;
    private String regionName;
    private String scoreText;
    private String strand;
    private String sourceFile;
}
