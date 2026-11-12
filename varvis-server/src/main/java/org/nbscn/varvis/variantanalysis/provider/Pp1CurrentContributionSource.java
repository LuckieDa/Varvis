package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.nbscn.varvis.variantanalysis.service.CurrentClinicalAllocation;
import java.util.Set;
import java.util.TreeSet;
import org.nbscn.varvis.variantanalysis.service.CurrentAcmgEvaluationSession;
import org.nbscn.varvis.variantanalysis.service.CurrentAdoptionInventory;

/** One current native inventory source. Audit JSON and recovered declarations never issue authority.
 * MAIN must bind this once before PP1 executes; this source neither reads nor merges history.
 */
public final class Pp1CurrentContributionSource implements Pp1ContributionSnapshot.Source {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CurrentAcmgEvaluationSession session;
    private final Pp4CurrentAdoptionProducer producer;
    private final ObjectNode producerIdentity;
    private final ObjectNode identity;
    private final ObjectNode frozen;

    /** Stable, pre-claim factory capability bound to the actual registered native producer. */
    public static final class Factory implements Pp1SelectedBatch.CurrentContributionFactory {
        private final Pp4CurrentAdoptionProducer producer;
        private final ObjectNode producerIdentity;
        private final ObjectNode identity;
        public Factory(Pp4CurrentAdoptionProducer producer) {
            this.producer = Objects.requireNonNull(producer);
            producerIdentity = producer.identity().deepCopy();
            require(hash(producerIdentity.path("registrationSha256").asText()), "PP1_CURRENT_CONTRIBUTION_NATIVE_REGISTRATION_REQUIRED");
            identity = JSON.createObjectNode().put("schemaVersion", "pp1-native-current-contribution-source-v1")
                    .put("sourceId", "NATIVE_PP4_CURRENT_ADOPTION_INVENTORY").put("version", "1.0.0")
                    .put("origin", "SERVICE_OWNED").put("scope", "CURRENT_EVALUATION_ONLY")
                    .put("phase", "BEFORE_PP1_EXECUTION").put("producerIdentitySha256", digest(producerIdentity));
            identity.set("producerIdentity", producerIdentity.deepCopy());
            var implementation = identity.putObject("implementationPins");
            for (Class<?> type : new Class<?>[]{Pp1CurrentContributionSource.class, Factory.class, CurrentAdoptionInventory.class, Pp1ContributionSnapshot.class})
                implementation.put(type.getName(), implementationPin(type));
        }
        public Pp4CurrentAdoptionProducer producer() { return producer; }
        public Optional<Pp4ServerRegisteredInputs> registeredInputs() { return producer.registeredInputs(); }
        public void bindCurrentUseSource(CurrentClinicalAllocation.CurrentUseSource source) {
            checkProducer(producer, producerIdentity); producer.bindCurrentUseSource(source); checkProducer(producer, producerIdentity);
        }
        @Override public ObjectNode identity() { checkProducer(producer, producerIdentity); return identity.deepCopy(); }
        @Override public Pp1CurrentContributionSource forCurrentSession(CurrentAcmgEvaluationSession session, CurrentAdoptionInventory inventory) {
            Objects.requireNonNull(session); Objects.requireNonNull(inventory);
            checkProducer(producer, producerIdentity);
            require(inventory.matches(session, producer) && producerIdentity.equals(inventory.producerIdentity()),
                    "PP1_CURRENT_CONTRIBUTION_NATIVE_INVENTORY_REQUIRED");
            return new Pp1CurrentContributionSource(session, inventory, producer, producerIdentity, identity);
        }
    }

