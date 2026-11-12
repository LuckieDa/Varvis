package org.nbscn.varvis.variantanalysis.service.impl;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.service.VcfBuildConverter;
import org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

@Service
public class PicardVcfBuildConverter implements VcfBuildConverter {

    private static final Map<String, String> GRCH37_NUMERIC_TO_UCSC = grch37NumericToUcsc();

    private final VariantAnalysisProperties properties;

    public PicardVcfBuildConverter(VariantAnalysisProperties properties) {
        this.properties = properties;
    }

    @Override
    public BuildConversionResult convert(Path inputFile,
                                         Path workspace,
                                         GenomeAssembly inputAssembly,
                                         GenomeAssembly analysisAssembly) throws IOException {
        if (!inputAssembly.requiresLiftOverTo(analysisAssembly)) {
            return new BuildConversionResult(inputFile, false, "NONE", Collections.emptyList());
        }
        if (inputAssembly != GenomeAssembly.GRCh37 || analysisAssembly != GenomeAssembly.GRCh38) {
            throw new IllegalStateException(
                    "Only GRCh37/hg19 input can be lifted over to the GRCh38 analysis build"
            );
        }
        VariantAnalysisProperties.LiftOver config = properties.getLiftOver();
        if (config.getMode() != VariantAnalysisProperties.LiftOver.Mode.PICARD) {
            throw new IllegalStateException(
                    "GRCh37/hg19 input requires liftOver before analysis. Configure "
                            + "variant-analysis.lift-over.mode=PICARD, picard-jar, "
                            + "hg19-to-hg38-chain, and the GRCh38 reference FASTA."
            );
        }
        validateConfig(config);

        Path output = workspace.resolve("lifted-grch38.vcf");
        Path reject = workspace.resolve("liftover-rejected.vcf");
        Path log = workspace.resolve("liftover-picard.log");
        var proofMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var originalIdentity = ConversionSourceProof.artifact(proofMapper, inputFile);
        Path liftoverInput = adaptInputContigsForChain(inputFile, workspace, config.getHg19ToHg38Chain());
        Path linkedInput = ConversionSourceProof.tagInput(liftoverInput, workspace);
        List<String> command = command(config, linkedInput, output, reject);
        run(command, log, config.getTimeout());

        if (!Files.isRegularFile(output)) {
            throw new IllegalStateException("Picard LiftoverVcf did not produce a GRCh38 VCF");
        }

        List<String> warnings = new ArrayList<String>();
        warnings.add("Input GRCh37/hg19 VCF was lifted over to GRCh38 before analysis.");
        if (!inputFile.equals(liftoverInput)) {
            warnings.add("Applied GRCh37 numeric-to-UCSC canonical contig aliases for the configured liftover chain.");
        }
        long sourceRecords = countVcfRecords(inputFile);
        long liftedRecords = countVcfRecords(output);
        int rejectedRecords = (int) Math.min(Integer.MAX_VALUE, countVcfRecords(reject));
        warnings.add("Liftover record audit: source=" + sourceRecords
                + ", lifted=" + liftedRecords + ", rejected=" + rejectedRecords + ".");
        if (rejectedRecords > 0) {
            warnings.add(
                    rejectedRecords + " variant record(s) could not be lifted to GRCh38; see "
                            + reject.getFileName()
            );
        }
        com.fasterxml.jackson.databind.node.ObjectNode sourceProof;
        try {
            sourceProof = ConversionSourceProof.completed(proofMapper, originalIdentity, inputFile, linkedInput, output, reject, properties);
        } catch (IOException | IllegalArgumentException unavailable) {
            // Analysis output remains usable; evidence cannot manufacture a missing conversion witness.
            sourceProof = proofMapper.createObjectNode().put("version", ConversionSourceProof.VERSION)
                    .put("status", "BLOCKED").put("reasonCode", "CONVERSION_SOURCE_PROOF_UNAVAILABLE");
            warnings.add("Conversion evidence source could not be verified; criterion evaluation will retain its blocker.");
        }
        return new BuildConversionResult(
                output, true, "Picard LiftoverVcf", warnings, reject, rejectedRecords,
                sourceRecords, liftedRecords, sourceProof
        );
    }

    private void validateConfig(VariantAnalysisProperties.LiftOver config) {
        if (!StringUtils.hasText(config.getJavaExecutable())) {
            throw new IllegalStateException("variant-analysis.lift-over.java-executable is not configured");
        }
        if (config.getPicardJar() == null || !Files.isRegularFile(config.getPicardJar())) {
            throw new IllegalStateException("Picard jar is not configured or does not exist");
        }
        if (config.getHg19ToHg38Chain() == null || !Files.isRegularFile(config.getHg19ToHg38Chain())) {
            throw new IllegalStateException("hg19ToHg38 chain file is not configured or does not exist");
        }
        Path reference = properties.getNormalization().getGrch38Reference();
        if (reference == null || !Files.isRegularFile(reference)) {
            throw new IllegalStateException("GRCh38 reference FASTA is required for Picard LiftoverVcf");
        }
        Path dictionary = reference.resolveSibling(referenceFileStem(reference) + ".dict");
        if (!Files.isRegularFile(dictionary)) {
            throw new IllegalStateException("GRCh38 sequence dictionary is required for Picard LiftoverVcf: "
                    + dictionary);
        }
    }

