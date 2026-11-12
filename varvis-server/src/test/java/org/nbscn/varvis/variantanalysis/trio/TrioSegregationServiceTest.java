package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioSegregationServiceTest {

    private final TrioSegregationService service = new TrioSegregationService();

    @Test
    void marksDeNovoOnlyWhenBothParentsAreConfirmedReference() {
        TrioVariantObservation variant = variant("v1", "MECP2", "chr1",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.homRef(TrioRole.FATHER),
                TrioGenotypeObservation.homRef(TrioRole.MOTHER));

        SegregationEvidence evidence = evidence(service.evaluate(input(TrioSex.FEMALE, variant)), "v1");

        assertEquals(InheritanceModelCandidate.DE_NOVO_CANDIDATE, evidence.getModel());
        assertEquals(SegregationConfidence.SUPPORTED, evidence.getConfidence());
        assertEquals(ParentalOrigin.NONE, evidence.getParentalOrigin());
    }

    @Test
    void doesNotTreatUnobservedParentAsReferenceForDeNovo() {
        TrioVariantObservation variant = variant("v1", "MECP2", "chr1",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.unobserved(TrioRole.FATHER),
                TrioGenotypeObservation.homRef(TrioRole.MOTHER));

        SegregationEvidence evidence = evidence(service.evaluate(input(TrioSex.FEMALE, variant)), "v1");

        assertEquals(InheritanceModelCandidate.UNINFORMATIVE, evidence.getModel());
        assertFalse(evidence.getWarnings().isEmpty());
        assertTrue(evidence.getWarnings().get(0).contains("not treated as confirmed 0/0"));
    }

    @Test
    void marksCompoundHeterozygousPairWithComplementaryParentalOrigin() {
        TrioVariantObservation paternal = variant("ppa2-pat", "PPA2", "chr4",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.het(TrioRole.FATHER),
                TrioGenotypeObservation.homRef(TrioRole.MOTHER));
        TrioVariantObservation maternal = variant("ppa2-mat", "PPA2", "chr4",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.homRef(TrioRole.FATHER),
                TrioGenotypeObservation.het(TrioRole.MOTHER));

        List<SegregationEvidence> result = service.evaluate(input(TrioSex.FEMALE, paternal, maternal));
        SegregationEvidence paternalEvidence = evidence(result, "ppa2-pat");
        SegregationEvidence maternalEvidence = evidence(result, "ppa2-mat");

        assertEquals(InheritanceModelCandidate.COMPOUND_HETEROZYGOUS_CANDIDATE, paternalEvidence.getModel());
        assertEquals(ParentalOrigin.PATERNAL, paternalEvidence.getParentalOrigin());
        assertEquals(Arrays.asList("ppa2-mat"), paternalEvidence.getPartnerVariantIds());
        assertEquals(InheritanceModelCandidate.COMPOUND_HETEROZYGOUS_CANDIDATE, maternalEvidence.getModel());
        assertEquals(ParentalOrigin.MATERNAL, maternalEvidence.getParentalOrigin());
        assertEquals(Arrays.asList("ppa2-pat"), maternalEvidence.getPartnerVariantIds());
    }

    @Test
    void doesNotMarkCompoundHetWhenBothVariantsComeFromSameParent() {
        TrioVariantObservation first = variant("v1", "PPA2", "chr4",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.het(TrioRole.FATHER),
                TrioGenotypeObservation.homRef(TrioRole.MOTHER));
        TrioVariantObservation second = variant("v2", "PPA2", "chr4",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.het(TrioRole.FATHER),
                TrioGenotypeObservation.homRef(TrioRole.MOTHER));

        List<SegregationEvidence> result = service.evaluate(input(TrioSex.FEMALE, first, second));

        assertEquals(InheritanceModelCandidate.INHERITED_CANDIDATE, evidence(result, "v1").getModel());
        assertEquals(InheritanceModelCandidate.INHERITED_CANDIDATE, evidence(result, "v2").getModel());
    }

    @Test
    void doesNotUseUnobservedParentAsReferenceForCompoundHetOrigin() {
        TrioVariantObservation uncertainPaternal = variant("v1", "PPA2", "chr4",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.het(TrioRole.FATHER),
                TrioGenotypeObservation.unobserved(TrioRole.MOTHER));
        TrioVariantObservation maternal = variant("v2", "PPA2", "chr4",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.homRef(TrioRole.FATHER),
                TrioGenotypeObservation.het(TrioRole.MOTHER));

        List<SegregationEvidence> result = service.evaluate(input(TrioSex.FEMALE, uncertainPaternal, maternal));

        assertEquals(InheritanceModelCandidate.INHERITED_CANDIDATE, evidence(result, "v1").getModel());
        assertEquals(InheritanceModelCandidate.INHERITED_CANDIDATE, evidence(result, "v2").getModel());
    }

    @Test
    void marksHomozygousRecessiveWhenBothParentsAreCarriers() {
        TrioVariantObservation variant = variant("v1", "GJB2", "chr13",
                TrioGenotypeObservation.homAlt(TrioRole.PROBAND),
                TrioGenotypeObservation.het(TrioRole.FATHER),
                TrioGenotypeObservation.het(TrioRole.MOTHER));

        SegregationEvidence evidence = evidence(service.evaluate(input(TrioSex.FEMALE, variant)), "v1");

        assertEquals(InheritanceModelCandidate.HOMOZYGOUS_RECESSIVE_CANDIDATE, evidence.getModel());
        assertEquals(ParentalOrigin.BIPARENTAL, evidence.getParentalOrigin());
    }

    @Test
    void marksXLinkedCandidateForMaleProbandAndMaternalCarrier() {
        TrioVariantObservation variant = variant("x1", "DMD", "chrX",
                TrioGenotypeObservation.hemiAlt(TrioRole.PROBAND),
                TrioGenotypeObservation.homRef(TrioRole.FATHER),
                TrioGenotypeObservation.het(TrioRole.MOTHER));

        SegregationEvidence evidence = evidence(service.evaluate(input(TrioSex.MALE, variant)), "x1");

        assertEquals(InheritanceModelCandidate.X_LINKED_CANDIDATE, evidence.getModel());
        assertEquals(ParentalOrigin.MATERNAL, evidence.getParentalOrigin());
    }

    @Test
    void marksSingleInheritedCarrierAsInheritedCandidate() {
        TrioVariantObservation variant = variant("v1", "PPA2", "chr4",
                TrioGenotypeObservation.het(TrioRole.PROBAND),
                TrioGenotypeObservation.homRef(TrioRole.FATHER),
                TrioGenotypeObservation.het(TrioRole.MOTHER));

        SegregationEvidence evidence = evidence(service.evaluate(input(TrioSex.FEMALE, variant)), "v1");

        assertEquals(InheritanceModelCandidate.INHERITED_CANDIDATE, evidence.getModel());
        assertEquals(SegregationConfidence.POSSIBLE, evidence.getConfidence());
        assertEquals(ParentalOrigin.MATERNAL, evidence.getParentalOrigin());
    }

    @Test
    void marksNonCarrierProbandAsUninformative() {
        TrioVariantObservation variant = variant("v1", "PPA2", "chr4",
                TrioGenotypeObservation.homRef(TrioRole.PROBAND),
                TrioGenotypeObservation.het(TrioRole.FATHER),
                TrioGenotypeObservation.homRef(TrioRole.MOTHER));

        SegregationEvidence evidence = evidence(service.evaluate(input(TrioSex.FEMALE, variant)), "v1");

        assertEquals(InheritanceModelCandidate.UNINFORMATIVE, evidence.getModel());
        assertEquals(ParentalOrigin.NONE, evidence.getParentalOrigin());
    }

    private TrioAnalysisInput input(TrioSex probandSex, TrioVariantObservation... variants) {
        List<TrioMember> members = Arrays.asList(
                new TrioMember(TrioRole.PROBAND, "proband", probandSex, true),
                new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false));
        return new TrioAnalysisInput(members, Arrays.asList(variants));
    }

    private TrioVariantObservation variant(String variantId,
                                           String gene,
                                           String chromosome,
                                           TrioGenotypeObservation proband,
                                           TrioGenotypeObservation father,
                                           TrioGenotypeObservation mother) {
        Map<TrioRole, TrioGenotypeObservation> genotypes =
                new EnumMap<TrioRole, TrioGenotypeObservation>(TrioRole.class);
        genotypes.put(TrioRole.PROBAND, proband);
        genotypes.put(TrioRole.FATHER, father);
        genotypes.put(TrioRole.MOTHER, mother);
        return new TrioVariantObservation(variantId, gene, chromosome, 100L, genotypes);
    }

    private SegregationEvidence evidence(List<SegregationEvidence> result, String variantId) {
        for (SegregationEvidence evidence : result) {
            if (variantId.equals(evidence.getVariantId())) {
                return evidence;
            }
        }
        throw new AssertionError("Missing segregation evidence for " + variantId);
    }
}
