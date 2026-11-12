package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(value = "canonicalVariantId", allowGetters = true)
public class VariantInterpretation {

    private final String variantId;
    private final CandidateIdentity candidateIdentity;
    private final ExomiserCandidateFeatures exomiserCandidateFeatures;
    private final AnalysisVcfCall analysisVcfCall;
    private final VariantQcObservation observation;
    private final List<TranscriptConsequence> consequences;
    private List<TranscriptConsequence> transcriptAnnotations;
    private final List<EvidenceItem> evidence;
    private String priorityTier;
    private Double rankingScore;
    private Map<String, Double> scoreBreakdown;
    private String tierReason;
    private Map<String, ScoreBreakdownComponent> scoreComponents;
    private List<String> positiveReasons;
    private List<String> negativeReasons;
    private List<String> rationale;
    private List<String> warnings;
    private List<String> filterReasons;
    private List<AcmgEvidenceSuggestion> acmgEvidenceSuggestions;
    private VariantAnalysisBucket analysisBucket;
    private boolean largeFileMode;
    private boolean summaryOnly;
    private boolean fullInterpretationAvailable;
    private boolean expensiveAnnotationSkipped;
    private String expensiveAnnotationSkippedReason;
    private List<String> cheapGateReasons;

    public VariantInterpretation(String variantId,
                                 VariantQcObservation observation,
                                 List<TranscriptConsequence> consequences,
                                 List<EvidenceItem> evidence) {
        this(variantId, observation, consequences, evidence, null, null, null, null, null);
    }

    public VariantInterpretation(CandidateIdentity candidateIdentity,
                                 VariantQcObservation observation,
                                 List<TranscriptConsequence> consequences,
                                 List<EvidenceItem> evidence) {
        this(candidateIdentity, observation, consequences, evidence, null);
    }

    public VariantInterpretation(CandidateIdentity candidateIdentity,
                                 VariantQcObservation observation,
                                 List<TranscriptConsequence> consequences,
                                 List<EvidenceItem> evidence,
                                 ExomiserCandidateFeatures exomiserCandidateFeatures) {
        this(
                candidateIdentity == null ? null : candidateIdentity.getCanonicalVariantId(),
                observation,
                consequences,
                null,
                evidence,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                candidateIdentity,
                candidateIdentity == null ? null : candidateIdentity.getCandidateId(),
                exomiserCandidateFeatures
        );
    }

    public VariantInterpretation(String variantId,
                                 CandidateIdentity candidateIdentity,
                                 VariantQcObservation observation,
                                 List<TranscriptConsequence> consequences,
                                 List<EvidenceItem> evidence) {
        this(
                variantId,
                observation,
                consequences,
                null,
                evidence,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                candidateIdentity,
                candidateIdentity == null ? null : candidateIdentity.getCandidateId(),
                null
        );
    }

