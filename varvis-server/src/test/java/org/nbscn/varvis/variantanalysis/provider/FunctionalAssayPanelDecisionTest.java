package org.nbscn.varvis.variantanalysis.provider;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.FunctionalAssayPanelDecision.*;

/** Synthetic categorical controls, derived from the official decision branches, not held-out assay rows. */
class FunctionalAssayPanelDecisionTest {
    private static final AssayIdentity PRIMARY = new AssayIdentity("primary", "independent-primary");
    private static final Eligibility CLEAR = new Eligibility(ReviewState.CLEAR, ReviewState.CLEAR, Pvs1State.NOT_APPLIED);

    @Test void supportedSourceBranchesUseSecondaryMajorityAndPreserveDissent() {
        Decision strong = decide(panel(PrimaryClass.NON_FUNCTIONAL, CLEAR, SecondaryClass.LOF, SecondaryClass.LOF, SecondaryClass.NO_LOF));
        assertTrue(strong.supportedBranch());
        assertEquals(Strength.STRONG, strong.strength());
        assertEquals(3, strong.available());
        assertEquals(1, strong.noLof());
        Decision moderate = decide(panel(PrimaryClass.PARTIALLY_FUNCTIONAL, CLEAR, SecondaryClass.LOF, SecondaryClass.LOF, SecondaryClass.NO_LOF));
        assertTrue(moderate.supportedBranch());
        assertEquals(Strength.MODERATE, moderate.strength());
    }

    @Test void primaryIsNeverAnExtraVoteThatBreaksASecondaryTie() {
        Decision tie = decide(panel(PrimaryClass.NON_FUNCTIONAL, CLEAR, SecondaryClass.LOF, SecondaryClass.NO_LOF));
        assertFalse(tie.supportedBranch());
        assertEquals("ASSAY_PANEL_TIE_NOT_RESOLVED_BY_SOURCE", tie.reason());
        assertEquals(2, tie.available());
    }

    @Test void ReviewedNoDataIsNeitherNormalNorAnUnexplainedOmission() {
        Panel full = panel(PrimaryClass.NON_FUNCTIONAL, CLEAR, SecondaryClass.LOF, SecondaryClass.REVIEWED_NO_DATA);
        Decision knownMissing = decide(full);
        assertTrue(knownMissing.supportedBranch());
        assertEquals(1, knownMissing.available());
        assertEquals(1, knownMissing.reviewedNoData());
        assertEquals(0, knownMissing.noLof());
        assertEquals("ASSAY_PANEL_RECORDS_INCOMPLETE", decide(new Panel(PRIMARY, full.primaryClass(), full.expectedSecondaryAssays(),
                List.of(full.observations().get(0)), CLEAR)).reason());
        Decision unresolved = decide(panel(PrimaryClass.NON_FUNCTIONAL, CLEAR, SecondaryClass.LOF, SecondaryClass.UNRESOLVED));
        assertFalse(unresolved.supportedBranch());
        assertEquals(1, unresolved.unresolved());
    }

    @Test void zeroAvailableOrUnavailablePrimaryCannotSelectAFavorableFallback() {
        assertEquals("ASSAY_PANEL_NO_AVAILABLE_SECONDARY_ASSAYS", decide(panel(PrimaryClass.NON_FUNCTIONAL, CLEAR,
                SecondaryClass.REVIEWED_NO_DATA)).reason());
        assertEquals("PRIMARY_ASSAY_DATA_MISSING", decide(panel(PrimaryClass.UNAVAILABLE, CLEAR, SecondaryClass.LOF)).reason());
        assertFalse(decide(panel(PrimaryClass.FUNCTIONAL, CLEAR, SecondaryClass.NO_LOF)).supportedBranch());
        assertFalse(decide(panel(PrimaryClass.NON_FUNCTIONAL, CLEAR, SecondaryClass.NO_LOF, SecondaryClass.NO_LOF)).supportedBranch());
    }

    @Test void repeatedAssayOrCorrelatedStudyCannotCreateMajority() {
        Panel good = panel(PrimaryClass.NON_FUNCTIONAL, CLEAR, SecondaryClass.LOF, SecondaryClass.NO_LOF);
        List<Observation> duplicate = new ArrayList<>(good.observations());
        duplicate.add(good.observations().get(0));
        assertEquals("ASSAY_OBSERVATION_IDENTITY_OR_DUPLICATE_INVALID", decide(new Panel(PRIMARY, good.primaryClass(),
                good.expectedSecondaryAssays(), duplicate, CLEAR)).reason());
        AssayIdentity sameSource = new AssayIdentity("secondary-2", "independent-primary");
        assertEquals("ASSAY_PANEL_IDENTITY_OR_INDEPENDENCE_INVALID", decide(new Panel(PRIMARY, good.primaryClass(),
                List.of(sameSource), List.of(new Observation(sameSource, SecondaryClass.LOF)), CLEAR)).reason());
        assertEquals("ASSAY_PANEL_IDENTITY_OR_INDEPENDENCE_INVALID", decide(new Panel(PRIMARY, good.primaryClass(),
                List.of(PRIMARY), List.of(new Observation(PRIMARY, SecondaryClass.LOF)), CLEAR)).reason());
    }

