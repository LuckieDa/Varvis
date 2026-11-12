package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full synthetic local source/runtime/H2/HTTP path with a data-defined HBB model. */
class Bs1KnowledgeBusinessTest {
    @TempDir Path temporary;
    Ba1LocalBusinessTest local;
    Bs1KnowledgeCatalog catalog;
    List<ObjectNode> knowledgeRecords;
    @BeforeEach void setup() throws Exception {
        local = new Ba1LocalBusinessTest(); local.temporary = temporary; local.setup(); local.local("HIT");
        var base = local.fixture.base; var factory = new Bs1KnowledgeCatalogTest();
        var records = new ArrayList<ObjectNode>();
        for (var item : base.request.path("hypotheses")) {
            var h = item.path("hypothesis");
            if ("HGNC:4827".equals(h.path("hgncId").asText()))
                records.add(factory.record(h.path("hgncId").asText(), h.path("mondoId").asText(), "AUTOSOMAL_RECESSIVE"));
            ((ObjectNode) item).remove("transcript");
        }
        assertFalse(records.isEmpty());
        knowledgeRecords = records;
        byte[] bytes = base.mapper.writeValueAsBytes(factory.bundle(records.toArray(ObjectNode[]::new)));
        var file = temporary.resolve("knowledge.json"); Files.write(file, bytes);
        catalog = Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(bytes));
        base.request.putArray("hypotheses");
        wire();
    }
    void wire() {
        var base = local.fixture.base;
        base.service = AcmgBusinessService.formalPopulation(base.service, () -> Bs1Runtime.localExomes(base.mapper, new VariantAnalysisProperties(), true, catalog));
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        base.request.put("declarationVersion", AcmgBusinessRuleScope.BS1_DECLARATION);
        var options = base.service.inputOptions(base.analysisId, 2); var selections = base.request.putArray("recommendations");
        for (var row : options.path("candidates")) {
            var selected = selections.addObject(); var recommendation = row.path("recommendation");
            for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) selected.set(key, recommendation.path(key));
            var key = recommendation.at("/bs1ContextPreparation/eligibleRelationshipKeys/0");
            if (key.isTextual()) selected.set("stableRelationshipKey", key); else selected.putNull("stableRelationshipKey");
            selected.putNull("annotationId");
        }
    }
    @AfterEach void close() { if (local != null) local.close(); }
    com.fasterxml.jackson.databind.JsonNode post() throws Exception {
        var base = local.fixture.base;
        var response = base.http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/{id}/acmg/runs", base.analysisId)
                .contentType("application/json").content(requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getErrorMessage() + response.getContentAsString());
        return base.mapper.readTree(response.getContentAsByteArray());
    }
    @Test void dataDefinedModelPersistsAndReusesAfterDatabaseReopen() throws Exception {
        var fixture = local.fixture; var base = fixture.base;
        var result = post(); boolean modelMet = false;
        for (var candidate : result.path("candidates")) for (var attempt : candidate.path("attempts")) {
            var bs1 = attempt.path("bs1");
            if ("MAX_CREDIBLE_AF".equals(bs1.at("/preparation/policy/knowledgeRecord/kind").asText())) {
                assertEquals("MET", bs1.at("/evaluation/evaluationResult/status").asText(), bs1.toPrettyString()); modelMet = true;
            }
        }
        assertTrue(modelMet, result.toPrettyString());
        assertEquals(0, result.at("/bs1Calls/provider").asInt());
        String calls = Files.readString(temporary.resolve("queries.txt"));
        assertEquals(result, post()); assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
        base.closeDatabase(); base.recreateRepositories(); fixture.wire(); wire();
        assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
        assertEquals(result, post()); assertEquals(calls, Files.readString(temporary.resolve("queries.txt")));
        String output = System.getProperty("bs1.knowledge.business-output", "");
        if (!output.isBlank()) {
            Path folder = Path.of(output).resolve(catalog.identity().has("frequencyStatisticVersion") ? "target-af" : catalog.identity().has("qualificationVersion") ? "qualified" : "legacy"); Files.createDirectories(folder);
            Files.writeString(folder.resolve("saved.json"), base.mapper.writeValueAsString(result), StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("reopened.json"), base.mapper.writeValueAsString(base.service.read(base.analysisId, result.path("runId").asText())), StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("repeated.json"), base.mapper.writeValueAsString(post()), StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("counts.json"), "{\"populationQueriesBeforeAndAfterEqual\":true,\"bs1AdditionalPopulationQueries\":0,\"dataClass\":\"SYNTHETIC\"}", StandardOpenOption.CREATE_NEW);
        }
    }
    @Test void qualifiedModelUsesSameConfirmationSaveReadAndReusePath() throws Exception {
        for (var record : knowledgeRecords) Bs1KnowledgeQualificationTest.qualify(record);
        byte[] bytes = local.fixture.base.mapper.writeValueAsBytes(new Bs1KnowledgeCatalogTest().bundle(knowledgeRecords.toArray(ObjectNode[]::new)));
        var file = temporary.resolve("qualified-knowledge.json"); Files.write(file, bytes);
        catalog = Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(bytes)); wire();
        // The existing full business assertion also closes/reopens its task H2,
        // compares full JSON and verifies no additional population queries.
        dataDefinedModelPersistsAndReusesAfterDatabaseReopen();
    }
    @Test void targetAfUsesSameConfirmationSaveReadAndReusePath() throws Exception {
        for (var record : knowledgeRecords) Bs1FrequencyStatisticTest.useAf(record, "AF", "nfe");
        byte[] bytes = local.fixture.base.mapper.writeValueAsBytes(new Bs1KnowledgeCatalogTest().bundle(knowledgeRecords.toArray(ObjectNode[]::new)));
        var file = temporary.resolve("af-knowledge.json"); Files.write(file, bytes);
        catalog = Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(bytes)); wire();
        dataDefinedModelPersistsAndReusesAfterDatabaseReopen();
    }
    @Test void unavailableBackgroundRetainsSupplyThroughHttpReopenAndReuse() throws Exception {
        var base = local.fixture.base; var b = new Bs1KnowledgeCatalogTest().bundle();
        for (var record : knowledgeRecords) {
            var scope = record.path("scope");
            b.withArray("/backgrounds").add(Bs1ParameterSupplyTest.background(base.mapper,
                    scope.path("hgncId").asText(), scope.path("mondoId").asText(), scope.at("/modes/0").asText()));
        }
        byte[] raw = base.mapper.writeValueAsBytes(b); var file = temporary.resolve("supply.json"); Files.write(file, raw);
        catalog = Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(raw)); wire();
        var result = post(); int explained = 0;
        for (var candidate : result.path("candidates")) for (var attempt : candidate.path("attempts"))
            for (var option : attempt.at("/bs1ContextPreparation/policyOptions")) if (option.path("knowledgeGap").has("parameterSupply")) {
                explained++;
                assertFalse(option.path("available").asBoolean(true));
                assertEquals("NOT_EVALUATED", attempt.at("/bs1/evaluation/evaluationResult/status").asText());
                assertEquals("CANDIDATE_AVAILABLE", option.at("/knowledgeGap/parameterSupply/fields/prevalence/status").asText());
                assertEquals("SOURCE_GAP", option.at("/knowledgeGap/parameterSupply/fields/penetrance/status").asText());
                assertEquals(catalog.identity(), option.at("/knowledgeGap/knowledgeIdentity"));
            }
        assertTrue(explained > 0, result.toPrettyString());
        String calls = Files.readString(temporary.resolve("queries.txt"));
        assertEquals(result, post());
        base.closeDatabase(); base.recreateRepositories(); local.fixture.wire(); wire();
        var response = base.http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/variant-analyses/{id}/acmg/runs/{runId}", base.analysisId, result.path("runId").asText())).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var reopened = base.mapper.readTree(response.getContentAsByteArray());
        assertEquals(result, reopened); assertEquals(result, post());
        assertEquals(calls, Files.readString(temporary.resolve("queries.txt")));
        String output = System.getProperty("bs1.knowledge.business-output", "");
        if (!output.isBlank()) {
            Path folder = Path.of(output).resolve("parameter-supply"); Files.createDirectories(folder);
            Files.writeString(folder.resolve("saved.json"), base.mapper.writeValueAsString(result), StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("reopened.json"), base.mapper.writeValueAsString(reopened), StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("repeated.json"), base.mapper.writeValueAsString(post()), StandardOpenOption.CREATE_NEW);
        }
        // A later snapshot never replaces explanation in an already saved record.
        b.put("version", "SYNTHETIC-2"); raw = base.mapper.writeValueAsBytes(b); Files.write(file, raw);
        catalog = Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(raw)); wire();
        assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
    }
}
