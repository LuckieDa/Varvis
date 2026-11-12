package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Server-owned, per-job configuration snapshot. No database migration or client template paths. */
public final class AnalysisScopePolicy {
    public enum Scope { STANDARD, INCLUDE_INTRONS, SINGLE_VARIANT_REVIEW }
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE = "analysis-scope-v1.json";
    private static final String EXECUTED = "analysis-scope-executed.sha256";
    public static final String ZERO_PASS_COVERAGE = "EXOMISER_15_1_ZERO_PASS_V1";
    public record Policy(Scope scope, String policyId, String policySha256, String effectiveYaml,
                         String outputCoveragePolicy) {
        public Policy {
            if (outputCoveragePolicy != null && (!ZERO_PASS_COVERAGE.equals(outputCoveragePolicy)
                    || (scope != Scope.INCLUDE_INTRONS && scope != Scope.SINGLE_VARIANT_REVIEW)))
                throw new IllegalArgumentException("ANALYSIS_OUTPUT_COVERAGE_POLICY_INVALID");
        }
        public Policy(Scope scope, String id, String sha, String yaml) { this(scope, id, sha, yaml, null); }
        public boolean supplementsZeroPassed() {
            return ZERO_PASS_COVERAGE.equals(outputCoveragePolicy);
        }
    }
    public static Scope parse(String value) {
        if (value == null) return Scope.STANDARD;
        try { return Scope.valueOf(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("ANALYSIS_SCOPE_INVALID"); }
    }
    @SuppressWarnings("unchecked")
    public static Policy resolve(VariantAnalysisProperties.Exomiser settings, Scope scope, boolean hpo) throws java.io.IOException {
        String yaml = ExomiserRequestWriter.analysisYaml(settings.getAnalysisTemplate(), hpo);
        String id = "VARVIS_STANDARD_V1", derivative = "", coverage = null;
        boolean singleVariant = scope == Scope.SINGLE_VARIANT_REVIEW;
        if (singleVariant) yaml = singleVariantReviewYaml(yaml);
        if (scope == Scope.INCLUDE_INTRONS || singleVariant) {
            try (var input = AnalysisScopePolicy.class.getResourceAsStream("/exomiser/analysis-scope-introns-v1.json")) {
                derivative = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
                var policy = MAPPER.readTree(derivative);
                id = singleVariant ? "VARVIS_SINGLE_VARIANT_REVIEW_V2" : policy.path("policyId").asText();
                coverage = singleVariant ? ZERO_PASS_COVERAGE : policy.path("outputCoveragePolicy").asText(null);
                var codec = new Yaml(new SafeConstructor(new LoaderOptions()));
                Map<String,Object> document = codec.load(yaml);
                if (coverage != null && (document.containsKey("outputOptions") || document.containsKey("output") || document.containsKey("analysis")))
                    throw new IllegalArgumentException("ANALYSIS_OUTPUT_COVERAGE_SETTINGS_UNSUPPORTED");
                boolean effect = false, pathogenicity = false;
                for (var step : (List<Map<String,Object>>) document.get("steps")) {
                    if (step.containsKey("variantEffectFilter")) {
                        var filter = (Map<String,Object>) step.get("variantEffectFilter");
                        var remove = (List<String>) filter.get("remove");
                        remove.removeIf(policy.path("removeFromVariantEffectExclusions").asText()::equals); effect = true;
                    }
                    if (step.containsKey("pathogenicityFilter")) {
                        var filter = (Map<String,Object>) step.get("pathogenicityFilter");
                        if ("NON_CODING".equals(filter.get("target"))) filter.put("keepNonPathogenic", true);
                        else if (!Boolean.TRUE.equals(filter.get("keepNonPathogenic")))
                            throw new IllegalArgumentException("INTRON_SCOPE_PATHOGENICITY_POLICY_UNSUPPORTED");
                        pathogenicity = true;
                    }
                }
                if (!effect || !pathogenicity) throw new IllegalArgumentException("INTRON_SCOPE_TEMPLATE_UNSUPPORTED");
                String header = singleVariant
                        ? "# Varvis SINGLE_VARIANT_REVIEW: frequency caps of 100 percent and coding-transcript intron review.\n"
                        : "# Varvis INCLUDE_INTRONS: retains low/missing precomputed scores; other filters preserved.\n";
                yaml = header + codec.dump(document);
            }
        }
        return new Policy(scope, id, sha(scope.name()+"\n"+id+"\n"+derivative+"\n"+yaml), yaml, coverage);
    }

    private static String singleVariantReviewYaml(String yaml) {
        String unsupported = "SINGLE_VARIANT_REVIEW_TEMPLATE_UNSUPPORTED";
        var loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        var codec = new Yaml(new SafeConstructor(loader));
        Object parsed;
        try {
            parsed = codec.load(yaml);
        } catch (YAMLException invalid) {
            throw new IllegalArgumentException(unsupported, invalid);
        }
        if (!(parsed instanceof Map<?, ?> document) || !"PASS_ONLY".equals(document.get("analysisMode"))
                || document.containsKey("outputOptions") || document.containsKey("output") || document.containsKey("analysis")
                || !(document.get("inheritanceModes") instanceof Map<?, ?> modes) || modes.isEmpty()
                || !(document.get("steps") instanceof List<?> steps)) {
            throw new IllegalArgumentException(unsupported);
        }
        Map<Object, Object> frequencies = new LinkedHashMap<>();
        for (var mode : modes.entrySet()) {
            if (!(mode.getValue() instanceof Number)) throw new IllegalArgumentException(unsupported);
            frequencies.put(mode.getKey(), 100.0);
        }
        List<Object> reviewSteps = new ArrayList<>(steps);
        boolean frequencyFound = false;
        for (int i = 0; i < steps.size(); i++) {
            if (!(steps.get(i) instanceof Map<?, ?> step) || !step.containsKey("frequencyFilter")) continue;
            if (frequencyFound || !(step.get("frequencyFilter") instanceof Map<?, ?> filter)
                    || !(filter.get("maxFrequency") instanceof Number)) {
                throw new IllegalArgumentException(unsupported);
            }
            Map<Object, Object> reviewFilter = new LinkedHashMap<>(filter);
            reviewFilter.put("maxFrequency", 100.0);
            Map<Object, Object> reviewStep = new LinkedHashMap<>(step);
            reviewStep.put("frequencyFilter", reviewFilter);
            reviewSteps.set(i, reviewStep);
            frequencyFound = true;
        }
        if (!frequencyFound) throw new IllegalArgumentException(unsupported);
        Map<Object, Object> review = new LinkedHashMap<>(document);
        review.put("inheritanceModes", frequencies);
        review.put("steps", reviewSteps);
        return codec.dump(review);
    }
    public static void save(VariantAnalysisJob job, Policy policy) throws java.io.IOException {
        var value = MAPPER.createObjectNode().put("version", "analysis-scope-v1").put("analysisId", job.getId())
                .put("uploadSha256", job.getFileSha256()).put("scope", policy.scope().name()).put("policyId", policy.policyId())
                .put("policySha256", policy.policySha256()).put("effectiveYaml", policy.effectiveYaml());
        if (policy.outputCoveragePolicy() != null) value.put("outputCoveragePolicy", policy.outputCoveragePolicy());
        value.put("snapshotSha256", sha(value.toString()));
        Files.writeString(job.getWorkspace().resolve(FILE), value.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    public static Policy read(VariantAnalysisJob job) throws java.io.IOException {
        Path path = job.getWorkspace().resolve(FILE);
        if (!Files.exists(path)) return null; // Historical STANDARD; policy is unproven and cannot hit the new reuse cache.
        if (Files.size(path) > 256*1024) throw new IllegalArgumentException("ANALYSIS_SCOPE_SNAPSHOT_INVALID");
        var value = (ObjectNode) MAPPER.readTree(Files.readString(path));
        String digest = value.path("snapshotSha256").asText(); value.remove("snapshotSha256");
        if (!digest.equals(sha(value.toString())) || !"analysis-scope-v1".equals(value.path("version").asText())
                || !job.getId().equals(value.path("analysisId").asText()) || !job.getFileSha256().equals(value.path("uploadSha256").asText())
                || !value.path("policySha256").asText().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("ANALYSIS_SCOPE_SNAPSHOT_INVALID");
        return new Policy(parse(value.path("scope").asText()), value.path("policyId").asText(), value.path("policySha256").asText(), value.path("effectiveYaml").asText(), value.path("outputCoveragePolicy").asText(null));
    }
    static String forExecution(VariantAnalysisJob job, VariantAnalysisProperties.Exomiser settings, boolean hpo) throws java.io.IOException {
        Policy policy = read(job);
        if (policy == null) return resolve(settings, Scope.STANDARD, hpo).effectiveYaml();
        // The submitted snapshot is the executed policy, even if an operator changes the live template while queued.
        Files.writeString(job.getWorkspace().resolve(EXECUTED), policy.policySha256(), StandardCharsets.UTF_8);
        return policy.effectiveYaml();
    }
    public static boolean reusable(VariantAnalysisJob job, Policy current) {
        try {
            Policy saved = read(job);
            return current != null && saved != null && saved.scope() == current.scope()
                    && saved.policySha256().equals(current.policySha256())
                    && Files.readString(job.getWorkspace().resolve(EXECUTED)).equals(saved.policySha256());
        } catch (Exception missing) { return false; }
    }
    public static Map<String,String> publicDetails(VariantAnalysisJob job) {
        try {
            Policy saved = read(job);
            return saved == null ? Map.of("scope", "STANDARD", "status", "LEGACY_POLICY_UNATTESTED")
                    : Map.of("scope", saved.scope().name(), "status", "RECORDED", "policyId", saved.policyId(), "policySha256", saved.policySha256());
        } catch (Exception invalid) { return Map.of("scope", "UNKNOWN", "status", "ANALYSIS_SCOPE_SNAPSHOT_INVALID"); }
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
