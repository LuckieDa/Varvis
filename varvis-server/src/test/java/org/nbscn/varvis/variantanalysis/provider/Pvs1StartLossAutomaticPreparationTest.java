package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native data shapes are synthetic; no invented scientific review/positive material. */
class Pvs1StartLossAutomaticPreparationTest {
    @TempDir Path root;
    final Pvs1StartLossRuntimeTest base = new Pvs1StartLossRuntimeTest();
    final com.fasterxml.jackson.databind.ObjectMapper mapper = base.mapper;
    final AtomicInteger calls = new AtomicInteger();

    @Test void repeatedPublicProjectionPreservesMechanismFactsAndOriginalRecordCounts() {
        var raw = mapper.createObjectNode();
        var findings = raw.putObject("mechanismAssessment").putArray("findings");
        for (int i = 0; i < 25; i++) findings.addObject().put("status", "CURATED_MECHANISM_SUPPORTED")
                .putObject("sourceRecord").put("molecular mechanism support", "evidence").put("confidence", "definitive");
        var first = Pvs1StartLossAutomaticPreparation.publicSummary(mapper, raw);
        var second = Pvs1StartLossAutomaticPreparation.publicSummary(mapper, first);
        assertEquals(first, second);
        assertEquals(25, second.at("/mechanismAssessment/totalRecords").asInt());
        assertEquals("evidence", second.at("/mechanismAssessment/findings/0/sourceSupport").asText());
        assertEquals(25, raw.at("/mechanismAssessment/findings").size());
    }

