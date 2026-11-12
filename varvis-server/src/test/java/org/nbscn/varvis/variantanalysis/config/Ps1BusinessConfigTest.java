package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class Ps1BusinessConfigTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final String prefix = "variant-analysis.acmg-business.";

    @Test void defaultIsUnchangedAndExplicitScopeRequiresEnabledPinnedNewBundle() throws Exception {
        var env = new MockEnvironment(); var config = new AcmgBusinessConfig();
        var original = Objects.requireNonNull(config.acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env));
        var profile = (AcmgExecutionProfile) ReflectionTestUtils.getField(original, "executionProfile");
        assertNotNull(profile); assertEquals(AcmgBusinessRuleScope.CURRENT, profile.criteria());
        env.withProperty(prefix + "acceptance-scope", AcmgBusinessRuleScope.PS1_DEVELOPMENT);
        assertEquals("PS1_BUSINESS_EXECUTION_DISABLED", assertThrows(IllegalArgumentException.class,
                () -> config.acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env)).getMessage());
        env.withProperty(prefix + "enabled", "true");
        assertEquals("PS1_TRUSTED_BUNDLE_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> config.acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env)).getMessage());
        var fixture = new Ps1TestCatalog(mapper); var document = fixture.document();
        Path path = temporary.resolve("test-only-ps1-bundle.json"); mapper.writeValue(path.toFile(), document);
        String bundlePath = Objects.requireNonNull(path.toString());
        String bundleSha = Objects.requireNonNull(fixture.canonicalSha256(document));
        String wrongSha = Objects.requireNonNull("f".repeat(64));
        env.withProperty(prefix + "reviewed-rule-policy-enabled", "true")
                .withProperty(prefix + "reviewed-rule-bundle-file", bundlePath)
                .withProperty(prefix + "reviewed-rule-bundle-sha256", bundleSha);
        var service = Objects.requireNonNull(config.acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env));
        profile = (AcmgExecutionProfile) ReflectionTestUtils.getField(service, "executionProfile");
        assertNotNull(profile); assertEquals(List.of(MissenseComparisonRuntime.PS1), profile.criteria());
        String originalManifest = profile.document().path("manifestSha256").asText();
        document.put("bundleVersion", "2"); mapper.writeValue(path.toFile(), document);
        env.withProperty(prefix + "reviewed-rule-bundle-sha256", Objects.requireNonNull(fixture.canonicalSha256(document)));
        var changed = config.acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env);
        var newProfile = (AcmgExecutionProfile) ReflectionTestUtils.getField(Objects.requireNonNull(changed), "executionProfile");
        assertNotNull(newProfile); assertNotEquals(originalManifest, newProfile.document().path("manifestSha256").asText());
        document.put("schemaVersion", "reviewed-rule-bundle-ps1-unknown"); mapper.writeValue(path.toFile(), document);
        env.withProperty(prefix + "reviewed-rule-bundle-sha256", Objects.requireNonNull(fixture.canonicalSha256(document)));
        assertThrows(IllegalArgumentException.class, () -> config.acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env));
        env.withProperty(prefix + "reviewed-rule-bundle-sha256", wrongSha);
        assertThrows(IllegalArgumentException.class, () -> config.acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env));
        env.withProperty(prefix + "reviewed-rule-bundle-sha256", bundleSha)
                .withProperty(prefix + "revel-application-enabled", "true");
        assertEquals("PS1_EXECUTION_MODE_CONFLICT", assertThrows(IllegalArgumentException.class,
                () -> config.acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env)).getMessage());
    }
}
