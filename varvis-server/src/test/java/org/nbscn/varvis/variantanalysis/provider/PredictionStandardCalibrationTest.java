package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PredictionStandardCalibrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    @TempDir Path temp;
    private Path repo() { return Path.of(System.getProperty("varvis.repo", "..")).toAbsolutePath().normalize(); }
    private byte[] source(String name) throws Exception { return Files.readAllBytes(repo().resolve("docs/execution/pp3-calibration-source-20260918/" + name)); }
    private ObjectNode contract() throws Exception {
        byte[] bytes = Files.readAllBytes(repo().resolve("varvis-server/src/main/resources/acmg/prediction-standard-offline/am-standard-1.0.0.json"));
        return PredictionCalibration.loadStandard(mapper, bytes, "20d9dfe35874fe4b90370fd26ad0259dc9821b87b0084893a7fa79888992b489",
                source("discounted_comparator_tools_w3pts_bootstrap10k_thrs.txt"), source("results-README.txt"));
    }

    @Test void parsesOriginalPairedHeaderAndPreservesUnavailableThresholdsAndDiscrepancy() throws Exception {
        var c = contract(); var row = c.path("sourceThresholds");
        assertEquals(10, row.size()); assertTrue(row.path("PP3_VeryStrong").isNull()); assertTrue(row.path("BP4_Strong").isNull());
        assertEquals("0.070", row.path("BP4_-3").asText()); assertEquals("0.973", row.path("PP3_+3").asText());
        assertEquals("0.972", c.at("/discrepancy/final2025Plus3Lower").asText());
        byte[] bad = source("discounted_comparator_tools_w3pts_bootstrap10k_thrs.txt"); bad[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> PredictionCalibration.alphaThresholds(mapper, bad, source("results-README.txt")));
        assertThrows(IllegalArgumentException.class, () -> PredictionCalibration.alphaThresholds(mapper, new byte[0], source("results-README.txt")));
        assertThrows(IllegalArgumentException.class, () -> PredictionCalibration.loadStandard(mapper, mapper.writeValueAsBytes(c), "0".repeat(64), bad, new byte[0]));
    }

    @ParameterizedTest @CsvSource({
        "0,BENIGN,MODERATE", "0.070,BENIGN,MODERATE", "0.0989999,BENIGN,MODERATE", "0.099,BENIGN,MODERATE",
        "0.0990001,BENIGN,SUPPORTING", "0.0995,BENIGN,SUPPORTING", "0.1689999,BENIGN,SUPPORTING", "0.169,BENIGN,SUPPORTING",
        "0.1690001,UNINFORMATIVE,NONE", "0.1695,UNINFORMATIVE,NONE", "0.7915,UNINFORMATIVE,NONE", "0.7919999,UNINFORMATIVE,NONE",
        "0.792,HARMFUL,SUPPORTING", "0.7920001,HARMFUL,SUPPORTING", "0.9055,HARMFUL,SUPPORTING", "0.9059999,HARMFUL,SUPPORTING",
        "0.906,HARMFUL,MODERATE", "0.9060001,HARMFUL,MODERATE", "0.9720,HARMFUL,MODERATE", "0.9725,HARMFUL,MODERATE",
        "0.9730,HARMFUL,MODERATE", "0.9899999,HARMFUL,MODERATE", "0.990,HARMFUL,STRONG", "0.9900001,HARMFUL,STRONG", "1,HARMFUL,STRONG"})
    void syntheticScoreExactBoundaries(String score, String direction, String strength) throws Exception {
        var out = PredictionCalibration.evaluateStandard(mapper, contract(), score); out.put("dataClass", "SYNTHETIC_SCORE");
        assertEquals("CALIBRATED", out.path("status").asText()); assertEquals(score, out.path("rawScore").asText());
        assertEquals(direction, out.path("direction").asText()); assertEquals(strength, out.path("calibratedStrength").asText());
        assertTrue(out.path("sourcePointValue").isNull()); assertFalse(out.path("fullSourcePointBandingVerified").asBoolean());
        assertEquals("NONE", out.path("appliedStrength").asText()); assertEquals("NOT_EVALUATED", out.path("applicationStatus").asText());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {".", "NaN", "-0.1", "1.01", "0.1e1", " 0.1", "Infinity"})
    void invalidOrMissingScoresNeverBecomeUninformative(String score) throws Exception {
        var out = PredictionCalibration.evaluateStandard(mapper, contract(), score);
        assertEquals("NOT_EVALUATED", out.path("status").asText()); assertFalse(out.has("direction"));
    }

    @Test void rejectsGapsOverlapsUnassignedPointsAndUnsupportedContract() throws Exception {
        for (String replacement : List.of("0.098", "0.100", "-1")) {
            var c = contract(); ((ObjectNode) c.path("bands").get(1)).put("lower", replacement);
            assertNotNull(PredictionCalibration.standardIssue(c));
        }
        var c = contract(); ((ObjectNode) c.path("bands").get(1)).put("lowerInclusive", true);
        assertNotNull(PredictionCalibration.standardIssue(c));
        c = contract(); ((ObjectNode) c.path("bands").get(1)).put("upperInclusive", false);
        assertNotNull(PredictionCalibration.standardIssue(c));
        c = contract(); ((ObjectNode) c.path("bands").get(1)).put("sourcePointValue", -1);
        assertNotNull(PredictionCalibration.standardIssue(c));
        c = contract(); c.put("version", "unsupported"); assertNotNull(PredictionCalibration.standardIssue(c));
        assertNotNull(PredictionCalibration.issue(contract())); // v1 runtime cannot silently consume the successor.
    }

    private PredictionResourceCompatibility.StandardBinding binding(ObjectNode evidence) throws Exception {
        Path p = temp.resolve("compatibility.json"); byte[] b = mapper.writeValueAsBytes(evidence); Files.write(p, b);
        return PredictionResourceCompatibility.StandardBinding.load(mapper, p, PredictionCalibration.sha256(b));
    }
    private PredictionLocalResourceBinding syntheticLocal() throws Exception {
        var resource = mapper.createObjectNode().put("model", "ALPHAMISSENSE").put("dataVersion", "2023-08-03")
                .put("sourceLocator", "https://storage.googleapis.com/dm_alphamissense/AlphaMissense_hg38.tsv.gz")
                .put("sha256", "1".repeat(64)).put("indexSha256", "2".repeat(64)).put("assembly", "GRCh38")
                .put("scoreField", "am_pathogenicity").put("scorePrecision", "ORIGINAL_DECIMAL_STRING").put("calibrationCompatibility", "UNVERIFIED");
        resource.putObject("license").put("purpose", "DECLARED_RESEARCH").put("status", "PERMITTED").put("sourceLocator", "synthetic:license");
        var local = mapper.createObjectNode().put("schemaVersion", "prediction-local-binding-v1"); local.set("resource", resource);
        byte[] b = mapper.writeValueAsBytes(local); Path p = temp.resolve("local.json"); Files.write(p, b);
        return PredictionLocalResourceBinding.load(mapper, p, PredictionCalibration.sha256(b));
    }
    private ObjectNode syntheticInput() {
        var in = mapper.createObjectNode().put("assemblyLineage", "NATIVE").put("consequence", "missense_variant")
                .put("route", "EXACT_VERSIONED_TRANSCRIPT").put("transcriptId", "ENST00000000001.1").put("proteinPosition", "1").put("aminoAcids", "A/V");
        in.putObject("queryAllele").put("genomeAssembly", "GRCh38").put("reference", "C").put("alternate", "T"); return in;
    }
    private ObjectNode syntheticEvidence(JsonNode c, JsonNode resource) {
        var e = mapper.createObjectNode().put("schemaVersion", "prediction-standard-resource-compatibility-v1")
                .put("dataClass", "SYNTHETIC_SCORE").put("calibrationId", c.path("calibrationId").asText())
                .put("calibrationVersion", c.path("version").asText()).put("calibrationCanonicalSha256", adapter.canonicalSha256(c));
        e.set("runtimeResourceIdentity", resource.deepCopy()); var checks = e.putObject("checks");
        for (String key : List.of("modelAndScore", "productAndDerivation", "studyMappingAndAggregation", "purpose"))
            checks.putObject(key).put("status", "PASS").put("sourceLocator", "synthetic:proof").put("sha256", "3".repeat(64));
        return e;
    }

    @Test void resourceScopeAndMappingAreIndependentOfExactAssociation() throws Exception {
        var c = contract(); var local = syntheticLocal(); var resource = local.identity(); var input = syntheticInput(); var e = syntheticEvidence(c, resource);
        var out = PredictionClauseRuntime.calibrateStandard(mapper, c, binding(e), local, resource, input, "0.0995");
        assertEquals("CALIBRATED", out.path("status").asText()); assertEquals("NOT_EVALUATED", out.path("applicationStatus").asText());
        ((ObjectNode) e.at("/checks/studyMappingAndAggregation")).put("status", "MISSING");
        out = PredictionClauseRuntime.calibrateStandard(mapper, c, binding(e), local, resource, input, "0.0995");
        assertEquals("AM_STUDY_MAPPING_AND_AGGREGATION_UNVERIFIED", out.path("reasonCode").asText()); assertFalse(out.has("direction"));
        for (String[] change : new String[][] {{"model", "OTHER", "MODEL"}, {"scoreField", "am_class", "SCORE_FIELD"},
                {"sourceLocator", "isoforms", "MAJOR_TRANSCRIPT_PRODUCT"}, {"scorePrecision", "ROUNDED", "SCORE_PRECISION"},
                {"sha256", "4".repeat(64), "LOCAL_MANIFEST"}}) {
            var wrong = resource.deepCopy().put(change[0], change[1]);
            out = PredictionClauseRuntime.calibrateStandard(mapper, c, binding(e), local, wrong, input, "0.0995");
            assertEquals("COMPATIBILITY_" + change[2] + "_MISMATCH", out.path("reasonCode").asText());
        }
        input.put("assemblyLineage", "PROJECTED");
        assertEquals("COMPATIBILITY_INPUT_OUTSIDE_SCOPE", PredictionClauseRuntime.calibrateStandard(mapper, c, binding(e), local, resource, input, "0.1").path("reasonCode").asText());
        e.put("calibrationVersion", "other");
        assertEquals("COMPATIBILITY_CALIBRATION_BINDING_MISMATCH", PredictionClauseRuntime.calibrateStandard(mapper, c, binding(e), local, resource, input, "0.1").path("reasonCode").asText());
        assertThrows(IllegalArgumentException.class, () -> PredictionResourceCompatibility.StandardBinding.load(mapper, temp.resolve("compatibility.json"), "0".repeat(64)));
        assertEquals("UNVERIFIED", resource.path("calibrationCompatibility").asText());
    }

    private ObjectNode readPinned(Path path, String sha) throws Exception {
        byte[] b = Files.readAllBytes(path); assertEquals(sha, PredictionCalibration.sha256(b), path.toString());
        JsonNode n = mapper.readTree(b); assertInstanceOf(ObjectNode.class, n); return (ObjectNode) n;
    }
    private JsonNode prediction(JsonNode run, String candidate) {
        for (JsonNode c : run.path("candidates")) if (candidate.equals(c.path("candidateId").asText())) return c.at("/attempts/0/prediction");
        throw new AssertionError("CANDIDATE_MISSING");
    }
    private void digest(JsonNode value, String field, String prefix) {
        ObjectNode copy = ((ObjectNode) value).deepCopy(); copy.remove(field);
        assertEquals(prefix + adapter.canonicalSha256(copy), value.path(field).asText());
    }

    @Test @EnabledIfSystemProperty(named = "varvis.pp3.offlineRoot", matches = ".+")
    void verifySavedPublicArtifactsAndWriteOneOfflineSidecar() throws Exception {
        Path root = Path.of(System.getProperty("varvis.pp3.offlineRoot"));
        JsonNode locations = mapper.readTree(Files.readAllBytes(root.resolve("offline-inputs.json")));
        Path previous = Path.of(locations.path("previousHandoff").asText());
        var child = readPinned(previous.resolve("acmg-41bbec6c-26e8-43a1-b637-94b3798f93e7.json"), "24f3af2114f1aea80eb110a29c2550c0d13ed46838261bc037a5a6b00f29d414");
        var frozen = readPinned(previous.resolve("frozen-selections.json"), "b8e0d0e54a249e0c8f7f40d60d43d0585bb738f50bc03c687cb60def2fb3f383");
        var request = readPinned(previous.resolve("submitted-request.json"), "a945122ba93860ad3fb8c853956faa6a676f159c029cc6b0a4f1d6ef6c25d499");
        var parent = readPinned(Path.of(locations.path("parent").asText()), "4b835ebd2791b69e6f6cb44193f131a475862b6e73e104307ed712430186290a");
        var local = PredictionLocalResourceBinding.load(mapper, Path.of(locations.path("localBinding").asText()), "a8684fd93b955bdb4244f25ef8274e91bc8d5a6a1d25d798eeae21d8c2173239");
        var compatibility = PredictionResourceCompatibility.StandardBinding.load(mapper, root.resolve("resource-compatibility.json"), locations.path("compatibilitySha256").asText());
        var c = contract(); var sidecar = mapper.createObjectNode().put("operation", "OFFLINE_CALIBRATION_VALIDATION")
                .put("newAcquisition", 0).put("newTransport", 0).put("newBusinessRuns", 0).put("criteriaModified", false);
        sidecar.set("inputArtifacts", locations.deepCopy()); sidecar.set("comparisonContract", c); sidecar.set("compatibilityEvidence", compatibility.evidence());
        var results = sidecar.putArray("results"); int calibrated = 0;
        Path output = root.resolve("offline-sidecar.json"); assertFalse(Files.exists(output), "Do not repeat real calibration or overwrite an earlier sidecar");
        assertEquals("b6353342-1e10-4c75-9ef9-8f42c229d084", child.path("analysisId").asText());
        assertEquals(parent.path("analysisId"), child.path("analysisId")); assertEquals(parent.path("runId"), child.path("sourceRunId"));
        assertEquals(parent.path("requestSha256"), request.at("/association/sourceRequestSha256"));
        assertEquals(2, child.path("candidates").size()); assertEquals(2, frozen.path("selections").size());
        for (JsonNode selection : frozen.path("selections")) {
            String id = selection.path("candidateId").asText(); JsonNode p = prediction(child, id), raw = prediction(parent, id);
            digest(p, "resultId", "prediction-result-sha256-"); digest(p.path("plan"), "planId", "saved-association-plan-sha256-");
            digest(p.path("observation"), "observationSha256", ""); assertEquals(raw.path("observation"), p.path("observation"));
            assertEquals(request.path("association"), p.at("/plan/parentReference"));
            assertEquals(selection.at("/selected/annotationId"), p.at("/selection/selectedAnnotationId"));
            assertEquals(selection.at("/selected/annotationSourceSha256"), p.at("/selection/annotationSourceSha256"));
            assertEquals(adapter.canonicalSha256(selection.path("actualSavedAnnotation")), p.at("/selection/annotationSourceSha256").asText());
            assertEquals(p.at("/selection/sourceObservationSha256"), p.at("/observation/observationSha256"));
            assertEquals("ASSOCIATED", p.at("/association/status").asText()); assertEquals(local.identity(), p.at("/observation/resource"));
            JsonNode row = p.at("/observation/rows/0"), in = p.path("associationInput");
            assertEquals(row.path("transcriptId"), in.path("transcriptId")); assertEquals(selection.at("/selected/versionedTranscriptId"), in.path("transcriptId"));
            assertEquals(selection.at("/actualSavedAnnotation/proteinPosition"), in.path("proteinPosition"));
            assertEquals(selection.at("/actualSavedAnnotation/aminoAcids"), in.path("aminoAcids"));
            assertEquals(in.path("queryAllele"), p.at("/observation/queryAllele"));
            assertEquals(row.path("rawScore"), p.at("/association/rawScore"));
            assertEquals("REAL_LOCAL_PRECOMPUTED_RESOURCE", p.at("/observation/scoreOrigin").asText());
            var scope = ((ObjectNode) in).deepCopy().put("assemblyLineage", "NATIVE").put("consequence", selection.at("/actualSavedAnnotation/consequence").asText());
            var result = PredictionClauseRuntime.calibrateStandard(mapper, c, compatibility, local, p.at("/observation/resource"), scope, row.path("rawScore").asText());
            if ("CALIBRATED".equals(result.path("status").asText())) calibrated++;
            else assertEquals("AM_STUDY_MAPPING_AND_AGGREGATION_UNVERIFIED", result.path("reasonCode").asText());
            var item = results.addObject().put("candidateId", id).put("childResultId", p.path("resultId").asText())
                    .put("observedAt", p.at("/observation/observedAt").asText()).put("observationSha256", p.at("/observation/observationSha256").asText());
            item.set("selection", p.path("selection").deepCopy()); item.set("associationInput", in.deepCopy()); item.set("calibration", result);
            item.set("criteria", p.path("criteria").deepCopy());
            for (JsonNode criterion : item.path("criteria")) {
                assertEquals("NOT_EVALUATED", criterion.path("status").asText()); assertEquals("NONE", criterion.path("appliedStrength").asText());
            }
        }
        sidecar.put("realObservationsCalibrated", calibrated).put("savedObservationsVerified", 2).put("fullSourcePointBandingVerified", false);
        readPinned(previous.resolve("acmg-41bbec6c-26e8-43a1-b637-94b3798f93e7.json"), "24f3af2114f1aea80eb110a29c2550c0d13ed46838261bc037a5a6b00f29d414");
        readPinned(Path.of(locations.path("parent").asText()), "4b835ebd2791b69e6f6cb44193f131a475862b6e73e104307ed712430186290a");
        Files.write(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sidecar), StandardOpenOption.CREATE_NEW);
    }
}
