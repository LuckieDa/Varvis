package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic source-navigation checks; no declaration here is a scientific approval. */
class MissenseExpertReferenceEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode audit = mapper.createObjectNode(), packet = mapper.createObjectNode();
    private final ObjectNode reference, row, pins;

    MissenseExpertReferenceEvidenceTest() {
        reference = audit.putArray("references").addObject().put("expertEvidenceReferenceId", "synthetic-1")
                .put("variationId", "123").put("geneSymbol", "SYNTHETIC");
        reference.putObject("allele").put("assembly", "GRCh38").put("contig", "1").put("position", 100).put("reference", "A").put("alternate", "C");
        reference.putArray("sourceIds").add("DECLARATION");
        reference.putObject("sourceAssertion").put("scv", "SCV000001.1").put("classification", "Pathogenic")
                .put("dateLastEvaluated", "2026-01-02").put("reviewStatus", "reviewed by expert panel");
        // Placeholder is insufficient at the original preparer/runtime; this helper checks navigation only.
        reference.putObject("qualification").put("schemaVersion", "SYNTHETIC_PLACEHOLDER_NOT_APPROVED");
        packet.put("schemaVersion", "missense-expert-reference-evidence-v1").put("draft", true)
                .put("reviewStatus", "UNREVIEWED").put("scientificAdoption", false);
        row = packet.putArray("references").addObject().put("referenceId", "synthetic-1").put("variationId", "123")
                .put("bindingStatus", "EXACT_SOURCE_BOUND").put("reviewStatus", "UNREVIEWED").put("scientificAdoption", false);
        row.set("allele", reference.path("allele").deepCopy()); row.set("originalAssertion", reference.path("sourceAssertion").deepCopy());
        row.putArray("geneSymbols").add("SYNTHETIC"); row.putArray("sourceIds").add("RAW_EXPERT");
        row.putArray("appliedComparisonCriteria").add("PS1"); row.putArray("gapReasons").add("ORIGINAL_LABEL_MISSING");
        row.putArray("criterionAssessments").addObject().putNull("code").putObject("original").put("comment", "SYNTHETIC untranslated source");
        pins = mapper.createObjectNode().put("rawSha256", "a".repeat(64)).put("canonicalSha256", "b".repeat(64));
    }

    @Test void navigationRetainsWholeOriginalButDoesNotMakeApproval() {
        var originalPacket = packet.deepCopy(); var originalAssertion = reference.path("sourceAssertion").deepCopy();
        MissenseExpertReferenceEvidence.bind(audit, packet, pins, Set.of("RAW_EXPERT"));
        assertEquals(row, reference.at("/expertEvidence/record")); assertEquals(originalPacket, packet);
        assertEquals(originalAssertion, reference.path("sourceAssertion"));
        assertEquals("SOURCE_NAVIGATION_ONLY", reference.at("/expertEvidence/use").asText());
        ObjectNode policy = mapper.createObjectNode();
        policy.putArray("sources").addObject().put("sourceId", "RAW_EXPERT");
        assertNull(MissenseExpertReferenceEvidence.issue(reference, policy));
        assertEquals("REFERENCE_INDEPENDENT_ROUTE_NOT_ENABLED", MissenseIndependentReference.issue(policy, reference, new RuntimeEvidenceV2Adapter(mapper)));
    }

    @ParameterizedTest @CsvSource({
        "SCV,EXPERT_EVIDENCE_ASSERTION_MISMATCH", "CLASS,EXPERT_EVIDENCE_ASSERTION_MISMATCH",
        "DATE,EXPERT_EVIDENCE_ASSERTION_MISMATCH", "LABEL,EXPERT_EVIDENCE_ASSERTION_MISMATCH",
        "ALLELE,EXPERT_EVIDENCE_IDENTITY_MISMATCH", "VID,EXPERT_EVIDENCE_IDENTITY_MISMATCH",
        "GENE,EXPERT_EVIDENCE_IDENTITY_MISMATCH", "UNBOUND,EXPERT_EVIDENCE_SOURCE_UNBOUND",
        "DUP_SOURCE,EXPERT_EVIDENCE_SOURCE_UNBOUND", "DUP_ROW,EXPERT_EVIDENCE_REFERENCE_ID_INVALID",
        "NOT_FOUND,EXPERT_EVIDENCE_REFERENCE_NOT_FOUND", "APPROVED_PACKET,EXPERT_EVIDENCE_DRAFT_REQUIRED",
        "APPROVED_ROW,EXPERT_EVIDENCE_DRAFT_REQUIRED", "NO_REVIEW,EXPERT_EVIDENCE_INDEPENDENT_REVIEW_REQUIRED"
    })
    void changedIdentityOrPretendedApprovalCannotEnterBridge(String fault, String reason) {
        switch (fault) {
            case "SCV" -> ((ObjectNode) row.path("originalAssertion")).put("scv", "SCV000001.2");
            case "CLASS" -> ((ObjectNode) row.path("originalAssertion")).put("classification", "Likely pathogenic");
            case "DATE" -> ((ObjectNode) row.path("originalAssertion")).put("dateLastEvaluated", "2026-01-03");
            case "LABEL" -> ((ObjectNode) row.path("originalAssertion")).put("reviewStatus", "single submitter");
            case "ALLELE" -> ((ObjectNode) row.path("allele")).put("alternate", "G");
            case "VID" -> row.put("variationId", "124");
            case "GENE" -> row.putArray("geneSymbols").add("DIFFERENT");
            case "UNBOUND" -> row.putArray("sourceIds").add("OTHER");
            case "DUP_SOURCE" -> row.withArray("sourceIds").add("RAW_EXPERT");
            case "DUP_ROW" -> packet.withArray("references").add(row.deepCopy());
            case "NOT_FOUND" -> reference.put("expertEvidenceReferenceId", "other");
            case "APPROVED_PACKET" -> packet.put("scientificAdoption", true);
            case "APPROVED_ROW" -> row.put("reviewStatus", "REVIEWED");
            case "NO_REVIEW" -> reference.remove("qualification");
            default -> fail(fault);
        }
        var failure = assertThrows(IllegalArgumentException.class,
                () -> MissenseExpertReferenceEvidence.bind(audit, packet, pins, Set.of("RAW_EXPERT")));
        assertEquals(reason, failure.getMessage());
    }

    @Test void noExplicitSelectionLeavesDraftUnused() {
        reference.remove("expertEvidenceReferenceId");
        MissenseExpertReferenceEvidence.bind(audit, packet, pins, Set.of("RAW_EXPERT"));
        assertFalse(reference.has("expertEvidence"));
    }

    private ObjectNode mappedCondition() {
        row.put("bindingStatus", "EXACT_RECORD_BOUND_CONDITION_UNRESOLVED");
        row.putObject("disease").put("id", "MONDO:0000001");
        reference.put("mondoId", "MONDO:0000001");
        ((ObjectNode) reference.path("sourceAssertion")).putObject("condition")
                .put("submittedRaw", "Orphanet:ORPHA1").put("reportedRaw", "Synthetic disease");
        var mapping = reference.putArray("conditionMappings").addObject()
                .put("schemaVersion", "reviewed-clinvar-condition-mapping-v1")
                .put("status", "REVIEWED_EXACT").put("relation", "EXACT_DISEASE")
                .put("reviewId", "SYNTHETIC_MAPPING").put("mondoId", "MONDO:0000001");
        mapping.set("sourceCondition", reference.at("/sourceAssertion/condition").deepCopy());
        mapping.putArray("sourceIds").add("DECLARATION");
        return mapping;
    }

    private ObjectNode registeredPolicy() {
        var policy = mapper.createObjectNode();
        policy.putArray("sources").addObject().put("sourceId", "RAW_EXPERT");
        policy.withArray("sources").addObject().put("sourceId", "DECLARATION");
        return policy;
    }

    @Test void exactReviewedConditionCanBindUnchangedNonMondoSourceNavigation() {
        mappedCondition(); var frozen = packet.deepCopy();
        MissenseExpertReferenceEvidence.bind(audit, packet, pins, Set.of("RAW_EXPERT", "DECLARATION"));
        assertEquals(frozen, packet);
        assertEquals(row, reference.at("/expertEvidence/record"));
        assertNull(MissenseExpertReferenceEvidence.issue(reference, registeredPolicy()));
        assertEquals("EXACT_RECORD_BOUND_CONDITION_UNRESOLVED", reference.at("/expertEvidence/record/bindingStatus").asText());
        assertEquals("REFERENCE_INDEPENDENT_ROUTE_NOT_ENABLED", MissenseIndependentReference.issue(
                registeredPolicy(), reference, new RuntimeEvidenceV2Adapter(mapper)));
    }

    @ParameterizedTest @CsvSource({"NO_MAPPING", "UNREVIEWED", "WRONG_SOURCE", "WRONG_DISEASE", "WRONG_REFERENCE",
            "UNBOUND_SOURCE", "DUPLICATE_MAPPING", "UNKNOWN_BINDING", "EXPLICIT_CONFLICT"})
    void unresolvedConditionCannotBorrowOtherMapping(String fault) {
        var mapping = mappedCondition();
        switch (fault) {
            case "NO_MAPPING" -> reference.remove("conditionMappings");
            case "UNREVIEWED" -> mapping.put("status", "UNREVIEWED");
            case "WRONG_SOURCE" -> ((ObjectNode) mapping.path("sourceCondition")).put("submittedRaw", "Orphanet:ORPHA2");
            case "WRONG_DISEASE" -> ((ObjectNode) row.path("disease")).put("id", "MONDO:0000002");
            case "WRONG_REFERENCE" -> reference.put("mondoId", "MONDO:0000002");
            case "UNBOUND_SOURCE" -> mapping.putArray("sourceIds").add("NOT_REGISTERED");
            case "DUPLICATE_MAPPING" -> reference.withArray("conditionMappings").add(mapping.deepCopy());
            case "UNKNOWN_BINDING" -> row.put("bindingStatus", "OTHER_UNRESOLVED");
            case "EXPLICIT_CONFLICT" -> {
                ((ObjectNode) mapping.path("sourceCondition")).put("reportedRaw", "MONDO:0000002");
                ((ObjectNode) reference.at("/sourceAssertion/condition")).put("reportedRaw", "MONDO:0000002");
            }
            default -> fail(fault);
        }
        assertThrows(IllegalArgumentException.class, () -> MissenseExpertReferenceEvidence.bind(
                audit, packet, pins, Set.of("RAW_EXPERT", "DECLARATION")));
        // Persisted material must enforce the same binding even without running the bridge again.
        var attached = reference.putObject("expertEvidence").put("use", "SOURCE_NAVIGATION_ONLY");
        attached.set("packetPins", pins); attached.set("record", row);
        assertNotNull(MissenseExpertReferenceEvidence.issue(reference, registeredPolicy()));
    }
}
