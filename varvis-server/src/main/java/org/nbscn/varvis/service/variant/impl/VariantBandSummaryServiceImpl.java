package org.nbscn.varvis.service.variant.impl;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.response.BandDiseaseCountDTO;
import org.nbscn.varvis.mapper.varvis.VariantBandSummaryMapper;
import org.nbscn.varvis.service.variant.VariantBandSummaryService;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, transactionManager = "varvisTxManager")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantBandSummaryServiceImpl implements VariantBandSummaryService {

    private final VariantBandSummaryMapper variantBandSummaryMapper;

    @Override
    public List<BandDiseaseCountDTO> listBandDiseaseCountsByChromosome(String chromosome) {
        String normalizedChr = CoordinateUtils.normalizeChromosome(chromosome);
        if (normalizedChr == null) {
            return Collections.emptyList();
        }
        return variantBandSummaryMapper.listBandDiseaseCountsByChromosome(normalizedChr);
    }
}
