package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse.FailureCategory;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse.FailureCode;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse.NextAction;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse.RetryDisposition;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse.RunState;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse.Stage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds a bounded, privacy-safe diagnosis without invoking any runtime dependency. */
public final class VariantAnalysisDiagnosticAssembler {

    private static final int MAX_FAILURE_PREFIX_LENGTH = 96;
    private static final Map<String, FailureDiagnosis> KNOWN_FAILURES = knownFailures();
    private static final FailureDiagnosis UNKNOWN_FAILURE = new FailureDiagnosis(
            FailureCode.UNKNOWN_FAILURE,
            FailureCategory.UNKNOWN,
            NextAction.CONTACT_OPERATOR,
            RetryDisposition.AFTER_OPERATOR_REMEDIATION
    );

    public VariantAnalysisDiagnosticResponse assemble(VariantAnalysisJob job) {
        if (job == null) {
            throw new IllegalArgumentException("job is required");
        }
        VariantAnalysisStatus status = job.getStatus();
        Stage stage = stage(status);
        if (status == VariantAnalysisStatus.UPLOADED) {
            return response(job, RunState.WAITING, stage, noFailure(NextAction.WAIT_FOR_COMPLETION));
        }
        if (status == VariantAnalysisStatus.VALIDATING
                || status == VariantAnalysisStatus.NORMALIZING
                || status == VariantAnalysisStatus.QC_EXTRACTING
                || status == VariantAnalysisStatus.BASE_ANNOTATING
                || status == VariantAnalysisStatus.RANKING) {
            return response(job, RunState.RUNNING, stage, noFailure(NextAction.WAIT_FOR_COMPLETION));
        }
        if (status == VariantAnalysisStatus.READY) {
            return response(job, RunState.SUCCEEDED, stage, noFailure(NextAction.REVIEW_RESULTS));
        }
        if (status == VariantAnalysisStatus.FAILED) {
            return response(job, RunState.FAILED, stage, failure(job));
        }
        return response(job, RunState.UNKNOWN, Stage.UNKNOWN, UNKNOWN_FAILURE);
    }

