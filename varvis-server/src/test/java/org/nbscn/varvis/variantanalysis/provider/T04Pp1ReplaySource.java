package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Test capture seam over real generated SYNTHETIC source bytes.
 * Every scoring call delegates to the unchanged business source and actual Python replay.
 * No worker result, clinical award, ledger value or current-use capability is fabricated here.
 */
public final class T04Pp1ReplaySource implements Pp1SelectedBatch.Source {
    private final ObjectMapper json = new ObjectMapper();
    private final Pp1SelectedBatch.Source real;
    private final ObjectNode identity;
    private final ObjectNode document;
    private final AtomicInteger evaluations = new AtomicInteger();
    private final Path workflow;

    private T04Pp1ReplaySource(Path project, ObjectNode binding) throws Exception {
        workflow = project.resolve("local/workflow.json"); real = Pp1LiteratureJobs.businessSource(workflow);
        var summary = json.readTree(Files.readAllBytes(project.resolve("local/fixture.json")));
        assertTrue(summary.path("noNetworkOrModel").asBoolean());
        Path artifact = Path.of(summary.path("artifact").asText()).toRealPath();
        assertTrue(artifact.startsWith(project.toRealPath()));
        byte[] bytes = Files.readAllBytes(artifact); ObjectNode actual = (ObjectNode) json.readTree(bytes);
        assertTrue(actual.at("/binding/target").isObject(), "actual audited extraction artifact must retain its source target");
        var capturedSourceMetadata = json.createObjectNode().put("captureLayer", "SYNTHETIC_RAW_AUDITED_ARTIFACT_ONLY");
        capturedSourceMetadata.set("target", actual.at("/binding/target").deepCopy());
        document = json.createObjectNode().put("taskId", T04NativeOriginalFixture.rawSha(bytes))
                .put("resultSha256", T04NativeOriginalFixture.canonicalSha(capturedSourceMetadata));
        var request = document.putObject("request").put("pmcid", summary.path("pmcid").asText())
                .put("bundleSha256", summary.path("bundleSha256").asText());
        request.set("analysisBinding", binding.deepCopy()); request.set("target", summary.path("target").deepCopy());
        document.set("result", capturedSourceMetadata);
        document.putArray("artifacts").addObject().put("path", project.toRealPath().relativize(artifact).toString().replace('\\', '/'))
                .put("sha256", T04NativeOriginalFixture.rawSha(bytes));
        identity = real.identity().deepCopy();
        identity.put("captureTestSeam", "ACTUAL_SYNTHETIC_RAW_EXTRACTION_AND_REVIEW_ONLY")
                .put("capturedDocumentSha256", T04NativeOriginalFixture.canonicalSha(document));
    }
    public static T04Pp1ReplaySource generate(Path project, Path python, Path sourceScripts, ObjectNode binding, String mode) throws Exception {
        Files.createDirectories(project); Path sourceBinding = project.resolve("initial-binding.json");
        Files.write(sourceBinding, new ObjectMapper().writeValueAsBytes(binding));
        var process = new SystemExternalProcessRunner().run(List.of(python.toRealPath().toString(), "-E", "-s", "-B",
                sourceScripts.resolve("test_pp1_formal_business_fixture.py").toRealPath().toString(), project.toRealPath().toString(),
                sourceBinding.toRealPath().toString(), mode, python.toRealPath().toString()), sourceScripts.toRealPath(), Duration.ofSeconds(60));
        assertTrue(process.isSuccess(), process.getOutput());
        return new T04Pp1ReplaySource(project, binding);
    }
    public int evaluations() { return evaluations.get(); }
    public Path workflow() { return workflow; }
    @Override public ObjectNode identity() { return identity.deepCopy(); }
    @Override public ObjectNode capture(String analysis, ObjectNode recovered) throws IOException {
        var snapshot = json.createObjectNode().put("schemaVersion", "pp1-business-source-snapshot-v1").put("analysisId", analysis);
        snapshot.putArray("documents").add(document.deepCopy());
        snapshot.put("snapshotSha256", new RuntimeEvidenceV2Adapter(json).canonicalSha256(snapshot)); return snapshot;
    }
    @Override public ObjectNode evaluate(ObjectNode captured, ObjectNode binding) throws IOException {
        evaluations.incrementAndGet(); return real.evaluate(captured, binding);
    }
}