    ObjectNode summary(Pvs1StartLossRuntimeTest.Fixture f, int cds, String classification) {
        var row = mapper.createObjectNode().put("uuid", java.util.UUID.nameUUIDFromBytes((cds + classification).getBytes(StandardCharsets.UTF_8)).toString()).put("caId", "CA1234").put("gene", f.data().model.path("geneSymbol").asText())
                .put("mondoId", f.data().context.at("/disease/mondoId").asText()).put("moi", "Autosomal dominant inheritance")
                .put("classification", classification).put("retracted", false).put("approvedDate", "2024-01-01").put("publishedDate", "2024-02-01");
        String ref = f.data().model.path("cdsSequence").asText().substring(cds - 1, cds), alt = ref.equals("A") ? "G" : "A";
        row.putArray("hgvs").add(f.data().model.path("transcript").asText() + ":c." + cds + ref + ">" + alt);
        return row;
    }
    ObjectNode original(ObjectNode row, String code) {
        var out = mapper.createObjectNode().put("type", "VariantPathogenicityInterpretation").put("uuid", row.path("uuid").asText());
        out.putObject("condition").set("id", row.path("mondoId"));
        out.putObject("variant").put("id", "CAR:CA1234");
        out.putObject("statementOutcome").set("label", row.path("classification"));
        var item = out.putArray("evidenceLine").addObject().putArray("evidenceItem").addObject().put("type", "CriterionAssessment");
        item.putObject("criterion").put("label", code).putObject("defaultStrength").put("label", "Pathogenic Strong");
        item.putObject("statementOutcome").put("label", "Met"); return out;
    }
    ObjectNode document(ObjectNode... rows) {
        var out = mapper.createObjectNode(); out.putObject("status").put("code", 200);
        var data = out.putArray("data"); for (var row : rows) data.add(row); return out;
    }
    Pvs1StartLossPublicSources sources(ObjectNode summary, ObjectNode original) {
        return new Pvs1StartLossPublicSources(mapper, true, (url, timeout, max) -> {
            calls.incrementAndGet();
            assertTrue(java.util.Set.of("erepo.genome.network", "api.ncbi.nlm.nih.gov").contains(url.getHost()));
            if ("api.ncbi.nlm.nih.gov".equals(url.getHost()))
                return new ProteinRegionHttpTransport.Response(200, Map.of(), mapper.writeValueAsBytes(mapper.createObjectNode()));
            assertFalse(url.toString().contains("patient"));
            return new ProteinRegionHttpTransport.Response(200, Map.of(), mapper.writeValueAsBytes(url.getPath().contains("/interpretation/") ? original : summary));
        });
    }
    ObjectNode sequence(Pvs1StartLossRuntimeTest.Fixture f) {
        return Pvs1StartLossResolver.sequence(mapper, f.data().context, f.data().candidate.getTranscriptAnnotations().get(0), f.data().model);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void exactUpstreamIntervalSelfBoundaryAndRawInterpretationAreHandledInBothOrientations(int strand) throws Exception {
        var f = base.fixture(strand); var positive = summary(f, 4, "Pathogenic");
        var source = sources(document(summary(f, 1, "Pathogenic"), positive, summary(f, 10, "Pathogenic"), summary(f, 7, "Likely Pathogenic")), original(positive, "PVS1_Strong"));
        var prep = new Pvs1StartLossAutomaticPreparation(mapper, () -> { throw new AssertionError("unneeded"); }, source);
        var result = prep.upstream(f.data().context, f.data().model, sequence(f));
        assertEquals("TARGET_EXCLUDED", result.at("/records/0/reasonCode").asText());
        assertEquals("START_LOSS_DEPENDENCY_REVIEW_REQUIRED", result.at("/records/1/reasonCode").asText());
        assertEquals("OUTSIDE_UPSTREAM_INTERVAL", result.at("/records/2/reasonCode").asText());
        assertEquals("BASELINE_PATHOGENIC_REQUIRED", result.at("/records/3/reasonCode").asText());
        assertEquals(3, calls.get()); assertFalse(result.path("complete").asBoolean());
        assertEquals(result, prep.upstream(f.data().context, f.data().model, sequence(f))); assertEquals(3, calls.get());
        for (var raw : source.originals()) assertEquals(raw.at("/receipt/sha256").asText(), CodingTranscriptMaterialSupply.sha(raw.path("rawJson").asText()));
    }
    @ParameterizedTest @ValueSource(strings = {"PVS1", "PVS1_Moderate", "PM4", "PM4_Strong", "PS3"})
    void nativeMetCriteriaAreAuditedButAbsenceOfPvs1DoesNotInventIndependence(String code) throws Exception {
        var f = base.fixture(1); var row = summary(f, 4, "Pathogenic");
        var result = Pvs1StartLossAutomaticPreparation.assessment(mapper, row, original(row, code));
        assertFalse(result.path("independenceEstablished").asBoolean());
        assertEquals(code.equals("PS3") ? "CLASSIFICATION_INDEPENDENCE_REQUIRES_SCIENTIFIC_FACTS" : "START_LOSS_DEPENDENCY_REVIEW_REQUIRED", result.path("reasonCode").asText());
        assertEquals("/evidenceLine/0/evidenceItem/0", result.at("/criteria/0/sourceLocator").asText());
    }

    @Test void missingBackgroundCannotBlockIndependentExclusionsOrPermitPathogenicAdoption() throws Exception {
        var f = base.fixture(1);
        var target = summary(f, 1, "Pathogenic").put("moi", "Unknown");
        var outside = summary(f, 10, "Pathogenic").put("moi", "Unknown");
        var nonP = summary(f, 7, "Uncertain Significance").put("moi", "Unknown");
        var relevant = summary(f, 4, "Pathogenic").put("moi", "Unknown");
        var prep = new Pvs1StartLossAutomaticPreparation(mapper, () -> null,
                sources(document(target, outside, nonP, relevant), mapper.createObjectNode()));
        var result = prep.upstream(f.data().context, f.data().model, sequence(f));
        assertEquals("TARGET_EXCLUDED", result.at("/records/0/reasonCode").asText());
        assertEquals("OUTSIDE_UPSTREAM_INTERVAL", result.at("/records/1/reasonCode").asText());
        assertEquals("BASELINE_PATHOGENIC_REQUIRED", result.at("/records/2/reasonCode").asText());
        assertEquals("BACKGROUND_UNRESOLVED", result.at("/records/3/reasonCode").asText());
        assertFalse(result.at("/records/3").has("nativeAssessment"));
        assertEquals(2, calls.get(), "No interpretation request for excluded or unscoped references");
        assertFalse(result.path("negativeCoverageGranted").asBoolean());
    }

    @Test void unknownBackgroundNonPathogenicRecordStillBlocksSameAllelePathogenicWitness() throws Exception {
        var f = base.fixture(1); var positive = summary(f, 4, "Pathogenic");
        var uncertain = summary(f, 4, "Uncertain Significance").put("moi", "Unknown");
        var prep = new Pvs1StartLossAutomaticPreparation(mapper, () -> null,
                sources(document(positive, uncertain), original(positive, "PS3")));
        var result = prep.upstream(f.data().context, f.data().model, sequence(f));
        assertEquals("CONFLICT", result.at("/records/0/disposition").asText());
        assertEquals("CONFLICT", result.at("/records/1/disposition").asText());
        assertEquals("REFERENCE_CLASSIFICATION_CONFLICT", result.at("/records/0/reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"uuid", "disease", "allele", "classification", "missing"})
    void mismatchedOriginalNeverBecomesReferenceQualification(String changed) throws Exception {
        var row = summary(base.fixture(1), 4, "Pathogenic"); var raw = original(row, "PS3");
        switch (changed) {
            case "uuid" -> raw.put("uuid", "other");
            case "disease" -> raw.withObject("condition").put("id", "MONDO:9999999");
            case "allele" -> raw.withObject("variant").put("id", "CAR:CA1");
            case "classification" -> raw.withObject("statementOutcome").put("label", "Benign");
            default -> raw.removeAll();
        }
        assertEquals("ORIGINAL_INTERPRETATION_UNAVAILABLE_OR_MISMATCH", Pvs1StartLossAutomaticPreparation.assessment(mapper, row, raw).path("reasonCode").asText());
    }
    @Test void appliedStrengthComesFromTheEvidenceLineAndKeepsOriginalObservationLocations() throws Exception {
        var row = summary(base.fixture(1), 4, "Pathogenic"); var raw = original(row, "PS3");
        var line = (ObjectNode) raw.at("/evidenceLine/0");
        line.putObject("evidenceStrength").put("label", "Pathogenic Supporting");
        var item = (ObjectNode) line.at("/evidenceItem/0");
        item.putArray("evidenceLine").addObject().putArray("evidenceItem").addObject()
                .put("type", "FunctionalAssayResult").put("id", "example-observation");
        var result = Pvs1StartLossAutomaticPreparation.assessment(mapper, row, raw);
        assertEquals("Pathogenic Supporting", result.at("/criteria/0/sourceAppliedStrength").asText());
        assertEquals("Pathogenic Strong", result.at("/criteria/0/sourceDefaultStrength").asText());
        assertEquals("/evidenceLine/0/evidenceItem/0/evidenceLine/0/evidenceItem/0",
                result.at("/criteria/0/observationLocations/0/sourceLocator").asText());
        assertFalse(result.path("independenceEstablished").asBoolean());
        assertFalse(result.at("/criteria/0/observationSemanticsVerified").asBoolean());
        line.remove("evidenceStrength");
        assertFalse(Pvs1StartLossAutomaticPreparation.assessment(mapper, row, raw).at("/criteria/0").has("sourceAppliedStrength"));
        line.putObject("evidenceStrength").put("label", "Pathogenic Strong");
        line.withArray("evidenceItem").add(item.deepCopy());
        assertFalse(Pvs1StartLossAutomaticPreparation.assessment(mapper, row, raw).at("/criteria/0").has("sourceAppliedStrength"),
                "a line with multiple assessments does not assign its strength to each one");
    }
    @Test void conflictsStayVisibleAndEmptyTruncatedOrUnmappedSourcesNeverEstablishAbsence() throws Exception {
        var f = base.fixture(1); var row = summary(f, 4, "Pathogenic"); var benign = summary(f, 4, "Benign").put("uuid", "another");
        var source = sources(document(row, benign), original(row, "PS3"));
        var prep = new Pvs1StartLossAutomaticPreparation(mapper, () -> null, source);
        var result = prep.upstream(f.data().context, f.data().model, sequence(f));
        assertEquals("CONFLICT", result.at("/records/0/disposition").asText());
        assertEquals("CONFLICT", result.at("/records/1/disposition").asText());
        var unmapped = row.deepCopy(); unmapped.putArray("hgvs").add("NM_999999.1:c.4A>G");
        for (var doc : List.of(document(), document(row).put("next", "unrecognised"), document(unmapped))) {
            var p = new Pvs1StartLossAutomaticPreparation(mapper, () -> null, sources(doc, mapper.createObjectNode()));
            var empty = p.upstream(f.data().context, f.data().model, sequence(f));
            assertFalse(empty.path("complete").asBoolean()); assertFalse(empty.path("negativeCoverageGranted").asBoolean());
        }
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void transcriptStructureNeverBecomesAFunctionalAssertionEvenOutsideExons(int strand) throws Exception {
        var f = base.fixture(strand); var model = f.data().model;
        var inventory = mapper.createObjectNode().put("coverage", "INDEXED_CODING_TRANSCRIPTS_ONLY");
        inventory.putArray("transcripts").add(model.deepCopy()).add(model.deepCopy().put("transcript", "NM_999999.1"));
        var other = model.deepCopy().put("transcript", "NM_999998.1");
        for (var exon : other.path("exons")) { ((ObjectNode) exon).put("genomicStart", exon.path("genomicStart").asInt() + 100000).put("genomicEnd", exon.path("genomicEnd").asInt() + 100000); }
        inventory.withArray("transcripts").add(other);
        var review = Pvs1StartLossAutomaticPreparation.transcripts(mapper, f.data().context, model, inventory, "a".repeat(64));
        assertEquals("SAME_START_CODON", review.at("/findings/1/reasonCode").asText());
        assertTrue(review.at("/findings/2/differentStart").asBoolean());
        assertTrue(review.at("/findings/2/targetOutsideTranscribedRegion").asBoolean());
        assertTrue(review.at("/findings/2/targetUnaffected").isNull());
        assertTrue(review.at("/findings/2/functionallyRelevant").isNull()); assertFalse(review.path("complete").asBoolean());
    }
    CodingTranscriptMaterialSupply technicalSupply(Pvs1StartLossRuntimeTest.Fixture f) throws Exception {
        var model = f.data().model.deepCopy(); String cds = model.path("cdsSequence").asText();
        model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(cds)).put("cdsSha256", CodingTranscriptMaterialSupply.sha(cds));
        byte[] raw = mapper.writeValueAsBytes(model); String name = model.path("transcript").asText() + ".model.json";
        Files.write(root.resolve(name), raw);
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries").addObject().put("transcript", model.path("transcript").asText()).put("file", name)
                .put("fileBytes", raw.length).put("fileSha256", CodingTranscriptMaterialSupply.sha(raw)).put("canonicalSha256", base.adapter.canonicalSha256(model))
                .put("modelId", model.path("modelId").asText()).put("modelVersion", model.path("modelVersion").asText());
        byte[] manifestRaw = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), manifestRaw);
        return new CodingTranscriptMaterialSupply(mapper, root, CodingTranscriptMaterialSupply.sha(manifestRaw), (c, start, length) -> {
            var text = new StringBuilder();
            for (int i = 0; i < length; i++) {
                int pos = CodingTranscriptMapping.cdsPosition(model, start + i);
                text.append(CodingTranscriptMapping.orientAllele(model, cds.substring(pos - 1, pos)));
            }
            return new CodingTranscriptMaterialSupply.ReferenceSpan(text.toString(), mapper.createObjectNode().put("expectedSha256", f.data().context.at("/variant/normalization/referenceSequenceSha256").asText()));
        });
    }
    @Test void realTechnicalReaderAndAutomaticSourcesRunWithoutAReviewPackageAndKeepFailuresIndependent() throws Exception {
        var f = base.fixture(1); var technical = technicalSupply(f);
        var source = sources(document(), mapper.createObjectNode());
        var prep = new Pvs1StartLossAutomaticPreparation(mapper, () -> technical, source);
        var result = prep.prepare(f.data().context, f.data().candidate, mapper.createObjectNode());
        assertEquals("AVAILABLE", result.at("/sequence/status").asText(), result.toPrettyString());
        assertEquals("UNRESOLVED", result.at("/transcriptReview/status").asText());
        assertEquals("INCOMPLETE", result.at("/upstreamReview/status").asText());
        assertFalse(result.path("runtimeEligible").asBoolean()); assertEquals(4, calls.get());
        assertEquals(5, prep.originals().size()); // verified selected model is retained as well
        artifact("AUTO_INVENTORY_UNAVAILABLE", result);
    }
    @Test void successfulInventoryRetainsOriginalAndNoResultCanMutateSharedMaterial() throws Exception {
        var f = base.fixture(-1); var technical = spy(technicalSupply(f));
        var inventory = mapper.createObjectNode().put("coverage", "INDEXED_CODING_TRANSCRIPTS_ONLY"); inventory.putArray("transcripts").add(f.data().model.deepCopy());
        doReturn(inventory).when(technical).startLossInventory(any());
        var prep = new Pvs1StartLossAutomaticPreparation(mapper, () -> technical, sources(document(), mapper.createObjectNode()));
        var result = prep.prepare(f.data().context, f.data().candidate, mapper.createObjectNode());
        assertEquals("UNRESOLVED", result.at("/transcriptReview/status").asText(), result.toPrettyString());
        assertEquals(6, prep.originals().size()); prep.originals().removeAll();
        ((ObjectNode) result.path("transcriptReview")).removeAll();
        var repeat = prep.prepare(f.data().context, f.data().candidate, mapper.createObjectNode());
        assertEquals(1, repeat.at("/transcriptReview/findings").size()); assertEquals(4, calls.get());
        artifact("AUTO_NATIVE_SOURCES", repeat);
    }
    void artifact(String name, ObjectNode result) throws Exception {
        String output = System.getProperty("varvis.pvs1.startloss.output"); if (output == null) return;
        Files.createDirectories(Path.of(output));
        Files.writeString(Path.of(output).resolve(name + ".json"), mapper.writeValueAsString(Pvs1StartLossAutomaticPreparation.publicSummary(mapper, result)), StandardCharsets.UTF_8);
    }

    @Test void publicProjectionLimitsDisplayWithoutDiscardingPrivateEvidenceAndPrioritisesConflict() {
        var full = mapper.createObjectNode(); var rows = full.putObject("upstreamReview").putArray("records");
        for (int i = 0; i < 101; i++) rows.addObject().put("recordId", "excluded-" + i).put("disposition", "EXCLUDED").put("reasonCode", "OUTSIDE_UPSTREAM_INTERVAL");
        rows.addObject().put("recordId", "conflict").put("disposition", "CONFLICT").put("reasonCode", "REFERENCE_CLASSIFICATION_CONFLICT");
        var publicView = Pvs1StartLossAutomaticPreparation.publicSummary(mapper, full);
        assertEquals(102, full.at("/upstreamReview/records").size());
        assertEquals(100, publicView.at("/upstreamReview/records").size());
        assertEquals(102, publicView.at("/upstreamReview/totalRecords").asInt());
        assertTrue(publicView.at("/upstreamReview/displayTruncated").asBoolean());
        assertEquals("conflict", publicView.at("/upstreamReview/records/0/recordId").asText());
    }
    @Test void submissionSummaryKeepsLateConflictAndRetainsTheEntirePrivateAudit() {
        var full = mapper.createObjectNode();
        var assertions = full.putObject("upstreamReview").putArray("records").addObject()
                .putObject("nativeAssessment").putObject("clinvarAssertions").putArray("assertions");
        for (int i = 0; i < 25; i++) assertions.addObject().put("accession", "source-" + i).put("disposition", "NON_ADVERSE_NOT_INDEPENDENTLY_QUALIFIED");
        assertions.addObject().put("accession", "conflict").put("disposition", "CONFLICTING_CURRENT_ASSERTION");
        var result = Pvs1StartLossAutomaticPreparation.publicSummary(mapper, full).at("/upstreamReview/records/0/nativeAssessment/clinvarAssertions");
        assertEquals(20, result.path("assertions").size()); assertEquals(26, result.path("totalRecords").asInt());
        assertTrue(result.path("displayTruncated").asBoolean()); assertEquals("conflict", result.at("/assertions/0/accession").asText());
        assertEquals(26, assertions.size()); assertEquals("source-0", assertions.get(0).path("accession").asText());
    }
}
