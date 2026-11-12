package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFIteratorBuilder;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.engine.PublishedSampleFilter;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.provider.LocalEvidenceBatchRuntime;
import org.nbscn.varvis.variantanalysis.provider.Pp4CurrentAdoptionProducer;
import org.nbscn.varvis.variantanalysis.provider.Pp4QualifiedMaterial;
import org.nbscn.varvis.variantanalysis.provider.Pp4ProductionMethodRegistration;
import org.nbscn.varvis.variantanalysis.provider.Pp4ServerRegisteredInputs;

/** Native current clinical-use authority is issued only by the post-claim service path.
 * Original-call replay below proves identity, never phenotype qualification or an award.
 */
public final class CurrentClinicalAllocation {
    private static final ObjectMapper JSON = new ObjectMapper();
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    private CurrentClinicalAllocation() { }

    /** Stable implemented capability, never a current-use issuer or approval receipt. */
    public static ObjectNode implementationIdentity() {
        var result = JSON.createObjectNode().put("schemaVersion", "current-native-clinical-allocation-implementation-v1")
                .put("protocolVersion", "1.0.0").put("reservationIsAward", false);
        var pins = result.putObject("implementationPins");
        for (Class<?> type : List.of(CurrentClinicalAllocation.class, CurrentUseSource.class, Grant.class, Adoption.class,
                NativeCall.class, FileStamp.class, NativeClinicalIdentity.class, CurrentAcmgEvaluationSession.class, LocalEvidenceBatchRuntime.class, LocalEvidenceBatchRuntime.CurrentConsumerExecution.class)) {
            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                require(input != null, "PP1_CURRENT_CLINICAL_IMPLEMENTATION_REQUIRED");
                if (input == null) throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_IMPLEMENTATION_REQUIRED");
                byte[] bytes = input.readNBytes(1048577);
                require(bytes.length > 0 && bytes.length <= 1048576, "PP1_CURRENT_CLINICAL_IMPLEMENTATION_LIMIT");
                pins.put(type.getName(), HexFormat.of().formatHex(sha256().digest(bytes)));
            } catch (IOException unavailable) { throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_IMPLEMENTATION_REQUIRED", unavailable); }
        }
        return result;
    }

    /** Only the service may issue this source, after the real persistent claim and after
     * the runtime has actually executed every first-stage attempt. Neither JSON nor history
     * can reconstitute the native execution token or any of the private-issued capabilities.
     */
    static CurrentUseSource prepareAfterClaim(ObjectMapper mapper, AcmgBusinessStore store,
            CurrentAcmgEvaluationSession session, SelectedEvidencePreparation prepared,
            AcmgExecutionSources.Recovered recovered, List<VariantInterpretation> ranked,
            Pp4ServerRegisteredInputs inputs, LocalEvidenceBatchRuntime.CurrentConsumerExecution consumers) throws IOException {
        Objects.requireNonNull(mapper); Objects.requireNonNull(store); Objects.requireNonNull(session);
        Objects.requireNonNull(prepared); Objects.requireNonNull(inputs); Objects.requireNonNull(consumers);
        var identity = session.identity(); String analysis = identity.path("analysisId").asText(), runId = identity.path("runId").asText();
        var run = store.run(analysis, runId).orElseThrow(() -> new IllegalArgumentException("PP1_CURRENT_CLINICAL_CLAIM_REQUIRED"));
        var hashes = new AcmgBusinessArtifacts(mapper);
        JsonNode request = mapper.readTree(run.requestJson());
        if (!("RUNNING".equals(run.status()) && analysis.equals(run.analysisId()) && runId.equals(run.id())
                && identity.path("requestSha256").asText().equals(run.requestSha256()) && request != null
                && run.requestSha256().equals(hashes.requestHash(request)))) {
            throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_CLAIM_MISMATCH");
        }
        var source = store.source(analysis).orElseThrow(() -> new IllegalArgumentException("PP1_CURRENT_CLINICAL_EXECUTION_SOURCE_REQUIRED"));
        if (!(hash(source.sha256()) && source.sha256().equals(AcmgBusinessStore.sha(source.json()))
                && source.sha256().equals(identity.path("executionSourceSha256").asText())
                && source.sha256().equals(request.at("/localBatchPublishedInputIdentity/executionSourceSha256").asText())
                && recovered != null && source.sha256().equals(recovered.snapshotSha256()))) {
            throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_EXECUTION_SOURCE_MISMATCH");
        }
        var assembly = prepared.assembly(); var ranking = mapper.createArrayNode();
        if (!(assembly != null && analysis.equals(assembly.analysisId()) && assembly.unmatchedInputs().isEmpty())) {
            throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_SELECTION_MISMATCH");
        }
        for (String candidate : assembly.candidateIds()) ranking.add(prepared.formalCandidate(candidate));
        // Raw typed DTO bytes and the prepared tree are two different witnesses under
        // Jackson decimal normalization. Retain the original pin, and verify both.
        require(ranked != null && ranked.stream().allMatch(Objects::nonNull)
                && assembly.candidateIds().equals(ranked.stream().map(value->value.getCandidateId()).toList())
                && AcmgBusinessStore.sha(mapper.writeValueAsString(ranked)).equals(request.at("/localBatchPublishedInputIdentity/candidatesRawSha256").asText())
                && ranking.equals(mapper.valueToTree(ranked))
                && request.at("/executionProfile/enabledCriteria").isArray()
                && strings(request.at("/executionProfile/enabledCriteria")).equals(strings(identity.path("enabledCriteria"))),
                "PP1_CURRENT_CLINICAL_SELECTED_PROFILE_MISMATCH");
        for (var attempt : session.attempts()) if ("READY".equals(attempt.path("bindingStatus").asText()))
            require(prepared.pp1SelectionContext(attempt.path("candidateId").asText(), attempt.path("hypothesisId").asText())
                    .equals(attempt.at("/binding/identity/preparedContext")), "PP1_CURRENT_CLINICAL_PREPARED_CONTEXT_MISMATCH");
        JsonNode original = mapper.readTree(source.json());
        require(original != null && original.isObject() && analysis.equals(original.path("analysisId").asText())
                && recovered.execution() != null && recovered.execution().getInputSource() != null
                && analysis.equals(recovered.execution().getInputSource().analysisId()), "PP1_CURRENT_CLINICAL_EXECUTION_SOURCE_INVALID");
        // Identity failures remain hard rejections. Physical replay failures after this
        // verified association are evidence unavailability, never failure of other modules.
        verifyPublishedSourceAssociation(recovered, original);
        require(consumers.matches(session), "PP1_CURRENT_CLINICAL_CONSUMER_SESSION_MISMATCH");
        AcmgRunControl.checkpoint();
        Map<String, NativeCall> calls; String globalIssue = null;
        try { calls = nativeCalls(mapper, recovered, original, ranked, assembly.candidateIds()); }
        catch (IOException unavailable) {
            globalIssue = nativeReason(unavailable, "PP1_CURRENT_CLINICAL_VCF_REPLAY_FAILED");
            calls = blockedCalls(assembly.candidateIds(), globalIssue);
        } catch (IllegalArgumentException unavailable) {
            globalIssue = nativeReason(unavailable, "PP1_CURRENT_CLINICAL_NATIVE_CALL_UNAVAILABLE");
            calls = blockedCalls(assembly.candidateIds(), globalIssue);
        }
        AcmgRunControl.checkpoint();
        return new CurrentUseSource(store, session, inputs, consumers, calls, globalIssue);
    }

