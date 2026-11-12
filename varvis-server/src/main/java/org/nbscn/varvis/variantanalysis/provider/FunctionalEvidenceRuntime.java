package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Executes one reviewed protein assay. It never selects a policy, runs an experiment or adopts evidence. */
public final class FunctionalEvidenceRuntime {
    public static final String PS3 = "ACMG_AMP_2015:PS3";
    public static final String EVALUATOR_ID = "reviewed-functional-assay";
    public static final String VERSION = "1.0.5";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RuntimeEvidenceV2ProviderWiring wiring;

    public FunctionalEvidenceRuntime(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
    }

    /** Policy and receipt must come from a separately pinned, reviewed catalog. Hash agreement alone is not review. */
    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, JsonNode policy,
                               JsonNode selectionReceipt, JsonNode reviewedFactBundle) {
        var result = mapper.createObjectNode().put("criterionId", PS3).put("sourceOfTruth", "ACMG_EVIDENCE_V2")
                .put("classificationEnabled", false).put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("adoptionSupported", false).put("adoptionReasonCode", "FUNCTIONAL_NEW_POLICY_ADOPTION_NOT_INTEGRATED");
        result.putArray("disabledCriteria").addObject().put("criterionId", "ACMG_AMP_2015:BS3")
                .put("reasonCode", "BS3_SCIENTIFIC_GATE_NOT_PASSED");
        try {
            if (selectionReceipt != null) result.set("policySelection", selectionReceipt.deepCopy());
            String issue = selectionIssue(context, policy, selectionReceipt);
            if (issue != null) return disposition(result, issue);
            adapter.validateCompleteContext(context);
            result.set("context", context.deepCopy());
            issue = FunctionalEvidencePolicy.issue(policy);
            if (issue == null) issue = FunctionalEvidencePolicy.contextIssue(policy, context);
            if (issue != null) return disposition(result, issue);
            result.set("policy", policy.deepCopy());
            result.put("profileSha256", adapter.canonicalSha256(policy));
            JsonNode registryRule = registryRule(policy);
            if (registryRule == null) return disposition(result, "FUNCTIONAL_REGISTRY_IDENTITY_INVALID");
            if (reviewedFactBundle == null || !reviewedFactBundle.isObject())
                return disposition(result, "FUNCTIONAL_FACT_BUNDLE_MISSING");
            issue = FunctionalEvidencePolicy.factsIssue(policy, reviewedFactBundle, adapter);
            if (issue != null) return disposition(result, issue);
            JsonNode model = reviewedFactBundle.path("nativeModel");
            issue = CodingTranscriptMapping.modelIssue(model, context);
            if (issue != null) return disposition(result, "FUNCTIONAL_NATIVE_MODEL_INVALID");
            if (!model.path("protein").equals(policy.at("/scope/protein")))
                return disposition(result, "FUNCTIONAL_MODEL_PROTEIN_MISMATCH");
            if (!"VEP".equals(reviewedFactBundle.at("/annotationResource/provider").asText())
                    || !policy.at("/scope/assembly").equals(reviewedFactBundle.at("/annotationResource/assembly")))
                return disposition(result, "FUNCTIONAL_ANNOTATION_RESOURCE_NOT_SUPPORTED");
            if (candidate == null) return disposition(result, "FUNCTIONAL_CANDIDATE_MISSING");
            TranscriptConsequence tx = wiring.selectedVepConsequence(context, candidate);
            if (tx == null) return disposition(result, "FUNCTIONAL_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            ObjectNode manifest = mapper.createObjectNode();
            manifest.putArray("resources").add(reviewedFactBundle.path("annotationResource"));
            var binding = wiring.bind(manifest, reviewedFactBundle.at("/annotationResource/resourceId").asText());
            wiring.projectStoredTranscript(context, candidate, tx, binding);
            result.put("storedVepProjections", 1);
            ObjectNode identity = mapper.createObjectNode();
            issue = resolveProtein(context, tx, model, identity);
            if (issue != null) return disposition(result, issue);
            result.set("proteinIdentity", identity);
            JsonNode record = null;
            for (JsonNode row : reviewedFactBundle.path("records"))
                if (identity.path("proteinVariantKey").equals(row.path("proteinVariantKey"))) record = row;
            if (record == null) return disposition(result, "FUNCTIONAL_NO_REVIEWED_RECORD");
            result.set("factPackage", reviewedFactBundle.deepCopy());
            result.set("factRecord", record.deepCopy());
            result.put("recordId", record.path("recordId").asText()).put("providerCalls", 1);
            issue = recordIdentityIssue(policy, record, identity);
            if (issue != null) return disposition(result, issue);
            if (!"CLEAR_FOR_THIS_ASSAY".equals(record.at("/conflictReview/status").asText())
                    || !FunctionalEvidencePolicy.text(record.at("/conflictReview/reviewId")))
                return disposition(result, "FUNCTIONAL_ASSAY_CONFLICT_REVIEW_REQUIRED");
            String status = observedStatus(policy, record);
            if (!Set.of("MET", "NOT_MET").contains(status)) return disposition(result, status);
            var atomic = result.putArray("atomicEvidence");
            atomic.add(atomic(context, policy, reviewedFactBundle, record));
            result.set("evaluation", evaluation(context, policy, reviewedFactBundle, record, selectionReceipt, atomic, identity, status, registryRule));
            var reviews = result.putArray("reviewRecords");
            requestReview((ObjectNode) atomic.path(0), "ATOMIC_EVIDENCE", "evidenceId", reviews);
            requestReview((ObjectNode) result.path("evaluation"), "CRITERION_EVALUATION", "evaluationId", reviews);
            result.put("evaluatorCalls", 1);
            return result;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return disposition(result, "FUNCTIONAL_INPUT_INTEGRITY_INVALID");
        }
    }

    @Nullable private JsonNode registryRule(JsonNode policy) {
        String path = policy.at("/registryBinding/registryClasspathResource").asText();
        if (!policy.at("/registryBinding/registrySha256").asText().equals(ClinVarMissenseBusinessArtifacts.resourceSha(path))) return null;
        try (var stream = FunctionalEvidenceRuntime.class.getResourceAsStream(path)) {
            if (stream == null) return null;
            JsonNode registry = mapper.readTree(stream);
            if (registry == null || !"acmg-rule-registry-v2".equals(registry.path("schemaVersion").asText())
                    || !policy.at("/registryBinding/registryId").equals(registry.path("registryId"))) return null;
            JsonNode matched = null;
            for (JsonNode rule : registry.path("criteria")) if (PS3.equals(rule.path("criterionId").asText())) {
                if (matched != null) return null;
                matched = rule;
            }
            if (matched == null || !matched.at("/currentGeneral/authoritySourceIds").isArray()
                    || matched.at("/currentGeneral/authoritySourceIds").isEmpty()) return null;
            for (JsonNode allowed : matched.at("/currentGeneral/allowedStrengths"))
                if (allowed.equals(policy.at("/assay/threshold/strength"))) return matched;
            return null;
        } catch (java.io.IOException invalid) { return null; }
    }

    @Nullable private String selectionIssue(JsonNode context, JsonNode policy, JsonNode receipt) {
        if (!(receipt instanceof ObjectNode object)) return "FUNCTIONAL_SELECTION_REQUIRED";
        if (context == null || !context.path("contextId").equals(receipt.path("contextId")))
            return "FUNCTIONAL_SELECTION_CONTEXT_MISMATCH";
        ObjectNode projection = object.deepCopy();
        projection.remove("selectionId");
        if (!AcmgSpecificationSelector.SCHEMA.equals(receipt.path("schemaVersion").asText())
                || !receipt.path("criterionIds").isArray() || receipt.path("criterionIds").size() != 1
                || !PS3.equals(receipt.at("/criterionIds/0").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(projection)).equals(receipt.path("selectionId").asText())
                || !FunctionalEvidencePolicy.text(receipt.path("catalogId"))
                || !FunctionalEvidencePolicy.text(receipt.path("catalogVersion"))
                || !FunctionalEvidencePolicy.hash(receipt.path("catalogSha256")))
            return "FUNCTIONAL_SELECTION_IDENTITY_INVALID";
        if (!Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText()))
            return receipt.path("reasonCode").asText("FUNCTIONAL_POLICY_SELECTION_INCOMPLETE");
        if (policy == null || !policy.isObject() || !receipt.path("policyId").equals(policy.path("policyId"))
                || !receipt.path("policyVersion").equals(policy.path("policyVersion"))
                || !receipt.path("policySha256").asText().equals(adapter.canonicalSha256(policy)))
            return "FUNCTIONAL_SELECTION_POLICY_MISMATCH";
        return null;
    }

    @Nullable private String resolveProtein(JsonNode context, TranscriptConsequence tx, JsonNode model, ObjectNode identity) {
        String issue = CodingMissenseResolver.resolve(context, tx, model, identity, adapter);
        return issue == null ? null : issue.replace("CODING_MISSENSE_", "FUNCTIONAL_");
    }

    @Nullable private String recordIdentityIssue(JsonNode policy, JsonNode record, JsonNode identity) {
        String key = record.path("proteinVariantKey").asText();
        String ref = record.path("wildTypeCodon").asText();
        String alt = record.path("experimentalMutantCodon").asText();
        if (!"MISSENSE".equals(record.path("mutationType").asText())
                || !identity.path("hgvsp").equals(record.path("proteinHgvs"))
                || !identity.path("refCodon").asText().equals(ref) || !alt.matches("[ACGT]{3}")
                || TruncatingSequence.translateCodon(ref) != key.charAt(0)
                || TruncatingSequence.translateCodon(alt) != key.charAt(key.length() - 1))
            return "FUNCTIONAL_ASSAY_PROTEIN_IDENTITY_MISMATCH";
        if ("MEASURED_SCALAR".equals(policy.at("/assay/representation").asText())) {
            for (String field : List.of("proteinVariantKey", "wildTypeCodon", "experimentalMutantCodon")) {
                JsonNode cell = cell(policy, record, field);
                if (!"s".equals(cell.path("type").asText()) || !record.path(field).equals(cell.path("raw")))
                    return "FUNCTIONAL_RAW_IDENTITY_CELL_MISMATCH";
            }
            JsonNode proteinCell = cell(policy, record, "proteinVariantThreeLetter");
            JsonNode effectCell = cell(policy, record, "mutationType");
            if (!"s".equals(proteinCell.path("type").asText())
                    || !record.path("proteinHgvs").asText().endsWith(":p." + proteinCell.path("raw").asText())
                    || !"s".equals(effectCell.path("type").asText()) || !"missense".equals(effectCell.path("raw").asText()))
                return "FUNCTIONAL_RAW_IDENTITY_CELL_MISMATCH";
        }
        return null;
    }

    /** No display rounding and no imputed-value substitution. Range evidence must lie wholly on one side. */
    static String observedStatus(JsonNode policy, JsonNode record) {
        BigDecimal threshold = FunctionalEvidencePolicy.decimal(policy.at("/assay/threshold/decimal"));
        if (threshold == null) return "FUNCTIONAL_POLICY_OPERATOR_UNSUPPORTED";
        String operator = policy.at("/assay/threshold/operator").asText();
        if ("MEASURED_SCALAR".equals(policy.at("/assay/representation").asText())) {
            JsonNode measured = cell(policy, record, "measuredScore");
            BigDecimal value = FunctionalEvidencePolicy.decimal(measured.path("raw"));
            if (!"n".equals(measured.path("type").asText()) || value == null)
                return "FUNCTIONAL_ACCEPTABLE_MEASURED_SCORE_MISSING";
            JsonNode highConfidence = cell(policy, record, "highConfidence");
            if (!"b".equals(highConfidence.path("type").asText()) || !"1".equals(highConfidence.path("raw").asText()))
                return "FUNCTIONAL_MEASURED_QUALITY_FAILED_OR_UNKNOWN";
            return compare(value, threshold, operator) ? "MET" : "NOT_MET";
        }
        JsonNode range = record.path("publishedRange");
        BigDecimal lower = FunctionalEvidencePolicy.decimal(range.path("lower"));
        BigDecimal upper = FunctionalEvidencePolicy.decimal(range.path("upper"));
        if (lower == null || upper == null || lower.compareTo(upper) > 0
                || !range.path("lowerInclusive").isBoolean() || !range.path("upperInclusive").isBoolean()
                || !range.path("unit").equals(policy.at("/assay/unit"))
                || lower.compareTo(upper) == 0 && (!range.path("lowerInclusive").asBoolean() || !range.path("upperInclusive").asBoolean())
                || !"CLEAR_FOR_RECORDED_BOUND_ONLY".equals(range.path("reviewStatus").asText())
                || !FunctionalEvidencePolicy.text(range.path("sourceLocator"))) return "FUNCTIONAL_REVIEWED_RANGE_INVALID";
        boolean lowerPass = compare(lower, threshold, operator);
        boolean upperPass = compare(upper, threshold, operator);
        if (lowerPass && upperPass) return "MET";
        if (!lowerPass && !upperPass) return "NOT_MET";
        if ("LT".equals(operator) && upper.compareTo(threshold) == 0 && !range.path("upperInclusive").asBoolean()
                || "GT".equals(operator) && lower.compareTo(threshold) == 0 && !range.path("lowerInclusive").asBoolean()) return "MET";
        if ("LTE".equals(operator) && lower.compareTo(threshold) == 0 && !range.path("lowerInclusive").asBoolean()
                || "GTE".equals(operator) && upper.compareTo(threshold) == 0 && !range.path("upperInclusive").asBoolean()) return "NOT_MET";
        return "FUNCTIONAL_RANGE_CROSSES_THRESHOLD";
    }

    private static boolean compare(BigDecimal value, BigDecimal threshold, String operator) {
        int order = value.compareTo(threshold);
        return switch (operator) { case "LTE" -> order <= 0; case "LT" -> order < 0; case "GTE" -> order >= 0; case "GT" -> order > 0; default -> false; };
    }

    private static JsonNode cell(JsonNode policy, JsonNode record, String semantic) {
        return record.path("sourceCells").path(policy.at("/assay/sourceColumns").path(semantic).asText());
    }

    private ObjectNode atomic(ObjectNode context, JsonNode policy, JsonNode facts, JsonNode record) {
        String now = Instant.now().toString();
        String sha = adapter.canonicalSha256(facts);
        JsonNode method = facts.path("method");
        JsonNode sourceIdentity = FunctionalEvidencePolicy.source(policy, record.path("sourceId").asText());
        if (sourceIdentity == null) throw new IllegalArgumentException("Source missing");
        ObjectNode correlation = mapper.createObjectNode().put("studyId", method.path("studyId").asText())
                .put("assayId", method.path("assayId").asText()).put("proteinVariantKey", record.path("proteinVariantKey").asText());
        var carrier = mapper.createObjectNode().put("evidenceType", "FUNCTIONAL_STUDY").put("producedAt", now)
                .put("sourcePayloadSha256", sha).put("correlationGroupId", "correlation-sha256-" + adapter.canonicalSha256(correlation));
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        var source = carrier.putObject("source").put("sourceId", "REVIEWED_FUNCTIONAL_FACTS")
                .put("resourceVersion", facts.path("version").asText()).put("resourceSha256", sha)
                .put("retrievedAt", facts.at("/review/reviewedAt").asText());
        boolean scalar = "MEASURED_SCALAR".equals(policy.at("/assay/representation").asText());
        var locator = mapper.createObjectNode().put("locatorType", scalar ? "TABLE" : "FIGURE")
                .put("locatorValue", sourceIdentity.path("url").asText() + "#" + (scalar
                        ? record.path("sheet").asText() + ":row=" + record.path("row").asInt() : record.at("/publishedRange/sourceLocator").asText()))
                .put("sourceRecordId", record.path("recordId").asText()).put("artifactSha256", sourceIdentity.path("sha256").asText())
                .put("retrievedAt", facts.at("/review/reviewedAt").asText());
        source.putArray("sourceLocators").add(locator);
        source.putArray("sourceRecords").addObject().put("recordId", record.path("recordId").asText()).set("locator", locator.deepCopy());
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED").put("attemptedAt", now).put("completedAt", now);
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                .put("queryFingerprint", adapter.canonicalSha256(correlation));
        String raw = scalar
                ? cell(policy, record, "measuredScore").path("raw").asText()
                : (record.at("/publishedRange/lowerInclusive").asBoolean() ? "[" : "(")
                        + record.at("/publishedRange/lower").asText() + "," + record.at("/publishedRange/upper").asText()
                        + (record.at("/publishedRange/upperInclusive").asBoolean() ? "]" : ")");
        carrier.putArray("measurements").addObject().put("metricId", "functional.reviewedAssay")
                .put("rawValue", raw).put("unit", method.path("unit").asText()).put("calibrationProfileId", method.path("calibrationId").asText());
        return adapter.adapt(context, carrier);
    }

    private ObjectNode evaluation(ObjectNode context, JsonNode policy, JsonNode facts, JsonNode record,
                                  JsonNode selection, ArrayNode atomic, JsonNode identity, String status, JsonNode registryRule) {
        String strength = policy.at("/assay/threshold/strength").asText();
        var value = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2").put("canonicalizationProfile", "varvis-json-c14n-v1")
                .put("contextId", context.path("contextId").asText()).put("criterionId", PS3)
                .put("baseStrength", strength).put("appliedStrength", "MET".equals(status) ? strength : "NONE");
        var rule = value.putObject("ruleBinding").put("ruleRegistryId", policy.at("/registryBinding/registryId").asText())
                .put("ruleRegistrySha256", policy.at("/registryBinding/registrySha256").asText()).put("currentGeneralDesignation", "PS3")
                .put("cspecSelectionId", selection.path("selectionId").asText());
        rule.set("authoritySourceIds", registryRule.at("/currentGeneral/authoritySourceIds").deepCopy());
        value.putObject("criterionApplicability").put("status", "APPLICABLE");
        value.putObject("evaluationResult").put("status", status);
        var ids = value.putArray("evidenceIds").add(atomic.path(0).path("evidenceId"));
        var trace = value.putObject("deterministicTrace").put("executionStatus", "COMPLETED")
                .put("evaluatorId", EVALUATOR_ID).put("evaluatorVersion", VERSION)
                .put("algorithmId", policy.path("policyId").asText()).put("algorithmVersion", policy.path("policyVersion").asText());
        trace.set("inputEvidenceIds", ids.deepCopy());
        var parameters = trace.putArray("parameters");
        parameter(parameters, "policySha256", adapter.canonicalSha256(policy));
        parameter(parameters, "factPackageSha256", adapter.canonicalSha256(facts));
        parameter(parameters, "methodSha256", adapter.canonicalSha256(facts.path("method")));
        parameter(parameters, "nativeModelSha256", adapter.canonicalSha256(facts.path("nativeModel")));
        parameter(parameters, "annotationResourceSha256", adapter.canonicalSha256(facts.path("annotationResource")));
        parameter(parameters, "selectionSha256", adapter.canonicalSha256(selection));
        parameter(parameters, "proteinIdentitySha256", adapter.canonicalSha256(identity));
        parameter(parameters, "recordSha256", adapter.canonicalSha256(record));
        parameter(parameters, "thresholdComparison", "ORIGINAL_DECIMAL_NO_DISPLAY_ROUNDING");
        parameter(parameters, "imputedValueUse", "NEVER_SUBSTITUTED_FOR_MEASUREMENT_OR_QUALITY");
        parameter(parameters, "combinedEvidenceUse", "SINGLE_ASSAY_NO_STRENGTH_ADDITION");
        parameter(parameters, "negativeResultMeaning", "THIS_ASSAY_THRESHOLD_ONLY_NO_BS3");
        trace.putArray("steps").addObject().put("stepCode", "REVIEWED_ASSAY_THRESHOLD_COMPARISON")
                .put("outcome", "MET".equals(status) ? "TRUE" : "FALSE").set("inputEvidenceIds", ids.deepCopy());
        trace.put("traceSha256", adapter.canonicalSha256(trace));
        value.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        value.putObject("review").put("status", "REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        ObjectNode projection = value.deepCopy(); projection.remove(List.of("conflict", "review"));
        value.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(projection));
        return value;
    }

    private static void parameter(ArrayNode parameters, String name, String value) {
        parameters.addObject().put("name", name).put("value", value);
    }

    private void requestReview(ObjectNode subject, String type, String idField, ArrayNode requests) {
        var request = mapper.createObjectNode().put("schemaVersion", "review-record-v2").put("contextId", subject.path("contextId").asText())
                .put("subjectType", type).put("subjectId", subject.path(idField).asText()).put("eventType", "REQUESTED")
                .put("status", "REQUIRED").put("decision", "NONE").put("reasonCode", "FUNCTIONAL_EXPLICIT_ADOPTION_REQUIRED")
                .put("rationaleSummary", "Calculation only; new policy adoption is not integrated and no human decision is recorded.")
                .put("createdAt", Instant.now().toString());
        request.putObject("actor").put("actorId", "reviewer-system-functional-request").put("role", "SYSTEM_ADMINISTRATOR");
        request.put("reviewRecordId", "review-sha256-" + adapter.canonicalSha256(request));
        ((ObjectNode) subject.path("review")).putArray("reviewRecordIds").add(request.path("reviewRecordId"));
        requests.add(request);
    }

    private ObjectNode disposition(ObjectNode result, String reason) {
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        result.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
        return result;
    }
}
