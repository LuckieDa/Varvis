package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Supplier;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Executes the admitted truncating/GOF source conditions without turning a
 * predicted premature stop or a missing disease record into benign evidence.
 * A gene-wide negative condition requires a separate source-issued qualification.
 * READY means finite source subconditions, not final BP1 adoption. The normal
 * runtime still owns current functional restriction checks and final strength.
 */
final class Bp1TruncatingAssessment {
    static final String VERSION = "1.2.0";
    private static final String CRITERION = "ACMG_AMP_2015:BP1";
    private Bp1TruncatingAssessment() { }

    static final class Result {
        private final ObjectNode facts;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private Result(ObjectNode facts) { this.facts = facts.deepCopy(); }
        ObjectNode snapshot() { return facts.deepCopy(); }
        ObjectNode publicSummary() { return ((ObjectNode) facts.path("publicSummary")).deepCopy(); }
        String status() { return facts.path("status").asText(); }
        String reasonCode() { return facts.path("reasonCode").asText(); }
    }

    static ObjectNode identity(ObjectMapper mapper) {
        return mapper.createObjectNode().put("schemaVersion", "bp1-truncating-condition-capability-v1")
                .put("methodVersion", VERSION).put("sequenceObservation", "SOURCE_BOUND_CODING_TRUNCATION_CANDIDATE")
                .put("establishedLofContraindicationSupported", true)
                .put("noEstablishedLofCorrelationQualificationSupported", false)
                .put("noLofSourceGrammar", "NATIVE_REVIEW_INTERPRETATION_PENDING_SOURCE_REPLAY_ONLY_V1")
                .put("sourceDefinedTruncationMeansExperimentalLof", false)
                .put("missingRecordMeansNoCorrelation", false).put("createsReviewReceipt", false);
    }

    static Result assess(ObjectMapper mapper, PublishedCriterionKnowledge knowledge, ObjectNode context,
            VariantInterpretation candidate, CodingTranscriptMaterialSupply.Material structure) {
        return assess(mapper, knowledge, context, candidate, structure, null);
    }

