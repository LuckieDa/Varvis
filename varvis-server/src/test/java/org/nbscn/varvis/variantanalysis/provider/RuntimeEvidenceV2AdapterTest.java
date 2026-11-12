package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeEvidenceV2AdapterTest {

    private ObjectMapper mapper;
    private RuntimeEvidenceV2Adapter adapter;
    private ObjectNode context;
    private ObjectNode carrier;
    private ObjectNode expectedAtomicEvidence;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        try (InputStream input = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            JsonNode fixture = mapper.readTree(input);
            context = (ObjectNode) fixture.path("context").deepCopy();
            carrier = (ObjectNode) fixture.path("carrier").deepCopy();
            expectedAtomicEvidence = (ObjectNode) fixture.path("expectedAtomicEvidence").deepCopy();
        }
    }

    @Test
    void createsCanonicalAtomicEvidenceOnlyFromCompleteExplicitContext() {
        ObjectNode evidence = adapter.adapt(context, carrier);

        assertEquals(expectedAtomicEvidence, evidence);
        assertEquals(
                "dedup-sha256-65fadb498a56d3c635368f6c769a2927288ab85232518747b6806d233c6175f9",
                evidence.path("deduplicationKey").asText()
        );
        assertEquals(
                "evidence-sha256-778329d1a31e67b530003709339f975baddb2965afdb353e2229f0612a3871bd",
                evidence.path("evidenceId").asText()
        );
        assertEquals("SUCCEEDED", evidence.path("resourceExecution").path("status").asText());
        assertEquals("HIT", evidence.path("queryOutcome").path("status").asText());
        assertEquals("UNDETERMINED", evidence.path("direction").asText());
        assertFalse(evidence.toString().contains("F:\\"));
        assertFalse(evidence.toString().contains("D:\\"));
    }

    @Test
    void canonicalIdentitiesIgnoreHttpPrettyPrintingWithoutChangingTheCallerMapper() throws Exception {
        mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        assertEquals(expectedAtomicEvidence, adapter.adapt(context, carrier));
        AcmgBusinessActivation.verify(mapper);
        org.junit.jupiter.api.Assertions.assertTrue(
                mapper.isEnabled(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT));
        org.junit.jupiter.api.Assertions.assertTrue(mapper.writeValueAsString(context).contains("\n"));
    }

    @Test
    void rejectsIncompleteOrNonCanonicalContextInsteadOfInventingIt() {
        context.withObjectProperty("transcript").remove("selectionPolicy");

        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, carrier));
    }

    @Test
    void rejectsCarrierForAnotherAllele() {
        carrier.withObjectProperty("queryAllele").put("alternate", "T");

        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, carrier));
    }

    @Test
    void rejectsProviderFailurePresentedAsNoRecord() {
        carrier.withObjectProperty("resourceExecution").removeAll();
        carrier.withObjectProperty("resourceExecution").put("status", "FAILED").put("reasonCode", "PROVIDER_FAILED");
        carrier.withObjectProperty("queryOutcome").put("status", "NO_RECORD").put("recordCount", 0);
        carrier.withObjectProperty("queryOutcome").remove("queryFingerprint");
        carrier.withObjectProperty("source").withArray("sourceRecords").removeAll();
        carrier.withArray("measurements").removeAll();
        carrier.withObjectProperty("resourceApplicability").put("status", "UNKNOWN").put("reasonCode", "NOT_EVALUATED");

        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, carrier));
    }

    @Test
    void acceptsNoRecordOnlyAsSuccessfulEmptyQuery() {
        carrier.withObjectProperty("source").withArray("sourceRecords").removeAll();
        carrier.withArray("measurements").removeAll();
        carrier.withObjectProperty("queryOutcome").removeAll();
        carrier.withObjectProperty("queryOutcome").put("status", "NO_RECORD").put("recordCount", 0)
                .put("queryFingerprint", "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");

        ObjectNode evidence = adapter.adapt(context, carrier);

        assertEquals("NO_RECORD", evidence.path("queryOutcome").path("status").asText());
        assertEquals(0, evidence.path("source").path("sourceRecords").size());
    }

    @Test
    void acceptsTimeoutOnlyAsNotQueriedAndNotEvaluated() {
        carrier.withObjectProperty("source").withArray("sourceRecords").removeAll();
        carrier.withArray("measurements").removeAll();
        carrier.withObjectProperty("resourceExecution").removeAll();
        carrier.withObjectProperty("resourceExecution").put("status", "TIMEOUT").put("reasonCode", "PROVIDER_TIMEOUT");
        carrier.withObjectProperty("queryOutcome").removeAll();
        carrier.withObjectProperty("queryOutcome").put("status", "NOT_QUERIED").put("recordCount", 0);
        carrier.withObjectProperty("resourceApplicability").removeAll();
        carrier.withObjectProperty("resourceApplicability").put("status", "UNKNOWN").put("reasonCode", "NOT_EVALUATED");

        ObjectNode evidence = adapter.adapt(context, carrier);

        assertEquals("TIMEOUT", evidence.path("resourceExecution").path("status").asText());
        assertEquals("NOT_QUERIED", evidence.path("queryOutcome").path("status").asText());
    }

    @Test
    void rejectsSchemaInvalidAxesAndMissingQueryFingerprint() throws IOException {
        carrier.withObjectProperty("resourceAvailability").put("status", "READY");
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, carrier));

        setUp();
        carrier.withObjectProperty("queryOutcome").remove("queryFingerprint");
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, carrier));
    }

    @Test
    void rejectsSucceededNotQueriedAndAbsoluteLocator() throws IOException {
        setUp();
        carrier.withObjectProperty("source").withArray("sourceRecords").removeAll();
        carrier.withArray("measurements").removeAll();
        carrier.withObjectProperty("queryOutcome").removeAll();
        carrier.withObjectProperty("queryOutcome").put("status", "NOT_QUERIED").put("recordCount", 0);
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, carrier));

        setUp();
        ((ObjectNode) carrier.withObjectProperty("source").withArray("sourceLocators").get(0))
                .put("locatorValue", "F:\\private\\resource.vcf.gz");
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, carrier));
    }
}
