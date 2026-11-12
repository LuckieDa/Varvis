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
public final class GeneralAlphaMissenseRuntime {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode policy;
    private final ObjectNode resource;
    private final Function<ObjectNode, ObjectNode> lookup;
    private final boolean synthetic;
    @Nullable private PredictionSpecializationGuard specializationGuard;
    private int calls;
    private final PredictionBatchFactCache facts = new PredictionBatchFactCache();
    private Function<ObjectNode, String> resourceCheck = request -> null;

    @Nullable private GencodeTranscriptIndex sourceIndex;
    @Nullable private CodingTranscriptMaterialSupply.ReferenceReader reference;
    public static GeneralAlphaMissenseRuntime configured(ObjectMapper mapper, AlphaMissenseProvider provider,
            PredictionSpecializationGuard guard, @Nullable GencodeTranscriptIndex sourceIndex,
            @Nullable CodingTranscriptMaterialSupply.ReferenceReader reference) {
        var resource = provider.rawResourceIdentity(mapper);
        var runtime = new GeneralAlphaMissenseRuntime(mapper, resource, request -> {
            var a = request.at("/context/variant/evidenceQueryAllele");
            var key = new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey(a.path("assembly").asText(),
                    a.path("contig").asText(), a.path("position").asInt(), a.path("reference").asText(), a.path("alternate").asText());
            return provider.lookupGeneralRecords(mapper, key, resource, request.at("/context/dataClass").asText());
        }, false);
        runtime.resourceCheck = request -> provider.generalReuseIssue(request, resource);
        runtime.specializationGuard = Objects.requireNonNull(guard);
        runtime.sourceIndex = sourceIndex; runtime.reference = reference; return runtime;
    }

    /** Test seam is restricted to synthetic contexts. Production only accepts the pinned local publisher. */
    GeneralAlphaMissenseRuntime(ObjectMapper mapper, ObjectNode resource, Function<ObjectNode, ObjectNode> lookup, boolean synthetic) {
        this.mapper = Objects.requireNonNull(mapper); adapter = new RuntimeEvidenceV2Adapter(mapper);
        policy = GeneralAlphaMissensePolicy.load(mapper); this.resource = resource.deepCopy();
        this.lookup = Objects.requireNonNull(lookup); this.synthetic = synthetic;
        if (!synthetic && !GeneralAlphaMissensePolicy.accepts(resource))
            throw new IllegalArgumentException("GENERAL_ALPHAMISSENSE_CALIBRATION_RESOURCE_MISMATCH");
    }

