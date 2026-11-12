package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Opt-in BS2 bytes boundary. Clinical facts are preserved, never inferred or repaired. */
public final class Bs2HealthyAdultBundle {
    static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("BS2_");
    static final List<String> PARTS = List.of("facts", "healthyAdultReview", "diseaseApplicability", "evidenceUse");
    private final ObjectNode value;
    private final String raw;
    private Bs2HealthyAdultBundle(ObjectNode value, String raw) { this.value = value.deepCopy(); this.raw = raw; }
    public ObjectNode snapshot() { return value.deepCopy(); }
    public String rawSha256() { return raw; }
    public static Bs2HealthyAdultBundle load(ObjectMapper ignored, byte[] bytes, String rawPin, String canonicalPin) {
        if (!FamilyDeNovoJson.sha256(bytes).equals(rawPin)) throw JSON.invalid("RAW_PIN_MISMATCH");
        var m = new ObjectMapper(); var root = JSON.parse(m, bytes);
        String canonical;
        try { canonical = new RuntimeEvidenceV2Adapter(m).canonicalSha256(root); }
        catch (IllegalArgumentException invalid) { throw JSON.invalid("CANONICAL_INPUT_INVALID"); }
        if (!canonical.equals(canonicalPin)) throw JSON.invalid("CANONICAL_PIN_MISMATCH");
        JSON.shape(root, "schemaVersion:s context:o observations:a policy:o catalog:o catalogSha256:s registryBinding:o");
        JSON.equal(root, "schemaVersion", "bs2-healthy-adult-core-bundle-v1");
        for (var o : root.path("observations")) {
            JSON.shape(o, "facts:o healthyAdultReview:o diseaseApplicability:o evidenceUse:o assessment:o");
            JSON.facts(o.path("facts"));
            for (String part : PARTS.subList(1, 4)) {
                JSON.binding(o.path(part).path("binding")); review(o.path(part).path("review"));
                new NuclearFamilyShape(JSON).sources(o.path(part).path("sources"));
            }
            var r = o.path("healthyAdultReview");
            JSON.shape(r, "schemaVersion:s binding:o caseId:s occurrenceId:s probandMemberId:s assessedAt:s ageYears:?i ageUnit:s healthStatus:s sourceType:s mechanism:s subtype:s populationId:s sex:s definitionId:s definitionVersion:s rosterStatus:s findings:a masking:a review:o sources:a");
            JSON.equal(r, "schemaVersion", "bs2-healthy-adult-review-v1");
            for (var f : r.path("findings")) {
                JSON.shape(f, "findingId:s featureId:s methodId:s result:s examination:s applicability:s reliability:s observedAt:s ageYears:?i sourceIds:a");
                JSON.strings(f.path("sourceIds"));
            }
            for (var f : r.path("masking")) { JSON.shape(f, "kind:s status:s basis:s sourceIds:a"); JSON.strings(f.path("sourceIds")); }
            var d = o.path("diseaseApplicability");
            JSON.shape(d, "schemaVersion:s binding:o mechanism:s subtype:s validity:s independence:s excludedAlleleIds:a excludedPersonIds:a model:s onset:s fullyPenetrantAgeYears:?i ageUnit:s sexScope:a populationIds:a variantClass:s definition:o review:o sources:a");
            JSON.equal(d, "schemaVersion", "bs2-disease-applicability-v1");
            for (String k : List.of("excludedAlleleIds", "excludedPersonIds", "sexScope", "populationIds")) JSON.strings(d.path(k));
            var definition = d.path("definition");
            JSON.shape(definition, "definitionId:s version:s mode:s requirements:a");
            for (var q : definition.path("requirements")) JSON.shape(q, "featureId:s methodId:s expectedResult:s ageMinimumYears:i ageMaximumYears:i validityDays:i");
            var u = o.path("evidenceUse");
            JSON.shape(u, "schemaVersion:s binding:o personId:s caseId:s occurrenceId:s familyId:s evidenceGroupId:s clinicalSourceIds:a rosterStatus:s uses:a review:o sources:a");
            JSON.equal(u, "schemaVersion", "bs2-evidence-use-v1"); JSON.strings(u.path("clinicalSourceIds"));
            for (var e : u.path("uses")) {
                JSON.shape(e, "criterionId:s personId:s alleleId:s clinicalSourceIds:a status:s basis:s sourceIds:a");
                JSON.strings(e.path("clinicalSourceIds")); JSON.strings(e.path("sourceIds"));
            }
            var a = o.path("assessment");
            JSON.shape(a, "schemaVersion:s assessmentId:s assessmentVersion:s partPins:o factsReview:o review:o contentSha256:s");
            JSON.equal(a, "schemaVersion", "bs2-assessment-v1"); pins(a.path("partPins")); review(a.path("review")); review(a.path("factsReview"));
        }
        var p = root.path("policy");
        JSON.shape(p, "schemaVersion:s policyId:s policyVersion:s mode:s criterionIds:a scope:o review:o partPins:o assessmentSha256:s authoritySourceIds:a scientificSources:a qualityProfile:s");
        JSON.equal(p, "schemaVersion", "bs2-reviewed-healthy-adult-policy-v1"); JSON.scope(p.path("scope")); pins(p.path("partPins"));
        JSON.shape(p.path("review"), "status:s reviewId:s"); JSON.strings(p.path("criterionIds")); JSON.strings(p.path("authoritySourceIds"));
        for (var s : p.path("scientificSources")) JSON.shape(s, "sourceId:s version:s url:s sha256:s publicationDate:s publicationStatus:s normativeEligibility:s authorityCutoff:s");
        JSON.catalog(root.path("catalog")); JSON.shape(root.path("registryBinding"), "registryId:s registrySha256:s registryClasspathResource:s");
        return new Bs2HealthyAdultBundle(root, rawPin);
    }
    private static void pins(JsonNode n) { JSON.shape(n, "facts:s healthyAdultReview:s diseaseApplicability:s evidenceUse:s"); }
    private static void review(JsonNode n) { new NuclearFamilyShape(JSON).review(n); }
}
