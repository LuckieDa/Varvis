package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class Pm3LiteratureAssessmentTest {
    final ObjectMapper json=new ObjectMapper();
    final Pm3LiteratureRaritySupplyTest fixture=new Pm3LiteratureRaritySupplyTest();
    ObjectNode request(){var r=fixture.request(fixture.row(true));r.putObject("target").put("geneSymbol","PAH");return r;}
    ObjectNode caseRow(String pmcid,String label,String family) {
        var row=fixture.row(true);row.withObject("/reportedObservation").put("phase","UNKNOWN")
                .putArray("facts").addObject().putObject("citation").put("unitId","table-cell-"+label);
        var rarity=row.putObject("rarityQualification").putObject("target").put("qualification","VERIFIED");
        rarity.set("queryAllele",row.at("/identities/target/allele"));
        var c=json.createObjectNode().put("caseLabel",label);
        c.putArray("resolvedObservations").add(row);
        c.putObject("primarySourceQualification").put("qualification","VERIFIED");
        c.putObject("occurrenceQualification").put("schemaVersion","pm3-source-occurrence-v1")
                .put("qualification","VERIFIED").put("pmcid",pmcid).put("caseLabel",label).put("familyIdentity",family);
        return c;
    }
    ObjectNode paper(String pmcid,ObjectNode... cases) {
        var p=json.createObjectNode().put("pmcid",pmcid).put("schemaVersion","pm3-paper-process-v1");
        var rows=p.putObject("observations").putArray("cases");for(var c:cases)rows.add(c);return p;
    }
    ObjectNode run(ObjectNode paper){return new Pm3LiteratureAssessment(json).assess(paper,request());}
    int points(ObjectNode result){return result.at("/diseaseAssessments/0/calculation/quarterPoints").asInt();}
    @Test void originalSingleHomoCaseDoesNotRequireFamilyNumberOrPartner() {
        var r=run(paper("PMC123",caseRow("PMC123","1",null)));
        assertEquals(2,points(r),r.toPrettyString());assertEquals("SUPPORTING",r.at("/diseaseAssessments/0/calculation/strength").asText());
        assertFalse(r.path("businessPublishable").asBoolean());assertFalse(r.path("pm3FinalBusinessExecution").asBoolean());
        assertEquals("SPECIFICATION_SELECTION_PENDING",r.path("policyApplicability").asText());
    }
    @Test void duplicateLabelsAndRelatedFamilyAreNotAdditionalProbands() {
        var c=caseRow("PMC123","1","F1");
        assertEquals(2,points(run(paper("PMC123",c,c.deepCopy()))));
        assertEquals(2,points(run(paper("PMC123",c,caseRow("PMC123","2","F1")))));
        assertEquals(2,points(run(paper("PMC123",caseRow("PMC123","1",null),caseRow("PMC123","2",null)))));
    }
    @Test void distinctSourceFamiliesOrExplicitUnrelatedCasesAccumulateWithHomoCap() {
        var r=run(paper("PMC123",caseRow("PMC123","1","F1"),caseRow("PMC123","2","F2"),caseRow("PMC123","3","F3")));
        assertEquals(4,points(r),r.toPrettyString());
        var a=caseRow("PMC123","1",null);var b=caseRow("PMC123","2",null);
        for(var c:java.util.List.of(a,b))c.withObject("/occurrenceQualification").putArray("unrelatedGroups").addObject()
                .putArray("caseLabels").add("1").add("2");
        assertEquals(4,points(run(paper("PMC123",a,b))));
    }
    @Test void parentAndContinuedPaperDoNotCountTheSameCaseTwice() {
        var parent=paper("PMC123",caseRow("PMC123","1","F1"));
        var child=parent.deepCopy();child.putObject("cacheReuse").putArray("reviewChunks").add(43);
        var combined=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1");
        combined.putArray("papers").add(parent).add(child);
        assertEquals(points(run(parent)),points(run(combined)));
        assertEquals(1,run(combined).at("/diseaseAssessments/0/selectedOccurrenceIds").size());
    }
    @Test void sourceReviewCompletionIsNotAContradictionOfPreviouslyUnknownOrigin() {
        var partial=paper("PMC123",caseRow("PMC123","1",null));
        partial.withObject("/observations/cases/0/primarySourceQualification").put("qualification","UNKNOWN");
        partial.withObject("/observations/cases/0/occurrenceQualification").put("qualification","UNKNOWN");
        var reviewed=paper("PMC123",caseRow("PMC123","1",null));
        var combined=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1");combined.putArray("papers").add(partial).add(reviewed);
        assertEquals(2,points(run(combined)));assertEquals("SUPPORTING",run(combined).at("/diseaseAssessments/0/calculation/strength").asText());
        reviewed.withObject("/observations/cases/0/primarySourceQualification").put("qualification","CONFLICT");
        assertEquals(0,points(run(combined)));
    }
    @Test void differentPapersAndDifferentDiseasesAreNotBlindlySummed() {
        var d=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1");
        d.putArray("papers").add(paper("PMC123",caseRow("PMC123","1","F1"),caseRow("PMC123","2","F2")))
                .add(paper("PMC456",caseRow("PMC456","1","F9")));
        var r=run(d);assertEquals(4,points(r),r.toPrettyString());
        assertEquals("PM3_CROSS_PAPER_OVERLAP_NOT_RECONCILED",r.at("/diseaseAssessments/0/withheldOccurrences/0/reasonCode").asText());
        var foreign=caseRow("PMC789","1",null);foreign.withObject("/resolvedObservations/0/diseaseContext").put("diseaseId","MONDO:9999999");
        d.withArray("/papers").add(paper("PMC789",foreign));assertEquals(2,run(d).path("diseaseAssessments").size());
    }
    @Test void failedRarityOrMismatchedSelectedAlleleCannotBecomeAPositiveResult() {
        var c=caseRow("PMC123","1",null);c.withObject("/resolvedObservations/0/rarityQualification/target").put("qualification","FAILED");
        assertEquals(0,points(run(paper("PMC123",c))));
        c=caseRow("PMC123","1",null);c.withObject("/resolvedObservations/0/identities/target/allele").put("position",1);
        var r=run(paper("PMC123",c));assertEquals(0,points(r));
        assertEquals("CONFLICT",r.at("/diseaseAssessments/0/calculation/contributions/0/qualificationStatus").asText());
    }
    @Test void unresolvedTwoAlleleIdentitiesProduceMissingInformationInsteadOfCanonicalizationFailure() {
        var c=caseRow("PMC123","anonymous-row",null);var row=(ObjectNode)c.at("/resolvedObservations/0");
        row.withObject("/reportedObservation").put("kind","REPORTED_TWO_ALLELES_PHASE_UNRESOLVED");
        row.putObject("identities");row.putObject("rarityQualification");
        var r=run(paper("PMC123",c));
        assertEquals(0,points(r));
        assertEquals("MISSING_INFORMATION",r.at("/diseaseAssessments/0/calculation/contributions/0/qualificationStatus").asText());
        assertTrue(r.at("/diseaseAssessments/0/calculation/contributions/0/requirements").toString().contains("PM3_PARTNER_IDENTITY_UNRESOLVED"));
    }
    @Test void verifiedLabelWithoutTargetAlleleCannotAwardHomozygousPoints() {
        var c=caseRow("PMC123","1",null);
        c.withObject("/resolvedObservations/0/identities/target").remove("allele");
        c.withObject("/resolvedObservations/0/rarityQualification").remove("target");
        var r=run(paper("PMC123",c));
        assertEquals(0,points(r));
        assertEquals("MISSING_INFORMATION",r.at("/diseaseAssessments/0/calculation/contributions/0/qualificationStatus").asText());
    }
    @Test void sourceAdapterPreservesPhaseAndPartnerClassificationBranches() {
        for(var scenario:java.util.List.of(new String[]{"TRANS","PATHOGENIC","4"},new String[]{"UNKNOWN","PATHOGENIC","2"},
                new String[]{"UNKNOWN","LIKELY_PATHOGENIC","1"},new String[]{"TRANS","VUS","1"},new String[]{"UNKNOWN","VUS","0"})) {
            var c=caseRow("PMC123","1",null);var row=(ObjectNode)c.at("/resolvedObservations/0");
            row.withObject("/reportedObservation").put("kind","REPORTED_TWO_ALLELES_PHASE_UNRESOLVED").put("phase",scenario[0]);
            row.withObject("/identities").set("partner",fixture.row(false).at("/identities/partner"));
            row.withObject("/rarityQualification").putObject("partner").put("qualification","VERIFIED")
                    .set("queryAllele",row.at("/identities/partner/allele"));
            row.putObject("partnerQualification").put("classification",scenario[1]).put("independentClassification","VERIFIED");
            var r=run(paper("PMC123",c));assertEquals(Integer.parseInt(scenario[2]),points(r),r.toPrettyString());
            row.withObject("/partnerQualification").put("independentClassification","UNKNOWN");
            assertEquals(0,points(run(paper("PMC123",c))));
        }
    }
    @Test void explicitUnaffectedAndConflictRemainDistinctFromMissingness() {
        var c=caseRow("PMC123","1",null);c.withObject("/resolvedObservations/0/reportedObservation").put("reportedAffectedStatus","UNAFFECTED");
        var r=run(paper("PMC123",c));assertEquals("NOT_APPLICABLE",r.at("/diseaseAssessments/0/calculation/status").asText());
        c=caseRow("PMC123","1",null);c.withObject("/primarySourceQualification").put("qualification","CONFLICT");
        r=run(paper("PMC123",c));assertEquals("CONFLICT",r.at("/diseaseAssessments/0/calculation/contributions/0/qualificationStatus").asText());
    }
}
