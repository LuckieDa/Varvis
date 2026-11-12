package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.lang.Nullable;
import org.nbscn.varvis.variantanalysis.service.CurrentAcmgEvaluationSession;
import org.nbscn.varvis.variantanalysis.service.CurrentClinicalAllocation;

/** Source qualification, actual old-core execution, then current native adoption.
 * Stable dependencies precede the claim. Binding is request-owned and once-only; a
 * reservation never becomes awarded points and unselected PP4 never consumes PP1 capacity.
 */
public final class Pp4CurrentAdoptionProducer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Pp4ProductionMethodRegistration registration;
    private final Pp4RegisteredRuleInputs rules;
    private final @Nullable Pp4ServerRegisteredInputs registered;
    private final AtomicReference<CurrentClinicalAllocation.CurrentUseSource> currentUse = new AtomicReference<>();
    private final ObjectNode identity;

    /** Legacy no-native-input callers remain unavailable; this constructor grants no permission. */
    public Pp4CurrentAdoptionProducer(Pp4ProductionMethodRegistration registration, Pp4RegisteredRuleInputs rules) {
        this(registration, rules, null);
    }
    public Pp4CurrentAdoptionProducer(Pp4ServerRegisteredInputs registered) {
        this(Objects.requireNonNull(registered).registration(), registered.rules(), registered);
    }
    private Pp4CurrentAdoptionProducer(Pp4ProductionMethodRegistration registration, Pp4RegisteredRuleInputs rules,
            @Nullable Pp4ServerRegisteredInputs registered) {
        this.registration = Objects.requireNonNull(registration); this.rules = Objects.requireNonNull(rules); this.registered = registered;
        identity = JSON.createObjectNode().put("schemaVersion", "pp4-current-adoption-producer-v1")
                .put("producerId", "native-source-qualified-pp4-current-allocation")
                .put("producerVersion", "1.1.0")
                .put("registrationSha256", registration.sha256()).put("registeredRuleInputsSha256", rules.sha256())
                .put("currentAllocationCapability", registered == null ? "UNAVAILABLE_NO_SERVER_REGISTERED_INPUTS" : "OPAQUE_CURRENT_CLINICAL_ALLOCATION_V1")
                .put("phase", "BEFORE_PP1_EXECUTION").put("clinicalValidationCompleted", false);
        identity.set("registration", registration.identity()); identity.set("registeredRuleInputs", rules.identity());
        identity.set("allocationImplementation", CurrentClinicalAllocation.implementationIdentity());
        if (registered != null) identity.set("serverRegisteredInputs", registered.identity());
    }
    public ObjectNode identity() { return identity.deepCopy(); }
    public Optional<Pp4ServerRegisteredInputs> registeredInputs() { return Optional.ofNullable(registered); }
    public void bindCurrentUseSource(CurrentClinicalAllocation.CurrentUseSource source) {
        Objects.requireNonNull(source);
        if (registered == null || source.registeredInputs() != registered) throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_REGISTERED_INPUTS_MISMATCH");
        if (!currentUse.compareAndSet(null, source)) throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_CURRENT_USE_ALREADY_BOUND");
    }

    public Outcome evaluate(CurrentAcmgEvaluationSession session, ObjectNode attempt) {
        Objects.requireNonNull(session); Objects.requireNonNull(attempt);
        ObjectNode captured = attempt.deepCopy(); ObjectNode result = base(session, captured);
        Pp4QualifiedMaterial qualifiedMaterial = null; CoreExecution coreExecution = null;
        try {
            if (session.attempts().stream().noneMatch(captured::equals))
                return outcome(session, captured, result, "FAILED", "PP4_CURRENT_ATTEMPT_NOT_IN_SESSION", null, null);
            if (!"READY".equals(captured.path("bindingStatus").asText()) || !captured.at("/binding/identity/preparedContext").isObject())
                return outcome(session, captured, result, "UNAVAILABLE", "PP4_CURRENT_ATTEMPT_BINDING_UNAVAILABLE", null, null);
            if (!Pp4ProductionMethodRegistration.canonical(captured.at("/binding/identity")).equals(captured.path("bindingIdentitySha256").asText()))
                return outcome(session, captured, result, "FAILED", "PP4_CURRENT_ATTEMPT_BINDING_PIN_MISMATCH", null, null);
            ObjectNode context = ((ObjectNode) captured.at("/binding/identity/preparedContext")).deepCopy();
            var source=currentUse.get();
            var identity=source==null?null:source.clinicalIdentity(session,captured).orElse(null);
            var verification = Pp4QualifiedMaterial.verify(registration, context,identity); result.set("qualification", verification.snapshot());
            if (verification.material().isEmpty()) return outcome(session, captured, result, "UNAVAILABLE",
                    verification.snapshot().path("reasonCode").asText("PP4_NATIVE_FACTS_UNAVAILABLE"), null, null);
            var material = verification.material().orElseThrow(); qualifiedMaterial = material;
            result.put("materialSha256", material.sha256()); result.set("material", material.snapshot());
            result.set("materialOriginalsSha256", material.snapshot().path("originalsSha256").deepCopy());
            if (!rules.matchesContext(context)) return outcome(session, captured, result, "UNAVAILABLE",
                    "PP4_REGISTERED_RULE_SCOPE_OR_DATA_CLASS_MISMATCH", material, null);
            var observation = Pp4LegacyMaterialAdapter.observation(material);
            if (source == null) {
                Pp4LegacyMaterialAdapter.seal(observation, material);
                new Pp4PhenotypeRecordAdapter().adapt((ObjectNode) observation.path("facts"));
                var input = rules.derive(material, observation); byte[] raw = JSON.writeValueAsBytes(input);
                var bundle = Pp4PhenotypeBundle.load(JSON, raw, Pp4ProductionMethodRegistration.rawSha(raw), Pp4ProductionMethodRegistration.canonical(input));
                result.put("derivedCoreInputSha256", Pp4ProductionMethodRegistration.canonical(bundle.snapshot()))
                        .put("coreExecutionStatus", "NOT_RUN_CURRENT_ALLOCATION_UNAVAILABLE");
                return outcome(session, captured, result, "UNAVAILABLE", "PP4_CURRENT_CLINICAL_ALLOCATION_UNAVAILABLE", material, null);
            }
            if (!source.matches(session)) return outcome(session, captured, result, "UNAVAILABLE", "PP1_CURRENT_CONTRIBUTION_SESSION_MISMATCH", material, null);
            var grant = source.reserve(session, captured, material); result.set("currentUseGrant", grant.snapshot());
            require(grant.matches(session, captured, material), "PP1_CURRENT_CONTRIBUTION_GRANT_SCOPE_MISMATCH");
            if (!grant.isAvailable()) return outcome(session, captured, result, "UNAVAILABLE", grant.snapshot().path("reasonCode").asText("PP1_CURRENT_CLINICAL_ALLOCATION_UNAVAILABLE"), material, null);
            ObjectNode allocation = grant.legacyAllocation(); var locus = (ObjectNode) observation.path("locusAttribution");
            for (String field : List.of("usedEvidenceStatus", "usedEvidence", "clinicalUseStatus", "clinicalUse")) {
                require(allocation.has(field), "PP1_CURRENT_CONTRIBUTION_LEGACY_ALLOCATION_INCOMPLETE"); locus.set(field, allocation.path(field).deepCopy());
            }
            Pp4LegacyMaterialAdapter.seal(observation, material);
            new Pp4PhenotypeRecordAdapter().adapt((ObjectNode) observation.path("facts"));
            var input = rules.derive(material, observation); byte[] raw = JSON.writeValueAsBytes(input);
            var bundle = Pp4PhenotypeBundle.load(JSON, raw, Pp4ProductionMethodRegistration.rawSha(raw), Pp4ProductionMethodRegistration.canonical(input));
            // The only CoreExecution constructor call follows the actual unchanged evaluator.
            coreExecution = new CoreExecution(grant, material, raw, new Pp4PhenotypeCriterionEvaluator(JSON).evaluate(bundle));
            result.set("coreExecution", coreExecution.snapshot()); result.put("coreExecutionSha256", Pp4ProductionMethodRegistration.canonical(coreExecution.snapshot()));
            result.put("coreExecutionStatus", "ACTUAL_CORE_EXECUTED").put("evidenceSelected", grant.evidenceSelected());
            var adoption = source.finish(grant, coreExecution); require(adoption.matches(grant, coreExecution), "PP1_CURRENT_CONTRIBUTION_ADOPTION_SCOPE_MISMATCH");
            ObjectNode adopted = adoption.snapshot(); result.set("currentAdoption", adopted);
            String kind = adopted.path("kind").asText(), reason = adopted.path("reasonCode").asText();
            if (!adoption.isComplete()) return outcome(session, captured, result, kind, reason, material, coreExecution);
            require(List.of("ADOPTED", "NOT_ADOPTED", "NOT_SELECTED", "NOT_APPLICABLE").contains(kind)
                    && adopted.path("awardedPoints").isTextual(), "PP1_CURRENT_CONTRIBUTION_TERMINAL_INVALID");
            require(grant.evidenceSelected() || "NOT_SELECTED".equals(kind), "PP1_CURRENT_CONTRIBUTION_UNSELECTED_AWARD_FORBIDDEN");
            result.put("complete", true); result.set("finalPoints", adopted.path("awardedPoints").deepCopy());
            result.set("eventIds", adopted.path("allocatedEventIds").deepCopy()); result.set("exclusiveFactIds", adopted.path("allocatedFactIds").deepCopy());
            if (!"NOT_SELECTED".equals(kind)) result.put("sourceResultSha256", coreExecution.sourceResultSha256()).put("evaluationSha256", coreExecution.evaluationSha256());
            return outcome(session, captured, result, kind, reason, material, coreExecution);
        } catch (IOException | IllegalArgumentException invalid) {
            result.put("diagnostic", safeReason(invalid));
            return outcome(session, captured, result, "FAILED", "PP1_CURRENT_CONTRIBUTION_EXECUTION_FAILED", qualifiedMaterial, coreExecution);
        }
    }

    private ObjectNode base(CurrentAcmgEvaluationSession session, ObjectNode attempt) {
        var result = JSON.createObjectNode().put("schemaVersion", "pp4-current-adoption-outcome-v1").put("complete", false).put("phase", "BEFORE_PP1_EXECUTION")
                .put("registrationSha256", registration.sha256()).put("producerIdentitySha256", Pp4ProductionMethodRegistration.canonical(identity));
        result.set("sessionIdentity", session.identity()); var target = result.putObject("attemptIdentity");
        for (String key : List.of("candidateId", "hypothesisId", "bindingIdentitySha256")) if (attempt.has(key)) target.set(key, attempt.path(key).deepCopy()); else target.putNull(key);
        result.putNull("sourceResultSha256").putNull("evaluationSha256").putNull("materialSha256").putNull("finalPoints");
        result.set("sourcePins", registration.inputs().identity().path("originals").deepCopy()); result.set("registeredRuleInputs", rules.identity());
        result.putArray("eventIds"); result.putArray("exclusiveFactIds"); return result;
    }
    private Outcome outcome(CurrentAcmgEvaluationSession session, ObjectNode attempt, ObjectNode result, String kind, String reason,
            @Nullable Pp4QualifiedMaterial material, @Nullable CoreExecution core) {
        var diagnostic = JSON.createObjectNode().put("schemaVersion", "pp4-current-native-production-diagnostic-v1").put("reasonCode", reason)
                .put("currentAllocationCapability", currentUse.get() == null ? "UNAVAILABLE" : "NATIVE_CURRENT_SOURCE_BOUND");
        if (result.has("qualification")) diagnostic.set("qualification", result.path("qualification").deepCopy());
        if (result.has("diagnostic")) diagnostic.set("executionDiagnostic", result.path("diagnostic").deepCopy());
        diagnostic.set("sourcePins", result.path("sourcePins").deepCopy()); result.set("materialDiagnostic", diagnostic);
        String outer = reason.startsWith("PP1_") ? reason : "PP4_CURRENT_CLINICAL_ALLOCATION_UNAVAILABLE".equals(reason)
                ? "PP1_CURRENT_CLINICAL_ALLOCATION_UNAVAILABLE" : reason.startsWith("PP4_REGISTERED_RULE_")
                ? "PP1_CURRENT_CONTRIBUTION_RULE_UNAVAILABLE" : reason.startsWith("PP4_CURRENT_ATTEMPT_")
                ? "PP1_CURRENT_CONTRIBUTION_BINDING_UNAVAILABLE" : "PP1_CURRENT_CONTRIBUTION_MATERIAL_UNAVAILABLE";
        result.put("kind", kind).put("reasonCode", outer).put("receiptSha256", Pp4ProductionMethodRegistration.canonical(result));
        return new Outcome(session, attempt, result, material, core);
    }
    private static String safeReason(Exception invalid) { String m = invalid.getMessage(); return m != null && m.matches("(?:PP1|PP4)_[A-Z0-9_]+") ? m : "PP4_NATIVE_PRODUCTION_INPUT_INVALID"; }
    private static void require(boolean ok, String code) { if (!ok) throw new IllegalArgumentException(code); }

    /** Issued only immediately after the actual old evaluator returns; no JSON/public factory. */
    public static final class CoreExecution {
        private final CurrentClinicalAllocation.Grant grant;
        private final ObjectNode sourceResult;
        private final ObjectNode evaluation;
        private final ObjectNode snapshot;
        private final ObjectNode index;
        private final Map<String, byte[]> bytes;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private CoreExecution(CurrentClinicalAllocation.Grant grant, Pp4QualifiedMaterial material, byte[] input, ObjectNode actual) throws IOException {
            this.grant = grant; sourceResult = actual.deepCopy();
            require("CORE_RESULT_ONLY".equals(actual.path("resultMode").asText()) && actual.path("adoptionSupported").isBoolean()
                    && !actual.path("adoptionSupported").asBoolean() && !actual.path("classificationEnabled").asBoolean(true), "PP1_CURRENT_CONTRIBUTION_CORE_CONTRACT_CHANGED");
            JsonNode evaluated = actual.path("evaluation").isObject() ? actual.path("evaluation") : actual.at("/criterionEvaluations/0");
            require(evaluated.isObject(), "PP1_CURRENT_CONTRIBUTION_ACTUAL_EVALUATION_REQUIRED"); evaluation = ((ObjectNode) evaluated).deepCopy();
            index = JSON.createObjectNode().put("schemaVersion", "pp4-actual-core-artifacts-v1"); var artifacts = index.putArray("artifacts");
            var captured = new TreeMap<String, byte[]>();
            addArtifact(captured, artifacts, "INPUT", input, JSON.readTree(input));
            addArtifact(captured, artifacts, "SOURCE_RESULT", JSON.writeValueAsBytes(sourceResult), sourceResult);
            addArtifact(captured, artifacts, "EVALUATION", JSON.writeValueAsBytes(evaluation), evaluation); bytes = copied(captured);
            snapshot = JSON.createObjectNode().put("schemaVersion", "pp4-actual-core-execution-v1")
                    .put("grantSha256", grant.snapshot().path("grantSha256").asText())
                    .put("grantSnapshotSha256", Pp4ProductionMethodRegistration.canonical(grant.snapshot())).put("materialSha256", material.sha256())
                    .put("contextId", material.snapshot().at("/context/contextId").asText())
                    .put("inputRawSha256", Pp4ProductionMethodRegistration.rawSha(input)).put("inputCanonicalSha256", Pp4ProductionMethodRegistration.canonical(JSON.readTree(input)))
                    .put("sourceResultSha256", Pp4ProductionMethodRegistration.canonical(sourceResult)).put("evaluationSha256", Pp4ProductionMethodRegistration.canonical(evaluation))
                    .put("actualStatus", evaluation.at("/evaluationResult/status").asText()).put("actualReasonCode", evaluation.at("/evaluationResult/reasonCode").asText())
                    .put("legacyResultMode", "CORE_RESULT_ONLY").put("legacyAdoptionSupported", false);
            if (evaluation.has("appliedStrength")) snapshot.set("appliedStrength", evaluation.path("appliedStrength").deepCopy()); else snapshot.putNull("appliedStrength");
            snapshot.set("artifactIndex", index.deepCopy());
        }
        public boolean matches(CurrentClinicalAllocation.Grant actual) { return actual == grant && snapshot.path("grantSha256").equals(actual.snapshot().path("grantSha256")) && snapshot.path("grantSnapshotSha256").asText().equals(Pp4ProductionMethodRegistration.canonical(actual.snapshot())); }
        public ObjectNode snapshot() { return snapshot.deepCopy(); }
        public ObjectNode sourceResult() { return sourceResult.deepCopy(); }
        public ObjectNode evaluation() { return evaluation.deepCopy(); }
        public String sourceResultSha256() { return snapshot.path("sourceResultSha256").asText(); }
        public String evaluationSha256() { return snapshot.path("evaluationSha256").asText(); }
        public ObjectNode artifactIndex() { return index.deepCopy(); }
        public Map<String, byte[]> artifactBytes() { return copied(bytes); }
        private static void addArtifact(Map<String, byte[]> bytes, com.fasterxml.jackson.databind.node.ArrayNode index, String kind, byte[] raw, JsonNode value) {
            String pin = Pp4ProductionMethodRegistration.rawSha(raw); bytes.put(pin, raw.clone());
            index.addObject().put("kind", kind).put("rawSha256", pin).put("canonicalSha256", Pp4ProductionMethodRegistration.canonical(value)).put("byteLength", raw.length);
        }
        private static Map<String, byte[]> copied(Map<String, byte[]> original) { var r = new TreeMap<String, byte[]>(); original.forEach((pin, value) -> r.put(pin, value.clone())); return java.util.Collections.unmodifiableMap(r); }
    }
    public static final class Outcome {
        private final CurrentAcmgEvaluationSession session;
        private final ObjectNode attempt;
        private final ObjectNode value;
        private final @Nullable Pp4QualifiedMaterial material;
        private final @Nullable CoreExecution core;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private Outcome(CurrentAcmgEvaluationSession session, ObjectNode attempt, ObjectNode value, @Nullable Pp4QualifiedMaterial material, @Nullable CoreExecution core) {
            this.session = session; this.attempt = attempt.deepCopy(); this.value = value.deepCopy(); this.material = material; this.core = core;
        }
        public Optional<Pp4QualifiedMaterial> qualifiedMaterial() { return Optional.ofNullable(material); }
        public Optional<CoreExecution> coreExecution() { return Optional.ofNullable(core); }
        public ObjectNode snapshot() { return value.deepCopy(); }
        public boolean matches(CurrentAcmgEvaluationSession current, ObjectNode candidate) { return current == session && candidate != null && attempt.equals(candidate) && current.attempts().stream().anyMatch(attempt::equals); }
        public boolean isComplete() { return value.path("complete").asBoolean(false); }
    }
}
