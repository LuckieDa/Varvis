package org.nbscn.varvis.variantanalysis.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.trio.TrioMember;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.nbscn.varvis.variantanalysis.trio.TrioRole;
import org.nbscn.varvis.variantanalysis.trio.TrioSex;
import org.nbscn.varvis.variantanalysis.trio.TrioVcfSampleMapping;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in local-only smoke. It reads just Exomiser path values from a Git-ignored
 * properties file and uses no database, service, patient VCF, truth, or benchmark input.
 */
@EnabledIfSystemProperty(named = "varvis.exomiser.trio.smoke", matches = "true")
class ExomiserTrioRuntimeSmokeTest {

    @TempDir
    Path temporary;

    @Test
    void runsAThreeSampleTruthFreeJointVcfThroughTheConfiguredWindowsRuntime() throws Exception {
        VariantAnalysisProperties properties = runtimeProperties();
        Path vcf = temporary.resolve("synthetic-joint-trio.vcf");
        Files.write(vcf, Arrays.asList(
                "##fileformat=VCFv4.2",
                "##reference=GRCh38",
                "##contig=<ID=chr7,length=159345973,assembly=GRCh38>",
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\tfather\tmother",
                "chr7\t140753336\t.\tA\tT\t.\tPASS\t.\tGT\t0/1\t0/0\t0/0"
        ), StandardCharsets.UTF_8);
        VariantAnalysisJob job = new VariantAnalysisJob(
                "synthetic-trio", "synthetic-joint-trio.vcf",
                "synthetic-input-not-a-patient", GenomeAssembly.GRCh38, AnalysisMode.WES,
                "proband", temporary, vcf, PhenotypeAwareSummary.disabled()
        );
        TrioPipelineInput trio = TrioPipelineInput.jointVcf(vcf,
                new TrioVcfSampleMapping("proband", "father", "mother"), Arrays.asList(
                        new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                        new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                        new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false)
                ));

        EngineExecutionResult result = new ConfiguredExomiserEngineAdapter(
                properties, new SystemExomiserProcessRunner())
                .prepare(job, vcf, GenomeAssembly.GRCh38, trio);

        assertEquals(EngineExecutionStatus.RANKED, result.getMetadata().getStatus(), result.getFailureCode());
        assertEquals("EXOMISER_G0", result.getMetadata().getRankingMode());
        assertTrue(result.getRequestSha256().matches("[0-9a-f]{64}"));
        assertTrue(result.getPedigreeSha256().matches("[0-9a-f]{64}"));
        assertTrue(result.getMetadata().getOutputSha256().matches("[0-9a-f]{64}"));
        assertFalse(result.getMetadata().isFallbackUsed());
        String sample = new String(Files.readAllBytes(result.getWorkDirectory().resolve("input/sample.yaml")),
                StandardCharsets.UTF_8);
        String pedigree = new String(Files.readAllBytes(result.getWorkDirectory().resolve("input/generated.ped")),
                StandardCharsets.UTF_8);
        assertTrue(sample.contains("hpoIds:\n  []"));
        assertTrue(sample.contains("ped:"));
        assertTrue(pedigree.contains("VARVIS\tproband\tfather\tmother\t2\t2"));
        assertFalse(sample.toLowerCase().contains("truth"));
        assertFalse(pedigree.toLowerCase().contains("truth"));
    }

    private VariantAnalysisProperties runtimeProperties() throws Exception {
        String location = System.getProperty("varvis.exomiser.trio.local-config", "");
        if (location.trim().isEmpty()) {
            throw new IllegalStateException("VARVIS_EXOMISER_TRIO_SMOKE_CONFIG_REQUIRED");
        }
        Properties source = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get(location))) {
            source.load(input);
        }
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantAnalysisProperties.Exomiser exomiser = properties.getExomiser();
        exomiser.setEnabled(true);
        exomiser.setVersion(required(source, "variant-analysis.exomiser.version"));
        exomiser.setDataVersion(required(source, "variant-analysis.exomiser.data-version"));
        exomiser.setJavaExecutable(required(source, "variant-analysis.exomiser.java-executable"));
        exomiser.setCliJar(required(source, "variant-analysis.exomiser.cli-jar"));
        exomiser.setDataDirectory(required(source, "variant-analysis.exomiser.data-directory"));
        exomiser.setAnalysisTemplate(required(source, "variant-analysis.exomiser.analysis-template"));
        exomiser.setResourceFingerprint(required(source, "variant-analysis.exomiser.resource-fingerprint"));
        exomiser.setTimeout(Duration.parse(required(source, "variant-analysis.exomiser.timeout")));
        return properties;
    }

    private String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("VARVIS_EXOMISER_TRIO_SMOKE_CONFIG_MISSING:" + key);
        }
        return value.trim();
    }
}
