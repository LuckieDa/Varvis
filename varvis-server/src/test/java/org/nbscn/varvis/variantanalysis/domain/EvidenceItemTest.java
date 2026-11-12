package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceItemTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsLegacyEvidenceWithoutInventingClinicalStrength() throws Exception {
        String legacyJson = "{"
                + "\"source\":\"GNOMAD\","
                + "\"sourceVersion\":\"gnomAD 4.1\","
                + "\"evidenceType\":\"POPULATION\","
                + "\"applicability\":\"NO_RECORD\","
                + "\"rawScore\":null,"
                + "\"label\":\"No record\","
                + "\"summary\":\"No matching allele\","
                + "\"details\":{}"
                + "}";

        EvidenceItem legacy = objectMapper.readValue(legacyJson, EvidenceItem.class);

        assertEquals(EvidenceItem.LEGACY_SCHEMA_VERSION, legacy.getSchemaVersion());
        assertEquals("NO_RECORD", legacy.getEffectiveStatus());
        assertEquals("gnomAD 4.1", legacy.getEffectiveResourceVersion());
        assertNull(legacy.getEvidenceStrength());
        assertNull(legacy.getSourceRecordId());
        assertNull(legacy.getCanonicalVariantId());
        assertNull(legacy.getAssembly());
        assertTrue(legacy.getProvenance().isEmpty());

        EvidenceItem migrated = legacy.withVariantContext("GRCh38|1|100|A|G", "GRCh38");
        assertEquals(EvidenceItem.CURRENT_SCHEMA_VERSION, migrated.getSchemaVersion());
        assertEquals("NO_RECORD", migrated.getStatus());
        assertEquals(EvidenceItem.INDETERMINATE_STRENGTH, migrated.getEvidenceStrength());
        assertEquals("GRCh38|1|100|A|G", migrated.getCanonicalVariantId());
        assertEquals("GRCh38", migrated.getAssembly());
    }

    @Test
    void serializesUnifiedProvenanceWithoutConfusingUnavailableAndNoRecord() throws Exception {
        String unifiedJson = "{"
                + "\"schemaVersion\":\"evidence-item-v1\","
                + "\"id\":\"clinvar:VCV0001\","
                + "\"canonicalVariantId\":\"GRCh38|1|100|A|G\","
                + "\"source\":\"CLINVAR\","
                + "\"sourceRecordId\":\"VCV0001\","
                + "\"resourceVersion\":\"2026-03\","
                + "\"resourceSha256\":\"abc123\","
                + "\"assembly\":\"GRCh38\","
                + "\"evidenceType\":\"CLINICAL\","
                + "\"status\":\"UNAVAILABLE\","
                + "\"evidenceStrength\":\"INDETERMINATE\","
                + "\"explanationCode\":\"PROVIDER_UNAVAILABLE\","
                + "\"provenance\":{\"provider\":\"local-index\"},"
                + "\"details\":{}"
                + "}";

        EvidenceItem item = objectMapper.readValue(unifiedJson, EvidenceItem.class);
        String serialized = objectMapper.writeValueAsString(item);

        assertEquals("UNAVAILABLE", item.getEffectiveStatus());
        assertFalse(serialized.contains("NO_RECORD"));
        assertTrue(serialized.contains("\"schemaVersion\":\"evidence-item-v1\""));
        assertTrue(serialized.contains("\"sourceRecordId\":\"VCV0001\""));
        assertEquals("local-index", item.getProvenance().get("provider"));
    }

    @Test
    void compactPresentationPreservesMachineReadableProvenance() {
        EvidenceItem item = new EvidenceItem(
                EvidenceItem.CURRENT_SCHEMA_VERSION,
                "evidence-1",
                "GRCh38|1|100|A|G",
                "1",
                100,
                "A",
                "G",
                "ENSG000001",
                "ENST000001",
                null,
                "VEP",
                "record-1",
                "legacy-version",
                "116",
                "sha256",
                "GRCh38",
                "CONSEQUENCE",
                "APPLICABLE",
                "APPLICABLE",
                null,
                "missense_variant",
                "missense_variant",
                null,
                EvidenceItem.INDETERMINATE_STRENGTH,
                "Long label",
                "Long summary",
                Collections.singletonMap("provider", "VEP"),
                "2026-07-12T00:00:00Z",
                "TRANSCRIPT_CONSEQUENCE",
                Collections.singletonMap("payload", "large")
        );

        EvidenceItem compact = item.withPresentation("Label", "Summary", Collections.emptyMap());

        assertEquals("evidence-1", compact.getId());
        assertEquals("GRCh38|1|100|A|G", compact.getCanonicalVariantId());
        assertEquals("116", compact.getResourceVersion());
        assertEquals("VEP", compact.getProvenance().get("provider"));
        assertEquals("Label", compact.getLabel());
        assertTrue(compact.getDetails().isEmpty());
    }

    @Test
    void preservesUnknownFutureSchemaInsteadOfTreatingItAsCurrent() throws Exception {
        EvidenceItem future = objectMapper.readValue(
                "{\"schemaVersion\":\"evidence-item-v99\","
                        + "\"source\":\"FUTURE\",\"status\":\"FUTURE_STATUS\"}",
                EvidenceItem.class
        );

        EvidenceItem contextualized = future.withVariantContext("GRCh38|1|100|A|G", "GRCh38");

        assertEquals("evidence-item-v99", contextualized.getSchemaVersion());
        assertEquals("FUTURE_STATUS", contextualized.getStatus());
    }
}
