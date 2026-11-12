package org.nbscn.varvis.variantanalysis.provider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic secondary VCVs exercise conflict review, never independent P evidence. */
class Pvs1StartLossEquivalentRecordTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings = {"Pathogenic", "Likely pathogenic", "Pathogenic/Likely pathogenic"})
    void sameAlleleSecondaryRecordIsActuallyReviewedWithoutAddingEvidence(String classification) throws Exception {
        var helper = new Pvs1StartLossUpstreamCoverageTest(); helper.temporary = temporary;
        var s = helper.sources(); helper.witness(s);
        s[3].withArray("records").add(s[3].at("/records/0").deepCopy());
        ((com.fasterxml.jackson.databind.node.ObjectNode) s[3].at("/records/1")).put("variationId", "456");
        String xml = new Pvs1StartLossClinVarCandidatesTest().xml(s, classification)
                .replace("123", "456");
        var calls = new AtomicInteger();
        var source = new Pvs1StartLossPublicSources(helper.helper.mapper, true, (uri, timeout, maximum) -> {
            calls.incrementAndGet(); assertTrue(uri.toString().endsWith("VCV000000456"));
            return new ProteinRegionHttpTransport.Response(200, Map.of(), xml.getBytes(StandardCharsets.UTF_8));
        });
        var result = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3], source);
        assertEquals("WITNESS_VALIDATED", result.path("status").asText(), result.toPrettyString());
        assertEquals(1, result.path("qualifyingReferenceCount").asInt());
        var review = result.at("/records/0/nativeAssessment/clinvarAssertions/localEquivalentRecordReviews/0");
        assertEquals("NO_ADVERSE_CURRENT_ASSERTION", review.path("status").asText());
        assertFalse(review.path("independentPathogenicityGranted").asBoolean());
        assertEquals(0, review.path("additionalEvidenceCount").asInt(-1));
        assertEquals(result, Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, result, s[3], source));
        assertEquals(1, calls.get());
        assertFalse(result.path("negativeCoverageGranted").asBoolean());
    }

    @ParameterizedTest @ValueSource(strings = {"Benign", "Uncertain significance", "unknownDisease", "wrongAllele", "missingDate", "unavailable"})
    void adverseUnknownOrMismatchedSecondarySourcesKeepWitnessUnresolved(String change) throws Exception {
        var helper = new Pvs1StartLossUpstreamCoverageTest(); helper.temporary = temporary;
        var s = helper.sources(); helper.witness(s);
        ((com.fasterxml.jackson.databind.node.ObjectNode) s[3].at("/records/0")).put("variationId", "456");
        String xml = new Pvs1StartLossClinVarCandidatesTest().xml(s,
                change.equals("Benign") || change.equals("unknownDisease") ? "Benign" : change.equals("Uncertain significance") ? change : "Pathogenic")
                .replace("123", "456");
        if (change.equals("unknownDisease")) xml = xml.replace("DB=\"MONDO\"", "DB=\"Unknown\"");
        if (change.equals("wrongAllele")) xml = xml.replace("alternateAlleleVCF=\"G\"", "alternateAlleleVCF=\"T\"");
        if (change.equals("missingDate")) xml = xml.replace("DateLastEvaluated=\"2026-01-01\"", "");
        final byte[] raw = xml.getBytes(StandardCharsets.UTF_8);
        var source = new Pvs1StartLossPublicSources(helper.helper.mapper, true, (uri, timeout, maximum) ->
                new ProteinRegionHttpTransport.Response(change.equals("unavailable") ? 503 : 200, Map.of(), raw));
        var result = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3], source);
        assertEquals(0, result.path("qualifyingReferenceCount").asInt());
        assertFalse(result.path("negativeCoverageGranted").asBoolean());
        assertFalse(result.at("/records/0/nativeAssessment/clinvarAssertions/localEquivalentRecordIssues").isEmpty());
    }

    @Test void callerSuppliedAliasReviewCannotReplaceSourceRead() throws Exception {
        var helper = new Pvs1StartLossUpstreamCoverageTest(); helper.temporary = temporary;
        var s = helper.sources(); helper.witness(s);
        var local = (com.fasterxml.jackson.databind.node.ObjectNode) s[3].at("/records/0");
        local.put("variationId", "456").putObject("aliasReview").put("status", "NO_ADVERSE_CURRENT_ASSERTION");
        assertEquals(0, Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]).path("qualifyingReferenceCount").asInt());
    }
}
