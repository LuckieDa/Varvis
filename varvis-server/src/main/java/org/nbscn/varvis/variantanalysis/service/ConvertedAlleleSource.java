package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Allele;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.nbscn.varvis.variantanalysis.service.NativeSnvReference.require;

/** Rechecks both references and the allele relation before a converted candidate can supply BA1. */
final class ConvertedAlleleSource {
    static final String METHOD = "VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT";
    static final String VERSION = "1.0.0";
    private ConvertedAlleleSource() { }

    static Verified verify(ObjectMapper mapper, BuildConversionResult conversion, VariantInterpretation candidate,
                           EngineExecutionResult.MatchedRecord matched, NativeSnvReference targetReference,
                           NativeSnvReference originalReference, ObjectNode publicEvidence) throws IOException {
        var proof = conversion.getSourceProof();
        if (proof == null) throw new NativeSnvReference.Blocked("CONVERSION_SOURCE_PROOF_MISSING");
        require(proof != null && ConversionSourceProof.VERSION.equals(proof.path("version").asText()), "CONVERSION_SOURCE_PROOF_MISSING");
        JsonNode row = proof.path("candidates").path(candidate.getCandidateId());
        for (String field : List.of("originalAllele", "analysisAllele", "originalRecordOrdinal", "analysisRecordOrdinal", "originalAltIndex", "analysisAltIndex", "strand"))
            if (row.has(field)) publicEvidence.set(field, row.path(field).deepCopy());
        publicEvidence.put("method", proof.path("method").asText()).put("methodVersion", VERSION)
                .put("implementationVersion", proof.path("implementationVersion").asText())
                .put("implementationSha256", proof.at("/implementation/sha256").asText())
                .put("chainSha256", proof.at("/chain/sha256").asText())
                .put("originalUploadSha256", proof.at("/originalArtifact/sha256").asText())
                .put("analysisVcfSha256", proof.at("/analysisArtifact/sha256").asText())
                .put("originalAssembly", "GRCh37").put("analysisAssembly", "GRCh38").put("status", "NOT_VERIFIED");
        require(!"BLOCKED".equals(row.path("status").asText()), row.path("reasonCode").asText("CONVERSION_RECORD_ASSOCIATION_UNAVAILABLE"));
        require("ASSOCIATED_PENDING_REFERENCE_VALIDATION".equals(row.path("status").asText()), "CONVERSION_RECORD_ASSOCIATION_UNAVAILABLE");
        if (originalReference == null) throw new NativeSnvReference.Blocked("CONVERSION_ORIGINAL_REFERENCE_UNAVAILABLE");
        int originalOrdinal = positive(row, "originalRecordOrdinal"), originalAlt = positive(row, "originalAltIndex");
        require(positive(row, "analysisRecordOrdinal") == matched.recordOrdinal()
                && positive(row, "analysisAltIndex") == matched.selectedAltIndex() && originalAlt == matched.selectedAltIndex(), "CONVERSION_ALT_ASSOCIATION_INVALID");
        var key = candidate.getObservation().getKey();
        Allele original = allele(row.path("originalAllele")), mapped = allele(row.path("mappedAllele")), target = allele(row.path("analysisAllele"));
        require("GRCh37".equals(original.assembly()) && "GRCh38".equals(mapped.assembly()) && "GRCh38".equals(target.assembly()), "CONVERSION_ASSEMBLY_CONFLICT");
        require(sameContig(target.contig(), key.getChromosome()) && target.position() == key.getPosition()
                && target.reference().equals(key.getReference()) && target.alternate().equals(key.getAlternate()), "CONVERSION_ANALYSIS_ALLELE_CONFLICT");
        var mapping = NormalizationAlleleMapping.from(row.path("originalRecord").asText(), key);
        require(mapping.isResolved() && Objects.equals(mapping.getRawAlternateIndex(), originalAlt)
                && Objects.equals(mapping.getRawPosition(), original.position()) && sameContig(mapping.getRawChromosome(), original.contig())
                && Objects.equals(mapping.getRawReference(), original.reference())
                && mapping.getRawAlternates().get(originalAlt - 1).equals(original.alternate()), "CONVERSION_ORIGINAL_RECORD_CONFLICT");
        require("EXACT_ALLELE_INDEX_AND_PHASE_MATCH".equals(row.path("genotypeAssociation").asText())
                && row.path("genotype").path("alleleIndexes").isArray() && !row.path("genotype").path("alleleIndexes").isEmpty(), "CONVERSION_GENOTYPE_ASSOCIATION_MISSING");
        publicEvidence.put("referenceQueryAttempts", 1);
        var sourceObserved = originalReference.observe(original.contig(), original.position(), original.reference());
        publicEvidence.set("originalReferenceObservation", sourceObserved.observation());
        require("MATCH".equals(sourceObserved.observation().path("status").asText()), "CONVERSION_ORIGINAL_REF_MISMATCH");
        publicEvidence.put("referenceQueryAttempts", 2);
        var targetObserved = targetReference.observe(target.contig(), target.position(), target.reference());
        publicEvidence.set("targetReferenceObservation", targetObserved.observation());
        require("MATCH".equals(targetObserved.observation().path("status").asText()), "CONVERSION_TARGET_REF_MISMATCH");
        require(proof.path("sourceReferenceSha256").asText().equals(sourceObserved.identity().path("observedSha256").asText())
                && proof.path("targetReferenceSha256").asText().equals(targetObserved.identity().path("observedSha256").asText()), "CONVERSION_REFERENCE_IDENTITY_CONFLICT");
        require(sameContig(mapped.contig(), target.contig()) && Math.abs((long) mapped.position() - target.position()) <= 50, "CONVERSION_NORMALIZATION_SPAN_UNSUPPORTED");
        int start = Math.min(mapped.position(), target.position());
        int end = Math.max(mapped.position() + mapped.reference().length(), target.position() + target.reference().length());
        publicEvidence.put("referenceQueryAttempts", 3);
        var span = targetReference.observeSpan(target.contig(), start, end - start);
        String reference = span.observation().path("observedReference").asText();
        require(apply(reference, start, mapped).equals(apply(reference, start, target)), "CONVERSION_ALLELE_RELATION_UNRESOLVED");
        publicEvidence.put("status", "VERIFIED").put("normalization", "BOUNDED_REFERENCE_HAPLOTYPE_EQUIVALENCE")
                .put("originalReferenceSha256", sourceObserved.identity().path("observedSha256").asText())
                .put("targetReferenceSha256", targetObserved.identity().path("observedSha256").asText());
        publicEvidence.set("normalizationObservation", span.observation());
        require(originalOrdinal > 0, "CONVERSION_ORIGINAL_RECORD_MISSING");
        // Keep the raw contig in the witnessed VCF record; frozen contexts use canonical contig labels.
        Allele canonicalOriginal = new Allele(original.assembly(), org.nbscn.varvis.utils.CoordinateUtils.normalizeChromosome(original.contig()),
                original.position(), original.reference(), original.alternate());
        return new Verified(canonicalOriginal, new Allele(key.getGenomeAssembly(), key.getChromosome(), key.getPosition(), key.getReference(), key.getAlternate()),
                row.path("originalRecord").asText(), originalAlt, targetObserved.identity(), sourceObserved.identity(), publicEvidence.deepCopy());
    }

