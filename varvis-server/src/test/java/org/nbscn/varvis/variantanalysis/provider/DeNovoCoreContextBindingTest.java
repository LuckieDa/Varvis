package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** A cached qualification must depend on each nested original, not just its manifest. */
class DeNovoCoreContextBindingTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode file(String name, byte[] raw) throws Exception {
        Path p = root.resolve(name); Files.createDirectories(p.getParent()); Files.write(p, raw);
        return mapper.createObjectNode().put("path", name).put("sha256", FamilyDeNovoJson.sha256(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"denovo-refseq-snv-cache-v1", "denovo-refseq-deletion-cache-v1"})
    void nestedRefseqOriginalsInvalidateQualifiedCache(String schema) throws Exception {
        file("scripts/acmg-generalization/normalize_public_panel.py", new byte[]{1});
        file("scripts/acmg-generalization/build_transcript_model.py", new byte[]{2});
        ObjectNode raw = file("local/contextual.raw", new byte[]{3,4});
        String transcript = "NM_000001.2";
        String coding = schema.contains("deletion") ? "c.2_4del" : "c.2A>G";
        ObjectNode manifest = mapper.createObjectNode().put("schemaVersion", schema)
                .put("transcript", transcript).put("coding", coding);
        manifest.putObject("sources").set("contextual", raw);
        ObjectNode binding = file("local/manifest.json", mapper.writeValueAsBytes(manifest));
        ObjectNode config = mapper.createObjectNode().put("schemaVersion", "denovo-core-context-config-v1");
        config.putObject("identity").putArray("refseqMappings").addObject()
                .put("transcript", transcript).put("coding", coding).set("manifest", binding);
        ObjectNode configFile = file("local/config.json", mapper.writeValueAsBytes(config));
        var reader = new DeNovoCoreContextBinding(mapper, root, root.resolve("local/config.json"), configFile.path("sha256").asText());
        var dependencies = reader.snapshot().path("dependencies");
        assertEquals(raw.path("sha256"), dependencies.path(root.relativize(root.resolve("local/contextual.raw")).toString()));
        Files.write(root.resolve("local/contextual.raw"), new byte[]{3,5});
        assertTrue(assertThrows(java.io.IOException.class, reader::snapshot).getMessage().contains("DEPENDENCY_CHANGED"));
    }
}
