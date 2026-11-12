package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.AssemblyResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Blocker;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashMap;

/** Explicit local AlphaMissense slice, also used by the opt-in business submission. No automatic job trigger or classification. */
public final class ExplicitAlphaMissenseDispatcher {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final CSpecExecutionPreflight preflight;
    private final TopNAcmgContextPlanner planner;
    private final AlphaMissenseProvider provider;
    private final CriterionCall evaluator;
    private final ObjectNode profile;
    private final ObjectNode rules;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding binding;
    private java.util.function.Supplier<SpliceAiBusinessRuntime> spliceRuntime;
    private java.util.function.Supplier<Hnf1aRevelRuntime> revelRuntime;
    private ReviewedRuleContextPreparation ruleContexts;
    private ReviewedPredictionBusinessRuntime reviewedPrediction;
    private ReviewedPredictionObservationRouter predictionRouter;

    public ExplicitAlphaMissenseDispatcher(ObjectMapper mapper, AlphaMissenseProvider provider,
            java.util.function.Supplier<SpliceAiBusinessRuntime> spliceRuntime,
            java.util.function.Supplier<Hnf1aRevelRuntime> revelRuntime, ReviewedRuleContextPreparation ruleContexts,
            ReviewedPredictionBusinessRuntime prediction, ReviewedPredictionObservationRouter router) {
        this(mapper, provider, spliceRuntime, revelRuntime);
        this.ruleContexts = ruleContexts;
        reviewedPrediction = Objects.requireNonNull(prediction);
        predictionRouter = router;
    }

    public ExplicitAlphaMissenseDispatcher(ObjectMapper mapper, AlphaMissenseProvider provider,
                                            java.util.function.Supplier<SpliceAiBusinessRuntime> spliceRuntime,
                                            java.util.function.Supplier<Hnf1aRevelRuntime> revelRuntime,
                                            ReviewedRuleContextPreparation ruleContexts) {
        this(mapper, provider, spliceRuntime, revelRuntime);
        this.ruleContexts = Objects.requireNonNull(ruleContexts);
    }

    public ExplicitAlphaMissenseDispatcher(ObjectMapper mapper, AlphaMissenseProvider provider,
                                            java.util.function.Supplier<SpliceAiBusinessRuntime> spliceRuntime,
                                            java.util.function.Supplier<Hnf1aRevelRuntime> revelRuntime) {
        this(mapper, provider, spliceRuntime);
        this.revelRuntime = Objects.requireNonNull(revelRuntime);
    }

    public ExplicitAlphaMissenseDispatcher(ObjectMapper mapper, AlphaMissenseProvider provider,
                                            java.util.function.Supplier<SpliceAiBusinessRuntime> spliceRuntime) {
        this(mapper, provider);
        this.spliceRuntime = Objects.requireNonNull(spliceRuntime);
    }

    public ExplicitAlphaMissenseDispatcher(ObjectMapper mapper, AlphaMissenseProvider provider) {
        this(mapper, provider, new AlphaMissenseCriterionEvaluator(mapper)::evaluate);
    }

