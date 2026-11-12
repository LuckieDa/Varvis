package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class Pm2SupportingRuntimeTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);


    private com.fasterxml.jackson.databind.node.ObjectNode supportedContext(NormalizedVariantKey key, String transcript) throws Exception {
        return supportedContext(key, transcript, 0);
    }
    private com.fasterxml.jackson.databind.node.ObjectNode supportedContext(NormalizedVariantKey key, String transcript, int policyIndex) throws Exception {
        var context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key, transcript);
        var policy = GnomadPopulationInputProfile.loadBusinessPm2Supporting().getDocument()
                .at("/evaluation/criteria/2/populationRule/policySnapshot/records/" + policyIndex);
        ((com.fasterxml.jackson.databind.node.ObjectNode) context.path("gene")).put("hgncId", policy.at("/gene/hgncId").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) context.path("disease")).put("mondoId", policy.at("/disease/mondoId").asText());
        context.put("moi", policy.path("moi").asText());
        context.put("contextId", adapter.canonicalContextId(context));
        return context;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false,0", "true,false,1", "true,true,0"})
    void absenceBatchRequiresSuccessfulNoRecordAndAtLeastOneApplicablePolicy(boolean allowedSibling, boolean queryFails, int batches) throws Exception {
        var key = RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 12591, "C", "A");
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        var profile = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var script = temporary.resolve("synthetic-tabix.cmd");
        java.nio.file.Files.writeString(script, "@echo off\r\nexit /b " + (queryFails ? "7" : "0") + "\r\n");
        var settings = new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        settings.getGnomad().setEnabled(true); settings.getGnomad().setPopulationInputEnabled(true);
        settings.getGnomad().setVersion(profile.getDocument().path("resources").get(0).path("resourceVersion").asText());
        settings.getGnomad().setTabixExecutable(script.toString());
        settings.getGnomad().setAllowFullVcfFallback(false); settings.getGnomad().setAllowVcfInfoFallback(false);
        var runtime = new Pm2SupportingRuntime(mapper, profile, new GnomadTabixProvider(settings, profile),
                org.mockito.Mockito.mock(GnomadAllSitesHailBatchRunner.class));
        var prohibited = supportedContext(key, null, 0);
        var allowed = supportedContext(key, null, 9);
        assertFalse(profile.getDocument().at("/evaluation/criteria/2/populationRule/policySnapshot/records/0/allowWellCoveredAbsence").asBoolean());
        assertTrue(profile.getDocument().at("/evaluation/criteria/2/populationRule/policySnapshot/records/9/allowWellCoveredAbsence").asBoolean());
        var analysis = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);
        runtime.prepareContexts(Map.of(key, allowedSibling ? List.of(prohibited, allowed) : List.of(prohibited)), List.of(candidate), analysis);
        assertEquals(batches, runtime.allSitesBatches());
        var result = runtime.evaluate(prohibited, candidate, analysis);
        assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals(queryFails ? "FAILED" : "SUCCEEDED", result.at("/evidence/resourceExecution/status").asText());
        if (allowedSibling) {
            var sibling = runtime.evaluate(allowed, candidate, analysis);
            assertEquals("NOT_EVALUATED", sibling.at("/evaluation/evaluationResult/status").asText());
            assertNotEquals(result.at("/policySelection/selectedPolicyId"), sibling.at("/policySelection/selectedPolicyId"));
            assertEquals(allowed.path("contextId"), sibling.at("/evidence/contextId"));
        }
    }

    @Test void unsupportedFirstContextDoesNotHideSupportedSiblingAndDoesNotQueryAlone() throws Exception {
        var key = RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 12591, "C", "A");
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        var unsupported = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key);
        var supported = supportedContext(key, null);
        var analysis = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);
        var runtime = unavailableProvider();
        var blocked = runtime.evaluate(unsupported, candidate, analysis);
        assertEquals("NOT_EVALUATED", blocked.at("/evaluationResult/status").asText());
        assertEquals(0, runtime.providerCalls());
        var wrongAllele = supportedContext(RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 12592, "C", "A"), null);
        runtime.prepareContexts(Map.of(key, List.of(unsupported, wrongAllele, supported)), List.of(candidate), analysis);
        var evaluated = runtime.evaluate(supported, candidate, analysis);
        assertEquals(1, runtime.providerCalls());
        assertEquals(1, runtime.evaluatorCalls());
        assertEquals(supported.path("contextId"), evaluated.at("/evidence/contextId"));
        assertEquals(unsupported.path("contextId"), runtime.evaluate(unsupported, candidate, analysis).at("/context/contextId"));
        assertEquals(1, runtime.evaluatorCalls());
    }

    private Pm2SupportingRuntime unavailableProvider() {
        var profile = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        return new Pm2SupportingRuntime(mapper, profile, new VariantEvidenceProvider() {
            public String source() { return "GNOMAD"; }
            public String version() { return profile.getDocument().path("resources").get(0).path("resourceVersion").asText(); }
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext context) {
                throw new IllegalStateException("synthetic provider unavailable");
            }
        });
    }

    @Test void oneAlleleQueryCanServeDistinctContextsWithoutReusingEvaluationIdentity() throws Exception {
        var key = RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 12591, "C", "A");
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        var first = supportedContext(key, "ENST000001");
        var second = supportedContext(key, "ENST000002");
        var analysis = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);
        var runtime = unavailableProvider();
        runtime.prepare(Map.of(key, first), List.of(candidate, candidate), analysis);
        var a = runtime.evaluate(first, candidate, analysis);
        var b = runtime.evaluate(second, candidate, analysis);
        assertEquals(1, runtime.providerCalls());
        assertEquals("FAILED", a.at("/evidence/resourceExecution/status").asText());
        assertNotEquals(a.at("/evidence/evidenceId"), b.at("/evidence/evidenceId"));
        assertEquals(a.at("/evidence/provenance/sourcePayloadSha256"), b.at("/evidence/provenance/sourcePayloadSha256"));
        assertEquals(second.path("contextId"), b.at("/evidence/contextId"));
        assertEquals("NOT_EVALUATED", b.at("/evaluation/evaluationResult/status").asText());
    }

    @Test void invalidAlleleContextDoesNotPreventAnotherCandidateQuery() throws Exception {
        var key = RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 12591, "C", "A");
        var badKey = RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 12592, "C", "A");
        var context = supportedContext(key, null);
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        var invalid = RuntimeEvidenceV2ProviderWiringTest.variant(badKey);
        var analysis = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);
        var runtime = unavailableProvider();
        runtime.prepare(Map.of(key, context, badKey, context), List.of(invalid, candidate), analysis);
        assertEquals("FAILED", runtime.evaluate(context, candidate, analysis).at("/evidence/resourceExecution/status").asText());
        assertEquals("PM2_POPULATION_INPUT_INVALID", runtime.evaluate(context, invalid, analysis).at("/evaluationResult/reasonCode").asText());
        assertEquals(1, runtime.providerCalls());
    }

    @Test void requestScopedObservationCannotCrossDataClassOrNormalizationIdentity() throws Exception {
        var key = RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "1", 12591, "C", "A");
        var context = supportedContext(key, null);
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        var analysis = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);
        var runtime = unavailableProvider(); runtime.prepare(Map.of(key, context), List.of(candidate), analysis);
        var otherClass = context.deepCopy(); otherClass.put("dataClass", "PATIENT");
        assertThrows(IllegalArgumentException.class, () -> runtime.evaluate(otherClass, candidate, analysis));
        var otherNormalization = context.deepCopy();
        String originalHash = context.at("/variant/normalization/referenceSequenceSha256").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode) otherNormalization.at("/variant/normalization"))
                .put("referenceSequenceSha256", originalHash.startsWith("0") ? "1".repeat(64) : "0".repeat(64));
        assertNotEquals(context.path("variant"), otherNormalization.path("variant"));
        assertThrows(IllegalArgumentException.class, () -> runtime.evaluate(otherNormalization, candidate, analysis));
        assertEquals(1, runtime.providerCalls());
    }
}
