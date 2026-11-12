package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real separate Writer exit followed by a read-only normal Service/Controller Reader.
 * Clinical/policy rows remain explicitly synthetic normal engineering controls.
 */
class T03NativeCountProcessPersistenceTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest @CsvSource({"BA1_ONLY,HEMI", "PAIR,SUM", "ALL,HEMI",
            "BA1_ONLY,RAW_BOUND", "PAIR,RAW_BOUND", "ALL,RAW_BOUND", "BA1_ONLY,RAW_UNKNOWN_BOUND"})
    void nativeCountResultsSurviveWriterExitBeforeASeparateReadOnlyHistoryAndExport(String selection, String fault) throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("case"));
        Path writerLog = temporary.resolve("writer.log"), readerLog = temporary.resolve("reader.log");
        var writer = start(Writer.class, directory, selection, fault, writerLog);
        waitFor(writer, writerLog);
        assertFalse(writer.isAlive());
        var checkpoint = mapper.readTree(Files.readAllBytes(directory.resolve("writer-checkpoint.json")));
        assertEquals(writer.pid(), checkpoint.path("writerPid").asLong());
        assertTrue(checkpoint.path("databaseClosed").asBoolean());
        Path database = directory.resolve("business-db.mv.db");
        String dbBefore = Bs1KnowledgeCatalog.sha(Files.readAllBytes(database));
        String jointBefore = Files.readString(directory.resolve("joint-queries.txt"));
        String exomesBefore = Files.exists(directory.resolve("exomes-queries.txt"))
                ? Files.readString(directory.resolve("exomes-queries.txt")) : "";
        long writerExitedAt = System.nanoTime();
        var reader = start(Reader.class, directory, checkpoint.path("analysisId").asText(), checkpoint.path("runId").asText(), readerLog);
        assertNotEquals(writer.pid(), reader.pid());
        waitFor(reader, readerLog);
        assertEquals(dbBefore, Bs1KnowledgeCatalog.sha(Files.readAllBytes(database)), "Read-only Reader must preserve the DB file bytes");
        assertArrayEquals(Files.readAllBytes(directory.resolve("expected-full-get.json")), Files.readAllBytes(directory.resolve("reader-full-get.json")));
        assertArrayEquals(Files.readAllBytes(directory.resolve("expected-private.json")), Files.readAllBytes(directory.resolve("reader-private.json")));
        assertEquals(jointBefore, Files.readString(directory.resolve("joint-queries.txt")));
        assertEquals(exomesBefore, Files.exists(directory.resolve("exomes-queries.txt")) ? Files.readString(directory.resolve("exomes-queries.txt")) : "");
        var receipt = mapper.createObjectNode().put("layer", "SYNTHETIC_NORMAL_CURRENT_WRITER_EXIT_THEN_READ_ONLY_SERVICE_CONTROLLER_READER")
                .put("selection", selection).put("fault", fault).put("writerPid", writer.pid()).put("writerExitCode", writer.exitValue())
                .put("readerPid", reader.pid()).put("readerExitCode", reader.exitValue()).put("writerExitedBeforeReaderStarted", true)
                .put("readerStartedAfterObservedWriterExitNanos", System.nanoTime() - writerExitedAt)
                .put("databaseBytesUnchanged", true).put("publicFullGetAndPrivateBytesIdentical", true)
                .put("queryLogsUnchanged", true).put("realClinicalAdoption", false);
        Files.write(directory.resolve("process-receipt.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
        String output = System.getProperty("varvis.t03.process.output");
        if (output != null) {
            Path target = Files.createDirectories(Path.of(output).resolve(selection.toLowerCase(java.util.Locale.ROOT) + "-" + fault.toLowerCase(java.util.Locale.ROOT)));
            for (String name : List.of("expected-full-get.json", "expected-public.json", "expected-private.json", "reader-full-get.json", "reader-private.json",
                    "reader-index.json", "reader-detail.json", "reader-history.json", "writer-checkpoint.json", "expected-source-pins.json", "process-receipt.json"))
                Files.copy(directory.resolve(name), target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(writerLog, target.resolve("writer.log"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(readerLog, target.resolve("reader.log"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Process start(Class<?> main, Path directory, String arg1, String arg2, Path log) throws Exception {
        var command = new ArrayList<String>();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) java = Path.of(System.getProperty("java.home"), "bin", "java");
        command.add(java.toString()); command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        for (String key : List.of("varvis.pp1.python", "varvis.t03.denovo.scripts")) {
            String value = System.getProperty(key); if (value != null) command.add("-D" + key + "=" + value);
        }
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                main.getName(), directory.toString(), arg1, arg2));
        // Existing publication fixtures locate their frozen dispatch document from
        // the server module. Keep that module cwd; all case data/H2 paths are absolute.
        return new ProcessBuilder(command).directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void waitFor(Process process, Path log) throws Exception {
        boolean ended = process.waitFor(50, TimeUnit.SECONDS);
        if (!ended) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertTrue(ended, "Dedicated test process timed out: " + log);
        assertEquals(0, process.exitValue(), Files.readString(log));
    }

    public static final class Writer {
        public static void main(String[] args) throws Exception {
            System.setProperty("varvis.t03.separateWriter", "true");
            var test = new T03FrequencyConditionsBusinessTest(); test.temporary = Path.of(args[0]);
            try { test.runNativeProcessCase(args[1], args[2]); }
            finally { test.close(); }
        }
    }
    public static final class Reader {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]); String analysis = args[1], run = args[2];
            var mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            verifyInputs(directory, mapper);
            var ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("business-db").toString().replace('\\', '/')
                    + ";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            var settings = new VariantAnalysisProperties(); settings.setStorageRoot(directory.resolve("jobs"));
            settings.getPersistence().setRetryBackoff(Duration.ZERO);
            var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(ds), new DataSourceTransactionManager(ds), settings, mapper);
            var store = new AcmgBusinessStore(ds); var sources = new AcmgExecutionSources(mapper, store);
            var service = new AcmgBusinessService(mapper, jobs, store, sources,
                    () -> { throw new AssertionError("Historical Reader cannot acquire/reissue evidence runtime authority"); });
            var saved = store.run(analysis, run).orElseThrow();
            require(Files.readString(directory.resolve("expected-public.json")).equals(saved.responseJson()), "PUBLIC_SAVED_BYTES_CHANGED");
            require(Files.readString(directory.resolve("expected-private.json")).equals(saved.privateJson()), "PRIVATE_SAVED_BYTES_CHANGED");
            JsonNode result = service.read(analysis, run);
            require(mapper.readTree(saved.responseJson()).equals(result), "NORMAL_SERVICE_READ_CHANGED");
            var history = service.list(analysis); require(history.stream().anyMatch(row -> run.equals(row.path("runId").asText())), "HISTORY_RUN_MISSING");
            var http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
            byte[] full = http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysis, run))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            require(Arrays.equals(Files.readAllBytes(directory.resolve("expected-full-get.json")), full), "FULL_EXPORT_BYTES_CHANGED");
            JsonNode index = mapper.readTree(http.perform(get("/variant-analyses/{id}/acmg/runs/{run}/view", analysis, run))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            String candidate = result.at("/candidates/0/candidateId").asText();
            byte[] detail = http.perform(get("/variant-analyses/{id}/acmg/runs/{run}/view/background", analysis, run)
                            .param("candidateId", candidate).param("backgroundIndex", "0").param("sourceSha256", index.path("sourceViewSha256").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            require(result.at("/candidates/0/ba1/contextResults/0/evaluation").equals(mapper.readTree(detail).at("/candidates/0/ba1/contextResults/0/evaluation")), "NATIVE_DETAIL_CHANGED");
            require(saved.equals(store.run(analysis, run).orElseThrow()), "HISTORICAL_READ_REWROTE_RESULT");
            verifyInputs(directory, mapper);
            Files.write(directory.resolve("reader-full-get.json"), full);
            Files.writeString(directory.resolve("reader-private.json"), saved.privateJson());
            Files.write(directory.resolve("reader-detail.json"), detail);
            Files.write(directory.resolve("reader-index.json"), mapper.writeValueAsBytes(index));
            Files.write(directory.resolve("reader-history.json"), mapper.writeValueAsBytes(history));
            System.out.println("PASS: Writer already exited; read-only H2 normal Service/Controller history/detail/full GET; no runtime acquisition");
        }
        private static void verifyInputs(Path directory, ObjectMapper mapper) throws Exception {
            for (var pin : mapper.readTree(Files.readAllBytes(directory.resolve("expected-source-pins.json")))) {
                Path file = directory.resolve(pin.path("file").asText()).normalize();
                require(file.startsWith(directory) && Files.isRegularFile(file) && Files.size(file) <= 1024 * 1024, "INPUT_SCOPE_INVALID");
                require(Files.size(file) == pin.path("bytes").asLong() && Bs1KnowledgeCatalog.sha(Files.readAllBytes(file)).equals(pin.path("sha256").asText()), "INPUT_BYTES_CHANGED");
            }
        }
        private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    }
}
