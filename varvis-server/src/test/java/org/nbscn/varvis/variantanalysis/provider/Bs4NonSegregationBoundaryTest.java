package org.nbscn.varvis.variantanalysis.provider;

import static org.nbscn.varvis.variantanalysis.provider.Bs4TestMaterials.*;
import static org.nbscn.varvis.variantanalysis.provider.Bs4NonSegregationCriterionEvaluatorTest.minimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Bs4NonSegregationBoundaryTest {
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
        "nonSegregationReview/rosterStatus;INCOMPLETE;ROSTER_REVIEW_INCOMPLETE",
        "nonSegregationReview/memberReviews/2/clinicalStatus;UNKNOWN;MEMBER_REVIEW_INCOMPLETE",
        "diseaseApplicability/geneDiseaseValidity;UNKNOWN;GENE_DISEASE_VALIDITY_UNESTABLISHED",
        "diseaseApplicability/linkageRequired;REQUIRED;FORMAL_LINKAGE_OUTSIDE_SCOPE",
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
        var result=evaluate(r);artifact("refusal-"+path.replace('/','_')+"-"+value.replaceAll("[^A-Za-z0-9]","_"),r,result);minimal(result,"BS4_"+expected);
    }

}
