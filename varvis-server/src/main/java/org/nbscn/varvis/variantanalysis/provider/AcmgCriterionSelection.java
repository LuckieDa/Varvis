package org.nbscn.varvis.variantanalysis.provider;

import java.util.List;

/** Validated request scope, independent of clinical eligibility and historical runs. */
public record AcmgCriterionSelection(List<String> criteria) {
    public AcmgCriterionSelection { criteria = List.copyOf(criteria); }
    public boolean includes(String code) { return criteria.contains(code.startsWith("ACMG_") ? code : "ACMG_AMP_2015:" + code); }
    /** BP7 consumes BP4 internally even when BP4 is not selected for output. */
    public boolean needsFunctionalReferences() {
        return List.of("PP3", "BP4", "BP7", "BP1").stream().anyMatch(this::includes);
    }
    public static List<String> validate(List<String> requested, List<String> available) {
        if (requested == null) return List.copyOf(available);
        if (requested.isEmpty() || requested.size() > available.size() ||
                requested.stream().distinct().count() != requested.size() || !available.containsAll(requested))
            throw new IllegalArgumentException("ACMG_CRITERION_SELECTION_INVALID");
        return available.stream().filter(requested::contains).toList();
    }
}
