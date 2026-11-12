package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.service.CandidateAlleleCallSummary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Existing VCF parser + pure typed summary over tiny SYNTHETIC input; no CURRENT/store/clinical adoption. */
class CandidateAlleleCallSummaryParsedTest {
    private static final String SAMPLE = "PRIVATE-SAMPLE-NOT-FOR-PROJECTION";
    private static final String RECORD = "PRIVATE-RECORD-NOT-FOR-PROJECTION";
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temporary;

    private record Case(String name, String genotype, String alternates, String target,
                        int index, String contig, AnalysisVcfCall.TargetAlleleCall call,
                        Boolean reported, Boolean complete) { }

    static Stream<Case> sourceCases() {
        return Stream.of(
                new Case("HOM_REF", "0/0", "G", "G", 1, "1", AnalysisVcfCall.TargetAlleleCall.HOM_REF, false, true),
                new Case("HET_TARGET", "0/1", "G", "G", 1, "1", AnalysisVcfCall.TargetAlleleCall.HET_TARGET, true, true),
                new Case("HOM_TARGET", "1/1", "G", "G", 1, "1", AnalysisVcfCall.TargetAlleleCall.HOM_TARGET, true, true),
                new Case("HEMI_REF", "0", "G", "G", 1, "Y", AnalysisVcfCall.TargetAlleleCall.HEMI_REF, false, true),
                new Case("HEMI_TARGET", "1", "G", "G", 1, "Y", AnalysisVcfCall.TargetAlleleCall.HEMI_TARGET, true, true),
                new Case("TARGET_WITH_OTHER_ALT", "1/2", "C,G", "G", 2, "1", AnalysisVcfCall.TargetAlleleCall.TARGET_WITH_OTHER_ALT, true, true),
                new Case("OTHER_ALT_ONLY", "1/1", "C,G", "G", 2, "1", AnalysisVcfCall.TargetAlleleCall.OTHER_ALT_ONLY, false, true),
                new Case("PARTIAL_TARGET", "2/.", "C,G", "G", 2, "1", AnalysisVcfCall.TargetAlleleCall.PARTIAL_TARGET, true, false),
                new Case("NO_CALL", "./.", "G", "G", 1, "1", AnalysisVcfCall.TargetAlleleCall.NO_CALL, null, false),
                new Case("UNSUPPORTED", "0/.", "G", "G", 1, "1", AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED, null, false));
    }

    @ParameterizedTest @MethodSource("sourceCases")
    void preservesAllExistingTargetClassesWithoutParsingGenotypeAgain(Case input) throws Exception {
        var key = key(GenomeAssembly.GRCh38, input.contig(), 100, input.target());
        Path vcf = writeVcf(input.contig()+"\t100\t"+RECORD+"\tA\t"+input.alternates()+"\t.\tPASS\t.\tGT\t"+input.genotype());
        var call = index(vcf, GenomeAssembly.GRCh38, key);
        assertEquals(input.call(), call.getTargetAlleleCall());
        assertEquals(input.index(), call.getTargetAlleleIndex());
        byte[] sourceBytes = Files.readAllBytes(vcf), callBytes = mapper.writeValueAsBytes(call);
        var summary = CandidateAlleleCallSummary.summarize(call, key);
        assertEquals(input.reported(), summary.targetAltReported());
        assertEquals(input.complete(), summary.genotypeComplete());
        assertEquals(input.call(), summary.targetAlleleCall());
        assertEquals(call.getCandidateRelativeGenotype(), summary.candidateRelativeGenotype());
        assertEquals(input.index(), summary.source().targetAlleleIndex());
        assertEquals("GRCh38", summary.source().assembly());
        assertArrayEquals(sourceBytes, Files.readAllBytes(vcf));
        assertArrayEquals(callBytes, mapper.writeValueAsBytes(call));
        assertSafe(summary);
        emit(input.name(), call, key, summary);
    }

