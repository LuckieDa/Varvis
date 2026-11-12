package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/** Display-only, snapshot-bound preparation summary. Never a policy or calculator input. */
final class Bs1ParameterSupply {
    static final String VERSION = "bs1-parameter-supply-v1";
    static final List<String> FIELDS = List.of("prevalence", "penetrance", "maximumGeneContribution", "maximumAlleleContribution");
    private static final Set<String> STATES = Set.of("SOURCE_GAP", "IDENTITY_UNRESOLVED", "CONFLICT", "REVIEW_REQUIRED",
            "CANDIDATE_AVAILABLE", "SOFTWARE_UNSUPPORTED", "INVALID_SOURCE_DATA");
    private Bs1ParameterSupply() { }

    static void validate(JsonNode supply, JsonNode background, JsonNode sources) {
        require(supply.isObject() && Bs1Policy.keys(supply, Set.of("schemaVersion", "scope", "sourceScope", "modelExecutable",
                "scientificallyAdopted", "blockingReasons", "fields")) && VERSION.equals(supply.path("schemaVersion").asText()));
        require("ONLY_PINNED_LOADED_INPUTS".equals(supply.path("sourceScope").asText())
                && supply.path("modelExecutable").isBoolean() && !supply.path("modelExecutable").asBoolean()
                && supply.path("scientificallyAdopted").isBoolean() && !supply.path("scientificallyAdopted").asBoolean());
        require(Bs1Policy.keys(supply.path("scope"), Set.of("hgncId", "mondoId", "moi")));
        for (String k : List.of("hgncId", "mondoId", "moi"))
            require(background.path(k).isTextual() && supply.path("scope").path(k).equals(background.path(k)));
        codes(supply.path("blockingReasons"));
        var fields = supply.path("fields"); require(fields.isObject() && fields.size() == FIELDS.size());
        for (String name : FIELDS) {
            var field = fields.path(name);
            require(Bs1Policy.keys(field, Set.of("status", "structuredStatus", "textStatuses", "reasonCodes", "observationCount",
                    "textProposalCount", "evidenceCount", "omittedEvidenceCount", "evidence"))
                    && STATES.contains(field.path("status").asText()) && STATES.contains(field.path("structuredStatus").asText()));
            codes(field.path("reasonCodes")); codes(field.path("textStatuses"));
            for (String count : List.of("observationCount", "textProposalCount", "evidenceCount", "omittedEvidenceCount"))
                require(field.path(count).isIntegralNumber() && field.path(count).canConvertToInt() && field.path(count).asInt() >= 0);
            var evidence = field.path("evidence");
            require(evidence.isArray() && evidence.size() <= 3
                    && (long) evidence.size() + field.path("omittedEvidenceCount").asInt() == field.path("evidenceCount").asInt()
                    && field.path("evidenceCount").asInt() <= (long) field.path("observationCount").asInt() + field.path("textProposalCount").asInt());
            for (var item : evidence) {
                require(Bs1Policy.keys(item, Set.of("source", "locator", "factId", "kind", "valueKind", "valueText", "statistic", "population"))
                        && Bs1Policy.sha(item.path("factId")) && Set.of("TEXT_PROPOSAL", "PREPARATION_OBSERVATION").contains(item.path("kind").asText()));
                for (String key : List.of("locator", "valueKind", "valueText", "statistic", "population"))
                    require(item.path(key).isTextual() && item.path(key).asText().length() <= 2000);
                require(!item.path("locator").asText().isBlank() && !item.path("valueKind").asText().isBlank());
                var ref = item.path("source"); int matches = 0;
                for (var source : sources) if (source.equals(ref)) matches++;
                require(matches == 1);
            }
        }
    }
    private static void codes(JsonNode values) {
        require(values.isArray() && values.size() <= 100);
        for (var code : values) require(code.isTextual() && code.asText().matches("[A-Z0-9_]{1,160}"));
    }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("BS1_PARAMETER_SUPPLY_INVALID");
    }
}
