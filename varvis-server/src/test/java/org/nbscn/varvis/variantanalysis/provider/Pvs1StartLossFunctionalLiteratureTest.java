package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossFunctionalLiteratureTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();

    private Path configuration() throws Exception {
        Path library = root.resolve("local/knowledge/library"); Files.createDirectories(library.resolve("evidence/PVS1"));
        Files.writeString(library.resolve("evidence/PVS1/index.json"), "{}");
        Files.createDirectories(root.resolve("scripts/pvs1"));
        for (String name : java.util.List.of("start_loss_functional_library.py", "start_loss_functional_reading.py", "start_loss_experiment_facts.py"))
            Files.writeString(root.resolve("scripts/pvs1/" + name), "# fixture");
        Files.writeString(root.resolve("local/python"), "fixture");
        var config = mapper.createObjectNode().put("schemaVersion", PublicLiteratureJobs.VERSION).put("enabled", true)
                .put("projectRoot", root.toString()).put("library", "local/knowledge/library").put("python", "local/python");
        Path path = root.resolve("local/config.json"); mapper.writeValue(path.toFile(), config); return path;
    }
    private ObjectNode report(String gene) {
        var r = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-functional-library-v1").put("geneSymbol", gene)
                .put("indexSha256", CodingTranscriptMaterialSupply.sha("{}")).put("status", "READ_WITH_LIMITS")
                .put("runtimeEligible", false).put("functionalRescueEstablished", false).put("negativeFunctionalCoverageGranted", false);
        r.putArray("pendingDocuments");
        String xml = "<article>whole original ∼</article>";
        r.putObject("originals").putObject(CodingTranscriptMaterialSupply.sha(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("kind", "PUBLIC_PAPER_XML").put("rawXml", xml);
        var doc = r.putArray("documents").addObject().put("pmcid", "PMC123").put("reviewedUnitCount", 1).put("matchingUnitCount", 2)
                .put("sourceSha256", CodingTranscriptMaterialSupply.sha(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        doc.putArray("pendingUnits").addObject().put("reasonCode", "READING_UNIT_BUDGET");
        var assessment = doc.putObject("experimentalAssessment");
        assessment.putArray("remainingRequirements").add("EXACT_ISOFORM_TO_TRANSCRIPT_BINDING");
        var observation = assessment.putArray("observations").addObject().put("kind", "FRAGMENT_CONSTRUCT_REPORTED")
                .put("sourceQuote", "x".repeat(700)).put("sourceStart", 0).put("sourceEnd", 700).put("unitId", "/body/1")
                .put("sourceRole", "PRIMARY_OR_DISCUSSION_REQUIRES_REVIEW");
        observation.putObject("reportedValues"); return r;
    }
    private Pvs1StartLossFunctionalLiterature reader(Path config, java.util.function.Consumer<ObjectNode> mutate) {
        return new Pvs1StartLossFunctionalLiterature(mapper, config, (command, working, timeout) -> {
            calls.incrementAndGet(); assertEquals(30, timeout.toSeconds());
            assertEquals(root.toRealPath(), working);
            assertFalse(command.toString().contains("patient"));
            var report = report(command.get(command.indexOf("--gene") + 1)); mutate.accept(report);
            mapper.writeValue(Path.of(command.get(command.indexOf("--output") + 1)).toFile(), report);
            return new ProcessResult(0, "", false);
        });
    }
    @Test void summaryIsBoundedFullSourceAndObservationSurviveSerializationAndBatchReuse() throws Exception {
        var r = reader(configuration(), ignored -> {}); var result = r.review("GENE1");
        assertEquals("READ_WITH_LIMITS", result.path("status").asText());
        assertEquals(600, result.at("/documents/0/observations/0/sourceQuote").asText().length());
        assertTrue(result.at("/documents/0/observations/0/quoteTruncated").asBoolean());
        assertEquals(1, result.at("/documents/0/pendingUnitCount").asInt());
        var restored = mapper.readTree(mapper.writeValueAsBytes(r.originals()));
        var raw = restored.path(result.path("reportSha256").asText()).path("rawJson").asText();
        assertEquals(result.path("reportSha256").asText(), CodingTranscriptMaterialSupply.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(700, mapper.readTree(raw).at("/documents/0/experimentalAssessment/observations/0/sourceQuote").asText().length());
        assertEquals(2, restored.size());
        assertEquals(result, r.review("GENE1")); assertEquals(1, calls.get());
        result.put("runtimeEligible", true); assertFalse(r.review("GENE1").path("runtimeEligible").asBoolean());
        try (var files = Files.list(root.resolve("local/temp"))) { assertEquals(0, files.count()); }
    }
    @Test void indexOrReaderChangeInvalidatesReuse() throws Exception {
        var r = reader(configuration(), ignored -> {}); r.review("GENE1");
        Files.writeString(root.resolve("scripts/pvs1/start_loss_experiment_facts.py"), "# changed");
        r.review("GENE1"); assertEquals(2, calls.get());
    }
    @Test void candidateJoinIsRecomputedWhileFullPaperReadIsReusedForTheGene() throws Exception {
        var reader = reader(configuration(), ignored -> {});
        var first = new Pvs1StartLossFunctionalApplicabilityTest().transcripts();
        var second = first.deepCopy(); ((ObjectNode) second.at("/findings/0")).put("transcript", "NM_000002.1");
        var a = reader.review("GENE1", first); var b = reader.review("GENE1", second);
        assertEquals(1, calls.get());
        assertEquals("NM_000001.2", a.at("/candidateApplicability/candidates/0/transcript").asText());
        assertEquals("NM_000002.1", b.at("/candidateApplicability/candidates/0/transcript").asText());
        assertFalse(reader.review("GENE1").has("candidateApplicability"));
        assertEquals(a.path("reportSha256"), b.path("reportSha256"));
    }
    @Test void changedGeneFalseScoringFlagAndAlteredOriginalAreRejected() throws Exception {
        Path config = configuration();
        for (java.util.function.Consumer<ObjectNode> mutation : java.util.List.<java.util.function.Consumer<ObjectNode>>of(
                r -> r.put("geneSymbol", "OTHER"), r -> r.put("runtimeEligible", true),
                r -> ((ObjectNode) r.path("originals").elements().next()).put("rawXml", "altered"))) {
            var reader = reader(config, mutation); var result = reader.review("GENE1");
            assertEquals("UNAVAILABLE", result.path("status").asText());
            assertFalse(result.path("runtimeEligible").asBoolean()); assertTrue(reader.originals().isEmpty());
        }
    }
    @Test void timeoutDoesNotCrashOrGrantNegativeEvidence() throws Exception {
        var r = new Pvs1StartLossFunctionalLiterature(mapper, configuration(), (command, dir, duration) -> new ProcessResult(-1, "private stderr", true));
        var result = r.review("GENE1");
        assertEquals("FUNCTIONAL_LITERATURE_READING_TIMEOUT", result.path("reasonCode").asText());
        assertFalse(result.toString().contains("private stderr"));
        assertFalse(result.path("negativeFunctionalCoverageGranted").asBoolean());
    }
    @Test void disabledWorkflowDoesNotRunProcess() throws Exception {
        Path config = configuration(); var c = (ObjectNode) mapper.readTree(config.toFile()); c.put("enabled", false); mapper.writeValue(config.toFile(), c);
        assertEquals("DISABLED", reader(config, ignored -> {}).review("GENE1").path("status").asText());
        assertEquals(0, calls.get());
    }
    @Test void normalPreparationAttachesFindingsWithoutChangingStructuralConclusionOrStrength() throws Exception {
        var reader = reader(configuration(), ignored -> {});
        var p = new Pvs1StartLossAutomaticPreparation(mapper, () -> { throw new AssertionError(); },
                new Pvs1StartLossPublicSources(mapper, false), () -> null, null, reader);
        var context = mapper.createObjectNode(); context.putObject("gene").put("symbol", "GENE1");
        var prepared = mapper.createObjectNode();
        var t = prepared.putObject("transcriptReview").put("status", "UNRESOLVED").put("complete", false);
        t.set("findings", new Pvs1StartLossFunctionalApplicabilityTest().transcripts().path("findings"));
        p.attachFunctionalReading(context, prepared);
        assertEquals("UNRESOLVED", t.path("status").asText()); assertFalse(t.path("complete").asBoolean());
        assertEquals("READ_WITH_LIMITS", t.at("/functionalLiterature/status").asText());
        assertEquals(reader.originals(), p.originals());
        assertEquals(1, t.at("/functionalLiterature/candidateApplicability/candidateCount").asInt());
        var before = prepared.deepCopy(); var visible = Pvs1StartLossAutomaticPreparation.publicSummary(mapper, prepared);
        assertEquals(t.at("/functionalLiterature/reportSha256"), visible.at("/transcriptReview/functionalLiterature/reportSha256"));
        assertEquals(before, prepared);
        for (String status : java.util.List.of("FOUND_FUNCTIONAL_ALTERNATIVE", "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET", "NOT_REQUIRED")) {
            prepared.putObject("transcriptReview").put("status", status);
            p.attachFunctionalReading(context, prepared); assertFalse(prepared.path("transcriptReview").has("functionalLiterature"));
        }
        assertEquals(1, calls.get());
    }
    @Test @EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
    void nativeRegisteredPapersAreReadByActualPythonWithoutNetwork() throws Exception {
        Path project = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        var reader = new Pvs1StartLossFunctionalLiterature(mapper, project.resolve("local/private/config/denovo-literature-workflow/config.json"));
        // Real papers + synthetic structural candidate: tests the failure to bind
        // unsupported isoform aliases, not a native patient/variant evaluation.
        var result = reader.review("MECP2", new Pvs1StartLossFunctionalApplicabilityTest().transcripts());
        assertEquals("READ_WITH_LIMITS", result.path("status").asText(), result::toString);
        assertEquals(2, result.path("documentCount").asInt());
        assertTrue(result.toString().contains("FRAGMENT_CONSTRUCT_REPORTED"));
        assertTrue(result.toString().contains("PARTIAL_READOUT_RESCUE_REPORTED"));
        assertFalse(result.path("functionalRescueEstablished").asBoolean());
        assertFalse(result.path("negativeFunctionalCoverageGranted").asBoolean());
        assertEquals(3, reader.originals().size());
        assertEquals("IDENTITY_NOT_ESTABLISHED", result.at("/candidateApplicability/candidates/0/status").asText());
        assertEquals("NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT", result.at("/candidateApplicability/candidates/0/documents/0/identityRelation").asText());
        var archive = mapper.createObjectNode(); archive.set("reading", result); archive.set("originals", reader.originals());
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = root;
        persistence.saveAndRead(mapper.writeValueAsString(result), mapper.writeValueAsString(archive));
    }
}
