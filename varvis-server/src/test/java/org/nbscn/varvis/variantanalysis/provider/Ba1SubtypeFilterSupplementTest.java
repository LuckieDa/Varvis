package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Ba1SubtypeFilterSupplementTest {
    @TempDir Path work;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String JOINT_SHA = "a".repeat(64);
    private static final String PASS = "chr1\t100\t.\tA\tG\t.\tPASS\tAC=3;AN=100\n";

    private ObjectNode allele() {
        return mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1").put("position", 100)
                .put("reference", "A").put("alternate", "G");
    }
    private JsonNode policy() throws IOException {
        try (var in = getClass().getResourceAsStream("/acmg/ba1-native-subtype-source-policy-v1.json")) {
            return mapper.readTree(Objects.requireNonNull(in));
        }
    }
    private VariantAnalysisProperties settings() {
        var settings = new VariantAnalysisProperties();
        settings.getGnomad().setTabixExecutable("synthetic-tabix-never-executed");
        settings.getGnomad().setTimeout(Duration.ofSeconds(120));
        settings.getGnomad().setRemoteMaxAttempts(3);
        settings.getGnomad().setRemoteIndexCacheDir(work.resolve("remote-index-cache").toString());
        return settings;
    }
    private void succeeded(Path output, String raw, List<Map<String, Object>> diagnostics) throws IOException {
        Files.writeString(output, raw, StandardCharsets.UTF_8);
        diagnostics.add(Map.of("status", "SUCCEEDED", "attempt", 1, "maximumAttempts", 1,
                "effectiveAttemptMillis", 60000L, "stdoutBytes", Files.size(output), "stderrBytes", 0L));
    }
    private Ba1SubtypeFilterSupplement helper(List<String> raws, AtomicInteger calls) throws IOException {
        return new Ba1SubtypeFilterSupplement(mapper, settings(), policy(), work, (command, output, error, budget, resource, querySha, diagnostics) -> {
            assertEquals(1, budget.getRemoteMaxAttempts());
            assertEquals(work.resolve("remote-index-cache"), budget.getRemoteIndexCacheDir());
            assertEquals(Duration.ofSeconds(60), budget.getTimeout());
            assertEquals(Duration.ofSeconds(60), budget.getRemoteTotalTimeout());
            assertEquals(List.of("--cache", "1", "-R"), command.subList(1, 4));
            assertEquals("chr1\t99\t100\n", Files.readString(Path.of(command.get(4))));
            int call = calls.getAndIncrement();
            assertTrue(command.get(5).contains("/4.1/vcf/" + (call == 0 ? "exomes" : "genomes") + "/"));
            assertTrue(querySha.matches("[0-9a-f]{64}"));
            succeeded(output, raws.get(call), diagnostics);
        });
    }
    private ObjectNode result(String first, String second) throws IOException {
        var output = helper(List.of(first, second), new AtomicInteger()).supplement(allele(), Set.of("exomes", "genomes"), "4.1", JOINT_SHA);
        try (var files = Files.list(work)) { assertEquals(0, files.count(), "only owned temporary files must be removed"); }
        return output;
    }
    private ObjectNode reseal(ObjectNode output) {
        output.remove("resultId");
        return output.put("resultId", "ba1-subtype-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(output));
    }
    private boolean valid(JsonNode output) { return Ba1SubtypeFilterSupplement.verifiedPass(mapper, output, "exomes", allele(), JOINT_SHA); }

    @ParameterizedTest @ValueSource(ints = {1, 60, 120})
    void sharedNativeBudgetReachesBothQueriesAndSavedValidation(int seconds) throws Exception {
        var configured = settings();
        configured.getGnomad().setBa1NativeTimeoutSeconds(seconds);
        var calls = new AtomicInteger();
        var h = new Ba1SubtypeFilterSupplement(mapper, configured, policy(), work,
                (command, output, error, budget, resource, querySha, diagnostics) -> {
                    calls.incrementAndGet();
                    assertEquals(Duration.ofSeconds(seconds), budget.getTimeout());
                    assertEquals(budget.getTimeout(), budget.getRemoteTotalTimeout());
                    assertEquals(1, budget.getRemoteMaxAttempts());
                    assertEquals(configured.getGnomad().getRemoteIndexCacheDir(), budget.getRemoteIndexCacheDir());
                    Files.writeString(output, PASS);
                    diagnostics.add(Map.of("status", "SUCCEEDED", "attempt", 1, "maximumAttempts", 1,
                            "effectiveAttemptMillis", seconds * 1000L, "stdoutBytes", Files.size(output), "stderrBytes", 0L));
                });
        var result = h.supplement(allele(), Set.of("exomes"), "4.1", JOINT_SHA);
        assertEquals(2, calls.get());
        assertTrue(valid(result));
        assertEquals(Ba1SubtypeFilterSupplement.bundled(mapper, settings()).identity(), h.identity());
        var tampered = result.deepCopy();
        ((ObjectNode) tampered.at("/results/0/transportDiagnostics/0")).put("effectiveAttemptMillis", 120001);
        assertFalse(valid(reseal(tampered)));
    }

    @Test void exactlyTwoNativeSourceRecordsEstablishOnlySupplementaryQc() throws Exception {
        var output = result(PASS, PASS);
        assertEquals("VERIFIED_PASS", output.path("status").asText());
        assertEquals(2, output.path("queryCalls").asInt());
        assertTrue(valid(output));
        assertTrue(Ba1SubtypeFilterSupplement.verifiedPass(mapper, output, "genomes", allele(), JOINT_SHA));
        assertEquals(PASS.strip(), output.at("/results/0/exactRecords/0/rawRecord").asText());
        assertFalse(output.toString().contains(work.toString()));
        assertFalse(output.has("frequency"));
    }

    @Test void oneMissingOriginalFieldStillRequiresBothIndependentNativeSources() throws Exception {
        var calls = new AtomicInteger();
        var output = helper(List.of(PASS, PASS), calls).supplement(allele(), Set.of("genomes"), "4.1", JOINT_SHA);
        assertEquals(2, calls.get()); assertEquals(2, output.path("requiredSubtypes").size());
        assertEquals(1, output.path("originalMissingSubtypes").size()); assertTrue(valid(output));
    }

    @Test void noMissingSubtypeMakesNoTransportCall() throws Exception {
        var calls = new AtomicInteger();
        var output = helper(List.of(), calls).supplement(allele(), Set.of(), "4.1", JOINT_SHA);
        assertEquals(0, calls.get()); assertEquals("NOT_REQUESTED", output.path("status").asText()); assertFalse(valid(output));
    }

    @ParameterizedTest @ValueSource(strings = {".", "", "AC0", "PASS;FAIL", "pass"})
    void everyNonliteralPassRemainsUnresolved(String filter) throws Exception {
        var output = result(PASS, PASS.replace("\tPASS\t", "\t" + filter + "\t"));
        assertEquals("NONPASS", output.at("/results/1/status").asText()); assertFalse(valid(output));
    }

    @ParameterizedTest @ValueSource(strings = {"", "chr1\t100\t.\tA\tT\t.\tPASS\t.\n", "chr1\t100\t.\tC\tG\t.\tPASS\t.\n", "chr1\t101\t.\tA\tG\t.\tPASS\t.\n", "chr2\t100\t.\tA\tG\t.\tPASS\t.\n", "chr1\t100\t.\tA\tG,T\t.\tPASS\t.\n"})
    void absenceOrWrongAlleleCannotSupportPass(String raw) throws Exception {
        var output = result(PASS, raw); assertEquals("NO_RECORD", output.at("/results/1/status").asText()); assertFalse(valid(output));
    }

    @Test void duplicateExactRowsAreAmbiguousEvenWhenIdentical() throws Exception {
        var output = result(PASS + PASS, PASS);
        assertEquals("AMBIGUOUS", output.at("/results/0/status").asText());
        assertEquals(2, output.at("/results/0/exactRecords").size()); assertFalse(valid(output));
    }

    @Test void otherAlternateDoesNotReplaceExactRecordAndAllReturnedBytesRemainBound() throws Exception {
        String raw = PASS.replace("\tG\t", "\tT\t") + PASS;
        var output = result(raw, PASS); assertTrue(valid(output));
        assertEquals(2, output.at("/results/0/returnedRows").asInt()); assertEquals(raw, output.at("/results/0/rawResponse").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"partial", "bad\n", "#header\n"})
    void incompleteAndMalformedTransportOutputNeverBecomesAbsence(String raw) throws Exception {
        var output = result(raw, PASS); assertEquals("FAILED", output.at("/results/0/status").asText()); assertFalse(valid(output));
    }

    @Test void outputAndRowCapsFailClosed() throws Exception {
        var overBytes = result("X".repeat(262145), PASS);
        assertEquals("BA1_SUBTYPE_OUTPUT_LIMIT_EXCEEDED", overBytes.at("/results/0/reasonCode").asText());
        assertFalse(valid(overBytes));
        var overRows = result(PASS.repeat(257), PASS);
        assertEquals("FAILED", overRows.at("/results/0/status").asText()); assertFalse(valid(overRows));
    }

    @Test void badReleaseAssemblyHashAndSubtypeAllRefuseBeforeTransport() throws Exception {
        var calls = new AtomicInteger(); var h = helper(List.of(), calls);
        assertEquals(0, h.supplement(allele(), Set.of("exomes"), "4.1.1", JOINT_SHA).path("queryCalls").asInt());
        assertEquals(0, h.supplement(allele().put("assembly", "GRCh37"), Set.of("exomes"), "4.1", JOINT_SHA).path("queryCalls").asInt());
        assertEquals(0, h.supplement(allele(), Set.of("exomes"), "4.1", "invalid").path("queryCalls").asInt());
        assertEquals(0, h.supplement(allele(), Set.of("joint"), "4.1", JOINT_SHA).path("queryCalls").asInt());
        assertEquals(0, calls.get());
    }

    @Test void transportFailureAndTimeoutDoNotReadSuccessfulLookingPrefix() throws Exception {
        for (String status : List.of("TIMEOUT", "EXECUTION_FAILED")) {
            var h = new Ba1SubtypeFilterSupplement(mapper, settings(), policy(), work, (command, output, error, budget, resource, querySha, diagnostics) -> {
                Files.writeString(output, PASS); diagnostics.add(Map.of("status", status)); throw new IOException("synthetic failure");
            });
            var output = h.supplement(allele(), Set.of("exomes"), "4.1", JOINT_SHA);
            assertEquals("TIMEOUT".equals(status) ? "TIMEOUT" : "FAILED", output.at("/results/0/status").asText());
            assertFalse(output.at("/results/0").has("rawResponse")); assertFalse(valid(output));
        }
    }

    @Test void entryAndPostTransportCancellationPreserveFlagAndStopFurtherQueries() throws Exception {
        var calls = new AtomicInteger(); var h = helper(List.of(), calls);
        try {
            Thread.currentThread().interrupt();
            var output = h.supplement(allele(), Set.of("exomes"), "4.1", JOINT_SHA);
            assertEquals(0, calls.get()); assertTrue(Thread.currentThread().isInterrupted()); assertFalse(valid(output));
        } finally { Thread.interrupted(); }
        var cancelled = new Ba1SubtypeFilterSupplement(mapper, settings(), policy(), work, (command, output, error, budget, resource, querySha, diagnostics) -> {
            calls.incrementAndGet(); succeeded(output, PASS, diagnostics); Thread.currentThread().interrupt();
        });
        try {
            var output = cancelled.supplement(allele(), Set.of("exomes"), "4.1", JOINT_SHA);
            assertEquals(1, calls.get()); assertEquals("BA1_SUBTYPE_CANCELLED", output.at("/results/0/reasonCode").asText());
            assertTrue(Thread.currentThread().isInterrupted()); assertFalse(valid(output));
        } finally { Thread.interrupted(); }
        try (var files = Files.list(work)) { assertEquals(0, files.count()); }
    }

    @Test void callerAndIdentityMutationCannotChangePreparedQueryOrSourcePins() throws Exception {
        var input = allele(); var source = policy(); var calls = new AtomicInteger(); var queries = new ArrayList<String>();
        var h = new Ba1SubtypeFilterSupplement(mapper, settings(), source, work, (command, output, error, budget, resource, querySha, diagnostics) -> {
            queries.add(Files.readString(Path.of(command.get(4))));
            calls.incrementAndGet(); input.put("position", 101); succeeded(output, PASS, diagnostics);
        });
        ((ObjectNode) source.path("review")).put("receiptSha256", "f".repeat(64));
        h.identity().put("status", "UNAVAILABLE");
        var output = h.supplement(input, Set.of("exomes"), "4.1", JOINT_SHA);
        assertEquals(List.of("chr1\t99\t100\n", "chr1\t99\t100\n"), queries); assertTrue(valid(output));
    }

    @Test void savedLabelsResealedRawTamperingAndSourceIdentityCannotFabricatePass() throws Exception {
        var output = result(PASS, PASS);
        var wrongRaw = output.deepCopy();
        ((ObjectNode) wrongRaw.at("/results/0")).put("rawResponse", PASS.replace("PASS", "."));
        assertFalse(valid(reseal(wrongRaw)));
        var changedRawAndHash = wrongRaw.deepCopy();
        ((ObjectNode) changedRawAndHash.at("/results/0")).put("rawResponseSha256", GnomadQueryRunner.queryIdentity(PASS.replace("PASS", ".")))
                .put("rawResponseByteLength", PASS.replace("PASS", ".").getBytes(StandardCharsets.UTF_8).length);
        assertFalse(valid(reseal(changedRawAndHash)));
        var wrongSource = output.deepCopy(); ((ObjectNode) wrongSource.at("/results/1")).put("version", "4.1.1"); assertFalse(valid(reseal(wrongSource)));
        var wrongPolicy = output.deepCopy(); ((ObjectNode) wrongPolicy.path("sourcePolicyIdentity")).put("policySha256", "f".repeat(64)); assertFalse(valid(reseal(wrongPolicy)));
        var missingResult = output.deepCopy(); ((com.fasterxml.jackson.databind.node.ArrayNode) missingResult.path("results")).remove(1); assertFalse(valid(reseal(missingResult)));
        var foreign = allele().put("alternate", "T");
        assertFalse(Ba1SubtypeFilterSupplement.verifiedPass(mapper, output, "exomes", foreign, JOINT_SHA));
        assertFalse(Ba1SubtypeFilterSupplement.verifiedPass(mapper, output, "exomes", allele(), "b".repeat(64)));
    }

    @Test void unavailableDefaultConfigurationIsStructuredNotStartupFatal() {
        var bad = settings(); bad.getGnomad().setTabixExecutable("");
        var h = Ba1SubtypeFilterSupplement.bundled(mapper, bad);
        assertEquals("UNAVAILABLE", h.identity().path("status").asText());
        var output = h.supplement(allele(), Set.of("exomes"), "4.1", JOINT_SHA);
        assertEquals("UNAVAILABLE", output.path("status").asText()); assertEquals(0, output.path("queryCalls").asInt());
    }

    @Test void existingRunnerCompletesTwoOfflineOwnedSubprocessesAndCleansItsFiles() throws Exception {
        Path command = work.resolve("synthetic source.cmd");
        Files.writeString(command, "@echo off\r\necho " + PASS.strip() + "\r\nexit /b 0\r\n", StandardCharsets.UTF_8);
        var configured = settings(); configured.getGnomad().setTabixExecutable(command.toString());
        configured.getGnomad().setTimeout(Duration.ofSeconds(5));
        var h = new Ba1SubtypeFilterSupplement(mapper, configured, policy(), work,
                (cmd, output, error, budget, resource, querySha, diagnostics) -> GnomadQueryRunner.run(
                        cmd, output, error, true, budget, resource, querySha, 1, diagnostics));
        var output = h.supplement(allele(), Set.of("exomes"), "4.1", JOINT_SHA);
        assertTrue(valid(output), output.toPrettyString());
        assertEquals(2, output.path("queryCalls").asInt());
        try (var files = Files.list(work)) {
            assertEquals(Set.of(command, work.resolve("remote-index-cache")), Set.copyOf(files.toList()));
        }
    }

    @Test void policyCannotChangeNativeVersionOrAddUnimplementedCondition() throws Exception {
        var wrong = policy(); ((ObjectNode) wrong.at("/sources/0")).put("version", "4.1.1");
        assertThrows(IllegalArgumentException.class, () -> new Ba1SubtypeFilterSupplement(mapper, settings(), wrong, work,
                (command, output, error, budget, resource, querySha, diagnostics) -> fail("must not query")));
        var extra = (ObjectNode) policy(); extra.put("allowNoRecord", true);
        assertThrows(IllegalArgumentException.class, () -> new Ba1SubtypeFilterSupplement(mapper, settings(), extra, work,
                (command, output, error, budget, resource, querySha, diagnostics) -> fail("must not query")));
    }
}
