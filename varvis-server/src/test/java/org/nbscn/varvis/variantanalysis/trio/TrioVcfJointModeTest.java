package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TrioVcfJointModeTest {

    private final TrioVcfGenotypeObservationBuilder builder = new TrioVcfGenotypeObservationBuilder();
    private final TrioSegregationService segregationService = new TrioSegregationService();

    @Test
    void parsesCompoundHetJointFixtureIntoSegregationEvidence() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("compound_het.synthetic.vcf", TrioSex.FEMALE));

        assertFalse(result.hasErrors());
        List<SegregationEvidence> evidence = segregationService.evaluate(result.getAnalysisInput());

        SegregationEvidence paternal = evidence(evidence, "compound-pat");
        SegregationEvidence maternal = evidence(evidence, "compound-mat");
        assertEquals(InheritanceModelCandidate.COMPOUND_HETEROZYGOUS_CANDIDATE, paternal.getModel());
        assertEquals(InheritanceModelCandidate.COMPOUND_HETEROZYGOUS_CANDIDATE, maternal.getModel());
        assertEquals(Arrays.asList("compound-mat"), paternal.getPartnerVariantIds());
        assertEquals(Arrays.asList("compound-pat"), maternal.getPartnerVariantIds());
    }

    @Test
    void parsesHomozygousRecessiveJointFixtureIntoSegregationEvidence() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("homozygous_recessive.synthetic.vcf", TrioSex.FEMALE));

        assertFalse(result.hasErrors());
        SegregationEvidence evidence = evidence(segregationService.evaluate(result.getAnalysisInput()), "hom-rec-1");

        assertEquals(InheritanceModelCandidate.HOMOZYGOUS_RECESSIVE_CANDIDATE, evidence.getModel());
        assertEquals(ParentalOrigin.BIPARENTAL, evidence.getParentalOrigin());
    }

    private TrioVcfParseRequest joint(String resourceName, TrioSex probandSex) throws Exception {
        return TrioVcfParseRequest.joint(
                "GRCh37",
                resource(resourceName),
                new TrioVcfSampleMapping("proband", "father", "mother"),
                members(probandSex));
    }

    private List<TrioMember> members(TrioSex probandSex) {
        return Arrays.asList(
                new TrioMember(TrioRole.PROBAND, "proband", probandSex, true),
                new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false));
    }

    private Path resource(String resourceName) throws URISyntaxException {
        return Paths.get(getClass().getResource("/trio/" + resourceName).toURI());
    }

    private SegregationEvidence evidence(List<SegregationEvidence> evidenceList, String variantId) {
        for (SegregationEvidence evidence : evidenceList) {
            if (variantId.equals(evidence.getVariantId())) {
                return evidence;
            }
        }
        throw new AssertionError("Missing evidence for " + variantId);
    }
}
