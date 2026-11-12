package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossClinVarSearchTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode model(int strand) {
        var m = mapper.createObjectNode().put("contig", "1").put("strand", strand).put("cdsLength", 18);
        var exons = m.putArray("exons");
        exons.addObject().put("cdsStart", 1).put("cdsEnd", 6).put("genomicStart", strand == 1 ? 100 : 300).put("genomicEnd", strand == 1 ? 105 : 305);
        exons.addObject().put("cdsStart", 7).put("cdsEnd", 18).put("genomicStart", strand == 1 ? 300 : 100).put("genomicEnd", strand == 1 ? 311 : 111);
        return m;
    }
    ObjectNode context() { var c = mapper.createObjectNode(); c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1"); return c; }
    VariantAnalysisProperties properties() throws Exception {
        Path file = root.resolve("clinvar.vcf.gz"); Files.writeString(file, "fixture"); Files.writeString(Path.of(file + ".tbi"), "fixture index");
        var p = new VariantAnalysisProperties(); p.getClinVar().setEnabled(true); p.getClinVar().setGrch38File(file.toString());
        p.getClinVar().setVersion("SYNTHETIC_ONLY"); return p;
    }
    @Test void mapsCodingDirectionAndExcludesIntronicBoundingSpan() {
        assertEquals(List.of(new Pvs1StartLossClinVarSearch.Interval(100, 105), new Pvs1StartLossClinVarSearch.Interval(300, 302)), Pvs1StartLossClinVarSearch.intervals(model(1), 10));
        assertEquals(List.of(new Pvs1StartLossClinVarSearch.Interval(300, 305), new Pvs1StartLossClinVarSearch.Interval(109, 111)), Pvs1StartLossClinVarSearch.intervals(model(-1), 10));
        var broken = model(1); ((ObjectNode) broken.path("exons").get(1)).put("cdsStart", 8);
        assertThrows(IllegalArgumentException.class, () -> Pvs1StartLossClinVarSearch.intervals(broken, 10));
    }
    @Test void retainsIndelSymbolicAndOverlappingRecordsWithoutGrantingPathogenicity() throws Exception {
        var provider = new Pvs1StartLossClinVarSearch(mapper, properties(), (file, regions) -> {
            assertEquals(List.of("1:100-105", "1:300-302"), regions);
            return List.of("1\t100\t1\tA\tG\t.\t.\tCLNSIG=Pathogenic", "1\t101\t2\tAT\tA\t.\t.\tCLNSIG=Pathogenic",
                    "1\t99\t3\tAAA\tA\t.\t.\tCLNSIG=Pathogenic", "1\t103\t4\tA\t<DEL>\t.\t.\tEND=106",
                    "1\t100\t1\tA\tG\t.\t.\tCLNSIG=Pathogenic", "1\t200\t5\tA\tG\t.\t.\t.");
        });
        var result = provider.query(context(), model(1), 10);
        assertEquals("ENUMERATED", result.path("status").asText(), result.toPrettyString());
        assertTrue(result.path("complete").asBoolean()); assertFalse(result.path("negativeCoverageGranted").asBoolean());
        assertEquals(4, result.path("records").size());
        for (var row : result.path("records")) assertEquals("REQUIRES_REFERENCE_QUALIFICATION", row.path("disposition").asText());
    }
    @Test void emptySourceIsNotGlobalAbsenceAndFailedOrChangedSourceIsNotComplete() throws Exception {
        var p = properties();
        var empty = new Pvs1StartLossClinVarSearch(mapper, p, (f, r) -> List.of()).query(context(), model(1), 10);
        assertTrue(empty.path("complete").asBoolean()); assertFalse(empty.path("negativeCoverageGranted").asBoolean());
        var changed = new Pvs1StartLossClinVarSearch(mapper, p, (f, r) -> { Files.writeString(f, "changed snapshot"); return List.of(); }).query(context(), model(1), 10);
        assertFalse(changed.path("complete").asBoolean()); assertEquals("CLINVAR_UPSTREAM_RESOURCE_CHANGED", changed.path("reasonCode").asText());
        var failed = new Pvs1StartLossClinVarSearch(mapper, p, (f, r) -> { throw new java.io.IOException(); }).query(context(), model(1), 10);
        assertFalse(failed.path("complete").asBoolean()); assertEquals("CLINVAR_UPSTREAM_QUERY_FAILED", failed.path("reasonCode").asText());
    }
}
