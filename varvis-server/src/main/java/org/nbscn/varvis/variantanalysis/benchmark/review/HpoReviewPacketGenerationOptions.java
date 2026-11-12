package org.nbscn.varvis.variantanalysis.benchmark.review;

/** Bounded generation options for reusing the frozen truth-masked review contract. */
public final class HpoReviewPacketGenerationOptions {
    private final int expectedCaseCount;
    private final String reviewIdPrefix;
    private final String hpoOntologyVersion;
    private final String purpose;
    private final String priorTruthExposure;

    private HpoReviewPacketGenerationOptions(int expectedCaseCount, String reviewIdPrefix,
                                             String hpoOntologyVersion, String purpose,
                                             String priorTruthExposure) {
        if (expectedCaseCount <= 0) {
            throw new IllegalArgumentException("expectedCaseCount must be positive");
        }
        if (reviewIdPrefix == null || !reviewIdPrefix.matches("[A-Z][A-Z0-9-]*-")) {
            throw new IllegalArgumentException("reviewIdPrefix must be a stable uppercase ASCII prefix");
        }
        this.expectedCaseCount = expectedCaseCount;
        this.reviewIdPrefix = reviewIdPrefix;
        this.hpoOntologyVersion = requireText(hpoOntologyVersion, "hpoOntologyVersion");
        this.purpose = requireText(purpose, "purpose");
        this.priorTruthExposure = requireText(priorTruthExposure, "priorTruthExposure");
    }

    public static HpoReviewPacketGenerationOptions smokeDefaults() {
        return new HpoReviewPacketGenerationOptions(5, "SMOKE-HPO-",
                "UNAVAILABLE_IN_FROZEN_MANIFEST", "DEVELOPMENT_HPO_REVIEW_ONLY",
                "REPORT_PREVIOUSLY_DISPLAYED");
    }

    public static HpoReviewPacketGenerationOptions pilotProposal(int expectedCaseCount) {
        return new HpoReviewPacketGenerationOptions(expectedCaseCount, "PILOT-HPO-",
                "2026-06-23", "DEVELOPMENT_PILOT_HPO_REVIEW_PROPOSAL_ONLY",
                "NOT_RECORDED");
    }

    public int getExpectedCaseCount() { return expectedCaseCount; }
    public String getReviewIdPrefix() { return reviewIdPrefix; }
    public String getHpoOntologyVersion() { return hpoOntologyVersion; }
    public String getPurpose() { return purpose; }
    public String getPriorTruthExposure() { return priorTruthExposure; }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
