package org.nbscn.varvis.variantanalysis.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcCandidateIdentityRoundTripTest {

    private static final String ANALYSIS_ID = "analysis";
    private static final String CANONICAL_ID = "GRCh38|1|100|A|T";

    @Test
    void preservesDuplicateCanonicalCandidateRowsAcrossJdbcRoundTripAndRestart() {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        VariantInterpretation dominant = candidate(
                1,
                "AUTOSOMAL_DOMINANT",
                1,
                0.40d
        );
        VariantInterpretation recessive = candidate(
                2,
                "AUTOSOMAL_RECESSIVE",
                2,
                0.90d
        );

        repository.replaceInterpretations(ANALYSIS_ID, Arrays.asList(dominant, recessive));

        List<VariantInterpretation> firstPage = repository.findInterpretationSummaries(
                ANALYSIS_ID,
                0,
                1,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        List<VariantInterpretation> secondPage = repository.findInterpretationSummaries(
                ANALYSIS_ID,
                1,
                1,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        assertEquals(Collections.singletonList("exomiser-v1-00000002"), candidateIds(firstPage));
        assertEquals(Collections.singletonList("exomiser-v1-00000001"), candidateIds(secondPage));
        assertCandidate(repository.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000001"
        ).orElseThrow(AssertionError::new), "AUTOSOMAL_DOMINANT", 1);
        assertCandidate(repository.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000002"
        ).orElseThrow(AssertionError::new), "AUTOSOMAL_RECESSIVE", 2);
        assertThrows(
                AmbiguousVariantIdException.class,
                () -> repository.findInterpretation(ANALYSIS_ID, CANONICAL_ID)
        );

        TranscriptConsequence annotation = new TranscriptConsequence(
                "GENE1",
                null,
                "NM_SYNTHETIC.1",
                null,
                null,
                "missense_variant"
        );
        Map<String, List<TranscriptConsequence>> annotations =
                new LinkedHashMap<String, List<TranscriptConsequence>>();
        annotations.put(CANONICAL_ID, Collections.singletonList(annotation));
        repository.updateTranscriptAnnotations(ANALYSIS_ID, annotations);

        JdbcVariantAnalysisJobRepository restarted = repository(dataSource);
        VariantInterpretation restoredDominant = restarted.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000001"
        ).orElseThrow(AssertionError::new);
        VariantInterpretation restoredRecessive = restarted.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000002"
        ).orElseThrow(AssertionError::new);
        assertCandidate(restoredDominant, "AUTOSOMAL_DOMINANT", 1);
        assertCandidate(restoredRecessive, "AUTOSOMAL_RECESSIVE", 2);
        assertEquals(1, restoredDominant.getTranscriptAnnotations().size());
        assertEquals(1, restoredRecessive.getTranscriptAnnotations().size());
        assertEquals("NM_SYNTHETIC.1", restoredDominant.getTranscriptAnnotations().get(0).getTranscriptId());
        assertEquals("NM_SYNTHETIC.1", restoredRecessive.getTranscriptAnnotations().get(0).getTranscriptId());
        assertThrows(
                AmbiguousVariantIdException.class,
                () -> restarted.findInterpretation(ANALYSIS_ID, CANONICAL_ID)
        );
    }

    @Test
    void analysisVcfCallsSurviveRepeatedCandidateFullAndSummaryRoundTrips() {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        VariantInterpretation dominant = candidate(
                1,
                "AUTOSOMAL_DOMINANT",
                1,
                0.40d
        ).withAnalysisVcfCall(analysisVcfCall(41, 1));
        VariantInterpretation recessive = candidate(
                2,
                "AUTOSOMAL_RECESSIVE",
                2,
                0.90d
        ).withAnalysisVcfCall(analysisVcfCall(52, 2));

        repository.replaceInterpretations(ANALYSIS_ID, Arrays.asList(dominant, recessive));

        List<VariantInterpretation> summaries = repository.findInterpretationSummaries(
                ANALYSIS_ID,
                0,
                10,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        assertEquals(2, summaries.size());
        assertCallAndLegacyObservation(summaries.get(0), 52, 2);
        assertCallAndLegacyObservation(summaries.get(1), 41, 1);
        assertNull(summaries.get(0).getObservation().getDepth());
        assertNull(summaries.get(1).getObservation().getDepth());

        JdbcVariantAnalysisJobRepository restarted = repository(dataSource);
        VariantInterpretation restoredDominant = restarted.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000001"
        ).orElseThrow(AssertionError::new);
        VariantInterpretation restoredRecessive = restarted.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000002"
        ).orElseThrow(AssertionError::new);
        assertCallAndLegacyObservation(restoredDominant, 41, 1);
        assertCallAndLegacyObservation(restoredRecessive, 52, 2);
        assertLegacyObservationDetails(restoredDominant);
        assertLegacyObservationDetails(restoredRecessive);
    }

    @Test
    void analysisVcfCallSurvivesCompactSummaryOnlyPayload() {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        VariantInterpretation compact = candidate(
                1,
                "AUTOSOMAL_DOMINANT",
                1,
                0.40d
        ).withAnalysisVcfCall(analysisVcfCall(44, 3));
        compact.applyLargeFileClassification(
                org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket.FILTERED_COMMON,
                true,
                true,
                true,
                "LARGE_FILE_COMMON_AF",
                Collections.singletonList("common")
        );

        repository.replaceInterpretations(ANALYSIS_ID, Collections.singletonList(compact));

        VariantInterpretation full = repository.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000001"
        ).orElseThrow(AssertionError::new);
        assertCallAndLegacyObservation(full, 44, 3);
        assertLegacyObservationDetails(full);
        assertTrue(full.isSummaryOnly());
        assertEquals(false, full.isFullInterpretationAvailable());

        VariantInterpretation summary = repository.findInterpretationSummaries(
                ANALYSIS_ID,
                0,
                10,
                VariantInterpretationView.ALL,
                VariantInterpretationFilter.empty()
        ).get(0);
        assertCallAndLegacyObservation(summary, 44, 3);
        assertNull(summary.getObservation().getDepth());
        assertNull(summary.getObservation().getAlleleDepths());
        assertTrue(summary.isSummaryOnly());
        assertEquals(false, summary.isFullInterpretationAvailable());
    }

    @Test
    void legacyPayloadWithoutAnalysisVcfCallDeserializesAsNull() throws Exception {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        VariantInterpretation source = candidate(
                1,
                "AUTOSOMAL_DOMINANT",
                1,
                0.40d
        ).withAnalysisVcfCall(analysisVcfCall(47, 4));
        repository.replaceInterpretations(ANALYSIS_ID, Collections.singletonList(source));

        JdbcTemplate jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(dataSource));
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM variant_analysis_interpretation WHERE analysis_id = ?",
                String.class,
                ANALYSIS_ID
        );
        ObjectNode legacyTree = (ObjectNode) objectMapper().readTree(payload);
        legacyTree.remove("analysisVcfCall");
        jdbc.update(
                "UPDATE variant_analysis_interpretation SET payload_json = ? WHERE analysis_id = ?",
                objectMapper().writeValueAsString(legacyTree),
                ANALYSIS_ID
        );

        VariantInterpretation restored = repository.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000001"
        ).orElseThrow(AssertionError::new);
        assertNull(restored.getAnalysisVcfCall());
        assertEquals("0/1", restored.getObservation().getGenotype());
        assertEquals(30, restored.getObservation().getDepth());

        VariantInterpretation summary = repository.findInterpretationSummaries(
                ANALYSIS_ID,
                0,
                10,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        ).get(0);
        assertNull(summary.getAnalysisVcfCall());
        assertEquals("0/1", summary.getObservation().getGenotype());
    }

    @Test
    void jdbcSearchMatchesInMemoryForExactGenesAndGeneShapedStructuredValues() {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository jdbcRepository = repository(dataSource);
        InMemoryVariantAnalysisJobRepository memoryRepository =
                new InMemoryVariantAnalysisJobRepository();
        List<VariantInterpretation> candidates = Arrays.asList(
                searchableCandidate(1, "PPA2", "AD", "rs123", 0.90d),
                searchableCandidate(2, "PAPPA2", "AR", "rs999", 0.80d)
        );
        jdbcRepository.replaceInterpretations(ANALYSIS_ID, candidates);
        memoryRepository.replaceInterpretations(ANALYSIS_ID, candidates);

        List<VariantInterpretation> jdbcSummaries = jdbcRepository.findInterpretationSummaries(
                ANALYSIS_ID,
                0,
                10,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        assertEquals(2, jdbcSummaries.size());
        assertNotNull(jdbcSummaries.get(0).getExomiserCandidateFeatures());

        for (String search : Arrays.asList(
                "PPA2", "PAPPA2", "AD", "AR", "rs123", "result.variants.tsv")) {
            VariantInterpretationFilter filter = new VariantInterpretationFilter(
                    null, false, false, null, search
            );
            List<String> expected = candidateIds(memoryRepository.findInterpretationSummaries(
                    ANALYSIS_ID, 0, 10, VariantInterpretationView.CANDIDATES, filter
            ));
            List<String> actual = candidateIds(jdbcRepository.findInterpretationSummaries(
                    ANALYSIS_ID, 0, 10, VariantInterpretationView.CANDIDATES, filter
            ));

            assertEquals(expected, actual, "search parity for " + search);
            assertEquals(
                    memoryRepository.countInterpretations(
                            ANALYSIS_ID, VariantInterpretationView.CANDIDATES, filter),
                    jdbcRepository.countInterpretations(
                            ANALYSIS_ID, VariantInterpretationView.CANDIDATES, filter),
                    "count parity for " + search
            );
        }

        VariantInterpretationFilter exactGene = new VariantInterpretationFilter(
                null, false, false, null, "PPA2"
        );
        assertEquals(
                Collections.singletonList("exomiser-v1-00000001"),
                candidateIds(jdbcRepository.findInterpretationSummaries(
                        ANALYSIS_ID, 0, 10, VariantInterpretationView.CANDIDATES, exactGene
                ))
        );
    }

    @Test
    void rejectsTamperedPersistedCandidateIdForExomiserPayload() {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        repository.replaceInterpretations(
                ANALYSIS_ID,
                Collections.singletonList(candidate(
                        1,
                        "AUTOSOMAL_DOMINANT",
                        1,
                        0.90d
                ))
        );
        String tamperedCandidateId = "exomiser-v1-99999999";
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).update(
                "UPDATE variant_analysis_interpretation SET candidate_id = ? "
                        + "WHERE analysis_id = ?",
                tamperedCandidateId,
                ANALYSIS_ID
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> repository.findCandidate(ANALYSIS_ID, tamperedCandidateId)
        );

        assertEquals("PERSISTED_CANDIDATE_ID_MISMATCH", failure.getMessage());
    }

    @Test
    void structuredFeaturesRoundTripAndTamperedFeatureIdentityFailsClosed() throws Exception {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        VariantInterpretation source = searchableCandidate(1, "GENE1", "AR", "rs123", 0.90d);
        repository.replaceInterpretations(ANALYSIS_ID, Collections.singletonList(source));

        VariantInterpretation restored = repository.findCandidate(
                ANALYSIS_ID,
                "exomiser-v1-00000001"
        ).orElseThrow(AssertionError::new);
        assertNotNull(restored.getExomiserCandidateFeatures());
        assertEquals("GENE1", restored.getExomiserCandidateFeatures().getGeneSymbol());
        assertEquals(1, restored.getExomiserCandidateFeatures().getVariantSourceOrdinal());

        JdbcTemplate jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(dataSource));
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM variant_analysis_interpretation "
                        + "WHERE analysis_id = ? AND candidate_id = ?",
                String.class,
                ANALYSIS_ID,
                "exomiser-v1-00000001"
        );
        ObjectNode tree = (ObjectNode) objectMapper().readTree(payload);
        ((ObjectNode) tree.get("exomiserCandidateFeatures")).put("variantSourceOrdinal", 2);
        jdbc.update(
                "UPDATE variant_analysis_interpretation SET payload_json = ? "
                        + "WHERE analysis_id = ? AND candidate_id = ?",
                objectMapper().writeValueAsString(tree),
                ANALYSIS_ID,
                "exomiser-v1-00000001"
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> repository.findCandidate(ANALYSIS_ID, "exomiser-v1-00000001")
        );
        assertEquals("Could not deserialize variant interpretation", failure.getMessage());
        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains(
                "EXOMISER_FEATURE_SOURCE_ORDINAL_MISMATCH"
        ));
    }

    @Test
    void summaryFallsBackOnlyForUnreadableLegacyPayloads() {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        VariantInterpretation legacy = new VariantInterpretation(
                CandidateIdentity.legacy("legacy-v1-0000000000", CANONICAL_ID),
                candidate(1, "AUTOSOMAL_DOMINANT", 1, 0.4d).getObservation(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        legacy.applyRanking(
                "REVIEW",
                0.4d,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        repository.replaceInterpretations(ANALYSIS_ID, Collections.singletonList(legacy));
        JdbcTemplate jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(dataSource));
        jdbc.update(
                "UPDATE variant_analysis_interpretation "
                        + "SET payload_json = '{broken', canonical_variant_id = NULL "
                        + "WHERE analysis_id = ?",
                ANALYSIS_ID
        );

        List<VariantInterpretation> summaries = repository.findInterpretationSummaries(
                ANALYSIS_ID,
                0,
                10,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        assertEquals(1, summaries.size());
        assertEquals("legacy-v1-0000000000", summaries.get(0).getCandidateId());
        assertEquals(CANONICAL_ID, summaries.get(0).getCanonicalVariantId());

        jdbc.update(
                "UPDATE variant_analysis_interpretation SET candidate_id = ? WHERE analysis_id = ?",
                "exomiser-v1-00000001",
                ANALYSIS_ID
        );
        assertThrows(
                IllegalStateException.class,
                () -> repository.findInterpretationSummaries(
                        ANALYSIS_ID,
                        0,
                        10,
                        VariantInterpretationView.CANDIDATES,
                        VariantInterpretationFilter.empty()
                )
        );
    }

    @Test
    void legacyShapedSummaryDoesNotHideStructuredFeatureIntegrityFailure() throws Exception {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        VariantInterpretation legacy = new VariantInterpretation(
                CandidateIdentity.legacy("legacy-v1-0000000000", CANONICAL_ID),
                candidate(1, "AUTOSOMAL_RECESSIVE", 1, 0.4d).getObservation(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        legacy.applyRanking(
                "REVIEW",
                0.4d,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        repository.replaceInterpretations(ANALYSIS_ID, Collections.singletonList(legacy));

        JdbcTemplate jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(dataSource));
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM variant_analysis_interpretation WHERE analysis_id = ?",
                String.class,
                ANALYSIS_ID
        );
        ObjectNode legacyTree = (ObjectNode) objectMapper().readTree(payload);
        ObjectNode exomiserTree = objectMapper().valueToTree(
                searchableCandidate(1, "GENE1", "AR", "rs123", 0.9d)
        );
        legacyTree.set(
                "exomiserCandidateFeatures",
                exomiserTree.get("exomiserCandidateFeatures")
        );
        jdbc.update(
                "UPDATE variant_analysis_interpretation "
                        + "SET payload_json = ?, canonical_variant_id = NULL WHERE analysis_id = ?",
                objectMapper().writeValueAsString(legacyTree),
                ANALYSIS_ID
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> repository.findInterpretationSummaries(
                        ANALYSIS_ID,
                        0,
                        10,
                        VariantInterpretationView.CANDIDATES,
                        VariantInterpretationFilter.empty()
                )
        );
        assertTrue(causeMessageContains(
                failure,
                "EXOMISER_FEATURE_IDENTITY_SOURCE_MISMATCH"
        ));
    }

    @Test
    void legacyShapedSummaryDoesNotHideAnyAnalysisVcfCallDeserializationFailure() throws Exception {
        DataSource dataSource = dataSource();
        createInterpretationTable(dataSource);
        JdbcVariantAnalysisJobRepository repository = repository(dataSource);
        VariantInterpretation legacy = new VariantInterpretation(
                CandidateIdentity.legacy("legacy-v1-0000000000", CANONICAL_ID),
                candidate(1, "AUTOSOMAL_DOMINANT", 1, 0.4d).getObservation(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        legacy.applyRanking(
                "REVIEW",
                0.4d,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        repository.replaceInterpretations(ANALYSIS_ID, Collections.singletonList(legacy));

        JdbcTemplate jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(dataSource));
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM variant_analysis_interpretation WHERE analysis_id = ?",
                String.class,
                ANALYSIS_ID
        );
        ObjectNode legacyTree = (ObjectNode) objectMapper().readTree(payload);
        ObjectNode callTree = objectMapper().valueToTree(analysisVcfCall(40, 1));
        callTree.put("schemaVersion", "unsupported");
        legacyTree.set("analysisVcfCall", callTree);
        jdbc.update(
                "UPDATE variant_analysis_interpretation "
                        + "SET payload_json = ?, canonical_variant_id = NULL WHERE analysis_id = ?",
                objectMapper().writeValueAsString(legacyTree),
                ANALYSIS_ID
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> repository.findInterpretationSummaries(
                        ANALYSIS_ID,
                        0,
                        10,
                        VariantInterpretationView.CANDIDATES,
                        VariantInterpretationFilter.empty()
                )
        );
        assertTrue(causeMessageContains(failure, "ANALYSIS_VCF_CALL_SCHEMA_INVALID"));

        callTree = objectMapper().valueToTree(analysisVcfCall(40, 1));
        callTree.put("matchStatus", "FUTURE_STATUS");
        legacyTree.set("analysisVcfCall", callTree);
        jdbc.update(
                "UPDATE variant_analysis_interpretation SET payload_json = ? WHERE analysis_id = ?",
                objectMapper().writeValueAsString(legacyTree),
                ANALYSIS_ID
        );
        IllegalStateException unknownEnum = assertThrows(
                IllegalStateException.class,
                () -> repository.findInterpretationSummaries(
                        ANALYSIS_ID,
                        0,
                        10,
                        VariantInterpretationView.CANDIDATES,
                        VariantInterpretationFilter.empty()
                )
        );
        assertEquals("Could not deserialize variant interpretation", unknownEnum.getMessage());

        callTree = objectMapper().valueToTree(analysisVcfCall(40, 1));
        callTree.put("depth", "not-an-integer");
        legacyTree.set("analysisVcfCall", callTree);
        jdbc.update(
                "UPDATE variant_analysis_interpretation SET payload_json = ? WHERE analysis_id = ?",
                objectMapper().writeValueAsString(legacyTree),
                ANALYSIS_ID
        );
        IllegalStateException invalidType = assertThrows(
                IllegalStateException.class,
                () -> repository.findInterpretationSummaries(
                        ANALYSIS_ID,
                        0,
                        10,
                        VariantInterpretationView.CANDIDATES,
                        VariantInterpretationFilter.empty()
                )
        );
        assertEquals("Could not deserialize variant interpretation", invalidType.getMessage());
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:candidate_round_trip_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
    }

    private void createInterpretationTable(DataSource dataSource) {
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).execute(
                "CREATE TABLE variant_analysis_interpretation ("
                        + "analysis_id VARCHAR(36) NOT NULL, "
                        + "variant_order INT NOT NULL, "
                        + "candidate_id VARCHAR(128) NOT NULL, "
                        + "canonical_variant_id VARCHAR(1024) NULL, "
                        + "variant_id VARCHAR(1024) NOT NULL, "
                        + "priority_tier VARCHAR(32) NOT NULL, "
                        + "ranking_score DOUBLE NOT NULL, "
                        + "genome_assembly VARCHAR(16) NULL, "
                        + "chromosome VARCHAR(64) NULL, "
                        + "variant_position INT NULL, "
                        + "reference_allele TEXT NULL, "
                        + "alternate_allele TEXT NULL, "
                        + "sample_id VARCHAR(255) NULL, "
                        + "genotype VARCHAR(64) NULL, "
                        + "filter_status VARCHAR(255) NULL, "
                        + "variant_type VARCHAR(32) NULL, "
                        + "consequences_json TEXT NULL, "
                        + "cadd_score DOUBLE NULL, "
                        + "alpha_missense_score DOUBLE NULL, "
                        + "alpha_genome_score DOUBLE NULL, "
                        + "carried_alt BOOLEAN NOT NULL DEFAULT TRUE, "
                        + "analysis_bucket VARCHAR(64) NULL, "
                        + "summary_only BOOLEAN NOT NULL DEFAULT FALSE, "
                        + "large_file_mode BOOLEAN NOT NULL DEFAULT FALSE, "
                        + "expensive_annotation_skipped BOOLEAN NOT NULL DEFAULT FALSE, "
                        + "expensive_annotation_skipped_reason TEXT NULL, "
                        + "cheap_gate_reason_text TEXT NULL, "
                        + "full_interpretation_available BOOLEAN NOT NULL DEFAULT TRUE, "
                        + "payload_json LONGTEXT NOT NULL, "
                        + "PRIMARY KEY (analysis_id, variant_order), "
                        + "UNIQUE (analysis_id, candidate_id))"
        );
    }

    private JdbcVariantAnalysisJobRepository repository(DataSource dataSource) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPersistence().setInterpretationBatchSize(10);
        properties.getPersistence().setRetryAttempts(1);
        properties.getPersistence().setRetryBackoff(Duration.ZERO);
        properties.getPersistence().setPersistSummaryOnlyInterpretations(true);
        return new JdbcVariantAnalysisJobRepository(
                new NamedParameterJdbcTemplate(java.util.Objects.requireNonNull(dataSource)),
                new DataSourceTransactionManager(java.util.Objects.requireNonNull(dataSource)),
                properties,
                objectMapper()
        );
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private boolean causeMessageContains(Throwable failure, String expected) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private VariantInterpretation candidate(int sourceOrdinal,
                                            String modeOfInheritance,
                                            int declaredRank,
                                            double score) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "T"),
                "sample",
                "0/1",
                30,
                99,
                "15,15",
                0.5d,
                "PASS",
                "SNV",
                null
        );
        VariantInterpretation candidate = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        CANONICAL_ID,
                        sourceOrdinal,
                        "GENE1",
                        modeOfInheritance,
                        declaredRank
                ),
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
        candidate.applyRanking(
                "REVIEW",
                score,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        return candidate;
    }

    private VariantInterpretation searchableCandidate(int sourceOrdinal,
                                                       String geneSymbol,
                                                       String modeOfInheritance,
                                                       String sourceRecordId,
                                                       double score) {
        VariantInterpretation base = candidate(
                sourceOrdinal, modeOfInheritance, sourceOrdinal, score
        );
        ExomiserCandidateFeatures features = new ExomiserCandidateFeatures(
                null,
                "15.1.0",
                geneSymbol,
                null,
                modeOfInheritance,
                "AD".equals(modeOfInheritance)
                        ? "AUTOSOMAL_DOMINANT"
                        : "AUTOSOMAL_RECESSIVE",
                sourceOrdinal,
                null,
                sourceRecordId,
                score,
                null,
                null,
                null,
                "missense_variant",
                null,
                true,
                "result.variants.tsv",
                "result.genes.tsv",
                sourceOrdinal,
                sourceOrdinal,
                "EXACT_RANK_GENE_MOI",
                "EXOMISER_VARIANT_SCORE",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonMap("geneSymbol", "VARIANT_TSV:GENE_SYMBOL")
        );
        VariantInterpretation candidate = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        CANONICAL_ID,
                        sourceOrdinal,
                        geneSymbol,
                        modeOfInheritance,
                        sourceOrdinal
                ),
                base.getObservation(),
                Collections.emptyList(),
                Collections.emptyList(),
                features
        );
        candidate.applyRanking(
                "REVIEW",
                score,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        return candidate;
    }

    private AnalysisVcfCall analysisVcfCall(int depth, int recordOrdinal) {
        return AnalysisVcfCall.matched(
                AnalysisVcfCall.TargetAlleleCall.HET_TARGET,
                "REF/TARGET_ALT",
                1,
                depth,
                91,
                12,
                8,
                0.4d,
                AnalysisVcfCall.FilterEvaluation.PASS,
                AnalysisVcfCall.PhasingStatus.UNPHASED,
                null,
                recordOrdinal,
                GenomeAssembly.GRCh38,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private void assertCallAndLegacyObservation(VariantInterpretation candidate,
                                                int expectedDepth,
                                                int expectedRecordOrdinal) {
        assertNotNull(candidate.getAnalysisVcfCall());
        assertEquals(AnalysisVcfCall.MatchStatus.MATCHED,
                candidate.getAnalysisVcfCall().getMatchStatus());
        assertEquals(expectedDepth, candidate.getAnalysisVcfCall().getDepth());
        assertEquals(expectedRecordOrdinal,
                candidate.getAnalysisVcfCall().getMatchedRecordOrdinal());
        assertEquals("REF/TARGET_ALT",
                candidate.getAnalysisVcfCall().getCandidateRelativeGenotype());
        assertEquals("0/1", candidate.getObservation().getGenotype());
    }

    private void assertLegacyObservationDetails(VariantInterpretation candidate) {
        assertEquals(30, candidate.getObservation().getDepth());
        assertEquals("15,15", candidate.getObservation().getAlleleDepths());
    }

    private List<String> candidateIds(List<VariantInterpretation> values) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<String>();
        for (VariantInterpretation value : values) {
            ids.add(value.getCandidateId());
        }
        return ids;
    }

    private void assertCandidate(VariantInterpretation candidate,
                                 String modeOfInheritance,
                                 int sourceOrdinal) {
        assertEquals(CANONICAL_ID, candidate.getCanonicalVariantId());
        assertEquals(CANONICAL_ID, candidate.getVariantId());
        assertEquals("GENE1", candidate.getCandidateIdentity().getGeneSymbol());
        assertEquals(modeOfInheritance, candidate.getCandidateIdentity().getModeOfInheritance());
        assertEquals(sourceOrdinal, candidate.getCandidateIdentity().getSourceOrdinal());
        assertEquals(sourceOrdinal, candidate.getCandidateIdentity().getDeclaredRank());
    }
}
