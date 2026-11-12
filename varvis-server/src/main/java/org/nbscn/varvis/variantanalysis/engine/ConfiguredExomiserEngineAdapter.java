package org.nbscn.varvis.variantanalysis.engine;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Truth-free Exomiser runtime boundary. It uses only explicit private runtime
 * configuration and never falls back to the legacy Varvis engine.
 */
@Component
public class ConfiguredExomiserEngineAdapter implements ExomiserEngineAdapter {

    private final VariantAnalysisProperties properties;
    private final ExomiserProcessRunner processRunner;
    private final ExomiserRequestWriter requestWriter;
    private final ExomiserOutputParser outputParser;
    private final ExomiserRuntimeValidator runtimeValidator;
    private final AnalysisVcfAlleleIndex analysisVcfAlleleIndex;

    @Autowired
    public ConfiguredExomiserEngineAdapter(VariantAnalysisProperties properties,
                                           ExomiserProcessRunner processRunner,
                                           ExomiserRuntimeValidator runtimeValidator) {
        this(properties, processRunner, new ExomiserRequestWriter(), new ExomiserOutputParser(), runtimeValidator);
    }

    ConfiguredExomiserEngineAdapter(VariantAnalysisProperties properties,
                                    ExomiserProcessRunner processRunner) {
        this(properties, processRunner, new ExomiserRequestWriter(), new ExomiserOutputParser(),
                new ExomiserRuntimeValidator());
    }

    ConfiguredExomiserEngineAdapter(VariantAnalysisProperties properties,
                                    ExomiserProcessRunner processRunner,
                                    ExomiserRequestWriter requestWriter,
                                    ExomiserOutputParser outputParser) {
        this(properties, processRunner, requestWriter, outputParser, new ExomiserRuntimeValidator());
    }

