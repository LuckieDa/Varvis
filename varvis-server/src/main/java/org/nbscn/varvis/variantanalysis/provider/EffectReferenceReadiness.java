package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Public dependency diagnostics only. Never supplies functional facts or grants adoption. */
public final class EffectReferenceReadiness {
    private EffectReferenceReadiness() { }
    public static ObjectNode view(JsonNode value) {
        var out = JsonNodeFactory.instance.objectNode().put("dependency", "SAVED_FUNCTIONAL_REFERENCES")
                .put("purpose", "EXPLANATION_ONLY");
        for (String field : List.of("status", "coverage", "restriction", "reasonCode", "failureStage")) {
            String text = value.path(field).asText();
            if (text.matches("[A-Z][A-Z0-9_]{0,160}")) out.put(field, text);
        }
        if (!out.has("status")) out.put("status", "NOT_RECORDED");
        if (value.path("sourceSha256").asText().matches("[a-f0-9]{64}")) out.set("sourceSha256", value.path("sourceSha256"));
        out.put("absenceEstablished", false);
        return out;
    }
}
