package org.nbscn.varvis.service.variant.impl;

import org.nbscn.varvis.dto.variant.request.GeneVariantFilterRequest;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;
import org.nbscn.varvis.dto.variant.response.RegionDetailDTO;
import org.nbscn.varvis.dto.variant.response.SmallVariantDTO;
import org.nbscn.varvis.dto.variant.response.StructuralVariantDTO;
import org.nbscn.varvis.entity.variant.ClingenRegionCurationEntity;
import org.nbscn.varvis.entity.variant.RecurrentCnvRegionEntity;
import org.nbscn.varvis.mapper.varvis.VariantDiseaseMapper;
import org.nbscn.varvis.mapper.varvis.VariantPositionMapper;
import org.nbscn.varvis.service.variant.VariantPositionService;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.utils.VariantAssembler;
import org.nbscn.varvis.utils.VariantUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true, transactionManager = "varvisTxManager")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantPositionServiceImpl implements VariantPositionService {

    private final VariantPositionMapper variantPositionMapper;
    private final VariantDiseaseMapper variantDiseaseMapper;
    private final NamedParameterJdbcTemplate varvisNamedJdbcTemplate;

    public VariantPositionServiceImpl(VariantPositionMapper variantPositionMapper,
                                      VariantDiseaseMapper variantDiseaseMapper,
                                      @Qualifier("varvisNamedParameterJdbcTemplate")
                                      NamedParameterJdbcTemplate varvisNamedJdbcTemplate) {
        this.variantPositionMapper = variantPositionMapper;
        this.variantDiseaseMapper = variantDiseaseMapper;
        this.varvisNamedJdbcTemplate = varvisNamedJdbcTemplate;
    }

    @Override
    public List<SmallVariantDTO> listSmallVariantsByGene(String geneSymbol, int page, int pageSize, String sortBy, String sortOrder) {
        String normalizedGene = normalizeGeneSymbol(geneSymbol);
        if (normalizedGene == null) {
            return Collections.emptyList();
        }
        int normalizedPage = normalizePage(page);
        int normalizedPageSize = normalizePageSize(pageSize);
        int offset = (normalizedPage - 1) * normalizedPageSize;
        boolean defaultSort = !StringUtils.hasText(sortBy) || "start_bp".equalsIgnoreCase(sortBy.trim());
        boolean ascSort = !StringUtils.hasText(sortOrder) || !"desc".equalsIgnoreCase(sortOrder.trim());
        if (defaultSort && ascSort) {
            return variantPositionMapper.listSmallVariantsByGene(normalizedGene, offset, normalizedPageSize);
        }

        GeneVariantFilterRequest request = new GeneVariantFilterRequest();
        request.setGeneSymbol(normalizedGene);
        request.setPage(normalizedPage);
        request.setPageSize(normalizedPageSize);
        request.setSortBy(sortBy);
        request.setSortOrder(sortOrder);
        return filterSmallVariants(request);
    }

    @Override
    public List<StructuralVariantDTO> listStructuralVariantsByGene(String geneSymbol, int page, int pageSize, String sortBy, String sortOrder) {
        String normalizedGene = normalizeGeneSymbol(geneSymbol);
        if (normalizedGene == null) {
            return Collections.emptyList();
        }
        int normalizedPage = normalizePage(page);
        int normalizedPageSize = normalizePageSize(pageSize);
        int offset = (normalizedPage - 1) * normalizedPageSize;
        boolean defaultSort = !StringUtils.hasText(sortBy) || "start_bp".equalsIgnoreCase(sortBy.trim());
        boolean ascSort = !StringUtils.hasText(sortOrder) || !"desc".equalsIgnoreCase(sortOrder.trim());
        if (defaultSort && ascSort) {
            return variantPositionMapper.listStructuralVariantsByGene(normalizedGene, offset, normalizedPageSize);
        }

        GeneVariantFilterRequest request = new GeneVariantFilterRequest();
        request.setGeneSymbol(normalizedGene);
        request.setPage(normalizedPage);
        request.setPageSize(normalizedPageSize);
        request.setSortBy(sortBy);
        request.setSortOrder(sortOrder);
        return filterStructuralVariants(request);
    }

    @Override
    public List<SmallVariantDTO> filterSmallVariants(GeneVariantFilterRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }

        SqlBuilder builder = new SqlBuilder(
                "SELECT sv.small_variant_id AS variant_id, " +
                        "chr.chr_name AS chromosome, " +
                        "sv.start_bp AS position, " +
                        "sv.ref_allele AS ref, " +
                        "sv.alt_allele AS alt, " +
                        "sv.gene_symbol, " +
                        "sv.variant_type, " +
                        "sv.clinical_significance, " +
                        "NULL AS hgvs_c, " +
                        "NULL AS hgvs_p, " +
                        "NULL AS genotype " +
                        "FROM small_variant sv " +
                        "LEFT JOIN chromosome chr ON chr.chromosome_id = sv.chromosome_id"
        );
        applySmallVariantFilters(builder, request);

        int pageSize = normalizePageSize(request.getPageSize());
        int page = normalizePage(request.getPage());
        int offset = (page - 1) * pageSize;
        builder.appendOrderBy(" ORDER BY " + resolveSmallVariantSortColumn(request.getSortBy()) + " " + resolveSortOrder(request.getSortOrder()) +
                ", sv.small_variant_id ASC LIMIT " + pageSize + " OFFSET " + offset);

        List<Map<String, Object>> rows = varvisNamedJdbcTemplate.queryForList(
                Objects.requireNonNull(builder.build(), "sql"),
                Objects.requireNonNull(builder.params, "params")
        );
        return rows.stream().map(VariantAssembler::toSmallVariant).collect(Collectors.toList());
    }

    @Override
    public List<StructuralVariantDTO> filterStructuralVariants(GeneVariantFilterRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }

        String normalizedGene = normalizeGeneSymbol(request.getGeneSymbol());
        SqlBuilder builder = new SqlBuilder(
                "SELECT stv.structural_variant_id AS variant_id, " +
                        "chr.chr_name AS chromosome, " +
                        "stv.start_bp, " +
                        "stv.end_bp, " +
                        "stv.sv_type, " +
                        "NULL AS copy_number, " +
                        ":geneSymbol AS gene_symbol, " +
                        "stv.clinical_significance " +
                        "FROM structural_variant stv " +
                        "LEFT JOIN chromosome chr ON chr.chromosome_id = stv.chromosome_id"
        );
        builder.params.addValue("geneSymbol", normalizedGene);
        applyStructuralVariantFilters(builder, request, normalizedGene);

        int pageSize = normalizePageSize(request.getPageSize());
        int page = normalizePage(request.getPage());
        int offset = (page - 1) * pageSize;
        builder.appendOrderBy(" ORDER BY " + resolveStructuralVariantSortColumn(request.getSortBy()) + " " + resolveSortOrder(request.getSortOrder()) +
                ", stv.structural_variant_id ASC LIMIT " + pageSize + " OFFSET " + offset);

        List<Map<String, Object>> rows = varvisNamedJdbcTemplate.queryForList(
                Objects.requireNonNull(builder.build(), "sql"),
                Objects.requireNonNull(builder.params, "params")
        );
        return rows.stream().map(VariantAssembler::toStructuralVariant).collect(Collectors.toList());
    }

    @Override
    public List<ClingenRegionCurationEntity> listRegionOverlap(String chromosome, Long startBp, Long endBp) {
        String chr = CoordinateUtils.normalizeChromosome(chromosome);
        if (chr == null || startBp == null || endBp == null) {
            return Collections.emptyList();
        }
        long[] range = CoordinateUtils.normalizeRange(startBp, endBp);
        return variantPositionMapper.listRegionCurationOverlap(chr, range[0], range[1]);
    }

    @Override
    public List<RecurrentCnvRegionEntity> listRecurrentCnv(String chromosome, Long startBp, Long endBp) {
        String chr = CoordinateUtils.normalizeChromosome(chromosome);
        if (chr == null || startBp == null || endBp == null) {
            return Collections.emptyList();
        }
        long[] range = CoordinateUtils.normalizeRange(startBp, endBp);
        return variantPositionMapper.listRecurrentCnv(chr, range[0], range[1]);
    }

    @Override
    public RegionDetailDTO getRegionDetail(String chromosome, Long startBp, Long endBp) {
        String chr = CoordinateUtils.normalizeChromosome(chromosome);
        if (chr == null || startBp == null || endBp == null) {
            return null;
        }
        long[] range = CoordinateUtils.normalizeRange(startBp, endBp);

        RegionDetailDTO detail = new RegionDetailDTO();
        detail.setChromosome(chr);
        detail.setStartBp(range[0]);
        detail.setEndBp(range[1]);
        detail.setDiseases(variantDiseaseMapper.listDiseasesByRegion(chr, range[0], range[1]));
        detail.setGenes(queryGenesByRegion(chr, range[0], range[1]));
        detail.setRegionCuration(variantPositionMapper.listRegionCurationOverlap(chr, range[0], range[1]));
        detail.setRecurrentCnvRegions(variantPositionMapper.listRecurrentCnv(chr, range[0], range[1]));
        detail.setDiseaseCount(detail.getDiseases() == null ? 0 : detail.getDiseases().size());
        detail.setGeneCount(detail.getGenes() == null ? 0 : detail.getGenes().size());
        detail.setSmallVariantCount(querySmallVariantCount(chr, range[0], range[1]));
        detail.setStructuralVariantCount(queryStructuralVariantCount(chr, range[0], range[1]));
        return detail;
    }

    private void applySmallVariantFilters(SqlBuilder builder, GeneVariantFilterRequest request) {
        String normalizedGene = normalizeGeneSymbol(request.getGeneSymbol());
        if (normalizedGene != null) {
            builder.addCondition("(sv.gene_symbol = :geneSymbol OR sv.gene_id = (SELECT g.gene_id FROM gene g WHERE g.gene_symbol = :geneSymbol LIMIT 1))");
            builder.params.addValue("geneSymbol", normalizedGene);
        }
        String chr = CoordinateUtils.normalizeChromosome(request.getChromosome());
        if (chr != null) {
            builder.addCondition("chr.chr_name = :chromosome");
            builder.params.addValue("chromosome", chr);
        }
        if (request.getStartBp() != null && request.getEndBp() != null) {
            long[] range = CoordinateUtils.normalizeRange(request.getStartBp(), request.getEndBp());
            builder.addCondition("sv.start_bp BETWEEN :startBp AND :endBp");
            builder.params.addValue("startBp", range[0]);
            builder.params.addValue("endBp", range[1]);
        }
        if (!CollectionUtils.isEmpty(request.getVariantTypes())) {
            List<String> normalized = request.getVariantTypes().stream()
                    .map(VariantUtils::normalizeVariantType)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
            if (!CollectionUtils.isEmpty(normalized)) {
                builder.addCondition("sv.variant_type IN (:variantTypes)");
                builder.params.addValue("variantTypes", normalized);
            }
        }
        if (!CollectionUtils.isEmpty(request.getClinicalSignificance())) {
            List<String> normalized = request.getClinicalSignificance().stream()
                    .map(VariantUtils::normalizeClinicalSignificance)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
            if (!CollectionUtils.isEmpty(normalized)) {
                builder.addCondition("sv.clinical_significance IN (:clinicalSignificance)");
                builder.params.addValue("clinicalSignificance", normalized);
            }
        }
    }

    private void applyStructuralVariantFilters(SqlBuilder builder,
                                               GeneVariantFilterRequest request,
                                               String normalizedGene) {
        if (normalizedGene != null) {
            builder.addCondition("EXISTS (" +
                    "SELECT 1 FROM gene g " +
                    "WHERE g.gene_symbol = :geneSymbol " +
                    "AND g.chromosome_id = stv.chromosome_id " +
                    "AND stv.start_bp <= g.end_bp " +
                    "AND stv.end_bp >= g.start_bp" +
                    ")");
        }
        String chr = CoordinateUtils.normalizeChromosome(request.getChromosome());
        if (chr != null) {
            builder.addCondition("chr.chr_name = :chromosome");
            builder.params.addValue("chromosome", chr);
        }
        if (request.getStartBp() != null && request.getEndBp() != null) {
            long[] range = CoordinateUtils.normalizeRange(request.getStartBp(), request.getEndBp());
            builder.addCondition("stv.start_bp <= :endBp AND stv.end_bp >= :startBp");
            builder.params.addValue("startBp", range[0]);
            builder.params.addValue("endBp", range[1]);
        }
        if (!CollectionUtils.isEmpty(request.getVariantTypes())) {
            List<String> normalized = request.getVariantTypes().stream()
                    .map(VariantUtils::normalizeVariantType)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
            if (!CollectionUtils.isEmpty(normalized)) {
                builder.addCondition("stv.sv_type IN (:variantTypes)");
                builder.params.addValue("variantTypes", normalized);
            }
        }
        if (!CollectionUtils.isEmpty(request.getClinicalSignificance())) {
            List<String> normalized = request.getClinicalSignificance().stream()
                    .map(VariantUtils::normalizeClinicalSignificance)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
            if (!CollectionUtils.isEmpty(normalized)) {
                builder.addCondition("stv.clinical_significance IN (:clinicalSignificance)");
                builder.params.addValue("clinicalSignificance", normalized);
            }
        }
    }

    private List<GeneSummaryDTO> queryGenesByRegion(String chr, long start, long end) {
        String sql = "SELECT g.gene_symbol, " +
                "g.gene_symbol AS gene_name, " +
                "chr.chr_name AS chromosome, " +
                "g.start_bp, " +
                "g.end_bp, " +
                "(SELECT COUNT(DISTINCT dgm.disease_id) FROM disease_gene_map dgm WHERE dgm.gene_id = g.gene_id) AS disease_count " +
                "FROM gene g " +
                "JOIN chromosome chr ON chr.chromosome_id = g.chromosome_id " +
                "WHERE chr.chr_name = :chromosome " +
                "AND g.start_bp <= :endBp " +
                "AND g.end_bp >= :startBp " +
                "ORDER BY g.start_bp";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("chromosome", chr)
                .addValue("startBp", start)
                .addValue("endBp", end);
        List<Map<String, Object>> rows = varvisNamedJdbcTemplate.queryForList(sql, params);
        if (CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        List<GeneSummaryDTO> genes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            GeneSummaryDTO dto = new GeneSummaryDTO();
            dto.setGeneSymbol(stringValue(row.get("gene_symbol")));
            dto.setGeneName(stringValue(row.get("gene_name")));
            dto.setChromosome(stringValue(row.get("chromosome")));
            dto.setStartBp(longValue(row.get("start_bp")));
            dto.setEndBp(longValue(row.get("end_bp")));
            dto.setDiseaseCount(intValue(row.get("disease_count")));
            genes.add(dto);
        }
        return genes;
    }

    private Integer querySmallVariantCount(String chr, long start, long end) {
        String sql = "SELECT COUNT(1) " +
                "FROM small_variant sv " +
                "JOIN chromosome c ON c.chromosome_id = sv.chromosome_id " +
                "WHERE c.chr_name = :chromosome " +
                "AND sv.start_bp BETWEEN :startBp AND :endBp";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("chromosome", chr)
                .addValue("startBp", start)
                .addValue("endBp", end);
        Integer count = varvisNamedJdbcTemplate.queryForObject(sql, params, Integer.class);
        return count == null ? 0 : count;
    }

    private Integer queryStructuralVariantCount(String chr, long start, long end) {
        String sql = "SELECT COUNT(1) " +
                "FROM structural_variant stv " +
                "JOIN chromosome c ON c.chromosome_id = stv.chromosome_id " +
                "WHERE c.chr_name = :chromosome " +
                "AND stv.start_bp <= :endBp " +
                "AND stv.end_bp >= :startBp";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("chromosome", chr)
                .addValue("startBp", start)
                .addValue("endBp", end);
        Integer count = varvisNamedJdbcTemplate.queryForObject(sql, params, Integer.class);
        return count == null ? 0 : count;
    }

    private int normalizePage(Integer page) {
        if (page == null) {
            return 1;
        }
        return Math.max(page, 1);
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null) {
            return 50;
        }
        return Math.max(1, Math.min(pageSize, 200));
    }

    private String resolveSortOrder(String sortOrder) {
        return "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";
    }

    private String resolveSmallVariantSortColumn(String sortBy) {
        if (!StringUtils.hasText(sortBy)) {
            return "sv.start_bp";
        }
        String normalized = sortBy.trim().toLowerCase();
        Map<String, String> whitelist = new LinkedHashMap<>();
        whitelist.put("start_bp", "sv.start_bp");
        whitelist.put("variant_type", "sv.variant_type");
        whitelist.put("clinical_significance", "sv.clinical_significance");
        whitelist.put("gene_symbol", "sv.gene_symbol");
        whitelist.put("created_at", "sv.created_at");
        return whitelist.getOrDefault(normalized, "sv.start_bp");
    }

    private String resolveStructuralVariantSortColumn(String sortBy) {
        if (!StringUtils.hasText(sortBy)) {
            return "stv.start_bp";
        }
        String normalized = sortBy.trim().toLowerCase();
        Map<String, String> whitelist = new LinkedHashMap<>();
        whitelist.put("start_bp", "stv.start_bp");
        whitelist.put("end_bp", "stv.end_bp");
        whitelist.put("sv_type", "stv.sv_type");
        whitelist.put("clinical_significance", "stv.clinical_significance");
        whitelist.put("created_at", "stv.created_at");
        return whitelist.getOrDefault(normalized, "stv.start_bp");
    }

    private String normalizeGeneSymbol(String geneSymbol) {
        if (!StringUtils.hasText(geneSymbol)) {
            return null;
        }
        return geneSymbol.trim().toUpperCase();
    }

    private static final class SqlBuilder {
        private final String base;
        private final List<String> conditions = new ArrayList<>();
        private final MapSqlParameterSource params = new MapSqlParameterSource();
        private String orderBy = "";

        private SqlBuilder(String base) {
            this.base = base;
        }

        private void addCondition(String sql) {
            conditions.add(sql);
        }

        private void appendOrderBy(String sql) {
            this.orderBy = sql;
        }

        private String build() {
            StringBuilder builder = new StringBuilder(base);
            if (!conditions.isEmpty()) {
                builder.append(" WHERE ");
                builder.append(String.join(" AND ", conditions));
            }
            builder.append(orderBy);
            return builder.toString();
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