    private Pp1CurrentContributionSource(CurrentAcmgEvaluationSession session, CurrentAdoptionInventory inventory,
            Pp4CurrentAdoptionProducer producer, ObjectNode producerIdentity, ObjectNode identity) {
        this.session = session; this.producer = producer;
        this.producerIdentity = producerIdentity.deepCopy(); this.identity = identity.deepCopy();
        ObjectNode inventoryView = inventory.snapshot();
        var content = inventoryView.deepCopy(); content.remove("inventorySha256");
        require(digest(content).equals(inventoryView.path("inventorySha256").asText())
                && inventoryView.path("sessionIdentity").equals(session.identity())
                && inventoryView.path("producerIdentity").equals(producerIdentity)
                && "BEFORE_PP1_EXECUTION".equals(inventoryView.path("phase").asText()), "PP1_CURRENT_CONTRIBUTION_INVENTORY_INVALID");
        String sessionPin = digest(session.identity()), inventoryPin = digest(inventoryView);
        frozen = JSON.createObjectNode().put("schemaVersion", Pp1ContributionSnapshot.SCHEMA)
                .put("analysisId", session.identity().path("analysisId").asText())
                .put("sourceIdentitySha256", digest(identity)).put("phase", "BEFORE_PP1_EXECUTION")
                .put("currentSessionIdentitySha256", sessionPin).put("currentInventorySha256", inventoryPin)
                .put("producerIdentitySha256", digest(producerIdentity));
        frozen.set("currentSessionIdentity", session.identity()); frozen.set("currentInventory", inventoryView.deepCopy());
        frozen.set("producerIdentity", producerIdentity.deepCopy());
        frozen.set("registeredProducerClosure", inventoryView.path("registeredProducerClosure").deepCopy());
        var entries = frozen.putArray("entries"); var attempts = session.attempts(); JsonNode captured = inventoryView.path("attempts");
        require(captured.isArray() && captured.size() == attempts.size(), "PP1_CURRENT_CONTRIBUTION_ATTEMPT_COVERAGE_REQUIRED");
        for (int index = 0; index < attempts.size(); index++) {
            ObjectNode attempt = attempts.get(index); JsonNode outcome = captured.get(index);
            require(attempt.path("candidateId").equals(outcome.path("candidateId"))
                    && attempt.path("hypothesisId").equals(outcome.path("hypothesisId"))
                    && attempt.path("bindingStatus").equals(outcome.path("bindingStatus"))
                    && attempt.path("bindingIdentitySha256").equals(outcome.path("bindingIdentitySha256"))
                    && attempt.path("binding").equals(outcome.path("binding")), "PP1_CURRENT_CONTRIBUTION_ATTEMPT_SCOPE_MISMATCH");
            var entry = entries.addObject().put("candidateId", attempt.path("candidateId").asText())
                    .put("hypothesisId", attempt.path("hypothesisId").asText()).put("bindingStatus", attempt.path("bindingStatus").asText());
            if (attempt.has("bindingIdentitySha256")) entry.set("bindingIdentitySha256", attempt.path("bindingIdentitySha256").deepCopy());
            // Full native facts and receipts occur once in currentInventory; entries retain
            // an exact indexed reference into that pinned immutable audit.
            var audit = entry.putObject("nativeAttemptAudit").put("currentInventorySha256", inventoryPin)
                    .put("attemptIndex", index).put("status", outcome.path("status").asText())
                    .put("reasonCode", availabilityReason(outcome));
            if (outcome.has("terminalReceiptSha256")) audit.set("terminalReceiptSha256", outcome.path("terminalReceiptSha256").deepCopy());
            else audit.putNull("terminalReceiptSha256");
            var coverage = entry.putObject("coverage").put("status", "UNAVAILABLE")
                    .put("reasonCode", availabilityReason(outcome));
            coverage.putArray("checkedResultSha256s"); entry.putArray("contributions");
            if (!"READY".equals(attempt.path("bindingStatus").asText())) continue;
            JsonNode binding = attempt.path("binding"), ledger = binding.path("ledger");
            entry.set("scope", ledger.path("scope").deepCopy()); entry.set("allele", ledger.path("allele").deepCopy());
            if (!freshLedger(ledger)) {
                coverage.put("reasonCode", "PP1_CURRENT_CONTRIBUTION_EXISTING_OCCUPANCY_UNRESOLVED");
                entry.set("existingCurrentOccupancy", ledger.deepCopy()); continue;
            }
            if (!outcome.path("complete").asBoolean(false) || !"COMPLETE".equals(outcome.path("status").asText())) continue;
            JsonNode receipt = outcome.path("terminalReceipt");
            String kind = receipt.path("kind").asText(), terminalPin = outcome.path("terminalReceiptSha256").asText();
            require(Set.of("ADOPTED", "NOT_ADOPTED", "NOT_APPLICABLE", "NOT_SELECTED").contains(kind)
                    && hash(terminalPin) && terminalPin.equals(receipt.path("receiptSha256").asText())
                    && digest(receipt).equals(outcome.path("terminalSnapshotSha256").asText()), "PP1_CURRENT_CONTRIBUTION_TERMINAL_REQUIRED");
            var provenance = JSON.createObjectNode().put("currentSessionIdentitySha256", sessionPin)
                    .put("currentInventorySha256", inventoryPin).put("bindingIdentitySha256", attempt.path("bindingIdentitySha256").asText())
                    .put("terminalReceiptSha256", terminalPin).put("terminalSnapshotSha256", digest(receipt));
            coverage.put("status", "COMPLETE").put("reasonCode", "PP1_CURRENT_CONTRIBUTION_VERIFIED_PRODUCER_TERMINAL")
                    .put("provenanceSha256", digest(provenance));
            var checked = new TreeSet<String>(); checked.add(terminalPin);
            var receiptKinds = coverage.putArray("checkedReceiptKinds");
            receiptKinds.addObject().put("sha256", terminalPin).put("kind", "ACTUAL_PRODUCER_TERMINAL_RECEIPT").put("terminalKind", kind);
            String sourceResult = receipt.path("sourceResultSha256").asText();
            if (hash(sourceResult)) {
                checked.add(sourceResult); receiptKinds.addObject().put("sha256", sourceResult).put("kind", "ACTUAL_CORE_SCIENTIFIC_RESULT");
            }
            var checkedPins = coverage.putArray("checkedResultSha256s"); checked.forEach(checkedPins::add);
            if (kind.equals("ADOPTED")) {
                require(hash(sourceResult), "PP1_CURRENT_CONTRIBUTION_SCIENTIFIC_RESULT_REQUIRED");
                Pp1ContributionSnapshot.pointValue(receipt.path("finalPoints"), "PP1_CURRENT_CONTRIBUTION_POINTS_INVALID");
                var contribution = entry.withArray("contributions").addObject().put("criterionId", "ACMG_AMP_2015:PP4")
                        .put("status", "ADOPTED").put("sourceResultSha256", sourceResult).put("sourceTerminalReceiptSha256", terminalPin);
                contribution.set("points", receipt.path("finalPoints").deepCopy());
                contribution.set("eventIds", receipt.path("eventIds").deepCopy());
                contribution.set("exclusiveFactIds", receipt.path("exclusiveFactIds").deepCopy());
                var contributionIdentity = provenance.deepCopy(); contributionIdentity.set("allocation", contribution.deepCopy());
                contribution.put("contributionId", digest(contributionIdentity));
            }
        }
        frozen.put("snapshotSha256", digest(frozen)); bounded(frozen);
        checkProducer(producer, producerIdentity);
    }

