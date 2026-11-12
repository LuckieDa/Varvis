package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"schemaVersion", "reviewProtocolVersion", "reviewId", "packetFingerprint", "packetContentChecksum", "reviewMethod", "reviewerRole", "priorTruthExposure", "activeTruthReviewReported", "eligibleForStrictBlindedEvaluation", "sourceContext", "aiAssistance", "semanticAuditNotes", "reviewCompletionStatus", "reviewDate", "termDecisions", "additions", "decisionCounts"})
public class HpoReviewDecisionDocument {
    private String schemaVersion;
    private String reviewProtocolVersion;
    private String reviewId;
    private String packetFingerprint;
    private String packetContentChecksum;
    private String reviewMethod;
    private String reviewerRole;
    private String priorTruthExposure;
    private Boolean activeTruthReviewReported;
    private Boolean eligibleForStrictBlindedEvaluation;
    private String sourceContext;
    private HpoReviewAiAssistanceProvenance aiAssistance;
    private List<HpoSemanticAuditNote> semanticAuditNotes = new ArrayList<HpoSemanticAuditNote>();
    private HpoReviewReadinessStatus reviewCompletionStatus;
    private String reviewDate;
    private List<HpoReviewTermDecision> termDecisions = new ArrayList<HpoReviewTermDecision>();
    private List<HpoReviewAddition> additions = new ArrayList<HpoReviewAddition>();
    private java.util.Map<String, Integer> decisionCounts = new java.util.LinkedHashMap<String, Integer>();

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getReviewProtocolVersion() { return reviewProtocolVersion; }
    public void setReviewProtocolVersion(String value) { reviewProtocolVersion = value; }
    public String getReviewId() { return reviewId; }
    public void setReviewId(String value) { reviewId = value; }
    public String getPacketFingerprint() { return packetFingerprint; }
    public void setPacketFingerprint(String value) { packetFingerprint = value; }
    public String getPacketContentChecksum() { return packetContentChecksum; }
    public void setPacketContentChecksum(String value) { packetContentChecksum = value; }
    public String getReviewMethod() { return reviewMethod; }
    public void setReviewMethod(String value) { reviewMethod = value; }
    public String getReviewerRole() { return reviewerRole; }
    public void setReviewerRole(String value) { reviewerRole = value; }
    public String getPriorTruthExposure() { return priorTruthExposure; }
    public void setPriorTruthExposure(String value) { priorTruthExposure = value; }
    public Boolean getActiveTruthReviewReported() { return activeTruthReviewReported; }
    public void setActiveTruthReviewReported(Boolean value) { activeTruthReviewReported = value; }
    public Boolean getEligibleForStrictBlindedEvaluation() { return eligibleForStrictBlindedEvaluation; }
    public void setEligibleForStrictBlindedEvaluation(Boolean value) { eligibleForStrictBlindedEvaluation = value; }
    public String getSourceContext() { return sourceContext; }
    public void setSourceContext(String value) { sourceContext = value; }
    public HpoReviewAiAssistanceProvenance getAiAssistance() { return aiAssistance; }
    public void setAiAssistance(HpoReviewAiAssistanceProvenance value) { aiAssistance = value; }
    public List<HpoSemanticAuditNote> getSemanticAuditNotes() { return semanticAuditNotes; }
    public void setSemanticAuditNotes(List<HpoSemanticAuditNote> value) { semanticAuditNotes = value == null ? new ArrayList<HpoSemanticAuditNote>() : value; }
    public HpoReviewReadinessStatus getReviewCompletionStatus() { return reviewCompletionStatus; }
    public void setReviewCompletionStatus(HpoReviewReadinessStatus value) { reviewCompletionStatus = value; }
    public String getReviewDate() { return reviewDate; }
    public void setReviewDate(String value) { reviewDate = value; }
    public List<HpoReviewTermDecision> getTermDecisions() { return termDecisions; }
    public void setTermDecisions(List<HpoReviewTermDecision> value) { termDecisions = value == null ? new ArrayList<HpoReviewTermDecision>() : value; }
    public List<HpoReviewAddition> getAdditions() { return additions; }
    public void setAdditions(List<HpoReviewAddition> value) { additions = value == null ? new ArrayList<HpoReviewAddition>() : value; }
    public java.util.Map<String, Integer> getDecisionCounts() { return decisionCounts; }
    public void setDecisionCounts(java.util.Map<String, Integer> value) { decisionCounts = value == null ? new java.util.LinkedHashMap<String, Integer>() : value; }
}
