package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** TEST_ONLY actual configuration factory, no application server or scientific resources. */
class Pvs1NmdBusinessConfigTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
    private MockEnvironment scope() { return new MockEnvironment().withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.PVS1_DEVELOPMENT); }
    private AcmgBusinessService service(MockEnvironment env) { return new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env); }
    private AcmgExecutionProfile profile(AcmgBusinessService service) {
        Object value = ReflectionTestUtils.getField(Objects.requireNonNull(service), "executionProfile");
        if (!(value instanceof AcmgExecutionProfile profile)) throw new AssertionError("Missing profile");return profile;
    }
    @Test void escapeOperatorChangesOnlyNewBundleAlgorithmIdentity() throws Exception {
        var oldFixture=new Pvs1NmdBusinessFixture(mapper,false);
        var oldDoc=oldFixture.document();
        var oldBinding=new ReviewedRuleBundle(mapper,oldDoc,oldFixture.canonical(oldDoc)).identity();
        var nextFixture=new Pvs1NmdBusinessFixture(mapper,false,true);
        var nextDoc=nextFixture.document();
        var nextBinding=new ReviewedRuleBundle(mapper,nextDoc,nextFixture.canonical(nextDoc)).identity();
        String path="/algorithms/"+TruncatingNmdPvs1Runtime.EVALUATOR_ID;
        assertEquals("1.1.0",oldBinding.at(path).asText());assertEquals("1.2.0",nextBinding.at(path).asText());
        assertNotEquals(oldBinding.path("bundleSha256"),nextBinding.path("bundleSha256"));
    }
    @Test void defaultCurrentAndDisabledModeRemainClosed() {
        var env = new MockEnvironment().withProperty("variant-analysis.acmg-business.pvs1-bundle-file", "bad" + (char) 0);
        assertEquals(profile(service(new MockEnvironment())).document(), profile(service(env)).document());
        assertEquals(AcmgBusinessRuleScope.CURRENT, profile(service(env)).criteria());
        assertEquals("PVS1_BUSINESS_EXECUTION_DISABLED", assertThrows(IllegalArgumentException.class, () -> service(scope())).getMessage());
        assertEquals("PVS1_TRUSTED_BUNDLE_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> service(scope().withProperty("variant-analysis.acmg-business.enabled", "true"))).getMessage());
        for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
            assertEquals("PVS1_EXECUTION_MODE_CONFLICT", assertThrows(IllegalArgumentException.class, () -> service(scope()
                    .withProperty("variant-analysis.acmg-business.enabled", "true").withProperty("variant-analysis.acmg-business." + flag, "true"))).getMessage());
    }
    @Test void fixedRawAndCanonicalPinsAndSingleRuleIdentityAreRequired() throws Exception {
        var fixture = new Pvs1NmdBusinessFixture(mapper, false);var d = fixture.document();
        Path file = temporary.resolve("TEST_ONLY.json");byte[] bytes = mapper.writeValueAsBytes(d);Files.write(file, bytes);
        String raw = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var env = scope().withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.pvs1-bundle-file", Objects.requireNonNull(file.toString()))
                .withProperty("variant-analysis.acmg-business.pvs1-bundle-file-sha256", Objects.requireNonNull(raw))
                .withProperty("variant-analysis.acmg-business.pvs1-bundle-sha256", Objects.requireNonNull(fixture.canonical(d)));
        var p = profile(service(env));assertEquals(List.of(TruncatingNmdPvs1Runtime.CRITERION), p.criteria());
        assertEquals(AcmgBusinessRuleScope.PVS1_DECLARATION, p.document().path("requiredDeclaration").asText());
        for (String flag : List.of("formalEnabled", "automaticTrigger", "classificationEnabled", "adoptionSupported")) assertFalse(p.document().path(flag).asBoolean(true));
        assertEquals(raw, p.document().at("/reviewedRuleBinding/bundleRawSha256").asText());
        Files.writeString(file, "\n", StandardOpenOption.APPEND);
        assertEquals("PVS1_TRUSTED_BUNDLE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> service(env)).getMessage());
        Files.write(file, bytes);env.withProperty("variant-analysis.acmg-business.pvs1-bundle-sha256", Objects.requireNonNull("0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> service(env));
    }
    @Test void oldV1IdentityIsPreservedAndCannotEnableTheSingleRuleMode() throws Exception {
        var f = new Pvs1NmdBusinessFixture(mapper, false);var d = f.document();
        var single = new ReviewedRuleBundle(mapper, d, f.canonical(d));
        assertThrows(IllegalArgumentException.class, () -> single.evaluate(FunctionalEvidenceRuntime.PS3, mapper.createObjectNode(), null));
        d.put("schemaVersion", "reviewed-rule-bundle-v1");var legacy = new ReviewedRuleBundle(mapper, d, f.canonical(d));
        assertEquals(2, legacy.identity().path("criterionIds").size());assertFalse(legacy.supportsPvs1Only());
        assertEquals("reviewed-rule-bundle-binding-v1", legacy.identity().path("schemaVersion").asText());
        assertThrows(IllegalArgumentException.class, () -> AcmgExecutionProfile.pvs1Development(mapper, legacy));
        d.put("schemaVersion", "reviewed-rule-bundle-pvs1-v6").withObject("review").put("status", "DRAFT");
        assertThrows(IllegalArgumentException.class, () -> new ReviewedRuleBundle(mapper, d, f.canonical(d)));
    }

    @Test void materialSupplyIsExplicitAndCannotStartWithoutLocalSources() {
        var env = scope().withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.pvs1-local-material-supply-enabled", "true");
        var failure = assertThrows(IllegalArgumentException.class, () -> service(env));
        assertEquals("PVS1_TRUSTED_BUNDLE_REQUIRED", failure.getMessage());
        assertEquals("PVS1_LOCAL_TRANSCRIPT_MATERIAL_REQUIRED", failure.getCause().getMessage());
    }

    @Test void selectedKnowledgeCannotSilentlyEnableMaterialSupplyOrOtherScopes() {
        var flag = "variant-analysis.acmg-business.pvs1-selected-knowledge-enabled";
        assertEquals(profile(service(new MockEnvironment())).document(), profile(service(new MockEnvironment().withProperty(flag, "true"))).document());
        var failure = assertThrows(IllegalArgumentException.class, () -> service(scope()
                .withProperty("variant-analysis.acmg-business.enabled", "true").withProperty(flag, "true")));
        assertEquals("PVS1_SELECTED_KNOWLEDGE_MATERIAL_SUPPLY_REQUIRED", failure.getCause().getMessage());
    }

    @Test void applicabilitySourcesRequireBothPinsAndCannotActivateDefaultScope() {
        String p="variant-analysis.acmg-business.";
        var env=new MockEnvironment().withProperty(p+"pvs1-knowledge-source-file",java.util.Objects.requireNonNull(temporary.resolve("facts.json").toString()))
                .withProperty(p+"pvs1-knowledge-source-sha256",java.util.Objects.requireNonNull("a".repeat(64)))
                .withProperty(p+"pvs1-knowledge-adoption-file",java.util.Objects.requireNonNull(temporary.resolve("adoption.json").toString()))
                .withProperty(p+"pvs1-knowledge-adoption-sha256",java.util.Objects.requireNonNull("b".repeat(64)))
                .withProperty(p+"pvs1-applicability-source-file",java.util.Objects.requireNonNull(temporary.resolve("sources.json").toString()));
        assertEquals("PVS1_APPLICABILITY_SOURCE_BINDING_REQUIRED",assertThrows(IllegalArgumentException.class,
                ()->AcmgBusinessConfig.pvs1Knowledge(mapper,env)).getMessage());
        assertEquals(profile(service(new MockEnvironment())).document(),profile(service(env)).document());
        env.setProperty(p+"pvs1-applicability-source-file","");env.setProperty(p+"pvs1-applicability-source-sha256",java.util.Objects.requireNonNull("c".repeat(64)));
        assertEquals("PVS1_APPLICABILITY_SOURCE_BINDING_REQUIRED",assertThrows(IllegalArgumentException.class,
                ()->AcmgBusinessConfig.pvs1Knowledge(mapper,env)).getMessage());
    }
}
