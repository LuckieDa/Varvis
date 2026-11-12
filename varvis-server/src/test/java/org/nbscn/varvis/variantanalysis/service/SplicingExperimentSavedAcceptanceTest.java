package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.controller.SplicingExperimentCheckController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real saved computation and file H2 copy; no new inference and no mutation of historical artifacts. */
@EnabledIfSystemProperty(named = "varvis.splice-check.saved-acceptance", matches = "true")
class SplicingExperimentSavedAcceptanceTest {
    @Test void savedRealLmnaAndUncoveredControlThroughHttpAndRebuiltDatabase() throws Exception {
        ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        Path original = Path.of("target/bp7-synonymous-acceptance");
        Path originalJson = original.resolve("attempt-625e1009-1bd1-4d99-86f4-8d880a309636/saved-business.json");
        String originalBytesHash = AcmgBusinessStore.sha(Files.readString(originalJson));
        assertEquals("d46101d36eae532252f2e3acf0405fe22fc3027c8cd17b62f3653ed3944bd9a7", originalBytesHash);
        ObjectNode expected = (ObjectNode) mapper.readTree(Files.readAllBytes(originalJson));
        Path output = Path.of("target/splicing-experiment-acceptance/attempt-" + UUID.randomUUID()); Files.createDirectories(output);
        Files.copy(original.resolve("business-db.mv.db"), output.resolve("business-db.mv.db"));
        String url = "jdbc:h2:file:" + output.resolve("business-db").toAbsolutePath().toString().replace('\\', '/') + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
        String analysis = expected.path("analysisId").asText(), runId = expected.path("runId").asText();
        AtomicInteger runtimeCalls = new AtomicInteger(); ObjectNode first = null;
        for (int phase = 0; phase < 2; phase++) {
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            try {
                var properties = new VariantAnalysisProperties(); properties.setStorageRoot(output.resolve("jobs"));
                var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource), properties, mapper);
                var store = new AcmgBusinessStore(dataSource);
                var business = new AcmgBusinessService(mapper, jobs, store, new AcmgExecutionSources(mapper, store),
                        () -> { runtimeCalls.incrementAndGet(); throw new AssertionError("No model/provider/evaluator call permitted"); },
                        () -> { runtimeCalls.incrementAndGet(); throw new AssertionError("No BA1 call permitted"); });
                var checks = new SplicingExperimentChecks(mapper, new ClassPathResource("acmg/public-splicing-experiments-v1.json"), output.resolve("checks"), business::read);
                var http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(business), new SplicingExperimentCheckController(checks)).build();
                String base = "/variant-analyses/" + analysis + "/acmg/runs/" + runId;
                assertEquals(expected, mapper.readTree(http.perform(get(base)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
                ObjectNode value = (ObjectNode) mapper.readTree(http.perform(post(base + "/splicing-checks")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
                if (first == null) first = value; else assertEquals(first, value);
                String id = value.path("checkId").asText();
                for (String suffix : new String[]{"", "/export"}) assertEquals(value, mapper.readTree(http.perform(get(base + "/splicing-checks/" + id + suffix)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
                assertEquals(4, value.path("checks").size());
                for (JsonNode check : value.path("checks")) {
                    long pos = check.path("context").path("variant").path("inputAllele").path("position").asLong();
                    assertEquals(pos == 156138613 ? "OBSERVATION_MISMATCH_REVIEW_REQUIRED" : "UNVERIFIED", check.path("status").asText());
                    assertEquals("MET", check.path("calculation").path("evaluationResult").path("status").asText());
                    assertEquals("SUPPORTING", check.path("calculation").path("appliedStrength").asText());
                }
                Files.write(output.resolve("phase-" + phase + "-check.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
                assertEquals(expected, business.read(analysis, runId));
            } finally { new JdbcTemplate(dataSource).execute("SHUTDOWN"); }
        }
        assertEquals(0, runtimeCalls.get());
        assertEquals(originalBytesHash, AcmgBusinessStore.sha(Files.readString(originalJson)));
        ObjectNode report = mapper.createObjectNode().put("savedCalculation", "REAL_PREVIOUS_RUN")
                .put("originalBiologicalCounterexamplePassed", false).put("experimentPromptPassed", true)
                .put("exomiserCalls", 0).put("vepCalls", 0).put("spliceAiCalls", 0).put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("httpReadExportRebuildReusePassed", true).put("pageCovered", false);
        Files.write(output.resolve("report.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        System.out.println("Saved experiment acceptance: " + output);
    }
}
