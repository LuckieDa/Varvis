package org.nbscn.varvis.variantanalysis.engine;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Real request/parser/AnalysisVcfCall code, synthetic TSV runner. Never starts an Exomiser process. */
public final class ParsedCandidateFixture {
    private ParsedCandidateFixture() { }

    public static Run run(Path root, String analysisId, GenomeAssembly inputAssembly,
                            String vcf, String variantTsv) throws Exception {
        return run(root, analysisId, inputAssembly, vcf, variantTsv, null, false);
    }

    public static Run run(Path root, String analysisId, GenomeAssembly inputAssembly,
                           String vcf, String variantTsv, String declaredUploadSha, boolean changeInputDuringExecution) throws Exception {
        var prepared = prepare(root, analysisId, inputAssembly, vcf, variantTsv, declaredUploadSha, changeInputDuringExecution);
        return new Run(prepared.job(), prepared.conversion(), prepared.adapter().prepare(prepared.job(), prepared.conversion().getOutputFile(), GenomeAssembly.GRCh38));
    }

    public static Prepared prepare(Path root, String analysisId, GenomeAssembly inputAssembly,
                           String vcf, String variantTsv, String declaredUploadSha, boolean changeInputDuringExecution) throws Exception {
        return prepare(root,analysisId,inputAssembly,vcf,variantTsv,declaredUploadSha,changeInputDuringExecution,null,null);
    }

    public static Prepared prepare(Path root, String analysisId, GenomeAssembly inputAssembly, String vcf,
            String variantTsv, String declaredUploadSha, boolean changeInputDuringExecution,
            Path companionDirectory, AnalysisScopePolicy.Policy policy) throws Exception {
        Files.createDirectories(root);
        Path uploaded = root.resolve("input.vcf");
        Files.writeString(uploaded, vcf, StandardCharsets.UTF_8);
        Path analysisVcf = uploaded;
        if (inputAssembly == GenomeAssembly.GRCh37) {
            analysisVcf = root.resolve("synthetic-lifted.vcf");
            Files.writeString(analysisVcf, vcf, StandardCharsets.UTF_8);
        }
        VariantAnalysisJob job = new VariantAnalysisJob(analysisId, "synthetic-input.vcf", declaredUploadSha == null ? sha(Files.readAllBytes(uploaded)) : declaredUploadSha,
                inputAssembly, AnalysisMode.WES, "synthetic-proband", root, uploaded);
        if (policy != null) AnalysisScopePolicy.save(job,policy);
        Path template = root.resolve("synthetic-analysis.yml");
        Files.writeString(template, """
                inheritanceModes: {
                  AUTOSOMAL_DOMINANT: 0.1, AUTOSOMAL_RECESSIVE_HOM_ALT: 0.1,
                  AUTOSOMAL_RECESSIVE_COMP_HET: 2.0, X_DOMINANT: 0.1,
                  X_RECESSIVE_HOM_ALT: 0.1, X_RECESSIVE_COMP_HET: 2.0, MITOCHONDRIAL: 0.2
                }
                hiPhivePrioritiser: {}
                """);
        var properties = new VariantAnalysisProperties();
        properties.getExomiser().setEnabled(true);
        properties.getExomiser().setJavaExecutable("SYNTHETIC_RUNNER_NO_JAVA_PROCESS");
        properties.getExomiser().setCliJar(root.resolve("synthetic-not-executed.jar").toString());
        properties.getExomiser().setDataDirectory(root.toString());
        properties.getExomiser().setAnalysisTemplate(template.toString());
        properties.getExomiser().setResourceFingerprint(sha("SYNTHETIC_RUNNER".getBytes(StandardCharsets.UTF_8)));
        properties.getExomiser().setTimeout(Duration.ofSeconds(5));
        ExomiserRuntimeValidator validator = new ExomiserRuntimeValidator() {
            @Override public ExomiserRuntimeReadiness validate(VariantAnalysisProperties.Exomiser settings) {
                return new ExomiserRuntimeReadiness(true, true, "SYNTHETIC_TEST_RUNNER", "TEST", "No real Exomiser execution", List.of());
            }
        };
        var adapter = new ConfiguredExomiserEngineAdapter(properties, (command, work, stdout, stderr, environment, timeout) -> {
            if (changeInputDuringExecution) Files.writeString(uploaded,
                    vcf + "chr11\t4\tchanged-during-synthetic-runner\tG\tA\t.\tPASS\t.\tGT\t.\n", StandardCharsets.UTF_8);
            Files.writeString(work.resolve("output/result.variants.tsv"), variantTsv, StandardCharsets.UTF_8);
            if (companionDirectory != null) for (String name : List.of("result.jsonl","result.genes.tsv"))
                Files.copy(companionDirectory.resolve(name),work.resolve("output").resolve(name));
            return new ExomiserProcessResult(0, false);
        }, validator);
        var conversion = new BuildConversionResult(analysisVcf, inputAssembly == GenomeAssembly.GRCh37,
                inputAssembly == GenomeAssembly.GRCh37 ? "SYNTHETIC_LIFTOVER" : "NONE", List.of());
        return new Prepared(job, conversion, adapter);
    }

    public static String vcf(String... records) {
        return "##fileformat=VCFv4.2\n##reference=GRCh38\n"
                + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Synthetic genotype\">\n"
                + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n"
                + String.join("\n", records) + "\n";
    }

    public static String tsv(String... rows) {
        return "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\tEXOMISER_VARIANT_SCORE\n"
                + String.join("\n", rows) + "\n";
    }

    public static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public record Run(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution) { }
    public record Prepared(VariantAnalysisJob job, BuildConversionResult conversion, ConfiguredExomiserEngineAdapter adapter) { }
}
