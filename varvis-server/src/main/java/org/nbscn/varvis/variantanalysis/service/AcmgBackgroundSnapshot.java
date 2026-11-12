package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/** Read-only preparation identity. This is not a reference check or scientific approval. */
public final class AcmgBackgroundSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AcmgBackgroundSnapshot() { }

    public static ObjectNode prepare(ObjectNode options, ObjectNode literature) {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", "acmg-common-background-v1");
        value.put("backgroundHandling", "AUTOMATIC_DISTINCT_CONTEXTS_V1");
        value.set("options", options);
        value.set("literature", literature);
        value.put("backgroundSha256", fingerprint(value));
        return value;
    }

    public static void verify(ObjectNode current, String expected) {
        if (expected == null || !expected.matches("[a-f0-9]{64}") || !expected.equals(current.path("backgroundSha256").asText()))
            throw new IllegalArgumentException("ACMG_BACKGROUND_CHANGED_RECONFIRM");
    }

    private static String fingerprint(JsonNode node) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(node))));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("ACMG_BACKGROUND_IDENTITY_FAILED", error);
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            // Recommendations are regenerated at read time. All source hashes, annotation
            // timestamps, configuration, selected identities and actual choices remain bound.
            node.properties().iterator().forEachRemaining(e -> { if (!e.getKey().equals("generatedAt")) fields.put(e.getKey(), e.getValue()); });
            ObjectNode value = JSON.createObjectNode();
            fields.forEach((key, child) -> value.set(key, canonical(child)));
            return value;
        }
        if (node.isArray()) {
            var value = JSON.createArrayNode();
            node.forEach(child -> value.add(canonical(child)));
            return value;
        }
        return node;
    }
}
