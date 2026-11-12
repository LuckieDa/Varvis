package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Validates only tiny synthetic outputs; it never scans or transforms a VCF. */
public class SyntheticSpikedVcfValidator {

    public SyntheticVcfValidationResult validate(Path path, SyntheticVcfValidationRequest request) {
        SyntheticVcfValidationResult result = new SyntheticVcfValidationResult();
        if (path == null || request == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            return fail(result, SyntheticVcfValidationStatus.INPUT_MISSING, "VCF input is missing or unreadable");
        }
        Set<String> contigHeaders = new HashSet<String>();
        Set<String> observedChromosomes = new HashSet<String>();
        Set<String> alleles = new HashSet<String>();
        String headerSample = null;
        String reference = null;
        boolean sawColumnHeader = false;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("##reference=")) reference = line.substring("##reference=".length()).trim();
                if (line.startsWith("##assembly=")) reference = line.substring("##assembly=".length()).trim();
                if (line.startsWith("##contig=<ID=")) {
                    int start = "##contig=<ID=".length();
                    int end = line.indexOf(',', start);
                    if (end < 0) end = line.indexOf('>', start);
                    if (end > start) contigHeaders.add(normalizeChromosome(line.substring(start, end)));
                    continue;
                }
                if (line.startsWith("#CHROM")) {
                    String[] columns = line.split("\t", -1);
                    int sampleCount = Math.max(0, columns.length - 9);
                    if (columns.length < 8 || sampleCount != request.getExpectedSampleCount()) {
                        return fail(result, SyntheticVcfValidationStatus.SAMPLE_MISMATCH,
                                "VCF sample count does not match the synthetic request");
                    }
                    if (sampleCount > 0) headerSample = columns[9];
                    sawColumnHeader = true;
                    continue;
                }
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] columns = line.split("\t", -1);
                if (columns.length < 8) {
                    return fail(result, SyntheticVcfValidationStatus.MALFORMED_RECORD, "VCF record has fewer than 8 columns");
                }
                int position;
                try {
                    position = Integer.parseInt(columns[1]);
                } catch (NumberFormatException ex) {
                    return fail(result, SyntheticVcfValidationStatus.MALFORMED_RECORD, "VCF position is not an integer");
                }
                String chromosome = normalizeChromosome(columns[0]);
                String referenceAllele = columns[3];
                String alternate = columns[4];
                if (position <= 0 || referenceAllele.isEmpty() || alternate.isEmpty()) {
                    return fail(result, SyntheticVcfValidationStatus.MALFORMED_RECORD, "VCF REF/ALT/position is incomplete");
                }
                observedChromosomes.add(chromosome);
                for (String alt : alternate.split(",")) {
                    alleles.add(chromosome + "|" + position + "|" + referenceAllele.toUpperCase() + "|" + alt.toUpperCase());
                }
            }
        } catch (IOException ex) {
            return fail(result, SyntheticVcfValidationStatus.INPUT_MISSING, "VCF input could not be read");
        }
        result.setObservedAssembly(reference);
        result.setSampleId(headerSample);
        result.getObservedChromosomes().addAll(observedChromosomes);
        if (!sawColumnHeader) return fail(result, SyntheticVcfValidationStatus.HEADER_MALFORMED, "VCF column header is missing");
        if (request.getExpectedAssembly() == null || reference == null
                || !request.getExpectedAssembly().equalsIgnoreCase(reference)) {
            return fail(result, SyntheticVcfValidationStatus.ASSEMBLY_MISMATCH, "VCF assembly does not match request");
        }
        if (request.getExpectedSampleId() != null && !request.getExpectedSampleId().equals(headerSample)) {
            return fail(result, SyntheticVcfValidationStatus.SAMPLE_MISMATCH, "VCF sample ID does not match request");
        }
        for (String chromosome : request.getRequiredChromosomes()) {
            if (!contigHeaders.contains(normalizeChromosome(chromosome))
                    || !observedChromosomes.contains(normalizeChromosome(chromosome))) {
                return fail(result, SyntheticVcfValidationStatus.SCOPE_INCOMPLETE,
                        "synthetic VCF does not cover the complete requested chromosome scope");
            }
        }
        for (NormalizedVariantKey truth : request.getTruthVariants()) {
            if (truth == null) return fail(result, SyntheticVcfValidationStatus.TRUTH_MISSING, "null truth identity");
            if (!truth.getGenomeAssembly().equalsIgnoreCase(request.getExpectedAssembly())) {
                return fail(result, SyntheticVcfValidationStatus.ASSEMBLY_MISMATCH, "truth assembly does not match request");
            }
            String key = normalizeChromosome(truth.getChromosome()) + "|" + truth.getPosition() + "|"
                    + truth.getReference().toUpperCase() + "|" + truth.getAlternate().toUpperCase();
            if (!alleles.contains(key)) {
                return fail(result, SyntheticVcfValidationStatus.TRUTH_MISSING, "expected truth allele is absent");
            }
            result.getMatchedTruthVariants().add(truth.canonicalId());
        }
        result.setStatus(SyntheticVcfValidationStatus.VALID);
        return result;
    }

    private SyntheticVcfValidationResult fail(SyntheticVcfValidationResult result,
                                               SyntheticVcfValidationStatus status, String message) {
        result.setStatus(status);
        result.getIssues().add(message);
        return result;
    }

    private static String normalizeChromosome(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.regionMatches(true, 0, "chr", 0, 3)) normalized = normalized.substring(3);
        return normalized;
    }
}
