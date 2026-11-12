package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Offline-only reader of explicitly pinned normal exports. No database, provider or association execution. */
public final class SavedPredictionCalibration {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter hashes;
    private final PredictionResourceCompatibility.StandardBinding binding;
    private final PredictionLocalResourceBinding local;
    private final ObjectNode calibration;
    private final Map<String, JsonNode> saved;

    private SavedPredictionCalibration(ObjectMapper mapper, PredictionResourceCompatibility.StandardBinding binding,
            PredictionLocalResourceBinding local, ObjectNode calibration, Map<String, JsonNode> saved) {
        this.mapper = mapper.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        hashes = new RuntimeEvidenceV2Adapter(this.mapper); this.binding = binding; this.local = local;
        this.calibration = calibration.deepCopy(); this.saved = Map.copyOf(saved);
    }

    public static SavedPredictionCalibration load(ObjectMapper mapper, PredictionResourceCompatibility.StandardBinding binding,
            PredictionLocalResourceBinding local, ObjectNode calibration, Map<String, Path> paths) throws IOException {
        String issue = PredictionCalibration.standardIssue(calibration);
        if (issue == null) issue = binding.exactIssue(mapper, calibration, local);
        if (issue != null) throw new IllegalArgumentException(issue);
        Map<String, JsonNode> saved = new HashMap<>();
        for (String name : List.of("parent", "child", "request", "selections", "job", "candidates")) {
            Path path = paths.get(name);
            if (path == null) throw new IllegalArgumentException("SAVED_CALIBRATION_ARTIFACT_MISSING_" + name);
            require(Files.size(path) <= 262144, "SAVED_CALIBRATION_ARTIFACT_TOO_LARGE");
            byte[] bytes = Files.readAllBytes(path);
            require(PredictionCalibration.sha256(bytes).equals(binding.evidence().path("inputArtifacts").path(name).asText()),
                    "SAVED_CALIBRATION_ARTIFACT_DIGEST_MISMATCH_" + name);
            JsonNode value = mapper.readTree(bytes);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("SAVED_CALIBRATION_ARTIFACT_INVALID");
            saved.put(name, value);
        }
        return new SavedPredictionCalibration(mapper, binding, local, calibration, saved);
    }

    /** Deep copy of the exact bytes-verified load; never reopens a path. Server-internal use only. */
    public JsonNode savedArtifact(String name) {
        JsonNode value = saved.get(name);
        if (value == null) throw new IllegalArgumentException("SAVED_CALIBRATION_ARTIFACT_UNKNOWN");
        return value.deepCopy();
    }

    /** Only this reader can construct the opaque input; callers cannot replace its score or scope. */
    public static final class VerifiedInput {
        private final ObjectNode calibration;
        private final ObjectNode provenance;
        private final String rawScore;
        private VerifiedInput(ObjectNode calibration, ObjectNode provenance, String rawScore) {
            this.calibration = calibration.deepCopy(); this.provenance = provenance.deepCopy(); this.rawScore = rawScore;
        }
        ObjectNode compare(ObjectMapper mapper) {
            ObjectNode out = PredictionCalibration.evaluateStandard(mapper, calibration, rawScore);
            out.set("savedSource", provenance.deepCopy());
            out.put("compatibilityEvidenceSha256", provenance.path("compatibilityEvidenceSha256").asText());
            out.set("localAdoption", provenance.path("localAdoption").deepCopy());
            out.set("criteria", provenance.path("criteria").deepCopy());
            out.putArray("remainingApplicationConditions").add("SPLICING_MECHANISM_NOT_EVALUATED")
                    .add("DISEASE_APPLICABILITY_NOT_EVALUATED").add("APPLICATION_ADOPTION_NOT_EVALUATED");
            return out;
        }
        public ObjectNode provenance() { return provenance.deepCopy(); }
    }

