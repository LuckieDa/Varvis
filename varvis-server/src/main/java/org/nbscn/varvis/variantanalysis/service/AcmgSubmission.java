package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.nbscn.varvis.variantanalysis.service.ExplicitCaseInputCoordinator.*;
import java.util.List;

/** External declaration only. No execution witnesses, paths, ledgers, binding or context objects. */
public record AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId,
                             String dataClass, PatientDeclaration patient, List<HypothesisInput> hypotheses,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             List<AcmgRecommendations.Selection> recommendations,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             SavedObservationSelection association,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             SavedCalibrationSelection calibration,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             RevelApplicationSelection revelApplication,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Ps2Selection ps2Selection,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Pm6Selection pm6Selection,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Pm3Selection pm3Selection,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Bp2Selection bp2Selection,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Pp1Selection pp1Selection,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Bs4Selection bs4Selection,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Pp4Selection pp4Selection,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             List<String> selectedCriteria,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             String operationId) {
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId, String dataClass,
            PatientDeclaration patient, List<HypothesisInput> hypotheses, List<AcmgRecommendations.Selection> recommendations,
            SavedObservationSelection association, SavedCalibrationSelection calibration, RevelApplicationSelection revelApplication,
            Ps2Selection ps2Selection, Pm6Selection pm6Selection, Pm3Selection pm3Selection, Bp2Selection bp2Selection,
            Pp1Selection pp1Selection, Bs4Selection bs4Selection, Pp4Selection pp4Selection) {
        this(topN,declarationVersion,retryOf,sampleId,dataClass,patient,hypotheses,recommendations,association,calibration,
                revelApplication,ps2Selection,pm6Selection,pm3Selection,bp2Selection,pp1Selection,bs4Selection,pp4Selection,null,null);
    }
    public record Pp4Selection(String candidateId, String contextId, String alleleId, String observationId, String occurrenceId,
            String caseId, String familyContextId, String materialBindingSha256, String preparationToken, String policyVersion, Boolean confirmed) { }
    /** Preserve the complete BS4 constructor and every older overload. */
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId, String dataClass,
            PatientDeclaration patient, List<HypothesisInput> hypotheses, List<AcmgRecommendations.Selection> recommendations,
            SavedObservationSelection association, SavedCalibrationSelection calibration, RevelApplicationSelection revelApplication,
            Ps2Selection ps2Selection, Pm6Selection pm6Selection, Pm3Selection pm3Selection, Bp2Selection bp2Selection, Pp1Selection pp1Selection, Bs4Selection bs4Selection) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association,
                calibration, revelApplication, ps2Selection, pm6Selection, pm3Selection, bp2Selection, pp1Selection, bs4Selection, null);
    }
    public record Bs4Selection(String candidateId, String contextId, String alleleId, String observationId, String occurrenceId,
            String caseId, String familyContextId, String materialBindingSha256, String preparationToken, String policyVersion, Boolean confirmed) { }
    /** Preserve the complete PP1 constructor and every older overload. */
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId, String dataClass,
            PatientDeclaration patient, List<HypothesisInput> hypotheses, List<AcmgRecommendations.Selection> recommendations,
            SavedObservationSelection association, SavedCalibrationSelection calibration, RevelApplicationSelection revelApplication,
            Ps2Selection ps2Selection, Pm6Selection pm6Selection, Pm3Selection pm3Selection, Bp2Selection bp2Selection, Pp1Selection pp1Selection) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association,
                calibration, revelApplication, ps2Selection, pm6Selection, pm3Selection, bp2Selection, pp1Selection, null);
    }
    public record Pp1Selection(String candidateId, String contextId, String alleleId, String observationId, String occurrenceId,
            String caseId, String familyContextId, String materialBindingSha256, String preparationToken, String policyVersion, Boolean confirmed) { }
    /** Preserve the pre-PP1 canonical constructor and request identity. */
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId, String dataClass,
            PatientDeclaration patient, List<HypothesisInput> hypotheses, List<AcmgRecommendations.Selection> recommendations,
            SavedObservationSelection association, SavedCalibrationSelection calibration, RevelApplicationSelection revelApplication,
            Ps2Selection ps2Selection, Pm6Selection pm6Selection, Pm3Selection pm3Selection, Bp2Selection bp2Selection) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association,
                calibration, revelApplication, ps2Selection, pm6Selection, pm3Selection, bp2Selection, null);
    }
    public record Bp2Selection(String candidateId, String partnerCandidateId, String contextId, String targetAlleleId,
            String partnerAlleleId, String pairId, String observationId, String occurrenceId, String caseId,
            String familyContextId, String materialBindingSha256, String preparationToken, String policyVersion, Boolean confirmed) { }
    /** Preserve the previous canonical constructor and absent-field serialization. */
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId, String dataClass,
            PatientDeclaration patient, List<HypothesisInput> hypotheses, List<AcmgRecommendations.Selection> recommendations,
            SavedObservationSelection association, SavedCalibrationSelection calibration, RevelApplicationSelection revelApplication,
            Ps2Selection ps2Selection, Pm6Selection pm6Selection, Pm3Selection pm3Selection) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association,
                calibration, revelApplication, ps2Selection, pm6Selection, pm3Selection, null);
    }
    public record Pm3Selection(String candidateId, String partnerCandidateId, String contextId, String targetAlleleId,
            String partnerAlleleId, String pairId, String observationId, String occurrenceId, String caseId,
            String familyContextId, String materialBindingSha256, String preparationToken, String policyVersion, Boolean confirmed) { }
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId, String dataClass,
            PatientDeclaration patient, List<HypothesisInput> hypotheses, List<AcmgRecommendations.Selection> recommendations,
            SavedObservationSelection association, SavedCalibrationSelection calibration, RevelApplicationSelection revelApplication,
            Ps2Selection ps2Selection, Pm6Selection pm6Selection) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association,
                calibration, revelApplication, ps2Selection, pm6Selection, null);
    }
    public record Pm6Selection(String candidateId, String contextId, String observationId, String occurrenceId,
            String caseId, String materialBindingSha256, String preparationToken, String policyVersion, Boolean confirmed) { }
    /** Preserve every pre-PM6 constructor and its serialized identity when PM6 is absent. */
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId,
            String dataClass, PatientDeclaration patient, List<HypothesisInput> hypotheses,
            List<AcmgRecommendations.Selection> recommendations, SavedObservationSelection association,
            SavedCalibrationSelection calibration, RevelApplicationSelection revelApplication, Ps2Selection ps2Selection) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association,
                calibration, revelApplication, ps2Selection, null);
    }
    public record Ps2Selection(String candidateId, String contextId, String observationId, String occurrenceId,
            String caseId, String materialBindingSha256, String preparationToken, String policyVersion, Boolean confirmed) { }
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId,
            String dataClass, PatientDeclaration patient, List<HypothesisInput> hypotheses,
            List<AcmgRecommendations.Selection> recommendations, SavedObservationSelection association,
            SavedCalibrationSelection calibration, RevelApplicationSelection revelApplication) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association,
                calibration, revelApplication, null);
    }
    public record RevelApplicationSelection(String operation, String sourceRunId, String sourceRequestSha256, String bindingSha256) { }
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId,
            String dataClass, PatientDeclaration patient, List<HypothesisInput> hypotheses,
            List<AcmgRecommendations.Selection> recommendations, SavedObservationSelection association, SavedCalibrationSelection calibration) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association, calibration, null);
    }
    public record SavedCalibrationSelection(String operation, String sourceRunId, String sourceRequestSha256,
            String executionSourceSha256, String calibrationCanonicalSha256, String adoptionSha256,
            List<CalibrationResultReference> results) { }
    public record CalibrationResultReference(String candidateId, String sourceResultId, String sourceObservationSha256) { }
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId,
            String dataClass, PatientDeclaration patient, List<HypothesisInput> hypotheses,
            List<AcmgRecommendations.Selection> recommendations, SavedObservationSelection association) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, association, null);
    }
    public record SavedObservationSelection(String operation, String sourceRunId, String sourceRequestSha256,
            String executionSourceSha256, List<AnnotationSelection> selections) { }
    public record AnnotationSelection(String candidateId, String sourceObservationSha256, String selectedAnnotationId,
            String annotationSourceSha256, String versionedTranscriptId, String selectionOrigin) { }
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId,
                          String dataClass, PatientDeclaration patient, List<HypothesisInput> hypotheses,
                          List<AcmgRecommendations.Selection> recommendations) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, recommendations, null);
    }
    public AcmgSubmission(int topN, String declarationVersion, String retryOf, String sampleId,
                          String dataClass, PatientDeclaration patient, List<HypothesisInput> hypotheses) {
        this(topN, declarationVersion, retryOf, sampleId, dataClass, patient, hypotheses, null);
    }
    public static final int MAX_BYTES = 1024 * 1024;
    public static AcmgSubmission parse(byte[] bytes, ObjectMapper configured) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("ACMG_REQUEST_SIZE_INVALID");
        ObjectMapper strict = configured.copy().findAndRegisterModules()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT, DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS);
        strict.setConfig(strict.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        try {
            JsonNode tree = strict.readTree(bytes);
            checkSize(tree, 0);
            JsonNode n = tree.path("topN");
            if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 1 || n.intValue() > 100) throw new IllegalArgumentException();
            if (!tree.path("hypotheses").isArray() || tree.path("hypotheses").size() > 200) throw new IllegalArgumentException();
            if (tree.hasNonNull("recommendations")) {
                if (!tree.path("recommendations").isArray() || tree.path("recommendations").size() > 100) throw new IllegalArgumentException();
                for (var reference : tree.path("recommendations")) if (!reference.isObject()) throw new IllegalArgumentException();
            }
            for (JsonNode input : tree.path("hypotheses")) {
                var version = input.path("transcript").path("version");
                if (!version.isMissingNode() && !version.isNull()
                        && (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1)) throw new IllegalArgumentException();
            }
            AcmgSubmission value = strict.treeToValue(tree, AcmgSubmission.class);
            if (value.declarationVersion() == null || !value.declarationVersion().matches("[A-Za-z0-9_.-]{1,80}")
                    || value.sampleId() == null || value.sampleId().isBlank()
                    || !List.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(value.dataClass() == null ? "" : value.dataClass())
                    || (value.retryOf() != null && !value.retryOf().matches("[a-f0-9-]{36}"))) throw new IllegalArgumentException();
            if (value.selectedCriteria() != null && (value.selectedCriteria().isEmpty() ||
                    value.selectedCriteria().size() > 18 || value.selectedCriteria().stream().anyMatch(c -> c == null || !c.matches("ACMG_AMP_2015:(?:[A-Z]{2}|PVS)[1-9]")) ||
                    value.selectedCriteria().stream().distinct().count() != value.selectedCriteria().size())) throw new IllegalArgumentException();
            if (value.operationId() != null) java.util.UUID.fromString(value.operationId());
            return value;
        } catch (Exception invalid) { throw new IllegalArgumentException("ACMG_REQUEST_INVALID"); }
    }
    private static void checkSize(JsonNode node, int depth) {
        if (depth > 12 || node.isTextual() && node.textValue().length() > 4096
                || node.isArray() && node.size() > 200 || node.isObject() && node.size() > 20) throw new IllegalArgumentException();
        node.forEach(n -> checkSize(n, depth + 1));
    }
}
