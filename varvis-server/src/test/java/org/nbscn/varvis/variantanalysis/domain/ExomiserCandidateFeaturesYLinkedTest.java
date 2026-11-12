package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ExomiserCandidateFeaturesYLinkedTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"Y_LINKED,Y_LINKED", "y-linked,Y LINKED", "Y linked,Y-LINKED"})
    void acceptsOnlyConsistentExplicitYDeclaration(String raw, String declared) throws Exception {
        var json = mapper.createObjectNode().put("rawModeOfInheritance", raw)
                .put("normalizedModeOfInheritance", declared);
        var features = mapper.treeToValue(json, ExomiserCandidateFeatures.class);
        assertEquals(raw, features.getRawModeOfInheritance());
        assertEquals("Y_LINKED", features.getNormalizedModeOfInheritance());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Y_LINKED", "Y-LINKED", "Y linked"})
    void legacyNullYDeclarationIsReadWithoutAutoUpgrade(String raw) throws Exception {
        var json = mapper.createObjectNode().put("rawModeOfInheritance", raw)
                .putNull("normalizedModeOfInheritance");
        json.putArray("invalidFields").add("MOI:UNRECOGNIZED");
        byte[] original = mapper.writeValueAsBytes(json);
        var features = mapper.readValue(original, ExomiserCandidateFeatures.class);
        assertEquals(raw, features.getRawModeOfInheritance());
        assertNull(features.getNormalizedModeOfInheritance());
        assertTrue(features.getInvalidFields().contains("MOI:UNRECOGNIZED"));
    }

    @Test
    void legacyAbsentYDeclarationIsNotBackfilled() throws Exception {
        var features = mapper.readValue("{\"rawModeOfInheritance\":\"Y_LINKED\"}", ExomiserCandidateFeatures.class);
        assertNull(features.getNormalizedModeOfInheritance());
    }

    @ParameterizedTest
    @CsvSource({"Y_LINKED,AUTOSOMAL_RECESSIVE", "AR,Y_LINKED", "XL,Y_LINKED", "HEMIZYGOUS,Y_LINKED", "UNKNOWN,Y_LINKED"})
    void refusesContradictoryOrInferredYDeclaration(String raw, String declared) {
        var json = mapper.createObjectNode().put("rawModeOfInheritance", raw)
                .put("normalizedModeOfInheritance", declared);
        var failure = assertThrows(JsonMappingException.class,
                () -> mapper.treeToValue(json, ExomiserCandidateFeatures.class));
        assertTrue(failure.getMessage().contains("EXOMISER_MOI_NORMALIZATION_MISMATCH"));
    }

    @Test
    void existingAdArXMissingDeclarationsRemainRejected() {
        for (String raw : new String[]{"AD", "AR", "XD", "XR", "MT"}) {
            var json = mapper.createObjectNode().put("rawModeOfInheritance", raw)
                    .putNull("normalizedModeOfInheritance");
            var failure = assertThrows(JsonMappingException.class,
                    () -> mapper.treeToValue(json, ExomiserCandidateFeatures.class));
            assertTrue(failure.getMessage().contains("EXOMISER_MOI_NORMALIZATION_MISMATCH"));
        }
    }

    @Test
    void rejectsUnsupportedDeclaredModeAndYWithoutRawSource() {
        var unsupported = mapper.createObjectNode().put("rawModeOfInheritance", "Y_LINKED")
                .put("normalizedModeOfInheritance", "Y");
        var invalid = assertThrows(JsonMappingException.class,
                () -> mapper.treeToValue(unsupported, ExomiserCandidateFeatures.class));
        assertTrue(invalid.getMessage().contains("EXOMISER_NORMALIZED_MOI_INVALID"));
        var noSource = mapper.createObjectNode().putNull("rawModeOfInheritance")
                .put("normalizedModeOfInheritance", "Y_LINKED");
        var mismatch = assertThrows(JsonMappingException.class,
                () -> mapper.treeToValue(noSource, ExomiserCandidateFeatures.class));
        assertTrue(mismatch.getMessage().contains("EXOMISER_MOI_NORMALIZATION_MISMATCH"));
    }
}
