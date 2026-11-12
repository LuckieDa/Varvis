package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual material and criterion runtimes over existing literal SYNTHETIC fixtures.
 * Positive results are evaluated, never supplied as prebuilt MET/NOT_MET documents.
 * No VEP, network, native biological reference or clinical qualification is asserted.
 */
class ProteinLengthPublicProofTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private static final String HYPOTHESIS = "test-hypothesis";
    private record Scenario(Pm4TestFixtures data, GeneralPm4Runtime pm4, GeneralBp3Runtime bp3) { }

    @Test void actualStopLossMnvPublishesItsCurrentMaterialAndExistingModerateResult() throws Exception {
        var scenario = stopLoss();
        ObjectNode result = evaluate(scenario);
        assertOriginalResult(result, "MET", "PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET", "MODERATE");
        JsonNode association = result.at("/sequenceMaterial/alleleAssociation");
        assertEquals(0, association.path("netNucleotideChange").asInt());
        assertEquals(3, association.path("netAminoAcidChange").asInt());
        assertEquals(6, association.path("referenceProteinLength").asInt());
        assertEquals(9, association.path("alteredProteinLength").asInt());
        assertEquals(28, association.path("alteredStopCdsPosition").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEPGFKRAG*"), association.path("alteredProteinSha256").asText());
        assertTrue(association.path("newTerminationResolved").asBoolean());
        // The runtime captures the actual selected annotation for this extension branch as well.
        JsonNode publicFacts = GeneralPm4Runtime.publicSummary(result);
        assertAvailable(scenario, result, publicFacts);
        assertEquals("PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE", publicFacts.path("scope").asText());
        assertEquals("SEQUENCE_DERIVED_STOP_LOSS", publicFacts.path("qualificationBasis").asText());
        assertFalse(publicFacts.path("observedRnaOrProtein").asBoolean(true));
        assertEquals("SEQUENCE_DERIVED_STOP_LOSS", publicFacts.at("/adoptionProof/rule/branch").asText());
        assertEquals("https://doi.org/10.1038/gim.2015.30", publicFacts.at("/adoptionProof/rule/source").asText());
        assertEquals(0, publicFacts.at("/adoptionProof/change/netNucleotideChange").asInt());
        assertEquals(3, publicFacts.at("/adoptionProof/change/netAminoAcidChange").asInt());
        assertBounded(publicFacts);
        artifact("stop-loss-mnv", result, publicFacts);
    }

    @Test void actualRepeatExclusionPublishesNotMetWithoutInventingNonRepeatApproval() throws Exception {
        var scenario = inFrame(true);
        ObjectNode result = evaluate(scenario);
        assertOriginalResult(result, "NOT_MET", "PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET", "NONE");
        assertEquals("REPEAT_OVERLAP", result.at("/regionAssessment/regionState").asText());
        assertEquals(3, result.at("/proteinAnnotations/queryRange/start").asInt());
        assertEquals(3, result.at("/proteinAnnotations/queryRange/end").asInt());
        assertFalse(result.at("/regionAssessment/supportingAnnotationLocators").isEmpty());
        assertTrue(result.at("/proteinAnnotations/source/rawSha256").asText().matches("[0-9a-f]{64}"));
        JsonNode publicFacts = GeneralPm4Runtime.publicSummary(result);
        assertAvailable(scenario, result, publicFacts);
        assertEquals("NONE", publicFacts.path("qualificationBasis").asText());
        assertEquals("KNOWN_REPEAT", publicFacts.path("repeatObservation").asText());
        assertEquals(1, publicFacts.path("netAminoAcidChange").asInt());
        assertEquals("OBSERVED_REPEAT_OVERLAP_EXCLUDES_PM4", publicFacts.at("/adoptionProof/rule/branch").asText());
        assertEquals(result.at("/proteinAnnotations/source/rawSha256"), publicFacts.at("/adoptionProof/rule/uniProtRawSha256"));
        assertEquals("/features/0", publicFacts.at("/adoptionProof/rule/repeatObservations/0/sourceLocator").asText());
        assertBounded(publicFacts);
        artifact("repeat-exclusion", result, publicFacts);
    }

    @Test void actualReviewedBp3RepeatWithoutKnownFunctionPublishesSupportingProof() throws Exception {
        reviewedBp3("NO_KNOWN_FUNCTION", "MET", "BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION", "SUPPORTING");
    }

    @Test void actualReviewedBp3KnownFunctionPublishesItsExplicitNotMetProof() throws Exception {
        reviewedBp3("KNOWN_FUNCTION", "NOT_MET", "BP3_REVIEWED_NON_REPEAT_OR_KNOWN_FUNCTION", "NONE");
    }

    @Test void optionalPublicAnnotationBindingFailureCannotRescoreValidStopLoss() throws Exception {
        var scenario = stopLoss(new ObjectMapper());
        ObjectNode result = evaluate(scenario);
        assertOriginalResult(result, "MET", "PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET", "MODERATE");
        assertEquals("READY", result.at("/sequenceMaterial/status").asText());
        assertEquals("PUBLIC_ANNOTATION_BINDING_UNAVAILABLE", result.at("/sequenceMaterial/publicAnnotationBindingReason").asText());
        assertTrue(result.at("/sequenceMaterial/alleleAssociation/newTerminationResolved").asBoolean());
        assertEquals(3, result.at("/sequenceMaterial/alleleAssociation/netAminoAcidChange").asInt());
        assertUnavailable(ProteinLengthPublicProof.project(mapper, result));
        assertUnavailable(GeneralPm4Runtime.publicSummary(result));
    }

    @Test void readySequenceProgressCannotAdoptMissingPm4OrBp3Qualification() throws Exception {
        var scenario = inFrame(false);
        ObjectNode pm4 = evaluate(scenario);
        ObjectNode bp3 = scenario.bp3().evaluate(scenario.data().context, scenario.data().candidate, identity(scenario));
        assertOriginalResult(pm4, "NOT_EVALUATED", "PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED", "NONE");
        assertEquals("NOT_EVALUATED", bp3.path("status").asText());
        assertEquals("BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED", bp3.path("reasonCode").asText());
        for (ObjectNode result : List.of(pm4, bp3)) {
            ObjectNode before = result.deepCopy();
            JsonNode envelope = ProteinLengthPublicProof.project(mapper, result);
            assertEquals("READY", envelope.at("/sequenceMaterial/status").asText());
            assertEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", envelope.path("annotationCorrespondence").asText());
            assertUnavailable(envelope);
            assertEquals(before, result);
            assertBounded(envelope);
        }
        assertUnavailable(GeneralPm4Runtime.publicSummary(pm4));
        assertUnavailable(GeneralBp3Runtime.publicSummary(bp3));
        assertEquals("UNKNOWN", GeneralBp3Runtime.publicSummary(bp3).path("regionState").asText());
        assertFalse(GeneralBp3Runtime.publicSummary(bp3).path("functionAbsenceInferredFromNoHit").asBoolean(true));
        artifact("ready-pm4-unknown", pm4, GeneralPm4Runtime.publicSummary(pm4));
        artifact("ready-bp3-unknown", bp3, GeneralBp3Runtime.publicSummary(bp3));
    }

    @Test void failedComparisonCannotClaimCurrentReadyOrAdoption() throws Exception {
        var scenario = inFrame(false);
        scenario.data().candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(
                scenario.data().annotation("7_9delinsGCTGAG", "Pro3delinsAlaGlu", "protein_altering_variant"),
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        ObjectNode result = evaluate(scenario);
        assertOriginalResult(result, "NOT_EVALUATED", "PM4_HGVSC_SEQUENCE_MISMATCH", "NONE");
        JsonNode envelope = ProteinLengthPublicProof.project(mapper, result);
        assertNotEquals("READY", envelope.at("/sequenceMaterial/status").asText());
        assertNotEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", envelope.path("annotationCorrespondence").asText());
        assertUnavailable(envelope);
    }

    @ParameterizedTest @ValueSource(strings = {"missing-receipt", "missing-material", "missing-verification",
            "candidate", "hypothesis", "context", "allele", "model", "transcript", "policy", "reference",
            "altered-cds", "comparison", "result-status", "result-reason", "result-strength", "rule-source", "termination"})
    void changingAnActualResultCannotReuseItsIssuedAdoptionProof(String fault) throws Exception {
        var scenario = stopLoss();
        ObjectNode original = evaluate(scenario);
        assertEquals("AVAILABLE", ProteinLengthPublicProof.project(mapper, original).at("/adoptionProof/status").asText());
        ObjectNode changed = original.deepCopy();
        switch (fault) {
            case "missing-receipt" -> changed.remove("proteinLengthPublicProof");
            case "missing-material" -> changed.remove("sequenceMaterial");
            case "missing-verification" -> changed.withObject("sequenceMaterial").remove("verification");
            case "candidate" -> changed.put("candidateId", "other-candidate");
            case "hypothesis" -> changed.put("hypothesisId", "other-hypothesis");
            case "context" -> changed.put("contextId", "context-sha256-" + "f".repeat(64));
            case "allele" -> changed.withObject("/sequenceMaterial/queryAllele").put("alternate", "CAG");
            case "model" -> changed.withObject("sequenceMaterial").put("modelCanonicalSha256", "f".repeat(64));
            case "transcript" -> changed.withObject("sequenceMaterial").put("transcript", "ENST000001.2");
            case "policy" -> changed.withObject("policy").put("policyVersion", "0.0.0");
            case "reference" -> changed.withObject("/sequenceMaterial/verification/referenceIdentity").put("expectedSha256", "0".repeat(64));
            case "altered-cds" -> changed.withObject("/sequenceMaterial/alleleAssociation").put("alteredCdsSha256", "f".repeat(64));
            case "comparison" -> changed.put("annotationCorrespondence", "UNAVAILABLE");
            case "result-status" -> changed.put("status", "NOT_MET");
            case "result-reason" -> changed.put("reasonCode", "PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET");
            case "result-strength" -> changed.withObject("evaluation").put("appliedStrength", "STRONG");
            case "rule-source" -> changed.withObject("stopLossAssessment").put("source", "https://example.invalid/unrelated-rule");
            case "termination" -> changed.withObject("/sequenceMaterial/alleleAssociation").put("newTerminationResolved", false);
            default -> throw new AssertionError(fault);
        }
        assertNotEquals(original, changed, "The fault must change its actual runtime result: " + fault);
        ObjectNode changedBefore = changed.deepCopy();
        JsonNode projected = ProteinLengthPublicProof.project(mapper, changed);
        assertNotAvailable(projected);
        assertEquals(changedBefore, changed, "Projection must not repair the supplied result");
        assertNotAvailable(GeneralPm4Runtime.publicSummary(changed));
        assertEquals("AVAILABLE", ProteinLengthPublicProof.project(mapper, original).at("/adoptionProof/status").asText());
    }

    @Test void aChangedPayloadCannotSelfCertifyByRecomputingOnlyItsReceiptHash() throws Exception {
        ObjectNode original = evaluate(stopLoss());
        assertEquals("AVAILABLE", ProteinLengthPublicProof.project(mapper, original).at("/adoptionProof/status").asText());
        ObjectNode changed = original.deepCopy();
        changed.withObject("/proteinLengthPublicProof/payload/adoptionProof/rule")
                .put("source", "https://example.invalid/self-certified-rule");
        ObjectNode receipt = changed.withObject("proteinLengthPublicProof");
        ObjectNode unsigned = receipt.deepCopy(); unsigned.remove("receiptSha256");
        receipt.put("receiptSha256", adapter.canonicalSha256(unsigned));
        assertNotEquals(original, changed);
        assertNotEquals(original.at("/proteinLengthPublicProof/receiptSha256"), receipt.path("receiptSha256"));
        assertEquals(original.at("/proteinLengthPublicProof/resultSha256"), receipt.path("resultSha256"));
        ObjectNode originalModule = original.deepCopy(); originalModule.remove("proteinLengthPublicProof");
        ObjectNode changedModule = changed.deepCopy(); changedModule.remove("proteinLengthPublicProof");
        assertEquals(originalModule, changedModule, "Only the receipt payload and its self hash were changed");
        ObjectNode beforeProjection = changed.deepCopy();
        JsonNode projected = ProteinLengthPublicProof.project(mapper, changed);
        assertUnavailable(projected);
        assertEquals("PUBLIC_PROOF_PAYLOAD_MISMATCH", projected.at("/adoptionProof/reasonCode").asText());
        assertEquals(beforeProjection, changed);
        assertEquals("AVAILABLE", ProteinLengthPublicProof.project(mapper, original).at("/adoptionProof/status").asText());
    }

    @Test void labelsWithoutAnActualRuntimeReceiptCannotCreateAnAdoptionProof() {
        ObjectNode labels = mapper.createObjectNode().put("candidateId", "unissued-candidate")
                .put("hypothesisId", HYPOTHESIS).put("contextId", "context-sha256-" + "a".repeat(64))
                .put("status", "MET").put("reasonCode", "PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET")
                .put("annotationCorrespondence", "COMPLETE_SEQUENCE_COMPARISON_PASSED");
        labels.putObject("sequenceMaterial").put("status", "READY");
        labels.putObject("evaluation").put("criterionId", GeneralPm4Runtime.CRITERION).put("appliedStrength", "MODERATE");
        labels.putObject("adoptionProof").put("schemaVersion", "protein-length-adoption-proof-v1").put("status", "AVAILABLE");
        ObjectNode before = labels.deepCopy();
        assertNotAvailable(ProteinLengthPublicProof.project(mapper, labels));
        assertEquals(before, labels);
    }

    @Test void publicSnapshotsAreDetachedAndDoNotTriggerASecondEvaluation() throws Exception {
        var scenario = stopLoss();
        ObjectNode result = evaluate(scenario), before = result.deepCopy();
        int calls = scenario.pm4().evaluations();
        ObjectNode first = ProteinLengthPublicProof.project(mapper, result);
        first.withObject("adoptionProof").put("status", "UNAVAILABLE");
        first.withObject("sequenceMaterial").put("status", "UNAVAILABLE");
        JsonNode again = ProteinLengthPublicProof.project(mapper, result);
        assertEquals("AVAILABLE", again.at("/adoptionProof/status").asText());
        assertEquals("READY", again.at("/sequenceMaterial/status").asText());
        assertEquals(before, result);
        assertEquals(calls, scenario.pm4().evaluations());
    }

    @Test void legacySimpleStopLossSummaryKeepsItsExistingShape() throws Exception {
        var fixture = new GeneralPm4StopLossTest(); fixture.root = Files.createDirectory(temporary.resolve("legacy"));
        fixture.setup(1, "GCTGGTTGACCC", "Ter7GlnextTer3");
        ObjectNode result = fixture.evaluate(fixture.runtime(false));
        assertOriginalResult(result, "MET", "PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET", "MODERATE");
        JsonNode summary = GeneralPm4Runtime.publicSummary(result);
        assertEquals("PROJECT_LOCAL_STOP_LOSS_SNV", summary.path("scope").asText());
        assertEquals(3, summary.path("netAminoAcidChange").asInt());
        for (String field : List.of("sequenceMaterial", "annotationCorrespondence", "adoptionProof")) assertFalse(summary.has(field));
    }

    private void reviewedBp3(String function, String status, String reason, String strength) throws Exception {
        var fixture = new GeneralBp3RuntimeTest();
        fixture.root = Files.createDirectory(temporary.resolve("reviewed-bp3-" + function));
        fixture.setup();
        var data = fixture.variant.data;
        var material = ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, fixture.variant.root,
                sha(fixture.variant.root.resolve("manifest.json")), fixture.variant::read, null);
        var uniprot = new UniProtProteinRegionSupply(mapper, fixture.root.resolve("source"),
                sha(fixture.root.resolve("source/manifest.json")));
        Path combinedFile = fixture.root.resolve("combined.json");
        String combinedPin = sha(combinedFile);
        var facts = new ProteinLengthRegionFactSupply(mapper, material, uniprot,
                new CombinedProteinRegionSupply(mapper, combinedFile, combinedPin), null);
        // Re-resolve with the named factory: do not borrow the old fixture's sequence-material hash.
        var current = facts.resolve(data.context, data.candidate, true);
        assertTrue(current.material().ready(), current.snapshot()::toPrettyString);
        fixture.knowledge.model = java.util.Objects.requireNonNull(current.material().model());
        fixture.knowledge.context = data.context;
        fixture.knowledge.annotations = (ObjectNode) current.snapshot().path("proteinAnnotations");
        fixture.knowledge.extra = (ObjectNode) current.snapshot().path("combinedRegions");
        var assertions = fixture.knowledge.qualified();
        assertEquals("SYNTHETIC_TEST_ONLY", assertions.at("/records/0/review/reviewId").asText());
        assertions.withObject("/records/0/intervals/0").put("start", 1).put("end", 6).put("functionalStatus", function);
        var assessment = fixture.knowledge.accepted(assertions);
        Path assertionsFile = fixture.root.resolve("knowledge.json");
        String assertionsPin = sha(assertionsFile);
        var engine = new GeneralBp3Runtime(mapper, facts, assessment, fixture.guard(false));
        JsonNode beforeCandidate = mapper.valueToTree(data.candidate);
        ObjectNode beforeContext = data.context.deepCopy();
        ObjectNode result = engine.evaluate(data.context, data.candidate,
                mapper.createObjectNode().put("candidateId", data.candidate.getCandidateId()).put("hypothesisId", HYPOTHESIS));
        assertOriginalResult(result, status, reason, strength);
        assertEquals(GeneralBp3Runtime.CRITERION, result.at("/evaluation/criterionId").asText());
        assertEquals("READY", result.at("/sequenceMaterial/status").asText());
        assertEquals("SIMPLE_INFRAME_INSERTION", result.at("/sequenceMaterial/alleleAssociation/branch").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEAPGFK*"),
                result.at("/sequenceMaterial/alleleAssociation/alteredProteinSha256").asText());
        JsonNode summary = GeneralBp3Runtime.publicSummary(result);
        assertAvailable(data, result, summary);
        assertEquals("REVIEWED_REPEAT_AND_FUNCTION", summary.at("/adoptionProof/rule/branch").asText());
        assertEquals(assertionsPin, result.at("/policy/regionAssessment/assertionsSha256").asText());
        assertEquals(assertionsPin, summary.at("/adoptionProof/rule/acceptedRawSha256").asText());
        assertEquals(combinedPin, summary.at("/adoptionProof/rule/combinedRawSha256").asText());
        assertEquals(result.at("/proteinAnnotations/source/rawSha256"), summary.at("/adoptionProof/rule/uniProtRawSha256"));
        assertEquals("/records/0", summary.at("/adoptionProof/rule/recordLocator").asText());
        assertEquals(function, summary.at("/adoptionProof/rule/coverageIntervals/0/functionalStatus").asText());
        assertFalse(summary.path("functionAbsenceInferredFromNoHit").asBoolean(true));
        assertEquals(beforeCandidate, mapper.valueToTree(data.candidate));
        assertEquals(beforeContext, data.context);
        assertEquals(assertionsPin, sha(assertionsFile));
        assertEquals(combinedPin, sha(combinedFile));
        assertBounded(summary);
        artifact("reviewed-bp3-" + function.toLowerCase(java.util.Locale.ROOT), result, summary);
    }

    private Scenario stopLoss() throws Exception { return stopLoss(mapper); }

    private Scenario stopLoss(ObjectMapper runtimeMapper) throws Exception {
        var fixture = new GeneralPm4StopLossTest(); fixture.root = Files.createDirectory(temporary.resolve("stop-" + UUID.randomUUID()));
        fixture.setup(1, "GCTGGTTGACCC", null);
        fixture.data.model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100)
                .put("genomicEnd", 99 + fixture.spliced.length()).put("cdsStart", 1).put("cdsEnd", fixture.spliced.length());
        fixture.data.variant(118, "TAA", "CGA", "19_21delinsCGA", "Ter7ArgextTer3");
        fixture.annotation = fixture.data.annotation("19_21delinsCGA", "Ter7ArgextTer3", "stop_lost").put("exon", "1/1");
        fixture.applyAnnotation();
        variantType(fixture.data, "MNV");
        return runtimes(fixture.root.resolve("runtime"), fixture.data, fixture::read, false, runtimeMapper);
    }

    private Scenario inFrame(boolean repeat) throws Exception {
        var fixture = new ProteinLengthChangeMaterialSupplyTest();
        fixture.root = Files.createDirectory(temporary.resolve("inframe-" + UUID.randomUUID()));
        fixture.setup(1, 106, "CCT", "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu");
        variantType(fixture.data, "SMALL_DELINS");
        fixture.data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(
                fixture.data.annotation("7_9delinsGCTGAA", "Pro3delinsAlaGlu", "protein_altering_variant"),
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        return runtimes(fixture.root.resolve("runtime"), fixture.data, fixture::read, repeat);
    }

    private Scenario runtimes(Path resources, Pm4TestFixtures data,
            CodingTranscriptMaterialSupply.ReferenceReader reader, boolean repeat) throws Exception {
        return runtimes(resources, data, reader, repeat, mapper);
    }

    private Scenario runtimes(Path resources, Pm4TestFixtures data,
            CodingTranscriptMaterialSupply.ReferenceReader reader, boolean repeat, ObjectMapper runtimeMapper) throws Exception {
        // This existing helper writes actual pinned toy originals. Its returned legacy runtime is not evaluated.
        GeneralPm4TestSupport.create(mapper, resources, data.model, reader, repeat, false, false);
        var material = ProteinLengthMaterialFactory.reviewedSmallAlleles(runtimeMapper, resources,
                sha(resources.resolve("manifest.json")), reader, null);
        var annotations = new UniProtProteinRegionSupply(runtimeMapper, resources.resolve("uniprot"),
                sha(resources.resolve("uniprot/manifest.json")));
        var combined = new CombinedProteinRegionSupply(runtimeMapper, resources.resolve("combined.json"), sha(resources.resolve("combined.json")));
        var guard = PredictionSpecializationGuard.load(runtimeMapper, resources.resolve("catalog.json"), sha(resources.resolve("catalog.json")));
        var pm4 = new GeneralPm4Runtime(runtimeMapper, material, annotations, combined, new Pm4ProteinRegionAssessment(runtimeMapper), guard);
        var bp3 = new GeneralBp3Runtime(runtimeMapper, pm4.sharedFactSupply(), new Bp3ProteinRegionAssessment(runtimeMapper), guard);
        return new Scenario(data, pm4, bp3);
    }

    private ObjectNode evaluate(Scenario scenario) {
        return scenario.pm4().evaluate(scenario.data().context, scenario.data().candidate, identity(scenario));
    }
    private ObjectNode identity(Scenario scenario) {
        return mapper.createObjectNode().put("candidateId", scenario.data().candidate.getCandidateId()).put("hypothesisId", HYPOTHESIS);
    }
    private void variantType(Pm4TestFixtures data, String type) {
        data.context.withObject("variant").put("variantType", type);
        data.context.withObject("variant").put("alleleId", adapter.canonicalAlleleId(
                data.context.at("/variant/normalizedAllele"), data.context.at("/variant/normalization")));
        data.context.put("contextId", adapter.canonicalContextId(data.context));
    }
    private void assertOriginalResult(JsonNode result, String status, String reason, String strength) {
        assertEquals(status, result.path("status").asText(), result::toPrettyString);
        assertEquals(reason, result.path("reasonCode").asText());
        assertEquals(strength, result.at("/evaluation/appliedStrength").asText());
    }
    private void assertAvailable(Scenario scenario, JsonNode result, JsonNode summary) {
        assertAvailable(scenario.data(), result, summary);
    }
    private void assertAvailable(Pm4TestFixtures data, JsonNode result, JsonNode summary) {
        assertEquals("READY", summary.at("/sequenceMaterial/status").asText(), summary::toPrettyString);
        assertEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", summary.path("annotationCorrespondence").asText());
        JsonNode proof = summary.path("adoptionProof");
        assertEquals("protein-length-adoption-proof-v1", proof.path("schemaVersion").asText());
        assertEquals("AVAILABLE", proof.path("status").asText(), proof::toPrettyString);
        assertEquals(result.path("status"), proof.at("/result/status"));
        assertEquals(result.path("reasonCode"), proof.at("/result/reasonCode"));
        assertEquals(result.at("/evaluation/appliedStrength"), proof.at("/result/appliedStrength"));
        assertEquals(data.context.path("contextId"), proof.at("/binding/evaluationContextId"));
        assertEquals(data.candidate.getCandidateId(), proof.at("/binding/candidateId").asText());
        assertEquals(HYPOTHESIS, proof.at("/binding/hypothesisId").asText());
        assertEquals("DIRECT_EVALUATION_CONTEXT", proof.at("/binding/bindingMode").asText());
        assertFalse(proof.path("binding").has("selectedContextId"));
        assertEquals(data.context.at("/variant/evidenceQueryAllele"), proof.at("/binding/queryAllele"));
        assertEquals(data.model.path("transcript"), proof.at("/binding/transcript"));
        assertEquals(data.model.path("protein"), proof.at("/binding/protein"));
        assertEquals(result.at("/sequenceMaterial/modelCanonicalSha256"), proof.at("/binding/modelCanonicalSha256"));
        assertEquals(result.at("/sequenceMaterial/alleleAssociation/storedAnnotationSha256"), proof.at("/binding/storedAnnotationSha256"));
        assertTrue(proof.at("/binding/policySha256").asText().matches("[0-9a-f]{64}"));
        assertFalse(proof.path("clinicalValidationCompleted").asBoolean(true));
        assertFalse(proof.path("createsScientificApproval").asBoolean(true));
        assertFalse(proof.path("rescoringPerformed").asBoolean(true));
        assertEquals(summary.at("/sequenceMaterial/binding"), proof.path("binding"));
        ObjectNode envelope = ProteinLengthPublicProof.project(mapper, result);
        var keys = new java.util.HashSet<String>(); envelope.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("sequenceMaterial", "annotationCorrespondence", "adoptionProof"), keys);
        assertEquals(envelope.path("sequenceMaterial"), summary.path("sequenceMaterial"));
        assertEquals(envelope.path("adoptionProof"), proof);
    }
    private void assertUnavailable(JsonNode summary) {
        assertEquals("UNAVAILABLE", summary.at("/adoptionProof/status").asText(), summary::toPrettyString);
        assertFalse(summary.at("/adoptionProof/reasonCode").asText().isBlank());
    }
    private void assertNotAvailable(JsonNode summary) {
        // Unsupported/legacy scope projects no envelope; a malformed known scope projects UNAVAILABLE.
        assertNotEquals("AVAILABLE", summary.at("/adoptionProof/status").asText(), summary::toPrettyString);
        if (summary.has("adoptionProof")) assertUnavailable(summary);
    }
    private void assertBounded(JsonNode node) {
        String text = node.toString();
        for (String hidden : List.of(Pm4TestFixtures.CDS, "MEPGFKRAG*", "ATGGAAGCTGAAGGCTTTAAATAA",
                "GCTGGTTGACCC", temporary.toString(), "rawFeatureJson", "actualChecksumReceipt")) assertFalse(text.contains(hidden), hidden);
    }
    private static String sha(Path path) throws Exception { return CodingTranscriptMaterialSupply.sha(Files.readAllBytes(path)); }
    private void artifact(String name, JsonNode result, JsonNode summary) throws Exception {
        String configured = System.getProperty("a5.publicproof.actualRoot");
        if (configured == null) return;
        Path output = Files.createDirectories(Path.of(configured).resolve("direct-public-proof"));
        ObjectNode value = mapper.createObjectNode().put("dataClass", "SYNTHETIC_ENGINEERING_ONLY")
                .put("actualCriterionRuntimeExecuted", true).put("newVepOrNetworkCalls", 0).put("scientificQualificationGranted", false);
        value.set("actualModuleResult", result.deepCopy()); value.set("actualPublicSummary", summary.deepCopy());
        Files.write(output.resolve(name + "-" + UUID.randomUUID() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }
}
