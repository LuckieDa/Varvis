package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.service.T04CurrentSessionFixture;

/** Real manifest/raw loaders over explicitly SYNTHETIC originals, not clinical adoption. */
@EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
class Pp4ServerRegisteredInputsTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void acquiredOriginalsAndSampleAssociationOwnTheirExactBytesAndStableIdentity() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(directory.resolve("engine"), "loader-analysis", "loader-run", List.of("ACMG_AMP_2015:PP1"))) {
            var context = T04NativeOriginalFixture.context(fixture);
            var originals = new T04NativeOriginalFixture(fixture, context); originals.nativeSampleLink(fixture, context);
            originals.set("CLINICAL", "case", 0, "sample_id", "clinical-code-A");
            originals.set("CLINICAL", "sample_links", 0, "clinical_sample_id", "clinical-code-A");
            Path manifest = originals.writeServerBundle(directory.resolve("registered"), context);
            var loaded = Pp4ServerRegisteredInputs.load(manifest); var replay = Pp4ServerRegisteredInputs.load(manifest);
            assertEquals(loaded.identity(), replay.identity());
            assertEquals(9, loaded.archiveArtifacts().size(), "manifest, guidance, rules and six originals are captured once");
            assertEquals(1, loaded.clinicalSampleLinks().size()); var link = loaded.clinicalSampleLinks().get(0);
            assertEquals("clinical-code-A", link.clinicalSampleId()); assertEquals(fixture.sampleId(), link.vcfSampleId());
            assertEquals(1, link.sampleOrdinal()); assertEquals(Pp4ProductionMethodRegistration.rawSha(fixture.originalVcfBytes()), link.vcfRawSha256());
            assertFalse(link.snapshot().at("/sourceRef/cells").isEmpty());
            var material = Pp4QualifiedMaterial.verify(loaded.registration(), context).material().orElseThrow(); assertTrue(link.matches(material));
            assertEquals(loaded.rules().identity().path("templateRawSha256").asText(), Pp4ProductionMethodRegistration.rawSha(loaded.rules().originalBytes()));
            var exported = loaded.archiveArtifacts(); String pin = exported.keySet().iterator().next(); byte[] bytes = exported.get(pin); bytes[0] ^= 1;
            assertEquals(pin, Pp4ProductionMethodRegistration.rawSha(loaded.archiveArtifacts().get(pin)));
            assertThrows(UnsupportedOperationException.class, () -> exported.clear());
            var forgedLink = json.createObjectNode(); forgedLink.set("value", link.snapshot()); forgedLink.put("registrationSha256", loaded.registration().sha256());
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> new ObjectMapper().findAndRegisterModules()
                    .treeToValue(forgedLink, Pp4ServerRegisteredInputs.SampleLink.class));
        }
    }

    @Test void missingOrCrossPersonOriginalAssociationIsNotReplacedByTheSameSampleName() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(directory.resolve("engine"), "loader-analysis", "loader-run", List.of("ACMG_AMP_2015:PP1"))) {
            var context = T04NativeOriginalFixture.context(fixture);
            var wrong = new T04NativeOriginalFixture(fixture, context); wrong.nativeSampleLink(fixture, context);
            wrong.set("CLINICAL", "sample_links", 0, "person_id", "another-clinical-person");
            Path path = wrong.writeServerBundle(directory.resolve("wrong"), context);
            assertEquals("PP4_SERVER_SAMPLE_LINK_CLINICAL_SCOPE_MISMATCH", assertThrows(IOException.class, () -> Pp4ServerRegisteredInputs.load(path)).getMessage());
            var empty = new T04NativeOriginalFixture(fixture, context); empty.nativeSampleLink(fixture, context); empty.remove("CLINICAL", "sample_links", 0);
            Path absent = empty.writeServerBundle(directory.resolve("empty"), context);
            assertEquals("PP4_SERVER_CLINICAL_SAMPLE_LINKS_REQUIRED", assertThrows(IOException.class, () -> Pp4ServerRegisteredInputs.load(absent)).getMessage());
            var ordinal = new T04NativeOriginalFixture(fixture, context); ordinal.nativeSampleLink(fixture, context); ordinal.set("CLINICAL", "sample_links", 0, "vcf_sample_ordinal", "0");
            Path zero = ordinal.writeServerBundle(directory.resolve("zero"), context);
            assertEquals("PP4_SERVER_SAMPLE_LINK_ORDINAL_INVALID", assertThrows(IOException.class, () -> Pp4ServerRegisteredInputs.load(zero)).getMessage());
        }
    }

    @Test void changedRawOriginalAndEscapingPathsAreRefusedWithoutRepairingPins() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(directory.resolve("engine"), "loader-analysis", "loader-run", List.of("ACMG_AMP_2015:PP1"))) {
            var context = T04NativeOriginalFixture.context(fixture); var original = new T04NativeOriginalFixture(fixture, context); original.nativeSampleLink(fixture, context);
            Path path = original.writeServerBundle(directory.resolve("bytes"), context);
            var manifest = (ObjectNode) json.readTree(Files.readAllBytes(path)); Path raw = path.getParent().resolve(manifest.at("/originals/0/file").asText());
            Files.write(raw, " ".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
            assertEquals("PP4_SERVER_ORIGINAL_PIN_MISMATCH", assertThrows(IOException.class, () -> Pp4ServerRegisteredInputs.load(path)).getMessage());
            Path outside = original.writeServerBundle(directory.resolve("path"), context);
            var escaping = (ObjectNode) json.readTree(Files.readAllBytes(outside)); ((ObjectNode) escaping.path("rules")).put("file", "../outside.json"); Files.write(outside, json.writeValueAsBytes(escaping));
            assertEquals("PP4_SERVER_ORIGINAL_PATH_OUTSIDE_BUNDLE", assertThrows(IOException.class, () -> Pp4ServerRegisteredInputs.load(outside)).getMessage());
        }
    }

    @Test void unknownApprovalFieldsAndFutureAssociationCannotIssueRegistration() throws Exception {
        try (var fixture = new T04CurrentSessionFixture(directory.resolve("engine"), "loader-analysis", "loader-run", List.of("ACMG_AMP_2015:PP1"))) {
            var context = T04NativeOriginalFixture.context(fixture); var original = new T04NativeOriginalFixture(fixture, context); original.nativeSampleLink(fixture, context);
            Path path = original.writeServerBundle(directory.resolve("flag"), context);
            var manifest = (ObjectNode) json.readTree(Files.readAllBytes(path)); manifest.put("approved", true); Files.write(path, json.writeValueAsBytes(manifest));
            assertThrows(IOException.class, () -> Pp4ServerRegisteredInputs.load(path));
            original.set("CLINICAL", "sample_links", 0, "issued_at", "2026-01-01T00:00:00Z");
            Path future = original.writeServerBundle(directory.resolve("future"), context);
            assertEquals("PP4_SERVER_SAMPLE_LINK_ACQUISITION_MISMATCH", assertThrows(IOException.class, () -> Pp4ServerRegisteredInputs.load(future)).getMessage());
        }
    }
}
