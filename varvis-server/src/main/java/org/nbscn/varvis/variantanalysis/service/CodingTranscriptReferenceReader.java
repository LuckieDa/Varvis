package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.provider.CodingTranscriptMaterialSupply;
import org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator;

/** Reuses the existing receipt/FAI/alias/metadata checks; no new reference qualification algorithm. */
public final class CodingTranscriptReferenceReader implements CodingTranscriptMaterialSupply.ReferenceReader {
    private final NativeSnvReference reference;
    public CodingTranscriptReferenceReader(ObjectMapper mapper, VariantAnalysisProperties properties, Path resourceRoot,
            ExplicitContextSourceLedger ledger, String locator) {
        reference = new NativeSnvReference(mapper, properties, new NormalizationResourceValidator(properties), resourceRoot,
                List.of(ledger), ledger.reference(locator));
    }
    @Override public CodingTranscriptMaterialSupply.ReferenceSpan read(String contig, int start, int length) throws IOException {
        try {
            var span = reference.observeNormalizationSpan(contig, start, length);
            var identity = span.identity().deepCopy();
            identity.remove("checksumApplicabilityCheckedAt");
            identity.set("contigAssociation", span.observation().path("contigAssociation").deepCopy());
            identity.put("referenceContig", span.observation().path("referenceContig").asText());
            return new CodingTranscriptMaterialSupply.ReferenceSpan(span.observation().path("observedReference").asText(), identity);
        } catch (NativeSnvReference.Blocked blocked) { throw new IllegalArgumentException(blocked.reason, blocked); }
    }
}
