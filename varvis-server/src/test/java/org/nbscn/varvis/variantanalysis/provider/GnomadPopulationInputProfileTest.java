package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GnomadPopulationInputProfileTest {

    @Test
    void loadsVersionedInputOnlyProfileAndBindsItsPathFreeResourceIdentity() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();

        assertEquals("GNOMAD_POPULATION_INPUT_V1", profile.getProfileId());
        assertEquals("1.0.0", profile.getProfileVersion());
        assertEquals("EXOMES", profile.getDatasetType());
        assertEquals("108243d789af1e71162a7407c61bdc79a1668df826033b36378e8bf232cc4bac",
                profile.getProfileSha256());
        assertFalse(profile.getDocument().has("profileSha256"));
        assertTrue(profile.getRequiredInfoFieldIds().containsAll(List.of(
                "AC", "AN", "AF", "nhomalt", "grpmax", "AC_grpmax", "AN_grpmax",
                "AF_grpmax", "nhomalt_grpmax", "faf95", "fafmax_faf95_max",
                "fafmax_faf95_max_gen_anc"
        )));
        assertTrue(profile.getInfoFieldIds().containsAll(List.of(
                "AC_afr", "AN_afr", "AF_afr", "nhomalt_afr", "faf95_afr",
                "AC_nfe", "AN_nfe", "AF_nfe", "nhomalt_nfe", "faf95_nfe"
        )));

        ObjectMapper mapper = new ObjectMapper();
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(
                mapper, new RuntimeEvidenceV2Adapter(mapper));
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                profile.getDocument(), profile.getFullSitesResourceId());
        assertEquals("GNOMAD", binding.getProvider());
        assertEquals("GRCh38", binding.getAssembly());
        assertEquals("gnomAD exomes v4.1 GRCh38", binding.getResourceVersion());

        String serialized = profile.getDocument().path("resources").get(0)
                .path("identity").toString();
        assertFalse(serialized.matches("(?is).*[A-Z]:[\\\\/].*"));
    }

    @Test
    void preservesRawDecimalLexemesAndSelectsTheMatchedAlternateExactly() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        Map<String, String> info = completeInfoForTwoAlternates();

        GnomadPopulationInputProfile.Result result = profile.parse("PASS", info, 1, 2);

        assertEquals("PASS", result.getInputQualityStatus());
        assertEquals("READY", result.getInputReadinessStatus());
        assertTrue(result.getReasonCodes().isEmpty());
        assertMeasurement(result, "gnomad.exomes.af", "0.0200", "0.02");
        assertMeasurement(result, "gnomad.exomes.faf95", "2.5000e-2", "0.025");
        assertMeasurement(result, "gnomad.exomes.ac", "2", "2");
        assertMeasurement(result, "gnomad.exomes.ancestry.nfe.an", "100", "100");
        assertMeasurement(result, "gnomad.recordInputReadinessStatus", "READY", "READY");
        assertMeasurement(result, "gnomad.jointOrCombinedAvailable", "false", "false");
        assertMeasurement(result, "gnomad.criterionEvaluationEnabled", "false", "false");
        assertMeasurement(result, "gnomad.generalCriterionDatasetReadinessStatus",
                "NOT_READY", "NOT_READY");
        assertMeasurement(result, "gnomad.generalCriterionDatasetReadinessReasonCodes",
                "JOINT_OR_COMBINED_DATASET_UNAVAILABLE,ALL_SITES_AN_UNAVAILABLE",
                "JOINT_OR_COMBINED_DATASET_UNAVAILABLE,ALL_SITES_AN_UNAVAILABLE");
        assertEquals("READY", result.getRecordInputReadinessStatus());
        assertFalse(result.getMeasurements().toString().contains("NOT_MET"));
    }

    @Test
    void keepsAHitButRejectsNonPassFilterAsRecordInput() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();

        GnomadPopulationInputProfile.Result result = profile.parse(
                "AS_VQSR", completeInfoForTwoAlternates(), 0, 2);

        assertEquals("FAIL", result.getInputQualityStatus());
        assertEquals("NOT_READY", result.getInputReadinessStatus());
        assertEquals(List.of("FILTER_NOT_PASS"), result.getReasonCodes());
        assertMeasurement(result, "gnomad.recordInputReadinessReasonCodes",
                "FILTER_NOT_PASS", "FILTER_NOT_PASS");
    }

    @Test
    void rejectsMissingFieldsCardinalityErrorsAndInconsistentCountsWithoutCriteria() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        Map<String, String> info = completeInfoForTwoAlternates();
        info.remove("AF");
        info.put("AC", "111,2");
        info.put("faf95", "0.01");

        GnomadPopulationInputProfile.Result result = profile.parse("PASS", info, 0, 2);

        assertEquals("PASS", result.getInputQualityStatus());
        assertEquals("NOT_READY", result.getInputReadinessStatus());
        assertTrue(result.getReasonCodes().contains("REQUIRED_INFO_FIELD_MISSING"));
        assertTrue(result.getReasonCodes().contains("ALLELE_CARDINALITY_MISMATCH"));
        assertTrue(result.getReasonCodes().contains("COUNT_INCONSISTENT"));
        assertFalse(result.getReasonCodes().contains("MET"));
        assertFalse(result.getReasonCodes().contains("NOT_MET"));
    }

    @Test
    void rejectsAZeroGroupmaxAlleleNumberAndAnOutOfRangeAltIndex() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        Map<String, String> info = completeInfoForTwoAlternates();
        info.put("AN_grpmax", "0,100");

        GnomadPopulationInputProfile.Result zeroAn = profile.parse("PASS", info, 0, 2);
        assertEquals("NOT_READY", zeroAn.getInputReadinessStatus());
        assertTrue(zeroAn.getReasonCodes().contains("ALLELE_NUMBER_BELOW_PROFILE_MINIMUM"));
        assertTrue(zeroAn.getReasonCodes().contains("COUNT_INCONSISTENT"));

        GnomadPopulationInputProfile.Result badIndex = profile.parse("PASS", info, 2, 2);
        assertEquals("NOT_READY", badIndex.getInputReadinessStatus());
        assertEquals(List.of("ALT_INDEX_OUT_OF_RANGE"), badIndex.getReasonCodes());
    }

    @Test
    void rejectsSelfHashAndTamperedResourceIdentity() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode selfHashed = profile.getDocument();
        selfHashed.put("profileSha256", profile.getProfileSha256());
        assertThrows(IllegalArgumentException.class,
                () -> GnomadPopulationInputProfile.fromDocument(mapper, selfHashed));

        ObjectNode tampered = profile.getDocument();
        ((ObjectNode) tampered.path("resources").get(0).path("identity"))
                .put("dataTotalBytes", 1);
        assertThrows(IllegalArgumentException.class,
                () -> GnomadPopulationInputProfile.fromDocument(mapper, tampered));
    }

    @Test
    void loadsThePinnedExecutableV2ProfileWithoutEnablingPatientOrClassificationFlows() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();

        assertEquals("GNOMAD_POPULATION_INPUT_V2", profile.getProfileId());
        assertEquals("2.0.0", profile.getProfileVersion());
        assertEquals("JOINT", profile.getDatasetType());
        assertEquals("626320cc24bb480adb4d34c97646326c3ca358c5c68b8282eb7edf177731a0a3",
                profile.getProfileSha256());
        assertEquals("GNOMAD_JOINT_V4_1_GRCH38_SITES", profile.getPopulationResourceId());
        assertEquals("c7ab8fc9194cc8474c29fbc493cb6ce05c4958264d00c9faa40a6d569bc2ef2f",
                profile.getPopulationResourceIdentityFingerprintSha256());
        assertEquals(profile.getPopulationResourceId(), profile.getFullSitesResourceId());
        assertEquals(profile.getPopulationResourceIdentityFingerprintSha256(),
                profile.getFullSitesResourceIdentityFingerprintSha256());
        assertTrue(profile.isCriterionEvaluationEnabled());
        assertTrue(profile.getDocument().path("activation").path("runtimeBound").asBoolean());
        assertTrue(profile.getDocument().path("activation").path("evaluationEnabled").asBoolean());
        assertFalse(profile.getDocument().path("activation").path("patientFlowEnabled").asBoolean());
        assertFalse(profile.getDocument().path("activation").path("classificationEnabled").asBoolean());
        ObjectNode successor = profile.getSuccessorRegistryDocument();
        successor.put("registryId", "tampered");
        assertEquals("acmg-rule-registry-e3-p1a-2026-09-02",
                profile.getSuccessorRegistryDocument().path("registryId").asText());
        assertEquals(
                "https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/joint/"
                        + "gnomad.joint.v4.1.sites.chr1.vcf.bgz",
                profile.getRemoteJointUri("1"));
        assertEquals(
                "https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/joint/"
                        + "gnomad.joint.v4.1.sites.chrX.vcf.bgz.tbi",
                profile.getRemoteJointIndexUri("X"));
        assertThrows(IllegalArgumentException.class, () -> profile.getRemoteJointUri("chr1"));
        assertThrows(IllegalArgumentException.class, () -> profile.getRemoteJointUri("x"));
        assertThrows(IllegalArgumentException.class, () -> profile.getRemoteJointUri("../1"));
        assertThrows(IllegalStateException.class,
                () -> GnomadPopulationInputProfile.loadDefault().getRemoteJointUri("1"));
        assertThrows(IllegalStateException.class,
                () -> GnomadPopulationInputProfile.loadDefault().getSuccessorRegistryDocument());
    }

    @Test
    void loadsThePinnedPm2SuccessorWithSeparateAllSitesResourcesAndNoProductionPolicy() {
        GnomadPopulationInputProfile profile =
                GnomadPopulationInputProfile.loadExecutablePm2Supporting();
        ObjectNode document = profile.getDocument();

        assertEquals("GNOMAD_POPULATION_INPUT_V2_PM2_SUPPORTING", profile.getProfileId());
        assertEquals("2.1.0", profile.getProfileVersion());
        assertEquals("221386bf48b12cc3d8671697b04ce27b0b933e2ec921e5c57040b5813df69ec5",
                profile.getProfileSha256());
        assertEquals("JOINT_WITH_SEPARATE_ALL_SITES_AN", profile.getDatasetType());
        assertEquals(3, document.path("resources").size());
        assertFalse(document.path("activation").path("patientFlowEnabled").asBoolean());
        assertFalse(document.path("activation").path("classificationEnabled").asBoolean());
        JsonNode pm2 = document.path("evaluation").path("criteria").get(2)
                .path("populationRule");
        assertTrue(pm2.path("policySnapshot").path("records").isEmpty());
        assertEquals("acmg-rule-registry-e3-p1b-2026-09-02",
                profile.getSuccessorRegistryDocument().path("registryId").asText());
    }

    @Test
    void freezesBa1AndBs1RulesWithoutAStandaloneBs1Default() {
        ObjectNode document = GnomadPopulationInputProfile.loadExecutableV2().getDocument();
        RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(new ObjectMapper());
        assertEquals("acmg-rule-registry-v1",
                document.path("lineage").path("baseRuleRegistrySchemaVersion").asText());
        assertEquals("1865ae07534d90244df4d9f77f94d824befbe88e31b6e138611f425c7e19a557",
                document.path("lineage").path("baseRuleRegistrySha256").asText());
        assertEquals("acmg-rule-registry-v2",
                document.path("registryBinding").path("registrySchemaVersion").asText());
        assertEquals("gnomad.joint.ancestry.{ancestry}.ac", document
                .path("criterionInputGates")
                .path("selectedAncestryAlleleCountMetricTemplate").asText());
        JsonNode ba1 = document.path("evaluation").path("criteria").get(0)
                .path("frequencyRule");
        assertEquals("gnomad.joint.faf95max", ba1.path("metricId").asText());
        assertEquals("GREATER_THAN", ba1.path("comparisonOperator").asText());
        assertEquals("0.05", ba1.path("threshold").path("rawValue").asText());
        assertEquals("2000",
                ba1.path("minimumSelectedAncestryAlleleNumber").path("rawValue").asText());
        assertEquals("GREATER_THAN_OR_EQUAL",
                ba1.path("minimumSelectedAncestryAlleleNumber")
                        .path("comparisonOperator").asText());
        JsonNode exceptions = ba1.path("exceptionPolicy");
        assertEquals("READY", exceptions.path("snapshotStatus").asText());
        assertEquals(9, exceptions.path("entryCount").asInt());
        assertEquals(9, exceptions.path("entries").size());
        assertEquals("27624dccfc9719cfe5361296b96fbb64df2edaa7703b815d1b29c7c796383c0d",
                exceptions.path("entrySetSha256").asText());
        assertEquals(exceptions.path("entrySetSha256").asText(),
                adapter.canonicalSha256(exceptions.path("entries")));
        assertEquals("2018-07-30", exceptions.path("snapshotDate").asText());
        assertTrue(exceptions.path("sourceUrl").asText().startsWith("https://clinicalgenome.org/"));
        assertEquals("EXACT_ASSEMBLY_CONTIG_POSITION_REF_ALT",
                exceptions.path("matchMode").asText());
        assertEquals(Set.of("NOT_HIT", "HIT", "UNKNOWN", "AMBIGUOUS"),
                textSet(exceptions.path("allowedOutcomes")));

        JsonNode bs1 = document.path("evaluation").path("criteria").get(1)
                .path("frequencyRule");
        assertEquals(List.of("gene.hgncId", "disease.mondoId", "moi"),
                textValues(bs1.path("threshold").path("selectionKeys")));
        assertTrue(bs1.path("threshold").path("noDefaultThreshold").asBoolean());
        assertEquals("NOT_EVALUATED", bs1.path("threshold")
                .path("missingAmbiguousOrUnversionedResult").asText());
        assertEquals("1ea515f01f73542b5e3759cec06660ee9b942148413a5a4d905a609b484d11fa",
                bs1.path("thresholdSnapshot").path("sha256").asText());
        assertTrue(bs1.path("thresholdSnapshot").path("records").isEmpty());
        ObjectNode bs1SnapshotProjection = ((ObjectNode) bs1.path("thresholdSnapshot")).deepCopy();
        bs1SnapshotProjection.remove("sha256");
        assertEquals(bs1.path("thresholdSnapshot").path("sha256").asText(),
                adapter.canonicalSha256(bs1SnapshotProjection));
    }

    @Test
    void parsesJointFieldsAsExactRawBigNumbersAndKeepsDiscordanceAsQcOnly() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();

        GnomadPopulationInputProfile.Result result = profile.parse(
                "PASS", completeJointInfoForTwoAlternates(), 1, 2);

        assertEquals("PASS", result.getInputQualityStatus());
        assertEquals("READY", result.getInputReadinessStatus());
        assertTrue(result.getReasonCodes().isEmpty());
        assertMeasurement(result, "gnomad.exactAlleleMatch", "true", "true",
                "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertMeasurement(result, "gnomad.joint.filter", "PASS", "PASS",
                "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertMeasurement(result, "gnomad.joint.faf95max", "5.1000e-2", "0.051",
                "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertMeasurement(result, "gnomad.joint.faf95max.ancestry", "nfe", "nfe",
                "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertMeasurement(result, "gnomad.joint.ancestry.nfe.an", "2500", "2500",
                "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertMeasurement(result, "gnomad.joint.qc.statUnionPValue", "9e-5", "0.00009",
                "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertMeasurement(result, "gnomad.joint.qc.statUnionGeneticAncestries",
                "afr,nfe", "afr,nfe", "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertMeasurement(result, "gnomad.criterionEvaluationEnabled", "true", "true",
                "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertMeasurement(result, "gnomad.generalCriterionDatasetReadinessStatus",
                "READY_FOR_HIT_BASED_BA1_BS1", "READY_FOR_HIT_BASED_BA1_BS1",
                "GNOMAD_POPULATION_INPUT_V2@2.0.0");
        assertFalse(result.getMeasurements().toString().contains("MET"));
        assertFalse(result.getMeasurements().toString().contains("NOT_MET"));
    }

    @Test
    void validatesCountsForTheFafSelectedAncestryIndependentlyOfGroupmax() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
        Map<String, String> missing = completeJointInfoForTwoAlternates();
        missing.remove("AC_joint_nfe");

        GnomadPopulationInputProfile.Result missingResult = profile.parse("PASS", missing, 1, 2);

        assertEquals("NOT_READY", missingResult.getInputReadinessStatus());
        assertTrue(missingResult.getReasonCodes().contains("FAF95_ANCESTRY_FIELDS_MISSING"));

        Map<String, String> inconsistent = completeJointInfoForTwoAlternates();
        inconsistent.put("AC_joint_nfe", "0,2501");
        GnomadPopulationInputProfile.Result inconsistentResult =
                profile.parse("PASS", inconsistent, 1, 2);
        assertEquals("NOT_READY", inconsistentResult.getInputReadinessStatus());
        assertTrue(inconsistentResult.getReasonCodes().contains("COUNT_INCONSISTENT"));
    }

    @Test
    void rejectsExecutableProfileActivationAuthorityUriAndRuleTampering() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode activation = profile.getDocument();
        ((ObjectNode) activation.path("activation")).put("patientFlowEnabled", true);
        assertThrows(IllegalArgumentException.class,
                () -> GnomadPopulationInputProfile.fromDocument(mapper, activation));

        ObjectNode uri = profile.getDocument();
        ((ObjectNode) uri.path("resources").get(0).path("identity"))
                .put("dataUriTemplate", "https://example.invalid/chr{chromosome}.vcf.bgz");
        assertThrows(IllegalArgumentException.class,
                () -> GnomadPopulationInputProfile.fromDocument(mapper, uri));

        ObjectNode exception = profile.getDocument();
        ((ObjectNode) exception.path("evaluation").path("criteria").get(0)
                .path("frequencyRule").path("exceptionPolicy").path("entries").get(0))
                .put("alternate", "T");
        assertThrows(IllegalArgumentException.class,
                () -> GnomadPopulationInputProfile.fromDocument(mapper, exception));

        ObjectNode bs1 = profile.getDocument();
        ((ObjectNode) bs1.path("evaluation").path("criteria").get(1)
                .path("frequencyRule").path("threshold"))
                .put("noDefaultThreshold", false);
        assertThrows(IllegalArgumentException.class,
                () -> GnomadPopulationInputProfile.fromDocument(mapper, bs1));
    }

    @Test
    void successorRegistryIsCompleteAndOnlyBa1Bs1HaveV2ImplementationStatus() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode registry;
        try (InputStream input = getClass().getResourceAsStream(
                "/acmg/rule-registry-v2/acmg-rule-registry-e3-p1a-2026-09-02.json")) {
            registry = mapper.readTree(input);
        }
        assertEquals("acmg-rule-registry-v2", registry.path("schemaVersion").asText());
        assertEquals("acmg-rule-registry-e3-p1a-2026-09-02",
                registry.path("registryId").asText());
        assertEquals("acmg-rule-registry-general-2026-08-31",
                registry.path("lineage").path("baseRegistryId").asText());
        assertEquals("acmg-rule-registry-v1",
                registry.path("lineage").path("baseSchemaVersion").asText());
        assertEquals("1865ae07534d90244df4d9f77f94d824befbe88e31b6e138611f425c7e19a557",
                registry.path("lineage").path("baseRegistrySha256").asText());
        assertEquals(28, registry.path("criteria").size());
        Set<String> ids = new LinkedHashSet<String>();
        Set<String> v2Implemented = new LinkedHashSet<String>();
        for (JsonNode criterion : registry.path("criteria")) {
            assertTrue(ids.add(criterion.path("criterionId").asText()));
            if ("V2_IMPLEMENTED".equals(criterion.path("implementationStatus").asText())) {
                v2Implemented.add(criterion.path("criterionId").asText());
            }
        }
        assertEquals(28, ids.size());
        assertEquals(Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1"), v2Implemented);
    }

    private Map<String, String> completeInfoForTwoAlternates() {
        Map<String, String> info = new LinkedHashMap<String, String>();
        info.put("AC", "1,2");
        info.put("AN", "110");
        info.put("AF", "9.09091e-3,0.0200");
        info.put("nhomalt", "0,1");
        info.put("grpmax", "afr,nfe");
        info.put("AC_grpmax", "1,2");
        info.put("AN_grpmax", "102,100");
        info.put("AF_grpmax", "0.00980392,0.0200");
        info.put("nhomalt_grpmax", "0,1");
        info.put("faf95", "0.001,2.5000e-2");
        info.put("fafmax_faf95_max", "0.002,0.0250");
        info.put("fafmax_faf95_max_gen_anc", "afr,nfe");
        info.put("AC_afr", "1,0");
        info.put("AN_afr", "102");
        info.put("AF_afr", "0.00980392,0");
        info.put("nhomalt_afr", "0,0");
        info.put("faf95_afr", "0.002,0");
        info.put("AC_nfe", "0,2");
        info.put("AN_nfe", "100");
        info.put("AF_nfe", "0,0.0200");
        info.put("nhomalt_nfe", "0,1");
        info.put("faf95_nfe", "0,2.5000e-2");
        return info;
    }

    private Map<String, String> completeJointInfoForTwoAlternates() {
        Map<String, String> info = new LinkedHashMap<String, String>();
        info.put("AC_joint", "1,110");
        info.put("AN_joint", "5000");
        info.put("AF_joint", "0.0002,0.0220");
        info.put("nhomalt_joint", "0,1");
        info.put("grpmax_joint", "afr,afr");
        info.put("AC_grpmax_joint", "1,110");
        info.put("AN_grpmax_joint", "2500,2500");
        info.put("AF_grpmax_joint", "0.000416667,0.0440");
        info.put("nhomalt_grpmax_joint", "0,1");
        info.put("faf95_joint", "0.001,0.0500");
        info.put("fafmax_faf95_max_joint", "0.002,5.1000e-2");
        info.put("fafmax_faf95_max_gen_anc_joint", "afr,nfe");
        info.put("AC_joint_afr", "1,110");
        info.put("AN_joint_afr", "2500");
        info.put("AF_joint_afr", "0.0004,0.0440");
        info.put("nhomalt_joint_afr", "0,1");
        info.put("faf95_joint_afr", "0.002,0.0490");
        info.put("AC_joint_nfe", "0,110");
        info.put("AN_joint_nfe", "2500");
        info.put("AF_joint_nfe", "0,0.0440");
        info.put("nhomalt_joint_nfe", "0,1");
        info.put("faf95_joint_nfe", "0,0.0500");
        info.put("exomes_filters", "AS_VQSR,InbreedingCoeff");
        info.put("genomes_filters", "PASS");
        info.put("stat_union_p_value", "0.1,9e-5");
        info.put("stat_union_test_name", "fisher,cmh");
        info.put("stat_union_gen_ancs", "afr,nfe");
        return info;
    }

    private void assertMeasurement(GnomadPopulationInputProfile.Result result,
                                   String metricId,
                                   String expectedRaw,
                                   String expectedNormalized) {
        Map<String, String> measurement = result.getMeasurements().stream()
                .filter(value -> metricId.equals(value.get("metricId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing measurement " + metricId));
        assertEquals(expectedRaw, measurement.get("rawValue"));
        assertEquals(expectedNormalized, measurement.get("normalizedValue"));
        assertEquals("GNOMAD_POPULATION_INPUT_V1@1.0.0",
                measurement.get("calibrationProfileId"));
    }

    private void assertMeasurement(GnomadPopulationInputProfile.Result result,
                                   String metricId,
                                   String expectedRaw,
                                   String expectedNormalized,
                                   String expectedCalibrationProfileId) {
        Map<String, String> measurement = result.getMeasurements().stream()
                .filter(value -> metricId.equals(value.get("metricId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing measurement " + metricId));
        assertEquals(expectedRaw, measurement.get("rawValue"));
        assertEquals(expectedNormalized, measurement.get("normalizedValue"));
        assertEquals(expectedCalibrationProfileId, measurement.get("calibrationProfileId"));
    }

    private List<String> textValues(JsonNode values) {
        List<String> result = new java.util.ArrayList<String>();
        for (JsonNode value : values) result.add(value.asText());
        return result;
    }

    private Set<String> textSet(JsonNode values) {
        return new LinkedHashSet<String>(textValues(values));
    }
}
