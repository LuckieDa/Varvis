package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Three frozen public controls; real local facts, synthetic contexts, no formal analysis or clinical adoption. */
class Pm2LocalExomesRealResourceTest {
    @Test @EnabledIfSystemProperty(named = "varvis.pm2.local.replay", matches = ".+")
    void retainedRealFactsReplayWithoutAProviderAfterAdapterChanges() throws Exception {
        var mapper = new ObjectMapper(); var profile = Pm2LocalExomesProfile.load(mapper);
        var path = Path.of(System.getProperty("varvis.pm2.local.replay"));
        var original = mapper.readTree(Files.readAllBytes(path));
        var report = mapper.createObjectNode().put("scope", "REPLAY_OF_RETAINED_REAL_LOCAL_FACTS_SYNTHETIC_CONTEXT")
                .put("originalFileSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))))
                .put("providerCalls", 0);
        var results = report.putArray("results");
        for (var row : original.path("results")) {
            var observation = PopulationObservation.restoreSealed(mapper, row.path("observation"), row.path("context"),
                    original.path("sourceControlsSha256").asText(), profile.factProfile());
            var evaluation = new GnomadPopulationCriterionEvaluator(mapper).evaluatePm2LocalExomes(row.path("context"), observation,
                    profile, original.path("sourceControlsSha256").asText());
            assertEquals(row.at("/evaluation/evaluationResult"), evaluation.path("evaluationResult"));
            var result = results.addObject(); result.set("context", row.path("context")); result.set("observation", observation);
            result.set("evaluation", evaluation);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.resolveSibling("real-local-replay.json").toFile(), report);
    }

    @Test @EnabledIfSystemProperty(named = "varvis.pm2.local.real", matches = "true")
    void fixedPublicControlsKeepActualExomesRecordsAndScientificBlockers() throws Exception {
        var mapper = new ObjectMapper(); var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var profile = Pm2LocalExomesProfile.load(mapper);
        var file = Path.of(System.getProperty("varvis.pm2.local.sites")).toAbsolutePath().normalize();
        var tool = Path.of(System.getProperty("varvis.pm2.local.tabix")).toAbsolutePath().normalize();
        var output = Path.of(System.getProperty("varvis.pm2.local.output")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(file)); assertTrue(Files.isRegularFile(Path.of(file + ".tbi")));
        assertTrue(Files.isRegularFile(tool)); assertTrue(Files.isDirectory(output));
        var settings = new VariantAnalysisProperties();
        settings.getGnomad().setGrch38Files(file.toString()); settings.getGnomad().setTabixExecutable(tool.toString());
        settings.getGnomad().setTimeout(java.time.Duration.ofSeconds(20));
        var provider = Ba1LocalExomes.provider(settings);
        var controlsStream = getClass().getResourceAsStream("/acmg/pm2-public-controls-v1.json");
        assertNotNull(controlsStream);
        com.fasterxml.jackson.databind.JsonNode controls;
        try (controlsStream) { controls = mapper.readTree(controlsStream); }
        var report = mapper.createObjectNode().put("scope", "REAL_LOCAL_FACTS_SYNTHETIC_CONTEXT_NO_FORMAL_TOP_N")
                .put("sourceControlsSha256", adapter.canonicalSha256(controls))
                .put("expectedScientificDecision", "NOT_EVALUATED_NO_EXOMES_ADOPTION")
                .put("resourceFile", file.getFileName().toString()).put("resourceBytesBefore", Files.size(file))
                .put("resourceMtimeBefore", Files.getLastModifiedTime(file).toString());
        report.set("profile", profile.document());
        var results = report.putArray("results");
        for (var control : controls.path("controls")) {
            String[] a = control.path("allele").asText().split(":");
            var key = new NormalizedVariantKey(a[0], a[1], Integer.parseInt(a[2]), a[3], a[4]);
            var c = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key);
            c.withObject("/gene").put("hgncId", "HGNC:12687").put("symbol", "VHL");
            c.withObject("/disease").put("mondoId", "MONDO:0008667"); c.put("moi", "AUTOSOMAL_DOMINANT");
            c.put("contextId", adapter.canonicalVersionedContextId(c));
            long started = System.nanoTime();
            var observation = PopulationObservation.acquire(mapper, provider, RuntimeEvidenceV2ProviderWiringTest.variant(key),
                    RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), profile.factProfile(), adapter.canonicalSha256(controls), "SYNTHETIC");
            long queryMs = (System.nanoTime() - started) / 1_000_000;
            var evaluation = new GnomadPopulationCriterionEvaluator(mapper).evaluatePm2LocalExomes(c, observation, profile, adapter.canonicalSha256(controls));
            var row = results.addObject().put("queryMs", queryMs); row.set("control", control); row.set("context", c);
            row.set("observation", observation); row.set("evaluation", evaluation); row.set("policySelection", profile.select(c));
            assertEquals("SUCCEEDED", observation.at("/carrier/resourceExecution/status").asText(), observation.toPrettyString());
            assertTrue(List.of("HIT", "NO_RECORD").contains(observation.at("/carrier/queryOutcome/status").asText()));
            assertEquals("NOT_EVALUATED", evaluation.at("/evaluationResult/status").asText());
        }
        report.set("queryDiagnostics", mapper.valueToTree(provider.queryDiagnostics()));
        report.put("resourceBytesAfter", Files.size(file)).put("resourceMtimeAfter", Files.getLastModifiedTime(file).toString());
        assertEquals(report.path("resourceBytesBefore"), report.path("resourceBytesAfter"));
        assertEquals(report.path("resourceMtimeBefore"), report.path("resourceMtimeAfter"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("real-local-observations.json").toFile(), report);
    }
}