    public VariantInterpretation(@JsonProperty("variantId") String variantId,
                                 @JsonProperty("observation") VariantQcObservation observation,
                                 @JsonProperty("consequences") List<TranscriptConsequence> consequences,
                                 @JsonProperty("transcriptAnnotations")
                                         List<TranscriptConsequence> transcriptAnnotations,
                                 @JsonProperty("evidence") List<EvidenceItem> evidence,
                                 @JsonProperty("priorityTier") String priorityTier,
                                 @JsonProperty("rankingScore") Double rankingScore,
                                 @JsonProperty("scoreBreakdown") Map<String, Double> scoreBreakdown,
                                 @JsonProperty("rationale") List<String> rationale,
                                 @JsonProperty("warnings") List<String> warnings,
                                 @JsonProperty("tier") String tier,
                                 @JsonProperty("totalScore") Double totalScore,
                                 @JsonProperty("tierReason") String tierReason,
                                 @JsonProperty("scoreComponents")
                                         Map<String, ScoreBreakdownComponent> scoreComponents,
                                 @JsonProperty("positiveReasons") List<String> positiveReasons,
                                 @JsonProperty("negativeReasons") List<String> negativeReasons,
                                 @JsonProperty("filterReasons") List<String> filterReasons,
                                 @JsonProperty("acmgEvidenceSuggestions")
                                         List<AcmgEvidenceSuggestion> acmgEvidenceSuggestions,
                                 @JsonProperty("analysisBucket") VariantAnalysisBucket analysisBucket,
                                 @JsonProperty("largeFileMode") Boolean largeFileMode,
                                 @JsonProperty("summaryOnly") Boolean summaryOnly,
                                 @JsonProperty("expensiveAnnotationSkipped") Boolean expensiveAnnotationSkipped,
                                 @JsonProperty("expensiveAnnotationSkippedReason")
                                         String expensiveAnnotationSkippedReason,
                                 @JsonProperty("cheapGateReasons") List<String> cheapGateReasons,
                                  @JsonProperty("fullInterpretationAvailable")
                                          Boolean fullInterpretationAvailable,
                                  @JsonProperty("candidateIdentity") CandidateIdentity candidateIdentity,
                                  @JsonProperty("candidateId") String candidateId,
                                  @JsonProperty("exomiserCandidateFeatures")
                                          ExomiserCandidateFeatures exomiserCandidateFeatures) {
        this(
                variantId,
                observation,
                consequences,
                transcriptAnnotations,
                evidence,
                priorityTier,
                rankingScore,
                scoreBreakdown,
                rationale,
                warnings,
                tier,
                totalScore,
                tierReason,
                scoreComponents,
                positiveReasons,
                negativeReasons,
                filterReasons,
                acmgEvidenceSuggestions,
                analysisBucket,
                largeFileMode,
                summaryOnly,
                expensiveAnnotationSkipped,
                expensiveAnnotationSkippedReason,
                cheapGateReasons,
                fullInterpretationAvailable,
                candidateIdentity,
                candidateId,
                exomiserCandidateFeatures,
                null
        );
    }

    @JsonCreator
    public VariantInterpretation(@JsonProperty("variantId") String variantId,
                                 @JsonProperty("observation") VariantQcObservation observation,
                                 @JsonProperty("consequences") List<TranscriptConsequence> consequences,
                                 @JsonProperty("transcriptAnnotations")
                                         List<TranscriptConsequence> transcriptAnnotations,
                                 @JsonProperty("evidence") List<EvidenceItem> evidence,
                                 @JsonProperty("priorityTier") String priorityTier,
                                 @JsonProperty("rankingScore") Double rankingScore,
                                 @JsonProperty("scoreBreakdown") Map<String, Double> scoreBreakdown,
                                 @JsonProperty("rationale") List<String> rationale,
                                 @JsonProperty("warnings") List<String> warnings,
                                 @JsonProperty("tier") String tier,
                                 @JsonProperty("totalScore") Double totalScore,
                                 @JsonProperty("tierReason") String tierReason,
                                 @JsonProperty("scoreComponents")
                                         Map<String, ScoreBreakdownComponent> scoreComponents,
                                 @JsonProperty("positiveReasons") List<String> positiveReasons,
                                 @JsonProperty("negativeReasons") List<String> negativeReasons,
                                 @JsonProperty("filterReasons") List<String> filterReasons,
                                 @JsonProperty("acmgEvidenceSuggestions")
                                         List<AcmgEvidenceSuggestion> acmgEvidenceSuggestions,
                                 @JsonProperty("analysisBucket") VariantAnalysisBucket analysisBucket,
                                 @JsonProperty("largeFileMode") Boolean largeFileMode,
                                 @JsonProperty("summaryOnly") Boolean summaryOnly,
                                 @JsonProperty("expensiveAnnotationSkipped") Boolean expensiveAnnotationSkipped,
                                 @JsonProperty("expensiveAnnotationSkippedReason")
                                         String expensiveAnnotationSkippedReason,
                                 @JsonProperty("cheapGateReasons") List<String> cheapGateReasons,
                                 @JsonProperty("fullInterpretationAvailable")
                                         Boolean fullInterpretationAvailable,
                                 @JsonProperty("candidateIdentity") CandidateIdentity candidateIdentity,
                                 @JsonProperty("candidateId") String candidateId,
                                 @JsonProperty("exomiserCandidateFeatures")
                                         ExomiserCandidateFeatures exomiserCandidateFeatures,
                                 @JsonProperty("analysisVcfCall") AnalysisVcfCall analysisVcfCall) {
        this.variantId = variantId;
        this.candidateIdentity = resolveCandidateIdentity(
                candidateIdentity,
                candidateId,
                variantId,
                observation
        );
        this.exomiserCandidateFeatures = validateExomiserCandidateFeatures(
                this.candidateIdentity,
                exomiserCandidateFeatures
        );
        validateAnalysisVcfCallAssembly(observation, analysisVcfCall);
        this.analysisVcfCall = analysisVcfCall;
        this.observation = observation;
        this.consequences = mutableCopy(consequences);
        this.transcriptAnnotations = immutableCopy(transcriptAnnotations);
        this.evidence = new ArrayList<EvidenceItem>();
        addEvidence(evidence);
        this.priorityTier = priorityTier == null ? tier : priorityTier;
        this.rankingScore = rankingScore == null ? totalScore : rankingScore;
        this.scoreBreakdown = immutableMapCopy(scoreBreakdown);
        this.tierReason = tierReason;
        this.scoreComponents = immutableComponentMapCopy(scoreComponents);
        this.positiveReasons = immutableCopy(positiveReasons);
        this.negativeReasons = immutableCopy(negativeReasons);
        this.rationale = immutableCopy(rationale);
        this.warnings = immutableCopy(warnings);
        this.filterReasons = immutableCopy(filterReasons);
        this.acmgEvidenceSuggestions = immutableCopy(acmgEvidenceSuggestions);
        this.analysisBucket = analysisBucket == null ? VariantAnalysisBucket.CANDIDATE : analysisBucket;
        this.largeFileMode = Boolean.TRUE.equals(largeFileMode);
        this.summaryOnly = Boolean.TRUE.equals(summaryOnly);
        this.fullInterpretationAvailable = fullInterpretationAvailable == null
                ? true
                : fullInterpretationAvailable.booleanValue();
        this.expensiveAnnotationSkipped = Boolean.TRUE.equals(expensiveAnnotationSkipped);
        this.expensiveAnnotationSkippedReason = expensiveAnnotationSkippedReason;
        this.cheapGateReasons = immutableCopy(cheapGateReasons);
    }