    /**
     * Picard's commonly used hg19ToHg38 chain is named with UCSC source contigs (chr1, chr2, ...),
     * whereas clinical GRCh37 VCFs frequently use numeric contigs (1, 2, ...).  Create a private
     * liftover-only copy when both conditions hold; never alter the uploaded VCF.
     */
    Path adaptInputContigsForChain(Path inputFile, Path workspace, Path chainFile) throws IOException {
        if (!chainUsesUcscSourceContigs(chainFile)) {
            return inputFile;
        }
        Path adapted = workspace.resolve("liftover-grch37-ucsc-contigs.vcf");
        boolean changed = false;
        try (BufferedReader reader = openText(inputFile);
             BufferedWriter writer = Files.newBufferedWriter(adapted, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String rewritten = rewriteContig(line);
                changed = changed || !line.equals(rewritten);
                writer.write(rewritten);
                writer.newLine();
            }
        }
        if (changed) {
            return adapted;
        }
        Files.deleteIfExists(adapted);
        return inputFile;
    }

    private boolean chainUsesUcscSourceContigs(Path chainFile) throws IOException {
        try (BufferedReader reader = openText(chainFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("chain ")) {
                    continue;
                }
                String[] fields = line.trim().split("\\s+");
                return fields.length > 7 && fields[7].startsWith("chr");
            }
        }
        return false;
    }

    private String rewriteContig(String line) {
        if (line.startsWith("##contig=<ID=")) {
            int start = "##contig=<ID=".length();
            int end = line.indexOf(',', start);
            if (end < 0) {
                end = line.indexOf('>', start);
            }
            if (end > start) {
                String replacement = GRCH37_NUMERIC_TO_UCSC.get(line.substring(start, end));
                if (replacement != null) {
                    return line.substring(0, start) + replacement + line.substring(end);
                }
            }
            return line;
        }
        if (line.startsWith("#")) {
            return line;
        }
        int tab = line.indexOf('\t');
        if (tab <= 0) {
            return line;
        }
        String replacement = GRCH37_NUMERIC_TO_UCSC.get(line.substring(0, tab));
        return replacement == null ? line : replacement + line.substring(tab);
    }

    private List<String> command(VariantAnalysisProperties.LiftOver config,
                                 Path inputFile,
                                 Path output,
                                 Path reject) {
        Path reference = properties.getNormalization().getGrch38Reference();
        List<String> command = new ArrayList<String>();
        command.add(config.getJavaExecutable());
        if (StringUtils.hasText(config.getJavaHeap())) {
            command.add("-Xmx" + config.getJavaHeap().trim());
        }
        command.add("-jar");
        command.add(config.getPicardJar().toAbsolutePath().normalize().toString());
        command.add("LiftoverVcf");
        command.add("I=" + inputFile.toAbsolutePath().normalize());
        command.add("O=" + output.toAbsolutePath().normalize());
        command.add("CHAIN=" + config.getHg19ToHg38Chain().toAbsolutePath().normalize());
        command.add("REJECT=" + reject.toAbsolutePath().normalize());
        command.add("R=" + reference.toAbsolutePath().normalize());
        command.add("LIFTOVER_MIN_MATCH=" + config.getMinMatch());
        command.add("WRITE_ORIGINAL_POSITION=true");
        command.add("WRITE_ORIGINAL_ALLELES=true");
        command.add("RECOVER_SWAPPED_REF_ALT=true");
        command.add("WARN_ON_MISSING_CONTIG=true");
        command.add("CREATE_INDEX=false");
        command.add("VALIDATION_STRINGENCY=SILENT");
        return command;
    }

    private void run(List<String> command, Path logFile, Duration timeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start Picard LiftoverVcf", ex);
        }
        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Picard LiftoverVcf was interrupted", ex);
        }
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("Picard LiftoverVcf timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Picard LiftoverVcf failed with exit code "
                    + process.exitValue() + ": " + readLogSummary(logFile));
        }
    }

    private long countVcfRecords(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        long records = 0L;
        try (BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(file));
             InputStream input = gzipIfNeeded(raw);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#") && !line.trim().isEmpty()) {
                    records++;
                }
            }
        }
        return records;
    }

    private BufferedReader openText(Path file) throws IOException {
        BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(file));
        return new BufferedReader(new InputStreamReader(gzipIfNeeded(raw), StandardCharsets.UTF_8));
    }

    private InputStream gzipIfNeeded(BufferedInputStream input) throws IOException {
        input.mark(2);
        int first = input.read();
        int second = input.read();
        input.reset();
        return first == 0x1f && second == 0x8b ? new GZIPInputStream(input) : input;
    }

    private String readLogSummary(Path logFile) throws IOException {
        if (!Files.exists(logFile)) {
            return "Picard did not write a log file";
        }
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index).trim();
            if (line.startsWith("ERROR:")) {
                return line.length() > 500 ? line.substring(0, 500) : line;
            }
        }
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index).trim();
            if (line.contains("OutOfMemoryError")) {
                return line.length() > 500 ? line.substring(0, 500) : line;
            }
        }
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index).trim();
            if (!line.isEmpty()) {
                return line.length() > 500 ? line.substring(0, 500) : line;
            }
        }
        return "Picard did not write an error message";
    }

    private String referenceFileStem(Path reference) {
        String name = reference.getFileName().toString();
        if (name.endsWith(".fa")) {
            return name.substring(0, name.length() - 3);
        }
        if (name.endsWith(".fasta")) {
            return name.substring(0, name.length() - 6);
        }
        return name;
    }

    private static Map<String, String> grch37NumericToUcsc() {
        Map<String, String> aliases = new HashMap<String, String>();
        for (int chromosome = 1; chromosome <= 22; chromosome++) {
            aliases.put(String.valueOf(chromosome), "chr" + chromosome);
        }
        aliases.put("X", "chrX");
        aliases.put("Y", "chrY");
        aliases.put("M", "chrM");
        aliases.put("MT", "chrM");
        return Collections.unmodifiableMap(aliases);
    }
}
