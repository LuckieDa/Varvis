package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElevenEvidenceProfileTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode population() {
        var p = mapper.createObjectNode(); p.putArray("enabledCriteria").add("ACMG_AMP_2015:BA1").add("ACMG_AMP_2015:BS1");
        p.putObject("bs1RuntimeBinding").put("activationContract", "LOCAL_EXOMES_BUSINESS_V1"); return p;
    }
    ObjectNode binding() { var b = mapper.createObjectNode(); b.putObject("bp7").put("synthetic", true); return b; }
    @Test void elevenRequiresAllThreeNewModulesAndCannotReuseTheEightRuleConfirmation() {
        var b = binding().put("pm4Configured", true).put("pvs1Configured", true).put("bp3Configured", true);
        var p = AcmgExecutionProfile.unifiedLocal(mapper, b, population()).document();
        assertEquals("acmg-unified-local-eleven-v1", p.path("profileId").asText());
        assertEquals("ordinary-unified-local-eleven-v1", p.path("requiredDeclaration").asText());
        assertEquals(mapper.valueToTree(List.of("BA1","BS1","PM2","PP3","BP4","PS1","PM5","BP7","PVS1","PM4","BP3")
                .stream().map(s -> "ACMG_AMP_2015:" + s).toList()), p.path("enabledCriteria"));
        for (String name : List.of("pvs1", "pm4", "bp3")) assertTrue(p.at("/moduleBindings/"+name).isObject());
        assertFalse(p.path("classificationEnabled").asBoolean(true));
        for (String field : List.of("pm4Configured", "pvs1Configured", "bp3Configured", "bp7")) {
            var partial = b.deepCopy(); partial.remove(field);
            assertThrows(IllegalArgumentException.class, () -> AcmgExecutionProfile.unifiedLocal(mapper, partial, population()));
        }
    }
    @Test void eightRetainsItsExistingIdentityAndCriteria() {
        var p = AcmgExecutionProfile.unifiedLocal(mapper, binding(), population()).document();
        assertEquals("acmg-unified-local-eight-v1", p.path("profileId").asText()); assertEquals(8, p.path("enabledCriteria").size());
        assertEquals("ordinary-unified-local-eight-v1", p.path("requiredDeclaration").asText());
        assertFalse(p.path("moduleBindings").has("pvs1")); assertFalse(p.path("moduleBindings").has("bp3"));
    }

    @Test void fourteenPinsItsExplicitScopeAndRejectsEveryPartialConfiguration() {
        var b = binding().put("pm4Configured", true).put("pvs1Configured", true).put("bp3Configured", true)
                .put("pm1Configured", true).put("pp2Configured", true).put("bp1Configured", true).put("pp2GermlineDeclared", false);
        var p = AcmgExecutionProfile.unifiedLocal(mapper, b, population()).document();
        assertEquals("acmg-unified-local-fourteen-v1", p.path("profileId").asText());
        assertEquals(AcmgBusinessRuleScope.UNIFIED_FOURTEEN_DECLARATION, p.path("requiredDeclaration").asText());
        assertEquals(14, p.path("enabledCriteria").size());
        assertEquals("1.5.0-published-three", AcmgExecutionProfile.localBatch(mapper, b).document().path("profileVersion").asText());
        assertFalse(p.path("pp2GermlineDeclared").asBoolean(true));
        for (String name : List.of("pm1", "pp2", "bp1")) assertTrue(p.at("/moduleBindings/"+name).isObject());
        for (String field : List.of("pm1Configured", "pp2Configured", "bp1Configured", "pvs1Configured", "bp3Configured", "pm4Configured", "bp7", "pp2GermlineDeclared")) {
            var partial = b.deepCopy(); partial.remove(field);
            assertThrows(IllegalArgumentException.class, () -> AcmgExecutionProfile.unifiedLocal(mapper, partial, population()), field);
        }
        b.put("pp2GermlineDeclared", true);
        assertNotEquals(p.path("manifestSha256"), AcmgExecutionProfile.unifiedLocal(mapper,b,population()).document().path("manifestSha256"));
    }

}
