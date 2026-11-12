package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcmgHistoricalExecutionProfilesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sameJointV1NameRetainsThreePublishedIdentities() {
        var main = AcmgHistoricalExecutionProfiles.load(mapper, "acmg-explicit-business-joint-v1",
                "313b0b82bc836d7a1ec379fb68e0a9e06e55eafcf5699793064346506d6de2b9");
        var oldFunctional = AcmgHistoricalExecutionProfiles.load(mapper, "acmg-explicit-business-joint-v1",
                "3e25a0a886e654aa30b52570d4b0ace89a1515900de3077a0538a811688bdd24");
        var functional = AcmgHistoricalExecutionProfiles.load(mapper, "acmg-explicit-business-joint-v1",
                "f71fde7c4265a393dec414235421fdb855d449162f17602e642d6334af20f798");
        assertEquals("1.2.0", main.path("manifestVersion").asText());
        assertEquals("1.3.0", oldFunctional.path("manifestVersion").asText());
        assertEquals("1.3.1", functional.path("manifestVersion").asText());
        assertEquals(8, main.path("enabledCriteria").size());
        assertEquals(9, functional.path("enabledCriteria").size());
        assertNotEquals(oldFunctional, functional);
    }

    @Test void sameJointV4NameAndVersionCannotConfuseRevelAndBs1() {
        var revel = AcmgHistoricalExecutionProfiles.load(mapper, "acmg-explicit-business-joint-v4",
                "769b9868f1f9977b69fec72bbee0cec5b08e97d078cd723aa61fd4226b20208b");
        var bs1 = AcmgHistoricalExecutionProfiles.load(mapper, "acmg-explicit-business-joint-v4",
                "57a357360ea9312ae7f4a3c37f9bf3ec9da30c9ed43716cdbccc01a09874e4c1");
        assertEquals(revel.path("manifestVersion"), bs1.path("manifestVersion"));
        assertEquals(8, revel.path("enabledCriteria").size());
        assertEquals(9, bs1.path("enabledCriteria").size());
        assertNotEquals(revel, bs1);
        assertThrows(IllegalArgumentException.class, () -> AcmgHistoricalExecutionProfiles.load(
                mapper, "acmg-explicit-business-joint-v1", "57a357360ea9312ae7f4a3c37f9bf3ec9da30c9ed43716cdbccc01a09874e4c1"));
    }

    @Test void directPredecessorsRequireEveryRegisteredIdentityField() throws Exception {
        var bindings = predecessors();
        AcmgHistoricalExecutionProfiles.validatePredecessors(mapper, bindings);
        for (String field : List.of("profileId", "manifestVersion", "manifestCanonicalSha256", "rawSha256", "resourceClasspath", "sourceCommit")) {
            var changed = bindings.deepCopy();
            ((ObjectNode) changed.get(0)).put(field, "tampered");
            assertThrows(IllegalArgumentException.class, () -> AcmgHistoricalExecutionProfiles.validatePredecessors(mapper, changed), field);
        }
        bindings.add(bindings.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> AcmgHistoricalExecutionProfiles.validatePredecessors(mapper, bindings));
        assertThrows(IllegalArgumentException.class, () -> AcmgHistoricalExecutionProfiles.validatePredecessors(mapper, mapper.createArrayNode()));
    }

    @Test void originalFunctionalFactsAndProfilesRemainBoundToTheirOwnVersion() {
        String oldFactsHash = "105b6224195bfb33a6bcc9580d2f885203bcb808ef1cff3f45692eeeaaac5046";
        String newFactsHash = "a70b1e1640293bd43897a7a8d8517fbc1b3fdcb9e0b45ff6bb24113bf341a427";
        var oldFacts = AcmgHistoricalExecutionProfiles.loadScientificSnapshot(mapper, oldFactsHash);
        var newFacts = AcmgHistoricalExecutionProfiles.loadScientificSnapshot(mapper, newFactsHash);
        var oldProfile = AcmgHistoricalExecutionProfiles.loadScientificSnapshot(mapper,
                "d9a6375c6adf8eb6a92258d9d703a907e4c6c9a60ef58f121b36ac5d42bb5184");
        var newProfile = AcmgHistoricalExecutionProfiles.loadScientificSnapshot(mapper,
                "aecb00a1f5f0c65cba3484e0d1dc4b62856fa6e7d66f7219eb1915bf4044970a");
        assertEquals(oldFactsHash, oldProfile.at("/factPackage/canonicalSha256").asText());
        assertEquals(newFactsHash, newProfile.at("/factPackage/canonicalSha256").asText());
        assertEquals("1.0.0", oldProfile.path("profileVersion").asText());
        assertEquals("1.0.1", newProfile.path("profileVersion").asText());
        assertEquals(oldFacts.path("records"), newFacts.path("records"));
        assertNotEquals(oldFacts, newFacts);
        assertFalse(newProfile.at("/policy/bs3/enabled").asBoolean(true));
    }

    private ArrayNode predecessors() throws Exception {
        try (var input = getClass().getResourceAsStream("/acmg/history/execution-profile-history-v1.json")) {
            if (input == null) throw new IllegalStateException("Missing historical catalog");
            var catalog = mapper.readTree(input);
            if (catalog == null) throw new IllegalStateException("Missing historical catalog JSON");
            var result = mapper.createArrayNode();
            for (var entry : catalog.path("profiles")) {
                String path = entry.path("resourceClasspath").asText();
                if (path.endsWith("joint-v6.json") || path.contains("/bs1-335c8ac/") || path.contains("/ps3-ee7289c/"))
                    result.add(entry.deepCopy());
            }
            assertEquals(3, result.size());
            return result;
        }
    }
}
