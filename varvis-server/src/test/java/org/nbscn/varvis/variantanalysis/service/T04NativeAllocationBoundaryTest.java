package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.LocalEvidenceBatchRuntime;
import org.nbscn.varvis.variantanalysis.provider.Pp1CurrentContributionSource;
import org.nbscn.varvis.variantanalysis.provider.Pp1LiteratureJobs;
import org.nbscn.varvis.variantanalysis.provider.Pp1SelectedBatch;
import org.nbscn.varvis.variantanalysis.provider.Pp4CurrentAdoptionProducer;
import org.nbscn.varvis.variantanalysis.provider.Pp4QualifiedMaterial;
import org.nbscn.varvis.variantanalysis.provider.Pp4ServerRegisteredInputs;
import org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture;

/** Actual native capabilities, H2 claim, source modules and unchanged PP4 core.
 * Internal selected-PP4 controls are SYNTHETIC module tests, never public CURRENT18 adoption.
 * The real missing PP1 workflow remains unavailable; this fixture does not claim PP1 acceptance.
 */
@EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
class T04NativeAllocationBoundaryTest {
    private static final String PP1 = "ACMG_AMP_2015:PP1", PP4 = "ACMG_AMP_2015:PP4";
    @TempDir Path directory;

    private static final class Actual implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final T04CurrentSessionFixture fixture;
        final Pp4ServerRegisteredInputs registered;
        final Pp4CurrentAdoptionProducer producer;
        final CurrentAcmgEvaluationSession session;
        CurrentClinicalAllocation.CurrentUseSource use;
        CurrentClinicalAllocation.Grant grant;
        ObjectNode reservationBeforeCore;
        Pp4CurrentAdoptionProducer.Outcome outcome;
        Pp4QualifiedMaterial material;
        CurrentAdoptionInventory inventory;
        ObjectNode archive;
        ObjectNode runtimeResult;

