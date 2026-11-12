package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks.CatalogSnapshot;
import org.springframework.lang.Nullable;

/** Local computational BP7 only. Reuses the original server-owned prediction plan and captured
 * experiment catalog; no model, network, database or resource I/O. Never derive the trusted plan
 * from submitted result JSON. Saved outputs are records, not reusable source qualifications. */
public final class Bp7ComputationalRuntime {
    public static final String VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter identities;
    private final Bp7PositionAssessment computation;
    private final ObjectNode policy;

    public Bp7ComputationalRuntime(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        identities = new RuntimeEvidenceV2Adapter(mapper);
        computation = new Bp7PositionAssessment(mapper);
        policy = new Bp7PredictionDependency(mapper).policy();
    }

    public ObjectNode evaluate(@Nullable ObjectNode preparedPlan, @Nullable VariantInterpretation candidate,
                               @Nullable ObjectNode savedPrediction, @Nullable CatalogSnapshot catalog) {
        var checks = computation.inspect(preparedPlan, candidate, savedPrediction);
        var experiments = assessExperiments(preparedPlan, catalog);
        var effects = assessEffects(preparedPlan);
        var out = mapper.createObjectNode().put("schemaVersion", "bp7-local-computational-result-v1")
                .put("methodVersion", VERSION).put("criterionId", "ACMG_AMP_2015:BP7")
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE")
                .put("stage", "CORE_RESULT_ONLY").put("finalEvaluationProduced", true)
                .put("clinicalAdoption", false).put("finalClassificationEnabled", false)
                .put("automaticComputationalApplication", false).put("providerCalls", 0);
        out.set("policy", policy.deepCopy()); out.put("policySha256", identities.canonicalSha256(policy));
        out.set("computationalChecks", checks); out.set("experimentalConflictCheck", experiments); out.set("functionalEffectCheck", effects);
        if (preparedPlan != null) {
            try {
                identities.canonicalSha256(preparedPlan.path("context"));
                out.set("context", preparedPlan.path("context").deepCopy());
            } catch (IllegalArgumentException invalid) { out.put("contextCaptured", false); }
            out.put("candidateId", preparedPlan.path("candidateId").asText());
        }
        var limitations = out.putArray("limitations");
        limitations.add("PROJECT_LOCAL_COMPUTATIONAL_POLICY_NOT_CLINICAL_CLASSIFICATION")
                .add("EXPERIMENT_CATALOG_NOT_COMPREHENSIVE_NO_RECORD_IS_NOT_NORMAL")
                .add("LOCAL_FUNCTIONAL_REVIEW_SCOPE_ONLY_EXTERNAL_EXPERIMENTS_NOT_EXHAUSTIVE")
                .add("NO_RNA_EXPERIMENTAL_STRENGTH_OR_DISEASE_CAUSALITY_INFERRED");
        String state = checks.path("status").asText();
        if ("NOT_APPLICABLE".equals(state)) out.put("status", "NOT_APPLICABLE").put("reasonCode", checks.path("reasonCode").asText());
        else if ("DEPENDENCY_NOT_SATISFIED".equals(state)) out.put("status", "NOT_MET").put("reasonCode", checks.path("reasonCode").asText());
        else if (!"READY_FOR_EXPERIMENTAL_CONFLICT_CHECK".equals(state)) out.put("reasonCode", checks.path("reasonCode").asText());
        else if (!"NO_MATCH_IN_DECLARED_CATALOG".equals(experiments.path("status").asText()))
            out.put("reasonCode", experiments.path("reasonCode").asText());
        else if (!"NO_LINKED_SPLICE_RESTRICTION".equals(effects.path("status").asText()))
            out.put("reasonCode", effects.path("reasonCode").asText());
        else out.put("status", "MET").put("appliedStrength", "SUPPORTING")
                .put("automaticComputationalApplication", true).put("reasonCode", "BP7_LOCAL_COMPUTATIONAL_REQUIREMENTS_MET");
        var dependency = checks.at("/predictionDependency/dependency");
        if (dependency.isObject()) {
            var provenance = out.putObject("sharedPrediction").put("additionalPredictionPerformed", false)
                    .put("relationship", "BP4_SPLICE_PREREQUISITE_PLUS_BP7_POSITION_PRIOR")
                    .put("automaticCombinedClassification", false);
            for (String key : List.of("selectedPredictionResultId", "rawQueryId", "rawObservationSha256", "selectedTranscriptId"))
                provenance.set(key, dependency.path(key).deepCopy());
        }
        boolean review = "REVIEW_REQUIRED".equals(experiments.path("status").asText()) || "REVIEW_REQUIRED".equals(effects.path("status").asText());
        out.put("reviewRequired", review);
        out.put("resultId", "bp7-local-sha256-" + identities.canonicalSha256(out));
        return out;
    }

