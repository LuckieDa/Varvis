package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScoreBreakdownComponent {

    private final double score;
    private final List<String> reasons;

    @JsonCreator
    public ScoreBreakdownComponent(@JsonProperty("score") double score,
                                   @JsonProperty("reasons") List<String> reasons) {
        this.score = score;
        this.reasons = reasons == null || reasons.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(reasons));
    }

    public double getScore() {
        return score;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