    /** This immutable native handle is checked by the current-session consumer, never restored from JSON. */
    public CurrentAcmgEvaluationSession currentSession() { return session; }
    @Override public ObjectNode identity() { checkProducer(producer, producerIdentity); return identity.deepCopy(); }
    @Override public ObjectNode capture(String analysisId, ObjectNode recovered) throws IOException {
        if (analysisId == null || !analysisId.equals(session.identity().path("analysisId").asText()))
            throw new IOException("PP1_CURRENT_CONTRIBUTION_ANALYSIS_MISMATCH");
        if (recovered != null && recovered.has("analysisId") && (!recovered.path("analysisId").isTextual()
                || !analysisId.equals(recovered.path("analysisId").asText()))) throw new IOException("PP1_CURRENT_CONTRIBUTION_ANALYSIS_MISMATCH");
        checkProducer(producer, producerIdentity);
        // No points, completeness, source or approval from recovered JSON is consulted.
        return frozen.deepCopy();
    }
    private static boolean freshLedger(JsonNode ledger) {
        return ledger.isObject() && "UNKNOWN".equals(ledger.path("status").asText()) && ledger.path("pp4Points").isNull() && !ledger.has("unmergedCurrentOccupancy")
                && ledger.path("eventIds").isArray() && ledger.path("eventIds").isEmpty()
                && ledger.path("exclusiveFactIds").isArray() && ledger.path("exclusiveFactIds").isEmpty();
    }
    private static String availabilityReason(JsonNode attempt) {
        String code = attempt.path("reasonCode").asText();
        return code.matches("PP1_[A-Z0-9_]{1,150}") ? code : "PP1_CURRENT_CONTRIBUTION_PRODUCER_UNAVAILABLE";
    }
    private static void checkProducer(Pp4CurrentAdoptionProducer producer, ObjectNode expected) {
        require(expected.equals(producer.identity()), "PP1_CURRENT_CONTRIBUTION_PRODUCER_IDENTITY_CHANGED");
    }
    private static String implementationPin(Class<?> type) {
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (stream == null) throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_IMPLEMENTATION_REQUIRED");
            byte[] bytes = stream.readNBytes(1048577);
            require(bytes.length > 0 && bytes.length <= 1048576, "PP1_CURRENT_CONTRIBUTION_IMPLEMENTATION_LIMIT");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException unavailable) {
            throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_IMPLEMENTATION_REQUIRED");
        }
    }
    private static String digest(JsonNode value) { return Pp1ContributionSnapshot.digest(JSON, value); }
    private static boolean hash(String value) { return value.matches("[a-f0-9]{64}"); }
    private static void bounded(ObjectNode value) {
        try { require(JSON.writeValueAsBytes(value).length <= 1048576, "PP1_CURRENT_CONTRIBUTION_SNAPSHOT_LIMIT"); }
        catch (IOException invalid) { throw new IllegalArgumentException("PP1_CURRENT_CONTRIBUTION_SNAPSHOT_INVALID"); }
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