    @Test void filteredAndMissingQualityStaySeparateFromReportedTarget() throws Exception {
        var key = key(GenomeAssembly.GRCh38,"1",100,"G");
        var call = index(writeVcf("1\t100\t"+RECORD+"\tA\tG\t.\tq10\t.\tGT\t0/1"),GenomeAssembly.GRCh38,key);
        assertEquals(AnalysisVcfCall.FilterEvaluation.FILTERED,call.getFilterEvaluation());
        assertTrue(call.getMissingFields().contains("DP"));
        var summary = CandidateAlleleCallSummary.summarize(call,key);
        assertEquals(Boolean.TRUE,summary.targetAltReported());
        assertEquals(Boolean.TRUE,summary.genotypeComplete());
        var json=mapper.valueToTree(summary);
        assertEquals("FILTERED",json.at("/quality/filterEvaluation").asText());
        assertEquals(mapper.valueToTree(call.getMissingFields()),json.at("/quality/missingFields"));
        emit("FILTERED_WITH_MISSING_QUALITY",call,key,summary);
    }

    @Test void missingOrAmbiguousOriginalRecordsRemainUnknown() throws Exception {
        var key=key(GenomeAssembly.GRCh38,"1",100,"G");
        var missing=index(writeVcf("1\t101\t"+RECORD+"\tA\tG\t.\tPASS\t.\tGT\t0/1"),GenomeAssembly.GRCh38,key);
        var ambiguous=index(writeVcf("1\t100\t"+RECORD+"\tA\tG\t.\tPASS\t.\tGT\t0/1",
                "1\t100\t"+RECORD+"\tA\tG\t.\tPASS\t.\tGT\t1/1"),GenomeAssembly.GRCh38,key);
        for (var call:List.of(missing,ambiguous)) {
            var summary=CandidateAlleleCallSummary.summarize(call,key);
            assertNull(summary.targetAltReported()); assertNull(summary.genotypeComplete());
            assertEquals(call.getMatchStatus(),summary.source().matchStatus());
            emit(call.getMatchStatus().name(),call,key,summary);
        }
    }

    @Test void originalAnalysisAssemblyIsNotReplacedByASeparateQueryProjection() throws Exception {
        var original=key(GenomeAssembly.GRCh37,"1",100,"G");
        var query=key(GenomeAssembly.GRCh38,"1",200,"G");
        var call=index(writeVcf("1\t100\t"+RECORD+"\tA\tG\t.\tPASS\t.\tGT\t0/1"),GenomeAssembly.GRCh37,original);
        var originalSummary=CandidateAlleleCallSummary.summarize(call,original);
        assertEquals("GRCh37",originalSummary.source().assembly());
        assertEquals("GRCh37",originalSummary.source().originalAnalysisAssembly());
        assertEquals(Boolean.TRUE,originalSummary.targetAltReported());
        var wrongQuerySubstitution=CandidateAlleleCallSummary.summarize(call,query);
        assertNull(wrongQuerySubstitution.targetAltReported());
        assertNull(wrongQuerySubstitution.genotypeComplete());
        assertEquals("GRCh37",call.getAssembly());
        assertEquals("GRCh37",original.getGenomeAssembly());
        emit("ORIGINAL_GRCH37_ANALYSIS_FACT",call,original,originalSummary);
        emit("QUERY_COPY_ASSEMBLY_MISMATCH",call,query,wrongQuerySubstitution);
    }

