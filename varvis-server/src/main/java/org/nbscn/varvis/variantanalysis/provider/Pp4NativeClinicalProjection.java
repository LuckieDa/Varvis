package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import org.springframework.lang.Nullable;

/** Versioned native clinical facts and qualification obligations. The current PP4
 * verifier remains the only material issuer. This reader does not diagnose from a
 * measurement, authorize an assessment, count a cohort or issue a current grant.
 */
public final class Pp4NativeClinicalProjection {
    public static final String SCHEMA="pp4-native-clinical-facts-v1";
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final FamilyDeNovoJson READER=new FamilyDeNovoJson("PP4_NATIVE_CLINICAL_");
    private Pp4NativeClinicalProjection() { }

    public static ObjectNode implementationIdentity() {
        var out=JSON.createObjectNode().put("schemaVersion",SCHEMA).put("standardCommit",Pp4PhenopacketReplay.STANDARD_COMMIT)
                .put("scope","ORIGINAL_MEASUREMENT_PROCEDURE_TREATMENT_AND_ASSERTION_FACTS_WITHOUT_CLINICAL_DIAGNOSIS")
                .put("clinicalAuthority",false).put("completeCohortInferred",false).put("numericPhenotypeInferred",false);
        out.putObject("normativeOriginalSha256s")
                .put("measurement.proto","739bb5113591ffb3d972721a40891a4727ceef9df78f9390cc7ee0d22b243533")
                .put("medical_action.proto","7d807c5672f7d2530f982577b3b2260186b2968aae47ce7d9154d2ef1722fe34");
        return out;
    }
    public static Facts read(Pp4PhenopacketReplay.Replay original) throws IOException {
        ObjectNode document;
        try { document=READER.parse(JSON,original.originalBytes()); }
        catch(IllegalArgumentException invalid) { throw new IOException("PP4_NATIVE_CLINICAL_JSON_INVALID",invalid); }
        var base=original.snapshot();
        var facts=JSON.createObjectNode().put("schemaVersion",SCHEMA).put("sourceRawSha256",base.path("rawSha256").asText())
                .put("clinicalAuthority",false).put("completeClinicalAssessmentInferred",false).put("completeCohortInferred",false);
        facts.set("sourceIdentity",original.sourceIdentity());facts.set("readerIdentity",implementationIdentity());
        var members=facts.putArray("members");int total=0;
        for(var source:base.path("packets")) {
            String pointer=source.path("jsonPointer").asText();JsonNode packet=document.at(pointer);
            var member=members.addObject().put("subjectId",source.path("subjectId").asText()).put("jsonPointer",pointer)
                    .put("featureAssessmentAgeStatus","UNKNOWN_NO_PER_FEATURE_EXAMINATION_AGE_IMPUTED");
            for(String field:List.of("phenotypicFeatures","lastEncounterAgeSource","authorDiseaseAssertions","interpretations"))
                if(source.has(field)) member.set(field,source.path(field).deepCopy());
            var measurements=member.putArray("measurements");int index=0;
            for(var measurement:array(packet,"measurements")) {
                String at=pointer+"/measurements/"+index++;var row=measurements.addObject().put("jsonPointer",at)
                        .put("useRole","RECORDED_MEASUREMENT_NOT_AUTOMATIC_PHENOTYPE_OR_DIAGNOSIS");
                row.set("assay",ontology(measurement.path("assay")));
                need(!(measurement.has("value")&&measurement.has("complexValue")),"MEASUREMENT_ONEOF_CONFLICT");
                if(measurement.has("value")) {
                    var value=measurement.path("value");need(value.isObject(),"MEASUREMENT_VALUE_INVALID");
                    need(!(value.has("quantity")&&value.has("ontologyClass")),"MEASUREMENT_VALUE_ONEOF_CONFLICT");
                    if(value.has("quantity")) row.set("quantity",quantity(value.path("quantity")));
                    else if(value.has("ontologyClass")) row.set("recordedCategory",ontology(value.path("ontologyClass")));
                    else row.put("valueStatus","NOT_RECORDED_NO_ZERO_IMPUTATION");
                } else if(measurement.has("complexValue")) {
                    var quantities=row.putArray("typedQuantities");
                    for(var value:array(measurement.path("complexValue"),"typedQuantities")) {
                        var q=quantities.addObject();q.set("type",ontology(value.path("type")));q.set("quantity",quantity(value.path("quantity")));
                    }
                } else row.put("valueStatus","NOT_RECORDED_NO_ZERO_IMPUTATION");
                if(measurement.has("timeObserved")) time(row,"timeObserved",measurement.path("timeObserved"),at+"/timeObserved","MEASUREMENT_OBSERVED_TIME_NOT_OTHER_FEATURE_ASSESSMENT_TIME");
                if(measurement.has("procedure")) row.set("procedure",procedure(measurement.path("procedure"),at+"/procedure"));
                total++;
            }
            var actions=member.putArray("medicalActions");index=0;
            for(var action:array(packet,"medicalActions")) {
                String at=pointer+"/medicalActions/"+index++;var row=actions.addObject().put("jsonPointer",at)
                        .put("useRole","RECORDED_ACTION_NOT_INDEPENDENT_CRITERIA_FULFILLMENT_OR_DIFFERENTIAL_EXCLUSION");
                var known=List.of("procedure","treatment","radiationTherapy","therapeuticRegimen").stream().filter(field -> action.has(field)).toList();
                need(known.size()==1,"MEDICAL_ACTION_ONEOF_REQUIRED");String kind=known.get(0);row.put("kind",kind);
                if(kind.equals("procedure")) row.set("procedure",procedure(action.path(kind),at+"/procedure"));
                else {
                    var value=action.path(kind);need(value.isObject(),"MEDICAL_ACTION_INVALID");
                    if(kind.equals("treatment")) {
                        row.set("agent",ontology(value.path("agent")));
                        if(value.has("routeOfAdministration")) row.set("routeOfAdministration",ontology(value.path("routeOfAdministration")));
                        var doses=row.putArray("doseIntervals");
                        for(var dose:array(value,"doseIntervals")) {
                            var item=doses.addObject();
                            if(dose.has("quantity")) item.set("quantity",quantity(dose.path("quantity")));
                            else item.put("quantityStatus","NOT_RECORDED_NO_DOSE_IMPUTATION");
                            if(dose.has("interval")) item.set("recordedTreatmentInterval",dose.path("interval").deepCopy());
                            if(dose.has("scheduleFrequency")) item.set("scheduleFrequency",ontology(dose.path("scheduleFrequency")));
                        }
                    } else {
                        // Native treatment semantics are retained, never used as a diagnostic or review approval.
                        row.set("recordedAction",literal(value));
                    }
                }
                for(String field:List.of("treatmentTarget","treatmentIntent","responseToTreatment","treatmentTerminationReason"))
                    if(action.has(field)) row.set(field,ontology(action.path(field)));
                total++;
            }
            need(total<=2048,"FACT_CAPACITY_UNSUPPORTED");
        }
        facts.put("factsSha256",Pp4ProductionMethodRegistration.canonical(facts));return new Facts(facts);
    }
    /** Separately versioned semantic-quality audit. Proto3 optional syntax does not
     * make these semantic required fields optional. This never admits an invalid
     * quantity to numeric/phenotype interpretation or certifies a whole Phenopacket. */
    public static ObjectNode sourceQuality(Pp4PhenopacketReplay.Replay original) throws IOException {
        ObjectNode document;
        try { document=READER.parse(JSON,original.originalBytes()); }
        catch(IllegalArgumentException invalid) { throw new IOException("PP4_NATIVE_CLINICAL_JSON_INVALID",invalid); }
        var base=original.snapshot();var out=JSON.createObjectNode().put("schemaVersion","pp4-native-clinical-source-quality-v1")
                .put("sourceRawSha256",base.path("rawSha256").asText()).put("fullPhenopacketValidationPerformed",false)
                .put("completeSchemaConformanceAsserted",false).put("invalidFieldsUsedForClinicalQualification",false);
        var refs=out.putObject("normativeReferences");
        refs.putObject("quantity.html").put("url","https://phenopacket-schema.readthedocs.io/en/latest/quantity.html").put("snapshotSha256","34fa8c4d91a93b1bda0c977189ba3791d9a65bb261df140a2700d419042af188");
        refs.putObject("dose-interval.html").put("url","https://phenopacket-schema.readthedocs.io/en/latest/dose-interval.html").put("snapshotSha256","64566dd9a21bd3fe11844b580e6955c166de5b980ee0ce38d72541394200c25f");
        refs.putObject("requirements.html").put("url","https://phenopacket-schema.readthedocs.io/en/latest/requirements.html").put("snapshotSha256","03478a6b19700b17f8ec999ea22cad8e0acafec7fdd114f01fcdf4e866956f4b");
        var issues=out.putArray("issues");
        for(var source:base.path("packets")) {
            String at=source.path("jsonPointer").asText();var packet=document.at(at);int index=0;
            for(var measurement:array(packet,"measurements")) {
                String m=at+"/measurements/"+index++;
                if(measurement.path("value").has("quantity")) quantityQuality(measurement.at("/value/quantity"),m+"/value/quantity",issues);
                if(measurement.has("complexValue")) { int part=0;for(var q:array(measurement.path("complexValue"),"typedQuantities")) quantityQuality(q.path("quantity"),m+"/complexValue/typedQuantities/"+(part++)+"/quantity",issues); }
            }
            index=0;for(var action:array(packet,"medicalActions")) {
                String a=at+"/medicalActions/"+index++;int doseIndex=0;
                for(var dose:array(action.path("treatment"),"doseIntervals")) {
                    String d=a+"/treatment/doseIntervals/"+doseIndex++;
                    if(!dose.has("quantity")) qualityIssue(issues,"REQUIRED_DOSE_QUANTITY_NOT_RECORDED",d+"/quantity");
                    else quantityQuality(dose.path("quantity"),d+"/quantity",issues);
                    if(!dose.path("scheduleFrequency").path("id").isTextual()||dose.path("scheduleFrequency").path("id").asText().isBlank()) qualityIssue(issues,"REQUIRED_DOSE_FREQUENCY_NOT_RECORDED",d+"/scheduleFrequency");
                    if(!dose.path("interval").isObject()) qualityIssue(issues,"REQUIRED_DOSE_INTERVAL_NOT_RECORDED",d+"/interval");
                }
            }
        }
        out.put("status",issues.isEmpty()?"BOUNDED_FIELDS_READ_NOT_FULL_SCHEMA_VALIDATION":"PARTIAL_SOURCE_KNOWN_REQUIRED_FIELD_QUALITY_FAILURES");
        out.put("qualitySha256",Pp4ProductionMethodRegistration.canonical(out));return out;
    }
    /** Diagnostic for an unavailable optional projection. It preserves the raw source
     * and never changes independent six-role qualification or sample proof rules. */
    public static ObjectNode unavailableProjection(ObjectNode quality,IOException failure) {
        String reason=failure.getMessage();
        if(reason==null||!reason.matches("PP4_NATIVE_CLINICAL_[A-Z0-9_]+")) reason="PP4_NATIVE_CLINICAL_PROJECTION_UNAVAILABLE";
        return JSON.createObjectNode().put("schemaVersion","pp4-native-clinical-projection-unavailable-v1")
                .put("sourceRawSha256",quality.path("sourceRawSha256").asText()).put("qualitySha256",quality.path("qualitySha256").asText())
                .put("kind",quality.path("issues").isEmpty()&&reason.endsWith("_UNSUPPORTED")?"SOFTWARE_FORMAT_LIMIT":"SOURCE_DATA_QUALITY_NOT_PASSED")
                .put("reasonCode",reason).put("projectionAvailable",false).put("invalidFieldsUsedForClinicalQualification",false)
                .put("independentMaterialRequirementsUnchanged",true).put("completeSchemaConformanceAsserted",false);
    }
    private static void quantityQuality(JsonNode value,String pointer,com.fasterxml.jackson.databind.node.ArrayNode issues) {
        if(!value.isObject()) { qualityIssue(issues,"REQUIRED_QUANTITY_NOT_RECORDED",pointer);return; }
        if(!value.path("unit").path("id").isTextual()||value.path("unit").path("id").asText().isBlank()) qualityIssue(issues,"REQUIRED_QUANTITY_UNIT_MISSING_OR_INVALID",pointer+"/unit");
        if(!value.has("value")) qualityIssue(issues,"REQUIRED_QUANTITY_VALUE_NOT_RECORDED",pointer+"/value");
        else if(!value.path("value").isNumber()) qualityIssue(issues,"REQUIRED_QUANTITY_VALUE_NOT_NUMERIC",pointer+"/value");
    }
    private static void qualityIssue(com.fasterxml.jackson.databind.node.ArrayNode issues,String code,String pointer) {
        issues.addObject().put("kind","SOURCE_DATA_QUALITY_NOT_PASSED").put("code",code).put("jsonPointer",pointer)
                .put("numericOrClinicalInterpretationAllowed",false).put("missingValueImputed",false);
    }

