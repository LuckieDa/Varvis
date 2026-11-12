package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP/service/source/H2 wiring; population transport and disease knowledge are synthetic. */
class UnifiedLocalEvidenceBusinessTest {
    @TempDir Path temporary;
    private Bs1KnowledgeBusinessTest fixture;
    private AcmgBusinessIntegrationTest base;
    private AcmgBusinessWorkflows workflows;
    private AcmgBusinessService population;
    private List<String> criteria = List.of("BA1", "BS1", "PM2", "PP3", "BP4", "PS1", "PM5", "BP7")
            .stream().map(c -> "ACMG_AMP_2015:" + c).toList();
    private boolean eleven, fourteen, pm3, pp1;
    private int pm3Generation;
    private int pp1Generation, pp1Evaluations;
    private final List<String> effectPins = new ArrayList<>();
    private final Map<String,Integer> opened = new LinkedHashMap<>();
    private <T> T unavailable(String code) { opened.merge(code,1,(existingCount, addedCount) -> existingCount + addedCount);throw new IllegalArgumentException(code+"_TEST_RESOURCE_UNAVAILABLE"); }
    private void enableEleven() throws Exception {
        eleven=true;criteria=List.of("BA1","BS1","PM2","PP3","BP4","PS1","PM5","BP7","PVS1","PM4","BP3")
                .stream().map(c->"ACMG_AMP_2015:"+c).toList(); install();
    }