    @Test void candidateProjectionReadsOnlySafeOriginalFactsAndLeavesCandidateUnchanged() throws Exception {
        var key=key(GenomeAssembly.GRCh38,"Y",100,"G");
        var call=index(writeVcf("Y\t100\t"+RECORD+"\tA\tG\t.\tPASS\t.\tGT\t1"),GenomeAssembly.GRCh38,key);
        var observation=new VariantQcObservation(key,SAMPLE,"RAW-GT-NOT-FOR-PROJECTION",20,50,"RAW-AD",0.5,"PASS","SNV",RECORD);
        var candidate=new VariantInterpretation(key.canonicalId(),observation,List.of(),List.of()).withAnalysisVcfCall(call);
        byte[] before=mapper.writeValueAsBytes(candidate);
        var summary=CandidateAlleleCallSummary.summarize(candidate);
        assertEquals(Boolean.TRUE,summary.targetAltReported());
        assertEquals(call.getCandidateRelativeGenotype(),summary.candidateRelativeGenotype());
        assertArrayEquals(before,mapper.writeValueAsBytes(candidate));
        assertSafe(summary);
        assertFalse(mapper.writeValueAsString(summary).contains("RAW-GT-NOT-FOR-PROJECTION"));
        assertFalse(mapper.writeValueAsString(summary).contains("RAW-AD"));
        var legacy=new VariantInterpretation(key.canonicalId(),observation,List.of(),List.of());
        var legacySummary=CandidateAlleleCallSummary.summarize(legacy);
        assertNull(legacySummary.targetAltReported());assertNull(legacySummary.genotypeComplete());
        assertSafe(legacySummary);
        emit("LEGACY_CANDIDATE_WITHOUT_CALL",null,key,legacySummary);
    }

    private void assertSafe(CandidateAlleleCallSummary.Summary summary) throws Exception {
        String json=mapper.writeValueAsString(summary);
        assertFalse(json.contains(SAMPLE));assertFalse(json.contains(RECORD));
        assertFalse(json.contains(temporary.toString()));assertFalse(json.contains("sampleId"));
        assertFalse(json.contains("originalRecord"));
    }

    private void emit(String name,AnalysisVcfCall call,NormalizedVariantKey original,
                      CandidateAlleleCallSummary.Summary summary) throws Exception {
        String output=System.getProperty("varvis.a10.sample-call.output");
        if(output==null||output.isBlank())return;
        Path folder=Path.of(output);Files.createDirectories(folder);
        var sample=mapper.createObjectNode().put("scenario",name).put("dataClass","SYNTHETIC")
                .put("layer","SYNTHETIC_TINY_VCF_ACTUAL_EXISTING_PARSER_PURE_HELPER")
                .put("centralCurrentAndPersistence","NOT_CONNECTED_NOT_RUN")
                .put("clinicalAdoption","NOT_CLAIMED");
        sample.set("analysisVcfCall",mapper.valueToTree(call));
        sample.set("originalAnalysisAllele",mapper.valueToTree(original));
        sample.set("summary",mapper.valueToTree(summary));
        String json=mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample);
        assertFalse(json.contains(SAMPLE));assertFalse(json.contains(RECORD));assertFalse(json.contains(temporary.toString()));
        Files.writeString(folder.resolve(name+".json"),json,StandardCharsets.UTF_8);
    }

    private AnalysisVcfCall index(Path source,GenomeAssembly assembly,NormalizedVariantKey key) {
        var call=new AnalysisVcfAlleleIndex().index(source,assembly,SAMPLE,List.of(key)).get(key);
        return java.util.Objects.requireNonNull(call,"Existing parser must publish a typed matched/unmatched call");
    }
    private NormalizedVariantKey key(GenomeAssembly assembly,String contig,int position,String alt) {
        return NormalizedVariantKey.canonical(assembly,contig,position,"A",alt);
    }
    private Path writeVcf(String... rows) throws Exception {
        Path path=temporary.resolve("synthetic-input.vcf");
        String header="##fileformat=VCFv4.2\n##contig=<ID=1>\n##contig=<ID=Y>\n"
                +"##FILTER=<ID=q10,Description=\"Synthetic filter\">\n"
                +"##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                +"#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t"+SAMPLE+"\n";
        Files.writeString(path,header+String.join("\n",rows)+"\n",StandardCharsets.UTF_8);return path;
    }
}
