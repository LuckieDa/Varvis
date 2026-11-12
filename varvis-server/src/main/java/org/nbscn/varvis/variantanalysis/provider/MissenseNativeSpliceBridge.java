package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;

/** Request-owned RAW transport for reviewed missense comparisons. No calibration or approval. */
public final class MissenseNativeSpliceBridge {
    public static final String VERSION = "1.0.0";
    public static final String RETAINED_AUDIT_VERSION = "1.0.0";
    private static final int MAX_UNITS = 50, CHUNK_SIZE = 10;
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final CodingTranscriptMaterialSupply.ReferenceReader reference;
    private final BiFunction<ObjectNode, Duration, ObjectNode> transport;
    private final LongSupplier clock;
    private final Duration budget;
    private final ObjectNode identity;
    private final int distance;
    private final Map<String, ObjectNode> facts = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final List<ObjectNode> receipts = new ArrayList<>();
    private Long started;
    private String stopped;
    private int calls, hits;
    private final Map<String, RetainedFact> retained = new LinkedHashMap<>();
    private String retainedAuditSha;
    private int retainedHits;
    private record RetainedFact(ObjectNode unit, ObjectNode value, ObjectNode response) { }

    public MissenseNativeSpliceBridge(ObjectMapper mapper, CodingTranscriptMaterialSupply.ReferenceReader reference,
            NativeSpliceAiBatchRunner runner) {
        this(mapper, reference, runner.identity(), runner.timeout(), System::nanoTime, runner::run);
    }

    /** Explicit extra condition family; never changes the default 4,999 bp identity. */
    public MissenseNativeSpliceBridge(ObjectMapper mapper, CodingTranscriptMaterialSupply.ReferenceReader reference,
            NativeSpliceAiBatchRunner runner, int distance) {
        this(mapper, reference, runner.identity(), runner.timeout(), System::nanoTime, runner::run, distance);
    }

    MissenseNativeSpliceBridge(ObjectMapper mapper, CodingTranscriptMaterialSupply.ReferenceReader reference,
            ObjectNode runtime, Duration budget, LongSupplier clock, BiFunction<ObjectNode, Duration, ObjectNode> transport) {
        this(mapper, reference, runtime, budget, clock, transport, 4999);
    }

