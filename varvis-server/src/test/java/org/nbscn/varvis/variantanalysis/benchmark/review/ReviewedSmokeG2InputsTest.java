package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewedSmokeG2InputsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reviewedSmokeDecisionsFreezeG2IdenticallyToG1() throws Exception {
        Path root = Paths.get(System.getProperty("varvis.repoRoot", "..")).toAbsolutePath().normalize();
        Path reviewRoot = root.resolve("docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-smoke-v1/truth-masked-hpo-review-v1");
        JsonNode mapping = mapper.readTree(reviewRoot.resolve("internal/DO_NOT_PROVIDE_TO_REVIEWER.mapping.json").toFile());
        JsonNode originalPlan = mapper.readTree(root.resolve("docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-smoke-v1/g0-g1-g2-input-plan.json").toFile());
        JsonNode reviewedPlan = mapper.readTree(reviewRoot.resolve("final-decisions/g0-g1-g2-reviewed-input-plan.json").toFile());
        HpoReviewDecisionValidator validator = new HpoReviewDecisionValidator();
        HpoReviewG2InputBuilder builder = new HpoReviewG2InputBuilder();

        assertEquals(5, mapping.path("mappings").size());
        for (JsonNode entry : mapping.path("mappings")) {
            String reviewId = entry.path("reviewId").asText();
            String caseId = entry.path("caseId").asText();
            HpoReviewPacket packet = mapper.readValue(reviewRoot.resolve("reviewer-visible/" + reviewId + ".packet.json").toFile(), HpoReviewPacket.class);
            HpoReviewDecisionDocument decision = mapper.readValue(reviewRoot.resolve("final-decisions/" + reviewId + ".decision.json").toFile(), HpoReviewDecisionDocument.class);

            assertEquals(HpoReviewReadinessStatus.READY_IDENTICAL_TO_G1, validator.validate(packet, decision).getStatus());
            assertEquals("PROJECT_RESEARCHER", decision.getReviewerRole());
            assertEquals(Boolean.TRUE, decision.getAiAssistance().getAiAssistanceUsed());
            assertEquals(Boolean.TRUE, decision.getAiAssistance().getHumanFinalConfirmation());
            assertEquals(Boolean.FALSE, decision.getEligibleForStrictBlindedEvaluation());
            assertTrue(decision.getAdditions().isEmpty());
            for (HpoReviewTermDecision term : decision.getTermDecisions()) {
                assertNull(term.getMappingErrorReason());
                assertEquals(term.getOriginalStatus() == HpoReviewOriginalStatus.OBSERVED
                        ? HpoReviewDecisionType.KEEP_OBSERVED : HpoReviewDecisionType.KEEP_EXCLUDED, term.getDecision());
            }

            ReviewedHpoInput input = builder.build(packet, decision);
            JsonNode originalCase = findCase(originalPlan, caseId);
            JsonNode reviewedCase = findCase(reviewedPlan, caseId);
            List<String> g1 = strings(group(originalCase, "G1").path("hpoTerms"));
            List<String> g2 = strings(group(reviewedCase, "G2").path("hpoTerms"));
            assertEquals(g1, input.getObservedTerms());
            assertEquals(g1, g2);
            assertEquals("READY_IDENTICAL_TO_G1", group(reviewedCase, "G2").path("status").asText());
            assertEquals(packetTerms(packet, HpoReviewOriginalStatus.EXCLUDED), input.getExcludedTerms());
            assertFalse(g2.contains("HP:0009098"));
        }
    }

    @Test
    void legacyDecisionWithoutAiProvenanceRemainsReadable() throws Exception {
        HpoReviewPacket packet = packet();
        HpoReviewDecisionDocument decision = new HpoReviewDecisionDocument();
        decision.setReviewId(packet.getReviewId()); decision.setPacketFingerprint(packet.getPacketFingerprint());
        decision.setPacketContentChecksum(packet.getPacketContentChecksum()); decision.setReviewMethod("TRUTH_MASKED_DEVELOPMENT_REVIEW");
        decision.setPriorTruthExposure("REPORT_PREVIOUSLY_DISPLAYED"); decision.setActiveTruthReviewReported(Boolean.FALSE);
        decision.setEligibleForStrictBlindedEvaluation(Boolean.FALSE);
        HpoReviewTermDecision term = new HpoReviewTermDecision(); term.setHpoId("HP:0000001"); term.setOriginalStatus(HpoReviewOriginalStatus.OBSERVED); term.setDecision(HpoReviewDecisionType.KEEP_OBSERVED);
        decision.setTermDecisions(Collections.singletonList(term));
        assertNull(mapper.readValue(mapper.writeValueAsString(decision), HpoReviewDecisionDocument.class).getAiAssistance());
        assertEquals(HpoReviewReadinessStatus.READY_IDENTICAL_TO_G1, new HpoReviewDecisionValidator().validate(packet, decision).getStatus());
    }

    private JsonNode findCase(JsonNode plan, String caseId) { for (JsonNode item : plan.path("cases")) if (caseId.equals(item.path("caseId").asText())) return item; throw new AssertionError("case missing: " + caseId); }
    private JsonNode group(JsonNode item, String groupId) { for (JsonNode group : item.path("groups")) if (groupId.equals(group.path("groupId").asText())) return group; throw new AssertionError("group missing: " + groupId); }
    private List<String> strings(JsonNode values) { java.util.ArrayList<String> result = new java.util.ArrayList<String>(); for (JsonNode value : values) result.add(value.asText()); Collections.sort(result); return result; }
    private List<String> packetTerms(HpoReviewPacket packet, HpoReviewOriginalStatus status) { java.util.ArrayList<String> result = new java.util.ArrayList<String>(); for (HpoReviewTerm term : packet.getTerms()) if (term.getOriginalStatus() == status) result.add(term.getHpoId()); Collections.sort(result); return result; }
    private HpoReviewPacket packet() {
        HpoReviewPacket packet = new HpoReviewPacket(); packet.setSchemaVersion(TruthMaskedHpoReviewPacketGenerator.PACKET_SCHEMA_VERSION); packet.setReviewProtocolVersion(TruthMaskedHpoReviewPacketGenerator.REVIEW_PROTOCOL_VERSION); packet.setReviewId("legacy"); packet.setDatasetId("synthetic"); packet.setDatasetVersion("1"); packet.setHpoOntologyVersion("unknown"); packet.setGeneratorVersion("test"); packet.setSourceContext("PATIENT_SPECIFIC_SOURCE_CONTEXT_UNAVAILABLE");
        HpoReviewTerm term = new HpoReviewTerm(); term.setHpoId("HP:0000001"); term.setOriginalStatus(HpoReviewOriginalStatus.OBSERVED); packet.setTerms(Collections.singletonList(term)); packet.setPacketContentChecksum(TruthMaskedHpoReviewPacketGenerator.calculatePacketContentChecksum(packet)); packet.setPacketFingerprint("legacy-fingerprint"); return packet;
    }
}
