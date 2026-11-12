package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** TEST_ONLY transport receipt; production capture and Python replay still verify raw originals. */
public final class Pp1FormalTaskFixture {
    private Pp1FormalTaskFixture() { }

    public static String register(Path config, String analysis, ObjectNode binding, ObjectNode fixture) throws Exception {
        var json = new ObjectMapper();
        var problem = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        ExternalProcessRunner transport = (command, cwd, remaining) -> {
            try {
            byte[] input = Files.readAllBytes(Path.of(command.get(command.indexOf("--request") + 1)));
            var request = json.readTree(input);
            Path out = Path.of(command.get(command.indexOf("--output") + 1));
            Files.createDirectories(out);
            var manifest = json.createObjectNode();
            manifest.putArray("acceptedArtifacts").add(fixture.path("artifact").asText());
            byte[] raw = json.writeValueAsBytes(manifest);
            Files.write(out.resolve("artifacts.json"), raw);
            var result = json.createObjectNode().put("schemaVersion", "pp1-paper-process-v1").put("status", "EVALUATED")
                    .put("businessPublishable", false).put("scientificExecution", true)
                    .put("requestSha256", FamilyDeNovoJson.sha256(input))
                    .put("workflowConfigSha256", FamilyDeNovoJson.sha256(Files.readAllBytes(config)))
                    .put("analysisBindingSha256", new RuntimeEvidenceV2Adapter(json).canonicalSha256(request.path("analysisBinding")))
                    .put("artifactManifestSha256", FamilyDeNovoJson.sha256(raw));
            for (String key : List.of("pmcid", "bundleSha256", "selectionSha256", "target")) result.set(key, request.path(key));
            var evaluation = result.putObject("evaluation").put("status", "MET").put("awardedPP1Points", 99);
            evaluation.set("target", request.at("/analysisBinding/selection"));
            evaluation.set("ledger", request.at("/analysisBinding/ledger"));
            Files.write(out.resolve("result.json"), json.writeValueAsBytes(result));
            return new ProcessResult(0, "SYNTHETIC original-source transport; no model/network", false);
            } catch (java.io.IOException | RuntimeException failure) { problem.set(failure); throw failure; }
        };
        try (var jobs = new Pp1LiteratureJobs(config, new DeNovoSourceTasks(1, 2, 10000, true), transport)) {
            String id = jobs.startPrepared(analysis, binding, (ObjectNode) fixture.path("target"),
                    fixture.path("pmcid").asText(), fixture.path("bundleSha256").asText()).path("taskId").asText();
            for (int i = 0; i < 300; i++) {
                var state = jobs.read(analysis, id);
                if (!List.of("QUEUED", "RUNNING").contains(state.path("status").asText())) {
                    var settings = json.readTree(Files.readAllBytes(config));
                    Path failureFile = Path.of(settings.path("projectRoot").asText()).resolve(settings.path("work").asText())
                            .resolve("pp1-paper-tasks").resolve(FamilyDeNovoJson.sha256(analysis.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            .resolve(id).resolve("failure.txt");
                    assertEquals("READY", state.path("status").asText(), state.toPrettyString() + "\n" + problem.get()
                            + "\n" + (Files.isRegularFile(failureFile) ? Files.readString(failureFile) : "no failure file"));
                    return id;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Synthetic source task did not finish");
        }
    }
}
