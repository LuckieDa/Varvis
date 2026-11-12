package org.nbscn.varvis.dto.variant.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DiseaseDetailDTO {
    private String diseaseId;
    private String diseaseName;
    private String diseaseNameCn;
    private String orphaNumber;
    private String omimId;
    private String locus;
    private String locusType;
    private Long bandId;
    private List<String> moi;
    private List<String> onset;
    private List<String> externalLinks;
    private List<GeneSummaryDTO> genes;
}