    /** Native, request-owned source. A snapshot is an audit only and cannot recreate it. */
    public static final class CurrentUseSource {
        private final AcmgBusinessStore store;
        private final CurrentAcmgEvaluationSession session;
        private final Pp4ServerRegisteredInputs inputs;
        private final LocalEvidenceBatchRuntime.CurrentConsumerExecution consumers;
        private final ObjectNode consumerAudit;
        private final String consumerPin;
        private final Map<String, NativeCall> calls;
        private final String globalNativeIssue;
        private final Map<String, ObjectNode> attempts = new LinkedHashMap<>();
        private final Map<String, Pp4QualifiedMaterial> materials = new HashMap<>();
        private final Map<String, NativeClinicalIdentity> clinicalIdentities = new HashMap<>();
        private final Map<String, String> materialBlockers = new HashMap<>();
        private final Set<String> collisions = new HashSet<>();
        private final Map<String, Grant> grants = new HashMap<>();
        private final Map<Grant, Adoption> finished = new HashMap<>();
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private CurrentUseSource(AcmgBusinessStore store, CurrentAcmgEvaluationSession session, Pp4ServerRegisteredInputs inputs,
                LocalEvidenceBatchRuntime.CurrentConsumerExecution consumers, Map<String, NativeCall> calls, String globalIssue) {
            this.store = store; this.session = session; this.inputs = inputs; this.consumers = consumers; this.calls = Map.copyOf(calls);
            globalNativeIssue = globalIssue;
            require(consumers.matches(session), "PP1_CURRENT_CLINICAL_CONSUMER_SESSION_MISMATCH");
            consumerAudit = consumers.snapshot(); consumerPin = canonical(consumerAudit);
            require("current-native-consumer-execution-v1".equals(consumerAudit.path("schemaVersion").asText())
                    && session.identity().equals(consumerAudit.path("sessionIdentity"))
                    && session.identity().path("executionSourceSha256").equals(consumerAudit.path("sourceSnapshotSha256"))
                    && "ALL_ATTEMPTS_STAGE1_COMPLETE_BEFORE_PP1".equals(consumerAudit.path("phase").asText())
                    && consumerAudit.path("pp1EvaluatorCallsBeforeBind").isIntegralNumber()
                    && consumerAudit.path("pp1EvaluatorCallsBeforeBind").asLong(-1) == 0,
                    "PP1_CURRENT_CLINICAL_CONSUMER_PHASE_UNPROVEN");
            require(strings(consumerAudit.path("executionCriteria")).equals(strings(session.identity().path("enabledCriteria")))
                    && strings(consumerAudit.path("configuredCriteria")).containsAll(strings(consumerAudit.path("executionCriteria")))
                    && "DEFERRED".equals(consumerAudit.at("/registry/pp1/phase").asText())
                    && "DEFERRED_REGISTERED".equals(consumerAudit.at("/registry/pp4/phase").asText())
                    && consumerAudit.at("/registry/pp4/configured").isBoolean() && consumerAudit.at("/registry/pp4/configured").asBoolean(false)
                    && inputs.identity().equals(consumerAudit.at("/registry/pp4/identity/producerIdentity/serverRegisteredInputs")),
                    "PP1_CURRENT_CLINICAL_CONSUMER_REGISTRY_UNPROVEN");
            var actual = session.attempts();
            require(consumerAudit.path("attempts").isArray() && consumerAudit.path("attempts").size() == actual.size(),
                    "PP1_CURRENT_CLINICAL_CONSUMER_ATTEMPT_COVERAGE_REQUIRED");
            for (var attempt : actual) {
                String key = key(attempt); require(attempts.putIfAbsent(key, attempt.deepCopy()) == null, "PP1_CURRENT_CLINICAL_ATTEMPT_AMBIGUOUS");
                var entries = new ArrayList<JsonNode>();
                for (var row : consumerAudit.path("attempts")) if (sameAttempt(attempt, row)) entries.add(row);
                require(entries.size() == 1 && attempt.path("bindingStatus").equals(entries.get(0).path("bindingStatus"))
                        && attempt.path("bindingIdentitySha256").asText("").equals(entries.get(0).path("bindingIdentitySha256").asText("")),
                        "PP1_CURRENT_CLINICAL_CONSUMER_ATTEMPT_SCOPE_MISMATCH");
                if (!"READY".equals(attempt.path("bindingStatus").asText())) continue;
                var context = (ObjectNode) attempt.at("/binding/identity/preparedContext");
                var call=calls.get(attempt.path("candidateId").asText());
                if(globalIssue==null && call!=null && call.facts!=null && knownId(context.at("/patientContext/patientContextId"))
                        && knownId(context.at("/patientContext/familyContextId")) && "PROBAND".equals(context.at("/patientContext/role").asText())) {
                    var actualCall=call.facts;
                    var links=inputs.clinicalSampleLinks().stream().filter(link -> link.matchesNativeCall(actualCall)).toList();
                    if(links.size()==1) {
                        var link=links.get(0);
                        if(!link.personId().equals(context.at("/patientContext/patientContextId").asText())
                                ||!link.familyId().equals(context.at("/patientContext/familyContextId").asText()))
                            clinicalIdentities.put(key,new NativeClinicalIdentity(this,attempt,link,call));
                    }
                }
                var verified = Pp4QualifiedMaterial.verify(inputs.registration(), context,clinicalIdentities.get(key));
                if (verified.material().isPresent()) materials.put(key, verified.material().orElseThrow());
                else materialBlockers.put(key, "PP1_CURRENT_CLINICAL_MATERIAL_UNAVAILABLE");
            }
            // Compute all collision participants before the first reservation, never first-winner.
            var use = new HashMap<String, Set<String>>();
            materials.forEach((key, material) -> {
                for (String event : material.proposedEventIds()) use.computeIfAbsent("event:" + event, ignored -> new HashSet<>()).add(key);
                for (String fact : material.observedFactIds()) use.computeIfAbsent("fact:" + fact, ignored -> new HashSet<>()).add(key);
            });
            for (var users : use.values()) if (users.size() > 1) collisions.addAll(users);
        }
        /** Read-only lookup of a handle already issued from the real post-claim native
         * sample chain. This method never issues from caller JSON or same-name aliases. */
        public java.util.Optional<NativeClinicalIdentity> clinicalIdentity(CurrentAcmgEvaluationSession candidate,ObjectNode actualAttempt) {
            require(matches(candidate) && actualAttempt!=null && actualAttempt.equals(attempts.get(key(actualAttempt))),"PP1_CURRENT_CLINICAL_NATIVE_IDENTITY_SCOPE_MISMATCH");
            liveClaim();return java.util.Optional.ofNullable(clinicalIdentities.get(key(actualAttempt)));
        }
        public Pp4ServerRegisteredInputs registeredInputs() { return inputs; }
        public boolean matches(CurrentAcmgEvaluationSession candidate) { return candidate == session && consumers.matches(candidate); }
        public ObjectNode identity() {
            var value = implementationIdentity(); value.put("registeredInputsSha256", canonical(inputs.identity())); return value;
        }
        public synchronized ObjectNode snapshot() {
            var value = JSON.createObjectNode().put("schemaVersion", "current-native-clinical-use-source-v1")
                    .put("consumerExecutionSha256", consumerPin).put("registeredInputsSha256", canonical(inputs.identity()));
            value.set("sessionIdentity", session.identity());
            if (globalNativeIssue == null) value.putNull("globalNativeIssue"); else value.put("globalNativeIssue", globalNativeIssue);
            var rows = value.putArray("attempts");
            for (var attempt : attempts.values()) {
                var row = rows.addObject().put("candidateId", attempt.path("candidateId").asText()).put("hypothesisId", attempt.path("hypothesisId").asText());
                var identity=clinicalIdentities.get(key(attempt));if(identity!=null) row.set("nativeClinicalIdentity",identity.snapshot());
                var call = calls.get(attempt.path("candidateId").asText());
                if (call != null && call.facts != null) row.put("nativeCallStatus", "VERIFIED").put("nativeCallProofSha256", canonical(call.facts));
                else row.put("nativeCallStatus", "UNAVAILABLE").put("nativeCallIssue", call == null ? "PP1_CURRENT_CLINICAL_NATIVE_CALL_UNAVAILABLE" : call.blocker);
                if (collisions.contains(key(attempt))) row.put("reasonCode", "PP1_CURRENT_CLINICAL_FACT_ALLOCATION_CONFLICT");
                if (grants.containsKey(key(attempt))) row.set("grant", grants.get(key(attempt)).snapshot());
            }
            return value;
        }
        public synchronized Grant reserve(CurrentAcmgEvaluationSession candidate, ObjectNode actualAttempt, Pp4QualifiedMaterial material) {
            if (!(matches(candidate) && actualAttempt != null && attempts.containsKey(key(actualAttempt))
                    && attempts.get(key(actualAttempt)).equals(actualAttempt) && material != null)) {
                throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_RESERVATION_SCOPE_MISMATCH");
            }
            liveClaim();
            String key = key(actualAttempt); var context = actualAttempt.at("/binding/identity/preparedContext");
            require(context.isObject() && material.matchesContext((ObjectNode) context), "PP1_CURRENT_CLINICAL_MATERIAL_SCOPE_MISMATCH");
            if (grants.containsKey(key)) {
                var prior = grants.get(key); require(prior.matches(candidate, actualAttempt, material), "PP1_CURRENT_CLINICAL_RESERVATION_REPLAY_MISMATCH"); return prior;
            }
            String issue = materialBlockers.get(key);
            var expected = materials.get(key);
            if (expected == null || !expected.sha256().equals(material.sha256())) issue = "PP1_CURRENT_CLINICAL_MATERIAL_SCOPE_MISMATCH";
            var ledger = actualAttempt.at("/binding/ledger");
            if (!fresh(ledger)) issue = "PP1_CURRENT_CLINICAL_EXISTING_OCCUPANCY_UNRESOLVED";
            if (collisions.contains(key)) issue = "PP1_CURRENT_CLINICAL_FACT_ALLOCATION_CONFLICT";
            NativeCall call = calls.get(actualAttempt.path("candidateId").asText());
            var facts = call == null ? null : call.facts;
            if (facts == null) {
                issue = call == null ? "PP1_CURRENT_CLINICAL_NATIVE_CALL_UNAVAILABLE" : call.blocker;
                if (issue == null) issue = "PP1_CURRENT_CLINICAL_NATIVE_CALL_UNAVAILABLE";
            }
            Pp4ServerRegisteredInputs.SampleLink selectedLink = null;
            if (issue == null && facts != null) {
                var links = inputs.clinicalSampleLinks().stream().filter(link -> link.matches(material) && link.matchesNativeCall(facts)).toList();
                if (links.size() != 1) issue = "PP1_CURRENT_CLINICAL_ORIGINAL_SAMPLE_LINK_UNPROVEN";
                else selectedLink = links.get(0);
            }
            if (issue == null && facts != null) {
                var clinical = material.originals().at("/nativeFacts/case"); var record = facts.path("matchedRecord");
                if (!clinical.path("assembly").asText().equals(facts.path("assembly").asText())
                        || !clinical.path("contig").asText().equals(record.path("contig").asText().replaceFirst("^chr", ""))
                        || !clinical.path("position").asText().equals(record.path("position").asText())
                        || !clinical.path("reference").equals(record.path("reference"))
                        || !clinical.path("alternate").equals(record.at("/alternates/0"))) issue = "PP1_CURRENT_CLINICAL_ORIGINAL_ALLELE_MISMATCH";
                for (var pair : Map.of("genotype", "genotype", "depth", "depth", "genotype_quality", "genotypeQuality", "allele_depth", "alleleDepth",
                        "record_filter", "recordFilter", "sample_filter", "sampleFilter").entrySet())
                    if (!clinical.path(pair.getKey()).asText().equals(facts.path(pair.getValue()).asText())) issue = "PP1_CURRENT_CLINICAL_ORIGINAL_CALL_MISMATCH";
            }
            ObjectNode closure = closure(actualAttempt);
            if (issue == null && !"COMPLETE".equals(closure.path("status").asText())) issue = closure.path("reasonCode").asText();
            var grant = new Grant(this, actualAttempt, material, call, selectedLink, closure, issue);
            grants.put(key, grant); return grant;
        }
        public synchronized Adoption finish(Grant grant, Pp4CurrentAdoptionProducer.CoreExecution execution) {
            if (!(grant != null && grant.owner == this && grant.available && execution != null && execution.matches(grant))) {
                throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_CORE_CAPABILITY_REQUIRED");
            }
            if (finished.containsKey(grant)) {
                var prior = finished.get(grant); require(prior.execution == execution, "PP1_CURRENT_CLINICAL_CORE_ALREADY_FINISHED"); return prior;
            }
            AcmgRunControl.checkpoint();
            liveClaim();
            ObjectNode core = execution.sourceResult(), evaluation = execution.evaluation();
            require(canonical(core).equals(execution.sourceResultSha256()) && canonical(evaluation).equals(execution.evaluationSha256())
                    && "CORE_RESULT_ONLY".equals(core.path("resultMode").asText()) && core.path("adoptionSupported").isBoolean()
                    && !core.path("adoptionSupported").asBoolean(true) && "ACMG_AMP_2015:PP4".equals(core.path("criterionId").asText())
                    && grant.material.snapshot().path("context").equals(core.at("/sourceBundle/context"))
                    && (core.path("evaluation").equals(evaluation) || core.at("/criterionEvaluations/0").equals(evaluation)),
                    "PP1_CURRENT_CLINICAL_CORE_RESULT_MISMATCH");
            for (String field : List.of("usedEvidence", "usedEvidenceStatus", "clinicalUse", "clinicalUseStatus"))
                require(grant.allocation.path(field).equals(core.at("/sourceBundle/observations/0/locusAttribution").path(field)),
                        "PP1_CURRENT_CLINICAL_CORE_ALLOCATION_MISMATCH");
            String status = evaluation.at("/evaluationResult/status").asText(); String kind = "UNAVAILABLE", reason = "PP1_CURRENT_CLINICAL_CORE_NOT_COMPLETED", points = null;
            boolean complete = false; ObjectNode refusalBasis = null;
            if ("MET".equals(status) && "COMPLETED".equals(evaluation.at("/deterministicTrace/executionStatus").asText())
                    && "APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText())) {
                JsonNode amount = core.path("appliedPoints");
                require(amount.isTextual() && amount.asText().length() <= 32 && amount.asText().matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"), "PP1_CURRENT_CLINICAL_CORE_POINTS_INVALID");
                var exact = new BigDecimal(amount.asText()); require(exact.signum() > 0 && exact.compareTo(BigDecimal.valueOf(5)) <= 0, "PP1_CURRENT_CLINICAL_CORE_POINTS_INVALID");
                complete = true; kind = grant.selected ? "ADOPTED" : "NOT_SELECTED"; points = grant.selected ? amount.asText() : "0";
                reason = grant.selected ? "PP1_CURRENT_CLINICAL_ACTUAL_PP4_AWARDED" : "PP1_CURRENT_CLINICAL_COMPLETED_PP4_NOT_SELECTED";
            }
            if ("NOT_EVALUATED".equals(status) && "PP4_PHENOTYPE_REQUIREMENT_NOT_MET".equals(evaluation.at("/evaluationResult/reasonCode").asText())
                    && "NOT_EVALUATED".equals(core.at("/evaluationResult/status").asText())
                    && "PP4_PHENOTYPE_REQUIREMENT_NOT_MET".equals(core.at("/evaluationResult/reasonCode").asText())) {
                refusalBasis = explicitNegativeBasis(grant, core);
                if (refusalBasis != null) {
                    complete = true; kind = grant.selected ? "NOT_ADOPTED" : "NOT_SELECTED"; points = "0";
                    reason = "PP1_CURRENT_CLINICAL_EXPLICIT_PHENOTYPE_REQUIREMENT_NOT_MET";
                }
            }
            var adoption = new Adoption(grant, execution, kind, complete, reason, points, refusalBasis); finished.put(grant, adoption); return adoption;
        }
        /** A finite, fully checked opposite presence proves non-adoption of this PP4
         * requirement. It does not reinterpret other NE reasons or reclassify the variant.
         */
        private static ObjectNode explicitNegativeBasis(Grant grant, JsonNode core) {
            JsonNode facts = grant.material.originals().path("nativeFacts"), clinical = facts.path("case");
            JsonNode requirements = facts.path("requirements"), observations = facts.path("observations");
            JsonNode projected = core.at("/sourceBundle/observations/0/phenotypeReview");
            if (!requirements.isArray() || requirements.isEmpty() || !observations.isArray()
                    || observations.size() != requirements.size() || !projected.path("findings").isArray()
                    || projected.path("findings").size() != observations.size()
                    || !projected.at("/definition/requirements").isArray()
                    || projected.at("/definition/requirements").size() != requirements.size()) return null;
            var required = new HashMap<String, String>(); var observed = new HashMap<String, JsonNode>();
            for (var row : requirements) {
                String feature = row.path("feature_id").asText(), presence = row.path("presence").asText();
                if (feature.isBlank() || !Set.of("PRESENT", "ABSENT").contains(presence) || required.putIfAbsent(feature, presence) != null) return null;
            }
            for (var row : observations) {
                String feature = row.path("feature_id").asText(), presence = row.path("presence").asText(), method = row.path("examination_method").asText();
                if (!required.containsKey(feature) || !Set.of("PRESENT", "ABSENT").contains(presence) || method.isBlank()
                        || Set.of("UNKNOWN", "PENDING", "NOT_PERFORMED").contains(method)
                        || !clinical.path("case_id").equals(row.path("case_id")) || !clinical.path("observation_id").equals(row.path("observation_id"))
                        || !sameInstant(clinical.path("observed_at"), row.path("observed_at")) || observed.putIfAbsent(feature, row) != null) return null;
            }
            if (!observed.keySet().equals(required.keySet())) return null;
            var definitionSeen = new HashSet<String>(); var findingSeen = new HashSet<String>(); boolean opposite = false;
            for (var row : projected.at("/definition/requirements")) {
                String feature = row.path("featureId").asText();
                if (!definitionSeen.add(feature) || !required.containsKey(feature) || !required.get(feature).equals(row.path("presence").asText())) return null;
            }
            for (var row : projected.path("findings")) {
                String feature = row.path("featureId").asText(); var nativeRow = observed.get(feature);
                if (!findingSeen.add(feature) || nativeRow == null || !nativeRow.path("presence").equals(row.path("presence"))
                        || !nativeRow.path("observed_at").equals(row.path("observedAt")) || !nativeRow.path("examination_method").equals(row.path("method"))
                        || !"PERFORMED".equals(row.path("examination").asText()) || !"APPLICABLE".equals(row.path("ageApplicable").asText())) return null;
                opposite |= !required.get(feature).equals(nativeRow.path("presence").asText());
            }
            if (!opposite || !definitionSeen.equals(required.keySet()) || !findingSeen.equals(required.keySet())) return null;
            return JSON.createObjectNode().put("schemaVersion", "current-native-pp4-known-nonadoption-v1")
                    .put("basis", "COMPLETE_CHECKED_NATIVE_ROSTER_EXPLICIT_OPPOSITE_PRESENCE")
                    .put("originalCoreStatus", "NOT_EVALUATED").put("originalCoreReasonCode", "PP4_PHENOTYPE_REQUIREMENT_NOT_MET")
                    .put("nativeFactsSha256", canonical(facts)).put("requirementsSha256", canonical(requirements)).put("observationsSha256", canonical(observations))
                    .put("actualCorePhenotypeReviewSha256", canonical(projected));
        }
        private void liveClaim() {
            var current = session.identity();
            var run = store.run(current.path("analysisId").asText(), current.path("runId").asText())
                    .orElseThrow(() -> new IllegalArgumentException("PP1_CURRENT_CLINICAL_CLAIM_REQUIRED"));
            require("RUNNING".equals(run.status()) && current.path("analysisId").asText().equals(run.analysisId())
                    && current.path("runId").asText().equals(run.id()) && current.path("requestSha256").asText().equals(run.requestSha256()),
                    "PP1_CURRENT_CLINICAL_CLAIM_NO_LONGER_RUNNING");
        }
        private ObjectNode closure(ObjectNode attempt) {
            JsonNode row = null; for (var entry : consumerAudit.path("attempts")) if (sameAttempt(attempt, entry)) row = entry;
            var result = JSON.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "PP1_CURRENT_CLINICAL_CONSUMER_TERMINAL_INCOMPLETE")
                    .put("nativeConsumerExecutionSha256", consumerPin);
            var observed = result.putArray("observedTerminals");
            JsonNode actualModule = row == null ? JSON.nullNode() : row.at("/moduleResults/deNovo");
            JsonNode actualCore = actualModule.path("qualifiedResult");
            if (actualModule.isObject()) result.put("actualModuleResultSha256", canonical(actualModule)); else result.putNull("actualModuleResultSha256");
            if (actualCore.isObject()) result.put("actualCoreResultSha256", canonical(actualCore)); else result.putNull("actualCoreResultSha256");
            for (String code : List.of("PS2", "PM6")) {
                JsonNode terminal = actualModule.path("criteria").path(code);
                var fact = observed.addObject().put("criterionId", "ACMG_AMP_2015:" + code)
                        .put("requiredStatus", "NOT_MET").put("requiredStrength", "NONE");
                diagnosticText(fact, "actualStatus", terminal.at("/evaluationResult/status"));
                diagnosticText(fact, "actualStrength", terminal.path("appliedStrength"));
                diagnosticText(fact, "actualReasonCode", terminal.at("/evaluationResult/reasonCode"));
                if (terminal.isObject()) fact.put("actualTerminalSha256", canonical(terminal)); else fact.putNull("actualTerminalSha256");
            }
            if (row == null || !attempt.at("/binding/identity/preparedContext").equals(row.at("/stage1Result/context"))) return result;
            // Other attempts can consume the same person's clinical facts even when their
            // own PP4 material is unavailable. Absence of a qualified PP4 row is not absence
            // of a current consumer. Unresolved cross-use is withheld rather than guessed disjoint.
            JsonNode patient = attempt.at("/binding/identity/preparedContext/patientContext");
            for (var other : consumerAudit.path("attempts")) {
                if (sameAttempt(attempt, other)) continue;
                // Optional stage-one output cannot establish that two native selections
                // are unrelated. Use the immutable session binding, including when the
                // other scientific context/consumer failed to materialize at stage one.
                JsonNode nativeAttempt = attempts.get(key(other));
                JsonNode otherContext = nativeAttempt == null ? JSON.nullNode() : nativeAttempt.at("/binding/identity/preparedContext");
                JsonNode otherPatient = otherContext.path("patientContext");
                boolean disjoint = knownId(patient.path("patientContextId")) && knownId(otherPatient.path("patientContextId"))
                        && !patient.path("patientContextId").equals(otherPatient.path("patientContextId"))
                        && knownId(patient.path("familyContextId")) && knownId(otherPatient.path("familyContextId"))
                        && !patient.path("familyContextId").equals(otherPatient.path("familyContextId"));
                if (disjoint) continue;
                if (nativeAttempt != null && "READY".equals(nativeAttempt.path("bindingStatus").asText())
                        && otherContext.isObject() && otherContext.equals(other.at("/stage1Result/context"))
                        && knownRelatedNonUse(otherContext, other, consumerAudit)) continue;
                boolean met = "MET".equals(other.at("/moduleResults/pm3/evaluationResult/status").asText())
                        || "MET".equals(other.at("/moduleResults/deNovo/criteria/PS2/evaluationResult/status").asText())
                        || "MET".equals(other.at("/moduleResults/deNovo/criteria/PM6/evaluationResult/status").asText());
                result.put("reasonCode", met ? "PP1_CURRENT_CLINICAL_RELATED_FACT_USE_UNRESOLVED"
                        : "PP1_CURRENT_CLINICAL_RELATED_CONSUMER_TERMINAL_INCOMPLETE");
                var blocker = result.putObject("relatedConsumerBlocker").put("candidateId", other.path("candidateId").asText())
                        .put("hypothesisId", other.path("hypothesisId").asText()).put("actualAttemptSha256", canonical(other));
                if (nativeAttempt != null && nativeAttempt.has("bindingIdentitySha256")) blocker.set("bindingIdentitySha256", nativeAttempt.path("bindingIdentitySha256").deepCopy());
                else blocker.putNull("bindingIdentitySha256");
                var observedRelated = blocker.putArray("observedTerminals");
                for (String code : List.of("PS2", "PM6", "PM3")) {
                    JsonNode terminal = code.equals("PM3") ? other.at("/moduleResults/pm3") : other.at("/moduleResults/deNovo/criteria/" + code);
                    var observedFact = observedRelated.addObject().put("criterionId", "ACMG_AMP_2015:" + code)
                            .put("requiredStatus", code.equals("PM3") ? "NOT_MET_OR_EXPLICIT_SCOPE_EXCLUSION" : "NOT_MET").put("requiredStrength", "NONE");
                    diagnosticText(observedFact, "actualStatus", terminal.at("/evaluationResult/status"));
                    diagnosticText(observedFact, "actualStrength", terminal.path("appliedStrength"));
                    diagnosticText(observedFact, "actualReasonCode", terminal.at("/evaluationResult/reasonCode"));
                }
                return result;
            }
            var route = row.at("/stage1Result/applicabilityPlan"); var context = attempt.at("/binding/identity/preparedContext");
            boolean pm3Excluded = "AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())
                    && "NOT_APPLICABLE".equals(route.at("/criteria/PM3/status").asText())
                    && "ROUTING_PM3_NON_RECESSIVE_CONTEXT".equals(route.at("/criteria/PM3/reasonCode").asText())
                    && route.at("/criteria/PM3/skipSpecializedAcquisition").asBoolean(false)
                    && context.path("contextId").equals(route.at("/criteria/PM3/contextId"))
                    && context.path("moi").equals(route.at("/criteria/PM3/moi"));
            if (!pm3Excluded) return result;
            result.put("pm3TerminalSha256", canonical(route.path("criteria").path("PM3")));
            JsonNode deNovo = row.at("/moduleResults/deNovo");
            if (!deNovo.isObject() || !consumerAudit.at("/registry/deNovo/configured").asBoolean(false)
                    || !(consumerAudit.at("/registry/deNovo/selected").asBoolean(false) || consumerAudit.at("/registry/deNovo/dependencyOnly").asBoolean(false))
                    || consumerAudit.at("/registry/deNovo/actualEvaluatorCalls").asLong(0) <= 0
                    || row.at("/moduleResults/deNovoMatchingResults").asInt(0) != 1) return result;
            for (String criterion : List.of("PS2", "PM6")) {
                JsonNode terminal = deNovo.path("criteria").path(criterion);
                if (!"NOT_MET".equals(terminal.at("/evaluationResult/status").asText())
                        || !"NONE".equals(terminal.path("appliedStrength").asText())) {
                    result.put("reasonCode", "MET".equals(terminal.at("/evaluationResult/status").asText())
                            ? "PP1_CURRENT_CLINICAL_RELATED_FACT_USE_UNRESOLVED" : "PP1_CURRENT_CLINICAL_CONSUMER_TERMINAL_INCOMPLETE"); return result;
                }
                result.put(criterion.toLowerCase(java.util.Locale.ROOT) + "TerminalSha256", canonical(terminal));
            }
            Set<String> configured = strings(consumerAudit.path("configuredCriteria"));
            for (String criterion : List.of("BS4", "PS4")) {
                if (configured.contains("ACMG_AMP_2015:" + criterion)
                        || !"NO_CURRENT_LOCAL_DISPATCH".equals(consumerAudit.path("registry").path("outsideCurrentRun").path(criterion).path("reasonCode").asText())) return result;
            }
            result.put("status", "COMPLETE").put("reasonCode", "PP1_CURRENT_CLINICAL_ACTUAL_CONSUMER_TERMINALS_COMPLETE"); return result;
        }
    }

