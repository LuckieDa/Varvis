package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TruthMaskedHpoReviewPacketGeneratorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generatesStableOpaquePacketsAndSeparatesInternalMapping(@TempDir Path temp) throws Exception {
        Path dataset = write(temp.resolve("dataset.json"), datasetJson(false));
        Path acceptance = write(temp.resolve("acceptance.json"), acceptanceJson(false));
        TruthMaskedHpoReviewPacketGenerator generator = new TruthMaskedHpoReviewPacketGenerator();
        HpoReviewGenerationResult first = generator.generate(dataset, acceptance, temp.resolve("first"));
        HpoReviewGenerationResult second = generator.generate(write(temp.resolve("dataset-reversed.json"), datasetJson(true)),
                write(temp.resolve("acceptance-reversed.json"), acceptanceJson(true)), temp.resolve("second"));
        assertEquals(5, first.getPackets().size());
        assertEquals(Arrays.asList("SMOKE-HPO-001", "SMOKE-HPO-002", "SMOKE-HPO-003", "SMOKE-HPO-004", "SMOKE-HPO-005"), ids(first));
        assertEquals(fingerprints(first), fingerprints(second));
        assertTrue(Files.exists(first.getReviewerDirectory().resolve("SMOKE-HPO-001.packet.json")));
        String reviewer = new String(Files.readAllBytes(first.getReviewerDirectory().resolve("SMOKE-HPO-001.packet.json")), StandardCharsets.UTF_8);
        assertFalse(reviewer.contains("case-a")); assertFalse(reviewer.contains("GENE-A")); assertFalse(reviewer.contains("Disease A"));
        assertFalse(reviewer.contains("1|100|A|G")); assertFalse(reviewer.contains("PMID"));
        String internal = new String(Files.readAllBytes(first.getInternalDirectory().resolve("DO_NOT_PROVIDE_TO_REVIEWER.mapping.json")), StandardCharsets.UTF_8);
        assertTrue(internal.contains("DO_NOT_PROVIDE_TO_REVIEWER")); assertTrue(internal.contains("case-a"));
        generator.assertReviewerVisibleContentIsMasked(first.getReviewerDirectory(), Arrays.asList("case-a", "GENE-A", "Disease A", "1|100|A|G", "PMID"));
    }

    @Test
    void validatesPendingIncompleteInvalidAndReadyDecisions() {
        HpoReviewPacket packet = packet("PATIENT_SPECIFIC_SOURCE_CONTEXT_UNAVAILABLE");
        HpoReviewDecisionValidator validator = new HpoReviewDecisionValidator();
        HpoReviewDecisionDocument blank = template(packet);
        assertEquals(HpoReviewReadinessStatus.HUMAN_REVIEW_PENDING, validator.validate(packet, blank).getStatus());

        blank.getTermDecisions().get(0).setDecision(HpoReviewDecisionType.KEEP_OBSERVED);
        assertEquals(HpoReviewReadinessStatus.HUMAN_REVIEW_INCOMPLETE, validator.validate(packet, blank).getStatus());
        blank.setPacketFingerprint("bad");
        assertEquals(HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID, validator.validate(packet, blank).getStatus());

        HpoReviewPacket tampered = packet("PATIENT_SPECIFIC_SOURCE_CONTEXT_UNAVAILABLE");
        tampered.setSchemaVersion(TruthMaskedHpoReviewPacketGenerator.PACKET_SCHEMA_VERSION);
        tampered.setReviewProtocolVersion(TruthMaskedHpoReviewPacketGenerator.REVIEW_PROTOCOL_VERSION);
        tampered.setDatasetId("synthetic"); tampered.setDatasetVersion("1.0"); tampered.setHpoOntologyVersion("test"); tampered.setGeneratorVersion("test");
        tampered.setPacketContentChecksum(TruthMaskedHpoReviewPacketGenerator.calculatePacketContentChecksum(tampered));
        HpoReviewDecisionDocument tamperedDocument = template(tampered);
        tamperedDocument.setPacketContentChecksum(tampered.getPacketContentChecksum());
        tampered.getTerms().get(0).setLabel("modified");
        assertEquals(HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID, validator.validate(tampered, tamperedDocument).getStatus());

        HpoReviewDecisionDocument complete = template(packet);
        complete.getTermDecisions().get(0).setDecision(HpoReviewDecisionType.KEEP_OBSERVED);
        complete.getTermDecisions().get(1).setDecision(HpoReviewDecisionType.KEEP_EXCLUDED);
        assertEquals(HpoReviewReadinessStatus.READY_IDENTICAL_TO_G1, validator.validate(packet, complete).getStatus());

        HpoReviewDecisionDocument changed = template(packet);
        changed.getTermDecisions().get(0).setDecision(HpoReviewDecisionType.REMOVE_MAPPING_ERROR);
        changed.getTermDecisions().get(0).setMappingErrorReason("source term mapped to the wrong HPO concept");
        changed.getTermDecisions().get(1).setDecision(HpoReviewDecisionType.KEEP_EXCLUDED);
        assertEquals(HpoReviewReadinessStatus.READY_WITH_REVIEWED_CHANGES, validator.validate(packet, changed).getStatus());
        assertEquals(Collections.<String>emptyList(), new HpoReviewG2InputBuilder().build(packet, changed).getObservedTerms());

        HpoReviewDecisionDocument uncertain = template(packet);
        uncertain.getTermDecisions().get(0).setDecision(HpoReviewDecisionType.UNCERTAIN);
        uncertain.getTermDecisions().get(1).setDecision(HpoReviewDecisionType.KEEP_EXCLUDED);
        assertEquals(HpoReviewReadinessStatus.HUMAN_REVIEW_INCOMPLETE, validator.validate(packet, uncertain).getStatus());
    }

    @Test
    void rejectsUnsafeAdditionsAndBuildsCompliantReviewedChanges() {
        HpoReviewPacket unavailable = packet("PATIENT_SPECIFIC_SOURCE_CONTEXT_UNAVAILABLE");
        HpoReviewDecisionDocument unavailableDocument = completeTemplate(unavailable);
        unavailableDocument.getAdditions().add(addition("HP:0000003", true));
        assertEquals(HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID,
                new HpoReviewDecisionValidator().validate(unavailable, unavailableDocument).getStatus());

        HpoReviewPacket available = packet("PATIENT_SPECIFIC_SOURCE_CONTEXT_AVAILABLE");
        HpoReviewDecisionDocument document = completeTemplate(available);
        document.getAdditions().add(addition("HP:0000003", true));
        HpoReviewValidationResult result = new HpoReviewDecisionValidator().validate(available, document);
        assertEquals(HpoReviewReadinessStatus.READY_WITH_REVIEWED_CHANGES, result.getStatus());
        assertEquals(Arrays.asList("HP:0000001", "HP:0000003"), new HpoReviewG2InputBuilder().build(available, document).getObservedTerms());

        HpoReviewDecisionDocument noReason = completeTemplate(available);
        noReason.getTermDecisions().get(0).setDecision(HpoReviewDecisionType.REMOVE_MAPPING_ERROR);
        assertEquals(HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID,
                new HpoReviewDecisionValidator().validate(available, noReason).getStatus());
        noReason.setEligibleForStrictBlindedEvaluation(Boolean.TRUE);
        assertEquals(HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID,
                new HpoReviewDecisionValidator().validate(available, noReason).getStatus());
    }

    @Test
    void roundTripsReviewerPacketAndDoesNotRequireFrozenArtifactGeneration() throws Exception {
        HpoReviewPacket packet = packet("PATIENT_SPECIFIC_SOURCE_CONTEXT_UNAVAILABLE");
        HpoReviewPacket decoded = mapper.readValue(mapper.writeValueAsString(packet), HpoReviewPacket.class);
        assertEquals(packet.getReviewId(), decoded.getReviewId()); assertEquals(2, decoded.getTerms().size());
    }

    @Test
    void generatesParameterizedPilotProposalPacketsWithoutChangingSmokeDefaults(@TempDir Path temp) throws Exception {
        Path dataset = write(temp.resolve("pilot-dataset.json"), datasetJson(30));
        Path acceptance = write(temp.resolve("pilot-acceptance.json"), acceptanceJson(30));
        HpoReviewGenerationResult result = new TruthMaskedHpoReviewPacketGenerator().generate(
                dataset, acceptance, temp.resolve("pilot-review"),
                HpoReviewPacketGenerationOptions.pilotProposal(30));
        assertEquals(30, result.getPackets().size());
        assertEquals("PILOT-HPO-001", result.getPackets().get(0).getReviewId());
        assertEquals("PILOT-HPO-030", result.getPackets().get(29).getReviewId());
        assertEquals("2026-06-23", result.getPackets().get(0).getHpoOntologyVersion());
        String template = new String(Files.readAllBytes(result.getReviewerDirectory()
                .resolve("PILOT-HPO-001.decision-template.json")), StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode templateJson = mapper.readTree(template);
        assertEquals("HUMAN_REVIEW_PENDING", templateJson.path("reviewCompletionStatus").asText());
        assertEquals("NOT_RECORDED", templateJson.path("priorTruthExposure").asText());
        assertFalse(template.contains("case-001"));
        String instructions = new String(Files.readAllBytes(result.getReviewerDirectory()
                .resolve("REVIEW_INSTRUCTIONS_zh-CN.md")), StandardCharsets.UTF_8);
        assertTrue(instructions.startsWith("# Truth-masked HPO \u590d\u6838\u8bf4\u660e"));
        assertTrue(instructions.contains("`PILOT-HPO-`"));
    }

    @Test
    void generatesFrozenArtifactsWhenExplicitlyRequested() throws Exception {
        String output = System.getProperty("varvis.hpoReviewOutputDirectory");
        if (output == null || output.trim().isEmpty()) return;
        Path root = Paths.get(System.getProperty("varvis.repoRoot", ".."));
        TruthMaskedHpoReviewPacketGenerator generator = new TruthMaskedHpoReviewPacketGenerator();
        HpoReviewGenerationResult result = generator.generate(
                root.resolve("docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-smoke-v1/dataset-manifest.json"),
                root.resolve("docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-smoke-v1/selection-acceptance.json"),
                Paths.get(output));
        assertEquals(5, result.getPackets().size());
    }

    private List<String> ids(HpoReviewGenerationResult result) { List<String> ids = new ArrayList<String>(); for (HpoReviewPacket packet : result.getPackets()) ids.add(packet.getReviewId()); return ids; }
    private List<String> fingerprints(HpoReviewGenerationResult result) { List<String> values = new ArrayList<String>(); for (HpoReviewPacket packet : result.getPackets()) values.add(packet.getPacketFingerprint()); return values; }
    private Path write(Path path, String content) throws Exception { Files.write(path, content.getBytes(StandardCharsets.UTF_8)); return path; }
    private String datasetJson(boolean reverse) {
        List<String> cases = new ArrayList<String>();
        for (char c = 'a'; c <= 'e'; c++) cases.add("{\"caseId\":\"case-" + c + "\",\"originalHpoTerms\":[\"HP:0000001\"],\"excludedHpoTerms\":[\"HP:0000002\"],\"hpoTermLabels\":{\"HP:0000001\":\"Observed\",\"HP:0000002\":\"Excluded\"}}");
        if (reverse) Collections.reverse(cases);
        return "{\"datasetId\":\"synthetic-smoke\",\"datasetVersion\":\"1.0\",\"cases\":[" + String.join(",", cases) + "]}";
    }
    private String acceptanceJson(boolean reverse) {
        List<String> cases = new ArrayList<String>();
        for (char c = 'a'; c <= 'e'; c++) cases.add("{\"caseId\":\"case-" + c + "\",\"phenopacketSha256\":\"" + String.format("%064x", (int) c) + "\"}");
        if (reverse) Collections.reverse(cases);
        return "{\"cases\":[" + String.join(",", cases) + "]}";
    }
    private String datasetJson(int count) {
        List<String> cases = new ArrayList<String>();
        for (int index = 1; index <= count; index++) cases.add("{\"caseId\":\"case-" + String.format("%03d", index) + "\",\"originalHpoTerms\":[\"HP:0000001\"],\"excludedHpoTerms\":[\"HP:0000002\"],\"hpoTermLabels\":{\"HP:0000001\":\"Observed\",\"HP:0000002\":\"Excluded\"}}");
        return "{\"datasetId\":\"synthetic-pilot\",\"datasetVersion\":\"1.0\",\"cases\":[" + String.join(",", cases) + "]}";
    }
    private String acceptanceJson(int count) {
        List<String> cases = new ArrayList<String>();
        for (int index = 1; index <= count; index++) cases.add("{\"caseId\":\"case-" + String.format("%03d", index) + "\",\"phenopacketSha256\":\"" + String.format("%064x", index) + "\"}");
        return "{\"cases\":[" + String.join(",", cases) + "]}";
    }
    private HpoReviewPacket packet(String sourceContext) {
        HpoReviewPacket packet = new HpoReviewPacket(); packet.setSchemaVersion(TruthMaskedHpoReviewPacketGenerator.PACKET_SCHEMA_VERSION); packet.setReviewProtocolVersion(TruthMaskedHpoReviewPacketGenerator.REVIEW_PROTOCOL_VERSION); packet.setReviewId("SMOKE-HPO-001"); packet.setPacketFingerprint("f" + String.format("%063d", 0)); packet.setDatasetId("synthetic"); packet.setDatasetVersion("1.0"); packet.setHpoOntologyVersion("test"); packet.setGeneratorVersion("test"); packet.setSourceContext(sourceContext);
        HpoReviewTerm observed = new HpoReviewTerm(); observed.setHpoId("HP:0000001"); observed.setOriginalStatus(HpoReviewOriginalStatus.OBSERVED);
        HpoReviewTerm excluded = new HpoReviewTerm(); excluded.setHpoId("HP:0000002"); excluded.setOriginalStatus(HpoReviewOriginalStatus.EXCLUDED);
        packet.setTerms(Arrays.asList(observed, excluded)); packet.setPacketContentChecksum(TruthMaskedHpoReviewPacketGenerator.calculatePacketContentChecksum(packet)); return packet;
    }
    private HpoReviewDecisionDocument template(HpoReviewPacket packet) {
        HpoReviewDecisionDocument document = new HpoReviewDecisionDocument(); document.setReviewId(packet.getReviewId()); document.setPacketFingerprint(packet.getPacketFingerprint()); document.setPacketContentChecksum(packet.getPacketContentChecksum());
        document.setReviewMethod("TRUTH_MASKED_DEVELOPMENT_REVIEW"); document.setPriorTruthExposure("REPORT_PREVIOUSLY_DISPLAYED"); document.setActiveTruthReviewReported(Boolean.FALSE); document.setEligibleForStrictBlindedEvaluation(Boolean.FALSE);
        List<HpoReviewTermDecision> decisions = new ArrayList<HpoReviewTermDecision>(); for (HpoReviewTerm term : packet.getTerms()) { HpoReviewTermDecision decision = new HpoReviewTermDecision(); decision.setHpoId(term.getHpoId()); decision.setOriginalStatus(term.getOriginalStatus()); decisions.add(decision); } document.setTermDecisions(decisions); return document;
    }
    private HpoReviewDecisionDocument completeTemplate(HpoReviewPacket packet) { HpoReviewDecisionDocument document = template(packet); document.getTermDecisions().get(0).setDecision(HpoReviewDecisionType.KEEP_OBSERVED); document.getTermDecisions().get(1).setDecision(HpoReviewDecisionType.KEEP_EXCLUDED); return document; }
    private HpoReviewAddition addition(String id, boolean complete) { HpoReviewAddition addition = new HpoReviewAddition(); addition.setHpoId(id); if (complete) { addition.setPatientEvidenceExcerpt("patient-specific source excerpt"); addition.setSourceLocator("phenopacket.clinicalPhenotypes[0]"); addition.setRationale("documented patient evidence"); addition.setReviewerConfirmed(Boolean.TRUE); } return addition; }
}