    ExplicitAlphaMissenseDispatcher(ObjectMapper mapper, AlphaMissenseProvider provider, CriterionCall evaluator) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        this.preflight = new CSpecExecutionPreflight(mapper);
        this.planner = new TopNAcmgContextPlanner(mapper);
        this.profile = AlphaMissenseCriterionEvaluator.loadProfile(mapper);
        this.rules = AlphaMissenseCriterionEvaluator.loadSuccessorRegistry(mapper);
        this.binding = wiring.bind(profile, "ALPHAMISSENSE_2023_08_03_GRCH38_TABIX");
        this.revelRuntime = () -> Hnf1aRevelRuntime.unavailable(mapper);
    }

    public DispatchResult dispatch(AssemblyResult assembled, List<VariantInterpretation> rankedCandidates, AnalysisContext analysis) {
        return dispatch(assembled, rankedCandidates, analysis, List.of(), List.of());
    }

    /** Always reruns the pinned preflight; callers cannot submit an allow flag or standalone selection ID. */
    public DispatchResult dispatch(AssemblyResult assembled, List<VariantInterpretation> rankedCandidates,
                                   AnalysisContext analysis, List<JsonNode> reviews, List<JsonNode> conflicts) {
        var checked = preflight.check(assembled, reviews, conflicts);
        var spliceChecked = spliceRuntime == null ? null : CSpecExecutionPreflight.splice(mapper).check(assembled, reviews, conflicts);
        if (reviewedPrediction != null)
            return dispatchReviewed(checked, spliceChecked, rankedCandidates, analysis, conflicts);
        var pending = new ArrayList<PendingSplice>();
        List<VariantInterpretation> ranked = rankedCandidates == null ? List.of() : rankedCandidates;
        Map<String, Integer> counts = new HashMap<>();
        for (var candidate : ranked) if (candidate != null) counts.merge(candidate.getCandidateId(), 1, (left, right) -> left + right);
        List<CandidateDispatch> rows = new ArrayList<>();
        for (int index = 0; index < checked.candidates().size(); index++) {
            var row = checked.candidates().get(index);
            VariantInterpretation candidate = index < ranked.size() ? ranked.get(index) : null;
            Blocker association = null;
            if (analysis == null || !Objects.equals(checked.analysisId(), analysis.getAnalysisId())) {
                association = blocker("ANALYSIS_ASSOCIATION_MISMATCH", "analysisId");
            } else if (checked.selectedCount() != Math.min(checked.requestedN(), ranked.size())) {
                association = blocker("RANKED_CANDIDATE_COUNT_MISMATCH", "rankedCandidates");
            } else if (candidate == null || !Objects.equals(row.candidateId(), candidate.getCandidateId())) {
                association = blocker("RANKED_CANDIDATE_ASSOCIATION_MISMATCH", "candidateId");
            } else if (counts.getOrDefault(candidate.getCandidateId(), 0) != 1) {
                association = blocker("RANKED_CANDIDATE_ID_AMBIGUOUS", "candidateId");
            }
            List<DispatchAttempt> attempts = new ArrayList<>();
            for (int a = 0; a < row.attempts().size(); a++) {
                var attempt = row.attempts().get(a);
                var originalContext = attempt.original().context();
                if (originalContext != null && org.nbscn.varvis.variantanalysis.service.AcmgRecommendations
                        .populationOnlySelection(originalContext.at("/transcript/selectionPolicy").asText())) {
                    var blockers = new ArrayList<>(attempt.blockers());
                    if (association != null) blockers.add(association);
                    blockers.add(blocker("PREDICTION_TRANSCRIPT_CONFIRMATION_REQUIRED", "transcript.selectionPolicy"));
                    attempts.add(result(attempt, originalContext, attempt.selection(), List.of(), List.of(),
                            mapper.createObjectNode().put("predictionPath", "PM2_CONTEXT_ONLY").put("predictionTranscriptConfirmed", false), blockers, 0, 0));
                    continue;
                }
                if (originalContext != null && ReviewedRuleContextPreparation.requested(
                        originalContext.at("/transcript/selectionPolicy").asText())) {
                    attempts.add(prepareRuleContext(attempt, candidate, analysis, association));
                    continue;
                }
                TranscriptConsequence selected = null;
                try { if (candidate != null && attempt.original().context() != null) selected = wiring.selectedVepConsequence(attempt.original().context(), candidate); }
                catch (RuntimeException invalid) { /* existing gated execute records the input failure */ }
                if (Hnf1aRevelRuntime.matchesContext(originalContext) && IntronicSplicePolicy.missense(selected)) {
                    attempts.add(executeRevel(attempt, candidate, analysis, association));
                } else if (spliceChecked != null && IntronicSplicePolicy.spliceCandidate(selected)) {
                    var spliceAttempt = spliceChecked.candidates().get(index).attempts().get(a);
                    var prepared = prepareSplice(spliceAttempt, candidate, analysis, selected, spliceChecked.registryPairing(), association);
                    attempts.add(prepared);
                    if (prepared.blockers().isEmpty()) pending.add(new PendingSplice(index, a, prepared,
                            new SpliceAiBusinessRuntime.Input(prepared.context(), candidate, selected, spliceAttempt.selectionId())));
                } else attempts.add(execute(attempt, candidate, analysis, checked.registryPairing(), association));
            }
            rows.add(new CandidateDispatch(row.candidateId(), attempts));
        }
        finishSplice(rows, pending, analysis);
        int providerCalls = rows.stream().flatMap(row -> row.attempts().stream()).mapToInt((DispatchAttempt attempt) -> attempt.providerCalls()).sum();
        int evaluatorCalls = rows.stream().flatMap(row -> row.attempts().stream()).mapToInt((DispatchAttempt attempt) -> attempt.evaluatorCalls()).sum();
        var effectiveRows = rows.stream().map(row -> new CSpecExecutionPreflight.CandidateResult(row.candidateId(),
                row.attempts().stream().map(a -> Objects.requireNonNull(a).preflight()).toList())).toList();
        var effectivePairing = checked.registryPairing();
        if (effectivePairing != null && rows.stream().flatMap(row -> row.attempts().stream())
                .anyMatch(a -> a.transcriptInput() != null && "HNF1A_REVEL".equals(a.transcriptInput().path("predictionPath").asText())))
            effectivePairing.set("hnf1aRevelScientificPolicy", Hnf1aRevelRuntime.loadProfile(mapper));
        var effective = new CSpecExecutionPreflight.BatchResult(checked.analysisId(), checked.requestedN(), checked.selectedCount(),
                checked.candidateIds(), effectiveRows, checked.unmatchedInputs(), checked.candidateOrderingChanged(), effectivePairing);
        return new DispatchResult(effective, rows, providerCalls, evaluatorCalls);
    }

    private record PredictionPending(int row, int attempt, ReviewedPredictionBusinessRuntime.PreparedContext prepared,
                                     VariantInterpretation candidate, DispatchAttempt validated) { }

    /** First pass selects every policy; subsequent passes may query only those frozen requests. */
    private DispatchResult dispatchReviewed(CSpecExecutionPreflight.BatchResult checked, CSpecExecutionPreflight.BatchResult spliceChecked,
            List<VariantInterpretation> rankedCandidates, AnalysisContext analysis, List<JsonNode> conflicts) {
        var runtime = Objects.requireNonNull(reviewedPrediction);
        List<VariantInterpretation> ranked = rankedCandidates == null ? List.of() : rankedCandidates;
        var pending = new ArrayList<PredictionPending>();
        var rows = new ArrayList<CandidateDispatch>();
        var sourceInputs = new ArrayList<ReviewedPredictionObservationRouter.Input>();
        var counts = new HashMap<String, Integer>();
        for (var candidate : ranked) if (candidate != null) counts.merge(candidate.getCandidateId(), 1, (left, right) -> left + right);
        for (int rowIndex = 0; rowIndex < checked.candidates().size(); rowIndex++) {
            var row = checked.candidates().get(rowIndex);
            var candidate = rowIndex < ranked.size() ? ranked.get(rowIndex) : null;
            var attempts = new ArrayList<DispatchAttempt>();
            Blocker association = null;
            if (analysis == null || !Objects.equals(analysis.getAnalysisId(), checked.analysisId())) association = blocker("ANALYSIS_ASSOCIATION_MISMATCH", "analysisId");
            else if (checked.selectedCount() != Math.min(checked.requestedN(), ranked.size())) association = blocker("RANKED_CANDIDATE_COUNT_MISMATCH", "rankedCandidates");
            else if (candidate == null || !Objects.equals(candidate.getCandidateId(), row.candidateId()) || counts.getOrDefault(row.candidateId(), 0) != 1)
                association = blocker("RANKED_CANDIDATE_ASSOCIATION_MISMATCH", "candidateId");
            for (int index = 0; index < row.attempts().size(); index++) {
                var selected = row.attempts().get(index);
                var context = selected.original().context();
                String selection = context == null ? "" : context.at("/transcript/selectionPolicy").asText();
                if (org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(selection)) {
                    var blockers = new ArrayList<>(selected.blockers()); if (association != null) blockers.add(association);
                    blockers.add(blocker("PREDICTION_TRANSCRIPT_CONFIRMATION_REQUIRED", "transcript.selectionPolicy"));
                    attempts.add(result(selected, context, selected.selection(), List.of(), List.of(), mapper.createObjectNode()
                            .put("predictionPath", "PM2_CONTEXT_ONLY").put("predictionTranscriptConfirmed", false), blockers, 0, 0));
                    continue;
                }
                if (ReviewedRuleContextPreparation.requested(selection)) {
                    attempts.add(prepareRuleContext(selected, candidate, analysis, association)); continue;
                }
                ObjectNode input = mapper.createObjectNode().put("predictionPath", ReviewedPredictionContextPreparation.PATH)
                        .put("predictionContextConfirmed", false).put("predictionCompatibilityClaimed", false).put("adoptionSupported", false);
                input.set("predictionBusinessBinding", runtime.identity()); input.set("predictionContextPreparationBinding", runtime.contexts().identity());
                var blockers = new ArrayList<>(selected.original().blockers()); if (association != null) blockers.add(association);
                try {
                    require(context != null && blockers.isEmpty(), "PREDICTION_CONTEXT_SOURCE_NOT_READY", "context");
                    if (context == null) throw new DispatchBlocked("PREDICTION_CONTEXT_SOURCE_NOT_READY", "context");
                    require(runtime.contexts().accepts(selection), "PREDICTION_CONTEXT_CAPABILITY_NOT_CONFIRMED", "transcript.selectionPolicy");
                    adapter.validateCompleteContext(context);
                    require("NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()), "PREDICTION_CONTEXT_NATIVE_ALLELE_REQUIRED", "variant");
                    var planned = planner.plan(analysis, List.of(candidate), 1, List.of(selected.original().seed())).candidates().get(0);
                    require(planned.blockers().isEmpty() && planned.readyContexts().size() == 1 && planned.readyContexts().get(0).equals(context),
                            "RANKED_CANDIDATE_CONTEXT_MISMATCH", "candidate/context");
                    var tx = wiring.selectedVepConsequence(context, candidate);
                    require(ReviewedPredictionContextPreparation.eligibleAnnotation(candidate, tx), "PREDICTION_VERSIONED_ANNOTATION_REQUIRED", "transcript");
                    if (tx == null) throw new DispatchBlocked("PREDICTION_VERSIONED_ANNOTATION_REQUIRED", "transcript");
                    verifyTranscriptIdentity(context, tx);
                    input.set("persistedAnnotation", transcriptInput(tx)); input.put("predictionContextConfirmed", true);
                } catch (DispatchBlocked failure) { blockers.add(blocker(failure.reason, failure.field)); }
                catch (RuntimeException failure) { blockers.add(blocker("PREDICTION_CONTEXT_INPUT_INVALID", "context")); }
                var attempt = result(selected, blockers.isEmpty() ? context : null, selected.selection(), List.of(), List.of(), input, blockers, 0, 0);
                attempts.add(attempt);
                if (context != null) {
                    var prepared = runtime.prepare(context);
                    pending.add(new PredictionPending(rowIndex, index, prepared, candidate, attempt));
                    if (blockers.isEmpty() && candidate != null) sourceInputs.add(new ReviewedPredictionObservationRouter.Input(prepared, candidate,
                            conflicts == null ? List.of() : conflicts));
                }
            }
            rows.add(new CandidateDispatch(row.candidateId(), attempts));
        }
        // No legacy or new provider has run above. Only explicit outside-scope clauses may use the old calibrated path.
        var legacySplice = new ArrayList<PendingSplice>();
        for (var p : pending) {
            var a = p.validated(); if (!a.blockers().isEmpty() || p.prepared().cancelled() || Thread.currentThread().isInterrupted()) continue;
            Set<String> allowed = new java.util.LinkedHashSet<>();
            for (String code : ReviewedPredictionBusinessRuntime.CLAUSES)
                if (ReviewedPredictionBundle.usesPreexistingClause(p.prepared().snapshot().at("/clauses/" + code))) allowed.add("ACMG_AMP_2015:" + code);
            if (allowed.isEmpty()) continue;
            var tx = wiring.selectedVepConsequence(a.context(), p.candidate());
            DispatchAttempt legacy;
            String legacyModel = Hnf1aRevelRuntime.matchesContext(a.context()) && IntronicSplicePolicy.missense(tx) ? "REVEL"
                    : IntronicSplicePolicy.spliceCandidate(tx) ? "SPLICEAI" : "ALPHAMISSENSE";
            if (predictionRouter == null || !predictionRouter.enabled(legacyModel)) {
                var issues = new ArrayList<>(a.blockers()); issues.add(blocker("PREDICTION_" + legacyModel + "_DISABLED", "preexistingPrediction"));
                replace(rows, p.row(), p.attempt(), result(a.preflight(), a.context(), a.selection(), List.of(), List.of(),
                        mapper.createObjectNode().put("predictionPath", "PREEXISTING_PREDICTION_DISABLED").put("preselectedModel", legacyModel), issues, 0, 0));
                continue;
            }
            if (Hnf1aRevelRuntime.matchesContext(a.context()) && IntronicSplicePolicy.missense(tx)) {
                var pair = new java.util.LinkedHashSet<>(allowed); pair.remove("ACMG_AMP_2015:BP7");
                legacy = executeRevel(a.preflight(), p.candidate(), analysis, null, pair);
            } else if (spliceChecked != null && IntronicSplicePolicy.spliceCandidate(tx)) {
                var selection = spliceChecked.candidates().get(p.row()).attempts().get(p.attempt());
                legacy = prepareSplice(selection, p.candidate(), analysis, tx, spliceChecked.registryPairing(), null);
                boolean missingBp4 = allowed.contains("ACMG_AMP_2015:BP7") && !allowed.contains("ACMG_AMP_2015:BP4");
                if (missingBp4) {
                    allowed.remove("ACMG_AMP_2015:BP7");
                    var issues = new ArrayList<>(legacy.blockers());
                    issues.add(blocker("BP7_PREEXISTING_BP4_POLICY_NOT_ALLOWED", "preexistingPrediction.BP7"));
                    legacy = result(legacy.preflight(), legacy.context(), legacy.selection(), List.of(), List.of(), legacy.transcriptInput(), issues, 0, 0);
                }
                if ((legacy.blockers().isEmpty() || missingBp4 && legacy.blockers().size() == 1) && !allowed.isEmpty())
                    legacySplice.add(new PendingSplice(p.row(), p.attempt(), legacy,
                            new SpliceAiBusinessRuntime.Input(legacy.context(), p.candidate(), tx, selection.selectionId()), Set.copyOf(allowed)));
            } else {
                var pair = new java.util.LinkedHashSet<>(allowed); pair.remove("ACMG_AMP_2015:BP7");
                legacy = execute(a.preflight(), p.candidate(), analysis, checked.registryPairing(), null, pair, true);
            }
            replace(rows, p.row(), p.attempt(), legacy);
        }
        ReviewedPredictionObservationRouter.Session session = null;
        if (predictionRouter != null && analysis != null) {
            try {
                session = predictionRouter.open(sourceInputs, analysis);
                session.includeLegacySplice(legacySplice.stream().map(p -> Objects.requireNonNull(p).input()).toList());
            } catch (RuntimeException invalid) { /* all current clauses retain a missing-provider refusal */ }
        }
        if (!Thread.currentThread().isInterrupted() && session != null) finishSplice(rows, legacySplice, analysis, session);
        else if (session == null) for (var p : legacySplice) {
            var a = rows.get(p.row()).attempts().get(p.attempt());
            var issues = new ArrayList<>(a.blockers()); issues.add(blocker("PREDICTION_OBSERVATION_SESSION_UNAVAILABLE", "preexistingPrediction"));
            replace(rows, p.row(), p.attempt(), result(a.preflight(), a.context(), a.selection(), List.of(), List.of(), a.transcriptInput(), issues, 0, 0));
        }
        int accountedSourceDelegations = 0;
        for (var p : pending) {
            var validated = p.validated();
            var legacy = rows.get(p.row()).attempts().get(p.attempt());
            ObjectNode current;
            if (Thread.currentThread().isInterrupted()) current = runtime.refuse(p.prepared(), "PREDICTION_BUNDLE_INTERRUPTED");
            else if (!validated.blockers().isEmpty()) current = runtime.refuse(p.prepared(), validated.blockers().get(0).reasonCode());
            else current = runtime.execute(p.prepared(), p.candidate(), session);
            var input = validated.transcriptInput(); input.set("reviewedPrediction", current);
            input.set("predictionPreparation", p.prepared().snapshot());
            if (session != null) input.set("sourceSessionAudit", session.audit());
            input.put("providerCallsSemantics", "SOURCE_PROVIDER_INVOCATIONS")
                    .put("lookupCallbackAttempts", current.path("providerCalls").asInt());
            input.set("preexistingPrediction", mapper.valueToTree(legacy));
            var atomics = new LinkedHashMap<String, ObjectNode>();
            for (var atomic : legacy.atomicEvidence()) atomics.put(adapter.canonicalSha256(atomic), atomic);
            var evaluations = new ArrayList<ObjectNode>();
            var blockers = new ArrayList<>(validated.blockers());
            for (String code : ReviewedPredictionBusinessRuntime.CLAUSES) {
                JsonNode clause = current.at("/clauses/" + code);
                if (ReviewedPredictionBundle.usesPreexistingClause(clause)) {
                    legacy.criterionEvaluations().stream().filter(e -> ("ACMG_AMP_2015:" + code).equals(e.path("criterionId").asText())).forEach(evaluations::add);
                    for (var blocker : legacy.blockers()) if (!blockers.contains(blocker)) blockers.add(blocker);
                } else if (clause.at("/result/evaluation") instanceof ObjectNode evaluated) {
                    evaluations.add(evaluated.deepCopy());
                    if (clause.at("/result/evidence") instanceof ObjectNode atomic) atomics.put(adapter.canonicalSha256(atomic), atomic.deepCopy());
                    if (clause.at("/result/vepEvidence") instanceof ObjectNode atomic) atomics.put(adapter.canonicalSha256(atomic), atomic.deepCopy());
                } else blockers.add(blocker(ReviewedPredictionBusinessRuntime.refusalReason(clause.path("result")), "reviewedPrediction." + code));
            }
            int calls = current.at("/executionCounts/completedClauseEvaluations").asInt();
            int sourceDelegations = session == null ? 0 : session.audit().path("sourceDelegations").asInt();
            int actualCalls = legacy.providerCalls() + sourceDelegations - accountedSourceDelegations;
            accountedSourceDelegations = sourceDelegations;
            replace(rows, p.row(), p.attempt(), result(validated.preflight(), validated.context(), legacy.selection(),
                    new ArrayList<>(atomics.values()), evaluations, input, blockers, actualCalls, legacy.evaluatorCalls() + calls));
        }
        if (session != null) for (var p : pending) {
            var a = rows.get(p.row()).attempts().get(p.attempt());
            var input = a.transcriptInput(); input.set("sourceSessionAudit", session.audit().put("snapshotStage", "DISPATCH_FINISHED"));
            replace(rows, p.row(), p.attempt(), result(a.preflight(), a.context(), a.selection(), a.atomicEvidence(), a.criterionEvaluations(), input, a.blockers(), a.providerCalls(), a.evaluatorCalls()));
        }
        var effectiveRows = rows.stream().map(row -> new CSpecExecutionPreflight.CandidateResult(row.candidateId(), row.attempts().stream().map(a -> Objects.requireNonNull(a).preflight()).toList())).toList();
        var effective = new CSpecExecutionPreflight.BatchResult(checked.analysisId(), checked.requestedN(), checked.selectedCount(), checked.candidateIds(), effectiveRows,
                checked.unmatchedInputs(), checked.candidateOrderingChanged(), checked.registryPairing());
        return new DispatchResult(effective, rows, rows.stream().flatMap(row -> row.attempts().stream()).mapToInt((DispatchAttempt a) -> a.providerCalls()).sum(),
                rows.stream().flatMap(row -> row.attempts().stream()).mapToInt((DispatchAttempt a) -> a.evaluatorCalls()).sum());
    }
    private static void replace(List<CandidateDispatch> rows, int row, int attempt, DispatchAttempt value) {
        var attempts = new ArrayList<>(rows.get(row).attempts()); attempts.set(attempt, value);
        rows.set(row, new CandidateDispatch(rows.get(row).candidateId(), attempts));
    }

    /** Zero-provider channel for an explicitly selected annotation. Old prediction preflight does not select a new rule. */
    private DispatchAttempt prepareRuleContext(CSpecExecutionPreflight.SelectionAttempt selected, VariantInterpretation candidate,
                                               AnalysisContext analysis, Blocker association) {
        var context = selected.original().context();
        var blockers = new ArrayList<>(selected.original().blockers());
        if (association != null) blockers.add(association);
        ObjectNode input = mapper.createObjectNode().put("predictionPath", ReviewedRuleContextPreparation.PATH)
                .put("ruleContextConfirmed", false).put("criterionPolicySelected", false)
                .put("predictionCompatibilityClaimed", false).put("predictionEvaluationDeferred", true);
        try {
            require(blockers.isEmpty() && context != null, "RULE_CONTEXT_SOURCE_NOT_READY", "context");
            if (context == null) throw new DispatchBlocked("RULE_CONTEXT_SOURCE_NOT_READY", "context");
            var capability = ruleContexts;
            require(capability != null && capability.accepts(context.at("/transcript/selectionPolicy").asText()),
                    "RULE_CONTEXT_CAPABILITY_NOT_CONFIRMED", "transcript.selectionPolicy");
            if (capability == null) throw new DispatchBlocked("RULE_CONTEXT_CAPABILITY_NOT_CONFIRMED", "transcript.selectionPolicy");
            adapter.validateCompleteContext(context);
            require("NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                            && "GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                            && ReviewedRuleContextPreparation.smallAllele(context.at("/variant/evidenceQueryAllele/reference").asText(),
                                    context.at("/variant/evidenceQueryAllele/alternate").asText()),
                    "RULE_CONTEXT_NATIVE_SMALL_VARIANT_REQUIRED", "variant");
            var planned = planner.plan(analysis, List.of(candidate), 1, List.of(selected.original().seed())).candidates().get(0);
            require(planned.blockers().isEmpty() && planned.readyContexts().size() == 1
                    && planned.readyContexts().get(0).equals(context), "RANKED_CANDIDATE_CONTEXT_MISMATCH", "candidate/context");
            TranscriptConsequence annotation = wiring.selectedVepConsequence(context, candidate);
            require(ReviewedRuleContextPreparation.eligibleAnnotation(candidate, annotation),
                    "RULE_CONTEXT_VERSIONED_ANNOTATION_REQUIRED", "transcript");
            if (annotation == null) throw new DispatchBlocked("RULE_CONTEXT_VERSIONED_ANNOTATION_REQUIRED", "transcript");
            verifyTranscriptIdentity(context, annotation);
            input = transcriptInput(annotation).put("predictionPath", ReviewedRuleContextPreparation.PATH)
                    .put("ruleContextConfirmed", true).put("criterionPolicySelected", false)
                    .put("predictionCompatibilityClaimed", false).put("predictionEvaluationDeferred", true)
                    .put("contextMeaning", "EXPLICIT_RULE_CONTEXT_CONFIRMED_EVIDENCE_REQUIRES_VERIFICATION");
            input.set("ruleContextPreparationBinding", capability.identity());
            return result(selected, context, selected.selection(), List.of(), List.of(), input, blockers, 0, 0);
        } catch (DispatchBlocked invalid) { blockers.add(blocker(invalid.reason, invalid.field)); }
        catch (RuntimeException invalid) { blockers.add(blocker("RULE_CONTEXT_INPUT_INVALID", "context")); }
        // Do not publish a complete execution context on a failed preparation. Downstream rules must refuse it.
        return result(selected, null, selected.selection(), List.of(), List.of(), input, blockers, 0, 0);
    }

    private DispatchAttempt executeRevel(CSpecExecutionPreflight.SelectionAttempt base, VariantInterpretation candidate,
                                         AnalysisContext analysis, Blocker association) {
        return executeRevel(base, candidate, analysis, association, Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4"), false);
    }
    private DispatchAttempt executeRevel(CSpecExecutionPreflight.SelectionAttempt base, VariantInterpretation candidate,
            AnalysisContext analysis, Blocker association, Set<String> clauses) {
        return executeRevel(base, candidate, analysis, association, clauses, true);
    }
    private DispatchAttempt executeRevel(CSpecExecutionPreflight.SelectionAttempt base, VariantInterpretation candidate,
            AnalysisContext analysis, Blocker association, Set<String> clauses, boolean masked) {
        var blockers = new ArrayList<>(base.blockers());
        if (association != null) blockers.add(association);
        var context = base.original().context();
        var input = mapper.createObjectNode().put("predictionPath", "HNF1A_REVEL");
        if (!blockers.isEmpty() || context == null)
            return result(base, context, base.selection(), List.of(), List.of(), input, blockers, 0, 0);
        var selection = Hnf1aRevelRuntime.selection(mapper, context);
        var ids = new ArrayList<String>();
        selection.path("candidateOverlayIds").forEach(id -> ids.add(id.asText()));
        boolean exact = "SINGLE_SELECTABLE_MATCH".equals(selection.path("outcome").asText());
        // A known disease policy outside the implemented transcript scope cannot fall back to AM.
        var constraints = mapper.createObjectNode().put("overlaySelectionStatus", exact ? "SELECTED" : "NO_MATCH")
                .put("reviewConstraint", "NOT_REQUIRED").put("criterionEvaluationConstraint", exact ? "EVALUATE_WITH_SELECTED_OVERLAY" : "FORCE_NOT_EVALUATED")
                .put("combinationDisposition", exact ? "CONTINUE" : "ABSTAIN");
        var selected = new CSpecExecutionPreflight.SelectionAttempt(base.original(), selection.path("outcome").asText(), ids,
                selection.path("selectionId").asText(), selection, constraints, List.of(), List.of(), blockers);
        try {
            adapter.validateCompleteContext(context);
            var planned = planner.plan(analysis, List.of(candidate), 1, List.of(base.original().seed())).candidates().get(0);
            require(planned.blockers().isEmpty() && planned.readyContexts().size() == 1
                    && planned.readyContexts().get(0).equals(context), "RANKED_CANDIDATE_CONTEXT_MISMATCH", "candidate/context");
            var runtime = Objects.requireNonNull(revelRuntime.get());
            var value = masked ? runtime.evaluate(context, candidate, clauses) : runtime.evaluate(context, candidate);
            for (var issue : value.blockers()) blockers.add(new Blocker("REVEL_DISPATCH_BLOCKED", issue.code(), "revel"));
            require(selection.equals(value.selection()), "REVEL_SELECTION_IDENTITY_MISMATCH", "selection");
            require(value.criterionEvaluations().stream().allMatch(e -> context.path("contextId").equals(e.path("contextId"))
                    && selection.path("selectionId").equals(e.at("/ruleBinding/cspecSelectionId"))),
                    "CRITERION_EVALUATION_INSTANCE_INVALID", "criterionEvaluations");
            return result(selected, context, selection, value.atomicEvidence(), value.criterionEvaluations(),
                    value.transcriptInput(), blockers, value.providerCalls(), value.evaluatorCalls());
        } catch (DispatchBlocked failure) {
            blockers.add(new Blocker("REVEL_DISPATCH_BLOCKED", failure.reason, failure.field));
        } catch (RuntimeException unavailable) {
            blockers.add(new Blocker("REVEL_DISPATCH_BLOCKED", "REVEL_RUNTIME_UNAVAILABLE", "revel"));
        }
        return result(selected, context, selection, List.of(), List.of(), input, blockers, 0, 0);
    }

    private record PendingSplice(int row, int attempt, DispatchAttempt prepared, SpliceAiBusinessRuntime.Input input, Set<String> clauses) {
        PendingSplice(int row, int attempt, DispatchAttempt prepared, SpliceAiBusinessRuntime.Input input) {
            this(row, attempt, prepared, input, Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BP7"));
        }
    }
    private DispatchAttempt prepareSplice(CSpecExecutionPreflight.SelectionAttempt selected, VariantInterpretation candidate,
            AnalysisContext analysis, TranscriptConsequence transcript, ObjectNode pairing, Blocker association) {
        var blockers = new ArrayList<>(selected.blockers());
        if (association != null) blockers.add(association);
        if (blockers.isEmpty()) try {
            gate(selected, pairing, SpliceAiCriterionEvaluator.loadBp7Profile(mapper));
            require(selected.original().context().at("/variant/evidenceQueryAllele/reference").asText().matches("[ACGT]")
                            && selected.original().context().at("/variant/evidenceQueryAllele/alternate").asText().matches("[ACGT]"),
                    "SPLICEAI_BUSINESS_VARIANT_TYPE_UNSUPPORTED", "variant.evidenceQueryAllele");
            var planned = planner.plan(analysis, List.of(candidate), 1, List.of(selected.original().seed())).candidates().get(0);
            require(planned.blockers().isEmpty() && planned.readyContexts().size() == 1
                    && planned.readyContexts().get(0).equals(selected.original().context()), "RANKED_CANDIDATE_CONTEXT_MISMATCH", "candidate/context");
            verifyTranscriptIdentity(selected.original().context(), transcript);
            if (IntronicSplicePolicy.synonymous(transcript)) {
                var proof = transcript.getSynonymousExonPosition();
                require(proof != null && proof.matches(candidate.getObservation().getKey(), transcript.getVersionedTranscriptId()),
                        "SYNONYMOUS_POSITION_IDENTITY_MISMATCH", "transcript.synonymousExonPosition");
            }
            String reason = new IntronicSplicePolicy(mapper).blocker(transcript);
            require(reason == null, reason, "transcript.hgvsc");
            require("116.0".equals(transcript.getVersion()) && transcript.getTimestamp() != null,
                    "VEP_RESOURCE_IDENTITY_MISMATCH", "transcript.annotationVersion/timestamp");
        } catch (DispatchBlocked invalid) { blockers.add(blocker(invalid.reason, invalid.field)); }
        catch (RuntimeException invalid) { blockers.add(blocker("DISPATCH_INPUT_OR_PROVIDER_FAILED", "context")); }
        var input = transcriptInput(transcript).put("predictionPath", IntronicSplicePolicy.predictionPath(transcript))
                .put("intronicSignedOffset", transcript.getBp7IntronicSignedOffset())
                .put("positionSource", IntronicSplicePolicy.synonymous(transcript) ? "VEP116_NEAREST_EXON_JB_SYNONYMOUS_V1" : "PERSISTED_VEP_SELECTED_TRANSCRIPT_HGVSC")
                .put("spliceAiTranscriptIdentityClaimed", false);
        if (transcript != null && transcript.getSynonymousExonPosition() != null)
            input.set("synonymousExonPosition", mapper.valueToTree(transcript.getSynonymousExonPosition()));
        return result(selected, selected.original().context(), selected.selection(), List.of(), List.of(), input, blockers, 0, 0);
    }
    private void finishSplice(List<CandidateDispatch> rows, List<PendingSplice> pending, AnalysisContext analysis) {
        finishSplice(rows, pending, analysis, null);
    }
    private void finishSplice(List<CandidateDispatch> rows, List<PendingSplice> pending, AnalysisContext analysis,
            ReviewedPredictionObservationRouter.Session session) {
        if (pending.isEmpty()) return;
        SpliceAiBusinessRuntime runtime = null;
        SpliceAiRawBatchRunner.BatchResult batch = null;
        try {
            if (session != null) { runtime = session.spliceRuntime(); batch = session.spliceBatch(); }
            else { runtime = spliceRuntime.get(); batch = runtime.compute(pending.stream().map(item -> item.input()).toList()); }
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (RuntimeException unavailable) { /* isolated below, BA1 and AM remain available */ }
        boolean first = true;
        for (var p : pending) {
            var a = p.prepared(); var blockers = new ArrayList<>(a.blockers());
            List<ObjectNode> atomic = List.of(), evaluations = List.of();
            int calls = 0;
            try {
                if (runtime == null || batch == null) throw new IllegalArgumentException("SPLICEAI_RAW_RUNTIME_UNAVAILABLE");
                if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("PREDICTION_BUNDLE_INTERRUPTED");
                var output = runtime.evaluate(p.input(), analysis, batch, p.clauses());
                atomic = output.atomic(); evaluations = output.evaluations();
                calls = (p.clauses().stream().anyMatch(id -> !id.endsWith(":BP7")) ? 1 : 0) + (p.clauses().contains("ACMG_AMP_2015:BP7") ? 1 : 0);
            } catch (RuntimeException failed) {
                String code = Thread.currentThread().isInterrupted() ? "PREDICTION_BUNDLE_INTERRUPTED"
                        : runtime == null || batch == null ? "SPLICEAI_RAW_RUNTIME_UNAVAILABLE" : "SPLICEAI_EVIDENCE_OR_EVALUATION_INVALID";
                blockers.add(blocker(code, "spliceai"));
            }
            var input = a.transcriptInput();
            input.put("rawBatchReused", !first);
            if (runtime != null) input.set("rawBatchLineage", runtime.batchLineage());
            var values = new ArrayList<>(rows.get(p.row()).attempts());
            values.set(p.attempt(), result(a.preflight(), a.context(), a.selection(), atomic, evaluations, input, blockers,
                    first && batch != null && session == null ? 1 : 0, calls));
            rows.set(p.row(), new CandidateDispatch(rows.get(p.row()).candidateId(), values)); first = false;
        }
    }

    // Package-local for blocked synthetic policy tests. The public entry point always regenerates preflight.
    DispatchAttempt execute(CSpecExecutionPreflight.SelectionAttempt selected, VariantInterpretation candidate,
                            AnalysisContext analysis, ObjectNode pairing, Blocker association) {
        return execute(selected, candidate, analysis, pairing, association, Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4"), false);
    }
    private DispatchAttempt execute(CSpecExecutionPreflight.SelectionAttempt selected, VariantInterpretation candidate,
            AnalysisContext analysis, ObjectNode pairing, Blocker association, Set<String> clauses, boolean masked) {
        List<Blocker> blockers = new ArrayList<>(selected.blockers());
        ObjectNode context = selected.original().context();
        ObjectNode selection = selected.selection();
        ObjectNode transcriptInput = null;
        List<ObjectNode> atomic = List.of();
        List<ObjectNode> evaluations = List.of();
        int[] providerCalls = {0};
        int evaluatorCalls = 0;
        if (association != null) blockers.add(association);
        if (clauses.isEmpty()) return result(selected, context, selection, atomic, evaluations,
                mapper.createObjectNode().put("predictionPath", "ALPHAMISSENSE_MISSENSE"), blockers, 0, 0);
        if (!blockers.isEmpty()) return result(selected, context, selection, atomic, evaluations, null, blockers, 0, 0);
        try {
            gate(selected, pairing);
            require(context.at("/variant/evidenceQueryAllele/reference").asText().matches("[ACGT]")
                            && context.at("/variant/evidenceQueryAllele/alternate").asText().matches("[ACGT]"),
                    "ALPHAMISSENSE_VARIANT_TYPE_UNSUPPORTED", "variant.evidenceQueryAllele");
            require(binding.getProvider().equals(provider.source()) && binding.getProviderVersion().equals(provider.version()),
                    "ALPHAMISSENSE_PROVIDER_BINDING_MISMATCH", "provider/source/version");
            var planned = planner.plan(analysis, List.of(candidate), 1, List.of(selected.original().seed())).candidates().get(0);
            require(planned.blockers().isEmpty() && planned.readyContexts().size() == 1
                            && planned.readyContexts().get(0).equals(context),
                    "RANKED_CANDIDATE_CONTEXT_MISMATCH", "candidate/context");
            TranscriptConsequence consequence = wiring.selectedVepConsequence(context, candidate);
            if (consequence == null) { throw new DispatchBlocked("SELECTED_TRANSCRIPT_ANNOTATION_NOT_UNIQUE", "transcript"); }
            require(!(Hnf1aRevelRuntime.matchesContext(context) && IntronicSplicePolicy.missense(consequence)),
                    "HNF1A_REVEL_POLICY_REQUIRED", "context/predictionPolicy");
            transcriptInput = transcriptInput(consequence);
            verifyTranscriptIdentity(context, consequence);
            String substitution = consequence.getAminoAcidChange();
            require(substitution == null || (substitution.matches("[ACDEFGHIKLMNPQRSTVWY][1-9][0-9]*[ACDEFGHIKLMNPQRSTVWY]")
                            && substitution.charAt(0) != substitution.charAt(substitution.length() - 1)),
                    "PROTEIN_SUBSTITUTION_UNRESOLVED", "transcript.aminoAcidChange");
            // Missing protein fields remain missing; the existing provider emits its NOT_RUN axes.
            atomic = wiring.annotateVariantProvider(context, countedProvider(providerCalls), candidate, analysis, binding);
            if (masked) require(!Thread.currentThread().isInterrupted(), "PREDICTION_BUNDLE_INTERRUPTED", "provider");
            require(atomic.size() == 1 && context.path("contextId").equals(atomic.get(0).path("contextId")),
                    "ATOMIC_EVIDENCE_INSTANCE_INVALID", "atomicEvidence");
            evaluatorCalls++;
            evaluations = masked ? new AlphaMissenseCriterionEvaluator(mapper).evaluateClauses(context, atomic.get(0), profile.deepCopy(), rules.deepCopy(), selected.selectionId(), clauses)
                    : evaluator.evaluate(context, atomic.get(0), profile.deepCopy(), rules.deepCopy(), selected.selectionId());
            require(evaluations != null && evaluations.size() == clauses.size()
                            && evaluations.stream().allMatch(value -> context.path("contextId").equals(value.path("contextId"))
                            && selection.path("selectionId").equals(value.at("/ruleBinding/cspecSelectionId"))),
                    "CRITERION_EVALUATION_INSTANCE_INVALID", "criterionEvaluations");
        } catch (DispatchBlocked failure) {
            blockers.add(blocker(failure.reason, failure.field));
            if ("ATOMIC_EVIDENCE_INSTANCE_INVALID".equals(failure.reason)) atomic = List.of();
            if ("CRITERION_EVALUATION_INSTANCE_INVALID".equals(failure.reason)) evaluations = List.of();
        } catch (RuntimeException failure) {
            // Never manufacture an evaluation or expose local exception paths in an export.
            blockers.add(blocker(evaluatorCalls > 0 ? "EVALUATOR_INSTANCE_FAILED" : "DISPATCH_INPUT_OR_PROVIDER_FAILED",
                    evaluatorCalls > 0 ? "criterionEvaluations" : "context/provider"));
            evaluations = List.of();
        }
        return result(selected, context, selection, atomic, evaluations, transcriptInput, blockers, providerCalls[0], evaluatorCalls);
    }

    private void gate(CSpecExecutionPreflight.SelectionAttempt selected, ObjectNode pairing) {
        gate(selected, pairing, profile);
    }
    private void gate(CSpecExecutionPreflight.SelectionAttempt selected, ObjectNode pairing, ObjectNode profile) {
        ObjectNode selection = selected.selection();
        ObjectNode context = selected.original().context();
        if (selection == null) { throw new DispatchBlocked("FORMAL_CSPEC_SELECTION_REQUIRED", "selection"); }
        require("ZERO_MATCH".equals(selected.outcome()) && "ZERO_MATCH".equals(selection.path("outcome").asText())
                        && "EVALUATE_WITH_GENERAL_RULES".equals(selected.constraints().path("criterionEvaluationConstraint").asText()),
                "CSPEC_GENERAL_ALPHAMISSENSE_DISPATCH_FORBIDDEN", "selection.outcome");
        adapter.validateCompleteContext(context);
        require(selected.selectionId().equals(preflight.selectionIdentity(selection))
                        && selected.selectionId().equals(selection.path("selectionId").asText())
                        && context.path("contextId").equals(selection.path("contextId"))
                        && selection.path("candidateOverlayIds").isArray() && selection.path("candidateOverlayIds").isEmpty()
                        && "NOT_REQUIRED".equals(selection.at("/review/status").asText())
                        && "NONE".equals(selection.at("/conflict/status").asText()),
                "CSPEC_SELECTION_IDENTITY_MISMATCH", "selection");
        require(pairing != null && pairing.path("overlayRegistryId").equals(selection.path("overlayRegistryId"))
                        && pairing.path("overlayRegistrySha256").equals(selection.path("overlayRegistrySha256"))
                        && pairing.path("ruleRegistryId").equals(profile.at("/registryBinding/registryId"))
                        && pairing.path("ruleRegistrySha256").equals(profile.at("/registryBinding/registrySha256"))
                        && pairing.at("/executionSlice/profileCanonicalSha256").asText().equals(adapter.canonicalSha256(profile)),
                "CSPEC_EXECUTION_SLICE_PAIRING_MISMATCH", "registryPairing");
    }

    private void verifyTranscriptIdentity(ObjectNode context, TranscriptConsequence annotation) {
        String accession = context.at("/transcript/accession").asText();
        String selected = accession + "." + context.at("/transcript/version").asInt();
        String coding = annotation.getHgvsc();
        String codingIdentity = coding == null || !coding.contains(":") ? null : coding.substring(0, coding.indexOf(':'));
        // Apply the assembler's existing identity consistency rule again to the supplied candidate.
        for (String identity : new String[]{annotation.getTranscriptAccession(), annotation.getTranscriptId(), codingIdentity}) {
            require(identity == null || identity.isBlank() || identity.equals(accession) || identity.equals(selected),
                    "TRANSCRIPT_IDENTITY_CONFLICT", "transcript");
        }
    }

    private AlphaMissenseProvider countedProvider(int[] calls) {
        return new AlphaMissenseProvider(new VariantAnalysisProperties()) {
            @Override public String source() { return provider.source(); }
            @Override public String version() { return provider.version(); }
            @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotateSelectedTranscripts(
                    List<VariantInterpretation> variants, AnalysisContext analysis, Map<NormalizedVariantKey, TranscriptConsequence> selected) {
                calls[0]++;
                return provider.annotateSelectedTranscripts(variants, analysis, selected);
            }
        };
    }

    private ObjectNode transcriptInput(TranscriptConsequence selected) {
        return transcriptInput(mapper, selected);
    }
    static ObjectNode transcriptInput(ObjectMapper mapper, TranscriptConsequence selected) {
        ObjectNode value = mapper.createObjectNode();
        value.put("predictionPath", "ALPHAMISSENSE_MISSENSE");
        value.put("source", selected.getSource()).put("annotationVersion", selected.getVersion())
                .put("annotationTimestamp", selected.getTimestamp() == null ? null : selected.getTimestamp().toString())
                .put("geneSymbol", selected.getGeneSymbol()).put("geneId", selected.getGeneId())
                .put("versionedTranscriptId", selected.getVersionedTranscriptId()).put("hgvsc", selected.getHgvsc())
                .put("hgvsp", selected.getHgvsp()).put("vepProteinIdentity", selected.getProteinIdentity())
                .put("aminoAcidChange", selected.getAminoAcidChange()).put("consequence", selected.getConsequence())
                .put("proteinIdentityMappingStatus", "NOT_ESTABLISHED");
        return value;
    }
    private static void require(boolean condition, String reason, String field) { if (!condition) throw new DispatchBlocked(reason, field); }
    private static Blocker blocker(String reason, String field) { return new Blocker("ALPHAMISSENSE_DISPATCH_BLOCKED", reason, field); }
    private static DispatchAttempt result(CSpecExecutionPreflight.SelectionAttempt preflight, ObjectNode context, ObjectNode selection,
                                          List<ObjectNode> atomic, List<ObjectNode> evaluations, ObjectNode transcriptInput,
                                          List<Blocker> blockers, int providerCalls, int evaluatorCalls) {
        return new DispatchAttempt(preflight, context, selection, atomic, evaluations, transcriptInput, blockers, providerCalls, evaluatorCalls);
    }
    private static ObjectNode copy(ObjectNode value) { return value == null ? null : value.deepCopy(); }
    private static List<ObjectNode> copies(List<ObjectNode> values) { return values.stream().map((ObjectNode value) -> value.deepCopy()).toList(); }

    public record DispatchAttempt(CSpecExecutionPreflight.SelectionAttempt preflight, ObjectNode context, ObjectNode selection,
                                  List<ObjectNode> atomicEvidence, List<ObjectNode> criterionEvaluations, ObjectNode transcriptInput,
                                  List<Blocker> blockers, int providerCalls, int evaluatorCalls) {
        public DispatchAttempt {
            context = copy(context); selection = copy(selection); transcriptInput = copy(transcriptInput);
            atomicEvidence = copies(atomicEvidence); criterionEvaluations = copies(criterionEvaluations); blockers = List.copyOf(blockers);
        }
        @Override public ObjectNode context() { return copy(context); }
        @Override public ObjectNode selection() { return copy(selection); }
        @Override public ObjectNode transcriptInput() { return copy(transcriptInput); }
        @Override public List<ObjectNode> atomicEvidence() { return copies(atomicEvidence); }
        @Override public List<ObjectNode> criterionEvaluations() { return copies(criterionEvaluations); }
    }
    public record CandidateDispatch(String candidateId, List<DispatchAttempt> attempts) {
        public CandidateDispatch { attempts = List.copyOf(attempts); }
    }
    public record DispatchResult(CSpecExecutionPreflight.BatchResult preflight, List<CandidateDispatch> candidates,
                                 int providerCalls, int evaluatorCalls) {
        public DispatchResult { candidates = List.copyOf(candidates); }
    }
    @FunctionalInterface
    interface CriterionCall {
        List<ObjectNode> evaluate(JsonNode context, ObjectNode atomic, ObjectNode profile, ObjectNode registry, String selectionId);
    }
    private static final class DispatchBlocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;
        private final String field;
        private DispatchBlocked(String reason, String field) { this.reason = reason; this.field = field; }
    }
}
