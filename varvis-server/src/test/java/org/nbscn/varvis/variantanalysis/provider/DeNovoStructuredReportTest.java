package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** All reports are invented program fixtures, never laboratory or clinical validation. */
class DeNovoStructuredReportTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/denovo-automatic/synthetic-local-report.json")) {
            assertNotNull(stream); return (ObjectNode) mapper.readTree(stream);
        }
    }
    private DeNovoStructuredReport read(ObjectNode n) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(n); return DeNovoStructuredReport.read(raw, FamilyDeNovoJson.sha256(raw));
    }
    private ObjectNode row(ObjectNode n, String field, int index) { return (ObjectNode) n.path(field).get(index); }
    private void rejected(ObjectNode n, String reason) {
        var error = assertThrows(IllegalArgumentException.class, () -> read(n)); assertEquals("DENOVO_REPORT_" + reason, error.getMessage());
    }

    @Test void readsReportsWithoutInventingVcfQualityOrClinicalApproval() throws Exception {
        var report = read(fixture());
        assertEquals(TargetTest.PRESENT, report.targetStatus("PROBAND"));
        assertEquals(TargetTest.ABSENT_QUALIFIED, report.targetStatus("MOTHER"));
        assertEquals(TargetTest.ABSENT_QUALIFIED, report.targetStatus("FATHER"));
        assertEquals(Parentage.CONFIRMED, report.parentageStatus("MOTHER"));
        assertEquals("NOT_REPORTED", report.clinical().mosaicism());
        assertEquals("SYNTHETIC", report.sourceClass());
        assertFalse(report.snapshot().has("qualified"));
        assertFalse(report.snapshot().path("clinical").has("phenotypeCategory"));
        assertEquals("SANGER", report.tests().get(0).method());
    }

    @Test void exactOriginalBytesAndPointersAreImmutable() throws Exception {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(fixture());
        byte[] original = bytes.clone();
        var report = DeNovoStructuredReport.read(bytes, FamilyDeNovoJson.sha256(bytes));
        bytes[0] = 0; report.originalBytes()[0] = 0; report.snapshot().remove("tests");
        assertArrayEquals(original, report.originalBytes()); assertEquals(3, report.snapshot().path("tests").size());
        for (int i = 0; i < report.tests().size(); i++) {
            var test = report.tests().get(i);
            assertEquals(report.sha256() + "#/tests/" + i, test.sourceRef());
            assertEquals(test.result(), report.snapshot().at(test.sourceRef().substring(65)).path("result").asText());
        }
        assertThrows(UnsupportedOperationException.class, () -> report.tests().clear());
    }

    @Test void versionTwoPreservesUnqualifiedSpecialFactsWithoutChangingVersionOne() throws Exception {
        var legacy = read(fixture());
        assertNull(legacy.specialEvent());
        var n = fixture();
        n.put("schemaVersion", DeNovoStructuredReport.SCHEMA_V2);
        var original = n.putObject("specialEvent").put("schemaVersion", "denovo-special-event-v1")
                .put("kind", "CONSTITUTIONAL").put("personId", "child");
        var report = read(n);
        var special = report.specialEvent();
        assertNotNull(special);
        assertEquals(original, special);
        ((ObjectNode) special).put("personId", "changed-copy");
        assertEquals("child", report.snapshot().at("/specialEvent/personId").asText());
        n.put("schemaVersion", DeNovoStructuredReport.SCHEMA);
        rejected(n, "UNKNOWN_FIELD_specialEvent");
    }

    @Test void versionTwoMissingSpecialFactsStayMissingAndExplicitNullIsRejected() throws Exception {
        var n = fixture(); n.put("schemaVersion", DeNovoStructuredReport.SCHEMA_V2);
        assertNull(read(n).specialEvent());
        n.putNull("specialEvent");
        rejected(n, "FIELD_INVALID_specialEvent");
    }

    @Test void noParentTestIsNotAQualifiedNegative() throws Exception {
        var n = fixture(); ((ArrayNode) n.path("tests")).remove(2);
        var report = read(n);
        assertEquals(TargetTest.UNKNOWN, report.targetStatus("FATHER"));
        assertEquals(Parentage.CONFIRMED, report.parentageStatus("FATHER"));
    }

    @Test void unreportedAndUntestedParentageStayDistinct() throws Exception {
        var n = fixture(); n.putArray("parentage");
        assertEquals(Parentage.ASSUMED_NOT_REPORTED, read(n).parentageStatus("FATHER"));
        n = fixture(); row(n, "parentage", 1).put("result", "NOT_TESTED").put("method", "NOT_DONE");
        assertEquals(Parentage.ASSUMED_NOT_TESTED, read(n).parentageStatus("FATHER"));
    }

    @Test void missingMotherDoesNotBecomeAReportedRelationship() throws Exception {
        var n = fixture(); ((ArrayNode) n.path("members")).remove(1); ((ArrayNode) n.path("tests")).remove(1);
        ((ArrayNode) n.path("parentage")).remove(0);
        var report = read(n); assertNull(report.memberId("MOTHER")); assertEquals(TargetTest.UNKNOWN, report.targetStatus("MOTHER"));
    }

    @TestFactory Stream<DynamicTest> detectionMissingQualityAndConflicts() {
        String[][] changes = {{"targetCoverage", "UNKNOWN", "UNKNOWN"}, {"targetCoverage", "NOT_COVERED", "UNKNOWN"},
                {"detectionCapability", "UNKNOWN", "UNKNOWN"}, {"detectionCapability", "INADEQUATE", "UNKNOWN"},
                {"quality", "UNKNOWN", "UNKNOWN"}, {"quality", "FAILED", "QUALITY_FAILED"},
                {"quality", "CONFLICT", "CONFLICT"}, {"result", "CONFLICT", "CONFLICT"},
                {"method", "UNKNOWN", "UNKNOWN"}, {"method", "OTHER", "UNKNOWN"}};
        return Stream.of(changes).map(c -> DynamicTest.dynamicTest(String.join("-", c), () -> {
            var n = fixture(); row(n, "tests", 1).put(c[0], c[1]);
            assertEquals(TargetTest.valueOf(c[2]), read(n).targetStatus("MOTHER"));
        }));
    }

    @Test void contradictoryTestsCannotPickTheHelpfulRow() throws Exception {
        var n = fixture(); ((ArrayNode) n.path("tests")).add(row(n, "tests", 1).deepCopy().put("result", "PRESENT"));
        assertEquals(TargetTest.CONFLICT, read(n).targetStatus("MOTHER"));
    }

    @Test void inconclusiveAndExcludedParentageNeverDowngradeToAssumed() throws Exception {
        var n = fixture(); row(n, "parentage", 0).put("result", "INCONCLUSIVE");
        assertEquals(Parentage.INCONCLUSIVE, read(n).parentageStatus("MOTHER"));
        row(n, "parentage", 0).put("result", "EXCLUDED");
        assertEquals(Parentage.EXCLUDED, read(n).parentageStatus("MOTHER"));
    }

    @Test void parentageRequiresRelationshipMethodAndKeepsConflicts() throws Exception {
        var n = fixture(); row(n, "parentage", 0).put("method", "OTHER");
        assertEquals(Parentage.INCONCLUSIVE, read(n).parentageStatus("MOTHER"));
        n = fixture(); ((ArrayNode) n.path("parentage")).add(row(n, "parentage", 0).deepCopy().put("result", "NOT_TESTED").put("method", "NOT_DONE"));
        assertEquals(Parentage.INCONCLUSIVE, read(n).parentageStatus("MOTHER"));
    }

    @Test void stableCaseRoleBindingsCannotBeRearranged() throws Exception {
        var n = fixture(); row(n, "members", 2).put("memberId", "mother"); rejected(n, "MEMBER_IDENTITY_CONFLICT");
        n = fixture(); row(n, "tests", 2).put("memberId", "outside"); rejected(n, "TEST_MEMBER_MISMATCH");
        n = fixture(); row(n, "parentage", 0).put("probandId", "father"); rejected(n, "PARENTAGE_MEMBER_MISMATCH");
        n = fixture(); ((ObjectNode) n.path("clinical")).put("probandId", "father"); rejected(n, "CLINICAL_MEMBER_MISMATCH");
    }

    @Test void modelDraftAndClaimedApprovalAreNotNativeReports() throws Exception {
        var n = fixture(); n.put("schemaVersion", "denovo-literature-case-candidates-v1"); rejected(n, "SCHEMA_INVALID");
        n = fixture(); n.put("sourceClass", "LITERATURE_CASE"); rejected(n, "ENUM_INVALID_sourceClass");
        n = fixture(); n.put("qualified", true); rejected(n, "UNKNOWN_FIELD_qualified");
        n = fixture(); ((ObjectNode) n.path("clinical")).put("phenotypeCategory", "HIGHLY_SPECIFIC"); rejected(n, "UNKNOWN_FIELD_phenotypeCategory");
    }

    @Test void symbolicAndInvalidTargetRejectedButReportedIndelPreserved() throws Exception {
        var n = fixture(); ((ObjectNode) n.path("target")).put("alternate", "<DEL>"); rejected(n, "TARGET_INVALID");
        n = fixture(); ((ObjectNode) n.path("target")).put("alternate", "AT");
        assertEquals("AT", read(n).snapshot().at("/target/alternate").asText());
    }

    @Test void phenotypeContradictionRetainedWithoutInventedCategory() throws Exception {
        var n = fixture(); ((ArrayNode) n.at("/clinical/absentHpo")).add("HP:0001250");
        var facts = read(n).clinical(); assertEquals(facts.presentHpo(), facts.absentHpo());
    }

    @Test void badPinAndMalformedJsonCannotEnterReader() throws Exception {
        byte[] valid = mapper.writeValueAsBytes(fixture());
        assertThrows(IllegalArgumentException.class, () -> DeNovoStructuredReport.read(valid, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> DeNovoStructuredReport.read(valid, "invalid"));
        for (String input : List.of("{\"x\":1,\"x\":2}", "{} {}", "{} // comment", "{\"é\":1,\"é\":2}")) {
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> DeNovoStructuredReport.read(bytes, FamilyDeNovoJson.sha256(bytes)));
        }
    }

    @Test void recordAndPhenotypeBoundariesAreExplicit() throws Exception {
        var n = fixture(); var records = (ArrayNode) n.path("tests");
        while (records.size() <= 30) records.add(records.get(0).deepCopy()); rejected(n, "RECORD_LIMIT");
        n = fixture(); ((ArrayNode) n.at("/clinical/presentHpo")).add("HP:bad"); rejected(n, "HPO_INVALID");
        n = fixture(); row(n, "members", 0).put("memberId", " child"); rejected(n, "IDENTIFIER_NOT_CANONICAL");
    }
}
