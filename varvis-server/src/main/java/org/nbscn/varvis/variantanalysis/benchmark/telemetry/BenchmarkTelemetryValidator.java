package org.nbscn.varvis.variantanalysis.benchmark.telemetry;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkFailureCode;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BenchmarkTelemetryValidator {

    private static final Set<String> REQUIRED_COUNTS = new HashSet<String>(Arrays.asList(
            "rawVcfRecords", "parsedAlleles", "normalizedAlleles", "normalizationSkipped",
            "normalizationFailed", "cheapGateEligible", "retainedBeforeHardCap",
            "retainedAfterHardCap", "releasedByHardCap", "phenotypeRescued",
            "phenotypeDisplaced", "vepEligible", "vepSelected", "vepAnnotated",
            "vepUnavailable", "persistedInterpretations", "rankedInterpretations"
    ));
    private static final Set<String> REQUIRED_TRUTH_STAGES = new HashSet<String>(Arrays.asList(
            "SOURCE_TRUTH_VALIDATED", "SPIKED_VCF_PRESENT", "PARSED", "NORMALIZATION_RESULT",
            "BASIC_INTERPRETATION", "CHEAP_GATE_ELIGIBLE", "RETAINED_BEFORE_HARD_CAP",
            "RETAINED_AFTER_HARD_CAP", "RELEASED_BY_HARD_CAP", "PHENOTYPE_DISPOSITION",
            "VEP_SELECTED", "PERSISTED_INTERPRETATION", "RANKED_STATUS"
    ));

    private BenchmarkTelemetryValidator() { }

    public static void requireValid(BenchmarkAnalysisTelemetryManifest manifest) {
        if (manifest == null) throw new IllegalArgumentException("BENCHMARK_TELEMETRY_MANIFEST_REQUIRED");
        requiredExact(manifest.getSchemaVersion(), BenchmarkAnalysisTelemetryManifest.SCHEMA_VERSION,
                "SCHEMA_VERSION_UNSUPPORTED");
        required(manifest.getAttemptId(), "ATTEMPT_ID_REQUIRED");
        checksum(manifest.getRequestFingerprint(), "REQUEST_FINGERPRINT_INVALID");
        required(manifest.getAnalysisReference(), "ANALYSIS_REFERENCE_REQUIRED");
        checksum(manifest.getConfigurationFingerprint(), "CONFIGURATION_FINGERPRINT_INVALID");
        requiredExact(manifest.getStatus(), "COMPLETED", "STATUS_NOT_COMPLETED");
        try {
            Instant.parse(manifest.getCreatedAt());
        } catch (RuntimeException ex) {
            fail("CREATED_AT_INVALID");
        }
        nonNegative(manifest.getCandidateHardLimit(), "CANDIDATE_HARD_LIMIT_INVALID");
        nonNegative(manifest.getVepCandidateCap(), "VEP_CANDIDATE_CAP_INVALID");
        validateCounts(manifest.getCounts());
        validateTruths(manifest.getTruths());
    }

    private static void validateCounts(Map<String, BenchmarkAnalysisTelemetryManifest.CountValue> counts) {
        if (counts == null || !counts.keySet().containsAll(REQUIRED_COUNTS)) {
            throw new IllegalArgumentException("BENCHMARK_TELEMETRY_REQUIRED_COUNTS_MISSING");
        }
        for (Map.Entry<String, BenchmarkAnalysisTelemetryManifest.CountValue> entry : counts.entrySet()) {
            BenchmarkAnalysisTelemetryManifest.CountValue value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException("BENCHMARK_TELEMETRY_COUNT_VALUE_REQUIRED:" + entry.getKey());
            }
            required(value.getStatus(), "COUNT_STATUS_REQUIRED:" + entry.getKey());
            required(value.getUnit(), "COUNT_UNIT_REQUIRED:" + entry.getKey());
            required(value.getSource(), "COUNT_SOURCE_REQUIRED:" + entry.getKey());
            if ("AVAILABLE".equals(value.getStatus())) {
                nonNegative(value.getValue(), "COUNT_VALUE_INVALID:" + entry.getKey());
            } else if ("UNAVAILABLE".equals(value.getStatus())) {
                if (value.getValue() != null) fail("UNAVAILABLE_COUNT_HAS_VALUE:" + entry.getKey());
            } else {
                fail("COUNT_STATUS_INVALID:" + entry.getKey());
            }
        }
        int eligible = availableCount(counts, "cheapGateEligible");
        int before = availableCount(counts, "retainedBeforeHardCap");
        int retained = availableCount(counts, "retainedAfterHardCap");
        int released = availableCount(counts, "releasedByHardCap");
        if (eligible != before || eligible != retained + released) {
            fail("CANDIDATE_COUNT_INVARIANT_FAILED");
        }
    }

    private static void validateTruths(List<BenchmarkAnalysisTelemetryManifest.TruthTelemetry> truths) {
        if (truths == null || truths.isEmpty()) {
            throw new IllegalArgumentException("BENCHMARK_TELEMETRY_TRUTH_TELEMETRY_REQUIRED");
        }
        Set<NormalizedVariantKey> identities = new HashSet<NormalizedVariantKey>();
        for (BenchmarkAnalysisTelemetryManifest.TruthTelemetry truth : truths) {
            if (truth == null || truth.getOriginalIdentity() == null) {
                throw new IllegalArgumentException("BENCHMARK_TELEMETRY_TRUTH_IDENTITY_REQUIRED");
            }
            NormalizedVariantKey originalIdentity = truth.getOriginalIdentity();
            if (!identities.add(originalIdentity)) fail("TRUTH_IDENTITY_DUPLICATE");
            Map<String, BenchmarkAnalysisTelemetryManifest.StageValue> stages = truth.getStages();
            if (stages == null || !stages.keySet().containsAll(REQUIRED_TRUTH_STAGES)) {
                throw new IllegalArgumentException("BENCHMARK_TELEMETRY_TRUTH_STAGES_INCOMPLETE");
            }
            String firstAbsent = null;
            for (Map.Entry<String, BenchmarkAnalysisTelemetryManifest.StageValue> entry : stages.entrySet()) {
                BenchmarkAnalysisTelemetryManifest.StageValue value = entry.getValue();
                if (value == null) {
                    throw new IllegalArgumentException("BENCHMARK_TELEMETRY_TRUTH_STAGE_REQUIRED:" + entry.getKey());
                }
                required(value.getStatus(), "TRUTH_STAGE_STATUS_REQUIRED:" + entry.getKey());
                required(value.getReason(), "TRUTH_STAGE_REASON_REQUIRED:" + entry.getKey());
                if (firstAbsent == null && "ABSENT".equals(value.getStatus())) firstAbsent = entry.getKey();
            }
            if (firstAbsent == null) {
                if (truth.getFirstLossStage() != null || truth.getFailureCode() != null) {
                    fail("TRUTH_FIRST_LOSS_UNSUPPORTED");
                }
            } else {
                requiredExact(truth.getFirstLossStage(), firstAbsent, "TRUTH_FIRST_LOSS_MISMATCH");
                if (truth.getFailureCode() != null && !BenchmarkFailureCode.isAllowed(truth.getFailureCode())) {
                    fail("TRUTH_FAILURE_CODE_INVALID");
                }
            }
        }
    }

    private static int availableCount(Map<String, BenchmarkAnalysisTelemetryManifest.CountValue> counts,
                                      String name) {
        BenchmarkAnalysisTelemetryManifest.CountValue value = counts.get(name);
        if (value == null || !"AVAILABLE".equals(value.getStatus()) || value.getValue() == null) {
            throw new IllegalArgumentException("BENCHMARK_TELEMETRY_COUNT_REQUIRED_FOR_INVARIANT:" + name);
        }
        Integer count = value.getValue();
        return count.intValue();
    }

    private static void nonNegative(Integer value, String code) {
        if (value == null || value.intValue() < 0) fail(code);
    }

    private static void checksum(String value, String code) {
        if (value == null || !value.matches("[0-9a-f]{64}")) fail(code);
    }

    private static void required(String value, String code) {
        if (value == null || value.trim().isEmpty()) fail(code);
    }

    private static void requiredExact(String actual, String expected, String code) {
        if (!expected.equals(actual)) fail(code);
    }

    private static void fail(String code) {
        throw new IllegalArgumentException("BENCHMARK_TELEMETRY_" + code);
    }
}
