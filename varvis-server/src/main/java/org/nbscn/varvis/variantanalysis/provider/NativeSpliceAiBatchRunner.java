package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Windows job ownership includes the venv launcher and its interpreter. No legacy WSL identity. */
public final class NativeSpliceAiBatchRunner {
    public static final String VERSION = "1.1.0";
    private final ObjectMapper mapper;
    private final List<String> command;
    private final Path reference, binding, working;
    private final Duration timeout;
    private final ObjectNode identity;
    private final String bindingSha;

    public NativeSpliceAiBatchRunner(ObjectMapper mapper, List<String> command, Path reference, Path binding,
            String bindingSha, Path working, Duration timeout) throws java.io.IOException {
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.command = List.copyOf(command); this.reference = reference; this.binding = binding;
        this.working = working; this.timeout = timeout; this.bindingSha = bindingSha;
        require(command.size() == 2 && command.stream().allMatch(s -> !s.isBlank() && Files.isRegularFile(Path.of(s))), "NATIVE_COMMAND_INVALID");
        require(timeout.toSeconds() >= 1 && timeout.toSeconds() <= 600, "NATIVE_TIMEOUT_INVALID");
        require(Files.size(binding) <= 65536, "NATIVE_BINDING_TOO_LARGE");
        byte[] bytes = Files.readAllBytes(binding);
        require(CodingTranscriptMaterialSupply.sha(bytes).equals(bindingSha), "NATIVE_BINDING_HASH_MISMATCH");
        var parsed = this.mapper.readTree(bytes);
        if (!(parsed instanceof ObjectNode bindingIdentity)
                || !"spliceai-native-business-identity-v1".equals(bindingIdentity.path("schemaVersion").asText()))
            throw new IllegalArgumentException("NATIVE_BINDING_INVALID");
        String version = bindingIdentity.path("version").asText();
        var distance = bindingIdentity.path("executionDistanceBp");
        var mask = bindingIdentity.path("mask");
        boolean validWideBinding = "1.2.0".equals(version) && distance.isIntegralNumber() && distance.asInt() == 10000
                && mask.isIntegralNumber() && mask.asInt() == 0;
        require(VERSION.equals(version) || validWideBinding, "NATIVE_BINDING_INVALID");
        identity = bindingIdentity;
        require(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(Path.of(command.get(1))))
                .equals(identity.path("adapterSha256").asText()), "NATIVE_ADAPTER_HASH_MISMATCH");
    }

    public ObjectNode identity() { return identity.deepCopy(); }
    Duration timeout() { return timeout; }

    public ObjectNode run(ObjectNode request) {
        return run(request, timeout);
    }

    /** The caller's remaining whole-selection budget includes setup, owner queueing and process teardown. */
    public ObjectNode run(ObjectNode request, Duration remainingBudget) {
        long started = System.nanoTime();
        var failed = mapper.createObjectNode().put("status", "UNAVAILABLE");
        try {
            Duration budget = remainingBudget.compareTo(timeout) < 0 ? remainingBudget : timeout;
            require(budget.toMillis() >= 1, "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED");
            require(!Thread.currentThread().isInterrupted(), "NATIVE_SPLICE_INTERRUPTED");
            require("spliceai-native-selected-request-v1".equals(request.path("requestSchemaVersion").asText())
                    && request.path("variants").isArray() && !request.path("variants").isEmpty()
                    && request.path("variants").size() <= 10, "NATIVE_BATCH_LIMIT_EXCEEDED");
            require(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(binding)).equals(bindingSha), "NATIVE_BINDING_CHANGED");
            Files.createDirectories(working);
            Path task = Files.createTempDirectory(working, "native-splice-");
            Path input = task.resolve("request.json"), output = task.resolve("result.json"), log = task.resolve("stdout.log"), error = task.resolve("stderr.log");
            mapper.writeValue(input.toFile(), request);
            var argv = new ArrayList<>(command);
            for (var pair : List.of(Map.entry("--reference-fasta", reference), Map.entry("--binding", binding),
                    Map.entry("--batch-request", input), Map.entry("--output", output))) {
                argv.add(pair.getKey()); argv.add(pair.getValue().toAbsolutePath().toString());
            }
            var spec = new OwnedQueryExecution.CommandSpec(argv, task, Map.of("PYTHONNOUSERSITE", "1", "PYTHONDONTWRITEBYTECODE", "1"),
                    log, error, "NATIVE_SPLICEAI_" + bindingSha,
                    // Python's launcher can signal before interpreter/helper teardown. Wait for
                    // the entire owned job to empty within the original deadline; never accept
                    // root exit alone. Deadline/interruption still terminate the whole job.
                    Map.of(output, 2_097_152L, log, 1_048_576L, error, 1_048_576L), false, true);
            long remainingMillis = (budget.toNanos() - (System.nanoTime() - started)) / 1_000_000L;
            require(remainingMillis >= 1, "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED");
            var ticket = OwnedQueryExecution.WINDOWS.launch(spec, remainingMillis, 1);
            OwnedQueryExecution.Outcome outcome;
            try { outcome = OwnedQueryExecution.await(ticket); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); failed.set("processReceipt", mapper.valueToTree(ticket.receipt()));
                return failed.put("reasonCode", "NATIVE_SPLICE_INTERRUPTED");
            }
            failed.set("processReceipt", mapper.valueToTree(ticket.receipt()));
            require(Boolean.TRUE.equals(ticket.receipt().get("ownedProcessesStopped")), "NATIVE_PROCESS_CLEANUP_UNCONFIRMED");
            require(ticket.cancellation.get() == null, "NATIVE_SPLICE_" + (ticket.cancellation.get() == null ? "CANCELLED" : ticket.cancellation.get().trigger()));
            require(outcome.exitCode() == 0 && outcome.failure().isEmpty(), "NATIVE_SPLICE_PROCESS_FAILED");
            require(Files.isRegularFile(output) && Files.size(output) <= 2_097_152, "NATIVE_RESULT_MISSING_OR_TOO_LARGE");
            byte[] rawBytes = Files.readAllBytes(output);
            var result = mapper.readTree(rawBytes);
            require(System.nanoTime() - started < budget.toNanos(), "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED");
            require(result instanceof ObjectNode && "spliceai-native-business-result-v1".equals(result.path("resultSchemaVersion").asText())
                    && "SUCCEEDED".equals(result.path("status").asText()) && identity.equals(result.path("identity"))
                    && request.equals(result.path("request")) && request.path("requestId").equals(result.path("requestId")), "NATIVE_RESULT_IDENTITY_MISMATCH");
            var out = ((ObjectNode) result).deepCopy(); out.put("rawOutputSha256", CodingTranscriptMaterialSupply.sha(rawBytes));
            out.set("processReceipt", failed.path("processReceipt")); return out;
        } catch (Exception blocked) {
            return failed.put("reasonCode", blocked instanceof IllegalArgumentException ? blocked.getMessage() : "NATIVE_SPLICE_EXECUTION_UNAVAILABLE");
        }
    }

    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
