package org.nbscn.varvis.dto.variant.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GeneVariantFilterRequest {
    private String geneSymbol;
    private String chromosome;
    private Long startBp;
    private Long endBp;
    private List<String> variantTypes;
    private List<String> clinicalSignificance;
    private Integer page = 1;
    private Integer pageSize = 50;
    private String sortBy;
    private String sortOrder;
}