    @Test void eligibilityIsExplicitAndStrongPvs1CapsTheOutput() {
        for (Eligibility eligibility : List.of(
                new Eligibility(ReviewState.PRESENT, ReviewState.CLEAR, Pvs1State.NOT_APPLIED),
                new Eligibility(ReviewState.CLEAR, ReviewState.PRESENT, Pvs1State.NOT_APPLIED),
                new Eligibility(ReviewState.CLEAR, ReviewState.CLEAR, Pvs1State.APPLIED_FOR_SPLICING),
                new Eligibility(ReviewState.CLEAR, ReviewState.CLEAR, Pvs1State.VERY_STRONG),
                new Eligibility(ReviewState.UNKNOWN, ReviewState.CLEAR, Pvs1State.NOT_APPLIED),
                new Eligibility(ReviewState.CLEAR, ReviewState.UNKNOWN, Pvs1State.NOT_APPLIED),
                new Eligibility(ReviewState.CLEAR, ReviewState.CLEAR, Pvs1State.UNKNOWN))) {
            Decision blocked = decide(panel(PrimaryClass.NON_FUNCTIONAL, eligibility, SecondaryClass.LOF));
            assertFalse(blocked.supportedBranch());
            assertEquals(Strength.NONE, blocked.strength());
            assertEquals(1, blocked.lof());
        }
        Eligibility strongPvs1 = new Eligibility(ReviewState.CLEAR, ReviewState.CLEAR, Pvs1State.STRONG);
        assertEquals(Strength.MODERATE, decide(panel(PrimaryClass.NON_FUNCTIONAL, strongPvs1, SecondaryClass.LOF)).strength());
        assertEquals(Strength.MODERATE, decide(panel(PrimaryClass.PARTIALLY_FUNCTIONAL, strongPvs1, SecondaryClass.LOF)).strength());
    }

    @Test void distinctSecondaryLabelsCannotHideOneUnderlyingStudy() {
        AssayIdentity first = new AssayIdentity("original", "one-secondary-study");
        AssayIdentity reprocessed = new AssayIdentity("reprocessed", "one-secondary-study");
        Decision duplicateSource = decide(new Panel(PRIMARY, PrimaryClass.NON_FUNCTIONAL, List.of(first, reprocessed),
                List.of(new Observation(first, SecondaryClass.LOF), new Observation(reprocessed, SecondaryClass.LOF)), CLEAR));
        assertEquals("ASSAY_PANEL_IDENTITY_OR_INDEPENDENCE_INVALID", duplicateSource.reason());
    }

    @Test void observedAssayCannotSubstituteAnotherEvidenceGroup() {
        AssayIdentity expected = new AssayIdentity("original", "reviewed-study");
        AssayIdentity substituted = new AssayIdentity("original", "another-study");
        Decision wrongSource = decide(new Panel(PRIMARY, PrimaryClass.NON_FUNCTIONAL, List.of(expected),
                List.of(new Observation(substituted, SecondaryClass.LOF)), CLEAR));
        assertEquals("ASSAY_OBSERVATION_IDENTITY_OR_DUPLICATE_INVALID", wrongSource.reason());
    }

    @Test void panelCopiesCallerListsAndNeverReturnsBs3OrAnAcmgEvaluation() {
        List<AssayIdentity> ids = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        AssayIdentity one = new AssayIdentity("one", "one-study");
        ids.add(one); observations.add(new Observation(one, SecondaryClass.LOF));
        Panel snapshot = new Panel(PRIMARY, PrimaryClass.NON_FUNCTIONAL, ids, observations, CLEAR);
        ids.clear(); observations.clear();
        assertTrue(decide(snapshot).supportedBranch());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.observations().clear());
        assertThrows(NullPointerException.class, () -> new Eligibility(null, ReviewState.CLEAR, Pvs1State.NOT_APPLIED));
    }

    private static Panel panel(PrimaryClass primaryClass, Eligibility eligibility, SecondaryClass... outcomes) {
        List<AssayIdentity> ids = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < outcomes.length; i++) {
            AssayIdentity identity = new AssayIdentity("secondary-" + i, "independent-secondary-" + i);
            ids.add(identity); observations.add(new Observation(identity, outcomes[i]));
        }
        return new Panel(PRIMARY, primaryClass, ids, observations, eligibility);
    }
}