        Actual(Path directory, String name, boolean selected, boolean captureInventory,
                Consumer<T04NativeOriginalFixture> mutate) throws Exception {
            fixture = new T04CurrentSessionFixture(directory.resolve("native"), "boundary-" + name, "run-" + name,
                    selected ? List.of(PP1, PP4) : List.of(PP1), true);
            try {
                var context = T04NativeOriginalFixture.context(fixture);
                var originals = new T04NativeOriginalFixture(fixture, context); originals.nativeSampleLink(fixture, context); mutate.accept(originals);
                registered = Pp4ServerRegisteredInputs.load(originals.writeServerBundle(directory.resolve("registered"), context));
                producer = new Pp4CurrentAdoptionProducer(registered);
                var factory = new Pp1CurrentContributionSource.Factory(producer);
                var pp1 = Pp1SelectedBatch.withCurrentFactory(json, Pp1LiteratureJobs.businessSource(directory.resolve("absent-pp1-workflow.json")), factory);
                var deNovo = T04NativeOriginalFixture.inheritedDeNovo(directory.resolve("actual-inherited-source"), context);
                var runtime = new LocalEvidenceBatchRuntime(json, json.createObjectNode().put("dataClass", "SYNTHETIC").put("fixture", "ACTUAL_NATIVE_PP4_BOUNDARY"),
                        null, null, null, null, null, null, null, null, null, null, null, true, deNovo, null, pp1);
                // The registered method may be explicitly claimed by an internal module
                // caller; it is deliberately absent from public dispatch/criterion rows.
                runtime.bindExecutionCriteria(List.of(PP1)); runtime.preparePp1Sources(fixture.analysisId(), json.createObjectNode());
                session = fixture.issue(context, runtime.identity());
                runtimeResult = runtime.execute(fixture.generalPrepared(), new AnalysisContext(fixture.analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES),
                        fixture.executionSourceSha256(), fixture.prepared(), session, token -> {
                    try {
                        use = CurrentClinicalAllocation.prepareAfterClaim(json, fixture.store(), session, fixture.prepared(), fixture.recovered(),
                                fixture.ranked(), registered, token);
                        factory.bindCurrentUseSource(use);
                        var verified = Pp4QualifiedMaterial.verify(registered.registration(), context);
                        if (verified.material().isPresent()) {
                            material = verified.material().orElseThrow(); grant = use.reserve(session, session.attempts().get(0), material);
                            reservationBeforeCore = grant.snapshot();
                        }
                        if (captureInventory) {
                            inventory = CurrentAdoptionInventory.capture(session, producer);
                            archive = CurrentClinicalArchive.capture(json, session, registered, inventory);
                            runtime.bindCurrentPp1(session, inventory);
                        } else outcome = producer.evaluate(session, session.attempts().get(0));
                    } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    catch (RuntimeException failure) { throw failure; }
                    catch (Exception failure) { throw new IllegalStateException("BOUNDARY_ACTUAL_NATIVE_FAILURE", failure); }
                });
            } catch (Exception | AssertionError failure) { fixture.close(); throw failure; }
        }
        @Override public void close() { fixture.close(); }
    }

    @Test void selectedInternalPp4AwardsOnlyActualCorePointsAndReservationIsNotAward() throws Exception {
        try (var actual = new Actual(directory.resolve("selected"), "selected", true, false, ignored -> { })) {
            var before = actual.reservationBeforeCore;
            assertEquals("RESERVED_NOT_AWARDED", before.path("status").asText()); assertTrue(before.path("awardedPoints").isNull());
            assertFalse(before.path("reservedFactIds").isEmpty()); assertTrue(before.path("allocatedFactIds").isEmpty());
            var receipt = actual.outcome.snapshot(); assertTrue(actual.outcome.isComplete(), receipt::toPrettyString);
            assertEquals("ADOPTED", receipt.path("kind").asText()); assertEquals("2.5", receipt.path("finalPoints").asText());
            assertFalse(receipt.path("eventIds").isEmpty()); assertFalse(receipt.path("exclusiveFactIds").isEmpty());
            var core = actual.outcome.coreExecution().orElseThrow(); assertTrue(core.matches(actual.grant));
            assertEquals("MET", core.evaluation().at("/evaluationResult/status").asText());
            assertEquals("MODERATE", core.evaluation().path("appliedStrength").asText());
            assertEquals("2.5", core.sourceResult().path("appliedPoints").asText());
            assertEquals("CORE_RESULT_ONLY", core.sourceResult().path("resultMode").asText());
            assertFalse(core.sourceResult().path("adoptionSupported").asBoolean(true)); assertFalse(core.sourceResult().path("classificationEnabled").asBoolean(true));
            assertEquals(core.sourceResultSha256(), receipt.path("sourceResultSha256").asText());
            assertEquals(core.evaluationSha256(), receipt.path("evaluationSha256").asText());
            var finished = actual.use.finish(actual.grant, core).snapshot(); assertEquals("2.5", finished.path("awardedPoints").asText());
            assertEquals(finished, actual.use.finish(actual.grant, core).snapshot(), "same native execution is idempotent");
            assertEquals(1, actual.runtimeResult.at("/results/0/localBatch/criteria").size(), "the module control does not add public PP4");
            var repeated = actual.producer.evaluate(actual.session, actual.session.attempts().get(0));
            assertFalse(repeated.isComplete()); assertTrue(repeated.snapshot().path("finalPoints").isNull());
            assertEquals("PP1_CURRENT_CLINICAL_CORE_ALREADY_FINISHED", repeated.snapshot().at("/materialDiagnostic/executionDiagnostic").asText());
            assertEquals(finished, actual.use.finish(actual.grant, core).snapshot(), "a repeated evaluator cannot award the grant twice");
        }
    }

    @Test void crossSessionAndCrossGrantCoreCannotBeFinishedOrRestoredFromJson() throws Exception {
        try (var a = new Actual(directory.resolve("a"), "a", true, false, ignored -> { });
             var b = new Actual(directory.resolve("b"), "b", true, false, ignored -> { })) {
            assertTrue(a.outcome.isComplete(), () -> a.outcome.snapshot().toPrettyString());
            assertTrue(b.outcome.isComplete(), () -> b.outcome.snapshot().toPrettyString());
            var core = a.outcome.coreExecution().orElseThrow(); assertFalse(core.matches(b.grant));
            assertEquals("PP1_CURRENT_CLINICAL_CORE_CAPABILITY_REQUIRED", assertThrows(IllegalArgumentException.class,
                    () -> b.use.finish(b.grant, core)).getMessage());
            assertEquals("PP1_CURRENT_CLINICAL_RESERVATION_SCOPE_MISMATCH", assertThrows(IllegalArgumentException.class,
                    () -> a.use.reserve(b.session, b.session.attempts().get(0), b.material)).getMessage());
            var reissued = a.fixture.reissueSameClaim(); assertFalse(a.use.matches(reissued));
            assertEquals("PP1_CURRENT_CLINICAL_RESERVATION_SCOPE_MISMATCH", assertThrows(IllegalArgumentException.class,
                    () -> a.use.reserve(reissued, reissued.attempts().get(0), a.material)).getMessage());
            for (var entry : List.of(new Object[]{a.session.snapshot(), CurrentAcmgEvaluationSession.class},
                    new Object[]{a.use.snapshot(), CurrentClinicalAllocation.CurrentUseSource.class}, new Object[]{a.grant.snapshot(), CurrentClinicalAllocation.Grant.class},
                    new Object[]{core.snapshot(), Pp4CurrentAdoptionProducer.CoreExecution.class}, new Object[]{a.outcome.snapshot(), Pp4CurrentAdoptionProducer.Outcome.class},
                    new Object[]{a.use.finish(a.grant, core).snapshot(), CurrentClinicalAllocation.Adoption.class})) {
                JsonNode audit = (JsonNode) entry[0]; Class<?> type = (Class<?>) entry[1];
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> a.json.treeToValue(audit, type), type.getSimpleName());
            }
        }
    }

    @Test void occupiedCallerAttemptAndClosedClaimCannotAuthorizeAnotherAward() throws Exception {
        try (var actual = new Actual(directory.resolve("closed"), "closed", true, false, ignored -> { })) {
            assertTrue(actual.outcome.isComplete(), () -> actual.outcome.snapshot().toPrettyString());
            var occupied = actual.session.attempts().get(0); occupied.withObject("/binding/ledger").put("status", "KNOWN").put("pp4Points", "2.5");
            occupied.withArray("/binding/ledger/eventIds").add("pre-existing-other-native-event");
            assertEquals("PP1_CURRENT_CLINICAL_RESERVATION_SCOPE_MISMATCH", assertThrows(IllegalArgumentException.class,
                    () -> actual.use.reserve(actual.session, occupied, actual.material)).getMessage());
            actual.fixture.store().finish(actual.fixture.runId(), "PAUSED", "{}", "{}");
            assertEquals("PP1_CURRENT_CLINICAL_CLAIM_NO_LONGER_RUNNING", assertThrows(IllegalArgumentException.class,
                    () -> actual.use.reserve(actual.session, actual.session.attempts().get(0), actual.material)).getMessage());
            var blocked = actual.producer.evaluate(actual.session, actual.session.attempts().get(0));
            assertFalse(blocked.isComplete()); assertTrue(blocked.snapshot().path("finalPoints").isNull());
            assertEquals("PP1_CURRENT_CLINICAL_CLAIM_NO_LONGER_RUNNING", blocked.snapshot().at("/materialDiagnostic/executionDiagnostic").asText());
        }
    }

    @Test void knownOppositePhenotypeHasACompleteNonadoptionButUnknownDoesNotBecomeZero() throws Exception {
        try (var opposite = new Actual(directory.resolve("opposite"), "opposite", true, false,
                source -> source.set("CLINICAL", "observations", 0, "presence", "ABSENT"))) {
            var receipt = opposite.outcome.snapshot(); assertTrue(opposite.outcome.isComplete(), receipt::toPrettyString);
            assertEquals("NOT_ADOPTED", receipt.path("kind").asText()); assertEquals("0", receipt.path("finalPoints").asText());
            assertTrue(receipt.path("eventIds").isEmpty()); assertTrue(receipt.path("exclusiveFactIds").isEmpty());
            assertEquals("NOT_EVALUATED", opposite.outcome.coreExecution().orElseThrow().evaluation().at("/evaluationResult/status").asText());
            assertEquals("PP4_PHENOTYPE_REQUIREMENT_NOT_MET", opposite.outcome.coreExecution().orElseThrow().evaluation().at("/evaluationResult/reasonCode").asText());
            assertFalse(receipt.at("/currentAdoption/knownNoAdoptionBasis").isMissingNode());
        }
        try (var unknown = new Actual(directory.resolve("unknown"), "unknown", true, false,
                source -> source.set("CLINICAL", "observations", 0, "presence", "UNKNOWN"))) {
            assertFalse(unknown.outcome.isComplete()); assertTrue(unknown.outcome.snapshot().path("finalPoints").isNull());
            assertTrue(unknown.outcome.coreExecution().isEmpty()); assertTrue(unknown.outcome.snapshot().path("eventIds").isEmpty());
        }
    }

    @Test void resealedCompactCoreOrAdoptionCannotDetachFromItsActualGrant() throws Exception {
        try (var actual = new Actual(directory.resolve("archive"), "archive", false, true, ignored -> { })) {
            assertTrue(actual.inventory.isComplete(), () -> actual.inventory.snapshot().toPrettyString());
            var originalInventory = actual.inventory.snapshot(); var originalArchive = actual.archive.deepCopy();
            for (String mutation : List.of("grant", "status", "adoption")) {
                var inventory = originalInventory.deepCopy(); var receipt = (ObjectNode) inventory.at("/attempts/0/terminalReceipt");
                var core = (ObjectNode) receipt.path("coreExecution");
                if (mutation.equals("grant")) core.put("grantSha256", "0".repeat(64));
                if (mutation.equals("status")) core.put("actualStatus", "NOT_MET");
                if (mutation.equals("adoption")) {
                    var adoption = (ObjectNode) receipt.path("currentAdoption"); adoption.put("awardedPoints", "5"); reseal(adoption, "adoptionSha256");
                }
                receipt.put("coreExecutionSha256", T04NativeOriginalFixture.canonicalSha(core));
                if (!mutation.equals("adoption")) {
                    var adoption = (ObjectNode) receipt.path("currentAdoption"); adoption.set("coreExecutionSha256", receipt.path("coreExecutionSha256").deepCopy());
                    reseal(adoption, "adoptionSha256");
                }
                reseal(receipt, "receiptSha256");
                var attempt = (ObjectNode) inventory.at("/attempts/0"); attempt.set("terminalReceiptSha256", receipt.path("receiptSha256").deepCopy());
                attempt.put("terminalSnapshotSha256", T04NativeOriginalFixture.canonicalSha(receipt));
                reseal(inventory, "inventorySha256");
                var archive = originalArchive.deepCopy();
                for (var binding : archive.path("rawBindings")) if ("CORE_ARTIFACT".equals(binding.path("kind").asText()))
                    ((ObjectNode) binding).set("coreExecutionSha256", receipt.path("coreExecutionSha256").deepCopy());
                archive.put("inventorySnapshotSha256", T04NativeOriginalFixture.canonicalSha(inventory)); reseal(archive, "archiveSha256");
                assertEquals("CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH", assertThrows(IllegalArgumentException.class,
                        () -> CurrentClinicalArchive.verifyAndExpand(actual.json, archive, inventory, actual.fixture.analysisId(), actual.fixture.runId()), mutation).getMessage());
            }
        }
    }
    private static void reseal(ObjectNode value, String pin) throws Exception { value.remove(pin); value.put(pin, T04NativeOriginalFixture.canonicalSha(value)); }
}
