package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GeneralPp2RuntimeTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    CodingTranscriptMaterialSupplyTest data;
    CodingTranscriptMaterialSupply material;
    void setup() throws Exception {
        data = new CodingTranscriptMaterialSupplyTest(); data.root = Files.createDirectory(root.resolve("model")); material = data.setup();
    }
    ObjectNode document() throws Exception {
        var support = new PublishedCriterionKnowledgeTest(); var doc = support.document();
        var original = (ObjectNode)doc.at("/specifications/0/original");
        ((ObjectNode)original.at("/ld/Gene/0/entContent/HGNC")).put("symbol","SYNTH1").put("hgnc_id","HGNC:1");
        var gene = (ObjectNode)original.at("/ld/RuleSet/0/entContent/genes/0"); gene.put("gene","SYNTH1").put("preferredTranscript","ENST000001.4");
        var context = data.input.fixture().context();
        var disease = (ObjectNode)gene.at("/diseases/0"); disease.put("preferredMondoId",context.at("/disease/mondoId").asText());
        var row = (ObjectNode)doc.at("/records/0"); row.put("criterionId",GeneralPp2Runtime.CRITERION);
        ((ObjectNode)row.path("scope")).put("hgncId","HGNC:1").put("mondoId",context.at("/disease/mondoId").asText())
                .put("moi",context.path("moi").asText()).put("transcript","ENST000001.4");
        disease.put("preferredModeOfInheritance","AUTOSOMAL_RECESSIVE".equals(context.path("moi").asText())
                ? "Autosomal recessive inheritance" : "Autosomal dominant inheritance");
        var clause = (ObjectNode)original.at("/ld/CriteriaCode/0/entContent"); clause.put("label","PP2");
        clause.put("instructionsToUse",Pp2PublishedAssessment.DEFINITION);
        clause.putArray("gene").add("SYNTH1"); clause.putArray("disease").add(context.at("/disease/mondoId").asText());
        clause.remove("text"); clause.put("additionalComments",Pp2PublishedAssessment.APPLICATION);
        clause.putArray("strengthDescriptor").addObject().put("applicability","Applicable").put("status","approved")
                .put("strength","Supporting").put("instructionsToUse","").put("text",Pp2PublishedAssessment.ASSERTION);
        ((ObjectNode)original.path("entContent")).put("notes",Pp2PublishedAssessment.NOTES);
        row.set("interpretation",interpretation()); refresh(doc); return doc;
    }
    ObjectNode interpretation() {
        var i = mapper.createObjectNode().put("type",Pp2PublishedAssessment.TYPE).put("version","1.0.0")
                .put("germlineOnly",true).put("appliedStrength","SUPPORTING");
        var conditions = i.putObject("conditions");
        conditions.putObject("missenseMechanism").put("conclusion","SUPPORTED").put("basisType","PUBLISHED_PP2_APPLICATION");
        conditions.putObject("lowBenignMissense").put("conclusion","SUPPORTED").put("basisType","PUBLISHED_CONSTRAINT_ASSERTION");
        i.putObject("sourceTextBindings").put("supportingText",Pp2PublishedAssessment.ASSERTION)
                .put("comments",Pp2PublishedAssessment.APPLICATION).put("notes",Pp2PublishedAssessment.NOTES); return i;
    }
    void refresh(ObjectNode doc) {
        ((ObjectNode)doc.at("/specifications/0")).put("entitySha256",adapter.canonicalSha256(doc.at("/specifications/0/original")));
        ((ObjectNode)doc.at("/records/0")).put("clauseSha256",adapter.canonicalSha256(doc.at("/specifications/0/original/ld/CriteriaCode/0")));
    }
    PublishedCriterionKnowledge load(ObjectNode doc) throws Exception {
        var bytes = mapper.writeValueAsBytes(doc); Path f = root.resolve("published.json"); Files.write(f,bytes);
        return PublishedCriterionKnowledge.load(mapper,f,CodingTranscriptMaterialSupply.sha(bytes));
    }
    ObjectNode run(GeneralPp2Runtime runtime) {
        return runtime.evaluate(data.input.fixture().context(),data.input.fixture().candidate(),mapper.createObjectNode());
    }
    @Test void exactSourceAndSequenceCanProduceSupportingWithoutReviewFabrication() throws Exception {
        setup(); var doc = document(); var runtime = new GeneralPp2Runtime(mapper,material,load(doc),true);
        var candidateBefore=mapper.valueToTree(data.input.fixture().candidate()); var contextBefore=data.input.fixture().context().deepCopy();
        var out=run(runtime); assertEquals("MET",out.path("status").asText(),out.toPrettyString());
        assertEquals("SUPPORTING",out.at("/evaluation/appliedStrength").asText());
        assertEquals(doc.at("/specifications/0/original"),out.at("/knowledgeSelection/specification/original"));
        for (String field : List.of("sourceDisposition", "sourceReasonCode", "sourceClausePointer"))
            assertEquals(out.path("knowledgeSelection").path(field), out.path("publicSummary").path(field));
        assertEquals("EXECUTABLE", out.at("/publicSummary/sourceDisposition").asText());
        assertFalse(out.toString().contains("REVIEWED_IMPLEMENTED")); assertFalse(out.has("review"));
        assertFalse(out.at("/publicSummary/constraintScoresRecalculated").asBoolean(true));
        assertFalse(out.path("publicSummary").toString().contains("cdsSequence"));
        assertEquals(candidateBefore,mapper.valueToTree(data.input.fixture().candidate())); assertEquals(contextBefore,data.input.fixture().context());
        var again=run(runtime); assertEquals("MET",again.path("status").asText());
        assertTrue(again.at("/sequenceMaterial/modelCacheHit").asBoolean()); assertEquals(2,runtime.evaluations());
        assertArrayEquals(mapper.writeValueAsBytes(out),mapper.writeValueAsBytes(mapper.readTree(mapper.writeValueAsBytes(out))));
    }
    @Test void germlineDeclarationDefaultsFalseAndChangesIdentity() throws Exception {
        setup(); var k=load(document()); var absent=new GeneralPp2Runtime(mapper,material,k);
        var declared=new GeneralPp2Runtime(mapper,material,k,true);
        assertNotEquals(absent.identity(),declared.identity());
        assertEquals("PP2_GERMLINE_SCOPE_NOT_DECLARED",run(absent).path("reasonCode").asText());
        assertEquals("MET",run(declared).path("status").asText());
    }

    private ObjectNode authoritySpectrum(ObjectNode doc) {
        var pack = doc.putObject("pp2AuthoritySpectrum").put("schemaVersion", "pp2-authority-spectrum-v1");
        pack.putObject("source").put("url", Pp2PublishedSpectrum.URL).put("locator", "S19").put("rawSha256", "1".repeat(64));
        var row = pack.putArray("records").addObject().put("recordId", "synthetic-variant-spectrum");
        row.set("scope", doc.at("/records/0/scope").deepCopy());
        row.putObject("specialistCheck").put("status", "NO_MATCHING_SPECIFICATION").put("inventoryRawSha256", "2".repeat(64));
        row.putObject("mechanismCorroboration").put("url", "https://search.clinicalgenome.org/kb/gene-validity/synthetic")
                .put("locator", "Synthetic mechanism fixture").put("rawSha256", "3".repeat(64));
        var conditions = row.putObject("conditions");
        for (String condition : List.of("missenseMechanism", "lowBenignMissense")) {
            conditions.putObject(condition).put("conclusion", "NOT_SUPPORTED").put("basisType", Pp2PublishedSpectrum.BASIS)
                    .put("sourceText", "Synthetic source observation for " + condition).put("sourceLocator", "S19");
        }
        return conditions;
    }

    @Test void authoritySpectrumRequiresTwoKnownConditionsAndVerifiedMissense() throws Exception {
        setup(); var doc = document(); var conditions = authoritySpectrum(doc);
        doc.putArray("records");
        var knowledge = load(doc);
        var out = run(new GeneralPp2Runtime(mapper, material, knowledge, true));
        assertEquals("NOT_MET", out.path("status").asText(), out.toPrettyString());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("PP2_MISSENSE_MECHANISM_NOT_SUPPORTED", out.path("reasonCode").asText());
        assertEquals(conditions, out.at("/publicSummary/conditions"));
        assertFalse(out.at("/publicSummary/proteinVariantKey").asText().isBlank());
        assertEquals("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED", knowledge.select(GeneralPp2Runtime.CRITERION,
                data.input.fixture().context(), data.model).path("reasonCode").asText());
        assertEquals("PP2_GERMLINE_SCOPE_NOT_DECLARED",
                run(new GeneralPp2Runtime(mapper, material, knowledge)).path("reasonCode").asText());
        ((ObjectNode) conditions.path("lowBenignMissense")).put("conclusion", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> load(doc));
    }

    @Test void authoritySpectrumNeverOverridesAnExactSpecialistConflict() throws Exception {
        setup(); var doc = document(); authoritySpectrum(doc);
        ((ObjectNode) doc.at("/records/0")).put("status", "SOURCE_CONFLICT").put("reasonCode", "PP2_SOURCE_CONTENT_CONFLICT");
        var out = run(new GeneralPp2Runtime(mapper, material, load(doc), true));
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("PP2_SOURCE_CONTENT_CONFLICT", out.path("reasonCode").asText());
        assertTrue(out.at("/publicSummary/authoritySpectrum").isMissingNode());
    }

    @Test void acgsNegativeBindsIndependentSourcesAndRequiresScoreBelowPublishedThreshold() throws Exception {
        setup();
        var doc = document();
        var pack = doc.putObject("pp2AcgsConstraint").put("schemaVersion", "pp2-acgs-constraint-negative-v1");
        pack.putObject("source").put("url", Pp2PublishedSpectrum.ACGS_URL)
                .put("locator", Pp2PublishedSpectrum.ACGS_LOCATOR).put("rawSha256", "1".repeat(64));
        var row = pack.putArray("records").addObject().put("recordId", "synthetic-constraint-spectrum");
        row.set("scope", doc.at("/records/0/scope").deepCopy());
        row.putObject("specialistCheck").put("status", "NO_MATCHING_SPECIFICATION").put("inventoryRawSha256", "2".repeat(64));
        row.putObject("mechanismCorroboration").put("url", "https://search.clinicalgenome.org/kb/gene-validity/synthetic")
                .put("locator", "Synthetic mechanism fixture").put("rawSha256", "3".repeat(64));
        var mechanism = row.putObject("mechanism").put("conclusion", "SUPPORTED").put("sourceText", "Synthetic disease spectrum");
        mechanism.putObject("source").put("url", "https://pmc.ncbi.nlm.nih.gov/articles/PMC000000/#tbl1")
                .put("locator", "Table 1").put("rawSha256", "4".repeat(64));
        var constraint = row.putObject("constraint").put("assembly", "GRCh38").put("transcript", "ENST000001.4")
                .put("release", "4.1.2").put("observed", 35).put("expected", 49.1).put("z", 3.08);
        constraint.putArray("flags");
        var constraintSource = constraint.putObject("source")
                .put("url", Pp2PublishedSpectrum.GNOMAD_TRANSCRIPT_URL + "ENST000001?dataset=gnomad_r4")
                .put("locator", "Transcript constraint, missense").put("rawSha256", "5".repeat(64));
        ((ObjectNode) doc.at("/records/0")).put("status", "SOURCE_CONFLICT").put("reasonCode", "PP2_SOURCE_CONTENT_CONFLICT");
        assertEquals("PP2_SOURCE_CONTENT_CONFLICT", run(new GeneralPp2Runtime(mapper, material, load(doc), true)).path("reasonCode").asText());

        doc.putArray("records");
        var out = run(new GeneralPp2Runtime(mapper, material, load(doc), true));
        assertEquals("NOT_MET", out.path("status").asText(), out.toPrettyString());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED", out.path("reasonCode").asText());
        var conditions = out.at("/publicSummary/conditions");
        assertEquals("SUPPORTED", conditions.at("/missenseMechanism/conclusion").asText());
        assertEquals("NOT_SUPPORTED", conditions.at("/lowBenignMissense/conclusion").asText());
        assertEquals(mechanism.path("source"), conditions.at("/missenseMechanism/source"));
        assertEquals(constraintSource, conditions.at("/lowBenignMissense/source"));
        assertEquals(3.09, conditions.at("/lowBenignMissense/measurement/threshold").asDouble());
        assertEquals("GTE", conditions.at("/lowBenignMissense/measurement/operator").asText());

        constraint.put("z", 3.09);
        assertThrows(IllegalArgumentException.class, () -> load(doc));
        constraint.put("z", 3.08).put("transcript", "ENST000001.3");
        assertThrows(IllegalArgumentException.class, () -> load(doc));
        constraint.put("transcript", "ENST000001.4");
        constraintSource.put("rawSha256", "4".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> load(doc));
    }
    @Test void ruleTranscriptIsPreparedSeparatelyWithoutChangingTheSharedCandidate() throws Exception {
        setup(); var doc=document();
        // A different default transcript is valid technically but is outside the rule scope.
        var other=(ObjectNode)mapper.readTree(data.model.toString().replace("ENST000001.4","ENST000002.1"));
        byte[] bytes=mapper.writeValueAsBytes(other);Files.write(data.root.resolve("ENST000002.1.model.json"),bytes);
        data.manifest.withArray("entries").addObject().put("transcript","ENST000002.1")
                .put("file","ENST000002.1.model.json").put("fileBytes",bytes.length)
                .put("fileSha256",CodingTranscriptMaterialSupply.sha(bytes)).put("canonicalSha256",adapter.canonicalSha256(other))
                .put("modelId",other.path("modelId").asText()).put("modelVersion",other.path("modelVersion").asText());
        material=data.load(); var candidate=data.input.fixture().candidate();
        var originalAnnotation=candidate.getTranscriptAnnotations().get(0);
        var alternateAnnotation=mapper.readTree(mapper.writeValueAsString(originalAnnotation).replace("ENST000001.4","ENST000002.1").replace("ENST000001","ENST000002"));
        ((ObjectNode)alternateAnnotation).remove(List.of("hgvsc","hgvsp"));
        candidate.applyTranscriptAnnotations(List.of(originalAnnotation,mapper.treeToValue(alternateAnnotation,
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        var context=data.input.fixture().context();
        ((ObjectNode)context.path("transcript")).put("accession","ENST000002").put("version",1);
        var frozen=context.deepCopy();var candidateBefore=mapper.valueToTree(candidate);
        var result=run(new GeneralPp2Runtime(mapper,material,load(doc),true));
        assertEquals("MET",result.path("status").asText(),result.toPrettyString());
        assertEquals("ENST000001.4",result.at("/knowledgeSelection/executionTranscript").asText());
        assertEquals("ENST000002.1",result.at("/transcriptPreparation/originalTranscript").asText());
        assertFalse(result.at("/transcriptPreparation/selectionUsesScore").asBoolean(true));
        assertEquals(frozen,context);assertEquals(candidateBefore,mapper.valueToTree(candidate));
        var stale=(ObjectNode)mapper.valueToTree(originalAnnotation);
        stale.put("version","different-annotation-release");
        stale.remove(List.of("hgvsc","hgvsp"));
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(stale,
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class),mapper.treeToValue(alternateAnnotation,
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        var incompatible=run(new GeneralPp2Runtime(mapper,material,load(doc),true));
        assertEquals("NOT_EVALUATED",incompatible.path("status").asText());
        assertEquals("UNAVAILABLE",incompatible.at("/transcriptPreparation/status").asText());
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(alternateAnnotation,
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        var absent=run(new GeneralPp2Runtime(mapper,material,load(doc),true));
        assertEquals("NOT_EVALUATED",absent.path("status").asText());
        assertEquals("UNAVAILABLE",absent.at("/transcriptPreparation/status").asText());
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE_CONFLICT","UNSUPPORTED_CONDITIONS","NOT_APPLICABLE"})
    void blockedSourceRetainsFullReasonAndNeverFallsBack(String state) throws Exception {
        setup(); var doc=document(); ((ObjectNode)doc.at("/records/0")).put("status",state).put("reasonCode","PP2_TEST_BLOCKED");
        if ("NOT_APPLICABLE".equals(state)) {
            assertEquals("PUBLISHED_KNOWLEDGE_SOURCE_NONAPPLICABILITY_REQUIRED", assertThrows(IllegalArgumentException.class, () -> load(doc)).getMessage());
            var clause = (ObjectNode)doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent");
            clause.put("applicability", "Not Applicable").put("instructionsToUse", "").put("additionalComments", "");
            ((ObjectNode)clause.at("/strengthDescriptor/0")).put("applicability", "Not Applicable").put("text", "Not applicable.");
            refresh(doc);
        }
        var out=run(new GeneralPp2Runtime(mapper,material,load(doc),true));
        assertEquals("NOT_EVALUATED",out.path("status").asText()); assertEquals("PP2_TEST_BLOCKED",out.path("reasonCode").asText());
        assertEquals(doc.at("/specifications/0/original"),out.at("/knowledgeSelection/specification/original")); assertFalse(out.has("publishedConditions"));
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals(state, out.at("/publicSummary/sourceDisposition").asText());
        assertEquals("PP2_TEST_BLOCKED", out.at("/publicSummary/sourceReasonCode").asText());
        assertEquals("/ld/CriteriaCode/0", out.at("/publicSummary/sourceClausePointer").asText());
    }
    @Test void publicExplanationCopiesOnlyTheNamedTextFieldsWithoutMutatingOrPromotingTheResult() {
        var out = mapper.createObjectNode().put("status", "NOT_EVALUATED");
        var source = out.putObject("knowledgeSelection");
        source.put("sourceDisposition", "EXECUTABLE").putNull("sourceReasonCode").putObject("sourceClausePointer").put("private", "value");
        source.put("privateProof", "DO_NOT_PROJECT");
        var before = out.deepCopy();
        var summary = GeneralPp2Runtime.publicSummary(out);
        assertEquals("EXECUTABLE", summary.path("sourceDisposition").asText());
        assertFalse(summary.has("sourceReasonCode")); assertFalse(summary.has("sourceClausePointer"));
        assertFalse(summary.has("privateProof")); assertFalse(summary.has("status"));
        assertEquals(before, out);
        assertFalse(GeneralPp2Runtime.publicSummary(mapper.createObjectNode()).has("sourceDisposition"));
    }
    @ParameterizedTest @ValueSource(strings={"DEFINITION_ONLY","COMMENTS","NOTES","GENERAL_NOTES","CROSS_RULE","CROSS_TOP_INSTRUCTION","TOP_INSTRUCTION","MISSING_TOP_INSTRUCTION","STRENGTH","ACTIVE_EXTRA","UNAPPROVED","INSTRUCTION","MISSING_CONDITION","NEGATIVE_CONDITION","RAW_SCORE","EXTRA_OPERATOR","BINDING"})
    void activeLabelDoesNotAuthorizeChangedOrIncompleteAssertions(String change) throws Exception {
        setup(); var doc=document(); var original=(ObjectNode)doc.at("/specifications/0/original");
        var clause=(ObjectNode)original.at("/ld/CriteriaCode/0/entContent"); var strength=(ObjectNode)clause.at("/strengthDescriptor/0");
        var i=(ObjectNode)doc.at("/records/0/interpretation");
        switch(change) {
            case "DEFINITION_ONLY" -> strength.put("text","Missense variant in a gene with low benign missense variation.");
            case "COMMENTS" -> clause.put("additionalComments","Not applicable because z score is <3.09.");
            case "NOTES" -> ((ObjectNode)original.path("entContent")).put("notes",Pp2PublishedAssessment.NOTES+" Extra restriction.");
            case "GENERAL_NOTES" -> ((ObjectNode)original.at("/ld/RuleSet/0/entContent")).put("generalComments","Only if PM2 met.");
            case "CROSS_RULE" -> ((ObjectNode)original.at("/ld/CriteriaCode/1/entContent")).put("additionalComments","PP2 requires PS3.");
            case "CROSS_TOP_INSTRUCTION" -> ((ObjectNode)original.at("/ld/CriteriaCode/1/entContent")).put("instructionsToUse","Do not combine this criterion with PP2.");
            case "TOP_INSTRUCTION" -> clause.put("instructionsToUse","Only apply PP2 when PM2_supporting and PP3 are met.");
            case "MISSING_TOP_INSTRUCTION" -> clause.remove("instructionsToUse");
            case "STRENGTH" -> strength.put("strength","Strong");
            case "ACTIVE_EXTRA" -> clause.withArray("strengthDescriptor").add(strength.deepCopy());
            case "UNAPPROVED" -> strength.put("status","not approved");
            case "INSTRUCTION" -> strength.put("instructionsToUse","Only with clinical records");
            case "MISSING_CONDITION" -> ((ObjectNode)i.path("conditions")).remove("missenseMechanism");
            case "NEGATIVE_CONDITION" -> ((ObjectNode)i.at("/conditions/lowBenignMissense")).put("conclusion","NOT_SUPPORTED");
            case "RAW_SCORE" -> ((ObjectNode)i.at("/conditions/lowBenignMissense")).put("basisType","CURRENT_GNOMAD_Z");
            case "EXTRA_OPERATOR" -> i.put("ignoreExceptions",true);
            case "BINDING" -> ((ObjectNode)i.path("sourceTextBindings")).put("supportingText","Changed");
        }
        refresh(doc); var out=run(new GeneralPp2Runtime(mapper,material,load(doc),true));
        assertEquals("NOT_EVALUATED",out.path("status").asText(),out.toPrettyString()); assertFalse(out.has("publishedConditions"));
    }
    @Test void sourceAndSequenceRejectionPrecedeKnowledgeAndRemainSpecific() throws Exception {
        setup(); var runtime=new GeneralPp2Runtime(mapper,material,load(document()),true);
        data.reference="ATGAGATAA"; var out=run(runtime);
        assertEquals("TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH",out.path("reasonCode").asText()); assertFalse(out.has("knowledgeSelection"));
    }
    @Test void otherDiseaseCannotBorrowAssertions() throws Exception {
        setup();var runtime=new GeneralPp2Runtime(mapper,material,load(document()),true);
        ((ObjectNode)data.input.fixture().context().path("disease")).put("mondoId","MONDO:0000002");
        var out=run(runtime); assertEquals("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED",out.path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"synonymous_variant","missense_variant&splice_region_variant","stop_gained","frameshift_variant"})
    void annotationLabelCannotReplaceTheSupportedSequenceEffect(String consequence) throws Exception {
        setup();var runtime=new GeneralPp2Runtime(mapper,material,load(document()),true);
        var tx=(ObjectNode)mapper.valueToTree(data.input.fixture().candidate().getTranscriptAnnotations().get(0));
        tx.remove(List.of("hgvsc","hgvsp"));tx.put("consequence",consequence);
        data.input.fixture().candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(tx,org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        var out=run(runtime);assertEquals("NOT_EVALUATED",out.path("status").asText());assertFalse(out.has("knowledgeSelection"));
    }
    @Test void anotherSyntheticBackgroundUsesTheSameAlgorithmAndDoesNotBorrowTheOldRow() throws Exception {
        setup();var firstDoc=document();var oldKnowledge=load(firstDoc);var context=data.input.fixture().context();
        ((ObjectNode)context.path("gene")).put("hgncId","HGNC:2").put("symbol","SYNTH2");
        ((ObjectNode)context.path("disease")).put("mondoId","MONDO:0000002");
        data.model.put("hgncId","HGNC:2").put("geneSymbol","SYNTH2");
        var tx=(ObjectNode)mapper.valueToTree(data.input.fixture().candidate().getTranscriptAnnotations().get(0));
        tx.remove(List.of("hgvsc","hgvsp"));tx.put("geneSymbol","SYNTH2");
        data.input.fixture().candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(tx,org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        material=data.writeAndLoad();assertEquals("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED",run(new GeneralPp2Runtime(mapper,material,oldKnowledge,true)).path("reasonCode").asText());
        var next=document();var original=(ObjectNode)next.at("/specifications/0/original");
        ((ObjectNode)original.at("/ld/Gene/0/entContent/HGNC")).put("hgnc_id","HGNC:2").put("symbol","SYNTH2");
        ((ObjectNode)original.at("/ld/RuleSet/0/entContent/genes/0")).put("gene","SYNTH2");
        ((ObjectNode)original.at("/ld/CriteriaCode/0/entContent")).putArray("gene").add("SYNTH2");
        ((ObjectNode)next.at("/records/0/scope")).put("hgncId","HGNC:2");refresh(next);
        assertEquals("MET",run(new GeneralPp2Runtime(mapper,material,load(next),true)).path("status").asText());
    }
    @ParameterizedTest @CsvSource({"SUPPORTED,SUPPORTED,OK","NOT_SUPPORTED,SUPPORTED,PP2_MISSENSE_MECHANISM_NOT_SUPPORTED","SUPPORTED,NOT_SUPPORTED,PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED","NOT_SUPPORTED,NOT_SUPPORTED,PP2_MISSENSE_MECHANISM_NOT_SUPPORTED","NOT_SUPPORTED,UNKNOWN,PP2_CONDITION_UNKNOWN_OR_CONFLICT","UNKNOWN,SUPPORTED,PP2_CONDITION_UNKNOWN_OR_CONFLICT"})
    void sharedTwoConditionDecisionNeverTreatsUnknownAsNegative(String mechanism,String benign,String expected) {
        var conditions=mapper.createObjectNode(); conditions.putObject("missenseMechanism").put("conclusion",mechanism);
        conditions.putObject("lowBenignMissense").put("conclusion",benign);
        assertEquals("OK".equals(expected)?null:expected,Pp2ConditionDecision.reason(conditions));
    }
    @Test void retainedRealSpecificationsPassOnlyTheSourceInterpreter() throws Exception {
        String path=System.getProperty("varvis.pp2.published"); org.junit.jupiter.api.Assumptions.assumeTrue(path!=null);
        var doc=mapper.readTree(Files.readAllBytes(Path.of(path))); int count=0;
        for(var record:doc.path("records")) if("EXECUTABLE".equals(record.path("status").asText())) {
            var selection=mapper.createObjectNode(); selection.set("record",record.deepCopy());
            for(var spec:doc.path("specifications")) if(spec.path("specificationKey").equals(record.path("specificationKey"))) selection.set("specification",spec.deepCopy());
            assertNull(Pp2PublishedAssessment.validate(selection,true),record.path("recordId").asText());
            assertEquals("PP2_GERMLINE_SCOPE_NOT_DECLARED",Pp2PublishedAssessment.validate(selection,false));count++;
        }
        assertEquals(3,count); // Source semantics only: not real sequence, Top-N or patient validation.
    }
}
