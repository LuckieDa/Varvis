package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GnomadLocalAllSitesTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    Path data, manifest;
    GnomadLocalAllSites prepare(String rows) throws Exception {
        data = temporary.resolve("all-sites.tsv.bgz"); manifest = temporary.resolve("manifest.json");
        try (var out = new BlockCompressedOutputStream(data.toFile())) {
            out.write((GnomadLocalAllSites.HEADER + "\n" + rows).getBytes(StandardCharsets.UTF_8));
        }
        GnomadLocalAllSites.build(mapper, data, temporary.resolve("index.bin"), manifest, GnomadLocalAllSites.sha(data));
        return new GnomadLocalAllSites(mapper, manifest, GnomadLocalAllSites.sha(manifest));
    }
    ObjectNode allele(String contig, int position) {
        return mapper.createObjectNode().put("assembly", "GRCh38").put("contig", contig).put("position", position)
                .put("reference", "A").put("alternate", "G");
    }
    @Test void sparseSeekCrossesBgzfBlocksAndContigsAndKeepsZeroDistinctFromMissing() throws Exception {
        var rows = new StringBuilder();
        for (int i = 1; i <= 12500; i++) rows.append("chr1:").append(i * 2).append("\t").append(i == 1 ? 0 : 10000).append("\tfalse\tfalse\tfalse\tfalse\n");
        rows.append("chr2:7\t4000\tfalse\ttrue\tfalse\tfalse\nchrX:10\t0\ttrue\ttrue\tfalse\ttrue\n")
                .append("chrY:10\t0\ttrue\ttrue\tfalse\ttrue\nchrM:427\t0\ttrue\ttrue\tfalse\ttrue\n");
        var resource = prepare(rows.toString());
        for (int pos : new int[]{2, 8192, 8194, 16384, 25000}) {
            var r = resource.query(allele("1", pos)); assertEquals("HIT", r.path("status").asText(), r.toString());
            assertTrue(r.path("rowsScanned").asInt() <= 4097);
        }
        assertEquals("0", resource.query(allele("1", 2)).path("adjAn").asText());
        for (int pos : new int[]{1, 8193, 25001}) assertEquals("NO_RECORD", resource.query(allele("1", pos)).path("status").asText());
        assertEquals("4000", resource.query(allele("2", 7)).path("adjAn").asText());
        assertEquals("NO_RECORD", resource.query(allele("3", 7)).path("status").asText());
        assertEquals("NOT_APPLICABLE", resource.query(allele("X", 7)).path("status").asText());
        assertEquals("NOT_APPLICABLE", resource.query(allele("MT", 427)).path("status").asText());
    }
    @Test void cacheStillChecksFileIdentityAndNeverBecomesAResultFromAnotherAllele() throws Exception {
        var resource = prepare("chr1:10\t6000\tfalse\tfalse\tfalse\tfalse\n");
        var first = resource.query(allele("1", 10));
        var secondAllele = allele("1", 10).put("alternate", "T");
        var second = resource.query(secondAllele);
        assertEquals(1, resource.queries()); assertEquals(secondAllele, second.path("queryAllele"));
        assertEquals(first.path("rawRecord"), second.path("rawRecord"));
        Files.setLastModifiedTime(data, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60000));
        assertEquals("UNAVAILABLE", resource.query(secondAllele).path("status").asText());
    }
    @Test void indexOrManifestTamperingAndWrongSourceHashAreRejected() throws Exception {
        prepare("chr1:10\t6000\tfalse\tfalse\tfalse\tfalse\n");
        assertThrows(IllegalArgumentException.class, () -> new GnomadLocalAllSites(mapper, manifest, "0".repeat(64)));
        Files.write(temporary.resolve("index.bin"), new byte[]{1}, StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> new GnomadLocalAllSites(mapper, manifest, GnomadLocalAllSites.sha(manifest)));
        assertThrows(Exception.class, () -> GnomadLocalAllSites.build(mapper, data, temporary.resolve("other.bin"), temporary.resolve("other.json"), "0".repeat(64)));
    }
    @Test void duplicatePositionsAreRejectedBeforeManifestPublication() {
        assertThrows(Exception.class, () -> prepare("chr1:10\t6000\tfalse\tfalse\tfalse\tfalse\nchr1:10\t7000\tfalse\tfalse\tfalse\tfalse\n"));
        assertFalse(Files.exists(temporary.resolve("manifest.json")));
    }
    @Test void malformedNumbersOrMissingFlagsAreNotConvertedToZeroOrPass() {
        for (String line : new String[]{"chr1:1\tNA\tfalse\tfalse\tfalse\tfalse", "chr1:1\t20\tNA\tfalse\tfalse\tfalse", "chr1:1\t-1\tfalse\tfalse\tfalse\tfalse"})
            assertThrows(Exception.class, () -> GnomadLocalAllSites.parse(line));
    }

    @Test void crcFailureCannotBecomeAbsenceEvenWhenSizeAndModificationTimeMatch() throws Exception {
        prepare("chr1:10\t6000\tfalse\tfalse\tfalse\tfalse\n");
        var time = Files.getLastModifiedTime(data);
        try (var file = new java.io.RandomAccessFile(data.toFile(), "rw")) {
            file.seek(16); int size = file.readUnsignedByte() + 256 * file.readUnsignedByte() + 1;
            file.seek(size - 8); int crc = file.readUnsignedByte(); file.seek(size - 8); file.writeByte(crc ^ 1);
        }
        Files.setLastModifiedTime(data, time);
        var reader = new GnomadLocalAllSites(mapper, manifest, GnomadLocalAllSites.sha(manifest));
        assertEquals("UNAVAILABLE", reader.query(allele("1", 10)).path("status").asText());
    }
}
