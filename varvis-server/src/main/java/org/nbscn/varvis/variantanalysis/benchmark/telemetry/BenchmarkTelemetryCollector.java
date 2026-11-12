package org.nbscn.varvis.variantanalysis.benchmark.telemetry;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkFailureCode;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthMatch;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.NormalizationRunManifest;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VcfQcResult;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;
import org.nbscn.varvis.variantanalysis.service.PreRankingTranscriptAnnotationService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BenchmarkTelemetryCollector {

    private static final BenchmarkTelemetryCollector DISABLED = new BenchmarkTelemetryCollector();

    private final boolean enabled;
    private final String attemptId;
    private final String requestFingerprint;
    private final String configurationFingerprint;
    private final Map<NormalizedVariantKey, MutableTruth> truths;
    private final Map<String, BenchmarkAnalysisTelemetryManifest.CountValue> counts =
            new LinkedHashMap<String, BenchmarkAnalysisTelemetryManifest.CountValue>();
    private Integer candidateHardLimit;
    private Integer vepCandidateCap;

    private BenchmarkTelemetryCollector() {
        this.enabled = false;
        this.attemptId = null;
        this.requestFingerprint = null;
        this.configurationFingerprint = null;
        this.truths = Collections.emptyMap();
    }

    public BenchmarkTelemetryCollector(String attemptId,
                                       String requestFingerprint,
                                       String configurationFingerprint,
                                       List<NormalizedVariantKey> truthKeys) {
        this.enabled = true;
        this.attemptId = attemptId;
        this.requestFingerprint = requestFingerprint;
        this.configurationFingerprint = configurationFingerprint;
        this.truths = new LinkedHashMap<NormalizedVariantKey, MutableTruth>();
        if (truthKeys != null) {
            for (NormalizedVariantKey key : truthKeys) {
                if (key != null && !truths.containsKey(key)) {
                    MutableTruth truth = new MutableTruth(key);
                    truth.stage("SOURCE_TRUTH_VALIDATED", "PRESENT", "VALIDATED_BENCHMARK_REQUEST", null);
                    truth.stage("SPIKED_VCF_PRESENT", "PRESENT", "BOUND_INPUT_AND_TRUTH_FINGERPRINT", null);
                    truths.put(key, truth);
                }
            }
        }
    }

    public static BenchmarkTelemetryCollector disabled() { return DISABLED; }
    public boolean isEnabled() { return enabled; }
    public boolean isTruth(VariantInterpretation interpretation) {
        return enabled && interpretation != null && interpretation.getObservation() != null
                && truths.containsKey(interpretation.getObservation().getKey());
    }

    public synchronized void observeNormalization(NormalizationResult normalization, VcfQcResult qc) {
        if (!enabled) return;
        NormalizationRunManifest manifest = normalization == null ? null : normalization.getManifest();
        availableOrUnavailable("rawVcfRecords", manifest == null ? null : manifest.getRawRecordCount(),
                "RECORD", "NORMALIZATION_MANIFEST");
        int parsed = qc == null || qc.getObservations() == null ? 0 : qc.getObservations().size();
        available("parsedAlleles", parsed, "ALLELE", "QC_OBSERVATIONS");
        availableOrUnavailable("normalizedAlleles", manifest == null ? null : manifest.getNormalizedAlleleCount(),
                "ALLELE", "NORMALIZATION_MANIFEST");
        availableOrUnavailable("normalizationSkipped", manifest == null ? null : manifest.getSkippedRecordCount(),
                "RECORD", "NORMALIZATION_MANIFEST");
        put("normalizationFailed", manifest != null && manifest.getFailureCode() != null ? 1 : 0,
                "RUN", "NORMALIZATION_MANIFEST");
        Set<NormalizedVariantKey> observed = new LinkedHashSet<NormalizedVariantKey>();
        if (qc != null && qc.getObservations() != null) {
            for (VariantQcObservation observation : qc.getObservations()) {
                if (observation != null && observation.getKey() != null) observed.add(observation.getKey());
            }
        }
        for (MutableTruth truth : truths.values()) {
            boolean present = observed.contains(truth.originalIdentity);
            truth.stage("PARSED", present ? "PRESENT" : "ABSENT",
                    present ? "QC_ALLELE_MATCH" : "TRUTH_NOT_IN_NORMALIZED_QC_OBSERVATIONS",
                    present ? null : BenchmarkFailureCode.F2);
            truth.stage("NORMALIZATION_RESULT", present ? "PRESENT" : "ABSENT",
                    present ? "ASSEMBLY_AWARE_CANONICAL_MATCH" : "NORMALIZED_IDENTITY_NOT_FOUND",
                    present ? null : BenchmarkFailureCode.F2);
            if (present) truth.normalizedIdentity = truth.originalIdentity;
        }
    }

    public synchronized void observeBasicInterpretations(List<VariantInterpretation> interpretations) {
        if (!enabled) return;
        Set<NormalizedVariantKey> keys = interpretationKeys(interpretations);
        for (MutableTruth truth : truths.values()) {
            boolean present = keys.contains(truth.originalIdentity);
            truth.stage("BASIC_INTERPRETATION", present ? "PRESENT" : "ABSENT",
                    present ? "BASIC_ANNOTATOR_MATCH" : "BASIC_INTERPRETATION_NOT_FOUND", null);
        }
    }

    public synchronized void observeCandidateGate(int eligible,
                                                  int retained,
                                                  int released,
                                                  int rescued,
                                                  int displaced,
                                                  int hardLimit,
                                                  Map<NormalizedVariantKey, GateObservation> observations) {
        if (!enabled) return;
        this.candidateHardLimit = hardLimit;
        available("cheapGateEligible", eligible, "CANDIDATE", "LARGE_FILE_GATE");
        available("retainedBeforeHardCap", eligible, "CANDIDATE", "LARGE_FILE_GATE");
        available("retainedAfterHardCap", retained, "CANDIDATE", "LARGE_FILE_GATE");
        available("releasedByHardCap", released, "CANDIDATE", "LARGE_FILE_GATE");
        available("phenotypeRescued", rescued, "CANDIDATE", "LARGE_FILE_GATE_COUNTERFACTUAL");
        available("phenotypeDisplaced", displaced, "CANDIDATE", "LARGE_FILE_GATE_COUNTERFACTUAL");
        if (eligible != retained + released) {
            throw new IllegalStateException("BENCHMARK_CANDIDATE_COUNT_INVARIANT_FAILED");
        }
        for (MutableTruth truth : truths.values()) {
            GateObservation observation = observations == null ? null : observations.get(truth.originalIdentity);
            if (observation == null) {
                truth.stage("CHEAP_GATE_ELIGIBLE", "UNAVAILABLE", "TRUTH_GATE_OBSERVATION_MISSING", null);
                truth.stage("RETAINED_BEFORE_HARD_CAP", "UNAVAILABLE", "TRUTH_GATE_OBSERVATION_MISSING", null);
                truth.stage("RETAINED_AFTER_HARD_CAP", "UNAVAILABLE", "TRUTH_GATE_OBSERVATION_MISSING", null);
                truth.stage("RELEASED_BY_HARD_CAP", "UNAVAILABLE", "TRUTH_GATE_OBSERVATION_MISSING", null);
                truth.stage("PHENOTYPE_DISPOSITION", "UNAVAILABLE", "TRUTH_GATE_OBSERVATION_MISSING", null);
                continue;
            }
            truth.stage("CHEAP_GATE_ELIGIBLE", observation.eligible ? "PRESENT" : "ABSENT",
                    observation.reason, observation.failureCode);
            truth.stage("RETAINED_BEFORE_HARD_CAP", observation.eligible ? "PRESENT" : "ABSENT",
                    observation.reason, observation.failureCode);
            truth.stage("RETAINED_AFTER_HARD_CAP", observation.retained ? "PRESENT" : "ABSENT",
                    observation.retained ? "RETAINED_BY_PRODUCTION_GATE" : observation.reason,
                    observation.failureCode);
            truth.stage("RELEASED_BY_HARD_CAP", observation.eligible && !observation.retained
                            ? "PRESENT" : "NOT_APPLICABLE",
                    observation.eligible && !observation.retained ? "CANDIDATE_HARD_LIMIT" : "NOT_RELEASED",
                    null);
            truth.stage("PHENOTYPE_DISPOSITION", observation.phenotypeDisposition,
                    observation.phenotypeDispositionReason, null);
        }
    }

    public synchronized void observeVepSelection(List<VariantInterpretation> eligible,
                                                 List<VariantInterpretation> selected,
                                                 int cap) {
        if (!enabled) return;
        this.vepCandidateCap = cap;
        Set<NormalizedVariantKey> eligibleKeys = interpretationKeys(eligible);
        Set<NormalizedVariantKey> selectedKeys = interpretationKeys(selected);
        for (MutableTruth truth : truths.values()) {
            if (selectedKeys.contains(truth.originalIdentity)) {
                truth.stage("VEP_SELECTED", "PRESENT", "SELECTED_BY_PRODUCTION_VEP_ORDER", null);
            } else if (eligibleKeys.contains(truth.originalIdentity)) {
                truth.stage("VEP_SELECTED", "NOT_SELECTED", "VEP_CANDIDATE_CAP", null);
            } else {
                truth.stage("VEP_SELECTED", "NOT_APPLICABLE", "NOT_VEP_ELIGIBLE", null);
            }
        }
    }

    public synchronized void observeVepSummary(
            PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary) {
        if (!enabled || summary == null) return;
        available("vepEligible", summary.getEligibleCandidateCount(), "CANDIDATE", "PRE_RANKING_VEP_SUMMARY");
        available("vepSelected", summary.getSelectedCount(), "CANDIDATE", "PRE_RANKING_VEP_SUMMARY");
        available("vepAnnotated", summary.getAnnotatedAvailableCount(), "CANDIDATE", "PRE_RANKING_VEP_SUMMARY");
        available("vepUnavailable", summary.getUnavailableCount(), "CANDIDATE", "PRE_RANKING_VEP_SUMMARY");
        this.vepCandidateCap = summary.getPreRankingCandidateCap();
    }

    public synchronized void observePersistence(List<VariantInterpretation> interpretations, int persistedCount) {
        if (!enabled) return;
        available("persistedInterpretations", persistedCount, "INTERPRETATION", "JOB_REPOSITORY");
        Set<NormalizedVariantKey> keys = interpretationKeys(interpretations);
        for (MutableTruth truth : truths.values()) {
            boolean present = keys.contains(truth.originalIdentity);
            truth.stage("PERSISTED_INTERPRETATION", present ? "PRESENT" : "ABSENT",
                    present ? "PERSISTED_CANONICAL_MATCH" : "NOT_PERSISTED", null);
        }
    }

    public synchronized void observeRanking(List<BenchmarkTruthMatch> matches, int rankedCount) {
        if (!enabled) return;
        available("rankedInterpretations", rankedCount, "INTERPRETATION", "RANKING_CONTRACT_V1");
        Map<NormalizedVariantKey, BenchmarkTruthMatch> byKey = new LinkedHashMap<NormalizedVariantKey, BenchmarkTruthMatch>();
        if (matches != null) {
            for (BenchmarkTruthMatch match : matches) {
                if (match != null && match.getTruthCanonicalIdentity() != null) {
                    byKey.put(match.getTruthCanonicalIdentity(), match);
                }
            }
        }
        for (MutableTruth truth : truths.values()) {
            BenchmarkTruthMatch match = byKey.get(truth.originalIdentity);
            String persistedStatus = "UNAVAILABLE";
            String persistedReason = "TRUTH_RANK_MATCH_MISSING";
            if (match != null && match.getRankStatus() == RankStatus.RANKED) {
                persistedStatus = "PRESENT";
                persistedReason = "FORMAL_PERSISTED_RANK_MATCH";
            } else if (match != null && match.getRankStatus()
                    == RankStatus.NOT_IN_RANKING_UNIVERSE) {
                persistedStatus = "ABSENT";
                persistedReason = "NOT_IN_FORMAL_PERSISTED_RANKING_UNIVERSE";
            }
            truth.stage("PERSISTED_INTERPRETATION", persistedStatus, persistedReason, null);
            truth.stage("RANKED_STATUS", match == null ? "UNAVAILABLE" : String.valueOf(match.getRankStatus()),
                    match == null ? "TRUTH_RANK_MATCH_MISSING" : "RANKING_CONTRACT_V1", null);
        }
    }

    public synchronized BenchmarkAnalysisTelemetryManifest snapshot(String analysisReference) {
        return snapshot(analysisReference, Instant.now());
    }

    public synchronized BenchmarkAnalysisTelemetryManifest snapshot(
            String analysisReference,
            Instant createdAt) {
        if (!enabled) throw new IllegalStateException("BENCHMARK_TELEMETRY_DISABLED");
        if (createdAt == null) throw new IllegalArgumentException("BENCHMARK_TELEMETRY_CREATED_AT_REQUIRED");
        BenchmarkAnalysisTelemetryManifest manifest = new BenchmarkAnalysisTelemetryManifest();
        manifest.setAttemptId(attemptId);
        manifest.setRequestFingerprint(requestFingerprint);
        manifest.setAnalysisReference(analysisReference);
        manifest.setConfigurationFingerprint(configurationFingerprint);
        manifest.setStatus("COMPLETED");
        manifest.setCreatedAt(createdAt.toString());
        manifest.setCandidateHardLimit(candidateHardLimit);
        manifest.setVepCandidateCap(vepCandidateCap);
        manifest.setCounts(new LinkedHashMap<String, BenchmarkAnalysisTelemetryManifest.CountValue>(counts));
        List<BenchmarkAnalysisTelemetryManifest.TruthTelemetry> values =
                new ArrayList<BenchmarkAnalysisTelemetryManifest.TruthTelemetry>();
        for (MutableTruth truth : truths.values()) values.add(truth.toManifest());
        manifest.setTruths(values);
        return manifest;
    }

    private Set<NormalizedVariantKey> interpretationKeys(List<VariantInterpretation> interpretations) {
        Set<NormalizedVariantKey> keys = new LinkedHashSet<NormalizedVariantKey>();
        if (interpretations != null) {
            for (VariantInterpretation interpretation : interpretations) {
                if (interpretation != null && interpretation.getObservation() != null
                        && interpretation.getObservation().getKey() != null) {
                    keys.add(interpretation.getObservation().getKey());
                }
            }
        }
        return keys;
    }

    private void available(String name, int value, String unit, String source) {
        counts.put(name, new BenchmarkAnalysisTelemetryManifest.CountValue(
                "AVAILABLE", Math.max(0, value), unit, source));
    }

    private void put(String name, int value, String unit, String source) { available(name, value, unit, source); }

    private void availableOrUnavailable(String name, Integer value, String unit, String source) {
        counts.put(name, new BenchmarkAnalysisTelemetryManifest.CountValue(
                value == null ? "UNAVAILABLE" : "AVAILABLE",
                value == null ? null : Math.max(0, value.intValue()), unit, source));
    }

    public static class GateObservation {
        private final boolean eligible;
        private final boolean retained;
        private final String reason;
        private final String failureCode;
        private final String phenotypeDisposition;
        private final String phenotypeDispositionReason;

        public GateObservation(boolean eligible,
                               boolean retained,
                               String reason,
                               String failureCode,
                               String phenotypeDisposition,
                               String phenotypeDispositionReason) {
            this.eligible = eligible;
            this.retained = retained;
            this.reason = reason;
            this.failureCode = failureCode;
            this.phenotypeDisposition = phenotypeDisposition;
            this.phenotypeDispositionReason = phenotypeDispositionReason;
        }
    }

    private static class MutableTruth {
        private final NormalizedVariantKey originalIdentity;
        private NormalizedVariantKey normalizedIdentity;
        private final Map<String, BenchmarkAnalysisTelemetryManifest.StageValue> stages =
                new LinkedHashMap<String, BenchmarkAnalysisTelemetryManifest.StageValue>();
        private String firstLossStage;
        private String failureCode;

        private MutableTruth(NormalizedVariantKey originalIdentity) { this.originalIdentity = originalIdentity; }

        private void stage(String name, String status, String reason, String code) {
            stages.put(name, new BenchmarkAnalysisTelemetryManifest.StageValue(status, reason));
            if (firstLossStage == null && "ABSENT".equals(status)) {
                firstLossStage = name;
                failureCode = code;
            }
        }

        private BenchmarkAnalysisTelemetryManifest.TruthTelemetry toManifest() {
            BenchmarkAnalysisTelemetryManifest.TruthTelemetry value =
                    new BenchmarkAnalysisTelemetryManifest.TruthTelemetry();
            value.setOriginalIdentity(originalIdentity);
            value.setNormalizedIdentity(normalizedIdentity);
            value.setStages(new LinkedHashMap<String, BenchmarkAnalysisTelemetryManifest.StageValue>(stages));
            value.setFirstLossStage(firstLossStage);
            value.setFailureCode(failureCode);
            return value;
        }
    }
}
