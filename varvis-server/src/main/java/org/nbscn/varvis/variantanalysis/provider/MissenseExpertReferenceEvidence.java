package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Binds pinned source facts; optional policy qualification preserves, never reclassifies, the original expert assertion. */
final class MissenseExpertReferenceEvidence {
    private MissenseExpertReferenceEvidence() { }

    static void bind(ObjectNode audit, JsonNode packet, ObjectNode pins, Set<String> evidenceIds) {
        bind(audit, packet, pins, evidenceIds, evidenceIds);
    }

    static void bind(ObjectNode audit, JsonNode packet, ObjectNode pins, Set<String> evidenceIds, Set<String> registeredSourceIds) {
        require("missense-expert-reference-evidence-v1".equals(packet.path("schemaVersion").asText())
                && packet.path("draft").isBoolean() && packet.path("draft").asBoolean() && unreviewed(packet),
                "EXPERT_EVIDENCE_DRAFT_REQUIRED");
        require(packet.path("references").isArray() && packet.path("references").size() <= 256,
                "EXPERT_EVIDENCE_REFERENCE_COUNT_INVALID");
        Map<String, JsonNode> rows = new HashMap<>();
        for (JsonNode row : packet.path("references")) {
            String id = row.path("referenceId").asText();
            require(MissenseComparisonPolicy.text(row.path("referenceId")) && rows.putIfAbsent(id, row) == null,
                    "EXPERT_EVIDENCE_REFERENCE_ID_INVALID");
        }
        ObjectNode registered = audit.objectNode();
        ArrayNode sources = registered.putArray("sources");
        for (String id : registeredSourceIds) sources.addObject().put("sourceId", id);
        for (JsonNode value : audit.path("references")) {
            ObjectNode reference = (ObjectNode) value;
            if (!reference.has("expertEvidenceReferenceId")) continue;
            JsonNode row = rows.get(reference.path("expertEvidenceReferenceId").asText());
            require(row != null, "EXPERT_EVIDENCE_REFERENCE_NOT_FOUND");
            if (row == null) throw new IllegalArgumentException("EXPERT_EVIDENCE_REFERENCE_NOT_FOUND");
            if (MissenseExpertClassificationAdoption.sourceVerified(reference))
                MissenseExpertClassificationAdoption.deriveSourceFacts(reference, row,
                        new RuntimeEvidenceV2Adapter(new com.fasterxml.jackson.databind.ObjectMapper()).canonicalSha256(row));
            String bindingIssue = rowIssue(reference, row, registered);
            require(bindingIssue == null, bindingIssue == null ? "EXPERT_EVIDENCE_IDENTITY_MISMATCH" : bindingIssue);
            Set<String> ids = new HashSet<>();
            require(row.path("sourceIds").isArray() && !row.path("sourceIds").isEmpty() && row.path("sourceIds").size() <= 64,
                    "EXPERT_EVIDENCE_SOURCE_UNBOUND");
            ArrayNode declared = (ArrayNode) reference.path("sourceIds");
            for (JsonNode id : row.path("sourceIds")) {
                require(MissenseComparisonPolicy.text(id) && ids.add(id.asText()) && evidenceIds.contains(id.asText()),
                        "EXPERT_EVIDENCE_SOURCE_UNBOUND");
                boolean present = false;
                for (JsonNode existing : declared) if (existing.equals(id)) present = true;
                if (!present) declared.add(id.deepCopy());
            }
            ObjectNode attached = reference.putObject("expertEvidence")
                    .put("use", MissenseExpertClassificationAdoption.sourceVerified(reference)
                            ? "SOURCE_FACT_QUALIFICATION_ORIGINAL_RETAINED" : "SOURCE_NAVIGATION_ONLY")
                    .put("locatorValidation", "DECLARED_LOCATORS_RETAINED_NOT_REINTERPRETED");
            attached.set("packetPins", pins.deepCopy()); attached.set("record", row.deepCopy());
        }
    }

