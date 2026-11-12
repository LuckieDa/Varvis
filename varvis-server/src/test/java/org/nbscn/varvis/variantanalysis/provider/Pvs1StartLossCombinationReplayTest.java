package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossCombinationReplayTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode originalRules() throws Exception {
        String root = System.getProperty("pvs1.nativeExpertRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "Native source root not configured");
        var path = Path.of(root, "local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture/raw-008-contents-0.json");
        byte[] raw = Files.readAllBytes(path);
        assertEquals("b47be311e6b44f781297b4e8ef78bdaaec0fced164f8d8b9d5984007a1dd6fe4", CodingTranscriptMaterialSupply.sha(raw));
        var entity = mapper.readTree(raw).at("/data/1");
        assertEquals("GN003", entity.path("entId").asText());
        return (ObjectNode) entity.at("/ld/RuleSet/0/entContent/rules");
    }
    @Test void nativeCountRulesReplayExplicitStrengthsWithoutInferringIndependence() throws Exception {
        var rules = originalRules();
        var out = Pvs1StartLossCombinationReplay.replay(mapper, rules, Set.of("PS2_Very Strong", "PS3_Moderate", "PM2_Supporting"));
        assertEquals("REPLAYED", out.path("status").asText(), out.toPrettyString());
        assertEquals("Pathogenic", out.path("classification").asText());
        assertEquals("Rule3", out.at("/matchedRules/0/rule").asText());
        assertFalse(out.path("scientificIndependenceEstablished").asBoolean());
        assertEquals("Likely Pathogenic", Pvs1StartLossCombinationReplay.replay(mapper, rules, Set.of("PS2_Very Strong", "PS3_Moderate")).path("classification").asText());
        assertEquals("Uncertain Significance", Pvs1StartLossCombinationReplay.replay(mapper, rules, Set.of("PM2_Supporting")).path("classification").asText());
    }
    @Test void conflictingBenignEvidenceAndDuplicateStrengthsAreNotHidden() throws Exception {
        var rules = originalRules();
        assertEquals("CONFLICT", Pvs1StartLossCombinationReplay.replay(mapper, rules,
                Set.of("PS2_Very Strong", "PS3_Moderate", "PM2_Supporting", "BA1")).path("status").asText());
        assertEquals("APPLIED_CRITERIA_DUPLICATE_OR_INVALID", Pvs1StartLossCombinationReplay.replay(mapper, rules,
                Set.of("PS3", "PS3_Moderate")).path("reasonCode").asText());
    }
    @Test void unknownConditionsOrUnmappedEvidencePreventPartialReplay() throws Exception {
        var rules = originalRules();
        assertEquals("APPLIED_CRITERION_NOT_IN_ORIGINAL_RULES", Pvs1StartLossCombinationReplay.replay(mapper, rules,
                Set.of("PS1_Supporting")).path("reasonCode").asText());
        ((ObjectNode) rules.at("/mainRules/0/conditions/0")).put("condition", ">=1 OR true");
        var out = Pvs1StartLossCombinationReplay.replay(mapper, rules, Set.of("PS2_Very Strong", "PS3_Moderate", "PM2_Supporting"));
        assertEquals("UNSUPPORTED", out.path("status").asText()); assertTrue(out.path("matchedRules").isEmpty());
    }
}
