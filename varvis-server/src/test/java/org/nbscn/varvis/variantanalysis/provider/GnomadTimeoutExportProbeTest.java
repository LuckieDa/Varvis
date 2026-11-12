package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real population query/evaluation using the exact exported allele order and confirmed contexts.
 * This does not import a public export as a trusted production source or claim HTTP/page acceptance.
 */
@EnabledIfSystemProperty(named = "varvis.gnomad.timeout.real", matches = "true")
class GnomadTimeoutExportProbeTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test void queryCurrentTop20AndRecheckOriginalFailedGroups() throws Exception {
        Path input = Path.of(System.getProperty("varvis.gnomad.timeout.export"));
        assertTrue(Files.size(input) < 16 * 1024 * 1024);
        String hash = sha(input); assertEquals(System.getProperty("varvis.gnomad.timeout.exportSha256"), hash);
        var old = mapper.readTree(input.toFile());
        JsonNode retained = null;
        String replayPath = System.getProperty("varvis.gnomad.timeout.populationReplay", "");
        if (!replayPath.isBlank()) {
            Path replay = Path.of(replayPath); assertTrue(Files.size(replay) < 16 * 1024 * 1024);
            retained = mapper.readTree(replay.toFile()); assertEquals(hash, retained.path("sourceExportSha256").asText());
        }
        var updated = mapper.createObjectNode().put("sourceRunId", old.path("runId").asText()).put("sourceExportSha256", hash)
                .put("scope", "DERIVED_POPULATION_PROBE_NOT_A_SAVED_BUSINESS_RUN");
        updated.set("candidates", old.path("candidates").deepCopy());
        Path work = Path.of(System.getProperty("varvis.gnomad.timeout.output")).toAbsolutePath().resolve("probe-" + UUID.randomUUID());
        Files.createDirectories(work);
        var report = mapper.createObjectNode().put("status", "BLOCKED").put("exportSha256", hash)
                .put("mode", "REAL_POPULATION_QUERY_RETAINED_CONTEXT_NO_HTTP");
        var runtime = new Pm2SupportingRuntime(mapper, settings());
        var ba1 = new Ba1AlleleRuntime(mapper, settings());
        try {
            var contexts = new LinkedHashMap<NormalizedVariantKey, JsonNode>();
            var candidates = new ArrayList<VariantInterpretation>();
            for (var row : old.path("candidates")) {
                var allele = row.at("/ba1/lineage/originalInputAllele");
                var key = new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(), allele.path("position").asInt(),
                        allele.path("reference").asText(), allele.path("alternate").asText());
                assertEquals("GRCh38", key.getGenomeAssembly());
                var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
                var ctx = row.at("/attempts/0/context");
                if (ctx.isObject()) {
                    new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(ctx);
                    assertEquals(allele, ctx.at("/variant/evidenceQueryAllele")); contexts.putIfAbsent(key, ctx);
                }
                candidates.add(candidate);
            }
            assertEquals(20, candidates.size());
            var analysis = RuntimeEvidenceV2ProviderWiringTest.analysisContext(candidates.get(0).getObservation().getKey());
            int retainedPm2 = 0;
            if (retained != null) for (int i = 0; i < candidates.size(); i++) {
                var entry = retained.path("candidates").get(i);
                assertEquals(old.path("candidates").get(i).path("candidateId"), entry.path("candidateId"));
                var pm2 = entry.at("/attempts/0/pm2"); var evidence = pm2.path("evidence");
                if (!"SUCCEEDED".equals(evidence.at("/resourceExecution/status").asText()) || !"HIT".equals(evidence.at("/queryOutcome/status").asText())) continue;
                assertEquals(GnomadPopulationInputProfile.loadBusinessPm2Supporting().getProfileSha256(), pm2.path("profileSha256").asText());
                assertEquals(old.at("/candidates/" + i + "/attempts/0/context"), pm2.path("context"));
                var key = candidates.get(i).getObservation().getKey();
                retainedMap(runtime, "jointEvidence").put(key, evidence.deepCopy());
                retainedMap(runtime, "preparedAlleles").put(key, pm2.at("/context/variant/evidenceQueryAllele"));
                var raw = pm2.path("rawPopulationRecord");
                if (raw.isTextual()) retainedRaw(runtime).put(key, raw.asText());
                retainedPm2++;
            }
            report.put("currentTaskSuccessfulPm2CarriersReused", retainedPm2);
            runtime.prepare(contexts, candidates, analysis);
            int ba1Queries = 0, reused = 0;
            for (int i = 0; i < candidates.size(); i++) {
                var row = (ObjectNode) updated.path("candidates").get(i);
                var context = old.at("/candidates/" + i + "/attempts/0/context");
                var candidate = candidates.get(i);
                if (context.isObject()) {
                    var evidence = runtime.evaluate((ObjectNode) context, candidate, analysis);
                    ((ObjectNode) row.path("attempts").get(0)).set("pm2", evidence);
                }
                var saved = row.path("ba1");
                if (retained != null) {
                    var recent = retained.path("candidates").get(i).path("ba1");
                    if ("SUCCEEDED".equals(recent.at("/evidence/resourceExecution/status").asText())) {
                        assertEquals(saved.path("alleleId"), recent.path("alleleId"));
                        assertEquals(saved.path("normalization"), recent.path("normalization"));
                        saved = recent; row.set("ba1", recent.deepCopy());
                    }
                }
                if ("SUCCEEDED".equals(saved.at("/evidence/resourceExecution/status").asText())) {
                    var profile = GnomadPopulationInputProfile.loadExecutableV2();
                    var bind = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper)).bind(profile.getDocument(), profile.getPopulationResourceId());
                    assertEquals(saved.at("/lineage/originalInputAllele"), saved.at("/evidence/queryAllele"));
                    assertEquals(profile.getDocument().path("resources").get(0).path("identityFingerprintSha256").asText(), saved.at("/evidence/source/resourceSha256").asText());
                    assertNotNull(bind); reused++;
                } else {
                    var fresh = ba1.evaluate(saved.path("alleleId").asText(), candidate, analysis);
                    var replacement = ((ObjectNode) saved).deepCopy();
                    for (var entry : fresh.properties()) replacement.set(entry.getKey(), entry.getValue());
                    row.set("ba1", replacement); ba1Queries++;
                }
                mapper.writerWithDefaultPrettyPrinter().writeValue(work.resolve("population-results.json").toFile(), updated);
            }
            report.put("historicalBa1ResultsReused", reused).put("ba1Provider", ba1Queries).put("ba1Evaluator", ba1Queries)
                    .put("pm2Provider", runtime.providerCalls()).put("pm2Evaluator", runtime.evaluatorCalls());
            var states = report.putArray("rows");
            for (int i = 0; i < 20; i++) {
                var row = updated.path("candidates").get(i); var before = old.path("candidates").get(i);
                var change = states.addObject().put("rank", i + 1).put("candidateId", row.path("candidateId").asText());
                change.set("before", result(before.at("/attempts/0/pm2"))); change.set("after", result(row.at("/attempts/0/pm2")));
                assertEquals(before.path("candidateId"), row.path("candidateId"));
                assertEquals("SUCCEEDED", row.at("/ba1/evidence/resourceExecution/status").asText());
                if (before.at("/attempts/0/context").isObject()) assertEquals("SUCCEEDED", row.at("/attempts/0/pm2/evidence/resourceExecution/status").asText());
                else assertEquals(before.at("/attempts/0/pm2"), row.at("/attempts/0/pm2"));
                var previous = result(before.at("/attempts/0/pm2"));
                if (Set.of("MET", "NOT_MET").contains(previous.path("status").asText())
                        || previous.path("reasonCode").asText().contains("FREQUENCY_PARAMETERS"))
                    assertEquals(previous, result(row.at("/attempts/0/pm2")));
            }
            assertEquals("NOT_MET", result(updated.at("/candidates/16/attempts/0/pm2")).path("status").asText());
            var rechecks = report.putArray("recheckDiagnostics");
            for (var indexes : List.of(List.of(1), List.of(9, 16))) {
                var check = new Pm2SupportingRuntime(mapper, settings()); var selected = new ArrayList<VariantInterpretation>();
                for (int i : indexes) selected.add(candidates.get(i));
                check.prepare(contexts, selected, analysis);
                for (int i : indexes) assertEquals("SUCCEEDED", check.evaluate((ObjectNode) old.at("/candidates/" + i + "/attempts/0/context"), candidates.get(i), analysis)
                        .at("/evidence/resourceExecution/status").asText());
                rechecks.add(mapper.valueToTree(check.queryDiagnostics()));
            }
            assertEquals(hash, sha(input)); report.put("status", "REAL_POPULATION_QUERY_AND_RECHECK_VERIFIED");
        } finally {
            report.set("pm2QueryDiagnostics", mapper.valueToTree(runtime.queryDiagnostics()));
            report.set("ba1QueryDiagnostics", mapper.valueToTree(ba1.queryDiagnostics()));
            mapper.writerWithDefaultPrettyPrinter().writeValue(work.resolve("report.json").toFile(), report);
        }
    }
    private VariantAnalysisProperties settings() {
        var value = new VariantAnalysisProperties(); var g = value.getGnomad(); g.setEnabled(true); g.setPopulationInputEnabled(true);
        g.setVersion("gnomAD joint v4.1 GRCh38"); g.setTabixExecutable(System.getProperty("varvis.gnomad.timeout.tabix"));
        ReflectionTestUtils.invokeMethod(AcmgBusinessConfig.class, "configurePopulationQuery", value, new org.springframework.mock.env.MockEnvironment());
        return value;
    }
    private JsonNode result(JsonNode pm2) { return pm2.has("evaluation") ? pm2.at("/evaluation/evaluationResult") : pm2.path("evaluationResult"); }
    @SuppressWarnings("unchecked") // Test-only replay of identity-checked actual successful carriers, never failures.
    private Map<NormalizedVariantKey, JsonNode> retainedMap(Pm2SupportingRuntime value, String name) {
        return (Map<NormalizedVariantKey, JsonNode>) Objects.requireNonNull(ReflectionTestUtils.getField(Objects.requireNonNull(value), Objects.requireNonNull(name)));
    }
    @SuppressWarnings("unchecked")
    private Map<NormalizedVariantKey, String> retainedRaw(Pm2SupportingRuntime value) {
        return (Map<NormalizedVariantKey, String>) Objects.requireNonNull(ReflectionTestUtils.getField(Objects.requireNonNull(value), "rawRecords"));
    }
    private String sha(Path path) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
