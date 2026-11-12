package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/** Selected-query to existing reference material chain. An invocation never authors scientific approval. */
public final class MissenseSelectedReferencePreparation {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ClinVarConditionAwareComparatorInput comparator;
    @Nullable private final BiFunction<NormalizedVariantKey, String, SpliceFact> splice;

    /** Includes the exact source envelope bytes, so a saved snapshot retains a resolvable ledger. */
    public record SpliceFact(SharedSpliceObservation observation, ExplicitContextSourceLedger source) { }

    public MissenseSelectedReferencePreparation(ObjectMapper mapper, ClinVarConditionAwareComparatorInput comparator,
            @Nullable BiFunction<NormalizedVariantKey, String, SpliceFact> splice) {
        this.mapper = Objects.requireNonNull(mapper);
        this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.comparator = Objects.requireNonNull(comparator);
        this.splice = splice;
    }

    /** Detached identity and immutable captured source facts, suitable for the original trusted bundle factory. */
    public static final class PreparedReference {
        private final ObjectNode projection, report;
        private final ClinVarConditionAwareComparatorInput.SnapshotCapture capture;
        private PreparedReference(ObjectNode projection, ObjectNode report, ClinVarConditionAwareComparatorInput.SnapshotCapture capture) {
            this.projection = projection.deepCopy(); this.report = report.deepCopy(); this.capture = capture;
        }
        public ObjectNode projection() { return projection.deepCopy(); }
        public ObjectNode report() { return report.deepCopy(); }
        public ClinVarConditionAwareComparatorInput.SnapshotCapture capture() { return capture; }
        public MissenseComparisonRuntime bind(MissenseComparisonRuntime runtime) { return runtime.withCapturedInput(capture); }
    }

    public static final class Batch {
        private final ObjectNode report;
        private final List<PreparedReference> prepared;
        private Batch(ObjectNode report, List<PreparedReference> prepared) {
            this.report = report.deepCopy(); this.prepared = List.copyOf(prepared);
        }
        public ObjectNode report() { return report.deepCopy(); }
        public List<PreparedReference> prepared() { return prepared; }
    }

