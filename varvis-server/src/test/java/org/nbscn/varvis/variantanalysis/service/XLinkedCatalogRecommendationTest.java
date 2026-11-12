package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic non-gene-specific XL source scope; it must never silently become XD or XR. */
class XLinkedCatalogRecommendationTest {
    final AcmgRecommendationsTest f = new AcmgRecommendationsTest();
    VariantInterpretation candidate(String moi) throws Exception {
        var raw=f.mapper.createObjectNode().put("geneSymbol","HBB").put("transcriptAccession","ENST00000335295.4")
                .put("hgvsCoding","ENST00000335295.4:c.7_9del").put("hgvsProtein","ENSP00000494175.1:p.Glu3del")
                .put("consequence","inframe_deletion").put("source","VEP").put("version","116.0")
                .put("status","COMPLETED").put("timestamp","2026-09-07T00:00:00Z").put("maneSelect",true);
        var c=f.candidate("HBB",f.mapper.treeToValue(raw,TranscriptConsequence.class));
        when(c.getObservation()).thenReturn(new VariantQcObservation(new NormalizedVariantKey("GRCh38","X",20,"ACCT","A"),
                "synthetic",null,null,null,null,null,"PASS","INDEL",null));
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn(moi);
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn(moi);
        when(c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn(moi);
        when(c.getAnalysisVcfCall()).thenReturn(LocalInterpretationContextPolicyTest.call("REF/TARGET_ALT",AnalysisVcfCall.TargetAlleleCall.HET_TARGET));
        return c;
    }
    ObjectNode catalog(String moi) {
        var cat=f.catalog("Definitive");((ObjectNode)cat.at("/relationships/0")).put("moi",moi);return cat;
    }
    AcmgRecommendations policy() {
        return new AcmgRecommendations(f.mapper,ignored->{throw new AssertionError("No score query during context selection");},f.clock)
                .withUnifiedLocal(f.mapper.createObjectNode());
    }
    @ParameterizedTest @ValueSource(strings={"X_LINKED_DOMINANT","X_LINKED_RECESSIVE"})
    void broaderCatalogPreservedAndRankingContextRequiresExplicitConfirmation(String moi) throws Exception {
        var c=candidate(moi);var cat=catalog("XL");var original=cat.deepCopy();var p=policy();
        var r=p.recommend("analysis",c,cat,"a".repeat(64));var relation=r.at("/relationships/0");
        assertTrue(relation.path("selectable").asBoolean(),r.toPrettyString());
        assertEquals("XL",relation.path("catalogMoi").asText());assertEquals(moi,relation.path("moi").asText());
        assertTrue(relation.path("requiresExplicitInterpretationContext").asBoolean());assertTrue(r.path("stableRelationshipKey").isNull());
        assertFalse(relation.at("/interpretationContext/diagnosisClaimed").asBoolean(true));
        assertTrue(r.at("/annotations/0/contextSelectable").asBoolean());
        assertFalse(r.at("/annotations/0/predictionCompatible").asBoolean());
        assertEquals(original,cat);assertEquals(moi,c.getCandidateIdentity().getModeOfInheritance());
        var none=new AcmgRecommendations.Selection(r.path("candidateId").asText(),r.path("recommendationId").asText(),r.path("policyVersion").asText(),r.path("policyHash").asText(),null,null,null);
        p.verify(none,r,List.of());
        assertTrue(p.withPopulationContexts(new AcmgSubmission(1,"ordinary-unified-local-eight-v1",null,"synthetic","SYNTHETIC",null,List.of(),List.of(none)),f.mapper.createArrayNode().add(r)).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE","CALL","RANKING","AUTOSOME","UNKNOWN","DUPLICATE"})
    void incompleteOrConflictingScopeCannotBeSelected(String kind) throws Exception {
        var c=candidate("X_LINKED_RECESSIVE");var cat=catalog("XL");String source="a".repeat(64);
        switch(kind) {
            case "SOURCE" -> source=null;
            case "CALL" -> when(c.getAnalysisVcfCall()).thenReturn(null);
            case "RANKING" -> when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("X_LINKED_DOMINANT");
            case "AUTOSOME" -> when(c.getObservation()).thenReturn(new VariantQcObservation(new NormalizedVariantKey("GRCh38","1",20,"ACCT","A"),"synthetic",null,null,null,null,null,"PASS","INDEL",null));
            case "UNKNOWN" -> ((ObjectNode)cat.at("/relationships/0")).put("moi","UD");
            case "DUPLICATE" -> ((com.fasterxml.jackson.databind.node.ArrayNode)cat.path("relationships")).add(cat.at("/relationships/0").deepCopy());
            default -> fail();
        }
        assertFalse(policy().recommend("a",c,cat,source).at("/relationships/0/selectable").asBoolean());
    }
    @Test void oldIntakeStillDoesNotGuessAnXLinkedSubtype() throws Exception {
        var p=new AcmgRecommendations(f.mapper,ignored->{throw new AssertionError();},f.clock);
        assertFalse(p.recommend("a",candidate("X_LINKED_RECESSIVE"),catalog("XL"),"a".repeat(64)).at("/relationships/0/selectable").asBoolean());
        assertNull(PatientDiseaseContextBindingResolver.normalizeMoi("XL"));
        assertNull(PatientDiseaseContextBindingResolver.normalizeCatalogMoi("XL"));
    }
}
