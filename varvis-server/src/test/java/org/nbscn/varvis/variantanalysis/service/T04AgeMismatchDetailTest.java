package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture;

/** Actual normal submit/store/HTTP negative control; all clinical/cohort/sample inputs
 * are explicitly SYNTHETIC, never a real clinical qualification or edited result. */
@EnabledIfSystemProperty(named="varvis.t04.guidance-xml",matches=".+")
@EnabledIfSystemProperty(named="varvis.pp1.python",matches=".+")
@EnabledIfSystemProperty(named="varvis.t04.age-detail-work-root",matches=".+")
@EnabledIfSystemProperty(named="varvis.t04.age-detail-output",matches=".+")
class T04AgeMismatchDetailTest {
    @Test void normalSavedAgeMismatchRetainsKnownFailureAndSourceOnlyClinicalFacts() throws Exception {
        Path work=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("varvis.t04.age-detail-work-root"))),"age-");
        Path output=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("varvis.t04.age-detail-output"))),"record-");
        try(var f=new T04CurrentBusinessTest.BusinessFixture(work.resolve("w"),true,true,false,
                doc->doc.withObject("/proband/subject/timeAtLastEncounter/age").put("iso8601duration","P80Y"),
                original->original.set("CLINICAL","case",0,"age_years","3"))) {
            var json=f.base.mapper;var request=f.base.request.deepCopy();request.set("selectedCriteria",json.valueToTree(List.of("ACMG_AMP_2015:PP1")));
            var response=f.submit(request);var evaluation=f.pp1(response);
            assertEquals(1,response.path("enabledCriteria").size());assertEquals("ACMG_AMP_2015:PP1",response.at("/enabledCriteria/0").asText());
            assertEquals("NOT_EVALUATED",evaluation.path("status").asText());assertEquals("UNKNOWN",evaluation.at("/ledger/status").asText());
            assertTrue(evaluation.at("/ledger/pp4Points").isNull());assertEquals(0,evaluation.path("awardedPP1Points").asInt(-1));
            String analysis=f.base.analysisId,run=response.path("runId").asText(),endpoint="/variant-analyses/"+analysis+"/acmg/runs/"+run;
            var stored=f.base.store.run(analysis,run).orElseThrow();var privateData=json.readTree(stored.privateJson());
            var attempt=privateData.at("/currentClinicalInventory/attempts/0");var terminal=attempt.path("terminalReceipt");var qualification=terminal.path("qualification");
            assertEquals("PP4_NATIVE_AGE_SCOPE_MISMATCH",qualification.path("reasonCode").asText());
            assertEquals("3",qualification.at("/availableSourceInputs/literalOriginalFields/CLINICAL:case/0/age_years").asText());
            assertEquals("5",qualification.at("/availableSourceInputs/literalOriginalFields/DEFINITION:definition/0/minimum_age_years").asText());
            assertFalse(terminal.has("material"));assertFalse(terminal.has("currentUseGrant"));assertFalse(terminal.has("coreExecution"));
            for(var obligation:qualification.at("/nativeClinicalSourceReview/obligations")) {
                String condition=obligation.path("condition").asText();
                assertEquals(condition.equals("COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE")?"NOT_SATISFIED":"NOT_REVIEWED",obligation.path("status").asText());
                if(condition.equals("COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE")) assertEquals("PP4_NATIVE_AGE_SCOPE_MISMATCH",obligation.path("reasonCode").asText());
            }
            int pp1Evaluations=f.pp1Source.evaluations(),deNovoEvaluations=f.deNovoEvaluations();
            byte[] full=f.base.http.perform(get(endpoint)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            byte[] view=f.base.http.perform(get(endpoint+"/view")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            var index=json.readTree(view);String source=index.path("sourceViewSha256").asText();
            byte[] background=f.base.http.perform(get(endpoint+"/view/background").param("candidateId",response.at("/candidateIds/0").asText())
                    .param("backgroundIndex","0").param("sourceSha256",source)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            var clinicalReview=json.readTree(background).at("/candidates/0/attempts/0/localBatch/criteria/0/details/clinicalContext");
            assertEquals("SOURCE_ONLY",clinicalReview.path("status").asText());
            assertEquals("REVIEW_ONLY_SOURCE",clinicalReview.at("/binding/mode").asText());
            assertEquals(analysis,clinicalReview.at("/binding/analysisId").asText());
            assertEquals(run,clinicalReview.at("/binding/runId").asText());
            assertFalse(clinicalReview.has("member"));assertFalse(clinicalReview.path("review").has("independentAssessmentAge"));
            assertEquals("UNKNOWN",clinicalReview.at("/contribution/ledgerStatus").asText());assertTrue(clinicalReview.at("/contribution/pp4Points").isNull());
            var ageComparison=clinicalReview.path("comparisons").findValuesAsText("condition");
            assertEquals(List.of("COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE"),ageComparison);
            assertTrue(clinicalReview.path("comparisons").findValuesAsText("requirement").stream().anyMatch(value->value.contains("5–60")));
            assertTrue(clinicalReview.path("comparisons").findValuesAsText("known").stream().anyMatch(value->value.contains("3 岁")));
            assertTrue(clinicalReview.at("/review/missing").isEmpty());assertFalse(clinicalReview.toString().contains("P80Y"));
            assertArrayEquals(full,f.base.http.perform(get(endpoint).param("sourceViewSha256",source)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            var evidence=json.readTree(full).path("currentClinicalEvidence");assertTrue(evidence.path("materialReferences").isEmpty());
            var member=evidence.at("/nativeClinicalDetails/members/0");assertEquals("source-clinical-person",member.path("subjectId").asText());
            assertEquals("P80Y",evidence.at("/nativeClinicalFacts/packets/0/lastEncounterAgeSource/recordedTimeElement/age/iso8601duration").asText());
            assertFalse(member.has("currentPatientContextId"));assertFalse(member.has("currentFamilyContextId"));
            var identityOnly=privateData.at("/currentClinicalUse/attempts/0/nativeClinicalIdentity");
            assertEquals("source-clinical-person",identityOnly.path("sourcePersonId").asText());
            assertFalse(identityOnly.path("clinicalQualificationGranted").asBoolean(true));assertFalse(identityOnly.path("familyRelationshipProven").asBoolean(true));
            assertEquals(stored,f.base.store.run(analysis,run).orElseThrow());
            write(output,"full.json",full);write(output,"view.json",view);write(output,"background.json",background);
            write(output,"private.json",stored.privateJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            write(output,"public.json",stored.responseJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            write(output,"request.json",stored.requestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            write(output,"qualification.json",json.writeValueAsBytes(qualification));
            write(output,"obligations.json",json.writeValueAsBytes(qualification.at("/nativeClinicalSourceReview/obligations")));
            var receipt=json.createObjectNode().put("schemaVersion","t04-normal-age-mismatch-detail-control-v1").put("dataClass","SYNTHETIC_ENGINEERING_CONTROL_NOT_REAL_CLINICAL_CASE")
                    .put("analysisId",analysis).put("runId",run).put("candidateId",response.at("/candidateIds/0").asText()).put("hypothesisId",attempt.path("hypothesisId").asText())
                    .put("bindingIdentitySha256",attempt.path("bindingIdentitySha256").asText()).put("sourceViewSha256",source)
                    .put("nativeVcfRawSha256",f.nativeVcfSha).put("sourceClinicalAgeYears","3").put("definitionMinimumAgeYears","5").put("nativeLastEncounterDuration","P80Y")
                    .put("qualifiedMaterialPresent",false).put("currentUseGrantPresent",false).put("coreExecutionPresent",false)
                    .put("actualPp1SourceEvaluations",pp1Evaluations).put("actualDeNovoEvaluations",deNovoEvaluations)
                    .put("historyReadAddedWorkers",false).put("mainD18SourceOnlyReviewVerified",true);
            receipt.set("evaluation",evaluation.deepCopy());receipt.set("qualification",qualification.deepCopy());receipt.set("identityOnlyAudit",identityOnly.deepCopy());
            receipt.put("identityBoundary","Source-only member facts; actual identity-only audit does not replace qualified material/grant. MAIN must test D18 projection from these saved original files.");
            var sources=receipt.putArray("originalSources");Files.createDirectory(output.resolve("source-originals"));
            for(var binding:evidence.path("rawBindings")) {
                byte[] bytes=Base64.getDecoder().decode(binding.at("/artifact/base64").asText());String pin=T04NativeOriginalFixture.rawSha(bytes);
                assertEquals(pin,binding.path("rawSha256").asText());assertEquals(bytes.length,binding.at("/artifact/byteLength").asInt());
                String name=binding.path("kind").asText()+(binding.has("role")?"-"+binding.path("role").asText():"")+".bin";
                write(output.resolve("source-originals"),name,bytes);var entry=sources.addObject().put("file","source-originals/"+name).put("rawSha256",pin).put("byteLength",bytes.length);
                var identity=((ObjectNode)binding).deepCopy();identity.remove("artifact");entry.set("savedBinding",identity);
            }
            var outputs=receipt.putObject("outputRawSha256");
            for(String name:List.of("full.json","view.json","background.json","private.json","public.json","request.json","qualification.json","obligations.json")) outputs.put(name,T04NativeOriginalFixture.rawSha(Files.readAllBytes(output.resolve(name))));
            assertEquals(pp1Evaluations,f.pp1Source.evaluations());assertEquals(deNovoEvaluations,f.deNovoEvaluations());
            write(output,"receipt.json",json.writeValueAsBytes(receipt));
        }
        System.out.println("T04_NORMAL_AGE_MISMATCH_DETAIL_EVIDENCE="+output);
        System.out.println("T04_NORMAL_AGE_MISMATCH_WORK="+work);
    }
    private static void write(Path directory,String name,byte[] bytes) throws Exception { Files.write(directory.resolve(name),bytes,StandardOpenOption.CREATE_NEW); }
}
