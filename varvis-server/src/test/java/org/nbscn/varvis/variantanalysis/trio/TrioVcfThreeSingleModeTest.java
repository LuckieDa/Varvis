package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioVcfThreeSingleModeTest {

    private final TrioVcfGenotypeObservationBuilder builder = new TrioVcfGenotypeObservationBuilder();

    @Test
    void alignsThreeSingleSampleVcfsByNormalizedVariantKey() throws Exception {
        TrioVcfParseResult result = builder.parse(TrioVcfParseRequest.threeSingleSample(
                "GRCh37",
                resource("three_single_proband.synthetic.vcf"),
                resource("three_single_father_missing.synthetic.vcf"),
                resource("three_single_mother.synthetic.vcf"),
                new TrioVcfSampleMapping("proband", "father", "mother"),
                members()));

        assertFalse(result.hasErrors());
        TrioVariantObservation variant = result.getAnalysisInput().getVariants().get(0);

        assertEquals(TrioGenotypeCall.HET_ALT, variant.genotype(TrioRole.PROBAND).getCall());
        assertEquals(TrioGenotypeCall.UNOBSERVED, variant.genotype(TrioRole.FATHER).getCall());
        assertEquals(TrioGenotypeCall.HOM_REF, variant.genotype(TrioRole.MOTHER).getCall());
        assertTrue(hasIssue(result, "TRIO_VCF_PARENT_VARIANT_UNOBSERVED"));

        SegregationEvidence evidence = evidence(new TrioSegregationService()
                .evaluate(result.getAnalysisInput()), "three-single-1");
        assertEquals(InheritanceModelCandidate.UNINFORMATIVE, evidence.getModel());
    }

    @Test
    void normalizesChromosomePrefixButKeepsAssemblySeparate() {
        TrioVcfVariantKey grch37Chr = new TrioVcfVariantKey("GRCh37", "chr4", 500L, "A", "G");
        TrioVcfVariantKey grch37NoChr = new TrioVcfVariantKey("GRCh37", "4", 500L, "A", "G");
        TrioVcfVariantKey grch38SameCoordinates = new TrioVcfVariantKey("GRCh38", "4", 500L, "A", "G");
        TrioVcfVariantKey grch37ChrX = new TrioVcfVariantKey("GRCh37", "chrX", 600L, "A", "G");
        TrioVcfVariantKey grch37X = new TrioVcfVariantKey("GRCh37", "X", 600L, "A", "G");

        assertEquals(grch37Chr, grch37NoChr);
        assertEquals(grch37ChrX, grch37X);
        assertNotEquals(grch37Chr, grch38SameCoordinates);
    }

    private List<TrioMember> members() {
        return Arrays.asList(
                new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false));
    }

    private Path resource(String resourceName) throws URISyntaxException {
        return Paths.get(getClass().getResource("/trio/" + resourceName).toURI());
    }

    private boolean hasIssue(TrioVcfParseResult result, String code) {
        for (TrioVcfParseIssue issue : result.getIssues()) {
            if (code.equals(issue.getCode())) {
                return true;
            }
        }
        return false;
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