    private static boolean knownId(JsonNode value) { return value.isTextual() && !value.textValue().isBlank(); }
    /** Every potentially shared current consumer needs a real terminal, not merely no MET. */
    private static boolean knownRelatedNonUse(JsonNode context, JsonNode row, JsonNode execution) {
        JsonNode route = row.at("/stage1Result/applicabilityPlan");
        boolean pm3Excluded = "AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())
                && "NOT_APPLICABLE".equals(route.at("/criteria/PM3/status").asText())
                && "ROUTING_PM3_NON_RECESSIVE_CONTEXT".equals(route.at("/criteria/PM3/reasonCode").asText())
                && route.at("/criteria/PM3/skipSpecializedAcquisition").asBoolean(false)
                && context.path("contextId").equals(route.at("/criteria/PM3/contextId")) && context.path("moi").equals(route.at("/criteria/PM3/moi"));
        JsonNode pm3 = row.at("/moduleResults/pm3");
        boolean pm3NotMet = row.at("/moduleResults/pm3MatchingResults").asInt(0) == 1 && pm3.isObject()
                && execution.at("/registry/pm3/configured").asBoolean(false) && execution.at("/registry/pm3/selected").asBoolean(false)
                && execution.at("/registry/pm3/actualEvaluatorCalls").asLong(0) > 0
                && "NOT_MET".equals(pm3.at("/evaluationResult/status").asText()) && "NONE".equals(pm3.path("appliedStrength").asText());
        if (!pm3Excluded && !pm3NotMet) return false;
        JsonNode deNovo = row.at("/moduleResults/deNovo");
        if (!deNovo.isObject() || row.at("/moduleResults/deNovoMatchingResults").asInt(0) != 1
                || !execution.at("/registry/deNovo/configured").asBoolean(false)
                || !(execution.at("/registry/deNovo/selected").asBoolean(false) || execution.at("/registry/deNovo/dependencyOnly").asBoolean(false))
                || execution.at("/registry/deNovo/actualEvaluatorCalls").asLong(0) <= 0) return false;
        for (String code : List.of("PS2", "PM6")) {
            JsonNode terminal = deNovo.path("criteria").path(code);
            if (!"NOT_MET".equals(terminal.at("/evaluationResult/status").asText()) || !"NONE".equals(terminal.path("appliedStrength").asText())) return false;
        }
        return true;
    }

