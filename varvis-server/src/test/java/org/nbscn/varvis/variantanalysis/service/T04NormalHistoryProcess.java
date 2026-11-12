package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.TreeMap;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate actual Writer and store/archive-only Reader. No authority is reconstructed by the Reader. */
public final class T04NormalHistoryProcess {
    private T04NormalHistoryProcess() { }
    private static String url(Path directory) { return "jdbc:h2:file:" + directory.resolve("business-db").toAbsolutePath().toString().replace('\\', '/') + ";MODE=MySQL"; }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        if (args[0].equals("write")) write(directory, Path.of(args[2]), Path.of(args[3]));
        else if (args[0].equals("read")) read(directory);
        else throw new IllegalArgumentException("T04_HISTORY_PROCESS_MODE_INVALID");
    }
    private static void write(Path directory, Path python, Path scripts) throws Exception {
        Files.createDirectories(directory); var json = new ObjectMapper();
        try (var f = new T04NormalPipelineFixture(directory.resolve("work"), python, scripts, List.of("ACMG_AMP_2015:PP1"), "positive", true, url(directory))) {
            var result = f.execute(); JsonNode pp1 = result.at("/modules/pp1/results/0/publicSummary/evaluation");
            assertEquals("MET", pp1.path("status").asText(), "actual PP1 firstNeed=" + pp1.at("/needs/0/reasonCode").asText() + "; debug=" + f.debugDirectory()); assertEquals(1, pp1.path("awardedPP1Points").asInt(-1));
            assertEquals("NOT_SELECTED", f.inventoryAudit().at("/attempts/0/terminalReceipt/kind").asText());
            assertEquals("0", pp1.at("/ledger/pp4Points").asText()); f.save();
            var stored = f.nativeFixture.store().run(f.nativeFixture.analysisId(), f.nativeFixture.runId()).orElseThrow();
            Files.writeString(directory.resolve("expected-runtime.json"), stored.responseJson(), StandardOpenOption.CREATE_NEW);
            Files.writeString(directory.resolve("expected-private.json"), stored.privateJson(), StandardOpenOption.CREATE_NEW);
            var expected = new TreeMap<>(f.registered.archiveArtifacts());
            for (var core : f.coreArtifacts().entrySet()) {
                if (expected.containsKey(core.getKey())) assertArrayEquals(expected.get(core.getKey()), core.getValue());
                else expected.put(core.getKey(), core.getValue());
            }
            assertEquals(expected.size(), f.archive().path("rawArtifacts").size());
            // The actual inventory saved by the run contains every core reference; the archive
            // carries exact captured bytes. Independently validate each against its content pin.
            for (var field = f.archive().path("rawArtifacts").properties().iterator(); field.hasNext();) {
                var entry = field.next(); byte[] bytes = Base64.getDecoder().decode(entry.getValue().path("base64").asText());
                assertEquals(entry.getKey(), T04NativeOriginalFixture.rawSha(bytes));
                assertTrue(expected.containsKey(entry.getKey())); assertArrayEquals(expected.get(entry.getKey()), bytes);
            }
            var index = json.createObjectNode();
            for (var raw : expected.entrySet()) {
                Files.write(directory.resolve(raw.getKey() + ".original"), raw.getValue(), StandardOpenOption.CREATE_NEW);
                index.put(raw.getKey(), raw.getValue().length);
            }
            var metadata = json.createObjectNode().put("analysisId", stored.analysisId()).put("runId", stored.id())
                    .put("writerPid", ProcessHandle.current().pid()).put("actualPp1SourceEvaluations", f.pp1Source.evaluations())
                    .put("actualDeNovoEvaluations", f.deNovo.evaluations()).put("postclaimCallbacks", f.callbacks.get());
            metadata.set("originalByteIndex", index); var materialIndex = metadata.putArray("materialOriginalPins");
            for (var material : f.materialOriginals().entrySet()) {
                assertEquals(material.getKey(), T04NativeOriginalFixture.canonicalSha(material.getValue())); materialIndex.add(material.getKey());
                Files.write(directory.resolve(material.getKey() + ".material.json"), json.writeValueAsBytes(material.getValue()), StandardOpenOption.CREATE_NEW);
            }
            Files.write(directory.resolve("metadata.json"), json.writeValueAsBytes(metadata), StandardOpenOption.CREATE_NEW);
        }
        assertFalse(Files.exists(directory.resolve("business-db.lock.db")));
        System.out.println("T04_WRITER_COMPLETE_ACTUAL_NATIVE_AND_PYTHON");
    }
    private static void read(Path directory) throws Exception {
        var json = new ObjectMapper(); var metadata = json.readTree(Files.readAllBytes(directory.resolve("metadata.json")));
        var ds = new DriverManagerDataSource(requireNonNull(url(directory)), "sa", "");
        try {
            var stored = new AcmgBusinessStore(ds).run(metadata.path("analysisId").asText(), metadata.path("runId").asText()).orElseThrow();
            assertEquals(Files.readString(directory.resolve("expected-runtime.json")), stored.responseJson());
            assertEquals(Files.readString(directory.resolve("expected-private.json")), stored.privateJson());
            assertEquals(AcmgBusinessStore.sha(stored.responseJson()), stored.responseSha256());
            assertEquals(new AcmgBusinessArtifacts(json).requestHash(json.readTree(stored.requestJson())), stored.requestSha256());
            var response = (ObjectNode) json.readTree(stored.responseJson()); var privateData = json.readTree(stored.privateJson());
            assertEquals(AcmgBusinessStore.sha(stored.privateJson()), response.path("privateArtifactSha256").asText());
            assertEquals(stored.requestSha256(), privateData.at("/currentClinicalArchive/sessionIdentity/requestSha256").asText());
            var expanded = CurrentClinicalArchive.verifyAndExpand(json, privateData.path("currentClinicalArchive"), privateData.path("currentInventory"), stored.analysisId(), stored.id());
            var found = new TreeMap<String, byte[]>();
            for (var binding : expanded.path("rawBindings")) {
                byte[] bytes = Base64.getDecoder().decode(binding.at("/artifact/base64").asText()); String pin = binding.path("rawSha256").asText();
                assertEquals(pin, T04NativeOriginalFixture.rawSha(bytes)); assertArrayEquals(Files.readAllBytes(directory.resolve(pin + ".original")), bytes);
                found.put(pin, bytes);
            }
            assertEquals(metadata.path("originalByteIndex").size(), found.size());
            for (var raw = metadata.path("originalByteIndex").properties().iterator(); raw.hasNext();) { var entry = raw.next(); assertEquals(entry.getValue().asInt(), found.get(entry.getKey()).length); }
            assertEquals(metadata.path("materialOriginalPins").size(), expanded.path("materialReferences").size());
            for (var material : expanded.path("materialReferences")) {
                String pin = material.path("sha256").asText(); assertEquals(pin, T04NativeOriginalFixture.canonicalSha(material.path("originals")));
                assertEquals(json.readTree(Files.readAllBytes(directory.resolve(pin + ".material.json"))), material.path("originals"));
            }
            response.set("currentClinicalArchive", expanded); response.set("currentClinicalInventory", privateData.path("currentInventory").deepCopy());
            Files.write(directory.resolve("full-export.json"), json.writeValueAsBytes(response), StandardOpenOption.CREATE_NEW);
            assertTrue(expanded.path("historyOnly").asBoolean()); assertFalse(expanded.path("authorityRestorationSupported").asBoolean());
            System.out.println("T04_READER_COMPLETE_STORE_AND_MECHANICAL_ARCHIVE_ONLY");
        } finally { new JdbcTemplate(ds).execute("SHUTDOWN"); }
    }
}
