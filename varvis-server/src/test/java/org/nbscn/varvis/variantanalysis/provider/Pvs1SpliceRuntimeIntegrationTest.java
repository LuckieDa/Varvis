package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real Runtime dispatch with deliberately synthetic scientific facts; Bundle tests are separate. */
class Pvs1SpliceRuntimeIntegrationTest {
    final Pvs1SpliceConsequenceTest base = new Pvs1SpliceConsequenceTest();
    Pvs1SpliceConsequenceTest.Data data(String mode, int strand) throws Exception {
        var fixture=new Pvs1NmdBusinessFixture(base.mapper,false);int i=strand==1?0:1;
        var fact=Pvs1SpliceRound3Fixture.configure(fixture,i,mode);var old=base.data(strand,false,false).input();
        old.model=fixture.models.get(i);old.policy=fixture.policies.get(i);
        old.candidate=RuntimeEvidenceV2ProviderWiringTest.variant(fixture.alleles.get(i));
        old.candidate.applyTranscriptAnnotations(List.of(fixture.annotation(i)));
        old.context.set("variant",RuntimeEvidenceV2ProviderWiringTest.syntheticContext(base.mapper,base.adapter,fixture.alleles.get(i)).path("variant"));
        old.context.put("contextId",base.adapter.canonicalContextId(old.context));
        return new Pvs1SpliceConsequenceTest.Data(old,fact);
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void inFrameModuleStrengthAndNegativeReachRuntimeOnBothStrands(int strand) throws Exception {
        var d=data("INFRAME",strand);var r=base.run(d);
        assertEquals("MET",base.status(r).path("status").asText(),r::toPrettyString);
        assertEquals("MODERATE",r.at("/evaluation/appliedStrength").asText());
        assertEquals("MODERATE",r.at("/evaluation/baseStrength").asText());
        assertEquals(TruncatingNmdPvs1Runtime.SPLICE_MODULE_VERSION,r.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        var summary=Pvs1SelectedBatch.publicSummary(base.mapper,r);
        assertEquals(Pvs1SelectedBatch.SPLICE_VERSION,summary.path("methodVersion").asText());
        assertEquals("MODERATE",summary.at("/spliceAssessment/adoptedStrength").asText());
        assertEquals(4,summary.at("/inFrameAssessment/deletedProteinResidues").asInt());
        assertFalse(summary.toString().contains("cdsSequence"));assertFalse(summary.toString().contains("referenceResidueEffects"));
        d=data("NEGATIVE",strand);r=base.run(d);
        assertEquals("NOT_MET",base.status(r).path("status").asText(),r::toPrettyString);
        assertEquals("NONE",r.at("/evaluation/appliedStrength").asText());
        assertEquals("NOT_APPLICABLE",r.at("/evaluation/criterionApplicability/status").asText());
        d=data("INFRAME",strand);d.material().withObject("inFrameReview").put("status","UNREVIEWED");r=base.run(d);
        assertEquals("NOT_EVALUATED",base.status(r).path("status").asText(),r::toPrettyString);
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void crossJunctionPtcReachesRuntimeWithoutTheOldTopologyBlocker(int strand) throws Exception {
        var r=base.run(data("TOPOLOGY",strand));
        assertEquals("MET",base.status(r).path("status").asText(),r::toPrettyString);
        assertTrue(r.at("/resolvedInput/spliceAssessment/ptcCrossesJunction").asBoolean());
        assertEquals(437,r.at("/resolvedInput/lastJunctionCdsPosition").asInt());
        assertEquals(386,r.at("/resolvedInput/distanceToLastJunction").asInt());
        var summary=Pvs1SelectedBatch.publicSummary(base.mapper,r);
        assertEquals(5,summary.at("/spliceAssessment/referenceTopology/exonCount").asInt());
        assertEquals(4,summary.at("/spliceAssessment/alteredTopology/exonCount").asInt());
    }
    @Test void reviewedV4ReachesTheRealUtrTopologyModuleAndRequiresItsOwnApplicability() throws Exception {
        var d=base.data(1,false,false);var model=d.input().model;var exons=model.withArray("exons");
        var terminal=(ObjectNode)exons.path(3);terminal.put("cdsEnd",620).put("genomicEnd",terminal.path("genomicEnd").asInt()+20);
        exons.addObject().put("ordinal",5).put("cdsStart",621).put("cdsEnd",650).put("genomicStart",6000).put("genomicEnd",6029);
        model.put("lastJunctionCdsPosition",620);String cds=model.path("cdsSequence").asText();
        model.putObject("sequenceVerification").put("cdsSha256",CodingTranscriptMaterialSupply.sha(cds))
                .put("fullSplicedTranscriptSha256",CodingTranscriptMaterialSupply.sha(cds+"A".repeat(50)));
        d.material().put("transcriptModelSha256",base.adapter.canonicalSha256(model));
        var nmd=d.input().policy.withObject("nmdScope");nmd.putArray("relevantExonOrdinals").add(1).add(2).add(3).add(4).add(5);
        nmd.putArray("engineeringSupportedExonOrdinals").add(1).add(2).add(3).add(4);
        var result=base.run(d);
        assertEquals("PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED",base.status(result).path("reasonCode").asText(),result::toPrettyString);
        nmd.putObject("utrJunctionApplicability").put("status","REVIEWED_FOR_3_PRIME_UTR_JUNCTION")
                .put("basis","TEST_ONLY source bound UTR applicability").putArray("sourceIds").add("synthetic-source");
        result=base.run(d);
        assertEquals("MET",base.status(result).path("status").asText(),result::toPrettyString);
        assertEquals(607,result.at("/resolvedInput/lastJunctionCdsPosition").asInt());
        assertEquals("LAST_JUNCTION_IN_3_PRIME_UTR",result.at("/resolvedInput/transcriptTopology/kind").asText());
    }
    @Test void publicRowsAreBoundedAndOldOrdinarySpliceKeepsItsMethod() throws Exception {
        var ordinary=base.run(base.data(1,false,false));
        assertEquals(Pvs1SelectedBatch.LEGACY_FACTS_VERSION,Pvs1SelectedBatch.publicSummary(base.mapper,ordinary).path("methodVersion").asText());
        var r=base.run(data("TOPOLOGY",1));var rows=((ObjectNode)r.at("/resolvedInput/spliceAssessment")).putArray("retainedExons");
        for(int i=0;i<80;i++)rows.addObject().put("referenceOrdinal",i+1).put("alteredOrdinal",i+1).put("rawText","PRIVATE_ORIGINAL");
        var s=Pvs1SelectedBatch.publicSummary(base.mapper,r).path("spliceAssessment");
        assertEquals(32,s.path("retainedExons").size());assertEquals(80,s.path("retainedExonsTotal").asInt());assertTrue(s.path("retainedExonsTruncated").asBoolean());
        assertFalse(s.toString().contains("PRIVATE_ORIGINAL"));assertEquals(80,rows.size());
    }
}
