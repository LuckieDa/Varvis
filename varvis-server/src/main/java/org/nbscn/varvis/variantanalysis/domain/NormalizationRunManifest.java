package org.nbscn.varvis.variantanalysis.domain;

public class NormalizationRunManifest {

    private final String status;
    private final String assembly;
    private final String referenceProfileId;
    private final String configuredReferenceSha256;
    private final String toolName;
    private final String toolVersion;
    private final String commandDigest;
    private final boolean contigAliasApplied;
    private final Integer rawRecordCount;
    private final Integer normalizedAlleleCount;
    private final Integer splitRecordCount;
    private final Integer realignedRecordCount;
    private final Integer skippedRecordCount;
    private final long elapsedMillis;
    private final String startedAt;
    private final String completedAt;
    private final String failureCode;
    private final String failureReason;
    private final String hashStatus;

    public NormalizationRunManifest(String status,
                                    String assembly,
                                    String referenceProfileId,
                                    String configuredReferenceSha256,
                                    String toolName,
                                    String toolVersion,
                                    String commandDigest,
                                    boolean contigAliasApplied,
                                    Integer rawRecordCount,
                                    Integer normalizedAlleleCount,
                                    Integer splitRecordCount,
                                    Integer realignedRecordCount,
                                    Integer skippedRecordCount,
                                    long elapsedMillis,
                                    String startedAt,
                                    String completedAt,
                                    String failureCode,
                                    String failureReason,
                                    String hashStatus) {
        this.status = status;
        this.assembly = assembly;
        this.referenceProfileId = referenceProfileId;
        this.configuredReferenceSha256 = configuredReferenceSha256;
        this.toolName = toolName;
        this.toolVersion = toolVersion;
        this.commandDigest = commandDigest;
        this.contigAliasApplied = contigAliasApplied;
        this.rawRecordCount = rawRecordCount;
        this.normalizedAlleleCount = normalizedAlleleCount;
        this.splitRecordCount = splitRecordCount;
        this.realignedRecordCount = realignedRecordCount;
        this.skippedRecordCount = skippedRecordCount;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.hashStatus = hashStatus;
    }

    public NormalizationRunManifest withNormalizedAlleleCount(int count) {
        return new NormalizationRunManifest(
                status, assembly, referenceProfileId, configuredReferenceSha256,
                toolName, toolVersion, commandDigest, contigAliasApplied,
                rawRecordCount, Math.max(0, count), splitRecordCount,
                realignedRecordCount, skippedRecordCount, elapsedMillis,
                startedAt, completedAt, failureCode, failureReason, hashStatus
        );
    }

    public String eventMessage() {
        return "Reference normalization " + status
                + ": assembly=" + safe(assembly)
                + ", profile=" + safe(referenceProfileId)
                + ", toolVersion=" + safe(toolVersion)
                + ", rawRecords=" + safe(rawRecordCount)
                + ", normalizedAlleles=" + safe(normalizedAlleleCount)
                + ", splitRecords=" + safe(splitRecordCount)
                + ", realignedRecords=" + safe(realignedRecordCount)
                + ", skippedRecords=" + safe(skippedRecordCount)
                + ", elapsedMs=" + elapsedMillis
                + (failureCode == null ? "" : ", failureCode=" + failureCode);
    }

    private String safe(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty()
                ? "NOT_RECORDED" : String.valueOf(value);
    }

    public String getStatus() { return status; }
    public String getAssembly() { return assembly; }
    public String getReferenceProfileId() { return referenceProfileId; }
    public String getConfiguredReferenceSha256() { return configuredReferenceSha256; }
    public String getToolName() { return toolName; }
    public String getToolVersion() { return toolVersion; }
    public String getCommandDigest() { return commandDigest; }
    public boolean isContigAliasApplied() { return contigAliasApplied; }
    public Integer getRawRecordCount() { return rawRecordCount; }
    public Integer getNormalizedAlleleCount() { return normalizedAlleleCount; }
    public Integer getSplitRecordCount() { return splitRecordCount; }
    public Integer getRealignedRecordCount() { return realignedRecordCount; }
    public Integer getSkippedRecordCount() { return skippedRecordCount; }
    public long getElapsedMillis() { return elapsedMillis; }
    public String getStartedAt() { return startedAt; }
    public String getCompletedAt() { return completedAt; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public String getHashStatus() { return hashStatus; }
}
