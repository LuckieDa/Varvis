package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class FullRnaProteinLengthObservationTest {
    @TempDir Path temporary;
    @ParameterizedTest @CsvSource({"1,deletion,MKGFE*,-1", "-1,deletion,MKGFE*,-1", "1,insertion,MKPPGFE*,1", "-1,insertion,MKPPGFE*,1",
            "1,stop-loss,MKPGFEG*,1", "-1,stop-loss,MKPGFEG*,1", "1,cross-utr,MKPGFEW*,1", "-1,cross-utr,MKPGFEW*,1"})
    void exactSourceProductUsesActualProteinLengthAndKeepsItsOwnCoordinateMeaning(int strand, String scenario, String protein, int delta) throws Exception {
        var f = new FullRnaCriterionFixture(temporary,strand);f.scenario(scenario);var source = f.source();var out = f.f.mapper.createObjectNode();
        assertNull(ProteinLengthChangeObservation.resolveFullRna(f.f.mapper,f.f.context,source,out),out::toPrettyString);
        assertEquals("MKPGFE*",out.path("referenceProtein").asText());assertEquals(protein,out.path("alteredProtein").asText());
        assertEquals(delta,out.path("netAminoAcidChange").asInt());
        assertEquals(source.originalSha256(),out.path("sourceOriginalSha256").asText());
        assertEquals(source.facts().canonicalSha256(),out.path("factsSha256").asText());
        assertEquals("SOURCE_BOUND_RNA_AND_REFERENCE_PROTEIN",out.path("coordinateSystem").asText());
        assertEquals(scenario.contains("utr") || scenario.equals("stop-loss") ? "SOURCE_BOUND_RNA_STOP_LOSS_EXTENSION" : "SOURCE_BOUND_RNA_INFRAME_LENGTH_CHANGE",out.path("branch").asText());
        assertFalse(out.path("criterionQualificationGranted").asBoolean());
        for (String fabricated : List.of("hgvsc","referenceEditStart0","referenceEditEnd0","equivalentEditEnvelope","exonOrdinal")) assertFalse(out.has(fabricated),fabricated);
    }
    @ParameterizedTest @CsvSource({"utr,FULL_RNA_NO_NET_PROTEIN_LENGTH_CHANGE", "start-loss,FULL_RNA_LENGTH_INITIATION_UNRESOLVED",
            "no-stop,FULL_RNA_LENGTH_TERMINATION_UNRESOLVED", "ptc,FULL_RNA_LENGTH_REQUIRES_TRUNCATING_OR_OTHER_MECHANISM",
            "frame-utr-cancellation,FULL_RNA_LENGTH_REQUIRES_TRUNCATING_OR_OTHER_MECHANISM"})
    void unknownOrDifferentMechanismsDoNotBecomeLengthEvidence(String scenario,String reason) throws Exception {
        var f = new FullRnaCriterionFixture(temporary,1);f.scenario(scenario);var source=f.source();var out=f.f.mapper.createObjectNode();
        assertEquals(reason,ProteinLengthChangeObservation.resolveFullRna(f.f.mapper,f.f.context,source,out),out::toPrettyString);
        assertFalse(out.has("branch"));
        if (scenario.equals("start-loss") || scenario.equals("no-stop")) assertTrue(out.path("alteredProteinLength").isNull());
        if (scenario.equals("frame-utr-cancellation")) {
            assertEquals(0,source.result().delta());assertEquals(1,out.at("/codingEffects/frameOffsetAtRetainedNativeStop").asInt());
        }
    }
    @Test void noSourceAndWrongContextCannotBeAccepted() throws Exception {
        var f=new FullRnaCriterionFixture(temporary,1);f.scenario("deletion");var out=f.f.mapper.createObjectNode();
        assertEquals("FULL_RNA_LENGTH_SOURCE_REQUIRED",ProteinLengthChangeObservation.resolveFullRna(f.f.mapper,f.f.context,null,out));
        var source=f.source();var wrong=f.f.context.deepCopy().put("moi","AUTOSOMAL_RECESSIVE");
        assertEquals("FULL_RNA_LENGTH_CONTEXT_BINDING_MISMATCH",ProteinLengthChangeObservation.resolveFullRna(f.f.mapper,wrong,source,out));
    }
    @Test void neutralObservationPreservesAssayLimitationsAndDoesNotGrantCriterionEligibility() throws Exception {
        var f=new FullRnaCriterionFixture(temporary,1);f.scenario("deletion");
        f.original.withObject("assay").put("assayQuality","UNKNOWN").put("tissueRelevance","UNKNOWN").put("residualNormalTranscript","DETECTED_IN_ASSESSED_SCOPE");
        var out=f.f.mapper.createObjectNode();assertNull(ProteinLengthChangeObservation.resolveFullRna(f.f.mapper,f.f.context,f.source(),out));
        assertEquals("DETECTED_IN_ASSESSED_SCOPE",out.at("/sourceReplay/assayConditions/residualNormalTranscript").asText());
        assertFalse(out.path("criterionQualificationGranted").asBoolean());
    }
}