    static Result assess(ObjectMapper mapper, PublishedCriterionKnowledge knowledge, ObjectNode context,
            VariantInterpretation candidate, CodingTranscriptMaterialSupply.Material structure,
            @Nullable Supplier<ReviewedRuleBundle> structuredLofSource) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp1-truncating-condition-assessment-v1")
                .put("methodVersion", VERSION).put("criterionId", CRITERION)
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE")
                .put("reasonCode", "BP1_TRUNCATING_INPUT_UNAVAILABLE")
                .put("clinicalValidationCompleted", false).put("createsReviewReceipt", false)
                .put("scientificQualificationGranted", false);
        var required = out.putObject("required");
        required.put("publishedRule", Bp1PublishedPolicy.TRUNCATING_TYPE)
                .put("variantConsequence", "SOURCE_DEFINED_PREDICTED_CODING_TRUNCATION")
                .put("geneLofDiseaseCorrelation", "NO_ESTABLISHED_CORRELATION")
                .put("setting", "GERMLINE_RASOPATHY")
                .put("missingRecordMeansNoCorrelation", false);
        var available = out.putObject("available");
        available.put("variantConsequence", "NOT_VERIFIED")
                .put("geneLofDiseaseCorrelation", "UNKNOWN")
                .put("functionalLossEstablishedBySequence", false);
        out.putArray("missing");
        try {
            if (!(knowledge != null && context != null && candidate != null && structure != null)) {
                throw new IllegalArgumentException("BP1_TRUNCATING_INPUT_UNAVAILABLE");
            }
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            adapter.validateCompleteContext(context);
            require(adapter.canonicalContextId(context).equals(context.path("contextId").asText()),
                    "BP1_TRUNCATING_CONTEXT_MISMATCH");
            out.put("contextId", context.path("contextId").asText())
                    .put("contextCanonicalSha256", adapter.canonicalSha256(context))
                    .put("candidateId", candidate.getCandidateId());
            out.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
            var model = structure.model();
            if (!structure.ready() || model == null)
                throw new IllegalArgumentException("BP1_TRUNCATING_STRUCTURE_MATERIAL_REQUIRED");
            var selection = knowledge.select(CRITERION, context, model);
            var row = selection.path("record");
            require("READY".equals(selection.path("status").asText())
                    || "BLOCKED".equals(selection.path("status").asText()),
                    selection.path("reasonCode").asText("BP1_TRUNCATING_PUBLISHED_SOURCE_REQUIRED"));
            require("EXECUTABLE".equals(row.path("status").asText())
                    || "UNSUPPORTED_CONDITIONS".equals(row.path("status").asText())
                    && "BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED".equals(row.path("reasonCode").asText()),
                    "BP1_TRUNCATING_SOURCE_ADMISSION_UNRESOLVED");
            Bp1PublishedPolicy.validateTruncatingSource(selection);
            var source = out.putObject("publishedSource");
            source.put("specificationKey", row.path("specificationKey").asText())
                    .put("entitySha256", selection.at("/specification/entitySha256").asText())
                    .put("clausePointer", row.path("clausePointer").asText())
                    .put("clauseSha256", row.path("clauseSha256").asText())
                    .put("sourceRawSha256", selection.at("/specification/source/rawSha256").asText());
            source.set("catalogIdentity", knowledge.identity());
            var scope = source.putObject("scope");
            for (String key : new String[] {"hgncId", "mondoId", "moi", "assembly", "transcript"})
                if (row.path("scope").has(key)) scope.set(key, row.path("scope").path(key).deepCopy());
            var observation = Bp1TruncatingConsequence.observe(mapper, context, candidate, structure);
            out.set("sequenceConsequence", observation.snapshot());
            var correlation = Bp1LofDiseaseContraindication.assess(mapper, knowledge, context, structure, observation);
            out.set("lofDiseaseCorrelation", correlation.snapshot());
            available.put("variantConsequence", observation.snapshot().path("status").asText())
                    .put("geneLofDiseaseCorrelation", correlation.snapshot().path("status").asText());
            if (!observation.sequenceConfirmed()) {
                out.put("reasonCode", observation.snapshot().path("reasonCode").asText("BP1_TRUNCATING_CONSEQUENCE_UNRESOLVED"));
                missing(out, "VARIANT_CONSEQUENCE", "SOURCE_DEFINED_PREDICTED_CODING_TRUNCATION",
                        observation.snapshot().path("reasonCode").asText());
                return finish(mapper, out);
            }
            if (!correlation.sourceChecked()) {
                out.put("reasonCode", correlation.snapshot().path("reasonCode").asText("BP1_LOF_DISEASE_SOURCE_UNAVAILABLE"));
                missing(out, "GENE_LOF_SOURCE_QUALIFICATION", "VERIFIED_NAMED_SUBJECT_AND_PUBLISHED_SOURCE",
                        out.path("reasonCode").asText());
                return finish(mapper, out);
            }
            var structured = !correlation.established() && structuredLofSource != null
                    ? structuredLofSource.get().establishedLofForBp1(context) : null;
            if (structured != null && structured.path("modelSha256").equals(observation.snapshot().path("nativeModelSha256"))) {
                out.set("structuredLof", structured);
                available.put("geneLofDiseaseCorrelation", "CONTRAINDICATION_ESTABLISHED");
            }
            if (correlation.established() || out.has("structuredLof")) {
                out.put("status", "NOT_MET")
                        .put("reasonCode", "BP1_GENE_HAS_ESTABLISHED_LOF_DISEASE_CORRELATION");
                available.put("sourceCondition", "NO_ESTABLISHED_LOF_DISEASE_CORRELATION_CONTRADICTED");
                return finish(mapper, out);
            }
            var noLof = knowledge.assessBp1NoEstablishedLof(context, structure, observation);
            out.set("noEstablishedLofQualification", noLof.snapshot());
            if (noLof.qualified()) {
                available.put("geneLofDiseaseCorrelation", "NO_ESTABLISHED_CORRELATION_QUALIFIED")
                        .put("sourceCondition", "SATISFIED_BY_EXPLICIT_IN_SCOPE_REVIEW");
                // This result is only the published source subconditions. GeneralBp1
                // must still check the current functional restriction source before MET.
                out.put("status", "READY").put("reasonCode", "BP1_TRUNCATING_SOURCE_CONDITIONS_SATISFIED");
                return finish(mapper, out);
            }
            out.put("reasonCode", "BP1_NO_ESTABLISHED_LOF_DISEASE_CORRELATION_NOT_VERIFIED");
            String qualificationReason = noLof.snapshot().path("reasonCode").asText();
            if (!"BP1_NO_LOF_SOURCE_NOT_CONFIGURED".equals(qualificationReason))
                out.put("reasonCode", qualificationReason);
            missing(out, "GENE_LOF_DISEASE_CORRELATION", "SOURCE_QUALIFIED_NO_ESTABLISHED_CORRELATION",
                    "BP1_NO_LOF_SOURCE_NOT_CONFIGURED".equals(qualificationReason)
                            ? correlation.snapshot().path("reasonCode").asText("BP1_LOF_DISEASE_CORRELATION_UNKNOWN")
                            : qualificationReason);
            available.put("sourceCondition", "UNRESOLVED_NOT_ASSUMED_TRUE");
            return finish(mapper, out);
        } catch (RuntimeException invalid) {
            String message = invalid.getMessage();
            out.put("reasonCode", message != null && message.matches("[A-Z][A-Z0-9_]+")
                    ? message : "BP1_TRUNCATING_CONDITION_CHECK_FAILED");
            missing(out, "BOUND_INPUT_OR_SOURCE", "VERIFIED_CONTEXT_MATERIAL_AND_PUBLISHED_RULE", out.path("reasonCode").asText());
            return finish(mapper, out);
        }
    }

    private static void missing(ObjectNode out, String condition, String requirement, String actualReason) {
        out.withArray("missing").addObject().put("conditionId", condition)
                .put("required", requirement).put("availableReasonCode", actualReason);
    }

    private static Result finish(ObjectMapper mapper, ObjectNode out) {
        out.putObject("evaluation").put("criterionId", CRITERION).put("appliedStrength", "NONE")
                .putObject("evaluationResult").put("status", out.path("status").asText())
                .put("reasonCode", out.path("reasonCode").asText());
        var summary = mapper.createObjectNode().put("schemaVersion", "bp1-truncating-condition-facts-v1")
                .put("methodVersion", VERSION).put("status", out.path("status").asText())
                .put("reasonCode", out.path("reasonCode").asText()).put("appliedStrength", "NONE")
                .put("scientificQualificationGranted", false).put("clinicalValidationCompleted", false);
        for (String key : new String[] {"required", "available", "missing", "publishedSource", "noEstablishedLofQualification", "structuredLof"})
            if (out.has(key)) summary.set(key, out.path(key).deepCopy());
        var observation = out.path("sequenceConsequence");
        if (observation.isObject()) {
            var bounded = summary.putObject("sequenceConsequence");
            for (String key : new String[] {"status", "reasonCode", "nativeModelSha256", "structureMaterialSha256"})
                if (observation.has(key)) bounded.set(key, observation.path(key).deepCopy());
            bounded.put("functionalLossEstablishedBySequence", false);
        }
        var correlation = out.path("lofDiseaseCorrelation");
        if (correlation.isObject()) {
            var bounded = summary.putObject("lofDiseaseCorrelation");
            for (String key : new String[] {"status", "reasonCode", "sourceCheckStatus", "relation", "sourceMention", "sourceBinding"})
                if (correlation.has(key)) bounded.set(key, correlation.path(key).deepCopy());
        }
        out.set("publicSummary", summary);
        return new Result(out);
    }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalArgumentException(code); }
}