    /** A conflict is a molecular observation requiring review, never a disease/pathogenicity call.
     * Same allele with a different transcript version is unresolved, not silently absent. */
    public ObjectNode assessExperiments(@Nullable ObjectNode plan, @Nullable CatalogSnapshot snapshot) {
        var out = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "BP7_EXPERIMENT_SOURCE_UNAVAILABLE")
                .put("experimentalNormalityEstablished", false).put("diseaseApplicability", "NOT_ASSESSED_NO_CLINICAL_INFERENCE")
                .put("externalWorldCovered", false);
        var matches = out.putArray("matches");
        if (snapshot == null) return out;
        var source = snapshot.content(); out.set("sourceSnapshot", source);
        var material = source.path("material");
        if (!"AVAILABLE".equals(material.path("status").asText())) {
            out.put("sourceReasonCode", material.path("reasonCode").asText()); return out;
        }
        try {
            require(plan != null, "BP7_EXPERIMENT_CONTEXT_REQUIRED");
            if (plan == null) return out;
            var context = plan.path("context"); identities.validateCompleteContext(context);
            require(identities.canonicalContextId(context).equals(context.path("contextId").asText()), "BP7_EXPERIMENT_CONTEXT_INVALID");
            var allele = context.at("/variant/evidenceQueryAllele");
            require(same(allele, plan.path("queryAllele")) && "GRCh38".equals(allele.path("assembly").asText())
                    && "NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()), "BP7_EXPERIMENT_QUERY_MISMATCH");
            out.put("contextId", context.path("contextId").asText()); out.set("queryAllele", allele.deepCopy());
            String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
            boolean exact = false;
            for (var record : material.at("/catalog/records")) {
                if (!alleleMatches(allele, record.path("allele"))) continue;
                var mapping = record.path("transcriptMapping");
                boolean applicable = (tx.equals(mapping.path("ensembl").asText()) || tx.equals(mapping.path("refseq").asText()))
                        && context.at("/gene/hgncId").equals(record.path("geneId"))
                        && context.at("/gene/symbol").equals(record.path("geneSymbol"));
                var match = matches.addObject().put("molecularMatch", applicable ? "EXACT_ALLELE_GENE_TRANSCRIPT" : "ALLELE_ONLY_APPLICABILITY_UNRESOLVED")
                        .put("clinicalApproval", false);
                match.set("record", record.deepCopy()); exact |= applicable;
            }
            if (!matches.isEmpty()) return out.put("status", "REVIEW_REQUIRED").put("reasonCode", exact
                    ? "BP7_RECORDED_ALTERED_SPLICING_REVIEW_REQUIRED" : "BP7_EXPERIMENT_TRANSCRIPT_OR_GENE_UNRESOLVED");
            return out.put("status", "NO_MATCH_IN_DECLARED_CATALOG").put("reasonCode", "BP7_EXPERIMENT_NOT_COVERED")
                    .put("coverageDisposition", "NOT_COVERED_NOT_EXPERIMENTALLY_NORMAL");
        } catch (IllegalArgumentException invalid) {
            return out.put("reasonCode", code(invalid, "BP7_EXPERIMENT_CONTEXT_INVALID"));
        }
    }

