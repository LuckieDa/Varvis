package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Project-scope parser/comparator fixtures. No fixture impersonates external scientific approval. */
class Pm2ProjectLocalAdoptionTest {
    @TempDir Path temporary;
    final Pm2LocalExomesTest fixture = new Pm2LocalExomesTest();

    ObjectNode release() {
        fixture.temporary = temporary;
        var release = Pm2LocalExomesProfile.load(fixture.mapper).adoptionDraft();
        release.put("purpose", "PROJECT_LOCAL_EXOMES_HIT");
        release.set("projectScope", Pm2LocalExomesProfile.projectLocalScope(fixture.mapper));
        for (var row : release.path("records")) {
            var review = ((ObjectNode) row).withObject("/review");
            review.put("status", "ACCEPTED_FOR_PROJECT_LOCAL_USE").put("reviewId", "TEST_ONLY")
                    .put("reviewedBy", "TEST_FIXTURE_NOT_AN_APPROVAL").put("reviewedAt", "2026-10-10T00:00:00Z")
                    .put("rationale", "Synthetic parser fixture for the explicit project-local contract only.");
            for (String key : List.of("sourceScope", "datasetSemantics", "statisticSemantics", "qualityRequirements", "additionalConditions"))
                review.withObject("/checks").put(key, "CHECKED_FOR_PROJECT_LOCAL_SCOPE");
        }
        return release;
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 9, 10, 11})
    void sixExistingRulesUseExactBoundariesAndRecordLocalInterpretation(int index) throws Exception {
        var profile = fixture.load(release()); var c = fixture.context(index);
        var selection = profile.select(c); var policy = selection.path("policy");
        assertEquals("PROJECT_LOCAL_EXOMES_ONLY", selection.path("adoptionScope").asText());
        for (boolean satisfies : List.of(true, false)) {
            String frequency = "0.000001", ac = satisfies ? "1" : "2", an = "2000";
            if (policy.has("maximumAlleleFrequency")) {
                frequency = new BigDecimal(policy.at("/maximumAlleleFrequency/rawValue").asText())
                        .add(satisfies ? BigDecimal.ZERO : new BigDecimal("0.00000001")).toPlainString();
                ac = "100"; an = "10000";
            }
            var result = new GnomadPopulationCriterionEvaluator(fixture.mapper).evaluatePm2LocalExomes(c,
                    fixture.observation(c, fixture.info(ac, an, frequency), "PASS"), profile, Pm2LocalExomesTest.SOURCE);
            assertEquals(satisfies ? "MET" : "NOT_MET", result.at("/evaluationResult/status").asText());
            assertEquals(satisfies ? "SUPPORTING" : "NONE", result.path("appliedStrength").asText());
            assertTrue(result.at("/deterministicTrace/parameters").toString().contains("LOCAL_DATASET_CRITERION_ONLY"));
        }
        assertEquals("NOT_ESTABLISHED", profile.document().path("clinicalAdoption").asText());
        c.put("dataClass", "PRIVACY_BOUNDED_RUNTIME"); c.put("contextId", fixture.adapter.canonicalVersionedContextId(c));
        assertEquals("APPLICABLE", profile.select(c).path("status").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"scope", "clinical", "whole", "dataset", "absence", "singleton", "hom", "status", "check", "purpose"})
    void projectContractCannotBeRelabeledAsWholeDatabaseOrExternalApproval(String fault) {
        var release = release(); var scope = release.withObject("/projectScope");
        var first = (ObjectNode) release.path("records").get(0);
        switch (fault) {
            case "scope" -> release.remove("projectScope");
            case "clinical" -> scope.put("clinicalAdoption", "APPROVED");
            case "whole" -> scope.put("wholeGnomadConclusion", true);
            case "dataset" -> scope.put("dataset", "JOINT");
            case "absence" -> scope.put("allowNoRecord", true);
            case "singleton" -> scope.put("allowMissingFafSingleton", true);
            case "hom" -> scope.put("allowWholeDatabaseHomozygoteRequirement", true);
            case "status" -> first.withObject("/review").put("status", "APPROVED_FOR_LOCAL_EXOMES_HIT");
            case "check" -> first.withObject("/review/checks").put("datasetSemantics", "APPROVED");
            case "purpose" -> release.put("purpose", "REVIEWED_LOCAL_EXOMES_HIT");
        }
        assertThrows(IllegalArgumentException.class, () -> fixture.load(release));
    }

    @Test void projectScopeKeepsFullDatabaseNoFafAndAbsenceGates() throws Exception {
        var profile = fixture.load(release());
        assertEquals(6, profile.document().at("/counts/APPLICABLE").asInt());
        assertEquals(Pm2LocalExomesProfile.WHOLE_DATABASE_GAP, profile.select(fixture.context(3)).path("reasonCode").asText());
        var c = fixture.context(0); var values = fixture.info("1", "2000", "0.000001");
        values.keySet().removeIf(key -> key.contains("faf"));
        var evaluator = new GnomadPopulationCriterionEvaluator(fixture.mapper);
        assertEquals("PM2_LOCAL_NO_FAF_SCOPE_UNSUPPORTED", evaluator.evaluatePm2LocalExomes(c,
                fixture.observation(c, values, "PASS"), profile, Pm2LocalExomesTest.SOURCE).at("/evaluationResult/reasonCode").asText());
        var carrier = fixture.facts.facts.baseCarrier(c);
        carrier.withObject("/source").putArray("sourceRecords");
        carrier.withObject("/queryOutcome").put("status", "NO_RECORD").put("recordCount", 0);
        var observation = PopulationObservation.seal(fixture.mapper, carrier, "", profile.factProfile(),
                Pm2LocalExomesTest.SOURCE, "SYNTHETIC", "TEST_ONLY");
        assertEquals("PM2_LOCAL_ALL_SITES_DETECTION_PROOF_MISSING", evaluator.evaluatePm2LocalExomes(c, observation,
                profile, Pm2LocalExomesTest.SOURCE).at("/evaluationResult/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"true", "1", "false", "."})
    void intervalQualityFlagCannotBeIgnoredEvenWhenFilterPasses(String flag) throws Exception {
        var profile = fixture.load(release()); var c = fixture.context(1);
        var values = fixture.info("2", "1000000", "0.0000003");
        values.put("fail_interval_qc", flag);
        var result = new GnomadPopulationCriterionEvaluator(fixture.mapper).evaluatePm2LocalExomes(c,
                fixture.observation(c, values, "PASS"), profile, Pm2LocalExomesTest.SOURCE);
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertEquals("PM2_LOCAL_INTERVAL_QC_FAILED", result.at("/evaluationResult/reasonCode").asText());
        assertEquals("NONE", result.path("appliedStrength").asText());
    }

    @Test void publicProjectionPreservesScopeAndUnambiguousReason() {
        var full = fixture.mapper.createObjectNode();
        var row = full.putArray("results").addObject().putObject("localBatch").putArray("criteria").addObject();
        row.put("criterionId", "ACMG_AMP_2015:PM2").put("status", "MET").put("appliedStrength", "SUPPORTING")
                .put("reasonCode", "PM2_PROJECT_LOCAL_HIT_MET");
        row.putObject("details").set("populationScope", Pm2LocalExomesProfile.projectLocalScope(fixture.mapper));
        var projected = LocalEvidenceBatchRuntime.publicView(fixture.mapper, full).at("/results/0/localBatch/criteria/0");
        assertEquals(row.at("/details/populationScope"), projected.at("/details/populationScope"));
        assertTrue(projected.path("reason").asText().contains("仅限 gnomAD 4.1 EXOMES"));
    }
}
