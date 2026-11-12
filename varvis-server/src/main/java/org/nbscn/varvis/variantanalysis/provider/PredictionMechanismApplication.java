package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Local first-release application contract; no joint likelihood or final classification. */
public final class PredictionMechanismApplication {
    public static final String VERSION = "1.1.0";
    private PredictionMechanismApplication() { }
    public static ObjectNode apply(ObjectMapper mapper, JsonNode plan, JsonNode mechanisms, String applicationIssue) {
        return apply(mapper, plan, mechanisms, applicationIssue, java.util.List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4"));
    }
    public static ObjectNode apply(ObjectMapper mapper, JsonNode plan, JsonNode mechanisms, String applicationIssue, java.util.List<String> selectedCriteria) {
        return apply(mapper, plan, mechanisms, applicationIssue, selectedCriteria, false);
    }
    static ObjectNode apply(ObjectMapper mapper, JsonNode plan, JsonNode mechanisms, String applicationIssue,
            java.util.List<String> selectedCriteria, boolean publishedMissenseOnly) {
        var result = mapper.createObjectNode();
        String primary = plan.path("primaryMechanism").asText();
        for (String code : new String[]{"PP3", "BP4"}) {
            if (!selectedCriteria.contains("ACMG_AMP_2015:" + code)) continue;
            var rule = result.putObject(code).put("criterionId", "ACMG_AMP_2015:" + code).put("appliedStrength", "NONE")
                    .put("status", "NOT_EVALUATED").put("selectedMechanism", primary);
            rule.putObject("criterionApplicability").put("status", applicationIssue == null ? "APPLICABLE" : "UNKNOWN");
            if (applicationIssue != null) { rule.put("reasonCode", applicationIssue); continue; }
            var main = mechanisms.path(primary);
            if ("PP3".equals(code)) {
                String chosen = null;
                if (ready(main) && "HARMFUL".equals(main.path("direction").asText()) && allowed(plan, primary)) chosen = primary;
                else if ("WHEN_PRIMARY_NOT_HARMFUL".equals(plan.path("secondaryPp3Route").asText())) {
                    for (var mechanism : plan.path("allowedPp3Mechanisms")) {
                        String name = mechanism.asText(); var assessment = mechanisms.path(name);
                        if (!name.equals(primary) && ready(assessment) && "HARMFUL".equals(assessment.path("direction").asText())) { chosen = name; break; }
                    }
                }
                if (chosen != null) {
                    rule.put("status", "MET").put("selectedMechanism", chosen).put("appliedStrength", mechanisms.path(chosen).path("appliedStrength").asText())
                            .put("reasonCode", "PRESELECTED_MECHANISM_SUPPORTS_PP3");
                } else {
                    boolean unknown = false;
                    for (var name : plan.path("allowedPp3Mechanisms")) if (!ready(mechanisms.path(name.asText()))) unknown = true;
                    rule.put("status", unknown ? "NOT_EVALUATED" : "NOT_MET").put("reasonCode", unknown ? "PP3_ALLOWED_MECHANISM_UNRESOLVED" : "NO_PLANNED_MECHANISM_SUPPORTS_PP3");
                }
            } else {
                if (!completeBp4Plan(plan, publishedMissenseOnly)) {
                    rule.put("reasonCode", "BP4_REQUIRED_MECHANISM_PLAN_INVALID");
                    rule.putObject("criterionApplicability").put("status", "UNKNOWN");
                    continue;
                }
                boolean unknown = false, notBenign = false;
                for (var name : plan.path("requiredBp4Mechanisms")) {
                    var m = mechanisms.path(name.asText());
                    if (!ready(m)) unknown = true;
                    else if (!"BENIGN".equals(m.path("direction").asText())) notBenign = true;
                }
                if (notBenign) rule.put("status", "NOT_MET").put("reasonCode", "BP4_REQUIRED_MECHANISM_NOT_BENIGN");
                else if (unknown || !ready(main)) rule.put("reasonCode", "BP4_REQUIRED_MECHANISM_UNRESOLVED");
                else rule.put("status", "MET").put("appliedStrength", main.path("appliedStrength").asText()).put("reasonCode", "ALL_REQUIRED_MECHANISMS_BENIGN_NO_ADDITION");
            }
        }
        for (var ruleValue : result) {
            var rule = (ObjectNode) ruleValue;
            rule.put("applicationContract", "MECHANISM_APPLICATION_V1");
            rule.put("applicationVersion", VERSION);
            rule.putArray("deterministicTrace").add("PRESELECTED_PRIMARY:" + primary).add(rule.path("reasonCode").asText());
            if (!"MET".equals(rule.path("status").asText())) continue;
            var restrictions = plan.path("effectRestrictions");
            String reason = null;
            if (!"VERIFIED_SAVED_REFERENCES".equals(restrictions.path("status").asText())
                    || !restrictions.path("references").isArray()
                    || !restrictions.path("sourceSha256").asText().matches("[0-9a-f]{64}")
                    || !plan.at("/context/contextId").isTextual()
                    || !plan.at("/context/contextId").equals(restrictions.path("contextId"))) reason = "SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE";
            else for (var ref : restrictions.path("references")) {
                if (!ref.path("criterionId").equals(rule.path("criterionId")) || !ref.path("mechanism").equals(rule.path("selectedMechanism"))) continue;
                if (ref.path("savedEvidenceId").asText().isBlank() || ref.path("effectId").asText().isBlank()) reason = "SAVED_EFFECT_REFERENCE_INVALID";
                else if ("BLOCK_DUPLICATE".equals(ref.path("action").asText())) reason = "SAME_EFFECT_ALREADY_ADOPTED";
                else if (!"ALLOW".equals(ref.path("action").asText())) reason = "SAVED_EFFECT_REFERENCE_INVALID";
            }
            rule.set("effectReferences", restrictions.deepCopy());
            if (reason != null) rule.put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", reason);
        }
        return result;
    }
    private static boolean allowed(JsonNode plan, String name) { for (var m : plan.path("allowedPp3Mechanisms")) if (name.equals(m.asText())) return true; return false; }
    private static boolean ready(JsonNode mechanism) {
        String direction = mechanism.path("direction").asText(), strength = mechanism.path("appliedStrength").asText();
        boolean calibratedDirection = "UNINFORMATIVE".equals(direction) ? "NONE".equals(strength)
                : java.util.Set.of("BENIGN", "HARMFUL").contains(direction)
                && java.util.Set.of("SUPPORTING", "MODERATE", "STRONG", "VERY_STRONG").contains(strength);
        return "CALIBRATED".equals(mechanism.path("status").asText())
                && calibratedDirection
                && !"OFFLINE_CALIBRATION_VALIDATION".equals(mechanism.path("operation").asText())
                && !"NOT_EVALUATED".equals(mechanism.path("applicationStatus").asText());
    }
    private static boolean completeBp4Plan(JsonNode plan, boolean publishedMissenseOnly) {
        var required = plan.path("requiredBp4Mechanisms");
        if (!required.isArray() || required.isEmpty() || required.size() > 2) return false;
        var names = new java.util.HashSet<String>();
        for (var name : required) if (!name.isTextual() || !java.util.Set.of("PROTEIN_MISSENSE", "SPLICING").contains(name.asText())
                || !names.add(name.asText())) return false;
        String primary = plan.path("primaryMechanism").asText();
        if (publishedMissenseOnly) return "PROTEIN_MISSENSE".equals(primary) && names.equals(java.util.Set.of("PROTEIN_MISSENSE"));
        // General missense BP4 must cover both effects.
        return "PROTEIN_MISSENSE".equals(primary) ? names.equals(java.util.Set.of("PROTEIN_MISSENSE", "SPLICING"))
                : "SPLICING".equals(primary) && names.equals(java.util.Set.of("SPLICING"));
    }
}
