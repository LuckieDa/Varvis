package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;

class PublishedSampleFilterTest {
    @TempDir Path temp;
    private static final String HEADER="##fileformat=VCFv4.2\n##contig=<ID=1,length=1000000>\n"
            +"##FORMAT=<ID=GT,Number=1,Type=String,Description=\"genotype\">\n"
            +"##FORMAT=<ID=FT,Number=1,Type=String,Description=\"filter\">\n";
    private static NormalizedVariantKey key(int position) { return new NormalizedVariantKey("GRCh38","1",position,"A","G"); }
    @ParameterizedTest
    @CsvSource(value={"GT:FT|0/1:PASS|PASS","GT:FT|0/1:LowQual|FILTERED","GT:FT|0/1:0|PARSER_REJECT",
            "GT|0/1|FORMAT_ABSENT","GT:FT|0/1:.|DOT","GT:FT|0/1:|PARSER_REJECT","GT:FT|0/1|VALUE_MISSING"},delimiter='|')
    void sameStreamPreservesRawDistinctionsWithUnchangedPublicCall(String format,String sample,String expected) throws Exception {
        String vcf=HEADER+"#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tselected\n"
                +"1\t100\t.\tA\tG\t.\tPASS\t.\t"+format+"\t"+sample+"\n";
        Path file=temp.resolve("input.vcf"); Files.writeString(file,vcf);
        if(expected.equals("PARSER_REJECT")) {
            var error=assertThrows(IllegalStateException.class,()->new AnalysisVcfAlleleIndex().indexWithSource(file,GenomeAssembly.GRCh38,"selected",List.of(key(100))));
            assertEquals("ANALYSIS_VCF_UNREADABLE",error.getMessage());
            assertNotNull(error.getCause()); assertTrue(error.getCause().getMessage().contains("filter"));
            Files.writeString(temp.resolve("parser-rejection.txt"),error+"\n"+error.getCause()); return;
        }
        var indexed=new AnalysisVcfAlleleIndex().indexWithSource(file,GenomeAssembly.GRCh38,"selected",List.of(key(100)));
        var witness=indexed.sampleFilters().get(key(100)); assertNotNull(witness); assertEquals(expected,witness.state().name());
        assertEquals(1,indexed.fullScans()); assertEquals(ParsedCandidateFixture.sha(Files.readAllBytes(file)),indexed.rawSha256());
        assertEquals(AnalysisVcfCall.FilterEvaluation.PASS,indexed.calls().get(key(100)).getFilterEvaluation());
        assertEquals(indexed.records().get(key(100)),witness.record());
        var mapper=new ObjectMapper(); var json=mapper.valueToTree(witness);
        assertEquals(witness,PublishedSampleFilter.recover(json,"selected","GRCh38",witness.record()));
        assertFalse(mapper.writeValueAsString(indexed.calls().get(key(100))).contains("sampleFilter"));
        Files.writeString(temp.resolve("witness.json"),json.toPrettyString());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void sampleOrderAndFtColumnOrderCannotBorrowAnotherPass(boolean selectedFirst) throws Exception {
        String names=selectedFirst?"selected\tother":"other\tselected";
        String a=selectedFirst?"0/1:LowQual\t0/1:PASS":"0/1:PASS\t0/1:LowQual";
        String b=selectedFirst?"0/1:PASS:40\t0/1:LowQual:40":"0/1:LowQual:40\t0/1:PASS:40";
        String vcf=HEADER+"##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"depth\">\n"
                +"#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t"+names+"\n"
                +"1\t100\t.\tA\tG\t.\tPASS\t.\tGT:FT\t"+a+"\n"
                +"1\t200\t.\tA\tG\t.\tPASS\t.\tGT:FT:DP\t"+b+"\n";
        Path file=temp.resolve("two-samples.vcf"); Files.writeString(file,vcf);
        var indexed=new AnalysisVcfAlleleIndex().indexWithSource(file,GenomeAssembly.GRCh38,"selected",List.of(key(100),key(200)));
        assertEquals(PublishedSampleFilter.State.FILTERED,indexed.sampleFilters().get(key(100)).state());
        assertEquals(PublishedSampleFilter.State.PASS,indexed.sampleFilters().get(key(200)).state());
        assertEquals(1,indexed.sampleFilters().get(key(100)).record().recordOrdinal());
        assertEquals(2,indexed.sampleFilters().get(key(200)).record().recordOrdinal()); assertEquals(1,indexed.fullScans());
    }
    @Test void duplicateAndMissingMatchesRetainNoWitness() throws Exception {
        Path file=temp.resolve("ambiguous.vcf");
        String row="1\t100\t.\tA\tG\t.\tPASS\t.\tGT:FT\t0/1:PASS\n";
        Files.writeString(file,HEADER+"#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tselected\n"+row+row);
        var indexed=new AnalysisVcfAlleleIndex().indexWithSource(file,GenomeAssembly.GRCh38,"selected",List.of(key(100),key(200)));
        assertTrue(indexed.sampleFilters().isEmpty()); assertTrue(indexed.records().isEmpty()); assertEquals(1,indexed.fullScans());
    }
    @Test void unavailableRawOrMissingSampleNeverGetsInferredPass() {
        var record=new EngineExecutionResult.MatchedRecord("1",100,"A",List.of("G"),1,1);
        assertEquals(PublishedSampleFilter.State.RAW_UNAVAILABLE,PublishedSampleFilter.capture(null,0,"selected","GRCh38",record).state());
        assertEquals(PublishedSampleFilter.State.SAMPLE_MISSING,PublishedSampleFilter.capture("GT:FT\t.",0,"selected","GRCh38",record).state());
        assertEquals(PublishedSampleFilter.State.SAMPLE_MISSING,PublishedSampleFilter.capture("GT:FT",0,"selected","GRCh38",record).state());
        assertEquals(PublishedSampleFilter.State.MALFORMED,PublishedSampleFilter.capture("GT:FT:FT\t0/1:PASS:PASS",0,"selected","GRCh38",record).state());
    }
}
