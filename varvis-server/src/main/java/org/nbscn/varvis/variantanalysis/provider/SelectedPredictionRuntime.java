package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Stage-one adapter only: prepares all routes before acquisition and retains original v3 identities. */
public final class SelectedPredictionRuntime {
    private final ObjectMapper mapper;
    private final PredictionDevelopmentRuntime development;
    private final Hnf1aRevelRuntime revel;
    @Nullable private final ReviewedPredictionBusinessRuntime reviewed;
    @Nullable private final ReviewedPredictionObservationRouter router;
    private final SpliceAiLocalObservationSession splice;
    private final Function<JsonNode, JsonNode> effects;
    @Nullable private final CodingTranscriptMaterialSupply transcriptMaterials;
    @Nullable private final GeneralRevelRuntime generalRevel;
    @Nullable private final GeneralAlphaMissenseRuntime generalAlpha;
    @Nullable private final NativeSpliceAiRuntime nativeSplice;
    @Nullable private final PublishedRoute published;

    public record PublishedRoute(PublishedPredictionCatalog catalog, @Nullable GeneralRevelRuntime revel) { }

    /** Construct per request. The optional reviewed pair is selected by the caller before any score is read. */
    public SelectedPredictionRuntime(ObjectMapper mapper, PredictionDevelopmentRuntime development, Hnf1aRevelRuntime revel,
            @Nullable ReviewedPredictionBusinessRuntime reviewed, @Nullable ReviewedPredictionObservationRouter router,
            SpliceAiLocalObservationSession splice, Function<JsonNode, JsonNode> effects) {
        this(mapper, development, revel, reviewed, router, splice, effects, null);
    }

    public SelectedPredictionRuntime(ObjectMapper mapper, PredictionDevelopmentRuntime development, Hnf1aRevelRuntime revel,
            @Nullable ReviewedPredictionBusinessRuntime reviewed, @Nullable ReviewedPredictionObservationRouter router,
            SpliceAiLocalObservationSession splice, Function<JsonNode, JsonNode> effects,
            @Nullable CodingTranscriptMaterialSupply transcriptMaterials) {
        this(mapper, development, revel, reviewed, router, splice, effects, transcriptMaterials, null);
    }

    public SelectedPredictionRuntime(ObjectMapper mapper, PredictionDevelopmentRuntime development, Hnf1aRevelRuntime revel,
            @Nullable ReviewedPredictionBusinessRuntime reviewed, @Nullable ReviewedPredictionObservationRouter router,
            SpliceAiLocalObservationSession splice, Function<JsonNode, JsonNode> effects,
            @Nullable CodingTranscriptMaterialSupply transcriptMaterials, @Nullable GeneralRevelRuntime generalRevel) {
        this(mapper, development, revel, reviewed, router, splice, effects, transcriptMaterials, generalRevel, null);
    }

    public SelectedPredictionRuntime(ObjectMapper mapper, PredictionDevelopmentRuntime development, Hnf1aRevelRuntime revel,
            @Nullable ReviewedPredictionBusinessRuntime reviewed, @Nullable ReviewedPredictionObservationRouter router,
            SpliceAiLocalObservationSession splice, Function<JsonNode, JsonNode> effects,
            @Nullable CodingTranscriptMaterialSupply transcriptMaterials, @Nullable GeneralRevelRuntime generalRevel,
            @Nullable NativeSpliceAiRuntime nativeSplice) {
        this(mapper, development, revel, reviewed, router, splice, effects, transcriptMaterials, generalRevel, nativeSplice, null);
    }

    public SelectedPredictionRuntime(ObjectMapper mapper, PredictionDevelopmentRuntime development, Hnf1aRevelRuntime revel,
            @Nullable ReviewedPredictionBusinessRuntime reviewed, @Nullable ReviewedPredictionObservationRouter router,
            SpliceAiLocalObservationSession splice, Function<JsonNode, JsonNode> effects,
            @Nullable CodingTranscriptMaterialSupply transcriptMaterials, @Nullable GeneralRevelRuntime generalRevel,
            @Nullable NativeSpliceAiRuntime nativeSplice, @Nullable GeneralAlphaMissenseRuntime generalAlpha) {
        this(mapper, development, revel, reviewed, router, splice, effects, transcriptMaterials,
                generalRevel, nativeSplice, generalAlpha, null);
    }

