package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Failure injection uses fabricated work and synthetic scores; never opens saved real inputs. */
class SavedPredictionCalibrationReceiptTest {
    @TempDir Path temp;
    private final List<String> candidates = List.of("synthetic-a", "synthetic-b");
    private final IOException original = new IOException("synthetic failure; must propagate without copying its raw message");

    private class FakeWork implements SavedPredictionCalibrationOfflineTest.Work<String> {
        final String fault;
        final List<String> prepared = new ArrayList<>(), compared = new ArrayList<>();
        int loads;
        FakeWork(String fault) { this.fault = fault; }
        @Override public void load(ObjectNode receipt) throws Exception {
            loads++; assertTrue(Files.exists(temp.resolve(SavedPredictionCalibrationOfflineTest.MARKER)));
            if (fault.equals("load")) throw original;
        }
        @Override public String prepare(String id) throws Exception {
            prepared.add(id);
            if (fault.equals("prepare") || fault.equals("second") && id.equals("synthetic-b")) throw original;
            return id;
        }
        @Override public ObjectNode compare(String input) throws Exception {
            compared.add(input); if (fault.equals("compare")) throw original;
            var result = PredictionCalibration.evaluateStandard(SavedPredictionCalibrationTest.M, SavedPredictionCalibrationTest.contract(), "0.2");
            for (String code : List.of("PP3", "BP4")) result.withObject("/criteria").putObject(code).put("status", "NOT_EVALUATED").put("appliedStrength", "NONE");
            if (fault.equals("assertion")) result.put("appliedStrength", "SUPPORTING");
            return result;
        }
        @Override public void verifyPreservation(ObjectNode receipt) throws IOException {
            if (fault.equals("preservation")) throw original;
        }
    }
    private JsonNode receipt() throws IOException {
        return SavedPredictionCalibrationTest.M.readTree(Files.readAllBytes(temp.resolve(SavedPredictionCalibrationOfflineTest.OUTPUT)));
    }
    private ObjectNode run(FakeWork work) throws Exception {
        return SavedPredictionCalibrationOfflineTest.executeOnce(temp, candidates, work, SavedPredictionCalibrationOfflineTest::writeReceipt);
    }

    @ParameterizedTest @CsvSource({"load,INPUT_LOAD,0,0,0", "prepare,SOURCE_PREPARE,1,0,0", "compare,COMPARISON,1,1,0",
            "assertion,RESULT_VALIDATION,1,1,0", "second,SOURCE_PREPARE,2,1,1", "preservation,INPUT_PRESERVATION,2,2,2"})
    void everyFailureWritesReceiptAndFailsCallerWithoutLosingEarlierWork(String fault, String stage, int prepares, int compares, int calibrated) throws Exception {
        var work = new FakeWork(fault);
        Class<? extends Throwable> expected = fault.equals("assertion") ? AssertionError.class : IOException.class;
        Throwable thrown = assertThrows(expected, () -> run(work));
        if (!fault.equals("assertion")) assertSame(original, thrown);
        JsonNode out = receipt();
        assertEquals("FAILED", out.path("batchStatus").asText()); assertEquals(stage, out.at("/failure/stage").asText());
        assertEquals("OFFLINE_" + stage + "_FAILED", out.at("/failure/reasonCode").asText());
        assertEquals(prepares, out.path("sourceVerificationAttempts").asInt()); assertEquals(prepares, work.prepared.size());
        assertEquals(compares, out.path("comparatorCalls").asInt()); assertEquals(compares, work.compared.size());
        assertEquals(calibrated, out.path("realObservationsCalibrated").asInt()); assertEquals(0, out.path("acceptedCalibrations").asInt());
        assertFalse(out.toString().contains(original.getMessage()));
        if (prepares < 2) assertEquals("NOT_ATTEMPTED", out.at("/results/1/status").asText());
        if (fault.equals("assertion")) assertEquals("SUPPORTING", out.at("/results/0/calibration/appliedStrength").asText());
        if (calibrated > 0) assertEquals("CALIBRATED", out.at("/results/0/calibration/status").asText());
        assertFalse(out.at("/results/0/accepted").asBoolean());
        if (fault.equals("second")) assertEquals("synthetic-b", out.at("/failure/candidateId").asText());
    }

