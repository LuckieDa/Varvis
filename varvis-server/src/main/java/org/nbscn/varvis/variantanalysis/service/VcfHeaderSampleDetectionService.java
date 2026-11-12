package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.dto.VcfSampleDetectionResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

@Service
public class VcfHeaderSampleDetectionService {

    public static final String FILE_TYPE_VCF = "VCF";
    public static final String FILE_TYPE_VCF_GZ = "VCF_GZ";
    public static final String FILE_TYPE_BCF = "BCF";
    public static final String FILE_TYPE_UNKNOWN = "UNKNOWN";

    private static final int MAX_HEADER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_HEADER_LINES = 50000;
    private static final int MAX_CONTIGS = 64;

    public VcfSampleDetectionResult detect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return VcfSampleDetectionResult.error(
                    FILE_TYPE_UNKNOWN,
                    "VCF_HEADER_FILE_EMPTY",
                    "VCF file is empty."
            );
        }
        String fileType = fileType(file.getOriginalFilename());
        try (InputStream inputStream = file.getInputStream()) {
            return detect(inputStream, file.getOriginalFilename(), fileType);
        } catch (IOException ex) {
            return VcfSampleDetectionResult.error(
                    fileType,
                    "VCF_HEADER_READ_FAILED",
                    "VCF header could not be read."
            );
        }
    }

    public VcfSampleDetectionResult detect(InputStream source, String originalFileName) {
        return detect(source, originalFileName, fileType(originalFileName));
    }

    private VcfSampleDetectionResult detect(InputStream source,
                                            String originalFileName,
                                            String fileType) {
        if (source == null) {
            return VcfSampleDetectionResult.error(
                    fileType,
                    "VCF_HEADER_FILE_EMPTY",
                    "VCF file is empty."
            );
        }
        if (FILE_TYPE_BCF.equals(fileType)) {
            return VcfSampleDetectionResult.error(
                    fileType,
                    "VCF_HEADER_BCF_UNSUPPORTED",
                    "BCF sample detection is not supported by the lightweight header endpoint."
            );
        }
        if (FILE_TYPE_UNKNOWN.equals(fileType)) {
            return VcfSampleDetectionResult.error(
                    fileType,
                    "VCF_HEADER_FILE_TYPE_UNSUPPORTED",
                    "Only .vcf and .vcf.gz files are supported for sample detection."
            );
        }

        try {
            InputStream limited = new HeaderLimitedInputStream(source, MAX_HEADER_BYTES);
            InputStream headerStream = FILE_TYPE_VCF_GZ.equals(fileType)
                    ? new GZIPInputStream(limited)
                    : limited;
            return readHeader(headerStream, fileType);
        } catch (HeaderLimitExceededException ex) {
            return VcfSampleDetectionResult.error(
                    fileType,
                    "VCF_HEADER_TOO_LARGE",
                    "VCF header exceeds the lightweight detection limit."
            );
        } catch (IOException ex) {
            return VcfSampleDetectionResult.error(
                    fileType,
                    "VCF_HEADER_READ_FAILED",
                    "VCF header could not be read."
            );
        }
    }

    private VcfSampleDetectionResult readHeader(InputStream headerStream, String fileType)
            throws IOException {
        List<String> contigs = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        AssemblyEvidence assemblyEvidence = new AssemblyEvidence();
        ContigStyleEvidence contigStyleEvidence = new ContigStyleEvidence();
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(headerStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (lineCount > MAX_HEADER_LINES) {
                    return VcfSampleDetectionResult.error(
                            fileType,
                            "VCF_HEADER_TOO_LARGE",
                            "VCF header exceeds the lightweight detection line limit."
                    );
                }
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                if (line.startsWith("##contig=")) {
                    addContig(contigs, line);
                    assemblyEvidence.observeDeclared(assemblyFromHeaderLine(line));
                    assemblyEvidence.observeContig(
                            attributeValue(line, "ID"),
                            attributeValue(line, "length")
                    );
                    contigStyleEvidence.observe(attributeValue(line, "ID"));
                    continue;
                }
                if (line.startsWith("##reference=")) {
                    assemblyEvidence.observeDeclared(assemblyFromHeaderLine(line));
                    continue;
                }
                if (line.startsWith("#CHROM")) {
                    String[] columns = line.split("\t", -1);
                    if (columns.length < 8) {
                        return VcfSampleDetectionResult.error(
                                fileType,
                                "VCF_HEADER_MALFORMED",
                                "VCF #CHROM header line has too few columns."
                        );
                    }
                    List<String> samples = new ArrayList<String>();
                    if (columns.length > 9) {
                        for (int i = 9; i < columns.length; i++) {
                            if (StringUtils.hasText(columns[i])) {
                                samples.add(columns[i].trim());
                            }
                        }
                    }
                    if (samples.isEmpty()) {
                        warnings.add("VCF header has no sample columns.");
                    }
                    assemblyEvidence.appendWarnings(warnings);
                    contigStyleEvidence.appendWarnings(warnings);
                    return VcfSampleDetectionResult.success(
                            samples,
                            contigs,
                            assemblyEvidence.getAssemblyHint(),
                            assemblyEvidence.getConfidence(),
                            assemblyEvidence.requiresConfirmation(),
                            contigStyleEvidence.getStyle(),
                            indexPreparation(fileType),
                            fileType,
                            warnings
                    );
                }
                if (!line.startsWith("#")) {
                    return VcfSampleDetectionResult.error(
                            fileType,
                            "VCF_HEADER_MALFORMED",
                            "VCF data line appeared before the #CHROM header line."
                    );
                }
            }
        }
        return VcfSampleDetectionResult.error(
                fileType,
                "VCF_HEADER_CHROM_LINE_MISSING",
                "VCF #CHROM header line was not found."
        );
    }

    private void addContig(List<String> contigs, String line) {
        if (contigs.size() >= MAX_CONTIGS) {
            return;
        }
        String id = attributeValue(line, "ID");
        if (StringUtils.hasText(id)) {
            contigs.add(id.trim());
        }
    }

    private String assemblyFromHeaderLine(String line) {
        String normalized = String.valueOf(line).toLowerCase(Locale.ROOT);
        if (normalized.contains("grch38") || normalized.contains("hg38")) {
            return "GRCh38";
        }
        if (normalized.contains("grch37") || normalized.contains("hg19")) {
            return "GRCh37";
        }
        String assembly = attributeValue(line, "assembly");
        if (StringUtils.hasText(assembly)) {
            String lower = assembly.toLowerCase(Locale.ROOT);
            if (lower.contains("38")) {
                return "GRCh38";
            }
            if (lower.contains("37") || lower.contains("19")) {
                return "GRCh37";
            }
        }
        return null;
    }

    private String indexPreparation(String fileType) {
        if (FILE_TYPE_VCF_GZ.equals(fileType)) {
            return "PRIVATE_BGZF_TABIX";
        }
        if (FILE_TYPE_VCF.equals(fileType)) {
            return "NOT_REQUIRED";
        }
        return "UNSUPPORTED";
    }

    private String attributeValue(String line, String attribute) {
        if (!StringUtils.hasText(line) || !StringUtils.hasText(attribute)) {
            return null;
        }
        String token = attribute + "=";
        int start = line.indexOf(token);
        if (start < 0) {
            return null;
        }
        int valueStart = start + token.length();
        int valueEnd = line.indexOf(',', valueStart);
        int angleEnd = line.indexOf('>', valueStart);
        if (valueEnd < 0 || (angleEnd >= 0 && angleEnd < valueEnd)) {
            valueEnd = angleEnd;
        }
        if (valueEnd < 0) {
            valueEnd = line.length();
        }
        return line.substring(valueStart, valueEnd).replace("\"", "").trim();
    }

    private String fileType(String originalFileName) {
        String name = String.valueOf(originalFileName).toLowerCase(Locale.ROOT);
        if (name.endsWith(".vcf.gz")) {
            return FILE_TYPE_VCF_GZ;
        }
        if (name.endsWith(".vcf")) {
            return FILE_TYPE_VCF;
        }
        if (name.endsWith(".bcf")) {
            return FILE_TYPE_BCF;
        }
        return FILE_TYPE_UNKNOWN;
    }

    private static class HeaderLimitedInputStream extends FilterInputStream {

        private final long maxBytes;
        private long bytesRead;

        private HeaderLimitedInputStream(InputStream source, long maxBytes) {
            super(source);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(int count) {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new HeaderLimitExceededException();
            }
        }
    }

    private static class HeaderLimitExceededException extends RuntimeException {
    }

    /**
     * Accept only independent, header-level build evidence. Unknown or conflicting
     * evidence deliberately remains a confirmation gate instead of becoming a
     * guessed genome assembly.
     */
    private static class AssemblyEvidence {

        private String declaredAssembly;
        private String contigAssembly;
        private boolean conflicting;

        private void observeDeclared(String candidate) {
            declaredAssembly = merge(declaredAssembly, candidate);
        }

        private void observeContig(String contig, String length) {
            String candidate = assemblyForContig(contig, length);
            contigAssembly = merge(contigAssembly, candidate);
        }

        private String merge(String current, String candidate) {
            if (!StringUtils.hasText(candidate)) {
                return current;
            }
            if (!StringUtils.hasText(current)) {
                return candidate;
            }
            if (!current.equals(candidate)) {
                conflicting = true;
            }
            return current;
        }

        private String getAssemblyHint() {
            if (isConflicting()) {
                return null;
            }
            return StringUtils.hasText(declaredAssembly) ? declaredAssembly : contigAssembly;
        }

        private String getConfidence() {
            if (isConflicting()) {
                return "CONFLICTING_EVIDENCE";
            }
            if (StringUtils.hasText(declaredAssembly)) {
                return "EXPLICIT_HEADER";
            }
            if (StringUtils.hasText(contigAssembly)) {
                return "CONTIG_SIGNATURE";
            }
            return "UNKNOWN";
        }

        private boolean requiresConfirmation() {
            return !StringUtils.hasText(getAssemblyHint());
        }

        private boolean isConflicting() {
            return conflicting || (StringUtils.hasText(declaredAssembly)
                    && StringUtils.hasText(contigAssembly)
                    && !declaredAssembly.equals(contigAssembly));
        }

        private void appendWarnings(List<String> warnings) {
            if (isConflicting()) {
                warnings.add("VCF header has conflicting GRCh37/GRCh38 assembly evidence; confirm the input build before analysis.");
                return;
            }
            if (!StringUtils.hasText(getAssemblyHint())) {
                warnings.add("Genome assembly could not be determined from the VCF header; select and confirm GRCh37 or GRCh38 before analysis.");
            }
        }

        private String assemblyForContig(String contig, String length) {
            if (!StringUtils.hasText(contig) || !StringUtils.hasText(length)) {
                return null;
            }
            String normalizedContig = contig.trim().toLowerCase(Locale.ROOT);
            String normalizedLength = length.trim();
            if (("1".equals(normalizedContig) || "chr1".equals(normalizedContig))) {
                if ("249250621".equals(normalizedLength)) {
                    return "GRCh37";
                }
                if ("248956422".equals(normalizedLength)) {
                    return "GRCh38";
                }
            }
            if (("x".equals(normalizedContig) || "chrx".equals(normalizedContig))) {
                if ("155270560".equals(normalizedLength)) {
                    return "GRCh37";
                }
                if ("156040895".equals(normalizedLength)) {
                    return "GRCh38";
                }
            }
            return null;
        }
    }

    private static class ContigStyleEvidence {

        private boolean ucsc;
        private boolean numeric;

        private void observe(String contig) {
            if (!StringUtils.hasText(contig)) {
                return;
            }
            String normalized = contig.trim();
            if (normalized.toLowerCase(Locale.ROOT).startsWith("chr")) {
                ucsc = true;
            } else if (normalized.matches("(?:[0-9]+|X|Y|MT|M)")) {
                numeric = true;
            }
        }

        private String getStyle() {
            if (ucsc && numeric) {
                return "MIXED";
            }
            if (ucsc) {
                return "UCSC_CHR";
            }
            if (numeric) {
                return "NUMERIC";
            }
            return "UNKNOWN";
        }

        private void appendWarnings(List<String> warnings) {
            if ("MIXED".equals(getStyle())) {
                warnings.add("VCF header mixes chr-prefixed and numeric canonical contig names; compatibility will be validated before analysis.");
            }
        }
    }
}