    public SelectedPredictionRuntime(ObjectMapper mapper, PredictionDevelopmentRuntime development, Hnf1aRevelRuntime revel,
            @Nullable ReviewedPredictionBusinessRuntime reviewed, @Nullable ReviewedPredictionObservationRouter router,
            SpliceAiLocalObservationSession splice, Function<JsonNode, JsonNode> effects,
            @Nullable CodingTranscriptMaterialSupply transcriptMaterials, @Nullable GeneralRevelRuntime generalRevel,
            @Nullable NativeSpliceAiRuntime nativeSplice, @Nullable GeneralAlphaMissenseRuntime generalAlpha,
            @Nullable PublishedRoute published) {
        if (generalRevel != null && generalAlpha != null) throw new IllegalArgumentException("PREDICTION_ONE_GENERAL_MODEL_REQUIRED");
        this.generalAlpha = generalAlpha;
        this.mapper = Objects.requireNonNull(mapper); this.development = Objects.requireNonNull(development);
        this.revel = Objects.requireNonNull(revel); this.reviewed = reviewed; this.router = router;
        this.splice = Objects.requireNonNull(splice); this.effects = Objects.requireNonNull(effects);
        this.transcriptMaterials = transcriptMaterials;
        this.generalRevel = generalRevel;
        this.nativeSplice = nativeSplice;
        this.published = published;
        if ((reviewed == null) != (router == null)) throw new IllegalArgumentException("PREDICTION_REVIEWED_ROUTER_PAIR_REQUIRED");
    }

    private record Work(ObjectNode plan, @Nullable LegacyContextProjection projection,
                        @Nullable PredictionDevelopmentRuntime.Plan development,
                        @Nullable ReviewedPredictionBusinessRuntime.PreparedContext reviewed,
                        @Nullable GeneralRevelRuntime.Prepared general, @Nullable GeneralAlphaMissenseRuntime.Prepared alpha) {
        Work(ObjectNode plan, @Nullable LegacyContextProjection projection, @Nullable PredictionDevelopmentRuntime.Plan development,
                @Nullable ReviewedPredictionBusinessRuntime.PreparedContext reviewed, @Nullable GeneralRevelRuntime.Prepared general) {
            this(plan, projection, development, reviewed, general, null);
        }
        Work(ObjectNode plan, @Nullable LegacyContextProjection projection, @Nullable PredictionDevelopmentRuntime.Plan development,
                @Nullable ReviewedPredictionBusinessRuntime.PreparedContext reviewed) { this(plan, projection, development, reviewed, null); }
    }

    public final class Batch {
        private final List<Work> work;
        private final AnalysisContext analysis;
        @Nullable private ObjectNode result;
        @Nullable private List<String> resultCriteria;
        // Work contains mutable JSON snapshots: structural record hash codes are not stable keys.
        private final java.util.Map<Work, NativeSpliceAiRuntime.Plan> nativePlans = new java.util.IdentityHashMap<>();
        private Batch(List<Work> work, AnalysisContext analysis) {
            this.work = List.copyOf(work); this.analysis = analysis;
            var nativeSplice = SelectedPredictionRuntime.this.nativeSplice;
            if (nativeSplice != null) for (var entry : work) {
                var projection = entry.projection;
                if (projection == null || "PUBLISHED_LOCAL_REVEL".equals(entry.plan.path("route").asText())) continue;
                var plan = nativeSplice.prepare(projection.context(), projection.queryCandidate(),
                        entry.reviewed != null || "REVEL_EXISTING_SUBSET".equals(entry.plan.path("route").asText()));
                nativePlans.put(entry, plan); entry.plan.set("nativeSplicePreparation", plan.snapshot());
            }
        }
        public ObjectNode snapshot() {
            var out = mapper.createObjectNode().put("schemaVersion", "selected-prediction-preparation-v1").put("scoreLookups", 0);
            var plans = out.putArray("plans"); for (var entry : work) plans.add(entry.plan.deepCopy()); return out;
        }
        private SelectedPredictionRuntime owner() { return SelectedPredictionRuntime.this; }
    }

