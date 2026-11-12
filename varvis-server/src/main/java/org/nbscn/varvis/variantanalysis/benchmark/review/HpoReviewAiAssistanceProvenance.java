package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Optional provenance for terminology and consistency assistance, never diagnosis. */
@JsonPropertyOrder({"aiAssistanceUsed", "assistanceScope", "humanFinalConfirmationRequired", "humanFinalConfirmation", "provider", "modelIdentifier", "sourceHpoOntologyVersion", "semanticReferenceHpoVersion", "semanticReferenceSource"})
public class HpoReviewAiAssistanceProvenance {
    private Boolean aiAssistanceUsed;
    private String assistanceScope;
    private Boolean humanFinalConfirmationRequired;
    private Boolean humanFinalConfirmation;
    private String provider;
    private String modelIdentifier;
    private String sourceHpoOntologyVersion;
    private String semanticReferenceHpoVersion;
    private String semanticReferenceSource;

    public Boolean getAiAssistanceUsed() { return aiAssistanceUsed; }
    public void setAiAssistanceUsed(Boolean value) { aiAssistanceUsed = value; }
    public String getAssistanceScope() { return assistanceScope; }
    public void setAssistanceScope(String value) { assistanceScope = value; }
    public Boolean getHumanFinalConfirmationRequired() { return humanFinalConfirmationRequired; }
    public void setHumanFinalConfirmationRequired(Boolean value) { humanFinalConfirmationRequired = value; }
    public Boolean getHumanFinalConfirmation() { return humanFinalConfirmation; }
    public void setHumanFinalConfirmation(Boolean value) { humanFinalConfirmation = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getModelIdentifier() { return modelIdentifier; }
    public void setModelIdentifier(String value) { modelIdentifier = value; }
    public String getSourceHpoOntologyVersion() { return sourceHpoOntologyVersion; }
    public void setSourceHpoOntologyVersion(String value) { sourceHpoOntologyVersion = value; }
    public String getSemanticReferenceHpoVersion() { return semanticReferenceHpoVersion; }
    public void setSemanticReferenceHpoVersion(String value) { semanticReferenceHpoVersion = value; }
    public String getSemanticReferenceSource() { return semanticReferenceSource; }
    public void setSemanticReferenceSource(String value) { semanticReferenceSource = value; }
}
