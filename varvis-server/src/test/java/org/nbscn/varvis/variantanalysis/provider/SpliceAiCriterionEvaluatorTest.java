package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpliceAiCriterionEvaluatorTest {

    private static final String SELECTION_ID = "cspec-selection-sha256-"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String APPLICABLE_CONSEQUENCE =
            "intron_variant&splice_region_variant";

    private ObjectMapper mapper;
    private RuntimeEvidenceV2Adapter adapter;
    private SpliceAiCriterionEvaluator evaluator;
    private ObjectNode profile;
    private ObjectNode registry;
    private ObjectNode bp7Profile;
    private ObjectNode bp7Registry;
    private ObjectNode context;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        evaluator = new SpliceAiCriterionEvaluator(mapper);
        profile = SpliceAiCriterionEvaluator.loadProfile(mapper);
        registry = SpliceAiCriterionEvaluator.loadRuleRegistry(mapper);
        bp7Profile = SpliceAiCriterionEvaluator.loadBp7Profile(mapper);
        bp7Registry = SpliceAiCriterionEvaluator.loadBp7RuleRegistry(mapper);
        try (InputStream input = getClass()
                .getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            context = (ObjectNode) mapper.readTree(input).path("context").deepCopy();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "0.00,NOT_MET,MET",
            "0.10,NOT_MET,MET",
            "0.11,NOT_MET,NOT_MET",
            "0.19,NOT_MET,NOT_MET",
            "0.20,MET,NOT_MET",
            "0.99,MET,NOT_MET"
    })
    void appliesOnlyTheFrozenSupportingBoundaries(String score,
                                                   String pp3Status,
                                                   String bp4Status) {
        ObjectNode atomic = atomic(hitCarrier(score, contextGene(), true, true));

        List<ObjectNode> evaluations = evaluate(atomic, APPLICABLE_CONSEQUENCE);
        ObjectNode pp3 = criterion(evaluations, "ACMG_AMP_2015:PP3");
        ObjectNode bp4 = criterion(evaluations, "ACMG_AMP_2015:BP4");

        assertEvaluation(pp3, pp3Status);
        assertEvaluation(bp4, bp4Status);
        assertFalse("MET".equals(pp3Status) && "MET".equals(bp4Status));
        assertTrue(evaluations.stream()
                .filter(value -> "MET".equals(value.path("evaluationResult")
                        .path("status").asText()))
                .count() <= 1);
        assertEquals(score, parameter(pp3, "score.raw").path("value").asText());
        assertEquals(SpliceAiCriterionEvaluator.CALIBRATION_PROFILE_ID,
                measurement(atomic, "spliceai.maximumRawDeltaScore")
                        .path("calibrationProfileId").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "splice_region_variant",
            "intron_variant",
            "synonymous_variant",
            "intron_variant&splice_region_variant"
    })
    void onlyTheFrozenSelectedTranscriptTermsAreApplicable(String consequence) {
        ObjectNode atomic = atomic(hitCarrier("0.20", contextGene(), true, true));

        List<ObjectNode> evaluations = evaluate(atomic, consequence);

        assertEquals("MET", criterion(evaluations, "ACMG_AMP_2015:PP3")
                .path("evaluationResult").path("status").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missense_variant",
            "splice_donor_variant",
            "splice_acceptor_variant",
            "missense_variant&splice_region_variant",
            "stop_gained"
    })
    void missenseCanonicalSpliceAndUnlistedTermsAreNotApplicable(String consequence) {
        ObjectNode atomic = atomic(hitCarrier("0.99", contextGene(), true, true));

        assertNotEvaluated(evaluate(atomic, consequence),
                "SPLICEAI_PROFILE_NOT_APPLICABLE", "NOT_APPLICABLE");
    }

    @Test
    void geneAndTranscriptMustMatchTheEvidenceContext() {
        ObjectNode geneMismatch = atomic(hitCarrier("0.20", "OTHER", false, true));
        assertNotEvaluated(evaluate(geneMismatch, APPLICABLE_CONSEQUENCE),
                "GENE_CONTEXT_MISMATCH", "UNKNOWN");

        ObjectNode transcriptMismatchCarrier = hitCarrier("0.20", contextGene(), true, true);
        ObjectNode transcript = measurement(transcriptMismatchCarrier,
                "spliceai.selectedTranscriptId");
        transcript.put("rawValue", "NM_999999.1").put("normalizedValue", "NM_999999.1");
        assertNotEvaluated(evaluate(atomic(transcriptMismatchCarrier), APPLICABLE_CONSEQUENCE),
                "SELECTED_TRANSCRIPT_CONTEXT_MISMATCH", "UNKNOWN");
    }

    @Test
    void exactAlleleAndCalculatedMaximumMustBeConfirmed() {
        ObjectNode alleleMismatch = atomic(hitCarrier("0.20", contextGene(), true, false));
        assertNotEvaluated(evaluate(alleleMismatch, APPLICABLE_CONSEQUENCE),
                "EXACT_ALLELE_NOT_CONFIRMED", "UNKNOWN");

        ObjectNode inconsistent = hitCarrier("0.20", contextGene(), true, true);
        ObjectNode maximum = measurement(inconsistent, "spliceai.maximumRawDeltaScore");
        maximum.put("rawValue", "0.19").put("normalizedValue", "0.19");
        assertNotEvaluated(evaluate(atomic(inconsistent), APPLICABLE_CONSEQUENCE),
                "SPLICEAI_MAXIMUM_SCORE_MISMATCH", "UNKNOWN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_RECORD", "FAILED", "TIMEOUT"})
    void absenceFailureAndTimeoutCannotBecomeComputationalEvidence(String status) {
        ObjectNode atomic = atomic(failedCarrier(status));
        String reason = switch (status) {
            case "NO_RECORD" -> "SPLICEAI_NO_RECORD";
            case "FAILED" -> "RESOURCE_EXECUTION_FAILED";
            case "TIMEOUT" -> "RESOURCE_EXECUTION_TIMEOUT";
            default -> throw new IllegalArgumentException(status);
        };

        assertNotEvaluated(evaluate(atomic, APPLICABLE_CONSEQUENCE),
                reason, "UNKNOWN");
    }

    @Test
    void executableProfileRetainsCandidateLineageAndBindsExistingRegistry() {
        assertEquals("SPLICEAI_RAW_GRCH38_4999_V1_3_1_CANDIDATE",
                profile.path("lineage").path("candidateProfileId").asText());
        assertEquals("ae3f4079aac95a96ea60a0c5b7fa039673027acc422ac139c3144bfa0704663d",
                profile.path("lineage").path("candidateProfileCanonicalSha256").asText());
        assertTrue(profile.path("activation").path("runtimeBound").asBoolean());
        assertTrue(profile.path("activation").path("evaluationEnabled").asBoolean());
        assertFalse(profile.path("activation").path("patientFlowEnabled").asBoolean());
        assertFalse(profile.path("activation").path("classificationEnabled").asBoolean());
        assertEquals("acmg-rule-registry-e3-c1-2026-09-02",
                profile.path("registryBinding").path("registryId").asText());

        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding =
                new RuntimeEvidenceV2ProviderWiring(mapper, adapter).bind(profile,
                        "SPLICEAI_V1_3_1_RAW_GRCH38_LOCAL_COMPUTE");
        assertEquals("SPLICEAI", binding.getProvider());
        assertEquals("GRCh38", binding.getAssembly());
        assertEquals("e2f652e52955a0c6aaed434a3424cb69bb29413908d7dba8aaded016aa537542",
                binding.getIdentityFingerprintSha256());
    }

    @ParameterizedTest
    @CsvSource({
            "true,3,NOT_APPLICABLE",
            "false,0,NOT_APPLICABLE",
            "false,1,NOT_APPLICABLE",
            "false,2,NOT_APPLICABLE",
            "false,3,MET",
            "false,8,MET"
    })
    void bp7AppliesTheSynonymousExonBoundaries(boolean firstBase,
                                                int distanceFromEnd,
                                                String expected) {
        ObjectNode splice = atomic(hitCarrier("0.10", contextGene(), true, true));
        ObjectNode bp4 = criterion(evaluate(splice, "synonymous_variant"),
                "ACMG_AMP_2015:BP4");
        ObjectNode vep = vepAtomic("synonymous_variant", firstBase,
                distanceFromEnd, null);

        ObjectNode bp7 = evaluateBp7(splice, vep, bp4);

        assertBp7(bp7, expected);
    }

    @ParameterizedTest
    @CsvSource({
            "6,NOT_APPLICABLE",
            "7,MET",
            "25,MET",
            "-20,NOT_APPLICABLE",
            "-21,MET",
            "-100,MET"
    })
    void bp7AppliesTheSignedIntronicBoundaries(int offset, String expected) {
        ObjectNode splice = atomic(hitCarrier("0.10", contextGene(), true, true));
        ObjectNode bp4 = criterion(evaluate(splice, "intron_variant"),
                "ACMG_AMP_2015:BP4");
        ObjectNode vep = vepAtomic("intron_variant", null, null, offset);

        assertBp7(evaluateBp7(splice, vep, bp4), expected);
    }

    @Test
    void eligiblePositionUsesTheExistingBp4StatusWithoutTreatingBp7AsAnotherVote() {
        ObjectNode splice = atomic(hitCarrier("0.11", contextGene(), true, true));
        ObjectNode bp4 = criterion(evaluate(splice, "synonymous_variant"),
                "ACMG_AMP_2015:BP4");
        ObjectNode vep = vepAtomic("synonymous_variant", false, 3, null);

        ObjectNode bp7 = evaluateBp7(splice, vep, bp4);

        assertBp7(bp7, "NOT_MET");
        assertEquals("NOT_MET", bp4.path("evaluationResult").path("status").asText());
    }

    @Test
    void bp4AndBp7MayBothBeMetFromTheSameSpliceAiEvidence() {
        ObjectNode splice = atomic(hitCarrier("0.10", contextGene(), true, true));
        ObjectNode bp4 = criterion(evaluate(splice, "synonymous_variant"),
                "ACMG_AMP_2015:BP4");
        ObjectNode vep = vepAtomic("synonymous_variant", false, 3, null);

        ObjectNode bp7 = evaluateBp7(splice, vep, bp4);

        assertEquals("MET", bp4.path("evaluationResult").path("status").asText());
        assertEquals("MET", bp7.path("evaluationResult").path("status").asText());
        assertEquals(2, bp7.path("evidenceIds").size());
    }

    @Test
    void missingPositionAndUnevaluatedBp4CannotProduceBp7Met() {
        ObjectNode splice = atomic(hitCarrier("0.10", contextGene(), true, true));
        ObjectNode bp4 = criterion(evaluate(splice, "synonymous_variant"),
                "ACMG_AMP_2015:BP4");
        ObjectNode missingPosition = vepAtomic("synonymous_variant", null, null, null);
        assertBp7Reason(evaluateBp7(splice, missingPosition, bp4),
                "TRANSCRIPT_RELATIVE_POSITION_MISSING", "UNKNOWN");

        ObjectNode unevaluated = bp4.deepCopy();
        ((ObjectNode) unevaluated.path("evaluationResult")).removeAll()
                .put("status", "NOT_EVALUATED").put("reasonCode", "SCORE_MISSING");
        ObjectNode eligible = vepAtomic("synonymous_variant", false, 3, null);
        assertBp7Reason(evaluateBp7(splice, eligible, unevaluated),
                "BP4_NOT_EVALUATED", "UNKNOWN");
        assertBp7Reason(evaluateBp7(splice, eligible, null),
                "SAME_CONTEXT_BP4_EVALUATION_MISSING", "UNKNOWN");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missense_variant",
            "splice_donor_variant",
            "splice_acceptor_variant",
            "stop_gained"
    })
    void bp7RejectsMissenseCanonicalSpliceAndOtherConsequences(String consequence) {
        ObjectNode splice = atomic(hitCarrier("0.10", contextGene(), true, true));
        ObjectNode bp4 = criterion(evaluate(splice, "synonymous_variant"),
                "ACMG_AMP_2015:BP4");
        ObjectNode vep = vepAtomic(consequence, false, 3, null);

        assertBp7Reason(evaluateBp7(splice, vep, bp4),
                "BP7_CONSEQUENCE_NOT_APPLICABLE", "NOT_APPLICABLE");
    }

    @Test
    void bp7RequiresTheSameContextAndSpliceAiEvidenceAsBp4() {
        ObjectNode splice = atomic(hitCarrier("0.10", contextGene(), true, true));
        ObjectNode bp4 = criterion(evaluate(splice, "synonymous_variant"),
                "ACMG_AMP_2015:BP4");
        ((ArrayNode) bp4.path("evidenceIds")).removeAll()
                .add("evidence-sha256-"
                        + "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        ObjectNode vep = vepAtomic("synonymous_variant", false, 3, null);

        assertBp7Reason(evaluateBp7(splice, vep, bp4),
                "SAME_CONTEXT_BP4_BINDING_MISMATCH", "UNKNOWN");
    }

    @Test
    void buildsThePathFreeSyntheticBp7Smoke() throws IOException {
        ObjectNode splice = atomic(hitCarrier("0.10", contextGene(), true, true));
        ObjectNode bp4 = criterion(evaluate(splice, "synonymous_variant"),
                "ACMG_AMP_2015:BP4");
        ObjectNode vep = vepAtomic("synonymous_variant", false, 3, null);
        ObjectNode bp7 = evaluateBp7(splice, vep, bp4);
        assertBp7(bp7, "MET");

        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", "e3-c3-bp7-synthetic-smoke-v1");
        report.put("status", "PASS");
        report.put("contextDataClass", "SYNTHETIC");
        report.put("positionDataClass", "SYNTHETIC");
        report.put("clinicalValidation", false);
        report.put("patientFlowEnabled", false);
        report.put("classificationEnabled", false);
        report.put("realSpliceAiCliRerun", false);
        report.putObject("profile")
                .put("profileId", SpliceAiCriterionEvaluator.BP7_PROFILE_ID)
                .put("profileVersion", SpliceAiCriterionEvaluator.BP7_PROFILE_VERSION);
        report.set("context", context.deepCopy());
        ObjectNode evidence = report.putObject("atomicEvidence");
        evidence.set("spliceAi", splice.deepCopy());
        evidence.set("vepPosition", vep.deepCopy());
        report.set("sameContextBp4Evaluation", bp4.deepCopy());
        report.set("bp7Evaluation", bp7.deepCopy());
        report.putArray("limitations")
                .add("Synthetic position fixture proves rule engineering, not clinical validation")
                .add("Standard VEP output does not directly supply synonymous exon-boundary distance");
        String serialized = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(report) + System.lineSeparator();
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));

        String configured = System.getProperty("varvis.e3c3.smoke-output");
        if (configured != null && !configured.isBlank()) {
            Path output = Path.of(configured).toAbsolutePath().normalize();
            Path target = Path.of("target").toAbsolutePath().normalize();
            if (!output.startsWith(target)) {
                throw new IllegalArgumentException(
                        "varvis.e3c3.smoke-output must remain below varvis-server/target");
            }
            Files.createDirectories(output.getParent());
            Files.writeString(output, serialized, StandardCharsets.UTF_8);
        }
    }

    private List<ObjectNode> evaluate(ObjectNode atomic, String consequence) {
        return evaluator.evaluate(context, atomic, consequence, profile, registry, SELECTION_ID);
    }

    private ObjectNode evaluateBp7(ObjectNode splice,
                                   ObjectNode vep,
                                   ObjectNode bp4) {
        return evaluator.evaluateBp7(context, splice, vep, bp4,
                bp7Profile, bp7Registry, SELECTION_ID);
    }

    private ObjectNode atomic(ObjectNode carrier) {
        return adapter.adapt(context, carrier);
    }

    private ObjectNode hitCarrier(String maximum,
                                  String gene,
                                  boolean geneMatch,
                                  boolean exactAllele) {
        ObjectNode carrier = baseCarrier();
        addMeasurement(carrier, "spliceai.exactAlleleMatch", exactAllele,
                exactAllele, "boolean");
        addMeasurement(carrier, "spliceai.geneSymbol", gene, gene, "gene-symbol");
        addMeasurement(carrier, "spliceai.geneContextMatch", geneMatch,
                geneMatch, "boolean");
        addMeasurement(carrier, "spliceai.selectedTranscriptId",
                selectedTranscript(), selectedTranscript(), "transcript-id");
        addDecimalMeasurement(carrier, "spliceai.DS_AG", maximum);
        addDecimalMeasurement(carrier, "spliceai.DS_AL", "0.00");
        addDecimalMeasurement(carrier, "spliceai.DS_DG", "0.00");
        addDecimalMeasurement(carrier, "spliceai.DS_DL", "0.00");
        addMeasurement(carrier, "spliceai.DP_AG", "1", "1", "base-offset");
        addMeasurement(carrier, "spliceai.DP_AL", "2", "2", "base-offset");
        addMeasurement(carrier, "spliceai.DP_DG", "-3", "-3", "base-offset");
        addMeasurement(carrier, "spliceai.DP_DL", "4", "4", "base-offset");
        addDecimalMeasurement(carrier, "spliceai.maximumRawDeltaScore", maximum);
        return carrier;
    }

    private ObjectNode baseCarrier() {
        JsonNode resource = profile.path("resources").path(0);
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", "COMPUTATIONAL_PREDICTION");
        carrier.set("queryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source");
        source.put("sourceId", resource.path("resourceId").asText());
        source.put("resourceVersion", resource.path("resourceVersion").asText());
        source.put("resourceSha256", resource.path("identityFingerprintSha256").asText());
        source.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "DATABASE_RECORD");
        locator.put("locatorValue", "SPLICEAI:GRCh38:1:100:A:G:" + contextGene());
        locator.put("sourceRecordId", "SPLICEAI:1:100:A:G:" + contextGene());
        locator.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode record = mapper.createObjectNode();
        record.put("recordId", locator.path("sourceRecordId").asText());
        record.set("locator", locator.deepCopy());
        source.putArray("sourceRecords").add(record);
        source.putArray("sourceLocators").add(locator);
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED")
                .put("attemptedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        ObjectNode queryProjection = mapper.createObjectNode();
        queryProjection.put("resourceId", resource.path("resourceId").asText());
        queryProjection.set("queryAllele", carrier.path("queryAllele").deepCopy());
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                .put("queryFingerprint", adapter.canonicalSha256(queryProjection));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putArray("measurements");
        carrier.put("producedAt", "2026-09-02T00:00:00Z");
        carrier.put("sourcePayloadSha256",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return carrier;
    }

    private ObjectNode failedCarrier(String status) {
        ObjectNode carrier = baseCarrier();
        ((ArrayNode) carrier.path("source").path("sourceRecords")).removeAll();
        ObjectNode query = (ObjectNode) carrier.path("queryOutcome");
        query.put("recordCount", 0);
        if ("NO_RECORD".equals(status)) {
            query.put("status", "NO_RECORD");
            return carrier;
        }
        query.removeAll();
        query.put("status", "NOT_QUERIED").put("recordCount", 0);
        ObjectNode execution = (ObjectNode) carrier.path("resourceExecution");
        execution.removeAll();
        execution.put("status", status).put("reasonCode", "SPLICEAI_BATCH_" + status)
                .put("attemptedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z");
        ObjectNode availability = (ObjectNode) carrier.path("resourceAvailability");
        availability.removeAll();
        availability.put("status", "UNKNOWN").put("reasonCode", "NOT_CHECKED");
        ObjectNode applicability = (ObjectNode) carrier.path("resourceApplicability");
        applicability.removeAll();
        applicability.put("status", "UNKNOWN").put("reasonCode", "NOT_EVALUATED");
        return carrier;
    }

    private ObjectNode vepAtomic(String consequence,
                                 Boolean firstBase,
                                 Integer distanceFromEnd,
                                 Integer intronicOffset) {
        JsonNode resource = bp7Profile.path("bp7PositionRules").path("resourceBinding");
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", "VARIANT_CONSEQUENCE");
        carrier.set("queryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source");
        source.put("sourceId", resource.path("resourceId").asText());
        source.put("resourceVersion", resource.path("resourceVersion").asText());
        source.put("resourceSha256", resource.path("identityFingerprintSha256").asText());
        source.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "DATABASE_RECORD");
        locator.put("locatorValue", "VEP:GRCh38:1:100:A:G:" + selectedTranscript());
        locator.put("sourceRecordId", "VEP:1:100:A:G:" + selectedTranscript());
        locator.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode record = mapper.createObjectNode();
        record.put("recordId", locator.path("sourceRecordId").asText());
        record.set("locator", locator.deepCopy());
        source.putArray("sourceRecords").add(record);
        source.putArray("sourceLocators").add(locator);
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED")
                .put("attemptedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        ObjectNode queryProjection = mapper.createObjectNode();
        queryProjection.put("resourceId", resource.path("resourceId").asText());
        queryProjection.set("queryAllele", carrier.path("queryAllele").deepCopy());
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                .put("queryFingerprint", adapter.canonicalSha256(queryProjection));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putArray("measurements");
        addVepMeasurement(carrier, "vep.selectedTranscriptId", selectedTranscript(),
                "transcript-id");
        addVepMeasurement(carrier, "consequenceTerm", consequence, null);
        if (firstBase != null) {
            addVepMeasurement(carrier, "vep.synonymousAtExonFirstBase",
                    firstBase.toString(), "boolean");
        }
        if (distanceFromEnd != null) {
            addVepMeasurement(carrier, "vep.synonymousDistanceFromExonEnd",
                    distanceFromEnd.toString(), "base-offset");
        }
        if (intronicOffset != null) {
            addVepMeasurement(carrier, "vep.intronicSignedOffset",
                    intronicOffset.toString(), "base-offset");
        }
        carrier.put("producedAt", "2026-09-02T00:00:00Z");
        carrier.put("sourcePayloadSha256",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        return adapter.adapt(context, carrier);
    }

    private void addVepMeasurement(ObjectNode carrier,
                                   String metricId,
                                   String raw,
                                   String unit) {
        ObjectNode value = ((ArrayNode) carrier.path("measurements")).addObject();
        value.put("metricId", metricId).put("rawValue", raw);
        if (unit != null) value.put("unit", unit);
    }

    private void addDecimalMeasurement(ObjectNode carrier, String metricId, String raw) {
        BigDecimal value = new BigDecimal(raw);
        String normalized = value.stripTrailingZeros().signum() == 0
                ? "0" : value.stripTrailingZeros().toPlainString();
        addMeasurement(carrier, metricId, raw, normalized, "score");
    }

    private void addMeasurement(ObjectNode carrier,
                                String metricId,
                                Object raw,
                                Object normalized,
                                String unit) {
        ObjectNode value = ((ArrayNode) carrier.path("measurements")).addObject();
        value.put("metricId", metricId);
        if (raw instanceof Boolean) {
            value.put("rawValue", (Boolean) raw);
            value.put("normalizedValue", (Boolean) normalized);
        } else {
            value.put("rawValue", raw.toString());
            value.put("normalizedValue", normalized.toString());
        }
        value.put("unit", unit);
        value.put("calibrationProfileId",
                SpliceAiCriterionEvaluator.CALIBRATION_PROFILE_ID);
    }

    private ObjectNode measurement(ObjectNode parent, String metricId) {
        for (JsonNode value : parent.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                return (ObjectNode) value;
            }
        }
        throw new AssertionError("measurement not found: " + metricId);
    }

    private ObjectNode criterion(List<ObjectNode> evaluations, String criterionId) {
        return evaluations.stream()
                .filter(value -> criterionId.equals(value.path("criterionId").asText()))
                .findFirst().orElseThrow();
    }

    private ObjectNode parameter(ObjectNode evaluation, String name) {
        for (JsonNode value : evaluation.path("deterministicTrace").path("parameters")) {
            if (name.equals(value.path("name").asText())) {
                return (ObjectNode) value;
            }
        }
        throw new AssertionError("parameter not found: " + name);
    }

    private String contextGene() {
        return context.path("gene").path("symbol").asText();
    }

    private String selectedTranscript() {
        return context.path("transcript").path("accession").asText()
                + "." + context.path("transcript").path("version").asInt();
    }

    private void assertEvaluation(ObjectNode evaluation, String expectedStatus) {
        assertEquals(expectedStatus,
                evaluation.path("evaluationResult").path("status").asText());
        assertEquals("SUPPORTING", evaluation.path("baseStrength").asText());
        assertEquals("MET".equals(expectedStatus) ? "SUPPORTING" : "NONE",
                evaluation.path("appliedStrength").asText());
        assertEquals("COMPLETED",
                evaluation.path("deterministicTrace").path("executionStatus").asText());
    }

    private void assertNotEvaluated(List<ObjectNode> evaluations,
                                    String reason,
                                    String applicability) {
        assertEquals(2, evaluations.size());
        for (ObjectNode evaluation : evaluations) {
            assertEquals(applicability,
                    evaluation.path("criterionApplicability").path("status").asText());
            assertEquals(reason,
                    evaluation.path("criterionApplicability").path("reasonCode").asText());
            assertEquals("NOT_EVALUATED",
                    evaluation.path("evaluationResult").path("status").asText());
            assertEquals(reason,
                    evaluation.path("evaluationResult").path("reasonCode").asText());
            assertEquals("NONE", evaluation.path("appliedStrength").asText());
            assertEquals("NOT_RUN",
                    evaluation.path("deterministicTrace").path("executionStatus").asText());
        }
    }

    private void assertBp7(ObjectNode evaluation, String expected) {
        if ("NOT_APPLICABLE".equals(expected)) {
            assertEquals("NOT_APPLICABLE",
                    evaluation.path("criterionApplicability").path("status").asText());
            assertEquals("NOT_EVALUATED",
                    evaluation.path("evaluationResult").path("status").asText());
            return;
        }
        assertEquals("APPLICABLE",
                evaluation.path("criterionApplicability").path("status").asText());
        assertEquals(expected,
                evaluation.path("evaluationResult").path("status").asText());
        assertEquals("MET".equals(expected) ? "SUPPORTING" : "NONE",
                evaluation.path("appliedStrength").asText());
        assertEquals("COMPLETED",
                evaluation.path("deterministicTrace").path("executionStatus").asText());
    }

    private void assertBp7Reason(ObjectNode evaluation,
                                 String reason,
                                 String applicability) {
        assertEquals(applicability,
                evaluation.path("criterionApplicability").path("status").asText());
        assertEquals(reason,
                evaluation.path("criterionApplicability").path("reasonCode").asText());
        assertEquals("NOT_EVALUATED",
                evaluation.path("evaluationResult").path("status").asText());
        assertEquals(reason,
                evaluation.path("evaluationResult").path("reasonCode").asText());
    }
}
