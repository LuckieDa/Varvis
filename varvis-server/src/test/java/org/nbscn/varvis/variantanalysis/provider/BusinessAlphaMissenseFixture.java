package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.util.*;

/** Synthetic transport only, using the real exact-record parser and evaluator downstream. */
public final class BusinessAlphaMissenseFixture extends AlphaMissenseProvider {
    public static IdentityLookup identityFixture(NormalizedVariantKey key, List<String> rows) {
        var identities = rows.stream().map(row -> identityProjection(row, key)).filter(Objects::nonNull).toList();
        return new IdentityLookup("AVAILABLE", identities.isEmpty() ? "NO_RECORD" : "IDENTITY_RECORDS_AVAILABLE",
                "2023-08-03", "SYNTHETIC_IDENTITY_FIXTURE", sortedIdentities(identities));
    }
    public int calls;
    public BusinessAlphaMissenseFixture() { super(settings()); }
    private static VariantAnalysisProperties settings() {
        var p = new VariantAnalysisProperties(); p.getAlphaMissense().setVersion("2023-08-03"); return p;
    }
    @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotateSelectedTranscripts(
            List<VariantInterpretation> candidates, AnalysisContext context, Map<NormalizedVariantKey, TranscriptConsequence> selected) {
        calls++; var key = candidates.get(0).getObservation().getKey(); var tx = selected.get(key);
        String row = key.getChromosome() + "\t" + key.getPosition() + "\t" + key.getReference() + "\t" + key.getAlternate()
                + "\thg38\tSYNTHETIC_PROTEIN\t" + tx.getVersionedTranscriptId() + "\t" + tx.getAminoAcidChange() + "\t0.9\tambiguous";
        return Map.of(key, List.of(resolveExactRecords(List.of(row), key, tx)));
    }
}
