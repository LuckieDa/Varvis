package org.nbscn.varvis.variantanalysis.provider;

/** Pure generic decision table for the bounded ClinGen start-loss baseline. */
final class Pvs1StartLossDecision {
    enum Outcome { MET, NOT_APPLICABLE, INSUFFICIENT_INFORMATION, SOURCE_UNAVAILABLE, CONFLICT, RULE_NOT_IMPLEMENTED, SOFTWARE_UNSUPPORTED }
    record Facts(String sequence, String policy, String mechanism, String transcriptReview,
                 boolean transcriptSetComplete, Integer restartCdsPosition,
                 String upstreamReview, boolean upstreamSearchComplete,
                 boolean qualifyingIndependentPathogenicReference) { }
    record Result(Outcome outcome, String strength, String reasonCode) { }

    private Pvs1StartLossDecision() { }

    static Result evaluate(Facts facts) {
        if (facts == null) return result(Outcome.INSUFFICIENT_INFORMATION, "NONE", "PVS1_START_LOSS_FACTS_MISSING");
        if ("CONFLICT".equals(facts.sequence()))
            return result(Outcome.CONFLICT, "NONE", "PVS1_START_LOSS_EVIDENCE_CONFLICT");
        if ("UNSUPPORTED".equals(facts.sequence()))
            return result(Outcome.SOFTWARE_UNSUPPORTED, "NONE", "PVS1_START_LOSS_SCOPE_UNSUPPORTED");
        if (!"AVAILABLE".equals(facts.sequence()))
            return result(Outcome.INSUFFICIENT_INFORMATION, "NONE", "PVS1_START_LOSS_SEQUENCE_UNAVAILABLE");
        if ("PROHIBITED".equals(facts.policy()))
            return result(Outcome.NOT_APPLICABLE, "NONE", "PVS1_START_LOSS_POLICY_PROHIBITS_CRITERION");
        if ("UNSUPPORTED".equals(facts.policy()))
            return result(Outcome.RULE_NOT_IMPLEMENTED, "NONE", "PVS1_START_LOSS_POLICY_CONDITIONS_UNSUPPORTED");
        if (!"PERMITS".equals(facts.policy()))
            return result(Outcome.INSUFFICIENT_INFORMATION, "NONE", "PVS1_START_LOSS_POLICY_UNRESOLVED");
        if ("NOT_LOF".equals(facts.mechanism()))
            return result(Outcome.NOT_APPLICABLE, "NONE", "PVS1_START_LOSS_LOF_NOT_APPLICABLE");
        if (!"ESTABLISHED_LOF".equals(facts.mechanism()))
            return result(Outcome.INSUFFICIENT_INFORMATION, "NONE", "PVS1_START_LOSS_LOF_MECHANISM_UNRESOLVED");
        if ("CONFLICT".equals(facts.transcriptReview()))
            return result(Outcome.CONFLICT, "NONE", "PVS1_START_LOSS_EVIDENCE_CONFLICT");
        if ("FOUND_FUNCTIONAL_ALTERNATIVE".equals(facts.transcriptReview()))
            return result(Outcome.NOT_APPLICABLE, "NONE", "PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT");
        if (!"NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET".equals(facts.transcriptReview()) || !facts.transcriptSetComplete())
            return result(Outcome.INSUFFICIENT_INFORMATION, "NONE", "PVS1_START_LOSS_TRANSCRIPT_REVIEW_INCOMPLETE");
        if (facts.restartCdsPosition() == null || facts.restartCdsPosition() <= 3
                || (facts.restartCdsPosition() - 1) % 3 != 0)
            return result(Outcome.INSUFFICIENT_INFORMATION, "NONE", "PVS1_START_LOSS_RESTART_CANDIDATE_UNRESOLVED");
        if ("CONFLICT".equals(facts.upstreamReview()))
            return result(Outcome.CONFLICT, "NONE", "PVS1_START_LOSS_EVIDENCE_CONFLICT");
        if ("UNAVAILABLE".equals(facts.upstreamReview()))
            return result(Outcome.SOURCE_UNAVAILABLE, "NONE", "PVS1_START_LOSS_UPSTREAM_SEARCH_INCOMPLETE");
        if ("QUALIFYING_FOUND".equals(facts.upstreamReview()) && facts.qualifyingIndependentPathogenicReference())
            return result(Outcome.MET, "MODERATE", "PVS1_START_LOSS_UPSTREAM_PATHOGENIC_REFERENCE");
        if (!facts.upstreamSearchComplete() || !"COMPLETE".equals(facts.upstreamReview()))
            return result(Outcome.INSUFFICIENT_INFORMATION, "NONE", "PVS1_START_LOSS_UPSTREAM_REVIEW_INCOMPLETE");
        return facts.qualifyingIndependentPathogenicReference()
                ? result(Outcome.MET, "MODERATE", "PVS1_START_LOSS_UPSTREAM_PATHOGENIC_REFERENCE")
                : result(Outcome.MET, "SUPPORTING", "PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE");
    }

    private static Result result(Outcome outcome, String strength, String reason) {
        return new Result(outcome, strength, reason);
    }
}
