package org.nbscn.varvis.service.variant.impl;

import org.nbscn.varvis.dto.variant.request.GeneSearchRequest;
import org.nbscn.varvis.dto.variant.response.DiseaseSummaryDTO;
import org.nbscn.varvis.dto.variant.response.GeneDetailDTO;
import org.nbscn.varvis.dto.variant.response.GeneSummaryDTO;
import org.nbscn.varvis.entity.variant.ClingenGeneCurationEntity;
import org.nbscn.varvis.entity.variant.GeneCoordinateEntity;
import org.nbscn.varvis.mapper.varvis.VariantGeneMapper;
import org.nbscn.varvis.mapper.varvis.VariantPositionMapper;
import org.nbscn.varvis.service.variant.VariantGeneService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
@Transactional(readOnly = true, transactionManager = "varvisTxManager")
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantGeneServiceImpl implements VariantGeneService {

    private final VariantGeneMapper variantGeneMapper;
    private final VariantPositionMapper variantPositionMapper;
    private final NamedParameterJdbcTemplate varvisNamedJdbcTemplate;

    public VariantGeneServiceImpl(VariantGeneMapper variantGeneMapper,
                                  VariantPositionMapper variantPositionMapper,
                                  @Qualifier("varvisNamedParameterJdbcTemplate")
                                  NamedParameterJdbcTemplate varvisNamedJdbcTemplate) {
        this.variantGeneMapper = variantGeneMapper;
        this.variantPositionMapper = variantPositionMapper;
        this.varvisNamedJdbcTemplate = varvisNamedJdbcTemplate;
    }

    @Override
    public GeneDetailDTO getGeneDetail(String geneSymbol) {
        if (!StringUtils.hasText(geneSymbol)) {
            return null;
        }
        String normalized = geneSymbol.trim().toUpperCase();
        GeneDetailDTO detail = variantGeneMapper.getGeneDetail(normalized);
        if (detail == null) {
            return null;
        }
        GeneCoordinateEntity coordinate = variantGeneMapper.getGeneCoordinate(normalized);
        List<DiseaseSummaryDTO> diseases = variantGeneMapper.listDiseasesByGene(normalized);
        List<ClingenGeneCurationEntity> curation = variantGeneMapper.listGeneCuration(normalized);
        Integer smallCount = variantPositionMapper.countSmallVariantsByGene(normalized);
        Integer structuralCount = variantPositionMapper.countStructuralVariantsByGene(normalized);

        detail.setCoordinate(coordinate);
        detail.setDiseases(diseases);
        detail.setCuration(curation);
        detail.setSmallVariantCount(smallCount == null ? 0 : smallCount);
        detail.setStructuralVariantCount(structuralCount == null ? 0 : structuralCount);
        return detail;
    }

    @Override
    public GeneCoordinateEntity getGeneCoordinate(String geneSymbol) {
        if (!StringUtils.hasText(geneSymbol)) {
            return null;
        }
        return variantGeneMapper.getGeneCoordinate(geneSymbol.trim().toUpperCase());
    }

    @Override
    public List<GeneCoordinateEntity> listGeneCoordinates(List<String> geneSymbols) {
        if (geneSymbols == null || geneSymbols.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalized = geneSymbols.stream()
                .filter(StringUtils::hasText)
                .map(sym -> sym.trim().toUpperCase())
                .distinct()
                .collect(Collectors.toList());
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        String sql = "SELECT gc.gene_coordinate_id, " +
                "gc.gene_id, " +
                "gc.gene_symbol, " +
                "chr.chr_name AS chromosome, " +
                "gc.start_bp, " +
                "gc.end_bp, " +
                "gc.strand, " +
                "gc.gene_type, " +
                "gc.gencode_gene_id, " +
                "gc.source_db, " +
                "gc.source_file, " +
                "gc.is_basic " +
                "FROM gene_coordinate gc " +
                "LEFT JOIN chromosome chr ON chr.chromosome_id = gc.chromosome_id " +
                "WHERE gc.gene_symbol IN (:symbols) " +
                "ORDER BY gc.gene_symbol, gc.is_basic DESC, gc.source_db, gc.gene_coordinate_id";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("symbols", normalized);

        List<GeneCoordinateEntity> rows = varvisNamedJdbcTemplate.query(sql, params, this::mapGeneCoordinate);
        Map<String, GeneCoordinateEntity> picked = new LinkedHashMap<>();
        for (GeneCoordinateEntity row : rows) {
            if (row == null || !StringUtils.hasText(row.getGeneSymbol())) {
                continue;
            }
            String key = row.getGeneSymbol().trim().toUpperCase();
            if (!picked.containsKey(key)) {
                picked.put(key, row);
            }
        }

        Set<String> resolved = new HashSet<>(picked.keySet());
        List<String> unresolved = normalized.stream()
                .filter(symbol -> !resolved.contains(symbol))
                .collect(Collectors.toList());

        if (!unresolved.isEmpty()) {
            String fallbackSql = "SELECT g.gene_id, " +
                    "g.gene_symbol, " +
                    "chr.chr_name AS chromosome, " +
                    "g.start_bp, " +
                    "g.end_bp " +
                    "FROM gene g " +
                    "LEFT JOIN chromosome chr ON chr.chromosome_id = g.chromosome_id " +
                    "WHERE g.gene_symbol IN (:symbols) " +
                    "ORDER BY g.gene_symbol";
            MapSqlParameterSource fallbackParams = new MapSqlParameterSource()
                    .addValue("symbols", unresolved);
            List<GeneCoordinateEntity> fallbackRows = varvisNamedJdbcTemplate.query(
                    fallbackSql,
                    fallbackParams,
                    this::mapGeneCoordinateFromGeneTable
            );
            for (GeneCoordinateEntity row : fallbackRows) {
                if (row == null || !StringUtils.hasText(row.getGeneSymbol())) {
                    continue;
                }
                String key = row.getGeneSymbol().trim().toUpperCase();
                if (!picked.containsKey(key)) {
                    picked.put(key, row);
                }
            }
        }

        if (picked.isEmpty()) {
            return Collections.emptyList();
        }
        List<GeneCoordinateEntity> ordered = new ArrayList<>();
        for (String symbol : normalized) {
            GeneCoordinateEntity entity = picked.get(symbol);
            if (entity != null) {
                ordered.add(entity);
            }
        }
        return ordered;
    }

    @Override
    public List<DiseaseSummaryDTO> listDiseasesByGene(String geneSymbol) {
        if (!StringUtils.hasText(geneSymbol)) {
            return Collections.emptyList();
        }
        return variantGeneMapper.listDiseasesByGene(geneSymbol.trim().toUpperCase());
    }

    @Override
    public List<ClingenGeneCurationEntity> listGeneCuration(String geneSymbol) {
        if (!StringUtils.hasText(geneSymbol)) {
            return Collections.emptyList();
        }
        return variantGeneMapper.listGeneCuration(geneSymbol.trim().toUpperCase());
    }

    @Override
    public List<GeneSummaryDTO> searchGenes(GeneSearchRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }
        int page = request.getPage() == null ? 1 : Math.max(1, request.getPage());
        int pageSize = request.getPageSize() == null ? 20 : Math.max(1, Math.min(200, request.getPageSize()));
        if (request.getLimit() != null) {
            pageSize = Math.min(pageSize, Math.max(1, request.getLimit()));
        }
        int offset = (page - 1) * pageSize;
        String keyword = StringUtils.hasText(request.getKeyword())
                ? request.getKeyword().trim()
                : "";

        String sortColumn = resolveSortColumn(request.getSortBy());
        String sortOrder = "desc".equalsIgnoreCase(request.getSortOrder()) ? "DESC" : "ASC";

        StringBuilder sqlBuilder = new StringBuilder("SELECT g.gene_symbol, " +
                "g.gene_symbol AS gene_name, " +
                "chr.chr_name AS chromosome, " +
                "g.start_bp, " +
                "g.end_bp, " +
                "(SELECT COUNT(DISTINCT dgm.disease_id) FROM disease_gene_map dgm WHERE dgm.gene_id = g.gene_id) AS disease_count " +
                "FROM gene g " +
                "LEFT JOIN chromosome chr ON chr.chromosome_id = g.chromosome_id ");
        if (StringUtils.hasText(keyword)) {
            sqlBuilder.append("WHERE g.gene_symbol LIKE :keyword ");
        }
        sqlBuilder.append(
                "ORDER BY " + sortColumn + " " + sortOrder + ", g.gene_symbol ASC " +
                "LIMIT :limit OFFSET :offset"
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        if (StringUtils.hasText(keyword)) {
            params.addValue("keyword", "%" + keyword + "%");
        }
        params.addValue("limit", pageSize);
        params.addValue("offset", offset);

        List<Map<String, Object>> rows = varvisNamedJdbcTemplate.queryForList(
                Objects.requireNonNull(sqlBuilder.toString(), "sql"),
                params
        );
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(this::toGeneSummary).collect(Collectors.toList());
    }

    private String resolveSortColumn(String sortBy) {
        if (!StringUtils.hasText(sortBy)) {
            return "g.gene_symbol";
        }
        String normalized = sortBy.trim().toLowerCase();
        switch (normalized) {
            case "start_bp":
                return "g.start_bp";
            case "end_bp":
                return "g.end_bp";
            case "disease_count":
                return "disease_count";
            case "gene_symbol":
            case "gene_name":
            default:
                return "g.gene_symbol";
        }
    }

    private GeneSummaryDTO toGeneSummary(Map<String, Object> row) {
        GeneSummaryDTO dto = new GeneSummaryDTO();
        dto.setGeneSymbol(stringValue(row.get("gene_symbol")));
        dto.setGeneName(stringValue(row.get("gene_name")));
        dto.setChromosome(stringValue(row.get("chromosome")));
        dto.setStartBp(longValue(row.get("start_bp")));
        dto.setEndBp(longValue(row.get("end_bp")));
        dto.setDiseaseCount(intValue(row.get("disease_count")));
        return dto;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
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

    private GeneCoordinateEntity mapGeneCoordinate(ResultSet rs, int rowNum) throws SQLException {
        GeneCoordinateEntity entity = new GeneCoordinateEntity();
        entity.setGeneCoordinateId(rs.getLong("gene_coordinate_id"));
        entity.setGeneId(rs.getLong("gene_id"));
        entity.setGeneSymbol(trimToNull(rs.getString("gene_symbol")));
        entity.setChromosome(trimToNull(rs.getString("chromosome")));
        entity.setStartBp(rs.getLong("start_bp"));
        entity.setEndBp(rs.getLong("end_bp"));
        entity.setStrand(trimToNull(rs.getString("strand")));
        entity.setGeneType(trimToNull(rs.getString("gene_type")));
        entity.setGencodeGeneId(trimToNull(rs.getString("gencode_gene_id")));
        entity.setSourceDb(trimToNull(rs.getString("source_db")));
        entity.setSourceFile(trimToNull(rs.getString("source_file")));
        entity.setIsBasic(rs.getObject("is_basic") == null ? null : rs.getInt("is_basic"));
        return entity;
    }

    private GeneCoordinateEntity mapGeneCoordinateFromGeneTable(ResultSet rs, int rowNum) throws SQLException {
        GeneCoordinateEntity entity = new GeneCoordinateEntity();
        entity.setGeneCoordinateId(null);
        entity.setGeneId(rs.getLong("gene_id"));
        entity.setGeneSymbol(trimToNull(rs.getString("gene_symbol")));
        entity.setChromosome(trimToNull(rs.getString("chromosome")));
        entity.setStartBp(rs.getLong("start_bp"));
        entity.setEndBp(rs.getLong("end_bp"));
        entity.setStrand(null);
        entity.setGeneType(null);
        entity.setGencodeGeneId(null);
        entity.setSourceDb("GENE_TABLE");
        entity.setSourceFile("gene");
        entity.setIsBasic(0);
        return entity;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