    public int providerCalls() { return calls; }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("runtime", "GENERAL_LOCAL_ALPHAMISSENSE").put("version", GeneralAlphaMissensePolicy.VERSION)
                .put("policySha256", adapter.canonicalSha256(policy)).put("synthetic", synthetic);
        out.put("rawFactReuseVersion", PredictionBatchFactCache.VERSION);
        out.set("resourceBinding", resource.deepCopy());
        if (specializationGuard != null) out.set("specializationCatalog", specializationGuard.identity());
        if (sourceIndex != null) out.set("sourceTranscriptIndex", sourceIndex.identity());
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
        private GeneralAlphaMissenseRuntime owner() { return GeneralAlphaMissenseRuntime.this; }
    }

    public Prepared prepare(ObjectNode context, VariantInterpretation candidate, @Nullable CodingTranscriptMaterialSupply.Material material) {
        return prepareModel(context, candidate, material == null ? null : material.model(),
                material != null && material.ready() ? null : "GENERAL_ALPHAMISSENSE_VERIFIED_TRANSCRIPT_MATERIAL_REQUIRED");
    }

    Prepared prepareSynthetic(ObjectNode context, VariantInterpretation candidate, ObjectNode model) {
        if (!synthetic) throw new IllegalStateException("GENERAL_ALPHAMISSENSE_SYNTHETIC_SEAM_DISABLED");
        return prepareModel(context, candidate, model, null);
    }

    private Prepared prepareModel(ObjectNode context, VariantInterpretation candidate, @Nullable ObjectNode model, @Nullable String inputIssue) {
        var plan = identity().put("status", "PREPARED").put("preselectedModel", "ALPHAMISSENSE").put("fallbackAfterScore", false)
                .put("specSearchScope", policy.path("specSearchScope").asText());
        plan.set("policy", policy.deepCopy()); plan.set("context", context.deepCopy());
        String issue = inputIssue; TranscriptConsequence tx = null;
        try {
            adapter.validateCompleteContext(context);
            if (synthetic && !"SYNTHETIC".equals(context.path("dataClass").asText())) issue = "GENERAL_ALPHAMISSENSE_SYNTHETIC_INPUT_ONLY";
            if (issue == null && GeneralRevelPolicy.specialIssue(mapper, context) != null)
                issue = "GENERAL_ALPHAMISSENSE_SPECIALIZED_POLICY_REQUIRED";
            if (issue == null && !synthetic) {
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
                if (!model.path("transcript").asText().matches("ENST[0-9]+\\.[1-9][0-9]*")) issue = "GENERAL_ALPHAMISSENSE_EXACT_ENST_REQUIRED";
                if (issue == null) issue = CodingMissenseResolver.resolve(context, tx, model, mapper.createObjectNode(), adapter);
                var key = candidate.getObservation() == null ? null : candidate.getObservation().getKey();
                var allele = context.at("/variant/evidenceQueryAllele");
                if (issue == null && (key == null || !key.getGenomeAssembly().equals(allele.path("assembly").asText())
                        || !key.getChromosome().equals(allele.path("contig").asText()) || key.getPosition() != allele.path("position").asInt()
                        || !key.getReference().equals(allele.path("reference").asText()) || !key.getAlternate().equals(allele.path("alternate").asText())))
                    issue = "GENERAL_ALPHAMISSENSE_CANDIDATE_ALLELE_MISMATCH";
                if (issue == null) {
                    var request = plan.putObject("request").put("schemaVersion", "reviewed-prediction-observation-request-v1").put("preselectedModel", "ALPHAMISSENSE");
                    request.set("context", context.deepCopy()); request.set("resourceBinding", resource.deepCopy());
                    request.set("transcriptModel", model.deepCopy());
                    request.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                            .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", adapter.canonicalSha256(model));
                    request.put("lookupId", "prediction-lookup-sha256-" + adapter.canonicalSha256(request));
                }
            }
        } catch (RuntimeException invalid) { issue = "GENERAL_ALPHAMISSENSE_CONTEXT_INVALID"; }
        if (issue != null) plan.put("status", "BLOCKED").put("reasonCode", issue);
        return new Prepared(plan, tx);
    }

    public ObjectNode execute(Prepared prepared) {
        if (prepared.owner() != this) throw new IllegalArgumentException("GENERAL_ALPHAMISSENSE_PLAN_OWNER_MISMATCH");
        if (prepared.result != null) return prepared.result.deepCopy();
        var plan = prepared.snapshot(); var out = mapper.createObjectNode(); out.set("plan", plan);
        var protein = out.putObject("protein").put("status", "NOT_EVALUATED").put("appliedStrength", "NONE");
        String issue = "BLOCKED".equals(plan.path("status").asText()) ? plan.path("reasonCode").asText() : null;
        if (issue == null && prepared.transcript == null) issue = "PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE";
        if (issue == null && Thread.currentThread().isInterrupted()) issue = "GENERAL_ALPHAMISSENSE_INTERRUPTED";
        if (issue == null) {
            try {
                var request = (ObjectNode) plan.path("request");
                String resourceIssue = resourceCheck.apply(request);
                if (resourceIssue != null) throw new IllegalStateException(resourceIssue);
                var key = mapper.createObjectNode().put("model", "ALPHAMISSENSE")
                        .put("dataClass", request.at("/context/dataClass").asText());
                key.set("resource", resource.deepCopy()); key.set("allele", request.at("/context/variant/evidenceQueryAllele").deepCopy());
                String rawKey = adapter.canonicalSha256(key);
                var capture = facts.obtain(rawKey, () -> { calls++; return lookup.apply(request.deepCopy()); },
                        "GENERAL_ALPHAMISSENSE_LOOKUP_OR_IDENTITY_FAILURE");
                var observation = capture.value(); out.set("observation", observation);
                out.putObject("rawFactReuse").put("version", PredictionBatchFactCache.VERSION).put("keySha256", rawKey)
                        .put("reused", capture.reused()).put("scope", "THIS_REQUEST_RAW_FACT_ONLY");
                if (!resource.equals(observation.path("resource")) || !sameAllele(request, observation))
                    issue = "GENERAL_ALPHAMISSENSE_OBSERVATION_IDENTITY_MISMATCH";
                if (issue == null && !"HIT".equals(observation.path("status").asText()))
                    issue = "NO_RECORD".equals(observation.path("status").asText()) ? "ALPHAMISSENSE_NO_MATCHING_RECORD"
                            : observation.path("reasonCode").asText("ALPHAMISSENSE_RESOURCE_NOT_READY");
                if (issue == null) issue = rowIssue(observation, request);
                if (issue == null) {
                    var bridge = bridge(observation, request); out.set("transcriptBridge", bridge);
                    var association = PredictionObservationAssociation.alpha(mapper, observation, Objects.requireNonNull(prepared.transcript), bridge);
                    out.set("association", association);
                    if (!"ASSOCIATED".equals(association.path("status").asText()))
                        issue = "UNAVAILABLE".equals(bridge.path("status").asText()) ? bridge.path("reasonCode").asText() : association.path("reasonCode").asText();
                    else out.set("protein", GeneralAlphaMissensePolicy.calibrate(mapper, policy, association.path("rawScore").asText()));
                }
            } catch (RuntimeException unavailable) {
                String reason = unavailable.getMessage();
                issue = reason != null && (reason.startsWith("PREDICTION_FACT_REUSE_") || reason.equals("PREDICTION_RESOURCE_FILE_CHANGED"))
                        ? reason : "GENERAL_ALPHAMISSENSE_LOOKUP_OR_IDENTITY_FAILURE";
            }
        }
        if (issue != null) protein.put("reasonCode", issue);
        prepared.result = out.deepCopy(); return out;
    }

    private boolean sameAllele(JsonNode request, JsonNode observation) {
        var expected = request.at("/context/variant/evidenceQueryAllele"); var actual = observation.path("queryAllele");
        return expected.path("assembly").equals(actual.path("genomeAssembly")) && expected.path("contig").equals(actual.path("chromosome"))
                && expected.path("position").asLong() == actual.path("position").asLong()
                && expected.path("reference").equals(actual.path("reference")) && expected.path("alternate").equals(actual.path("alternate"));
    }

    /** The genomic major-transcript product is adopted only as one exact raw row, never a maximum or vote. */
    private String rowIssue(JsonNode observation, JsonNode request) {
        var rows = observation.path("rows");
        if (!rows.isArray() || rows.size() != 1) return "ALPHAMISSENSE_SINGLE_SOURCE_ROW_REQUIRED";
        var row = rows.get(0); String[] f = row.path("sourceRow").asText().split("\\t", -1);
        var a = request.at("/context/variant/evidenceQueryAllele");
        if (f.length != 10 || !f[0].replaceFirst("^chr", "").equals(a.path("contig").asText())
                || !f[1].equals(a.path("position").asText()) || !f[2].equals(a.path("reference").asText())
                || !f[3].equals(a.path("alternate").asText()) || !java.util.Set.of("hg38", "GRCh38").contains(f[4])
                || !f[5].equals(row.path("uniprotId").asText()) || f[5].isBlank()
                || !f[6].equals(row.path("transcriptId").asText()) || !f[6].matches("ENST[0-9]+\\.[1-9][0-9]*")
                || !f[7].equals(row.path("proteinVariant").asText()) || !f[8].equals(row.path("rawScore").asText())
                || !f[9].equals(row.path("rawClass").asText())) return "ALPHAMISSENSE_SOURCE_ROW_IDENTITY_MISMATCH";
        return null;
    }

    private ObjectNode bridge(JsonNode observation, ObjectNode request) {
        var out = mapper.createObjectNode().put("status", "NOT_REQUIRED");
        String sourceTx = observation.at("/rows/0/transcriptId").asText(); var target = request.path("transcriptModel");
        String targetTx = target.path("transcript").asText();
        if (sourceTx.equals(targetTx)) return out;
        if (!sourceTx.split("\\.")[0].equals(targetTx.split("\\.")[0]))
            return out.put("status", "UNAVAILABLE").put("reasonCode", "ALPHAMISSENSE_DIFFERENT_TRANSCRIPT_UNSUPPORTED");
        var index = sourceIndex; var reader = reference;
        if (index == null || reader == null) return out.put("status", "UNAVAILABLE").put("reasonCode", "ALPHAMISSENSE_SOURCE_TRANSCRIPT_MATERIAL_REQUIRED");
        try {
            var sourceContext = ((ObjectNode) request.path("context")).deepCopy();
            ((ObjectNode) sourceContext.path("transcript")).put("version", sourceTx.substring(sourceTx.indexOf('.') + 1));
            var source = index.model(sourceContext, reader);
            String cds = source.path("cdsSequence").asText();
            int genomic = request.at("/context/variant/evidenceQueryAllele/position").asInt();
            if (!cds.equals(target.path("cdsSequence").asText()) || !source.path("strand").equals(target.path("strand"))
                    || CodingTranscriptMapping.cdsPosition(source, genomic) != CodingTranscriptMapping.cdsPosition(target, genomic))
                return out.put("status", "UNAVAILABLE").put("reasonCode", "ALPHAMISSENSE_CDS_OR_POSITION_NOT_EQUIVALENT");
            out.put("status", "VERIFIED_SEQUENCE_EQUIVALENCE").put("sourceTranscriptId", sourceTx).put("targetTranscriptId", targetTx)
                    .put("sourceCds", cds).put("targetCds", target.path("cdsSequence").asText())
                    .put("cdsCanonicalSha256", adapter.canonicalSha256(mapper.getNodeFactory().textNode(cds)))
                    .put("sourceProvenanceSha256", adapter.canonicalSha256(source));
            out.set("sourceModel", source); out.set("sourceIndex", index.identity()); return out;
        } catch (java.io.IOException | IllegalArgumentException missing) {
            return out.put("status", "UNAVAILABLE").put("reasonCode", "ALPHAMISSENSE_SOURCE_TRANSCRIPT_MATERIAL_UNAVAILABLE")
                    .put("detail", Objects.toString(missing.getMessage(), "UNAVAILABLE"));
        }
    }
}
