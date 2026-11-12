package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Normal request-owned dispatcher over actual pinned synthetic RNA/region providers; selected boundary is a fixture. */
class FullRnaSharedBatchTest {
    @TempDir Path root;
    private static final String SOURCE = "a".repeat(64);
    private record Data(FullRnaCriterionFixture rna, ReviewedRuleBundle bundle, SelectedEvidencePreparation selected) { }
    private Data data() throws Exception {
        return data(false);
    }
    private Data data(boolean multipleOutcomes) throws Exception {
        var rna = new FullRnaCriterionFixture(root.resolve("rna"), 1); rna.scenario("deletion"); rna.source();
        if (multipleOutcomes) {
            rna.original.withObject("assay").put("outcomeMultiplicity", "MULTIPLE_OUTCOMES");
            Pvs1FullRnaSourceProducerTest.repinNeutralSourceFixture(rna.data);
        }
        var mapper = rna.f.mapper; var document = new Pvs1NmdBusinessFixture(mapper, false).document();
        var entry = document.withObject("materials").putArray("pvs1FullRnaOriginals").addObject().put("entryId", "SYNTHETIC_SHARED");
        entry.set("allele", rna.f.context.at("/variant/evidenceQueryAllele").deepCopy());
        entry.put("transcript", rna.f.model.path("transcript").asText()); entry.set("binding", rna.data.binding().deepCopy());
        Path file = rna.data.originals().resolve("bundle.json"); byte[] raw = mapper.writeValueAsBytes(document); Files.write(file, raw);
        var bundle = ReviewedRuleBundle.loadPvs1(mapper, file, CodingTranscriptMaterialSupply.sha(raw), rna.f.adapter.canonicalSha256(document),
                new Pvs1TranscriptMaterialSupply(mapper, rna.f.supply));
        return new Data(rna, bundle, selected(rna));
    }
    private Data reviewedData() throws Exception {
        var helper = new Pvs1FullRnaCriterionTest(); helper.root = root.resolve("rna");
        var fixture = helper.data(1, "deletion", 0); helper.bind(fixture); var rna = fixture.rna(); rna.source();
        var mapper = rna.f.mapper; var business = new Pvs1NmdBusinessFixture(mapper, false);
        business.models.clear(); business.policies.clear();
        business.models.add(rna.f.model); business.models.add(rna.f.model); business.policies.add(fixture.policy()); business.policies.add(fixture.policy());
        var document = business.document(); document.withArray("policies").remove(1); document.withObject("catalog").withArray("entries").remove(1);
        document.put("catalogSha256", rna.f.adapter.canonicalSha256(document.path("catalog")));
        var entry = document.withObject("materials").putArray("pvs1FullRnaOriginals").addObject().put("entryId", "SYNTHETIC_SHARED");
        entry.set("allele", rna.f.context.at("/variant/evidenceQueryAllele").deepCopy());
        entry.put("transcript", rna.f.model.path("transcript").asText()); entry.set("binding", rna.data.binding().deepCopy());
        var facts = Pvs1KnowledgeCatalogTest.facts(mapper, List.of(fixture.policy()));
        var knowledgeHelper = new Pvs1ReviewedSourceSupplyTest(); knowledgeHelper.root = root.resolve("knowledge"); Files.createDirectories(knowledgeHelper.root);
        var use = Pvs1SourceQualificationTest.qualified(mapper, facts, List.of(fixture.policy()));
        var qualification = (ObjectNode) use.at("/entries/0/qualification");
        qualification.withObject("knowledge").set("spliceScope", fixture.policy().path("spliceScope").deepCopy());
        byte[] modelBytes = mapper.writeValueAsBytes(rna.f.model); Files.write(knowledgeHelper.root.resolve("synthetic-model-original.json"), modelBytes);
        var splice = qualification.putObject("spliceVerification").put("modelRawSha256", CodingTranscriptMaterialSupply.sha(modelBytes));
        splice.set("model", rna.f.model.deepCopy()); splice.putArray("outcomes"); // Full RNA is supplied independently, never fabricated as a legacy CDS deletion.
        var knowledge = knowledgeHelper.load(facts, use);
        Path file = rna.data.originals().resolve("bundle.json"); byte[] raw = mapper.writeValueAsBytes(document); Files.write(file, raw);
        var bundle = ReviewedRuleBundle.loadPvs1Selected(mapper, file, CodingTranscriptMaterialSupply.sha(raw), rna.f.adapter.canonicalSha256(document),
                new Pvs1TranscriptMaterialSupply(mapper, rna.f.supply), knowledge);
        return new Data(rna, bundle, selected(rna));
    }
    private SelectedEvidencePreparation selected(FullRnaCriterionFixture rna) {
        var f = rna.f; var selected = mock(SelectedEvidencePreparation.class);
        when(selected.assembly()).thenReturn(new AssemblyResult("full-rna-test", 1, 1, List.of("one"),
                List.of(new CandidateAssembly("one", List.of(new Attempt("one", "hypothesis", null, null, null, f.context, List.of())))), List.of(), false));
        when(selected.sources()).thenReturn(List.of()); when(selected.provenance()).thenReturn(f.mapper.createObjectNode());
        when(selected.context("one", "hypothesis")).thenAnswer(ignored -> f.context.deepCopy());
        when(selected.formalCandidate("one")).thenReturn(f.mapper.valueToTree(f.candidate));
        var projection = mock(LegacyContextProjection.class);
        var snapshot = f.mapper.createObjectNode().put("candidateId", "one").put("hypothesisId", "hypothesis").put("fixture", "SYNTHETIC_SELECTED_BOUNDARY");
        snapshot.set("originalContext", f.context.deepCopy()); snapshot.set("evaluationContext", f.context.deepCopy());
        String binding = f.adapter.canonicalSha256(snapshot); snapshot.put("bindingSha256", binding);
        when(projection.bindingSha256()).thenReturn(binding); when(projection.snapshot()).thenAnswer(ignored -> snapshot.deepCopy());
        when(projection.context()).thenAnswer(ignored -> f.context.deepCopy()); when(projection.queryCandidate()).thenReturn(f.candidate);
        when(selected.legacyProjection("one", "hypothesis")).thenReturn(projection);
        when(selected.codingSequenceProjection("one", "hypothesis")).thenReturn(projection); return selected;
    }
    private LocalEvidenceBatchRuntime runtime(Data d, FullRnaRegionTestFixture regions, String code, AtomicInteger pvs1Calls) {
        var mapper = d.rna.f.mapper;
        var batch = new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode(), null, null, null, null,
                mock(Bp7SelectedBatch.class), regions::pm4, () -> { pvs1Calls.incrementAndGet(); throw new AssertionError("PVS1 must not run"); }, regions::bp3);
        batch.withFullRnaPreparation(() -> d.bundle); batch.bindExecutionCriteria(List.of("ACMG_AMP_2015:" + code)); return batch;
    }
    private ObjectNode execute(LocalEvidenceBatchRuntime batch, Data d) {
        return batch.execute(d.selected, new AnalysisContext("full-rna-test", GenomeAssembly.GRCh38, AnalysisMode.WES), SOURCE);
    }
    @ParameterizedTest @CsvSource({"PM4,false,true,NO_KNOWN_FUNCTION", "BP3,true,false,NO_KNOWN_FUNCTION"})
    void independentCriterionUsesActualSourceWithoutPvs1AndKeepsArchive(String code, boolean repeat, boolean nonRepeat, String function) throws Exception {
        var d = data(); var f = d.rna.f; var calls = new AtomicInteger();
        var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, repeat, nonRepeat, function);
        var batch = runtime(d, regions, code, calls); assertTrue(batch.needsFullRnaSourceIdentity()); batch.prepareFullRnaSourceIdentity();
        var full = execute(batch, d); var row = full.at("/results/0/localBatch/criteria/0");
        assertEquals("MET", row.path("status").asText(), full::toPrettyString); assertEquals(0, calls.get());
        assertFalse(full.path("modules").has("pvs1")); assertEquals(0, d.bundle.pvs1RuntimeCalls());
        var archived = FullRnaEvidenceArchive.verifyAndExport(f.mapper, full.path("fullRnaArchive"), full);
        assertEquals(1, archived.path("originalsByRawSha256").size()); assertEquals(1, archived.path("materialsBySha256").size());
        assertEquals(1, archived.path("judgments").size());
        var pub = batch.publicView(full); var publicRow = pub.at("/results/0/localBatch/criteria/0");
        assertEquals("ADOPTED", publicRow.at("/details/fullRnaUse/claim/application").asText(), pub::toPrettyString);
        assertEquals(row.path("status"), publicRow.path("status")); assertFalse(pub.has("fullRnaArchive"));
        assertFalse(pub.toString().contains(FullRnaCriterionFixture.REFERENCE)); assertFalse(pub.toString().contains("completeAlteredRnaWitness"));
        assertFalse(pub.toString().contains("relativePath"));
        Files.delete(d.rna.data.originals().resolve("original.json"));
        assertEquals(archived, FullRnaEvidenceArchive.verifyAndExport(f.mapper, full.path("fullRnaArchive"), full));
        String directory = System.getProperty("varvis.t07.main.artifacts", "");
        if (!directory.isBlank()) { var target = Path.of(directory); Files.createDirectories(target);
            f.mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve(code + "-private-batch.json").toFile(), full);
            f.mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve(code + "-public-batch.json").toFile(), pub); }
    }
    @Test void changedOriginalBeforeRequestChangesActualCacheIdentityAndNeverFallsBack() throws Exception {
        var d = data(); var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, false, true, "MISSING");
        var batch = runtime(d, regions, "PM4", new AtomicInteger()); var before = batch.prepareFullRnaSourceIdentity();
        Files.writeString(d.rna.data.originals().resolve("original.json"), "{}");
        var after = batch.prepareFullRnaSourceIdentity(); assertNotEquals(before, after);
        var full = execute(batch, d); assertEquals("NOT_EVALUATED", full.at("/results/0/localBatch/criteria/0/status").asText());
        assertEquals(0, full.at("/fullRnaArchive/originalsByRawSha256").size());
        assertEquals(1, full.at("/fullRnaArchive/materialsBySha256").size());
        assertDoesNotThrow(() -> FullRnaEvidenceArchive.verifyAndExport(d.rna.f.mapper, full.path("fullRnaArchive"), full));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void changedOriginalAfterRequestCannotPublishSourceOrAssayObservations(boolean multipleOutcomes) throws Exception {
        var d = data(multipleOutcomes);
        var original = d.rna.data.originals().resolve("original.json");
        byte[] pinned = Files.readAllBytes(original);
        Files.writeString(original, "{}");
        var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, false, true, "MISSING");
        var batch = runtime(d, regions, "PM4", new AtomicInteger());
        var observed = batch.prepareFullRnaSourceIdentity();
        assertNotEquals(CodingTranscriptMaterialSupply.sha(pinned), observed.at("/sources/0/observedSha256").asText());
        Files.write(original, pinned);

        var full = execute(batch, d);
        var row = batch.publicView(full).at("/results/0/localBatch/criteria/0");
        var preparation = row.at("/details/fullRnaUse/preparation");
        assertEquals("NOT_EVALUATED", row.path("status").asText(), row::toPrettyString);
        assertEquals("FULL_RNA_SOURCE_CHANGED_AFTER_REQUEST_IDENTITY", preparation.path("reasonCode").asText());
        assertFalse(preparation.has("sourceRecord"));
        assertFalse(preparation.has("assayConditions"));
        assertFalse(preparation.at("/binding/originalSha256").isTextual());
        assertEquals(0, full.at("/fullRnaArchive/originalsByRawSha256").size());
        assertDoesNotThrow(() -> FullRnaEvidenceArchive.verifyAndExport(d.rna.f.mapper, full.path("fullRnaArchive"), full));
    }
    @Test void noPreRequestObservedPinCannotAwardEvenWithAnOtherwiseValidSource() throws Exception {
        var d = data(); var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, false, true, "MISSING");
        var full = execute(runtime(d, regions, "PM4", new AtomicInteger()), d);
        assertEquals("NOT_EVALUATED", full.at("/results/0/localBatch/criteria/0/status").asText());
        assertEquals("FULL_RNA_PRE_REQUEST_SOURCE_IDENTITY_REQUIRED", full.at("/results/0/localBatch/criteria/0/details/fullRnaUse/preparation/reasonCode").asText());
    }
    @Test void unrelatedSelectionNeverOpensOptionalRnaConfigurationAndIdentityIsStable() throws Exception {
        var d = data(); var mapper = d.rna.f.mapper; var calls = new AtomicInteger();
        var batch = new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode(), null, null, null, null)
                .withFullRnaPreparation(() -> { calls.incrementAndGet(); throw new IllegalArgumentException("MISSING_OPTIONAL_PVS1_BUNDLE"); });
        var original = batch.identity(); batch.bindExecutionCriteria(List.of("ACMG_AMP_2015:PM2"));
        assertFalse(batch.needsFullRnaSourceIdentity()); execute(batch, d);
        assertEquals(0, calls.get()); assertEquals(original, batch.identity());
    }
    @Test void invalidConfiguredSourceIsSelectedFailureButCannotBreakUnrelatedOrFallBackToDna() throws Exception {
        var d = data(); var mapper = d.rna.f.mapper;
        var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, false, true, "MISSING");
        var batch = runtime(d, regions, "PM4", new AtomicInteger()).withFullRnaPreparation(() -> { throw new IllegalArgumentException("BAD_PIN"); });
        var before = batch.identity(); var observation = batch.prepareFullRnaSourceIdentity();
        assertTrue(observation.path("archiveRequired").asBoolean()); assertEquals("UNKNOWN", observation.path("sourceIndexConfigured").asText());
        assertEquals(before, batch.identity()); var full = execute(batch, d);
        assertEquals("NOT_EVALUATED", full.at("/results/0/localBatch/criteria/0/status").asText());
        assertEquals("FULL_RNA_CONTROLLED_CONFIGURATION_UNAVAILABLE", full.at("/results/0/localBatch/criteria/0/details/fullRnaUse/preparation/reasonCode").asText());
        assertEquals("BAD_PIN", full.at("/results/0/localBatch/criteria/0/details/fullRnaUse/preparation/configurationReasonCode").asText());
        assertDoesNotThrow(() -> FullRnaEvidenceArchive.verifyAndExport(mapper, full.path("fullRnaArchive"), full));
        writeSample("UNAVAILABLE", full, batch.publicView(full), mapper);
    }
    @Test void explicitNoIndexKeepsDnaRouteWithoutAnRnaArchive() throws Exception {
        var d = data(); var f = d.rna.f; Path file = d.rna.data.originals().resolve("bundle.json");
        var doc = (ObjectNode) f.mapper.readTree(Files.readAllBytes(file)); doc.withObject("materials").remove("pvs1FullRnaOriginals");
        byte[] raw = f.mapper.writeValueAsBytes(doc); Files.write(file, raw);
        var noIndex = ReviewedRuleBundle.loadPvs1(f.mapper, file, CodingTranscriptMaterialSupply.sha(raw), f.adapter.canonicalSha256(doc), new Pvs1TranscriptMaterialSupply(f.mapper, f.supply));
        var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, false, true, "MISSING");
        var batch = runtime(d, regions, "PM4", new AtomicInteger()).withFullRnaPreparation(() -> noIndex);
        var observation = batch.prepareFullRnaSourceIdentity(); assertFalse(observation.path("archiveRequired").asBoolean(true));
        var full = execute(batch, d); assertFalse(full.has("fullRnaArchive")); assertFalse(full.at("/results/0/localBatch/criteria/0/details").has("fullRnaUse"));
        assertFalse(FullRnaEvidenceArchive.required(full));
    }
    @Test void beforeGateArchiveRecordsNonExecutionWithoutScientificRowsOrSourceQualification() throws Exception {
        var d = data(); var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, false, true, "MISSING");
        var batch = runtime(d, regions, "PM4", new AtomicInteger()); batch.prepareFullRnaSourceIdentity();
        var full = batch.notExecutedFullRnaSnapshot("UNAVAILABLE", "EXECUTION_SOURCE_UNAVAILABLE");
        assertEquals("NOT_EXECUTED", full.path("status").asText()); assertTrue(full.path("results").isEmpty());
        assertTrue(full.path("modules").isEmpty()); assertEquals(0, full.at("/fullRnaArchive/originalsByRawSha256").size());
        assertDoesNotThrow(() -> FullRnaEvidenceArchive.verifyAndExport(d.rna.f.mapper, full.path("fullRnaArchive"), full));
        assertTrue(batch.publicView(full).path("results").isEmpty());
    }
    @Test void mechanicalSuccessWithUnknownAssayQualityCannotBecomeAnAdoptedCriterion() throws Exception {
        var d = data(); var f = d.rna.f; d.rna.original.withObject("assay").put("assayQuality", "UNKNOWN");
        Pvs1FullRnaSourceProducerTest.repinNeutralSourceFixture(d.rna.data);
        Path file = d.rna.data.originals().resolve("bundle.json"); var document = (ObjectNode) f.mapper.readTree(Files.readAllBytes(file));
        ((ObjectNode) document.at("/materials/pvs1FullRnaOriginals/0")).set("binding", d.rna.data.binding().deepCopy());
        byte[] bytes = f.mapper.writeValueAsBytes(document); Files.write(file, bytes);
        var bundle = ReviewedRuleBundle.loadPvs1(f.mapper, file, CodingTranscriptMaterialSupply.sha(bytes), f.adapter.canonicalSha256(document), new Pvs1TranscriptMaterialSupply(f.mapper, f.supply));
        var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, false, true, "MISSING");
        var batch = runtime(d, regions, "PM4", new AtomicInteger()).withFullRnaPreparation(() -> bundle); batch.prepareFullRnaSourceIdentity();
        var full = execute(batch, d); var row = full.at("/results/0/localBatch/criteria/0");
        assertEquals("READY", row.at("/details/fullRnaUse/preparation/status").asText());
        assertEquals("NOT_EVALUATED", row.path("status").asText()); assertEquals("NONE", row.path("appliedStrength").asText());
        assertEquals("NOT_ADOPTED", row.at("/details/fullRnaUse/claim/application").asText());
        assertDoesNotThrow(() -> FullRnaEvidenceArchive.verifyAndExport(f.mapper, full.path("fullRnaArchive"), full));
        writeSample("ASSAY_UNKNOWN", full, batch.publicView(full), f.mapper);
    }
    private void writeSample(String name, ObjectNode full, ObjectNode pub, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        String directory = System.getProperty("varvis.t07.main.artifacts", "");
        if (!directory.isBlank()) { var target = Path.of(directory); Files.createDirectories(target);
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve(name + "-private-batch.json").toFile(), full);
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve(name + "-public-batch.json").toFile(), pub); }
    }
    private ObjectNode methodEnvelope(String evaluatorVersion, String moduleVersion) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); var raw = mapper.createObjectNode();
        var evaluation = raw.putObject("evaluation").put("criterionId", "ACMG_AMP_2015:PVS1").put("appliedStrength", "NONE");
        evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", "SYNTHETIC_METHOD_CONTRACT_ONLY");
        evaluation.putObject("deterministicTrace").put("evaluatorId", TruncatingNmdPvs1Runtime.EVALUATOR_ID).put("evaluatorVersion", evaluatorVersion);
        raw.putObject("resolvedInput").putObject("fullRnaAssessment").put("schemaVersion", "pvs1-full-rna-assessment-v1")
                .put("methodVersion", moduleVersion).put("status", "NOT_EVALUATED").put("reasonCode", "SYNTHETIC_METHOD_CONTRACT_ONLY");
        return raw; // Serialized method metadata only: no fabricated MET, source qualification or scientific execution.
    }
    @ParameterizedTest @CsvSource({"1.9.0,1.0.0", "1.10.0,1.1.0"})
    void savedKnownFullRnaTupleKeepsOriginalEvaluationVersion(String evaluatorVersion, String moduleVersion) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); var raw = methodEnvelope(evaluatorVersion, moduleVersion);
        var hashes = new RuntimeEvidenceV2Adapter(mapper); String original = hashes.canonicalSha256(raw);
        var actual = FullRnaCurrentUse.pvs1MethodMetadata(mapper, raw, false, evaluatorVersion);
        assertEquals(evaluatorVersion, actual.path("methodVersion").asText()); assertEquals("EXECUTED", actual.path("evaluationState").asText());
        assertEquals(evaluatorVersion, actual.path("evaluationMethodVersion").asText());
        assertEquals(evaluatorVersion, Pvs1SelectedBatch.publicSummary(mapper, raw).path("methodVersion").asText());
        assertEquals(original, hashes.canonicalSha256(raw), "Read projection must not rewrite the saved evaluation");
        assertThrows(IllegalArgumentException.class, () -> FullRnaCurrentUse.pvs1MethodMetadata(mapper, raw, false, evaluatorVersion.equals("1.9.0") ? "1.10.0" : "1.9.0"));
    }
    @ParameterizedTest @CsvSource({"1.11.0,1.2.0", "1.9.0,1.1.0", "1.10.0,1.0.0"})
    void unknownOrCrossedFullRnaMethodTupleIsNotSilentlyRelabeled(String evaluatorVersion, String moduleVersion) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); var raw = methodEnvelope(evaluatorVersion, moduleVersion);
        assertThrows(IllegalArgumentException.class, () -> FullRnaCurrentUse.pvs1MethodMetadata(mapper, raw, false, evaluatorVersion));
        assertThrows(IllegalArgumentException.class, () -> Pvs1SelectedBatch.publicSummary(mapper, raw));
    }
    @Test void unexecutedSavedPvs1UsesOnlyItsSavedTargetAndLegacySummaryRemainsUnchanged() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); var raw = methodEnvelope("1.9.0", "1.0.0");
        raw.remove("resolvedInput"); ((ObjectNode) raw.path("evaluation")).remove("deterministicTrace");
        var saved = raw.putObject("publicSummary").put("schemaVersion", "pvs1-selected-facts-v1")
                .put("method", "SOURCE_BOUND_FULL_RNA_CONSEQUENCE").put("methodVersion", "1.9.0");
        var actual = FullRnaCurrentUse.pvs1MethodMetadata(mapper, raw, false, "1.9.0");
        assertEquals("1.9.0", actual.path("methodVersion").asText()); assertEquals("NOT_EXECUTED", actual.path("evaluationState").asText());
        assertTrue(actual.path("evaluationMethodVersion").isNull());
        assertThrows(IllegalArgumentException.class, () -> FullRnaCurrentUse.pvs1MethodMetadata(mapper, raw, false, "1.10.0"));
        saved.remove("methodVersion"); var old = saved.deepCopy();
        assertEquals(old, Pvs1SelectedBatch.publicSummary(mapper, raw));
        assertThrows(IllegalArgumentException.class, () -> FullRnaCurrentUse.pvs1MethodMetadata(mapper, raw, false, "1.9.0"));
    }
    @Test void archiveAndPublicProjectionRejectChangedOriginalFactsOrApplication() throws Exception {
        var d = data(); var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, false, true, "MISSING");
        var batch = runtime(d, regions, "PM4", new AtomicInteger()); batch.prepareFullRnaSourceIdentity(); var full = execute(batch, d);
        var archive = (ObjectNode) full.path("fullRnaArchive").deepCopy(); archive.withObject("originalsByRawSha256").removeAll();
        assertThrows(IllegalArgumentException.class, () -> FullRnaEvidenceArchive.verifyAndExport(d.rna.f.mapper, archive, full));
        var changed = full.deepCopy(); ((ObjectNode) changed.at("/modules/pm4/results/0/evaluation")).put("appliedStrength", "STRONG");
        var pub = batch.publicView(changed); assertEquals("FULL_RNA_CURRENT_CENTRAL_BINDING_INVALID", pub.at("/results/0/localBatch/criteria/0/reasonCode").asText());
    }
    @ParameterizedTest @CsvSource({"false,true,MISSING,NOT_COUNTED", "true,false,NO_KNOWN_FUNCTION,CONFLICT"})
    void realSelectedPvs1AndIndependentLengthRuleShareOneSourceButAdoptSeparately(boolean repeat, boolean nonRepeat, String function, String application) throws Exception {
        var d = reviewedData(); var mapper = d.rna.f.mapper;
        var regions = new FullRnaRegionTestFixture(root.resolve("regions"), d.rna, repeat, nonRepeat, function);
        var selectedPvs1 = new Pvs1SelectedBatch(mapper, d.bundle);
        var batch = new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode(), null, null, null, null,
                mock(Bp7SelectedBatch.class), regions::pm4, () -> selectedPvs1, regions::bp3);
        String length = repeat ? "BP3" : "PM4";
        batch.withFullRnaPreparation(() -> d.bundle); batch.bindExecutionCriteria(List.of("ACMG_AMP_2015:PVS1", "ACMG_AMP_2015:" + length));
        batch.prepareFullRnaSourceIdentity(); var full = execute(batch, d); var pub = batch.publicView(full);
        assertEquals("MET", full.at("/modules/pvs1/results/0/evaluation/evaluationResult/status").asText(), full::toPrettyString);
        assertEquals("STRONG", full.at("/modules/pvs1/results/0/evaluation/appliedStrength").asText());
        String module = length.toLowerCase(java.util.Locale.ROOT);
        assertEquals("MET", full.at("/modules/" + module + "/results/0/evaluation/evaluationResult/status").asText());
        assertEquals(application, full.at("/modules/" + module + "/results/0/fullRnaCurrentUse/claim/application").asText());
        for (var row : pub.at("/results/0/localBatch/criteria")) assertTrue(row.at("/details/fullRnaUse").isObject(), pub::toPrettyString);
        var publicCandidate = mapper.createObjectNode().put("candidateId", "one"); var publicAttempt = pub.at("/results/0");
        assertEquals(!repeat, LocalEvidenceBatchRuntime.completedFullRnaAssignment(mapper, full, publicCandidate, publicAttempt, publicAttempt.at("/localBatch/criteria/1")));
        var tampered = (ObjectNode) publicAttempt.deepCopy(); ((ObjectNode) tampered.at("/localBatch/criteria/0")).put("status", "NOT_EVALUATED");
        assertFalse(LocalEvidenceBatchRuntime.completedFullRnaAssignment(mapper, full, publicCandidate, tampered, tampered.at("/localBatch/criteria/1")));
        for (String key : List.of("appliedStrength", "reasonCode")) {
            var changed = (ObjectNode) publicAttempt.deepCopy(); ((ObjectNode) changed.at("/localBatch/criteria/0")).put(key, key.equals("appliedStrength") ? "VERY_STRONG" : "ALTERED_PUBLIC_REASON");
            assertFalse(LocalEvidenceBatchRuntime.completedFullRnaAssignment(mapper, full, publicCandidate, changed, changed.at("/localBatch/criteria/1")));
        }
        assertEquals("NOT_EVALUATED", full.at("/results/0/localBatch/criteria/1/status").asText());
        assertEquals("NONE", full.at("/results/0/criterionEvaluations/1/appliedStrength").asText());
        assertEquals(1, full.at("/fullRnaArchive/originalsByRawSha256").size()); assertEquals(1, full.at("/fullRnaArchive/materialsBySha256").size());
        assertEquals(2, full.at("/fullRnaArchive/judgments").size());
        assertDoesNotThrow(() -> FullRnaEvidenceArchive.verifyAndExport(mapper, full.path("fullRnaArchive"), full));
        if (repeat) assertEquals("NOT_EVALUATED", full.at("/results/0/localBatch/criteria/0/status").asText());
        else assertEquals("COMPLETED", full.path("status").asText());
        String directory = System.getProperty("varvis.t07.main.artifacts", "");
        if (!directory.isBlank()) { var target = Path.of(directory); Files.createDirectories(target);
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve(application + "-private-batch.json").toFile(), full);
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve(application + "-public-batch.json").toFile(), pub); }
    }
}
