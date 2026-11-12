package org.nbscn.varvis.variantanalysis.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.provider.Pp1SelectedBatch;
import org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture;
import org.nbscn.varvis.variantanalysis.service.T04CurrentSessionFixture;
import org.springframework.mock.env.MockEnvironment;

/** Actual optional server-configuration loading; no model, network or result-factory stub. */
class T04CurrentInputConfigurationTest {
    private static final String PREFIX = "variant-analysis.acmg-business.";
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private MockEnvironment environment(String manifest) {
        return new MockEnvironment()
                .withProperty(PREFIX + "local-batch-pp1-literature-enabled", "true")
                .withProperty(PREFIX + "local-batch-pp1-current-input-file", java.util.Objects.requireNonNull(manifest))
                .withProperty(PREFIX + "local-batch-denovo-public-network-enabled", "true")
                .withProperty(PREFIX + "local-batch-denovo-model-artifact-index", java.util.Objects.requireNonNull(temporary.resolve("model-input.json").toString()))
                .withProperty(PREFIX + "pm4-region-demand-network-enabled", "false")
                .withProperty("varvis.literature-workflow-config", java.util.Objects.requireNonNull(temporary.resolve("no-workflow-config.json").toString()));
    }
    private VariantAnalysisProperties properties() {
        var value = new VariantAnalysisProperties();
        value.getLlmShadow().setEnabled(true); value.getLlmShadow().setProvider("SYNTHETIC_CONFIGURATION_SENTINEL");
        return value;
    }
    private JsonNode environmentSnapshot(MockEnvironment env) {
        var source = env.getPropertySources().get("mockProperties");
        if (source == null) throw new AssertionError("mock property source missing");
        return json.valueToTree(source.getSource());
    }
    private void unchanged(MockEnvironment env, VariantAnalysisProperties properties, JsonNode envBefore, JsonNode propertiesBefore) {
        assertEquals(envBefore, environmentSnapshot(env));
        assertEquals(propertiesBefore, json.valueToTree(properties));
        assertFalse(Files.exists(temporary.resolve("model-input.json")));
        assertFalse(Files.exists(temporary.resolve("no-workflow-config.json")));
    }

