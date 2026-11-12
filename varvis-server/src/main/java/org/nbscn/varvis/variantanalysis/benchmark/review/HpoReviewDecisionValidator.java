package org.nbscn.varvis.variantanalysis.benchmark.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HpoReviewDecisionValidator {

    public HpoReviewValidationResult validate(HpoReviewPacket packet, HpoReviewDecisionDocument document) {
        List<String> errors = new ArrayList<String>();
        if (packet == null || document == null) {
            return invalid("REVIEW_PACKET_OR_DOCUMENT_MISSING");
        }
        if (!equals(packet.getReviewId(), document.getReviewId())) {
            errors.add("REVIEW_ID_MISMATCH");
        }
        if (!equals(packet.getPacketFingerprint(), document.getPacketFingerprint())) {
            errors.add("PACKET_FINGERPRINT_MISMATCH");
        }
        if (!equals(packet.getPacketContentChecksum(), document.getPacketContentChecksum())
                || !equals(packet.getPacketContentChecksum(), TruthMaskedHpoReviewPacketGenerator.calculatePacketContentChecksum(packet))) {
            errors.add("PACKET_CONTENT_TAMPERED");
        }
        if (!"TRUTH_MASKED_DEVELOPMENT_REVIEW".equals(document.getReviewMethod())) {
            errors.add("REVIEW_METHOD_INVALID");
        }
        if (!"REPORT_PREVIOUSLY_DISPLAYED".equals(document.getPriorTruthExposure())
                && !"NOT_RECORDED".equals(document.getPriorTruthExposure())) {
            errors.add("PRIOR_TRUTH_EXPOSURE_INVALID");
        }
        if (!Boolean.FALSE.equals(document.getActiveTruthReviewReported())) {
            errors.add("ACTIVE_TRUTH_REVIEW_MUST_BE_FALSE");
        }
        if (!Boolean.FALSE.equals(document.getEligibleForStrictBlindedEvaluation())) {
            errors.add("STRICT_BLINDED_ELIGIBILITY_MUST_BE_FALSE");
        }
        validateAiAssistance(packet, document.getAiAssistance(), errors);
        if (!errors.isEmpty()) {
            return finish(document, HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID, errors);
        }

        Map<String, HpoReviewTerm> packetTerms = new HashMap<String, HpoReviewTerm>();
        for (HpoReviewTerm term : safe(packet.getTerms())) {
            if (term == null || !hasText(term.getHpoId()) || packetTerms.put(term.getHpoId(), term) != null) {
                errors.add("PACKET_TERM_INVALID");
            }
        }
        Map<String, HpoReviewTermDecision> decisions = new HashMap<String, HpoReviewTermDecision>();
        boolean anyDecision = false;
        boolean uncertain = false;
        boolean changed = false;
        for (HpoReviewTermDecision decision : safe(document.getTermDecisions())) {
            if (decision == null || !hasText(decision.getHpoId()) || decision.getOriginalStatus() == null) {
                errors.add("TERM_DECISION_INVALID");
                continue;
            }
            if (decisions.put(decision.getHpoId(), decision) != null) {
                errors.add("DUPLICATE_TERM_DECISION");
                continue;
            }
            HpoReviewTerm term = packetTerms.get(decision.getHpoId());
            if (term == null || term.getOriginalStatus() != decision.getOriginalStatus()) {
                errors.add("TERM_NOT_IN_PACKET_OR_STATUS_MISMATCH");
                continue;
            }
            if (decision.getDecision() == null) {
                continue;
            }
            anyDecision = true;
            if (!allowed(term.getOriginalStatus(), decision.getDecision())) {
                errors.add("UNSUPPORTED_TERM_DECISION");
            }
            if (decision.getDecision() == HpoReviewDecisionType.UNCERTAIN) {
                uncertain = true;
            }
            if (requiresReason(decision.getDecision()) && !hasText(decision.getMappingErrorReason())) {
                errors.add("MAPPING_ERROR_REASON_REQUIRED");
            }
            if (isChange(decision.getDecision())) {
                changed = true;
            }
        }
        if (!errors.isEmpty()) {
            return finish(document, HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID, errors);
        }
        if (!anyDecision && safe(document.getAdditions()).isEmpty()) {
            return finish(document, HpoReviewReadinessStatus.HUMAN_REVIEW_PENDING, errors);
        }
        if (decisions.size() != packetTerms.size()) {
            return finish(document, HpoReviewReadinessStatus.HUMAN_REVIEW_INCOMPLETE, Collections.singletonList("TERM_DECISIONS_INCOMPLETE"));
        }
        for (String id : packetTerms.keySet()) {
            HpoReviewTermDecision decision = decisions.get(id);
            if (decision == null || decision.getDecision() == null) {
                return finish(document, HpoReviewReadinessStatus.HUMAN_REVIEW_INCOMPLETE, Collections.singletonList("TERM_DECISIONS_INCOMPLETE"));
            }
        }
        if (uncertain) {
            return finish(document, HpoReviewReadinessStatus.HUMAN_REVIEW_INCOMPLETE, Collections.singletonList("UNCERTAIN_DECISION_PRESENT"));
        }

        Set<String> observed = new HashSet<String>();
        Set<String> excluded = new HashSet<String>();
        for (HpoReviewTerm term : packetTerms.values()) {
            if (term.getOriginalStatus() == HpoReviewOriginalStatus.OBSERVED) observed.add(term.getHpoId());
            else excluded.add(term.getHpoId());
        }
        for (HpoReviewAddition addition : safe(document.getAdditions())) {
            if (addition == null || !"ADD_OBSERVED_WITH_PATIENT_EVIDENCE".equals(addition.getDecision())) {
                errors.add("UNSUPPORTED_ADDITION_DECISION");
                continue;
            }
            if (!"PATIENT_SPECIFIC_SOURCE_CONTEXT_AVAILABLE".equals(packet.getSourceContext())) {
                errors.add("ADDITION_REQUIRES_PATIENT_SPECIFIC_SOURCE_CONTEXT");
            }
            if (!hasText(addition.getHpoId()) || !addition.getHpoId().matches("HP:[0-9]{7}")) {
                errors.add("ADDITION_HPO_ID_INVALID");
            }
            if (!hasText(addition.getPatientEvidenceExcerpt()) || !hasText(addition.getSourceLocator())
                    || !hasText(addition.getRationale()) || !Boolean.TRUE.equals(addition.getReviewerConfirmed())) {
                errors.add("ADDITION_PATIENT_EVIDENCE_REQUIRED");
            }
            if (observed.contains(addition.getHpoId()) || excluded.contains(addition.getHpoId())) {
                errors.add("ADDITION_CONFLICTS_WITH_ORIGINAL_TERM");
            }
            changed = true;
        }
        if (!Collections.disjoint(observed, excluded)) {
            errors.add("OBSERVED_EXCLUDED_CONFLICT");
        }
        if (!errors.isEmpty()) {
            return finish(document, HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID, errors);
        }
        return finish(document, changed ? HpoReviewReadinessStatus.READY_WITH_REVIEWED_CHANGES
                : HpoReviewReadinessStatus.READY_IDENTICAL_TO_G1, errors);
    }

    private boolean allowed(HpoReviewOriginalStatus original, HpoReviewDecisionType decision) {
        return decision == HpoReviewDecisionType.UNCERTAIN
                || (original == HpoReviewOriginalStatus.OBSERVED
                && (decision == HpoReviewDecisionType.KEEP_OBSERVED || decision == HpoReviewDecisionType.REMOVE_MAPPING_ERROR))
                || (original == HpoReviewOriginalStatus.EXCLUDED
                && (decision == HpoReviewDecisionType.KEEP_EXCLUDED || decision == HpoReviewDecisionType.REMOVE_EXCLUSION_MAPPING_ERROR));
    }

    private boolean requiresReason(HpoReviewDecisionType decision) {
        return decision == HpoReviewDecisionType.REMOVE_MAPPING_ERROR
                || decision == HpoReviewDecisionType.REMOVE_EXCLUSION_MAPPING_ERROR;
    }

    private boolean isChange(HpoReviewDecisionType decision) {
        return decision == HpoReviewDecisionType.REMOVE_MAPPING_ERROR
                || decision == HpoReviewDecisionType.REMOVE_EXCLUSION_MAPPING_ERROR;
    }

    private void validateAiAssistance(HpoReviewPacket packet, HpoReviewAiAssistanceProvenance assistance,
                                      List<String> errors) {
        // Older decision templates did not record assistance; their absence remains compatible.
        if (assistance == null) return;
        if (!Boolean.TRUE.equals(assistance.getAiAssistanceUsed())) {
            errors.add("AI_ASSISTANCE_PROVENANCE_INVALID");
            return;
        }
        if (!"HPO_ID_LABEL_AND_INTERNAL_CONSISTENCY_CHECK".equals(assistance.getAssistanceScope())
                || !Boolean.TRUE.equals(assistance.getHumanFinalConfirmationRequired())
                || !Boolean.TRUE.equals(assistance.getHumanFinalConfirmation())
                || !equals(packet.getHpoOntologyVersion(), assistance.getSourceHpoOntologyVersion())
                || !"2026-06-23".equals(assistance.getSemanticReferenceHpoVersion())
                || !"https://purl.obolibrary.org/obo/hp.obo".equals(assistance.getSemanticReferenceSource())) {
            errors.add("AI_ASSISTANCE_PROVENANCE_INVALID");
        }
    }

    private HpoReviewValidationResult invalid(String error) { return result(HpoReviewReadinessStatus.HUMAN_REVIEW_INVALID, Collections.singletonList(error)); }
    private HpoReviewValidationResult finish(HpoReviewDecisionDocument document, HpoReviewReadinessStatus status, List<String> errors) {
        if (document != null) {
            document.setReviewCompletionStatus(status);
            Map<String, Integer> counts = new java.util.LinkedHashMap<String, Integer>();
            counts.put("totalOriginalTerms", safe(document.getTermDecisions()).size());
            int completed = 0;
            for (HpoReviewTermDecision decision : safe(document.getTermDecisions())) if (decision != null && decision.getDecision() != null) completed++;
            counts.put("completedOriginalTermDecisions", completed);
            counts.put("additions", safe(document.getAdditions()).size());
            document.setDecisionCounts(counts);
        }
        return result(status, errors);
    }
    private HpoReviewValidationResult result(HpoReviewReadinessStatus status, List<String> errors) { HpoReviewValidationResult result = new HpoReviewValidationResult(status); result.setErrorCodes(errors); return result; }
    private boolean equals(String a, String b) { return a == null ? b == null : a.equals(b); }
    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private <T> List<T> safe(List<T> values) { return values == null ? Collections.<T>emptyList() : values; }
}
