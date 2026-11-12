package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Frozen reviewed nuclear-family facts; deliberately independent of the three-person TrioRole schema. */
public final class NuclearFamilyObservation {
    public static final String SCHEMA = "nuclear-family-observation-v1";
    private final ObjectNode facts;
    public NuclearFamilyObservation(ObjectNode facts) {
        this(facts, new FamilyDeNovoJson("PP1_"));
    }
    NuclearFamilyObservation(ObjectNode facts, FamilyDeNovoJson json) {
        new NuclearFamilyShape(json).factsShape(facts);
        this.facts = facts.deepCopy();
    }
    public ObjectNode snapshot() {
        return facts.deepCopy();
    }
}
