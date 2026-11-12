package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Independent strict bytes entry point. Review pins cover whole parts, including original reports. */
public final class Bs4NonSegregationBundle {
    static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("BS4_");
    static final List<String> PARTS = List.of("facts", "nonSegregationReview", "diseaseApplicability", "locusAttribution");
    private final ObjectNode value;
    private final String rawSha256;
    private Bs4NonSegregationBundle(ObjectNode value, String raw) {
        this.value = value.deepCopy();
        rawSha256 = raw;
    }
    public ObjectNode snapshot() {
        return value.deepCopy();
    }
    public String rawSha256() {
        return rawSha256;
    }
    public List<NuclearFamilyObservation> observations() {
        var result = new java.util.ArrayList<NuclearFamilyObservation>();
        for (JsonNode o : value.path("observations")) result.add(new NuclearFamilyObservation((ObjectNode) o.path("facts"), JSON));
        return List.copyOf(result);
    }
    public static Bs4NonSegregationBundle load(ObjectMapper ignored, byte[] bytes, String rawPin, String canonicalPin) {
        if (!FamilyDeNovoJson.sha256(bytes).equals(rawPin)) throw JSON.invalid("RAW_PIN_MISMATCH");
        ObjectMapper m = new ObjectMapper();
        ObjectNode root = JSON.parse(m, bytes);
        String canonical;
        try {
            canonical = new RuntimeEvidenceV2Adapter(m).canonicalSha256(root);
        }
        catch (IllegalArgumentException invalid) {
            throw JSON.invalid("CANONICAL_INPUT_INVALID");
        }
        if (!canonical.equals(canonicalPin)) throw JSON.invalid("CANONICAL_PIN_MISMATCH");
        JSON.shape(root, "schemaVersion:s context:o observations:a policy:o catalog:o catalogSha256:s registryBinding:o");
        JSON.equal(root, "schemaVersion", "bs4-nonsegregation-core-bundle-v1");
        for (JsonNode o : root.path("observations")) {
            JSON.shape(o, "facts:o nonSegregationReview:o diseaseApplicability:o locusAttribution:o assessment:o");
            factsShape(o.path("facts"));
            var r = o.path("nonSegregationReview");
            JSON.shape(r, "schemaVersion:s binding:o rosterStatus:s memberReviews:a declaredNonSegregatingMembers:a review:o sources:a");
            JSON.equal(r, "schemaVersion", "bs4-nonsegregation-review-v1");
            binding(r.path("binding"));
            review(r.path("review"));
            sources(r.path("sources"));
            JSON.strings(r.path("declaredNonSegregatingMembers"));
            for (var member : r.path("memberReviews")) {
                JSON.shape(member, "memberId:s sourcePersonId:s independence:s clinicalStatus:s detectionStatus:s exclusionReason:s negativeTest:o sourceIds:a");
                JSON.strings(member.path("sourceIds"));
                JSON.shape(member.path("negativeTest"), "memberId:s sampleId:s alleleId:s method:s coverage:s capability:s result:s sourceIds:a");
                JSON.strings(member.path("negativeTest").path("sourceIds"));
            }
            var d = o.path("diseaseApplicability");
            JSON.shape(d, "schemaVersion:s binding:o mechanism:s geneDiseaseValidity:s validityBasis:s validityIndependentOfFamily:b alternativeExplanations:a linkageRequired:s conditionMode:s conditions:a affectedMembers:a review:o sources:a");
            JSON.equal(d, "schemaVersion", "bs4-disease-applicability-v1");
            binding(d.path("binding"));
            review(d.path("review"));
            sources(d.path("sources"));
            for (var alternative : d.path("alternativeExplanations")) {
                JSON.shape(alternative, "memberId:s kind:s assessment:s investigation:o method:s basis:s sourceIds:a");
                JSON.strings(alternative.path("sourceIds"));
                JSON.shape(alternative.path("investigation"), "memberId:s caseId:s mondoId:s evaluationComplete:b alternativeDetected:b unresolvedFindings:a");
                JSON.strings(alternative.path("investigation").path("unresolvedFindings"));
            }
            for (var condition : d.path("conditions")) JSON.shape(condition, "memberId:s kind:s requirement:s patientFact:s conclusion:s");
            for (var member : d.path("affectedMembers")) {
                JSON.shape(member, "memberId:s mondoId:s mechanism:s conclusion:s sourceIds:a");
                JSON.strings(member.path("sourceIds"));
            }
            var l = o.path("locusAttribution");
            JSON.shape(l, "schemaVersion:s binding:o method:s coverageScope:s coverageStatus:s candidateRosterStatus:s candidates:a allocation:s usedEvidenceStatus:s usedEvidence:a review:o sources:a");
            JSON.equal(l, "schemaVersion", "bs4-locus-attribution-v1");
            binding(l.path("binding"));
            review(l.path("review"));
            sources(l.path("sources"));
            for (var c : l.path("candidates")) {
                JSON.shape(c, "candidateId:s alleleId:s hgncId:s disposition:s basis:s sourceIds:a");
                JSON.strings(c.path("sourceIds"));
            }
            for (var e : l.path("usedEvidence")) {
                JSON.shape(e, "criterionId:s evidenceGroupId:s status:s basis:s sourceIds:a");
                JSON.strings(e.path("sourceIds"));
            }
            var a = o.path("assessment");
            JSON.shape(a, "schemaVersion:s assessmentId:s assessmentVersion:s partPins:o review:o contentSha256:s");
            JSON.equal(a, "schemaVersion", "bs4-assessment-v1");
            pins(a.path("partPins"));
            review(a.path("review"));
        }
        var p = root.path("policy");
        JSON.shape(p, "schemaVersion:s policyId:s policyVersion:s mode:s criterionIds:a scope:o review:o partPins:o assessmentSha256:s authoritySourceIds:a scientificSources:a qualityProfile:s");
        JSON.equal(p, "schemaVersion", "bs4-reviewed-nonsegregation-policy-v1");
        JSON.scope(p.path("scope"));
        pins(p.path("partPins"));
        JSON.shape(p.path("review"), "status:s reviewId:s");
        JSON.strings(p.path("criterionIds"));
        JSON.strings(p.path("authoritySourceIds"));
        for (var source : p.path("scientificSources")) JSON.shape(source, "sourceId:s version:s url:s sha256:s publicationDate:s publicationStatus:s normativeEligibility:s authorityCutoff:s");
        JSON.catalog(root.path("catalog"));
        JSON.shape(root.path("registryBinding"), "registryId:s registrySha256:s registryClasspathResource:s");
        return new Bs4NonSegregationBundle(root, rawPin);
    }
    static void pins(JsonNode n) {
        JSON.shape(n, "facts:s nonSegregationReview:s diseaseApplicability:s locusAttribution:s");
    }
    static void binding(JsonNode n) { new NuclearFamilyShape(JSON).binding(n); }
    static void review(JsonNode n) { new NuclearFamilyShape(JSON).review(n); }
    static void sources(JsonNode n) { new NuclearFamilyShape(JSON).sources(n); }
    static void factsShape(JsonNode n) { new NuclearFamilyShape(JSON).factsShape(n); }
}
