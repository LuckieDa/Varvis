package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticTrioObservationBuilderTest {

    private final SyntheticTrioObservationBuilder builder = new SyntheticTrioObservationBuilder();

    @Test
    void parsesCommonDiploidAndHemizygousGenotypes() {
        assertEquals(TrioGenotypeCall.HOM_REF, builder.parse(TrioRole.PROBAND, "0/0").getCall());
        assertEquals(TrioGenotypeCall.HET_ALT, builder.parse(TrioRole.PROBAND, "0|1").getCall());
        assertEquals(TrioGenotypeCall.HET_ALT, builder.parse(TrioRole.PROBAND, "1/0").getCall());
        assertEquals(TrioGenotypeCall.HOM_ALT, builder.parse(TrioRole.PROBAND, "1|1").getCall());
        assertEquals(TrioGenotypeCall.HEMI_ALT, builder.parse(TrioRole.PROBAND, "1").getCall());
        assertEquals(TrioGenotypeCall.NO_CALL, builder.parse(TrioRole.PROBAND, "./.").getCall());
        assertEquals(TrioGenotypeCall.UNOBSERVED, builder.parse(TrioRole.PROBAND, null).getCall());
    }

    @Test
    void nullParentGenotypeBuildsUnobservedNotConfirmedReference() {
        TrioAnalysisInput input = builder.build(members(), Arrays.asList(
                new SyntheticTrioVariantRecord("v1", "GENE1", "chr1", 100L, "0/1", null, "0/0")));

        TrioVariantObservation variant = input.getVariants().get(0);

        assertTrue(variant.genotype(TrioRole.FATHER).isUninformative());
        assertFalse(variant.genotype(TrioRole.FATHER).isConfirmedReference());
    }

    @Test
    void buildsSyntheticTrioAnalysisInput() {
        TrioAnalysisInput input = builder.build(members(), Arrays.asList(
                new SyntheticTrioVariantRecord("v1", "GENE1", "chr1", 100L, "0/1", "0/0", "0/0"),
                new SyntheticTrioVariantRecord("v2", "GENE2", "chr2", 200L, "1/1", "0/1", "0/1")));

        assertEquals(3, input.getMembers().size());
        assertEquals(2, input.getVariants().size());
        assertEquals("GENE2", input.getVariants().get(1).getGeneSymbol());
        assertEquals(TrioGenotypeCall.HOM_ALT, input.getVariants().get(1).genotype(TrioRole.PROBAND).getCall());
    }

    private List<TrioMember> members() {
        return Arrays.asList(
                new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false));
    }
}
