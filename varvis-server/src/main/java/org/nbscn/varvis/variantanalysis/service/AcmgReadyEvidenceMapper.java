package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AcmgReadyEvidenceMapper {

    private static final String STATUS_SUGGESTED = "SUGGESTED";
    private static final String STATUS_NOT_SUGGESTED = "NOT_SUGGESTED";
    private static final String STATUS_NOT_EVALUATED = "NOT_EVALUATED";
    private static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";

    private static final String STRENGTH_STANDALONE = "STANDALONE";
    private static final String STRENGTH_VERY_STRONG = "VERY_STRONG";
    private static final String STRENGTH_STRONG = "STRONG";
    private static final String STRENGTH_SUPPORTING = "SUPPORTING";
    private static final String STRENGTH_UNSPECIFIED = "UNSPECIFIED";

    private static final String SOURCE_GNOMAD = "GNOMAD";
    private static final String SOURCE_CLINVAR = "CLINVAR";
    private static final String SOURCE_CADD = "CADD";
    private static final String SOURCE_ALPHAMISSENSE = "ALPHAMISSENSE";
    private static final String SOURCE_SPLICEAI = "SPLICEAI";
    private static final String SOURCE_TRANSCRIPT = "TRANSCRIPT_ANNOTATION";
    private static final String SOURCE_COMPUTATIONAL = "COMPUTATIONAL_PREDICTORS";

    private final VariantAnalysisProperties properties;

    public AcmgReadyEvidenceMapper(VariantAnalysisProperties properties) {
        this.properties = properties;
    }

    public void apply(List<VariantInterpretation> interpretations) {
        if (!properties.getAcmgReady().isEnabled() || interpretations == null) {
            return;
        }
        for (VariantInterpretation interpretation : interpretations) {
            if (shouldSkipLargeFileSummaryOnly(interpretation)) {
                interpretation.applyAcmgEvidenceSuggestions(Collections.emptyList());
                continue;
            }
            interpretation.applyAcmgEvidenceSuggestions(suggestions(interpretation));
        }
    }

    public List<AcmgEvidenceSuggestion> suggestions(VariantInterpretation interpretation) {
        if (interpretation == null || !properties.getAcmgReady().isEnabled()) {
            return Collections.emptyList();
        }
        List<AcmgEvidenceSuggestion> suggestions = new ArrayList<AcmgEvidenceSuggestion>();
        suggestions.add(ba1(interpretation));
        suggestions.add(bs1(interpretation));
        suggestions.add(pm2Candidate(interpretation));
        suggestions.add(pvs1Candidate(interpretation));
        suggestions.add(pp3Candidate(interpretation));
        suggestions.add(bp4Candidate(interpretation));
        suggestions.add(bp7Candidate(interpretation));
        return suggestions;
    }

    private AcmgEvidenceSuggestion ba1(VariantInterpretation interpretation) {
        EvidenceItem gnomad = evidence(interpretation, SOURCE_GNOMAD);
        if (isUnavailable(gnomad)) {
            return suggestion(
                    "BA1",
                    STRENGTH_STANDALONE,
                    STATUS_NOT_EVALUATED,
                    SOURCE_GNOMAD,
                    "gnomAD evidence is unavailable; BA1 cannot be evaluated.",
                    false,
                    unavailableWarning(SOURCE_GNOMAD)
            );
        }
        Double frequency = gnomadFrequency(gnomad);
        if (frequency != null
                && frequency >= properties.getRanking().getVeryCommonAlleleFrequencyThreshold()) {
            List<String> warnings = clinicalConflictWarnings(interpretation);
            return suggestion(
                    "BA1",
                    STRENGTH_STANDALONE,
                    STATUS_SUGGESTED,
                    SOURCE_GNOMAD,
                    "gnomAD maximum allele frequency " + frequency
                            + " meets or exceeds the configured very-common threshold.",
                    !warnings.isEmpty(),
                    warnings
            );
        }
        return suggestion(
                "BA1",
                STRENGTH_STANDALONE,
                STATUS_NOT_SUGGESTED,
                SOURCE_GNOMAD,
                "gnomAD frequency does not meet the configured BA1 candidate threshold.",
                false,
                Collections.emptyList()
        );
    }

    private AcmgEvidenceSuggestion bs1(VariantInterpretation interpretation) {
        EvidenceItem gnomad = evidence(interpretation, SOURCE_GNOMAD);
        if (isUnavailable(gnomad)) {
            return suggestion(
                    "BS1",
                    STRENGTH_STRONG,
                    STATUS_NOT_EVALUATED,
                    SOURCE_GNOMAD,
                    "gnomAD evidence is unavailable; BS1 cannot be evaluated.",
                    false,
                    unavailableWarning(SOURCE_GNOMAD)
            );
        }
        Double frequency = gnomadFrequency(gnomad);
        double commonThreshold = properties.getRanking().getCommonAlleleFrequencyThreshold();
        double veryCommonThreshold = properties.getRanking().getVeryCommonAlleleFrequencyThreshold();
        if (frequency != null && frequency >= commonThreshold && frequency < veryCommonThreshold) {
            List<String> warnings = clinicalConflictWarnings(interpretation);
            return suggestion(
                    "BS1",
                    STRENGTH_STRONG,
                    STATUS_SUGGESTED,
                    SOURCE_GNOMAD,
                    "gnomAD maximum allele frequency " + frequency
                            + " meets the configured common-frequency threshold.",
                    !warnings.isEmpty(),
                    warnings
            );
        }
        return suggestion(
                "BS1",
                STRENGTH_STRONG,
                STATUS_NOT_SUGGESTED,
                SOURCE_GNOMAD,
                "gnomAD frequency does not meet the configured BS1 candidate threshold.",
                false,
                Collections.emptyList()
        );
    }

    private AcmgEvidenceSuggestion pm2Candidate(VariantInterpretation interpretation) {
        EvidenceItem gnomad = evidence(interpretation, SOURCE_GNOMAD);
        if (isUnavailable(gnomad)) {
            return suggestion(
                    "PM2_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_NOT_EVALUATED,
                    SOURCE_GNOMAD,
                    "gnomAD evidence is unavailable; absence/rarity cannot be evaluated.",
                    false,
                    unavailableWarning(SOURCE_GNOMAD)
            );
        }
        if (isNoRecord(gnomad)) {
            return suggestion(
                    "PM2_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_SUGGESTED,
                    SOURCE_GNOMAD,
                    "No allele-matched gnomAD record was found. This is not equivalent to AF=0 and needs review.",
                    true,
                    Collections.singletonList("NO_RECORD is not interpreted as allele frequency zero.")
            );
        }
        Double frequency = gnomadFrequency(gnomad);
        if (frequency != null
                && frequency < properties.getRanking().getCommonAlleleFrequencyThreshold()) {
            return suggestion(
                    "PM2_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_SUGGESTED,
                    SOURCE_GNOMAD,
                    "gnomAD maximum allele frequency " + frequency
                            + " is below the configured rare-disease threshold.",
                    false,
                    Collections.emptyList()
            );
        }
        return suggestion(
                "PM2_candidate",
                STRENGTH_SUPPORTING,
                STATUS_NOT_SUGGESTED,
                SOURCE_GNOMAD,
                "gnomAD frequency does not support a rarity candidate.",
                false,
                Collections.emptyList()
        );
    }

    private AcmgEvidenceSuggestion pvs1Candidate(VariantInterpretation interpretation) {
        if (!hasLofLikeConsequence(interpretation)) {
            return suggestion(
                    "PVS1_candidate",
                    STRENGTH_VERY_STRONG,
                    STATUS_NOT_APPLICABLE,
                    SOURCE_TRANSCRIPT,
                    "No LoF-like consequence was identified.",
                    false,
                    Collections.emptyList()
            );
        }
        String explanation = "A LoF-like consequence was identified; this is a PVS1 candidate only "
                + "and requires transcript, mechanism, and disease-gene review.";
        List<String> warnings = new ArrayList<String>();
        warnings.add("PVS1 is only suggested as a candidate and is not a clinical conclusion.");
        if (hasLofteeHighConfidence(interpretation)) {
            explanation += " LOFTEE high-confidence annotation is present.";
        }
        return suggestion(
                "PVS1_candidate",
                STRENGTH_VERY_STRONG,
                STATUS_SUGGESTED,
                SOURCE_TRANSCRIPT,
                explanation,
                true,
                warnings
        );
    }

    private AcmgEvidenceSuggestion pp3Candidate(VariantInterpretation interpretation) {
        int highPredictors = highPredictorCount(interpretation);
        if (highPredictors >= 2) {
            return suggestion(
                    "PP3_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_SUGGESTED,
                    SOURCE_COMPUTATIONAL,
                    highPredictors + " computational predictors support deleteriousness.",
                    false,
                    Collections.emptyList()
            );
        }
        return suggestion(
                "PP3_candidate",
                STRENGTH_SUPPORTING,
                STATUS_NOT_EVALUATED,
                SOURCE_COMPUTATIONAL,
                "Fewer than two applicable computational predictors support deleteriousness.",
                false,
                Collections.emptyList()
        );
    }

    private AcmgEvidenceSuggestion bp4Candidate(VariantInterpretation interpretation) {
        int highPredictors = highPredictorCount(interpretation);
        int lowPredictors = lowPredictorCount(interpretation);
        if (highPredictors > 0) {
            return suggestion(
                    "BP4_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_NOT_SUGGESTED,
                    SOURCE_COMPUTATIONAL,
                    "At least one applicable computational predictor supports deleteriousness.",
                    false,
                    Collections.emptyList()
            );
        }
        if (lowPredictors >= 2) {
            return suggestion(
                    "BP4_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_SUGGESTED,
                    SOURCE_COMPUTATIONAL,
                    lowPredictors + " computational predictors show a benign-leaning pattern.",
                    false,
                    Collections.emptyList()
            );
        }
        return suggestion(
                "BP4_candidate",
                STRENGTH_SUPPORTING,
                STATUS_NOT_EVALUATED,
                SOURCE_COMPUTATIONAL,
                "Insufficient applicable computational evidence for BP4 candidate support.",
                false,
                Collections.emptyList()
        );
    }

    private AcmgEvidenceSuggestion bp7Candidate(VariantInterpretation interpretation) {
        if (!hasSynonymousConsequence(interpretation)) {
            return suggestion(
                    "BP7_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_NOT_APPLICABLE,
                    SOURCE_SPLICEAI,
                    "The variant is not annotated as synonymous.",
                    false,
                    Collections.emptyList()
            );
        }
        EvidenceItem spliceAi = evidence(interpretation, SOURCE_SPLICEAI);
        if (isUnavailable(spliceAi)) {
            return suggestion(
                    "BP7_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_NOT_EVALUATED,
                    SOURCE_SPLICEAI,
                    "SpliceAI evidence is unavailable; BP7 cannot be evaluated.",
                    false,
                    unavailableWarning(SOURCE_SPLICEAI)
            );
        }
        if (!isApplicable(spliceAi)) {
            return suggestion(
                    "BP7_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_NOT_EVALUATED,
                    SOURCE_SPLICEAI,
                    "Splice impact evidence is not available for this synonymous variant.",
                    false,
                    Collections.emptyList()
            );
        }
        Double score = spliceAi.getRawScore();
        if (score != null && score < properties.getRanking().getSpliceAiHighlightThreshold()) {
            return suggestion(
                    "BP7_candidate",
                    STRENGTH_SUPPORTING,
                    STATUS_SUGGESTED,
                    SOURCE_SPLICEAI,
                    "The variant is synonymous and SpliceAI does not indicate a splice-impact signal.",
                    true,
                    Collections.singletonList("BP7 still requires conservation and transcript-context review.")
            );
        }
        return suggestion(
                "BP7_candidate",
                STRENGTH_SUPPORTING,
                STATUS_NOT_SUGGESTED,
                SOURCE_SPLICEAI,
                "SpliceAI indicates a splice-impact signal or lacks a usable score.",
                false,
                Collections.emptyList()
        );
    }

    private AcmgEvidenceSuggestion suggestion(String code,
                                              String strength,
                                              String status,
                                              String sourceEvidence,
                                              String explanation,
                                              boolean manualReviewRequired,
                                              List<String> warnings) {
        return new AcmgEvidenceSuggestion(
                code,
                strength == null ? STRENGTH_UNSPECIFIED : strength,
                status,
                sourceEvidence,
                explanation,
                manualReviewRequired,
                properties.getAcmgReady().getRuleVersion(),
                warnings
        );
    }

    private EvidenceItem evidence(VariantInterpretation interpretation, String source) {
        if (interpretation == null || source == null) {
            return null;
        }
        for (EvidenceItem item : interpretation.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        return null;
    }

    private Double gnomadFrequency(EvidenceItem evidence) {
        if (!isApplicable(evidence)) {
            return null;
        }
        Double score = evidence.getRawScore();
        if (score != null) {
            return score;
        }
        Double popmax = doubleDetail(evidence, "AF_popmax");
        if (popmax == null) {
            popmax = doubleDetail(evidence, "populationMaximumAlleleFrequency");
        }
        if (popmax == null) {
            popmax = doubleDetail(evidence, "maximumAlleleFrequency");
        }
        if (popmax != null) {
            return popmax;
        }
        Double frequency = doubleDetail(evidence, "AF");
        if (frequency == null) {
            frequency = doubleDetail(evidence, "alleleFrequency");
        }
        return frequency;
    }

    private int highPredictorCount(VariantInterpretation interpretation) {
        int count = 0;
        EvidenceItem cadd = evidence(interpretation, SOURCE_CADD);
        if (isApplicable(cadd) && cadd.getRawScore() != null
                && cadd.getRawScore() >= properties.getRanking().getCaddHighlightThreshold()) {
            count++;
        }
        EvidenceItem alphaMissense = evidence(interpretation, SOURCE_ALPHAMISSENSE);
        if (isApplicable(alphaMissense) && alphaMissense.getRawScore() != null
                && alphaMissense.getRawScore() >= properties.getRanking().getAlphaMissenseHighlightThreshold()) {
            count++;
        }
        EvidenceItem spliceAi = evidence(interpretation, SOURCE_SPLICEAI);
        if (isApplicable(spliceAi) && spliceAi.getRawScore() != null
                && spliceAi.getRawScore() >= properties.getRanking().getSpliceAiHighlightThreshold()) {
            count++;
        }
        return count;
    }

    private int lowPredictorCount(VariantInterpretation interpretation) {
        int count = 0;
        EvidenceItem cadd = evidence(interpretation, SOURCE_CADD);
        if (isApplicable(cadd) && cadd.getRawScore() != null
                && cadd.getRawScore() < properties.getRanking().getCaddReviewThreshold()) {
            count++;
        }
        EvidenceItem alphaMissense = evidence(interpretation, SOURCE_ALPHAMISSENSE);
        if (isApplicable(alphaMissense) && alphaMissense.getRawScore() != null
                && alphaMissense.getRawScore() < properties.getRanking().getAlphaMissenseHighlightThreshold()) {
            count++;
        }
        EvidenceItem spliceAi = evidence(interpretation, SOURCE_SPLICEAI);
        if (isApplicable(spliceAi) && spliceAi.getRawScore() != null
                && spliceAi.getRawScore() < properties.getRanking().getSpliceAiHighlightThreshold()) {
            count++;
        }
        return count;
    }

    private boolean hasLofLikeConsequence(VariantInterpretation interpretation) {
        return containsAnyConsequence(
                interpretation,
                "stop_gained",
                "frameshift_variant",
                "splice_donor_variant",
                "splice_acceptor_variant"
        );
    }

    private boolean hasSynonymousConsequence(VariantInterpretation interpretation) {
        return containsAnyConsequence(interpretation, "synonymous_variant", "synonymous");
    }

    private boolean containsAnyConsequence(VariantInterpretation interpretation, String... terms) {
        if (interpretation == null || terms == null || terms.length == 0) {
            return false;
        }
        return containsAnyConsequence(interpretation.getConsequences(), terms)
                || containsAnyConsequence(interpretation.getTranscriptAnnotations(), terms);
    }

    private boolean containsAnyConsequence(List<TranscriptConsequence> consequences, String... terms) {
        if (consequences == null || consequences.isEmpty()) {
            return false;
        }
        for (TranscriptConsequence consequence : consequences) {
            if (consequence == null || consequence.getConsequence() == null) {
                continue;
            }
            String value = consequence.getConsequence().toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (term != null && value.contains(term.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasLofteeHighConfidence(VariantInterpretation interpretation) {
        return hasLofteeHighConfidence(interpretation.getConsequences())
                || hasLofteeHighConfidence(interpretation.getTranscriptAnnotations());
    }

    private boolean hasLofteeHighConfidence(List<TranscriptConsequence> consequences) {
        if (consequences == null || consequences.isEmpty()) {
            return false;
        }
        for (TranscriptConsequence consequence : consequences) {
            if (consequence != null && "HC".equalsIgnoreCase(consequence.getLof())) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSkipLargeFileSummaryOnly(VariantInterpretation interpretation) {
        return interpretation != null
                && interpretation.isLargeFileMode()
                && (interpretation.isSummaryOnly()
                        || interpretation.isExpensiveAnnotationSkipped()
                        || !interpretation.isFullInterpretationAvailable());
    }

    private List<String> clinicalConflictWarnings(VariantInterpretation interpretation) {
        Set<String> warnings = new LinkedHashSet<String>();
        EvidenceItem clinVar = evidence(interpretation, SOURCE_CLINVAR);
        if (clinVar == null) {
            return Collections.emptyList();
        }
        String text = lower(clinVar.getApplicability())
                + " " + lower(clinVar.getLabel())
                + " " + lower(clinVar.getSummary())
                + " " + lower(String.valueOf(clinVar.getDetails()));
        if (text.contains("conflict")) {
            warnings.add("ClinVar evidence is conflicting and requires manual review.");
        }
        if (text.contains("pathogenic")) {
            warnings.add("ClinVar pathogenic/likely pathogenic evidence conflicts with high population frequency.");
        }
        return new ArrayList<String>(warnings);
    }

    private List<String> unavailableWarning(String source) {
        return Collections.singletonList(source + " is unavailable; absence is not evidence of low risk.");
    }

    private boolean isApplicable(EvidenceItem evidence) {
        return evidence != null && "APPLICABLE".equals(evidence.getApplicability());
    }

    private boolean isUnavailable(EvidenceItem evidence) {
        return evidence == null || (!isApplicable(evidence) && !isNoRecord(evidence));
    }

    private boolean isNoRecord(EvidenceItem evidence) {
        return evidence != null && "NO_RECORD".equals(evidence.getApplicability());
    }

    private Double doubleDetail(EvidenceItem evidence, String key) {
        if (evidence == null || key == null) {
            return null;
        }
        Object value = evidence.getDetails().get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.valueOf(((String) value).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