    /** Reservation is permission to execute one exact qualified input; it carries no award. */
    public static final class Grant {
        private final CurrentUseSource owner;
        private final ObjectNode attempt;
        private final Pp4QualifiedMaterial material;
        private final boolean available;
        private final boolean selected;
        private final ObjectNode audit;
        private final ObjectNode allocation;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private Grant(CurrentUseSource owner, ObjectNode attempt, Pp4QualifiedMaterial material, NativeCall call,
                Pp4ServerRegisteredInputs.SampleLink link, ObjectNode closure, String issue) {
            this.owner = owner; this.attempt = attempt.deepCopy(); this.material = material; available = issue == null;
            selected = strings(owner.session.identity().path("enabledCriteria")).contains("ACMG_AMP_2015:PP4");
            audit = JSON.createObjectNode().put("schemaVersion", "current-native-clinical-grant-v1").put("status", available ? "RESERVED_NOT_AWARDED" : "UNAVAILABLE")
                    .put("reasonCode", available ? "PP1_CURRENT_CLINICAL_EXECUTION_RESERVED" : issue).put("awardedPointsKnown", false)
                    .putNull("awardedPoints").put("materialSha256", material.sha256()).put("nativeConsumerExecutionSha256", owner.consumerPin);
            audit.set("sessionIdentity", owner.session.identity()); audit.set("attemptIdentity", attempt.deepCopy().remove(List.of("binding")));
            audit.set("consumerClosure", closure.deepCopy());
            if (call != null && call.facts != null) audit.set("nativeCallProof", call.facts.deepCopy());
            if (link != null) audit.set("originalSampleLink", link.snapshot());
            var identity=owner.clinicalIdentities.get(key(attempt));
            if(identity!=null) {
                audit.set("nativeClinicalIdentity",identity.snapshot());
                if(link==null) audit.set("originalSampleLink",identity.sourceLink.snapshot());
            }
            audit.set("reservedEventIds", JSON.valueToTree(available ? material.proposedEventIds() : List.of()));
            audit.set("reservedFactIds", JSON.valueToTree(available ? material.observedFactIds() : List.of()));
            audit.putArray("allocatedEventIds"); audit.putArray("allocatedFactIds");
            audit.put("evidenceSelected", selected); allocation = available ? legacy(material, owner.consumerPin) : JSON.createObjectNode();
            audit.put("legacyAllocationSha256", canonical(allocation)); audit.put("grantSha256", canonical(audit));
        }
        public boolean matches(CurrentAcmgEvaluationSession session, ObjectNode candidate, Pp4QualifiedMaterial value) {
            return owner.matches(session) && candidate != null && attempt.equals(candidate) && value != null && material.sha256().equals(value.sha256())
                    && value.matchesContext((ObjectNode) attempt.at("/binding/identity/preparedContext"));
        }
        public boolean isAvailable() { return available; }
        public boolean evidenceSelected() { return selected; }
        public ObjectNode snapshot() { return audit.deepCopy(); }
        public ObjectNode legacyAllocation() { require(available, "PP1_CURRENT_CLINICAL_GRANT_UNAVAILABLE"); return allocation.deepCopy(); }
        private static ObjectNode legacy(Pp4QualifiedMaterial material, String consumerPin) {
            var data = material.originals().path("nativeFacts"); var c = material.currentCaseFacts(); var context = data.path("context");
            var group = JSON.createObjectNode().put("domain", "pp1-pp4-locus-family-v1").put("familyId", c.path("family_id").asText());
            group.set("hgncId", context.at("/gene/hgncId")); group.set("mondoId", context.at("/disease/mondoId")); group.set("alleleId", context.at("/variant/alleleId"));
            String groupId = "pp1-pp4-group-sha256-" + canonical(group), source = "pp4-native-locusAttribution-" + material.sha256();
            var result = JSON.createObjectNode().put("usedEvidenceStatus", "COMPLETE_FROZEN_ROSTER").put("clinicalUseStatus", "COMPLETE_FROZEN_ROSTER");
            String basis = "Actual complete native consumer closure " + consumerPin
                    + "; no awarded allocation for this case before this reserved PP4 execution";
            var used = result.putArray("usedEvidence");
            for (String code : List.of("PP1", "BS4", "PP4")) used.addObject().put("criterionId", "ACMG_AMP_2015:" + code)
                    .put("evidenceGroupId", groupId).put("status", "NOT_APPLIED_NO_CONFLICT").put("basis", basis).putArray("sourceIds").add(source);
            var clinical = result.putArray("clinicalUse");
            for (String code : List.of("PS2", "PM6", "PM3", "PS4")) {
                var entry = clinical.addObject().put("criterionId", "ACMG_AMP_2015:" + code).put("caseId", c.path("case_id").asText())
                        .put("personId", c.path("person_id").asText()).put("status", "NOT_APPLIED_NO_CONFLICT").put("basis", basis);
                entry.putArray("clinicalSourceIds").add("pp4-native-phenotypeReview-" + material.sha256()); entry.putArray("sourceIds").add(source);
            }
            return result;
        }
    }
    /** Selected-proband identity correspondence only. Original family IDs are retained
     * and related to the current case grouping; no biological relationship or parentage
     * is inferred. Clinical source qualification and current use remain separate checks. */
    public static final class NativeClinicalIdentity {
        private final CurrentUseSource owner;private final ObjectNode attempt,context,audit;
        private final Pp4ServerRegisteredInputs.SampleLink sourceLink;
        @JsonCreator(mode=JsonCreator.Mode.DISABLED)
        private NativeClinicalIdentity(CurrentUseSource owner,ObjectNode attempt,Pp4ServerRegisteredInputs.SampleLink link,NativeCall call) {
            require(call.facts!=null,"PP1_CURRENT_CLINICAL_NATIVE_IDENTITY_CALL_REQUIRED");
            this.owner=owner;this.attempt=attempt.deepCopy();this.sourceLink=link;
            context=((ObjectNode)attempt.at("/binding/identity/preparedContext")).deepCopy();
            audit=JSON.createObjectNode().put("schemaVersion","current-native-clinical-identity-v1")
                    .put("scope","SELECTED_PROBAND_IDENTITY_ONLY_NO_CLINICAL_QUALIFICATION_OR_PARENTAGE")
                    .put("registrationSha256",owner.inputs.registration().sha256()).put("registeredInputsSha256",canonical(owner.inputs.identity()))
                    .put("currentSessionIdentitySha256",canonical(owner.session.identity())).put("sourceCaseId",link.caseId())
                    .put("sourcePersonId",link.personId()).put("sourceFamilyId",link.familyId()).put("sourceSampleId",link.clinicalSampleId())
                    .put("currentPatientContextId",context.at("/patientContext/patientContextId").asText())
                    .put("currentFamilyContextId",context.at("/patientContext/familyContextId").asText())
                    .put("sourceSampleLinkSha256",canonical(link.snapshot())).put("nativeCallProofSha256",canonical(call.facts))
                    .put("clinicalQualificationGranted",false).put("familyRelationshipProven",false);
            audit.set("sourceSampleLink",link.snapshot());audit.set("nativeCallProof",call.facts.deepCopy());
            audit.set("attemptIdentity",attempt.deepCopy().remove(List.of("binding")));audit.put("identitySha256",canonical(audit));
        }
        public ObjectNode snapshot() { return audit.deepCopy(); }
        public boolean matches(Pp4ProductionMethodRegistration registration,ObjectNode candidateContext,Map<String,String> originalCase) {
            return registration==owner.inputs.registration() && owner.clinicalIdentities.get(key(attempt))==this
                    && context.equals(candidateContext) && sourceLink.caseId().equals(originalCase.get("case_id"))
                    && sourceLink.personId().equals(originalCase.get("person_id")) && sourceLink.familyId().equals(originalCase.get("family_id"))
                    && sourceLink.clinicalSampleId().equals(originalCase.get("sample_id")) && sourceLink.analysisAssembly().equals(originalCase.get("assembly"));
        }
    }

