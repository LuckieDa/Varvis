package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"stage", "presence", "observedCount", "note"})
public class BenchmarkStageRetention {

    private String stage;
    private String presence;
    private Integer observedCount;
    private String note;

    public BenchmarkStageRetention() {
    }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getPresence() { return presence; }
    public void setPresence(String presence) { this.presence = presence; }
    public Integer getObservedCount() { return observedCount; }
    public void setObservedCount(Integer observedCount) { this.observedCount = observedCount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
