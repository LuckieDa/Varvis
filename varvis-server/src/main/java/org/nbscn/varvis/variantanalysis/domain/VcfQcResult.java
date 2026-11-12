package org.nbscn.varvis.variantanalysis.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VcfQcResult {

    private final String selectedSampleId;
    private final VariantQcSummary summary;
    private final List<VariantQcObservation> observations;
    private final List<String> warnings;

    public VcfQcResult(String selectedSampleId,
                       VariantQcSummary summary,
                       List<VariantQcObservation> observations) {
        this(selectedSampleId, summary, observations, Collections.emptyList());
    }

    public VcfQcResult(String selectedSampleId,
                       VariantQcSummary summary,
                       List<VariantQcObservation> observations,
                       List<String> warnings) {
        this.selectedSampleId = selectedSampleId;
        this.summary = summary;
        this.observations = observations == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<VariantQcObservation>(observations));
        this.warnings = warnings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public String getSelectedSampleId() {
        return selectedSampleId;
    }

    public VariantQcSummary getSummary() {
        return summary;
    }

    public List<VariantQcObservation> getObservations() {
        return observations;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