    private VariantAnalysisDiagnosticResponse response(VariantAnalysisJob job,
                                                       RunState runState,
                                                       Stage stage,
                                                       FailureDiagnosis diagnosis) {
        return new VariantAnalysisDiagnosticResponse(
                runState,
                stage,
                diagnosis.failureCode,
                diagnosis.failureCategory,
                diagnosis.nextAction,
                diagnosis.retryDisposition,
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }

    private FailureDiagnosis noFailure(NextAction nextAction) {
        return new FailureDiagnosis(
                FailureCode.NONE,
                FailureCategory.NONE,
                nextAction,
                RetryDisposition.NOT_APPLICABLE
        );
    }

    private FailureDiagnosis failure(VariantAnalysisJob job) {
        String jobFailure = job.getErrorMessage();
        if (jobFailure != null && !jobFailure.isEmpty()) {
            FailureDiagnosis fromJob = knownFailure(jobFailure);
            return fromJob == null ? UNKNOWN_FAILURE : fromJob;
        }
        EngineMetadata metadata = job.getEngineMetadata();
        FailureDiagnosis fromEngine = metadata == null
                ? null : knownFailure(metadata.getUnavailableReason());
        return fromEngine == null ? UNKNOWN_FAILURE : fromEngine;
    }

    private FailureDiagnosis knownFailure(String rawFailure) {
        String prefix = failurePrefix(rawFailure);
        return prefix == null ? null : KNOWN_FAILURES.get(prefix);
    }

    /**
     * Extracts only the complete token before the first colon. The suffix is
     * deliberately discarded before lookup and can never reach the response.
     */
    private String failurePrefix(String rawFailure) {
        if (rawFailure == null) {
            return null;
        }
        int colon = -1;
        int scanLength = Math.min(rawFailure.length(), MAX_FAILURE_PREFIX_LENGTH + 1);
        for (int index = 0; index < scanLength; index++) {
            if (rawFailure.charAt(index) == ':') {
                colon = index;
                break;
            }
        }
        if (colon < 0 && rawFailure.length() > MAX_FAILURE_PREFIX_LENGTH) {
            return null;
        }
        String prefix = colon < 0 ? rawFailure : rawFailure.substring(0, colon);
        return prefix.isEmpty() || prefix.length() > MAX_FAILURE_PREFIX_LENGTH ? null : prefix;
    }

    private Stage stage(VariantAnalysisStatus status) {
        if (status == null) {
            return Stage.UNKNOWN;
        }
        switch (status) {
            case UPLOADED:
                return Stage.UPLOADED;
            case VALIDATING:
                return Stage.VALIDATING;
            case NORMALIZING:
                return Stage.NORMALIZING;
            case QC_EXTRACTING:
                return Stage.QC_EXTRACTING;
            case BASE_ANNOTATING:
                return Stage.BASE_ANNOTATING;
            case RANKING:
                return Stage.RANKING;
            case READY:
                return Stage.READY;
            case FAILED:
                return Stage.FAILED;
            default:
                return Stage.UNKNOWN;
        }
    }

    private static Map<String, FailureDiagnosis> knownFailures() {
        Map<String, FailureDiagnosis> values = new LinkedHashMap<String, FailureDiagnosis>();

        FailureDiagnosis input = new FailureDiagnosis(
                FailureCode.INVALID_INPUT,
                FailureCategory.INPUT,
                NextAction.CORRECT_INPUT_AND_RESUBMIT,
                RetryDisposition.AFTER_INPUT_CORRECTION
        );
        register(values, input,
                "INVALID_INPUT",
                "EXOMISER_GRCH38_REQUIRED",
                "EXOMISER_INPUT_VCF_UNREADABLE",
                "EXOMISER_SAMPLE_ID_MISSING",
                "EXOMISER_SAMPLE_ID_NOT_FOUND",
                "EXOMISER_SAMPLE_ID_NOT_FOUND_IN_ANALYSIS_VCF",
                "EXOMISER_TRIO_JOINT_VCF_REQUIRED",
                "EXOMISER_TRIO_SAMPLE_MAPPING_INVALID",
                "EXOMISER_TRIO_PEDIGREE_SEX_INVALID",
                "EXOMISER_ANALYSIS_VCF_MISSING",
                "EXOMISER_VCF_HEADER_CHROM_LINE_MISSING",
                "EXOMISER_HPO_TERM_INVALID");

        FailureDiagnosis unavailable = new FailureDiagnosis(
                FailureCode.ENGINE_UNAVAILABLE,
                FailureCategory.CONFIGURATION,
                NextAction.CONTACT_OPERATOR,
                RetryDisposition.AFTER_OPERATOR_REMEDIATION
        );
        register(values, unavailable,
                "ENGINE_UNAVAILABLE",
                "EXOMISER_UNAVAILABLE",
                "EXOMISER_DISABLED",
                "EXOMISER_TIMEOUT_INVALID",
                "EXOMISER_CLI_VERSION_UNSUPPORTED",
                "EXOMISER_DATA_VERSION_UNSUPPORTED",
                "EXOMISER_JAVA_HEAP_INVALID",
                "EXOMISER_RESOURCE_FINGERPRINT_MISSING",
                "EXOMISER_HOST_PLATFORM_UNSUPPORTED",
                "EXOMISER_JAVA_RELEASE_UNREADABLE",
                "EXOMISER_JAVA21_WINDOWS_UNAVAILABLE",
                "EXOMISER_JAVA21_WINDOWS_IDENTITY_INVALID",
                "EXOMISER_JAVA21_LINUX_UNAVAILABLE",
                "EXOMISER_JAVA21_LINUX_IDENTITY_INVALID",
                "EXOMISER_CLI_UNAVAILABLE",
                "EXOMISER_2602_RESOURCE_UNAVAILABLE",
                "EXOMISER_ANALYSIS_TEMPLATE_UNREADABLE",
                "EXOMISER_ANALYSIS_TEMPLATE_MISSING",
                "EXOMISER_INHERITANCE_MODE_MISSING",
                "EXOMISER_G1_HIPHIVE_MISSING",
                "EXOMISER_G0_HIPHIVE_DISABLE_FAILED",
                "EXOMISER_ADAPTER_UNAVAILABLE");

        register(values, new FailureDiagnosis(
                        FailureCode.ENGINE_TIMEOUT,
                        FailureCategory.TIMEOUT,
                        NextAction.RESUBMIT_ANALYSIS,
                        RetryDisposition.RESUBMISSION_ALLOWED),
                "EXOMISER_TIMEOUT", "TOOL_TIMEOUT");

        register(values, new FailureDiagnosis(
                        FailureCode.ENGINE_EXECUTION_FAILED,
                        FailureCategory.EXECUTION,
                        NextAction.CONTACT_OPERATOR,
                        RetryDisposition.AFTER_OPERATOR_REMEDIATION),
                "EXECUTION_FAILED",
                "EXOMISER_NONZERO_EXIT",
                "EXOMISER_PROCESS_START_OR_IO_FAILED",
                "EXOMISER_EXECUTION_FAILED");

        register(values, new FailureDiagnosis(
                        FailureCode.ENGINE_INTERRUPTED,
                        FailureCategory.INTERRUPTION,
                        NextAction.RESUBMIT_ANALYSIS,
                        RetryDisposition.RESUBMISSION_ALLOWED),
                "EXOMISER_PROCESS_INTERRUPTED");

        register(values, new FailureDiagnosis(
                        FailureCode.ENGINE_OUTPUT_INVALID,
                        FailureCategory.OUTPUT,
                        NextAction.CONTACT_OPERATOR,
                        RetryDisposition.AFTER_OPERATOR_REMEDIATION),
                "RESULT_PARSE_FAILED",
                "TSV_COLUMN_COUNT_MISMATCH",
                "EXOMISER_OUTPUT_VARIANTS_MISSING",
                "EXOMISER_OUTPUT_VARIANTS_EMPTY",
                "EXOMISER_OUTPUT_PARSE_FAILED");

        register(values, new FailureDiagnosis(
                        FailureCode.NORMALIZATION_FAILED,
                        FailureCategory.NORMALIZATION,
                        NextAction.CONTACT_OPERATOR,
                        RetryDisposition.AFTER_OPERATOR_REMEDIATION),
                "EXOMISER_LIFTOVER_OUTPUT_UNREADABLE",
                "EXOMISER_LIFTOVER_NO_RECORDS");

        register(values, new FailureDiagnosis(
                        FailureCode.PERSISTENCE_INTERRUPTED,
                        FailureCategory.PERSISTENCE,
                        NextAction.CONTACT_OPERATOR,
                        RetryDisposition.AFTER_OPERATOR_REMEDIATION),
                "EXOMISER_PERSISTENCE_INCOMPLETE");

        register(values, new FailureDiagnosis(
                        FailureCode.APPLICATION_RESTARTED,
                        FailureCategory.INTERRUPTION,
                        NextAction.RESUBMIT_ANALYSIS,
                        RetryDisposition.RESUBMISSION_ALLOWED),
                "Application restarted before analysis completed");

        register(values, new FailureDiagnosis(
                        FailureCode.INTERNAL_FAILURE,
                        FailureCategory.INTERNAL,
                        NextAction.CONTACT_OPERATOR,
                        RetryDisposition.AFTER_OPERATOR_REMEDIATION),
                "EXOMISER_JOB_MISSING",
                "EXOMISER_ADAPTER_RESULT_INVALID");

        return Collections.unmodifiableMap(values);
    }

    private static void register(Map<String, FailureDiagnosis> target,
                                 FailureDiagnosis diagnosis,
                                 String... prefixes) {
        for (String prefix : prefixes) {
            if (target.put(prefix, diagnosis) != null) {
                throw new IllegalStateException("Duplicate diagnostic failure prefix: " + prefix);
            }
        }
    }

    private static final class FailureDiagnosis {

        private final FailureCode failureCode;
        private final FailureCategory failureCategory;
        private final NextAction nextAction;
        private final RetryDisposition retryDisposition;

        private FailureDiagnosis(FailureCode failureCode,
                                 FailureCategory failureCategory,
                                 NextAction nextAction,
                                 RetryDisposition retryDisposition) {
            this.failureCode = failureCode;
            this.failureCategory = failureCategory;
            this.nextAction = nextAction;
            this.retryDisposition = retryDisposition;
        }
    }
}