    private void enableFourteen() throws Exception {
        eleven = true; fourteen = true;
        criteria = List.of("BA1","BS1","PM2","PP3","BP4","PS1","PM5","BP7","PVS1","PM4","BP3","PM1","PP2","BP1")
                .stream().map(c -> "ACMG_AMP_2015:" + c).toList(); install();
    }
    @BeforeEach void setup() throws Exception {
        fixture = new Bs1KnowledgeBusinessTest(); fixture.temporary = temporary; fixture.setup();
        base = fixture.local.fixture.base;
        population = base.service;
        install();
    }
    @AfterEach void close() { if (fixture != null) fixture.close(); }
    @Test void selectedPm3HasNoHistoryDependencyAndDoesNotEvaluateOtherModules() throws Exception {
        pm3=true; pp1=true; enableFourteen(); selectCurrentAnnotations();
        base.request.putArray("selectedCriteria").add("ACMG_AMP_2015:PM3");
        base.request.put("operationId", UUID.randomUUID().toString());
        opened.clear(); int priorPp1=pp1Evaluations;
        var saved=submit();
        assertEquals(base.mapper.readTree("[\"ACMG_AMP_2015:PM3\"]"),saved.path("enabledCriteria"));
        assertFalse(saved.has("dependencyPreparation"), "PM3 alone must not read unrelated functional history");
        assertTrue(opened.isEmpty(),opened.toString()); assertEquals(priorPp1,pp1Evaluations);
        for(var row:saved.path("candidates")) {
            assertFalse(row.has("ba1"));
            for(var attempt:row.path("attempts")) {
                assertEquals(1,attempt.at("/localBatch/criteria").size(),saved.toPrettyString());
                assertEquals("ACMG_AMP_2015:PM3",attempt.at("/localBatch/criteria/0/criterionId").asText());
                assertFalse(attempt.has("bs1"));
            }
        }
        assertEquals(saved,submit(),"Same operation is idempotent");
        assertEquals(saved,base.service.read(base.analysisId,saved.path("runId").asText()));
        base.request.put("operationId",UUID.randomUUID().toString());
        assertNotEquals(saved.path("runId"),submit().path("runId"),"New explicit operation has independent history");
        assertEquals(saved,base.service.read(base.analysisId,saved.path("runId").asText()),"Prior result is immutable");
    }
    @Test void selectedBs1ObtainsPopulationFactsWithoutBa1Evaluation() throws Exception {
        enableFourteen(); selectCurrentAnnotations(); opened.clear();
        base.request.putArray("selectedCriteria").add("ACMG_AMP_2015:BS1");
        var saved=submit();
        assertTrue(opened.isEmpty(),opened.toString());
        for(var row:saved.path("candidates")) {
            assertFalse(row.has("ba1"));
            assertEquals(0,row.at("/populationObservation/evaluatorCalls").asInt());
            assertFalse(row.path("populationObservation").has("evaluation"));
            for(var attempt:row.path("attempts")) assertEquals(0,attempt.at("/localBatch/criteria").size());
        }
        assertNotEquals("FAILED",saved.path("status").asText());
    }
    @Test void everySingleCriterionAndMixedSelectionPreservesSavedScope() throws Exception {
        pm3=true; pp1=true; enableFourteen(); selectCurrentAnnotations();
        var available=new ArrayList<>(criteria);available.add("ACMG_AMP_2015:PM3");available.add("ACMG_AMP_2015:PP1");
        var selections=new ArrayList<List<String>>();
        for(String code:available)selections.add(List.of(code));
        selections.add(List.of("ACMG_AMP_2015:PM3","ACMG_AMP_2015:PM2")); selections.add(available);
        for(var selection:selections) {
            base.request.set("selectedCriteria",base.mapper.valueToTree(selection));
            base.request.put("operationId",UUID.randomUUID().toString());
            var saved=submit(); assertNotEquals("FAILED",saved.path("status").asText());
            var expected=available.stream().filter(selection::contains).toList();
            assertEquals(base.mapper.valueToTree(expected),saved.path("enabledCriteria"));
            if (new AcmgCriterionSelection(expected).needsFunctionalReferences())
                assertEquals("READY", saved.at("/dependencyPreparation/functionalReferences/status").asText());
            else assertFalse(saved.has("dependencyPreparation"));
            for(var row:saved.path("candidates")) for(var attempt:row.path("attempts"))
                assertEquals(expected.stream().filter(c->!Set.of("ACMG_AMP_2015:BA1","ACMG_AMP_2015:BS1").contains(c)).toList(),
                    base.mapper.convertValue(attempt.at("/localBatch/criteria").findValuesAsText("criterionId"),List.class));
            assertEquals(saved,base.service.read(base.analysisId,saved.path("runId").asText()));
            var index=base.service.readView(base.analysisId,saved.path("runId").asText(),null,0,null);
            assertEquals(saved.path("enabledCriteria"),index.path("enabledCriteria"));
            for(var candidate:saved.path("candidates")) for(int i=0;i<candidate.path("attempts").size();i++) {
                var view=base.service.readView(base.analysisId,saved.path("runId").asText(),candidate.path("candidateId").asText(),i,index.path("sourceViewSha256").asText());
                assertEquals(candidate.path("attempts").get(i),view.at("/candidates/0/attempts/"+i));
                assertEquals(saved.path("enabledCriteria"),view.path("enabledCriteria"));
            }
            String output=System.getProperty("varvis.selection.fixtureOutput");
            if(output!=null) {
                var folder=Path.of(output);Files.createDirectories(folder);
                Files.writeString(folder.resolve("selected-"+String.join("-",expected.stream().map(c->c.split(":")[1]).toList())+".json"),saved.toString());
            }
        }
    }
    @Test void emptyDuplicateAndUnknownCriterionSelectionsAreRejected() throws Exception {
        for(var ids:List.of(List.<String>of(),List.of("ACMG_AMP_2015:PM3"),List.of("ACMG_AMP_2015:PM2","ACMG_AMP_2015:PM2"))) {
            base.request.set("selectedCriteria",base.mapper.valueToTree(ids));
            base.http.perform(post("/variant-analyses/"+base.analysisId+"/acmg/runs")
                .contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isBadRequest());
        }
    }
    @Test void configurationEndpointReportsCurrentWiringWithoutOpeningScientificProviders() throws Exception {
        enableFourteen();
        var before = new LinkedHashMap<>(opened);
        var effectsBefore = new ArrayList<>(effectPins);
        var mvc = MockMvcBuilders.standaloneSetup(
                new org.nbscn.varvis.variantanalysis.controller.AcmgConfigurationController(workflows)).build();
        var response = mvc.perform(get("/variant-analyses/acmg/configuration")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var result = base.mapper.readTree(response);
        assertEquals(14, result.path("criterionCount").asInt());
        assertEquals(base.mapper.valueToTree(criteria), result.path("enabledCriteria"));
        assertEquals("acmg-unified-local-fourteen-v1", result.path("profileId").asText());
        assertEquals("CHECKED_PER_ANALYSIS", result.path("resourceReadiness").asText());
        assertFalse(result.has("moduleBindings"));
        assertFalse(result.has("analysisId"));
        assertEquals(before, opened);
        assertEquals(effectsBefore, effectPins);
    }
    private void install() throws Exception {
        Files.createDirectories(temporary.resolve("reviews"));
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population, (effects, effectPin) -> {
            if (effectPin != null) effectPins.add(effectPin);
            return new LocalEvidenceBatchRuntime(base.mapper,
                base.mapper.createObjectNode().put("syntheticTest", "unified-local-v1"), null, null, null, null,
                new Bp7SelectedBatch(base.mapper, new SplicingExperimentChecks(base.mapper,
                        new ClassPathResource("acmg/public-splicing-experiments-v1.json"), temporary.resolve("experiments"),
                        (analysis, run) -> { throw new AssertionError("Catalog capture must not execute business"); }).captureCatalog()),
                eleven ? ()->unavailable("PM4") : null, eleven ? ()->unavailable("PVS1") : null, eleven ? ()->unavailable("BP3") : null,
                fourteen ? ()->unavailable("PM1") : null, fourteen ? ()->unavailable("PP2") : null, fourteen ? ()->unavailable("BP1") : null, false,null,
                pm3?new Pm3SelectedBatch(base.mapper,new Pm3SelectedBatch.Source(){
                    public ObjectNode identity(){return base.mapper.createObjectNode().put("version","pm3-business-source-v1").put("workflowConfigSha256","a".repeat(64));}
                    public ObjectNode capture(String analysis,ObjectNode recovered){
                        var snapshot=base.mapper.createObjectNode().put("schemaVersion","pm3-business-source-snapshot-v1")
                                .put("analysisId",analysis).put("generation",pm3Generation);snapshot.putArray("documents");
                        snapshot.put("snapshotSha256",new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(snapshot));return snapshot;
                    }
                }):null,pp1?new Pp1SelectedBatch(base.mapper,new Pp1SelectedBatch.Source(){
                    public ObjectNode identity(){return base.mapper.createObjectNode().put("scope","SYNTHETIC_PP1_SOURCE").put("version","pp1-paper-job-v2").put("workflowConfigSha256","a".repeat(64));}
                    public ObjectNode capture(String analysis,ObjectNode recovered){
                        var snapshot=base.mapper.createObjectNode().put("schemaVersion","pp1-business-source-snapshot-v1")
                                .put("analysisId",analysis).put("generation",pp1Generation);snapshot.putArray("documents");
                        snapshot.put("snapshotSha256",new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(snapshot));return snapshot;
                    }
                    public ObjectNode evaluate(ObjectNode captured,ObjectNode binding){
                        pp1Evaluations++;
                        assertEquals("UNKNOWN",binding.at("/ledger/status").asText());
                        assertTrue(binding.at("/ledger/pp4Points").isNull());
                        var out=base.mapper.createObjectNode().put("schemaVersion","pp1-business-evaluation-v1")
                                .put("analysisBindingSha256",new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(binding))
                                .put("modelCalls",0).put("networkRequests",0).put("evaluatorCalls",0);
                        var evaluation=out.putObject("evaluation").put("schemaVersion","pp1-segregation-evaluation-v1").put("businessPublishable",false)
                                .put("status","NOT_EVALUATED").put("awardedPP1Points",0);
                        evaluation.putNull("strength"); evaluation.putArray("events");
                        evaluation.set("target",binding.path("selection"));evaluation.set("ledger",binding.path("ledger"));
                        evaluation.putArray("needs").addObject().put("reasonCode","PP1_NO_REPLAYABLE_SELECTED_SOURCE")
                                .put("requirement","SYNTHETIC empty source snapshot; not absence from literature");
                        out.putArray("sourceCoverage");out.putArray("qualifications");return out;
                    }
                }):null);
        }, temporary.resolve("reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        workflows = new AcmgBusinessWorkflows(base.service, null);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows)).build();
        base.request.put("declarationVersion", fourteen?AcmgBusinessRuleScope.UNIFIED_FOURTEEN_DECLARATION:eleven?AcmgBusinessRuleScope.UNIFIED_ELEVEN_DECLARATION:AcmgBusinessRuleScope.UNIFIED_LOCAL_DECLARATION);
        var selections = base.request.putArray("recommendations");
        for (var row : options().path("candidates")) {
            var selected = selections.addObject(); var rec = row.path("recommendation");
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) selected.set(field, rec.path(field));
            var key = rec.at("/bs1ContextPreparation/eligibleRelationshipKeys/0");
            if (key.isTextual()) selected.set("stableRelationshipKey", key); else selected.putNull("stableRelationshipKey");
            selected.putNull("annotationId");
        }
    }
    private ObjectNode options() { return workflows.inputOptions(base.analysisId, 2, "CURRENT"); }
    private JsonNode submit() throws Exception { return fixture.post(); }
    @Test void unlistedExplicitDiseaseReachesPp1QualificationWithoutOpeningOtherCriteria() throws Exception {
        pp1=true; install(); selectCurrentAnnotations();
        for (var choice : base.request.path("recommendations"))
            ((ObjectNode)choice).putNull("stableRelationshipKey").putNull("annotationId");
        for (var input : base.request.path("hypotheses")) {
            ((ObjectNode)input).putNull("transcript");
            ((ObjectNode) input.path("hypothesis")).put("mondoId","MONDO:9999998");
        }
        var first = base.request.path("hypotheses").get(0).path("hypothesis");
        var request = base.mapper.writeValueAsBytes(base.request);
        var prepared = base.service.preparePp1Literature(base.analysisId, request,
                first.path("candidateId").asText(),first.path("hypothesisId").asText());
        assertEquals("MONDO:9999998",prepared.binding().at("/selection/diseaseId").asText());
        var saved=submit();
        assertTrue(pp1Evaluations>0,"The PP1 worker must explain its actual knowledge/source gap");
        assertTrue(saved.toString().contains("PP1_NO_REPLAYABLE_SELECTED_SOURCE"));
        assertTrue(saved.toString().contains("CLINGEN_COVERAGE_ABSENT"));
        for (var row : saved.at("/localBatch/results")) for (var criterion : row.at("/localBatch/criteria"))
            assertEquals("NOT_EVALUATED",criterion.path("status").asText(),criterion.toString());
        int calls=pp1Evaluations; assertEquals(saved,submit()); assertEquals(calls,pp1Evaluations);
    }
    @Test void pp1NormalSourceSaveReopenAndRepeatDoNotExecuteAgain() throws Exception {
        pp1=true;install();selectCurrentAnnotations();
        var before=base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,2));
        var saved=submit();int evaluations=pp1Evaluations;
        assertTrue(evaluations>0,"PP1 must reach the selected source adapter, not only a missing-context branch");
        assertEquals("acmg-unified-local-pp1-v1",saved.at("/executionProfile/profileId").asText());
        assertTrue(saved.toString().contains("PP1_NO_REPLAYABLE_SELECTED_SOURCE"));
        assertTrue(saved.toString().contains("pp1-selected-facts-v1"));
        assertEquals(before,base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,2)));
        String counts=Files.readString(temporary.resolve("queries.txt"));
        assertEquals(saved,submit());assertEquals(evaluations,pp1Evaluations);
        var declaration=base.request.deepCopy();
        base.closeDatabase();base.recreateRepositories();fixture.local.fixture.wire();fixture.wire();population=base.service;install();base.request=declaration;
        var response=base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(saved,base.mapper.readTree(response.getContentAsByteArray()));
        assertEquals(saved,submit());assertEquals(evaluations,pp1Evaluations);assertEquals(counts,Files.readString(temporary.resolve("queries.txt")));
        pp1Generation++;
        assertNotEquals(saved.path("runId"),submit().path("runId"));assertTrue(pp1Evaluations>evaluations);
        assertEquals(saved,base.service.read(base.analysisId,saved.path("runId").asText()));
    }
    @Test void pm3SnapshotBindsNormalSaveReopenRepeatAndChangedSourceCreatesNewRun() throws Exception {
        pm3=true;install();
        var saved=submit();assertEquals("acmg-unified-local-pm3-v1",saved.at("/executionProfile/profileId").asText());
        assertTrue(saved.path("enabledCriteria").toString().contains("ACMG_AMP_2015:PM3"));
        assertTrue(saved.toString().contains("pm3-selected-facts-v1"),saved.toPrettyString());
        String counts=Files.readString(temporary.resolve("queries.txt"));
        assertEquals(saved,submit());assertEquals(counts,Files.readString(temporary.resolve("queries.txt")));
        var declaration=base.request.deepCopy();
        base.closeDatabase();base.recreateRepositories();fixture.local.fixture.wire();fixture.wire();population=base.service;install();base.request=declaration;
        var response=base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(saved,base.mapper.readTree(response.getContentAsByteArray()));assertEquals(saved,submit());
        assertEquals(counts,Files.readString(temporary.resolve("queries.txt")));
        String output=System.getProperty("varvis.pm3.business.artifacts","");
        if(!output.isBlank()) {
            Path folder=Path.of(output);Files.createDirectories(folder);
            Files.write(folder.resolve("options.json"),base.mapper.writeValueAsBytes(options()),StandardOpenOption.CREATE_NEW);
            Files.write(folder.resolve("saved.json"),base.mapper.writeValueAsBytes(saved),StandardOpenOption.CREATE_NEW);
        }
        pm3Generation++;
        var changed=submit();assertNotEquals(saved.path("runId"),changed.path("runId"));
        assertEquals(saved,base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
    }
    private void assertEight(JsonNode result) {
        assertEquals(base.mapper.valueToTree(criteria), result.path("enabledCriteria"));
        assertEquals(fourteen?"acmg-unified-local-fourteen-v1":eleven?"acmg-unified-local-eleven-v1":"acmg-unified-local-eight-v1", result.at("/executionProfile/profileId").asText());
        assertEquals("COMPLETED_WITH_BLOCKERS", result.path("status").asText(), result.toPrettyString());
        for (var row : result.path("candidates")) {
            assertEquals("ACMG_AMP_2015:BA1", row.at("/ba1/criterionId").asText());
            assertFalse(row.path("attempts").isEmpty());
            for (var attempt : row.path("attempts")) {
                assertEquals("ACMG_AMP_2015:BS1", attempt.at("/bs1/criterionId").asText());
                assertEquals(base.mapper.valueToTree(criteria.subList(2, criteria.size())), base.mapper.valueToTree(
                        java.util.stream.StreamSupport.stream(attempt.at("/localBatch/criteria").spliterator(), false)
                                .map(r -> r.path("criterionId").asText()).toList()));
            }
            assertTrue(row.path("enabledRuleResultsComplete").asBoolean(), row.toPrettyString());
        }
        assertTrue(result.path("providerCalls").isNull());
        assertTrue(result.path("populationCalls").isObject());
    }
    @Test void oneSubmissionRetainsPopulationSuccessAndSixIndependentReasonsAcrossReopenAndExactRepeat() throws Exception {
        var options = options(); assertEquals(1, options.path("availableWorkflows").size());
        assertEquals(base.mapper.valueToTree(criteria), options.path("enabledCriteria"));
        assertTrue(options.at("/candidates/0/recommendation/bs1ContextPreparation").isObject());
        var saved = submit(); assertEight(saved);
        assertTrue(java.util.stream.StreamSupport.stream(saved.path("candidates").spliterator(), false)
                .anyMatch(row -> "MET".equals(row.at("/ba1/evaluation/evaluationResult/status").asText())), saved.toPrettyString());
        assertTrue(saved.toString().contains("MAX_CREDIBLE_AF"), "The existing BS1 knowledge model must survive composition");
        boolean bs1Met = false;
        for (var row : saved.path("candidates")) for (var attempt : row.path("attempts"))
            bs1Met |= "MET".equals(attempt.at("/bs1/evaluation/evaluationResult/status").asText());
        assertTrue(bs1Met, saved.toPrettyString());
        String counts = Files.readString(temporary.resolve("queries.txt"));
        assertEquals(saved, submit());
        var declaration = base.request.deepCopy();
        base.closeDatabase(); base.recreateRepositories(); fixture.local.fixture.wire(); fixture.wire(); population = base.service; install();
        base.request = declaration;
        var response = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(saved, base.mapper.readTree(response.getContentAsByteArray()));
        assertEquals(saved, submit()); assertEquals(counts, Files.readString(temporary.resolve("queries.txt")));
        String output = System.getProperty(fourteen?"varvis.fourteen.artifacts":eleven?"varvis.eleven.artifacts":"unified.business.output", "");
        if (!output.isBlank()) {
            Path folder = fourteen?Path.of(output).resolve("fourteen-business"):eleven?Path.of(output).resolve("eleven-business"):Path.of(output); Files.createDirectories(folder);
            Files.writeString(folder.resolve("options.json"), options.toPrettyString(), StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("saved.json"), base.mapper.writeValueAsString(saved), StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("reopened.json"), response.getContentAsString(), StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("repeated.json"), base.mapper.writeValueAsString(submit()), StandardOpenOption.CREATE_NEW);
        }
    }
    @Test void missingSelectionsNeverDisableAlleleBa1OrInventLocalTranscripts() throws Exception {
        for (var ref : base.request.path("recommendations")) ((ObjectNode) ref).putNull("stableRelationshipKey").putNull("annotationId");
        var saved = submit(); assertEight(saved);
        for (var row : saved.path("candidates")) {
            assertEquals("MET", row.at("/ba1/evaluation/evaluationResult/status").asText());
            for (var attempt : row.path("attempts")) {
                assertEquals("NOT_EVALUATED", attempt.at("/bs1/evaluation/evaluationResult/status").asText());
                for (var result : attempt.at("/localBatch/criteria")) assertEquals("NOT_EVALUATED", result.path("status").asText());
            }
        }
    }
    @Test void selectedTranscriptReachesSixModulesAlongsidePopulationWithoutChangingRankedCandidates() throws Exception {
        var before = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2));
        selectCurrentAnnotations();
        var saved = submit(); assertEight(saved);
        assertTrue(java.util.stream.StreamSupport.stream(saved.path("candidates").spliterator(), false)
                .flatMap(row -> java.util.stream.StreamSupport.stream(row.path("attempts").spliterator(), false))
                .anyMatch(a -> a.path("context").isObject() && a.at("/localBatch/contextId").equals(a.at("/context/contextId"))), saved.toPrettyString());
        assertEquals(before, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2)));
    }
    private void selectCurrentAnnotations() {
        var options = options();
        var hypotheses = base.request.putArray("hypotheses");
        for (var row : options.path("candidates")) {
            var rec = row.path("recommendation");
            JsonNode relation = null, annotation = null;
            for (var r : rec.path("relationships")) if (r.path("selectable").asBoolean()) { relation = r; break; }
            for (var a : rec.path("annotations")) if (a.path("contextSelectable").asBoolean()) { annotation = a; break; }
            if (relation == null || annotation == null) continue;
            var provenance = base.mapper.createObjectNode().put("source", "EXPLICIT_USER_DECLARATION").put("version", "1.0.0")
                    .put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
            var item = hypotheses.addObject().put("affectedStatus", "UNKNOWN");
            var h = item.putObject("hypothesis");
            h.set("candidateId", row.path("candidateId")); h.set("hypothesisId", rec.path("hypothesisId"));
            for (String key : List.of("hgncId", "mondoId", "moi")) h.set(key, relation.path(key));
            h.set("provenance", provenance);
            String tx = annotation.path("versionedTranscriptId").asText(); int dot = tx.lastIndexOf('.');
            var transcript = item.putObject("transcript").put("accession", tx.substring(0, dot))
                    .put("version", Integer.parseInt(tx.substring(dot + 1)))
                    .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                    .put("selectionReason", "Synthetic explicit selection for unified business verification");
            transcript.set("annotationSource", annotation.path("annotationSource"));
            transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion")); transcript.set("source", provenance);
            for (var ref : base.request.path("recommendations")) if (ref.path("candidateId").equals(row.path("candidateId"))) {
                ((ObjectNode) ref).set("annotationId", annotation.path("annotationId"));
                ((ObjectNode) ref).set("stableRelationshipKey", relation.path("stableRelationshipKey"));
            }
        }
        assertFalse(hypotheses.isEmpty());
    }
    @Test void oldPopulationHistoryRemainsExactAndCannotBeRetriedUnderEightRuleConfirmation() throws Exception {
        base.service = population; fixture.wire(); population = base.service;
        var old = fixture.post();
        install();
        assertEquals(old, base.service.read(base.analysisId, old.path("runId").asText()));
        String counts = Files.readString(temporary.resolve("queries.txt"));
        base.request.put("retryOf", old.path("runId").asText());
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isConflict());
        assertEquals(counts, Files.readString(temporary.resolve("queries.txt")));
    }
    @Test void oldConfirmationsAndRetiredWorkflowCannotExecuteUnifiedScope() throws Exception {
        for (String old : List.of(AcmgBusinessRuleScope.BS1_DECLARATION, AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION)) {
            base.request.put("declarationVersion", old);
            base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                    .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isBadRequest());
        }
        base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("workflow", AcmgBusinessRuleScope.LOCAL_BATCH1)).andExpect(status().isConflict());
        assertFalse(Files.exists(temporary.resolve("queries.txt")));
    }
    @Test void sourceFailureGivesAllEightReasonsWithoutPopulationQuery() throws Exception {
        new org.springframework.jdbc.core.JdbcTemplate(Objects.requireNonNull(base.dataSource))
                .update("DELETE FROM variant_analysis_acmg_source WHERE analysis_id=?", base.analysisId);
        // Refresh confirmations against the actual failed source; stale confirmations must not be accepted.
        install();
        var saved = submit(); assertEight(saved);
        assertFalse(Files.exists(temporary.resolve("queries.txt")));
    }
    @Test void elevenKeepsPopulationSuccessAndNineModuleOutcomesAcrossDatabaseReopen() throws Exception {
        enableEleven();oneSubmissionRetainsPopulationSuccessAndSixIndependentReasonsAcrossReopenAndExactRepeat();
    }
    @Test void elevenSelectedInputsReachEachNewFactoryOnceWithoutChangingOriginalCandidates() throws Exception {
        enableEleven();selectedTranscriptReachesSixModulesAlongsidePopulationWithoutChangingRankedCandidates();
        assertEquals(Map.of("PVS1",1),opened);
        var saved=submit();assertEquals(Map.of("PVS1",1),opened);
        for(var row:saved.path("candidates"))for(var attempt:row.path("attempts")) {
            assertFalse(attempt.has("pvs1"));assertFalse(attempt.has("pm4"));assertFalse(attempt.has("bp3"));
            for(var result:attempt.at("/localBatch/criteria")) {
                if(result.path("criterionId").asText().endsWith(":PVS1")) assertEquals("PVS1_TEST_RESOURCE_UNAVAILABLE",result.path("reasonCode").asText());
                if(result.path("criterionId").asText().endsWith(":PM4") || result.path("criterionId").asText().endsWith(":BP3")) assertNonLengthRouting(result);
            }
        }
    }
    private static void assertNonLengthRouting(com.fasterxml.jackson.databind.JsonNode result) {
        assertEquals("ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE",result.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED",result.path("status").asText()); assertEquals("NONE",result.path("appliedStrength").asText());
        assertEquals("NOT_APPLICABLE",result.at("/details/applicabilityRouting/status").asText());
        assertTrue(result.at("/details/applicabilityRouting/skipSpecializedAcquisition").asBoolean());
        assertEquals("NOT_REQUIRED",result.at("/details/materialPreparation/status").asText());
    }

    @Test void elevenMissingSourceDoesNotOpenAnyNewModule() throws Exception {
        enableEleven();sourceFailureGivesAllEightReasonsWithoutPopulationQuery();assertTrue(opened.isEmpty());
    }
    @Test void eightHistoryRemainsExactWhileItsConfirmationAndRetryCannotRunEleven() throws Exception {
        var old=submit();String counts=Files.readString(temporary.resolve("queries.txt"));enableEleven();
        assertEquals(old,base.service.read(base.analysisId,old.path("runId").asText()));
        base.request.put("retryOf",old.path("runId").asText());
        base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isConflict());
        base.request.remove("retryOf");base.request.put("declarationVersion",AcmgBusinessRuleScope.UNIFIED_LOCAL_DECLARATION);
        base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isBadRequest());
        assertEquals(counts,Files.readString(temporary.resolve("queries.txt")));assertTrue(opened.isEmpty());
    }

    @Test void fourteenPersistsAllIndependentOutcomesAndReusesExactRequestAcrossReopen() throws Exception {
        enableFourteen(); oneSubmissionRetainsPopulationSuccessAndSixIndependentReasonsAcrossReopenAndExactRepeat();
        assertFalse(effectPins.isEmpty()); assertEquals(1, new HashSet<>(effectPins).size());
        assertTrue(effectPins.get(0).matches("[0-9a-f]{64}"));
        var policy = options().path("candidates").get(0).path("recommendation");
        assertEquals("1.0.0-unified-local-fourteen", policy.path("policyVersion").asText());
    }
    @Test void fourteenSelectedInputsIsolateEachMissingModuleAndRetainOriginalCandidates() throws Exception {
        enableFourteen(); selectedTranscriptReachesSixModulesAlongsidePopulationWithoutChangingRankedCandidates();
        var expected = Map.of("PVS1",1,"PM1",1,"PP2",1,"BP1",1);
        assertEquals(expected,opened); var saved = submit(); assertEquals(expected,opened);
        for (var row : saved.path("candidates")) for (var attempt : row.path("attempts")) {
            for (var result : attempt.at("/localBatch/criteria"))
                if (result.path("criterionId").asText().endsWith(":PM4") || result.path("criterionId").asText().endsWith(":BP3")) assertNonLengthRouting(result);
            assertFalse(attempt.has("pm1"), "Legacy PM1 append must not run alongside the batch");
            for (var result : attempt.at("/localBatch/criteria")) for (String code : List.of("PM1","PP2","BP1"))
                if (result.path("criterionId").asText().endsWith(":"+code)) assertEquals(code+"_TEST_RESOURCE_UNAVAILABLE", result.path("reasonCode").asText());
        }
    }
    @Test void fourteenMissingSourceNeverOpensNewModuleFactories() throws Exception {
        enableFourteen(); sourceFailureGivesAllEightReasonsWithoutPopulationQuery(); assertTrue(opened.isEmpty());
    }
    @Test void elevenHistoryIsUnchangedAndCannotBeRetriedWithFourteenScope() throws Exception {
        enableEleven(); var old = submit(); String counts = Files.readString(temporary.resolve("queries.txt")); enableFourteen();
        var priorFactories = Map.copyOf(opened);
        assertEquals(old, base.service.read(base.analysisId,old.path("runId").asText()));
        base.request.put("retryOf", old.path("runId").asText());
        base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isConflict());
        base.request.remove("retryOf"); base.request.put("declarationVersion", AcmgBusinessRuleScope.UNIFIED_ELEVEN_DECLARATION);
        base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isBadRequest());
        assertEquals(counts,Files.readString(temporary.resolve("queries.txt"))); assertEquals(priorFactories,opened);
    }

}
