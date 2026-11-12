package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExomiserZeroPassCoverageTest {
    @TempDir Path work;
    final ObjectMapper mapper = new ObjectMapper();
    final ExomiserOutputParser parser = new ExomiserOutputParser();
    final AnalysisScopePolicy.Policy policy = new AnalysisScopePolicy.Policy(AnalysisScopePolicy.Scope.INCLUDE_INTRONS,
            "VARVIS_INCLUDE_INTRONS_V2","b".repeat(64),"unchanged",AnalysisScopePolicy.ZERO_PASS_COVERAGE);
    @BeforeEach void fixture() throws Exception {
        for (String name : List.of("result.jsonl","result.genes.tsv","result.variants.tsv"))
            Files.writeString(work.resolve(name),retained(name));
    }
    String retained(String name) throws Exception {
        try(var input=getClass().getResourceAsStream("/exomiser/zero-pass-15.1.0/retained-output.json")) {
            return mapper.readTree(input).path(name).textValue();
        }
    }
    ExomiserOutputParser.ParsedOutput parse() throws Exception { return parser.parseVerified(work,"S1","15.1.0",policy); }
    ObjectNode json() throws Exception { return (ObjectNode)mapper.readTree(Files.readString(work.resolve("result.jsonl"))); }
    void save(JsonNode n) throws Exception { Files.writeString(work.resolve("result.jsonl"),n.toString()); }
    ObjectNode ar(ObjectNode n) { return (ObjectNode)n.path("geneScores").get(1); }
    void variantChange(java.util.function.Consumer<ObjectNode> change) throws Exception {
        var n=json();var v=(ObjectNode)n.path("variantEvaluations").get(0);change.accept(v);
        ((ArrayNode)ar(n).path("contributingVariants")).set(0,v.deepCopy());save(n);
    }
    @Test void retainedRealOutputsRestoreZeroWithoutTsvRowOrNativeVariantRankClaim() throws Exception {
        var output=parse(); assertEquals(1,output.interpretations().size());
        var v=output.interpretations().get(0);
        assertEquals("GRCh38|11|5225756|T|G",v.getVariantId());assertEquals(0.0,v.getRankingScore());
        var f=v.getExomiserCandidateFeatures();assertEquals(0.0,f.getVariantScore());assertEquals(0.0,f.getGeneCombinedScore());
        assertEquals(1,f.getGeneMoiGroupRank());assertEquals(1,f.getVariantSourceOrdinal());assertTrue(f.getFieldSources().get("variantSourceOrdinal").startsWith("DERIVED:"));assertEquals("result.jsonl",f.getVariantArtifactName());
        assertFalse(v.getEvidence().get(0).getDetails().containsKey("variant"));
        assertEquals("c173881131169a7ae032cc69bc64ad60b5926ea249587de4bf61fe738ffa035f",output.jsonScan().rawSha256());
        assertNotEquals(output.variantScan().rawSha256(),output.outputSha256());parser.verifyStillCurrent(work,output);
    }
    void mixed() throws Exception {
        Files.writeString(work.resolve("result.variants.tsv"),"#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\tEXOMISER_VARIANT_SCORE\n"
                +"2\t1\t200\tA\tG\tOTHER\t2\tAR\t0.7\n"+"1\t1\t100\tC\tT\tTHIRD\t3\tAR\t0.9\n");
    }
    void completeMixed(double score, String formatted) throws Exception {
        // Engineering records: both positive rows have matching JSONL and gene/variant TSV sources.
        // Expected TSV strings are supplied independently, never produced by the code under test.
        mixed();
        String variants = Files.readString(work.resolve("result.variants.tsv"));
        Files.writeString(work.resolve("result.variants.tsv"),variants.replace("\t0.7\n","\t"+formatted+"\n")
                .replace("\t0.9\n","\t0.0938\n"));
        appendPositiveGene("OTHER","2",2,200,"A","G",score,formatted);
        appendPositiveGene("THIRD","3",1,100,"C","T",0.09375,"0.0938");
    }
    void appendPositiveGene(String symbol, String entrez, int rank, int position,
                            String ref, String alt, double score, String formatted) throws Exception {
        var id=mapper.createObjectNode().put("geneId","synthetic-"+symbol).put("geneSymbol",symbol).put("entrezId",entrez);
        var variant=mapper.createObjectNode().put("contigName","1").put("start",position).put("end",position)
                .put("ref",ref).put("alt",alt).put("type","SNV").put("filterStatus","PASSED")
                .put("variantScore",score).put("contributesToGeneScore",true);
        variant.putArray("passedFilterTypes").add("FREQUENCY_FILTER").add("INHERITANCE_FILTER");
        variant.putArray("compatibleInheritanceModes").add("AUTOSOMAL_RECESSIVE");
        variant.putArray("contributingInheritanceModes").add("AUTOSOMAL_RECESSIVE");
        var gene=mapper.createObjectNode().put("geneSymbol",symbol);gene.set("geneIdentifier",id);
        gene.putArray("variantEvaluations").add(variant);
        var group=gene.putArray("geneScores").addObject().put("modeOfInheritance","AUTOSOMAL_RECESSIVE")
                .put("combinedScore",score).put("phenotypeScore",score).put("variantScore",score).put("pValue",score);
        group.set("geneIdentifier",id);group.putArray("contributingVariants").add(variant.deepCopy());
        Files.writeString(work.resolve("result.jsonl"),"\n"+gene,StandardOpenOption.APPEND);
        Files.writeString(work.resolve("result.genes.tsv"),rank+"\t"+symbol+"_AR\t"+symbol+"\t"+entrez+"\tAR\t"
                +String.join("\t",Collections.nCopies(4,formatted))+"\n",StandardOpenOption.APPEND);
    }
    @Test void mixedPreservesOriginalFieldsOrderAndAddsOnlyMissingRows() throws Exception {
        completeMixed(0.03125,"0.0312");
        var old=parser.parse(work,"S1","15.1.0");var result=parse();assertEquals(3,result.interpretations().size());
        for(int i=0;i<2;i++)assertEquals(mapper.valueToTree(old.get(i)),mapper.valueToTree(result.interpretations().get(i)));
        assertEquals(List.of("GRCh38|1|200|A|G","GRCh38|1|100|C|T","GRCh38|11|5225756|T|G"),
                result.interpretations().stream().map(v->v.getVariantId()).toList());
        assertEquals(List.of(0.0312,0.0938,0.0),result.interpretations().stream().map(v->v.getRankingScore()).toList());
        assertEquals(3,result.jsonScan().dataRowCount());assertEquals(3,result.geneScan().dataRowCount());
        assertEquals(2,result.variantScan().dataRowCount());assertEquals(1,result.manifest().path("supplementedRows").asInt());
        Files.writeString(work.resolve("result.variants.tsv"),"1\t11\t5225756\tT\tG\tHBB\t3043\tAR\t0.0\n",StandardOpenOption.APPEND);
        assertEquals(3,parse().interpretations().size());assertEquals(0,parse().manifest().path("supplementedRows").asInt());
    }
    @ParameterizedTest
    @CsvSource({"0.03125,0.0312", "0.09375,0.0938", "0.00015,0.0001",
            "0.031249999999999997,0.0312", "0.031250000000000007,0.0313"})
    void officialDoubleRoundingBoundariesAcceptCompleteSources(double score, String formatted) throws Exception {
        completeMixed(score,formatted);
        assertEquals(3,parse().interpretations().size());
    }
    @ParameterizedTest
    @CsvSource({"5", "6", "7", "8"})
    void genuineConflictInEachGeneScoreColumnStillFailsBeforeNonzeroSkip(int column) throws Exception {
        completeMixed(0.03125,"0.0312");
        var rows=Files.readAllLines(work.resolve("result.genes.tsv"));
        String[] positive=rows.get(2).split("\t",-1);positive[column]="0.0313";rows.set(2,String.join("\t",positive));
        Files.write(work.resolve("result.genes.tsv"),rows);
        assertEquals("EXOMISER_OUTPUT_COVERAGE_SCORE_CONFLICT",assertThrows(IllegalArgumentException.class,this::parse).getMessage());
    }
    @Test void roundedNonzeroMissingFromVariantTsvIsStillNotSupplemented() throws Exception {
        completeMixed(0.0000001,"0.0000");
        var rows=Files.readAllLines(work.resolve("result.variants.tsv"));rows.remove(1);
        Files.write(work.resolve("result.variants.tsv"),rows);
        var result=parse();assertEquals(2,result.interpretations().size());
        assertEquals(List.of("THIRD","HBB"),result.interpretations().stream().map(v->v.getExomiserCandidateFeatures().getGeneSymbol()).toList());
        assertEquals(1,result.manifest().path("supplementedRows").asInt());
    }
    @Test @org.junit.jupiter.api.parallel.ResourceLock(value="java.util.Locale",mode=org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE)
    void concurrentParsingUsesFixedSymbolsWithoutChangingDefaultLocale() throws Exception {
        completeMixed(0.03125,"0.0312");
        Locale previous=Locale.getDefault(Locale.Category.FORMAT);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            Locale.setDefault(Locale.Category.FORMAT,Locale.GERMANY);
            List<java.util.concurrent.Callable<Integer>> tasks=new ArrayList<>();
            for(int i=0;i<16;i++)tasks.add(()->parse().interpretations().size());
            for(var result:pool.invokeAll(tasks))assertEquals(3,result.get());
            assertEquals(Locale.GERMANY,Locale.getDefault(Locale.Category.FORMAT));
        } finally { pool.shutdownNow();Locale.setDefault(Locale.Category.FORMAT,previous); }
    }
    @Test void smallNonzeroRoundedToZeroIsNotRestored() throws Exception {
        mixed();var n=json();ar(n).put("combinedScore",0.0000001);save(n);assertEquals(2,parse().interpretations().size());
    }
    @Test void failedUnfilteredAndIncompatibleNeverRestore() throws Exception {
        mixed();for(String status:List.of("FAILED","UNFILTERED")) {
            variantChange(v->v.put("filterStatus",status));assertEquals(2,parse().interpretations().size());
        }
        variantChange(v->{v.put("filterStatus","PASSED");v.putArray("compatibleInheritanceModes").add("AUTOSOMAL_DOMINANT");});
        assertEquals(2,parse().interpretations().size());
        variantChange(v->{v.putArray("compatibleInheritanceModes").add("AUTOSOMAL_RECESSIVE");v.put("contributesToGeneScore",false);});
        assertThrows(IllegalArgumentException.class,this::parse);
    }
    @Test void missingCorruptAmbiguousOrConflictingSourcesFailClosedEvenWithExistingRows() throws Exception {
        mixed();String original=Files.readString(work.resolve("result.jsonl"));
        for(String invalid:List.of("{}",original+"\n"+original,original.replace("\"pValue\":1.0","\"combinedScore\":0.5,\"pValue\":1.0"),"{broken")) {
            Files.writeString(work.resolve("result.jsonl"),invalid);assertThrows(IllegalArgumentException.class,this::parse);
        }
        Files.delete(work.resolve("result.jsonl"));assertThrows(IllegalArgumentException.class,this::parse);
    }
    @Test void contributionIdentityMismatchAndArtifactDriftFailClosed() throws Exception {
        var n=json();((ObjectNode)ar(n).path("contributingVariants").get(0)).put("alt","A");save(n);
        assertThrows(IllegalArgumentException.class,this::parse);
        Files.writeString(work.resolve("result.jsonl"),retained("result.jsonl"));
        var output=parse();Files.writeString(work.resolve("result.jsonl")," ",StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class,()->parser.verifyStillCurrent(work,output));
    }
    @Test void standardAndLegacyIntronsRetainEmptyFailureAndDoNotReadJsonl() throws Exception {
        Files.delete(work.resolve("result.jsonl"));
        var old=new AnalysisScopePolicy.Policy(AnalysisScopePolicy.Scope.INCLUDE_INTRONS,"VARVIS_INCLUDE_INTRONS_V1","a".repeat(64),"unchanged");
        assertEquals("EXOMISER_OUTPUT_VARIANTS_EMPTY",assertThrows(IllegalArgumentException.class,()->parser.parseVerified(work,"S1","15.1.0",old)).getMessage());
        mixed();assertEquals(2,parser.parse(work,"S1","15.1.0").size());
    }
    @Test void actualAdapterPublicationTopNAndRecoveryPreserveAppendOrderAndIsolatePolicies() throws Exception {
        completeMixed(0.03125,"0.0312");
        String vcf=ParsedCandidateFixture.vcf("1\t200\t.\tA\tG\t.\tPASS\t.\tGT\t1/1",
                "1\t100\t.\tC\tT\t.\tPASS\t.\tGT\t1/1","11\t5225756\t.\tT\tG\t.\tPASS\t.\tGT\t1/1");
        var prepared=ParsedCandidateFixture.prepare(work.resolve("job"),"coverage-job",
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,vcf,Files.readString(work.resolve("result.variants.tsv")),null,false,work,policy);
        var result=prepared.adapter().prepare(prepared.job());
        assertEquals(org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus.RANKED,result.getMetadata().getStatus(),result.getFailureCode());
        assertEquals(3,result.getInterpretations().size());
        assertEquals(org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall.MatchStatus.MATCHED,result.getInterpretations().get(2).getAnalysisVcfCall().getMatchStatus());
        var properties=new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        var service=new org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl(null,List.of(),
                new org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService(properties));
        var original=parser.parse(work,"synthetic-proband","15.1.0");
        var baseline=service.enrichEvidenceOnly(original,null);
        var enriched=service.enrichEvidenceOnly(result.getInterpretations(),null);
        for(int i=0;i<2;i++)assertEquals(baseline.get(i).getRankingScore(),enriched.get(i).getRankingScore());
        assertEquals(0.0,enriched.get(2).getRankingScore());
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:coverage;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/variant-analysis-schema.sql"),
                new org.springframework.core.io.ClassPathResource("db/exomiser-analysis-schema.sql"),
                new org.springframework.core.io.ClassPathResource("db/acmg-business-schema.sql")).execute(ds);
        var repository=new org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),properties,mapper.findAndRegisterModules());
        prepared.job().applyEngineMetadata(result.getMetadata());repository.save(prepared.job());
        repository.replaceInterpretations(prepared.job().getId(),enriched);
        var ranked=repository.findInterpretations(prepared.job().getId(),0,3);
        assertEquals(0.0,ranked.get(2).getRankingScore());assertEquals(enriched.get(2).getCandidateId(),ranked.get(2).getCandidateId());
        assertEquals(2,repository.findInterpretations(prepared.job().getId(),0,2).size());
        var store=new org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore(ds);
        var sources=new AcmgExecutionSources(mapper,store);
        sources.publish(prepared.job(),prepared.conversion(),result,ranked);
        var recovered=new AcmgExecutionSources(mapper,store).recover(prepared.job(),ranked);
        assertTrue(recovered.candidateBlockers().isEmpty());assertEquals(result.getMetadata().getOutputSha256(),recovered.execution().getMetadata().getOutputSha256());
        assertTrue(AnalysisScopePolicy.reusable(prepared.job(),policy));
        var old=new AnalysisScopePolicy.Policy(AnalysisScopePolicy.Scope.INCLUDE_INTRONS,"VARVIS_INCLUDE_INTRONS_V1","a".repeat(64),policy.effectiveYaml());
        assertFalse(AnalysisScopePolicy.reusable(prepared.job(),old));
        ObjectNode snapshot=(ObjectNode)mapper.readTree(store.source(prepared.job().getId()).orElseThrow().json());
        snapshot.remove("outputCoverage");new org.springframework.jdbc.core.JdbcTemplate(ds).update(
                "UPDATE variant_analysis_acmg_source SET snapshot_sha256=?, snapshot_json=? WHERE analysis_id=?",
                org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(snapshot.toString()),snapshot.toString(),prepared.job().getId());
        assertThrows(IllegalArgumentException.class,()->sources.recover(prepared.job(),ranked));
        new org.springframework.jdbc.core.JdbcTemplate(ds).execute("SHUTDOWN");
    }
    @Test void consistentNonContributorFollowsActualFalseOutputSetting() throws Exception {
        var n=json();ar(n).remove("contributingVariants");
        var v=(ObjectNode)n.path("variantEvaluations").get(0);v.remove("contributesToGeneScore");v.remove("contributingInheritanceModes");save(n);
        assertEquals(1,parse().interpretations().size());assertFalse(parse().interpretations().get(0).getExomiserCandidateFeatures().getContributingVariant());
    }
    @Test void inconsistentSnvEndAndAssemblyAreRejected() throws Exception {
        variantChange(v->v.put("end",5225757));assertThrows(IllegalArgumentException.class,this::parse);
        variantChange(v->{v.put("end",5225756);v.put("genomeAssembly","GRCh37");});assertThrows(IllegalArgumentException.class,this::parse);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="varvis.zeroPass.actualOutput",matches=".+")
    void finalParserReproducesCurrentRealOutputManifestWithoutRunningAnyEngine() throws Exception {
        Path output=Path.of(System.getProperty("varvis.zeroPass.actualOutput"));
        var snapshot=mapper.readTree(Files.readString(output.getParent().getParent().resolve("analysis-scope-v1.json")));
        var savedPolicy=new AnalysisScopePolicy.Policy(AnalysisScopePolicy.Scope.valueOf(snapshot.path("scope").asText()),
                snapshot.path("policyId").asText(),snapshot.path("policySha256").asText(),snapshot.path("effectiveYaml").asText(),snapshot.path("outputCoveragePolicy").asText());
        var parsed=parser.parseVerified(output,"synthetic-proband","15.1.0",savedPolicy);
        assertEquals(Files.readString(output.getParent().resolve("output-coverage-v1.json")),parsed.manifest().toString());
        assertEquals(1,parsed.interpretations().size());assertEquals(0.0,parsed.interpretations().get(0).getRankingScore());
        parser.verifyStillCurrent(output,parsed);
    }
    @Test void malformedContributionBooleanIsNotCoercedToApproval() throws Exception {
        variantChange(v->v.put("contributesToGeneScore","true"));
        assertThrows(IllegalArgumentException.class,this::parse);
    }
}
