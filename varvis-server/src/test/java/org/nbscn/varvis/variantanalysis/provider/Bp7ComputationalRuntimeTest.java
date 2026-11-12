package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks.CatalogSnapshot;
import org.springframework.core.io.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic model/reference/context tests plus unchanged public catalog matching; no clinical adoption. */
class Bp7ComputationalRuntimeTest {
    @TempDir Path directory;
    final Bp7PredictionDependencyTest prediction = new Bp7PredictionDependencyTest();
    final Bp7PositionAssessmentTest position = new Bp7PositionAssessmentTest();
    final com.fasterxml.jackson.databind.ObjectMapper mapper = prediction.mapper;
    final RuntimeEvidenceV2Adapter identities = prediction.adapter;
    final Bp7ComputationalRuntime runtime = new Bp7ComputationalRuntime(mapper);
    final Resource publicCatalog = new ClassPathResource("acmg/public-splicing-experiments-v1.json");

    SplicingExperimentChecks source(Resource resource) {
        return new SplicingExperimentChecks(mapper, resource, directory.resolve("unused-store"),
                (a, r) -> { throw new AssertionError("Catalog capture must not read saved business runs"); });
    }
    ObjectNode document() throws IOException { try (var in = publicCatalog.getInputStream()) { return (ObjectNode) mapper.readTree(in); } }
    CatalogSnapshot capture(ObjectNode document) throws IOException {
        return source(new ByteArrayResource(requireNonNull(mapper.writeValueAsBytes(document)))).captureCatalog();
    }
    void sealDocument(ObjectNode document) {
        var service = source(publicCatalog);
        for (var value : document.path("records")) { var r = (ObjectNode)value; r.remove("contentSha256"); r.put("contentSha256", service.hash(r)); }
        document.remove("contentSha256"); document.put("contentSha256", service.hash(document));
    }
    Bp7PredictionDependencyTest.Saved saved(String score, String consequence) throws Exception {
        prediction.directory = directory;
        var s = prediction.saved(score, consequence, false);
        var nativePlan = (ObjectNode)s.plan().path("nativeSplicePreparation");
        ((ObjectNode)nativePlan.path("identity")).set("gencode", position.gencodeIdentity());
        var model = (ObjectNode)nativePlan.path("annotation");
        model.put("assembly", "GRCh38").put("hgncId", s.plan().at("/context/gene/hgncId").asText()).put("rawGtfSha256", "c".repeat(64)); position.coding(model);
        var refs = (ObjectNode)s.plan().path("effectRestrictions");
        refs.put("coverage", "COMPLETE_FOR_DECLARED_SCOPE").put("externalDeclaration", "UNSPECIFIED").put("externalWorldCovered", false);
        var scope = refs.putObject("scope").put("candidateId", s.plan().path("candidateId").asText());
        scope.set("contextId", s.plan().at("/context/contextId")); scope.set("queryAllele", s.plan().path("queryAllele").deepCopy());
        reseal(s); return s;
    }
    void reseal(Bp7PredictionDependencyTest.Saved s) {
        s.row().set("plan", s.plan().deepCopy());
        ((ObjectNode)s.row().path("nativeSpliceResult")).set("plan", s.plan().path("nativeSplicePreparation").deepCopy());
        s.row().set("criteria", PredictionMechanismApplication.apply(mapper, s.plan(), s.row().path("mechanisms"), null));
        prediction.seal(s.row());
    }
    ObjectNode evaluate(Bp7PredictionDependencyTest.Saved s, CatalogSnapshot catalog) { return runtime.evaluate(s.plan(), s.candidate(), s.row(), catalog); }
    void status(ObjectNode out, String state, String reason) {
        assertEquals(state, out.path("status").asText(), out.toPrettyString());
        assertEquals(reason, out.path("reasonCode").asText(), out.toPrettyString());
        assertEquals("MET".equals(state) ? "SUPPORTING" : "NONE", out.path("appliedStrength").asText());
        assertEquals("MET".equals(state), out.path("automaticComputationalApplication").asBoolean());
        assertFalse(out.path("clinicalAdoption").asBoolean()); assertFalse(out.path("finalClassificationEnabled").asBoolean());
        assertEquals(0, out.path("providerCalls").asInt());
    }
    void evidence(String name, ObjectNode result) throws IOException {
        String folder = System.getProperty("bp7.adoption.evidence.dir");
        if (folder != null) Files.write(Path.of(folder).resolve(name + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
    }

    @ParameterizedTest @ValueSource(strings={"synonymous_variant", "intron_variant"})
    void finalComputationalSupportDoesNotClaimAnUnrecordedExperimentWasNormal(String consequence) throws Exception {
        var s = saved("0.0", consequence); var catalog = source(publicCatalog).captureCatalog();
        var out = evaluate(s, catalog); status(out, "MET", "BP7_LOCAL_COMPUTATIONAL_REQUIREMENTS_MET");
        assertEquals("NO_MATCH_IN_DECLARED_CATALOG", out.at("/experimentalConflictCheck/status").asText());
        assertFalse(out.at("/experimentalConflictCheck/experimentalNormalityEstablished").asBoolean());
        assertFalse(out.at("/experimentalConflictCheck/externalWorldCovered").asBoolean());
        assertFalse(out.path("reviewRequired").asBoolean());
        assertEquals("CORE_RESULT_ONLY", out.path("stage").asText());
        assertEquals(s.row().path("resultId"), out.at("/sharedPrediction/selectedPredictionResultId"));
        assertEquals(1, s.calls().get()); evidence("met-"+consequence, out);
    }
    @ParameterizedTest @CsvSource({"0.15,BP7_SPLICE_SCORE_UNINFORMATIVE", "0.2,BP7_SPLICE_NOT_BENIGN"})
    void nonBenignOrUninformativePredictionNeverBecomesMet(String score, String reason) throws Exception {
        var s = saved(score,"synonymous_variant"); var out = evaluate(s, source(publicCatalog).captureCatalog());
        status(out,"NOT_MET",reason); assertEquals(1,s.calls().get()); evidence("not-met-"+score,out);
    }
    @Test void excludedLocationAndMissingOrForgedPredictionStayDistinct() throws Exception {
        var s = saved("0.0","synonymous_variant"); var catalog = source(publicCatalog).captureCatalog();
        status(runtime.evaluate(s.plan(),s.candidate(),null,catalog),"NOT_EVALUATED","BP7_SAVED_PREDICTION_REQUIRED");
        s.row().put("resultId","forged"); status(evaluate(s,catalog),"NOT_EVALUATED","BP7_SAVED_PREDICTION_IDENTITY_INVALID");
        var i=position.input(1,"synonymous_variant",20000,new int[][]{{20000,20020}});
        status(runtime.evaluate(i.plan(),i.candidate(),null,catalog),"NOT_APPLICABLE","BP7_POSITION_SPLICE_PROXIMAL");
        status(runtime.evaluate(null,null,null,null),"NOT_EVALUATED","BP7_PREPARED_CONTEXT_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","IO","EXISTS_ERROR","CORRUPT","DUPLICATE","TRAILING","OVERSIZE","FLOAT"})
    void unreadableCatalogNeverMeansNoMatchingExperiment(String change) throws Exception {
        var doc=document(); Resource resource;
        switch(change) {
            case "MISSING" -> resource=new FileSystemResource(requireNonNull(directory.resolve("missing.json")));
            case "IO" -> resource=new ByteArrayResource(new byte[0]) { @Override @org.springframework.lang.NonNull public InputStream getInputStream() throws IOException { throw new IOException("fixture"); } };
            case "EXISTS_ERROR" -> resource=new ByteArrayResource(new byte[0]) { @Override public boolean exists() { throw new IllegalStateException("fixture"); } };
            case "CORRUPT" -> { doc.put("contentSha256","0".repeat(64)); resource=new ByteArrayResource(requireNonNull(mapper.writeValueAsBytes(doc))); }
            case "DUPLICATE" -> resource=new ByteArrayResource(requireNonNull("{\"schemaVersion\":\"x\",\"schemaVersion\":\"y\"}".getBytes(StandardCharsets.UTF_8)));
            case "TRAILING" -> resource=new ByteArrayResource(requireNonNull((mapper.writeValueAsString(doc)+" {}").getBytes(StandardCharsets.UTF_8)));
            case "OVERSIZE" -> resource=new ByteArrayResource(new byte[1_048_577]);
            case "FLOAT" -> { doc.put("unsupportedNumber",0.1); sealDocument(doc); resource=new ByteArrayResource(requireNonNull(mapper.writeValueAsBytes(doc))); }
            default -> throw new AssertionError(change);
        }
        var s=saved("0.0","synonymous_variant"); var out=evaluate(s,source(resource).captureCatalog());
        status(out,"NOT_EVALUATED","BP7_EXPERIMENT_SOURCE_UNAVAILABLE"); assertEquals("UNAVAILABLE",out.at("/experimentalConflictCheck/status").asText());
        assertFalse(Files.exists(directory.resolve("unused-store"))); evidence("source-"+change,out);
    }

    ObjectNode planForPublicRecord(ObjectNode record) throws Exception {
        var c=prediction.fixture.input().context().deepCopy(); var allele=record.path("allele");
        for(String key:List.of("inputAllele","normalizedAllele","evidenceQueryAllele")) ((ObjectNode)c.path("variant")).set(key,allele.deepCopy());
        ((ObjectNode)c.path("variant")).put("alleleId",identities.canonicalAlleleId(allele,c.at("/variant/normalization")));
        c.putObject("gene").put("hgncId",record.path("geneId").asText()).put("symbol",record.path("geneSymbol").asText());
        String tx=record.at("/transcriptMapping/ensembl").asText();
        ((ObjectNode)c.path("transcript")).put("accession",tx.substring(0,tx.indexOf('.'))).put("version",Integer.parseInt(tx.substring(tx.indexOf('.')+1)));
        c.put("contextId",identities.canonicalContextId(c));
        var plan=mapper.createObjectNode(); plan.set("context",c); plan.set("queryAllele",allele.deepCopy()); return plan;
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void originalLmnaAndCftrRecordsRequireReviewWithoutBorrowingDiseaseCausality(int index) throws Exception {
        var doc=document(); byte[] original=mapper.writeValueAsBytes(doc);
        var record=(ObjectNode)doc.path("records").get(index); var plan=planForPublicRecord(record);
        var result=runtime.assessExperiments(plan,source(publicCatalog).captureCatalog());
        assertEquals("REVIEW_REQUIRED",result.path("status").asText(),result.toPrettyString());
        assertEquals("BP7_RECORDED_ALTERED_SPLICING_REVIEW_REQUIRED",result.path("reasonCode").asText());
        assertEquals("EXACT_ALLELE_GENE_TRANSCRIPT",result.at("/matches/0/molecularMatch").asText());
        assertEquals(record,result.at("/matches/0/record")); assertEquals("NOT_ASSESSED_NO_CLINICAL_INFERENCE",result.path("diseaseApplicability").asText());
        assertArrayEquals(original,mapper.writeValueAsBytes(document())); evidence("public-record-"+index,result);
    }
    @ParameterizedTest @ValueSource(strings={"TX","GENE","SYMBOL"})
    void sameAlleleWithUncertainApplicabilityIsNotSilentlyAbsent(String change) throws Exception {
        var plan=planForPublicRecord((ObjectNode)document().at("/records/0")); var context=(ObjectNode)plan.path("context");
        switch(change) {
            case "TX" -> ((ObjectNode)context.path("transcript")).put("version",999);
            case "GENE" -> ((ObjectNode)context.path("gene")).put("hgncId","HGNC:99999");
            case "SYMBOL" -> ((ObjectNode)context.path("gene")).put("symbol","OTHER");
        }
        context.put("contextId",identities.canonicalContextId(context));
        var result=runtime.assessExperiments(plan,source(publicCatalog).captureCatalog());
        assertEquals("REVIEW_REQUIRED",result.path("status").asText());
        assertEquals("BP7_EXPERIMENT_TRANSCRIPT_OR_GENE_UNRESOLVED",result.path("reasonCode").asText());
    }
    ObjectNode syntheticMatchingCatalog(Bp7PredictionDependencyTest.Saved s) throws Exception {
        var doc=document(); var record=((ObjectNode)doc.at("/records/0")).deepCopy();
        doc.put("catalogId","SYNTHETIC-TEST-ONLY"); doc.putArray("records").add(record);
        record.put("recordId","synthetic-altered-splice").put("geneId",s.plan().at("/context/gene/hgncId").asText())
                .put("geneSymbol",s.plan().at("/context/gene/symbol").asText()).put("summary","SYNTHETIC TEST, NOT AN OBSERVED EXPERIMENT");
        record.set("allele",s.plan().path("queryAllele").deepCopy());
        String tx=s.candidate().getTranscriptAnnotations().get(0).getVersionedTranscriptId(); var mapping=(ObjectNode)record.path("transcriptMapping"); mapping.put("ensembl",tx);
        var row=mapping.path("sourceRow").asText().split("\t",-1); row[2]=record.path("geneId").asText(); row[3]=record.path("geneSymbol").asText(); row[7]=tx;
        mapping.put("sourceRow",String.join("\t",row)); sealDocument(doc); return doc;
    }
    @Test void lowScoreAndGoodPositionCannotOverruleAnUnreviewedAlteredSplicingRecord() throws Exception {
        var s=saved("0.0","synonymous_variant"); var out=evaluate(s,capture(syntheticMatchingCatalog(s)));
        status(out,"NOT_EVALUATED","BP7_RECORDED_ALTERED_SPLICING_REVIEW_REQUIRED");
        assertEquals("READY_FOR_EXPERIMENTAL_CONFLICT_CHECK",out.at("/computationalChecks/status").asText());
        assertTrue(out.path("reviewRequired").asBoolean()); assertEquals(1,s.calls().get()); evidence("synthetic-conflict",out);
    }
    @ParameterizedTest @CsvSource({"SPLICING,OBSERVED_NOT_ADOPTED,ALLOW,NOT_EVALUATED", "SPLICING,EXPLICITLY_ADOPTED,BLOCK_DUPLICATE,NOT_EVALUATED",
            "UNKNOWN,OBSERVED_NOT_ADOPTED,ALLOW,NOT_EVALUATED", "PROTEIN_MISSENSE,OBSERVED_NOT_ADOPTED,ALLOW,MET", "PROTEIN_MISSENSE,EXPLICITLY_ADOPTED,BLOCK_DUPLICATE,MET"})
    void functionalRecordsUseExistingUsageAndMechanismInsteadOfAnApprovalFlag(String mechanism,String usage,String action,String expected) throws Exception {
        var s=saved("0.0","synonymous_variant");
        ((ObjectNode)s.plan().path("effectRestrictions")).withArray("references").addObject().put("criterionId","ACMG_AMP_2015:PP3")
                .put("mechanism",mechanism).put("usage",usage).put("action",action).put("savedEvidenceId","synthetic-evidence").put("effectId","synthetic-effect");
        reseal(s); var out=evaluate(s,source(publicCatalog).captureCatalog());
        status(out,expected,"MET".equals(expected)?"BP7_LOCAL_COMPUTATIONAL_REQUIREMENTS_MET":("SPLICING".equals(mechanism)?"BP7_SPLICE_EXPERIMENT_REVIEW_REQUIRED":"BP7_EXPERIMENT_MECHANISM_UNRESOLVED"));
        assertEquals(1,s.calls().get());
    }
    @ParameterizedTest @ValueSource(strings={"COVERAGE","EXTERNAL","SCOPE","ARRAY","ACTION","FLOAT"})
    void incompleteOrDamagedFunctionalReferencesCannotBecomeAnEmptyCleanScope(String change) throws Exception {
        var s=saved("0.0","synonymous_variant"); var refs=(ObjectNode)s.plan().path("effectRestrictions");
        switch(change) {
            case "COVERAGE" -> refs.remove("coverage");
            case "EXTERNAL" -> refs.put("externalDeclaration","REFERENCED");
            case "SCOPE" -> ((ObjectNode)refs.path("scope")).put("candidateId","other");
            case "ARRAY" -> refs.putNull("references");
            case "ACTION" -> refs.withArray("references").addObject().put("mechanism","SPLICING").put("action","APPROVED");
            case "FLOAT" -> refs.put("invalid",0.1);
        }
        if (!"FLOAT".equals(change)) reseal(s);
        var out=evaluate(s,source(publicCatalog).captureCatalog()); assertEquals("NOT_EVALUATED",out.path("status").asText());
        assertEquals("NONE",out.path("appliedStrength").asText()); assertEquals(1,s.calls().get());
    }
    @Test void preparedPlanAndCatalogAreIndependentTrustBoundariesWithDefensiveSnapshots() throws Exception {
        var s=saved("0.0","synonymous_variant"); var count=new AtomicInteger();
        byte[] bytes; try(var in=publicCatalog.getInputStream()){ bytes=in.readAllBytes(); }
        var resource=new ByteArrayResource(requireNonNull(bytes)){ @Override @org.springframework.lang.NonNull public InputStream getInputStream() throws IOException { count.incrementAndGet(); return super.getInputStream(); }};
        var snapshot=source(resource).captureCatalog(); var before=snapshot.content();
        snapshot.content().withObject("/material").put("status","INVALID"); assertEquals(before,snapshot.content());
        byte[] originalPlan=mapper.writeValueAsBytes(s.plan()), originalRow=mapper.writeValueAsBytes(s.row());
        var result=evaluate(s,snapshot); byte[] serialized=mapper.writeValueAsBytes(result);
        result.put("status","FORGED"); ((ObjectNode)result.path("policy")).put("version","FORGED");
        assertArrayEquals(serialized,mapper.writeValueAsBytes(evaluate(s,snapshot)));
        assertArrayEquals(serialized,mapper.writeValueAsBytes(runtime.evaluate((ObjectNode)mapper.readTree(originalPlan),s.candidate(),(ObjectNode)mapper.readTree(originalRow),snapshot)));
        assertArrayEquals(originalPlan,mapper.writeValueAsBytes(s.plan())); assertArrayEquals(originalRow,mapper.writeValueAsBytes(s.row()));
        assertEquals(1,count.get()); assertEquals(1,s.calls().get());
        var changed=document(); changed.put("version","1.0.1"); sealDocument(changed);
        assertNotEquals(evaluate(s,snapshot).path("resultId"),evaluate(s,capture(changed)).path("resultId"));
        assertFalse(Files.exists(directory.resolve("unused-store")));
    }
    @Test void noSnapshotOrInvalidContextCannotCreateMetOrAnUncaughtIdentityFailure() throws Exception {
        var s=saved("0.0","synonymous_variant"); status(evaluate(s,null),"NOT_EVALUATED","BP7_EXPERIMENT_SOURCE_UNAVAILABLE");
        ((ObjectNode)s.plan().at("/context/variant/evidenceQueryAllele")).put("position",1.5);
        var out=evaluate(s,source(publicCatalog).captureCatalog()); assertEquals("NOT_EVALUATED",out.path("status").asText());
        assertFalse(out.path("contextCaptured").asBoolean());
    }
    @Test void alreadyAdoptedSpliceBp4CannotBeBypassedByTheNewBp7Layer() throws Exception {
        var s=saved("0.0","synonymous_variant");
        ((ObjectNode)s.plan().path("effectRestrictions")).withArray("references").addObject().put("criterionId","ACMG_AMP_2015:BP4")
                .put("mechanism","SPLICING").put("usage","EXPLICITLY_ADOPTED").put("action","BLOCK_DUPLICATE")
                .put("savedEvidenceId","synthetic-adopted-bs3").put("effectId","synthetic-splice-effect");
        reseal(s); var out=evaluate(s,source(publicCatalog).captureCatalog());
        status(out,"NOT_EVALUATED","BP7_BP4_NOT_EVALUATED");
        assertEquals("SAME_EFFECT_ALREADY_ADOPTED",out.at("/computationalChecks/predictionDependency/dependencyReasonCode").asText());
        assertTrue(out.path("reviewRequired").asBoolean()); assertEquals(1,s.calls().get());
    }
    @Test void neighboringAlleleCannotMatchThePublicExperiment() throws Exception {
        var plan=planForPublicRecord((ObjectNode)document().at("/records/0")); var context=(ObjectNode)plan.path("context");
        var allele=((ObjectNode)plan.path("queryAllele")).deepCopy(); allele.put("position",allele.path("position").asLong()+1);
        for(String key:List.of("inputAllele","normalizedAllele","evidenceQueryAllele")) ((ObjectNode)context.path("variant")).set(key,allele.deepCopy());
        ((ObjectNode)context.path("variant")).put("alleleId",identities.canonicalAlleleId(allele,context.at("/variant/normalization")));
        context.put("contextId",identities.canonicalContextId(context)); plan.set("queryAllele",allele);
        var out=runtime.assessExperiments(plan,source(publicCatalog).captureCatalog());
        assertEquals("NO_MATCH_IN_DECLARED_CATALOG",out.path("status").asText()); assertTrue(out.path("matches").isEmpty());
    }
}
