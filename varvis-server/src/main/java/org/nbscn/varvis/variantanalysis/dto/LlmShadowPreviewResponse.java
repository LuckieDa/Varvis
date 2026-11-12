package org.nbscn.varvis.variantanalysis.dto;

/**
 * Privacy-bounded, read-only shadow output. It deliberately contains no case identifiers.
 * The diagnosis flag means Varvis neither requests nor persists or consumes this preview as a
 * diagnosis; it is not a semantic classifier for arbitrary provider text.
 */
public final class LlmShadowPreviewResponse {

    public enum Status { DISABLED, PROVIDER_UNAVAILABLE, PREVIEW_READY }

    private final Status status;
    private final String previewText;
    private final Provenance provenance;

    public LlmShadowPreviewResponse(Status status, String previewText, Provenance provenance) {
        this.status = status;
        this.previewText = previewText;
        this.provenance = provenance;
    }

    public String getStatus() {
        return status.name();
    }

    public String getPreviewText() {
        return previewText;
    }

    public Provenance getProvenance() {
        return provenance;
    }

    public static final class Provenance {
        private final String mode;
        private final boolean externalTransmission;
        private final boolean rankingModified;
        private final boolean diagnosisGenerated;
        private final String provider;
        private final String schemaVersion;
        private final int candidateCount;
        private final String inputFingerprint;

        public Provenance(String provider, String schemaVersion, int candidateCount, String inputFingerprint) {
            this.mode = "SHADOW";
            this.externalTransmission = false;
            this.rankingModified = false;
            this.diagnosisGenerated = false;
            this.provider = provider;
            this.schemaVersion = schemaVersion;
            this.candidateCount = candidateCount;
            this.inputFingerprint = inputFingerprint;
        }

        public String getMode() { return mode; }
        public boolean isExternalTransmission() { return externalTransmission; }
        public boolean isRankingModified() { return rankingModified; }
        public boolean isDiagnosisGenerated() { return diagnosisGenerated; }
        public String getProvider() { return provider; }
        public String getSchemaVersion() { return schemaVersion; }
        public int getCandidateCount() { return candidateCount; }
        public String getInputFingerprint() { return inputFingerprint; }
    }
}
