package org.nbscn.varvis.variantanalysis.benchmark.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class HpoReviewG2InputBuilder {
    private final HpoReviewDecisionValidator validator = new HpoReviewDecisionValidator();

    public ReviewedHpoInput build(HpoReviewPacket packet, HpoReviewDecisionDocument document) {
        HpoReviewValidationResult validation = validator.validate(packet, document);
        if (!validation.isReady()) {
            throw new IllegalArgumentException("G2_REVIEW_NOT_READY: " + validation.getStatus());
        }
        Set<String> observed = new HashSet<String>();
        Set<String> excluded = new HashSet<String>();
        java.util.Map<String, HpoReviewTermDecision> byId = new java.util.HashMap<String, HpoReviewTermDecision>();
        for (HpoReviewTermDecision decision : document.getTermDecisions()) byId.put(decision.getHpoId(), decision);
        for (HpoReviewTerm term : packet.getTerms()) {
            HpoReviewDecisionType decision = byId.get(term.getHpoId()).getDecision();
            if (term.getOriginalStatus() == HpoReviewOriginalStatus.OBSERVED && decision == HpoReviewDecisionType.KEEP_OBSERVED) observed.add(term.getHpoId());
            if (term.getOriginalStatus() == HpoReviewOriginalStatus.EXCLUDED && decision == HpoReviewDecisionType.KEEP_EXCLUDED) excluded.add(term.getHpoId());
        }
        for (HpoReviewAddition addition : document.getAdditions()) observed.add(addition.getHpoId());
        ReviewedHpoInput input = new ReviewedHpoInput();
        input.setObservedTerms(sorted(observed)); input.setExcludedTerms(sorted(excluded));
        input.setProvenance("reviewMethod=" + document.getReviewMethod() + ";priorTruthExposure=" + document.getPriorTruthExposure()
                + ";packetFingerprint=" + document.getPacketFingerprint());
        return input;
    }

    private ArrayList<String> sorted(Set<String> values) { ArrayList<String> result = new ArrayList<String>(values); Collections.sort(result); return result; }
}
