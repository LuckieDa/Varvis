package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded orchestration of the existing review bridge and preparer, with no new scientific decisions. */
public final class MissenseMaterialBatchPreparation {
    private final ObjectMapper mapper = MissenseMaterialIndex.strictMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    public record Result(boolean complete, ObjectNode report) { }

    public Result prepare(Path manifestPath, String pin, Path output) throws IOException {
        MissenseMaterialIndex.require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS), "OUTPUT_EXISTS_REFUSE_OVERWRITE");
        JsonNode manifest = mapper.readTree(MissenseMaterialIndex.read(manifestPath, pin, MissenseMaterialIndex.MAX_MANIFEST));
        if (!(manifest != null && "missense-material-batch-manifest-v1".equals(manifest.path("schemaVersion").asText())
                && manifest.path("entries").isArray() && !manifest.path("entries").isEmpty()
                && manifest.path("entries").size() <= MissenseMaterialIndex.MAX_ENTRIES)) {
            throw new IllegalArgumentException("BATCH_MANIFEST_INVALID");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode row : manifest.path("entries")) MissenseMaterialIndex.require(row.path("entryId").asText().matches("[A-Za-z0-9_-]{1,80}")
                && ids.add(row.path("entryId").asText()), "BATCH_ENTRY_ID_INVALID_OR_DUPLICATE");
        Files.createDirectory(output);
        ObjectNode report = mapper.createObjectNode().put("schemaVersion", "missense-material-batch-report-v1")
                .put("manifestRawSha256", pin).put("scientificAdoption", false).put("runtimeExecution", "NOT_RUN")
                .put("approvalSemantics", "REUSE_EXACT_EXTERNAL_APPROVAL_ONLY");
        ObjectNode index = mapper.createObjectNode().put("schemaVersion", "missense-material-index-v1");
        var outputs = index.putArray("entries"); var rows = report.putArray("entries");
        Path root = Objects.requireNonNull(manifestPath.toAbsolutePath().normalize().getParent());
        int prepared = 0;
        for (JsonNode task : manifest.path("entries")) {
            String id = task.path("entryId").asText(); Path rowRoot = output.resolve(id); Files.createDirectory(rowRoot);
            ObjectNode row = rows.addObject().put("entryId", id).put("status", "BLOCKED").put("artifactDirectory", id);
            try {
                MissenseMaterialIndex.require(task.has("bridgeManifest") != task.has("preparationManifest"), "EXACTLY_ONE_MATERIAL_INPUT_REQUIRED");
                JsonNode input = task.path(task.has("bridgeManifest") ? "bridgeManifest" : "preparationManifest");
                MissenseMaterialIndex.require(!input.path("path").asText().isBlank(), "MATERIAL_INPUT_PATH_REQUIRED");
                Path preparation = root.resolve(input.path("path").asText()); String preparationPin = input.path("rawSha256").asText();
                row.set("input", input.deepCopy());
                if (task.has("bridgeManifest")) {
                    var bridge = new MissenseReferenceReviewBridge().prepare(preparation, preparationPin, rowRoot.resolve("bridge"));
                    row.set("bridge", bridge.report());
                    if (!bridge.bridged()) { row.put("reasonCode", bridge.report().path("reasonCode").asText("REFERENCE_BRIDGE_BLOCKED")); continue; }
                    row.put("referenceSelectionFile", id + "/bridge/selection-map.json");
                    preparation = Objects.requireNonNull(bridge.manifestPath()); preparationPin = Objects.requireNonNull(bridge.manifestSha256());
                    if (task.has("approval")) {
                        JsonNode description = task.path("approval");
                        Path approvalPath = root.resolve(description.path("path").asText());
                        byte[] bytes = MissenseMaterialIndex.read(approvalPath, description.path("rawSha256").asText(), 1_000_000);
                        JsonNode approval = mapper.readTree(bytes);
                        MissenseMaterialIndex.require(approval != null && adapter.canonicalSha256(approval).equals(description.path("canonicalSha256").asText()), "APPROVAL_CANONICAL_SHA_MISMATCH");
                        ObjectNode seed = (ObjectNode) mapper.readTree(MissenseMaterialIndex.read(preparation, preparationPin, MissenseMaterialIndex.MAX_MANIFEST));
                        ObjectNode bound = description.deepCopy(); bound.put("path", approvalPath.toAbsolutePath().normalize().toString()); seed.set("approval", bound);
                        // Same directory retains all original bridge-relative file bindings.
                        preparation = preparation.resolveSibling("externally-approved-manifest.json");
                        write(preparation, seed); preparationPin = Pm4MaterialPreparation.sha(Files.readAllBytes(preparation));
                    }
                } else MissenseMaterialIndex.require(!task.has("approval"), "APPROVAL_MUST_BE_IN_PREPARATION_MANIFEST");
                var result = new MissenseMaterialPreparation().prepare(preparation, preparationPin, rowRoot.resolve("prepared"));
                row.set("preparation", result.report());
                row.put("status", result.report().path("status").asText());
                if (!result.ready()) { row.put("reasonCode", result.report().path("reasonCode").asText("REFERENCE_PREPARATION_BLOCKED")); continue; }
                Path bundle = rowRoot.resolve("prepared/bundle.json"); byte[] bytes = Files.readAllBytes(bundle);
                outputs.addObject().put("entryId", id).put("criterion", result.report().path("criterion").asText())
                        .put("path", id + "/prepared/bundle.json").put("rawSha256", Pm4MaterialPreparation.sha(bytes))
                        .put("canonicalSha256", result.report().path("bundleSha256").asText());
                prepared++;
            } catch (IllegalArgumentException | IOException error) { row.put("status", "BLOCKED").put("reasonCode", MissenseMaterialIndex.reason(error)); }
        }
        report.put("preparedCount", prepared).put("blockedCount", rows.size() - prepared)
                .put("status", prepared == rows.size() ? "PREPARED" : prepared == 0 ? "BLOCKED" : "PARTIAL");
        Path staged = output.resolve("qualified-index.pending.json"); write(staged, index);
        String indexPin = Pm4MaterialPreparation.sha(Files.readAllBytes(staged));
        try {
            MissenseMaterialIndex.load(staged, indexPin);
            report.put("indexStatus", "VALIDATED").put("indexRawSha256", indexPin);
        } catch (IllegalArgumentException | IOException invalid) {
            report.put("indexStatus", "BLOCKED").put("status", "BLOCKED").put("indexReasonCode", MissenseMaterialIndex.reason(invalid));
        }
        write(output.resolve("batch-report.json"), report);
        if ("VALIDATED".equals(report.path("indexStatus").asText())) Files.move(staged, output.resolve("qualified-index.json"));
        return new Result("PREPARED".equals(report.path("status").asText()), report);
    }

    private void write(Path path, JsonNode value) throws IOException {
        Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Exception {
        int code = 2;
        try {
            MissenseMaterialIndex.require(args.length == 6 && "--manifest".equals(args[0])
                    && "--manifest-sha256".equals(args[2]) && "--output".equals(args[4]), "CLI_ARGUMENTS_INVALID");
            Result result = new MissenseMaterialBatchPreparation().prepare(Path.of(args[1]), args[3], Path.of(args[5]));
            System.out.println(result.report().toPrettyString()); code = result.complete() ? 0 : 2;
        } catch (IllegalArgumentException | IOException invalid) {
            System.out.println("{\"status\":\"BLOCKED\",\"reasonCode\":\"" + MissenseMaterialIndex.reason(invalid) + "\"}");
        }
        System.exit(code);
    }
}