    @Test void successfulSyntheticBatchPersistsExactCountsAndUnappliedCriteria() throws Exception {
        var work = new FakeWork("none"); var out = run(work); assertEquals(out, receipt());
        assertEquals("SUCCEEDED", out.path("batchStatus").asText()); assertEquals("VERIFIED", out.path("inputPreservationStatus").asText());
        for (String count : List.of("sourceVerificationAttempts", "comparatorCalls", "realObservationsCalibrated", "acceptedCalibrations")) assertEquals(2, out.path(count).asInt());
        for (JsonNode item : out.path("results")) {
            assertTrue(item.path("accepted").asBoolean()); assertEquals("CALIBRATED", item.path("status").asText());
            SavedPredictionCalibrationOfflineTest.verifyResult(item.path("calibration"));
        }
        byte[] marker = Files.readAllBytes(temp.resolve(SavedPredictionCalibrationOfflineTest.MARKER));
        byte[] saved = Files.readAllBytes(temp.resolve(SavedPredictionCalibrationOfflineTest.OUTPUT));
        assertThrows(FileAlreadyExistsException.class, () -> run(work));
        assertEquals(1, work.loads); assertEquals(2, work.prepared.size()); assertEquals(2, work.compared.size());
        assertArrayEquals(marker, Files.readAllBytes(temp.resolve(SavedPredictionCalibrationOfflineTest.MARKER)));
        assertArrayEquals(saved, Files.readAllBytes(temp.resolve(SavedPredictionCalibrationOfflineTest.OUTPUT)));
    }

    @Test void writeFailureRetainsPrimaryCauseAndCannotAuthorizeSecondAttempt() throws Exception {
        var work = new FakeWork("prepare"); var writeError = new IOException("synthetic write failure"); var captured = new AtomicReference<ObjectNode>();
        var thrown = assertThrows(IOException.class, () -> SavedPredictionCalibrationOfflineTest.executeOnce(temp, candidates, work, (path, out) -> {
            captured.set(out); throw writeError;
        }));
        assertSame(original, thrown); assertArrayEquals(new Throwable[] {writeError}, thrown.getSuppressed());
        var out = captured.get(); assertNotNull(out);
        assertEquals("SOURCE_PREPARE", out.at("/failure/stage").asText()); assertEquals("RECEIPT_WRITE", out.at("/receiptWriteFailure/stage").asText());
        assertFalse(Files.exists(temp.resolve(SavedPredictionCalibrationOfflineTest.OUTPUT)));
        assertThrows(FileAlreadyExistsException.class, () -> run(work)); assertEquals(1, work.prepared.size());
    }

    @Test void writeFailureAfterTwoComparisonsStillFailsAndKeepsUnacceptedResults() throws Exception {
        var work = new FakeWork("none"); var writeError = new IOException("synthetic write failure"); var captured = new AtomicReference<ObjectNode>();
        assertSame(writeError, assertThrows(IOException.class, () -> SavedPredictionCalibrationOfflineTest.executeOnce(temp, candidates, work, (path, out) -> {
            captured.set(out); throw writeError;
        })));
        var out = captured.get(); assertNotNull(out);
        assertEquals("FAILED", out.path("batchStatus").asText()); assertEquals(2, out.path("comparatorCalls").asInt());
        assertEquals(2, out.path("realObservationsCalibrated").asInt()); assertEquals(0, out.path("acceptedCalibrations").asInt());
        assertFalse(out.at("/results/0/accepted").asBoolean()); assertTrue(out.at("/results/1/calibration/sourcePointValue").isNull());
    }

    @Test void existingOutputWithoutMarkerIsNeverOverwrittenOrExecuted() throws Exception {
        Path output = temp.resolve(SavedPredictionCalibrationOfflineTest.OUTPUT); Files.writeString(output, "keep-original");
        var work = new FakeWork("none"); assertThrows(FileAlreadyExistsException.class, () -> run(work));
        assertEquals("keep-original", Files.readString(output)); assertEquals(0, work.loads);
        assertFalse(Files.exists(temp.resolve(SavedPredictionCalibrationOfflineTest.MARKER)));
    }
}
