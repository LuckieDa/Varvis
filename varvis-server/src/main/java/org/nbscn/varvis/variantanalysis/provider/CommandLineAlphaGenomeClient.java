package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.config.AlphaGenomeEnvironment;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class CommandLineAlphaGenomeClient implements AlphaGenomeClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() { };

    private final VariantAnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public CommandLineAlphaGenomeClient(VariantAnalysisProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> score(NormalizedVariantKey key) throws IOException {
        VariantAnalysisProperties.AlphaGenome config = properties.getAlphaGenome();
        Path script = config.getScriptPath();
        if (!StringUtils.hasText(config.getPythonExecutable())) {
            throw new IOException("AlphaGenome Python executable is not configured");
        }
        if (script == null || !Files.isRegularFile(script)) {
            throw new IOException("AlphaGenome adapter script is unavailable");
        }
        Path stdout = Files.createTempFile("varvis-alphagenome-", ".json");
        Path stderr = Files.createTempFile("varvis-alphagenome-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command(config, script, key));
            String apiKey = AlphaGenomeEnvironment.apiKey();
            if (StringUtils.hasText(apiKey)) {
                builder.environment().put(AlphaGenomeEnvironment.API_KEY_ENV, apiKey);
            }
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            Process process = builder.start();
            boolean completed;
            try {
                completed = process.waitFor(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("AlphaGenome lookup was interrupted", ex);
            }
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("AlphaGenome lookup timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("AlphaGenome adapter failed: " + readLimited(stderr));
            }
            return objectMapper.readValue(stdout.toFile(), MAP_TYPE);
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private List<String> command(VariantAnalysisProperties.AlphaGenome config,
                                 Path script,
                                 NormalizedVariantKey key) {
        List<String> command = new ArrayList<String>();
        command.add(config.getPythonExecutable());
        command.add(script.toAbsolutePath().normalize().toString());
        command.add("--chromosome");
        command.add(key.getChromosome());
        command.add("--position");
        command.add(String.valueOf(key.getPosition()));
        command.add("--reference");
        command.add(key.getReference());
        command.add("--alternate");
        command.add(key.getAlternate());
        command.add("--sequence-length");
        command.add(String.valueOf(config.getSequenceLength()));
        return command;
    }

    private String readLimited(Path path) throws IOException {
        String value = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
