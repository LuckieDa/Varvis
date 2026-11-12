package org.nbscn.varvis.variantanalysis.service.impl;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkFailureCode;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeRescueSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.VariantEvidenceProvider;
import org.nbscn.varvis.variantanalysis.ranking.VariantRankingService;
import org.nbscn.varvis.variantanalysis.service.AcmgReadyEvidenceMapper;
import org.nbscn.varvis.variantanalysis.service.BasicVariantAnnotator;
import org.nbscn.varvis.variantanalysis.service.Grch37EvidenceProjectionService;
import org.nbscn.varvis.variantanalysis.service.PhenotypePriorGeneIntervalService;
import org.nbscn.varvis.variantanalysis.service.TranscriptAnnotationService;
import org.nbscn.varvis.variantanalysis.service.VariantInterpretationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class VariantInterpretationServiceImpl implements VariantInterpretationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantInterpretationServiceImpl.class);
    private static final int PROVIDER_QUERY_BATCH_SIZE = 25000;
    private static final int MATERIALIZED_SKIP_EVIDENCE_LIMIT = 50000;
    private static final List<String> NO_OBSERVATION_REASONS = Collections.singletonList(
            "LARGE_FILE_GATE_NO_OBSERVATION: no QC observation was available");
    private static final List<String> NOT_CARRIED_REASONS = Collections.singletonList(
            "LARGE_FILE_GATE_NOT_CARRIED_ALT: selected sample genotype is 0/0");
    private static final List<String> QC_FILTERED_REASONS = Collections.singletonList(
            "LARGE_FILE_GATE_QC_FAILED: FILTER, DP, GQ, or allele balance failed the configured QC gate");
    private static final List<String> COMMON_REASONS = Collections.singletonList(
            "LARGE_FILE_GATE_COMMON_AF: available population frequency reaches the rare-disease threshold");
    private static final List<String> LOW_PRIORITY_REASONS = Collections.singletonList(
            "LARGE_FILE_GATE_LOW_PRIORITY_CONSEQUENCE: no high-impact, protein-changing, splice, or clinical signal was available before expensive annotation");
    private static final List<String> CANDIDATE_REASONS = Collections.singletonList(
            "LARGE_FILE_GATE_CANDIDATE: retained for standard annotation");

    private final BasicVariantAnnotator basicAnnotator;
    private final List<VariantEvidenceProvider> providers;
    private final VariantRankingService rankingService;
    private final TranscriptAnnotationService transcriptAnnotationService;
    private final AcmgReadyEvidenceMapper acmgReadyEvidenceMapper;
    private final PhenotypePriorGeneIntervalService phenotypePriorGeneIntervalService;
    private final Grch37EvidenceProjectionService grch37EvidenceProjectionService;
    private final VariantAnalysisProperties properties;

    @Autowired
    public VariantInterpretationServiceImpl(BasicVariantAnnotator basicAnnotator,
                                            List<VariantEvidenceProvider> providers,
                                            VariantRankingService rankingService,
                                            TranscriptAnnotationService transcriptAnnotationService,
                                            AcmgReadyEvidenceMapper acmgReadyEvidenceMapper,
                                            VariantAnalysisProperties properties,
                                            PhenotypePriorGeneIntervalService phenotypePriorGeneIntervalService,
                                            Grch37EvidenceProjectionService grch37EvidenceProjectionService) {
        this.basicAnnotator = basicAnnotator;
        this.providers = providers;
        this.rankingService = rankingService;
        this.transcriptAnnotationService = transcriptAnnotationService;
        this.acmgReadyEvidenceMapper = acmgReadyEvidenceMapper;
        this.phenotypePriorGeneIntervalService = phenotypePriorGeneIntervalService;
        this.grch37EvidenceProjectionService = grch37EvidenceProjectionService;
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
    }

    public VariantInterpretationServiceImpl(BasicVariantAnnotator basicAnnotator,
                                            List<VariantEvidenceProvider> providers,
                                            VariantRankingService rankingService,
                                            TranscriptAnnotationService transcriptAnnotationService,
                                            AcmgReadyEvidenceMapper acmgReadyEvidenceMapper,
                                            VariantAnalysisProperties properties) {
        this(
                basicAnnotator,
                providers,
                rankingService,
                transcriptAnnotationService,
                acmgReadyEvidenceMapper,
                properties,
                null,
                null
        );
    }

    public VariantInterpretationServiceImpl(BasicVariantAnnotator basicAnnotator,
                                            List<VariantEvidenceProvider> providers,
                                            VariantRankingService rankingService,
                                            TranscriptAnnotationService transcriptAnnotationService,
                                            AcmgReadyEvidenceMapper acmgReadyEvidenceMapper,
                                            VariantAnalysisProperties properties,
                                            PhenotypePriorGeneIntervalService phenotypePriorGeneIntervalService) {
        this(
                basicAnnotator,
                providers,
                rankingService,
                transcriptAnnotationService,
                acmgReadyEvidenceMapper,
                properties,
                phenotypePriorGeneIntervalService,
                null
        );
    }

    public VariantInterpretationServiceImpl(BasicVariantAnnotator basicAnnotator,
                                            List<VariantEvidenceProvider> providers,
                                            VariantRankingService rankingService,
                                            TranscriptAnnotationService transcriptAnnotationService) {
        this(basicAnnotator, providers, rankingService, transcriptAnnotationService, null,
                new VariantAnalysisProperties(), null, null);
    }

    public VariantInterpretationServiceImpl(BasicVariantAnnotator basicAnnotator,
                                            List<VariantEvidenceProvider> providers,
                                            VariantRankingService rankingService) {
        this(basicAnnotator, providers, rankingService, null, null,
                new VariantAnalysisProperties(), null, null);
    }

    @Override
    public List<VariantInterpretation> interpret(
            Path normalizedVcf,
            List<VariantQcObservation> observations,
            AnalysisContext context) {
        long started = System.nanoTime();
        List<VariantInterpretation> interpretations = basicAnnotator.annotate(normalizedVcf, observations);
        context.getBenchmarkTelemetry().observeBasicInterpretations(interpretations);
        started = logStage(context, "basic VCF INFO annotation", started);
        interpretations = applyLargeFileGate(interpretations, context);
        started = logStage(context, "large-file cheap gate", started);
        if (transcriptAnnotationService != null) {
            try {
                transcriptAnnotationService.annotate(transcriptAnnotationInput(interpretations, context), context);
            } catch (RuntimeException ex) {
                LOGGER.warn(
                        "Variant analysis job {} transcript annotation stage failed; continuing with base analysis: {}",
                        context.getAnalysisId(),
                        ex.getMessage()
                );
            }
            started = logStage(context, "transcript annotation (ANN fallback / VEP readiness)", started);
        }
        started = enrichConfiguredEvidence(interpretations, context, started);
        if (grch37EvidenceProjectionService != null) {
            grch37EvidenceProjectionService.apply(interpretations, context);
            started = logStage(context, "GRCh37 candidate evidence projection", started);
        }
        rankingService.rank(interpretations);
        started = logStage(context, "transparent ranking", started);
        applyPostRankingBuckets(interpretations, context);
        if (acmgReadyEvidenceMapper != null) {
            List<VariantInterpretation> acmgReadyInput = acmgReadyInput(interpretations, context);
            if (acmgReadyInput.size() < interpretations.size()) {
                LOGGER.info(
                        "Variant analysis job {} skipped ACMG-ready suggestions for {} large-file summary-only variants",
                        context.getAnalysisId(),
                        interpretations.size() - acmgReadyInput.size()
                );
            }
            acmgReadyEvidenceMapper.apply(acmgReadyInput);
            logStage(context, "ACMG-ready evidence suggestions", started);
        }
        return interpretations;
    }

    @Override
    public List<VariantInterpretation> enrichEvidenceOnly(
            List<VariantInterpretation> interpretations,
            AnalysisContext context) {
        if (interpretations == null || interpretations.isEmpty()) {
            return interpretations == null ? Collections.<VariantInterpretation>emptyList() : interpretations;
        }
        AnalysisContext effectiveContext = context == null
                ? new AnalysisContext("engine-evidence", GenomeAssembly.GRCh38, AnalysisMode.WES)
                : context;
        enrichConfiguredEvidence(interpretations, effectiveContext, System.nanoTime());
        // Zero-pass additions do not change the existing candidates' percentile population or scores.
        // Their raw zero score and deterministic appended order remain below the original candidates.
        var originalRows = interpretations.stream().filter(v -> v.getExomiserCandidateFeatures() == null
                || !"result.jsonl".equals(v.getExomiserCandidateFeatures().getVariantArtifactName())).toList();
        if (originalRows.size() == interpretations.size()) applyEnginePrimaryCompositeRanking(interpretations);
        else {
            var originals = new ArrayList<>(originalRows);
            applyEnginePrimaryCompositeRanking(originals);
            var additions = interpretations.stream().filter(v -> v.getExomiserCandidateFeatures() != null
                    && "result.jsonl".equals(v.getExomiserCandidateFeatures().getVariantArtifactName())).toList();
            interpretations = new ArrayList<>(originals); interpretations.addAll(additions);
        }
        return interpretations;
    }

    /**
     * Reuses the established local-evidence scoring rules without calling the legacy
     * phenotype pipeline. The final sortable score is percentile based so the two
     * incompatible score scales are never added directly. Exomiser remains primary.
     */
    private void applyEnginePrimaryCompositeRanking(List<VariantInterpretation> interpretations) {
        if (rankingService == null || interpretations == null || interpretations.isEmpty()) {
            return;
        }
        Map<VariantInterpretation, Integer> sourceRowOrders =
                new IdentityHashMap<VariantInterpretation, Integer>();
        for (int index = 0; index < interpretations.size(); index++) {
            sourceRowOrders.put(interpretations.get(index), index + 1);
        }
        Map<VariantInterpretation, EngineRankSnapshot> engineRanks =
                snapshotEngineRanks(interpretations, sourceRowOrders);
        rankingService.rank(interpretations);
        Map<VariantInterpretation, Integer> localEvidenceRanks =
                ranksByCurrentScore(interpretations, sourceRowOrders);
        double engineWeight = properties.getExomiser().normalizedEnginePrimaryWeight();
        double evidenceWeight = 1.0d - engineWeight;
        int count = interpretations.size();
        for (VariantInterpretation interpretation : interpretations) {
            EngineRankSnapshot engine = engineRanks.get(interpretation);
            int engineRank = engine == null ? count : engine.rank;
            int evidenceRank = localEvidenceRanks.get(interpretation);
            double enginePercentile = rankPercentile(engineRank, count);
            double evidencePercentile = rankPercentile(evidenceRank, count);
            double composite = engineWeight * enginePercentile + evidenceWeight * evidencePercentile;

            Map<String, Double> breakdown = new LinkedHashMap<String, Double>(interpretation.getScoreBreakdown());
            breakdown.put("exomiserRawScore", engine == null ? null : engine.rawScore);
            breakdown.put("exomiserRawRank", (double) engineRank);
            breakdown.put("exomiserRankPercentile", enginePercentile);
            breakdown.put("localEvidenceRankPercentile", evidencePercentile);
            breakdown.put("exomiserPrimaryContribution", engineWeight * enginePercentile);
            breakdown.put("localEvidenceContribution", evidenceWeight * evidencePercentile);
            breakdown.put("engineEvidenceComposite", composite);

            Map<String, org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent> components =
                    new LinkedHashMap<String, org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent>(
                            interpretation.getScoreComponents());
            components.put("exomiser_primary", new org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent(
                    engineWeight * enginePercentile,
                    Collections.singletonList("Derived candidate-row order " + engineRank
                            + " by Exomiser variant score retains "
                            + Math.round(engineWeight * 100.0d)
                            + "% of the composite ordering; this is not a native global variant rank.")));
            components.put("local_evidence", new org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent(
                    evidenceWeight * evidencePercentile,
                    Collections.singletonList("Configured ClinVar, gnomAD, CADD, and splice evidence contributes "
                            + Math.round(evidenceWeight * 100.0d) + "% without replacing Exomiser output.")));

            List<String> rationale = new ArrayList<String>(interpretation.getRationale());
            rationale.add(0, "Composite order: derived Exomiser-score candidate order "
                    + engineRank + " plus configured local evidence; native #RANK remains the gene/MOI group rank.");
            interpretation.applyRanking(
                    interpretation.getTier(),
                    composite,
                    breakdown,
                    components,
                    interpretation.getTierReason(),
                    interpretation.getPositiveReasons(),
                    interpretation.getNegativeReasons(),
                    rationale,
                    interpretation.getWarnings(),
                    interpretation.getFilterReasons()
            );
        }
    }

    private Map<VariantInterpretation, EngineRankSnapshot> snapshotEngineRanks(
            List<VariantInterpretation> interpretations,
            Map<VariantInterpretation, Integer> sourceRowOrders) {
        Map<VariantInterpretation, EngineRankSnapshot> snapshots = new IdentityHashMap<VariantInterpretation, EngineRankSnapshot>();
        List<VariantInterpretation> ordered = new ArrayList<VariantInterpretation>(interpretations);
        Collections.sort(ordered, engineRankComparator(sourceRowOrders));
        for (int index = 0; index < ordered.size(); index++) {
            VariantInterpretation interpretation = ordered.get(index);
            snapshots.put(interpretation, new EngineRankSnapshot(index + 1, interpretation.getRankingScore()));
        }
        return snapshots;
    }

    private Map<VariantInterpretation, Integer> ranksByCurrentScore(
            List<VariantInterpretation> interpretations,
            Map<VariantInterpretation, Integer> sourceRowOrders) {
        List<VariantInterpretation> ordered = new ArrayList<VariantInterpretation>(interpretations);
        Collections.sort(ordered, currentRankingComparator(sourceRowOrders));
        Map<VariantInterpretation, Integer> ranks = new IdentityHashMap<VariantInterpretation, Integer>();
        for (int index = 0; index < ordered.size(); index++) {
            ranks.put(ordered.get(index), index + 1);
        }
        return ranks;
    }

    private double engineSortScore(VariantInterpretation interpretation) {
        Double score = interpretation == null ? null : interpretation.getRankingScore();
        return score == null ? Double.NEGATIVE_INFINITY : score;
    }

    private Comparator<VariantInterpretation> engineRankComparator(
            final Map<VariantInterpretation, Integer> sourceRowOrders) {
        return new Comparator<VariantInterpretation>() {
            @Override
            public int compare(VariantInterpretation left, VariantInterpretation right) {
                int byScore = Double.compare(engineSortScore(right), engineSortScore(left));
                if (byScore != 0) {
                    return byScore;
                }
                int byVariant = left.getVariantId().compareTo(right.getVariantId());
                return byVariant != 0 ? byVariant : Integer.compare(
                        sourceRowOrder(sourceRowOrders, left),
                        sourceRowOrder(sourceRowOrders, right));
            }
        };
    }

    private Comparator<VariantInterpretation> currentRankingComparator(
            final Map<VariantInterpretation, Integer> sourceRowOrders) {
        return new Comparator<VariantInterpretation>() {
            @Override
            public int compare(VariantInterpretation left, VariantInterpretation right) {
                int byScore = Double.compare(rankingSortScore(right), rankingSortScore(left));
                if (byScore != 0) {
                    return byScore;
                }
                int byVariant = left.getVariantId().compareTo(right.getVariantId());
                return byVariant != 0 ? byVariant : Integer.compare(
                        sourceRowOrder(sourceRowOrders, left),
                        sourceRowOrder(sourceRowOrders, right));
            }
        };
    }

    private int sourceRowOrder(Map<VariantInterpretation, Integer> sourceRowOrders,
                               VariantInterpretation interpretation) {
        Integer value = sourceRowOrders.get(interpretation);
        if (value == null) {
            throw new IllegalStateException("VARIANT_RANKING_SOURCE_ROW_ORDER_MISSING");
        }
        return value.intValue();
    }

    private double rankingSortScore(VariantInterpretation interpretation) {
        Double score = interpretation == null ? null : interpretation.getRankingScore();
        return score == null ? Double.NEGATIVE_INFINITY : score;
    }

    private double rankPercentile(int rank, int count) {
        return count <= 1 ? 1.0d : 1.0d - ((Math.max(1, rank) - 1.0d) / (count - 1.0d));
    }

    private static class EngineRankSnapshot {
        private final int rank;
        private final Double rawScore;

        private EngineRankSnapshot(int rank, Double rawScore) {
            this.rank = rank;
            this.rawScore = rawScore;
        }
    }

    private long enrichConfiguredEvidence(List<VariantInterpretation> interpretations,
                                          AnalysisContext context,
                                          long started) {
        for (VariantEvidenceProvider provider : orderedProviders()) {
            EvidenceQueryPlan queryPlan = queryPlan(provider, interpretations, context);
            annotateProvider(provider, queryPlan.queryVariants, context);
            started = logStage(context, provider.source() + " evidence (queried "
                    + queryPlan.queryVariants.size() + ", skipped "
                    + queryPlan.skipReasons.size() + ")", started);
            if (queryPlan.skipReasons.size() > MATERIALIZED_SKIP_EVIDENCE_LIMIT) {
                LOGGER.info(
                        "Variant analysis job {} skipped materializing {} {} pre-screen evidence items to reduce memory pressure",
                        context.getAnalysisId(),
                        queryPlan.skipReasons.size(),
                        provider.source()
                );
                continue;
            }
            for (VariantInterpretation interpretation : interpretations) {
                if (queryPlan.skipReasons.containsKey(interpretation)) {
                    List<EvidenceItem> evidence = Collections.singletonList(skipEvidence(
                            provider,
                            queryPlan.skipReasons.get(interpretation)
                    ));
                    interpretation.addEvidence(evidence);
                }
            }
        }
        return started;
    }

    private List<VariantInterpretation> applyLargeFileGate(
            List<VariantInterpretation> interpretations,
            AnalysisContext context) {
        if (!largeFileMode(context) || interpretations == null || interpretations.isEmpty()) {
            recordBenchmarkGateWithoutLargeFileMode(interpretations, context);
            return interpretations;
        }
        List<VariantInterpretation> gateInput = interpretations;
        PhenotypePriorGeneIntervalService.RescueEvaluation rescueEvaluation =
                phenotypePriorRescueEvaluation(interpretations, context);
        Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> rescueMatches =
                rescueEvaluation.getMatches();
        int rescueRejectedByHardGateCount;
        int candidateLimit = properties.getLargeFile().normalizedCandidateHardLimit();
        if (shouldCompactLargeFileInput(interpretations, candidateLimit)) {
            CompactLargeFileGateResult compactResult = compactLargeFileCandidates(
                    interpretations,
                    rescueMatches,
                    candidateLimit
            );
            interpretations = compactResult.interpretations;
            rescueRejectedByHardGateCount = compactResult.rescueRejectedByHardGateCount;
        } else {
            rescueRejectedByHardGateCount = 0;
            for (VariantInterpretation interpretation : interpretations) {
                LargeFileClassification classification = cheapGateClassification(
                        interpretation,
                        rescueMatches.get(interpretation)
                );
                applyLargeFileClassification(interpretation, classification);
                if (rescueMatches.containsKey(interpretation)
                        && !VariantAnalysisBucket.CANDIDATE.equals(classification.bucket)) {
                    rescueRejectedByHardGateCount++;
                }
            }
            applyCandidateHardLimit(interpretations, rescueMatches);
        }
        for (VariantInterpretation interpretation : interpretations) {
            addPhenotypePriorRescueReason(interpretation, rescueMatches.get(interpretation));
        }
        logPhenotypePriorRescueTelemetry(context, rescueEvaluation, rescueRejectedByHardGateCount,
                rescuedSelectedCount(interpretations, rescueMatches));
        context.setPhenotypeRescueSummary(PhenotypeRescueSummary.from(
                rescueEvaluation,
                rescuedSelectedCount(interpretations, rescueMatches),
                rescueRejectedByHardGateCount));
        recordBenchmarkGateTelemetry(gateInput, interpretations, rescueMatches, context, candidateLimit);
        applyExpensiveAnnotationLimit(interpretations);
        return interpretations;
    }

    private void recordBenchmarkGateWithoutLargeFileMode(List<VariantInterpretation> interpretations,
                                                         AnalysisContext context) {
        BenchmarkTelemetryCollector telemetry = context == null ? null : context.getBenchmarkTelemetry();
        if (telemetry == null || !telemetry.isEnabled()) return;
        int count = interpretations == null ? 0 : interpretations.size();
        Map<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation> observations =
                new LinkedHashMap<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation>();
        if (interpretations != null) {
            for (VariantInterpretation interpretation : interpretations) {
                if (!telemetry.isTruth(interpretation)) continue;
                observations.put(interpretation.getObservation().getKey(),
                        new BenchmarkTelemetryCollector.GateObservation(
                                true, true, "LARGE_FILE_GATE_NOT_APPLICABLE", null,
                                "NOT_APPLICABLE", "NO_LARGE_FILE_GATE"));
            }
        }
        telemetry.observeCandidateGate(count, count, 0, 0, 0, 0, observations);
    }

    private void recordBenchmarkGateTelemetry(
            List<VariantInterpretation> gateInput,
            List<VariantInterpretation> retainedInterpretations,
            Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> rescueMatches,
            AnalysisContext context,
            int candidateLimit) {
        BenchmarkTelemetryCollector telemetry = context == null ? null : context.getBenchmarkTelemetry();
        if (telemetry == null || !telemetry.isEnabled()) return;
        Set<NormalizedVariantKey> retained = candidateKeys(retainedInterpretations);
        Set<NormalizedVariantKey> baselineRetained = baselineCandidateKeys(gateInput, candidateLimit);
        int eligible = 0;
        Map<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation> truthObservations =
                new LinkedHashMap<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation>();
        for (VariantInterpretation interpretation : gateInput) {
            LargeFileClassification classification = cheapGateClassification(
                    interpretation, rescueMatches.get(interpretation));
            boolean candidate = VariantAnalysisBucket.CANDIDATE.equals(classification.bucket);
            if (candidate) eligible++;
            if (!telemetry.isTruth(interpretation)) continue;
            NormalizedVariantKey key = interpretation.getObservation().getKey();
            boolean selected = retained.contains(key);
            boolean baselineSelected = baselineRetained.contains(key);
            String disposition = selected && !baselineSelected ? "RESCUED"
                    : baselineSelected && !selected ? "DISPLACED"
                    : rescueMatches.containsKey(interpretation) ? "RETAINED_WITH_PHENOTYPE_PRIORITY"
                    : "UNCHANGED";
            truthObservations.put(key, new BenchmarkTelemetryCollector.GateObservation(
                    candidate,
                    selected,
                    firstReason(classification.reasons),
                    gateFailureCode(classification.bucket, candidate && !selected),
                    disposition,
                    "PRODUCTION_COMPARATOR_COUNTERFACTUAL_WITHOUT_PHENOTYPE_RESCUE"
            ));
        }
        int retainedCount = candidateCount(retainedInterpretations);
        int rescued = differenceSize(retained, baselineRetained);
        int displaced = differenceSize(baselineRetained, retained);
        telemetry.observeCandidateGate(eligible, retainedCount, Math.max(0, eligible - retainedCount),
                rescued, displaced, candidateLimit, truthObservations);
    }

    private Set<NormalizedVariantKey> candidateKeys(List<VariantInterpretation> interpretations) {
        Set<NormalizedVariantKey> keys = new LinkedHashSet<NormalizedVariantKey>();
        if (interpretations == null) return keys;
        for (VariantInterpretation interpretation : interpretations) {
            if (interpretation != null && interpretation.getObservation() != null
                    && interpretation.getObservation().getKey() != null
                    && VariantAnalysisBucket.CANDIDATE.equals(interpretation.getAnalysisBucket())) {
                keys.add(interpretation.getObservation().getKey());
            }
        }
        return keys;
    }

    private int candidateCount(List<VariantInterpretation> interpretations) {
        int count = 0;
        if (interpretations != null) {
            for (VariantInterpretation interpretation : interpretations) {
                if (interpretation != null
                        && VariantAnalysisBucket.CANDIDATE.equals(interpretation.getAnalysisBucket())) {
                    count++;
                }
            }
        }
        return count;
    }

    private Set<NormalizedVariantKey> baselineCandidateKeys(List<VariantInterpretation> interpretations,
                                                            int limit) {
        List<IndexedVariant> candidates = new ArrayList<IndexedVariant>();
        if (interpretations != null) {
            for (VariantInterpretation interpretation : interpretations) {
                if (VariantAnalysisBucket.CANDIDATE.equals(
                        cheapGateClassification(interpretation, null).bucket)) {
                    candidates.add(new IndexedVariant(interpretation));
                }
            }
        }
        Collections.sort(candidates, candidateComparator(
                Collections.<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch>emptyMap()));
        int selected = limit <= 0 ? candidates.size() : Math.min(limit, candidates.size());
        Set<NormalizedVariantKey> keys = new LinkedHashSet<NormalizedVariantKey>();
        for (int index = 0; index < selected; index++) {
            VariantInterpretation interpretation = candidates.get(index).variant;
            if (interpretation.getObservation() != null) keys.add(interpretation.getObservation().getKey());
        }
        return keys;
    }

    private int differenceSize(Set<NormalizedVariantKey> left, Set<NormalizedVariantKey> right) {
        int count = 0;
        for (NormalizedVariantKey key : left) if (!right.contains(key)) count++;
        return count;
    }

    private String firstReason(List<String> reasons) {
        return reasons == null || reasons.isEmpty() ? "UNSPECIFIED_GATE_REASON" : reasons.get(0);
    }

    private String gateFailureCode(VariantAnalysisBucket bucket, boolean releasedByCap) {
        // F9 requires a retained, evaluable truth below the frozen ranking cutoff.
        // A pre-ranking hard-cap release has a precise loss stage but no matching frozen code.
        if (releasedByCap) return null;
        if (bucket == VariantAnalysisBucket.FILTERED_QC
                || bucket == VariantAnalysisBucket.FILTERED_NOT_CARRIED) return BenchmarkFailureCode.F3;
        if (bucket == VariantAnalysisBucket.FILTERED_COMMON) return BenchmarkFailureCode.F4;
        if (bucket == VariantAnalysisBucket.DEFERRED_NO_EXPENSIVE_ANNOTATION) return BenchmarkFailureCode.F5;
        return null;
    }

    private boolean shouldCompactLargeFileInput(
            List<VariantInterpretation> interpretations,
            int candidateLimit) {
        if (properties.getPersistence().isPersistSummaryOnlyInterpretations()
                || candidateLimit <= 0
                || interpretations == null) {
            return false;
        }
        long compactThreshold = Math.max(
                properties.getLargeFile().normalizedVariantThreshold(),
                (long) candidateLimit * 2L
        );
        return interpretations.size() > compactThreshold;
    }

    private CompactLargeFileGateResult compactLargeFileCandidates(
            List<VariantInterpretation> interpretations,
            Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> rescueMatches,
            int limit) {
        Comparator<IndexedVariant> bestFirst = candidateComparator(rescueMatches);
        PriorityQueue<IndexedVariant> retained = new PriorityQueue<IndexedVariant>(
                Math.max(1, limit),
                bestFirst.reversed()
        );
        int eligibleCandidateCount = 0;
        int rescueRejectedByHardGateCount = 0;
        for (VariantInterpretation interpretation : interpretations) {
            LargeFileClassification classification = cheapGateClassification(
                    interpretation,
                    rescueMatches.get(interpretation)
            );
            if (rescueMatches.containsKey(interpretation)
                    && !VariantAnalysisBucket.CANDIDATE.equals(classification.bucket)) {
                rescueRejectedByHardGateCount++;
            }
            if (!VariantAnalysisBucket.CANDIDATE.equals(classification.bucket)) {
                continue;
            }
            eligibleCandidateCount++;
            IndexedVariant indexed = new IndexedVariant(interpretation);
            if (retained.size() < limit) {
                retained.add(indexed);
            } else if (bestFirst.compare(indexed, retained.peek()) < 0) {
                retained.poll();
                retained.add(indexed);
            }
        }

        List<IndexedVariant> selected = new ArrayList<IndexedVariant>(retained);
        Collections.sort(selected, bestFirst);
        List<VariantInterpretation> compact = new ArrayList<VariantInterpretation>(selected.size());
        for (IndexedVariant indexed : selected) {
            VariantInterpretation interpretation = indexed.variant;
            applyLargeFileClassification(
                    interpretation,
                    cheapGateClassification(interpretation, rescueMatches.get(interpretation))
            );
            compact.add(interpretation);
        }
        LOGGER.info(
                "Large-file cheap gate retained {} of {} eligible candidates and released {} summary-only records before provider annotation",
                compact.size(),
                eligibleCandidateCount,
                Math.max(0, interpretations.size() - compact.size())
        );
        return new CompactLargeFileGateResult(compact, rescueRejectedByHardGateCount);
    }

    private void applyLargeFileClassification(
            VariantInterpretation interpretation,
            LargeFileClassification classification) {
        interpretation.applyLargeFileClassification(
                classification.bucket,
                true,
                classification.summaryOnly,
                classification.expensiveAnnotationSkipped,
                classification.expensiveAnnotationSkippedReason,
                classification.reasons
        );
    }

    private boolean largeFileMode(AnalysisContext context) {
        return context != null && context.isLargeFileMode() && properties.getLargeFile().isEnabled();
    }

    private PhenotypePriorGeneIntervalService.RescueEvaluation phenotypePriorRescueEvaluation(
            List<VariantInterpretation> interpretations,
            AnalysisContext context) {
        if (phenotypePriorGeneIntervalService == null) {
            return PhenotypePriorGeneIntervalService.RescueEvaluation.empty(
                    interpretations == null ? 0 : interpretations.size(),
                    "DISABLED",
                    "DISABLED",
                    context == null || context.getGenomeAssembly() == null
                            ? "UNRESOLVED"
                            : context.getGenomeAssembly().name(),
                    properties.getLargeFile().normalizedCandidateHardLimit()
            );
        }
        PhenotypePriorGeneIntervalService.RescueEvaluation evaluation =
                phenotypePriorGeneIntervalService.evaluateRescueMatches(interpretations, context);
        return evaluation == null
                ? PhenotypePriorGeneIntervalService.RescueEvaluation.empty(
                        interpretations == null ? 0 : interpretations.size(),
                        "UNKNOWN",
                        "NOT_EVALUATED",
                        context == null || context.getGenomeAssembly() == null
                                ? "UNRESOLVED"
                                : context.getGenomeAssembly().name(),
                        properties.getLargeFile().normalizedCandidateHardLimit()
                )
                : evaluation;
    }

    private void logPhenotypePriorRescueTelemetry(
            AnalysisContext context,
            PhenotypePriorGeneIntervalService.RescueEvaluation evaluation,
            int rescueRejectedByHardGateCount,
            int rescuedSelectedCount) {
        if (context == null || evaluation == null) {
            return;
        }
        LOGGER.info(
                "Variant analysis job {} phenotype-prior interval rescue: "
                        + "phenotypePriorStatus={}, phenotypePriorGeneCount={}, "
                        + "intervalResourceStatus={}, intervalSourceVersion={}, "
                        + "intervalMappedPriorGeneCount={}, rescueCandidateCount={}, "
                        + "rescuedSelectedCount={}, rescueRejectedByHardGateCount={}, "
                        + "rescueMissingIntervalGeneCount={}, effectiveAssembly={}, candidateCap={}, "
                        + "totalCandidates={}, warnings={}",
                context.getAnalysisId(),
                evaluation.getPhenotypePriorStatus(),
                evaluation.getPhenotypePriorGeneCount(),
                evaluation.getIntervalResourceStatus(),
                evaluation.getIntervalSourceVersion(),
                evaluation.getIntervalMappedPriorGeneCount(),
                evaluation.getRescueCandidateCount(),
                rescuedSelectedCount,
                rescueRejectedByHardGateCount,
                evaluation.getRescueMissingIntervalGeneCount(),
                evaluation.getEffectiveAssembly(),
                evaluation.getCandidateCap(),
                evaluation.getTotalCandidates(),
                evaluation.getWarnings()
        );
    }

    private int rescuedSelectedCount(
            List<VariantInterpretation> interpretations,
            Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> rescueMatches) {
        if (interpretations == null || rescueMatches == null || rescueMatches.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (VariantInterpretation interpretation : interpretations) {
            if (rescueMatches.containsKey(interpretation)
                    && VariantAnalysisBucket.CANDIDATE.equals(interpretation.getAnalysisBucket())) {
                count++;
            }
        }
        return count;
    }

    private LargeFileClassification cheapGateClassification(
            VariantInterpretation interpretation,
            PhenotypePriorGeneIntervalService.GeneIntervalMatch phenotypePriorRescueMatch) {
        VariantQcObservation observation = interpretation.getObservation();
        if (observation == null) {
            return LargeFileClassification.candidate(NO_OBSERVATION_REASONS);
        }
        if (isNonCarriedAlternate(observation.getGenotype())) {
            return largeFileFilteredClassification(
                    VariantAnalysisBucket.FILTERED_NOT_CARRIED,
                    "LARGE_FILE_NOT_CARRIED_ALT: selected sample does not carry ALT",
                    NOT_CARRIED_REASONS
            );
        }
        if (qcGateFailed(observation)) {
            return largeFileFilteredClassification(
                    VariantAnalysisBucket.FILTERED_QC,
                    "LARGE_FILE_QC_FILTERED: variant failed cheap QC gate",
                    QC_FILTERED_REASONS
            );
        }
        Double frequency = populationFrequency(interpretation);
        if (frequency != null
                && frequency.doubleValue() >= properties.getRanking().getCommonAlleleFrequencyThreshold()
                && !hasReviewableClinicalSignal(interpretation)) {
            return largeFileFilteredClassification(
                    VariantAnalysisBucket.FILTERED_COMMON,
                    "LARGE_FILE_COMMON_AF: common population frequency from available evidence",
                    COMMON_REASONS
            );
        }
        if (phenotypePriorRescueMatch != null) {
            return LargeFileClassification.candidate(Collections.singletonList(
                    phenotypePriorRescueReason(phenotypePriorRescueMatch)));
        }
        if (lowPriorityConsequenceOnly(interpretation) && !hasReviewableClinicalSignal(interpretation)) {
            return LargeFileClassification.filtered(
                    VariantAnalysisBucket.DEFERRED_LOW_PRIORITY,
                    "LARGE_FILE_LOW_PRIORITY_CONSEQUENCE: deferred expensive annotation",
                    LOW_PRIORITY_REASONS
            );
        }
        return LargeFileClassification.candidate(CANDIDATE_REASONS);
    }

    private void addPhenotypePriorRescueReason(
            VariantInterpretation interpretation,
            PhenotypePriorGeneIntervalService.GeneIntervalMatch match) {
        if (interpretation == null || match == null
                || !VariantAnalysisBucket.CANDIDATE.equals(interpretation.getAnalysisBucket())) {
            return;
        }
        List<String> reasons = new ArrayList<String>(interpretation.getCheapGateReasons());
        String reason = phenotypePriorRescueReason(match);
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
        interpretation.applyLargeFileClassification(
                interpretation.getAnalysisBucket(),
                interpretation.isLargeFileMode(),
                interpretation.isSummaryOnly(),
                interpretation.isExpensiveAnnotationSkipped(),
                interpretation.getExpensiveAnnotationSkippedReason(),
                reasons
        );
    }

    private String phenotypePriorRescueReason(
            PhenotypePriorGeneIntervalService.GeneIntervalMatch match) {
        return "LARGE_FILE_GATE_PHENOTYPE_PRIOR_GENE_INTERVAL: retained before hard cap because "
                + match.getGeneSymbol()
                + " is a phenotype-prior gene rank "
                + match.getPhenotypeRank()
                + " and overlaps "
                + match.getChromosome()
                + ":"
                + match.getStart()
                + "-"
                + match.getEnd()
                + " from "
                + match.getSource();
    }

    private LargeFileClassification largeFileFilteredClassification(VariantAnalysisBucket bucket,
                                                                    String skipReason,
                                                                    List<String> reasons) {
        if (!properties.getLargeFile().isDefaultSkipExpensiveForFiltered()) {
            return new LargeFileClassification(bucket, false, false, null, reasons);
        }
        return LargeFileClassification.filtered(bucket, skipReason, reasons);
    }

    private boolean qcGateFailed(VariantQcObservation observation) {
        boolean failed = false;
        String filter = observation.getFilterStatus();
        if (filter != null && !filter.trim().isEmpty() && !"PASS".equals(filter)) {
            failed = true;
        }
        Integer depth = observation.getDepth();
        if (depth != null && depth.intValue() < properties.getRanking().getMinDepth()) {
            failed = true;
        }
        Integer genotypeQuality = observation.getGenotypeQuality();
        if (genotypeQuality != null
                && genotypeQuality.intValue() < properties.getRanking().getMinGenotypeQuality()) {
            failed = true;
        }
        Double alleleBalance = observation.getAlleleBalance();
        if (alleleBalance != null
                && alleleBalance.doubleValue() > 0.0d
                && alleleBalance.doubleValue() < properties.getRanking().getMinAlleleBalance()) {
            failed = true;
        }
        return failed;
    }

    private Double populationFrequency(VariantInterpretation interpretation) {
        EvidenceItem gnomad = bestEvidence(interpretation, "GNOMAD");
        if (gnomad == null || !"APPLICABLE".equals(gnomad.getApplicability())) {
            return null;
        }
        if (gnomad.getRawScore() != null) {
            return gnomad.getRawScore();
        }
        Object maximum = gnomad.getDetails().get("maximumAlleleFrequency");
        if (maximum instanceof Number) {
            return ((Number) maximum).doubleValue();
        }
        Object alleleFrequency = gnomad.getDetails().get("alleleFrequency");
        if (alleleFrequency instanceof Number) {
            return ((Number) alleleFrequency).doubleValue();
        }
        return null;
    }

    private boolean hasReviewableClinicalSignal(VariantInterpretation interpretation) {
        EvidenceItem clinVar = bestEvidence(interpretation, "CLINVAR");
        if (clinVar == null) {
            return false;
        }
        String applicability = clinVar.getApplicability();
        String label = normalize(clinVar.getLabel());
        return "CONFLICTING".equals(applicability)
                || ("APPLICABLE".equals(applicability) && isClinVarPathogenic(label))
                || ("APPLICABLE".equals(applicability) && isClinVarVus(label));
    }

    private boolean lowPriorityConsequenceOnly(VariantInterpretation interpretation) {
        boolean sawLowPriority = false;
        for (TranscriptConsequence consequence : interpretation.getConsequences()) {
            String text = normalize(consequence.getConsequence())
                    + " " + normalize(consequence.getImpact())
                    + " " + normalize(consequence.getLof());
            if (text.contains("high")
                    || text.contains("moderate")
                    || text.contains("missense")
                    || text.contains("protein")
                    || text.contains("frameshift")
                    || text.contains("stop_gained")
                    || text.contains("stop_lost")
                    || text.contains("start_lost")
                    || text.contains("splice_acceptor")
                    || text.contains("splice_donor")
                    || text.contains("splice_region")
                    || text.contains("hc")) {
                return false;
            }
            if (text.contains("synonymous")
                    || text.contains("intron")
                    || text.contains("intergenic")
                    || text.contains("upstream")
                    || text.contains("downstream")
                    || text.contains("utr")
                    || text.contains("non_coding")
                    || text.contains("noncoding")
                    || text.contains("regulatory")) {
                sawLowPriority = true;
            }
        }
        return sawLowPriority;
    }

    private void applyCandidateHardLimit(
            List<VariantInterpretation> interpretations,
            Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> rescueMatches) {
        int limit = properties.getLargeFile().normalizedCandidateHardLimit();
        if (limit <= 0) {
            return;
        }
        List<IndexedVariant> candidates = new ArrayList<IndexedVariant>();
        for (int index = 0; index < interpretations.size(); index++) {
            VariantInterpretation interpretation = interpretations.get(index);
            if (VariantAnalysisBucket.CANDIDATE.equals(interpretation.getAnalysisBucket())) {
                candidates.add(new IndexedVariant(interpretation));
            }
        }
        if (candidates.size() <= limit) {
            return;
        }
        Collections.sort(candidates, candidateComparator(rescueMatches));
        Map<VariantInterpretation, Boolean> selected =
                new IdentityHashMap<VariantInterpretation, Boolean>();
        for (int index = 0; index < limit; index++) {
            selected.put(candidates.get(index).variant, Boolean.TRUE);
        }
        for (IndexedVariant candidate : candidates) {
            if (selected.containsKey(candidate.variant)) {
                continue;
            }
            List<String> reasons = new ArrayList<String>(candidate.variant.getCheapGateReasons());
            reasons.add("LARGE_FILE_GATE_CANDIDATE_HARD_LIMIT: candidate hard limit " + limit
                    + " was reached before expensive annotation");
            candidate.variant.applyLargeFileClassification(
                    VariantAnalysisBucket.DEFERRED_NO_EXPENSIVE_ANNOTATION,
                    true,
                    true,
                    true,
                    "LARGE_FILE_CANDIDATE_HARD_LIMIT: deferred expensive annotation",
                    reasons
            );
        }
    }

    private Comparator<IndexedVariant> candidateComparator(
            Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> rescueMatches) {
        return (left, right) -> {
            int rescuePriority = Integer.compare(
                    phenotypePriorRescuePriority(right.variant, rescueMatches),
                    phenotypePriorRescuePriority(left.variant, rescueMatches)
            );
            if (rescuePriority != 0) {
                return rescuePriority;
            }
            int priority = Integer.compare(
                    caddPreQueryPriority(right.variant),
                    caddPreQueryPriority(left.variant)
            );
            if (priority != 0) {
                return priority;
            }
            int confidence = Long.compare(
                    cheapCandidateConfidence(right.variant),
                    cheapCandidateConfidence(left.variant)
            );
            return confidence != 0
                    ? confidence
                    : compareNormalizedVariantKeys(left.variant, right.variant);
        };
    }

    private int phenotypePriorRescuePriority(
            VariantInterpretation variant,
            Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> rescueMatches) {
        if (rescueMatches == null || rescueMatches.isEmpty()) {
            return 0;
        }
        PhenotypePriorGeneIntervalService.GeneIntervalMatch match = rescueMatches.get(variant);
        if (match == null) {
            return 0;
        }
        int rank = Math.max(1, match.getPhenotypeRank());
        int score = (int) Math.round(Math.max(0.0d, match.getPhenotypeScore()) * 1000.0d);
        return 1000000 + score + Math.max(0, 10000 - rank);
    }

    private void applyExpensiveAnnotationLimit(List<VariantInterpretation> interpretations) {
        int limit = properties.getLargeFile().normalizedExpensiveAnnotationLimit();
        if (limit <= 0) {
            return;
        }
        int retained = 0;
        for (VariantInterpretation interpretation : interpretations) {
            if (interpretation.isExpensiveAnnotationSkipped()) {
                continue;
            }
            retained++;
            if (retained <= limit) {
                continue;
            }
            List<String> reasons = new ArrayList<String>(interpretation.getCheapGateReasons());
            reasons.add("LARGE_FILE_GATE_EXPENSIVE_ANNOTATION_LIMIT: expensive annotation limit "
                    + limit + " was reached");
            interpretation.applyLargeFileClassification(
                    VariantAnalysisBucket.DEFERRED_NO_EXPENSIVE_ANNOTATION,
                    true,
                    true,
                    true,
                    "LARGE_FILE_EXPENSIVE_ANNOTATION_LIMIT: deferred expensive annotation",
                    reasons
            );
        }
    }

    private void applyPostRankingBuckets(List<VariantInterpretation> interpretations, AnalysisContext context) {
        if (!largeFileMode(context) || interpretations == null) {
            return;
        }
        for (VariantInterpretation interpretation : interpretations) {
            VariantAnalysisBucket current = interpretation.getAnalysisBucket();
            if (current != null && !VariantAnalysisBucket.CANDIDATE.equals(current)) {
                continue;
            }
            interpretation.applyLargeFileClassification(
                    bucketFromTier(interpretation),
                    true,
                    interpretation.isSummaryOnly(),
                    interpretation.isExpensiveAnnotationSkipped(),
                    interpretation.getExpensiveAnnotationSkippedReason(),
                    interpretation.getCheapGateReasons()
            );
        }
    }

    private VariantAnalysisBucket bucketFromTier(VariantInterpretation interpretation) {
        String tier = interpretation.getTier();
        if ("KNOWN_CLINICAL".equals(tier)) {
            return VariantAnalysisBucket.KNOWN_CLINICAL;
        }
        if ("HIGH_PRIORITY".equals(tier)) {
            return VariantAnalysisBucket.HIGH_PRIORITY;
        }
        if ("REVIEW".equals(tier)) {
            return VariantAnalysisBucket.REVIEW;
        }
        if ("FILTERED_COMMON".equals(tier) || "POPULATION_FILTERED".equals(tier)) {
            return VariantAnalysisBucket.FILTERED_COMMON;
        }
        if ("FILTERED_QC".equals(tier)) {
            return isNonCarriedAlternate(interpretation.getObservation().getGenotype())
                    ? VariantAnalysisBucket.FILTERED_NOT_CARRIED
                    : VariantAnalysisBucket.FILTERED_QC;
        }
        return VariantAnalysisBucket.CANDIDATE;
    }

    private List<VariantEvidenceProvider> orderedProviders() {
        List<VariantEvidenceProvider> ordered = new ArrayList<VariantEvidenceProvider>();
        List<VariantEvidenceProvider> caddProviders = new ArrayList<VariantEvidenceProvider>();
        for (VariantEvidenceProvider provider : providers) {
            if ("CADD".equals(provider.source())) {
                caddProviders.add(provider);
            } else {
                ordered.add(provider);
            }
        }
        ordered.addAll(caddProviders);
        return ordered;
    }

    private List<VariantInterpretation> transcriptAnnotationInput(List<VariantInterpretation> interpretations,
                                                                  AnalysisContext context) {
        if (interpretations == null || interpretations.isEmpty() || !largeFileMode(context)) {
            return interpretations;
        }
        List<VariantInterpretation> candidates = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation interpretation : interpretations) {
            if (!interpretation.isSummaryOnly() && !interpretation.isExpensiveAnnotationSkipped()) {
                candidates.add(interpretation);
            }
        }
        return candidates;
    }

    private List<VariantInterpretation> acmgReadyInput(List<VariantInterpretation> interpretations,
                                                       AnalysisContext context) {
        if (interpretations == null || interpretations.isEmpty() || !largeFileMode(context)) {
            return interpretations == null ? Collections.emptyList() : interpretations;
        }
        List<VariantInterpretation> candidates = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation interpretation : interpretations) {
            if (!interpretation.isSummaryOnly()
                    && !interpretation.isExpensiveAnnotationSkipped()
                    && interpretation.isFullInterpretationAvailable()) {
                candidates.add(interpretation);
            }
        }
        return candidates;
    }

    private void annotateProvider(VariantEvidenceProvider provider,
                                  List<VariantInterpretation> queryVariants,
                                  AnalysisContext context) {
        if (queryVariants.isEmpty()) {
            return;
        }
        int batchSize = providerBatchSize(provider);
        for (int start = 0; start < queryVariants.size(); start += batchSize) {
            int end = Math.min(start + batchSize, queryVariants.size());
            List<VariantInterpretation> batch = queryVariants.subList(start, end);
            Map<NormalizedVariantKey, List<EvidenceItem>> evidenceByVariant =
                    provider.annotate(batch, context);
            for (VariantInterpretation interpretation : batch) {
                List<EvidenceItem> evidence = evidenceByVariant.get(
                        interpretation.getObservation().getKey()
                );
                interpretation.addEvidence(evidence == null ? Collections.emptyList() : evidence);
            }
        }
    }

    private int providerBatchSize(VariantEvidenceProvider provider) {
        if (provider != null
                && "CLINVAR".equals(provider.source())
                && properties.getClinVar().getCompactIndex().isEnabled()) {
            return properties.getClinVar().getCompactIndex().normalizedBatchSize();
        }
        return PROVIDER_QUERY_BATCH_SIZE;
    }

    private EvidenceQueryPlan queryPlan(VariantEvidenceProvider provider,
                                        List<VariantInterpretation> interpretations,
                                        AnalysisContext context) {
        List<VariantInterpretation> queryVariants =
                new ArrayList<VariantInterpretation>(interpretations.size());
        Map<VariantInterpretation, String> skipReasons =
                new IdentityHashMap<VariantInterpretation, String>();
        for (VariantInterpretation interpretation : interpretations) {
            String skipReason = skipReason(provider, interpretation, context);
            if (skipReason == null) {
                queryVariants.add(interpretation);
            } else {
                skipReasons.put(interpretation, skipReason);
            }
        }
        if ("CADD".equals(provider.source())) {
            applyCaddCandidateLimit(queryVariants, skipReasons);
        }
        return new EvidenceQueryPlan(queryVariants, skipReasons);
    }

    private void applyCaddCandidateLimit(List<VariantInterpretation> queryVariants,
                                         Map<VariantInterpretation, String> skipReasons) {
        int maxCandidates = properties.getCadd().normalizedMaxCandidates();
        if (maxCandidates <= 0 || queryVariants.size() <= maxCandidates) {
            return;
        }
        List<IndexedVariant> ranked = new ArrayList<IndexedVariant>(queryVariants.size());
        for (int index = 0; index < queryVariants.size(); index++) {
            ranked.add(new IndexedVariant(queryVariants.get(index)));
        }
        Collections.sort(ranked, (left, right) -> {
            int priority = Integer.compare(
                    caddPreQueryPriority(right.variant),
                    caddPreQueryPriority(left.variant)
            );
            if (priority != 0) {
                return priority;
            }
            int confidence = Long.compare(
                    cheapCandidateConfidence(right.variant),
                    cheapCandidateConfidence(left.variant)
            );
            return confidence != 0
                    ? confidence
                    : compareNormalizedVariantKeys(left.variant, right.variant);
        });

        Map<VariantInterpretation, Boolean> selected =
                new IdentityHashMap<VariantInterpretation, Boolean>();
        for (int index = 0; index < maxCandidates; index++) {
            selected.put(ranked.get(index).variant, Boolean.TRUE);
        }

        List<VariantInterpretation> limited =
                new ArrayList<VariantInterpretation>(maxCandidates);
        String reason = "Skipped by CADD large-file candidate limit (max "
                + maxCandidates
                + ") after ClinVar/gnomAD/QC pre-screen; CADD is auxiliary evidence only";
        boolean materializeSkippedEvidence =
                queryVariants.size() <= MATERIALIZED_SKIP_EVIDENCE_LIMIT;
        for (VariantInterpretation variant : queryVariants) {
            if (selected.containsKey(variant)) {
                limited.add(variant);
            } else if (materializeSkippedEvidence) {
                skipReasons.put(variant, reason);
            }
        }
        queryVariants.clear();
        queryVariants.addAll(limited);
    }

    private int caddPreQueryPriority(VariantInterpretation variant) {
        int priority = 0;
        EvidenceItem clinVar = bestEvidence(variant, "CLINVAR");
        if (clinVar != null) {
            String label = normalize(clinVar.getLabel());
            String applicability = clinVar.getApplicability();
            if ("CONFLICTING".equals(applicability) || label.contains("conflict")) {
                priority += 9000;
            } else if ("APPLICABLE".equals(applicability) && isClinVarPathogenic(label)) {
                priority += 10000;
            } else if ("APPLICABLE".equals(applicability) && isClinVarVus(label)) {
                priority += 7000;
            } else if ("APPLICABLE".equals(applicability) && isClinVarBenign(label)) {
                priority -= 3000;
            }
        }

        EvidenceItem gnomad = bestEvidence(variant, "GNOMAD");
        if (gnomad != null) {
            String applicability = gnomad.getApplicability();
            if ("APPLICABLE".equals(applicability) && gnomad.getRawScore() != null) {
                double af = gnomad.getRawScore();
                if (af >= properties.getRanking().getCommonAlleleFrequencyThreshold()) {
                    priority -= isPathogenicClinVar(clinVar) ? 500 : 8000;
                } else {
                    priority += 3000;
                }
            } else if ("NO_RECORD".equals(applicability)) {
                priority += 1500;
            }
        }

        String highImpact = firstHighImpactConsequence(variant);
        if (highImpact != null) {
            priority += 2500;
        } else if (firstConsequenceContaining(variant, "missense") != null) {
            priority += 1200;
        } else if (firstConsequenceContaining(variant, "synonymous") != null) {
            priority -= 500;
        }

        if ("INDEL".equals(variant.getObservation().getVariantType())) {
            priority += 250;
        }
        return priority;
    }

    private long cheapCandidateConfidence(VariantInterpretation variant) {
        VariantQcObservation observation = variant == null ? null : variant.getObservation();
        if (observation == null) {
            return 0L;
        }
        long genotypeQuality = observation.getGenotypeQuality() == null
                ? 0L
                : Math.min(999L, Math.max(0L, observation.getGenotypeQuality().longValue()));
        long depth = observation.getDepth() == null
                ? 0L
                : Math.min(500L, Math.max(0L, observation.getDepth().longValue()));
        long balance = alleleBalanceConfidence(observation);
        return genotypeQuality * 1_000_000L + depth * 1_000L + balance;
    }

    private long alleleBalanceConfidence(VariantQcObservation observation) {
        Double alleleBalance = observation.getAlleleBalance();
        if (alleleBalance == null
                || Double.isNaN(alleleBalance.doubleValue())
                || Double.isInfinite(alleleBalance.doubleValue())) {
            return 0L;
        }
        String genotype = observation.getGenotype() == null ? "" : observation.getGenotype();
        double expected = genotype.matches("[1-9][0-9]*[/|][1-9][0-9]*") ? 1.0d : 0.5d;
        double distance = Math.min(1.0d, Math.abs(alleleBalance.doubleValue() - expected));
        return Math.max(0L, Math.round((1.0d - distance) * 999.0d));
    }

    private int compareNormalizedVariantKeys(VariantInterpretation left, VariantInterpretation right) {
        NormalizedVariantKey leftKey = left == null || left.getObservation() == null
                ? null
                : left.getObservation().getKey();
        NormalizedVariantKey rightKey = right == null || right.getObservation() == null
                ? null
                : right.getObservation().getKey();
        if (leftKey == rightKey) {
            return 0;
        }
        if (leftKey == null) {
            return 1;
        }
        if (rightKey == null) {
            return -1;
        }
        int assembly = safeText(leftKey.getGenomeAssembly()).compareTo(safeText(rightKey.getGenomeAssembly()));
        if (assembly != 0) {
            return assembly;
        }
        int chromosome = chromosomeOrder(leftKey.getChromosome()) - chromosomeOrder(rightKey.getChromosome());
        if (chromosome != 0) {
            return chromosome;
        }
        int chromosomeText = safeText(leftKey.getChromosome()).compareTo(safeText(rightKey.getChromosome()));
        if (chromosomeText != 0) {
            return chromosomeText;
        }
        int position = Long.compare(leftKey.getPosition(), rightKey.getPosition());
        if (position != 0) {
            return position;
        }
        int reference = safeText(leftKey.getReference()).compareTo(safeText(rightKey.getReference()));
        return reference != 0
                ? reference
                : safeText(leftKey.getAlternate()).compareTo(safeText(rightKey.getAlternate()));
    }

    private int chromosomeOrder(String chromosome) {
        String normalized = safeText(chromosome).toUpperCase(Locale.ROOT);
        if (normalized.startsWith("CHR")) {
            normalized = normalized.substring(3);
        }
        if ("X".equals(normalized)) {
            return 23;
        }
        if ("Y".equals(normalized)) {
            return 24;
        }
        if ("M".equals(normalized) || "MT".equals(normalized)) {
            return 25;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            return 1000;
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private EvidenceItem bestEvidence(VariantInterpretation variant, String source) {
        EvidenceItem best = null;
        int bestRank = Integer.MIN_VALUE;
        for (EvidenceItem item : variant.getEvidence()) {
            if (!source.equals(item.getSource())) {
                continue;
            }
            int rank = applicabilityRank(item.getApplicability());
            if (rank > bestRank) {
                best = item;
                bestRank = rank;
            } else if (rank == bestRank && best != null
                    && scoreOrNegative(item.getRawScore()) > scoreOrNegative(best.getRawScore())) {
                best = item;
            }
        }
        return best;
    }

    private int applicabilityRank(String applicability) {
        if ("APPLICABLE".equals(applicability)) {
            return 5;
        }
        if ("CONFLICTING".equals(applicability)) {
            return 4;
        }
        if ("NO_RECORD".equals(applicability)) {
            return 3;
        }
        if ("NOT_APPLICABLE".equals(applicability)) {
            return 2;
        }
        if ("UNAVAILABLE".equals(applicability)) {
            return 1;
        }
        return 0;
    }

    private double scoreOrNegative(Double score) {
        return score == null ? Double.NEGATIVE_INFINITY : score;
    }

    private boolean isPathogenicClinVar(EvidenceItem clinVar) {
        return clinVar != null
                && "APPLICABLE".equals(clinVar.getApplicability())
                && isClinVarPathogenic(normalize(clinVar.getLabel()));
    }

    private boolean isClinVarPathogenic(String normalized) {
        return normalized.contains("pathogenic")
                && !normalized.contains("benign")
                && !normalized.contains("conflict");
    }

    private boolean isClinVarBenign(String normalized) {
        return normalized.contains("benign")
                && !normalized.contains("pathogenic")
                && !normalized.contains("conflict");
    }

    private boolean isClinVarVus(String normalized) {
        return normalized.contains("uncertain")
                || normalized.contains("vus")
                || normalized.contains("uncertain_significance");
    }

    private String firstHighImpactConsequence(VariantInterpretation variant) {
        for (TranscriptConsequence consequence : variant.getConsequences()) {
            String value = consequence.getConsequence();
            if (value == null) {
                continue;
            }
            String normalized = normalize(value);
            if (normalized.contains("frameshift")
                    || normalized.contains("stop_gained")
                    || normalized.contains("splice_acceptor")
                    || normalized.contains("splice_donor")) {
                return value;
            }
        }
        return null;
    }

    private String firstConsequenceContaining(VariantInterpretation variant, String needle) {
        for (TranscriptConsequence consequence : variant.getConsequences()) {
            String value = consequence.getConsequence();
            if (value != null && normalize(value).contains(needle)) {
                return value;
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String skipReason(VariantEvidenceProvider provider,
                              VariantInterpretation interpretation,
                              AnalysisContext context) {
        if (shouldSkipExpensiveProvider(provider, interpretation)) {
            return interpretation.getExpensiveAnnotationSkippedReason();
        }
        if (isNonCarriedAlternate(interpretation.getObservation().getGenotype())) {
            return "Skipped because the selected sample genotype is 0/0 and does not carry the ALT allele";
        }

        String source = provider.source();
        if (shouldSkipClinVarCompactBatch(provider, interpretation, context)) {
            return "LARGE_FILE_CLINVAR_COMPACT_CANDIDATE_ONLY: skipped compact ClinVar lookup for "
                    + "summary-only, filtered, or deferred record without prior ClinVar clinical signal";
        }
        String filterStatus = interpretation.getObservation().getFilterStatus();
        if (!"CLINVAR".equals(source)
                && filterStatus != null
                && !filterStatus.trim().isEmpty()
                && !"PASS".equalsIgnoreCase(filterStatus.trim())
                && !".".equals(filterStatus.trim())) {
            return "Skipped because VCF FILTER is not PASS and the variant is already QC-down-ranked";
        }

        if (!"CLINVAR".equals(source)
                && !supportedLocalEvidenceVariantType(interpretation.getObservation().getVariantType())) {
            return "Skipped because this variant type has limited local evidence support";
        }

        return null;
    }

    private boolean shouldSkipClinVarCompactBatch(VariantEvidenceProvider provider,
                                                  VariantInterpretation interpretation,
                                                  AnalysisContext context) {
        if (provider == null || interpretation == null || !"CLINVAR".equals(provider.source())) {
            return false;
        }
        if (!largeFileMode(context) || !properties.getClinVar().getCompactIndex().isEnabled()) {
            return false;
        }
        if (hasReviewableClinicalSignal(interpretation)) {
            return false;
        }
        VariantAnalysisBucket bucket = interpretation.getAnalysisBucket();
        return interpretation.isSummaryOnly()
                || interpretation.isExpensiveAnnotationSkipped()
                || (bucket != null && !VariantAnalysisBucket.CANDIDATE.equals(bucket));
    }

    private boolean shouldSkipExpensiveProvider(VariantEvidenceProvider provider,
                                                VariantInterpretation interpretation) {
        if (provider == null || interpretation == null || !interpretation.isExpensiveAnnotationSkipped()) {
            return false;
        }
        if ("CLINVAR".equals(provider.source())) {
            return VariantAnalysisBucket.FILTERED_NOT_CARRIED.equals(interpretation.getAnalysisBucket());
        }
        return true;
    }

    private boolean supportedLocalEvidenceVariantType(String variantType) {
        return "SNV".equals(variantType) || "INDEL".equals(variantType);
    }

    private EvidenceItem skipEvidence(VariantEvidenceProvider provider, String reason) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        String preScreen = reason != null && reason.startsWith("LARGE_FILE")
                ? "LARGE_FILE_CHEAP_GATE"
                : "QC";
        details.put("preScreen", preScreen);
        details.put("reason", reason);
        details.put("summaryOnly", reason != null && reason.startsWith("LARGE_FILE"));
        return new EvidenceItem(
                provider.source(),
                provider.version(),
                evidenceType(provider.source()),
                "NOT_APPLICABLE",
                null,
                "LARGE_FILE_CHEAP_GATE".equals(preScreen)
                        ? "Large-file pre-screen skipped"
                        : "QC pre-screen skipped",
                reason,
                details
        );
    }

    private String evidenceType(String source) {
        if ("CLINVAR".equals(source)) {
            return "CLINICAL";
        }
        if ("GNOMAD".equals(source)) {
            return "POPULATION";
        }
        if ("ALPHAGENOME".equals(source)) {
            return "MECHANISTIC";
        }
        return "COMPUTATIONAL";
    }

    private boolean isNonCarriedAlternate(String genotype) {
        if (genotype == null || genotype.isEmpty()) {
            return false;
        }
        int cursor = 0;
        int length = genotype.length();
        boolean sawReferenceAllele = false;
        while (cursor < length) {
            while (cursor < length && Character.isWhitespace(genotype.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length || genotype.charAt(cursor) != '0') {
                return false;
            }
            sawReferenceAllele = true;
            cursor++;
            while (cursor < length && Character.isWhitespace(genotype.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length) {
                return true;
            }
            char separator = genotype.charAt(cursor);
            if (separator != '/' && separator != '|') {
                return false;
            }
            cursor++;
            if (cursor >= length) {
                return false;
            }
        }
        return sawReferenceAllele;
    }

    private static class EvidenceQueryPlan {

        private final List<VariantInterpretation> queryVariants;
        private final Map<VariantInterpretation, String> skipReasons;

        private EvidenceQueryPlan(List<VariantInterpretation> queryVariants,
                                  Map<VariantInterpretation, String> skipReasons) {
            this.queryVariants = queryVariants;
            this.skipReasons = skipReasons;
        }
    }

    private static class IndexedVariant {

        private final VariantInterpretation variant;

        private IndexedVariant(VariantInterpretation variant) {
            this.variant = variant;
        }
    }

    private static class CompactLargeFileGateResult {

        private final List<VariantInterpretation> interpretations;
        private final int rescueRejectedByHardGateCount;

        private CompactLargeFileGateResult(
                List<VariantInterpretation> interpretations,
                int rescueRejectedByHardGateCount) {
            this.interpretations = interpretations;
            this.rescueRejectedByHardGateCount = rescueRejectedByHardGateCount;
        }
    }

    private static class LargeFileClassification {

        private final VariantAnalysisBucket bucket;
        private final boolean summaryOnly;
        private final boolean expensiveAnnotationSkipped;
        private final String expensiveAnnotationSkippedReason;
        private final List<String> reasons;

        private LargeFileClassification(VariantAnalysisBucket bucket,
                                        boolean summaryOnly,
                                        boolean expensiveAnnotationSkipped,
                                        String expensiveAnnotationSkippedReason,
                                        List<String> reasons) {
            this.bucket = bucket;
            this.summaryOnly = summaryOnly;
            this.expensiveAnnotationSkipped = expensiveAnnotationSkipped;
            this.expensiveAnnotationSkippedReason = expensiveAnnotationSkippedReason;
            this.reasons = reasons == null ? Collections.emptyList() : reasons;
        }

        private static LargeFileClassification candidate(List<String> reasons) {
            return new LargeFileClassification(
                    VariantAnalysisBucket.CANDIDATE,
                    false,
                    false,
                    null,
                    reasons
            );
        }

        private static LargeFileClassification filtered(VariantAnalysisBucket bucket,
                                                        String skipReason,
                                                        List<String> reasons) {
            return new LargeFileClassification(bucket, true, true, skipReason, reasons);
        }
    }

    private long logStage(AnalysisContext context, String stage, long startedAtNanos) {
        long now = System.nanoTime();
        LOGGER.info(
                "Variant analysis job {} interpretation stage '{}' completed in {} ms",
                context.getAnalysisId(),
                stage,
                TimeUnit.NANOSECONDS.toMillis(now - startedAtNanos)
        );
        return now;
    }
}
