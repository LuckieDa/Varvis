package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MissenseReusableReviewTest {
    @Test void policyLibraryReusesReferenceForNewTargetWithoutPriorPairButPreservesAssertionBinding() throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("fixture"), 0);
        var old = library(f);
        old.put("schemaVersion", "missense-reusable-review-v2").put("status", "POLICY_QUALIFIED");
        old.withObject("rnaReview").put("mode", MissenseRnaApplicability.MODE);
        old.putArray("rnaComparisons");
        var rna = f.rnaDraft.deepCopy();
        for (var pair : rna.path("pairs")) ((ObjectNode) pair.path("targetAllele")).put("position", 500);
        var expanded = new MissenseReusableReview(f.mapper).expand(old, f.referenceDraft, rna, f.mapper.createObjectNode());
        assertEquals(1, expanded.at("/reuseProvenance/matchedAssertions").asInt());
        assertTrue(expanded.path("rnaComparisons").isEmpty());
        assertEquals("REFERENCE_REUSE_WITH_POLICY_RNA_SCREEN_NO_PAIR_APPROVAL", expanded.at("/reuseProvenance/mode").asText());
        ((ObjectNode) old.at("/references/0/sourceAssertion")).put("extraClinicalStatement", "changed");
        expanded = new MissenseReusableReview(f.mapper).expand(old, f.referenceDraft, rna, f.mapper.createObjectNode());
        assertEquals(0, expanded.at("/reuseProvenance/matchedAssertions").asInt());
    }

    @TempDir Path temp;
    private ObjectNode library(MissenseReferenceReviewBridgeFixture f) {
        var library = MissenseReviewedReuseFixture.library(f.mapper, f.declaration, f.selectedReference, f.selectedAssertion, f.selectedPair);
        MissenseReviewedReuseFixture.sourceBindings(library, f.manifest); return library;
    }
    private void install(MissenseReferenceReviewBridgeFixture f, ObjectNode library) throws Exception {
        f.manifest.remove("review"); f.manifest.set("reusableReview", f.file("library.json", library));
        f.mapper.writeValue(f.manifestPath.toFile(), f.manifest);
    }
    @ParameterizedTest @ValueSource(ints = {0,1})
    void exactPriorJudgmentsReachExistingPreparerButDoNotApprove(int background) throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("fixture"), background);
        ObjectNode old = library(f), original = old.deepCopy(); install(f, old);
        var result = f.bridge(temp.resolve("bridge")); assertTrue(result.bridged(), result.report().toPrettyString());
        assertEquals(1, result.report().at("/reviewReuse/matchedAssertions").asInt());
        var audit = f.json(temp.resolve("bridge/reference-audit.json"));
        assertEquals(old.at("/references/0/review/independence"), audit.at("/references/0/independence"));
        assertEquals(f.selectedAssertion.path("sourceAssertion"), audit.at("/references/0/sourceAssertion"));
        assertEquals(original, f.json(temp.resolve("bridge/reusable-review-library.json")));
        assertFalse(f.json(result.manifestPath()).has("approval"));
        assertFalse(result.report().path("scientificAdoption").asBoolean());
    }
    @ParameterizedTest @CsvSource({"ANNOTATION_METADATA,REUSABLE_REFERENCE_ASSERTION_CHANGED", "SCV,REUSABLE_REFERENCE_NOT_FOUND",
        "ALLELE,REUSABLE_REFERENCE_NOT_FOUND", "TARGET,REUSABLE_EXACT_RNA_PAIR_NOT_FOUND", "TRANSCRIPT,REUSABLE_EXACT_RNA_PAIR_NOT_FOUND"})
    void changedIdentityDoesNotInheritPriorReview(String fault, String reason) throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("fixture"), 0); ObjectNode old = library(f);
        switch (fault) {
            case "ANNOTATION_METADATA" -> ((ObjectNode) old.at("/references/0/sourceAssertion")).put("extraClinicalStatement", "changed");
            case "SCV" -> ((ObjectNode) old.at("/references/0/sourceAssertion")).put("scv", "SCV000000002.3");
            case "ALLELE" -> ((ObjectNode) old.at("/references/0/allele")).put("position", 500);
            case "TARGET" -> ((ObjectNode) old.at("/rnaComparisons/0/targetAllele")).put("position", 500);
            case "TRANSCRIPT" -> ((ObjectNode) old.at("/rnaComparisons/0")).put("transcript", "ENST00000647020.2");
            default -> fail(fault);
        }
        var expanded = new MissenseReusableReview(f.mapper).expand(old, f.referenceDraft, f.rnaDraft, f.mapper.createObjectNode());
        assertEquals(0, expanded.at("/reuseProvenance/matchedAssertions").asInt());
        boolean found = false;
        for (var selected : expanded.path("references")) { assertEquals("DO_NOT_ADOPT", selected.path("decision").asText()); found |= reason.equals(selected.path("reason").asText()); }
        assertTrue(found, expanded.toPrettyString());
    }
    @ParameterizedTest @CsvSource({"DUP_REF,REUSABLE_REFERENCE_DUPLICATE", "DUP_RNA,REUSABLE_RNA_PAIR_DUPLICATE",
        "UNREVIEWED,REUSABLE_REVIEW_NOT_REVIEWED", "APPROVAL,REUSABLE_REVIEW_APPROVAL_OR_DRAFT_BINDING_FORBIDDEN",
        "BACKGROUND,REVIEW_BACKGROUND_MISMATCH", "BOTH,EXACTLY_ONE_REVIEW_INPUT_REQUIRED", "UNKNOWN_SOURCE,REUSABLE_SOURCE_BINDINGS_INCOMPLETE"})
    void invalidLibraryBlockedByOriginalBridge(String fault, String reason) throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("fixture"), 0); ObjectNode old = library(f);
        switch (fault) {
            case "DUP_REF" -> old.withArray("references").add(old.at("/references/0").deepCopy());
            case "DUP_RNA" -> old.withArray("rnaComparisons").add(old.at("/rnaComparisons/0").deepCopy());
            case "UNREVIEWED" -> old.put("status", "UNREVIEWED");
            case "APPROVAL" -> old.putObject("approval");
            case "BACKGROUND" -> ((ObjectNode) old.path("background")).put("mondoId", "MONDO:9999999");
            case "UNKNOWN_SOURCE" -> ((ObjectNode) old.at("/references/0/review/independence")).putArray("sourceIds").add("MISSING");
            case "BOTH" -> { }
            default -> fail(fault);
        }
        install(f, old);
        if ("BOTH".equals(fault)) { f.manifest.set("review", f.descriptor(f.root.resolve("review.json"))); f.mapper.writeValue(f.manifestPath.toFile(), f.manifest); }
        var result = f.bridge(temp.resolve("bridge")); assertFalse(result.bridged()); assertEquals(reason, result.report().path("reasonCode").asText());
    }
    @Test void freshDraftIdsDoNotRequireNewScientificJudgments() throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("fixture"), 0); var old = library(f);
        var draft = f.referenceDraft.deepCopy(); ((ObjectNode) draft.path("references").get(0)).put("referenceDraftId", "NEW_DRAFT_ID");
        var expanded = new MissenseReusableReview(f.mapper).expand(old, draft, f.rnaDraft, f.mapper.createObjectNode());
        for (var row : expanded.path("references")) if ("ADOPT".equals(row.path("decision").asText())) {
            assertEquals("NEW_DRAFT_ID", row.path("referenceDraftId").asText());
            assertEquals(old.at("/references/0/review"), row.path("review"));
        }
    }
    @Test void validatedExistingDeclarationCanExportLibraryThenReuseWithoutReauthoring() throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("fixture"), 0);
        f.manifest.put("exportReusableReview", true); f.save();
        var first = f.bridge(temp.resolve("first")); assertTrue(first.bridged(), first.report().toPrettyString());
        var exported = f.json(temp.resolve("first/exported-reusable-review.json"));
        assertEquals(f.declaration.at("/references/1/review"), exported.at("/references/0/review"));
        install(f, exported);
        var second = f.bridge(temp.resolve("second")); assertTrue(second.bridged(), second.report().toPrettyString());
        assertEquals(f.json(temp.resolve("first/reference-audit.json")), f.json(temp.resolve("second/reference-audit.json")));
        assertEquals(f.json(temp.resolve("first/rna-review.json")), f.json(temp.resolve("second/rna-review.json")));
    }
    @ParameterizedTest @ValueSource(strings = {"RAW_CHANGED", "SOURCE_URL", "OMITTED"})
    void sameSourceIdCannotSubstituteDifferentEvidence(String fault) throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temp.resolve("fixture"), 0); var old = library(f);
        if ("RAW_CHANGED".equals(fault)) {
            var evidence = f.json(f.root.resolve("external-evidence.json")); evidence.put("content", "changed source with SAME ID");
            var descriptor = f.file("external-evidence.json", evidence).put("format", "JSON").put("sourceDigestKind", "CANONICAL_JSON");
            descriptor.set("sourceRecord", f.manifest.at("/reviewEvidence/0/sourceRecord").deepCopy());
            f.manifest.putArray("reviewEvidence").add(descriptor);
        } else if ("SOURCE_URL".equals(fault)) ((ObjectNode)f.manifest.at("/reviewEvidence/0/sourceRecord")).put("url", "https://example.invalid/changed");
        else old.remove("sourceBindings");
        install(f, old); var result = f.bridge(temp.resolve("bridge")); assertFalse(result.bridged());
        assertEquals("OMITTED".equals(fault) ? "REUSABLE_SOURCE_BINDINGS_REQUIRED" : "REUSABLE_SOURCE_BINDING_CHANGED_OR_MISSING", result.report().path("reasonCode").asText());
    }
}
