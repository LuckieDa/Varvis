package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioPipelineInputResolverTest {

    private final TrioPipelineInputResolver resolver = new TrioPipelineInputResolver();

    @Test
    void disabledRequestReturnsAbsentInput() {
        assertFalse(resolver.resolve(
                TrioAnalysisInputRequest.disabled(),
                Paths.get("proband.vcf"),
                null,
                null
        ).isPresent());
    }

    @Test
    void jointRequestBuildsSampleMappingAndThresholdOverrides() {
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "JOINT_VCF",
                true,
                "p",
                "f",
                "m",
                12,
                30,
                true,
                false,
                false
        );

        TrioPipelineInput input = resolver.resolve(request, Paths.get("joint.vcf"), null, null);

        assertTrue(input.isPresent());
        assertEquals(TrioInputMode.JOINT_VCF, input.getInputMode());
        assertEquals("p", input.getSampleMapping().getProbandSampleId());
        assertEquals(12, input.getMinDepthForConfirmedRef());
        assertEquals(30, input.getMinGqForConfirmedRef());
    }

    @Test
    void threeVcfRequestUsesPrimaryAsProbandAndKeepsParentPaths() {
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "THREE_SINGLE_SAMPLE_VCFS",
                true,
                "p",
                "f",
                "m",
                null,
                null,
                true,
                true,
                true
        );

        TrioPipelineInput input = resolver.resolve(
                request,
                Paths.get("proband.vcf"),
                Paths.get("father.vcf"),
                Paths.get("mother.vcf")
        );

        assertTrue(input.isPresent());
        assertEquals(TrioInputMode.THREE_SINGLE_SAMPLE_VCFS, input.getInputMode());
        assertEquals(Paths.get("proband.vcf"), input.getProbandVcfPath());
        assertEquals(Paths.get("father.vcf"), input.getFatherVcfPath());
        assertEquals(Paths.get("mother.vcf"), input.getMotherVcfPath());
    }
}
