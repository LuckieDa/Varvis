package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.function.Function;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Per-batch generic protein route. Only verified material can enter the public preparation API. */
public final class GeneralRevelRuntime {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode policy;
    private final ObjectNode resource;
    private final Function<ObjectNode, ObjectNode> lookup;
    private final boolean synthetic;
    @Nullable private PredictionSpecializationGuard specializationGuard;
    private int calls;

    public static GeneralRevelRuntime configured(ObjectMapper mapper, RevelPublisherIndexObservationProvider provider, PredictionSpecializationGuard guard) {
        var runtime = new GeneralRevelRuntime(mapper, provider.resourceBinding(), provider.withBatchFactReuse()::lookupSnapshot, false);
        runtime.specializationGuard = Objects.requireNonNull(guard); return runtime;
    }

    /** Test seam is restricted to synthetic contexts. Production only accepts the pinned local publisher. */
    GeneralRevelRuntime(ObjectMapper mapper, ObjectNode resource, Function<ObjectNode, ObjectNode> lookup, boolean synthetic) {
        this.mapper = Objects.requireNonNull(mapper); adapter = new RuntimeEvidenceV2Adapter(mapper);
        policy = GeneralRevelPolicy.load(mapper); this.resource = resource.deepCopy();
        this.lookup = Objects.requireNonNull(lookup); this.synthetic = synthetic;
        if (!synthetic && !GeneralRevelPolicy.sameResource(policy.path("resourceBinding"), resource))
            throw new IllegalArgumentException("GENERAL_REVEL_CALIBRATION_RESOURCE_MISMATCH");
    }

