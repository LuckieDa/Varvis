package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Source-byte and live-token integration only; the reference and observed RNA are explicitly synthetic. */
class Pvs1FullRnaSelectedPreparationTest {
    @TempDir Path root;
    record Data(Pvs1FullRnaSourceProducerTest.Data source, ObjectNode document) { }

    private Data data(int strand) throws Exception {
        var d = Pvs1FullRnaSourceProducerTest.createNeutralSourceFixture(root.resolve("strand-" + strand), strand);
        var document = new Pvs1NmdBusinessFixture(d.f().mapper, false).document();
        var entry = document.withObject("materials").putArray("pvs1FullRnaOriginals").addObject().put("entryId", "TEST_ONLY_RNA");
        entry.set("allele", d.f().context.at("/variant/evidenceQueryAllele").deepCopy());
        entry.put("transcript", d.f().model.path("transcript").asText()); entry.set("binding", d.binding().deepCopy());
        return new Data(d, document);
    }
    private ReviewedRuleBundle bundle(Data d) throws Exception {
        var f = d.source().f(); Path file = d.source().originals().resolve("bundle.json");
        byte[] raw = f.mapper.writeValueAsBytes(d.document()); Files.write(file, raw);
        return ReviewedRuleBundle.loadPvs1(f.mapper, file, CodingTranscriptMaterialSupply.sha(raw), f.adapter.canonicalSha256(d.document()),
                new Pvs1TranscriptMaterialSupply(f.mapper, f.supply));
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void pinnedBundleActuallyReadsOriginalAndRetainsLiveTypedMaterialWithoutScoring(int strand) throws Exception {
        var d = data(strand);var f = d.source().f();var bundle = bundle(d);
        var prepared = bundle.prepareFullRna(f.context, f.candidate);
        assertTrue(prepared.ready(), prepared.snapshot().toString());var source = prepared.source();assertNotNull(source);
        assertEquals(Pvs1FullRnaSourceProducerTest.COMPLETE, source.result().alteredRna());
        assertEquals(d.source().binding().at("/sourceRecord/sha256"), prepared.snapshot().at("/sourceReplay/originalSha256"));
        assertEquals(0, bundle.pvs1EvaluatorCalls());assertEquals(0, bundle.pvs1RuntimeCalls());assertEquals(0, bundle.pvs1ProjectionCalls());
        var publicFields = prepared.publicSummary(f.mapper);
        assertEquals("READY", publicFields.path("status").asText());
        assertEquals("NOT_RUN_BY_NEUTRAL_PREPARATION", publicFields.path("criterionConsumerStatus").asText());
        assertEquals(source.facts().mechanicalEffectSha256(), publicFields.at("/binding/mechanicalEffectSha256").asText());
        assertFalse(publicFields.path("criterionAdoptionGranted").asBoolean(true));assertFalse(publicFields.has("adoptedStrength"));
        String text = publicFields.toString();assertFalse(text.contains(CodingTranscriptRnaFixture.DEFAULT_RNA));
        assertFalse(text.contains(Pvs1FullRnaSourceProducerTest.COMPLETE));assertFalse(text.contains("relativePath"));assertFalse(text.contains("structuredOriginal"));
        var live = prepared.bindSelected("a".repeat(64), "b".repeat(64));
        assertSame(source, live.source());assertTrue(live.matchesSelected(f.mapper, f.context, "a".repeat(64), "b".repeat(64)));
        assertFalse(live.matchesSelected(f.mapper, f.context, "c".repeat(64), "b".repeat(64)));
        var changed = f.context.deepCopy().put("contextId", "changed");
        assertFalse(live.matchesSelected(f.mapper, changed, "a".repeat(64), "b".repeat(64)));
        var archive = live.privateSnapshot(f.mapper);
        assertEquals(d.source().original(), archive.path("sourceOriginal"));
        assertEquals(source.facts().snapshot(), archive.path("consequenceFacts"));
        assertEquals(source.result().snapshot(f.mapper), archive.path("rnaEditFacts"));
        assertFalse(archive.path("restoresLiveQualification").asBoolean(true));
        archive.withObject("sourceOriginal").removeAll();assertFalse(live.privateSnapshot(f.mapper).path("sourceOriginal").isEmpty());
        assertThrows(Exception.class, () -> f.mapper.readValue(f.mapper.writeValueAsBytes(prepared.snapshot()), ReviewedRuleBundle.FullRnaPrepared.class));
        assertTrue(bundle.identity().at("/pvs1Capabilities/fullRnaPreparation/materialIndexSha256").asText().matches("[0-9a-f]{64}"));
    }
    @Test void changedSourceBytesCannotBeRestoredByEarlierPreparedJsonOrRepeatedLookup() throws Exception {
        var d = data(1);var f = d.source().f();var bundle = bundle(d);
        assertTrue(bundle.prepareFullRna(f.context, f.candidate).ready());
        Files.writeString(d.source().originals().resolve("original.json"), "{}");
        var again = bundle.prepareFullRna(f.context, f.candidate);
        assertFalse(again.ready());assertNull(again.source());
        assertEquals("FULL_RNA_SOURCE_ORIGINAL_SHA_MISMATCH", again.snapshot().path("reasonCode").asText());
        assertEquals(0, bundle.pvs1EvaluatorCalls());
    }
    @Test void onePinnedReportCanShowMultipleOutcomesWithoutPublishingASelectedProduct() throws Exception {
        var d = data(1);
        var f = d.source().f();
        d.source().original().withObject("assay").put("outcomeMultiplicity", "MULTIPLE_OUTCOMES")
                .put("residualNormalTranscript", "DETECTED_IN_ASSESSED_SCOPE");
        Pvs1FullRnaSourceProducerTest.repinNeutralSourceFixture(d.source());
        ((ObjectNode) d.document().at("/materials/pvs1FullRnaOriginals/0"))
                .set("binding", d.source().binding().deepCopy());

        var prepared = bundle(d).prepareFullRna(f.context, f.candidate);
        var summary = prepared.publicSummary(f.mapper);
        assertFalse(prepared.ready());
        assertNull(prepared.source());
        assertEquals("FULL_RNA_SOURCE_MULTIPLE_OUTCOMES_UNRESOLVED", summary.path("reasonCode").asText());
        assertEquals("MULTIPLE_OUTCOMES", summary.at("/assayConditions/outcomeMultiplicity").asText());
        assertEquals("DETECTED_IN_ASSESSED_SCOPE", summary.at("/assayConditions/residualNormalTranscript").asText());
        assertEquals(d.source().binding().at("/sourceRecord/sha256"), summary.at("/sourceRecord/sha256"));
        assertFalse(summary.has("sequenceEffect"));
        assertFalse(summary.path("criterionAdoptionGranted").asBoolean(true));
    }

    @Test void multipleExactSourcesAreAConflictRatherThanChoosingOneOutcome() throws Exception {
        var d = data(1);var entries = d.document().withObject("materials").withArray("pvs1FullRnaOriginals");
        entries.add(((ObjectNode) entries.get(0)).deepCopy().put("entryId", "SECOND"));
        int calls = d.source().f().referenceCalls;
        var result = bundle(d).prepareFullRna(d.source().f().context, d.source().f().candidate);
        assertFalse(result.ready());assertEquals("FULL_RNA_SOURCE_INDEX_MULTIPLE_MATCHES", result.snapshot().path("reasonCode").asText());
        assertEquals(calls, d.source().f().referenceCalls);
    }
    @ParameterizedTest @ValueSource(strings = {"ALLELE", "TRANSCRIPT"})
    void onlyExactAlleleAndTranscriptAreRead(String field) throws Exception {
        var d = data(1);var context = d.source().f().context.deepCopy();
        if (field.equals("ALLELE")) context.withObject("/variant/evidenceQueryAllele").put("position", 99999);
        else context.withObject("transcript").put("version", 999);
        int calls = d.source().f().referenceCalls;
        var result = bundle(d).prepareFullRna(context, d.source().f().candidate);
        assertFalse(result.ready());assertEquals("NOT_FOUND", result.snapshot().path("status").asText());
        assertEquals("FULL_RNA_SOURCE_EXACT_ALLELE_TRANSCRIPT_NOT_FOUND", result.snapshot().path("reasonCode").asText());
        assertEquals(calls, d.source().f().referenceCalls);
    }
    @Test void missingConfigurationLeavesLegacyIdentityDecisionsAndReferenceCallsUntouched() throws Exception {
        var d = data(1);d.document().withObject("materials").remove("pvs1FullRnaOriginals");
        var b = bundle(d);var before = b.identity();int calls = d.source().f().referenceCalls;
        var prepared = b.prepareFullRna(d.source().f().context, d.source().f().candidate);
        assertFalse(prepared.configured());assertFalse(prepared.ready());assertEquals(before, b.identity());
        assertFalse(before.at("/pvs1Capabilities").has("fullRnaPreparation"));assertEquals(calls, d.source().f().referenceCalls);
        assertEquals(0, b.pvs1EvaluatorCalls());
    }
    @Test void publicProjectionNeverCopiesRawRnaOrRestoresAuthority() throws Exception {
        var d = data(1);var summary = bundle(d).prepareFullRna(d.source().f().context, d.source().f().candidate).publicSummary(d.source().f().mapper);
        summary.put("alteredRnaSequence", "DO_NOT_COPY").put("criterionAdoptionGranted", true);
        summary.withObject("sequenceEffect").put("alteredRnaSequence", "DO_NOT_COPY");
        var result = d.source().f().mapper.createObjectNode();result.set("fullRnaPreparation", summary);
        var actual = Pvs1SelectedBatch.publicSummary(d.source().f().mapper, result).path("fullRnaPreparation");
        assertFalse(actual.toString().contains("DO_NOT_COPY"));assertFalse(actual.path("criterionAdoptionGranted").asBoolean(true));
        assertFalse(actual.path("functionalQualificationGranted").asBoolean(true));
        for (String key : List.of("schemaVersion", "methodVersion", "status", "binding", "sequenceEffect", "topology")) assertTrue(actual.has(key), key);
    }
    @ParameterizedTest @ValueSource(strings = {"AUTOMATIC", "EXPERT", "START_LOSS"})
    void neutralPreparationSurvivesExistingStartLossPublicSummaryPaths(String kind) throws Exception {
        var d = data(1);var summary = bundle(d).prepareFullRna(d.source().f().context, d.source().f().candidate).publicSummary(d.source().f().mapper);
        var result = d.source().f().mapper.createObjectNode();result.set("fullRnaPreparation", summary);
        result.putObject(kind.equals("AUTOMATIC") ? "automaticStartLossFacts" : kind.equals("EXPERT") ? "expertStartLossFacts" : "startLossFacts");
        var actual = Pvs1SelectedBatch.publicSummary(d.source().f().mapper, result).path("fullRnaPreparation");
        assertEquals(summary, actual);assertFalse(actual.path("criterionAdoptionGranted").asBoolean(true));
    }
}