    @Test void blankManifestPreservesLegacySourceAndGivenModelNetworkSettings() {
        var env = environment("   "); var properties = properties();
        JsonNode envBefore = environmentSnapshot(env), propsBefore = json.valueToTree(properties);
        var config = new LocalEvidenceBatchConfiguration(json, properties, env, null);
        var batch = Objects.requireNonNull(config.pp1());
        assertTrue(batch.currentNativeFactory().isEmpty());
        assertEquals("NOT_CONFIGURED", batch.identity().at("/contributionSource/status").asText());
        assertFalse(batch.identity().has("currentInputReadiness"));
        var preparation = batch.prepare("configuration-legacy", json.createObjectNode());
        assertEquals("UNKNOWN", preparation.at("/contributionPreparation/status").asText());
        assertNotEquals("PP1_CURRENT_REGISTERED_INPUTS_UNAVAILABLE", preparation.at("/contributionPreparation/reasonCode").asText());
        unchanged(env, properties, envBefore, propsBefore);
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void genuineSyntheticBundlePinsEnterStablePreclaimIdentityWithoutRunningTheProducer() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("engine"), "config-analysis", "config-run", List.of(Pp1SelectedBatch.CRITERION))) {
            var context = T04NativeOriginalFixture.context(fixture);
            var originals = new T04NativeOriginalFixture(fixture, context); originals.nativeSampleLink(fixture, context);
            Path manifest = originals.writeServerBundle(temporary.resolve("registered"), context);
            var env = environment(manifest.toString()); var properties = properties();
            JsonNode envBefore = environmentSnapshot(env), propsBefore = json.valueToTree(properties);
            var config = new LocalEvidenceBatchConfiguration(json, properties, env, null);
            var first = Objects.requireNonNull(config.pp1()); var next = Objects.requireNonNull(config.pp1());
            var factory = first.currentNativeFactory().orElseThrow(); var registered = factory.registeredInputs().orElseThrow();
            assertNotSame(factory.producer(), next.currentNativeFactory().orElseThrow().producer(), "each preparation owns its one-time binding");
            ObjectNode identity = first.identity(); assertEquals(identity, next.identity());
            assertEquals(sha(Files.readAllBytes(manifest)), identity.at("/contributionSource/producerIdentity/serverRegisteredInputs/manifestRawSha256").asText());
            assertEquals(sha(registered.rules().originalBytes()), identity.at("/contributionSource/producerIdentity/registeredRuleInputs/templateRawSha256").asText());
            assertEquals(6, identity.at("/contributionSource/producerIdentity/registration/registeredInputs/originals").size());
            for (var original : identity.at("/contributionSource/producerIdentity/registration/registeredInputs/originals"))
                assertTrue(registered.archiveArtifacts().containsKey(original.path("rawSha256").asText()));
            var hashes = new AcmgBusinessArtifacts(json); String before = hashes.requestHash(identity);
            var caller = first.identity(); caller.withObject("/contributionSource/producerIdentity/serverRegisteredInputs").put("manifestRawSha256", "0".repeat(64));
            assertEquals(before, hashes.requestHash(first.identity())); assertNotEquals(before, hashes.requestHash(caller));
            assertEquals(0, first.evaluations()); assertTrue(first.originals().isEmpty());
            unchanged(env, properties, envBefore, propsBefore);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
    void badRawPinFallsBackToExplicitUnknownWithoutBlockingOtherPreparation() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("engine"), "config-analysis", "config-run", List.of(Pp1SelectedBatch.CRITERION))) {
            var context = T04NativeOriginalFixture.context(fixture);
            var originals = new T04NativeOriginalFixture(fixture, context); originals.nativeSampleLink(fixture, context);
            Path manifest = originals.writeServerBundle(temporary.resolve("bad"), context);
            var changed = (ObjectNode) json.readTree(Files.readAllBytes(manifest));
            ((ObjectNode) changed.at("/originals/0")).put("rawSha256", "0".repeat(64)); Files.write(manifest, json.writeValueAsBytes(changed));
            assertUnknownFallback(fixture, context, manifest, "PP4_SERVER_ORIGINAL_PIN_MISMATCH");
        }
    }

    @Test void missingConfiguredFileIsAnInputFailureAndNeverAZeroContribution() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(temporary.resolve("engine"), "config-analysis", "config-run", List.of(Pp1SelectedBatch.CRITERION))) {
            assertUnknownFallback(fixture, T04NativeOriginalFixture.context(fixture), temporary.resolve("missing-manifest.json"), null);
        }
    }

    private void assertUnknownFallback(T04CurrentSessionFixture fixture, ObjectNode context, Path manifest, String preciseCause) throws Exception {
        var env = environment(manifest.toString()); var properties = properties();
        JsonNode envBefore = environmentSnapshot(env), propsBefore = json.valueToTree(properties);
        var config = new LocalEvidenceBatchConfiguration(json, properties, env, null);
        ObjectNode population = config.populationIdentity();
        var batch = Objects.requireNonNull(assertDoesNotThrow(config::pp1)); assertTrue(batch.currentNativeFactory().isEmpty());
        assertEquals("UNAVAILABLE", batch.identity().at("/currentInputReadiness/status").asText());
        if (preciseCause != null) assertEquals(preciseCause, batch.identity().at("/currentInputReadiness/reasonCode").asText());
        assertEquals(population, config.populationIdentity(), "PP1 source failure does not prevent unrelated resource preparation");
        var session = fixture.issue(context);
        String hypothesis = session.attempts().get(0).path("hypothesisId").asText();
        var prepared = batch.prepare(fixture.analysisId(), json.createObjectNode());
        assertEquals("UNKNOWN", prepared.at("/contributionPreparation/status").asText());
        assertEquals("PP1_CURRENT_REGISTERED_INPUTS_UNAVAILABLE", prepared.at("/contributionPreparation/reasonCode").asText());
        var result = batch.evaluate(fixture.prepared(), fixture.candidateId(), hypothesis, fixture.executionSourceSha256(), List.of(Pp1SelectedBatch.CRITERION));
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText()); assertEquals("NONE", result.path("appliedStrength").asText());
        assertEquals("PP1_CURRENT_REGISTERED_INPUTS_UNAVAILABLE", result.at("/evaluationResult/reasonCode").asText());
        assertEquals("UNKNOWN", result.at("/publicSummary/contributionPreparation/status").asText());
        for (JsonNode points : result.findValues("pp4Points")) assertTrue(points.isNull(), "unknown input cannot issue a zero");
        unchanged(env, properties, envBefore, propsBefore);
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
