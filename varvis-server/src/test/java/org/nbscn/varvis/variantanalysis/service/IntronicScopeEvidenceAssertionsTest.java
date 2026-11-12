package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic acceptance-gate regression tests, never a claim of real resource execution. */
class IntronicScopeEvidenceAssertionsTest {
    @TempDir Path temporary;
    @Test void fixedDeclarationUsesContractPatientIdAndExistingCatalogMoiNormalization() {
        var smoke=new IntronicScopeBusinessSmokeTest();var mapper=smoke.base.mapper;
        var options=mapper.createObjectNode();var candidate=options.putArray("candidates").addObject().put("candidateId","public-candidate");
        var rec=candidate.putObject("recommendation").put("candidateId","public-candidate").put("recommendationId","public-recommendation")
                .put("policyVersion","1.1.0").put("policyHash","test-hash").put("generatedAt","2026-09-08T00:00:00Z").put("hypothesisId","public-hypothesis");
        var relation=rec.putArray("relationships").addObject().put("hgncId","HGNC:4827").put("mondoId","MONDO:0013517")
                .put("moi","AR").put("stableRelationshipKey","public-relationship").put("selectable",true);
        rec.putObject("resourceCompatibleTranscript").put("versionedTranscriptId","ENST00000335295.4")
                .put("annotationId","public-annotation").put("predictionPath","SPLICEAI_INTRONIC").put("annotationSource","VEP").put("annotationSourceVersion","116.0");
        var request=smoke.declaration(options);
        assertTrue(request.at("/patient/patientContextId").asText().matches("^patient-context-[a-z0-9][a-z0-9._-]{7,95}$"));
        assertEquals("AR",request.at("/hypotheses/0/hypothesis/moi").asText());
        relation.put("moi","AD");assertThrows(AssertionError.class,()->smoke.declaration(options));
    }
    @Test void acceptsExplicitStatesAndRejectsMissingStatusOrBrokenEvidenceDependency() throws Exception {
        var fixture=new SpliceAiBusinessIntegrationTest();fixture.temporary=temporary;fixture.setup();
        try {
            // Construct an assertion fixture on a copy; never alter an exported Java acceptance artifact.
            var saved=fixture.base.postRequest(fixture.base.request).deepCopy();
            var mapper=fixture.base.mapper;
            var attempt=(ObjectNode)saved.at("/candidates/0/attempts/0");
            ((ObjectNode)attempt.path("transcriptInput")).put("source","VEP");
            var raw=mapper.createObjectNode().put("status","SUCCEEDED");
            var hit=raw.putArray("results").addObject().put("status","HIT").put("exactAlleleMatch",true);
            hit.set("allele",attempt.at("/context/variant/evidenceQueryAllele"));
            var annotation=hit.putArray("annotations").addObject().put("geneSymbol","HBB");
            var ds=annotation.putObject("deltaScores");var dp=annotation.putObject("deltaPositions");
            for(String field:List.of("AG","AL","DG","DL")){ds.put("DS_"+field,"0.01");dp.put("DP_"+field,"0");}
            var lineage=(ObjectNode)attempt.at("/transcriptInput/rawBatchLineage");
            lineage.put("rawResultCanonicalSha256",IntronicScopeEvidenceAssertions.rawHash(raw,mapper));
            assertTrue(IntronicScopeEvidenceAssertions.verify(saved,raw,mapper));
            for(String invalid:List.of("","UNKNOWN","NOT_EVALUATED","NOT_APPLICABLE")) {
                var broken=saved.deepCopy();((ObjectNode)broken.at("/bundles/0/bundle/criterionEvaluations/2/evaluationResult")).put("status",invalid);
                assertThrows(AssertionError.class,()->IntronicScopeEvidenceAssertions.verify(broken,raw,mapper),invalid);
            }
            var absent=saved.deepCopy();((ObjectNode)absent.at("/bundles/0/bundle/criterionEvaluations/2/evaluationResult")).remove("status");
            assertThrows(AssertionError.class,()->IntronicScopeEvidenceAssertions.verify(absent,raw,mapper));
            var failed=saved.deepCopy();((ObjectNode)failed.at("/bundles/0/bundle/atomicEvidence/0/resourceExecution")).put("status","FAILED");
            assertThrows(AssertionError.class,()->IntronicScopeEvidenceAssertions.verify(failed,raw,mapper));
            var mismatch=saved.deepCopy();mismatch.withArray("/bundles/0/bundle/criterionEvaluations/1/evidenceIds").removeAll().add("wrong-evidence");
            assertThrows(AssertionError.class,()->IntronicScopeEvidenceAssertions.verify(mismatch,raw,mapper));
            var wrongDependency=saved.deepCopy();
            for(var parameter:wrongDependency.at("/bundles/0/bundle/criterionEvaluations/2/deterministicTrace/parameters"))
                if("dependency.bp4EvaluationId".equals(parameter.path("name").asText()))((ObjectNode)parameter).put("value","wrong-evaluation");
            assertThrows(AssertionError.class,()->IntronicScopeEvidenceAssertions.verify(wrongDependency,raw,mapper));
            // NOT_MET is a successful execution outcome; it still requires the same evidence dependency.
            var notMet=saved.deepCopy();var higher=raw.deepCopy();
            for(String field:List.of("AG","AL","DG","DL"))((ObjectNode)higher.at("/results/0/annotations/0/deltaScores")).put("DS_"+field,"0.30");
            ((ObjectNode)notMet.at("/candidates/0/attempts/0/transcriptInput/rawBatchLineage")).put("rawResultCanonicalSha256",IntronicScopeEvidenceAssertions.rawHash(higher,mapper));
            for(var measurement:notMet.at("/bundles/0/bundle/atomicEvidence/0/measurements"))
                if(measurement.path("metricId").asText().startsWith("spliceai.DS_")||"spliceai.maximumRawDeltaScore".equals(measurement.path("metricId").asText()))((ObjectNode)measurement).put("rawValue","0.30");
            for(var evaluation:notMet.at("/bundles/0/bundle/criterionEvaluations"))
                ((ObjectNode)evaluation.path("evaluationResult")).put("status",evaluation.path("criterionId").asText().endsWith(":PP3")?"MET":"NOT_MET");
            for(var parameter:notMet.at("/bundles/0/bundle/criterionEvaluations/2/deterministicTrace/parameters"))
                if("dependency.bp4Status".equals(parameter.path("name").asText()))((ObjectNode)parameter).put("value","NOT_MET");
            assertFalse(IntronicScopeEvidenceAssertions.verify(notMet,higher,mapper));
        } finally {fixture.close();}
    }
}
