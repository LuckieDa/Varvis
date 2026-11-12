package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Constructor wiring only. Empty synthetic catalogs grant no candidate or reference qualification. */
class ProteinLengthMaterialConfigurationTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final String PREFIX = "variant-analysis.acmg-business.";

    @Test void onlyProteinLengthFactoryGetsCodeBoundCapabilityAndSharedIdentityStaysUnchanged() throws Exception {
        var environment = configured();
        var configuration = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), environment, null);
        var general = java.util.Objects.requireNonNull(configuration.transcriptMaterials());
        var length = java.util.Objects.requireNonNull(configuration.proteinLengthMaterials());
        assertFalse(general.complexAllelesEnabled());
        assertFalse(general.proteinLengthChangeIdentity().has("sequenceCapability"));
        assertTrue(length.complexAllelesEnabled());
        var capability = length.proteinLengthChangeIdentity().path("sequenceCapability");
        assertEquals("A5_NATIVE_GRCH38_SMALL_ALLELE_SEQUENCE_FACTS", capability.path("capabilityId").asText());
        assertFalse(capability.path("qualificationGranted").asBoolean(true));
        assertFalse(capability.path("scoringPermissionGranted").asBoolean(true));
        assertEquals(general.identity(), length.identity(), "General consumers must not inherit the length-only capability");
        assertNotSame(length, configuration.proteinLengthMaterials(), "Mutable caches belong to the request's lazy runtime");
        assertFalse(java.util.Objects.requireNonNull(configuration.transcriptMaterials()).complexAllelesEnabled());
        assertFalse(configuration.identity().has("proteinLengthMaterialFactoryVersion"));
        environment.withProperty(PREFIX + "local-batch-pm4-enabled", "true");
        var enabledIdentity = configuration.identity();
        assertEquals("1.0.0", enabledIdentity.path("proteinLengthMaterialFactoryVersion").asText());
        assertEquals("A5_NATIVE_GRCH38_SMALL_ALLELE_SEQUENCE_FACTS",
                enabledIdentity.path("proteinLengthMaterialCapability").asText(),
                "A fresh run must not reuse the prior simple-only factory identity");
    }

    @Test void dedicatedFactoryStillRejectsChangedReferenceReceiptAndModelManifest() throws Exception {
        var environment = configured();
        var configuration = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), environment, null);
        Files.writeString(temporary.resolve("receipt.json"), "{}");
        assertEquals("TRANSCRIPT_MATERIAL_CONFIGURATION_UNAVAILABLE", assertThrows(IllegalArgumentException.class,
                configuration::proteinLengthMaterials).getMessage());
        environment = configured();
        configuration = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), environment, null);
        Files.writeString(temporary.resolve("models/manifest.json"), "{}");
        var changed = configuration;
        assertThrows(IllegalArgumentException.class, changed::proteinLengthMaterials);
        assertThrows(IllegalArgumentException.class, changed::transcriptMaterials);
    }

    @Test void absentConfigurationKeepsOriginalUnavailableBehavior() {
        var configuration = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), new MockEnvironment(), null);
        assertNull(configuration.transcriptMaterials());
        assertNull(configuration.proteinLengthMaterials());
        assertEquals("PM4_CONFIGURATION_UNAVAILABLE", assertThrows(IllegalArgumentException.class, configuration::pm4).getMessage());
    }

    private MockEnvironment configured() throws Exception {
        var models = temporary.resolve("models"); Files.createDirectories(models);
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries");
        byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
        Files.write(models.resolve("manifest.json"), manifestBytes);
        var receipt = mapper.createObjectNode().put("source", "SYNTHETIC_CONFIG_ONLY").put("version", "1")
                .put("dataClass", "SYNTHETIC");
        receipt.putObject("records").putObject("reference").put("purpose", "constructor test, not a FASTA receipt");
        byte[] receiptBytes = mapper.writeValueAsBytes(receipt);
        Files.write(temporary.resolve("receipt.json"), receiptBytes);
        return new MockEnvironment().withProperty(PREFIX + "transcript-material-directory", java.util.Objects.requireNonNull(models.toString()))
                .withProperty(PREFIX + "transcript-material-manifest-sha256", java.util.Objects.requireNonNull(sha(manifestBytes)))
                .withProperty(PREFIX + "reference-receipt-file", java.util.Objects.requireNonNull(temporary.resolve("receipt.json").toString()))
                .withProperty(PREFIX + "reference-receipt-sha256", java.util.Objects.requireNonNull(sha(receiptBytes)))
                .withProperty(PREFIX + "reference-receipt-locator", "reference")
                .withProperty(PREFIX + "resource-root", java.util.Objects.requireNonNull(temporary.toString()));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
