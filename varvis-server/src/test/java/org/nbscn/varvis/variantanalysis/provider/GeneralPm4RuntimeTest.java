package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GeneralPm4RuntimeTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @ParameterizedTest @CsvSource({"true,false,false,NOT_MET,PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET",
            "false,true,false,MET,PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET",
            "false,false,false,NOT_EVALUATED,PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED",
            "true,true,false,NOT_EVALUATED,PM4_REGION_SOURCE_CONFLICT",
            "false,true,true,NOT_EVALUATED,PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION"})
    void actualSequenceAndSourcePolicy(boolean repeat, boolean negative, boolean spec, String status, String reason) throws Exception {
        var f = fixture(); var runtime = GeneralPm4TestSupport.create(mapper, root.resolve("runtime"), f.data.model, f::read, repeat, negative, spec);
        var before = mapper.valueToTree(f.data.candidate); var context = f.data.context.deepCopy();
        var result = runtime.evaluate(context, f.data.candidate, mapper.createObjectNode());
        assertEquals(status, result.path("status").asText(), result.toPrettyString());
        assertEquals(reason, result.path("reasonCode").asText());
        assertEquals("MET".equals(status) ? "MODERATE" : "NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals(before, mapper.valueToTree(f.data.candidate)); assertEquals(context, f.data.context);
        assertFalse(result.path("publicSummary").toString().contains("cdsSequence"));
        assertEquals(spec ? "INCOMPLETE" : repeat ? "KNOWN_REPEAT" : "NO_REPEAT_OBSERVED",
                result.at("/publicSummary/repeatObservation").asText());
    }
    @Test void runtimeCannotBorrowSyntheticNegativeApprovalForNonSyntheticInput() throws Exception {
        var f = fixture(); var runtime = GeneralPm4TestSupport.create(mapper, root.resolve("runtime"), f.data.model, f::read, false, true, false);
        f.data.context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
        f.data.context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(f.data.context));
        var out = runtime.evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
        assertEquals("PM4_SYNTHETIC_ASSERTION_RUNTIME_FORBIDDEN", out.path("reasonCode").asText(), out.toPrettyString());
    }
    @Test void alteredCombinedFileDoesNotSilentlyAuthorizeNonRepeat() throws Exception {
        var f = fixture(); var runtime = GeneralPm4TestSupport.create(mapper, root.resolve("runtime"), f.data.model, f::read, false, true, false);
        Files.writeString(root.resolve("runtime/combined.json"), "{}");
        assertEquals("PM4_COMBINED_BYTES_CHANGED", runtime.evaluate(f.data.context, f.data.candidate, mapper.createObjectNode()).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"MEQQQQQQQQPGFK", "MEAPAPAPAPGFK", "MEACDACDACDGFK", "MEAAAAAAAAAAAAAAAGFK"})
    void shortRepeatsProduceBoundedSequenceFactsNeverNegativeProof(String sequence) {
        var out = ProteinShortRepeatScreen.scan(mapper, sequence, 3, 4);
        assertEquals("COMPLETE", out.path("status").asText()); assertFalse(out.path("hits").isEmpty());
        assertFalse(out.path("nonRepeatQualified").asBoolean(true));
        for (var hit : out.path("hits")) {
            int start = hit.path("start").asInt(), end = hit.path("end").asInt(); String motif = hit.path("motif").asText();
            for (int i = start - 1; i < end; i++) assertEquals(motif.charAt((i - start + 1) % motif.length()), sequence.charAt(i));
        }
    }
    @Test void screenDoesNotClipIntervalsOrAssignNonRepeatOnMissOrUnsupported() {
        var hit = ProteinShortRepeatScreen.scan(mapper, "MEQQQQQQQQPGFK", 5, 5).at("/hits/0");
        assertEquals(3, hit.path("start").asInt()); assertEquals(10, hit.path("end").asInt());
        var miss = ProteinShortRepeatScreen.scan(mapper, "MEPGFK", 2, 3);
        assertTrue(miss.path("hits").isEmpty()); assertFalse(miss.path("nonRepeatQualified").asBoolean(true));
        assertEquals("UNAVAILABLE", ProteinShortRepeatScreen.scan(mapper, "XQQQQQ", 1, 3).path("status").asText());
        assertEquals("UNAVAILABLE", ProteinShortRepeatScreen.scan(mapper, "A".repeat(6001), 1, 3).path("status").asText());
        assertTrue(ProteinShortRepeatScreen.scan(mapper, "MEQQQQQQPGFK", 10, 11).path("hits").isEmpty());
    }

    @Test void insertedRepeatCannotBorrowNegativeReferenceCoverage() throws Exception {
        var f = fixture(); String inserted = "GCT".repeat(6);
        f.data.variant(105, "A", "A" + inserted, "6_7ins" + inserted, "Glu2_Pro3ins" + "Ala".repeat(6));
        var runtime = GeneralPm4TestSupport.create(mapper, root.resolve("runtime"), f.data.model, f::read, false, true, false);
        var out = runtime.evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
        assertEquals("PM4_SHORT_REPEAT_CANDIDATE_REQUIRES_QUALIFICATION", out.path("reasonCode").asText(), out.toPrettyString());
        assertTrue(out.at("/shortRepeats/reference/hits").isEmpty()); assertFalse(out.at("/shortRepeats/altered/hits").isEmpty());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("SHORT_REPEAT_CANDIDATE", out.at("/publicSummary/repeatObservation").asText());
    }
    @ParameterizedTest @ValueSource(strings={"uniprot", "unmapped", "combined", "unplaced", "screen"})
    void incompleteFactsNeverReportACompletedNegativeObservation(String missing) throws Exception {
        var f = fixture(); var runtime = GeneralPm4TestSupport.create(mapper, root.resolve("runtime"), f.data.model, f::read, false, false, false);
        var out = runtime.evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
        assertEquals("NO_REPEAT_OBSERVED", out.at("/publicSummary/repeatObservation").asText());
        switch (missing) {
            case "uniprot" -> ((ObjectNode) out.path("proteinAnnotations")).put("status", "UNAVAILABLE");
            case "unmapped" -> ((ObjectNode) out.path("proteinAnnotations")).putArray("unmappedFeatures").addObject().put("type", "Repeat");
            case "combined" -> ((ObjectNode) out.path("combinedRegions")).put("status", "UNAVAILABLE");
            case "unplaced" -> ((ObjectNode) out.path("combinedRegions")).put("unplacedCount", 1);
            case "screen" -> ((ObjectNode) out.path("shortRepeats")).put("status", "UNAVAILABLE");
            default -> fail("Unknown test input");
        }
        assertEquals("INCOMPLETE", GeneralPm4Runtime.publicSummary(out).path("repeatObservation").asText());
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertFalse(GeneralPm4Runtime.publicSummary(out).path("nonRepeatFromNoHit").asBoolean(true));
    }
    ProteinLengthChangeMaterialSupplyTest fixture() throws Exception {
        var f = new ProteinLengthChangeMaterialSupplyTest(); f.root = root;
        f.setup(1, 105, "A", "AGCT", "6_7insGCT", "Glu2_Pro3insAla"); return f;
    }
}
