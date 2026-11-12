package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;

/** Transport receipts contain only request hashes and task IDs, never case declarations or facts.
 * An uncertain submission is read back, not resubmitted. Survives server/browser restarts. */
public final class LiteratureSubmissionReceipts {
    private final Path configuration;
    private final ObjectMapper json = new ObjectMapper();
    public LiteratureSubmissionReceipts(Path configuration) { this.configuration = configuration; }
    @FunctionalInterface public interface Start { ObjectNode run() throws IOException; }

    private Path file(String analysis, String operation) throws IOException {
        if (operation == null || !operation.matches("[a-f0-9-]{36}") || analysis == null || analysis.length() > 100)
            throw new IOException("LITERATURE_SUBMISSION_ID_INVALID");
        var c = json.readTree(Files.readAllBytes(configuration));
        Path project = Path.of(c.path("projectRoot").asText()).toRealPath();
        Path work = project.resolve(c.path("work").asText()).normalize();
        if (!work.startsWith(project.resolve("local")) || work.equals(project.resolve("local")))
            throw new IOException("LITERATURE_CONFIG_PATH_INVALID");
        return work.resolve("submission-receipts").resolve(FamilyDeNovoJson.sha256(analysis.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .resolve(operation + ".json");
    }
    public ObjectNode read(String analysis, String operation) throws IOException {
        Path p = file(analysis, operation);
        if (!Files.isRegularFile(p)) return json.createObjectNode().put("operationId", operation).put("status", "NOT_FOUND");
        try (var in = Files.newInputStream(p)) {
            byte[] bytes = in.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("LITERATURE_SUBMISSION_RECEIPT_INVALID");
            var value = json.readTree(bytes);
            if (!(value instanceof ObjectNode result) || !operation.equals(result.path("operationId").asText()))
                throw new IOException("LITERATURE_SUBMISSION_RECEIPT_INVALID");
            return result;
        }
    }
    public ObjectNode start(String analysis, String operation, String lane, byte[] request, Start action) throws IOException {
        if (operation == null) return action.run(); // Existing explicit/manual clients retain their contract.
        Path p = file(analysis, operation); Files.createDirectories(p.getParent());
        var receipt = json.createObjectNode().put("operationId", operation).put("lane", lane)
                .put("requestSha256", FamilyDeNovoJson.sha256(request)).put("status", "STARTING");
        try { Files.write(p, json.writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException duplicate) { throw new IOException("LITERATURE_SUBMISSION_ALREADY_RECORDED_READ_RECEIPT"); }
        // Leave STARTING after any uncertain failure: a task might already exist. Never repeat action.
        ObjectNode task = action.run();
        String taskId = task.path("taskId").asText();
        if (!taskId.matches("[a-f0-9]{64}")) throw new IOException("LITERATURE_TASK_RECEIPT_INVALID");
        receipt.put("taskId", taskId).put("status", "ACKNOWLEDGED");
        Path next = p.resolveSibling(operation + ".next");
        Files.write(next, json.writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW);
        Files.move(next, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return task;
    }
}
