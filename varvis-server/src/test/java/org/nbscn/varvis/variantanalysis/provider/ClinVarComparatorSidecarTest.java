package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClinVarComparatorSidecarTest {

    @TempDir
    Path tempDirectory;

    @Test
    void readsAnActualBgzfTabixFixtureWithExactAndFailureSemantics() throws Exception {
        Path sidecarPath = buildFixture();
        ObjectMapper mapper = new ObjectMapper();
        ClinVarComparatorSidecar sidecar = new ClinVarComparatorSidecar(
                mapper,
                sidecarPath,
                "CLINVAR_COMPARATOR_TEST",
                "ClinVar synthetic test release",
                "0123456789abcdef");

        ClinVarComparatorSidecar.LookupResult hit = sidecar.lookup(key(100, "A", "T"), "13961");
        assertEquals(ClinVarComparatorSidecar.Status.HIT, hit.getStatus());
        assertNull(hit.getReasonCode());
        assertEquals("13961", hit.getRecord().getVariationId());
        assertEquals(List.of("BRAF"), hit.getRecord().getGeneSymbols());
        assertEquals("CONFLICTING", hit.getRecord().getConflictState());
        assertEquals(1, hit.getRecord().getVariantSummaryRecords().size());
        assertEquals(2, hit.getRecord().getSubmissions().size());
        assertEquals("RCV0001.1", hit.getRecord().getVariantSummaryRecords()
                .get(0).path("rcvAccessions").get(0).asText());
        assertEquals("SCV0001.1", hit.getRecord().getSubmissions().get(0).path("scv").asText());
        assertTrue(hit.getRecord().getVariantSummaryRecords()
                .get(0).path("dateLastEvaluated").isNull());
        assertEquals("CLINVAR_COMPARATOR_TEST", hit.getProvenance().getResourceId());
        assertEquals(sidecarPath.getFileName().toString(), hit.getProvenance().getLogicalName());
        assertEquals("7:100-100;variationId=13961", hit.getProvenance().getRecordLocator());
        String serialized = mapper.writeValueAsString(hit);
        assertFalse(serialized.contains(tempDirectory.toAbsolutePath().toString()));

        ClinVarComparatorSidecar.LookupResult absent =
                sidecar.lookup(key(100, "A", "T"), "99999");
        assertEquals(ClinVarComparatorSidecar.Status.NO_RECORD, absent.getStatus());
        assertEquals("COMPARATOR_RECORD_NOT_FOUND", absent.getReasonCode());

        ClinVarComparatorSidecar.LookupResult ambiguous =
                sidecar.lookup(key(101, "A", "C"), "22");
        assertEquals(ClinVarComparatorSidecar.Status.NOT_EVALUATED, ambiguous.getStatus());
        assertEquals("COMPARATOR_RECORD_AMBIGUOUS", ambiguous.getReasonCode());

        ClinVarComparatorSidecar.LookupResult malformed =
                sidecar.lookup(key(102, "G", "A"), "33");
        assertEquals(ClinVarComparatorSidecar.Status.FAILED, malformed.getStatus());
        assertEquals("COMPARATOR_SIDECAR_PARSE_FAILED", malformed.getReasonCode());

        var batch = sidecar.lookupBatch(List.of(
                new ClinVarComparatorSidecar.Query(key(102, "G", "A"), "33"),
                new ClinVarComparatorSidecar.Query(key(100, "A", "T"), "13961"),
                new ClinVarComparatorSidecar.Query(key(100, "A", "C"), "13961"),
                new ClinVarComparatorSidecar.Query(key(101, "A", "C"), "22")), executable("tabix").toString());
        assertEquals(List.of(ClinVarComparatorSidecar.Status.FAILED, ClinVarComparatorSidecar.Status.HIT,
                ClinVarComparatorSidecar.Status.NO_RECORD, ClinVarComparatorSidecar.Status.NOT_EVALUATED),
                batch.stream().map(ClinVarComparatorSidecar.LookupResult::getStatus).toList());
        assertEquals(hit.getRecord().getSubmissions(), batch.get(1).getRecord().getSubmissions());
    }

    private Path buildFixture() throws Exception {
        Path plain = tempDirectory.resolve("clinvar-comparator.tsv");
        String header = "#index_contig\tindex_start\tindex_end\tvariation_id\tassembly\t"
                + "chromosome\tposition\treference_allele\talternate_allele\t"
                + "gene_symbols_json\tconflict_state\t"
                + "variant_summary_records_json\tsubmissions_json\n";
        String variantRecords = "[{\"aggregateClassification\":\"Conflicting_classifications_of_pathogenicity\","
                + "\"condition\":{\"identifiersRaw\":\"MONDO:1|MONDO:2\","
                + "\"namesRaw\":\"Condition_one|Condition_two\"},"
                + "\"dateLastEvaluated\":null,\"rcvAccessions\":[\"RCV0001.1\"],"
                + "\"reviewStatus\":\"criteria_provided,_conflicting_classifications\"}]";
        String submissions = "[{\"classification\":\"Pathogenic\",\"scv\":\"SCV0001.1\"},"
                + "{\"classification\":\"Likely_benign\",\"scv\":null}]";
        String row100 = "VID\t13961\t13961\t13961\tGRCh38\t7\t100\tA\tT\t[\"BRAF\"]\tCONFLICTING\t"
                + variantRecords + "\t" + submissions + "\n";
        String duplicate = "VID\t22\t22\t22\tGRCh38\t7\t101\tA\tC\t[\"BRAF\"]\tNONE\t[]\t[]\n";
        String malformed = "VID\t33\t33\t33\tGRCh38\t7\t102\tG\tA\t{\tNONE\t[]\t[]\n";
        Files.write(plain, (header + duplicate + duplicate + malformed + row100)
                .getBytes(StandardCharsets.UTF_8));

        Path bgzip = executable("bgzip");
        Path tabix = executable("tabix");
        Path compressed = tempDirectory.resolve("clinvar-comparator.tsv.gz");
        run(List.of(bgzip.toString(), "-c", plain.toString()), compressed);
        run(List.of(tabix.toString(), "-f", "-s", "1", "-b", "2", "-e", "3",
                "-S", "1", compressed.toString()), null);
        assertTrue(Files.isRegularFile(compressed));
        assertTrue(Files.isRegularFile(compressed.resolveSibling(compressed.getFileName() + ".tbi")));
        return compressed;
    }

    private void run(List<String> command, Path standardOutput) throws Exception {
        Path stderr = tempDirectory.resolve("tool-" + Math.abs(command.hashCode()) + ".stderr");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(stderr.toFile());
        if (standardOutput != null) {
            builder.redirectOutput(standardOutput.toFile());
        }
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(command.get(0) + " failed: "
                    + Files.readString(stderr, StandardCharsets.UTF_8));
        }
    }

    private Path executable(String name) {
        String suffix = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
        String override = System.getProperty("varvis.test." + name);
        if (override != null && Files.isRegularFile(Paths.get(override))) {
            return Paths.get(override);
        }
        String resourceRoot = System.getenv("VARVIS_RESOURCE_ROOT");
        if (resourceRoot != null) {
            Path configured = Paths.get(resourceRoot, "Tool", "msys64", "ucrt64", "bin", name + suffix);
            if (Files.isRegularFile(configured)) return configured;
        }
        Path projectRelative = Paths.get("..", "..", "Tool", "msys64", "ucrt64", "bin", name + suffix)
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(projectRelative)) return projectRelative;
        throw new IllegalStateException("Set -Dvarvis.test." + name + " to the executable path");
    }

    private NormalizedVariantKey key(int position, String reference, String alternate) {
        return new NormalizedVariantKey("GRCh38", "7", position, reference, alternate);
    }
}
