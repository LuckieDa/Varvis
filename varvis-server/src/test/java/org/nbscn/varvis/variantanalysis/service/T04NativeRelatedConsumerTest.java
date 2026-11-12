package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.LocalEvidenceBatchRuntime;
import org.nbscn.varvis.variantanalysis.provider.Pp1CurrentContributionSource;
import org.nbscn.varvis.variantanalysis.provider.Pp1SelectedBatch;
import org.nbscn.varvis.variantanalysis.provider.Pp4CurrentAdoptionProducer;
import org.nbscn.varvis.variantanalysis.provider.Pp4QualifiedMaterial;
import org.nbscn.varvis.variantanalysis.provider.Pp4ServerRegisteredInputs;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture;
import org.nbscn.varvis.variantanalysis.provider.T04Pp1ReplaySource;

/** Actual H2/native VCF/two-attempt Runtime token and real de novo/Python consumers.
 * All case/report/definition inputs are SYNTHETIC engineering originals. The second disease
 * is an explicit alternative background, not a fabricated capability or criterion result.
 */
@EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
@EnabledIfSystemProperty(named = "varvis.pp1.python", matches = ".+")
class T04NativeRelatedConsumerTest {
    @TempDir Path temporary;
    @Test void relatedNeFromUnqualifiedOtherBackgroundMustBlockKnownZero() throws Exception {
        var json = new ObjectMapper(); var criteria = List.of("ACMG_AMP_2015:PP1");
        try (var f = new T04CurrentSessionFixture(temporary.resolve("n"), "t04-related-analysis", "t04-related-run", criteria, true)) {
            ObjectNode a = T04NativeOriginalFixture.context(f), b = a.deepCopy();
            b.withObject("/disease").put("mondoId", "MONDO:0000002").put("label", "SYNTHETIC second background without corresponding registered clinical/report originals");
            var adapter = new RuntimeEvidenceV2Adapter(json);
            b.put("contextId", adapter.canonicalVersionedContextId(b)); adapter.validateVersionedContext(b);
            assertEquals(a.path("patientContext"), b.path("patientContext"));
            assertNotEquals(a.path("disease"), b.path("disease"));
            var raw = new T04NativeOriginalFixture(f, a); raw.nativeSampleLink(f, a);
            var registered = Pp4ServerRegisteredInputs.load(raw.writeServerBundle(temporary.resolve("r"), a));
            assertTrue(Pp4QualifiedMaterial.verify(registered.registration(), a).isComplete());
            assertFalse(Pp4QualifiedMaterial.verify(registered.registration(), b).isComplete(), "B cannot be included in the qualified-material collision set");
            var source = T04Pp1ReplaySource.generate(temporary.resolve("p"), T04NormalPipelineTest.python(), T04NormalPipelineTest.scripts(),
                    T04NativeOriginalFixture.analysisBinding(a, f, criteria), "positive");
            var deNovo = T04NativeOriginalFixture.inheritedDeNovo(temporary.resolve("d"), a);
            var pp1 = Pp1SelectedBatch.withCurrentFactory(json, source,
                    new Pp1CurrentContributionSource.Factory(new Pp4CurrentAdoptionProducer(registered)));
            var runtime = new LocalEvidenceBatchRuntime(json, json.createObjectNode().put("dataClass", "SYNTHETIC").put("fixture", "T04_RELATED_CONSUMER_NATIVE_COUNTEREXAMPLE"),
                    null, null, null, null, null, null, null, null, null, null, null, true, deNovo, null, pp1);
            runtime.bindExecutionCriteria(criteria); runtime.preparePp1Sources(f.analysisId(), json.createObjectNode());
            var session = f.issue(List.of(T04CurrentSessionFixture.AttemptSpec.ready("hypothesis", a),
                    T04CurrentSessionFixture.AttemptSpec.ready("other-background", b)), runtime.identity());
            var tokenAudit = new AtomicReference<ObjectNode>(); var inventoryAudit = new AtomicReference<ObjectNode>();
            var useAudit = new AtomicReference<ObjectNode>();
            ObjectNode result = runtime.execute(f.generalPrepared(), new AnalysisContext(f.analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES),
                    f.executionSourceSha256(), f.prepared(), session, token -> {
                        tokenAudit.set(token.snapshot());
                        try {
                            var current = CurrentClinicalAllocation.prepareAfterClaim(json, f.store(), session, f.prepared(), f.recovered(), f.ranked(), registered, token);
                            var factory = runtime.currentPp1Factory().orElseThrow(); factory.bindCurrentUseSource(current);
                            var inventory = CurrentAdoptionInventory.capture(session, factory.producer());
                            inventoryAudit.set(inventory.snapshot()); useAudit.set(current.snapshot()); runtime.bindCurrentPp1(session, inventory);
                        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        catch (RuntimeException failure) { throw failure; }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    });
            assertEquals(1, deNovo.evaluations(), "A executes the actual old core; B returns its genuine scope/report NE before that core is called");
            JsonNode token = tokenAudit.get(); assertNotNull(token);
            JsonNode first = byHypothesis(token.path("attempts"), "hypothesis"), other = byHypothesis(token.path("attempts"), "other-background");
            assertEquals("READY", first.path("bindingStatus").asText()); assertEquals("READY", other.path("bindingStatus").asText());
            assertEquals(1, first.at("/moduleResults/deNovoMatchingResults").asInt());
            assertEquals(1, other.at("/moduleResults/deNovoMatchingResults").asInt());
            for (String code : List.of("PS2", "PM6")) {
                assertEquals("NOT_MET", first.at("/moduleResults/deNovo/criteria/" + code + "/evaluationResult/status").asText());
                assertEquals("NONE", first.at("/moduleResults/deNovo/criteria/" + code + "/appliedStrength").asText());
                assertEquals("NOT_EVALUATED", other.at("/moduleResults/deNovo/criteria/" + code + "/evaluationResult/status").asText(), other.toPrettyString());
            }
            JsonNode inventory = inventoryAudit.get(), original = byHypothesis(inventory.path("attempts"), "hypothesis");
            assertFalse(original.path("complete").asBoolean(), "A's own NOT_MET pair must not conceal B's related unknown use");
            assertEquals("PP1_CURRENT_CLINICAL_RELATED_CONSUMER_TERMINAL_INCOMPLETE", original.path("reasonCode").asText(), original.toPrettyString());
            assertTrue(original.at("/terminalReceipt/finalPoints").isNull()); assertFalse(original.path("terminalReceipt").has("coreExecution"));
            assertFalse(original.path("reasonCode").asText().contains("FACT_ALLOCATION_CONFLICT"), "This is unknown related use, not two qualified material allocations");
            JsonNode pp1Result = byHypothesis(result.at("/modules/pp1/results"), "hypothesis").at("/publicSummary/evaluation");
            assertEquals("UNKNOWN", pp1Result.at("/ledger/status").asText()); assertTrue(pp1Result.at("/ledger/pp4Points").isNull());
            assertEquals(0, pp1Result.path("awardedPP1Points").asInt(-1)); assertEquals("NOT_EVALUATED", pp1Result.path("status").asText());
            assertEquals("VERIFIED", byHypothesis(useAudit.get().path("attempts"), "hypothesis").path("nativeCallStatus").asText());
        }
    }
    private static JsonNode byHypothesis(JsonNode rows, String hypothesis) {
        for (var row : rows) if (hypothesis.equals(row.path("hypothesisId").asText())) return row;
        throw new AssertionError("Missing actual hypothesis " + hypothesis + ": " + rows);
    }
}
