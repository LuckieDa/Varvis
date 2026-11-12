package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.lang.Nullable;

/** Native material facts issued only by the real fixed-profile verifier.
 * Material completeness is distinct from criterion satisfaction and current adoption.
 * Definition specified_at is the original versioned phenotype-requirement effective date,
 * not a gene-disease curation date. Independent gene-disease curation may be later than
 * historical phenotype enrollment; no earlier GD-date rule is inferred here.
 */
public final class Pp4QualifiedMaterial {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Pp4ProductionMethodRegistration registration;
    private final ObjectNode data;
    private final ObjectNode audit;
    private final ObjectNode originals;

    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    private Pp4QualifiedMaterial(Pp4ProductionMethodRegistration registration, ObjectNode data) {
        this.registration = registration; this.data = data.deepCopy();
        originals = JSON.createObjectNode().put("schemaVersion", "pp4-native-source-material-originals-v1")
                .put("registrationSha256", registration.sha256()).put("nativeFactsSha256", Pp4ProductionMethodRegistration.canonical(data));
        originals.set("nativeFacts", data.deepCopy());
        originals.set("rawArtifactIndex", registration.inputs().identity().path("originals").deepCopy());
        audit = JSON.createObjectNode().put("schemaVersion", "pp4-native-qualified-material-v1")
                .put("status", "NATIVE_FACTS_REPLAYED_AND_SCOPE_CHECKED")
                .put("registrationSha256", registration.sha256()).put("nativeFactsSha256", Pp4ProductionMethodRegistration.canonical(data))
                .put("clinicalValidationCompleted", false).put("adoptionGranted", false)
                .put("originalsSha256", Pp4ProductionMethodRegistration.canonical(originals))
                .put("originalsPersistenceStatus", "REQUIRES_MAIN_CONTENT_ADDRESSED_ARCHIVE")
                .put("externalClassificationRecalculated", false)
                .put("phenotypeRequirementsSpecifiedAt", data.at("/definition/specified_at").asText())
                .put("geneDiseaseCuratedAt", data.at("/geneDiseaseBasisAssertion/curated_at").asText())
                .put("definitionTimingBasis", "ORIGINAL_VERSIONED_REQUIREMENTS_DATE_NOT_INFERRED_FROM_COHORT_OR_GD_CURATION")
                .put("dataClass", registration.inputs().identity().path("dataClass").asText())
                .put("numerator", data.path("numerator").asInt()).put("denominator", data.path("denominator").asInt());
        audit.set("sourcePins", registration.inputs().identity().path("originals").deepCopy());
        audit.set("context", data.path("context").deepCopy());
        audit.set("resolvedDiagnosticReports", data.path("diagnosticReportLinks").deepCopy());
        audit.put("caseId", data.at("/case/case_id").asText()).put("observationId", data.at("/case/observation_id").asText());
        if(data.has("sourceIdentityBridge")) audit.set("sourceIdentityBridge",data.path("sourceIdentityBridge").deepCopy());
        audit.put("materialSha256", Pp4ProductionMethodRegistration.canonical(audit));
    }

    public static final class Verification {
        private final @Nullable Pp4QualifiedMaterial material;
        private final ObjectNode diagnostic;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private Verification(@Nullable Pp4QualifiedMaterial material, ObjectNode diagnostic) { this.material = material; this.diagnostic = diagnostic.deepCopy(); }
        public Optional<Pp4QualifiedMaterial> material() { return Optional.ofNullable(material); }
        public ObjectNode snapshot() { return diagnostic.deepCopy(); }
        public boolean isComplete() { return material != null; }
    }

