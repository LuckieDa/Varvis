package org.nbscn.varvis.variantanalysis.provider;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp1TestMaterials.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp1SegregationCriterionEvaluatorTest.minimal;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;

class Pp1SegregationScopeTest {
    private static void contextIdentity(ObjectNode r)throws Exception{
        var c=r.path("context");var p=M.createObjectNode().put("domain","varvis-evidence-context-v3").put("schemaVersion","evidence-context-v3");for(String k:List.of("dataClass","scope","moi","patientContext"))p.set(k,c.path(k));p.set("alleleId",c.at("/variant/alleleId"));p.set("hgncId",c.at("/gene/hgncId"));p.set("mondoId",c.at("/disease/mondoId"));obj(r,"/context").put("contextId","context-sha256-"+hash(p));
    }
    @Test void changingProbandDoesNotAddTransmission()throws Exception{
        var r=base("alpha-3");var f=obj(observation(r),"/facts");String oldId=f.at("/members/3/memberId").asText(),newId="patient-context-test-pp1-second";
        // All exact identity references are explicitly reissued, never inferred by production.
        r=(ObjectNode)M.readTree(M.writeValueAsString(r).replace(oldId,newId));f=obj(observation(r),"/facts");obj(f,"/members/2").put("proband",false);obj(f,"/members/3").put("proband",true);obj(r,"/context/patientContext").put("patientContextId",newId);contextIdentity(r);
        for(String part:PARTS){var b=obj(observation(r),"/"+part+"/binding");b.put("patientContextId",newId);b.set("contextId",r.at("/context/contextId"));}reissue(r,PARTS.toArray(String[]::new));var result=evaluate(r);assertEquals(3,result.path("appliedPoints").asInt());assertEquals(3,result.path("transmissionEvents").size());
    }
    @Test void observationIdNeverDefinesEventKey()throws Exception{var r=base("beta-2");var before=evaluate(r);obj(observation(r),"/facts").put("observationId","OTHER_OBSERVATION");for(String part:PARTS)obj(observation(r),"/"+part+"/binding").put("observationId","OTHER_OBSERVATION");reissue(r,PARTS.toArray(String[]::new));assertEquals(before.path("transmissionEvents"),evaluate(r).path("transmissionEvents"));}
    @ParameterizedTest @ValueSource(strings={"hgncId","mondoId","moi","familyContextId","patientContextId"})
    void newValidContextStillRequiresExactMaterialBinding(String field)throws Exception{
        var r=base("alpha-1");switch(field){case "hgncId"->obj(r,"/context/gene").put(field,"HGNC:940099");case "mondoId"->obj(r,"/context/disease").put(field,"MONDO:0940099");case "moi"->obj(r,"/context").put(field,"AUTOSOMAL_RECESSIVE");case "familyContextId"->obj(r,"/context/patientContext").put(field,"family-context-test-other");default->obj(r,"/context/patientContext").put(field,"patient-context-test-other");}contextIdentity(r);minimal(evaluate(r),"PP1_CONTEXT_BINDING_MISMATCH");
    }
    @Test void invalidBuildAndAlteredAlleleFailContextIdentity()throws Exception{for(String key:List.of("assembly","alternate")){var r=base("alpha-1");obj(r,"/context/variant/normalizedAllele").put(key,key.equals("assembly")?"GRCh37":"T");minimal(evaluate(r),"PP1_CONTEXT_IDENTITY_INVALID");}}
    @ParameterizedTest @ValueSource(strings={"members","records","parentage"})
    void duplicatedRowsNeverIncreaseCount(String field)throws Exception{var r=base("alpha-2");var a=(ArrayNode)observation(r).at("/facts/"+field);a.add(a.get(0).deepCopy());reissue(r,"facts");minimal(evaluate(r),switch(field){case "members"->"PP1_MEMBERS_NOT_DISTINCT";case "records"->"PP1_RECORD_MEMBERSHIP_OR_DUPLICATE";default->"PP1_PARENTAGE_DUPLICATE";});}
    @Test void missingRecordAndParentageCannotBeInferred()throws Exception{var r=base("alpha-1");((ArrayNode)observation(r).at("/facts/records")).remove(0);reissue(r,"facts");minimal(evaluate(r),"PP1_MEMBER_RECORD_MISSING");r=base("alpha-1");((ArrayNode)observation(r).at("/facts/parentage")).remove(0);reissue(r,"facts");minimal(evaluate(r),"PP1_PARENTAGE_ROSTER_INCOMPLETE");}
    @Test void noFtAndShiftedFormatKeepRawSemantics()throws Exception{
        var r=base("alpha-1");var rec=obj(observation(r),"/facts/records/2");rec.put("format","GT:DP:GQ:AD").put("rawSample","0/1:40:60:20,20");reissue(r,"facts");minimal(evaluate(r),"PP1_RECORD_FT_REQUIRED");
        r=base("alpha-1");rec=obj(observation(r),"/facts/records/2");rec.put("format","FT:AD:GQ:GT:DP").put("rawSample","PASS:20,20:60:0/1:40");reissue(r,"facts");assertEquals(1,evaluate(r).path("appliedPoints").asInt());
    }
    @Test void rawQualityProjectionAndLongAltIndexCannotWrap()throws Exception{for(String field:List.of("targetAltIndex","ploidy","depth","genotypeQuality")){var r=base("alpha-1");obj(observation(r),"/facts/records/2/observation").put(field,4294967296L+(field.equals("targetAltIndex")?1:field.equals("ploidy")?2:40));reissue(r,"facts");minimal(evaluate(r),"PP1_RECORD_RAW_PROJECTION_CONFLICT");}}
    @Test void immutableObservationKeepsCompleteRawFacts()throws Exception{var r=base("alpha-1");var f=obj(observation(r),"/facts");var model=new NuclearFamilyObservation(f);var snapshot=model.snapshot();f.removeAll();assertEquals(snapshot,model.snapshot());snapshot.removeAll();assertFalse(model.snapshot().isEmpty());}
    @Test void duplicateCandidatesAndSourceReferencesRejected()throws Exception{
        var r=base("alpha-1");var candidates=(ArrayNode)observation(r).at("/locusAttribution/candidates");candidates.add(((ObjectNode)candidates.get(0)).deepCopy().put("candidateId","ANOTHER_CANDIDATE"));reissue(r,"locusAttribution");minimal(evaluate(r),"PP1_CANDIDATE_DUPLICATE");
        r=base("alpha-1");obj(observation(r),"/facts/members/0/clinical").putArray("sourceIds").add("UNKNOWN_SOURCE");reissue(r,"facts");minimal(evaluate(r),"PP1_SOURCE_REFERENCE_UNRESOLVED");
        r=base("alpha-1");obj(observation(r),"/facts/sources/0").put("acquiredAt","not-a-date");pinsOnly(r);minimal(evaluate(r),"PP1_SOURCE_IDENTITY_INVALID");
    }
}
