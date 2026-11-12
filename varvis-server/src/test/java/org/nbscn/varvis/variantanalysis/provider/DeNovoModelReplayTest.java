package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real Python/Java replay with SYNTHETIC provider artifacts; zero external calls. */
class DeNovoModelReplayTest {
    @TempDir Path temporary;
    private DeNovoBatchFixture fixture;
    private Path python, scripts, index;

    @BeforeEach void setup() throws Exception {
        python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required");
        fixture = new DeNovoBatchFixture(temporary.resolve("materials"));
        fixture.addSyntheticLiterature(); fixture.manifest.putArray("reports");
        index = fixture.root.resolve("model-artifacts.json");
    }
    private void prepare(String mode) throws Exception {
        Path path = fixture.root.resolve("synthetic-literature.xml");
        String source = Files.readString(path);
        if (mode.equals("pm6")) source = source.replace("SNP analysis confirmed both maternity and paternity.", "No parentage testing was performed.");
        if (mode.equals("conflict")) source = source.replace("</p></sec>", " The variant was inherited from the mother.</p></sec>");
        if (mode.equals("narrative")) source = source.replaceAll("(?s)<table-wrap>.*?</table-wrap>", "")
                .replace("</p></sec>", " The proband had Synthetic trait. There was no family history of similar symptoms.</p></sec>");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8); Files.write(path, bytes);
        for (var entry : fixture.manifest.path("sources")) if (entry.path("path").asText().equals(path.getFileName().toString()))
            ((ObjectNode) entry).put("sha256", DeNovoBatchFixture.sha(bytes));
        fixture.writeManifest();
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                "from test_model_literature import write_synthetic_fixture; import sys; write_synthetic_fixture(sys.argv[1])",
                fixture.root.toString()), scripts, Duration.ofSeconds(20));
        assertTrue(process.isSuccess(), process.getOutput());
    }
    private DeNovoLiteratureReader reader() throws Exception {
        return new DeNovoLiteratureReader(fixture.mapper, python, scripts, temporary.resolve("replay-cache"), index,
                DeNovoBatchFixture.sha(Files.readAllBytes(index)));
    }
    private DeNovoSelectedBatch load(DeNovoLiteratureReader reader) throws Exception {
        return DeNovoSelectedBatch.load(fixture.mapper, fixture.root.resolve("manifest.json"),
                DeNovoBatchFixture.sha(Files.readAllBytes(fixture.root.resolve("manifest.json"))),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, reader);
    }
    @ParameterizedTest @ValueSource(strings={"ps2", "pm6", "narrative"})
    void extractedFactsReachSharedRulesAndReopenWithoutModelCalls(String mode) throws Exception {
        prepare(mode); var first = reader(); var module = load(first);
        var one = module.evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        assertEquals(mode.equals("pm6") ? "PM6" : "PS2", one.at("/qualifiedResult/result/code").asText(), one.toPrettyString());
        assertEquals("MET", one.at("/qualifiedResult/result/status").asText());
        assertEquals("SYNTHETIC_MODEL_REPLAY_ONLY", one.at("/publicSummary/modelValidation").asText());
        assertEquals(1, first.executions());
        var second = reader(); var reopened = load(second);
        assertEquals(0, second.executions());
        assertEquals(module.originals(), reopened.originals());
        assertEquals(one, reopened.evaluateBound(fixture.context(), fixture.mapper.createObjectNode()));
        assertTrue(module.originals().toString().length() > 0);
    }
    @Test void hiddenNegativeOriginalCannotBeReplacedByPositiveModelFields() throws Exception {
        prepare("conflict"); var result = load(reader()).evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED", result.at("/qualifiedResult/result/status").asText());
        assertTrue(result.toString().contains("CONFLICT_TEXT"));
    }
    @Test void indexAndArtifactMutationFailClosedBeforeReplay() throws Exception {
        prepare("ps2"); var bound = reader(); Files.writeString(index, "{}");
        assertEquals("DENOVO_MODEL_INDEX_PIN_MISMATCH", load(bound).identity().path("literatureQualification").asText());
    }
    @Test void modelResponseTamperingCannotUseCachedQualification() throws Exception {
        prepare("ps2"); load(reader()); Files.writeString(fixture.root.resolve("fake-model-artifact.json"), "{}");
        assertEquals("DENOVO_MODEL_ARTIFACT_PIN_MISMATCH", load(reader()).identity().path("literatureQualification").asText());
    }
    @Test void absentArtifactIsExplicitAndOldSourceReaderRemainsUsable() throws Exception {
        prepare("ps2"); Files.writeString(index, "{\"schemaVersion\":\"denovo-model-artifact-index-v1\",\"artifacts\":[]}");
        var result = load(reader()).evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        assertEquals("MET", result.at("/qualifiedResult/result/status").asText());
        assertTrue(result.toString().contains("DENOVO_MODEL_ARTIFACT_NOT_AVAILABLE"));
        assertEquals("DEFERRED_NOT_CONFIGURED", result.at("/publicSummary/modelValidation").asText());
    }
}
