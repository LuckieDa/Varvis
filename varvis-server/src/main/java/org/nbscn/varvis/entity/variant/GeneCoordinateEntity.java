package org.nbscn.varvis.entity.variant;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GeneCoordinateEntity {
    private Long geneCoordinateId;
    private Long geneId;
    private String geneSymbol;
    private String chromosome;
    private Long startBp;
    private Long endBp;
    private String strand;
    private String geneType;
    private String gencodeGeneId;
    private String sourceDb;
    private String sourceFile;
    private Integer isBasic;
}
