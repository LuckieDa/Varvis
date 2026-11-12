package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PublishedBp3RuntimeTest {
    @TempDir Path root;
    final PublishedCriterionKnowledgeTest source = new PublishedCriterionKnowledgeTest();
    static final String TEXT = "Use for R1 repetitive regions only (amino acids 2-5). "
            + "These events can be multiple and large (e.g. 9 plus 15 total 20-30 bp is known, a single 21bp event is common). "
            + "Please note that R1 encompasses residues 1 through 6, with UniProt identifying particularly disordered regions between 2-3, 3-4, and 4-5.";

    ObjectNode document() throws Exception {
        source.root = root; source.mapper.findAndRegisterModules(); var d = source.document(); var o = (ObjectNode)d.at("/specifications/0/original");
        var clause = (ObjectNode)o.at("/ld/CriteriaCode/0/entContent"); clause.remove("text");
        clause.put("label", "BP3").put("baseStrength", "Benign").put("defaultStrength", "Benign Supporting");
        clause.putArray("strengthDescriptor").addObject().put("applicability", "Applicable").put("status", "approved")
                .put("strength", "Supporting").put("text", TEXT);
        ((ObjectNode)d.at("/records/0")).put("criterionId", GeneralBp3Runtime.CRITERION);
        derive(d); return d;
    }
    void derive(ObjectNode d) {
        ((ObjectNode)d.at("/records/0")).setAll(PublishedBp3RegionAssessment.derive(d.at("/specifications/0/original/ld/CriteriaCode/0/entContent")));
        source.refresh(d);
    }
    ObjectNode runtimeDocument(ObjectNode d, GeneralBp3RuntimeTest fixture) {
        var model = fixture.variant.data.model; var c = fixture.variant.data.context;
        var gene = (ObjectNode)d.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0");
        gene.put("gene", model.path("geneSymbol").asText()).put("preferredTranscript", model.path("transcript").asText());
        var disease = (ObjectNode)gene.at("/diseases/0"); disease.set("preferredMondoId", c.at("/disease/mondoId").deepCopy());
        disease.put("preferredModeOfInheritance", "Autosomal recessive inheritance");
        var hgnc = (ObjectNode)d.at("/specifications/0/original/ld/Gene/0/entContent/HGNC");
        hgnc.set("symbol", model.path("geneSymbol").deepCopy()); hgnc.set("hgnc_id", model.path("hgncId").deepCopy());
        ((ObjectNode)d.at("/specifications/0/original/ld/CriteriaCode/0/entContent")).putArray("gene").add(model.path("geneSymbol"));
        var scope = (ObjectNode)d.at("/records/0/scope"); scope.set("hgncId", model.path("hgncId").deepCopy());
        scope.set("mondoId", c.at("/disease/mondoId").deepCopy()); scope.set("moi", c.path("moi").deepCopy());
        scope.set("transcript", model.path("transcript").deepCopy()); derive(d); return d;
    }
    GeneralBp3RuntimeTest fixture() throws Exception {
        var f = new GeneralBp3RuntimeTest(); f.root = Files.createDirectory(root.resolve("runtime")); f.setup(); return f;
    }
    ObjectNode run(GeneralBp3RuntimeTest f, ObjectNode d) throws Exception {
        var engine = new GeneralBp3Runtime(source.mapper, f.facts, new Bp3ProteinRegionAssessment(source.mapper), f.guard(true), source.load(d));
        return engine.evaluate(f.variant.data.context, f.variant.data.candidate, source.mapper.createObjectNode());
    }
    void save(String name, ObjectNode out) throws Exception {
        String directory = System.getProperty("varvis.bp3.published.artifacts");
        if (directory != null) source.mapper.writeValue(Files.createDirectories(Path.of(directory)).resolve(name + ".json").toFile(), out);
    }

    @Test void verifiedSequenceAndSourceRegionWorkWithoutGenericAnnotationLookupOrPerVariantReview() throws Exception {
        var f = fixture(); var d = runtimeDocument(document(), f);
        // Published source itself supplies the rule. Do not consume unrelated feature files.
        Files.writeString(f.root.resolve("combined.json"), "changed-unread");
        var candidateBefore = source.mapper.valueToTree(f.variant.data.candidate); var contextBefore = f.variant.data.context.deepCopy();
        var out = run(f, d); assertEquals("MET", out.path("status").asText(), out.toPrettyString());
        assertEquals("BP3_PUBLISHED_REPEAT_REGION_MATCHED", out.path("reasonCode").asText());
        assertFalse(out.has("proteinAnnotations")); assertFalse(out.has("regionSupply"));
        assertEquals(d.at("/specifications/0"), out.at("/knowledgeSelection/specification"));
        assertEquals(candidateBefore, source.mapper.valueToTree(f.variant.data.candidate)); assertEquals(contextBefore, f.variant.data.context);
        assertArrayEquals(source.mapper.writeValueAsBytes(out), source.mapper.writeValueAsBytes(source.mapper.readTree(source.mapper.writeValueAsBytes(out))));
        save("MET", out);
        f.variant.data.variant(105,"ACCT","A","7_9del","Pro3del");
        var second = run(f,d); assertEquals("MET",second.path("status").asText(),second.toPrettyString());
        assertEquals(out.at("/knowledgeSelection/record"),second.at("/knowledgeSelection/record"));
        assertEquals(-1,second.at("/publicSummary/netAminoAcidChange").asInt()); save("MET_SECOND",second);
    }
    @Test void equivalentLeftAlignedInsertionStillRequiresCompleteOriginalAnnotationAgreement() throws Exception {
        var f = fixture(); var d = runtimeDocument(document(), f);
        // Inserting CCT before the original CCT is equivalent to duplicating it after:
        // ATG GAA CCT GGC ... -> ATG GAA CCT CCT GGC ... .
        f.variant.data.variant(105,"A","ACCT","7_9dup","Pro3dup");
        var good = run(f,d);
        assertEquals("MET", good.path("status").asText(), good.toPrettyString());
        assertEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", good.path("annotationCorrespondence").asText());
        assertTrue(good.at("/sequenceMaterial/alleleAssociation/hgvscSequenceEquivalent").asBoolean());
        assertTrue(good.at("/sequenceMaterial/alleleAssociation/hgvspSequenceEquivalent").asBoolean());
        f.variant.data.variant(105,"A","ACCT","6_7insGCT","Glu2_Pro3insAla");
        var bad = run(f,d);
        assertEquals("NOT_EVALUATED", bad.path("status").asText());
        assertEquals("PM4_HGVSC_SEQUENCE_MISMATCH", bad.path("reasonCode").asText());
        assertFalse(bad.has("annotationCorrespondence"));
        assertFalse(bad.has("knowledgeSelection"));
    }

    @Test void wholeEnvelopeRatherThanConvenientAlignmentMustBeCovered() throws Exception {
        var f = fixture(); var d = runtimeDocument(document(), f);
        ((ObjectNode)d.at("/specifications/0/original/ld/CriteriaCode/0/entContent/strengthDescriptor/0")).put("text", TEXT.replace("2-5", "3-5"));
        derive(d); var out = run(f,d); assertEquals("NOT_MET", out.path("status").asText(), out.toPrettyString());
        assertEquals("BP3_OUTSIDE_PUBLISHED_ELIGIBLE_REGION",out.path("reasonCode").asText()); save("OUTSIDE",out);
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE_CONFLICT", "POPULATION", "UNAPPROVED", "GENERIC", "NEW_CONDITION", "NOT_APPLICABLE"})
    void unsupportedOrConflictingKnowledgeNeverFallsBack(String kind) throws Exception {
        var f = fixture(); var d = runtimeDocument(document(),f); var clause = (ObjectNode)d.at("/specifications/0/original/ld/CriteriaCode/0/entContent");
        var strength=(ObjectNode)clause.at("/strengthDescriptor/0");
        switch(kind) {
            case "SOURCE_CONFLICT" -> clause.put("additionalComments", "Not applicable at this time.");
            case "POPULATION" -> clause.put("additionalComments", "Other in-frame variants require BA1 frequencies.");
            case "UNAPPROVED" -> strength.put("status", "not approved");
            case "GENERIC" -> strength.put("text", "Use with no specification.");
            case "NEW_CONDITION" -> strength.put("text", TEXT+" Extra requirement.");
            case "NOT_APPLICABLE" -> { clause.put("applicability", "Not applicable"); clause.putArray("strengthDescriptor"); }
            default -> fail();
        }
        derive(d); var out = run(f,d); assertEquals("NOT_EVALUATED",out.path("status").asText(),out.toPrettyString());
        assertFalse(out.has("proteinAnnotations")); assertFalse(out.has("regionAssessment")); save(kind,out);
    }
    @ParameterizedTest @ValueSource(strings={"RANGE", "REASON", "STATUS", "TRANSCRIPT", "STRENGTH", "ADDITIONAL_TEXT"})
    void editedInterpretationCannotOverrideFullSource(String edit) throws Exception {
        var d=document(); var row=(ObjectNode)d.at("/records/0");
        switch(edit) {
            case "RANGE" -> ((ObjectNode)row.path("interpretation")).put("start",1);
            case "REASON" -> row.put("reasonCode","FAKE_REASON");
            case "STATUS" -> row.put("status","NOT_APPLICABLE");
            case "TRANSCRIPT" -> ((ObjectNode)row.path("scope")).put("transcript","NM_000001.3");
            case "STRENGTH" -> ((ObjectNode)row.path("interpretation")).put("strength","STRONG");
            case "ADDITIONAL_TEXT" -> { ((ObjectNode)d.at("/specifications/0/original/ld/CriteriaCode/0/entContent")).put("additionalComments","Requires experiment."); source.refresh(d); }
            default -> fail();
        }
        assertThrows(IllegalArgumentException.class, () -> source.load(d));
    }
    @ParameterizedTest @ValueSource(strings={"X_LINKED_DOMINANT","X_LINKED_RECESSIVE","AUTOSOMAL_DOMINANT","UNKNOWN"})
    void broaderSourceTaxonomyOnlyMatchesExplicitXLinkedContexts(String moi) throws Exception {
        var d=document(); ((ObjectNode)d.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0/diseases/0"))
                .put("preferredModeOfInheritance","X-linked inheritance");
        ((ObjectNode)d.at("/records/0/scope")).put("moi","X_LINKED"); source.refresh(d);
        var result=source.load(d).select(GeneralBp3Runtime.CRITERION,source.context().put("moi",moi),source.model());
        assertEquals(moi.startsWith("X_LINKED_") ? "READY" : "UNAVAILABLE",result.path("status").asText());
    }
    @Test void sourceTranscriptVersionAndDiseaseCannotBeBorrowed() throws Exception {
        var f=fixture(); var d=runtimeDocument(document(),f);
        ((ObjectNode)d.at("/records/0/scope")).put("transcript","ENST000001.2");
        ((ObjectNode)d.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0")).put("preferredTranscript","ENST000001.2");
        derive(d); var out=run(f,d); assertEquals("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED",out.path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"NO_ENVELOPE", "FRACTIONAL_RANGE", "MODEL_TOO_SHORT", "WRONG_SEQUENCE"})
    void sourceRegionCannotReplaceVerifiedSequenceAndCompleteEnvelope(String missing) throws Exception {
        var d=document(); var selection=source.load(d).select(GeneralBp3Runtime.CRITERION,source.context(),source.model());
        var f=fixture(); var material=f.facts.resolveSequence(f.variant.data.context,f.variant.data.candidate).material();
        var a=(ObjectNode)material.snapshot().path("alleleAssociation").deepCopy(); var model=java.util.Objects.requireNonNull(material.model()).deepCopy();
        switch(missing) {
            case "NO_ENVELOPE" -> a.remove("equivalentEditEnvelopeStatus");
            case "FRACTIONAL_RANGE" -> ((ObjectNode)a.path("equivalentEditEnvelope")).put("proteinStart",1.2);
            case "MODEL_TOO_SHORT" -> model.put("proteinLength",5);
            case "WRONG_SEQUENCE" -> a.remove("sequenceDerivationStatus");
            default -> fail();
        }
        var result=PublishedBp3RegionAssessment.assess(selection,model,a);
        assertEquals("UNKNOWN",result.path("regionState").asText(),result.toPrettyString());
    }
    @Test void fixedRealBatchHasExactlyOneSourceEligibleRegionAndRetainsContradictions() throws Exception {
        String file=System.getProperty("varvis.bp3.published.knowledge");
        org.junit.jupiter.api.Assumptions.assumeTrue(file != null);
        var d=(ObjectNode)source.mapper.readTree(Path.of(file).toFile()); source.root=root;
        var k=source.load(d); int executable=0; boolean vhl=false,foxg1=false;
        for(var row:d.path("records")) {
            if("EXECUTABLE".equals(row.path("status").asText())) { executable++; assertEquals("GN106@1.0",row.path("specificationKey").asText()); }
            if("GN078@1.1".equals(row.path("specificationKey").asText())) { vhl=true; assertEquals("SOURCE_CONFLICT",row.path("status").asText()); }
            if("GN035@6.0".equals(row.path("specificationKey").asText())) { foxg1=true; assertEquals("BP3_OTHER_INFRAME_POPULATION_EVIDENCE_REQUIRED",row.path("reasonCode").asText()); }
        }
        assertEquals(1,executable); assertTrue(vhl); assertTrue(foxg1); assertFalse(k.identity().path("createsReviewReceipt").asBoolean(true));
    }
}
