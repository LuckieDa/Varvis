package org.nbscn.varvis.variantanalysis.service.llmshadow;

import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts persisted ranking summaries into the only data shape providers may receive. */
public final class LlmShadowInputAssembler {

    private static final Set<String> ALLOWED_COMPONENTS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "clinical_evidence", "population_frequency", "predicted_consequence",
                    "computational_prediction", "splice_prediction", "qc_quality",
                    "genotype_support", "conflict_penalty", "phenotype_support",
                    "trio_segregation_support")));

    public LlmShadowInput assemble(String schemaVersion, List<VariantInterpretation> variants) {
        List<LlmShadowInput.Candidate> candidates = new ArrayList<LlmShadowInput.Candidate>();
        if (variants == null) {
            return new LlmShadowInput(schemaVersion, candidates);
        }
        int ordinal = 0;
        for (VariantInterpretation variant : variants) {
            if (variant == null) continue;
            ordinal++;
            List<LlmShadowInput.Component> components = components(variant);
            String completeness = completeness(variant, components);
            String availability = components.isEmpty()
                    ? "PERSISTED_COMPONENTS_UNAVAILABLE"
                    : "PERSISTED_COMPONENTS_AVAILABLE";
            candidates.add(new LlmShadowInput.Candidate(ordinal, completeness, availability, components));
        }
        return new LlmShadowInput(schemaVersion, candidates);
    }

    private List<LlmShadowInput.Component> components(VariantInterpretation variant) {
        List<LlmShadowInput.Component> result = new ArrayList<LlmShadowInput.Component>();
        Map<String, ScoreBreakdownComponent> detailed = variant.getScoreComponents();
        if (detailed != null && !detailed.isEmpty()) {
            for (String key : ALLOWED_COMPONENTS) {
                ScoreBreakdownComponent component = detailed.get(key);
                add(result, key, component == null ? null : component.getScore());
            }
            return result;
        }
        Map<String, Double> legacy = variant.getScoreBreakdown();
        if (legacy != null) {
            for (String key : ALLOWED_COMPONENTS) {
                add(result, key, legacy.get(key));
            }
        }
        return result;
    }

    private void add(List<LlmShadowInput.Component> result, String key, Double score) {
        if (score == null || !Double.isFinite(score.doubleValue())) return;
        String direction = score.doubleValue() > 0.0d ? "POSITIVE"
                : score.doubleValue() < 0.0d ? "NEGATIVE" : "NEUTRAL";
        result.add(new LlmShadowInput.Component(key, score.doubleValue(), direction));
    }

    private String completeness(VariantInterpretation variant,
                                List<LlmShadowInput.Component> components) {
        if (variant.isSummaryOnly() || !variant.isFullInterpretationAvailable()) return "SUMMARY_ONLY";
        if (components.isEmpty()) return "UNAVAILABLE";
        return variant.getScoreComponents() != null && !variant.getScoreComponents().isEmpty()
                ? "COMPLETE" : "LEGACY_PARTIAL";
    }
}