    /** Mechanical exact-decimal historical content read; never reconstructs a Facts
     * handle or clinical authority from a saved JSON object. */
    public static ObjectNode readSavedProjection(byte[] bytes) throws IOException {
        ObjectNode saved;
        try { saved=READER.parse(JSON,bytes); }
        catch(IllegalArgumentException invalid) { throw new IOException("PP4_NATIVE_CLINICAL_SAVED_PROJECTION_INVALID",invalid); }
        need(SCHEMA.equals(saved.path("schemaVersion").asText()),"SAVED_PROJECTION_SCHEMA_UNSUPPORTED");
        var content=saved.deepCopy();content.remove("factsSha256");
        try { need(Pp4ProductionMethodRegistration.canonical(content).equals(saved.path("factsSha256").asText()),"SAVED_PROJECTION_PIN_MISMATCH"); }
        catch(IllegalArgumentException invalid) { throw new IOException("PP4_NATIVE_CLINICAL_SAVED_PROJECTION_INVALID",invalid); }
        return saved;
    }
    public static final class Facts {
        private final ObjectNode value;
        @JsonCreator(mode=JsonCreator.Mode.DISABLED)
        private Facts(ObjectNode value) { this.value=value.deepCopy(); }
        public ObjectNode snapshot() { return value.deepCopy(); }
        public ObjectNode identity() {
            var out=JSON.createObjectNode();for(String field:List.of("schemaVersion","sourceRawSha256","factsSha256","readerIdentity")) out.set(field,value.path(field).deepCopy());return out;
        }
    }

