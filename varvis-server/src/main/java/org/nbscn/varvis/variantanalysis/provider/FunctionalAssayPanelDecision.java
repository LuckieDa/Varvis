package org.nbscn.varvis.variantanalysis.provider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.lang.Nullable;

/**
 * Pure decision operator for a reviewed primary assay and a complete secondary assay panel.
 * The two implemented branches follow GN009 v2.4 PS3 and its functional flowchart.
 * This class neither validates scientific source truth nor produces an ACMG evaluation.
 * Callers must bind each observation, eligibility review and independent assay group to
 * the same verified variant context before invoking it. It has no provider or I/O path.
 *
 * <p>Source: https://cspec.genome.network/cspec/ui/svi/doc/GN009?version=2.4
 * No gene list, experimental cutoff, inferred missing result or automatic BS3 is embedded.
 */
final class FunctionalAssayPanelDecision {
    static final String VERSION = "1.0.0";
    private FunctionalAssayPanelDecision() { }

    enum PrimaryClass { NON_FUNCTIONAL, PARTIALLY_FUNCTIONAL, FUNCTIONAL, UNAVAILABLE }
    enum SecondaryClass { LOF, NO_LOF, REVIEWED_NO_DATA, UNRESOLVED }
    enum ReviewState { CLEAR, PRESENT, UNKNOWN }
    enum Pvs1State { NOT_APPLIED, SUPPORTING, MODERATE, STRONG, VERY_STRONG, APPLIED_FOR_SPLICING, UNKNOWN }
    enum Strength { NONE, MODERATE, STRONG }

    record AssayIdentity(String assayId, String independentEvidenceGroupId) {
        AssayIdentity {
            Objects.requireNonNull(assayId);
            Objects.requireNonNull(independentEvidenceGroupId);
        }
    }

    record Observation(AssayIdentity assay, SecondaryClass result) {
        Observation {
            Objects.requireNonNull(assay);
            Objects.requireNonNull(result);
        }
    }

    /** These are explicit reviewed states, never defaults inferred from absent evidence. */
    record Eligibility(ReviewState abnormalRna, ReviewState splicePp3Applied, Pvs1State pvs1) {
        Eligibility {
            Objects.requireNonNull(abnormalRna);
            Objects.requireNonNull(splicePp3Applied);
            Objects.requireNonNull(pvs1);
        }
    }

    record Panel(AssayIdentity primaryAssay, PrimaryClass primaryClass,
                 List<AssayIdentity> expectedSecondaryAssays, List<Observation> observations,
                 Eligibility eligibility) {
        Panel {
            Objects.requireNonNull(primaryAssay);
            Objects.requireNonNull(primaryClass);
            expectedSecondaryAssays = List.copyOf(expectedSecondaryAssays);
            observations = List.copyOf(observations);
            Objects.requireNonNull(eligibility);
        }
    }

    /** available is the complete reviewed LOF + NO_LOF denominator; missing stays separate. */
    record Decision(boolean supportedBranch, Strength strength, String reason,
                    int lof, int noLof, int reviewedNoData, int unresolved) {
        int available() { return lof + noLof; }
    }

    static Decision decide(Panel panel) {
        Objects.requireNonNull(panel);
        AssayIdentity primary = panel.primaryAssay();
        if (!identityValid(primary)) return refused("PRIMARY_ASSAY_IDENTITY_INVALID");
        Map<String, AssayIdentity> expected = new HashMap<>();
        Set<String> groups = new HashSet<>();
        groups.add(primary.independentEvidenceGroupId());
        for (AssayIdentity identity : panel.expectedSecondaryAssays()) {
            if (!identityValid(identity) || primary.assayId().equals(identity.assayId())
                    || expected.putIfAbsent(identity.assayId(), identity) != null
                    || !groups.add(identity.independentEvidenceGroupId()))
                return refused("ASSAY_PANEL_IDENTITY_OR_INDEPENDENCE_INVALID");
        }
        Set<String> seen = new HashSet<>();
        int lof = 0, noLof = 0, missing = 0, unresolved = 0;
        for (Observation observation : panel.observations()) {
            AssayIdentity identity = observation.assay();
            if (!identity.equals(expected.get(identity.assayId())) || !seen.add(identity.assayId()))
                return refused("ASSAY_OBSERVATION_IDENTITY_OR_DUPLICATE_INVALID");
            switch (observation.result()) {
                case LOF -> lof++;
                case NO_LOF -> noLof++;
                case REVIEWED_NO_DATA -> missing++;
                case UNRESOLVED -> unresolved++;
            }
        }
        if (!seen.equals(expected.keySet())) return refused("ASSAY_PANEL_RECORDS_INCOMPLETE");
        String reason = eligibilityIssue(panel.eligibility());
        if (reason == null && unresolved != 0) reason = "ASSAY_PANEL_UNRESOLVED_OBSERVATION";
        if (reason == null && panel.primaryClass() == PrimaryClass.UNAVAILABLE) reason = "PRIMARY_ASSAY_DATA_MISSING";
        if (reason == null && panel.primaryClass() == PrimaryClass.FUNCTIONAL) reason = "PRIMARY_CLASS_BRANCH_NOT_IMPLEMENTED";
        if (reason == null && lof + noLof == 0) reason = "ASSAY_PANEL_NO_AVAILABLE_SECONDARY_ASSAYS";
        if (reason == null && lof == noLof) reason = "ASSAY_PANEL_TIE_NOT_RESOLVED_BY_SOURCE";
        if (reason == null && lof < noLof) reason = "SECONDARY_MAJORITY_LOF_BRANCH_NOT_MET_OTHER_BRANCHES_UNRESOLVED";
        if (reason != null) return new Decision(false, Strength.NONE, reason, lof, noLof, missing, unresolved);
        Strength strength = panel.primaryClass() == PrimaryClass.NON_FUNCTIONAL ? Strength.STRONG : Strength.MODERATE;
        if (panel.eligibility().pvs1() == Pvs1State.STRONG) strength = Strength.MODERATE;
        return new Decision(true, strength, "PRIMARY_CLASS_AND_SECONDARY_MAJORITY_LOF", lof, noLof, missing, unresolved);
    }

    private static boolean identityValid(AssayIdentity identity) {
        return !identity.assayId().isBlank() && !identity.independentEvidenceGroupId().isBlank();
    }

    @Nullable private static String eligibilityIssue(Eligibility eligibility) {
        if (eligibility.abnormalRna() == ReviewState.PRESENT) return "ABNORMAL_RNA_REQUIRES_SPLICE_MECHANISM_REVIEW";
        if (eligibility.splicePp3Applied() == ReviewState.PRESENT) return "SPLICE_PP3_EXCLUDES_PROTEIN_FUNCTIONAL_CODE";
        if (eligibility.pvs1() == Pvs1State.APPLIED_FOR_SPLICING) return "SPLICING_PVS1_EXCLUDES_PROTEIN_FUNCTIONAL_CODE";
        if (eligibility.pvs1() == Pvs1State.VERY_STRONG) return "FULL_STRENGTH_PVS1_EXCLUDES_FUNCTIONAL_CODE";
        if (eligibility.abnormalRna() == ReviewState.UNKNOWN || eligibility.splicePp3Applied() == ReviewState.UNKNOWN
                || eligibility.pvs1() == Pvs1State.UNKNOWN) return "FUNCTIONAL_DEPENDENCY_REVIEW_INCOMPLETE";
        return null;
    }

    private static Decision refused(String reason) {
        return new Decision(false, Strength.NONE, reason, 0, 0, 0, 0);
    }
}
