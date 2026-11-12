package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import htsjdk.variant.vcf.VCFFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy;
import org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator;
import org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.VepCommandBuilder;
import org.nbscn.varvis.variantanalysis.provider.VepCommandOptions;
import org.nbscn.varvis.variantanalysis.service.impl.BcftoolsVcfNormalizer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in bounded public-input audit. No analysis, candidate injection, database or listening service. */
class Hnf1aFrameshiftPreflightTest {
    private static final Set<String> INPUTS = Set.of("12:120989017:C:T", "12:120993577:CA:C",
            "12:120993678:C:T", "12:120994314:G:GC");
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @EnabledIfSystemProperty(named = "varvis.pvs1.frameshift.preflight", matches = "true")
    void publicInputsThroughReferenceNormalizationVepAndBoundedFrequency() throws Exception {
        Path root = requiredPath("varvis.pvs1.frameshift.artifactRoot").toRealPath();
        assertFalse(root.startsWith(Path.of("..").toRealPath()), "Use the task-owned external acceptance directory");
        Path work = Files.createDirectory(root.resolve("preflight-" + UUID.randomUUID()));
        System.out.println("PVS1_FRAMESHIFT_PREFLIGHT=" + work);
        var report = mapper.createObjectNode().put("status", "BLOCKED").put("dataClass", "SYNTHETIC")
                .put("scope", "Public exact alleles; no patient evidence or clinical inference")
                .put("formalAnalysisCalls", 0).put("vepCalls", 0).put("frequencyDecoderBatches", 0);
        try {
            Path input = requiredPath("varvis.pvs1.frameshift.vcf").toRealPath();
            assertTrue(Files.size(input) <= 1024 * 1024, "Only a small public VCF is authorized");
            Path copied = work.resolve("fixed-public.vcf"); Files.copy(input, copied);
            report.put("inputVcfSha256", IntronicScopeRuntimeProbe.sha(copied));
            var properties = new VariantAnalysisProperties();
            var configured = new Properties();
            try (var stream = Files.newInputStream(requiredPath("varvis.pvs1.frameshift.config"))) { configured.load(stream); }
            var allowed = new Properties();
            configured.forEach((key, value) -> { if (key.toString().startsWith("variant-analysis.")) allowed.put(key, value); });
            var environment = new MockEnvironment().withProperty("VARVIS_RESOURCE_ROOT",
                    Objects.requireNonNull(requiredPath("varvis.pvs1.frameshift.resourceRoot").toRealPath().toString()));
            environment.withProperty("VARVIS_PROJECT_ROOT", Objects.requireNonNull(Path.of("../..").toRealPath().toString()));
            environment.getPropertySources().addLast(new PropertiesPropertySource("existing-local-variant-settings", allowed));
            Binder.get(environment).bind("variant-analysis", Bindable.ofInstance(properties));
            assertEquals(VariantAnalysisProperties.Mode.BCFTOOLS, properties.getNormalization().getMode());
            properties.setStorageRoot(work.resolve("unused-jobs")); properties.getVep().setTempDir(work.resolve("vep-temp").toString());
            write(work, "actual-configuration.json", mapper.valueToTree(properties));
            var ledger = ExplicitContextSourceLedger.load(mapper,
                    Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),
                    environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var reference = new NativeSnvReference(mapper, properties, new NormalizationResourceValidator(properties),
                    Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")), List.of(ledger), ledger.reference("reference"));
            var original = referenceObservations(copied, reference); write(work, "original-reference.json", original);
            assertEquals(INPUTS, alleleKeys(original), "Public controls were fixed before annotation and frequency inspection");
            var normalization = new BcftoolsVcfNormalizer(properties).normalize(copied,
                    Files.createDirectory(work.resolve("normalization")), GenomeAssembly.GRCh38);
            write(work, "normalization-result.json", mapper.valueToTree(normalization));
            assertTrue(normalization.isNormalizationApplied(), "A COPY_ONLY input cannot prove indel normalization");
            Path normalizedVcf = Objects.requireNonNull(normalization.getOutputFile());
            var normalized = referenceObservations(normalizedVcf, reference); write(work, "normalized-reference.json", normalized);
            assertEquals(INPUTS.size(), normalized.size());
            var policy = AnalysisScopePolicy.resolve(properties.getExomiser(), AnalysisScopePolicy.Scope.STANDARD, false);
            Path effectiveYaml = work.resolve("effective-standard.yml"); Files.writeString(effectiveYaml, policy.effectiveYaml(), StandardOpenOption.CREATE_NEW);
            if (!Boolean.parseBoolean(System.getProperty("varvis.pvs1.frameshift.annotationAndFrequency", "true"))) {
                report.put("status", "REFERENCE_AND_NORMALIZATION_VERIFIED").put("normalizedVcf", normalizedVcf.toString())
                        .put("annotationAndFrequencyCovered", false);
                return;
            }
            var command = new VepCommandBuilder(properties).build(new VepCommandOptions(normalizedVcf, work.resolve("preflight-vep.jsonl")));
            write(work, "vep-preflight-command.json", mapper.valueToTree(command));
            report.put("vepCalls", 1);
            var runner = new SystemExternalProcessRunner();
            var outcome = runner.run(command, work, Duration.ofSeconds(properties.getVep().getTimeoutSeconds()));
            write(work, "vep-preflight-process.json", mapper.valueToTree(outcome)); assertTrue(outcome.isSuccess(), outcome.getOutput());
            var annotations = mapper.createArrayNode();
            for (String line : Files.readAllLines(work.resolve("preflight-vep.jsonl"))) {
                if (line.isBlank()) continue;
                var raw = mapper.readTree(line); assertEquals("GRCh38", raw.path("assembly_name").asText());
                var matches = new ArrayList<JsonNode>();
                for (var tx : raw.path("transcript_consequences")) if ("ENST00000257555".equals(tx.path("transcript_id").asText())) matches.add(tx);
                assertEquals(1, matches.size()); var tx = matches.get(0);
                assertEquals("HNF1A", tx.path("gene_symbol").asText());
                assertTrue(tx.path("hgvsc").asText().startsWith("ENST00000257555.11:"));
                assertTrue(tx.path("hgvsp").asText().startsWith("ENSP00000257555.5:"));
                String[] fields = raw.path("input").asText().split("\\t"); assertTrue(fields.length >= 5);
                String expectedConsequence = fields[3].length() == fields[4].length() ? "stop_gained" : "frameshift_variant";
                assertTrue(tx.path("consequence_terms").toString().contains(expectedConsequence));
                var record = annotations.addObject().put("input", raw.path("input").asText()); record.set("transcript", tx);
            }
            assertEquals(INPUTS.size(), annotations.size()); write(work, "preflight-annotations.json", annotations);
            Path cli = Path.of(properties.getExomiser().getCliJar()).toRealPath();
            var frequencyCommand = new ArrayList<>(List.of(properties.getExomiser().getJavaExecutable(), "--class-path",
                    Objects.requireNonNull(cli.getParent()).resolve("lib/*").toString(),
                    Path.of("../scripts/pm2/QueryExomiserFrequencyBatch.java").toRealPath().toString(),
                    Path.of(properties.getExomiser().getDataDirectory()).resolve("2602_hg38/2602_hg38_variants.mv.db").toString(),
                    effectiveYaml.toString(), work.resolve("frequency-precheck.json").toString()));
            frequencyCommand.addAll(alleleKeys(normalized).stream().sorted().toList());
            write(work, "frequency-command.json", mapper.valueToTree(frequencyCommand)); report.put("frequencyDecoderBatches", 1);
            var frequencyRun = runner.run(frequencyCommand, work, Duration.ofMinutes(2));
            write(work, "frequency-process.json", mapper.valueToTree(frequencyRun)); assertTrue(frequencyRun.isSuccess(), frequencyRun.getOutput());
            var frequencies = mapper.readTree(Files.readAllBytes(work.resolve("frequency-precheck.json")));
            var ad = frequencies.at("/inheritanceModesPercent/AUTOSOMAL_DOMINANT").decimalValue();
            assertTrue(ad.signum() > 0);
            for (var row : frequencies.path("alleles")) {
                assertNotEquals("FAIL", row.path("frequencyFilter").asText());
                if (!row.path("selectedMaxPercent").isNull()) assertTrue(new java.math.BigDecimal(row.path("selectedMaxPercent").asText()).compareTo(ad) <= 0);
            }
            report.put("status", "PUBLIC_INPUTS_PREFLIGHT_VERIFIED").put("normalizedVcf", normalizedVcf.toString())
                    .put("frequencyExactAlleles", frequencies.path("alleles").size())
                    .put("noRecordIsNotFrequencyZero", true).put("scienceAndNmdRequireIndependentVerification", true);
        } catch (Exception | AssertionError failure) {
            report.put("failure", failure.toString()); throw failure;
        } finally { write(work, "preflight-report.json", report); }
    }

    private JsonNode referenceObservations(Path vcf, NativeSnvReference reference) throws Exception {
        var result = mapper.createArrayNode();
        try (var reader = new VCFFileReader(vcf, false)) {
            assertEquals(List.of("synthetic-proband"), reader.getFileHeader().getGenotypeSamples());
            for (var row : reader) {
                assertEquals(1, row.getAlternateAlleles().size());
                String ref = row.getReference().getDisplayString(), alt = row.getAlternateAllele(0).getDisplayString();
                assertTrue(ref.matches("[ACGT]{1,50}") && alt.matches("[ACGT]{1,50}"));
                assertTrue(ref.length() == 1 && alt.length() == 1
                        || Math.min(ref.length(), alt.length()) == 1 && ref.charAt(0) == alt.charAt(0));
                var observation = reference.observe(row.getContig(), row.getStart(), ref);
                assertEquals("MATCH", observation.observation().path("status").asText());
                var item = result.addObject().put("allele", row.getContig().replaceFirst("^chr", "") + ":" + row.getStart() + ":" + ref + ":" + alt);
                item.set("reference", mapper.valueToTree(observation));
            }
        }
        return result;
    }
    private static Set<String> alleleKeys(JsonNode rows) {
        var result = new HashSet<String>(); for (var row : rows) assertTrue(result.add(row.path("allele").asText())); return result;
    }
    private static Path requiredPath(String key) { return Path.of(Objects.requireNonNull(System.getProperty(key), key + " is required")).toAbsolutePath().normalize(); }
    private void write(Path directory, String name, JsonNode value) throws Exception {
        Files.writeString(directory.resolve(name), value.toPrettyString(), StandardOpenOption.CREATE_NEW);
    }
}
