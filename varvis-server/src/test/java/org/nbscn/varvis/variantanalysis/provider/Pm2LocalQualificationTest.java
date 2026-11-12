package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Approval-shaped fixtures exercise validation; they do not grant scientific adoption. */
class Pm2LocalQualificationTest {
    @TempDir Path temporary;
    final Pm2LocalExomesTest fixture = new Pm2LocalExomesTest();

    ObjectNode release(int index) {
        fixture.temporary = temporary;
        var release = fixture.adoption().put("purpose", "REVIEWED_LOCAL_EXOMES_HIT");
        var source = Pm2LocalExomesProfile.load(fixture.mapper).document()
                .at("/policyInventory/" + index + "/sourcePolicy");
        var row = (ObjectNode) release.path("records").get(0);
        row.put("sourcePolicyId", source.path("policyId").asText())
                .put("sourcePolicySha256", fixture.adapter.canonicalSha256(source))
                .put("authoritySnapshotSha256", source.at("/authority/snapshotSha256").asText());
        row.withObject("/review/sourceRef").put("url", source.at("/authority/sourceUrl").asText())
                .put("sha256", source.at("/authority/snapshotSha256").asText());
        return release;
    }

    @ParameterizedTest @ValueSource(ints = {3, 4, 5, 6, 7, 8})
    void approvalLabelsCannotReplaceWholeDatabaseHomozygoteEvidence(int index) throws Exception {
        var error = assertThrows(IllegalArgumentException.class, () -> fixture.load(release(index)));
        assertEquals("PM2_LOCAL_WHOLE_DATABASE_HOMOZYGOTE_PROOF_MISSING", error.getMessage());
    }

    @Test void defaultSelectionNamesTheActualWholeDatabaseDataGap() throws Exception {
        var profile = Pm2LocalExomesProfile.load(fixture.mapper);
        var selection = profile.select(fixture.context(3));
        assertEquals("PM2_LOCAL_WHOLE_DATABASE_HOMOZYGOTE_PROOF_MISSING", selection.path("reasonCode").asText());
    }

    @Test void localSingletonCannotInheritWholeGnomadNoFafPermission() throws Exception {
        var profile = fixture.load(release(0)); var context = fixture.context(0);
        var facts = fixture.info("1", "2000", "0.000001");
        facts.keySet().removeIf(key -> key.contains("faf"));
        var result = new GnomadPopulationCriterionEvaluator(fixture.mapper).evaluatePm2LocalExomes(context,
                fixture.observation(context, facts, "PASS"), profile, Pm2LocalExomesTest.SOURCE);
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("PM2_LOCAL_NO_FAF_SCOPE_UNSUPPORTED", result.at("/evaluationResult/reasonCode").asText());
    }

