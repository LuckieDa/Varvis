package org.nbscn.varvis.service.variant.impl;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.response.BandDiseaseCountDTO;
import org.nbscn.varvis.mapper.varvis.VariantBandSummaryAllMapper;
import org.nbscn.varvis.service.variant.VariantBandSummaryAllService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, transactionManager = "varvisTxManager")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantBandSummaryAllServiceImpl implements VariantBandSummaryAllService {

    private final VariantBandSummaryAllMapper variantBandSummaryAllMapper;

    @Override
    public List<BandDiseaseCountDTO> listAllBandDiseaseCounts() {
        return variantBandSummaryAllMapper.listAllBandDiseaseCounts();
    }
}
