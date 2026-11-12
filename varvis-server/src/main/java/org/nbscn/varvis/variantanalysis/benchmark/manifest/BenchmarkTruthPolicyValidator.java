package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BenchmarkTruthPolicyValidator {

    private BenchmarkTruthPolicyValidator() {
    }

    public static List<BenchmarkValidationError> validate(BenchmarkTruthPolicyManifest manifest) {
        List<BenchmarkValidationError> errors = new ArrayList<BenchmarkValidationError>();
        if (manifest == null) {
            add(errors, "REQUIRED_FIELD", "$", "truth policy manifest is required");
            return errors;
        }
        required(errors, manifest.getSchemaVersion(), "schemaVersion");
        if (hasText(manifest.getSchemaVersion())
                && !BenchmarkManifestSchema.TRUTH_POLICY.equals(manifest.getSchemaVersion())) {
            add(errors, "UNSUPPORTED_SCHEMA_VERSION", "schemaVersion", "unsupported truth policy schema");
        }
        required(errors, manifest.getPolicyVersion(), "policyVersion");
        if (hasText(manifest.getPolicyVersion())
                && !PublicPilotTruthPolicy.POLICY_VERSION.equals(manifest.getPolicyVersion())) {
            add(errors, "UNSUPPORTED_POLICY_VERSION", "policyVersion", "unsupported truth policy version");
        }
        required(errors, manifest.getDatasetId(), "datasetId");
        required(errors, manifest.getDatasetVersion(), "datasetVersion");
        required(errors, manifest.getCreatedAt(), "createdAt");
        if (hasText(manifest.getCreatedAt())) {
            try { Instant.parse(manifest.getCreatedAt()); }
            catch (DateTimeParseException ex) { add(errors, "INVALID_TIMESTAMP", "createdAt", "UTC ISO-8601 required"); }
        }
        required(errors, manifest.getStatus(), "status");
        if (hasText(manifest.getStatus()) && !"PROPOSED".equals(manifest.getStatus())
                && !"FROZEN".equals(manifest.getStatus())) {
            add(errors, "UNSUPPORTED_STATUS", "status", "status must be PROPOSED or FROZEN");
        }
        required(errors, manifest.getOfficialCaseMetric(), "officialCaseMetric");
        required(errors, manifest.getAuxiliaryCompleteTruthSetMetric(), "auxiliaryCompleteTruthSetMetric");
        if (manifest.getAssessments() == null || manifest.getAssessments().isEmpty()) {
            add(errors, "REQUIRED_FIELD", "assessments", "at least one assessment is required");
        } else {
            Set<String> ids = new HashSet<String>();
            for (int i = 0; i < manifest.getAssessments().size(); i++) {
                BenchmarkTruthPolicyAssessment item = manifest.getAssessments().get(i);
                String path = "assessments[" + i + "]";
                if (item == null) {
                    add(errors, "REQUIRED_FIELD", path, "assessment is required");
                    continue;
                }
                required(errors, item.getCaseId(), path + ".caseId");
                if (hasText(item.getCaseId()) && !ids.add(item.getCaseId())) {
                    add(errors, "DUPLICATE_CASE_ID", path + ".caseId", "case assessment must be unique");
                }
                if (item.getTruthSetFingerprint() == null
                        || !item.getTruthSetFingerprint().matches("[0-9a-f]{64}")) {
                    add(errors, "INVALID_CHECKSUM", path + ".truthSetFingerprint", "lowercase SHA-256 required");
                }
                if (item.getSemantics() == null) {
                    add(errors, "REQUIRED_FIELD", path + ".semantics", "truth semantics are required");
                }
                if (item.getEligibilityStatus() == null) {
                    add(errors, "REQUIRED_FIELD", path + ".eligibilityStatus", "eligibility is required");
                } else if (item.getEligibilityStatus() == BenchmarkPilotEligibilityStatus.ELIGIBLE
                        && item.getExclusionReasons() != null && !item.getExclusionReasons().isEmpty()) {
                    add(errors, "ELIGIBILITY_REASON_CONFLICT", path, "eligible cases cannot have exclusion reasons");
                } else if (item.getEligibilityStatus() != BenchmarkPilotEligibilityStatus.ELIGIBLE
                        && (item.getExclusionReasons() == null || item.getExclusionReasons().isEmpty())) {
                    add(errors, "EXCLUSION_REASON_REQUIRED", path + ".exclusionReasons", "excluded/invalid cases require reasons");
                }
            }
        }
        return errors;
    }

    public static void requireValid(BenchmarkTruthPolicyManifest manifest) {
        List<BenchmarkValidationError> errors = validate(manifest);
        if (!errors.isEmpty()) {
            throw new BenchmarkManifestValidationException(errors);
        }
    }

    private static void required(List<BenchmarkValidationError> errors, String value, String path) {
        if (!hasText(value)) {
            add(errors, "REQUIRED_FIELD", path, "value is required");
        }
    }

    private static void add(List<BenchmarkValidationError> errors, String code, String path, String message) {
        errors.add(new BenchmarkValidationError(code, path, message));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
