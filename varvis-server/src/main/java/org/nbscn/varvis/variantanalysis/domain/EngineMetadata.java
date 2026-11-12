package org.nbscn.varvis.variantanalysis.domain;

/**
 * Application-level engine provenance. It deliberately contains no truth or
 * result-derived fields and does not require a database schema change.
 */
public final class EngineMetadata {

    private final AnalysisEngine engine;
    private final String engineVersion;
    private final String rankingMode;
    private final EngineExecutionStatus status;
    private final String unavailableReason;
    private final String resourceFingerprint;
    private final String outputSha256;
    private final boolean fallbackUsed;

    public EngineMetadata(AnalysisEngine engine,
                          String engineVersion,
                          String rankingMode,
                          EngineExecutionStatus status,
                          String unavailableReason,
                          String resourceFingerprint,
                          String outputSha256,
                          boolean fallbackUsed) {
        this.engine = engine == null ? AnalysisEngine.VARVIS : engine;
        this.engineVersion = emptyIfNull(engineVersion);
        this.rankingMode = emptyIfNull(rankingMode);
        this.status = status == null ? EngineExecutionStatus.NOT_REQUESTED : status;
        this.unavailableReason = emptyIfNull(unavailableReason);
        this.resourceFingerprint = emptyIfNull(resourceFingerprint);
        this.outputSha256 = emptyIfNull(outputSha256);
        this.fallbackUsed = fallbackUsed;
    }

    public static EngineMetadata varvisRunning() {
        return varvis(EngineExecutionStatus.RUNNING);
    }

    public static EngineMetadata varvisRanked() {
        return varvis(EngineExecutionStatus.RANKED);
    }

    public static EngineMetadata varvis(EngineExecutionStatus status) {
        return new EngineMetadata(
                AnalysisEngine.VARVIS,
                "product",
                "VARVIS_DEFAULT",
                status,
                "",
                "",
                "",
                false
        );
    }

    public static EngineMetadata exomiserUnavailable(String reason,
                                                      String version,
                                                      String resourceFingerprint) {
        return exomiserUnavailable(reason, version, resourceFingerprint, "EXOMISER_G1");
    }

    public static EngineMetadata exomiserRunning(String version,
                                                 String resourceFingerprint,
                                                 String rankingMode) {
        return new EngineMetadata(
                AnalysisEngine.EXOMISER,
                version,
                rankingMode,
                EngineExecutionStatus.RUNNING,
                "",
                resourceFingerprint,
                "",
                false
        );
    }

    public static EngineMetadata exomiserUnavailable(String reason,
                                                      String version,
                                                      String resourceFingerprint,
                                                      String rankingMode) {
        return new EngineMetadata(
                AnalysisEngine.EXOMISER,
                version,
                rankingMode,
                EngineExecutionStatus.UNAVAILABLE,
                reason,
                resourceFingerprint,
                "",
                false
        );
    }

    public static EngineMetadata exomiserRanked(String version,
                                                String resourceFingerprint,
                                                String outputSha256) {
        return exomiserRanked(version, resourceFingerprint, outputSha256, "EXOMISER_G1");
    }

    public static EngineMetadata exomiserRanked(String version,
                                                String resourceFingerprint,
                                                String outputSha256,
                                                String rankingMode) {
        return new EngineMetadata(
                AnalysisEngine.EXOMISER,
                version,
                rankingMode,
                EngineExecutionStatus.RANKED,
                "",
                resourceFingerprint,
                outputSha256,
                false
        );
    }

    public static EngineMetadata exomiserFailed(String reason,
                                                 String version,
                                                 String resourceFingerprint) {
        return exomiserFailed(reason, version, resourceFingerprint, "EXOMISER_G1");
    }

    public static EngineMetadata exomiserFailed(String reason,
                                                 String version,
                                                 String resourceFingerprint,
                                                 String rankingMode) {
        return new EngineMetadata(
                AnalysisEngine.EXOMISER,
                version,
                rankingMode,
                EngineExecutionStatus.FAILED,
                reason,
                resourceFingerprint,
                "",
                false
        );
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    public AnalysisEngine getEngine() {
        return engine;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public String getRankingMode() {
        return rankingMode;
    }

    public EngineExecutionStatus getStatus() {
        return status;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public String getResourceFingerprint() {
        return resourceFingerprint;
    }

    public String getOutputSha256() {
        return outputSha256;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }
}