    public Batch prepare(SelectedEvidencePreparation selected, AnalysisContext analysis) {
        return prepareSelected(selected, analysis, null);
    }

    /** A caller-owned subset of actual prepared attempts, never a replacement context/score. */
    public record AttemptKey(String candidateId, String hypothesisId) {
        public AttemptKey {
            if (candidateId == null || candidateId.isBlank() || hypothesisId == null || hypothesisId.isBlank())
                throw new IllegalArgumentException("PREDICTION_ATTEMPT_KEY_REQUIRED");
        }
    }
    public Batch prepare(SelectedEvidencePreparation selected, AnalysisContext analysis, java.util.Set<AttemptKey> attempts) {
        return prepareSelected(selected, analysis, java.util.Set.copyOf(Objects.requireNonNull(attempts)));
    }
    private Batch prepareSelected(SelectedEvidencePreparation selected, AnalysisContext analysis,
            @Nullable java.util.Set<AttemptKey> attempts) {
        Objects.requireNonNull(selected); Objects.requireNonNull(analysis);
        if (!analysis.getAnalysisId().equals(selected.assembly().analysisId())) throw new IllegalArgumentException("ANALYSIS_ASSOCIATION_MISMATCH");
        if (attempts != null) {
            var actual = new java.util.HashSet<AttemptKey>();
            for (var candidate : selected.assembly().candidates()) for (var attempt : candidate.attempts())
                actual.add(new AttemptKey(candidate.candidateId(), attempt.hypothesisId()));
            if (!actual.containsAll(attempts)) throw new IllegalArgumentException("PREDICTION_ATTEMPT_SUBSET_NOT_PREPARED");
        }
        List<Work> plans = new ArrayList<>();
        for (var candidate : selected.assembly().candidates()) {
            if (candidate.attempts().isEmpty() && attempts == null) {
                plans.add(new Work(mapper.createObjectNode().put("candidateId", candidate.candidateId()).put("hypothesisId", "")
                        .put("route", "BLOCKED").put("status", "NOT_EVALUATED").put("reasonCode", "SELECTED_CONTEXT_UNAVAILABLE"), null, null, null));
            }
            for (var attempt : candidate.attempts()) {
                if (attempts != null && !attempts.contains(new AttemptKey(candidate.candidateId(), attempt.hypothesisId()))) continue;
                try { plans.add(prepare(selected.legacyProjection(candidate.candidateId(), attempt.hypothesisId()))); }
                catch (IllegalArgumentException blocked) {
                    var plan = mapper.createObjectNode().put("candidateId", candidate.candidateId()).put("hypothesisId", attempt.hypothesisId())
                            .put("route", "BLOCKED").put("status", "NOT_EVALUATED").put("reasonCode", blocked.getMessage());
                    plans.add(new Work(plan, null, null, null));
                }
            }
        }
        return new Batch(plans, analysis);
    }