    private static String apply(String reference, int start, Allele allele) {
        int offset = allele.position() - start;
        require(offset >= 0 && offset + allele.reference().length() <= reference.length()
                && reference.substring(offset, offset + allele.reference().length()).equals(allele.reference()), "CONVERSION_MAPPED_REF_MISMATCH");
        return reference.substring(0, offset) + allele.alternate() + reference.substring(offset + allele.reference().length());
    }
    private static int positive(JsonNode row, String field) {
        JsonNode value = row.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0, "CONVERSION_RECORD_ASSOCIATION_INVALID");
        return value.intValue();
    }
    private static Allele allele(JsonNode value) {
        require(value.path("reference").asText().matches("[ACGT]{1,50}") && value.path("alternate").asText().matches("[ACGT]{1,50}"), "CONVERSION_VARIANT_TYPE_UNSUPPORTED");
        return new Allele(value.path("assembly").asText(), value.path("contig").asText(), positive(value, "position"), value.path("reference").asText(), value.path("alternate").asText());
    }
    private static boolean sameContig(String left, String right) {
        return Objects.equals(org.nbscn.varvis.utils.CoordinateUtils.normalizeChromosome(left),
                org.nbscn.varvis.utils.CoordinateUtils.normalizeChromosome(right));
    }
    record Verified(Allele original, Allele target, String originalRecord, int originalAlt,
                    ObjectNode targetReference, ObjectNode originalReference, ObjectNode evidence) { }
}
