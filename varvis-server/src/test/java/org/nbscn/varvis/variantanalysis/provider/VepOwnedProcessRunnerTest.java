package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import static org.junit.jupiter.api.Assertions.*;

class VepOwnedProcessRunnerTest {
    @TempDir Path work;

    @Test void optInIsVepSpecificAndDefaultCacheIdentityIsPreserved() throws Exception {
        var settings = new VariantAnalysisProperties();
        var legacy = new SystemExternalProcessRunner();
        String original = settings.getVep().preRankingOptionsHash();
        assertSame(legacy, VepOwnedProcessRunner.select(settings, legacy));
        settings.getVep().setOwnedExecutionEnabled(true);
        assertInstanceOf(VepOwnedProcessRunner.class, VepOwnedProcessRunner.select(settings, legacy));
        assertNotEquals(original, settings.getVep().preRankingOptionsHash());
        var provider = new VepTranscriptAnnotationProvider(settings);
        var field = VepTranscriptAnnotationProvider.class.getDeclaredField("processRunner"); field.setAccessible(true);
        assertInstanceOf(VepOwnedProcessRunner.class, field.get(provider));
        var enrichment = new org.nbscn.varvis.variantanalysis.service.VepTranscriptAnnotationEnrichmentService(null,
                settings, new VepResourceValidator(settings), new VepCommandBuilder(settings),
                new VepJsonOutputParser(new ObjectMapper()), legacy, null);
        var batchField = enrichment.getClass().getDeclaredField("processRunner"); batchField.setAccessible(true);
        assertInstanceOf(VepOwnedProcessRunner.class, batchField.get(enrichment));
        settings.getVep().setOwnedExecutionEnabled(false);
        assertEquals(original, settings.getVep().preRankingOptionsHash());
    }

    @Test void partialJsonCannotPublishAnOtherwiseSuccessfulOwnedProcess() throws Exception {
        Path json = work.resolve("output.json");
        Files.writeString(json, "{\"id\":\"one\"}\n{\"id\":");
        assertThrows(java.io.IOException.class, () -> VepOwnedProcessRunner.validateJsonOutput(json));
        Files.writeString(json, "{\"id\":\"one\"} trailing");
        assertThrows(java.io.IOException.class, () -> VepOwnedProcessRunner.validateJsonOutput(json));
        Files.writeString(json, "{\"id\":\"one\"}\n");
        VepOwnedProcessRunner.validateJsonOutput(json);
    }

