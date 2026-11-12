package org.nbscn.varvis.dto.variant.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GeneSearchRequest {
    private String keyword;
    private Integer page = 1;
    private Integer pageSize = 20;
    private String sortBy;
    private String sortOrder;
    private Integer limit = 20;
}