    /** Qualification is reused from the existing immutable material. No source flag,
     * SOLVED/genomic diagnosis, cohort member count or measurement can replace it.
     * Actual current identity/use is independently checked by CurrentClinicalAllocation.
     */
    static ObjectNode qualification(Facts nativeFacts,@Nullable Pp4QualifiedMaterial material) { return qualification(nativeFacts,material,null,""); }
    static ObjectNode qualification(Facts nativeFacts,@Nullable Pp4QualifiedMaterial material,
            @Nullable Pp4ProductionMethodRegistration registration,String failure) {
        if(material!=null && !nativeFacts.value.path("sourceRawSha256").equals(material.registration().registeredInputs().identity().at("/nativeClinicalRecord/rawSha256")))
            throw new IllegalArgumentException("PP4_NATIVE_CLINICAL_PROJECTION_SOURCE_MISMATCH");
        var out=JSON.createObjectNode().put("schemaVersion","pp4-native-clinical-source-review-v1")
                .put("scope","SOURCE_REQUIREMENTS_ONLY_CURRENT_NATIVE_GRANT_IS_SEPARATE")
                .put("nativeFactsSha256",nativeFacts.value.path("factsSha256").asText())
                .put("sourceRawSha256",nativeFacts.value.path("sourceRawSha256").asText())
                .put("status",material==null?"INDEPENDENT_REVIEW_CONDITIONS_NOT_ESTABLISHED":"EXISTING_REGISTERED_MATERIAL_REQUIREMENTS_VERIFIED")
                .put("nativeLabelsUsedToQualify",false).put("currentVariantDiagnosisUsedToQualify",false)
                .put("cohortMemberCountUsedAsDenominator",false).put("clinicalValidationCompleted",false);
        int measurements=0,actions=0,features=0;
        for(var member:nativeFacts.value.path("members")) {
            measurements+=member.path("measurements").size();actions+=member.path("medicalActions").size();features+=member.path("phenotypicFeatures").size();
        }
        out.putObject("availableNativeFacts").put("members",nativeFacts.value.path("members").size())
                .put("explicitPhenotypeAssertions",features).put("measurements",measurements).put("medicalActions",actions)
                .put("measurementTimesReferToTheirOwnMeasurements",true).put("completeClinicalReviewFromTheseCounts",false);
        var obligations=out.putArray("obligations");
        for(String condition:List.of("APPLICABLE_INDEPENDENT_PHENOTYPE_DEFINITION","COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE",
                "REQUIRED_DIFFERENTIAL_AND_LOCUS_REVIEW","COMPARABLE_INDEPENDENT_TESTED_COHORT","NONCIRCULAR_ORIGINAL_DIAGNOSIS_AND_SUPPORT")) {
            var row=obligations.addObject().put("condition",condition).put("status",material==null?"NOT_REVIEWED":"VERIFIED_BY_EXISTING_SOURCE_QUALIFIER")
                    .put("basis",material==null?"This native record supplies recorded facts; it does not establish this independent review condition":"Existing immutable material and its exact original source/record/report checks");
            if(material!=null) row.put("nativeFactsSha256",material.snapshot().path("nativeFactsSha256").asText());
            else {
                List<String> requiredRoles=switch(condition) {
                    case "APPLICABLE_INDEPENDENT_PHENOTYPE_DEFINITION" -> List.of("DEFINITION","GENE_DISEASE_BASIS");
                    case "COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE" -> List.of("CLINICAL","DEFINITION");
                    case "REQUIRED_DIFFERENTIAL_AND_LOCUS_REVIEW" -> List.of("CLINICIAN");
                    case "COMPARABLE_INDEPENDENT_TESTED_COHORT" -> List.of("COHORT","DEFINITION");
                    default -> List.of("DIAGNOSTIC_REPORTS","GENE_DISEASE_BASIS");
                };
                var missing=row.putArray("unavailableRegisteredSourceRoles");
                for(String role:requiredRoles) if(registration==null||registration.inputs().artifact(role)==null) missing.add(role);
                if(!missing.isEmpty()) row.put("status","NOT_ESTABLISHED").put("reasonCode","INDEPENDENT_ORIGINAL_NOT_REGISTERED_FOR_THIS_METHOD");
                boolean profileLimit=failure.startsWith("PP4_NATIVE_PROFILE_")&&failure.endsWith("_UNSUPPORTED");
                if(!profileLimit && condition.equals(failedObligation(failure))) row.put("status",failure.contains("AGE_SCOPE_MISMATCH")?"NOT_SATISFIED"
                        :failure.contains("CIRCULAR")?"NOT_ADMITTED_CIRCULAR_BASIS":"NOT_ESTABLISHED").put("reasonCode",failure);
                row.put("absenceOfPatientFactAsserted",false);
            }
        }
        if(measurements>0||actions>0) out.putObject("interpretationLimit")
                .put("kind","APPLICABLE_RULE_REQUIRED").put("code","PP4_MEASUREMENT_OR_ACTION_NOT_AUTOMATIC_CLINICAL_CRITERIA")
                .put("available","Recorded values, units, reference ranges, observation/performance times and actions")
                .put("required","An applicable independently sourced rule before deriving a phenotype or diagnostic-criteria conclusion")
                .put("effect","Retain native facts; use explicit qualified clinical assertions from the existing verifier instead of inferring a diagnosis");
        if(material!=null) {
            var data=material.nativeFacts();out.put("qualifiedMaterialSha256",material.sha256());
            out.putObject("independentlyQualifiedCohort").set("numerator",data.path("numerator").deepCopy());
            ((ObjectNode)out.path("independentlyQualifiedCohort")).set("denominator",data.path("denominator").deepCopy());
            out.set("qualifiedSourcePins",material.snapshot().path("sourcePins").deepCopy());
            out.put("resolvedOriginalDiagnosticReportCount",data.path("diagnosticReportLinks").size());
            if(data.has("sourceIdentityBridge")) out.putObject("identityBinding").put("status","ACTUAL_REGISTERED_SAMPLE_AND_CURRENT_NATIVE_CALL_CHAIN_VERIFIED")
                    .put("identitySha256",data.at("/sourceIdentityBridge/identitySha256").asText()).put("clinicalQualificationFromIdentity",false).put("familyRelationshipProven",false);
        }
        return out;
    }
    private static String failedObligation(String reason) {
        if(reason.isEmpty()||reason.contains("CONTEXT_MISMATCH")||reason.contains("IDENTITY_PROOF_MISMATCH")) return "";
        if(reason.contains("CIRCULAR")||reason.contains("DIAGNOSIS_REPORT")||reason.contains("GENE_DISEASE")) return "NONCIRCULAR_ORIGINAL_DIAGNOSIS_AND_SUPPORT";
        if(reason.contains("CLINICIAN")) return "REQUIRED_DIFFERENTIAL_AND_LOCUS_REVIEW";
        if(reason.contains("COHORT")||reason.contains("DIAGNOSIS_OUTCOME")) return "COMPARABLE_INDEPENDENT_TESTED_COHORT";
        if(reason.contains("DEFINITION")) return "APPLICABLE_INDEPENDENT_PHENOTYPE_DEFINITION";
        if(reason.contains("EXAMINATION")||reason.contains("OBSERVATION")||reason.contains("AGE_")) return "COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE";
        return "";
    }

