package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1NmdConditionsTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final TruncatingNmdPvs1RuntimeTest fixture = new TruncatingNmdPvs1RuntimeTest();

    @ParameterizedTest @ValueSource(ints={1,-1})
    void oneRuntimeHandlesPartialExonDomainsOnBothStrands(int strand) throws Exception {
        var d=fixture.nonsense(strand==1?1:2,strand,31);
        d.policy.withObject("nmdScope").putArray("engineeringSupportedExonOrdinals").add(2);
        assertEquals("PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED",run(d).at("/evaluation/evaluationResult/reasonCode").asText());
        d.policy.withObject("nmdScope").putArray("engineeringSupportedExonOrdinals").add(1).add(2);
        condition(d.policy,"REFERENCE_PTC_SPAN",31,34);
        var result=run(d);assertEquals("MET",result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("1.1.0",result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        assertEquals("PASS",result.at("/resolvedInput/positionConditionChecks/0/status").asText());
        ((ObjectNode)d.policy.at("/nmdScope/positionConditions/0")).put("maximumExclusive",33);
        assertEquals("PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE",run(d).at("/evaluation/evaluationResult/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings={"REFERENCE_EVENT_SPAN","REFERENCE_PTC_SPAN","ALTERED_PTC_SPAN"})
    void frameshiftKeepsTrueCoordinateSystemsAndFullCodon(String coordinate) throws Exception {
        var d=fixture.frameshift(true,1,120);condition(d.policy,coordinate,1,100);
        var result=run(d);assertEquals("MET",result.at("/evaluation/evaluationResult/status").asText(),result.toPrettyString());
        assertEquals(55,result.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals(121,result.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
    }

    @Test void insertedStopCannotBeTreatedAsReferenceCoordinate() {
        var p=policy("REFERENCE_PTC_SPAN",1,100);
        var input=mapper.createObjectNode().put("variantMechanism","FRAMESHIFT").put("ptcReferenceMapping","CONTAINS_INSERTED_BASES")
                .put("ptcStart",64).put("ptcEnd",66).put("ptcReferenceStart",63).putNull("ptcReferenceMiddle").put("ptcReferenceEnd",64);
        assertEquals("PVS1_POLICY_POSITION_UNRESOLVED",Pvs1NmdConditions.evaluate(p,input));
    }

    @Test void insertionChecksBothFlanksAndBothEquivalentRepresentations() {
        var p=policy("REFERENCE_EVENT_SPAN",31,40);
        var input=mapper.createObjectNode().put("variantMechanism","FRAMESHIFT").put("referenceEditStart0",31).put("referenceEditEnd0",31)
                .put("hgvscReferenceEditStart0",39).put("hgvscReferenceEditEnd0",39);
        assertEquals("PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE",Pvs1NmdConditions.evaluate(p,input));
        input.put("hgvscReferenceEditStart0",32).put("hgvscReferenceEditEnd0",32);
        assertNull(Pvs1NmdConditions.evaluate(p,input));
        input.put("referenceEditStart0",30).put("referenceEditEnd0",30);
        assertEquals("PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE",Pvs1NmdConditions.evaluate(p,input));
    }

    @ParameterizedTest @ValueSource(strings={"coordinate","source","unknown","empty","range","float","overflow","duplicate","v1"})
    void invalidConditionNeverSilentlyIgnored(String kind) {
        var p=policy("REFERENCE_EVENT_SPAN",1,100);var c=(ObjectNode)p.at("/nmdScope/positionConditions/0");
        switch(kind) {
            case "coordinate" -> c.put("coordinate","AMINO_ACID_GUESS");
            case "source" -> c.putArray("sourceIds").add("missing");
            case "unknown" -> c.put("ignoreIfMissing",true);
            case "empty" -> p.withObject("nmdScope").putArray("positionConditions");
            case "range" -> c.put("minimumInclusive",100);
            case "float" -> c.put("maximumExclusive",100.0);
            case "overflow" -> c.put("maximumExclusive",Long.MAX_VALUE);
            case "duplicate" -> p.withObject("nmdScope").withArray("positionConditions").add(c.deepCopy());
            case "v1" -> p.put("schemaVersion","pvs1-truncating-nmd-policy-v1");
        }
        assertFalse(Pvs1NmdConditions.valid(p));
    }

    @Test void newCompilerCandidateCannotBorrowOriginalReview() throws Exception {
        var d=fixture.nonsense(1,1,31);condition(d.policy,"REFERENCE_EVENT_SPAN",1,100);
        d.policy.withObject("review").put("status","UNREVIEWED");
        assertEquals("PVS1_REVIEWED_POLICY_REQUIRED",run(d).path("evaluationResult").path("reasonCode").asText());
    }

    @Test void allConditionsMustPassAndOldVersionStaysOld() throws Exception {
        var d=fixture.nonsense(1,1,31);
        assertEquals("1.0.4",run(d).at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        condition(d.policy,"REFERENCE_EVENT_SPAN",1,100);
        var second=d.policy.at("/nmdScope/positionConditions/0").deepCopy();((ObjectNode)second).put("conditionId","second").put("minimumInclusive",32);
        d.policy.withObject("nmdScope").withArray("positionConditions").add(second);
        assertEquals("PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE",run(d).at("/evaluation/evaluationResult/reasonCode").asText());
    }

    @Test void broadPositionIntervalCannotOverrideNmdOrMechanismRequirements() throws Exception {
        var escape=fixture.nonsense(1,1,499);condition(escape.policy,"REFERENCE_EVENT_SPAN",1,1000);
        assertEquals("PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED",run(escape).at("/evaluation/evaluationResult/reasonCode").asText());
        var mechanism=fixture.nonsense(1,1,31);condition(mechanism.policy,"REFERENCE_EVENT_SPAN",1,1000);
        mechanism.policy.withObject("mechanism").put("status","UNKNOWN");
        assertEquals("PVS1_LOF_MECHANISM_NOT_ESTABLISHED",run(mechanism).at("/evaluationResult/reasonCode").asText());
    }

    private ObjectNode policy(String coordinate,int min,int max) { var p=mapper.createObjectNode();p.putArray("scientificSources").addObject().put("sourceId","synthetic-source");condition(p,coordinate,min,max);return p; }
    private void condition(ObjectNode p,String coordinate,int min,int max) {
        p.put("schemaVersion",Pvs1NmdConditions.SCHEMA);
        var c=p.withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId","test-position")
                .put("coordinate",coordinate).put("minimumInclusive",min).put("maximumExclusive",max).put("basis","TEST_ONLY explicit interval");
        c.putArray("sourceIds").add("synthetic-source");
    }
    private ObjectNode run(TruncatingNmdPvs1RuntimeTest.Inputs d) {
        d.selection.put("policySha256",adapter.canonicalSha256(d.policy));d.selection.remove("selectionId");
        d.selection.put("selectionId","cspec-selection-sha256-"+adapter.canonicalSha256(d.selection));
        return new TruncatingNmdPvs1Runtime(mapper,fixture.resourceProfile(),d.model).evaluate(d.context,d.candidate,d.policy,d.selection);
    }
}
