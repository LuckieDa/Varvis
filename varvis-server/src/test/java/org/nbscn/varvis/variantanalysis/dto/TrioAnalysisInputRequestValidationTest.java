package org.nbscn.varvis.variantanalysis.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioAnalysisInputRequestValidationTest {

    @Test
    void disabledInputIsValidNoOp() {
        TrioAnalysisInputRequest request = TrioAnalysisInputRequest.disabled();

        assertTrue(request.isDisabled());
        assertTrue(request.validationErrors().isEmpty());
    }

    @Test
    void jointModeRequiresSampleMappingAndPrimaryVcf() {
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "JOINT_VCF",
                true,
                "proband",
                "",
                "mother",
                null,
                null,
                false,
                false,
                false
        );

        List<String> errors = request.validationErrors();

        assertTrue(errors.toString().contains("TRIO_SAMPLE_MAPPING_INCOMPLETE"));
        assertTrue(errors.toString().contains("JOINT_VCF_REQUIRED"));
    }

    @Test
    void threeVcfModeRequiresParentsAndEvidenceOnly() {
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "THREE_SINGLE_SAMPLE_VCFS",
                false,
                "proband",
                "father",
                "mother",
                8,
                15,
                true,
                false,
                true
        );

        List<String> errors = request.validationErrors();

        assertTrue(errors.toString().contains("TRIO_EVIDENCE_ONLY_REQUIRED"));
        assertTrue(errors.toString().contains("FATHER_VCF_REQUIRED"));
        assertFalse(errors.toString().contains("MOTHER_VCF_REQUIRED"));
    }

    @Test
    void duplicateRoleMappingIsRejected() {
        TrioAnalysisInputRequest request = new TrioAnalysisInputRequest(
                true,
                "JOINT_VCF",
                true,
                "proband",
                "father",
                "father",
                null,
                null,
                true,
                false,
                false
        );

        List<String> errors = request.validationErrors();

        assertTrue(errors.toString().contains("TRIO_SAMPLE_MAPPING_DUPLICATE"));
    }

    @Test
    void jointModeRequiresExplicitPedigreeAttributesAndAcceptsCompleteAttributes() {
        TrioAnalysisInputRequest incomplete = new TrioAnalysisInputRequest(
                true, "JOINT_VCF", true, "proband", "father", "mother",
                null, null, null, null, null, null,
                null, null, true, false, false
        );
        assertFalse(incomplete.hasExplicitPedigreeAttributes());

        TrioAnalysisInputRequest complete = new TrioAnalysisInputRequest(
                true, "JOINT_VCF", true, "proband", "father", "mother",
                "FEMALE", "MALE", "FEMALE", true, false, false,
                null, null, true, false, false
        );
        assertTrue(complete.validationErrors().isEmpty());
        assertTrue(complete.hasExplicitPedigreeAttributes());
        assertTrue(complete.hasValidParentSexes());
    }
}
