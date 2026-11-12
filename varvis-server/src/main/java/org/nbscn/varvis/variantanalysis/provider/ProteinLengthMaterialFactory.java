package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.lang.Nullable;

/** Server-side, code-bound technical scope for PM4/BP3 material construction.
 * No request/context flag, scientific approval or evidence strength enables this scope.
 * Normal source/model/reference checks still run for every supplied candidate.
 */
public final class ProteinLengthMaterialFactory {
    public static final String VERSION = "1.0.0";
    public static final String CAPABILITY_ID = "A5_NATIVE_GRCH38_SMALL_ALLELE_SEQUENCE_FACTS";
    private ProteinLengthMaterialFactory() { }

    /** Keep the general/missense/PVS1 factory on its existing constructors. This named factory
     * opts only PM4/BP3 acquisition into the finite A5 sequence algorithms reviewed in code.
     * The caller owns this instance for a request; PM4.sharedFactSupply() can be reused by BP3.
     */
    public static CodingTranscriptMaterialSupply reviewedSmallAlleles(ObjectMapper mapper,
            @Nullable Path modelRoot, String manifestSha256, CodingTranscriptMaterialSupply.ReferenceReader reference,
            @Nullable GencodeTranscriptIndex gencode) throws IOException {
        return new CodingTranscriptMaterialSupply(mapper, modelRoot, manifestSha256, reference, gencode,
                true, Capability.INSTANCE);
    }

    // There is intentionally no public JSON deserializer, arbitrary scope/strength argument or
    // capability setter. Request data cannot construct or replace this immutable code-owned token.
    static final class Capability {
        private static final Capability INSTANCE = new Capability();
        private Capability() { }
        ObjectNode snapshot(ObjectMapper mapper) {
            var out = mapper.createObjectNode().put("schemaVersion", "a5-protein-length-sequence-capability-v1")
                    .put("capabilityId", CAPABILITY_ID).put("capabilityVersion", VERSION)
                    .put("assembly", "GRCh38").put("mappingStatus", "NATIVE").put("singlePhysicalAllele", true)
                    .put("maximumAlleleLengthIncludingAnchor", ProteinLengthChangeObservation.MAXIMUM_ALLELE_LENGTH)
                    .put("scopeBasis", "CODE_BOUND_TECHNICAL_SUPPORT_NOT_SCIENTIFIC_APPROVAL")
                    .put("qualificationGranted", false).put("scoringPermissionGranted", false)
                    .put("fullReferenceAndAnnotationVerificationRequired", true)
                    .put("codingEditMethod", CodingTranscriptEdits.VERSION);
            out.putArray("branches").add("VERIFIED_INFRAME_LENGTH_CHANGE").add("VERIFIED_STOP_LOSS_SMALL_ALLELE");
            out.putArray("unsupported").add("MULTIPLE_UNPHASED_ALLELES").add("INFERRED_SPLICE_RESULT")
                    .add("INSERTED_RNA_ALIGNMENT").add("PHYSICAL_EDIT_EXTENDING_CDS_INTO_UTR");
            out.put("canonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(out));
            return out;
        }
    }
}
