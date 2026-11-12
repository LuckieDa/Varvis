package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LiteratureSubmissionReceiptsTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    Path config() throws IOException {
        Path p = root.resolve("config.json");
        Files.write(p,json.writeValueAsBytes(json.createObjectNode().put("projectRoot",root.toString()).put("work","local/work")));
        return p;
    }
    @Test void receiptSurvivesRestartAndNeverStartsSameOperationTwice() throws Exception {
        Path config = config(); var receipts = new LiteratureSubmissionReceipts(config);
        String operation = UUID.randomUUID().toString(); var calls = new AtomicInteger();
        byte[] request = "private request".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        receipts.start("analysis",operation,"pp1",request,() -> {
            calls.incrementAndGet();
            assertEquals("STARTING",receipts.read("analysis",operation).path("status").asText());
            return json.createObjectNode().put("taskId","a".repeat(64)).put("status","QUEUED");
        });
        var recovered = new LiteratureSubmissionReceipts(config).read("analysis",operation);
        assertEquals("ACKNOWLEDGED",recovered.path("status").asText());
        assertEquals(FamilyDeNovoJson.sha256(request),recovered.path("requestSha256").asText());
        assertFalse(recovered.toString().contains("private request"));
        assertEquals("NOT_FOUND",receipts.read("other-analysis",operation).path("status").asText());
        assertThrows(IOException.class,() -> receipts.start("analysis",operation,"pp1",request,() -> { calls.incrementAndGet(); return recovered; }));
        assertEquals(1,calls.get());
    }
    @Test void uncertainFailureStaysPendingAndInvalidPathsAreRejected() throws Exception {
        var receipts = new LiteratureSubmissionReceipts(config()); String operation = UUID.randomUUID().toString();
        assertThrows(IOException.class,() -> receipts.start("a",operation,"pm3",new byte[]{1},() -> { throw new IOException("uncertain"); }));
        assertEquals("STARTING",receipts.read("a",operation).path("status").asText());
        assertThrows(IOException.class,() -> receipts.read("a","../../bad"));
    }
}
