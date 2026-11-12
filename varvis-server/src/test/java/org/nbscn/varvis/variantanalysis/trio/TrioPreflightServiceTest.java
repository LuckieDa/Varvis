package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioPreflightServiceTest {

    private final TrioPreflightService service = new TrioPreflightService();

    @Test
    void acceptsCompleteSyntheticTrioInput() {
        TrioPreflightReport report = service.preflight(
                TrioInputMode.SYNTHETIC_OBSERVATIONS,
                input(variant("v1",
                        TrioGenotypeObservation.het(TrioRole.PROBAND),
                        TrioGenotypeObservation.homRef(TrioRole.FATHER),
                        TrioGenotypeObservation.homRef(TrioRole.MOTHER))));

        assertTrue(report.isValid());
        assertEquals(3, report.getMemberCount());
        assertEquals(1, report.getVariantCount());
        assertTrue(report.getIssues().isEmpty());
    }

    @Test
    void rejectsMissingRequiredMemberRole() {
        TrioAnalysisInput input = new TrioAnalysisInput(
                Arrays.asList(
                        new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                        new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false)),
                Arrays.asList(variant("v1",
                        TrioGenotypeObservation.het(TrioRole.PROBAND),
                        TrioGenotypeObservation.homRef(TrioRole.FATHER),
                        TrioGenotypeObservation.homRef(TrioRole.MOTHER))));

        TrioPreflightReport report = service.preflight(TrioInputMode.SYNTHETIC_OBSERVATIONS, input);

        assertFalse(report.isValid());
        assertTrue(hasIssue(report, "TRIO_FATHER_MISSING"));
    }

    @Test
    void warnsButDoesNotFailOnUninformativeParentGenotype() {
        TrioPreflightReport report = service.preflight(
                TrioInputMode.SYNTHETIC_OBSERVATIONS,
                input(variant("v1",
                        TrioGenotypeObservation.het(TrioRole.PROBAND),
                        TrioGenotypeObservation.unobserved(TrioRole.FATHER),
                        TrioGenotypeObservation.homRef(TrioRole.MOTHER))));

        assertTrue(report.isValid());
        assertTrue(hasIssue(report, "TRIO_PARENT_GENOTYPE_UNINFORMATIVE"));
        assertEquals(TrioPreflightSeverity.WARNING, issue(report, "TRIO_PARENT_GENOTYPE_UNINFORMATIVE").getSeverity());
    }

    private TrioAnalysisInput input(TrioVariantObservation... variants) {
        List<TrioMember> members = Arrays.asList(
                new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false));
        return new TrioAnalysisInput(members, Arrays.asList(variants));
    }

    private TrioVariantObservation variant(String variantId,
                                           TrioGenotypeObservation proband,
                                           TrioGenotypeObservation father,
                                           TrioGenotypeObservation mother) {
        Map<TrioRole, TrioGenotypeObservation> genotypes =
                new EnumMap<TrioRole, TrioGenotypeObservation>(TrioRole.class);
        genotypes.put(TrioRole.PROBAND, proband);
        genotypes.put(TrioRole.FATHER, father);
        genotypes.put(TrioRole.MOTHER, mother);
        return new TrioVariantObservation(variantId, "GENE1", "chr1", 100L, genotypes);
    }

    private boolean hasIssue(TrioPreflightReport report, String code) {
        return issue(report, code) != null;
    }

    private TrioPreflightIssue issue(TrioPreflightReport report, String code) {
        for (TrioPreflightIssue issue : report.getIssues()) {
            if (code.equals(issue.getCode())) {
                return issue;
            }
        }
        return null;
    }
}
