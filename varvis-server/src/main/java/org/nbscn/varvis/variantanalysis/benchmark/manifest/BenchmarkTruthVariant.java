package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

@JsonPropertyOrder({"canonicalIdentity", "truthProvenance", "confidence", "relationId"})
public class BenchmarkTruthVariant {

    private NormalizedVariantKey canonicalIdentity;
    private String truthProvenance;
    private String confidence;
    private String relationId;

    public BenchmarkTruthVariant() {
    }

    public NormalizedVariantKey getCanonicalIdentity() { return canonicalIdentity; }
    public void setCanonicalIdentity(NormalizedVariantKey canonicalIdentity) { this.canonicalIdentity = canonicalIdentity; }
    public String getTruthProvenance() { return truthProvenance; }
    public void setTruthProvenance(String truthProvenance) { this.truthProvenance = truthProvenance; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public String getRelationId() { return relationId; }
    public void setRelationId(String relationId) { this.relationId = relationId; }
}
