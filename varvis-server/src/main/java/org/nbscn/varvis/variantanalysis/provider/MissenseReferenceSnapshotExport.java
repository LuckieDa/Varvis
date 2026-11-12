package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit single-build capture and bounded offline export. No evaluation or approval. */
public final class MissenseReferenceSnapshotExport {
    private static final String MANIFEST = "manifest.json";
    private static final String SEMANTICS = "EXTERNALLY_PINNED_SNAPSHOT_DECLARATIONS_NOT_LIVE_RESOURCE_VERIFICATION";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    public record Result(boolean exported, Path manifestPath, String manifestSha256, ObjectNode report) { }

    /** Frozen scientific bytes. Repeated writes do not call any provider or share mutable DTOs. */
    public static final class Capture {
        private final Map<String, byte[]> documents;
        private final byte[] manifest;
        private final ObjectNode build;
        private final ArrayNode identities;

        private Capture(Map<String, byte[]> documents, byte[] manifest, ObjectNode build, ArrayNode identities) {
            this.documents = new LinkedHashMap<>();
            documents.forEach((name, bytes) -> this.documents.put(name, bytes.clone()));
            this.manifest = manifest.clone();
            this.build = build.deepCopy();
            this.identities = identities.deepCopy();
        }
    }

    public Result export(JsonNode context, VariantInterpretation target, TranscriptConsequence selectedTarget,
                         ClinVarConditionAwareComparatorInput builder, JsonNode sourceSnapshot, Path output) {
        return perform(output, () -> capture(context, target, selectedTarget, builder, sourceSnapshot));
    }

    public Capture capture(JsonNode context, VariantInterpretation target, TranscriptConsequence selectedTarget,
                           ClinVarConditionAwareComparatorInput builder, JsonNode sourceSnapshot) throws IOException {
        if (builder == null) throw new IllegalArgumentException("BUILDER_REQUIRED");
        return capture(context, target, selectedTarget, sourceSnapshot,
                (frozenContext, checked) -> builder.buildWithSnapshots(frozenContext, target, checked));
    }

    Capture capture(JsonNode context, VariantInterpretation target, TranscriptConsequence selectedTarget,
                    JsonNode sourceSnapshot,
                    java.util.function.BiFunction<ObjectNode, TranscriptConsequence, ClinVarConditionAwareComparatorInput.SnapshotCapture> build) throws IOException {
        if (target == null || target.getObservation() == null || target.getObservation().getKey() == null)
            throw new IllegalArgumentException("TARGET_ALLELE_REQUIRED");
        if (selectedTarget == null) throw new IllegalArgumentException("TARGET_ANNOTATION_MISSING");
        ObjectNode frozenContext = object(context, "CONTEXT_OBJECT_REQUIRED").deepCopy();
        ObjectNode sources = object(sourceSnapshot, "SOURCE_SNAPSHOT_REQUIRED").deepCopy();
        ObjectNode annotation = mapper.valueToTree(selectedTarget);
        var checks = new MissenseReferenceDraftPreparation();
        ObjectNode targetSnapshot = targetSnapshot(frozenContext, annotation, sources, checks);
        require(targetSnapshot.path("allele").equals(checks.allele(mapper.valueToTree(target.getObservation().getKey()), true)),
                "TARGET_CONTEXT_MISMATCH");
        TranscriptConsequence frozenTarget = checks.validateTarget(manifest(sources), frozenContext, targetSnapshot, sources);
        // Reject oversized/private caller inputs before any provider work. Provider output is checked below.
        for (JsonNode input : List.of(frozenContext, targetSnapshot, sources)) {
            MissenseReferenceDraftPreparation.noPrivatePaths(input);
            bytes(input);
        }
        return capture(build.apply(frozenContext, frozenTarget), sources);
    }

