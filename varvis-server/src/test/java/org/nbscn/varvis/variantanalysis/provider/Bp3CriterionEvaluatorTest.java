package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

class Bp3CriterionEvaluatorTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void fixedIndependentSequencesAndAllEquivalentPositions() throws Exception {
        JsonNode inputs;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/bp3-test-only-sequences.json"))) { inputs = mapper.readTree(stream); }
        for (JsonNode row : Objects.requireNonNull(inputs).path("rows")) {
            var fixture = new Bp3TestFixtures(mapper, row.path("strand").asInt());
            fixture.variant(row.path("pos").asInt(), row.path("ref").asText(), row.path("alt").asText(), row.path("coding").asText(), row.path("protein").asText());
            ObjectNode observation = mapper.createObjectNode();
            assertNull(ProteinLengthChangeObservation.resolve(fixture.context, fixture.model, fixture.candidate.getTranscriptAnnotations().get(0), observation), row.toPrettyString());
            for (String field : List.of("referenceCds", "referenceProtein", "alteredCds", "alteredProtein")) {
                assertEquals(row.path(field), observation.path(field), field);
                assertEquals(ProteinLengthChangeObservation.sha(row.path(field).asText()), observation.path(field + "Sha256").asText());
            }
            assertEquals(row.path("start"), observation.path("referenceEditStart0"));
            assertEquals(row.path("end"), observation.path("referenceEditEnd0"));
            assertEquals(row.path("net"), observation.path("netAminoAcidChange"));
            assertNull(Bp3EquivalentEditEnvelope.resolve(fixture.model, observation));
            JsonNode envelope = observation.path("equivalentEditEnvelope");
            assertEquals(row.path("equivalentStart0"), envelope.path("firstStart0"));
            assertEquals(row.path("equivalentEnd0"), envelope.path("lastStart0"));
            assertEquals(row.path("proteinStart"), envelope.path("proteinStart"));
            assertEquals(row.path("proteinEnd"), envelope.path("proteinEnd"));
            ObjectNode result = fixture.evaluate();
            status(result, "MET", "BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION");
            assertEquals("SUPPORTING", result.at("/evaluation/appliedStrength").asText());
            assertEquals(envelope, result.at("/resolvedInput/equivalentEditEnvelope"));
            assertEquals(1, result.path("criterionEvaluations").size());
            assertFalse(result.toString().contains(row.path("referenceCds").asText()));
            archive(row.path("id").asText(), result);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"NO_FUNCTION_REVIEW", "FUNCTION_UNKNOWN", "REPEAT_UNKNOWN", "UNREVIEWED", "KNOWN_FUNCTION", "NON_REPEAT", "MIXED_UNKNOWN", "GAP", "CONFLICT", "INSERT_ONE_SIDE", "EQUIVALENT_OUTSIDE", "DELETE_END_OUTSIDE", "MODEL_SHA", "TRANSCRIPT", "SOURCE", "SOURCE_VERSION", "MODEL_SEQUENCE_SHA", "HGVS", "SPLICE", "POLICY_DISABLED", "AUTHORITY", "AUTHORITY_VERSION", "REVIEW_HASH", "REGISTRY", "REGISTRY_OLD"})
    void singleCauseGate(String mode) throws Exception {
        var f = positive(); ObjectNode row = (ObjectNode) f.regions.at("/intervals/0");
        String reason; String expected = "NOT_EVALUATED"; boolean review = true;
        switch (mode) {
            case "NO_FUNCTION_REVIEW" -> { row.remove("functionalReview"); review = false; reason = "BP3_FUNCTIONAL_REVIEW_MISSING"; }
            case "FUNCTION_UNKNOWN" -> { row.put("functionalStatus", "UNKNOWN"); reason = "BP3_REGION_FACT_UNKNOWN"; }
            case "REPEAT_UNKNOWN" -> { row.put("repeatStatus", "UNKNOWN"); reason = "BP3_REGION_FACT_UNKNOWN"; }
            case "UNREVIEWED" -> { row.withObject("functionalReview").put("status", "UNREVIEWED"); review = false; reason = "BP3_FUNCTIONAL_REVIEW_MISSING"; }
            case "KNOWN_FUNCTION" -> { row.put("functionalStatus", "KNOWN_FUNCTION"); reason = "BP3_KNOWN_FUNCTION"; expected = "NOT_MET"; }
            case "NON_REPEAT" -> { row.put("repeatStatus", "NON_REPEAT"); reason = "BP3_NON_REPEAT_REGION"; expected = "NOT_MET"; }
            case "MIXED_UNKNOWN" -> { row.put("functionalStatus", "KNOWN_FUNCTION"); f.regions.withArray("intervals").add(row.deepCopy().put("start", 6).put("end", 6).put("repeatStatus", "UNKNOWN")); reason = "BP3_REGION_FACT_UNKNOWN"; }
            case "GAP" -> { row.put("start", 4); reason = "BP3_EQUIVALENT_ENVELOPE_REGION_COVERAGE_INCOMPLETE"; }
            case "CONFLICT" -> { f.regions.withArray("intervals").add(row.deepCopy().put("functionalStatus", "KNOWN_FUNCTION")); reason = "BP3_REGION_CONFLICT"; }
            case "INSERT_ONE_SIDE" -> { row.put("start", 3); reason = "BP3_EQUIVALENT_ENVELOPE_REGION_COVERAGE_INCOMPLETE"; }
            case "EQUIVALENT_OUTSIDE" -> { f.variant(108, "T", "TCCT", "7_9dup", "Pro3dup"); row.put("start", 3); reason = "BP3_EQUIVALENT_ENVELOPE_REGION_COVERAGE_INCOMPLETE"; }
            case "DELETE_END_OUTSIDE" -> { f.variant(103, "GAAC", "G", "5_7del", "Glu2_Pro3delinsAla"); row.put("end", 2); reason = "BP3_EQUIVALENT_ENVELOPE_REGION_COVERAGE_INCOMPLETE"; }
            case "MODEL_SHA" -> { f.policy.withObject("transcriptModelBinding").put("canonicalSha256", "0".repeat(64)); status(f.evaluate(), expected, "BP3_MODEL_MISSING"); return; }
            case "TRANSCRIPT" -> { f.model.put("transcript", "ENST000001.2"); reason = "BP3_TRANSCRIPT_MODEL_CONTEXT_MISMATCH"; }
            case "SOURCE" -> { ((ObjectNode) f.regions.at("/sourceRecords/0")).put("sha256", "b".repeat(64)); reason = "BP3_MATERIAL_SOURCE_INVALID"; }
            case "SOURCE_VERSION" -> { ((ObjectNode) f.model.at("/sourceRecords/0")).put("version", "2"); reason = "BP3_MATERIAL_SOURCE_INVALID"; }
            case "MODEL_SEQUENCE_SHA" -> { f.policy.put("cdsSha256", "0".repeat(64)); status(f.evaluate(), expected, "BP3_MODEL_SEQUENCE_SHA_MISMATCH"); return; }
            case "HGVS" -> { f.variant(105, "A", "AGCT", "5_6insGCT", null); reason = "BP3_HGVSC_SEQUENCE_MISMATCH"; }
            case "SPLICE" -> { f.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(f.annotation("6_7insGCT", null, "splice_region_variant"), TranscriptConsequence.class))); reason = "BP3_SPLICE_BRANCH_UNSUPPORTED"; }
            case "POLICY_DISABLED" -> { f.policy.put("allowBp3", false); reason = "BP3_POLICY_DISABLED"; }
            case "AUTHORITY" -> { f.policy.putArray("authoritySourceIds"); reason = "BP3_POLICY_AUTHORITY_MISSING"; }
            case "AUTHORITY_VERSION" -> { ((ObjectNode) f.policy.at("/scientificSources/1")).put("version", "UNAPPROVED_VERSION"); reason = "BP3_REGISTRY_IDENTITY_INVALID"; }
            case "REGISTRY_OLD" -> { var doc = f.document(); ((ObjectNode) doc.at("/materials/bp3ResourceProfile")).set("registryBinding", Hnf1aPvs1Runtime.loadProfile(mapper).path("registryBinding")); status(f.bundle(doc).evaluate(Bp3CriterionEvaluator.CRITERION, f.context, f.candidate), expected, "BP3_REGISTRY_IDENTITY_INVALID"); return; }
            case "REVIEW_HASH" -> { row.withObject("functionalReview").put("contentSha256", "0".repeat(64)); review = false; reason = "BP3_FUNCTIONAL_REVIEW_IDENTITY_MISMATCH"; }
            case "REGISTRY" -> { var doc = f.document(); ((ObjectNode) doc.at("/materials/bp3ResourceProfile/registryBinding")).put("registrySha256", "0".repeat(64)); status(f.bundle(doc).evaluate(Bp3CriterionEvaluator.CRITERION, f.context, f.candidate), expected, "BP3_REGISTRY_IDENTITY_INVALID"); return; }
            default -> throw new AssertionError(mode);
        }
        f.bind(); if (review) f.review(); f.bind();
        var result = f.evaluate(); status(result, expected, reason); archive(mode, result);
        if (Set.of("AUTHORITY", "CONFLICT").contains(mode)) assertFalse(result.has("evaluation"));
    }

    @ParameterizedTest @ValueSource(strings = {"SEARCH", "DISABLED", "CONFLICT", "MODEL_MISSING", "MODEL_TAMPER", "REGION_MISSING", "REGION_TAMPER"})
    void selectorAndMaterialIntegrityNeverFallBack(String mode) throws Exception {
        var f = positive(); var doc = f.document(); String reason;
        ObjectNode entry = (ObjectNode) doc.at("/catalog/entries/0");
        switch (mode) {
            case "SEARCH" -> { entry.put("searchStatus", "INCOMPLETE"); reason = "CLAUSE_SEARCH_INCOMPLETE"; }
            case "DISABLED" -> { entry.put("disposition", "SPECIALIZED_DISABLED"); reason = "CLAUSE_DISPOSITION_UNKNOWN"; }
            case "CONFLICT" -> { doc.withObject("catalog").withArray("entries").add(entry.deepCopy().put("entryId", "SECOND")); reason = "CLAUSE_SELECTION_CONFLICT"; }
            default -> {
                boolean model = mode.startsWith("MODEL"); var materials = (ObjectNode) doc.at("/materials/" + (model ? "transcriptModels" : "bp3Regions"));
                if (mode.endsWith("MISSING")) materials.removeAll(); else ((ObjectNode) materials.elements().next()).put("tampered", true);
                reason = "BP3_" + (model ? "MODEL" : "REGION") + (mode.endsWith("MISSING") ? "_MISSING" : "_SHA_MISMATCH");
            }
        }
        doc.put("catalogSha256", f.canonicalSha256(doc.path("catalog"))); var result = f.bundle(doc).evaluate(Bp3CriterionEvaluator.CRITERION, f.context, f.candidate);
        status(result, "NOT_EVALUATED", reason); assertFalse(ReviewedRuleBundle.usesPreexistingClause(result)); archive(mode, result);
    }
    @Test void incompleteIdentityIsMinimalAndLegacyCannotExecuteBp3() throws Exception {
        var f = positive(); var doc = f.document();
        var invalid = f.context.deepCopy(); invalid.remove("contextId");
        assertFalse(f.bundle(doc).evaluate(Bp3CriterionEvaluator.CRITERION, invalid, f.candidate).has("evaluation"));
        var legacy = f.bundle(ReviewedRuleContextPreparationTest.emptyBundleDocument(mapper, "1"));
        assertThrows(IllegalArgumentException.class, () -> legacy.evaluate(Bp3CriterionEvaluator.CRITERION, f.context, f.candidate));
        assertFalse(AcmgBusinessRuleScope.CURRENT.contains(Bp3CriterionEvaluator.CRITERION));
        assertEquals(List.of(Bp3CriterionEvaluator.CRITERION), AcmgExecutionProfile.bp3Development(mapper, f.bundle(doc)).criteria());
    }
    @Test void envelopeCannotBeClippedToExonOrStartBoundary() throws Exception {
        var f = positive(); f.variant(105, "A", "ACCT", "6_7insCCT", "Pro3dup");
        ObjectNode facts = mapper.createObjectNode();
        assertNull(ProteinLengthChangeObservation.resolve(f.context, f.model, f.candidate.getTranscriptAnnotations().get(0), facts));
        f.model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 107).put("cdsStart", 1).put("cdsEnd", 8);
        f.model.withArray("exons").addObject().put("ordinal", 2).put("genomicStart", 200).put("genomicEnd", 212).put("cdsStart", 9).put("cdsEnd", 21);
        assertNull(CodingTranscriptMapping.modelIssue(f.model, f.context));
        assertEquals("BP3_EQUIVALENT_ENVELOPE_EXON_BOUNDARY", Bp3EquivalentEditEnvelope.resolve(f.model, facts));
        assertEquals(6, facts.at("/equivalentEditEnvelope/firstStart0").asInt());
        assertEquals(9, facts.at("/equivalentEditEnvelope/lastStart0").asInt());
        facts.put("referenceCds", "ATGATGGAATAA").put("alteredCds", "ATGATGATGGAATAA");
        assertEquals("BP3_EQUIVALENT_ENVELOPE_ENGINEERING_BOUNDARY", Bp3EquivalentEditEnvelope.resolve(f.model, facts));
        assertEquals(0, facts.at("/equivalentEditEnvelope/firstStart0").asInt());
    }
    @Test void completeIdentityHashesAndFrozenContractAxesAreConsistent() throws Exception {
        var f = positive(); var result = f.evaluate(); f.adapter.validateCompleteContext(result.path("context"));
        var selection = ((ObjectNode) result.path("policySelection")).deepCopy(); String selectionId = selection.remove("selectionId").asText();
        assertEquals("cspec-selection-sha256-" + f.canonicalSha256(selection), selectionId);
        var evaluation = ((ObjectNode) result.path("evaluation")).deepCopy();
        String id = evaluation.remove("evaluationId").asText(); evaluation.remove(List.of("conflict", "review"));
        assertEquals("evaluation-sha256-" + f.canonicalSha256(evaluation), id);
        var trace = ((ObjectNode) evaluation.path("deterministicTrace")).deepCopy(); String digest = trace.remove("traceSha256").asText();
        assertEquals(f.canonicalSha256(trace), digest);
        assertTrue(f.adapter.savedAtomicIdentityMatches((ObjectNode) result.path("evidence")));
        assertEquals("COMPLETED", trace.path("executionStatus").asText());
        assertEquals("APPLICABLE", evaluation.at("/criterionApplicability/status").asText());
        assertEquals(result.at("/policy/authoritySourceIds"), result.at("/evaluation/ruleBinding/authoritySourceIds"));
        assertEquals(f.canonicalSha256(f.policy), selection.path("policySha256").asText());
        archive("contract-identities", result);
    }
    static void status(ObjectNode result, String status, String reason) {
        JsonNode value = result.has("evaluation") ? result.at("/evaluation/evaluationResult") : result.path("evaluationResult");
        assertEquals(status, value.path("status").asText(), result.toPrettyString());
        assertEquals(reason, value.path("reasonCode").asText(), result.toPrettyString());
    }
    Bp3TestFixtures positive() throws Exception { var f = new Bp3TestFixtures(mapper, 1); f.variant(105, "A", "AGCT", "6_7insGCT", "Glu2_Pro3insAla"); return f; }
    void archive(String name, JsonNode result) throws Exception {
        String root = System.getProperty("varvis.bp3.artifacts"); if (root == null) return;
        Path file = Path.of(root).resolve("core").resolve(name + ".json"); Files.createDirectories(file.getParent()); Files.writeString(file, result.toPrettyString());
    }
}