    @Test void rootCodeAndActiveDescendantsRemainSeparateFromCleanupResult() throws Exception {
        var live = new java.util.concurrent.atomic.AtomicBoolean(true);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> new OwnedQueryExecution.NativeExecution() {
            public void launch(OwnedQueryExecution.CommandSpec command) { }
            public void resume() { }
            public boolean rootExited(int waitMillis) { return true; }
            public int exitCode() { return 0; }
            public int activeProcesses() { return live.get() ? 1 : 0; }
            public void terminate() { live.set(false); }
            public void close() { }
        });
        try {
            var command = new OwnedQueryExecution.CommandSpec(List.of("unused-fixture"), work, Map.of(),
                    work.resolve("out"), work.resolve("err"), "synthetic-owner-contract", Map.of(), true, true);
            var ticket = pool.launch(command, 1000, 1);
            var outcome = OwnedQueryExecution.await(ticket);
            assertEquals("ROOT_EXITED_WITH_ACTIVE_DESCENDANTS", outcome.failure());
            assertEquals(0, ticket.receipt().get("rootExitCodeObserved"));
            assertEquals(1, ticket.receipt().get("activeAtRootExit"));
            assertEquals(0, ticket.receipt().get("activeProcessCount"));
            assertEquals(true, ticket.receipt().get("exitConfirmed"));
        } finally { pool.shutdown(); }
    }

    @Test @EnabledIfSystemProperty(named="varvis.vep.owned.fixture.root", matches=".+")
    void nativeNormal() throws Exception { mechanical("normal"); }
    @Test @EnabledIfSystemProperty(named="varvis.vep.owned.fixture.root", matches=".+")
    void nativeTimeout() throws Exception { mechanical("timeout"); }
    @Test @EnabledIfSystemProperty(named="varvis.vep.owned.fixture.root", matches=".+")
    void nativeInterruptedWithIndependentSentinel() throws Exception { mechanical("interrupted"); }
    @Test @EnabledIfSystemProperty(named="varvis.vep.owned.fixture.root", matches=".+")
    void nativeRootExitWithLivePerlDescendant() throws Exception { mechanical("orphan"); }

    private void mechanical(String role) throws Exception {
        Path root = Path.of(System.getProperty("varvis.vep.owned.fixture.root"));
        Path cwd = Files.createDirectory(root.resolve(role + " 中文 path"));
        var mapper = new ObjectMapper();
        var report = mapper.createObjectNode().put("role", role).put("scientificExecution", false);
        Path reportFile = root.resolve(role+".json");
        Files.writeString(reportFile, "{\"status\":\"STARTED\"}", StandardOpenOption.CREATE_NEW);
        Path wrapper = Path.of(System.getProperty("varvis.vep.owned.fixture.wrapper"));
        String original = Files.readString(wrapper);
        String source = original.replace("for %%I in (\"%~dp0..\") do set \"VEP_ROOT=%%~fI\"",
                "set \"VEP_ROOT=" + wrapper.getParent().getParent() + "\"")
                .replace("\"%MSYS2_ROOT%\\usr\\bin\\perl.exe\" \"%VEP_ROOT%\\bin\\ensembl-vep\\vep\" %*",
                        "\"%MSYS2_ROOT%\\usr\\bin\\perl.exe\" \"fixture.pl\" %*");
        assertNotEquals(original, source); assertTrue(source.contains("\"fixture.pl\" %*"));
        Files.writeString(cwd.resolve("vep fixture.cmd"), source, StandardCharsets.UTF_8);
        Files.writeString(cwd.resolve("fixture.pl"), """
                use strict; use warnings; use Cwd qw(getcwd);
                my $role = shift @ARGV;
                if ($role eq 'orphan') {
                    my $child = fork(); die "fork failed" unless defined $child;
                    if ($child) {
                        for (1..100) { last if -e 'ready.txt'; select(undef,undef,undef,0.05); }
                        die "child handshake missing" unless -e 'ready.txt';
                        exit 0;
                    }
                }
                open(my $f, '>', 'ready.txt') or die $!;
                print $f "$$\\n" . getcwd() . "\\n" . ($ENV{VARVIS_VEP_FIXTURE}//'') . "\\n"
                    . ($ENV{PERL5LIB}//'') . "\\n" . join("\\n", @ARGV) . "\\n";
                close($f);
                if ($role eq 'normal') {
                    print STDOUT "normal-output\\n"; print STDERR "normal-diagnostic\\n";
                    select(undef,undef,undef,0.3); exit 0;
                }
                sleep 60;
                """, StandardCharsets.UTF_8);
        var pool = new OwnedQueryExecution.OwnerPool(2, WindowsJobExecution::new);
        var runner = new VepOwnedProcessRunner(pool, Map.of("VARVIS_VEP_FIXTURE", "owned-local-only"), 64L*1024*1024);
        var result = new AtomicReference<ProcessResult>(); var error = new AtomicReference<Throwable>();
        var interruptRestored = new java.util.concurrent.atomic.AtomicBoolean();
        var argv = List.of(cwd.resolve("vep fixture.cmd").toString(), role, "a b", "中文", "tail\\");
        Thread target = new Thread(() -> {
            try { result.set(runner.run(argv, cwd, Duration.ofSeconds("timeout".equals(role) ? 20 : 30))); }
            catch (Throwable failed) { error.set(failed); interruptRestored.set(Thread.currentThread().isInterrupted()); }
        }, "vep-owned-target-fixture");
        OwnedQueryExecution.Ticket sentinel = null;
        try {
            if ("interrupted".equals(role)) {
                Path sentinelDir = Files.createDirectory(root.resolve("independent-sentinel"));
                Files.copy(cwd.resolve("fixture.pl"), sentinelDir.resolve("fixture.pl"));
                Files.copy(cwd.resolve("vep fixture.cmd"), sentinelDir.resolve("sentinel.cmd"));
                sentinel = pool.launch(new OwnedQueryExecution.CommandSpec(List.of(sentinelDir.resolve("sentinel.cmd").toString(), "sentinel"),
                        sentinelDir, Map.of(), sentinelDir.resolve("stdout"), sentinelDir.resolve("stderr"), "independent-sentinel"), 45000, 2);
                handshake(sentinelDir.resolve("ready.txt"));
            }
            target.start(); handshake(cwd.resolve("ready.txt"));
            report.put("handshakeBeforeCancellation", true);
            var ready = Files.readAllLines(cwd.resolve("ready.txt"));
            assertEquals("owned-local-only", ready.get(2)); assertTrue(ready.get(3).contains("ensembl-release-116"));
            assertTrue(ready.get(1).endsWith("/" + cwd.getFileName()), "MSYS cwd must retain space and Unicode path");
            assertEquals(List.of("a b", "中文", "tail\\"), ready.subList(4,7));
            report.put("perlPosixPid", ready.get(0)).put("argvEnvironmentRoundTrip", true);
            if ("interrupted".equals(role)) target.interrupt();
            target.join(25000); assertFalse(target.isAlive(), "bounded fixture return");
            try (var dirs = Files.list(cwd)) {
                Path receipt = dirs.filter(p -> p.getFileName().toString().startsWith("owned-")).findFirst().orElseThrow().resolve("execution.json");
                report.set("execution", mapper.readTree(receipt.toFile()));
            }
            assertEquals("STOPPED", report.at("/execution/cleanupOutcome").asText());
            assertEquals(0, report.at("/execution/activeProcessCount").asInt(-1));
            assertTrue(report.at("/execution/jobBound").asBoolean());
            assertTrue(report.at("/execution/rootExitObserved").asBoolean());
            if ("normal".equals(role)) {
                assertNull(error.get()); assertNotNull(result.get()); assertTrue(result.get().isSuccess());
                assertEquals(0, report.at("/execution/rootExitCodeObserved").asInt(-1));
                assertFalse(report.at("/execution/terminationRequested").asBoolean());
                assertTrue(report.at("/execution/outputReadComplete").asBoolean());
                assertTrue(result.get().getOutput().contains("normal-output")); assertTrue(result.get().getOutput().contains("normal-diagnostic"));
            } else if ("interrupted".equals(role)) {
                assertInstanceOf(java.io.IOException.class, error.get()); assertTrue(interruptRestored.get());
                assertNotNull(sentinel); assertFalse(sentinel.done.isDone());
                report.put("independentSentinelSurvived", true); report.set("independentSentinel", mapper.valueToTree(sentinel.receipt()));
            } else {
                assertNull(error.get()); assertNotNull(result.get()); assertFalse(result.get().isSuccess());
                assertEquals("timeout".equals(role) ? "TIMEOUT" : "ROOT_EXITED_WITH_ACTIVE_DESCENDANTS", report.at("/execution/cleanupTrigger").asText());
            }
            report.put("status", "PASS");
        } catch (Throwable failed) {
            report.put("status", "FAIL").put("exception", failed.toString()); throw failed;
        } finally {
            if (target.isAlive()) { target.interrupt(); target.join(5000); }
            if (sentinel != null) { sentinel.cancel("SENTINEL_OWNER_FINALLY"); sentinel.done.get(5, java.util.concurrent.TimeUnit.SECONDS); report.set("sentinelCleanup", mapper.valueToTree(sentinel.receipt())); }
            pool.shutdown();
            Files.write(reportFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        }
    }
    private static void handshake(Path file) throws Exception {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> Files.isRegularFile(file) && Files.size(file) > 0);
    }
}
