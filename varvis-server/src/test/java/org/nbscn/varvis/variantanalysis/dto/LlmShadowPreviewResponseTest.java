package org.nbscn.varvis.variantanalysis.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmShadowPreviewResponseTest {

    @Test
    void serializedContractContainsOnlyTheExplicitSafeFields() throws Exception {
        LlmShadowPreviewResponse response = new LlmShadowPreviewResponse(
                LlmShadowPreviewResponse.Status.PREVIEW_READY,
                "Synthetic shadow preview.",
                new LlmShadowPreviewResponse.Provenance(
                        "LOCAL_SYNTHETIC", "llm-shadow-input-v1", 1,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));

        JsonNode root = new ObjectMapper().valueToTree(response);

        assertEquals(Set.of("status", "previewText", "provenance"), fields(root));
        assertEquals(Set.of(
                "mode",
                "externalTransmission",
                "rankingModified",
                "diagnosisGenerated",
                "provider",
                "schemaVersion",
                "candidateCount",
                "inputFingerprint"
        ), fields(root.get("provenance")));
    }

    private Set<String> fields(JsonNode node) {
        Set<String> result = new LinkedHashSet<String>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(result::add);
        return result;
    }
}
