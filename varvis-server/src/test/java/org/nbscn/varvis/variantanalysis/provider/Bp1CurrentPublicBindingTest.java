package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.nio.file.Files;
import com.fasterxml.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic transport guards around an actually executed existing BP1 MET core.
 * The fake root/preparation controls below are not normal Service source authorization.
 * Real-source, normal Controller/persistence coverage lives in T05Bp1TruncatingBusinessTest.
 */
class Bp1CurrentPublicBindingTest {
    @TempDir Path temporary;
    private final GeneralBp1RuntimeTest fixture = new GeneralBp1RuntimeTest();
    private ObjectNode full, raw, criterion, context;
    private void setup() throws Exception {
        fixture.root = temporary; fixture.setup(); context = fixture.fixture.input.fixture().context();
        raw = fixture.run(); assertEquals("MET", raw.path("status").asText());
        raw.put("hypothesisId", "synthetic-background");
        var projection = fixture.mapper.createObjectNode().put("schemaVersion", "selected-legacy-context-projection-v1")
                .put("candidateId", "synthetic").put("hypothesisId", "synthetic-background");
        projection.set("originalContext", context.deepCopy()); projection.set("evaluationContext", context.deepCopy());
        projection.put("bindingSha256", fixture.ids.canonicalSha256(projection)); raw.set("sourceProjection", projection);
        var selected = mock(org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation.class);
        when(selected.context("synthetic", "synthetic-background")).thenAnswer(call -> context.deepCopy());
        Bp1CurrentPublicBinding.capture(fixture.mapper, raw, selected, "synthetic", "synthetic-background", "a".repeat(64), "/modules/bp1/results/0");
        full = fixture.mapper.createObjectNode().put("analysisId", "SYNTHETIC_BINDING_CONTROL").put("sourceSnapshotSha256", "a".repeat(64));
        full.putObject("identity").set("bp1TruncatingConditionExecution", GeneralBp1Runtime.truncatingConditionIdentity(fixture.mapper));
        full.putArray("executionCriteria").add(GeneralBp1Runtime.CRITERION);
        var preparation = full.putObject("preparation").put("analysisId", "SYNTHETIC_BINDING_CONTROL");
        var attempt = preparation.putArray("candidates").addObject().put("candidateId", "synthetic").putArray("attempts").addObject().put("hypothesisId", "synthetic-background");
        attempt.set("context", context.deepCopy()); attempt.putArray("blockers");
        var module = full.putObject("modules").putObject("bp1").put("sourceSnapshotSha256", "a".repeat(64))
                .put("currentPublicBindingVersion", Bp1CurrentPublicBinding.VERSION);
        module.set("policy", raw.path("policy").deepCopy()); module.putArray("results").add(raw);
        var wrapper = full.putArray("results").addObject().put("candidateId", "synthetic").put("hypothesisId", "synthetic-background");
        wrapper.set("context", context.deepCopy()); var local = wrapper.putObject("localBatch").put("candidateId", "synthetic")
                .put("hypothesisId", "synthetic-background").put("contextId", context.path("contextId").asText());
        criterion = local.putArray("criteria").addObject().put("criterionId", GeneralBp1Runtime.CRITERION)
                .put("status", "MET").put("appliedStrength", "SUPPORTING").put("reasonCode", raw.path("reasonCode").asText());
        var details = criterion.putObject("details").put("modulePointer", "/modules/bp1/results/0");
        details.set("originalResult", raw.path("evaluation").deepCopy()); details.set("bp1Facts", raw.path("publicSummary").deepCopy());
    }
    private ObjectNode project() { return Bp1CurrentPublicBinding.project(fixture.mapper, full, full.at("/results/0"), criterion); }
    @Test void oldMetKeepsItsFinalResultAndReceivesOnlyActualTransportBinding() throws Exception {
        setup(); var facts = project(); assertFalse(facts.has("publicProjectionIssue"), facts::toPrettyString);
        assertEquals("MET", facts.at("/currentBinding/status").asText());
        assertEquals("SUPPORTING", facts.at("/currentBinding/appliedStrength").asText());
        assertEquals("TRANSPORT_JSON_VALUE_C14N_V1", facts.at("/currentBinding/auditHashMethod").asText());
        assertFalse(facts.path("experimentalNormalityEstablished").asBoolean(true));
        assertEquals(facts, Bp1CurrentPublicBinding.project(fixture.mapper, fixture.mapper.readTree(fixture.mapper.writeValueAsBytes(full)), full.at("/results/0"), criterion));
    }
    @ParameterizedTest @ValueSource(strings={"ROOT_SOURCE", "MODULE_SOURCE", "RAW_SOURCE", "PROJECTION", "ALLELE", "MODEL", "CLAUSE", "FINAL", "DUPLICATE", "PREPARED", "CACHED_FACTS", "CAPABILITY_DOWNGRADE"})
    void mutationsCannotAcquireANewBinding(String fault) throws Exception {
        setup();
        switch(fault) {
            case "ROOT_SOURCE" -> full.put("sourceSnapshotSha256", "b".repeat(64));
            case "MODULE_SOURCE" -> full.withObject("/modules/bp1").put("sourceSnapshotSha256", "b".repeat(64));
            case "RAW_SOURCE" -> raw.put("executionSourceSha256", "b".repeat(64));
            case "PROJECTION" -> raw.withObject("/sourceProjection").put("bindingSha256", "b".repeat(64));
            case "ALLELE" -> raw.withObject("/sourceProjection/evaluationContext/variant/evidenceQueryAllele").put("alternate", "A");
            case "MODEL" -> raw.withObject("/sequenceMaterial").put("modelCanonicalSha256", "b".repeat(64));
            case "CLAUSE" -> raw.withObject("/publishedKnowledge/record").put("clauseSha256", "b".repeat(64));
            case "FINAL" -> raw.withObject("/evaluation").put("appliedStrength", "STRONG");
            case "DUPLICATE" -> full.withArray("/modules/bp1/results").add(raw.deepCopy());
            case "PREPARED" -> full.withObject("/preparation/candidates/0/attempts/0/context").put("contextId", "wrong");
            case "CAPABILITY_DOWNGRADE" -> full.withObject("/modules/bp1").remove("currentPublicBindingVersion");
            case "CACHED_FACTS" -> { criterion.withObject("/details/bp1Facts").put("sourceSpecification", "CLIENT_INVENTED"); var facts = project(); assertFalse(facts.has("publicProjectionIssue")); assertNotEquals("CLIENT_INVENTED", facts.path("sourceSpecification").asText()); return; }
        }
        assertEquals("CENTRAL_BP1_MODULE_BINDING_INVALID", project().path("publicProjectionIssue").asText());
        assertFalse(project().has("currentBinding"));
    }
    @Test void oldHistoryHasNoNewAuthorityAndCannotBorrowACurrentReceipt() throws Exception {
        setup(); full.withObject("/modules/bp1").remove("currentPublicBindingVersion");
        full.withObject("/identity").remove("bp1TruncatingConditionExecution");
        raw.remove("currentPublicBindingReceipt"); assertFalse(project().has("currentBinding"));
        criterion.withObject("/details/bp1Facts").putObject("currentBinding").put("status", "MET");
        assertEquals("CENTRAL_BP1_MODULE_BINDING_INVALID", project().path("publicProjectionIssue").asText());
    }

