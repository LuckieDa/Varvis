package org.nbscn.varvis.variantanalysis.trio;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Immutable snapshot of caller facts, not a de novo verdict or a persisted patient record. */
public final class CaseFamilyObservation {
    public static final String SCHEMA = "case-family-observation-v1";
    public static final String TWO_ALLELE_SCHEMA = "case-family-observation-v2";
    private final ObjectNode snapshot;

    public CaseFamilyObservation(ObjectNode snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot).deepCopy();
        if (!SCHEMA.equals(snapshot.path("schemaVersion").asText())
                && !TWO_ALLELE_SCHEMA.equals(snapshot.path("schemaVersion").asText()))
            throw new IllegalArgumentException("CASE_FAMILY_SCHEMA_INVALID");
    }

    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    public String observationId() { return snapshot.path("observationId").asText(); }
    public String occurrenceId() { return snapshot.path("occurrenceId").asText(); }
}
