package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Deterministic PVS1 evaluator for the single E3-L1A HNF1A fixed point. */
public final class Pvs1CriterionEvaluator {

    public static final String EVALUATOR_ID = "hnf1a-fixed-point-pvs1-criterion-evaluator";
    public static final String EVALUATOR_VERSION = "1.0.0-e3-l1a";
    public static final String PROFILE_ID = "HNF1A_FIXED_POINT_PVS1_VERY_STRONG";
    public static final String PROFILE_VERSION = "1.0.0";

    private static final String C14N = "varvis-json-c14n-v1";
    private static final String PVS1 = "ACMG_AMP_2015:PVS1";
    private static final String PROFILE_RESOURCE =
            "/acmg/pvs1-hnf1a-fixed-point-evaluation-profile-v1.json";
    private static final String PROFILE_RAW_SHA256 =
            "37b20c0feaeee6ee980ca41dc0d469e66e849187246658cab7145c11aa6108e8";
    private static final String PROFILE_CANONICAL_SHA256 =
            "bb8124f9eb6d77ce158b637b2f37aa966f246f504c87b4fee9c0c8240ef05328";
    private static final String RULE_REGISTRY_RESOURCE =
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-l1a-2026-09-05.json";
    private static final String RULE_REGISTRY_ID =
            "acmg-rule-registry-e3-l1a-2026-09-05";
    private static final String RULE_REGISTRY_RAW_SHA256 =
            "3cd5b36002a00c39b3af54ac9c2c409337f8e05b76482c274216c2e3c5f9db42";
    private static final String RULE_REGISTRY_CANONICAL_SHA256 =
            "2aacc86af0b974a4f52120a32350bfd5912cf641f8c41014519ac16020c1d6f3";
    private static final String CSPEC_REGISTRY_RESOURCE =
            "/acmg/cspec-overlay-registry-v1/"
                    + "cspec-overlay-registry-e3-l1a-2026-09-05.json";
    private static final String CSPEC_REGISTRY_ID =
            "cspec-overlay-registry-e3-l1a-2026-09-05";
    private static final String CSPEC_REGISTRY_RAW_SHA256 =
            "26c1e5a5cbd1dca3b4b5af53c6b413ee869aa665246bc918d2ec022a12f34273";
    private static final String CSPEC_REGISTRY_CANONICAL_SHA256 =
            "647b4031c1ba2573887939810127cf13044733ead2223633320e0f2308f7abf4";
    private static final Pattern CONTEXT_ID =
            Pattern.compile("^context-sha256-[0-9a-f]{64}$");
    private static final Pattern SELECTION_ID =
            Pattern.compile("^cspec-selection-sha256-[0-9a-f]{64}$");
    private static final Pattern EVIDENCE_ID =
            Pattern.compile("^evidence-sha256-[0-9a-f]{64}$");

    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter canonicalizer;
    private final Pvs1PrerequisiteInputResolver prerequisiteResolver;
    private final ObjectNode profile;
    private final ObjectNode ruleRegistry;
    private final ObjectNode cspecRegistry;
    private final Criterion criterion;

