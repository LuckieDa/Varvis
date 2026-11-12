package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler;
import static org.nbscn.varvis.variantanalysis.provider.SavedPredictionApplicationInputs.require;

/** Server-owned limited REVEL entry. Preparation and immutable-source adoption remain separate operations. */
public final class RevelApplicationBusiness {
    public static final String DECLARATION = "ordinary-revel-pp3-v1";
    public static final String OPERATION = "ADOPT_SAVED_REVEL_PP3";
    public static final String PROFILE = "acmg-revel-pp3-application-v1";
    public static final List<String> CRITERIA = List.of("ACMG_AMP_2015:PP3");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter hash;
    private final ReviewedPredictionBusinessRuntime runtime;
    private final java.util.function.BiFunction<ReviewedPredictionBusinessRuntime.PreparedContext, VariantInterpretation,
            ReviewedPredictionBundle.ObservationProvider> observations;
    private final ApprovalLoader approvals;
    private final ObjectNode identity;
    private final ObjectNode preparationIdentity;

    record Approval(ObjectNode settings, SavedPredictionContextBridge.TrustedContextSupply contexts) { }
    @FunctionalInterface interface ApprovalLoader { Approval load(String runId, JsonNode privateData) throws Exception; }

    RevelApplicationBusiness(ObjectMapper mapper, ReviewedPredictionBusinessRuntime runtime,
            java.util.function.BiFunction<ReviewedPredictionBusinessRuntime.PreparedContext, VariantInterpretation,
                    ReviewedPredictionBundle.ObservationProvider> observations, ApprovalLoader approvals, ObjectNode binding) {
        this.mapper = mapper; hash = new RuntimeEvidenceV2Adapter(mapper); this.runtime = runtime;
        this.observations = observations; this.approvals = approvals;
        identity = binding.deepCopy(); identity.set("sourceRuntime", runtime.identity());
        identity.put("schemaVersion", "revel-application-business-v1").put("automaticTrigger", false)
                .put("classificationEnabled", false).put("externalWorldCovered", false);
        identity.put("bindingSha256", hash.canonicalSha256(identity));
        // Source deduplication binds science and purpose, not later approval registration.
        preparationIdentity = mapper.createObjectNode().put("schemaVersion", "revel-source-preparation-binding-v1")
                .put("implementationVersion", "1.1.0").put("requiredDeclaration", DECLARATION)
                .put("purpose", "EXPLICIT_REVEL_PP3_SOURCE").put("testOnly", binding.path("testOnly").asBoolean())
                .put("automaticTrigger", false).put("classificationEnabled", false);
        preparationIdentity.set("sourceRuntime", runtime.identity());
        var policies = new TreeSet<String>(); binding.path("allowedPolicySha256s").forEach(p -> policies.add(p.asText()));
        preparationIdentity.set("allowedPolicySha256s", mapper.valueToTree(policies));
        preparationIdentity.put("bindingSha256", hash.canonicalSha256(preparationIdentity));
    }

