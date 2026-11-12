package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StructureBatchCliTest {
    @TempDir Path root;
    final GencodeTranscriptIndexTest fixture = new GencodeTranscriptIndexTest();
    GencodeTranscriptIndex index;
    ObjectNode plan;
    void setup() throws Exception {
        fixture.root = root; index = fixture.setup();
        var c = fixture.input.fixture().context();
        plan = fixture.base.mapper.createObjectNode().put("schemaVersion", "pvs1-material-plan-v1")
                .put("methodVersion", "1.0.0").put("runtimeEligible", false).put("clinicalAcceptance", false);
        plan.putArray("historicalMaterials");
        var request = plan.putArray("structureRequests").addObject();
        request.set("context", c);
        request.put("requestId", new RuntimeEvidenceV2Adapter(fixture.base.mapper).canonicalSha256(c));
    }
    @Test void reusesExistingExactReaderAndRetainsOriginalStructureWithoutQualification() throws Exception {
        setup(); var out = Pvs1StructureBatchCli.assemble(fixture.base.mapper, plan, "a".repeat(64), index);
        assertEquals("AVAILABLE", out.at("/results/0/status").asText());
        assertEquals(fixture.row, out.at("/results/0/annotation"));
        assertFalse(out.path("runtimeEligible").asBoolean(true));
        assertFalse(out.path("sequenceVerified").asBoolean(true)); assertEquals(0, fixture.bases.get());
    }
    @ParameterizedTest @ValueSource(strings={"VERSION", "GENE", "CONTIG", "CHANGED_FILE"})
    void invalidOrChangedSourcesStaySpecificGaps(String kind) throws Exception {
        setup(); var context = (ObjectNode) plan.at("/structureRequests/0/context");
        if (kind.equals("VERSION")) context.withObject("transcript").put("version", 99);
        if (kind.equals("GENE")) context.withObject("gene").put("hgncId", "HGNC:999");
        if (kind.equals("CONTIG")) context.withObject("/variant/evidenceQueryAllele").put("contig", "99");
        if (kind.equals("CHANGED_FILE")) Files.writeString(root.resolve("annotations.jsonl"), "broken");
        ((ObjectNode) plan.at("/structureRequests/0")).put("requestId", new RuntimeEvidenceV2Adapter(fixture.base.mapper).canonicalSha256(context));
        var out = Pvs1StructureBatchCli.assemble(fixture.base.mapper, plan, "a".repeat(64), index);
        assertEquals("UNAVAILABLE", out.at("/results/0/status").asText());
        assertEquals(kind.equals("VERSION") ? "GENCODE_EXACT_TRANSCRIPT_VERSION_UNAVAILABLE" : kind.equals("CHANGED_FILE")
                ? "GENCODE_RESOURCE_CHANGED" : "GENCODE_CONTEXT_IDENTITY_MISMATCH", out.at("/results/0/reasonCode").asText());
        assertEquals(0, fixture.bases.get());
    }
    @ParameterizedTest @ValueSource(strings={"DUPLICATE", "PIN", "ELIGIBLE"})
    void untrustedPlansFailBeforeLookup(String kind) throws Exception {
        setup();
        if (kind.equals("DUPLICATE")) plan.withArray("structureRequests").add(plan.at("/structureRequests/0").deepCopy());
        if (kind.equals("PIN")) ((ObjectNode) plan.at("/structureRequests/0")).put("requestId", "b".repeat(64));
        if (kind.equals("ELIGIBLE")) plan.put("runtimeEligible", true);
        assertThrows(IllegalArgumentException.class, () -> Pvs1StructureBatchCli.assemble(fixture.base.mapper, plan, "a".repeat(64), index));
    }

    @ParameterizedTest @ValueSource(strings={"VALID", "MODEL_TAMPER", "SCOPE_MISMATCH", "MISSING_MODEL"})
    void historicalModelChecksDoNotApproveOrHideInvalidCatalogs(String kind) throws Exception {
        var mapper = fixture.base.mapper; var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var data = new TruncatingNmdPvs1RuntimeTest().nonsense(1, 1, 31);
        var material = mapper.createObjectNode();
        if (kind.equals("SCOPE_MISMATCH")) data.policy.withObject("scope").put("hgncId", "HGNC:999");
        String sha = adapter.canonicalSha256(data.policy);
        material.put("materialId", "pvs1-material-" + sha).put("policySha256", sha).set("policy", data.policy);
        var original = material.putObject("originalCandidate");
        original.putObject("catalog"); original.put("catalogSha256", adapter.canonicalSha256(original.path("catalog")));
        original.putArray("policies").add(data.policy);
        if (!kind.equals("MISSING_MODEL")) {
            var model = material.putObject("modelMaterial");
            model.put("canonicalSha256", adapter.canonicalSha256(data.model)).set("model", data.model);
            if (kind.equals("MODEL_TAMPER")) data.model.put("cdsSequence", "ATGTAA");
        }
        var result = Pvs1StructureBatchCli.historicalChecks(mapper, mapper.createArrayNode().add(material)).get(0);
        String expected = switch (kind) {
            case "MODEL_TAMPER" -> "POLICY_MODEL_BINDING_MISMATCH";
            case "SCOPE_MISMATCH" -> "PVS1_TRANSCRIPT_MODEL_CONTEXT_MISMATCH";
            case "MISSING_MODEL" -> "POLICY_MODEL_BYTES_NOT_VERIFIED";
            default -> "MODEL_STRUCTURE_AND_STORED_SEQUENCE_VALID";
        };
        assertEquals(expected, result.path("modelReasonCode").asText());
        assertNotEquals("CATALOG_CONFIGURATION_VALID", result.path("catalogReasonCode").asText());
        assertFalse(result.path("runtimeEligible").asBoolean(true));
    }
}
