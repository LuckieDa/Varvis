package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp1TestMaterials.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp1SegregationCriterionEvaluatorTest.minimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;

class Pp1SegregationBoundaryTest {
    @ParameterizedTest @CsvSource(delimiter=';',value={
        "facts/structure;MULTIGENERATION;FAMILY_STRUCTURE_UNSUPPORTED",
        "facts/parentRelatedness;CONSANGUINEOUS;FAMILY_STRUCTURE_UNSUPPORTED",
        "facts/members/2/independence;MONOZYGOTIC_TWIN;MEMBER_INDEPENDENCE_UNESTABLISHED",
        "facts/members/2/independence;UNKNOWN;MEMBER_INDEPENDENCE_UNESTABLISHED",
        "facts/members/2/fatherId;OTHER_PARENT;PARENT_CHILD_BINDING_MISMATCH",
        "facts/members/0/fatherId;TEST_ONLY_pp1_alpha_child2;PARENT_PEDIGREE_OUTSIDE_SCOPE",
        "facts/members/2/affectedStatus;UNKNOWN;CLINICAL_STATUS_UNESTABLISHED",
        "facts/members/2/detection/status;UNTESTED;DETECTION_INCOMPLETE",
        "facts/members/2/detection/coverage;UNKNOWN;DETECTION_INCOMPLETE",
        "facts/members/2/detection/mosaicism;MOSAIC;DETECTION_INCOMPLETE",
        "facts/members/2/clinical/diagnosis;OTHER_DISEASE;CLINICAL_STATUS_UNESTABLISHED",
        "facts/members/2/clinical/mondoId;MONDO:0940012;MEMBER_DISEASE_MISMATCH",
        "facts/members/2/clinical/mechanism;GAIN_OF_FUNCTION;MEMBER_DISEASE_MISMATCH",
        "facts/members/2/clinical/review/status;UNREVIEWED;REVIEW_REQUIRED",
        "facts/records/2/sampleId;other-sample;RECORD_BINDING_MISMATCH",
        "facts/records/2/geneSymbol;OTHER_GENE;RECORD_BINDING_MISMATCH",
        "facts/records/2/allele/alternate;T;RECORD_BINDING_MISMATCH",
        "facts/records/2/recordFilter;LowQual;RECORD_FILTER_NOT_PASS",
        "facts/records/2/rawSample;0/1:40:60:20,20:LowQual;SAMPLE_FT_NOT_PASS",
        "facts/records/2/rawSample;0/1:40:60:20,20:.;RECORD_FT_REQUIRED",
        "facts/records/2/rawSample;0/1:40:60:20,20:;RECORD_FT_REQUIRED",
        "facts/records/2/rawSample;0/1:40:60:20,20;RECORD_FIELDS_INCOMPLETE",
        "facts/records/2/rawSample;0/1:10:60:5,5:PASS;RECORD_QUALITY_UNSUPPORTED",
        "facts/records/2/rawSample;0/1:40:10:20,20:PASS;RECORD_QUALITY_UNSUPPORTED",
        "facts/records/2/rawSample;0/1:40:60:40,0:PASS;RECORD_QUALITY_UNSUPPORTED",
        "facts/records/2/rawSample;0/1:40:60:20,19:PASS;RECORD_QUALITY_UNSUPPORTED",
        "facts/records/2/rawSample;1/2:40:60:20,20:PASS;RECORD_DIPLOID_BIALLELIC_GT_REQUIRED",
        "facts/records/2/rawSample;0/1:4294967336:60:20,20:PASS;RECORD_INTEGER_INVALID",
        "facts/records/2/observation/genotype;1/0;RECORD_RAW_PROJECTION_CONFLICT",
        "facts/parentage/0/status;UNKNOWN;PARENTAGE_UNCONFIRMED",
        "facts/parentage/0/relationship;ADOPTIVE;PARENTAGE_UNCONFIRMED",
        "facts/parentage/0/childMemberId;other-child;PARENTAGE_BINDING_MISMATCH",
        "segregationReview/rosterStatus;INCOMPLETE;ROSTER_REVIEW_INCOMPLETE",
        "segregationReview/memberReviews/2/clinicalStatus;UNKNOWN;MEMBER_REVIEW_INCOMPLETE",
        "diseaseApplicability/geneDiseaseValidity;UNKNOWN;GENE_DISEASE_VALIDITY_UNESTABLISHED",
        "diseaseApplicability/heterogeneity;HOMOGENEOUS;SIMPLE_AD_METHOD_NOT_APPLICABLE",
        "diseaseApplicability/diagnosticYield;HIGH;SIMPLE_AD_METHOD_NOT_APPLICABLE",
        "diseaseApplicability/phenocopy;UNRESOLVED;SIMPLE_AD_METHOD_NOT_APPLICABLE",
        "diseaseApplicability/linkageRequired;REQUIRED;SIMPLE_AD_METHOD_NOT_APPLICABLE",
        "diseaseApplicability/simpleAdMethod;UNKNOWN;SIMPLE_AD_METHOD_NOT_APPLICABLE",
        "diseaseApplicability/conditionMode;SATISFIED;CONDITIONAL_APPLICABILITY_OUTSIDE_SCOPE",
        "diseaseApplicability/affectedMembers/0/conclusion;UNKNOWN;AFFECTED_DISEASE_REVIEW_CONFLICT",
        "locusAttribution/coverageScope;EXON_ONLY;LOCUS_COVERAGE_OR_ALLOCATION_UNESTABLISHED",
        "locusAttribution/coverageStatus;INCOMPLETE;LOCUS_COVERAGE_OR_ALLOCATION_UNESTABLISHED",
        "locusAttribution/allocation;SHARED;LOCUS_COVERAGE_OR_ALLOCATION_UNESTABLISHED",
        "locusAttribution/method;OTHER_METHOD;LOCUS_METHOD_CONFLICT",
        "locusAttribution/candidates/0/disposition;EXCLUDED_INDEPENDENTLY;TARGET_ATTRIBUTION_CONFLICT",
        "locusAttribution/candidates/1/disposition;UNRESOLVED;OTHER_CANDIDATE_UNRESOLVED",
        "locusAttribution/candidates/1/hgncId;HGNC:940012;CANDIDATE_GENE_MISMATCH",
        "locusAttribution/usedEvidenceStatus;INCOMPLETE;USED_EVIDENCE_INCOMPLETE",
        "locusAttribution/usedEvidence/0/status;APPLIED;SHARED_EVIDENCE_OCCUPIED_OR_CONFLICT",
        "locusAttribution/usedEvidence/1/status;APPLIED;SHARED_EVIDENCE_OCCUPIED_OR_CONFLICT",
        "locusAttribution/usedEvidence/2/status;CONFLICT;SHARED_EVIDENCE_OCCUPIED_OR_CONFLICT"
    })
    void fullyReviewedButIneligibleFacts(String path,String value,String expected)throws Exception{
        var r=base("alpha-2");var o=observation(r);int slash=path.lastIndexOf('/');obj(o,"/"+path.substring(0,slash)).put(path.substring(slash+1),value);reissue(r,PARTS.toArray(String[]::new));
        var result=evaluate(r);artifact("refusal-"+path.replace('/','_')+"-"+value.replaceAll("[^A-Za-z0-9]","_"),r,result);minimal(result,"PP1_"+expected);
    }
    @ParameterizedTest @ValueSource(strings={"memberId","sourcePersonId","sampleId","recordId","locator"})
    void nfcDuplicateIdentities(String field)throws Exception{
        var r=base("alpha-2");var f=obj(observation(r),"/facts");boolean rec=field.equals("recordId")||field.equals("locator");var items=f.path(rec?"records":"members");
        ((ObjectNode)items.get(0)).put(field,"TEST_é");((ObjectNode)items.get(1)).put(field,"TEST_e\u0301");reissue(r,"facts");var result=evaluate(r);artifact("nfc-duplicate-"+field,r,result);minimal(result,rec?"PP1_RECORD_MEMBERSHIP_OR_DUPLICATE":"PP1_MEMBERS_NOT_DISTINCT");
    }
    @ParameterizedTest @ValueSource(strings={"0/1","1/0","0|1","1|0"})
    void allHeterozygousGtRepresentations(String gt)throws Exception{var r=base("beta-1");for(var record:observation(r).at("/facts/records"))if(record.at("/observation/call").asText().equals("HET_ALT"))call((ObjectNode)record,gt);reissue(r,"facts");var result=evaluate(r);artifact("gt-"+gt.replace('/','s').replace('|','p'),r,result);assertEquals(1,result.path("appliedPoints").asInt(),result.at("/evaluationResult/reasonCode").asText());}
    @Test void homozygousReferencePhasedSupported()throws Exception{var r=base("alpha-2-healthy");for(var n:observation(r).at("/facts/records"))if(n.at("/observation/call").asText().equals("HOM_REF"))call((ObjectNode)n,"0|0");reissue(r,"facts");assertEquals(2,evaluate(r).path("appliedPoints").asInt());}
    @ParameterizedTest @ValueSource(strings={"affected-reference","unaffected-carrier","homozygous-alt","both-parents-het"})
    void clinicalGenotypeConflicts(String kind)throws Exception{var r=base("alpha-2-healthy");var records=observation(r).at("/facts/records");int index=switch(kind){case "unaffected-carrier"->4;case "both-parents-het"->0;default->2;};call((ObjectNode)records.get(index),kind.equals("affected-reference")?"0/0":kind.equals("homozygous-alt")?"1/1":"0/1");reissue(r,"facts");var result=evaluate(r);artifact(kind,r,result);minimal(result,kind.equals("both-parents-het")?"PP1_PARENT_GENOTYPE_CLINICAL_CONFLICT":"PP1_CHILD_GENOTYPE_CLINICAL_CONFLICT");}
    @Test void duplicateObservationsNeverAggregate()throws Exception{var r=base("alpha-1");var duplicate=observation(r).deepCopy();obj(duplicate,"/facts").put("observationId","OTHER_OBSERVATION");((ArrayNode)r.path("observations")).add(duplicate);minimal(evaluate(r),"PP1_SINGLE_OBSERVATION_REQUIRED_NO_AGGREGATION");}
    @Test void twoProbandsAndSixChildrenReject()throws Exception{
        var r=base("alpha-2");obj(observation(r),"/facts/members/3").put("proband",true);reissue(r,"facts");minimal(evaluate(r),"PP1_NUCLEAR_FAMILY_CARDINALITY_INVALID");
        r=base("alpha-5");var members=(ArrayNode)observation(r).at("/facts/members");var sixth=((ObjectNode)members.get(6)).deepCopy().put("memberId","child6").put("sourcePersonId","person6").put("sampleId","sample6");members.add(sixth);reissue(r,"facts");minimal(evaluate(r),"PP1_NUCLEAR_FAMILY_CARDINALITY_INVALID");
    }
    @Test void sourceRosterCannotDropChild()throws Exception{var r=base("alpha-2");((ArrayNode)observation(r).at("/facts/members")).remove(3);pinsOnly(r);var result=evaluate(r);artifact("deleted-child-original-source",r,result);minimal(result,"PP1_SOURCE_FACTS_MISMATCH");}
    @Test void rosterStillRequiredAfterNewFactsIssuance()throws Exception{var r=base("alpha-2");var f=obj(observation(r),"/facts");((ArrayNode)f.path("members")).remove(3);((ArrayNode)f.path("records")).remove(3);((ArrayNode)f.path("parentage")).remove(3);((ArrayNode)f.path("parentage")).remove(2);reissue(r,"facts");minimal(evaluate(r),"PP1_ROSTER_REVIEW_MISMATCH");}
    @Test void memberReorderPreservesEventsAndCounting()throws Exception{var r=base("beta-3");var expected=evaluate(r);var f=obj(observation(r),"/facts");for(String field:List.of("members","records","parentage")){List<JsonNode> items=new ArrayList<>();f.path(field).forEach(items::add);Collections.reverse(items);f.set(field,M.valueToTree(items));}reissue(r,"facts");var actual=evaluate(r);assertEquals(expected.path("transmissionEvents"),actual.path("transmissionEvents"));assertEquals(expected.path("appliedPoints"),actual.path("appliedPoints"));}
    @Test void countCannotBeTakenFromDeclarationOrNarrowedInteger()throws Exception{for(long value:new long[]{0,1,5,4294967298L,-4294967294L}){var r=base("alpha-2");obj(observation(r),"/segregationReview").put("declaredCount",value);reissue(r,"segregationReview");minimal(evaluate(r),"PP1_DECLARED_COUNT_CONFLICT");}}
    @Test void independentValidityRequiredAndConditionsNeverOverridden()throws Exception{var r=base("alpha-1");obj(observation(r),"/diseaseApplicability").put("validityIndependentOfFamily",false);reissue(r,"diseaseApplicability");minimal(evaluate(r),"PP1_GENE_DISEASE_VALIDITY_UNESTABLISHED");r=base("alpha-1");((ArrayNode)observation(r).at("/diseaseApplicability/conditions")).addObject().put("memberId",r.at("/context/patientContext/patientContextId").asText()).put("kind","AGE").put("requirement","18").put("patientFact","10").put("conclusion","SATISFIED");reissue(r,"diseaseApplicability");minimal(evaluate(r),"PP1_CONDITIONAL_APPLICABILITY_OUTSIDE_SCOPE");}
    @Test void emptyCandidateAndMissingOverlapRosterReject()throws Exception{var r=base("alpha-1");((ArrayNode)observation(r).at("/locusAttribution/candidates")).removeAll();reissue(r,"locusAttribution");minimal(evaluate(r),"PP1_SOLE_TARGET_REQUIRED");r=base("alpha-1");((ArrayNode)observation(r).at("/locusAttribution/usedEvidence")).remove(1);reissue(r,"locusAttribution");minimal(evaluate(r),"PP1_USED_EVIDENCE_INCOMPLETE");}
}
