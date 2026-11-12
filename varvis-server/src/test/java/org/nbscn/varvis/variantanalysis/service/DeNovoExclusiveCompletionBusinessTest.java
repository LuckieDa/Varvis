package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.DeNovoCurrentTestDirectory;
import org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual CURRENT18/owned Python/HTTP/H2; explicit synthetic source and reference materials.
 * Completion changes no evidence result, score, source qualification or historical data. */
class DeNovoExclusiveCompletionBusinessTest {
    @TempDir(factory = DeNovoCurrentTestDirectory.class) Path temporary;
    private DeNovoUnifiedEighteenFixture fixture;
    private AcmgBusinessIntegrationTest base;
    @AfterEach void close() { if (fixture != null) fixture.close(); }

    private void prepare(String scenario) throws Exception {
        Path python = Path.of(System.getProperty("denovo.test.python", ""));
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required; skip is not acceptance");
        fixture = new DeNovoUnifiedEighteenFixture(temporary, python,
                Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")));
        fixture.prepare("JSON", scenario); base = fixture.base;
    }
    private ObjectNode submit(List<String> selection) throws Exception {
        var request = base.request.deepCopy();
        if (selection == null) request.remove("selectedCriteria");
        else request.set("selectedCriteria", base.mapper.valueToTree(selection));
        var result = base.postRequest(request);
        int calls = fixture.evaluations(), reads = fixture.readerExecutions();
        assertEquals(result, base.postRequest(request));
        assertEquals(calls, fixture.evaluations()); assertEquals(reads, fixture.readerExecutions());
        return result;
    }
    private JsonNode row(JsonNode response, String code) {
        for (var value : response.at("/candidates/0/attempts/0/localBatch/criteria"))
            if (("ACMG_AMP_2015:" + code).equals(value.path("criterionId").asText())) return value;
        throw new AssertionError(code + " missing");
    }
    private ObjectNode privateData(JsonNode response) throws Exception {
        return (ObjectNode) base.mapper.readTree(base.store.run(base.analysisId, response.path("runId").asText()).orElseThrow().privateJson());
    }
    private boolean complete(JsonNode response, JsonNode data, String opposite) {
        return AcmgBusinessService.completedDeNovoExclusiveAllocation(base.mapper, response,
                response.at("/candidates/0"), response.at("/candidates/0/attempts/0"), row(response, opposite), data);
    }

    @ParameterizedTest @ValueSource(strings = {"TISSUE_BOTH", "COMBINED_ASSUMED", "GENERAL_ASSUMED"})
    void selectedWinnerCompletesItsSharedPairWithoutRewritingEitherEvidence(String scenario) throws Exception {
        prepare(scenario);
        String winner = scenario.equals("GENERAL_ASSUMED") ? "PM6" : "PS2", opposite = winner.equals("PS2") ? "PM6" : "PS2";
        var outputs = new LinkedHashMap<String, ObjectNode>();
        var pair = submit(List.of("ACMG_AMP_2015:PS2", "ACMG_AMP_2015:PM6")); outputs.put("pair", pair);
        assertEquals("COMPLETED", pair.path("status").asText());
        assertEquals("MET", row(pair, winner).path("status").asText());
        assertEquals("NOT_EVALUATED", row(pair, opposite).path("status").asText());
        assertEquals("NONE", row(pair, opposite).path("appliedStrength").asText());
        assertEquals("DENOVO_SHARED_EVENT_ASSIGNED_TO_" + winner, row(pair, opposite).path("reasonCode").asText());
        var data = privateData(pair); assertTrue(complete(pair, data, opposite));
        assertEquals("SOURCE_BOUND_SHARED_EVENT_COMPLETION_V1", base.mapper.readTree(base.store.run(base.analysisId,
                pair.path("runId").asText()).orElseThrow().requestJson()).path("deNovoExclusiveCompletionCapability").asText());
        assertRejectedMutations(pair, data, opposite, winner);
        var chosen = submit(List.of("ACMG_AMP_2015:" + winner)); outputs.put("winner-only", chosen);
        assertEquals("COMPLETED", chosen.path("status").asText());
        var unchosen = submit(List.of("ACMG_AMP_2015:" + opposite)); outputs.put("opposite-only", unchosen);
        assertEquals("COMPLETED_WITH_BLOCKERS", unchosen.path("status").asText());
        assertFalse(complete(unchosen, privateData(unchosen), opposite));
        var all = submit(null); outputs.put("all", all);
        assertEquals(18, all.path("enabledCriteria").size());
        assertTrue(complete(all, privateData(all), opposite), "The pair is complete even when independent criteria have genuine gaps");
        assertEquals(row(pair, winner).path("status"), row(all, winner).path("status"));
        assertEquals(row(pair, opposite).path("reasonCode"), row(all, opposite).path("reasonCode"));
        assertEquals(1, fixture.readerExecutions());
        roundTrip(scenario, outputs);
    }

    private void assertRejectedMutations(ObjectNode response, ObjectNode originalData, String opposite, String winner) {
        for (String fault : List.of("source", "module-source", "context", "pointer", "identity", "winner", "proof", "private-proof",
                "execution", "qualification", "missing-winner", "source-failure")) {
            var changed = response.deepCopy(); var data = originalData.deepCopy();
            var raw = (ObjectNode) data.at("/localBatch/modules/deNovo/results/0");
            switch (fault) {
                case "source" -> data.withObject("/localBatch").put("sourceSnapshotSha256", "f".repeat(64));
                case "module-source" -> data.withObject("/localBatch/modules/deNovo").put("sourceSnapshotSha256", "f".repeat(64));
                case "context" -> changed.withObject("/candidates/0/attempts/0/context").put("contextId", "another-context");
                case "pointer" -> ((ObjectNode) row(changed, opposite).path("details")).put("modulePointer", "/modules/deNovo/results/1");
                case "identity" -> changed.withObject("/executionProfile/moduleBindings/deNovo").put("manifestSha256", "f".repeat(64));
                case "winner" -> ((ObjectNode) row(changed, winner)).put("status", "NOT_EVALUATED");
                case "proof" -> ((ObjectNode) row(changed, opposite).at("/details/deNovoFacts")).remove("adoptionProof");
                case "private-proof" -> raw.withObject("/qualifiedResult/adoptionProof").put("projectionStatus", "INCOMPLETE");
                case "execution" -> ((ObjectNode) raw.withArray("calculationBindings").get(0)).put("executedCalculatorVersion", "wrong");
                case "qualification" -> raw.withObject("/qualifiedResult/result").putArray("events");
                case "missing-winner" -> {
                    var values = (ArrayNode) changed.at("/candidates/0/attempts/0/localBatch/criteria");
                    for (int i = values.size() - 1; i >= 0; i--) if (("ACMG_AMP_2015:" + winner).equals(values.get(i).path("criterionId").asText())) values.remove(i);
                }
                case "source-failure" -> changed.putObject("executionSourceFailure").put("reasonCode", "EXECUTION_SOURCE_UNAVAILABLE");
                default -> throw new AssertionError(fault);
            }
            assertFalse(complete(changed, data, opposite), fault);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"TISSUE_QUALITY", "TISSUE_WRONG_TARGET"})
    void failedQualificationNeverBecomesCompletedExclusiveAllocation(String scenario) throws Exception {
        prepare(scenario);
        var pair = submit(List.of("ACMG_AMP_2015:PS2", "ACMG_AMP_2015:PM6"));
        assertEquals("COMPLETED_WITH_BLOCKERS", pair.path("status").asText());
        for (String code : List.of("PS2", "PM6")) {
            assertEquals("NOT_EVALUATED", row(pair, code).path("status").asText());
            assertEquals("NONE", row(pair, code).path("appliedStrength").asText());
            assertFalse(complete(pair, privateData(pair), code));
        }
        roundTrip(scenario, Map.of("pair", pair));
    }

    private void roundTrip(String scenario, Map<String, ObjectNode> outputs) throws Exception {
        var source = base.store.source(base.analysisId).orElseThrow();
        Path directory = temporary.resolve("exclusive-readback"); Files.createDirectories(directory);
        var manifest = base.mapper.createObjectNode().put("expectedSourceSha256", source.sha256()); var runs = manifest.putArray("runs");
        String configured = System.getProperty("central.denovo.output", "");
        Path published = configured.isBlank() ? null : Path.of(configured).resolve(scenario);
        if (published != null) Files.createDirectories(published);
        for (var entry : outputs.entrySet()) {
            var response = entry.getValue(); var saved = base.store.run(base.analysisId, response.path("runId").asText()).orElseThrow();
            assertEquals(response, base.service.read(base.analysisId, saved.id()));
            var exported = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.id()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertEquals(response, base.mapper.readTree(exported));
            runs.addObject().put("name", entry.getKey()).put("runId", saved.id());
            Path target = directory.resolve(entry.getKey()); Files.createDirectories(target);
            Files.writeString(target.resolve("expected-public.json"), saved.responseJson());
            Files.writeString(target.resolve("expected-private.json"), saved.privateJson());
            Files.writeString(target.resolve("expected-request.json"), saved.requestJson());
            Files.writeString(target.resolve("expected-source.json"), source.json());
            if (published != null) {
                Files.write(published.resolve(entry.getKey() + "-public.json"), exported);
                Files.writeString(published.resolve(entry.getKey() + "-private.json"), saved.privateJson());
            }
        }
        Files.write(directory.resolve("manifest.json"), base.mapper.writeValueAsBytes(manifest));
        int calls = fixture.evaluations(), reads = fixture.readerExecutions();
        base.closeDatabase();
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        var child = new SystemExternalProcessRunner().run(List.of(java, "-cp", System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")), DeNovoUnifiedEighteenReopenReader.class.getName(), temporary.toString(),
                base.analysisId, directory.toString()), temporary, Duration.ofSeconds(45));
        assertTrue(child.isSuccess(), child.getOutput());
        assertEquals(calls, fixture.evaluations()); assertEquals(reads, fixture.readerExecutions());
        if (published != null) Files.copy(directory.resolve("reopen-receipt.json"), published.resolve("reopen-receipt.json"));
    }
}
