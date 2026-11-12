package org.nbscn.varvis.variantanalysis.persistence;

/** Raised when a legacy variant identifier maps to more than one candidate row. */
public final class AmbiguousVariantIdException extends RuntimeException {

    public AmbiguousVariantIdException(String variantId) {
        super("AMBIGUOUS_VARIANT_ID:" + variantId);
    }
}
