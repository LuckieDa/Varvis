package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import static org.junit.jupiter.api.Assertions.*;

/** Native sequence/geometry + local ClinVar + two actual ERepo empty captures.
 * Scoped reference-search absence only: no TWIST1 LoF mechanism or target PVS1
 * strength is granted by this test. */
@EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
class Pvs1StartLossNativeNegativeTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    private byte[] source(Path folder, String name, String sha) throws Exception {
        byte[] raw = Files.readAllBytes(folder.resolve(name)); assertEquals(sha, CodingTranscriptMaterialSupply.sha(raw)); return raw;
    }
    private Pvs1StartLossPublicSources.Snapshot jsonSource(byte[] bytes) throws Exception {
        return new Pvs1StartLossPublicSources.Snapshot(mapper.createObjectNode().put("status", "AVAILABLE")
                .put("sha256", CodingTranscriptMaterialSupply.sha(bytes)), mapper.readTree(bytes));
    }
    @Test void exactNativeThreeBaseScopeHasNoQualifyingReferenceAfterBothRequiredSourcesCompleteAndSurvivesHistory() throws Exception {
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        Path folder = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/goal-20261103");
        byte[] gene = source(folder, "TWIST1-gene7291-current.json", "a1ca24ca0d7f7dcaf0b8f747ea341d8739f4fa962c7cfd5c60ff8c2445c7f9df");
        byte[] products = source(folder, "TWIST1-products7291-current.json", "8ad8c2d42ad600aa1ecdb415e07949847efccf3a1e93278b129255e6afd1ff37");
        byte[] gb = source(folder, "TWIST1-NM_000474.4.gb", "b6c131fd3dd6f1fcb49230b6d3b3ba7af6065e761b690bb009fa8393f33aab51");
        byte[] empty1 = source(folder, "TWIST1-erepo-page1-pass1.json", "e4140e96ee9a77d7bca40955bfe10c5e17dc89b00475e7b4fbb64fa5fd7e1c7e");
        byte[] empty2 = source(folder, "TWIST1-erepo-page1-pass2.json", "3bea63b5f5c788047000961a23886dafebfce5ceb00fadc4b1119b89a466ad93");
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        context.putObject("gene").put("hgncId", "HGNC:12428").put("symbol", "TWIST1");
        context.putObject("disease").put("mondoId", "MONDO:0007042");
        context.putObject("transcript").put("accession", "NM_000474").put("version", 4);
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "7");
        var inventory = Pvs1StartLossRefSeqInventory.read(mapper, context, jsonSource(gene), jsonSource(products));
        assertTrue(inventory.path("sourceCoverageComplete").asBoolean(), inventory::toPrettyString);
        ObjectNode model = null;
        for (var row : inventory.path("transcripts")) if ("NM_000474.4".equals(row.path("transcript").asText())) model = (ObjectNode) row.deepCopy();
        model = java.util.Objects.requireNonNull(model);
        String rawGenBank = new String(gb, StandardCharsets.UTF_8);
        assertTrue(rawGenBank.contains("VERSION     NM_000474.4"));
        int origin = rawGenBank.indexOf("\nORIGIN"), end = rawGenBank.indexOf("\n//", origin);
        assertTrue(origin >= 0 && end > origin);
        String rna = rawGenBank.substring(origin + "\nORIGIN".length(), end).replaceAll("[\\s0-9]", "").toUpperCase(java.util.Locale.ROOT);
        assertTrue(rna.matches("[ACGT]+")); assertEquals(model.path("transcriptLength").asInt(), rna.length());
        String cds = rna.substring(model.path("transcriptCdsStart").asInt() - 1, model.path("transcriptCdsEnd").asInt());
        model.put("cdsSequence", cds); assertEquals(609, cds.length()); assertTrue(cds.startsWith("ATGATG"));
        int restart = 0;
        for (int i = 3; i + 3 <= cds.length(); i += 3) if (cds.substring(i, i + 3).equals("ATG")) { restart = i + 1; break; }
        assertEquals(4, restart);
        assertEquals(java.util.List.of(new Pvs1StartLossClinVarSearch.Interval(19117319, 19117321)), Pvs1StartLossClinVarSearch.intervals(model, restart));
        var calls = new AtomicInteger();
        var sources = new Pvs1StartLossPublicSources(mapper, true, (uri, timeout, maximum) -> {
            assertEquals("erepo.genome.network", uri.getHost()); assertTrue(uri.getQuery().contains("values=TWIST1"));
            return new ProteinRegionHttpTransport.Response(404, Map.of(), calls.incrementAndGet() == 1 ? empty1 : empty2);
        });
        var properties = new VariantAnalysisProperties(); properties.getClinVar().setEnabled(true);
        Path vcf = root.resolve("local/resources/clinvar/clinvar.vcf.gz"); properties.getClinVar().setGrch38File(vcf.toString());
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(Files.newInputStream(vcf)), StandardCharsets.UTF_8))) {
            assertTrue(reader.lines().limit(20).anyMatch(line -> line.equals("##fileDate=2026-03-21")));
        }
        properties.getClinVar().setVersion("2026-03-21");
        var scan = new Pvs1StartLossClinVarSearch(mapper, properties).query(context, model, restart);
        assertEquals("ENUMERATED", scan.path("status").asText(), scan::toPrettyString); assertTrue(scan.path("records").isEmpty(), scan::toPrettyString);
        String scanRaw = mapper.writeValueAsString(scan); scan.put("sourceSha256", CodingTranscriptMaterialSupply.sha(scanRaw.getBytes(StandardCharsets.UTF_8)));
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> null, sources, () -> null);
        var sequence = mapper.createObjectNode().put("candidateRestartCdsPosition", restart);
        var result = preparation.upstreamWithCoverage(context, model, sequence, scan);
        assertEquals("NO_QUALIFYING_REFERENCE_WITHIN_SCOPE", result.path("status").asText(), result::toPrettyString);
        assertTrue(result.path("negativeCoverageGranted").asBoolean()); assertTrue(result.at("/coverage/complete").asBoolean());
        assertFalse(result.at("/coverage/globalAbsenceClaimed").asBoolean()); assertEquals(2, calls.get());
        var originalArchive = sources.originals();
        for (byte[] raw : new byte[][]{gene, products, gb, scanRaw.getBytes(StandardCharsets.UTF_8)})
            originalArchive.putObject(CodingTranscriptMaterialSupply.sha(raw)).put("rawText", new String(raw, StandardCharsets.UTF_8));
        var archive = mapper.createObjectNode(); archive.set("upstreamReview", result); archive.set("originals", originalArchive);
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(result), mapper.writeValueAsString(archive));
    }
}
