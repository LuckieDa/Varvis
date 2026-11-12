package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossUpstreamCoverageTest {
    @TempDir Path temporary;
    final Pvs1StartLossAutomaticPreparationTest helper = new Pvs1StartLossAutomaticPreparationTest();

    ObjectNode[] sources() throws Exception {
        var f = helper.base.fixture(1);
        var provider = new Pvs1StartLossClinVarSearchTest(); provider.root = temporary;
        var scan = new Pvs1StartLossClinVarSearch(helper.mapper, provider.properties(), (file, regions) -> List.of())
                .query(f.data().context, f.data().model, 10);
        scan.put("sourceSha256", CodingTranscriptMaterialSupply.sha(helper.mapper.writeValueAsBytes(scan)));
        var preparation = new Pvs1StartLossAutomaticPreparation(helper.mapper, () -> null,
                helper.sources(helper.document(), helper.mapper.createObjectNode()));
        return new ObjectNode[]{f.data().context, f.data().model,
                preparation.upstream(f.data().context, f.data().model, helper.sequence(f)), scan};
    }
    @Test void bothInternallyEnumeratedSourcesCanEstablishOnlyScopedAbsence() throws Exception {
        var s = sources(); var result = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]);
        assertEquals("NO_QUALIFYING_REFERENCE_WITHIN_SCOPE", result.path("status").asText(), result.toPrettyString());
        assertTrue(result.path("complete").asBoolean()); assertTrue(result.path("negativeCoverageGranted").asBoolean());
        assertFalse(result.at("/coverage/globalAbsenceClaimed").asBoolean());
        assertEquals(2, helper.calls.get());
        assertFalse(s[2].path("complete").asBoolean(), "reconciliation must not mutate the acquired original");
    }
    // Synthetic already-qualified source facts test only the reconciliation
    // boundary. Native original-source semantics are tested separately.
    ObjectNode witness(ObjectNode[] s) {
        var mapped = Pvs1StartLossReferenceMapping.map(s[1], s[1].path("transcript").asText() + ":c.4A>G");
        assertTrue(mapped.mapped());
        var row = s[2].withArray("records").addObject().put("classification", "Pathogenic")
                .put("clinvarVariationId", "123").put("alleleKey", mapped.key()).put("disposition", "UNRESOLVED");
        var nativeAssessment = row.putObject("nativeAssessment");
        nativeAssessment.putObject("curatedGrounds").put("status", "INDEPENDENT_CURATED_BASIS_ESTABLISHED");
        var linked = nativeAssessment.putObject("clinvarAssertions").put("status", "CONSISTENT_WITH_EREPO")
                .put("codingAlleleKey", mapped.key()).put("localEquivalentRecordReviewPending", true);
        int position = helper.base.helper.genomic(s[1], 4);
        linked.putObject("allele").put("position", position).put("reference", "A").put("alternate", "G");
        s[3].withArray("records").addObject().put("variationId", "123").put("position", position).put("reference", "A").put("alternate", "G");
        s[2].withObject("source").put("recordCount", 1);
        return row;
    }
    @Test void validatedWitnessIsExistenceNotAnAbsenceClaimAndIsRecheckedEachTime() throws Exception {
        var s = sources(); witness(s);
        s[2].put("sourceEnumerationComplete", false);
        s[3].withArray("records").addObject().put("variationId", "456").put("position", helper.base.helper.genomic(s[1], 5)).put("reference", "A").put("alternate", "C");
        var first = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]);
        assertEquals("WITNESS_VALIDATED", first.path("status").asText(), first.toPrettyString());
        assertEquals(1, first.path("qualifyingReferenceCount").asInt());
        assertFalse(first.path("complete").asBoolean()); assertFalse(first.path("negativeCoverageGranted").asBoolean());
        var repeat = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, first, s[3]);
        assertEquals(first, repeat);
        s[3].put("complete", false);
        var unavailable = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, first, s[3]);
        assertEquals("INCOMPLETE", unavailable.path("status").asText());
        assertEquals(0, unavailable.path("qualifyingReferenceCount").asInt());
        assertFalse(unavailable.at("/records/0/nativeAssessment/independenceEstablished").asBoolean());
        assertEquals("UNRESOLVED", unavailable.at("/records/0/disposition").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"alias", "idMismatch", "unmapped", "conflict", "unknown", "differentAllele", "missingGrounds"})
    void unresolvedRelevantRecordsCannotBePromoted(String change) throws Exception {
        var s = sources(); var row = witness(s); var local = (ObjectNode) s[3].at("/records/0");
        switch (change) {
            case "alias" -> local.put("variationId", "789");
            case "idMismatch" -> local.put("position", helper.base.helper.genomic(s[1], 5));
            case "unmapped" -> local.put("alternate", "<DEL>");
            case "differentAllele" -> row.withObject("nativeAssessment").withObject("clinvarAssertions").put("codingAlleleKey", "f".repeat(64));
            case "missingGrounds" -> row.withObject("nativeAssessment").remove("curatedGrounds");
            default -> s[2].withArray("records").addObject().put("alleleKey", row.path("alleleKey").asText())
                    .put("classification", change.equals("conflict") ? "Benign" : "unknown").put("disposition", "UNRESOLVED");
        }
        var result = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]);
        assertEquals(0, result.path("qualifyingReferenceCount").asInt(), result.toPrettyString());
        assertEquals("INCOMPLETE", result.path("status").asText());
    }
    @Test void duplicateWitnessIsCountedOnceAndWithdrawnConflictingAssertionIsRetainedButExcluded() throws Exception {
        var s = sources(); var row = witness(s);
        s[2].withArray("records").add(row.deepCopy());
        s[2].withArray("records").addObject().put("alleleKey", row.path("alleleKey").asText())
                .put("classification", "Benign").put("disposition", "EXCLUDED").put("reasonCode", "WITHDRAWN_EXCLUDED");
        var result = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]);
        assertEquals(1, result.path("qualifyingReferenceCount").asInt());
        assertEquals("DUPLICATE_QUALIFYING", result.at("/records/1/disposition").asText());
        assertEquals(3, result.path("records").size());
    }
    @Test void overlappingDeletionCannotAliasASnvButTheSameIdentifierConflictStillBlocks() throws Exception {
        var s = sources(); witness(s);
        int position = helper.base.helper.genomic(s[1], 3);
        var deletion = s[3].withArray("records").addObject().put("variationId", "789").put("position", position)
                .put("reference", "GAAACCCATGAAATAA").put("alternate", "G");
        // This edit crosses coding exons and is unresolved as a candidate. Its
        // nonzero length change proves it is not an alias of the SNV witness.
        assertEquals("WITNESS_VALIDATED", Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]).path("status").asText());
        deletion.put("variationId", "123");
        var conflict = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]);
        assertEquals("INCOMPLETE", conflict.path("status").asText());
        assertEquals("123", conflict.at("/records/0/nativeAssessment/clinvarAssertions/localEquivalentRecordIssues/0/variationId").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"mondoId", "moi"})
    void missingBackgroundIsNotAnExplicitlyDifferentDisease(String field) throws Exception {
        var s = sources(); var f = helper.base.fixture(1); var row = helper.summary(f, 4, "Pathogenic"); row.remove(field);
        var preparation = new Pvs1StartLossAutomaticPreparation(helper.mapper, () -> null,
                helper.sources(helper.document(row), helper.mapper.createObjectNode()));
        var upstream = preparation.upstream(s[0], s[1], helper.sequence(f));
        assertEquals("UNRESOLVED", upstream.at("/records/0/disposition").asText());
        assertEquals("BACKGROUND_UNRESOLVED", upstream.at("/records/0/reasonCode").asText());
        assertFalse(Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, upstream, s[3]).path("complete").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings = {"missingClinvar", "wrongInterval", "indel", "missingVersion", "missingSha",
            "failedPage", "recordCount", "unresolved", "conflict", "unrecognisedExclusion", "staleNegative"})
    void incompleteOrPotentiallyRelevantFindingsCannotBecomeSupporting(String change) throws Exception {
        var s = sources();
        switch (change) {
            case "missingClinvar" -> s[3].removeAll();
            case "wrongInterval" -> s[3].putArray("regions").add("1:1-3");
            case "indel" -> s[3].withArray("records").addObject().put("reference", "AT").put("alternate", "A");
            case "missingVersion" -> s[3].withObject("resource").remove("version");
            case "missingSha" -> s[3].remove("sourceSha256");
            case "failedPage" -> s[2].withObject("source").put("sourceEnumerationComplete", false);
            case "recordCount" -> s[2].withObject("source").put("recordCount", 1);
            case "staleNegative" -> {
                s[2].put("complete", true).put("negativeCoverageGranted", true).put("status", "NO_QUALIFYING_REFERENCE_WITHIN_SCOPE");
                s[3].removeAll();
            }
            default -> {
                s[2].withObject("source").put("recordCount", 1);
                s[2].withArray("records").addObject().put("disposition", change.equals("conflict") ? "CONFLICT" : change.equals("unresolved") ? "UNRESOLVED" : "EXCLUDED")
                        .put("reasonCode", "UNSUPPORTED");
            }
        }
        var result = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]);
        assertFalse(result.path("complete").asBoolean(), result.toPrettyString());
        assertFalse(result.path("negativeCoverageGranted").asBoolean());
        assertFalse(result.at("/coverage/unresolvedReasons").isEmpty());
    }
}