    public VariantInterpretation(String variantId,
                                 VariantQcObservation observation,
                                 List<TranscriptConsequence> consequences,
                                 List<TranscriptConsequence> transcriptAnnotations,
                                 List<EvidenceItem> evidence,
                                 String priorityTier,
                                 Double rankingScore,
                                 Map<String, Double> scoreBreakdown,
                                 List<String> rationale,
                                 List<String> warnings,
                                 String tier,
                                 Double totalScore,
                                 String tierReason,
                                 Map<String, ScoreBreakdownComponent> scoreComponents,
                                 List<String> positiveReasons,
                                 List<String> negativeReasons,
                                 List<String> filterReasons,
                                 List<AcmgEvidenceSuggestion> acmgEvidenceSuggestions,
                                 VariantAnalysisBucket analysisBucket,
                                 Boolean largeFileMode,
                                 Boolean summaryOnly,
                                 Boolean expensiveAnnotationSkipped,
                                 String expensiveAnnotationSkippedReason,
                                 List<String> cheapGateReasons,
                                 Boolean fullInterpretationAvailable) {
        this(
                variantId,
                observation,
                consequences,
                transcriptAnnotations,
                evidence,
                priorityTier,
                rankingScore,
                scoreBreakdown,
                rationale,
                warnings,
                tier,
                totalScore,
                tierReason,
                scoreComponents,
                positiveReasons,
                negativeReasons,
                filterReasons,
                acmgEvidenceSuggestions,
                analysisBucket,
                largeFileMode,
                summaryOnly,
                expensiveAnnotationSkipped,
                expensiveAnnotationSkippedReason,
                cheapGateReasons,
                fullInterpretationAvailable,
                null,
                null,
                null
        );
    }