    public int providerCalls() { return calls; }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("runtime", "GENERAL_LOCAL_REVEL").put("version", GeneralRevelPolicy.VERSION)
                .put("policySha256", adapter.canonicalSha256(policy)).put("synthetic", synthetic);
        out.put("rawFactReuseVersion", PredictionBatchFactCache.VERSION);
        out.set("resourceBinding", resource.deepCopy());
        if (specializationGuard != null) out.set("specializationCatalog", specializationGuard.identity());
        return out;
    }

    public final class Prepared {
        private final ObjectNode plan;
        @Nullable private final TranscriptConsequence transcript;
        @Nullable private ObjectNode result;
        private Prepared(ObjectNode plan, @Nullable TranscriptConsequence transcript) {
            this.plan = plan.deepCopy();
            this.transcript = transcript; // Coding/protein fields are immutable in TranscriptConsequence.
        }
        public ObjectNode snapshot() { return plan.deepCopy(); }
        private GeneralRevelRuntime owner() { return GeneralRevelRuntime.this; }
    }

    public Prepared prepare(ObjectNode context, VariantInterpretation candidate, @Nullable CodingTranscriptMaterialSupply.Material material) {
        return prepare(context, candidate, material, null);
    }

    Prepared prepare(ObjectNode context, VariantInterpretation candidate, @Nullable CodingTranscriptMaterialSupply.Material material,
            @Nullable ObjectNode publishedSelection) {
        return prepareModel(context, candidate, material == null ? null : material.model(),
                material != null && material.ready() ? null : "GENERAL_REVEL_VERIFIED_TRANSCRIPT_MATERIAL_REQUIRED", publishedSelection);
    }

    Prepared prepareSynthetic(ObjectNode context, VariantInterpretation candidate, ObjectNode model) {
        if (!synthetic) throw new IllegalStateException("GENERAL_REVEL_SYNTHETIC_SEAM_DISABLED");
        return prepareModel(context, candidate, model, null, null);
    }

    private Prepared prepareModel(ObjectNode context, VariantInterpretation candidate, @Nullable ObjectNode model, @Nullable String inputIssue,
            @Nullable ObjectNode publishedSelection) {
        var plan = identity().put("status", "PREPARED").put("preselectedModel", "REVEL").put("fallbackAfterScore", false)
                .put("specSearchScope", policy.path("specSearchScope").asText());
        if (publishedSelection == null) plan.set("policy", policy.deepCopy());
        else plan.set("publishedSelection", publishedSelection.deepCopy());
        plan.set("context", context.deepCopy());
        String issue = inputIssue; TranscriptConsequence tx = null;
        try {
            adapter.validateCompleteContext(context);
            if (synthetic && !"SYNTHETIC".equals(context.path("dataClass").asText())) issue = "GENERAL_REVEL_SYNTHETIC_INPUT_ONLY";
            if (issue == null && publishedSelection == null) issue = GeneralRevelPolicy.specialIssue(mapper, context);
            if (issue == null && !synthetic && publishedSelection == null) {
                var guard = specializationGuard;
                if (guard == null) issue = "PREDICTION_SPEC_CATALOG_REQUIRED";
                else {
                    var selection = guard.checkPredictions(context); plan.set("specializationSelection", selection);
                    if ("BLOCKED".equals(selection.path("status").asText())) issue = selection.path("reasonCode").asText();
                }
            }
            tx = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (issue == null && tx == null) issue = "PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE";
            if (issue == null && model != null) {
                if (!model.path("transcript").asText().matches("ENST[0-9]+\\.[1-9][0-9]*")) issue = "GENERAL_REVEL_EXACT_ENST_REQUIRED";
                if (issue == null) issue = CodingMissenseResolver.resolve(context, tx, model, mapper.createObjectNode(), adapter);
                var key = candidate.getObservation() == null ? null : candidate.getObservation().getKey();
                var allele = context.at("/variant/evidenceQueryAllele");
                if (issue == null && (key == null || !key.getGenomeAssembly().equals(allele.path("assembly").asText())
                        || !key.getChromosome().equals(allele.path("contig").asText()) || key.getPosition() != allele.path("position").asInt()
                        || !key.getReference().equals(allele.path("reference").asText()) || !key.getAlternate().equals(allele.path("alternate").asText())))
                    issue = "GENERAL_REVEL_CANDIDATE_ALLELE_MISMATCH";
                if (issue == null) {
                    var request = plan.putObject("request").put("schemaVersion", "reviewed-prediction-observation-request-v1").put("preselectedModel", "REVEL");
                    request.set("context", context.deepCopy()); request.set("resourceBinding", resource.deepCopy());
                    request.set("transcriptModel", model.deepCopy());
                    request.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                            .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", adapter.canonicalSha256(model));
                    var bridge = request.putObject("revelTranscriptBinding").put("accession", context.at("/transcript/accession").asText())
                            .put("status", GeneralRevelPolicy.BRIDGE);
                    bridge.putArray("sourceIds").add("CURRENT_QUALIFIED_REFERENCE_AND_EXACT_TRANSCRIPT");
                    request.put("lookupId", "prediction-lookup-sha256-" + adapter.canonicalSha256(request));
                }
            }
        } catch (RuntimeException invalid) { issue = "GENERAL_REVEL_CONTEXT_INVALID"; }
        if (issue != null) plan.put("status", "BLOCKED").put("reasonCode", issue);
        return new Prepared(plan, tx);
    }

    public ObjectNode execute(Prepared prepared) {
        if (prepared.owner() != this) throw new IllegalArgumentException("GENERAL_REVEL_PLAN_OWNER_MISMATCH");
        if (prepared.result != null) return prepared.result.deepCopy();
        var plan = prepared.snapshot(); var out = mapper.createObjectNode(); out.set("plan", plan);
        var protein = out.putObject("protein").put("status", "NOT_EVALUATED").put("appliedStrength", "NONE");
        String issue = "BLOCKED".equals(plan.path("status").asText()) ? plan.path("reasonCode").asText() : null;
        if (issue == null && Thread.currentThread().isInterrupted()) issue = "GENERAL_REVEL_INTERRUPTED";
        if (issue == null) {
            try {
                var request = (ObjectNode) plan.path("request"); calls++;
                var observation = Objects.requireNonNull(lookup.apply(request.deepCopy())).deepCopy(); out.set("observation", observation);
                if (!request.path("context").equals(observation.path("context"))
                        || !request.path("transcriptModel").equals(observation.path("transcriptModel"))) issue = "GENERAL_REVEL_OBSERVATION_IDENTITY_MISMATCH";
                if (issue == null && !"HIT".equals(observation.path("status").asText()))
                    issue = "NO_RECORD".equals(observation.path("status").asText()) ? "REVEL_NO_RECORD" : observation.path("reasonCode").asText("REVEL_RESOURCE_NOT_READY");
                if (issue == null) {
                    var resolved = out.putObject("resolvedInput");
                    var input = RevelPredictionObservation.read(plan.path("context"), prepared.transcript, observation, request, resolved, adapter);
                    issue = input.issue();
                    if (issue == null && input.score() != null) {
                        issue = isoformIssue(observation, request, input.score());
                        if (issue == null) out.set("protein", plan.has("publishedSelection")
                                ? publishedCalibration(mapper, plan.at("/publishedSelection/selected/revelThresholds"), input.score())
                                : GeneralRevelPolicy.calibrate(mapper, policy, input.score().toPlainString()));
                    }
                }
            } catch (RuntimeException unavailable) { issue = "GENERAL_REVEL_LOOKUP_OR_IDENTITY_FAILURE"; }
        }
        if (issue != null) protein.put("reasonCode", issue);
        prepared.result = out.deepCopy(); return out;
    }

    static ObjectNode publishedCalibration(ObjectMapper mapper, JsonNode thresholds, java.math.BigDecimal score) {
        var pp3Minimum = PredictionCalibration.decimal(thresholds.path("pp3Minimum").asText());
        var bp4Maximum = PredictionCalibration.decimal(thresholds.path("bp4Maximum").asText());
        if (pp3Minimum == null || bp4Maximum == null || bp4Maximum.compareTo(pp3Minimum) >= 0
                || score.signum() < 0 || score.compareTo(java.math.BigDecimal.ONE) > 0)
            return mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "PREDICTION_PUBLISHED_SCORE_OR_THRESHOLD_INVALID");
        boolean harmful = score.compareTo(pp3Minimum) >= 0;
        boolean benign = score.compareTo(bp4Maximum) <= 0;
        String strength = harmful || benign ? "SUPPORTING" : "NONE";
        var result = mapper.createObjectNode().put("status", "CALIBRATED").put("rawScore", score.toPlainString())
                .put("direction", harmful ? "HARMFUL" : benign ? "BENIGN" : "UNINFORMATIVE")
                .put("appliedStrength", strength).put("calibratedStrength", strength)
                .put("reasonCode", "PREDICTION_PUBLISHED_REVEL_INTERVAL");
        result.putObject("band").put("lower", harmful ? pp3Minimum.toPlainString() : benign ? "0" : bp4Maximum.toPlainString())
                .put("upper", harmful ? "1" : benign ? bp4Maximum.toPlainString() : pp3Minimum.toPlainString())
                .put("lowerInclusive", harmful || benign).put("upperInclusive", harmful || benign);
        return result;
    }

    /** Agreement makes selected/maximum/isoform aggregation immaterial; never silently pick the highest. */
    private String isoformIssue(JsonNode observation, JsonNode request, java.math.BigDecimal score) {
        var rows = observation.path("coordinateRecords"); var allele = request.at("/context/variant/evidenceQueryAllele");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 1000) return "REVEL_ALL_ISOFORMS_UNVERIFIED";
        for (var row : rows) {
            String[] cells = row.path("rawCsv").asText().split(",", -1);
            if (cells.length != 9 || !cells[0].equals(allele.path("contig").asText()) || !cells[2].equals(allele.path("position").asText()))
                return "REVEL_ALL_ISOFORMS_UNVERIFIED";
            if (!cells[3].equals(allele.path("reference").asText()) || !cells[4].equals(allele.path("alternate").asText())) continue;
            var value = PredictionCalibration.decimal(cells[7]);
            if (value == null || score.compareTo(value) != 0) return "REVEL_ISOFORM_SCORE_CONFLICT";
        }
        return null;
    }
}
