package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CaseVcfUploadResponseDTO {
    private String caseId;
    private String fileName;
    private String sampleName;
    private String genomeAssembly;
    private Integer totalVariants;
    private Integer smallVariantCount;
    private Integer structuralVariantCount;
    private Long uploadedAtEpochMs;
}
