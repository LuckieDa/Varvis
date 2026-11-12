package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** One task-owned ledger per isolated Spring context. No scores, observations or shared cache. */
@Component
@ConditionalOnExpression("${variant-analysis.acmg-business.am-phase-budget-enabled:false} && !${variant-analysis.acmg-business.saved-observation-association-only:false} && !${variant-analysis.acmg-business.saved-observation-calibration-only:false}")
public final class AlphaMissensePhaseBudget {
    public static final String LEGACY = "LEGACY_ANNOTATION", RAW = "EXPLICIT_RAW", IDENTITY = "IDENTITY_ONLY";
    private final String taskId, resourceIdentity;
    private final Set<String> allowed;
    private final BiFunction<Path, NormalizedVariantKey, String> resourceGate;
    private final Path receipt;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Integer> invocations = new LinkedHashMap<>(), transports = new LinkedHashMap<>(), cacheHits = new LinkedHashMap<>();
    private final Set<String> reserved = new LinkedHashSet<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private String analysisId, stoppedReason;

    @org.springframework.beans.factory.annotation.Autowired
    public AlphaMissensePhaseBudget(ObjectMapper mapper, VariantAnalysisProperties properties, Environment env) {
        String prefix = "variant-analysis.acmg-business.";
        if (!"PP3_BP4_DEVELOPMENT".equals(env.getProperty(prefix + "acceptance-scope"))
                || !properties.getVep().isOwnedExecutionEnabled()) throw new IllegalArgumentException("AM_BUDGET_ISOLATION_REQUIRED");
        var binding = PredictionLocalResourceBinding.load(mapper,
                Path.of(env.getRequiredProperty(prefix + "prediction-local-binding-file")),
                env.getRequiredProperty(prefix + "prediction-local-binding-sha256"));
        taskId = env.getRequiredProperty(prefix + "am-phase-budget-task-id");
        resourceIdentity = binding.identity().path("sha256").asText() + ":" + binding.identity().path("indexSha256").asText();
        allowed = binding.registeredControls().stream().map(AlphaMissensePhaseBudget::allele).collect(java.util.stream.Collectors.toUnmodifiableSet());
        resourceGate = (file, key) -> binding.issue(file, key, binding.identity(), "SYNTHETIC");
        receipt = Path.of(env.getRequiredProperty(prefix + "am-phase-budget-receipt-file"));
        initialize();
    }
    AlphaMissensePhaseBudget(String task, String resource, Set<NormalizedVariantKey> controls, Path receipt) {
        taskId = task; resourceIdentity = resource; allowed = controls.stream().map(AlphaMissensePhaseBudget::allele).collect(java.util.stream.Collectors.toUnmodifiableSet());
        resourceGate = (file, key) -> null; this.receipt = receipt; initialize();
    }
    private void initialize() {
        if (taskId.isBlank() || allowed.size() != 2) throw new IllegalArgumentException("AM_TWO_CONTROL_BUDGET_REQUIRED");
        try { Files.writeString(receipt, "{}", StandardOpenOption.CREATE_NEW); persist(); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("AM_BUDGET_RECEIPT_MUST_BE_NEW", failure); }
    }
    public synchronized void invocation(String purpose, String analysis) {
        if (stoppedReason != null) throw new Exceeded(stoppedReason);
        if (analysis != null && !analysis.isBlank()) {
            if (analysisId != null && !analysisId.equals(analysis)) stop("AM_BUDGET_ANALYSIS_MISMATCH");
            analysisId = analysis;
        }
        invocations.put(purpose, invocations.getOrDefault(purpose, 0) + 1); persist();
        if (!Set.of(LEGACY, RAW).contains(purpose)) stop("AM_PHASE_NOT_AUTHORIZED_" + purpose);
    }
    public synchronized void cacheHit(String purpose, NormalizedVariantKey key) {
        check(purpose, key); cacheHits.put(purpose, cacheHits.getOrDefault(purpose, 0) + 1); persist();
    }
    /** Reserve before opening the indexed resource. Failed/empty/time-out reads do not refund. */
    public synchronized void reserve(String purpose, NormalizedVariantKey key, Path file) {
        check(purpose, key);
        String gate = resourceGate.apply(file, key);
        if (gate != null) stop(gate);
        String id = purpose + "|" + allele(key);
        if (reserved.contains(id) || transports.getOrDefault(purpose, 0) >= 2
                || transports.getOrDefault(LEGACY, 0) + transports.getOrDefault(RAW, 0) >= 4) stop("AM_PHASE_TRANSPORT_BUDGET_EXHAUSTED");
        reserved.add(id); transports.put(purpose, transports.getOrDefault(purpose, 0) + 1);
        var event = new LinkedHashMap<String, Object>();
        event.put("taskId", taskId); event.put("analysisId", analysisId == null ? "TASK_BOUND_BEFORE_ANALYSIS" : analysisId);
        event.put("purpose", purpose); event.put("queryAllele", allele(key)); event.put("resourceIdentity", resourceIdentity);
        event.put("reservedAt", java.time.Instant.now().toString()); event.put("transport", "LOCAL_HTSJDK_TABIX_READER");
        events.add(event); persist();
    }
    private void check(String purpose, NormalizedVariantKey key) {
        if (stoppedReason != null) throw new Exceeded(stoppedReason);
        if (!Set.of(LEGACY, RAW).contains(purpose) || key == null || !allowed.contains(allele(key))) stop("AM_BUDGET_PURPOSE_OR_ALLELE_NOT_REGISTERED");
    }
    private void stop(String reason) { stoppedReason = reason; persist(); throw new Exceeded(reason); }
    public synchronized Map<String, Object> snapshot() {
        var value = new LinkedHashMap<String, Object>(); value.put("schemaVersion", "am-task-phase-budget-v1");
        value.put("taskId", taskId); value.put("analysisId", analysisId == null ? "UNBOUND" : analysisId);
        value.put("resourceIdentity", resourceIdentity); value.put("invocations", Map.copyOf(invocations));
        value.put("transportAttempts", Map.copyOf(transports)); value.put("legacyCacheHits", Map.copyOf(cacheHits));
        value.put("trustedRawReuse", 0); value.put("events", List.copyOf(events));
        value.put("status", stoppedReason == null ? "ACTIVE" : "STOPPED"); value.put("stopReason", stoppedReason == null ? "" : stoppedReason);
        return value;
    }
    /** Write-once task evidence, never read as a cache and never used to authorize another lookup. */
    public synchronized void captureRaw(NormalizedVariantKey key, com.fasterxml.jackson.databind.node.ObjectNode observation) {
        check(RAW, key);
        try {
            String name = "am-raw-" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((taskId + "|" + allele(key) + "|" + resourceIdentity).getBytes(java.nio.charset.StandardCharsets.UTF_8))) + ".json";
            Files.write(receipt.resolveSibling(name), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(observation), StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { stop("AM_RAW_OBSERVATION_CAPTURE_FAILED"); }
    }
    private void persist() {
        try { Files.write(receipt, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot()), StandardOpenOption.TRUNCATE_EXISTING); }
        catch (java.io.IOException failure) { stoppedReason = "AM_BUDGET_RECEIPT_UNWRITABLE"; throw new Exceeded(stoppedReason); }
    }
    private static String allele(NormalizedVariantKey key) {
        return key.getGenomeAssembly() + "|" + key.getChromosome() + "|" + key.getPosition() + "|" + key.getReference() + "|" + key.getAlternate();
    }
    public static final class Exceeded extends IllegalStateException {
        Exceeded(String reason) { super(reason); }
    }
}