    private void savedCapability(String version) {
        var capability = (ObjectNode) full.at("/identity/bp1TruncatingConditionExecution").deepCopy();
        capability.put("methodVersion", version).remove("canonicalSha256");
        if ("1.0.0".equals(version)) {
            capability.remove("noLofSourceGrammar"); capability.remove("sourceDefinedTruncationMeansExperimentalLof");
            capability.put("noEstablishedLofCorrelationQualificationSupported", false);
        } else if ("1.1.0".equals(version)) capability
                .put("noEstablishedLofCorrelationQualificationSupported", true)
                .put("noLofSourceGrammar", "EXPLICIT_GENE_WIDE_GERMLINE_REVIEW_WITH_NATIVE_VALIDITY_V1");
        capability.put("canonicalSha256", fixture.ids.canonicalSha256(capability));
        if ("1.0.0".equals(version)) assertEquals("c0a1b8c28851ce144c35aba276cb3a9fc6a2755b4f8a99fddd8daad9ba2df7f4",
                capability.path("canonicalSha256").asText(), "Frozen 0fec943a/d8bc8d1e capability, not a version-only allowlist");
        full.withObject("/identity").set("bp1TruncatingConditionExecution", capability.deepCopy());
        raw.withObject("/policy").set("truncatingConditionExecution", capability.deepCopy());
        full.withObject("/modules/bp1").set("policy", raw.path("policy").deepCopy());
        recaptureSyntheticTransport();
    }

    private void recaptureSyntheticTransport() {
        var selected = mock(org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation.class);
        when(selected.context("synthetic", "synthetic-background")).thenAnswer(call -> context.deepCopy());
        Bp1CurrentPublicBinding.capture(fixture.mapper, raw, selected, "synthetic", "synthetic-background", "a".repeat(64), "/modules/bp1/results/0");
    }

    @Test void frozenOneZeroMissenseKeepsItsOriginalBindingWithoutCurrentVersionResigning() throws Exception {
        setup(); savedCapability("1.0.0"); var before = full.deepCopy();
        var savedBinding = raw.at("/currentPublicBindingReceipt/binding").deepCopy(); var facts = project();
        assertFalse(facts.has("publicProjectionIssue"), facts::toPrettyString);
        assertEquals(savedBinding, facts.path("currentBinding")); assertEquals(before, full);
        assertEquals("1.0.0", raw.at("/policy/truncatingConditionExecution/methodVersion").asText());
    }

