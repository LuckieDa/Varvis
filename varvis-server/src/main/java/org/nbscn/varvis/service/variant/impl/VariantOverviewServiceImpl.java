package org.nbscn.varvis.service.variant.impl;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.response.ChromosomeSummaryDTO;
import org.nbscn.varvis.dto.variant.response.CytobandDTO;
import org.nbscn.varvis.dto.variant.response.VariantOverviewDTO;
import org.nbscn.varvis.mapper.varvis.VariantOverviewMapper;
import org.nbscn.varvis.service.variant.VariantOverviewService;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, transactionManager = "varvisTxManager")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantOverviewServiceImpl implements VariantOverviewService {

    private final VariantOverviewMapper variantOverviewMapper;

    @Override
    public List<ChromosomeSummaryDTO> listChromosomes() {
        return variantOverviewMapper.listChromosomes();
    }

    @Override
    public List<CytobandDTO> listBandsByChromosome(String chromosome) {
        String normalizedChr = CoordinateUtils.normalizeChromosome(chromosome);
        if (normalizedChr == null) {
            return Collections.emptyList();
        }
        return variantOverviewMapper.listBandsByChromosome(normalizedChr);
    }

    @Override
    public VariantOverviewDTO getBandSummary(Long bandId) {
        if (bandId == null) {
            return null;
        }
        return variantOverviewMapper.getBandSummaryByBandId(bandId);
    }

    @Override
    public VariantOverviewDTO getChromosomeOverview(String chromosome) {
        String normalizedChr = CoordinateUtils.normalizeChromosome(chromosome);
        if (normalizedChr == null) {
            return null;
        }
        List<CytobandDTO> bands = variantOverviewMapper.listBandsByChromosome(normalizedChr);
        if (CollectionUtils.isEmpty(bands)) {
            return null;
        }
        long start = bands.get(0).getStartBp() == null ? 0L : bands.get(0).getStartBp();
        long end = bands.get(bands.size() - 1).getEndBp() == null ? start : bands.get(bands.size() - 1).getEndBp();
        return variantOverviewMapper.getBandSummaryByRegion(normalizedChr, start, end);
    }
}
