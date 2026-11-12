package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiFunction;

/** Business adjunct only. Never invokes or mutates a computational evidence producer. */
public final class SplicingExperimentChecks {
    public static final String POLICY = "RECORDED_SPLICE_OBSERVATION_CHECK@1.0.0";
    private final ObjectMapper mapper;
    private final Resource catalog;
    private final Path root;
    private final BiFunction<String, String, ObjectNode> savedRun;

    public SplicingExperimentChecks(ObjectMapper mapper, Resource catalog, Path root,
            BiFunction<String, String, ObjectNode> savedRun) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.root = root.toAbsolutePath().normalize();
        this.savedRun = savedRun;
    }

    public ObjectNode checkCurrent(String analysisId, String runId) {
        ObjectNode calculation = savedRun.apply(analysisId, runId);
        if (!calculation.path("candidates").isArray()) throw error("CALCULATION_NOT_AVAILABLE");
        ObjectNode snapshot = mapper.createObjectNode().put("schemaVersion", "splicing-experiment-check-v1")
                .put("policyVersion", POLICY).put("analysisId", analysisId).put("runId", runId)
                .put("calculationSha256", hash(calculation));
        ObjectNode material = loadCatalog();
        snapshot.set("material", material);
        ArrayNode checks = snapshot.putArray("checks");
        for (JsonNode candidate : calculation.path("candidates")) {
            for (JsonNode attempt : candidate.path("attempts")) {
                for (JsonNode evaluation : attempt.path("criterionEvaluations")) {
                    String criterion = evaluation.path("criterionId").asText();
                    if (!List.of("ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BP7").contains(criterion)
                            || !attempt.path("transcriptInput").path("predictionPath").asText().startsWith("SPLICEAI_")) continue;
                    checks.add(check(candidate.path("candidateId").asText(), attempt, evaluation, material));
                }
            }
        }
        snapshot.put("checkId", hash(snapshot));
        persist(snapshot);
        return read(analysisId, runId, snapshot.path("checkId").asText());
    }

    public List<ObjectNode> list(String analysisId, String runId) {
        savedRun.apply(analysisId, runId); // Same ownership/existence boundary as the original read API.
        Path folder = folder(analysisId, runId);
        if (Files.notExists(root)) return List.of();
        if (!Files.isDirectory(root)) throw error("CHECK_STORAGE_UNAVAILABLE");
        if (Files.notExists(folder)) return List.of();
        if (!Files.isDirectory(folder)) throw error("CHECK_STORAGE_UNAVAILABLE");
        try (var paths = Files.list(folder)) {
            List<ObjectNode> result = new ArrayList<>();
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).sorted().toList()) {
                String id = path.getFileName().toString().replace(".json", "");
                try {
                    ObjectNode item = read(analysisId, runId, id);
                    result.add(mapper.createObjectNode().put("checkId", id).put("availability", "AVAILABLE")
                            .put("catalogVersion", item.path("material").path("catalog").path("version").asText("不可用"))
                            .put("policyVersion", item.path("policyVersion").asText())
                            .put("savedAt", Files.getLastModifiedTime(path).toInstant().toString()));
                } catch (ResponseStatusException ex) {
                    if (!List.of("CHECK_SNAPSHOT_UNAVAILABLE", "CHECK_SNAPSHOT_INTEGRITY_FAILURE").contains(ex.getReason())) throw ex;
                    result.add(mapper.createObjectNode().put("checkId", id).put("availability", "UNAVAILABLE").put("reasonCode", ex.getReason()));
                } catch (IOException ex) {
                    result.add(mapper.createObjectNode().put("checkId", id).put("availability", "UNAVAILABLE").put("reasonCode", "CHECK_SNAPSHOT_METADATA_UNAVAILABLE"));
                }
            }
            result.sort(java.util.Comparator.comparing(o -> o.path("savedAt").asText()));
            return result;
        } catch (IOException | java.io.UncheckedIOException ex) { throw error("CHECK_STORAGE_UNAVAILABLE"); }
    }

    public ObjectNode read(String analysisId, String runId, String checkId) {
        ObjectNode calculation = savedRun.apply(analysisId, runId);
        if (!checkId.matches("[a-f0-9]{64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CHECK_ID");
        Path path = folder(analysisId, runId).resolve(checkId + ".json");
        try {
            JsonNode parsed = mapper.readTree(Files.readAllBytes(path));
            if (!(parsed instanceof ObjectNode snapshot)) throw error("CHECK_SNAPSHOT_INTEGRITY_FAILURE");
            ObjectNode identity = snapshot.deepCopy(); identity.remove("checkId");
            if (!checkId.equals(snapshot.path("checkId").asText()) || !checkId.equals(hash(identity))
                    || !analysisId.equals(snapshot.path("analysisId").asText()) || !runId.equals(snapshot.path("runId").asText())
                    || !hash(calculation).equals(snapshot.path("calculationSha256").asText())) throw error("CHECK_SNAPSHOT_INTEGRITY_FAILURE");
            return snapshot;
        } catch (IOException ex) { throw error("CHECK_SNAPSHOT_UNAVAILABLE"); }
    }

    private void persist(ObjectNode snapshot) {
        Path folder = folder(snapshot.path("analysisId").asText(), snapshot.path("runId").asText());
        Path destination = folder.resolve(snapshot.path("checkId").asText() + ".json");
        Path temporary = null;
        try {
            Files.createDirectories(folder);
            temporary = Files.createTempFile(folder, "check-", ".tmp");
            Files.writeString(temporary, compact(snapshot));
            // Atomic, no replacement, including concurrent service instances on the same local store.
            try { Files.createLink(destination, temporary); }
            catch (FileAlreadyExistsException reused) { /* read() verifies the existing immutable snapshot */ }
        } catch (IOException ex) { throw error("CHECK_STORAGE_UNAVAILABLE"); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* ignored temporary file cannot be read as a snapshot */ }
        }
    }

    private Path folder(String analysisId, String runId) {
        return root.resolve(AcmgBusinessStore.sha(analysisId + "\n" + runId));
    }

    /** Capture once from the configured source, before evaluating a selected batch. No persistence,
     * saved-run access or clinical approval. A caller-supplied JSON document cannot mint a snapshot. */
    public CatalogSnapshot captureCatalog() {
        ObjectNode material;
        try { material = loadCatalog(true); }
        catch (RuntimeException unavailable) {
            material = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "CATALOG_READ_FAILED");
        }
        var body = mapper.createObjectNode().put("schemaVersion", "splicing-experiment-catalog-snapshot-v1")
                .put("validationPolicy", POLICY).put("clinicalApproval", false).put("externalWorldCovered", false);
        body.set("material", material);
        body.put("snapshotId", "splice-catalog-sha256-" + hash(body));
        return new CatalogSnapshot(body);
    }

    public static final class CatalogSnapshot {
        private final ObjectNode body;
        private CatalogSnapshot(ObjectNode body) { this.body = body.deepCopy(); }
        public ObjectNode content() { return body.deepCopy(); }
    }

    private ObjectNode loadCatalog() { return loadCatalog(false); }

    private ObjectNode loadCatalog(boolean includeRawDigest) {
        ObjectNode result = mapper.createObjectNode();
        if (!catalog.exists()) return result.put("status", "UNAVAILABLE").put("reasonCode", "CATALOG_MISSING");
        byte[] bytes;
        try (var stream = catalog.getInputStream()) { bytes = stream.readNBytes(1_048_577); }
        catch (IOException ex) { return result.put("status", "UNAVAILABLE").put("reasonCode", "CATALOG_READ_FAILED"); }
        try {
            if (bytes.length > 1_048_576) throw new IllegalArgumentException();
            JsonNode document = mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            validateCatalog(document);
            if (includeRawDigest) new org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document);
            result.put("status", "AVAILABLE"); result.set("catalog", document);
            if (includeRawDigest) result.put("observedBytesSha256", bytesHash(bytes));
            return result;
        } catch (IOException | RuntimeException ex) {
            return result.put("status", "UNAVAILABLE").put("reasonCode", "CATALOG_INVALID")
                    .put("observedBytesSha256", bytesHash(bytes));
        }
    }

    public void validateCatalog(JsonNode document) {
        if (document == null) throw new IllegalArgumentException("Missing catalog");
        require(document.isObject() && "public-splicing-experiments-v1".equals(document.path("schemaVersion").asText()));
        requireText(document, "catalogId", "version", "coverage", "updateMethod");
        verifyHash(document);
        require(document.path("records").isArray());
        var ids = new HashSet<String>();
        for (JsonNode record : document.path("records")) {
            requireText(record, "recordId", "version", "geneId", "geneSymbol", "summary", "molecularScope", "diseaseScope");
            requireText(record.path("reportedAllele"), "assembly");
            require(ids.add(record.path("recordId").asText())); verifyHash(record);
            JsonNode allele = record.path("allele");
            require("GRCh38".equals(allele.path("assembly").asText()) && allele.path("contig").asText().matches("([1-9]|1[0-9]|2[0-2]|X|Y)")
                    && allele.path("position").isIntegralNumber() && allele.path("position").asLong() > 0
                    && allele.path("reference").asText().matches("[ACGT]") && allele.path("alternate").asText().matches("[ACGT]")
                    && !allele.path("reference").equals(allele.path("alternate")));
            JsonNode mapping = record.path("transcriptMapping");
            requireText(mapping, "ensembl", "refseq", "sourceUrl", "sourceSha256", "sourceRow", "alleleMappingEvidence");
            require(mapping.path("ensembl").asText().matches("ENST[0-9]+\\.[0-9]+")
                    && mapping.path("refseq").asText().matches("NM_[0-9]+\\.[0-9]+"));
            String[] row = mapping.path("sourceRow").asText().split("\t", -1);
            require(row.length == 14 && row[2].equals(record.path("geneId").asText()) && row[3].equals(record.path("geneSymbol").asText())
                    && row[5].equals(mapping.path("refseq").asText()) && row[7].equals(mapping.path("ensembl").asText()) && row[9].equals("MANE Select"));
            require(mapping.path("sourceSha256").asText().matches("[a-f0-9]{64}")); safeUrl(mapping.path("sourceUrl").asText());
            JsonNode source = record.path("source");
            requireText(source, "doi", "pmid", "url", "locator", "artifactSha256"); safeUrl(source.path("url").asText());
            require(source.path("doi").asText().startsWith("10.") && source.path("pmid").asText().matches("[0-9]+")
                    && source.path("artifactSha256").asText().matches("[a-f0-9]{64}"));
            requireText(record.path("experiment"), "rnaEvent", "type", "system", "controls", "limitations");
            require("ALTERED_SPLICING_OBSERVED".equals(record.path("experiment").path("observation").asText()));
            requireText(record.path("provenance"), "compilationMethod", "sourceVerifiedAt", "clinicalReview");
            require("NOT_PERFORMED".equals(record.path("provenance").path("clinicalReview").asText()));
            java.time.LocalDate.parse(record.path("provenance").path("sourceVerifiedAt").asText());
            require("synonymous_variant".equals(record.path("consequence").asText()));
        }
    }

    private ObjectNode check(String candidateId, JsonNode attempt, JsonNode evaluation, JsonNode material) {
        JsonNode context = attempt.path("context"), input = attempt.path("transcriptInput");
        ObjectNode out = mapper.createObjectNode().put("candidateId", candidateId)
                .put("evaluationId", evaluation.path("evaluationId").asText()).put("contextId", context.path("contextId").asText())
                .put("criterionId", evaluation.path("criterionId").asText()).put("status", "UNVERIFIED")
                .put("reasonCode", "NO_MATCHING_RECORD").put("molecularApplicability", "NOT_CONFIRMED")
                .put("diseaseApplicability", "NOT_ASSESSED_NO_CLINICAL_INFERENCE");
        out.set("context", context.deepCopy()); out.set("calculation", evaluation.deepCopy());
        ArrayNode matched = out.putArray("recordIds");
        if (!"APPLICABLE".equals(evaluation.path("criterionApplicability").path("status").asText())) return out.put("reasonCode", "CALCULATION_NOT_APPLICABLE");
        if (!"AVAILABLE".equals(material.path("status").asText())) return out.put("status", "UNAVAILABLE").put("reasonCode", material.path("reasonCode").asText());
        if (!"SPLICEAI_SYNONYMOUS".equals(input.path("predictionPath").asText())) return out.put("reasonCode", "OUTSIDE_CURRENT_COVERAGE");
        String tx = context.path("transcript").path("accession").asText() + "." + context.path("transcript").path("version").asText();
        JsonNode allele = context.path("variant").path("inputAllele");
        boolean inputValid = tx.equals(input.path("versionedTranscriptId").asText())
                && "synonymous_variant".equals(input.path("consequence").asText())
                && "NATIVE".equals(context.path("variant").path("assemblyLineage").path("mappingStatus").asText())
                && "MATCH".equals(context.path("variant").path("normalization").path("refValidation").asText())
                && alleleMatches(allele, context.path("variant").path("evidenceQueryAllele"))
                && context.path("contextId").asText().equals(evaluation.path("contextId").asText());
        if (!inputValid) return out.put("reasonCode", "INPUT_APPLICABILITY_UNCERTAIN");
        for (JsonNode record : material.path("catalog").path("records")) {
            if (!alleleMatches(allele, record.path("allele"))) continue;
            JsonNode mapping = record.path("transcriptMapping");
            if (!(tx.equals(mapping.path("ensembl").asText()) || tx.equals(mapping.path("refseq").asText()))
                    || !context.path("gene").path("hgncId").equals(record.path("geneId"))) {
                out.put("reasonCode", "TRANSCRIPT_OR_GENE_APPLICABILITY_UNCERTAIN"); continue;
            }
            matched.add(record.path("recordId").asText());
        }
        if (matched.isEmpty()) return out;
        out.put("molecularApplicability", "RECORDED_ASSAY_CONTEXT_ONLY");
        if (validSpliceSupport(attempt, evaluation, tx)) return out.put("status", "OBSERVATION_MISMATCH_REVIEW_REQUIRED")
                .put("reasonCode", "BENIGN_COMPUTATIONAL_SUPPORT_VS_ALTERED_SPLICING");
        return out.put("reasonCode", "NO_COMPARABLE_BENIGN_SUPPORT");
    }

    private boolean validSpliceSupport(JsonNode attempt, JsonNode evaluation, String tx) {
        if (!"MET".equals(evaluation.path("evaluationResult").path("status").asText())) return false;
        if ("ACMG_AMP_2015:BP7".equals(evaluation.path("criterionId").asText())) {
            boolean paired = false;
            for (JsonNode bp4 : attempt.path("criterionEvaluations")) {
                if ("ACMG_AMP_2015:BP4".equals(bp4.path("criterionId").asText())
                        && evaluation.path("contextId").equals(bp4.path("contextId"))
                        && "APPLICABLE".equals(bp4.path("criterionApplicability").path("status").asText())
                        && validSpliceSupport(attempt, bp4, tx)) paired = true;
            }
            if (!paired) return false;
        }
        for (JsonNode evidence : attempt.path("atomicEvidence")) {
            boolean linked = false;
            for (JsonNode id : evaluation.path("evidenceIds")) if (id.equals(evidence.path("evidenceId"))) linked = true;
            if (!linked || !evaluation.path("contextId").equals(evidence.path("contextId"))
                    || !evidence.path("source").path("sourceId").asText().startsWith("SPLICEAI_")
                    || !"HIT".equals(evidence.path("queryOutcome").path("status").asText())
                    || !"SUCCEEDED".equals(evidence.path("resourceExecution").path("status").asText())
                    || !"AVAILABLE".equals(evidence.path("resourceAvailability").path("status").asText())
                    || !"APPLICABLE".equals(evidence.path("resourceApplicability").path("status").asText())
                    || !"true".equals(metric(evidence, "spliceai.exactAlleleMatch"))
                    || !"true".equals(metric(evidence, "spliceai.geneContextMatch"))
                    || !tx.equals(metric(evidence, "spliceai.selectedTranscriptId"))) continue;
            return true;
        }
        return false;
    }

    private static String metric(JsonNode evidence, String key) {
        for (JsonNode value : evidence.path("measurements")) if (key.equals(value.path("metricId").asText())) return value.path("normalizedValue").asText();
        return "";
    }

    private static boolean alleleMatches(JsonNode a, JsonNode b) {
        for (String key : List.of("assembly", "position", "reference", "alternate")) if (a.path(key).isMissingNode() || !a.path(key).equals(b.path(key))) return false;
        return a.path("contig").asText().replaceFirst("^chr", "").equals(b.path("contig").asText().replaceFirst("^chr", ""));
    }

    public String hash(JsonNode node) { return AcmgBusinessStore.sha(compact(sorted(node))); }
    private static String bytesHash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode(); var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, sorted(node.path(name)))); return result;
        }
        if (node.isArray()) { ArrayNode result = mapper.createArrayNode(); node.forEach(n -> result.add(sorted(n))); return result; }
        return node;
    }
    private String compact(JsonNode node) {
        try { return mapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(node); }
        catch (IOException ex) { throw error("CHECK_SERIALIZATION_FAILURE"); }
    }
    private void verifyHash(JsonNode node) {
        require(node instanceof ObjectNode); ObjectNode identity = ((ObjectNode) node).deepCopy(); identity.remove("contentSha256");
        require(hash(identity).equals(node.path("contentSha256").asText()));
    }
    private static void requireText(JsonNode node, String... keys) { for (String key : keys) require(node.path(key).isTextual() && !node.path(key).asText().isBlank()); }
    private static void safeUrl(String text) {
        java.net.URI uri = java.net.URI.create(text); require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null);
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid experimental catalog"); }
    private static ResponseStatusException error(String code) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, code); }
}
