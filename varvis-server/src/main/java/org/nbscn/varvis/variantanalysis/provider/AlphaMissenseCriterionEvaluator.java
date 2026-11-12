package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Deterministic E3-C1 AlphaMissense PP3/BP4 Supporting-only evaluator. */
public final class AlphaMissenseCriterionEvaluator {

    public static final String EVALUATOR_ID = "alphamissense-criterion-evaluator";
    public static final String EVALUATOR_VERSION = "1.0.0-e3-c1";
    public static final String PROFILE_ID = "ALPHAMISSENSE_PP3_BP4_SUPPORTING";
    public static final String PROFILE_VERSION = "1.0.0";
    public static final String CALIBRATION_PROFILE_ID = PROFILE_ID + "@" + PROFILE_VERSION;

    private static final String PROFILE_RESOURCE =
            "/acmg/alphamissense-pp3-bp4-supporting-profile-v1.json";
    private static final String REGISTRY_RESOURCE =
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-c1-2026-09-02.json";
    private static final String PROFILE_CANONICAL_SHA256 =
            "b8f040d58ee6ef19f687886a742cacaa0a96dc3b5f803e8f423578e1aaa4cb83";
    private static final String REGISTRY_BYTE_SHA256 =
            "73fca655b6d76700242fc6b12ba4c6c4b9f34ac5c79b38621fe03fecc8303469";
    private static final String REGISTRY_CANONICAL_SHA256 =
            "61617eb005138ebc814b9c14afd9f3f34189fb7a93442c7c6843c780161b647e";
    private static final String REGISTRY_ID = "acmg-rule-registry-e3-c1-2026-09-02";
    private static final String AUTHORITY_ID =
            "authority-clingen-svi-pp3-bp4-extension-2025";
    private static final String PP3 = "ACMG_AMP_2015:PP3";
    private static final String BP4 = "ACMG_AMP_2015:BP4";
    private static final String C14N = "varvis-json-c14n-v1";
    private static final String SCORE_METRIC = "alphamissense.amPathogenicity";
    private static final String EXACT_ALLELE_METRIC = "alphamissense.exactAlleleMatch";
    private static final String TRANSCRIPT_PROTEIN_MATCH_METRIC =
            "alphamissense.transcriptProteinMatch";
    private static final String TRANSCRIPT_METRIC = "alphamissense.transcriptId";
    private static final String PROTEIN_CHANGE_METRIC = "alphamissense.proteinVariant";
    private static final String PROTEIN_ID_METRIC = "alphamissense.uniprotId";
    private static final Pattern SELECTION_ID =
            Pattern.compile("^cspec-selection-sha256-[0-9a-f]{64}$");

    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter canonicalizer;

