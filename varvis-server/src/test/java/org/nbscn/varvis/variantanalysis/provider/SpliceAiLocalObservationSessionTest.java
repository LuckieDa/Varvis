package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.IndexFactory;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.variant.vcf.VCFCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import static org.junit.jupiter.api.Assertions.*;

class SpliceAiLocalObservationSessionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path root;
    private final NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");

    private Path fixture(String annotation) throws Exception {
        Path file = root.resolve("scores.masked.snv.hg38.vcf.gz");
        try (var out = new BlockCompressedOutputStream(file.toFile())) {
            out.write(("##fileformat=VCFv4.2\n##reference=GRCh38\n##fileDate=20191004\n"
                    + "##contig=<ID=1,length=248956422>\n##INFO=<ID=SpliceAI,Number=.,Type=String,Description=\"Synthetic fixture\">\n"
                    + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n1\t100\t.\tA\tG\t.\t.\tSpliceAI=" + annotation + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        IndexFactory.createTabixIndex(file.toFile(), new VCFCodec(), TabixFormat.VCF, null).writeBasedOnFeatureFile(file.toFile());
        return file;
    }
    private SpliceAiLocalObservationSession session(Path file) {
        var properties = new VariantAnalysisProperties(); properties.getSpliceAi().setEnabled(true);
        properties.getSpliceAi().setVersion("v1.3.1"); properties.getSpliceAi().setSnvResourcePath(file.toString());
        return new SpliceAiProvider(properties).openSharedSession(mapper);
    }
    @Test void capturesAllRowsAndOriginalPrecisionWithoutRnaOrParameterClaims() throws Exception {
        var session = session(fixture("G|GENE1|0.0000|0.0200|0.9100|0.0400|+1|-2|3|4,G|GENE2|0.99|0.00|0.00|0.00|0|0|0|0"));
        var result = session.observe(key, "SYNTHETIC");
        var raw = result.observation().snapshot().path("sourceRecord");
        assertEquals("HIT", raw.at("/queryOutcome/status").asText());
        assertEquals(2, raw.path("records").size());
        assertEquals("0.0000", raw.at("/records/0/DS_AG").asText());
        assertEquals("+1", raw.at("/records/0/DP_AG_original").asText());
        assertEquals(-2, raw.at("/records/0/DP_AL").asInt());
        assertEquals("MASKED", raw.at("/resource/scoreMode").asText());
        assertEquals("UNKNOWN", raw.at("/resource/toolVersion").asText());
        assertEquals("UNKNOWN", raw.at("/resource/parameters/status").asText());
        assertEquals("NOT_ESTABLISHED", result.observation().snapshot().path("rnaApproval").asText());
        assertEquals("NOT_EVALUATED", result.observation().snapshot().path("applicationStatus").asText());
        assertEquals("0.9100", result.observation().events(0).path("rawScore").asText());
        assertEquals(raw, result.source().resolve(result.sourceReference()));
        assertSame(result, session.observe(key, "SYNTHETIC")); assertEquals(1, session.providerCalls());
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw).removeAll();
        assertEquals(2, result.observation().snapshot().at("/sourceRecord/records").size());
    }
    @Test void absentMalformedAndUnavailableRemainDifferentAndFailureIsMemoizedOnlyAsFailure() throws Exception {
        var session = session(fixture("G|GENE1|.|0.02|0.91|0.04|1|2|3|4"));
        var failed = session.observe(key, "SYNTHETIC").observation().snapshot();
        assertEquals("FAILED", failed.at("/sourceRecord/resourceExecution/status").asText());
        assertEquals("NOT_QUERIED", failed.at("/sourceRecord/queryOutcome/status").asText());
        assertEquals(failed, session.observe(key, "SYNTHETIC").observation().snapshot()); assertEquals(1, session.providerCalls());
        var absent = session.observe(new NormalizedVariantKey("GRCh38", "1", 101, "A", "G"), "SYNTHETIC").observation().snapshot();
        assertEquals("NO_RECORD", absent.at("/sourceRecord/queryOutcome/status").asText());
        var unavailable = session(root.resolve("absent.gz")).observe(key, "SYNTHETIC").observation().snapshot();
        assertEquals("UNAVAILABLE", unavailable.at("/sourceRecord/resourceAvailability/status").asText());
        assertEquals("NOT_QUERIED", unavailable.at("/sourceRecord/queryOutcome/status").asText());
    }
    @Test void changedResourceInvalidatesCacheAndExplicitIndexIsActuallyUsed() throws Exception {
        Path file = fixture("G|GENE1|0.00|0.02|0.91|0.04|1|2|3|4");
        Path defaultIndex = Path.of(file + ".tbi"), chosenIndex = root.resolve("chosen.tbi");
        Files.move(defaultIndex, chosenIndex);
        var properties = new VariantAnalysisProperties(); properties.getSpliceAi().setEnabled(true);
        properties.getSpliceAi().setSnvResourcePath(file.toString()); properties.getSpliceAi().setSnvIndexPath(chosenIndex.toString());
        var session = new SpliceAiProvider(properties).openSharedSession(mapper);
        assertEquals("HIT", session.observe(key, "SYNTHETIC").observation().snapshot().at("/sourceRecord/queryOutcome/status").asText());
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
        session.observe(key, "SYNTHETIC"); assertEquals(2, session.providerCalls());
        Files.writeString(chosenIndex, "broken");
        assertEquals("FAILED", session.observe(key, "SYNTHETIC").observation().snapshot().at("/sourceRecord/resourceExecution/status").asText());
    }
    @Test void anExactVcfRowWithWrongSpliceAlleleIsAFailureNotNoRecord() throws Exception {
        var result = session(fixture("T|GENE1|0.00|0.00|0.00|0.00|0|0|0|0")).observe(key, "SYNTHETIC").observation().snapshot();
        assertEquals("SPLICE_RECORD_ALLELE_MISMATCH", result.at("/sourceRecord/resourceExecution/reasonCode").asText());
        assertEquals("NOT_QUERIED", result.at("/sourceRecord/queryOutcome/status").asText());
        assertEquals(1, result.at("/sourceRecord/rawRecords").size());
    }
    @Test @EnabledIfSystemProperty(named = "varvis.u14.splice.resource", matches = ".+")
    void fixedRealMaskedPointQuery() throws Exception {
        var session = session(Path.of(System.getProperty("varvis.u14.splice.resource")));
        var capture = session.observe(new NormalizedVariantKey("GRCh38", "1", 925775, "G", "T"), "PRIVACY_BOUNDED_RUNTIME");
        var result = capture.observation().snapshot();
        Path output = Path.of(System.getProperty("varvis.u14.splice.output"));
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertEquals("HIT", result.at("/sourceRecord/queryOutcome/status").asText(), result.toPrettyString());
        assertEquals("0.96", result.at("/sourceRecord/records/0/DS_DG").asText());
        assertEquals(-2, result.at("/sourceRecord/records/0/DP_DG").asInt());
        assertEquals("PARSED", capture.observation().events(0).path("status").asText());
        assertSame(capture, session.observe(new NormalizedVariantKey("GRCh38", "1", 925775, "G", "T"), "PRIVACY_BOUNDED_RUNTIME"));
        assertEquals(1, session.providerCalls());
    }
}
