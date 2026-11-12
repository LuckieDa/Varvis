package org.nbscn.varvis.variantanalysis.casevalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CaseValidationReportWriter {

    private final ObjectMapper objectMapper;

    public CaseValidationReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public CaseValidationReportWriter() {
        this(new ObjectMapper());
    }

    public ReportPaths write(Path outputDirectory, CaseValidationResult result) throws IOException {
        if (outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory is required");
        }
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        Files.createDirectories(outputDirectory);
        Path json = outputDirectory.resolve("case_validation_result.json");
        Path csv = outputDirectory.resolve("case_validation_result.csv");
        Path markdown = outputDirectory.resolve("case_validation_report.md");

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), result);
        Files.write(csv, csv(result).getBytes(StandardCharsets.UTF_8));
        Files.write(markdown, markdown(result).getBytes(StandardCharsets.UTF_8));
        return new ReportPaths(json, csv, markdown);
    }

    private String csv(CaseValidationResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("caseId,assembly,inputAssembly,annotationAssembly,resourceAssembly,knowledgeAssembly,")
                .append("providerName,providerVersion,cacheVersion,liftoverApplied,liftoverStatus,")
                .append("truthType,geneSymbol,variant,rankNoHpo,rankWithHpo,rankDelta,")
                .append("rankStatusNoHpo,rankStatusWithHpo,")
                .append("hitNoHpo,hitWithHpo,phenotypeSupportPresent,phenotypeScoreContribution,")
                .append("geneAnnotationStatus,hardGateStatus,missReason,note\n");
        for (CaseValidationResult.TruthVariantResult truth : result.getTruthVariantResults()) {
            appendProvenanceCsv(builder, result)
                    .append("variant").append(',')
                    .append(csvValue(truth.getGeneSymbol())).append(',')
                    .append(csvValue(variantLabel(truth))).append(',')
                    .append(csvValue(truth.getRankNoHpo())).append(',')
                    .append(csvValue(truth.getRankWithHpo())).append(',')
                    .append(csvValue(truth.getRankDelta())).append(',')
                    .append(csvValue(truth.getRankStatusNoHpo())).append(',')
                    .append(csvValue(truth.getRankStatusWithHpo())).append(',')
                    .append(csvValue(hitSummary(truth.getHitNoHpo()))).append(',')
                    .append(csvValue(hitSummary(truth.getHitWithHpo()))).append(',')
                    .append(csvValue(truth.isPhenotypeSupportPresent())).append(',')
                    .append(csvValue(truth.getPhenotypeScoreContribution())).append(',')
                    .append(csvValue(truth.getGeneAnnotationStatus())).append(',')
                    .append(csvValue(truth.getHardGateStatus())).append(',')
                    .append(csvValue(truth.getMissReason())).append(',')
                    .append(csvValue(truth.getNote()))
                    .append('\n');
        }
        for (CaseValidationResult.TruthGeneResult truth : result.getTruthGeneResults()) {
            appendProvenanceCsv(builder, result)
                    .append("gene").append(',')
                    .append(csvValue(truth.getGeneSymbol())).append(',')
                    .append(',').append(csvValue(truth.getBestRankNoHpo())).append(',')
                    .append(csvValue(truth.getBestRankWithHpo())).append(',')
                    .append(csvValue(truth.getRankDelta())).append(',')
                    .append(csvValue(truth.getRankStatusNoHpo())).append(',')
                    .append(csvValue(truth.getRankStatusWithHpo())).append(',')
                    .append("").append(',')
                    .append("").append(',')
                    .append(csvValue(truth.isPhenotypeSupportPresent())).append(',')
                    .append("").append(',')
                    .append("").append(',')
                    .append("").append(',')
                    .append(csvValue(truth.getMissReason())).append(',')
                    .append("")
                    .append('\n');
        }
        return builder.toString();
    }

    private String markdown(CaseValidationResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("# No-HPO vs With-HPO Case Validation\n\n")
                .append("- Case ID: ").append(result.getCaseId()).append('\n')
                .append("- Assembly: ").append(result.getAssembly()).append('\n')
                .append("- Proband sample ID: ").append(nullToDash(result.getProbandSampleId())).append('\n')
                .append("- HPO terms: ").append(result.getHpoTerms()).append('\n')
                .append("- Git commit: ").append(result.getGitCommit()).append('\n')
                .append("- Input assembly: ").append(nullToDash(result.getInputAssembly())).append('\n')
                .append("- Annotation assembly: ").append(nullToDash(result.getAnnotationAssembly())).append('\n')
                .append("- Resource assembly: ").append(nullToDash(result.getResourceAssembly())).append('\n')
                .append("- Knowledge assembly: ").append(nullToDash(result.getKnowledgeAssembly())).append('\n')
                .append("- Provider: ").append(nullToDash(result.getProviderName())).append('\n')
                .append("- Provider version: ").append(nullToDash(result.getProviderVersion())).append('\n')
                .append("- Cache version: ").append(nullToDash(result.getCacheVersion())).append('\n')
                .append("- LiftOver applied: ").append(result.isLiftoverApplied()).append('\n')
                .append("- LiftOver status: ").append(nullToDash(result.getLiftoverStatus())).append('\n')
                .append("- No-HPO candidates: ").append(result.getCandidateCountNoHpo()).append('\n')
                .append("- With-HPO candidates: ").append(result.getCandidateCountWithHpo()).append('\n')
                .append("- Annotated with-HPO candidates: ")
                .append(result.getAnnotatedCandidateCountWithHpo()).append('\n')
                .append("- Cache hits / misses: ").append(result.getCacheHitCount())
                .append(" / ").append(result.getCacheMissCount()).append('\n')
                .append("- Provider batches: ").append(result.getProviderBatchCount()).append("\n\n");

        builder.append("## Truth Variant Results\n\n")
                .append("| Variant | Gene | Rank no-HPO | Rank with-HPO | Delta | Rank status | Phenotype support | Miss reason |\n")
                .append("| --- | --- | ---: | ---: | ---: | --- | --- | --- |\n");
        for (CaseValidationResult.TruthVariantResult truth : result.getTruthVariantResults()) {
            builder.append("| ").append(escapeMarkdown(variantLabel(truth)))
                    .append(" | ").append(escapeMarkdown(nullToDash(truth.getGeneSymbol())))
                    .append(" | ").append(nullToDash(truth.getRankNoHpo()))
                    .append(" | ").append(nullToDash(truth.getRankWithHpo()))
                    .append(" | ").append(nullToDash(truth.getRankDelta()))
                    .append(" | ").append(truth.getRankStatusNoHpo())
                    .append(" / ").append(truth.getRankStatusWithHpo())
                    .append(" | ").append(truth.isPhenotypeSupportPresent())
                    .append(" | ").append(nullToDash(truth.getMissReason()))
                    .append(" |\n");
        }
        builder.append("\n## Truth Gene Results\n\n")
                .append("| Gene | Best rank no-HPO | Best rank with-HPO | Delta | Phenotype support | Miss reason |\n")
                .append("| --- | ---: | ---: | ---: | --- | --- |\n");
        for (CaseValidationResult.TruthGeneResult truth : result.getTruthGeneResults()) {
            builder.append("| ").append(escapeMarkdown(nullToDash(truth.getGeneSymbol())))
                    .append(" | ").append(nullToDash(truth.getBestRankNoHpo()))
                    .append(" | ").append(nullToDash(truth.getBestRankWithHpo()))
                    .append(" | ").append(nullToDash(truth.getRankDelta()))
                    .append(" | ").append(truth.isPhenotypeSupportPresent())
                    .append(" | ").append(nullToDash(truth.getMissReason()))
                    .append(" |\n");
        }
        if (!result.getWarnings().isEmpty()) {
            builder.append("\n## Warnings\n\n");
            for (String warning : result.getWarnings()) {
                builder.append("- ").append(warning).append('\n');
            }
        }
        return builder.toString();
    }

    private StringBuilder appendProvenanceCsv(StringBuilder builder, CaseValidationResult result) {
        return builder.append(csvValue(result.getCaseId())).append(',')
                .append(csvValue(result.getAssembly())).append(',')
                .append(csvValue(result.getInputAssembly())).append(',')
                .append(csvValue(result.getAnnotationAssembly())).append(',')
                .append(csvValue(result.getResourceAssembly())).append(',')
                .append(csvValue(result.getKnowledgeAssembly())).append(',')
                .append(csvValue(result.getProviderName())).append(',')
                .append(csvValue(result.getProviderVersion())).append(',')
                .append(csvValue(result.getCacheVersion())).append(',')
                .append(csvValue(result.isLiftoverApplied())).append(',')
                .append(csvValue(result.getLiftoverStatus())).append(',');
    }

    private String hitSummary(Map<Integer, Boolean> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<Integer, Boolean> entry : values.entrySet()) {
            parts.add("@" + entry.getKey() + "=" + entry.getValue());
        }
        return String.join(";", parts);
    }

    private String variantLabel(CaseValidationResult.TruthVariantResult truth) {
        return truth.getChrom() + ":" + truth.getPos() + " " + truth.getRef() + ">" + truth.getAlt();
    }

    private String csvValue(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private String escapeMarkdown(String value) {
        return value == null ? "-" : value.replace("|", "\\|");
    }

    public static class ReportPaths {
        private final Path json;
        private final Path csv;
        private final Path markdown;

        public ReportPaths(Path json, Path csv, Path markdown) {
            this.json = json;
            this.csv = csv;
            this.markdown = markdown;
        }

        public Path getJson() {
            return json;
        }

        public Path getCsv() {
            return csv;
        }

        public Path getMarkdown() {
            return markdown;
        }
    }
}
