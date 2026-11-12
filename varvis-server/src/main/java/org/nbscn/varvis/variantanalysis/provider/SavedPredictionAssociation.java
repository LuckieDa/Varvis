package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.AcmgSubmission;

/** Pure derivation from a business-read-verified parent and persisted VEP selection.
 * Has no lookup, file import, calibration or resource-query capability. */
public final class SavedPredictionAssociation {
    public static final String DECLARATION = "ordinary-pp3-bp4-development-v2";
    public static final String OPERATION = "ASSOCIATE_SAVED_OBSERVATIONS";
    public static final String VERSION = "saved-prediction-association-v1";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final JsonNode resource;

    public SavedPredictionAssociation(ObjectMapper mapper, JsonNode resource) {
        // Match AcmgRecommendations' persisted-annotation identity (ISO dates, not fractional timestamps).
        this.mapper = mapper.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        adapter = new RuntimeEvidenceV2Adapter(this.mapper); this.resource = resource.deepCopy();
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-business-binding-v1")
                .put("status", "CONFIGURED").put("version", VERSION).put("associationAlgorithm", PredictionObservationAssociation.VERSION)
                .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false)
                .put("formalEnabled", false).put("syntheticGridOnly", false).put("newScientificAcquisitionAllowed", false)
                .put("resourceSha256", adapter.canonicalSha256(resource));
        out.set("enabledCriteria", mapper.valueToTree(PredictionDevelopmentRuntime.CRITERIA)); return out;
    }
    public static boolean eligible(VariantInterpretation candidate, TranscriptConsequence tx) {
        return ReviewedPredictionContextPreparation.eligibleAnnotation(candidate, tx)
                && tx.getProteinPosition() != null && tx.getProteinPosition().matches("[1-9][0-9]*")
                && tx.getAminoAcids() != null && tx.getAminoAcids().matches("[A-Z*]/[A-Z*]")
                && tx.getGeneId() != null && !tx.getGeneId().isBlank() && tx.getAminoAcidChange() != null;
    }
    public JsonNode options(VariantInterpretation candidate, JsonNode recommendation) {
        var out = mapper.createArrayNode();
        for (var option : recommendation.path("annotations")) {
            int index = option.path("annotationIndex").asInt(-1);
            var annotations = candidate.getTranscriptAnnotations();
            if (index >= 0 && index < annotations.size() && option.path("predictionContextReady").asBoolean()
                    && eligible(candidate, annotations.get(index))) out.add(option.deepCopy());
        }
        return out;
    }
    public final class Plan {
        private final ObjectNode snapshot;
        private final ObjectNode observation;
        private final TranscriptConsequence transcript;
        private Plan(ObjectNode snapshot, ObjectNode observation, TranscriptConsequence transcript) {
            this.snapshot = snapshot.deepCopy(); this.observation = observation.deepCopy();
            this.transcript = mapper.convertValue(transcript, TranscriptConsequence.class);
        }
        public ObjectNode snapshot() { return snapshot.deepCopy(); }
        private SavedPredictionAssociation owner() { return SavedPredictionAssociation.this; }
    }
    public Plan prepare(JsonNode parent, AcmgSubmission request, AcmgSubmission.AnnotationSelection selection,
                        VariantInterpretation candidate, JsonNode options, String sourceSha) {
        var reference = request.association();
        require(reference != null, "SAVED_OBSERVATION_REFERENCE_REQUIRED");
        if (reference == null || selection == null) throw new IllegalArgumentException("SAVED_OBSERVATION_SELECTION_REQUIRED");
        require(Set.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(parent.path("status").asText())
                && PredictionDevelopmentRuntime.DECLARATION.equals(parent.path("declarationVersion").asText())
                && !parent.has("operation") && !parent.has("sourceRunId"), "SAVED_OBSERVATION_DIRECT_PARENT_REQUIRED");
        require(parent.path("runId").asText().equals(reference.sourceRunId())
                && parent.path("requestSha256").asText().equals(reference.sourceRequestSha256()), "SAVED_OBSERVATION_PARENT_IDENTITY_MISMATCH");
        require(sourceSha.equals(reference.executionSourceSha256())
                && sourceSha.equals(parent.path("executionSourceSha256").asText()), "SAVED_OBSERVATION_EXECUTION_SOURCE_MISMATCH");
        require(request.dataClass().equals(parent.path("dataClass").asText()), "SAVED_OBSERVATION_PURPOSE_MISMATCH");
        require(Objects.equals(candidate.getCandidateId(), selection.candidateId()), "SAVED_OBSERVATION_CANDIDATE_MISMATCH");
        require(Set.of("USER_EXPLICIT", "DEVELOPER_PUBLIC_CONTROL").contains(Objects.toString(selection.selectionOrigin(), "")), "ASSOCIATION_SELECTION_ORIGIN_REQUIRED");
        require(!"DEVELOPER_PUBLIC_CONTROL".equals(selection.selectionOrigin()) || "SYNTHETIC".equals(request.dataClass()), "ASSOCIATION_SELECTION_PURPOSE_MISMATCH");
        var found = new ArrayList<JsonNode>();
        for (var row : parent.path("candidates")) if (selection.candidateId().equals(row.path("candidateId").asText()))
            for (var attempt : row.path("attempts")) {
                var prediction = attempt.path("prediction");
                if (Objects.equals(selection.sourceObservationSha256(), prediction.at("/observation/observationSha256").asText())) found.add(prediction);
            }
        require(found.size() == 1, "SAVED_OBSERVATION_REFERENCE_NOT_UNIQUE");
        var prediction = found.get(0); var node = prediction.path("observation");
        if (!(node instanceof ObjectNode observation)) throw new IllegalArgumentException("SAVED_OBSERVATION_MISSING");
        var unhashed = observation.deepCopy(); unhashed.remove("observationSha256");
        require(adapter.canonicalSha256(unhashed).equals(selection.sourceObservationSha256()), "SAVED_OBSERVATION_DIGEST_MISMATCH");
        require("HIT".equals(observation.path("status").asText())
                && "prediction-observation-v1".equals(observation.path("schemaVersion").asText())
                && "REAL_LOCAL_PRECOMPUTED_RESOURCE".equals(observation.path("scoreOrigin").asText())
                && observation.path("transportAttempts").asInt() == 1 && observation.path("referenceVerified").asBoolean()
                && observation.path("observedAt").isTextual(), "SAVED_OBSERVATION_DIRECT_RAW_REQUIRED");
        require(resource.equals(observation.path("resource")) && resource.equals(prediction.at("/plan/resources/ALPHAMISSENSE"))
                && PredictionResourceCompatibility.acquisitionIssue(resource) == null, "SAVED_OBSERVATION_RESOURCE_OR_USE_MISMATCH");
        require(request.dataClass().equals(observation.path("dataClass").asText())
                && request.dataClass().equals(prediction.at("/plan/dataClass").asText()), "SAVED_OBSERVATION_PURPOSE_MISMATCH");
        var source = candidate.getObservation();
        if (source == null) throw new IllegalArgumentException("SAVED_OBSERVATION_CANDIDATE_ALLELE_MISSING");
        var allele = mapper.valueToTree(source.getKey());
        require(allele.equals(observation.path("queryAllele")) && allele.equals(prediction.at("/plan/queryAllele"))
                && selection.candidateId().equals(prediction.at("/plan/candidateId").asText())
                && sourceSha.equals(prediction.at("/plan/executionSourceSha256").asText()), "SAVED_OBSERVATION_ALLELE_OR_SOURCE_MISMATCH");
        require(observation.path("rows").isArray() && !observation.path("rows").isEmpty(), "SAVED_OBSERVATION_ROWS_MISSING");
        for (var row : observation.path("rows")) {
            String[] columns = validateRawRow(row, allele);
            // Preserve the historical association contract's narrower genome spelling.
            require("hg38".equals(columns[4]), "SAVED_OBSERVATION_ROW_IDENTITY_MISMATCH");
        }
        var selected = new ArrayList<JsonNode>();
        for (var option : options) if (Objects.equals(selection.selectedAnnotationId(), option.path("annotationId").asText())) selected.add(option);
        require(selected.size() == 1, "ASSOCIATION_ANNOTATION_SELECTION_NOT_UNIQUE");
        var option = selected.get(0);
        require(option.path("annotationSourceSha256").asText().equals(selection.annotationSourceSha256())
                && option.path("versionedTranscriptId").asText().equals(selection.versionedTranscriptId()), "ASSOCIATION_ANNOTATION_REFERENCE_MISMATCH");
        int index = option.path("annotationIndex").asInt(-1); var annotations = candidate.getTranscriptAnnotations();
        require(index >= 0 && index < annotations.size(), "ASSOCIATION_ANNOTATION_INDEX_INVALID");
        var tx = annotations.get(index);
        require(eligible(candidate, tx) && new AcmgBusinessArtifacts(mapper).requestHash(mapper.valueToTree(tx)).equals(selection.annotationSourceSha256())
                && selection.versionedTranscriptId().equals(tx.getVersionedTranscriptId()), "ASSOCIATION_PERSISTED_ANNOTATION_MISMATCH");
        require(annotations.stream().filter(Objects::nonNull).filter(a -> Objects.equals(tx.getVersionedTranscriptId(), a.getVersionedTranscriptId())
                && Objects.equals(tx.getGeneSymbol(), a.getGeneSymbol()) && Objects.equals(tx.getSource(), a.getSource())
                && Objects.equals(tx.getVersion(), a.getVersion())).count() == 1, "ASSOCIATION_ANNOTATION_IDENTITY_CONFLICT");
        var plan = mapper.createObjectNode().put("schemaVersion", "saved-prediction-association-plan-v1")
                .put("operation", OPERATION).put("candidateId", candidate.getCandidateId()).putNull("hypothesisId")
                .put("dataClass", request.dataClass()).put("executionSourceSha256", sourceSha)
                .put("model", "ALPHAMISSENSE").put("primaryMechanism", "PROTEIN_MISSENSE");
        plan.set("parentReference", mapper.valueToTree(reference)); plan.set("selection", mapper.valueToTree(selection));
        plan.set("runtimeIdentity", identity()); plan.set("queryAllele", allele);
        plan.put("planId", "saved-association-plan-sha256-" + adapter.canonicalSha256(plan));
        return new Plan(plan, observation, tx);
    }
    public ObjectNode execute(Plan plan) {
        require(plan.owner() == this, "ASSOCIATION_PLAN_OWNER_MISMATCH");
        var out = mapper.createObjectNode().put("schemaVersion", "prediction-rule-result-v2")
                .put("operation", OPERATION).put("associatedAt", Instant.now().toString()).put("associationAlgorithm", PredictionObservationAssociation.VERSION)
                .put("providerCalls", 0).put("newProviderInvocations", 0).put("newTransportAttempts", 0)
                .put("sourceObservationsRead", 1).put("associationEvaluations", 1).put("calibratedMechanisms", 0)
                .put("formalEnabled", false).put("classificationEnabled", false);
        var snapshot = plan.snapshot(); out.set("plan", snapshot); out.set("runtimeIdentity", identity());
        out.set("observation", plan.observation.deepCopy()); out.set("selection", snapshot.path("selection").deepCopy());
        var input = out.putObject("associationInput").put("schemaVersion", "prediction-association-input-v1")
                .put("transcriptId", plan.transcript.getVersionedTranscriptId()).put("proteinPosition", plan.transcript.getProteinPosition())
                .put("aminoAcids", plan.transcript.getAminoAcids()).put("route", "EXACT_VERSIONED_TRANSCRIPT")
                .put("executionSourceSha256", snapshot.path("executionSourceSha256").asText())
                .put("annotationSourceSha256", snapshot.at("/selection/annotationSourceSha256").asText())
                .put("annotationSource", plan.transcript.getSource()).put("annotationSourceVersion", plan.transcript.getVersion());
        input.set("queryAllele", snapshot.path("queryAllele").deepCopy());
        var association = PredictionObservationAssociation.alpha(mapper, plan.observation, plan.transcript, mapper.nullNode());
        out.set("association", association); boolean associated = "ASSOCIATED".equals(association.path("status").asText());
        out.putObject("calibration").put("comparisonSemantics", "UNVERIFIED_NOT_EXECUTED");
        var mechanisms = out.putObject("mechanisms");
        mechanisms.putObject("PROTEIN_MISSENSE").put("status", "NOT_EVALUATED").put("reasonCode", "CALIBRATION_RESOURCE_COMPATIBILITY_UNVERIFIED");
        mechanisms.putObject("SPLICING").put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_EXECUTION_NOT_AUTHORIZED");
        out.putObject("effectRestrictions").put("status", "UNAVAILABLE").put("reasonCode", "SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE");
        var criteria = out.putObject("criteria");
        for (String code : List.of("PP3", "BP4")) criteria.putObject(code).put("criterionId", "ACMG_AMP_2015:" + code)
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", "SPEC_APPLICABILITY_UNRESOLVED_CONTEXT_MISSING");
        out.putObject("stages").put("rawLookupReady", true).put("associationReady", associated)
                .put("calibrationReady", false).put("applicationReady", false).put("firstUnresolvedStage", associated ? "CALIBRATION" : "ASSOCIATION");
        out.put("resultId", "prediction-result-sha256-" + adapter.canonicalSha256(out)); return out;
    }
    /** Shared pure raw/derived identity check. No lookup, reference access or score selection. */
    static String[] validateRawRow(JsonNode row, JsonNode allele) {
        String[] columns = row.path("sourceRow").asText().split("\t", -1);
        require(columns.length == 10, "SAVED_OBSERVATION_ROW_INVALID");
        require(columns[0].replaceFirst("^chr", "").equals(allele.path("chromosome").asText())
                && columns[1].equals(allele.path("position").asText()) && columns[2].equals(allele.path("reference").asText())
                && columns[3].equals(allele.path("alternate").asText()) && Set.of("hg38", "GRCh38").contains(columns[4])
                && columns[5].equals(row.path("uniprotId").asText()) && columns[6].equals(row.path("transcriptId").asText())
                && columns[7].equals(row.path("proteinVariant").asText()) && row.path("rawScore").isTextual()
                && columns[8].equals(row.path("rawScore").asText()) && columns[9].equals(row.path("rawClass").asText()), "SAVED_OBSERVATION_ROW_IDENTITY_MISMATCH");
        return columns;
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