    private Work prepare(LegacyContextProjection projection) {
        ObjectNode context = projection.context(); VariantInterpretation candidate = projection.queryCandidate();
        var plan = PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json");
        plan.put("schemaVersion", "selected-prediction-plan-v1").put("status", "PREPARED")
                .put("candidateId", candidate.getCandidateId()).put("hypothesisId", projection.snapshot().path("hypothesisId").asText())
                .put("fallbackAfterScore", false).put("sharedSplicePurpose", "OBSERVATION_ONLY")
                .put("pairedClauseStrengthScope", "SUPPORTING_ONLY");
        plan.set("sourceProjection", projection.snapshot()); plan.set("context", context.deepCopy());
        plan.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        JsonNode restrictions;
        try { restrictions = effects.apply(plan.deepCopy()); } catch (RuntimeException missing) { restrictions = null; }
        plan.set("effectRestrictions", restrictions != null && restrictions.isObject() ? restrictions.deepCopy()
                : mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE"));
        var tx = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper)).selectedVepConsequence(context, candidate);
        if (tx == null) throw new IllegalArgumentException("PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        ObjectNode publishedSelection = null;
        var publishedRoute = published;
        boolean existingSubset = Hnf1aRevelRuntime.matchesContext(context) && IntronicSplicePolicy.missense(tx);
        if (publishedRoute != null && reviewed == null && !existingSubset) {
            var selection = publishedRoute.catalog().select(context);
            plan.set("publishedSelection", selection);
            if ("BLOCKED".equals(selection.path("status").asText())) return blocked(plan, selection.path("reasonCode").asText());
            if ("PUBLISHED_REVEL_MISSENSE".equals(selection.path("status").asText())) {
                if (!"missense_variant".equals(tx.getConsequence())) return blocked(plan, "PREDICTION_PUBLISHED_MISSENSE_BRANCH_OUTSIDE_CONSEQUENCE");
                if (publishedRoute.revel() == null) return blocked(plan, "PREDICTION_PUBLISHED_REVEL_RESOURCE_UNAVAILABLE");
                publishedSelection = selection;
            }
        }
        if (!IntronicSplicePolicy.missense(tx)) {
            if (!IntronicSplicePolicy.spliceCandidate(tx)) throw new IllegalArgumentException("PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE");
            plan.put("primaryMechanism", "SPLICING"); plan.putArray("allowedPp3Mechanisms").add("SPLICING");
            plan.putArray("requiredBp4Mechanisms").add("SPLICING");
        }
        ObjectNode suppliedModel = null;
        CodingTranscriptMaterialSupply.Material suppliedMaterial = null;
        var transcriptMaterials = this.transcriptMaterials;
        if (transcriptMaterials != null && IntronicSplicePolicy.missense(tx)) {
            var material = transcriptMaterials.resolve(context, candidate);
            suppliedMaterial = material;
            plan.set("transcriptMaterial", material.snapshot());
            if (!material.ready()) {
                return blocked(plan, material.snapshot().path("reasonCode").asText());
            }
            suppliedModel = material.model();
        }
        var current = reviewed;
        if (current != null) {
            var prepared = suppliedModel == null ? current.prepare(context) : current.prepare(context, suppliedModel);
            plan.put("route", "REVIEWED_POLICY"); plan.set("reviewedPreparation", prepared.snapshot());
            return new Work(plan, projection, null, prepared);
        }
        if (existingSubset) {
            plan.put("route", "REVEL_EXISTING_SUBSET").put("preselectedModel", "REVEL");
            plan.set("resource", revel.providerInfo()); plan.set("proteinPolicy", Hnf1aRevelRuntime.loadProfile(mapper));
            return new Work(plan, projection, null, null);
        }
        if (publishedSelection != null && publishedRoute != null && publishedRoute.revel() != null) {
            var prepared = publishedRoute.revel().prepare(context, candidate, suppliedMaterial, publishedSelection);
            plan.put("route", "PUBLISHED_LOCAL_REVEL").put("preselectedModel", "REVEL");
            plan.putArray("allowedPp3Mechanisms").add("PROTEIN_MISSENSE");
            plan.putArray("requiredBp4Mechanisms").add("PROTEIN_MISSENSE");
            plan.set("publishedRevelPreparation", prepared.snapshot());
            return new Work(plan, projection, null, null, prepared);
        }
        var generalAlpha = this.generalAlpha;
        if (generalAlpha != null && IntronicSplicePolicy.missense(tx)) {
            var prepared = generalAlpha.prepare(context, candidate, suppliedMaterial);
            plan.put("route", "GENERAL_LOCAL_ALPHAMISSENSE").put("preselectedModel", "ALPHAMISSENSE")
                    .put("pairedClauseStrengthScope", "SOURCE_CALIBRATED_MAXIMUM_STRONG");
            plan.set("generalAlphaPreparation", prepared.snapshot());
            return new Work(plan, projection, null, null, null, prepared);
        }
        var general = generalRevel;
        if (general != null && IntronicSplicePolicy.missense(tx)) {
            var prepared = general.prepare(context, candidate, suppliedMaterial);
            plan.put("route", "GENERAL_LOCAL_REVEL").put("preselectedModel", "REVEL")
                    .put("pairedClauseStrengthScope", "SOURCE_CALIBRATED_MAXIMUM_STRONG");
            plan.set("generalRevelPreparation", prepared.snapshot());
            return new Work(plan, projection, null, null, prepared);
        }
        var prepared = development.prepare(candidate, context, projection.bindingSha256(), plan.path("hypothesisId").asText(),
                context.path("dataClass").asText(), null);
        plan.put("route", "EXISTING_DEVELOPMENT_CORE"); plan.set("developmentPreparation", prepared.snapshot());
        return new Work(plan, projection, prepared, null);
    }

