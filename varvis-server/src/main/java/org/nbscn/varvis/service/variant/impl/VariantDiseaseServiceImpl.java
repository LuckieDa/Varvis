package org.nbscn.varvis.service.variant.impl;

import lombok.RequiredArgsConstructor;
import org.nbscn.varvis.dto.variant.request.BandDiseaseQueryRequest;
import org.nbscn.varvis.dto.variant.request.DiseaseGeneQueryRequest;
import org.nbscn.varvis.dto.variant.response.DiseaseDetailDTO;
import org.nbscn.varvis.dto.variant.response.DiseaseSummaryDTO;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;
import org.nbscn.varvis.mapper.varvis.VariantDiseaseMapper;
import org.nbscn.varvis.service.variant.VariantDiseaseService;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, transactionManager = "varvisTxManager")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantDiseaseServiceImpl implements VariantDiseaseService {

    private final VariantDiseaseMapper variantDiseaseMapper;

    @Override
    public List<DiseaseSummaryDTO> listDiseasesByBand(BandDiseaseQueryRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }
        List<DiseaseSummaryDTO> result;
        if (request.getBandId() != null) {
            result = variantDiseaseMapper.listDiseasesByBandId(request.getBandId());
        } else {
            String chromosome = CoordinateUtils.normalizeChromosome(request.getChromosome());
            if (chromosome == null || request.getStartBp() == null || request.getEndBp() == null) {
                return Collections.emptyList();
            }
            long[] range = CoordinateUtils.normalizeRange(request.getStartBp(), request.getEndBp());
            result = variantDiseaseMapper.listDiseasesByRegion(chromosome, range[0], range[1]);
        }
        if (CollectionUtils.isEmpty(result)) {
            return result;
        }

        List<DiseaseSummaryDTO> sorted = new ArrayList<>(result);
        sorted.sort(resolveDiseaseComparator(request.getSortBy(), request.getSortOrder()));

        int hardLimit = request.getLimit() == null ? 200 : Math.max(1, request.getLimit());
        if (sorted.size() > hardLimit) {
            sorted = sorted.subList(0, hardLimit);
        }
        return page(sorted, request.getPage(), request.getPageSize());
    }

    @Override
    public DiseaseDetailDTO getDiseaseDetail(String diseaseId) {
        if (!StringUtils.hasText(diseaseId)) {
            return null;
        }
        String normalizedId = diseaseId.trim();
        DiseaseDetailDTO detail = variantDiseaseMapper.getDiseaseDetail(normalizedId);
        if (detail == null) {
            return null;
        }
        detail.setMoi(variantDiseaseMapper.listDiseaseMoi(normalizedId));
        detail.setOnset(variantDiseaseMapper.listDiseaseOnset(normalizedId));
        detail.setExternalLinks(variantDiseaseMapper.listDiseaseLinks(normalizedId));

        DiseaseGeneQueryRequest request = new DiseaseGeneQueryRequest();
        request.setDiseaseId(normalizedId);
        request.setPage(1);
        request.setPageSize(200);
        request.setSortBy("gene_symbol");
        request.setSortOrder("asc");
        detail.setGenes(listGenesByDisease(request));
        return detail;
    }

    @Override
    public List<GeneSummaryDTO> listGenesByDisease(DiseaseGeneQueryRequest request) {
        if (request == null || !StringUtils.hasText(request.getDiseaseId())) {
            return Collections.emptyList();
        }
        List<GeneSummaryDTO> result = variantDiseaseMapper.listGenesByDisease(request.getDiseaseId().trim());
        if (CollectionUtils.isEmpty(result)) {
            return result;
        }
        List<GeneSummaryDTO> sorted = new ArrayList<>(result);
        sorted.sort(resolveGeneComparator(request.getSortBy(), request.getSortOrder()));

        int hardLimit = request.getLimit() == null ? 200 : Math.max(1, request.getLimit());
        if (sorted.size() > hardLimit) {
            sorted = sorted.subList(0, hardLimit);
        }
        return page(sorted, request.getPage(), request.getPageSize());
    }

    @Override
    public List<GeneSummaryDTO> listGenesByBand(Long bandId) {
        if (bandId == null) {
            return Collections.emptyList();
        }
        List<GeneSummaryDTO> result = variantDiseaseMapper.listGenesByBandId(bandId);
        if (CollectionUtils.isEmpty(result)) {
            return result;
        }
        List<GeneSummaryDTO> sorted = new ArrayList<>(result);
        sorted.sort(
                Comparator.comparingInt((GeneSummaryDTO item) -> item.getDiseaseCount() == null ? 0 : item.getDiseaseCount())
                        .reversed()
                        .thenComparing(item -> safeString(item.getGeneSymbol()), String.CASE_INSENSITIVE_ORDER)
        );
        return sorted;
    }

    @Override
    public Map<String, Object> getDiseaseMeta(String diseaseId) {
        DiseaseDetailDTO detail = getDiseaseDetail(diseaseId);
        if (detail == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("disease_id", detail.getDiseaseId());
        meta.put("moi", detail.getMoi());
        meta.put("onset", detail.getOnset());
        meta.put("external_links", detail.getExternalLinks());
        meta.put("locus", detail.getLocus());
        meta.put("locus_type", detail.getLocusType());
        meta.put("band_id", detail.getBandId());
        return meta;
    }

    private Comparator<DiseaseSummaryDTO> resolveDiseaseComparator(String sortBy, String sortOrder) {
        String key = normalizeSortKey(sortBy, "disease_name");
        Comparator<DiseaseSummaryDTO> comparator;
        switch (key) {
            case "disease_id":
                comparator = Comparator.comparingLong(this::safeDiseaseId);
                break;
            case "orpha_code":
                comparator = Comparator.comparing(
                        (DiseaseSummaryDTO item) -> safeString(item.getOrphaNumber()),
                        String.CASE_INSENSITIVE_ORDER
                );
                break;
            case "disease_name":
            default:
                comparator = Comparator.comparing(
                                (DiseaseSummaryDTO item) -> safeString(item.getDiseaseName()),
                                String.CASE_INSENSITIVE_ORDER
                        )
                        .thenComparingLong(this::safeDiseaseId);
                break;
        }
        if ("desc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }
        return comparator;
    }

    private Comparator<GeneSummaryDTO> resolveGeneComparator(String sortBy, String sortOrder) {
        String key = normalizeSortKey(sortBy, "gene_symbol");
        Comparator<GeneSummaryDTO> comparator;
        switch (key) {
            case "start_bp":
                comparator = Comparator.comparingLong(item -> safeLong(item.getStartBp()));
                break;
            case "disease_count":
                comparator = Comparator.comparingInt(item -> item.getDiseaseCount() == null ? 0 : item.getDiseaseCount());
                break;
            case "gene_symbol":
            default:
                comparator = Comparator.comparing(
                        (GeneSummaryDTO item) -> safeString(item.getGeneSymbol()),
                        String.CASE_INSENSITIVE_ORDER
                );
                break;
        }
        if ("desc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }
        return comparator;
    }

    private String normalizeSortKey(String sortBy, String fallback) {
        if (!StringUtils.hasText(sortBy)) {
            return fallback;
        }
        String normalized = sortBy.trim().toLowerCase();
        switch (normalized) {
            case "disease_id":
            case "disease_name":
            case "orpha_code":
            case "gene_symbol":
            case "start_bp":
            case "disease_count":
                return normalized;
            default:
                return fallback;
        }
    }

    private long safeDiseaseId(DiseaseSummaryDTO item) {
        if (item == null || item.getDiseaseId() == null) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(item.getDiseaseId().toString());
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
    }

    private long safeLong(Long value) {
        return value == null ? Long.MAX_VALUE : value;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> page(List<T> input, Integer page, Integer pageSize) {
        if (CollectionUtils.isEmpty(input)) {
            return input;
        }
        int p = page == null ? 1 : Math.max(page, 1);
        int ps = pageSize == null ? 50 : Math.max(1, Math.min(pageSize, 500));
        int from = (p - 1) * ps;
        if (from >= input.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(from + ps, input.size());
        return input.subList(from, to);
    }
}
