package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;

public class BenchmarkManifestCodec {

    private final ObjectMapper objectMapper;

    public BenchmarkManifestCodec() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public BenchmarkManifestCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules()
                : objectMapper.copy().findAndRegisterModules();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String toJson(BenchmarkDatasetManifest manifest) throws IOException {
        BenchmarkManifestValidator.requireValid(manifest);
        return safeJson(objectMapper.writeValueAsString(manifest));
    }

    public String toJson(BenchmarkRunManifest manifest) throws IOException {
        BenchmarkManifestValidator.requireValid(manifest);
        return safeJson(objectMapper.writeValueAsString(manifest));
    }

    public String toJson(BenchmarkCaseResultManifest manifest) throws IOException {
        BenchmarkManifestValidator.requireValid(manifest);
        return safeJson(objectMapper.writeValueAsString(manifest));
    }

    public String toJson(BenchmarkTruthPolicyManifest manifest) throws IOException {
        BenchmarkTruthPolicyValidator.requireValid(manifest);
        return safeJson(objectMapper.writeValueAsString(manifest));
    }

    public BenchmarkDatasetManifest readDataset(Path path) throws IOException {
        BenchmarkDatasetManifest manifest = objectMapper.readValue(path.toFile(), BenchmarkDatasetManifest.class);
        BenchmarkManifestValidator.requireValid(manifest);
        return manifest;
    }

    public BenchmarkRunManifest readRun(Path path) throws IOException {
        BenchmarkRunManifest manifest = objectMapper.readValue(path.toFile(), BenchmarkRunManifest.class);
        BenchmarkManifestValidator.requireValid(manifest);
        return manifest;
    }

    public BenchmarkCaseResultManifest readCaseResult(Path path) throws IOException {
        BenchmarkCaseResultManifest manifest = objectMapper.readValue(path.toFile(), BenchmarkCaseResultManifest.class);
        BenchmarkManifestValidator.requireValid(manifest);
        return manifest;
    }

    public BenchmarkDatasetManifest readDatasetJson(String json) throws IOException {
        BenchmarkDatasetManifest manifest = objectMapper.readValue(json, BenchmarkDatasetManifest.class);
        BenchmarkManifestValidator.requireValid(manifest);
        return manifest;
    }

    public BenchmarkRunManifest readRunJson(String json) throws IOException {
        BenchmarkRunManifest manifest = objectMapper.readValue(json, BenchmarkRunManifest.class);
        BenchmarkManifestValidator.requireValid(manifest);
        return manifest;
    }

    public BenchmarkCaseResultManifest readCaseResultJson(String json) throws IOException {
        BenchmarkCaseResultManifest manifest = objectMapper.readValue(json, BenchmarkCaseResultManifest.class);
        BenchmarkManifestValidator.requireValid(manifest);
        return manifest;
    }

    public BenchmarkTruthPolicyManifest readTruthPolicyJson(String json) throws IOException {
        BenchmarkTruthPolicyManifest manifest = objectMapper.readValue(json, BenchmarkTruthPolicyManifest.class);
        BenchmarkTruthPolicyValidator.requireValid(manifest);
        return manifest;
    }

    public BenchmarkTruthPolicyManifest readTruthPolicy(Path path) throws IOException {
        BenchmarkTruthPolicyManifest manifest = objectMapper.readValue(path.toFile(), BenchmarkTruthPolicyManifest.class);
        BenchmarkTruthPolicyValidator.requireValid(manifest);
        return manifest;
    }

    private String safeJson(String json) {
        if (json.matches("(?s).*\\b[A-Za-z]:[\\\\/][^/].*|.*/etc/.*|.*/home/.*")) {
            throw new BenchmarkManifestValidationException(java.util.Collections.singletonList(
                    new BenchmarkValidationError("UNSAFE_PATH", "$", "manifest contains an absolute machine path")
            ));
        }
        if (json.matches("(?is).*(hostname|username|password|secret|access[_-]?token).*")) {
            throw new BenchmarkManifestValidationException(java.util.Collections.singletonList(
                    new BenchmarkValidationError("SENSITIVE_FIELD", "$", "manifest contains a forbidden machine or credential field")
            ));
        }
        return json + "\n";
    }
}
