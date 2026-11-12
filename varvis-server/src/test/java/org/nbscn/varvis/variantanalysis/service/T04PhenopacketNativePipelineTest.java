package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.Pp4PhenopacketReplay;
import org.nbscn.varvis.variantanalysis.provider.T04PhenopacketOriginalFixture;

@EnabledIfSystemProperty(named="varvis.t04.guidance-xml",matches=".+")
@EnabledIfSystemProperty(named="varvis.pp1.python",matches=".+")
class T04PhenopacketNativePipelineTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;
    private T04NormalPipelineFixture fixture(String name,java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        return new T04NormalPipelineFixture(temporary.resolve(name),T04NormalPipelineTest.python(),T04NormalPipelineTest.scripts(),
                List.of("ACMG_AMP_2015:PP1"),"positive",true,null,ignored->{},false,ignored->{},
                (nativeFixture,context,manifest)->T04PhenopacketOriginalFixture.register(nativeFixture,context,manifest,mutation));
    }
    @Test void actualNativeSourceUsesStandardSampleLinkAndArchivesItsOriginalWithoutPmcIdentity() throws Exception {
        try(var f=fixture("standard-source",ignored->{})) {
            var link=f.registered.clinicalSampleLinks().get(0).snapshot();assertTrue(link.path("sampleOrdinal").isNull());
            assertTrue(link.path("vcfRawSha256").isNull());assertFalse(link.path("sourceRef").has("pmcid"));
            var result=f.execute();var pp1=result.at("/modules/pp1/results/0/publicSummary/evaluation");
            assertEquals("MET",pp1.path("status").asText());assertEquals("SUPPORTING",pp1.path("strength").asText());
            assertEquals("9",pp1.at("/bayesFactor/numerator").asText());assertEquals("4",pp1.at("/bayesFactor/denominator").asText());
            assertEquals(1,pp1.path("awardedPP1Points").asInt(-1));assertEquals("KNOWN_EMPTY",pp1.at("/ledger/status").asText());
            var receipt=f.inventoryAudit().at("/attempts/0/terminalReceipt");assertEquals("NOT_SELECTED",receipt.path("kind").asText());
            assertEquals(Pp4PhenopacketReplay.FORMAT,receipt.at("/currentUseGrant/originalSampleLink/sourceRef/kind").asText());
            assertEquals(1,receipt.at("/currentUseGrant/nativeCallProof/sampleOrdinal").asInt());
            var exported=CurrentClinicalArchive.verifyAndExpand(f.json,f.archive(),f.inventoryAudit(),f.nativeFixture.analysisId(),f.nativeFixture.runId());
            assertFalse(exported.path("nativeClinicalFacts").path("clinicalAuthority").asBoolean(true));
            assertEquals("UNKNOWN",exported.at("/nativeClinicalDetails/members/0/featureAssessmentAgeStatus").asText());
            assertFalse(exported.path("nativeClinicalDetails").toString().contains("fileUri\""));
            assertEquals(14,f.archive().path("rawArtifacts").size());assertEquals(14,exported.path("rawBindings").size());
            assertEquals("EXISTING_REGISTERED_MATERIAL_REQUIREMENTS_VERIFIED",receipt.at("/qualification/nativeClinicalSourceReview/status").asText());
            assertEquals(5,receipt.at("/qualification/nativeClinicalSourceReview/independentlyQualifiedCohort/numerator").asInt());
            assertEquals(12,receipt.at("/qualification/nativeClinicalSourceReview/independentlyQualifiedCohort/denominator").asInt());
            assertEquals("pp4-native-clinical-facts-v1",exported.path("nativeClinicalReviewFacts").path("schemaVersion").asText());
            assertEquals(1,f.pp1Source.evaluations());
            var altered=f.archive();((ObjectNode)altered.at("/registeredInputsIdentity/clinicalSampleSource")).put("recordId","wrong");
            for(var rawBinding:altered.path("rawBindings")) if("CLINICAL_STANDARD".equals(rawBinding.path("kind").asText())) ((ObjectNode)rawBinding).put("recordId","wrong");
            altered.put("registeredInputsIdentitySha256",org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture.canonicalSha(altered.path("registeredInputsIdentity")));
            altered.remove("archiveSha256");altered.put("archiveSha256",org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture.canonicalSha(altered));
            assertEquals("CURRENT_CLINICAL_ARCHIVE_NATIVE_SOURCE_MISMATCH",assertThrows(IllegalArgumentException.class,
                    ()->CurrentClinicalArchive.verifyAndExpand(f.json,altered,f.inventoryAudit(),f.nativeFixture.analysisId(),f.nativeFixture.runId())).getMessage());
        }
    }
    @Test @EnabledIfSystemProperty(named="varvis.t04.phenopacket-original-root",matches=".+")
    void actualOfficialOriginalRegistersWithoutInventedPmcRolesButNeverIssuesClinicalQualification() throws Exception {
        Path original=Path.of(System.getProperty("varvis.t04.phenopacket-original-root")).resolve("src/test/python/mcahs1.json");
        byte[] raw=Files.readAllBytes(original);
        try(var f=new T04NormalPipelineFixture(temporary.resolve("official-intake"),T04NormalPipelineTest.python(),T04NormalPipelineTest.scripts(),
                List.of("ACMG_AMP_2015:PP1"),"positive",true,null,ignored->{},false,ignored->{},(nativeFixture,context,manifest)->{
                    var json=new com.fasterxml.jackson.databind.ObjectMapper();var doc=(ObjectNode)json.readTree(Files.readAllBytes(manifest));
                    doc.put("registeredAt","2026-11-07T00:00:00Z");doc.putArray("originals");
                    Files.write(manifest.getParent().resolve("official-phenopacket.json"),raw);
                    doc.putObject("clinicalSampleSource").put("format",Pp4PhenopacketReplay.FORMAT).put("kind","PHENOPACKET")
                            .put("sourceId","ga4gh-release-2-example-mcahs1").put("recordId","id-A").put("creator","Peter R.")
                            .put("file","official-phenopacket.json").put("rawSha256","25d70f62c08ba55e9bc36e99aa51992e9f4354d2274a8163bf9536636ea22067")
                            .put("locator","https://raw.githubusercontent.com/ga4gh/phenopacket-schema/dd70d53b2c1dfd5ab6ac25b1b4b422b7be90a6ae/src/test/python/mcahs1.json")
                            .put("acquiredAt","2026-11-07T00:00:00Z");
                    Files.write(manifest,json.writeValueAsBytes(doc));return manifest;
                })) {
            // The authoritative format example is not the synthetic execution patient or a complete clinical cohort.
            assertEquals(0,f.registered.registration().registeredInputs().originalArtifacts().size());
            assertTrue(f.registered.clinicalSampleLinks().isEmpty());assertEquals(5,f.registered.archiveArtifacts().size());
            var result=f.execute();var pp1=result.at("/modules/pp1/results/0/publicSummary/evaluation");
            assertEquals("NOT_EVALUATED",pp1.path("status").asText());
            assertEquals("UNKNOWN",pp1.at("/ledger/status").asText());assertTrue(pp1.at("/ledger/pp4Points").isNull());
            var receipt=f.inventoryAudit().at("/attempts/0/terminalReceipt");
            assertFalse(receipt.has("coreExecution"));assertFalse(receipt.path("complete").asBoolean(true));
            var qualification=receipt.path("qualification");
            assertTrue(qualification.path("standardFormatRead").asBoolean());assertFalse(qualification.path("nativeCaseQualificationIssued").asBoolean(true));
            assertEquals("NATIVE_FACTS_PROJECTED_INDEPENDENT_REVIEW_NOT_ESTABLISHED",qualification.path("nativeClinicalProjectionStatus").asText());
            assertEquals("SOURCE_REVIEW_CONDITIONS_NOT_ESTABLISHED",qualification.at("/qualificationGaps/0/kind").asText());
            assertEquals("INDEPENDENT_REVIEW_CONDITIONS_NOT_ESTABLISHED",qualification.at("/nativeClinicalSourceReview/status").asText());
            assertEquals(10,qualification.at("/nativeClinicalSourceReview/availableNativeFacts/explicitPhenotypeAssertions").asInt());
            var exported=CurrentClinicalArchive.verifyAndExpand(f.json,f.archive(),f.inventoryAudit(),f.nativeFixture.analysisId(),f.nativeFixture.runId());
            assertEquals(5,f.archive().path("rawArtifacts").size());assertEquals(5,exported.path("rawBindings").size());
            assertEquals("SOLVED",exported.at("/nativeClinicalFacts/packets/0/interpretations/0/authorProgressStatus").asText());
            assertFalse(exported.path("nativeClinicalFacts").path("clinicalAuthority").asBoolean(true));
        }
    }
    @Test void badUnusedMeasurementIsExplicitPartialSourceAndCannotCreateOrEraseIndependentQualification() throws Exception {
        for(boolean independentReview:List.of(true,false)) try(var f=new T04NormalPipelineFixture(temporary.resolve("partial-"+independentReview),
                T04NormalPipelineTest.python(),T04NormalPipelineTest.scripts(),List.of("ACMG_AMP_2015:PP1"),"positive",true,null,
                source->{if(!independentReview) source.set("CLINICIAN","reviews",0,"result","UNKNOWN");},false,ignored->{},
                (nativeFixture,context,manifest)->T04PhenopacketOriginalFixture.register(nativeFixture,context,manifest,doc->{
                    T04PhenopacketOriginalFixture.addClinicalNumberAndUnrecordedRequiredDose(doc,false);
                    doc.withObject("/proband/measurements/0/value/quantity").remove("unit");
                }))) {
            assertFalse(f.registered.registration().registeredInputs().nativeClinicalProjection().isPresent());
            assertEquals(1,f.registered.clinicalSampleLinks().size());
            var result=f.execute();var evaluation=result.at("/modules/pp1/results/0/publicSummary/evaluation");
            var receipt=f.inventoryAudit().at("/attempts/0/terminalReceipt");
            assertEquals(independentReview?"MET":"NOT_EVALUATED",evaluation.path("status").asText());
            assertEquals(independentReview?"KNOWN_EMPTY":"UNKNOWN",evaluation.at("/ledger/status").asText());
            if(independentReview) {
                assertEquals("9",evaluation.at("/bayesFactor/numerator").asText());assertEquals("4",evaluation.at("/bayesFactor/denominator").asText());
                assertEquals(1,evaluation.path("awardedPP1Points").asInt(-1));assertTrue(receipt.has("coreExecution"));
            } else {
                assertTrue(evaluation.at("/ledger/pp4Points").isNull());assertFalse(receipt.has("coreExecution"));
                assertEquals("PP4_NATIVE_CLINICIAN_EXCLUSION_NOT_CLEARED",receipt.at("/qualification/reasonCode").asText());
            }
            var quality=receipt.at("/qualification/nativeClinicalSourceQuality");
            assertEquals("PARTIAL_SOURCE_KNOWN_REQUIRED_FIELD_QUALITY_FAILURES",quality.path("status").asText());
            assertTrue(quality.path("issues").findValuesAsText("code").contains("REQUIRED_QUANTITY_UNIT_MISSING_OR_INVALID"));
            assertFalse(quality.path("completeSchemaConformanceAsserted").asBoolean(true));
            assertFalse(quality.path("invalidFieldsUsedForClinicalQualification").asBoolean(true));
            var exported=CurrentClinicalArchive.verifyAndExpand(f.json,f.archive(),f.inventoryAudit(),f.nativeFixture.analysisId(),f.nativeFixture.runId());
            assertFalse(exported.has("nativeClinicalReviewFacts"));assertEquals(quality,exported.path("nativeClinicalSourceQuality"));
            assertEquals("SOURCE_DATA_QUALITY_NOT_PASSED",exported.at("/nativeClinicalProjectionFailure/kind").asText());
            assertFalse(exported.at("/nativeClinicalProjectionFailure/projectionAvailable").asBoolean(true));
            if(independentReview) {
                var changed=f.archive();changed.withObject("/registeredInputsIdentity/nativeClinicalSourceQuality").put("status","SCHEMA_VALID");
                changed.put("registeredInputsIdentitySha256",org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture.canonicalSha(changed.path("registeredInputsIdentity")));
                changed.remove("archiveSha256");changed.put("archiveSha256",org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture.canonicalSha(changed));
                assertEquals("CURRENT_CLINICAL_ARCHIVE_NATIVE_QUALITY_MISMATCH",assertThrows(IllegalArgumentException.class,
                        ()->CurrentClinicalArchive.verifyAndExpand(f.json,changed,f.inventoryAudit(),f.nativeFixture.analysisId(),f.nativeFixture.runId())).getMessage());
            }
        }
    }
    @Test void sameSampleNameAtAnotherNativeFileAndWrongOriginalBiosampleNeverBorrowPermission() throws Exception {
        for(String mutation:List.of("file","biosample")) try(var f=fixture(mutation,doc->{
            if(mutation.equals("file")) doc.withObject("/proband/biosamples/0/files/0").put("uri",temporary.resolve("another-original.vcf").toUri().toASCIIString());
            else { doc.withObject("/proband/biosamples/0").put("id","another-sample");
                var mapping=doc.withObject("/proband/biosamples/0/files/0/individualToFileIdentifiers");var value=mapping.remove("synthetic-proband");mapping.set("another-sample",value); }
        })) {
            var result=f.execute();var pp1=result.at("/modules/pp1/results/0/publicSummary/evaluation");
            assertEquals("UNKNOWN",pp1.at("/ledger/status").asText());assertTrue(pp1.at("/ledger/pp4Points").isNull());
            assertEquals("NOT_EVALUATED",pp1.path("status").asText());assertEquals(0,pp1.path("awardedPP1Points").asInt(-1));
            assertFalse(f.inventoryAudit().at("/attempts/0/terminalReceipt").has("coreExecution"));
        }
    }
}
