package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class Pp4NativeClinicalProjectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode packet() {
        var p=json.createObjectNode().put("id","clinical-source-record");p.putObject("subject").put("id","source-person");
        p.set("metaData",T04PhenopacketOriginalFixture.metadata());return p;
    }
    private Pp4PhenopacketReplay.Replay replay(ObjectNode packet) throws Exception {
        byte[] raw=json.writeValueAsBytes(packet);
        return Pp4PhenopacketReplay.read(raw,T04NativeOriginalFixture.rawSha(raw),"PHENOPACKET");
    }
    private Pp4NativeClinicalProjection.Facts read(ObjectNode packet) throws Exception { return Pp4NativeClinicalProjection.read(replay(packet)); }
    @Test @EnabledIfSystemProperty(named="varvis.t04.phenopacket-original-root",matches=".+")
    void actualOfficialClinicalFieldsRetainTheirValuesUnitsAndOwnTimesWithoutManufacturingClinicalReview() throws Exception {
        Path path=Path.of(System.getProperty("varvis.t04.phenopacket-original-root")).resolve("src/test/python/covid19.json");
        byte[] raw=Files.readAllBytes(path);var original=Pp4PhenopacketReplay.read(raw,T04NativeOriginalFixture.rawSha(raw),"PHENOPACKET");
        var facts=Pp4NativeClinicalProjection.read(original);var view=facts.snapshot();
        assertEquals(11,view.at("/members/0/phenotypicFeatures").size());assertEquals(2,view.at("/members/0/measurements").size());
        assertEquals(6,view.at("/members/0/medicalActions").size());
        var quality=Pp4NativeClinicalProjection.sourceQuality(original);
        assertEquals("PARTIAL_SOURCE_KNOWN_REQUIRED_FIELD_QUALITY_FAILURES",quality.path("status").asText());
        assertEquals(java.util.Set.of("REQUIRED_DOSE_QUANTITY_NOT_RECORDED","REQUIRED_DOSE_FREQUENCY_NOT_RECORDED"),
                java.util.Set.copyOf(quality.path("issues").findValuesAsText("code")),"The actual official example has incomplete required dose fields");
        assertFalse(quality.path("completeSchemaConformanceAsserted").asBoolean(true));
        assertFalse(quality.path("invalidFieldsUsedForClinicalQualification").asBoolean(true));
        assertEquals("1.4",view.at("/members/0/measurements/0/quantity/recordedValue").asText());
        assertEquals("0.7",view.at("/members/0/measurements/1/quantity/recordedValue").asText());
        assertEquals("NCIT:C67245",view.at("/members/0/measurements/0/quantity/unit/id").asText());
        assertEquals("2019-09-01T00:00:00Z",view.at("/members/0/measurements/0/timeObserved/recordedTimeElement/interval/start").asText());
        assertEquals("2020-03-20T00:00:00Z",view.at("/members/0/measurements/1/timeObserved/recordedTimeElement/timestamp").asText());
        assertEquals("NCIT:C80473",view.at("/members/0/medicalActions/0/procedure/code/id").asText());
        assertEquals("2016-01-01T00:00:00Z",view.at("/members/0/medicalActions/0/procedure/performed/recordedTimeElement/timestamp").asText());
        assertFalse(view.path("completeClinicalAssessmentInferred").asBoolean(true));assertFalse(view.path("completeCohortInferred").asBoolean(true));
        var review=Pp4NativeClinicalProjection.qualification(facts,null);
        assertEquals("INDEPENDENT_REVIEW_CONDITIONS_NOT_ESTABLISHED",review.path("status").asText());
        assertEquals(2,review.at("/availableNativeFacts/measurements").asInt());assertEquals(6,review.at("/availableNativeFacts/medicalActions").asInt());
        assertFalse(review.has("independentlyQualifiedCohort"));assertFalse(review.path("nativeLabelsUsedToQualify").asBoolean(true));
        assertTrue(review.path("obligations").findValuesAsText("status").stream().allMatch("NOT_ESTABLISHED"::equals));
    }
    @Test @EnabledIfSystemProperty(named="varvis.t04.phenopacket-original-root",matches=".+")
    void officialSolvedGenomicInterpretationIsNotAnIndependentClinicalAssessmentOrDenominator() throws Exception {
        byte[] raw=Files.readAllBytes(Path.of(System.getProperty("varvis.t04.phenopacket-original-root")).resolve("src/test/python/mcahs1.json"));
        var source=Pp4PhenopacketReplay.read(raw,T04NativeOriginalFixture.rawSha(raw),"PHENOPACKET");
        var facts=Pp4NativeClinicalProjection.read(source);var view=facts.snapshot();
        assertEquals("SOLVED",view.at("/members/0/interpretations/0/authorProgressStatus").asText());
        assertEquals("PATHOGENIC",view.at("/members/0/interpretations/0/genomicInterpretations/0/authorAcmgClassification").asText());
        assertEquals("P8D",view.at("/members/0/lastEncounterAgeSource/recordedTimeElement/age/iso8601duration").asText());
        var review=Pp4NativeClinicalProjection.qualification(facts,null);assertFalse(review.path("currentVariantDiagnosisUsedToQualify").asBoolean(true));
        assertFalse(review.path("cohortMemberCountUsedAsDenominator").asBoolean(true));assertFalse(review.has("independentlyQualifiedCohort"));
        assertEquals("INDEPENDENT_REVIEW_CONDITIONS_NOT_ESTABLISHED",review.path("status").asText());
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.treeToValue(view,Pp4NativeClinicalProjection.Facts.class));
    }
    @Test void anOmittedValueIsNotZeroAndUnitsAndComplexMeasurementsAreNotSilentlyConverted() throws Exception {
        var p=packet();var measurements=p.putArray("measurements");
        var first=measurements.addObject();first.putObject("assay").put("id","LOINC:synthetic");
        var q=first.putObject("value").putObject("quantity");q.putObject("unit").put("id","UO:synthetic-unit");
        var range=q.putObject("referenceRange").put("low",0).put("high",10);range.putObject("unit").put("id","UO:different-source-unit");
        var zero=first.deepCopy();zero.withObject("/value/quantity").put("value",0);measurements.add(zero);
        var complex=measurements.addObject();complex.putObject("assay").put("id","LOINC:complex-synthetic");
        var component=complex.putObject("complexValue").putArray("typedQuantities").addObject();component.putObject("type").put("id","NCIT:component");
        component.putObject("quantity").put("value",120).putObject("unit").put("id","UO:pressure");
        var facts=read(p);var view=facts.snapshot();
        assertEquals("NOT_RECORDED_NO_ZERO_IMPUTATION",view.at("/members/0/measurements/0/quantity/valueStatus").asText());
        assertTrue(view.at("/members/0/measurements/0/quantity/recordedValue").isMissingNode());
        assertEquals(0,view.at("/members/0/measurements/1/quantity/recordedValue").asInt(-1));
        assertEquals("UO:different-source-unit",view.at("/members/0/measurements/0/quantity/referenceRange/unit/id").asText());
        assertEquals(120,view.at("/members/0/measurements/2/typedQuantities/0/quantity/recordedValue").asInt());
        view.put("clinicalAuthority",true);assertFalse(facts.snapshot().path("clinicalAuthority").asBoolean(true));
    }
    @Test void malformedRequiredQuantityFieldsAreNeverNumericallyAdmittedOrImputed() throws Exception {
        var p=packet();var measurement=p.putArray("measurements").addObject();measurement.putObject("assay").put("id","LOINC:test");
        var q=measurement.putObject("value").putObject("quantity").put("value",17);
        assertEquals("PP4_NATIVE_CLINICAL_ONTOLOGY_ID_REQUIRED",assertThrows(IOException.class,()->read(p)).getMessage());
        var audit=Pp4NativeClinicalProjection.sourceQuality(replay(p));
        assertEquals("REQUIRED_QUANTITY_UNIT_MISSING_OR_INVALID",audit.at("/issues/0/code").asText());
        assertEquals("/measurements/0/value/quantity/unit",audit.at("/issues/0/jsonPointer").asText());
        q.putObject("unit").put("id",12);
        assertEquals("PP4_NATIVE_CLINICAL_ONTOLOGY_ID_REQUIRED",assertThrows(IOException.class,()->read(p)).getMessage());
        q.withObject("/unit").put("id","UO:test");q.remove("value");
        assertEquals("REQUIRED_QUANTITY_VALUE_NOT_RECORDED",Pp4NativeClinicalProjection.sourceQuality(replay(p)).at("/issues/0/code").asText());
        assertFalse(read(p).snapshot().at("/members/0/measurements/0/quantity").has("recordedValue"));
        assertFalse(audit.path("fullPhenopacketValidationPerformed").asBoolean(true));
        assertFalse(audit.at("/issues/0/missingValueImputed").asBoolean(true));
    }
    @Test void exactDecimalMeasurementsSurviveTheSavedProjectionWithoutFloatingPointRounding() throws Exception {
        var p=packet();var measurement=p.putArray("measurements").addObject();measurement.putObject("assay").put("id","LOINC:decimal-test");
        var precise=new java.math.BigDecimal("1.234567890123456789");
        measurement.putObject("value").putObject("quantity").put("value",precise).putObject("unit").put("id","UO:exact-unit");
        var facts=read(p);assertEquals(0,precise.compareTo(new java.math.BigDecimal(facts.snapshot().at("/members/0/measurements/0/quantity/recordedValue").asText())));
        byte[] raw=json.writeValueAsBytes(facts.snapshot());var saved=Pp4NativeClinicalProjection.readSavedProjection(raw);
        assertEquals(0,precise.compareTo(new java.math.BigDecimal(saved.at("/members/0/measurements/0/quantity/recordedValue").asText())));
        saved.withObject("/members/0/measurements/0/quantity").put("recordedValue","1.0");
        assertEquals("PP4_NATIVE_CLINICAL_SAVED_PROJECTION_PIN_MISMATCH",assertThrows(IOException.class,
                ()->Pp4NativeClinicalProjection.readSavedProjection(json.writeValueAsBytes(saved))).getMessage());
    }
    @Test void conflictingNativeOneofsAndUnsupportedQuantityTypesArePreciseFormatErrors() throws Exception {
        var p=packet();var m=p.putArray("measurements").addObject();m.putObject("assay").put("id","LOINC:test");
        m.putObject("value").putObject("quantity").put("value","unknown").putObject("unit").put("id","UO:unit");
        assertEquals("PP4_NATIVE_CLINICAL_QUANTITY_VALUE_UNSUPPORTED",assertThrows(IOException.class,()->read(p)).getMessage());
        m.putObject("complexValue");assertEquals("PP4_NATIVE_CLINICAL_MEASUREMENT_ONEOF_CONFLICT",assertThrows(IOException.class,()->read(p)).getMessage());
        var action=packet();var a=action.putArray("medicalActions").addObject();a.putObject("procedure");a.putObject("treatment");
        assertEquals("PP4_NATIVE_CLINICAL_MEDICAL_ACTION_ONEOF_REQUIRED",assertThrows(IOException.class,()->read(action)).getMessage());
    }
    @Test void arbitraryApprovalFlagsInNativeDocumentsCannotCreateQualification() throws Exception {
        var p=packet();p.put("completeClinicalAssessment",true).put("completeCohort",true).put("approved",true);
        p.putObject("subject").put("id","source-person").putObject("timeAtLastEncounter").putObject("age").put("iso8601duration","P80Y");
        var disease=p.putArray("diseases").addObject().put("excluded",true);disease.putObject("term").put("id","MONDO:alternative");
        var facts=read(p);var review=Pp4NativeClinicalProjection.qualification(facts,null);
        assertEquals("INDEPENDENT_REVIEW_CONDITIONS_NOT_ESTABLISHED",review.path("status").asText());
        assertFalse(review.has("independentlyQualifiedCohort"));assertFalse(review.path("nativeLabelsUsedToQualify").asBoolean(true));
        assertEquals(5,review.path("obligations").size());
    }
}