    public VariantInterpretation(String variantId,
                                 VariantQcObservation observation,
                                 List<TranscriptConsequence> consequences,
                                 List<EvidenceItem> evidence,
                                 String priorityTier,
                                 Double rankingScore,
                                 Map<String, Double> scoreBreakdown,
                                 List<String> rationale,
                                 List<String> warnings) {
        this(
                variantId,
                observation,
                consequences,
                null,
                evidence,
                priorityTier,
                rankingScore,
                scoreBreakdown,
                rationale,
                warnings,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public void addEvidence(List<EvidenceItem> additionalEvidence) {
        if (additionalEvidence != null) {
            for (EvidenceItem item : additionalEvidence) {
                addOrMergeEvidence(item);
            }
        }
    }

    public void applyTranscriptAnnotations(List<TranscriptConsequence> annotations) {
        this.transcriptAnnotations = immutableCopy(annotations);
    }

    public void applyAcmgEvidenceSuggestions(List<AcmgEvidenceSuggestion> suggestions) {
        this.acmgEvidenceSuggestions = immutableCopy(suggestions);
    }

    public void replaceEvidence(String source, EvidenceItem replacement) {
        evidence.removeIf(item -> source.equals(item.getSource()));
        if (replacement != null) {
            evidence.add(replacement);
        }
    }

    public void applyRanking(String priorityTier,
                             Double rankingScore,
                             List<String> rationale,
                             List<String> warnings) {
        applyRanking(priorityTier, rankingScore, Collections.emptyMap(), rationale, warnings);
    }

    public void applyRanking(String priorityTier,
                             Double rankingScore,
                             Map<String, Double> scoreBreakdown,
                             List<String> rationale,
                             List<String> warnings) {
        applyRanking(
                priorityTier,
                rankingScore,
                scoreBreakdown,
                Collections.emptyMap(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                rationale,
                warnings,
                Collections.emptyList()
        );
    }

    public void applyRanking(String priorityTier,
                             Double rankingScore,
                             Map<String, Double> scoreBreakdown,
                             Map<String, ScoreBreakdownComponent> scoreComponents,
                             String tierReason,
                             List<String> positiveReasons,
                             List<String> negativeReasons,
                             List<String> rationale,
                             List<String> warnings,
                             List<String> filterReasons) {
        this.priorityTier = priorityTier;
        this.rankingScore = rankingScore;
        this.scoreBreakdown = immutableMapCopy(scoreBreakdown);
        this.scoreComponents = immutableComponentMapCopy(scoreComponents);
        this.tierReason = tierReason;
        this.positiveReasons = immutableCopy(positiveReasons);
        this.negativeReasons = immutableCopy(negativeReasons);
        this.rationale = immutableCopy(rationale);
        this.warnings = immutableCopy(warnings);
        this.filterReasons = immutableCopy(filterReasons);
    }

    public void applyLargeFileClassification(VariantAnalysisBucket analysisBucket,
                                             boolean largeFileMode,
                                             boolean summaryOnly,
                                             boolean expensiveAnnotationSkipped,
                                             String expensiveAnnotationSkippedReason,
                                             List<String> cheapGateReasons) {
        this.analysisBucket = analysisBucket == null ? VariantAnalysisBucket.CANDIDATE : analysisBucket;
        this.largeFileMode = largeFileMode;
        this.summaryOnly = summaryOnly;
        this.expensiveAnnotationSkipped = expensiveAnnotationSkipped;
        this.expensiveAnnotationSkippedReason = expensiveAnnotationSkippedReason;
        this.cheapGateReasons = immutableCopy(cheapGateReasons);
    }

    public void applyFullInterpretationAvailable(boolean fullInterpretationAvailable) {
        this.fullInterpretationAvailable = fullInterpretationAvailable;
    }

    public void copyLargeFileClassificationFrom(VariantInterpretation source) {
        if (source == null) {
            return;
        }
        applyLargeFileClassification(
                source.getAnalysisBucket(),
                source.isLargeFileMode(),
                source.isSummaryOnly(),
                source.isExpensiveAnnotationSkipped(),
                source.getExpensiveAnnotationSkippedReason(),
                source.getCheapGateReasons()
        );
        applyFullInterpretationAvailable(source.isFullInterpretationAvailable());
    }

    /** Returns an equivalent interpretation hydrated with its persisted candidate-row identity. */
    public VariantInterpretation withCandidateIdentity(CandidateIdentity identity) {
        return withCandidateIdentityAndExomiserFeatures(identity, exomiserCandidateFeatures);
    }

    /**
     * Returns an equivalent interpretation with persisted candidate identity and immutable
     * Exomiser row context. This is used when JDBC builds a compact relational summary while the
     * structured engine context is retained in the validated payload.
     */
    public VariantInterpretation withCandidateIdentityAndExomiserFeatures(
            CandidateIdentity identity,
            ExomiserCandidateFeatures features) {
        return new VariantInterpretation(
                variantId,
                observation,
                consequences,
                transcriptAnnotations,
                evidence,
                priorityTier,
                rankingScore,
                scoreBreakdown,
                rationale,
                warnings,
                priorityTier,
                rankingScore,
                tierReason,
                scoreComponents,
                positiveReasons,
                negativeReasons,
                filterReasons,
                acmgEvidenceSuggestions,
                analysisBucket,
                largeFileMode,
                summaryOnly,
                expensiveAnnotationSkipped,
                expensiveAnnotationSkippedReason,
                cheapGateReasons,
                fullInterpretationAvailable,
                identity,
                identity == null ? null : identity.getCandidateId(),
                features,
                analysisVcfCall
        );
    }

    /** Returns an equivalent interpretation enriched with the call from the analysis VCF. */
    public VariantInterpretation withAnalysisVcfCall(AnalysisVcfCall call) {
        return new VariantInterpretation(
                variantId,
                observation,
                consequences,
                transcriptAnnotations,
                evidence,
                priorityTier,
                rankingScore,
                scoreBreakdown,
                rationale,
                warnings,
                priorityTier,
                rankingScore,
                tierReason,
                scoreComponents,
                positiveReasons,
                negativeReasons,
                filterReasons,
                acmgEvidenceSuggestions,
                analysisBucket,
                largeFileMode,
                summaryOnly,
                expensiveAnnotationSkipped,
                expensiveAnnotationSkippedReason,
                cheapGateReasons,
                fullInterpretationAvailable,
                candidateIdentity,
                candidateIdentity == null ? null : candidateIdentity.getCandidateId(),
                exomiserCandidateFeatures,
                call
        );
    }

    public String getVariantId() {
        return variantId;
    }

    /** Analysis-local candidate-row identity. */
    public String getCandidateId() {
        return candidateIdentity.getCandidateId();
    }

    /** Canonical normalized allele identity; {@link #getVariantId()} remains the legacy identifier. */
    public String getCanonicalVariantId() {
        return candidateIdentity.getCanonicalVariantId();
    }

    public CandidateIdentity getCandidateIdentity() {
        return candidateIdentity;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ExomiserCandidateFeatures getExomiserCandidateFeatures() {
        return exomiserCandidateFeatures;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public AnalysisVcfCall getAnalysisVcfCall() {
        return analysisVcfCall;
    }

    private static void validateAnalysisVcfCallAssembly(VariantQcObservation observation,
                                                        AnalysisVcfCall call) {
        if (call == null || observation == null || observation.getKey() == null) {
            return;
        }
        if (!call.getAssembly().equals(observation.getKey().getGenomeAssembly())) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_CANDIDATE_ASSEMBLY_MISMATCH");
        }
    }

    public VariantQcObservation getObservation() {
        return observation;
    }

    public List<TranscriptConsequence> getConsequences() {
        return Collections.unmodifiableList(consequences);
    }

    public List<TranscriptConsequence> getTranscriptAnnotations() {
        return transcriptAnnotations;
    }

    public List<EvidenceItem> getEvidence() {
        return Collections.unmodifiableList(evidence);
    }

    public String getPriorityTier() {
        return priorityTier;
    }

    public String getTier() {
        return priorityTier;
    }

    public Double getRankingScore() {
        return rankingScore;
    }

    public Double getTotalScore() {
        return rankingScore;
    }

    public Map<String, Double> getScoreBreakdown() {
        return scoreBreakdown;
    }

    public String getTierReason() {
        return tierReason;
    }

    public Map<String, ScoreBreakdownComponent> getScoreComponents() {
        return scoreComponents;
    }

    public List<String> getPositiveReasons() {
        return positiveReasons;
    }

    public List<String> getNegativeReasons() {
        return negativeReasons;
    }

    public List<String> getRationale() {
        return rationale;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getFilterReasons() {
        return filterReasons;
    }

    public List<AcmgEvidenceSuggestion> getAcmgEvidenceSuggestions() {
        return acmgEvidenceSuggestions;
    }

    public VariantAnalysisBucket getAnalysisBucket() {
        return analysisBucket;
    }

    public boolean isLargeFileMode() {
        return largeFileMode;
    }

    public boolean isSummaryOnly() {
        return summaryOnly;
    }

    public boolean isFullInterpretationAvailable() {
        return fullInterpretationAvailable;
    }

    public boolean isExpensiveAnnotationSkipped() {
        return expensiveAnnotationSkipped;
    }

    public String getExpensiveAnnotationSkippedReason() {
        return expensiveAnnotationSkippedReason;
    }

    public List<String> getCheapGateReasons() {
        return cheapGateReasons;
    }

    private static <T> List<T> mutableCopy(List<T> source) {
        return source == null ? new ArrayList<T>() : new ArrayList<T>(source);
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return source == null || source.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(source));
    }

    private static Map<String, Double> immutableMapCopy(Map<String, Double> source) {
        return source == null || source.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Double>(source));
    }

    private static Map<String, ScoreBreakdownComponent> immutableComponentMapCopy(
            Map<String, ScoreBreakdownComponent> source) {
        return source == null || source.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, ScoreBreakdownComponent>(source));
    }

    private static CandidateIdentity resolveCandidateIdentity(CandidateIdentity identity,
                                                               String candidateId,
                                                               String variantId,
                                                               VariantQcObservation observation) {
        String canonicalVariantId = observation != null && observation.getKey() != null
                ? observation.getKey().canonicalId()
                : variantId;
        if (identity != null) {
            if (canonicalVariantId != null
                    && !canonicalVariantId.equals(identity.getCanonicalVariantId())) {
                throw new IllegalArgumentException("CANDIDATE_CANONICAL_VARIANT_ID_MISMATCH");
            }
            if (candidateId != null
                    && !candidateId.trim().isEmpty()
                    && !candidateId.trim().equals(identity.getCandidateId())) {
                throw new IllegalArgumentException("CANDIDATE_ID_MISMATCH");
            }
            return identity;
        }
        return CandidateIdentity.legacy(candidateId, canonicalVariantId);
    }

    private static ExomiserCandidateFeatures validateExomiserCandidateFeatures(
            CandidateIdentity identity,
            ExomiserCandidateFeatures features) {
        if (features == null) {
            return null;
        }
        if (identity == null || !"EXOMISER".equals(identity.getSource())) {
            throw new IllegalArgumentException("EXOMISER_FEATURE_IDENTITY_SOURCE_MISMATCH");
        }
        if (identity.getSourceOrdinal() == null
                || features.getVariantSourceOrdinal() == null
                || !identity.getSourceOrdinal().equals(features.getVariantSourceOrdinal())) {
            throw new IllegalArgumentException("EXOMISER_FEATURE_SOURCE_ORDINAL_MISMATCH");
        }
        if (!sameNullableGene(identity.getGeneSymbol(), features.getGeneSymbol())) {
            throw new IllegalArgumentException("EXOMISER_FEATURE_GENE_SYMBOL_MISMATCH");
        }
        if (!sameNullableMoi(identity.getModeOfInheritance(), features.getRawModeOfInheritance())) {
            throw new IllegalArgumentException("EXOMISER_FEATURE_MOI_MISMATCH");
        }
        if (!java.util.Objects.equals(
                identity.getDeclaredRank(),
                features.getGeneMoiGroupRank()
        )) {
            throw new IllegalArgumentException("EXOMISER_FEATURE_RANK_MISMATCH");
        }
        return features;
    }

    private static boolean sameNullableGene(String left, String right) {
        String normalizedLeft = trimToNull(left);
        String normalizedRight = trimToNull(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return normalizedLeft == null && normalizedRight == null;
        }
        return normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    private static boolean sameNullableMoi(String left, String right) {
        String normalizedLeft = trimToNull(left);
        String normalizedRight = trimToNull(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return normalizedLeft == null && normalizedRight == null;
        }
        return stableMoiKey(normalizedLeft).equals(stableMoiKey(normalizedRight));
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String stableMoiKey(String raw) {
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT)
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
            default:
                return normalized;
        }
    }

    private void addOrMergeEvidence(EvidenceItem incoming) {
        if (incoming == null) {
            return;
        }
        EvidenceItem contextualized = contextualizeEvidence(incoming);
        if (isClinVarVcfFallback(contextualized)) {
            removeNonAuthoritativeClinVarStatuses();
        } else if (isNonAuthoritativeClinVarStatus(contextualized)
                && hasApplicableClinVarVcfFallback()) {
            return;
        }
        int existingIndex = indexOfEvidence(contextualized);
        if (existingIndex < 0) {
            evidence.add(contextualized);
            return;
        }
        EvidenceItem existing = evidence.get(existingIndex);
        if (shouldReplaceEvidence(existing, contextualized)) {
            evidence.set(existingIndex, contextualized);
        }
    }

    private EvidenceItem contextualizeEvidence(EvidenceItem incoming) {
        if (observation == null || observation.getKey() == null) {
            return incoming.withVariantContext(null, null);
        }
        return incoming.withVariantContext(observation.getKey());
    }

    private int indexOfEvidence(EvidenceItem incoming) {
        for (int index = 0; index < evidence.size(); index++) {
            EvidenceItem item = evidence.get(index);
            if (sameEvidenceIdentity(item, incoming)) {
                return index;
            }
        }
        return -1;
    }

    private boolean sameEvidenceIdentity(EvidenceItem left, EvidenceItem right) {
        String leftIdentity = left.getStableIdentityKey();
        String rightIdentity = right.getStableIdentityKey();
        if (leftIdentity != null || rightIdentity != null) {
            return leftIdentity != null && leftIdentity.equals(rightIdentity);
        }
        return sameSource(left.getSource(), right.getSource());
    }

    private boolean shouldReplaceEvidence(EvidenceItem existing, EvidenceItem incoming) {
        if (isVcfInfo(existing) && !isVcfInfo(incoming)
                && authoritativeLocalStatus(incoming.getApplicability())) {
            return true;
        }
        if (isVcfInfo(existing) && !isVcfInfo(incoming)
                && "NO_RECORD".equals(existing.getApplicability())
                && "NOT_APPLICABLE".equals(incoming.getApplicability())) {
            return true;
        }
        if (!isVcfInfo(existing) && isVcfInfo(incoming)) {
            return false;
        }
        int existingRank = applicabilityRank(existing.getApplicability());
        int incomingRank = applicabilityRank(incoming.getApplicability());
        if (incomingRank != existingRank) {
            return incomingRank > existingRank;
        }
        if (!"APPLICABLE".equals(incoming.getApplicability())) {
            return false;
        }
        if ("GNOMAD".equals(incoming.getSource())) {
            return scoreOrNegative(existing.getRawScore()) < scoreOrNegative(incoming.getRawScore());
        }
        if ("CLINVAR".equals(incoming.getSource())) {
            return "VCF_INFO".equals(existing.getSourceVersion())
                    && !"VCF_INFO".equals(incoming.getSourceVersion());
        }
        return true;
    }

    private boolean authoritativeLocalStatus(String applicability) {
        return "APPLICABLE".equals(applicability)
                || "CONFLICTING".equals(applicability)
                || "NO_RECORD".equals(applicability);
    }

    private int applicabilityRank(String applicability) {
        if ("APPLICABLE".equals(applicability)) {
            return 4;
        }
        if ("CONFLICTING".equals(applicability)) {
            return 3;
        }
        if ("NO_RECORD".equals(applicability)) {
            return 2;
        }
        if ("NOT_APPLICABLE".equals(applicability)) {
            return 1;
        }
        if ("UNAVAILABLE".equals(applicability)) {
            return 0;
        }
        return 0;
    }

    private boolean isVcfInfo(EvidenceItem evidence) {
        return evidence != null && "VCF_INFO".equals(evidence.getSourceVersion());
    }

    private boolean hasApplicableClinVarVcfFallback() {
        for (EvidenceItem item : evidence) {
            if (isClinVarVcfFallback(item) && "APPLICABLE".equals(item.getApplicability())) {
                return true;
            }
        }
        return false;
    }

    private boolean isClinVarVcfFallback(EvidenceItem evidence) {
        return evidence != null && "CLINVAR".equals(evidence.getSource()) && isVcfInfo(evidence);
    }

    private boolean isNonAuthoritativeClinVarStatus(EvidenceItem evidence) {
        return evidence != null
                && "CLINVAR".equals(evidence.getSource())
                && !isVcfInfo(evidence)
                && !authoritativeLocalStatus(evidence.getApplicability());
    }

    private void removeNonAuthoritativeClinVarStatuses() {
        evidence.removeIf(this::isNonAuthoritativeClinVarStatus);
    }

    private double scoreOrNegative(Double value) {
        return value == null ? Double.NEGATIVE_INFINITY : value;
    }

    private boolean sameSource(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

}
