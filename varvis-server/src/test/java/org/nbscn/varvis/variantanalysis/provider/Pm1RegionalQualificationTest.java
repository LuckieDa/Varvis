package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pm1RegionalQualificationTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void strengthIsComputedFromIndependentComparatorsAndTheDeclaredCalibration() throws Exception {
        var document = catalog();
        var result = assess(document);
        assertEquals("MET", result.path("status").asText(), result::toPrettyString);
        assertEquals("MODERATE", result.path("appliedStrength").asText());
        var region = result.at("/reviewedRegion/assessments/0");
        assertEquals(3, region.path("start").asInt());
        assertEquals(7, region.path("end").asInt());
        assertEquals(3, region.at("/comparisonCounts/pathogenicInside").asInt());
        assertEquals(0, region.at("/comparisonCounts/benignInside").asInt());
        assertEquals(1, region.at("/comparisonCounts/pathogenicOutside").asInt());
        assertEquals(10, region.at("/comparisonCounts/benignOutside").asInt());
        assertEquals("INFINITE", region.at("/enrichment/oddsRatio").asText());
        assertEquals(new BigDecimal(4).divide(new BigDecimal(364), MathContext.DECIMAL128),
                new BigDecimal(region.at("/enrichment/pValue").asText()));
        assertEquals("EXCLUDED_TARGET", result.at("/reviewedRegion/observations/4/role").asText());
        assertEquals("EXCLUDED_UNCERTAIN", result.at("/reviewedRegion/observations/5/role").asText());
        assertEquals("EXCLUDED_DIFFERENT_BACKGROUND", result.at("/reviewedRegion/observations/6/role").asText());
        assertEquals("EXCLUDED_DEPENDENT", result.at("/reviewedRegion/observations/7/role").asText());
        observations(document).remove(0);
        assertEquals("SUPPORTING", assess(document).path("appliedStrength").asText());
        observations(document).remove(0);
        assertEquals("PM1_REGION_ENRICHMENT_BELOW_THRESHOLD", assess(document).path("reasonCode").asText());
    }

    @Test void missingCoverageOrCalibrationAndBenignComparatorsPreventAdoption() throws Exception {
        var document = catalog();
        var candidate = (ObjectNode) document.at("/records/0/regions/0");
        var survey = (ObjectNode) document.at("/records/0/survey");
        var calibration = candidate.remove("calibration");
        var exploratory = assess(document);
        assertEquals("NOT_EVALUATED", exploratory.path("status").asText());
        assertEquals("PM1_REGION_ENRICHMENT_CALIBRATION_REQUIRED", exploratory.path("reasonCode").asText());
        assertTrue(exploratory.at("/reviewedRegion/assessments/0/enrichment").isObject());
        candidate.set("calibration", calibration);
        survey.put("status", "INCOMPLETE");
        assertEquals("PM1_REVIEWED_REGION_BENIGN_SURVEY_INCOMPLETE", assess(document).path("reasonCode").asText());
        survey.put("status", "COMPLETE");
        addObservation(observations(document), 6, "T", "BENIGN", "MATCHED", "INDEPENDENT");
        var negative = assess(document);
        assertEquals("NOT_MET", negative.path("status").asText());
        assertEquals("PM1_REVIEWED_REGION_BENIGN_OBSERVATION", negative.path("reasonCode").asText());
    }

    @Test void duplicateComparatorAndPrefilledRegionalStrengthAreRejected() throws Exception {
        var document = catalog();
        var observations = observations(document);
        observations.add(observations.path(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> assess(document));
        observations.remove(observations.size() - 1);
        ((ObjectNode) document.at("/records/0/regions/0")).put("strength", "MODERATE");
        assertThrows(IllegalArgumentException.class, () -> assess(document));
    }

    @Test void unknownBenignDiseaseBlocksAdoptionWithoutInventingSameDiseaseConflict() throws Exception {
        var document = catalog();
        var uncertain = observations(document).path(5);
        var assertion = ((ArrayNode) uncertain.path("assertions")).addObject()
                .put("assertionId", "unspecified-benign").put("classification", "LIKELY_BENIGN")
                .put("diseaseScope", "UNRESOLVED").put("basis", "UNVERIFIED").put("rationale", "TEST_ONLY unreported disease");
        assertion.putArray("sourceIds").add("test");
        var result = assess(document);
        assertEquals("NOT_EVALUATED", result.path("status").asText());
        assertEquals("UNRESOLVED", result.at("/reviewedRegion/observations/5/role").asText());
        assertEquals(0, result.at("/reviewedRegion/assessments/0/comparisonCounts/conflictingInside").asInt());
        assertEquals(1, result.at("/reviewedRegion/assessments/0/comparisonCounts/unresolvedInside").asInt());
        assertEquals("PM1_REVIEWED_REGION_OBSERVATION_INCOMPLETE", result.path("reasonCode").asText());
        assertion.put("diseaseScope", "MATCHED");
        assertEquals("CONFLICTING", assess(document).at("/reviewedRegion/observations/5/role").asText());
        assertion.put("diseaseScope", "DIFFERENT");
        assertEquals("MET", assess(document).path("status").asText());
    }

    private ObjectNode assess(ObjectNode document) throws Exception {
        var record = document.at("/records/0");
        var scope = record.path("scope");
        var context = mapper.createObjectNode().put("moi", scope.path("moi").asText());
        context.putObject("gene").set("hgncId", scope.path("hgncId"));
        context.putObject("disease").set("mondoId", scope.path("mondoId"));
        var model = mapper.createObjectNode().put("proteinLength", 20).put("cdsSequence", "GCT".repeat(20) + "TAA");
        for (String key : new String[]{"transcript", "protein", "assembly"}) model.set(key, scope.path(key));
        var target = mapper.createObjectNode().put("proteinPosition", 5).put("refAminoAcid", "A").put("altAminoAcid", "V");
        var bytes = mapper.writeValueAsBytes(document);
        var file = Files.write(root.resolve("catalog.json"), bytes);
        var regions = Pm1ReviewedRegions.load(mapper, file, CodingTranscriptMaterialSupply.sha(bytes),
                ignored -> mapper.createObjectNode().put("status", "NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT")
                        .put("catalogSha256", "a".repeat(64)));
        return java.util.Objects.requireNonNull(regions.assess(context, model, target));
    }

    private ObjectNode catalog() {
        var document = mapper.createObjectNode().put("schemaVersion", Pm1ReviewedRegions.QUALIFICATION_SCHEMA);
        var record = document.putArray("records").addObject();
        record.putObject("scope").put("hgncId", "HGNC:999999").put("mondoId", "MONDO:9999999")
                .put("moi", "AUTOSOMAL_DOMINANT").put("assembly", "GRCh38")
                .put("transcript", "NM_012345.1").put("protein", "NP_012345.1");
        record.putArray("sources").addObject().put("sourceId", "test").put("title", "TEST_ONLY material")
                .put("url", "https://example.test/pm1").put("locator", "TEST_ONLY observations");
        record.putArray("reviewedCoverage");
        record.putObject("survey").put("start", 1).put("end", 20).put("status", "COMPLETE")
                .put("dataset", "TEST_ONLY snapshot").putArray("sourceIds").add("test");
        var candidate = record.putArray("regions").addObject().put("regionId", "test-window").put("label", "TEST_ONLY window")
                .put("kind", "MUTATIONAL_HOTSPOT").put("method", "MISSENSE_ENRICHMENT").put("radius", 2)
                .put("rationale", "TEST_ONLY method");
        candidate.putArray("sourceIds").add("test");
        var calibration = candidate.putObject("calibration").put("method", "FISHER_EXACT_ONE_SIDED").put("version", "TEST_ONLY v1");
        calibration.putArray("sourceIds").add("test");
        var levels = calibration.putArray("levels");
        levels.addObject().put("strength", "SUPPORTING").put("minimumIndependentPathogenic", 2)
                .put("minimumOddsRatio", "2").put("maximumPValue", "0.05");
        levels.addObject().put("strength", "MODERATE").put("minimumIndependentPathogenic", 3)
                .put("minimumOddsRatio", "4").put("maximumPValue", "0.02");
        var observations = record.putArray("observations");
        for (int position : new int[]{3, 4, 7, 10, 5})
            addObservation(observations, position, "V", "PATHOGENIC", "MATCHED", "INDEPENDENT");
        addObservation(observations, 6, "L", "UNCERTAIN_SIGNIFICANCE", "MATCHED", "UNVERIFIED");
        addObservation(observations, 6, "G", "BENIGN", "DIFFERENT", "INDEPENDENT");
        addObservation(observations, 6, "Q", "LIKELY_PATHOGENIC", "MATCHED", "DEPENDENT");
        for (int position = 11; position <= 20; position++)
            addObservation(observations, position, "V", "LIKELY_BENIGN", "MATCHED", "INDEPENDENT");
        return document;
    }

    private static ArrayNode observations(ObjectNode document) {
        return (ArrayNode) document.at("/records/0/observations");
    }

    private static void addObservation(ArrayNode observations, int position, String alt, String classification, String scope, String basis) {
        observations.addObject().put("proteinPosition", position).put("refAminoAcid", "A").put("altAminoAcid", alt)
                .putArray("assertions").addObject().put("assertionId", "test-" + position + alt)
                .put("classification", classification).put("diseaseScope", scope).put("basis", basis)
                .put("rationale", "TEST_ONLY observation").putArray("sourceIds").add("test");
    }
}