    private Work blocked(ObjectNode plan, String reason) {
        plan.put("route", "BLOCKED").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        return new Work(plan, null, null, null);
    }

    /** Exact repeat on this owned batch returns the saved result without provider or evaluator calls. */
    public ObjectNode execute(Batch batch) { return execute(batch, List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4")); }
    public ObjectNode execute(Batch batch, List<String> selectedCriteria) {
        if (batch.owner() != this) throw new IllegalArgumentException("PREDICTION_BATCH_OWNER_MISMATCH");
        var scope = List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4").stream().filter(selectedCriteria::contains).toList();
        if (scope.isEmpty()) throw new IllegalArgumentException("PREDICTION_CRITERIA_REQUIRED");
        if (batch.resultCriteria != null && !batch.resultCriteria.equals(scope)) throw new IllegalArgumentException("PREDICTION_BATCH_SCOPE_CHANGED");
        batch.resultCriteria = scope;
        if (batch.result != null) return batch.result.deepCopy();
        if (nativeSplice != null) nativeSplice.compute(batch.work.stream().map(batch.nativePlans::get).filter(Objects::nonNull).toList());
        var inputs = new ArrayList<ReviewedPredictionObservationRouter.Input>();
        for (var work : batch.work) if (work.reviewed != null && work.projection != null)
            inputs.add(new ReviewedPredictionObservationRouter.Input(work.reviewed, work.projection.queryCandidate(), List.of()));
        var selectedRouter = router;
        var session = selectedRouter == null ? null : selectedRouter.open(inputs, batch.analysis);
        var output = mapper.createObjectNode().put("schemaVersion", "selected-prediction-result-v1")
                .put("automaticTrigger", false).put("formalEnabled", false).put("classificationEnabled", false);
        output.set("preparation", batch.snapshot()); var results = output.putArray("results");
        for (var work : batch.work) {
            ObjectNode result = results.addObject(); result.set("plan", work.plan.deepCopy());
            var projection = work.projection;
            if (projection == null) { result.set("criteria", refused(work.plan.path("reasonCode").asText(), selectedCriteria)); continue; }
            if (Thread.currentThread().isInterrupted()) { result.set("criteria", refused("PREDICTION_BUNDLE_INTERRUPTED", selectedCriteria)); continue; }
            ObjectNode context = projection.context(); VariantInterpretation candidate = projection.queryCandidate();
            result.set("sourceProjection", projection.snapshot());
            boolean expertRevel = "PUBLISHED_LOCAL_REVEL".equals(work.plan.path("route").asText());
            if (!expertRevel) {
                var fact = splice.observe(candidate.getObservation().getKey(), context.path("dataClass").asText());
                result.set("sharedSpliceObservation", fact.observation().snapshot()); result.set("sharedSpliceSource", fact.source().receipt(mapper));
            }
            ObjectNode mechanisms = mapper.createObjectNode();
            mechanisms.putObject("PROTEIN_MISSENSE").put("status", "NOT_EVALUATED").put("reasonCode", "PREDICTION_PROTEIN_UNRESOLVED");
            mechanisms.putObject("SPLICING").put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED");
            if (expertRevel) {
                var expert = published == null ? null : published.revel();
                if (expert == null || work.general == null) throw new IllegalStateException("PREDICTION_PUBLISHED_PREPARATION_REQUIRED");
                var evaluated = expert.execute(work.general);
                result.set("publishedRevelResult", evaluated);
                var protein = evaluated.path("protein");
                mechanisms.set("PROTEIN_MISSENSE", protein.deepCopy());
                mechanisms.putObject("SPLICING").put("status", "NOT_APPLICABLE").put("reasonCode", "PREDICTION_PUBLISHED_MISSENSE_RULE_ONLY");
                result.set("mechanisms", mechanisms);
                var criteria = PredictionMechanismApplication.apply(mapper, work.plan, mechanisms,
                        "CALIBRATED".equals(protein.path("status").asText()) ? null : protein.path("reasonCode").asText(), selectedCriteria, true);
                for (var criterion : criteria) ((ObjectNode) criterion).put("adoptionScope", "PUBLISHED_EXPERT_SPECIFICATION");
                result.set("criteria", criteria);
            } else if (work.development != null) {
                var evaluated = development.execute(work.development, selectedCriteria); result.set("developmentResult", evaluated);
                result.set("mechanisms", evaluated.path("mechanisms").deepCopy()); result.set("criteria", evaluated.path("criteria").deepCopy());
            } else if (work.reviewed != null && reviewed != null) {
                var evaluated = reviewed.execute(work.reviewed, candidate, session, false); result.set("reviewedResult", evaluated);
                // Existing reviewed output remains auditable; the mechanism contract still requires complete coverage.
                for (String mechanism : List.of("PROTEIN_MISSENSE", "SPLICING"))
                    mechanisms.set(mechanism, reviewedMechanism(evaluated, mechanism));
                result.set("mechanisms", mechanisms); result.set("criteria", PredictionMechanismApplication.apply(mapper, work.plan, mechanisms, null, selectedCriteria));
            } else if (work.alpha != null && generalAlpha != null) {
                var evaluated = generalAlpha.execute(work.alpha); result.set("generalAlphaResult", evaluated);
                mechanisms.set("PROTEIN_MISSENSE", evaluated.path("protein").deepCopy());
                result.set("mechanisms", mechanisms);
                var criteria = PredictionMechanismApplication.apply(mapper, work.plan, mechanisms, null, selectedCriteria);
                for (var criterion : criteria) {
                    ((ObjectNode) criterion).put("adoptionScope", "PROJECT_LOCAL_RESEARCH");
                    ((ObjectNode) criterion).set("combinationConstraints", evaluated.at("/plan/policy/combinationConstraints").deepCopy());
                }
                result.set("criteria", criteria);
            } else if (work.general != null && generalRevel != null) {
                var evaluated = generalRevel.execute(work.general); result.set("generalRevelResult", evaluated);
                mechanisms.set("PROTEIN_MISSENSE", evaluated.path("protein").deepCopy());
                result.set("mechanisms", mechanisms);
                var criteria = PredictionMechanismApplication.apply(mapper, work.plan, mechanisms, null, selectedCriteria);
                for (var criterion : criteria) {
                    ((ObjectNode) criterion).put("adoptionScope", "PROJECT_LOCAL_RESEARCH");
                    ((ObjectNode) criterion).set("combinationConstraints", evaluated.at("/plan/policy/combinationConstraints").deepCopy());
                }
                result.set("criteria", criteria);
            } else {
                var evaluated = revel.evaluate(context, candidate, java.util.Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4"));
                result.set("revelResult", mapper.valueToTree(evaluated));
                var pair = mapper.createObjectNode();
                for (var evaluation : evaluated.criterionEvaluations()) pair.set(evaluation.path("criterionId").asText(), evaluation);
                mechanisms.set("PROTEIN_MISSENSE", pairedMechanism(pair.path("ACMG_AMP_2015:PP3"), pair.path("ACMG_AMP_2015:BP4")));
                result.set("mechanisms", mechanisms); result.set("criteria", PredictionMechanismApplication.apply(mapper, work.plan, mechanisms, null, selectedCriteria));
            }
            var nativeSplice = this.nativeSplice;
            if (nativeSplice != null && batch.nativePlans.containsKey(work)) {
                var evaluated = nativeSplice.evaluate(batch.nativePlans.get(work)); result.set("nativeSpliceResult", evaluated);
                if (work.reviewed == null && !"REVEL_EXISTING_SUBSET".equals(work.plan.path("route").asText())) {
                    var combined = (ObjectNode) result.path("mechanisms"); combined.set("SPLICING", evaluated.path("mechanism").deepCopy());
                    var development = work.development;
                    String issue = development != null && "BLOCKED".equals(development.snapshot().path("status").asText())
                            ? development.snapshot().path("reasonCode").asText() : null;
                    var criteria = PredictionMechanismApplication.apply(mapper, work.plan, combined, issue, selectedCriteria);
                    if (work.general != null || work.alpha != null) for (var criterion : criteria) {
                        ((ObjectNode) criterion).put("adoptionScope", "PROJECT_LOCAL_RESEARCH");
                        ((ObjectNode) criterion).set("combinationConstraints", result.at((work.alpha != null ? "/generalAlphaResult" : "/generalRevelResult") + "/plan/policy/combinationConstraints").deepCopy());
                    }
                    result.set("criteria", criteria);
                }
            }
        }
        for (var value : results) {
            var result = (ObjectNode) value;
            result.set("mechanismSummary", PredictionMechanismSummary.fromResult(mapper, result));
            sealResult(mapper, result);
        }
        if (session != null) output.set("reviewedSourceAudit", session.audit());
        output.put("sharedSpliceProviderCalls", splice.providerCalls()).put("developmentProviderCalls", development.providerCalls());
        if (generalAlpha != null) output.put("generalAlphaProviderCalls", generalAlpha.providerCalls());
        if (generalRevel != null) output.put("generalRevelProviderCalls", generalRevel.providerCalls());
        if (nativeSplice != null) output.set("nativeSpliceAudit", nativeSplice.audit());
        batch.result = output.deepCopy(); return output;
    }

    static void sealResult(ObjectMapper mapper, ObjectNode result) {
        result.remove("resultId");
        result.put("resultId", "selected-prediction-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(result));
    }

    private ObjectNode reviewedMechanism(JsonNode evaluated, String mechanism) {
        JsonNode pp3 = evaluated.at("/clauses/PP3/result"), bp4 = evaluated.at("/clauses/BP4/result");
        if (!mechanism.equals(pp3.path("evidenceMechanism").asText()) || !mechanism.equals(bp4.path("evidenceMechanism").asText())
                || !pp3.path("preselectedModel").equals(bp4.path("preselectedModel")) || !pp3.path("resourceBinding").equals(bp4.path("resourceBinding")))
            return mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "PREDICTION_REQUIRED_MECHANISM_UNRESOLVED");
        return pairedMechanism(pp3.path("evaluation"), bp4.path("evaluation"));
    }
    /** The existing REVEL and reviewed-clause runtimes support Supporting only; reject future unhandled strengths. */
    private ObjectNode pairedMechanism(JsonNode pp3, JsonNode bp4) {
        var out = mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "PREDICTION_REQUIRED_MECHANISM_UNRESOLVED");
        String p = pp3.at("/evaluationResult/status").asText(), b = bp4.at("/evaluationResult/status").asText();
        if (!List.of("MET", "NOT_MET").contains(p) || !List.of("MET", "NOT_MET").contains(b) || "MET".equals(p) && "MET".equals(b)) return out;
        if ("MET".equals(p) && !"SUPPORTING".equals(pp3.path("appliedStrength").asText())
                || "MET".equals(b) && !"SUPPORTING".equals(bp4.path("appliedStrength").asText()))
            return out.put("reasonCode", "PREDICTION_PAIRED_STRENGTH_UNSUPPORTED");
        return out.put("status", "CALIBRATED").put("direction", "MET".equals(p) ? "HARMFUL" : "MET".equals(b) ? "BENIGN" : "UNINFORMATIVE")
                .put("appliedStrength", "MET".equals(p) || "MET".equals(b) ? "SUPPORTING" : "NONE").put("reasonCode", "EXISTING_PAIRED_CLAUSE_ASSESSMENT");
    }
    private ObjectNode refused(String reason, List<String> selectedCriteria) {
        var out = mapper.createObjectNode();
        for (String code : List.of("PP3", "BP4")) if (selectedCriteria.contains("ACMG_AMP_2015:" + code)) out.putObject(code).put("criterionId", "ACMG_AMP_2015:" + code)
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", reason);
        return out;
    }
}
