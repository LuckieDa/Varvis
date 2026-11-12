package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.TreeSet;

/** Hashes source documents, which may contain decimal retrieval metadata.
 * Scientific carrier identities retain their separate, strict numeric contract.
 * Integer-only documents keep the existing canonical digest. Never mutates sources.
 */
final class Pm3SourceDigest {
    private Pm3SourceDigest() { }

    static String sha256(ObjectMapper json, JsonNode source) {
        try {
            return FamilyDeNovoJson.sha256(json.writer().without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(canonical(json, source)).getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("PM3_SOURCE_JSON_SERIALIZATION_FAILED", invalid);
        }
    }

    private static JsonNode canonical(ObjectMapper json, JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean() || value.isIntegralNumber()) return value;
        if (value.isFloatingPointNumber()) {
            if ((value.isDouble() || value.isFloat()) && !Double.isFinite(value.doubleValue()))
                throw new IllegalArgumentException("PM3_SOURCE_NON_FINITE_NUMBER");
            return value;
        }
        if (value.isTextual()) return json.getNodeFactory().textNode(Normalizer.normalize(value.asText(), Normalizer.Form.NFC));
        if (value.isArray()) {
            var out = json.createArrayNode();
            value.forEach(item -> out.add(canonical(json, item)));
            return out;
        }
        if (value.isObject()) {
            var out = json.createObjectNode();
            var names = new TreeSet<String>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
                if (out.has(normalized)) throw new IllegalArgumentException("PM3_SOURCE_DUPLICATE_NORMALIZED_KEY");
                out.set(normalized, canonical(json, value.path(name)));
            }
            return out;
        }
        throw new IllegalArgumentException("PM3_SOURCE_JSON_VALUE_UNSUPPORTED");
    }
}
