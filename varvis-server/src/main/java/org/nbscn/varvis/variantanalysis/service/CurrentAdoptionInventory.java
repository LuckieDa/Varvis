package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.provider.Pp4CurrentAdoptionProducer;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.springframework.lang.Nullable;

/** Current native producer outcomes, never restored from an audit document or caller results.
 * Completeness is per selected attempt; unavailable attempts do not erase independent terminal receipts.
 */
public final class CurrentAdoptionInventory {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TERMINAL = Set.of("ADOPTED", "NOT_ADOPTED", "NOT_APPLICABLE", "NOT_SELECTED");
    private final ObjectNode sessionIdentity;
    private final ObjectNode snapshot;
    private final ObjectNode producerIdentity;
    @Nullable private final Pp4CurrentAdoptionProducer nativeProducer;
    private final boolean complete;
    private final Map<String, ObjectNode> materialOriginals;
    private final Map<String, byte[]> coreArtifacts;

    private CurrentAdoptionInventory(CurrentAcmgEvaluationSession session) {
        nativeProducer = null; complete = false; sessionIdentity = session.identity(); materialOriginals = Map.of(); coreArtifacts = Map.of();
        producerIdentity = sessionIdentity.objectNode().put("schemaVersion", "current-adoption-inventory-producer-v1")
                .put("producerId", "SERVICE_CURRENT_ADOPTION_INVENTORY")
                .put("origin", "SERVICE_OWNED").put("scope", "CURRENT_EVALUATION_ONLY")
                .put("registrationStatus", "UNAVAILABLE");
        snapshot = sessionIdentity.objectNode().put("schemaVersion", "current-adoption-inventory-v1")
                .put("status", "UNAVAILABLE").put("complete", false)
                .put("reasonCode", "CURRENT_ADOPTION_FORMAL_PRODUCER_NOT_REGISTERED");
        snapshot.set("sessionIdentity", sessionIdentity.deepCopy()); snapshot.set("producerIdentity", producerIdentity.deepCopy());
        snapshot.putArray("materialOriginalSha256s");
        var attempts = snapshot.putArray("attempts");
        for (var binding : session.attempts()) {
            var attempt = selectedAttempt(binding).put("status", "UNAVAILABLE")
                    .put("reasonCode", "CURRENT_ADOPTION_FORMAL_PRODUCER_NOT_REGISTERED");
            attempt.putNull("pp4Points"); attempts.add(attempt);
        }
    }

    private CurrentAdoptionInventory(CurrentAcmgEvaluationSession session, Pp4CurrentAdoptionProducer producer,
            ObjectNode producerIdentity, ObjectNode captured, boolean complete, Map<String, ObjectNode> originals, Map<String, byte[]> cores) {
        sessionIdentity = session.identity(); nativeProducer = producer;
        this.producerIdentity = producerIdentity.deepCopy(); snapshot = captured.deepCopy(); this.complete = complete;
        materialOriginals = immutableOriginals(originals); coreArtifacts = immutableCoreArtifacts(cores);
    }

