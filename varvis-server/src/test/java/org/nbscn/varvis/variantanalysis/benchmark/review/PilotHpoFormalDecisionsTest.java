package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PilotHpoFormalDecisionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void formalPilotDecisionsMatchPacketsAndRepositoryValidator() throws Exception {
        Path root = Paths.get(System.getProperty("varvis.repoRoot", "..")).toAbsolutePath().normalize();
        Path reviewRoot = root.resolve("docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1/truth-masked-hpo-review-v1");
        Path finalRoot = reviewRoot.resolve("final-decisions");
        JsonNode importManifest = mapper.readTree(finalRoot.resolve("formal-decision-import-manifest.json").toFile());
        HpoReviewDecisionValidator validator = new HpoReviewDecisionValidator();
        int terms = 0;
        int keepObserved = 0;
        int keepExcluded = 0;
        int semanticNotes = 0;
        Set<String> reviewIds = new HashSet<String>();

        assertEquals("HUMAN_REVIEW_COMPLETED", importManifest.path("status").asText());
        assertEquals("NOT_AUTHORIZED", importManifest.path("g2Status").asText());
        assertFalse(importManifest.toString().matches(".*[A-Za-z]:\\\\.*"));
        for (int number = 1; number <= 30; number++) {
            String reviewId = String.format("PILOT-HPO-%03d", number);
            HpoReviewPacket packet = mapper.readValue(reviewRoot.resolve("reviewer-visible/" + reviewId + ".packet.json").toFile(), HpoReviewPacket.class);
            Path decisionPath = finalRoot.resolve(reviewId + ".decision.json");
            assertTrue(Files.isRegularFile(decisionPath));
            HpoReviewDecisionDocument decision = mapper.readValue(decisionPath.toFile(), HpoReviewDecisionDocument.class);
            assertTrue(reviewIds.add(decision.getReviewId()));
            assertEquals(TruthMaskedHpoReviewPacketGenerator.DECISION_SCHEMA_VERSION, decision.getSchemaVersion());
            assertEquals(packet.getReviewProtocolVersion(), decision.getReviewProtocolVersion());
            assertEquals(packet.getReviewId(), decision.getReviewId());
            assertEquals(packet.getPacketFingerprint(), decision.getPacketFingerprint());
            assertEquals(packet.getPacketContentChecksum(), decision.getPacketContentChecksum());
            assertEquals(HpoReviewReadinessStatus.READY_IDENTICAL_TO_G1, validator.validate(packet, decision).getStatus());
            assertEquals("NOT_RECORDED", decision.getPriorTruthExposure());
            assertEquals(Boolean.FALSE, decision.getEligibleForStrictBlindedEvaluation());
            assertNotNull(decision.getAiAssistance());
            assertEquals(Boolean.TRUE, decision.getAiAssistance().getAiAssistanceUsed());
            assertEquals(Boolean.TRUE, decision.getAiAssistance().getHumanFinalConfirmation());
            assertEquals(packet.getHpoOntologyVersion(), decision.getAiAssistance().getSourceHpoOntologyVersion());
            assertTrue(decision.getAdditions().isEmpty());
            assertEquals(packet.getTerms().size(), decision.getTermDecisions().size());
            for (int index = 0; index < packet.getTerms().size(); index++) {
                HpoReviewTerm term = packet.getTerms().get(index);
                HpoReviewTermDecision decided = decision.getTermDecisions().get(index);
                assertEquals(term.getHpoId(), decided.getHpoId());
                assertEquals(term.getOriginalStatus(), decided.getOriginalStatus());
                assertEquals(term.getOriginalStatus() == HpoReviewOriginalStatus.OBSERVED
                        ? HpoReviewDecisionType.KEEP_OBSERVED : HpoReviewDecisionType.KEEP_EXCLUDED,
                        decided.getDecision());
                if (decided.getDecision() == HpoReviewDecisionType.KEEP_OBSERVED) keepObserved++;
                else keepExcluded++;
            }
            terms += decision.getTermDecisions().size();
            semanticNotes += decision.getSemanticAuditNotes().size();
        }
        assertEquals(30, reviewIds.size());
        assertEquals(577, terms);
        assertEquals(208, keepObserved);
        assertEquals(369, keepExcluded);
        assertEquals(2, semanticNotes);
        assertEquals(30, importManifest.path("summary").path("reviews").asInt());
        assertEquals(577, importManifest.path("summary").path("terms").asInt());
    }
}
