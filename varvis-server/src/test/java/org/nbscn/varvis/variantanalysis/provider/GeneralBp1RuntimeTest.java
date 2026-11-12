package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks;
import org.springframework.core.io.ByteArrayResource;

/** Synthetic exact sequence and source grammar; no clinical adoption or native scientific run. */
class GeneralBp1RuntimeTest {
    @TempDir Path root;
    final CodingTranscriptMaterialSupplyTest fixture = new CodingTranscriptMaterialSupplyTest();
    final com.fasterxml.jackson.databind.ObjectMapper mapper = fixture.base.mapper;
    final RuntimeEvidenceV2Adapter ids = fixture.base.adapter;
    final AtomicInteger calls = new AtomicInteger();
    CodingTranscriptMaterialSupply supply;
    ObjectNode doc, rawIdentity = mapper.createObjectNode().put("testOnly", true), lastResult;
    SplicingExperimentChecks.CatalogSnapshot catalog;
    Consumer<ObjectNode> rawChange = n -> { }, effectChange = n -> { };
    String score = "0.1";
    boolean rawMissing, effectsMissing;
    void setup() throws Exception {
        fixture.root = root; supply = fixture.setup(); fixture.model.put("geneId", "ENSG000001"); supply = fixture.writeAndLoad();
        var context = fixture.input.fixture().context(); context.put("contextId", ids.canonicalContextId(context));
        doc = new PublishedCriterionKnowledgeTest().document();
        var original = (ObjectNode) doc.at("/specifications/0/original"); original.withObject("/ld").remove("File");
        var hgnc = (ObjectNode) original.at("/ld/Gene/0/entContent/HGNC"); hgnc.put("symbol", "SYNTH1").put("hgnc_id", "HGNC:1");
        var gene = (ObjectNode) original.at("/ld/RuleSet/0/entContent/genes/0"); gene.put("gene", "SYNTH1").put("preferredTranscript", "ENST000001.4");
        ((ObjectNode)gene.at("/diseases/0")).put("preferredModeOfInheritance", "AUTOSOMAL_RECESSIVE".equals(context.path("moi").asText())
                ? "Autosomal recessive inheritance" : "Autosomal dominant inheritance");
        var clause = (ObjectNode) original.at("/ld/CriteriaCode/0/entContent"); clause.removeAll();
        clause.put("label", "BP1").put("applicability", "Applicable").put("baseStrength", "Benign").put("defaultStrength", "Benign Supporting")
                .put("instructionsToUse", Bp1PublishedPolicy.instructions("SYNTH1")).putNull("additionalComments"); clause.putArray("gene").add("SYNTH1");
        clause.putArray("strengthDescriptor").addObject().put("applicability", "Applicable").put("strength", "Supporting")
                .put("text", "\u00a0Apply to all missense variants.").put("instructionsToUse", "");
        doc.set("bp1FunctionalScreen", Bp1PublishedFunctionScreenTest.empty(mapper));
        var record = (ObjectNode) doc.at("/records/0"); record.put("criterionId", GeneralBp1Runtime.CRITERION);
        var scope = (ObjectNode) record.path("scope"); scope.put("hgncId", "HGNC:1").put("transcript", "ENST000001.4").set("moi", context.path("moi"));
        record.putObject("interpretation").put("type", Bp1PublishedPolicy.TYPE).put("version", "1.0.0").put("projectRestriction", Bp1PublishedPolicy.RESTRICTION);
        var cat = mapper.createObjectNode().put("schemaVersion", "public-splicing-experiments-v1").put("catalogId", "synthetic-only")
                .put("version", "1").put("coverage", "EMPTY_SYNTHETIC_CATALOG").put("updateMethod", "TEST_ONLY");
        cat.putArray("records"); cat.put("contentSha256", new SplicingExperimentChecks(mapper, new ByteArrayResource(new byte[0]), root, (a,b) -> null).hash(cat));
        catalog = new SplicingExperimentChecks(mapper, new ByteArrayResource(requireNonNull(mapper.writeValueAsBytes(cat))), root, (a,b) -> null).captureCatalog();
    }
    PublishedCriterionKnowledge knowledge() {
        new PublishedCriterionKnowledgeTest().refresh(doc);
        return new PublishedCriterionKnowledge(mapper, doc, ids.canonicalSha256(doc));
    }
    ObjectNode effects(JsonNode plan) {
        var out = mapper.createObjectNode().put("status", "VERIFIED_SAVED_REFERENCES").put("coverage", "COMPLETE_FOR_DECLARED_SCOPE")
                .put("restriction", "NO_ADOPTED_OVERLAP_IN_DECLARED_SCOPE").put("externalDeclaration", "UNSPECIFIED")
                .put("externalWorldCovered", false).put("sourceSha256", "b".repeat(64));
        out.set("contextId", plan.at("/context/contextId")); out.putObject("snapshotIdentity").put("sha256", "b".repeat(64));
        var scope = out.putObject("scope"); scope.set("candidateId", plan.path("candidateId")); scope.set("contextId", plan.at("/context/contextId"));
        scope.set("queryAllele", plan.path("queryAllele")); out.putArray("references"); effectChange.accept(out); return out;
    }
    ObjectNode raw(ObjectNode context, JsonNode model, String queryId) {
        calls.incrementAndGet(); var batch = mapper.createObjectNode().put("resultSchemaVersion", "spliceai-raw-batch-result-v1")
                .put("nativeResourceSha256", ids.canonicalSha256(rawIdentity)); batch.set("nativeIdentity", rawIdentity.deepCopy());
        var fact = batch.putArray("results").addObject().put("queryId", queryId).put("status", "HIT").put("exactAlleleMatch", true)
                .put("annotationFileSha256", "a".repeat(64)); fact.set("allele", context.at("/variant/evidenceQueryAllele").deepCopy());
        var selected = NativeSpliceAiRuntime.selectedAnnotation(mapper, model); fact.set("selectedAnnotation", selected);
        var row = fact.putArray("annotationRows").addObject().put("geneSymbol", "SYNTH1").put("contig", "1").put("strand", "+").put("start", 96).put("end", 104);
        row.set("exons", selected.path("exons"));
        var scores = fact.putArray("annotations").addObject().put("geneSymbol", "SYNTH1").put("allele", "G").putObject("deltaScores");
        for (String f : java.util.List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) scores.put(f, score);
        rawChange.accept(batch); return batch;
    }
    GeneralBp1Runtime runtime() {
        return new GeneralBp1Runtime(mapper, supply, knowledge(), rawMissing ? null : (c,m,q) ->
                SpliceAiRawBatchRunner.BatchResult.succeeded(raw(c,m,q.get(0).getQueryId()),0), rawIdentity, catalog,
                effectsMissing ? null : this::effects, "b".repeat(64));
    }
    ObjectNode run() { lastResult = runtime().evaluate(fixture.input.fixture().context(), fixture.input.fixture().candidate(), mapper.createObjectNode().put("candidateId", "synthetic")); return lastResult; }
    @Test void realEmptyLocalInventorySuppliesBp1WithoutInventingExperimentalEvidence() throws Exception {
        setup();
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new org.springframework.jdbc.core.JdbcTemplate(ds).execute("CREATE TABLE variant_analysis_job (id CHAR(36) PRIMARY KEY)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/acmg-business-schema.sql")).execute(ds);
        var store=new org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore(ds);
        var snapshot=new org.nbscn.varvis.variantanalysis.service.PredictionEffectReferences(mapper,store,root.resolve("absent-reviews"),
                (a,r)->{fail("No functional run to read");return null;}).capture("a",java.util.Set.of("synthetic"));
        var runtime=new GeneralBp1Runtime(mapper,supply,knowledge(),(c,m,q)->
                SpliceAiRawBatchRunner.BatchResult.succeeded(raw(c,m,q.get(0).getQueryId()),0),rawIdentity,catalog,snapshot::resolve,snapshot.identity());
        var result=runtime.evaluate(fixture.input.fixture().context(),fixture.input.fixture().candidate(),mapper.createObjectNode().put("candidateId","synthetic"));
        assertEquals("MET",result.path("status").asText(),result.toPrettyString());
        assertFalse(java.nio.file.Files.exists(root.resolve("absent-reviews")));
        assertFalse(result.at("/publicSummary/experimentalNormalityEstablished").asBoolean(true));
        assertEquals(snapshot.identity(),result.at("/policy/effectSourceSha256").asText());
    }
    @Test void exactSyntheticScopeProducesSupportingWithoutReviewOrNormalRnaClaim() throws Exception {
        setup(); var result = run(); assertEquals("MET", result.path("status").asText(), result.toPrettyString());
        directArtifact("legacy-missense-MET-direct", result, fixture.input.fixture().context());
        assertEquals("SUPPORTING",result.at("/evaluation/appliedStrength").asText()); assertEquals(1,calls.get());
        assertFalse(result.at("/publicSummary/experimentalNormalityEstablished").asBoolean(true));
        assertFalse(result.at("/policy/createsReviewReceipt").asBoolean(true));
        assertFalse(result.at("/projectSpliceCheck/expertBp1Threshold").asBoolean(true));
        assertEquals(doc.at("/specifications/0/original"), result.at("/publishedKnowledge/specification/original"));
        for (String field : java.util.List.of("sourceDisposition", "sourceReasonCode", "sourceClausePointer"))
            assertEquals(result.path("publishedKnowledge").path(field), result.path("publicSummary").path(field));
        assertEquals("EXECUTABLE", result.at("/publicSummary/sourceDisposition").asText());
        assertEquals(fixture.model.path("transcript"), result.at("/publicSummary/transcript"));
        assertEquals(fixture.model.path("protein"), result.at("/publicSummary/protein"));
        assertFalse(result.at("/sequenceMaterial/alleleAssociation").has("transcript"));
        assertFalse(result.at("/sequenceMaterial/alleleAssociation").has("protein"));
        var misleadingAssociation = result.deepCopy();
        misleadingAssociation.withObject("/sequenceMaterial/alleleAssociation").put("transcript", "UNVERIFIED").put("protein", "UNVERIFIED");
        assertEquals(result.path("publicSummary"), GeneralBp1Runtime.publicSummary(misleadingAssociation));
        assertFalse(result.path("publicSummary").toString().contains("cdsSequence"));
        assertEquals(ids.canonicalSha256(result), ids.canonicalSha256(mapper.readTree(mapper.writeValueAsBytes(result))));
        assertArrayEquals(mapper.writeValueAsBytes(result), mapper.writeValueAsBytes(mapper.readTree(mapper.writeValueAsBytes(result))));
    }
    @ParameterizedTest @ValueSource(strings={"0", "0.1000000"}) void inclusiveProjectBoundary(String value) throws Exception {
        setup(); score=value; assertEquals("MET", run().path("status").asText(), lastResult.toPrettyString());
    }
    @ParameterizedTest @ValueSource(strings={"NOT_APPLICABLE", "UNSUPPORTED_CONDITIONS", "SOURCE_CONFLICT"})
    void knownSourceDispositionStopsBeforeRawAndRemainsVisible(String status) throws Exception {
        setup(); var row = (ObjectNode)doc.at("/records/0");
        row.put("status",status).put("reasonCode",status.equals("NOT_APPLICABLE") ? "BP1_SOURCE_NOT_APPLICABLE"
                : status.equals("SOURCE_CONFLICT") ? "BP1_SOURCE_APPLICABILITY_CONFLICT" : "BP1_PUBLISHED_SOURCE_CONDITIONS_UNSUPPORTED");
        if ("NOT_APPLICABLE".equals(status)) {
            assertEquals("PUBLISHED_KNOWLEDGE_SOURCE_NONAPPLICABILITY_REQUIRED", assertThrows(IllegalArgumentException.class, this::knowledge).getMessage());
            var clause = (ObjectNode)doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent");
            clause.put("applicability", "Not Applicable").put("instructionsToUse", "").put("additionalComments", "");
            ((ObjectNode)clause.at("/strengthDescriptor/0")).put("applicability", "Not Applicable").put("text", "Not applicable.");
        }
        var result=run(); assertEquals("NOT_EVALUATED",result.path("status").asText()); assertEquals(0,calls.get());
        assertEquals(row.path("reasonCode"),result.path("reasonCode"));
        assertEquals(status,result.at("/publicSummary/knowledgeDisposition").asText());
        assertEquals("BLOCKED",result.at("/publicSummary/knowledgeStatus").asText());
        assertEquals(doc.at("/specifications/0/original"),result.at("/publishedKnowledge/specification/original"));
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals(status, result.at("/publicSummary/sourceDisposition").asText());
        assertEquals(row.path("reasonCode"), result.at("/publicSummary/sourceReasonCode"));
        assertEquals("/ld/CriteriaCode/0", result.at("/publicSummary/sourceClausePointer").asText());
    }
    @Test void publicExplanationCopiesOnlyTheNamedTextFieldsWithoutMutatingOrPromotingTheResult() {
        var out = mapper.createObjectNode().put("status", "NOT_EVALUATED");
        var source = out.putObject("publishedKnowledge");
        source.put("sourceDisposition", "EXECUTABLE").putNull("sourceReasonCode").putObject("sourceClausePointer").put("private", "value");
        source.put("privateProof", "DO_NOT_PROJECT");
        var before = out.deepCopy();
        var summary = GeneralBp1Runtime.publicSummary(out);
        assertEquals("EXECUTABLE", summary.path("sourceDisposition").asText());
        assertFalse(summary.has("sourceReasonCode")); assertFalse(summary.has("sourceClausePointer"));
        assertFalse(summary.has("privateProof")); assertFalse(summary.has("status"));
        assertEquals(before, out);
        assertFalse(GeneralBp1Runtime.publicSummary(mapper.createObjectNode()).has("sourceDisposition"));
    }
    @Test void missingPublicationScreenCannotBorrowLegacyPositive() throws Exception {
        setup();doc.remove("bp1FunctionalScreen");
        assertEquals("BP1_FUNCTION_SCREEN_NOT_CONFIGURED",run().path("reasonCode").asText());assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"0.100001", "0.2", "1"}) void highRawIsReviewNotBp1Negative(String value) throws Exception {
        setup(); score=value; assertEquals("BP1_PROJECT_SPLICE_PREDICTION_REQUIRES_REVIEW",run().path("reasonCode").asText());
        assertEquals("NOT_EVALUATED",lastResult.path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings={".", "NaN", "-0.1", "1.1", "1e-2"}) void malformedRawNeverBecomesZero(String value) throws Exception {
        setup(); score=value; assertEquals("BP1_RAW_SPLICE_SCORE_INVALID",run().path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"IDENTITY", "ALLELE", "QUERY", "NO_RECORD", "EXONS", "DUPLICATE", "MISSING_SCORE"})
    void corruptRawRefused(String fault) throws Exception {
        setup(); rawChange = b -> { var f=(ObjectNode)b.at("/results/0"); switch(fault) {
            case "IDENTITY" -> b.put("nativeResourceSha256", "c".repeat(64));
            case "ALLELE" -> ((ObjectNode)f.path("allele")).put("alternate","T");
            case "QUERY" -> f.put("queryId","other");
            case "NO_RECORD" -> f.put("status","NO_RECORD");
            case "EXONS" -> ((ObjectNode)f.at("/annotationRows/0")).put("end",105);
            case "DUPLICATE" -> b.withArray("results").add(f.deepCopy());
            default -> ((ObjectNode)f.at("/annotations/0/deltaScores")).remove("DS_DL");
        }}; assertEquals("NOT_EVALUATED",run().path("status").asText()); assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"CATALOG", "EFFECTS", "RAW", "MATERIAL", "KNOWLEDGE"})
    void missingDependenciesExplainRatherThanThrow(String missing) throws Exception {
        setup(); if(missing.equals("CATALOG"))catalog=null; if(missing.equals("EFFECTS"))effectsMissing=true;
        if(missing.equals("RAW"))rawMissing=true; if(missing.equals("MATERIAL"))supply=null;
        var r=missing.equals("KNOWLEDGE") ? new GeneralBp1Runtime(mapper,supply,null,(MissenseNativeSpliceBridge)null,catalog,this::effects,"b".repeat(64)) : runtime();
        var result=r.evaluate(fixture.input.fixture().context(),fixture.input.fixture().candidate(),mapper.createObjectNode().put("candidateId","synthetic"));
        assertEquals("NOT_EVALUATED",result.path("status").asText()); assertNotEquals("BP1_RUNTIME_CHECK_FAILED",result.path("reasonCode").asText()); assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"SCOPE", "SOURCE", "EXTERNAL", "REFERENCE", "INCOMPLETE"})
    void functionalRestrictionAndUntrustedReferencesStopBeforeRaw(String fault) throws Exception {
        setup(); effectChange=e->{switch(fault){
            case "SCOPE" -> ((ObjectNode)e.path("scope")).put("candidateId","another");
            case "SOURCE" -> e.put("sourceSha256","c".repeat(64));
            case "EXTERNAL" -> e.put("externalDeclaration","NORMAL_RNA_UNREVIEWED");
            case "REFERENCE" -> e.withArray("references").addObject().put("mechanism","PROTEIN_MISSENSE");
            default -> e.put("coverage","PARTIAL");
        }}; assertEquals("NOT_EVALUATED",run().path("status").asText()); assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"NOTE", "STRENGTH", "ATTACHMENT", "INSTRUCTION", "POLICY", "EXTRA_POLICY"})
    void unsupportedSourceConditionsNeverBecomeTheSimpleRule(String fault) throws Exception {
        setup(); var c=(ObjectNode)doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent");
        switch(fault){
            case "NOTE" -> c.put("additionalComments","Exclude residues 10-20");
            case "STRENGTH" -> ((ObjectNode)c.at("/strengthDescriptor/0")).put("strength","Strong");
            case "ATTACHMENT" -> ((ObjectNode)doc.at("/specifications/0/original/ld")).putArray("File").addObject().put("url","https://example.invalid/appendix");
            case "INSTRUCTION" -> c.put("instructionsToUse","All missense variants except a domain");
            case "POLICY" -> ((ObjectNode)doc.at("/records/0/interpretation")).put("projectRestriction","NONE");
            default -> ((ObjectNode)doc.at("/records/0/interpretation")).put("threshold","0.9");
        } assertEquals("NOT_EVALUATED",run().path("status").asText()); assertEquals(0,calls.get());
    }
    @Test void wrongBackgroundAndTranscriptVersionNeverBorrowSource() throws Exception {
        setup(); ((ObjectNode)doc.at("/records/0/scope")).put("transcript","ENST000001.3");
        ((ObjectNode)doc.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0")).put("preferredTranscript","ENST000001.3");
        assertEquals("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED",run().path("reasonCode").asText()); assertEquals(0,calls.get());
    }
    @Test void sourceAndProjectPolicyAreBoundIntoIdentity() throws Exception {
        setup(); var r=runtime(); var i=r.identity(); i.removeAll(); assertFalse(r.identity().isEmpty());
        assertEquals("b".repeat(64),r.identity().path("effectSourceSha256").asText());
        assertTrue(r.identity().path("experimentCatalog").has("snapshotId"));
    }
    @ParameterizedTest @ValueSource(strings={"synonymous_variant", "missense_variant&splice_region_variant", "stop_gained", "frameshift_variant"})
    void nonPureMissenseCannotUseAllMissensePrior(String consequence) throws Exception {
        setup(); var candidate=fixture.input.fixture().candidate();
        ObjectNode annotation=mapper.valueToTree(candidate.getTranscriptAnnotations().get(0)); annotation.put("consequence",consequence);
        annotation.retain("geneSymbol", "entrezGeneId", "transcriptAccession", "hgvsCoding", "hgvsProtein", "consequence",
                "geneId", "transcriptId", "transcriptSource", "impact", "exon", "intron", "canonical", "maneSelect",
                "manePlusClinical", "proteinPosition", "aminoAcids", "codons", "lof", "lofFilter", "lofFlags", "source", "status", "version", "timestamp");
        candidate.applyTranscriptAnnotations(java.util.List.of(mapper.treeToValue(annotation,org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        assertEquals("NOT_EVALUATED",run().path("status").asText()); assertEquals(0,calls.get());
    }
    @Test void differentDiseaseCannotBorrowTheSameGeneSpecification() throws Exception {
        setup(); ((ObjectNode)doc.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0/diseases/0")).put("preferredMondoId","MONDO:0000002");
        ((ObjectNode)doc.at("/records/0/scope")).put("mondoId","MONDO:0000002");
        var result = run();
        assertEquals("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED",result.path("reasonCode").asText()); assertEquals(0,calls.get());
        directArtifact("wrong-disease-direct", result, fixture.input.fixture().context());
    }
    @Test void sameAlleleExperimentalConflictStopsBeforeRawEvenWithDifferentTranscript() throws Exception {
        setup(); ObjectNode cat;
        try (var input = new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json").getInputStream()) {
            cat=(ObjectNode)mapper.readTree(input);
        }
        var record=(ObjectNode)cat.path("records").get(0); record.set("allele",fixture.input.fixture().context().at("/variant/evidenceQueryAllele").deepCopy());
        var checks=new SplicingExperimentChecks(mapper,new ByteArrayResource(new byte[0]),root,(a,b)->null);
        record.remove("contentSha256"); record.put("contentSha256",checks.hash(record));
        cat.remove("contentSha256"); cat.put("contentSha256",checks.hash(cat));
        catalog=new SplicingExperimentChecks(mapper,new ByteArrayResource(requireNonNull(mapper.writeValueAsBytes(cat))),root,(a,b)->null).captureCatalog();
        assertEquals("BP1_RECORDED_SPLICE_EFFECT_REVIEW_REQUIRED",run().path("reasonCode").asText());
        assertEquals(0,calls.get()); assertFalse(lastResult.at("/experimentalConflictCheck/matches").isEmpty());
    }
    @Test void rawTimeoutKeepsSpecificFailureAndNoResult() throws Exception {
        setup(); var r=new GeneralBp1Runtime(mapper,supply,knowledge(),(c,m,q)->SpliceAiRawBatchRunner.BatchResult.failed("NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED",null),
                rawIdentity,catalog,this::effects,"b".repeat(64));
        var result=r.evaluate(fixture.input.fixture().context(),fixture.input.fixture().candidate(),mapper.createObjectNode().put("candidateId","synthetic"));
        assertEquals("NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED",result.path("reasonCode").asText());
        assertEquals("FAILED",result.at("/rawSpliceExecution/status").asText()); assertFalse(result.has("rawSpliceResult"));
    }
    private GeneralBp1RuntimeTest truncatingMissenseFixture() throws Exception {
        var source = new Bp1TruncatingApplicabilityTest(); source.root = root; source.setup();
        var f = source.f;
        ((ObjectNode) f.doc.at("/records/0")).put("status", "UNSUPPORTED_CONDITIONS")
                .put("reasonCode", "BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED");
        return f;
    }
    @Test void truncatingSourceUsesVerifiedMissenseNonApplicabilityBeforeBlockedReadinessAndWithoutRaw() throws Exception {
        var f = truncatingMissenseFixture(); var out = f.run();
        assertEquals("NOT_EVALUATED", out.path("status").asText(), out::toPrettyString);
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("BP1_TRUNCATING_RULE_NOT_APPLICABLE_TO_PURE_MISSENSE", out.path("reasonCode").asText());
        assertEquals("NOT_APPLICABLE", out.at("/evaluation/criterionApplicability/status").asText());
        assertEquals("NOT_APPLICABLE", out.at("/publicSummary/truncatingConditionCheck/status").asText());
        assertEquals("VERIFIED_PURE_CODING_MISSENSE", out.at("/publicSummary/truncatingConditionCheck/available/variantEffect").asText());
        assertEquals("UNSUPPORTED_CONDITIONS", out.at("/publicSummary/sourceDisposition").asText());
        assertEquals("BLOCKED", out.at("/publicSummary/knowledgeStatus").asText());
        assertEquals(0, f.calls.get()); assertFalse(out.has("rawSpliceResult"));
        assertFalse(out.path("publicSummary").has("projectSplicePolicy"));
        assertFalse(out.path("publicSummary").has("maximumObservedDelta"));
        assertFalse(out.path("publicSummary").has("functionalReferences"));
        assertFalse(out.path("publicSummary").has("functionalScreen"));
    }
    @ParameterizedTest @ValueSource(strings={"CONFLICT", "OTHER_UNSUPPORTED", "COMMENT", "NOTES", "DUPLICATE", "ATTACHMENT", "UNKNOWN"})
    void truncatingLabelCannotBypassConflictingOrUninterpretedSourceConditions(String fault) throws Exception {
        var f = truncatingMissenseFixture();
        var row = (ObjectNode) f.doc.at("/records/0");
        var original = (ObjectNode) f.doc.at("/specifications/0/original");
        var clause = (ObjectNode) original.at("/ld/CriteriaCode/0/entContent");
        switch (fault) {
            case "CONFLICT" -> row.put("status", "SOURCE_CONFLICT").put("reasonCode", "BP1_SOURCE_APPLICABILITY_CONFLICT");
            case "OTHER_UNSUPPORTED" -> row.put("reasonCode", "BP1_OTHER_SOURCE_CONDITION_UNSUPPORTED");
            case "COMMENT" -> clause.put("additionalComments", "An extra requirement must not be ignored.");
            case "NOTES" -> original.withObject("/entContent").put("notes", "Unknown general restrictions.");
            case "DUPLICATE" -> clause.withArray("strengthDescriptor").add(clause.at("/strengthDescriptor/0").deepCopy());
            case "ATTACHMENT" -> original.withObject("/ld").putArray("File").addObject().put("name", "Uninterpreted attachment");
            default -> row.withObject("/interpretation").put("unknownCondition", true);
        }
        var out = f.run(); assertEquals("NOT_EVALUATED", out.path("status").asText(), out::toPrettyString);
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText()); assertEquals(0, f.calls.get());
        assertNotEquals("NOT_APPLICABLE", out.at("/criterionApplicability/status").asText());
        assertFalse(out.has("truncatingConditionCheck"));
        f.directArtifact("truncating-source-fault-" + fault, out, f.fixture.input.fixture().context());
    }
    @Test void claimedPureMissenseWithWrongCodingAnnotationRemainsAQualityFailure() throws Exception {
        var f = truncatingMissenseFixture(); var c = f.fixture.input.fixture().candidate();
        ObjectNode annotation = f.mapper.valueToTree(c.getTranscriptAnnotations().get(0));
        annotation.put("hgvsCoding", "ENST000001.4:c.5C>T");
        var tx = f.mapper.readerFor(org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence>readValue(annotation);
        c.applyTranscriptAnnotations(java.util.List.of(tx));
        var out = f.run(); assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("CODING_MISSENSE_GENOMIC_CODING_IDENTITY_MISMATCH", out.path("reasonCode").asText());
        assertFalse(out.has("criterionApplicability")); assertEquals(0, f.calls.get());
        f.directArtifact("wrong-annotation-direct", out, f.fixture.input.fixture().context());
    }
    @ParameterizedTest @ValueSource(strings={"PTPN11", "MAP2K1"})
    void actualStructureAndNamedSourceConditionsExecuteWithoutMissenseRawOrFunctionalDependencies(String symbol) throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root, symbol,
                "PTPN11".equals(symbol) ? "HGNC:9644" : "HGNC:6840", "ENST000001.4");
        var runtime = new GeneralBp1Runtime(f.mapper, f.supply, f.catalog(), (MissenseNativeSpliceBridge) null, null, null, null);
        var out = runtime.evaluate(f.context, f.candidate, f.mapper.createObjectNode().put("candidateId", f.candidate.getCandidateId()));
        assertEquals("PTPN11".equals(symbol) ? "NOT_MET" : "NOT_EVALUATED", out.path("status").asText(), out::toPrettyString);
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("READY", out.at("/sequenceMaterial/status").asText());
        assertEquals("TRUNCATION_CANDIDATE", out.at("/truncatingConditionCheck/sequenceConsequence/status").asText());
        assertEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", out.path("annotationCorrespondence").asText());
        assertEquals("CHECKED", out.at("/publicSummary/truncatingConditionCheck/lofDiseaseCorrelation/sourceCheckStatus").asText());
        assertFalse(out.has("rawSpliceExecution")); assertFalse(out.has("functionalEffectCheck"));
        assertFalse(out.path("publicSummary").toString().contains("cdsSequence"));
        assertFalse(out.path("publicSummary").toString().contains(root.toString()));
        assertEquals(1, runtime.evaluations());
    }
    @Test void boundedTruncatingProjectionOmitsUnrecognisedFieldsAtEveryPublicLevel() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        var runtime = new GeneralBp1Runtime(f.mapper, f.supply, f.catalog(), (MissenseNativeSpliceBridge) null, null, null, null);
        var out = runtime.evaluate(f.context, f.candidate, f.mapper.createObjectNode().put("candidateId", f.candidate.getCandidateId()));
        var expected = out.path("publicSummary").deepCopy();
        var publicCheck = out.withObject("/truncatingConditionCheck/publicSummary");
        publicCheck.put("rawJson", "DO_NOT_PROJECT"); publicCheck.withObject("/required").put("privateSequence", "DO_NOT_PROJECT");
        publicCheck.withObject("/available").put("privatePath", "DO_NOT_PROJECT");
        publicCheck.withObject("/available").put("functionalLossEstablishedBySequence", true);
        publicCheck.withObject("/required").put("missingRecordMeansNoCorrelation", true);
        publicCheck.withObject("/publishedSource/scope").put("private", "DO_NOT_PROJECT");
        publicCheck.withObject("/lofDiseaseCorrelation/sourceBinding").put("rawRef", "DO_NOT_PROJECT");
        publicCheck.withObject("/lofDiseaseCorrelation/relation").put("rawText", "DO_NOT_PROJECT");
        publicCheck.withObject("/lofDiseaseCorrelation/sourceMention").put("rawBody", "DO_NOT_PROJECT");
        assertEquals(expected, GeneralBp1Runtime.publicSummary(out));
        assertFalse(GeneralBp1Runtime.publicSummary(out).toString().contains("DO_NOT_PROJECT"));
    }
    @Test void newTruncatingCapabilityIdentityIsStaticWithoutInputsOrResourceAccess() {
        var first = GeneralBp1Runtime.truncatingConditionIdentity(mapper); var second = GeneralBp1Runtime.truncatingConditionIdentity(mapper);
        assertEquals(first, second); assertFalse(first.path("noEstablishedLofCorrelationQualificationSupported").asBoolean(true));
        String sha = first.remove("canonicalSha256").asText(); assertEquals(ids.canonicalSha256(first), sha);
        first.removeAll(); assertEquals(second, GeneralBp1Runtime.truncatingConditionIdentity(mapper));
    }

    @Test void legacyMissenseCodingVersionTransferIsNotBlockedByAStructureOnlyProbe() throws Exception {
        var f = new GeneralBp1RuntimeTest(); f.root = java.nio.file.Files.createDirectories(root.resolve("classic")); f.setup();
        var bridge = new PublishedTranscriptBridgeTest(); bridge.root = java.nio.file.Files.createDirectories(root.resolve("bridge")); bridge.setup();
        var sequence = bridge.fixture.sequence; sequence.model.put("geneId", "ENSG000001");
        ((ObjectNode) f.doc.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0")).put("preferredTranscript", "NM_000001.2");
        ((ObjectNode) f.doc.at("/records/0/scope")).put("transcript", "NM_000001.2");
        var runtime = new GeneralBp1Runtime(f.mapper, sequence.writeAndLoad(), f.knowledge(),
                (c, m, q) -> SpliceAiRawBatchRunner.BatchResult.succeeded(f.raw(c, m, q.get(0).getQueryId()), 0),
                f.rawIdentity, f.catalog, f::effects, "b".repeat(64));
        var input = sequence.input.fixture();
        var out = runtime.evaluate(input.context(), input.candidate(), f.mapper.createObjectNode().put("candidateId", "synthetic"));
        assertEquals("MET", out.path("status").asText(), out::toPrettyString);
        assertEquals("SUPPORTING", out.at("/evaluation/appliedStrength").asText());
        assertEquals("NM_000001.2", out.at("/publishedKnowledge/codingTranscriptTransfer/sourceTranscript").asText());
        assertEquals(1, f.calls.get()); assertFalse(out.has("truncatingConditionCheck"));
    }

    private void directArtifact(String name, ObjectNode actualResult, ObjectNode actualContext) throws Exception {
        String configured = System.getProperty("t05.bp1.runtime-artifacts");
        if (configured == null) return;
        var envelope = mapper.createObjectNode().put("schemaVersion", "t05-bp1-direct-runtime-control-v1")
                .put("dataClass", "SYNTHETIC").put("runtimeLayer", "ACTUAL_GENERAL_BP1_RUNTIME")
                .put("callerLayer", "DIRECT_NO_ROOT_EXECUTION")
                .put("rawSourceLayer", "SYNTHETIC_TEST_RAW_OUTPUT_NOT_NATIVE_MODEL")
                .put("normalBusiness", false).put("hasSelectedSourceProjection", actualResult.has("sourceProjection"))
                .put("hasRootExecutionSource", actualResult.has("executionSource"))
                .put("clinicalValidationCompleted", false);
        envelope.set("actualContext", actualContext.deepCopy());
        envelope.set("actualRuntimeResult", actualResult.deepCopy());
        envelope.set("actualPublicSummary", GeneralBp1Runtime.publicSummary(actualResult));
        Path output = java.nio.file.Files.createDirectories(Path.of(configured));
        java.nio.file.Files.write(output.resolve(name + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope));
    }

}