    public VerifiedInput prepare(String candidateId) throws IOException {
        JsonNode e = binding.evidence(), parent = saved.get("parent"), child = saved.get("child"), request = saved.get("request");
        JsonNode job = saved.get("job"), candidates = saved.get("candidates");
        for (JsonNode run : List.of(parent, child)) {
            require(run.path("analysisId").equals(e.path("analysisId")) && "SYNTHETIC".equals(run.path("dataClass").asText()), "SAVED_CALIBRATION_ANALYSIS_OR_PURPOSE_MISMATCH");
            require(List.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(run.path("status").asText()), "SAVED_CALIBRATION_RUN_INCOMPLETE");
            require(run.path("executionSourceSha256").equals(e.path("executionSourceSha256")), "SAVED_CALIBRATION_EXECUTION_SOURCE_MISMATCH");
        }
        require(parent.path("runId").equals(e.path("parentRunId")) && child.path("runId").equals(e.path("childRunId"))
                && child.path("sourceRunId").equals(parent.path("runId")) && !parent.has("sourceRunId") && !parent.has("operation")
                && PredictionDevelopmentRuntime.DECLARATION.equals(parent.path("declarationVersion").asText())
                && SavedPredictionAssociation.DECLARATION.equals(child.path("declarationVersion").asText())
                && SavedPredictionAssociation.OPERATION.equals(child.path("operation").asText()), "SAVED_CALIBRATION_DIRECT_PARENT_REQUIRED");
        require(parent.path("requestSha256").equals(request.at("/association/sourceRequestSha256"))
                && parent.path("runId").equals(request.at("/association/sourceRunId"))
                && child.path("declarationVersion").equals(request.path("declarationVersion"))
                && "SYNTHETIC".equals(request.path("dataClass").asText()), "SAVED_CALIBRATION_REQUEST_REFERENCE_MISMATCH");
        require(job.path("id").equals(e.path("analysisId")) && candidates.path("analysisId").equals(e.path("analysisId"))
                && request.path("sampleId").equals(job.path("selectedSampleId"))
                && "GRCh38".equals(job.path("genomeAssembly").asText()) && "GRCh38".equals(job.path("analysisAssembly").asText())
                && job.path("liftoverApplied").isBoolean() && !job.path("liftoverApplied").asBoolean()
                && "NOT_APPLICABLE".equals(job.path("liftoverStatus").asText()), "SAVED_CALIBRATION_NATIVE_SOURCE_REQUIRED");
        JsonNode allowed = unique(e.path("allowedObservations"), "candidateId", candidateId);
        JsonNode p = prediction(child, candidateId), raw = prediction(parent, candidateId);
        digest(p, "resultId", "prediction-result-sha256-"); digest(p.path("plan"), "planId", "saved-association-plan-sha256-");
        digest(raw, "resultId", "prediction-result-sha256-"); digest(raw.path("plan"), "planId", "prediction-plan-sha256-");
        JsonNode observation = p.path("observation"); digest(observation, "observationSha256", "");
        require(observation.equals(raw.path("observation")), "SAVED_CALIBRATION_PARENT_OBSERVATION_CHANGED");
        require(p.path("resultId").equals(allowed.path("childResultId")) && raw.path("resultId").equals(allowed.path("parentResultId"))
                && observation.path("observationSha256").equals(allowed.path("observationSha256")), "SAVED_CALIBRATION_OBSERVATION_NOT_REGISTERED");
        require(p.at("/plan/parentReference").equals(request.path("association")) && p.path("selection").equals(p.at("/plan/selection"))
                && p.path("selection").equals(unique(request.at("/association/selections"), "candidateId", candidateId))
                && hashes.canonicalSha256(p.path("selection")).equals(allowed.path("selectionCanonicalSha256").asText()), "SAVED_CALIBRATION_SELECTION_REFERENCE_MISMATCH");
        require(candidateId.equals(p.at("/plan/candidateId").asText()) && candidateId.equals(raw.at("/plan/candidateId").asText())
                && p.at("/plan/executionSourceSha256").equals(e.path("executionSourceSha256"))
                && raw.at("/plan/executionSourceSha256").equals(e.path("executionSourceSha256")), "SAVED_CALIBRATION_CANDIDATE_SOURCE_MISMATCH");
        require("prediction-observation-v1".equals(observation.path("schemaVersion").asText())
                && "HIT".equals(observation.path("status").asText()) && "RAW_RECORDS_OBTAINED".equals(observation.path("reasonCode").asText())
                && observation.path("referenceVerified").isBoolean() && observation.path("referenceVerified").asBoolean()
                && "REAL_LOCAL_PRECOMPUTED_RESOURCE".equals(observation.path("scoreOrigin").asText())
                && observation.path("transportAttempts").isIntegralNumber() && observation.path("transportAttempts").asInt() == 1
                && !observation.hasNonNull("exceptionType") && "SYNTHETIC".equals(observation.path("dataClass").asText())
                && "OBSERVATION_ONLY".equals(raw.at("/plan/planMode").asText()) && raw.at("/plan/rawAcquisitionAllowed").asBoolean()
                && "2.0.0-development.3".equals(raw.at("/runtimeIdentity/version").asText())
                && raw.path("runtimeIdentity").equals(raw.at("/plan/runtimeIdentity")), "SAVED_CALIBRATION_COMPLETE_RAW_REQUIRED");
        Instant.parse(observation.path("observedAt").asText());
        require(local.identity().equals(observation.path("resource")) && local.identity().equals(raw.at("/plan/resources/ALPHAMISSENSE")), "SAVED_CALIBRATION_RESOURCE_MISMATCH");
        JsonNode allele = observation.path("queryAllele"); NormalizedVariantKey key = completeAllele(allele);
        require(local.registeredControls().contains(key), "SAVED_CALIBRATION_ALLELE_NOT_REGISTERED");
        JsonNode candidate = unique(candidates.path("variants"), "candidateId", candidateId);
        require(allele.equals(candidate.at("/observation/key")) && "SNV".equals(candidate.at("/observation/variantType").asText())
                && request.path("sampleId").equals(candidate.at("/observation/sampleId")) && allele.equals(p.at("/plan/queryAllele"))
                && allele.equals(raw.at("/plan/queryAllele")), "SAVED_CALIBRATION_ALLELE_MISMATCH");
        JsonNode frozen = unique(saved.get("selections").path("selections"), "candidateId", candidateId);
        JsonNode selection = p.path("selection"), tx = frozen.path("actualSavedAnnotation"), in = p.path("associationInput");
        require(selection.path("selectedAnnotationId").equals(frozen.at("/selected/annotationId"))
                && selection.path("annotationSourceSha256").equals(frozen.at("/selected/annotationSourceSha256"))
                && hashes.canonicalSha256(tx).equals(selection.path("annotationSourceSha256").asText())
                && selection.path("sourceObservationSha256").equals(observation.path("observationSha256")), "SAVED_CALIBRATION_ANNOTATION_REFERENCE_MISMATCH");
        require("VEP".equals(tx.path("source").asText()) && "COMPLETED".equals(tx.path("status").asText())
                && "missense_variant".equals(tx.path("consequence").asText()), "SAVED_CALIBRATION_VEP_MISSENSE_REQUIRED");
        int matches = 0; for (JsonNode annotation : candidate.path("transcriptAnnotations")) if (annotation.equals(tx)) matches++;
        require(matches == 1, "SAVED_CALIBRATION_SAVED_ANNOTATION_NOT_UNIQUE");
        // Normal exports include read-only aliases from getHgvsc/getHgvsp. Validate them against
        // their persisted fields before omitting only these aliases from the typed read.
        // The complete original annotation, including aliases, remains covered by the digest above.
        ObjectNode typedFields = ((ObjectNode) tx).deepCopy();
        for (var alias : Map.of("hgvsc", "hgvsCoding", "hgvsp", "hgvsProtein").entrySet()) {
            if (typedFields.has(alias.getKey())) {
                require(typedFields.path(alias.getKey()).equals(typedFields.path(alias.getValue())), "SAVED_CALIBRATION_HGVS_ALIAS_CONFLICT");
                typedFields.remove(alias.getKey());
            }
        }
        TranscriptConsequence typed = mapper.treeToValue(typedFields, TranscriptConsequence.class);
        if (typed == null) throw new IllegalArgumentException("SAVED_CALIBRATION_ANNOTATION_MISSING");
        String transcript = typed.getVersionedTranscriptId(), position = tx.path("proteinPosition").asText(), aa = tx.path("aminoAcids").asText();
        if (transcript == null) throw new IllegalArgumentException("SAVED_CALIBRATION_PROTEIN_IDENTITY_INVALID");
        require(transcript.matches("ENST[0-9]+\\.[0-9]+") && position.matches("[1-9][0-9]*")
                && aa.matches("[ACDEFGHIKLMNPQRSTVWY]/[ACDEFGHIKLMNPQRSTVWY]") && aa.charAt(0) != aa.charAt(2), "SAVED_CALIBRATION_PROTEIN_IDENTITY_INVALID");
        require(transcript.equals(selection.path("versionedTranscriptId").asText()) && transcript.equals(frozen.at("/selected/versionedTranscriptId").asText())
                && transcript.equals(in.path("transcriptId").asText()) && position.equals(in.path("proteinPosition").asText()) && aa.equals(in.path("aminoAcids").asText())
                && in.path("queryAllele").equals(allele) && in.path("annotationSourceSha256").equals(selection.path("annotationSourceSha256"))
                && in.path("executionSourceSha256").equals(e.path("executionSourceSha256")) && in.path("annotationSource").equals(tx.path("source"))
                && in.path("annotationSourceVersion").equals(tx.path("version")), "SAVED_CALIBRATION_ASSOCIATION_INPUT_MISMATCH");
        require("EXACT_VERSIONED_TRANSCRIPT".equals(in.path("route").asText()) && "ASSOCIATED".equals(p.at("/association/status").asText())
                && mapper.createArrayNode().add(0).equals(p.at("/association/matchedRowIndices")), "SAVED_CALIBRATION_EXACT_ROUTE_REQUIRED");
        require(observation.path("rows").isArray() && observation.path("rows").size() == 1, "EXACT_SINGLE_ROW_OUTSIDE_SCOPE");
        JsonNode header = local.savedVerification().path("rawHeader");
        require(header.isArray() && !header.isEmpty() && "#CHROM\tPOS\tREF\tALT\tgenome\tuniprot_id\ttranscript_id\tprotein_variant\tam_pathogenicity\tam_class".equals(header.get(header.size() - 1).asText()), "SAVED_CALIBRATION_HEADER_MISMATCH");
        JsonNode row = observation.path("rows").get(0); String[] columns = SavedPredictionAssociation.validateRawRow(row, allele);
        require(!columns[5].isBlank() && columns[6].equals(transcript) && columns[7].equals(aa.charAt(0) + position + aa.charAt(2))
                && row.path("rawScore").equals(p.at("/association/rawScore")) && PredictionCalibration.decimal(columns[8]) != null, "SAVED_CALIBRATION_RAW_SCORE_OR_PROTEIN_MISMATCH");
        for (String code : List.of("PP3", "BP4")) require("NOT_EVALUATED".equals(p.path("criteria").path(code).path("status").asText())
                && "NONE".equals(p.path("criteria").path(code).path("appliedStrength").asText()), "SAVED_CALIBRATION_APPLICATION_MUST_REMAIN_UNEVALUATED");
        var provenance = mapper.createObjectNode().put("analysisId", e.path("analysisId").asText()).put("parentRunId", parent.path("runId").asText())
                .put("childRunId", child.path("runId").asText()).put("candidateId", candidateId).put("childResultId", p.path("resultId").asText())
                .put("observationSha256", observation.path("observationSha256").asText()).put("observedAt", observation.path("observedAt").asText())
                .put("sourceRowSha256", PredictionCalibration.sha256(row.path("sourceRow").asText().getBytes(StandardCharsets.UTF_8)))
                .put("compatibilityEvidenceSha256", binding.sha256()).put("calibrationCanonicalSha256", hashes.canonicalSha256(calibration));
        provenance.set("inputArtifacts", e.path("inputArtifacts").deepCopy()); provenance.set("localAdoption", e.path("localAdoption").deepCopy());
        provenance.set("selection", selection.deepCopy()); provenance.set("associationInput", in.deepCopy()); provenance.set("criteria", p.path("criteria").deepCopy());
        provenance.set("resource", observation.path("resource").deepCopy());
        provenance.putObject("nativeSource").put("jobArtifactSha256", e.at("/inputArtifacts/job").asText())
                .put("originalAssembly", job.path("genomeAssembly").asText()).put("analysisAssembly", job.path("analysisAssembly").asText())
                .put("liftoverApplied", job.path("liftoverApplied").asBoolean());
        return new VerifiedInput(calibration, provenance, columns[8]);
    }