    /** Actual current adoption, derived solely from an exact native Grant and actual core call. */
    public static final class Adoption {
        private final Grant grant;
        private final Pp4CurrentAdoptionProducer.CoreExecution execution;
        private final ObjectNode audit;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private Adoption(Grant grant, Pp4CurrentAdoptionProducer.CoreExecution execution, String kind, boolean complete, String reason, String points, ObjectNode refusalBasis) {
            this.grant = grant; this.execution = execution;
            audit = JSON.createObjectNode().put("schemaVersion", "current-native-clinical-adoption-v1").put("kind", kind).put("complete", complete)
                    .put("reasonCode", reason).put("grantSha256", grant.audit.path("grantSha256").asText())
                    .put("coreExecutionSha256", canonical(execution.snapshot())).put("nativeConsumerExecutionSha256", grant.owner.consumerPin);
            if (points == null) audit.putNull("awardedPoints"); else audit.put("awardedPoints", points);
            if (refusalBasis != null) audit.set("knownNoAdoptionBasis", refusalBasis.deepCopy());
            audit.set("allocatedEventIds", JSON.valueToTree("ADOPTED".equals(kind) ? grant.material.proposedEventIds() : List.of()));
            audit.set("allocatedFactIds", JSON.valueToTree("ADOPTED".equals(kind) ? grant.material.observedFactIds() : List.of()));
            audit.put("adoptionSha256", canonical(audit));
        }
        public ObjectNode snapshot() { return audit.deepCopy(); }
        public boolean isComplete() { return audit.path("complete").asBoolean(false); }
        public boolean isAdopted() { return "ADOPTED".equals(audit.path("kind").asText()); }
        public boolean matches(Grant candidate, Pp4CurrentAdoptionProducer.CoreExecution actual) { return candidate == grant && actual == execution && actual.matches(candidate); }
    }
    private static boolean fresh(JsonNode ledger) {
        return ledger.isObject() && "UNKNOWN".equals(ledger.path("status").asText()) && ledger.path("pp4Points").isNull()
                && !ledger.has("unmergedCurrentOccupancy") && ledger.path("eventIds").isArray() && ledger.path("eventIds").isEmpty()
                && ledger.path("exclusiveFactIds").isArray() && ledger.path("exclusiveFactIds").isEmpty();
    }
    private static String key(JsonNode attempt) { return canonical(JSON.createArrayNode().add(attempt.path("candidateId")).add(attempt.path("hypothesisId"))); }
    private static boolean sameAttempt(JsonNode left, JsonNode right) { return left.path("candidateId").equals(right.path("candidateId")) && left.path("hypothesisId").equals(right.path("hypothesisId")); }
    private static Set<String> strings(JsonNode values) {
        require(values.isArray(), "PP1_CURRENT_CLINICAL_NATIVE_ROSTER_REQUIRED"); var result = new HashSet<String>();
        for (var value : values) require(value.isTextual() && !value.asText().isBlank() && result.add(value.asText()), "PP1_CURRENT_CLINICAL_NATIVE_ROSTER_INVALID"); return Set.copyOf(result);
    }
    private record NativeCall(ObjectNode facts, String blocker) {
        private NativeCall { facts = facts == null ? null : facts.deepCopy(); }
        private static NativeCall blocked(String reason) { return new NativeCall(null, reason); }
    }
    private record FileStamp(long bytes, long modified, String key) {
        private static FileStamp read(Path path) throws IOException {
            var a = Files.readAttributes(path, BasicFileAttributes.class);
            require(a.isRegularFile(), "PP1_CURRENT_CLINICAL_VCF_FILE_UNSUPPORTED");
            return new FileStamp(a.size(), a.lastModifiedTime().to(TimeUnit.NANOSECONDS), Objects.toString(a.fileKey(), null));
        }
    }

