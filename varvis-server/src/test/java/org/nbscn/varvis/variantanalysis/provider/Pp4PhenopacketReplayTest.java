package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class Pp4PhenopacketReplayTest {
    @TempDir Path temporary;
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode context() { var value=json.createObjectNode();value.putObject("patientContext").put("patientContextId","patient").put("familyContextId","family");return value; }
    private ObjectNode family() { return T04PhenopacketOriginalFixture.family(context(),temporary.resolve("original.vcf").toUri().toASCIIString(),"VCF-sample"); }
    private Pp4PhenopacketReplay.Replay read(ObjectNode doc,String kind) throws Exception { byte[] raw=json.writeValueAsBytes(doc);return Pp4PhenopacketReplay.read(raw,T04NativeOriginalFixture.rawSha(raw),kind); }

    @Test @EnabledIfSystemProperty(named="varvis.t04.phenopacket-original-root",matches=".+")
    void officialPublishedExampleRetainsFactsButNeverBecomesAQualifiedClinicalCase() throws Exception {
        Path root=Path.of(System.getProperty("varvis.t04.phenopacket-original-root"));
        byte[] raw=Files.readAllBytes(root.resolve("src/test/python/mcahs1.json"));
        String sha="25d70f62c08ba55e9bc36e99aa51992e9f4354d2274a8163bf9536636ea22067";
        var source=Pp4PhenopacketReplay.read(raw,sha,"PHENOPACKET");var facts=source.snapshot();
        assertArrayEquals(raw,source.originalBytes());assertEquals("id-A",facts.path("recordId").asText());
        assertEquals("proband A",facts.at("/packets/0/subjectId").asText());
        assertEquals("SOLVED",facts.at("/packets/0/interpretations/0/authorProgressStatus").asText());
        assertEquals("CONTRIBUTORY",facts.at("/packets/0/interpretations/0/genomicInterpretations/0/authorInterpretationStatus").asText());
        assertEquals("PATHOGENIC",facts.at("/packets/0/interpretations/0/genomicInterpretations/0/authorAcmgClassification").asText());
        assertEquals("PRESENT",facts.at("/packets/0/phenotypicFeatures/0/presence").asText());
        assertEquals(10,facts.at("/packets/0/phenotypicFeatures").size());
        assertFalse(facts.at("/packets/0/phenotypicFeatures/0/assessmentAgeKnown").asBoolean(true));
        assertEquals(0,facts.path("sampleAssociations").size());assertFalse(facts.path("clinicalAuthority").asBoolean(true));
        assertFalse(facts.path("cohortCompletenessProven").asBoolean(true));assertTrue(facts.findValues("awardedPoints").isEmpty());
        var details=source.detailFacts();assertEquals("P8D",details.at("/members/0/lastEncounterAgeSource/recordedTimeElement/age/iso8601duration").asText());
        assertEquals("UNKNOWN",details.at("/members/0/featureAssessmentAgeStatus").asText());
        assertEquals(10,details.at("/members/0/phenotypicFeatures").size());assertFalse(details.at("/qualificationLimits/contributionKnownFromFormat").asBoolean(true));
        source.snapshot().put("clinicalAuthority",true);byte[] mutated=source.originalBytes();mutated[0]=0;
        assertFalse(source.snapshot().path("clinicalAuthority").asBoolean(true));assertArrayEquals(raw,source.originalBytes());
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.treeToValue(facts,Pp4PhenopacketReplay.Replay.class));
    }
    @Test void supportedNativeFactsSeparateNegationAgesFileScopeAndAuthorDiagnosis() throws Exception {
        var doc=family();doc.put("clinicalAuthority",true);var facts=read(doc,"FAMILY").snapshot();
        assertEquals("PRESENT",facts.at("/packets/0/phenotypicFeatures/0/presence").asText());
        assertEquals("ABSENT",facts.at("/packets/0/phenotypicFeatures/1/presence").asText());
        assertEquals("/proband/phenotypicFeatures/0/onset",facts.at("/packets/0/phenotypicFeatures/0/onsetSource/jsonPointer").asText());
        assertFalse(facts.at("/packets/0/phenotypicFeatures/0/assessmentAgeKnown").asBoolean(true));
        assertEquals("LAST_ENCOUNTER_NOT_FEATURE_ASSESSMENT_OR_ONSET",facts.at("/packets/0/lastEncounterAgeSource/semanticRole").asText());
        assertEquals(2,facts.at("/packets/0/phenotypicFeatures").size(),"Omitted features never turn into negative observations");
        assertEquals("synthetic-proband",facts.at("/sampleAssociations/0/biosampleId").asText());
        assertEquals("patient",facts.at("/sampleAssociations/0/subjectId").asText());
        assertEquals("VCF-sample",facts.at("/sampleAssociations/0/vcfSampleId").asText());
        assertFalse(facts.at("/sampleAssociations/0/nativeFileVerified").asBoolean(true));
        assertFalse(facts.path("clinicalAuthority").asBoolean(true));
    }
    @Test void crossSampleUnknownMajorDuplicateObjectsAndUnsupportedAssertionsAreRefused() throws Exception {
        var wrong=family();wrong.withObject("/proband/biosamples/0").put("individualId","someone-else");
        assertEquals("PP4_PHENOPACKET_BIOSAMPLE_SUBJECT_MISMATCH",assertThrows(IOException.class,()->read(wrong,"FAMILY")).getMessage());
        var wrongFile=family();wrongFile.withObject("/proband/biosamples/0/files/0/individualToFileIdentifiers").put("unrelated-person","other");
        assertEquals("PP4_PHENOPACKET_FILE_SUBJECT_SCOPE_MISMATCH",assertThrows(IOException.class,()->read(wrongFile,"FAMILY")).getMessage());
        var version=family();version.withObject("/metaData").put("phenopacketSchemaVersion","1.0");
        assertEquals("PP4_PHENOPACKET_SCHEMA_VERSION_UNSUPPORTED",assertThrows(IOException.class,()->read(version,"FAMILY")).getMessage());
        var invalidDate=family();invalidDate.withObject("/metaData").put("created","not-a-date");
        assertEquals("PP4_PHENOPACKET_CREATED_INVALID",assertThrows(IOException.class,()->read(invalidDate,"FAMILY")).getMessage());
        var excluded=family();excluded.withObject("/proband/phenotypicFeatures/0").put("excluded","false");assertThrows(IOException.class,()->read(excluded,"FAMILY"));
        byte[] duplicate="{\"id\":\"a\",\"id\":\"b\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IOException.class,()->Pp4PhenopacketReplay.read(duplicate,T04NativeOriginalFixture.rawSha(duplicate),"PHENOPACKET"));
        byte[] two="{}{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IOException.class,()->Pp4PhenopacketReplay.read(two,T04NativeOriginalFixture.rawSha(two),"PHENOPACKET"));
        var doc=family();byte[] raw=json.writeValueAsBytes(doc);assertThrows(IOException.class,()->Pp4PhenopacketReplay.read(raw,"0".repeat(64),"FAMILY"));
    }
    @Test void aSubjectIdCannotAliasAnotherIndividualsBiosample() throws Exception {
        var root=family();var relative=(ObjectNode)root.path("proband").deepCopy();relative.put("id","relative-record");relative.remove("biosamples");
        relative.withObject("/subject").put("id","synthetic-proband");root.putArray("relatives").add(relative);
        assertEquals("PP4_PHENOPACKET_SUBJECT_OR_PACKET_AMBIGUOUS",assertThrows(IOException.class,()->read(root,"FAMILY")).getMessage());
    }
    @Test void descriptorCannotBorrowAnotherCreatorRecordOrRawOriginalAndCreationCannotPostdateAcquisition() throws Exception {
        var replay=read(family(),"FAMILY");var facts=replay.snapshot();
        var source=json.createObjectNode().put("format",Pp4PhenopacketReplay.FORMAT).put("kind","FAMILY")
                .put("sourceId","synthetic-native-source").put("recordId",facts.path("recordId").asText())
                .put("creator",facts.path("creator").asText()).put("rawSha256",facts.path("rawSha256").asText())
                .put("locator","urn:synthetic:native-source").put("acquiredAt","2025-01-01T00:00:00Z");
        assertEquals(facts.path("rawSha256"),Pp4ServerRegisteredInputs.verifiedNativeIdentity(source,replay).path("rawSha256"));
        for(String field:java.util.List.of("recordId","creator","rawSha256")) {
            var wrong=source.deepCopy().put(field,"another-original");
            assertEquals("PP4_SERVER_NATIVE_ORIGINAL_IDENTITY_MISMATCH",assertThrows(IOException.class,()->Pp4ServerRegisteredInputs.verifiedNativeIdentity(wrong,replay)).getMessage());
        }
        var tooEarly=source.deepCopy().put("acquiredAt","2023-01-01T00:00:00Z");
        assertEquals("PP4_SERVER_NATIVE_ACQUISITION_MISMATCH",assertThrows(IOException.class,()->Pp4ServerRegisteredInputs.verifiedNativeIdentity(tooEarly,replay)).getMessage());
        var malformed=source.deepCopy().put("acquiredAt","not-a-date");
        assertEquals("PP4_SERVER_NATIVE_ACQUISITION_INVALID",assertThrows(IOException.class,()->Pp4ServerRegisteredInputs.verifiedNativeIdentity(malformed,replay)).getMessage());
        var missingAuthority=source.deepCopy().put("locator","https:missing-authority");
        assertEquals("PP4_SERVER_NATIVE_LOCATOR_INVALID",assertThrows(IOException.class,()->Pp4ServerRegisteredInputs.verifiedNativeIdentity(missingAuthority,replay)).getMessage());
    }
    @Test void diseaseAssertionsAndAgeRolesAreRetainedWithoutUpgradingClinicalAssessment() throws Exception {
        var doc=family();var disease=doc.withObject("/proband").putArray("diseases").addObject().put("excluded",true);
        disease.putObject("term").put("id","MONDO:synthetic").put("label","SYNTHETIC disease excluded by source");
        disease.putObject("onset").putObject("age").put("iso8601duration","P3Y");
        var replay=read(doc,"FAMILY");var details=replay.detailFacts();
        assertEquals("ABSENT",details.at("/members/0/authorDiseaseAssertions/0/presence").asText());
        assertFalse(details.at("/members/0/authorDiseaseAssertions/0/clinicalDiagnosisVerified").asBoolean(true));
        assertEquals("P6M",details.at("/members/0/phenotypicFeatures/0/onsetSource/recordedValue/age/iso8601duration").asText());
        assertEquals("P20Y",details.at("/members/0/lastEncounterAgeSource/recordedTimeElement/age/iso8601duration").asText());
        assertEquals("UNKNOWN",details.at("/members/0/featureAssessmentAgeStatus").asText());
        assertTrue(details.findValues("fileUri").isEmpty());assertFalse(details.at("/qualificationLimits/contributionKnownFromFormat").asBoolean(true));
        assertEquals("SOURCE_NAMESPACE_ONLY_REQUIRES_SAME_ATTEMPT_BINDING",details.at("/members/0/identityBindingStatus").asText());
        details.withObject("/qualificationLimits").put("clinicalQualificationIssued",true);
        assertFalse(replay.detailFacts().at("/qualificationLimits/clinicalQualificationIssued").asBoolean(true));
    }
    @Test void cohortMembershipAndCompletedInterpretationDoNotProveIndependenceOrDiagnosis() throws Exception {
        var family=family();var packet=(ObjectNode)family.path("proband");
        var interpretation=packet.putArray("interpretations").addObject().put("id","interpretation").put("progressStatus","COMPLETED");
        var genomic=interpretation.putObject("diagnosis").putArray("genomicInterpretations").addObject()
                .put("subjectOrBiosampleId","patient").put("interpretationStatus","CANDIDATE");
        genomic.putObject("variantInterpretation").put("acmgPathogenicityClassification","UNCERTAIN_SIGNIFICANCE");
        var cohort=json.createObjectNode().put("id","cohort");cohort.set("metaData",T04PhenopacketOriginalFixture.metadata());cohort.putArray("members").add(packet);
        var facts=read(cohort,"COHORT").snapshot();assertFalse(facts.path("cohortCompletenessProven").asBoolean(true));
        assertFalse(facts.at("/packets/0/interpretations/0/clinicalDiagnosisVerified").asBoolean(true));
        assertEquals("CANDIDATE",facts.at("/packets/0/interpretations/0/genomicInterpretations/0/authorInterpretationStatus").asText());
        assertEquals("UNCERTAIN_SIGNIFICANCE",facts.at("/packets/0/interpretations/0/genomicInterpretations/0/authorAcmgClassification").asText());
        assertTrue(facts.findValues("diagnosticYield").isEmpty());
        cohort.withArray("members").add(packet.deepCopy());assertThrows(IOException.class,()->read(cohort,"COHORT"));
    }
}
