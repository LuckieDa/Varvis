package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisVcfAlleleIndexTest {

    private static final String SAMPLE = "synthetic-proband";

    @TempDir
    Path tempDir;

    @Test
    void sameScanCapturesRawArtifactHashAndBoundedPrivateMultiAltSummary() throws Exception {
        Path plain = writeVcf("1\t100\tprivate-record-id\tA\tC,G\t.\tPASS\t.\tGT\t1/2");
        Path compressed = tempDir.resolve("input.vcf.gz");
        try (var gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(compressed))) {
            gzip.write(Files.readAllBytes(plain));
        }
        var target = key("1", 100, "A", "G");
        for (Path path : java.util.List.of(plain, compressed)) {
            var indexed = new AnalysisVcfAlleleIndex().indexWithSource(path, GenomeAssembly.GRCh38, SAMPLE, java.util.List.of(target));
            assertEquals(ParsedCandidateFixture.sha(Files.readAllBytes(path)), indexed.rawSha256());
            assertEquals(1, indexed.fullScans());
            var record = indexed.records().get(target);
            assertEquals(java.util.List.of("C", "G"), record.alternates());
            assertEquals("1|100|A|C,G|2", record.mappingToken());
            assertEquals(1, record.recordOrdinal());
            assertFalse(new ObjectMapper().valueToTree(indexed.calls().get(target)).has("matchedRecord"));
            assertFalse(new ObjectMapper().writeValueAsString(record).contains("private-record-id"));
        }
    }

    @Test
    void ambiguousAndMissingMatchesHaveNoInternalSuccessSummary() throws Exception {
        Path path = writeVcf("1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1", "1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1");
        var indexed = new AnalysisVcfAlleleIndex().indexWithSource(path, GenomeAssembly.GRCh38, SAMPLE,
                java.util.List.of(key("1", 100, "A", "G"), key("1", 200, "C", "T")));
        assertTrue(indexed.records().isEmpty());
        assertEquals(ParsedCandidateFixture.sha(Files.readAllBytes(path)), indexed.rawSha256());
        assertEquals(1, indexed.fullScans());
    }

    @Test
    void projectsTargetSpecificMultiAllelicCallAndAlleleBalance() throws Exception {
        Path vcf = writeVcf(
                "1\t100\t.\tA\tC,G\t.\tPASS\t.\tGT:DP:GQ:AD\t1/2:20:99:10,3,7"
        );
        NormalizedVariantKey target = key("1", 100, "A", "G");

        AnalysisVcfCall call = index(vcf, target).get(target);

        assertEquals(AnalysisVcfCall.MatchStatus.MATCHED, call.getMatchStatus());
        assertEquals(AnalysisVcfCall.TargetAlleleCall.TARGET_WITH_OTHER_ALT, call.getTargetAlleleCall());
        assertEquals("OTHER_ALT/TARGET_ALT", call.getCandidateRelativeGenotype());
        assertEquals(Integer.valueOf(2), call.getTargetAlleleIndex());
        assertEquals(Integer.valueOf(20), call.getDepth());
        assertEquals(Integer.valueOf(99), call.getGenotypeQuality());
        assertEquals(Integer.valueOf(10), call.getReferenceDepth());
        assertEquals(Integer.valueOf(7), call.getTargetAlleleDepth());
        assertEquals(7.0d / 17.0d, call.getAlleleBalance(), 1.0e-12d);
        assertEquals(AnalysisVcfCall.FilterEvaluation.PASS, call.getFilterEvaluation());
        assertEquals(AnalysisVcfCall.PhasingStatus.UNPHASED, call.getPhasingStatus());
        assertEquals(Integer.valueOf(1), call.getMatchingRecordCount());
        assertEquals(Integer.valueOf(1), call.getMatchedRecordOrdinal());
        assertEquals("GRCh38", call.getAssembly());
        assertEquals("EXOMISER_ANALYSIS_VCF", call.getArtifactRole());
        assertEquals("ANALYSIS_VCF_EXACT_ALLELE", call.getProvenance());
        assertTrue(call.getMissingFields().isEmpty());
        assertTrue(call.getInvalidFields().isEmpty());
    }

    @Test
    void targetAlleleAndMissingAlleleTakePartialPrecedenceOverAnotherAlternate() throws Exception {
        Path vcf = writeVcf(
                "1\t150\t.\tA\tC,G\t.\tPASS\t.\tGT:AD\t1/2/.:10,3,7"
        );
        NormalizedVariantKey target = key("1", 150, "A", "G");

        AnalysisVcfCall call = index(vcf, target).get(target);

        assertEquals(AnalysisVcfCall.MatchStatus.MATCHED, call.getMatchStatus());
        assertEquals(AnalysisVcfCall.TargetAlleleCall.PARTIAL_TARGET, call.getTargetAlleleCall());
        assertEquals("OTHER_ALT/TARGET_ALT/.", call.getCandidateRelativeGenotype());
        assertEquals(Integer.valueOf(2), call.getTargetAlleleIndex());
        assertEquals(Integer.valueOf(10), call.getReferenceDepth());
        assertEquals(Integer.valueOf(7), call.getTargetAlleleDepth());
        assertEquals(7.0d / 17.0d, call.getAlleleBalance(), 1.0e-12d);
        assertTrue(call.getMissingFields().contains("GT"));
        assertFalse(call.getInvalidFields().contains("GT"));
    }

    @Test
    void exactDuplicateBecomesAmbiguousAndDoesNotRetainAnArbitraryCall() throws Exception {
        Path vcf = writeVcf(
                "1\t200\tfirst\tA\tG\t.\tPASS\t.\tGT:DP:GQ:AD\t0/1:18:80:9,9",
                "1\t200\tsecond\tA\tG\t.\tq10\t.\tGT:DP:GQ:AD\t1/1:22:90:0,22"
        );
        NormalizedVariantKey target = key("1", 200, "A", "G");

        AnalysisVcfCall call = index(vcf, target).get(target);

        assertEquals(AnalysisVcfCall.MatchStatus.AMBIGUOUS, call.getMatchStatus());
        assertEquals(Integer.valueOf(2), call.getMatchingRecordCount());
        assertNull(call.getTargetAlleleCall());
        assertNull(call.getCandidateRelativeGenotype());
        assertNull(call.getMatchedRecordOrdinal());
        assertNull(call.getDepth());
        assertTrue(call.getMissingFields().isEmpty());
        assertTrue(call.getInvalidFields().isEmpty());
    }

    @Test
    void emitsExplicitNotFoundAndDeduplicatesCandidateKeys() throws Exception {
        Path vcf = writeVcf(
                "1\t300\t.\tA\tC\t.\tPASS\t.\tGT:DP:GQ:AD\t0/1:20:99:10,10"
        );
        NormalizedVariantKey absent = key("1", 301, "A", "G");

        Map<NormalizedVariantKey, AnalysisVcfCall> calls = new AnalysisVcfAlleleIndex().index(
                vcf,
                GenomeAssembly.GRCh38,
                SAMPLE,
                Arrays.asList(absent, absent)
        );

        assertEquals(1, calls.size());
        AnalysisVcfCall call = calls.get(absent);
        assertEquals(AnalysisVcfCall.MatchStatus.NOT_FOUND, call.getMatchStatus());
        assertEquals(Integer.valueOf(0), call.getMatchingRecordCount());
        assertNull(call.getTargetAlleleIndex());
        assertNull(call.getFilterEvaluation());
    }

    @Test
    void preservesNoCallWithoutInventingReferenceEvidence() throws Exception {
        Path vcf = writeVcf(
                "1\t310\t.\tA\tG\t.\tPASS\t.\tGT:DP:GQ:AD\t./.:.:.:."
        );
        NormalizedVariantKey target = key("1", 310, "A", "G");

        AnalysisVcfCall call = index(vcf, target).get(target);

        assertEquals(AnalysisVcfCall.TargetAlleleCall.NO_CALL, call.getTargetAlleleCall());
        assertEquals("./.", call.getCandidateRelativeGenotype());
        assertEquals(AnalysisVcfCall.PhasingStatus.UNKNOWN, call.getPhasingStatus());
        assertTrue(call.getMissingFields().contains("GT"));
        assertTrue(call.getMissingFields().contains("DP"));
        assertTrue(call.getMissingFields().contains("GQ"));
        assertTrue(call.getMissingFields().contains("AD"));
        assertNull(call.getAlleleBalance());
    }

    @Test
    void distinguishesUnassessedPassAndNonPassFilters() throws Exception {
        Path vcf = writeVcf(
                "1\t320\t.\tA\tG\t.\t.\t.\tGT\t0/1",
                "1\t321\t.\tA\tG\t.\tPASS\t.\tGT\t0/1",
                "1\t322\t.\tA\tG\t.\tq10\t.\tGT\t0/1"
        );
        NormalizedVariantKey unassessed = key("1", 320, "A", "G");
        NormalizedVariantKey pass = key("1", 321, "A", "G");
        NormalizedVariantKey filtered = key("1", 322, "A", "G");

        Map<NormalizedVariantKey, AnalysisVcfCall> calls = new AnalysisVcfAlleleIndex().index(
                vcf,
                GenomeAssembly.GRCh38,
                SAMPLE,
                Arrays.asList(unassessed, pass, filtered)
        );

        assertEquals(AnalysisVcfCall.FilterEvaluation.UNASSESSED,
                calls.get(unassessed).getFilterEvaluation());
        assertEquals(AnalysisVcfCall.FilterEvaluation.PASS,
                calls.get(pass).getFilterEvaluation());
        assertEquals(AnalysisVcfCall.FilterEvaluation.FILTERED,
                calls.get(filtered).getFilterEvaluation());
    }

    @Test
    void phaseSetTokenIsStableWithinScopeAndDoesNotExposeRawIdentifiers() throws Exception {
        Path vcf = writeVcf(
                "1\t400\t.\tA\tG\t.\tPASS\t.\tGT:PS\t0|1:phase-set-raw-42",
                "1\t401\t.\tC\tT\t.\tPASS\t.\tGT:PS\t1|0:phase-set-raw-42",
                "2\t402\t.\tG\tA\t.\tPASS\t.\tGT:PS\t0|1:phase-set-raw-42"
        );
        NormalizedVariantKey first = key("1", 400, "A", "G");
        NormalizedVariantKey second = key("1", 401, "C", "T");
        NormalizedVariantKey otherContig = key("2", 402, "G", "A");

        Map<NormalizedVariantKey, AnalysisVcfCall> calls = new AnalysisVcfAlleleIndex().index(
                vcf,
                GenomeAssembly.GRCh38,
                SAMPLE,
                Arrays.asList(first, second, otherContig)
        );
        String token = calls.get(first).getPhaseSetToken();

        assertEquals(AnalysisVcfCall.PhasingStatus.PHASE_SET, calls.get(first).getPhasingStatus());
        assertEquals("REF|TARGET_ALT", calls.get(first).getCandidateRelativeGenotype());
        assertEquals(token, calls.get(second).getPhaseSetToken());
        assertFalse(token.equals(calls.get(otherContig).getPhaseSetToken()));
        assertTrue(token.matches("[0-9a-f]{24}"));
        assertFalse(token.contains("phase-set-raw-42"));
        assertFalse(token.contains(SAMPLE));

        JsonNode json = new ObjectMapper().valueToTree(calls.get(first));
        String serialized = json.toString();
        assertFalse(serialized.contains(SAMPLE));
        assertFalse(serialized.contains("phase-set-raw-42"));
        assertFalse(serialized.contains(vcf.getFileName().toString()));

        String tokenFromAnotherAnalysisScope = new AnalysisVcfAlleleIndex().index(
                vcf,
                GenomeAssembly.GRCh38,
                SAMPLE,
                Collections.singleton(first)
        ).get(first).getPhaseSetToken();
        assertFalse(token.equals(tokenFromAnotherAnalysisScope));
    }

    @Test
    void reportsStableCodesForMissingSampleAndUnreadableVcf() throws Exception {
        Path vcf = writeVcf(
                "1\t500\t.\tA\tG\t.\tPASS\t.\tGT\t0/1"
        );
        NormalizedVariantKey target = key("1", 500, "A", "G");

        IllegalArgumentException sampleFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new AnalysisVcfAlleleIndex().index(
                        vcf,
                        GenomeAssembly.GRCh38,
                        "absent-sample",
                        Collections.singleton(target)
                )
        );
        assertEquals("ANALYSIS_VCF_SAMPLE_NOT_FOUND", sampleFailure.getMessage());

        IllegalStateException readFailure = assertThrows(
                IllegalStateException.class,
                () -> new AnalysisVcfAlleleIndex().index(
                        tempDir.resolve("does-not-exist.vcf"),
                        GenomeAssembly.GRCh38,
                        SAMPLE,
                        Collections.singleton(target)
                )
        );
        assertEquals("ANALYSIS_VCF_UNREADABLE", readFailure.getMessage());
    }

    @Test
    void domainValidationRejectsStateThatCouldMisrepresentAnotherAlternateAsReference() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisVcfCall.matched(
                        AnalysisVcfCall.TargetAlleleCall.HET_TARGET,
                        "OTHER_ALT/TARGET_ALT",
                        2,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AnalysisVcfCall.FilterEvaluation.PASS,
                        AnalysisVcfCall.PhasingStatus.UNPHASED,
                        null,
                        1,
                        GenomeAssembly.GRCh38,
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList()
                )
        );

        assertEquals("ANALYSIS_VCF_CALL_GENOTYPE_CLASS_MISMATCH", failure.getMessage());
    }

    @Test
    void domainValidationRejectsNonUniquePartialAndUnsupportedClassifications() {
        IllegalArgumentException partialAsComplete = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisVcfCall.matched(
                        AnalysisVcfCall.TargetAlleleCall.TARGET_WITH_OTHER_ALT,
                        "TARGET_ALT/OTHER_ALT/.",
                        2,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AnalysisVcfCall.FilterEvaluation.PASS,
                        AnalysisVcfCall.PhasingStatus.UNPHASED,
                        null,
                        1,
                        GenomeAssembly.GRCh38,
                        Collections.singletonList("GT"),
                        Collections.<String>emptyList()
                )
        );
        IllegalArgumentException knownAsUnsupported = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisVcfCall.matched(
                        AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED,
                        "REF/TARGET_ALT",
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AnalysisVcfCall.FilterEvaluation.PASS,
                        AnalysisVcfCall.PhasingStatus.UNPHASED,
                        null,
                        1,
                        GenomeAssembly.GRCh38,
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList()
                )
        );

        assertEquals("ANALYSIS_VCF_CALL_GENOTYPE_CLASS_MISMATCH", partialAsComplete.getMessage());
        assertEquals("ANALYSIS_VCF_CALL_GENOTYPE_CLASS_MISMATCH", knownAsUnsupported.getMessage());
    }

    @Test
    void domainValidationRequiresUnknownPhasingForNoCall() {
        IllegalArgumentException unphasedNoCall = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisVcfCall.matched(
                        AnalysisVcfCall.TargetAlleleCall.NO_CALL,
                        "./.",
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AnalysisVcfCall.FilterEvaluation.UNASSESSED,
                        AnalysisVcfCall.PhasingStatus.UNPHASED,
                        null,
                        1,
                        GenomeAssembly.GRCh38,
                        Collections.singletonList("GT"),
                        Collections.<String>emptyList()
                )
        );
        IllegalArgumentException phaseSetNoCall = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisVcfCall.matched(
                        AnalysisVcfCall.TargetAlleleCall.NO_CALL,
                        ".|.",
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AnalysisVcfCall.FilterEvaluation.UNASSESSED,
                        AnalysisVcfCall.PhasingStatus.PHASE_SET,
                        "0123456789abcdef01234567",
                        1,
                        GenomeAssembly.GRCh38,
                        Collections.singletonList("GT"),
                        Collections.<String>emptyList()
                )
        );

        assertEquals("ANALYSIS_VCF_CALL_PHASE_STATE_INCONSISTENT", unphasedNoCall.getMessage());
        assertEquals("ANALYSIS_VCF_CALL_PHASE_STATE_INCONSISTENT", phaseSetNoCall.getMessage());
    }

    @Test
    void domainValidationRejectsUnrecognizedFieldCodes() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisVcfCall.matched(
                        AnalysisVcfCall.TargetAlleleCall.HET_TARGET,
                        "REF/TARGET_ALT",
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AnalysisVcfCall.FilterEvaluation.PASS,
                        AnalysisVcfCall.PhasingStatus.UNPHASED,
                        null,
                        1,
                        GenomeAssembly.GRCh38,
                        Collections.singletonList("PATIENT_123"),
                        Collections.<String>emptyList()
                )
        );

        assertEquals("ANALYSIS_VCF_CALL_MISSING_FIELDS_INVALID", failure.getMessage());
    }

    private Map<NormalizedVariantKey, AnalysisVcfCall> index(Path vcf, NormalizedVariantKey key) {
        return new AnalysisVcfAlleleIndex().index(
                vcf,
                GenomeAssembly.GRCh38,
                SAMPLE,
                Collections.singleton(key)
        );
    }

    private NormalizedVariantKey key(String chromosome,
                                     int position,
                                     String reference,
                                     String alternate) {
        return NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38,
                chromosome,
                position,
                reference,
                alternate
        );
    }

    private Path writeVcf(String... records) throws Exception {
        Path vcf = tempDir.resolve("analysis.vcf");
        StringBuilder content = new StringBuilder()
                .append("##fileformat=VCFv4.2\n")
                .append("##contig=<ID=1>\n")
                .append("##contig=<ID=2>\n")
                .append("##FILTER=<ID=q10,Description=\"Synthetic low-quality filter\">\n")
                .append("##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n")
                .append("##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">\n")
                .append("##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n")
                .append("##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allelic depths\">\n")
                .append("##FORMAT=<ID=PS,Number=1,Type=String,Description=\"Phase set\">\n")
                .append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t")
                .append(SAMPLE)
                .append('\n');
        for (String record : records) {
            content.append(record).append('\n');
        }
        Files.write(vcf, content.toString().getBytes(StandardCharsets.UTF_8));
        return vcf;
    }
}
