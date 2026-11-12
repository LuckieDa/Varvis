package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;

/** Synthetic source/model fixtures; actual scoring, batch projection and reuse. */
class Pm3SelectedBatchTest {
    final ObjectMapper json=new ObjectMapper();
    final Pm3LiteratureAssessmentTest f=new Pm3LiteratureAssessmentTest();
    ObjectNode paper() {
        var c=f.caseRow("PMC123","1",null);var row=(ObjectNode)c.at("/resolvedObservations/0");
        T06Pm3PublishedConditionsTest.general(row);
        return f.paper("PMC123",c);
    }
    ObjectNode context(ObjectNode paper) {
        var row=paper.at("/observations/cases/0/resolvedObservations/0");
        var c=json.createObjectNode().put("contextId","context-test").put("moi","AUTOSOMAL_RECESSIVE");
        c.putObject("scope").put("analysisDomain","GERMLINE");
        c.putObject("variant").set("evidenceQueryAllele",row.at("/identities/target/allele"));
        c.putObject("gene").put("symbol","PAH").set("hgncId",row.at("/identities/target/hgncId"));
        c.putObject("disease").set("mondoId",row.at("/diseaseContext/diseaseId"));return c;
    }
    Pm3SelectedBatch batch(ObjectNode paper) {
        return batch(paper,doc->{});
    }
    Pm3SelectedBatch batch(ObjectNode paper,java.util.function.Consumer<ObjectNode> document) {
        var snapshot=json.createObjectNode().put("schemaVersion","pm3-business-source-snapshot-v1").put("analysisId","analysis");
        var doc=snapshot.putArray("documents").addObject().put("taskId","a".repeat(64));
        doc.set("request",f.request());doc.set("result",paper);
        document.accept(doc);
        snapshot.put("snapshotSha256",Pm3SourceDigest.sha256(json,snapshot));
        var module=new Pm3SelectedBatch(json,new Pm3SelectedBatch.Source(){
            public ObjectNode identity(){return json.createObjectNode().put("testOnly",true);}
            public ObjectNode capture(String a,ObjectNode b){return snapshot;}
        });
        module.prepare("analysis",json.createObjectNode());return module;
    }
    @Test void continuationDoesNotRepeatIdenticalFactsButKeepsChangedSourceDetails() {
        var p=paper();var ctx=context(p);
        var child=p.deepCopy().put("completedReviewChunks",2);
        var discovery=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1");
        discovery.putArray("papers").add(p).add(child);
        var baseline=batch(p).evaluateContext(ctx);
        var module=batch(discovery);var result=module.evaluateContext(ctx);
        assertEquals(1,result.at("/publicSummary/caseFacts").size());
        assertEquals(2,result.at("/publicSummary/paperCoverage").size());
        assertEquals(baseline.path("evaluationResult"),result.path("evaluationResult"));
        assertEquals(baseline.at("/publicSummary/assessment/calculation/quarterPoints"),
                result.at("/publicSummary/assessment/calculation/quarterPoints"));
        assertEquals(discovery,module.originals().at("/documents/0/result"));
        child.withObject("/observations/cases/0").putObject("supplementContext").put("source","additional source");
        assertEquals(2,batch(discovery).evaluateContext(ctx).at("/publicSummary/caseFacts").size());
    }
    @Test void coveredPm3TaskReferencesKeepCapturedPinsWithoutInventingDiseaseOrLiveEligibility() {
        var paper=paper();var module=batch(paper,doc->{
            doc.put("resultSha256","b".repeat(64)).put("privatePath","must-not-export");
            doc.withObject("/request").put("selectionSha256","c".repeat(64)).put("pmcid","PMC123");
        });
        var result=module.evaluateContext(context(paper));
        var references=result.at("/publicSummary/sourceTaskReferences");assertEquals(1,references.size());
        var reference=references.path(0);
        assertEquals("PM3_LITERATURE",reference.path("kind").asText());
        assertEquals("a".repeat(64),reference.path("taskId").asText());
        assertEquals("b".repeat(64),reference.path("capturedResultSha256").asText());
        assertEquals("c".repeat(64),reference.path("selectionSha256").asText());
        assertEquals("analysis",reference.path("analysisId").asText());
        assertEquals("GENE_AND_SELECTED_ALLELE_NOT_HYPOTHESIS_SPECIFIC",reference.path("bindingScope").asText());
        assertFalse(reference.has("hypothesisId"));assertFalse(reference.has("canContinue"));assertFalse(reference.has("status"));
        assertFalse(result.path("publicSummary").toString().contains("must-not-export"));
        assertEquals(batch(paper).evaluateContext(context(paper)).path("evaluationResult"),result.path("evaluationResult"));
    }
    @Test void missingTaskPinsAndDifferentTargetCannotCreateAnOpenableReference() {
        var paper=paper();
        assertTrue(batch(paper).evaluateContext(context(paper)).at("/publicSummary/sourceTaskReferences").isEmpty());
        var other=batch(paper,doc->{
            doc.put("resultSha256","b".repeat(64));
            doc.withObject("/request").put("selectionSha256","c".repeat(64));
            doc.withObject("/request/target").put("geneSymbol","OTHER_GENE");
        });
        var result=other.evaluateContext(context(paper));
        assertTrue(result.at("/publicSummary/sourceTaskReferences").isEmpty());
        assertTrue(result.at("/publicSummary/paperCoverage").isEmpty());
        assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());
    }
    @Test void sourceIdentityAndDiseaseMappingProofSurvivePublicProjectionWithoutPrivatePaths() throws Exception {
        var p=paper();var row=(ObjectNode)p.at("/observations/cases/0/resolvedObservations/0");
        var identity=(ObjectNode)row.at("/identities/target");
        identity.put("coding","c.2C>G").put("transcript","NM_000001.1");
        identity.putObject("resourcePins").put("privatePath","must-not-export");
        identity.putArray("sourceProteinCrosswalk").addObject().put("sourceSha256","a".repeat(64));
        identity.putObject("sourceTranscriptLink").put("transcript","NM_000001.1")
                .putArray("clinvarMatches").addObject().put("vcv","VCV000000123.1").put("sourceSha256","c".repeat(64));
        ((ObjectNode)row.path("diseaseContext")).putObject("diseaseNameMapping").put("status","CHECKED");
        row.putArray("issues");
        var r=batch(p).evaluateContext(context(p));
        assertEquals("c.2C>G",r.at("/publicSummary/caseFacts/0/sourceIdentityLinks/target/coding").asText());
        assertEquals("CHECKED",r.at("/publicSummary/caseFacts/0/diseaseNameMapping/status").asText());
        assertEquals(identity.path("sourceTranscriptLink"),r.at("/publicSummary/caseFacts/0/sourceIdentityLinks/target/sourceTranscriptLink"));
        assertFalse(r.path("publicSummary").toString().contains("must-not-export"));
        assertEquals(r,json.readTree(json.writeValueAsBytes(r)));
    }
    @Test void unresolvedDiseaseNameStillHasSerializableMissingResult() {
        var p=paper();var ctx=context(p);var row=(ObjectNode)p.at("/observations/cases/0/resolvedObservations/0");
        ((ObjectNode)row.path("diseaseContext")).remove("diseaseId");
        ((ObjectNode)row.path("diseaseContext")).put("reasonCode","PM3_CASE_DIAGNOSIS_NOT_MATCHED")
                .putArray("reportedDiagnoses").add("Explicit paper diagnosis");
        var r=batch(p).evaluateContext(ctx);
        assertEquals("NOT_EVALUATED",r.at("/evaluationResult/status").asText());
        assertTrue(r.at("/publicSummary/caseFacts/0/diseaseId").isNull());
        assertEquals("PM3_CASE_DISEASE_CONTEXT_UNRESOLVED",r.at("/publicSummary/excludedObservations/0/reasonCode").asText());
        assertNotNull(new RuntimeEvidenceV2Adapter(json).canonicalSha256(r));
        assertEquals("PM3_CASE_DIAGNOSIS_NOT_MATCHED",r.at("/publicSummary/caseFacts/0/qualificationBlocker/causeReasonCode").asText());
        assertEquals("DISEASE_CONTEXT",r.at("/publicSummary/caseFacts/0/qualificationBlocker/stage").asText());
        assertEquals("Explicit paper diagnosis",r.at("/publicSummary/caseFacts/0/diseaseContextCheck/reportedDiagnoses/0").asText());
        assertEquals(2,r.at("/publicSummary/caseFacts/0/qualificationBlocker/dependentChecks").size());
    }
    @Test void actualRarityFailureReasonAndExactAlleleAreKeptInPublicFactsWithoutPrivateTransport() {
        var p=paper();var row=p.withObject("/observations/cases/0/resolvedObservations/0");
        row.withObject("/rarityQualification/target").put("qualification","UNKNOWN")
                .put("reasonCode","PM2_SPECIALIST_POLICY_REVIEW_REQUIRED").put("profileSha256","a".repeat(64))
                .putObject("populationQuery").put("privatePath","must-not-export");
        var result=batch(p).evaluateContext(context(p));
        var rarity=result.at("/publicSummary/caseFacts/0/rarityQualification/target");
        assertEquals("PM2_SPECIALIST_POLICY_REVIEW_REQUIRED",rarity.path("reasonCode").asText());
        assertEquals(row.at("/identities/target/allele"),rarity.path("queryAllele"));
        assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());
        assertFalse(result.path("publicSummary").toString().contains("must-not-export"));
    }
    @Test void missingTargetIdentityIsNotReportedAsADifferentDisease() {
        var p=paper();var ctx=context(p);var row=p.withObject("/observations/cases/0/resolvedObservations/0");
        row.putObject("identities");row.remove("diseaseContext");
        var r=batch(p).evaluateContext(ctx);
        assertEquals("PM3_TARGET_IDENTITY_UNRESOLVED",r.at("/evaluationResult/reasonCode").asText());
        assertEquals("PM3_TARGET_IDENTITY_UNRESOLVED",r.at("/publicSummary/excludedObservations/0/reasonCode").asText());
        assertEquals("NOT_EVALUATED",r.at("/evaluationResult/status").asText());
    }
    @Test void anonymousObservationAndSupplementProvenanceSurviveBothProjectionPaths() throws Exception {
        for (boolean resolved : List.of(false, true)) {
            var p = paper(); var ctx = context(p); var c = p.withObject("/observations/cases/0");
            c.put("identityKind", "ANONYMOUS_SOURCE_ROW_NOT_PATIENT_ID").put("patientIdentifier", "NOT_REPORTED");
            c.putObject("supplementContext").put("status", "SOURCE_CONTEXT_VERIFIED");
            c.putObject("primarySourceQualification").put("qualification", "VERIFIED");
            if (!resolved) c.putArray("resolvedObservations");
            var result = batch(p).evaluateContext(ctx);
            var stored = json.readTree(json.writeValueAsBytes(result)).at("/publicSummary/caseFacts/0");
            for (String field : List.of("identityKind", "patientIdentifier", "supplementContext", "primarySourceQualification"))
                assertEquals(c.path(field), stored.path(field));
        }
    }
    @Test void sourceQualifiedPolicyComputesNewResultRatherThanCopyingCandidateScore() {
        var p=paper();p.putObject("pm3Assessment").put("strength","VERY_STRONG").put("quarterPoints",999);
        var module=batch(p);var r=module.evaluateContext(context(p));
        assertEquals("MET",r.at("/evaluationResult/status").asText(),r.toPrettyString());
        assertEquals("SUPPORTING",r.path("appliedStrength").asText());assertTrue(module.evaluations()>0);
        assertEquals(2,r.at("/publicSummary/assessment/calculation/quarterPoints").asInt());
        assertEquals("PMC123#table-cell-1",r.at("/publicSummary/assessment/calculation/contributions/0/sourceReferences/0").asText());
        assertEquals(999,module.originals().at("/documents/0/result/pm3Assessment/quarterPoints").asInt());
    }
    @Test void onlySourceReportedUnknownPhaseCanEarnReducedWeight() throws Exception {
        var p=paper();
        var row=p.withObject("/observations/cases/0/resolvedObservations/0");
        var observation=row.withObject("/reportedObservation");
        observation.put("kind","REPORTED_TWO_ALLELES_PHASE_UNRESOLVED").put("phase","UNKNOWN")
                .put("targetRepresentation","c.2C>G").put("partnerRepresentation","c.3C>T");
        row.withObject("/identities/partner").put("status","LOCAL_IDENTITY_VERIFIED").set("allele",
                row.withObject("/identities/target/allele").deepCopy().put("position",5));
        row.withObject("/rarityQualification/partner").put("qualification","VERIFIED").set("queryAllele",row.at("/identities/partner/allele"));
        row.withObject("/partnerQualification").put("classification","PATHOGENIC").put("independentClassification","VERIFIED");
        var missing=batch(p).evaluateContext(context(p));
        assertEquals("NOT_EVALUATED",missing.at("/evaluationResult/status").asText());
        assertEquals(0,missing.at("/publicSummary/assessment/calculation/quarterPoints").asInt(-1));
        assertEquals("PHASE_ORIGINAL_MISSING",missing.at("/publicSummary/caseFacts/0/phaseEvidenceStatus").asText());
        assertEquals("PM3_PHASE_ORIGINAL_MISSING",missing.at("/publicSummary/assessment/calculation/contributions/0/reason").asText());
        ((com.fasterxml.jackson.databind.node.ArrayNode)observation.path("facts")).addObject()
                .put("field","phaseText").put("value","phase unknown").put("method","EXPLICIT_CASE_TYPED_TABLE_PHASE")
                .putObject("citation").put("quote","phase unknown").put("unitId","phase-cell");
        var unphased=batch(p).evaluateContext(context(p));
        var stored=json.readTree(json.writeValueAsBytes(unphased));
        assertEquals("MET",stored.at("/evaluationResult/status").asText());
        assertEquals("SUPPORTING",stored.path("appliedStrength").asText());
        assertEquals(2,stored.at("/publicSummary/assessment/calculation/quarterPoints").asInt(-1));
        assertEquals("REPORTED_UNDETERMINED",stored.at("/publicSummary/caseFacts/0/phaseEvidenceStatus").asText());
        assertEquals("phase unknown",stored.at("/publicSummary/caseFacts/0/publishedConditions/requirements/0/sourceFacts/0/citation/quote").asText());
        row.withObject("/partnerQualification").put("classification","LIKELY_PATHOGENIC");
        var lower=batch(p).evaluateContext(context(p));
        assertEquals("NOT_MET",lower.at("/evaluationResult/status").asText());
        assertEquals(1,lower.at("/publicSummary/assessment/calculation/quarterPoints").asInt(-1));
    }
    @Test void persistedIntegerAndRuntimeLongCoordinatesHaveTheSameExactIdentity() {
        var p=paper();var ctx=context(p);
        var allele=ctx.withObject("/variant/evidenceQueryAllele");
        allele.put("position",allele.path("position").longValue());
        var result=batch(p).evaluateContext(ctx);
        assertEquals("MET",result.at("/evaluationResult/status").asText(),result.toPrettyString());
        assertEquals("SUPPORTING",result.path("appliedStrength").asText());
        for(String field:List.of("assembly","contig","reference","alternate")) {
            var other=ctx.deepCopy();other.withObject("/variant/evidenceQueryAllele").put(field,"DIFFERENT");
            assertEquals("NOT_EVALUATED",batch(p).evaluateContext(other).at("/evaluationResult/status").asText());
        }
        for(var position:List.of(json.getNodeFactory().numberNode(1.5),json.getNodeFactory().textNode(allele.path("position").asText()),json.getNodeFactory().numberNode(allele.path("position").longValue()+1))) {
            var other=ctx.deepCopy();other.withObject("/variant/evidenceQueryAllele").set("position",position);
            assertEquals("NOT_EVALUATED",batch(p).evaluateContext(other).at("/evaluationResult/status").asText());
        }
    }
    @Test void diseaseAndPolicyMismatchDoNotBorrowAnotherBackgroundsPoints() {
        var p=paper();var ctx=context(p);ctx.withObject("/disease").put("mondoId","MONDO:9999999");
        assertEquals("NOT_EVALUATED",batch(p).evaluateContext(ctx).at("/evaluationResult/status").asText());
        for(String status:List.of("UNRESOLVED","UNSUPPORTED","CONFLICT","NOT_APPLICABLE")) {
            p=paper();p.withObject("/observations/cases/0/resolvedObservations/0/policySelection").put("status",status);
            var module=batch(p);var r=module.evaluateContext(context(p));
            assertEquals("NONE",r.path("appliedStrength").asText());assertEquals(0,module.evaluations());
            assertEquals(1,r.at("/publicSummary/excludedObservations").size());
        }
    }
    @Test void absentPolicyAndUnpreparedSnapshotAreExplicitAndCannotScore() {
        var p=paper();p.withObject("/observations/cases/0/resolvedObservations/0").remove("policySelection");
        var r=batch(p).evaluateContext(context(p));
        assertEquals("PM3_APPLICABLE_POLICY_NOT_SUPPLIED",r.at("/publicSummary/excludedObservations/0/reasonCode").asText());
        var source=mock(Pm3SelectedBatch.Source.class);when(source.identity()).thenReturn(json.createObjectNode());
        r=new Pm3SelectedBatch(json,source).evaluateContext(context(p));
        assertEquals("PM3_SOURCE_SNAPSHOT_NOT_PREPARED",r.at("/evaluationResult/reasonCode").asText());
    }
    @Test void snapshotPinMismatchCannotBecomeTrustedData() throws Exception {
        var source=mock(Pm3SelectedBatch.Source.class);when(source.identity()).thenReturn(json.createObjectNode());
        when(source.capture(anyString(),any())).thenReturn(json.createObjectNode().put("snapshotSha256","f".repeat(64)));
        var module=new Pm3SelectedBatch(json,source);module.prepare("analysis",json.createObjectNode());
        assertEquals("PM3_SOURCE_SNAPSHOT_INVALID",module.evaluateContext(context(paper())).at("/evaluationResult/reasonCode").asText());
        assertEquals(0,module.evaluations());
    }
    @Test void unresolvedCaseRetainsOriginalQuotesWithoutScoring() {
        var p=paper();var ctx=context(p);var c=(ObjectNode)p.at("/observations/cases/0");
        c.putArray("resolvedObservations");
        c.putArray("facts").addObject().put("field","targetVariant").put("sourceValue","p.A403V")
                .putObject("citation").put("unitId","table-row-1").put("quote","p.A403V");
        c.putObject("semantics").putArray("unverifiedFindings").addObject()
                .put("field","targetVariant").put("reasonCode","PM3_VARIANT_COLUMN_MEANING_UNRESOLVED")
                .put("columnHeading","Mutation 2AV");
        c.at("/semantics/unverifiedFindings/0").withObject("/headingEvidence")
                .put("status","AMBIGUOUS").putArray("sourceHeadings").addObject()
                .put("unitId","table-header-3").put("quote","Mutation 2AV");
        c.putArray("tableLayoutIssues").addObject().put("tableId","other-table")
                .put("reasonCode","PM3_TABLE_HEADER_LAYOUT_UNRESOLVED");
        c.withObject("/semantics").putArray("issues").add("PM3_VARIANT_REPRESENTATIONS_UNRESOLVED");
        c.putObject("cohortContext").put("schemaVersion","pm3-cohort-context-v1")
                .put("status","SOURCE_COHORT_BOUND").putArray("relatedStatements").addObject()
                .putObject("citation").put("quote","Methods need case binding").put("unitId","methods");
        var module=batch(p);var r=module.evaluateContext(ctx);
        assertEquals("NOT_EVALUATED",r.at("/evaluationResult/status").asText());
        assertEquals(0,module.evaluations());
        assertEquals("p.A403V",r.at("/publicSummary/caseFacts/0/reportedFacts/0/citation/quote").asText());
        assertEquals("Methods need case binding",r.at("/publicSummary/caseFacts/0/cohortContext/relatedStatements/0/citation/quote").asText());
        assertEquals("PM3_VARIANT_REPRESENTATIONS_UNRESOLVED",r.at("/publicSummary/caseFacts/0/semanticIssues/0").asText());
        assertEquals("PM3_VARIANT_COLUMN_MEANING_UNRESOLVED",
                r.at("/publicSummary/caseFacts/0/unverifiedFindings/0/reasonCode").asText());
        assertEquals("PM3_CASE_SEMANTICS_UNRESOLVED",r.at("/publicSummary/excludedObservations/0/reasonCode").asText());
        assertEquals(c.at("/semantics/unverifiedFindings/0/headingEvidence"),
                r.at("/publicSummary/caseFacts/0/unverifiedFindings/0/headingEvidence"));
        assertEquals(c.path("tableLayoutIssues"),r.at("/publicSummary/caseFacts/0/tableLayoutIssues"));
    }
    @Test void noExtractedCasesKeepsPartialCoverageAndActualSourceQuote() {
        var p=paper();var ctx=context(p);p.withObject("/observations").putArray("cases");
        p.put("chunkCount",9);p.putArray("completedReviewChunks").add(2);
        p.withObject("/observations").putObject("unreviewedExtraction").putArray("cases").addObject().put("caseLabel","pending-only");
        p.withObject("/observations").putArray("materialAssessments").addObject()
                .put("materialKind","AGGREGATE_OR_METHODS").putObject("citation")
                .put("quote","Mutation frequencies").put("unitId","table-caption");
        var module=batch(p);var r=module.evaluateContext(ctx);
        assertEquals("pending-only",r.at("/publicSummary/paperCoverage/0/unreviewedExtraction/cases/0/caseLabel").asText());
        assertTrue(r.at("/publicSummary/caseFacts").isEmpty());
        assertEquals("NOT_EVALUATED",r.at("/evaluationResult/status").asText());assertEquals(0,module.evaluations());
        assertEquals(9,r.at("/publicSummary/paperCoverage/0/chunkCount").asInt());
        assertFalse(r.at("/publicSummary/paperCoverage/0/absenceOfCasesEstablished").asBoolean(true));
        assertEquals("Mutation frequencies",r.at("/publicSummary/paperCoverage/0/materialAssessments/0/citation/quote").asText());
    }
    @Test void unresolvedProteinCandidatesSurviveSerializationWithoutBecomingScorable() throws Exception {
        var p=paper();var ctx=context(p);var row=p.withObject("/observations/cases/0/resolvedObservations/0");
        row.putObject("identities");row.putObject("alleleIds");row.put("status","IDENTITY_INCOMPLETE");
        var issue=row.putArray("issues").addObject().put("role","target")
                .put("reasonCode","PM3_PROTEIN_ONLY_SOURCE_CODING_LINK_MISSING");
        var search=issue.putObject("candidateSearch").put("schemaVersion","pm3-protein-candidates-v1")
                .put("scopeComplete",false).put("paperAlleleEstablished",false);
        search.putArray("candidates").addObject().put("transcript","NM_000001.1").put("status","LOCAL_SEQUENCE_VERIFIED_CANDIDATE");
        var module=batch(p);var result=module.evaluateContext(ctx);
        var restored=json.readTree(json.writeValueAsBytes(result));
        assertEquals(issue,restored.at("/publicSummary/caseFacts/0/identityIssues/0"));
        assertEquals("NOT_EVALUATED",restored.at("/evaluationResult/status").asText());
        assertEquals(0,module.evaluations());
    }
    @Test void partnerProvenanceSurvivesWithoutPrivateCachePaths() {
        var p=paper();var row=p.withObject("/observations/cases/0/resolvedObservations/0");
        row.putObject("partnerQualification").put("classification","UNKNOWN").put("independentClassification","UNKNOWN");
        var sources=row.putObject("partnerClassificationSources").put("status","EXACT_SOURCE_ASSERTIONS_AVAILABLE");
        sources.putArray("assertions").addObject().put("scv","SCV123.1").put("sourceClassification","Pathogenic");
        var acquisition=sources.putObject("acquisition");acquisition.putArray("records").addObject().put("path","local/private/source.xml");
        acquisition.putObject("lookup").put("region","1:101-101").putObject("snapshot").put("path","local/private/locator.gz");
        var r=batch(p).evaluateContext(context(p));var fact=r.at("/publicSummary/caseFacts/0");
        assertEquals("SCV123.1",fact.at("/partnerClassificationSources/assertions/0/scv").asText());
        assertEquals("1:101-101",fact.at("/partnerClassificationSources/lookup/region").asText());
        assertFalse(fact.toString().contains("local/private"));
        assertTrue(fact.path("partnerQualification").isObject());
    }
    @Test void normalBatchContainsPm3FactsAndExactRepeatDoesNotRecalculate() {
        var p=paper();p.put("retrievedEpoch",1793368306.0243733);
        var c=context(p);var module=batch(p);
        var runtime=new LocalEvidenceBatchRuntime(json,json.createObjectNode(),null,null,null,null,null,null,null,null,null,null,null,false,null,module);
        assertTrue(AcmgExecutionProfile.localBatch(json,runtime.identity()).criteria().contains(Pm3SelectedBatch.CRITERION));
        var selected=mock(SelectedEvidencePreparation.class);
        when(selected.assembly()).thenReturn(new AssemblyResult("analysis",1,1,List.of("candidate"),
                List.of(new CandidateAssembly("candidate",List.of(new Attempt("candidate","hypothesis",null,null,null,c,List.of())))),List.of(),false));
        when(selected.context("candidate","hypothesis")).thenReturn(c);
        when(selected.sources()).thenReturn(List.of());when(selected.provenance()).thenReturn(json.createObjectNode());
        when(selected.formalCandidate("candidate")).thenReturn(json.createObjectNode().put("id","candidate"));
        var analysis=new AnalysisContext("analysis",GenomeAssembly.GRCh38,AnalysisMode.WES);
        var r=runtime.execute(selected,analysis,"b".repeat(64));var publicView=runtime.publicView(r);
        assertEquals("SUPPORTING",publicView.at("/results/0/localBatch/criteria/5/appliedStrength").asText(),r.toPrettyString());
        assertEquals("pm3-selected-facts-v1",publicView.at("/results/0/localBatch/criteria/5/details/pm3Facts/schemaVersion").asText());
        assertFalse(publicView.at("/modules/pm3").has("sourceSnapshot"));assertTrue(r.at("/modules/pm3/sourceSnapshot/documents").isArray());
        int before=module.evaluations();var repeated=runtime.execute(selected,analysis,"b".repeat(64));
        assertEquals(before,module.evaluations());assertEquals(0,repeated.path("newEvaluatorInvocations").asInt(-1));
        assertEquals(r.path("results"),repeated.path("results"));
    }
}
