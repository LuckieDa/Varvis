package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.Pp4CurrentAdoptionProducer;
import org.nbscn.varvis.variantanalysis.provider.Pp4ServerRegisteredInputs;
import org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture;

/** Archive-only controls with an actual native UNKNOWN producer, not normal clinical adoption acceptance. */
@EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
class T04CurrentClinicalArchiveTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path temporary;
    private record Captured(T04CurrentSessionFixture fixture, CurrentAcmgEvaluationSession session,
            Pp4ServerRegisteredInputs registered, CurrentAdoptionInventory inventory, ObjectNode archive) implements AutoCloseable {
        @Override public void close() { fixture.close(); }
    }
    private Captured capture(String name) throws Exception {
        var fixture = new T04CurrentSessionFixture(temporary.resolve(name), "t04-archive-analysis", "t04-archive-" + name,
                List.of("ACMG_AMP_2015:PP1"), true);
        try {
            var context = T04NativeOriginalFixture.context(fixture); var primary = new T04NativeOriginalFixture(fixture, context);
            primary.nativeSampleLink(fixture, context);
            var registered = Pp4ServerRegisteredInputs.load(primary.writeServerBundle(temporary.resolve(name).resolve("registered"), context));
            var session = fixture.issue(context);
            var producer = new Pp4CurrentAdoptionProducer(registered.registration(), registered.rules());
            var inventory = CurrentAdoptionInventory.capture(session, producer);
            assertFalse(inventory.isComplete(), "legacy two-argument actual producer must remain unavailable; this is not an adoption fixture");
            var archive = CurrentClinicalArchive.capture(json, session, registered, inventory);
            return new Captured(fixture, session, registered, inventory, archive);
        } catch (Exception | AssertionError failure) { fixture.close(); throw failure; }
    }
    private ObjectNode expand(ObjectNode archive, JsonNode inventory, Captured captured) {
        return CurrentClinicalArchive.verifyAndExpand(json, archive, inventory, captured.fixture.analysisId(), captured.fixture.runId());
    }
    private static void reseal(ObjectNode archive) throws Exception {
        archive.remove("archiveSha256"); archive.put("archiveSha256", T04NativeOriginalFixture.canonicalSha(archive));
    }
    private static void reason(String expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(IllegalArgumentException.class, operation).getMessage());
    }

    @Test void actualRegisteredRawBytesAndMaterialOriginalsRoundTripWithoutRestoringAuthority() throws Exception {
        try (var captured = capture("roundtrip")) {
            var saved = captured.archive.deepCopy(); var expanded = expand(saved, captured.inventory.snapshot(), captured);
            assertTrue(saved.path("historyOnly").asBoolean()); assertFalse(saved.path("authorityRestorationSupported").asBoolean());
            assertTrue(expanded.path("historyOnly").asBoolean()); assertFalse(expanded.path("authorityRestorationSupported").asBoolean());
            assertEquals(9, saved.path("rawArtifacts").size()); assertEquals(1, saved.path("materialOriginals").size());
            var configured = captured.registered.archiveArtifacts();
            for (JsonNode binding : expanded.path("rawBindings")) {
                String pin = binding.path("rawSha256").asText(); byte[] bytes = Base64.getDecoder().decode(binding.at("/artifact/base64").asText());
                assertArrayEquals(configured.get(pin), bytes); assertEquals(pin, T04NativeOriginalFixture.rawSha(bytes));
                assertEquals(bytes.length, binding.at("/artifact/byteLength").asInt());
            }
            assertEquals(1, expanded.path("materialReferences").size());
            var original = expanded.at("/materialReferences/0/originals");
            assertEquals(expanded.at("/materialReferences/0/sha256").asText(), T04NativeOriginalFixture.canonicalSha(original));
            assertEquals(13, original.at("/nativeFacts/originalTableReplays").size());
            assertEquals("UNAVAILABLE", captured.inventory.snapshot().at("/attempts/0/terminalReceipt/materialDiagnostic/currentAllocationCapability").asText());
            expanded.withArray("rawBindings").removeAll(); expanded.withArray("materialReferences").removeAll();
            assertEquals(saved, captured.archive, "export never mutates stored content");
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> json.treeToValue(saved.path("sessionIdentity"), CurrentAcmgEvaluationSession.class));
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> json.treeToValue(saved, CurrentClinicalAllocation.Grant.class));
        }
    }

    @Test void missingOrCorruptOriginalBytesAreRejectedEvenAfterArchiveDigestIsRecomputed() throws Exception {
        try (var captured = capture("raw-tamper")) {
            String pin = captured.registered.registration().registeredInputs().originalArtifacts().get(0).expectedRawSha256();
            var missing = captured.archive.deepCopy(); missing.withObject("/rawArtifacts").remove(pin); reseal(missing);
            reason("CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH", () -> expand(missing, captured.inventory.snapshot(), captured));
            var invalid = captured.archive.deepCopy(); invalid.withObject("/rawArtifacts/" + pin).put("base64", "@@not-base64@@"); reseal(invalid);
            reason("CURRENT_CLINICAL_ARCHIVE_RAW_INVALID", () -> expand(invalid, captured.inventory.snapshot(), captured));
            var changed = captured.archive.deepCopy(); byte[] bytes = Base64.getDecoder().decode(changed.at("/rawArtifacts/" + pin + "/base64").asText());
            bytes[bytes.length - 1] ^= 1; changed.withObject("/rawArtifacts/" + pin).put("base64", Base64.getEncoder().encodeToString(bytes)); reseal(changed);
            reason("CURRENT_CLINICAL_ARCHIVE_RAW_PIN_MISMATCH", () -> expand(changed, captured.inventory.snapshot(), captured));
            var length = captured.archive.deepCopy(); length.withObject("/rawArtifacts/" + pin).put("byteLength", 1); reseal(length);
            reason("CURRENT_CLINICAL_ARCHIVE_RAW_PIN_MISMATCH", () -> expand(length, captured.inventory.snapshot(), captured));
        }
    }

    @Test void wrongRunInventoryAndDanglingMaterialReferencesCannotBeReadAsCompleteHistory() throws Exception {
        try (var captured = capture("scope")) {
            reason("CURRENT_CLINICAL_ARCHIVE_REQUIRED", () -> CurrentClinicalArchive.verifyAndExpand(json, null, captured.inventory.snapshot(), "t04-archive-analysis", "t04-archive-scope"));
            reason("CURRENT_CLINICAL_ARCHIVE_TARGET_MISMATCH", () -> CurrentClinicalArchive.verifyAndExpand(json, captured.archive, captured.inventory.snapshot(), "another-analysis", captured.fixture.runId()));
            reason("CURRENT_CLINICAL_ARCHIVE_TARGET_MISMATCH", () -> CurrentClinicalArchive.verifyAndExpand(json, captured.archive, captured.inventory.snapshot(), captured.fixture.analysisId(), "another-run"));
            var changedInventory = captured.inventory.snapshot(); changedInventory.put("complete", true);
            reason("CURRENT_CLINICAL_ARCHIVE_INVENTORY_MISMATCH", () -> expand(captured.archive, changedInventory, captured));
            String pin = captured.archive.path("materialOriginalSha256s").get(0).asText();
            var missing = captured.archive.deepCopy(); missing.withObject("/materialOriginals").remove(pin); reseal(missing);
            reason("CURRENT_CLINICAL_ARCHIVE_MATERIAL_REFERENCES_MISMATCH", () -> expand(missing, captured.inventory.snapshot(), captured));
            var binding = captured.archive.deepCopy();
            ((ObjectNode) binding.withArray("rawBindings").get(0)).put("rawSha256", "0".repeat(64)); reseal(binding);
            reason("CURRENT_CLINICAL_ARCHIVE_BINDING_MISMATCH", () -> expand(binding, captured.inventory.snapshot(), captured));
        }
    }

    @Test void fullyResealedNativeTableProjectionStillMustMatchTheArchivedPrimaryBytes() throws Exception {
        try (var captured = capture("projection")) {
            var archive = captured.archive.deepCopy(); var inventory = captured.inventory.snapshot();
            String oldPin = archive.path("materialOriginalSha256s").get(0).asText();
            var original = (ObjectNode) archive.path("materialOriginals").path(oldPin).deepCopy();
            original.withObject("/nativeFacts/originalTableReplays/CLINICAL:case/table/bodyRows/0/cells/0").put("text", "forged-case-despite-resealing");
            original.put("nativeFactsSha256", T04NativeOriginalFixture.canonicalSha(original.path("nativeFacts")));
            String newPin = T04NativeOriginalFixture.canonicalSha(original);
            archive.withObject("/materialOriginals").remove(oldPin); archive.withObject("/materialOriginals").set(newPin, original);
            archive.withArray("materialOriginalSha256s").removeAll().add(newPin); inventory.withArray("materialOriginalSha256s").removeAll().add(newPin);
            var attempt = (ObjectNode) inventory.path("attempts").get(0); attempt.put("materialOriginalsSha256", newPin);
            var receipt = (ObjectNode) attempt.path("terminalReceipt"); receipt.put("materialOriginalsSha256", newPin);
            var material = (ObjectNode) receipt.path("material"); material.put("originalsSha256", newPin).set("nativeFactsSha256", original.path("nativeFactsSha256"));
            material.remove("materialSha256"); material.put("materialSha256", T04NativeOriginalFixture.canonicalSha(material));
            receipt.set("materialSha256", material.path("materialSha256")); receipt.remove("receiptSha256"); receipt.put("receiptSha256", T04NativeOriginalFixture.canonicalSha(receipt));
            attempt.set("terminalReceiptSha256", receipt.path("receiptSha256")); attempt.put("terminalSnapshotSha256", T04NativeOriginalFixture.canonicalSha(receipt));
            inventory.remove("inventorySha256"); inventory.put("inventorySha256", T04NativeOriginalFixture.canonicalSha(inventory));
            archive.put("inventorySnapshotSha256", T04NativeOriginalFixture.canonicalSha(inventory)); reseal(archive);
            reason("CURRENT_CLINICAL_ARCHIVE_TABLE_REPLAY_MISMATCH", () -> expand(archive, inventory, captured));
            assertEquals("case-target", captured.inventory.materialOriginals().get(oldPin).at("/nativeFacts/case/case_id").asText());
        }
    }

    @Test void actualBusinessStoreRetainsAndReadsTheCompleteInertArchive() throws Exception {
        try (var captured = capture("store")) {
            var privateData = json.createObjectNode(); privateData.set("currentClinicalArchive", captured.archive); privateData.set("inventory", captured.inventory.snapshot());
            String privateJson = json.writeValueAsString(privateData), responseJson = json.writeValueAsString(captured.inventory.snapshot());
            captured.fixture.store().finish(captured.fixture.runId(), "COMPLETED", responseJson, privateJson);
            var saved = captured.fixture.store().run(captured.fixture.analysisId(), captured.fixture.runId()).orElseThrow();
            assertEquals(privateJson, saved.privateJson()); assertEquals(responseJson, saved.responseJson());
            var read = json.readTree(saved.privateJson());
            assertEquals(expand(captured.archive, captured.inventory.snapshot(), captured),
                    CurrentClinicalArchive.verifyAndExpand(json, read.path("currentClinicalArchive"), read.path("inventory"), saved.analysisId(), saved.id()));
        }
    }
}
