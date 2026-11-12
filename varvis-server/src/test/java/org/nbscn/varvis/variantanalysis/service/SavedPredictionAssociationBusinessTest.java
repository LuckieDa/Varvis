package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.config.SavedObservationOnlyConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic parent acquisition and annotations, real H2/HTTP/claim/finish/read; no scientific transport. */
class SavedPredictionAssociationBusinessTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    AcmgBusinessService original;
    ObjectNode parent, request, resource, options;
    AtomicInteger lookups = new AtomicInteger();
    boolean isoPublicationDates;
    java.util.function.Consumer<AcmgBusinessIntegrationTest> beforePublication = b -> { };
    @BeforeEach void setup() throws Exception {
        base = new AcmgBusinessIntegrationTest();
        if (isoPublicationDates) base.mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        base.temporary = temporary; base.setup();
        beforePublication.accept(base);
        base.publish(false, GenomeAssembly.GRCh38, null); original = base.service;
        resource = PredictionCoreTest.resource(base.mapper).put("calibrationCompatibility", "UNVERIFIED");
        base.service = AcmgBusinessService.predictionDevelopment(original, this::runtime);
        var sourceOptions = base.service.inputOptions(base.analysisId, 2);
        base.request.put("declarationVersion", PredictionDevelopmentRuntime.DECLARATION).putArray("hypotheses");
        var refs = base.request.putArray("recommendations");
        for (var c : sourceOptions.path("candidates")) {
            var ref = refs.addObject();
            for (var key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(key, c.path("recommendation").path(key));
        }
        parent = base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(base.request));
        assertEquals(2, lookups.get());
        base.service = AcmgBusinessService.savedPredictionAssociation(original, this::runtime);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        options = base.service.inputOptions(base.analysisId, 2);
        request = base.request.deepCopy(); request.put("declarationVersion", SavedPredictionAssociation.DECLARATION); request.remove("recommendations");
        var ref = request.putObject("association").put("operation", SavedPredictionAssociation.OPERATION)
                .put("sourceRunId", parent.path("runId").asText()).put("sourceRequestSha256", parent.path("requestSha256").asText())
                .put("executionSourceSha256", parent.path("executionSourceSha256").asText());
        var selections = ref.putArray("selections");
        for (int i = 0; i < 2; i++) {
            var c = options.path("candidates").get(i); var tx = c.path("associationOptions").get(0); assertNotNull(tx, c.toPrettyString());
            selections.addObject().put("candidateId", c.path("candidateId").asText())
                    .put("sourceObservationSha256", parent.at("/candidates/" + i + "/attempts/0/prediction/observation/observationSha256").asText())
                    .put("selectedAnnotationId", tx.path("annotationId").asText()).put("annotationSourceSha256", tx.path("annotationSourceSha256").asText())
                    .put("versionedTranscriptId", tx.path("versionedTranscriptId").asText()).put("selectionOrigin", "DEVELOPER_PUBLIC_CONTROL");
        }
    }
    @AfterEach void close() { if (base != null) base.closeDatabase(); }
    PredictionDevelopmentRuntime runtime() {
        var resources = base.mapper.createObjectNode(); resources.set("ALPHAMISSENSE", resource);
        return new PredictionDevelopmentRuntime(base.mapper,
                PredictionDevelopmentRuntime.load(base.mapper, "prediction-core-policy-v1.json"),
                PredictionDevelopmentRuntime.load(base.mapper, "prediction-am-calibration-v1.json"), resources, query -> {
            lookups.incrementAndGet();
            var observation = base.mapper.createObjectNode().put("schemaVersion", "prediction-observation-v1").put("status", "HIT")
                    .put("observedAt", "2026-09-18T00:00:00Z").put("scoreOrigin", "REAL_LOCAL_PRECOMPUTED_RESOURCE")
                    .put("transportAttempts", 1).put("referenceVerified", true);
            for (String field : List.of("queryAllele", "resource", "dataClass")) observation.set(field, query.path(field));
            var rows = observation.putArray("rows"); var key = query.path("queryAllele");
            for (var c : base.jobs.findInterpretations(base.analysisId, 0, 2)) if (base.mapper.valueToTree(c.getObservation().getKey()).equals(key))
                for (var tx : c.getTranscriptAnnotations()) {
                    String source = String.join("\t", "chr" + key.path("chromosome").asText(), key.path("position").asText(),
                            key.path("reference").asText(), key.path("alternate").asText(), "hg38", "SYNTHETIC", tx.getVersionedTranscriptId(), tx.getAminoAcidChange(), "0.222900", "fixture");
                    rows.addObject().put("sourceRow", source).put("transcriptId", tx.getVersionedTranscriptId())
                            .put("proteinVariant", tx.getAminoAcidChange()).put("uniprotId", "SYNTHETIC").put("rawScore", "0.222900").put("rawClass", "fixture");
                }
            observation.put("observationSha256", new AcmgBusinessArtifacts(base.mapper).requestHash(observation)); return observation;
        }, true);
    }
    ObjectNode submit() throws Exception { return base.postRequest(request); }
    void rejects(String reason) throws Exception {
        byte[] bytes = base.mapper.writeValueAsBytes(request);
        var ex = assertThrows(ResponseStatusException.class, () -> base.service.submit(base.analysisId, bytes));
        assertEquals(reason, ex.getReason()); assertEquals(2, lookups.get(), "no acquisition fallback");
        assertEquals(parent, base.service.read(base.analysisId, parent.path("runId").asText()));
    }
    @Test void noDiseaseExplicitChoiceAssociatesAndNormalSaveReadRepeatKeepsParentAndHistoricalTransports() throws Exception {
        var before = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2));
        var child = submit(); assertNotEquals(parent.path("runId"), child.path("runId"));
        assertEquals(0, child.path("providerCalls").asInt()); assertEquals(2, child.path("associationEvaluations").asInt());
        for (int i = 0; i < 2; i++) {
            var p = child.at("/candidates/" + i + "/attempts/0/prediction");
            assertEquals(parent.at("/candidates/" + i + "/attempts/0/prediction/observation"), p.path("observation"));
            assertEquals(1, p.at("/observation/transportAttempts").asInt()); assertEquals(0, p.path("newTransportAttempts").asInt());
            assertEquals("ASSOCIATED", p.at("/association/status").asText()); assertEquals("0.222900", p.at("/association/rawScore").asText());
            assertTrue(p.at("/plan/hypothesisId").isNull()); assertEquals("CALIBRATION", p.at("/stages/firstUnresolvedStage").asText());
            for (var criterion : p.path("criteria")) { assertEquals("NOT_EVALUATED", criterion.path("status").asText()); assertEquals("NONE", criterion.path("appliedStrength").asText()); }
        }
        assertEquals(child, submit()); assertEquals(child, base.service.read(base.analysisId, child.path("runId").asText()));
        assertEquals(child, base.service.read(base.analysisId, child.path("runId").asText())); assertEquals(2, lookups.get());
        assertEquals(parent, base.service.read(base.analysisId, parent.path("runId").asText()));
        assertEquals(before, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2)));
    }
    @Test void missingSelectionRejectedWithoutBackgroundSubstitution() throws Exception {
        request.withObject("/association").putArray("selections"); rejects("SAVED_OBSERVATION_DECLARATION_INVALID");
    }
    @Test void oldDeclarationCannotAuthorizeNewOperation() throws Exception {
        request.put("declarationVersion", PredictionDevelopmentRuntime.DECLARATION); rejects("SAVED_OBSERVATION_DECLARATION_INVALID");
    }
    @Test void wrongCandidateObservationAndAnnotationReferencesNeverQuery() throws Exception {
        var selection = (ObjectNode) request.at("/association/selections/0"); var originalSelection = selection.deepCopy();
        Map<String, String> cases = Map.of("candidateId", "SAVED_OBSERVATION_CANDIDATE_NOT_SELECTED",
                "sourceObservationSha256", "SAVED_OBSERVATION_REFERENCE_NOT_UNIQUE", "selectedAnnotationId", "ASSOCIATION_ANNOTATION_SELECTION_NOT_UNIQUE",
                "annotationSourceSha256", "ASSOCIATION_ANNOTATION_REFERENCE_MISMATCH", "versionedTranscriptId", "ASSOCIATION_ANNOTATION_REFERENCE_MISMATCH");
        for (var entry : cases.entrySet()) { selection.removeAll(); selection.setAll(originalSelection); selection.put(entry.getKey(), "wrong"); rejects(entry.getValue()); }
    }
    @Test void crossAnalysisParentAndAlteredSourceOrPurposeRejected() throws Exception {
        var ref = request.withObject("/association"); String source = ref.path("executionSourceSha256").asText();
        ref.put("executionSourceSha256", "0".repeat(64)); rejects("SAVED_OBSERVATION_EXECUTION_SOURCE_MISMATCH"); ref.put("executionSourceSha256", source);
        request.put("dataClass", "PRIVACY_BOUNDED_RUNTIME"); rejects("SAVED_OBSERVATION_PURPOSE_MISMATCH"); request.put("dataClass", "SYNTHETIC");
        ref.put("sourceRunId", UUID.randomUUID().toString()); rejects("ACMG_RUN_NOT_FOUND");
    }
    @Test void corruptParentPrivateDataRejectedByNormalReadBeforeAssociation() throws Exception {
        new org.springframework.jdbc.core.JdbcTemplate(Objects.requireNonNull(base.dataSource)).update("UPDATE variant_analysis_acmg_run SET private_json=? WHERE run_id=?", "{}", parent.path("runId").asText());
        byte[] bytes = base.mapper.writeValueAsBytes(request);
        var ex = assertThrows(ResponseStatusException.class, () -> base.service.submit(base.analysisId, bytes));
        assertEquals("ACMG_SAVED_RESULT_INTEGRITY_FAILURE", ex.getReason()); assertEquals(2, lookups.get());
    }
    @Test void parentBelongingToAnotherRealAnalysisCannotBeReadOrAssociated() throws Exception {
        String foreignAnalysis = base.analysisId;
        base.publish(false, GenomeAssembly.GRCh38, null);
        assertNotEquals(foreignAnalysis, base.analysisId);
        base.service = AcmgBusinessService.savedPredictionAssociation(original, this::runtime);
        var before = original.read(foreignAnalysis, parent.path("runId").asText());
        byte[] bytes = base.mapper.writeValueAsBytes(request);
        assertEquals("ACMG_RUN_NOT_FOUND", assertThrows(ResponseStatusException.class,
                () -> base.service.submit(base.analysisId, bytes)).getReason());
        assertEquals(before, original.read(foreignAnalysis, parent.path("runId").asText()));
        assertEquals(2, lookups.get());
    }
    @Test void unversionedChoiceAndDifferentResourceNeverBecomeTrusted() throws Exception {
        var selection = (ObjectNode) request.at("/association/selections/0");
        selection.put("versionedTranscriptId", selection.path("versionedTranscriptId").asText().replaceFirst("\\.[0-9]+$", ""));
        rejects("ASSOCIATION_ANNOTATION_REFERENCE_MISMATCH");
        // A resource identity change is not permission to fetch a replacement observation.
        var changed = resource.deepCopy().put("version", "changed-resource");
        var runner = new SavedPredictionAssociation(base.mapper, changed);
        var declared = AcmgSubmission.parse(base.mapper.writeValueAsBytes(request), base.mapper);
        var ex = assertThrows(IllegalArgumentException.class, () -> runner.prepare(parent, declared,
                Objects.requireNonNull(declared.association()).selections().get(0), base.jobs.findInterpretations(base.analysisId, 0, 2).get(0),
                options.at("/candidates/0/associationOptions"), parent.path("executionSourceSha256").asText()));
        assertEquals("SAVED_OBSERVATION_RESOURCE_OR_USE_MISMATCH", ex.getMessage());
    }
    @Test void formalServiceRejectsOperationAndClientCannotSupplyRawScores() throws Exception {
        byte[] bytes = base.mapper.writeValueAsBytes(request);
        var ex = assertThrows(ResponseStatusException.class, () -> original.submit(base.analysisId, bytes));
        assertEquals("SAVED_OBSERVATION_OPERATION_NOT_ENABLED", ex.getReason());
        request.withObject("/association").put("rawScore", "0.99"); rejects("ACMG_REQUEST_INVALID");
        assertFalse(SavedObservationOnlyConfig.allowed("POST", "/variant-analyses"));
        assertFalse(SavedObservationOnlyConfig.allowed("POST", "/variant-analyses/any/enhancements"));
        assertFalse(SavedObservationOnlyConfig.allowed("GET", "/variant-analyses/any/llm-shadow-preview"));
        assertTrue(SavedObservationOnlyConfig.allowed("POST", "/variant-analyses/" + base.analysisId + "/acmg/runs"));
    }
    @Test void purePlansRejectTamperedRawDigestAndDuplicateVersionIdentity() throws Exception {
        var candidate = base.jobs.findInterpretations(base.analysisId, 0, 2).get(0);
        var declared = AcmgSubmission.parse(base.mapper.writeValueAsBytes(request), base.mapper);
        var ref = Objects.requireNonNull(declared.association()).selections().get(0);
        var runner = new SavedPredictionAssociation(base.mapper, resource);
        var bad = parent.deepCopy(); ((ObjectNode) bad.at("/candidates/0/attempts/0/prediction/observation/rows/0")).put("rawScore", "0.9");
        var ex = assertThrows(IllegalArgumentException.class, () -> runner.prepare(bad, declared, ref, candidate, options.at("/candidates/0/associationOptions"), parent.path("executionSourceSha256").asText()));
        assertEquals("SAVED_OBSERVATION_DIGEST_MISMATCH", ex.getMessage());
        var list = new ArrayList<>(candidate.getTranscriptAnnotations());
        list.add(list.get(options.at("/candidates/0/associationOptions/0/annotationIndex").asInt())); candidate.applyTranscriptAnnotations(list);
        ex = assertThrows(IllegalArgumentException.class, () -> runner.prepare(parent, declared, ref, candidate, options.at("/candidates/0/associationOptions"), parent.path("executionSourceSha256").asText()));
        assertEquals("ASSOCIATION_ANNOTATION_IDENTITY_CONFLICT", ex.getMessage());
    }
}
