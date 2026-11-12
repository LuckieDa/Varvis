package org.nbscn.varvis.variantanalysis.provider;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Bs4TestMaterials.*;
import static org.nbscn.varvis.variantanalysis.provider.Bs4NonSegregationCriterionEvaluatorTest.minimal;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;

class Bs4NonSegregationScopeTest {
    private static void contextIdentity(ObjectNode r)throws Exception{
        var c=r.path("context");var p=M.createObjectNode().put("domain","varvis-evidence-context-v3").put("schemaVersion","evidence-context-v3");for(String k:List.of("dataClass","scope","moi","patientContext"))p.set(k,c.path(k));p.set("alleleId",c.at("/variant/alleleId"));p.set("hgncId",c.at("/gene/hgncId"));p.set("mondoId",c.at("/disease/mondoId"));obj(r,"/context").put("contextId","context-sha256-"+hash(p));
    }
    @Test void observationIdNeverDefinesEventKey()throws Exception{var r=base("beta-2");var before=evaluate(r);obj(observation(r),"/facts").put("observationId","OTHER_OBSERVATION");for(String part:PARTS)obj(observation(r),"/"+part+"/binding").put("observationId","OTHER_OBSERVATION");reissue(r,PARTS.toArray(String[]::new));assertEquals(before.path("nonSegregationEvents"),evaluate(r).path("nonSegregationEvents"));}
    @ParameterizedTest @ValueSource(strings={"hgncId","mondoId","moi","familyContextId","patientContextId"})
    void newValidContextStillRequiresExactMaterialBinding(String field)throws Exception{
        var r=base("alpha-2");switch(field){case "hgncId"->obj(r,"/context/gene").put(field,"HGNC:940099");case "mondoId"->obj(r,"/context/disease").put(field,"MONDO:0940099");case "moi"->obj(r,"/context").put(field,"AUTOSOMAL_RECESSIVE");case "familyContextId"->obj(r,"/context/patientContext").put(field,"family-context-test-other");default->obj(r,"/context/patientContext").put(field,"patient-context-test-other");}contextIdentity(r);minimal(evaluate(r),"BS4_CONTEXT_BINDING_MISMATCH");
    }
    @Test void invalidBuildAndAlteredAlleleFailContextIdentity()throws Exception{for(String key:List.of("assembly","alternate")){var r=base("alpha-2");obj(r,"/context/variant/normalizedAllele").put(key,key.equals("assembly")?"GRCh37":"T");minimal(evaluate(r),"BS4_CONTEXT_IDENTITY_INVALID");}}
    @ParameterizedTest @ValueSource(strings={"members","records","parentage"})
    void duplicatedRowsNeverIncreaseCount(String field)throws Exception{var r=base("alpha-2");var a=(ArrayNode)observation(r).at("/facts/"+field);a.add(a.get(0).deepCopy());reissue(r,"facts");minimal(evaluate(r),switch(field){case "members"->"BS4_MEMBERS_NOT_DISTINCT";case "records"->"BS4_RECORD_MEMBERSHIP_OR_DUPLICATE";default->"BS4_PARENTAGE_DUPLICATE";});}
    @Test void missingRecordAndParentageCannotBeInferred()throws Exception{var r=base("alpha-2");((ArrayNode)observation(r).at("/facts/records")).remove(0);reissue(r,"facts");minimal(evaluate(r),"BS4_MEMBER_RECORD_MISSING");r=base("alpha-2");((ArrayNode)observation(r).at("/facts/parentage")).remove(0);reissue(r,"facts");minimal(evaluate(r),"BS4_PARENTAGE_ROSTER_INCOMPLETE");}
    @Test void noFtAndShiftedFormatKeepRawSemantics()throws Exception{
        var r=base("alpha-2");var rec=obj(observation(r),"/facts/records/2");rec.put("format","GT:DP:GQ:AD").put("rawSample","0/1:40:60:20,20");reissue(r,"facts");minimal(evaluate(r),"BS4_RECORD_FT_REQUIRED");
        r=base("alpha-2");rec=obj(observation(r),"/facts/records/2");rec.put("format","FT:AD:GQ:GT:DP").put("rawSample","PASS:20,20:60:0/1:40");reissue(r,"facts");assertEquals(-4,evaluate(r).path("appliedPoints").asInt());
    }
    @Test void rawQualityProjectionAndLongAltIndexCannotWrap()throws Exception{for(String field:List.of("targetAltIndex","ploidy","depth","genotypeQuality")){var r=base("alpha-2");obj(observation(r),"/facts/records/2/observation").put(field,4294967296L+(field.equals("targetAltIndex")?1:field.equals("ploidy")?2:40));reissue(r,"facts");minimal(evaluate(r),"BS4_RECORD_RAW_PROJECTION_CONFLICT");}}
    @Test void immutableObservationKeepsCompleteRawFacts()throws Exception{var r=base("alpha-2");var f=obj(observation(r),"/facts");var model=new NuclearFamilyObservation(f);var snapshot=model.snapshot();f.removeAll();assertEquals(snapshot,model.snapshot());snapshot.removeAll();assertFalse(model.snapshot().isEmpty());}
    @Test void duplicateCandidatesAndSourceReferencesRejected()throws Exception{
        var r=base("alpha-2");var candidates=(ArrayNode)observation(r).at("/locusAttribution/candidates");candidates.add(((ObjectNode)candidates.get(0)).deepCopy().put("candidateId","ANOTHER_CANDIDATE"));reissue(r,"locusAttribution");minimal(evaluate(r),"BS4_CANDIDATE_DUPLICATE");
        r=base("alpha-2");obj(observation(r),"/facts/members/0/clinical").putArray("sourceIds").add("UNKNOWN_SOURCE");reissue(r,"facts");minimal(evaluate(r),"BS4_SOURCE_REFERENCE_UNRESOLVED");
        r=base("alpha-2");obj(observation(r),"/facts/sources/0").put("acquiredAt","not-a-date");pinsOnly(r);minimal(evaluate(r),"BS4_SOURCE_IDENTITY_INVALID");
    }
}
