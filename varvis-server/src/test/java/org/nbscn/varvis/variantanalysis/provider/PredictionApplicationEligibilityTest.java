package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** TEST_ONLY end-to-end in-memory supply -> validation -> selector -> eligibility. No IO services. */
class PredictionApplicationEligibilityTest {
    @Test void twoFictionalGenesUseSharedTemplateButExactPolicyEnvelopes() throws Exception {
        var f = new PredictionEligibilityFixtures();
        assertEquals(f.template(0), f.template(1));
        assertNotEquals(f.policy(0).path("scope"), f.policy(1).path("scope"));
        assertEquals("", f.input().issue());
        assertStatus(f.result(0, "BP4"), "PRECONDITIONS_PASS");
        assertStatus(f.result(1, "PP3"), "PRECONDITIONS_PASS");
        assertStatus(f.result(0, "PP3"), "NO_SUPPORT");
        assertStatus(f.result(1, "BP4"), "NO_SUPPORT");
        assertEquals("SUPPORTING", f.result(0, "BP4").path("proposedApplicationStrength").asText());
        assertEquals("GENERAL_POLICY_SELECTED", f.result(0, "BP4").at("/selector/outcome").asText());
    }

    @Test void sameGeneDifferentContextNeverOverwritesOrBorrowsScope() throws Exception {
        var f = new PredictionEligibilityFixtures();
        ObjectNode h = ((ObjectNode) f.candidate(0).at("/hypotheses/0")).deepCopy(); h.put("hypothesisId", "other-background");
        ObjectNode c = (ObjectNode) h.path("context");
        ((ObjectNode) c.path("disease")).put("mondoId", "MONDO:9999999"); c.put("moi", "AUTOSOMAL_RECESSIVE");
        c.put("contextId", f.hash.canonicalContextId(c));
        f.candidate(0).withArray("hypotheses").add(h); f.seal();
        var results = f.assess(); assertEquals(6, results.size());
        assertStatus(results.get(1).json(), "PRECONDITIONS_PASS");
        assertEquals("CLAUSE_SEARCH_SCOPE_NOT_COVERED", results.get(2).json().path("reasonCode").asText());
        assertNotEquals(results.get(0).json().path("assessmentId"), results.get(2).json().path("assessmentId"));
    }

    @ParameterizedTest @ValueSource(strings = {"SPECIALIZED_IMPLEMENTED", "SPECIALIZED_UNIMPLEMENTED", "SPECIALIZED_VERSION_CONFLICT", "SPECIALIZED_UNRESOLVED"})
    void strictSelectorDispositions(String disposition) throws Exception {
        var f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/catalog/entries/0")).put("disposition", disposition); f.seal();
        ObjectNode out = f.result(0, "PP3");
        if ("SPECIALIZED_IMPLEMENTED".equals(disposition)) {
            assertEquals("SPECIALIZED_POLICY_SELECTED", out.at("/selector/outcome").asText()); assertStatus(out, "NO_SUPPORT");
        } else {
            assertFalse(out.path("reasonCode").asText().isBlank()); assertNotEquals("PRECONDITIONS_PASS", out.path("status").asText());
            assertEquals("NOT_CHECKED", out.at("/stages/3/status").asText());
        }
    }

    @Test void uncoveredConflictingAndUnreviewedPoliciesDoNotFallBack() throws Exception {
        var f = new PredictionEligibilityFixtures(); f.settings.withObject("/catalog").putArray("entries"); f.seal();
        assertReason(f.result(0, "BP4"), "CLAUSE_SEARCH_SCOPE_NOT_COVERED");
        f = new PredictionEligibilityFixtures(); f.settings.withObject("/catalog").withArray("entries").add(((ObjectNode) f.settings.at("/catalog/entries/0")).deepCopy().put("entryId", "TEST_ONLY_OTHER_MATCH")); f.seal();
        assertReason(f.result(0, "PP3"), "CLAUSE_SELECTION_CONFLICT");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/catalog/entries/0/review")).put("status", "PENDING"); f.seal();
        assertReason(f.result(0, "PP3"), "CLAUSE_REVIEW_OR_SOURCE_IDENTITY_MISSING");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/catalog/entries/0/policyBinding")).put("policySha256", "0".repeat(64)); f.sealBytes();
        assertReason(f.result(0, "PP3"), "POLICY_INTEGRITY_MISMATCH");
    }

