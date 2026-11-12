package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossPublicSourcesTest {
    final ObjectMapper mapper = new ObjectMapper();
    private com.fasterxml.jackson.databind.node.ObjectNode emptyGene() {
        var doc = mapper.createObjectNode();
        doc.putObject("status").put("code", 404).put("msg", "No records were found for given query").put("name", "Not Found");
        doc.putObject("metadata").putObject("rendered").put("by", Pvs1StartLossPublicSources.BASE + "summary/srvc")
                .put("when", "2026-11-03T00:00:00Z");
        return doc;
    }
    @Test void structuredNoRecordsMeansOnlyACompletedExactGeneEnumerationAndRetains404Original() throws Exception {
        byte[] raw = mapper.writeValueAsBytes(emptyGene()); var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, true, (uri, timeout, max) -> {
            calls.incrementAndGet(); return new ProteinRegionHttpTransport.Response(404, Map.of(), raw);
        });
        var result = source.genePages("GENE1");
        assertEquals(2, calls.get()); assertTrue(result.receipt().path("sourceEnumerationComplete").asBoolean());
        assertEquals(0, result.document().path("data").size());
        String sha = CodingTranscriptMaterialSupply.sha(raw);
        var original = source.originals().path(sha);
        assertEquals(new String(raw, StandardCharsets.UTF_8), original.path("rawJson").asText());
        assertEquals(404, mapper.readTree(original.path("rawJson").asText()).at("/status/code").asInt());
        assertEquals(2, original.path("captures").size());
        assertEquals(404, original.at("/receipt/httpStatus").asInt());
        assertFalse(result.receipt().path("negativeCoverageGranted").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings = {"message", "service", "status", "extra", "time", "data", "duplicate", "trailing", "secondFailure"})
    void arbitraryOrMalformed404NeverBecomesCompleteAbsence(String mode) throws Exception {
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, true, (uri, timeout, max) -> {
            int number = calls.incrementAndGet(); var doc = emptyGene();
            if (mode.equals("message")) doc.withObject("status").put("msg", "Resource not found");
            if (mode.equals("service")) doc.withObject("metadata").withObject("rendered").put("by", "https://unrelated.invalid/service");
            if (mode.equals("status")) doc.withObject("status").put("code", "404");
            if (mode.equals("extra")) doc.put("partial", true);
            if (mode.equals("time")) doc.withObject("metadata").withObject("rendered").put("when", "unknown");
            if (mode.equals("data")) doc.putArray("data").addObject().put("uuid", "hidden-record");
            byte[] raw = mapper.writeValueAsBytes(doc);
            if (mode.equals("duplicate")) raw = new String(raw, StandardCharsets.UTF_8).replace("\"code\":404", "\"code\":200,\"code\":404").getBytes(StandardCharsets.UTF_8);
            if (mode.equals("trailing")) raw = (new String(raw, StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8);
            return new ProteinRegionHttpTransport.Response(mode.equals("secondFailure") && number == 2 ? 503 : 404, Map.of(), raw);
        });
        assertFalse(source.genePages("GENE1").receipt().path("sourceEnumerationComplete").asBoolean());
    }
    @Test void semanticEmptyIsNotAppliedToInterpretationOrUnpagedGenericRequest() throws Exception {
        byte[] raw = mapper.writeValueAsBytes(emptyGene());
        var source = new Pvs1StartLossPublicSources(mapper, true,
                (uri, timeout, max) -> new ProteinRegionHttpTransport.Response(404, Map.of(), raw));
        assertEquals("UNAVAILABLE", source.interpretation("d663419a-413e-4b9a-9410-9355060d6e3f").receipt().path("status").asText());
        assertEquals("UNAVAILABLE", source.gene("GENE1").receipt().path("status").asText());
    }
    @Test void pagingTraversesTwiceRetainsOriginalsAndDoesNotConfuseSourceCoverageWithEvidence() {
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, true, (u, t, max) -> {
            calls.incrementAndGet(); var doc = mapper.createObjectNode(); doc.putObject("status").put("code", 200);
            var rows = doc.putArray("data");
            int page = Integer.parseInt(u.getQuery().substring(u.getQuery().lastIndexOf('=') + 1));
            int size = page == 1 ? 100 : 2;
            for (int i = 0; i < size; i++) rows.addObject().put("uuid", "id-" + page + "-" + i).put("gene", "GENE1");
            return new ProteinRegionHttpTransport.Response(200, Map.of(), mapper.writeValueAsBytes(doc));
        });
        var first = source.genePages("GENE1");
        assertTrue(first.receipt().path("sourceEnumerationComplete").asBoolean());
        assertEquals(102, first.document().path("data").size()); assertEquals(4, calls.get());
        assertEquals(first, source.genePages("GENE1")); assertEquals(4, calls.get());
        assertEquals(3, source.originals().size());
        assertEquals("DERIVED_PAGINATED_EREPO_GENE_SNAPSHOT", first.receipt().path("kind").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"duplicate", "changed", "failed"})
    void unstableOrMissingPagesNeverEstablishSourceCoverage(String mode) {
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, true, (u, t, max) -> {
            int call = calls.incrementAndGet();
            if (mode.equals("failed") && call > 1) throw new java.io.IOException();
            var doc = mapper.createObjectNode(); doc.putObject("status").put("code", 200);
            var rows = doc.putArray("data"); int count = mode.equals("duplicate") ? 100 : 1;
            for (int i = 0; i < count; i++) rows.addObject().put("uuid", "id-" + (mode.equals("changed") ? call : i)).put("gene", "GENE1");
            return new ProteinRegionHttpTransport.Response(200, Map.of(), mapper.writeValueAsBytes(doc));
        });
        assertFalse(source.genePages("GENE1").receipt().path("sourceEnumerationComplete").asBoolean());
        assertEquals(2, calls.get());
    }
    @Test void networkDisabledAndInvalidIdentifiersNeverCallTransport() {
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, false, (u, t, m) -> { calls.incrementAndGet(); throw new AssertionError(); });
        assertEquals("PVS1_START_LOSS_AUTO_PUBLIC_NETWORK_DISABLED", source.gene("GENE1").receipt().path("reasonCode").asText());
        assertEquals("UNAVAILABLE", source.gene("x&patient=data").receipt().path("status").asText());
        assertEquals("UNAVAILABLE", source.interpretation("../../private").receipt().path("status").asText());
        assertEquals("PVS1_START_LOSS_AUTO_PUBLIC_NETWORK_DISABLED", source.refseqReport("GENE1", false).receipt().path("reasonCode").asText());
        assertEquals("UNAVAILABLE", source.refseqReport("../private", true).receipt().path("status").asText());
        assertEquals(0, calls.get()); assertTrue(source.originals().isEmpty());
    }
    @Test void refseqUsesTheSameBatchBudgetCacheAndOriginalArchive() {
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, true, (u, t, maximum) -> {
            calls.incrementAndGet(); assertEquals("api.ncbi.nlm.nih.gov", u.getHost());
            assertTrue(u.getPath().endsWith("/dataset_report") || u.getPath().endsWith("/product_report"));
            assertEquals("page_size=1000", u.getQuery());
            return new ProteinRegionHttpTransport.Response(200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        });
        source.refseqReport("GENE1", false); source.refseqReport("GENE1", true);
        source.refseqReport("GENE1", false); source.refseqReport("GENE1", true);
        assertEquals(2, calls.get()); assertEquals(1, source.originals().size());
        for (int i = 0; i < 30; i++) source.refseqReport("GENE" + (i + 2), false);
        assertEquals("PVS1_START_LOSS_AUTO_ACQUISITION_BUDGET_EXHAUSTED", source.gene("GENE1").receipt().path("reasonCode").asText());
        assertEquals(32, calls.get());
    }
    @Test void clinvarXmlUsesBoundedAcquisitionAndPreservesExactOriginalBytes() {
        var calls = new AtomicInteger(); String xml = "<?xml version=\"1.0\"?><ClinVarResult-Set>原始资料</ClinVarResult-Set>\r\n";
        var source = new Pvs1StartLossPublicSources(mapper, true, (u, t, maximum) -> {
            calls.incrementAndGet();
            assertEquals("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&rettype=vcv&id=VCV000185200", u.toString());
            return new ProteinRegionHttpTransport.Response(200, Map.of(), xml.getBytes(StandardCharsets.UTF_8));
        });
        var result = source.clinvarRecord("185200");
        assertEquals("XML", result.receipt().path("format").asText());
        assertEquals(xml, result.document().path("xml").asText());
        String sha = CodingTranscriptMaterialSupply.sha(xml.getBytes(StandardCharsets.UTF_8));
        assertEquals(xml, source.originals().path(sha).path("rawXml").asText());
        assertEquals(result, source.clinvarRecord("185200")); assertEquals(1, calls.get());
        assertEquals("UNAVAILABLE", source.clinvarRecord("185200&other=1").receipt().path("status").asText());
        assertEquals(1, calls.get());
        var disabled = new Pvs1StartLossPublicSources(mapper, false, (u, t, m) -> { throw new AssertionError(); });
        assertEquals("PVS1_START_LOSS_AUTO_PUBLIC_NETWORK_DISABLED", disabled.clinvarRecord("185200").receipt().path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"duplicate", "trailing", "utf8", "large", "http", "empty", "throw"})
    void failedOrMalformedSourcesAreCachedWithinTheBatchWithoutInventingEmptySuccess(String mode) {
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, true, (u, t, maximum) -> {
            calls.incrementAndGet();
            if (mode.equals("throw")) throw new java.io.IOException("private details must not leak");
            byte[] raw = switch (mode) {
                case "duplicate" -> "{\"data\":[],\"data\":[1]}".getBytes(StandardCharsets.UTF_8);
                case "trailing" -> "{} {}".getBytes(StandardCharsets.UTF_8);
                case "utf8" -> new byte[]{(byte) 0xff};
                case "large" -> new byte[maximum + 1];
                case "empty" -> new byte[0];
                default -> "{}".getBytes(StandardCharsets.UTF_8);
            };
            return new ProteinRegionHttpTransport.Response(mode.equals("http") ? 429 : 200, Map.of(), raw);
        });
        var first = source.gene("GENE1"); assertEquals("UNAVAILABLE", first.receipt().path("status").asText());
        assertTrue(first.document().isMissingNode()); assertEquals(first, source.gene("GENE1"));
        assertEquals(1, calls.get()); assertTrue(source.originals().isEmpty());
        assertFalse(first.receipt().toString().contains("private details"));
    }
    @Test void boundedCallsOriginalByteDigestsAndImmutableResponses() {
        var calls = new AtomicInteger(); byte[] raw = "{\"data\":[],\"label\":\"原件\"}".getBytes(StandardCharsets.UTF_8);
        var source = new Pvs1StartLossPublicSources(mapper, true, (u, t, maximum) -> {
            calls.incrementAndGet(); assertTrue(t.toSeconds() <= 12);
            return new ProteinRegionHttpTransport.Response(200, Map.of(), raw);
        });
        var first = source.gene("GENE1"); assertEquals(CodingTranscriptMaterialSupply.sha(raw), first.receipt().path("sha256").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) first.document()).removeAll(); first.receipt().removeAll(); source.originals().removeAll();
        assertTrue(source.gene("GENE1").document().has("data"));
        for (int i = 2; i <= 33; i++) source.gene("GENE" + i);
        assertEquals(32, calls.get());
        assertEquals("PVS1_START_LOSS_AUTO_ACQUISITION_BUDGET_EXHAUSTED", source.gene("GENE34").receipt().path("reasonCode").asText());
        assertEquals(1, source.originals().size(), "content-addressed originals are deduplicated");
    }
    @Test void interruptionIsPreservedAndNoSubsequentFetchStarts() {
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, true, (u, t, m) -> { calls.incrementAndGet(); throw new InterruptedException(); });
        try {
            assertEquals("PVS1_START_LOSS_AUTO_INTERRUPTED", source.gene("GENE1").receipt().path("reasonCode").asText());
            assertTrue(Thread.currentThread().isInterrupted()); source.gene("GENE2"); assertEquals(1, calls.get());
        } finally { Thread.interrupted(); }
    }
    @Test void failedPartialBodiesAreChargedAgainstTheTotalByteLimit() {
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(mapper, true, (u, t, max) -> {
            calls.incrementAndGet(); throw new java.io.IOException("partial body failure");
        });
        for (int i = 0; i < 20; i++) source.gene("GENE" + i);
        assertEquals(8, calls.get()); assertEquals(32_000_000, source.counts().path("bytes").asInt());
    }
}
