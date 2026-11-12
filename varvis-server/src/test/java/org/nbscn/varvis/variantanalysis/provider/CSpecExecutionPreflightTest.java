package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner.ContextSeed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Every context, source receipt and review actor here is synthetic, never a clinical assertion. */
class CSpecExecutionPreflightTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private CSpecExecutionPreflight preflight;
    private ObjectNode registry;
    private ObjectNode context;
    private byte[] ruleBytes;
    private byte[] registryBytes;
    private byte[] profileBytes;

    @BeforeEach
    void setup() throws Exception {
        ruleBytes = resource("/acmg/rule-registry-v2/acmg-rule-registry-e3-c1-2026-09-02.json");
        registryBytes = resource("/acmg/cspec-overlay-registry-v1/cspec-overlay-registry-e4-a2a-2026-09-05.json");
        profileBytes = resource("/acmg/alphamissense-pp3-bp4-supporting-profile-v1.json");
        registry = (ObjectNode) mapper.readTree(registryBytes);
        context = (ObjectNode) mapper.readTree(resource("/e2/runtime-evidence-v2-fixture.json")).path("context");
        context.withObjectProperty("gene").put("hgncId", "HGNC:1748").put("symbol", "CDH1");
        context.withObjectProperty("disease").put("mondoId", "MONDO:0007648").put("label", "Synthetic GN007 context");
        context.withObjectProperty("transcript").put("accession", "NM_004360").put("version", 5);
        reidentify(context);
        preflight = new CSpecExecutionPreflight(mapper);
    }

    @Test
    void realMetadataHitBlocksImmediatelyWithoutInventingReviewAndBindsExistingSlice() {
        Attempt original = attempt(context);
        var batch = preflight.check(batch(original));
        var result = batch.candidates().get(0).attempts().get(0);
        assertEquals("SINGLE_NON_SELECTABLE_MATCH", result.outcome());
        assertEquals(List.of("cspec-overlay-gn007-3.1.0"), result.candidateOverlayIds());
        blocked(result, "CSPEC_MATCH_NOT_EXECUTABLE");
        blocked(result, "CSPEC_REVIEW_RECORDS_REQUIRED");
        assertNull(result.selection());
        assertSame(original, result.original());
        assertEquals("REQUIRED", result.constraints().path("reviewConstraint").asText());
        assertEquals("FORCE_NOT_EVALUATED", result.constraints().path("criterionEvaluationConstraint").asText());
        assertEquals("ABSTAIN", result.constraints().path("combinationDisposition").asText());
        assertTrue(result.selectionId().matches("cspec-selection-sha256-[0-9a-f]{64}"));
        JsonNode pair = batch.registryPairing();
        assertEquals("acmg-rule-registry-e3-c1-2026-09-02", pair.path("ruleRegistryId").asText());
        assertEquals("2026-08-31T11:20:00Z", pair.path("asOf").asText());
        assertEquals("2026-09-02T00:00:00Z", pair.at("/executionSlice/profileAsOf").asText());
        assertEquals("1.0.0-e3-c1", pair.at("/executionSlice/evaluatorVersion").asText());
        assertEquals("SUPPORTING", pair.at("/executionSlice/strengthCap").asText());
        assertEquals(mapper.valueToTree(List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4")), pair.at("/executionSlice/criterionIds"));
    }

    @Test
    void everyPartialDimensionAndRequiredTranscriptMismatchIsOnlyZeroMatch() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.withObjectProperty("gene").put("hgncId", "HGNC:1"),
                node -> node.withObjectProperty("disease").put("mondoId", "MONDO:0000001"),
                node -> node.put("moi", "AUTOSOMAL_RECESSIVE"),
                node -> node.withObjectProperty("transcript").put("accession", "NM_000001"),
                node -> node.withObjectProperty("transcript").put("version", 4))) {
            ObjectNode partial = context.deepCopy();
            mutation.accept(partial);
            reidentify(partial);
            var result = checked(preflight, attempt(partial), List.of(), List.of());
            assertEquals("ZERO_MATCH", result.outcome());
            assertTrue(result.blockers().isEmpty());
            assertTrue(result.candidateOverlayIds().isEmpty());
            assertFalse(result.selection().has("reasonCode"));
        }
    }

    @Test
    void explicitReviewCanCompleteFormalMetadataSelectionButNeverEnableExecution() {
        Attempt original = attempt(context);
        var pending = checked(preflight, original, List.of(), List.of());
        ObjectNode review = review(pending);
        var complete = checked(preflight, original, List.of(review), List.of());
        assertNotNull(complete.selection());
        assertEquals(pending.selectionId(), complete.selectionId());
        assertEquals(pending.selectionId(), preflight.selectionIdentity(complete.selection()));
        assertEquals(review.path("reviewRecordId"), complete.selection().at("/review/reviewRecordIds/0"));
        assertEquals("NONE", complete.selection().at("/conflict/status").asText());
        assertEquals("FORCE_NOT_EVALUATED", complete.constraints().path("criterionEvaluationConstraint").asText());
        assertEquals(1, complete.blockers().size());
        blocked(complete, "CSPEC_MATCH_NOT_EXECUTABLE");
        assertFalse(complete.selection().has("selectedOverlayId"));
    }

    @Test
    void suppliedReviewIdentitySubjectAndContextMustReferToActualSelection() {
        Attempt original = attempt(context);
        var pending = checked(preflight, original, List.of(), List.of());
        for (String field : List.of("contextId", "subjectId", "reviewRecordId")) {
            ObjectNode invalid = review(pending);
            invalid.put(field, (field.equals("contextId") ? "context" : field.equals("subjectId") ? "cspec-selection" : "review")
                    + "-sha256-" + "0".repeat(64));
            var result = checked(preflight, original, List.of(invalid), List.of());
            assertNull(result.selection(), field);
            blocked(result, "CSPEC_MATCH_NOT_EXECUTABLE");
            assertEquals("FORCE_NOT_EVALUATED", result.constraints().path("criterionEvaluationConstraint").asText());
        }
        ObjectNode forged = checked(preflight, original, List.of(review(pending)), List.of()).selection();
        forged.put("selectionId", "cspec-selection-sha256-" + "0".repeat(64));
        assertNotEquals(forged.path("selectionId").asText(), preflight.selectionIdentity(forged));
    }

    @Test
    void syntheticNonRequiredTranscriptAndSelectableBranchKeepVersionBoundIdentity() throws Exception {
        ObjectNode synthetic = syntheticRegistry(false);
        ObjectNode overlay = (ObjectNode) synthetic.path("overlays").get(0);
        overlay.put("transcriptRequired", false);
        ((ObjectNode) overlay.path("selector")).remove("transcript");
        ObjectNode differentTranscript = context.deepCopy();
        differentTranscript.withObjectProperty("transcript").put("accession", "NM_000001").put("version", 8);
        reidentify(differentTranscript);
        var result = select(attempt(differentTranscript), synthetic, List.of(), List.of());
        assertEquals("SINGLE_SELECTABLE_MATCH", result.outcome());
        assertTrue(result.blockers().isEmpty());
        assertEquals(overlay.path("overlayId"), result.selection().path("selectedOverlayId"));
        assertEquals(overlay.path("snapshotSha256"), result.selection().path("selectedOverlaySha256"));
        assertEquals("EVALUATE_WITH_SELECTED_OVERLAY", result.constraints().path("criterionEvaluationConstraint").asText());
        assertEquals(result.selectionId(), preflight.selectionIdentity(result.selection()));
    }

    @Test
    void syntheticMultipleMatchesNeedBothReferenceTypesAndRetainEveryExactCandidate() throws Exception {
        ObjectNode synthetic = syntheticRegistry(true);
        Attempt original = attempt(context);
        var pending = select(original, synthetic, List.of(), List.of());
        assertEquals("MULTIPLE_EXACT_MATCHES", pending.outcome());
        assertEquals(List.of("cspec-overlay-synthetic-e4-a2a-z", "cspec-overlay-synthetic-e4-a2a-a"), pending.candidateOverlayIds());
        assertNull(pending.selection());
        blocked(pending, "CSPEC_MULTIPLE_EXACT_MATCHES");
        ObjectNode review = review(pending);
        var noConflict = select(original, synthetic, List.of(review), List.of());
        assertNull(noConflict.selection());
        blocked(noConflict, "CSPEC_CONFLICT_RECORDS_REQUIRED");
        ObjectNode conflict = conflict(pending);
        var complete = select(original, synthetic, List.of(review), List.of(conflict));
        assertNotNull(complete.selection());
        assertEquals(pending.selectionId(), complete.selectionId());
        assertEquals(conflict.path("conflictId"), complete.selection().at("/conflict/conflictIds/0"));
        assertEquals("PRESENT", complete.selection().at("/conflict/status").asText());
        assertFalse(complete.selection().has("selectedOverlayId"));
        assertEquals("FORCE_NOT_EVALUATED", complete.constraints().path("criterionEvaluationConstraint").asText());
        ObjectNode projectionOrder = complete.selection();
        projectionOrder.putArray("candidateOverlayIds").add(pending.candidateOverlayIds().get(1)).add(pending.candidateOverlayIds().get(0));
        assertEquals(pending.selectionId(), preflight.selectionIdentity(projectionOrder));
        conflict.put("conflictId", "conflict-sha256-" + "0".repeat(64));
        assertNull(select(original, synthetic, List.of(review), List.of(conflict)).selection());
    }

    @Test
    void registryPinsRejectRelabelingWrongHashesDatesAuthoritiesProfilesAndMalformedResources() throws Exception {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("registryId", "cspec-overlay-registry-e3-l1a-2026-09-05"),
                node -> node.put("baseRuleRegistryId", "acmg-rule-registry-e3-l1a-2026-09-05"),
                node -> node.put("baseRuleRegistrySha256", "0".repeat(64)),
                node -> node.put("authorityCatalogSha256", "0".repeat(64)),
                node -> node.put("authorityCatalogId", "authority-catalog-synthetic-invalid"),
                node -> node.put("asOf", "2026-09-05T00:00:00Z"))) {
            ObjectNode wrong = registry.deepCopy();
            mutation.accept(wrong);
            resourceBlocked(ruleBytes, mapper.writeValueAsBytes(wrong), profileBytes);
        }
        resourceBlocked(ruleBytes, (new String(registryBytes, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8), profileBytes);
        ObjectNode wrongRule = (ObjectNode) mapper.readTree(ruleBytes);
        wrongRule.put("asOf", "2026-09-05T00:00:00Z");
        resourceBlocked(mapper.writeValueAsBytes(wrongRule), registryBytes, profileBytes);
        wrongRule.put("asOf", "2026-08-31T11:20:00Z").put("registryId", "acmg-rule-registry-synthetic");
        resourceBlocked(mapper.writeValueAsBytes(wrongRule), registryBytes, profileBytes);
        ObjectNode wrongProfile = (ObjectNode) mapper.readTree(profileBytes);
        wrongProfile.withObjectProperty("registryBinding").put("registrySha256", "0".repeat(64));
        resourceBlocked(ruleBytes, registryBytes, mapper.writeValueAsBytes(wrongProfile));
        resourceBlocked(new byte[0], registryBytes, profileBytes);
        resourceBlocked(ruleBytes, "[]".getBytes(StandardCharsets.UTF_8), profileBytes);
    }

    @Test
    void invalidContextOrOuterNativeLineageCannotBecomeFormalSelection() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.withObjectProperty("transcript").remove("version"),
                node -> node.put("contextId", "context-sha256-" + "0".repeat(64)),
                node -> node.withObjectProperty("variant").put("alleleId", "allele-sha256-" + "0".repeat(64)))) {
            ObjectNode invalid = context.deepCopy();
            mutation.accept(invalid);
            assertNull(checked(preflight, attempt(invalid), List.of(), List.of()).selection());
        }
        Attempt original = attempt(context);
        Provenance before = original.provenance();
        var lineage = before.assemblyLineage();
        Provenance changed = new Provenance(before.dataClass(), before.transcript(), before.patient(), before.normalization(), before.reference(),
                new AssemblyLineage("GRCh37", "MAPPED", lineage.originalInputAllele(), "synthetic-liftover", lineage.source()));
        Attempt invalid = new Attempt(original.candidateId(), original.hypothesisId(), original.binding(), changed,
                original.seed(), original.context(), List.of());
        var result = checked(preflight, invalid, List.of(), List.of());
        assertNull(result.selection());
        assertEquals(changed, result.original().provenance());
        blocked(result, "NATIVE_GRCH38_REQUIRED");
    }

    @Test
    void javaFormalSelectionsExportWithTheirActualProductionOrSyntheticRegistryIdentity() throws Exception {
        ArrayNode rows = mapper.createArrayNode();
        Attempt original = attempt(context);
        var pending = checked(preflight, original, List.of(), List.of());
        rows.add(exported(checked(preflight, original, List.of(review(pending)), List.of()), null));
        ObjectNode selectable = syntheticRegistry(false);
        rows.add(exported(select(original, selectable, List.of(), List.of()), selectable));
        ObjectNode multiple = syntheticRegistry(true);
        var ambiguous = select(original, multiple, List.of(), List.of());
        rows.add(exported(select(original, multiple, List.of(review(ambiguous)), List.of(conflict(ambiguous))), multiple));
        String output = System.getProperty("varvis.e4a2a.selectionOutput");
        if (output != null) {
            Path path = Path.of(output);
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows), StandardCharsets.UTF_8);
        }
        assertEquals(3, rows.size());
    }

    private ObjectNode syntheticRegistry(boolean multiple) {
        ObjectNode synthetic = registry.deepCopy();
        synthetic.put("registryId", "cspec-overlay-registry-synthetic-e4-a2a-" + (multiple ? "multiple" : "selectable"));
        ObjectNode first = (ObjectNode) synthetic.path("overlays").get(0);
        first.put("overlayId", "cspec-overlay-synthetic-e4-a2a-z");
        if (multiple) {
            synthetic.withArray("overlays").add(first.deepCopy().put("overlayId", "cspec-overlay-synthetic-e4-a2a-a"));
        } else {
            first.put("implementationStatus", "IMPLEMENTED").put("selectionStatus", "SELECTABLE");
            ObjectNode spec = first.putArray("criterionSpecifications").addObject();
            spec.put("criterionId", "ACMG_AMP_2015:PP3").put("action", "USE_BASE").put("designation", "PP3").put("strength", "SUPPORTING");
            spec.set("authoritySourceIds", first.path("authoritySourceIds").deepCopy());
            spec.set("sourceLocators", first.path("sourceLocators").deepCopy());
        }
        return synthetic;
    }

    private Attempt attempt(ObjectNode value) {
        var binding = new DiseaseContextBinding("candidate-synthetic-a2a", "hypothesis-local", value.at("/gene/hgncId").asText(),
                value.at("/gene/symbol").asText(), value.at("/disease/mondoId").asText(), value.at("/disease/label").asText(),
                value.path("moi").asText(), "Definitive", "2026-09-05", "SYNTHETIC", "synthetic/relationship", "READY", null, null);
        JsonNode input = value.at("/variant/inputAllele");
        Allele allele = new Allele(input.path("assembly").asText(), input.path("contig").asText(), input.path("position").asInt(),
                input.path("reference").asText(), input.path("alternate").asText());
        SourceRef ref = new SourceRef("SYNTHETIC", "1", "synthetic/receipt", "f".repeat(64));
        Provenance provenance = new Provenance("SYNTHETIC", ref, ref, ref, ref, new AssemblyLineage("GRCh38", "NATIVE", allele, null, ref));
        ObjectNode draft = value.deepCopy();
        draft.remove(List.of("schemaVersion", "canonicalizationProfile", "contextId", "contextCompleteness"));
        ((ObjectNode) draft.path("variant")).remove(List.of("alleleId", "legacyCanonicalVariantId"));
        return new Attempt(binding.candidateId(), binding.hypothesisId(), binding, provenance,
                new ContextSeed(binding.candidateId(), draft), value, List.of());
    }

    private AssemblyResult batch(Attempt original) {
        return new AssemblyResult("analysis-synthetic-e4-a2a", 1, 1, List.of(original.candidateId()),
                List.of(new CandidateAssembly(original.candidateId(), List.of(original))), List.of(), false);
    }
    private CSpecExecutionPreflight.SelectionAttempt checked(CSpecExecutionPreflight checker, Attempt original,
                                                            List<JsonNode> reviews, List<JsonNode> conflicts) {
        return checker.check(batch(original), reviews, conflicts).candidates().get(0).attempts().get(0);
    }
    private CSpecExecutionPreflight.SelectionAttempt select(Attempt original, ObjectNode synthetic,
                                                           List<JsonNode> reviews, List<JsonNode> conflicts) throws Exception {
        return preflight.select(original, synthetic, sha(mapper.writeValueAsBytes(synthetic)), reviews, conflicts);
    }
    private void resourceBlocked(byte[] rules, byte[] overlays, byte[] profile) {
        var result = checked(new CSpecExecutionPreflight(mapper, rules, overlays, profile), attempt(context), List.of(), List.of());
        assertNull(result.selection());
        assertNull(result.outcome());
        assertFalse(result.blockers().isEmpty());
    }
    private void blocked(CSpecExecutionPreflight.SelectionAttempt result, String reason) {
        assertTrue(result.blockers().stream().anyMatch(blocker -> reason.equals(blocker.reasonCode()) && blocker.field() != null),
                () -> result.blockers().toString());
    }
    private ObjectNode review(CSpecExecutionPreflight.SelectionAttempt pending) {
        ObjectNode review = mapper.createObjectNode().put("schemaVersion", "review-record-v2")
                .put("contextId", pending.original().context().path("contextId").asText()).put("subjectType", "CSPEC_SELECTION")
                .put("subjectId", pending.selectionId()).put("eventType", "REQUESTED").put("status", "REQUIRED").put("decision", "NONE")
                .put("reasonCode", pending.constraints().path("reasonCode").asText()).put("createdAt", "2026-09-05T00:00:00Z");
        review.putObject("actor").put("actorId", "reviewer-synthetic-e4-a2a").put("role", "CURATOR");
        review.put("reviewRecordId", "review-sha256-" + adapter.canonicalSha256(review));
        return review;
    }
    private ObjectNode conflict(CSpecExecutionPreflight.SelectionAttempt pending) {
        ObjectNode conflict = mapper.createObjectNode().put("conflictType", "RULE_PROFILE_MULTIPLE_MATCH").put("resolutionStatus", "OPEN")
                .put("reasonCode", "CSPEC_MULTIPLE_EXACT_MATCHES").put("detectedAt", "2026-09-05T00:00:00Z");
        List<String> ids = new ArrayList<>(pending.candidateOverlayIds());
        ids.add(pending.selectionId());
        conflict.set("participantIds", mapper.valueToTree(ids.stream().sorted().toList()));
        conflict.put("conflictId", "conflict-sha256-" + adapter.canonicalSha256(conflict));
        return conflict;
    }
    private ObjectNode exported(CSpecExecutionPreflight.SelectionAttempt result, ObjectNode synthetic) throws Exception {
        assertNotNull(result.selection());
        ObjectNode row = mapper.createObjectNode();
        row.set("context", result.original().context()); row.set("selection", result.selection());
        row.set("reviewRecords", mapper.valueToTree(result.reviewRecords())); row.set("conflicts", mapper.valueToTree(result.conflicts()));
        if (synthetic != null) row.put("dataClass", "SYNTHETIC").put("overlayRegistryArtifact", mapper.writeValueAsString(synthetic));
        return row;
    }
    private void reidentify(ObjectNode value) { value.put("contextId", adapter.canonicalContextId(value)); adapter.validateCompleteContext(value); }
    private byte[] resource(String path) throws Exception { try (var input = getClass().getResourceAsStream(path)) { return input.readAllBytes(); } }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