    /** Every selected attempt retains its position. Public/test material contract is unchanged. */
    public Batch prepare(SelectedEvidencePreparation selected, JsonNode sourceSnapshot, Path output) throws IOException {
        Objects.requireNonNull(selected);
        ObjectNode sources = ((ObjectNode) Objects.requireNonNull(sourceSnapshot)).deepCopy();
        var checks = new MissenseReferenceDraftPreparation();
        for (String role : List.of("CLINVAR_VCF", "SIDECAR", "VEP")) checks.sourceIdentity(sources.path(role));
        MissenseReferenceDraftPreparation.noPrivatePaths(sources);
        if (!Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(sources.path("dataClass").asText()))
            throw new IllegalArgumentException("DATA_CLASS_UNSUPPORTED");
        if ("SYNTHETIC".equals(sources.path("dataClass").asText()) && !"TEST_ONLY".equals(sources.path("purpose").asText()))
            throw new IllegalArgumentException("SYNTHETIC_TEST_ONLY_REQUIRED");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("OUTPUT_EXISTS_REFUSE_OVERWRITE");
        Files.createDirectory(output);
        var session = comparator.openSession(sources);
        var exporter = new MissenseReferenceSnapshotExport();
        ObjectNode report = mapper.createObjectNode().put("schemaVersion", "missense-selected-reference-preparation-v1")
                .put("scientificAdoption", false).put("reviewStatus", "UNREVIEWED").put("runtimeExecution", "NOT_RUN")
                .put("sourceSnapshotSha256", adapter.canonicalSha256(sources));
        report.set("sourceSnapshot", sources);
        report.set("selectedPreparationProvenance", selected.provenance());
        ArrayNode rows = report.putArray("candidates");
        List<PreparedReference> prepared = new ArrayList<>();
        int index = 0;
        for (var candidate : selected.assembly().candidates()) {
            ObjectNode row = rows.addObject().put("candidateId", candidate.candidateId());
            ArrayNode attempts = row.putArray("attempts");
            if (candidate.attempts().isEmpty()) {
                row.put("status", "BLOCKED").put("reasonCode", "SELECTED_CONTEXT_UNAVAILABLE");
                continue;
            }
            for (var attempt : candidate.attempts()) {
                ObjectNode entry = attempts.addObject().put("hypothesisId", attempt.hypothesisId()).put("status", "BLOCKED")
                        .put("reviewStatus", "UNREVIEWED").put("runtimeExecution", "NOT_RUN");
                if (!attempt.blockers().isEmpty()) {
                    entry.set("blockers", mapper.valueToTree(attempt.blockers()));
                    entry.put("reasonCode", attempt.blockers().get(0).reasonCode()); continue;
                }
                try {
                    var projection = selected.legacyProjection(candidate.candidateId(), attempt.hypothesisId());
                    ObjectNode context = projection.context();
                    if (!Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(context.path("dataClass").asText()))
                        throw new IllegalArgumentException("REFERENCE_DRAFT_DATA_CLASS_UNSUPPORTED");
                    if (!sources.path("dataClass").equals(context.path("dataClass")))
                        throw new IllegalArgumentException("CONTEXT_DATA_CLASS_MISMATCH");
                    VariantInterpretation query = projection.queryCandidate();
                    var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
                    var transcript = wiring.selectedVepConsequence(context, query);
                    if (transcript == null) throw new IllegalArgumentException("MISSENSE_SELECTED_TRANSCRIPT_NOT_UNIQUE");
                    entry.set("contextProjection", projection.snapshot());
                    ObjectNode identity = mapper.createObjectNode(); identity.set("contextProjection", projection.snapshot()); identity.set("sources", sources);
                    entry.put("preparationId", "missense-preparation-sha256-" + adapter.canonicalSha256(identity));
                    String directory = String.format(java.util.Locale.ROOT, "attempt-%03d", index++);
                    Path root = output.resolve(directory); Files.createDirectory(root); entry.put("directory", directory);
                    var captured = new ClinVarConditionAwareComparatorInput.SnapshotCapture[1];
                    var exportCapture = exporter.capture(context, query, transcript, sources, (frozenContext, checked) -> {
                        captured[0] = session.buildWithSnapshots(frozenContext, query, checked, sources); return captured[0];
                    });
                    var actual = Objects.requireNonNull(captured[0]);
                    var exported = exporter.write(exportCapture, root.resolve("snapshot"));
                    entry.set("snapshotExport", exported.report());
                    if (!exported.exported()) { entry.put("reasonCode", exported.report().path("reasonCode").asText()); continue; }
                    var draft = new MissenseReferenceDraftPreparation().prepare(exported.manifestPath(), exported.manifestSha256(), root.resolve("draft"));
                    entry.set("draft", draft.report());
                    entry.set("search", search(actual));
                    if (!draft.generated()) { entry.put("reasonCode", draft.report().path("reasonCode").asText()); continue; }
                    entry.set("sharedSpliceObservations", spliceFacts(context, query, actual));
                    entry.put("status", "DRAFT_GENERATED").put("reasonCode", "REFERENCE_SCIENTIFIC_REVIEW_REQUIRED");
                    entry.putArray("remainingGates").add("EXTERNAL_REFERENCE_AND_RNA_REVIEW")
                            .add("SEPARATE_FINAL_MATERIAL_APPROVAL").add("ORIGINAL_RUNTIME_IDENTITY_AND_SPLICE_CHECKS");
                    write(root.resolve("preparation.json"), entry);
                    prepared.add(new PreparedReference(projection.snapshot(), entry, actual));
                } catch (RuntimeException | IOException invalid) {
                    String message = invalid.getMessage();
                    entry.put("reasonCode", message != null && message.matches("[A-Z0-9_]+") ? message : "REFERENCE_PREPARATION_FAILED");
                }
            }
        }
        report.set("providerCalls", session.counts()); report.put("preparedCount", prepared.size());
        write(output.resolve("preparation-report.json"), report);
        return new Batch(report, prepared);
    }

    private ObjectNode search(ClinVarConditionAwareComparatorInput.SnapshotCapture capture) {
        JsonNode input = capture.getResult();
        boolean complete = Set.of("READY", "NO_RECORD").contains(input.path("status").asText())
                && "READY".equals(input.at("/discovery/readinessStatus").asText());
        for (JsonNode decision : input.path("candidateDecisions"))
            if (!Set.of("READY", "NOT_APPLICABLE").contains(decision.path("status").asText())) complete = false;
        ObjectNode result = mapper.createObjectNode().put("scope", "NATIVE_SNV_CONTIGUOUS_CODON_ONLY")
                .put("completeWithinScope", complete).put("decidedCandidateCount", input.path("candidateDecisions").size())
                .put("referenceCount", input.path("comparators").size());
        var discovery = capture.input().getDiscovery();
        if (discovery != null && "READY".equals(discovery.getReadinessStatus())) result.put("candidateCount", discovery.candidateCount());
        else result.putNull("candidateCount");
        result.put("candidateCountSemantics", "EXACT_ALLELES_AFTER_EXISTING_DISCOVERY_DEDUPLICATION");
        result.set("codonWindow", input.path("codonWindow")); result.set("discovery", input.path("discovery"));
        result.set("candidateDecisions", input.path("candidateDecisions"));
        return result;
    }

