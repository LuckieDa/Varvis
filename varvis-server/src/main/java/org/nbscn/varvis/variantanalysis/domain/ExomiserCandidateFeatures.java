package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, typed projection of one Exomiser variant row and its safely matched gene row.
 *
 * <p>The original TSV maps remain available in the Exomiser evidence details for forward
 * compatibility.  This projection makes the fields used by the product explicit without
 * turning the engine's composite display fields into independent clinical evidence.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class ExomiserCandidateFeatures {

    public static final String CURRENT_SCHEMA_VERSION = "exomiser-candidate-features-v1";
    public static final String GENE_MOI_GROUP_RANK_SEMANTICS =
            "EXOMISER_GENE_MOI_GROUP_NATIVE_RANK";

    private final String schemaVersion;
    private final String engineVersion;
    private final String geneSymbol;
    private final String entrezGeneId;
    private final String rawModeOfInheritance;
    private final String normalizedModeOfInheritance;
    private final Integer geneMoiGroupRank;
    private final String geneMoiGroupRankSemantics;
    private final String sourceRecordId;
    private final Double variantScore;
    private final Double geneCombinedScore;
    private final Double genePhenotypeScore;
    private final Double geneVariantScore;
    private final String functionalClass;
    private final String hgvs;
    private final Boolean contributingVariant;
    private final String variantArtifactName;
    private final String geneArtifactName;
    private final Integer variantSourceOrdinal;
    private final Integer geneSourceOrdinal;
    private final String geneRowMatchStrategy;
    private final String rankingScoreSourceColumn;
    private final List<String> missingFields;
    private final List<String> invalidFields;
    private final Map<String, String> fieldSources;

    @JsonCreator
    public ExomiserCandidateFeatures(
            @JsonProperty("schemaVersion") String schemaVersion,
            @JsonProperty("engineVersion") String engineVersion,
            @JsonProperty("geneSymbol") String geneSymbol,
            @JsonProperty("entrezGeneId") String entrezGeneId,
            @JsonProperty("rawModeOfInheritance") String rawModeOfInheritance,
            @JsonProperty("normalizedModeOfInheritance") String normalizedModeOfInheritance,
            @JsonProperty("geneMoiGroupRank") Integer geneMoiGroupRank,
            @JsonProperty("geneMoiGroupRankSemantics") String geneMoiGroupRankSemantics,
            @JsonProperty("sourceRecordId") String sourceRecordId,
            @JsonProperty("variantScore") Double variantScore,
            @JsonProperty("geneCombinedScore") Double geneCombinedScore,
            @JsonProperty("genePhenotypeScore") Double genePhenotypeScore,
            @JsonProperty("geneVariantScore") Double geneVariantScore,
            @JsonProperty("functionalClass") String functionalClass,
            @JsonProperty("hgvs") String hgvs,
            @JsonProperty("contributingVariant") Boolean contributingVariant,
            @JsonProperty("variantArtifactName") String variantArtifactName,
            @JsonProperty("geneArtifactName") String geneArtifactName,
            @JsonProperty("variantSourceOrdinal") Integer variantSourceOrdinal,
            @JsonProperty("geneSourceOrdinal") Integer geneSourceOrdinal,
            @JsonProperty("geneRowMatchStrategy") String geneRowMatchStrategy,
            @JsonProperty("rankingScoreSourceColumn") String rankingScoreSourceColumn,
            @JsonProperty("missingFields") List<String> missingFields,
            @JsonProperty("invalidFields") List<String> invalidFields,
            @JsonProperty("fieldSources") Map<String, String> fieldSources) {
        this.schemaVersion = textOrDefault(schemaVersion, CURRENT_SCHEMA_VERSION);
        this.engineVersion = trimToNull(engineVersion);
        this.geneSymbol = trimToNull(geneSymbol);
        this.entrezGeneId = trimToNull(entrezGeneId);
        this.rawModeOfInheritance = trimToNull(rawModeOfInheritance);
        this.normalizedModeOfInheritance = validatedNormalizedMoi(
                this.rawModeOfInheritance,
                normalizedModeOfInheritance
        );
        this.geneMoiGroupRank = geneMoiGroupRank;
        this.geneMoiGroupRankSemantics = rankSemantics(geneMoiGroupRankSemantics);
        this.sourceRecordId = trimToNull(sourceRecordId);
        this.variantScore = finiteOrNull(variantScore);
        this.geneCombinedScore = finiteOrNull(geneCombinedScore);
        this.genePhenotypeScore = finiteOrNull(genePhenotypeScore);
        this.geneVariantScore = finiteOrNull(geneVariantScore);
        this.functionalClass = trimToNull(functionalClass);
        this.hgvs = trimToNull(hgvs);
        this.contributingVariant = contributingVariant;
        this.variantArtifactName = trimToNull(variantArtifactName);
        this.geneArtifactName = trimToNull(geneArtifactName);
        this.variantSourceOrdinal = variantSourceOrdinal;
        this.geneSourceOrdinal = geneSourceOrdinal;
        this.geneRowMatchStrategy = trimToNull(geneRowMatchStrategy);
        this.rankingScoreSourceColumn = trimToNull(rankingScoreSourceColumn);
        this.missingFields = immutableList(missingFields);
        this.invalidFields = immutableList(invalidFields);
        this.fieldSources = immutableMap(fieldSources);
    }

    public String getSchemaVersion() { return schemaVersion; }
    public String getEngineVersion() { return engineVersion; }
    public String getGeneSymbol() { return geneSymbol; }
    public String getEntrezGeneId() { return entrezGeneId; }
    public String getRawModeOfInheritance() { return rawModeOfInheritance; }
    public String getNormalizedModeOfInheritance() { return normalizedModeOfInheritance; }
    public Integer getGeneMoiGroupRank() { return geneMoiGroupRank; }
    public String getGeneMoiGroupRankSemantics() { return geneMoiGroupRankSemantics; }
    public String getSourceRecordId() { return sourceRecordId; }
    public Double getVariantScore() { return variantScore; }
    public Double getGeneCombinedScore() { return geneCombinedScore; }
    public Double getGenePhenotypeScore() { return genePhenotypeScore; }
    public Double getGeneVariantScore() { return geneVariantScore; }
    public String getFunctionalClass() { return functionalClass; }
    public String getHgvs() { return hgvs; }
    public Boolean getContributingVariant() { return contributingVariant; }
    public String getVariantArtifactName() { return variantArtifactName; }
    public String getGeneArtifactName() { return geneArtifactName; }
    public Integer getVariantSourceOrdinal() { return variantSourceOrdinal; }
    public Integer getGeneSourceOrdinal() { return geneSourceOrdinal; }
    public String getGeneRowMatchStrategy() { return geneRowMatchStrategy; }
    public String getRankingScoreSourceColumn() { return rankingScoreSourceColumn; }
    public List<String> getMissingFields() { return missingFields; }
    public List<String> getInvalidFields() { return invalidFields; }
    public Map<String, String> getFieldSources() { return fieldSources; }

    private static String rankSemantics(String value) {
        String normalized = trimToNull(value);
        if (normalized != null && !GENE_MOI_GROUP_RANK_SEMANTICS.equals(normalized)) {
            throw new IllegalArgumentException("EXOMISER_RANK_SEMANTICS_INVALID");
        }
        return GENE_MOI_GROUP_RANK_SEMANTICS;
    }

    private static String validatedNormalizedMoi(String rawMoi, String normalizedMoi) {
        String declared = trimToNull(normalizedMoi);
        String normalized = normalizeSupportedMoi(declared);
        if (declared != null && normalized == null) {
            throw new IllegalArgumentException("EXOMISER_NORMALIZED_MOI_INVALID");
        }
        String expected = normalizeSupportedMoi(rawMoi);
        // Earlier candidate snapshots retained explicit raw Y-linked MOI with no supported
        // normalized value. Do not derive the newly supported value during deserialization;
        // existing AD/AR/X completeness checks remain unchanged.
        if ("Y_LINKED".equals(expected) && declared == null) {
            return null;
        }
        if (expected == null ? normalized != null : !expected.equals(normalized)) {
            throw new IllegalArgumentException("EXOMISER_MOI_NORMALIZATION_MISMATCH");
        }
        return normalized;
    }

    private static String normalizeSupportedMoi(String value) {
        String selected = trimToNull(value);
        if (selected == null) {
            return null;
        }
        String normalized = selected.toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        switch (normalized) {
            case "AD":
                return "AUTOSOMAL_DOMINANT";
            case "AR":
                return "AUTOSOMAL_RECESSIVE";
            case "XD":
                return "X_DOMINANT";
            case "XR":
                return "X_RECESSIVE";
            case "MT":
                return "MITOCHONDRIAL";
            case "AUTOSOMAL_DOMINANT":
            case "AUTOSOMAL_RECESSIVE":
            case "X_DOMINANT":
            case "X_RECESSIVE":
            case "Y_LINKED":
            case "MITOCHONDRIAL":
                return normalized;
            default:
                return null;
        }
    }

    private static Double finiteOrNull(Double value) {
        return value == null || value.isNaN() || value.isInfinite() ? null : value;
    }

    private static String textOrDefault(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static List<String> immutableList(List<String> values) {
        return values == null || values.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    private static Map<String, String> immutableMap(Map<String, String> values) {
        return values == null || values.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }
}