    /** Pinned private configuration only. No client JSON, credentials, or paths are exported. */
    public static RevelApplicationBusiness configured(ObjectMapper mapper, ReviewedPredictionBusinessRuntime runtime,
            ReviewedPredictionObservationRouter router, Path file, String expectedSha) throws Exception {
        ObjectNode config = readPinned(mapper, file, expectedSha);
        require("revel-application-business-v1".equals(config.path("schemaVersion").asText()), "REVEL_APPLICATION_CONFIGURATION_INVALID");
        require(config.path("testOnly").isBoolean() && config.path("approvals").isObject()
                && config.path("allowedPolicySha256s").isArray() && !config.path("allowedPolicySha256s").isEmpty(), "REVEL_APPLICATION_CONFIGURATION_INVALID");
        var binding = mapper.createObjectNode().put("configurationSha256", expectedSha).put("testOnly", config.path("testOnly").asBoolean());
        binding.set("allowedPolicySha256s", config.path("allowedPolicySha256s").deepCopy());
        return new RevelApplicationBusiness(mapper, runtime, (prepared, candidate) -> router.open(
                List.of(new ReviewedPredictionObservationRouter.Input(prepared, candidate, List.of())),
                new AnalysisContext("revel-explicit", GenomeAssembly.GRCh38, AnalysisMode.WES)),
                (runId, privateData) -> loadApproval(mapper, file, expectedSha, runId, privateData), binding);
    }
    static Approval loadApproval(ObjectMapper mapper, Path file, String expectedSha, String runId, JsonNode privateData) throws Exception {
            ObjectNode fresh = readPinned(mapper, file, expectedSha);
            JsonNode ref = fresh.path("approvals").path(runId);
            require(ref.isObject(), "REVEL_APPLICATION_APPROVAL_MISSING");
            ObjectNode approved = readPinned(mapper, Path.of(ref.path("file").asText()), ref.path("sha256").asText());
            ObjectNode settings = (ObjectNode) approved.path("settings");
            require(runId.equals(settings.path("runId").asText()), "REVEL_APPLICATION_SOURCE_MISMATCH");
            var s = approved.path("bindingSnapshot");
            var snapshot = SavedPredictionContextBridge.Snapshot.load(mapper, Path.of(s.path("manifest").asText()),
                    Path.of(s.path("mane").asText()), Path.of(s.path("clingen").asText()), s.path("manifestSha256").asText());
            Map<SavedPredictionContextBridge.Key, SavedPredictionContextBridge.ReviewBytes> reviews = new LinkedHashMap<>();
            Map<SavedPredictionContextBridge.Key, String> pins = new LinkedHashMap<>();
            for (JsonNode r : approved.path("contextReviews")) {
                var key = new SavedPredictionContextBridge.Key(r.path("candidateId").asText(), r.path("hypothesisId").asText());
                byte[] bytes = bounded(Path.of(r.path("file").asText()));
                require(PredictionCalibration.sha256(bytes).equals(r.path("sha256").asText()) && !pins.containsKey(key), "CONTEXT_REVIEW_PIN_MISMATCH");
                reviews.put(key, new SavedPredictionContextBridge.ReviewBytes(bytes)); pins.put(key, r.path("sha256").asText());
            }
            return new Approval(settings, SavedPredictionContextBridge.TrustedContextSupply.fromBusiness(mapper, snapshot, privateData, reviews, pins));
    }
    private static byte[] bounded(Path file) throws Exception {
        require(Files.size(file) <= 4 * 1024 * 1024, "REVEL_APPLICATION_CONFIGURATION_TOO_LARGE");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            require(bytes.length <= 4 * 1024 * 1024, "REVEL_APPLICATION_CONFIGURATION_TOO_LARGE"); return bytes;
        }
    }
    private static ObjectNode readPinned(ObjectMapper mapper, Path file, String pin) throws Exception {
        byte[] bytes = bounded(file);
        require(PredictionCalibration.sha256(bytes).equals(pin), "REVEL_APPLICATION_CONFIGURATION_CHANGED");
        JsonNode value = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("REVEL_APPLICATION_CONFIGURATION_INVALID");
        return object;
    }
    public ObjectNode preparationIdentity() { return preparationIdentity.deepCopy(); }
    public ObjectNode identity() { return identity.deepCopy(); }
    public ObjectNode contextBinding() { return runtime.identity(); }

    /** Called after durable claim and normal coordinator assembly; no legacy model fallback. */
    public void prepare(ExplicitEvidenceContextAssembler.AssemblyResult assembly, List<VariantInterpretation> ranked, ObjectNode response) {
        var rows = new ArrayList<ExplicitAlphaMissenseDispatcher.CandidateDispatch>();
        int providers = 0, evaluators = 0;
        for (var row : assembly.candidates()) {
            var attempts = new ArrayList<ExplicitAlphaMissenseDispatcher.DispatchAttempt>();
            var candidate = ranked.stream().filter(c -> row.candidateId().equals(c.getCandidateId())).findFirst().orElseThrow();
            for (var a : row.attempts()) {
                var preflight = new CSpecExecutionPreflight.SelectionAttempt(a, "REVEL_EXPLICIT_SOURCE", List.of(), null, null, null, List.of(), List.of(), List.of());
                var blockers = new ArrayList<>(a.blockers());
                var atomic = new ArrayList<ObjectNode>(); var evaluations = new ArrayList<ObjectNode>();
                ObjectNode tx = mapper.createObjectNode(); int pc = 0, ec = 0;
                var context = a.context();
                if (context != null && blockers.isEmpty()) {
                    try {
                        require(!identity.path("testOnly").asBoolean() || "SYNTHETIC".equals(context.path("dataClass").asText()), "REVEL_TEST_ONLY_SCOPE_REQUIRED");
                        var plan = runtime.prepare(context); var prep = plan.snapshot();
                        JsonNode policy = prep.at("/selectedPolicies/ACMG_AMP_2015:PP3");
                        require("REVEL".equals(policy.path("preselectedModel").asText()) && "SUPPORTING".equals(policy.path("appliedStrength").asText()), "REVEL_PP3_SCOPE_UNAVAILABLE");
                        require(policyAllowed(hash.canonicalSha256(policy)), "REVEL_POLICY_OUTSIDE_RELEASE_SCOPE");
                        for (JsonNode lookup : prep.path("lookupRequests")) {
                            require("REVEL".equals(lookup.path("preselectedModel").asText()), "REVEL_MODEL_FALLBACK_FORBIDDEN");
                            for (JsonNode clause : lookup.path("plannedClauses"))
                                require("ACMG_AMP_2015:PP3".equals(clause.path("criterionId").asText()), "REVEL_PP3_ONLY_REQUIRED");
                        }
                        var selected = new RuntimeEvidenceV2ProviderWiring(mapper, hash).selectedVepConsequence(context, candidate);
                        require(selected != null, "CLAUSE_SELECTED_ANNOTATION_MISSING");
                        if (selected == null) throw new IllegalArgumentException("CLAUSE_SELECTED_ANNOTATION_MISSING");
                        tx.set("persistedAnnotation", ExplicitAlphaMissenseDispatcher.transcriptInput(mapper, selected));
                        tx.set("predictionPreparation", prep);
                        ObjectNode result = runtime.execute(plan, candidate, observations.apply(plan, candidate));
                        tx.set("reviewedPrediction", result);
                        for (JsonNode clause : result.path("clauses")) {
                            JsonNode r = clause.path("result");
                            for (String field : List.of("evidence", "vepEvidence")) if (r.path(field) instanceof ObjectNode node && !atomic.contains(node)) atomic.add(node);
                            if (r.path("evaluation") instanceof ObjectNode node) evaluations.add(node);
                        }
                        pc = result.path("providerCalls").asInt(); ec = result.at("/executionCounts/completedClauseEvaluations").asInt();
                    } catch (IllegalArgumentException invalid) {
                        blockers.add(new ExplicitEvidenceContextAssembler.Blocker("NOT_EVALUATED", SavedPredictionApplicationInputs.safeClauseIssue(invalid), "revel"));
                    }
                }
                providers += pc; evaluators += ec;
                attempts.add(new ExplicitAlphaMissenseDispatcher.DispatchAttempt(preflight, context, null, atomic, evaluations, tx, blockers, pc, ec));
            }
            rows.add(new ExplicitAlphaMissenseDispatcher.CandidateDispatch(row.candidateId(), attempts));
        }
        response.set("candidates", mapper.valueToTree(rows));
        response.set("bundles", new AcmgBusinessArtifacts(mapper).bundles(new ExplicitAlphaMissenseDispatcher.DispatchResult(null, rows, providers, evaluators), CRITERIA));
        response.put("providerCalls", providers).put("evaluatorCalls", evaluators).put("adoptionStatus", "NOT_APPLIED")
                .put("status", "COMPLETED_WITH_BLOCKERS").put("applicationReason", "EXPLICIT_SOURCE_ADOPTION_REQUIRED");
        response.put("bp4Reason", "PREDICTION_SPECIALIZED_REQUIREMENT_NOT_IMPLEMENTED");
    }

    /** No scientific execution. Consumes only server-owned saved bytes and independently pinned approval. */
    public ObjectNode adopt(Map<String, String> documents, List<ObjectNode> localApplications,
            java.util.function.Function<JsonNode, ObjectNode> functionalEffects) {
        ObjectNode out = mapper.createObjectNode(); var results = out.putArray("applications"); out.putArray("bundles");
        out.put("providerCalls", 0).put("evaluatorCalls", 0).put("bp4Reason", "PREDICTION_SPECIALIZED_REQUIREMENT_NOT_IMPLEMENTED");
        try {
            var artifacts = new LinkedHashMap<String, SavedPredictionApplicationInputs.Artifact>(); var pins = new LinkedHashMap<String, String>();
            for (var entry : documents.entrySet()) {
                byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                artifacts.put(entry.getKey(), new SavedPredictionApplicationInputs.Artifact(bytes, mapper.readTree(bytes))); pins.put(entry.getKey(), PredictionCalibration.sha256(bytes));
            }
            JsonNode run = mapper.readTree(documents.get("run")), privateData = mapper.readTree(documents.get("private"));
            if (run == null || privateData == null) throw new IllegalArgumentException("CLAUSE_SOURCE_STRUCTURE_INVALID");
            Approval approved = approvals.load(run.path("runId").asText(), privateData);
            ObjectNode settings = approved.settings().deepCopy();
            require(!identity.path("testOnly").asBoolean() || "SYNTHETIC".equals(run.path("dataClass").asText()), "REVEL_TEST_ONLY_SCOPE_REQUIRED");
            if (!identity.path("testOnly").asBoolean()) require(!"TEST_ONLY".equals(settings.at("/task/purpose").asText()), "REVEL_TEST_APPROVAL_FORBIDDEN");
            for (JsonNode policy : settings.path("policies"))
                if (policy.path("criterionIds").toString().contains("ACMG_AMP_2015:PP3"))
                    require(policyAllowed(hash.canonicalSha256(policy)), "REVEL_POLICY_OUTSIDE_RELEASE_SCOPE");
            settings.withObject("/task").put("assessmentAt", Instant.now().toString());
            var effectReads = out.putArray("functionalEffectReferences");
            for (JsonNode snapshot : settings.path("effectSnapshots")) {
                ObjectNode attempt = sourceAttempt(run, snapshot);
                ObjectNode plan = mapper.createObjectNode().set("context", attempt.path("context"));
                plan.set("candidateId", snapshot.path("candidateId"));
                plan.set("queryAllele", attempt.at("/context/variant/evidenceQueryAllele"));
                ObjectNode effects = functionalEffects.apply(plan); effectReads.add(effects);
                if (!"VERIFIED_SAVED_REFERENCES".equals(effects.path("status").asText())) {
                    ((ObjectNode) snapshot).put("status", "UNAVAILABLE");
                } else for (JsonNode ref : effects.path("references")) {
                    if ("EXPLICITLY_ADOPTED".equals(ref.path("usage").asText()))
                        ((ArrayNode) snapshot.path("effects")).addObject().put("kind", "SAME_EFFECT")
                                .put("referenceId", ref.path("savedEvidenceId").asText()).put("sourceSha256", ref.path("sourceRunSha256").asText())
                                .put("equivalence", "VERIFIED").put("adoption", "ADOPTED");
                }
            }
            // External supply is preserved. The local store contributes only actual saved adoptions in matching contexts.
            for (JsonNode snapshot : settings.path("effectSnapshots")) for (ObjectNode existing : localApplications)
                if ("ADOPTED".equals(existing.path("adoptionStatus").asText()) && snapshot.path("contextId").equals(existing.path("contextId")))
                    ((ArrayNode) snapshot.path("effects")).addObject().put("kind", "SAME_EFFECT").put("referenceId", existing.path("applicationId").asText())
                            .put("sourceSha256", hash.canonicalSha256(existing)).put("equivalence", "VERIFIED").put("adoption", "ADOPTED");
            var roots = new SavedPredictionApplicationInputs.Supply("SERVER_OWNED_SAVED_REVEL", pins, hash.canonicalSha256(SavedPredictionApplicationInputs.semanticSettings(settings)));
            var input = SavedPredictionApplicationInputs.verifyReviewedClause(mapper, artifacts, settings, roots);
            var bridge = new SavedPredictionContextBridge().prepare(mapper, input, approved.contexts());
            for (var receipt : new PredictionApplicationEligibility(mapper).assess(input, bridge)) {
                ObjectNode eligibility = receipt.json();
                if (!"ACMG_AMP_2015:PP3".equals(eligibility.path("criterionId").asText()) && !"BATCH".equals(eligibility.path("criterionId").asText())) continue;
                ObjectNode application = results.addObject().put("schemaVersion", "prediction-application-record-v1")
                        .put("criterionId", "ACMG_AMP_2015:PP3").put("candidateId", eligibility.path("candidateId").asText())
                        .put("hypothesisId", eligibility.path("hypothesisId").asText()).put("adoptionStatus", "NOT_APPLIED").put("appliedStrength", "NONE")
                        .put("status", "NOT_EVALUATED").put("reasonCode", eligibility.path("reasonCode").asText()).put("sourceRunId", run.path("runId").asText());
                application.set("eligibility", eligibility); application.set("sourceIdentity", input.identity());
                String status = eligibility.path("status").asText();
                if ("PRECONDITIONS_PASS".equals(status) || "NO_SUPPORT".equals(status)) {
                    JsonNode candidate = SavedPredictionApplicationInputs.unique(settings.path("candidates"), "candidateId", application.path("candidateId").asText());
                    var view = PredictionClauseSourceView.read(mapper, input, candidate, application.path("hypothesisId").asText(), "PP3").json();
                    require(view.path("issue").asText().isEmpty(), "CLAUSE_APPLICATION_SOURCE_INVALID");
                    ObjectNode sourceAttempt = sourceAttempt(run, application);
                    JsonNode clause = sourceAttempt.at("/transcriptInput/reviewedPrediction/clauses/PP3/result");
                    String outcome = view.path("sourceClauseOutcome").asText();
                    require(Set.of("MET", "NOT_MET").contains(outcome) && ("MET".equals(outcome) == "PRECONDITIONS_PASS".equals(status)), "CLAUSE_APPLICATION_OUTCOME_MISMATCH");
                    application.put("status", outcome).put("adoptionStatus", "MET".equals(outcome) ? "ADOPTED" : "NOT_ADOPTED")
                            .put("appliedStrength", "MET".equals(outcome) ? "SUPPORTING" : "NONE").put("contextId", sourceAttempt.at("/context/contextId").asText())
                            .put("scope", "REVEL_PROTEIN_MISSENSE_ONLY").put("otherSplicingMechanismsExcluded", false);
                    application.set("context", sourceAttempt.path("context")); application.set("sourceClause", clause.deepCopy());
                    // Register the unchanged production evidence-v2 evaluation; eligibility remains a separate NONE receipt.
                    application.set("criterionEvaluation", clause.path("evaluation").deepCopy());
                    application.set("atomicEvidence", sourceAttempt.path("atomicEvidence").deepCopy());
                }
                application.put("applicationId", "prediction-application-sha256-" + hash.canonicalSha256(application));
            }
        } catch (Exception invalid) {
            results.removeAll(); results.addObject().put("status", "NOT_EVALUATED").put("adoptionStatus", "NOT_APPLIED")
                    .put("appliedStrength", "NONE").put("reasonCode", invalid instanceof IllegalArgumentException e
                            ? SavedPredictionApplicationInputs.safeClauseIssue(e) : "REVEL_APPLICATION_APPROVAL_UNAVAILABLE");
        }
        boolean blocked = results.isEmpty(); for (JsonNode r : results) blocked |= "NOT_EVALUATED".equals(r.path("status").asText());
        return out.put("status", blocked ? "COMPLETED_WITH_BLOCKERS" : "COMPLETED");
    }
    private static ObjectNode sourceAttempt(JsonNode run, JsonNode application) {
        JsonNode row = SavedPredictionApplicationInputs.unique(run.path("candidates"), "candidateId", application.path("candidateId").asText());
        for (JsonNode a : row.path("attempts")) if (application.path("hypothesisId").equals(a.at("/preflight/original/hypothesisId")) && a instanceof ObjectNode object) return object;
        throw new IllegalArgumentException("CLAUSE_HYPOTHESIS_NOT_SAVED");
    }
    private boolean policyAllowed(String sha) {
        for (JsonNode allowed : identity.path("allowedPolicySha256s")) if (sha.equals(allowed.asText())) return true;
        return false;
    }
}
