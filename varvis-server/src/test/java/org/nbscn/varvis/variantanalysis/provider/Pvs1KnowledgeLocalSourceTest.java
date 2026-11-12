package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit local source audit only. No scientific evaluation, network, resource mutation or synthetic approval. */
class Pvs1KnowledgeLocalSourceTest {
    @TempDir Path root;
    @Test void retainedG2pSnapshotLoadsAndReportsGapsWithoutAdoption() throws Exception {
        String file = System.getProperty("varvis.pvs1.knowledge.source");
        assumeTrue(file != null, "Explicit existing local snapshot required");
        var mapper = new ObjectMapper(); Path source = Path.of(java.util.Objects.requireNonNull(file));
        String pin = System.getProperty("varvis.pvs1.knowledge.source.sha256", "");
        var facts = mapper.readTree(Files.readAllBytes(source));
        assertEquals(3851, facts.path("records").size());
        var adoption = mapper.createObjectNode().put("schemaVersion", "pvs1-knowledge-adoption-v1")
                .put("catalogId", "PVS1_G2P_UNADOPTED_SOURCE_AUDIT").put("catalogVersion", "1")
                .put("sourceDataClass", "PUBLIC_REFERENCE").put("sourceCandidatesSha256", pin);
        adoption.putArray("entries"); byte[] bytes = mapper.writeValueAsBytes(adoption);
        Files.write(root.resolve("empty-adoption.json"), bytes);
        var catalog = Pvs1KnowledgeCatalog.load(mapper, source, pin, root.resolve("empty-adoption.json"), CodingTranscriptMaterialSupply.sha(bytes));
        var counts = new TreeMap<String, Integer>(); var seen = new LinkedHashSet<String>();
        for (var r : facts.path("records")) {
            String hgnc = r.path("hgncId").asText(), mondo = r.path("mondoId").asText(), moi = r.path("moiCandidate").asText();
            if (!hgnc.startsWith("HGNC:") || !mondo.startsWith("MONDO:") || "null".equals(moi)) continue;
            if (!seen.add(hgnc + "|" + mondo + "|" + moi)) continue;
            var context = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("moi", moi);
            context.putObject("gene").put("hgncId", hgnc); context.putObject("disease").put("mondoId", mondo);
            context.putObject("transcript").put("accession", "ENST099999999").put("version", 1);
            context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38");
            var result = catalog.assess(context, null);
            assertEquals("UNAVAILABLE", result.path("status").asText());
            assertFalse(result.path("clinicalAcceptance").asBoolean(true));
            counts.merge(result.path("reasonCode").asText(), 1, (existingCount, addedCount) -> existingCount + addedCount);
        }
        assertFalse(counts.isEmpty());
        var audit = mapper.createObjectNode().put("sourceRows", 3851).put("assessedAutosomalBackgrounds", seen.size())
                .put("newAdoptedBackgrounds", 0).put("coreExecutions", 0).put("purpose", "SOURCE_GAP_AUDIT_ONLY");
        audit.set("identity", catalog.identity()); audit.set("reasonCounts", mapper.valueToTree(counts));
        String output = System.getProperty("varvis.pvs1.knowledge.audit");
        if (output != null) {
            Path destination = Path.of(output);
            Files.writeString(destination, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(audit));
            Files.write(destination.resolveSibling(destination.getFileName() + ".adoption.json"), bytes);
        }
    }
}
