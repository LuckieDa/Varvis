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

public class Pvs1ApplicabilitySourcesTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final String factSha = "a".repeat(64);

    ObjectNode document(String hgnc, String mondo, String mode) throws Exception {
        var original = (ObjectNode) mapper.readTree("""
          {"entId":"GN900","entContent":{"version":"1.0.0","states":[{"name":"Released","current":true}],"notes":"TEST_ONLY region restriction"},
           "ld":{"Gene":[{"entContent":{"HGNC":{"symbol":"TEST","hgnc_id":"HGNC:123"}}}],
           "RuleSet":[{"entContent":{"genes":[{"gene":"TEST","diseases":[{"preferredMondoId":"MONDO:0000001","preferredModeOfInheritance":"Autosomal dominant inheritance"}]}]}}],
           "CriteriaCode":[{"entContent":{"label":"PVS1","gene":["TEST"],"disease":["MONDO:0000001"],"instructionsToUse":"NM_000001.2 example, not transcript selection","strengthDescriptor":[{"applicability":"Not applicable","strength":"Very Strong","text":"Do not apply"}]}},
                           {"entContent":{"label":"PS3","additionalComments":"TEST_ONLY cannot combine"}}],
           "File":[{"fileName":"tree.pdf","url":"https://example.org/tree.pdf"}]}}
          """);
        ((ObjectNode) original.at("/ld/Gene/0/entContent/HGNC")).put("hgnc_id", hgnc);
        ((ObjectNode) original.at("/ld/RuleSet/0/entContent/genes/0/diseases/0")).put("preferredMondoId", mondo)
                .put("preferredModeOfInheritance", mode.equals("AUTOSOMAL_DOMINANT") ? "Autosomal dominant inheritance" : "Autosomal recessive inheritance");
        ((ObjectNode) original.at("/ld/CriteriaCode/0/entContent")).putArray("disease").add(mondo);
        var doc = mapper.createObjectNode().put("schemaVersion", "pvs1-applicability-sources-v1").put("methodVersion", "1.0.0")
                .put("status", "SOURCE_FACTS_ONLY").put("runtimeEligible", false).put("clinicalAcceptance", false).put("mechanismCandidatesRawSha256", factSha);
        var spec = doc.putArray("specifications").addObject().put("specificationKey", "GN900@1.0.0").put("runtimeEligible", false);
        spec.set("original", original);
        spec.putObject("source").put("rawSha256", "b".repeat(64)).put("jsonPointer", "/data/0").put("sourceUrl", "https://example.org/GN900");
        var entry = doc.putArray("entries").addObject().put("specificationKey", "GN900@1.0.0").put("scopeStatus", "EXACT")
                .put("geneSymbol", "TEST").put("scopePointer", "/ld/RuleSet/0/entContent/genes/0/diseases/0")
                .put("clausePointer", "/ld/CriteriaCode/0").put("clauseScopeRelation", "EXPLICIT_GENE_AND_DISEASE");
        entry.putObject("scope").put("hgncId", hgnc).put("mondoId", mondo).put("moi", mode);
        refresh(doc); return doc;
    }
    void refresh(ObjectNode doc) {
        var spec = (ObjectNode) doc.at("/specifications/0"); var original = spec.path("original");
        spec.put("entitySha256", adapter.canonicalSha256(original));
        var e = (ObjectNode) doc.at("/entries/0");
        e.put("clauseSha256", adapter.canonicalSha256(original.at("/ld/CriteriaCode/0")));
        e.set("rawGeneAssociation", original.at("/ld/RuleSet/0/entContent/genes/0").deepCopy());
        e.remove("entryId");e.put("entryId", "pvs1-source-"+adapter.canonicalSha256(e));
    }
    Pvs1ApplicabilitySources load(ObjectNode doc) throws Exception {
        var raw = mapper.writeValueAsBytes(doc);Path path=root.resolve("sources.json");Files.write(path,raw);
        return Pvs1ApplicabilitySources.load(mapper,path,CodingTranscriptMaterialSupply.sha(raw),factSha);
    }
    ObjectNode context(String gene, String disease, String mode) {
        var c=mapper.createObjectNode().put("moi",mode);c.putObject("gene").put("hgncId",gene);c.putObject("disease").put("mondoId",disease);return c;
    }

    @Test void exactSourcePreservesNegativeStrengthOtherClausesAndAttachments() throws Exception {
        var doc=document("HGNC:123","MONDO:0000001","AUTOSOMAL_DOMINANT");var sources=load(doc);
        var out=sources.describe(context("HGNC:123","MONDO:0000001","AUTOSOMAL_DOMINANT"));
        assertEquals("EXACT_GENE_DISEASE_INHERITANCE",out.at("/details/0/relation").asText());
        assertEquals(doc.at("/specifications/0/original"),out.at("/details/0/specification/original"));
        assertFalse(out.path("runtimeEligible").asBoolean(true));assertFalse(out.path("clinicalAcceptance").asBoolean(true));
        out.removeAll();doc.removeAll();assertEquals(1,sources.describe(context("HGNC:123","MONDO:0000001","AUTOSOMAL_DOMINANT")).path("details").size());
    }
    @Test void otherDiseaseDoesNotBorrowAndOtherInheritanceRemainsExplicit() throws Exception {
        var s=load(document("HGNC:123","MONDO:0000001","AUTOSOMAL_DOMINANT"));
        assertEquals(0,s.describe(context("HGNC:123","MONDO:0000002","AUTOSOMAL_DOMINANT")).path("details").size());
        assertEquals(0,s.describe(context("HGNC:999","MONDO:0000001","AUTOSOMAL_DOMINANT")).path("details").size());
        assertEquals("DIFFERENT_INHERITANCE_NOT_APPLICABLE",s.describe(context("HGNC:123","MONDO:0000001","AUTOSOMAL_RECESSIVE")).at("/details/0/relation").asText());
    }
    @Test void missingInheritanceAndMissingClauseDiseaseAreNotDefaultedToApproval() throws Exception {
        var doc=document("HGNC:123","MONDO:0000001","AUTOSOMAL_DOMINANT");
        ((ObjectNode)doc.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0/diseases/0")).remove("preferredModeOfInheritance");
        ((ObjectNode)doc.at("/entries/0/scope")).put("moi","");((ObjectNode)doc.at("/entries/0")).put("scopeStatus","INHERITANCE_NOT_PROVIDED");
        ((ObjectNode)doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent")).remove("disease");
        ((ObjectNode)doc.at("/entries/0")).put("clauseScopeRelation","RULESET_DISEASE_CONTEXT_ONLY");refresh(doc);
        var out=load(doc).describe(context("HGNC:123","MONDO:0000001","AUTOSOMAL_DOMINANT"));
        assertEquals("SCOPE_REQUIRES_INTERPRETATION",out.at("/details/0/relation").asText());
    }
    @ParameterizedTest @ValueSource(strings={"RAW_SOURCE_SET","PROMOTED","ENTITY","ENTRY","SCOPE","CLAUSE","DUPLICATE","DRAFT"})
    void alteredOrPromotedSourcesCannotLoad(String change) throws Exception {
        var doc=document("HGNC:123","MONDO:0000001","AUTOSOMAL_DOMINANT");
        switch(change) {
            case "RAW_SOURCE_SET" -> doc.put("mechanismCandidatesRawSha256","0".repeat(64));
            case "PROMOTED" -> doc.put("runtimeEligible",true);
            case "ENTITY" -> ((ObjectNode)doc.at("/specifications/0/original/entContent")).put("notes","edited");
            case "ENTRY" -> ((ObjectNode)doc.at("/entries/0")).put("scopeStatus","UNRESTRICTED");
            case "SCOPE" -> { ((ObjectNode)doc.at("/entries/0/scope")).put("mondoId","MONDO:0000002");refresh(doc); }
            case "CLAUSE" -> { ((ObjectNode)doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent")).put("label","BA1");refresh(doc); }
            case "DUPLICATE" -> doc.withArray("entries").add(doc.at("/entries/0").deepCopy());
            case "DRAFT" -> { ((ObjectNode)doc.at("/specifications/0/original/entContent/states/0")).put("name","Draft");refresh(doc); }
        }
        assertThrows(IllegalArgumentException.class,()->load(doc));
    }
    @Test void rawDigestDuplicateJsonAndTrailingJsonAreRejected() throws Exception {
        var path=root.resolve("bad.json");Files.writeString(path,"{}");
        assertThrows(IllegalArgumentException.class,()->Pvs1ApplicabilitySources.load(mapper,path,"0".repeat(64),factSha));
        for(String text:List.of("{\"entries\":[],\"entries\":[]}","{} {}")) {
            Files.writeString(path,text);String sha=CodingTranscriptMaterialSupply.sha(Files.readAllBytes(path));
            assertThrows(Exception.class,()->Pvs1ApplicabilitySources.load(mapper,path,sha,factSha));
        }
    }
    @Test void supplementIsVisibleOnMissingAdoptionButNeverChangesDecision() throws Exception {
        var input=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31);var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(input.policy));
        var use=Pvs1KnowledgeCatalogTest.adoption(mapper,facts,factSha,List.of(input.policy));use.putArray("entries");
        var doc=document(input.context.at("/gene/hgncId").asText(),input.context.at("/disease/mondoId").asText(),input.context.path("moi").asText());
        var before=new Pvs1KnowledgeCatalog(mapper,facts,factSha,use,"c".repeat(64));
        var after=new Pvs1KnowledgeCatalog(mapper,facts,factSha,use,"c".repeat(64),load(doc));
        var a=before.assess(input.context,input.policy);var b=after.assess(input.context,input.policy);
        assertEquals(a.path("status"),b.path("status"));assertEquals(a.path("reasonCode"),b.path("reasonCode"));
        assertEquals("PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING",b.path("reasonCode").asText());
        assertEquals(1,b.at("/applicabilitySources/details").size());assertEquals("inferred",b.at("/mechanismEvidenceDetails/0/molecular mechanism support").asText());
        assertNotEquals(before.identity(),after.identity());assertFalse(a.has("applicabilitySources"));
    }
}
