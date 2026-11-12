package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({
        "caseId", "truthSetFingerprint", "semantics", "sourceRelationship",
        "relationshipProvenance", "eligibilityStatus", "exclusionReasons"
})
public class BenchmarkTruthPolicyAssessment {

    private String caseId;
    private String truthSetFingerprint;
    private BenchmarkTruthSetSemantics semantics;
    private String sourceRelationship;
    private String relationshipProvenance;
    private BenchmarkPilotEligibilityStatus eligibilityStatus;
    private List<BenchmarkPilotExclusionReason> exclusionReasons =
            new ArrayList<BenchmarkPilotExclusionReason>();

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getTruthSetFingerprint() { return truthSetFingerprint; }
    public void setTruthSetFingerprint(String truthSetFingerprint) { this.truthSetFingerprint = truthSetFingerprint; }
    public BenchmarkTruthSetSemantics getSemantics() { return semantics; }
    public void setSemantics(BenchmarkTruthSetSemantics semantics) { this.semantics = semantics; }
    public String getSourceRelationship() { return sourceRelationship; }
    public void setSourceRelationship(String sourceRelationship) { this.sourceRelationship = sourceRelationship; }
    public String getRelationshipProvenance() { return relationshipProvenance; }
    public void setRelationshipProvenance(String relationshipProvenance) { this.relationshipProvenance = relationshipProvenance; }
    public BenchmarkPilotEligibilityStatus getEligibilityStatus() { return eligibilityStatus; }
    public void setEligibilityStatus(BenchmarkPilotEligibilityStatus eligibilityStatus) { this.eligibilityStatus = eligibilityStatus; }
    public List<BenchmarkPilotExclusionReason> getExclusionReasons() { return exclusionReasons; }
    public void setExclusionReasons(List<BenchmarkPilotExclusionReason> exclusionReasons) { this.exclusionReasons = exclusionReasons; }
}
