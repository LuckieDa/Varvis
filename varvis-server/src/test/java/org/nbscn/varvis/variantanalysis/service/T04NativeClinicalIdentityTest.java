package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.Pp4QualifiedMaterial;
import org.nbscn.varvis.variantanalysis.provider.T04PhenopacketOriginalFixture;

/** Different source IDs are engineering aliases backed by actual native file/sample
 * evidence, never equal-name matching or a new clinical qualification source. */
@EnabledIfSystemProperty(named="varvis.t04.guidance-xml",matches=".+")
@EnabledIfSystemProperty(named="varvis.pp1.python",matches=".+")
class T04NativeClinicalIdentityTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;
    static void relabelSources(org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture source) {
        source.set("CLINICAL","case",0,"person_id","source-clinical-person");source.set("CLINICAL","case",0,"family_id","source-clinical-family");
        source.set("CLINICAL","sample_links",0,"person_id","source-clinical-person");source.set("CLINICAL","sample_links",0,"family_id","source-clinical-family");
        for(int index=0;index<4;index++) source.set("CLINICIAN","reviews",index,"person_id","source-clinical-person");
    }
    static void relabelNativeDocument(com.fasterxml.jackson.databind.node.ObjectNode doc) {
        doc.put("id","source-clinical-family");doc.withObject("/proband/subject").put("id","source-clinical-person");
        doc.withObject("/proband/biosamples/0").put("individualId","source-clinical-person");
    }
    private T04NormalPipelineFixture fixture(String suffix,java.util.function.Consumer<org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture> change,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> docChange) throws Exception {
        return new T04NormalPipelineFixture(temporary.resolve(suffix),T04NormalPipelineTest.python(),T04NormalPipelineTest.scripts(),
                List.of("ACMG_AMP_2015:PP1"),"positive",true,null,source->{
                    relabelSources(source);change.accept(source);
                },false,ignored->{},(nativeFixture,context,manifest)->T04PhenopacketOriginalFixture.register(nativeFixture,context,manifest,doc->{
                    relabelNativeDocument(doc);docChange.accept(doc);
                }));
    }
    @Test void sourceNamespaceBindsOnlyThroughTheActualRegisteredNativeSampleChain() throws Exception {
        try(var f=fixture("a",ignored->{},ignored->{})) {
            var direct=Pp4QualifiedMaterial.verify(f.registered.registration(),f.context);
            assertFalse(direct.isComplete(),"No typed current sample proof supplied to the old verifier");
            assertEquals("PP4_NATIVE_CASE_CONTEXT_MISMATCH",direct.snapshot().path("reasonCode").asText());
            var result=f.execute();var pp1=result.at("/modules/pp1/results/0/publicSummary/evaluation");
            assertEquals("MET",pp1.path("status").asText(),"source="+f.inventoryAudit().at("/attempts/0/terminalReceipt/reasonCode").asText()+" qualification="+f.inventoryAudit().at("/attempts/0/terminalReceipt/qualification/reasonCode").asText()+" execution="+f.inventoryAudit().at("/attempts/0/terminalReceipt/diagnostic").asText()+" grant="+f.inventoryAudit().at("/attempts/0/terminalReceipt/currentUseGrant/reasonCode").asText());
            assertEquals("SUPPORTING",pp1.path("strength").asText());assertEquals(1,pp1.path("awardedPP1Points").asInt(-1));
            assertEquals("KNOWN_EMPTY",pp1.at("/ledger/status").asText());
            var receipt=f.inventoryAudit().at("/attempts/0/terminalReceipt");var bridge=receipt.at("/material/sourceIdentityBridge");
            assertEquals("source-clinical-person",bridge.path("sourcePersonId").asText());
            assertEquals(f.context.at("/patientContext/patientContextId"),bridge.path("currentPatientContextId"));
            assertFalse(bridge.path("clinicalQualificationGranted").asBoolean(true));assertFalse(bridge.path("familyRelationshipProven").asBoolean(true));
            assertEquals(bridge,receipt.at("/currentUseGrant/nativeClinicalIdentity"));
            var original=f.materialOriginals().values().iterator().next();
            assertEquals("source-clinical-person",original.at("/nativeFacts/case/person_id").asText(),"Original source IDs are retained");
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->f.json.treeToValue(bridge,CurrentClinicalAllocation.NativeClinicalIdentity.class));
        }
    }
    @Test void sameLabelsAndForgedJsonCannotBindAnotherFile() throws Exception {
        try(var f=fixture("wrong-file",ignored->{},doc->{
            doc.withObject("/proband/biosamples/0/files/0").put("uri",temporary.resolve("another.vcf").toUri().toASCIIString());
            doc.putObject("sourceIdentityBridge").put("approved",true).put("clinicalQualificationGranted",true);
        })) {
            var result=f.execute();var eval=result.at("/modules/pp1/results/0/publicSummary/evaluation");
            assertEquals("NOT_EVALUATED",eval.path("status").asText());assertEquals("UNKNOWN",eval.at("/ledger/status").asText());
            assertTrue(eval.at("/ledger/pp4Points").isNull());assertFalse(f.inventoryAudit().at("/attempts/0/terminalReceipt").has("coreExecution"));
        }
    }
    @Test void nativeIdentityDoesNotClearMissingClinicalReviewsOrOverrideActualAssessmentAge() throws Exception {
        for(String mode:List.of("review","age")) try(var f=fixture(mode,source->{
            if(mode.equals("review")) source.set("CLINICIAN","reviews",0,"result","UNKNOWN");
            else source.set("CLINICAL","case",0,"age_years","3");
        },doc->doc.withObject("/proband/subject/timeAtLastEncounter/age").put("iso8601duration","P80Y"))) {
            var result=f.execute();var eval=result.at("/modules/pp1/results/0/publicSummary/evaluation");
            assertEquals("NOT_EVALUATED",eval.path("status").asText());assertEquals("UNKNOWN",eval.at("/ledger/status").asText());assertTrue(eval.at("/ledger/pp4Points").isNull());
            var qualification=f.inventoryAudit().at("/attempts/0/terminalReceipt/qualification");
            assertEquals(mode.equals("review")?"PP4_NATIVE_CLINICIAN_EXCLUSION_NOT_CLEARED":"PP4_NATIVE_AGE_SCOPE_MISMATCH",qualification.path("reasonCode").asText());
            assertFalse(f.inventoryAudit().at("/attempts/0/terminalReceipt").has("coreExecution"));
            assertEquals("source-clinical-person",f.useAudit().at("/attempts/0/nativeClinicalIdentity/sourcePersonId").asText());
            var obligations=qualification.at("/nativeClinicalSourceReview/obligations");
            for(var condition:obligations) {
                String name=condition.path("condition").asText();
                if(mode.equals("age")&&name.equals("COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE")) assertEquals("NOT_SATISFIED",condition.path("status").asText());
                else if(mode.equals("review")&&name.equals("REQUIRED_DIFFERENTIAL_AND_LOCUS_REVIEW")) assertEquals("NOT_ESTABLISHED",condition.path("status").asText());
                else assertEquals("NOT_REVIEWED",condition.path("status").asText(),"A whole requirements list is not a list of missing facts");
            }
            if(mode.equals("age")) {
                assertEquals("3",qualification.at("/availableSourceInputs/literalOriginalFields/CLINICAL:case/0/age_years").asText());
                assertEquals("5",qualification.at("/availableSourceInputs/literalOriginalFields/DEFINITION:definition/0/minimum_age_years").asText());
            }
        }
    }
    @Test void aRealNativeHandleCannotBeBorrowedByAnotherRegisteredSource() throws Exception {
        try(var first=fixture("first",ignored->{},ignored->{});var second=fixture("second",ignored->{},ignored->{})) {
            first.execute();var attempt=first.session.attempts().get(0);
            var identity=first.currentUseSource().clinicalIdentity(first.session,attempt).orElseThrow();
            assertTrue(Pp4QualifiedMaterial.verify(first.registered.registration(),first.context,identity).isComplete());
            var borrowed=Pp4QualifiedMaterial.verify(second.registered.registration(),second.context,identity);
            assertFalse(borrowed.isComplete());assertEquals("PP4_NATIVE_CASE_IDENTITY_PROOF_MISMATCH",borrowed.snapshot().path("reasonCode").asText());
            identity.snapshot().put("currentPatientContextId","forged");
            assertEquals(first.context.at("/patientContext/patientContextId"),identity.snapshot().path("currentPatientContextId"));
            assertThrows(IllegalArgumentException.class,()->first.currentUseSource().clinicalIdentity(second.session,second.session.attempts().get(0)));
        }
    }
}
