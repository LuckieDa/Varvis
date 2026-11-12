package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.index.tabix.TabixIndexCreator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import static org.junit.jupiter.api.Assertions.*;

/** Production export/prepare boundary exercised only with invented SYNTHETIC / TEST_ONLY providers. */
class MissenseReferenceSnapshotExportTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test void stopGainedNeighborIsRecordedAsInapplicableWithoutPoisoningDraftExport() throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 0, "STOP_GAINED", "7/19");
        var result = new MissenseReferenceSnapshotExport().export(f.context, f.target, f.selected, f.builder, f.sources,
                temporary.resolve("export"));
        assertExported(result);
        JsonNode built = input(result, "comparatorInput");
        assertTrue(built.path("comparators").isEmpty());
        assertEquals("NOT_APPLICABLE", built.at("/candidateDecisions/0/status").asText());
        assertEquals("COMPARATOR_MISSENSE_CONSEQUENCE_REQUIRED", built.at("/candidateDecisions/0/reasonCode").asText());
        assertTrue(new MissenseReferenceDraftPreparation().prepare(result.manifestPath(), result.manifestSha256(),
                temporary.resolve("draft")).generated());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void productionEntryExportsActualSelectedDtosAndDownstreamUnreviewedFacts(int background) throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), background, "NORMAL", "7/19");
        Path output = temporary.resolve("export"), draftOutput = temporary.resolve("draft");
        var result = new MissenseReferenceSnapshotExport().export(f.context, f.target, f.selected, f.builder, f.sources, output);
        assertExported(result);
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);
        JsonNode manifest = json(result.manifestPath());
        assertEquals(f.context, input(result, "context"));
        assertEquals(f.sources, input(result, "sourceSnapshot"));
        JsonNode target = input(result, "target"), built = input(result, "comparatorInput");
        assertEquals(built.path("status"), result.report().at("/sourceSearch/status"));
        assertEquals(built.path("reasonCode"), result.report().at("/sourceSearch/reasonCode"));
        assertEquals(built.path("discovery"), result.report().at("/sourceSearch/discovery"));
        assertEquals("EXTERNALLY_PINNED_SNAPSHOT_DECLARATIONS_NOT_LIVE_RESOURCE_VERIFICATION", result.report().path("sourceIdentitySemantics").asText());
        JsonNode reference = reference(result, 0), record = built.at("/comparators/0");
        assertEquals(serialized(f.selected), target.path("annotation"));
        assertEquals(serialized(f.actualReference), reference.path("annotation"));
        assertEquals("7/19", reference.at("/annotation/exon").asText());
        assertEquals("SELECTED_TARGET_ONLY", target.at("/annotation/impact").asText());
        assertNotEquals(serialized(f.batchTarget), target.path("annotation"));
        assertEquals(1, manifest.path("referenceAnnotations").size());
        assertTrue(f.batchAlleles.size() > 2, "target and continuity probes remain in the single original batch");
        assertEquals(f.referenceKey.canonicalId(), alleleId(reference.path("referenceAllele")));
        assertEquals(f.targetKey.canonicalId(), alleleId(reference.path("targetAllele")));
        assertEquals(String.valueOf(background + 1), reference.path("variationId").asText());
        assertEquals(f.context.path("contextId"), reference.path("contextId"));
        assertEquals(f.transcript, reference.path("transcript").asText());
        assertEquals(background == 0 ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID", record.path("proteinRelation").asText());
        assertEquals(f.assertions, record.path("submissions"));
        assertEquals(f.summaries, record.path("variantSummaryRecords"));
        assertEquals("CONFLICTING", record.path("conflictState").asText());
        assertEquals(f.sources.at("/SIDECAR/identityFingerprintSha256"), record.at("/snapshotProvenance/identityFingerprintSha256"));
        assertDigests(result);

        var prepared = new MissenseReferenceDraftPreparation().prepare(result.manifestPath(), result.manifestSha256(), draftOutput);
        assertTrue(prepared.generated(), prepared.report().toPrettyString());
        JsonNode draft = json(draftOutput.resolve("reference-audit.draft.json")), fact = draft.at("/references/0");
        assertEquals(target, draft.path("target")); assertEquals(record, fact.at("/observations/0/record"));
        assertEquals(reference.path("annotation"), fact.path("annotation"));
        assertEquals(2, fact.path("assertions").size());
        for (int i = 0; i < f.assertions.size(); i++) assertEquals(f.assertions.get(i), fact.at("/assertions/" + i + "/sourceAssertion"));
        assertEquals("2026-02-03", fact.at("/assertions/1/sourceAssertion/dateLastEvaluated").asText());
        JsonNode pairs = json(draftOutput.resolve("rna-pairs.draft.json"));
        assertEquals(reference.path("targetAllele"), pairs.at("/pairs/0/targetAllele"));
        assertEquals(reference.path("referenceAllele"), pairs.at("/pairs/0/referenceAllele"));
        assertEquals("NOT_PROVIDED", pairs.at("/pairs/0/rnaEvidenceStatus").asText());
        assertEquals("UNREVIEWED", draft.path("reviewStatus").asText());
        assertEquals(built, prepared.report().path("sourceSearch"));
        assertFalse(draft.toString().contains("REVIEWED_SUFFICIENT"));
        assertFalse(pairs.toString().contains("NO_CONTRADICTORY"));
        assertFalse(Files.exists(output.resolve("bundle.json"))); assertFalse(Files.exists(draftOutput.resolve("bundle.json")));
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);

        Fixture legacy = new Fixture(temporary.resolve("legacy"), background, "NORMAL", "7/19");
        JsonNode oldResult = mapper.valueToTree(legacy.builder.build(legacy.context, legacy.target, legacy.selected));
        assertEquals(oldResult, built, "legacy build JSON must retain the complete original contract");
        assertFalse(built.has("referenceSnapshots")); assertFalse(record.has("annotation"));
        archive("background-" + background + "-upstream", f.root);
        archive("background-" + background + "-export", output); archive("background-" + background + "-draft", draftOutput);
        receipt("background-" + background + "-checks", mapper.createObjectNode().put("discoveryCalls", f.discoveryCalls)
                .put("annotationCalls", f.annotationCalls).put("publicEvaluateCalls", 0).put("rnaCalls", 0).put("spliceAiCalls", 0)
                .put("legacyResultDeepEqual", true).put("selectedDtosDeepEqual", true).put("sourceAssertionsDeepEqual", true)
                .put("manifestSha256", result.manifestSha256()).put("draftId", prepared.report().path("draftId").asText()));
    }

    @Test void actualProviderFieldChangeChangesSnapshotAndDraftIdentity() throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 0, "NORMAL", "8/23");
        var exporter = new MissenseReferenceSnapshotExport();
        var first = exporter.export(f.context, f.target, f.selected, f.builder, f.sources, temporary.resolve("first"));
        assertExported(first);
        f.referenceExon = "9/23";
        var second = exporter.export(f.context, f.target, f.selected, f.builder, f.sources, temporary.resolve("second"));
        assertExported(second);
        assertEquals("8/23", reference(first, 0).at("/annotation/exon").asText());
        assertEquals("9/23", reference(second, 0).at("/annotation/exon").asText());
        assertEquals(input(first, "comparatorInput"), input(second, "comparatorInput"), "exon is absent from old protein summary");
        assertNotEquals(first.manifestSha256(), second.manifestSha256());
        assertNotEquals(json(first.manifestPath()).at("/referenceAnnotations/0/canonicalSha256"), json(second.manifestPath()).at("/referenceAnnotations/0/canonicalSha256"));
        var a = new MissenseReferenceDraftPreparation().prepare(first.manifestPath(), first.manifestSha256(), temporary.resolve("draft-first"));
        var b = new MissenseReferenceDraftPreparation().prepare(second.manifestPath(), second.manifestSha256(), temporary.resolve("draft-second"));
        assertTrue(a.generated()); assertTrue(b.generated()); assertNotEquals(a.report().path("draftId"), b.report().path("draftId"));
        assertEquals(2, f.discoveryCalls); assertEquals(2, f.annotationCalls);
        for (String name : List.of("first", "second", "draft-first", "draft-second")) archive("field-change-" + name, temporary.resolve(name));
        receipt("field-change", mapper.createObjectNode().put("firstExon", "8/23").put("secondExon", "9/23")
                .put("firstDraftId", a.report().path("draftId").asText()).put("secondDraftId", b.report().path("draftId").asText()));
    }

    @Test void capturesAndRepeatedWritesAreIndependentOfLaterCallerChangesAndContexts() throws Exception {
        Fixture first = new Fixture(temporary.resolve("upstream-first"), 0, "NORMAL", "11/31");
        Fixture second = new Fixture(temporary.resolve("upstream-second"), 1, "NORMAL", "12/41");
        var exporter = new MissenseReferenceSnapshotExport();
        var captured = first.builder.buildWithSnapshots(first.context, first.target, first.selected);
        var frozen = exporter.capture(captured, first.sources);
        var before = exporter.write(frozen, temporary.resolve("before")); assertExported(before);
        ((ObjectNode) captured.getResult()).put("contextId", "CALLER_MUTATION");
        ((ObjectNode) captured.getEvidenceContext()).put("contextId", "CALLER_MUTATION");
        ((ObjectNode) captured.getSelectedTargetAnnotation()).put("exon", "CALLER_MUTATION");
        ((ObjectNode) captured.getReferenceSnapshots().get(0).getAnnotation()).put("exon", "CALLER_MUTATION");
        first.context.put("contextId", "CALLER_MUTATION"); first.sources.put("purpose", "CALLER_MUTATION"); first.referenceExon = "CALLER_MUTATION";
        var other = exporter.export(second.context, second.target, second.selected, second.builder, second.sources, temporary.resolve("other"));
        var after = exporter.write(frozen, temporary.resolve("after")); assertExported(other); assertExported(after);
        assertEquals(before.manifestSha256(), after.manifestSha256());
        for (JsonNode descriptor : descriptors(json(before.manifestPath()))) {
            String name = descriptor.path("path").asText();
            assertArrayEquals(Files.readAllBytes(before.manifestPath().getParent().resolve(name)), Files.readAllBytes(after.manifestPath().getParent().resolve(name)));
        }
        assertNotEquals(input(before, "context").path("contextId"), input(other, "context").path("contextId"));
        assertEquals("11/31", reference(after, 0).at("/annotation/exon").asText());
        assertEquals("12/41", reference(other, 0).at("/annotation/exon").asText());
        assertEquals(1, first.discoveryCalls); assertEquals(1, first.annotationCalls);
        assertEquals(1, second.discoveryCalls); assertEquals(1, second.annotationCalls);
        for (String name : List.of("before", "after", "other")) archive("isolation-" + name, temporary.resolve(name));
    }

    @ParameterizedTest @ValueSource(strings = {"NO_RECORD", "DISCOVERY_FAILED", "SIDECAR_MISSING", "ANNOTATION_OMITTED", "ANNOTATION_AMBIGUOUS", "PARTIAL", "REORDERED"})
    void lawfulGapsAndSelectionRemainLiteralWithoutInventingReferences(String scenario) throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 0, scenario, "13/43");
        var result = new MissenseReferenceSnapshotExport().export(f.context, f.target, f.selected, f.builder, f.sources, temporary.resolve("export"));
        assertExported(result); JsonNode built = input(result, "comparatorInput");
        int expected = scenario.equals("PARTIAL") || scenario.equals("REORDERED") ? 1 : 0;
        assertEquals(expected, built.path("comparators").size()); assertEquals(expected, json(result.manifestPath()).path("referenceAnnotations").size());
        assertEquals(scenario.equals("NO_RECORD") ? "NO_RECORD" : expected == 1 ? "READY" : "NOT_EVALUATED", built.path("status").asText());
        var prepared = new MissenseReferenceDraftPreparation().prepare(result.manifestPath(), result.manifestSha256(), temporary.resolve("draft"));
        assertTrue(prepared.generated(), prepared.report().toPrettyString()); assertEquals(built, prepared.report().path("sourceSearch"));
        assertEquals("UNREVIEWED", prepared.report().path("reviewStatus").asText());
        assertEquals(expected, json(temporary.resolve("draft/reference-audit.draft.json")).path("references").size());
        if (scenario.equals("PARTIAL")) {
            assertEquals(2, built.path("candidateDecisions").size()); assertTrue(prepared.report().path("gaps").toString().contains("CANDIDATE_OMITTED"));
        }
        if (scenario.equals("SIDECAR_MISSING")) assertTrue(built.path("candidateDecisions").toString().contains("COMPARATOR_SIDECAR_UNAVAILABLE"));
        if (scenario.equals("ANNOTATION_AMBIGUOUS")) assertTrue(built.toString().contains("SELECTED_COMPARATOR_TRANSCRIPT_AMBIGUOUS"));
        assertEquals(1, f.discoveryCalls); assertEquals(scenario.equals("DISCOVERY_FAILED") ? 0 : 1, f.annotationCalls);
        archive("gap-" + scenario + "-export", temporary.resolve("export")); archive("gap-" + scenario + "-draft", temporary.resolve("draft"));
        receipt("gap-" + scenario, mapper.createObjectNode().put("discoveryCalls", f.discoveryCalls).put("annotationCalls", f.annotationCalls).set("sourceSearch", built));
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "BINDING", "CONTEXT", "UNBOUND", "HGVS_ALIAS", "UNKNOWN_FIELD"})
    void corruptedCaptureInternalsCannotBePublished(String fault) throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 0, "NORMAL", "17/61");
        var original = f.builder.buildWithSnapshots(f.context, f.target, f.selected);
        ObjectNode built = (ObjectNode) original.getResult();
        ObjectNode selected = (ObjectNode) original.getSelectedTargetAnnotation();
        List<ClinVarConditionAwareComparatorInput.ReferenceAnnotationSnapshot> references = original.getReferenceSnapshots();
        String reason;
        switch (fault) {
            case "MISSING" -> { references = List.of(); reason = "CAPTURE_REFERENCE_MISSING"; }
            case "BINDING" -> { ((ObjectNode) built.at("/comparators/0")).put("variationId", "999"); reason = "CAPTURE_REFERENCE_MISSING"; }
            case "CONTEXT" -> { built.put("contextId", "WRONG"); reason = "CAPTURE_CONTEXT_MISMATCH"; }
            case "UNBOUND" -> { built.putArray("comparators"); built.putArray("candidateDecisions"); built.put("status", "NO_RECORD"); reason = "CAPTURE_REFERENCE_NOT_BOUND"; }
            case "HGVS_ALIAS" -> { selected.put("hgvsc", "WRONG_ALIAS"); reason = "ANNOTATION_HGVS_ALIAS_MISMATCH"; }
            default -> { selected.put("notInAnnotationDto", "TEST_ONLY_UNKNOWN"); reason = "UNKNOWN_ANNOTATION_FIELD_REJECTED"; }
        }
        // Only this rejection test bypasses the private constructor; production callers cannot forge a capture.
        var constructor = ClinVarConditionAwareComparatorInput.SnapshotCapture.class.getDeclaredConstructor(JsonNode.class, ClinVarConditionAwareComparatorInput.Result.class, JsonNode.class, JsonNode.class, List.class);
        constructor.setAccessible(true);
        var corrupted = constructor.newInstance(original.getEvidenceContext(), original.input(), built, selected, references);
        if (fault.equals("UNKNOWN_FIELD")) {
            var failure = assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> new MissenseReferenceSnapshotExport().capture(corrupted, f.sources));
            assertTrue(failure.getMessage().contains("notInAnnotationDto"));
        } else {
            var failure = assertThrows(IllegalArgumentException.class, () -> new MissenseReferenceSnapshotExport().capture(corrupted, f.sources));
            assertEquals(reason, failure.getMessage());
        }
        receipt("capture-rejection-" + fault, mapper.createObjectNode().put("reasonCode", reason).put("manifestPublished", false)
                .put("negativeControlOnly", true).put("discoveryCalls", f.discoveryCalls).put("annotationCalls", f.annotationCalls));
    }

    @ParameterizedTest @ValueSource(strings = {"CONTEXT", "DATA_CLASS", "SOURCE_MISSING", "SIDECAR_SOURCE", "VEP_SOURCE", "FILE_LIMIT", "ASSERTION_LIMIT"})
    void invalidIdentitySourcesAndBoundsAreBlockedWithoutCompletionManifest(String fault) throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 0, fault.equals("ASSERTION_LIMIT") ? fault : "NORMAL", "14/47");
        switch (fault) {
            case "CONTEXT" -> f.context.put("contextId", "WRONG");
            case "DATA_CLASS" -> f.sources.put("dataClass", "PATIENT");
            case "SOURCE_MISSING" -> f.sources.remove("VEP");
            case "SIDECAR_SOURCE" -> ((ObjectNode) f.sources.path("SIDECAR")).put("identityFingerprintSha256", "f".repeat(64));
            case "VEP_SOURCE" -> ((ObjectNode) f.sources.path("VEP")).put("resourceVersion", "117.0");
            case "FILE_LIMIT" -> f.referenceExon = "x".repeat(4_000_001);
            default -> { }
        }
        Path out = temporary.resolve("blocked");
        var result = new MissenseReferenceSnapshotExport().export(f.context, f.target, f.selected, f.builder, f.sources, out);
        assertBlocked(result, out); assertFalse(result.report().path("reasonCode").asText().isBlank());
        if (fault.equals("ASSERTION_LIMIT")) assertTrue(result.report().path("reasonCode").asText().contains("LIMIT"), result.report().toPrettyString());
        if (fault.equals("FILE_LIMIT")) assertTrue(result.report().path("reasonCode").asText().contains("SIZE"), result.report().toPrettyString());
        if (Files.isDirectory(out)) archive("blocked-" + fault, out);
        receipt("blocked-" + fault + "-receipt", result.report());
    }

    @Test void boundedSourceMetadataDoesNotOverflowReceiptsByBeingCopiedTwice() throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 0, "NORMAL", "15/53");
        f.sources.put("externalNotes", "x".repeat(3_998_000));
        var result = new MissenseReferenceSnapshotExport().export(f.context, f.target, f.selected, f.builder, f.sources, temporary.resolve("export"));
        assertExported(result);
        assertEquals(f.sources, input(result, "sourceSnapshot"));
        assertEquals(json(result.manifestPath()).at("/sourceSnapshot/rawSha256"), result.report().at("/sourceSnapshot/rawSha256"));
        assertTrue(Files.size(result.manifestPath().getParent().resolve("snapshot-export-report.json")) < 50_000);
        assertDigests(result);
        receipt("bounded-source-metadata", mapper.createObjectNode().put("sourceBytes", Files.size(result.manifestPath().getParent().resolve("source-snapshot.json")))
                .put("reportBytes", Files.size(result.manifestPath().getParent().resolve("snapshot-export-report.json"))).put("sourcePreserved", true));
    }

    @Test void existingOutputAndUncreatableDestinationDoNotOverwriteOrCallProviders() throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 0, "NORMAL", "15/53");
        Path existing = Files.createDirectory(temporary.resolve("existing")); Files.writeString(existing.resolve("keep.txt"), "USER_CONTENT");
        var exporter = new MissenseReferenceSnapshotExport();
        var result = exporter.export(f.context, f.target, f.selected, f.builder, f.sources, existing);
        assertBlocked(result, existing); assertEquals("OUTPUT_EXISTS_REFUSE_OVERWRITE", result.report().path("reasonCode").asText());
        assertEquals("USER_CONTENT", Files.readString(existing.resolve("keep.txt"))); assertEquals(0, f.discoveryCalls); assertEquals(0, f.annotationCalls);
        Path parentFile = temporary.resolve("parent-file"); Files.writeString(parentFile, "USER_CONTENT");
        Path impossible = parentFile.resolve("export");
        var failed = exporter.export(f.context, f.target, f.selected, f.builder, f.sources, impossible);
        assertBlocked(failed, impossible); assertEquals("USER_CONTENT", Files.readString(parentFile));
        receipt("existing-directory", result.report()); receipt("uncreatable-output", failed.report());
    }

    @Test void actualFileWriteFailurePublishesOnlyBlockedReceipt() throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 0, "NORMAL", "18/67");
        Path output = temporary.resolve("write-failed");
        f.annotationHook = () -> {
            try { Files.createDirectory(output.resolve("context.json")); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        };
        var result = new MissenseReferenceSnapshotExport().export(f.context, f.target, f.selected, f.builder, f.sources, output);
        assertBlocked(result, output); assertEquals("SNAPSHOT_OUTPUT_IO_FAILURE", result.report().path("reasonCode").asText());
        assertTrue(Files.isDirectory(output.resolve("context.json")));
        assertEquals("BLOCKED", json(output.resolve("snapshot-export-report.json")).path("exportStatus").asText());
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);
        archive("actual-write-failure", output); receipt("actual-write-failure-receipt", result.report());
    }

    @Test void realJavaDraftCliConsumesExportAndRejectsCorruptedCopy() throws Exception {
        Fixture f = new Fixture(temporary.resolve("upstream"), 1, "NORMAL", "16/59");
        var exported = new MissenseReferenceSnapshotExport().export(f.context, f.target, f.selected, f.builder, f.sources, temporary.resolve("export"));
        assertExported(exported);
        Path good = temporary.resolve("cli-good"); assertEquals(0, cli(exported.manifestPath(), exported.manifestSha256(), good));
        assertEquals("UNREVIEWED", json(good.resolve("reference-audit.draft.json")).path("reviewStatus").asText());
        assertEquals("DRAFT_GENERATED", json(good.resolve("draft-report.json")).path("extractionStatus").asText());
        Path corrupt = temporary.resolve("corrupt"); copyTree(exported.manifestPath().getParent(), corrupt);
        String targetFile = json(exported.manifestPath()).at("/target/path").asText();
        Files.writeString(corrupt.resolve(targetFile), " ", StandardOpenOption.APPEND);
        Path bad = temporary.resolve("cli-bad");
        assertEquals(2, cli(corrupt.resolve(exported.manifestPath().getFileName()), exported.manifestSha256(), bad));
        assertEquals("INPUT_RAW_SHA_MISMATCH", json(bad.resolve("draft-report.json")).path("reasonCode").asText());
        assertFalse(Files.exists(bad.resolve("reference-audit.draft.json")));
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);
        for (Path path : List.of(exported.manifestPath().getParent(), good, corrupt, bad)) {
            assertFalse(Files.exists(path.resolve("bundle.json"))); archive("cli-" + path.getFileName(), path);
        }
    }

    private void assertExported(MissenseReferenceSnapshotExport.Result result) throws Exception {
        assertTrue(result.exported(), result.report().toPrettyString()); assertNotNull(result.manifestPath()); assertNotNull(result.manifestSha256());
        assertEquals("SNAPSHOT_EXPORTED", result.report().path("exportStatus").asText());
        assertEquals("NOT_RUN", result.report().path("runtimeExecution").asText()); assertFalse(result.report().path("scientificAdoption").asBoolean(true));
        assertArtifact(result.manifestPath().getParent(), "SNAPSHOT_EXPORTED");
    }
    private void assertBlocked(MissenseReferenceSnapshotExport.Result result, Path output) throws Exception {
        assertFalse(result.exported()); assertNull(result.manifestPath()); assertNull(result.manifestSha256());
        assertEquals("BLOCKED", result.report().path("exportStatus").asText()); assertFalse(Files.exists(output.resolve("manifest.json")));
        if (Files.exists(output.resolve("artifact-manifest.json"))) assertArtifact(output, "BLOCKED");
    }
    private void assertArtifact(Path output, String status) throws Exception {
        JsonNode artifacts = json(output.resolve("artifact-manifest.json"));
        assertEquals(status, artifacts.path("exportStatus").asText()); assertEquals("NOT_RUN", artifacts.path("runtimeExecution").asText());
        assertFalse(artifacts.path("scientificAdoption").asBoolean(true)); assertTrue(artifacts.path("writtenFiles").size() > 0);
        for (JsonNode descriptor : artifacts.path("writtenFiles")) {
            String name = descriptor.path("file").asText(); assertFalse(Path.of(name).isAbsolute());
            Path file = output.resolve(name); assertTrue(Files.isRegularFile(file), name);
            byte[] bytes = Files.readAllBytes(file); assertEquals(bytes.length, descriptor.path("bytes").asInt());
            assertEquals(Pm4MaterialPreparation.sha(bytes), descriptor.path("rawSha256").asText(), name);
        }
    }
    private JsonNode json(Path file) throws Exception { return mapper.readTree(file.toFile()); }
    private JsonNode serialized(Object value) throws Exception { return mapper.readTree(mapper.writeValueAsBytes(value)); }
    private JsonNode input(MissenseReferenceSnapshotExport.Result result, String role) throws Exception {
        return json(result.manifestPath().getParent().resolve(json(result.manifestPath()).path(role).path("path").asText()));
    }
    private JsonNode reference(MissenseReferenceSnapshotExport.Result result, int index) throws Exception {
        return json(result.manifestPath().getParent().resolve(json(result.manifestPath()).path("referenceAnnotations").get(index).path("path").asText()));
    }
    private List<JsonNode> descriptors(JsonNode manifest) {
        List<JsonNode> result = new ArrayList<>();
        for (String role : List.of("context", "target", "comparatorInput", "sourceSnapshot")) result.add(manifest.path(role));
        manifest.path("referenceAnnotations").forEach(result::add); return result;
    }
    private void assertDigests(MissenseReferenceSnapshotExport.Result result) throws Exception {
        assertEquals(Pm4MaterialPreparation.sha(Files.readAllBytes(result.manifestPath())), result.manifestSha256());
        for (JsonNode descriptor : descriptors(json(result.manifestPath()))) {
            Path file = result.manifestPath().getParent().resolve(descriptor.path("path").asText());
            assertEquals(Pm4MaterialPreparation.sha(Files.readAllBytes(file)), descriptor.path("rawSha256").asText());
            assertEquals(adapter.canonicalSha256(json(file)), descriptor.path("canonicalSha256").asText());
        }
    }
    private String alleleId(JsonNode allele) {
        return new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(), allele.path("position").asInt(), allele.path("reference").asText(), allele.path("alternate").asText()).canonicalId();
    }
    private int cli(Path manifest, String pin, Path output) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp", System.getProperty("java.class.path"), MissenseReferenceDraftPreparation.class.getName(), "--manifest", manifest.toString(), "--manifest-sha256", pin, "--output", output.toString());
        Path log = temporary.resolve(output.getFileName() + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertTrue(finished, "draft CLI must finish within the bounded timeout");
        int exit = process.exitValue(); receipt(output.getFileName() + "-command", mapper.valueToTree(Map.of("command", command, "exitCode", exit, "log", Files.readString(log)))); return exit;
    }
    private void archive(String name, Path from) throws Exception {
        String root = System.getProperty("varvis.missense.snapshot.artifacts"); if (root != null) copyTree(from, Path.of(root).resolve(name));
    }
    private void copyTree(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path target = to.resolve(from.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    private void receipt(String name, JsonNode value) throws Exception {
        String root = System.getProperty("varvis.missense.snapshot.artifacts"); if (root == null) return;
        Files.createDirectories(Path.of(root)); mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(root, name + ".json").toFile(), value);
    }

    /** Inputs are invented upstream facts; this fixture never assembles a normal export or reviewed material. */
    final class Fixture {
        final Path root;
        final int background;
        final String gene, transcript, protein;
        final NormalizedVariantKey targetKey, referenceKey;
        final ObjectNode context, sources;
        final ArrayNode assertions = mapper.createArrayNode(), summaries = mapper.createArrayNode();
        final VariantInterpretation target;
        final TranscriptConsequence selected, batchTarget;
        final ClinVarConditionAwareComparatorInput builder;
        final List<String> batchAlleles = new ArrayList<>();
        String referenceExon;
        Runnable annotationHook = () -> { };
        TranscriptConsequence actualReference;
        int discoveryCalls, annotationCalls;

        Fixture(Path root, int background, String scenario, String exon) throws Exception {
            this.root = root; this.background = background; this.referenceExon = exon; Files.createDirectory(root);
            String contig = background == 0 ? "11" : "13";
            gene = background == 0 ? "HBB" : "GJB2"; transcript = background == 0 ? "ENST00000647020.1" : "ENST00000382848.5"; protein = background == 0 ? "ENSP000001.1" : "ENSP000002.1";
            targetKey = new NormalizedVariantKey("GRCh38", contig, 105, "A", "C");
            referenceKey = background == 0 ? new NormalizedVariantKey("GRCh38", contig, 105, "A", "T") : new NormalizedVariantKey("GRCh38", contig, 103, "G", "A");
            try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json"))) { context = (ObjectNode) mapper.readTree(stream).path("context").deepCopy(); }
            context.putObject("gene").put("symbol", gene).put("hgncId", background == 0 ? "HGNC:4827" : "HGNC:4284");
            context.putObject("disease").put("mondoId", background == 0 ? "MONDO:0013517" : "MONDO:0019497"); context.put("moi", "AUTOSOMAL_RECESSIVE");
            int dot = transcript.lastIndexOf('.'); context.putObject("transcript").put("accession", transcript.substring(0, dot)).put("version", Integer.parseInt(transcript.substring(dot + 1))).put("annotationSourceVersion", "116.0");
            ObjectNode identity = (ObjectNode) context.path("variant");
            for (String role : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) identity.set(role, allele(targetKey));
            identity.put("alleleId", adapter.canonicalAlleleId(allele(targetKey), identity.path("normalization"))); context.put("contextId", adapter.canonicalContextId(context));
            sources = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY");
            sources.set("CLINVAR_VCF", source("TEST_ONLY_VCF", "synthetic-1", 'a')); sources.set("SIDECAR", source("TEST_ONLY_SIDECAR", "synthetic-1", 'b')); sources.set("VEP", source("TEST_ONLY_VEP", "116.0", 'c'));
            selected = dto(annotation(false).put("impact", "SELECTED_TARGET_ONLY").put("exon", "2/17"));
            batchTarget = dto(annotation(false).put("impact", "BATCH_TARGET_ONLY").put("exon", "3/17"));
            target = new VariantInterpretation(targetKey.canonicalId(), new VariantQcObservation(targetKey, null, null, null, null, null, null, "PASS", "SNV", null), List.of(), List.of());
            Path vcf = root.resolve("synthetic.vcf.gz"); Files.write(vcf, new byte[]{1}); Files.write(Path.of(vcf + ".tbi"), new byte[]{1});
            var config = new VariantAnalysisProperties(); config.getClinVar().setEnabled(true); config.getClinVar().setGrch38File(vcf.toString());
            var clinvar = new ClinVarTabixProvider(config, null, (file, region) -> {
                discoveryCalls++;
                if (scenario.equals("NO_RECORD")) return List.of();
                if (scenario.equals("DISCOVERY_FAILED")) throw new java.io.IOException("SYNTHETIC query failure");
                String row = contig + "\t" + referenceKey.getPosition() + "\t" + (background + 1) + "\t" + referenceKey.getReference() + "\t" + referenceKey.getAlternate() + "\t.\tPASS\tCLNSIGSCV=SCV000000001.1|SCV000000002.2";
                return scenario.equals("PARTIAL") ? List.of(contig + "\t105\t9\tA\tG\t.\tPASS\tCLNSIGSCV=SCV9.1", row) : List.of(row);
            });
            var vep = new VepTranscriptAnnotationProvider(new VariantAnalysisProperties()) {
                @Override public Map<NormalizedVariantKey, List<TranscriptConsequence>> annotateBatch(List<VariantInterpretation> variants, AnalysisContext analysis) {
                    annotationCalls++; annotationHook.run(); Map<NormalizedVariantKey, List<TranscriptConsequence>> result = new LinkedHashMap<>();
                    List<VariantInterpretation> ordered = new ArrayList<>(variants); if (scenario.equals("REORDERED")) Collections.reverse(ordered);
                    for (VariantInterpretation variant : ordered) {
                        NormalizedVariantKey key = variant.getObservation().getKey(); batchAlleles.add(key.canonicalId());
                        if (scenario.equals("ANNOTATION_OMITTED") && key.equals(referenceKey)) continue;
                        if (scenario.equals("PARTIAL") && key.getAlternate().equals("G")) continue;
                        if (key.equals(referenceKey)) {
                            actualReference = dto(annotation(true).put("exon", referenceExon));
                            if (scenario.equals("STOP_GAINED")) actualReference = dto(annotation(true)
                                    .put("exon", referenceExon).put("consequence", "stop_gained"));
                            TranscriptConsequence unrelated = dto(annotation(true).put("transcriptAccession", "ENST99999999999.7").put("hgvsCoding", "ENST99999999999.7:c.6A>T").put("exon", "NOT_SELECTED"));
                            result.put(key, scenario.equals("ANNOTATION_AMBIGUOUS") ? List.of(actualReference, actualReference) : scenario.equals("REORDERED") ? List.of(actualReference, unrelated) : List.of(unrelated, actualReference));
                        } else result.put(key, List.of(batchTarget));
                    }
                    return result;
                }
            };
            int count = scenario.equals("ASSERTION_LIMIT") ? 1025 : 2;
            for (int i = 1; i <= count; i++) {
                ObjectNode scv = assertions.addObject().put("scv", i == 1 ? "SCV000000001.1" : "SCV000000002.2").put("classification", i == 1 ? "Pathogenic" : "Uncertain significance")
                        .put("dateLastEvaluated", i == 1 ? "2026-01-02" : "2026-02-03").put("reviewStatus", "criteria provided, single submitter");
                scv.putObject("condition").put("name", "TEST_ONLY condition " + i).put("submittedRaw", "SYNTHETIC raw condition " + i);
                scv.putObject("sourceLocator").put("logicalName", "SYNTHETIC-scv.xml").put("record", i);
            }
            for (int i = 1; i <= 2; i++) summaries.addObject().put("rcv", "RCV00000000" + i + ".1").put("classification", "Conflicting classifications of pathogenicity").putObject("condition").put("name", "TEST_ONLY condition " + i);
            Path sidecarPath = root.resolve("synthetic-sidecar.tsv.gz"); var index = new TabixIndexCreator(new TabixFormat(0, 1, 2, 3, '#', 0));
            try (var stream = new BlockCompressedOutputStream(sidecarPath.toFile())) {
                int id = background + 1;
                index.addFeature(new htsjdk.tribble.Feature() { public String getContig() { return "VID"; } public int getStart() { return id; } public int getEnd() { return id; } }, stream.getFilePointer());
                String row = "VID\t" + id + "\t" + id + "\t" + id + "\tGRCh38\t" + contig + "\t" + referenceKey.getPosition() + "\t" + referenceKey.getReference() + "\t" + referenceKey.getAlternate() + "\t[\"" + gene + "\"]\tCONFLICTING\t" + summaries + "\t" + assertions + "\n";
                stream.write(row.getBytes(StandardCharsets.UTF_8)); index.finalizeIndex(stream.getFilePointer()).writeBasedOnFeaturePath(sidecarPath);
            }
            var sidecar = new ClinVarComparatorSidecar(mapper, scenario.equals("SIDECAR_MISSING") ? root.resolve("absent.tsv.gz") : sidecarPath, "TEST_ONLY_SIDECAR", "synthetic-1", "b".repeat(64));
            builder = new ClinVarConditionAwareComparatorInput(clinvar, vep, sidecar, new VepProteinRelationClassifier());
            mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("synthetic-context.json").toFile(), context);
            mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("synthetic-source-declarations.json").toFile(), sources);
        }
        private TranscriptConsequence dto(ObjectNode value) {
            try { return Objects.requireNonNull(mapper.treeToValue(value, TranscriptConsequence.class)); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        private ObjectNode annotation(boolean reference) {
            boolean different = reference && background == 1;
            return mapper.createObjectNode().put("geneSymbol", gene).put("transcriptAccession", transcript)
                    .put("hgvsCoding", transcript + (different ? ":c.4G>A" : reference ? ":c.6A>T" : ":c.6A>C"))
                    .put("hgvsProtein", protein + (different ? ":p.Glu2Lys" : ":p.Glu2Asp")).put("proteinPosition", "2").put("aminoAcids", different ? "E/K" : "E/D")
                    .put("codons", different ? "Gaa/Aaa" : reference ? "gaA/gaT" : "gaA/gaC").put("consequence", "missense_variant")
                    .put("status", "COMPLETED").put("source", "VEP").put("version", "116.0").put("timestamp", "2026-09-26T00:00:00Z");
        }
        private ObjectNode source(String id, String version, char pin) { return mapper.createObjectNode().put("resourceId", id).put("resourceVersion", version).put("identityFingerprintSha256", String.valueOf(pin).repeat(64)); }
        private ObjectNode allele(NormalizedVariantKey key) { return mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome()).put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate()); }
    }

}