    /** Reuses the original server-resolved functional/review projection. It has no RNA-normal
     * adoption route: unknown or observed splice effects need review, even when not yet adopted. */
    private ObjectNode assessEffects(@Nullable ObjectNode plan) {
        var out = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "BP7_FUNCTIONAL_REFERENCES_UNAVAILABLE");
        if (plan == null) return out;
        var refs = plan.path("effectRestrictions");
        try {
            identities.canonicalSha256(refs); out.set("source", refs.deepCopy());
            require("VERIFIED_SAVED_REFERENCES".equals(refs.path("status").asText())
                    && "COMPLETE_FOR_DECLARED_SCOPE".equals(refs.path("coverage").asText())
                    && refs.path("contextId").equals(plan.at("/context/contextId"))
                    && refs.path("sourceSha256").asText().matches("[0-9a-f]{64}")
                    && refs.path("references").isArray(), "BP7_FUNCTIONAL_REFERENCES_UNAVAILABLE");
            require("UNSPECIFIED".equals(refs.path("externalDeclaration").asText())
                    && refs.path("externalWorldCovered").isBoolean() && !refs.path("externalWorldCovered").asBoolean(), "BP7_EXTERNAL_EXPERIMENT_DECLARATION_UNRESOLVED");
            require(refs.at("/scope/candidateId").equals(plan.path("candidateId"))
                    && refs.at("/scope/contextId").equals(plan.at("/context/contextId"))
                    && same(refs.at("/scope/queryAllele"), plan.path("queryAllele")), "BP7_FUNCTIONAL_REFERENCE_SCOPE_MISMATCH");
            String blocker = null;
            for (var ref : refs.path("references")) {
                String usage = ref.path("usage").asText(), action = ref.path("action").asText(), mechanism = ref.path("mechanism").asText();
                require(!ref.path("savedEvidenceId").asText().isBlank() && !ref.path("effectId").asText().isBlank()
                        && (("EXPLICITLY_ADOPTED".equals(usage) && "BLOCK_DUPLICATE".equals(action))
                        || ("OBSERVED_NOT_ADOPTED".equals(usage) && "ALLOW".equals(action))), "BP7_FUNCTIONAL_REFERENCE_INVALID");
                if (!"PROTEIN_MISSENSE".equals(mechanism)) blocker = "SPLICING".equals(mechanism)
                        ? "BP7_SPLICE_EXPERIMENT_REVIEW_REQUIRED" : "BP7_EXPERIMENT_MECHANISM_UNRESOLVED";
            }
            if (blocker != null) return out.put("status", "REVIEW_REQUIRED").put("reasonCode", blocker);
            return out.put("status", "NO_LINKED_SPLICE_RESTRICTION").put("reasonCode", "BP7_LOCAL_FUNCTIONAL_SCOPE_CHECKED");
        } catch (IllegalArgumentException invalid) { return out.put("reasonCode", code(invalid, "BP7_FUNCTIONAL_REFERENCE_INVALID")); }
    }

    private static boolean alleleMatches(JsonNode a, JsonNode b) {
        return a.path("position").isIntegralNumber() && a.path("position").asLong() == b.path("position").asLong()
                && List.of("assembly", "contig", "reference", "alternate").stream().allMatch(k -> a.path(k).equals(b.path(k)));
    }
    private boolean same(JsonNode a, JsonNode b) { return !a.isMissingNode() && !b.isMissingNode() && identities.canonicalSha256(a).equals(identities.canonicalSha256(b)); }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
    private static String code(IllegalArgumentException invalid, String fallback) {
        String message = invalid.getMessage(); return message != null && message.matches("[A-Z][A-Z0-9_]+") ? message : fallback;
    }
}
