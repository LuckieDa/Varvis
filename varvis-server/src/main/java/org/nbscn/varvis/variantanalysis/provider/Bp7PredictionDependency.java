package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/**
 * Offline dependency check for a server-owned selected-prediction plan and its saved row.
 * Hashes establish integrity, not source approval. The caller must supply the original prepared
 * plan from validated selected evidence, never a plan extracted from an untrusted result.
 * No position decision, experiment adoption, final BP7 evaluation, or provider call is made here.
 */
public final class Bp7PredictionDependency {
    public static final String VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode policy;

    public Bp7PredictionDependency(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        policy = PredictionDevelopmentRuntime.load(mapper, "bp7-local-computational-policy-v1.json");
    }

    public ObjectNode policy() { return policy.deepCopy(); }

    public ObjectNode inspect(@Nullable ObjectNode preparedPlan, @Nullable VariantInterpretation candidate,
                              @Nullable ObjectNode savedRow) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp7-prediction-dependency-v1")
                .put("adapterVersion", VERSION).put("criterionId", "ACMG_AMP_2015:BP7")
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE")
                .put("finalEvaluationProduced", false).put("providerCalls", 0)
                .put("positionCheck", "PENDING").put("experimentalConflictCheck", "PENDING");
        out.set("policy", policy());
        out.put("policySha256", adapter.canonicalSha256(policy));
        try {
            require(preparedPlan != null && candidate != null, "BP7_PREPARED_CONTEXT_REQUIRED");
            if (preparedPlan == null || candidate == null) return finish(out);
            require("selected-prediction-plan-v1".equals(preparedPlan.path("schemaVersion").asText())
                    && "PREPARED".equals(preparedPlan.path("status").asText()), "BP7_PREPARED_PLAN_UNAVAILABLE");
            require(preparedPlan.path("context") instanceof ObjectNode, "BP7_PREPARED_CONTEXT_REQUIRED");
            var context = (ObjectNode) preparedPlan.path("context");
            adapter.validateCompleteContext(context);
            require(adapter.canonicalContextId(context).equals(context.path("contextId").asText()), "BP7_CONTEXT_IDENTITY_INVALID");
            out.put("contextId", context.path("contextId").asText());
            out.put("preparedPlanSha256", adapter.canonicalSha256(preparedPlan));
            var tx = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            require(tx != null, "BP7_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            if (tx == null) return finish(out);
            Set<String> terms = new java.util.HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]")));
            boolean synonymous = terms.contains("synonymous_variant"), intronic = terms.contains("intron_variant");
            require(!(synonymous && intronic), "BP7_CONSEQUENCE_AMBIGUOUS");
            Set<String> allowed = Set.of("synonymous_variant", "intron_variant", "splice_region_variant",
                    "splice_donor_region_variant", "splice_polypyrimidine_tract_variant");
            if (!(synonymous || intronic) || !allowed.containsAll(terms))
                return finish(out.put("status", "NOT_APPLICABLE").put("reasonCode", "BP7_CONSEQUENCE_NOT_APPLICABLE"));
            require("EXISTING_DEVELOPMENT_CORE".equals(preparedPlan.path("route").asText()), "BP7_SPECIALIZED_OR_OTHER_ROUTE_REQUIRES_ADAPTER");
            require("SPLICING".equals(preparedPlan.path("primaryMechanism").asText())
                    && preparedPlan.path("requiredBp4Mechanisms").equals(mapper.createArrayNode().add("SPLICING")),
                    "BP7_SPLICING_BP4_PLAN_REQUIRED");
            require(!"BLOCKED".equals(preparedPlan.at("/developmentPreparation/status").asText()), "BP7_PREDICTION_PREPARATION_BLOCKED");
            require(savedRow != null, "BP7_SAVED_PREDICTION_REQUIRED");
            if (savedRow == null) return finish(out);
            var content = savedRow.deepCopy(); content.remove("resultId");
            require(("selected-prediction-sha256-" + adapter.canonicalSha256(content)).equals(savedRow.path("resultId").asText()),
                    "BP7_SAVED_PREDICTION_IDENTITY_INVALID");
            require(same(preparedPlan, savedRow.path("plan")), "BP7_PREPARED_PLAN_MISMATCH");
            require(preparedPlan.path("sourceProjection").isObject()
                    && same(preparedPlan.path("sourceProjection"), savedRow.path("sourceProjection"))
                    && preparedPlan.path("candidateId").asText().equals(candidate.getCandidateId()), "BP7_SOURCE_PROJECTION_MISMATCH");
            var nativePlan = preparedPlan.path("nativeSplicePreparation");
            var nativeResult = savedRow.path("nativeSpliceResult");
            require(nativePlan.isObject() && "PREPARED".equals(nativePlan.path("status").asText()), "BP7_NATIVE_PREPARATION_UNAVAILABLE");
            require(same(nativePlan, nativeResult.path("plan")), "BP7_NATIVE_PREPARED_PLAN_MISMATCH");
            var identity = nativePlan.path("identity");
            var binding = policy.path("predictionBinding");
            require(binding.path("method").equals(identity.path("method"))
                    && binding.path("runtimeVersion").equals(identity.path("version"))
                    && binding.path("calibrationId").equals(identity.path("calibrationId"))
                    && binding.path("scoreMode").equals(identity.path("scoreMode"))
                    && binding.path("distance").equals(identity.path("distance"))
                    && binding.path("mask").equals(identity.path("mask")), "BP7_NATIVE_METHOD_INCOMPATIBLE");
            var key = candidate.getObservation() == null ? null : candidate.getObservation().getKey();
            require(key != null, "BP7_QUERY_ALLELE_REQUIRED");
            if (key == null) return finish(out);
            var allele = context.at("/variant/evidenceQueryAllele");
            require("NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                    && "GRCh38".equals(key.getGenomeAssembly()) && "GRCh38".equals(allele.path("assembly").asText())
                    && key.getReference().matches("[ACGT]") && key.getAlternate().matches("[ACGT]")
                    && !key.getReference().equals(key.getAlternate()), "BP7_NATIVE_GRCH38_SNV_REQUIRED");
            require(key.getChromosome().equals(allele.path("contig").asText()) && key.getPosition() == allele.path("position").asInt()
                    && key.getReference().equals(allele.path("reference").asText()) && key.getAlternate().equals(allele.path("alternate").asText())
                    && same(allele, preparedPlan.path("queryAllele")), "BP7_QUERY_ALLELE_MISMATCH");
            var model = nativePlan.path("annotation");
            var selected = NativeSpliceAiRuntime.selectedAnnotation(mapper, model);
            require(selected.equals(nativePlan.path("selectedAnnotation"))
                    && model.path("transcript").asText().equals(tx.getVersionedTranscriptId())
                    && model.path("geneSymbol").asText().equals(tx.getGeneSymbol())
                    && tx.getGeneId() != null && model.path("geneId").asText().replaceFirst("\\.[0-9]+$", "").equals(tx.getGeneId().replaceFirst("\\.[0-9]+$", ""))
                    && selected.path("contig").asText().equals(key.getChromosome())
                    && selected.path("start").asInt() <= key.getPosition() && key.getPosition() <= selected.path("end").asInt(),
                    "BP7_SELECTED_ANNOTATION_MISMATCH");
            var unit = mapper.createObjectNode(); unit.set("allele", allele.deepCopy()); unit.set("annotation", selected);
            require(("selected-" + adapter.canonicalSha256(unit)).equals(nativePlan.path("rawQueryId").asText()), "BP7_NATIVE_QUERY_IDENTITY_INVALID");
            var fact = nativeResult.path("observation");
            require("EXACT_SELECTED_ANNOTATION_MATCH".equals(nativeResult.path("associationStatus").asText())
                    && "HIT".equals(fact.path("status").asText()) && fact.path("exactAlleleMatch").isBoolean()
                    && fact.path("exactAlleleMatch").asBoolean(), "BP7_NATIVE_OBSERVATION_UNAVAILABLE");
            require(same(allele, fact.path("allele")) && nativePlan.path("rawQueryId").equals(fact.path("queryId")), "BP7_NATIVE_OBSERVATION_ALLELE_MISMATCH");
            var window = nativePlan.path("referenceWindow");
            require(window.path("start").isIntegralNumber() && window.path("start").asLong() == key.getPosition() - 9999L
                    && window.path("start").asLong() > 0 && window.path("length").isIntegralNumber() && window.path("length").asLong() == 19999
                    && window.path("sha256").asText().matches("[0-9a-f]{64}")
                    && context.at("/variant/normalization/referenceSequenceSha256").asText().matches("[0-9a-f]{64}")
                    && window.at("/qualifiedReference/expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")),
                    "BP7_REFERENCE_BINDING_INVALID");
            for (String name : List.of("start", "length", "sha256"))
                require(same(window.path(name), fact.path("referenceWindow").path(name)), "BP7_REFERENCE_WINDOW_MISMATCH");
            var score = NativeSpliceAiRuntime.associate(mapper, fact, model);
            var record = mapper.createObjectNode().put("geneSymbol", tx.getGeneSymbol()).put("geneId", tx.getGeneId())
                    .put("transcriptId", tx.getVersionedTranscriptId()); record.set("queryAllele", mapper.valueToTree(key));
            for (String kind : List.of("AG", "AL", "DG", "DL")) {
                var ds = score.at("/deltaScores/DS_" + kind); var dp = score.at("/deltaPositions/DP_" + kind);
                var number = PredictionCalibration.decimal(ds.asText());
                require(ds.isTextual() && number != null && number.signum() >= 0 && number.compareTo(BigDecimal.ONE) <= 0
                        && dp.isIntegralNumber() && dp.canConvertToInt() && Math.abs((long) dp.asInt()) <= 4999, "BP7_NATIVE_SCORE_FIELDS_INVALID");
                record.set("DS_" + kind, ds); record.set("DP_" + kind, dp);
            }
            require(same(record, nativeResult.path("associatedRecord")), "BP7_ASSOCIATED_RECORD_MISMATCH");
            var recalibrated = SpliceAiBusinessRuntime.calibrateAssociatedDevelopment(mapper, record, tx, key);
            require(same(recalibrated, nativeResult.path("mechanism")) && same(recalibrated, savedRow.at("/mechanisms/SPLICING")),
                    "BP7_SPLICE_CALIBRATION_MISMATCH");
            require("CALIBRATED".equals(recalibrated.path("status").asText()), "BP7_SPLICE_CALIBRATION_UNAVAILABLE");
            var replayed = PredictionMechanismApplication.apply(mapper, preparedPlan, savedRow.path("mechanisms"), null).path("BP4");
            require(same(replayed, savedRow.at("/criteria/BP4")), "BP7_SAVED_BP4_REPLAY_MISMATCH");
            var dependency = out.putObject("dependency").put("selectedPredictionResultId", savedRow.path("resultId").asText())
                    .put("nativeIdentitySha256", adapter.canonicalSha256(identity)).put("rawQueryId", nativePlan.path("rawQueryId").asText())
                    .put("rawObservationSha256", adapter.canonicalSha256(fact)).put("selectedTranscriptId", tx.getVersionedTranscriptId())
                    .put("sharedPrediction", true);
            dependency.set("bp4", replayed.deepCopy()); dependency.set("calibration", recalibrated);
            String status = replayed.path("status").asText();
            if ("MET".equals(status)) out.put("status", "READY_FOR_POSITION_AND_CONFLICT_CHECKS").put("reasonCode", "BP7_SAME_SOURCE_BP4_VALIDATED");
            else if ("NOT_MET".equals(status)) out.put("status", "DEPENDENCY_NOT_SATISFIED").put("reasonCode",
                    "UNINFORMATIVE".equals(recalibrated.path("direction").asText()) ? "BP7_SPLICE_SCORE_UNINFORMATIVE" : "BP7_SPLICE_NOT_BENIGN");
            else out.put("reasonCode", "BP7_BP4_NOT_EVALUATED").put("dependencyReasonCode", replayed.path("reasonCode").asText());
        } catch (IllegalArgumentException invalid) {
            String reason = invalid.getMessage();
            out.put("reasonCode", reason != null && reason.matches("[A-Z][A-Z0-9_]+") ? reason : "BP7_DEPENDENCY_INVALID");
        }
        return finish(out);
    }

    private ObjectNode finish(ObjectNode out) {
        out.put("dependencyId", "bp7-dependency-sha256-" + adapter.canonicalSha256(out)); return out;
    }
    // JSON storage does not preserve Jackson's IntNode versus LongNode implementation type.
    // The existing canonicalizer preserves integer values and rejects floating-point identities.
    private boolean same(JsonNode expected, JsonNode actual) {
        return !expected.isMissingNode() && !actual.isMissingNode()
                && adapter.canonicalSha256(expected).equals(adapter.canonicalSha256(actual));
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