    /** Export a previously captured invocation, never accepting caller-supplied replacement annotations. */
    public Capture capture(ClinVarConditionAwareComparatorInput.SnapshotCapture captured,
                           JsonNode sourceSnapshot) throws IOException {
        if (captured == null) throw new IllegalArgumentException("CAPTURE_REQUIRED");
        var checks = new MissenseReferenceDraftPreparation();
        ObjectNode context = object(captured.getEvidenceContext(), "CONTEXT_OBJECT_REQUIRED");
        ObjectNode built = object(captured.getResult(), "COMPARATOR_OBJECT_REQUIRED");
        ObjectNode sources = object(sourceSnapshot, "SOURCE_SNAPSHOT_REQUIRED").deepCopy();
        ObjectNode target = targetSnapshot(context, object(captured.getSelectedTargetAnnotation(), "TARGET_ANNOTATION_MISSING"), sources, checks);
        ObjectNode manifest = manifest(sources);
        TranscriptConsequence selected = checks.validateTarget(manifest, context, target, sources);
        String contextId = context.path("contextId").asText(), transcript = checks.transcript(context.path("transcript"));
        ObjectNode targetAllele = checks.allele(target.path("allele"), false);
        require(ClinVarConditionAwareComparatorInput.RESULT_SCHEMA_VERSION.equals(built.path("schemaVersion").asText()), "COMPARATOR_SCHEMA_INVALID");
        require(contextId.equals(built.path("contextId").asText())
                && targetAllele.equals(checks.allele(built.path("targetAllele"), true)), "CAPTURE_CONTEXT_MISMATCH");
        require(Set.of("READY", "NO_RECORD", "NOT_EVALUATED").contains(built.path("status").asText()), "COMPARATOR_STATUS_INVALID");
        bounded(built.path("comparators"), MissenseReferenceDraftPreparation.MAX_CANDIDATES, "CANDIDATE_LIMIT_OR_ARRAY_INVALID");
        bounded(built.path("candidateDecisions"), MissenseReferenceDraftPreparation.MAX_CANDIDATES, "DECISION_LIMIT_OR_ARRAY_INVALID");
        require(captured.getReferenceSnapshots().size() <= MissenseReferenceDraftPreparation.MAX_CANDIDATES, "ANNOTATION_LIMIT_OR_ARRAY_INVALID");

        Map<String, ObjectNode> annotations = new LinkedHashMap<>();
        for (var snapshot : captured.getReferenceSnapshots()) {
            ObjectNode allele = checks.allele(mapper.valueToTree(snapshot.getExactAllele()), true);
            require(transcript.equals(snapshot.getTranscript()), "REFERENCE_TRANSCRIPT_MISMATCH");
            String variation = checks.variation(mapper.createObjectNode().put("variationId", snapshot.getVariationId()));
            String key = checks.key(allele, variation, transcript);
            ObjectNode annotation = object(snapshot.getAnnotation(), "REFERENCE_ANNOTATION_INVALID");
            ObjectNode previous = annotations.putIfAbsent(key, annotation);
            require(previous == null || previous.equals(annotation), "CAPTURE_REFERENCE_CONFLICT");
        }
        Map<String, ObjectNode> snapshots = new LinkedHashMap<>();
        int assertionCount = 0, conditionCount = 0;
        for (JsonNode record : built.path("comparators")) {
            require(ClinVarConditionAwareComparatorInput.RECORD_SCHEMA_VERSION.equals(record.path("schemaVersion").asText()), "COMPARATOR_RECORD_SCHEMA_INVALID");
            require(contextId.equals(record.path("targetContextId").asText()), "REFERENCE_CONTEXT_MISMATCH");
            ObjectNode allele = checks.allele(record.path("exactAllele"), true);
            require(targetAllele.path("contig").equals(allele.path("contig")), "REFERENCE_CONTIG_MISMATCH");
            String variation = checks.variation(record), key = checks.key(allele, variation, transcript);
            ObjectNode annotation = annotations.get(key);
            if (annotation == null) throw new IllegalArgumentException("CAPTURE_REFERENCE_MISSING");
            TranscriptConsequence reference = checks.annotation(annotation, transcript, sources.path("VEP"), sources.path("VEP"));
            require(Objects.equals(selected.getGeneSymbol(), reference.getGeneSymbol()), "REFERENCE_ANNOTATION_GENE_MISMATCH");
            checks.checkProtein(record.path("targetProteinChange"), selected, transcript);
            checks.checkProtein(record.path("comparatorProteinChange"), reference, transcript);
            require(record.path("selectedTargetConsequence").asText().equals(selected.getConsequence())
                    && record.path("selectedComparatorConsequence").asText().equals(reference.getConsequence()), "CAPTURE_CONSEQUENCE_MISMATCH");
            require(checks.sourceMatches(record.path("snapshotProvenance"), sources.path("SIDECAR")), "SIDECAR_SOURCE_MISMATCH");
            boolean bound = false;
            for (JsonNode decision : built.path("candidateDecisions")) {
                if (record.path("exactAllele").equals(decision.path("exactAllele"))
                        && record.path("variationId").equals(decision.path("variationId"))
                        && "READY".equals(decision.path("status").asText())
                        && record.path("snapshotProvenance").equals(decision.path("snapshotProvenance"))) bound = true;
            }
            require(bound, "COMPARATOR_DECISION_BINDING_MISMATCH");
            bounded(record.path("submissions"), MissenseReferenceDraftPreparation.MAX_ASSERTIONS, "ASSERTION_LIMIT_OR_ARRAY_INVALID");
            bounded(record.path("variantSummaryRecords"), MissenseReferenceDraftPreparation.MAX_ASSERTIONS, "CONDITION_LIMIT_OR_ARRAY_INVALID");
            assertionCount += record.path("submissions").size();
            conditionCount += record.path("variantSummaryRecords").size();
            require(assertionCount <= MissenseReferenceDraftPreparation.MAX_ASSERTIONS
                    && conditionCount <= MissenseReferenceDraftPreparation.MAX_ASSERTIONS, "ASSERTION_OR_CONDITION_TOTAL_LIMIT");
            ObjectNode snapshot = mapper.createObjectNode().put("schemaVersion", "missense-reference-annotation-snapshot-v1")
                    .put("contextId", contextId).put("variationId", variation).put("transcript", transcript);
            snapshot.set("targetAllele", targetAllele); snapshot.set("referenceAllele", allele);
            snapshot.set("annotation", annotation); snapshot.set("source", sources.path("VEP"));
            snapshots.putIfAbsent(key, snapshot);
        }
        require(snapshots.keySet().equals(annotations.keySet()), "CAPTURE_REFERENCE_NOT_BOUND");
        for (JsonNode decision : built.path("candidateDecisions")) {
            ObjectNode allele = checks.allele(decision.path("exactAllele"), true);
            require(targetAllele.path("contig").equals(allele.path("contig")), "DECISION_CONTIG_MISMATCH");
            String key = checks.key(allele, checks.variation(decision), transcript);
            if (decision.path("snapshotProvenance").isObject())
                require(checks.sourceMatches(decision.path("snapshotProvenance"), sources.path("SIDECAR")), "SIDECAR_SOURCE_MISMATCH");
            if ("READY".equals(decision.path("status").asText())) require(snapshots.containsKey(key), "READY_DECISION_WITHOUT_COMPARATOR");
        }
        Map<String, ObjectNode> documents = new LinkedHashMap<>();
        documents.put("context.json", context); documents.put("target.json", target);
        documents.put("comparator-input.json", built); documents.put("source-snapshot.json", sources);
        ArrayNode references = manifest.putArray("referenceAnnotations"), identities = mapper.createArrayNode();
        Map<String, byte[]> serialized = new LinkedHashMap<>();
        int index = 0;
        for (ObjectNode snapshot : snapshots.values())
            documents.put(String.format(Locale.ROOT, "reference-%03d.json", index++), snapshot);
        int total = 0;
        for (var entry : documents.entrySet()) {
            MissenseReferenceDraftPreparation.noPrivatePaths(entry.getValue());
            byte[] bytes = bytes(entry.getValue());
            total += bytes.length;
            require(total <= MissenseReferenceDraftPreparation.MAX_TOTAL, "INPUT_SIZE_LIMIT");
            serialized.put(entry.getKey(), bytes);
            ObjectNode descriptor = descriptor(entry.getKey(), bytes, entry.getValue());
            identities.add(descriptor.deepCopy().put("bytes", bytes.length));
            switch (entry.getKey()) {
                case "context.json" -> manifest.set("context", descriptor);
                case "target.json" -> manifest.set("target", descriptor);
                case "comparator-input.json" -> manifest.set("comparatorInput", descriptor);
                case "source-snapshot.json" -> manifest.set("sourceSnapshot", descriptor);
                default -> references.add(descriptor);
            }
        }
        byte[] manifestBytes = bytes(manifest);
        require(total + manifestBytes.length <= MissenseReferenceDraftPreparation.MAX_TOTAL, "INPUT_SIZE_LIMIT");
        identities.add(descriptor(MANIFEST, manifestBytes, manifest).put("bytes", manifestBytes.length));
        return new Capture(serialized, manifestBytes, built, identities);
    }

