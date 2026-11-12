package org.nbscn.varvis.variantanalysis.domain;

/** Outcome of resolving a syntactically valid HPO identifier against the configured ontology. */
public enum HpoOntologyTermStatus {
    CURRENT,
    CURRENT_ALIAS,
    UNKNOWN,
    OBSOLETE_WITH_REPLACEMENT,
    OBSOLETE_UNRESOLVED,
    OBSOLETE_AMBIGUOUS,
    UNAVAILABLE
}
