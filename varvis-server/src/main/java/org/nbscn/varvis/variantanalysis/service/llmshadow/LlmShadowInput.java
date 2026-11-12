package org.nbscn.varvis.variantanalysis.service.llmshadow;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The complete provider boundary. No clinical or variant identifiers are representable here. */
@JsonPropertyOrder({"schemaVersion", "candidates"})
public final class LlmShadowInput {

    private final String schemaVersion;
    private final List<Candidate> candidates;

    public LlmShadowInput(String schemaVersion, List<Candidate> candidates) {
        this.schemaVersion = schemaVersion;
        this.candidates = Collections.unmodifiableList(new ArrayList<Candidate>(candidates));
    }

    public String getSchemaVersion() { return schemaVersion; }
    public List<Candidate> getCandidates() { return candidates; }

    @JsonPropertyOrder({"candidateOrdinal", "completeness", "availabilityMeaning", "components"})
    public static final class Candidate {
        private final int candidateOrdinal;
        private final String completeness;
        private final String availabilityMeaning;
        private final List<Component> components;

        public Candidate(int candidateOrdinal, String completeness, String availabilityMeaning,
                         List<Component> components) {
            this.candidateOrdinal = candidateOrdinal;
            this.completeness = completeness;
            this.availabilityMeaning = availabilityMeaning;
            this.components = Collections.unmodifiableList(new ArrayList<Component>(components));
        }

        public int getCandidateOrdinal() { return candidateOrdinal; }
        public String getCompleteness() { return completeness; }
        public String getAvailabilityMeaning() { return availabilityMeaning; }
        public List<Component> getComponents() { return components; }
    }

    @JsonPropertyOrder({"key", "score", "direction"})
    public static final class Component {
        private final String key;
        private final double score;
        private final String direction;

        public Component(String key, double score, String direction) {
            this.key = key;
            this.score = score;
            this.direction = direction;
        }

        public String getKey() { return key; }
        public double getScore() { return score; }
        public String getDirection() { return direction; }
    }
}
