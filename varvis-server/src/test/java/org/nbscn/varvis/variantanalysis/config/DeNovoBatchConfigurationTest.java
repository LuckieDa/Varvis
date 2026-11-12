package org.nbscn.varvis.variantanalysis.config;

import static java.util.Objects.requireNonNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.nbscn.varvis.variantanalysis.provider.DeNovoBatchFixture;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DeNovoBatchConfigurationTest {
    @TempDir Path temporary;
    @Test void disabledDoesNotReadFilesAndResearchNeverImplicitlyAcceptsSyntheticMaterials() throws Exception {
        var f = new DeNovoBatchFixture(temporary); var env = new MockEnvironment();
        var config = new LocalEvidenceBatchConfiguration(f.mapper, new VariantAnalysisProperties(), env, null);
        assertNull(config.deNovo());
        String p = "variant-analysis.acmg-business.local-batch-denovo-";
        env.setProperty(p + "enabled", "true"); env.setProperty(p + "material-file", requireNonNull(temporary.resolve("manifest.json").toString()));
        env.setProperty(p + "material-sha256", requireNonNull(DeNovoBatchFixture.sha(Files.readAllBytes(temporary.resolve("manifest.json")))));
        assertThrows(IllegalArgumentException.class, config::deNovo);
        env.setProperty(p + "use", "SYNTHETIC_VALIDATION"); assertNotNull(config.deNovo());
        Files.writeString(temporary.resolve("report.json"), "{}"); assertThrows(IllegalArgumentException.class, config::deNovo);
        env.setProperty(p + "enabled", "false"); assertNull(config.deNovo());
    }
    @Test void automaticSupplyIsOptInAndRequiresOriginalReader() throws Exception {
        var f=new DeNovoBatchFixture(temporary);var env=new MockEnvironment();String p="variant-analysis.acmg-business.local-batch-denovo-";
        env.withProperty(p+"enabled","true").withProperty(p+"use","SYNTHETIC_VALIDATION")
                .withProperty(p+"material-file",requireNonNull(temporary.resolve("manifest.json").toString()))
                .withProperty(p+"material-sha256",requireNonNull(DeNovoBatchFixture.sha(Files.readAllBytes(temporary.resolve("manifest.json")))))
                .withProperty(p+"source-supply-enabled","true")
                .withProperty(p+"literature-python",requireNonNull(System.getProperty("denovo.test.python","unused-python")))
                .withProperty(p+"literature-scripts",requireNonNull(System.getProperty("denovo.test.scripts","../scripts/denovo")))
                .withProperty(p+"literature-cache",requireNonNull(temporary.resolve("reader-cache").toString()))
                .withProperty(p+"source-supply-work",requireNonNull(temporary.resolve("work").toString()))
                .withProperty(p+"public-cache",requireNonNull(temporary.resolve("public-cache").toString()));
        var config=new LocalEvidenceBatchConfiguration(f.mapper,new VariantAnalysisProperties(),env,null);
        assertThrows(IllegalArgumentException.class,config::deNovo);
        env.withProperty(p+"literature-enabled","true");var module=config.deNovo();assertNotNull(module);
        assertFalse(module.identity().at("/sourceSupply/publicNetworkEnabled").asBoolean(true));
        assertEquals("ASYNCHRONOUS",module.identity().at("/sourceSupply/executionMode").asText());
        assertEquals(0,module.sourceSupplyCalls());assertFalse(Files.exists(temporary.resolve("work")));
    }
    @Test void modelReplayIsExplicitAndChangesConfigurationIdentityWithoutProviderAccess() throws Exception {
        var f = new DeNovoBatchFixture(temporary); f.addSyntheticLiterature();
        f.mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String p = "variant-analysis.acmg-business.local-batch-denovo-";
        var env = new MockEnvironment().withProperty(p + "enabled", "true").withProperty(p + "use", "SYNTHETIC_VALIDATION")
                .withProperty(p + "material-file", requireNonNull(temporary.resolve("manifest.json").toString()))
                .withProperty(p + "material-sha256", requireNonNull(DeNovoBatchFixture.sha(Files.readAllBytes(temporary.resolve("manifest.json")))))
                .withProperty(p + "literature-enabled", "true")
                .withProperty(p + "literature-python", requireNonNull(System.getProperty("denovo.test.python", "missing-python")))
                .withProperty(p + "literature-scripts", requireNonNull(System.getProperty("denovo.test.scripts", "../scripts/denovo")))
                .withProperty(p + "literature-cache", requireNonNull(temporary.resolve("reader-cache").toString()));
        var config = new LocalEvidenceBatchConfiguration(f.mapper, new VariantAnalysisProperties(), env, null);
        var before = config.identity();
        Path index = temporary.resolve("model-index.json"); Files.writeString(index, "{}");
        env.withProperty(p + "model-artifact-index", requireNonNull(index.toString())).withProperty(p + "model-artifact-sha256", requireNonNull("a".repeat(64)));
        assertNotEquals(before, config.identity());
        var module = config.deNovo(); assertNotNull(module);
        assertEquals("DENOVO_MODEL_INDEX_PIN_MISMATCH", module.identity().path("literatureQualification").asText());
        assertFalse(module.identity().at("/modelArtifactReplay/automaticModelCalls").asBoolean(true));
        assertFalse(Files.exists(temporary.resolve("reader-cache")));
    }
}
