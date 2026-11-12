package org.nbscn.varvis.variantanalysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only explanation derived from the ranking values already stored for one candidate. */
public final class CandidateRankingExplanationResponse {

    public enum Completeness { COMPLETE, LEGACY_PARTIAL, SUMMARY_ONLY, UNAVAILABLE }
    public enum Direction { POSITIVE, NEGATIVE, NEUTRAL }

    private final String schemaVersion;
    private final String analysisId;
    private final String candidateId;
    private final String canonicalVariantId;
    private final String variantId;
    private final String priorityTier;
    private final Double rankingScore;
    private final String tierReason;
    private final Completeness completeness;
    private final List<Contribution> contributions;
    private final List<Contribution> positiveContributions;
    private final List<Contribution> negativeContributions;
    private final List<Contribution> neutralContributions;
    private final List<EvidenceProvenance> availableEvidence;
    private final List<EvidenceProvenance> conflictingEvidence;
    private final List<EvidenceProvenance> missingEvidence;
    private final List<String> warnings;
    private final EngineCandidateContext engineCandidateContext;
    private final AnalysisVcfCallContext analysisVcfCallContext;
    private final Provenance provenance;
    private final String disclaimer;

    public CandidateRankingExplanationResponse(
            String analysisId,
            String variantId,
            String priorityTier,
            Double rankingScore,
            String tierReason,
            Completeness completeness,
            List<Contribution> contributions,
            List<EvidenceProvenance> availableEvidence,
            List<EvidenceProvenance> conflictingEvidence,
            List<EvidenceProvenance> missingEvidence,
            List<String> warnings) {
        this(
                "candidate-ranking-explanation-v1",
                analysisId,
                null,
                null,
                variantId,
                priorityTier,
                rankingScore,
                tierReason,
                completeness,
                contributions,
                availableEvidence,
                conflictingEvidence,
                missingEvidence,
                warnings,
                null,
                null
        );
    }

    public CandidateRankingExplanationResponse(
            String analysisId,
            String candidateId,
            String canonicalVariantId,
            String variantId,
            String priorityTier,
            Double rankingScore,
            String tierReason,
            Completeness completeness,
            List<Contribution> contributions,
            List<EvidenceProvenance> availableEvidence,
            List<EvidenceProvenance> conflictingEvidence,
            List<EvidenceProvenance> missingEvidence,
            List<String> warnings) {
        this(
                analysisId,
                candidateId,
                canonicalVariantId,
                variantId,
                priorityTier,
                rankingScore,
                tierReason,
                completeness,
                contributions,
                availableEvidence,
                conflictingEvidence,
                missingEvidence,
                warnings,
                null,
                null
        );
    }

    public CandidateRankingExplanationResponse(
            String analysisId,
            String candidateId,
            String canonicalVariantId,
            String variantId,
            String priorityTier,
            Double rankingScore,
            String tierReason,
            Completeness completeness,
            List<Contribution> contributions,
            List<EvidenceProvenance> availableEvidence,
            List<EvidenceProvenance> conflictingEvidence,
            List<EvidenceProvenance> missingEvidence,
            List<String> warnings,
            EngineCandidateContext engineCandidateContext) {
        this(
                analysisId,
                candidateId,
                canonicalVariantId,
                variantId,
                priorityTier,
                rankingScore,
                tierReason,
                completeness,
                contributions,
                availableEvidence,
                conflictingEvidence,
                missingEvidence,
                warnings,
                engineCandidateContext,
                null
        );
    }

