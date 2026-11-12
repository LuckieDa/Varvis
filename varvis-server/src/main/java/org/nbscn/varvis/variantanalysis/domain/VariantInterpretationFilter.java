package org.nbscn.varvis.variantanalysis.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class VariantInterpretationFilter {

    private static final VariantInterpretationFilter EMPTY =
            new VariantInterpretationFilter(
                    null,
                    false,
                    false,
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
    private static final String PHENOTYPE_SCORE_COMPONENT = "phenotype_support";
    private static final String PHENOTYPE_EVIDENCE_TYPE = "PHENOTYPE_GENE_PRIOR";

    private final String tier;
    private final boolean hideFilteredCommon;
    private final boolean hideFilteredQc;
    private final String clinvarClass;
    private final String search;
    private final String acmgCode;
    private final String acmgStatus;
    private final Boolean manualReviewRequired;
    private final Boolean hasAcmgSuggestion;
    private final Boolean hasPhenotypeSupport;
    private final String phenotypeGene;
    private final Double minPhenotypeScore;
    private final String analysisBucket;
    private final Boolean summaryOnly;
    private final Boolean largeFileMode;
    private final Boolean expensiveAnnotationSkipped;

    public VariantInterpretationFilter(String tier,
                                       boolean hideFilteredCommon,
                                       boolean hideFilteredQc,
                                       String clinvarClass,
                                       String search) {
        this(tier, hideFilteredCommon, hideFilteredQc, clinvarClass, search, null, null, null, null);
    }

    public VariantInterpretationFilter(String tier,
                                       boolean hideFilteredCommon,
                                       boolean hideFilteredQc,
                                       String clinvarClass,
                                       String search,
                                       String acmgCode,
                                       String acmgStatus,
                                       Boolean manualReviewRequired,
                                       Boolean hasAcmgSuggestion) {
        this(
                tier,
                hideFilteredCommon,
                hideFilteredQc,
                clinvarClass,
                search,
                acmgCode,
                acmgStatus,
                manualReviewRequired,
                hasAcmgSuggestion,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public VariantInterpretationFilter(String tier,
                                       boolean hideFilteredCommon,
                                       boolean hideFilteredQc,
                                       String clinvarClass,
                                       String search,
                                       String acmgCode,
                                       String acmgStatus,
                                       Boolean manualReviewRequired,
                                       Boolean hasAcmgSuggestion,
                                       Boolean hasPhenotypeSupport,
                                       String phenotypeGene,
                                       Double minPhenotypeScore) {
        this(
                tier,
                hideFilteredCommon,
                hideFilteredQc,
                clinvarClass,
                search,
                acmgCode,
                acmgStatus,
                manualReviewRequired,
                hasAcmgSuggestion,
                hasPhenotypeSupport,
                phenotypeGene,
                minPhenotypeScore,
                null,
                null,
                null,
                null
        );
    }

    public VariantInterpretationFilter(String tier,
                                       boolean hideFilteredCommon,
                                       boolean hideFilteredQc,
                                       String clinvarClass,
                                       String search,
                                       String acmgCode,
                                       String acmgStatus,
                                       Boolean manualReviewRequired,
                                       Boolean hasAcmgSuggestion,
                                       Boolean hasPhenotypeSupport,
                                       String phenotypeGene,
                                       Double minPhenotypeScore,
                                       String analysisBucket,
                                       Boolean summaryOnly,
                                       Boolean largeFileMode,
                                       Boolean expensiveAnnotationSkipped) {
        this.tier = trimToNull(tier);
        this.hideFilteredCommon = hideFilteredCommon;
        this.hideFilteredQc = hideFilteredQc;
        this.clinvarClass = normalizeClinvarClass(clinvarClass);
        this.search = trimToNull(search);
        this.acmgCode = trimToNull(acmgCode);
        this.acmgStatus = trimToNull(acmgStatus) == null
                ? null
                : trimToNull(acmgStatus).toUpperCase(Locale.ROOT);
        this.manualReviewRequired = manualReviewRequired;
        this.hasAcmgSuggestion = hasAcmgSuggestion;
        this.hasPhenotypeSupport = hasPhenotypeSupport;
        this.phenotypeGene = trimToNull(phenotypeGene);
        this.minPhenotypeScore = minPhenotypeScore;
        this.analysisBucket = trimToNull(analysisBucket);
        this.summaryOnly = summaryOnly;
        this.largeFileMode = largeFileMode;
        this.expensiveAnnotationSkipped = expensiveAnnotationSkipped;
    }

    public static VariantInterpretationFilter empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return tier == null
                && !hideFilteredCommon
                && !hideFilteredQc
                && clinvarClass == null
                && search == null
                && acmgCode == null
                && acmgStatus == null
                && manualReviewRequired == null
                && hasAcmgSuggestion == null
                && hasPhenotypeSupport == null
                && phenotypeGene == null
                && minPhenotypeScore == null
                && analysisBucket == null
                && summaryOnly == null
                && largeFileMode == null
                && expensiveAnnotationSkipped == null;
    }

    public String getTier() {
        return tier;
    }

    public Set<String> getTierValues() {
        if (tier == null) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<String>();
        for (String token : tier.split(",")) {
            String normalized = trimToNull(token);
            if (normalized != null) {
                values.add(normalized.toUpperCase(Locale.ROOT));
            }
        }
        return values;
    }

    public boolean isHideFilteredCommon() {
        return hideFilteredCommon;
    }

    public boolean isHideFilteredQc() {
        return hideFilteredQc;
    }

    public String getClinvarClass() {
        return clinvarClass;
    }

    public String getSearch() {
        return search;
    }

    public String getAcmgCode() {
        return acmgCode;
    }

    public Set<String> getAcmgCodeValues() {
        if (acmgCode == null) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<String>();
        for (String token : acmgCode.split(",")) {
            String normalized = trimToNull(token);
            if (normalized != null) {
                values.add(normalized.toUpperCase(Locale.ROOT));
            }
        }
        return values;
    }

    public String getAcmgStatus() {
        return acmgStatus;
    }

    public Boolean getManualReviewRequired() {
        return manualReviewRequired;
    }

    public Boolean getHasAcmgSuggestion() {
        return hasAcmgSuggestion;
    }

    public Boolean getHasPhenotypeSupport() {
        return hasPhenotypeSupport;
    }

    public String getPhenotypeGene() {
        return phenotypeGene;
    }

    public Double getMinPhenotypeScore() {
        return minPhenotypeScore;
    }

    public String getAnalysisBucket() {
        return analysisBucket;
    }

    public Set<String> getAnalysisBucketValues() {
        if (analysisBucket == null) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<String>();
        for (String token : analysisBucket.split(",")) {
            String normalized = trimToNull(token);
            if (normalized != null) {
                values.add(normalized.toUpperCase(Locale.ROOT));
            }
        }
        return values;
    }

    public Boolean getSummaryOnly() {
        return summaryOnly;
    }

    public Boolean getLargeFileMode() {
        return largeFileMode;
    }

    public Boolean getExpensiveAnnotationSkipped() {
        return expensiveAnnotationSkipped;
    }

    public boolean requiresPostQueryPhenotypeFiltering() {
        return phenotypeGene != null || minPhenotypeScore != null;
    }

    public boolean requiresPostQueryFiltering() {
        // SQL deliberately performs only a broad search prefilter.  The Java predicate is
        // required to preserve exact gene-symbol semantics without making other gene-shaped
        // values (for example rs123, AD or an artifact name) unsearchable.
        return requiresPostQueryPhenotypeFiltering() || search != null;
    }

    public boolean matches(VariantInterpretation interpretation) {
        if (interpretation == null) {
            return false;
        }
        String tierValue = normalizedTier(interpretation);
        Set<String> tierValues = getTierValues();
        if (!tierValues.isEmpty() && !tierValues.contains(tierValue)) {
            return false;
        }
        if (hideFilteredCommon && isFilteredCommonTier(tierValue)) {
            return false;
        }
        if (hideFilteredQc && "FILTERED_QC".equals(tierValue)) {
            return false;
        }
        if ("PLP".equals(clinvarClass) && !hasClinVarPathogenicEvidence(interpretation)) {
            return false;
        }
        if ("VUS".equals(clinvarClass) && !hasClinVarVusEvidence(interpretation)) {
            return false;
        }
        if (!matchesAcmgCode(interpretation)) {
            return false;
        }
        if (acmgStatus != null && !hasAcmgStatus(interpretation, acmgStatus)) {
            return false;
        }
        if (manualReviewRequired != null
                && manualReviewRequired.booleanValue() != hasManualReviewRequired(interpretation)) {
            return false;
        }
        if (hasAcmgSuggestion != null
                && hasAcmgSuggestion.booleanValue() != hasAcmgSuggestion(interpretation)) {
            return false;
        }
        if (hasPhenotypeSupport != null
                && hasPhenotypeSupport.booleanValue() != hasPhenotypeSupport(interpretation)) {
            return false;
        }
        if (phenotypeGene != null && !hasPhenotypeGene(interpretation, phenotypeGene)) {
            return false;
        }
        if (minPhenotypeScore != null && !hasPhenotypeScoreAtLeast(interpretation, minPhenotypeScore)) {
            return false;
        }
        Set<String> requestedBuckets = getAnalysisBucketValues();
        if (!requestedBuckets.isEmpty()) {
            VariantAnalysisBucket bucket = interpretation.getAnalysisBucket();
            String value = bucket == null ? null : bucket.name();
            if (value == null || !requestedBuckets.contains(value)) {
                return false;
            }
        }
        if (summaryOnly != null && summaryOnly.booleanValue() != interpretation.isSummaryOnly()) {
            return false;
        }
        if (largeFileMode != null && largeFileMode.booleanValue() != interpretation.isLargeFileMode()) {
            return false;
        }
        if (expensiveAnnotationSkipped != null
                && expensiveAnnotationSkipped.booleanValue() != interpretation.isExpensiveAnnotationSkipped()) {
            return false;
        }
        return search == null || matchesSearch(interpretation, search);
    }

    public static boolean isFilteredCommonTier(String tier) {
        return "FILTERED_COMMON".equals(tier) || "POPULATION_FILTERED".equals(tier);
    }

    private boolean hasClinVarPathogenicEvidence(VariantInterpretation interpretation) {
        for (EvidenceItem evidence : interpretation.getEvidence()) {
            if (!"CLINVAR".equals(evidence.getSource())) {
                continue;
            }
            String text = evidenceText(evidence);
            if (text.contains("pathogenic") && !text.contains("benign")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasClinVarVusEvidence(VariantInterpretation interpretation) {
        for (EvidenceItem evidence : interpretation.getEvidence()) {
            if (!"CLINVAR".equals(evidence.getSource())) {
                continue;
            }
            String text = evidenceText(evidence);
            if (text.contains("uncertain") || text.contains("vus")) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAcmgCode(VariantInterpretation interpretation) {
        Set<String> requestedCodes = getAcmgCodeValues();
        if (requestedCodes.isEmpty()) {
            return true;
        }
        for (AcmgEvidenceSuggestion suggestion : interpretation.getAcmgEvidenceSuggestions()) {
            String code = trimToNull(suggestion.getCode());
            if (code != null && requestedCodes.contains(code.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAcmgStatus(VariantInterpretation interpretation, String status) {
        for (AcmgEvidenceSuggestion suggestion : interpretation.getAcmgEvidenceSuggestions()) {
            String value = trimToNull(suggestion.getStatus());
            if (value != null && value.toUpperCase(Locale.ROOT).equals(status)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasManualReviewRequired(VariantInterpretation interpretation) {
        for (AcmgEvidenceSuggestion suggestion : interpretation.getAcmgEvidenceSuggestions()) {
            if (suggestion.isManualReviewRequired()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAcmgSuggestion(VariantInterpretation interpretation) {
        return interpretation.getAcmgEvidenceSuggestions() != null
                && !interpretation.getAcmgEvidenceSuggestions().isEmpty();
    }

    private boolean hasPhenotypeSupport(VariantInterpretation interpretation) {
        if (interpretation.getScoreComponents() != null
                && interpretation.getScoreComponents().containsKey(PHENOTYPE_SCORE_COMPONENT)) {
            return true;
        }
        if (interpretation.getScoreBreakdown() != null
                && interpretation.getScoreBreakdown().containsKey(PHENOTYPE_SCORE_COMPONENT)) {
            return true;
        }
        for (EvidenceItem evidence : interpretation.getEvidence()) {
            if (PHENOTYPE_EVIDENCE_TYPE.equals(evidence.getEvidenceType())) {
                return true;
            }
        }
        for (String reason : interpretation.getPositiveReasons()) {
            String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
            if (normalized.contains("phenotype") && normalized.contains("support")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPhenotypeGene(VariantInterpretation interpretation, String gene) {
        if (!hasPhenotypeSupport(interpretation)) {
            return false;
        }
        String normalizedGene = normalizeGene(gene);
        if (normalizedGene == null) {
            return true;
        }
        for (EvidenceItem evidence : interpretation.getEvidence()) {
            if (PHENOTYPE_EVIDENCE_TYPE.equals(evidence.getEvidenceType())
                    && normalizedGene.equals(normalizeGene(evidence.getDetails().get("geneSymbol")))) {
                return true;
            }
        }
        for (TranscriptConsequence consequence : interpretation.getConsequences()) {
            if (normalizedGene.equals(normalizeGene(consequence.getGeneSymbol()))) {
                return true;
            }
        }
        for (TranscriptConsequence consequence : interpretation.getTranscriptAnnotations()) {
            if (normalizedGene.equals(normalizeGene(consequence.getGeneSymbol()))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPhenotypeScoreAtLeast(VariantInterpretation interpretation, Double threshold) {
        if (threshold == null || !hasPhenotypeSupport(interpretation)) {
            return false;
        }
        Double score = phenotypeScore(interpretation);
        return score != null && score.doubleValue() >= threshold.doubleValue();
    }

    private Double phenotypeScore(VariantInterpretation interpretation) {
        for (EvidenceItem evidence : interpretation.getEvidence()) {
            if (!PHENOTYPE_EVIDENCE_TYPE.equals(evidence.getEvidenceType())) {
                continue;
            }
            Double detailScore = numericDetail(evidence, "phenotypeScore");
            if (detailScore != null) {
                return detailScore;
            }
            if (evidence.getRawScore() != null) {
                return evidence.getRawScore();
            }
            detailScore = numericDetail(evidence, "score");
            if (detailScore != null) {
                return detailScore;
            }
        }
        ScoreBreakdownComponent component = interpretation.getScoreComponents() == null
                ? null
                : interpretation.getScoreComponents().get(PHENOTYPE_SCORE_COMPONENT);
        if (component != null) {
            return component.getScore();
        }
        return interpretation.getScoreBreakdown() == null
                ? null
                : interpretation.getScoreBreakdown().get(PHENOTYPE_SCORE_COMPONENT);
    }

    private Double numericDetail(EvidenceItem evidence, String key) {
        if (evidence == null || evidence.getDetails() == null) {
            return null;
        }
        Object value = evidence.getDetails().get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean matchesSearch(VariantInterpretation interpretation, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        boolean geneSymbolSearch = isGeneSymbolSearch(value);
        if (geneSymbolSearch && hasExactGeneSymbol(interpretation, value)) {
            return true;
        }
        return searchableText(interpretation, !geneSymbolSearch).contains(normalized);
    }

    private boolean isGeneSymbolSearch(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.length() > 24) {
            return false;
        }
        if (trimmed.regionMatches(true, 0, "chr", 0, 3)) {
            return false;
        }
        return trimmed.matches("[A-Za-z][A-Za-z0-9.-]*[0-9A-Za-z]?");
    }

    private boolean hasExactGeneSymbol(VariantInterpretation interpretation, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        CandidateIdentity identity = interpretation.getCandidateIdentity();
        if (identity != null
                && identity.getGeneSymbol() != null
                && identity.getGeneSymbol().toLowerCase(Locale.ROOT).equals(normalized)) {
            return true;
        }
        ExomiserCandidateFeatures features = interpretation.getExomiserCandidateFeatures();
        if (features != null
                && features.getGeneSymbol() != null
                && features.getGeneSymbol().toLowerCase(Locale.ROOT).equals(normalized)) {
            return true;
        }
        return hasExactGeneSymbol(interpretation.getConsequences(), normalized)
                || hasExactGeneSymbol(interpretation.getTranscriptAnnotations(), normalized);
    }

    private boolean hasExactGeneSymbol(List<TranscriptConsequence> consequences, String normalizedValue) {
        if (consequences == null || consequences.isEmpty()) {
            return false;
        }
        for (TranscriptConsequence consequence : consequences) {
            if (consequence == null || consequence.getGeneSymbol() == null) {
                continue;
            }
            if (consequence.getGeneSymbol().toLowerCase(Locale.ROOT).equals(normalizedValue)) {
                return true;
            }
        }
        return false;
    }

    private String searchableText(VariantInterpretation interpretation, boolean includeGeneSymbols) {
        StringBuilder builder = new StringBuilder();
        append(builder, interpretation.getVariantId());
        append(builder, interpretation.getPriorityTier());
        append(builder, interpretation.getTierReason());
        append(builder, interpretation.getRankingScore());
        append(builder, interpretation.getAnalysisBucket());
        append(builder, interpretation.isLargeFileMode());
        append(builder, interpretation.isSummaryOnly());
        append(builder, interpretation.isExpensiveAnnotationSkipped());
        append(builder, interpretation.getExpensiveAnnotationSkippedReason());
        appendAll(builder, interpretation.getCheapGateReasons());
        appendAll(builder, interpretation.getPositiveReasons());
        appendAll(builder, interpretation.getNegativeReasons());
        appendAll(builder, interpretation.getFilterReasons());
        appendAll(builder, interpretation.getRationale());
        appendAll(builder, interpretation.getWarnings());
        CandidateIdentity candidateIdentity = interpretation.getCandidateIdentity();
        if (candidateIdentity != null) {
            append(builder, interpretation.getCandidateId());
            append(builder, interpretation.getCanonicalVariantId());
            if (includeGeneSymbols) {
                append(builder, candidateIdentity.getGeneSymbol());
            }
            append(builder, candidateIdentity.getModeOfInheritance());
            append(builder, candidateIdentity.getDeclaredRank());
            append(builder, candidateIdentity.getSource());
            append(builder, candidateIdentity.getSourceOrdinal());
        }
        appendExomiserCandidateFeatures(
                builder,
                interpretation.getExomiserCandidateFeatures(),
                includeGeneSymbols
        );
        VariantQcObservation observation = interpretation.getObservation();
        if (observation != null) {
            NormalizedVariantKey key = observation.getKey();
            if (key != null) {
                append(builder, key.getGenomeAssembly());
                append(builder, key.getChromosome());
                append(builder, key.getPosition());
                append(builder, key.getReference());
                append(builder, key.getAlternate());
            }
            append(builder, observation.getSampleId());
            append(builder, observation.getGenotype());
            append(builder, observation.getFilterStatus());
            append(builder, observation.getVariantType());
        }
        appendAnalysisVcfCall(builder, interpretation.getAnalysisVcfCall());
        for (TranscriptConsequence consequence : interpretation.getConsequences()) {
            appendConsequence(builder, consequence, includeGeneSymbols);
        }
        for (TranscriptConsequence consequence : interpretation.getTranscriptAnnotations()) {
            appendConsequence(builder, consequence, includeGeneSymbols);
        }
        for (EvidenceItem evidence : interpretation.getEvidence()) {
            append(builder, evidence.getSource());
            append(builder, evidence.getSourceVersion());
            append(builder, evidence.getEvidenceType());
            append(builder, evidence.getApplicability());
            append(builder, evidence.getRawScore());
            append(builder, evidence.getLabel());
            append(builder, evidence.getSummary());
            appendDetails(builder, evidence.getDetails(), includeGeneSymbols);
        }
        for (AcmgEvidenceSuggestion suggestion : interpretation.getAcmgEvidenceSuggestions()) {
            append(builder, suggestion.getCode());
            append(builder, suggestion.getStrength());
            append(builder, suggestion.getStatus());
            append(builder, suggestion.getSourceEvidence());
            append(builder, suggestion.getExplanation());
            append(builder, suggestion.isManualReviewRequired());
            append(builder, suggestion.getRuleVersion());
            appendAll(builder, suggestion.getWarnings());
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private void appendAnalysisVcfCall(StringBuilder builder, AnalysisVcfCall call) {
        if (call == null) {
            return;
        }
        append(builder, call.getSchemaVersion());
        append(builder, call.getMatchStatus());
        append(builder, call.getTargetAlleleCall());
        append(builder, call.getCandidateRelativeGenotype());
        append(builder, call.getTargetAlleleIndex());
        append(builder, call.getDepth());
        append(builder, call.getGenotypeQuality());
        append(builder, call.getReferenceDepth());
        append(builder, call.getTargetAlleleDepth());
        append(builder, call.getAlleleBalance());
        append(builder, call.getFilterEvaluation());
        append(builder, call.getPhasingStatus());
        append(builder, call.getPhaseSetToken());
        append(builder, call.getMatchingRecordCount());
        append(builder, call.getMatchedRecordOrdinal());
        append(builder, call.getAssembly());
        append(builder, call.getArtifactRole());
        append(builder, call.getProvenance());
        appendAll(builder, call.getMissingFields());
        appendAll(builder, call.getInvalidFields());
    }

    private void appendExomiserCandidateFeatures(StringBuilder builder,
                                                 ExomiserCandidateFeatures features,
                                                 boolean includeGeneSymbols) {
        if (features == null) {
            return;
        }
        append(builder, features.getEngineVersion());
        if (includeGeneSymbols) {
            append(builder, features.getGeneSymbol());
        }
        append(builder, features.getEntrezGeneId());
        append(builder, features.getRawModeOfInheritance());
        append(builder, features.getNormalizedModeOfInheritance());
        append(builder, features.getGeneMoiGroupRank());
        append(builder, features.getGeneMoiGroupRankSemantics());
        append(builder, features.getGeneCombinedScore());
        append(builder, features.getGenePhenotypeScore());
        append(builder, features.getGeneVariantScore());
        append(builder, features.getVariantScore());
        append(builder, features.getFunctionalClass());
        append(builder, features.getHgvs());
        append(builder, features.getContributingVariant());
        append(builder, features.getSourceRecordId());
        append(builder, features.getVariantSourceOrdinal());
        append(builder, features.getGeneSourceOrdinal());
        append(builder, features.getGeneRowMatchStrategy());
        append(builder, features.getVariantArtifactName());
        append(builder, features.getGeneArtifactName());
        append(builder, features.getRankingScoreSourceColumn());
        if (features.getFieldSources() != null) {
            for (Map.Entry<String, String> entry : features.getFieldSources().entrySet()) {
                append(builder, entry.getKey());
                append(builder, entry.getValue());
            }
        }
        appendAll(builder, features.getMissingFields());
        appendAll(builder, features.getInvalidFields());
    }

    private void appendConsequence(StringBuilder builder,
                                   TranscriptConsequence consequence,
                                   boolean includeGeneSymbols) {
        if (consequence == null) {
            return;
        }
        if (includeGeneSymbols) {
            append(builder, consequence.getGeneSymbol());
        }
        append(builder, consequence.getEntrezGeneId());
        append(builder, consequence.getTranscriptAccession());
        append(builder, consequence.getHgvsCoding());
        append(builder, consequence.getHgvsProtein());
        append(builder, consequence.getConsequence());
    }

    private String evidenceText(EvidenceItem evidence) {
        StringBuilder builder = new StringBuilder();
        append(builder, evidence.getLabel());
        append(builder, evidence.getSummary());
        appendDetails(builder, evidence.getDetails());
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private void appendDetails(StringBuilder builder, Map<String, Object> details) {
        appendDetails(builder, details, true);
    }

    private void appendDetails(StringBuilder builder,
                               Map<String, Object> details,
                               boolean includeGeneSymbols) {
        if (details == null || details.isEmpty()) {
            return;
        }
        List<Object> stack = new ArrayList<Object>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (includeGeneSymbols || !isGeneSymbolKey(entry.getKey())) {
                stack.add(entry.getValue());
            }
        }
        while (!stack.isEmpty()) {
            Object value = stack.remove(stack.size() - 1);
            if (value instanceof Map<?, ?>) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    Object key = entry.getKey();
                    if (includeGeneSymbols || !isGeneSymbolKey(key == null ? null : String.valueOf(key))) {
                        stack.add(entry.getValue());
                    }
                }
            } else if (value instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) value) {
                    stack.add(item);
                }
            } else {
                append(builder, value);
            }
        }
    }

    private boolean isGeneSymbolKey(String key) {
        return key != null && "genesymbol".equals(key.toLowerCase(Locale.ROOT));
    }

    private void appendAll(StringBuilder builder, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            append(builder, value);
        }
    }

    private void append(StringBuilder builder, Object value) {
        if (value != null && !String.valueOf(value).trim().isEmpty()) {
            builder.append(' ').append(value);
        }
    }

    private String normalizedTier(VariantInterpretation interpretation) {
        String value = trimToNull(interpretation.getTier());
        if (value == null) {
            value = trimToNull(interpretation.getPriorityTier());
        }
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static String normalizeClinvarClass(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return "PLP".equals(normalized) || "VUS".equals(normalized) ? normalized : normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String normalizeGene(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
