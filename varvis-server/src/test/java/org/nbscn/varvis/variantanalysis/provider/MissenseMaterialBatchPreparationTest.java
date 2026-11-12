package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class MissenseMaterialBatchPreparationTest {
    @TempDir Path temp;
    final ObjectMapper mapper = MissenseMaterialIndex.strictMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    ObjectNode manifest() { return mapper.createObjectNode().put("schemaVersion", "missense-material-batch-manifest-v1"); }
    ObjectNode descriptor(Path file) throws Exception {
        return mapper.createObjectNode().put("path", file.toAbsolutePath().toString())
                .put("rawSha256", Pm4MaterialPreparation.sha(Files.readAllBytes(file)));
    }
    MissensePreparationFixture fixture(String name, boolean pm5, boolean approved) throws Exception {
        var f = new MissensePreparationFixture(temp.resolve(name), new Ps1TestCatalog(mapper), pm5);
        if (approved) f.release("original"); else f.save(); return f;
    }
    MissenseMaterialBatchPreparation.Result run(ObjectNode manifest, String name) throws Exception {
        Path input = temp.resolve(name + ".json"); mapper.writeValue(input.toFile(), manifest);
        return new MissenseMaterialBatchPreparation().prepare(input, Pm4MaterialPreparation.sha(Files.readAllBytes(input)), temp.resolve(name));
    }
    MissenseMaterialIndex index(String name) throws Exception {
        Path path = temp.resolve(name + "/qualified-index.json");
        return MissenseMaterialIndex.load(path, Pm4MaterialPreparation.sha(Files.readAllBytes(path)));
    }
    @Test void mixedBatchKeepsQualifiedBundlesAndSpecificBlocksWithoutSigning() throws Exception {
        var ps1 = fixture("ps1", false, true); var pm5 = fixture("pm5", true, true); var pending = fixture("pending", false, false);
        var manifest = manifest(); var entries = manifest.putArray("entries");
        for (var f : List.of(ps1, pm5, pending)) entries.addObject().put("entryId", f.root.getFileName().toString())
                .set("preparationManifest", descriptor(f.manifestPath));
        ObjectNode before = ps1.manifest.deepCopy(); var result = run(manifest, "mixed");
        assertFalse(result.complete()); assertEquals("PARTIAL", result.report().path("status").asText());
        assertEquals(2, result.report().path("preparedCount").asInt());
        assertEquals("EXTERNAL_APPROVAL_MISSING", result.report().at("/entries/2/reasonCode").asText());
        assertFalse(Files.exists(temp.resolve("mixed/pending/prepared/bundle.json")));
        assertFalse(result.report().path("scientificAdoption").asBoolean());
        assertEquals(before, mapper.readTree(ps1.manifestPath.toFile()));
        assertEquals(2, index("mixed").identity().path("entries").size());
        for (var f : List.of(ps1, pm5)) assertEquals(mapper.readTree(f.root.resolve("original/bundle.json").toFile()),
                mapper.readTree(temp.resolve("mixed/" + f.root.getFileName() + "/prepared/bundle.json").toFile()));
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    @SuppressWarnings("unchecked")
    void qualifiedBatchFeedsOriginalCoreForBothBackgroundsAndCannotBorrowDiseaseOrUse(boolean pm5) throws Exception {
        var f = fixture("source", pm5, true); var manifest = manifest();
        manifest.putArray("entries").addObject().put("entryId", "retained").set("preparationManifest", descriptor(f.manifestPath));
        assertTrue(run(manifest, "batch").complete()); var index = index("batch");
        var file = temp.resolve("batch/retained/prepared/bundle.json");
        var old = f.fixture.loadPrepared(Files.createDirectory(temp.resolve("execution")), file, adapter.canonicalSha256(mapper.readTree(file.toFile())));
        Supplier<MissenseComparisonRuntime> runtime = (Supplier<MissenseComparisonRuntime>) ReflectionTestUtils.getField(requireNonNull(old), "missense");
        assertNotNull(runtime); String criterion = pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1;
        for (int i = 0; i < 2; i++) {
            var target = new MissenseReferenceSnapshotExportTest().new Fixture(temp.resolve("facts" + i), i, "NORMAL", "1/1");
            ((ObjectNode) target.context.path("disease")).put("label", "TEST_ONLY invented disease");
            ((ObjectNode) target.context.path("transcript")).put("selectionPolicy", "SYNTHETIC_PINNED_TRANSCRIPT");
            target.context.put("contextId", adapter.canonicalContextId(target.context));
            var annotation = f.fixture.annotation(i, false);
            target.target.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation,
                    org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
            assertNull(index.useIssue(criterion, target.context));
            var bundle = index.bind(criterion, target.context, annotation, runtime, mapper.createObjectNode().put("testOnly", true));
            assertEquals("retained", bundle.identity().at("/referenceMaterialSelection/selectedEntryId").asText());
            assertEquals(index.identity().path("indexRawSha256"), bundle.identity().at("/referenceMaterialSelection/indexRawSha256"));
            var result = bundle.evaluate(criterion, target.context, target.target);
            assertEquals("MET", result.at("/criterionEvaluations/0/evaluationResult/status").asText(), result.toPrettyString());
            int calls = f.fixture.spliceCalls + f.fixture.discoveryCalls + f.fixture.annotationCalls;
            var other = target.context.deepCopy(); ((ObjectNode) other.path("disease")).put("mondoId", "MONDO:9999999");
            assertEquals("REFERENCE_MATERIAL_BACKGROUND_NOT_FOUND", index.useIssue(criterion, other));
            final var wrongDisease = other;
            assertEquals("REFERENCE_MATERIAL_BACKGROUND_NOT_FOUND", assertThrows(IllegalArgumentException.class,
                    () -> index.bind(criterion, wrongDisease, annotation, runtime, mapper.createObjectNode())).getMessage());
            other = target.context.deepCopy(); other.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
            assertEquals("MISSENSE_CONTEXT_USE_NOT_APPROVED", index.useIssue(criterion, other));
            final var patient = other;
            assertEquals("MISSENSE_CONTEXT_USE_NOT_APPROVED", assertThrows(IllegalArgumentException.class,
                    () -> index.bind(criterion, patient, annotation, runtime, mapper.createObjectNode())).getMessage());
            assertEquals(calls, f.fixture.spliceCalls + f.fixture.discoveryCalls + f.fixture.annotationCalls);
        }
    }
    @Test void reviewBridgeStillRunsBeforeOriginalPreparerAndKeepsUnadoptedReasons() throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("bridge-input"), 0);
        var library = MissenseReviewedReuseFixture.library(f.mapper, f.declaration, f.selectedReference, f.selectedAssertion, f.selectedPair);
        MissenseReviewedReuseFixture.sourceBindings(library, f.manifest);
        f.manifest.remove("review"); f.manifest.set("reusableReview", f.file("library.json", library));
        f.mapper.writeValue(f.manifestPath.toFile(), f.manifest);
        var manifest = manifest(); manifest.putArray("entries").addObject().put("entryId", "reuse").set("bridgeManifest", descriptor(f.manifestPath));
        var result = run(manifest, "bridge-batch");
        assertEquals(1, result.report().at("/entries/0/bridge/reviewReuse/matchedAssertions").asInt());
        assertEquals("EXTERNAL_APPROVAL_MISSING", result.report().at("/entries/0/reasonCode").asText());
        assertTrue(index("bridge-batch").identity().path("entries").isEmpty());
        assertTrue(Files.exists(temp.resolve("bridge-batch/reuse/bridge/reusable-review-library.json")));
    }
    @ParameterizedTest @ValueSource(strings = {"raw", "canonical", "criterion", "qualification", "duplicate", "json"})
    void indexRejectsUntrustedOrAmbiguousInputs(String fault) throws Exception {
        var f = fixture("source", false, true); var manifest = manifest();
        manifest.putArray("entries").addObject().put("entryId", "retained").set("preparationManifest", descriptor(f.manifestPath));
        run(manifest, "batch"); Path path = temp.resolve("batch/qualified-index.json");
        var index = (ObjectNode) mapper.readTree(path.toFile()); var row = (ObjectNode) index.at("/entries/0");
        switch (fault) {
            case "raw" -> Files.writeString(temp.resolve("batch/retained/prepared/bundle.json"), " ", StandardOpenOption.APPEND);
            case "canonical" -> row.put("canonicalSha256", "a".repeat(64));
            case "criterion" -> row.put("criterion", MissenseComparisonRuntime.PM5);
            case "duplicate" -> index.withArray("entries").add(row.deepCopy().put("entryId", "duplicate"));
            case "qualification" -> {
                Path bundle = temp.resolve("batch/retained/prepared/bundle.json"); var doc = (ObjectNode) mapper.readTree(bundle.toFile());
                doc.remove("materialQualification"); mapper.writeValue(bundle.toFile(), doc);
                row.put("rawSha256", Pm4MaterialPreparation.sha(Files.readAllBytes(bundle))).put("canonicalSha256", adapter.canonicalSha256(doc));
            }
            default -> { }
        }
        mapper.writeValue(path.toFile(), index);
        if ("json".equals(fault)) Files.writeString(path, "{\"schemaVersion\":\"missense-material-index-v1\",\"entries\":[],\"entries\":[]}");
        String pin = Pm4MaterialPreparation.sha(Files.readAllBytes(path));
        assertThrows(Exception.class, () -> MissenseMaterialIndex.load(path, pin));
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void batchCanCarryOnlyAnExactSeparatelyAuthoredFinalApproval(boolean wrongCandidate) throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("bridge-input"), 0);
        var prior = f.bridge(temp.resolve("prior-bridge")); assertTrue(prior.bridged());
        var candidate = new MissenseMaterialPreparation().prepare(prior.manifestPath(),prior.manifestSha256(),temp.resolve("prior-candidate"));
        assertEquals("EXTERNAL_APPROVAL_MISSING",candidate.report().path("reasonCode").asText());
        var approval = mapper.createObjectNode().put("schemaVersion","missense-preparation-approval-v1")
                .put("status","REVIEWED_IMPLEMENTED").put("reviewId","TEST_ONLY_SEPARATE_AUTHOR")
                .put("dataClass",candidate.report().path("dataClass").asText()).put("criterion",candidate.report().path("criterion").asText())
                .put("candidateSha256",wrongCandidate ? "a".repeat(64) : candidate.report().path("candidateSha256").asText());
        approval.set("backgrounds",candidate.report().path("backgrounds"));
        Path path = temp.resolve("external-approval.json"); mapper.writeValue(path.toFile(),approval);
        var manifest = manifest(); var row = manifest.putArray("entries").addObject().put("entryId","bridged");
        row.set("bridgeManifest",descriptor(f.manifestPath)); row.set("approval",descriptor(path).put("canonicalSha256",adapter.canonicalSha256(approval)));
        byte[] original = Files.readAllBytes(path); var result = run(manifest,"approved-bridge");
        assertEquals(!wrongCandidate,result.complete(),result.report().toPrettyString());
        if (wrongCandidate) assertEquals("EXTERNAL_APPROVAL_BINDING_MISMATCH",result.report().at("/entries/0/reasonCode").asText());
        else assertEquals(1,index("approved-bridge").identity().path("entries").size());
        assertArrayEquals(original,Files.readAllBytes(path));
    }
    @Test void overlappingReviewedRevisionsRefuseBeforeAnyEvaluatorRuns() throws Exception {
        var a = fixture("first", false, true); var b = fixture("second", false, false);
        Path header = b.root.resolve(b.manifest.at("/bundleHeader/path").asText()); var changed = (ObjectNode) mapper.readTree(header.toFile());
        changed.put("bundleVersion", "2"); b.manifest.set("bundleHeader", b.file("header.json", changed)); b.release("other");
        var manifest = manifest(); var entries = manifest.putArray("entries");
        for (var f : List.of(a,b)) entries.addObject().put("entryId", f.root.getFileName().toString()).set("preparationManifest", descriptor(f.manifestPath));
        run(manifest, "ambiguous"); var index = index("ambiguous");
        var target = new MissenseReferenceSnapshotExportTest().new Fixture(temp.resolve("facts"), 0, "NORMAL", "1/1");
        assertEquals("REFERENCE_MATERIAL_SELECTION_AMBIGUOUS", assertThrows(IllegalArgumentException.class,
                () -> index.bind(MissenseComparisonRuntime.PS1, target.context, a.fixture.annotation(0,false),
                        () -> { fail("No runtime on ambiguous materials"); return null; }, mapper.createObjectNode())).getMessage());
    }
    @Test void wrongInputPinDoesNotSuppressOtherRowsAndExistingOutputIsNeverOverwritten() throws Exception {
        var f = fixture("source", false, true); var manifest = manifest(); var entries = manifest.putArray("entries");
        entries.addObject().put("entryId", "bad").set("preparationManifest", descriptor(f.manifestPath).put("rawSha256", "a".repeat(64)));
        entries.addObject().put("entryId", "good").set("preparationManifest", descriptor(f.manifestPath));
        var result = run(manifest, "mixed"); assertEquals(1, result.report().path("preparedCount").asInt());
        assertTrue(result.report().at("/entries/0/reasonCode").asText().contains("SHA"));
        byte[] original = Files.readAllBytes(temp.resolve("mixed/batch-report.json"));
        assertThrows(IllegalArgumentException.class, () -> run(manifest, "mixed"));
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("mixed/batch-report.json")));
    }
}