    public CandidateRankingExplanationResponse(
            String analysisId,
            String candidateId,
            String canonicalVariantId,
            String variantId,
            String priorityTier,
            Double rankingScore,
            String tierReason,
            Completeness completeness,
            List<Contribution> contributions,
            List<EvidenceProvenance> availableEvidence,
            List<EvidenceProvenance> conflictingEvidence,
            List<EvidenceProvenance> missingEvidence,
            List<String> warnings,
            EngineCandidateContext engineCandidateContext,
            AnalysisVcfCallContext analysisVcfCallContext) {
        this(
                "candidate-ranking-explanation-v2",
                analysisId,
                candidateId,
                canonicalVariantId,
                variantId,
                priorityTier,
                rankingScore,
                tierReason,
                completeness,
                contributions,
                availableEvidence,
                conflictingEvidence,
                missingEvidence,
                warnings,
                engineCandidateContext,
                analysisVcfCallContext
        );
    }

    private CandidateRankingExplanationResponse(
            String schemaVersion,
            String analysisId,
            String candidateId,
            String canonicalVariantId,
            String variantId,
            String priorityTier,
            Double rankingScore,
            String tierReason,
            Completeness completeness,
            List<Contribution> contributions,
            List<EvidenceProvenance> availableEvidence,
            List<EvidenceProvenance> conflictingEvidence,
            List<EvidenceProvenance> missingEvidence,
            List<String> warnings,
            EngineCandidateContext engineCandidateContext,
            AnalysisVcfCallContext analysisVcfCallContext) {
        this.schemaVersion = schemaVersion;
        this.analysisId = analysisId;
        this.candidateId = candidateId;
        this.canonicalVariantId = canonicalVariantId;
        this.variantId = variantId;
        this.priorityTier = priorityTier;
        this.rankingScore = rankingScore;
        this.tierReason = tierReason;
        this.completeness = completeness == null ? Completeness.UNAVAILABLE : completeness;
        this.contributions = immutable(contributions);
        this.positiveContributions = byDirection(this.contributions, Direction.POSITIVE);
        this.negativeContributions = byDirection(this.contributions, Direction.NEGATIVE);
        this.neutralContributions = byDirection(this.contributions, Direction.NEUTRAL);
        this.availableEvidence = immutable(availableEvidence);
        this.conflictingEvidence = immutable(conflictingEvidence);
        this.missingEvidence = immutable(missingEvidence);
        this.warnings = immutable(warnings);
        this.engineCandidateContext = engineCandidateContext;
        this.analysisVcfCallContext = analysisVcfCallContext;
        this.provenance = new Provenance("DERIVED_FROM_PERSISTED_RANKING_OUTPUT", false);
        this.disclaimer = "This explanation describes an existing ranking result; it is not a new score or a clinical diagnosis.";
    }

