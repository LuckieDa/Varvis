package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"id", "label", "excluded", "onset"})
public class PhenopacketHpoTerm {

    private String id;
    private String label;
    private boolean excluded;
    private String onset;

    public PhenopacketHpoTerm() {
    }

    public PhenopacketHpoTerm(String id, String label, boolean excluded, String onset) {
        this.id = id;
        this.label = label;
        this.excluded = excluded;
        this.onset = onset;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public boolean isExcluded() { return excluded; }
    public String getOnset() { return onset; }
    public void setId(String value) { id = value; }
    public void setLabel(String value) { label = value; }
    public void setExcluded(boolean value) { excluded = value; }
    public void setOnset(String value) { onset = value; }
}
