package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PredictionSpecializationGuardTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();

    ObjectNode catalog() {
        var data = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        data.putObject("metadata").put("status", "COMPLETE").put("entryCount", 1).put("snapshotId", "synthetic");
        data.putArray("entries").addObject().putObject("scope").putArray("hgncIds").add("HGNC:11005");
        return data;
    }
    PredictionSpecializationGuard load(ObjectNode data) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(data); Path path = temp.resolve("catalog.json"); Files.write(path, raw);
        return PredictionSpecializationGuard.load(mapper, path, PredictionCalibration.sha256(raw));
    }
    @Test void anyKnownGeneBlocksWithoutReusingDiseaseOrBa1Threshold() throws Exception {
        var guard = load(catalog()); var context = mapper.createObjectNode();
        context.putObject("gene").put("hgncId", "HGNC:11005");
        context.putObject("disease").put("mondoId", "MONDO:9999999");
        assertEquals("BLOCKED", guard.check(context).path("status").asText());
        context.withObject("/gene").put("hgncId", "HGNC:99999");
        assertEquals("NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT", guard.check(context).path("status").asText());
        assertFalse(guard.identity().path("worldwideAbsenceClaim").asBoolean());
        assertFalse(guard.identity().path("ba1ThresholdsUsed").asBoolean());
        guard.identity().put("catalogSha256", "changed");
        assertNotEquals("changed", guard.identity().path("catalogSha256").asText());
    }
    @Test void changedBytesIncompleteInventoryOrUnknownScopeCannotDeclareAbsence() throws Exception {
        var data = catalog(); load(data);
        assertThrows(IllegalArgumentException.class, () -> PredictionSpecializationGuard.load(mapper, temp.resolve("catalog.json"), "0".repeat(64)));
        data.withObject("/metadata").put("entryCount", 2);
        var incomplete = data;
        assertThrows(IllegalArgumentException.class, () -> load(incomplete));
        data = catalog(); ((ObjectNode) data.at("/entries/0/scope")).putArray("hgncIds");
        var unknown = data;
        assertThrows(IllegalArgumentException.class, () -> load(unknown));
        ((ObjectNode) data.at("/entries/0/scope")).put("sequenceScope", "MITOCHONDRIAL");
        assertEquals(0, load(data).identity().path("genes").asInt());
    }
}