    ConfiguredExomiserEngineAdapter(VariantAnalysisProperties properties,
                                    ExomiserProcessRunner processRunner,
                                    ExomiserRequestWriter requestWriter,
                                    ExomiserOutputParser outputParser,
                                    ExomiserRuntimeValidator runtimeValidator) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.requestWriter = requestWriter;
        this.outputParser = outputParser;
        this.runtimeValidator = runtimeValidator;
        this.analysisVcfAlleleIndex = new AnalysisVcfAlleleIndex();
    }

    @Override
    public EngineExecutionResult prepare(VariantAnalysisJob job) {
        return prepare(job, job == null ? null : job.getUploadedFile(),
                job == null ? null : job.getGenomeAssembly());
    }

    @Override
    public EngineExecutionResult prepare(VariantAnalysisJob job,
                                         Path analysisVcf,
                                         GenomeAssembly analysisAssembly) {
        return prepare(job, analysisVcf, analysisAssembly, TrioPipelineInput.absent());
    }

    @Override
    public EngineExecutionResult prepare(VariantAnalysisJob job,
                                         Path analysisVcf,
                                         GenomeAssembly analysisAssembly,
                                         TrioPipelineInput trioInput) {
        VariantAnalysisProperties.Exomiser settings = properties.getExomiser();
        if (!settings.isEnabled()) {
            return unavailable("EXOMISER_DISABLED", job, settings);
        }
        String configurationError = validateConfiguration(job, analysisVcf, analysisAssembly, settings);
        if (configurationError != null) {
            return unavailable(configurationError, job, settings);
        }

        Path workDirectory = null;
        String requestSha256 = "";
        String commandSha256 = "";
        try {
            var analysisVcfBeforeExecution = AnalysisVcfAlleleIndex.ArtifactStamp.capture(analysisVcf);
            workDirectory = Files.createTempDirectory(job.getWorkspace(), "exomiser-");
            ExomiserRequestWriter.RequestArtifacts artifacts = requestWriter.write(
                    job, settings, workDirectory, analysisVcf, hpoTerms(job), trioInput
            );
            requestSha256 = artifacts.requestSha256();
            List<String> command = command(settings, artifacts, analysisVcf);
            commandSha256 = sha256(commandText(command).getBytes(StandardCharsets.UTF_8));
            Path stdout = workDirectory.resolve("stdout.log");
            Path stderr = workDirectory.resolve("stderr.log");
            ExomiserProcessResult process = processRunner.run(
                    command,
                    workDirectory,
                    stdout,
                    stderr,
                    environment(artifacts),
                    settings.getTimeout()
            );
            if (process.isTimedOut()) {
                return failed("EXOMISER_TIMEOUT", job, settings, workDirectory, requestSha256, commandSha256, process.getExitCode());
            }
            if (process.getExitCode() != 0) {
                return failed("EXOMISER_NONZERO_EXIT", job, settings, workDirectory, requestSha256, commandSha256, process.getExitCode());
            }
            ExomiserOutputParser.ParsedOutput parsedOutput = outputParser.parseVerified(
                    artifacts.getOutputDirectory(), sampleId(job), settings.getVersion(), AnalysisScopePolicy.read(job)
            );
            List<VariantInterpretation> interpretations = parsedOutput.interpretations();
            var hydrated = hydrateAnalysisVcfCalls(
                    interpretations,
                    analysisVcf,
                    analysisAssembly,
                    sampleId(job)
            );
            interpretations = hydrated.candidates();
            outputParser.verifyStillCurrent(artifacts.getOutputDirectory(), parsedOutput);
            if (parsedOutput.manifest() != null) java.nio.file.Files.writeString(
                    workDirectory.resolve(ExomiserZeroPassCoverage.MANIFEST),parsedOutput.manifest().toString(),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            return EngineExecutionResult.completed(
                    EngineMetadata.exomiserRanked(
                            settings.getVersion(),
                            settings.getResourceFingerprint(),
                            parsedOutput.outputSha256(),
                            rankingMode(job)
                    ),
                    interpretations,
                    workDirectory,
                    requestSha256,
                    commandSha256,
                    artifacts.pedigreeSha256(),
                    process.getExitCode()
            ).withInputSource(job, analysisVcf, analysisAssembly, sampleId(job), hydrated.indexed(),
                    analysisVcfBeforeExecution.equals(hydrated.indexed().stamp()));
        } catch (IllegalArgumentException error) {
            return failed(error.getMessage(), job, settings, workDirectory, requestSha256, commandSha256, null);
        } catch (IllegalStateException error) {
            if (isAnalysisVcfFailure(error)) {
                return failed(error.getMessage(), job, settings, workDirectory, requestSha256, commandSha256, null);
            }
            throw error;
        } catch (IOException error) {
            return failed("EXOMISER_PROCESS_START_OR_IO_FAILED", job, settings, workDirectory, requestSha256, commandSha256, null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failed("EXOMISER_PROCESS_INTERRUPTED", job, settings, workDirectory, requestSha256, commandSha256, null);
        }
    }

    private EngineExecutionResult unavailable(String reason,
                                              VariantAnalysisJob job,
                                              VariantAnalysisProperties.Exomiser settings) {
        return EngineExecutionResult.unavailable(EngineMetadata.exomiserUnavailable(
                reason, settings.getVersion(), settings.getResourceFingerprint(), rankingMode(job)
        ));
    }

    private EngineExecutionResult failed(String reason,
                                         VariantAnalysisJob job,
                                         VariantAnalysisProperties.Exomiser settings,
                                         Path workDirectory,
                                         String requestSha256,
                                         String commandSha256,
                                         Integer exitCode) {
        return EngineExecutionResult.failed(
                EngineMetadata.exomiserFailed(reason, settings.getVersion(), settings.getResourceFingerprint(), rankingMode(job)),
                workDirectory, requestSha256, commandSha256, exitCode, reason
        );
    }

    private String validateConfiguration(VariantAnalysisJob job,
                                         Path analysisVcf,
                                         GenomeAssembly analysisAssembly,
                                         VariantAnalysisProperties.Exomiser settings) {
        if (settings.getTimeout() == null || settings.getTimeout().isNegative() || settings.getTimeout().isZero()) {
            return "EXOMISER_TIMEOUT_INVALID";
        }
        if (job == null) {
            return "EXOMISER_JOB_MISSING";
        }
        if (analysisAssembly != GenomeAssembly.GRCh38) {
            return "EXOMISER_GRCH38_REQUIRED";
        }
        if (job.getWorkspace() == null || analysisVcf == null
                || !Files.isRegularFile(analysisVcf) || !Files.isReadable(analysisVcf)) {
            return "EXOMISER_INPUT_VCF_UNREADABLE";
        }
        if (sampleId(job) == null) {
            return "EXOMISER_SAMPLE_ID_MISSING";
        }
        ExomiserRuntimeReadiness readiness = runtimeValidator.validate(settings);
        return readiness.isReady() ? null : readiness.getStatus();
    }

    private List<String> command(VariantAnalysisProperties.Exomiser settings,
                                 ExomiserRequestWriter.RequestArtifacts artifacts,
                                 Path analysisVcf) {
        return Arrays.asList(
                settings.getJavaExecutable(), "-Xmx" + settings.getJavaHeap().toLowerCase(Locale.ROOT),
                "-jar", settings.getCliJar(), "analyse",
                "--sample=" + artifacts.getSample().toAbsolutePath(),
                "--vcf=" + analysisVcf.toAbsolutePath(),
                "--assembly=GRCh38",
                "--analysis=" + artifacts.getAnalysis().toAbsolutePath(),
                "--output-directory=" + artifacts.getOutputDirectory().toAbsolutePath(),
                "--output-filename=result",
                "--output-format=JSON,TSV_GENE,TSV_VARIANT"
        );
    }

    private Map<String, String> environment(ExomiserRequestWriter.RequestArtifacts artifacts) {
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("SPRING_CONFIG_ADDITIONAL_LOCATION", "file:" + artifacts.getRuntimeProperties().toAbsolutePath().toString().replace('\\', '/'));
        return environment;
    }

    private String sampleId(VariantAnalysisJob job) {
        String selected = job.getSelectedSampleId();
        if (!blank(selected)) {
            return selected.trim();
        }
        return blank(job.getRequestedSampleId()) ? null : job.getRequestedSampleId().trim();
    }

    private List<String> hpoTerms(VariantAnalysisJob job) {
        if (job == null || job.getPhenotypeSummary() == null || job.getPhenotypeSummary().getValidHpoTerms() == null) {
            return Collections.emptyList();
        }
        return job.getPhenotypeSummary().getValidHpoTerms();
    }

    private String rankingMode(VariantAnalysisJob job) {
        return hpoTerms(job).isEmpty() ? "EXOMISER_G0" : "EXOMISER_G1";
    }

    private String commandText(List<String> command) {
        StringBuilder builder = new StringBuilder();
        for (String token : command) {
            builder.append(token).append('\n');
        }
        return builder.toString();
    }

    private HydratedCandidates hydrateAnalysisVcfCalls(
            List<VariantInterpretation> interpretations,
            Path analysisVcf,
            GenomeAssembly analysisAssembly,
            String sampleId) {
        List<NormalizedVariantKey> candidateKeys = new ArrayList<NormalizedVariantKey>();
        for (VariantInterpretation interpretation : interpretations) {
            if (interpretation == null || interpretation.getObservation() == null
                    || interpretation.getObservation().getKey() == null) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CANDIDATE_KEY_MISSING");
            }
            candidateKeys.add(interpretation.getObservation().getKey());
        }
        var indexed = analysisVcfAlleleIndex.indexWithSource(
                analysisVcf,
                analysisAssembly,
                sampleId,
                candidateKeys
        );
        Map<NormalizedVariantKey, AnalysisVcfCall> calls = indexed.calls();
        List<VariantInterpretation> hydrated = new ArrayList<VariantInterpretation>(
                interpretations.size()
        );
        for (VariantInterpretation interpretation : interpretations) {
            NormalizedVariantKey key = interpretation.getObservation().getKey();
            AnalysisVcfCall call = calls.get(key);
            if (call == null) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CALL_INDEX_INCOMPLETE");
            }
            hydrated.add(interpretation.withAnalysisVcfCall(call));
        }
        return new HydratedCandidates(hydrated, indexed);
    }

    private record HydratedCandidates(List<VariantInterpretation> candidates, AnalysisVcfAlleleIndex.IndexedCalls indexed) { }

    private String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String hex(byte[] digest) {
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isAnalysisVcfFailure(IllegalStateException error) {
        return error.getMessage() != null
                && (error.getMessage().startsWith("ANALYSIS_VCF_")
                || error.getMessage().startsWith("ANALYSIS_VCF_CALL_"));
    }
}
