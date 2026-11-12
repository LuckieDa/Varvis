package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.trio.CaseFamilyObservation;

/** Explicit PM6 bytes boundary. Does not widen the shared facts or the PS2 assessment schema. */
public final class Pm6DeNovoBundle {
    private static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("PM6_");
    private final ObjectNode snapshot;
    private final String rawSha256;
    private Pm6DeNovoBundle(ObjectNode snapshot, String rawSha256) {
        this.snapshot = snapshot.deepCopy(); this.rawSha256 = rawSha256;
    }
    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    public String rawSha256() { return rawSha256; }
    public List<CaseFamilyObservation> observations() {
        var observations = new java.util.ArrayList<CaseFamilyObservation>();
        for (JsonNode o : snapshot.path("observations")) observations.add(new CaseFamilyObservation((ObjectNode) o.path("facts")));
        return List.copyOf(observations);
    }
    public static Pm6DeNovoBundle load(ObjectMapper mapper, byte[] bytes, String rawPin, String canonicalPin) {
        if (!FamilyDeNovoJson.sha256(bytes).equals(rawPin)) throw JSON.invalid("RAW_PIN_MISMATCH");
        ObjectNode root = JSON.parse(mapper, bytes);
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        if (!adapter.canonicalSha256(root).equals(canonicalPin)) throw JSON.invalid("CANONICAL_PIN_MISMATCH");
        JSON.shape(root, "schemaVersion:s context:o observations:a policy:o catalog:o catalogSha256:s registryBinding:o");
        JSON.equal(root, "schemaVersion", "pm6-denovo-core-bundle-v1");
        adapter.validateVersionedContext(root.path("context"));
        for (JsonNode o : root.path("observations")) {
            JSON.shape(o, "facts:o parentageBasis:o assessment:o"); JSON.facts(o.path("facts")); basis(o.path("parentageBasis"));
            JsonNode a = o.path("assessment");
            JSON.shape(a, "schemaVersion:s assessmentId:s assessmentVersion:s factsSha256:s parentageBasisSha256:s quality:o parentage:o clinical:o scope:o contentSha256:s");
            JSON.equal(a, "schemaVersion", "pm6-case-family-assessment-v1");
            for (String part : List.of("quality", "parentage", "clinical", "scope")) review(a.path(part));
        }
        JsonNode p = root.path("policy");
        JSON.shape(p, "schemaVersion:s policyId:s policyVersion:s mode:s criterionIds:a scope:o review:o factsSha256:s parentageBasisSha256:s assessmentSha256:s authoritySourceIds:a scientificSources:a");
        JSON.equal(p, "schemaVersion", "pm6-reviewed-denovo-policy-v1");
        JSON.strings(p.path("criterionIds")); JSON.strings(p.path("authoritySourceIds")); JSON.scope(p.path("scope"));
        if (p.path("criterionIds").size() != 1 || !Pm6DeNovoCriterionEvaluator.CRITERION.equals(p.at("/criterionIds/0").asText())) throw JSON.invalid("POLICY_CRITERION_INVALID");
        JSON.shape(p.path("review"), "status:s reviewId:s"); JSON.enumeration(p.path("review"), "status", "REVIEWED_IMPLEMENTED UNREVIEWED DISABLED");
        for (JsonNode s : p.path("scientificSources")) JSON.shape(s, "sourceId:s version:s url:s sha256:s");
        JSON.catalog(root.path("catalog"));
        JSON.shape(root.path("registryBinding"), "registryId:s registrySha256:s registryClasspathResource:s");
        return new Pm6DeNovoBundle(root, rawPin);
    }
    private static void review(JsonNode n) {
        JSON.shape(n, "status:s reviewer:s date:s basis:s sourceIds:a");
        JSON.enumeration(n, "status", "REVIEWED UNREVIEWED UNKNOWN CONFLICT"); JSON.strings(n.path("sourceIds"));
    }
    private static void basis(JsonNode b) {
        JSON.shape(b, "schemaVersion:s factsSha256:s binding:o relations:a sources:a");
        JSON.equal(b, "schemaVersion", "pm6-parentage-basis-v1"); JSON.binding(b.path("binding"));
        for (JsonNode r : b.path("relations")) {
            JSON.shape(r, "binding:o parentRole:s parentMemberId:s parentSampleId:s probandMemberId:s probandSampleId:s testStatus:s method:s result:s limitations:s conclusion:s review:o sourceIds:a");
            JSON.binding(r.path("binding")); JSON.enumeration(r, "parentRole", "FATHER MOTHER");
            JSON.enumeration(r, "testStatus", "COMPLETED NOT_PERFORMED UNKNOWN INVALID CONFLICT");
            JSON.enumeration(r, "conclusion", "CONFIRMED ASSUMED EXCLUDED UNKNOWN CONFLICT UNRESOLVED");
            JSON.strings(r.path("sourceIds")); review(r.path("review"));
        }
        for (JsonNode s : b.path("sources")) JSON.shape(s, "sourceId:s recordId:s version:s locator:s acquiredAt:s rawUtf8:s rawSha256:s canonicalSha256:s");
    }
}
