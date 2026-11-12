package org.nbscn.varvis.service.variant;

import org.nbscn.varvis.dto.variant.response.CaseVcfUploadResponseDTO;
import org.nbscn.varvis.dto.variant.response.CaseBandOverlapDTO;
import org.nbscn.varvis.dto.variant.response.CaseMatchedVariantDTO;
import org.nbscn.varvis.dto.variant.response.SmallVariantDTO;
import org.nbscn.varvis.dto.variant.response.StructuralVariantDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface VariantCaseService {

    CaseVcfUploadResponseDTO uploadCaseVcf(MultipartFile vcfFile, String genomeAssembly) throws IOException;

    boolean removeCase(String caseId);

    List<SmallVariantDTO> listCaseSmallVariants(String caseId,
                                                String chromosome,
                                                Long startBp,
                                                Long endBp,
                                                Integer page,
                                                Integer pageSize);

    List<StructuralVariantDTO> listCaseStructuralVariants(String caseId,
                                                          String chromosome,
                                                          Long startBp,
                                                          Long endBp,
                                                          Integer page,
                                                          Integer pageSize);

    List<SmallVariantDTO> listCaseSmallVariantsByGene(String caseId,
                                                      String geneSymbol,
                                                      String chromosome,
                                                      Long startBp,
                                                      Long endBp,
                                                      Integer page,
                                                      Integer pageSize);

    List<StructuralVariantDTO> listCaseStructuralVariantsByGene(String caseId,
                                                                String geneSymbol,
                                                                String chromosome,
                                                                Long startBp,
                                                                Long endBp,
                                                                Integer page,
                                                                Integer pageSize);

    List<CaseBandOverlapDTO> listCaseOverlapBandsByChromosome(String caseId, String chromosome);

    List<CaseMatchedVariantDTO> listCaseMatchedVariants(String caseId,
                                                        String chromosome,
                                                        Integer page,
                                                        Integer pageSize);
}
