package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transport and policy fixtures; no real gene-disease scientific acceptance. */
class PredictionClauseRuntimeTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final PredictionClauseRuntime runtime = new PredictionClauseRuntime(mapper);

    @ParameterizedTest @CsvSource({"0.169,NOT_MET,MET", "0.170,NOT_MET,NOT_MET", "0.791,NOT_MET,NOT_MET", "0.792,MET,NOT_MET"})
    void selectedAlphaMissenseUsesIndependentClauseThresholdsWithoutSwitching(String score, String pp3, String bp4) throws Exception {
        Fixture f = alpha(score);
        assertStatus(pp3, run(f, policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792"), null));
        assertStatus(bp4, run(f, policy(f, "BP4", "ALPHAMISSENSE", "LE", "0.169"), null));
    }

    @ParameterizedTest @CsvSource({"0.0999,MET,NOT_MET", "0.1,NOT_MET,NOT_MET", "0.1999,NOT_MET,NOT_MET", "0.2,NOT_MET,MET"})
    void reviewedStrictSpliceBoundaryOverridesOldInclusiveProfileConclusion(String score, String bp4, String pp3) throws Exception {
        Fixture f = splice(score, 7);
        assertStatus(bp4, run(f, policy(f, "BP4", "SPLICEAI", "LT", "0.1"), null));
        assertStatus(pp3, run(f, policy(f, "PP3", "SPLICEAI", "GE", "0.2"), null));
    }

    @ParameterizedTest @CsvSource({"7,MET", "-21,MET", "6,NOT_EVALUATED", "-20,NOT_EVALUATED"})
    void bp7RequiresPositionAndExactSavedSpliceBp4(int offset, String expected) throws Exception {
        Fixture f = splice("0.01", offset);
        ObjectNode bp4Policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode bp4 = run(f, bp4Policy, null);
        assertStatus("MET", bp4);
        ObjectNode result = run(f, bp7Policy(f, bp4Policy), bp4);
        if ("NOT_EVALUATED".equals(expected)) assertRefused(result);
        else {
            assertStatus(expected, result);
            assertTrue(result.path("sharedSpliceEvidence").asBoolean());
            assertEquals(1, result.path("independentEvidenceCount").asInt());
            assertEquals(bp4, result.path("bp4Dependency"));
            assertEquals(1, result.path("dependencyRevalidationCalls").asInt());
            assertEquals(0, result.path("dependencyProviderCalls").asInt());
            assertEquals(1, result.path("dependencyStoredVepProjections").asInt());
        }
    }

    @Test void bp7CannotTurnThresholdEqualityIntoSupportingEvidence() throws Exception {
        Fixture f = splice("0.1", 7);
        ObjectNode policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode bp4 = run(f, policy, null);
        assertStatus("NOT_MET", bp4);
        assertStatus("NOT_MET", run(f, bp7Policy(f, policy), bp4));
    }

    @Test void bp7CannotHideAdditionalThresholdOrDependencyConditions() throws Exception {
        Fixture f = splice("0.01", 7);
        ObjectNode bp4Policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode bp4 = run(f, bp4Policy, null), policy = bp7Policy(f, bp4Policy);
        policy.putObject("threshold").put("operator", "LT").put("decimal", "0.001").put("unit", "score");
        assertRefused(run(f, policy, bp4));
        policy.remove("threshold"); ((ObjectNode) policy.path("bp7Dependency")).put("conservationRequired", true);
        assertRefused(run(f, policy, bp4));
    }

    @ParameterizedTest @CsvSource({"false,3,MET", "false,2,NOT_EVALUATED", "true,9,NOT_EVALUATED"})
    void synonymousBp7RetainsSameRecordVepExonEngineeringBoundaries(boolean firstBase, int distance, String expected) throws Exception {
        Fixture f = splice("0.01", 7);
        long start = firstBase ? 100 : 90, end = 100 + distance;
        long left = 100 - start, right = end - 100;
        String nearest = left == right ? "start_end" : left < right ? "start" : "end";
        var position = new org.nbscn.varvis.variantanalysis.domain.SynonymousExonPosition("GRCh38", "1", 100,
                "A", "G", "ENST000001.4", "ENSE000001", 1, start, end,
                "ENSE000001|" + Math.min(left, right) + "|" + nearest + "|" + (end - start + 1), "116.0",
                org.nbscn.varvis.variantanalysis.domain.SynonymousExonPosition.PLUGIN_SHA256);
        ObjectNode tx = mapper.createObjectNode().put("geneSymbol", "SYNTH1").put("transcriptAccession", "ENST000001")
                .put("transcriptId", "ENST000001").put("hgvsCoding", "ENST000001.4:c.102A>G")
                .put("hgvsProtein", "ENSP000001.2:p.Lys34=").put("consequence", "synonymous_variant").put("exon", "1/2")
                .put("proteinPosition", "34").put("aminoAcids", "K").put("codons", "AAA/AAG")
                .put("source", "VEP_SYNTHETIC").put("status", "COMPLETED").put("version", "116.0").put("timestamp", "2026-09-01T00:00:00Z");
        f.candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(tx,
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class).withSynonymousExonPosition(position)));
        ObjectNode bp4Policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode bp4 = run(f, bp4Policy, null); assertStatus("MET", bp4);
        ObjectNode result = run(f, bp7Policy(f, bp4Policy), bp4);
        if ("NOT_EVALUATED".equals(expected)) assertRefused(result); else assertStatus(expected, result);
    }

    @Test void absentProteinOtherSourceAndRehashedTamperedBp4AreNotDependencies() throws Exception {
        Fixture f = splice("0.01", 7);
        ObjectNode bp4Policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode policy = bp7Policy(f, bp4Policy);
        assertRefused(run(f, policy, null));
        Fixture protein = alpha("0.01");
        assertRefused(run(f, policy, run(protein, policy(protein, "BP4", "ALPHAMISSENSE", "LE", "0.169"), null)));
        ObjectNode bp4 = run(f, bp4Policy, null);
        for (String field : List.of("context", "evidence", "policy")) {
            ObjectNode changed = bp4.deepCopy(); ((ObjectNode) changed.path(field)).put("changed", true);
            seal(changed); assertRefused(run(f, policy, changed));
        }
        ObjectNode changed = bp4.deepCopy();
        ((ObjectNode) changed.at("/evaluation/evaluationResult")).put("status", "NOT_MET");
        seal(changed); assertRefused(run(f, policy, changed));
    }

    @Test void publishedHashesCannotLendPp3PolicyAndReceiptToBp4() throws Exception {
        Fixture f = splice("0.3", 7);
        ObjectNode realBp4Policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode realBp4 = run(f, realBp4Policy, null);
        assertStatus("NOT_MET", realBp4);
        assertStatus("NOT_MET", run(f, bp7Policy(f, realBp4Policy), realBp4));
        ObjectNode pp3Policy = policy(f, "PP3", "SPLICEAI", "GE", "0.2");
        ObjectNode pp3 = run(f, pp3Policy, null); assertStatus("MET", pp3);
        pp3.put("criterionId", PredictionClausePolicy.BP4);
        rehashEvaluation(pp3, "MET");
        ObjectNode result = run(f, bp7Policy(f, pp3Policy), pp3);
        assertRefused(result);
        assertEquals("PREDICTION_BP4_POLICY_BINDING_MISMATCH", result.at("/evaluationResult/reasonCode").asText());
        assertEquals(0, result.path("dependencyRevalidationCalls").asInt());
    }

    @Test void rehashedSavedDiscordanceCannotBecomeReviewedBp4() throws Exception {
        Fixture f = splice("0.01", 7);
        ObjectNode bp4Policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode saved = run(f, bp4Policy, null); assertStatus("MET", saved);
        ((ObjectNode) saved.path("observation")).withArray("knownConflicts").add("SYNTHETIC_KNOWN_DISCORDANCE");
        rehashEvaluation(saved, "MET");
        ObjectNode result = run(f, bp7Policy(f, bp4Policy), saved);
        assertRefused(result);
        assertEquals("PREDICTION_BP4_SAVED_DISCORDANCE_REQUIRES_REVIEW", result.at("/evaluationResult/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings={"observationContext", "observationEvidence", "storedVep", "modelName", "executionCount"})
    void completeSavedSnapshotMustSurviveDeterministicRevalidation(String field) throws Exception {
        Fixture f = splice("0.01", 7);
        ObjectNode bp4Policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode saved = run(f, bp4Policy, null); assertStatus("MET", saved);
        switch (field) {
            case "observationContext" -> ((ObjectNode) saved.at("/observation/context")).put("changed", true);
            case "observationEvidence" -> ((ObjectNode) saved.at("/observation/evidence")).put("changed", true);
            case "storedVep" -> ((ObjectNode) saved.path("vepEvidence")).put("changed", true);
            case "modelName" -> saved.put("preselectedModel", "ALPHAMISSENSE");
            case "executionCount" -> saved.put("storedVepProjections", 0);
            default -> throw new AssertionError(field);
        }
        rehashEvaluation(saved, "MET");
        assertRefused(run(f, bp7Policy(f, bp4Policy), saved));
    }

    @Test void savedBp4CanBeRevalidatedInFreshRuntimeWithoutAnyProvider() throws Exception {
        Fixture f = splice("0.01", 7);
        ObjectNode bp4Policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode saved = run(f, bp4Policy, null), policy = bp7Policy(f, bp4Policy);
        ObjectNode restored = (ObjectNode) mapper.readTree(mapper.writeValueAsBytes(saved));
        ObjectNode result = new PredictionClauseRuntime(mapper).evaluate(f.context(), f.candidate(), policy,
                selection(f.context(), policy, "SPECIALIZED_IMPLEMENTED"), f.observation(), restored);
        assertStatus("MET", result);
        assertEquals(1, result.path("dependencyRevalidationCalls").asInt());
        assertEquals(0, result.path("dependencyProviderCalls").asInt());
    }

    /** Emulates rehashing a tampered saved document using its public deterministic identity algorithm. */
    private void rehashEvaluation(ObjectNode saved, String status) throws Exception {
        saved.remove(List.of("evaluation", "resultId"));
        var method = PredictionClauseRuntime.class.getDeclaredMethod("evaluation", ObjectNode.class, String.class);
        method.setAccessible(true);
        Object evaluated = method.invoke(runtime, saved, status);
        if (!(evaluated instanceof ObjectNode node)) throw new AssertionError("Synthetic evaluation absent");
        saved.set("evaluation", node); seal(saved);
    }

    @Test void noSearchAndKnownSpecializedGapDoNotActivateAvailableGeneralModels() throws Exception {
        Fixture f = alpha("0.99");
        ObjectNode p = policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        for (String disposition : List.of("SPECIALIZED_UNIMPLEMENTED", "SPECIALIZED_UNRESOLVED", "SPECIALIZED_VERSION_CONFLICT")) {
            ObjectNode receipt = selection(f.context(), p, disposition);
            ObjectNode result = runtime.evaluate(f.context(), f.candidate(), p, receipt, f.observation(), null);
            assertRefused(result); assertEquals(receipt.path("reasonCode"), result.at("/evaluationResult/reasonCode"));
        }
        ObjectNode receipt = selection(f.context(), p, "SPECIALIZED_IMPLEMENTED"); receipt.put("searchScope", "tampered");
        assertRefused(runtime.evaluate(f.context(), f.candidate(), p, receipt, f.observation(), null));
    }

    @Test void unknownAdditionalRequirementsAndKnownDiscordanceRemainReviewGates() throws Exception {
        Fixture f = alpha("0.99");
        ObjectNode p = policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ((ArrayNode) p.path("additionalRequirements")).add("VARSEAK_CONCORDANCE");
        assertRefused(run(f, p, null));
        p.putArray("additionalRequirements");
        ((ArrayNode) p.at("/discordanceReview/knownDiscordantAlleles")).addObject()
                .put("alleleId", f.context().at("/variant/alleleId").asText()).put("reasonCode", "SYNTHETIC_DISCORDANCE")
                .putArray("sourceIds").add("synthetic-source");
        ObjectNode result = run(f, p, null);
        assertEquals("PREDICTION_KNOWN_DISCORDANCE_REQUIRES_REVIEW", result.at("/evaluationResult/reasonCode").asText());
        assertRefused(result);
        ((ObjectNode) p.path("discordanceReview")).putArray("knownDiscordantAlleles");
        ((ArrayNode) f.observation().path("knownConflicts")).add("SYNTHETIC_OBSERVED_DISCORDANCE");
        result = run(f, p, null);
        assertEquals("PREDICTION_OBSERVED_DISCORDANCE_REQUIRES_REVIEW", result.at("/evaluationResult/reasonCode").asText());
    }

    @Test void planAndSavedResultKeepIndependentInputSnapshots() throws Exception {
        Fixture f = alpha("0.99");
        ObjectNode p = policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ObjectNode result = run(f, p, null), saved = result.deepCopy();
        assertStatus("MET", result);
        p.put("policyVersion", "2"); f.context().put("changed", true); f.observation().put("changed", true);
        assertEquals(saved, result);
        assertNotEquals(selection(f.context(), p, "SPECIALIZED_IMPLEMENTED").path("selectionId"), result.at("/policySelection/selectionId"));
    }

    @ParameterizedTest @ValueSource(strings = {"NO_RECORD", "TIMEOUT", "MISSING_SCORE", "DUPLICATE_SCORE"})
    void missingFailedOrAmbiguousSelectedScoreNeverBecomesEvidence(String failure) throws Exception {
        Fixture f = alpha("0.99");
        ObjectNode p = policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ObjectNode carrier = carrier(f);
        ArrayNode metrics = (ArrayNode) carrier.path("measurements");
        if (failure.contains("SCORE")) {
            JsonNode score = null;
            for (int i = metrics.size() - 1; i >= 0; i--)
                if ("alphamissense.amPathogenicity".equals(metrics.path(i).path("metricId").asText())) {
                    score = metrics.path(i).deepCopy();
                    if (failure.startsWith("MISSING")) metrics.remove(i);
                }
            if (failure.startsWith("DUPLICATE")) {
                if (!(score instanceof ObjectNode scoreObject)) throw new AssertionError("Synthetic score was missing");
                metrics.add(scoreObject.put("rawValue", "0.98").put("normalizedValue", "0.98"));
            }
        } else {
            metrics.removeAll(); ((ObjectNode) carrier.path("source")).putArray("sourceRecords");
            ObjectNode query = carrier.putObject("queryOutcome").put("recordCount", 0);
            if ("NO_RECORD".equals(failure)) query.put("status", "NO_RECORD").put("queryFingerprint", "d".repeat(64));
            else {
                query.put("status", "NOT_QUERIED");
                carrier.putObject("resourceExecution").put("status", "TIMEOUT").put("reasonCode", "SYNTHETIC_TIMEOUT");
                carrier.putObject("resourceApplicability").put("status", "UNKNOWN").put("reasonCode", "SYNTHETIC_TIMEOUT");
            }
        }
        f.observation().set("evidence", adapter.adapt(f.context(), carrier));
        f.observation().putObject("unusedAlternativeModel").put("REVEL", "0.99");
        ObjectNode result = run(f, p, null);
        assertRefused(result); assertEquals("ALPHAMISSENSE", result.path("preselectedModel").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"0.7e0", "NaN", "-0.1", "1.1", ".7"})
    void invalidDecimalPoliciesDoNotReadAvailableScores(String threshold) throws Exception {
        Fixture f = alpha("0.99");
        ObjectNode p = policy(f, "PP3", "ALPHAMISSENSE", "GE", threshold);
        ObjectNode result = run(f, p, null); assertRefused(result);
        assertFalse(result.has("observation"));
    }

    @Test void unknownScopeRulesCannotBeSilentlyIgnoredAndProteinIdentityCannotDrift() throws Exception {
        Fixture f = alpha("0.99");
        ObjectNode p = policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ((ObjectNode) p.path("scope")).put("conservationRequired", true);
        assertRefused(run(f, p, null));
        ((ObjectNode) p.path("scope")).remove("conservationRequired");
        ObjectNode annotation = mapper.valueToTree(f.candidate().getTranscriptAnnotations().get(0));
        annotation.remove(List.of("hgvsc", "hgvsp")); annotation.put("aminoAcids", "K/Q");
        f.candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        ObjectNode result = run(f, p, null); assertRefused(result);
        assertEquals("PREDICTION_ALPHAMISSENSE_STORED_PROTEIN_MISMATCH", result.at("/evaluationResult/reasonCode").asText());
    }

    @Test void noncanonicalPolicyObservationAndDependencyReturnStructuredFailures() throws Exception {
        Fixture f = splice("0.01", 7);
        ObjectNode policy = policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode selection = selection(f.context(), policy, "SPECIALIZED_IMPLEMENTED");
        ObjectNode changed = policy.deepCopy(); ((ObjectNode) changed.path("threshold")).put("decimal", 0.1);
        assertRefused(runtime.evaluate(f.context(), f.candidate(), changed, selection, f.observation(), null));
        ObjectNode bp4 = run(f, policy, null), bp7 = bp7Policy(f, policy);
        ObjectNode dependency = bp4.deepCopy(); dependency.put("invalid", 0.1);
        assertRefused(run(f, bp7, dependency));
        f.observation().put("invalid", 0.1); assertRefused(run(f, policy, null));
    }

    ObjectNode carrier(Fixture f) {
        JsonNode atomic = f.observation().path("evidence");
        ObjectNode carrier = mapper.createObjectNode();
        for (String field : List.of("evidenceType", "source", "resourceExecution", "resourceAvailability", "queryOutcome", "resourceApplicability", "measurements"))
            carrier.set(field, atomic.path(field).deepCopy());
        carrier.set("queryAllele", f.context().at("/variant/evidenceQueryAllele").deepCopy());
        carrier.put("producedAt", atomic.at("/provenance/producedAt").asText());
        carrier.put("sourcePayloadSha256", atomic.at("/provenance/sourcePayloadSha256").asText());
        return carrier;
    }

    Fixture alpha(String score) throws Exception {
        var base = new ExplicitAlphaMissenseDispatcherTest();
        var f = base.fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        ObjectNode annotation = mapper.valueToTree(f.candidate().getTranscriptAnnotations().get(0));
        annotation.retain("geneSymbol", "entrezGeneId", "transcriptAccession", "hgvsCoding", "hgvsProtein", "consequence",
                "geneId", "transcriptId", "transcriptSource", "impact", "exon", "intron", "canonical", "maneSelect",
                "manePlusClinical", "proteinPosition", "aminoAcids", "codons", "lof", "lofFilter", "lofFlags", "source", "status", "version");
        annotation.put("timestamp", "2026-09-01T00:00:00Z");
        f.candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        var batch = new ExplicitAlphaMissenseDispatcher(base.mapper, base.new FixtureProvider(score, false, false))
                .dispatch(base.assembly(List.of(f)), List.of(f.candidate()), base.analysis);
        var attempt = batch.candidates().get(0).attempts().get(0);
        assertFalse(attempt.atomicEvidence().isEmpty(), attempt.blockers().toString());
        return fixture(f.candidate(), attempt.context(), attempt.atomicEvidence().get(0));
    }
    Fixture splice(String score, int offset) throws Exception {
        var base = new SpliceAiBusinessDispatchTest(); base.score = score;
        // The old dispatcher pre-blocks proximal BP7. Obtain the synthetic allele score first;
        // this test separately projects each stored position through the new clause runtime.
        var f = base.fixture(1, 7);
        var batch = base.dispatcher(base.base.new FixtureProvider("0.99", false, false))
                .dispatch(base.base.assembly(List.of(f)), List.of(f.candidate()), base.base.analysis);
        var attempt = batch.candidates().get(0).attempts().get(0);
        assertFalse(attempt.atomicEvidence().isEmpty(), attempt.blockers().toString());
        base.setTranscript(f, offset);
        return fixture(f.candidate(), attempt.context(), attempt.atomicEvidence().get(0));
    }
    Fixture fixture(VariantInterpretation candidate, ObjectNode context, ObjectNode evidence) {
        ObjectNode observation = mapper.createObjectNode(); observation.set("context", context.deepCopy());
        observation.set("evidence", evidence.deepCopy()); observation.putArray("knownConflicts");
        return new Fixture(candidate, context.deepCopy(), observation);
    }
    ObjectNode policy(Fixture f, String criterion, String model, String operator, String threshold) {
        ObjectNode p = mapper.createObjectNode().put("schemaVersion", "prediction-clause-policy-v1")
                .put("policyId", "synthetic-" + model + "-" + criterion).put("policyVersion", "1")
                .put("appliedStrength", "SUPPORTING").put("preselectedModel", model)
                .put("modelSelection", "BEFORE_SCORE_LOOKUP_NO_FALLBACK").put("missingOrGreyPolicy", "NO_MODEL_SWITCH_NO_VOTING")
                .put("evidenceMechanism", "SPLICEAI".equals(model) ? "SPLICING" : "PROTEIN_MISSENSE")
                .put("scopeRationale", "SYNTHETIC mechanical tests only; no clinical permission inferred");
        p.putArray("criterionIds").add("ACMG_AMP_2015:" + criterion);
        p.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "synthetic-review");
        ObjectNode scope = p.putObject("scope");
        scope.set("hgncId", f.context().at("/gene/hgncId")); scope.set("mondoId", f.context().at("/disease/mondoId"));
        scope.set("moi", f.context().path("moi")); scope.put("assembly", "GRCh38").put("variantType", "NATIVE_NONPROJECTED_SNV")
                .put("transcriptMode", "EXACT_ACCESSIONS");
        ObjectNode tx = scope.putArray("transcripts").addObject(); tx.set("accession", f.context().at("/transcript/accession"));
        tx.set("version", f.context().at("/transcript/version"));
        p.putArray("sources").addObject().put("sourceId", "synthetic-source").put("version", "1")
                .put("url", "https://example.invalid/synthetic-policy").put("sha256", "a".repeat(64));
        p.putArray("calibrationSourceIds").add("synthetic-source"); p.putArray("additionalRequirements");
        JsonNode source = f.observation().at("/evidence/source");
        p.putObject("resourceBinding").put("resourceId", source.path("sourceId").asText())
                .put("resourceVersion", source.path("resourceVersion").asText())
                .put("identityFingerprintSha256", source.path("resourceSha256").asText());
        ObjectNode review = p.putObject("discordanceReview").put("status", "REVIEWED_PINNED_SOURCES_NO_AUTO_RESOLUTION")
                .put("reviewId", "synthetic-review").put("scope", "Synthetic fixture only; empty is not a real-gene discordance assessment");
        review.putArray("sourceIds").add("synthetic-source"); review.putArray("knownDiscordantAlleles");
        p.putObject("threshold").put("operator", operator).put("decimal", threshold).put("unit", "score");
        return p;
    }
    ObjectNode bp7Policy(Fixture f, ObjectNode bp4Policy) {
        ObjectNode p = policy(f, "BP7", "SPLICEAI", "LT", "0.1"); p.remove("threshold");
        p.put("positionProfileSha256", "b128e11f4d0e074422b771d195adbc3acdd893a66a56b600de9ff0e1518803e6");
        p.putObject("bp7Dependency").put("mode", "SAME_CONTEXT_SAME_SPLICEAI_EVIDENCE_BP4")
                .put("policyId", bp4Policy.path("policyId").asText()).put("policyVersion", bp4Policy.path("policyVersion").asText())
                .put("policySha256", adapter.canonicalSha256(bp4Policy));
        return p;
    }
    ObjectNode selection(ObjectNode context, ObjectNode policy, String disposition) {
        ObjectNode c = mapper.createObjectNode().put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "synthetic-catalog").put("catalogVersion", "1").put("searchedAt", "2026-09-13T14:00:00Z")
                .put("searchScope", "Synthetic unit-test scope only");
        c.putObject("review").put("status", "REVIEWED").put("reviewId", "synthetic-review"); c.set("sources", policy.path("sources").deepCopy());
        ObjectNode entry = c.putArray("entries").addObject().put("entryId", "synthetic-clause")
                .put("criterionId", policy.path("criterionIds").path(0).asText()).put("searchStatus", "COMPLETE").put("disposition", disposition)
                .put("baseRuleRelationship", "Synthetic independently selected clause");
        entry.set("scope", policy.path("scope").deepCopy()); entry.putArray("sourceIds").add("synthetic-source");
        entry.putObject("review").put("status", "REVIEWED").put("reviewId", "synthetic-review");
        entry.putObject("policyBinding").put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText())
                .put("policySha256", adapter.canonicalSha256(policy));
        return new AcmgSpecificationSelector(mapper, c, adapter.canonicalSha256(c), mapper.createArrayNode().add(policy))
                .select(context, policy.path("criterionIds").path(0).asText()).receipt();
    }
    ObjectNode run(Fixture f, ObjectNode policy, ObjectNode bp4) {
        return runtime.evaluate(f.context(), f.candidate(), policy, selection(f.context(), policy, "SPECIALIZED_IMPLEMENTED"), f.observation(), bp4);
    }
    void assertStatus(String expected, ObjectNode result) {
        assertEquals("EVALUATED", result.path("status").asText(), result.toPrettyString());
        assertEquals(expected, result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(0, result.path("providerCalls").asInt()); assertFalse(result.path("classificationEnabled").asBoolean());
    }
    void assertRefused(ObjectNode result) {
        assertEquals("NOT_EVALUATED", result.path("status").asText(), result.toPrettyString());
        assertEquals("UNKNOWN", result.at("/criterionApplicability/status").asText());
        assertEquals(0, result.path("providerCalls").asInt()); assertEquals(0, result.path("evaluatorCalls").asInt());
    }
    void seal(ObjectNode result) { result.remove("resultId"); result.put("resultId", "prediction-result-sha256-" + adapter.canonicalSha256(result)); }
    record Fixture(VariantInterpretation candidate, ObjectNode context, ObjectNode observation) { }
}