    @Test void draftIsBoundToUnchangedSourcesAndNeverLoadableAsApproval() throws Exception {
        fixture.temporary = temporary;
        var profile = Pm2LocalExomesProfile.load(fixture.mapper);
        var before = profile.document(); var draft = profile.adoptionDraft();
        assertEquals(12, before.path("policyInventory").size());
        assertEquals(6, before.at("/counts/LOCAL_DATA_SCOPE_BLOCKED").asInt());
        assertEquals(6, draft.path("records").size());
        assertEquals(0, before.at("/counts/APPLICABLE").asInt());
        for (var row : draft.path("records")) {
            assertEquals("UNREVIEWED", row.at("/review/status").asText());
            assertEquals("", row.at("/review/reviewedBy").asText());
            var old = before.path("policyInventory").findValues("sourcePolicy").stream()
                    .filter(p -> p.path("policyId").equals(row.path("sourcePolicyId"))).findFirst().orElseThrow();
            assertFalse(old.has("maximumHomozygoteCount"));
            assertEquals(fixture.adapter.canonicalSha256(old), row.path("sourcePolicySha256").asText());
        }
        assertThrows(IllegalArgumentException.class, () -> fixture.load(draft));
        draft.put("purpose", "REVIEWED_LOCAL_EXOMES_HIT");
        assertThrows(IllegalArgumentException.class, () -> fixture.load(draft));
        draft.withArray("records").removeAll();
        assertEquals(before, profile.document());
        assertEquals(6, profile.adoptionDraft().path("records").size());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 9, 10, 11})
    void eligibleShapesKeepTheirOriginalThresholdsButRequireExplicitReview(int index) throws Exception {
        var context = fixture.context(index);
        assertEquals("PM2_LOCAL_EXOMES_POLICY_REVIEW_REQUIRED",
                Pm2LocalExomesProfile.load(fixture.mapper).select(context).path("reasonCode").asText());
        var loaded = fixture.load(release(index)); var policy = loaded.select(context).path("policy");
        var old = Pm2LocalExomesProfile.load(fixture.mapper).document().at("/policyInventory/" + index + "/sourcePolicy");
        if (old.has("maximumAlleleCount")) {
            assertEquals(old.path("maximumAlleleCount"), policy.path("maximumAlleleCount"));
            assertEquals(old.path("minimumObservedAlleleNumber"), policy.path("minimumObservedAlleleNumber"));
        } else {
            assertEquals(old.at("/maximumAlleleFrequency/rawValue"), policy.at("/maximumAlleleFrequency/rawValue"));
            assertEquals(old.at("/maximumAlleleFrequency/comparisonOperator"), policy.at("/maximumAlleleFrequency/comparisonOperator"));
        }
        assertFalse(policy.path("allowWellCoveredAbsence").asBoolean());
        assertFalse(policy.has("missingFafPolicy"));
        assertEquals("REVIEWED_LOCAL_SCOPE_ONLY", policy.at("/localQualification/status").asText());
    }

    @Test void qualifiedScopeAndDataGapSurviveFullJsonRoundTrip() throws Exception {
        var context = fixture.context(3); var profile = Pm2LocalExomesProfile.load(fixture.mapper);
        var observation = fixture.observation(context, fixture.info("100", "10000", "0.000001"), "PASS");
        var result = new GnomadPopulationCriterionEvaluator(fixture.mapper).evaluatePm2LocalExomes(context,
                observation, profile, Pm2LocalExomesTest.SOURCE);
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertEquals("NONE", result.path("appliedStrength").asText());
        assertEquals(Pm2LocalExomesProfile.WHOLE_DATABASE_GAP, result.at("/evaluationResult/reasonCode").asText());
        var selected = profile.select(context);
        assertEquals(selected, fixture.mapper.readTree(fixture.mapper.writeValueAsBytes(selected)));
        assertEquals(result, fixture.mapper.readTree(fixture.mapper.writeValueAsBytes(result)));
        assertTrue(selected.at("/localQualification/explanation").asText().contains("整个 gnomAD"));
    }

    @Test void allSitesRestoresOnlyAnAbsenceBranchAlreadyPresentInTheExactAdoptedSource() throws Exception {
        var absence = new Pm2LocalAbsenceTest();
        for (int index : new int[]{0, 9}) {
            var context = fixture.context(index);
            var profile = fixture.load(release(index)).withLocalAbsence(absence.binding(), true);
            var selected = profile.select(context).path("policy");
            assertEquals(index == 9, selected.path("allowWellCoveredAbsence").asBoolean());
            assertEquals(index == 9, selected.at("/localQualification/absenceSupported").asBoolean());
            assertNotEquals("GENERAL_ABSENCE_ONLY", selected.path("route").asText());
            var result = new GnomadPopulationCriterionEvaluator(fixture.mapper).evaluatePm2LocalExomes(context,
                    absence.absent(context), profile, Pm2LocalExomesTest.SOURCE, absence.detection(context, "2000", false));
            assertEquals(index == 9 ? "MET" : "NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result.toPrettyString());
            assertEquals(0, profile.document().at("/counts/MISSING_LOCAL_ALL_SITES").asInt(-1));
            assertEquals(index == 9 ? 1 : 0, profile.document().at("/counts/LOCAL_ABSENCE_POLICIES").asInt(-1));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"PM2_LOCAL_WHOLE_DATABASE_HOMOZYGOTE_PROOF_MISSING",
            "PM2_LOCAL_NO_FAF_SCOPE_UNSUPPORTED", "PM2_LOCAL_ALL_SITES_DETECTION_PROOF_MISSING",
            "PM2_LOCAL_EXOMES_POLICY_REVIEW_REQUIRED"})
    void publicSavedViewExplainsSpecificLocalGaps(String reason) {
        var full = fixture.mapper.createObjectNode();
        var local = full.putArray("results").addObject().putObject("localBatch");
        local.putArray("criteria").addObject().put("criterionId", "ACMG_AMP_2015:PM2")
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", reason);
        var view = LocalEvidenceBatchRuntime.publicView(fixture.mapper, full).at("/results/0/localBatch/criteria/0");
        assertEquals(reason, view.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED", view.path("status").asText());
        assertFalse(view.path("reason").asText().contains("现有材料或资格不足"));
        assertFalse(view.path("reason").asText().isBlank());
    }
}
