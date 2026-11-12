package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Public audit of the actual acquisition path; no qualification or scoring authority. */
public final class MaterialPreparationSummary {
    private MaterialPreparationSummary() { }
    public static ObjectNode build(ObjectMapper json, JsonNode criterion, JsonNode dispatch,
            JsonNode module, String moduleName, String pointer) {
        var out = json.createObjectNode().put("schemaVersion", "material-preparation-summary-v1")
                .put("module", moduleName).put("moduleResultPointer", pointer)
                .put("status", dispatch.path("skipSpecializedAcquisition").asBoolean() ? "NOT_REQUIRED"
                        : isInapplicable(criterion) ? "NOT_APPLICABLE"
                        : List.of("MET", "NOT_MET").contains(criterion.path("status").asText()) ? "EVALUATED" : "UNRESOLVED")
                .put("reasonCode", safeCode(criterion.path("reasonCode").asText()))
                .put("moduleStatus", safeCode(module.path("status").asText("RECORDED")));
        // A captured file is not yet usable evidence; binding/qualification is still done by the original evaluator.
        var snapshot = module.path("sourceSnapshot");
        if (snapshot.path("documents").isArray()) out.put("capturedDocuments", snapshot.path("documents").size());
        if (snapshot.path("excludedTasks").isArray()) {
            var excluded = out.putArray("excludedSourceReasons");
            snapshot.path("excludedTasks").forEach(row -> {
                String reason = row.path("reasonCode").asText();
                if (reason.matches("[A-Z][A-Z0-9_]{1,160}")) excluded.add(reason);
            });
        }
        for (String field : List.of("providerCalls", "evaluatorCalls", "allSitesCalls"))
            if (module.path(field).isIntegralNumber()) out.set(field, module.path(field).deepCopy());
        return out;
    }
    private static boolean isInapplicable(JsonNode criterion) {
        // The evaluator has already made this decision. No scientific applicability is inferred here.
        return "NOT_EVALUATED".equals(criterion.path("status").asText())
                && "NONE".equals(criterion.path("appliedStrength").asText())
                && List.of("PVS1_CONSEQUENCE_NOT_APPLICABLE", Pvs1CodingApplicability.REASON).contains(criterion.path("reasonCode").asText())
                && "ACMG_AMP_2015:PVS1".equals(criterion.path("criterionId").asText());
    }
    private static String safeCode(String value) {
        return value.matches("[A-Z][A-Z0-9_]{0,200}") ? value : "LOCAL_BATCH_SOURCE_REASON_REDACTED";
    }
}
