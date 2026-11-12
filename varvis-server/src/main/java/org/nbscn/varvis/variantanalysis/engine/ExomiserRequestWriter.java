package org.nbscn.varvis.variantanalysis.engine;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.trio.TrioInputMode;
import org.nbscn.varvis.variantanalysis.trio.TrioMember;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.nbscn.varvis.variantanalysis.trio.TrioRole;
import org.nbscn.varvis.variantanalysis.trio.TrioSex;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Writes only truth-free, per-job Exomiser request artifacts. */
final class ExomiserRequestWriter {

    private static final List<String> REQUIRED_INHERITANCE_MODES = Arrays.asList(
            "AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE_HOM_ALT", "AUTOSOMAL_RECESSIVE_COMP_HET",
            "X_DOMINANT", "X_RECESSIVE_HOM_ALT", "X_RECESSIVE_COMP_HET", "MITOCHONDRIAL"
    );
    private static final Pattern HIPHIVE_LINE = Pattern.compile("(?m)^[ \\t]*hiPhivePrioritiser:\\s*\\{\\s*\\},?\\s*\\r?\\n");

    RequestArtifacts write(VariantAnalysisJob job,
                           VariantAnalysisProperties.Exomiser settings,
                           Path workDirectory,
                           Path analysisVcf,
                           List<String> hpoTerms,
                           TrioPipelineInput trioInput) throws IOException {
        Path inputDirectory = workDirectory.resolve("input");
        Path outputDirectory = workDirectory.resolve("output");
        Files.createDirectories(inputDirectory);
        Files.createDirectories(outputDirectory);
        Path sample = inputDirectory.resolve("sample.yaml");
        Path analysis = inputDirectory.resolve("analysis.yaml");
        Path properties = inputDirectory.resolve("runtime.properties");

        List<String> cleanHpo = cleanHpoTerms(hpoTerms);
        List<String> sampleIds = sampleIds(analysisVcf);
        Path pedigree = sampleIds.size() > 1 ? inputDirectory.resolve("generated.ped") : null;
        if (pedigree != null) {
            Files.write(pedigree, (trioInput != null && trioInput.isPresent()
                    ? trioPedigree(sampleIds, sampleId(job), trioInput)
                    : generatedPedigree(sampleIds, sampleId(job))).getBytes(StandardCharsets.UTF_8));
        }
        Files.write(sample, sampleYaml(job, analysisVcf, pedigree, cleanHpo).getBytes(StandardCharsets.UTF_8));
        Files.write(analysis, AnalysisScopePolicy.forExecution(job, settings, !cleanHpo.isEmpty()).getBytes(StandardCharsets.UTF_8));
        Files.write(properties, runtimeProperties(settings).getBytes(StandardCharsets.UTF_8));
        String sampleSha256 = sha256(sample);
        String analysisSha256 = sha256(analysis);
        String runtimePropertiesSha256 = sha256(properties);
        String pedigreeSha256 = pedigree == null ? "" : sha256(pedigree);
        String requestSha256 = sha256((sampleSha256 + "\n" + analysisSha256 + "\n" + runtimePropertiesSha256
                + "\n" + pedigreeSha256)
                .getBytes(StandardCharsets.UTF_8));
        var policy = AnalysisScopePolicy.read(job);
        if (policy != null && policy.supplementsZeroPassed())
            requestSha256 = sha256((requestSha256 + "\n" + policy.policySha256() + "\n" + policy.outputCoveragePolicy()).getBytes(StandardCharsets.UTF_8));
        return new RequestArtifacts(sample, analysis, properties, pedigree, outputDirectory, requestSha256, pedigreeSha256);
    }

