package org.nbscn.varvis.entity.variant;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ClingenRegionCurationEntity {
    private Long regionCurationId;
    private String chromosome;
    private Long startBp;
    private Long endBp;
    private String regionName;
    private String haploScore;
    private String triploScore;
    private String haploDescription;
    private String triploDescription;
    private String diseaseLabel;
    private String sourceFile;
}
