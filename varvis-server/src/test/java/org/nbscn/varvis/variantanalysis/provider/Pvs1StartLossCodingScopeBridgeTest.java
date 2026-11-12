package org.nbscn.varvis.variantanalysis.provider;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic molecular counterexamples; native sources are tested separately. */
class Pvs1StartLossCodingScopeBridgeTest {
    final Pvs1StartLossRuntimeTest helper = new Pvs1StartLossRuntimeTest();
    String record(String transcript, String tail) {
        String rna = "ATGAAACCCATGAAATAA" + tail;
        return "LOCUS       SYNTHETIC\nVERSION     " + transcript + "\n  ORGANISM  Homo sapiens\nCOMMENT     REVIEWED REFSEQ\n"
                + "FEATURES             Location/Qualifiers\n     source          1.." + rna.length() + "\n                     /db_xref=\"taxon:9606\"\n"
                + "     gene            1.." + rna.length() + "\n                     /gene=\"SYN_GENE_1\"\n                     /db_xref=\"HGNC:HGNC:1\"\n"
                + "     exon            1..9\n     CDS             1..18\n                     /gene=\"SYN_GENE_1\"\n                     /codon_start=1\n"
                + "                     /protein_id=\"NP_000001.1\"\n                     /db_xref=\"CCDS:CCDS123.1\"\n                     /translation=\"MKPMK\"\n"
                + "     exon            10.." + rna.length() + "\nORIGIN\n        1 " + rna.toLowerCase(java.util.Locale.ROOT) + "\n//\n";
    }
    Pvs1StartLossPublicSources sources(String selected, String declared) {
        return new Pvs1StartLossPublicSources(helper.mapper, true, (uri, timeout, limit) -> {
            assertEquals("eutils.ncbi.nlm.nih.gov", uri.getHost());
            assertTrue(uri.getQuery().contains("rettype=gb"));
            return new ProteinRegionHttpTransport.Response(200, Map.of(),
                    (uri.getQuery().contains("id=NM_000001.2&") ? declared : selected).getBytes(StandardCharsets.UTF_8));
        });
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void codingScopeCanMatchDespiteDifferentUtrsWithoutClaimingFullRnaEquality(int strand) throws Exception {
        var f = helper.fixture(strand); var data = f.data();
        // Both independent strand contexts use the same source record gene.
        data.model.put("transcript", "NM_000001.1").put("protein", "NP_000001.1").put("geneSymbol", "SYN_GENE_1").put("hgncId", "HGNC:1");
        data.context.withObject("transcript").put("accession", "NM_000001").put("version", 1);
        data.context.withObject("gene").put("symbol", "SYN_GENE_1").put("hgncId", "HGNC:1");
        var result = Pvs1StartLossCodingScopeBridge.verify(helper.mapper, data.context, data.model, "NM_000001.2",
                sources(record("NM_000001.1", "AAAAAA"), record("NM_000001.2", "AAA")));
        assertEquals("CODING_START_SCOPE_VERIFIED", result.audit().path("status").asText());
        assertFalse(result.audit().path("fullRnaEqual").asBoolean());
        assertFalse(result.audit().path("fullTranscriptEquivalenceGranted").asBoolean());
        assertEquals(2, result.sourceContext().at("/transcript/version").asInt());
        assertEquals(1, data.context.at("/transcript/version").asInt());
        result.audit().removeAll(); assertFalse(result.audit().isEmpty());
    }
    @Test void identityCodingStructureAndPartialOrExceptionalTranslationsCannotBridge() throws Exception {
        var f = helper.fixture(1); String current = record("NM_000001.1", "AAAAAA"), original = record("NM_000001.2", "AAA");
        for (var mutation : java.util.List.<java.util.function.UnaryOperator<String>>of(
                s -> s.replace("CCDS123.1", "CCDS123.2"), s -> s.replace("NP_000001.1", "NP_000001.2"),
                s -> s.replace("HGNC:HGNC:1", "HGNC:HGNC:2"), s -> s.replace("SYN_GENE_1", "OTHER_GENE"),
                s -> s.replace("1..18", "<1..18"), s -> s.replace("/codon_start=1", "/codon_start=2"),
                s -> s.replace("/codon_start=1", "/codon_start=1\n                     /exception=\"RNA editing\""),
                s -> s.replace("1..9\n", "1..8\n").replace("10..21", "9..21"),
                s -> s.replace("atgaaa", "atggag"), s -> s.replace("/translation=\"MKPMK\"", "/translation=\"MEPMK\"")))
            assertThrows(IllegalArgumentException.class, () -> Pvs1StartLossCodingScopeBridge.verify(helper.mapper,
                    f.data().context, f.data().model, "NM_000001.2", sources(current, mutation.apply(original))));
    }
    @Test void wrongReferenceAlleleOrNonStartEditIsRejectedAndNoOtherAccessionIsAliased() throws Exception {
        var f = helper.fixture(1); var source = sources(record("NM_000001.1", "AAAAAA"), record("NM_000001.2", "AAA"));
        var context = f.data().context.deepCopy();
        // Set through the actual nested node to retain the rest of the context.
        ((com.fasterxml.jackson.databind.node.ObjectNode) context.at("/variant/evidenceQueryAllele")).put("reference", "C");
        assertThrows(IllegalArgumentException.class, () -> Pvs1StartLossCodingScopeBridge.verify(helper.mapper, context, f.data().model, "NM_000001.2", source));
        assertThrows(IllegalArgumentException.class, () -> Pvs1StartLossCodingScopeBridge.verify(helper.mapper, f.data().context, f.data().model, "NM_000002.2", source));
    }
}
