package org.nbscn.varvis.variantanalysis.service.impl;

import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFContigHeaderLine;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.NormalizationRunManifest;
import org.nbscn.varvis.variantanalysis.provider.NormalizationReferenceReadiness;
import org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator;
import org.nbscn.varvis.variantanalysis.service.NormalizationManifestWriter;
import org.nbscn.varvis.variantanalysis.service.VcfNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BcftoolsVcfNormalizer implements VcfNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BcftoolsVcfNormalizer.class);
    private static final long MAX_CONTIG_ALIAS_BYTES = 1024L * 1024L;
    private static final Pattern NORM_STATS_PATTERN = Pattern.compile(
            "Lines\\s+total/split/realigned/skipped:\\s*(\\d+)/(\\d+)/(\\d+)/(\\d+)"
    );
    private static final Pattern EXTENDED_NORM_STATS_PATTERN = Pattern.compile(
            "Lines\\s+total/split/joined/realigned/mismatch_removed/dup_removed/skipped:"
                    + "\\s*(\\d+)/(\\d+)/(\\d+)/(\\d+)/(\\d+)/(\\d+)/(\\d+)"
    );
    private static final Pattern UCSC_GRCH37_GL_CONTIG_PATTERN = Pattern.compile(
            "^chr(?:[0-9]+|X|Y|Un)_gl(\\d{6})(?:_(?:random|unplaced))?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern UCSC_GRCH37_HAPLOTYPE_CONTIG_PATTERN = Pattern.compile(
            "^chr(?:[0-9]+|X|Y)_[a-z0-9]+_hap\\d+$",
            Pattern.CASE_INSENSITIVE
    );

    private final VariantAnalysisProperties properties;
    private final NormalizationResourceValidator resourceValidator;
    private volatile String detectedBcftoolsVersion;

    public BcftoolsVcfNormalizer(VariantAnalysisProperties properties) {
        this.properties = properties;
        this.resourceValidator = new NormalizationResourceValidator(properties);
    }

    @Override
    public NormalizationResult normalize(Path inputFile,
                                         Path workspace,
                                         GenomeAssembly genomeAssembly) throws IOException {
        if (properties.getNormalization().getMode() == VariantAnalysisProperties.Mode.COPY_ONLY) {
            return copyWithoutReferenceNormalization(inputFile, workspace);
        }
        String startedAt = Instant.now().toString();
        long startedNanos = System.nanoTime();
        try {
            return normalizeWithBcftools(
                    inputFile, workspace, genomeAssembly, startedAt, startedNanos
            );
        } catch (IOException | RuntimeException ex) {
            writeFailureManifest(workspace, genomeAssembly, startedAt, startedNanos, ex);
            throw ex;
        }
    }

    private NormalizationResult copyWithoutReferenceNormalization(Path inputFile, Path workspace) throws IOException {
        return copyWithoutReferenceNormalization(
                inputFile,
                workspace,
                "COPY_ONLY",
                Collections.singletonList(
                        "Reference-based normalization was not applied. "
                                + "Configure variant-analysis.normalization.mode=BCFTOOLS, "
                                + "bcftools and the matching reference FASTA before using normalized coordinates."
                )
        );
    }

    private NormalizationResult copyWithoutReferenceNormalization(Path inputFile,
                                                                   Path workspace,
                                                                   String engine,
                                                                   List<String> warnings) throws IOException {
        String extension = extensionOf(inputFile);
        Path outputFile = workspace.resolve("qc-input" + extension);
        Files.copy(inputFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
        return new NormalizationResult(
                outputFile,
                false,
                engine,
                warnings
        );
    }

    private NormalizationResult normalizeWithBcftools(Path inputFile,
                                                      Path workspace,
                                                      GenomeAssembly genomeAssembly,
                                                      String startedAt,
                                                      long startedNanos) throws IOException {
        if (genomeAssembly == null) {
            throw new IllegalStateException(
                    "Genome assembly must be resolved before reference-based normalization"
            );
        }
        Path reference = referenceFor(genomeAssembly);
        if (reference == null || !Files.isRegularFile(reference)) {
            throw new IllegalStateException(
                    "Reference FASTA for " + genomeAssembly.name() + " is not configured or does not exist"
            );
        }
        Path referenceIndex = Paths.get(reference.toString() + ".fai");
        if (!Files.isRegularFile(referenceIndex)) {
            throw new IllegalStateException(
                    "Reference FASTA index for " + genomeAssembly.name() + " does not exist: "
                            + referenceIndex.getFileName()
            );
        }
        NormalizationReferenceReadiness readiness = resourceValidator.validate(genomeAssembly);
        if (!readiness.isReady()) {
            throw new IllegalStateException(
                    "Normalization reference profile for " + genomeAssembly.name() + " is "
                            + readiness.getStatus() + ": missing=" + readiness.getMissingResources()
                            + ", warnings=" + readiness.getWarnings()
            );
        }
        validateSupportedInputMetadata(inputFile);
        Path contigAliasFile = properties.getNormalization().contigAliasFileFor(genomeAssembly);
        Map<String, String> contigAliases = resolveContigAliases(
                inputFile,
                referenceIndex,
                genomeAssembly,
                loadContigAliases(contigAliasFile)
        );
        List<String> copyOnlyWarnings = validateContigLengths(
                inputFile, referenceIndex, genomeAssembly, contigAliases
        );
        if (!copyOnlyWarnings.isEmpty()) {
            return copyWithoutReferenceNormalization(
                    inputFile,
                    workspace,
                    "COPY_ONLY_REFERENCE_PROFILE_INCOMPATIBLE",
                    copyOnlyWarnings
                );
        }
        Path effectiveContigAliasFile = materializeContigAliases(workspace, contigAliases);
        String executable = properties.getNormalization().getBcftoolsExecutable();
        if (!StringUtils.hasText(executable)) {
            throw new IllegalStateException("bcftools executable is not configured");
        }
        String toolVersion = detectBcftoolsVersion(executable, workspace);

        Path contigAdjustedFile = workspace.resolve("contig-adjusted.vcf.gz");
        Path reheaderedFile = workspace.resolve("reference-header.vcf.gz");
        Path normalizedUnsortedFile = workspace.resolve("normalized.unsorted.vcf.gz");
        Path normalizedFile = workspace.resolve("normalized.vcf.gz");
        Path logFile = workspace.resolve("bcftools.log");
        Path commandInput = inputFile;
        if (effectiveContigAliasFile != null) {
            runCommand(
                    Arrays.asList(
                            executable,
                            "annotate",
                            "--rename-chrs",
                            effectiveContigAliasFile.toString(),
                            "-Oz",
                            "-o",
                            contigAdjustedFile.toString(),
                            inputFile.toString()
                    ),
                    workspace,
                    logFile
            );
            commandInput = contigAdjustedFile;
        }
        runCommand(
                Arrays.asList(
                        executable,
                        "reheader",
                        "--fai",
                        referenceIndex.toString(),
                        "--output",
                        reheaderedFile.toString(),
                        commandInput.toString()
                ),
                workspace,
                logFile
        );
        List<String> normalizeCommand = new ArrayList<String>(Arrays.asList(
                executable,
                "norm",
                "-f",
                reference.toString(),
                "-c",
                "e",
                "-m",
                "-any"
        ));
        if (hasAlleleDepthFormat(inputFile)) {
            normalizeCommand.add("--keep-sum");
            normalizeCommand.add("AD");
        }
        normalizeCommand.addAll(Arrays.asList(
                "--old-rec-tag",
                "VARVIS_ORIGINAL_VARIANT",
                reheaderedFile.toString(),
                "-Oz",
                "-o",
                normalizedUnsortedFile.toString()
        ));
        runCommand(normalizeCommand, workspace, logFile);
        runCommand(
                Arrays.asList(
                        executable,
                        "sort",
                        "-Oz",
                        "-T",
                        workspace.resolve("bcftools-sort.XXXXXX").toString(),
                        "-o",
                        normalizedFile.toString(),
                        normalizedUnsortedFile.toString()
                ),
                workspace,
                logFile
        );
        runCommand(
                Arrays.asList(executable, "index", "-f", normalizedFile.toString()),
                workspace,
                logFile
        );
        Files.deleteIfExists(normalizedUnsortedFile);
        Files.deleteIfExists(reheaderedFile);
        Files.deleteIfExists(contigAdjustedFile);
        if (!Files.isRegularFile(normalizedFile) || Files.size(normalizedFile) == 0) {
            throw new IllegalStateException("bcftools did not produce a normalized VCF");
        }
        NormStats stats = readNormStats(logFile);
        NormalizationRunManifest manifest = new NormalizationRunManifest(
                "PASSED",
                genomeAssembly.name(),
                properties.getNormalization().profileIdFor(genomeAssembly),
                properties.getNormalization().referenceSha256For(genomeAssembly),
                "bcftools",
                toolVersion,
                commandDigest(effectiveContigAliasFile != null, hasAlleleDepthFormat(inputFile)),
                effectiveContigAliasFile != null,
                stats.total,
                null,
                stats.split,
                stats.realigned,
                stats.skipped,
                elapsedMillis(startedNanos),
                startedAt,
                Instant.now().toString(),
                null,
                null,
                "NOT_COLLECTED_PERFORMANCE_POLICY"
        );
        NormalizationManifestWriter.write(workspace, manifest);
        return new NormalizationResult(
                normalizedFile,
                true,
                "bcftools norm + sort",
                Collections.emptyList(),
                manifest
        );
    }

    private void runCommand(List<String> command, Path workspace, Path logFile) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workspace.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not start bcftools. Configure variant-analysis.normalization.bcftools-executable",
                    ex
            );
        }

        Duration timeout = properties.getNormalization().getTimeout();
        long timeoutMs = timeout == null ? Duration.ofMinutes(10).toMillis() : timeout.toMillis();
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                terminateOwnedProcess(process);
                throw new IllegalStateException("bcftools command timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            terminateOwnedProcess(process);
            throw new IllegalStateException("bcftools command was interrupted", ex);
        }
        if (process.exitValue() != 0) {
            String summary = readLogSummary(logFile);
            throw new IllegalStateException(
                    summary.startsWith("Reference allele mismatch")
                            ? "Uploaded VCF REF allele does not match the configured reference FASTA: "
                                    + summary + ". Confirm the input assembly and exact reference identity."
                            : "bcftools command failed with exit code " + process.exitValue() + ": " + summary
            );
        }
    }

    private Path referenceFor(GenomeAssembly genomeAssembly) {
        return properties.getNormalization().referenceFor(genomeAssembly);
    }

    private static void terminateOwnedProcess(Process process) throws IOException {
        var children = process.descendants().toList();
        children.forEach(mappedItem -> mappedItem.destroyForcibly());
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("bcftools cleanup unconfirmed");
            for (var child : children) {
                try { child.onExit().get(5, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                    throw new IOException("bcftools child cleanup unconfirmed", failure);
                }
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new IOException("bcftools cleanup interrupted", failure);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private boolean hasAlleleDepthFormat(Path inputFile) {
        try (VCFFileReader reader = new VCFFileReader(inputFile, false)) {
            return reader.getFileHeader().getFormatHeaderLine("AD") != null;
        }
    }

    private void validateSupportedInputMetadata(Path inputFile) {
        try (VCFFileReader reader = new VCFFileReader(inputFile, false)) {
            if (reader.getFileHeader().getInfoHeaderLine("SYNTHETIC_GRCH38_POS") != null) {
                throw new IllegalStateException(
                        "Uploaded VCF is marked SYNTHETIC_GRCH38_POS and is not a reference-valid GRCh38 VCF. "
                                + "It appears to contain synthetic coordinates derived from GRCh37/hg19. "
                                + "Provide a clinical-grade GRCh38 VCF or perform a validated liftOver with "
                                + "REF/ALT reconciliation before analysis."
                );
            }
        }
    }

    private List<String> validateContigLengths(Path inputFile,
                                                Path referenceIndex,
                                                GenomeAssembly genomeAssembly,
                                                Map<String, String> contigAliases)
            throws IOException {
        Map<String, Integer> expectedLengths = referenceLengths(referenceIndex);
        Map<String, Integer> mismatches = new LinkedHashMap<String, Integer>();
        List<String> unmapped = new ArrayList<String>();
        List<String> copyOnlyWarnings = new ArrayList<String>();
        for (Map.Entry<String, String> alias : contigAliases.entrySet()) {
            if (!expectedLengths.containsKey(alias.getValue())) {
                throw new IllegalStateException(
                        "Contig alias target '" + alias.getValue()
                                + "' is absent from the selected reference profile"
                );
            }
        }
        try (VCFFileReader reader = new VCFFileReader(inputFile, false)) {
            for (VCFContigHeaderLine contig : reader.getFileHeader().getContigLines()) {
                String id = contig.getID();
                String referenceContig = contigAliases.containsKey(id) ? contigAliases.get(id) : id;
                Integer expectedLength = expectedLengths.get(referenceContig);
                int actualLength = contig.getSAMSequenceRecord().getSequenceLength();
                if (expectedLength == null) {
                    unmapped.add(id);
                } else if (actualLength > 0 && actualLength != expectedLength) {
                    mismatches.put(referenceContig, actualLength);
                }
            }
        }
        if (!mismatches.isEmpty()) {
            if (isKnownGrch37MitochondrialReferenceMismatch(genomeAssembly, mismatches, expectedLengths)) {
                copyOnlyWarnings.add(unsupportedMitochondrialWarning());
            } else {
                Map.Entry<String, Integer> first = mismatches.entrySet().iterator().next();
                throw new IllegalStateException(
                        "Uploaded VCF contig lengths do not match " + genomeAssembly.name()
                                + " or its configured reference profile. "
                                + first.getKey() + " length is " + first.getValue()
                                + " but the selected reference expects " + expectedLengths.get(first.getKey())
                                + ". Confirm assembly and reference FASTA before analysis."
                );
            }
        }
        if (!unmapped.isEmpty()) {
            if (genomeAssembly == GenomeAssembly.GRCh37 && allKnownUCSCGrch37HaplotypeContigs(unmapped)) {
                copyOnlyWarnings.addAll(unsupportedHaplotypeWarning(unmapped));
            } else {
                throw new IllegalStateException(
                        "VCF contig '" + unmapped.get(0)
                                + "' is not present in the selected reference profile. "
                                + "Configure an explicit assembly-specific contig alias file when names differ."
                );
            }
        }
        return Collections.unmodifiableList(copyOnlyWarnings);
    }

    private boolean isKnownGrch37MitochondrialReferenceMismatch(GenomeAssembly genomeAssembly,
                                                                  Map<String, Integer> mismatches,
                                                                  Map<String, Integer> expectedLengths) {
        if (genomeAssembly != GenomeAssembly.GRCh37 || mismatches.size() != 1) {
            return false;
        }
        Integer actualLength = mismatches.get("MT");
        Integer expectedLength = expectedLengths.get("MT");
        return Integer.valueOf(16571).equals(actualLength) && Integer.valueOf(16569).equals(expectedLength);
    }

    private boolean allKnownUCSCGrch37HaplotypeContigs(List<String> contigs) {
        if (contigs == null || contigs.isEmpty()) {
            return false;
        }
        for (String contig : contigs) {
            if (!StringUtils.hasText(contig)
                    || !UCSC_GRCH37_HAPLOTYPE_CONTIG_PATTERN.matcher(contig.trim()).matches()) {
                return false;
            }
        }
        return true;
    }

    private List<String> unsupportedHaplotypeWarning(List<String> contigs) {
        String first = contigs.get(0);
        String suffix = contigs.size() == 1 ? "" : " and " + (contigs.size() - 1) + " additional contig(s)";
        return Collections.singletonList(
                "Reference-based normalization was skipped because the configured GRCh37 FASTA does not "
                        + "contain UCSC haplotype contig '" + first + "'" + suffix + ". "
                        + "All VCF variants were preserved in COPY_ONLY mode; use a reference profile that "
                        + "includes these haplotype contigs to normalize them."
        );
    }

    private String unsupportedMitochondrialWarning() {
        return "Reference-based normalization was skipped because the uploaded GRCh37 VCF uses a "
                + "16571 bp UCSC mitochondrial contig while the configured FASTA uses a 16569 bp MT sequence. "
                + "All VCF variants were preserved in COPY_ONLY mode; configure a matching mitochondrial "
                + "reference profile before reference-based normalization.";
    }

    private Map<String, String> resolveContigAliases(Path inputFile,
                                                      Path referenceIndex,
                                                      GenomeAssembly genomeAssembly,
                                                      Map<String, String> configuredAliases)
            throws IOException {
        Map<String, String> resolved = new LinkedHashMap<String, String>(configuredAliases);
        if (genomeAssembly != GenomeAssembly.GRCh37) {
            return resolved;
        }
        Map<String, Integer> referenceLengths = referenceLengths(referenceIndex);
        int derivedCount = 0;
        try (VCFFileReader reader = new VCFFileReader(inputFile, false)) {
            for (VCFContigHeaderLine contig : reader.getFileHeader().getContigLines()) {
                String source = contig.getID();
                if (resolved.containsKey(source) || referenceLengths.containsKey(source)) {
                    continue;
                }
                String target = derivedGrch37GlContig(source);
                Integer expectedLength = target == null ? null : referenceLengths.get(target);
                int actualLength = contig.getSAMSequenceRecord().getSequenceLength();
                if (expectedLength != null && (actualLength <= 0 || actualLength == expectedLength)) {
                    resolved.put(source, target);
                    derivedCount++;
                }
            }
        }
        if (derivedCount > 0) {
            LOGGER.info(
                    "Derived {} safe GRCh37 UCSC-to-Ensembl GL contig aliases for normalization",
                    derivedCount
            );
        }
        return resolved;
    }

    private String derivedGrch37GlContig(String sourceContig) {
        if (!StringUtils.hasText(sourceContig)) {
            return null;
        }
        Matcher matcher = UCSC_GRCH37_GL_CONTIG_PATTERN.matcher(sourceContig.trim());
        return matcher.matches() ? "GL" + matcher.group(1) + ".1" : null;
    }

    private Map<String, Integer> referenceLengths(Path referenceIndex) throws IOException {
        Map<String, Integer> result = new HashMap<String, Integer>();
        for (String line : Files.readAllLines(referenceIndex, StandardCharsets.UTF_8)) {
            String[] columns = line.split("\t");
            if (columns.length >= 2) {
                result.put(columns[0], Integer.parseInt(columns[1]));
            }
        }
        return result;
    }

    private Map<String, String> loadContigAliases(Path aliasFile) throws IOException {
        if (aliasFile == null) {
            return Collections.emptyMap();
        }
        if (!Files.isRegularFile(aliasFile)) {
            throw new IllegalStateException(
                    "Configured contig alias file does not exist: " + aliasFile.getFileName()
            );
        }
        if (Files.size(aliasFile) > MAX_CONTIG_ALIAS_BYTES) {
            throw new IllegalStateException("Configured contig alias file exceeds the 1 MiB safety limit");
        }
        Map<String, String> aliases = new LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(aliasFile, StandardCharsets.UTF_8)) {
            String value = line == null ? "" : line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            String[] columns = value.split("\\s+");
            if (columns.length != 2 || !StringUtils.hasText(columns[0]) || !StringUtils.hasText(columns[1])) {
                throw new IllegalStateException(
                        "Contig alias file must contain exactly two columns: source and reference contig"
                );
            }
            String previous = aliases.put(columns[0], columns[1]);
            if (previous != null && !previous.equals(columns[1])) {
                throw new IllegalStateException(
                        "Contig alias file contains conflicting mappings for '" + columns[0] + "'"
                );
            }
        }
        if (aliases.isEmpty()) {
            throw new IllegalStateException("Configured contig alias file is empty");
        }
        return Collections.unmodifiableMap(aliases);
    }

    private Path materializeContigAliases(Path workspace, Map<String, String> aliases) throws IOException {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<String>(aliases.size());
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            lines.add(alias.getKey() + "\t" + alias.getValue());
        }
        Path resolvedAliases = workspace.resolve("resolved-contig-aliases.tsv");
        Files.write(resolvedAliases, lines, StandardCharsets.UTF_8);
        return resolvedAliases;
    }

    private String detectBcftoolsVersion(String executable, Path workspace) throws IOException {
        String cached = detectedBcftoolsVersion;
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        synchronized (this) {
            if (StringUtils.hasText(detectedBcftoolsVersion)) {
                return detectedBcftoolsVersion;
            }
            Path versionLog = workspace.resolve("bcftools-version.log");
            Files.deleteIfExists(versionLog);
            runCommand(Arrays.asList(executable, "--version"), workspace, versionLog);
            String firstLine = firstNonEmptyLine(versionLog);
            Files.deleteIfExists(versionLog);
            detectedBcftoolsVersion = StringUtils.hasText(firstLine)
                    ? firstLine.replaceFirst("^bcftools\\s+", "").trim()
                    : "UNKNOWN";
            return detectedBcftoolsVersion;
        }
    }

    private String firstNonEmptyLine(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    return line.trim().length() <= 200
                            ? line.trim() : line.trim().substring(0, 200);
                }
            }
        }
        return null;
    }

    private NormStats readNormStats(Path logFile) throws IOException {
        NormStats result = new NormStats(null, null, null, null);
        if (!Files.isRegularFile(logFile)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher extendedMatcher = EXTENDED_NORM_STATS_PATTERN.matcher(line);
                if (extendedMatcher.find()) {
                    result = new NormStats(
                            Integer.valueOf(extendedMatcher.group(1)),
                            Integer.valueOf(extendedMatcher.group(2)),
                            Integer.valueOf(extendedMatcher.group(4)),
                            Integer.valueOf(extendedMatcher.group(7))
                    );
                    continue;
                }
                Matcher matcher = NORM_STATS_PATTERN.matcher(line);
                if (matcher.find()) {
                    result = new NormStats(
                            Integer.valueOf(matcher.group(1)),
                            Integer.valueOf(matcher.group(2)),
                            Integer.valueOf(matcher.group(3)),
                            Integer.valueOf(matcher.group(4))
                    );
                }
            }
        }
        return result;
    }

    private String commandDigest(boolean aliasApplied, boolean keepAlleleDepthSum) {
        String logicalCommand = "bcftools-normalization-v1"
                + "|rename-chrs=" + aliasApplied
                + "|reheader-fai=true"
                + "|norm-check=e"
                + "|split=-any"
                + "|keep-sum-ad=" + keepAlleleDepthSum
                + "|old-rec-tag=VARVIS_ORIGINAL_VARIANT"
                + "|sort=true|index=true";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(logicalCommand.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                value.append(String.format("%02x", current & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void writeFailureManifest(Path workspace,
                                      GenomeAssembly assembly,
                                      String startedAt,
                                      long startedNanos,
                                      Exception failure) {
        try {
            NormalizationRunManifest manifest = new NormalizationRunManifest(
                    "FAILED",
                    assembly == null ? null : assembly.name(),
                    properties.getNormalization().profileIdFor(assembly),
                    properties.getNormalization().referenceSha256For(assembly),
                    "bcftools",
                    detectedBcftoolsVersion,
                    commandDigest(
                            properties.getNormalization().contigAliasFileFor(assembly) != null,
                            false
                    ),
                    properties.getNormalization().contigAliasFileFor(assembly) != null,
                    null, null, null, null, null,
                    elapsedMillis(startedNanos),
                    startedAt,
                    Instant.now().toString(),
                    failureCode(failure),
                    safeFailureReason(failure, workspace, assembly),
                    "NOT_COLLECTED_PERFORMANCE_POLICY"
            );
            NormalizationManifestWriter.write(workspace, manifest);
        } catch (IOException | RuntimeException manifestFailure) {
            LOGGER.warn(
                    "Could not write failed normalization manifest: {}",
                    manifestFailure.getClass().getSimpleName()
            );
        }
    }

    private String failureCode(Exception failure) {
        String message = rootMessage(failure).toLowerCase();
        if (message.contains("ref allele does not match")) {
            return "REFERENCE_MISMATCH";
        }
        if (message.contains("contig") || message.contains("alias")) {
            return "CONTIG_UNMAPPED";
        }
        if (message.contains("timed out")) {
            return "TOOL_TIMEOUT";
        }
        if (message.contains("could not start bcftools") || message.contains("executable")) {
            return "TOOL_UNAVAILABLE";
        }
        if (message.contains("assembly") || message.contains("reference profile")) {
            return "ASSEMBLY_UNSUPPORTED";
        }
        if (message.contains("normalized vcf")) {
            return "OUTPUT_INVALID";
        }
        return "FAILED";
    }

    private String safeFailureReason(Exception failure, Path workspace, GenomeAssembly assembly) {
        String value = rootMessage(failure);
        if (workspace != null) {
            value = value.replace(workspace.toString(), "<workspace>");
        }
        Path reference = properties.getNormalization().referenceFor(assembly);
        if (reference != null) {
            value = value.replace(reference.toString(), "<reference>");
        }
        String executable = properties.getNormalization().getBcftoolsExecutable();
        if (StringUtils.hasText(executable)) {
            value = value.replace(executable, "<bcftools>");
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        return StringUtils.hasText(message)
                ? message : current == null ? "unknown failure" : current.getClass().getSimpleName();
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private String readLogSummary(Path logFile) throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return "bcftools did not write an error message";
        }
        String lastLine = null;
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lastLine = line.trim();
                }
            }
        }
        if (lastLine == null || !StringUtils.hasText(lastLine)) {
            return "bcftools did not write an error message";
        }
        return lastLine.length() <= 500 ? lastLine : lastLine.substring(0, 500);
    }

    private String extensionOf(Path inputFile) {
        String fileName = inputFile.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".vcf.gz")) {
            return ".vcf.gz";
        }
        if (fileName.endsWith(".bcf")) {
            return ".bcf";
        }
        return ".vcf";
    }

    private static final class NormStats {
        private final Integer total;
        private final Integer split;
        private final Integer realigned;
        private final Integer skipped;

        private NormStats(Integer total, Integer split, Integer realigned, Integer skipped) {
            this.total = total;
            this.split = split;
            this.realigned = realigned;
            this.skipped = skipped;
        }
    }
}
