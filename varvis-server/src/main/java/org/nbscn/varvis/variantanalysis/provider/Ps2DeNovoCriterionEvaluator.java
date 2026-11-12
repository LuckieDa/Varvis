package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Objects;

/** Pure, opt-in single-trio core. No Spring bean, providers, persistence or final classification. */
public final class Ps2DeNovoCriterionEvaluator {
    public static final String CRITERION = "ACMG_AMP_2015:PS2";
    public static final String ID = "reviewed-single-trio-denovo-ps2";
    public static final String VERSION = "1.0.0";
    public static final String REGISTRY = "/acmg/rule-registry-v2/acmg-rule-registry-ps2-denovo-core-2026-09-28.json";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    public Ps2DeNovoCriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper); adapter = new RuntimeEvidenceV2Adapter(mapper);
    }

    public ObjectNode evaluate(Ps2DeNovoBundle bundle) {
        ObjectNode input = bundle.snapshot();
        ObjectNode result = mapper.createObjectNode().put("criterionId", CRITERION).put("resultMode", "CORE_RESULT_ONLY")
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("classificationEnabled", false).put("adoptionSupported", false);
        result.set("sourceBundle", input.deepCopy());
        ObjectNode context = (ObjectNode) input.path("context"), policy = (ObjectNode) input.path("policy");
        try {
            if (!"evidence-context-v3".equals(context.path("schemaVersion").asText())) return refuse(result, "PS2_EXPLICIT_V3_REQUIRED");
            adapter.validateVersionedContext(context);
        } catch (IllegalArgumentException invalid) { return refuse(result, "PS2_CONTEXT_IDENTITY_INVALID"); }
        if (bundle.observations().size() != 1) return refuse(result, "PS2_SINGLE_OBSERVATION_REQUIRED_NO_AGGREGATION");
        ObjectNode facts = bundle.observations().get(0).snapshot();
        ObjectNode assessment = (ObjectNode) input.at("/observations/0/assessment");
        String issue = new Ps2DeNovoPolicy(mapper).validate(context, facts, assessment, policy);
        if (issue != null) return refuse(result, issue);
        var selection = AcmgSpecificationSelector.forPs2Versioned(mapper, (ObjectNode) input.path("catalog"),
                input.path("catalogSha256").asText(), mapper.createArrayNode().add(policy)).select(context, CRITERION);
        result.set("policySelection", selection.receipt().deepCopy());
        if (selection.selectedPolicy() == null) return refuse(result, selection.receipt().path("reasonCode").asText());
        if (!validRegistry(input.path("registryBinding"), policy)) return refuse(result, "PS2_REGISTRY_OR_AUTHORITY_INVALID");
        ObjectNode evidence;
        try { evidence = adapter.adaptVersioned(context, carrier(context, facts, assessment)); }
        catch (IllegalArgumentException invalid) { return refuse(result, "PS2_CASE_EVIDENCE_IDENTITY_INVALID"); }
        result.set("evidence", evidence);
        BigDecimal points = Ps2DeNovoPolicy.points(facts);
        boolean met = points.signum() > 0;
        String strength = points.compareTo(new BigDecimal("2")) == 0 ? "STRONG"
                : points.compareTo(BigDecimal.ONE) == 0 ? "MODERATE" : met ? "SUPPORTING" : "NONE";
        String reason = met ? "PS2_SINGLE_CONFIRMED_TRIO_REVIEWED_PHENOTYPE" : "PS2_COMPLETE_REVIEWED_COUNTEREVIDENCE";
        // The only fractional value in this bounded policy is exactly representable 0.5.
        if (points.scale() == 0) result.put("deNovoPoints", points.intValueExact());
        else result.put("deNovoPoints", points.doubleValue());
        result.put("observationCount", 1).put("occurrenceId", facts.path("occurrenceId").asText())
                .put("caseId", facts.path("caseId").asText());
        ObjectNode evaluation = result.putObject("evaluation").put("schemaVersion", "criterion-evaluation-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("criterionId", CRITERION).put("baseStrength", "STRONG").put("appliedStrength", strength);
        evaluation.putObject("evaluationResult").put("status", met ? "MET" : "NOT_MET").put("reasonCode", reason);
        evaluation.putObject("criterionApplicability").put("status", "APPLICABLE");
        evaluation.putObject("ruleBinding").put("currentGeneralDesignation", "PS2")
                .put("ruleRegistryId", input.at("/registryBinding/registryId").asText())
                .put("ruleRegistrySha256", input.at("/registryBinding/registrySha256").asText())
                .put("cspecSelectionId", selection.receipt().path("selectionId").asText())
                .set("authoritySourceIds", policy.path("authoritySourceIds").deepCopy());
        var ids = evaluation.putArray("evidenceIds").add(evidence.path("evidenceId").asText());
        ObjectNode trace = evaluation.putObject("deterministicTrace").put("executionStatus", "COMPLETED")
                .put("evaluatorId", ID).put("evaluatorVersion", VERSION).put("algorithmId", policy.path("policyId").asText())
                .put("algorithmVersion", policy.path("policyVersion").asText());
        trace.set("inputEvidenceIds", ids.deepCopy());
        var parameters = trace.putArray("parameters");
        parameters.addObject().put("name", "factsSha256").put("value", adapter.canonicalSha256(facts));
        parameters.addObject().put("name", "assessmentSha256").put("value", adapter.canonicalSha256(assessment));
        parameters.addObject().put("name", "policySha256").put("value", adapter.canonicalSha256(policy));
        parameters.addObject().put("name", "deNovoPoints").put("value", points.toPlainString());
        parameters.addObject().put("name", "occurrenceId").put("value", facts.path("occurrenceId").asText());
        trace.putArray("steps").addObject().put("stepCode", reason).put("outcome", met ? "TRUE" : "FALSE")
                .set("inputEvidenceIds", ids.deepCopy());
        trace.put("traceSha256", adapter.canonicalSha256(trace));
        evaluation.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(evaluation));
        evaluation.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        evaluation.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        result.putArray("criterionEvaluations").add(evaluation.deepCopy());
        return result;
    }

    private ObjectNode carrier(JsonNode context, JsonNode facts, JsonNode assessment) {
        String date = assessment.at("/quality/date").asText();
        ObjectNode carrier = mapper.createObjectNode().put("evidenceType", "CASE_OBSERVATION").put("producedAt", date)
                .put("sourcePayloadSha256", adapter.canonicalSha256(facts));
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source").put("sourceId", "REVIEWED_CASE_FAMILY_OBSERVATION")
                .put("resourceVersion", facts.path("observationVersion").asText()).put("resourceSha256", adapter.canonicalSha256(facts))
                .put("retrievedAt", date);
        var records = source.putArray("sourceRecords"); var locators = source.putArray("sourceLocators");
        for (JsonNode document : facts.path("sources")) {
            ObjectNode locator = mapper.createObjectNode().put("locatorType", "DOCUMENT_SECTION")
                    .put("locatorValue", document.path("locator").asText()).put("sourceRecordId", document.path("recordId").asText())
                    .put("artifactSha256", document.path("rawSha256").asText()).put("retrievedAt", document.path("acquiredAt").asText());
            locators.add(locator); records.addObject().put("recordId", document.path("recordId").asText()).set("locator", locator.deepCopy());
        }
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED").put("attemptedAt", date).put("completedAt", date);
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", records.size()).put("queryFingerprint", adapter.canonicalSha256(facts.path("binding")));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE"); carrier.putArray("measurements");
        return carrier;
    }

    private ObjectNode refuse(ObjectNode result, String reason) {
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        result.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
        ObjectNode minimal = result.putArray("criterionEvaluations").addObject().put("criterionId", CRITERION);
        minimal.set("evaluationResult", result.path("evaluationResult").deepCopy());
        minimal.set("criterionApplicability", result.path("criterionApplicability").deepCopy());
        return result;
    }

    private boolean validRegistry(JsonNode binding, JsonNode policy) {
        if (!REGISTRY.equals(binding.path("registryClasspathResource").asText())) return false;
        try (var input = getClass().getResourceAsStream(REGISTRY)) {
            if (input == null) return false;
            byte[] bytes = input.readAllBytes(); JsonNode registry = mapper.readTree(bytes);
            if (registry == null || !Ps2DeNovoBundle.sha256(bytes).equals(binding.path("registrySha256").asText())
                    || !registry.path("registryId").equals(binding.path("registryId"))) return false;
            int count = 0;
            for (JsonNode criterion : registry.path("criteria")) if (CRITERION.equals(criterion.path("criterionId").asText())) {
                if (!"V2_IMPLEMENTED".equals(criterion.path("implementationStatus").asText())
                        || !"PATHOGENIC".equals(criterion.path("direction").asText())
                        || !criterion.at("/currentGeneral/authoritySourceIds").equals(policy.path("authoritySourceIds"))) return false;
                count++;
            }
            String authorityId = registry.path("authorityCatalogId").asText();
            if (count != 1 || !authorityId.matches("authority-catalog-[a-z0-9._-]+")) return false;
            try (var source = getClass().getResourceAsStream("/acmg/" + authorityId + ".json")) {
                if (source == null) return false;
                byte[] authorityBytes = source.readAllBytes(); JsonNode authority = mapper.readTree(authorityBytes);
                if (authority == null || !Ps2DeNovoBundle.sha256(authorityBytes).equals(registry.path("authorityCatalogSha256").asText())
                        || policy.path("scientificSources").size() != 1 || policy.path("authoritySourceIds").size() != 1) return false;
                JsonNode expected = policy.path("scientificSources").get(0);
                if (expected == null || !"authority-clingen-svi-ps2-pm6-v1.1".equals(expected.path("sourceId").asText())
                        || !"1.1".equals(expected.path("version").asText())) return false;
                for (JsonNode entry : authority.path("sources"))
                    if (expected.path("sourceId").equals(entry.path("authoritySourceId")))
                        return "RELEASED".equals(entry.path("publicationStatus").asText()) && "ELIGIBLE".equals(entry.path("normativeEligibility").asText())
                                && expected.path("sourceId").equals(policy.path("authoritySourceIds").get(0))
                                && expected.path("url").equals(entry.path("sourceUrl")) && expected.path("version").equals(entry.path("documentVersion"))
                                && expected.path("sha256").equals(entry.path("snapshotSha256"));
            }
        } catch (IOException invalid) { return false; }
        return false;
    }
}
