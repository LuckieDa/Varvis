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
import org.junit.jupiter.params.provider.ValueSource;

class PublishedCriterionKnowledgeTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    static final String CRITERION = "ACMG_AMP_2015:PM1";

    ObjectNode document() throws Exception {
        var doc = (ObjectNode) mapper.readTree("""
          {"schemaVersion":"published-criterion-knowledge-v1","methodVersion":"1.0.0",
           "adoptionMode":"PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS","clinicalValidationCompleted":false,
           "policyId":"TEST_ONLY","policyVersion":"1.0.0","specifications":[{
           "specificationKey":"GN900@1.0","source":{"rawSha256":"placeholder","jsonPointer":"/data/0","sourceUrl":"https://example.org/spec"},
           "original":{"entId":"GN900","entContent":{"version":"1.0","states":[{"current":true,"name":"Released"}]},
            "ld":{"Gene":[{"entContent":{"HGNC":{"symbol":"TEST","hgnc_id":"HGNC:123"}}}],
             "RuleSet":[{"entContent":{"genes":[{"gene":"TEST","preferredTranscript":"NM_000001.2",
              "diseases":[{"preferredMondoId":"MONDO:0000001","preferredModeOfInheritance":"Autosomal dominant inheritance"}]}]}}],
             "CriteriaCode":[{"entContent":{"label":"PM1","gene":["TEST"],"disease":["MONDO:0000001"],
               "applicability":"Applicable","text":"TEST_ONLY region; retain all restrictions"}},
               {"entContent":{"label":"PS3","text":"TEST_ONLY cannot combine"}}],
             "File":[{"fileName":"attachment.pdf","url":"https://example.org/attachment.pdf"}]}}}],
           "records":[{"recordId":"test-1","criterionId":"ACMG_AMP_2015:PM1","specificationKey":"GN900@1.0",
            "clausePointer":"/ld/CriteriaCode/0","scopePointer":"/ld/RuleSet/0/entContent/genes/0/diseases/0",
            "scope":{"hgncId":"HGNC:123","mondoId":"MONDO:0000001","moi":"AUTOSOMAL_DOMINANT","assembly":"GRCh38","transcript":"NM_000001.2"},
            "status":"EXECUTABLE","reasonCode":"TEST_SOURCE_MATCHED","interpretation":{"type":"TEST_ONLY","version":"1.0.0"}}]}
          """);
        ((ObjectNode) doc.at("/specifications/0/source")).put("rawSha256", "a".repeat(64)); refresh(doc); return doc;
    }
    void refresh(ObjectNode doc) {
        var s = (ObjectNode) doc.at("/specifications/0"); var e = (ObjectNode) doc.at("/records/0");
        s.put("entitySha256", adapter.canonicalSha256(s.path("original")));
        e.put("clauseSha256", adapter.canonicalSha256(s.path("original").at(e.path("clausePointer").asText())));
    }
    PublishedCriterionKnowledge load(ObjectNode doc) throws Exception {
        var bytes = mapper.writeValueAsBytes(doc); Path file = root.resolve("knowledge.json"); Files.write(file, bytes);
        return PublishedCriterionKnowledge.load(mapper, file, CodingTranscriptMaterialSupply.sha(bytes));
    }
    ObjectNode context() {
        var c = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        c.putObject("gene").put("hgncId", "HGNC:123"); c.putObject("disease").put("mondoId", "MONDO:0000001");
        c.putObject("transcript").put("accession", "NM_000001").put("version", "2");
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38"); return c;
    }
    ObjectNode model() { return mapper.createObjectNode().put("hgncId", "HGNC:123").put("assembly", "GRCh38").put("transcript", "NM_000001.2"); }
    @Test void sourceAndProjectInterpretationStaySeparateAndFullOriginalIsPreserved() throws Exception {
        var d = document(); var k = load(d); var r = k.select(CRITERION, context(), model());
        assertEquals("READY", r.path("status").asText()); assertFalse(r.has("evaluation")); assertFalse(r.has("review"));
        assertEquals(d.at("/specifications/0"), r.path("specification")); assertEquals(d.at("/records/0"), r.path("record"));
        assertFalse(k.identity().path("createsReviewReceipt").asBoolean(true)); assertFalse(r.path("clinicalValidationCompleted").asBoolean(true));
        r.removeAll(); d.removeAll(); assertEquals("READY", k.select(CRITERION, context(), model()).path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings={"GENE", "DISEASE", "MOI", "TRANSCRIPT", "MODEL", "ASSEMBLY", "CRITERION"})
    void otherContextsCannotBorrowKnowledge(String which) throws Exception {
        var c = context(); var m = model(); String criterion = CRITERION;
        switch (which) {
            case "GENE" -> ((ObjectNode)c.path("gene")).put("hgncId", "HGNC:999");
            case "DISEASE" -> ((ObjectNode)c.path("disease")).put("mondoId", "MONDO:0000002");
            case "MOI" -> c.put("moi", "AUTOSOMAL_RECESSIVE");
            case "TRANSCRIPT" -> { ((ObjectNode)c.path("transcript")).put("version", "3"); m.put("transcript", "NM_000001.3"); }
            case "MODEL" -> m.put("transcript", "NM_000001.3");
            case "ASSEMBLY" -> { ((ObjectNode)c.at("/variant/evidenceQueryAllele")).put("assembly", "GRCh37"); m.put("assembly", "GRCh37"); }
            case "CRITERION" -> criterion = "ACMG_AMP_2015:BA1";
        }
        var out = load(document()).select(criterion,c,m); assertEquals("UNAVAILABLE",out.path("status").asText()); assertFalse(out.has("record"));
        if("TRANSCRIPT".equals(which)) {
            assertEquals("NM_000001.3",out.path("executionTranscript").asText());
            assertEquals("NM_000001.2",out.at("/backgroundTranscriptSources/0/declaredTranscript").asText());
            assertEquals("a".repeat(64),out.at("/backgroundTranscriptSources/0/source/rawSha256").asText());
            assertEquals("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED",out.path("reasonCode").asText());
        }
        if(List.of("GENE","DISEASE","MOI").contains(which))
            assertTrue(out.path("backgroundTranscriptSources").isEmpty(),"Never suggest a transcript from another background");
    }
    @ParameterizedTest @ValueSource(strings={"CLINICAL", "ENTITY", "CLAUSE", "UNRELEASED", "MOI", "MOI_SOURCE_CONFLICT", "GENE", "DISEASE", "WRONG_CRITERION", "MENTION_ONLY", "APPLICABILITY", "DUPLICATE_ID"})
    void forgedScopePromotedSourceAndUnreleasedSpecAreRejected(String which) throws Exception {
        var d = document(); var r = (ObjectNode) d.at("/records/0"); var original = (ObjectNode)d.at("/specifications/0/original");
        switch (which) {
            case "CLINICAL" -> d.put("clinicalValidationCompleted",true);
            case "ENTITY" -> original.put("changed",true);
            case "CLAUSE" -> r.put("clauseSha256","0".repeat(64));
            case "UNRELEASED" -> { ((ObjectNode)original.at("/entContent/states/0")).put("name","Draft"); refresh(d); }
            case "MOI" -> { ((ObjectNode)r.path("scope")).put("moi","AUTOSOMAL_RECESSIVE"); refresh(d); }
            case "MOI_SOURCE_CONFLICT" -> { ((ObjectNode)original.at("/ld/RuleSet/0/entContent/genes/0/diseases/0"))
                    .putArray("modesOfInheritance").addObject().put("modeOfInheritance","Autosomal recessive inheritance"); refresh(d); }
            case "GENE" -> { ((ObjectNode)r.path("scope")).put("hgncId","HGNC:999"); refresh(d); }
            case "DISEASE" -> { ((ObjectNode)r.path("scope")).put("mondoId","MONDO:0000002"); refresh(d); }
            case "WRONG_CRITERION" -> { r.put("criterionId","ACMG_AMP_2015:PP2"); refresh(d); }
            case "MENTION_ONLY" -> { ((ObjectNode)original.at("/ld/RuleSet/0/entContent/genes/0")).remove("preferredTranscript"); original.put("notes","NM_000001.2 is an example"); refresh(d); }
            case "APPLICABILITY" -> { ((ObjectNode)original.at("/ld/CriteriaCode/0/entContent")).put("applicability","Not applicable"); refresh(d); }
            case "DUPLICATE_ID" -> d.withArray("records").add(r.deepCopy());
        }
        assertThrows(IllegalArgumentException.class,()->load(d));
    }
    @Test void competingRecordsDoNotChooseFirstOrHighestStrength() throws Exception {
        var d = document(); var duplicate = d.at("/records/0").deepCopy(); ((ObjectNode)duplicate).put("recordId","test-2");
        d.withArray("records").add(duplicate);
        var result=load(d).select(CRITERION,context(),model());
        assertEquals("PUBLISHED_KNOWLEDGE_MULTIPLE_POLICIES_REQUIRE_SELECTION",result.path("reasonCode").asText()); assertFalse(result.has("record"));
    }
    ObjectNode bridgedModel() {
        var m=model().put("transcript","ENST000001.4").put("protein","ENSP000001.3").put("cdsSequence","ATGAAATAA");
        m.putObject("transcriptBridge").put("status","FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE").put("refseqTranscript","NM_000001.2")
                .put("refseqProtein","NP_000001.1").put("ensemblTranscript","ENST000001.4").put("ensemblProtein","ENSP000001.3");
        m.putObject("sequenceVerification").put("fullSplicedTranscriptSha256",CodingTranscriptMaterialSupply.sha("ATGAAATAA"))
                .put("cdsSha256",CodingTranscriptMaterialSupply.sha("ATGAAATAA")).put("fastaIndexSha256","a".repeat(64)).put("queriedBases",9);
        m.putArray("exons").addObject().put("genomicStart",1).put("genomicEnd",9);
        var sources=m.putArray("sourceRecords");
        sources.addObject().put("sourceId","RefSeq:NM_000001.2").put("sourceKind","NCBI_VERSIONED_REFSEQ_GENBANK")
                .put("version","NM_000001.2").put("url","https://example.org/refseq").put("sha256","b".repeat(64));
        sources.addObject().put("sourceId","Ensembl:ENST000001.4").put("version","ENST000001.4")
                .put("url","https://example.org/ensembl").put("sha256","c".repeat(64)); return m;
    }
    @Test void exactExistingFullBridgePreservesBothIdentities() throws Exception {
        var c=context();((ObjectNode)c.path("transcript")).put("accession","ENST000001").put("version","4");
        var m=bridgedModel();var out=load(document()).select(CRITERION,c,m);
        assertEquals("READY",out.path("status").asText(),out.toString());assertEquals("NM_000001.2",out.path("declaredTranscript").asText());
        assertEquals("ENST000001.4",out.path("executionTranscript").asText());assertEquals(m.path("transcriptBridge"),out.path("transcriptBridge"));
        assertEquals(adapter.canonicalSha256(m),out.path("verifiedModelSha256").asText());
    }
    @ParameterizedTest @ValueSource(strings={"BARE_MANE", "REFSEQ_VERSION", "PROTEIN", "CDS", "EXONS", "RNA_SOURCE", "MAPPING_SOURCE"})
    void aliasWithoutExactFullSequenceProofCannotSelect(String change) throws Exception {
        var c=context();((ObjectNode)c.path("transcript")).put("accession","ENST000001").put("version","4");
        var m=bridgedModel();
        switch(change) {
            case "BARE_MANE" -> m.remove("sequenceVerification");
            case "REFSEQ_VERSION" -> ((ObjectNode)m.path("transcriptBridge")).put("refseqTranscript","NM_000001.3");
            case "PROTEIN" -> m.put("protein","ENSP000001.4");
            case "CDS" -> m.put("cdsSequence","ATGAGATAA");
            case "EXONS" -> ((ObjectNode)m.at("/exons/0")).put("genomicEnd",8);
            case "RNA_SOURCE" -> ((ObjectNode)m.at("/sourceRecords/0")).put("sourceKind","MANE_NAME_ONLY");
            case "MAPPING_SOURCE" -> ((ObjectNode)m.at("/sourceRecords/1")).put("sourceId","Ensembl:ENST000001.5");
        }
        assertEquals("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED",load(document()).select(CRITERION,c,m).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"NOT_APPLICABLE", "UNSUPPORTED_CONDITIONS", "SOURCE_CONFLICT"})
    void blockedRecordsRemainExplanationsWithFullClause(String status) throws Exception {
        var d=document();((ObjectNode)d.at("/records/0")).put("status",status).put("reasonCode","TEST_BLOCKED");
        if ("NOT_APPLICABLE".equals(status)) {
            ((ObjectNode) d.at("/specifications/0/original/ld/CriteriaCode/0/entContent"))
                    .put("applicability", "Not applicable").putArray("strengthDescriptor");
            refresh(d);
        }
        var out=load(d).select(CRITERION,context(),model());assertEquals("BLOCKED",out.path("status").asText());
        assertEquals(d.at("/specifications/0"),out.path("specification"));assertEquals("TEST_BLOCKED",out.path("reasonCode").asText());
        assertEquals(status, out.path("sourceDisposition").asText());
        assertEquals("TEST_BLOCKED", out.path("sourceReasonCode").asText());
        assertEquals("/ld/CriteriaCode/0", out.path("sourceClausePointer").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"AD", "AR"})
    void finiteInheritanceSynonymsPreserveExactOriginal(String inheritance) throws Exception {
        var d = document();
        var disease = (ObjectNode) d.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0/diseases/0");
        boolean dominant = "AD".equals(inheritance);
        String canonical = dominant ? "AUTOSOMAL_DOMINANT" : "AUTOSOMAL_RECESSIVE";
        disease.put("preferredModeOfInheritance", dominant ? "Autosomal dominant inheritance" : "Autosomal recessive inheritance")
                .putArray("modesOfInheritance").addObject().put("modeOfInheritance", dominant ? "Autosomal Dominant" : "Autosomal Recessive");
        ((ObjectNode) d.at("/records/0/scope")).put("moi", canonical); refresh(d);
        var result = load(d).select(CRITERION, context().put("moi", canonical), model());
        assertEquals("READY", result.path("status").asText());
        assertEquals(d.at("/specifications/0"), result.path("specification"));
    }

    @ParameterizedTest @ValueSource(strings = {"CONFLICT", "UNKNOWN", "NULL", "NON_ARRAY"})
    void inheritanceCanonicalizationDoesNotAuthorizeMissingOrContradictorySource(String fault) throws Exception {
        var d = document();
        var disease = (ObjectNode) d.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0/diseases/0");
        if ("NON_ARRAY".equals(fault)) disease.putNull("modesOfInheritance");
        else {
            var mode = disease.putArray("modesOfInheritance").addObject();
            if ("NULL".equals(fault)) mode.putNull("modeOfInheritance");
            else mode.put("modeOfInheritance", "CONFLICT".equals(fault) ? "Autosomal Recessive" : "AD");
        }
        refresh(d); assertThrows(IllegalArgumentException.class, () -> load(d));
    }

    @ParameterizedTest @ValueSource(strings = {"APPLICABLE", "ACTIVE_STRENGTH", "MALFORMED_STRENGTH"})
    void nonapplicableDispositionMustBeProvenByOriginalClause(String fault) throws Exception {
        var d = document(); ((ObjectNode) d.at("/records/0")).put("status", "NOT_APPLICABLE");
        var clause = (ObjectNode) d.at("/specifications/0/original/ld/CriteriaCode/0/entContent");
        if (!"APPLICABLE".equals(fault)) {
            clause.put("applicability", "Not Applicable");
            var strengths = clause.putArray("strengthDescriptor");
            if ("ACTIVE_STRENGTH".equals(fault)) strengths.addObject().put("applicability", "Applicable");
            else strengths.addNull();
        }
        refresh(d); assertThrows(IllegalArgumentException.class, () -> load(d));
    }
    @Test void rawPinDuplicateKeysTrailingJsonAndIdentityChangeAreEnforced() throws Exception {
        Path p=root.resolve("bad.json");Files.writeString(p,"{}");
        assertThrows(IllegalArgumentException.class,()->PublishedCriterionKnowledge.load(mapper,p,"0".repeat(64)));
        for (String raw:List.of("{\"records\":[],\"records\":[]}","{} {}")) {
            Files.writeString(p,raw);String sha=CodingTranscriptMaterialSupply.sha(Files.readAllBytes(p));
            assertThrows(Exception.class,()->PublishedCriterionKnowledge.load(mapper,p,sha));
        }
        var d=document();var before=load(d).identity();d.put("policyVersion","1.0.1");assertNotEquals(before,load(d).identity());
    }
}
