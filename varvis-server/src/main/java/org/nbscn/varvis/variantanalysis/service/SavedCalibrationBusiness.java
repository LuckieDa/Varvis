package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;

/** Fixed saved-source calibration. No acquisition capability; archive loading is claim-owned. */
public final class SavedCalibrationBusiness {
    public static final String OPERATION = "CALIBRATE_SAVED_OBSERVATIONS";
    public static final String DECLARATION = "ordinary-pp3-bp4-development-v3";
    private final ObjectMapper mapper;
    private final PredictionResourceCompatibility.StandardBinding binding;
    private final PredictionLocalResourceBinding local;
    private final ObjectNode contract;
    private final Map<String, Path> paths;
    private final String sampleId;
    private final ObjectNode reference;
    private final ObjectNode identity;

    public SavedCalibrationBusiness(ObjectMapper mapper, PredictionResourceCompatibility.StandardBinding binding,
            PredictionLocalResourceBinding local, ObjectNode contract, Map<String, Path> paths,
            String sourceRequestSha, String sampleId) {
        this.mapper = mapper; this.binding = binding; this.local = local; this.contract = contract.deepCopy();
        this.paths = Map.copyOf(paths); this.sampleId = sampleId;
        require(sourceRequestSha.matches("[a-f0-9]{64}") && !sampleId.isBlank(), "CALIBRATION_REFERENCE_INVALID");
        var e = binding.evidence();
        require(e.path("allowedObservations").size() == 2, "CALIBRATION_FIXED_PAIR_REQUIRED");
        reference = mapper.createObjectNode().put("operation", OPERATION).put("sourceRunId", e.path("childRunId").asText())
                .put("sourceRequestSha256", sourceRequestSha).put("executionSourceSha256", e.path("executionSourceSha256").asText())
                .put("calibrationCanonicalSha256", e.path("calibrationCanonicalSha256").asText()).put("adoptionSha256", binding.sha256());
        var refs = reference.putArray("results");
        for (var row : e.path("allowedObservations")) refs.addObject().put("candidateId", row.path("candidateId").asText())
                .put("sourceResultId", row.path("childResultId").asText()).put("sourceObservationSha256", row.path("observationSha256").asText());
        identity = mapper.createObjectNode().put("runtimeVersion", "saved-calibration-business-1.0.0")
                .put("analysisId", e.path("analysisId").asText()).put("rawAncestorRunId", e.path("parentRunId").asText())
                .put("sampleId", sampleId).put("localBindingSha256", local.identity().path("localBindingSha256").asText())
                .put("scope", "EXACT_SINGLE_ROW").put("overallCompatibility", "PARTIAL").put("newScientificAcquisitionAllowed", false);
        identity.set("reference", reference.deepCopy()); identity.set("allowedObservations", e.path("allowedObservations").deepCopy());
        // File digests bind the frozen annotations/selections as well as the old run/result identities.
        identity.set("archiveByteSha256", e.path("inputArtifacts").deepCopy());
        identity.set("localAdoption", e.path("localAdoption").deepCopy());
        identity.set("representationContract", SavedCalibrationRepresentations.contract(mapper));
    }
    public static SavedCalibrationBusiness configured(ObjectMapper mapper, Path config, String sha) {
        try {
        require(Files.size(config) <= 65536, "CALIBRATION_CONFIG_TOO_LARGE");
        byte[] bytes = Files.readAllBytes(config);
        require(AcmgBusinessStore.sha(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).equals(sha), "CALIBRATION_CONFIG_DIGEST_MISMATCH");
        JsonNode c = mapper.readTree(bytes);
        var binding = PredictionResourceCompatibility.StandardBinding.loadExact(mapper, Path.of(c.path("adoptionPath").asText()),
                c.path("adoptionSha256").asText(), Path.of(c.path("supplementPath").asText()));
        var local = PredictionLocalResourceBinding.load(mapper, Path.of(c.path("bindingPath").asText()), c.path("bindingSha256").asText());
        var contract = PredictionCalibration.loadStandard(mapper, Files.readAllBytes(Path.of(c.path("contractPath").asText())),
                c.path("contractSha256").asText(), Files.readAllBytes(Path.of(c.path("thresholdSourcePath").asText())),
                Files.readAllBytes(Path.of(c.path("readmeSourcePath").asText())));
        Map<String, Path> paths = new LinkedHashMap<>(); c.path("artifacts").properties().forEach(e -> paths.put(e.getKey(), Path.of(e.getValue().asText())));
        return new SavedCalibrationBusiness(mapper, binding, local, contract, paths, c.path("sourceRequestSha256").asText(), c.path("sampleId").asText());
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("CALIBRATION_CONFIG_UNAVAILABLE", failure); }
    }
    public ObjectNode identity() { return identity.deepCopy(); }
    public ObjectNode reference() { return reference.deepCopy(); }
    String analysisId() { return identity.path("analysisId").asText(); }
    String ancestorId() { return identity.path("rawAncestorRunId").asText(); }
    String sourceId() { return reference.path("sourceRunId").asText(); }
    List<String> candidates() { List<String> out = new ArrayList<>(); reference.path("results").forEach(r -> out.add(r.path("candidateId").asText())); return List.copyOf(out); }
    ObjectNode declaration(AcmgSubmission request, VariantAnalysisJob job) {
        require(analysisId().equals(job.getId()) && sampleId.equals(job.getSelectedSampleId() == null ? job.getRequestedSampleId() : job.getSelectedSampleId()), "CALIBRATION_ANALYSIS_OR_SAMPLE_MISMATCH");
        require(DECLARATION.equals(request.declarationVersion()) && request.topN() == 2 && sampleId.equals(request.sampleId())
                && "SYNTHETIC".equals(request.dataClass()) && request.patient() == null && request.retryOf() == null
                && request.association() == null && request.recommendations() == null && request.hypotheses().isEmpty()
                && request.calibration() != null, "CALIBRATION_DECLARATION_INVALID");
        ObjectNode submitted = mapper.valueToTree(request.calibration());
        JsonNode rows = submitted.remove("results"); ObjectNode expected = reference.deepCopy(); JsonNode fixed = expected.remove("results");
        if (rows == null) throw new IllegalArgumentException("CALIBRATION_REFERENCE_MISMATCH");
        require(submitted.equals(expected) && rows.isArray() && rows.size() == fixed.size(), "CALIBRATION_REFERENCE_MISMATCH");
        Set<JsonNode> unique = new HashSet<>(); rows.forEach(unique::add);
        require(unique.size() == fixed.size(), "CALIBRATION_REFERENCE_DUPLICATE");
        for (var row : fixed) require(unique.contains(row), "CALIBRATION_RESULT_REFERENCE_MISMATCH");
        ObjectNode declaration = mapper.valueToTree(request); declaration.set("calibration", reference.deepCopy()); return declaration;
    }
    SavedPredictionCalibration load() throws Exception { return SavedPredictionCalibration.load(mapper, binding, local, contract, paths); }
    ObjectNode verify(SavedPredictionCalibration reader, AcmgBusinessService.TrustedRun source, AcmgBusinessService.TrustedRun ancestor,
            AcmgBusinessStore.Source execution, VariantAnalysisJob job, JsonNode summaries) throws Exception {
        require(sourceId().equals(source.record().id()) && ancestorId().equals(ancestor.record().id())
                && reference.path("sourceRequestSha256").asText().equals(source.record().requestSha256()), "CALIBRATION_DB_CHAIN_MISMATCH");
        require(reader.savedArtifact("child").equals(source.response()) && reader.savedArtifact("parent").equals(ancestor.response()), "CALIBRATION_DB_ARCHIVE_RUN_MISMATCH");
        // The normal submit path serializes optional absent fields as null. Preserve that
        // existing declaration contract; never compare pretty-print request bytes to DB bytes.
        JsonNode archivedDeclaration = mapper.valueToTree(AcmgSubmission.parse(
                mapper.writeValueAsBytes(reader.savedArtifact("request")), mapper));
        require(archivedDeclaration.equals(source.privateData().path("declaration")), "CALIBRATION_DB_DECLARATION_MISMATCH");
        var plans = source.privateData().path("verifiedAssociationPlans");
        require(plans.isArray() && plans.size() == candidates().size(), "CALIBRATION_PRIVATE_PLANS_MISSING");
        for (String candidate : candidates()) {
            var prediction = prediction(source.response(), candidate); int found = 0;
            for (var plan : plans) if (plan.equals(prediction.path("plan"))) found++;
            require(found == 1, "CALIBRATION_PRIVATE_PUBLIC_PLAN_MISMATCH");
        }
        require(AcmgBusinessStore.sha(execution.json()).equals(execution.sha256())
                && execution.sha256().equals(reference.path("executionSourceSha256").asText()), "CALIBRATION_EXECUTION_SOURCE_MISMATCH");
        // Original stored JSON preserves annotation aliases. Do not hash float-bearing publication DTOs.
        var json = mapper.readTree(execution.json()); var archivedJob = reader.savedArtifact("job");
        require(analysisId().equals(json.path("analysisId").asText()) && sampleId.equals(json.path("sampleId").asText())
                && "GRCh38".equals(json.path("inputAssembly").asText()) && "GRCh38".equals(json.path("analysisAssembly").asText())
                && json.path("conversionApplied").isBoolean() && !json.path("conversionApplied").asBoolean()
                && job.getGenomeAssembly() != null && "GRCh38".equals(job.getGenomeAssembly().name())
                && sampleId.equals(archivedJob.path("selectedSampleId").asText()), "CALIBRATION_NATIVE_SOURCE_MISMATCH");
        var archived = reader.savedArtifact("candidates").path("variants");
        return SavedCalibrationRepresentations.verify(mapper, archived, summaries, json.path("candidates"), candidates());
    }
    static boolean semanticEquals(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            for (var field : a.properties()) if (!b.has(field.getKey()) || !semanticEquals(field.getValue(), b.path(field.getKey()))) return false;
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!semanticEquals(a.get(i), b.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }
    static JsonNode unique(JsonNode rows, String candidate) {
        JsonNode match = null;
        for (var row : rows) if (candidate.equals(row.path("candidateId").asText())) {
            require(match == null, "CALIBRATION_DUPLICATE_CANDIDATE"); match = row;
        }
        if (match == null) throw new IllegalArgumentException("CALIBRATION_CANDIDATE_MISSING"); return match;
    }
    static ObjectNode prediction(JsonNode run, String candidate) {
        var attempts = unique(run.path("candidates"), candidate).path("attempts");
        require(attempts.size() == 1 && attempts.get(0).path("prediction").isObject(), "CALIBRATION_PREDICTION_MISSING");
        return ((ObjectNode) attempts.get(0).path("prediction")).deepCopy();
    }
    ObjectNode result(JsonNode source, String candidate, ObjectNode comparison, ObjectNode provenance) {
        require("CALIBRATED".equals(comparison.path("status").asText()), "CALIBRATION_COMPARISON_NOT_COMPLETED");
        var result = prediction(source, candidate); result.remove("resultId");
        result.put("schemaVersion", "prediction-rule-result-v3").put("operation", OPERATION).put("calibratedAt", Instant.now().toString())
                .put("associationEvaluations", 0).put("calibrationPrepares", 1).put("calibrationComparisons", 1).put("calibratedMechanisms", 1)
                .put("sourceRunId", sourceId()).put("rawAncestorRunId", ancestorId());
        result.set("runtimeIdentity", identity()); result.set("calibration", comparison.deepCopy());
        result.set("businessProvenance", provenance.deepCopy());
        result.putObject("stages").put("rawLookupReady", true).put("associationReady", true).put("calibrationReady", true)
                .put("applicationReady", false).put("firstUnresolvedStage", "APPLICATION");
        ((ObjectNode) result.path("mechanisms")).set("PROTEIN_MISSENSE", comparison.deepCopy());
        result.set("remainingApplicationConditions", comparison.path("remainingApplicationConditions").deepCopy());
        result.put("resultId", "prediction-result-sha256-" + new AcmgBusinessArtifacts(mapper).requestHash(result)); return result;
    }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
