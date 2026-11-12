package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;

/** Current central-module transport binding, never a scientific qualification or a JSON issuer.
 * Capture is called only after the actual selected runtime returns; history can only verify it.
 * The full-module audit excludes this receipt itself, not the module's scientific/source facts.
 */
final class Bp1CurrentPublicBinding {
    static final String VERSION = "1.0.0";
    private static final String RECEIPT = "currentPublicBindingReceipt";
    private static final String METHOD = "TRANSPORT_JSON_VALUE_C14N_V1";
    private Bp1CurrentPublicBinding() { }

    static void capture(ObjectMapper mapper, ObjectNode raw, SelectedEvidencePreparation selected,
            String candidate, String hypothesis, String source, String pointer) {
        raw.remove(RECEIPT);
        raw.put("executionSourceSha256", source);
        var receipt = mapper.createObjectNode().put("schemaVersion", "bp1-current-runtime-binding-receipt-v1");
        try {
            receipt.set("binding", binding(mapper, raw, selected.context(candidate, hypothesis), candidate, hypothesis, source, pointer));
            receipt.put("status", "CAPTURED_FROM_CURRENT_SELECTED_RUNTIME");
        } catch (RuntimeException invalid) {
            receipt.put("status", "UNAVAILABLE").put("reasonCode", "CENTRAL_BP1_ACTUAL_MODULE_BINDING_UNAVAILABLE");
        }
        receipt.put("receiptSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(receipt));
        raw.set(RECEIPT, receipt);
    }

    static ObjectNode project(ObjectMapper mapper, JsonNode full, JsonNode wrapper, JsonNode criterion) {
        JsonNode module = full.at("/modules/bp1"), previous = criterion.at("/details/bp1Facts");
        // Old stored runs keep their old facts. They never acquire current authority on read.
        if (!module.has("currentPublicBindingVersion") && !full.at("/identity/bp1TruncatingConditionExecution").isObject()
                && !previous.has("currentBinding")) {
            var old = previous instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode().put("schemaVersion", "bp1-general-facts-v1");
            old.remove("currentBinding"); return old;
        }
        try {
            String candidate = wrapper.path("candidateId").asText(), hypothesis = wrapper.path("hypothesisId").asText();
            String source = full.path("sourceSnapshotSha256").asText(), pointer = criterion.at("/details/modulePointer").asText();
            JsonNode context = wrapper.path("context"), local = wrapper.path("localBatch");
            check(VERSION.equals(module.path("currentPublicBindingVersion").asText())
                    && source.equals(module.path("sourceSnapshotSha256").asText())
                    && full.at("/identity/bp1TruncatingConditionExecution").equals(module.at("/policy/truncatingConditionExecution"))
                    && full.path("analysisId").equals(full.at("/preparation/analysisId"))
                    && wrapper.path("candidateId").equals(local.path("candidateId"))
                    && wrapper.path("hypothesisId").equals(local.path("hypothesisId"))
                    && context.path("contextId").equals(local.path("contextId"))
                    && GeneralBp1Runtime.CRITERION.equals(criterion.path("criterionId").asText()));
            int enabled = 0, prepared = 0, wrappers = 0, modules = 0;
            for (var value : full.path("executionCriteria")) if (GeneralBp1Runtime.CRITERION.equals(value.asText())) enabled++;
            for (var value : full.path("results")) if (same(value, candidate, hypothesis)) wrappers++;
            for (var value : module.path("results")) if (same(value, candidate, hypothesis)) modules++;
            for (var value : full.at("/preparation/candidates")) if (candidate.equals(value.path("candidateId").asText()))
                for (var attempt : value.path("attempts")) if (hypothesis.equals(attempt.path("hypothesisId").asText())
                        && context.equals(attempt.path("context")) && attempt.path("blockers").isArray() && attempt.path("blockers").isEmpty()) prepared++;
            check(enabled == 1 && prepared == 1 && wrappers == 1 && modules == 1);
            JsonNode raw = full.at(pointer);
            check(raw instanceof ObjectNode && raw.path("policy").equals(module.path("policy"))
                    && savedCapabilityValid(mapper, full.at("/identity/bp1TruncatingConditionExecution"), raw)
                    && criterion.at("/details/originalResult").equals(raw.path("evaluation"))
                    && criterion.path("status").equals(raw.at("/evaluation/evaluationResult/status"))
                    && criterion.path("appliedStrength").equals(raw.at("/evaluation/appliedStrength"))
                    && criterion.path("reasonCode").equals(raw.at("/evaluation/evaluationResult/reasonCode")));
            JsonNode receipt = raw.path(RECEIPT); var unsigned = ((ObjectNode) receipt).deepCopy(); unsigned.remove("receiptSha256");
            check("bp1-current-runtime-binding-receipt-v1".equals(receipt.path("schemaVersion").asText())
                    && "CAPTURED_FROM_CURRENT_SELECTED_RUNTIME".equals(receipt.path("status").asText())
                    && new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(unsigned).equals(receipt.path("receiptSha256").asText()));
            var actual = binding(mapper, (ObjectNode) raw, context, candidate, hypothesis, source, pointer);
            check(actual.equals(receipt.path("binding")));
            var summary = GeneralBp1Runtime.publicSummary(((ObjectNode) raw).deepCopy()); summary.set("currentBinding", actual); return summary;
        } catch (RuntimeException invalid) {
            return mapper.createObjectNode().put("schemaVersion", "bp1-general-facts-v1").put("clinicalValidationCompleted", false)
                    .put("publicProjectionIssue", "CENTRAL_BP1_MODULE_BINDING_INVALID");
        }
    }

    /** Frozen transport contracts from 0fec943a/d8bc8d1e, 514617e6 and 01eec0cb.
     * History is verified against its own complete identity, never today's runtime.
     * The retired 1.1 no-LoF template cannot qualify a truncating observation.
     */
    private static boolean savedCapabilityValid(ObjectMapper mapper, JsonNode saved, JsonNode raw) {
        String version = saved.path("methodVersion").asText();
        if (!Set.of("1.0.0", "1.1.0", "1.2.0").contains(version)) return false;
        var expected = mapper.createObjectNode().put("schemaVersion", "bp1-truncating-condition-capability-v1")
                .put("methodVersion", version).put("sequenceObservation", "SOURCE_BOUND_CODING_TRUNCATION_CANDIDATE")
                .put("establishedLofContraindicationSupported", true)
                .put("noEstablishedLofCorrelationQualificationSupported", "1.1.0".equals(version));
        if (!"1.0.0".equals(version)) expected
                .put("noLofSourceGrammar", "1.1.0".equals(version)
                        ? "EXPLICIT_GENE_WIDE_GERMLINE_REVIEW_WITH_NATIVE_VALIDITY_V1"
                        : "NATIVE_REVIEW_INTERPRETATION_PENDING_SOURCE_REPLAY_ONLY_V1")
                .put("sourceDefinedTruncationMeansExperimentalLof", false);
        expected.put("missingRecordMeansNoCorrelation", false).put("createsReviewReceipt", false)
                .put("runtimeDispatch", "EXACT_SOURCE_SUBTYPE_BEFORE_LEGACY_MISSENSE_READY_V1")
                .put("pureMissenseApplicability", "bp1-truncating-applicability-v1")
                .put("publicProjection", "BOUNDED_BP1_TRUNCATING_CONDITION_V1")
                .put("codingProjectionScope", "TRUNCATING_SUBTYPE_ONLY");
        expected.put("canonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(expected));
        if (!expected.equals(saved)) return false;
        if (!"1.1.0".equals(version)) return true;
        JsonNode condition = raw.path("truncatingConditionCheck");
        if (condition.isMissingNode()) {
            String type = raw.at("/publishedKnowledge/record/interpretation/type").asText();
            return Bp1PublishedPolicy.TYPE.equals(type) || Bp1RegionPolicy.TYPE.equals(type);
        }
        // This old source branch only established that the truncating rule did not
        // apply to a verified missense. It must not acquire a MET on historical read.
        return "bp1-truncating-applicability-v1".equals(condition.path("schemaVersion").asText())
                && "NOT_APPLICABLE".equals(condition.path("status").asText())
                && "BP1_TRUNCATING_RULE_NOT_APPLICABLE_TO_PURE_MISSENSE".equals(condition.path("reasonCode").asText())
                && "VERIFIED_PURE_CODING_MISSENSE".equals(condition.at("/available/variantEffect").asText())
                && condition.path("scientificQualificationGranted").isBoolean()
                && !condition.path("scientificQualificationGranted").asBoolean()
                && !"MET".equals(raw.path("status").asText());
    }

    private static ObjectNode binding(ObjectMapper mapper, ObjectNode raw, JsonNode original, String candidate,
            String hypothesis, String source, String pointer) {
        check(source != null && source.matches("[a-f0-9]{64}") && source.equals(raw.path("executionSourceSha256").asText())
                && !candidate.isBlank() && !hypothesis.isBlank() && same(raw, candidate, hypothesis)
                && pointer.matches("/modules/bp1/results/(?:0|[1-9][0-9]{0,5})"));
        var adapter = new RuntimeEvidenceV2Adapter(mapper); var projection = raw.path("sourceProjection");
        check("selected-legacy-context-projection-v1".equals(projection.path("schemaVersion").asText())
                && original.isObject() && original.equals(projection.path("originalContext"))
                && candidate.equals(projection.path("candidateId").asText()) && hypothesis.equals(projection.path("hypothesisId").asText())
                && projection.path("evaluationContext").isObject());
        adapter.validateVersionedContext(original); adapter.validateCompleteContext((ObjectNode) projection.path("evaluationContext"));
        var projected = projection.path("evaluationContext"); var unsigned = ((ObjectNode) projection).deepCopy(); unsigned.remove("bindingSha256");
        check(adapter.canonicalSha256(unsigned).equals(projection.path("bindingSha256").asText())
                && adapter.canonicalContextId(projected).equals(raw.path("contextId").asText())
                && raw.path("contextId").equals(projected.path("contextId"))
                && original.at("/variant/evidenceQueryAllele").equals(projected.at("/variant/evidenceQueryAllele"))
                && GeneralBp1Runtime.CRITERION.equals(raw.at("/evaluation/criterionId").asText())
                && raw.path("status").equals(raw.at("/evaluation/evaluationResult/status"))
                && raw.path("reasonCode").equals(raw.at("/evaluation/evaluationResult/reasonCode")));
        String status = raw.path("status").asText(), strength = raw.at("/evaluation/appliedStrength").asText();
        check(Set.of("MET", "NOT_MET", "NOT_EVALUATED").contains(status)
                && ("MET".equals(status) ? Set.of("SUPPORTING", "STRONG").contains(strength) : "NONE".equals(strength)));
        var body = raw.deepCopy(); body.remove(RECEIPT);
        var out = mapper.createObjectNode().put("schemaVersion", "bp1-current-module-binding-v1")
                .put("executionSourceSha256", source).put("candidateId", candidate).put("hypothesisId", hypothesis)
                .put("originalContextId", original.path("contextId").asText()).put("evaluationContextId", projected.path("contextId").asText())
                .put("originalContextSha256", adapter.canonicalSha256(original)).put("evaluationContextSha256", adapter.canonicalSha256(projected))
                .put("projectionBindingSha256", projection.path("bindingSha256").asText())
                .put("queryAlleleSha256", adapter.canonicalSha256(projected.at("/variant/evidenceQueryAllele")))
                .put("modulePointer", pointer).put("auditHashMethod", METHOD).put("moduleHashScope", "ACTUAL_MODULE_EXCLUDING_CURRENT_BINDING_RECEIPT")
                .put("actualModuleSha256", PublishedPm1CrossCriterionConditions.currentAuditSha256(mapper, body))
                .put("actualEvaluationSha256", PublishedPm1CrossCriterionConditions.currentAuditSha256(mapper, raw.path("evaluation")))
                .put("status", status).put("appliedStrength", strength).put("reasonCode", raw.path("reasonCode").asText());
        var pins = out.putObject("observedSourcePins");
        for (var pair : List.of(new String[]{"specificationKey", "/publishedKnowledge/record/specificationKey"},
                new String[]{"sourceRawSha256", "/publishedKnowledge/specification/source/rawSha256"},
                new String[]{"entitySha256", "/publishedKnowledge/specification/entitySha256"},
                new String[]{"clausePointer", "/publishedKnowledge/record/clausePointer"},
                new String[]{"clauseSha256", "/publishedKnowledge/record/clauseSha256"},
                new String[]{"knowledgeRawSha256", "/publishedKnowledge/binding/rawSha256"},
                new String[]{"modelCanonicalSha256", "/sequenceMaterial/modelCanonicalSha256"},
                new String[]{"structureModelSha256", "/structureMaterial/modelCanonicalSha256"})) {
            JsonNode value = raw.at(pair[1]); if (value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 256) pins.set(pair[0], value.deepCopy());
        }
        if (raw.at("/truncatingConditionCheck/structuredLof").isObject()) {
            var structured = GeneralBp1Runtime.publicSummary(raw).at("/truncatingConditionCheck/structuredLof");
            check(structured.isObject());
            pins.put("structuredLofSha256", adapter.canonicalSha256(structured));
        }
        return out;
    }
    private static boolean same(JsonNode value, String candidate, String hypothesis) {
        return candidate.equals(value.path("candidateId").asText()) && hypothesis.equals(value.path("hypothesisId").asText());
    }
    private static void check(boolean condition) { if (!condition) throw new IllegalArgumentException("CENTRAL_BP1_MODULE_BINDING_INVALID"); }
}
