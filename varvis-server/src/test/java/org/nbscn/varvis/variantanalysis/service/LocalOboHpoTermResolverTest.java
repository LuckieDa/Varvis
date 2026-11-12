package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.HpoOntologyTermStatus;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalOboHpoTermResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesCurrentUnknownReplacementChainAndTermLevelObsoleteFailures() throws Exception {
        Path file = write("hp.obo", validOntology());
        LocalOboHpoTermResolver resolver = resolver(file, "synthetic-v1");

        assertTrue(resolver.isAvailable());
        assertEquals("synthetic-v1", resolver.getDataVersion());
        assertResolution(resolver.resolve("HP:0000001"), HpoOntologyTermStatus.CURRENT,
                "HP:0000001", true);
        HpoTermResolver.Resolution currentAlias = resolver.resolve("HP:0000101");
        assertResolution(currentAlias, HpoOntologyTermStatus.CURRENT_ALIAS,
                "HP:0000001", true);
        assertEquals("HP:0000101", currentAlias.getRequestedHpoId());
        assertEquals("HP:0000001", currentAlias.getReplacementHpoId());
        assertEquals("Current term", currentAlias.getLabel());
        HpoTermResolver.Resolution replaced = resolver.resolve("HP:0000002");
        assertResolution(replaced, HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT,
                "HP:0000001", true);
        assertEquals("HP:0000001", replaced.getReplacementHpoId());
        assertEquals("Current term", replaced.getLabel());
        HpoTermResolver.Resolution obsoleteAlias = resolver.resolve("HP:0000102");
        assertResolution(obsoleteAlias, HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT,
                "HP:0000001", true);
        assertEquals("HP:0000102", obsoleteAlias.getRequestedHpoId());
        assertEquals("HP:0000001", obsoleteAlias.getReplacementHpoId());
        assertResolution(resolver.resolve("HP:0000004"), HpoOntologyTermStatus.OBSOLETE_UNRESOLVED,
                null, false);
        assertResolution(resolver.resolve("HP:0000005"), HpoOntologyTermStatus.OBSOLETE_AMBIGUOUS,
                null, false);
        assertResolution(resolver.resolve("HP:0000007"), HpoOntologyTermStatus.OBSOLETE_AMBIGUOUS,
                null, false);
        assertResolution(resolver.resolve("HP:9999999"), HpoOntologyTermStatus.UNKNOWN,
                null, false);
    }

    @Test
    void missingIntegrityMetadataHashOrVersionMakesTheWholeResolverUnavailable() throws Exception {
        Path file = write("hp.obo", validOntology());

        assertUnavailable(new LocalOboHpoTermResolver(file, "synthetic-v1", null));
        assertUnavailable(new LocalOboHpoTermResolver(file, "wrong-version", sha256(file)));
        assertUnavailable(new LocalOboHpoTermResolver(file, "synthetic-v1", repeat('0', 64)));
        assertUnavailable(new LocalOboHpoTermResolver(tempDir.resolve("missing.obo"),
                "synthetic-v1", repeat('0', 64)));
        assertUnavailable(new LocalOboHpoTermResolver(Path.of("hp.obo"),
                "synthetic-v1", repeat('0', 64)));
        assertUnavailable(new LocalOboHpoTermResolver(Path.of("\\\\no-such-host\\share\\hp.obo"),
                "synthetic-v1", repeat('0', 64)));
    }

    @Test
    void malformedUtf8WrongFormatMissingTargetAndCyclesNeverPublishPartialIndex() throws Exception {
        Path wrongFormat = write("wrong-format.obo", validOntology().replace("format-version: 1.2", "format-version: 1.4"));
        assertUnavailable(resolver(wrongFormat, "synthetic-v1"));

        Path missingTarget = write("missing-target.obo",
                header() + term("HP:0000001", "Obsolete", true, "HP:0000999"));
        assertUnavailable(resolver(missingTarget, "synthetic-v1"));

        Path cycle = write("cycle.obo",
                header()
                        + term("HP:0000001", "First", true, "HP:0000002")
                        + term("HP:0000002", "Second", true, "HP:0000001"));
        assertUnavailable(resolver(cycle, "synthetic-v1"));

        Path branchedCycle = write("branched-cycle.obo",
                header()
                        + term("HP:0000001", "Branch", true, "HP:0000002", "HP:0000003")
                        + term("HP:0000002", "Cycle back", true, "HP:0000001")
                        + term("HP:0000003", "Current", false));
        assertUnavailable(resolver(branchedCycle, "synthetic-v1"));

        Path duplicate = write("duplicate.obo",
                header()
                        + term("HP:0000001", "First", false)
                        + term("HP:0000001", "Second", false));
        assertUnavailable(resolver(duplicate, "synthetic-v1"));

        Path malformedUtf8 = tempDir.resolve("malformed.obo");
        byte[] prefix = header().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[prefix.length + 2];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        bytes[prefix.length] = (byte) 0xc3;
        bytes[prefix.length + 1] = (byte) 0x28;
        Files.write(malformedUtf8, bytes);
        assertUnavailable(resolver(malformedUtf8, "synthetic-v1"));

        Path oversizedLine = write("oversized-line.obo",
                header() + "remark: " + repeat('x', LocalOboHpoTermResolver.MAX_LINE_LENGTH + 1));
        assertUnavailable(resolver(oversizedLine, "synthetic-v1"));
    }

    @Test
    void deepReplacementChainUsesBoundedIterativeGraphResolution() throws Exception {
        int count = 5_000;
        StringBuilder ontology = new StringBuilder(header());
        for (int index = 1; index <= count; index++) {
            String id = String.format("HP:%07d", index);
            if (index == count) {
                ontology.append(term(id, "Current", false));
            } else {
                ontology.append(term(id, "Obsolete " + index, true,
                        String.format("HP:%07d", index + 1)));
            }
        }
        Path file = write("deep-chain.obo", ontology.toString());

        LocalOboHpoTermResolver resolver = resolver(file, "synthetic-v1");

        assertTrue(resolver.isAvailable());
        assertResolution(resolver.resolve("HP:0000001"),
                HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT,
                String.format("HP:%07d", count),
                true);
    }

    @Test
    void replacementTargetsMayUseAliasesButAliasConflictsInvalidateTheWholeSnapshot() throws Exception {
        Path aliasTarget = write("alias-target.obo",
                header()
                        + termWithAliases("HP:0000001", "Current", false,
                        new String[]{"HP:0000101"})
                        + term("HP:0000002", "Obsolete", true, "HP:0000101"));
        assertResolution(resolver(aliasTarget, "synthetic-v1").resolve("HP:0000002"),
                HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT,
                "HP:0000001",
                true);

        Path duplicateAlias = write("duplicate-alias.obo",
                header()
                        + termWithAliases("HP:0000001", "First", false,
                        new String[]{"HP:0000101"})
                        + termWithAliases("HP:0000002", "Second", false,
                        new String[]{"HP:0000101"}));
        assertUnavailable(resolver(duplicateAlias, "synthetic-v1"));

        Path repeatedAlias = write("repeated-alias.obo",
                header() + termWithAliases("HP:0000001", "First", false,
                        new String[]{"HP:0000101", "HP:0000101"}));
        assertUnavailable(resolver(repeatedAlias, "synthetic-v1"));

        Path primaryConflict = write("alias-primary-conflict.obo",
                header()
                        + termWithAliases("HP:0000001", "First", false,
                        new String[]{"HP:0000002"})
                        + term("HP:0000002", "Second", false));
        assertUnavailable(resolver(primaryConflict, "synthetic-v1"));
    }

    @Test
    void alternateIdentifierCountHasAnIndependentSafetyLimit() throws Exception {
        StringBuilder ontology = new StringBuilder(header())
                .append("[Term]\n")
                .append("id: HP:0000001\n")
                .append("name: Alias-heavy term\n");
        for (int index = 0; index <= LocalOboHpoTermResolver.MAX_ALIASES; index++) {
            ontology.append("alt_id: HP:").append(1_000_000 + index).append('\n');
        }
        Path file = write("too-many-aliases.obo", ontology.append('\n').toString());

        assertUnavailable(resolver(file, "synthetic-v1"));
    }

    @Test
    void symbolicFileIsRejectedWhenThePlatformAllowsCreatingIt() throws Exception {
        Path target = write("target.obo", validOntology());
        Path link = tempDir.resolve("link.obo");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ex) {
            return;
        }

        assertUnavailable(new LocalOboHpoTermResolver(link, "synthetic-v1", sha256(target)));
    }

    @Test
    void nonDefaultFilesystemProviderIsRejectedBeforeOntologyAccess() throws Exception {
        Path archive = tempDir.resolve("ontology.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(
                uri, Collections.singletonMap("create", "true"))) {
            Path nested = fileSystem.getPath("/hp.obo");
            Files.writeString(nested, validOntology(), StandardCharsets.UTF_8);

            assertUnavailable(new LocalOboHpoTermResolver(
                    nested, "synthetic-v1", sha256(nested)));
        }
    }

    private LocalOboHpoTermResolver resolver(Path file, String version) throws Exception {
        return new LocalOboHpoTermResolver(file, version, sha256(file));
    }

    private void assertUnavailable(LocalOboHpoTermResolver resolver) {
        assertFalse(resolver.isAvailable());
        HpoTermResolver.Resolution resolution = resolver.resolve("HP:9999999");
        assertEquals(HpoOntologyTermStatus.UNAVAILABLE, resolution.getStatus());
        assertEquals("HP:9999999", resolution.getEffectiveHpoId());
        assertTrue(resolution.isUsable());
        assertEquals("HPO_ONTOLOGY_UNAVAILABLE", resolution.getWarningCode());
    }

    private void assertResolution(HpoTermResolver.Resolution resolution,
                                  HpoOntologyTermStatus status,
                                  String effective,
                                  boolean usable) {
        assertEquals(status, resolution.getStatus());
        assertEquals(effective, resolution.getEffectiveHpoId());
        assertEquals(usable, resolution.isUsable());
        if (effective == null) {
            assertNull(resolution.getEffectiveHpoId());
        }
    }

    private Path write(String name, String value) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, value, StandardCharsets.UTF_8);
        return file;
    }

    private String validOntology() {
        return header()
                + termWithAliases("HP:0000001", "Current term", false,
                new String[]{"HP:0000101"})
                + termWithAliases("HP:0000002", "Old first", true,
                new String[]{"HP:0000102"}, "HP:0000003")
                + term("HP:0000003", "Old second", true, "HP:0000001")
                + term("HP:0000004", "Old unresolved", true)
                + term("HP:0000005", "Old ambiguous", true, "HP:0000001", "HP:0000006")
                + term("HP:0000006", "Other current", false)
                + term("HP:0000007", "Old chain to ambiguous", true, "HP:0000005");
    }

    private String header() {
        return "format-version: 1.2\n"
                + "data-version: synthetic-v1\n\n";
    }

    private String term(String id, String name, boolean obsolete, String... replacements) {
        return termWithAliases(id, name, obsolete, new String[0], replacements);
    }

    private String termWithAliases(String id,
                                   String name,
                                   boolean obsolete,
                                   String[] aliases,
                                   String... replacements) {
        StringBuilder value = new StringBuilder("[Term]\n")
                .append("id: ").append(id).append('\n')
                .append("name: ").append(name).append('\n');
        for (String alias : aliases) {
            value.append("alt_id: ").append(alias).append('\n');
        }
        if (obsolete) {
            value.append("is_obsolete: true\n");
        }
        for (String replacement : replacements) {
            value.append("replaced_by: ").append(replacement).append('\n');
        }
        return value.append('\n').toString();
    }

    private String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder value = new StringBuilder();
        for (byte current : digest) {
            value.append(String.format("%02x", current & 0xff));
        }
        return value.toString();
    }

    private String repeat(char value, int count) {
        return String.valueOf(value).repeat(count);
    }
}