    public AlphaMissenseCriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.canonicalizer = new RuntimeEvidenceV2Adapter(mapper);
    }

    /** Loads the pinned Supporting-only calibration profile. */
    public static ObjectNode loadProfile(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode profile = readObject(mapper, PROFILE_RESOURCE, null);
        String actual = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(profile);
        if (!PROFILE_CANONICAL_SHA256.equals(actual)) {
            throw invalid("calibration profile canonical hash is not pinned");
        }
        return profile;
    }

    /** Loads the pinned E3-C1 successor registry and verifies its committed bytes. */
    public static ObjectNode loadSuccessorRegistry(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode registry = readObject(mapper, REGISTRY_RESOURCE, REGISTRY_BYTE_SHA256);
        String canonical = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(registry);
        if (!REGISTRY_CANONICAL_SHA256.equals(canonical)) {
            throw invalid("successor registry canonical hash is not pinned");
        }
        return registry;
    }

    /** Evaluates the classpath-pinned E3-C1 profile and successor registry. */
    public List<ObjectNode> evaluate(JsonNode evidenceContext,
                                     ObjectNode atomicEvidence,
                                     String cspecSelectionId) {
        return evaluate(evidenceContext, atomicEvidence, loadProfile(mapper),
                loadSuccessorRegistry(mapper), cspecSelectionId);
    }

    /** Evaluates only PP3 and BP4 from one AlphaMissense atomic evidence record. */
    public List<ObjectNode> evaluate(JsonNode evidenceContext,
                                     ObjectNode atomicEvidence,
                                     ObjectNode profile,
                                     ObjectNode successorRegistry,
                                     String cspecSelectionId) {
        return evaluateClauses(evidenceContext, atomicEvidence, profile, successorRegistry, cspecSelectionId, Set.of(PP3, BP4));
    }

    List<ObjectNode> evaluateClauses(JsonNode evidenceContext, ObjectNode atomicEvidence, ObjectNode profile,
                                    ObjectNode successorRegistry, String cspecSelectionId, Set<String> clauses) {
        if (!Set.of(PP3, BP4).containsAll(clauses)) throw new IllegalArgumentException("ALPHAMISSENSE_CLAUSE_MASK_INVALID");
        if (clauses.isEmpty()) return List.of();
        Objects.requireNonNull(evidenceContext, "evidenceContext");
        Objects.requireNonNull(atomicEvidence, "atomicEvidence");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(successorRegistry, "successorRegistry");
        if (cspecSelectionId == null || !SELECTION_ID.matcher(cspecSelectionId).matches()) {
            throw new IllegalArgumentException(
                    "cspecSelectionId must be an opaque canonical selection ID");
        }

        Bindings bindings = bindings(profile, successorRegistry);
        Decision decision = input(bindings, evidenceContext, atomicEvidence);
        var outputs = new java.util.ArrayList<ObjectNode>();
        if (clauses.contains(PP3)) outputs.add(output(bindings, bindings.pp3(), atomicEvidence, cspecSelectionId, decision));
        if (clauses.contains(BP4)) outputs.add(output(bindings, bindings.bp4(), atomicEvidence, cspecSelectionId, decision));
        return List.copyOf(outputs);
    }

    private Bindings bindings(ObjectNode profile, ObjectNode registry) {
        String profileHash = canonicalizer.canonicalSha256(profile);
        String issue = PROFILE_CANONICAL_SHA256.equals(profileHash)
                ? null : "PROFILE_HASH_MISMATCH";

        JsonNode registryBinding = profile.path("registryBinding");
        String registryId = registryBinding.path("registryId").asText();
        String registryHash = registryBinding.path("registrySha256").asText();
        if (issue == null && (!REGISTRY_CANONICAL_SHA256.equals(
                canonicalizer.canonicalSha256(registry))
                || !REGISTRY_ID.equals(registryId)
                || !REGISTRY_ID.equals(registry.path("registryId").asText())
                || !REGISTRY_BYTE_SHA256.equals(registryHash))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }

        Set<String> authorities = authorityIds(profile.path("authorityBindings"));
        Criterion pp3 = criterion(profile, registry, PP3, authorities);
        Criterion bp4 = criterion(profile, registry, BP4, authorities);
        if (issue == null && (!criterionBound(pp3, registry)
                || !criterionBound(bp4, registry))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }

        JsonNode resource = profile.path("resources").path(0);
        JsonNode benign = profile.path("thresholds").path("benignMaxInclusive");
        JsonNode pathogenic = profile.path("thresholds").path("pathogenicMinInclusive");
        return new Bindings(profile.deepCopy(), profileHash, registryId, registryHash,
                resource.deepCopy(), decimal(benign.path("rawValue")),
                decimal(pathogenic.path("rawValue")), pp3, bp4, issue);
    }

    /** Reuses the existing source/allele/transcript/score checks; no legacy criterion output is returned. */
    PredictionModelInput inputForPolicy(JsonNode context, ObjectNode evidence) {
        Decision decision = input(bindings(loadProfile(mapper), loadSuccessorRegistry(mapper)), context, evidence);
        if (!decision.completed()) return new PredictionModelInput(null, decision.reason());
        for (ObjectNode parameter : decision.parameters())
            if ("score.raw".equals(parameter.path("name").asText()))
                return new PredictionModelInput(new BigDecimal(parameter.path("value").asText()), null);
        return new PredictionModelInput(null, "ALPHAMISSENSE_SCORE_INVALID");
    }

    private Criterion criterion(ObjectNode profile,
                                ObjectNode registry,
                                String criterionId,
                                Set<String> authorities) {
        JsonNode declared = find(profile.path("evaluation").path("criteria"),
                "criterionId", criterionId);
        JsonNode registered = find(registry.path("criteria"), "criterionId", criterionId);
        return new Criterion(criterionId,
                declared.path("currentDesignation").asText(),
                declared.path("baseStrength").asText(),
                Collections.unmodifiableSet(new LinkedHashSet<>(authorities)), registered);
    }

    private boolean criterionBound(Criterion criterion, JsonNode registry) {
        JsonNode declared = criterion.registered();
        Set<String> registeredAuthorities = textSet(
                declared.path("currentGeneral").path("authoritySourceIds"));
        return declared.isObject()
                && declared.equals(find(registry.path("criteria"),
                "criterionId", criterion.id()))
                && "V2_IMPLEMENTED".equals(declared.path("implementationStatus").asText())
                && criterion.designation().equals(
                declared.path("currentGeneral").path("designation").asText())
                && "SUPPORTING".equals(criterion.strength())
                && criterion.strength().equals(
                declared.path("currentGeneral").path("strength").asText())
                && criterion.authorities().equals(Set.of(AUTHORITY_ID))
                && registeredAuthorities.containsAll(criterion.authorities());
    }

    private Decision input(Bindings bindings,
                           JsonNode context,
                           ObjectNode evidence) {
        List<ObjectNode> steps = new ArrayList<>();
        List<ObjectNode> parameters = baseParameters(bindings, evidence);
        if (bindings.issue() != null) {
            return blocked("UNKNOWN", bindings.issue(), steps, parameters, evidence);
        }
        if (!context.path("contextId").asText().equals(evidence.path("contextId").asText())) {
            return blocked("UNKNOWN", "ATOMIC_EVIDENCE_CONTEXT_MISMATCH",
                    steps, parameters, evidence);
        }
        if (!"evidence-context-v2".equals(context.path("schemaVersion").asText())
                || !C14N.equals(context.path("canonicalizationProfile").asText())
                || !"atomic-evidence-v2".equals(evidence.path("schemaVersion").asText())
                || !C14N.equals(evidence.path("canonicalizationProfile").asText())
                || !"COMPUTATIONAL_PREDICTION".equals(evidence.path("evidenceType").asText())
                || !RuntimeEvidenceV2Adapter.PRODUCER_ID.equals(
                evidence.path("provenance").path("producerId").asText())) {
            return blocked("UNKNOWN", "ATOMIC_EVIDENCE_BINDING_MISMATCH",
                    steps, parameters, evidence);
        }
        JsonNode source = evidence.path("source");
        JsonNode resource = bindings.resource();
        if (!resource.path("resourceId").asText().equals(source.path("sourceId").asText())
                || !resource.path("resourceVersion").asText()
                .equals(source.path("resourceVersion").asText())
                || !resource.path("identityFingerprintSha256").asText()
                .equals(source.path("resourceSha256").asText())) {
            return blocked("UNKNOWN", "RESOURCE_IDENTITY_MISMATCH",
                    steps, parameters, evidence);
        }

        JsonNode variant = context.path("variant");
        JsonNode queryAllele = variant.path("evidenceQueryAllele");
        if (!completeContext(context)) {
            return blocked("UNKNOWN", "CONTEXT_INCOMPLETE", steps, parameters, evidence);
        }
        boolean nativeGrch38Snv = "SNV".equals(variant.path("variantType").asText())
                && "GRCh38".equals(queryAllele.path("assembly").asText())
                && "NATIVE".equals(variant.path("assemblyLineage")
                .path("mappingStatus").asText())
                && queryAllele.path("reference").asText().length() == 1
                && queryAllele.path("alternate").asText().length() == 1;
        String execution = evidence.path("resourceExecution").path("status").asText();
        String availability = evidence.path("resourceAvailability").path("status").asText();
        String query = evidence.path("queryOutcome").path("status").asText();
        String applicability = evidence.path("resourceApplicability").path("status").asText();
        parameters.add(parameter("resource.executionStatus", execution, null));
        parameters.add(parameter("resource.availabilityStatus", availability, null));
        parameters.add(parameter("resource.queryStatus", query, null));
        parameters.add(parameter("resource.applicabilityStatus", applicability, null));
        if (!nativeGrch38Snv || "NOT_APPLICABLE".equals(applicability)) {
            return blocked("NOT_APPLICABLE", "ALPHAMISSENSE_PROFILE_NOT_APPLICABLE",
                    steps, parameters, evidence);
        }
        if ("FAILED".equals(execution)) {
            return blocked("UNKNOWN", "RESOURCE_EXECUTION_FAILED",
                    steps, parameters, evidence);
        }
        if ("TIMEOUT".equals(execution)) {
            return blocked("UNKNOWN", "RESOURCE_EXECUTION_TIMEOUT",
                    steps, parameters, evidence);
        }
        if (!"SUCCEEDED".equals(execution)) {
            return blocked("UNKNOWN", "RESOURCE_EXECUTION_NOT_SUCCEEDED",
                    steps, parameters, evidence);
        }
        if (!"AVAILABLE".equals(availability)) {
            return blocked("UNKNOWN", "RESOURCE_NOT_AVAILABLE",
                    steps, parameters, evidence);
        }
        if ("NO_RECORD".equals(query)) {
            return blocked("UNKNOWN", "ALPHAMISSENSE_NO_RECORD",
                    steps, parameters, evidence);
        }
        if (!"HIT".equals(query)
                || evidence.path("queryOutcome").path("recordCount").asInt(-1) != 1) {
            return blocked("UNKNOWN", "QUERY_NOT_SINGLE_EXACT_HIT",
                    steps, parameters, evidence);
        }
        if (!"APPLICABLE".equals(applicability)) {
            return blocked("UNKNOWN", "RESOURCE_APPLICABILITY_UNKNOWN",
                    steps, parameters, evidence);
        }
        steps.add(step("RESOURCE_FOUR_AXES_READY", "TRUE", evidence));
        String selectedTranscript = context.path("transcript").path("accession").asText()
                + "." + context.path("transcript").path("version").asInt();
        parameters.add(parameter("context.transcriptId", selectedTranscript, "transcript-id"));

        Reading exactAllele = reading(evidence, EXACT_ALLELE_METRIC);
        if (exactAllele.reason() != null) {
            return blocked("UNKNOWN", exactAllele.reason(), steps, parameters, evidence);
        }
        if (!trueMeasurement(exactAllele.node())) {
            return blocked("UNKNOWN", "EXACT_ALLELE_NOT_CONFIRMED",
                    steps, parameters, evidence);
        }
        steps.add(step("EXACT_ALLELE_CONFIRMED", "TRUE", evidence));

        Reading transcriptProteinMatch = reading(evidence, TRANSCRIPT_PROTEIN_MATCH_METRIC);
        if (transcriptProteinMatch.reason() != null) {
            return blocked("UNKNOWN", transcriptProteinMatch.reason(),
                    steps, parameters, evidence);
        }
        if (!trueMeasurement(transcriptProteinMatch.node())) {
            return blocked("UNKNOWN", "TRANSCRIPT_PROTEIN_MATCH_NOT_CONFIRMED",
                    steps, parameters, evidence);
        }
        Reading transcript = reading(evidence, TRANSCRIPT_METRIC);
        Reading proteinChange = reading(evidence, PROTEIN_CHANGE_METRIC);
        Reading proteinId = reading(evidence, PROTEIN_ID_METRIC);
        for (Reading identity : List.of(transcript, proteinChange, proteinId)) {
            if (identity.reason() != null) {
                return blocked("UNKNOWN", identity.reason(), steps, parameters, evidence);
            }
        }
        String evidenceTranscript = textMeasurement(transcript.node(), "transcript-id");
        String evidenceProteinChange = textMeasurement(proteinChange.node(), "protein-change");
        String evidenceProteinId = textMeasurement(proteinId.node(), "protein-id");
        if (!selectedTranscript.equals(evidenceTranscript)) {
            return blocked("UNKNOWN", "TRANSCRIPT_IDENTITY_MISMATCH",
                    steps, parameters, evidence);
        }
        if (evidenceProteinChange == null || evidenceProteinChange.isBlank()
                || evidenceProteinId == null || evidenceProteinId.isBlank()) {
            return blocked("UNKNOWN", "PROTEIN_IDENTITY_INCOMPLETE",
                    steps, parameters, evidence);
        }
        parameters.add(parameter("evidence.proteinVariant", evidenceProteinChange,
                "protein-change"));
        parameters.add(parameter("evidence.proteinId", evidenceProteinId, "protein-id"));
        steps.add(step("TRANSCRIPT_PROTEIN_EXACT_MATCH_CONFIRMED", "TRUE", evidence));

        Reading scoreReading = reading(evidence, SCORE_METRIC);
        if (scoreReading.reason() != null) {
            return blocked("UNKNOWN", scoreReading.reason(), steps, parameters, evidence);
        }
        JsonNode rawNode = scoreReading.node().path("rawValue");
        JsonNode normalizedNode = scoreReading.node().path("normalizedValue");
        String raw = rawNode.isTextual() ? rawNode.asText() : null;
        String normalized = normalizedNode.isTextual() ? normalizedNode.asText() : null;
        BigDecimal score = raw == null ? null : decimal(rawNode);
        if (!"score".equals(scoreReading.node().path("unit").asText())
                || score == null
                || score.compareTo(BigDecimal.ZERO) < 0
                || score.compareTo(BigDecimal.ONE) > 0
                || !canonicalDecimal(score).equals(normalized)) {
            return blocked("UNKNOWN", "ALPHAMISSENSE_SCORE_INVALID",
                    steps, parameters, evidence);
        }
        parameters.add(parameter("score.metricId", SCORE_METRIC, null));
        parameters.add(parameter("score.raw", raw, "score"));
        parameters.add(parameter("score.normalized", normalized, "score"));
        parameters.add(parameter("threshold.bp4.maxInclusive", "0.169", "score"));
        parameters.add(parameter("threshold.pp3.minInclusive", "0.792", "score"));
        parameters.add(parameter("strength.cap", "SUPPORTING", null));
        boolean bp4Met = score.compareTo(bindings.benignMaximum()) <= 0;
        boolean pp3Met = score.compareTo(bindings.pathogenicMinimum()) >= 0;
        if (bp4Met && pp3Met) {
            return blocked("UNKNOWN", "PP3_BP4_MUTUAL_EXCLUSION_VIOLATION",
                    steps, parameters, evidence);
        }
        steps.add(step("BP4_MAXIMUM_SCORE_SATISFIED", bp4Met ? "TRUE" : "FALSE", evidence));
        steps.add(step("PP3_MINIMUM_SCORE_SATISFIED", pp3Met ? "TRUE" : "FALSE", evidence));
        return new Decision("APPLICABLE", pp3Met ? "MET" : "NOT_MET",
                bp4Met ? "MET" : "NOT_MET", null, true, steps, parameters);
    }

    private boolean completeContext(JsonNode context) {
        JsonNode completeness = context.path("contextCompleteness");
        JsonNode transcript = context.path("transcript");
        JsonNode variant = context.path("variant");
        JsonNode queryAllele = variant.path("evidenceQueryAllele");
        return "COMPLETE".equals(completeness.path("status").asText())
                && completeness.path("missingFields").isArray()
                && completeness.path("missingFields").isEmpty()
                && variant.path("variantType").isTextual()
                && queryAllele.path("assembly").isTextual()
                && queryAllele.path("reference").isTextual()
                && !queryAllele.path("reference").asText().isBlank()
                && queryAllele.path("alternate").isTextual()
                && !queryAllele.path("alternate").asText().isBlank()
                && variant.path("assemblyLineage").path("mappingStatus").isTextual()
                && transcript.path("accession").isTextual()
                && !transcript.path("accession").asText().isBlank()
                && transcript.path("version").isIntegralNumber()
                && transcript.path("version").asInt(-1) > 0;
    }

    private ObjectNode output(Bindings bindings,
                              Criterion criterion,
                              ObjectNode evidence,
                              String selectionId,
                              Decision decision) {
        String evaluationStatus = PP3.equals(criterion.id())
                ? decision.pp3Result() : decision.bp4Result();
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "criterion-evaluation-v2");
        result.put("contextId", evidence.path("contextId").asText());
        result.put("canonicalizationProfile", C14N);
        result.put("criterionId", criterion.id());
        ObjectNode ruleBinding = result.putObject("ruleBinding");
        ruleBinding.put("ruleRegistryId", bindings.registryId());
        ruleBinding.put("ruleRegistrySha256", bindings.registryHash());
        ruleBinding.put("currentGeneralDesignation", criterion.designation());
        ArrayNode authorityValues = ruleBinding.putArray("authoritySourceIds");
        // ruleBinding identifies the complete registered rule. The narrower calibration
        // authority remains in the unchanged Supporting profile and deterministic inputs.
        new TreeSet<>(textSet(criterion.registered().path("currentGeneral").path("authoritySourceIds")))
                .forEach(authorityValues::add);
        ruleBinding.put("cspecSelectionId", selectionId);

        ObjectNode applicability = result.putObject("criterionApplicability");
        applicability.put("status", decision.applicability());
        if (!"APPLICABLE".equals(decision.applicability())) {
            applicability.put("reasonCode", decision.reason());
        }
        ObjectNode evaluation = result.putObject("evaluationResult");
        evaluation.put("status", evaluationStatus);
        if ("NOT_EVALUATED".equals(evaluationStatus)) {
            evaluation.put("reasonCode", decision.reason());
        }
        result.put("baseStrength", "SUPPORTING");
        result.put("appliedStrength", "MET".equals(evaluationStatus)
                ? "SUPPORTING" : "NONE");
        result.putArray("evidenceIds").add(evidence.path("evidenceId").asText());

        ObjectNode trace = result.putObject("deterministicTrace");
        trace.put("executionStatus", decision.completed() ? "COMPLETED" : "NOT_RUN");
        trace.put("evaluatorId", EVALUATOR_ID);
        trace.put("evaluatorVersion", EVALUATOR_VERSION);
        trace.putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
        trace.set("parameters", array(sortedParameters(decision.parameters())));
        trace.set("steps", array(decision.steps()));
        if (decision.completed()) {
            trace.put("algorithmId", "ALPHAMISSENSE_PP3_BP4_SUPPORTING_V1");
            trace.put("algorithmVersion", "1.0.0");
            trace.put("traceSha256", canonicalizer.canonicalSha256(trace));
        } else {
            trace.put("reasonCode", decision.reason());
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

    private Decision blocked(String applicability,
                             String reason,
                             List<ObjectNode> steps,
                             List<ObjectNode> parameters,
                             ObjectNode evidence) {
        List<ObjectNode> values = copy(steps);
        values.add(step(reason, "NOT_APPLICABLE".equals(applicability)
                ? "NOT_APPLICABLE" : "FALSE", evidence));
        return new Decision(applicability, "NOT_EVALUATED", "NOT_EVALUATED",
                reason, false, values, copy(parameters));
    }

    private Reading reading(ObjectNode evidence, String metricId) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                found.add(value);
            }
        }
        if (found.isEmpty()) {
            return new Reading(null, "REQUIRED_MEASUREMENT_MISSING");
        }
        if (found.size() != 1) {
            return new Reading(null, "REQUIRED_MEASUREMENT_AMBIGUOUS");
        }
        if (!CALIBRATION_PROFILE_ID.equals(
                found.get(0).path("calibrationProfileId").asText())) {
            return new Reading(null, "PROFILE_CALIBRATION_MISMATCH");
        }
        return new Reading(found.get(0), null);
    }

    private boolean trueMeasurement(JsonNode measurement) {
        if (!"boolean".equals(measurement.path("unit").asText())) {
            return false;
        }
        JsonNode raw = measurement.path("rawValue");
        JsonNode normalized = measurement.path("normalizedValue");
        return raw.equals(normalized)
                && ((raw.isBoolean() && raw.booleanValue())
                || (raw.isTextual() && "true".equals(raw.asText())));
    }

    private String textMeasurement(JsonNode measurement, String unit) {
        JsonNode raw = measurement.path("rawValue");
        JsonNode normalized = measurement.path("normalizedValue");
        return unit.equals(measurement.path("unit").asText())
                && raw.isTextual() && raw.equals(normalized)
                ? raw.asText() : null;
    }

    private List<ObjectNode> baseParameters(Bindings bindings, ObjectNode evidence) {
        return new ArrayList<>(List.of(
                parameter("profile.id", PROFILE_ID, null),
                parameter("profile.version", PROFILE_VERSION, null),
                parameter("profile.sha256", bindings.profileHash(), "sha256"),
                parameter("resource.id", evidence.path("source").path("sourceId").asText(), null),
                parameter("resource.version",
                        evidence.path("source").path("resourceVersion").asText(), null),
                parameter("resource.sha256",
                        evidence.path("source").path("resourceSha256").asText(), "sha256")));
    }

    private ObjectNode parameter(String name, String value, String unit) {
        ObjectNode result = mapper.createObjectNode().put("name", name).put("value", value);
        if (unit != null) {
            result.put("unit", unit);
        }
        return result;
    }

    private ObjectNode step(String code, String outcome, ObjectNode evidence) {
        ObjectNode result = mapper.createObjectNode().put("stepCode", code).put("outcome", outcome);
        result.putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
        return result;
    }

    private ArrayNode array(List<ObjectNode> values) {
        ArrayNode result = mapper.createArrayNode();
        values.forEach(value -> result.add(value.deepCopy()));
        return result;
    }

    private ObjectNode summary(String status, String idsField) {
        ObjectNode result = mapper.createObjectNode().put("status", status);
        result.putArray(idsField);
        return result;
    }

    private static ObjectNode readObject(ObjectMapper mapper,
                                         String resource,
                                         String expectedByteHash) {
        byte[] bytes;
        try (InputStream input = AlphaMissenseCriterionEvaluator.class
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw invalid("classpath resource is missing: " + resource);
            }
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw invalid("classpath resource cannot be read: " + resource);
        }
        if (expectedByteHash != null && !expectedByteHash.equals(sha256(bytes))) {
            throw invalid("classpath resource byte hash is not pinned: " + resource);
        }
        try {
            JsonNode value = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isObject()) {
                throw invalid("classpath resource is not an object: " + resource);
            }
            return (ObjectNode) value;
        } catch (IOException failure) {
            throw invalid("classpath resource is invalid JSON: " + resource);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static BigDecimal decimal(JsonNode value) {
        if (!value.isTextual()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String canonicalDecimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }

    private static JsonNode find(JsonNode values, String field, String expected) {
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (expected.equals(value.path(field).asText())) {
                    return value;
                }
            }
        }
        return MissingNode.getInstance();
    }

    private static Set<String> authorityIds(JsonNode bindings) {
        Set<String> result = new LinkedHashSet<>();
        if (bindings.isArray()) {
            for (JsonNode binding : bindings) {
                String value = binding.path("authoritySourceId").asText();
                if (!value.isBlank()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static Set<String> textSet(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (value.isTextual() && !value.asText().isBlank()) {
                    result.add(value.asText());
                }
            }
        }
        return result;
    }

    private static List<ObjectNode> copy(List<ObjectNode> values) {
        List<ObjectNode> result = new ArrayList<>();
        values.forEach(value -> result.add(value.deepCopy()));
        return result;
    }

    private static List<ObjectNode> sortedParameters(List<ObjectNode> values) {
        List<ObjectNode> result = copy(values);
        result.sort(Comparator.comparing((ObjectNode value) -> value.path("name").asText())
                .thenComparing(value -> value.path("unit").asText())
                .thenComparing(value -> value.path("value").asText()));
        return result;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid AlphaMissense E3-C1 profile: " + message);
    }

    private record Bindings(ObjectNode profile,
                            String profileHash,
                            String registryId,
                            String registryHash,
                            JsonNode resource,
                            BigDecimal benignMaximum,
                            BigDecimal pathogenicMinimum,
                            Criterion pp3,
                            Criterion bp4,
                            String issue) {
    }

    private record Criterion(String id,
                             String designation,
                             String strength,
                             Set<String> authorities,
                             JsonNode registered) {
    }

    private record Reading(JsonNode node, String reason) {
    }

    private record Decision(String applicability,
                            String pp3Result,
                            String bp4Result,
                            String reason,
                            boolean completed,
                            List<ObjectNode> steps,
                            List<ObjectNode> parameters) {
    }
}