    public Pvs1CriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.canonicalizer = new RuntimeEvidenceV2Adapter(mapper);
        this.prerequisiteResolver = new Pvs1PrerequisiteInputResolver(mapper);
        this.profile = loadPinned(PROFILE_RESOURCE, PROFILE_RAW_SHA256,
                PROFILE_CANONICAL_SHA256);
        this.ruleRegistry = loadPinned(RULE_REGISTRY_RESOURCE,
                RULE_REGISTRY_RAW_SHA256, RULE_REGISTRY_CANONICAL_SHA256);
        this.cspecRegistry = loadPinned(CSPEC_REGISTRY_RESOURCE,
                CSPEC_REGISTRY_RAW_SHA256, CSPEC_REGISTRY_CANONICAL_SHA256);
        this.criterion = validateStaticBindings();
    }

    /** Creates the complete ZERO_MATCH selection used by this fixed slice. */
    public ObjectNode createZeroMatchSelection(JsonNode evidenceContext) {
        String contextId = evidenceContext == null
                ? "" : evidenceContext.path("contextId").asText();
        if (!CONTEXT_ID.matcher(contextId).matches()) {
            throw new IllegalArgumentException("evidenceContext has no canonical contextId");
        }
        ObjectNode selection = mapper.createObjectNode();
        selection.put("schemaVersion", "cspec-selection-v1");
        selection.put("contextId", contextId);
        selection.put("canonicalizationProfile", C14N);
        selection.put("overlayRegistryId", CSPEC_REGISTRY_ID);
        selection.put("overlayRegistrySha256", CSPEC_REGISTRY_RAW_SHA256);
        selection.put("policyId", "CSPEC_EXACT_CONTEXT_SELECTION_V1");
        selection.put("outcome", "ZERO_MATCH");
        selection.putArray("candidateOverlayIds");
        selection.set("conflict", summary("NONE", "conflictIds"));
        ObjectNode review = summary("NOT_REQUIRED", "reviewRecordIds");
        review.put("decision", "NONE");
        selection.set("review", review);
        selection.put("selectionId", "cspec-selection-sha256-"
                + canonicalizer.canonicalSha256(selectionIdentity(selection)));
        return selection;
    }

    /** Evaluates only the fixed HNF1A branch and never emits NOT_MET. */
    public ObjectNode evaluate(JsonNode evidenceContext,
                               ObjectNode vepAtomicEvidence,
                               JsonNode cspecSelection) {
        Pvs1PrerequisiteInputResolver.Result prerequisite =
                prerequisiteResolver.resolve(evidenceContext, vepAtomicEvidence);
        String selectionIssue = selectionIssue(evidenceContext, cspecSelection);
        boolean ready = selectionIssue == null
                && "INPUT_READY".equals(prerequisite.getStatus())
                && "PREDICTED_NMD".equals(prerequisite.getNmdPrediction());
        String reason = ready ? null : selectionIssue != null
                ? selectionIssue : prerequisite.getReasonCode();
        if (!ready && (reason == null || reason.isBlank())) {
            reason = "PVS1_PREREQUISITE_INPUT_NOT_READY";
        }

        String contextId = evidenceContext == null
                ? "" : evidenceContext.path("contextId").asText();
        List<String> evidenceIds = evidenceIds(vepAtomicEvidence);
        String selectionId = boundSelectionId(cspecSelection);

        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "criterion-evaluation-v2");
        result.put("contextId", contextId);
        result.put("canonicalizationProfile", C14N);
        result.put("criterionId", PVS1);
        ObjectNode ruleBinding = result.putObject("ruleBinding");
        ruleBinding.put("ruleRegistryId", RULE_REGISTRY_ID);
        ruleBinding.put("ruleRegistrySha256", RULE_REGISTRY_RAW_SHA256);
        ruleBinding.put("currentGeneralDesignation", criterion.designation());
        ArrayNode authorities = ruleBinding.putArray("authoritySourceIds");
        new TreeSet<>(criterion.authoritySourceIds()).forEach(authorities::add);
        ruleBinding.put("cspecSelectionId", selectionId);

        ObjectNode applicability = result.putObject("criterionApplicability");
        applicability.put("status", ready ? "APPLICABLE" : "UNKNOWN");
        if (!ready) applicability.put("reasonCode", reason);
        ObjectNode evaluation = result.putObject("evaluationResult");
        evaluation.put("status", ready ? "MET" : "NOT_EVALUATED");
        if (!ready) evaluation.put("reasonCode", reason);
        result.put("baseStrength", criterion.strength());
        result.put("appliedStrength", ready ? criterion.strength() : "NONE");
        ArrayNode cited = result.putArray("evidenceIds");
        evidenceIds.forEach(cited::add);

        ObjectNode trace = result.putObject("deterministicTrace");
        trace.put("executionStatus", ready ? "COMPLETED" : "NOT_RUN");
        trace.put("evaluatorId", EVALUATOR_ID);
        trace.put("evaluatorVersion", EVALUATOR_VERSION);
        ArrayNode inputs = trace.putArray("inputEvidenceIds");
        evidenceIds.forEach(inputs::add);
        trace.set("parameters", parameters());
        ArrayNode steps = trace.putArray("steps");
        if (ready) {
            steps.add(step("CSPEC_ZERO_MATCH_GENERAL_RULES", "TRUE", evidenceIds));
            steps.add(step("PVS1_PREREQUISITE_INPUT_READY", "TRUE", evidenceIds));
            steps.add(step("PVS1_NMD_PREDICTED", "TRUE", evidenceIds));
            trace.put("algorithmId", "HNF1A_FIXED_POINT_PVS1_VERY_STRONG_V1");
            trace.put("algorithmVersion", "1.0.0");
            trace.put("traceSha256", canonicalizer.canonicalSha256(trace));
        } else {
            steps.add(step(reason, "UNKNOWN", evidenceIds));
            trace.put("reasonCode", reason);
        }

        result.set("conflict", summary("NONE", "conflictIds"));
        ObjectNode review = summary("NOT_REQUIRED", "reviewRecordIds");
        review.put("decision", "NONE");
        result.set("review", review);

        ObjectNode identity = result.deepCopy();
        identity.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-"
                + canonicalizer.canonicalSha256(identity));
        return result;
    }

    public String getProfileSha256() {
        return PROFILE_CANONICAL_SHA256;
    }

    public String getRuleRegistrySha256() {
        return RULE_REGISTRY_RAW_SHA256;
    }

    public String getCspecRegistrySha256() {
        return CSPEC_REGISTRY_RAW_SHA256;
    }

    private Criterion validateStaticBindings() {
        JsonNode activation = profile.path("activation");
        JsonNode prerequisite = profile.path("prerequisiteBinding");
        JsonNode ruleBinding = profile.path("ruleRegistryBinding");
        JsonNode cspecBinding = profile.path("cspecRegistryBinding");
        if (!PROFILE_ID.equals(profile.path("profileId").asText())
                || !PROFILE_VERSION.equals(profile.path("profileVersion").asText())
                || !C14N.equals(profile.path("canonicalizationProfile").asText())
                || !activation.path("runtimeBound").asBoolean(false)
                || !activation.path("evaluationEnabled").asBoolean(false)
                || activation.path("patientFlowEnabled").asBoolean(true)
                || activation.path("classificationEnabled").asBoolean(true)
                || !Pvs1PrerequisiteInputResolver.PROFILE_ID.equals(
                prerequisite.path("profileId").asText())
                || !Pvs1PrerequisiteInputResolver.PROFILE_VERSION.equals(
                prerequisite.path("profileVersion").asText())
                || !prerequisiteResolver.getProfileSha256().equals(
                prerequisite.path("profileCanonicalSha256").asText())) {
            throw invalid("PVS1 evaluation profile binding mismatch");
        }
        if (!RULE_REGISTRY_ID.equals(ruleRegistry.path("registryId").asText())
                || !RULE_REGISTRY_ID.equals(ruleBinding.path("registryId").asText())
                || !RULE_REGISTRY_RAW_SHA256.equals(
                ruleBinding.path("registrySha256").asText())
                || !RULE_REGISTRY_CANONICAL_SHA256.equals(
                ruleBinding.path("registryCanonicalSha256").asText())) {
            throw invalid("PVS1 rule registry binding mismatch");
        }
        if (!CSPEC_REGISTRY_ID.equals(cspecRegistry.path("registryId").asText())
                || !CSPEC_REGISTRY_ID.equals(cspecBinding.path("registryId").asText())
                || !CSPEC_REGISTRY_RAW_SHA256.equals(
                cspecBinding.path("registrySha256").asText())
                || !CSPEC_REGISTRY_CANONICAL_SHA256.equals(
                cspecBinding.path("registryCanonicalSha256").asText())
                || !RULE_REGISTRY_ID.equals(
                cspecRegistry.path("baseRuleRegistryId").asText())
                || !RULE_REGISTRY_RAW_SHA256.equals(
                cspecRegistry.path("baseRuleRegistrySha256").asText())) {
            throw invalid("PVS1 CSpec registry binding mismatch");
        }

        JsonNode declared = profile.path("criterion");
        JsonNode registered = findCriterion(ruleRegistry, PVS1);
        JsonNode current = registered.path("currentGeneral");
        Set<String> authorities = textSet(current.path("authoritySourceIds"));
        if (!registered.isObject()
                || !"V2_IMPLEMENTED".equals(
                registered.path("implementationStatus").asText())
                || !PVS1.equals(declared.path("criterionId").asText())
                || !declared.path("currentGeneralDesignation").asText()
                .equals(current.path("designation").asText())
                || !"VERY_STRONG".equals(current.path("strength").asText())
                || !"VERY_STRONG".equals(declared.path("baseStrength").asText())
                || !"VERY_STRONG".equals(declared.path("appliedStrength").asText())
                || !authorities.equals(textSet(declared.path("authoritySourceIds")))) {
            throw invalid("PVS1 current-general criterion binding mismatch");
        }
        return new Criterion(current.path("designation").asText(),
                current.path("strength").asText(), authorities);
    }

    private String selectionIssue(JsonNode context, JsonNode selection) {
        if (selection == null || !selection.isObject()) {
            return "CSPEC_SELECTION_MISSING_OR_INVALID";
        }
        if (!"cspec-selection-v1".equals(selection.path("schemaVersion").asText())
                || !C14N.equals(selection.path("canonicalizationProfile").asText())) {
            return "CSPEC_SELECTION_MISSING_OR_INVALID";
        }
        if (context == null || !selection.path("contextId").asText()
                .equals(context.path("contextId").asText())) {
            return "CSPEC_SELECTION_CONTEXT_MISMATCH";
        }
        if (!CSPEC_REGISTRY_ID.equals(selection.path("overlayRegistryId").asText())
                || !CSPEC_REGISTRY_RAW_SHA256.equals(
                selection.path("overlayRegistrySha256").asText())
                || !cspecRegistry.path("selectionPolicy").path("policyId").asText()
                .equals(selection.path("policyId").asText())) {
            return "CSPEC_SELECTION_REGISTRY_MISMATCH";
        }
        if (!"ZERO_MATCH".equals(selection.path("outcome").asText())
                || !selection.path("candidateOverlayIds").isArray()
                || !selection.path("candidateOverlayIds").isEmpty()
                || selection.has("selectedOverlayId")
                || selection.has("selectedOverlaySha256")
                || selection.has("reasonCode")
                || !"NONE".equals(selection.path("conflict").path("status").asText())
                || !selection.path("conflict").path("conflictIds").isArray()
                || !selection.path("conflict").path("conflictIds").isEmpty()
                || !"NOT_REQUIRED".equals(
                selection.path("review").path("status").asText())
                || !"NONE".equals(selection.path("review").path("decision").asText())
                || !selection.path("review").path("reviewRecordIds").isArray()
                || !selection.path("review").path("reviewRecordIds").isEmpty()) {
            return "CSPEC_ZERO_MATCH_SELECTION_REQUIRED";
        }
        String expected = "cspec-selection-sha256-"
                + canonicalizer.canonicalSha256(selectionIdentity(selection));
        if (!expected.equals(selection.path("selectionId").asText())) {
            return "CSPEC_SELECTION_IDENTITY_MISMATCH";
        }
        return null;
    }

    private ObjectNode selectionIdentity(JsonNode selection) {
        ObjectNode projection = ((ObjectNode) selection).deepCopy();
        projection.remove(List.of("selectionId", "conflict", "review"));
        ArrayNode sorted = mapper.createArrayNode();
        List<String> ids = new ArrayList<>();
        for (JsonNode value : projection.path("candidateOverlayIds")) {
            ids.add(value.asText());
        }
        ids.stream().sorted().forEach(sorted::add);
        projection.set("candidateOverlayIds", sorted);
        return projection;
    }

    private String boundSelectionId(JsonNode selection) {
        String value = selection == null ? "" : selection.path("selectionId").asText();
        if (SELECTION_ID.matcher(value).matches()) return value;
        return "cspec-selection-sha256-" + canonicalizer.canonicalSha256(
                selection == null ? mapper.nullNode() : selection);
    }

    private List<String> evidenceIds(JsonNode atomic) {
        String value = atomic == null ? "" : atomic.path("evidenceId").asText();
        return EVIDENCE_ID.matcher(value).matches() ? List.of(value) : List.of();
    }

    private ArrayNode parameters() {
        List<ObjectNode> values = new ArrayList<>();
        values.add(parameter("evaluationProfile.id", PROFILE_ID));
        values.add(parameter("evaluationProfile.version", PROFILE_VERSION));
        values.add(parameter("evaluationProfile.sha256", PROFILE_CANONICAL_SHA256));
        values.add(parameter("prerequisiteProfile.id",
                Pvs1PrerequisiteInputResolver.PROFILE_ID));
        values.add(parameter("prerequisiteProfile.version",
                Pvs1PrerequisiteInputResolver.PROFILE_VERSION));
        values.add(parameter("prerequisiteProfile.sha256",
                prerequisiteResolver.getProfileSha256()));
        values.add(parameter("ruleRegistry.id", RULE_REGISTRY_ID));
        values.add(parameter("ruleRegistry.sha256", RULE_REGISTRY_RAW_SHA256));
        values.add(parameter("cspecRegistry.id", CSPEC_REGISTRY_ID));
        values.add(parameter("cspecRegistry.sha256", CSPEC_REGISTRY_RAW_SHA256));
        values.sort(Comparator.comparing(value -> value.path("name").asText()));
        ArrayNode result = mapper.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private ObjectNode parameter(String name, String value) {
        return mapper.createObjectNode().put("name", name).put("value", value);
    }

    private ObjectNode step(String code, String outcome, List<String> evidenceIds) {
        ObjectNode value = mapper.createObjectNode();
        value.put("stepCode", code);
        value.put("outcome", outcome);
        ArrayNode ids = value.putArray("inputEvidenceIds");
        evidenceIds.forEach(ids::add);
        return value;
    }

    private ObjectNode summary(String status, String idsField) {
        ObjectNode value = mapper.createObjectNode().put("status", status);
        value.putArray(idsField);
        return value;
    }

    private JsonNode findCriterion(JsonNode registry, String criterionId) {
        for (JsonNode value : registry.path("criteria")) {
            if (criterionId.equals(value.path("criterionId").asText())) return value;
        }
        return mapper.missingNode();
    }

    private Set<String> textSet(JsonNode values) {
        Set<String> result = new TreeSet<>();
        for (JsonNode value : values) {
            if (value.isTextual()) result.add(value.asText());
        }
        return Set.copyOf(result);
    }

    private ObjectNode loadPinned(String resource,
                                  String expectedRawSha256,
                                  String expectedCanonicalSha256) {
        byte[] bytes;
        try (InputStream input = Pvs1CriterionEvaluator.class
                .getResourceAsStream(resource)) {
            if (input == null) throw invalid("classpath resource is missing: " + resource);
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw invalid("cannot load classpath resource: " + resource, failure);
        }
        if (!expectedRawSha256.equals(sha256(bytes))) {
            throw invalid("classpath resource raw hash mismatch: " + resource);
        }
        try {
            JsonNode value = mapper.readTree(bytes);
            if (!value.isObject()) throw invalid("classpath resource is not an object: " + resource);
            ObjectNode object = (ObjectNode) value;
            if (!expectedCanonicalSha256.equals(canonicalizer.canonicalSha256(object))) {
                throw invalid("classpath resource canonical hash mismatch: " + resource);
            }
            return object;
        } catch (IOException failure) {
            throw invalid("cannot parse classpath resource: " + resource, failure);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    private record Criterion(String designation,
                             String strength,
                             Set<String> authoritySourceIds) {
    }
}
