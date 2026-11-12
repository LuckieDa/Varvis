package org.nbscn.varvis.variantanalysis.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.HpoOntologyTermStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInput;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInputStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeRescueSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeSupportedCandidate;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class PhenotypeSummaryPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripPreservesHpoProvenancePriorAndRankingSummary() throws Exception {
        GenePhenotypePrior prior = new GenePhenotypePrior(
                "GENE1",
                "ENSG000001",
                0.91,
                1,
                "PHEN2GENE_LOCAL_KB",
                "kb-v1",
                Collections.singletonList("HP:0001250"),
                Collections.singletonList("matched phenotype"),
                GenePhenotypePriorStatus.AVAILABLE,
                Instant.parse("2026-07-11T00:00:00Z"),
                Collections.emptyList()
        );
        PhenotypeAwareSummary source = new PhenotypeAwareSummary(
                2,
                1,
                1,
                0,
                Collections.singletonList("HP:0001250"),
                Collections.singletonList("HP:invalid"),
                Collections.singletonList("one invalid HPO term"),
                Collections.singletonList(new PhenotypeInput(
                        "HP:0001250",
                        "Seizure",
                        "MANUAL_INPUT",
                        PhenotypeInputStatus.VALID,
                        1.0,
                        "Seizure",
                        Collections.emptyList()
                )),
                true,
                true,
                new GenePhenotypePriorSummary(
                        true,
                        "PHEN2GENE_LOCAL_KB",
                        "kb-v1",
                        GenePhenotypePriorStatus.AVAILABLE,
                        2,
                        1,
                        1,
                        Collections.singletonList(prior),
                        Collections.emptyList(),
                        Instant.parse("2026-07-11T00:00:00Z")
                ),
                1,
                Collections.singletonList(new PhenotypeSupportedCandidate(
                        "GRCh38:1:100:A:G",
                        "GENE1",
                        "HIGH_PRIORITY",
                        0.91,
                        1,
                        7.2,
                        "PHEN2GENE_LOCAL_KB"
                )),
                new PhenotypeRescueSummary(
                        "AVAILABLE",
                        "AVAILABLE",
                        "intervals.tsv:100:200:GRCh38",
                        2,
                        2,
                        3,
                        2,
                        1,
                        0,
                        "GRCh38",
                        3000,
                        Collections.singletonList("one rescue warning")
                )
        );

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PhenotypeAwareSummary restored = mapper.readValue(
                mapper.writeValueAsString(source),
                PhenotypeAwareSummary.class
        );

        assertTrue(restored.isEnabled());
        assertTrue(restored.isRankingApplied());
        assertEquals(source.getValidHpoTerms(), restored.getValidHpoTerms());
        assertEquals("MANUAL_INPUT", restored.getPhenotypeInputs().get(0).getSource());
        assertEquals(GenePhenotypePriorStatus.AVAILABLE, restored.getGenePriorSummary().getStatus());
        assertEquals("GENE1", restored.getGenePriorSummary().getTopGenePriors().get(0).getGeneSymbol());
        assertEquals(7.2, restored.getTopPhenotypeSupportedCandidates().get(0).getScoreBoost(), 0.000001);
        assertEquals(2, restored.getPhenotypeRescueSummary().getRescuedSelectedCount());
        assertEquals(1, restored.getPhenotypeRescueSummary().getRescueRejectedByHardGateCount());
    }

    @Test
    void legacySummaryWithoutPayloadIsExplicitlyUnavailable() {
        PhenotypeAwareSummary summary = PhenotypeAwareSummary.historicalSummaryUnavailable();

        assertTrue(summary.getWarnings().stream()
                .anyMatch(value -> value.contains("Historical phenotype summary is unavailable")));
        assertEquals(0, summary.getInputHpoCount());
        assertTrue(summary.getGenePriorSummary().getTopGenePriors().isEmpty());
    }

    @Test
    void additiveFieldsRoundTripAndLegacyJsonWithoutThemRemainsReadable() throws Exception {
        PhenotypeAwareSummary source = excludedOnlySummary();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        PhenotypeAwareSummary restored = mapper.readValue(
                mapper.writeValueAsString(source), PhenotypeAwareSummary.class);
        assertFalse(restored.isEnabled());
        assertTrue(restored.hasAnyPhenotypeInput());
        assertEquals(Collections.singletonList("HP:0000002"), restored.getExcludedHpoTerms());
        assertEquals(1, restored.getExcludedHpoCount());
        assertTrue(restored.getPhenotypeInputs().get(0).isExcluded());
        assertEquals(HpoOntologyTermStatus.CURRENT,
                restored.getPhenotypeInputs().get(0).getOntologyStatus());

        String legacyJson = "{"
                + "\"inputHpoCount\":1,\"validHpoCount\":1,\"invalidHpoCount\":0,"
                + "\"duplicateHpoCount\":0,\"validHpoTerms\":[\"HP:0000001\"],"
                + "\"invalidHpoTerms\":[],\"warnings\":[],"
                + "\"phenotypeInputs\":[{\"hpoId\":\"HP:0000001\",\"label\":\"Legacy\","
                + "\"source\":\"MANUAL\",\"status\":\"VALID\",\"confidence\":1.0,"
                + "\"matchedTerm\":\"HP:0000001\",\"warnings\":[]}],"
                + "\"enabled\":true,\"rankingApplied\":false}";
        PhenotypeAwareSummary legacy = mapper.readValue(legacyJson, PhenotypeAwareSummary.class);
        assertTrue(legacy.getExcludedHpoTerms().isEmpty());
        assertFalse(legacy.getPhenotypeInputs().get(0).isExcluded());
        assertEquals("HP:0000001", legacy.getPhenotypeInputs().get(0).getRequestedHpoId());
        assertEquals(HpoOntologyTermStatus.UNAVAILABLE,
                legacy.getPhenotypeInputs().get(0).getOntologyStatus());
    }

    @Test
    void jdbcSchemaOnePersistsAndReloadsExcludedOnlySummary() throws Exception {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        JdbcVariantAnalysisJobRepository repository = new JdbcVariantAnalysisJobRepository(
                mock(NamedParameterJdbcTemplate.class),
                mock(PlatformTransactionManager.class),
                properties,
                new ObjectMapper().findAndRegisterModules()
        );
        PhenotypeAwareSummary source = excludedOnlySummary();
        VariantAnalysisJob job = new VariantAnalysisJob(
                "synthetic-analysis",
                "synthetic.vcf",
                repeat('a', 64),
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                tempDir,
                tempDir.resolve("synthetic.vcf"),
                source
        );

        String schema = (String) invoke(repository, "phenotypeSummarySchemaVersion",
                new Class<?>[]{VariantAnalysisJob.class}, job);
        String payload = (String) invoke(repository, "writePhenotypeSummary",
                new Class<?>[]{PhenotypeAwareSummary.class}, source);
        PhenotypeAwareSummary restored = (PhenotypeAwareSummary) invoke(
                repository,
                "readPhenotypeSummary",
                new Class<?>[]{String.class, String.class},
                schema,
                payload
        );

        assertEquals("1", schema);
        assertNotNull(payload);
        assertEquals(Collections.singletonList("HP:0000002"), restored.getExcludedHpoTerms());
        assertTrue(restored.hasAnyPhenotypeInput());
        assertFalse(restored.isEnabled());
    }

    private PhenotypeAwareSummary excludedOnlySummary() {
        PhenotypeInput excluded = new PhenotypeInput(
                "HP:0000002",
                "Explicitly absent",
                "MANUAL",
                PhenotypeInputStatus.VALID,
                1.0d,
                "HP:0000002",
                Collections.emptyList(),
                "HP:0000002",
                null,
                "Explicitly absent",
                HpoOntologyTermStatus.CURRENT,
                true
        );
        return new PhenotypeAwareSummary(
                0,
                0,
                0,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("observational only"),
                Collections.singletonList(excluded),
                Collections.singletonList("HP:0000002"),
                false,
                false,
                GenePhenotypePriorSummary.notGenerated(),
                0,
                Collections.emptyList(),
                PhenotypeRescueSummary.disabled()
        );
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private String repeat(char value, int count) {
        return String.valueOf(value).repeat(count);
    }
}
