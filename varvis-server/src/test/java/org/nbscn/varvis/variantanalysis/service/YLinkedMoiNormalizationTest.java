package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Input spelling compatibility only: no disease, genotype, region or frequency qualification. */
class YLinkedMoiNormalizationTest {
    @ParameterizedTest
    @ValueSource(strings = {"Y_LINKED", "Y-LINKED", "Y LINKED", "y_linked", "y-linked", " y linked "})
    void candidateYSpellingsAgreeWithTheExistingParser(String raw) {
        assertEquals("Y_LINKED", PatientDiseaseContextBindingResolver.normalizeMoi(raw));
    }

    @Test
    void ambiguousOrUnknownInputsCannotBecomeSpecificY() {
        for (String raw : new String[]{null, "", " ", "Y", "YL", "HOLANDRIC", "HEMIZYGOUS", "MALE", "XL", "UNKNOWN",
                "Y_LINKED_DOMINANT", "Y_LINKED_RECESSIVE", "Y  LINKED", "Y\tLINKED", "Y__LINKED", "Y / LINKED"}) {
            assertNull(PatientDiseaseContextBindingResolver.normalizeMoi(raw), raw);
        }
    }

    @Test
    void existingFiniteNonYModesKeepTheirMeaning() {
        for (var entry : Map.ofEntries(
                Map.entry("AD", "AUTOSOMAL_DOMINANT"), Map.entry("AUTOSOMAL_DOMINANT", "AUTOSOMAL_DOMINANT"),
                Map.entry("AR", "AUTOSOMAL_RECESSIVE"), Map.entry("AUTOSOMAL_RECESSIVE", "AUTOSOMAL_RECESSIVE"),
                Map.entry("XD", "X_LINKED_DOMINANT"), Map.entry("X_DOMINANT", "X_LINKED_DOMINANT"),
                Map.entry("X_LINKED_DOMINANT", "X_LINKED_DOMINANT"), Map.entry("XR", "X_LINKED_RECESSIVE"),
                Map.entry("X_RECESSIVE", "X_LINKED_RECESSIVE"), Map.entry("X_LINKED_RECESSIVE", "X_LINKED_RECESSIVE")).entrySet()) {
            assertEquals(entry.getValue(), PatientDiseaseContextBindingResolver.normalizeMoi(entry.getKey()), entry.getKey());
        }
    }

    @Test
    void yCompatibilityDoesNotWidenOtherModeSpellings() {
        for (String raw : new String[]{"ad", " AD ", "autosomal dominant", "ar", "xd", "xr", "X-LINKED-DOMINANT",
                "X LINKED RECESSIVE", "XL", "SD", "UD", "MT", "MITOCHONDRIAL"}) {
            assertNull(PatientDiseaseContextBindingResolver.normalizeMoi(raw), raw);
        }
    }

    @Test
    void scientificCatalogVocabularyIsUnchanged() {
        assertEquals("Y_LINKED", PatientDiseaseContextBindingResolver.normalizeCatalogMoi("Y_LINKED"));
        for (String raw : new String[]{"Y-LINKED", "Y LINKED", "y_linked", " Y_LINKED ", "Y", "YL", "XD", "XR", "X_DOMINANT", "X_RECESSIVE"}) {
            assertNull(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(raw), raw);
        }
    }

    @Test
    void rawYCompatibilityDoesNotFillMissingNormalizedHistory() {
        for (String raw : new String[]{"Y_LINKED", "Y-LINKED", "Y LINKED"}) {
            var candidate = PatientDiseaseContextBindingResolverTest.candidate(1, "SYNTHETIC", "16", raw, raw, null);
            var assessment = LocalInterpretationContextPolicy.assess(candidate, "Y_LINKED");
            assertNull(candidate.getExomiserCandidateFeatures().getNormalizedModeOfInheritance());
            assertEquals("CANDIDATE_MOI_REQUIRED", assessment.reasonCode());
        }
    }
}
