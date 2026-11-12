package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

public class Pvs1ReviewedSourceSupplyTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    /** A TEST_ONLY binding of unchanged, already reviewed toy policies; no real source approval. */
    public static ObjectNode use(ObjectMapper mapper, ObjectNode facts, List<ObjectNode> policies) {
        var use = Pvs1KnowledgeCatalogTest.adoption(mapper, facts, "", policies);
        use.put("schemaVersion", "pvs1-knowledge-adoption-v2");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        for (int i=0;i<policies.size();i++) {
            var p=policies.get(i);var e=(ObjectNode)use.path("entries").path(i);
            var sources=mapper.createArrayNode();
            for(var s:p.path("scientificSources")) for(var id:p.at("/mechanism/sourceIds")) if(id.equals(s.path("sourceId"))) sources.add(s);
            var evidence=e.putObject("mechanismEvidence").put("type","REVIEWED_POLICY_ORIGINAL_SOURCES")
                    .put("policyReviewId",p.at("/review/reviewId").asText()).put("sourceSetSha256",adapter.canonicalSha256(sources));
            evidence.set("sourceIds",p.at("/mechanism/sourceIds").deepCopy());
        }
        return use;
    }
    Pvs1KnowledgeCatalog load(ObjectNode facts,ObjectNode use) throws Exception {
        byte[] f=mapper.writeValueAsBytes(facts);String fs=CodingTranscriptMaterialSupply.sha(f);
        use.put("sourceCandidatesSha256",fs);byte[] u=mapper.writeValueAsBytes(use);
        Files.write(root.resolve("facts.json"),f);Files.write(root.resolve("use.json"),u);
        return Pvs1KnowledgeCatalog.load(mapper,root.resolve("facts.json"),fs,root.resolve("use.json"),CodingTranscriptMaterialSupply.sha(u));
    }
    void rewriteSource(ObjectNode facts,String field,String value) {
        var raw=(ObjectNode)facts.at("/records/0/sourceRecord");raw.put(field,value);
        ((ObjectNode)facts.at("/records/0")).put("sourceRecordSha256",Pvs1KnowledgeCatalog.sourceRecordSha(mapper,raw));
    }
    @Test void qualifiedOriginalSourcesDoNotRequireAnUnrelatedDatabaseDiseaseAlias() throws Exception {
        var d=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31);
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(d.policy));
        rewriteSource(facts,"disease MONDO","MONDO:9999999");
        var use=use(mapper,facts,List.of(d.policy));
        var result=load(facts,use).assess(d.context,d.policy);
        assertEquals("READY",result.path("status").asText(),result.toPrettyString());
        assertEquals("NO_EXACT_BACKGROUND",result.path("g2pConsistency").asText());
        assertEquals(d.policy.path("scientificSources"),result.path("policyMechanismSources"));
        assertEquals(d.policy.at("/scope/mondoId"),d.context.at("/disease/mondoId"));
        assertFalse(result.path("clinicalAcceptance").asBoolean(true));
        var original=Pvs1KnowledgeCatalogTest.adoption(mapper,facts,"",List.of(d.policy));
        assertEquals("PVS1_MECHANISM_SOURCE_NOT_FOUND",load(facts,original).assess(d.context,d.policy).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"UNREVIEWED","SOURCE_PIN","REVIEW_ID","MISSING_SOURCE","POLICY_PIN","MODEL_PIN","EXON_PIN","SOURCE_SET","EXPIRED","PATIENT","WRONG_DISEASE","AMBIGUOUS","G2P_CONFLICT","G2P_WEAK","G2P_MOI","PENDING_EXONS"})
    void exactAuthorityAndConflictsCannotBeBypassed(String failure) throws Exception {
        var d=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31);
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(d.policy));var use=use(mapper,facts,List.of(d.policy));
        var e=(ObjectNode)use.at("/entries/0");String expected;
        switch(failure) {
            case "UNREVIEWED" -> {d.policy.withObject("review").put("status","UNREVIEWED");expected="PVS1_POLICY_SOURCE_REVIEW_REQUIRED";}
            case "SOURCE_PIN" -> {e.withObject("mechanismEvidence").put("sourceSetSha256","0".repeat(64));expected="PVS1_POLICY_MECHANISM_SOURCE_BINDING_MISMATCH";}
            case "REVIEW_ID" -> {e.withObject("mechanismEvidence").put("policyReviewId","other");expected="PVS1_POLICY_MECHANISM_SOURCE_BINDING_MISMATCH";}
            case "MISSING_SOURCE" -> {e.withObject("mechanismEvidence").putArray("sourceIds").add("absent");expected="PVS1_POLICY_MECHANISM_SOURCE_BINDING_MISMATCH";}
            case "POLICY_PIN","MODEL_PIN","EXON_PIN" -> {e.put(failure.equals("POLICY_PIN")?"policySha256":failure.equals("MODEL_PIN")?"modelSha256":"exonNmdScopeSha256","0".repeat(64));expected="PVS1_KNOWLEDGE_POLICY_OR_EXON_BINDING_MISMATCH";}
            case "SOURCE_SET" -> {e.put("sourceRecordSetSha256","0".repeat(64));expected="PVS1_KNOWLEDGE_SOURCE_SET_MISMATCH";}
            case "EXPIRED" -> {e.withObject("review").put("validUntil","2020-01-01T00:00:00Z");expected="PVS1_KNOWLEDGE_REVIEW_TIME_INVALID";}
            case "PATIENT" -> {d.context.put("dataClass","PRIVACY_BOUNDED_RUNTIME");expected="PVS1_KNOWLEDGE_DATA_USE_NOT_QUALIFIED";}
            case "WRONG_DISEASE" -> {d.context.withObject("disease").put("mondoId","MONDO:9999999");expected="PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING";}
            case "AMBIGUOUS" -> {use.withArray("entries").add(e.deepCopy().put("entryId","OTHER"));expected="PVS1_KNOWLEDGE_ADOPTION_AMBIGUOUS";}
            case "G2P_CONFLICT" -> {rewriteSource(facts,"molecular mechanism","gain of function");expected="PVS1_MECHANISM_SOURCE_CONFLICT";}
            case "G2P_WEAK" -> {rewriteSource(facts,"confidence","limited");expected="PVS1_MECHANISM_SOURCE_STRENGTH_REQUIRES_REVIEW";}
            case "G2P_MOI" -> {rewriteSource(facts,"allelic requirement","biallelic_autosomal");expected="PVS1_MECHANISM_INHERITANCE_NOT_COVERED";}
            case "PENDING_EXONS" -> {d.policy.withObject("nmdScope").put("exonRelevance","PENDING explicit original limitation");expected="PVS1_POLICY_SOURCE_APPLICABILITY_INCOMPLETE";}
            default -> throw new AssertionError(failure);
        }
        assertEquals(expected,load(facts,use).assess(d.context,d.policy).path("reasonCode").asText());
    }
    @Test void v2RequiresExplicitCompleteSourceModeAndOldSchemaRejectsIt() throws Exception {
        var d=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31);
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(d.policy));var use=use(mapper,facts,List.of(d.policy));
        use.put("schemaVersion","pvs1-knowledge-adoption-v1");assertThrows(IllegalArgumentException.class,()->load(facts,use));
        use.put("schemaVersion","pvs1-knowledge-adoption-v2");((ObjectNode)use.at("/entries/0/mechanismEvidence")).put("ignoreConflicts",true);
        assertThrows(IllegalArgumentException.class,()->load(facts,use));
    }
}
