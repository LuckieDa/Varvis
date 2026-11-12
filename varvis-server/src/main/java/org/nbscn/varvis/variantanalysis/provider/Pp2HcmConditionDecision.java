package org.nbscn.varvis.variantanalysis.provider;

/** Consumes an already source-bound review outcome. This mapping grants no source qualification. */
final class Pp2HcmConditionDecision {
    record Decision(String status, String reasonCode) { }
    private Pp2HcmConditionDecision() { }
    static Decision evaluate(Pp2OtherPhenotypeSource.Outcome outcome) {
        return switch (outcome) {
            case NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE -> new Decision("MET", "PP2_HCM_NO_OTHER_PHENOTYPE_SUPPORT_IN_ASSESSED_SCOPE");
            case SUPPORTS_OTHER_PHENOTYPE -> new Decision("NOT_MET", "PP2_HCM_OTHER_PHENOTYPE_SUPPORTED");
            case UNKNOWN -> new Decision("NOT_EVALUATED", "PP2_HCM_OTHER_PHENOTYPE_REVIEW_INCOMPLETE");
            case CONFLICT -> new Decision("NOT_EVALUATED", "PP2_HCM_OTHER_PHENOTYPE_CONFLICT");
            case INVALID -> new Decision("NOT_EVALUATED", "PP2_HCM_OTHER_PHENOTYPE_REVIEW_INVALID");
        };
    }
}