    public Result write(Capture capture, Path output) {
        return perform(output, () -> { require(capture != null, "CAPTURE_REQUIRED"); return capture; });
    }

    private Result perform(Path output, CaptureSupplier supplier) {
        ObjectNode report = mapper.createObjectNode().put("schemaVersion", "missense-reference-snapshot-export-report-v1")
                .put("exportStatus", "BLOCKED").put("runtimeExecution", "NOT_RUN").put("scientificAdoption", false)
                .put("sourceIdentitySemantics", SEMANTICS);
        boolean directoryCreated = false;
        ArrayNode written = mapper.createArrayNode();
        try {
            if (output == null) throw new IllegalArgumentException("OUTPUT_PATH_REQUIRED");
            Path absolute = output.toAbsolutePath().normalize();
            require(!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS), "OUTPUT_EXISTS_REFUSE_OVERWRITE");
            require(absolute.getParent() != null && Files.isDirectory(absolute.getParent()), "OUTPUT_PARENT_MISSING");
            Files.createDirectory(absolute); directoryCreated = true;
            Capture capture = supplier.get();
            ObjectNode sourceSearch = report.putObject("sourceSearch");
            sourceSearch.set("status", capture.build.path("status").deepCopy());
            sourceSearch.set("reasonCode", capture.build.path("reasonCode").deepCopy());
            sourceSearch.set("discovery", capture.build.path("discovery").deepCopy());
            sourceSearch.put("comparatorCount", capture.build.path("comparators").size());
            sourceSearch.put("candidateDecisionCount", capture.build.path("candidateDecisions").size());
            sourceSearch.put("completeResultFile", "comparator-input.json");
            // Pin the complete declarations without duplicating arbitrarily large source metadata in receipts.
            for (JsonNode identity : capture.identities) if ("source-snapshot.json".equals(identity.path("path").asText()))
                report.set("sourceSnapshot", identity.deepCopy());
            report.set("outputs", capture.identities.deepCopy());
            String pin = Pm4MaterialPreparation.sha(capture.manifest);
            report.put("manifestRawSha256", pin);
            for (var entry : capture.documents.entrySet()) writeFile(absolute, entry.getKey(), entry.getValue(), written);
            // A pending name is never a successful completion entry. Atomic publication happens last.
            writeFile(absolute, "manifest.pending.json", capture.manifest, written);
            report.put("exportStatus", "SNAPSHOT_EXPORTED");
            report.put("completionEntry", MANIFEST);
            writeFile(absolute, "snapshot-export-report.json", bytes(report), written);
            ArrayNode completedFiles = written.deepCopy();
            for (JsonNode entry : completedFiles) if ("manifest.pending.json".equals(entry.path("file").asText()))
                ((ObjectNode) entry).put("file", MANIFEST);
            ObjectNode artifacts = artifacts(report, completedFiles, absolute);
            writeFile(absolute, "artifact-manifest.json", bytes(artifacts), written);
            Files.move(absolute.resolve("manifest.pending.json"), absolute.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE);
            return new Result(true, absolute.resolve(MANIFEST), pin, report);
        } catch (IOException | RuntimeException failure) {
            report.put("exportStatus", "BLOCKED").put("reasonCode", reason(failure, directoryCreated));
            report.remove("manifestRawSha256");
            report.put("residualFilesAreIncomplete", true);
            if (directoryCreated && output != null) {
                // Only replace this invocation's receipts; never touch a caller's pre-existing directory.
                try {
                    byte[] failedReport;
                    try {
                        failedReport = bytes(report);
                    } catch (IOException | IllegalArgumentException oversizedReceipt) {
                        report.remove(List.of("sourceSearch", "sourceSnapshot", "outputs"));
                        report.put("receiptDetailStatus", "OMITTED_TO_PRESERVE_BOUNDED_FAILURE_REPORT");
                        failedReport = bytes(report);
                    }
                    Files.write(output.resolve("snapshot-export-report.json"), failedReport, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    ArrayNode failureFiles = mapper.createArrayNode();
                    for (JsonNode entry : written) if (!Set.of("snapshot-export-report.json", "artifact-manifest.json").contains(entry.path("file").asText()))
                        failureFiles.add(entry.deepCopy());
                    failureFiles.addObject().put("file", "snapshot-export-report.json").put("bytes", failedReport.length)
                            .put("rawSha256", Pm4MaterialPreparation.sha(failedReport));
                    Files.write(output.resolve("artifact-manifest.json"), bytes(artifacts(report, failureFiles, output)), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException | RuntimeException receiptFailure) {
                    report.put("receiptStatus", "WRITE_FAILED");
                }
            }
            return new Result(false, null, null, report);
        }
    }

    private ObjectNode artifacts(ObjectNode report, ArrayNode written, Path output) {
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "missense-reference-snapshot-artifact-manifest-v1")
                .put("generatedAt", Instant.now().toString()).put("outputDirectory", output.toAbsolutePath().normalize().toString())
                .put("exportStatus", report.path("exportStatus").asText()).put("runtimeExecution", "NOT_RUN").put("scientificAdoption", false)
                .put("completionEntry", MANIFEST).put("completionRequiresManifest", true);
        result.set("sourceSnapshot", report.path("sourceSnapshot").deepCopy());
        result.set("outputs", report.path("outputs").deepCopy());
        result.set("writtenFiles", written.deepCopy());
        if (report.has("reasonCode")) result.set("reasonCode", report.path("reasonCode"));
        return result;
    }

