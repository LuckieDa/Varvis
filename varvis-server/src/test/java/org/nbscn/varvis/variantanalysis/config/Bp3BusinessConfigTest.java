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
class Bp3BusinessConfigTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
    private MockEnvironment scope() { return new MockEnvironment().withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.BP3_DEVELOPMENT); }
    private AcmgBusinessService service(MockEnvironment env) { return new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env); }
    private AcmgExecutionProfile profile(AcmgBusinessService service) {
        Object value = ReflectionTestUtils.getField(Objects.requireNonNull(service), "executionProfile");
        if (!(value instanceof AcmgExecutionProfile profile)) throw new AssertionError("Missing profile");return profile;
    }
    @Test void defaultCurrentAndDisabledModeRemainClosed() {
        var env = new MockEnvironment().withProperty("variant-analysis.acmg-business.bp3-bundle-file", "bad" + (char) 0);
        assertEquals(profile(service(new MockEnvironment())).document(), profile(service(env)).document());
        assertEquals(AcmgBusinessRuleScope.CURRENT, profile(service(env)).criteria());
        assertEquals("BP3_BUSINESS_EXECUTION_DISABLED", assertThrows(IllegalArgumentException.class, () -> service(scope())).getMessage());
        assertEquals("BP3_TRUSTED_BUNDLE_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> service(scope().withProperty("variant-analysis.acmg-business.enabled", "true"))).getMessage());
        for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
            assertEquals("BP3_EXECUTION_MODE_CONFLICT", assertThrows(IllegalArgumentException.class, () -> service(scope()
                    .withProperty("variant-analysis.acmg-business.enabled", "true").withProperty("variant-analysis.acmg-business." + flag, "true"))).getMessage());
    }
    @Test void fixedRawAndCanonicalPinsAndSingleRuleIdentityAreRequired() throws Exception {
        var fixture = new Bp3BusinessFixture(mapper, false);var d = fixture.document();
        Path file = temporary.resolve("TEST_ONLY.json");byte[] bytes = mapper.writeValueAsBytes(d);Files.write(file, bytes);
        String raw = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var env = scope().withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.bp3-bundle-file", Objects.requireNonNull(file.toString()))
                .withProperty("variant-analysis.acmg-business.bp3-bundle-file-sha256", Objects.requireNonNull(raw))
                .withProperty("variant-analysis.acmg-business.bp3-bundle-sha256", Objects.requireNonNull(fixture.canonical(d)));
        var p = profile(service(env));assertEquals(List.of(Bp3CriterionEvaluator.CRITERION), p.criteria());
        assertEquals(AcmgBusinessRuleScope.BP3_DECLARATION, p.document().path("requiredDeclaration").asText());
        for (String flag : List.of("formalEnabled", "automaticTrigger", "classificationEnabled", "adoptionSupported")) assertFalse(p.document().path(flag).asBoolean(true));
        assertEquals(raw, p.document().at("/reviewedRuleBinding/bundleRawSha256").asText());
        Files.writeString(file, "\n", StandardOpenOption.APPEND);
        assertEquals("BP3_TRUSTED_BUNDLE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> service(env)).getMessage());
        Files.write(file, bytes);env.withProperty("variant-analysis.acmg-business.bp3-bundle-sha256", Objects.requireNonNull("0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> service(env));
    }
    @Test void oldV1IdentityIsPreservedAndCannotEnableTheSingleRuleMode() throws Exception {
        var f = new Bp3BusinessFixture(mapper, false);var d = f.document();
        var single = new ReviewedRuleBundle(mapper, d, f.canonical(d));
        assertThrows(IllegalArgumentException.class, () -> single.evaluate(FunctionalEvidenceRuntime.PS3, mapper.createObjectNode(), null));
        d.put("schemaVersion", "reviewed-rule-bundle-v1");var legacy = new ReviewedRuleBundle(mapper, d, f.canonical(d));
        assertEquals(2, legacy.identity().path("criterionIds").size());assertFalse(legacy.supportsBp3());
        assertEquals("reviewed-rule-bundle-binding-v1", legacy.identity().path("schemaVersion").asText());
        assertThrows(IllegalArgumentException.class, () -> AcmgExecutionProfile.bp3Development(mapper, legacy));
        d.put("schemaVersion", "reviewed-rule-bundle-bp3-v7").withObject("review").put("status", "DRAFT");
        assertThrows(IllegalArgumentException.class, () -> new ReviewedRuleBundle(mapper, d, f.canonical(d)));
    }
    @Test void strictLoaderRejectsDuplicateKeysAndTrailingDocumentsWithMatchingRawHash() throws Exception {
        var fixture = new Bp3BusinessFixture(mapper, false); var document = fixture.document();
        String json = mapper.writeValueAsString(document); int index = 0;
        for (String invalid : List.of(json + " {}", json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\","))) {
            Path file = temporary.resolve("invalid-json-" + index++ + ".json");
            byte[] bytes = invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(file, bytes);
            String raw = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThrows(Exception.class, () -> ReviewedRuleBundle.loadBp3(mapper, file, raw, fixture.canonical(document)));
        }
    }
}