    @Test void retiredOneOneMissenseDoesNotBorrowItsUnrelatedTruncationQualification() throws Exception {
        setup(); assertFalse(raw.has("truncatingConditionCheck")); savedCapability("1.1.0");
        var facts = project(); assertFalse(facts.has("publicProjectionIssue"), facts::toPrettyString);
        assertEquals("MET", facts.at("/currentBinding/status").asText());
    }

    @Test void retiredOneOneTruncatingMetIsNotAdoptedUnderTheSafeContract() throws Exception {
        setup(); savedCapability("1.1.0");
        raw.putObject("truncatingConditionCheck").put("schemaVersion", "bp1-truncating-condition-assessment-v1")
                .put("methodVersion", "1.1.0").put("status", "MET").put("scientificQualificationGranted", true);
        recaptureSyntheticTransport();
        assertEquals("CENTRAL_BP1_MODULE_BINDING_INVALID", project().path("publicProjectionIssue").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"FUTURE", "RESEALED_QUALIFICATION", "EXTRA_FIELD", "MIXED_CAPABILITIES"})
    void futureOrChangedFrozenCapabilitiesAreRefusedEvenWithNewTransportHashes(String fault) throws Exception {
        setup(); savedCapability("1.0.0");
        var cap = (ObjectNode) raw.at("/policy/truncatingConditionExecution");
        switch (fault) {
            case "FUTURE" -> cap.put("methodVersion", "1.3.0");
            case "RESEALED_QUALIFICATION" -> cap.put("noEstablishedLofCorrelationQualificationSupported", true);
            case "EXTRA_FIELD" -> cap.put("newUnreviewedSemantics", true);
            case "MIXED_CAPABILITIES" -> full.withObject("/identity").set("bp1TruncatingConditionExecution", GeneralBp1Runtime.truncatingConditionIdentity(fixture.mapper));
            default -> fail("Unknown control");
        }
        cap.remove("canonicalSha256"); cap.put("canonicalSha256", fixture.ids.canonicalSha256(cap));
        if (!"MIXED_CAPABILITIES".equals(fault)) full.withObject("/identity").set("bp1TruncatingConditionExecution", cap.deepCopy());
        full.withObject("/modules/bp1").set("policy", raw.path("policy").deepCopy()); recaptureSyntheticTransport();
        assertEquals("CENTRAL_BP1_MODULE_BINDING_INVALID", project().path("publicProjectionIssue").asText());
    }

    @Test @EnabledIfSystemProperty(named = "varvis.bp1.history.root", matches = ".+")
    void realSavedOneZeroBatchesReplayWithoutIssuingNewReceiptsOrChangingBytes() throws Exception {
        Path directory = Path.of(java.util.Objects.requireNonNull(System.getProperty("varvis.bp1.history.root"))); int checked = 0;
        for (String scenario : java.util.List.of("MISSENSE_BP1", "MISSENSE_BP1_AND_PP2", "MISSENSE_ALL_CONFIGURED",
                "NONSENSE_BP1", "NONSENSE_BP1_AND_PP2", "NONSENSE_ALL_CONFIGURED")) {
            Path file = directory.resolve(scenario).resolve("expected-private.json"); byte[] bytes = Files.readAllBytes(file);
            var document = fixture.mapper.readTree(bytes); var batch = document.path("localBatch"); var before = batch.deepCopy();
            assertEquals("1.0.0", batch.at("/identity/bp1TruncatingConditionExecution/methodVersion").asText());
            for (var wrapper : batch.path("results")) for (var row : wrapper.at("/localBatch/criteria")) {
                if (!GeneralBp1Runtime.CRITERION.equals(row.path("criterionId").asText())) continue;
                JsonNode original = batch.at(row.at("/details/modulePointer").asText());
                var projected = Bp1CurrentPublicBinding.project(fixture.mapper, batch, wrapper, row);
                assertFalse(projected.has("publicProjectionIssue"), () -> scenario + ": " + projected.toPrettyString());
                assertEquals(original.at("/currentPublicBindingReceipt/binding"), projected.path("currentBinding"));
                assertEquals(row.path("status"), projected.at("/currentBinding/status"));
                assertEquals(row.path("appliedStrength"), projected.at("/currentBinding/appliedStrength")); checked++;
            }
            assertEquals(before, batch); assertArrayEquals(bytes, Files.readAllBytes(file));
        }
        assertEquals(12, checked, "Six actual normal-flow saved engineering cases, two backgrounds each; not clinical validation");
    }
}
