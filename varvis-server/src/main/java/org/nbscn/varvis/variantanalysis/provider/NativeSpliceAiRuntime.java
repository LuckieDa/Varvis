package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.springframework.lang.Nullable;

/** Request-local RAW facts, then exact transcript association. Never substitutes a gene label for a transcript. */
public final class NativeSpliceAiRuntime {
    public static final String VERSION = "1.2.0";
    private static final int CHUNK_SIZE = 10, MAX_UNITS = 50;
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final Function<ObjectNode, ObjectNode> annotation;
    private final BiFunction<ObjectNode, Duration, ObjectNode> run;
    private final Duration totalBudget;
    private final LongSupplier nanoTime;
    private final CodingTranscriptMaterialSupply.ReferenceReader reference;
    private final PredictionSpecializationGuard guard;
    private final ObjectNode identity;
    @Nullable private ObjectNode batch;
    private final Map<String, ObjectNode> facts = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private int calls;

    public NativeSpliceAiRuntime(ObjectMapper mapper, GencodeTranscriptIndex index,
            CodingTranscriptMaterialSupply.ReferenceReader reference, PredictionSpecializationGuard guard, NativeSpliceAiBatchRunner runner) {
        this(mapper, context -> { try { return index.annotation(context); } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("GENCODE_ANNOTATION_UNAVAILABLE", invalid); } }, reference, guard, runner.identity(),
                runner.timeout(), System::nanoTime, runner::run);
        identity.set("gencode", index.identity());
    }

    NativeSpliceAiRuntime(ObjectMapper mapper, Function<ObjectNode, ObjectNode> annotation,
            CodingTranscriptMaterialSupply.ReferenceReader reference, PredictionSpecializationGuard guard,
            ObjectNode runtimeIdentity, Function<ObjectNode, ObjectNode> run) {
        this(mapper, annotation, reference, guard, runtimeIdentity, Duration.ofSeconds(540), System::nanoTime, (request, budget) -> run.apply(request));
    }

    NativeSpliceAiRuntime(ObjectMapper mapper, Function<ObjectNode, ObjectNode> annotation,
            CodingTranscriptMaterialSupply.ReferenceReader reference, PredictionSpecializationGuard guard,
            ObjectNode runtimeIdentity, Duration totalBudget, LongSupplier nanoTime, BiFunction<ObjectNode, Duration, ObjectNode> run) {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper); this.annotation = annotation;
        this.reference = reference; this.guard = guard; this.run = run;
        require(totalBudget.toMillis() >= 1 && totalBudget.compareTo(Duration.ofSeconds(600)) <= 0, "NATIVE_TIMEOUT_INVALID");
        this.totalBudget = totalBudget; this.nanoTime = nanoTime;
        identity = mapper.createObjectNode().put("method", "NATIVE_RAW_SELECTED_GENCODE_V1").put("version", VERSION)
                .put("calibrationId", "CLINGEN_SVI_SPLICE_2024_03_22_SECTION_5").put("scoreMode", "RAW")
                .put("distance", 4999).put("mask", 0).put("clinicalApproval", false);
        identity.put("maximumUnits", MAX_UNITS).put("unitsPerProcess", CHUNK_SIZE).put("totalBudgetMillis", totalBudget.toMillis());
        // Runtime metadata contains Python floating-point settings; retain its JSON as opaque text,
        // never feed binary floats into the scientific canonical identity contract.
        identity.put("runtimeIdentityJson", runtimeIdentity.toString()); identity.set("specializationScope", guard.identity());
    }

    public final class Plan {
        private final ObjectNode snapshot, context;
        @Nullable private final ObjectNode model;
        @Nullable private final TranscriptConsequence tx;
        @Nullable private final NormalizedVariantKey key;
        private Plan(ObjectNode snapshot, ObjectNode context, @Nullable ObjectNode model, @Nullable TranscriptConsequence tx, @Nullable NormalizedVariantKey key) {
            this.snapshot = snapshot.deepCopy(); this.context = context.deepCopy(); this.model = model == null ? null : model.deepCopy();
            this.tx = tx == null ? null : mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .convertValue(mapper.valueToTree(tx), TranscriptConsequence.class); this.key = key;
        }
        public ObjectNode snapshot() { return snapshot.deepCopy(); }
        private NativeSpliceAiRuntime owner() { return NativeSpliceAiRuntime.this; }
    }

    public Plan prepare(ObjectNode context, VariantInterpretation candidate, boolean specializedRoute) {
        var out = mapper.createObjectNode().put("status", "PREPARED").put("rawQueryId", ""); out.set("identity", identity.deepCopy());
        ObjectNode model = null; TranscriptConsequence tx = null;
        NormalizedVariantKey key = candidate.getObservation() == null ? null : candidate.getObservation().getKey();
        try {
            adapter.validateCompleteContext(context);
            require("NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()), "NATIVE_SPLICE_SOURCE_REQUIRED");
            if (!(key != null && "GRCh38".equals(key.getGenomeAssembly()) && key.getReference().matches("[ACGT]")
                    && key.getAlternate().matches("[ACGT]") && !key.getReference().equals(key.getAlternate()))) {
                throw new IllegalArgumentException("NATIVE_SPLICE_SNV_APPLICATION_SCOPE");
            }
            var expected = context.at("/variant/evidenceQueryAllele");
            require(key.getChromosome().equals(expected.path("contig").asText()) && key.getPosition() == expected.path("position").asLong()
                    && key.getReference().equals(expected.path("reference").asText()) && key.getAlternate().equals(expected.path("alternate").asText())
                    && "GRCh38".equals(expected.path("assembly").asText()), "NATIVE_SPLICE_CONTEXT_ALLELE_MISMATCH");
            tx = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (!(tx != null && tx.getGeneId() != null && tx.getVersionedTranscriptId() != null)) {
                throw new IllegalArgumentException("PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            }
            var scopeSelection = guard.checkPredictions(context);
            out.set("specializationSelection", scopeSelection);
            require(!specializedRoute && !"BLOCKED".equals(scopeSelection.path("status").asText()), "PREDICTION_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION");
            require((IntronicSplicePolicy.missense(tx) || IntronicSplicePolicy.spliceCandidate(tx))
                    && !tx.getConsequence().contains("splice_donor_variant") && !tx.getConsequence().contains("splice_acceptor_variant"), "SPLICE_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE");
            model = annotation.apply(context);
            require(model.path("transcript").asText().equals(tx.getVersionedTranscriptId())
                    && model.path("geneSymbol").asText().equals(tx.getGeneSymbol())
                    && model.path("geneId").asText().replaceFirst("\\.[0-9]+$", "").equals(tx.getGeneId().replaceFirst("\\.[0-9]+$", "")),
                    "NATIVE_SPLICE_TRANSCRIPT_IDENTITY_MISMATCH");
            out.set("annotation", model.deepCopy());
            var selected = selectedAnnotation(mapper, model);
            require(selected.path("contig").asText().equals(key.getChromosome())
                    && selected.path("start").asInt() <= key.getPosition() && key.getPosition() <= selected.path("end").asInt(),
                    "NATIVE_SELECTED_TRANSCRIPT_RANGE_INVALID");
            out.set("selectedAnnotation", selected);
            var unit = mapper.createObjectNode(); unit.set("allele", expected.deepCopy()); unit.set("annotation", selected.deepCopy());
            out.put("rawQueryId", "selected-" + adapter.canonicalSha256(unit));
            out.set("referenceWindow", window(context, key));
        } catch (Exception blocked) {
            out.put("status", "BLOCKED").put("reasonCode", blocked instanceof IllegalArgumentException ? blocked.getMessage() : "NATIVE_SPLICE_PREPARATION_UNAVAILABLE");
        }
        return new Plan(out, context, model, tx, key);
    }

    private ObjectNode window(ObjectNode context, NormalizedVariantKey key) throws java.io.IOException {
        long start = key.getPosition() - 9999L;
        require(start > 0 && start <= Integer.MAX_VALUE, "REFERENCE_WINDOW_INCOMPLETE");
        var span = reference.read(key.getChromosome(), (int) start, 19999);
        if (!(span != null && span.identity() != null && span.bases() != null && span.bases().length() == 19999
                && span.identity().path("expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")))) {
            throw new IllegalArgumentException("NATIVE_SPLICE_QUALIFIED_REFERENCE_REQUIRED");
        }
        require(span.bases().substring(9999, 10000).equals(key.getReference()), "REFERENCE_ALLELE_MISMATCH");
        var out = mapper.createObjectNode().put("start", (int) start).put("length", 19999).put("sha256", CodingTranscriptMaterialSupply.sha(span.bases()));
        out.set("qualifiedReference", span.identity()); return out;
    }

    /** One unit is an allele plus its exact transcript/structure; different transcripts never share scores. */
    public void compute(List<Plan> plans) {
        if (batch != null) return;
        long started = nanoTime.getAsLong(); var unique = new LinkedHashMap<String, Plan>();
        for (var plan : plans) {
            require(plan.owner() == this, "NATIVE_SPLICE_PLAN_OWNER_MISMATCH");
            if ("PREPARED".equals(plan.snapshot.path("status").asText())) unique.putIfAbsent(plan.snapshot.path("rawQueryId").asText(), plan);
        }
        var batch = mapper.createObjectNode().put("status", "NOT_QUERIED").put("uniqueUnits", unique.size());
        this.batch = batch;
        if (unique.isEmpty() || unique.size() > MAX_UNITS) {
            String reason = unique.isEmpty() ? "NO_ELIGIBLE_NATIVE_SPLICE_PLAN" : "NATIVE_TOTAL_UNIT_LIMIT_EXCEEDED";
            batch.put("reasonCode", reason); unique.keySet().forEach(id -> failures.put(id, reason)); return;
        }
        var units = new ArrayList<>(unique.entrySet()); var chunks = batch.putArray("batches");
        String stopped = null;
        for (int offset = 0; offset < units.size(); offset += CHUNK_SIZE) {
            var chunk = new LinkedHashMap<String, Plan>();
            for (var entry : units.subList(offset, Math.min(offset + CHUNK_SIZE, units.size()))) chunk.put(entry.getKey(), entry.getValue());
            var request = request(chunk); long remaining = totalBudget.toNanos() - (nanoTime.getAsLong() - started);
            var audit = chunks.addObject().put("batchIndex", offset / CHUNK_SIZE).put("units", chunk.size())
                    .put("requestId", request.path("requestId").asText()).put("attempted", false);
            if (stopped == null && Thread.currentThread().isInterrupted()) stopped = "NATIVE_SPLICE_INTERRUPTED";
            if (stopped == null && remaining < 1_000_000L) stopped = "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED";
            ObjectNode response;
            if (stopped != null) response = mapper.createObjectNode().put("status", "NOT_QUERIED").put("reasonCode", stopped);
            else {
                audit.put("attempted", true).put("remainingBudgetMillis", remaining / 1_000_000L); calls++;
                try { response = run.apply(request.deepCopy(), Duration.ofNanos(remaining)); }
                catch (RuntimeException unavailable) { response = null; }
                if (response == null) response = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "NATIVE_SPLICE_EXECUTION_UNAVAILABLE");
                else response = response.deepCopy();
                if (Thread.currentThread().isInterrupted()) stopped = "NATIVE_SPLICE_INTERRUPTED";
                else if (nanoTime.getAsLong() - started >= totalBudget.toNanos()) stopped = "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED";
                if (stopped != null) response.put("status", "UNAVAILABLE").put("reasonCode", stopped);
            }
            if ("SUCCEEDED".equals(response.path("status").asText())) accept(chunk, response);
            if (stopped == null && nanoTime.getAsLong() - started >= totalBudget.toNanos()) {
                stopped = "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED";
                chunk.keySet().forEach(facts::remove);
                response.put("status", "UNAVAILABLE").put("reasonCode", stopped);
            }
            String reason = response.path("reasonCode").asText("NATIVE_SPLICE_EXECUTION_UNAVAILABLE");
            if (!"SUCCEEDED".equals(response.path("status").asText())) {
                chunk.keySet().forEach(id -> failures.put(id, reason));
                if (reason.equals("NATIVE_SPLICE_TIMEOUT") || reason.equals("NATIVE_SPLICE_INTERRUPTED")
                        || reason.equals("NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED")
                        || reason.endsWith("CLEANUP_UNCONFIRMED")) stopped = reason;
            }
            audit.set("result", response);
        }
        batch.put("status", facts.size() == unique.size() ? "SUCCEEDED" : facts.isEmpty() ? "UNAVAILABLE" : "PARTIAL")
                .put("acceptedUnits", facts.size()).put("unavailableUnits", failures.size());
    }

    private ObjectNode request(Map<String, Plan> units) {
        var request = mapper.createObjectNode().put("requestSchemaVersion", "spliceai-native-selected-request-v1")
                .put("assembly", "GRCh38").put("distanceBp", 4999).put("mask", 0);
        var variants = request.putArray("variants");
        for (var entry : units.entrySet()) {
            var selection = entry.getValue().snapshot.path("selectedAnnotation");
            var value = variants.addObject().put("queryId", entry.getKey()).put("geneSymbol", selection.path("geneSymbol").asText())
                    .put("selectedTranscriptId", selection.path("transcriptId").asText());
            value.set("selectedAnnotation", selection.deepCopy());
            value.set("allele", entry.getValue().context.at("/variant/evidenceQueryAllele").deepCopy());
        }
        return request.put("requestId", "native-" + adapter.canonicalSha256(request));
    }

    private void accept(Map<String, Plan> units, ObjectNode response) {
        var accepted = new LinkedHashMap<String, ObjectNode>();
        try {
            require(response.path("results").isArray() && response.path("results").size() == units.size()
                    && response.path("annotationFileSha256").asText().matches("[0-9a-f]{64}"), "NATIVE_BATCH_RECORDS_INVALID");
            for (var record : response.path("results")) {
                String id = record.path("queryId").asText(); var plan = units.get(id);
                require(record instanceof ObjectNode && plan != null && plan.context.at("/variant/evidenceQueryAllele").equals(record.path("allele"))
                        && response.path("annotationFileSha256").equals(record.path("annotationFileSha256"))
                        && accepted.putIfAbsent(id, (ObjectNode) record.deepCopy()) == null, "NATIVE_BATCH_RECORDS_INVALID");
            }
            facts.putAll(accepted);
        } catch (IllegalArgumentException invalid) { response.put("status", "UNAVAILABLE").put("reasonCode", invalid.getMessage()); }
    }

    public ObjectNode evaluate(Plan plan) {
        var currentBatch = batch;
        if (plan.owner() != this || currentBatch == null) throw new IllegalArgumentException("NATIVE_SPLICE_NOT_COMPUTED");
        var out = mapper.createObjectNode(); out.set("plan", plan.snapshot());
        var mechanism = out.putObject("mechanism").put("status", "NOT_EVALUATED").put("appliedStrength", "NONE");
        try {
            require("PREPARED".equals(plan.snapshot.path("status").asText()), plan.snapshot.path("reasonCode").asText());
            String id = plan.snapshot.path("rawQueryId").asText();
            String failure = failures.get(id);
            if (failure != null) throw new IllegalArgumentException(failure);
            ObjectNode fact = facts.get(id);
            if (fact == null) throw new IllegalArgumentException("NATIVE_SPLICE_RECORD_MISSING");
            var key = plan.key; var tx = plan.tx; var model = plan.model;
            if (key == null || tx == null || model == null) throw new IllegalArgumentException("NATIVE_SPLICE_PREPARATION_UNAVAILABLE");
            out.set("observation", fact.deepCopy());
            require("HIT".equals(fact.path("status").asText()) && fact.path("exactAlleleMatch").asBoolean(), fact.path("reasonCode").asText("NATIVE_SPLICE_NO_SCORE"));
            var now = window(plan.context, key);
            require(plan.snapshot.path("referenceWindow").equals(now), "NATIVE_SPLICE_REFERENCE_CHANGED");
            for (String name : List.of("start", "length", "sha256")) require(now.path(name).equals(fact.path("referenceWindow").path(name)), "NATIVE_SPLICE_REFERENCE_WINDOW_MISMATCH");
            var selected = associate(mapper, fact, model);
            var record = mapper.createObjectNode().put("geneSymbol", tx.getGeneSymbol()).put("geneId", tx.getGeneId())
                    .put("transcriptId", tx.getVersionedTranscriptId()); record.set("queryAllele", mapper.valueToTree(key));
            for (String kind : List.of("AG", "AL", "DG", "DL")) {
                var ds = selected.path("deltaScores").path("DS_" + kind); var dp = selected.path("deltaPositions").path("DP_" + kind);
                var score = PredictionCalibration.decimal(ds.asText());
                require(ds.isTextual() && score != null && score.signum() >= 0 && score.compareTo(java.math.BigDecimal.ONE) <= 0
                        && dp.isIntegralNumber() && dp.canConvertToInt() && Math.abs((long) dp.asInt()) <= 4999, "NATIVE_SPLICE_SCORE_FIELDS_INVALID");
                record.set("DS_" + kind, ds); record.set("DP_" + kind, dp);
            }
            out.put("associationStatus", "EXACT_SELECTED_ANNOTATION_MATCH"); out.set("associatedRecord", record);
            out.set("mechanism", SpliceAiBusinessRuntime.calibrateAssociatedDevelopment(mapper, record, tx, key));
        } catch (Exception blocked) { mechanism.put("reasonCode", blocked instanceof IllegalArgumentException ? blocked.getMessage() : "NATIVE_SPLICE_ASSOCIATION_UNAVAILABLE"); }
        return out;
    }

    static JsonNode associate(ObjectMapper mapper, JsonNode fact, JsonNode model) {
        require(selectedAnnotation(mapper, model).equals(fact.path("selectedAnnotation")), "NATIVE_SELECTED_ANNOTATION_RECEIPT_MISMATCH");
        require(fact.path("annotationFileSha256").asText().matches("[0-9a-f]{64}"), "NATIVE_SELECTED_ANNOTATION_RECEIPT_MISSING");
        String gene = model.path("geneSymbol").asText(); JsonNode row = null, score = null;
        for (var value : fact.path("annotationRows")) if (gene.equals(value.path("geneSymbol").asText())) {
            require(row == null, "NATIVE_SPLICE_ANNOTATION_AMBIGUOUS"); row = value;
        }
        if (row == null) {
            throw new IllegalArgumentException("NATIVE_SPLICE_GENE_NOT_ANNOTATED");
        }
        var exons = new ArrayList<JsonNode>(); model.path("exons").forEach(exons::add);
        exons.sort(Comparator.comparingInt(e -> e.path("genomicStart").asInt()));
        var expected = mapper.createArrayNode();
        for (var exon : exons) expected.addArray().add(exon.path("genomicStart").asInt()).add(exon.path("genomicEnd").asInt());
        require(!exons.isEmpty() && expected.equals(row.path("exons"))
                && model.path("contig").asText().equals(row.path("contig").asText())
                && (model.path("strand").asInt() == 1 ? "+" : "-").equals(row.path("strand").asText())
                && exons.get(0).path("genomicStart").asInt() == row.path("start").asInt()
                && exons.get(exons.size() - 1).path("genomicEnd").asInt() == row.path("end").asInt(), "NATIVE_SPLICE_TRANSCRIPT_STRUCTURE_MISMATCH");
        for (var value : fact.path("annotations")) if (gene.equals(value.path("geneSymbol").asText())) {
            require(score == null, "NATIVE_SPLICE_SCORE_AMBIGUOUS"); score = value;
        }
        require(score != null && fact.at("/allele/alternate").equals(score.path("allele")), "NATIVE_SPLICE_SCORE_ALLELE_MISMATCH");
        return score;
    }

    /** Canonical one-based annotation; the official parser alone performs the inverse file conversion. */
    static ObjectNode selectedAnnotation(ObjectMapper mapper, JsonNode model) {
        require(model.path("transcript").asText().matches("ENST[0-9]+\\.[1-9][0-9]*")
                && model.path("geneId").asText().matches("ENSG[0-9]+(?:\\.[1-9][0-9]*)?")
                && model.path("geneSymbol").asText().matches("[A-Za-z0-9_.-]{1,100}")
                && model.path("contig").asText().matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y)")
                && model.path("strand").isIntegralNumber() && model.path("strand").canConvertToInt()
                && (model.path("strand").asInt() == 1 || model.path("strand").asInt() == -1), "NATIVE_SELECTED_ANNOTATION_IDENTITY_INVALID");
        require(model.path("exons").isArray() && !model.path("exons").isEmpty() && model.path("exons").size() <= 1000,
                "NATIVE_SELECTED_EXONS_INVALID");
        var exons = new ArrayList<JsonNode>();
        for (var exon : model.path("exons")) {
            require(exon.path("genomicStart").isIntegralNumber() && exon.path("genomicStart").canConvertToInt()
                    && exon.path("genomicEnd").isIntegralNumber() && exon.path("genomicEnd").canConvertToInt(), "NATIVE_SELECTED_EXONS_INVALID");
            exons.add(exon);
        }
        exons.sort(Comparator.comparingInt(e -> e.path("genomicStart").asInt()));
        var out = mapper.createObjectNode().put("transcriptId", model.path("transcript").asText())
                .put("geneId", model.path("geneId").asText()).put("geneSymbol", model.path("geneSymbol").asText())
                .put("contig", model.path("contig").asText()).put("strand", model.path("strand").asInt() == 1 ? "+" : "-");
        var spans = out.putArray("exons"); int previous = 0;
        for (var exon : exons) {
            int start = exon.path("genomicStart").asInt(), end = exon.path("genomicEnd").asInt();
            require(start > previous && end >= start, "NATIVE_SELECTED_EXONS_INVALID");
            spans.addArray().add(start).add(end); previous = end;
        }
        return out.put("start", exons.get(0).path("genomicStart").asInt()).put("end", previous);
    }

    public ObjectNode audit() {
        var out = mapper.createObjectNode().put("batchInvocations", calls); out.set("identity", identity.deepCopy());
        if (batch != null) out.put("batchJson", batch.toString()); return out;
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
