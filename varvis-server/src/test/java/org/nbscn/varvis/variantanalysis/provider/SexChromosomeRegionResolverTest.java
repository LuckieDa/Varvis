package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SexChromosomeRegionResolverTest {
    final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode allele(String assembly, String contig, int position, String ref, String alt) {
        return mapper.createObjectNode().put("assembly", assembly).put("contig", contig)
                .put("position", position).put("reference", ref).put("alternate", alt);
    }
    private ObjectNode result(String assembly, String contig, int p, String ref, String alt) {
        return SexChromosomeRegionResolver.assess(mapper, allele(assembly, contig, p, ref, alt));
    }

    // Independently transcribed from NCBI Region Reports; not generated from the resolver/catalog.
    @ParameterizedTest @CsvSource({"GRCh37,X,60001,2699520,PAR1", "GRCh37,X,154931044,155260560,PAR2",
            "GRCh37,Y,10001,2649520,PAR1", "GRCh37,Y,59034050,59363566,PAR2",
            "GRCh38,X,10001,2781479,PAR1", "GRCh38,X,155701383,156030895,PAR2",
            "GRCh38,Y,10001,2781479,PAR1", "GRCh38,Y,56887903,57217415,PAR2"})
    void allOfficialParEndsAndAdjacentNonParBases(String assembly, String contig, int start, int end, String id) {
        for (int p : new int[]{start, end}) {
            var r = result(assembly, contig, p, "A", "G");
            assertEquals("RESOLVED", r.path("status").asText()); assertEquals("PAR", r.path("region").asText());
            assertEquals(id, r.path("regionId").asText()); assertEquals(2, r.path("sourceBindings").size());
            assertFalse(r.path("patientPloidyInferred").asBoolean()); assertFalse(r.path("clinicalQualificationGranted").asBoolean());
        }
        for (int p : new int[]{start - 1, end + 1}) assertEquals("NON_PAR", result(assembly, contig, p, "A", "G").path("region").asText());
    }

    @ParameterizedTest @CsvSource({"10000,AC,A,PAR,10001,10001", "10001,AC,C,PAR,10001,10001",
            "10000,AC,GT,CROSSES_BOUNDARY,10000,10001", "2781479,AC,A,NON_PAR,2781480,2781480"})
    void deletedOrReplacedReferenceBasesExcludePadding(int pos, String ref, String alt, String expected, int lo, int hi) {
        var r = result("GRCh38", "X", pos, ref, alt);
        assertEquals(expected, r.path("region").asText());
        assertEquals(lo, r.at("/classificationSpan/start").asInt()); assertEquals(hi, r.at("/classificationSpan/end").asInt());
    }

    @ParameterizedTest @CsvSource({"9999,A,AT,NON_PAR,9999,10000", "10000,A,AT,CROSSES_BOUNDARY,10000,10001",
            "10001,A,AT,PAR,10001,10002", "2781479,A,AT,CROSSES_BOUNDARY,2781479,2781480",
            "10001,A,TA,CROSSES_BOUNDARY,10000,10001"})
    void pureInsertionsRetainTwoFlanks(int pos, String ref, String alt, String expected, int left, int right) {
        var r = result("GRCh38", "X", pos, ref, alt);
        assertEquals(expected, r.path("region").asText()); assertEquals("INSERTION", r.at("/event/type").asText());
        assertEquals(left, r.at("/event/insertionBreakpoint/leftFlank").asInt());
        assertEquals(right, r.at("/event/insertionBreakpoint/rightFlank").asInt());
        assertFalse(r.path("event").has("affectedReferenceInterval"));
    }

    @ParameterizedTest @ValueSource(strings = {"hg38", "GRCh39", "", "GRCh37.p13"})
    void assemblyIsNeverSilentlyConverted(String assembly) {
        assertEquals("SEX_REGION_ASSEMBLY_UNSUPPORTED", result(assembly, "X", 10001, "A", "G").path("reasonCode").asText());
    }

    @Test void invalidUnknownAndUnsupportedInputsDoNotBecomeNonPar() {
        assertEquals("SEX_REGION_CONTIG_UNSUPPORTED", result("GRCh38", "MT", 1, "A", "G").path("reasonCode").asText());
        for (String ref : java.util.List.of("N", "", "<DEL>", "a", "A".repeat(51)))
            assertEquals("UNKNOWN", result("GRCh38", "X", 10000, ref, "G").path("region").asText());
        for (int p : new int[]{0, -1, Integer.MAX_VALUE})
            assertEquals("UNRESOLVED", result("GRCh38", "X", p, "A", "G").path("status").asText());
        assertEquals("SEX_REGION_REFERENCE_SPAN_OUT_OF_RANGE", result("GRCh38", "X", 156040895, "AC", "A").path("reasonCode").asText());
        assertEquals("SEX_REGION_EVENT_FLANK_OUT_OF_RANGE", result("GRCh38", "X", 1, "A", "TA").path("reasonCode").asText());
        assertEquals("SEX_REGION_SMALL_ALLELE_UNSUPPORTED", result("GRCh38", "X", 10, "A", "A").path("reasonCode").asText());
        var fractional = allele("GRCh38", "X", 10, "A", "G").put("position", 10.5);
        assertEquals("UNKNOWN", SexChromosomeRegionResolver.assess(mapper, fractional).path("region").asText());
        assertEquals("UNKNOWN", SexChromosomeRegionResolver.assess(mapper, allele("GRCh38", "X", 10, "A", "G").put("end", 20)).path("region").asText());
    }

    @Test void receiptCanBeSavedAndRecomputedWithoutNumericNodeTypeMismatch() throws Exception {
        for (String contig : java.util.List.of("X", "Y")) {
            var input = allele("GRCh38", contig, 10001, "A", "G").put("position", 10001L);
            var original = input.deepCopy(); var r = SexChromosomeRegionResolver.assess(mapper, input);
            var restored = mapper.readTree(mapper.writeValueAsBytes(r));
            assertEquals(r, restored);
            assertEquals(restored, SexChromosomeRegionResolver.assess(mapper, restored.path("queryAllele")));
            assertEquals(original, input);
        }
    }

    @Test void inputIdentityIsImmutableAndAliasesDoNotMergeXY() {
        var a = allele("GRCh38", "chrX", 10001, "A", "G"); var before = a.deepCopy();
        var r = SexChromosomeRegionResolver.assess(mapper, a);
        assertEquals(before, a); assertEquals(before, r.path("queryAllele")); assertTrue(r.path("contigAliasUsed").asBoolean());
        assertNotEquals(r.path("queryAlleleSha256"), result("GRCh38", "Y", 10001, "A", "G").path("queryAlleleSha256"));
        assertEquals("AUTOSOMAL", result("GRCh38", "1", 10, "A", "G").path("region").asText());
    }
}
