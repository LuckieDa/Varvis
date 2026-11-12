package org.nbscn.varvis.variantanalysis.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.trio.TrioMember;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.nbscn.varvis.variantanalysis.trio.TrioRole;
import org.nbscn.varvis.variantanalysis.trio.TrioSex;
import org.nbscn.varvis.variantanalysis.trio.TrioVcfSampleMapping;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredExomiserEngineAdapterTest {

    private static final String SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @TempDir
    Path temporary;

    @Test
    void remainsDisabledAndReturnsStructuredUnavailableWithoutStartingRuntime() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        ConfiguredExomiserEngineAdapter adapter = adapter(properties, failingRunner());

        EngineExecutionResult result = adapter.prepare(null);

        assertEquals(AnalysisEngine.EXOMISER, result.getMetadata().getEngine());
        assertEquals(EngineExecutionStatus.UNAVAILABLE, result.getMetadata().getStatus());
        assertEquals("EXOMISER_DISABLED", result.getMetadata().getUnavailableReason());
        assertEquals("15.1.0", result.getMetadata().getEngineVersion());
        assertEquals("2602", properties.getExomiser().getDataVersion());
        assertFalse(properties.getExomiser().isEnabled());
    }

    @Test
    void rejectsInvalidRuntimeConfigurationWithoutStartingProcess() throws Exception {
        VariantAnalysisProperties properties = configured();
        properties.getExomiser().setJavaExecutable(temporary.resolve("missing/java.exe").toString());
        ConfiguredExomiserEngineAdapter adapter = adapter(properties, failingRunner());

        EngineExecutionResult result = adapter.prepare(job("invalid-runtime", PhenotypeAwareSummary.disabled()));

        assertEquals(EngineExecutionStatus.UNAVAILABLE, result.getMetadata().getStatus());
        assertEquals("EXOMISER_JAVA21_WINDOWS_UNAVAILABLE", result.getMetadata().getUnavailableReason());
        assertFalse(result.getMetadata().isFallbackUsed());
    }

    @Test
    void writesTruthFreeG0AndG1RequestsUsesConfiguredJavaAndParsesTsv() throws Exception {
        RecordingRunner runner = new RecordingRunner(0, false, OutputMode.VALID);
        VariantAnalysisProperties properties = configured();
        ConfiguredExomiserEngineAdapter adapter = adapter(properties, runner);

        EngineExecutionResult g0 = adapter.prepare(job("g0", PhenotypeAwareSummary.disabled()));
        EngineExecutionResult g1 = adapter.prepare(job("g1", summary("HP:0001250", "HP:0001290")));

        assertEquals(EngineExecutionStatus.RANKED, g0.getMetadata().getStatus());
        assertEquals(EngineExecutionStatus.RANKED, g1.getMetadata().getStatus());
        assertEquals("EXOMISER_G0", g0.getMetadata().getRankingMode());
        assertEquals("EXOMISER_G1", g1.getMetadata().getRankingMode());
        assertEquals(1, g1.getInterpretations().size());
        assertEquals("GENE1", g1.getInterpretations().get(0).getEvidence().get(0).getDetails().get("geneSymbol"));
        assertEquals(Double.valueOf(0.91d), g1.getInterpretations().get(0).getRankingScore());
        assertEquals("REVIEW", g1.getInterpretations().get(0).getPriorityTier());
        assertEquals("SNV", g1.getInterpretations().get(0).getObservation().getVariantType());
        assertFalse("EXOMISER".equals(g1.getInterpretations().get(0).getPriorityTier()));
        assertFalse(g1.getMetadata().isFallbackUsed());
        assertTrue(g1.getMetadata().getOutputSha256().matches("[0-9a-f]{64}"));
        assertTrue(g1.getRequestSha256().matches("[0-9a-f]{64}"));
        assertNotEquals(g0.getWorkDirectory(), g1.getWorkDirectory());
        assertNotNull(g1.getCommandSha256());
        assertTrue(runner.command.contains("analyse"));
        assertEquals(properties.getExomiser().getJavaExecutable(), runner.command.get(0));
        assertEquals("-Xmx4g", runner.command.get(1));
        assertEquals("-jar", runner.command.get(2));

        String g0Sample = text(g0.getWorkDirectory().resolve("input/sample.yaml"));
        String g0Analysis = text(g0.getWorkDirectory().resolve("input/analysis.yaml"));
        String g1Sample = text(g1.getWorkDirectory().resolve("input/sample.yaml"));
        String g1Analysis = text(g1.getWorkDirectory().resolve("input/analysis.yaml"));
        assertTrue(g0Sample.contains("hpoIds:\n  []"));
        assertFalse(g0Analysis.contains("hiPhivePrioritiser:"));
        assertTrue(g1Sample.contains("HP:0001250"));
        assertTrue(g1Analysis.contains("hiPhivePrioritiser:"));
        assertFalse(g1Sample.toLowerCase().contains("truth"));
        assertFalse(g1Analysis.toLowerCase().contains("truth"));
    }

    @Test
    void hydratesTheEngineCandidateFromTheExactAnalysisVcfAlleleWithoutRescoring() throws Exception {
        VariantAnalysisJob analysisJob = job(
                "analysis-vcf-call",
                PhenotypeAwareSummary.disabled()
        );
        Files.write(analysisJob.getUploadedFile(), Arrays.asList(
                "##fileformat=VCFv4.2",
                "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">",
                "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">",
                "##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">",
                "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allele depths\">",
                "##FORMAT=<ID=PS,Number=1,Type=Integer,Description=\"Phase set\">",
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1",
                "1\t123\trs1\tA\tT\t.\tPASS\t.\tGT:DP:GQ:AD:PS\t0|1:30:99:16,14:42"
        ), StandardCharsets.UTF_8);

        EngineExecutionResult result = adapter(
                configured(),
                new RecordingRunner(0, false, OutputMode.VALID)
        ).prepare(analysisJob);

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus());
        AnalysisVcfCall call = result.getInterpretations().get(0).getAnalysisVcfCall();
        assertNotNull(call);
        assertEquals(AnalysisVcfCall.MatchStatus.MATCHED, call.getMatchStatus());
        assertEquals(AnalysisVcfCall.TargetAlleleCall.HET_TARGET, call.getTargetAlleleCall());
        assertEquals("REF|TARGET_ALT", call.getCandidateRelativeGenotype());
        assertEquals(Integer.valueOf(1), call.getTargetAlleleIndex());
        assertEquals(Integer.valueOf(30), call.getDepth());
        assertEquals(Integer.valueOf(99), call.getGenotypeQuality());
        assertEquals(Integer.valueOf(16), call.getReferenceDepth());
        assertEquals(Integer.valueOf(14), call.getTargetAlleleDepth());
        assertEquals(Double.valueOf(14.0d / 30.0d), call.getAlleleBalance());
        assertEquals(AnalysisVcfCall.FilterEvaluation.PASS, call.getFilterEvaluation());
        assertEquals(AnalysisVcfCall.PhasingStatus.PHASE_SET, call.getPhasingStatus());
        assertNotNull(call.getPhaseSetToken());
        assertTrue(call.getPhaseSetToken().matches("[0-9a-f]{24}"));
        assertNotEquals("42", call.getPhaseSetToken());
        assertEquals(Double.valueOf(0.91d), result.getInterpretations().get(0).getRankingScore());
    }

    @Test
    void reportsAStableFailureWhenTheAnalysisVcfBecomesUnreadableBeforeCallHydration() throws Exception {
        VariantAnalysisJob analysisJob = job(
                "analysis-vcf-unreadable-during-hydration",
                PhenotypeAwareSummary.disabled()
        );
        RecordingRunner runner = new RecordingRunner(0, false, OutputMode.VALID) {
            @Override
            public ExomiserProcessResult run(List<String> command,
                                             Path work,
                                             Path stdout,
                                             Path stderr,
                                             Map<String, String> environment,
                                             Duration timeout) throws IOException {
                ExomiserProcessResult result = super.run(
                        command, work, stdout, stderr, environment, timeout
                );
                Files.write(
                        analysisJob.getUploadedFile(),
                        Collections.singletonList("not-a-vcf"),
                        StandardCharsets.UTF_8
                );
                return result;
            }
        };

        EngineExecutionResult result = adapter(configured(), runner).prepare(analysisJob);

        assertEquals(EngineExecutionStatus.FAILED, result.getMetadata().getStatus());
        assertEquals("ANALYSIS_VCF_UNREADABLE", result.getMetadata().getUnavailableReason());
        assertEquals("ANALYSIS_VCF_UNREADABLE", result.getFailureCode());
    }

    @Test
    void reportsNonzeroExitTimeoutAndOutputFailuresWithoutFallback() throws Exception {
        assertFailure(new RecordingRunner(9, false, OutputMode.NONE), "EXOMISER_NONZERO_EXIT", 9);
        assertFailure(new RecordingRunner(-1, true, OutputMode.NONE), "EXOMISER_TIMEOUT", -1);
        assertFailure(new RecordingRunner(0, false, OutputMode.NONE), "EXOMISER_OUTPUT_VARIANTS_MISSING", null);
        assertFailure(new RecordingRunner(0, false, OutputMode.MALFORMED), "EXOMISER_OUTPUT_PARSE_FAILED", null);
    }

    @Test
    void acceptsACompletelyMissingOptionalGeneArtifact() throws Exception {
        EngineExecutionResult result = adapter(configured(),
                new RecordingRunner(0, false, OutputMode.NO_GENES))
                .prepare(job("no-gene-artifact", PhenotypeAwareSummary.disabled()));

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus());
        assertEquals(1, result.getInterpretations().size());
        assertEquals("GENE_ARTIFACT_MISSING", result.getInterpretations().get(0)
                .getExomiserCandidateFeatures().getGeneRowMatchStrategy());
        assertTrue(result.getMetadata().getOutputSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsMalformedOptionalGeneArtifactWithoutLeakingItsCell() throws Exception {
        EngineExecutionResult result = adapter(configured(),
                new RecordingRunner(0, false, OutputMode.MALFORMED_GENE))
                .prepare(job("malformed-gene", PhenotypeAwareSummary.disabled()));

        assertEquals(EngineExecutionStatus.FAILED, result.getMetadata().getStatus());
        assertEquals("EXOMISER_OUTPUT_PARSE_FAILED", result.getFailureCode());
        assertEquals("EXOMISER_OUTPUT_PARSE_FAILED", result.getMetadata().getUnavailableReason());
        assertFalse(result.getFailureCode().contains("PRIVATE_GENE_CANARY_7f30"));
    }

    @Test
    void acceptsExomiserTrailingOptionalTsvColumns() throws Exception {
        RecordingRunner runner = new RecordingRunner(0, false, OutputMode.TRAILING_OPTIONAL_GENE_COLUMNS);
        EngineExecutionResult result = adapter(configured(), runner)
                .prepare(job("trailing-optional-columns", PhenotypeAwareSummary.disabled()));

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus());
        assertEquals(1, result.getInterpretations().size());
        assertFalse(result.getMetadata().isFallbackUsed());
    }

    @Test
    void parsesAndHashesVariantOutputAcrossMultipleStreamingBuffers() throws Exception {
        EngineExecutionResult result = adapter(configured(),
                new RecordingRunner(0, false, OutputMode.LARGE))
                .prepare(job("large-output", PhenotypeAwareSummary.disabled()));

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus());
        assertEquals(5_000, result.getInterpretations().size());
        assertEquals("GRCh38|1|100|A|T", result.getInterpretations().get(0).getVariantId());
        assertEquals("GRCh38|1|5099|A|T", result.getInterpretations().get(4_999).getVariantId());
        assertEquals("e6f5436b141c70b0848a8f3a31bd4860a5577d301e71a07c307aaad183c89530",
                result.getMetadata().getOutputSha256());
    }

    @Test
    void classifiesEngineOutputIndelsForLocalEvidenceRouting() throws Exception {
        EngineExecutionResult result = adapter(
                configured(), new RecordingRunner(0, false, OutputMode.INDEL)
        ).prepare(job("indel", PhenotypeAwareSummary.disabled()));

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus());
        assertEquals("INDEL", result.getInterpretations().get(0).getObservation().getVariantType());
        assertFalse(result.getMetadata().isFallbackUsed());
    }

    @Test
    void acceptsAValidatedGrch38DerivedVcfForAGrch37Upload() throws Exception {
        RecordingRunner runner = new RecordingRunner(0, false, OutputMode.VALID);
        VariantAnalysisJob original = job("grch37-upload", PhenotypeAwareSummary.disabled(), GenomeAssembly.GRCh37);
        Path derived = original.getWorkspace().resolve("lifted-grch38.vcf");
        Files.write(derived, Arrays.asList("##fileformat=VCFv4.2",
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1"), StandardCharsets.UTF_8);

        EngineExecutionResult result = adapter(configured(), runner)
                .prepare(original, derived, GenomeAssembly.GRCh38);

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus());
        assertTrue(runner.command.contains("--vcf=" + derived.toAbsolutePath()));
        assertTrue(text(result.getWorkDirectory().resolve("input/sample.yaml"))
                .contains(derived.toAbsolutePath().toString().replace('\\', '/')));
        assertFalse(result.getMetadata().isFallbackUsed());
    }

    @Test
    void writesATruthFreeNeutralPedigreeForMultiSampleVcf() throws Exception {
        RecordingRunner runner = new RecordingRunner(0, false, OutputMode.VALID);
        VariantAnalysisJob job = multiSampleJob("multi-sample");

        EngineExecutionResult result = adapter(configured(), runner).prepare(job);

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus());
        Path input = result.getWorkDirectory().resolve("input");
        String sample = text(input.resolve("sample.yaml"));
        String pedigree = text(input.resolve("generated.ped"));
        assertTrue(sample.contains("ped: '" + input.resolve("generated.ped").toAbsolutePath()
                .toString().replace('\\', '/') + "'"));
        assertTrue(pedigree.contains("VARVIS\tS1\t0\t0\t0\t2"));
        assertTrue(pedigree.contains("VARVIS\tS2\t0\t0\t0\t-9"));
        assertFalse(sample.toLowerCase().contains("truth"));
        assertFalse(result.getMetadata().isFallbackUsed());
    }

    @Test
    void writesAnExplicitTruthFreeJointTrioPedigree() throws Exception {
        RecordingRunner runner = new RecordingRunner(0, false, OutputMode.VALID);
        VariantAnalysisJob job = trioJob("joint-trio");
        TrioPipelineInput trio = TrioPipelineInput.jointVcf(
                job.getUploadedFile(),
                new TrioVcfSampleMapping("proband", "father", "mother"),
                Arrays.asList(
                        new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                        new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                        new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false)
                )
        );

        EngineExecutionResult result = adapter(configured(), runner)
                .prepare(job, job.getUploadedFile(), GenomeAssembly.GRCh38, trio);

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus());
        String pedigree = text(result.getWorkDirectory().resolve("input/generated.ped"));
        assertTrue(pedigree.contains("VARVIS\tproband\tfather\tmother\t2\t2"));
        assertTrue(pedigree.contains("VARVIS\tfather\t0\t0\t1\t1"));
        assertTrue(pedigree.contains("VARVIS\tmother\t0\t0\t2\t1"));
        assertTrue(result.getPedigreeSha256().matches("[0-9a-f]{64}"));
        assertFalse(pedigree.toLowerCase().contains("truth"));
        assertFalse(result.getMetadata().isFallbackUsed());
    }

    @Test
    void jointTrioG1WritesOnlyObservedHpoAndKeepsG0SemanticsSeparate() throws Exception {
        VariantAnalysisJob job = trioJob("joint-trio-g1", summary("HP:0001250"));
        TrioPipelineInput trio = TrioPipelineInput.jointVcf(job.getUploadedFile(),
                new TrioVcfSampleMapping("proband", "father", "mother"), Arrays.asList(
                        new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                        new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                        new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false)));
        EngineExecutionResult result = adapter(configured(),
                new RecordingRunner(0, false, OutputMode.VALID))
                .prepare(job, job.getUploadedFile(), GenomeAssembly.GRCh38, trio);

        String sample = text(result.getWorkDirectory().resolve("input/sample.yaml"));
        String analysis = text(result.getWorkDirectory().resolve("input/analysis.yaml"));
        assertEquals("EXOMISER_G1", result.getMetadata().getRankingMode());
        assertTrue(sample.contains("HP:0001250"));
        assertTrue(analysis.contains("hiPhivePrioritiser:"));
        assertFalse(sample.toLowerCase().contains("truth"));
    }

    private ConfiguredExomiserEngineAdapter adapter(VariantAnalysisProperties properties,
                                                     ExomiserProcessRunner runner) {
        return new ConfiguredExomiserEngineAdapter(
                properties,
                runner,
                new ExomiserRuntimeValidator(
                        ExomiserRuntimeValidator.HostPlatform.WINDOWS,
                        ignored -> true
                )
        );
    }

    private void assertFailure(RecordingRunner runner, String code, Integer exitCode) throws Exception {
        EngineExecutionResult result = adapter(configured(), runner)
                .prepare(job("failure-" + code, summary("HP:0001250")));
        assertEquals(EngineExecutionStatus.FAILED, result.getMetadata().getStatus());
        assertTrue(result.getFailureCode().startsWith(code));
        assertEquals(exitCode, result.getExitCode());
        assertFalse(result.getMetadata().isFallbackUsed());
    }

    private VariantAnalysisProperties configured() throws IOException {
        Path java = temporary.resolve("jdk/bin/java.exe");
        Files.createDirectories(java.getParent());
        Files.write(java, new byte[] {'M', 'Z'});
        Files.write(java.getParent().getParent().resolve("release"), (
                "JAVA_VERSION=\"21.0.11\"\nOS_NAME=\"Windows\"\nOS_ARCH=\"x86_64\"\nIMAGE_TYPE=\"JDK\"\n"
        ).getBytes(StandardCharsets.UTF_8));
        Path cli = temporary.resolve("exomiser-cli-15.1.0.jar");
        Files.write(cli, new byte[] {'P', 'K'});
        Path data = temporary.resolve("data");
        Files.createDirectories(data.resolve("2602_hg38"));
        Files.createDirectories(data.resolve("2602_phenotype"));
        Path template = temporary.resolve("preset-exome-analysis.yml");
        Files.write(template, (
                "inheritanceModes: {\n"
                        + "  AUTOSOMAL_DOMINANT: 0.1,\n  AUTOSOMAL_RECESSIVE_HOM_ALT: 0.1,\n"
                        + "  AUTOSOMAL_RECESSIVE_COMP_HET: 2.0,\n  X_DOMINANT: 0.1,\n"
                        + "  X_RECESSIVE_HOM_ALT: 0.1,\n  X_RECESSIVE_COMP_HET: 2.0,\n  MITOCHONDRIAL: 0.2\n}\n"
                        + "hiPhivePrioritiser: {}\n"
        ).getBytes(StandardCharsets.UTF_8));

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getExomiser().setEnabled(true);
        properties.getExomiser().setJavaExecutable(java.toString());
        properties.getExomiser().setCliJar(cli.toString());
        properties.getExomiser().setDataDirectory(data.toString());
        properties.getExomiser().setAnalysisTemplate(template.toString());
        properties.getExomiser().setResourceFingerprint(SHA256);
        properties.getExomiser().setTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private VariantAnalysisJob job(String id, PhenotypeAwareSummary phenotype) throws IOException {
        return job(id, phenotype, GenomeAssembly.GRCh38);
    }

    private VariantAnalysisJob job(String id, PhenotypeAwareSummary phenotype, GenomeAssembly assembly) throws IOException {
        Path workspace = temporary.resolve("jobs").resolve(id);
        Files.createDirectories(workspace);
        Path vcf = workspace.resolve("input.vcf");
        Files.write(vcf, Arrays.asList("##fileformat=VCFv4.2", "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1"), StandardCharsets.UTF_8);
        return new VariantAnalysisJob(id, "input.vcf", SHA256, assembly,
                org.nbscn.varvis.variantanalysis.domain.AnalysisMode.WES, "S1", workspace, vcf, phenotype);
    }

    private VariantAnalysisJob multiSampleJob(String id) throws IOException {
        Path workspace = temporary.resolve("jobs").resolve(id);
        Files.createDirectories(workspace);
        Path vcf = workspace.resolve("input.vcf");
        Files.write(vcf, Arrays.asList("##fileformat=VCFv4.2",
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\tS2"), StandardCharsets.UTF_8);
        return new VariantAnalysisJob(id, "input.vcf", SHA256, GenomeAssembly.GRCh38,
                org.nbscn.varvis.variantanalysis.domain.AnalysisMode.WES, "S1", workspace, vcf,
                PhenotypeAwareSummary.disabled());
    }

    private VariantAnalysisJob trioJob(String id) throws IOException {
        return trioJob(id, PhenotypeAwareSummary.disabled());
    }

    private VariantAnalysisJob trioJob(String id, PhenotypeAwareSummary phenotype) throws IOException {
        Path workspace = temporary.resolve("jobs").resolve(id);
        Files.createDirectories(workspace);
        Path vcf = workspace.resolve("input.vcf");
        Files.write(vcf, Arrays.asList("##fileformat=VCFv4.2",
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\tfather\tmother"), StandardCharsets.UTF_8);
        return new VariantAnalysisJob(id, "input.vcf", SHA256, GenomeAssembly.GRCh38,
                org.nbscn.varvis.variantanalysis.domain.AnalysisMode.WES, "proband", workspace, vcf,
                phenotype);
    }

    private PhenotypeAwareSummary summary(String... hpo) {
        return new PhenotypeAwareSummary(hpo.length, hpo.length, 0, 0, Arrays.asList(hpo),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), true, false);
    }

    private RecordingRunner failingRunner() {
        return new RecordingRunner(0, false, OutputMode.NONE) {
            @Override
            public ExomiserProcessResult run(List<String> command, Path work, Path stdout, Path stderr,
                                              Map<String, String> environment, Duration timeout) {
                throw new AssertionError("disabled adapter must not start a process");
            }
        };
    }

    private String text(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private enum OutputMode {
        VALID,
        INDEL,
        MALFORMED,
        MALFORMED_GENE,
        NO_GENES,
        TRAILING_OPTIONAL_GENE_COLUMNS,
        LARGE,
        NONE
    }

    private static class RecordingRunner implements ExomiserProcessRunner {
        private final int exitCode;
        private final boolean timedOut;
        private final OutputMode outputMode;
        private List<String> command = Collections.emptyList();

        private RecordingRunner(int exitCode, boolean timedOut, OutputMode outputMode) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.outputMode = outputMode;
        }

        @Override
        public ExomiserProcessResult run(List<String> command, Path work, Path stdout, Path stderr,
                                         Map<String, String> environment, Duration timeout) throws IOException {
            this.command = command;
            Files.write(stdout, Collections.singletonList("synthetic runner"), StandardCharsets.UTF_8);
            Files.write(stderr, Collections.<String>emptyList(), StandardCharsets.UTF_8);
            if (outputMode != OutputMode.NONE) {
                Path output = null;
                for (String token : command) {
                    if (token.startsWith("--output-directory=")) {
                        output = Paths.get(token.substring("--output-directory=".length()));
                    }
                }
                if (output == null) {
                    throw new IOException("Synthetic runner did not receive an output directory");
                }
                Files.createDirectories(output);
                if (outputMode == OutputMode.MALFORMED) {
                    Files.write(output.resolve("result.variants.tsv"), Arrays.asList(
                            "#RANK\tCONTIG\tSTART\tREF\tALT",
                            "1\t1\tPRIVATE_START_CANARY_0d9a\tA\tT"
                    ), StandardCharsets.UTF_8);
                } else if (outputMode == OutputMode.LARGE) {
                    try (BufferedWriter writer = Files.newBufferedWriter(
                            output.resolve("result.variants.tsv"), StandardCharsets.UTF_8)) {
                        writer.write("#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tEXOMISER_VARIANT_SCORE\n");
                        for (int index = 0; index < 5_000; index++) {
                            writer.write(Integer.toString(index + 1));
                            writer.write("\t1\t");
                            writer.write(Integer.toString(index + 100));
                            writer.write("\tA\tT\tGENE1\t0.91\n");
                        }
                    }
                    Files.write(output.resolve("result.genes.tsv"), Arrays.asList(
                            "#RANK\tGENE_SYMBOL\tGENE_SCORE", "1\tGENE1\t0.92"
                    ), StandardCharsets.UTF_8);
                } else {
                    Files.write(output.resolve("result.variants.tsv"), Arrays.asList(
                            "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tEXOMISER_VARIANT_SCORE",
                            outputMode == OutputMode.INDEL
                                    ? "1\t1\t123\tA\tAT\tGENE1\t0.91"
                                    : "1\t1\t123\tA\tT\tGENE1\t0.91"
                    ), StandardCharsets.UTF_8);
                    if (outputMode == OutputMode.MALFORMED_GENE) {
                        Files.write(output.resolve("result.genes.tsv"), Arrays.asList(
                                "#RANK\tPRIVATE_GENE_CANARY_7f30\tPRIVATE_GENE_CANARY_7f30",
                                "1\tsecret\tsecret"
                        ), StandardCharsets.UTF_8);
                    } else if (outputMode != OutputMode.NO_GENES) {
                        Files.write(output.resolve("result.genes.tsv"),
                                outputMode == OutputMode.TRAILING_OPTIONAL_GENE_COLUMNS
                                        ? Arrays.asList(
                                                "#RANK\tGENE_SYMBOL\tGENE_SCORE\tOPTIONAL_EVIDENCE",
                                                "1\tGENE1\t0.92"
                                        )
                                        : Arrays.asList(
                                                "#RANK\tGENE_SYMBOL\tGENE_SCORE",
                                                "1\tGENE1\t0.92"
                                        ),
                                StandardCharsets.UTF_8);
                    }
                }
            }
            return new ExomiserProcessResult(exitCode, timedOut);
        }
    }
}
