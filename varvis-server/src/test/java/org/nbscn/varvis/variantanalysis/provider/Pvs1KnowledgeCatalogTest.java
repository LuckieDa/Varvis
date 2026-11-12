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

public class Pvs1KnowledgeCatalogTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    /** Synthetic source facts and adoption are visibly fictional; no real G2P record is approved. */
    public static ObjectNode facts(ObjectMapper mapper, List<ObjectNode> policies) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-mechanism-source-candidates-v1").put("methodVersion", "1.0.0")
                .put("status", "UNREVIEWED").put("runtimeEligible", false).put("clinicalAcceptance", false);
        out.putObject("source").put("sha256", "a".repeat(64)).put("version", "TEST_ONLY").put("url", "https://example.org/test-only");
        out.putObject("counts").put("sourceRecords", policies.size()); var records = out.putArray("records"); int i = 0;
        for (var policy : policies) {
            i++; var raw = mapper.createObjectNode().put("g2p id", "G2P0000" + i)
                    .put("hgnc id", policy.at("/scope/hgncId").asText().replace("HGNC:", ""))
                    .put("disease MONDO", policy.at("/scope/mondoId").asText())
                    .put("allelic requirement", "AUTOSOMAL_DOMINANT".equals(policy.at("/scope/moi").asText()) ? "monoallelic_autosomal" : "biallelic_autosomal")
                    .put("molecular mechanism", "loss of function").put("confidence", "definitive")
                    .put("molecular mechanism support", "inferred").put("comments", "TEST_ONLY 原文\n保留限制")
                    .put("review", "Source review is not project approval");
            var record = records.addObject().put("recordId", "G2P0000" + i + ":" + i).put("sourceRow", i)
                    .put("sourceRecordSha256", Pvs1KnowledgeCatalog.sourceRecordSha(mapper, raw)).put("status", "UNREVIEWED").put("runtimeEligible", false);
            record.set("sourceRecord", raw);
        }
        return out;
    }
    public static ObjectNode adoption(ObjectMapper mapper, ObjectNode facts, String factsRawSha, List<ObjectNode> policies) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-knowledge-adoption-v1").put("catalogId", "TEST_ONLY")
                .put("catalogVersion", "1").put("sourceCandidatesSha256", factsRawSha).put("sourceDataClass", "SYNTHETIC");
        var entries = out.putArray("entries"); int i = 0;
        for (var policy : policies) {
            var e = entries.addObject().put("entryId", "TEST_ONLY_" + i++).put("policySha256", adapter.canonicalSha256(policy))
                    .put("modelSha256", policy.at("/transcriptModelBinding/canonicalSha256").asText())
                    .put("exonNmdScopeSha256", adapter.canonicalSha256(policy.path("nmdScope")))
                    .put("mechanismStatus", "ESTABLISHED_LOSS_OF_FUNCTION").put("transcriptRelevance", "REVIEWED_RELEVANT").put("purpose", "TEST_ONLY");
            e.putArray("allowedDataClasses").add("SYNTHETIC");
            var scope = e.putObject("scope");
            for (String k : List.of("hgncId", "mondoId", "moi", "assembly")) scope.set(k, policy.path("scope").path(k));
            scope.put("transcript", policy.at("/scope/transcripts/0/accession").asText() + "." + policy.at("/scope/transcripts/0/version").asText());
            var matches = mapper.createArrayNode();
            for (var r : facts.path("records")) if (("HGNC:" + r.at("/sourceRecord/hgnc id").asText()).equals(scope.path("hgncId").asText())
                    && r.at("/sourceRecord/disease MONDO").equals(scope.path("mondoId"))) matches.add(r);
            e.put("sourceRecordSetSha256", adapter.canonicalSha256(matches));
            e.putObject("review").put("status", "REVIEWED_FOR_PVS1_NMD").put("reviewId", "TEST_ONLY_NOT_SCIENTIFIC_APPROVAL")
                    .put("reviewedBy", "TEST_ONLY").put("reviewedAt", "2026-01-01T00:00:00Z").put("validUntil", "2099-01-01T00:00:00Z")
                    .put("basis", "TEST_ONLY fixed toy mechanism, source restrictions and exon scope");
        }
        return out;
    }
    Pvs1KnowledgeCatalog catalog(ObjectNode facts, ObjectNode use) throws Exception {
        byte[] f = mapper.writeValueAsBytes(facts); String hash = CodingTranscriptMaterialSupply.sha(f);
        use.put("sourceCandidatesSha256", hash); byte[] u = mapper.writeValueAsBytes(use);
        Files.write(root.resolve("facts.json"), f); Files.write(root.resolve("use.json"), u);
        return Pvs1KnowledgeCatalog.load(mapper, root.resolve("facts.json"), hash, root.resolve("use.json"), CodingTranscriptMaterialSupply.sha(u));
    }

    @Test void twoBackgroundsConsumeOriginalRecordsAndExactExonPolicyWithoutGeneCode() throws Exception {
        var fixture = new TruncatingNmdPvs1RuntimeTest();
        var first = fixture.nonsense(1, 1, 31); var second = fixture.nonsense(2, -1, 199);
        var policies = List.of(first.policy, second.policy); var facts = facts(mapper, policies);
        var use = adoption(mapper, facts, "", policies); var catalog = catalog(facts, use);
        for (var input : List.of(first, second)) {
            var audit = catalog.assess(input.context, input.policy);
            assertEquals("READY", audit.path("status").asText(), audit.toString());
            assertEquals("inferred", audit.at("/sourceRecords/0/sourceRecord/molecular mechanism support").asText());
            assertFalse(audit.path("clinicalAcceptance").asBoolean(true));
            audit.removeAll(); assertEquals("READY", catalog.assess(input.context, input.policy).path("status").asText());
        }
        facts.removeAll(); use.removeAll(); assertEquals("READY", catalog.assess(first.context, first.policy).path("status").asText());
    }

    @ParameterizedTest @ValueSource(strings={"NO_ADOPTION", "SOURCE_REVIEW_ONLY", "EXPIRED", "FUTURE", "WRONG_SOURCE_SET", "POLICY", "MODEL", "EXON", "TRANSCRIPT", "DUPLICATE", "PATIENT", "UNKNOWN_LOF", "WEAK", "CONFLICT", "MOI", "GENE"})
    void singleCauseCannotBorrowQualification(String failure) throws Exception {
        var input = new TruncatingNmdPvs1RuntimeTest().nonsense(1, 1, 31);
        var facts = facts(mapper, List.of(input.policy)); var use = adoption(mapper, facts, "", List.of(input.policy));
        var e = (ObjectNode) use.at("/entries/0"); var raw = (ObjectNode) facts.at("/records/0/sourceRecord"); String expected;
        switch(failure) {
            case "NO_ADOPTION" -> { use.putArray("entries"); expected = "PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING"; }
            case "SOURCE_REVIEW_ONLY" -> { e.withObject("review").put("status", "UNREVIEWED"); expected = "PVS1_KNOWLEDGE_REVIEW_REQUIRED"; }
            case "EXPIRED", "FUTURE" -> { e.withObject("review").put(failure.equals("EXPIRED") ? "validUntil" : "reviewedAt", failure.equals("EXPIRED") ? "2020-01-01T00:00:00Z" : "2099-01-01T00:00:00Z"); expected = "PVS1_KNOWLEDGE_REVIEW_TIME_INVALID"; }
            case "WRONG_SOURCE_SET" -> { e.put("sourceRecordSetSha256", "0".repeat(64)); expected = "PVS1_KNOWLEDGE_SOURCE_SET_MISMATCH"; }
            case "POLICY", "MODEL", "EXON" -> { e.put(failure.equals("POLICY") ? "policySha256" : failure.equals("MODEL") ? "modelSha256" : "exonNmdScopeSha256", "0".repeat(64)); expected = "PVS1_KNOWLEDGE_POLICY_OR_EXON_BINDING_MISMATCH"; }
            case "TRANSCRIPT" -> { e.withObject("scope").put("transcript", "NM_999999.1"); expected = "PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING"; }
            case "DUPLICATE" -> { use.withArray("entries").add(e.deepCopy().put("entryId", "SECOND")); expected = "PVS1_KNOWLEDGE_ADOPTION_AMBIGUOUS"; }
            case "PATIENT" -> { input.context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME"); e.putArray("allowedDataClasses").add("PRIVACY_BOUNDED_RUNTIME"); expected = "PVS1_KNOWLEDGE_DATA_USE_NOT_QUALIFIED"; }
            case "UNKNOWN_LOF" -> { raw.put("molecular mechanism", "undetermined"); expected = "PVS1_LOF_SOURCE_NOT_ESTABLISHED"; }
            case "WEAK" -> { raw.put("confidence", "limited"); expected = "PVS1_MECHANISM_SOURCE_STRENGTH_REQUIRES_REVIEW"; }
            case "CONFLICT" -> { var extra = ((ObjectNode) facts.at("/records/0")).deepCopy().put("recordId", "G2P00001:2").put("sourceRow", 2); extra.withObject("sourceRecord").put("molecular mechanism", "gain of function"); extra.put("sourceRecordSha256", Pvs1KnowledgeCatalog.sourceRecordSha(mapper, extra.path("sourceRecord"))); facts.withArray("records").add(extra); facts.withObject("counts").put("sourceRecords", 2); expected = "PVS1_MECHANISM_SOURCE_CONFLICT"; }
            case "MOI" -> { input.context.put("moi", "AUTOSOMAL_RECESSIVE"); expected = "PVS1_MECHANISM_INHERITANCE_NOT_COVERED"; }
            case "GENE" -> { input.context.withObject("gene").put("hgncId", "HGNC:999"); expected = "PVS1_MECHANISM_SOURCE_NOT_FOUND"; }
            default -> throw new AssertionError(failure);
        }
        ((ObjectNode) facts.at("/records/0")).put("sourceRecordSha256", Pvs1KnowledgeCatalog.sourceRecordSha(mapper, raw));
        assertEquals(expected, catalog(facts, use).assess(input.context, input.policy).path("reasonCode").asText());
    }

    @Test void brokenRecordOrUnknownAdoptionConditionCannotLoad() throws Exception {
        var input = new TruncatingNmdPvs1RuntimeTest().nonsense(1, 1, 31);
        var facts = facts(mapper, List.of(input.policy)); var use = adoption(mapper, facts, "", List.of(input.policy));
        use.put("ignoreAllConflicts", true);
        assertEquals("PVS1_KNOWLEDGE_ADOPTION_IDENTITY_INVALID", assertThrows(IllegalArgumentException.class, () -> catalog(facts, use)).getMessage());
        use.remove("ignoreAllConflicts");
        ((ObjectNode) use.at("/entries/0")).put("onlyIfSomeExtraCondition", true);
        assertEquals("PVS1_KNOWLEDGE_ADOPTION_ENTRY_INVALID", assertThrows(IllegalArgumentException.class, () -> catalog(facts, use)).getMessage());
        ((ObjectNode) use.at("/entries/0")).remove("onlyIfSomeExtraCondition");
        ((ObjectNode) facts.at("/records/0/sourceRecord")).put("comments", "ALTERED");
        assertEquals("PVS1_KNOWLEDGE_SOURCE_RECORD_INVALID", assertThrows(IllegalArgumentException.class, () -> catalog(facts, use)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings={"RAW_SHA", "DUPLICATE_FIELD", "TRAILING_DOCUMENT"})
    void pinnedReaderRejectsCorruptionAndAmbiguousJson(String failure) throws Exception {
        var input = new TruncatingNmdPvs1RuntimeTest().nonsense(1, 1, 31);
        var facts = facts(mapper, List.of(input.policy));
        byte[] source = mapper.writeValueAsBytes(facts); String pin = CodingTranscriptMaterialSupply.sha(source);
        var use = adoption(mapper, facts, pin, List.of(input.policy));
        String json = mapper.writeValueAsString(use);
        if (failure.equals("DUPLICATE_FIELD")) json = json.substring(0, json.length() - 1) + ",\"catalogId\":\"OVERRIDE\"}";
        if (failure.equals("TRAILING_DOCUMENT")) json += " {}";
        byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve("source.json"), source); Files.write(root.resolve("adoption.json"), body);
        String usePin = failure.equals("RAW_SHA") ? "0".repeat(64) : CodingTranscriptMaterialSupply.sha(body);
        var error = assertThrows(Exception.class, () -> Pvs1KnowledgeCatalog.load(mapper, root.resolve("source.json"), pin, root.resolve("adoption.json"), usePin));
        if (failure.equals("RAW_SHA")) assertEquals("PVS1_KNOWLEDGE_FILE_SHA_MISMATCH", error.getMessage());
        else assertInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class, error);
    }
}
