package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;

/** PP1 scope and current-run contribution ledger, built only from trusted preparation.
 * Historical runs are alternative evaluations, not additional segregation events.
 * Evidence selection does not establish an adopted-contribution inventory.
 */
public final class Pp1AnalysisContext {
    public static final String VERSION = "pp1-analysis-binding-v1";
    private static final Map<String, String> INHERITANCE = Map.of(
            "AUTOSOMAL_DOMINANT", "AD", "AUTOSOMAL_RECESSIVE", "AR", "X_LINKED_RECESSIVE", "XLR");
    private final ObjectMapper json;

    public Pp1AnalysisContext(ObjectMapper json) { this.json = Objects.requireNonNull(json); }

    public ObjectNode prepare(SelectedEvidencePreparation prepared, String candidate, String hypothesis,
            String sourceSnapshotPin, List<String> enabledCriteria) {
        return bind(prepared.pp1SelectionContext(candidate, hypothesis), candidate, hypothesis, sourceSnapshotPin, enabledCriteria);
    }

    ObjectNode bind(JsonNode context, String candidate, String hypothesis, String sourceSnapshotPin,
            List<String> enabledCriteria) {
        require(candidate != null && !candidate.isBlank() && hypothesis != null && !hypothesis.isBlank(),
                "PP1_SELECTED_ATTEMPT_REQUIRED");
        require(sourceSnapshotPin != null && sourceSnapshotPin.matches("[a-f0-9]{64}"), "PP1_SOURCE_SNAPSHOT_PIN_REQUIRED");
        require("GERMLINE".equals(context.at("/scope/analysisDomain").asText()), "PP1_GERMLINE_CONTEXT_REQUIRED");
        String gene = context.at("/gene/symbol").asText(), disease = context.at("/disease/mondoId").asText();
        require(!gene.isBlank() && disease.matches("MONDO:[0-9]{7}"), "PP1_EXACT_DISEASE_CONTEXT_REQUIRED");
        String moi = INHERITANCE.get(context.path("moi").asText());
        require(moi != null, "PP1_INHERITANCE_METHOD_UNSUPPORTED");
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        require(allele.isObject() && List.of("GRCh37", "GRCh38").contains(allele.path("assembly").asText())
                && allele.path("contig").asText().matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)")
                && allele.path("position").isIntegralNumber() && allele.path("position").canConvertToInt()
                && allele.path("position").asInt() > 0 && allele.path("reference").asText().matches("[ACGT]+")
                && allele.path("alternate").asText().matches("[ACGT]+")
                && !allele.path("reference").equals(allele.path("alternate")), "PP1_NORMALIZED_ALLELE_REQUIRED");
        if (!(enabledCriteria != null && enabledCriteria.contains("ACMG_AMP_2015:PP1")
                && enabledCriteria.stream().distinct().count() == enabledCriteria.size())) {
            throw new IllegalArgumentException("PP1_PROFILE_REQUIRED");
        }
        var identity = json.createObjectNode().put("schemaVersion", VERSION).put("candidateId", candidate)
                .put("hypothesisId", hypothesis).put("sourceSnapshotSha256", sourceSnapshotPin);
        identity.set("preparedContext", context.deepCopy());
        identity.set("enabledCriteria", json.valueToTree(enabledCriteria.stream().sorted().toList()));
        String selectionKey = new RuntimeEvidenceV2Adapter(json).canonicalSha256(identity);
        var selection = json.createObjectNode().put("selectionKey", selectionKey).put("geneSymbol", gene)
                .put("diseaseId", disease).put("inheritance", moi);
        selection.set("allele", allele.deepCopy());
        var ledger = json.createObjectNode().put("selectionKey", selectionKey);
        ledger.set("scope", selection.deepCopy().remove(List.of("selectionKey", "allele")));
        ledger.set("allele", allele.deepCopy());
        ledger.put("scopeStatement", "CURRENT_EVALUATION_ONLY_NOT_PATIENT_HISTORY_OR_AUTHOR_PP4");
        ledger.put("provenanceSha256", new RuntimeEvidenceV2Adapter(json).canonicalSha256(identity));
        ledger.putArray("eventIds"); ledger.putArray("exclusiveFactIds");
        // A later internal contribution snapshot may establish a complete inventory.
        // Neither an unchecked PP4 result nor omitting PP4 from a request proves zero.
        ledger.put("status", "UNKNOWN").put("reasonCode", "PP1_CURRENT_CONTRIBUTION_SOURCE_UNAVAILABLE");
        ledger.putNull("pp4Points");
        var out = json.createObjectNode().put("schemaVersion", VERSION);
        out.set("identity", identity); out.set("selection", selection); out.set("ledger", ledger);
        return out;
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }
}