    /** One bounded-memory scan for the whole selected run. The original native publication
     * is cross-checked; no public candidate JSON can substitute for its sample/record witness.
     * HTSJDK is the existing parser. FT remains tied to its exact original raw-stream witness.
     */
    private static Map<String, NativeCall> nativeCalls(ObjectMapper mapper, AcmgExecutionSources.Recovered recovered,
            JsonNode sourceDocument, List<VariantInterpretation> ranked, List<String> selected) throws IOException {
        if (!(recovered != null && recovered.execution() != null && recovered.conversion() != null
                && !recovered.conversion().isConversionApplied())) {
            throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_NATIVE_EXECUTION_REQUIRED");
        }
        var input = recovered.execution().getInputSource();
        if (!(input != null && input.inputAssembly() == GenomeAssembly.GRCh38 && input.analysisAssembly() == GenomeAssembly.GRCh38
                && input.unchangedDuringExecution() && input.fullVcfScans() == 1
                && hash(input.observedVcfSha256()) && input.observedVcfSha256().equals(input.uploadSha256()))) {
            throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_NATIVE_SOURCE_REQUIRED");
        }
        require(AcmgExecutionSources.VERSION.equals(sourceDocument.path("version").asText())
                && input.analysisId().equals(sourceDocument.path("analysisId").asText())
                && input.sampleId().equals(sourceDocument.path("sampleId").asText())
                && input.observedVcfSha256().equals(sourceDocument.path("observedVcfSha256").asText())
                && input.uploadSha256().equals(sourceDocument.path("uploadSha256").asText())
                && !sourceDocument.path("conversionApplied").asBoolean(true)
                && input.analysisVcf().equals(Path.of(sourceDocument.path("analysisVcf").asText()).toAbsolutePath().normalize()),
                "PP1_CURRENT_CLINICAL_PUBLISHED_SOURCE_MISMATCH");
        var result = new LinkedHashMap<String, NativeCall>();
        var byOrdinal = new HashMap<Integer, List<VariantInterpretation>>();
        var records = new HashMap<String, EngineExecutionResult.MatchedRecord>();
        var sourceRows = new HashMap<String, JsonNode>();
        for (var row : sourceDocument.path("candidates")) require(sourceRows.putIfAbsent(row.path("candidateId").asText(), row) == null,
                "PP1_CURRENT_CLINICAL_PUBLISHED_CANDIDATE_AMBIGUOUS");
        if (!(selected != null && !selected.isEmpty() && selected.size() <= 100 && new HashSet<>(selected).size() == selected.size())) {
            throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_SELECTED_COVERAGE_REQUIRED");
        }
        for (String id : selected) {
            try {
                require(recovered.candidateBlockers() != null && !recovered.candidateBlockers().containsKey(id), "PP1_CURRENT_CLINICAL_CANDIDATE_SOURCE_BLOCKED");
                var matches = ranked.stream().filter(c -> c != null && id.equals(c.getCandidateId())).toList();
                require(matches.size() == 1, "PP1_CURRENT_CLINICAL_RANKED_CANDIDATE_AMBIGUOUS");
                var candidate = matches.get(0); var row = sourceRows.get(id);
                if (!(row != null && !row.has("sourceBlocker") && jsonEqual(row.path("published"), mapper.valueToTree(candidate)))) {
                    throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_PUBLISHED_CANDIDATE_MISMATCH");
                }
                require(candidate.getObservation() != null && candidate.getObservation().getKey() != null,
                        "PP1_CURRENT_CLINICAL_CANDIDATE_ALLELE_REQUIRED");
                var record = input.matchedRecord(candidate.getObservation().getKey()); var call = candidate.getAnalysisVcfCall();
                if (!(record != null && call != null && mapper.valueToTree(record).equals(row.path("matchedRecord"))
                        && jsonEqual(mapper.valueToTree(call), row.at("/engineCandidate/analysisVcfCall")))) {
                    throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_ORIGINAL_CALL_MISMATCH");
                }
                require(record.recordOrdinal() > 0 && record.selectedAltIndex() == 1 && record.alternates().size() == 1
                        && Objects.equals(call.getMatchedRecordOrdinal(), record.recordOrdinal())
                        && Objects.equals(call.getTargetAlleleIndex(), record.selectedAltIndex())
                        && call.getMatchStatus() == AnalysisVcfCall.MatchStatus.MATCHED
                        && call.getTargetAlleleCall() == AnalysisVcfCall.TargetAlleleCall.HET_TARGET
                        && "GRCh38".equals(call.getAssembly()) && call.getInvalidFields().isEmpty()
                        && call.getMissingFields().stream().allMatch(field -> "PS".equals(field)
                            && call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.RECORD_ONLY),
                        "PP1_CURRENT_CLINICAL_NATIVE_CALL_UNSUPPORTED");
                var key = candidate.getObservation().getKey();
                require("GRCh38".equals(key.getGenomeAssembly()) && record.contig().replaceFirst("^chr", "").equals(key.getChromosome())
                        && record.position() == key.getPosition() && record.reference().equals(key.getReference())
                        && record.alternates().get(0).equals(key.getAlternate()) && key.getReference().matches("[ACGT]")
                        && key.getAlternate().matches("[ACGT]"), "PP1_CURRENT_CLINICAL_NATIVE_ALLELE_MISMATCH");
                var ft = input.sampleFilter(key);
                require(ft != null && input.sampleId().equals(ft.sampleId()) && "GRCh38".equals(ft.assembly())
                        && record.equals(ft.record()) && ft.state() == PublishedSampleFilter.State.PASS && "PASS".equals(ft.rawValue())
                        && mapper.valueToTree(ft).equals(row.path("sampleFilterWitness")), "PP1_CURRENT_CLINICAL_NATIVE_SAMPLE_FT_UNPROVEN");
                records.put(id, record); byOrdinal.computeIfAbsent(record.recordOrdinal(), ignored -> new ArrayList<>()).add(candidate);
                result.put(id, NativeCall.blocked("PP1_CURRENT_CLINICAL_NATIVE_RECORD_NOT_FOUND"));
            } catch (IllegalArgumentException invalid) { result.put(id, NativeCall.blocked(reason(invalid, "PP1_CURRENT_CLINICAL_NATIVE_CALL_UNAVAILABLE"))); }
        }
        AcmgRunControl.checkpoint();
        Path path = input.analysisVcf(); FileStamp before = FileStamp.read(path);
        require(before.bytes == input.observedVcfByteLength() && before.modified == input.observedVcfMtimeNs()
                && before.bytes == sourceDocument.path("byteLength").asLong(-1)
                && before.modified == sourceDocument.path("mtimeNs").asLong(-1)
                && Objects.equals(before.key, sourceDocument.path("fileKey").isNull() ? null : sourceDocument.path("fileKey").asText()),
                "PP1_CURRENT_CLINICAL_VCF_STAMP_MISMATCH");
        MessageDigest digest = sha256();
        try (var raw = new DigestInputStream(Files.newInputStream(path), digest); var reader = new VCFIteratorBuilder().open(raw)) {
            var samples = reader.getHeader().getGenotypeSamples();
            require(new HashSet<>(samples).size() == samples.size() && samples.contains(input.sampleId()), "PP1_CURRENT_CLINICAL_NATIVE_SAMPLE_COLUMN_UNPROVEN");
            int sampleOrdinal = samples.indexOf(input.sampleId()) + 1; int ordinal = 0;
            while (reader.hasNext()) {
                if ((ordinal & 255) == 0) AcmgRunControl.checkpoint();
                VariantContext row = reader.next(); ordinal++;
                for (var candidate : byOrdinal.getOrDefault(ordinal, List.of())) {
                    String id = candidate.getCandidateId();
                    try { result.put(id, verifyCall(input, candidate, records.get(id), row, sampleOrdinal)); }
                    catch (IllegalArgumentException invalid) { result.put(id, NativeCall.blocked(reason(invalid, "PP1_CURRENT_CLINICAL_NATIVE_CALL_UNAVAILABLE"))); }
                }
            }
            byte[] tail = new byte[65536]; int blocks = 0;
            while (raw.read(tail) >= 0) if ((blocks++ & 63) == 0) AcmgRunControl.checkpoint();
        } catch (AcmgRunControl.Stopped stopped) { throw stopped; }
        catch (RuntimeException invalid) { throw new IOException("PP1_CURRENT_CLINICAL_VCF_REPLAY_FAILED", invalid); }
        AcmgRunControl.checkpoint();
        require(input.observedVcfSha256().equals(HexFormat.of().formatHex(digest.digest())) && before.equals(FileStamp.read(path)),
                "PP1_CURRENT_CLINICAL_VCF_BYTES_CHANGED");
        return Map.copyOf(result);
    }