    private String sampleYaml(VariantAnalysisJob job,
                              Path analysisVcf,
                              Path pedigree,
                              List<String> hpoTerms) {
        String sampleId = sampleId(job);
        List<String> lines = new ArrayList<String>();
        lines.add("# Varvis Exomiser request.");
        lines.add("genomeAssembly: GRCh38");
        if (analysisVcf == null) {
            throw new IllegalArgumentException("EXOMISER_ANALYSIS_VCF_MISSING");
        }
        lines.add("vcf: " + yamlScalar(analysisVcf.toAbsolutePath().toString().replace('\\', '/')));
        if (pedigree != null) {
            lines.add("ped: " + yamlScalar(pedigree.toAbsolutePath().toString().replace('\\', '/')));
        }
        lines.add("proband: " + yamlScalar(sampleId));
        lines.add("hpoIds:");
        if (hpoTerms.isEmpty()) {
            lines.add("  []");
        } else {
            for (String hpo : hpoTerms) {
                lines.add("  - " + hpo);
            }
        }
        return join(lines);
    }

    private List<String> sampleIds(Path vcf) throws IOException {
        if (vcf == null || !Files.isRegularFile(vcf) || !Files.isReadable(vcf)) {
            throw new IllegalArgumentException("EXOMISER_ANALYSIS_VCF_MISSING");
        }
        try (InputStream raw = Files.newInputStream(vcf);
             InputStream source = vcf.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")
                     ? new GZIPInputStream(raw) : raw;
             BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#CHROM")) {
                    continue;
                }
                String[] columns = line.split("\\t", -1);
                if (columns.length <= 9) {
                    return Collections.emptyList();
                }
                List<String> values = new ArrayList<String>();
                for (int index = 9; index < columns.length; index++) {
                    String value = trimToNull(columns[index]);
                    if (value != null) {
                        values.add(value);
                    }
                }
                return values;
            }
        }
        throw new IllegalArgumentException("EXOMISER_VCF_HEADER_CHROM_LINE_MISSING");
    }

    private String generatedPedigree(List<String> sampleIds, String selectedSampleId) {
        if (!sampleIds.contains(selectedSampleId)) {
            throw new IllegalArgumentException("EXOMISER_SAMPLE_ID_NOT_FOUND_IN_ANALYSIS_VCF");
        }
        List<String> lines = new ArrayList<String>();
        for (String id : sampleIds) {
            // Do not infer family relationships, sex, or affected status from VCF headers.
            lines.add("VARVIS\t" + id + "\t0\t0\t0\t" + (id.equals(selectedSampleId) ? "2" : "-9"));
        }
        return join(lines);
    }

    private String trioPedigree(List<String> sampleIds,
                                String selectedSampleId,
                                TrioPipelineInput input) {
        if (input.getInputMode() != TrioInputMode.JOINT_VCF) {
            throw new IllegalArgumentException("EXOMISER_TRIO_JOINT_VCF_REQUIRED");
        }
        TrioMember proband = member(input, TrioRole.PROBAND);
        TrioMember father = member(input, TrioRole.FATHER);
        TrioMember mother = member(input, TrioRole.MOTHER);
        if (proband == null || father == null || mother == null
                || !selectedSampleId.equals(proband.getSampleId())
                || !sampleIds.contains(proband.getSampleId())
                || !sampleIds.contains(father.getSampleId())
                || !sampleIds.contains(mother.getSampleId())
                || proband.getSampleId().equals(father.getSampleId())
                || proband.getSampleId().equals(mother.getSampleId())
                || father.getSampleId().equals(mother.getSampleId())) {
            throw new IllegalArgumentException("EXOMISER_TRIO_SAMPLE_MAPPING_INVALID");
        }
        if (proband.getSex() == TrioSex.UNKNOWN || father.getSex() != TrioSex.MALE
                || mother.getSex() != TrioSex.FEMALE) {
            throw new IllegalArgumentException("EXOMISER_TRIO_PEDIGREE_SEX_INVALID");
        }
        List<String> lines = new ArrayList<String>();
        lines.add(pedigreeLine(proband, father.getSampleId(), mother.getSampleId()));
        lines.add(pedigreeLine(father, "0", "0"));
        lines.add(pedigreeLine(mother, "0", "0"));
        return join(lines);
    }

    private TrioMember member(TrioPipelineInput input, TrioRole role) {
        for (TrioMember member : input.getMembers()) {
            if (member != null && member.getRole() == role) {
                return member;
            }
        }
        return null;
    }

    private String pedigreeLine(TrioMember member, String father, String mother) {
        return "VARVIS\t" + member.getSampleId() + "\t" + father + "\t" + mother
                + "\t" + member.getSex().pedCode() + "\t" + (member.isAffected() ? "2" : "1");
    }

    private String sampleId(VariantAnalysisJob job) {
        String sampleId = trimToNull(job.getSelectedSampleId());
        if (sampleId == null) {
            sampleId = trimToNull(job.getRequestedSampleId());
        }
        if (sampleId == null) {
            throw new IllegalArgumentException("EXOMISER_SAMPLE_ID_MISSING");
        }
        return sampleId;
    }

    static String analysisYaml(String templateLocation, boolean includeHiPhive) throws IOException {
        String configured = trimToNull(templateLocation);
        if (configured == null) {
            throw new IllegalArgumentException("EXOMISER_ANALYSIS_TEMPLATE_MISSING");
        }
        Path template = Paths.get(configured);
        if (!Files.isRegularFile(template) || !Files.isReadable(template)) {
            throw new IllegalArgumentException("EXOMISER_ANALYSIS_TEMPLATE_UNREADABLE");
        }
        String text = new String(Files.readAllBytes(template), StandardCharsets.UTF_8);
        for (String mode : REQUIRED_INHERITANCE_MODES) {
            if (!text.contains(mode + ":")) {
                throw new IllegalArgumentException("EXOMISER_INHERITANCE_MODE_MISSING:" + mode);
            }
        }
        if (includeHiPhive && !text.contains("hiPhivePrioritiser:")) {
            throw new IllegalArgumentException("EXOMISER_G1_HIPHIVE_MISSING");
        }
        if (!includeHiPhive) {
            text = HIPHIVE_LINE.matcher(text).replaceAll("");
            if (text.contains("hiPhivePrioritiser:")) {
                throw new IllegalArgumentException("EXOMISER_G0_HIPHIVE_DISABLE_FAILED");
            }
        }
        return "# Varvis Exomiser analysis configuration.\n"
                + "# Java 21; Exomiser 15.1.0; GRCh38/phenotype 2602.\n" + text;
    }

    private String runtimeProperties(VariantAnalysisProperties.Exomiser settings) {
        return "exomiser.data-directory=" + normalizedPath(settings.getDataDirectory()) + "\n"
                + "exomiser.hg38.data-version=" + settings.getDataVersion() + "\n"
                + "exomiser.phenotype.data-version=" + settings.getDataVersion() + "\n";
    }

    private List<String> cleanHpoTerms(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> clean = new ArrayList<String>();
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized == null || !normalized.matches("HP:\\d{7}")) {
                throw new IllegalArgumentException("EXOMISER_HPO_TERM_INVALID");
            }
            if (!clean.contains(normalized)) {
                clean.add(normalized);
            }
        }
        return clean;
    }

    private String yamlScalar(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String normalizedPath(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private String join(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    private String sha256(byte[] input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(input));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    static final class RequestArtifacts {
        private final Path sample;
        private final Path analysis;
        private final Path runtimeProperties;
        private final Path pedigree;
        private final Path outputDirectory;
        private final String requestSha256;
        private final String pedigreeSha256;

        RequestArtifacts(Path sample, Path analysis, Path runtimeProperties, Path pedigree, Path outputDirectory,
                         String requestSha256,
                         String pedigreeSha256) {
            this.sample = sample;
            this.analysis = analysis;
            this.runtimeProperties = runtimeProperties;
            this.pedigree = pedigree;
            this.outputDirectory = outputDirectory;
            this.requestSha256 = requestSha256;
            this.pedigreeSha256 = pedigreeSha256;
        }

        Path getSample() { return sample; }
        Path getAnalysis() { return analysis; }
        Path getRuntimeProperties() { return runtimeProperties; }
        Path getPedigree() { return pedigree; }
        Path getOutputDirectory() { return outputDirectory; }
        String requestSha256() { return requestSha256; }
        String pedigreeSha256() { return pedigreeSha256; }
    }
}
