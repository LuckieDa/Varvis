package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Historical HNF1A facade. Its fixed scientific/engineering policy and output identities are preserved. */
final class Hnf1aFrameshiftResolver {
    private final TruncatingFrameshiftResolver delegate;
    private final int strand;

    Hnf1aFrameshiftResolver(JsonNode model, JsonNode policy) {
        strand = model.path("strand").asInt();
        delegate = new TruncatingFrameshiftResolver(model, policy, Hnf1aPvs1Runtime.TRANSCRIPT,
                "GN017_DISEASE_TRANSCRIPT_EXONS_1_TO_9", "HNF1A_MONOGENIC_DIABETES_LOSS_OF_FUNCTION");
    }

    String resolve(JsonNode context, TranscriptConsequence tx, ObjectNode input) {
        if (strand != 1) return "PVS1_TRANSCRIPT_NOT_SUPPORTED";
        return delegate.resolve(context, tx, input);
    }

    static String localPeptide(String codons) {
        return TruncatingFrameshiftResolver.localPeptide(codons);
    }

    static String localAminoAcids(String referenceCodons, String alternateCodons) {
        return TruncatingFrameshiftResolver.localAminoAcids(referenceCodons, alternateCodons);
    }
}
