package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves one pinned HNF1A early-exon stop-gained prerequisite input.
 * This class does not evaluate PVS1 or assign an evidence strength.
 */
public final class Pvs1PrerequisiteInputResolver {

    public static final String PROFILE_ID =
            "HNF1A_EARLY_EXON_STOP_GAINED_PVS1_PREREQUISITE";
    public static final String PROFILE_VERSION = "1.0.0";

    private static final String PROFILE_RESOURCE =
            "/acmg/pvs1-early-exon-stop-gained-prerequisite-profile-v1.json";
    private static final String PROFILE_CANONICAL_SHA256 =
            "66b9c089cbcf6f4eaedb6ba6de387fd00a11cfb0e52449ada9f0386bb8192822";
    private static final Pattern EXON = Pattern.compile("^([1-9][0-9]*)/([1-9][0-9]*)$");

    private final ObjectMapper mapper;
    private final ObjectNode profile;
    private final String profileSha256;
    private final String profileIssue;

    public Pvs1PrerequisiteInputResolver(ObjectMapper mapper) {
        this(mapper, loadProfile(mapper));
    }

    Pvs1PrerequisiteInputResolver(ObjectMapper mapper, ObjectNode profile) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.profile = Objects.requireNonNull(profile, "profile").deepCopy();
        this.profileSha256 = new RuntimeEvidenceV2Adapter(mapper)
                .canonicalSha256(this.profile);
        this.profileIssue = profileIssue(this.profile, profileSha256);
    }

    public static ObjectNode loadProfile(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        try (InputStream input = Pvs1PrerequisiteInputResolver.class
                .getResourceAsStream(PROFILE_RESOURCE)) {
            if (input == null) throw new IllegalStateException("PVS1 prerequisite profile is missing");
            JsonNode value = mapper.readTree(input);
            if (!value.isObject()) {
                throw new IllegalStateException("PVS1 prerequisite profile is not an object");
            }
            return (ObjectNode) value;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load PVS1 prerequisite profile", failure);
        }
    }

    /** Returns only prerequisite readiness and the fixed profile's NMD prediction. */
    public Result resolve(JsonNode evidenceContext, ObjectNode atomicEvidence) {
        if (profileIssue != null) return notReady(profileIssue, evidenceContext, atomicEvidence);
        if (evidenceContext == null || !evidenceContext.isObject()
                || atomicEvidence == null) {
            return notReady("EVIDENCE_CONTEXT_IDENTITY_INVALID",
                    evidenceContext, atomicEvidence);
        }

        JsonNode selector = profile.path("selector");
        if (!"evidence-context-v2".equals(evidenceContext.path("schemaVersion").asText())
                || !profile.path("canonicalizationProfile").asText().equals(
                evidenceContext.path("canonicalizationProfile").asText())
                || !"COMPLETE".equals(evidenceContext.path("contextCompleteness")
                .path("status").asText())) {
            return notReady("EVIDENCE_CONTEXT_IDENTITY_INVALID",
                    evidenceContext, atomicEvidence);
        }
        if (!scopeMatches(evidenceContext.path("scope"))) {
            return notReady("CONTEXT_SCOPE_MISMATCH", evidenceContext, atomicEvidence);
        }
        JsonNode allele = evidenceContext.path("variant").path("evidenceQueryAllele");
        if (!alleleMatches(allele, selector)
                || !selector.path("variantType").asText().equals(
                evidenceContext.path("variant").path("variantType").asText())
                || !selector.path("requiredAssemblyLineage").asText().equals(
                evidenceContext.path("variant").path("assemblyLineage")
                        .path("mappingStatus").asText())) {
            return notReady("CONTEXT_ALLELE_MISMATCH", evidenceContext, atomicEvidence);
        }
        if (!selector.path("gene").path("hgncId").asText().equals(
                evidenceContext.path("gene").path("hgncId").asText())
                || !selector.path("gene").path("symbol").asText().equals(
                evidenceContext.path("gene").path("symbol").asText())) {
            return notReady("CONTEXT_HGNC_MISMATCH", evidenceContext, atomicEvidence);
        }
        if (!selector.path("disease").path("mondoId").asText().equals(
                evidenceContext.path("disease").path("mondoId").asText())) {
            return notReady("CONTEXT_MONDO_MISMATCH", evidenceContext, atomicEvidence);
        }
        if (!selector.path("moi").asText().equals(evidenceContext.path("moi").asText())) {
            return notReady("CONTEXT_MOI_MISMATCH", evidenceContext, atomicEvidence);
        }
        JsonNode selectedTranscript = selector.path("selectedTranscript");
        if (!selectedTranscript.path("accession").asText().equals(
                evidenceContext.path("transcript").path("accession").asText())
                || selectedTranscript.path("version").asInt(-1) !=
                evidenceContext.path("transcript").path("version").asInt(-2)
                || !selectedTranscript.path("selectionPolicy").asText().equals(
                evidenceContext.path("transcript").path("selectionPolicy").asText())
                || !selectedTranscript.path("annotationSourceVersion").asText().equals(
                evidenceContext.path("transcript").path("annotationSourceVersion").asText())) {
            return notReady("CONTEXT_TRANSCRIPT_MISMATCH", evidenceContext, atomicEvidence);
        }

        String contextId = evidenceContext.path("contextId").asText();
        if (!"atomic-evidence-v2".equals(atomicEvidence.path("schemaVersion").asText())
                || !profile.path("canonicalizationProfile").asText().equals(
                atomicEvidence.path("canonicalizationProfile").asText())
                || contextId.isEmpty()
                || !contextId.equals(atomicEvidence.path("contextId").asText())
                || !profile.path("vepInput").path("requiredEvidenceType").asText().equals(
                atomicEvidence.path("evidenceType").asText())) {
            return notReady("VEP_CONTEXT_MISMATCH", evidenceContext, atomicEvidence);
        }

        JsonNode vep = profile.path("vepInput");
        if (!vep.path("resourceId").asText().equals(
                atomicEvidence.path("source").path("sourceId").asText())
                || !vep.path("resourceVersion").asText().equals(
                atomicEvidence.path("source").path("resourceVersion").asText())
                || !vep.path("identityFingerprintSha256").asText().equals(
                atomicEvidence.path("source").path("resourceSha256").asText())
                || !vep.path("producerId").asText().equals(
                atomicEvidence.path("provenance").path("producerId").asText())
                || !vep.path("producerVersion").asText().equals(
                atomicEvidence.path("provenance").path("producerVersion").asText())) {
            return notReady("VEP_SOURCE_IDENTITY_MISMATCH", evidenceContext, atomicEvidence);
        }
        if (!vep.path("requiredAxes").path("resourceExecution").asText().equals(
                atomicEvidence.path("resourceExecution").path("status").asText())) {
            return notReady("VEP_EXECUTION_NOT_SUCCEEDED", evidenceContext, atomicEvidence);
        }
        if (!vep.path("requiredAxes").path("resourceAvailability").asText().equals(
                atomicEvidence.path("resourceAvailability").path("status").asText())) {
            return notReady("VEP_RESOURCE_NOT_AVAILABLE", evidenceContext, atomicEvidence);
        }
        if (!vep.path("requiredAxes").path("queryOutcome").asText().equals(
                atomicEvidence.path("queryOutcome").path("status").asText())) {
            return notReady("VEP_QUERY_NOT_HIT", evidenceContext, atomicEvidence);
        }
        if (!vep.path("requiredAxes").path("resourceApplicability").asText().equals(
                atomicEvidence.path("resourceApplicability").path("status").asText())) {
            return notReady("VEP_RESOURCE_NOT_APPLICABLE", evidenceContext, atomicEvidence);
        }

        Measurement transcript = measurement(atomicEvidence,
                vep.path("requiredMeasurements").path("selectedTranscript").asText());
        if (transcript.count == 0) {
            return notReady("VEP_SELECTED_TRANSCRIPT_MISSING", evidenceContext, atomicEvidence);
        }
        if (transcript.count != 1) {
            return notReady("VEP_SELECTED_TRANSCRIPT_AMBIGUOUS", evidenceContext, atomicEvidence);
        }
        if (!selectedTranscript.path("versionedAccession").asText().equals(
                transcript.value.path("rawValue").asText())) {
            return notReady("VEP_SELECTED_TRANSCRIPT_MISMATCH", evidenceContext, atomicEvidence);
        }

        Measurement consequence = measurement(atomicEvidence,
                vep.path("requiredMeasurements").path("consequence").asText());
        if (consequence.count != 1
                || !containsConsequence(profile.path("selector").path("allowedConsequences"),
                consequence.value.path("rawValue").asText())) {
            return notReady("VEP_CONSEQUENCE_NOT_ALLOWED", evidenceContext, atomicEvidence);
        }

        Measurement exon = measurement(atomicEvidence,
                vep.path("requiredMeasurements").path("exon").asText());
        if (exon.count == 0) {
            return notReady("VEP_EXON_MISSING", evidenceContext, atomicEvidence);
        }
        if (exon.count != 1) {
            return notReady("VEP_EXON_AMBIGUOUS", evidenceContext, atomicEvidence);
        }
        String rawExon = exon.value.path("rawValue").asText(null);
        String normalizedExon = exon.value.path("normalizedValue").asText(null);
        if (rawExon == null || normalizedExon == null || !rawExon.equals(normalizedExon)) {
            return notReady("VEP_EXON_MALFORMED", evidenceContext, atomicEvidence);
        }
        Matcher parsed = EXON.matcher(normalizedExon);
        if (!parsed.matches()) {
            return notReady("VEP_EXON_MALFORMED", evidenceContext, atomicEvidence);
        }
        int ordinal;
        int total;
        try {
            ordinal = Integer.parseInt(parsed.group(1));
            total = Integer.parseInt(parsed.group(2));
        } catch (NumberFormatException invalidExon) {
            return notReady("VEP_EXON_MALFORMED", evidenceContext, atomicEvidence);
        }
        if (ordinal > total) {
            return notReady("VEP_EXON_MALFORMED", evidenceContext, atomicEvidence);
        }
        JsonNode allowedExon = selector.path("allowedExon");
        if (ordinal != allowedExon.path("ordinal").asInt(-1)
                || total != allowedExon.path("total").asInt(-1)
                || !normalizedExon.equals(allowedExon.path("rawValue").asText())) {
            return notReady("VEP_EXON_NOT_NMD_READY", evidenceContext, atomicEvidence);
        }
        return new Result("INPUT_READY", "PREDICTED_NMD", null,
                PROFILE_ID, PROFILE_VERSION, profileSha256, contextId,
                atomicEvidence.path("evidenceId").asText(null));
    }

    public String getProfileSha256() {
        return profileSha256;
    }

    public ObjectNode getProfile() {
        return profile.deepCopy();
    }

    private Result notReady(String reasonCode,
                            JsonNode evidenceContext,
                            JsonNode atomicEvidence) {
        return new Result("INPUT_NOT_READY", "NOT_EVALUATED", reasonCode,
                PROFILE_ID, PROFILE_VERSION, profileSha256,
                evidenceContext == null ? null : evidenceContext.path("contextId").asText(null),
                atomicEvidence == null ? null : atomicEvidence.path("evidenceId").asText(null));
    }

    private String profileIssue(ObjectNode value, String actualSha256) {
        if (!PROFILE_CANONICAL_SHA256.equals(actualSha256)
                || !PROFILE_ID.equals(value.path("profileId").asText())
                || !PROFILE_VERSION.equals(value.path("profileVersion").asText())) {
            return "PROFILE_IDENTITY_MISMATCH";
        }
        JsonNode activation = value.path("activation");
        if (!"INPUT_ONLY".equals(value.path("status").asText())
                || !activation.path("inputResolutionEnabled").asBoolean(false)
                || activation.path("evaluationEnabled").asBoolean(true)
                || activation.path("patientFlowEnabled").asBoolean(true)
                || activation.path("classificationEnabled").asBoolean(true)
                || value.path("fixedInputDecision").path("pvs1CriterionEvaluationImplemented")
                .asBoolean(true)
                || value.path("fixedInputDecision").path("strengthAssigned").asBoolean(true)) {
            return "PROFILE_SCOPE_INVALID";
        }
        return null;
    }

    private boolean scopeMatches(JsonNode scope) {
        return "GERMLINE".equals(scope.path("analysisDomain").asText())
                && "MENDELIAN".equals(scope.path("diseaseModel").asText())
                && "SMALL_SEQUENCE_VARIANT".equals(scope.path("variantScope").asText());
    }

    private boolean alleleMatches(JsonNode allele, JsonNode selector) {
        JsonNode expected = selector.path("allele");
        return selector.path("assembly").asText().equals(allele.path("assembly").asText())
                && expected.path("contig").asText().equals(allele.path("contig").asText())
                && expected.path("position").asInt(-1) == allele.path("position").asInt(-2)
                && expected.path("reference").asText().equals(allele.path("reference").asText())
                && expected.path("alternate").asText().equals(allele.path("alternate").asText());
    }

    private boolean contains(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) return true;
        }
        return false;
    }

    private boolean containsConsequence(JsonNode allowed, String observed) {
        for (String term : observed.split("&")) {
            if (contains(allowed, term)) return true;
        }
        return false;
    }

    private Measurement measurement(JsonNode evidence, String metricId) {
        JsonNode found = mapper.missingNode();
        int count = 0;
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                count++;
                found = value;
            }
        }
        return new Measurement(count, found);
    }

    private static final class Measurement {
        private final int count;
        private final JsonNode value;

        private Measurement(int count, JsonNode value) {
            this.count = count;
            this.value = value;
        }
    }

    public static final class Result {
        private final String status;
        private final String nmdPrediction;
        private final String reasonCode;
        private final String profileId;
        private final String profileVersion;
        private final String profileSha256;
        private final String contextId;
        private final String evidenceId;

        private Result(String status,
                       String nmdPrediction,
                       String reasonCode,
                       String profileId,
                       String profileVersion,
                       String profileSha256,
                       String contextId,
                       String evidenceId) {
            this.status = status;
            this.nmdPrediction = nmdPrediction;
            this.reasonCode = reasonCode;
            this.profileId = profileId;
            this.profileVersion = profileVersion;
            this.profileSha256 = profileSha256;
            this.contextId = contextId;
            this.evidenceId = evidenceId;
        }

        public String getStatus() {
            return status;
        }

        public String getNmdPrediction() {
            return nmdPrediction;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String getProfileId() {
            return profileId;
        }

        public String getProfileVersion() {
            return profileVersion;
        }

        public String getProfileSha256() {
            return profileSha256;
        }

        public String getContextId() {
            return contextId;
        }

        public String getEvidenceId() {
            return evidenceId;
        }
    }
}