    private static ObjectNode procedure(JsonNode value,String pointer) throws IOException {
        need(value.isObject(),"PROCEDURE_INVALID");var out=JSON.createObjectNode().put("jsonPointer",pointer)
                .put("useRole","PROCEDURE_FACT_NOT_PROOF_OF_COMPLETE_ASSESSMENT");
        out.set("code",ontology(value.path("code")));
        if(value.has("bodySite")) out.set("bodySite",ontology(value.path("bodySite")));
        if(value.has("performed")) time(out,"performed",value.path("performed"),pointer+"/performed","PROCEDURE_PERFORMED_TIME_NOT_ALL_FEATURE_ASSESSMENT_AGE");
        return out;
    }
    private static ObjectNode quantity(JsonNode value) throws IOException {
        need(value.isObject(),"QUANTITY_INVALID");var out=JSON.createObjectNode();out.set("unit",ontology(value.path("unit")));
        if(value.has("value")) { need(value.path("value").isNumber(),"QUANTITY_VALUE_UNSUPPORTED");out.put("recordedValue",value.path("value").decimalValue().toPlainString()).put("numericEncoding","EXACT_DECIMAL_STRING"); }
        else out.put("valueStatus","NOT_RECORDED_NO_ZERO_IMPUTATION");
        if(value.has("referenceRange")) {
            var range=value.path("referenceRange");need(range.isObject(),"REFERENCE_RANGE_INVALID");var r=out.putObject("referenceRange");
            if(range.has("unit")) r.set("unit",ontology(range.path("unit")));
            for(String bound:List.of("low","high")) if(range.has(bound)) { need(range.path(bound).isNumber(),"REFERENCE_RANGE_VALUE_UNSUPPORTED");r.put(bound,range.path(bound).decimalValue().toPlainString()).put("numericEncoding","EXACT_DECIMAL_STRING"); }
        }
        return out;
    }
    private static JsonNode literal(JsonNode value) {
        if(value.isFloatingPointNumber()) return JSON.createObjectNode().put("recordedNumber",value.decimalValue().toPlainString()).put("numericEncoding","EXACT_DECIMAL_STRING");
        if(value.isObject()) { var copy=JSON.createObjectNode();value.properties().forEach(entry -> copy.set(entry.getKey(),literal(entry.getValue())));return copy; }
        if(value.isArray()) { var copy=JSON.createArrayNode();for(var item:value) copy.add(literal(item));return copy; }
        return value.deepCopy();
    }
    private static ObjectNode ontology(JsonNode value) throws IOException {
        need(value.isObject()&&value.path("id").isTextual()&&!value.path("id").asText().isBlank(),"ONTOLOGY_ID_REQUIRED");
        var out=JSON.createObjectNode().put("id",value.path("id").asText());if(value.path("label").isTextual()) out.put("label",value.path("label").asText());return out;
    }
    private static void time(ObjectNode out,String field,JsonNode value,String pointer,String role) throws IOException {
        need(value.isObject(),"TIME_ELEMENT_INVALID");var t=out.putObject(field).put("jsonPointer",pointer).put("semanticRole",role);t.set("recordedTimeElement",value.deepCopy());
    }
    private static Iterable<JsonNode> array(JsonNode value,String field) throws IOException {
        if(!value.has(field)) return List.of();need(value.path(field).isArray()&&value.path(field).size()<=1024,"ARRAY_OR_CAPACITY_UNSUPPORTED");return value.path(field);
    }
    private static void need(boolean condition,String code) throws IOException { if(!condition) throw new IOException("PP4_NATIVE_CLINICAL_"+code); }
}