    /** The only production issuer: executes the actual registered producer for every native attempt.
     * There is intentionally no JSON, List<result>, completeness flag or outcome callback factory.
     */
    public static CurrentAdoptionInventory capture(CurrentAcmgEvaluationSession session, Pp4CurrentAdoptionProducer producer) {
        Objects.requireNonNull(session); Objects.requireNonNull(producer);
        ObjectNode producerIdentity = producer.identity().deepCopy(); String producerPin = digest(producerIdentity);
        var value = JSON.createObjectNode().put("schemaVersion", "current-adoption-inventory-v1").put("phase", "BEFORE_PP1_EXECUTION");
        value.set("sessionIdentity", session.identity()); value.put("sessionIdentitySha256", digest(session.identity()));
        value.set("producerIdentity", producerIdentity.deepCopy()); value.put("producerIdentitySha256", producerPin);
        value.putArray("registeredProducerClosure").addObject().put("criterionId", "ACMG_AMP_2015:PP4")
                .put("producerIdentitySha256", producerPin).set("registrationSha256", producerIdentity.path("registrationSha256").deepCopy());
        var entries = value.putArray("attempts"); int completed = 0;
        var originals = new TreeMap<String, ObjectNode>();
        var cores = new TreeMap<String, byte[]>();
        for (ObjectNode attempt : session.attempts()) {
            ObjectNode entry = selectedAttempt(attempt); entry.put("complete", false).put("status", "UNAVAILABLE");
            entry.put("reasonCode", "PP1_CURRENT_CONTRIBUTION_PRODUCER_UNAVAILABLE").putNull("pp4Points");
            try {
                var outcome = producer.evaluate(session, attempt.deepCopy());
                if (!(outcome != null && outcome.matches(session, attempt))) {
                    throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_RECEIPT_SCOPE_MISMATCH");
                }
                ObjectNode receipt = outcome.snapshot().deepCopy();
                verifyReceipt(session, attempt, producerPin, receipt, outcome.isComplete());
                require(producerIdentity.path("registrationSha256").equals(receipt.path("registrationSha256")), "PP1_CURRENT_CONTRIBUTION_REGISTRATION_MISMATCH");
                String nativeReason = receipt.path("reasonCode").asText();
                if (!outcome.isComplete() && nativeReason.matches("PP1_[A-Z0-9_]{1,150}")) entry.put("reasonCode", nativeReason);
                if (receipt.path("materialDiagnostic").isObject()) entry.set("materialDiagnostic", receipt.path("materialDiagnostic").deepCopy());
                entry.set("terminalReceipt", receipt);
                entry.put("terminalReceiptSha256", receipt.path("receiptSha256").asText())
                        .put("terminalSnapshotSha256", digest(receipt)).put("terminalKind", receipt.path("kind").asText());
                var material = outcome.qualifiedMaterial();
                if (material.isPresent()) {
                    ObjectNode original = material.orElseThrow().originals();
                    String pin = receipt.path("materialOriginalsSha256").asText();
                    require(hash(pin) && pin.equals(digest(original))
                            && material.orElseThrow().sha256().equals(receipt.path("materialSha256").asText())
                            && original.path("registrationSha256").equals(receipt.path("registrationSha256")),
                            "PP1_CURRENT_CONTRIBUTION_MATERIAL_ORIGINALS_PIN_MISMATCH");
                    ObjectNode previous = originals.get(pin);
                    require(previous == null || previous.equals(original), "PP1_CURRENT_CONTRIBUTION_MATERIAL_ORIGINALS_CONFLICT");
                    if (previous == null) originals.put(pin, original.deepCopy());
                    entry.put("materialOriginalsSha256", pin);
                } else require(!receipt.hasNonNull("materialOriginalsSha256"), "PP1_CURRENT_CONTRIBUTION_NATIVE_MATERIAL_ORIGINALS_REQUIRED");
                var actualCore = outcome.coreExecution();
                if (actualCore.isPresent()) collectCore(actualCore.orElseThrow(), receipt, entry, cores);
                else require(!receipt.hasNonNull("coreExecutionSha256") && !receipt.has("coreExecution"), "PP1_CURRENT_CONTRIBUTION_ACTUAL_CORE_REQUIRED");
                if (outcome.isComplete()) {
                    require("READY".equals(attempt.path("bindingStatus").asText()), "PP1_CURRENT_CONTRIBUTION_READY_BINDING_REQUIRED");
                    verifyTerminal(receipt);
                    entry.put("complete", true).put("status", "COMPLETE")
                            .put("reasonCode", "PP1_CURRENT_CONTRIBUTION_VERIFIED_PRODUCER_TERMINAL");
                    entry.set("pp4Points", receipt.path("finalPoints").deepCopy()); completed++;
                }
            } catch (AcmgRunControl.Stopped stopped) {
                throw stopped;
            } catch (RuntimeException unavailable) {
                entry.put("complete", false).put("status", "UNAVAILABLE")
                        .put("reasonCode", "PP1_CURRENT_CONTRIBUTION_NATIVE_RECEIPT_UNAVAILABLE").putNull("pp4Points");
                String reason = unavailable.getMessage();
                if (reason != null && reason.matches("[A-Z][A-Z0-9_]{0,159}")) entry.put("producerFailureReason", reason);
            }
            entries.add(entry);
        }
        markAllocationConflicts(entries);
        completed = 0; for (JsonNode entry : entries) if (entry.path("complete").asBoolean(false)) completed++;
        require(producerIdentity.equals(producer.identity()), "PP1_CURRENT_CONTRIBUTION_PRODUCER_IDENTITY_CHANGED");
        boolean all = !session.attempts().isEmpty() && completed == session.attempts().size();
        value.put("status", all ? "COMPLETE" : completed > 0 ? "PARTIAL" : "UNAVAILABLE").put("complete", all)
                .put("reasonCode", all ? "PP1_CURRENT_CONTRIBUTION_INVENTORY_COMPLETE" : "PP1_CURRENT_CONTRIBUTION_INVENTORY_INCOMPLETE")
                .put("completedAttempts", completed).put("selectedAttempts", entries.size());
        var originalPins = value.putArray("materialOriginalSha256s"); originals.keySet().forEach(originalPins::add);
        var corePins = value.putArray("coreArtifactSha256s"); cores.keySet().forEach(corePins::add);
        value.put("inventorySha256", digest(value)); bounded(value);
        return new CurrentAdoptionInventory(session, producer, producerIdentity, value, all, originals, cores);
    }

