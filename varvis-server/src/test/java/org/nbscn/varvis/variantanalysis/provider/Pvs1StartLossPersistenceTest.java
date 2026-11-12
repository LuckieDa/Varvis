package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated H2 persistence contract. Does not use the configured user database or a browser download. */
public class Pvs1StartLossPersistenceTest {
    @TempDir Path temporary;

    @Test void sourceFactsAndBothStrengthsSurviveAnotherJvmReadAndExportSerialization() throws Exception {
        var fixture = new Pvs1StartLossRuntimeTest();
        var results = fixture.mapper.createArrayNode();
        var archive = fixture.mapper.createObjectNode();
        archive.set("results", results);
        var publicRows = fixture.mapper.createArrayNode();
        for (int strand : new int[]{1, -1}) {
            var f = fixture.fixture(strand);
            if (strand == -1) f.upstream().withArray("records").add(fixture.record(f, 4));
            fixture.pin(f);
            var result = fixture.run(f);
            assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
            results.add(result);
            if (strand == 1) {
                var nativeFixture = new Pvs1StartLossAutomaticPreparationTest();
                nativeFixture.root = Files.createDirectory(temporary.resolve("technical"));
                var supply = nativeFixture.technicalSupply(f);
                var preparation = new Pvs1StartLossAutomaticPreparation(fixture.mapper, () -> supply,
                        nativeFixture.sources(nativeFixture.document(), fixture.mapper.createObjectNode()));
                result.set("startLossAutomaticPreparation", preparation.prepare(f.data().context, f.data().candidate, result));
                archive.set("startLossSourceDocuments", preparation.originals());
                assertEquals(5, preparation.originals().size());
            }
            var row = publicRows.addObject();
            row.set("evaluation", result.path("evaluation"));
            row.set("facts", Pvs1SelectedBatch.publicSummary(fixture.mapper, result));
        }
        saveAndRead(fixture.mapper.writeValueAsString(publicRows), fixture.mapper.writeValueAsString(archive));
    }

    void saveAndRead(String publicJson, String privateJson) throws Exception {
        Files.writeString(temporary.resolve("expected-public.json"), publicJson);
        Files.writeString(temporary.resolve("expected-private.json"), privateJson);
        String url = url(temporary);
        var ds = new DriverManagerDataSource(requireNonNull(url), "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE variant_analysis_acmg_run (run_id VARCHAR PRIMARY KEY, analysis_id VARCHAR, request_sha256 VARCHAR, request_json CLOB, status VARCHAR, created_at VARCHAR, response_sha256 VARCHAR, response_json CLOB, private_json CLOB)");
        var store = new AcmgBusinessStore(ds);
        assertTrue(store.claim(new AcmgBusinessStore.Run("start-loss", "synthetic", AcmgBusinessStore.sha("{}"), "{}", "RUNNING", "2026-11-02T00:00:00Z", null, null, null)));
        store.finish("start-loss", "COMPLETED", publicJson, privateJson);
        jdbc.execute("SHUTDOWN");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(java))) java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temporary.resolve("reader.log");
        Process child = new ProcessBuilder(java, "-cp", classpath, getClass().getName(), temporary.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = child.waitFor(45, TimeUnit.SECONDS);
        if (!ended) child.destroyForcibly();
        assertTrue(ended, "Independent history reader timed out");
        assertEquals(0, child.exitValue(), Files.readString(log));
        assertEquals(publicJson, Files.readString(temporary.resolve("export.json")));
    }

    private static String url(Path directory) {
        return "jdbc:h2:file:" + directory.resolve("start-loss-history").toAbsolutePath().toString().replace('\\', '/') + ";MODE=MySQL";
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var ds = new DriverManagerDataSource(requireNonNull(url(directory)), "sa", "");
        var store = new AcmgBusinessStore(ds);
        var saved = store.run("synthetic", "start-loss").orElseThrow();
        if (!Files.readString(directory.resolve("expected-public.json")).equals(saved.responseJson())
                || !Files.readString(directory.resolve("expected-private.json")).equals(saved.privateJson())
                || !AcmgBusinessStore.sha(saved.responseJson()).equals(saved.responseSha256()))
            throw new IllegalStateException("Start-loss source/decision history changed");
        Files.writeString(directory.resolve("export.json"), saved.responseJson());
        new JdbcTemplate(ds).execute("SHUTDOWN");
        System.out.println("PASS: independent JVM read; public export and private source bytes unchanged");
    }
}
