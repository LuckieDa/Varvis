package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Standalone store reader and full JSON exporter; never reconstructs a service or invokes an evaluator. */
public final class Pvs1UnifiedEighteenHistoryReader {
    private Pvs1UnifiedEighteenHistoryReader() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 4 && !args[1].isBlank() && !args[2].isBlank(), "ARGUMENTS_INVALID");
        Path directory = Path.of(args[0]).toRealPath(), output = Path.of(args[3]).toRealPath();
        require(Files.isDirectory(directory) && Files.isDirectory(output)
                && Files.isRegularFile(directory.resolve("business-db.mv.db")), "EXISTING_DATABASE_REQUIRED");
        var mapper = new ObjectMapper().findAndRegisterModules().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        Instant started = Instant.now();
        byte[] expectedPublic = Files.readAllBytes(output.resolve("expected-public.json"));
        byte[] expectedPrivate = Files.readAllBytes(output.resolve("expected-private.json"));
        byte[] expectedRequest = Files.readAllBytes(output.resolve("expected-request.json"));
        var ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("business-db").toString().replace('\\', '/')
                + ";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
        ObjectNode receipt;
        try {
            var store = new AcmgBusinessStore(ds);
            var saved = store.run(args[1], args[2]).orElseThrow(() -> new IllegalStateException("PVS1_UNIFIED18_READER_RUN_NOT_FOUND"));
            require(args[1].equals(saved.analysisId()) && args[2].equals(saved.id()) && saved.status() != null
                    && !"RUNNING".equals(saved.status()), "SAVED_RUN_IDENTITY_MISMATCH");
            String responseJson = saved.responseJson(), privateJson = saved.privateJson(), requestJson = saved.requestJson();
            if (responseJson == null || privateJson == null || requestJson == null) throw new IllegalStateException("PVS1_UNIFIED18_READER_COMPLETE_RUN_REQUIRED");
            byte[] publicBytes = responseJson.getBytes(StandardCharsets.UTF_8), privateBytes = privateJson.getBytes(StandardCharsets.UTF_8),
                    requestBytes = requestJson.getBytes(StandardCharsets.UTF_8);
            require(Arrays.equals(expectedPublic, publicBytes), "PUBLIC_BYTES_CHANGED");
            require(Arrays.equals(expectedPrivate, privateBytes), "PRIVATE_BYTES_CHANGED");
            require(Arrays.equals(expectedRequest, requestBytes), "REQUEST_BYTES_CHANGED");
            JsonNode response = mapper.readTree(publicBytes), privateData = mapper.readTree(privateBytes), request = mapper.readTree(requestBytes);
            require(response != null && response.isObject() && privateData != null && privateData.isObject()
                    && request != null && request.isObject(), "JSON_DOCUMENT_INVALID");
            if (response == null || privateData == null || request == null) throw new IllegalStateException("PVS1_UNIFIED18_READER_JSON_DOCUMENT_INVALID");
            String responseSha = AcmgBusinessStore.sha(responseJson), privateSha = AcmgBusinessStore.sha(privateJson),
                    requestRawSha = AcmgBusinessStore.sha(requestJson);
            // The product stores the canonical request identity, independently of its serialized UTF-8 byte digest.
            String requestIdentitySha = new AcmgBusinessArtifacts(mapper).requestHash(request);
            require(responseSha.equals(saved.responseSha256()), "STORED_RESPONSE_SHA_MISMATCH");
            require(requestIdentitySha.equals(saved.requestSha256()), "STORED_REQUEST_IDENTITY_SHA_MISMATCH");
            require(saved.id().equals(response.path("runId").asText()) && saved.analysisId().equals(response.path("analysisId").asText())
                    && saved.status().equals(response.path("status").asText())
                    && saved.requestSha256().equals(response.path("requestSha256").asText()), "PUBLIC_RUN_IDENTITY_MISMATCH");
            JsonNode declaration = request.has("executionProfile") ? request.path("declaration") : request;
            require(declaration.isObject() && declaration.equals(privateData.path("declaration")), "PRIVATE_DECLARATION_MISMATCH");
            if (request.has("executionProfile")) require(request.path("executionProfile").equals(response.path("executionProfile"))
                    && request.at("/executionProfile/enabledCriteria").equals(response.path("enabledCriteria")), "EXECUTION_PROFILE_MISMATCH");
            boolean privateDigestDeclared = response.has("privateArtifactSha256");
            if (privateDigestDeclared) require(privateSha.equals(response.path("privateArtifactSha256").asText()), "PUBLIC_PRIVATE_SHA_MISMATCH");
            Path export = output.resolve("full-export.json");
            Files.write(export, publicBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            byte[] exported = Files.readAllBytes(export);
            require(Arrays.equals(publicBytes, exported) && Arrays.equals(expectedPublic, exported), "FULL_EXPORT_BYTES_MISMATCH");
            require(AcmgBusinessStore.sha(new String(exported, StandardCharsets.UTF_8)).equals(saved.responseSha256()), "FULL_EXPORT_SHA_MISMATCH");
            receipt = mapper.createObjectNode().put("schemaVersion", "pvs1-unified18-store-reader-receipt-v1").put("methodVersion", "1.0.0")
                    .put("status", "PASS").put("verificationLayer", "STANDALONE_JVM_ACMG_BUSINESS_STORE_READ")
                    .put("readerClass", Pvs1UnifiedEighteenHistoryReader.class.getName()).put("processId", ProcessHandle.current().pid())
                    .put("startedAt", started.toString()).put("completedAt", Instant.now().toString())
                    .put("analysisId", saved.analysisId()).put("runId", saved.id()).put("storedStatus", saved.status())
                    .put("serviceReconstructed", false).put("controllerReconstructed", false).put("runtimeInstantiated", false)
                    .put("evaluationTriggered", false).put("publicPrivateRequestRawBytesMatched", true).put("fullProductResponseExported", true);
            var checks = receipt.putObject("documents");
            documentReceipt(checks.putObject("public"), "expected-public.json", publicBytes, responseSha)
                    .put("storedResponseSha256", saved.responseSha256()).put("storedResponseShaMatched", true);
            documentReceipt(checks.putObject("private"), "expected-private.json", privateBytes, privateSha)
                    .put("publicPrivateDigestStatus", privateDigestDeclared ? "MATCHED" : "NOT_DECLARED")
                    .put("declarationMatched", true);
            documentReceipt(checks.putObject("request"), "expected-request.json", requestBytes, requestRawSha)
                    .put("canonicalizationProfile", "varvis-json-c14n-v1").put("canonicalIdentitySha256", requestIdentitySha)
                    .put("storedRequestSha256", saved.requestSha256()).put("storedRequestIdentityShaMatched", true)
                    .put("executionProfileStatus", request.has("executionProfile") ? "MATCHED" : "NOT_DECLARED");
            documentReceipt(receipt.putObject("export"), "full-export.json", exported, responseSha).put("completeResponseBytesMatched", true);
            sourceIdentity(receipt, output);
        } finally {
            new JdbcTemplate(ds).execute("SHUTDOWN");
        }
        receipt.put("readerConnectionClosed", true);
        Files.write(output.resolve("reader-receipt.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("PASS: complete AcmgBusinessStore public/private/request bytes, stored response SHA and canonical request SHA verified; full product JSON exported. Service/controller not reconstructed.");
    }

    private static ObjectNode documentReceipt(ObjectNode row, String file, byte[] bytes, String sha) {
        return row.put("file", file).put("encoding", "UTF-8").put("byteLength", bytes.length).put("rawSha256", sha).put("expectedRawBytesMatched", true);
    }

    private static void sourceIdentity(ObjectNode receipt, Path output) throws Exception {
        var source = receipt.putObject("source");String head = System.getProperty("varvis.pvs1.unified18.sourceHead");
        if (head == null) source.put("status", "NOT_PROVIDED");
        else {
            require(head.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "SOURCE_HEAD_INVALID");
            source.put("status", "CALLER_DECLARED").put("head", head).put("readerVerifiedGitHead", false);
        }
        String report = System.getProperty("varvis.pvs1.unified18.reportFile");var reportIdentity = receipt.putObject("report");
        if (report == null) reportIdentity.put("status", "NOT_PROVIDED").put("externalReportAssociationRequired", true);
        else {
            Path file = Path.of(report);if (!file.isAbsolute()) file = output.resolve(file);
            require(Files.isRegularFile(file), "REPORT_FILE_MISSING");byte[] raw = Files.readAllBytes(file);
            require(raw.length > 0, "REPORT_FILE_EMPTY");
            reportIdentity.put("status", "RAW_BYTES_HASHED").put("file", file.getFileName().toString()).put("byteLength", raw.length)
                    .put("rawSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)))
                    .put("testOutcomeIndependentlyReviewed", false);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException("PVS1_UNIFIED18_READER_" + reason);
    }
}