    private void writeFile(Path output, String name, byte[] bytes, ArrayNode written) throws IOException {
        Files.write(output.resolve(name), bytes, StandardOpenOption.CREATE_NEW);
        written.addObject().put("file", name).put("bytes", bytes.length).put("rawSha256", Pm4MaterialPreparation.sha(bytes));
    }

    private ObjectNode targetSnapshot(ObjectNode context, ObjectNode annotation, ObjectNode sources,
                                      MissenseReferenceDraftPreparation checks) {
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "missense-target-annotation-snapshot-v1")
                .put("contextId", context.path("contextId").asText()).put("transcript", checks.transcript(context.path("transcript")));
        result.set("allele", checks.allele(context.at("/variant/evidenceQueryAllele"), false));
        result.set("annotation", annotation); result.set("source", sources.path("VEP"));
        return result;
    }

    private ObjectNode manifest(ObjectNode sources) {
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "missense-reference-draft-manifest-v1");
        result.set("dataClass", sources.path("dataClass"));
        if (sources.has("purpose")) result.set("purpose", sources.path("purpose"));
        return result;
    }

    private ObjectNode descriptor(String name, byte[] bytes, JsonNode content) {
        return mapper.createObjectNode().put("path", name).put("rawSha256", Pm4MaterialPreparation.sha(bytes))
                .put("canonicalSha256", adapter.canonicalSha256(content));
    }

    private byte[] bytes(JsonNode content) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (OutputStream bounded = new OutputStream() {
            @Override public void write(int value) { require(buffer.size() < MissenseReferenceDraftPreparation.MAX_FILE, "INPUT_SIZE_LIMIT"); buffer.write(value); }
            @Override public void write(byte[] values, int offset, int length) {
                require(length <= MissenseReferenceDraftPreparation.MAX_FILE - buffer.size(), "INPUT_SIZE_LIMIT");
                buffer.write(values, offset, length);
            }
        }) { mapper.writerWithDefaultPrettyPrinter().writeValue(bounded, content); }
        return buffer.toByteArray();
    }

    private static ObjectNode object(JsonNode node, String reason) {
        require(node instanceof ObjectNode, reason); return (ObjectNode) node;
    }
    private static void bounded(JsonNode values, int max, String reason) { require(values.isArray() && values.size() <= max, reason); }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
    private static String reason(Exception failure, boolean directoryCreated) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null && cause.getMessage().matches("[A-Z0-9_]+"))
                return cause.getMessage();
        }
        if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException) return "SNAPSHOT_JSON_INVALID";
        return failure instanceof IOException ? (directoryCreated ? "SNAPSHOT_OUTPUT_IO_FAILURE" : "OUTPUT_DIRECTORY_IO_FAILURE") : "SNAPSHOT_INPUT_INVALID";
    }
    @FunctionalInterface private interface CaptureSupplier { Capture get() throws IOException; }
}