    private static List<Contribution> byDirection(List<Contribution> values, Direction direction) {
        List<Contribution> result = new ArrayList<Contribution>();
        for (Contribution value : values) if (value.getDirection() == direction) result.add(value);
        return immutable(result);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null || values.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return values == null || values.isEmpty() ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<K, V>(values));
    }

    public String getSchemaVersion() { return schemaVersion; }
    public String getAnalysisId() { return analysisId; }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getCandidateId() { return candidateId; }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getCanonicalVariantId() { return canonicalVariantId; }
    public String getVariantId() { return variantId; }
    public String getPriorityTier() { return priorityTier; }
    public Double getRankingScore() { return rankingScore; }
    public String getTierReason() { return tierReason; }
    public Completeness getCompleteness() { return completeness; }
    public List<Contribution> getContributions() { return contributions; }
    public List<Contribution> getPositiveContributions() { return positiveContributions; }
    public List<Contribution> getNegativeContributions() { return negativeContributions; }
    public List<Contribution> getNeutralContributions() { return neutralContributions; }
    public List<EvidenceProvenance> getAvailableEvidence() { return availableEvidence; }
    public List<EvidenceProvenance> getConflictingEvidence() { return conflictingEvidence; }
    public List<EvidenceProvenance> getMissingEvidence() { return missingEvidence; }
    public List<String> getWarnings() { return warnings; }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public EngineCandidateContext getEngineCandidateContext() { return engineCandidateContext; }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public AnalysisVcfCallContext getAnalysisVcfCallContext() { return analysisVcfCallContext; }
    public Provenance getProvenance() { return provenance; }
    public String getDisclaimer() { return disclaimer; }

    public static final class Contribution {
        private final String key;
        private final Double score;
        private final Direction direction;
        private final List<String> reasons;
        private final String derivedFrom;

        public Contribution(String key, Double score, List<String> reasons, String derivedFrom) {
            this(key, score, null, reasons, derivedFrom);
        }

        public Contribution(String key, Double score, Direction recordedDirection,
                            List<String> reasons, String derivedFrom) {
            this.key = key;
            this.score = score;
            this.direction = recordedDirection != null ? recordedDirection
                    : score == null || score.doubleValue() == 0.0d ? Direction.NEUTRAL
                    : score.doubleValue() > 0.0d ? Direction.POSITIVE : Direction.NEGATIVE;
            this.reasons = immutable(reasons);
            this.derivedFrom = derivedFrom;
        }

        public String getKey() { return key; }
        public Double getScore() { return score; }
        public Direction getDirection() { return direction; }
        public List<String> getReasons() { return reasons; }
        public String getDerivedFrom() { return derivedFrom; }
    }

    /** Deliberately excludes arbitrary evidence details/provenance and variant/sample fields. */
    public static final class EvidenceProvenance {
        private final String source;
        private final String resourceVersion;
        private final String evidenceType;
        private final String applicability;
        private final String status;
        private final String evidenceStrength;
        private final String explanationCode;
        private final String retrievedAt;
        private final String availabilityMeaning;

        public EvidenceProvenance(String source, String resourceVersion, String evidenceType,
                                  String applicability, String status, String evidenceStrength,
                                  String explanationCode, String retrievedAt,
                                  String availabilityMeaning) {
            this.source = source;
            this.resourceVersion = resourceVersion;
            this.evidenceType = evidenceType;
            this.applicability = applicability;
            this.status = status;
            this.evidenceStrength = evidenceStrength;
            this.explanationCode = explanationCode;
            this.retrievedAt = retrievedAt;
            this.availabilityMeaning = availabilityMeaning;
        }
        public String getSource() { return source; }
        public String getResourceVersion() { return resourceVersion; }
        public String getEvidenceType() { return evidenceType; }
        public String getApplicability() { return applicability; }
        public String getStatus() { return status; }
        public String getEvidenceStrength() { return evidenceStrength; }
        public String getExplanationCode() { return explanationCode; }
        public String getRetrievedAt() { return retrievedAt; }
        public String getAvailabilityMeaning() { return availabilityMeaning; }
    }

    public static final class Provenance {
        private final String explanationMode;
        private final boolean rescored;
        private Provenance(String explanationMode, boolean rescored) {
            this.explanationMode = explanationMode;
            this.rescored = rescored;
        }
        public String getExplanationMode() { return explanationMode; }
        public boolean isRescored() { return rescored; }
    }

    /** Whitelisted Exomiser row context. It is descriptive and never a score contribution. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class EngineCandidateContext {
        private final String engineVersion;
        private final String geneSymbol;
        private final String entrezGeneId;
        private final String rawModeOfInheritance;
        private final String normalizedModeOfInheritance;
        private final Integer geneMoiGroupRank;
        private final String geneMoiGroupRankSemantics;
        private final Double geneCombinedScore;
        private final Double genePhenotypeScore;
        private final Double geneVariantScore;
        private final Double variantScore;
        private final String functionalClass;
        private final String hgvs;
        private final Boolean contributingVariant;
        private final String sourceRecordId;
        private final Integer variantSourceOrdinal;
        private final Integer geneSourceOrdinal;
        private final String geneRowMatchStrategy;
        private final String variantArtifactName;
        private final String geneArtifactName;
        private final String rankingScoreSourceColumn;
        private final Map<String, String> fieldSources;
        private final List<String> missingFields;
        private final List<String> invalidFields;

        public EngineCandidateContext(String engineVersion,
                                      String geneSymbol,
                                      String entrezGeneId,
                                      String rawModeOfInheritance,
                                      String normalizedModeOfInheritance,
                                      Integer geneMoiGroupRank,
                                      String geneMoiGroupRankSemantics,
                                      Double geneCombinedScore,
                                      Double genePhenotypeScore,
                                      Double geneVariantScore,
                                      Double variantScore,
                                      String functionalClass,
                                      String hgvs,
                                      Boolean contributingVariant,
                                      String sourceRecordId,
                                      Integer variantSourceOrdinal,
                                      Integer geneSourceOrdinal,
                                      String geneRowMatchStrategy,
                                      String variantArtifactName,
                                      String geneArtifactName,
                                      String rankingScoreSourceColumn,
                                      Map<String, String> fieldSources,
                                      List<String> missingFields,
                                      List<String> invalidFields) {
            this.engineVersion = engineVersion;
            this.geneSymbol = geneSymbol;
            this.entrezGeneId = entrezGeneId;
            this.rawModeOfInheritance = rawModeOfInheritance;
            this.normalizedModeOfInheritance = normalizedModeOfInheritance;
            this.geneMoiGroupRank = geneMoiGroupRank;
            this.geneMoiGroupRankSemantics = geneMoiGroupRankSemantics;
            this.geneCombinedScore = geneCombinedScore;
            this.genePhenotypeScore = genePhenotypeScore;
            this.geneVariantScore = geneVariantScore;
            this.variantScore = variantScore;
            this.functionalClass = functionalClass;
            this.hgvs = hgvs;
            this.contributingVariant = contributingVariant;
            this.sourceRecordId = sourceRecordId;
            this.variantSourceOrdinal = variantSourceOrdinal;
            this.geneSourceOrdinal = geneSourceOrdinal;
            this.geneRowMatchStrategy = geneRowMatchStrategy;
            this.variantArtifactName = variantArtifactName;
            this.geneArtifactName = geneArtifactName;
            this.rankingScoreSourceColumn = rankingScoreSourceColumn;
            this.fieldSources = immutableMap(fieldSources);
            this.missingFields = immutable(missingFields);
            this.invalidFields = immutable(invalidFields);
        }

        public String getEngineVersion() { return engineVersion; }
        public String getGeneSymbol() { return geneSymbol; }
        public String getEntrezGeneId() { return entrezGeneId; }
        public String getRawModeOfInheritance() { return rawModeOfInheritance; }
        public String getNormalizedModeOfInheritance() { return normalizedModeOfInheritance; }
        public Integer getGeneMoiGroupRank() { return geneMoiGroupRank; }
        public String getGeneMoiGroupRankSemantics() { return geneMoiGroupRankSemantics; }
        public Double getGeneCombinedScore() { return geneCombinedScore; }
        public Double getGenePhenotypeScore() { return genePhenotypeScore; }
        public Double getGeneVariantScore() { return geneVariantScore; }
        public Double getVariantScore() { return variantScore; }
        public String getFunctionalClass() { return functionalClass; }
        public String getHgvs() { return hgvs; }
        public Boolean getContributingVariant() { return contributingVariant; }
        public String getSourceRecordId() { return sourceRecordId; }
        public Integer getVariantSourceOrdinal() { return variantSourceOrdinal; }
        public Integer getGeneSourceOrdinal() { return geneSourceOrdinal; }
        public String getGeneRowMatchStrategy() { return geneRowMatchStrategy; }
        public String getVariantArtifactName() { return variantArtifactName; }
        public String getGeneArtifactName() { return geneArtifactName; }
        public String getRankingScoreSourceColumn() { return rankingScoreSourceColumn; }
        public Map<String, String> getFieldSources() { return fieldSources; }
        public List<String> getMissingFields() { return missingFields; }
        public List<String> getInvalidFields() { return invalidFields; }
    }

    /**
     * Whitelisted facts from the actual VCF passed to this analysis run.  It deliberately
     * excludes sample identity, raw GT/PS, raw records and filesystem provenance.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class AnalysisVcfCallContext {
        private final String schemaVersion;
        private final String matchStatus;
        private final String targetAlleleCall;
        private final String candidateRelativeGenotype;
        private final Integer targetAlleleIndex;
        private final Integer depth;
        private final Integer genotypeQuality;
        private final Integer referenceDepth;
        private final Integer targetAlleleDepth;
        private final Double alleleBalance;
        private final String filterEvaluation;
        private final String phasingStatus;
        private final String phaseSetToken;
        private final Integer matchingRecordCount;
        private final Integer matchedRecordOrdinal;
        private final String assembly;
        private final String artifactRole;
        private final String provenance;
        private final List<String> missingFields;
        private final List<String> invalidFields;

        public AnalysisVcfCallContext(String schemaVersion,
                                      String matchStatus,
                                      String targetAlleleCall,
                                      String candidateRelativeGenotype,
                                      Integer targetAlleleIndex,
                                      Integer depth,
                                      Integer genotypeQuality,
                                      Integer referenceDepth,
                                      Integer targetAlleleDepth,
                                      Double alleleBalance,
                                      String filterEvaluation,
                                      String phasingStatus,
                                      String phaseSetToken,
                                      Integer matchingRecordCount,
                                      Integer matchedRecordOrdinal,
                                      String assembly,
                                      String artifactRole,
                                      String provenance,
                                      List<String> missingFields,
                                      List<String> invalidFields) {
            this.schemaVersion = schemaVersion;
            this.matchStatus = matchStatus;
            this.targetAlleleCall = targetAlleleCall;
            this.candidateRelativeGenotype = candidateRelativeGenotype;
            this.targetAlleleIndex = targetAlleleIndex;
            this.depth = depth;
            this.genotypeQuality = genotypeQuality;
            this.referenceDepth = referenceDepth;
            this.targetAlleleDepth = targetAlleleDepth;
            this.alleleBalance = alleleBalance;
            this.filterEvaluation = filterEvaluation;
            this.phasingStatus = phasingStatus;
            this.phaseSetToken = phaseSetToken;
            this.matchingRecordCount = matchingRecordCount;
            this.matchedRecordOrdinal = matchedRecordOrdinal;
            this.assembly = assembly;
            this.artifactRole = artifactRole;
            this.provenance = provenance;
            this.missingFields = immutable(missingFields);
            this.invalidFields = immutable(invalidFields);
        }

        public String getSchemaVersion() { return schemaVersion; }
        public String getMatchStatus() { return matchStatus; }
        public String getTargetAlleleCall() { return targetAlleleCall; }
        public String getCandidateRelativeGenotype() { return candidateRelativeGenotype; }
        public Integer getTargetAlleleIndex() { return targetAlleleIndex; }
        public Integer getDepth() { return depth; }
        public Integer getGenotypeQuality() { return genotypeQuality; }
        public Integer getReferenceDepth() { return referenceDepth; }
        public Integer getTargetAlleleDepth() { return targetAlleleDepth; }
        public Double getAlleleBalance() { return alleleBalance; }
        public String getFilterEvaluation() { return filterEvaluation; }
        public String getPhasingStatus() { return phasingStatus; }
        public String getPhaseSetToken() { return phaseSetToken; }
        public Integer getMatchingRecordCount() { return matchingRecordCount; }
        public Integer getMatchedRecordOrdinal() { return matchedRecordOrdinal; }
        public String getAssembly() { return assembly; }
        public String getArtifactRole() { return artifactRole; }
        public String getProvenance() { return provenance; }
        public List<String> getMissingFields() { return missingFields; }
        public List<String> getInvalidFields() { return invalidFields; }
    }
}
