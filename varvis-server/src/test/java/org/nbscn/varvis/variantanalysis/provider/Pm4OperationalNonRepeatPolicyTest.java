package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pm4OperationalNonRepeatPolicyTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ObjectNode evaluate(String type, boolean review, boolean knownSpec) throws Exception {
        var f = new ProteinLengthChangeMaterialSupplyTest(); f.root = root;
        f.setup(1, 105, "A", "AGCT", "6_7insGCT", "Glu2_Pro3insAla");
        var runtime = GeneralPm4TestSupport.create(mapper, root.resolve("runtime"), f.data.model, f::read, false, review, knownSpec,
                Pm4OperationalNonRepeatPolicy.MODE, raw -> {
                    if (type.isEmpty()) return;
                    var feature = raw.withArray("features").addObject().put("type", type.equals("Disordered") ? "Region" : type);
                    feature.put("description", type); var loc = feature.putObject("location");
                    loc.putObject("start").put("value", 2).put("modifier", "EXACT");
                    loc.putObject("end").put("value", 3).put("modifier", "EXACT");
                });
        return runtime.evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
    }
    @Test void optInCompletesWithoutPerProteinApprovalAndDoesNotMasqueradeAsReviewed() throws Exception {
        var out = evaluate("", false, false);
        assertEquals("MET", out.path("status").asText(), out.toPrettyString());
        assertEquals("PM4_PROJECT_LOCAL_OPERATIONAL_NON_REPEAT_MET", out.path("reasonCode").asText());
        assertEquals("OPERATIONAL_NON_REPEAT_SUPPORTED", out.at("/publicSummary/regionState").asText());
        assertTrue(out.at("/publicSummary/nonRepeatFromNoHit").asBoolean());
        assertEquals("OPERATIONAL_INFERENCE", out.at("/publicSummary/qualificationBasis").asText());
        assertFalse(out.at("/publicSummary/absoluteNonRepeatProof").asBoolean(true));
        assertFalse(Files.exists(root.resolve("runtime/assertions.json")));
    }
    @ParameterizedTest @ValueSource(strings={"Compositional bias", "Disordered"})
    void lowComplexityAndDisorderAreScopeExclusionsNotRepeatOrBp3Proof(String type) throws Exception {
        var out = evaluate(type, false, false);
        assertEquals("NOT_EVALUATED", out.path("status").asText(), out.toPrettyString());
        assertEquals("PM4_OPERATIONAL_LOW_COMPLEXITY_OR_DISORDER_EXCLUDED", out.path("reasonCode").asText());
        assertEquals(1, out.at("/operationalNonRepeat/exclusions").size());
        assertEquals("NO_REPEAT_OBSERVED", out.at("/publicSummary/repeatObservation").asText());
    }
    @Test void annotatedRepeatStillFails() throws Exception {
        assertEquals("PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET", evaluate("Repeat", false, false).path("reasonCode").asText());
    }
    @Test void functionalDomainIsNotAnAutomaticPm4Exclusion() throws Exception {
        assertEquals("MET", evaluate("Domain", false, false).path("status").asText());
    }
    @Test void knownSpecializationCannotFallBackToOperationalPolicy() throws Exception {
        assertEquals("PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION", evaluate("", false, true).path("reasonCode").asText());
    }
    @Test void reviewedConflictRemainsBlocked() throws Exception {
        assertEquals("PM4_REGION_SOURCE_CONFLICT", evaluate("Repeat", true, false).path("reasonCode").asText());
    }
    @Test void reviewedPositiveRetainsItsOwnBasis() throws Exception {
        var out = evaluate("", true, false);
        assertEquals("PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET", out.path("reasonCode").asText());
        assertEquals("REVIEWED_COVERAGE", out.at("/publicSummary/qualificationBasis").asText());
        assertFalse(out.at("/publicSummary/nonRepeatFromNoHit").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings={"uniprotFeaturesComplete", "interproEntriesComplete", "exactFullSequenceMatch",
            "uniprotFeatureCount", "interproEntryCount", "oldAdapter", "release", "pin"})
    void sourceCompletenessCannotBeBorrowedFromLegacyOrDamagedExport(String missing) throws Exception {
        var out = evaluate("", false, false); var mapped = (ObjectNode) out.path("proteinAnnotations");
        var doc = GeneralPm4TestSupport.combined(mapper, mapped); var f = (ObjectNode)doc.at("/results/0/facts");
        switch (missing) {
            case "oldAdapter" -> f.put("adapterVersion", "1.0.0");
            case "release" -> ((ObjectNode)f.path("sources")).put("uniprotRelease", "wrong");
            case "pin" -> ((ObjectNode)f.at("/sources/pins/entries")).put("sha256", "wrong");
            default -> ((ObjectNode)f.path("sourceCompleteness")).remove(missing);
        }
        var path = root.resolve("damaged-combined.json"); byte[] bytes = mapper.writeValueAsBytes(doc); Files.write(path, bytes);
        var combined = new CombinedProteinRegionSupply(mapper, path, CodingTranscriptMaterialSupply.sha(bytes)).resolve(mapped);
        assertEquals("READY", combined.path("status").asText());
        var result = Pm4OperationalNonRepeatPolicy.assess(mapper, mapped, combined, (ObjectNode)out.path("shortRepeats"));
        assertFalse(result.path("qualified").asBoolean());
        assertEquals("PM4_OPERATIONAL_SOURCE_COMPLETENESS_REQUIRED", result.path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"unmapped", "unplaced", "shortCandidate", "shortIncomplete", "malformedRaw", "coordinates"})
    void incompleteOrConflictingInputsNeverBecomeInferredNegative(String defect) throws Exception {
        var out = evaluate("Domain", false, false); var mapped = (ObjectNode)out.path("proteinAnnotations");
        var combined = (ObjectNode)out.path("combinedRegions"); var screen = (ObjectNode)out.path("shortRepeats");
        switch (defect) {
            case "unmapped" -> mapped.withArray("unmappedFeatures").addObject();
            case "unplaced" -> combined.put("unplacedCount", 1);
            case "shortCandidate" -> screen.put("candidateFound", true);
            case "shortIncomplete" -> screen.put("status", "UNAVAILABLE");
            case "malformedRaw" -> ((ObjectNode)mapped.at("/features/0")).put("rawFeatureJson", "{");
            case "coordinates" -> ((ObjectNode)mapped.at("/features/0")).put("start", 4);
            default -> fail();
        }
        assertFalse(Pm4OperationalNonRepeatPolicy.assess(mapper, mapped, combined, screen).path("qualified").asBoolean());
    }
    @Test void defaultIsStrictAndUnknownModeRejected() {
        assertEquals("REVIEWED_ONLY", Pm4OperationalNonRepeatPolicy.mode(""));
        assertThrows(IllegalArgumentException.class, () -> Pm4OperationalNonRepeatPolicy.mode("auto"));
    }
}
