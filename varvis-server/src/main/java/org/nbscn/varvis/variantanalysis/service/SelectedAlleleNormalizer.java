package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.variant.vcf.VCFFileReader;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Allele;
import java.io.IOException;
import java.nio.file.*;
import static org.nbscn.varvis.variantanalysis.service.NativeSnvReference.require;

/** Selected-allele adapter: one witnessed ALT, existing BCFtools algorithm, independent reference equivalence. */
final class SelectedAlleleNormalizer {
    static final String METHOD = "BCFTOOLS_SELECTED_ALLELE_REFERENCE_NORMALIZATION";
    static final String VERSION = "1.0.1";
    private final ObjectMapper mapper;
    private final VcfNormalizer tool;
    SelectedAlleleNormalizer(ObjectMapper mapper, VcfNormalizer tool) { this.mapper = mapper; this.tool = tool; }

    Verified normalize(Path workspace, Allele original, EngineExecutionResult.MatchedRecord record,
                       NativeSnvReference reference, ObjectNode diagnostics, boolean allowExtendedDeletion) throws IOException {
        diagnostics.put("referenceQueryAttempts", 1).put("toolInvocations", 0);
        var before = reference.observeNormalizationInput(record.contig(), original.position(), original.reference());
        require("MATCH".equals(before.observation().path("status").asText()), "REF_VALIDATION_NOT_MATCH");
        Path directory = Files.createTempDirectory(Files.createDirectories(workspace.resolve("acmg-normalization")), "selected-");
        // Samples are deliberately not projected into a biallelic genotype. The original GT/AD/phase
        // remain in the associated source receipt and are never inferred from tool output.
        // annotate --rename-chrs needs a declared contig even for this single-record VCF.
        // Preserve the witnessed input name; the existing alias/reference checks still own conversion.
        String contigHeader = new htsjdk.variant.vcf.VCFContigHeaderLine(java.util.Map.of("ID", record.contig()), 0).toString();
        String text = "##fileformat=VCFv4.2\n##" + contigHeader + "\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                + record.contig() + "\t" + original.position() + "\tVARVIS_SELECTED_ALT\t" + original.reference()
                + "\t" + original.alternate() + "\t.\tPASS\t.\n";
        Path input = Files.writeString(directory.resolve("selected.vcf"), text);
        NormalizationResult result;
        try { diagnostics.put("toolInvocations", 1); result = tool.normalize(input, directory, GenomeAssembly.valueOf(original.assembly())); }
        catch (RuntimeException | IOException error) {
            String message = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
            throw new NativeSnvReference.Blocked(message.contains("timed out") ? "NORMALIZATION_TOOL_TIMEOUT"
                    : message.contains("could not start") ? "NORMALIZATION_TOOL_UNAVAILABLE" : "NORMALIZATION_TOOL_FAILED");
        }
        var manifest = result.getManifest();
        if (!(result.isNormalizationApplied() && manifest != null && "PASSED".equals(manifest.getStatus())
                && "bcftools".equals(manifest.getToolName()) && manifest.getToolVersion() != null
                && !manifest.getToolVersion().isBlank() && !"UNKNOWN".equals(manifest.getToolVersion()))) {
            throw new NativeSnvReference.Blocked("NORMALIZATION_NOT_REFERENCE_APPLIED");
        }
        require(original.assembly().equals(manifest.getAssembly())
                && before.identity().path("observedSha256").asText().equals(manifest.getConfiguredReferenceSha256()),
                "NORMALIZATION_REFERENCE_IDENTITY_CONFLICT");
        Path output = result.getOutputFile();
        if (!(output != null && output.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())
                && Files.isRegularFile(output) && Files.size(output) > 0 && Files.size(output) <= 1_048_576)) {
            throw new NativeSnvReference.Blocked("NORMALIZATION_OUTPUT_MISSING_OR_UNBOUNDED");
        }
        // Bound decoded bytes too; a compressed-size limit alone does not bound parser memory.
        try (var raw = Files.newInputStream(output);
             var decoded = output.toString().endsWith(".gz") ? new java.util.zip.GZIPInputStream(raw) : raw) {
            require(decoded.readNBytes(1_048_577).length <= 1_048_576, "NORMALIZATION_OUTPUT_MISSING_OR_UNBOUNDED");
        }
        Allele normalized;
        try (var reader = new VCFFileReader(output, false); var iterator = reader.iterator()) {
            require(iterator.hasNext(), "NORMALIZATION_OUTPUT_EMPTY");
            var row = iterator.next();
            require(!iterator.hasNext() && row.getAlternateAlleles().size() == 1 && "VARVIS_SELECTED_ALT".equals(row.getID()),
                    "NORMALIZATION_OUTPUT_ASSOCIATION_AMBIGUOUS");
            normalized = new Allele(original.assembly(), org.nbscn.varvis.utils.CoordinateUtils.normalizeChromosome(row.getContig()),
                    row.getStart(), row.getReference().getDisplayString(), row.getAlternateAllele(0).getDisplayString());
        }
        require(original.contig().equals(normalized.contig()), "NORMALIZATION_CONTIG_CONFLICT");
        boolean extendedDeletion = allowExtendedDeletion && "GRCh38".equals(normalized.assembly())
                && BoundedSequenceDeletion.extended(normalized.reference(), normalized.alternate());
        require(extendedDeletion || normalized.reference().matches("[ACGT]{1,50}")
                && normalized.alternate().matches("[ACGT]{1,50}") && !normalized.reference().equals(normalized.alternate()),
                "NORMALIZED_ALLELE_OUTSIDE_FROZEN_SCOPE");
        int start = Math.min(original.position(), normalized.position());
        long end = Math.max((long) original.position() + original.reference().length(),
                (long) normalized.position() + normalized.reference().length());
        require(end - start <= 100_000, "NORMALIZATION_EQUIVALENCE_SPAN_LIMIT");
        diagnostics.put("referenceQueryAttempts", 2);
        var span = reference.observeNormalizationSpan(record.contig(), start, (int) (end - start));
        require(before.identity().path("observedSha256").equals(span.identity().path("observedSha256")),
                "NORMALIZATION_REFERENCE_IDENTITY_CONFLICT");
        String sequence = span.observation().path("observedReference").asText();
        require(apply(sequence, start, original).equals(apply(sequence, start, normalized)), "NORMALIZATION_NON_EQUIVALENT_ALLELE");
        // Independently reject unfinished trimming or a still-left-shiftable indel. Never repair it here.
        String ref = normalized.reference(), alt = normalized.alternate();
        require(!(ref.length() > 1 && alt.length() > 1
                && (ref.charAt(0) == alt.charAt(0) || ref.charAt(ref.length()-1) == alt.charAt(alt.length()-1)))
                && !(ref.length() != alt.length() && normalized.position() > 1
                && ref.charAt(ref.length()-1) == alt.charAt(alt.length()-1)), "NORMALIZATION_OUTPUT_NOT_MINIMAL");
        var proof = mapper.createObjectNode().put("method", extendedDeletion ? BoundedSequenceDeletion.NORMALIZATION_METHOD : METHOD)
                .put("methodVersion", VERSION)
                .put("equivalence", "REFERENCE_HAPLOTYPE_EQUIVALENCE").put("spanLimit", 100_000)
                .put("selectedAltIndex", record.selectedAltIndex()).put("originalRecord", record.mappingToken())
                .put("inputVcf", text).put("outputSha256", sha(Files.readAllBytes(output)));
        proof.set("analysisAllele", mapper.valueToTree(original));
        proof.set("queryAllele", mapper.valueToTree(normalized));
        proof.set("toolManifest", mapper.valueToTree(manifest));
        proof.set("inputReferenceObservation", before.observation());
        proof.set("equivalenceObservation", span.observation());
        return new Verified(normalized, before.identity(), proof, manifest.getToolVersion());
    }
    private static String apply(String sequence, int start, Allele allele) {
        int offset = allele.position() - start;
        require(offset >= 0 && offset + allele.reference().length() <= sequence.length()
                && sequence.substring(offset, offset + allele.reference().length()).equals(allele.reference()), "NORMALIZATION_OUTPUT_REF_MISMATCH");
        return sequence.substring(0, offset) + allele.alternate() + sequence.substring(offset + allele.reference().length());
    }
    private static String sha(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    record Verified(Allele allele, ObjectNode reference, ObjectNode proof, String toolVersion) { }
}
