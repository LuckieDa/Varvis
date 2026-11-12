package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import static org.junit.jupiter.api.Assertions.*;

/** Actual retained stable57 saved SYNTHETIC service records. No provider/producer is executed or qualification restored. */
@EnabledIfSystemProperty(named="varvis.test.saved-clinical-dir",matches=".+")
class AcmgClinicalDetailProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode saved, privateData;
    private static final String CONTEXT = "/candidates/0/attempts/0/localBatch/criteria/0/details/clinicalContext";
    @BeforeEach void actualSavedFixture() throws Exception {
        Path directory = Path.of(System.getProperty("varvis.test.saved-clinical-dir"));
        saved = original(directory, "full.json", "03e61be73799dc514d1bc974e1da4f516c5607ac26fa3c4429db32233985c1ad");
        privateData = original(directory, "private.json", "9f821465b622fd276dc1f360bb6303d2af01de6f4e590c2c95ebfa592122de6c");
    }
    private ObjectNode original(Path directory, String file, String pin) throws Exception {
        return originalNamed(directory, "stable-57d3-" + file + ".gz", pin);
    }
    private ObjectNode originalNamed(Path directory, String file, String pin) throws Exception {
        try (var input = new GZIPInputStream(Files.newInputStream(directory.resolve(file)))) {
            byte[] bytes = input.readNBytes(4_000_001); assertTrue(bytes.length <= 4_000_000);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8); assertEquals(pin, AcmgBusinessStore.sha(text));
            return (ObjectNode) mapper.readTree(bytes);
        }
    }
    private void nativeSaved(String kind) throws Exception {
        String[] pins = switch (kind) {
            case "alias" -> new String[]{"fa27bc850e1dff92249ef83c91f3498298da8ea6756a92e3cadbe3e01befaead", "f8aafb570ed5194a12031113da56b049d211830452653839d2995bacc20a3b58"};
            case "partial" -> new String[]{"1757f32016ad65959a4132e3c8b2c335b8184a1a1ec0223cd0e3b8daf8080589", "3ca33655fb895f7d6737b4a25c7d7f7367c7859e0dde22841a961c7dfce69ca2"};
            case "decimal" -> new String[]{"b3cda16385296e7290c4814049d07ff227cfee7001e4dcb8a24cb8118eb42488", "7bc4517ce09d9b5a59ad925acd68851e05626abcf79aff1df99f10cb4e292f8c"};
            case "age" -> new String[]{"d34b46355345d8bdb8fe735f3c628543e1f2429d9b1225daac721058c3952f78", "fb084c7fa01dc2ee0905f88dd1cdb1a09fe9fe0803456a57671a8a3e42c0e606"};
            default -> throw new AssertionError(kind);
        };
        var directory = Path.of(System.getProperty("varvis.test.saved-clinical-dir"));
        saved = originalNamed(directory, "native-a0-" + kind + "-full.json.gz", pins[0]);
        privateData = originalNamed(directory, "native-a0-" + kind + "-private.json.gz", pins[1]);
    }
    private ObjectNode enriched() {
        return AcmgEvidenceDetailProjection.enrich(mapper, saved, privateData.path("localBatch"),
                privateData.path("currentClinicalInventory"), saved.path("currentClinicalEvidence"));
    }
    @Test void exactStableSavedAttemptProjectsOnlyTheLinkedMemberAndPreservesAllInputsAndJudgment() throws Exception {
        String before = saved.toString(), privateBefore = privateData.toString(); var view = enriched(); var details = view.at(CONTEXT);
        assertEquals("CURRENT_SAMPLE_LINKED", details.path("status").asText(), () -> details.toPrettyString());
        assertEquals(2, details.at("/member/features").size()); assertEquals("本次对应病例", details.at("/member/alias").asText());
        assertEquals("P6M", details.at("/member/features/0/onset/iso8601duration").asText());
        assertEquals("FEATURE_ONSET", details.at("/member/features/0/onset/semanticRole").asText());
        assertEquals("P20Y", details.at("/member/lastEncounter/iso8601duration").asText());
        assertEquals("LAST_ENCOUNTER", details.at("/member/lastEncounter/semanticRole").asText());
        assertEquals("UNKNOWN", details.at("/member/features/0/assessmentAgeStatus").asText());
        assertEquals("20", details.at("/review/independentAssessmentAge/value").asText());
        assertEquals("QUALIFIED_CLINICAL_ASSESSMENT", details.at("/review/independentAssessmentAge/semanticRole").asText());
        assertTrue(details.at("/review/missing").isEmpty()); assertFalse(details.has("comparisons"));
        assertEquals("0", details.at("/contribution/pp4Points").asText()); assertEquals("NOT_SELECTED", details.at("/contribution/terminalKind").asText());
        assertEquals(before, saved.toString()); assertEquals(privateBefore, privateData.toString());
        assertEquals(saved.at("/candidates/0/attempts/0/localBatch/criteria/0/status"), view.at("/candidates/0/attempts/0/localBatch/criteria/0/status"));
        var withoutExtension = view.deepCopy(); ((ObjectNode) withoutExtension.at("/candidates/0/attempts/0/localBatch/criteria/0/details")).remove("clinicalContext");
        assertEquals(AcmgEvidenceDetailProjection.enrich(mapper, saved, privateData.path("localBatch")), withoutExtension);
        String text = details.toString();
        for (String forbidden : List.of("rawBindings", "base64", "recordedFileUriSha256", "vcfSampleId", "personId", "subjectId", "authorDisease", "SOLVED", "genotype", "file:", "D:\\")) assertFalse(text.contains(forbidden), forbidden);
        assertTrue(text.length() < 12000);
        String directory = System.getProperty("varvis.clinical.detail.artifacts", "");
        if (!directory.isBlank()) { Files.createDirectories(Path.of(directory)); mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(directory).resolve("clinical-context.json").toFile(), details); }
    }
    @ParameterizedTest @ValueSource(strings={"run", "session", "context", "candidate", "hypothesis", "modulePin", "coveragePin", "sourcePin", "duplicateInventory", "duplicatePrivateCriterion"})
    void crossAttemptOrBrokenSavedBindingCannotAttachClinicalFacts(String mutation) {
        switch (mutation) {
            case "run" -> saved.put("runId", "another-run");
            case "session" -> ((ObjectNode) saved.at("/currentClinicalEvidence/sessionIdentity")).put("evaluationId", "another-session");
            case "context" -> ((ObjectNode) saved.at("/candidates/0/attempts/0/context/disease")).put("mondoId", "MONDO:9999999");
            case "candidate" -> ((ObjectNode) saved.at("/candidates/0")).put("candidateId", "another-candidate");
            case "hypothesis" -> ((ObjectNode) saved.at("/candidates/0/attempts/0")).put("hypothesisId", "another-background");
            case "modulePin" -> ((ObjectNode) saved.at("/candidates/0/attempts/0/localBatch/criteria/0/details")).put("originalResultSha256", "b".repeat(64));
            case "coveragePin" -> ((ObjectNode) saved.at("/candidates/0/attempts/0/localBatch/criteria/0/details/pp1Facts/evaluation/ledger")).put("inventoryProvenanceSha256", "b".repeat(64));
            case "sourcePin" -> ((ObjectNode) saved.at("/currentClinicalEvidence/nativeClinicalDetails/source")).put("factsSha256", "b".repeat(64));
            case "duplicateInventory" -> ((ArrayNode) privateData.at("/currentClinicalInventory/attempts")).add(privateData.at("/currentClinicalInventory/attempts/0").deepCopy());
            case "duplicatePrivateCriterion" -> ((ArrayNode) privateData.at("/localBatch/results/0/localBatch/criteria")).add(privateData.at("/localBatch/results/0/localBatch/criteria/0").deepCopy());
            default -> throw new AssertionError(mutation);
        }
        assertTrue(enriched().at(CONTEXT).isMissingNode(), mutation);
    }
    @ParameterizedTest @ValueSource(strings={"otherPerson", "wrongSample", "duplicateMember", "duplicateAssociation"})
    void unresolvedNativeIdentityDoesNotAttachAnotherPersonOrAnyMemberFields(String mutation) {
        var details = (ObjectNode) saved.at("/currentClinicalEvidence/nativeClinicalDetails");
        switch (mutation) {
            case "otherPerson" -> ((ObjectNode) details.at("/members/0")).put("subjectId", "foreign-person");
            case "wrongSample" -> ((ObjectNode) details.at("/sampleAssociations/0")).put("biosampleId", "foreign-biosample");
            case "duplicateMember" -> ((ArrayNode) details.path("members")).add(details.at("/members/0").deepCopy());
            case "duplicateAssociation" -> ((ArrayNode) details.path("sampleAssociations")).add(details.at("/sampleAssociations/0").deepCopy());
            default -> throw new AssertionError(mutation);
        }
        var projected = enriched().at(CONTEXT);
        assertFalse(projected.path("member").isObject()); assertFalse(projected.at("/review/independentAssessmentAge").isObject());
        assertNotEquals("CURRENT_SAMPLE_LINKED", projected.path("status").asText());
    }
    @Test void memberSelectionIsUniqueAndNotArrayOrderAndLiteralUriHashDoesNotReplaceNormalizedIdentity() {
        var details = (ObjectNode) saved.at("/currentClinicalEvidence/nativeClinicalDetails");
        var foreign = ((ObjectNode) details.at("/members/0")).deepCopy().put("subjectId", "foreign-person");
        ((ObjectNode) foreign.at("/phenotypicFeatures/0")).put("featureId", "HP:9999999"); ((ArrayNode) details.path("members")).insert(0, foreign);
        ((ObjectNode) details.at("/sampleAssociations/0")).put("recordedFileUriSha256", "e".repeat(64));
        var projected = enriched().at(CONTEXT);
        assertEquals("CURRENT_SAMPLE_LINKED", projected.path("status").asText());
        assertEquals("HP:0001250", projected.at("/member/features/0/id").asText());
        assertFalse(projected.toString().contains("HP:9999999"));
    }
    @Test void noNativeHistoryKeepsTheOriginalThreeArgumentBehaviorAndFingerprint() {
        ((ObjectNode) saved.path("currentClinicalEvidence")).remove("nativeClinicalDetails");
        var before = saved.toString(); var views = new AcmgResultViews(); String source = AcmgBusinessStore.sha(before);
        var index = views.readWithDetails(() -> "same-saved-file", () -> new AcmgResultViews.VerifiedResult(saved,
                full -> AcmgEvidenceDetailProjection.enrich(mapper, full, privateData.path("localBatch"), privateData.path("currentClinicalInventory"), saved.path("currentClinicalEvidence"))), null, 0, null);
        assertEquals(source, index.path("sourceViewSha256").asText());
        assertTrue(enriched().at(CONTEXT).isMissingNode()); assertEquals(before, saved.toString());
    }
    @Test void nativeDetailsStayOutOfIndexAndCachedBackgroundUsesTheUnmodifiedSavedFingerprint() {
        String before = saved.toString(), source = AcmgBusinessStore.sha(before); var views = new AcmgResultViews();
        var index = views.readWithDetails(() -> "same-native-saved-file", () -> new AcmgResultViews.VerifiedResult(saved,
                full -> AcmgEvidenceDetailProjection.enrich(mapper, full, privateData.path("localBatch"), privateData.path("currentClinicalInventory"), saved.path("currentClinicalEvidence"))), null, 0, null);
        assertEquals(source, index.path("sourceViewSha256").asText()); assertFalse(index.toString().contains("saved-clinical-detail-v1"));
        String candidate = saved.at("/candidates/0/candidateId").asText();
        var background = views.readWithDetails(() -> "same-native-saved-file", () -> { throw new AssertionError("History must reuse the verified cached projection"); }, candidate, 0, source);
        assertEquals(source, background.path("sourceViewSha256").asText());
        assertEquals("CURRENT_SAMPLE_LINKED", background.at(CONTEXT + "/status").asText()); assertEquals(before, saved.toString());
    }
    @Test void fullRequirementsAreNotInventedMissingFactsAndUnknownContributionNeverDefaultsToZero() {
        var out = mapper.createObjectNode(); var qualification = mapper.createObjectNode().put("status", "PARTIAL_OR_CONFLICT");
        qualification.putArray("requiredMaterial").add("one").add("two").add("three").add("four").add("five");
        AcmgClinicalDetailProjection.review(mapper, out, qualification);
        assertEquals(5, out.at("/review/requirements").size()); assertTrue(out.at("/review/missing").isEmpty()); assertFalse(out.has("comparisons"));
        var evaluation = mapper.createObjectNode(); evaluation.putObject("ledger").put("status", "UNKNOWN").putNull("pp4Points");
        var receipt = mapper.createObjectNode().put("complete", true).put("finalPoints", "0").put("kind", "NOT_SELECTED");
        receipt.putObject("currentAdoption").put("complete", true).put("awardedPoints", "0").put("kind", "NOT_SELECTED");
        receipt.putObject("currentUseGrant").putArray("reservedEventIds").add("reserved-is-not-awarded");
        AcmgClinicalDetailProjection.contribution(mapper, out, evaluation, receipt, mapper.createObjectNode().put("status", "COMPLETE"));
        assertTrue(out.at("/contribution/pp4Points").isNull());
        var unsupported = mapper.createObjectNode().put("timestamp", "2020-01-01T00:00:00Z");
        assertEquals("UNKNOWN", AcmgClinicalDetailProjection.time(mapper, unsupported, "FEATURE_ONSET").path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings={"alias", "partial", "decimal"})
    void actualNewSavedSourcesKeepOnlyTheLinkedCoreAndLeaveEveryScientificValueUntouched(String kind) throws Exception {
        nativeSaved(kind); String before = saved.toString(), privateBefore = privateData.toString();
        var view = enriched(); var core = view.at(CONTEXT);
        assertEquals("CURRENT_SAMPLE_LINKED", core.path("status").asText(), core::toPrettyString);
        assertEquals(2, core.at("/member/features").size()); assertEquals("20", core.at("/review/independentAssessmentAge/value").asText());
        assertTrue(core.at("/review/missing").isEmpty()); assertEquals("0", core.at("/contribution/pp4Points").asText());
        if (kind.equals("alias")) {
            assertEquals(privateData.at("/currentClinicalInventory/attempts/0/terminalReceipt/material/sourceIdentityBridge/identitySha256"), core.at("/binding/sourceIdentityBridgeSha256"));
            assertNotEquals(privateData.at("/currentClinicalInventory/attempts/0/terminalReceipt/material/sourceIdentityBridge/sourcePersonId"), saved.at("/candidates/0/attempts/0/context/patientContext/patientContextId"));
        }
        if (kind.equals("partial")) assertTrue(core.at("/review/conditions").isMissingNode());
        else assertEquals(5, core.at("/review/conditions").size());
        for (String forbidden : List.of("source-clinical-person", "medicalActions", "measurements", "authorDisease", "sourceIdentityBridge\":", "rawBindings", "file:"))
            assertFalse(core.toString().contains(forbidden), forbidden);
        var plain = view.deepCopy(); ((ObjectNode) plain.at("/candidates/0/attempts/0/localBatch/criteria/0/details")).remove("clinicalContext");
        assertEquals(AcmgEvidenceDetailProjection.enrich(mapper, saved, privateData.path("localBatch")), plain);
        assertEquals(before, saved.toString()); assertEquals(privateBefore, privateData.toString());
        String directory = System.getProperty("varvis.clinical.detail.artifacts", "");
        if (!directory.isBlank()) { Files.createDirectories(Path.of(directory)); mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(directory).resolve("native-" + kind + "-clinical-context.json").toFile(), core); }
    }
    @ParameterizedTest @ValueSource(strings={"materialBridge", "grantBridge", "session", "context", "sourceLink", "call", "otherMember", "noGrantTechnicalOnly", "reviewSourcePin"})
    void namespaceBridgeCannotBorrowAnotherSourceScopeOrPrivateTechnicalIdentity(String mutation) throws Exception {
        nativeSaved("alias"); var receipt = (ObjectNode) privateData.at("/currentClinicalInventory/attempts/0/terminalReceipt");
        var original = (ObjectNode) saved.at("/currentClinicalEvidence/materialReferences/0/originals/nativeFacts/sourceIdentityBridge");
        switch (mutation) {
            case "materialBridge" -> original.put("identitySha256", "0".repeat(64));
            case "grantBridge" -> ((ObjectNode) receipt.at("/currentUseGrant/nativeClinicalIdentity")).put("identitySha256", "0".repeat(64));
            case "session" -> original.put("currentSessionIdentitySha256", "0".repeat(64));
            case "context" -> original.put("currentPatientContextId", "another-current-patient");
            case "sourceLink" -> ((ObjectNode) original.path("sourceSampleLink")).put("personId", "foreign-source-person");
            case "call" -> ((ObjectNode) original.at("/nativeCallProof/matchedRecord")).put("position", 999);
            case "otherMember" -> ((ObjectNode) saved.at("/currentClinicalEvidence/nativeClinicalDetails/members/0")).put("subjectId", "another-source-member");
            case "noGrantTechnicalOnly" -> { assertTrue(privateData.at("/currentClinicalUse/attempts/0/nativeClinicalIdentity").isObject()); receipt.remove("currentUseGrant"); }
            case "reviewSourcePin" -> ((ObjectNode) saved.at("/currentClinicalEvidence/nativeClinicalReviewFacts")).put("sourceRawSha256", "0".repeat(64));
            default -> throw new AssertionError(mutation);
        }
        var core = enriched().at(CONTEXT); assertNotEquals("CURRENT_SAMPLE_LINKED", core.path("status").asText(), mutation);
        assertFalse(core.path("member").isObject()); assertFalse(core.at("/review/independentAssessmentAge").isObject());
    }
    @Test void obligationMetadataSeparatesUnreviewedMissingAndKnownUnsatisfiedConditions() throws Exception {
        nativeSaved("alias");
        // Display contract only: these modified statuses do not represent a new scientific or persisted failed run.
        var qualification = ((ObjectNode) privateData.at("/currentClinicalInventory/attempts/0/terminalReceipt/qualification")).deepCopy();
        var obligations = (ArrayNode) qualification.at("/nativeClinicalSourceReview/obligations");
        ((ObjectNode) obligations.get(0)).put("status", "NOT_REVIEWED");
        ((ObjectNode) obligations.get(1)).put("status", "NOT_SATISFIED").put("reasonCode", "PP4_NATIVE_AGE_SCOPE_MISMATCH");
        ((ObjectNode) obligations.get(2)).put("status", "NOT_ESTABLISHED");
        ((ObjectNode) obligations.get(3)).put("status", "NOT_ADMITTED_CIRCULAR_BASIS");
        qualification.put("reasonCode", "PP4_NATIVE_AGE_SCOPE_MISMATCH");
        var gaps = qualification.putArray("qualificationGaps");
        gaps.addObject().put("kind", "APPLICABLE_CONDITION_NOT_SATISFIED").put("required", "eligible assessment age").put("available", "recorded age3").put("gap", "outside range");
        gaps.addObject().put("kind", "SOURCE_REVIEW_CONDITIONS_NOT_ESTABLISHED").put("required", "independent review").put("available", "review not established");
        gaps.addObject().put("kind", "CIRCULAR_SOURCE_BASIS_NOT_ADMITTED").put("required", "independent basis").put("available", "circular basis");
        var fields = qualification.putObject("availableSourceInputs").putObject("literalOriginalFields");
        var original = saved.at("/currentClinicalEvidence/materialReferences/0/originals/nativeFacts");
        fields.putArray("CLINICAL:case").add(original.path("case").deepCopy()); ((ObjectNode) fields.at("/CLINICAL:case/0")).put("age_years", "3");
        fields.putArray("DEFINITION:definition").add(original.path("definition").deepCopy());
        var out = mapper.createObjectNode(); AcmgClinicalDetailProjection.review(mapper, out, qualification);
        AcmgClinicalDetailProjection.nativeReview(out, qualification, saved.path("currentClinicalEvidence"));
        assertEquals(1, out.at("/review/missing").size()); assertEquals(5, out.at("/review/conditions").size());
        assertEquals("NOT_REVIEWED", out.at("/review/conditions/0/status").asText());
        assertTrue(out.path("comparisons").toString().contains("5–60")); assertTrue(out.path("comparisons").toString().contains("3 岁"));
        assertFalse(out.has("member")); assertTrue(out.at("/review/independentAssessmentAge").isMissingNode());
    }
    @Test void actualPersistedAgeMismatchKeepsSourceOnlyAndDisplaysKnownThreeVersusFiveWithoutInventingMissingFacts() throws Exception {
        nativeSaved("age"); String before = saved.toString(), privateBefore = privateData.toString();
        var receipt = privateData.at("/currentClinicalInventory/attempts/0/terminalReceipt");
        assertFalse(receipt.has("material")); assertFalse(receipt.has("currentUseGrant")); assertFalse(receipt.has("coreExecution"));
        assertTrue(privateData.at("/currentClinicalUse/attempts/0/nativeClinicalIdentity").isObject());
        assertEquals("P80Y", saved.at("/currentClinicalEvidence/nativeClinicalDetails/members/0/lastEncounterAgeSource/recordedTimeElement/age/iso8601duration").asText());
        var view = enriched(); var core = view.at(CONTEXT);
        assertEquals("SOURCE_ONLY", core.path("status").asText(), core::toPrettyString);
        assertEquals("REVIEW_ONLY_SOURCE", core.at("/binding/mode").asText());
        assertEquals(privateData.at("/localBatch/modules/pp1/results/0/contributionAudit/snapshotSha256"), core.at("/binding/reviewAuditSha256"));
        assertTrue(core.at("/binding/contributionSnapshotSha256").isMissingNode());
        assertTrue(core.at("/binding/contributionCoverageProvenanceSha256").isMissingNode());
        assertFalse(core.has("member")); assertTrue(core.at("/review/independentAssessmentAge").isMissingNode());
        assertEquals("UNKNOWN", core.at("/contribution/ledgerStatus").asText()); assertTrue(core.at("/contribution/pp4Points").isNull());
        assertTrue(core.at("/contribution/remainingLocusCapacity").isMissingNode());
        assertTrue(core.at("/review/missing").isEmpty()); int notReviewed = 0, notSatisfied = 0;
        for (var condition : core.at("/review/conditions")) {
            if ("NOT_REVIEWED".equals(condition.path("status").asText())) notReviewed++;
            if ("NOT_SATISFIED".equals(condition.path("status").asText())) {
                assertEquals("COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE", condition.path("condition").asText()); notSatisfied++;
            }
        }
        assertEquals(4, notReviewed); assertEquals(1, notSatisfied);
        var comparison = core.path("comparisons").findValuesAsText("requirement"); assertTrue(comparison.stream().anyMatch(text -> text.contains("5–60")));
        assertTrue(core.path("comparisons").findValuesAsText("known").stream().anyMatch(text -> text.contains("3 岁")));
        assertFalse(core.toString().contains("80 岁")); assertFalse(core.toString().contains("P80Y"));
        var plain = view.deepCopy(); ((ObjectNode) plain.at("/candidates/0/attempts/0/localBatch/criteria/0/details")).remove("clinicalContext");
        assertEquals(AcmgEvidenceDetailProjection.enrich(mapper, saved, privateData.path("localBatch")), plain);
        assertEquals(before, saved.toString()); assertEquals(privateBefore, privateData.toString());
        String directory = System.getProperty("varvis.clinical.detail.artifacts", "");
        if (!directory.isBlank()) { Files.createDirectories(Path.of(directory)); mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(directory).resolve("native-age-clinical-context.json").toFile(), core); }
    }
    @ParameterizedTest @ValueSource(strings={"audit", "originalPin"})
    void sourceReviewOnlyStillRejectsBrokenAuditAndOriginalBindings(String mutation) throws Exception {
        nativeSaved("age");
        if (mutation.equals("audit")) ((ObjectNode) privateData.at("/localBatch/modules/pp1/results/0/contributionAudit")).put("snapshotSha256", "0".repeat(64));
        else for (var binding : saved.at("/currentClinicalEvidence/rawBindings"))
            if ("PRIMARY".equals(binding.path("kind").asText()) && "CLINICAL".equals(binding.path("role").asText()))
                ((ObjectNode) binding.path("artifact")).put("rawSha256", "0".repeat(64));
        assertTrue(enriched().at(CONTEXT).isMissingNode());
    }
}
