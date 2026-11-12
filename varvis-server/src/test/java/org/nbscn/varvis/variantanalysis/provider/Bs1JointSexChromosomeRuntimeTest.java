package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Independent synthetic rule boundaries; optional public RAW replay is not real disease adoption. */
class Bs1JointSexChromosomeRuntimeTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Bs1SexChromosomeRuntimeTest old = new Bs1SexChromosomeRuntimeTest();
    final GnomadJointSexStratifiedObservationTest source = new GnomadJointSexStratifiedObservationTest();
    @TempDir Path temporary;
    static final String SOURCE = "a".repeat(64);

    ObjectNode record(boolean af) {
        var r = old.record("GT");
        r.withObject("/scope/sexChromosome").put("requiredDataset", "JOINT");
        r.withObject("/expertPolicy/scope/sexChromosome").put("requiredDataset", "JOINT");
        r.withObject("/expertPolicy/statistic").put("metricId", af ? Bs1SexChromosomePolicy.JOINT_AF : Bs1SexChromosomePolicy.JOINT_FAF95)
                .put("confidence", af ? "POINT_ESTIMATE" : "95_PERCENT_LOWER_BOUND")
                .put("sourceTerm", af ? "AF" : "FAF95").put("interpretation", af ? "TARGET_ALT_AF" : "TARGET_ALT_FAF95");
        r.withObject("/expertPolicy/tiers/0/conditions/0").put("fact", af ? "AF" : "FAF95");
        qualify(r); return r;
    }
    void qualify(ObjectNode r) {
        Bs1SexChromosomeStatisticTest.qualify(r);
        r.withObject("/qualification/priorReview").put("dataScopePolicyId", Bs1SexChromosomePolicy.JOINT_SCOPE);
        new Bs1KnowledgeCatalogTest().review(r);
        r.withObject("review").put("dataScopePolicyId", Bs1SexChromosomePolicy.JOINT_SCOPE);
    }
    ObjectNode context(ObjectNode r) throws Exception { return old.context(r); }
    Bs1Runtime runtime(ObjectNode r) throws Exception {
        var bytes = mapper.writeValueAsBytes(old.bundle(r)); var file = temporary.resolve("knowledge-" + Bs1KnowledgeCatalog.sha(bytes) + ".json");
        Files.write(file, bytes); var catalog = Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(bytes));
        return new Bs1Runtime(mapper, catalog.merge(List.of()), mock(VariantEvidenceProvider.class), null);
    }
    Map<String,String> fields(String ac, String an) {
        var f = new LinkedHashMap<String,String>();
        f.put("AC_joint_nfe_XY", ac); f.put("AN_joint_nfe_XY", an);
        f.put("AF_joint_nfe_XY", new BigDecimal(ac).divide(new BigDecimal(an), java.math.MathContext.DECIMAL128).toPlainString());
        f.put("faf95_joint_nfe_XY", "0.001"); f.put("stat_union_p_value", "0.5");
        // Deliberately no aggregate FAF, AC/AN/AF or max-ancestry inputs.
        return f;
    }
    ObjectNode parent(ObjectNode c, Map<String,String> f, String filter) {
        var p = source.parent(c, f, filter, SOURCE);
        String uri = Bs1JointFilterSource.sexContract(mapper).at("/headers/X/url").asText();
        var t = mapper.createObjectNode().put("querySource", "remote-joint-vcf-population-input").put("sourceDataset", "joint")
                .put("gnomadVersion", GnomadPopulationInputProfile.loadExecutableV2().getDocument().at("/resources/0/resourceVersion").asText())
                .put("resourceUri", uri).put("resourceIndexUri", uri + ".tbi");
        return PopulationObservation.withTransport(mapper, p, t);
    }
    String status(JsonNode r) { return r.at("/evaluation/evaluationResult/status").asText(); }
    String reason(JsonNode r) { return r.at("/evaluation/evaluationResult/reasonCode").asText(); }

    @ParameterizedTest @CsvSource({"19,NOT_MET", "20,NOT_MET", "21,MET"})
    void exactJointBoundaryWithoutAggregateGateOrProvider(String ac, String expected) throws Exception {
        var rec = record(true); var c = context(rec); var o = parent(c, fields(ac, "10000"), "PASS"); var before = o.deepCopy();
        var runtime = runtime(rec); var r = runtime.replay(c, o, SOURCE);
        assertEquals(expected, status(r), r.path("evaluation").toString());
        assertEquals("READY", r.at("/qualityDecision/sourceQualityDecision/status").asText());
        assertEquals("JOINT", r.at("/qualityDecision/sexStratifiedObservation/dataset").asText());
        assertEquals(ac, r.at("/qualityDecision/facts/AC").asText());
        assertEquals(Bs1SexChromosomeStatistic.JOINT_VERSION, r.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        assertEquals(Bs1SexChromosomePolicy.JOINT_SCOPE, r.at("/dataScope/policyId").asText());
        assertEquals(Bs1KnowledgeCatalog.JOINT_ACTIVATION, r.at("/preparation/policy/activation").asText());
        assertEquals(Bs1SexChromosomeStatistic.JOINT_VERSION,runtime.identity().path("jointSexStatisticVersion").asText());
        assertEquals(before, o); assertEquals(0, runtime.counts().path("provider").asInt()); assertEquals(0, runtime.counts().path("native").asInt());
        assertEquals("BLOCKED", Bs1Quality.assess(mapper, o, r.at("/preparation/policy"), mapper.createObjectNode()).path("status").asText());
    }

    @ParameterizedTest @CsvSource({"1999,NOT_EVALUATED", "2000,MET", "2001,MET"})
    void denominatorBoundaryNeverAddsOverlappingNativeSamples(String an, String expected) throws Exception {
        var rec = record(true); var c = context(rec); var f = fields("21", an);
        f.put("AN_exomes_nfe_XY", "1500"); f.put("AN_genomes_nfe_XY", "1500");
        var r = runtime(rec).replay(c, parent(c, f, "PASS"), SOURCE); assertEquals(expected, status(r));
        if ("1999".equals(an)) assertEquals("BS1_SEX_NO_ELIGIBLE_POPULATION", reason(r));
        else assertEquals(an, r.at("/qualityDecision/facts/AN").asText());
    }

    @ParameterizedTest @CsvSource({"AC_joint_nfe_XY,BS1_SEX_AC_INVALID_OR_MISSING", "AN_joint_nfe_XY,BS1_SEX_AN_INVALID_OR_MISSING", "AF_joint_nfe_XY,BS1_SEX_AF_INVALID_OR_MISSING"})
    void missingStratumCannotBorrowAggregateOrNative(String key, String expected) throws Exception {
        var rec = record(true); var c = context(rec); var f = fields("21", "10000"); f.remove(key);
        f.put("AC_joint_nfe", "2000"); f.put("AN_joint_nfe", "10000"); f.put("AF_joint_nfe", "0.2");
        var r = runtime(rec).replay(c, parent(c, f, "PASS"), SOURCE);
        assertEquals("NOT_EVALUATED", status(r)); assertEquals(expected, reason(r));
    }

    @ParameterizedTest @CsvSource({"0.00001,BS1_LOCAL_DISCORDANCE_REVIEW_REQUIRED", ".,BS1_DISCORDANCE_APPLICABILITY_OR_INPUT_UNKNOWN"})
    void sourceDiscordanceStillBlocksDespiteHighSexAf(String p, String code) throws Exception {
        var rec = record(true); var c = context(rec); var f = fields("21", "10000"); f.put("stat_union_p_value", p);
        var r = runtime(rec).replay(c, parent(c, f, "PASS"), SOURCE); assertEquals("NOT_EVALUATED", status(r)); assertEquals(code, reason(r));
    }
    @Test void nativeFilterConflictIsNotWashedByJointPass() throws Exception {
        var rec = record(true); var c = context(rec); var f = fields("21", "10000"); f.put("exomes_filters", "AS_VQSR");
        var r = runtime(rec).replay(c, parent(c, f, "PASS"), SOURCE);
        assertEquals("NOT_EVALUATED", status(r)); assertEquals("BS1_JOINT_SUBTYPE_FILTER_CONFLICT", reason(r));
        assertEquals("bs1-joint-sex-filter-source-v1", r.at("/qualityDecision/sourceQualityDecision/filterCondition/sourceContract/schemaVersion").asText());
    }

    @Test void fafRemainsDistinctFromAfAndRegionApplicable() throws Exception {
        var rec = record(false); var c = context(rec); var f = fields("100", "10000");
        var r = runtime(rec).replay(c, parent(c, f, "PASS"), SOURCE); assertEquals("NOT_MET", status(r));
        f.put("faf95_joint_nfe_XY", "0.003"); r = runtime(rec).replay(c, parent(c, f, "PASS"), SOURCE); assertEquals("MET", status(r));
        f.put("faf95_joint_nfe_XY", "0.02"); r = runtime(rec).replay(c, parent(c, f, "PASS"), SOURCE);
        assertEquals("BS1_SEX_FAF_INVALID_OR_MISSING", reason(r));
    }

    @ParameterizedTest @ValueSource(strings={"SCOPE", "METRIC", "REVIEW", "PRIOR_SCOPE"})
    void adoptionCannotChangeDatasetMetricOrReviewScope(String fault) throws Exception {
        var rec = record(true);
        if ("SCOPE".equals(fault)) { rec.withObject("/scope/sexChromosome").put("requiredDataset", "EXOMES"); rec.withObject("/expertPolicy/scope/sexChromosome").put("requiredDataset", "EXOMES"); qualify(rec); }
        else if ("METRIC".equals(fault)) { rec.withObject("/expertPolicy/statistic").put("metricId", Bs1SexChromosomePolicy.AF); Bs1SexChromosomeStatisticTest.qualify(rec); }
        else if ("PRIOR_SCOPE".equals(fault)) {
            rec.withObject("/qualification/priorReview").put("dataScopePolicyId", "BS1_LOCAL_EXOMES_V1");
            new Bs1KnowledgeCatalogTest().review(rec); rec.withObject("review").put("dataScopePolicyId", Bs1SexChromosomePolicy.JOINT_SCOPE);
        } else rec.withObject("/review").put("dataScopePolicyId", "BS1_LOCAL_EXOMES_V1");
        assertNotNull(Bs1Policy.issue(Bs1KnowledgeCatalog.policy(rec)));
    }
    @Test void exomesCannotSatisfyJointAndLocalEntryExplainsMissingIntegration() throws Exception {
        var rec = record(true); var c = context(rec); var shared = old.shared(c, "21");
        var exome = Bs1LocalExomes.sharedObservation(mapper, c, shared, SOURCE);
        assertThrows(IllegalArgumentException.class, () -> runtime(rec).replay(c, exome, SOURCE));
        var bytes = mapper.writeValueAsBytes(old.bundle(rec)); var p = temporary.resolve("joint-local.json"); Files.write(p, bytes);
        var catalog = Bs1KnowledgeCatalog.load(p, Bs1KnowledgeCatalog.sha(bytes));
        var local = Bs1Runtime.localExomes(mapper, new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties(), true, catalog);
        var r = local.evaluateLocal(c, shared, SOURCE); assertEquals("BS1_SEX_JOINT_REQUIRES_JOINT_OBSERVATION", reason(r));
        assertEquals(0, local.counts().path("provider").asInt());
    }
    @Test void persistenceAndReadbackReverifyRawAndAllBindings() throws Exception {
        var rec = record(true); var c = context(rec); var o = parent(c, fields("21", "10000"), "PASS"); var r = runtime(rec).replay(c, o, SOURCE);
        var data = mapper.createObjectNode(); data.set("record",rec); data.set("context",c); data.set("observation",o); data.set("result",r);
        var file = temporary.resolve("full-module.json"); var bytes = mapper.writeValueAsBytes(data); Files.write(file,bytes); var restored=mapper.readTree(Files.readAllBytes(file));
        var replay = runtime((ObjectNode)restored.path("record")).replay(restored.path("context"),restored.path("observation"),SOURCE);
        assertEquals(r,restored.path("result")); assertEquals(r,replay); assertArrayEquals(bytes,Files.readAllBytes(file));
        var output=temporary.resolve("fresh-jvm-result.json"); var log=temporary.resolve("fresh-jvm.log");
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var process=new ProcessBuilder(javaExecutable,"-cp",classpath,FreshJvmReplay.class.getName(),file.toString(),output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Isolated module replay JVM timed out"); }
        assertEquals(0,process.exitValue(),Files.readString(log)); assertEquals(r,mapper.readTree(Files.readAllBytes(output)));
        assertArrayEquals(bytes,Files.readAllBytes(file));
        GnomadSexStratifiedObservation.verifyJoint(mapper,replay.at("/qualityDecision/sexStratifiedObservation"),o,c,SOURCE,GnomadSexStratifiedObservation.loadPinnedJointHeader());
        for (String key : List.of("rawRecord", "sourceSnapshot", "profileSha256")) {
            var corrupt=o.deepCopy(); corrupt.put(key,"b".repeat(64));
            assertThrows(IllegalArgumentException.class,()->runtime(rec).replay(c,corrupt,SOURCE));
        }
    }
    @Test @EnabledIfSystemProperty(named="varvis.a10.joint.real.pending", matches=".+")
    void actualPythonQualificationPreservesUnresolvedMetricWithoutCallingItVersionFailure() throws Exception {
        var file=Path.of(System.getProperty("varvis.a10.joint.real.pending")); var bytes=Files.readAllBytes(file);
        assertEquals("9525b991ece93e729bf8171d2bc44bac78c645e5b5216d2d3166de5925c4a6e1",Bs1KnowledgeCatalog.sha(bytes));
        var catalog=Bs1KnowledgeCatalog.load(file,Bs1KnowledgeCatalog.sha(bytes));
        var rec=(ObjectNode)mapper.readTree(bytes).path("records").path(0); var c=context(rec);
        assertEquals("HGNC:11055",rec.at("/scope/hgncId").asText());
        assertEquals("MONDO:0010305",rec.at("/scope/mondoId").asText());
        assertEquals("X_LINKED_RECESSIVE",rec.at("/scope/modes/0").asText());
        var selection=Bs1Policy.select(mapper,catalog.merge(List.of()),c);
        assertEquals("BS1_QUALIFICATION_POPULATION_METRIC_UNRESOLVED",selection.path("reasonCode").asText());
        assertEquals("BS1_JOINT_SEX_V1",rec.at("/qualification/priorReview/dataScopePolicyId").asText());
    }

    @Test @EnabledIfSystemProperty(named="varvis.a10.joint.real.bundle", matches=".+")
    void actualReleasedRuleIndependentMoiAndPublisherJointRecordReplay() throws Exception {
        var file=Path.of(System.getProperty("varvis.a10.joint.real.bundle")); var bytes=Files.readAllBytes(file);
        String bundleSha=System.getProperty("varvis.a10.joint.real.sha"); assertNotNull(bundleSha);
        assertEquals(bundleSha,Bs1KnowledgeCatalog.sha(bytes));
        var catalog=Bs1KnowledgeCatalog.load(file,bundleSha); var document=mapper.readTree(bytes);
        assertEquals(1,document.path("records").size()); var rec=(ObjectNode)document.path("records").path(0);
        assertEquals("HGNC:11055",rec.at("/scope/hgncId").asText()); assertEquals("MONDO:0010305",rec.at("/scope/mondoId").asText());
        assertEquals("X_LINKED_RECESSIVE",rec.at("/scope/modes/0").asText());
        assertEquals("gnomad.joint.sex_ancestry_max_faf95",rec.at("/expertPolicy/statistic/metricId").asText());
        assertEquals("ALL",rec.at("/expertPolicy/statistic/karyotypes/0").asText());
        assertEquals("0.0002",rec.at("/expertPolicy/tiers/0/conditions/0/decimal").asText());
        assertEquals("GTE",rec.at("/expertPolicy/tiers/0/conditions/0/operator").asText());
        var rawBytes=Files.readAllBytes(Path.of(System.getProperty("varvis.a10.gnomad.joint.record")));
        assertEquals("74bd94f674f76ab931d187e46db06e41c5c4003e47d3cf83f96fa6d9f9441e76",Bs1KnowledgeCatalog.sha(rawBytes));
        String raw=new String(rawBytes,java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(raw.endsWith("\n")); raw=raw.substring(0,raw.length()-1); assertFalse(raw.contains("\n"));
        String[] columns=raw.split("\t",-1); var fields=new LinkedHashMap<String,String>();
        for(String field:columns[7].split(";")) { int eq=field.indexOf('='); fields.put(eq<0?field:field.substring(0,eq),eq<0?"":field.substring(eq+1)); }
        var c=context(rec); var allele=c.withObject("/variant/evidenceQueryAllele");
        allele.put("position",153691089).put("reference","G").put("alternate","A"); old.refresh(c);
        var carrier=source.carrier(c); carrier.set("measurements",mapper.valueToTree(source.profile.parse(columns[6],fields,0,1).getMeasurements()));
        carrier.put("sourcePayloadSha256",Bs1KnowledgeCatalog.sha(rawBytes)).put("producedAt","2026-11-03T12:29:27Z");
        carrier.withObject("source").put("retrievedAt","2026-11-03T12:29:27Z");
        String snapshot=Bs1KnowledgeCatalog.sha(rawBytes);
        var o=PopulationObservation.seal(mapper,carrier,raw,source.profile,snapshot,"SYNTHETIC","REAL_PUBLIC_ROW_SYNTHETIC_CONTEXT_TECHNICAL_REPLAY");
        var transport=mapper.createObjectNode().put("querySource","remote-joint-vcf-population-input").put("sourceDataset","joint")
                .put("gnomadVersion",source.profile.getDocument().at("/resources/0/resourceVersion").asText());
        String uri=Bs1JointFilterSource.sexContract(mapper).at("/headers/X/url").asText();
        transport.put("resourceUri",uri).put("resourceIndexUri",uri+".tbi"); PopulationObservation.withTransport(mapper,o,transport);
        var runtime=new Bs1Runtime(mapper,catalog.merge(List.of()),mock(VariantEvidenceProvider.class),null);
        var result=runtime.replay(c,o,snapshot);
        assertEquals("NOT_MET",status(result),result.path("evaluation").toString());
        assertEquals("READY",result.at("/qualityDecision/status").asText());
        assertEquals("nfe",result.at("/qualityDecision/facts/ancestry").asText()); assertEquals("ALL",result.at("/qualityDecision/facts/karyotype").asText());
        assertEquals(0,new BigDecimal("9.74000e-06").compareTo(new BigDecimal(result.at("/qualityDecision/facts/FAF95").asText())));
        assertEquals("246676",result.at("/qualityDecision/facts/AN").asText()); assertEquals("6",result.at("/qualityDecision/facts/AC").asText());
        assertEquals(0,runtime.counts().path("provider").asInt()); assertEquals(0,runtime.counts().path("native").asInt());
        var restored=mapper.readTree(mapper.writeValueAsBytes(result));
        assertEquals(result,runtime.replay(restored.path("context"),restored.path("observation"),snapshot));
        String output=System.getProperty("varvis.a10.joint.real.output");
        if(output!=null) Files.write(Path.of(output),mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
    }

    /** Isolated JSON module readback; this is not the production store/history/export integration. */
    public static class FreshJvmReplay {
        public static void main(String[] args) throws Exception {
            var mapper=new ObjectMapper(); var saved=mapper.readTree(Files.readAllBytes(Path.of(args[0])));
            var policy=Bs1KnowledgeCatalog.policy(saved.path("record"));
            var runtime=new Bs1Runtime(mapper,List.of(policy),mock(VariantEvidenceProvider.class),null);
            var result=runtime.replay(saved.path("context"),saved.path("observation"),SOURCE);
            if(runtime.counts().path("provider").asInt()!=0) throw new IllegalStateException("Unexpected provider query");
            Files.write(Path.of(args[1]),mapper.writeValueAsBytes(result));
        }
    }

}