    @Test void missingHypothesisIsReportedAndMissingVerificationIsNotInferred() throws Exception {
        var f = new PredictionEligibilityFixtures(); f.candidate(0).putArray("hypotheses"); f.seal();
        assertEquals(4, f.assess().size()); assertReason(f.result(0, "PP3"), "EXPLICIT_CONTEXT_MISSING");
        assertEquals("NOT_CHECKED", f.result(0, "PP3").at("/stages/2/status").asText());
        assertFalse(f.result(0, "PP3").has("selector"));
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.candidate(0).at("/hypotheses/0")).remove("reviewId"); f.seal();
        assertReason(f.result(0, "BP4"), "EXPLICIT_CONTEXT_VERIFICATION_MISSING");
    }

    @Test void transcriptMismatchRemainsSpecificAndDoesNotSelectByScore() throws Exception {
        var f = new PredictionEligibilityFixtures(); ObjectNode c = (ObjectNode) f.candidate(0).at("/hypotheses/0/context");
        ((ObjectNode) c.path("transcript")).put("version", 9); c.put("contextId", f.hash.canonicalContextId(c)); f.seal();
        assertReason(f.result(0, "BP4"), "CONTEXT_TRANSCRIPT_MISMATCH");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.candidate(0).at("/hypotheses/0/context")).put("rawScore", "1"); f.seal();
        assertReason(f.result(0, "BP4"), "CONTEXT_IDENTITY_INCOMPLETE_OR_INVALID");
    }

    @Test void sourceTreeBytesAndFixedSupplyAreSeparateChecks() throws Exception {
        var f = new PredictionEligibilityFixtures(); ObjectNode tree = f.docs.get("run").deepCopy(); tree.put("runId", "forged");
        f.artifacts.put("run", new SavedPredictionApplicationInputs.Artifact(f.mapper.writeValueAsBytes(f.docs.get("run")), tree));
        assertReason(f.assess().get(0).json(), "SOURCE_TREE_BYTES_MISMATCH");
        f.artifacts.put("run", new SavedPredictionApplicationInputs.Artifact(f.mapper.writeValueAsBytes(tree), tree));
        assertReason(f.assess().get(0).json(), "SOURCE_BYTES_DIGEST_MISMATCH");
        f = new PredictionEligibilityFixtures(); f.settings.put("VERIFIED", true);
        assertReason(f.assess().get(0).json(), "INTERNAL_SUPPLY_SETTINGS_MISMATCH");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/adoptions/TEST_ONLY_0")).put("reviewId", "self-replacement");
        assertReason(f.assess().get(0).json(), "INTERNAL_SUPPLY_SETTINGS_MISMATCH");
    }

    @ParameterizedTest @ValueSource(strings = {"parent", "request", "private", "profile", "order"})
    void containerCorrespondenceIsCheckedBeyondSelfConsistentHashes(String kind) throws Exception {
        var f = new PredictionEligibilityFixtures();
        switch (kind) {
            case "parent" -> f.docs.get("run").put("sourceRunId", "wrong-parent");
            case "request" -> f.docs.get("run").put("requestSha256", "0".repeat(64));
            case "private" -> f.docs.get("run").put("privateArtifactSha256", "0".repeat(64));
            case "profile" -> ((ObjectNode) f.docs.get("run").path("executionProfile")).put("version", "other");
            case "order" -> f.docs.get("run").withArray("candidateIds").set(0, f.mapper.getNodeFactory().textNode("wrong-candidate"));
            default -> fail();
        }
        f.sealBytes(); assertStatus(f.assess().get(0).json(), "BLOCKED");
        assertEquals("BATCH", f.assess().get(0).json().path("criterionId").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"candidate", "annotation", "model", "calibration", "row", "parentObservation"})
    void badCandidateIsolatedFromTheOtherCandidate(String kind) throws Exception {
        var f = new PredictionEligibilityFixtures(); ObjectNode p = f.prediction(0);
        switch (kind) {
            case "candidate" -> ((ObjectNode) p.path("selection")).put("candidateId", "other");
            case "annotation" -> ((ObjectNode) p.path("selection")).put("annotationSourceSha256", "0".repeat(64));
            case "model" -> ((ObjectNode) f.candidate(0).at("/evidence/PROTEIN")).put("model", "wrong");
            case "calibration" -> ((ObjectNode) p.path("calibration")).put("rawScore", "0.9");
            case "row" -> ((ObjectNode) p.path("association")).putArray("matchedRowIndices").add(9);
            case "parentObservation" -> { ((ObjectNode) p.path("observation")).put("reasonCode", "changed"); f.digest((ObjectNode) p.path("observation"), "observationSha256", ""); }
            default -> fail();
        }
        f.seal(); assertStatus(f.result(0, "PP3"), "BLOCKED"); assertStatus(f.result(1, "PP3"), "PRECONDITIONS_PASS");
        assertFalse(f.result(0, "PP3").has("sourceCalibratedStrength"));
    }

    @Test void immutableSnapshotsAndReturnedReceiptsCannotBeBackMutated() throws Exception {
        var f = new PredictionEligibilityFixtures(); var input = f.input(); var engine = new PredictionApplicationEligibility(f.mapper);
        var before = engine.assess(input); ObjectNode saved = before.get(1).json();
        f.settings.removeAll(); f.docs.get("run").removeAll(); input.settings().removeAll(); ((ObjectNode) input.document("run")).removeAll(); input.identity().removeAll();
        before.get(1).json().put("status", "FORGED");
        assertEquals(saved, before.get(1).json()); assertEquals(saved, engine.assess(input).get(1).json());
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
    }

    @Test void preselectionAdoptionTemplateAndCalibrationBindingAreRequired() throws Exception {
        var f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/modelProofs/TEST_ONLY_0")).remove("sourceSha256"); f.seal();
        assertReason(f.result(0, "BP4"), "MODEL_SELECTION_PROOF_MISSING");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/adoptions/TEST_ONLY_0")).put("status", "PENDING"); f.seal();
        assertReason(f.result(0, "BP4"), "LOCAL_APPLICATION_ADOPTION_REQUIRED");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.policy(0).path("application")).put("templateSha256", "0".repeat(64));
        // Rebind policy envelope but deliberately leave its internal template hash wrong.
        for (JsonNode e : f.settings.at("/catalog/entries")) if (e.at("/policyBinding/policyId").equals(f.policy(0).path("policyId")))
            ((ObjectNode) e.path("policyBinding")).put("policySha256", f.hash.canonicalSha256(f.policy(0)));
        f.sealBytes(); assertReason(f.result(0, "BP4"), "APPLICATION_TEMPLATE_BINDING_INVALID");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.template(0).at("/mechanisms/PROTEIN")).put("calibrationVersion", "9"); f.seal();
        assertReason(f.result(0, "BP4"), "SELECTED_CALIBRATION_CONTRACT_MISMATCH");
    }

    @Test void compatibilityRegistrationAndMultipleRowsAreNotExtended() throws Exception {
        var f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/compatibilities/TEST_ONLY_COMPAT")).putArray("observationSha256s"); f.seal();
        assertReason(f.result(0, "BP4"), "COMPATIBILITY_RECORD_OUTSIDE_ADOPTED_SCOPE");
        f = new PredictionEligibilityFixtures(); ObjectNode obs = (ObjectNode) f.prediction(0).path("observation");
        obs.withArray("rows").add(obs.at("/rows/0").deepCopy()); f.digest(obs, "observationSha256", "");
        ((ObjectNode) f.prediction(0).path("selection")).put("sourceObservationSha256", obs.path("observationSha256").asText());
        ((ObjectNode) f.docs.get("parent").path("candidates").get(0).at("/attempts/0/prediction")).set("observation", obs.deepCopy());
        f.seal(); assertReason(f.result(0, "BP4"), "EXACT_SINGLE_ROW_OUTSIDE_SCOPE");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/compatibilities/TEST_ONLY_COMPAT")).put("scope", "UNKNOWN_MULTIROW_AGGREGATION"); f.seal();
        assertReason(f.result(0, "BP4"), "AGGREGATION_CONTRACT_UNSUPPORTED");
    }

    @ParameterizedTest @ValueSource(strings = {"BENIGN", "HARMFUL", "UNINFORMATIVE"})
    void directionMatrixPreservesCalibrationAndInternalNoSupport(String direction) throws Exception {
        var f = new PredictionEligibilityFixtures(); f.prediction(0).set("calibration", f.standard(direction)); f.seal();
        JsonNode before = f.prediction(0).deepCopy();
        for (String criterion : List.of("PP3", "BP4")) {
            boolean pass = criterion.equals("PP3") ? direction.equals("HARMFUL") : direction.equals("BENIGN");
            ObjectNode result = f.result(0, criterion); assertStatus(result, pass ? "PRECONDITIONS_PASS" : "NO_SUPPORT");
            assertEquals(pass ? "SATISFIED" : "NOT_SATISFIED", result.path("directionGate").asText());
            assertEquals(pass ? "SUPPORTING" : "NONE", result.path("proposedApplicationStrength").asText());
        }
        assertEquals(before, f.prediction(0));
    }

    private void splice( PredictionEligibilityFixtures f, String direction, String pp3Mode, String bp4Mode) {
        f.addMechanism(0, "SPLICE", direction, f.prediction(0).at("/observation/queryAllele"));
        f.template(0).withObject("/mechanisms").set("SPLICE", f.template(0).at("/mechanisms/PROTEIN").deepCopy());
        for (String code : List.of("PP3", "BP4")) {
            ObjectNode plan = (ObjectNode) f.template(0).at("/criteria/" + code);
            plan.put("mode", code.equals("PP3") ? pp3Mode : bp4Mode).put("strengthMechanism", "PROTEIN");
            plan.putArray("mechanisms").add("PROTEIN").add("SPLICE");
        }
    }

    @Test void mixedDirectionsAreNotAutomaticallyAConflictButDualQualificationNeedsGovernance() throws Exception {
        var f = new PredictionEligibilityFixtures(); splice(f, "HARMFUL", "PRIORITY_OR", "ALL"); f.seal();
        assertStatus(f.result(0, "PP3"), "PRECONDITIONS_PASS"); assertStatus(f.result(0, "BP4"), "NO_SUPPORT");
        ((ObjectNode) f.template(0).at("/criteria/BP4")).put("mode", "PRIORITY_OR"); f.seal();
        assertReason(f.result(0, "PP3"), "JOINT_DIRECTION_ADOPTION_UNGOVERNED");
        assertStatus(f.result(0, "BP4"), "REVIEW_REQUIRED");
    }

    @Test void requiredAndOptionalMechanismsRespectTheirPlan() throws Exception {
        var f = new PredictionEligibilityFixtures(); splice(f, "BENIGN", "PRIORITY_OR", "ALL"); f.seal();
        assertStatus(f.result(0, "BP4"), "PRECONDITIONS_PASS");
        f.candidate(0).withObject("/evidence").remove("SPLICE"); f.seal();
        assertReason(f.result(0, "BP4"), "MECHANISM_NOT_QUERIED");
        assertEquals("UNKNOWN", f.result(0, "BP4").path("directionGate").asText());
        ((ObjectNode) f.template(0).at("/criteria/BP4")).put("mode", "SINGLE").putArray("mechanisms").add("PROTEIN"); f.seal();
        assertStatus(f.result(0, "BP4"), "PRECONDITIONS_PASS");
    }

    @Test void grayProteinCanUseOnlyPreplannedAlternativeWithoutSumming() throws Exception {
        var f = new PredictionEligibilityFixtures(); splice(f, "HARMFUL", "PRIORITY_OR", "ALL");
        f.prediction(0).set("calibration", f.standard("UNINFORMATIVE")); f.seal();
        assertStatus(f.result(0, "PP3"), "PRECONDITIONS_PASS");
        assertEquals("SUPPORTING", f.result(0, "PP3").path("proposedApplicationStrength").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"NO_RECORD", "UNAVAILABLE", "NOT_APPLICABLE", "UNINFORMATIVE"})
    void restrictedMissingFallbackDoesNotTreatFailuresOrGrayAsMissing(String state) throws Exception {
        var f = new PredictionEligibilityFixtures(); splice(f, "HARMFUL", "MISSING_FALLBACK", "ALL");
        ((ObjectNode) f.template(0).at("/criteria/PP3")).putArray("fallbackReasons").add("NO_RECORD");
        if (state.equals("UNINFORMATIVE")) f.prediction(0).set("calibration", f.standard(state));
        else {
            ObjectNode obs = (ObjectNode) f.prediction(0).path("observation"); obs.put("status", state); obs.putArray("rows");
            obs.putObject("execution").put("status", state.equals("NO_RECORD") ? "SUCCEEDED" : "FAILED");
            f.digest(obs, "observationSha256", "");
            ((ObjectNode) f.prediction(0).path("selection")).put("sourceObservationSha256", obs.path("observationSha256").asText());
            ((ObjectNode) f.docs.get("parent").path("candidates").get(0).at("/attempts/0/prediction")).set("observation", obs.deepCopy());
            if (state.equals("NOT_APPLICABLE")) ((ObjectNode) f.candidate(0).at("/evidence/PROTEIN")).putObject("applicability")
                    .put("status", "VERIFIED").put("reviewId", "TEST_ONLY").put("sourceSha256", "a".repeat(64));
        }
        f.seal(); ObjectNode result = f.result(0, "PP3");
        assertStatus(result, switch (state) { case "NO_RECORD" -> "UNSUPPORTED"; case "UNINFORMATIVE" -> "NO_SUPPORT"; case "NOT_APPLICABLE" -> "NOT_APPLICABLE"; default -> "BLOCKED"; });
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "partial", "unavailable", "equivalence", "stale", "internal", "identity", "observed", "external"})
    void coverageSeparatesAdoptionPartialUnknownAndDiscordance(String kind) throws Exception {
        var f = new PredictionEligibilityFixtures(); ObjectNode snapshot = (ObjectNode) f.settings.at("/effectSnapshots/TEST_ONLY_0");
        switch (kind) {
            case "partial" -> snapshot.putObject("coverage").put("PS3_ONLY", "COMPLETE");
            case "unavailable" -> snapshot.put("status", "UNAVAILABLE");
            case "equivalence" -> snapshot.remove("equivalenceScope");
            case "stale" -> snapshot.put("validThrough", "2026-09-18T00:00:00Z");
            default -> snapshot.withArray("effects").addObject().put("kind", switch (kind) { case "internal" -> "INTERNAL_EVIDENCE_CONFLICT"; case "identity" -> "IDENTITY_CONFLICT"; case "external" -> "EXTERNAL_CLASSIFICATION_DISCORDANCE"; default -> "SAME_EFFECT"; })
                    .put("referenceId", "TEST_ONLY_REFERENCE").put("sourceSha256", "b".repeat(64)).put("equivalence", "VERIFIED")
                    .put("adoption", kind.equals("observed") ? "OBSERVED_ONLY" : "ADOPTED");
        }
        f.seal(); ObjectNode result = f.result(0, "BP4");
        if (Set.of("observed", "external").contains(kind)) assertStatus(result, "PRECONDITIONS_PASS");
        else { assertNotEquals("PRECONDITIONS_PASS", result.path("status").asText()); assertEquals("NONE", result.path("proposedApplicationStrength").asText()); }
        assertFalse(result.at("/effectCoverage/externalWorldCovered").asBoolean());
        assertEquals("SATISFIED", result.path("directionGate").asText());
    }

    @Test void noSupportNeverConcealsAnIndependentCoverageGap() throws Exception {
        var f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/effectSnapshots/TEST_ONLY_0")).put("status", "UNAVAILABLE"); f.seal();
        assertReason(f.result(0, "PP3"), "EFFECT_COVERAGE_INCOMPLETE");
        assertEquals("NOT_SATISFIED", f.result(0, "PP3").path("directionGate").asText());
    }

    @Test void idsBindCriterionVersionsPurposesContextsCoverageAndOrderedPlans() throws Exception {
        var f = new PredictionEligibilityFixtures(); JsonNode original = f.result(0, "BP4").path("assessmentId");
        assertNotEquals(original, f.result(0, "PP3").path("assessmentId"));
        assertEquals(original, f.result(0, "BP4").path("assessmentId"));
        f.settings.put("readAt", "2099-01-01T00:00:00Z"); assertEquals(original, f.result(0, "BP4").path("assessmentId"));
        ((ObjectNode) f.settings.at("/effectSnapshots/TEST_ONLY_0")).put("readAt", "2099-01-01T01:00:00Z");
        assertEquals(original, f.result(0, "BP4").path("assessmentId"));
        ObjectNode reversed = f.mapper.createObjectNode(); var names = new java.util.ArrayList<String>(); f.settings.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names); for (String name : names) reversed.set(name, f.settings.path(name).deepCopy());
        var input = SavedPredictionApplicationInputs.verify(f.mapper, f.artifacts, reversed, f.roots);
        assertEquals(original, new PredictionApplicationEligibility(f.mapper).assess(input).get(1).json().path("assessmentId"));
        ((ObjectNode) f.settings.at("/effectSnapshots/TEST_ONLY_0")).put("version", "2"); f.seal();
        assertNotEquals(original, f.result(0, "BP4").path("assessmentId"));
        original = f.result(0, "BP4").path("assessmentId"); ((ObjectNode) f.settings.path("task")).put("purpose", "OTHER_PURPOSE"); f.seal();
        assertNotEquals(original, f.result(0, "BP4").path("assessmentId"));
        f = new PredictionEligibilityFixtures(); splice(f, "BENIGN", "PRIORITY_OR", "ALL"); f.seal(); original = f.result(0, "PP3").path("assessmentId");
        ((ObjectNode) f.template(0).at("/criteria/PP3")).putArray("mechanisms").add("SPLICE").add("PROTEIN"); f.seal();
        assertNotEquals(original, f.result(0, "PP3").path("assessmentId"));
    }

    @Test void taskLimitsRejectWholeBatchAndDoNotTruncate() throws Exception {
        var f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.path("task")).put("maxCandidates", 1); f.seal();
        assertEquals(1, f.assess().size()); assertReason(f.assess().get(0).json(), "TASK_CANDIDATE_LIMIT_EXCEEDED");
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.path("task")).put("maxHypothesesPerCandidate", 1);
        ObjectNode extra = ((ObjectNode) f.candidate(0).at("/hypotheses/0")).deepCopy().put("hypothesisId", "extra"); f.candidate(0).withArray("hypotheses").add(extra); f.seal();
        assertReason(f.assess().get(0).json(), "TASK_HYPOTHESIS_LIMIT_EXCEEDED");
    }

    @Test void originalRowColumnsAndProteinMustAgreeEvenAfterAllDigestsAreRebound() throws Exception {
        var f = new PredictionEligibilityFixtures();
        ObjectNode row = (ObjectNode) f.prediction(0).at("/observation/rows/0");
        row.put("sourceRow", row.path("sourceRow").asText().replace("0.5", "0.9"));
        ObjectNode observation = (ObjectNode) f.prediction(0).path("observation"); f.digest(observation, "observationSha256", "");
        ((ObjectNode) f.prediction(0).path("selection")).put("sourceObservationSha256", observation.path("observationSha256").asText());
        ((ObjectNode) f.docs.get("parent").path("candidates").get(0).at("/attempts/0/prediction")).set("observation", observation.deepCopy());
        f.seal(); assertReason(f.result(0, "BP4"), "SAVED_OBSERVATION_ROW_IDENTITY_MISMATCH");
        assertStatus(f.result(1, "PP3"), "PRECONDITIONS_PASS");
    }

    @Test void selectedCalibrationDigestCannotBeSubstitutedAndUntrustedStrengthIsNotDisplayed() throws Exception {
        var f = new PredictionEligibilityFixtures();
        ((ObjectNode) f.prediction(0).at("/calibration/savedSource")).put("calibrationCanonicalSha256", "0".repeat(64)); f.seal();
        ObjectNode result = f.result(0, "BP4"); assertReason(result, "CALIBRATION_CONTRACT_DIGEST_MISMATCH");
        assertFalse(result.path("sourceCalibratedStrength").has("PROTEIN"));
        assertEquals("NONE", result.path("proposedApplicationStrength").asText());
    }

    @Test void unknownCombinationsAndTimeoutFallbackRequireImplementationRatherThanGuessing() throws Exception {
        var f = new PredictionEligibilityFixtures(); ((ObjectNode) f.template(0).at("/criteria/PP3")).put("mode", "SCRIPT"); f.seal();
        assertStatus(f.result(0, "PP3"), "UNSUPPORTED");
        f = new PredictionEligibilityFixtures(); splice(f, "HARMFUL", "MISSING_FALLBACK", "ALL");
        ((ObjectNode) f.template(0).at("/criteria/PP3")).putArray("fallbackReasons").add("TIMEOUT"); f.seal();
        assertReason(f.result(0, "PP3"), "FALLBACK_REASON_UNSUPPORTED");
    }

    @Test void policyRevisionAndExplicitAssessmentTimeChangeSemanticIdentity() throws Exception {
        var f = new PredictionEligibilityFixtures(); JsonNode before = f.result(0, "BP4").path("assessmentId");
        f.policy(0).put("policyVersion", "2");
        ((ObjectNode) f.settings.at("/adoptions/TEST_ONLY_0")).put("policyVersion", "2");
        for (JsonNode entry : f.settings.at("/catalog/entries")) if (entry.at("/policyBinding/policyId").equals(f.policy(0).path("policyId")))
            ((ObjectNode) entry.path("policyBinding")).put("policyVersion", "2");
        f.seal(); assertStatus(f.result(0, "BP4"), "PRECONDITIONS_PASS"); assertNotEquals(before, f.result(0, "BP4").path("assessmentId"));
        before = f.result(0, "BP4").path("assessmentId");
        ((ObjectNode) f.settings.path("task")).put("assessmentAt", "2026-09-21T00:00:00Z"); f.seal();
        assertNotEquals(before, f.result(0, "BP4").path("assessmentId")); assertReason(f.result(0, "BP4"), "EFFECT_SNAPSHOT_STALE");
    }

    @Test void missingContextPreservesEarlierSavedStageFactsAndNoBackgroundBorrowing() throws Exception {
        var f = new PredictionEligibilityFixtures(); f.candidate(0).putArray("hypotheses"); f.seal();
        ObjectNode result = f.result(0, "BP4"); assertReason(result, "EXPLICIT_CONTEXT_MISSING");
        assertEquals("CALIBRATED", result.at("/savedStageFacts/PROTEIN/savedCalibrationStatus").asText());
        assertEquals("NOT_CHECKED", result.at("/stages/4/status").asText());
        assertStatus(f.result(1, "PP3"), "PRECONDITIONS_PASS");
    }

    @Test void productionTypesExposeNeitherPublicFactoriesNorIoDependencies() {
        for (Class<?> type : List.of(SavedPredictionApplicationInputs.class, PredictionApplicationEligibility.class,
                PredictionApplicationPolicy.class, PredictionCalibrationView.class, PredictionEffectCoverage.class)) {
            assertFalse(java.lang.reflect.Modifier.isPublic(type.getModifiers()));
            for (var method : type.getDeclaredMethods()) assertFalse(java.lang.reflect.Modifier.isPublic(method.getModifiers()));
            for (var field : type.getDeclaredFields()) assertFalse(field.getType().getName().matches(".*(Provider|Store|Budget|DataSource|HttpClient|Path|File)$"));
            assertEquals(0, type.getAnnotations().length);
        }
    }

    private static void assertReason(ObjectNode out, String reason) { assertEquals(reason, out.path("reasonCode").asText(), out.toPrettyString()); }
    private static void assertStatus(ObjectNode out, String status) {
        assertEquals(status, out.path("status").asText(), out.toPrettyString());
        assertEquals("NONE", out.path("appliedStrength").asText());
        for (String flag : List.of("applicationReady", "formalEnabled", "criterionEvaluationCreated")) assertFalse(out.path(flag).asBoolean());
        assertFalse(out.has("criterionEvaluations"));
    }
}
