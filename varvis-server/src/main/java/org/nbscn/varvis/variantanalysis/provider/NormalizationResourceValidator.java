package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class NormalizationResourceValidator {

    private static final long GRCH37_CHROMOSOME_1_LENGTH = 249250621L;
    private static final long GRCH38_CHROMOSOME_1_LENGTH = 248956422L;

    private final VariantAnalysisProperties properties;

    public NormalizationResourceValidator(VariantAnalysisProperties properties) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
    }

    public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
        VariantAnalysisProperties.Normalization normalization = properties.getNormalization();
        Path reference = normalization.referenceFor(assembly);
        String profileId = normalization.profileIdFor(assembly);
        String configuredSha = normalization.referenceSha256For(assembly);
        List<String> missing = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        if (assembly == null) {
            return report(null, "ASSEMBLY_UNSUPPORTED", profileId, reference, configuredSha,
                    false, false, 0, "UNRESOLVED", missing, warnings);
        }
        if (reference == null) {
            missing.add("normalization." + assembly.name() + ".reference");
            return report(assembly, "RESOURCE_UNAVAILABLE", profileId, null, configuredSha,
                    false, false, 0, "UNRESOLVED", missing, warnings);
        }
        boolean referenceExists = Files.isRegularFile(reference);
        Path fastaIndex = Paths.get(reference.toString() + ".fai");
        boolean indexExists = referenceExists && Files.isRegularFile(fastaIndex);
        if (!referenceExists) {
            missing.add("normalization." + assembly.name() + ".reference.exists");
        }
        if (referenceExists && !indexExists) {
            missing.add("normalization." + assembly.name() + ".reference.fai");
        }
        if (!StringUtils.hasText(profileId)) {
            warnings.add("Reference profile ID is not configured for " + assembly.name() + ".");
        }
        if (!StringUtils.hasText(configuredSha)) {
            warnings.add("Reference SHA-256 is not frozen for " + assembly.name() + ".");
        }
        if (!missing.isEmpty()) {
            return report(assembly, "RESOURCE_UNAVAILABLE", profileId, reference, configuredSha,
                    referenceExists, indexExists, 0, "UNRESOLVED", missing, warnings);
        }
        try {
            FastaIndexSummary indexSummary = inspectFastaIndex(fastaIndex);
            long expectedLength = assembly == GenomeAssembly.GRCh37
                    ? GRCH37_CHROMOSOME_1_LENGTH
                    : GRCH38_CHROMOSOME_1_LENGTH;
            if (indexSummary.chromosome1Length != expectedLength) {
                warnings.add("Chromosome 1 length does not match " + assembly.name() + ".");
                return report(assembly, "ASSEMBLY_UNSUPPORTED", profileId, reference, configuredSha,
                        true, true, indexSummary.contigCount, indexSummary.contigStyle, missing, warnings);
            }
            if ("MIXED".equals(indexSummary.contigStyle) || "UNRESOLVED".equals(indexSummary.contigStyle)) {
                warnings.add("Reference contig naming is mixed or unresolved.");
                return report(assembly, "INVALID_REFERENCE_PROFILE", profileId, reference, configuredSha,
                        true, true, indexSummary.contigCount, indexSummary.contigStyle, missing, warnings);
            }
            return report(assembly, "READY", profileId, reference, configuredSha,
                    true, true, indexSummary.contigCount, indexSummary.contigStyle, missing, warnings);
        } catch (IOException | RuntimeException ex) {
            warnings.add("Reference FAI could not be parsed: " + safeMessage(ex));
            return report(assembly, "INVALID_REFERENCE_PROFILE", profileId, reference, configuredSha,
                    true, true, 0, "UNRESOLVED", missing, warnings);
        }
    }

    private FastaIndexSummary inspectFastaIndex(Path fastaIndex) throws IOException {
        int count = 0;
        int prefixed = 0;
        int unprefixed = 0;
        Long chromosome1Length = null;
        try (BufferedReader reader = Files.newBufferedReader(fastaIndex, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] columns = line.split("\\t");
                if (columns.length < 2) {
                    throw new IllegalArgumentException("FAI row has fewer than two columns");
                }
                String contig = columns[0].trim();
                long length = Long.parseLong(columns[1]);
                count++;
                if (contig.toLowerCase().startsWith("chr")) {
                    prefixed++;
                } else {
                    unprefixed++;
                }
                if ("1".equals(contig) || "chr1".equalsIgnoreCase(contig)) {
                    chromosome1Length = length;
                }
            }
        }
        String style = prefixed > 0 && unprefixed > 0
                ? "MIXED"
                : prefixed > 0 ? "CHR_PREFIXED" : unprefixed > 0 ? "NO_CHR_PREFIX" : "UNRESOLVED";
        return new FastaIndexSummary(count, style, chromosome1Length == null ? -1L : chromosome1Length);
    }

    private NormalizationReferenceReadiness report(GenomeAssembly assembly,
                                                    String status,
                                                    String profileId,
                                                    Path reference,
                                                    String configuredSha,
                                                    boolean referenceExists,
                                                    boolean indexExists,
                                                    int contigCount,
                                                    String contigStyle,
                                                    List<String> missing,
                                                    List<String> warnings) {
        return new NormalizationReferenceReadiness(
                assembly,
                status,
                profileId,
                reference == null || reference.getFileName() == null ? null : reference.getFileName().toString(),
                configuredSha,
                referenceExists,
                indexExists,
                contigCount,
                contigStyle,
                missing,
                warnings
        );
    }

    private String safeMessage(Exception ex) {
        String message = ex == null ? null : ex.getMessage();
        if (message == null || !StringUtils.hasText(message)) {
            return ex == null ? "unknown error" : ex.getClass().getSimpleName();
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    private static final class FastaIndexSummary {
        private final int contigCount;
        private final String contigStyle;
        private final long chromosome1Length;

        private FastaIndexSummary(int contigCount, String contigStyle, long chromosome1Length) {
            this.contigCount = contigCount;
            this.contigStyle = contigStyle;
            this.chromosome1Length = chromosome1Length;
        }
    }
}
