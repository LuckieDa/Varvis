package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog;
import org.nbscn.varvis.variantanalysis.service.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Context intake only: no scientific provider, patient diagnosis, or database execution. */
class AcmgFourteenContextKnowledgeTest {
    private static final String PREFIX = "variant-analysis.acmg-business.";
    private static final List<String> FLAGS = List.of("unified-local-workflow-enabled", "local-batch-workflow-enabled",
            "local-batch-bp7-enabled", "local-batch-pvs1-enabled", "local-batch-pm4-enabled", "local-batch-bp3-enabled",
            "local-batch-pm1-enabled", "local-batch-pp2-enabled", "local-batch-bp1-enabled");
    private final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();

    @Test void onlyCompleteFourteenRetainsContextKnowledgeWhileBs1ExecutionStaysDisabled() {
        var env = new MockEnvironment().withProperty(PREFIX + "bs1-knowledge-file", "synthetic.json")
                .withProperty(PREFIX + "bs1-knowledge-sha256", java.util.Objects.requireNonNull("a".repeat(64)));
        for (String flag : FLAGS) env.withProperty(PREFIX + flag, "true");
        var local = AcmgBusinessConfig.localWorkflowEnvironment(env);
        assertEquals("synthetic.json", local.getProperty(PREFIX + "bs1-knowledge-file"));
        assertEquals("a".repeat(64), local.getProperty(PREFIX + "bs1-knowledge-sha256"));
        assertEquals("false", local.getProperty(PREFIX + "bs1-local-exomes-enabled"));
        var before = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), local, null).identity();
        env.withProperty(PREFIX + "bs1-knowledge-sha256", java.util.Objects.requireNonNull("b".repeat(64)));
        var after = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), local, null).identity();
        assertNotEquals(before, after, "Changed context source pin must invalidate saved execution and confirmation identity");
        assertEquals("1.0.0", after.path("publishedContextKnowledgeBindingVersion").asText());
        for (String flag : FLAGS) {
            env.withProperty(PREFIX + flag, "false");
            assertEquals("", AcmgBusinessConfig.localWorkflowEnvironment(env).getProperty(PREFIX + "bs1-knowledge-file"), flag);
            env.withProperty(PREFIX + flag, "true");
        }
        assertEquals("synthetic.json", env.getProperty(PREFIX + "bs1-knowledge-file"));
    }

    @ParameterizedTest @ValueSource(strings={"exomiser-v1-00000005", "exomiser-v1-00000002", "exomiser-v1-00000001", "exomiser-v1-00000006"})
    void selectedRealPublishedRelationshipReachesBothIntakesWithTheSameProvenance(String candidateId) throws Exception {
        String directory = System.getProperty("varvis.fourteen.real-chain", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(directory.isBlank(), "Explicit fixed local research originals required");
        Path root = Path.of(directory); var env = new MockEnvironment();
        for (String name : List.of("evidence-base.properties", "evidence-overrides.properties")) {
            var properties = new java.util.Properties(); try (var in = Files.newInputStream(root.resolve(name))) { properties.load(in); }
            for (String key : properties.stringPropertyNames()) if (key.startsWith("variant-analysis.")) env.withProperty(key, java.util.Objects.requireNonNull(properties.getProperty(key)));
        }
        mapper.readTree(root.resolve("task-settings.json").toFile()).properties().forEach(e -> env.withProperty(java.util.Objects.requireNonNull(e.getKey()), java.util.Objects.requireNonNull(e.getValue().asText())));
        Path knowledge = Path.of(env.getRequiredProperty(PREFIX + "bs1-knowledge-file")); byte[] knowledgeBefore = Files.readAllBytes(knowledge);
        assertEquals(env.getRequiredProperty(PREFIX + "bs1-knowledge-sha256"), Bs1KnowledgeCatalog.sha(knowledgeBefore));
        JsonNode rawCandidate = null, rawHypothesis = null, recommendation = null;
        for (var c : mapper.readTree(root.resolve("formal-candidates.json").toFile())) if (candidateId.equals(c.path("candidateId").asText())) rawCandidate = c;
        for (var h : mapper.readTree(root.resolve("prepare-final-request.json").toFile()).path("hypotheses")) if (candidateId.equals(h.at("/hypothesis/candidateId").asText())) rawHypothesis = h.path("hypothesis");
        for (var c : mapper.readTree(root.resolve("prepare-final-options.json").toFile()).path("candidates")) if (candidateId.equals(c.path("candidateId").asText())) recommendation = c.path("recommendation");
        assertNotNull(rawCandidate); assertNotNull(rawHypothesis); assertNotNull(recommendation);
        var candidate = mapper.treeToValue(rawCandidate, VariantInterpretation.class);
        JsonNode candidateBefore = mapper.valueToTree(candidate);
        var hypothesis = mapper.treeToValue(rawHypothesis, PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis.class);
        var original = resolver(env); var localEnvironment = AcmgBusinessConfig.localWorkflowEnvironment(env); var local = resolver(localEnvironment);
        var originalBinding = original.resolve(candidate, List.of(hypothesis)).bindings().get(0);
        assertEquals("READY", originalBinding.status());
        boolean selectable = false;
        for (var r : recommendation.path("relationships")) if (hypothesis.hgncId().equals(r.path("hgncId").asText())
                && hypothesis.mondoId().equals(r.path("mondoId").asText()) && hypothesis.moi().equals(r.path("moi").asText())) selectable |= r.path("selectable").asBoolean();
        assertTrue(selectable, "Frozen real recommendation was selectable");
        var localBinding = local.resolve(candidate, List.of(hypothesis)).bindings().get(0);
        assertEquals("READY", localBinding.status(), "Selected relation lost in local intake: " + localBinding.reasonCode());
        assertEquals(originalBinding.provenance(), localBinding.provenance());
        var relationship = localBinding.provenance().relationshipRecords().get(0);
        assertEquals("BS1_VCEP_APPLICABLE", relationship.path("classification").asText());
        assertFalse(relationship.path("classificationIsGeneValidity").asBoolean(true));
        assertEquals(env.getRequiredProperty(PREFIX + "bs1-knowledge-sha256"), relationship.at("/relationshipSource/rawSha256").asText());
        assertArrayEquals(knowledgeBefore, Files.readAllBytes(knowledge));
        assertEquals(candidateBefore, mapper.valueToTree(candidate), "Intake must not alter formal candidate contents or rank");
    }

    private PatientDiseaseContextBindingResolver resolver(org.springframework.core.env.Environment environment) {
        var properties = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("variant-analysis", org.springframework.boot.context.properties.bind.Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(() -> new IllegalArgumentException("TEST_CONFIGURATION_MISSING"));
        var service = new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, properties, environment);
        var supplier = (Supplier<?>)ReflectionTestUtils.getField(java.util.Objects.requireNonNull(service), "runtime"); assertNotNull(supplier);
        var coordinator = supplier.get(); var resolver = ReflectionTestUtils.getField(java.util.Objects.requireNonNull(coordinator), "resolver"); assertNotNull(resolver);
        var configured = (PatientDiseaseContextBindingResolver)ReflectionTestUtils.invokeMethod(resolver, "withLocalInterpretationContexts");
        return java.util.Objects.requireNonNull(configured);
    }
}