    private ArrayNode spliceFacts(ObjectNode context, VariantInterpretation query,
                                   ClinVarConditionAwareComparatorInput.SnapshotCapture captured) {
        ArrayNode values = mapper.createArrayNode();
        Map<NormalizedVariantKey, List<String>> keys = new LinkedHashMap<>();
        keys.put(query.getObservation().getKey(), new ArrayList<>(List.of("TARGET")));
        for (var reference : captured.getReferenceSnapshots())
            keys.computeIfAbsent(reference.getExactAllele(), ignored -> new ArrayList<>()).add("REFERENCE:" + reference.getVariationId());
        keys.forEach((key, roles) -> {
            ObjectNode row = values.addObject(); row.set("roles", mapper.valueToTree(roles)); row.put("normalRnaClaimed", false);
            row.set("queryAllele", mapper.valueToTree(key));
            var source = splice;
            if (source == null) { row.put("status", "NOT_EVALUATED").put("reasonCode", "SHARED_SPLICE_SOURCE_UNAVAILABLE"); return; }
            try {
                var fact = source.apply(key, context.path("dataClass").asText());
                if (fact == null || fact.observation() == null || fact.source() == null)
                    throw new IllegalArgumentException("SHARED_SPLICE_SOURCE_UNAVAILABLE");
                ObjectNode snapshot = fact.observation().snapshot();
                var reference = mapper.convertValue(snapshot.path("sourceReference"), ExplicitContextSourceLedger.SourceRef.class);
                if (!fact.source().resolve(reference).equals(snapshot.path("sourceRecord")))
                    throw new IllegalArgumentException("SHARED_SPLICE_SOURCE_MISMATCH");
                JsonNode allele = snapshot.at("/sourceRecord/queryAllele");
                if (!key.equals(new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(),
                        allele.path("position").asInt(), allele.path("reference").asText(), allele.path("alternate").asText())))
                    throw new IllegalArgumentException("SPLICE_QUERY_IDENTITY_MISMATCH");
                row.set("observation", snapshot); row.put("status", "CAPTURED").put("applicationStatus", "NOT_EVALUATED")
                        .put("reasonCode", "CONSUMER_COMPATIBILITY_AND_EXTERNAL_RNA_REVIEW_REQUIRED");
                row.set("sourceReceipt", fact.source().receipt(mapper));
            } catch (RuntimeException invalid) {
                row.put("status", "NOT_EVALUATED").put("reasonCode", "SHARED_SPLICE_OBSERVATION_UNAVAILABLE");
            }
        });
        return values;
    }

    /** External review is pinned by the existing bridge; this step cannot supply final approval. */
    public ObjectNode continueReviewed(Path externalBridgeManifest, String sha256, Path output) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("OUTPUT_EXISTS_REFUSE_OVERWRITE");
        Files.createDirectory(output);
        ObjectNode report = mapper.createObjectNode().put("scientificAdoption", false).put("runtimeExecution", "NOT_RUN");
        var bridge = new MissenseReferenceReviewBridge().prepare(externalBridgeManifest, sha256, output.resolve("bridge"));
        report.set("reviewBridge", bridge.report());
        Path manifest = bridge.manifestPath(); String pin = bridge.manifestSha256();
        if (bridge.bridged() && manifest != null && pin != null) {
            var candidate = new MissenseMaterialPreparation().prepare(manifest, pin, output.resolve("material"));
            report.set("materialPreparation", candidate.report());
        }
        write(output.resolve("review-continuation.json"), report);
        return report;
    }

    /** The approval manifest must be separately authored and pinned outside the automatic preparation. */
    public MissenseMaterialPreparation.Result prepareApproved(Path approvedManifest, String sha256, Path output) throws IOException {
        return new MissenseMaterialPreparation().prepare(approvedManifest, sha256, output);
    }

    private void write(Path path, JsonNode value) throws IOException {
        Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }
}
