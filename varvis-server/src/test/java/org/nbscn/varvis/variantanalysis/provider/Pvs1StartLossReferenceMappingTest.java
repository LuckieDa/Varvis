package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic geometry tests; no scientific pathogenicity assertion. */
class Pvs1StartLossReferenceMappingTest {
    final Pvs1StartLossRuntimeTest base = new Pvs1StartLossRuntimeTest();
    Pvs1StartLossReferenceMapping.Result map(ObjectNode model, String edit) {
        return Pvs1StartLossReferenceMapping.map(model, model.path("transcript").asText() + ":c." + edit);
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void equivalentRepeatEditsAndDistinctSubstitutions(int strand) throws Exception {
        var model = base.fixture(strand).data().model;
        var deletion = map(model, "4del");
        assertTrue(deletion.mapped(), deletion.reason());
        assertEquals(deletion.key(), map(model, "6delA").key());
        assertEquals(4, deletion.first()); assertEquals(6, deletion.last());
        assertEquals(map(model, "4dup").key(), map(model, "5_6insA").key());
        assertTrue(map(model, "4dup").mapped());
        assertEquals(map(model, "4A>G").key(), map(model, "4delinsG").key());
        assertNotEquals(map(model, "4A>G").key(), map(model, "5A>G").key());
        assertTrue(map(model, "4_5delinsGC").mapped());
    }
    @Test void uncertainOrInvalidDescriptionsNeverMap() throws Exception {
        var model = base.fixture(1).data().model;
        for (String edit : new String[]{"4delG", "4dupG", "4G>A", "4A>A", "4_6insA", "4_4del", "4+1del", "(4del)", "999999999999del", "8_11del", "9dup", "18dup"})
            assertFalse(map(model, edit).mapped(), edit);
        assertFalse(Pvs1StartLossReferenceMapping.map(model, "NM_000001.999:c.4del").mapped());
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void forwardGenomicAnchoredEditsShareCodingIdentityOnBothStrands(int strand) throws Exception {
        var model = base.fixture(strand).data().model;
        int p = base.helper.genomic(model, 4);
        String ref = strand == 1 ? "A" : "T", alt = strand == 1 ? "G" : "C";
        assertEquals(map(model, "4A>G").key(), Pvs1StartLossReferenceMapping.genomic(model, p, ref, alt).key());
        int left = Math.min(base.helper.genomic(model, 3), base.helper.genomic(model, 4));
        String deletionRef = strand == 1 ? "GA" : "TC", deletionAlt = strand == 1 ? "G" : "C";
        var deletion = Pvs1StartLossReferenceMapping.genomic(model, left, deletionRef, deletionAlt);
        assertTrue(deletion.mapped(), deletion.reason());
        assertEquals(map(model, "4del").key(), deletion.key());
        assertFalse(Pvs1StartLossReferenceMapping.genomic(model, p, "C", "A").mapped());
        assertFalse(Pvs1StartLossReferenceMapping.genomic(model, p, ref, "<DEL>").mapped());
    }
    @Test void repeatAtExonBoundaryDoesNotClaimNormalization() throws Exception {
        var model = base.fixture(1).data().model;
        assertEquals("REFERENCE_NORMALIZATION_BOUNDARY_UNRESOLVED", map(model, "7del").reason());
    }
    @Test void equivalentNativeRowsShareConflictButDoNotAcquireIndependence() throws Exception {
        var helper = new Pvs1StartLossAutomaticPreparationTest();
        var f = base.fixture(1);
        var p = helper.summary(f, 4, "Pathogenic");
        var vus = helper.summary(f, 6, "Uncertain Significance");
        String tx = f.data().model.path("transcript").asText();
        p.putArray("hgvs").add(tx + ":c.4del").add(tx + ":c.6delA");
        vus.putArray("hgvs").add(tx + ":c.5del");
        var prep = new Pvs1StartLossAutomaticPreparation(base.mapper, () -> { throw new AssertionError(); },
                helper.sources(helper.document(p, vus), helper.original(p, "PVS1_Strong")));
        var out = prep.upstream(f.data().context, f.data().model, helper.sequence(f));
        assertEquals("REFERENCE_CLASSIFICATION_CONFLICT", out.at("/records/0/reasonCode").asText());
        assertEquals("REFERENCE_CLASSIFICATION_CONFLICT", out.at("/records/1/reasonCode").asText());
        assertFalse(out.at("/records/0/nativeAssessment/independenceEstablished").asBoolean());
        assertFalse(out.path("negativeCoverageGranted").asBoolean());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void intronicSnvScopeRequiresRealAdjacentExonBoundariesAndConsistentDescriptions(int strand) throws Exception {
        var model = base.fixture(strand).data().model;
        String tx = model.path("transcript").asText() + ":c.";
        int start = base.helper.genomic(model, 9), end = base.helper.genomic(model, 10);
        int gap = Math.abs(end - start) - 1;
        var descriptions = base.mapper.createArrayNode().add(tx + "9+2A>G").add(tx + "10-" + (gap - 1) + "A>G");
        var mapped = Pvs1StartLossReferenceMapping.intronicLocation(model, descriptions);
        assertTrue(mapped.resolved(), mapped.reason()); assertEquals(start + strand * 2, mapped.position());
        String accession = strand == 1 ? "NC_000001.11" : "NC_000002.12";
        String genomicEdit = mapped.position() + (strand == 1 ? "A>G" : "T>C");
        assertTrue(Pvs1StartLossReferenceMapping.intronicLocation(model,
                descriptions.deepCopy().add(accession + ":g." + genomicEdit)).resolved());
        for (String conflictingGenomic : new String[]{accession + ":g.1A>G", accession + ":g." + mapped.position() + "del",
                "NC_000003.12:g." + genomicEdit})
            assertEquals("REFERENCE_REPRESENTATION_CONFLICT", Pvs1StartLossReferenceMapping.intronicLocation(model,
                    descriptions.deepCopy().add(conflictingGenomic)).reason());
        for (String invalid : new String[]{"8+2A>G", "10+2A>G", "9-2A>G", "9+" + (gap + 1) + "A>G",
                "9+0A>G", "9+2A>A", "9+2del", "9+2_10-2del", "(9+2A>G)", "999999999999+2A>G", "9C>A"})
            assertFalse(Pvs1StartLossReferenceMapping.intronicLocation(model, base.mapper.createArrayNode().add(tx + invalid)).resolved(), invalid);
        assertFalse(Pvs1StartLossReferenceMapping.intronicLocation(model,
                base.mapper.createArrayNode().add("NM_999999.1:c.9+2A>G")).resolved());
        for (String conflict : new String[]{"9+3A>G", "9+2A>C", "4A>G", "9+2del"})
            assertFalse(Pvs1StartLossReferenceMapping.intronicLocation(model, descriptions.deepCopy().add(tx + conflict)).resolved(), conflict);
        ((ObjectNode) model.at("/exons/1")).put("cdsStart", 11);
        assertFalse(Pvs1StartLossReferenceMapping.intronicLocation(model, descriptions).resolved());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void outsideCodingScopeDoesNotFetchInterpretationOrClaimSpliceHarmlessness(int strand) throws Exception {
        var helper = new Pvs1StartLossAutomaticPreparationTest(); var f = base.fixture(strand);
        var row = helper.summary(f, 4, "Pathogenic"); String tx = f.data().model.path("transcript").asText();
        row.putArray("hgvs").add(tx + ":c.9+2A>G");
        var prep = new Pvs1StartLossAutomaticPreparation(base.mapper, () -> null,
                helper.sources(helper.document(row), base.mapper.createObjectNode()));
        var result = prep.upstream(f.data().context, f.data().model, helper.sequence(f));
        assertEquals("OUTSIDE_UPSTREAM_INTERVAL", result.at("/records/0/reasonCode").asText());
        assertEquals("EXCLUDED", result.at("/records/0/disposition").asText());
        assertFalse(result.at("/records/0/scopeExclusion/referenceAlleleVerified").asBoolean());
        assertFalse(result.at("/records/0/scopeExclusion/spliceEffectAssessed").asBoolean());
        assertEquals(2, helper.calls.get()); assertFalse(result.path("negativeCoverageGranted").asBoolean());
        row.withArray("hgvs").add(tx + ":c.4A>G");
        var conflicting = new Pvs1StartLossAutomaticPreparation(base.mapper, () -> null,
                helper.sources(helper.document(row), base.mapper.createObjectNode()))
                .upstream(f.data().context, f.data().model, helper.sequence(f));
        assertEquals("UNRESOLVED", conflicting.at("/records/0/disposition").asText());
        assertFalse(conflicting.at("/records/0").has("scopeExclusion"));
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void intronicDeletionNeedsWholeSpanAndIndependentNativeGenomicAgreement(int strand) throws Exception {
        var model = base.fixture(strand).data().model;
        String tx = model.path("transcript").asText() + ":c.";
        int origin = base.helper.genomic(model, 9), other = base.helper.genomic(model, 10);
        int gap = Math.abs(other - origin) - 1;
        int lo = Math.min(origin + strand * 2, origin + strand * 5), hi = Math.max(origin + strand * 2, origin + strand * 5);
        String nc = strand == 1 ? "NC_000001.11" : "NC_000002.12";
        var hgvs = base.mapper.createArrayNode().add(tx + "9+2_9+5del")
                .add(tx + "10-" + (gap - 1) + "_10-" + (gap - 4) + "del")
                .add(nc + ":g." + lo + "_" + hi + "del");
        var out = Pvs1StartLossReferenceMapping.intronicDeletion(model, hgvs);
        assertTrue(out.resolved(), out.reason()); assertEquals(lo, out.first()); assertEquals(hi, out.last());
        for (String edit : new String[]{"9+5_9+2del", "9+2_9+2del", "9+2_10+2del", "8+2_8+5del",
                "9+2_9+999del", "9+2_9+5delinsA", "9+2_9+5dup", "9+2_9+5delAAAA", "(9+2_9+5del)", "9+2_9+999999999999del", "9_10del"}) {
            var bad = base.mapper.createArrayNode().add(tx + edit).add(nc + ":g." + lo + "_" + hi + "del");
            assertFalse(Pvs1StartLossReferenceMapping.intronicDeletion(model, bad).resolved(), edit);
        }
        assertEquals("REFERENCE_INTRON_GENOMIC_CONFIRMATION_REQUIRED", Pvs1StartLossReferenceMapping.intronicDeletion(model,
                base.mapper.createArrayNode().add(tx + "9+2_9+5del")).reason());
        for (String conflict : new String[]{tx + "9+2_9+4del", tx + "4A>G", nc + ":g." + lo + "_" + (hi + 1) + "del",
                "NC_000003.12:g." + lo + "_" + hi + "del"})
            assertFalse(Pvs1StartLossReferenceMapping.intronicDeletion(model, hgvs.deepCopy().add(conflict)).resolved(), conflict);
        assertFalse(Pvs1StartLossReferenceMapping.intronicDeletion(model,
                base.mapper.createArrayNode().add("NM_000001.999:c.9+2_9+5del").add(nc + ":g." + lo + "_" + hi + "del")).resolved());
        ((ObjectNode) model.at("/exons/1")).put("cdsStart", 11);
        assertFalse(Pvs1StartLossReferenceMapping.intronicDeletion(model, hgvs).resolved());
    }

    @Test void intronicDeletionIsOnlyScopeExcludedAndKeepsMissingCoverage() throws Exception {
        var helper = new Pvs1StartLossAutomaticPreparationTest(); var f = base.fixture(1);
        int start = base.helper.genomic(f.data().model, 9) + 2, end = start + 3;
        var row = helper.summary(f, 4, "Pathogenic");
        row.putArray("hgvs").add(f.data().model.path("transcript").asText() + ":c.9+2_9+5del")
                .add("NC_000001.11:g." + start + "_" + end + "del");
        var preparation = new Pvs1StartLossAutomaticPreparation(base.mapper, () -> null,
                helper.sources(helper.document(row), base.mapper.createObjectNode()));
        var out = preparation.upstream(f.data().context, f.data().model, helper.sequence(f));
        assertEquals("EXCLUDED", out.at("/records/0/disposition").asText());
        assertEquals("EXACT_TRANSCRIPT_INTRON_DELETION_GEOMETRY_V1", out.at("/records/0/scopeExclusion/method").asText());
        assertEquals(end, out.at("/records/0/scopeExclusion/genomicEnd").asInt());
        assertFalse(out.at("/records/0/scopeExclusion/referenceAlleleVerified").asBoolean());
        assertFalse(out.at("/records/0/scopeExclusion/spliceEffectAssessed").asBoolean());
        assertFalse(out.path("negativeCoverageGranted").asBoolean()); assertEquals(2, helper.calls.get());
        row.withArray("hgvs").add("NC_000001.11:g." + start + "_" + (end + 1) + "del");
        var rejected = new Pvs1StartLossAutomaticPreparation(base.mapper, () -> null,
                helper.sources(helper.document(row), base.mapper.createObjectNode()))
                .upstream(f.data().context, f.data().model, helper.sequence(f));
        assertEquals("REFERENCE_REPRESENTATION_CONFLICT", rejected.at("/records/0/reasonCode").asText());
        assertEquals("UNRESOLVED", rejected.at("/records/0/disposition").asText());
    }

}