    private JsonNode prediction(JsonNode run, String candidate) {
        JsonNode item = unique(run.path("candidates"), "candidateId", candidate);
        require(item.path("attempts").isArray() && item.path("attempts").size() == 1, "SAVED_CALIBRATION_ATTEMPT_NOT_UNIQUE");
        return item.at("/attempts/0/prediction");
    }
    private static JsonNode unique(JsonNode array, String key, String value) {
        JsonNode found = null;
        require(array.isArray(), "SAVED_CALIBRATION_REFERENCE_LIST_MISSING");
        for (JsonNode item : array) if (value.equals(item.path(key).asText())) {
            require(found == null, "SAVED_CALIBRATION_REFERENCE_NOT_UNIQUE"); found = item;
        }
        if (found == null) throw new IllegalArgumentException("SAVED_CALIBRATION_REFERENCE_MISSING"); return found;
    }
    private void digest(JsonNode node, String field, String prefix) {
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("SAVED_CALIBRATION_OBJECT_MISSING");
        ObjectNode value = object.deepCopy(); value.remove(field);
        require((prefix + hashes.canonicalSha256(value)).equals(node.path(field).asText()), "SAVED_CALIBRATION_DIGEST_MISMATCH_" + field);
    }
    private static NormalizedVariantKey completeAllele(JsonNode allele) {
        require("GRCh38".equals(allele.path("genomeAssembly").asText()) && !allele.path("chromosome").asText().isBlank()
                && allele.path("position").isIntegralNumber() && allele.path("position").canConvertToInt() && allele.path("position").asInt() > 0
                && allele.path("reference").asText().matches("[ACGT]") && allele.path("alternate").asText().matches("[ACGT]")
                && !allele.path("reference").equals(allele.path("alternate")), "SAVED_CALIBRATION_COMPLETE_ALLELE_REQUIRED");
        return new NormalizedVariantKey("GRCh38", allele.path("chromosome").asText(), allele.path("position").asInt(), allele.path("reference").asText(), allele.path("alternate").asText());
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
