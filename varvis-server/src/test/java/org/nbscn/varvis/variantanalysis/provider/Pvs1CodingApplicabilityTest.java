package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1CodingApplicabilityTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    void annotate(TruncatingNmdPvs1RuntimeTest.Inputs data, String consequence) throws Exception {
        ObjectNode annotation = mapper.valueToTree(data.candidate.getTranscriptAnnotations().get(0));
        annotation.remove(List.of("hgvsc", "hgvsp", "synonymousExonPosition"));
        annotation.put("consequence", consequence);
        annotation.put("hgvsProtein", "NP_000001.1:p.Gln11Arg").put("aminoAcids", "Q/R");
        data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
    }
    @ParameterizedTest @ValueSource(strings={"missense_variant","synonymous_variant","missense_variant&NMD_transcript_variant"})
    void pureCodingAnnotationSkipsMechanismAndSequenceWithoutGrantingEvidence(String consequence) throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root=root; f.setup("STOP",1);
        var bundle = Pvs1CodingSupplyIntegrationTest.load(root,f,f.data.model.deepCopy(),f.write());
        annotate(f.data,consequence);
        var result=bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION,f.data.context,f.data.candidate);
        assertEquals(Pvs1CodingApplicability.REASON,result.at("/evaluationResult/reasonCode").asText(),result.toPrettyString());
        assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());
        assertFalse(result.at("/codingApplicability/spliceEffectsExcluded").asBoolean(true));
        assertTrue(result.at("/codingApplicability/evidence").isObject());
        assertEquals(0,f.reads.get()); assertEquals(0,bundle.pvs1RuntimeCalls());
        assertEquals(0,bundle.pvs1EvaluatorCalls());
    }
    @ParameterizedTest @ValueSource(strings={"stop_gained","frameshift_variant","missense_variant&splice_region_variant","intron_variant","start_lost","stop_lost","unknown_variant",""})
    void otherAndUnknownConsequencesRemainOnFullPath(String consequence) throws Exception {
        var fixture=new TruncatingNmdPvs1RuntimeTest();var d=fixture.nonsense(1,1,31);annotate(d,consequence);
        var m=mapper.createObjectNode();m.set("pvs1ResourceProfile",fixture.resourceProfile());
        assertNull(Pvs1CodingApplicability.exclude(mapper,d.context,d.candidate,m,null));
    }
    @Test void suppliedRnaOrAmbiguousIdentityCannotBeShortCircuited() throws Exception {
        var fixture=new TruncatingNmdPvs1RuntimeTest();var d=fixture.nonsense(1,1,31);annotate(d,"missense_variant");
        var m=mapper.createObjectNode();m.set("pvs1ResourceProfile",fixture.resourceProfile());
        m.putArray("pvs1SpliceOutcomes").addObject().set("allele",d.context.at("/variant/evidenceQueryAllele"));
        assertNull(Pvs1CodingApplicability.exclude(mapper,d.context,d.candidate,m,null));
        m.remove("pvs1SpliceOutcomes");
        var a=d.candidate.getTranscriptAnnotations().get(0); d.candidate.applyTranscriptAnnotations(List.of(a,a));
        assertNull(Pvs1CodingApplicability.exclude(mapper,d.context,d.candidate,m,null));
    }
    @Test void splicePolicyNeverSkippedByCodingAnnotation() throws Exception {
        var fixture=new TruncatingNmdPvs1RuntimeTest();var d=fixture.nonsense(1,1,31);annotate(d,"missense_variant");
        var m=mapper.createObjectNode();m.set("pvs1ResourceProfile",fixture.resourceProfile());
        assertNull(Pvs1CodingApplicability.exclude(mapper,d.context,d.candidate,m,mapper.createObjectNode().put("schemaVersion",Pvs1SpliceConsequence.SCHEMA)));
    }
    @ParameterizedTest @ValueSource(strings={"TIMESTAMP","CONTEXT","PROTEIN_CONFLICT","RESOURCE_VERSION","POPULATION_ONLY"})
    void invalidOrConflictingIdentityIsNotNonApplicability(String defect) throws Exception {
        var fixture=new TruncatingNmdPvs1RuntimeTest();var d=fixture.nonsense(1,1,31);annotate(d,"missense_variant");
        var m=mapper.createObjectNode();m.set("pvs1ResourceProfile",fixture.resourceProfile());
        ObjectNode a=mapper.valueToTree(d.candidate.getTranscriptAnnotations().get(0));a.remove(List.of("hgvsc","hgvsp","synonymousExonPosition"));
        switch(defect) {
            case "TIMESTAMP" -> a.remove("timestamp");
            case "CONTEXT" -> d.context.withObject("gene").put("symbol","WRONG");
            case "PROTEIN_CONFLICT" -> a.put("hgvsProtein","NP_000001.1:p.Gln11Ter");
            case "RESOURCE_VERSION" -> a.put("version","115.0");
            case "POPULATION_ONLY" -> d.context.withObject("transcript").put("selectionPolicy","PM2_CONTEXT_ONLY");
        }
        d.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(a,TranscriptConsequence.class)));
        assertNull(Pvs1CodingApplicability.exclude(mapper,d.context,d.candidate,m,null));
    }
}
