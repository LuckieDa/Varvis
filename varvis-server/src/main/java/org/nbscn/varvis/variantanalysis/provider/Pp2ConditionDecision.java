package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;

/** Pure two-condition truth table, independent of how a source was adopted. */
final class Pp2ConditionDecision {
    private Pp2ConditionDecision() { }
    static String reason(JsonNode conditions) {
        String mechanism = conditions.at("/missenseMechanism/conclusion").asText();
        String benign = conditions.at("/lowBenignMissense/conclusion").asText();
        if (!("SUPPORTED".equals(mechanism) || "NOT_SUPPORTED".equals(mechanism))
                || !("SUPPORTED".equals(benign) || "NOT_SUPPORTED".equals(benign)))
            return "PP2_CONDITION_UNKNOWN_OR_CONFLICT";
        if ("NOT_SUPPORTED".equals(mechanism)) return "PP2_MISSENSE_MECHANISM_NOT_SUPPORTED";
        if ("NOT_SUPPORTED".equals(benign)) return "PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED";
        return null;
    }
}