    public static boolean mayNeedNativeIdentityForPlanning(Pp4ProductionMethodRegistration registration,ObjectNode context) {
        try { return Pp4NativeMaterialVerifier.mayNeedNativeIdentity(registration,context); }
        catch(IOException|IllegalArgumentException unavailable) { return false; }
    }
    public static Verification verify(Pp4ProductionMethodRegistration registration, ObjectNode context) { return verify(registration,context,null); }
    public static Verification verify(Pp4ProductionMethodRegistration registration,ObjectNode context,
            @Nullable org.nbscn.varvis.variantanalysis.service.CurrentClinicalAllocation.NativeClinicalIdentity identity) {
        Objects.requireNonNull(registration); Objects.requireNonNull(context);
        try {
            var facts = Pp4NativeMaterialVerifier.verify(registration, context.deepCopy(),identity);
            var material = new Pp4QualifiedMaterial(registration, facts);
            var diagnostic=material.snapshot();
            registration.inputs().nativeClinicalProjection().ifPresent(nativeProjection -> diagnostic.set("nativeClinicalSourceReview",Pp4NativeClinicalProjection.qualification(nativeProjection,material)));
            attachNativeSourceQuality(registration,diagnostic);
            return new Verification(material,diagnostic);
        } catch (IOException | IllegalArgumentException invalid) {
            var diagnostic = JSON.createObjectNode().put("schemaVersion", "pp4-native-qualified-material-v1")
                    .put("status", "PARTIAL_OR_CONFLICT").put("adoptionGranted", false)
                    .put("registrationSha256", registration.sha256())
                    .put("reasonCode", invalid.getMessage() != null && invalid.getMessage().matches("PP4_[A-Z0-9_]+")
                            ? invalid.getMessage() : "PP4_NATIVE_FACTS_UNAVAILABLE");
            if (diagnostic.path("reasonCode").asText().endsWith("_UNSUPPORTED")) diagnostic.put("status", "NATIVE_PROFILE_UNSUPPORTED");
            diagnostic.set("availableSourceInputs",Pp4NativeMaterialVerifier.diagnosticInputs(registration));
            var nativeRecord=registration.inputs().identity().path("nativeClinicalRecord");
            if(nativeRecord.isObject()) {
                diagnostic.set("registeredStandardOriginal",nativeRecord.deepCopy());
                diagnostic.put("standardFormatRead",true).put("nativeCaseQualificationIssued",false);
                // These reader facts do not replace independent phenotype definition,
                // clinical assessment, cohort ascertainment or original diagnoses.
                var projection=registration.inputs().nativeClinicalProjection();
                if(projection.isPresent()) {
                    diagnostic.put("nativeClinicalProjectionStatus","NATIVE_FACTS_PROJECTED_INDEPENDENT_REVIEW_NOT_ESTABLISHED");
                    diagnostic.set("nativeClinicalSourceReview",Pp4NativeClinicalProjection.qualification(projection.orElseThrow(),null,registration,diagnostic.path("reasonCode").asText()));
                    var gaps=diagnostic.putArray("qualificationGaps");
                    String reason=diagnostic.path("reasonCode").asText();
                    String gapKind=reason.contains("AGE_SCOPE_MISMATCH")?"APPLICABLE_CONDITION_NOT_SATISFIED":reason.startsWith("PP4_NATIVE_PROFILE_")&&reason.endsWith("_UNSUPPORTED")?"SOFTWARE_FORMAT_LIMIT"
                            :reason.contains("CIRCULAR")?"CIRCULAR_SOURCE_BASIS_NOT_ADMITTED"
                            :reason.contains("CONTEXT_MISMATCH")||reason.contains("IDENTITY_PROOF_MISMATCH")?"SOURCE_BINDING_NOT_VERIFIED":"SOURCE_REVIEW_CONDITIONS_NOT_ESTABLISHED";
                    gaps.addObject().put("kind",gapKind).put("code",reason)
                            .put("required","Applicable independent clinical assessment/examinations, comparable diagnostic-yield source and noncircular original diagnosis/support")
                            .put("available","Original explicit phenotype/age/disease assertions and any recorded measurement/procedure/action fields have a bounded native projection")
                            .put("gap","Those source facts and author genomic conclusions do not establish the required independent review conditions");
                }
            }
            diagnostic.putArray("requiredMaterial").add("exact case/sample/native allele and original assay")
                    .add("independent scoped definition and complete examinations")
                    .add("dated original clinician exclusion and locus review")
                    .add("complete phenotype-first enrollment/diagnosis/observation roster and export closure")
                    .add("registered original diagnostic reports resolving independent positive cohort and gene-disease support records");
            diagnostic.set("sourcePins", registration.inputs().identity().path("originals").deepCopy());
            attachNativeSourceQuality(registration,diagnostic);
            return new Verification(null, diagnostic);
        }
    }
    private static void attachNativeSourceQuality(Pp4ProductionMethodRegistration registration,ObjectNode diagnostic) {
        var inputs=registration.inputs().identity();
        for(String field:List.of("nativeClinicalSourceQuality","nativeClinicalProjectionFailure"))
            if(inputs.has(field)) diagnostic.set(field,inputs.path(field).deepCopy());
    }
    public ObjectNode snapshot() { return audit.deepCopy(); }
    /** Full literal facts and table/row/cell replay for MAIN private content-addressed storage.
     * This is not an HTTP endpoint, persisted receipt, or a substitute for keeping raw originals.
     */
    public ObjectNode originals() { return originals.deepCopy(); }
    public String sha256() { return audit.path("materialSha256").asText(); }
    public boolean matchesContext(ObjectNode context) { return context != null && data.path("context").equals(context); }
    public ObjectNode currentCaseFacts() {
        var c=((ObjectNode)data.path("case")).deepCopy();
        if(data.has("sourceIdentityBridge")) {
            c.put("person_id",data.at("/context/patientContext/patientContextId").asText());
            c.put("family_id",data.at("/context/patientContext/familyContextId").asText());
        }
        return c;
    }
    public List<String> proposedEventIds() {
        var c=currentCaseFacts();var group = JSON.createObjectNode().put("domain", "pp4-single-case-current-contribution-v1");
        for (String key : List.of("person_id", "family_id", "hgnc_id", "mondo_id", "moi", "assembly", "contig", "position", "reference", "alternate"))
            group.set(key,c.path(key));
        return List.of("pp4-event-sha256-" + Pp4ProductionMethodRegistration.canonical(group));
    }
    /** Candidate facts for the MAIN allocator, not automatic exclusive ownership. */
    public List<String> observedFactIds() {
        var c=currentCaseFacts();return java.util.stream.StreamSupport.stream(data.path("observations").spliterator(), false).map(row -> {
            var identity = JSON.createObjectNode().put("domain", "pp4-native-observation-v1");
            for (String key : List.of("person_id", "family_id")) identity.set(key,c.path(key));
            for (String key : List.of("feature_id", "presence", "examination_method", "observed_at")) identity.set(key, row.path(key));
            return "pp4-fact-sha256-" + Pp4ProductionMethodRegistration.canonical(identity);
        }).sorted().toList();
    }
    ObjectNode nativeFacts() { return data.deepCopy(); }
    Pp4ProductionMethodRegistration registration() { return registration; }
}
