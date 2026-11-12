package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VepTranscriptAnnotationProvider implements TranscriptAnnotationProvider {

    private final VariantAnalysisProperties properties;
    private final VepResourceValidator resourceValidator;
    private final VepCommandBuilder commandBuilder;
    private final VepJsonOutputParser outputParser;
    private final ExternalProcessRunner processRunner;
    private final VepResourceProfileResolver profileResolver;

    @Autowired
    public VepTranscriptAnnotationProvider(VariantAnalysisProperties properties,
                                           VepResourceValidator resourceValidator,
                                           VepCommandBuilder commandBuilder,
                                           VepJsonOutputParser outputParser,
                                           ExternalProcessRunner processRunner) {
        this.properties = properties;
        this.resourceValidator = resourceValidator;
        this.commandBuilder = commandBuilder;
        this.outputParser = outputParser;
        this.processRunner = VepOwnedProcessRunner.select(properties, processRunner);
        this.profileResolver = new VepResourceProfileResolver(properties);
    }

    public VepTranscriptAnnotationProvider(VariantAnalysisProperties properties) {
        this.properties = properties;
        this.resourceValidator = new VepResourceValidator(properties);
        this.commandBuilder = new VepCommandBuilder(properties);
        this.outputParser = new VepJsonOutputParser(new ObjectMapper());
        this.processRunner = VepOwnedProcessRunner.select(properties, new SystemExternalProcessRunner());
        this.profileResolver = new VepResourceProfileResolver(properties);
    }

    @Override
    public String source() {
        return "VEP_CACHED";
    }

    @Override
    public String version() {
        return properties.getVep().getVersion();
    }

    @Override
    public List<TranscriptConsequence> annotate(VariantInterpretation variant, AnalysisContext context) {
        try {
            GenomeAssembly assembly = context == null ? null : context.getGenomeAssembly();
            VepResourceProfileResolution resolution = profileResolver.resolve(assembly);
            if (!resolution.isResolved()) {
                return placeholder(
                        "VEP resource profile is unavailable for "
                                + (assembly == null ? "unknown" : assembly.name()) + ": " + resolution.getReason(),
                        "ASSEMBLY_UNSUPPORTED".equals(resolution.getStatus())
                                ? "ASSEMBLY_UNSUPPORTED"
                                : "UNAVAILABLE"
                );
            }
            VepResourceProfile profile = resolution.getProfile();
            VepReadinessReport readiness = resourceValidator.validate(assembly);
            if (!readiness.isEnabled()) {
                return placeholder(
                        "VEP transcript annotation is disabled; ANN fallback remains available when present.",
                        "DISABLED"
                );
            }
            if (!"READY".equals(readiness.getStatus())) {
                return placeholder(readiness.getExplanation(), "UNAVAILABLE");
            }
            if (!readiness.isExecutionEnabled()) {
                return placeholder(
                        "VEP resources are ready for manual small-run, but execution is disabled by configuration.",
                        "DISABLED"
                );
            }
            Path workspace = null;
            try {
                workspace = createWorkspace("varvis-vep-");
                Path input = workspace.resolve("input.vcf");
                Path output = workspace.resolve("output.json");
                writeSingleVariantVcf(variant, input);
                List<String> command = commandBuilder.build(new VepCommandOptions(input, output), profile);
                ProcessResult result = processRunner.run(
                        command,
                        workspace,
                        Duration.ofSeconds(Math.max(1, properties.getVep().getTimeoutSeconds()))
                );
                if (!result.isSuccess()) {
                    return placeholder(
                            result.isTimedOut()
                                    ? "VEP command timed out."
                                    : "VEP command failed with exit code " + result.getExitCode() + ownedFailure(result),
                            result.isTimedOut() ? "TIMEOUT" : "FAILED"
                    );
                }
                List<TranscriptConsequence> annotations = outputParser.parse(
                        output, source(), profile.getVersion());
                if (annotations.isEmpty()) {
                    return placeholder("VEP completed but returned no transcript consequences.", "NO_RECORD");
                }
                return annotations;
            } catch (IOException ex) {
                boolean timeout = SpecialistProviderSupport.isTimeout(ex);
                return placeholder(
                        timeout ? "VEP execution path timed out." : "VEP execution path failed: " + ex.getMessage(),
                        timeout ? "TIMEOUT" : "FAILED"
                );
            } finally {
                cleanup(workspace);
            }
        } catch (RuntimeException ex) {
            boolean timeout = SpecialistProviderSupport.isTimeout(ex);
            return placeholder(
                    timeout ? "VEP provider runtime timed out." : "VEP provider runtime failed.",
                    timeout ? "TIMEOUT" : "FAILED"
            );
        }
    }

    /**
     * Runs one bounded VEP process for a group of variants and maps its records
     * back by deterministic VCF IDs. This explicit entry is not connected to
     * the default patient flow.
     */
    public Map<NormalizedVariantKey, List<TranscriptConsequence>> annotateBatch(
            List<VariantInterpretation> variants,
            AnalysisContext context) {
        Map<NormalizedVariantKey, List<TranscriptConsequence>> result =
                new LinkedHashMap<NormalizedVariantKey, List<TranscriptConsequence>>();
        if (variants == null || variants.isEmpty()) {
            return result;
        }
        try {
            GenomeAssembly assembly = context == null ? null : context.getGenomeAssembly();
            VepResourceProfileResolution resolution = profileResolver.resolve(assembly);
            if (!resolution.isResolved()) {
                return batchPlaceholders(variants,
                        "VEP resource profile is unavailable for "
                                + (assembly == null ? "unknown" : assembly.name())
                                + ": " + resolution.getReason(),
                        "ASSEMBLY_UNSUPPORTED".equals(resolution.getStatus())
                                ? "ASSEMBLY_UNSUPPORTED" : "UNAVAILABLE");
            }
            VepResourceProfile profile = resolution.getProfile();
            VepReadinessReport readiness = resourceValidator.validate(assembly);
            if (!readiness.isEnabled()) {
                return batchPlaceholders(variants,
                        "VEP transcript annotation is disabled; ANN fallback remains available when present.",
                        "DISABLED");
            }
            if (!"READY".equals(readiness.getStatus())) {
                return batchPlaceholders(variants, readiness.getExplanation(), "UNAVAILABLE");
            }
            if (!readiness.isExecutionEnabled()) {
                return batchPlaceholders(variants,
                        "VEP resources are ready for manual small-run, but execution is disabled by configuration.",
                        "DISABLED");
            }

            Path workspace = null;
            try {
                workspace = createWorkspace("varvis-vep-batch-");
                Path input = workspace.resolve("input.vcf");
                Path output = workspace.resolve("output.json");
                Map<String, NormalizedVariantKey> keysByVcfId = writeBatchVariantVcf(variants, input);
                List<String> command = commandBuilder.build(new VepCommandOptions(input, output), profile);
                ProcessResult process = processRunner.run(
                        command,
                        workspace,
                        Duration.ofSeconds(Math.max(1, properties.getVep().getTimeoutSeconds()))
                );
                if (!process.isSuccess()) {
                    return batchPlaceholders(variants,
                            process.isTimedOut()
                                    ? "VEP command timed out."
                                    : "VEP command failed with exit code " + process.getExitCode() + ownedFailure(process),
                            process.isTimedOut() ? "TIMEOUT" : "FAILED");
                }
                VepParseResult parsed = outputParser.parseByVariantId(output, source(), profile.getVersion());
                for (Map.Entry<String, NormalizedVariantKey> entry : keysByVcfId.entrySet()) {
                    List<TranscriptConsequence> consequences =
                            parsed.getAnnotationsByVariantId().get(entry.getKey());
                    result.put(entry.getValue(), consequences == null || consequences.isEmpty()
                            ? placeholder("VEP completed but returned no transcript consequences.", "NO_RECORD")
                            : consequences);
                }
                return result;
            } catch (IOException ex) {
                boolean timeout = SpecialistProviderSupport.isTimeout(ex);
                return batchPlaceholders(variants,
                        timeout ? "VEP execution path timed out."
                                : "VEP execution path failed: " + ex.getMessage(),
                        timeout ? "TIMEOUT" : "FAILED");
            } finally {
                cleanup(workspace);
            }
        } catch (RuntimeException ex) {
            boolean timeout = SpecialistProviderSupport.isTimeout(ex);
            return batchPlaceholders(variants,
                    timeout ? "VEP provider runtime timed out." : "VEP provider runtime failed.",
                    timeout ? "TIMEOUT" : "FAILED");
        }
    }

    private void writeSingleVariantVcf(VariantInterpretation variant, Path input) throws IOException {
        NormalizedVariantKey key = variant == null || variant.getObservation() == null
                ? null
                : variant.getObservation().getKey();
        List<String> lines = new ArrayList<String>();
        lines.add("##fileformat=VCFv4.2");
        lines.add("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO");
        if (key != null) {
            lines.add(vcfChromosome(key.getChromosome())
                    + "\t" + key.getPosition()
                    + "\t.\t" + key.getReference()
                    + "\t" + key.getAlternate()
                    + "\t.\tPASS\t.");
        }
        Files.write(input, lines, StandardCharsets.UTF_8);
    }

    private Map<String, NormalizedVariantKey> writeBatchVariantVcf(
            List<VariantInterpretation> variants,
            Path input) throws IOException {
        List<String> lines = new ArrayList<String>();
        lines.add("##fileformat=VCFv4.2");
        lines.add("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO");
        Map<String, NormalizedVariantKey> keysByVcfId =
                new LinkedHashMap<String, NormalizedVariantKey>();
        int index = 0;
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant == null || variant.getObservation() == null
                    ? null : variant.getObservation().getKey();
            if (key == null) {
                continue;
            }
            String vcfId = "VARVIS_VEP_BATCH_" + index++;
            keysByVcfId.put(vcfId, key);
            lines.add(vcfChromosome(key.getChromosome())
                    + "\t" + key.getPosition()
                    + "\t" + vcfId
                    + "\t" + key.getReference()
                    + "\t" + key.getAlternate()
                    + "\t.\tPASS\t.");
        }
        Files.write(input, lines, StandardCharsets.UTF_8);
        return keysByVcfId;
    }

    private Map<NormalizedVariantKey, List<TranscriptConsequence>> batchPlaceholders(
            List<VariantInterpretation> variants,
            String summary,
            String status) {
        Map<NormalizedVariantKey, List<TranscriptConsequence>> result =
                new LinkedHashMap<NormalizedVariantKey, List<TranscriptConsequence>>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant == null || variant.getObservation() == null
                    ? null : variant.getObservation().getKey();
            if (key != null) {
                result.put(key, placeholder(summary, status));
            }
        }
        return result;
    }

    private Path createWorkspace(String prefix) throws IOException {
        Path tempDir = properties.getVep().getTempDir();
        if (tempDir == null) {
            return Files.createTempDirectory(prefix);
        }
        Files.createDirectories(tempDir);
        return Files.createTempDirectory(tempDir, prefix);
    }

    private String vcfChromosome(String chromosome) {
        if (chromosome == null || chromosome.toLowerCase().startsWith("chr")) {
            return chromosome;
        }
        return "chr" + chromosome;
    }

    private void cleanup(Path workspace) {
        if (workspace == null) {
            return;
        }
        if (!properties.getVep().isCleanupTemp() || VepOwnedProcessRunner.preserveWorkspace(properties)) {
            return;
        }
        try {
            Files.deleteIfExists(workspace.resolve("input.vcf"));
            Files.deleteIfExists(workspace.resolve("output.json"));
            Files.deleteIfExists(workspace);
        } catch (IOException ignored) {
            // Temporary VEP test workspace cleanup is best-effort.
        }
    }

    private String ownedFailure(ProcessResult result) {
        return result.getExecutionReceipt().isEmpty() ? "" : ": " + result.getExecutionReceipt().get("failure");
    }

    private List<TranscriptConsequence> placeholder(String summary, String status) {
        return Collections.singletonList(TranscriptConsequence.unavailable(
                summary,
                source(),
                version(),
                Instant.now()
        ).withAnnotationMetadata(source(), status, version(), Instant.now()));
    }
}
