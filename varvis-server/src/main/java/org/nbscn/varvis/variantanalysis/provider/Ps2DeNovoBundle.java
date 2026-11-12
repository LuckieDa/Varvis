package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.trio.CaseFamilyObservation;

/** Bounded, pinned bytes boundary. No business preparation token, I/O supplier or verdict input. */
public final class Ps2DeNovoBundle {
    private static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("PS2_");
    public static final int MAX_BYTES = 1_048_576;
    private final ObjectNode snapshot;
    private final String rawSha256;
    private Ps2DeNovoBundle(ObjectNode snapshot, String rawSha256) {
        this.snapshot = snapshot.deepCopy(); this.rawSha256 = rawSha256;
    }
    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    public String rawSha256() { return rawSha256; }
    public List<CaseFamilyObservation> observations() {
        var result = new java.util.ArrayList<CaseFamilyObservation>();
        for (JsonNode observation : snapshot.path("observations"))
            result.add(new CaseFamilyObservation((ObjectNode) observation.path("facts")));
        return List.copyOf(result);
    }
    public static Ps2DeNovoBundle load(ObjectMapper mapper, byte[] bytes, String rawPin, String canonicalPin) {
        if (!sha256(bytes).equals(rawPin)) throw invalid("RAW_PIN_MISMATCH");
        ObjectNode root = parse(mapper, bytes);
        if (!new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(root).equals(canonicalPin))
            throw invalid("CANONICAL_PIN_MISMATCH");
        shape(root, "schemaVersion:s context:o observations:a policy:o catalog:o catalogSha256:s registryBinding:o");
        JSON.equal(root, "schemaVersion", "ps2-denovo-core-bundle-v1");
        new RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(root.path("context"));
        for (JsonNode observation : root.path("observations")) {
            shape(observation, "facts:o assessment:o"); JSON.facts(observation.path("facts"));
            JsonNode a = observation.path("assessment");
            shape(a, "schemaVersion:s assessmentId:s assessmentVersion:s factsSha256:s quality:o parentage:o clinical:o scope:o contentSha256:s");
            JSON.equal(a, "schemaVersion", "case-family-assessment-v1");
            for (String part : List.of("quality", "parentage", "clinical", "scope")) {
                JsonNode review = a.path(part);
                shape(review, "status:s reviewer:s date:s basis:s sourceIds:a");
                JSON.enumeration(review, "status", "REVIEWED UNREVIEWED UNKNOWN CONFLICT"); JSON.strings(review.path("sourceIds"));
            }
        }
        policy(root.path("policy")); JSON.catalog(root.path("catalog"));
        shape(root.path("registryBinding"), "registryId:s registrySha256:s registryClasspathResource:s");
        return new Ps2DeNovoBundle(root, rawPin);
    }
    private static void policy(JsonNode n) {
        shape(n, "schemaVersion:s policyId:s policyVersion:s mode:s criterionIds:a scope:o review:o factsSha256:s assessmentSha256:s authoritySourceIds:a scientificSources:a");
        JSON.equal(n, "schemaVersion", "ps2-reviewed-denovo-policy-v1");
        JSON.strings(n.path("criterionIds")); JSON.strings(n.path("authoritySourceIds")); JSON.scope(n.path("scope"));
        if (n.path("criterionIds").size() != 1 || !"ACMG_AMP_2015:PS2".equals(n.path("criterionIds").path(0).asText()))
            throw invalid("POLICY_CRITERION_INVALID");
        shape(n.path("review"), "status:s reviewId:s");
        JSON.enumeration(n.path("review"), "status", "REVIEWED_IMPLEMENTED UNREVIEWED DISABLED");
        for (JsonNode s : n.path("scientificSources")) shape(s, "sourceId:s version:s url:s sha256:s");
    }
    static ObjectNode parse(ObjectMapper mapper, byte[] bytes) { return JSON.parse(mapper, bytes); }
    static void shape(JsonNode n, String spec) { JSON.shape(n, spec); }
    public static String sha256(byte[] bytes) { return FamilyDeNovoJson.sha256(bytes); }
    static IllegalArgumentException invalid(String reason) { return JSON.invalid(reason); }
}
