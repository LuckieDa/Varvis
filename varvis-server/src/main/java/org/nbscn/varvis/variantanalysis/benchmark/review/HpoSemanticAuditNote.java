package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"hpoId", "sourceLabel", "canonicalLabelAtSemanticReference", "relationship", "decisionImpact"})
public class HpoSemanticAuditNote {
    private String hpoId;
    private String sourceLabel;
    private String canonicalLabelAtSemanticReference;
    private String relationship;
    private String decisionImpact;

    public String getHpoId() { return hpoId; }
    public void setHpoId(String value) { hpoId = value; }
    public String getSourceLabel() { return sourceLabel; }
    public void setSourceLabel(String value) { sourceLabel = value; }
    public String getCanonicalLabelAtSemanticReference() { return canonicalLabelAtSemanticReference; }
    public void setCanonicalLabelAtSemanticReference(String value) { canonicalLabelAtSemanticReference = value; }
    public String getRelationship() { return relationship; }
    public void setRelationship(String value) { relationship = value; }
    public String getDecisionImpact() { return decisionImpact; }
    public void setDecisionImpact(String value) { decisionImpact = value; }
}
