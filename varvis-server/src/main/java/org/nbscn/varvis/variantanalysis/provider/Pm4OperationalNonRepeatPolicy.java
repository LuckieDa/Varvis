package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.require;

/** Project research inference, not proof of biological absence or a clinical calibration. */
public final class Pm4OperationalNonRepeatPolicy {
    public static final String MODE = "ANNOTATION_SEQUENCE_V1";
    public static final String REVIEWED_ONLY = "REVIEWED_ONLY";
    public static final String VERSION = "1.0.0";
    private Pm4OperationalNonRepeatPolicy() { }

    public static String mode(String value) {
        String mode = value.isBlank() ? REVIEWED_ONLY : value;
        require(List.of(REVIEWED_ONLY, MODE).contains(mode), "PM4_NON_REPEAT_POLICY_UNKNOWN");
        return mode;
    }

    static ObjectNode assess(ObjectMapper mapper, ObjectNode mapped, ObjectNode combined, ObjectNode screen) {
        var out = mapper.createObjectNode().put("method", MODE).put("methodVersion", VERSION)
                .put("qualified", false).put("basis", "OPERATIONAL_INFERENCE")
                .put("absoluteNonRepeatProof", false).put("clinicalValidationCompleted", false);
        var exclusions = out.putArray("exclusions");
        try {
            require("READY".equals(mapped.path("status").asText()) && mapped.path("features").isArray()
                    && mapped.path("unmappedFeatures").isArray() && mapped.path("unmappedFeatures").isEmpty(),
                    "PM4_OPERATIONAL_ANNOTATIONS_INCOMPLETE");
            require("READY".equals(combined.path("status").asText())
                    && combined.path("operationalSourceComplete").isBoolean() && combined.path("operationalSourceComplete").asBoolean()
                    && combined.path("unplacedCount").isIntegralNumber() && combined.path("unplacedCount").asInt(-1) == 0,
                    "PM4_OPERATIONAL_SOURCE_COMPLETENESS_REQUIRED");
            require(combined.path("repeatOverlaps").isArray() && combined.path("repeatOverlaps").isEmpty()
                    && "COMPLETE".equals(screen.path("status").asText())
                    && screen.path("candidateFound").isBoolean() && !screen.path("candidateFound").asBoolean(),
                    "PM4_OPERATIONAL_REPEAT_CHECK_INCOMPLETE_OR_POSITIVE");
            int start = mapped.at("/queryRange/start").asInt(), end = mapped.at("/queryRange/end").asInt();
            require(start > 0 && end >= start, "PM4_OPERATIONAL_QUERY_INVALID");
            for (var feature : mapped.path("features")) {
                var raw = mapper.readTree(feature.path("rawFeatureJson").asText());
                if (raw == null) throw new IllegalArgumentException("PM4_OPERATIONAL_FEATURE_PROJECTION_INVALID");
                require(raw.path("type").equals(feature.path("type"))
                        && raw.at("/location/start/value").equals(feature.path("start"))
                        && raw.at("/location/end/value").equals(feature.path("end"))
                        && "EXACT".equals(raw.at("/location/start/modifier").asText())
                        && "EXACT".equals(raw.at("/location/end/modifier").asText())
                        && feature.path("sourceLocator").asText().matches("/features/[0-9]+"),
                        "PM4_OPERATIONAL_FEATURE_PROJECTION_INVALID");
                String type = feature.path("type").asText();
                boolean excluded = "Repeat".equals(type) || "Compositional bias".equals(type)
                        || "Region".equals(type) && "Disordered".equalsIgnoreCase(raw.path("description").asText().trim());
                if (excluded && feature.path("start").asInt() <= end && feature.path("end").asInt() >= start) {
                    var hit = exclusions.addObject().put("type", type).put("description", raw.path("description").asText());
                    for (String field : List.of("start", "end", "sourceLocator", "featureJsonSha256")) hit.set(field, feature.path(field));
                }
            }
            require(exclusions.isEmpty(), "PM4_OPERATIONAL_LOW_COMPLEXITY_OR_DISORDER_EXCLUDED");
            out.set("sourceCompleteness", combined.path("sourceCompleteness").deepCopy());
            return out.put("qualified", true).put("reasonCode", "PM4_PROJECT_LOCAL_OPERATIONAL_NON_REPEAT_MET");
        } catch (IllegalArgumentException bad) { return out.put("reasonCode", bad.getMessage()); }
        catch (IOException bad) { return out.put("reasonCode", "PM4_OPERATIONAL_FEATURE_JSON_INVALID"); }
    }
}
