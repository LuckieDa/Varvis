package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local registered-paper reading only. Its findings cannot grant functional
 * transcript qualification, negative coverage or an ACMG strength. */
public final class Pvs1StartLossFunctionalLiterature {
    private final ObjectMapper mapper;
    private final Path configuration;
    private final ExternalProcessRunner runner;
    private final Map<String, ObjectNode> cache = new LinkedHashMap<>();
    private final ObjectNode originals;
    private static final List<String> METHODS = List.of("start_loss_functional_library.py",
            "start_loss_functional_reading.py", "start_loss_experiment_facts.py");

    public Pvs1StartLossFunctionalLiterature(ObjectMapper mapper, Path configuration) {
        this(mapper, configuration, new SystemExternalProcessRunner());
    }
    Pvs1StartLossFunctionalLiterature(ObjectMapper mapper, Path configuration, ExternalProcessRunner runner) {
        this.mapper = mapper; this.configuration = configuration; this.runner = runner;
        originals = mapper.createObjectNode();
    }
    private static byte[] bounded(Path path, int maximum) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length == 0 || bytes.length > maximum) throw new IOException("FUNCTIONAL_LITERATURE_SIZE_LIMIT");
            return bytes;
        }
    }
    private JsonNode config() throws IOException {
        var c = mapper.readTree(bounded(configuration, 16384));
        if (c == null || !PublicLiteratureJobs.VERSION.equals(c.path("schemaVersion").asText()))
            throw new IOException("FUNCTIONAL_LITERATURE_CONFIG_INVALID");
        return c;
    }
    private static Path local(Path root, JsonNode c, String field) throws IOException {
        String value = c.path(field).asText();
        if (value.isBlank()) throw new IOException("FUNCTIONAL_LITERATURE_CONFIG_INVALID");
        Path result = root.resolve(value).toRealPath();
        if (!result.startsWith(root.resolve("local").toRealPath())) throw new IOException("FUNCTIONAL_LITERATURE_PATH_INVALID");
        return result;
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("method", "REGISTERED_PVS1_FUNCTIONAL_READING_V1")
                .put("candidateApplicabilityVersion", Pvs1StartLossFunctionalApplicability.VERSION);
        try {
            var c = config(); out.put("enabled", c.path("enabled").asBoolean());
            if (!c.path("enabled").asBoolean()) return out.put("status", "DISABLED");
            Path root = Path.of(c.path("projectRoot").asText()).toRealPath();
            Path library = local(root, c, "library");
            out.put("indexSha256", CodingTranscriptMaterialSupply.sha(bounded(library.resolve("evidence/PVS1/index.json"), 1048576)));
            for (String method : METHODS) out.put(method, CodingTranscriptMaterialSupply.sha(bounded(root.resolve("scripts/pvs1/" + method), 262144)));
            return out.put("status", "AVAILABLE");
        } catch (IOException | RuntimeException unavailable) { return out.put("status", "UNAVAILABLE"); }
    }
    public ObjectNode originals() { return originals.deepCopy(); }

    /** Bind this reader's full report, rather than its truncated UI excerpt, to
     * the current unresolved structural candidates. No caller-supplied report. */
    synchronized ObjectNode review(String gene, JsonNode transcripts) {
        var result = review(gene);
        var raw = originals.path(result.path("reportSha256").asText()).path("rawJson");
        if (!raw.isTextual()) return result;
        try {
            result.set("candidateApplicability", Pvs1StartLossFunctionalApplicability.assess(mapper, transcripts, mapper.readTree(raw.asText())));
        } catch (IOException | RuntimeException invalid) {
            result.putObject("candidateApplicability").put("schemaVersion", "pvs1-start-loss-functional-applicability-v1")
                    .put("status", "UNAVAILABLE").put("reasonCode", "FUNCTIONAL_LITERATURE_CANDIDATE_ASSESSMENT_FAILED")
                    .put("functionalQualificationGranted", false).put("negativeFunctionalCoverageGranted", false);
        }
        return result;
    }

    public synchronized ObjectNode review(String gene) {
        var identity = identity();
        String key = gene + ":" + identity.toString();
        var previous = cache.get(key);
        if (previous != null) return previous.deepCopy();
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-functional-summary-v1")
                .put("runtimeEligible", false).put("functionalRescueEstablished", false).put("negativeFunctionalCoverageGranted", false);
        Path output = null, work = null;
        try {
            if (!gene.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw new IOException("FUNCTIONAL_LITERATURE_GENE_INVALID");
            if (!"AVAILABLE".equals(identity.path("status").asText()))
                return out.put("status", identity.path("status").asText()).put("reasonCode", "FUNCTIONAL_LITERATURE_READER_UNAVAILABLE");
            var c = config();
            if (!c.path("enabled").asBoolean()) return out.put("status", "DISABLED");
            Path root = Path.of(c.path("projectRoot").asText()).toRealPath();
            Path library = local(root, c, "library"), python = local(root, c, "python");
            Path temporary = root.resolve("local/temp"); Files.createDirectories(temporary);
            work = Files.createTempDirectory(temporary, "pvs1-reading-"); output = work.resolve("result.json");
            var process = runner.run(List.of(python.toString(), root.resolve("scripts/pvs1/start_loss_functional_library.py").toString(),
                    "--library", library.toString(), "--gene", gene, "--index-sha256", identity.path("indexSha256").asText(),
                    "--output", output.toString()), root, Duration.ofSeconds(30));
            if (process == null || !process.isSuccess()) throw new IOException(process != null && process.isTimedOut()
                    ? "FUNCTIONAL_LITERATURE_READING_TIMEOUT" : "FUNCTIONAL_LITERATURE_READING_FAILED");
            if (!identity.equals(identity())) throw new IOException("FUNCTIONAL_LITERATURE_SOURCE_CHANGED");
            var result = mapper.readTree(bounded(output, 8388608));
            if (!(result instanceof ObjectNode report) || !"pvs1-start-loss-functional-library-v1".equals(report.path("schemaVersion").asText())
                    || !gene.equals(report.path("geneSymbol").asText()) || !identity.path("indexSha256").equals(report.path("indexSha256"))
                    || !report.path("documents").isArray() || report.path("documents").size() > 8
                    || !report.path("pendingDocuments").isArray()) throw new IOException("FUNCTIONAL_LITERATURE_RESULT_INVALID");
            for (String flag : List.of("runtimeEligible", "functionalRescueEstablished", "negativeFunctionalCoverageGranted"))
                if (!report.path(flag).isBoolean() || report.path(flag).asBoolean()) throw new IOException("FUNCTIONAL_LITERATURE_SCORING_NOT_ALLOWED");
            for (var source : report.path("originals").properties()) {
                if (!source.getKey().equals(CodingTranscriptMaterialSupply.sha(source.getValue().path("rawXml").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    throw new IOException("FUNCTIONAL_LITERATURE_ORIGINAL_CHANGED");
            }
            originals.setAll((ObjectNode) report.path("originals")); report.remove("originals");
            report.set("readerIdentity", identity);
            String raw = mapper.writeValueAsString(report), sha = CodingTranscriptMaterialSupply.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            originals.putObject(sha).put("kind", "PVS1_FUNCTIONAL_LITERATURE_READING").put("rawJson", raw);
            out.put("reportSha256", sha).put("status", report.path("status").asText())
                    .put("documentCount", report.path("documents").size()).put("pendingDocumentCount", report.path("pendingDocuments").size());
            if (report.path("reusedReadingCount").isIntegralNumber())
                out.set("reusedReadingCount", report.path("reusedReadingCount"));
            var documents = out.putArray("documents");
            for (var doc : report.path("documents")) {
                var summary = documents.addObject();
                for (String field : List.of("pmcid", "sourceSha256", "reviewedUnitCount", "matchingUnitCount", "supplementCoverage"))
                    summary.set(field, doc.path(field).deepCopy());
                summary.put("pendingUnitCount", doc.path("pendingUnits").size());
                var assessment = doc.path("experimentalAssessment");
                summary.put("observationCount", assessment.path("observations").size());
                var observations = summary.putArray("observations");
                for (var observation : assessment.path("observations")) {
                    if (observations.size() == 20) break;
                    // The complete sentence remains in the archived report.
                    var brief = observations.addObject();
                    String quote = observation.path("sourceQuote").asText();
                    brief.put("sourceQuote", quote.substring(0, Math.min(600, quote.length())))
                            .put("quoteTruncated", quote.length() > 600);
                    for (String field : List.of("kind", "reportedValues", "useLimit", "sourceRole", "unitId", "sourceSha256", "sourceStart", "sourceEnd"))
                        brief.set(field, observation.path(field).deepCopy());
                }
                summary.set("remainingRequirements", assessment.path("remainingRequirements").deepCopy());
            }
        } catch (IOException | RuntimeException unavailable) {
            String reason = unavailable.getMessage();
            out.put("status", "UNAVAILABLE").put("reasonCode", reason != null && reason.matches("FUNCTIONAL_LITERATURE_[A-Z_]+")
                    ? reason : "FUNCTIONAL_LITERATURE_READING_FAILED");
        } finally {
            try { if (output != null) Files.deleteIfExists(output); if (work != null) Files.deleteIfExists(work); }
            catch (IOException cleanup) { out.put("temporaryCleanupPending", true); }
        }
        if (cache.size() < 100) cache.put(key, out.deepCopy());
        return out;
    }
}