    private static NativeCall verifyCall(EngineExecutionResult.InputSource input, VariantInterpretation candidate,
            EngineExecutionResult.MatchedRecord original, VariantContext row, int sampleOrdinal) {
        var call = candidate.getAnalysisVcfCall();
        require(original.contig().equals(row.getContig()) && original.position() == row.getStart()
                && original.reference().equals(row.getReference().getDisplayString()) && row.getAlternateAlleles().size() == 1
                && original.alternates().get(0).equals(row.getAlternateAllele(0).getDisplayString()), "PP1_CURRENT_CLINICAL_REPLAY_RECORD_MISMATCH");
        var sample = row.getGenotype(input.sampleId());
        if (!(sample != null && sample.getPloidy() == 2 && sample.isHet() && !sample.isNoCall()
                && sample.getAlleles().stream().allMatch(a -> a.equals(row.getReference()) || a.equals(row.getAlternateAllele(0))))) {
            throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_REPLAY_GT_UNSUPPORTED");
        }
        String delimiter = sample.isPhased() ? "|" : "/";
        String relative = String.join(delimiter, sample.getAlleles().stream().map(a -> a.isReference() ? "REF" : "TARGET_ALT").toList());
        String gt = String.join(delimiter, sample.getAlleles().stream().map(a -> a.isReference() ? "0" : "1").toList());
        require(relative.equals(call.getCandidateRelativeGenotype()) && (sample.isPhased()
                ? Set.of(AnalysisVcfCall.PhasingStatus.PHASE_SET, AnalysisVcfCall.PhasingStatus.RECORD_ONLY).contains(call.getPhasingStatus())
                : call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.UNPHASED), "PP1_CURRENT_CLINICAL_REPLAY_GT_MISMATCH");
        require(sample.hasDP() && sample.hasGQ() && sample.hasAD() && sample.getAD().length == 2
                && sample.getDP() >= 0 && sample.getGQ() >= 0 && Arrays.stream(sample.getAD()).allMatch(n -> n >= 0)
                && Objects.equals(sample.getDP(), call.getDepth()) && Objects.equals(sample.getGQ(), call.getGenotypeQuality())
                && Objects.equals(sample.getAD()[0], call.getReferenceDepth()) && Objects.equals(sample.getAD()[1], call.getTargetAlleleDepth()),
                "PP1_CURRENT_CLINICAL_REPLAY_QUALITY_MISMATCH");
        require(row.filtersWereApplied() && !row.isFiltered() && call.getFilterEvaluation() == AnalysisVcfCall.FilterEvaluation.PASS,
                "PP1_CURRENT_CLINICAL_REPLAY_RECORD_FILTER_MISMATCH");
        var proof = JSON.createObjectNode().put("schemaVersion", "current-native-clinical-call-proof-v1")
                .put("analysisId", input.analysisId()).put("vcfRawSha256", input.observedVcfSha256()).put("vcfSampleId", input.sampleId())
                .put("analysisVcfUriSha256", HexFormat.of().formatHex(sha256().digest(input.analysisVcf().toAbsolutePath().normalize().toUri().toASCIIString().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .put("sampleOrdinal", sampleOrdinal).put("recordOrdinal", original.recordOrdinal()).put("targetAlleleIndex", original.selectedAltIndex())
                .put("assembly", "GRCh38").put("ploidy", 2).put("genotype", gt).put("depth", sample.getDP()).put("genotypeQuality", sample.getGQ())
                .put("alleleDepth", sample.getAD()[0] + "," + sample.getAD()[1]).put("recordFilter", "PASS").put("sampleFilter", "PASS");
        proof.set("matchedRecord", JSON.valueToTree(original));
        // Preserve the complete actual privacy projection as serialized bytes. Its Double
        // alleleBalance is display-only; the authoritative ratio is the original AD integers.
        try {
            byte[] serialized = JSON.writeValueAsBytes(call);
            proof.putObject("analysisVcfCallRawAudit").put("serialization", "ACTUAL_JACKSON_CALL_JSON_UTF8")
                    .put("rawUtf8", new String(serialized, java.nio.charset.StandardCharsets.UTF_8))
                    .put("rawSha256", HexFormat.of().formatHex(sha256().digest(serialized)));
        } catch (IOException unavailable) { throw new IllegalArgumentException("PP1_CURRENT_CLINICAL_CALL_AUDIT_UNAVAILABLE", unavailable); }
        var balance = proof.putObject("alleleBalanceAudit").put("semantics", "DISPLAY_ONLY_DOUBLE_NO_MEDICAL_THRESHOLD_OR_EXACTNESS_CLAIM")
                .put("numeratorFromNativeAD", sample.getAD()[1]).put("denominatorFromNativeAD", (long) sample.getAD()[0] + sample.getAD()[1]);
        if (call.getAlleleBalance() == null) balance.putNull("displayValue"); else balance.put("displayValue", Double.toString(call.getAlleleBalance()));
        proof.set("sampleFilterWitness", JSON.valueToTree(input.sampleFilter(candidate.getObservation().getKey())));
        proof.put("proofSha256", canonical(proof)); return new NativeCall(proof, "");
    }
    private static void verifyPublishedSourceAssociation(AcmgExecutionSources.Recovered recovered, JsonNode document) {
        var input = recovered.execution().getInputSource();
        require((AcmgExecutionSources.VERSION.equals(document.path("version").asText())
                    || AcmgExecutionSources.CONVERTED_VERSION.equals(document.path("version").asText()))
                && input.sampleId().equals(document.path("sampleId").asText())
                && input.observedVcfSha256().equals(document.path("observedVcfSha256").asText())
                && input.uploadSha256().equals(document.path("uploadSha256").asText())
                && input.inputAssembly().name().equals(document.path("inputAssembly").asText())
                && input.analysisAssembly().name().equals(document.path("analysisAssembly").asText())
                && document.path("conversionApplied").isBoolean() && recovered.conversion() != null
                && recovered.conversion().isConversionApplied() == document.path("conversionApplied").asBoolean()
                && input.analysisVcf().equals(Path.of(document.path("analysisVcf").asText()).toAbsolutePath().normalize()),
                "PP1_CURRENT_CLINICAL_PUBLISHED_SOURCE_MISMATCH");
    }
    private static Map<String, NativeCall> blockedCalls(List<String> selected, String reason) {
        var unavailable = new LinkedHashMap<String, NativeCall>();
        for (String id : selected) unavailable.put(id, NativeCall.blocked(reason)); return Map.copyOf(unavailable);
    }
    private static String nativeReason(Exception failure, String fallback) {
        String value = failure.getMessage(); return value != null && value.matches("PP1_[A-Z0-9_]{1,150}") ? value : fallback;
    }
    private static void diagnosticText(ObjectNode target, String field, JsonNode value) {
        if (value.isTextual() && value.textValue().length() <= 512 && value.textValue().codePoints().noneMatch(Character::isISOControl)) target.put(field, value.textValue());
        else {
            target.putNull(field);
            if (!value.isMissingNode() && !value.isNull()) target.put(field + "OriginalSha256", canonical(value));
        }
    }
    private static boolean sameInstant(JsonNode left, JsonNode right) {
        try { return left.isTextual() && right.isTextual() && java.time.Instant.parse(left.textValue()).equals(java.time.Instant.parse(right.textValue())); }
        catch (java.time.format.DateTimeParseException malformed) { return false; }
    }
    private static boolean jsonEqual(JsonNode left, JsonNode right) {
        return left.equals((a, b) -> a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1, right);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String canonical(JsonNode value) { return new RuntimeEvidenceV2Adapter(JSON).canonicalSha256(value); }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static String reason(RuntimeException failure, String fallback) {
        String value = failure.getMessage(); return value != null && value.matches("PP1_[A-Z0-9_]{1,150}") ? value : fallback;
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
