package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.nbscn.varvis.variantanalysis.config.Pm4Bp3ComplexPublicFixture;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Normal production factories over explicit invented non-rescue data. Neither named human gene is a clinical positive. */
public final class T07RnaInitiationBusinessWriter {
    private static final String C = "ACMG_AMP_2015:";
    private final Path root, output, python;
    private final Map<String, TranscriptConsequence> annotations = new LinkedHashMap<>();
    private AcmgBusinessIntegrationTest base;
    private Pm4Bp3ComplexPublicFixture transport;
    private T07RnaInitiationBusinessMaterials materials;
    private Ba1BusinessIntegrationTest population;
    private ObjectNode manifest;
    private List<String> enabled = List.of();
    private T07RnaInitiationBusinessWriter(Path root, Path output, Path python) { this.root = root; this.output = output; this.python = python; }

    public static void main(String[] args) throws Exception {
        assertEquals(3, args.length);
        var writer = new T07RnaInitiationBusinessWriter(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        Files.createDirectories(writer.root); Files.createDirectories(writer.output);
        try { writer.prepare(); writer.run(); } finally { if (writer.base != null) writer.base.closeDatabase(); }
        writer.manifest.put("writerDatabaseClosed", true).put("writerCompleted", true);
        writer.write(writer.output.resolve("reader-manifest.json"), writer.manifest);
    }

    private void prepare() throws Exception {
        base = new AcmgBusinessIntegrationTest() {
            @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations() {
                var service = mock(VepTranscriptAnnotationEnrichmentService.class);
                when(service.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenAnswer(call -> {
                    List<VariantInterpretation> candidates = call.getArgument(0); var values = new LinkedHashMap<String, List<TranscriptConsequence>>();
                    for (var candidate : candidates) values.put(candidate.getVariantId(), List.of(annotations.get(candidate.getExomiserCandidateFeatures().getGeneSymbol())));
                    return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(values, VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
                }); return service;
            }
        };
        base.temporary = root; base.setup();
        var layout = new Pm4Bp3ComplexPublicFixture.SyntheticRnaLayout(T07RnaInitiationBusinessMaterials.REFERENCE,
                3, 23, List.of(8, 17, 25, 29), 1000, 1000, "MNAMFE");
        transport = new Pm4Bp3ComplexPublicFixture(base.mapper, root.resolve("transport"), root.resolve("reference"), layout);
        transport.applyNormalizationTo(base.properties); base.fixture.sources.add(transport.referenceLedger()); base.producer = transport.producer(base.fixture.sources);
        for (String gene : List.of("HBB", "GJB2")) {
            var model = transport.model(gene); var annotation = transport.annotation(gene);
            assertEquals("ATGAATGCCATGTTTGAATAA", model.path("cdsSequence").asText()); assertEquals(4, model.path("exons").size());
            annotation.put("hgvsCoding", model.path("transcript").asText() + ":c.6+1A>G")
                    .put("hgvsProtein", model.path("protein").asText() + ":p.?").put("consequence", "splice_donor_variant")
                    .put("exon", "1/4").putNull("proteinPosition").putNull("aminoAcids").putNull("codons");
            annotations.put(gene, base.mapper.treeToValue(annotation, TranscriptConsequence.class));
        }
        base.variantTsvOverride = tsv("1\t11\t1008\tA\tG\tHBB\t3043\tAR\t0.8", "2\t13\t1008\tA\tG\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t1008\t.\tA\tG\t.\tPASS\t.\tGT\t0/1", "chr13\t1008\t.\tA\tG\t.\tPASS\t.\tGT\t0/1"));
        materials = new T07RnaInitiationBusinessMaterials(base.mapper, root.resolve("rna"), transport, base.fixture.choices.path("fixedChoices"));
        materials.configureCompanions(python);
        population = new Ba1BusinessIntegrationTest(); population.base = base; population.temporary = root; population.am = new BusinessAlphaMissenseFixture();
        population.configureCatalog(new Ba1SpecificationCatalog(base.mapper, population.syntheticCatalog("SYNTHETIC_T07_INITIATION_OTHER_CRITERIA_NOT_ORACLES")));
        install();
        manifest = base.mapper.createObjectNode().put("schemaVersion", "t07-normal-full-rna-writer-reader-v1")
                .put("analysisId", base.analysisId).put("writerPid", ProcessHandle.current().pid()).put("dataClass", "SYNTHETIC")
                .put("humanClinicalValidation", false).put("newVepRun", false).put("coreFactoryOverridden", false)
                .put("functionalRescueSourceCreated", false).put("observedTranslationClaimed", false)
                .put("independentOracle", "Reference RNA3..23 MNAMFE*; native ATG removed; source putative RNA9 MFE* is not observed translation. Bounded upstream negative=Supporting; independent upstream c4A>G Pathogenic reference=Moderate, subject to parent cap.");
        manifest.putArray("runs"); write(output.resolve("transport-scope.json"), transport.scope());
    }

    private void install() {
        population.wire();
        var formal = AcmgBusinessService.formalPopulation(base.service, () -> Bs1Runtime.localExomes(base.mapper, new VariantAnalysisProperties(), false));
        var local = AcmgBusinessService.localBatchWithEffectIdentity(formal, (effects, pin) -> transport.centralRuntime(effects, pin), root.resolve("effect-reviews"));
        base.service = AcmgBusinessService.unifiedLocal(formal, local);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }

    private ObjectNode request(List<String> selection) throws Exception {
        var options = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", "2").param("workflow", "CURRENT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        enabled = new ArrayList<>(); options.path("enabledCriteria").forEach(value -> enabled.add(value.asText())); assertEquals(18, enabled.size(), options::toPrettyString);
        var request = base.request.deepCopy().put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
        request.set("selectedCriteria", base.mapper.valueToTree(selection)); var hypotheses = request.putArray("hypotheses"); var refs = request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            var rec = options.at("/candidates/" + i + "/recommendation"); var choice = base.fixture.choices.at("/fixedChoices/" + i);
            JsonNode relation = null, annotation = null;
            for (var value : rec.path("relationships")) if (value.path("selectable").asBoolean() && choice.path("syntheticHypothesisMondoId").equals(value.path("mondoId"))) relation = value;
            for (var value : rec.path("annotations")) if (value.path("contextSelectable").asBoolean() && choice.path("transcript").equals(value.path("versionedTranscriptId"))) annotation = value;
            if (relation == null || annotation == null) throw new AssertionError(rec.toPrettyString());
            var item = hypotheses.addObject().put("affectedStatus", "UNKNOWN"); var h = item.putObject("hypothesis");
            h.set("candidateId", rec.path("candidateId")); h.set("hypothesisId", rec.path("hypothesisId"));
            for (String field : List.of("hgncId", "mondoId", "moi")) h.set(field, relation.path(field));
            h.set("provenance", base.declaredSource("private/t07/synthetic-non-rescue-hypothesis"));
            String tx = choice.path("transcript").asText(); int dot = tx.lastIndexOf('.');
            var transcript = item.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                    .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                    .put("selectionReason", "Explicit invented non-rescue normal-business background");
            transcript.set("annotationSource", annotation.path("annotationSource")); transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            transcript.set("source", base.declaredSource("recommendations/" + rec.path("recommendationId").asText() + "/selection"));
            var ref = refs.addObject(); for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, rec.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", annotation.path("annotationId"));
        }
        write(output.resolve("input-options.json"), options); return request;
    }

    private ObjectNode submit(List<String> codes, String name) throws Exception {
        Path folder = Files.createDirectories(output.resolve(name)); var req = request(codes); write(folder.resolve("request.json"), req);
        var ranking = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
        var saved = base.postRequest(req);
        assertNotEquals("FAILED", saved.path("status").asText(), saved::toPrettyString);
        assertEquals(base.mapper.valueToTree(enabled.stream().filter(codes::contains).toList()), saved.path("enabledCriteria"));
        assertEquals(ranking, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
        String runId = saved.path("runId").asText(); var run = base.store.run(base.analysisId, runId).orElseThrow();
        Files.writeString(folder.resolve("expected-public.json"), run.responseJson(), StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("expected-private.json"), run.privateJson(), StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("expected-request.json"), run.requestJson(), StandardCharsets.UTF_8);
        var full = base.http.perform(get("/variant-analyses/{a}/acmg/runs/{r}", base.analysisId, runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Files.write(folder.resolve("expected-controller-full.json"), full); assertEquals(saved, base.mapper.readTree(full));
        for (var candidate : saved.path("candidates")) for (var attempt : candidate.path("attempts")) for (var criterion : attempt.at("/localBatch/criteria"))
            if ((C + "PVS1").equals(criterion.path("criterionId").asText())) {
                assertNotEquals("FULL_RNA_CURRENT_CENTRAL_BINDING_INVALID", criterion.path("reasonCode").asText(), criterion::toPrettyString);
                if ("NOT_EVALUATED".equals(criterion.path("status").asText())) assertEquals("NONE", criterion.path("appliedStrength").asText());
            }
        manifest.withArray("runs").addObject().put("name", name).put("runId", runId).put("expectedHttpStatus", 200);
        var batch = (ObjectNode) base.mapper.readTree(run.privateJson()).path("localBatch"); write(folder.resolve("private-batch.json"), batch);
        var calls = transport.configurationCalls(); assertEquals(0, calls.path("actualStubCount").asInt());
        assertEquals(saved, base.postRequest(req), "Identical actual request and source must reuse durable result");
        for (String method : List.of("pm4", "proteinLengthMaterials", "bp3", "pvs1")) assertEquals(calls.path(method), transport.configurationCalls().path(method));
        write(folder.resolve("actual-central-calls.json"), transport.configurationCalls()); return batch;
    }

    private void run() throws Exception {
        var neutral = submit(List.of(C + "PVS1"), "NEUTRAL_PREPARATION"); verifyNe(neutral, "PVS1_FULL_RNA_EFFECT_REVIEW_REQUIRED");
        materials.baseline(neutral, T07RnaInitiationBusinessMaterials.Baseline.SUPPORTING); install();
        var supporting = submit(List.of(C + "PVS1"), "PVS1_SINGLE_SUPPORTING"); verifyMet(supporting, "SUPPORTING", "SUPPORTING");
        materials.baseline(neutral, T07RnaInitiationBusinessMaterials.Baseline.MODERATE); install();
        var moderate = submit(List.of(C + "PVS1"), "PVS1_SINGLE_MODERATE"); verifyMet(moderate, "MODERATE", "MODERATE");
        var related = submit(List.of(C + "PVS1", C + "PM4", C + "BP3"), "RELATED"); verifyMet(related, "MODERATE", "MODERATE");
        var all = submit(List.copyOf(enabled), "CURRENT18"); verifyMet(all, "MODERATE", "MODERATE");
        for (var batch : List.of(related, all)) for (String module : List.of("pm4", "bp3")) for (var raw : batch.at("/modules/" + module + "/results"))
            assertNotEquals("ADOPTED", raw.at("/fullRnaCurrentUse/claim/application").asText(), "Unresolved initiation protein length cannot be counted as PM4/BP3");
        materials.baseline(neutral, T07RnaInitiationBusinessMaterials.Baseline.PARENT_CAP); install();
        verifyMet(submit(List.of(C + "PVS1"), "PARENT_CAP"), "SUPPORTING", "MODERATE");
        materials.baseline(neutral, T07RnaInitiationBusinessMaterials.Baseline.POSITION_SCOPE); install();
        var positionScope = submit(List.of(C + "PVS1"), "POSITION_SCOPE");
        assertEquals(2, positionScope.at("/modules/pvs1/results").size());
        for (var raw : positionScope.at("/modules/pvs1/results")) {
            boolean covered = "HBB".equals(raw.at("/context/gene/symbol").asText());
            var position = raw.at("/fullRnaDecisionDetails/rule/positionChecks/0");
            assertEquals("REFERENCE_EVENT_SPAN", position.path("coordinate").asText());
            assertEquals(1, position.path("minimumInclusive").asInt());
            assertEquals(covered ? 4 : 2, position.path("maximumExclusive").asInt());
            assertEquals(1, position.path("firstCheckedPosition").asInt());
            assertEquals(3, position.path("lastCheckedPosition").asInt());
            assertEquals(covered ? "PASS" : "OUTSIDE_SUPPORTED_RANGE", position.path("status").asText());
            assertEquals(covered ? "MET" : "NOT_EVALUATED", raw.at("/evaluation/evaluationResult/status").asText());
            assertEquals(covered ? "SUPPORTING" : "NONE", raw.at("/fullRnaCurrentUse/claim/originalStrength").asText());
        }
        materials.baseline(neutral, T07RnaInitiationBusinessMaterials.Baseline.UPSTREAM_INCOMPLETE); install();
        verifyNe(submit(List.of(C + "PVS1"), "UPSTREAM_INCOMPLETE"), "PVS1_RNA_INITIATION_UPSTREAM_REVIEW_INCOMPLETE");
        materials.baseline(neutral, T07RnaInitiationBusinessMaterials.Baseline.WRONG_CONTEXT); install();
        verifyNe(submit(List.of(C + "PVS1"), "WRONG_CONTEXT"), "PVS1_RNA_INITIATION_SOURCE_PRODUCT_SCOPE_MISMATCH");
        materials.baseline(neutral, T07RnaInitiationBusinessMaterials.Baseline.SOURCE_OUTSIDE_REVIEW); install();
        var outsideReview = submit(List.of(C + "PVS1"), "SOURCE_OUTSIDE_REVIEW");
        verifyNe(outsideReview, "PVS1_RNA_INITIATION_SOURCE_DOCUMENT_NOT_IN_REVIEW_SCOPE");
        for (var raw : outsideReview.at("/modules/pvs1/results")) {
            assertEquals("init-primary", raw.at("/resolvedInput/fullRnaInitiationAssessment/sourceDocumentSet/outsideReviewSourceIds/0").asText());
        }
        materials.baseline(neutral, T07RnaInitiationBusinessMaterials.Baseline.SUPPORTING); install();
        Files.writeString(materials.originalFile(0), "{}", StandardCharsets.UTF_8);
        var mismatch = submit(List.of(C + "PVS1"), "SOURCE_BYTES_MISMATCH");
        assertEquals(2, mismatch.at("/modules/pvs1/results").size());
        var first = mismatch.at("/modules/pvs1/results/0");
        assertEquals("NOT_EVALUATED", first.at("/evaluation/evaluationResult/status").asText(), first::toPrettyString);
        assertTrue(first.at("/evaluation/appliedStrength").isMissingNode() || "NONE".equals(first.at("/evaluation/appliedStrength").asText()));
        assertEquals("FULL_RNA_SOURCE_ORIGINAL_SHA_MISMATCH", first.at("/fullRnaPreparation/reasonCode").asText(), first::toPrettyString);

        materials.declareMultipleOutcomes(true); install();
        var multiple = submit(List.of(C + "PVS1"), "MULTIPLE_OUTCOMES");
        assertEquals(2, multiple.at("/modules/pvs1/results").size());
        for (var raw : multiple.at("/modules/pvs1/results")) {
            assertEquals("NOT_EVALUATED", raw.at("/evaluation/evaluationResult/status").asText(), raw::toPrettyString);
            assertEquals("NONE", raw.at("/fullRnaCurrentUse/claim/originalStrength").asText());
            var preparation = raw.path("fullRnaPreparation");
            assertEquals("FULL_RNA_SOURCE_MULTIPLE_OUTCOMES_UNRESOLVED", preparation.path("reasonCode").asText());
            assertEquals("MULTIPLE_OUTCOMES", preparation.at("/assayConditions/outcomeMultiplicity").asText());
            assertFalse(preparation.has("sequenceEffect"));
        }
        assertTrue(multiple.at("/fullRnaArchive/originalsByRawSha256").isEmpty());
        var savedPreparation = multiple.at("/fullRnaArchive/materialsBySha256");
        assertEquals(2, savedPreparation.size());
        for (var archived : savedPreparation) {
            assertEquals("UNAVAILABLE", archived.at("/preparation/status").asText());
            assertFalse(archived.has("originalReference"));
            assertFalse(archived.has("consequenceFacts"));
        }
        materials.declareMultipleOutcomes(false); install();
        var unbound = submit(List.of(C + "PVS1"), "MULTIPLE_OUTCOMES_UNBOUND_ALLELE");
        assertEquals(2, unbound.at("/modules/pvs1/results").size());
        for (var raw : unbound.at("/modules/pvs1/results")) {
            assertEquals("NOT_EVALUATED", raw.at("/evaluation/evaluationResult/status").asText(), raw::toPrettyString);
            assertEquals("NONE", raw.at("/fullRnaCurrentUse/claim/originalStrength").asText());
            var preparation = raw.path("fullRnaPreparation");
            assertEquals("FULL_RNA_SOURCE_PHYSICAL_ALLELE_SCOPE_REQUIRED", preparation.path("reasonCode").asText());
            assertFalse(preparation.has("sourceRecord"));
            assertFalse(preparation.has("assayConditions"));
        }
        assertTrue(unbound.at("/fullRnaArchive/originalsByRawSha256").isEmpty());
        var source = base.store.source(base.analysisId).orElseThrow();
        Files.writeString(output.resolve("expected-source.json"), source.json(), StandardCharsets.UTF_8); manifest.put("sourceSha256", source.sha256());
        write(output.resolve("final-central-calls.json"), transport.configurationCalls());
    }

    private static void verifyMet(JsonNode batch, String strength, String branchStrength) {
        assertEquals(2, batch.at("/modules/pvs1/results").size(), batch::toPrettyString);
        for (var raw : batch.at("/modules/pvs1/results")) {
            assertEquals("MET", raw.at("/evaluation/evaluationResult/status").asText(), raw::toPrettyString);
            assertEquals(strength, raw.at("/evaluation/appliedStrength").asText());
            assertEquals("1.10.1", raw.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
            assertEquals("MET", raw.at("/resolvedInput/fullRnaAssessment/status").asText());
            assertEquals(strength, raw.at("/resolvedInput/fullRnaAssessment/adoptedStrength").asText());
            assertEquals(branchStrength, raw.at("/resolvedInput/fullRnaInitiationAssessment/strength").asText());
            assertEquals(9, raw.at("/resolvedInput/fullRnaInitiationAssessment/sourceSpecifiedStart/alteredStartRna1").asInt());
            assertEquals("MFE*", raw.at("/resolvedInput/fullRnaInitiationAssessment/sourceSpecifiedStart/proteinThroughFirstStop").asText());
            assertTrue(raw.at("/resolvedInput/fullRnaInitiationAssessment/putativeRestartDoesNotProveTranslation").asBoolean());
            assertFalse(raw.path("resolvedInput").has("ptcStart")); assertFalse(raw.path("resolvedInput").has("nmdPrediction"));
            assertEquals("ADOPTED", raw.at("/fullRnaCurrentUse/claim/application").asText());
        }
        assertEquals(2, batch.at("/fullRnaArchive/originalsByRawSha256").size());
    }
    private static void verifyNe(JsonNode batch, String reason) {
        assertEquals(2, batch.at("/modules/pvs1/results").size(), batch::toPrettyString);
        for (var raw : batch.at("/modules/pvs1/results")) {
            assertEquals("NOT_EVALUATED", raw.at("/evaluation/evaluationResult/status").asText(), raw::toPrettyString);
            assertTrue(raw.at("/evaluation/appliedStrength").isMissingNode() || "NONE".equals(raw.at("/evaluation/appliedStrength").asText()), "Atomic NE cannot grant a strength");
            assertEquals(reason, raw.at("/evaluation/evaluationResult/reasonCode").asText());
            assertNotEquals("ADOPTED", raw.at("/fullRnaCurrentUse/claim/application").asText());
        }
    }
    private void write(Path file, JsonNode value) throws Exception { Files.write(file, base.mapper.writeValueAsBytes(value)); }
}