    MissenseNativeSpliceBridge(ObjectMapper mapper, CodingTranscriptMaterialSupply.ReferenceReader reference,
            ObjectNode runtime, Duration budget, LongSupplier clock, BiFunction<ObjectNode, Duration, ObjectNode> transport, int distance) {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper); this.reference = reference;
        this.transport = transport; this.clock = clock; this.budget = budget;
        require(distance == 4999 || distance == 10000 && "1.2.0".equals(runtime.path("version").asText())
                && runtime.path("executionDistanceBp").asInt()==10000, "NATIVE_SPLICE_DISTANCE_BINDING_INVALID");
        this.distance=distance;
        require(budget.toMillis() > 0 && budget.compareTo(Duration.ofSeconds(600)) <= 0, "NATIVE_TIMEOUT_INVALID");
        identity = mapper.createObjectNode().put("method", "MISSENSE_NATIVE_SELECTED_RAW")
                .put("version", distance==4999 ? VERSION : "1.1.0").put("scoreMode", "RAW").put("distance", distance).put("mask", 0)
                .put("runtimeIdentityJson", runtime.toString()).put("scientificApprovalGranted", false);
    }

    /** This new identity must be reviewed in materials; it is never the old WSL resource pin. */
    public String resourceSha256() { return adapter.canonicalSha256(identity); }
    public ObjectNode identity() { return identity.deepCopy(); }

    /** Small independent reference checks for the PS1 splice motif; no score or RNA outcome is inferred. */
    ObjectNode verifyGtAgMotif(ObjectNode context, JsonNode motif) throws java.io.IOException {
        int strand = motif.path("strand").asInt();
        require(strand == 1 || strand == -1, "PS1_SPLICE_TRANSCRIPT_INVALID");
        var proof = mapper.createObjectNode();
        for (String end : List.of("donor", "acceptor")) {
            int boundary = motif.path(end + "Boundary").asInt();
            int start = "donor".equals(end) ? boundary + (strand == 1 ? 1 : -2) : boundary + (strand == 1 ? -2 : 1);
            var span = reference.read(context.at("/variant/evidenceQueryAllele/contig").asText(), start, 2);
            if (!(span != null && span.bases() != null && span.bases().length() == 2 && span.identity() != null
                    && context.at("/variant/normalization/referenceSequenceSha256").asText().matches("[0-9a-f]{64}")
                    && span.identity().path("expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")))) {
                throw new IllegalArgumentException("PS1_SPLICE_REFERENCE_UNQUALIFIED");
            }
            String bases = span.bases().toUpperCase(java.util.Locale.ROOT);
            if (strand == -1) bases = TruncatingSequence.reverseComplement(bases);
            require(("donor".equals(end) ? "GT" : "AG").equals(bases), "PS1_SPLICE_NON_GT_AG_INTRON_UNSUPPORTED");
            proof.put(end, bases); proof.set("referenceIdentity", span.identity());
        }
        return proof;
    }

    /** Explicitly pinned past executions, not a replacement transport or a scientific approval. */
    public synchronized void loadRetainedAudit(java.nio.file.Path path, String expectedRawSha256) throws java.io.IOException {
        require(retainedAuditSha == null && calls == 0 && facts.isEmpty(), "NATIVE_RETAINED_AUDIT_ALREADY_STARTED");
        require(expectedRawSha256 != null && expectedRawSha256.matches("[0-9a-f]{64}")
                && java.nio.file.Files.size(path) <= 32_000_000, "NATIVE_RETAINED_AUDIT_PIN_INVALID");
        byte[] bytes;
        try (var stream = java.nio.file.Files.newInputStream(path)) { bytes = stream.readNBytes(32_000_001); }
        require(bytes.length <= 32_000_000 && CodingTranscriptMaterialSupply.sha(bytes).equals(expectedRawSha256), "NATIVE_RETAINED_AUDIT_HASH_MISMATCH");
        var strict = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var audit = strict.readTree(bytes);
        require(identity.equals(audit.path("identity")) && audit.path("nativeReceiptsJson").isTextual(), "NATIVE_RETAINED_AUDIT_IDENTITY_MISMATCH");
        var previous = strict.readTree(audit.path("nativeReceiptsJson").asText());
        require(previous.isArray() && !previous.isEmpty() && previous.size() <= 5, "NATIVE_RETAINED_AUDIT_RECEIPTS_INVALID");
        var pending = new LinkedHashMap<String, RetainedFact>();
        for (var item : previous) {
            require(item instanceof ObjectNode && "spliceai-native-business-result-v1".equals(item.path("resultSchemaVersion").asText())
                    && "SUCCEEDED".equals(item.path("status").asText())
                    && strict.readTree(identity.path("runtimeIdentityJson").asText()).equals(item.path("identity"))
                    && item.path("rawOutputSha256").asText().matches("[0-9a-f]{64}")
                    && item.path("annotationFileSha256").asText().matches("[0-9a-f]{64}"), "NATIVE_RETAINED_RESULT_INVALID");
            var receipt = item.path("processReceipt");
            require(receipt.path("ownedProcessesStopped").isBoolean() && receipt.path("ownedProcessesStopped").asBoolean()
                    && receipt.path("exitConfirmed").isBoolean() && receipt.path("exitConfirmed").asBoolean()
                    && receipt.path("rootExitCodeObserved").isIntegralNumber() && receipt.path("rootExitCodeObserved").asInt() == 0
                    && receipt.path("activeProcessCount").isIntegralNumber() && receipt.path("activeProcessCount").asInt() == 0
                    && "NORMAL_EXIT".equals(receipt.path("cleanupTrigger").asText()), "NATIVE_RETAINED_PROCESS_UNQUALIFIED");
            require(item.path("request") instanceof ObjectNode, "NATIVE_RETAINED_REQUEST_INVALID");
            var request = ((ObjectNode) item.path("request")).deepCopy();
            String requestId = request.path("requestId").asText(); request.remove("requestId");
            require(requestId.equals("native-" + adapter.canonicalSha256(request)) && requestId.equals(item.path("requestId").asText())
                    && "spliceai-native-selected-request-v1".equals(request.path("requestSchemaVersion").asText())
                    && "GRCh38".equals(request.path("assembly").asText())
                    && request.path("distanceBp").isIntegralNumber() && request.path("distanceBp").asInt() == distance
                    && request.path("mask").isIntegralNumber() && request.path("mask").asInt() == 0
                    && request.path("variants").isArray() && !request.path("variants").isEmpty() && request.path("variants").size() <= CHUNK_SIZE
                    && item.path("results").isArray() && item.path("results").size() == request.path("variants").size(), "NATIVE_RETAINED_REQUEST_INVALID");
            var units = new LinkedHashMap<String, ObjectNode>();
            for (var unit : request.path("variants")) {
                String id = unit.path("queryId").asText();
                require(unit instanceof ObjectNode && id.matches("reference-[0-9a-f]{64}") && units.putIfAbsent(id, (ObjectNode) unit) == null, "NATIVE_RETAINED_UNIT_INVALID");
            }
            var originalResponse = ((ObjectNode) item).deepCopy();
            for (var value : item.path("results")) {
                String id = value.path("queryId").asText(); var unit = units.remove(id);
                if (!(value instanceof ObjectNode && unit != null && unit.path("allele").equals(value.path("allele"))
                        && item.path("annotationFileSha256").equals(value.path("annotationFileSha256"))
                        && !pending.containsKey(id))) {
                    throw new IllegalArgumentException("NATIVE_RETAINED_RECORD_INVALID");
                }
                pending.put(id, new RetainedFact(unit.deepCopy(), ((ObjectNode) value).deepCopy(), originalResponse));
            }
        }
        require(pending.size() <= MAX_UNITS, "NATIVE_TOTAL_UNIT_LIMIT_EXCEEDED");
        retained.putAll(pending); retainedAuditSha = expectedRawSha256;
    }

    public synchronized SpliceAiRawBatchRunner.BatchResult run(ObjectNode context, JsonNode model,
            List<SpliceAiRawBatchRunner.Query> queries) {
        try {
            adapter.validateCompleteContext(context);
            require("NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()), "NATIVE_SPLICE_SOURCE_REQUIRED");
            if (!(queries != null && !queries.isEmpty() && queries.size() <= MAX_UNITS)) {
                throw new IllegalArgumentException("NATIVE_TOTAL_UNIT_LIMIT_EXCEEDED");
            }
            var selected = NativeSpliceAiRuntime.selectedAnnotation(mapper, model);
            require(selected.path("geneSymbol").equals(context.at("/gene/symbol"))
                    && selected.path("transcriptId").asText().equals(context.at("/transcript/accession").asText()
                            + "." + context.at("/transcript/version").asInt()), "NATIVE_SPLICE_CONTEXT_TRANSCRIPT_MISMATCH");
            var units = new LinkedHashMap<String, ObjectNode>(); var keys = new ArrayList<String>();
            var ids = new HashSet<String>(); var windows = new LinkedHashMap<String, ObjectNode>();
            var requested = mapper.createArrayNode(); boolean hasTarget = false;
            for (var query : queries) {
                if (!(query != null && query.getQueryId() != null && !query.getQueryId().isBlank()
                        && query.getQueryId().length() <= 200 && ids.add(query.getQueryId()))) {
                    throw new IllegalArgumentException("NATIVE_QUERY_ID_INVALID");
                }
                var key = query.getKey(); var allele = allele(key);
                require(query.getGeneSymbol().equals(selected.path("geneSymbol").asText())
                        && query.getSelectedTranscriptId().equals(selected.path("transcriptId").asText())
                        && key.getChromosome().equals(selected.path("contig").asText())
                        && key.getPosition() >= selected.path("start").asInt() && key.getPosition() <= selected.path("end").asInt(),
                        "NATIVE_SPLICE_TRANSCRIPT_IDENTITY_MISMATCH");
                hasTarget |= allele.equals(context.at("/variant/evidenceQueryAllele"));
                var window = window(context, key);
                var pin = mapper.createObjectNode(); pin.set("allele", allele); pin.set("annotation", selected); pin.set("reference", window);
                pin.put("resourceSha256", resourceSha256()); String id = "reference-" + adapter.canonicalSha256(pin);
                var unit = mapper.createObjectNode().put("queryId", id).put("geneSymbol", query.getGeneSymbol())
                        .put("selectedTranscriptId", query.getSelectedTranscriptId());
                unit.set("allele", allele); unit.set("selectedAnnotation", selected);
                units.putIfAbsent(id, unit); windows.put(id, window); keys.add(id);
                var row = requested.addObject().put("queryId", query.getQueryId()); row.set("allele", allele);
                row.put("geneSymbol", query.getGeneSymbol()).put("selectedTranscriptId", query.getSelectedTranscriptId());
            }
            require(hasTarget, "NATIVE_SPLICE_TARGET_QUERY_REQUIRED");
            for (var entry : units.entrySet()) {
                var prior = retained.get(entry.getKey());
                if (prior == null || facts.containsKey(entry.getKey()) || failures.containsKey(entry.getKey())) continue;
                require(prior.unit().equals(entry.getValue()), "NATIVE_RETAINED_UNIT_MISMATCH");
                var record = validateRecord(context, model, entry.getValue(), windows.get(entry.getKey()), prior.value(),
                        prior.response(), prior.response().path("request"));
                var provenance = (ObjectNode) mapper.readTree(record.path("nativeExecutionReceiptJson").asText());
                provenance.put("retainedAuditRawSha256", retainedAuditSha).put("reuseMethod", "PINNED_ORIGINAL_EXECUTION_REVALIDATED");
                record.put("nativeExecutionReceiptJson", provenance.toString()); facts.put(entry.getKey(), record); retainedHits++;
            }
            var missing = new LinkedHashMap<String, ObjectNode>();
            for (var entry : units.entrySet()) {
                if (facts.containsKey(entry.getKey())) hits++;
                else if (!failures.containsKey(entry.getKey())) missing.put(entry.getKey(), entry.getValue());
            }
            require(facts.size() + failures.size() + missing.size() <= MAX_UNITS, "NATIVE_TOTAL_UNIT_LIMIT_EXCEEDED");
            var entries = new ArrayList<>(missing.entrySet());
            for (int offset = 0; offset < entries.size(); offset += CHUNK_SIZE) {
                var chunk = new LinkedHashMap<String, ObjectNode>();
                for (var entry : entries.subList(offset, Math.min(offset + CHUNK_SIZE, entries.size()))) chunk.put(entry.getKey(), entry.getValue());
                compute(context, model, chunk, windows);
            }
            for (String id : units.keySet()) require(facts.containsKey(id), failures.getOrDefault(id, "NATIVE_SPLICE_RECORD_MISSING"));
            var out = mapper.createObjectNode().put("resultSchemaVersion", SpliceAiRawBatchRunner.RESULT_SCHEMA_VERSION)
                    .put("status", "SUCCEEDED").put("requestId", "spliceai-batch-sha256-" + AcmgBusinessStore.sha(mapper.writeValueAsString(requested)));
            out.set("nativeIdentity", identity()); out.put("nativeResourceSha256", resourceSha256());
            var rows = out.putArray("results");
            for (int i = 0; i < queries.size(); i++) {
                var query = queries.get(i); String id = keys.get(i);
                require(windows.get(id).equals(window(context, query.getKey())), "NATIVE_SPLICE_REFERENCE_CHANGED");
                var row = facts.get(id).deepCopy(); row.put("queryId", query.getQueryId()); rows.add(row);
            }
            return SpliceAiRawBatchRunner.BatchResult.succeeded(out, 0);
        } catch (Exception invalid) {
            return SpliceAiRawBatchRunner.BatchResult.failed(invalid instanceof IllegalArgumentException
                    ? invalid.getMessage() : "NATIVE_REFERENCE_SPLICE_UNAVAILABLE", null);
        }
    }

    private void compute(ObjectNode context, JsonNode model, Map<String, ObjectNode> units, Map<String, ObjectNode> windows) {
        if (started == null) started = clock.getAsLong();
        long remaining = budget.toNanos() - (clock.getAsLong() - started);
        if (Thread.currentThread().isInterrupted()) stopped = "NATIVE_SPLICE_INTERRUPTED";
        if (remaining < 1_000_000L) stopped = "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED";
        if (stopped != null) { String reason = stopped; units.keySet().forEach(id -> failures.put(id, reason)); return; }
        var request = mapper.createObjectNode().put("requestSchemaVersion", "spliceai-native-selected-request-v1")
                .put("assembly", "GRCh38").put("distanceBp", distance).put("mask", 0);
        var variants = request.putArray("variants"); units.values().forEach(v -> variants.add(v.deepCopy()));
        request.put("requestId", "native-" + adapter.canonicalSha256(request));
        ObjectNode response = null;
        try {
            calls++; response = transport.apply(request.deepCopy(), Duration.ofNanos(remaining));
            if (response != null) { response = response.deepCopy(); receipts.add(response.deepCopy()); }
            require(!Thread.currentThread().isInterrupted(), "NATIVE_SPLICE_INTERRUPTED");
            require(clock.getAsLong() - started < budget.toNanos(), "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED");
            if (response == null) {
                throw new IllegalArgumentException("NATIVE_SPLICE_EXECUTION_UNAVAILABLE");
            }
            require("SUCCEEDED".equals(response.path("status").asText()), response.path("reasonCode").asText("NATIVE_SPLICE_EXECUTION_UNAVAILABLE"));
            require(response.path("results").isArray() && response.path("results").size() == units.size()
                    && response.path("annotationFileSha256").asText().matches("[0-9a-f]{64}"), "NATIVE_BATCH_RECORDS_INVALID");
            var accepted = new LinkedHashMap<String, ObjectNode>();
            for (var value : response.path("results")) {
                String id = value.path("queryId").asText(); var unit = units.get(id);
                require(value instanceof ObjectNode && unit != null && unit.path("allele").equals(value.path("allele"))
                        && response.path("annotationFileSha256").equals(value.path("annotationFileSha256"))
                        && !accepted.containsKey(id), "NATIVE_BATCH_RECORDS_INVALID");
                accepted.put(id, validateRecord(context, model, unit, windows.get(id), value, response, request));
            }
            require(clock.getAsLong() - started < budget.toNanos(), "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED");
            facts.putAll(accepted);
        } catch (Exception invalid) {
            String reason = invalid instanceof IllegalArgumentException ? invalid.getMessage() : "NATIVE_SPLICE_EXECUTION_UNAVAILABLE";
            units.keySet().forEach(id -> failures.put(id, reason));
            if (reason.contains("TIMEOUT") || reason.contains("INTERRUPTED") || reason.contains("BUDGET") || reason.endsWith("CLEANUP_UNCONFIRMED")) stopped = reason;
        }
    }

    private ObjectNode validateRecord(ObjectNode context, JsonNode model, ObjectNode unit, ObjectNode expectedWindow,
            JsonNode value, ObjectNode response, JsonNode request) throws java.io.IOException {
        require("HIT".equals(value.path("status").asText()) && value.path("exactAlleleMatch").isBoolean()
                && value.path("exactAlleleMatch").asBoolean(), value.path("reasonCode").asText("NATIVE_SPLICE_NO_SCORE"));
        var key = key(unit.path("allele")); var now = window(context, key);
        require(expectedWindow.equals(now), "NATIVE_SPLICE_REFERENCE_CHANGED");
        for (String name : List.of("start", "length", "sha256"))
            require(now.path(name).equals(value.path("referenceWindow").path(name)), "NATIVE_SPLICE_REFERENCE_WINDOW_MISMATCH");
        var score = NativeSpliceAiRuntime.associate(mapper, value, model);
        for (String kind : List.of("AG", "AL", "DG", "DL")) {
            var ds = score.path("deltaScores").path("DS_" + kind); var dp = score.path("deltaPositions").path("DP_" + kind);
            var number = PredictionCalibration.decimal(ds.asText());
            require(ds.isTextual() && number != null && number.signum() >= 0 && number.compareTo(java.math.BigDecimal.ONE) <= 0
                    && dp.isIntegralNumber() && dp.canConvertToInt() && Math.abs((long) dp.asInt()) <= distance, "NATIVE_SPLICE_SCORE_FIELDS_INVALID");
        }
        if(distance==10000) require(value.path("distanceBp").isIntegralNumber() && value.path("distanceBp").asInt()==distance
                && value.path("mask").isIntegralNumber() && value.path("mask").asInt()==0
                && value.path("officialRefAltScores").isObject() && value.path("officialRefAltScores").size()==4,
                "NATIVE_WIDE_SCORE_WITNESS_MISSING");
        if(distance==10000) for(String kind:List.of("AG","AL","DG","DL")) {
            var witness=value.path("officialRefAltScores").path(kind);
            require(witness.path("position").equals(score.path("deltaPositions").path("DP_"+kind)),
                    "NATIVE_WIDE_SCORE_WITNESS_MISMATCH");
            for(String field:List.of("referenceScore","alternateScore")) {
                var number=PredictionCalibration.decimal(witness.path(field).asText());
                require(witness.path(field).isTextual() && number!=null && number.signum()>=0
                        && number.compareTo(java.math.BigDecimal.ONE)<=0,"NATIVE_WIDE_SCORE_WITNESS_INVALID");
            }
            var ref=new java.math.BigDecimal(witness.path("referenceScore").asText());
            var alt=new java.math.BigDecimal(witness.path("alternateScore").asText());
            var difference=kind.endsWith("G")?alt.subtract(ref):ref.subtract(alt);
            var reported=new java.math.BigDecimal(score.path("deltaScores").path("DS_"+kind).asText());
            // The official VCF formatter rounds delta scores to two decimals.
            require(difference.subtract(reported).abs().compareTo(new java.math.BigDecimal("0.005001"))<=0,
                    "NATIVE_WIDE_SCORE_WITNESS_MISMATCH");
        }
        var record = ((ObjectNode) value).deepCopy();
        var provenance = mapper.createObjectNode(); provenance.set("request", request.deepCopy());
        provenance.set("rawOutputSha256", response.path("rawOutputSha256").deepCopy());
        provenance.set("processReceipt", response.path("processReceipt").deepCopy());
        record.put("nativeExecutionReceiptJson", provenance.toString());
        return record;
    }

    private ObjectNode window(ObjectNode context, NormalizedVariantKey key) throws java.io.IOException {
        int half=5000+distance, length=2*half+1;
        long start = key.getPosition() - (long)half;
        require(start > 0 && start <= Integer.MAX_VALUE, "REFERENCE_WINDOW_INCOMPLETE");
        var span = reference.read(key.getChromosome(), (int) start, length);
        if (!(span != null && span.identity() != null && span.bases() != null && span.bases().length() == length
                && context.at("/variant/normalization/referenceSequenceSha256").asText().matches("[0-9a-f]{64}")
                && span.identity().path("expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")))) {
            throw new IllegalArgumentException("NATIVE_SPLICE_QUALIFIED_REFERENCE_REQUIRED");
        }
        require(span.bases().substring(half, half+1).equals(key.getReference()), "REFERENCE_ALLELE_MISMATCH");
        var out = mapper.createObjectNode().put("start", (int) start).put("length", length).put("sha256", CodingTranscriptMaterialSupply.sha(span.bases()));
        out.set("qualifiedReference", span.identity()); return out;
    }

    private ObjectNode allele(NormalizedVariantKey key) {
        if (!(key != null && "GRCh38".equals(key.getGenomeAssembly()) && key.getReference().matches("[ACGT]")
                && key.getAlternate().matches("[ACGT]") && !key.getReference().equals(key.getAlternate()))) {
            throw new IllegalArgumentException("NATIVE_SPLICE_SNV_APPLICATION_SCOPE");
        }
        return mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
    }
    private static NormalizedVariantKey key(JsonNode a) {
        return new NormalizedVariantKey(a.path("assembly").asText(), a.path("contig").asText(), a.path("position").asInt(), a.path("reference").asText(), a.path("alternate").asText());
    }
    public synchronized ObjectNode audit() {
        var out = mapper.createObjectNode().put("batchInvocations", calls).put("reusedUnits", hits)
                .put("acceptedUnits", facts.size()).put("failedUnits", failures.size()); out.set("identity", identity());
        if (retainedAuditSha != null) out.put("retainedAuditRawSha256", retainedAuditSha).put("retainedAuditVersion", RETAINED_AUDIT_VERSION)
                .put("retainedUnitsAvailable", retained.size()).put("retainedUnitsUsed", retainedHits);
        // Raw engine metadata may contain floats; retain the exact serialized value outside canonical scientific fields.
        out.put("nativeReceiptsJson", mapper.valueToTree(receipts).toString()); return out;
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
