package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MissenseAnnotationVersionBindingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MissenseReferenceDraftPreparation preparation = new MissenseReferenceDraftPreparation();

    private ObjectNode source() {
        var registered = ClinVarMissenseResourceReadiness.identityPolicy(mapper).path("resources").get(1);
        var source = mapper.createObjectNode();
        for (String field : new String[]{"resourceId", "resourceVersion", "identityFingerprintSha256"})
            source.set(field, registered.path(field));
        return source;
    }

    private ObjectNode annotation(String version) {
        return mapper.createObjectNode().put("status", "COMPLETED").put("source", "VEP_CACHED")
                .put("version", version).put("consequence", "missense_variant")
                .put("transcriptId", "ENST00000371953").put("transcriptAccession", "ENST00000371953.8");
    }

    @Test void registeredResourceAcceptsItsActualProducerVersionWithoutRewritingIt() throws Exception {
        var source = source(); var annotation = annotation("116.0"); var original = annotation.deepCopy();
        assertEquals("116.0", preparation.annotation(annotation, "ENST00000371953.8", source, source).getVersion());
        assertEquals(original, annotation);
    }

    @Test void legacyExactResourceVersionRemainsAccepted() throws Exception {
        var source = source(); var annotation = annotation(source.path("resourceVersion").asText());
        assertNotNull(preparation.annotation(annotation, "ENST00000371953.8", source, source));
    }

    @ParameterizedTest @ValueSource(strings = {"resourceId", "resourceVersion", "identityFingerprintSha256", "version", "status", "source", "actualSource"})
    void unrelatedOrChangedIdentityCannotBorrowProducerVersion(String fault) {
        var source = source(); var annotation = annotation("116.0"); var actual = source.deepCopy();
        if (fault.equals("actualSource")) actual.put("resourceId", "OTHER");
        else if (source.has(fault)) { source.put(fault, fault.equals("identityFingerprintSha256") ? "a".repeat(64) : "OTHER"); actual = source.deepCopy(); }
        else annotation.put(fault, fault.equals("version") ? "117.0" : "OTHER");
        var finalActual = actual;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> preparation.annotation(annotation, "ENST00000371953.8", source, finalActual));
        assertEquals(fault.equals("actualSource") ? "ANNOTATION_SOURCE_MISMATCH" : "ANNOTATION_STATUS_OR_VERSION_INVALID", failure.getMessage());
    }
}
