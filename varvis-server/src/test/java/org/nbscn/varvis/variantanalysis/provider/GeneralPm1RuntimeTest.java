package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneralPm1RuntimeTest {
    @TempDir Path root;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter=new RuntimeEvidenceV2Adapter(mapper);
    CodingTranscriptMaterialSupplyTest sequence;
    CodingTranscriptMaterialSupply supply;
    ObjectNode doc;
    void setup() throws Exception {
        sequence=new CodingTranscriptMaterialSupplyTest();sequence.root=Files.createDirectory(root.resolve("sequence"));supply=sequence.setup();
        var source=new PublishedCriterionKnowledgeTest();source.root=root;doc=source.document();
        var s=(ObjectNode)doc.at("/specifications/0/original");var context=sequence.input.fixture().context();
        ((ObjectNode)s.at("/ld/Gene/0/entContent/HGNC")).put("hgnc_id",context.at("/gene/hgncId").asText());
        var gene=(ObjectNode)s.at("/ld/RuleSet/0/entContent/genes/0");gene.put("preferredTranscript","ENST000001.4");
        ((ObjectNode)gene.at("/diseases/0")).put("preferredMondoId",context.at("/disease/mondoId").asText())
                .put("preferredModeOfInheritance",context.path("moi").asText().equals("AUTOSOMAL_DOMINANT")?"Autosomal dominant inheritance":"Autosomal recessive inheritance");
        var clause=(ObjectNode)s.at("/ld/CriteriaCode/0/entContent");clause.putArray("disease").add(context.at("/disease/mondoId").asText());
        clause.putArray("strengthDescriptor").addObject().put("applicability","Applicable").put("status","approved").put("strength","Moderate").put("text","TEST_ONLY criticalresidueK2");
        var row=(ObjectNode)doc.at("/records/0");var scope=(ObjectNode)row.path("scope");
        scope.put("hgncId",context.at("/gene/hgncId").asText()).put("mondoId",context.at("/disease/mondoId").asText())
                .put("moi",context.path("moi").asText()).put("transcript","ENST000001.4");
        var i=row.putObject("interpretation").put("type",PublishedPm1RegionAssessment.TYPE).put("version","1.0.0")
                .put("qualificationBasis","PUBLISHED_EXPERT_SPECIFICATION").put("consequenceScope","PURE_MISSENSE_SNV");
        i.putObject("proteinIdentity").put("mode","SOURCE_TRANSCRIPT_DEFINED");i.putArray("residualConditions");i.putArray("crossCriterionRelations");i.putArray("precedence");
        var rule=i.putArray("rules").addObject().put("ruleId","testK2").put("start",2).put("end",2).put("regionType","CRITICAL_RESIDUE")
                .put("effect","SUPPORT").put("strength","MODERATE").put("sourcePointer","/ld/CriteriaCode/0/entContent/strengthDescriptor/0")
                .put("sourceSha256",adapter.canonicalSha256(clause.at("/strengthDescriptor/0")));
        rule.putArray("referenceResidues").addObject().put("position",2).put("aminoAcid","K");source.refresh(doc);
    }
    GeneralPm1Runtime engine() throws Exception {
        return engine(null);
    }
    GeneralPm1Runtime engine(Supplier<Pm1ReviewedRegions> reviewed) throws Exception {
        var bytes=mapper.writeValueAsBytes(doc);var file=root.resolve("knowledge.json");Files.write(file,bytes);
        return new GeneralPm1Runtime(mapper,supply,PublishedCriterionKnowledge.load(mapper,file,CodingTranscriptMaterialSupply.sha(bytes)),reviewed);
    }
    ObjectNode run(GeneralPm1Runtime e) {return e.evaluate(sequence.input.fixture().context(),sequence.input.fixture().candidate(),mapper.createObjectNode());}
    @Test void actualSequenceSupplyThenSourceSelectionProduceTraceableResultWithoutAlteringCandidate() throws Exception {
        setup();var e=engine();var before=mapper.valueToTree(sequence.input.fixture().candidate());var out=run(e);
        assertEquals("MET",out.path("status").asText(),out.toPrettyString());assertEquals("MODERATE",out.at("/evaluation/appliedStrength").asText());
        assertEquals(before,mapper.valueToTree(sequence.input.fixture().candidate()));assertEquals(1,e.evaluations());
        assertEquals(doc.at("/specifications/0"),out.at("/knowledgeSelection/specification"));
        for (String field : java.util.List.of("sourceDisposition", "sourceReasonCode", "sourceClausePointer"))
            assertEquals(out.path("knowledgeSelection").path(field), out.path("publicSummary").path(field));
        assertEquals("EXECUTABLE", out.at("/publicSummary/sourceDisposition").asText());
        assertEquals(sequence.model.path("transcript"), out.at("/publicSummary/transcript"));
        assertEquals(sequence.model.path("protein"), out.at("/publicSummary/protein"));
        assertFalse(out.at("/sequenceMaterial/alleleAssociation").has("transcript"));
        assertFalse(out.at("/sequenceMaterial/alleleAssociation").has("protein"));
        var misleadingAssociation = out.deepCopy();
        misleadingAssociation.withObject("/sequenceMaterial/alleleAssociation").put("transcript", "UNVERIFIED").put("protein", "UNVERIFIED");
        assertEquals(out.path("publicSummary"), GeneralPm1Runtime.publicSummary(misleadingAssociation));
        assertFalse(out.path("publicSummary").toString().contains("cdsSequence"));
        var bytes=mapper.writeValueAsBytes(out);assertArrayEquals(bytes,mapper.writeValueAsBytes(mapper.readTree(bytes)));
    }
    @Test void exactReplacementConditionIsConsumedByTheExistingSequenceAndKnowledgeRuntime() throws Exception {
        setup();
        var descriptor = (ObjectNode) doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent/strengthDescriptor/0");
        descriptor.put("text", "Defined to include missense alterations of one catalytic domain residue: K2R (PMID: 1234).");
        var rule = (ObjectNode) doc.at("/records/0/interpretation/rules/0");
        rule.put("sourceSha256", adapter.canonicalSha256(descriptor));
        rule.putArray("exactSubstitutions").addObject().put("position", 2).put("refAminoAcid", "K").put("altAminoAcid", "R");
        new PublishedCriterionKnowledgeTest().refresh(doc);
        var out = run(engine());
        assertEquals("MET", out.path("status").asText(), out::toPrettyString);
        assertEquals("MODERATE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("MATCH", out.at("/regionAssessment/conditionChecks/0/status").asText());
        assertEquals("R", out.at("/publicSummary/altAminoAcid").asText());
        assertEquals(doc.at("/specifications/0/original"), out.at("/knowledgeSelection/specification/original"));
        rule.remove("exactSubstitutions"); new PublishedCriterionKnowledgeTest().refresh(doc);
        var omitted = run(engine());
        assertEquals("NOT_EVALUATED", omitted.path("status").asText());
        assertEquals("PM1_PUBLISHED_EXACT_SUBSTITUTIONS_REQUIRED", omitted.path("reasonCode").asText());
    }
    @Test void annotationOrReferenceFailureStopsBeforeKnowledgeOrRegionEvaluation() throws Exception {
        setup();var e=engine();sequence.reference="ATGAGATAA";var out=run(e);
        assertEquals("TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH",out.path("reasonCode").asText());
        assertFalse(out.has("knowledgeSelection"));assertFalse(out.has("regionAssessment"));
    }
    @Test void backgroundHasNoGeneOnlyFallbackAndSourceConflictStaysBlocked() throws Exception {
        setup();var e=engine();((ObjectNode)sequence.input.fixture().context().path("disease")).put("mondoId","MONDO:0000009");
        assertEquals("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED",run(e).path("reasonCode").asText());
        ((ObjectNode)sequence.input.fixture().context().path("disease")).put("mondoId",doc.at("/records/0/scope/mondoId").asText());
        ((ObjectNode)doc.at("/records/0")).put("status","SOURCE_CONFLICT").put("reasonCode","SOURCE_BODY_COMMENT_CONFLICT");
        var out=run(engine());assertEquals("SOURCE_BODY_COMMENT_CONFLICT",out.path("reasonCode").asText());assertFalse(out.has("regionAssessment"));
        assertEquals("SOURCE_CONFLICT", out.at("/publicSummary/sourceDisposition").asText());
        assertEquals("SOURCE_BODY_COMMENT_CONFLICT", out.at("/publicSummary/sourceReasonCode").asText());
        assertEquals("/ld/CriteriaCode/0", out.at("/publicSummary/sourceClausePointer").asText());
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
    }
    @Test void genuineSourceNonapplicabilityRemainsUnevaluatedWithAnExplicitPublicExplanation() throws Exception {
        setup();
        ((ObjectNode)doc.at("/records/0")).put("status", "NOT_APPLICABLE").put("reasonCode", "PM1_SOURCE_NOT_APPLICABLE");
        assertEquals("PUBLISHED_KNOWLEDGE_SOURCE_NONAPPLICABILITY_REQUIRED", assertThrows(IllegalArgumentException.class, this::engine).getMessage());
        var clause = (ObjectNode)doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent");
        clause.put("applicability", "Not Applicable").put("instructionsToUse", "").put("additionalComments", "");
        ((ObjectNode)clause.at("/strengthDescriptor/0")).put("applicability", "Not Applicable").put("text", "Not applicable.");
        new PublishedCriterionKnowledgeTest().refresh(doc);
        var out = run(engine());
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertFalse(out.has("regionAssessment"));
        assertEquals("BLOCKED", out.at("/knowledgeSelection/status").asText());
        assertEquals("NOT_APPLICABLE", out.at("/publicSummary/sourceDisposition").asText());
        assertEquals("PM1_SOURCE_NOT_APPLICABLE", out.at("/publicSummary/sourceReasonCode").asText());
        assertEquals("/ld/CriteriaCode/0", out.at("/publicSummary/sourceClausePointer").asText());
        assertEquals(doc.at("/specifications/0/original"), out.at("/knowledgeSelection/specification/original"));
    }
    @Test void publicExplanationCopiesOnlyTheNamedTextFieldsWithoutMutatingOrPromotingTheResult() {
        var out = mapper.createObjectNode().put("status", "NOT_EVALUATED");
        var source = out.putObject("knowledgeSelection");
        source.put("sourceDisposition", "EXECUTABLE").putNull("sourceReasonCode").putObject("sourceClausePointer").put("private", "value");
        source.put("privateProof", "DO_NOT_PROJECT");
        var before = out.deepCopy();
        var summary = GeneralPm1Runtime.publicSummary(out);
        assertEquals("EXECUTABLE", summary.path("sourceDisposition").asText());
        assertFalse(summary.has("sourceReasonCode")); assertFalse(summary.has("sourceClausePointer"));
        assertFalse(summary.has("privateProof")); assertFalse(summary.has("status"));
        assertEquals(before, out);
        assertFalse(GeneralPm1Runtime.publicSummary(mapper.createObjectNode()).has("sourceDisposition"));
    }
    @Test void originalSelectedSourceGatePrecedesAnyReferenceOrKnowledgeConsumption() throws Exception {
        setup();var selected=org.mockito.Mockito.mock(org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation.class);
        org.mockito.Mockito.when(selected.legacyProjection("candidate","hypothesis")).thenThrow(new IllegalArgumentException("ORIGINAL_SOURCE_REJECTED"));
        var e=engine();var out=e.evaluate(selected,"candidate","hypothesis");
        assertEquals("ORIGINAL_SOURCE_REJECTED",out.path("reasonCode").asText());assertEquals(0,e.evaluations());assertEquals(0,sequence.bases.get());
        assertFalse(out.has("sequenceMaterial"));assertFalse(out.has("knowledgeSelection"));
    }

    @Test void reviewedRegionUsesVerifiedMissenseAndNeverReplacesAnApplicableExpertRule() throws Exception {
        setup();
        var reviewed = reviewedDocument();
        var regional = loadReviewed(reviewed, true);
        var published = run(engine(() -> { throw new AssertionError("Published PM1 must take priority"); }));
        assertEquals("PM1_PUBLISHED_CRITICAL_LOCATION_MATCH", published.path("reasonCode").asText());
        doc.withArray("records").removeAll();
        var result = run(engine(() -> regional));
        assertEquals("MET", result.path("status").asText(), result::toPrettyString);
        assertEquals("SUPPORTING", result.at("/evaluation/appliedStrength").asText());
        assertEquals("PM1_REVIEWED_REGION_MATCH", result.path("reasonCode").asText());
        assertEquals(1, result.at("/publicSummary/reviewedRegion/independentPathogenicCount").asInt());
        assertEquals("PROJECT_RESEARCH_REVIEWED_REGIONS", result.at("/publicSummary/scope").asText());
        assertEquals("https://example.test/pm1", result.at("/publicSummary/reviewedRegion/sources/0/url").asText());
        var blocked = loadReviewed(reviewed, false);
        assertEquals("PM1_REVIEWED_REGION_SPECIALIZATION_UNRESOLVED", run(engine(() -> blocked)).path("reasonCode").asText());
        ((ObjectNode) reviewed.at("/records/0/scope")).put("mondoId","MONDO:0000009");
        var otherDisease = loadReviewed(reviewed, true);
        assertEquals("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED", run(engine(() -> otherDisease)).path("reasonCode").asText());
    }

    @Test void benignConflictAndTargetOnlyObservationsCannotGivePm1() throws Exception {
        setup();
        doc.withArray("records").removeAll();
        var reviewed = reviewedDocument();
        var observation = (ObjectNode) reviewed.at("/records/0/regions/0/observations/0");
        observation.put("altAminoAcid","R");
        assertReviewedOutcome(reviewed,"NOT_EVALUATED","INDEPENDENT_OBSERVATION_MISSING");
        observation.put("altAminoAcid","Q").put("role","CONFLICTING");
        assertReviewedOutcome(reviewed,"NOT_EVALUATED","OBSERVATION_UNRESOLVED");
        observation.put("role","UNRESOLVED");
        assertReviewedOutcome(reviewed,"NOT_EVALUATED","OBSERVATION_INCOMPLETE");
        observation.put("role","QUALIFIED_BENIGN");
        assertReviewedOutcome(reviewed,"NOT_MET","BENIGN_OBSERVATION");
        observation.put("role","INDEPENDENT_PATHOGENIC");
        ((ObjectNode) reviewed.at("/records/0/regions/0")).put("benignSurvey","INCOMPLETE");
        assertReviewedOutcome(reviewed,"NOT_EVALUATED","BENIGN_SURVEY_INCOMPLETE");
    }

    @Test void qualifiedHotspotCanBeAdoptedWithAnUnresolvedOverlappingDomain() throws Exception {
        setup();
        doc.withArray("records").removeAll();
        var reviewed = reviewedDocument().put("schemaVersion", Pm1ReviewedRegions.QUALIFICATION_SCHEMA);
        var record = (ObjectNode) reviewed.at("/records/0");
        record.putObject("survey").put("start", 1).put("end", 2).put("status", "COMPLETE")
                .put("dataset", "TEST_ONLY snapshot").putArray("sourceIds").add("test");
        var regions = record.putArray("regions");
        regions.addObject().put("regionId", "domain").put("label", "TEST_ONLY domain")
                .put("start", 1).put("end", 2).put("kind", "CRITICAL_FUNCTIONAL_REGION")
                .put("method", "DOCUMENTED_REGION").put("established", false).put("rationale", "TEST_ONLY incomplete review")
                .putArray("sourceIds").add("test");
        regions.addObject().put("regionId", "hotspot").put("label", "TEST_ONLY hotspot")
                .put("start", 2).put("end", 2).put("kind", "MUTATIONAL_HOTSPOT")
                .put("method", "DOCUMENTED_REGION").put("established", true).put("rationale", "TEST_ONLY independent hotspot")
                .putArray("sourceIds").add("test");
        var observations = record.putArray("observations");
        var conflicting = observations.addObject().put("proteinPosition", 1).put("refAminoAcid", "M")
                .put("altAminoAcid", "V").putArray("assertions");
        addAssertion(conflicting.addObject(), "first", "LIKELY_BENIGN", "UNRESOLVED", "UNVERIFIED");
        addAssertion(conflicting.addObject(), "second", "UNCERTAIN_SIGNIFICANCE", "MATCHED", "UNVERIFIED");
        var pathogenic = observations.addObject().put("proteinPosition", 2).put("refAminoAcid", "K")
                .put("altAminoAcid", "Q").putArray("assertions");
        addAssertion(pathogenic.addObject(), "independent", "PATHOGENIC", "MATCHED", "INDEPENDENT");
        addAssertion(pathogenic.addObject(), "another disease", "BENIGN", "DIFFERENT", "INDEPENDENT");
        var regional = loadReviewed(reviewed, true);
        var result = run(engine(() -> regional));
        assertEquals("MET", result.path("status").asText(), result::toPrettyString);
        assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText());
        var proof = result.at("/publicSummary/reviewedRegion");
        assertEquals("hotspot", proof.path("decisionRegionId").asText());
        assertEquals("PM1_REVIEWED_REGION_OBSERVATION_INCOMPLETE", proof.at("/assessments/0/reasonCode").asText());
        assertEquals(0, proof.at("/assessments/0/comparisonCounts/conflictingInside").asInt());
        assertEquals(1, proof.at("/assessments/0/comparisonCounts/unresolvedInside").asInt());
        assertEquals(0, proof.at("/assessments/1/comparisonCounts/conflictingInside").asInt());
        assertEquals("INDEPENDENT_PATHOGENIC", proof.at("/observations/1/role").asText());
        addAssertion(pathogenic.addObject(), "unresolved benign", "LIKELY_BENIGN", "MATCHED", "UNVERIFIED");
        var conflicted = loadReviewed(reviewed, true);
        var blocked = run(engine(() -> conflicted));
        assertEquals("NOT_EVALUATED", blocked.path("status").asText());
        assertEquals("CONFLICTING", blocked.at("/publicSummary/reviewedRegion/observations/1/role").asText());
    }

    private void addAssertion(ObjectNode row, String id, String classification, String scope, String basis) {
        row.put("assertionId", id).put("classification", classification).put("diseaseScope", scope)
                .put("basis", basis).put("rationale", "TEST_ONLY evidence").putArray("sourceIds").add("test");
    }

    @Test void negativeOutsideRegionRequiresReviewedCoverageAndSources() throws Exception {
        setup();
        doc.withArray("records").removeAll();
        var reviewed = reviewedDocument();
        var record = (ObjectNode) reviewed.at("/records/0");
        var region = (ObjectNode) record.at("/regions/0");
        region.put("start",1).put("end",1);
        ((ObjectNode) region.at("/observations/0")).put("proteinPosition",1).put("refAminoAcid","M").put("altAminoAcid","V");
        assertReviewedOutcome(reviewed,"NOT_EVALUATED","POSITION_UNCOVERED");
        record.withArray("reviewedCoverage").addObject().put("start",1).put("end",2).putArray("sourceIds").add("test");
        assertReviewedOutcome(reviewed,"NOT_MET","OUTSIDE_QUALIFIED");
        var file = root.resolve("reviewed.json");
        assertThrows(IllegalArgumentException.class, () -> Pm1ReviewedRegions.load(mapper,file,"0".repeat(64),
                ignored -> mapper.createObjectNode()));
    }

    private void assertReviewedOutcome(ObjectNode document, String status, String reason) throws Exception {
        var material = loadReviewed(document, true);
        var result = run(engine(() -> material));
        assertEquals(status,result.path("status").asText(),result::toPrettyString);
        assertEquals("NONE",result.at("/evaluation/appliedStrength").asText());
        assertEquals("PM1_REVIEWED_REGION_" + reason,result.path("reasonCode").asText());
    }

    private ObjectNode reviewedDocument() {
        var result = mapper.createObjectNode().put("schemaVersion",Pm1ReviewedRegions.SCHEMA);
        var context = sequence.input.fixture().context();
        var record = result.putArray("records").addObject();
        record.putObject("scope").put("hgncId",context.at("/gene/hgncId").asText())
                .put("mondoId",context.at("/disease/mondoId").asText()).put("moi",context.path("moi").asText())
                .put("assembly","GRCh38").put("transcript",sequence.model.path("transcript").asText())
                .put("protein",sequence.model.path("protein").asText());
        record.putArray("sources").addObject().put("sourceId","test").put("title","TEST_ONLY material")
                .put("url","https://example.test/pm1").put("locator","Region and comparator");
        record.putArray("reviewedCoverage");
        var region = record.putArray("regions").addObject().put("regionId","test-region").put("label","TEST_ONLY region")
                .put("start",2).put("end",2).put("kind","CRITICAL_FUNCTIONAL_REGION").put("reviewStatus","QUALIFIED")
                .put("strength","SUPPORTING").put("benignSurvey","COMPLETE").put("reviewRationale","TEST_ONLY independent review");
        region.putArray("sourceIds").add("test");
        region.putArray("observations").addObject().put("proteinPosition",2).put("refAminoAcid","K").put("altAminoAcid","Q")
                .put("role","INDEPENDENT_PATHOGENIC").putArray("sourceIds").add("test");
        return result;
    }

    private Pm1ReviewedRegions loadReviewed(ObjectNode document, boolean general) throws Exception {
        var file = root.resolve("reviewed.json");
        byte[] bytes = mapper.writeValueAsBytes(document);
        Files.write(file, bytes);
        return Pm1ReviewedRegions.load(mapper,file,CodingTranscriptMaterialSupply.sha(bytes),
                ignored -> mapper.createObjectNode().put("status",general?"NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT":"BLOCKED")
                        .put("catalogSha256","a".repeat(64)));
    }

}