    /** Checked again at the material/runtime boundary; a packet cannot silently select an adoption policy. */
    @Nullable static String issue(JsonNode reference, JsonNode policy) {
        for (JsonNode mapping : reference.path("conditionMappings")) {
            if ("SOURCE_VERIFIED_EXACT".equals(mapping.path("status").asText())
                    && (!MissenseExpertClassificationAdoption.sourceVerified(reference)
                        || !mapping.equals(MissenseExpertClassificationAdoption.sourceCondition(reference, reference.at("/expertEvidence/record")))))
                return "EXPERT_DERIVED_CONDITION_BINDING_INVALID";
        }
        if (reference.has("originalSpecificationBinding") && !reference.has("expertClassificationAdoption"))
            return "EXPERT_ORIGINAL_SPECIFICATION_ROUTE_REQUIRED";
        if (reference.has("expertClassificationAdoption")) {
            if (!reference.has("expertEvidence")) return "EXPERT_EVIDENCE_BINDING_INVALID";
            String enabled = MissenseExpertClassificationAdoption.enabledIssue(policy);
            if (enabled != null) return enabled;
            if (!reference.path("expertClassificationAdoption").equals(policy.at("/expertSourceAdoption/mode")))
                return "EXPERT_CLASSIFICATION_ROUTE_MISMATCH";
            String panelIssue = MissenseExpertClassificationAdoption.panelIssue(reference, policy);
            if (panelIssue != null) return panelIssue;
        }
        if (!reference.has("expertEvidence") && !reference.has("expertEvidenceReferenceId")) return null;
        JsonNode attached = reference.path("expertEvidence"), row = attached.path("record");
        if (!(MissenseExpertClassificationAdoption.sourceVerified(reference)
                ? "SOURCE_FACT_QUALIFICATION_ORIGINAL_RETAINED" : "SOURCE_NAVIGATION_ONLY").equals(attached.path("use").asText())
                || !MissenseComparisonPolicy.hash(attached.at("/packetPins/rawSha256"))
                || !MissenseComparisonPolicy.hash(attached.at("/packetPins/canonicalSha256"))
                || !row.path("referenceId").equals(reference.path("expertEvidenceReferenceId")))
            return "EXPERT_EVIDENCE_BINDING_INVALID";
        String issue = rowIssue(reference, row, policy);
        if (issue != null) return issue;
        issue = MissenseExpertClassificationAdoption.methodSourceIssue(reference, policy);
        if (issue != null) return issue;
        if (!MissenseComparisonPolicy.boundSources(policy, row.path("sourceIds"))) return "EXPERT_EVIDENCE_SOURCE_UNBOUND";
        return null;
    }

    @Nullable private static String rowIssue(JsonNode reference, JsonNode row, JsonNode policy) {
        if (!unreviewed(row))
            return "EXPERT_EVIDENCE_DRAFT_REQUIRED";
        if (!"EXACT_SOURCE_BOUND".equals(row.path("bindingStatus").asText())) {
            if (!"EXACT_RECORD_BOUND_CONDITION_UNRESOLVED".equals(row.path("bindingStatus").asText()))
                return "EXPERT_EVIDENCE_DRAFT_REQUIRED";
            // The original packet stays unresolved and immutable. Resolve only this
            // adoption's exact SCV condition through the existing reviewed mapping.
            String mappingIssue = MissenseConditionMapping.issue(reference.path("conditionMappings"), policy);
            if (mappingIssue != null) return mappingIssue;
            JsonNode condition = reference.at("/sourceAssertion/condition");
            if (!condition.isObject()
                    || condition.has("submittedRaw") && !condition.path("submittedRaw").isTextual()
                    || condition.has("reportedRaw") && !condition.path("reportedRaw").isTextual())
                return "EXPERT_EVIDENCE_CONDITION_MAPPING_MISMATCH";
            String mapped = MissenseConditionMapping.resolve(condition.path("submittedRaw").asText(),
                    condition.path("reportedRaw").asText(), reference.path("conditionMappings"));
            if (mapped.isEmpty() || !mapped.equals(reference.path("mondoId").asText())
                    || !mapped.equals(row.at("/disease/id").asText()))
                return "EXPERT_EVIDENCE_CONDITION_MAPPING_MISMATCH";
        }
        if (!row.path("allele").equals(reference.path("allele"))
                || !row.path("variationId").equals(reference.path("variationId")))
            return "EXPERT_EVIDENCE_IDENTITY_MISMATCH";
        JsonNode assertion = row.path("originalAssertion"), original = reference.path("sourceAssertion");
        for (String key : List.of("scv", "classification", "reviewStatus", "dateLastEvaluated"))
            if (!MissenseComparisonPolicy.text(assertion.path(key)) || !MissenseComparisonPolicy.text(original.path(key)))
                return "EXPERT_EVIDENCE_ASSERTION_MISMATCH";
        // XML and the submission summary encode the same date/capitalization differently.
        // Compare only these representations; keep both complete source objects untouched.
        String date = ClinVarAssertionDate.iso(assertion.path("dateLastEvaluated").asText());
        if (!assertion.path("scv").equals(original.path("scv"))
                || !assertion.path("reviewStatus").equals(original.path("reviewStatus"))
                || !assertion.path("classification").asText().equalsIgnoreCase(original.path("classification").asText())
                || date.isEmpty() || !date.equals(ClinVarAssertionDate.iso(original.path("dateLastEvaluated").asText())))
            return "EXPERT_EVIDENCE_ASSERTION_MISMATCH";
        boolean sameGene = false;
        for (JsonNode gene : row.path("geneSymbols")) if (gene.equals(reference.path("geneSymbol"))) sameGene = true;
        if (!sameGene) return "EXPERT_EVIDENCE_IDENTITY_MISMATCH";
        if (reference.has("expertClassificationAdoption"))
            return MissenseExpertClassificationAdoption.sourceIssue(reference, row);
        // Without an explicit reviewed source-preservation policy, the separate reassessment remains required.
        if (!reference.has("qualification")) return "EXPERT_EVIDENCE_INDEPENDENT_REVIEW_REQUIRED";
        return null;
    }

    private static boolean unreviewed(JsonNode node) {
        return "UNREVIEWED".equals(node.path("reviewStatus").asText())
                && node.path("scientificAdoption").isBoolean() && !node.path("scientificAdoption").asBoolean();
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