    /** Legacy unavailable-only fixture; absence never establishes a zero contribution. */
    static CurrentAdoptionInventory unavailable(CurrentAcmgEvaluationSession session) {
        return new CurrentAdoptionInventory(Objects.requireNonNull(session));
    }
    public ObjectNode producerIdentity() { return producerIdentity.deepCopy(); }
    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    /** Exact native material originals for MAIN content-addressed archival, not JSON authority.
     * Every call returns isolated values and an unmodifiable map; snapshot only carries pins.
     */
    public Map<String, ObjectNode> materialOriginals() { return immutableOriginals(materialOriginals); }
    /** Exact captured input/result/evaluation bytes from native CoreExecution, never rebuilt. */
    public Map<String, byte[]> coreArtifacts() { return immutableCoreArtifacts(coreArtifacts); }
    public boolean isComplete() { return complete; }
    public boolean matches(CurrentAcmgEvaluationSession session) { return session != null && sessionIdentity.equals(session.identity()); }
    public boolean matches(CurrentAcmgEvaluationSession session, Pp4CurrentAdoptionProducer producer) {
        return producer != null && nativeProducer == producer && matches(session) && producerIdentity.equals(producer.identity());
    }

    private static void collectCore(Pp4CurrentAdoptionProducer.CoreExecution core, ObjectNode receipt,
            ObjectNode entry, Map<String, byte[]> destination) {
        ObjectNode snapshot = core.snapshot();
        require(snapshot.equals(receipt.path("coreExecution")) && digest(snapshot).equals(receipt.path("coreExecutionSha256").asText())
                && snapshot.path("materialSha256").equals(receipt.path("materialSha256"))
                && snapshot.path("grantSha256").equals(receipt.at("/currentUseGrant/grantSha256"))
                && snapshot.path("grantSnapshotSha256").asText().equals(digest(receipt.path("currentUseGrant"))), "PP1_CURRENT_CONTRIBUTION_CORE_SCOPE_MISMATCH");
        var bytes = core.artifactBytes(); var kinds = new HashSet<String>(); var pins = new TreeMap<String, byte[]>();
        JsonNode artifacts = core.artifactIndex().path("artifacts");
        require(artifacts.isArray() && artifacts.size() == 3, "PP1_CURRENT_CONTRIBUTION_CORE_ARTIFACTS_REQUIRED");
        for (JsonNode artifact : artifacts) {
            String kind = artifact.path("kind").asText(), pin = artifact.path("rawSha256").asText(); byte[] raw = bytes.get(pin);
            require(Set.of("INPUT", "SOURCE_RESULT", "EVALUATION").contains(kind) && kinds.add(kind) && raw != null
                    && pin.equals(rawSha(raw)) && raw.length == artifact.path("byteLength").asInt(), "PP1_CURRENT_CONTRIBUTION_CORE_RAW_PIN_MISMATCH");
            if (raw == null) throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_CORE_RAW_PIN_MISMATCH");
            try {
                JsonNode parsed = JSON.readTree(raw);
                require(parsed != null && digest(parsed).equals(artifact.path("canonicalSha256").asText()), "PP1_CURRENT_CONTRIBUTION_CORE_CANONICAL_PIN_MISMATCH");
                String expected = kind.equals("INPUT") ? snapshot.path("inputCanonicalSha256").asText()
                        : kind.equals("SOURCE_RESULT") ? core.sourceResultSha256() : core.evaluationSha256();
                require(expected.equals(artifact.path("canonicalSha256").asText()), "PP1_CURRENT_CONTRIBUTION_CORE_RESULT_PIN_MISMATCH");
            } catch (IOException invalid) { throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_CORE_JSON_INVALID", invalid); }
            byte[] previous = destination.get(pin);
            require(previous == null || java.util.Arrays.equals(previous, raw), "PP1_CURRENT_CONTRIBUTION_CORE_ARTIFACT_CONFLICT"); pins.put(pin, raw.clone());
        }
        pins.forEach(destination::putIfAbsent); var references = entry.putArray("coreArtifactSha256s"); pins.keySet().forEach(references::add);
    }
    private static Map<String, byte[]> immutableCoreArtifacts(Map<String, byte[]> originals) {
        var result = new TreeMap<String, byte[]>(); originals.forEach((pin, raw) -> result.put(pin, raw.clone())); return Collections.unmodifiableMap(result);
    }
    private static String rawSha(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static ObjectNode selectedAttempt(ObjectNode binding) {
        var out = JSON.createObjectNode().put("candidateId", binding.path("candidateId").asText())
                .put("hypothesisId", binding.path("hypothesisId").asText()).put("bindingStatus", binding.path("bindingStatus").asText());
        for (String key : new String[]{"bindingIdentitySha256", "binding"}) if (binding.has(key)) out.set(key, binding.path(key).deepCopy());
        if (binding.has("blockers")) out.set("selectionBlockers", binding.path("blockers").deepCopy());
        if (binding.has("reasonCode")) out.set("selectionReasonCode", binding.path("reasonCode").deepCopy());
        return out;
    }
    private static void verifyReceipt(CurrentAcmgEvaluationSession session, ObjectNode attempt, String producerPin,
            ObjectNode receipt, boolean complete) {
        var content = receipt.deepCopy(); content.remove("receiptSha256");
        require("pp4-current-adoption-outcome-v1".equals(receipt.path("schemaVersion").asText())
                && receipt.path("complete").isBoolean() && receipt.path("complete").asBoolean() == complete
                && session.identity().equals(receipt.path("sessionIdentity"))
                && producerPin.equals(receipt.path("producerIdentitySha256").asText())
                && digest(content).equals(receipt.path("receiptSha256").asText()), "PP1_CURRENT_CONTRIBUTION_RECEIPT_INVALID");
        JsonNode identity = receipt.path("attemptIdentity");
        require(attempt.path("candidateId").equals(identity.path("candidateId"))
                && attempt.path("hypothesisId").equals(identity.path("hypothesisId"))
                && attempt.path("bindingIdentitySha256").asText("").equals(identity.path("bindingIdentitySha256").asText(""))
                && (!"READY".equals(attempt.path("bindingStatus").asText()) || hash(attempt.path("bindingIdentitySha256").asText(""))
                    && hash(identity.path("bindingIdentitySha256").asText(""))),
                "PP1_CURRENT_CONTRIBUTION_RECEIPT_SCOPE_MISMATCH");
    }
    private static void verifyTerminal(ObjectNode receipt) {
        require(TERMINAL.contains(receipt.path("kind").asText()) && hash(receipt.path("registrationSha256").asText())
                && receipt.path("finalPoints").isTextual(), "PP1_CURRENT_CONTRIBUTION_TERMINAL_REQUIRED");
        String amount = receipt.path("finalPoints").asText();
        require(amount.length() <= 1024 && amount.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"), "PP1_CURRENT_CONTRIBUTION_POINTS_INVALID");
        BigDecimal points = new BigDecimal(amount);
        require(points.signum() >= 0 && points.compareTo(BigDecimal.valueOf(5)) <= 0 && Math.abs((long) points.scale()) <= 1024,
                "PP1_CURRENT_CONTRIBUTION_POINTS_INVALID");
        String kind = receipt.path("kind").asText();
        require(hash(receipt.path("materialSha256").asText()) && receipt.path("sourcePins").isArray() && !receipt.path("sourcePins").isEmpty(),
                "PP1_CURRENT_CONTRIBUTION_VERIFIED_MATERIAL_REQUIRED");
        if (Set.of("ADOPTED", "NOT_ADOPTED").contains(kind)) require(hash(receipt.path("sourceResultSha256").asText())
                && hash(receipt.path("evaluationSha256").asText()), "PP1_CURRENT_CONTRIBUTION_SCIENTIFIC_RESULT_REQUIRED");
        if (kind.equals("NOT_SELECTED")) require(receipt.path("sourceResultSha256").isNull() && receipt.path("evaluationSha256").isNull(),
                "PP1_CURRENT_CONTRIBUTION_NOT_SELECTED_RESULT_INVALID");
        boolean adopted = "ADOPTED".equals(kind);
        require(adopted ? points.signum() > 0 && hash(receipt.path("sourceResultSha256").asText()) : points.signum() == 0,
                "PP1_CURRENT_CONTRIBUTION_TERMINAL_RESULT_MISMATCH");
        for (String field : new String[]{"eventIds", "exclusiveFactIds"}) {
            var seen = new HashSet<String>(); JsonNode values = receipt.path(field);
            require(values.isArray() && values.size() <= 256, "PP1_CURRENT_CONTRIBUTION_FACTS_INVALID");
            for (JsonNode id : values) require(id.isTextual() && !id.asText().isBlank() && id.asText().length() <= 256
                    && id.asText().codePoints().noneMatch(Character::isISOControl) && seen.add(id.asText()), "PP1_CURRENT_CONTRIBUTION_FACTS_INVALID");
            require(adopted || values.isEmpty(), "PP1_CURRENT_CONTRIBUTION_UNADOPTED_FACT_ALLOCATION");
        }
    }
    /** All collision participants are withheld together; traversal order never selects a winner. */
    private static void markAllocationConflicts(JsonNode entries) {
        var allocations = new TreeMap<String, List<ObjectNode>>();
        for (JsonNode value : entries) if (value.path("complete").asBoolean(false) && "ADOPTED".equals(value.path("terminalKind").asText())) {
            ObjectNode entry = (ObjectNode) value;
            for (String field : new String[]{"eventIds", "exclusiveFactIds"}) for (JsonNode id : entry.at("/terminalReceipt/" + field))
                allocations.computeIfAbsent(field + ":" + id.asText(), ignored -> new ArrayList<>()).add(entry);
        }
        for (var allocation : allocations.entrySet()) if (allocation.getValue().size() > 1) {
            var participants = JSON.createArrayNode();
            allocation.getValue().forEach(entry -> participants.addObject().put("candidateId", entry.path("candidateId").asText())
                    .put("hypothesisId", entry.path("hypothesisId").asText()).put("bindingIdentitySha256", entry.path("bindingIdentitySha256").asText()));
            for (ObjectNode entry : allocation.getValue()) {
                entry.put("complete", false).put("status", "CONFLICT").putNull("pp4Points")
                        .put("reasonCode", "PP1_CURRENT_CONTRIBUTION_FACT_ALLOCATION_CONFLICT");
                entry.withArray("allocationConflicts").addObject().put("allocation", allocation.getKey()).set("participants", participants.deepCopy());
            }
        }
    }

    private static Map<String, ObjectNode> immutableOriginals(Map<String, ObjectNode> originals) {
        var copy = new TreeMap<String, ObjectNode>(); originals.forEach((pin, value) -> copy.put(pin, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }
    private static String digest(JsonNode value) { return new RuntimeEvidenceV2Adapter(JSON).canonicalSha256(value); }
    private static boolean hash(String value) { return value.matches("[a-f0-9]{64}"); }
    private static void bounded(ObjectNode value) {
        try { require(JSON.writeValueAsBytes(value).length <= 1048576, "PP1_CURRENT_CONTRIBUTION_INVENTORY_LIMIT"); }
        catch (IOException invalid) { throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_INVENTORY_INVALID"); }
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
